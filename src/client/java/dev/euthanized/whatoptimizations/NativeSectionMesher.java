package dev.euthanized.whatoptimizations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
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

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("whatoptimizations.nativeMesher", "true"));
    // Shadow mode runs beside vanilla compilation. Sample by default to keep
    // diagnostics useful without duplicating the full CPU workload on every section.
    private static final int SAMPLE_RATE = Math.max(1,
            Math.min(64, Integer.getInteger("whatoptimizations.nativeMesher.sampleRate", 8)));
    private static final AtomicLong SECTION_CALLBACKS = new AtomicLong();
    private static final AtomicLong SAMPLED_SECTIONS = new AtomicLong();
    private static final AtomicLong SAMPLED_OUT_SECTIONS = new AtomicLong();
    private static final AtomicLong COMPILED_SECTIONS = new AtomicLong();
    private static final AtomicLong OUTPUT_VERTICES = new AtomicLong();
    private static final AtomicLong FAILED_SECTIONS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();
    private static final AtomicLong NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong COPY_CACHE_NANOS = new AtomicLong();
    private static final AtomicLong LAST_FAILURE_LOG = new AtomicLong();

    private NativeSectionMesher() {
    }

    public static void compileShadow(SectionPos sectionPos, RenderSectionRegion region) {
        if (!ENABLED || !NativeLoader.isLoaded()) {
            return;
        }

        long callbackNumber = SECTION_CALLBACKS.incrementAndGet();
        if ((callbackNumber - 1L) % SAMPLE_RATE != 0L) {
            SAMPLED_OUT_SECTIONS.incrementAndGet();
            return;
        }

        SAMPLED_SECTIONS.incrementAndGet();
        long snapshotStart = System.nanoTime();
        try {
            ByteBuffer input = snapshot(sectionPos, region);
            long snapshotNanos = System.nanoTime() - snapshotStart;

            ByteBuffer output = OUTPUT_BUFFER.get();
            output.clear();
            long nativeStart = System.nanoTime();
            int vertexCount = Native.meshSection(input, output);
            long nativeNanos = System.nanoTime() - nativeStart;
            if (vertexCount < 0) {
                recordFailure("Rust returned native error " + vertexCount, null);
                return;
            }

            long bytesLong = (long) vertexCount * Native.MESH_VERTEX_STRIDE;
            if (bytesLong > output.capacity()) {
                recordFailure("Rust returned impossible vertex count " + vertexCount, null);
                return;
            }

            long copyCacheStart = System.nanoTime();
            int outputBytes = (int) bytesLong;
            byte[] vertices = new byte[outputBytes];
            output.position(0);
            output.limit(outputBytes);
            output.get(vertices);

            BlockPos origin = sectionPos.origin();
            long key = BlockPos.asLong(origin.getX(), origin.getY(), origin.getZ());
            NativeSectionMeshCache.put(key, vertices);
            long copyCacheNanos = System.nanoTime() - copyCacheStart;

            long sectionCount = COMPILED_SECTIONS.incrementAndGet();
            long totalVertices = OUTPUT_VERTICES.addAndGet(vertexCount);
            SNAPSHOT_NANOS.addAndGet(snapshotNanos);
            NATIVE_NANOS.addAndGet(nativeNanos);
            COPY_CACHE_NANOS.addAndGet(copyCacheNanos);
            boolean firstNonEmptySection = vertexCount > 0 && totalVertices == vertexCount;
            if (sectionCount == 1L || firstNonEmptySection || (sectionCount & 255L) == 0L) {
                long avgSnapshotMicros = SNAPSHOT_NANOS.get() / sectionCount / 1_000L;
                long avgNativeMicros = NATIVE_NANOS.get() / sectionCount / 1_000L;
                long avgCopyCacheMicros = COPY_CACHE_NANOS.get() / sectionCount / 1_000L;
                LOGGER.info(
                        "Rust shadow mesher: callbacks {} (selected {}, successful {}, skipped {} at 1/{}) | {} cumulative vertices (last {}) | failures {} | avg snapshot/native/copy+cache {} / {} / {} us | cache {} sections / {} MiB; vanilla rendering remains active",
                        SECTION_CALLBACKS.get(),
                        SAMPLED_SECTIONS.get(),
                        sectionCount,
                        SAMPLED_OUT_SECTIONS.get(),
                        SAMPLE_RATE,
                        totalVertices,
                        vertexCount,
                        FAILED_SECTIONS.get(),
                        avgSnapshotMicros,
                        avgNativeMicros,
                        avgCopyCacheMicros,
                        NativeSectionMeshCache.sectionCount(),
                        NativeSectionMeshCache.cachedBytes() / (1024L * 1024L));
            }
        } catch (RuntimeException | LinkageError failure) {
            recordFailure("Native shadow meshing failed; preserving vanilla result", failure);
        }
    }

    private static ByteBuffer snapshot(SectionPos sectionPos, RenderSectionRegion region) {
        BlockPos origin = sectionPos.origin();
        int minX = origin.getX();
        int minY = origin.getY();
        int minZ = origin.getZ();

        HashMap<BlockState, Integer> paletteLookup = new HashMap<>(256);
        ArrayList<PaletteRecord> palette = new ArrayList<>(256);
        short[] cellPalette = new short[CELL_COUNT];
        byte[] sky = new byte[CELL_COUNT];
        byte[] block = new byte[CELL_COUNT];

        LevelLightEngine lightEngine = region.getLightEngine();
        LayerLightEventListener skyLight = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockLight = lightEngine.getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int cell = 0;
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
                        paletteIndex = palette.size();
                        if (paletteIndex >= CELL_COUNT || paletteIndex > Short.MAX_VALUE) {
                            throw new IllegalStateException("Section palette exceeded ABI limits");
                        }
                        paletteLookup.put(state, paletteIndex);
                        palette.add(new PaletteRecord(stateId, flags));
                    }

                    cellPalette[cell] = paletteIndex.shortValue();
                    sky[cell] = (byte) clampLight(skyLight.getLightValue(pos));
                    block[cell] = (byte) clampLight(blockLight.getLightValue(pos));
                    cell++;
                }
            }
        }

        int paletteOffset = HEADER_BYTES;
        int cellsOffset = paletteOffset + palette.size() * PALETTE_ENTRY_BYTES;
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
        input.putShort(14, (short) palette.size());
        input.putInt(16, CELL_COUNT);
        input.putShort(20, (short) PALETTE_ENTRY_BYTES);
        input.putShort(22, (short) CELL_ENTRY_BYTES);
        input.putInt(24, paletteOffset);
        input.putInt(28, cellsOffset);
        input.putInt(32, totalBytes);
        input.putInt(36, 0);

        input.position(paletteOffset);
        for (PaletteRecord entry : palette) {
            input.putInt(entry.stateId);
            input.put((byte) entry.flags);
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

    private static final class PaletteRecord {
        private final int stateId;
        private final int flags;

        private PaletteRecord(int stateId, int flags) {
            this.stateId = stateId;
            this.flags = flags;
        }
    }
}
