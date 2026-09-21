package dev.engine_room.vanillin.mixin.item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.item.ItemStackRenderState;

@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
	@Accessor("activeLayerCount") int vanillin$activeLayerCount();
	@Accessor("layers") ItemStackRenderState.LayerRenderState[] vanillin$layers();
}
