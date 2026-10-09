package local.rustak.client;

import org.joml.Vector3f;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Plays a Rust viewmodel: a looping base (idle, blended toward idle_ads while aiming when the weapon has one) and
 * one-shot actions (fire, reload, deploy...) at their animator state speeds, with crossfades. On top:
 * Rust's own values for aiming, fire punch, walk/run bob, lowering and sway,
 * and the camera clips. The motion formulas
 * here are reconstructions around the real parameters.
 */
public final class Viewmodel {
	// fire punch (AK): direction (0, 0, -1), magnitude 0.02, duration 0.15
	private static final float[][] PUNCH = {{0, 0, 5.9609f}, {0.1667f, 0.9939f, -0.4843f}, {0.316f, -0.0403f, -3.0832f},
		{0.4299f, 0.0467f, -0.1811f}, {1, 0, 0.0179f}};
	// walk/run bob (same values on both weapons)
	private static final float BOB_SPEED_WALK = 9, BOB_SPEED_RUN = 13, BOB_WALK = 0.005f, BOB_RUN = 0.02f, LEFT_OFFSET_RUN = 0.04f;

	final Gun gun;
	final ViewmodelRig rig;
	/** The muzzle in the hand's view space as last drawn (x right, y up, -z forward). */
	final org.joml.Vector3f muzzleView = new org.joml.Vector3f(0.1f, -0.1f, -0.6f);
	final ViewmodelRig.Pose base, basePart, action, from, current;
	private ViewmodelRig.Clip actionClip;
	private long actionStart, fadeStart, lastFrame = System.nanoTime(), punchStart = -1;
	private float fade = 0.1f, lastActionTime;

	private float adsProgress = 1; // 0..1 along the current in/out transition
	private boolean adsIn;
	float sprint, moving, swayX, swayY, lastYaw, lastPitch, bobPhase;
	boolean hasLast;

	Viewmodel(Gun gun) {
		this.gun = gun;
		this.rig = new ViewmodelRig(gun.rig);
		base = rig.newPose();
		basePart = rig.newPose();
		action = rig.newPose();
		from = rig.newPose();
		current = rig.newPose();
	}

	/** Counts actions started, so the camera can tell a new one (even the same clip again) and blend into it. */
	int plays;

	public void play(String clip, float fadeSeconds) {
		ViewmodelRig.Clip c = rig.clips.get(clip);
		if (c == null) return;
		plays++;
		snapshot();
		actionClip = c;
		actionStart = fadeStart = System.nanoTime();
		fade = fadeSeconds;
		lastActionTime = -1;
		if (gun.punch && clip.startsWith("fire")) punchStart = actionStart;
	}

	private void snapshot() {
		System.arraycopy(current.pos, 0, from.pos, 0, from.pos.length);
		System.arraycopy(current.rot, 0, from.rot, 0, from.rot.length);
		System.arraycopy(current.scale, 0, from.scale, 0, from.scale.length);
	}

	public boolean isPlaying(String clip) {
		return actionClip != null && actionClip.name.equals(clip);
	}

	/** Real seconds since the current action started. */
	public float actionTime() {
		return (System.nanoTime() - actionStart) / 1e9f;
	}

	/** Advances everything to now and returns the final local pose. */
	public ViewmodelRig.Pose update(LocalPlayer player, float partialTick) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		gun.frame(player, dt);
		RustAkClient.applyRecoil(player, dt);

		boolean aim = gun.aiming;
		if (aim != adsIn) {
			adsIn = aim;
			adsProgress = 1 - adsProgress; // continue from the same spot on the other transition
		}
		adsProgress = Math.min(1, adsProgress + dt * (adsIn ? gun.adsInSpeed : gun.adsOutSpeed));

		boolean running = player.isSprinting() && actionClip == null;
		sprint = approach(sprint, running ? 1 : 0, dt / 0.15f);
		float speed = (float) player.getDeltaMovement().horizontalDistance();
		moving = approach(moving, player.onGround() && speed > 0.03f ? 1 : 0, dt / 0.1f);
		bobPhase += dt * Mth.lerp(sprint, BOB_SPEED_WALK, BOB_SPEED_RUN) * moving;

		// sway: the gun lags behind camera turns
		float yaw = player.getViewYRot(partialTick), pitch = player.getViewXRot(partialTick);
		if (hasLast) {
			float k = 1 - 0.6f * ads();
			swayX += Mth.clamp(Mth.wrapDegrees(yaw - lastYaw), -20, 20) * 0.0015f * k;
			swayY += Mth.clamp(pitch - lastPitch, -20, 20) * 0.0015f * k;
		}
		lastYaw = yaw;
		lastPitch = pitch;
		hasLast = true;
		float decay = (float) Math.exp(-dt * 12);
		swayX = Mth.clamp(swayX * decay, -0.03f, 0.03f);
		swayY = Mth.clamp(swayY * decay, -0.03f, 0.03f);

