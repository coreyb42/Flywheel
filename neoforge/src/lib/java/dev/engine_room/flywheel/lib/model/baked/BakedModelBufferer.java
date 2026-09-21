package dev.engine_room.flywheel.lib.model.baked;

import java.util.Iterator;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.engine_room.flywheel.lib.model.SimpleModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

final class BakedModelBufferer {
	private static final ThreadLocal<ThreadLocalObjects> THREAD_LOCAL_OBJECTS = ThreadLocal.withInitial(ThreadLocalObjects::new);

	private BakedModelBufferer() {}

	public static SimpleModel bufferModel(BlockStateModel model, BlockPos pos, BlockAndTintGetter level, BlockState state, @Nullable PoseStack poseStack, BlockMaterialFunction materialFunction) {
		ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
		poseStack = poseStack == null ? objects.identityPoseStack : poseStack;
		objects.emitters.prepare(materialFunction);
		objects.emitters.prepareForBlock();
		objects.emitter.prepareForBlock(poseStack);
		objects.blockRenderer.tesselateBlock(objects.emitter, 0, 0, 0, level, pos, state, model, state.getSeed(pos));
		return objects.emitters.end();
	}

	public static SimpleModel bufferBlocks(Iterator<BlockPos> positions, BlockAndTintGetter level, @Nullable PoseStack poseStack, boolean renderFluids, BlockMaterialFunction materialFunction) {
		ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
		poseStack = poseStack == null ? objects.identityPoseStack : poseStack;
		objects.emitters.prepare(materialFunction);
		BlockModelLighter.enableCaching();
		try {
			while (positions.hasNext()) {
				BlockPos pos = positions.next();
				BlockState state = level.getBlockState(pos);
				objects.emitters.prepareForBlock();
				if (renderFluids) {
					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty()) {
						objects.fluidOutput.prepare(objects.emitters, poseStack);
						objects.fluidRenderer.tesselate(level, pos, objects.fluidOutput, state, fluid);
					}
				}
				if (state.getRenderShape() == RenderShape.MODEL) {
					objects.emitter.prepareForBlock(poseStack);
					BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
					objects.blockRenderer.tesselateBlock(objects.emitter, pos.getX(), pos.getY(), pos.getZ(), level, pos, state, model, state.getSeed(pos));
				}
			}
		} finally {
			BlockModelLighter.clearCache();
			objects.fluidOutput.clear();
		}
		return objects.emitters.end();
	}

	private static class ThreadLocalObjects {
		final PoseStack identityPoseStack = new PoseStack();
		final MeshEmitterManager<NeoforgeMeshEmitter> emitters = new MeshEmitterManager<>(NeoforgeMeshEmitter::new);
		final NeoforgeMeshEmitter emitter = emitters.getEmitter(ChunkSectionLayer.SOLID);
		final ModelBlockRenderer blockRenderer = new ModelBlockRenderer(Minecraft.getInstance().options.ambientOcclusion().get(), true, Minecraft.getInstance().getBlockColors());
		final FluidRenderer fluidRenderer = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
		final FluidOutput fluidOutput = new FluidOutput();
	}

	private static class FluidOutput implements FluidRenderer.Output {
		private final TransformingVertexConsumer transforming = new TransformingVertexConsumer();
		private MeshEmitterManager<NeoforgeMeshEmitter> emitters;
		private PoseStack poseStack;

		void prepare(MeshEmitterManager<NeoforgeMeshEmitter> emitters, PoseStack poseStack) { this.emitters = emitters; this.poseStack = poseStack; }
		void clear() { transforming.clear(); emitters = null; poseStack = null; }

		@Override
		public VertexConsumer getBuilder(ChunkSectionLayer layer) {
			var buffer = emitters.getBuffer(layer, true, false);
			if (buffer == null) return DiscardingVertexConsumer.INSTANCE;
			transforming.prepare(buffer, poseStack);
			return transforming;
		}
	}

	private enum DiscardingVertexConsumer implements VertexConsumer {
		INSTANCE;
		@Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
		@Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
		@Override public VertexConsumer setColor(int color) { return this; }
		@Override public VertexConsumer setUv(float u, float v) { return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
		@Override public VertexConsumer setLineWidth(float width) { return this; }
	}
}
