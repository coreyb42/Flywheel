package dev.engine_room.flywheel.backend.gpu;

import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;

import dev.engine_room.flywheel.backend.engine.MaterialRenderState;

/**
 * One fully-described instanced submission to a Minecraft 26.2 render pass.
 *
 * <p>This is the boundary between Flywheel's draw sorting and the public GPU
 * renderer. It intentionally carries every input formerly supplied through
 * mutable OpenGL state: the immutable material pipeline state, its diffuse
 * texture binding, both vertex streams, index stream, named uniform buffers,
 * and any context-specific bindings. The renderer must construct this plan
 * before opening its pass; {@link #submit(RenderPass)} only encodes bindings
 * and a draw into that already-open pass.</p>
 *
 * <p>Slot {@value #MESH_VERTEX_SLOT} is the mesh stream and slot
 * {@value #INSTANCE_VERTEX_SLOT} is the per-instance stream. The associated
 * {@link RenderPipeline} must declare matching vertex bindings. This explicit
 * split is important: instance data is not a texture in the 26.2 API and must
 * not be smuggled through a legacy texture-buffer binding.</p>
 */
public record GpuInstancedDrawPlan(
		RenderPipeline pipeline,
		MaterialRenderState material,
		GpuVertexBuffer meshVertices,
		GpuVertexBuffer instances,
		GpuIndexBuffer indices,
		int indexCount,
		int instanceCount,
		int firstIndex,
		int baseVertex,
		int firstInstance,
		List<Uniform> uniforms,
		PassBindings bindings) {
	public static final int MESH_VERTEX_SLOT = 0;
	public static final int INSTANCE_VERTEX_SLOT = 1;

	public GpuInstancedDrawPlan {
		Objects.requireNonNull(pipeline, "pipeline");
		Objects.requireNonNull(material, "material");
		Objects.requireNonNull(meshVertices, "meshVertices");
		Objects.requireNonNull(instances, "instances");
		Objects.requireNonNull(indices, "indices");
		uniforms = List.copyOf(uniforms);
		Objects.requireNonNull(bindings, "bindings");
		if (indexCount <= 0 || instanceCount <= 0) {
			throw new IllegalArgumentException("Indexed instanced draws require positive index and instance counts");
		}
		if (firstIndex < 0 || firstInstance < 0) {
			throw new IllegalArgumentException("First index and first instance must be non-negative");
		}
	}

	/**
	 * Bind this plan's immutable state and submit it to {@code pass}.
	 *
	 * <p>The plan does not upload data. Every referenced GPU buffer must have
	 * been uploaded before the enclosing pass was opened; this preserves the
	 * command encoder's no-copy-during-render-pass rule.</p>
	 */
	public void submit(RenderPass pass) {
		Objects.requireNonNull(pass, "pass");
		pass.setPipeline(pipeline);
		material.diffuseTexture().bind(pass, "flw_diffuseTex");
		for (Uniform uniform : uniforms) {
			uniform.bind(pass);
		}
		bindings.bind(pass, this);
		meshVertices.bind(pass, MESH_VERTEX_SLOT);
		instances.bind(pass, INSTANCE_VERTEX_SLOT);
		indices.bind(pass);
		pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
	}

	/** A named uniform declaration expected by the plan's immutable pipeline. */
	public record Uniform(String name, GpuUniformBuffer buffer) {
		public Uniform {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Uniform name must not be blank");
			}
			Objects.requireNonNull(buffer, "buffer");
		}

		private void bind(RenderPass pass) {
			buffer.bind(pass, name);
		}
	}

	/**
	 * Supplies context-owned pass bindings, such as light/overlay textures or
	 * OIT targets. Implementations must bind only public {@link RenderPass}
	 * resources and must not retain the pass after this callback returns.
	 */
	@FunctionalInterface
	public interface PassBindings {
		void bind(RenderPass pass, GpuInstancedDrawPlan plan);
	}
}
