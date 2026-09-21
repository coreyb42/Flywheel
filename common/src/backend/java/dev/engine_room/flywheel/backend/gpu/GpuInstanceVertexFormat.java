package dev.engine_room.flywheel.backend.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.ArrayElementType;
import dev.engine_room.flywheel.api.layout.ElementType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.api.layout.MatrixElementType;
import dev.engine_room.flywheel.api.layout.ScalarElementType;
import dev.engine_room.flywheel.api.layout.UnsignedIntegerRepr;
import dev.engine_room.flywheel.api.layout.ValueRepr;
import dev.engine_room.flywheel.api.layout.VectorElementType;
import dev.engine_room.flywheel.backend.compile.LayoutInterpreter;

/**
 * The public 26.2 vertex-buffer representation of an {@link InstanceType}.
 *
 * <p>Instance data used to be decoded from a texture buffer or SSBO. Neither
 * resource is part of Minecraft's public 26.2 renderer contract. This class
 * instead maps the existing native {@link Layout} byte-for-byte to a second
 * vertex binding with a step rate of one. Its {@linkplain #attributes()
 * attributes} also carry the matching shader input declarations, so the
 * pipeline and generated shader cannot drift apart.</p>
 */
public final class GpuInstanceVertexFormat {
	public static final int STEP_RATE = 1;
	private static final String ATTRIBUTE_PREFIX = "flw_instance_";

	private final Layout layout;
	private final VertexFormat vertexFormat;
	private final List<Attribute> attributes;
	private final List<String> instanceArguments;

	private GpuInstanceVertexFormat(Layout layout, VertexFormat vertexFormat, List<Attribute> attributes, List<String> instanceArguments) {
		this.layout = layout;
		this.vertexFormat = vertexFormat;
		this.attributes = List.copyOf(attributes);
		this.instanceArguments = List.copyOf(instanceArguments);
	}

	public static GpuInstanceVertexFormat of(InstanceType<?> type) {
		return of(Objects.requireNonNull(type, "type").layout());
	}

	/** Create a step-rate-one vertex format for exactly one native instance. */
	public static GpuInstanceVertexFormat of(Layout layout) {
		Objects.requireNonNull(layout, "layout");
		if (layout.byteSize() == 0) {
			throw new IllegalArgumentException("An instance vertex layout must not be empty");
		}

		List<Attribute> attributes = new ArrayList<>();
		List<String> arguments = new ArrayList<>();
		for (Layout.Element element : layout.elements()) {
			arguments.add(emit(element.type(), element.byteOffset(), ATTRIBUTE_PREFIX + element.name(), attributes));
		}

		if (attributes.size() > VertexFormat.MAX_VERTEX_ELEMENTS) {
			throw new IllegalArgumentException("Instance layout requires " + attributes.size() + " vertex attributes, but Minecraft supports at most " + VertexFormat.MAX_VERTEX_ELEMENTS);
		}

		VertexFormat.Builder builder = VertexFormat.builder(STEP_RATE);
		for (Attribute attribute : attributes) {
			// The builder stores the supplied offset in the element and uses stride
			// only to determine the binding's total size. Ending every declaration
			// at byteSize preserves layout padding while retaining each true offset.
			builder.addAttribute(attribute.name(), attribute.offset(), layout.byteSize() - attribute.offset(), attribute.format(), 1);
		}
		VertexFormat vertexFormat = builder.build();
		if (vertexFormat.getVertexSize() != layout.byteSize()) {
			throw new IllegalStateException("Instance vertex stride " + vertexFormat.getVertexSize() + " does not match layout size " + layout.byteSize());
		}

		return new GpuInstanceVertexFormat(layout, vertexFormat, attributes, arguments);
	}

	public Layout layout() {
		return layout;
	}

	public VertexFormat vertexFormat() {
		return vertexFormat;
	}

	public List<Attribute> attributes() {
		return attributes;
	}

	/** GLSL arguments, in layout-element order, for {@code FlwInstance(...)}. */
	public List<String> instanceArguments() {
		return instanceArguments;
	}

