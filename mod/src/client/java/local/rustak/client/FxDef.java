package local.rustak.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import local.rustak.RustAk;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/** A Rust particle effect: Shuriken systems with Unity's curves and gradients. */
final class FxDef {
	final List<Sys> systems = new ArrayList<>();

	static FxDef load(String name) {
		try (var in = Minecraft.getInstance().getResourceManager().open(RustAk.id("fx/" + name + ".json"))) {
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			FxDef def = new FxDef();
			for (var s : root.getAsJsonArray("systems")) def.systems.add(new Sys(s.getAsJsonObject()));
			return def;
		} catch (Exception e) {
			throw new RuntimeException("rustak: can't load fx/" + name + ".json", e);
		}
	}

	/** Unity MinMaxCurve: 0 constant, 1 curve, 2 random between two curves, 3 random between two constants. */
	static final class Curve {
		final int mode;
		final float scalar, min;
		final float[][] max, minC; // keys: time, value, inSlope, outSlope

		Curve(JsonObject o) {
			mode = o.get("mode").getAsInt();
			scalar = o.get("scalar").getAsFloat();
			min = o.get("min").getAsFloat();
			max = keys(o.getAsJsonArray("max_curve"));
			minC = keys(o.getAsJsonArray("min_curve"));
		}

		static Curve of(JsonObject parent, String key) {
			return parent.has(key) ? new Curve(parent.getAsJsonObject(key)) : null;
		}

		private static float[][] keys(JsonArray a) {
			float[][] k = new float[a.size()][];
			for (int i = 0; i < k.length; i++) {
				JsonArray e = a.get(i).getAsJsonArray();
				k[i] = new float[] {e.get(0).getAsFloat(), e.get(1).getAsFloat(), e.get(2).getAsFloat(), e.get(3).getAsFloat()};
			}
			return k;
		}

		static float hermite(float[][] k, float t) {
			if (k.length == 0) return 1;
			if (t <= k[0][0]) return k[0][1];
			for (int i = 0; i < k.length - 1; i++) {
				float[] a = k[i], b = k[i + 1];
				if (t <= b[0]) {
					float dt = b[0] - a[0];
					if (dt <= 0) return b[1];
					if (Float.isInfinite(a[3]) || Float.isInfinite(b[2])) return a[1]; // stepped key
					float u = (t - a[0]) / dt, u2 = u * u, u3 = u2 * u;
					return (2 * u3 - 3 * u2 + 1) * a[1] + (u3 - 2 * u2 + u) * dt * a[3] + (-2 * u3 + 3 * u2) * b[1] + (u3 - u2) * dt * b[2];
				}
			}
			return k[k.length - 1][1];
		}

		float eval(float t, float rnd) {
			return switch (mode) {
				case 1 -> scalar * hermite(max, t);
				case 2 -> scalar * Mth.lerp(rnd, hermite(minC, t), hermite(max, t));
				case 3 -> Mth.lerp(rnd, min, scalar);
				default -> scalar;
			};
		}
	}

	/** Unity Gradient: colour and alpha keys, linear between them. */
	static final class Grad {
		final float[][] colors, alphas;

		Grad(JsonObject o) {
			JsonArray c = o.getAsJsonArray("colors"), a = o.getAsJsonArray("alphas");
			colors = new float[c.size()][4];
			alphas = new float[a.size()][2];
			for (int i = 0; i < colors.length; i++) for (int k = 0; k < 4; k++) colors[i][k] = c.get(i).getAsJsonArray().get(k).getAsFloat();
			for (int i = 0; i < alphas.length; i++) for (int k = 0; k < 2; k++) alphas[i][k] = a.get(i).getAsJsonArray().get(k).getAsFloat();
		}

		void eval(float t, float[] out) {
			lerpKeys(colors, t, out, 0, 3);
			float[] a = new float[1];
			lerpKeys(alphas, t, a, 0, 1);
			out[3] = a[0];
		}

