package local.rustak.client.building;

import local.rustak.client.MeshOut;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import local.rustak.RustAk;
import local.rustak.building.Building;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import local.rustak.client.FxManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Debris uses the current model's visible fragments, source colliders and material contact properties. */
public final class GibSystem {
	private static final Map<String, List<Piece>> SOURCES = new HashMap<>();
	private static final List<Gib> GIBS = new ArrayList<>();
	private static final Set<UUID> SPAWNED = new LinkedHashSet<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static final float DT = .05f;
	private static Object lastLevel;
	private static int vertices;

	record Piece(Identifier texture, Vector3f pos, Quaternionf rot, Vector3f size, Vector3f center,
		Vector3f sourceCenter, float friction, float bounce, float explodeScale, boolean important, boolean spawnFx, float[] v) { }

	static final class Gib {
		final Piece piece;
		Vec3 pos, prev, vel;
		final Quaternionf rot, prevRot = new Quaternionf();
		final Vector3f spin;
		final float variation;
		float age, prevAge, settledAt = -1;
		boolean sleeping;

		Gib(Piece piece, Vec3 pos, Quaternionf rot, Vec3 vel, Vector3f spin) {
			this.piece = piece;
			this.pos = this.prev = pos;
			this.rot = rot;
			this.prevRot.set(rot);
			this.vel = vel;
			this.spin = spin;
			this.variation = RANDOM.nextFloat();
		}

		float scale(float atAge) {
			return GibDynamics.scale(atAge, GibDynamics.cleanupStart(GibDynamics.lifetime(variation), settledAt, variation),
				GibDynamics.cleanupDuration(variation));
		}
	}

