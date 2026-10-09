package local.rustak.client.building;

import java.util.List;
import local.rustak.building.BuildingCollision;
import local.rustak.building.Obb;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Settled debris shrinks promptly, airborne debris expires, and a collapse cannot allocate unlimited pieces. */
public final class GibDynamicsCheck {
	private static int checks;
	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) {
		for (float variation : new float[] {0, .25f, .5f, .75f, 1}) {
			float life = GibDynamics.lifetime(variation), duration = GibDynamics.cleanupDuration(variation);
			float airborneStart = GibDynamics.cleanupStart(life, -1, variation);
			require(GibDynamics.scale(0, airborneStart, duration) == 1, "Debris is pre-shrunk when spawned");
			require(GibDynamics.scale(life, airborneStart, duration) < 1e-6, "Airborne debris survives its deadline");
			float settledStart = GibDynamics.cleanupStart(life, 1, variation);
			require(settledStart < 2, "Settled large fragments linger at full size");
			require(GibDynamics.scale(4, settledStart, duration) < 1e-6, "Settled debris still exists three seconds after contact");
			float previous = 1;
			for (int tick = 0; tick <= 160; tick++) {
				float scale = GibDynamics.scale(tick * .05f, settledStart, duration);
				require(scale >= 0 && scale <= previous + 1e-6, "Cleanup grows or reverses");
				previous = scale;
			}
		}
		for (double friction : new double[] {.25, .3, .4}) {
			double speed = 2;
			for (int i = 0; i < 40; i++) speed = GibDynamics.frictionSpeed(speed, friction, .05);
			require(speed == 0, "Material friction leaves settled debris sliding indefinitely");
		}
		for (int total : new int[] {4, 62, 192, 270, 1000}) {
			int emitted = Math.min(total, GibDynamics.MAX_PIECES), previous = -1;
			for (int i = 0; i < emitted; i++) {
				int index = GibDynamics.sampleIndex(i, total, emitted);
				require(index > previous && index < total, "Collapse sampling duplicates or overruns a fragment");
				previous = index;
			}
			require(previous >= total * .9 || total <= 4, "Collapse sampling loses the final visible sections");
		}
		collisionContacts();
		System.out.println("Debris dynamics fixtures passed: " + checks);
	}

	private static AABB fragment(Vec3 centre, double width, double height, double depth) {
		return new AABB(centre.x - width / 2, centre.y - height / 2, centre.z - depth / 2,
			centre.x + width / 2, centre.y + height / 2, centre.z + depth / 2);
	}

	private static void collisionContacts() {
		var rotatedFloor = new Obb(Vec3.ZERO, 1.5, .1, 1.5, (float) Math.PI / 4);
		var outside = fragment(new Vec3(1.9, .15, 1.9), .1, .1, .1);
		var down = new Vec3(0, -.02, 0);
		require(rotatedFloor.bounds().intersects(outside.move(down)), "Fixture misses the old false ground envelope");
		require(!BuildingCollision.intersects(rotatedFloor, outside.move(down)), "Outside fragment actually touches the floor");
		require(GibSystem.clipBuildings(outside, down, List.of(rotatedFloor)).distanceTo(down) < 1e-8,
			"A fragment sleeps on invented ground outside the rotated floor");

		for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) {
			var floor = new Obb(Vec3.ZERO, 1.5, .1, 1.5, yaw);
			var inside = fragment(new Vec3(0, .16, 0), .1, .1, .3);
			var landed = GibSystem.clipBuildings(inside, down, List.of(floor));
			require(Math.abs(landed.y + .01) < 1e-8 && landed.horizontalDistance() < 1e-8,
				"A rectangular fragment fails to land on the actual floor top");
			require(Math.abs(inside.move(landed).minY - .1) < 1e-8, "Floor landing leaves a gap or penetration");
			require(GibSystem.clipBuildings(inside.move(landed), down, List.of(floor)).lengthSqr() < 1e-12,
				"Sleep probe loses real floor support");

			var roof = new Obb(new Vec3(0, 1, 0), .05, .125, 1.5, yaw,
				new Vec3(1, 0, .075), new Vec3(1, 0, -.075));
			var xz = roof.toWorld(-.02, 0);
			var falling = fragment(new Vec3(xz[0], 1.25, xz[1]), .02, .1, .08);
			double surface = 1.075 - .02 + Math.abs(Math.cos(yaw)) * .01 + Math.abs(Math.sin(yaw)) * .04;
			var contact = GibSystem.clipBuildings(falling, new Vec3(0, -.2, 0), List.of(roof));
			require(Math.abs(falling.minY + contact.y - surface) < 1e-8,
				"Debris lands on a pitched roof's grid envelope instead of its surface");
			require(roof.bounds().maxY - surface > .02, "Slope fixture cannot distinguish the raster envelope");
			require(GibSystem.clipBuildings(falling.move(contact), down, List.of(roof)).lengthSqr() < 1e-12,
				"Sleep probe loses the continuous roof surface");
		}

		var obstacle = new Obb(new Vec3(0, .5, 0), .1, .5, 1.5, 0);
		var travelling = fragment(new Vec3(-1, .5, 0), .1, .1, .4);
		var blocked = GibSystem.clipBuildings(travelling, new Vec3(2, 0, 0), List.of(obstacle));
		require(Math.abs(blocked.x - .85) < 1e-8 && blocked.y == 0 && blocked.z == 0,
			"A fast fragment crosses an obstacle whose final footprint is clear");
		require(!BuildingCollision.intersects(obstacle, travelling.move(blocked).deflate(1e-6)),
			"Obstacle contact leaves debris penetrating the wall");

		var acrossDepth = new Obb(new Vec3(0, .5, 0), 1.5, .5, .1, 0);
		var wideFragment = new AABB(-.2, .45, -1.05, .2, .55, -.95);
		var clearMove = new Vec3(0, 0, .8);
		require(GibSystem.clipBuildings(wideFragment, clearMove, List.of(acrossDepth)).distanceTo(clearMove) < 1e-8,
			"A wide fragment's X size invents an early Z-axis collision");
		var longFragment = fragment(new Vec3(0, .5, -1), .1, .1, .4);
		var depthBlocked = GibSystem.clipBuildings(longFragment, new Vec3(0, 0, 2), List.of(acrossDepth));
		require(Math.abs(depthBlocked.z - .7) < 1e-8 && depthBlocked.x == 0 && depthBlocked.y == 0,
			"Obstacle sweep ignores a rectangular fragment's longer axis");
		require(!BuildingCollision.intersects(acrossDepth, longFragment.move(depthBlocked).deflate(1e-6)),
			"Rectangular fragment penetrates an obstacle along its depth");
	}
}
