package dev.engine_room.flywheel.backend.compile.component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.mojang.blaze3d.GpuFormat;

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
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

/**
 * Builds the vertex-stage side of direct instancing for Minecraft 26.2.
 *
 * <p>26.2's public render pipeline accepts instance data as a vertex binding;
 * it does not expose the buffer-texture path used by the legacy renderer.
 * This component consequently declares one GPU attribute for every scalar or
 * vector stream in an {@link Layout}, then reconstructs {@code FlwInstance}
 * from those attributes. Matrix columns and array members are individual
 * attributes so their byte offsets remain identical to the instance writer's
 * layout.</p>
 *
 * <p>The generated unpack function intentionally ignores its index. The GPU's
 * instance-rate vertex binding selects the current record, including any
 * {@code firstInstance} supplied to the draw command. Keeping the established
 * function signature means existing instance shaders and the common vertex
 * entry point do not need a special direct-renderer variant.</p>
 */
public final class AttributeInstanceComponent implements SourceComponent {
	private static final String STRUCT_NAME = "FlwInstance";
	private static final String UNPACK_FN_NAME = "_flw_unpackInstance";

	private final Layout layout;
	private final List<Attribute> attributes;

	public AttributeInstanceComponent(InstanceType<?> type) {
		this(type.layout());
	}

	public AttributeInstanceComponent(Layout layout) {
		this.layout = layout;
		List<Attribute> attributes = new ArrayList<>();
		for (Layout.Element element : layout.elements()) {
			collectAttributes(attributes, element.type(), element.byteOffset(), element.name());
		}
		this.attributes = List.copyOf(attributes);
	}

	/**
	 * The instance-rate attributes required by this source. Consumers bind these
	 * in declaration order as vertex binding 1, with {@link Layout#byteSize()}
	 * as the record stride.
	 */
	public List<Attribute> attributes() {
		return attributes;
	}

	public int stride() {
		return layout.byteSize();
	}

	@Override
	public String name() {
		return ResourceUtil.rl("attribute_instance_assembler").toString();
	}

	@Override
	public Collection<? extends SourceComponent> included() {
		return Collections.emptyList();
	}

	@Override
	public String source() {
		StringBuilder out = new StringBuilder();
		for (Attribute attribute : attributes) {
			out.append("in ").append(attribute.shaderType()).append(' ').append(attribute.name()).append(";\n");
		}
		out.append('\n');
		out.append(STRUCT_NAME).append(' ').append(UNPACK_FN_NAME).append("(int index) {\n");
		out.append("    return ").append(STRUCT_NAME).append('(');

		for (int i = 0; i < layout.elements().size(); i++) {
			if (i != 0) {
				out.append(", ");
			}
			Layout.Element element = layout.elements().get(i);
			out.append(unpack(element.type(), element.name()));
		}

		out.append(");\n}\n");
		return out.toString();
	}

	private static void collectAttributes(List<Attribute> out, ElementType type, int byteOffset, String path) {
		if (type instanceof ScalarElementType scalar) {
			out.add(attribute(path, byteOffset, scalar.repr(), 1));
		} else if (type instanceof VectorElementType vector) {
			out.add(attribute(path, byteOffset, vector.repr(), vector.size()));
		} else if (type instanceof MatrixElementType matrix) {
			int columnSize = matrix.rows() * matrix.repr().byteSize();
			for (int column = 0; column < matrix.columns(); column++) {
				out.add(attribute(path + "_c" + column, byteOffset + column * columnSize, matrix.repr(), matrix.rows()));
			}
		} else if (type instanceof ArrayElementType array) {
			int elementSize = array.innerType().byteSize();
			for (int i = 0; i < array.length(); i++) {
				collectAttributes(out, array.innerType(), byteOffset + i * elementSize, path + "_a" + i);
			}
		} else {
			throw new IllegalArgumentException("Unknown instance element type " + type);
		}
	}

	private static Attribute attribute(String path, int byteOffset, ValueRepr repr, int components) {
		return new Attribute("_flw_i_" + path, byteOffset, format(repr, components), rawShaderType(repr, components));
	}

