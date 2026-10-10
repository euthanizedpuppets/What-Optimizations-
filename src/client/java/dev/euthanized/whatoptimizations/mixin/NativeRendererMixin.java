package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeRendererControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Polls completed native jobs and uploads their meshes at the tail of world
 * rendering (after vanilla's own chunk buffer uploads), and releases all native
 * GPU resources when the level renderer closes (world unload, dimension
 * change, shutdown).
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer", remap = false)
public abstract class NativeRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void whatOptimizations$renderNativeSections(CallbackInfo ci) {
        NativeRendererControls.onRenderTail();
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void whatOptimizations$shutdownNativeRenderer(CallbackInfo ci) {
        NativeRendererControls.shutdown();
    }
}
