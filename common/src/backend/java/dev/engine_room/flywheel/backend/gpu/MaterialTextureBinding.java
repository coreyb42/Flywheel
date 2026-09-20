package dev.engine_room.flywheel.backend.gpu;

import java.util.Objects;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import dev.engine_room.flywheel.api.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * A material's diffuse texture expressed as a 26.2 render-pass binding.
 *
 * <p>Textures are deliberately resolved when the pass is encoded rather than
 * cached here. TextureManager replaces texture objects during reload, while a
 * material's identifier remains stable across the reload boundary.</p>
 */
public record MaterialTextureBinding(Identifier texture, FilterMode filter, boolean mipmap) {
	public MaterialTextureBinding {
		Objects.requireNonNull(texture, "texture");
		Objects.requireNonNull(filter, "filter");
	}

	public static MaterialTextureBinding from(Material material) {
		Objects.requireNonNull(material, "material");
		return new MaterialTextureBinding(material.texture(), material.blur() ? FilterMode.LINEAR : FilterMode.NEAREST, material.mipmap());
	}

	/**
	 * Bind this material's texture and sampler to a named pipeline resource.
	 * This must be called after the target render pipeline is installed on
	 * {@code pass} and before its draw command.
	 */
	public void bind(RenderPass pass, String name) {
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(name, "name");

		AbstractTexture diffuse = Minecraft.getInstance().getTextureManager().getTexture(texture);
		pass.bindTexture(name, diffuse.getTextureView(), sampler());
	}

	private GpuSampler sampler() {
		return com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
				.getSampler(AddressMode.REPEAT, AddressMode.REPEAT, filter, filter, mipmap);
	}
}
