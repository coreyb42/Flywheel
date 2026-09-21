package dev.engine_room.flywheel.lib.model.baked;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

@ApiStatus.Internal
public final class PartialModelEventHandler {
	private static final ConcurrentMap<Identifier, StandaloneModelKey<BlockStateModel>> KEYS = new ConcurrentHashMap<>();
	private PartialModelEventHandler() {
	}

	public static void onRegisterAdditional(ModelEvent.RegisterStandalone event) {
		for (Identifier modelLocation : PartialModel.ALL.keySet()) {
			StandaloneModelKey<BlockStateModel> key = key(modelLocation);
			event.register(key, SimpleUnbakedStandaloneModel.blockStateModel(modelLocation));
		}
	}

	public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
		PartialModel.populateOnInit = true;
		for (PartialModel partial : PartialModel.ALL.values()) {
			partial.bakedModel = event.getBakingResult().standaloneModels().get(key(partial.modelLocation()));
		}
	}

	public static BlockStateModel getBakedModel(ModelManager manager, Identifier location) {
		return manager.getStandaloneModel(key(location));
	}

	private static StandaloneModelKey<BlockStateModel> key(Identifier location) {
		return KEYS.computeIfAbsent(location, id -> new StandaloneModelKey<>(() -> id.toString()));
	}
}
