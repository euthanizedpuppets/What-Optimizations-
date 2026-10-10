package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import dev.euthanized.whatoptimizations.mixin.SectionCompilerAccess;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures a section's render geometry into the WOM2 snapshot ABI (see
 * docs/NATIVE_TERRAIN_PIPELINE.md).
 *
 * <p>Two capture modes exist:
 * <ul>
 *   <li><b>Takeover</b> — runs vanilla's own model/fluid tessellation
 *   ({@link ModelBlockRenderer} + {@link FluidRenderer}) with a capturing
 *   {@link CapturingVertexConsumer} per layer, replicating
 *   {@code SectionCompiler.compile}'s loop exactly (same culling, AO, tint,
 *   light, offsets, block entities and visibility graph). The result replaces
 *   vanilla's per-layer meshing for sections the native renderer owns.</li>
 *   <li><b>Shadow</b> — copies vanilla's finished {@link MeshData} vertex
 *   buffers (already in the 28-byte BLOCK format) for the first build of a
 *   section, at the cost of one memcpy per layer and zero extra quad work.</li>
 * </ul>
 *
 * <p>Any quad whose sprite lives outside the block atlas, any element-wise
 * vertex emission, any overflow and any exception aborts the capture; the
 * caller then leaves the section with vanilla.
 */
