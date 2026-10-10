package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws native-owned section meshes through Minecraft's own terrain pipelines.
 *
 * <p>Injected at the tail of {@code ChunkSectionsToRender.renderGroup}, this
 * runs inside the main frame pass, right after vanilla's terrain pass for the
 * same layer group, on the same render target with the same samplers, the same
 * shared sequential QUADS index buffer and the same per-section
 * {@code ChunkSection} uniform data. Passthrough geometry uses vanilla's
 * {@code SOLID_TERRAIN}/{@code CUTOUT_TERRAIN}/{@code TRANSLUCENT_TERRAIN}
 * pipelines directly; merged geometry uses the opt-in tiled pipelines (see
 * {@link NativeTiledPipelines}).
 *
 * <p>Visibility comes from vanilla's own {@code visibleSections} list (frustum
 * + occlusion graph), so native sections are never drawn when vanilla would not
 * draw them — the draw list is conservative by construction.
 */
public final class NativeTerrainRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/terrain");

    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

    private static volatile int lastDrawCalls;
    private static volatile int lastVerticesDrawn;
    private static volatile int lastSectionsDrawn;
    private static volatile int lastTiledDrawCalls;
    private static long lastFailureLog;

    private NativeTerrainRenderer() {
    }

    static int layerBit(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> NativeSectionOwnership.LAYER_SOLID_BIT;
            case CUTOUT -> NativeSectionOwnership.LAYER_CUTOUT_BIT;
            case TRANSLUCENT -> NativeSectionOwnership.LAYER_TRANSLUCENT_BIT;
        };
    }

    private static int activeLayerMask(long sectionKey) {
        if (!NativeSectionOwnership.isDrawable(sectionKey)) {
            return 0;
        }
        NativeSectionOwnership.Entry entry = NativeSectionOwnership.entry(sectionKey);
        return entry.activeLayers;
    }

    /**
     * Render thread, inside the main frame pass. Draws every visible section
     * that the native renderer owns for the group's layers.
     */
    public static void drawGroup(ChunkSectionLayerGroup group, GpuSampler sampler) {
        if (!NativeRendererControls.rendererEnabled() || !NativeLoader.isLoaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderer levelRenderer = minecraft.levelRenderer;
        if (levelRenderer == null || minecraft.level == null) {
            return;
        }
        List<SectionRenderDispatcher.RenderSection> visible = levelRenderer.visibleSections();
        if (visible.isEmpty()) {
            return;
        }

        boolean tiled = NativeRendererControls.tiledEnabled() && NativeTiledPipelines.isAvailable();
        NativeTerrainStore store = NativeTerrainStore.get();
        if (store.isClosed()) {
            store.ensureDevicePublic();
        }

        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> vanillaDraws =
                new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> tiledDraws =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : LAYERS) {
            vanillaDraws.put(layer, new ArrayList<>());
            tiledDraws.put(layer, new ArrayList<>());
        }
        List<DynamicUniforms.ChunkSectionInfo> infos = new ArrayList<>();
        Map<Long, Integer> uboIndexBySection = new HashMap<>();
        int[] maxIndices = new int[LAYERS.length];

        GpuTextureView atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int atlasWidth = atlas.getWidth(0);
        int atlasHeight = atlas.getHeight(0);
        Matrix4fc viewRotation = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix;
        long now = Util.getMillis();

        for (SectionRenderDispatcher.RenderSection section : visible) {
            BlockPos origin = section.getRenderOrigin();
            long sectionKey = NativeSectionOwnership.sectionKey(origin);
            int activeLayers = activeLayerMask(sectionKey);
            if (activeLayers == 0) {
                continue;
            }
            boolean anyInGroup = false;
            for (ChunkSectionLayer layer : group.layers()) {
                if ((activeLayers & layerBit(layer)) != 0 && store.drawInfo(sectionKey, layer) != null) {
                    anyInGroup = true;
                    break;
                }
            }
            if (!anyInGroup) {
                continue;
            }

            int uboIndex = infos.size();
            uboIndexBySection.put(sectionKey, uboIndex);
            infos.add(new DynamicUniforms.ChunkSectionInfo(
                    new Matrix4f(viewRotation),
                    origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    section.getVisibility(now),
                    atlasWidth,
                    atlasHeight));

            for (ChunkSectionLayer layer : group.layers()) {
                if ((activeLayers & layerBit(layer)) == 0) {
                    continue;
                }
                NativeTerrainStore.DrawInfo info = store.drawInfo(sectionKey, layer);
                if (info == null) {
                    continue;
                }
                if (info.passthroughIndexCount() > 0) {
                    int finalUboIndex = uboIndex;
                    vanillaDraws.get(layer).add(new RenderPass.Draw<>(
                            0,
                            info.buffer(),
                            null,
                            null,
                            0,
                            info.passthroughIndexCount(),
                            info.passthroughBaseVertex(),
                            (sectionUbos, uploader) -> uploader.upload("ChunkSection", sectionUbos[finalUboIndex])));
                    maxIndices[layer.ordinal()] = Math.max(maxIndices[layer.ordinal()], info.passthroughIndexCount());
                }
                if (tiled && info.mergedIndexCount() > 0) {
                    int finalUboIndex = uboIndex;
                    tiledDraws.get(layer).add(new RenderPass.Draw<>(
                            0,
                            info.buffer(),
                            null,
                            null,
                            0,
                            info.mergedIndexCount(),
                            info.mergedBaseVertex(),
                            (sectionUbos, uploader) -> uploader.upload("ChunkSection", sectionUbos[finalUboIndex])));
                    maxIndices[layer.ordinal()] = Math.max(maxIndices[layer.ordinal()], info.mergedIndexCount());
                }
            }
        }

        if (infos.isEmpty()) {
            return;
        }

        GpuBufferSlice[] sectionInfos = RenderSystem.getDynamicUniforms()
                .writeChunkSections(infos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        RenderTarget target = group.outputTarget();

        int drawCalls = 0;
        int tiledDrawCalls = 0;
        int verticesDrawn = 0;
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Native terrain sections for " + group.label(),
                        target.getColorTextureView(),
                        Optional.empty(),
                        target.getDepthTextureView(),
                        OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("Sampler0", atlas, sampler);
            renderPass.bindTexture(
                    "Sampler2",
                    minecraft.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

            for (ChunkSectionLayer layer : group.layers()) {
                List<RenderPass.Draw<GpuBufferSlice[]>> vanilla = vanillaDraws.get(layer);
                if (!vanilla.isEmpty()) {
                    List<RenderPass.Draw<GpuBufferSlice[]>> draws =
                            layer == ChunkSectionLayer.TRANSLUCENT ? vanilla.reversed() : vanilla;
                    renderPass.setPipeline(layer.pipeline());
                    drawLayer(renderPass, draws, maxIndices[layer.ordinal()], sectionInfos);
                    drawCalls++;
                    for (RenderPass.Draw<GpuBufferSlice[]> draw : draws) {
                        verticesDrawn += draw.indexCount() / 6 * 4;
                    }
                }

                List<RenderPass.Draw<GpuBufferSlice[]>> merged = tiledDraws.get(layer);
                if (tiled && !merged.isEmpty()) {
                    RenderPipeline tiledPipeline = NativeTiledPipelines.forLayer(layer);
                    if (tiledPipeline != null) {
                        List<RenderPass.Draw<GpuBufferSlice[]>> draws =
                                layer == ChunkSectionLayer.TRANSLUCENT ? merged.reversed() : merged;
                        try {
                            renderPass.setPipeline(tiledPipeline);
                            drawLayer(renderPass, draws, maxIndices[layer.ordinal()], sectionInfos);
                            tiledDrawCalls++;
                            for (RenderPass.Draw<GpuBufferSlice[]> draw : draws) {
                                verticesDrawn += draw.indexCount() / 6 * 4;
                            }
                        } catch (RuntimeException failure) {
                            NativeTiledPipelines.markFailed(String.valueOf(failure.getMessage()));
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            long nowNanos = System.nanoTime();
            if (nowNanos - lastFailureLog > 5_000_000_000L) {
                lastFailureLog = nowNanos;
                LOGGER.warn("Native terrain draw pass failed; sections fall back to vanilla next frame", failure);
            }
            NativeSectionOwnership.clearAll();
            store.close();
            return;
        }

        lastDrawCalls = drawCalls;
        lastTiledDrawCalls = tiledDrawCalls;
        lastVerticesDrawn = verticesDrawn;
        lastSectionsDrawn = infos.size();
    }

    private static void drawLayer(
            RenderPass renderPass,
            List<RenderPass.Draw<GpuBufferSlice[]>> draws,
            int maxIndices,
            GpuBufferSlice[] sectionInfos) {
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer defaultIndexBuffer = autoIndices.getBuffer(maxIndices);
        IndexType defaultIndexType = autoIndices.type();
        renderPass.drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, List.of("ChunkSection"), sectionInfos);
    }

    /** Render thread: level unload / shutdown. */
    public static void shutdown() {
        NativeTerrainStore.get().close();
        NativeSectionOwnership.clearAll();
        lastDrawCalls = 0;
        lastVerticesDrawn = 0;
        lastSectionsDrawn = 0;
        lastTiledDrawCalls = 0;
    }

    public static int lastDrawCalls() {
        return lastDrawCalls;
    }

    public static int lastTiledDrawCalls() {
        return lastTiledDrawCalls;
    }

    public static int lastVerticesDrawn() {
        return lastVerticesDrawn;
    }

    public static int lastSectionsDrawn() {
        return lastSectionsDrawn;
    }
}
