package local.rustak.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import local.rustak.RocketEntity;
import local.rustak.building.Obb;
import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Plays Rust particle effects (FxDef) with Minecraft particles: emission over time and distance, bursts, Unity's
 * shapes, and the systems' transforms inside the prefab. Unity space (x right, y up, z forward) maps onto an
 * effect's world basis; local-space systems drag their particles along with the emitter.
 */
public final class FxManager {
	static final Map<String, FabricSpriteProvider> SPRITES = new HashMap<>();
	private static final Map<String, FxDef> DEFS = new HashMap<>();
	private static final List<Emitter> EMITTERS = new ArrayList<>();
	private static final Map<Integer, Emitter> ROCKETS = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();
	static final float DT = 0.05f;

	static FxDef def(String name) {
		return DEFS.computeIfAbsent(name, FxDef::load);
	}

	public static void play(String effect, Vec3 pos, Vec3 forward) {
		EMITTERS.add(new Emitter(def(effect), pos, forward));
	}

	/** Emits count particles from every system at once: for effects Rust's scripts emit by hand (gibs). */
	public static void burst(String effect, Vec3 pos, Vec3 forward, int count, Obb box) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		Emitter e = new Emitter(def(effect), pos, forward);
		e.volume = box;
		e.playing = false;
		for (int i = 0; i < e.def.systems.size(); i++) {
			for (int k = 0; k < count; k++) e.spawn(level, i, e.def.systems.get(i), RANDOM.nextFloat());
		}
		EMITTERS.add(e);
	}

	/** Effects that emit from their parent's mesh in Rust (ParticleEmitFromParentObject) emit from this box. */
	public static void playInBox(String effect, Vec3 pos, Vec3 forward, Obb box) {
		Emitter e = new Emitter(def(effect), pos, forward);
		e.volume = box;
		EMITTERS.add(e);
	}

	/** Called from RocketEntity.tick on the client: keeps rocket_trail (and the engine loop) on the rocket. */
	static void rocketTick(RocketEntity rocket) {
		Emitter e = ROCKETS.get(rocket.getId());
		Vec3 fwd = rocket.getDeltaMovement().lengthSqr() > 1e-8 ? rocket.getDeltaMovement().normalize() : new Vec3(0, 0, 1);
		if (e == null) {
			e = new Emitter(def("rocket_trail"), rocket.position(), fwd);
			ROCKETS.put(rocket.getId(), e);
			EMITTERS.add(e);
			Minecraft.getInstance().getSoundManager().play(new RocketSound(rocket));
		}
		e.moveTo(rocket.position(), fwd);
		e.seen = true;
	}

	static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			EMITTERS.clear();
			ROCKETS.clear();
			return;
		}
		if (mc.isPaused()) return;
		for (Iterator<Map.Entry<Integer, Emitter>> it = ROCKETS.entrySet().iterator(); it.hasNext(); ) {
			Emitter e = it.next().getValue();
			if (!e.seen) { // the rocket is gone: stop emitting, let its particles live out
				e.playing = false;
				it.remove();
			}
			e.seen = false;
		}
		EMITTERS.removeIf(e -> !e.tick(level));
	}

	/** One playing effect instance. */
	static final class Emitter {
		final FxDef def;
		Vec3 pos, prevPos;
		Vec3 right, up, fwd;
		boolean playing = true, seen;
		Obb volume;
		final float[] time, accum, delay;
		final int[] alive;
		final boolean[] started;

		Emitter(FxDef def, Vec3 pos, Vec3 forward) {
			this.def = def;
			this.pos = this.prevPos = pos;
			setBasis(forward);
			int n = def.systems.size();
			time = new float[n];
			accum = new float[n];
			delay = new float[n];
			alive = new int[n];
			started = new boolean[n];
			for (int i = 0; i < n; i++) delay[i] = def.systems.get(i).startDelay.eval(0, RANDOM.nextFloat());
		}

		void moveTo(Vec3 p, Vec3 forward) {
			pos = p;
			setBasis(forward);
		}

		/** Unity LookRotation(forward): +Z forward, +Y up; right = forward x up in Minecraft's right-handed space. */
		private void setBasis(Vec3 forward) {
			fwd = forward.normalize();
			Vec3 worldUp = Math.abs(fwd.y) > 0.99 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
			right = fwd.cross(worldUp).normalize();
			up = right.cross(fwd).normalize();
		}

		Vec3 toWorld(double x, double y, double z) {
			return pos.add(right.scale(x)).add(up.scale(y)).add(fwd.scale(z));
		}

		Vec3 dirToWorld(double x, double y, double z) {
			return right.scale(x).add(up.scale(y)).add(fwd.scale(z));
		}

		/** Returns false when finished and all its particles are gone. */
		boolean tick(ClientLevel level) {
			boolean any = false;
			double moved = pos.distanceTo(prevPos);
			for (int i = 0; i < def.systems.size(); i++) {
				FxDef.Sys s = def.systems.get(i);
				float before = time[i];
				time[i] += DT * s.simSpeed;
				float t = time[i] - delay[i], prev = before - delay[i];
				if (t < 0) {
					any = true;
					continue;
				}
				boolean emitting = playing && (s.looping || t <= s.duration);
				if (emitting) {
					float norm = (t % s.duration) / s.duration;
					accum[i] += s.rate.eval(norm, RANDOM.nextFloat()) * DT * s.simSpeed;
					accum[i] += s.rateDistance.eval(norm, RANDOM.nextFloat()) * (float) moved;
					if (!started[i]) { // the first emitting tick also catches bursts at time 0
						started[i] = true;
						prev = -1e-4f;
					}
					float from = s.looping ? prev % s.duration : prev, to = s.looping ? t % s.duration : t;
					boolean wrapped = s.looping && to < from;
					for (FxDef.Burst b : s.bursts) {
						int cycles = b.cycles <= 0 ? 1000 : b.cycles;
						for (int c = 0; c < cycles; c++) {
							float bt = b.time + c * b.interval;
							boolean crossed = wrapped ? bt > from || bt <= to : bt > from && bt <= to;
							if (crossed && RANDOM.nextFloat() <= b.probability) accum[i] += b.count.eval(0, RANDOM.nextFloat());
							if (b.interval <= 0 || bt > to && !wrapped) break;
						}
					}
					int n = Math.min((int) accum[i], 200);
					accum[i] -= n;
					for (int k = 0; k < n && alive[i] < s.maxParticles; k++) spawn(level, i, s, norm);
				}
				if (emitting || alive[i] > 0) any = true;
			}
			prevPos = pos;
			return any;
		}

		private void spawn(ClientLevel level, int index, FxDef.Sys s, float norm) {
			FabricSpriteProvider sprites = SPRITES.get(s.sprite);
			if (sprites == null) return;
			float r = RANDOM.nextFloat();
			double[] p = new double[3], d = {0, 0, 1};
			if (s.shapeOn) shape(s, p, d);
			// shape transform, then the system's transform in the prefab
			shapeTransform(s, p, d);
			double[] lp = mul(s.matrix, p, 1), ld = mul(s.matrix, d, 0);
			double len = Math.sqrt(ld[0] * ld[0] + ld[1] * ld[1] + ld[2] * ld[2]);
			if (len > 1e-6) for (int k = 0; k < 3; k++) ld[k] /= len;
			Vec3 at = toWorld(lp[0], lp[1], lp[2]);
			Vec3 dir = dirToWorld(ld[0], ld[1], ld[2]);
			if (volume != null && (s.shapeType == 6 || s.shapeType == 13 || s.shapeType == 14)) {
				double[] w = volume.toWorld((RANDOM.nextDouble() * 2 - 1) * volume.ex(), (RANDOM.nextDouble() * 2 - 1) * volume.ez());
				at = volume.center().add(w[0], (RANDOM.nextDouble() * 2 - 1) * volume.ey(), w[1]);
				dir = randomUnit();
			}
			float speed = s.speed.eval(norm, RANDOM.nextFloat());
			float life = Math.max(0.05f, s.lifetime.eval(norm, RANDOM.nextFloat()));
			float size = s.size.eval(norm, RANDOM.nextFloat()) * (s.scalingMode == 0 ? s.matrixScale() : 1);
			float[] color = new float[4];
			s.color.eval(norm, RANDOM.nextFloat(), color);
			for (int k = 0; k < 4; k++) color[k] = Math.min(1, color[k] * (s.legacyTint ? 2 * s.tint[k] : s.tint[k]));
			RustParticle part = new RustParticle(level, at, dir.scale(speed), life, size, s.rotation.eval(norm, RANDOM.nextFloat()),
				color, s, sprites.getSprites(), s.worldSpace ? null : this, index, RANDOM);
			part.setGravity(s.gravity.eval(norm, RANDOM.nextFloat()));
			alive[index]++;
			Minecraft.getInstance().particleEngine.add(part);
		}

		/** Unity ParticleSystemShapeType -> position and direction in shape space. */
		private static void shape(FxDef.Sys s, double[] p, double[] d) {
			double phi = RANDOM.nextFloat() * Math.toRadians(s.arc > 0 ? s.arc : 360);
			double u = RANDOM.nextFloat();
			switch (s.shapeType) {
				case 0, 1, 2, 3 -> { // sphere / hemisphere (shells)
					Vec3 v = randomUnit();
					double z = s.shapeType >= 2 ? Math.abs(v.z) : v.z;
					double rr = s.radius * (s.shapeType == 1 || s.shapeType == 3 ? 1 : Mth.lerp(Math.cbrt(u), 1 - s.thickness, 1));
					d[0] = v.x;
					d[1] = v.y;
					d[2] = z;
					p[0] = v.x * rr;
					p[1] = v.y * rr;
					p[2] = z * rr;
				}
				case 4, 7, 8, 9 -> { // cone: base disk (or apex) tilted outward by angle
					double rFrac = s.radius < 1e-4 ? Math.sqrt(u) : Math.sqrt(Mth.lerp(u, Math.pow(1 - s.thickness, 2), 1));
					double rr = s.radius * rFrac;
					double tilt = Math.toRadians(s.angle) * rFrac;
					p[0] = rr * Math.cos(phi);
					p[1] = rr * Math.sin(phi);
					d[0] = Math.sin(tilt) * Math.cos(phi);
					d[1] = Math.sin(tilt) * Math.sin(phi);
					d[2] = Math.cos(tilt);
					if (s.shapeType >= 8) {
						double along = RANDOM.nextFloat() * s.length;
						for (int k = 0; k < 3; k++) p[k] += d[k] * along;
					}
				}
				case 5, 15, 16 -> { // box
					p[0] = (RANDOM.nextFloat() - 0.5) * s.shapeScale[0];
					p[1] = (RANDOM.nextFloat() - 0.5) * s.shapeScale[1];
					p[2] = (RANDOM.nextFloat() - 0.5) * s.shapeScale[2];
				}
				case 10, 11, 17 -> { // circle / edge / donut: XY plane, radial direction
					double rr = s.shapeType == 10 ? s.radius * Math.sqrt(Mth.lerp(u, Math.pow(1 - s.thickness, 2), 1)) : s.radius;
					p[0] = rr * Math.cos(phi);
					p[1] = rr * Math.sin(phi);
					d[0] = Math.cos(phi);
					d[1] = Math.sin(phi);
					d[2] = 0;
				}
				case 12 -> { // single-sided edge along X, emitting +Y
					p[0] = (RANDOM.nextFloat() * 2 - 1) * s.radius;
					d[0] = 0;
					d[1] = 1;
					d[2] = 0;
				}
				default -> {
				}
			}
			if (s.randomDir > 0 || s.sphericalDir > 0) {
				Vec3 rnd = randomUnit();
				double pl = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
				for (int k = 0; k < 3; k++) {
					double sph = pl > 1e-6 ? p[k] / pl : d[k];
					d[k] = Mth.lerp(s.randomDir, d[k], k == 0 ? rnd.x : k == 1 ? rnd.y : rnd.z);
					d[k] = Mth.lerp(s.sphericalDir, d[k], sph);
				}
			}
		}

		/** The shape module's own position / rotation (Unity euler: Z, then X, then Y) / scale. */
		private static void shapeTransform(FxDef.Sys s, double[] p, double[] d) {
			if (s.shapeType != 5 && s.shapeType != 15 && s.shapeType != 16) {
				for (int k = 0; k < 3; k++) p[k] *= s.shapeScale[k];
			}
			rotate(p, s.shapeRot);
			rotate(d, s.shapeRot);
			for (int k = 0; k < 3; k++) p[k] += s.shapePos[k];
		}

		private static void rotate(double[] v, float[] eulerDeg) {
			double x = v[0], y = v[1], z = v[2];
			double cz = Math.cos(Math.toRadians(eulerDeg[2])), sz = Math.sin(Math.toRadians(eulerDeg[2]));
			double nx = x * cz - y * sz, ny = x * sz + y * cz;
			x = nx;
			y = ny;
			double cx = Math.cos(Math.toRadians(eulerDeg[0])), sx = Math.sin(Math.toRadians(eulerDeg[0]));
			ny = y * cx - z * sx;
			double nz = y * sx + z * cx;
			y = ny;
			z = nz;
			double cy = Math.cos(Math.toRadians(eulerDeg[1])), sy = Math.sin(Math.toRadians(eulerDeg[1]));
			nx = x * cy + z * sy;
			nz = -x * sy + z * cy;
			v[0] = nx;
			v[1] = y;
			v[2] = nz;
		}

		private static double[] mul(float[] m, double[] v, double w) {
			return new double[] {
				m[0] * v[0] + m[1] * v[1] + m[2] * v[2] + m[3] * w,
				m[4] * v[0] + m[5] * v[1] + m[6] * v[2] + m[7] * w,
				m[8] * v[0] + m[9] * v[1] + m[10] * v[2] + m[11] * w};
		}

		private static Vec3 randomUnit() {
			double z = RANDOM.nextFloat() * 2 - 1, a = RANDOM.nextFloat() * Math.PI * 2, r = Math.sqrt(1 - z * z);
			return new Vec3(r * Math.cos(a), r * Math.sin(a), z);
		}

		void particleDied(int index) {
			alive[index]--;
		}
	}
}
