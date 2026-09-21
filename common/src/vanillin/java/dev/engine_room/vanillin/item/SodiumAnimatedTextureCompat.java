package dev.engine_room.vanillin.item;

import java.lang.reflect.Method;

import dev.engine_room.vanillin.VanillinXplat;
import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * An attempt to be compatible with sodium's animated texture optimization.
 *
 * <p>Unfortunately Vanillin does not have high time resolution as to what models
 * are on the screen, so once a sprite is observed we'll keep marking it as active
 * until the next renderer reload.
 *
 * <p>This should probably be implemented in Flywheel proper with an API on
 * Mesh to get a list of TextureAtlasSprites. That way a backend would be able
 * to decide which sprites are active based on instance counts, visibility, etc.
 * I have a feeling such an API could be useful otherwise too.
 */
public class SodiumAnimatedTextureCompat {
	private static final ReferenceSet<TextureAtlasSprite> VISIBLE = ReferenceSets.synchronize(new ReferenceArraySet<>());

	private static final boolean IS_SODIUM_LOADED = VanillinXplat.INSTANCE.isModLoaded("sodium");
	private static final SodiumApi SODIUM = IS_SODIUM_LOADED ? SodiumApi.load() : null;

	public static void add(TextureAtlasSprite sprite) {
		if (SODIUM != null) {
			Internals.add(sprite);
		}
	}

	public static void beginFrame() {
		if (SODIUM != null) {
			Internals.beginFrame();
		}
	}

	public static void onReloadRenderer() {
		VISIBLE.clear();
	}

	private static final class Internals {
		private static void add(TextureAtlasSprite sprite) {
			if (SODIUM.hasAnimation(sprite)) {
				VISIBLE.add(sprite);
			}
		}

		private static void beginFrame() {
			for (var sprite : VISIBLE) {
				SODIUM.markSpriteActive(sprite);
			}
		}
	}

	/** Sodium is an optional, jar-in-jar dependency on 26.2. Resolve its public
	 * helper lazily so loading Vanillin never links Sodium classes on NeoForge. */
	private record SodiumApi(Method hasAnimation, Method markSpriteActive) {
		private static SodiumApi load() {
			try {
				Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil");
				return new SodiumApi(type.getMethod("hasAnimation", TextureAtlasSprite.class),
						type.getMethod("markSpriteActive", TextureAtlasSprite.class));
			} catch (ReflectiveOperationException | LinkageError ignored) {
				return null;
			}
		}

		private boolean hasAnimation(TextureAtlasSprite sprite) {
			return invoke(hasAnimation, sprite);
		}

		private void markSpriteActive(TextureAtlasSprite sprite) {
			invoke(markSpriteActive, sprite);
		}

		private static boolean invoke(Method method, TextureAtlasSprite sprite) {
			try {
				return method.getReturnType() == boolean.class && (boolean) method.invoke(null, sprite);
			} catch (ReflectiveOperationException | LinkageError ignored) {
				return false;
			}
		}
	}
}
