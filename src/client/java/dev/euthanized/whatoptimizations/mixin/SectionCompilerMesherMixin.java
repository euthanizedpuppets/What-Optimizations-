package dev.euthanized.whatoptimizations.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.euthanized.whatoptimizations.NativeSectionMesher;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drives the native terrain pipeline from vanilla's section compilation.
 *
 * <p>HEAD: when the section is natively ACTIVE, the takeover capture replaces
 * vanilla's per-layer meshing (empty rendered layers in the returned Results).
 * In debug mode the capture runs alongside vanilla for comparison.
 *
 * <p>RETURN: shadow capture — vanilla's finished per-layer vertex buffers are
 * copied into a WOM2 snapshot and submitted to Rust. Timing is only measured
 * when vanilla actually compiled (not for taken-over compiles).
 *
 * <p>Target signatures verified against Minecraft 26.2 Mojang-mapped sources.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCompiler", remap = false)
public abstract class SectionCompilerMesherMixin {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void whatOptimizations$nativeTakeover(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        NativeSectionMesher.beginVanillaCompileTiming();
        if (NativeSectionMesher.isDebugMode()) {
            NativeSectionMesher.captureForDebug(this, sectionPos, region);
            return;
        }
        SectionCompiler.Results replacement =
                NativeSectionMesher.onCompileHead(this, sectionPos, region);
        if (replacement != null) {
            cir.setReturnValue(replacement);
        }
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void whatOptimizations$nativeShadowCapture(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        NativeSectionMesher.onCompileReturn(sectionPos, region, cir.getReturnValue());
    }
}
