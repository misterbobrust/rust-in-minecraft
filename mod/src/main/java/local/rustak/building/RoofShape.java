package local.rustak.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Selects building sections from links to neighbouring pieces, on both server and client. */
public final class RoofShape {
	record Link(int grade, float yaw, String piece, String socket) {}
	private record Cache(long tick, int grade, Vec3 pos, float yaw, long mask) {}
	private static final Map<BuildingEntity, Cache> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
	public record Candidate(BuildingDefs.Piece def, Vec3 pos, float yaw, int grade) {}

	public static long mask(BuildingEntity b) {
		if (b.def().grades[b.grade()].sections.isEmpty()) return 0;
		Cache c = CACHE.get(b);
		long tick = b.level().getGameTime();
		if (c != null && c.tick == tick && c.grade == b.grade() && c.pos.equals(b.position()) && c.yaw == b.yawRad()) return c.mask;
		long mask = mask(b.level(), b.def(), b.position(), b.yawRad(), b.grade(), b);
		CACHE.put(b, new Cache(tick, b.grade(), b.position(), b.yawRad(), mask));
		return mask;
	}

	public static long mask(Level level, BuildingDefs.Piece def, Vec3 pos, float yaw, int grade, BuildingEntity ignore) {
		return mask(level, def, pos, yaw, grade, ignore, null);
	}

	public static long mask(Level level, BuildingDefs.Piece def, Vec3 pos, float yaw, int grade, BuildingEntity ignore, Candidate added) {
		if (def.grades[grade].sections.isEmpty()) return 0;
		List<Candidate> nearby = new ArrayList<>();
		for (BuildingEntity b : BuildingCollision.blocksNear(level, BuildingEntity.obbAt(def, pos, yaw).bounds().inflate(1))) {
			if (b != ignore) nearby.add(new Candidate(b.def(), b.position(), b.yawRad(), b.grade()));
		}
		if (added != null) nearby.add(added);
		return layoutMask(def, pos, yaw, grade, nearby);
	}

	static long layoutMask(BuildingDefs.Piece def, Vec3 pos, float yaw, int grade, List<Candidate> nearby) {
		List<BuildingDefs.Section> sections = def.grades[grade].sections;
		if (sections.isEmpty()) return 0;
		Map<String, List<Link>> links = new HashMap<>();
		Quaternionf rot = new Quaternionf().rotationY(yaw);
		for (BuildingDefs.Socket s : def.sockets) {
			if (s.terrain) continue;
			List<Link> connected = new ArrayList<>();
			Vector3f at = world(s.pos, rot, pos);
			Quaternionf sr = new Quaternionf(rot).mul(s.rot);
			for (Candidate b : nearby) {
				addLinks(connected, s, at, sr, b.def, b.pos, b.yaw, b.grade);
			}
			links.put(s.name.substring(s.name.indexOf("/sockets/") + 9), connected);
		}
		long mask = 0;
		for (int i = 0; i < sections.size(); i++) if (tests(sections.get(i).tests, links, grade, yaw)) mask |= 1L << i;
		return mask;
	}

	private static void addLinks(List<Link> connected, BuildingDefs.Socket s, Vector3f at, Quaternionf sr,
			BuildingDefs.Piece def, Vec3 pos, float yaw, int grade) {
		Quaternionf br = new Quaternionf().rotationY(yaw);
		for (BuildingDefs.Socket t : def.sockets) {
			if (!s.cls.equals(t.cls) || (!s.male && !t.male) || (!s.female && !t.female)) continue;
			Vector3f bt = world(t.pos, br, pos);
			Quaternionf tr = new Quaternionf(br).mul(t.rot);
			boolean joins;
			if (s.neighbour || s.type == -2) {
				Vector3f sc = sr.transform(new Vector3f(s.selectCenter)).add(at);
				Vector3f tc = tr.transform(new Vector3f(t.selectCenter)).add(bt);
				joins = boxesOverlap(sc, sr, s.selectSize, tc, tr, t.selectSize);
			} else {
				joins = s.compatible(t) && at.distance(bt) <= 0.02f;
				if (joins) {
					float angle = sr.transform(new Vector3f(0, 0, 1)).angle(tr.transform(new Vector3f(0, 0, 1)));
					if (s.male && s.female || t.male && t.female) angle = Math.min(angle, (float) Math.PI - angle);
					joins = angle <= Math.toRadians(2);
				}
			}
			if (joins) connected.add(new Link(grade, yaw, def.name, t.name));
		}
	}

