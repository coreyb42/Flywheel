package dev.engine_room.flywheel.backend.engine.direct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.pipeline.RenderTarget;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.gpu.GpuDirectRenderPassManager;
import dev.engine_room.flywheel.backend.gpu.GpuDirectEnvironmentUniforms;
import dev.engine_room.flywheel.backend.gpu.GpuInstanceBufferAdapter;
import dev.engine_room.flywheel.backend.gpu.GpuInstancedDrawPlan;
import dev.engine_room.flywheel.backend.gpu.GpuMeshPoolAdapter;
import dev.engine_room.flywheel.lib.task.UnitPlan;
import net.minecraft.client.Minecraft;

/**
 * Concrete GL-free draw manager which owns direct instancers and converts their
 * CPU snapshots plus source meshes into public GPU draw plans.
 *
 * <p>Pipeline creation is injected because shader reload owns the final shader
 * source graph. The factory is not a compatibility escape hatch: it must return
 * complete {@link GpuInstancedDrawPlan}s, and this manager provides the only
 * mesh/instance streams from which those plans may draw. It keeps pipeline
 * compilation/reload separate from per-frame mutable instance ownership.</p>
 */
public final class GpuDirectDrawManager implements DirectDrawManager {
	private final Map<Key<?>, Entry<?>> entries = new ConcurrentHashMap<>();
	private final GpuMeshPoolAdapter meshes = new GpuMeshPoolAdapter(() -> "Flywheel direct mesh stream");
	private final GpuDirectRenderPassManager renderer = new GpuDirectRenderPassManager();
	private final GpuDirectEnvironmentUniforms environmentUniforms = new GpuDirectEnvironmentUniforms();
	private final DirectCrumblingPass crumbling = new DirectCrumblingPass(renderer);
	private final PlanFactory plans;
	private volatile boolean deleted;

	public GpuDirectDrawManager(PlanFactory plans) {
		this.plans = Objects.requireNonNull(plans, "plans");
	}

	@Override
	public <I extends Instance> Instancer<I> getInstancer(DirectEnvironment environment, InstanceType<I> type, Model model, int bias) {
		ensureOpen();
		Key<I> key = new Key<>(environment, type, model, bias);
		@SuppressWarnings("unchecked") Entry<I> entry = (Entry<I>) entries.computeIfAbsent(key, ignored -> new Entry<>(key));
		return entry.instancer;
	}

	@Override
	public Plan<RenderContext> createFramePlan() {
		// Direct snapshots are prepared on the render thread immediately before
		// their command encoder writes. This prevents a worker from observing a
		// visual update half-way through its native instance serialization.
		return UnitPlan.of();
	}

	@Override
	public void render(RenderContext context, LightStorage lightStorage, DirectEnvironmentStorage environments) {
		ensureOpen();
		List<Entry<?>> live = liveEntries();
		if (live.isEmpty()) return;
		environmentUniforms.sync(environments);
		meshes.uploadMeshes(sourceMeshes(live));
		List<GpuInstancedDrawPlan> drawPlans = preparePlans(context, lightStorage, environments, live, null);
		if (!drawPlans.isEmpty()) renderer.submit(sceneTarget(), drawPlans);
	}

	@Override
	public void renderCrumbling(RenderContext context, List<Engine.CrumblingBlock> blocks, LightStorage lightStorage, DirectEnvironmentStorage environments) {
		ensureOpen();
		List<Entry<?>> live = liveEntries();
		if (live.isEmpty() || blocks.isEmpty()) return;
		environmentUniforms.sync(environments);
		meshes.uploadMeshes(sourceMeshes(live));
		crumbling.submit(sceneTarget(), blocks, request -> plans.crumblingPlans(request, context, lightStorage, environments, live, meshes,
				environmentUniforms.uniform()));
	}

	@Override
	public void onRenderOriginChanged() {
		// Source instance positions are relative to the visual's active render
		// origin. Clearing prevents stale relative coordinates from being drawn.
		entries.clear();
	}

