package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeRendererControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runtime controls without a Fabric API dependency: F8 draw toggle, F9 debug, F7 mesher kill switch. */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class MinecraftTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void whatOptimizations$processHotkeys(CallbackInfo ci) {
        NativeRendererControls.tick();
    }
}
