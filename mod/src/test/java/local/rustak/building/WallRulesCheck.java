package local.rustak.building;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Regression fixtures for sloped walls, intermediate floors and obscured attachments. */
public final class WallRulesCheck {
	private static int checks;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	private static boolean condition(String kind, Map<String, List<RoofShape.Link>> links) {
		return RoofShape.tests(JsonParser.parseString("[{\"kind\":\"" + kind + "\"}]").getAsJsonArray(), links, 2, 0);
	}

	public static void main(String[] args) {
		conditions();
		layouts();
		lowCorners();
		clearance();
		sight();
		System.out.println("Wall shape/clearance/visibility fixtures passed: " + checks);
	}

	private static void conditions() {
		for (boolean right : new boolean[] {false, true}) for (String piece : new String[] {"roof", "roof.triangle"}) {
			String kind = right ? "wall_cut_right" : "wall_cut_left";
			float angle = right ? (float) Math.PI : 0;
			var links = new HashMap<String, List<RoofShape.Link>>();
			links.put("neighbour/1", List.of(new RoofShape.Link(0, angle, piece, piece + "/sockets/neighbour/1")));
			require(condition(kind, links), "Wall cut must work across grades");
			require(!condition("wall_full", links), "Full wall still selected alongside cut wall");
			for (String blocker : new String[] {"wall-female", "floor-female/1", "floor-female/2", "floor-female/3", "floor-female/4", right ? "stability/2" : "stability/1"}) {
				links.put(blocker, List.of(new RoofShape.Link(2, 0, "wall", "wall/sockets/wall-male")));
				require(!condition(kind, links) && condition("wall_full", links), "Occupied " + blocker + " failed to preserve full wall");
				links.remove(blocker);
			}
			int limit = piece.equals("roof") ? 10 : 40;
			for (int offset : new int[] {limit - 1, limit + 1}) {
				links.put("neighbour/1", List.of(new RoofShape.Link(4, angle + (float) Math.toRadians(offset), piece, piece + "/sockets/neighbour/1")));
				require(condition(kind, links) == (offset < limit), "Wrong wall cut angle threshold");
			}
		}
		for (boolean right : new boolean[] {false, true}) {
			String socket = right ? "stability/1" : "stability/2", opposite = right ? "stability/2" : "stability/1";
			String kind = right ? "wall_corner_right" : "wall_corner_left";
			float turn = (float) (right ? Math.PI / 2 : -Math.PI / 2);
			var good = new RoofShape.Link(2, turn, "wall", "wall/sockets/" + opposite);
			require(condition(kind, Map.of(socket, List.of(good))), "Missing joined wall corner");
			require(!condition(kind, Map.of(socket, List.of(new RoofShape.Link(0, turn, "wall", good.socket())))), "Corner ignores grade mismatch");
			require(!condition(kind, Map.of(socket, List.of(good, new RoofShape.Link(2, 0, "wall", good.socket())))), "Additional straight wall must cancel corner");
			require(!condition(kind, Map.of(socket, List.of(new RoofShape.Link(2, 0, "wall", "wall/sockets/" + socket)))), "Invalid same-end corner");
		}
	}

	private static void layouts() {
		var wall = BuildingDefs.piece(1);
		for (int grade = 0; grade < 5; grade++) {
			long full = RoofShape.layoutMask(wall, Vec3.ZERO, 0, grade, List.of());
			require(full == 1, "Standalone wall should select its full section");
			for (boolean right : new boolean[] {false, true}) {
				var roof = new RoofShape.Candidate(BuildingDefs.piece(9), new Vec3(1.5, 0, 0), right ? (float) Math.PI : 0, 4 - grade);
				long cut = RoofShape.layoutMask(wall, Vec3.ZERO, 0, grade, List.of(roof));
				require(cut == (right ? 4 : 2), "Wrong diagonal wall in real layout: " + grade + " " + right + " " + cut);
				var upper = new RoofShape.Candidate(wall, new Vec3(0, 3, 0), 0, grade);
				require(RoofShape.layoutMask(wall, Vec3.ZERO, 0, grade, List.of(roof, upper)) == full, "Upper wall must prevent cut");
				var adjacent = new RoofShape.Candidate(wall, new Vec3(0, 0, right ? 3 : -3), 0, grade);
				require(RoofShape.layoutMask(wall, Vec3.ZERO, 0, grade, List.of(roof, adjacent)) == full, "Adjacent wall must prevent cut");
				for (String name : new String[] {"floor-female/1", "floor-female/3"}) {
					var female = wall.sockets.stream().filter(s -> s.name.endsWith("/" + name)).findFirst().orElseThrow();
					var floor = BuildingDefs.piece(2);
					var male = floor.sockets.stream().filter(s -> s.male && !s.maleDummy && s.compatible(female)).findFirst().orElseThrow();
					var rot = new Quaternionf(female.rot).mul(new Quaternionf(male.rot).invert());
					var offset = rot.transform(new Vector3f(male.pos));
					var forward = rot.transform(new Vector3f(0, 0, 1));
					var at = new Vec3(female.pos.x - offset.x, female.pos.y - offset.y, female.pos.z - offset.z);
					var placed = new RoofShape.Candidate(floor, at, (float) Math.atan2(forward.x, forward.z), grade);
					require(RoofShape.layoutMask(wall, Vec3.ZERO, 0, grade, List.of(roof, placed)) == full, "Attached upper/intermediate floor must prevent cut");
				}
				var section = wall.grades[grade].sections.get(right ? 2 : 1);
				Vec3 removed = new Vec3(0, 2.4, right ? 1.2 : -1.2), retained = new Vec3(0, 2.4, right ? -1.2 : 1.2);
				require(section.colliders.stream().noneMatch(b -> box(b, Vec3.ZERO, 0).contains(removed, 0)), "Removed triangle still collides");
				require(section.colliders.stream().anyMatch(b -> box(b, Vec3.ZERO, 0).contains(retained, 0)), "Retained triangle lost collision");
			}
			var triangle = BuildingDefs.piece(10);
			var neighbour = triangle.sockets.stream().filter(s -> s.name.endsWith("/neighbour/1")).findFirst().orElseThrow();
			var at = new Vec3(-neighbour.pos.x, 1.5 - neighbour.pos.y, -neighbour.pos.z);
			require(RoofShape.layoutMask(wall, Vec3.ZERO, 0, grade, List.of(new RoofShape.Candidate(triangle, at, 0, grade))) == 2, "Triangle roof must cut adjacent wall");
		}
	}

