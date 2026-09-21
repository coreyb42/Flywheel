package dev.engine_room.vanillin.mixin.item;

import java.util.List;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemStackRenderStateLayerAccessor {
	@Accessor("quads") List<BakedQuad> vanillin$quads();
	@Accessor("itemTransform") ItemTransform vanillin$itemTransform();
	@Accessor("localTransform") Matrix4f vanillin$localTransform();
	@Accessor("foilType") ItemStackRenderState.FoilType vanillin$foilType();
	@Accessor("tintLayers") IntList vanillin$tintLayers();
	@Accessor("specialRenderer") SpecialModelRenderer<?> vanillin$specialRenderer();
}
