package local.rustak.client.building;

import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import local.rustak.building.RoofPlacement;
import local.rustak.building.PlacementRules;
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
	public record Placement(Vec3 pos, float yaw, boolean valid, int target) {
		public Placement(Vec3 pos, float yaw, boolean valid) { this(pos, yaw, valid, -1); }
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
		BuildingEntity blockedTarget = null;
		BuildingDefs.Socket blockedFemale = null;
		double blockedBest = Double.MAX_VALUE;
		double hiddenBest = Double.MAX_VALUE;
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(eye, eye).inflate(max + 4))) {
			Quaternionf bRot = new Quaternionf().rotateY(b.yawRad());
			for (BuildingDefs.Socket f : b.def().sockets) {
				if (!f.female || f.femaleDummy || def.sockets.stream().noneMatch(m -> m.male && !m.maleDummy && m.compatible(f))) continue;
				Vector3f fPos = bRot.transform(new Vector3f(f.pos)).add((float) b.getX(), (float) b.getY(), (float) b.getZ());
				Quaternionf fRot = new Quaternionf(bRot).mul(f.rot);
				Vector3f centre = fRot.transform(new Vector3f(f.selectCenter)).add(fPos);
				double t = rayBox(eye, rayDir, centre, fRot, f.selectSize, max);
				if (t < 0) continue;
				// of the select boxes the ray passes through, take the one whose centre is nearest the line of sight:
				// the nearest entry point would pick the raised-foundation box the player's eyes are standing in
				Vec3 c = new Vec3(centre.x, centre.y, centre.z);
				double along = Math.max(0, c.subtract(eye).dot(dir));
				double miss = eye.add(dir.scale(along)).distanceTo(c);
				if (def.name.equals("foundation.steps") && RoofPlacement.closedFoundationEdge(b, f)) {
					hiddenBest = Math.min(hiddenBest, miss);
					continue;
				}
				if (RoofPlacement.occupiedSocket(b, f)) continue;
				if (!free(p, def, b, f, fPos, placementRot(fRot, f), rayDir, userRotationDeg)) {
					// Keep an invalid exterior-step preview attached, so failed ground checks still show its true position.
					if (def.name.equals("foundation.steps") && f.type == 7 && miss < blockedBest) {
						blockedBest = miss;
						blockedTarget = b;
						blockedFemale = f;
					}
					continue;
				}
				if (miss < best) {
					best = miss;
					target = b;
					female = f;
				}
			}
		}
		if (hiddenBest < Double.MAX_VALUE && hiddenBest <= Math.min(best, blockedBest)) return null;
		if (target == null && blockedTarget != null) {
			target = blockedTarget;
			female = blockedFemale;
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
				// foundations join side by side (level, or a 1.5 m step up or down), never on top of one another (by the
				// middles of their outlines: a triangle's origin is on a side)
				Vec3 mid = centre(def, pl.pos(), pl.yaw()), targetMid = centre(target.def(), target.position(), target.yawRad());
				if (foundation && Math.hypot(mid.x - targetMid.x, mid.z - targetMid.z) < 1) continue;
				if (first == null) first = pl;
				if (!occupied(p, def, pl) && PlacementRules.socketAllowed(p.level(), male, pl.pos(), pl.yaw(), fRot, dir) && allowed(p, def, pl, target, fPos)) {
					return new Placement(pl.pos(), pl.yaw(), true, target.getId());
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
			// the terrain socket's own turn about Y counts (a triangle's points another way than a square's)
			Quaternionf rot = new Quaternionf().rotateY(yawFromForward(dir)).mul(new Quaternionf().rotateY(yawOf(terrain.rot)))
				.mul(new Quaternionf().rotateY(-(float) Math.toRadians(userRotationDeg)));
			Vector3f off = rot.transform(new Vector3f(terrain.pos));
			Vec3 pos = at.subtract(off.x, off.y, off.z);
			Placement pl = new Placement(pos, yawOf(rot), true);
			return new Placement(pos, pl.yaw(), terrainOk(p, def, pl) && !occupied(p, def, pl) && !overlapsBlocks(p, def, pl));
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
	 * The piece's own terrain check points: each must have ground within 3 m over it (the ones under a foundation's
	 * corners) or must not (the ones just over its top), as Rust checks them. Without exported points, a square
	 * foundation's: ground within 2.6 m below each corner of the top, none rising above it 1.4 m in from the edges.
	 */
	static boolean terrainOk(LocalPlayer p, BuildingDefs.Piece def, Placement pl) {
		if (!def.terrainChecks.isEmpty()) {
			Quaternionf rot = new Quaternionf().rotateY(pl.yaw());
			for (BuildingDefs.TerrainCheck c : def.terrainChecks) {
				Vector3f w = rot.transform(new Vector3f(c.pos()));
				double x = pl.pos().x + w.x, y = pl.pos().y + w.y, z = pl.pos().z + w.z;
				if (groundBetween(p, x, z, y, y + 3) != c.wantsGround()) return false;
			}
			return true;
		}
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
	private static boolean free(LocalPlayer p, BuildingDefs.Piece def, BuildingEntity target, BuildingDefs.Socket female, Vector3f fPos, Quaternionf fRot, Vector3f ray, float userDeg) {
		for (BuildingDefs.Socket male : def.sockets) {
			if (!male.male || male.maleDummy || !male.compatible(female)) continue;
			Placement pl = doPlacement(male, female, fPos, fRot, ray, userDeg);
			if (!occupied(p, def, pl) && PlacementRules.socketAllowed(p.level(), male, pl.pos(), pl.yaw(), fRot, new Vec3(ray.x, ray.y, ray.z)) && allowed(p, def, pl, target, fPos)) return true;
		}
		return false;
	}

	private static boolean allowed(LocalPlayer p, BuildingDefs.Piece def, Placement pl, BuildingEntity target, Vector3f point) {
		if (RoofPlacement.isRoof(def) ? !RoofPlacement.valid(p.level(), def, pl.pos(), pl.yaw()) : !PlacementRules.spatial(p.level(), def, pl.pos(), pl.yaw())) return false;
		if (checksOverlap(def) && def.placementChecks.isEmpty() && overlapsBlocks(p, def, pl)) return false;
		return PlacementRules.visible(p.level(), p.getEyePosition(), def, pl.pos(), pl.yaw(), target, new Vec3(point.x, point.y, point.z));
	}

	/** Foundations and floors (square or triangle) may not cut into other blocks; walls go on their edges. */
	static boolean checksOverlap(BuildingDefs.Piece def) {
		return def.sockets.stream().anyMatch(s -> s.terrain || s.male && s.type == FLOOR);
	}

	/** Rust's floor socket type. */
	private static final int FLOOR = 2;

	/**
	 * Rust's sockets are monogamous: a piece already standing where the new one would (same spot, sharing a kind of
	 * male socket: any wall, half wall, doorway or window wall in a wall slot) makes the spot taken.
	 */
	static boolean occupied(LocalPlayer p, BuildingDefs.Piece def, Placement pl) {
		if (RoofPlacement.isRoof(def)) {
			for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(pl.pos(), pl.pos()).inflate(4))) {
				if (b.piece() == def.index && b.position().distanceTo(pl.pos()) < 0.02 && sameYaw(b.yawRad(), pl.yaw())) return true;
			}
			return false;
		}
		// where it stands is the middle of its outline: a triangle's origin is on a side, so the same triangle put
		// down by another side would have another origin
		Vec3 at = centre(def, pl.pos(), pl.yaw());
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(at, at).inflate(2))) {
			if (centre(b.def(), b.position(), b.yawRad()).distanceTo(at) < 0.3 && sharesMale(b.def(), def)) return true;
		}
		return false;
	}

	private static boolean sameYaw(float a, float b) {
		return Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))) < Math.toRadians(2);
	}

	/** The middle of a piece's outline in the world. */
	static Vec3 centre(BuildingDefs.Piece def, Vec3 pos, float yaw) {
		double cs = Math.cos(yaw), sn = Math.sin(yaw);
		return pos.add(def.footprintX * cs + def.footprintZ * sn, 0, -def.footprintX * sn + def.footprintZ * cs);
	}

	private static boolean sharesMale(BuildingDefs.Piece a, BuildingDefs.Piece b) {
		if (a == b) return true;
		for (BuildingDefs.Socket x : a.sockets) {
			if (!x.male || x.maleDummy || x.terrain) continue;
			for (BuildingDefs.Socket y : b.sockets) if (y.male && !y.maleDummy && !y.terrain && x.type == y.type && x.type >= 0) return true;
		}
		return false;
	}

	/**
	 * A new foundation may not cut into another building block (Rust's deploy volume checks), compared by their
	 * collision boxes: two triangles meeting along a side make a rhombus whose bounds overlap, but not the triangles.
	 */
	static boolean overlapsBlocks(LocalPlayer p, BuildingDefs.Piece def, Placement pl) {
		Obb box = BuildingEntity.obbAt(def, pl.pos(), pl.yaw());
		// the new piece's outline drawn in a little: a wall or a floor along its side only touches it
		double[] outline = outline(def, pl.pos(), pl.yaw(), INSET);
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), box.bounds().inflate(0.5))) {
			double lo = Math.max(box.center().y - box.ey(), b.obb().center().y - b.obb().ey());
			double hi = Math.min(box.center().y + box.ey(), b.obb().center().y + b.obb().ey());
			if (hi - lo > 0.05 && polygonsOverlap(outline, outline(b.def(), b.position(), b.yawRad(), 0))) return true;
		}
		return false;
	}

	/** How far in the new piece's outline is drawn for the overlap test (walls stand 0.1-0.15 m into a floor's edge). */
	private static final double INSET = 0.25;

	/** A piece's outline in the world (x, z pairs), drawn in toward its middle by inset. */
	private static double[] outline(BuildingDefs.Piece def, Vec3 pos, float yaw, double inset) {
		float[] f = def.footprint;
		double k = def.footprintInradius > inset ? (def.footprintInradius - inset) / def.footprintInradius : 0.01;
		double cs = Math.cos(yaw), sn = Math.sin(yaw);
		double[] out = new double[f.length];
		for (int i = 0; i < f.length; i += 2) {
			double x = def.footprintX + (f[i] - def.footprintX) * k, z = def.footprintZ + (f[i + 1] - def.footprintZ) * k;
			out[i] = pos.x + x * cs + z * sn;
			out[i + 1] = pos.z - x * sn + z * cs;
		}
		return out;
	}

	/** Separating-axis test for two convex outlines: true when they share some area. */
	private static boolean polygonsOverlap(double[] a, double[] b) {
		return !separatedBy(a, b) && !separatedBy(b, a);
	}

	private static boolean separatedBy(double[] edges, double[] other) {
		int n = edges.length / 2;
		for (int i = 0; i < n; i++) {
			double ex = edges[2 * ((i + 1) % n)] - edges[2 * i], ez = edges[2 * ((i + 1) % n) + 1] - edges[2 * i + 1];
			double nx = -ez, nz = ex;
			double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
			for (int k = 0; k < edges.length; k += 2) {
				double d = edges[k] * nx + edges[k + 1] * nz;
				minA = Math.min(minA, d);
				maxA = Math.max(maxA, d);
			}
			for (int k = 0; k < other.length; k += 2) {
				double d = other[k] * nx + other[k + 1] * nz;
				minB = Math.min(minB, d);
				maxB = Math.max(maxB, d);
			}
			if (maxA <= minB || maxB <= minA) return true;
		}
		return false;
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
