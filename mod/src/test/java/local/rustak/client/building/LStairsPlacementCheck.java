package local.rustak.client.building;

import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Interior stairs occupy a square base, with quarter turns and their own clearance volumes. */
public final class LStairsPlacementCheck {
	private static int checks;
	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}
	public static void main(String[] args) {
		checkPiece(15, "stairs.l");
		checkPiece(16, "stairs.u");
		enclosedBases();
		overheadFloors();
		System.out.println("Interior stair placement fixtures passed: " + checks);
	}

	private static void overheadFloors() {
		int oldRejections = 0;
		for (int index : new int[] {2, 8}) {
			var floor = BuildingDefs.piece(index);
			require(!floor.placementChecks.isEmpty(), "Missing floor placement clearance above stairs: " + floor.name);
			for (int piece : new int[] {15, 16}) for (int grade = 0; grade < 5; grade++)
				for (float yaw : new float[] {0, -.8751361f, (float) Math.PI / 4, (float) -Math.PI / 2}) {
				var origin = new Vec3(16, 64, -20);
				var stairs = BuildingDefs.piece(piece);
				var stairPos = origin.add(0, .1, 0);
				var stairSolids = BuildingEntity.placementSolidsAt(stairs, stairPos, yaw, grade, 1);
				for (int degrees : new int[] {0, 90, 180, 270}) {
					float floorYaw = yaw + (float) Math.toRadians(degrees);
					Vec3 above = origin.add(0, 3, 0);
					if (index == 8) {
						var off = new Quaternionf().rotationY(floorYaw).transform(new Vector3f(0, 0, -.8660254f));
						above = above.subtract(off.x, off.y, off.z);
					}
					final Vec3 floorPos = above;
					var slab = BuildingEntity.obbAt(floor, floorPos, floorYaw);
					var bounds = BuildingEntity.obbAt(stairs, stairPos, yaw);
					if (Math.min(slab.center().y + slab.ey(), bounds.center().y + bounds.ey())
							- Math.max(slab.center().y - slab.ey(), bounds.center().y - bounds.ey()) > .05) oldRejections++;
					for (var clearance : floor.placementChecks) if (clearance.blocks(stairs.name))
						require(stairSolids.stream().noneMatch(b -> clearance.overlaps(floorPos, floorYaw, b)), "Upper floor is blocked by stairs: " + floor.name + " " + stairs.name + " " + grade + " " + yaw + " " + degrees);
					var duplicate = BuildingEntity.placementSolidsAt(floor, floorPos, floorYaw, grade, 0);
					require(floor.placementChecks.stream().filter(c -> c.blocks(floor.name)).anyMatch(c -> duplicate.stream().anyMatch(b -> c.overlaps(floorPos, floorYaw, b))), "An existing upper floor no longer blocks a duplicate: " + floor.name);
					for (var clearance : floor.placementChecks) if (clearance.blocksWorld) {
						var center = new Quaternionf().rotationY(floorYaw).transform(new Vector3f(clearance.center));
						var block = new Obb(floorPos.add(center.x, center.y, center.z), .5, .5, .5, 0);
						require(clearance.overlaps(floorPos, floorYaw, block), "An ordinary block through the upper floor is ignored: " + floor.name);
					}
				}
				if (index == 2) {
					require(floor.placementChecks.stream().filter(c -> c.blocks(stairs.name)).anyMatch(c -> stairSolids.stream().anyMatch(b -> c.overlaps(origin.add(0, 1.5, 0), yaw, b))), "A floor through the stair landing is accepted");
				}
			}
		}
		require(oldRejections > 0, "The old bounds check did not reject upper floors");
	}

	private static void enclosedBases() {
		var wall = BuildingDefs.piece(1);
		var wallMale = wall.sockets.stream().filter(s -> s.male && !s.maleDummy && s.type == 5).findFirst().orElseThrow();
		int oldRejections = 0;
		for (int baseIndex : new int[] {0, 2}) for (int piece : new int[] {15, 16}) for (int grade = 0; grade < 5; grade++)
			for (float yaw : new float[] {0, -.8751361f, (float) Math.PI / 4, (float) -Math.PI / 2}) for (int degrees : new int[] {0, 90, 180, 270}) {
			var base = BuildingDefs.piece(baseIndex);
			var def = BuildingDefs.piece(piece);
			var origin = new Vec3(16, 64, -20);
			var rotation = new Quaternionf().rotationY(yaw);
			var stairMale = def.sockets.stream().filter(s -> s.male && !s.maleDummy && s.type == 6).findFirst().orElseThrow();
			var stairFemale = base.sockets.stream().filter(s -> s.female && !s.femaleDummy && s.type == 6).findFirst().orElseThrow();
			var point = rotation.transform(new Vector3f(stairFemale.pos)).add((float) origin.x, (float) origin.y, (float) origin.z);
			var stairs = BuildingPlacement.doPlacement(stairMale, stairFemale, point, new Quaternionf(rotation).mul(stairFemale.rot), rotation.transform(new Vector3f(0, -.3f, -1)), degrees);
			for (var female : base.sockets) {
				if (!female.female || female.femaleDummy || female.type != 5) continue;
				var wallPoint = rotation.transform(new Vector3f(female.pos)).add((float) origin.x, (float) origin.y, (float) origin.z);
				var placement = BuildingPlacement.doPlacement(wallMale, female, wallPoint, new Quaternionf(rotation).mul(female.rot), new Vector3f(0, -.3f, -1), 0);
				var solids = BuildingEntity.placementSolidsAt(wall, placement.pos(), placement.yaw(), grade, 1);
				var walking = BuildingEntity.solidsAt(wall, placement.pos(), placement.yaw(), grade, 1);
				for (var clearance : BuildingDefs.piece(2).placementChecks) if (clearance.blocks(wall.name))
					require(solids.stream().noneMatch(b -> clearance.overlaps(origin.add(0, 3, 0), yaw, b)), "Perimeter wall blocks the upper square floor: " + grade + " " + female.name);
				for (int volume = 0; volume < def.placementChecks.size(); volume++) {
					var check = def.placementChecks.get(volume);
					if (walking.stream().anyMatch(b -> check.overlaps(stairs.pos(), stairs.yaw(), b))) oldRejections++;
					require(solids.stream().noneMatch(b -> check.overlaps(stairs.pos(), stairs.yaw(), b)), "Perimeter wall blocks interior stairs: " + def.name + " " + grade + " " + female.name + " volume=" + volume);
				}
			}
			var crossingWall = BuildingEntity.placementSolidsAt(wall, origin, yaw, grade, 1);
			require(def.placementChecks.stream().anyMatch(c -> crossingWall.stream().anyMatch(b -> c.overlaps(stairs.pos(), stairs.yaw(), b))), "An interior wall across the staircase no longer blocks placement");
			var crossingFloor = BuildingEntity.placementSolidsAt(BuildingDefs.piece(2), origin.add(0, 1.5, 0), yaw, grade, 0);
			require(def.placementChecks.stream().anyMatch(c -> crossingFloor.stream().anyMatch(b -> c.overlaps(stairs.pos(), stairs.yaw(), b))), "An intermediate floor across the landing no longer blocks placement");
		}
		require(oldRejections > 0, "The enlarged walking-cell regression was not reproduced");
	}

	private static void checkPiece(int pieceIndex, String expectedName) {
		var def = BuildingDefs.piece(pieceIndex);
		require(def.name.equals(expectedName) && !def.canRotate && def.rotationAmount == 90, "Stair ID or rotation rule changed");
		var male = def.sockets.stream().filter(s -> s.male && !s.maleDummy).findFirst().orElseThrow();
		require(male.type == 6 && male.rotationDegrees == 90 && Math.abs(male.support - .7) < 1e-5, "Wrong stair attachment/support");
		for (int targetIndex : new int[] {0, 2}) {
			var target = BuildingDefs.piece(targetIndex);
			var female = target.sockets.stream().filter(s -> s.female && !s.femaleDummy && male.compatible(s)).findFirst().orElseThrow();
			for (float yaw : new float[] {0, -.8751361f, (float) Math.PI / 4, (float) -Math.PI / 2}) {
				var origin = new Vec3(16, 64, -20);
				var turn = new Quaternionf().rotationY(yaw);
				var point = turn.transform(new Vector3f(female.pos)).add((float) origin.x, (float) origin.y, (float) origin.z);
				var attachment = new Quaternionf(turn).mul(female.rot);
				var ray = turn.transform(new Vector3f(0, -.3f, -1)).normalize();
				var first = BuildingPlacement.doPlacement(male, female, point, attachment, ray, 0);
				for (int degrees : new int[] {0, 90, 180, 270}) {
					var pl = BuildingPlacement.doPlacement(male, female, point, attachment, ray, degrees);
					var rotation = new Quaternionf().rotationY(pl.yaw());
					var at = rotation.transform(new Vector3f(male.pos)).add((float) pl.pos().x, (float) pl.pos().y, (float) pl.pos().z);
					require(at.distance(point) < 1e-5, "Stairs miss the base socket: " + def.name);
					require(Math.abs(pl.pos().y - origin.y - .1) < 1e-5, "Stairs start at a different floor height: " + def.name);
					double delta = Math.atan2(Math.sin(first.yaw() - pl.yaw()), Math.cos(first.yaw() - pl.yaw()));
					require(Math.abs(Math.atan2(Math.sin(delta - Math.toRadians(degrees)), Math.cos(delta - Math.toRadians(degrees)))) < 1e-5, "Manual stair rotation is not a quarter turn");
					for (var check : def.placementChecks) for (var box : target.colliders) {
						var c = turn.transform(new Vector3f(box[0])).add((float) origin.x, (float) origin.y, (float) origin.z);
						require(!check.overlaps(pl.pos(), pl.yaw(), new Obb(new Vec3(c.x, c.y, c.z), box[1].x, box[1].y, box[1].z, yaw)), "The base blocks its correctly attached stairs");
					}
				}
			}
		}
		for (int index : new int[] {7, 8, 9, 10, 12, 13})
			require(BuildingDefs.piece(index).sockets.stream().noneMatch(s -> s.female && !s.femaleDummy && s.compatible(male)), "Stairs offered on a non-square base: " + def.name);
		require(def.placementChecks.stream().anyMatch(c -> c.overlaps(new Vec3(0, .1, 0), 0, new Obb(new Vec3(.7, 1.5, .7), .5, .1, .5, 0))), "Stairs ignore a floor across their landing");
		require(def.placementChecks.stream().anyMatch(c -> c.overlaps(new Vec3(0, .1, 0), 0, new Obb(new Vec3(0, 1.5, .7), .1, 1.5, 1.5, 0))), "Stairs ignore an intersecting wall");
	}
}
