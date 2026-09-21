package dev.engine_room.flywheel.lib.model.baked;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Adapts Minecraft's 26.2 block-model output to Flywheel mesh buffers. */
@ApiStatus.Internal
public class NeoforgeMeshEmitter extends MeshEmitter implements BlockQuadOutput {
	private PoseStack poseStack;

	NeoforgeMeshEmitter(ByteBufferBuilderStack byteBufferBuilderStack, ChunkSectionLayer layer) {
		super(byteBufferBuilderStack, layer);
	}

	public void prepareForBlock(PoseStack poseStack) {
		this.poseStack = poseStack;
	}

	@Override
	public void put(float x, float y, float z, BakedQuad quad, QuadInstance quadInstance) {
		var info = quad.materialInfo();
		Material material = blockMaterialFunction.apply(info.layer(), info.shade(), info.ambientOcclusion());
		if (material == null) return;

		var buffer = getBuffer(material);
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		buffer.putBakedQuad(poseStack.last(), quad, quadInstance);
		poseStack.popPose();
	}
}