	private static Vector3f world(Vector3f local, Quaternionf rot, Vec3 pos) {
		return rot.transform(new Vector3f(local)).add((float) pos.x, (float) pos.y, (float) pos.z);
	}

	static boolean connected(Candidate a, Candidate b) {
		Quaternionf ar = new Quaternionf().rotationY(a.yaw);
		for (var s : a.def.sockets) {
			if (s.terrain) continue;
			List<Link> links = new ArrayList<>();
			addLinks(links, s, world(s.pos, ar, a.pos), new Quaternionf(ar).mul(s.rot), b.def, b.pos, b.yaw, b.grade);
			if (!links.isEmpty()) return true;
		}
		return false;
	}

	/** Matching corner links permit the intentional shared seam of adjacent slopes. */
	public static boolean joint(Candidate a, Candidate b) {
		Quaternionf ar = new Quaternionf().rotationY(a.yaw), br = new Quaternionf().rotationY(b.yaw);
		for (BuildingDefs.Socket s : a.def.sockets) {
			if (!s.neighbour) continue;
			for (BuildingDefs.Socket t : b.def.sockets) {
				if (!t.neighbour) continue;
				String sn = s.name.substring(s.name.lastIndexOf('/') + 1), tn = t.name.substring(t.name.lastIndexOf('/') + 1);
				boolean pair = sn.equals("3") && (tn.equals("4") || tn.equals("5")) || sn.equals("4") && (tn.equals("3") || tn.equals("6"))
					|| sn.equals("5") && tn.equals("3") || sn.equals("6") && tn.equals("4");
				if (!pair) continue;
				Quaternionf sq = new Quaternionf(ar).mul(s.rot), tq = new Quaternionf(br).mul(t.rot);
				Vector3f sc = sq.transform(new Vector3f(s.selectCenter)).add(world(s.pos, ar, a.pos));
				Vector3f tc = tq.transform(new Vector3f(t.selectCenter)).add(world(t.pos, br, b.pos));
				if (boxesOverlap(sc, sq, s.selectSize, tc, tq, t.selectSize)) return true;
			}
		}
		return false;
	}

	/** Full separating-axis test for oriented selection and placement boxes. */
	static boolean boxesOverlap(Vector3f a, Quaternionf ar, Vector3f as, Vector3f b, Quaternionf br, Vector3f bs) {
		Vector3f[] ax = axes(ar), bx = axes(br);
		Vector3f d = new Vector3f(b).sub(a);
		List<Vector3f> axes = new ArrayList<>();
		for (Vector3f v : ax) axes.add(v);
		for (Vector3f v : bx) axes.add(v);
		for (Vector3f x : ax) for (Vector3f y : bx) axes.add(new Vector3f(x).cross(y));
		for (Vector3f v : axes) {
			if (v.lengthSquared() < 1e-10f) continue;
			float ra = 0, rb = 0;
			for (int i = 0; i < 3; i++) {
				ra += Math.abs(v.dot(ax[i])) * as.get(i) / 2;
				rb += Math.abs(v.dot(bx[i])) * bs.get(i) / 2;
			}
			if (Math.abs(v.dot(d)) > ra + rb + 1e-6f) return false;
		}
		return true;
	}

	private static Vector3f[] axes(Quaternionf q) {
		return new Vector3f[] {q.transform(new Vector3f(1, 0, 0)), q.transform(new Vector3f(0, 1, 0)), q.transform(new Vector3f(0, 0, 1))};
	}

	private static boolean linked(Map<String, List<Link>> links, int from, int to) {
		return links.getOrDefault("neighbour/" + from, List.of()).stream().anyMatch(l -> l.socket.endsWith("/neighbour/" + to));
	}

