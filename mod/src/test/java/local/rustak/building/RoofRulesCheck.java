package local.rustak.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Fixtures for exposed edges, grade changes, ridges and mirrored convex/concave joints. */
public final class RoofRulesCheck {
	private static int checks;

	private static void check(boolean expected, String json, Map<String, List<RoofShape.Link>> links) {
		JsonArray tests = JsonParser.parseString("[" + json + "]").getAsJsonArray();
		if (RoofShape.tests(tests, links, 2, 0) != expected) throw new AssertionError(json + " " + links);
		checks++;
	}

	private static RoofShape.Link link(int grade, double yaw, String piece, int socket) {
		return new RoofShape.Link(grade, (float) Math.toRadians(yaw), piece, piece + "/sockets/neighbour/" + socket);
	}

	public static void main(String[] args) {
		local.rustak.RustSprintCheck.main(args);
		var empty = new HashMap<String, List<RoofShape.Link>>();
		for (int i = 1; i <= 6; i++) empty.put("neighbour/" + i, List.of());
		check(true, "{\"kind\":\"left\",\"angle\":-1,\"shape\":-1}", empty);
		check(true, "{\"kind\":\"right\",\"angle\":-1,\"shape\":-1}", empty);
		check(true, "{\"kind\":\"top\"}", empty);
		check(true, "{\"kind\":\"bottom\"}", empty);
		check(false, "{\"kind\":\"top\"}", Map.of());
		var left = new HashMap<>(empty);
		left.put("neighbour/4", List.of(link(2, -90, "roof", 3)));
		check(true, "{\"kind\":\"left\",\"angle\":90,\"shape\":0}", left);
		check(false, "{\"kind\":\"left\",\"angle\":-90,\"shape\":0}", left);
		check(false, "{\"kind\":\"left\",\"angle\":90,\"shape\":1}", left);
		check(false, "{\"kind\":\"left\",\"angle\":-1,\"shape\":-1}", left);
		left.put("neighbour/4", List.of(link(1, -90, "roof", 3)));
		check(false, "{\"kind\":\"left\",\"angle\":90,\"shape\":0}", left);
		check(false, "{\"kind\":\"left\",\"angle\":-1,\"shape\":-1}", left);
		left.put("neighbour/4", List.of(link(2, -90, "roof", 3), link(2, 0, "roof", 3)));
		check(false, "{\"kind\":\"left\",\"angle\":90,\"shape\":0}", left);
		left.put("neighbour/4", List.of(link(2, 60, "roof.triangle", 3), link(2, 0, "roof.triangle", 3)));
		check(true, "{\"kind\":\"left\",\"angle\":-60,\"shape\":1}", left);
		var right = new HashMap<>(empty);
		right.put("neighbour/3", List.of(link(2, 90, "roof", 4)));
		check(true, "{\"kind\":\"right\",\"angle\":90,\"shape\":0}", right);
		check(false, "{\"kind\":\"right\",\"angle\":-90,\"shape\":0}", right);
		var ridge = new HashMap<>(empty);
		ridge.put("neighbour/5", List.of(link(1, 0, "roof", 3)));
		check(true, "{\"kind\":\"top\"}", ridge);
		ridge.put("neighbour/6", List.of(link(1, 0, "roof", 4)));
		check(false, "{\"kind\":\"top\"}", ridge);
		check(true, "{\"kind\":\"empty_wall\"}", empty);
		empty.put("wall-female", List.of(new RoofShape.Link(0, 0, "wall", "wall/sockets/wall-male")));
		check(false, "{\"kind\":\"empty_wall\"}", empty);
		check(false, "{\"kind\":\"not\",\"tests\":[{\"kind\":\"constant\",\"value\":true}]}", empty);
		check(true, "{\"kind\":\"all\",\"tests\":[{\"kind\":\"constant\",\"value\":true}]}", empty);
		if (RoofRulesCheck.class.getResource("/assets/rustak/building/roof.json") != null) {
			realLayouts();
			WallRulesCheck.main(args);
			StabilityRulesCheck.main(args);
			FoundationEdgeCheck.main(args);
			BuildingMovementCheck.main(args);
			SmoothSurfaceCheck.main(args);
			LStairsMovementCheck.main(args);
			UStairsMovementCheck.main(args);
		}
		System.out.println("Roof joint fixtures passed: " + checks);
	}

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	private static void realLayouts() {
		require(BuildingDefs.PIECES.length == 17 && BuildingDefs.PIECES[7].equals("foundation.triangle") && BuildingDefs.PIECES[14].equals("foundation.steps") && BuildingDefs.PIECES[15].equals("stairs.l") && BuildingDefs.PIECES[16].equals("stairs.u"), "Saved piece IDs moved");
		for (int piece : new int[] {9, 10}) for (int grade = 0; grade < 5; grade++) {
			var def = BuildingDefs.piece(piece);
			long standalone = RoofShape.layoutMask(def, Vec3.ZERO, 0, grade, List.of());
			require(standalone != 0, "Empty standalone roof");
			var mine = def.sockets.stream().filter(s -> s.name.endsWith("/neighbour/4")).findFirst().orElseThrow();
			var other = def.sockets.stream().filter(s -> s.name.endsWith("/neighbour/3")).findFirst().orElseThrow();
			float turn = (float) -Math.PI / 2;
			Vector3f off = new Quaternionf().rotationY(turn).transform(new Vector3f(other.pos));
			Vec3 at = new Vec3(mine.pos.x - off.x, mine.pos.y - off.y, mine.pos.z - off.z);
			var neighbour = new RoofShape.Candidate(def, at, turn, grade);
			long joined = RoofShape.layoutMask(def, Vec3.ZERO, 0, grade, List.of(neighbour));
			require(joined != standalone, "A joined side still has its exposed edge");
			require(RoofShape.joint(new RoofShape.Candidate(def, Vec3.ZERO, 0, grade), neighbour), "Corner link was missed");
			long distant = RoofShape.layoutMask(def, Vec3.ZERO, 0, grade, List.of(new RoofShape.Candidate(def, at.add(10, 0, 0), turn, grade)));
			require(distant == standalone, "Distant roof changes shape");
		}
	}
}
