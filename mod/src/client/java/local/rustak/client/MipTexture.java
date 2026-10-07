package local.rustak.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;

/**
 * A texture with a full mip chain and trilinear filtering. Rust's textures are 1024 px over a 3 m wall: sampled like
 * Minecraft's entity textures (one level, nearest) they shimmer into noise a few blocks away.
 */
public class MipTexture extends SimpleTexture {
	public MipTexture(Identifier id) {
		super(id);
	}

	@Override
	public void apply(TextureContents contents) {
		sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, true);
		try (NativeImage image = contents.image()) {
			doLoad(image);
		}
	}

	@Override
	protected void doLoad(NativeImage image) {
		int w = image.getWidth(), h = image.getHeight(), levels = 1;
		while ((w >> levels - 1) % 2 == 0 && (h >> levels - 1) % 2 == 0 && w >> levels > 0 && h >> levels > 0) levels++;
		NativeImage[] mips = MipmapGenerator.generateMipLevels(resourceId(), new NativeImage[] {image}, levels - 1, MipmapStrategy.AUTO, 0);
		GpuDevice device = RenderSystem.getDevice();
		close();
		texture = device.createTexture(resourceId()::toString, 5, TextureFormat.RGBA8, w, h, 1, levels);
		textureView = device.createTextureView(texture);
		for (int i = 0; i < levels; i++) {
			device.createCommandEncoder().writeToTexture(texture, mips[i], i, 0, 0, 0, mips[i].getWidth(), mips[i].getHeight(), 0, 0);
			if (i > 0) mips[i].close();
		}
	}
}
