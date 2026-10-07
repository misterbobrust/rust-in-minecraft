package local.rustak.client;

import local.rustak.RocketEntity;
import local.rustak.RustAk;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** The rocket's engine sound, looping on the rocket while it flies. */
final class RocketSound extends AbstractTickableSoundInstance {
	private final RocketEntity rocket;

	RocketSound(RocketEntity rocket) {
		super(RustAk.ROCKET_ENGINE, SoundSource.NEUTRAL, RandomSource.create());
		this.rocket = rocket;
		this.looping = true;
		this.delay = 0;
		this.volume = 2f;
		this.x = rocket.getX();
		this.y = rocket.getY();
		this.z = rocket.getZ();
	}

	@Override
	public void tick() {
		if (rocket.isRemoved()) {
			stop();
			return;
		}
		x = rocket.getX();
		y = rocket.getY();
		z = rocket.getZ();
	}
}