		private static void lerpKeys(float[][] keys, float t, float[] out, int o, int n) {
			if (keys.length == 0) {
				for (int k = 0; k < n; k++) out[o + k] = 1;
				return;
			}
			float[] a = keys[0], b = keys[keys.length - 1];
			if (t <= a[0]) b = a;
			else for (int i = 0; i < keys.length - 1; i++) {
				if (t <= keys[i + 1][0]) {
					a = keys[i];
					b = keys[i + 1];
					break;
				}
			}
			float f = b[0] > a[0] ? Mth.clamp((t - a[0]) / (b[0] - a[0]), 0, 1) : 0;
			if (t > keys[keys.length - 1][0]) a = b;
			for (int k = 0; k < n; k++) out[o + k] = Mth.lerp(f, a[1 + k], b[1 + k]);
		}
	}

	/** Unity MinMaxGradient: 0 colour, 1 gradient, 2 two colours, 3 two gradients, 4 random colour. */
	static final class Colors {
		final int mode;
		final float[] maxColor = new float[4], minColor = new float[4];
		final Grad max, min;

		Colors(JsonObject o) {
			mode = o.get("mode").getAsInt();
			for (int k = 0; k < 4; k++) {
				maxColor[k] = o.getAsJsonArray("max_color").get(k).getAsFloat();
				minColor[k] = o.getAsJsonArray("min_color").get(k).getAsFloat();
			}
			max = new Grad(o.getAsJsonObject("max_gradient"));
			min = new Grad(o.getAsJsonObject("min_gradient"));
		}

		static Colors of(JsonObject parent, String key) {
			return parent.has(key) ? new Colors(parent.getAsJsonObject(key)) : null;
		}

		void eval(float t, float rnd, float[] out) {
			switch (mode) {
				case 1 -> max.eval(t, out);
				case 2 -> {
					for (int k = 0; k < 4; k++) out[k] = Mth.lerp(rnd, minColor[k], maxColor[k]);
				}
				case 3 -> {
					float[] a = new float[4];
					min.eval(t, a);
					max.eval(t, out);
					for (int k = 0; k < 4; k++) out[k] = Mth.lerp(rnd, a[k], out[k]);
				}
				case 4 -> max.eval(rnd, out);
				default -> System.arraycopy(maxColor, 0, out, 0, 4);
			}
		}
	}

	static final class Burst {
		final float time, interval, probability;
		final int cycles;
		final Curve count;

		Burst(JsonObject o) {
			time = o.get("time").getAsFloat();
			interval = o.get("interval").getAsFloat();
			probability = o.get("probability").getAsFloat();
			cycles = o.get("cycles").getAsInt();
			count = new Curve(o.getAsJsonObject("count"));
		}
	}

	/** One ParticleSystem with the modules the mod plays. */
	static final class Sys {
		final String name, sprite;
		final float[] matrix = new float[16]; // row-major, system -> effect space (Unity)
		final float duration, simSpeed, maxSize;
		final boolean looping, worldSpace, emissive, emissionFade, randomFrame, legacyTint;
		final int maxParticles, frames, scalingMode;
		final float[] tint = new float[4];
		final Curve startDelay, lifetime, speed, size, rotation, gravity, rate, rateDistance, sizeOverLife, rotationOverLife;
		final Colors color, colorOverLife;
		final List<Burst> bursts = new ArrayList<>();
		// shape
		final boolean shapeOn;
		final int shapeType;
		final float angle, radius, thickness, arc, length, randomDir, sphericalDir;
		final float[] shapePos = new float[3], shapeRot = new float[3], shapeScale = new float[3];
		// texture sheet
		final Curve frameOverTime, startFrame;
		final float cycles;
		// velocity / force / limit
		final Curve[] velocity, force;
		final boolean velocityWorld, forceWorld;
		final Curve limit, drag;
		final float dampen;

