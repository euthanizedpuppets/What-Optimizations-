package net.minecraft.client.renderer.entity.state;

import net.minecraft.world.entity.Display.TextDisplay.CachedInfo;
import net.minecraft.world.entity.Display.TextDisplay.TextRenderState;
import org.jspecify.annotations.Nullable;

public class TextDisplayEntityRenderState extends DisplayEntityRenderState {
	public @Nullable TextRenderState textRenderState;
	public @Nullable CachedInfo cachedInfo;

	@Override
	public boolean hasSubState() {
		return this.textRenderState != null;
	}
}
