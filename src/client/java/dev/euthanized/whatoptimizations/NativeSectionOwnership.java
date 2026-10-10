package dev.euthanized.whatoptimizations;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-section ownership state machine for the native terrain pipeline.
 *
 * <p>A section (keyed by {@code BlockPos.asLong(sectionPos.origin())} —
 * world-space block origins, never shifted section indices) transitions:
 *
 * <pre>
 *   VANILLA ──shadow capture──▶ PENDING ──mesh uploaded──▶ ACTIVE ──rebuild──▶ ACTIVE (new job)
 *      ▲                        │                            │
 *      │                        └── failure ──▶ FAILED ────────┘ (re-dirty → vanilla recompiles)
 *      └──────────── kill switch / level unload (re-dirty all) ──┘
 * </pre>
 *
 * <p>Vanilla geometry for a section is only dropped (empty rendered layers via
 * compile takeover) while the section is ACTIVE, i.e. after a native mesh for
 * that exact section has been staged and uploaded to the GPU store. The
 * takeover compile completes at least one frame after ACTIVE was set, and the
 * native mesh has been drawable since the frame after staging, so the native
 * path always draws before vanilla's mesh disappears — a section can never go
 * invisible because vanilla was suppressed early.
 */
public final class NativeSectionOwnership {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/ownership");

    public enum State {
        VANILLA,
        PENDING,
        ACTIVE,
        FAILED
    }

    static final int LAYER_SOLID_BIT = 1;
    static final int LAYER_CUTOUT_BIT = 2;
    static final int LAYER_TRANSLUCENT_BIT = 4;
    static final int ALL_LAYERS = LAYER_SOLID_BIT | LAYER_CUTOUT_BIT | LAYER_TRANSLUCENT_BIT;

    private static final int MAX_ENTRIES = 131_072;

    public static final class Entry {
        volatile State state = State.VANILLA;
        /** Layer bits whose native mesh is live in the GPU store. */
        volatile int activeLayers;
        /** Latest compile generation seen by the worker threads. */
        volatile long generation;
        /** Ticket of the in-flight native job, or 0. */
        volatile long pendingTicket;
        volatile long pendingGeneration;
        volatile boolean recompileQueued;
        /** Consecutive takeover-capture failures; at the cap the section stays vanilla. */
        volatile int takeoverFailures;
        /** Permanently unsupported: no capture, no takeover, vanilla forever. */
        volatile boolean unsupported;
    }

    private static final ConcurrentHashMap<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
    /** Section keys (packed origin longs) that need a vanilla recompile. */
    private static final ConcurrentLinkedQueue<Long> RECOMPILE_QUEUE = new ConcurrentLinkedQueue<>();

    private static final AtomicLong ACTIVATIONS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong STALE_RESULTS = new AtomicLong();
    private static final AtomicLong EVICTIONS = new AtomicLong();
    private static final AtomicLong LAST_FAILURE_LOG = new AtomicLong();

    private NativeSectionOwnership() {
    }

    static long sectionKey(SectionPos sectionPos) {
        BlockPos origin = sectionPos.origin();
        return BlockPos.asLong(origin.getX(), origin.getY(), origin.getZ());
    }

    public static long sectionKey(long sectionNode) {
        SectionPos sectionPos = SectionPos.of(sectionNode);
        BlockPos origin = sectionPos.origin();
        return BlockPos.asLong(origin.getX(), origin.getY(), origin.getZ());
    }

