package local.rustak.building;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Walking transitions and edge rules on real building geometry. */
public final class BuildingMovementCheck {
	private static int checks;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	static List<Obb> solids(BuildingDefs.Piece def, int grade, Vec3 pos, float yaw) {
		List<Obb> out = new ArrayList<>();
		var rotation = new Quaternionf().rotationY(yaw);
		List<Vector3f[]> data = new ArrayList<>();
		if (def.grades[grade].sections.isEmpty()) data.addAll(def.colliders);
		else {
			long mask = RoofShape.layoutMask(def, pos, yaw, grade, List.of());
			for (int i = 0; i < def.grades[grade].sections.size(); i++) if ((mask & (1L << i)) != 0) data.addAll(def.grades[grade].sections.get(i).colliders);
		}
		for (var b : data) out.add(Obb.at(b, pos, yaw));
		return out;
	}

	private static AABB player(double x, double feet, double z, double height) {
		return new AABB(x - 0.3, feet, z - 0.3, x + 0.3, feet + height, z + 0.3);
	}

	public static void main(String[] args) {
		var frame = BuildingDefs.piece(12);
		var boxes = solids(frame, 2, Vec3.ZERO, 0);
		boxes.addAll(solids(BuildingDefs.piece(2), 2, new Vec3(-3, 0, 0), 0));
		var movement = BuildingCollision.clip(player(-1.85, 0.1, 0, 1.8), new Vec3(0.2, -0.08, 0), true, 0.6, boxes, b -> true);
		var destination = player(-1.85, 0.1, 0, 1.8).move(movement).deflate(1e-5);
		require(Math.abs(movement.x - 0.2) < 1e-6 && Math.abs(movement.y) < 1e-5 && boxes.stream().noneMatch(b -> BuildingCollision.intersects(b, destination)), "Stone floor frame is not flush with the neighbouring floor: " + movement);
		frameTransitions();
		stepWalks();
		roofWalks();
		movementLimits();
		fallingEdges();
		openings();
		stepRules();
		sectionQueries();
		System.out.println("Building movement fixtures passed: " + checks);
	}

	private static Vec3 world(Vec3 p, float yaw) {
		var v = new Quaternionf().rotationY(yaw).transform(new Vector3f((float) p.x, (float) p.y, (float) p.z));
		return new Vec3(v.x, v.y, v.z);
	}

