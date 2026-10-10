package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeSectionOwnership;
import dev.euthanized.whatoptimizations.NativeTerrainStore;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The view area recycles {@code RenderSection} objects as the camera moves.
 * When a section is reset (before its node is reassigned), drop the native
 * ownership entry and GPU allocation for the old node so stale geometry is
 * never drawn for a recycled section.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection", remap = false)
public abstract class RenderSectionResetMixin {
    @Inject(method = "reset", at = @At("TAIL"))
    private void whatOptimizations$releaseNativeSection(CallbackInfo ci) {
        SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) (Object) this;
        long sectionKey = NativeSectionOwnership.sectionKey(section.getSectionNode());
        NativeTerrainStore.get().removeSection(sectionKey);
        NativeSectionOwnership.remove(sectionKey);
    }
}
