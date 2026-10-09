package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a 18x18x18 padded snapshot, calls Rust exactly once per section and
 * keeps the packed geometry in a bounded cache. This is intentionally shadow
 * mode: vanilla's completed section mesh is never replaced by this output.
 */
public final class NativeSectionMesher {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/mesher");
    private static final int GRID = 18;
    private static final int CELL_COUNT = GRID * GRID * GRID;
    private static final int HEADER_BYTES = 40;
    private static final int PALETTE_ENTRY_BYTES = 8;
    private static final int CELL_ENTRY_BYTES = 4;
    private static final int MAGIC = 0x314D4F57;
    private static final int VERSION = 1;
    private static final int FLAG_MESHABLE = 1;
    private static final int FLAG_OCCLUDES = 2;
    private static final int MAX_INPUT_BYTES = HEADER_BYTES
            + CELL_COUNT * PALETTE_ENTRY_BYTES
            + CELL_COUNT * CELL_ENTRY_BYTES;
    private static final int MAX_OUTPUT_BYTES = 1_200_000;

    // Section compilation is worker-threaded. Reuse bounded direct buffers per
    // worker to avoid a 1.2 MiB native allocation for every section compile.
    private static final ThreadLocal<ByteBuffer> INPUT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(MAX_INPUT_BYTES).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<ByteBuffer> OUTPUT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<SnapshotWorkspace> SNAPSHOT_WORKSPACE =
            ThreadLocal.withInitial(SnapshotWorkspace::new);

