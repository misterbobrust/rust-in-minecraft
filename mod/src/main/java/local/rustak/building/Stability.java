package local.rustak.building;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rust's building stability. Foundations are grounded (100%). Every other block leans on what its
 * male sockets are joined to: each male socket adds its supporter's stability times the socket's support factor
 * (a wall's base 0.7, each floor edge 0.25, a wall's side stability sockets 0.1 from the walls next to it), the
 * supporter counted as it would stand without this block; the sum is clamped to 1. Of several blocks on one socket
 * the one nearest the ground (fewest blocks down) is the supporter. A block under 5% gets ten more checks to find
 * support, then collapses. Changes ripple out through a work queue: a block whose value moved has the blocks it
 * holds up (on its female sockets) checked again.
 */
public final class Stability {
	public static final float COLLAPSE = 0.05f;
	private static final float ACCURACY = 0.001f;
	private static final int STRIKES = 10, PER_TICK = 64;
	private static final int FAR = Integer.MAX_VALUE;
	private static final Map<ServerLevel, LinkedHashSet<BuildingEntity>> QUEUE = new WeakHashMap<>();
	// blocks under the threshold, rechecked a few ticks apart (Rust spreads its queue over frames): the strikes give
	// the blocks around a change time to settle before anything falls
	private static final Map<BuildingEntity, Long> RETRY = new WeakHashMap<>();
	private static final int STRIKE_TICKS = 1;

	private record WorldSocket(BuildingDefs.Socket socket, Vector3f pos, Vector3f forward) {
	}

	private record Connection(BuildingEntity entity, boolean noStability) {
	}

	private record Support(float factor, List<Connection> connections) {
	}

	private Stability() {
	}

	/** Queues the block for a stability check. */
	public static void update(BuildingEntity b) {
		if (b.level() instanceof ServerLevel level) QUEUE.computeIfAbsent(level, k -> new LinkedHashSet<>()).add(b);
	}

	/** Something in this box changed (a block went): checks every block around it. */
	public static void neighboursChanged(ServerLevel level, AABB bounds) {
		for (BuildingEntity b : BuildingCollision.blocksNear(level, bounds.inflate(1))) update(b);
	}

	public static void tick(ServerLevel level) {
		long now = level.getGameTime();
		RETRY.entrySet().removeIf(e -> {
			if (e.getKey().level() != level || e.getValue() > now) return false;
			update(e.getKey());
			return true;
		});
		LinkedHashSet<BuildingEntity> q = QUEUE.get(level);
		for (int i = 0; q != null && i < PER_TICK && !q.isEmpty(); i++) {
			Iterator<BuildingEntity> it = q.iterator();
			BuildingEntity b = it.next();
			it.remove();
			check(level, b);
		}
	}

	private static boolean grounded(BuildingEntity b) {
		return b.def().sockets.stream().anyMatch(s -> s.terrain);
	}

	private static List<WorldSocket> sockets(BuildingEntity b) {
		Quaternionf rot = new Quaternionf().rotationY(b.yawRad());
		List<WorldSocket> out = new ArrayList<>();
		for (BuildingDefs.Socket s : b.def().sockets) {
			if (s.terrain) continue;
			Vector3f p = rot.transform(new Vector3f(s.pos)).add((float) b.getX(), (float) b.getY(), (float) b.getZ());
			Vector3f f = new Quaternionf(rot).mul(s.rot).transform(new Vector3f(0, 0, 1));
			out.add(new WorldSocket(s, p, f));
		}
		return out;
	}

	/** Socket_Base.CanConnect: complementary sockets of one kind at one spot (construction sockets also facing alike). */
	private static boolean connects(WorldSocket a, WorldSocket b) {
		BuildingDefs.Socket x = a.socket, y = b.socket;
		boolean stabilityX = x.cls.equals("StabilitySocket"), stabilityY = y.cls.equals("StabilitySocket");
		if (stabilityX != stabilityY || (!x.male && !y.male) || (!x.female && !y.female)) return false;
		if (stabilityX) return a.pos.distance(b.pos) < 0.3f; // their select boxes overlap
		if (x.type <= 0 || x.type != y.type || a.pos.distance(b.pos) > 0.06f) return false;
		float angle = a.forward.angle(b.forward);
		if (x.male && x.female || y.male && y.female) angle = Math.min(angle, (float) Math.PI - angle);
		return angle < Math.toRadians(5);
	}

