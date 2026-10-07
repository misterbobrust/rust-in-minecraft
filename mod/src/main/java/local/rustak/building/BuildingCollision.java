package local.rustak.building;

import java.util.ArrayList;
import java.util.List;

import local.rustak.decor.DecorEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Collision against building blocks at any yaw. Minecraft only collides axis-aligned boxes, so moving entities are
 * clipped here against each block's rotated box: the entity is treated as an upright cylinder of its half width,
 * which stays an axis-aligned square in the block's own frame. Vertical first, then the block's local X and Z; an
 * entity already inside a block may only move out of it.
 */
public final class BuildingCollision {
	private static final double EPS = 1e-7, SNAP = 0.02, FIT = 0.5, STEP = 0.05;

	public static Vec3 clip(Entity e, Vec3 move) {
		if (e instanceof BuildingEntity || e instanceof DecorEntity || e instanceof DoorEntity || e.noPhysics || move.lengthSqr() == 0) return move;
		AABB box = e.getBoundingBox();
		List<Obb> boxes = solidsNear(e.level(), box.expandTowards(move).inflate(0.1));
		if (boxes.isEmpty()) return move;
		double r = e.getBbWidth() / 2, h = e.getBbHeight();
		double px = (box.minX + box.maxX) / 2, pz = (box.minZ + box.maxZ) / 2, feet = box.minY;
		double mx = move.x, my = move.y, mz = move.z;

		// standing on a block counts within SNAP of its top: neighbouring foundations' tops differ by rounding, and a
		// block a hair higher would otherwise be a wall at the feet. Falling onto one lifts the feet to its top.
		for (Obb o : boxes) {
			double[] c = o.toLocal(px - o.center().x, pz - o.center().z);
			if (Math.abs(c[0]) >= o.ex() + r - EPS || Math.abs(c[1]) >= o.ez() + r - EPS) continue;
			double top = o.center().y + o.ey(), bottom = o.center().y - o.ey();
			if (my <= 0 && feet >= top - SNAP) my = Math.max(my, top - feet);
			else if (my > 0 && feet + h <= bottom + EPS) my = Math.min(my, bottom - (feet + h));
		}
		feet += my;

		// two passes: in a corner each wall's clip must hold after the other's slide
		for (int pass = 0; pass < 2; pass++) {
			for (Obb o : boxes) {
				double top = o.center().y + o.ey(), bottom = o.center().y - o.ey();
				if (feet >= top - SNAP || feet + h <= bottom + EPS) continue;
				double[] c = o.toLocal(px - o.center().x, pz - o.center().z);
				double[] l = o.toLocal(mx, mz);
				double lx = l[0], lz = l[1];
				double penX = o.ex() + r - Math.abs(c[0]), penZ = o.ez() + r - Math.abs(c[1]);
				if (penX > EPS && penZ > EPS) {
					// already inside (pushed in, or spawned there): no moving deeper through the nearest face, out is free
					if (penX <= penZ) {
						if (lx * c[0] < 0) lx = 0;
					} else if (lz * c[1] < 0) {
						lz = 0;
					}
				} else {
					if (Math.abs(c[1]) < o.ez() + r - EPS) {
						if (lx > 0 && c[0] + r <= -o.ex() + EPS) lx = Math.min(lx, -o.ex() - (c[0] + r));
						else if (lx < 0 && c[0] - r >= o.ex() - EPS) lx = Math.max(lx, o.ex() - (c[0] - r));
					}
					double cx = c[0] + lx;
					if (Math.abs(cx) < o.ex() + r - EPS) {
						if (lz > 0 && c[1] + r <= -o.ez() + EPS) lz = Math.min(lz, -o.ez() - (c[1] + r));
						else if (lz < 0 && c[1] - r >= o.ez() - EPS) lz = Math.max(lz, o.ez() - (c[1] - r));
					}
				}
				double[] w = o.toWorld(lx, lz);
				mx = w[0];
				mz = w[1];
			}
		}
		// in the air, a player stopped by a block may slip up or down a little into an opening that fits (a window
		// reached roughly at the right height): the nearest free height within FIT, keeping the full horizontal move
		double wanted = move.x * move.x + move.z * move.z;
		if (e instanceof Player && !e.onGround() && wanted > 1e-6 && mx * mx + mz * mz < wanted * 0.25) {
			for (double d = STEP; d <= FIT + 1e-9; d += STEP) {
				for (double dy : new double[] {-d, d}) {
					AABB moved = box.move(move.x, my + dy, move.z).deflate(1e-4);
					if (!intersects(e.level(), moved) && e.level().noCollision(e, moved)) return new Vec3(move.x, my + dy, move.z);
				}
			}
		}
		return new Vec3(mx, my, mz);
	}

	/** Whether any building block intersects the box (separating axes of the block; the box is axis-aligned). */
	public static boolean intersects(Level level, AABB box) {
		for (Obb o : solidsNear(level, box)) if (intersects(o, box)) return true;
		return false;
	}

	/** Whether the block's box and an axis-aligned box overlap (separating axes about Y). */
	public static boolean intersects(Obb o, AABB box) {
		if (o.center().y + o.ey() < box.minY || o.center().y - o.ey() > box.maxY) return false;
		double hx = (box.maxX - box.minX) / 2, hz = (box.maxZ - box.minZ) / 2;
		double[] c = o.toLocal((box.minX + box.maxX) / 2 - o.center().x, (box.minZ + box.maxZ) / 2 - o.center().z);
		double cos = Math.abs(o.cos()), sin = Math.abs(o.sin());
		return Math.abs(c[0]) < o.ex() + cos * hx + sin * hz && Math.abs(c[1]) < o.ez() + sin * hx + cos * hz;
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
		return level.getEntitiesOfClass(BuildingEntity.class, area, b -> !b.isRemoved());
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