	private static String unpack(ElementType type, String path) {
		if (type instanceof ScalarElementType scalar) {
			return convert("_flw_i_" + path, scalar.repr(), 1);
		} else if (type instanceof VectorElementType vector) {
			return convert("_flw_i_" + path, vector.repr(), vector.size());
		} else if (type instanceof MatrixElementType matrix) {
			StringBuilder out = new StringBuilder(LayoutInterpreter.matrixTypeName(matrix)).append('(');
			for (int column = 0; column < matrix.columns(); column++) {
				if (column != 0) out.append(", ");
				out.append(convert("_flw_i_" + path + "_c" + column, matrix.repr(), matrix.rows()));
			}
			return out.append(')').toString();
		} else if (type instanceof ArrayElementType array) {
			StringBuilder out = new StringBuilder(LayoutInterpreter.arrayTypeName(array)).append('(');
			for (int i = 0; i < array.length(); i++) {
				if (i != 0) out.append(", ");
				out.append(unpack(array.innerType(), path + "_a" + i));
			}
			return out.append(')').toString();
		}

		throw new IllegalArgumentException("Unknown instance element type " + type);
	}

	private static String convert(String input, ValueRepr repr, int components) {
		if (repr instanceof IntegerRepr || repr instanceof UnsignedIntegerRepr) {
			return input;
		}

		FloatRepr floatRepr = (FloatRepr) repr;
		String floatType = shaderType("vec", components);
		String asFloat = floatRepr == FloatRepr.FLOAT ? input : floatType + '(' + input + ')';
		return switch (floatRepr) {
			case NORMALIZED_BYTE -> "clamp(" + asFloat + " / 127.0, -1.0, 1.0)";
			case NORMALIZED_UNSIGNED_BYTE -> asFloat + " / 255.0";
			case NORMALIZED_SHORT -> "clamp(" + asFloat + " / 32767.0, -1.0, 1.0)";
			case NORMALIZED_UNSIGNED_SHORT -> asFloat + " / 65535.0";
			case NORMALIZED_INT -> "clamp(" + asFloat + " / 2147483647.0, -1.0, 1.0)";
			case NORMALIZED_UNSIGNED_INT -> asFloat + " / 4294967295.0";
			default -> asFloat;
		};
	}

	private static String rawShaderType(ValueRepr repr, int components) {
		if (repr instanceof IntegerRepr) return shaderType("ivec", components);
		if (repr instanceof UnsignedIntegerRepr) return shaderType("uvec", components);
		FloatRepr floatRepr = (FloatRepr) repr;
		return floatRepr == FloatRepr.FLOAT ? shaderType("vec", components)
				: (isSigned(floatRepr) ? shaderType("ivec", components) : shaderType("uvec", components));
	}

	private static String shaderType(String vectorPrefix, int components) {
		return components == 1 ? switch (vectorPrefix) {
			case "ivec" -> "int";
			case "uvec" -> "uint";
			default -> "float";
		} : vectorPrefix + components;
	}

	private static boolean isSigned(FloatRepr repr) {
		return switch (repr) {
			case BYTE, NORMALIZED_BYTE, SHORT, NORMALIZED_SHORT, INT, NORMALIZED_INT -> true;
			default -> false;
		};
	}

	private static GpuFormat format(ValueRepr repr, int components) {
		String componentPrefix;
		if (repr instanceof IntegerRepr integer) {
			componentPrefix = "SINT_" + integer.byteSize() * 8;
		} else if (repr instanceof UnsignedIntegerRepr integer) {
			componentPrefix = "UINT_" + integer.byteSize() * 8;
		} else {
			FloatRepr floatRepr = (FloatRepr) repr;
			componentPrefix = switch (floatRepr) {
				case BYTE -> "SINT_8";
				case NORMALIZED_BYTE -> "SNORM_8";
				case UNSIGNED_BYTE -> "UINT_8";
				case NORMALIZED_UNSIGNED_BYTE -> "UNORM_8";
				case SHORT -> "SINT_16";
				case NORMALIZED_SHORT -> "SNORM_16";
				case UNSIGNED_SHORT -> "UINT_16";
				case NORMALIZED_UNSIGNED_SHORT -> "UNORM_16";
				case INT -> "SINT_32";
				case NORMALIZED_INT -> "SINT_32";
				case UNSIGNED_INT -> "UINT_32";
				case NORMALIZED_UNSIGNED_INT -> "UINT_32";
				case FLOAT -> "FLOAT_32";
			};
		}

		String channels = switch (components) {
			case 1 -> "R";
			case 2 -> "RG";
			case 3 -> "RGB";
			case 4 -> "RGBA";
			default -> throw new IllegalArgumentException("Unsupported attribute component count " + components);
		};
		int separator = componentPrefix.indexOf('_');
		String kind = componentPrefix.substring(0, separator);
		String bits = componentPrefix.substring(separator + 1);
		return GpuFormat.valueOf(channels + bits + '_' + kind);
	}

	/** One physical instance-rate vertex attribute. */
	public record Attribute(String name, int byteOffset, GpuFormat format, String shaderType) {
		public Attribute {
			if (byteOffset < 0) throw new IllegalArgumentException("byteOffset must be non-negative");
		}
	}
}
