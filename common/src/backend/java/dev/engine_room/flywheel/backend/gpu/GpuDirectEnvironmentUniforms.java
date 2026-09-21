package dev.engine_room.flywheel.backend.gpu;

import java.util.Objects;

import org.lwjgl.system.MemoryUtil;

import dev.engine_room.flywheel.backend.engine.direct.DirectEnvironmentStorage;

/**
 * Public-GPU uniform storage for the direct renderer's embedded transforms.
 *
 * <p>The source arena is deliberately copied as a complete, indexed table:
 * {@link dev.engine_room.flywheel.backend.engine.direct.DirectEnvironment#matrixIndex()}
 * is therefore stable for the lifetime of a frame, including slot zero's world
 * identity transform. The buffer is replaced only when the CPU arena grows;
 * callers must obtain {@link #uniform()} after {@link #sync(DirectEnvironmentStorage)}.
 * This avoids retaining a closed GPU buffer in an already-built draw plan.</p>
 *
 * <p>The matching direct shader declaration is a std140 block named
 * {@value #UNIFORM_NAME} containing an array of one {@code mat4} followed by
 * one padded {@code mat3} (112 bytes) per matrix index. This class does not
 * expose a legacy GL buffer or texture-buffer compatibility path.</p>
 */
public final class GpuDirectEnvironmentUniforms implements AutoCloseable {
	public static final String UNIFORM_NAME = "_FlwDirectEnvironments";

	private GpuUniformBuffer buffer;
	private int capacity;

	public GpuDirectEnvironmentUniforms() {
		this(DirectEnvironmentStorage.MATRIX_SIZE_BYTES);
	}

	GpuDirectEnvironmentUniforms(int initialCapacity) {
		if (initialCapacity < DirectEnvironmentStorage.MATRIX_SIZE_BYTES) {
			throw new IllegalArgumentException("Initial direct environment capacity must contain the world transform");
		}
		allocate(initialCapacity);
	}

	/** Copy every allocated arena slot into a named public GPU uniform buffer. */
	public void sync(DirectEnvironmentStorage environments) {
		Objects.requireNonNull(environments, "environments");
		long bytes = environments.arena().byteCapacity();
		if (bytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Direct environment arena exceeds the maximum uniform-buffer size");
		}
		int required = Math.toIntExact(bytes);
		if (required > capacity) {
			allocate(required);
		}
		MemoryUtil.memCopy(environments.arena().indexToPointer(0), buffer.ptr(), required);
		buffer.markDirty();
	}

	/** The uniform binding to attach to every direct pipeline plan. */
	public GpuInstancedDrawPlan.Uniform uniform() {
		return new GpuInstancedDrawPlan.Uniform(UNIFORM_NAME, buffer);
	}

	public int capacity() {
		return capacity;
	}

	private void allocate(int requestedCapacity) {
		int aligned = Math.max(DirectEnvironmentStorage.MATRIX_SIZE_BYTES, requestedCapacity);
		if (buffer != null) {
			buffer.close();
		}
		buffer = new GpuUniformBuffer(() -> "Flywheel direct environment transforms", aligned);
		capacity = buffer.size();
	}

	@Override
	public void close() {
		buffer.close();
	}
}
