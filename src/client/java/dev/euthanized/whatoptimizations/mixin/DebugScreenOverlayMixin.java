package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeRendererControls;
import dev.euthanized.whatoptimizations.NativeSectionMesher;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds native renderer telemetry to the left column of Minecraft 26.2's F3 overlay. */
@Mixin(targets = "net.minecraft.client.gui.components.DebugScreenOverlay", remap = false)
public abstract class DebugScreenOverlayMixin {
    @Inject(method = "extractLines", at = @At("HEAD"))
    private void whatOptimizations$addNativeRendererStats(
            GuiGraphicsExtractor graphics,
            List<String> lines,
            boolean alignLeft,
            CallbackInfo ci) {
        if (!alignLeft) {
            return;
        }
        lines.add("What-Optimizations: draw=" + (NativeRendererControls.rendererEnabled() ? "ON" : "OFF")
                + " [F8 toggle] | F9 diagnostics | F7 stop/start Rust mesher");
        lines.add("Native sections: callbacks=" + NativeSectionMesher.callbacks()
                + " selected=" + NativeSectionMesher.sampledSections()
                + " complete=" + NativeSectionMesher.completedSections()
                + " pending=" + NativeSectionMesher.pendingJobs()
                + " stale=" + NativeSectionMesher.staleResults()
                + " failures=" + NativeSectionMesher.failures());
        lines.add("Section timings (us): vanilla compile=" + NativeSectionMesher.averageVanillaCompileMicros()
                + " snapshot=" + NativeSectionMesher.averageSnapshotMicros()
                + " Rust queue-to-poll=" + NativeSectionMesher.averageNativeQueueMicros()
                + " copy/cache=" + NativeSectionMesher.averageCopyCacheMicros());
        lines.add("Visible native sections=" + NativeRendererControls.visibleSections()
                + " / tracked section nodes=" + NativeRendererControls.totalSections());
        lines.add("Debug diff=" + (NativeSectionMesher.isDebugMode()
                ? "vanilla SOLID geometry + sync/async Rust output" : "off; press F9"));
    }
}
