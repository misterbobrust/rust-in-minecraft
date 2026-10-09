package local.rustak.building;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rust building blocks (building/<piece>.json): sockets, bounds, grades.
 * Unity is left-handed; everything here is mirrored on Z into Minecraft's right-handed space once, at load:
 * positions (x, y, -z), rotations (-x, -y, z, w).
 */
public final class BuildingDefs {
	public static final String[] PIECES = {"foundation", "wall", "floor", "wall.window", "wall.doorway", "wall.half", "wall.frame",
		"foundation.triangle", "floor.triangle", "roof", "roof.triangle", "wall.low", "floor.frame", "floor.triangle.frame", "foundation.steps", "stairs.l", "stairs.u"}; // appended: saved blocks keep their index
	public static final String[] GRADES = {"twig", "wood", "stone", "metal", "toptier"};
	// Rust DamageType indices used here
	public static final int BULLET = 9, BLUNT = 11, EXPLOSION = 16;
	public static final List<Piece> ALL = new ArrayList<>();

	static {
		for (String name : PIECES) ALL.add(load(name));
	}

	public static Piece piece(int index) {
		return ALL.get(Math.floorMod(index, ALL.size()));
	}

	public static final class Socket {
		public final String name, cls;
		public final Vector3f pos, selectSize, selectCenter;
		public final Quaternionf rot;
		public final boolean male, female, maleDummy, femaleDummy, restrictRotation, terrain, femaleNoStability, neighbour, monogamous;
		/** Stability: what this male socket passes on of its supporter's stability (Stability). */
		public final float support;
		public final int type, rotationDegrees, rotationOffset;
		public final boolean restrictAngle;
		public final float faceAngle, angleAllowed;
		public final List<TerrainCheck> terrainChecks = new ArrayList<>();

		Socket(JsonObject o) {
			name = o.get("name").getAsString();
			cls = o.get("cls").getAsString();
			terrain = cls.equals("Socket_Terrain");
			neighbour = cls.equals("NeighbourSocket");
			monogamous = !o.has("monogamous") || o.get("monogamous").getAsBoolean();
			pos = mirror(vec(o.getAsJsonArray("pos")));
			JsonArray r = o.getAsJsonArray("rot");
			rot = new Quaternionf(-r.get(0).getAsFloat(), -r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat()).normalize();
			male = o.get("male").getAsBoolean();
			female = o.get("female").getAsBoolean();
			maleDummy = o.get("male_dummy").getAsBoolean();
			femaleDummy = o.get("female_dummy").getAsBoolean();
			type = o.get("type").getAsInt();
			rotationDegrees = o.get("rotation_degrees").getAsInt();
			rotationOffset = o.get("rotation_offset").getAsInt();
			restrictRotation = o.get("restrict_rotation").getAsBoolean();
			restrictAngle = o.has("restrict_angle") && o.get("restrict_angle").getAsBoolean();
			faceAngle = o.has("face_angle") ? o.get("face_angle").getAsFloat() : 0;
			angleAllowed = o.has("angle_allowed") ? o.get("angle_allowed").getAsFloat() : 150;
			if (o.has("terrain_checks")) for (var c : o.getAsJsonArray("terrain_checks")) {
				var point = c.getAsJsonObject();
				terrainChecks.add(new TerrainCheck(mirror(vec(point.getAsJsonArray("pos"))), point.get("wants").getAsBoolean()));
			}
			support = o.has("support") ? o.get("support").getAsFloat() : 1;
			femaleNoStability = o.has("female_no_stability") && o.get("female_no_stability").getAsBoolean();
			selectSize = vec(o.getAsJsonArray("select_size"));
			selectCenter = mirror(vec(o.getAsJsonArray("select_center")));
		}

		/** Whether two sockets fit: same socket type, neither None. */
		public boolean compatible(Socket other) {
			return type > 0 && other.type > 0 && type == other.type;
		}
	}