	private static List<Piece> source(String key) {
		return SOURCES.computeIfAbsent(key, k -> {
			List<Piece> out = new ArrayList<>();
			JsonArray settings = new JsonArray();
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(RustAk.id("models/build/gibs/" + k + ".json"))) {
				settings = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
			} catch (Exception ignored) { } // Older local asset packs use mesh bounds and material defaults.
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(RustAk.id("models/build/gibs/" + k + ".bin"))) {
				ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				int n = b.getInt();
				for (int i = 0; i < n; i++) {
					byte[] name = new byte[b.getInt()];
					b.get(name);
					Vector3f pos = new Vector3f(b.getFloat(), b.getFloat(), -b.getFloat());
					float qx = b.getFloat(), qy = b.getFloat(), qz = b.getFloat(), qw = b.getFloat();
					Quaternionf rot = new Quaternionf(-qx, -qy, qz, qw).normalize();
					Vector3f size = new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
					float[] v = new float[b.getInt() * 8];
					b.asFloatBuffer().get(v);
					b.position(b.position() + v.length * 4);
					Vector3f min = new Vector3f(Float.POSITIVE_INFINITY), max = new Vector3f(Float.NEGATIVE_INFINITY);
					for (int k2 = 0; k2 < v.length; k2 += 8) {
						v[k2 + 2] = -v[k2 + 2];
						v[k2 + 7] = -v[k2 + 7];
						var p = new Vector3f(v[k2], v[k2 + 1], v[k2 + 2]);
						min.min(p); max.max(p);
					}
					var data = i < settings.size() ? settings.get(i).getAsJsonObject() : new JsonObject();
					Vector3f center = vector(data, "collider_center", new Vector3f(min).add(max).mul(.5f));
					out.add(new Piece(RustAk.id("textures/build/" + new String(name, StandardCharsets.UTF_8) + ".png"), pos, rot,
						size, center, vector(data, "source_center", new Vector3f()), number(data, "friction", .3f),
						number(data, "bounce", 0), number(data, "explode_scale", 0),
						data.has("important") && data.get("important").getAsBoolean(),
						!data.has("spawn_fx") || data.get("spawn_fx").getAsBoolean(), v));
				}
			} catch (Exception ignored) { out.clear(); }
			return out;
		});
	}

	private static float number(JsonObject data, String key, float fallback) {
		return data.has(key) ? data.get(key).getAsFloat() : fallback;
	}

	private static Vector3f vector(JsonObject data, String key, Vector3f fallback) {
		if (!data.has(key)) return fallback;
		var v = data.getAsJsonArray(key);
		return new Vector3f(v.get(0).getAsFloat(), v.get(1).getAsFloat(), -v.get(2).getAsFloat());
	}

	private static void level(Minecraft mc) {
		if (lastLevel == mc.level) return;
		lastLevel = mc.level;
		GIBS.clear(); SPAWNED.clear(); vertices = 0;
	}

	public static void spawn(BuildingEntity b) {
		Minecraft mc = Minecraft.getInstance();
		level(mc);
		if (mc.level == null || !SPAWNED.add(b.getUUID())) return;
		if (SPAWNED.size() > 512) SPAWNED.remove(SPAWNED.iterator().next());
		Quaternionf yaw = new Quaternionf().rotateY(b.yawRad());
		String fx = "gib_" + Building.GIB[b.grade()];
		Vec3 origin = b.position();
		String key = b.def().name + "_" + BuildingDefs.GRADES[b.grade()];
		List<Piece> pieces;
		var sections = b.def().grades[b.grade()].sections;
		if (sections.isEmpty()) pieces = source(key);
		else {
			pieces = new ArrayList<>();
			long mask = local.rustak.building.RoofShape.mask(b);
			for (int i = 0; i < sections.size(); i++) if (sections.get(i).visible && (mask & (1L << i)) != 0)
				pieces.addAll(source(key + "_section" + i));
		}
		int count = Math.min(pieces.size(), Math.max(0, GibDynamics.MAX_PIECES - GIBS.size()));
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			Piece p = pieces.get(GibDynamics.sampleIndex(i, pieces.size(), count));
			int cost = p.v().length / 8;
			if (vertices + cost > GibDynamics.MAX_VERTICES) continue;
			Vector3f lp = yaw.transform(new Vector3f(p.pos()));
			Vec3 at = origin.add(lp.x, lp.y, lp.z);
			Vector3f out = new Vector3f(p.pos()).sub(p.sourceCenter());
			if (out.lengthSquared() > 1e-8) out.normalize(p.explodeScale()); else out.zero();
			yaw.transform(out);
			// Unpowered demolition falls apart; it does not launch every material upward at the same speed.
			Vec3 vel = new Vec3(out.x + (RANDOM.nextFloat() - .5) * .3, out.y, out.z + (RANDOM.nextFloat() - .5) * .3);
			Vector3f spin = new Vector3f(RANDOM.nextFloat() - .5f, RANDOM.nextFloat() - .5f, RANDOM.nextFloat() - .5f);
			if (spin.lengthSquared() > 1e-8) spin.normalize(.5f + RANDOM.nextFloat());
			GIBS.add(new Gib(p, at, new Quaternionf(yaw).mul(p.rot()), vel, spin));
			vertices += cost;
			if (p.spawnFx() && spawned++ < 12) FxManager.burst(fx, at, new Vec3(0, 1, 0), 1, null);
		}
		if (pieces.isEmpty()) FxManager.burst(fx, b.obb().center(), new Vec3(0, 1, 0), 6, b.obb());
	}

	/** A rotating fragment keeps its actual collider centre and three dimensions. */
	private static AABB bounds(Gib g, float scale, Quaternionf rotation, Vec3 position) {
		Vector3f c = rotation.transform(new Vector3f(g.piece.center()));
		Vector3f x = rotation.transform(new Vector3f(g.piece.size().x / 2, 0, 0));
		Vector3f y = rotation.transform(new Vector3f(0, g.piece.size().y / 2, 0));
		Vector3f z = rotation.transform(new Vector3f(0, 0, g.piece.size().z / 2));
		double ex = Math.max(.002, (Math.abs(x.x) + Math.abs(y.x) + Math.abs(z.x)) * scale);
		double ey = Math.max(.002, (Math.abs(x.y) + Math.abs(y.y) + Math.abs(z.y)) * scale);
		double ez = Math.max(.002, (Math.abs(x.z) + Math.abs(y.z) + Math.abs(z.z)) * scale);
		double bottom = c.y - Math.abs(x.y) - Math.abs(y.y) - Math.abs(z.y);
		Vec3 centre = position.add(c.x * scale, c.y * scale + (1 - scale) * bottom, c.z * scale);
		return new AABB(centre.x - ex, centre.y - ey, centre.z - ez, centre.x + ex, centre.y + ey, centre.z + ez);
	}

	static Vec3 clipBuildings(AABB box, Vec3 move, List<Obb> nearby) {
		return BuildingCollision.clip(box, move, false, 0, nearby, candidate -> true);
	}

	private static Vec3 collide(Minecraft mc, AABB box, Vec3 move) {
		Vec3 nativeMove = Entity.collideBoundingBox(null, move, box, mc.level, List.of());
		var nearby = BuildingCollision.solidsNear(mc.level, box.expandTowards(nativeMove).inflate(Math.max(.02, nativeMove.horizontalDistance())));
		return BuildingCollision.clip(box, nativeMove, false, 0, nearby, candidate -> mc.level.noCollision(candidate));
	}

	public static void tick(Minecraft mc) {
		level(mc);
		if (mc.level == null || mc.isPaused()) return;
		for (Iterator<Gib> it = GIBS.iterator(); it.hasNext(); ) {
			Gib g = it.next();
			g.prev = g.pos; g.prevRot.set(g.rot); g.prevAge = g.age;
			g.age += DT;
			float scale = g.scale(g.age);
			if (scale <= .001f) { vertices -= g.piece.v().length / 8; it.remove(); continue; }
			AABB box = bounds(g, scale, g.rot, g.pos);
			Vec3 probe = new Vec3(0, -.02, 0);
			if (g.sleeping) {
				Vec3 down = collide(mc, box, probe);
				if (down.y > probe.y + 1e-6) continue;
				g.sleeping = false;
			}
			g.vel = g.vel.scale(Math.exp(-.1 * DT)).add(0, -GibDynamics.GRAVITY * DT, 0);
			Vec3 move = g.vel.scale(DT);
			Vec3 done = collide(mc, box, move);
			double vx = g.vel.x, vy = g.vel.y, vz = g.vel.z;
			boolean ground = move.y < 0 && done.y > move.y + 1e-6;
			if (Math.abs(done.y - move.y) > 1e-6) vy = -vy * g.piece.bounce();
			if (Math.abs(done.x - move.x) > 1e-6) vx = -vx * g.piece.bounce();
			if (Math.abs(done.z - move.z) > 1e-6) vz = -vz * g.piece.bounce();
			if (ground) {
				double speed = Math.hypot(vx, vz), next = GibDynamics.frictionSpeed(speed, g.piece.friction(), DT);
				if (speed > 0) { vx *= next / speed; vz *= next / speed; }
				g.spin.mul(.45f);
			}
			g.vel = new Vec3(vx, vy, vz);
			g.pos = g.pos.add(done);
			float angle = g.spin.length() * DT;
			if (angle > 1e-4f) {
				Quaternionf nextRot = new Quaternionf(g.rot).premul(new Quaternionf().rotateAxis(angle, new Vector3f(g.spin).normalize()));
				AABB rotated = bounds(g, scale, nextRot, g.pos).deflate(1e-5);
				if (mc.level.noCollision(rotated) && !BuildingCollision.intersects(mc.level, rotated)) g.rot.set(nextRot);
				else g.spin.zero();
			}
			if (ground && g.vel.lengthSqr() < .0025 && g.spin.lengthSquared() < .01) {
				g.sleeping = true; g.vel = Vec3.ZERO; g.spin.zero();
				if (g.settledAt < 0) g.settledAt = g.age;
			}
		}
	}

	public static void render(PoseStack pose, OrderedSubmitNodeCollector queue, float pt) {
		if (GIBS.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		for (Gib g : GIBS) {
			Vec3 p = g.prev.lerp(g.pos, pt);
			float scale = g.scale(g.prevAge + (g.age - g.prevAge) * pt);
			Quaternionf rotation = new Quaternionf(g.prevRot).slerp(g.rot, pt);
			AABB box = bounds(g, 1, rotation, p);
			double bottom = box.minY - p.y;
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p));
			pose.pushPose();
			pose.translate(p.x - cam.x, p.y - cam.y + (1 - scale) * bottom, p.z - cam.z);
			pose.mulPose(rotation); pose.scale(scale, scale, scale);
			float[] v = g.piece.v();
			var type = MeshOut.solid(g.piece.texture());
			boolean q = MeshOut.quads(type);
			queue.submitCustomGeometry(pose, type, (ps, vc) -> MeshOut.flat(vc, ps, v, -1, light, q));
			pose.popPose();
		}
	}
}
