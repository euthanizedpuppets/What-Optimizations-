package net.minecraft.client.renderer.blockentity.state;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity.ErrorMarker;

public class TestInstanceRenderState extends BlockEntityRenderState {
	public BeaconRenderState beaconRenderState;
	public BlockEntityWithBoundingBoxRenderState blockEntityWithBoundingBoxRenderState;
	public final List<ErrorMarker> errorMarkers = new ArrayList<>();
}