		Sys(JsonObject o) {
			name = o.get("name").getAsString();
			sprite = o.get("sprite").getAsString();
			JsonArray m = o.getAsJsonArray("matrix");
			for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) matrix[r * 4 + c] = m.get(r).getAsJsonArray().get(c).getAsFloat();
			duration = Math.max(0.01f, o.get("duration").getAsFloat());
			simSpeed = o.get("sim_speed").getAsFloat();
			looping = o.get("looping").getAsBoolean();
			worldSpace = o.get("world_space").getAsBoolean();
			emissive = o.get("emissive").getAsBoolean();
			emissionFade = o.has("emission_fade") && o.get("emission_fade").getAsBoolean();
			randomFrame = o.has("random_frame") && o.get("random_frame").getAsBoolean();
			maxParticles = o.get("max_particles").getAsInt();
			frames = o.get("frames").getAsInt();
			scalingMode = o.get("scaling_mode").getAsInt();
			maxSize = o.get("max_size").getAsFloat();
			JsonArray t = o.getAsJsonArray("tint");
			for (int k = 0; k < 4; k++) tint[k] = t.get(k).getAsFloat();
			legacyTint = tint[3] < 0.99f || tint[0] < 0.99f || tint[1] < 0.99f || tint[2] < 0.99f;
			startDelay = new Curve(o.getAsJsonObject("start_delay"));
			lifetime = new Curve(o.getAsJsonObject("lifetime"));
			speed = new Curve(o.getAsJsonObject("speed"));
			size = new Curve(o.getAsJsonObject("size"));
			rotation = new Curve(o.getAsJsonObject("rotation"));
			gravity = new Curve(o.getAsJsonObject("gravity"));
			rate = new Curve(o.getAsJsonObject("rate"));
			rateDistance = new Curve(o.getAsJsonObject("rate_distance"));
			sizeOverLife = Curve.of(o, "size_over_life");
			rotationOverLife = Curve.of(o, "rotation_over_life");
			color = new Colors(o.getAsJsonObject("color"));
			colorOverLife = Colors.of(o, "color_over_life");
			for (var b : o.getAsJsonArray("bursts")) bursts.add(new Burst(b.getAsJsonObject()));
			JsonObject sh = o.getAsJsonObject("shape");
			shapeOn = sh.get("enabled").getAsBoolean();
			shapeType = sh.get("type").getAsInt();
			angle = sh.get("angle").getAsFloat();
			radius = sh.get("radius").getAsFloat();
			thickness = sh.get("radius_thickness").getAsFloat();
			arc = sh.get("arc").getAsFloat();
			length = sh.get("length").getAsFloat();
			randomDir = sh.get("random_dir").getAsFloat();
			sphericalDir = sh.get("spherical_dir").getAsFloat();
			for (int k = 0; k < 3; k++) {
				shapePos[k] = sh.getAsJsonArray("position").get(k).getAsFloat();
				shapeRot[k] = sh.getAsJsonArray("rotation").get(k).getAsFloat();
				shapeScale[k] = sh.getAsJsonArray("scale").get(k).getAsFloat();
			}
			JsonObject uv = o.has("uv") ? o.getAsJsonObject("uv") : null;
			frameOverTime = uv == null ? null : new Curve(uv.getAsJsonObject("frame"));
			startFrame = uv == null ? null : new Curve(uv.getAsJsonObject("start_frame"));
			cycles = uv == null ? 1 : uv.get("cycles").getAsFloat();
			velocity = axes(o, "velocity");
			force = axes(o, "force");
			velocityWorld = o.has("velocity") && o.getAsJsonObject("velocity").get("world").getAsBoolean();
			forceWorld = o.has("force") && o.getAsJsonObject("force").get("world").getAsBoolean();
			JsonObject lim = o.has("limit") ? o.getAsJsonObject("limit") : null;
			limit = lim == null ? null : new Curve(lim.getAsJsonObject("magnitude"));
			drag = lim == null ? null : new Curve(lim.getAsJsonObject("drag"));
			dampen = lim == null ? 0 : lim.get("dampen").getAsFloat();
		}

		private static Curve[] axes(JsonObject o, String key) {
			if (!o.has(key)) return null;
			JsonObject v = o.getAsJsonObject(key);
			return new Curve[] {new Curve(v.getAsJsonObject("x")), new Curve(v.getAsJsonObject("y")), new Curve(v.getAsJsonObject("z"))};
		}

		/** Uniform scale of the system's transform (used when scalingMode is Hierarchy). */
		float matrixScale() {
			float sx = (float) Math.sqrt(matrix[0] * matrix[0] + matrix[4] * matrix[4] + matrix[8] * matrix[8]);
			float sy = (float) Math.sqrt(matrix[1] * matrix[1] + matrix[5] * matrix[5] + matrix[9] * matrix[9]);
			float sz = (float) Math.sqrt(matrix[2] * matrix[2] + matrix[6] * matrix[6] + matrix[10] * matrix[10]);
			return (sx + sy + sz) / 3;
		}

		static float rand(RandomSource r) {
			return r.nextFloat();
		}
	}
}
