package local.rustak.client;

import com.mojang.blaze3d.audio.Channel;
import local.rustak.client.building.WorldSolids;
import local.rustak.client.mixin.ChannelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;

/**
 * Sounds heard through a Rust building's walls or roof come muffled, as from the next room: an OpenAL low-pass filter
 * on the sound's source, stronger the more the building stands between it and the listener (WorldSolids.muffle).
 * The amount is worked out on the client thread; the filter is set on the sound thread, which holds the AL context.
 */
public final class SoundMuffle {
	private static final int LEVELS = 8;
	/** Per level, the EFX filter (0 = none); made on the sound thread on first use. */
	private static final int[] FILTERS = new int[LEVELS + 1];
	private static volatile boolean unsupported;

	private SoundMuffle() {
	}

	/** 0 (clear) .. LEVELS (fully muffled) for a playing sound; positionless ones (music, the interface) stay clear. */
	public static int level(SoundInstance s) {
		Minecraft mc = Minecraft.getInstance();
		if (unsupported || mc.level == null || s.isRelative() || s.getAttenuation() == SoundInstance.Attenuation.NONE) return 0;
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		float m = WorldSolids.muffle(mc.level, ear, new Vec3(s.getX(), s.getY(), s.getZ()));
		return Math.round(m * LEVELS);
	}

	/** On the sound thread: the channel's source gets the filter for this level. */
	public static void apply(Channel channel, int level) {
		if (unsupported) return;
		try {
			int source = ((ChannelAccessor) channel).rustak$source();
			AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter(level));
		} catch (Throwable t) { // no EFX on this device: leave sounds as they are
			unsupported = true;
		}
	}

	private static int filter(int level) {
		if (level <= 0) return EXTEfx.AL_FILTER_NULL;
		if (FILTERS[level] != 0) return FILTERS[level];
		if (!AL.getCapabilities().OpenAL10 || !org.lwjgl.openal.ALC.getCapabilities().ALC_EXT_EFX) {
			unsupported = true;
			return EXTEfx.AL_FILTER_NULL;
		}
		float m = level / (float) LEVELS;
		int f = EXTEfx.alGenFilters();
		EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAIN, 1 - 0.45f * m);
		EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAINHF, 1 - 0.9f * m);
		FILTERS[level] = f;
		return f;
	}
}