	/** Convex hull (x, z pairs, counter-clockwise) of the boxes' corners in plan. */
	private static float[] outline(List<Vector3f[]> boxes) {
		List<float[]> pts = new ArrayList<>();
		for (Vector3f[] b : boxes) {
			for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) pts.add(new float[] {b[0].x + sx * b[1].x, b[0].z + sz * b[1].z});
		}
		pts.sort((a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
		float[][] hull = new float[2 * pts.size()][];
		int k = 0;
		for (int pass = 0; pass < 2; pass++) {
			int start = k;
			for (int i = 0; i < pts.size(); i++) {
				float[] p = pts.get(pass == 0 ? i : pts.size() - 1 - i);
				while (k >= start + 2 && cross(hull[k - 2], hull[k - 1], p) <= 0) k--;
				hull[k++] = p;
			}
			k--; // the last point starts the other half
		}
		float[] out = new float[2 * k];
		for (int i = 0; i < k; i++) {
			out[2 * i] = hull[i][0];
			out[2 * i + 1] = hull[i][1];
		}
		return out;
	}

	private static float cross(float[] o, float[] a, float[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}

	public record TerrainCheck(Vector3f pos, boolean wantsGround) {
	}

	/** A placement clearance box, independent of the surface used for walking collision. */
	public static final class PlacementCheck {
		public final Vector3f center, extents;
		public final Quaternionf rot;
		public final boolean include, blocksWorld, blocksBuildings, sphere;
		public final List<String> pieces = new ArrayList<>();

		PlacementCheck(JsonObject o) {
			center = mirror(vec(o.getAsJsonArray("center")));
			extents = vec(o.getAsJsonArray("extents"));
			var r = o.getAsJsonArray("rot");
			rot = new Quaternionf(-r.get(0).getAsFloat(), -r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat()).normalize();
			include = o.get("include").getAsBoolean();
			blocksWorld = o.get("blocks_world").getAsBoolean();
			blocksBuildings = !o.has("blocks_buildings") || o.get("blocks_buildings").getAsBoolean();
			sphere = o.has("shape") && o.get("shape").getAsString().equals("sphere");
			for (var p : o.getAsJsonArray("pieces")) pieces.add(p.getAsString());
		}

		public boolean blocks(String piece) {
			return blocksBuildings && (pieces.isEmpty() || include == pieces.contains(piece));
		}

		private Vector3f worldCenter(Vec3 pos, float yaw) {
			return new Quaternionf().rotationY(yaw).transform(new Vector3f(center)).add((float) pos.x, (float) pos.y, (float) pos.z);
		}

		public boolean overlaps(Vec3 pos, float yaw, Obb other) {
			if (sphere) {
				Vector3f c = worldCenter(pos, yaw);
				Vec3 p = new Vec3(c.x, c.y, c.z);
				return other.closest(p).distanceToSqr(p) <= extents.x * extents.x;
			}
			return RoofShape.boxesOverlap(worldCenter(pos, yaw), new Quaternionf().rotationY(yaw).mul(rot), new Vector3f(extents).mul(2),
				new Vector3f((float) other.center().x, (float) other.center().y, (float) other.center().z),
				new Quaternionf().rotationY(other.yaw()), new Vector3f((float) other.ex(), (float) other.ey(), (float) other.ez()).mul(2));
		}

		public AABB bounds(Vec3 pos, float yaw) {
			Quaternionf q = new Quaternionf().rotationY(yaw).mul(rot);
			Vector3f half = q.transform(new Vector3f(1, 0, 0)).absolute().mul(extents.x)
				.add(q.transform(new Vector3f(0, 1, 0)).absolute().mul(extents.y))
				.add(q.transform(new Vector3f(0, 0, 1)).absolute().mul(extents.z));
			Vector3f c = worldCenter(pos, yaw);
			return new AABB(c.x - half.x, c.y - half.y, c.z - half.z, c.x + half.x, c.y + half.y, c.z + half.z);
		}
	}

	public static final class Grade {
		public final String name;
		public final float health;
		public final float[] protection;
		public final List<Section> sections = new ArrayList<>();

		Grade(JsonObject o) {
			name = o.get("grade").getAsString();
			health = o.get("health").getAsFloat();
			JsonArray p = o.getAsJsonArray("protection");
			protection = new float[p.size()];
			for (int i = 0; i < protection.length; i++) protection[i] = p.get(i).getAsFloat();
			if (o.has("sections")) for (var s : o.getAsJsonArray("sections")) sections.add(new Section(s.getAsJsonObject()));
			if (sections.size() > 63) throw new IllegalArgumentException("Too many building sections");
		}

		public float protection(int damageType) {
			return damageType < protection.length ? protection[damageType] : 0;
		}
	}

	/** A conditional section: shared shape rules, its mesh and its collision in the piece's frame. */
	public static final class Section {
		public final String mesh;
		public final boolean visible;
		public final JsonArray tests;
		public final List<Vector3f[]> colliders = new ArrayList<>();
		public final List<Vector3f[]> placementColliders;

		Section(JsonObject o) {
			mesh = o.get("mesh").getAsString();
			visible = o.get("visible").getAsBoolean();
			tests = o.getAsJsonArray("tests");
			for (var c : o.getAsJsonArray("colliders")) {
				JsonObject b = c.getAsJsonObject();
				colliders.add(collider(b));
			}
			if (o.has("placement_colliders")) {
				placementColliders = new ArrayList<>();
				for (var c : o.getAsJsonArray("placement_colliders")) placementColliders.add(collider(c.getAsJsonObject()));
			} else placementColliders = colliders;
		}
	}

	public static final class Piece {
		public final int index;
		public final String name;
		public final Vector3f boundsCenter, boundsExtents;
		public final Vector3f collisionBoundsCenter, collisionBoundsExtents;
		public final float maxDistance, rotationAmount;
		/** Hammer "Rotate": whether the piece can turn after placement (by its rotation step). */
		public final boolean canRotate;
		/** Collision boxes (centre, extents) in the piece's frame: the bounds, or the slab cut around a doorway/window. */
		public final List<Vector3f[]> colliders = new ArrayList<>();
		public final List<Socket> sockets = new ArrayList<>();
		public final List<PlacementCheck> placementChecks = new ArrayList<>();
		public final List<Vector3f> proximityPoints = new ArrayList<>(), sightPoints = new ArrayList<>();
		public final boolean alternateSight, checkParentSight;
		/**
		 * Points (in the piece's frame) that must have the ground over them, or must not, for the piece to go down on
		 * the terrain: under a foundation's corners, and just above its top.
		 */
		public final List<TerrainCheck> terrainChecks = new ArrayList<>();
		/**
		 * The piece's plan as a convex outline (x, z pairs in its frame) around its collision boxes, its centre and the
		 * distance from there to the nearest side. A model's outline would be wider: twigs and skirts stick out.
		 */
		public final float[] footprint;
		public final float footprintX, footprintZ, footprintInradius;
		public final Grade[] grades = new Grade[GRADES.length];

		Piece(int index, JsonObject o) {
			this.index = index;
			name = o.get("name").getAsString();
			JsonObject b = o.getAsJsonObject("bounds");
			boundsCenter = mirror(vec(b.getAsJsonArray("center")));
			boundsExtents = vec(b.getAsJsonArray("extents"));
			maxDistance = o.get("max_distance").getAsFloat();
			rotationAmount = o.get("rotation_amount").getAsFloat();
			canRotate = o.has("can_rotate") && o.get("can_rotate").getAsBoolean();
			alternateSight = o.has("alternate_sight") && o.get("alternate_sight").getAsBoolean();
			checkParentSight = o.has("check_parent_sight") && o.get("check_parent_sight").getAsBoolean();
			if (o.has("proximity_points")) for (var p : o.getAsJsonArray("proximity_points")) proximityPoints.add(mirror(vec(p.getAsJsonArray())));
			if (o.has("sight_points")) for (var p : o.getAsJsonArray("sight_points")) sightPoints.add(mirror(vec(p.getAsJsonArray())));
			if (o.has("colliders")) {
				for (var c : o.getAsJsonArray("colliders")) {
					JsonObject box = c.getAsJsonObject();
					colliders.add(collider(box));
				}
			} else {
				colliders.add(new Vector3f[] {boundsCenter, boundsExtents});
			}
			for (var s : o.getAsJsonArray("sockets")) sockets.add(new Socket(s.getAsJsonObject()));
			if (o.has("placement_checks")) for (var c : o.getAsJsonArray("placement_checks")) placementChecks.add(new PlacementCheck(c.getAsJsonObject()));
			float[] fp = outline(colliders);
			float fx = 0, fz = 0, area = 0, inradius = Float.MAX_VALUE;
			int n = fp.length / 2;
			for (int i = 0; i < n; i++) { // the outline's centre by area (its corners crowd along a triangle's stepped sides)
				float ax = fp[2 * i], az = fp[2 * i + 1], bx = fp[2 * ((i + 1) % n)], bz = fp[2 * ((i + 1) % n) + 1];
				float c = ax * bz - bx * az;
				area += c;
				fx += (ax + bx) * c;
				fz += (az + bz) * c;
			}
			fx /= 3 * area;
			fz /= 3 * area;
			for (int i = 0; i < n; i++) {
				float ax = fp[2 * i], az = fp[2 * i + 1], bx = fp[2 * ((i + 1) % n)], bz = fp[2 * ((i + 1) % n) + 1];
				float len = (float) Math.hypot(bx - ax, bz - az);
				if (len > 1e-4f) inradius = Math.min(inradius, Math.abs((bx - ax) * (az - fz) - (bz - az) * (ax - fx)) / len);
			}
			footprint = fp;
			footprintX = fx;
			footprintZ = fz;
			footprintInradius = inradius;
			if (o.has("terrain_checks")) {
				for (var t : o.getAsJsonArray("terrain_checks")) {
					JsonObject c = t.getAsJsonObject();
					terrainChecks.add(new TerrainCheck(mirror(vec(c.getAsJsonArray("pos"))), c.get("wants").getAsBoolean()));
				}
			}
			for (var g : o.getAsJsonArray("grades")) {
				Grade grade = new Grade(g.getAsJsonObject());
				for (int i = 0; i < GRADES.length; i++) if (GRADES[i].equals(grade.name)) grades[i] = grade;
			}
			Vector3f low = new Vector3f(boundsCenter).sub(boundsExtents), high = new Vector3f(boundsCenter).add(boundsExtents);
			for (Vector3f[] box : colliders) {
				low.min(new Vector3f(box[0]).sub(box[1]));
				high.max(new Vector3f(box[0]).add(box[1]));
			}
			for (Grade grade : grades) for (Section section : grade.sections) for (Vector3f[] box : section.colliders) {
				low.min(new Vector3f(box[0]).sub(box[1]));
				high.max(new Vector3f(box[0]).add(box[1]));
			}
			collisionBoundsCenter = new Vector3f(low).add(high).mul(0.5f);
			collisionBoundsExtents = new Vector3f(high).sub(low).mul(0.5f);
		}
	}

	private static Piece load(String name) {
		try (var in = BuildingDefs.class.getResourceAsStream("/assets/rustak/building/" + name + ".json")) {
			return new Piece(ALL.size(), JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (Exception e) {
			throw new RuntimeException("rustak: can't read building/" + name + ".json", e);
		}
	}

	private static Vector3f vec(JsonArray a) {
		return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	private static Vector3f mirror(Vector3f v) {
		return v.set(v.x, v.y, -v.z);
	}

	private static Vector3f[] collider(JsonObject box) {
		Vector3f center = mirror(vec(box.getAsJsonArray("center"))), extents = vec(box.getAsJsonArray("extents"));
		if (!box.has("top_plane")) return new Vector3f[] {center, extents};
		Vector3f top = vec(box.getAsJsonArray("top_plane")), bottom = vec(box.getAsJsonArray("bottom_plane"));
		top.y = -top.y; bottom.y = -bottom.y;
		return new Vector3f[] {center, extents, top, bottom};
	}
}