    private static volatile boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("whatoptimizations.nativeMesher", "true"));
    // Shadow mode runs beside vanilla compilation. Sample by default to keep
    // diagnostics useful without duplicating the full CPU workload on every section.
    private static final int SAMPLE_RATE = Math.max(1,
            Math.min(64, Integer.getInteger("whatoptimizations.nativeMesher.sampleRate", 8)));
    private static final AtomicLong SECTION_CALLBACKS = new AtomicLong();
    private static final AtomicLong EMPTY_SECTIONS = new AtomicLong();
    private static final AtomicLong EMPTY_SCAN_NANOS = new AtomicLong();
    private static final AtomicLong SAMPLED_SECTIONS = new AtomicLong();
    private static final AtomicLong SAMPLED_OUT_SECTIONS = new AtomicLong();
    private static final AtomicLong COMPILED_SECTIONS = new AtomicLong();
    private static final AtomicLong OUTPUT_VERTICES = new AtomicLong();
    private static final AtomicLong FAILED_SECTIONS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();
    private static final AtomicLong NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong COPY_CACHE_NANOS = new AtomicLong();
    private static final AtomicLong LAST_FAILURE_LOG = new AtomicLong();
    private static final AtomicLong GENERATION_SEQUENCE = new AtomicLong();
    private static final AtomicLong STALE_RESULTS = new AtomicLong();
    private static final AtomicLong VANILLA_COMPILE_NANOS = new AtomicLong();
    private static final AtomicLong VANILLA_COMPILE_COUNT = new AtomicLong();
    private static final ThreadLocal<Long> VANILLA_COMPILE_START = new ThreadLocal<>();
    private static final int MAX_PENDING_JOBS = 64;
    private static final ConcurrentMap<Long, PendingJob> PENDING = new ConcurrentHashMap<>();
    private static final Map<Long, Long> LATEST_GENERATION =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                    return size() > 32_768;
                }
            });
    private static volatile boolean FORCE_FULL_CAPTURE;
    private static volatile boolean DEBUG_MODE;

    private NativeSectionMesher() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
        if (!enabled) {
            for (Map.Entry<Long, PendingJob> entry : new ArrayList<>(PENDING.entrySet())) {
                if (PENDING.remove(entry.getKey(), entry.getValue())) {
                    safelyRelease(entry.getKey());
                    STALE_RESULTS.incrementAndGet();
                }
            }
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void setForceFullCapture(boolean force) {
        FORCE_FULL_CAPTURE = force;
    }

    public static void setDebugMode(boolean debugMode) {
        DEBUG_MODE = debugMode;
    }

    public static boolean isDebugMode() {
        return DEBUG_MODE;
    }

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

    public static long averageSnapshotMicros() {
        return SNAPSHOT_NANOS.get() / Math.max(1L, SAMPLED_SECTIONS.get()) / 1_000L;
    }

    public static long averageNativeQueueMicros() {
        return NATIVE_NANOS.get() / Math.max(1L, COMPILED_SECTIONS.get()) / 1_000L;
    }

    public static long averageCopyCacheMicros() {
        return COPY_CACHE_NANOS.get() / Math.max(1L, COMPILED_SECTIONS.get()) / 1_000L;
    }

    public static long averageVanillaCompileMicros() {
        return VANILLA_COMPILE_NANOS.get() / Math.max(1L, VANILLA_COMPILE_COUNT.get()) / 1_000L;
    }

    public static long callbacks() {
        return SECTION_CALLBACKS.get();
    }

    public static int pendingJobs() {
        return PENDING.size();
    }

    public static long staleResults() {
        return STALE_RESULTS.get();
    }

    public static long failures() {
        return FAILED_SECTIONS.get();
    }

    public static long sampledSections() {
        return SAMPLED_SECTIONS.get();
    }

    public static long completedSections() {
        return COMPILED_SECTIONS.get();
    }

    public static void compileShadow(SectionPos sectionPos, RenderSectionRegion region) {
        if (!ENABLED || !NativeLoader.isLoaded()) {
            return;
        }

        BlockPos origin = sectionPos.origin();
        long key = BlockPos.asLong(origin.getX(), origin.getY(), origin.getZ());
        long generation = GENERATION_SEQUENCE.incrementAndGet();
        LATEST_GENERATION.put(key, generation);
        cancelSupersededJobs(key);

        long callbackNumber = SECTION_CALLBACKS.incrementAndGet();
        if (!FORCE_FULL_CAPTURE && (callbackNumber - 1L) % SAMPLE_RATE != 0L) {
            SAMPLED_OUT_SECTIONS.incrementAndGet();
            NativeSectionMeshCache.invalidate(key);
            return;
        }

        SAMPLED_SECTIONS.incrementAndGet();
        long snapshotStart = System.nanoTime();
        try {
            NativeSectionMeshCache.invalidateMesh(key);
            ByteBuffer input = snapshot(sectionPos, region);
            long snapshotNanos = System.nanoTime() - snapshotStart;
            int openFaces = SNAPSHOT_WORKSPACE.get().openFaces;
            NativeSectionMeshCache.putMetadata(key, openFaces, generation);
            if (input == null) {
                EMPTY_SCAN_NANOS.addAndGet(snapshotNanos);
                long emptyCount = EMPTY_SECTIONS.incrementAndGet();
                SNAPSHOT_NANOS.addAndGet(snapshotNanos);
                if (emptyCount == 1L || (emptyCount & 255L) == 0L) {
                    LOGGER.info(
                            "Rust async mesher: skipped native work for {} sampled sections with no meshable interior cubes; average empty scan {} us; vanilla rendering remains active",
                            emptyCount,
                            EMPTY_SCAN_NANOS.get() / emptyCount / 1_000L);
                }
                return;
            }

            if (PENDING.size() >= MAX_PENDING_JOBS) {
                recordFailure("Native Java ticket queue is full; dropping newest snapshot", null);
                return;
            }

            byte[] expected = null;
            if (DEBUG_MODE) {
                ByteBuffer syncOutput = DEBUG_OUTPUT_BUFFER.get();
                syncOutput.clear();
                int syncVertices = Native.meshSection(input, syncOutput);
                if (syncVertices < 0) {
                    recordFailure("Synchronous Rust debug mesh failed with " + syncVertices, null);
                } else {
                    int syncBytes = Math.multiplyExact(syncVertices, Native.MESH_VERTEX_STRIDE);
                    expected = new byte[syncBytes];
                    syncOutput.position(0);
                    syncOutput.limit(syncBytes);
                    syncOutput.get(expected);
                }
            }

            long ticket = Native.submitSection(input, key, generation);
            if (ticket <= 0L) {
                recordFailure("Rust rejected async section job with " + ticket, null);
                return;
            }
            PendingJob job = new PendingJob(
                    ticket, key, generation, System.nanoTime(), snapshotNanos, expected);
            PENDING.put(ticket, job);
            SNAPSHOT_NANOS.addAndGet(snapshotNanos);
        } catch (RuntimeException | LinkageError failure) {
            recordFailure("Native section snapshot/submission failed; preserving vanilla result", failure);
        }
    }

    /** Called on the client/render thread. It is the only path that consumes finished jobs. */
    public static void pollCompleted() {
        if (!ENABLED || !NativeLoader.isLoaded() || PENDING.isEmpty()) {
            return;
        }

        ByteBuffer output = OUTPUT_BUFFER.get();
        for (Map.Entry<Long, PendingJob> entry : new ArrayList<>(PENDING.entrySet())) {
            long ticket = entry.getKey();
            PendingJob job = entry.getValue();
            int status;
            long pollStart = System.nanoTime();
            try {
                output.clear();
                status = Native.pollCompleted(ticket, output);
            } catch (RuntimeException | LinkageError failure) {
                recordFailure("Polling native ticket failed", failure);
                finishTicket(ticket, job);
                continue;
            }
            if (status == 0) {
                continue;
            }
            long nativeQueueNanos = System.nanoTime() - job.submittedNanos;
            if (status < 0) {
                recordFailure("Native ticket " + ticket + " failed with " + status, null);
                finishTicket(ticket, job);
                continue;
            }

            int vertexCount = status - 1;
            long byteCountLong = (long) vertexCount * Native.MESH_VERTEX_STRIDE;
            if (byteCountLong > output.capacity()) {
                recordFailure("Native ticket returned impossible vertex count " + vertexCount, null);
                finishTicket(ticket, job);
                continue;
            }

            int byteCount = (int) byteCountLong;
            long copyStart = System.nanoTime();
            if (isLatestGeneration(job.sectionKey, job.generation)) {
                byte[] vertices = new byte[byteCount];
                output.position(0);
                output.limit(byteCount);
                output.get(vertices);
                NativeSectionMeshCache.put(job.sectionKey, vertices);
                compareDebug(job, vertices);
                long sectionCount = COMPILED_SECTIONS.incrementAndGet();
                long totalVertices = OUTPUT_VERTICES.addAndGet(vertexCount);
                NATIVE_NANOS.addAndGet(nativeQueueNanos);
                COPY_CACHE_NANOS.addAndGet(System.nanoTime() - copyStart);
                boolean firstNonEmptySection = vertexCount > 0 && totalVertices == vertexCount;
                if (sectionCount == 1L || firstNonEmptySection || (sectionCount & 255L) == 0L) {
                    LOGGER.info(
                            "Rust async mesher: callbacks {} (selected {}, complete {}, skipped {} at 1/{}) | pending {} | {} cumulative vertices (last {}) | stale {} | failures {} | avg snapshot/queue+native/copy+cache {} / {} / {} us | cache {} sections / {} MiB",
                            SECTION_CALLBACKS.get(),
                            SAMPLED_SECTIONS.get(),
                            sectionCount,
                            SAMPLED_OUT_SECTIONS.get(),
                            FORCE_FULL_CAPTURE ? 1 : SAMPLE_RATE,
                            PENDING.size(),
                            totalVertices,
                            vertexCount,
                            STALE_RESULTS.get(),
                            FAILED_SECTIONS.get(),
                            SNAPSHOT_NANOS.get() / Math.max(1L, SAMPLED_SECTIONS.get()) / 1_000L,
                            NATIVE_NANOS.get() / Math.max(1L, sectionCount) / 1_000L,
                            COPY_CACHE_NANOS.get() / Math.max(1L, sectionCount) / 1_000L,
                            NativeSectionMeshCache.sectionCount(),
                            NativeSectionMeshCache.cachedBytes() / (1024L * 1024L));
                }
            } else {
                STALE_RESULTS.incrementAndGet();
            }
            finishTicket(ticket, job);
            long elapsed = System.nanoTime() - pollStart;
            if (elapsed > 10_000_000L) {
                LOGGER.debug("Native ticket {} completion handling took {} us", ticket, elapsed / 1_000L);
            }
        }
    }

    private static void compareDebug(PendingJob job, byte[] actual) {
        if (job.expectedBytes == null) {
            return;
        }
        int difference = firstDifference(job.expectedBytes, actual);
        if (difference >= 0) {
            int expectedState = stateIdAt(job.expectedBytes, difference);
            int actualState = stateIdAt(actual, difference);
            LOGGER.warn(
                    "Rust async/sync mesh mismatch at section key {} generation {} byte {}; first packed state id {} vs {}",
                    job.sectionKey, job.generation, difference, expectedState, actualState);
        }
    }

    private static int firstDifference(byte[] a, byte[] b) {
        int count = Math.min(a.length, b.length);
        for (int i = 0; i < count; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return a.length == b.length ? -1 : count;
    }

    private static int stateIdAt(byte[] bytes, int difference) {
        if (bytes.length < Native.MESH_VERTEX_STRIDE) {
            return -1;
        }
        int offset = (difference / Native.MESH_VERTEX_STRIDE) * Native.MESH_VERTEX_STRIDE + 4;
        if (offset + 4 > bytes.length) {
            return -1;
        }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.nativeOrder()).getInt();
    }

    private static boolean isLatestGeneration(long key, long generation) {
        Long current = LATEST_GENERATION.get(key);
        return current != null && current.longValue() == generation;
    }

    private static void cancelSupersededJobs(long sectionKey) {
        for (Map.Entry<Long, PendingJob> entry : new ArrayList<>(PENDING.entrySet())) {
            PendingJob job = entry.getValue();
            if (job.sectionKey == sectionKey && PENDING.remove(entry.getKey(), job)) {
                safelyRelease(entry.getKey());
                STALE_RESULTS.incrementAndGet();
            }
        }
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

    private static final ThreadLocal<ByteBuffer> DEBUG_OUTPUT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES).order(ByteOrder.nativeOrder()));

    private record PendingJob(
            long ticket,
            long sectionKey,
            long generation,
            long submittedNanos,
            long snapshotNanos,
            byte[] expectedBytes) {
    }

    private static ByteBuffer snapshot(SectionPos sectionPos, RenderSectionRegion region) {
        BlockPos origin = sectionPos.origin();
        int minX = origin.getX();
        int minY = origin.getY();
        int minZ = origin.getZ();

        SnapshotWorkspace workspace = SNAPSHOT_WORKSPACE.get();
        HashMap<BlockState, Integer> paletteLookup = workspace.paletteLookup;
        paletteLookup.clear();
        short[] cellPalette = workspace.cellPalette;
        byte[] sky = workspace.sky;
        byte[] block = workspace.block;
        int paletteCount = 0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int cell = 0;
        int openFaces = 0;
        boolean hasMeshableInterior = false;
        for (int z = 0; z < GRID; z++) {
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID; x++) {
                    pos.set(minX + x - 1, minY + y - 1, minZ + z - 1);
                    BlockState state = region.getBlockState(pos);
                    Integer paletteIndex = paletteLookup.get(state);

                    if (paletteIndex == null) {
                        int stateId = Block.getId(state);
                        boolean occludes = state.isSolidRender();
                        boolean fullCube = occludes && Block.isShapeFullBlock(state.getShape(region, pos));
                        boolean meshable = fullCube
                                && state.getRenderShape() == RenderShape.MODEL
                                && state.getFluidState().isEmpty()
                                && !state.hasBlockEntity();

                        int flags = (meshable ? FLAG_MESHABLE : 0) | (occludes ? FLAG_OCCLUDES : 0);
                        paletteIndex = paletteCount;
                        if (paletteCount >= CELL_COUNT || paletteCount > Short.MAX_VALUE) {
                            throw new IllegalStateException("Section palette exceeded ABI limits");
                        }
                        paletteLookup.put(state, paletteIndex);
                        workspace.paletteStateIds[paletteCount] = stateId;
                        workspace.paletteFlags[paletteCount] = (byte) flags;
                        paletteCount++;
                    }

                    cellPalette[cell] = paletteIndex.shortValue();
                    int cellFlags = workspace.paletteFlags[paletteIndex] & 0xff;
                    if ((cellFlags & FLAG_OCCLUDES) == 0) {
                        if (x == 1 && y >= 1 && y <= 16 && z >= 1 && z <= 16) openFaces |= 1 << 0;
                        if (x == 16 && y >= 1 && y <= 16 && z >= 1 && z <= 16) openFaces |= 1 << 1;
                        if (y == 1 && x >= 1 && x <= 16 && z >= 1 && z <= 16) openFaces |= 1 << 2;
                        if (y == 16 && x >= 1 && x <= 16 && z >= 1 && z <= 16) openFaces |= 1 << 3;
                        if (z == 1 && x >= 1 && x <= 16 && y >= 1 && y <= 16) openFaces |= 1 << 4;
                        if (z == 16 && x >= 1 && x <= 16 && y >= 1 && y <= 16) openFaces |= 1 << 5;
                    }
                    if (x >= 1 && x <= 16 && y >= 1 && y <= 16 && z >= 1 && z <= 16
                            && (cellFlags & FLAG_MESHABLE) != 0) {
                        hasMeshableInterior = true;
                    }
                    cell++;
                }
            }
        }

        workspace.openFaces = openFaces;

        // Sections without an admitted opaque cube cannot produce native faces.
        // Avoid all light lookups, JNI crossing, and Rust traversal for these.
        if (!hasMeshableInterior) {
            return null;
        }

        LevelLightEngine lightEngine = region.getLightEngine();
        LayerLightEventListener skyLight = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockLight = lightEngine.getLayerListener(LightLayer.BLOCK);
        cell = 0;
        for (int z = 0; z < GRID; z++) {
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID; x++) {
                    pos.set(minX + x - 1, minY + y - 1, minZ + z - 1);
                    sky[cell] = (byte) clampLight(skyLight.getLightValue(pos));
                    block[cell] = (byte) clampLight(blockLight.getLightValue(pos));
                    cell++;
                }
            }
        }

        int paletteOffset = HEADER_BYTES;
        int cellsOffset = paletteOffset + paletteCount * PALETTE_ENTRY_BYTES;
        int totalBytes = cellsOffset + CELL_COUNT * CELL_ENTRY_BYTES;
        ByteBuffer input = INPUT_BUFFER.get();
        if (totalBytes > input.capacity()) {
            throw new IllegalStateException("Section snapshot exceeded reusable direct-buffer capacity");
        }
        input.clear();

        input.putInt(0, MAGIC);
        input.putShort(4, (short) VERSION);
        input.putShort(6, (short) HEADER_BYTES);
        input.putShort(8, (short) GRID);
        input.putShort(10, (short) GRID);
        input.putShort(12, (short) GRID);
        input.putShort(14, (short) paletteCount);
        input.putInt(16, CELL_COUNT);
        input.putShort(20, (short) PALETTE_ENTRY_BYTES);
        input.putShort(22, (short) CELL_ENTRY_BYTES);
        input.putInt(24, paletteOffset);
        input.putInt(28, cellsOffset);
        input.putInt(32, totalBytes);
        input.putInt(36, 0);

        input.position(paletteOffset);
        for (int i = 0; i < paletteCount; i++) {
            input.putInt(workspace.paletteStateIds[i]);
            input.put(workspace.paletteFlags[i]);
            input.put((byte) 0x3f);
            input.putShort((short) 0);
        }

        input.position(cellsOffset);
        for (int i = 0; i < CELL_COUNT; i++) {
            input.putShort(cellPalette[i]);
            input.put(sky[i]);
            input.put(block[i]);
        }

        input.position(0);
        input.limit(totalBytes);
        return input;
    }

    private static int clampLight(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static void recordFailure(String message, Throwable failure) {
        long count = FAILED_SECTIONS.incrementAndGet();
        long previous = LAST_FAILURE_LOG.get();
        if (count == 1 || count - previous >= 256) {
            LAST_FAILURE_LOG.set(count);
            if (failure == null) {
                LOGGER.warn("{} ({} failures so far)", message, count);
            } else {
                LOGGER.warn("{} ({} failures so far)", message, count, failure);
            }
        }
    }

    private static final class SnapshotWorkspace {
        private final HashMap<BlockState, Integer> paletteLookup = new HashMap<>(256);
        private final short[] cellPalette = new short[CELL_COUNT];
        private final byte[] sky = new byte[CELL_COUNT];
        private final byte[] block = new byte[CELL_COUNT];
        private final int[] paletteStateIds = new int[CELL_COUNT];
        private final byte[] paletteFlags = new byte[CELL_COUNT];
        private int openFaces;
    }
}
