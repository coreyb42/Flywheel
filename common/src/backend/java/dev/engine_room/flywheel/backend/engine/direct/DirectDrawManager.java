package dev.engine_room.flywheel.backend.engine.direct;

import java.util.List;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.backend.engine.LightStorage;

/**
 * Render-context-aware engine boundary for Flywheel's Minecraft 26.2 direct
 * renderer.
 *
 * <p>Unlike the retired {@code DrawManager}, this contract receives the live
 * {@link RenderContext} at submission time. That is required to select a live
 * {@code RenderTarget} and encode public GPU commands; a direct renderer must
 * never infer framebuffer state from OpenGL globals. Implementations own all
 * GPU pipelines, buffers, pass bindings, and CPU visibility batching.</p>
 */
public interface DirectDrawManager {
	<I extends Instance> Instancer<I> getInstancer(DirectEnvironment environment, InstanceType<I> type, Model model, int bias);

	Plan<RenderContext> createFramePlan();

	/**
	 * Flush CPU instance state, select the current scene target from
	 * {@code context}, and encode all public-GPU draw passes.
	 */
	void render(RenderContext context, LightStorage lightStorage, DirectEnvironmentStorage environments);

	/** Encode the block-destruction overlay through the same direct path. */
	void renderCrumbling(RenderContext context, List<Engine.CrumblingBlock> crumblingBlocks,
			LightStorage lightStorage, DirectEnvironmentStorage environments);

	void onRenderOriginChanged();

	/** Disable this backend and request Flywheel's normal backend recovery. */
	void triggerFallback();

	/** Release all GPU and CPU resources on the render thread. */
	void delete();
}
