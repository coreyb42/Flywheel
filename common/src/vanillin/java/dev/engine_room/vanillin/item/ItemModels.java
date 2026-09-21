package dev.engine_room.vanillin.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import dev.engine_room.vanillin.Vanillin;
import dev.engine_room.vanillin.mixin.item.ItemStackRenderStateAccessor;
import dev.engine_room.vanillin.mixin.item.ItemStackRenderStateLayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Converts native 26.2 item render-state layers into Flywheel meshes. */
public class ItemModels {
	public static final TagKey<Item> NO_INSTANCING = TagKey.create(Registries.ITEM, Vanillin.rl("no_instancing"));

	private static final Model EMPTY_MODEL = new SimpleModel(List.of());
	private static final RendererReloadCache<ResolvedItemModel, Model> MODEL_CACHE = new RendererReloadCache<>(ItemModels::bakeModel);

	public static boolean isSupported(ItemStack stack) {
		return !stack.is(NO_INSTANCING) && isSupported(resolve(stack, ItemDisplayContext.GROUND, null));
	}

	public static boolean isSupported(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner) {
		return !stack.is(NO_INSTANCING) && isSupported(resolve(stack, displayContext, owner));
	}

	public static Geometry geometry(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner) {
		var resolved = resolve(stack, displayContext, owner);
		return new Geometry(resolved.minY(), resolved.zSize());
	}

	public static Model get(Level level, ItemStack stack, ItemDisplayContext displayContext) {
		return get(level, stack, displayContext, null);
	}

	public static Model get(Level level, ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner) {
		var resolved = resolve(stack, displayContext, owner);
		return isSupported(resolved) ? MODEL_CACHE.get(resolved) : EMPTY_MODEL;
	}

	private static ResolvedItemModel resolve(ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner) {
		if (stack.isEmpty()) {
			return ResolvedItemModel.EMPTY;
		}

		var state = new ItemStackRenderState();
		var level = Minecraft.getInstance().level;
		Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, stack, displayContext, level, owner, 0);

		var stateAccessor = (ItemStackRenderStateAccessor) state;
		var layers = stateAccessor.vanillin$layers();
		var resolvedLayers = new ArrayList<ResolvedLayer>(stateAccessor.vanillin$activeLayerCount());

		for (int i = 0; i < stateAccessor.vanillin$activeLayerCount(); i++) {
			var layer = (ItemStackRenderStateLayerAccessor) layers[i];
			resolvedLayers.add(new ResolvedLayer(List.copyOf(layer.vanillin$quads()), layer.vanillin$itemTransform(), new Matrix4f(layer.vanillin$localTransform()), layer.vanillin$foilType(), layer.vanillin$tintLayers(), layer.vanillin$specialRenderer() != null));
		}

		var bounds = state.getModelBoundingBox();
		return new ResolvedItemModel(List.copyOf(resolvedLayers), state.isAnimated(), (float) bounds.minY, (float) bounds.getZsize());
	}

	private static boolean isSupported(ResolvedItemModel model) {
		if (model.layers().isEmpty() || model.animated()) {
			return false;
		}

		for (var layer : model.layers()) {
			if (layer.specialRenderer() || layer.tintLayers() != null && !layer.tintLayers().isEmpty() || layer.foilType() == ItemStackRenderState.FoilType.SPECIAL) {
				return false;
			}
		}
		return true;
	}

	private static Model bakeModel(ResolvedItemModel model) {
		var configuredMeshes = new ArrayList<Model.ConfiguredMesh>();
		for (var layer : model.layers()) {
			Map<Material, List<BakedQuad>> quadsByMaterial = layer.quads().stream().collect(Collectors.groupingBy(quad -> materialFor(quad.materialInfo().itemRenderType())));
			for (var entry : quadsByMaterial.entrySet()) {
				var mesh = bakeMesh(entry.getValue(), layer.itemTransform(), layer.localTransform());
				configuredMeshes.add(new Model.ConfiguredMesh(entry.getKey(), mesh));
				if (layer.foilType() == ItemStackRenderState.FoilType.STANDARD) {
					configuredMeshes.add(new Model.ConfiguredMesh(Materials.GLINT, mesh));
				}
			}
		}
		return new SimpleModel(configuredMeshes);
	}

	private static Material materialFor(RenderType renderType) {
		var material = ModelUtil.getItemMaterial(renderType);
		if (material == null) return Materials.TRANSLUCENT_ENTITY;
		return material.transparency() == Transparency.TRANSLUCENT ? SimpleMaterial.builderOf(material).transparency(Transparency.ORDER_INDEPENDENT).build() : material;
	}

	private static Mesh bakeMesh(List<BakedQuad> quads, ItemTransform itemTransform, Matrix4f localTransform) {
		var poseStack = new PoseStack();
		itemTransform.apply(false, poseStack.last());
		poseStack.last().mulPose(localTransform);
		poseStack.translate(-0.5f, -0.5f, -0.5f);
		var memoryBlock = MemoryBlock.mallocTracked(quads.size() * BakedQuad.VERTEX_COUNT * FullVertexView.STRIDE);
		var meshVertices = new FullVertexView();
		meshVertices.nativeMemoryOwner(memoryBlock);
		meshVertices.ptr(memoryBlock.ptr());
		meshVertices.vertexCount(quads.size() * BakedQuad.VERTEX_COUNT);
		var position = new Vector4f();
		var normal = new Vector3f();
		Matrix4f poseMatrix = poseStack.last().pose();
		Matrix3f normalMatrix = poseStack.last().normal();
		int vertex = 0;
		for (var quad : quads) {
			SodiumAnimatedTextureCompat.add(quad.materialInfo().sprite());
			Direction direction = quad.direction();
			normal.set(direction.getStepX(), direction.getStepY(), direction.getStepZ()).mul(normalMatrix);
			for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
				Vector3fc sourcePosition = quad.position(i);
				position.set(sourcePosition.x(), sourcePosition.y(), sourcePosition.z(), 1.0f).mul(poseMatrix);
				long packedUv = quad.packedUV(i);
				meshVertices.x(vertex, position.x()); meshVertices.y(vertex, position.y()); meshVertices.z(vertex, position.z());
				meshVertices.r(vertex, 1.0f); meshVertices.g(vertex, 1.0f); meshVertices.b(vertex, 1.0f); meshVertices.a(vertex, 1.0f);
				meshVertices.u(vertex, Float.intBitsToFloat((int) packedUv));
				meshVertices.v(vertex, Float.intBitsToFloat((int) (packedUv >>> 32)));
				meshVertices.overlay(vertex, 0); meshVertices.light(vertex, 0);
				meshVertices.normalX(vertex, normal.x()); meshVertices.normalY(vertex, normal.y()); meshVertices.normalZ(vertex, normal.z());
				vertex++;
			}
		}
		return new SimpleQuadMesh(meshVertices);
	}

	public record Geometry(float minY, float zSize) {
	}

	private record ResolvedItemModel(List<ResolvedLayer> layers, boolean animated, float minY, float zSize) {
		private static final ResolvedItemModel EMPTY = new ResolvedItemModel(List.of(), false, 0, 0);
	}

	private record ResolvedLayer(List<BakedQuad> quads, ItemTransform itemTransform, Matrix4f localTransform, ItemStackRenderState.FoilType foilType, @Nullable List<Integer> tintLayers, boolean specialRenderer) {
	}
}