	private static void clearance() {
		var wall = BuildingDefs.piece(1);
		require(wall.placementChecks.stream().filter(c -> c.sphere).count() == 2, "Missing wall clearance spheres");
		require(wall.placementChecks.stream().filter(c -> !c.sphere).allMatch(c -> !c.blocks("wall") && c.blocksWorld), "World volume wrongly blocks building attachments");
		for (float yaw : new float[] {0, (float) Math.PI / 3, (float) -Math.PI / 2}) for (int grade = 0; grade < 5; grade++) {
			var existing = wall.grades[grade].sections.getFirst().colliders;
			boolean overlap = wall.placementChecks.stream().filter(c -> c.blocks("wall")).anyMatch(c -> existing.stream().anyMatch(b -> c.overlaps(new Vec3(0, 1.5, 0), yaw, box(b, Vec3.ZERO, yaw))));
			require(overlap, "Full wall at intermediate floor height overlaps existing wall");
			for (var check : wall.placementChecks) if (check.blocks("wall")) for (var b : existing)
				require(!check.overlaps(new Vec3(0, 3, 0), yaw, box(b, Vec3.ZERO, yaw)), "Normal stacked wall is blocked");
		}
		var own = new RoofShape.Candidate(wall, Vec3.ZERO, 0, 0);
		require(PlacementRules.tooClose(own, new RoofShape.Candidate(wall, new Vec3(0.5, 0.5, 0), 0, 0)), "Disconnected nearby wall should fail proximity");
		require(!PlacementRules.tooClose(own, new RoofShape.Candidate(wall, new Vec3(0, 3, 0), 0, 0)), "Connected wall should bypass proximity");
	}

	private static void lowCorners() {
		var low = BuildingDefs.piece(11);
		for (int grade = 0; grade < 5; grade++) {
			require(RoofShape.layoutMask(low, Vec3.ZERO, 0, grade, List.of()) == 1, "Standalone low wall lost its base section");
			for (boolean right : new boolean[] {false, true}) {
				var mine = low.sockets.stream().filter(s -> s.name.endsWith(right ? "/stability/1" : "/stability/2")).findFirst().orElseThrow();
				var other = low.sockets.stream().filter(s -> s.name.endsWith(right ? "/stability/2" : "/stability/1")).findFirst().orElseThrow();
				float turn = (float) (right ? Math.PI / 2 : -Math.PI / 2);
				var offset = new Quaternionf().rotationY(turn).transform(new Vector3f(other.pos));
				Vec3 at = new Vec3(mine.pos.x - offset.x, mine.pos.y - offset.y, mine.pos.z - offset.z);
				long joined = RoofShape.layoutMask(low, Vec3.ZERO, 0, grade, List.of(new RoofShape.Candidate(low, at, turn, grade)));
				require(joined == (grade == 0 ? 1 : right ? 5 : 3), "Incorrect low wall corner section: " + grade + " " + right + " " + joined);
				require(RoofShape.layoutMask(low, Vec3.ZERO, 0, grade, List.of(new RoofShape.Candidate(low, at, turn, (grade + 1) % 5))) == 1, "Low corner ignores grade mismatch");
			}
		}
	}

	private static void sight() {
		var roof = BuildingDefs.piece(9);
		Vec3 eye = new Vec3(3, 1.5, 0), pos = new Vec3(2, 0, 0), socket = new Vec3(-2, 0, 0);
		var wall = new Obb(new Vec3(0, 1.5, 0), 0.15, 1.5, 1.5, 0);
		require(!clear(eye, socket, 0.5, wall), "Fixture must obscure the main attachment path");
		require(PlacementRules.sightPaths(roof, pos, 0, socket, (p, padding) -> clear(eye, p, padding, wall)), "Visible roof alternate point should allow placement");
		require(!PlacementRules.sightPaths(roof, new Vec3(-2, 0, 0), 0, socket, (p, padding) -> clear(eye, p, padding, wall)), "All obscured paths should reject placement");
		require(PlacementRules.sightPaths(roof, pos, 0, Vec3.ZERO, (p, padding) -> clear(eye, p, padding, wall)), "Socket padding should allow an attachment on a wall face");
	}

	private static boolean clear(Vec3 eye, Vec3 point, double padding, Obb obstacle) {
		Vec3 delta = point.subtract(eye);
		double hit = obstacle.raycast(eye, delta.normalize(), delta.length());
		return hit < 0 || hit >= delta.length() - padding - 1e-6;
	}

	private static Obb box(Vector3f[] b, Vec3 pos, float yaw) {
		var c = new Quaternionf().rotationY(yaw).transform(new Vector3f(b[0]));
		return new Obb(pos.add(c.x, c.y, c.z), b[1].x, b[1].y, b[1].z, yaw);
	}
}
