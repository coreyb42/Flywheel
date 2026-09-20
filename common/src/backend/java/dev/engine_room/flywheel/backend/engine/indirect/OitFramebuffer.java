package dev.engine_room.flywheel.backend.engine.indirect;

import java.util.Objects;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.compile.OitPrograms;
import dev.engine_room.flywheel.backend.gpu.oit.OitPasses;
import dev.engine_room.flywheel.backend.gpu.oit.OitTargets;

/**
 * Owns Flywheel's moment-based order-independent-transparency targets and
 * orchestrates their 26.2 render passes.
 *
 * <p>The pre-26.2 implementation exposed a mutable OpenGL framebuffer and
 * relied on callers changing global GL state between its methods. Minecraft's
 * rendering API now makes attachments and state properties of a
 * {@link RenderPass}; keeping that old lifecycle would either use unsupported
 * implementation details or accidentally submit work to a closed pass. This
 * owner instead creates one explicit pass per OIT stage and gives the renderer
 * a callback while that pass is open.</p>
 *
 * <p>Draw submission remains deliberately external. The indirect and
 * instanced backends use different pipelines and buffers, but both need the
 * same attachment topology and pass ordering. Their 26.2 ports implement
 * {@link Draws} with public {@link RenderPass} commands.</p>
 */
public final class OitFramebuffer implements AutoCloseable {
	private final OitTargets targets = new OitTargets();

	/**
	 * Render all five OIT stages into {@code sceneTarget}.
	 *
	 * @param sceneTarget the active scene render target; it must expose both a
	 *                    color and depth view
	 * @param farDepth the camera far-plane distance used to initialize the
	 *                 depth-range reduction target
	 * @param draws stage-specific pipeline binding and draw submission
	 */
	public void render(RenderTarget sceneTarget, float farDepth, Draws draws) {
		Objects.requireNonNull(sceneTarget, "sceneTarget");
		Objects.requireNonNull(draws, "draws");
		if (!Float.isFinite(farDepth) || farDepth <= 0) {
			throw new IllegalArgumentException("OIT far depth must be finite and positive, got " + farDepth);
		}

		RenderSystem.assertOnRenderThread();
		GpuTextureView sceneColor = Objects.requireNonNull(sceneTarget.getColorTextureView(), "OIT requires a scene color texture");
		GpuTextureView sceneDepth = Objects.requireNonNull(sceneTarget.getDepthTextureView(), "OIT requires a scene depth texture");
		targets.ensureSize(sceneTarget.width, sceneTarget.height);

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = OitPasses.depthRange(encoder, targets, farDepth, sceneDepth)) {
			draws.draw(Stage.DEPTH_RANGE, pass, targets);
		}
		try (RenderPass pass = OitPasses.coefficients(encoder, targets, sceneDepth)) {
			draws.draw(Stage.COEFFICIENTS, pass, targets);
		}
		try (RenderPass pass = OitPasses.depthReconstruction(encoder, targets, sceneDepth)) {
			draws.draw(Stage.DEPTH_RECONSTRUCTION, pass, targets);
		}
		try (RenderPass pass = OitPasses.accumulation(encoder, targets, sceneDepth)) {
			draws.draw(Stage.ACCUMULATION, pass, targets);
		}
		try (RenderPass pass = OitPasses.composite(encoder, targets, sceneColor, sceneDepth)) {
			draws.draw(Stage.COMPOSITE, pass, targets);
		}
	}

	/** The ordered OIT stages supplied to {@link Draws}. */
	public enum Stage {
		DEPTH_RANGE,
		COEFFICIENTS,
		DEPTH_RECONSTRUCTION,
		ACCUMULATION,
		COMPOSITE
	}

	/**
	 * Binds a pipeline and submits the work for an open OIT pass.
	 *
	 * <p>Implementations must not retain {@code pass}: it is closed immediately
	 * after this method returns. {@code targets} is valid until this framebuffer
	 * is resized or closed, and can be used to bind the depth-range,
	 * coefficient, and accumulation texture views to the relevant pipelines.</p>
	 */
	@FunctionalInterface
	public interface Draws {
		void draw(Stage stage, RenderPass pass, OitTargets targets);
	}

	/** Releases the GPU targets. Must be called on the render thread. */
	@Override
	public void close() {
		RenderSystem.assertOnRenderThread();
		targets.close();
	}

	/**
	 * Compatibility constructor for backends that have not yet moved their OIT
	 * shader compilation to {@link RenderPass} pipelines. The program owner is
	 * intentionally not retained: raw GL programs cannot be submitted to this
	 * API.
	 */
	@ApiStatus.ScheduledForRemoval(inVersion = "1.22")
	public OitFramebuffer(OitPrograms ignored) {
	}

	public OitFramebuffer() {
	}

	/**
	 * @deprecated The legacy OpenGL stage API has no valid implementation on
	 *             Minecraft 26.2. Port the caller to {@link #render(RenderTarget,
	 *             float, Draws)} so every draw is submitted while its public GPU
	 *             render pass is open.
	 */
	@Deprecated(forRemoval = true)
	public void prepare() {
		throw legacyStageApi();
	}

	/** @deprecated See {@link #prepare()}. */
	@Deprecated(forRemoval = true)
	public void depthRange() {
		throw legacyStageApi();
	}

	/** @deprecated See {@link #prepare()}. */
	@Deprecated(forRemoval = true)
	public void renderTransmittance() {
		throw legacyStageApi();
	}

	/** @deprecated See {@link #prepare()}. */
	@Deprecated(forRemoval = true)
	public void renderDepthFromTransmittance() {
		throw legacyStageApi();
	}

	/** @deprecated See {@link #prepare()}. */
	@Deprecated(forRemoval = true)
	public void accumulate() {
		throw legacyStageApi();
	}

	/** @deprecated See {@link #prepare()}. */
	@Deprecated(forRemoval = true)
	public void composite() {
		throw legacyStageApi();
	}

	private static UnsupportedOperationException legacyStageApi() {
		return new UnsupportedOperationException(
			"The pre-26.2 OIT framebuffer state machine cannot submit to Minecraft's GPU render passes; use OitFramebuffer.render()"
		);
	}

	/** @deprecated Use {@link #close()}. */
	@Deprecated(forRemoval = true)
	public void delete() {
		close();
	}
}