	private static String emit(ElementType type, int offset, String name, List<Attribute> attributes) {
		if (type instanceof ScalarElementType scalar) {
			return attribute(name, offset, scalar.repr(), 1, attributes);
		}
		if (type instanceof VectorElementType vector) {
			return attribute(name, offset, vector.repr(), vector.size(), attributes);
		}
		if (type instanceof MatrixElementType matrix) {
			List<String> columns = new ArrayList<>(matrix.columns());
			int columnSize = matrix.rows() * matrix.repr().byteSize();
			for (int column = 0; column < matrix.columns(); column++) {
				columns.add(attribute(name + "_c" + column, offset + column * columnSize, matrix.repr(), matrix.rows(), attributes));
			}
			return LayoutInterpreter.matrixTypeName(matrix) + "(" + String.join(", ", columns) + ")";
		}
		if (type instanceof ArrayElementType array) {
			List<String> values = new ArrayList<>(array.length());
			int innerSize = array.innerType().byteSize();
			for (int index = 0; index < array.length(); index++) {
				values.add(emit(array.innerType(), offset + index * innerSize, name + "_" + index, attributes));
			}
			return LayoutInterpreter.arrayTypeName(array) + "(" + String.join(", ", values) + ")";
		}
		throw new IllegalArgumentException("Unknown layout type " + type);
	}

	private static String attribute(String name, int offset, ValueRepr repr, int components, List<Attribute> attributes) {
		Attribute attribute = new Attribute(name, offset, format(repr, components), inputType(repr, components), valueExpression(name, repr, components));
		attributes.add(attribute);
		return attribute.valueExpression();
	}

	private static String inputType(ValueRepr repr, int components) {
		String suffix = components == 1 ? "" : Integer.toString(components);
		if (repr instanceof IntegerRepr) {
			return components == 1 ? "int" : "ivec" + suffix;
		}
		if (repr instanceof UnsignedIntegerRepr) {
			return components == 1 ? "uint" : "uvec" + suffix;
		}
		FloatRepr floatRepr = (FloatRepr) repr;
		return switch (floatRepr) {
			case BYTE, SHORT, INT, NORMALIZED_INT -> components == 1 ? "int" : "ivec" + suffix;
			case UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT, NORMALIZED_UNSIGNED_INT -> components == 1 ? "uint" : "uvec" + suffix;
			default -> components == 1 ? "float" : "vec" + suffix;
		};
	}

	private static String valueExpression(String name, ValueRepr repr, int components) {
		if (repr instanceof FloatRepr floatRepr) {
			String asFloat = (components == 1 ? "float" : "vec" + components) + "(" + name + ")";
			return switch (floatRepr) {
				case BYTE, SHORT, INT, UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT -> asFloat;
				// 26.2 provides no 32-bit UNORM/SNORM vertex format. Keep the
				// source representation and normalize in the generated shader.
				case NORMALIZED_INT -> "clamp(" + asFloat + " / 2147483647.0, -1.0, 1.0)";
				case NORMALIZED_UNSIGNED_INT -> asFloat + " / 4294967295.0";
				default -> name;
			};
		}
		return name;
	}

	private static GpuFormat format(ValueRepr repr, int components) {
		if (repr instanceof IntegerRepr integer) {
			return signed(integer.byteSize(), components);
		}
		if (repr instanceof UnsignedIntegerRepr integer) {
			return unsigned(integer.byteSize(), components);
		}
		return switch ((FloatRepr) repr) {
			case BYTE -> signed(1, components);
			case NORMALIZED_BYTE -> snorm(1, components);
			case UNSIGNED_BYTE -> unsigned(1, components);
			case NORMALIZED_UNSIGNED_BYTE -> unorm(1, components);
			case SHORT -> signed(2, components);
			case NORMALIZED_SHORT -> snorm(2, components);
			case UNSIGNED_SHORT -> unsigned(2, components);
			case NORMALIZED_UNSIGNED_SHORT -> unorm(2, components);
			case INT -> signed(4, components);
			case NORMALIZED_INT -> signed(4, components);
			case UNSIGNED_INT -> unsigned(4, components);
			case NORMALIZED_UNSIGNED_INT -> unsigned(4, components);
			case FLOAT -> floating(components);
		};
	}

