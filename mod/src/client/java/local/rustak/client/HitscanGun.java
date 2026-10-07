package local.rustak.client;

import local.rustak.RustAk;
import local.rustak.ShootPayload;
import local.rustak.WeaponStats;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A Rust bullet gun driven by its stats (weapons/<name>.json): fire rate, automatic or not,
 * magazine, reload and deploy times, recoil, aim zoom. The server does the hit (ShootPayload).
 */
class HitscanGun extends Gun {
	final WeaponStats stats;
	private final String[] fireClips;
	private final String fireAds, dryfire, dryfireAds;
	final float handFov, pitchScale;
	private float cooldown;
	private boolean dryClicked;
	private int clicks;
	/** The muzzle flash ViewmodelRenderer draws: when it was lit (System.nanoTime), its size, roll and sprite frames. */
	long flashAt = Long.MIN_VALUE;
	boolean flashDrawn = true;
	float flashSize, flashRoll, flashLength;
	int flashFrame, flashSide;

	HitscanGun(WeaponStats stats, String[] fireClips, String fireAds, String dryfire, String dryfireAds) {
		this(stats, fireClips, fireAds, dryfire, dryfireAds, 70, 1);
	}

	/** handFov: the viewmodel's projection (vanilla 70); pitchScale tames Rust's upward kick, uncontrollable here. */
	HitscanGun(WeaponStats stats, String[] fireClips, String fireAds, String dryfire, String dryfireAds, float handFov, float pitchScale) {
		super(itemFor(stats.name), stats.name, stats.magazine, stats.reloadTime, stats.deployDelay, stats.zoom, 5, 5, stats.punch);
		this.stats = stats;
		this.fireClips = fireClips;
		this.fireAds = fireAds;
		this.dryfire = dryfire;
		this.dryfireAds = dryfireAds;
		this.handFov = handFov;
		this.pitchScale = pitchScale;
	}

	@Override
	float handFov() {
		return handFov;
	}

	/** A left click as Minecraft counts it (MinecraftMixin.startAttack), so none are lost between frames. */
	@Override
	void click() {
		if (!stats.automatic) clicks = Math.min(clicks + 1, 2);
	}

	private static net.minecraft.world.item.Item itemFor(String name) {
		return RustAk.GUNS.entrySet().stream().filter(e -> e.getValue().name.equals(name)).findFirst().orElseThrow().getKey();
	}

	// Rust squashes its gun viewmodels in depth (AK 0.56, SAR 0.56, pistol 0.49) for its own viewmodel camera; at
	// Minecraft's hand FOV that crushes the gun and brings the stock into the sights. Guns keep the unsquashed
	// placement: the prefab's x/y offset, the camera at the rig's eye depth (0.1 hip, 0.066 in ADS).
	private static final float[] SCALE = {1, 1, 1};
	private float[] offset;

	@Override
	float[] viewOffset(ViewmodelRig rig) {
		if (offset == null) offset = new float[] {rig.R[0], rig.R[1], -0.1f};
		return offset;
	}

	@Override
	float[] viewScale(ViewmodelRig rig) {
		return SCALE;
	}

