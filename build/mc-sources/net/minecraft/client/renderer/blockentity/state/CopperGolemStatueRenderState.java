package net.minecraft.client.renderer.blockentity.state;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.CopperGolemStatueBlock.Pose;
import net.minecraft.world.level.block.WeatheringCopper.WeatherState;

public class CopperGolemStatueRenderState extends BlockEntityRenderState {
	public Pose pose = Pose.STANDING;
	public Direction direction = Direction.NORTH;
	public WeatherState oxidationState = WeatherState.UNAFFECTED;
}
