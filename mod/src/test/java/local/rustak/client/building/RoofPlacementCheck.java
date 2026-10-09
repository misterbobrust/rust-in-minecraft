package local.rustak.client.building;

import local.rustak.building.BuildingDefs;
import local.rustak.building.Obb;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Placement fixtures at a wall's upper socket, including both look directions and manual rotation. */
public final class RoofPlacementCheck {
	private static int checks;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) {
		GibDynamicsCheck.main(args);
		if (RoofPlacementCheck.class.getResource("/assets/rustak/building/roof.json") == null)
			throw new AssertionError("Export roof assets before checking placement");
		var wall = BuildingDefs.piece(1);
		var female = wall.sockets.stream().filter(s -> s.female && !s.femaleDummy && s.type == 5).findFirst().orElseThrow();
		for (int index : new int[] {9, 10}) {
			var def = BuildingDefs.piece(index);
			var male = def.sockets.stream().filter(s -> s.male && !s.maleDummy && !s.neighbour).findFirst().orElseThrow();
			require(male.rotationDegrees == 180 && Math.abs(male.support - 0.3f) < 1e-5, "Placement/support data changed");
			for (float yaw : new float[] {0, (float) Math.PI / 3, (float) -Math.PI / 2}) {
				var rot = new Quaternionf().rotationY(yaw);
				var point = rot.transform(new Vector3f(female.pos));
				var fr = new Quaternionf(rot).mul(female.rot);
				for (int direction : new int[] {-1, 1}) {
					var ray = rot.transform(new Vector3f(direction, -0.3f, 0)).normalize();
					var pl = BuildingPlacement.doPlacement(male, female, point, fr, ray, 0);
					var turned = BuildingPlacement.doPlacement(male, female, point, fr, ray, 180);
					var placed = new Quaternionf().rotationY(pl.yaw());
					var socket = placed.transform(new Vector3f(male.pos)).add((float) pl.pos().x, (float) pl.pos().y, (float) pl.pos().z);
					require(socket.distance(point) < 1e-5, "Roof does not land on its wall socket");
					float delta = Math.abs((float) Math.atan2(Math.sin(pl.yaw() - turned.yaw()), Math.cos(pl.yaw() - turned.yaw())));
					require(Math.abs(delta - Math.PI) < 1e-5, "Manual rotation is not 180 degrees");
					var roofForward = placed.transform(new Vector3f(0, 0, -1));
					var wallForward = rot.transform(new Vector3f(direction, 0, 0));
					require(roofForward.distance(wallForward) < 1e-5, "Automatic roof direction differs from the aim");
					for (var a : def.colliders) for (var b : wall.colliders)
						require(!box(a, pl.pos(), pl.yaw()).overlaps(box(b, Vec3.ZERO, yaw), 0.02), "A correctly snapped roof intersects its supporting wall");
				}
			}
		}
		floorAttachments();
		highEdgeAttachments();
		NextPiecesCheck.main(args);
		StepsPlacementCheck.main(args);
		LStairsPlacementCheck.main(args);
		System.out.println("Roof placement fixtures passed: " + checks);
	}

	private static void highEdgeAttachments() {
		var roof = BuildingDefs.piece(9);
		var female = roof.sockets.stream().filter(s -> s.female && !s.femaleDummy && s.type == 5).findFirst().orElseThrow();
		require(Math.abs(female.pos.y - 3) < 1e-5, "Wall attachment is no longer at the high roof edge");
		for (int index : new int[] {1, 3, 4, 5, 6, 11}) {
			var wall = BuildingDefs.piece(index);
			var male = wall.sockets.stream().filter(s -> s.male && !s.maleDummy && s.type == 5).findFirst().orElseThrow();
			require(male.compatible(female), "High roof edge no longer accepts " + wall.name);
			for (float yaw : new float[] {0, (float) Math.PI / 3, (float) -Math.PI / 2}) {
				var rot = new Quaternionf().rotationY(yaw);
				var point = rot.transform(new Vector3f(female.pos));
				var fr = new Quaternionf(rot).mul(female.rot);
				var ray = rot.transform(new Vector3f(1, -0.3f, 0)).normalize();
				var pl = BuildingPlacement.doPlacement(male, female, point, fr, ray, 0);
				var socket = new Quaternionf().rotationY(pl.yaw()).transform(new Vector3f(male.pos))
					.add((float) pl.pos().x, (float) pl.pos().y, (float) pl.pos().z);
				require(socket.distance(point) < 1e-5, wall.name + " misses the high roof attachment");
				for (var clearance : wall.placementChecks) for (var b : roof.colliders) {
					if (clearance.blocks(roof.name)) require(!clearance.overlaps(pl.pos(), pl.yaw(), box(b, Vec3.ZERO, yaw)), "High roof edge falsely blocks " + wall.name);
				}
			}
		}
	}

	private static void floorAttachments() {
		int oldFalseRejections = 0;
		for (int index : new int[] {9, 10}) for (int base : new int[] {2, 8}) {
			var roof = BuildingDefs.piece(index);
			var floor = BuildingDefs.piece(base);
			require(!roof.placementChecks.isEmpty(), "Missing roof clearance data");
			for (var clearance : roof.placementChecks) {
				require(!clearance.blocks("roof") && !clearance.blocks("roof.triangle") && clearance.blocks("wall"), "Wrong roof clearance exclusions");
			}
			var male = roof.sockets.stream().filter(s -> s.male && !s.maleDummy && !s.neighbour).findFirst().orElseThrow();
			for (var female : floor.sockets) {
				if (!female.female || female.femaleDummy || !male.compatible(female)) continue;
				var ray = female.rot.transform(new Vector3f(0, 1, 0)).add(0, -0.3f, 0).normalize();
				var pl = BuildingPlacement.doPlacement(male, female, female.pos, female.rot, ray, 0);
				boolean lipOverlap = false;
				for (var a : roof.colliders) for (var b : floor.colliders)
					lipOverlap |= box(a, pl.pos(), pl.yaw()).overlaps(box(b, Vec3.ZERO, 0), 0.02);
				if (lipOverlap) oldFalseRejections++;
				for (var clearance : roof.placementChecks) {
					for (var b : floor.colliders)
						require(!clearance.overlaps(pl.pos(), pl.yaw(), box(b, Vec3.ZERO, 0)), "Roof attachment falsely blocked by " + floor.name);
					var bounds = clearance.bounds(pl.pos(), pl.yaw());
					require(clearance.overlaps(pl.pos(), pl.yaw(), new Obb(bounds.getCenter(), 0.05, 0.05, 0.05, 0)), "Intruding obstacle is not rejected");
					Vec3 elevated = pl.pos().add(0, 3, 0);
					for (var b : BuildingDefs.piece(1).colliders)
						require(!clearance.overlaps(elevated, pl.yaw(), box(b, new Vec3(female.pos.x, 0, female.pos.z), pl.yaw() + (float) Math.PI / 2)), "Wall below ceiling prevents attachment");
				}
			}
		}
		require(oldFalseRejections > 0, "The ceiling regression was not reproduced");
	}

	private static Obb box(Vector3f[] b, Vec3 pos, float yaw) {
		var center = new Quaternionf().rotationY(yaw).transform(new Vector3f(b[0]));
		return new Obb(pos.add(center.x, center.y, center.z), b[1].x, b[1].y, b[1].z, yaw);
	}
}
