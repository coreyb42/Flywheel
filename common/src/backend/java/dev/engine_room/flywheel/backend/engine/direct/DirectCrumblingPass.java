package dev.engine_room.flywheel.backend.engine.direct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.backend.gpu.GpuDirectRenderPassManager;
import dev.engine_room.flywheel.backend.gpu.GpuInstancedDrawPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.Identifier;

/**
 * Encodes Flywheel's block-destruction overlay through Minecraft 26.2's public
 * render-pass API.
 *
 * <p>Crumbling is deliberately expressed as individual, stage-aware plans.
 * The texture for a destruction stage is an input to a pass binding, rather
 * than mutable texture-unit state as it was in the retired GL renderer. The
 * plan factory is responsible for resolving a block's instances into the
 * corresponding direct mesh/instance draw plans and for selecting a pipeline
 * compiled with the crumbling material state.</p>
 */
public final class DirectCrumblingPass {
	/** The named texture resource declared by Flywheel's crumbling shader. */
	public static final String BREAKING_TEXTURE_BINDING = "_flw_crumblingTex";

	private final GpuDirectRenderPassManager renderer;

	public DirectCrumblingPass(GpuDirectRenderPassManager renderer) {
		this.renderer = Objects.requireNonNull(renderer, "renderer");
	}

	/**
	 * Build and submit all block-destruction plans to {@code target}.
	 *
	 * <p>The supplied blocks are the current engine API's crumbling inputs. A
	 * factory invocation receives the original block, its validated destruction
	 * stage, and that stage's resource identifier. It must return plans with a
	 * crumbling pipeline and an explicit {@value #BREAKING_TEXTURE_BINDING}
	 * binding, normally supplied through {@link #withBreakingTexture}.</p>
	 *
	 * @return number of submitted direct plans
	 */
	public int submit(RenderTarget target, List<Engine.CrumblingBlock> blocks, PlanFactory factory) {
		RenderSystem.assertOnRenderThread();
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(blocks, "blocks");
		Objects.requireNonNull(factory, "factory");

		List<GpuInstancedDrawPlan> plans = new ArrayList<>();
		for (Engine.CrumblingBlock block : blocks) {
			Objects.requireNonNull(block, "crumbling block");
			int progress = block.progress();
			Identifier texture = breakingTexture(progress);
			Collection<GpuInstancedDrawPlan> blockPlans = Objects.requireNonNull(
					factory.createPlans(new Request(block, progress, texture)), "PlanFactory returned null");
			for (GpuInstancedDrawPlan plan : blockPlans) {
				plans.add(Objects.requireNonNull(plan, "PlanFactory returned a null plan"));
			}
		}

		if (!plans.isEmpty()) {
			renderer.submit(target, plans);
		}
		return plans.size();
	}

	/**
	 * Return the resource for a vanilla destruction stage, rejecting invalid API
	 * values before they can select an unrelated texture.
	 */
	public static Identifier breakingTexture(int progress) {
		if (progress < 0 || progress >= ModelBakery.BREAKING_LOCATIONS.size()) {
			throw new IllegalArgumentException("Crumbling progress must be in [0, "
					+ ModelBakery.BREAKING_LOCATIONS.size() + "): " + progress);
		}
		return ModelBakery.BREAKING_LOCATIONS.get(progress);
	}

	/**
	 * Decorate a direct plan with the current destruction-stage texture.
	 *
	 * <p>The returned plan preserves all original buffers, uniforms, material
	 * state, and bindings. The stage texture is resolved when the render pass is
	 * encoded, so a resource reload cannot leave the plan holding a stale GPU
	 * texture view.</p>
	 */
	public static GpuInstancedDrawPlan withBreakingTexture(GpuInstancedDrawPlan plan, Identifier texture) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(texture, "texture");
		GpuInstancedDrawPlan.PassBindings originalBindings = plan.bindings();
		GpuInstancedDrawPlan.PassBindings bindings = (pass, boundPlan) -> {
			originalBindings.bind(pass, boundPlan);
			bindBreakingTexture(pass, texture);
		};
		return new GpuInstancedDrawPlan(plan.pipeline(), plan.material(), plan.meshVertices(), plan.instances(), plan.indices(),
				plan.indexCount(), plan.instanceCount(), plan.firstIndex(), plan.baseVertex(), plan.firstInstance(), plan.uniforms(), bindings);
	}

	/** Bind a stage texture with the sampler owned by Minecraft's texture object. */
	public static void bindBreakingTexture(RenderPass pass, Identifier texture) {
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(texture, "texture");
		AbstractTexture breaking = Minecraft.getInstance().getTextureManager().getTexture(texture);
		pass.bindTexture(BREAKING_TEXTURE_BINDING, breaking.getTextureView(), breaking.getSampler());
	}

	/** One validated block-destruction request for a direct draw-plan factory. */
	public record Request(Engine.CrumblingBlock block, int progress, Identifier texture) {
		public Request {
			Objects.requireNonNull(block, "block");
			Objects.requireNonNull(texture, "texture");
			if (progress < 0 || progress >= ModelBakery.BREAKING_LOCATIONS.size()) {
				throw new IllegalArgumentException("Crumbling progress is outside the vanilla stage range: " + progress);
			}
		}
	}

	/** Build direct plans for a single crumbling block and destruction stage. */
	@FunctionalInterface
	public interface PlanFactory {
		Collection<GpuInstancedDrawPlan> createPlans(Request request);
	}
}
