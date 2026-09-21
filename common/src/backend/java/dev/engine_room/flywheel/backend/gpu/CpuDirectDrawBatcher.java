package dev.engine_room.flywheel.backend.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.joml.FrustumIntersection;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.core.Vec3i;

/**
 * The CPU-side replacement for Flywheel's former compute/indirect submission
 * path.
 *
 * <p>Minecraft 26.2 exposes direct indexed instanced draws, but deliberately
 * does not expose shader-storage buffers, compute dispatch, or indirect draw
 * commands. This class is the explicit hand-off between visibility collection
 * and those direct draws: it keeps all candidates in stable insertion order,
 * applies camera-relative frustum culling on the CPU, coalesces compatible
 * candidates by key, and sends the resulting plans to one public GPU render
 * pass.</p>
 *
 * <p>The {@link PlanFactory} is responsible for packing the visible values into
 * the instance stream of each returned plan before this method is called. It
 * must return plans whose buffers are already uploaded, except for dirty
 * uniform buffers, which {@link GpuDirectRenderPassManager} uploads through
 * the same command encoder before opening the pass. This prevents the old
 * hidden GL/SSBO state from leaking into the 26.2 backend.</p>
 */
public final class CpuDirectDrawBatcher<K, T> {
	private final CpuVisibilityBatcher<K, T> visibility = new CpuVisibilityBatcher<>();
	private final GpuDirectRenderPassManager renderer;

	public CpuDirectDrawBatcher(GpuDirectRenderPassManager renderer) {
		this.renderer = Objects.requireNonNull(renderer, "renderer");
	}

	/** Record one candidate direct draw. Bounds are in world coordinates. */
	public void add(K key, CpuVisibilityBatcher.Bounds bounds, T value) {
		visibility.add(key, bounds, value);
	}

	/** Remove all candidates, normally when the engine or render origin resets. */
	public void clear() {
		visibility.clear();
	}

	public int size() {
		return visibility.size();
	}

	/**
	 * Cull, build and submit the current candidates.
	 *
	 * @return number of encoded direct draw plans
	 */
	public int render(RenderTarget target, FrustumIntersection frustum, Vec3i renderOrigin, PlanFactory<K, T> factory) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(frustum, "frustum");
		Objects.requireNonNull(renderOrigin, "renderOrigin");
		Objects.requireNonNull(factory, "factory");

		List<GpuInstancedDrawPlan> plans = new ArrayList<>();
		for (CpuVisibilityBatcher.Batch<K, T> batch : visibility.cull(frustum, renderOrigin)) {
			Collection<GpuInstancedDrawPlan> batchPlans = Objects.requireNonNull(
					factory.createPlans(batch.key(), batch.values()), "PlanFactory returned null");
			for (GpuInstancedDrawPlan plan : batchPlans) {
				plans.add(Objects.requireNonNull(plan, "PlanFactory returned a null plan"));
			}
		}

		if (!plans.isEmpty()) {
			renderer.submit(target, plans);
		}
		return plans.size();
	}

	/**
	 * Builds one or more direct draw plans from a stable, visibility-filtered
	 * batch. The values list is immutable and preserves candidate insertion
	 * order, so transparent material passes can retain their existing ordering.
	 */
	@FunctionalInterface
	public interface PlanFactory<K, T> {
		Collection<GpuInstancedDrawPlan> createPlans(K key, List<T> visibleValues);
	}
}