final class NativeSectionCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/capture");

    private static final int MAGIC = 0x32_4D_4F_57; // "WOM2" little-endian
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = Native.WOM2_HEADER_BYTES;
    private static final int LAYER_TABLE_BYTES = Native.WOM2_LAYER_TABLE_BYTES;
    private static final int LAYER_COUNT = Native.WOM2_LAYER_COUNT;
    private static final int TABLE_END = HEADER_BYTES + LAYER_COUNT * LAYER_TABLE_BYTES;
    private static final int REGION_BYTES = Native.MAX_VERTICES_PER_LAYER * Native.BLOCK_VERTEX_STRIDE;
    private static final int BUFFER_BYTES = TABLE_END + LAYER_COUNT * REGION_BYTES;
    private static final ChunkSectionLayer[] LAYERS = {
        ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT
    };
    private static final int FLAG_MERGE_ENABLED = 1;

    private static final ThreadLocal<ByteBuffer> SNAPSHOT_BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<byte[]> COMPACT_BUFFER =
            ThreadLocal.withInitial(() -> new byte[REGION_BYTES]);

    private NativeSectionCapture() {
    }

    /** Result of a successful takeover capture. */
    record CapturedSection(
            ByteBuffer snapshot,
            int totalBytes,
            int openFaces,
            List<BlockEntity> blockEntities,
            VisibilitySet visibilitySet) {
    }

    /**
     * Runs the takeover capture. Returns null when the section cannot be
     * captured natively (the caller must let vanilla compile normally).
     */
    static CapturedSection captureTakeover(
            Object compiler,
            SectionPos sectionPos,
            RenderSectionRegion region,
            boolean mergeEnabled) {
        BlockStateModelSet modelSet;
        FluidStateModelSet fluidSet;
        BlockColors blockColors;
        boolean ambientOcclusion;
        boolean cutoutLeaves;
        try {
            SectionCompilerAccess access = (SectionCompilerAccess) compiler;
            modelSet = access.whatOptimizations$getBlockModelSet();
            fluidSet = access.whatOptimizations$getFluidModelSet();
            blockColors = access.whatOptimizations$getBlockColors();
            ambientOcclusion = access.whatOptimizations$getAmbientOcclusion();
            cutoutLeaves = access.whatOptimizations$getCutoutLeaves();
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not read SectionCompiler settings", failure);
            return null;
        }

        ByteBuffer buffer = SNAPSHOT_BUFFER.get();
        buffer.clear();
        EnumMap<ChunkSectionLayer, CapturingVertexConsumer> consumers = new EnumMap<>(ChunkSectionLayer.class);
        for (int slot = 0; slot < LAYER_COUNT; slot++) {
            consumers.put(
                    LAYERS[slot],
                    new CapturingVertexConsumer(LAYERS[slot], buffer, TABLE_END + slot * REGION_BYTES, REGION_BYTES));
        }

        List<BlockEntity> blockEntities = new ArrayList<>();
        VisGraph visGraph = new VisGraph();
        boolean[] unsupported = {false};
        BlockModelLighter.enableCaching();
        try {
            ModelBlockRenderer blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, blockColors);
            FluidRenderer fluidRenderer = new FluidRenderer(fluidSet);
            BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> {
                if (!quad.materialInfo().sprite().atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS)) {
                    unsupported[0] = true;
                    return;
                }
                ChunkSectionLayer layer = quad.materialInfo().layer();
                CapturingVertexConsumer consumer = consumers.get(layer);
                if (consumer == null) {
                    unsupported[0] = true;
                    return;
                }
                consumer.putBlockBakedQuad(x, y, z, quad, instance);
            };
            BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> {
                if (!quad.materialInfo().sprite().atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS)) {
                    unsupported[0] = true;
                    return;
                }
                consumers.get(ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);
            };
            FluidRenderer.Output fluidOutput = layer -> {
                CapturingVertexConsumer consumer = consumers.get(layer);
                if (consumer == null) {
                    throw new IllegalStateException("No capture consumer for fluid layer " + layer);
                }
                return consumer;
            };

            BlockPos minPos = sectionPos.origin();
            BlockPos maxPos = minPos.offset(15, 15, 15);
            for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
                BlockState blockState = region.getBlockState(pos);
                if (blockState.isAir()) {
                    continue;
                }
                if (blockState.isSolidRender()) {
                    visGraph.setOpaque(pos);
                }
                if (blockState.hasBlockEntity()) {
                    BlockEntity blockEntity = region.getBlockEntity(pos);
                    if (blockEntity != null) {
                        blockEntities.add(blockEntity);
                    }
                }
                FluidState fluidState = blockState.getFluidState();
                if (!fluidState.isEmpty()) {
                    fluidRenderer.tesselate(region, pos, fluidOutput, blockState, fluidState);
                }
                if (blockState.getRenderShape() == RenderShape.MODEL) {
                    BlockStateModel model = modelSet.get(blockState);
                    blockRenderer.tesselateBlock(
                            ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
                            SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ()),
                            region,
                            pos,
                            blockState,
                            model,
                            blockState.getSeed(pos));
                }
                if (unsupported[0]) {
                    return null;
                }
            }
        } catch (RuntimeException | Error failure) {
            LOGGER.debug("Native takeover capture failed for section {}", sectionPos, failure);
            return null;
        } finally {
            BlockModelLighter.clearCache();
        }

        for (CapturingVertexConsumer consumer : consumers.values()) {
            if (consumer.overflow()) {
                LOGGER.debug("Native capture overflowed for section {}", sectionPos);
                return null;
            }
        }
        if (unsupported[0]) {
            return null;
        }

        int openFaces = computeOpenFaces(sectionPos, region);
        return finishSnapshot(buffer, sectionPos, consumers, openFaces, mergeEnabled, blockEntities, visGraph.resolve());
    }

    /**
     * Shadow capture: copies vanilla's finished per-layer vertex buffers into
     * the WOM2 snapshot. Returns null when vanilla produced nothing usable.
     */
    static CapturedSection captureShadow(
            SectionPos sectionPos,
            RenderSectionRegion region,
            SectionCompiler.Results vanillaResults,
            boolean mergeEnabled) {
        ByteBuffer buffer = SNAPSHOT_BUFFER.get();
        buffer.clear();
        EnumMap<ChunkSectionLayer, CapturingVertexConsumer> consumers = new EnumMap<>(ChunkSectionLayer.class);
        for (int slot = 0; slot < LAYER_COUNT; slot++) {
            consumers.put(
                    LAYERS[slot],
                    new CapturingVertexConsumer(LAYERS[slot], buffer, TABLE_END + slot * REGION_BYTES, REGION_BYTES));
        }

        try {
            for (Map.Entry<ChunkSectionLayer, MeshData> entry : vanillaResults.renderedLayers.entrySet()) {
                ChunkSectionLayer layer = entry.getKey();
                CapturingVertexConsumer consumer = consumers.get(layer);
                if (consumer == null) {
                    return null;
                }
                MeshData mesh = entry.getValue();
                int vertexCount = mesh.drawState().vertexCount();
                if (vertexCount <= 0 || vertexCount > Native.MAX_VERTICES_PER_LAYER || vertexCount % 4 != 0) {
                    return null;
                }
                ByteBuffer vertices = mesh.vertexBuffer();
                int byteCount = vertexCount * Native.BLOCK_VERTEX_STRIDE;
                if (vertices.remaining() < byteCount) {
                    return null;
                }
                int base = TABLE_END + layerSlot(layer) * REGION_BYTES;
                ByteBuffer slice = vertices.duplicate();
                slice.limit(slice.position() + byteCount);
                buffer.position(base);
                buffer.put(slice);
                consumer.noteCopiedVertices(vertexCount);
            }
        } catch (RuntimeException failure) {
            LOGGER.debug("Native shadow capture failed for section {}", sectionPos, failure);
            return null;
        }

        int openFaces = computeOpenFaces(sectionPos, region);
        return finishSnapshot(
                buffer,
                sectionPos,
                consumers,
                openFaces,
                mergeEnabled,
                vanillaResults.blockEntities,
                vanillaResults.visibilitySet);
    }

    private static CapturedSection finishSnapshot(
            ByteBuffer buffer,
            SectionPos sectionPos,
            EnumMap<ChunkSectionLayer, CapturingVertexConsumer> consumers,
            int openFaces,
            boolean mergeEnabled,
            List<BlockEntity> blockEntities,
            VisibilitySet visibilitySet) {
        // Compact the fixed layer regions into a contiguous data area so the
        // declared total stays within the ABI's input-size limit.
        int[] vertexCounts = new int[LAYER_COUNT];
        int[] usedBytes = new int[LAYER_COUNT];
        int totalData = 0;
        for (int slot = 0; slot < LAYER_COUNT; slot++) {
            CapturingVertexConsumer consumer = consumers.get(LAYERS[slot]);
            int vertexCount = consumer == null ? 0 : consumer.vertexCount();
            if (vertexCount % 4 != 0) {
                return null;
            }
            vertexCounts[slot] = vertexCount;
            usedBytes[slot] = vertexCount * Native.BLOCK_VERTEX_STRIDE;
            totalData += usedBytes[slot];
        }
        int totalBytes = TABLE_END + totalData;
        if (totalBytes > Native.MAX_INPUT_BYTES) {
            LOGGER.debug("Native snapshot for section {} exceeds the input limit", sectionPos);
            return null;
        }

        byte[] compact = COMPACT_BUFFER.get();
        int target = TABLE_END;
        for (int slot = 0; slot < LAYER_COUNT; slot++) {
            if (usedBytes[slot] == 0) {
                continue;
            }
            int source = TABLE_END + slot * REGION_BYTES;
            if (source != target) {
                if (usedBytes[slot] > compact.length) {
                    return null;
                }
                buffer.position(source);
                buffer.get(compact, 0, usedBytes[slot]);
                buffer.position(target);
                buffer.put(compact, 0, usedBytes[slot]);
            }
            target += usedBytes[slot];
        }

        BlockPos origin = sectionPos.origin();
        buffer.putInt(0, MAGIC);
        buffer.putShort(4, (short) VERSION);
        buffer.putShort(6, (short) HEADER_BYTES);
        buffer.putShort(8, (short) LAYER_COUNT);
        buffer.putShort(10, (short) (mergeEnabled ? FLAG_MERGE_ENABLED : 0));
        buffer.putInt(12, origin.getX());
        buffer.putInt(16, origin.getY());
        buffer.putInt(20, origin.getZ());
        buffer.putInt(24, HEADER_BYTES);
        buffer.putInt(28, TABLE_END);
        buffer.putInt(32, totalBytes);
        buffer.put(36, (byte) (openFaces & 0x3f));
        buffer.put(37, (byte) 0);
        buffer.putShort(38, (short) 0);
        buffer.putInt(40, Native.MAX_VERTICES_PER_LAYER);
        for (int i = 44; i < HEADER_BYTES; i++) {
            buffer.put(i, (byte) 0);
        }

        for (int slot = 0; slot < LAYER_COUNT; slot++) {
            int entry = HEADER_BYTES + slot * LAYER_TABLE_BYTES;
            buffer.put(entry, (byte) Native.LAYER_IDS[slot]);
            buffer.put(entry + 1, (byte) 0);
            buffer.putShort(entry + 2, (short) 0);
            buffer.putInt(entry + 4, vertexCounts[slot]);
            buffer.putInt(entry + 8, usedBytes[slot]);
            buffer.putInt(entry + 12, dataOffset(slot, usedBytes));
        }

        buffer.position(0);
        buffer.limit(totalBytes);
        return new CapturedSection(
                buffer,
                totalBytes,
                openFaces,
                blockEntities,
                visibilitySet);
    }

    private static int dataOffset(int slot, int[] usedBytes) {
        int offset = TABLE_END;
        for (int i = 0; i < slot; i++) {
            offset += usedBytes[i];
        }
        return offset;
    }

    private static int layerSlot(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> 0;
            case CUTOUT -> 1;
            case TRANSLUCENT -> 2;
        };
    }

    private static int computeOpenFaces(SectionPos sectionPos, BlockAndTintGetter region) {
        BlockPos origin = sectionPos.origin();
        int openFaces = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                if (!region.getBlockState(pos.set(origin.getX() - 1, origin.getY() + i, origin.getZ() + j)).isSolidRender()) {
                    openFaces |= 1;
                }
                if (!region.getBlockState(pos.set(origin.getX() + 16, origin.getY() + i, origin.getZ() + j)).isSolidRender()) {
                    openFaces |= 1 << 1;
                }
                if (!region.getBlockState(pos.set(origin.getX() + i, origin.getY() - 1, origin.getZ() + j)).isSolidRender()) {
                    openFaces |= 1 << 2;
                }
                if (!region.getBlockState(pos.set(origin.getX() + i, origin.getY() + 16, origin.getZ() + j)).isSolidRender()) {
                    openFaces |= 1 << 3;
                }
                if (!region.getBlockState(pos.set(origin.getX() + i, origin.getY() + j, origin.getZ() - 1)).isSolidRender()) {
                    openFaces |= 1 << 4;
                }
                if (!region.getBlockState(pos.set(origin.getX() + i, origin.getY() + j, origin.getZ() + 16)).isSolidRender()) {
                    openFaces |= 1 << 5;
                }
            }
        }
        return openFaces;
    }
}
