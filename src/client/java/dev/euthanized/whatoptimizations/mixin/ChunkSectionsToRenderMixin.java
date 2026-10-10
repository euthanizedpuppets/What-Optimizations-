package dev.euthanized.whatoptimizations.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.euthanized.whatoptimizations.NativeTerrainRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws native-owned section meshes right after vanilla's terrain pass for the
 * same layer group, inside the same main frame pass, on the same render target
 * with the same samplers (see {@link NativeTerrainRenderer}).
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkSectionsToRender", remap = false)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("TAIL"))
    private void whatOptimizations$drawNativeSections(
            ChunkSectionLayerGroup group,
            GpuSampler sampler,
            CallbackInfo ci) {
        NativeTerrainRenderer.drawGroup(group, sampler);
    }
}
