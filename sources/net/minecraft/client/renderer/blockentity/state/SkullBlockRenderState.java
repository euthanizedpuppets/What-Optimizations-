package net.minecraft.client.renderer.blockentity.state;

import com.mojang.math.Transformation;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.block.SkullBlock.Type;
import net.minecraft.world.level.block.SkullBlock.Types;

public class SkullBlockRenderState extends BlockEntityRenderState {
	public float animationProgress;
	public Transformation transformation = Transformation.IDENTITY;
	public Type skullType = Types.ZOMBIE;
	public RenderType renderType;
}
