package dev.engine_room.flywheel.backend.gpu;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.engine.direct.DirectInstancer;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

/**
 * Serializes existing instancer data into a public GPU vertex stream.
 *
 * <p>The retired OpenGL renderer supplied this data through a texture buffer.
 * In 26.2 it is a second vertex binding. The stride returned from
 * {@link #upload(DirectInstancer)} must match the instanced vertex binding
 * declared by the draw plan's immutable pipeline.</p>
 */
public final class GpuInstanceBufferAdapter implements AutoCloseable {
	private final GpuVertexBuffer instances;
	private boolean closed;

	public GpuInstanceBufferAdapter(Supplier<String> label) {
		instances = new GpuVertexBuffer(Objects.requireNonNull(label, "label"));
	}

	/** Upload a GL-free direct-instancer snapshot to the instance-rate stream. */
	public <I extends Instance> Snapshot upload(DirectInstancer<I> instancer) {
		ensureOpen();
		Objects.requireNonNull(instancer, "instancer");
		int stride = instancer.stride();
		int expectedCount = instancer.instanceCount();
		long byteCount = Math.multiplyExact((long) stride, expectedCount);
		if (byteCount == 0) return new Snapshot(0, stride);
		if (byteCount > Integer.MAX_VALUE) throw new IllegalArgumentException("Instance upload is too large for a CPU buffer");
		MemoryBlock block = MemoryBlock.malloc(byteCount);
		try {
			int count = instancer.writeInstances(block);
			if (count != expectedCount) throw new IllegalStateException("Direct instancer changed while its GPU snapshot was being prepared");
			ByteBuffer data = block.asBuffer();
			data.position(0);
			data.limit(Math.toIntExact(byteCount));
			instances.upload(data);
			return new Snapshot(count, stride);
		} finally {
			block.free();
		}
	}

	public GpuVertexBuffer instances() {
		return instances;
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("Instance buffer adapter has been closed");
		}
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			instances.close();
		}
	}

	/** A consistent upload's element count and vertex binding stride. */
	public record Snapshot(int instanceCount, int stride) {
	}
}
