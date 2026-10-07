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

/**
 * Rust building blocks (building/<piece>.json): sockets, bounds, grades.
 * Unity is left-handed; everything here is mirrored on Z into Minecraft's right-handed space once, at load:
 * positions (x, y, -z), rotations (-x, -y, z, w).
 */
public final class BuildingDefs {
	public static final String[] PIECES = {"foundation", "wall", "floor", "wall.window", "wall.doorway", "wall.half", "wall.frame"};
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
		public final boolean male, female, maleDummy, femaleDummy, restrictRotation, terrain, femaleNoStability;
		/** Stability: what this male socket passes on of its supporter's stability (Stability). */
		public final float support;
		public final int type, rotationDegrees, rotationOffset;

		Socket(JsonObject o) {
			name = o.get("name").getAsString();
			cls = o.get("cls").getAsString();
			terrain = cls.equals("Socket_Terrain");
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

	public static final class Grade {
		public final String name;
		public final float health;
		public final float[] protection;

		Grade(JsonObject o) {
			name = o.get("grade").getAsString();
			health = o.get("health").getAsFloat();
			JsonArray p = o.getAsJsonArray("protection");
			protection = new float[p.size()];
			for (int i = 0; i < protection.length; i++) protection[i] = p.get(i).getAsFloat();
		}

		public float protection(int damageType) {
			return damageType < protection.length ? protection[damageType] : 0;
		}
	}

	public static final class Piece {
		public final int index;
		public final String name;
		public final Vector3f boundsCenter, boundsExtents;
		public final float maxDistance, rotationAmount;
		/** Hammer "Rotate": whether the piece can turn after placement (by its rotation step). */
		public final boolean canRotate;
		/** Collision boxes (centre, extents) in the piece's frame: the bounds, or the slab cut around a doorway/window. */
		public final List<Vector3f[]> colliders = new ArrayList<>();
		public final List<Socket> sockets = new ArrayList<>();
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
			if (o.has("colliders")) {
				for (var c : o.getAsJsonArray("colliders")) {
					JsonObject box = c.getAsJsonObject();
					colliders.add(new Vector3f[] {mirror(vec(box.getAsJsonArray("center"))), vec(box.getAsJsonArray("extents"))});
				}
			} else {
				colliders.add(new Vector3f[] {boundsCenter, boundsExtents});
			}
			for (var s : o.getAsJsonArray("sockets")) sockets.add(new Socket(s.getAsJsonObject()));
			for (var g : o.getAsJsonArray("grades")) {
				Grade grade = new Grade(g.getAsJsonObject());
				for (int i = 0; i < GRADES.length; i++) if (GRADES[i].equals(grade.name)) grades[i] = grade;
			}
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
}
