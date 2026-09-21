package dev.engine_room.flywheel.backend.engine.direct;

/**
 * CPU-side transform context consumed by the public 26.2 direct renderer.
 *
 * <p>This deliberately does not expose shader programs or mutable draw state.
 * A direct draw manager uploads the matrix data identified by
 * {@link #matrixIndex()} to its own named GPU binding before opening a render
 * pass.</p>
 */
public interface DirectEnvironment {
	DrawContext drawContext();

	int matrixIndex();

	enum DrawContext {
		WORLD,
		EMBEDDED
	}
}
