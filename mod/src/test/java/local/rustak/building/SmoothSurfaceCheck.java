package local.rustak.building;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Continuous ground contacts, independent of the collision grid's row boundaries. */
public final class SmoothSurfaceCheck {
	private static int checks;
	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}
	private static AABB player(Vec3 feet, double height) {
		return new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + height, feet.z + 0.3);
	}
	private static Vec3 turn(Vec3 p, float yaw) {
		var box = new Obb(Vec3.ZERO, 0, 0, 0, yaw);
		var xz = box.toWorld(p.x, p.z);
		return new Vec3(xz[0], p.y, xz[1]);
	}
	private static List<Obb> slope(float yaw) {
		var boxes = new ArrayList<Obb>();
		for (int i = -15; i < 15; i++) {
			double x = (i + 0.5) * 0.1;
			boxes.add(new Obb(turn(new Vec3(x, x + 1 - 0.075, 0), yaw), 0.05, 0.125, 1.5, yaw,
				new Vec3(1, 0, 0.075), new Vec3(1, 0, -0.075)));
		}
		return boxes;
	}
	public static void main(String[] args) {
		for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) for (double height : new double[] {1.8, 0.9}) {
			var solids = slope(yaw);
			var start = turn(new Vec3(-0.7, 0, 0), yaw);
			var foot = player(start, height);
			double top = solids.stream().mapToDouble(o -> o.topAt(foot)).filter(Double::isFinite).max().orElseThrow();
			var box = foot.move(0, top, 0);
			for (int direction : new int[] {1, -1}) for (int tick = 0; tick < 30; tick++) {
				var wanted = turn(new Vec3(direction * 0.027, -0.08, 0), yaw);
				var moved = BuildingCollision.clip(box, wanted, true, 0.6, solids, b -> true);
				require(Math.abs(moved.y - direction * 0.027) < 1e-6, "Slope contact jumps at a grid boundary: " + yaw + " " + tick + " " + moved);
				require(moved.subtract(wanted).horizontalDistance() < 1e-6, "A continuous slope blocks horizontal movement");
				box = box.move(moved);
				require(BuildingCollision.supportedSlope(box, solids), "Downhill walking loses the ground contact");
				var body = box.deflate(1e-5);
				require(solids.stream().noneMatch(o -> BuildingCollision.intersects(o, body)), "Continuous contact puts the feet inside the slope");
			}
			var jumping = BuildingCollision.clip(box, new Vec3(0.03, 0.15, 0), false, 0.6, solids, b -> true);
			require(Math.abs(jumping.y - 0.15) < 1e-6, "Jumping receives slope adhesion");
		}
		var roof = slope(0);
		var ray = roof.get(7).raycast(new Vec3(-0.75, 3, 0), new Vec3(0, -1, 0), 4);
		require(Math.abs(ray - 2.75) < 1e-6, "Ray hits the grid envelope instead of the surface");
		var body = player(new Vec3(-0.7, 0.6, 0), 1.8);
		var wall = new Obb(new Vec3(-0.1, 1.5, 0), 0.1, 1.5, 1, 0);
		roof.add(wall);
		require(BuildingCollision.clip(body, new Vec3(0.4, -0.08, 0), true, 0.6, roof, b -> true).x < 0.21, "Slope adhesion crosses a wall");
		var blocked = BuildingCollision.clip(body, new Vec3(0.027, -0.08, 0), true, 0.6, slope(0), b -> b.maxY <= body.maxY + 0.01);
		require(blocked.x < 0.027 - 1e-5, "A slope pushes the head through a native ceiling");
		for (int index : new int[] {0, 7, 2, 8, 12, 13}) for (int grade = 0; grade < 5; grade++) {
			var solids = BuildingMovementCheck.solids(BuildingDefs.piece(index), grade, Vec3.ZERO, 0);
			for (var solid : solids) require(Math.abs(solid.center().y + solid.ey() - 0.1) < 1e-5, "Flat building surfaces use different walking levels: " + index + " " + grade);
		}
		for (int index : new int[] {9, 10}) for (int grade = 0; grade < 5; grade++) {
			var solids = BuildingMovementCheck.solids(BuildingDefs.piece(index), grade, Vec3.ZERO, 0);
			require(solids.stream().anyMatch(Obb::sloped), "Roof has no continuous surface metadata: " + index + " " + grade);
		}
		actualRoofs();
		fastEdgeCrossing();
		wallSliding();
		wallRelease();
		System.out.println("Continuous surface fixtures passed: " + checks);
	}

	private static void fastEdgeCrossing() {
		var solids = BuildingMovementCheck.solids(BuildingDefs.piece(9), 3, Vec3.ZERO, 0);
		var box = player(new Vec3(-2.195, 3.12135, 1.05), 1.8);
		var wanted = new Vec3(0, 0, .8);
		require(solids.stream().anyMatch(o -> BuildingCollision.intersects(o, box.move(0, 0, .4))), "Fast edge regression misses the actual roof");
		var done = BuildingCollision.clip(box, wanted, false, .6, solids, b -> true);
		require(done.z < .8 - 1e-5, "A fast airborne move crosses the roof's edge entirely");
	}

	private static void wallSliding() {
		for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) {
			var wall = new Obb(new Vec3(0, 1.5, 0), .1, 1.5, 1.5, yaw);
			double radius = .3 * (Math.abs(Math.cos(yaw)) + Math.abs(Math.sin(yaw)));
			var start = turn(new Vec3(-.1 - radius - .00001, .1, 0), yaw);
			var move = turn(new Vec3(.02, 0, .1), yaw);
			var done = BuildingCollision.clip(player(start, 1.8), move, false, .6, List.of(wall), b -> true);
			var local = wall.toLocal(done.x, done.z);
			require(Math.abs(local[1] - .1) < 1e-6, "Contact discards movement along a rotated wall: " + yaw + " " + done);
			require(local[0] < .000011, "Sliding moves through the wall");
			if (yaw > .7 && yaw < .8) {
				var box = player(start, 1.8);
				var nativeWall = new AABB(-1, 0, box.maxZ + .06, 1, 3, box.maxZ + .16);
				require(!nativeWall.intersects(box.move(move)), "Native slide fixture blocks the original direction");
				var stopped = BuildingCollision.clip(box, move, false, .6, List.of(wall), b -> !b.intersects(nativeWall));
				require(!nativeWall.intersects(box.move(stopped).deflate(1e-6)), "Wall sliding redirects the player into native blocks");
			}
		}
	}

	private static void actualRoofs() {
		for (int index : new int[] {9, 10}) for (int grade = 0; grade < 5; grade++)
			for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) for (double height : new double[] {1.8, 0.9}) {
				Vec3 origin = new Vec3(16, 64, -20);
				var solids = BuildingMovementCheck.solids(BuildingDefs.piece(index), grade, origin, yaw);
				var start = origin.add(turn(new Vec3(0, 0, -.5), yaw));
				var footprint = player(start, height);
				var column = footprint.expandTowards(0, 10, 0).expandTowards(0, -10, 0);
				double top = solids.stream().filter(o -> BuildingCollision.intersects(o, column)).mapToDouble(o -> o.topAt(footprint)).max().orElseThrow();
				var box = footprint.move(0, top - footprint.minY, 0);
				for (int direction : new int[] {1, -1}) for (int tick = 0; tick < 24; tick++) {
					var wanted = turn(new Vec3(0, -.08, direction * .027), yaw);
					var moved = BuildingCollision.clip(box, wanted, true, .6, solids, b -> true);
					double expected = direction * .027 * (index == 9 ? 1 : 2 / Math.sqrt(3));
					require(Math.abs(moved.y - expected) < .001, "An actual roof still hops at a cell boundary: " + index + " " + grade + " " + yaw + " " + tick + " " + moved);
					box = box.move(moved);
					require(BuildingCollision.supportedSlope(box, solids), "Actual roof walking loses ground contact");
				}
			}
	}

	private static void wallRelease() {
		for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) for (double height : new double[] {1.8, .9}) {
			var origin = new Vec3(16, 64, -20);
			var wall = new Obb(origin.add(0, 1.5, 0), .1, 1.5, 3, yaw);
			double halfWidth = .6F / 2;
			double radius = halfWidth * (Math.abs(Math.cos(yaw)) + Math.abs(Math.sin(yaw)));
			var centre = origin.add(turn(new Vec3(-.1 - radius - .02, .1, 0), yaw));
			var box = new AABB(centre.x - halfWidth, centre.y, centre.z - halfWidth,
				centre.x + halfWidth, centre.y + height, centre.z + halfWidth);
			for (int tick = 0; tick < 40; tick++) {
				var rub = turn(new Vec3(.04, 0, tick / 10 % 2 == 0 ? .025 : -.025), yaw);
				var moved = BuildingCollision.clip(box, rub, false, .6, List.of(wall), b -> true);
				box = box.move(moved);
				require(!BuildingCollision.intersects(wall, box.deflate(1e-6)), "Rubbing a wall penetrates it");
			}
			var away = turn(new Vec3(-.2, 0, 0), yaw);
			var moved = BuildingCollision.clip(box, away, false, .6, List.of(wall), b -> true);
			require(moved.distanceTo(away) < 1e-6, "Rubbing traps the player against the wall: " + yaw + " " + moved);
		}
		for (double overlap : new double[] {1e-14, 1e-10, 5e-8, 1e-7, 1e-6}) {
			var wall = new Obb(new Vec3(0, 1.5, 0), .1, 1.5, 3, 0);
			var box = player(new Vec3(-.4 + overlap, .1, 0), 1.8);
			var away = new Vec3(-.2, 0, 0);
			var moved = BuildingCollision.clip(box, away, false, .6, List.of(wall), b -> true);
			require(moved.distanceTo(away) < 1e-6, "A shallow wall contact forbids moving outward: " + overlap + " " + moved);
			require(BuildingCollision.clip(box, new Vec3(.2, 0, 0), false, .6, List.of(wall), b -> true).x < 1e-6,
				"A shallow contact allows deeper wall penetration");
		}
	}
}
