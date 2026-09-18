package dev.engine_room.flywheel.backend.gpu;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * An owned GPU buffer whose contents are uploaded from CPU memory.
 *
 * <p>Uploads must occur before a render pass is opened. The resource retains a
 * capacity larger than the most recently uploaded payload so callers can update
 * dynamic geometry without allocating a new GPU buffer for every small growth.</p>
 */
public final class GpuUploadBuffer implements AutoCloseable {
	private static final long MIN_CAPACITY = 256;

	private final Supplier<String> label;
	private final @GpuBuffer.Usage int usage;
	@Nullable
	private GpuBuffer buffer;
	private long dataSize;
	private boolean closed;

	public GpuUploadBuffer(Supplier<String> label, @GpuBuffer.Usage int usage) {
		this.label = Objects.requireNonNull(label, "label");
		this.usage = usage | GpuBuffer.USAGE_COPY_DST;
	}

	/**
	 * Replace the active contents with the remaining bytes in {@code data}.
	 * The supplied buffer's position and limit are not changed.
	 */
	public void upload(ByteBuffer data) {
		ensureOpen();
		Objects.requireNonNull(data, "data");

		ByteBuffer copy = data.duplicate();
		dataSize = copy.remaining();
		if (dataSize == 0) {
			return;
		}

		ensureCapacity(dataSize);
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, dataSize), copy);
	}

	public boolean hasData() {
		return dataSize != 0;
	}

	public long size() {
		return dataSize;
	}

	public long capacity() {
		return buffer == null ? 0 : buffer.size();
	}

	public GpuBuffer buffer() {
		ensureOpen();
		if (buffer == null || dataSize == 0) {
			throw new IllegalStateException("GPU buffer has no uploaded data");
		}
		return buffer;
	}

	public GpuBufferSlice slice() {
		return buffer().slice(0, dataSize);
	}

	private void ensureCapacity(long required) {
		if (buffer != null && !buffer.isClosed() && buffer.size() >= required) {
			return;
		}

		GpuBuffer replacement = RenderSystem.getDevice().createBuffer(label, usage, capacityFor(required));
		if (buffer != null) {
			buffer.close();
		}
		buffer = replacement;
	}

	private static long capacityFor(long required) {
		if (required <= MIN_CAPACITY) {
			return MIN_CAPACITY;
		}
		long rounded = Long.highestOneBit(required - 1) << 1;
		if (rounded <= 0) {
			throw new IllegalArgumentException("GPU buffer is too large: " + required + " bytes");
		}
		return rounded;
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("GPU buffer resource has been closed");
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		dataSize = 0;
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
	}
}
