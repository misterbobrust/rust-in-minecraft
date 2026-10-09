package local.rustak.client.building;

import local.rustak.building.BuildingDefs;
import local.rustak.building.Obb;
import local.rustak.building.StepsGroundCheck;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Steps attach to foundation edges, their own lower end and compatible interior slots. */
public final class StepsPlacementCheck {
	private static int checks;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) {
		var def = BuildingDefs.piece(14);
		for (int index : new int[] {0, 7, 2, 14}) {
			var target = BuildingDefs.piece(index);
			boolean offered = false;
			for (var male : def.sockets) for (var female : target.sockets) {
				if (!male.male || male.maleDummy || !female.female || female.femaleDummy || !male.compatible(female)) continue;
				offered = true;
				for (float yaw : new float[] {0, (float) Math.PI / 4, (float) -Math.PI / 2}) {
					var rot = new Quaternionf().rotationY(yaw);
					var point = rot.transform(new Vector3f(female.pos));
					var fr = new Quaternionf(rot).mul(female.rot);
					if (female.male && female.female) fr.rotateX((float) Math.PI).rotateZ((float) Math.PI);
					var ray = fr.transform(new Vector3f(0, 1, 0)).add(0, -0.3f, 0).normalize();
					var pl = BuildingPlacement.doPlacement(male, female, point, fr, ray, 0);
					var placed = new Quaternionf().rotationY(pl.yaw());
					var at = placed.transform(new Vector3f(male.pos)).add((float) pl.pos().x, (float) pl.pos().y, (float) pl.pos().z);
					require(at.distance(point) < 1e-5, "Steps miss the attachment on " + target.name);
					for (var check : def.placementChecks) if (check.blocks(target.name)) for (var b : target.colliders) {
						var c = rot.transform(new Vector3f(b[0]));
						require(!check.overlaps(pl.pos(), pl.yaw(), new Obb(new Vec3(c.x, c.y, c.z), b[1].x, b[1].y, b[1].z, yaw)), "Steps are blocked by their supporting " + target.name);
					}
				}
			}
			require(offered, "Missing step attachment on " + target.name);
		}
		groundScenes(def);
		System.out.println("Step placement fixtures passed: " + checks);
	}

	private static void groundScenes(BuildingDefs.Piece def) {
		var male = def.sockets.stream().filter(s -> s.male && !s.maleDummy && s.type == 7).findFirst().orElseThrow();
		for (int index : new int[] {0, 7}) for (var female : BuildingDefs.piece(index).sockets) {
			if (!female.female || female.femaleDummy || !male.compatible(female)) continue;
			for (float yaw : new float[] {0, -0.8751361f, (float) Math.PI / 4, (float) -Math.PI / 2}) {
				var rot = new Quaternionf().rotationY(yaw);
				var attachment = new Quaternionf(rot).mul(female.rot);
				var ray = attachment.transform(new Vector3f(0, 0, -1)).add(0, -0.3f, 0).normalize();
				for (float height : new float[] {0.7f, 1.5f, 1.5330346f, 1.6913264f, -0.1f}) {
					var point = rot.transform(new Vector3f(female.pos)).add(0, height, 0);
					var placement = BuildingPlacement.doPlacement(male, female, point, attachment, ray, 0);
					StepsGroundCheck.check(def, male, placement.pos(), placement.yaw(), attachment,
						new Vec3(ray.x, ray.y, ray.z), height > 0 && height < 1.55);
				}
			}
		}
		StepsGroundCheck.report();
	}
}