	@Override
	float adsDepth() {
		return 0.034f;
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
	void frame(LocalPlayer p, float dt) {
		Minecraft mc = Minecraft.getInstance();
		shotTimer(dt);
		if (!stats.automatic) {
			// semi-automatic: every click fires; one made during the repeat delay waits for it instead of being lost
			cooldown = Math.max(0, cooldown - dt);
			if (mc.screen != null || reloading() || deploying()) clicks = 0;
			if (clicks == 0 || cooldown > 0) return;
			clicks--;
			cooldown = stats.repeatDelay;
			if (ammo > 0) fire(p);
			else viewmodel().play(aiming ? dryfireAds : dryfire, 0.03f);
			return;
		}
		boolean trigger = trigger(mc);
		// automatics keep the fractional debt while held so the rate doesn't drift
		cooldown = trigger ? cooldown - dt : Math.max(0, cooldown - dt);
		if (!trigger) {
			dryClicked = false;
			return;
		}
		if (cooldown > 0) return;
		if (ammo > 0) {
			fire(p);
			cooldown = Math.max(cooldown + stats.repeatDelay, 0.02f);
		} else if (!dryClicked) {
			dryClicked = true;
			viewmodel().play(aiming ? dryfireAds : dryfire, 0.03f);
		}
	}

	/** Time since the last shot, for spray patterns that reset. */
	void shotTimer(float dt) {
	}

	void fire(LocalPlayer p) {
		ammo--;
		if (p.isSprinting()) p.setSprinting(false);
		ClientPlayNetworking.send(new ShootPayload());
		shotSound(p);
		viewmodel().play(aiming ? fireAds : fireClips[p.getRandom().nextInt(fireClips.length)], 0.02f);
		muzzleFlash(p);
		recoil(p);
	}

	/** The attack sound's layers: body, LFE, mechanism and outdoor tail, layered like Rust plays them. */
	void shotSound(LocalPlayer p) {
		float pitch = 0.95f + p.getRandom().nextFloat() * 0.1f;
		for (String layer : new String[] {"", "_1", "_2", "_3"}) {
			SoundEvent e = RustAk.SOUNDS.get(stats.name + "_attack" + layer);
			if (e != null) p.playSound(e, 1f, pitch);
		}
	}

	/** Recoil: random yaw and pitch in their ranges, taken over a short time, scaled in ADS and when moving. */
	void recoil(LocalPlayer p) {
		var r = p.getRandom();
		float yaw = (stats.yawMin + r.nextFloat() * (stats.yawMax - stats.yawMin)) * pitchScale;
		float pitchUp = -(stats.pitchMin + r.nextFloat() * (stats.pitchMax - stats.pitchMin)) * pitchScale; // negative is up in Unity
		float scale = aiming ? stats.adsScale : 1f;
		if (p.getDeltaMovement().horizontalDistanceSqr() > 0.001) scale *= 1 + stats.movementPenalty;
		RustAkClient.addRecoil(yaw * scale, pitchUp * scale, stats.timeToTakeMin + r.nextFloat() * (stats.timeToTakeMax - stats.timeToTakeMin));
	}

	/**
	 * The muzzle flash is drawn by ViewmodelRenderer at the viewmodel's muzzle, in its own pass, as Rust parents it to
	 * the viewmodel. The attack effect (smoke, embers) goes into the world where the muzzle shows on screen: the hand
	 * has its own projection, so the camera-space muzzle is rescaled to the world's FOV.
	 */
	void muzzleFlash(LocalPlayer p) {
		var r = p.getRandom();
		flashAt = System.nanoTime();
		flashDrawn = false;
		flashSize = 0.09f + r.nextFloat() * 0.05f; // muzzle_flash_front: startSize 0.04-0.07, grows over its life
		flashLength = 0.16f + r.nextFloat() * 0.08f; // muzzle_flash_side: stretched 1.1-1.6
		flashRoll = r.nextFloat() * Mth.TWO_PI;
		flashFrame = r.nextInt(9);
		flashSide = r.nextInt(4);
		Minecraft mc = Minecraft.getInstance();
		Vector3f m = viewmodel().muzzleView; // camera space: x right, y up, -z forward
		float worldFov = mc.options.fov().get() / (aiming ? zoom : 1);
		double k = Math.tan(Math.toRadians(worldFov / 2)) / Math.tan(Math.toRadians(handFov / 2));
		Vec3 fwd = p.getViewVector(1), up = p.getUpVector(1), right = fwd.cross(up).normalize();
		Vec3 at = p.getEyePosition().add(right.scale(m.x * k)).add(up.scale(m.y * k)).add(fwd.scale(-m.z));
		FxManager.play("attack_" + stats.name, at, fwd);
	}

	/** Clip events name the gun's effect prefabs (clip_out, bolt_back...), each with its sound. */
	@Override
	void onClipEvent(String name) {
		SoundEvent e = RustAk.SOUNDS.get(stats.name + "_" + name);
		if (e != null) play(e);
		else super.onClipEvent(name);
	}
}
