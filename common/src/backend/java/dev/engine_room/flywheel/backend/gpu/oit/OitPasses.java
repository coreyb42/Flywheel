package dev.engine_room.flywheel.backend.gpu.oit;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Vector4f;
import org.joml.Vector4fc;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Render-pass construction for the 26.2 moment-based OIT target set.
 *
 * <p>This class deliberately owns neither pipelines nor draw submission. Each
 * returned pass has the precise attachments and clear values needed by one OIT
 * stage; its caller selects a pipeline with matching color target states,
 * supplies uniforms/textures, submits draws, and closes the pass before opening
 * the next one.</p>
 *
 * <p>In particular, this does not emulate the old framebuffer by binding GL
 * object IDs. 26.2 validates pass attachment counts and formats when the caller
 * sets its pipeline, which makes the four coefficient textures explicit color
 * attachments rather than layers of an unsupported array attachment.</p>
 */
public final class OitPasses {
	private static final Vector4fc CLEAR_ZERO = new Vector4f(0, 0, 0, 0);

	private OitPasses() {
	}

	/**
	 * Begin the depth-range reduction pass.
	 *
	 * <p>The pipeline must use a single {@code RG32_FLOAT} color target with an
	 * additive MAX blend and depth testing enabled without depth writes.</p>
	 */
	public static RenderPass depthRange(CommandEncoder encoder, OitTargets targets, float farDepth, GpuTextureView sceneDepth) {
		if (!Float.isFinite(farDepth) || farDepth <= 0) {
			throw new IllegalArgumentException("OIT far depth must be finite and positive, got " + farDepth);
		}
		validateSceneView(targets, sceneDepth, "scene depth");

		return encoder.createRenderPass(RenderPassDescriptor.create(() -> "Flywheel OIT depth range")
			.withColorAttachment(targets.depthBoundsView(), Optional.of(new Vector4f(-farDepth, -farDepth, 0, 0)))
			.withDepthAttachment(sceneDepth)
			.withRenderArea(renderArea(targets)));
	}

	/**
	 * Begin coefficient generation with one attachment for each OIT coefficient.
	 *
	 * <p>The pipeline must provide exactly four {@code RGBA16_FLOAT} target
	 * states, each with additive blending, and retain depth testing without depth
	 * writes.</p>
	 */
	public static RenderPass coefficients(CommandEncoder encoder, OitTargets targets, GpuTextureView sceneDepth) {
		validateSceneView(targets, sceneDepth, "scene depth");

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Flywheel OIT coefficients")
			.withDepthAttachment(sceneDepth)
			.withRenderArea(renderArea(targets));
		for (int i = 0; i < 4; i++) {
			descriptor.withColorAttachment(targets.coefficientView(i), Optional.of(CLEAR_ZERO));
		}
		return encoder.createRenderPass(descriptor);
	}

	/**
	 * Begin depth reconstruction against the selected public scene depth view.
	 *
	 * <p>This is a depth-only pass. The caller must bind the depth-bounds and all
	 * coefficient views, then use a zero-color-target pipeline with an ALWAYS
	 * depth test and depth writes enabled.</p>
	 */
	public static RenderPass depthReconstruction(CommandEncoder encoder, OitTargets targets, GpuTextureView sceneDepth) {
		validateSceneView(targets, sceneDepth, "scene depth");
		return encoder.createRenderPass(RenderPassDescriptor.create(() -> "Flywheel OIT depth reconstruction")
			.withDepthAttachment(sceneDepth)
			.withRenderArea(renderArea(targets)));
	}

	/**
	 * Begin transparent-color accumulation.
	 *
	 * <p>The pipeline must use one {@code RGBA16_FLOAT} target with additive
	 * blending and depth testing enabled without depth writes.</p>
	 */
	public static RenderPass accumulation(CommandEncoder encoder, OitTargets targets, GpuTextureView sceneDepth) {
		validateSceneView(targets, sceneDepth, "scene depth");
		return encoder.createRenderPass(RenderPassDescriptor.create(() -> "Flywheel OIT accumulation")
			.withColorAttachment(targets.accumulationView(), Optional.of(CLEAR_ZERO))
			.withDepthAttachment(sceneDepth)
			.withRenderArea(renderArea(targets)));
	}

	/**
	 * Begin composition into the selected public scene color and depth views.
	 *
	 * <p>Neither attachment is cleared: this stage blends OIT over existing scene
	 * color and writes reconstructed OIT depth. The pipeline must match the exact
	 * scene color format, have one color target, and use the OIT composite's
	 * separate alpha blend function with an ALWAYS depth test.</p>
	 */
	public static RenderPass composite(CommandEncoder encoder, OitTargets targets, GpuTextureView sceneColor, GpuTextureView sceneDepth) {
		validateSceneView(targets, sceneColor, "scene color");
		validateSceneView(targets, sceneDepth, "scene depth");
		return encoder.createRenderPass(RenderPassDescriptor.create(() -> "Flywheel OIT composite")
			.withColorAttachment(sceneColor)
			.withDepthAttachment(sceneDepth, OptionalDouble.empty())
			.withRenderArea(renderArea(targets)));
	}

	private static RenderPass.RenderArea renderArea(OitTargets targets) {
		return new RenderPass.RenderArea(0, 0, targets.width(), targets.height());
	}

	private static void validateSceneView(OitTargets targets, GpuTextureView view, String name) {
		Objects.requireNonNull(view, name);
		if (view.isClosed()) {
			throw new IllegalStateException("Cannot create an OIT pass with a closed " + name + " view");
		}
		if (view.getWidth(0) != targets.width() || view.getHeight(0) != targets.height()) {
			throw new IllegalArgumentException(
				"OIT target size " + targets.width() + "x" + targets.height() + " does not match " + name + " size " + view.getWidth(0) + "x" + view.getHeight(0)
			);
		}
	}
}
