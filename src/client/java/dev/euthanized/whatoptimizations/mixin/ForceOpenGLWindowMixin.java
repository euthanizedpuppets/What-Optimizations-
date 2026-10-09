package dev.euthanized.whatoptimizations.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 26.2 target adapted from BackToGL's public 26.2 implementation.
 * Verify this injection still matches the exact game jar after any update.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.Window", remap = false)
public abstract class ForceOpenGLWindowMixin {
    @Inject(method = "createGlfwWindow", at = @At("HEAD"))
    private static void whatOptimizations$preferOpenGL(CallbackInfoReturnable<Long> cir) {
        System.setProperty("blaze3d.backend", "opengl");
    }
}
