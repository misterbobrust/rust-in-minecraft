package local.rustak.building;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Side support after removal of the lower half wall, at straight joins and corners. */
public final class StabilityRulesCheck {
	private static int checks;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	private static BuildingDefs.Socket socket(int piece, String suffix) {
		return BuildingDefs.piece(piece).sockets.stream().filter(s -> s.name.endsWith("/" + suffix)).findFirst().orElseThrow();
	}

	private static Stability.WorldSocket at(BuildingDefs.Socket s, Vec3 pos, float yaw) {
		var rot = new Quaternionf().rotationY(yaw);
		var point = rot.transform(new Vector3f(s.pos)).add((float) pos.x, (float) pos.y, (float) pos.z);
		return new Stability.WorldSocket(s, point, new Quaternionf(rot).mul(s.rot));
	}

	public static void main(String[] args) {
		var halfSide = socket(5, "stability/1");
		var fullSide = socket(1, "stability/2");
		var otherHalfSide = socket(5, "stability/2");
		for (float yaw : new float[] {0, (float) Math.PI / 3, (float) -Math.PI / 2}) {
			var origin = new Vec3(8, 64, -12);
			var upper = at(halfSide, origin.add(0, 1.5, 0), yaw);
			var offset = new Quaternionf().rotationY(yaw).transform(new Vector3f(0, 0, -3));
			var adjacentOrigin = origin.add(offset.x, offset.y, offset.z);
			var adjacent = at(fullSide, adjacentOrigin, yaw);
			require(upper.pos().distance(adjacent.pos()) > 0.3f, "Fixture must reproduce the old distance rejection");
			require(Stability.connects(upper, adjacent), "Upper half wall loses side support from a full wall when the lower half is removed");
			require(Stability.connects(adjacent, upper), "Side support does not propagate back across a mixed-height join");
			require(!Stability.connects(upper, at(otherHalfSide, adjacentOrigin, yaw)), "A half wall below the join falsely supports the upper half wall");
			require(Stability.connects(upper, at(otherHalfSide, adjacentOrigin.add(0, 1.5, 0), yaw)), "Adjacent upper half walls lose their same-height join");
			var gap = new Quaternionf().rotationY(yaw).transform(new Vector3f(0.2f, 0, 0));
			var separated = at(otherHalfSide, adjacentOrigin.add(gap.x, 1.5, gap.z), yaw);
			require(upper.pos().distance(separated.pos()) < 0.3f, "Gap fixture must expose the old false connection");
			require(!Stability.connects(upper, separated), "Non-touching half walls falsely pass side support across a gap");
			for (float turn : new float[] {(float) Math.PI / 2, (float) -Math.PI / 2}) {
				var end = new Quaternionf().rotationY(yaw + turn).transform(new Vector3f(fullSide.pos));
				var cornerOrigin = new Vec3(upper.pos().x - end.x, origin.y, upper.pos().z - end.z);
				require(Stability.connects(upper, at(fullSide, cornerOrigin, yaw + turn)), "Upper half wall loses corner support from a perpendicular full wall");
			}
		}
		var base = socket(5, "wall-male");
		var top = socket(5, "wall-female");
		require(Stability.connects(at(base, new Vec3(0, 1.5, 0), 0), at(top, Vec3.ZERO, 0)), "Ordinary half-wall stacking loses its base support");
		require(!Stability.connects(at(halfSide, Vec3.ZERO, 0), at(base, new Vec3(halfSide.pos.x, halfSide.pos.y, halfSide.pos.z), 0)), "Different attachment kinds exchange support");
		stairSupport(15);
		stairSupport(16);
		System.out.println("Stability fixtures passed: " + checks);
	}

	private static void stairSupport(int piece) {
		var def = BuildingDefs.piece(piece);
		var male = def.sockets.stream().filter(s -> s.male && !s.maleDummy).findFirst().orElseThrow();
		require(Math.abs(male.support - .7f) < 1e-5, "Stair support factor changed");
		require(def.sockets.stream().filter(s -> s.maleDummy).allMatch(s -> s.support == 0), "Dummy attachments add stair support");
		for (int index : new int[] {0, 2}) {
			var female = BuildingDefs.piece(index).sockets.stream().filter(s -> s.type == 6 && s.female).findFirst().orElseThrow();
			for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) for (int degrees : new int[] {0, 90, 180, 270}) {
				var origin = new Vec3(16, 64, -20);
				var supporter = at(female, origin, yaw);
				var stairs = at(male, origin.add(0, .1, 0), yaw + (float) Math.toRadians(degrees));
				require(Stability.connects(stairs, supporter), "A rotated stair flight loses its base support");
				require(!Stability.connects(stairs, at(female, origin.add(3, 0, 0), yaw)), "Another base keeps unsupported stairs floating");
			}
		}
	}
}