		float seconds = now / 1e9f;
		ViewmodelRig.Clip idle = rig.clips.get("idle");
		rig.sample(idle, seconds % idle.length(), base);
		ViewmodelRig.Clip idleAds = rig.clips.get("idle_ads");
		if (idleAds != null) {
			rig.sample(idleAds, 0, basePart);
			ViewmodelRig.blend(base, basePart, ads(), base);
		}

		ViewmodelRig.Pose goal = base;
		if (actionClip != null) {
			float t = actionTime() * actionClip.speed;
			fireEvents(lastActionTime, t);
			lastActionTime = t;
			if (t >= actionClip.length()) {
				snapshot(); // action over: fade back to the base loop from wherever it ended
				actionClip = null;
				fadeStart = now;
				fade = 0.12f;
			} else {
				rig.sample(actionClip, t, action);
				goal = action;
			}
		}
		float f = fade <= 0 ? 1 : Math.min(1, (now - fadeStart) / 1e9f / fade);
		ViewmodelRig.blend(from, goal, smooth(f), current);
		return current;
	}

	private void fireEvents(float from, float to) {
		for (int i = 0; i < actionClip.eventTime.length; i++) {
			float e = actionClip.eventTime[i];
			if (e > from && e <= to) gun.onClipEvent(actionClip.eventName[i]);
		}
	}

	/** ADS weight 0..1. */
	public float ads() {
		return gun.adsEase(adsIn, adsProgress);
	}

	/** Procedural offset for the whole viewmodel in view space (x right, y up, z toward the viewer). */
	public void proceduralOffset(Vector3f outPos, Vector3f outRotDeg) {
		float s = smooth(sprint), a = ads();
		float amount = Mth.lerp(s, BOB_WALK, BOB_RUN) * moving * (1 - 0.7f * a);
		float bobX = Mth.cos(bobPhase) * amount;
		float bobY = -Math.abs(Mth.sin(bobPhase)) * amount;
		float punch = 0;
		if (punchStart >= 0) {
			float t = (System.nanoTime() - punchStart) / 1e9f / 0.15f;
			if (t < 1) punch = curve(PUNCH, t) * 0.02f;
			else punchStart = -1;
		}
		outPos.set(-swayX + bobX - LEFT_OFFSET_RUN * s, swayY + bobY - 0.05f * s, punch + 0.02f * s);
		outRotDeg.set(-18 * s, 32 * s, 12 * s);
	}

	/** Seconds the current action takes to blend in. */
	public float fadeSeconds() {
		return fade;
	}

	public int plays() {
		return plays;
	}

	/** The camera clip of the current action, in degrees (Unity euler x, y, z). */
	public boolean cameraEuler(Vector3f out) {
		if (actionClip == null) return false;
		float[] e = rig.cameraClips.get(actionClip.name);
		if (e == null) return false;
		int n = e.length / 3;
		float fr = Math.min(n - 1, actionTime() * rig.cameraSpeed.getOrDefault(actionClip.name, 1f) * 30f);
		int f0 = (int) fr, f1 = Math.min(n - 1, f0 + 1);
		float k = fr - f0;
		out.set(Mth.lerp(k, e[f0 * 3], e[f1 * 3]), Mth.lerp(k, e[f0 * 3 + 1], e[f1 * 3 + 1]), Mth.lerp(k, e[f0 * 3 + 2], e[f1 * 3 + 2]));
		return true;
	}

	/** Unity AnimationCurve with non-weighted tangents: cubic Hermite between keys (time, value, slope). */
	static float curve(float[][] keys, float t) {
		if (t <= keys[0][0]) return keys[0][1];
		for (int i = 0; i < keys.length - 1; i++) {
			float[] a = keys[i], b = keys[i + 1];
			if (t <= b[0]) {
				float dt = b[0] - a[0], u = (t - a[0]) / dt, u2 = u * u, u3 = u2 * u;
				return (2 * u3 - 3 * u2 + 1) * a[1] + (u3 - 2 * u2 + u) * dt * a[2] + (-2 * u3 + 3 * u2) * b[1] + (u3 - u2) * dt * b[2];
			}
		}
		return keys[keys.length - 1][1];
	}

	static float approach(float v, float target, float step) {
		return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
	}

	static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}
}
