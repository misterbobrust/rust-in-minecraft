package local.rustak.client.building;

import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rust's Planner placement on the client: find the female socket the player aims at (ray against each socket's
 * select box), then the new piece is placed by its male socket.
 * All in Minecraft's mirrored space, where Unity's Euler(0, a, 0) becomes rotateY(-a).
 */
public final class BuildingPlacement {
	public record Placement(Vec3 pos, float yaw, boolean valid) {
	}

	/** partialTick interpolates the eye and view so the ghost follows the camera every frame, not every tick. */
	public static Placement compute(LocalPlayer p, int pieceIndex, float userRotationDeg, float partialTick) {
		BuildingDefs.Piece def = BuildingDefs.piece(pieceIndex);
		Vec3 eye = p.getEyePosition(partialTick);
		Vec3 dir = p.getViewVector(partialTick);
		float max = def.maxDistance;
		Vector3f rayDir = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);

		// 1. the nearest compatible female socket the ray passes through
		BuildingEntity target = null;
		BuildingDefs.Socket female = null;
		double best = Double.MAX_VALUE;
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(eye, eye).inflate(max + 4))) {
			Quaternionf bRot = new Quaternionf().rotateY(b.yawRad());
			for (BuildingDefs.Socket f : b.def().sockets) {
				if (!f.female || f.femaleDummy || def.sockets.stream().noneMatch(m -> m.male && !m.maleDummy && m.compatible(f))) continue;
				Vector3f fPos = bRot.transform(new Vector3f(f.pos)).add((float) b.getX(), (float) b.getY(), (float) b.getZ());
				Quaternionf fRot = new Quaternionf(bRot).mul(f.rot);
				Vector3f centre = fRot.transform(new Vector3f(f.selectCenter)).add(fPos);
				double t = rayBox(eye, rayDir, centre, fRot, f.selectSize, max);
				if (t < 0) continue;
				// Rust's sockets are monogamous: one already holding a piece of this kind isn't offered
				if (!free(p, def, f, fPos, placementRot(fRot, f), rayDir, userRotationDeg)) continue;
				// of the select boxes the ray passes through, take the one whose centre is nearest the line of sight:
				// the nearest entry point would pick the raised-foundation box the player's eyes are standing in
				Vec3 c = new Vec3(centre.x, centre.y, centre.z);
				double along = Math.max(0, c.subtract(eye).dot(dir));
				double miss = eye.add(dir.scale(along)).distanceTo(c);
				if (miss < best) {
					best = miss;
					target = b;
					female = f;
				}
			}
		}
		if (target != null) {
			Quaternionf bRot = new Quaternionf().rotateY(target.yawRad());
			Vector3f fPos = bRot.transform(new Vector3f(female.pos)).add((float) target.getX(), (float) target.getY(), (float) target.getZ());
			Quaternionf fRot = new Quaternionf(bRot).mul(female.rot);
			fRot = placementRot(fRot, female);
			Placement first = null;
			boolean foundation = def.sockets.stream().anyMatch(s -> s.terrain);
			for (BuildingDefs.Socket male : def.sockets) {
				if (!male.male || male.maleDummy || !male.compatible(female)) continue;
				Placement pl = doPlacement(male, female, fPos, fRot, rayDir, userRotationDeg);
				// foundations join side by side (level, or a 1.5 m step up or down), never on top of one another
				if (foundation && Math.hypot(pl.pos().x - target.getX(), pl.pos().z - target.getZ()) < 1) continue;
				if (first == null) first = pl;
				if (!occupied(p, def, pl)) {
					return foundation ? new Placement(pl.pos(), pl.yaw(), !overlapsBlocks(p, def, pl)) : pl;
				}
			}
			if (first != null) return new Placement(first.pos(), first.yaw(), false);
		}

		// 2. on the ground, for foundations
		BuildingDefs.Socket terrain = def.sockets.stream().filter(s -> s.terrain).findFirst().orElse(null);
		Vec3 end = eye.add(dir.scale(max));
		float lookYaw = yawFromForward(dir);
		if (terrain != null) {
			HitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			// aiming past the ground puts the foundation at reach: that's how Rust raises foundations
			Vec3 at = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : end;
			// LookRotation(flat look) * Euler(0, socket yaw) * Euler(user rotation), position = hit - rot * socket position
			Quaternionf rot = new Quaternionf().rotateY(yawFromForward(dir)).mul(new Quaternionf().rotateY(-(float) Math.toRadians(userRotationDeg)));
			Vector3f off = rot.transform(new Vector3f(terrain.pos));
			Vec3 pos = at.subtract(off.x, off.y, off.z);
			Placement pl = new Placement(pos, yawOf(rot), true);
			return new Placement(pos, pl.yaw(), terrainOk(p, pl) && !occupied(p, def, pl) && !overlapsBlocks(p, def, pl));
		}
		// 3. nothing to attach to: a red ghost at reach, like Rust
		return new Placement(end, lookYaw, false);
	}

	/** The female socket's world rotation: sockets that are both male and female face the other way. */
	private static Quaternionf placementRot(Quaternionf fRot, BuildingDefs.Socket female) {
		Quaternionf q = new Quaternionf(fRot);
		if (female.male && female.female) q.mul(new Quaternionf().rotateX((float) Math.PI).rotateZ((float) Math.PI));
		return q;
	}

	/** Places a male socket of the new piece on a female socket in the world. */
	static Placement doPlacement(BuildingDefs.Socket male, BuildingDefs.Socket female, Vector3f fPos, Quaternionf fRot, Vector3f ray, float userDeg) {
		Quaternionf base = new Quaternionf(fRot).mul(new Quaternionf(male.rot).invert());
		Quaternionf rot;
		if (male.rotationDegrees > 0 && !female.restrictRotation) {
			// pick the rotation step whose turned female "up" is closest to the look ray
			float bestAngle = Float.MAX_VALUE, pick = 0;
			for (int i = 0; i < 360; i += male.rotationDegrees) {
				Quaternionf q = new Quaternionf().rotateY(-(float) Math.toRadians(male.rotationOffset + i)).mul(fRot);
				Vector3f up = q.transform(new Vector3f(0, 1, 0));
				float a = up.angle(ray);
				if (a < bestAngle) {
					bestAngle = a;
					pick = i;
				}
			}
			rot = new Quaternionf().rotateY(-(float) Math.toRadians(userDeg))
				.mul(new Quaternionf().rotateY(-(float) Math.toRadians(male.rotationOffset + pick))).mul(base);
		} else {
			rot = base;
		}
		Vector3f off = rot.transform(new Vector3f(male.pos));
		return new Placement(new Vec3(fPos.x - off.x, fPos.y - off.y, fPos.z - off.z), yawOf(rot), true);
	}

	/**
	 * The foundation's terrain check points: under each corner there must be ground within 2.6 m below
	 * the top, and around the top no ground may rise above it (1.4 m in from the edges).
	 */
	static boolean terrainOk(LocalPlayer p, Placement pl) {
		Obb o = new Obb(pl.pos(), 1.5, 0, 1.5, pl.yaw());
		double top = pl.pos().y;
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
			double[] w = o.toWorld(sx * 1.5, sz * 1.5);
			if (!groundBetween(p, pl.pos().x + w[0], pl.pos().z + w[1], top - 2.6, top + 0.4)) return false;
		}
		for (int sx = -1; sx <= 1; sx++) for (int sz = -1; sz <= 1; sz++) {
			double[] w = o.toWorld(sx * 1.4, sz * 1.4);
			if (groundBetween(p, pl.pos().x + w[0], pl.pos().z + w[1], top + 0.1, top + 3.1)) return false;
		}
		return true;
	}

	/** Rust's IsInTerrain: a downward ray finds a ground surface between the two heights. */
	private static boolean groundBetween(LocalPlayer p, double x, double z, double low, double high) {
		HitResult h = p.level().clip(new ClipContext(new Vec3(x, high, z), new Vec3(x, low, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		return h.getType() == HitResult.Type.BLOCK;
	}

	/**
	 * Whether some compatible male socket of the new piece can go on this female socket without doubling up. A
	 * foundation step that would cut into a neighbouring foundation isn't offered either, so aiming over a foundation
	 * doesn't show a red one 1.5 m up inside it.
	 */
	private static boolean free(LocalPlayer p, BuildingDefs.Piece def, BuildingDefs.Socket female, Vector3f fPos, Quaternionf fRot, Vector3f ray, float userDeg) {
		boolean foundation = def.sockets.stream().anyMatch(s -> s.terrain);
		for (BuildingDefs.Socket male : def.sockets) {
			if (!male.male || male.maleDummy || !male.compatible(female)) continue;
			Placement pl = doPlacement(male, female, fPos, fRot, ray, userDeg);
			if (!occupied(p, def, pl) && !(foundation && overlapsBlocks(p, def, pl))) return true;
		}
		return false;
	}

	/**
	 * Rust's sockets are monogamous: a piece already standing where the new one would (same spot, sharing a kind of
	 * male socket: any wall, half wall, doorway or window wall in a wall slot) makes the spot taken.
	 */
	static boolean occupied(LocalPlayer p, BuildingDefs.Piece def, Placement pl) {
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(pl.pos(), pl.pos()).inflate(0.5))) {
			if (b.position().distanceTo(pl.pos()) < 0.3 && sharesMale(b.def(), def)) return true;
		}
		return false;
	}

	private static boolean sharesMale(BuildingDefs.Piece a, BuildingDefs.Piece b) {
		if (a == b) return true;
		for (BuildingDefs.Socket x : a.sockets) {
			if (!x.male || x.maleDummy || x.terrain) continue;
			for (BuildingDefs.Socket y : b.sockets) if (y.male && !y.maleDummy && !y.terrain && x.type == y.type && x.type >= 0) return true;
		}
		return false;
	}

	/** A new foundation may not cut into another building block (Rust's DeployVolume checks). */
	static boolean overlapsBlocks(LocalPlayer p, BuildingDefs.Piece def, Placement pl) {
		Obb box = BuildingEntity.obbAt(def, pl.pos(), pl.yaw());
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), box.bounds())) {
			if (separated(box, b.obb())) continue;
			return true;
		}
		return false;
	}

	/** Separating-axis test for two yaw-only boxes, shrunk a little so touching faces don't count. */
	private static boolean separated(Obb a, Obb b) {
		if (a.center().y + a.ey() - 0.05 <= b.center().y - b.ey() || b.center().y + b.ey() - 0.05 <= a.center().y - a.ey()) return true;
		double[][] axes = {{a.cos(), -a.sin()}, {a.sin(), a.cos()}, {b.cos(), -b.sin()}, {b.sin(), b.cos()}};
		for (double[] ax : axes) {
			double pa = project(a, ax), pb = project(b, ax);
			double dc = Math.abs((b.center().x - a.center().x) * ax[0] + (b.center().z - a.center().z) * ax[1]);
			if (dc >= pa + pb - 0.05) return true;
		}
		return false;
	}

	private static double project(Obb o, double[] ax) {
		double[] x = o.toWorld(1, 0), z = o.toWorld(0, 1);
		return o.ex() * Math.abs(x[0] * ax[0] + x[1] * ax[1]) + o.ez() * Math.abs(z[0] * ax[0] + z[1] * ax[1]);
	}

	/** Our yaw convention: world = Ry(yaw) * local with Ry(x, z) = (x cos + z sin, -x sin + z cos). */
	static float yawOf(Quaternionf q) {
		Vector3f x = q.transform(new Vector3f(1, 0, 0));
		return (float) Math.atan2(-x.z, x.x);
	}

	/** rotateY angle that turns Rust's local forward toward the flat look direction. Mirrored, forward is -Z. */
	static float yawFromForward(Vec3 dir) {
		return (float) Math.atan2(-dir.x, -dir.z);
	}

	/** Ray against a box of any rotation (socket select bounds): slab test in the box frame. */
	static double rayBox(Vec3 from, Vector3f dir, Vector3f centre, Quaternionf rot, Vector3f size, double max) {
		Quaternionf inv = new Quaternionf(rot).invert();
		Vector3f o = inv.transform(new Vector3f((float) from.x - centre.x, (float) from.y - centre.y, (float) from.z - centre.z));
		Vector3f d = inv.transform(new Vector3f(dir));
		float[] ro = {o.x, o.y, o.z}, rd = {d.x, d.y, d.z}, h = {size.x / 2 + 0.02f, size.y / 2 + 0.02f, size.z / 2 + 0.02f};
		double tMin = 0, tMax = max;
		for (int i = 0; i < 3; i++) {
			if (Math.abs(rd[i]) < 1e-7) {
				if (Math.abs(ro[i]) > h[i]) return -1;
				continue;
			}
			double t1 = (-h[i] - ro[i]) / rd[i], t2 = (h[i] - ro[i]) / rd[i];
			tMin = Math.max(tMin, Math.min(t1, t2));
			tMax = Math.min(tMax, Math.max(t1, t2));
			if (tMin > tMax) return -1;
		}
		return tMin;
	}
}
