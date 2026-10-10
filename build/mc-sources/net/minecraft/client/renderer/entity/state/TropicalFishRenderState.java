package net.minecraft.client.renderer.entity.state;

import net.minecraft.world.entity.animal.fish.TropicalFish.Pattern;

public class TropicalFishRenderState extends LivingEntityRenderState {
	public Pattern pattern = Pattern.FLOPPER;
	public int baseColor = -1;
	public int patternColor = -1;
}
