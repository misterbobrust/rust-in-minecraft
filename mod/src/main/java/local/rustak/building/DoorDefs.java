package local.rustak.building;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import local.rustak.RustAk;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rust's hinged doors from building/doors.json: the doorway (or wall frame) socket, health, and
 * per hinge its pivot, its leaf's collider and the open/close animation as a turn about the pivot. All mirrored into
 * Minecraft space like BuildingDefs (Unity's z flipped).
 */
public final class DoorDefs {
	/** Door kinds; DoorEntity stores the index, the items are in the same order (RustAk.DOOR_ITEMS). */
	public static final String[] DOORS = {"door.hinged.wood", "door.hinged.metal", "door.double.hinged.wood", "door.double.hinged.metal"};
	private static final List<Door> ALL = new ArrayList<>();

	public static final class Hinge {
		/** Pivot in the door's space. */
		public final Vector3f pivot;
		/** The leaf's collider at rest (closed), in the door's space: centre and half extents. */
		public final Vector3f centre, half;
		/** The turn about the pivot (radians about +Y) per animation frame, at 30 fps. */
		final float[] open, close;

		Hinge(JsonObject h, JsonObject clips) {
			pivot = mirror(vec(h.getAsJsonArray("pos")));
			JsonArray r = h.getAsJsonArray("rot");
			Quaternionf rest = new Quaternionf(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat());
			if (h.has("collider") && h.get("collider").isJsonObject()) {
				JsonObject c = h.getAsJsonObject("collider");
				Vector3f pu = vec(h.getAsJsonArray("pos")); // in Unity space until mirrored
				centre = mirror(rest.transform(vec(c.getAsJsonArray("center"))).add(pu));
				Vector3f s = vec(c.getAsJsonArray("size")).mul(0.5f);
				Vector3f x = rest.transform(new Vector3f(s.x, 0, 0)), y = rest.transform(new Vector3f(0, s.y, 0)), z = rest.transform(new Vector3f(0, 0, s.z));
				half = new Vector3f(Math.abs(x.x) + Math.abs(y.x) + Math.abs(z.x), Math.abs(x.y) + Math.abs(y.y) + Math.abs(z.y),
					Math.abs(x.z) + Math.abs(y.z) + Math.abs(z.z));
			} else {
				centre = new Vector3f(pivot);
				half = new Vector3f(0.05f, 1, 0.6f);
			}
			String name = h.get("name").getAsString();
			open = turns(clips, "open", name);
			close = turns(clips, "close", name);
		}

		private static float[] turns(JsonObject clips, String state, String hinge) {
			if (!clips.has(state)) return new float[] {0};
			JsonArray q = clips.getAsJsonObject(state).getAsJsonObject("hinges").getAsJsonArray(hinge);
			float[] out = new float[q.size()];
			for (int i = 0; i < out.length; i++) {
				JsonArray f = q.get(i).getAsJsonArray();
				float y = -f.get(1).getAsFloat(), w = f.get(3).getAsFloat(); // mirrored: (-x, -y, z, w)
				if (w < 0) {
					y = -y;
					w = -w;
				}
				out[i] = (float) (2 * Math.atan2(y, w));
			}
			return out;
		}
	}

	public static final class Door {
		public final String name, material;
		public final boolean isDouble, hasFrame;
		public final float health, openTime, closeTime;
		/** Rust's protection values by damage type (as BuildingDefs.Grade): 1 is immune, -1 takes double. */
		public final float[] protection;
		/** Seconds into closing when the leaf meets the frame (Rust's DoorCloseEnd event, 1.07 s of clip). */
		public final float closeHit;
		public final BuildingDefs.Socket socket;
		public final List<Hinge> hinges = new ArrayList<>();

		Door(String name, JsonObject o) {
			this.name = name;
			material = o.get("material").getAsString();
			isDouble = o.get("double").getAsBoolean();
			hasFrame = o.has("frame") && o.get("frame").getAsBoolean();
			health = o.get("health").getAsFloat();
			JsonArray prot = o.has("protection") ? o.getAsJsonArray("protection") : new JsonArray();
			protection = new float[prot.size()];
			for (int i = 0; i < protection.length; i++) protection[i] = prot.get(i).getAsFloat();
			JsonObject s = o.getAsJsonObject("socket");
			JsonObject sock = new JsonObject();
			sock.addProperty("name", s.get("name").getAsString());
			sock.addProperty("cls", "ConstructionSocket");
			sock.add("pos", s.get("pos"));
			sock.add("rot", s.get("rot"));
			sock.addProperty("male", true);
			sock.addProperty("female", false);
			sock.addProperty("male_dummy", false);
			sock.addProperty("female_dummy", false);
			sock.addProperty("type", s.get("type").getAsInt());
			sock.addProperty("rotation_degrees", 0);
			sock.addProperty("rotation_offset", 0);
			sock.addProperty("restrict_rotation", false);
			JsonArray zero = new JsonArray();
			for (int i = 0; i < 3; i++) zero.add(0);
			sock.add("select_size", zero);
			sock.add("select_center", zero);
			socket = new BuildingDefs.Socket(sock);
			JsonObject clips = o.getAsJsonObject("clips");
			for (var h : o.getAsJsonArray("hinges")) hinges.add(new Hinge(h.getAsJsonObject(), clips));
			openTime = time(clips, "open");
			closeTime = time(clips, "close");
			closeHit = clips.has("close") ? 1.07f / Math.max(0.01f, clips.getAsJsonObject("close").get("speed").getAsFloat()) : closeTime;
		}

		private static float time(JsonObject clips, String state) {
			if (!clips.has(state)) return 0.5f;
			JsonObject c = clips.getAsJsonObject(state);
			return c.get("length").getAsFloat() / Math.max(0.01f, c.get("speed").getAsFloat());
		}

		/** A hinge's turn: opening (or closing) for seconds since it started; the end pose once done. */
		public float turn(int hinge, boolean open, float seconds) {
			Hinge h = hinges.get(hinge);
			float[] f = open ? h.open : h.close;
			float t = Math.clamp(seconds / (open ? openTime : closeTime), 0, 1) * (f.length - 1);
			int i = Math.min(f.length - 2, (int) t);
			if (i < 0) return f[0];
			return f[i] + (f[i + 1] - f[i]) * (t - i);
		}

		public String sound(String event) {
			return "door_" + (isDouble ? "double_" : "") + material + "_" + event;
		}
	}

	static {
		try (var in = RustAk.class.getResourceAsStream("/assets/rustak/building/doors.json")) {
			JsonObject all = in == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (String name : DOORS) ALL.add(all.has(name) ? new Door(name, all.getAsJsonObject(name)) : null);
		} catch (Exception e) {
			throw new RuntimeException("rustak: can't load building/doors.json", e);
		}
	}

	private DoorDefs() {
	}

	/** The door of a kind, or null when the installer didn't export it. */
	public static Door door(int kind) {
		return kind >= 0 && kind < ALL.size() ? ALL.get(kind) : null;
	}

	private static Vector3f vec(JsonArray a) {
		return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	private static Vector3f mirror(Vector3f v) {
		return v.set(v.x, v.y, -v.z);
	}
}
