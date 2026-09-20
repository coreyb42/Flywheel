package dev.engine_room.flywheel.backend.gpu;

import java.util.Objects;
import java.util.Optional;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;

/**
 * The portion of a Flywheel material that is encoded in a 26.2 render pipeline.
 *
 * <p>Unlike the former OpenGL state setup, this value contains no mutable driver
 * state and may be used as part of a pipeline cache key. Texture and sampler
 * selection deliberately remain outside this type: those are bind-group inputs,
 * not pipeline state.</p>
 */
public record MaterialPipelineState(
		boolean cull,
		Optional<DepthStencilState> depthStencilState,
		Transparency transparency,
		@ColorTargetState.WriteMask int colorWriteMask) {
	private static final float POLYGON_OFFSET_SCALE = 1.0F;
	private static final float POLYGON_OFFSET_CONSTANT = 10.0F;

	public MaterialPipelineState {
		Objects.requireNonNull(depthStencilState, "depthStencilState");
		Objects.requireNonNull(transparency, "transparency");
	}

	public static MaterialPipelineState from(Material material) {
		Objects.requireNonNull(material, "material");
		return new MaterialPipelineState(
				material.backfaceCulling(),
				depthState(material.depthTest(), material.writeMask(), material.polygonOffset()),
				material.transparency(),
				colorWriteMask(material.writeMask()));
	}

	/**
	 * Create the color-target declaration for a regular render pass.
	 *
	 * <p>{@link Transparency#ORDER_INDEPENDENT} is intentionally rejected here.
	 * Its accumulation and revealage targets require an OIT-specific pipeline;
	 * treating it as ordinary alpha blending would silently lose its ordering
	 * guarantee.</p>
	 */
	public ColorTargetState colorTargetState(GpuFormat format) {
		Objects.requireNonNull(format, "format");
		return new ColorTargetState(blendFunction(), format, colorWriteMask);
	}

	public boolean requiresOrderIndependentTargets() {
		return transparency == Transparency.ORDER_INDEPENDENT;
	}

	private Optional<BlendFunction> blendFunction() {
		return switch (transparency) {
			case OPAQUE -> Optional.empty();
			case ADDITIVE -> Optional.of(BlendFunction.ADDITIVE);
			case LIGHTNING -> Optional.of(BlendFunction.LIGHTNING);
			case GLINT -> Optional.of(BlendFunction.GLINT);
			case CRUMBLING -> Optional.of(new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.SRC_COLOR, BlendFactor.ONE, BlendFactor.ZERO));
			case TRANSLUCENT -> Optional.of(BlendFunction.TRANSLUCENT);
			case ORDER_INDEPENDENT -> throw new IllegalStateException("Order-independent materials require an OIT pipeline");
		};
	}

	private static Optional<DepthStencilState> depthState(DepthTest depthTest, WriteMask writeMask, boolean polygonOffset) {
		Objects.requireNonNull(depthTest, "depthTest");
		Objects.requireNonNull(writeMask, "writeMask");

		if (depthTest == DepthTest.OFF) {
			return Optional.empty();
		}

		float scaleFactor = polygonOffset ? POLYGON_OFFSET_SCALE : 0.0F;
		float constant = polygonOffset ? POLYGON_OFFSET_CONSTANT : 0.0F;
		return Optional.of(new DepthStencilState(compareOp(depthTest), writeMask.depth(), scaleFactor, constant));
	}

	private static CompareOp compareOp(DepthTest depthTest) {
		return switch (depthTest) {
			case OFF -> throw new IllegalArgumentException("Disabled depth testing has no compare operation");
			case NEVER -> CompareOp.NEVER_PASS;
			case LESS -> CompareOp.LESS_THAN;
			case EQUAL -> CompareOp.EQUAL;
			case LEQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			case GREATER -> CompareOp.GREATER_THAN;
			case NOTEQUAL -> CompareOp.NOT_EQUAL;
			case GEQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			case ALWAYS -> CompareOp.ALWAYS_PASS;
		};
	}

	private static @ColorTargetState.WriteMask int colorWriteMask(WriteMask writeMask) {
		return writeMask.color() ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE;
	}
}