	private static void frameTransitions() {
		for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) for (double height : new double[] {1.8, 0.9}) {
			var origin = new Vec3(10, 64, -20);
			var boxes = solids(BuildingDefs.piece(12), grade, origin, yaw);
			boxes.addAll(solids(BuildingDefs.piece(2), grade, origin.add(world(new Vec3(-3, 0, 0), yaw)), yaw));
			var start = origin.add(world(new Vec3(-1.85, 0.1, 0), yaw));
			var box = player(start.x, start.y, start.z, height);
			var wanted = world(new Vec3(0.2, -0.08, 0), yaw);
			var moved = BuildingCollision.clip(box, wanted, true, 0.6, boxes, b -> true);
			require(Math.hypot(moved.x - wanted.x, moved.z - wanted.z) < 1e-5, "Frame transition stops a walker: " + grade + " " + yaw + " " + height + " " + moved);
			var destination = box.move(moved).deflate(1e-5);
			require(boxes.stream().noneMatch(o -> BuildingCollision.intersects(o, destination)), "Frame transition leaves the body in collision");
		}
	}

	private static void stepWalks() {
		for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 4, (float) Math.PI / 2}) for (double height : new double[] {1.8, 0.9}) {
			var origin = new Vec3(16, 64, -20);
			var def = BuildingDefs.piece(14);
			var boxes = solids(def, grade, origin, yaw);
			boxes.add(new Obb(origin.add(world(new Vec3(-2, -0.5, 0), yaw)), 2, 0.5, 2, yaw));
			boxes.addAll(solids(BuildingDefs.piece(0), grade, origin.add(world(new Vec3(4.5, 1.4, 0), yaw)), yaw));
			var start = origin.add(world(new Vec3(-0.75, 0, 0), yaw));
			AABB box = player(start.x, start.y, start.z, height);
			boolean grounded = true;
			for (int direction : new int[] {1, -1}) for (int tick = 0; tick < 45; tick++) {
				var wanted = world(new Vec3(direction * 0.1, -0.08, 0), yaw);
				var moved = BuildingCollision.clip(box, wanted, grounded, 0.6, boxes, b -> true);
				require(Math.hypot(moved.x - wanted.x, moved.z - wanted.z) < 1e-5, "Step walk is blocked: " + grade + " " + yaw + " " + height + " " + direction + " " + tick + " " + moved);
				require(moved.y <= 0.6 + 1e-5, "Walking exceeds normal step height");
				box = box.move(moved);
				var destination = box.deflate(1e-5);
				require(boxes.stream().noneMatch(o -> BuildingCollision.intersects(o, destination)), "Step walk enters a collider");
				grounded = moved.y > wanted.y + 1e-6;
			}
			require(Math.abs(box.minY - origin.y) < 0.09, "Walker does not return to the ground after descending steps: " + grade + " " + yaw + " " + height + " " + box.minY);
		}
	}

	private static void movementLimits() {
		var ground = new Obb(new Vec3(-1, -0.5, 0), 1, 0.5, 1, 0);
		var step = new Obb(new Vec3(0.5, 0.1, 0), 0.5, 0.1, 1, 0);
		var lowCeiling = new Obb(new Vec3(0, 2, 0), 2, 0.1, 2, 0);
		var standing = player(-0.31, 0, 0, 1.8);
		var wanted = new Vec3(0.2, -0.08, 0);
		var blocked = BuildingCollision.clip(standing, wanted, true, 0.6, List.of(ground, step, lowCeiling), b -> true);
		require(blocked.x < 0.02 && Math.abs(blocked.y) < 1e-6, "Step pulls a standing player through a low ceiling");
		var crouched = BuildingCollision.clip(player(-0.31, 0, 0, 0.9), wanted, true, 0.6, List.of(ground, step, lowCeiling), b -> true);
		require(Math.abs(crouched.x - 0.2) < 1e-6 && Math.abs(crouched.y - 0.2) < 1e-6, "Crouched walker cannot use an unobstructed step");
		var tooHigh = new Obb(new Vec3(0.5, 0.35, 0), 0.5, 0.35, 1, 0);
		require(BuildingCollision.clip(standing, wanted, true, 0.6, List.of(ground, tooHigh), b -> true).x < 0.02, "Walker climbs a ledge above the step limit");
		var lintel = new Obb(new Vec3(0.5, 2, 0), 0.5, 0.16, 1, 0);
		var air = BuildingCollision.clip(player(-0.31, 1.03, 0, 0.9), wanted, false, 0.6, List.of(lintel), b -> true);
		require(air.x < 0.02 && Math.abs(air.y + 0.08) < 1e-6, "Air collision moves the player vertically into a window");
		var rising = BuildingCollision.clip(standing, new Vec3(0.2, 0.1, 0), false, 0.6, List.of(step), b -> true);
		require(Math.abs(rising.y - 0.1) < 1e-6, "A jump receives an automatic step boost");
		var falling = BuildingCollision.clip(player(0.5, 0.5, 0, 0.9), new Vec3(0, -0.5, 0), false, 0.6, List.of(step), b -> true);
		require(Math.abs(falling.y + 0.3) < 1e-6, "Falling does not land on the step from above");
		var dropped = BuildingCollision.clip(player(0, 0.1, 0, 0.9), new Vec3(0, -0.08, 0), false, 0.6, List.of(), b -> true);
		require(Math.abs(dropped.y + 0.08) < 1e-6, "An empty opening retains or lifts a falling player");
		require(BuildingCollision.clip(standing, wanted, true, 0.6, List.of(ground, step), b -> b.maxY <= 1.9).x < 0.02, "Step ignores an obstacle in the native world");
	}

	private static void fallingEdges() {
		var wall = new Obb(new Vec3(0, 1.5, 0), 0.1, 1.5, 1.5, 0);
		var hiddenFloor = new Obb(new Vec3(1.5, 0, 0), 1.5, 0.1, 1.5, 0);
		var box = player(-0.41, 0.2, 0, 0.9);
		var wanted = new Vec3(0.2, -0.2, 0);
		var moved = BuildingCollision.clip(box, wanted, false, 0.6, List.of(wall, hiddenFloor), b -> true);
		require(moved.x <= 0.01 + 1e-6 && Math.abs(moved.y - wanted.y) < 1e-6, "An inaccessible floor behind a wall stops a fall: " + moved);
		for (int tick = 0; tick < 3; tick++) {
			box = box.move(moved);
			moved = BuildingCollision.clip(box, new Vec3(0.2, -0.08, 0), false, 0.6, List.of(wall, hiddenFloor), b -> true);
			require(Math.abs(moved.y + 0.08) < 1e-6, "Continuing to walk into the wall keeps an unsupported player airborne");
		}
		var floor = new Obb(Vec3.ZERO, 1.5, 0.1, 1.5, (float) Math.PI / 4);
		var outside = player(2.6, 0.2, 0, 1.8);
		require(outside.minX > floor.bounds().maxX && !BuildingCollision.intersects(floor, outside.expandTowards(0, -0.3, 0)), "Separated world-axis footprints count as intersecting");
		var fall = BuildingCollision.clip(outside, new Vec3(0, -0.3, 0), false, 0.6, List.of(floor), b -> true);
		require(Math.abs(fall.y + 0.3) < 1e-6, "A rotated floor stops falling outside its real footprint: " + fall);
	}

	private static void roofWalks() {
		for (int index : new int[] {9, 10}) for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 4, (float) Math.PI / 2}) for (double height : new double[] {1.8, 0.9}) {
			var origin = new Vec3(16, 64, -20);
			var boxes = solids(BuildingDefs.piece(index), grade, origin, yaw);
			var start = origin.add(world(new Vec3(0, 0, index == 9 ? -1.1 : -0.5), yaw));
			var column = new AABB(start.x - 0.3, origin.y - 4, start.z - 0.3, start.x + 0.3, origin.y + 4, start.z + 0.3);
			double top = boxes.stream().filter(o -> BuildingCollision.intersects(o, column)).mapToDouble(o -> o.center().y + o.ey()).max().orElseThrow();
			AABB box = player(start.x, top, start.z, height);
			boolean grounded = true;
			for (int direction : new int[] {1, -1}) for (int tick = 0; tick < 18; tick++) {
				var wanted = world(new Vec3(0, -0.08, direction * 0.08), yaw);
				var moved = BuildingCollision.clip(box, wanted, grounded, 0.6, boxes, b -> true);
				require(Math.hypot(moved.x - wanted.x, moved.z - wanted.z) < 1e-5, "Roof walk is blocked: " + index + " " + grade + " " + yaw + " " + height + " " + direction + " " + tick + " " + moved);
				box = box.move(moved);
				var destination = box.deflate(1e-5);
				require(boxes.stream().noneMatch(o -> BuildingCollision.intersects(o, destination)), "Roof walk enters a collider");
				grounded = moved.y > wanted.y + 1e-6;
			}
		}
	}

	private static void openings() {
		for (int index : new int[] {12, 13}) for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) {
			var def = BuildingDefs.piece(index);
			var origin = new Vec3(16, 64, -20);
			var boxes = solids(def, grade, origin, yaw);
			var centre = origin.add(world(new Vec3(0, 0.1, index == 12 ? 0 : -0.8660254), yaw));
			require(BuildingCollision.internalOpening(def, origin, yaw, boxes, player(centre.x, centre.y, centre.z, 0.9)), "Crouching cannot enter the inner frame opening");
			var outside = origin.add(world(new Vec3(2, 0.1, 0), yaw));
			require(!BuildingCollision.internalOpening(def, origin, yaw, boxes, player(outside.x, outside.y, outside.z, 0.9)), "Inner-hole exception disables protection on the outer edge");
		}
	}

	private static void stepRules() {
		var def = BuildingDefs.piece(14);
		require(def.name.equals("foundation.steps") && !def.canRotate, "Wrong saved step ID or hammer rotation rule");
		require(def.sockets.stream().noneMatch(s -> s.terrain), "Steps can incorrectly be placed without attachment");
		var ramp = def.sockets.stream().filter(s -> s.male && !s.maleDummy && s.type == 7).findFirst().orElseThrow();
		var block = def.sockets.stream().filter(s -> s.male && !s.maleDummy && s.type == 6).findFirst().orElseThrow();
		require(ramp.terrainChecks.size() == 6 && block.terrainChecks.isEmpty(), "Ground checks leak onto the alternate attachment");
		require(ramp.restrictAngle && Math.abs(ramp.angleAllowed - 130) < 1e-5 && Math.abs(ramp.support - 0.7) < 1e-5, "Wrong step restrictions or support");
		require(PlacementRules.angleAllowed(ramp, new Quaternionf(), new Vec3(0, -0.5, -1)), "Valid step aim rejected");
		require(!PlacementRules.angleAllowed(ramp, new Quaternionf(), new Vec3(1, -0.5, 0)), "Restricted step aim accepted");
		require(PlacementRules.terrainMatches(ramp, new Vec3(0, -0.5, 0), 0, p -> p.y < 0), "Valid step terrain rejected");
		require(!PlacementRules.terrainMatches(ramp, new Vec3(0, 4, 0), 0, p -> p.y < 0), "Floating steps accepted by terrain checks");
		require(!PlacementRules.terrainMatches(ramp, new Vec3(0, -2, 0), 0, p -> p.y < 0), "Buried steps accepted by terrain checks");
		require(PlacementRules.terrainMatches(block, new Vec3(0, 4, 0), 0, p -> false), "Alternate step attachment inherits unrelated terrain restrictions");
		var clearance = def.placementChecks.stream().filter(c -> c.blocksWorld).findFirst().orElseThrow();
		var groundBox = new Obb(new Vec3(0.9, -0.5, 0), 0.5, 0.5, 0.5, 0);
		var buried = new Vec3(0, -0.7, 0);
		require(clearance.overlaps(buried, 0, groundBox), "The buried-step regression no longer intersects native ground");
		require(!PlacementRules.worldBlocked(clearance, buried, 0, groundBox, true, 0), "Native sand ground incorrectly blocks partially buried exterior steps");
		require(PlacementRules.worldBlocked(clearance, buried, 0, groundBox, false, 0), "Exterior steps can now cut into artificial obstacles");
		require(PlacementRules.worldBlocked(clearance, buried, 0, groundBox, true, -1), "Terrain above the supporting surface is incorrectly ignored");
		require(PlacementRules.worldBlocked(clearance, buried, 0, groundBox, true, Double.NaN), "Unsupported steps ignore terrain obstacles");
		require(PlacementRules.terrainMatches(ramp, new Vec3(0, 0.133034535, 0), 0, p -> p.y <= 0), "Saved foundation's 3 cm terrain gap rejects exterior steps");
		require(!PlacementRules.terrainMatches(ramp, new Vec3(0, 0.20, 0), 0, p -> p.y <= 0), "Ground tolerance accepts a floating exterior step");
		require(!PlacementRules.terrainMatches(ramp, new Vec3(0, -1.4, 0), 0, p -> p.y <= 0), "Ground tolerance relaxes the upper clearance points");
		for (int index : new int[] {0, 7}) for (var female : BuildingDefs.piece(index).sockets) {
			if (!female.female || female.femaleDummy || female.type != 7) continue;
			for (float baseYaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) {
				var attachment = new Quaternionf().rotationY(baseYaw).mul(female.rot);
				var placed = new Quaternionf(attachment).mul(new Quaternionf(ramp.rot).invert());
				var inward = attachment.transform(new Vector3f(0, 0, -1));
				var aim = new Vec3(inward.x, -0.3, inward.z);
				require(!PlacementRules.angleAllowed(ramp, placed, aim), "The exterior-step regression no longer exercises distinct attachment and piece frames");
				require(PlacementRules.angleAllowed(ramp, attachment, aim),
					"Looking directly at a foundation edge rejects the exterior steps: " + index + " " + female.name + " " + baseYaw);
				for (float angle : new float[] {-180, -66, -64, 64, 66, 180}) {
					var angled = new Quaternionf().rotationY((float) Math.toRadians(angle)).transform(new Vector3f(inward));
					require(PlacementRules.angleAllowed(ramp, attachment, new Vec3(angled.x, -0.3, angled.z)) == (Math.abs(angle) < 65),
						"Exterior steps use the wrong aiming cone on foundation " + index + " at " + angle);
				}
			}
		}
		for (int grade = 0; grade < 5; grade++) {
			require(def.grades[grade].sections.size() == 1 && def.grades[grade].health == new float[] {10, 250, 500, 1000, 2000}[grade], "Missing standard step grade");
			for (var b : def.grades[grade].sections.getFirst().colliders) require(b[0].y + b[1].y <= def.collisionBoundsCenter.y + def.collisionBoundsExtents.y + 1e-5, "Step collision extends beyond entity query bounds");
		}
	}

	private static boolean indexedSectionIncluded(Vec3 pivot, AABB query) {
		// The entity-section search uses the pivot's section, with 2 m X/Z and 4 m downward padding.
		return Math.floor(pivot.x / 16) >= Math.floor((query.minX - 2) / 16) && Math.floor(pivot.x / 16) <= Math.floor((query.maxX + 2) / 16)
				&& Math.floor(pivot.z / 16) >= Math.floor((query.minZ - 2) / 16) && Math.floor(pivot.z / 16) <= Math.floor((query.maxZ + 2) / 16)
				&& Math.floor(pivot.y / 16) >= Math.floor((query.minY - 4) / 16) && Math.floor(pivot.y / 16) <= Math.floor(query.maxY / 16);
	}

	private static void sectionQueries() {
		var origin = new Vec3(15.95, 64, 0);
		var box = player(19, 65.6501, 0.75, 0.9);
		var wanted = new Vec3(0, -0.08, 0);
		var area = box.expandTowards(wanted).inflate(0.6);
		require(!indexedSectionIncluded(origin, area), "Section-boundary regression does not exercise a skipped pivot");
		var def = BuildingDefs.piece(14);
		var solids = solids(def, 2, origin, 0);
		var queried = indexedSectionIncluded(origin, BuildingCollision.queryArea(area)) ? solids : List.<Obb>of();
		var moved = BuildingCollision.clip(box, wanted, false, 0.6, queried, b -> true);
		require(Math.abs(moved.y + 0.05) < 1e-4, "The end of the steps disappears from collision across a section boundary: " + moved);
		for (var piece : BuildingDefs.ALL) for (int grade = 0; grade < 5; grade++) for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) {
			for (var solid : solids(piece, grade, origin, yaw)) for (int sign : new int[] {-1, 1}) {
				var corner = solid.toWorld(sign * solid.ex(), sign * solid.ez());
				var contact = new AABB(solid.center().x + corner[0] - 0.01, solid.center().y + sign * solid.ey() - 0.01, solid.center().z + corner[1] - 0.01,
						solid.center().x + corner[0] + 0.01, solid.center().y + sign * solid.ey() + 0.01, solid.center().z + corner[1] + 0.01);
				require(indexedSectionIncluded(origin, BuildingCollision.queryArea(contact)), "A real collision corner cannot find its indexed pivot: " + piece.name + " " + grade + " " + yaw);
			}
		}
	}
}
