package net.minecraft.client.renderer.entity.state;

import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.animal.rabbit.Rabbit.Variant;

public class RabbitRenderState extends LivingEntityRenderState {
	public float jumpCompletion;
	public boolean isToast;
	public Variant variant = Variant.DEFAULT;
	public final AnimationState hopAnimationState = new AnimationState();
	public final AnimationState idleHeadTiltAnimationState = new AnimationState();
}
