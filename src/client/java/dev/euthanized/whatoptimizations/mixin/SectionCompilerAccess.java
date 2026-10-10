package dev.euthanized.whatoptimizations.mixin;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link SectionCompiler}'s private configuration so the native
 * capture can drive vanilla's own {@code ModelBlockRenderer}/{@code
 * FluidRenderer} with identical settings (AO, cutout leaves, model sets,
 * block colors). Implemented on {@code SectionCompiler} by mixin; cast the
 * compiler instance to this interface to read the values.
 */
@Mixin(SectionCompiler.class)
public interface SectionCompilerAccess {
    @Accessor("ambientOcclusion")
    boolean whatOptimizations$getAmbientOcclusion();

    @Accessor("cutoutLeaves")
    boolean whatOptimizations$getCutoutLeaves();

    @Accessor("blockModelSet")
    BlockStateModelSet whatOptimizations$getBlockModelSet();

    @Accessor("fluidModelSet")
    FluidStateModelSet whatOptimizations$getFluidModelSet();

    @Accessor("blockColors")
    BlockColors whatOptimizations$getBlockColors();
}
