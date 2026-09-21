package dev.engine_room.flywheel.lib.model.baked;

import java.util.Iterator;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.lib.model.SimpleModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

final class BakedModelBufferer {
	private static final ThreadLocal<ThreadLocalObjects> THREAD_LOCAL_OBJECTS = ThreadLocal.withInitial(ThreadLocalObjects::new);

	private BakedModelBufferer() {
	}

	public static SimpleModel bufferModel(BlockStateModel model, BlockPos pos, BlockAndTintGetter level, BlockState state, @Nullable PoseStack poseStack, BlockMaterialFunction blockMaterialFunction) {
		ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
		FabricMeshEmitterManager emitters = objects.emitters;
		emitters.prepare(blockMaterialFunction);

		boolean useAo = Minecraft.getInstance().options.ambientOcclusion().get();
		emitters.prepareForModel(useAo);
		objects.blockRenderer.tesselateBlock(emitters, 0, 0, 0, level, pos, state, model, state.getSeed(pos));

		return emitters.end();
	}

	public static SimpleModel bufferBlocks(Iterator<BlockPos> posIterator, BlockAndTintGetter level, @Nullable PoseStack poseStack, boolean renderFluids, BlockMaterialFunction blockMaterialFunction) {
		ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
		FabricMeshEmitterManager emitters = objects.emitters;
		emitters.prepare(blockMaterialFunction);

		BlockModelLighter.enableCaching();
		boolean useAo = Minecraft.getInstance().options.ambientOcclusion().get();

		while (posIterator.hasNext()) {
			BlockPos pos = posIterator.next();
			BlockState state = level.getBlockState(pos);
			emitters.prepareForBlock();

			if (renderFluids) {
				FluidState fluidState = state.getFluidState();
				if (!fluidState.isEmpty()) {
					objects.fluidRenderer.tesselate(level, pos,
						layer -> emitters.getBuffer(layer, true, false), state, fluidState);
				}
			}

			if (state.getRenderShape() == RenderShape.MODEL) {
				emitters.prepareForModel(useAo);
				BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
				objects.blockRenderer.tesselateBlock(emitters, pos.getX(), pos.getY(), pos.getZ(), level, pos, state, model, state.getSeed(pos));
			}
		}

		BlockModelLighter.clearCache();
		return emitters.end();
	}

	private static class ThreadLocalObjects {
		private final FabricMeshEmitterManager emitters = new FabricMeshEmitterManager();
		private final ModelBlockRenderer blockRenderer = new ModelBlockRenderer(true, true, Minecraft.getInstance().getBlockColors());
		private final FluidRenderer fluidRenderer = new FluidRenderer(Minecraft.getInstance().getModelManager().getFluidStateModelSet());
	}
}
