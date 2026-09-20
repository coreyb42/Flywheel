package dev.engine_room.flywheel.backend.engine;

import java.util.Comparator;

import org.jetbrains.annotations.Nullable;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.gpu.MaterialPipelineState;
import dev.engine_room.flywheel.backend.gpu.MaterialTextureBinding;

/**
 * The immutable GPU state derived from a Flywheel material.
 *
 * <p>Minecraft 26.2 encodes rasterization, depth, blending and texture
 * selection in a {@code RenderPipeline}/{@code RenderPass}; there is no global
 * render state to set and later reset. Callers must use {@link #pipeline()} as
 * part of their pipeline key and bind {@link #diffuseTexture()} on the pass
 * which submits the matching draw.</p>
 */
public record MaterialRenderState(MaterialPipelineState pipeline, MaterialTextureBinding diffuseTexture) {
	public static final Comparator<Material> COMPARATOR = MaterialRenderState::compare;

	public MaterialRenderState {
		if (pipeline == null || diffuseTexture == null) {
			throw new NullPointerException("Material render state components must not be null");
		}
	}

	public static MaterialRenderState from(Material material) {
		return new MaterialRenderState(MaterialPipelineState.from(material), MaterialTextureBinding.from(material));
	}

	public static boolean materialEquals(Material lhs, Material rhs) {
		if (lhs == rhs) {
			return true;
		}

		// Not here because ubershader: useLight, useOverlay, diffuse, fog shader, ambient occlusion
		// Everything in the comparator should be here.
		// @formatter:off
		return lhs.blur() == rhs.blur()
				&& lhs.mipmap() == rhs.mipmap()
				&& lhs.backfaceCulling() == rhs.backfaceCulling()
				&& lhs.polygonOffset() == rhs.polygonOffset()
				&& lhs.depthTest() == rhs.depthTest()
				&& lhs.transparency() == rhs.transparency()
				&& lhs.writeMask() == rhs.writeMask()
				&& lhs.light().source().equals(rhs.light().source())
				&& lhs.texture().equals(rhs.texture())
				&& lhs.cutout().source().equals(rhs.cutout().source())
				&& lhs.shaders().fragmentSource().equals(rhs.shaders().fragmentSource())
				&& lhs.shaders().vertexSource().equals(rhs.shaders().vertexSource());
		// @formatter:on
	}

	public static boolean materialIsAllNonNull(@Nullable Material material) {
		// We do not trust people to give us valid NotNull objects.
		// @formatter:off
		return material != null &&
				material.shaders() != null &&
				material.shaders().fragmentSource() != null &&
				material.shaders().vertexSource() != null &&
				material.fog() != null &&
				material.fog().source() != null &&
				material.cutout() != null &&
				material.cutout().source() != null &&
				material.light() != null &&
				material.light().source() != null &&
				material.texture() != null &&
				material.depthTest() != null &&
				material.transparency() != null &&
				material.writeMask() != null &&
				material.cardinalLightingMode() != null;
		// @formatter:on
	}

	public static int compare(Material lhs, Material rhs) {
		if (lhs == rhs) {
			return 0;
		}

		int cmp;
		cmp = lhs.transparency()
				.compareTo(rhs.transparency());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.light()
				.source()
				.compareTo(rhs.light()
						.source());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.cutout()
				.source()
				.compareTo(rhs.cutout()
						.source());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.shaders()
				.fragmentSource()
				.compareTo(rhs.shaders()
						.fragmentSource());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.shaders()
				.vertexSource()
				.compareTo(rhs.shaders()
						.vertexSource());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.texture()
				.compareTo(rhs.texture());
		if (cmp != 0) {
			return cmp;
		}
		cmp = Boolean.compare(lhs.blur(), rhs.blur());
		if (cmp != 0) {
			return cmp;
		}
		cmp = Boolean.compare(lhs.mipmap(), rhs.mipmap());
		if (cmp != 0) {
			return cmp;
		}
		cmp = Boolean.compare(lhs.backfaceCulling(), rhs.backfaceCulling());
		if (cmp != 0) {
			return cmp;
		}
		cmp = Boolean.compare(lhs.polygonOffset(), rhs.polygonOffset());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.depthTest()
				.compareTo(rhs.depthTest());
		if (cmp != 0) {
			return cmp;
		}
		cmp = lhs.writeMask()
				.compareTo(rhs.writeMask());
		if (cmp != 0) {
			return cmp;
		}
		return 0;
	}
}