	static boolean tests(JsonArray tests, Map<String, List<Link>> links, int grade, float yaw) {
		for (var value : tests) if (!test(value.getAsJsonObject(), links, grade, yaw)) return false;
		return true;
	}

	private static boolean test(JsonObject t, Map<String, List<Link>> links, int grade, float yaw) {
		return switch (t.get("kind").getAsString()) {
			case "constant" -> t.get("value").getAsBoolean();
			case "all" -> tests(t.getAsJsonArray("tests"), links, grade, yaw);
			case "not" -> !tests(t.getAsJsonArray("tests"), links, grade, yaw);
			case "empty_wall" -> links.getOrDefault("wall-female", List.of()).isEmpty();
			case "top" -> links.containsKey("neighbour/5") && links.containsKey("neighbour/6") && !(linked(links, 5, 3) && linked(links, 6, 4));
			case "bottom" -> links.containsKey("neighbour/3") && links.containsKey("neighbour/4") && !(linked(links, 3, 5) && linked(links, 4, 6));
			case "left", "right" -> side(t, links, grade, yaw);
			case "wall_cut_left" -> wallCut(links, yaw, false);
			case "wall_cut_right" -> wallCut(links, yaw, true);
			case "wall_full" -> !wallCut(links, yaw, false) && !wallCut(links, yaw, true);
			case "wall_corner_left" -> wallCorner(links, grade, yaw, false);
			case "wall_corner_right" -> wallCorner(links, grade, yaw, true);
			default -> throw new IllegalArgumentException("Unknown building condition " + t);
		};
	}

	private static double degrees(float a, float b) {
		double d = Math.toDegrees(a - b);
		return ((d + 180) % 360 + 360) % 360 - 180;
	}

	private static boolean wallCut(Map<String, List<Link>> links, float yaw, boolean right) {
		for (String socket : new String[] {"wall-female", "floor-female/1", "floor-female/2", "floor-female/3", "floor-female/4", "stability/" + (right ? 2 : 1)}) {
			if (!links.getOrDefault(socket, List.of()).isEmpty()) return false;
		}
		for (Link l : links.getOrDefault("neighbour/1", List.of())) {
			double angle = Math.abs(degrees(yaw, l.yaw + (right ? (float) Math.PI : 0)));
			if (l.piece.equals("roof") && angle < 10 || l.piece.equals("roof.triangle") && angle < 40) return true;
		}
		return false;
	}

	private static boolean wallCorner(Map<String, List<Link>> links, int grade, float yaw, boolean right) {
		String socket = "stability/" + (right ? 1 : 2);
		boolean result = false;
		for (Link l : links.getOrDefault(socket, List.of())) {
			double angle = degrees(yaw, l.yaw) * (right ? 1 : -1);
			if (l.socket.endsWith("/" + socket)) {
				if (angle < 10 || angle > 100) return false;
			} else {
				if (angle < 10 && angle > -10 || angle > 10) return false;
				if (l.grade == grade) result = true;
			}
		}
		return result;
	}

	private static boolean side(JsonObject t, Map<String, List<Link>> links, int grade, float yaw) {
		boolean left = t.get("kind").getAsString().equals("left");
		int from = left ? 4 : 3, to = left ? 3 : 4;
		if (!links.containsKey("neighbour/" + from)) return false;
		int angle = t.get("angle").getAsInt(), shape = t.get("shape").getAsInt();
		if (angle == -1) return !linked(links, from, to);
		boolean result = false;
		for (Link link : links.get("neighbour/" + from)) {
			if (!link.socket.endsWith("/neighbour/" + to) || link.grade != grade) continue;
			if (shape == 0 && !link.piece.equals("roof") || shape == 1 && !link.piece.equals("roof.triangle")) continue;
			double degrees = Math.toDegrees((yaw - link.yaw) * (left ? 1 : -1));
			degrees = ((degrees + 180) % 360 + 360) % 360 - 180;
			if (degrees < angle - 10 || degrees > angle + 10) {
				if (angle > 10) return false;
			} else result = true;
		}
		return result;
	}
}
