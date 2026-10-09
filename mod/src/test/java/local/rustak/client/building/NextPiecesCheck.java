package local.rustak.client.building;

import local.rustak.building.BuildingDefs;
import local.rustak.building.Obb;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Fixtures for low walls and open floor frames, using exported geometry and attachment data. */
public final class NextPiecesCheck {
	private static int checks;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) {
		String[] old = {"foundation", "wall", "floor", "wall.window", "wall.doorway", "wall.half", "wall.frame",
			"foundation.triangle", "floor.triangle", "roof", "roof.triangle"};
		for (int i = 0; i < old.length; i++) require(BuildingDefs.piece(i).name.equals(old[i]), "Saved piece index changed");
		var low = BuildingDefs.piece(11);
		require(low.name.equals("wall.low") && Math.abs(low.boundsExtents.y * 2 - 1) < 1e-5, "Wrong low wall height/index");
		require(low.canRotate && low.rotationAmount == 180, "Low wall must rotate by a half turn");
		require(low.sockets.stream().noneMatch(s -> s.female && s.type == 5), "Low wall unexpectedly allows stacking");
		for (int piece : new int[] {11, 12, 13}) {
			var def = BuildingDefs.piece(piece);
			require(!def.placementChecks.isEmpty(), "Missing placement clearance for " + def.name);
			float[] health = {10, 250, 500, 1000, 2000};
			for (int grade = 0; grade < 5; grade++) {
				require(def.grades[grade] != null && def.grades[grade].health == health[grade], "Missing/wrong standard grade for " + def.name);
				require(def.grades[grade].sections.getFirst().visible && !def.grades[grade].sections.getFirst().colliders.isEmpty(), "Missing base mesh or collision");
			}
			attachments(def);
		}
		frames();
		lowClearance();
		System.out.println("Low wall/floor frame fixtures passed: " + checks);
	}

	private static void attachments(BuildingDefs.Piece def) {
		var male = def.sockets.stream().filter(s -> s.male && !s.maleDummy && !s.terrain && !s.neighbour).findFirst().orElseThrow();
		float support = def.index == 11 ? 0.9f : def.index == 12 ? 0.25f : 0.33f;
		require(Math.abs(male.support - support) < 1e-5, "Wrong support factor for " + def.name);
		int[] targets = def.index == 11 ? new int[] {0, 7, 2, 8, 12, 13} : new int[] {1, 5, 2, 8, 12, 13};
		for (int index : targets) {
			var target = BuildingDefs.piece(index);
			boolean offered = false;
			for (var female : target.sockets) {
				if (!female.female || female.femaleDummy || !male.compatible(female)) continue;
				offered = true;
				for (float yaw : new float[] {0, (float) Math.PI / 3, (float) -Math.PI / 2}) {
					var rot = new Quaternionf().rotationY(yaw);
					var point = rot.transform(new Vector3f(female.pos));
					var facing = new Quaternionf(rot).mul(female.rot);
					if (female.male && female.female) facing.rotateX((float) Math.PI).rotateZ((float) Math.PI);
					var aim = facing.transform(new Vector3f(0, 1, 0)).add(0, -0.3f, 0).normalize();
					var pl = BuildingPlacement.doPlacement(male, female, point, facing, aim, 0);
					var placed = new Quaternionf().rotationY(pl.yaw());
					var at = placed.transform(new Vector3f(male.pos)).add((float) pl.pos().x, (float) pl.pos().y, (float) pl.pos().z);
					require(at.distance(point) < 1e-5, "Misaligned attachment: " + def.name + " to " + target.name);
					var actual = new Quaternionf(placed).mul(male.rot).transform(new Vector3f(0, 0, 1));
					var expected = new Quaternionf(rot).mul(female.rot).transform(new Vector3f(0, 0, 1));
					float angle = actual.angle(expected);
					if (female.male && female.female) angle = Math.min(angle, (float) Math.PI - angle);
					require(angle < Math.toRadians(2), "Server would reject the attachment rotation");
					for (var check : def.placementChecks) if (check.blocks(target.name)) {
						for (var b : target.colliders) require(!check.overlaps(pl.pos(), pl.yaw(), box(b, Vec3.ZERO, yaw)), "Supporting piece blocks clearance: " + def.name + " to " + target.name);
					}
				}
			}
			require(offered, "No compatible attachment on " + target.name);
		}
	}

	private static void frames() {
		for (int index : new int[] {12, 13}) {
			var def = BuildingDefs.piece(index);
			require(!def.canRotate, "Placed floor frames should not rotate");
			long inserts = def.sockets.stream().filter(s -> s.female && s.type == (index == 12 ? 14 : 10)).count();
			require(inserts == (index == 12 ? 4 : 3), "Missing future hatch/grill attachments");
			Vec3 hole = new Vec3(0, 0, index == 12 ? 0 : -0.8660254);
			for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 3, (float) -Math.PI / 2}) {
				var rot = new Quaternionf().rotationY(yaw);
				var h = rot.transform(new Vector3f((float) hole.x, (float) hole.y, (float) hole.z));
				Vec3 at = new Vec3(h.x, h.y, h.z);
				var player = new Obb(at, 0.3, 1, 0.3, 0);
				boolean edge = false;
				for (var b : def.grades[grade].sections.getFirst().colliders) {
					var collider = box(b, Vec3.ZERO, yaw);
					require(!collider.overlaps(player, 0), "A player cannot pass through the frame's hole: " + def.name + " " + grade + " " + yaw);
					require(collider.raycast(at.add(0, 2, 0), new Vec3(0, -1, 0), 4) < 0, "Frame blocks the ray through its hole");
					edge |= collider.raycast(collider.center().add(0, 2, 0), new Vec3(0, -1, 0), 4) >= 0;
				}
				require(edge, "Frame lost its walkable edges");
			}
		}
	}

	private static void lowClearance() {
		var low = BuildingDefs.piece(11);
		for (int grade = 0; grade < 5; grade++) {
			var existing = BuildingDefs.piece(1).grades[grade].sections.getFirst().colliders;
			require(low.placementChecks.stream().filter(c -> c.blocks("wall")).anyMatch(c -> existing.stream().anyMatch(b -> c.overlaps(new Vec3(0, 1.5, 0), 0, box(b, Vec3.ZERO, 0)))), "Low wall can overlap an existing wall at intermediate height");
		}
	}

	private static Obb box(Vector3f[] b, Vec3 pos, float yaw) {
		var c = new Quaternionf().rotationY(yaw).transform(new Vector3f(b[0]));
		return new Obb(pos.add(c.x, c.y, c.z), b[1].x, b[1].y, b[1].z, yaw);
	}
}
