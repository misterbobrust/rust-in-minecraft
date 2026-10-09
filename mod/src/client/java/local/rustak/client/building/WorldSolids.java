package local.rustak.client.building;

import java.util.ArrayList;
import java.util.List;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import local.rustak.building.BuildingCollision;
import local.rustak.building.Obb;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The building blocks, doors and decor around the camera, gathered once a tick for what the world does many times a
 * frame: rain and snow stopping on roofs, particles landing on floors, and how muffled a sound is between inside and
 * outside. Client thread only.
 */
public final class WorldSolids {
	private static final double RANGE = 48, NO_ROOF = Double.NEGATIVE_INFINITY;
	private static final Vec3 DOWN = new Vec3(0, -1, 0);
	private static Level level;
	private static long tick = Long.MIN_VALUE;
	private static List<Obb> solids = List.of();
	private static List<AABB> bounds = List.of();
	private static final Long2DoubleOpenHashMap ROOFS = new Long2DoubleOpenHashMap();

	private WorldSolids() {
	}

	private static void refresh(Level l) {
		if (l == level && l.getGameTime() == tick) return;
		level = l;
		tick = l.getGameTime();
		ROOFS.clear();
		Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		solids = BuildingCollision.solidsNear(l, new AABB(cam, cam).inflate(RANGE));
		bounds = new ArrayList<>(solids.size());
		for (Obb o : solids) bounds.add(o.bounds());
	}

	/** Whether any of them takes in part of the box. */
	public static boolean blocked(Level l, AABB box) {
		refresh(l);
		for (int i = 0; i < solids.size(); i++) if (bounds.get(i).intersects(box) && BuildingCollision.intersects(solids.get(i), box)) return true;
		return false;
	}

	/** The top of the highest one over the middle of a block column, or -infinity. */
	public static double roof(Level l, int x, int z) {
		refresh(l);
		long key = BlockPos.asLong(x, 0, z);
		if (ROOFS.containsKey(key)) return ROOFS.get(key);
		double px = x + 0.5, pz = z + 0.5, top = NO_ROOF;
		for (int i = 0; i < solids.size(); i++) {
			AABB b = bounds.get(i);
			if (px < b.minX || px > b.maxX || pz < b.minZ || pz > b.maxZ || b.maxY <= top) continue;
			Vec3 from = new Vec3(px, b.maxY + 0.01, pz);
			double t = solids.get(i).raycast(from, DOWN, b.maxY - b.minY + 0.02);
			if (t >= 0) top = Math.max(top, from.y - t);
		}
		ROOFS.put(key, top);
		return top;
	}

	/** A roof top as the whole block height weather stops at: the block it's in, unless it's well up that block. */
	public static int roofBlock(Level l, int x, int z) {
		double top = roof(l, x, z);
		if (top == NO_ROOF) return Integer.MIN_VALUE;
		int floor = (int) Math.floor(top);
		return top - floor < 0.25 ? floor : floor + 1;
	}

	/** How much a sound at pos is muffled for the listener, 0..1: one inside a building and the other out, or walls between. */
	public static float muffle(Level l, Vec3 listener, Vec3 pos) {
		refresh(l);
		if (solids.isEmpty() || listener.distanceToSqr(pos) < 1) return 0;
		pos = clearOf(l, pos);
		float a = indoor(l, listener), b = indoor(l, pos);
		float m = Math.abs(a - b);
		BuildingCollision.Hit wall = BuildingCollision.raycast(l, listener, pos);
		if (wall != null && wall.distance() < listener.distanceTo(pos) - 0.3) m = Math.max(m, 0.6f);
		return Math.min(1, m);
	}

	/**
	 * A sound made on a floor (a footstep, something dropped) sits on or just inside its surface: taken there it would
	 * count as under a roof and behind the floor. It is heard from just above whatever solid it's in.
	 */
	private static Vec3 clearOf(Level l, Vec3 p) {
		for (int i = 0; i < 4; i++) {
			Vec3 q = p.add(0, i * 0.35, 0);
			if (!blocked(l, new AABB(q, q).inflate(0.05))) return q;
		}
		return p;
	}

		/** 0 under the open sky, 1 in a closed room (BuildLight's sky view). */
	private static float indoor(Level l, Vec3 p) {
		return Math.clamp((1 - BuildLight.skyFactor(l, p)) / 0.75f, 0, 1);
	}
}
