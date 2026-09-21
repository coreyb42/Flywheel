package dev.engine_room.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Captures the vanilla 26.2 block-model tessellator output into Flywheel meshes.
 *
 * <p>Fabric's renderer v1 API was removed with the 26.2 renderer rewrite. Vanilla
 * now provides the fully-lit {@link QuadInstance} through {@link BlockQuadOutput},
 * which preserves resource-pack model output, tinting, lighting, face culling, and
 * the quad's terrain layer.</p>
 */
class FabricMeshEmitterManager extends MeshEmitterManager<MeshEmitter> implements BlockQuadOutput {
	private boolean ambientOcclusion;

	FabricMeshEmitterManager() {
		super(MeshEmitter::new);
	}

	public void prepareForModel(boolean ambientOcclusion) {
		this.ambientOcclusion = ambientOcclusion;
	}

	@Override
	public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
		var info = quad.materialInfo();
		var buffer = getBuffer(info.layer(), info.shade(), ambientOcclusion);
		if (buffer != null) {
			buffer.putBlockBakedQuad(x, y, z, quad, instance);
		}
	}
}
