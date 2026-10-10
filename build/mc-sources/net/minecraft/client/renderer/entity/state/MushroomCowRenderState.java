package net.minecraft.client.renderer.entity.state;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.world.entity.animal.cow.MushroomCow.Variant;

public class MushroomCowRenderState extends LivingEntityRenderState {
	public Variant variant = Variant.RED;
	public final BlockModelRenderState mushroomModel = new BlockModelRenderState();
}
