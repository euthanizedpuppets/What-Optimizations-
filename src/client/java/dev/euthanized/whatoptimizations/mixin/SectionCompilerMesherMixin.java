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
 * Exact target signature was verified from Minecraft 26.2 generated sources.
 * A RETURN injection keeps the vanilla mesh and its special render layers
 * intact while Phase 1 tests the new native output in shadow mode.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCompiler", remap = false)
public abstract class SectionCompilerMesherMixin {
    @Inject(method = "compile", at = @At("HEAD"))
    private void whatOptimizations$startVanillaTiming(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        NativeSectionMesher.beginVanillaCompileTiming();
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void whatOptimizations$meshShadow(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        NativeSectionMesher.endVanillaCompileTiming();
        NativeSectionMesher.compileShadow(sectionPos, region);
    }
}