	private static GpuFormat signed(int bytes, int components) {
		return select(bytes, components, GpuFormat.R8_SINT, GpuFormat.RG8_SINT, GpuFormat.RGB8_SINT, GpuFormat.RGBA8_SINT, GpuFormat.R16_SINT, GpuFormat.RG16_SINT, GpuFormat.RGB16_SINT, GpuFormat.RGBA16_SINT, GpuFormat.R32_SINT, GpuFormat.RG32_SINT, GpuFormat.RGB32_SINT, GpuFormat.RGBA32_SINT);
	}

	private static GpuFormat unsigned(int bytes, int components) {
		return select(bytes, components, GpuFormat.R8_UINT, GpuFormat.RG8_UINT, GpuFormat.RGB8_UINT, GpuFormat.RGBA8_UINT, GpuFormat.R16_UINT, GpuFormat.RG16_UINT, GpuFormat.RGB16_UINT, GpuFormat.RGBA16_UINT, GpuFormat.R32_UINT, GpuFormat.RG32_UINT, GpuFormat.RGB32_UINT, GpuFormat.RGBA32_UINT);
	}

	private static GpuFormat snorm(int bytes, int components) {
		return select(bytes, components, GpuFormat.R8_SNORM, GpuFormat.RG8_SNORM, GpuFormat.RGB8_SNORM, GpuFormat.RGBA8_SNORM, GpuFormat.R16_SNORM, GpuFormat.RG16_SNORM, GpuFormat.RGB16_SNORM, GpuFormat.RGBA16_SNORM, null, null, null, null);
	}

	private static GpuFormat unorm(int bytes, int components) {
		return select(bytes, components, GpuFormat.R8_UNORM, GpuFormat.RG8_UNORM, GpuFormat.RGB8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.R16_UNORM, GpuFormat.RG16_UNORM, GpuFormat.RGB16_UNORM, GpuFormat.RGBA16_UNORM, null, null, null, null);
	}

	private static GpuFormat floating(int components) {
		return switch (components) {
			case 1 -> GpuFormat.R32_FLOAT;
			case 2 -> GpuFormat.RG32_FLOAT;
			case 3 -> GpuFormat.RGB32_FLOAT;
			case 4 -> GpuFormat.RGBA32_FLOAT;
			default -> throw new IllegalArgumentException("Unsupported vertex component count " + components);
		};
	}

	private static GpuFormat select(int bytes, int components, GpuFormat b1, GpuFormat b2, GpuFormat b3, GpuFormat b4, GpuFormat s1, GpuFormat s2, GpuFormat s3, GpuFormat s4, GpuFormat i1, GpuFormat i2, GpuFormat i3, GpuFormat i4) {
		GpuFormat result = switch (bytes) {
			case 1 -> selectComponents(components, b1, b2, b3, b4);
			case 2 -> selectComponents(components, s1, s2, s3, s4);
			case 4 -> selectComponents(components, i1, i2, i3, i4);
			default -> throw new IllegalArgumentException("Unsupported vertex component size " + bytes);
		};
		if (result == null) {
			throw new IllegalArgumentException("Minecraft 26.2 has no matching public vertex format");
		}
		return result;
	}

	private static GpuFormat selectComponents(int components, GpuFormat one, GpuFormat two, GpuFormat three, GpuFormat four) {
		return switch (components) {
			case 1 -> one;
			case 2 -> two;
			case 3 -> three;
			case 4 -> four;
			default -> throw new IllegalArgumentException("Unsupported vertex component count " + components);
		};
	}

	/** One public vertex attribute and its shader-side interpretation. */
	public record Attribute(String name, int offset, GpuFormat format, String inputType, String valueExpression) {
		public Attribute {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(format, "format");
			Objects.requireNonNull(inputType, "inputType");
			Objects.requireNonNull(valueExpression, "valueExpression");
		}
	}
}
