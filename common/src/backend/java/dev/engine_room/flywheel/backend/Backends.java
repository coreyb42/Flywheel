package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

public final class Backends {
	/**
	 * The pre-26.2 OpenGL instancing renderer.
	 *
	 * @deprecated This ID is retained so existing configurations fall back with a
	 * clear diagnostic. It can never be selected on Minecraft 26.2.
	 */
	@Deprecated(forRemoval = true)
	public static final Backend INSTANCING = SimpleBackend.builder()
			.priority(500)
			.engineFactory(level -> unavailable(BackendCapabilities.Feature.LEGACY_GL_INSTANCING))
			.supported(() -> BackendCapabilities.state(BackendCapabilities.Feature.LEGACY_GL_INSTANCING).available())
			.register(ResourceUtil.rl("instancing"));

	/**
	 * The pre-26.2 OpenGL compute/indirect renderer.
	 *
	 * @deprecated This ID is retained so existing configurations fall back with a
	 * clear diagnostic. It can never be selected on Minecraft 26.2.
	 */
	@Deprecated(forRemoval = true)
	public static final Backend INDIRECT = SimpleBackend.builder()
			.priority(1000)
			.engineFactory(level -> unavailable(BackendCapabilities.Feature.LEGACY_GL_INDIRECT))
			.supported(() -> BackendCapabilities.state(BackendCapabilities.Feature.LEGACY_GL_INDIRECT).available())
			.register(ResourceUtil.rl("indirect"));

	/**
	 * The native 26.2 renderer. It remains unavailable until its complete engine
	 * implementation calls {@link BackendCapabilities#installDirectRenderer}.
	 */
	public static final Backend DIRECT = SimpleBackend.builder()
			.priority(1500)
			.engineFactory(BackendCapabilities::createDirectEngine)
			.supported(() -> BackendCapabilities.state(BackendCapabilities.Feature.GPU_DIRECT_RENDERER).available())
			.register(ResourceUtil.rl("direct"));

	private Backends() {
	}

	public static void init() {
		for (BackendCapabilities.Feature feature : BackendCapabilities.Feature.values()) {
			var state = BackendCapabilities.state(feature);
			if (!state.available()) {
				FlwBackend.LOGGER.warn("Flywheel backend feature '{}' is unavailable: {}", feature, state.detail());
			}
		}
	}

	private static <T> T unavailable(BackendCapabilities.Feature feature) {
		throw new IllegalStateException(BackendCapabilities.state(feature).detail());
	}
}
