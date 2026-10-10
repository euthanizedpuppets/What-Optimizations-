package net.minecraft.client.renderer.entity.state;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.world.entity.Crackiness.Level;

public class IronGolemRenderState extends LivingEntityRenderState {
	public float attackTicksRemaining;
	public int offerFlowerTick;
	public final BlockModelRenderState flowerBlock = new BlockModelRenderState();
	public Level crackiness = Level.NONE;
}