    static long sectionKey(BlockPos renderOrigin) {
        return BlockPos.asLong(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
    }

    static Entry entry(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        if (entry == null) {
            Entry created = new Entry();
            entry = ENTRIES.putIfAbsent(sectionKey, created);
            if (entry == null) {
                entry = created;
            }
        }
        return entry;
    }

    static State state(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        return entry == null ? State.VANILLA : entry.state;
    }

    /** True when the section must never be captured or taken over again. */
    static boolean isUnsupported(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        return entry != null && entry.unsupported;
    }

    static boolean isActive(long sectionKey, int layerBit) {
        Entry entry = ENTRIES.get(sectionKey);
        return entry != null && entry.state == State.ACTIVE && (entry.activeLayers & layerBit) != 0;
    }

    /**
     * Worker-thread compile hook: bumps the generation and records the
     * in-flight job. Returns false when the job must not be submitted (the
     * section is being torn down or the queue is full — the caller handles
     * that separately).
     */
    static boolean beginJob(long sectionKey, long generation) {
        Entry entry = entry(sectionKey);
        entry.generation = generation;
        if (entry.state == State.VANILLA || entry.state == State.PENDING || entry.state == State.FAILED) {
            entry.state = State.PENDING;
        }
        return true;
    }

    static void setPendingTicket(long sectionKey, long ticket, long generation) {
        Entry entry = entry(sectionKey);
        entry.pendingTicket = ticket;
        entry.pendingGeneration = generation;
    }

    static boolean isLatestGeneration(long sectionKey, long generation) {
        Entry entry = ENTRIES.get(sectionKey);
        return entry != null && entry.generation == generation;
    }

    /**
     * Render thread: a native mesh was staged and uploaded for these layers.
     *
     * <p>The vanilla re-dirty is queued only when transitioning INTO ACTIVE:
     * the recompile makes vanilla drop its mesh via a takeover compile, which
     * must happen exactly once per ownership (and once more after a failure
     * recovery rebuilt vanilla's mesh). Rebuilds of an already-ACTIVE section
     * keep drawing the previous native mesh and must not recompile again —
     * queueing unconditionally would recompile every section every frame.
     */
    static void activate(long sectionKey, int layerMask) {
        Entry entry = entry(sectionKey);
        boolean wasActive = entry.state == State.ACTIVE;
        entry.activeLayers = layerMask & ALL_LAYERS;
        entry.state = State.ACTIVE;
        entry.pendingTicket = 0L;
        ACTIVATIONS.incrementAndGet();
        if (!wasActive && !entry.recompileQueued) {
            entry.recompileQueued = true;
            RECOMPILE_QUEUE.add(sectionKey);
        }
    }

    /** True when the section still has a drawable native mesh (ACTIVE, or FAILED with a live mesh). */
    public static boolean isDrawable(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        return entry != null
                && (entry.state == State.ACTIVE || entry.state == State.FAILED)
                && entry.activeLayers != 0;
    }

    /**
     * Render thread: the native path failed for this section. The section
     * falls back to vanilla compilation (a re-dirty is queued), but any
     * previously uploaded native mesh keeps drawing so the section can never
     * go invisible while vanilla rebuilds. The duplicate native+vanilla draw
     * for a frame or two is benign (identical geometry; the native pass runs
     * after vanilla and wins the equal-depth test).
     */
    static void markFailed(long sectionKey, String reason) {
        Entry entry = ENTRIES.get(sectionKey);
        if (entry == null) {
            return;
        }
        if (entry.state != State.FAILED) {
            entry.state = State.FAILED;
            entry.pendingTicket = 0L;
            FAILURES.incrementAndGet();
            queueRecompile(sectionKey);
            long count = FAILURES.get();
            long previous = LAST_FAILURE_LOG.get();
            if (count == 1L || count - previous >= 256L) {
                LAST_FAILURE_LOG.set(count);
                LOGGER.warn("Native terrain failure for section {}: {} ({} failures so far)", sectionKey, reason, count);
            }
        }
    }

    static void noteStaleResult() {
        STALE_RESULTS.incrementAndGet();
    }

    /**
     * Worker thread: the takeover capture failed for this section. After
     * three consecutive takeover failures the section is permanently
     * unsupported and stays with vanilla instead of oscillating between
     * native and vanilla on every rebuild.
     */
    static void noteTakeoverFailure(long sectionKey) {
        Entry entry = entry(sectionKey);
        entry.takeoverFailures++;
        FAILURES.incrementAndGet();
        if (entry.takeoverFailures >= 3 && !entry.unsupported) {
            entry.unsupported = true;
            LOGGER.warn("Section {} is permanently unsupported after {} takeover capture failures; using vanilla",
                    sectionKey, entry.takeoverFailures);
        }
    }

    /** Worker thread: the takeover capture succeeded; reset the failure count. */
    static void noteTakeoverSuccess(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        if (entry != null) {
            entry.takeoverFailures = 0;
        }
    }

    /** Test-visible: drops queued vanilla recompiles without applying them. */
    static void drainRecompileQueue() {
        RECOMPILE_QUEUE.clear();
    }

    static void queueRecompile(long sectionKey) {
        RECOMPILE_QUEUE.add(sectionKey);
    }

    /**
     * Render thread: applies queued vanilla recompiles (activations and
     * failures). Vanilla's own compile then rebuilds the section mesh, which
     * also re-runs the capture through the normal hook.
     */
    public static void applyQueuedRecompiles() {
        Long key;
        while ((key = RECOMPILE_QUEUE.poll()) != null) {
            applyRecompile(key);
        }
    }

    private static void applyRecompile(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        if (entry != null) {
            entry.recompileQueued = false;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            // SectionPos coordinates from the packed block origin.
            int x = (int) (sectionKey >> 38);
            int y = (int) (sectionKey << 52 >> 52);
            int z = (int) (sectionKey << 26 >> 38);
            minecraft.levelExtractor.setSectionDirty(
                    SectionPos.blockToSectionCoord(x),
                    SectionPos.blockToSectionCoord(y),
                    SectionPos.blockToSectionCoord(z));
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not queue vanilla recompile for section {}", sectionKey, failure);
        }
    }

    /**
     * Worker/render thread: the section compiled to nothing; drop the native
     * mesh and return to VANILLA without queueing a recompile (there is no
     * geometry to rebuild on either side).
     */
    static void invalidate(long sectionKey) {
        Entry entry = ENTRIES.get(sectionKey);
        if (entry != null) {
            entry.state = State.VANILLA;
            entry.activeLayers = 0;
            entry.pendingTicket = 0L;
        }
    }

    /** Render thread: drops one section entirely (view area recycling). */
    public static void remove(long sectionKey) {
        Entry entry = ENTRIES.remove(sectionKey);
        if (entry != null && entry.state == State.ACTIVE) {
            // The recycled RenderSection no longer exists at this node; make
            // sure vanilla rebuilds whatever is there now.
            queueRecompile(sectionKey);
        }
    }

    /** Render thread: reverts everything to vanilla (kill switch / unload). */
    public static int clearAll() {
        int active = 0;
        for (Map.Entry<Long, Entry> mapEntry : ENTRIES.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (entry.state == State.ACTIVE || entry.state == State.PENDING) {
                if (entry.state == State.ACTIVE) {
                    active++;
                }
                entry.state = State.VANILLA;
                entry.activeLayers = 0;
                entry.pendingTicket = 0L;
                queueRecompile(mapEntry.getKey());
            }
        }
        return active;
    }

    static int entryCount() {
        return ENTRIES.size();
    }

    /** Test-visible: number of queued vanilla recompiles. */
    static int queuedRecompileCount() {
        return RECOMPILE_QUEUE.size();
    }

    public static int countByState(State wanted) {
        int count = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.state == wanted) {
                count++;
            }
        }
        return count;
    }

    static long activations() {
        return ACTIVATIONS.get();
    }

    static long failures() {
        return FAILURES.get();
    }

    public static long staleResults() {
        return STALE_RESULTS.get();
    }

    static long evictions() {
        return EVICTIONS.get();
    }

    /** Render thread: enforces the entry cap, evicting non-active entries first. */
    static void enforceCap() {
        if (ENTRIES.size() <= MAX_ENTRIES) {
            return;
        }
        int toRemove = ENTRIES.size() - MAX_ENTRIES + 4096;
        Iterator<Map.Entry<Long, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (toRemove > 0 && iterator.hasNext()) {
            Map.Entry<Long, Entry> mapEntry = iterator.next();
            if (mapEntry.getValue().state == State.VANILLA || mapEntry.getValue().state == State.FAILED) {
                iterator.remove();
                EVICTIONS.incrementAndGet();
                toRemove--;
            }
        }
        if (toRemove > 0) {
            iterator = ENTRIES.entrySet().iterator();
            while (toRemove > 0 && iterator.hasNext()) {
                Map.Entry<Long, Entry> mapEntry = iterator.next();
                if (mapEntry.getValue().state == State.ACTIVE) {
                    mapEntry.getValue().state = State.VANILLA;
                    mapEntry.getValue().activeLayers = 0;
                    NativeTerrainStore.get().removeSection(mapEntry.getKey());
                    queueRecompile(mapEntry.getKey());
                }
                iterator.remove();
                EVICTIONS.incrementAndGet();
                toRemove--;
            }
        }
    }
}
