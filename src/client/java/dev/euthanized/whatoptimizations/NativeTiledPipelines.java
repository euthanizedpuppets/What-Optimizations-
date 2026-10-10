package dev.euthanized.whatoptimizations;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom terrain pipelines for the greedy-merged "tiled" vertex stream.
 *
 * <p>The pipelines mirror vanilla's {@code SOLID_TERRAIN} / {@code
 * CUTOUT_TERRAIN} / {@code TRANSLUCENT_TERRAIN} exactly (same bind groups,
 * topology, depth state, blend state and alpha cutout defines) but use a
 * 44-byte vertex format that adds a per-vertex sprite rectangle, and ship a
 * terrain shader that reconstructs per-block texturing from block-unit UVs:
 * {@code atlasUV = spriteRect.xy + fract(uv) * spriteRect.zw}.
 *
 * <p>Merged output drawn through these pipelines is pixel-identical to vanilla
 * whenever the Rust merge predicate holds (see docs/NATIVE_TERRAIN_PIPELINE.md).
 * The feature is opt-in ({@code -Dwhatoptimizations.nativeRenderer.tiled=true})
 * because the custom shader cannot be exercised by CI; the default path uses
 * vanilla's own pipelines with byte-identical passthrough vertices.
 */
final class NativeTiledPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("what-optimizations/tiled");

    private static final VertexFormat TILED_BLOCK = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("SpriteRect", GpuFormat.RGBA32_FLOAT)
            .build();

    private static volatile RenderPipeline solid;
    private static volatile RenderPipeline cutout;
    private static volatile RenderPipeline translucent;
    private static volatile boolean failed;
    private static long lastFailureLog;

    private NativeTiledPipelines() {
    }

    static boolean isAvailable() {
        return !failed;
    }

    static void markFailed(String reason) {
        failed = true;
        long now = System.nanoTime();
        if (now - lastFailureLog > 5_000_000_000L) {
            lastFailureLog = now;
            LOGGER.warn("Native tiled terrain pipelines unavailable: {}", reason);
        }
    }

    static RenderPipeline forLayer(ChunkSectionLayer layer) {
        if (failed) {
            return null;
        }
        return switch (layer) {
            case SOLID -> solidPipeline();
            case CUTOUT -> cutoutPipeline();
            case TRANSLUCENT -> translucentPipeline();
        };
    }

    private static RenderPipeline solidPipeline() {
        RenderPipeline pipeline = solid;
        if (pipeline == null) {
            synchronized (NativeTiledPipelines.class) {
                pipeline = solid;
                if (pipeline == null) {
                    pipeline = build("solid", null, 0.0F);
                    solid = pipeline;
                }
            }
        }
        return pipeline;
    }

    private static RenderPipeline cutoutPipeline() {
        RenderPipeline pipeline = cutout;
        if (pipeline == null) {
            synchronized (NativeTiledPipelines.class) {
                pipeline = cutout;
                if (pipeline == null) {
                    pipeline = build("cutout", null, 0.5F);
                    cutout = pipeline;
                }
            }
        }
        return pipeline;
    }

    private static RenderPipeline translucentPipeline() {
        RenderPipeline pipeline = translucent;
        if (pipeline == null) {
            synchronized (NativeTiledPipelines.class) {
                pipeline = translucent;
                if (pipeline == null) {
                    pipeline = build(
                            "translucent",
                            new ColorTargetState(BlendFunction.TRANSLUCENT),
                            0.1F);
                    translucent = pipeline;
                }
            }
        }
        return pipeline;
    }

    private static RenderPipeline build(String name, ColorTargetState blend, float alphaCutout) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
                .withVertexBinding(0, TILED_BLOCK)
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withVertexShader(Identifier.fromNamespaceAndPath("whatoptimizations", "core/terrain_tiled"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("whatoptimizations", "core/terrain_tiled"))
                .withLocation(Identifier.fromNamespaceAndPath("whatoptimizations", "pipeline/terrain_tiled_" + name));
        if (blend != null) {
            builder = builder.withColorTargetState(blend);
        }
        if (alphaCutout > 0.0F) {
            builder = builder.withShaderDefine("ALPHA_CUTOUT", alphaCutout);
        }
        return builder.build();
    }
}
