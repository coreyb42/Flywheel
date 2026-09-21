package dev.engine_room.flywheel.backend.gpu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Submits CPU-authored instanced draw plans to a live Minecraft scene target.
 *
 * <p>This is the direct-instancing vertical slice for the 26.2 renderer. It
 * deliberately owns the command encoder and render-pass lifetime: all dirty
 * uniform buffers are copied through its encoder before the pass opens, then
 * every plan is encoded into one pass that preserves the scene's existing
 * colour and depth contents. No framebuffer IDs, texture IDs, VAOs, or global
 * render state are involved.</p>
 *
 * <p>Call this on the render thread at the appropriate world-render stage.
 * The supplied target must still own live colour and depth views. Plans must
 * use pipelines compatible with the target's colour format; the Minecraft API
 * validates that invariant at {@link RenderPass#setPipeline} time.</p>
 */
public final class GpuDirectRenderPassManager {
	/**
	 * Upload the plans' dirty uniforms and draw them into {@code sceneTarget}.
	 *
	 * <p>The pass deliberately has no clear values, so this augments rather than
	 * replaces the scene that Minecraft has already rendered. A target without a
	 * depth attachment is supported for pipelines that do not require depth.
	 * An empty plan collection is rejected: callers must make visibility and
	 * batching decisions before they reach the renderer.</p>
	 */
	public void submit(RenderTarget sceneTarget, List<GpuInstancedDrawPlan> plans) {
		RenderSystem.assertOnRenderThread();
		Objects.requireNonNull(sceneTarget, "sceneTarget");
		List<GpuInstancedDrawPlan> drawPlans = List.copyOf(Objects.requireNonNull(plans, "plans"));
		if (drawPlans.isEmpty()) {
			throw new IllegalArgumentException("Direct render pass requires at least one draw plan");
		}

		GpuTextureView color = requireView(sceneTarget.getColorTextureView(), "colour", sceneTarget);
		@Nullable GpuTextureView depth = sceneTarget.getDepthTextureView();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

		for (GpuInstancedDrawPlan plan : drawPlans) {
			for (GpuInstancedDrawPlan.Uniform uniform : plan.uniforms()) {
				uniform.buffer().upload(encoder);
			}
		}

		try (RenderPass pass = encoder.createRenderPass(
				() -> "Flywheel direct instancing",
				color,
				Optional.empty(),
				depth,
				OptionalDouble.empty())) {
			for (GpuInstancedDrawPlan plan : drawPlans) {
				plan.submit(pass);
			}
		}
	}

	private static GpuTextureView requireView(@Nullable GpuTextureView view, String kind, RenderTarget target) {
		if (view == null || view.isClosed()) {
			throw new IllegalStateException("Scene render target has no live " + kind + " view: " + target);
		}
		return view;
	}
}
