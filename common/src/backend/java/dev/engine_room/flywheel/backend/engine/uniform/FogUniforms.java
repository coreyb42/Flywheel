package dev.engine_room.flywheel.backend.engine.uniform;

import net.minecraft.client.renderer.fog.FogData;

public final class FogUniforms extends UniformWriter {
	private static final int SIZE = 4 * 7;
	static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.FOG_INDEX, SIZE);

	/**
	 * Kept for the pre-26.2 RenderSystem hooks until they are replaced by the
	 * camera-extraction hook. There is no longer any fog state to read from
	 * RenderSystem, so retaining the last extracted {@link FogData} is safer
	 * than substituting an invented fog value.
	 */
	@Deprecated
	public static void update() {
	}

	/**
	 * Updates Flywheel's fog block from the fog state extracted by the 26.2 renderer.
	 *
	 * <p>Fog is no longer exposed as independent RenderSystem values. The renderer
	 * produces a {@link FogData} instance as part of camera extraction instead.</p>
	 */
	public static void update(FogData fog) {
		long ptr = BUFFER.ptr();

		var color = fog.color;

		ptr = writeFloat(ptr, color.x);
		ptr = writeFloat(ptr, color.y);
		ptr = writeFloat(ptr, color.z);
		ptr = writeFloat(ptr, color.w);
		ptr = writeFloat(ptr, fog.environmentalStart);
		ptr = writeFloat(ptr, fog.environmentalEnd);

		// 26.2 fog is radial; this is the legacy spherical shape expected by our shaders.
		ptr = writeInt(ptr, 1);

		BUFFER.markDirty();
	}
}
