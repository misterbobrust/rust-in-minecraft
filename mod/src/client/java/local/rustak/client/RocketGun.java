package local.rustak.client;

import java.util.Set;

import local.rustak.FireRocketPayload;
import local.rustak.RustAk;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Rust's rocket launcher: 2 s between shots, 3 s to draw, 6.162 s reload, 1 rocket,
 * spread 2.25 deg plus 1.8 deg from the hip; recoil pitch 15-20, yaw 5-10, over 0.1-0.2 s, ADS x0.75.
 * Aiming: zoom 1.25, in speed 5, out speed 3.
 */
final class RocketGun extends Gun {
	private float cooldown;
	private boolean dryClicked;

	RocketGun() {
		super(RustAk.ROCKET_LAUNCHER, "rocketlauncher", 1, 6.162f, 3.0f, 1.25f, 5, 3, false);
	}

	@Override
	String deployClip() {
		return "deploy";
	}

	@Override
	String reloadClip() {
		return "reload";
	}

	@Override
	Set<String> hiddenParts() {
		return ammo == 0 && !reloading() ? Set.of("rocket_mesh") : Set.of();
	}

	@Override
	void frame(LocalPlayer p, float dt) {
		Minecraft mc = Minecraft.getInstance();
		cooldown = Math.max(0, cooldown - dt);
		boolean trigger = trigger(mc);
		if (!trigger) {
			dryClicked = false;
			return;
		}
		if (cooldown > 0 || dryClicked) return;
		if (ammo > 0) fire(p);
		else {
			dryClicked = true;
			viewmodel().play("dryfire", 0.05f);
		}
	}

	private void fire(LocalPlayer p) {
		ammo--;
		cooldown = 2;
		dryClicked = true; // semi-automatic
		if (p.isSprinting()) p.setSprinting(false);
		var r = p.getRandom();
		float cone = 2.25f + (aiming ? 0 : 1.8f);
		Vec3 dir = cone(p.getViewVector(1), cone, r.nextFloat(), r.nextFloat());
		ClientPlayNetworking.send(new FireRocketPayload(dir));
		p.playSound(RustAk.ROCKET_ATTACK, 1f, 1f);
		viewmodel().play("attack", 0.03f);
		FxManager.play("rocket_launch", muzzle(p), dir);
		float scale = aiming ? 0.75f : 1f;
		float yaw = (5 + r.nextFloat() * 5) * (r.nextBoolean() ? 1 : -1);
		RustAkClient.addRecoil(yaw * scale, (15 + r.nextFloat() * 5) * scale, 0.1f + r.nextFloat() * 0.1f);
	}

	/** Roughly where the tube ends on screen: ahead, a little right and down of the eyes. */
	static Vec3 muzzle(LocalPlayer p) {
		Vec3 look = p.getViewVector(1);
		Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
		return p.getEyePosition().add(look.scale(0.8)).add(right.scale(0.12)).add(0, -0.12, 0);
	}

	/** Uniform random direction within a cone of the given full angle (Rust's AimConeUtil). */
	static Vec3 cone(Vec3 dir, float degrees, float u, float v) {
		double half = Math.toRadians(degrees) / 2;
		double cos = 1 - u * (1 - Math.cos(half)), sin = Math.sqrt(1 - cos * cos), phi = v * Math.PI * 2;
		Vec3 a = Math.abs(dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 x = dir.cross(a).normalize(), y = dir.cross(x);
		return dir.scale(cos).add(x.scale(sin * Mth.cos((float) phi))).add(y.scale(sin * Mth.sin((float) phi))).normalize();
	}
}