	@Override
	public void triggerFallback() {
		delete();
		Minecraft.getInstance().levelExtractor.allChanged();
	}

	@Override
	public void delete() {
		if (deleted) return;
		deleted = true;
		for (Entry<?> entry : entries.values()) entry.close();
		entries.clear();
		meshes.close();
		environmentUniforms.close();
	}

	private List<GpuInstancedDrawPlan> preparePlans(RenderContext context, LightStorage lights, DirectEnvironmentStorage environments,
			List<Entry<?>> live, DirectCrumblingPass.Request crumblingRequest) {
		List<GpuInstancedDrawPlan> out = new ArrayList<>();
		for (Entry<?> entry : live) {
			GpuInstanceBufferAdapter.Snapshot snapshot = entry.upload();
			if (snapshot.instanceCount() == 0) continue;
			for (Model.ConfiguredMesh configured : entry.key.model.meshes()) {
				meshes.geometry(configured.mesh()).ifPresent(geometry -> {
					GpuInstancedDrawPlan plan = plans.plan(new Request(entry, configured, geometry, snapshot, context, lights, environments,
							environmentUniforms.uniform()));
					if (plan != null) out.add(plan);
				});
			}
		}
		return out;
	}

	private List<Entry<?>> liveEntries() {
		List<Entry<?>> live = new ArrayList<>();
		entries.values().removeIf(entry -> {
			if (entry.instancer.instanceCount() != 0) return false;
			entry.close();
			return true;
		});
		live.addAll(entries.values());
		return live;
	}

	private static List<Mesh> sourceMeshes(List<Entry<?>> entries) {
		Map<Mesh, Boolean> seen = new IdentityHashMap<>();
		List<Mesh> out = new ArrayList<>();
		for (Entry<?> entry : entries) for (Model.ConfiguredMesh mesh : entry.key.model.meshes()) if (seen.put(mesh.mesh(), Boolean.TRUE) == null) out.add(mesh.mesh());
		return out;
	}

	private static RenderTarget sceneTarget() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	private void ensureOpen() {
		if (deleted) throw new IllegalStateException("Direct draw manager has been deleted");
	}

	private record Key<I extends Instance>(DirectEnvironment environment, InstanceType<I> type, Model model, int bias) {
	}

	/** One instancer and its public instance-rate buffer. */
	public static final class Entry<I extends Instance> implements AutoCloseable {
		private final Key<I> key;
		private final DirectInstancer<I> instancer;
		private final GpuInstanceBufferAdapter instances;
		private Entry(Key<I> key) {
			this.key = key;
			this.instancer = new DirectInstancer<>(key.type);
			this.instances = new GpuInstanceBufferAdapter(() -> "Flywheel direct instances: " + key.type);
		}
		private GpuInstanceBufferAdapter.Snapshot upload() { return instances.upload(instancer); }
		public DirectEnvironment environment() { return key.environment; }
		public InstanceType<I> type() { return key.type; }
		public Model model() { return key.model; }
		public int bias() { return key.bias; }
		public GpuInstanceBufferAdapter instanceBuffer() { return instances; }
		@Override public void close() { instances.close(); }
	}

	/** Complete inputs for one regular material mesh draw. */
	public record Request(Entry<?> entry, Model.ConfiguredMesh configuredMesh, GpuMeshPoolAdapter.Geometry geometry,
			GpuInstanceBufferAdapter.Snapshot snapshot, RenderContext context, LightStorage lights, DirectEnvironmentStorage environments,
			GpuInstancedDrawPlan.Uniform environmentUniform) {
	}

	/** Shader/pipeline layer which builds real plans from this manager's GPU streams. */
	public interface PlanFactory {
		GpuInstancedDrawPlan plan(Request request);
		Collection<GpuInstancedDrawPlan> crumblingPlans(DirectCrumblingPass.Request request, RenderContext context,
				LightStorage lights, DirectEnvironmentStorage environments, List<Entry<?>> entries, GpuMeshPoolAdapter meshes,
				GpuInstancedDrawPlan.Uniform environmentUniform);
	}
}
