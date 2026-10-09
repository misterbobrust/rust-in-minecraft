package local.rustak.building;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import local.rustak.decor.DecorEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Collision against building blocks at any yaw. Minecraft only collides axis-aligned boxes, so moving entities are
 * clipped here against each block's rotated solid and continuous surfaces. Vertical movement resolves first;
 * horizontal sweeps retain movement along contact faces. The full entity rectangle is used in every direction,
 * and an entity already inside a solid may move out without moving deeper through its nearest face.
 */
public final class BuildingCollision {
	private static final double EPS = 1e-7, SNAP = 0.12;

	public static Vec3 clip(Entity e, Vec3 move) {
		if (e instanceof BuildingEntity || e instanceof DecorEntity || e instanceof DoorEntity || e.noPhysics || move.lengthSqr() == 0) return move;
		AABB box = e.getBoundingBox();
		List<Obb> boxes = solidsNear(e.level(), box.expandTowards(move).inflate(Math.max(Math.max(0.1, e.maxUpStep()), move.horizontalDistance())));
		if (boxes.isEmpty()) return move;
		return clip(box, move, e.onGround(), e.maxUpStep(), boxes, candidate -> e.level().noCollision(e, candidate));
	}

	public static Vec3 clip(AABB box, Vec3 move, boolean grounded, double stepHeight, List<Obb> boxes, Predicate<AABB> worldClear) {
		double h = box.maxY - box.minY, feet = box.minY;
		double mx = move.x, my = move.y, mz = move.z;
		if (grounded && my <= 0 && mx * mx + mz * mz > 1e-8 && supportedSlope(box, boxes)) {
			AABB destination = box.move(mx, 0, mz);
			double top = Double.NEGATIVE_INFINITY;
			for (Obb o : boxes) if (horizontalOverlap(o, destination)) {
				double y = o.topAt(destination);
				if (y <= feet + stepHeight + EPS && y >= feet - stepHeight - EPS) top = Math.max(top, y);
			}
			if (Double.isFinite(top) && clearWalk(box, destination.move(0, top - feet, 0), boxes, worldClear))
				return new Vec3(mx, top - feet, mz);
		}
		// A step raises a grounded walker only when both the vertical and horizontal path have room.
		if (grounded && my <= 0 && stepHeight > 0 && mx * mx + mz * mz > 1e-8) {
			double lift = 0;
			AABB destination = box.expandTowards(mx, 0, mz);
			for (Obb o : boxes) {
				double top = o.topAt(destination);
				if (top <= feet + EPS || top > feet + stepHeight + EPS || !horizontalOverlap(o, destination)) continue;
				lift = Math.max(lift, top - feet);
			}
			if (lift > 0 && clearRise(box, lift, stepHeight, boxes, worldClear)
					&& clear(box.move(0, lift, 0).expandTowards(mx, 0, mz).deflate(1e-5), boxes, worldClear)) {
				return new Vec3(mx, lift, mz);
			}
		}

		// Vertical movement uses the current footprint: a desired move may still be blocked by a wall.
		// A grounded foot may resolve a small overlap only with enough headroom.
		for (Obb o : boxes) {
			double top = o.topAt(box), bottom = o.bottomAt(box);
			boolean current = horizontalOverlap(o, box);
			if (my <= 0 && feet >= top - EPS && current) my = Math.max(my, top - feet);
			else if (my <= 0 && grounded && current && feet >= top - SNAP && clearRise(box, top - feet, stepHeight, boxes, worldClear)
					&& clear(box.move(0, top - feet, 0).deflate(1e-5), boxes, worldClear)) my = Math.max(my, top - feet);
			else if (current && my > 0 && feet + h <= bottom + EPS) my = Math.min(my, bottom - (feet + h));
		}
		Vec3 horizontal = slide(box.move(0, my, 0), new Vec3(mx, 0, mz), boxes, worldClear);
		return new Vec3(horizontal.x, my, horizontal.z);
	}

	private static Vec3 slide(AABB body, Vec3 movement, List<Obb> boxes, Predicate<AABB> worldClear) {
		Vec3 done = Vec3.ZERO, remaining = movement;
		for (int pass = 0; pass < 4 && remaining.lengthSqr() > 1e-16; pass++) {
			Obb.HorizontalContact first = null;
			for (Obb o : boxes) {
				var contact = o.horizontalContact(body, remaining);
				if (contact != null && (first == null || contact.fraction() < first.fraction())) first = contact;
			}
			if (first == null) return done.add(remaining);
			Vec3 travel = remaining.scale(first.fraction());
			done = done.add(travel); body = body.move(travel);
			remaining = remaining.scale(1 - first.fraction());
			double inward = remaining.dot(first.normal());
			if (inward < 0) remaining = worldMove(body, remaining.subtract(first.normal().scale(inward)), worldClear);
		}
		return done;
	}

