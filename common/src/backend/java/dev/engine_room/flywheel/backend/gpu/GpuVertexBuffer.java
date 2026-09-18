package dev.engine_room.flywheel.backend.gpu;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;

/** A CPU-uploaded vertex stream that can be bound to a 26.2 render pass. */
public final class GpuVertexBuffer implements AutoCloseable {
	private final GpuUploadBuffer data;

	public GpuVertexBuffer(Supplier<String> label) {
		this.data = new GpuUploadBuffer(Objects.requireNonNull(label, "label"), GpuBuffer.USAGE_VERTEX);
	}

	/** Upload the complete vertex stream before opening a render pass. */
	public void upload(ByteBuffer vertices) {
		data.upload(vertices);
	}

	public long size() {
		return data.size();
	}

	/** Bind this stream at {@code slot}; the stream must have been uploaded first. */
	public void bind(RenderPass pass, int slot) {
		Objects.requireNonNull(pass, "pass").setVertexBuffer(slot, data.slice());
	}

	@Override
	public void close() {
		data.close();
	}
}
