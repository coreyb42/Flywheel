package dev.engine_room.flywheel.lib.model.baked;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel;
import net.fabricmc.fabric.api.resource.ResourceReloadListenerKeys;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

@ApiStatus.Internal
public final class PartialModelEventHandler {
	private static final Map<Identifier, ExtraModelKey<BlockStateModel>> MODEL_KEYS = new ConcurrentHashMap<>();

	private PartialModelEventHandler() {
	}

	public static void register(ModelLoadingPlugin.Context context) {
		for (Identifier location : PartialModel.ALL.keySet()) {
			ExtraModelKey<BlockStateModel> key = MODEL_KEYS.computeIfAbsent(location,
				ignored -> ExtraModelKey.create(() -> "Flywheel partial model " + location));
			context.addModel(key, new PartialUnbakedModel(location));
		}
	}

	public static BlockStateModel getBakedModel(ModelManager manager, Identifier location) {
		ExtraModelKey<BlockStateModel> key = MODEL_KEYS.get(location);
		if (key == null) {
			throw new IllegalStateException("Partial model was requested after Fabric's model-loading phase: " + location);
		}
		return ((FabricModelManager) manager).getModel(key);
	}

	public static void onBakingCompleted(ModelManager manager) {
		PartialModel.populateOnInit = true;
		for (PartialModel partial : PartialModel.ALL.values()) {
			partial.bakedModel = getBakedModel(manager, partial.modelLocation());
		}
	}

	private record PartialUnbakedModel(Identifier location) implements UnbakedExtraModel<BlockStateModel> {
		@Override
		public void resolveDependencies(ResolvableModel.Resolver resolver) {
			resolver.markDependency(location);
		}

		@Override
		public BlockStateModel bake(ModelBaker baker) {
			return new SingleVariant(new Variant(location).bake(baker));
		}
	}

	public static final class ReloadListener implements SimpleSynchronousResourceReloadListener {
		public static final ReloadListener INSTANCE = new ReloadListener();
		public static final Identifier ID = ResourceUtil.rl("partial_models");
		public static final List<Identifier> DEPENDENCIES = List.of(ResourceReloadListenerKeys.MODELS);

		private ReloadListener() {
		}

		@Override
		public void onResourceManagerReload(ResourceManager resourceManager) {
			onBakingCompleted(Minecraft.getInstance().getModelManager());
		}

		@Override
		public Identifier getFabricId() {
			return ID;
		}

		@Override
		public List<Identifier> getFabricDependencies() {
			return DEPENDENCIES;
		}
	}
}
