package dev.engine_room.flywheel.backend;

import java.io.IOException;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public class NoiseTextures {
	public static final Identifier NOISE_TEXTURE = ResourceUtil.rl("textures/flywheel/noise/blue.png");

	@Nullable
	private static GpuTexture blueNoise;
	@Nullable
	private static GpuTextureView blueNoiseView;

	public static void reload(ResourceManager manager) {
		close();
		var optional = manager.getResource(NOISE_TEXTURE);

		if (optional.isEmpty()) {
			return;
		}

		try (var is = optional.get()
				.open()) {
			var image = NativeImage.read(NativeImage.Format.LUMINANCE, is);

			blueNoise = RenderSystem.getDevice()
					.createTexture(() -> "flywheel/blue_noise", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
							GpuFormat.R8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
			try {
				blueNoiseView = RenderSystem.getDevice().createTextureView(blueNoise);
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(blueNoise, image);
			} catch (RuntimeException | Error e) {
				close();
				throw e;
			} finally {
				image.close();
			}
		} catch (IOException e) {

		}
	}

	/** Bind the blue-noise texture to a named resource on an active render pass. */
	public static void bind(RenderPass pass, String name) {
		if (blueNoiseView == null) {
			throw new IllegalStateException("Blue-noise texture has not been loaded");
		}
		pass.bindTexture(name, blueNoiseView, sampler());
	}

	private static GpuSampler sampler() {
		return RenderSystem.getSamplerCache()
				.getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, false);
	}

	private static void close() {
		if (blueNoiseView != null) {
			blueNoiseView.close();
			blueNoiseView = null;
		}
		if (blueNoise != null) {
			blueNoise.close();
			blueNoise = null;
		}
	}
}
