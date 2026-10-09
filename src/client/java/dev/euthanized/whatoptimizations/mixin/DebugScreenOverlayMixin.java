package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeRendererControls;
import dev.euthanized.whatoptimizations.NativeSectionMesher;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds renderer telemetry to the F3 game-information panel. */
@Mixin(targets = "net.minecraft.client.gui.components.DebugScreenOverlay", remap = false)
public abstract class DebugScreenOverlayMixin {
    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void whatOptimizations$addNativeRendererStats(CallbackInfoReturnable<List<String>> cir) {
        List<String> vanilla = cir.getReturnValue();
        if (vanilla == null) {
            return;
        }
        List<String> lines = new ArrayList<>(vanilla);
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
                ? "Rust synchronous vs asynchronous output" : "off; press F9"));
        return cir.setReturnValue(lines);
    }
}
