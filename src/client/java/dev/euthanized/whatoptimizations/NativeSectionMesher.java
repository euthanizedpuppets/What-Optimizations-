package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import dev.euthanized.whatoptimizations.mixin.SectionCompilerResultsAccess;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native terrain pipeline driver.
 *
 * <p>Worker threads (vanilla section compile tasks) call {@link #onCompileHead}
 * and {@link #onCompileReturn}; the render thread calls {@link #pollCompleted}
 * once per frame. The pipeline:
 *
 * <ol>
 *   <li>First build of a section: vanilla compiles normally; at RETURN the
 *   finished per-layer {@code MeshData} vertex buffers are copied into a WOM2
 *   snapshot (shadow capture) and submitted to Rust.</li>
 *   <li>When the Rust job completes, the render thread stages the mesh into the
 *   GPU store, flips ownership to ACTIVE and queues a vanilla recompile so the
 *   next compile takes over (vanilla's per-layer meshing is skipped).</li>
 *   <li>Steady state: at HEAD of a compile for an ACTIVE section, the capture
 *   runs vanilla's own model/fluid tessellation with capturing consumers,
 *   submits a new WOM2 snapshot and returns a {@code Results} with empty
 *   rendered layers — vanilla draws nothing for the section, the native path
 *   draws its (already uploaded) mesh, and the old mesh keeps drawing until
 *   the new one is staged.</li>
 * </ol>
 *
 * <p>Every failure path leaves the section with vanilla: exceptions during
 * capture are swallowed (vanilla proceeds), Rust errors mark the section FAILED
 * and queue a vanilla recompile, and the kill switch reverts everything.
 */
public final class NativeSectionMesher {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/mesher");

    private static final int MAX_PENDING_JOBS = 32;
    private static final int OUTPUT_CAPACITY = 12 * 1024 * 1024;
    private static final int WOM2_MAGIC = 0x32_4D_4F_57;
    private static final int WOM2_VERSION = 2;

    private static final ThreadLocal<ByteBuffer> OUTPUT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(OUTPUT_CAPACITY).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<NativeSectionCapture.CapturedSection> DEBUG_CAPTURE =
            new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TOOK_OVER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("whatoptimizations.nativeMesher", "true"));
    private static volatile boolean DEBUG_MODE =
            Boolean.getBoolean("whatoptimizations.nativeMesher.debug");

    private static final ConcurrentMap<Long, PendingJob> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION_SEQUENCE = new AtomicLong();
    private static final AtomicLong SECTION_CALLBACKS = new AtomicLong();
    private static final AtomicLong SUBMITTED_JOBS = new AtomicLong();
    private static final AtomicLong COMPLETED_JOBS = new AtomicLong();
    private static final AtomicLong FAILED_SECTIONS = new AtomicLong();
    private static final AtomicLong CAPTURE_NANOS = new AtomicLong();
    private static final AtomicLong CAPTURE_COUNT = new AtomicLong();
    private static final AtomicLong NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong VANILLA_COMPILE_NANOS = new AtomicLong();
    private static final AtomicLong VANILLA_COMPILE_COUNT = new AtomicLong();
    private static final ThreadLocal<Long> VANILLA_COMPILE_START = new ThreadLocal<>();
    private static final AtomicLong LAST_FAILURE_LOG = new AtomicLong();

    private NativeSectionMesher() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
        if (!enabled) {
            for (Map.Entry<Long, PendingJob> entry : new ArrayList<>(PENDING.entrySet())) {
                if (PENDING.remove(entry.getKey(), entry.getValue())) {
                    safelyRelease(entry.getKey());
                    NativeSectionOwnership.noteStaleResult();
                }
            }
            NativeSectionOwnership.clearAll();
            NativeTerrainStore.get().close();
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void setDebugMode(boolean debugMode) {
        DEBUG_MODE = debugMode;
    }

    public static boolean isDebugMode() {
        return DEBUG_MODE;
    }

    static boolean mergeEnabled() {
        return NativeRendererControls.tiledEnabled() && NativeTiledPipelines.isAvailable();
    }

    // ------------------------------------------------------------------
    // Worker-thread compile hooks
    // ------------------------------------------------------------------

    /**
     * HEAD hook. When the section is natively ACTIVE, runs the takeover
     * capture and returns a replacement {@code Results} with empty rendered
     * layers (vanilla's per-layer meshing is skipped). Returns null to let
     * vanilla compile normally.
     */
    public static SectionCompiler.Results onCompileHead(
            Object compiler,
            SectionPos sectionPos,
            RenderSectionRegion region) {
        TOOK_OVER.set(Boolean.FALSE);
        if (!ENABLED || !NativeLoader.isLoaded() || DEBUG_MODE) {
            return null;
        }
        long sectionKey = NativeSectionOwnership.sectionKey(sectionPos);
        if (NativeSectionOwnership.isUnsupported(sectionKey)
                || NativeSectionOwnership.state(sectionKey) != NativeSectionOwnership.State.ACTIVE) {
            return null;
        }
        long generation = GENERATION_SEQUENCE.incrementAndGet();
        SECTION_CALLBACKS.incrementAndGet();
        long start = System.nanoTime();
        NativeSectionCapture.CapturedSection captured =
                NativeSectionCapture.captureTakeover(compiler, sectionPos, region, mergeEnabled());
        if (captured == null) {
            // Capture failed: fall back to vanilla for this compile and mark
            // the section failed so vanilla geometry is rebuilt.
            NativeSectionOwnership.markFailed(sectionKey, "takeover capture failed");
            return null;
        }
        CAPTURE_NANOS.addAndGet(System.nanoTime() - start);
        CAPTURE_COUNT.incrementAndGet();
        TOOK_OVER.set(Boolean.TRUE);
        submitSnapshot(sectionKey, generation, captured, true);
        return buildTakeoverResults(captured);
    }

    private static SectionCompiler.Results buildTakeoverResults(NativeSectionCapture.CapturedSection captured) {
        SectionCompiler.Results results = new SectionCompiler.Results();
        results.blockEntities.addAll(captured.blockEntities());
        ((SectionCompilerResultsAccess) (Object) results).whatOptimizations$setVisibilitySet(captured.visibilitySet());
        // renderedLayers stays empty: vanilla draws nothing for this section.
        return results;
    }

    /** RETURN hook. Shadow capture for the first build (and any vanilla compile). */
    public static void onCompileReturn(
            SectionPos sectionPos,
            RenderSectionRegion region,
            SectionCompiler.Results vanillaResults) {
        if (TOOK_OVER.get()) {
            TOOK_OVER.set(Boolean.FALSE);
            return;
        }
        endVanillaCompileTiming();
        if (!ENABLED || !NativeLoader.isLoaded() || vanillaResults == null) {
            return;
        }
        if (NativeSectionOwnership.isUnsupported(NativeSectionOwnership.sectionKey(sectionPos))) {
            return;
        }
        if (DEBUG_MODE) {
            // Debug mode also ran the takeover capture at HEAD (without
            // cancelling vanilla); compare it against vanilla's output.
            NativeSectionCapture.CapturedSection debugCapture = DEBUG_CAPTURE.get();
            DEBUG_CAPTURE.remove();
            if (debugCapture != null) {
                NativeMeshDifferential.compareCaptures(sectionPos, vanillaResults, debugCapture);
            }
        }
        if (vanillaResults.renderedLayers.isEmpty()) {
            // Empty section: nothing to capture and vanilla draws nothing
            // either, so drop any stale native mesh (the section may have
            // become all-air since the last build).
            long emptyKey = NativeSectionOwnership.sectionKey(sectionPos);
            NativeTerrainStore.get().removeSection(emptyKey);
            NativeSectionOwnership.invalidate(emptyKey);
            return;
        }
        long sectionKey = NativeSectionOwnership.sectionKey(sectionPos);
        long generation = GENERATION_SEQUENCE.incrementAndGet();
        SECTION_CALLBACKS.incrementAndGet();
        long start = System.nanoTime();
        NativeSectionCapture.CapturedSection captured =
                NativeSectionCapture.captureShadow(sectionPos, region, vanillaResults, mergeEnabled());
        if (captured == null) {
            NativeSectionOwnership.markFailed(sectionKey, "shadow capture failed");
            return;
        }
        CAPTURE_NANOS.addAndGet(System.nanoTime() - start);
        CAPTURE_COUNT.incrementAndGet();
        submitSnapshot(sectionKey, generation, captured, false);
    }

    /** Debug mode: HEAD hook runs the takeover capture without taking over. */
    public static void captureForDebug(Object compiler, SectionPos sectionPos, RenderSectionRegion region) {
        if (!ENABLED || !NativeLoader.isLoaded()) {
            return;
        }
        NativeSectionCapture.CapturedSection captured =
                NativeSectionCapture.captureTakeover(compiler, sectionPos, region, mergeEnabled());
        if (captured == null) {
            return;
        }
        // The capture lives in a thread-local direct buffer that the shadow
        // capture at RETURN reuses; stash an independent heap copy so the
        // comparison still sees the takeover capture's bytes.
        ByteBuffer source = captured.snapshot();
        ByteBuffer copy = ByteBuffer.allocate(captured.totalBytes()).order(ByteOrder.nativeOrder());
        ByteBuffer slice = source.duplicate();
        slice.position(0);
        slice.limit(captured.totalBytes());
        copy.put(slice);
        copy.flip();
        DEBUG_CAPTURE.set(new NativeSectionCapture.CapturedSection(
                copy,
                captured.totalBytes(),
                captured.openFaces(),
                captured.blockEntities(),
                captured.visibilitySet()));
    }

    private static void submitSnapshot(
            long sectionKey,
            long generation,
            NativeSectionCapture.CapturedSection captured,
            boolean takeover) {
        if (PENDING.size() >= MAX_PENDING_JOBS) {
            recordFailure("Native ticket queue is full; dropping snapshot", null);
            if (takeover) {
                NativeSectionOwnership.markFailed(sectionKey, "ticket queue full");
            }
            return;
        }
        if (!NativeSectionOwnership.beginJob(sectionKey, generation)) {
            return;
        }
        // Cancel superseded jobs for this section.
        for (Map.Entry<Long, PendingJob> entry : new ArrayList<>(PENDING.entrySet())) {
            PendingJob job = entry.getValue();
            if (job.sectionKey() == sectionKey && PENDING.remove(entry.getKey(), job)) {
                safelyRelease(entry.getKey());
                NativeSectionOwnership.noteStaleResult();
            }
        }
        long ticket;
        try {
            ByteBuffer snapshot = captured.snapshot();
            ticket = Native.submitSectionV2(snapshot, sectionKey, generation);
        } catch (RuntimeException | LinkageError failure) {
            recordFailure("Submitting WOM2 snapshot failed", failure);
            NativeSectionOwnership.markFailed(sectionKey, "snapshot submission failed");
            return;
        }
        if (ticket <= 0L) {
            recordFailure("Rust rejected WOM2 job with " + ticket, null);
            NativeSectionOwnership.markFailed(sectionKey, "Rust rejected the job: " + ticket);
            return;
        }
        NativeSectionOwnership.setPendingTicket(sectionKey, ticket, generation);
        PENDING.put(ticket, new PendingJob(ticket, sectionKey, generation, System.nanoTime(), takeover));
        SUBMITTED_JOBS.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Render-thread poll
    // ------------------------------------------------------------------

    /** Render thread: polls finished jobs, stages uploads, flips ownership. */
    public static void pollCompleted() {
        if (!ENABLED || !NativeLoader.isLoaded() || PENDING.isEmpty()) {
            return;
        }
        ByteBuffer output = OUTPUT_BUFFER.get();
        List<CompletedMesh> completed = null;
        for (Map.Entry<Long, PendingJob> entry : new ArrayList<>(PENDING.entrySet())) {
            long ticket = entry.getKey();
            PendingJob job = entry.getValue();
            int status;
            try {
                output.clear();
                status = Native.pollCompleted(ticket, output);
            } catch (RuntimeException | LinkageError failure) {
                recordFailure("Polling native ticket failed", failure);
                finishTicket(ticket, job);
                NativeSectionOwnership.markFailed(job.sectionKey(), "poll failed");
                continue;
            }
            if (status == 0) {
                continue;
            }
            if (status < 0) {
                recordFailure("Native ticket " + ticket + " failed with " + status, null);
                finishTicket(ticket, job);
                NativeSectionOwnership.markFailed(job.sectionKey(), "native job failed: " + status);
                continue;
            }
            int totalBytes = status - 1;
            if (totalBytes <= 0 || totalBytes > output.capacity()) {
                recordFailure("Native ticket returned impossible byte count " + totalBytes, null);
                finishTicket(ticket, job);
                NativeSectionOwnership.markFailed(job.sectionKey(), "impossible result size");
                continue;
            }
            if (!NativeSectionOwnership.isLatestGeneration(job.sectionKey(), job.generation())) {
                NativeSectionOwnership.noteStaleResult();
                finishTicket(ticket, job);
                continue;
            }
            ParsedMesh mesh;
            try {
                mesh = parseOutput(output, totalBytes);
            } catch (RuntimeException failure) {
                recordFailure("Native WOM2 output failed validation", failure);
                finishTicket(ticket, job);
                NativeSectionOwnership.markFailed(job.sectionKey(), "output validation failed");
                continue;
            }
            if (completed == null) {
                completed = new ArrayList<>();
            }
            completed.add(new CompletedMesh(job, mesh));
            finishTicket(ticket, job);
        }

        if (completed == null || completed.isEmpty()) {
            return;
        }

        // Stage all uploads, flush once, then flip ownership.
        NativeTerrainStore store = NativeTerrainStore.get();
        for (CompletedMesh mesh : completed) {
            for (int slot = 0; slot < Native.WOM2_LAYER_COUNT; slot++) {
                ChunkSectionLayer layer = layerOf(slot);
                byte[] passthrough = mesh.mesh.passthrough(slot);
                byte[] merged = mesh.mesh.merged(slot);
                if (passthrough.length == 0 && merged.length == 0) {
                    store.remove(mesh.job().sectionKey(), layer);
                } else {
                    store.stageUpload(mesh.job().sectionKey(), layer, passthrough, merged);
                }
            }
        }
        store.flushUploads();
        for (CompletedMesh mesh : completed) {
            int layerMask = mesh.mesh.layerMask();
            NativeSectionOwnership.activate(mesh.job().sectionKey(), layerMask);
            COMPLETED_JOBS.incrementAndGet();
            NATIVE_NANOS.addAndGet(System.nanoTime() - mesh.job().submittedNanos());
        }
        NativeSectionOwnership.applyQueuedRecompiles();
        NativeSectionOwnership.enforceCap();
    }

    private static ChunkSectionLayer layerOf(int slot) {
        return switch (slot) {
            case Native.LAYER_SOLID -> ChunkSectionLayer.SOLID;
            case Native.LAYER_CUTOUT -> ChunkSectionLayer.CUTOUT;
            default -> ChunkSectionLayer.TRANSLUCENT;
        };
    }

    private static ParsedMesh parseOutput(ByteBuffer output, int totalBytes) {
        if (output.getInt(0) != WOM2_MAGIC) {
            throw new IllegalStateException("bad WOM2 output magic");
        }
        if (output.getShort(4) != (short) WOM2_VERSION) {
            throw new IllegalStateException("bad WOM2 output version");
        }
        int layerCount = output.getShort(8) & 0xFFFF;
        if (layerCount != Native.WOM2_LAYER_COUNT) {
            throw new IllegalStateException("bad WOM2 layer count " + layerCount);
        }
        int tableOffset = output.getInt(12);
        if (output.getInt(16) != totalBytes || tableOffset != Native.WOM2_OUTPUT_HEADER_BYTES) {
            throw new IllegalStateException("bad WOM2 output header");
        }
        byte[][] passthrough = new byte[Native.WOM2_LAYER_COUNT][];
        byte[][] merged = new byte[Native.WOM2_LAYER_COUNT][];
        int layerMask = 0;
        for (int slot = 0; slot < Native.WOM2_LAYER_COUNT; slot++) {
            int entry = tableOffset + slot * Native.WOM2_OUTPUT_LAYER_TABLE_BYTES;
            if ((output.get(entry) & 0xFF) != Native.LAYER_IDS[slot]) {
                throw new IllegalStateException("bad WOM2 layer id at slot " + slot);
            }
            int passthroughQuads = output.getInt(entry + 4);
            int passthroughBytes = output.getInt(entry + 8);
            int passthroughOffset = output.getInt(entry + 12);
            int mergedQuads = output.getInt(entry + 16);
            int mergedBytes = output.getInt(entry + 20);
            if (passthroughBytes != passthroughQuads * 4 * Native.BLOCK_VERTEX_STRIDE
                    || mergedBytes != mergedQuads * 4 * Native.TILED_VERTEX_STRIDE) {
                throw new IllegalStateException("bad WOM2 layer byte counts at slot " + slot);
            }
            if (passthroughOffset < 0
                    || passthroughOffset + passthroughBytes + mergedBytes > totalBytes) {
                throw new IllegalStateException("bad WOM2 layer offsets at slot " + slot);
            }
            if (passthroughBytes > 0) {
                passthrough[slot] = new byte[passthroughBytes];
                readBytes(output, passthroughOffset, passthrough[slot]);
            } else {
                passthrough[slot] = new byte[0];
            }
            if (mergedBytes > 0) {
                merged[slot] = new byte[mergedBytes];
                readBytes(output, passthroughOffset + passthroughBytes, merged[slot]);
            } else {
                merged[slot] = new byte[0];
            }
            if (passthroughQuads + mergedQuads > 0) {
                layerMask |= 1 << slot;
            }
        }
        return new ParsedMesh(passthrough, merged, layerMask);
    }

    private static void readBytes(ByteBuffer buffer, int offset, byte[] target) {
        ByteBuffer slice = buffer.duplicate();
        slice.position(offset);
        slice.limit(offset + target.length);
        slice.get(target);
    }

    private static void finishTicket(long ticket, PendingJob job) {
        if (PENDING.remove(ticket, job)) {
            safelyRelease(ticket);
        }
    }

    private static void safelyRelease(long ticket) {
        try {
            int status = Native.release(ticket);
            if (status < 0 && status != -8) {
                LOGGER.debug("Native ticket {} release returned {}", ticket, status);
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("Could not release native ticket {}", ticket, failure);
        }
    }

    // ------------------------------------------------------------------
    // Timing + telemetry
    // ------------------------------------------------------------------

    public static void beginVanillaCompileTiming() {
        VANILLA_COMPILE_START.set(System.nanoTime());
    }

    public static void endVanillaCompileTiming() {
        Long started = VANILLA_COMPILE_START.get();
        VANILLA_COMPILE_START.remove();
        if (started != null) {
            VANILLA_COMPILE_NANOS.addAndGet(System.nanoTime() - started);
            VANILLA_COMPILE_COUNT.incrementAndGet();
        }
    }

    public static long averageCaptureMicros() {
        return CAPTURE_NANOS.get() / Math.max(1L, CAPTURE_COUNT.get()) / 1_000L;
    }

    public static long averageNativeMicros() {
        return NATIVE_NANOS.get() / Math.max(1L, COMPLETED_JOBS.get()) / 1_000L;
    }

    public static long averageVanillaCompileMicros() {
        return VANILLA_COMPILE_NANOS.get() / Math.max(1L, VANILLA_COMPILE_COUNT.get()) / 1_000L;
    }

    public static long callbacks() {
        return SECTION_CALLBACKS.get();
    }

    public static long submittedJobs() {
        return SUBMITTED_JOBS.get();
    }

    public static long completedJobs() {
        return COMPLETED_JOBS.get();
    }

    public static int pendingJobs() {
        return PENDING.size();
    }

    public static long failures() {
        return FAILED_SECTIONS.get();
    }

    private static void recordFailure(String message, Throwable failure) {
        long count = FAILED_SECTIONS.incrementAndGet();
        long previous = LAST_FAILURE_LOG.get();
        if (count == 1L || count - previous >= 256L) {
            LAST_FAILURE_LOG.set(count);
            if (failure == null) {
                LOGGER.warn("{} ({} failures so far)", message, count);
            } else {
                LOGGER.warn("{} ({} failures so far)", message, count, failure);
            }
        }
    }

    private record PendingJob(
            long ticket,
            long sectionKey,
            long generation,
            long submittedNanos,
            boolean takeover) {
    }

    private record CompletedMesh(PendingJob job, ParsedMesh mesh) {
    }

    private record ParsedMesh(byte[][] passthrough, byte[][] merged, int layerMask) {
        public byte[] passthrough(int slot) {
            return passthrough[slot];
        }

        public byte[] merged(int slot) {
            return merged[slot];
        }

        public int layerMask() {
            return layerMask;
        }
    }
}
