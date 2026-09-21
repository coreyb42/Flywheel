package dev.engine_room.flywheel.backend;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import dev.engine_room.flywheel.api.backend.Engine;
import net.minecraft.world.level.LevelAccessor;

/**
 * The renderer features that can back Flywheel's built-in backend choices on
 * Minecraft 26.2.
 *
 * <p>The pre-26.2 instancing and indirect renderers depended on Minecraft's
 * stateful OpenGL API. That API is not available in 26.2, so those features are
 * deliberately represented as unavailable rather than probed through LWJGL.
 * The direct GPU renderer becomes available only after its implementation
 * installs an engine factory.
 */
public final class BackendCapabilities {
	public enum Feature {
		LEGACY_GL_INSTANCING,
		LEGACY_GL_INDIRECT,
		GPU_DIRECT_RENDERER
	}

	public record State(boolean available, String detail) {
	}

	private static final Map<Feature, State> FIXED_STATES = new EnumMap<>(Feature.class);

	static {
		FIXED_STATES.put(Feature.LEGACY_GL_INSTANCING, new State(false,
				"Minecraft 26.2 removed the stateful OpenGL API required by the legacy instancing renderer"));
		FIXED_STATES.put(Feature.LEGACY_GL_INDIRECT, new State(false,
				"Minecraft 26.2 removed the compute and OpenGL APIs required by the legacy indirect renderer"));
	}

	@Nullable
	private static Function<LevelAccessor, Engine> directEngineFactory;

	private BackendCapabilities() {
	}

	/**
	 * Return the current state of a backend feature, including a user-actionable
	 * explanation when it is unavailable.
	 */
	public static synchronized State state(Feature feature) {
		if (feature == Feature.GPU_DIRECT_RENDERER) {
			return directEngineFactory == null
					? new State(false, "the 26.2 GPU direct renderer has not been integrated")
					: new State(true, "the 26.2 GPU direct renderer is integrated");
		}

		return FIXED_STATES.get(feature);
	}

	/**
	 * Install the completed 26.2 direct renderer. This is intentionally the only
	 * path that can make the {@code flywheel:direct} backend selectable.
	 *
	 * @throws IllegalStateException if a second renderer attempts to install
	 *                               itself.
	 */
	public static synchronized void installDirectRenderer(Function<LevelAccessor, Engine> engineFactory) {
		Objects.requireNonNull(engineFactory, "engineFactory");
		if (directEngineFactory != null) {
			throw new IllegalStateException("A 26.2 GPU direct renderer is already installed");
		}
		directEngineFactory = engineFactory;
	}

	static synchronized Engine createDirectEngine(LevelAccessor level) {
		if (directEngineFactory == null) {
			throw new IllegalStateException(state(Feature.GPU_DIRECT_RENDERER).detail());
		}
		return directEngineFactory.apply(level);
	}
}
