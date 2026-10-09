package local.rustak.building;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Walk both flights through the turnaround and onto the neighbouring upper floor. */
public final class UStairsMovementCheck {
	private static int checks;
	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}
	private static Vec3 turn(Vec3 pos, float yaw) {
		var v = new Quaternionf().rotationY(yaw).transform(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
		return new Vec3(v.x, v.y, v.z);
	}
	private static AABB body(Vec3 feet, double height) {
		return new AABB(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + height, feet.z + .3);
	}
	public static void main(String[] args) {
		var def = BuildingDefs.piece(16);
		for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) for (double height : new double[] {1.8, .9}) {
			Vec3 origin = new Vec3(16, 64, -20);
			var stairs = BuildingMovementCheck.solids(def, grade, origin.add(0, .1, 0), yaw);
			require(stairs.stream().anyMatch(Obb::sloped), "U stairs lost their continuous flights");
			var solids = BuildingMovementCheck.solids(BuildingDefs.piece(0), grade, origin, yaw);
			solids.addAll(stairs);
			solids.addAll(BuildingMovementCheck.solids(BuildingDefs.piece(2), grade, origin.add(turn(new Vec3(0, 0, -3), yaw)), yaw));
			solids.addAll(BuildingMovementCheck.solids(BuildingDefs.piece(2), grade, origin.add(turn(new Vec3(-3, 3, 0), yaw)), yaw));
			var box = body(origin.add(turn(new Vec3(.7, .1, -2.1), yaw)), height);
			boolean grounded = true;
			var route = List.of(new Vec3(0, -.08, .1), new Vec3(-.1, -.08, 0), new Vec3(0, -.08, -.1), new Vec3(-.1, -.08, 0),
				new Vec3(.1, -.08, 0), new Vec3(0, -.08, .1), new Vec3(.1, -.08, 0), new Vec3(0, -.08, -.1));
			int[] lengths = {29, 14, 21, 14, 14, 21, 14, 29};
			for (int segment = 0; segment < route.size(); segment++) {
				var localMove = route.get(segment);
				for (int tick = 0; tick < lengths[segment]; tick++) {
					var wanted = turn(localMove, yaw);
					var moved = BuildingCollision.clip(box, wanted, grounded, .6, solids, b -> true);
					require(moved.subtract(wanted).horizontalDistance() < 1e-5, "U stair flight/turnaround blocks walking: " + grade + " " + yaw + " " + height + " " + segment + " " + tick + " " + moved + " feet=" + box.minY + " body=" + box);
					require(moved.y <= .6 + 1e-5, "U stairs exceed the regular step height");
					box = box.move(moved);
					var destination = box.deflate(1e-5);
					require(solids.stream().noneMatch(o -> BuildingCollision.intersects(o, destination)), "U stair walking enters a collider");
					grounded = moved.y > wanted.y + 1e-6;
				}
				if (segment == 3) require(Math.abs(box.minY - origin.y - 3.1) < .025, "U stairs fail to reach the upper floor");
			}
			for (int tick = 0; tick < 12; tick++) box = box.move(BuildingCollision.clip(box, new Vec3(0, -.08, 0), false, .6, solids, b -> true));
			require(Math.abs(box.minY - origin.y - .1) < .09, "Descending U stairs fails to return to the base");
			var under = body(origin.add(turn(new Vec3(-.7, .1, -2.1), yaw)), height);
			for (int tick = 0; tick < (height > 1 ? 10 : 13); tick++) {
				var wanted = turn(new Vec3(0, -.08, .1), yaw);
				var moved = BuildingCollision.clip(under, wanted, true, .6, solids, b -> true);
				require(moved.subtract(wanted).horizontalDistance() < 1e-5 && Math.abs(moved.y) < 1e-5, "U stairs fill the usable space under the upper flight: " + grade + " " + yaw + " " + height + " " + tick + " " + moved + " body=" + under);
				under = under.move(moved);
			}
		}
		headroom();
		System.out.println("U-stair movement fixtures passed: " + checks);
	}

	private static void headroom() {
		float yaw = (float) Math.PI / 4;
		Vec3 origin = new Vec3(16, 64, -20);
		var solids = BuildingMovementCheck.solids(BuildingDefs.piece(16), 0, origin.add(0, .1, 0), yaw);
		solids.addAll(BuildingMovementCheck.solids(BuildingDefs.piece(2), 0, origin.add(turn(new Vec3(0, 3, -3), yaw)), yaw));
		var feet = origin.add(turn(new Vec3(.7, 1.06434996, -1.1), yaw));
		var wanted = turn(new Vec3(0, -.08, .1), yaw);
		var standing = BuildingCollision.clip(body(feet, 1.8), wanted, true, .6, solids, b -> true);
		require(standing.horizontalDistance() < .02, "U stairs push a standing player's head through the upper-floor overhang");
		var crouching = BuildingCollision.clip(body(feet, .9), wanted, true, .6, solids, b -> true);
		require(crouching.subtract(wanted).horizontalDistance() < 1e-5, "A crouching player cannot clear the same overhang");
	}
}
