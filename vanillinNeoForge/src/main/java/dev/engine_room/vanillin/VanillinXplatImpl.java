package dev.engine_room.vanillin;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;

public class VanillinXplatImpl implements VanillinXplat {
	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLEnvironment.isProduction();
	}

	@Override
	public boolean isModLoaded(String modId) {
		return LoadingModList.get()
				.getModFileById(modId) != null;
	}
}
