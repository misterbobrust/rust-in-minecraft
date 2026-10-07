package local.rustak.client;

import local.rustak.RustAk;
import net.minecraft.client.player.LocalPlayer;

/** Rust's AK: the generic hitscan gun with the spray pattern the user preferred and its own sound mix. */
final class AkGun extends HitscanGun {
	// Spray pattern (yaw, pitch up) in degrees per shot: an approximation of Rust's old AK pattern, which the user
	// preferred over the current random recoil. Timing and scales stay as tuned before.
	static final float[][] PATTERN = {
		{0f, 1.9f}, {-0.2f, 1.9f}, {-0.4f, 1.8f}, {-0.6f, 1.7f}, {-0.7f, 1.5f}, {-0.6f, 1.3f}, {-0.3f, 1.1f},
		{0.2f, 0.9f}, {0.6f, 0.8f}, {0.9f, 0.7f}, {1.0f, 0.6f}, {0.8f, 0.5f}, {0.4f, 0.5f}, {-0.1f, 0.5f},
		{-0.6f, 0.5f}, {-0.9f, 0.4f}, {-1.0f, 0.4f}, {-0.8f, 0.4f}, {-0.4f, 0.4f}, {0.1f, 0.4f}, {0.5f, 0.4f},
		{0.8f, 0.4f}, {0.8f, 0.4f}, {0.5f, 0.4f}, {0.1f, 0.4f}, {-0.3f, 0.4f}, {-0.6f, 0.4f}, {-0.6f, 0.4f},
		{-0.3f, 0.4f}, {0f, 0.4f}};
	static final float TIME_TO_TAKE = 0.1f, ADS_SCALE = 0.75f, MOVEMENT_PENALTY = 0.2f;
	// aim-in curve (time, value, slope); 0.2 s each way was picked by feel
	private static final float[][] INTRO = {{0, 0, 2}, {1, 1, 0}};
	private float sinceShot = 1;
	private int shotIndex;

	AkGun() {
		super(RustAk.GUNS.get(RustAk.AK), new String[] {"fire-1", "fire-2", "fire-3"}, "fire_ads", "dryfire", "dryfire_ads");
	}

	@Override
	float adsEase(boolean in, float progress) {
		return in ? Viewmodel.curve(INTRO, progress) : 1 - Viewmodel.curve(INTRO, progress);
	}

	@Override
	void shotTimer(float dt) {
		sinceShot += dt;
		if (sinceShot > 0.3f) shotIndex = 0;
	}

	@Override
	void shotSound(LocalPlayer p) {
		p.playSound(RustAk.SHOT, 1f, 0.95f + p.getRandom().nextFloat() * 0.1f);
	}

	@Override
	void recoil(LocalPlayer p) {
		sinceShot = 0;
		float[] kick = PATTERN[Math.min(shotIndex++, PATTERN.length - 1)];
		float scale = aiming ? ADS_SCALE : 1f;
		if (p.getDeltaMovement().horizontalDistanceSqr() > 0.001) scale *= 1 + MOVEMENT_PENALTY;
		RustAkClient.addRecoil(kick[0] * scale, kick[1] * scale, TIME_TO_TAKE);
	}

	@Override
	void onClipEvent(String name) {
		switch (name) {
			case "reload_start" -> play(RustAk.RELOAD_START);
			case "grab_magazine" -> play(RustAk.GRAB_MAG);
			case "insert_magazine" -> play(RustAk.INSERT_MAG);
			case "bolt_back" -> play(RustAk.BOLT_BACK);
			case "bolt_forward" -> play(RustAk.BOLT_FORWARD);
			case "deploy" -> play(RustAk.DEPLOY);
			case "dryfire" -> play(RustAk.DRYFIRE);
			default -> super.onClipEvent(name);
		}
	}
}
