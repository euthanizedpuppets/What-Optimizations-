package net.minecraft.client.renderer.entity.state;

import net.minecraft.world.entity.animal.equine.Llama.Variant;
import net.minecraft.world.item.ItemStack;

public class LlamaRenderState extends LivingEntityRenderState {
	public Variant variant = Variant.DEFAULT;
	public boolean hasChest;
	public ItemStack bodyItem = ItemStack.EMPTY;
	public boolean isTraderLlama;
}