	private static Vec3 worldMove(AABB body, Vec3 movement, Predicate<AABB> worldClear) {
		if (worldClear.test(body.minmax(body.move(movement)).deflate(EPS))) return movement;
		double low = 0, high = 1;
		for (int i = 0; i < 24; i++) {
			double middle = (low + high) / 2;
			if (worldClear.test(body.minmax(body.move(movement.scale(middle))).deflate(EPS))) low = middle;
			else high = middle;
		}
		return movement.scale(low);
	}

	private static boolean clear(AABB box, List<Obb> boxes, Predicate<AABB> worldClear) {
		for (Obb o : boxes) if (intersects(o, box)) return false;
		return worldClear.test(box);
	}

	private static boolean clearWalk(AABB from, AABB to, List<Obb> boxes, Predicate<AABB> worldClear) {
		AABB swept = from.minmax(to).deflate(1e-5);
		if (!worldClear.test(swept)) return false;
		for (Obb o : boxes) if (intersects(o, swept)) {
			// The supporting surface stays below the foot along this linear walk; other geometry still blocks it.
			if ((!horizontalOverlap(o, from) || o.topAt(from) <= from.minY + EPS)
					&& (!horizontalOverlap(o, to) || o.topAt(to) <= to.minY + EPS)
					&& o.topAt(swept) <= Math.max(from.minY, to.minY) + EPS) continue;
			return false;
		}
		return true;
	}

	static boolean supportedSlope(AABB box, List<Obb> boxes) {
		for (Obb o : boxes) if (o.sloped() && horizontalOverlap(o, box) && Math.abs(box.minY - o.topAt(box)) < 1e-4) return true;
		return false;
	}

	public static boolean supportedSlope(Level level, AABB box) {
		return supportedSlope(box, solidsNear(level, box.inflate(0.01)));
	}

	private static boolean clearRise(AABB box, double lift, double stepHeight, List<Obb> boxes, Predicate<AABB> worldClear) {
		AABB swept = box.expandTowards(0, lift, 0).deflate(1e-5);
		for (Obb o : boxes) if (intersects(o, swept)) {
			// A foot already touching the riser may move out of that shallow overlap by rising.
			if (o.center().y - o.ey() <= box.minY + EPS && o.center().y + o.ey() <= box.minY + stepHeight + EPS && intersects(o, box)) continue;
			return false;
		}
		return worldClear.test(swept);
	}

	private static boolean horizontalOverlap(Obb o, AABB box) {
		double hx = (box.maxX - box.minX) / 2, hz = (box.maxZ - box.minZ) / 2;
		double dx = (box.minX + box.maxX) / 2 - o.center().x, dz = (box.minZ + box.maxZ) / 2 - o.center().z;
		double[] c = o.toLocal(dx, dz);
		double cos = Math.abs(o.cos()), sin = Math.abs(o.sin());
		return Math.abs(dx) < hx + cos * o.ex() + sin * o.ez() - EPS
				&& Math.abs(dz) < hz + sin * o.ex() + cos * o.ez() - EPS
				&& Math.abs(c[0]) < o.ex() + cos * hx + sin * hz - EPS
				&& Math.abs(c[1]) < o.ez() + sin * hx + cos * hz - EPS;
	}

	/** Crouching may descend into a frame's enclosed opening while its outer edge remains protected. */
	public static boolean internalOpening(Level level, AABB box, Vec3 move) {
		AABB destination = box.move(move.x, 0, move.z);
		for (BuildingEntity b : blocksNear(level, box.expandTowards(move).inflate(0.3))) {
			if (Math.abs(box.minY - b.getY()) > 0.25) continue;
			if (internalOpening(b.def(), b.position(), b.yawRad(), b.solids(), destination)) return true;
		}
		return false;
	}

