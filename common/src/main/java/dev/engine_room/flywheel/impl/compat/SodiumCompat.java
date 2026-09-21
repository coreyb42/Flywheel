package dev.engine_room.flywheel.impl.compat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.impl.FlwImpl;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class SodiumCompat {
	public static final boolean ACTIVE = CompatMod.SODIUM.isLoaded;

	static {
		if (ACTIVE) {
			FlwImpl.LOGGER.debug("Detected Sodium");
		}
	}

	private SodiumCompat() {
	}

	@Nullable
	public static <T extends BlockEntity> Object onSetBlockEntityVisualizer(BlockEntityType<T> type, @Nullable BlockEntityVisualizer<? super T> oldVisualizer, @Nullable BlockEntityVisualizer<? super T> newVisualizer, @Nullable Object predicate) {
		if (!ACTIVE) {
			return null;
		}

		if (oldVisualizer == null && newVisualizer != null) {
			if (predicate != null) {
				throw new IllegalArgumentException("Sodium predicate must be null when old visualizer is null");
			}

			return Internals.addPredicate(type);
		} else if (oldVisualizer != null && newVisualizer == null) {
			if (predicate == null) {
				throw new IllegalArgumentException("Sodium predicate must not be null when old visualizer is not null");
			}

			Internals.removePredicate(type, predicate);
			return null;
		}

		return predicate;
	}

	private static final class Internals {
		static <T extends BlockEntity> Object addPredicate(BlockEntityType<T> type) {
			Object predicate = createPredicate();
			invokeHandlerMethod("addRenderPredicate", type, predicate);
			return predicate;
		}

		static <T extends BlockEntity> void removePredicate(BlockEntityType<T> type, Object predicate) {
			invokeHandlerMethod("removeRenderPredicate", type, predicate);
		}

		private static Object createPredicate() {
			try {
				ClassLoader classLoader = SodiumCompat.class.getClassLoader();
				Class<?> predicateClass = Class.forName("net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate", true, classLoader);
				InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
					case "shouldRender" -> !VisualizationHelper.tryAddBlockEntity((BlockEntity) args[2]);
					case "toString" -> "Flywheel Sodium visualization predicate";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> throw new UnsupportedOperationException(method.toString());
				};
				return Proxy.newProxyInstance(classLoader, new Class<?>[] {predicateClass}, handler);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Sodium is present but its block entity rendering API is unavailable", e);
			}
		}

		private static void invokeHandlerMethod(String methodName, BlockEntityType<?> type, Object predicate) {
			try {
				ClassLoader classLoader = SodiumCompat.class.getClassLoader();
				Class<?> handlerClass = Class.forName("net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderHandler", true, classLoader);
				Class<?> predicateClass = Class.forName("net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate", true, classLoader);
				Object handler = handlerClass.getMethod("instance").invoke(null);
				Method method = handlerClass.getMethod(methodName, BlockEntityType.class, predicateClass);
				method.invoke(handler, type, predicate);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Failed to invoke Sodium's block entity rendering API", e);
			}
		}
	}
}
