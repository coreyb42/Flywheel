package dev.engine_room.flywheel.backend.compile.component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.gpu.GpuInstanceVertexFormat;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

/**
 * Generates the vertex-input side of direct 26.2 instancing.
 *
 * <p>The generated function has no index argument because the GPU advances
 * this binding once per instance. It deliberately contains no sampler-buffer
 * or texture-buffer declaration: every instance field is a public vertex
 * attribute declared by {@link GpuInstanceVertexFormat}.</p>
 */
public final class VertexInstanceComponent implements SourceComponent {
	private final GpuInstanceVertexFormat vertexFormat;
	private final InstanceStructComponent struct;

	public VertexInstanceComponent(InstanceType<?> type) {
		Objects.requireNonNull(type, "type");
		this.vertexFormat = GpuInstanceVertexFormat.of(type);
		this.struct = new InstanceStructComponent(type);
	}

	public GpuInstanceVertexFormat vertexFormat() {
		return vertexFormat;
	}

	@Override
	public String name() {
		return ResourceUtil.rl("vertex_instance_assembler").toString();
	}

	@Override
	public Collection<? extends SourceComponent> included() {
		return List.of(struct);
	}

	@Override
	public String source() {
		StringBuilder source = new StringBuilder();
		for (GpuInstanceVertexFormat.Attribute attribute : vertexFormat.attributes()) {
			source.append("in ").append(attribute.inputType()).append(' ').append(attribute.name()).append(";\n");
		}
		source.append("\nFlwInstance _flw_vertexInstance() {\n    return FlwInstance(")
				.append(String.join(", ", vertexFormat.instanceArguments()))
				.append(");\n}\n");
		return source.toString();
	}
}
