package dev.engine_room.flywheel.backend.engine;

import java.util.Objects;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/** Texture bindings for Flywheel render pipelines.
 *
 * <p>Minecraft 26.2 has no global texture-unit state. Every binding belongs to
 * the render pass which uses it, so callers must bind textures after selecting
 * their pipeline and before issuing its draw commands.</p>
 */
public class TextureBinder {
	public static final String DIFFUSE = "flw_diffuseTex";
	public static final String OVERLAY = "flw_overlayTex";
	public static final String LIGHT = "flw_lightTex";

	private TextureBinder() {
	}

	public static void bind(RenderPass pass, String name, Identifier resourceLocation) {
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(name, "name");
		AbstractTexture texture = texture(resourceLocation);
		pass.bindTexture(name, texture.getTextureView(), texture.getSampler());
	}

	/** Bind the game overlay and lightmap views used by Flywheel material shaders. */
	public static void bindLightAndOverlay(RenderPass pass) {
		Objects.requireNonNull(pass, "pass");
		var gameRenderer = Minecraft.getInstance().gameRenderer;
		pass.bindTexture(OVERLAY, gameRenderer.overlayTexture().getTextureView(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		pass.bindTexture(LIGHT, gameRenderer.lightmap(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
	}

	/**
	 * Get a built-in texture by its resource location.
	 *
	 * @param texture The texture's resource location.
	 * @return The current GPU-backed texture object.
	 */
	public static AbstractTexture texture(Identifier texture) {
		return Minecraft.getInstance()
				.getTextureManager()
				.getTexture(texture);
	}
}
