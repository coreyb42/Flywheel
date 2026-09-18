package dev.engine_room.flywheel.backend.gpu;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

/**
 * CPU-authored uniform data backed by a 26.2 GPU uniform buffer.
 *
 * <p>The caller writes through {@link #ptr()}, marks the contents dirty, then
 * binds the named block while a render pass is open. Uploads deliberately happen
 * before binding: command encoders reject copy commands during a render pass.</p>
 */
public final class GpuUniformBuffer implements AutoCloseable {
	private final Supplier<String> label;
	private final MemoryBlock clientBuffer;
	@Nullable
	private GpuBuffer buffer;
	private boolean needsUpload = true;

	public GpuUniformBuffer(Supplier<String> label, int size) {
		this.label = Objects.requireNonNull(label, "label");
		this.clientBuffer = MemoryBlock.malloc(MoreMath.align16(size));
		this.clientBuffer.clear();
	}

	public long ptr() {
		return clientBuffer.ptr();
	}

	public int size() {
		return Math.toIntExact(clientBuffer.size());
	}

	public void markDirty() {
		needsUpload = true;
	}

	public void clear() {
		clientBuffer.clear();
		markDirty();
	}

	/** Upload dirty data. Must be called when no render pass is open. */
	public void upload() {
		ensureBuffer();
		if (!needsUpload) {
			return;
		}

		ByteBuffer data = clientBuffer.asBuffer().duplicate();
		data.clear();
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data);
		needsUpload = false;
	}

	/** Bind this buffer to the pipeline's named uniform declaration. */
	public void bind(RenderPass pass, String name) {
		if (buffer == null || needsUpload) {
			throw new IllegalStateException("Uniform buffer must be uploaded before opening a render pass");
		}
		pass.setUniform(name, buffer);
	}

	private void ensureBuffer() {
		if (buffer == null || buffer.isClosed()) {
			buffer = RenderSystem.getDevice().createBuffer(label, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, clientBuffer.size());
			needsUpload = true;
		}
	}

	@Override
	public void close() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		clientBuffer.free();
	}
}
