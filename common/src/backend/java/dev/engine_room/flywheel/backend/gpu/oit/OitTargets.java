package dev.engine_room.flywheel.backend.gpu.oit;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * GPU-owned render targets for Flywheel's moment-based OIT passes.
 *
 * <p>26.2 does not support array textures as render-pass attachments. Each
 * coefficient is consequently a distinct 2D texture and may be supplied as a
 * separate color attachment. The owner recreates every target as one coherent
 * set when the render area changes, avoiding mixed-size attachments.</p>
 *
 * <p>This class only owns resources. The caller creates passes and pipelines,
 * and is responsible for ensuring calls occur on the render thread.</p>
 */
public final class OitTargets implements AutoCloseable {
	private static final int TARGET_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

	private int width;
	private int height;
	@Nullable
	private Target depthBounds;
	private final Target[] coefficients = new Target[4];
	@Nullable
	private Target accumulation;

	/** Recreate the target set when {@code width} or {@code height} changes. */
	public void ensureSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("OIT target size must be positive, got " + width + "x" + height);
		}
		if (this.width == width && this.height == height && isAllocated()) {
			return;
		}

		close();
		try {
			depthBounds = create("flywheel/oit_depth_bounds", GpuFormat.RG32_FLOAT, width, height);
			for (int i = 0; i < coefficients.length; i++) {
				coefficients[i] = create("flywheel/oit_coefficient_" + i, GpuFormat.RGBA16_FLOAT, width, height);
			}
			accumulation = create("flywheel/oit_accumulation", GpuFormat.RGBA16_FLOAT, width, height);
			this.width = width;
			this.height = height;
		} catch (RuntimeException | Error e) {
			close();
			throw e;
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public GpuTextureView depthBoundsView() {
		return require(depthBounds, "depth bounds").view;
	}

	public GpuTextureView coefficientView(int index) {
		if (index < 0 || index >= coefficients.length) {
			throw new IndexOutOfBoundsException("Coefficient index must be in [0, 4), got " + index);
		}
		return require(coefficients[index], "coefficient " + index).view;
	}

	public GpuTextureView accumulationView() {
		return require(accumulation, "accumulation").view;
	}

	private boolean isAllocated() {
		if (depthBounds == null || accumulation == null) {
			return false;
		}
		for (Target coefficient : coefficients) {
			if (coefficient == null) {
				return false;
			}
		}
		return true;
	}

	private static Target create(String label, GpuFormat format, int width, int height) {
		GpuTexture texture = RenderSystem.getDevice().createTexture(label, TARGET_USAGE, format, width, height, 1, 1);
		try {
			return new Target(texture, RenderSystem.getDevice().createTextureView(texture));
		} catch (RuntimeException | Error e) {
			texture.close();
			throw e;
		}
	}

	private static Target require(@Nullable Target target, String name) {
		return Objects.requireNonNull(target, "OIT " + name + " target has not been allocated");
	}

	@Override
	public void close() {
		close(depthBounds);
		depthBounds = null;
		for (int i = 0; i < coefficients.length; i++) {
			close(coefficients[i]);
			coefficients[i] = null;
		}
		close(accumulation);
		accumulation = null;
		width = 0;
		height = 0;
	}

	private static void close(@Nullable Target target) {
		if (target != null) {
			target.view.close();
			target.texture.close();
		}
	}

	private record Target(GpuTexture texture, GpuTextureView view) {
	}
}