	/** The block's male sockets and what is joined to each. */
	private static List<Support> supports(BuildingEntity b) {
		List<WorldSocket> mine = sockets(b);
		List<BuildingEntity> near = BuildingCollision.blocksNear(b.level(), b.obb().bounds().inflate(0.5));
		List<List<WorldSocket>> theirs = new ArrayList<>();
		for (BuildingEntity o : near) theirs.add(o == b ? List.of() : sockets(o));
		List<Support> out = new ArrayList<>();
		for (WorldSocket m : mine) {
			if (!m.socket.male) continue;
			List<Connection> conns = new ArrayList<>();
			for (int i = 0; i < near.size(); i++) for (WorldSocket t : theirs.get(i)) {
				if (connects(m, t)) conns.add(new Connection(near.get(i), t.socket.femaleNoStability));
			}
			out.add(new Support(m.socket.support, conns));
		}
		return out;
	}

	/** The supporting block: of the blocks on the socket, the one nearest the ground. */
	private static BuildingEntity supporter(Support s, BuildingEntity owner, BuildingEntity ignore) {
		BuildingEntity best = null;
		for (Connection c : s.connections) {
			BuildingEntity e = c.entity;
			if (e == owner || e == ignore || e.isRemoved() || c.noStability) continue;
			if (best == null || e.distanceFromGround() < best.distanceFromGround()) best = e;
		}
		return best;
	}

	/** CachedSupportValue: how well e would stand without ignore, from its supporters' cached stability. */
	private static float cachedSupport(BuildingEntity e, BuildingEntity ignore) {
		if (grounded(e)) return 1;
		float sum = 0;
		for (Support s : supports(e)) {
			BuildingEntity x = supporter(s, e, ignore);
			if (x != null) sum += x.stability() * s.factor;
		}
		return Math.clamp(sum, 0, 1);
	}

	private static int cachedDistance(BuildingEntity e, BuildingEntity ignore) {
		if (grounded(e)) return 1;
		int best = FAR;
		for (Support s : supports(e)) {
			BuildingEntity x = supporter(s, e, ignore);
			if (x != null && x.distanceFromGround() != FAR) best = Math.min(best, x.distanceFromGround() + 1);
		}
		return best;
	}

	/** One stability check. */
	private static void check(ServerLevel level, BuildingEntity b) {
		if (b.isRemoved()) return;
		int dist = 1;
		float stability = 1;
		if (!grounded(b)) {
			dist = FAR;
			float sum = 0;
			for (Support s : supports(b)) {
				BuildingEntity x = supporter(s, b, b);
				if (x == null) continue;
				int d = cachedDistance(x, b);
				if (d != FAR) dist = Math.min(dist, d + 1);
				sum += cachedSupport(x, b) * s.factor;
			}
			stability = Math.clamp(sum, 0, 1);
		}
		boolean changed = false;
		if (dist != b.distanceFromGround()) {
			b.setDistanceFromGround(dist);
			changed = true;
		}
		if (Math.abs(b.stability() - stability) > ACCURACY) {
			b.setStability(stability);
			changed = true;
		}
		if (changed) {
			held(b);
			update(b);
		}
		if (stability < COLLAPSE) {
			// nothing holds it at all: it falls at once (Rust's 0% block goes the moment it's placed)
			if (stability <= 0 && !b.settling) {
				Building.destroy(level, b);
				return;
			}
			if (b.strikes < STRIKES) {
				b.strikes++;
				RETRY.put(b, level.getGameTime() + STRIKE_TICKS);
				return;
			}
			// a block saved before stability existed is only rated, never felled, on its first checks (Rust never
			// rechecks on load; its neighbours may not have loaded yet)
			if (b.settling) return;
			Building.destroy(level, b);
		} else {
			b.strikes = 0;
		}
		if (!changed && b.strikes == 0) b.settling = false;
	}

	/** UpdateConnectedEntities: queues the blocks joined to this one's female sockets (those it holds up). */
	private static void held(BuildingEntity b) {
		List<WorldSocket> mine = sockets(b);
		for (BuildingEntity o : BuildingCollision.blocksNear(b.level(), b.obb().bounds().inflate(0.5))) {
			if (o == b) continue;
			search:
			for (WorldSocket t : sockets(o)) for (WorldSocket m : mine) {
				if (m.socket.female && connects(m, t)) {
					update(o);
					break search;
				}
			}
		}
	}
}
