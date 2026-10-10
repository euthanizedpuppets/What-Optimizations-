package dev.euthanized.whatoptimizations.mixin;

import dev.euthanized.whatoptimizations.NativeMeshDifferential;
import dev.euthanized.whatoptimizations.NativeRendererControls;
import dev.euthanized.whatoptimizations.NativeSectionMesher;
import dev.euthanized.whatoptimizations.NativeSectionOwnership;
import dev.euthanized.whatoptimizations.NativeTerrainRenderer;
import dev.euthanized.whatoptimizations.NativeTerrainStore;
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
        lines.add("What-Optimizations: native=" + (NativeRendererControls.rendererEnabled() ? "ON" : "OFF")
                + " [F8 toggle] | tiled=" + (NativeRendererControls.tiledEnabled() ? "ON" : "off")
                + " | F9 debug | F7 kill switch");
        lines.add("Native sections: active=" + NativeSectionOwnership.countByState(NativeSectionOwnership.State.ACTIVE)
                + " pending=" + NativeSectionOwnership.countByState(NativeSectionOwnership.State.PENDING)
                + " failed=" + NativeSectionOwnership.countByState(NativeSectionOwnership.State.FAILED)
                + " | jobs=" + NativeSectionMesher.submittedJobs()
                + "/" + NativeSectionMesher.completedJobs()
                + " pending=" + NativeSectionMesher.pendingJobs()
                + " stale=" + NativeSectionOwnership.staleResults()
                + " failures=" + NativeSectionMesher.failures());
        lines.add("Timings (us): vanilla compile=" + NativeSectionMesher.averageVanillaCompileMicros()
                + " capture=" + NativeSectionMesher.averageCaptureMicros()
                + " native queue=" + NativeSectionMesher.averageNativeMicros());
        lines.add("Native GPU: meshes=" + NativeTerrainStore.get().meshCount()
                + " bytes=" + (NativeTerrainStore.get().gpuBytes() / (1024L * 1024L)) + " MiB"
                + " | last frame: sections=" + NativeTerrainRenderer.lastSectionsDrawn()
                + " draws=" + NativeTerrainRenderer.lastDrawCalls()
                + " (+" + NativeTerrainRenderer.lastTiledDrawCalls() + " tiled)"
                + " vertices=" + NativeTerrainRenderer.lastVerticesDrawn());
        lines.add("Debug diff: " + (NativeSectionMesher.isDebugMode()
                ? "compared=" + NativeMeshDifferential.comparisons()
                + " mismatches=" + NativeMeshDifferential.mismatches()
                + " matchedVertices=" + NativeMeshDifferential.matchedVertices()
                : "off; press F9"));
    }
}
