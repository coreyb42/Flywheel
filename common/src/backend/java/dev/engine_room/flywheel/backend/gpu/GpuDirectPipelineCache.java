package dev.engine_room.flywheel.backend.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.compile.component.VertexInstanceComponent;
import net.minecraft.resources.Identifier;

/**
 * Render-thread cache for Flywheel's public 26.2 direct-instancing pipelines.
 *
 * <p>A {@link RenderPipeline} is immutable: material state, colour target,
 * vertex formats, shaders, and bind-group layouts are all selected when it is
 * built. This cache makes that contract explicit and makes an instance type's
 * {@link GpuInstanceVertexFormat} part of the key. It must be discarded on a
 * shader reload or GPU-device recreation; {@link #clear()} intentionally only
 * drops Java references because pipeline lifetime is owned by Minecraft's
 * {@code GpuDevice}.</p>
 *
 * <p>The request exposes its {@link VertexInstanceComponent}. The shader
 * assembler must include that component (and its {@code FlwInstance}
 * dependency) in the vertex source supplied to the request. Keeping shader
 * assembly separate from pipeline creation permits the existing generated
 * source graph to remain the single source of truth while ensuring its vertex
 * attributes and the pipeline's second binding always come from the same
 * component.</p>
 */
public final class GpuDirectPipelineCache {
	private final Map<PipelineKey, RenderPipeline> pipelines = new HashMap<>();

	/**
	 * Return the immutable pipeline for {@code request}, compiling it once when
	 * first used. Pipeline precompilation is a render-thread operation.
	 */
	public RenderPipeline get(PipelineRequest request) {
		RenderSystem.assertOnRenderThread();
		Objects.requireNonNull(request, "request");
		return pipelines.computeIfAbsent(new PipelineKey(request), ignored -> GpuPipelineCompiler.compile(request.definition(), request.sources()));
	}

	/** Number of pipelines retained for the current shader/device generation. */
	public int size() {
		return pipelines.size();
	}

	/** Drop all cached pipelines after shader reload or GPU-device recreation. */
	public void clear() {
		RenderSystem.assertOnRenderThread();
		pipelines.clear();
	}

	/**
	 * All inputs for one direct-instancing pipeline.
	 *
	 * <p>{@code sources} must be final stage text for this exact request. In
	 * particular, its vertex text must include {@link #vertexInstance()} before
	 * the direct-instancing entry point that calls it.</p>
	 */
	public record PipelineRequest(
			Identifier location,
			Identifier vertexShader,
			Identifier fragmentShader,
			VertexFormat meshVertexFormat,
			InstanceType<?> instanceType,
			PrimitiveTopology topology,
			MaterialPipelineState materialState,
			GpuFormat colorFormat,
			List<BindGroupLayout> bindGroups,
			GpuPipelineCompiler.StageSources sources) {
		public PipelineRequest {
			Objects.requireNonNull(location, "location");
			Objects.requireNonNull(vertexShader, "vertexShader");
			Objects.requireNonNull(fragmentShader, "fragmentShader");
			Objects.requireNonNull(meshVertexFormat, "meshVertexFormat");
			Objects.requireNonNull(instanceType, "instanceType");
			Objects.requireNonNull(topology, "topology");
			Objects.requireNonNull(materialState, "materialState");
			Objects.requireNonNull(colorFormat, "colorFormat");
			bindGroups = List.copyOf(Objects.requireNonNull(bindGroups, "bindGroups"));
			Objects.requireNonNull(sources, "sources");
		}

		/** The direct vertex-input source matching {@link #instanceVertexFormat()}. */
		public VertexInstanceComponent vertexInstance() {
			return new VertexInstanceComponent(instanceType);
		}

		/** The second, instance-rate binding used by this pipeline. */
		public GpuInstanceVertexFormat instanceVertexFormat() {
			return vertexInstance().vertexFormat();
		}

		private GpuPipelineCompiler.PipelineDefinition definition() {
			return new GpuPipelineCompiler.PipelineDefinition(location, vertexShader, fragmentShader,
					meshVertexFormat, instanceVertexFormat(), topology, materialState, colorFormat, bindGroups);
		}
	}

	/**
	 * Uses instance-type identity deliberately. API instance types are metadata
	 * objects, not value objects; two separately registered types can share a
	 * layout yet reference different instance shaders.
	 */
	private static final class PipelineKey {
		private final Identifier location;
		private final Identifier vertexShader;
		private final Identifier fragmentShader;
		private final VertexFormat meshVertexFormat;
		private final InstanceType<?> instanceType;
		private final PrimitiveTopology topology;
		private final MaterialPipelineState materialState;
		private final GpuFormat colorFormat;
		private final List<BindGroupLayout> bindGroups;
		private final GpuPipelineCompiler.StageSources sources;

		private PipelineKey(PipelineRequest request) {
			location = request.location();
			vertexShader = request.vertexShader();
			fragmentShader = request.fragmentShader();
			meshVertexFormat = request.meshVertexFormat();
			instanceType = request.instanceType();
			topology = request.topology();
			materialState = request.materialState();
			colorFormat = request.colorFormat();
			bindGroups = request.bindGroups();
			sources = request.sources();
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof PipelineKey other)) return false;
			return instanceType == other.instanceType
					&& location.equals(other.location)
					&& vertexShader.equals(other.vertexShader)
					&& fragmentShader.equals(other.fragmentShader)
					&& meshVertexFormat.equals(other.meshVertexFormat)
					&& topology == other.topology
					&& materialState.equals(other.materialState)
					&& colorFormat.equals(other.colorFormat)
					&& bindGroups.equals(other.bindGroups)
					&& sources.equals(other.sources);
		}

		@Override
		public int hashCode() {
			int result = location.hashCode();
			result = 31 * result + vertexShader.hashCode();
			result = 31 * result + fragmentShader.hashCode();
			result = 31 * result + meshVertexFormat.hashCode();
			result = 31 * result + System.identityHashCode(instanceType);
			result = 31 * result + topology.hashCode();
			result = 31 * result + materialState.hashCode();
			result = 31 * result + colorFormat.hashCode();
			result = 31 * result + bindGroups.hashCode();
			return 31 * result + sources.hashCode();
		}
	}
}