	static boolean internalOpening(BuildingDefs.Piece def, Vec3 pos, float yaw, List<Obb> solids, AABB destination) {
		if (!def.name.equals("floor.frame") && !def.name.equals("floor.triangle.frame")) return false;
		var frame = new Obb(pos, 0, 0, 0, yaw);
		for (double x : new double[] {destination.minX, destination.maxX}) for (double z : new double[] {destination.minZ, destination.maxZ}) {
			double[] point = frame.toLocal(x - pos.x, z - pos.z);
			for (int i = 0; i < def.footprint.length; i += 2) {
				int next = (i + 2) % def.footprint.length;
				double cross = (def.footprint[next] - def.footprint[i]) * (point[1] - def.footprint[i + 1])
					- (def.footprint[next + 1] - def.footprint[i + 1]) * (point[0] - def.footprint[i]);
				if (cross < -EPS) return false;
			}
		}
		for (Obb o : solids) if (horizontalOverlap(o, destination.deflate(1e-5))) return false;
		return true;
	}

	/** Whether any building block intersects the box (separating axes of the block; the box is axis-aligned). */
	public static boolean intersects(Level level, AABB box) {
		for (Obb o : solidsNear(level, box)) if (intersects(o, box)) return true;
		return false;
	}

	/** Whether the block's box and an axis-aligned box overlap (separating axes about Y). */
	public static boolean intersects(Obb o, AABB box) {
		if (o.center().y + o.ey() < box.minY || o.center().y - o.ey() > box.maxY) return false;
		return horizontalOverlap(o, box) && o.topAt(box) >= box.minY && o.bottomAt(box) <= box.maxY;
	}

	/** Collision boxes of building blocks and decor blocks (Decor) around. */
	public static List<Obb> solidsNear(Level level, AABB area) {
		List<Obb> out = new ArrayList<>();
		for (BuildingEntity b : blocksNear(level, area)) out.addAll(b.solids());
		for (DoorEntity d : level.getEntitiesOfClass(DoorEntity.class, area.inflate(1.5), d -> !d.isRemoved() && d.def() != null)) out.addAll(d.solids());
		for (DecorEntity d : level.getEntitiesOfClass(DecorEntity.class, area, d -> !d.isRemoved())) {
			Obb o = d.solid();
			if (o != null) out.add(o);
		}
		return out;
	}

	public static List<BuildingEntity> blocksNear(Level level, AABB area) {
		return level.getEntitiesOfClass(BuildingEntity.class, queryArea(area), b -> !b.isRemoved() && b.getBoundingBox().intersects(area));
	}

	static AABB queryArea(AABB area) {
		return area.inflate(SearchPadding.HORIZONTAL, SearchPadding.VERTICAL, SearchPadding.HORIZONTAL);
	}

	private static final class SearchPadding {
		static final double HORIZONTAL, VERTICAL;
		static {
			double horizontal = 0, vertical = 0;
			for (var def : BuildingDefs.ALL) {
				var c = def.collisionBoundsCenter;
				var e = def.collisionBoundsExtents;
				horizontal = Math.max(horizontal, Math.hypot(Math.abs(c.x) + e.x, Math.abs(c.z) + e.z));
				vertical = Math.max(vertical, Math.abs(c.y) + e.y);
			}
			// Sections index the entity's pivot. Include every possible pivot of geometry touching the query.
			HORIZONTAL = horizontal + 1e-4;
			VERTICAL = vertical + 1e-4;
		}
	}

	/** Nearest building block along a ray, or null. */
	public static Hit raycast(Level level, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 1e-6) return null;
		Vec3 dir = d.scale(1 / len);
		Hit best = null;
		for (BuildingEntity b : blocksNear(level, new AABB(from, to).inflate(0.5))) {
			for (Obb o : b.solids()) {
				double t = o.raycast(from, dir, len);
				if (t >= 0 && (best == null || t < best.distance)) best = new Hit(b, t, from.add(dir.scale(t)));
			}
		}
		return best;
	}

	/** The first door leaf along the segment, or null. */
	public static DoorHit raycastDoors(Level level, Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 1e-6) return null;
		Vec3 dir = d.scale(1 / len);
		DoorHit best = null;
		for (DoorEntity door : level.getEntitiesOfClass(DoorEntity.class, new AABB(from, to).inflate(2), e -> !e.isRemoved() && e.def() != null)) {
			for (Obb o : door.solids()) {
				double t = o.raycast(from, dir, len);
				if (t >= 0 && (best == null || t < best.distance)) best = new DoorHit(door, t, from.add(dir.scale(t)));
			}
		}
		return best;
	}

	public record DoorHit(DoorEntity door, double distance, Vec3 location) {
	}

	public record Hit(BuildingEntity block, double distance, Vec3 location) {
	}
}
