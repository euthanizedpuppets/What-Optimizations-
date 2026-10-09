package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeRendererControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Polls completed tickets and issues the optional GL pass at the end of world rendering. */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer", remap = false)
public abstract class NativeRendererMixin {
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void whatOptimizations$renderNativeSections(CallbackInfo ci) {
        NativeRendererControls.onRenderTail();
    }
}
