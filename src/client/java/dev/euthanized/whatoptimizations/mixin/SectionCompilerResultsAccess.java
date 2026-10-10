package dev.euthanized.whatoptimizations.mixin;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code SectionCompiler.Results.visibilitySet} is final; the native takeover
 * builds a {@code Results} whose visibility set must be the captured
 * {@code VisGraph.resolve()} so vanilla's occlusion graph keeps culling
 * correctly for natively-rendered sections. Implemented on {@code Results} by
 * mixin; cast the results instance to this interface to set the field.
 */
@Mixin(SectionCompiler.Results.class)
public interface SectionCompilerResultsAccess {
    @Mutable
    @Accessor("visibilitySet")
    void whatOptimizations$setVisibilitySet(VisibilitySet visibilitySet);
}
