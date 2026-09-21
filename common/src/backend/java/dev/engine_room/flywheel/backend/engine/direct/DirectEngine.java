package dev.engine_room.flywheel.backend.engine.direct;

import java.util.List;
import java.util.Objects;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * GL-free engine host for a completed Minecraft 26.2 direct draw manager.
 *
 * <p>This class intentionally is not registered as a backend factory. Factory
 * installation is only valid after a {@link DirectDrawManager} can build all
 * of the engine's plans, including crumbling and embedded transforms.</p>
 */
public final class DirectEngine implements Engine {
	private static final DirectEnvironment WORLD = new DirectEnvironment() {
		@Override
		public DrawContext drawContext() {
			return DrawContext.WORLD;
		}

		@Override
		public int matrixIndex() {
			return 0;
		}
	};

	private final DirectDrawManager drawManager;
	private final int sqrMaxOriginDistance;
	private final DirectEnvironmentStorage environmentStorage = new DirectEnvironmentStorage();
	private final LightStorage lightStorage;
	private BlockPos renderOrigin = BlockPos.ZERO;

	public DirectEngine(LevelAccessor level, DirectDrawManager drawManager, int maxOriginDistance) {
		this.drawManager = Objects.requireNonNull(drawManager, "drawManager");
		if (maxOriginDistance < 0) {
			throw new IllegalArgumentException("maxOriginDistance must not be negative");
		}
		sqrMaxOriginDistance = Math.multiplyExact(maxOriginDistance, maxOriginDistance);
		lightStorage = new LightStorage(Objects.requireNonNull(level, "level"));
	}

	@Override
	public VisualizationContext createVisualizationContext() {
		return new VisualizationContext() {
			private final InstancerProvider instancers = new InstancerProvider() {
				@Override
				public <I extends Instance> Instancer<I> instancer(InstanceType<I> type, Model model, int bias) {
					return DirectEngine.this.instancer(WORLD, type, model, bias);
				}
			};

			@Override
			public InstancerProvider instancerProvider() {
				return instancers;
			}

			@Override
			public Vec3i renderOrigin() {
				return DirectEngine.this.renderOrigin();
			}

			@Override
			public VisualEmbedding createEmbedding(Vec3i origin) {
				var embedding = new DirectEmbeddedEnvironment(DirectEngine.this, origin, null);
				environmentStorage.track(embedding);
				return embedding;
			}
		};
	}

	@Override
	public Plan<RenderContext> createFramePlan() {
		return drawManager.createFramePlan().and(lightStorage.createFramePlan());
	}

	@Override
	public Vec3i renderOrigin() {
		return renderOrigin;
	}

	@Override
	public boolean updateRenderOrigin(Camera camera) {
		Vec3 cameraPos = camera.position();
		double dx = renderOrigin.getX() - cameraPos.x;
		double dy = renderOrigin.getY() - cameraPos.y;
		double dz = renderOrigin.getZ() - cameraPos.z;
		if (dx * dx + dy * dy + dz * dz <= sqrMaxOriginDistance) {
			return false;
		}
		renderOrigin = BlockPos.containing(cameraPos);
		drawManager.onRenderOriginChanged();
		return true;
	}

	@Override
	public void lightSections(LongSet sections) {
		lightStorage.sections(sections);
	}

	@Override
	public void onLightUpdate(SectionPos sectionPos, LightLayer layer) {
		lightStorage.onLightUpdate(sectionPos.asLong());
	}

	@Override
	public void render(RenderContext context) {
		environmentStorage.flush();
		drawManager.render(context, lightStorage, environmentStorage);
	}

	@Override
	public void renderCrumbling(RenderContext context, List<CrumblingBlock> crumblingBlocks) {
		drawManager.renderCrumbling(context, crumblingBlocks, lightStorage, environmentStorage);
	}

	@Override
	public void delete() {
		drawManager.delete();
		lightStorage.delete();
		environmentStorage.delete();
	}

	public <I extends Instance> Instancer<I> instancer(DirectEnvironment environment, InstanceType<I> type, Model model, int bias) {
		return drawManager.getInstancer(environment, type, model, bias);
	}

	public DirectEnvironmentStorage environmentStorage() {
		return environmentStorage;
	}

	public LightStorage lightStorage() {
		return lightStorage;
	}
}
