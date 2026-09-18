package dev.engine_room.flywheel.backend.gpu;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;

/** A CPU-uploaded index stream with its element format retained for pass binding. */
public final class GpuIndexBuffer implements AutoCloseable {
	private final GpuUploadBuffer data;
	private final IndexType indexType;

	public GpuIndexBuffer(Supplier<String> label, IndexType indexType) {
		this.data = new GpuUploadBuffer(Objects.requireNonNull(label, "label"), GpuBuffer.USAGE_INDEX);
		this.indexType = Objects.requireNonNull(indexType, "indexType");
	}

	/**
	 * Upload the complete index stream before opening a render pass.
	 * The byte count must contain whole index elements.
	 */
	public void upload(ByteBuffer indices) {
		Objects.requireNonNull(indices, "indices");
		if (indices.remaining() % indexType.bytes != 0) {
			throw new IllegalArgumentException("Index data does not contain a whole number of " + indexType + " elements");
		}
		data.upload(indices);
	}

	public int indexCount() {
		return Math.toIntExact(data.size() / indexType.bytes);
	}

	public IndexType indexType() {
		return indexType;
	}

	/** Bind this stream; it must have been uploaded first. */
	public void bind(RenderPass pass) {
		Objects.requireNonNull(pass, "pass").setIndexBuffer(data.buffer(), indexType);
	}

	@Override
	public void close() {
		data.close();
	}
}
