package dev.engine_room.flywheel.backend.gpu;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceFile;
import net.minecraft.resources.Identifier;

/**
 * Compiles Flywheel-owned GLSL into Minecraft 26.2 render pipelines.
 *
 * <p>This is deliberately a thin adapter over {@link RenderPipeline} and
 * {@link com.mojang.blaze3d.systems.GpuDevice}. It has no OpenGL program,
 * shader-object, or mutable-state dependency. The generated shader text is
 * supplied to {@code GpuDevice.precompilePipeline}, which is the supported
 * public entry point for mod-owned shaders in 26.2.</p>
 *
 * <p>The old Flywheel sources use {@code #include}; Minecraft's shader source
 * callback receives one string per stage. {@link #expand(ShaderSources,
 * Identifier)} preserves the existing parsed include graph by emitting each
 * dependency before its consumer. A source generator may instead provide a
 * final string directly through {@link StageSources}.</p>
 */
public final class GpuPipelineCompiler {
	private GpuPipelineCompiler() {
	}

	/**
	 * Build and precompile a public GPU pipeline. Precompilation must occur on
	 * the render thread, just like Minecraft's own shader reload work.
	 */
	public static RenderPipeline compile(PipelineDefinition definition, StageSources sources) {
		Objects.requireNonNull(definition, "definition");
		Objects.requireNonNull(sources, "sources");

		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(definition.location())
				.withVertexShader(definition.vertexShader())
				.withFragmentShader(definition.fragmentShader())
				.withVertexBinding(0, definition.vertexFormat())
				.withPrimitiveTopology(definition.topology())
				.withCull(definition.materialState().cull())
				.withColorTargetState(definition.materialState().colorTargetState(definition.colorFormat()));

		definition.materialState().depthStencilState().ifPresent(builder::withDepthStencilState);
		definition.bindGroups().forEach(builder::withBindGroupLayout);

		RenderPipeline pipeline = builder.build();
		RenderSystem.getDevice().precompilePipeline(pipeline, sources);
		return pipeline;
	}

	/**
	 * Expand one existing Flywheel source and every parsed include it references.
	 *
	 * <p>The identity set is intentional: {@link SourceFile} models source files
	 * by identity and the same include can be reached through multiple paths. It
	 * prevents duplicate declarations while preserving dependency-before-user
	 * ordering.</p>
	 */
	public static String expand(ShaderSources sources, Identifier root) {
		Objects.requireNonNull(sources, "sources");
		Objects.requireNonNull(root, "root");
		StringBuilder output = new StringBuilder();
		expand(sources.get(root), output, new IdentityHashMap<>());
		return output.toString();
	}

	private static void expand(SourceFile file, StringBuilder output, Map<SourceFile, Boolean> emitted) {
		if (emitted.put(file, Boolean.TRUE) != null) {
			return;
		}

		for (SourceFile included : file.included) {
			expand(included, output, emitted);
		}

		output.append("\n// flywheel source: ").append(file.name).append('\n');
		output.append(file.source());
		if (!file.source().endsWith("\n")) {
			output.append('\n');
		}
	}

	/** All immutable inputs that form a public 26.2 graphics pipeline. */
	public record PipelineDefinition(
			Identifier location,
			Identifier vertexShader,
			Identifier fragmentShader,
			VertexFormat vertexFormat,
			PrimitiveTopology topology,
			MaterialPipelineState materialState,
			GpuFormat colorFormat,
			List<BindGroupLayout> bindGroups) {
		public PipelineDefinition {
			Objects.requireNonNull(location, "location");
			Objects.requireNonNull(vertexShader, "vertexShader");
			Objects.requireNonNull(fragmentShader, "fragmentShader");
			Objects.requireNonNull(vertexFormat, "vertexFormat");
			Objects.requireNonNull(topology, "topology");
			Objects.requireNonNull(materialState, "materialState");
			Objects.requireNonNull(colorFormat, "colorFormat");
			bindGroups = List.copyOf(bindGroups);
		}
	}

	/**
	 * A two-stage shader source provider suitable for
	 * {@link com.mojang.blaze3d.systems.GpuDevice#precompilePipeline}.
	 */
	public record StageSources(Identifier vertexId, String vertex, Identifier fragmentId, String fragment) implements ShaderSource {
		public StageSources {
			Objects.requireNonNull(vertexId, "vertexId");
			Objects.requireNonNull(vertex, "vertex");
			Objects.requireNonNull(fragmentId, "fragmentId");
			Objects.requireNonNull(fragment, "fragment");
		}

		public static StageSources fromExistingSources(ShaderSources sources, Identifier vertexId, Identifier fragmentId) {
			return new StageSources(vertexId, expand(sources, vertexId), fragmentId, expand(sources, fragmentId));
		}

		@Override
		public String get(Identifier id, ShaderType type) {
			return switch (type) {
				case VERTEX -> vertexId.equals(id) ? vertex : null;
				case FRAGMENT -> fragmentId.equals(id) ? fragment : null;
			};
		}
	}
}
