package local.rustak.building;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared sides disappear from exterior access choices without closing exposed sides. */
public final class FoundationEdgeCheck {
	private static int checks;
	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}
	public static void main(String[] args) {
		for (int base : new int[] {0, 7}) for (int neighbour : new int[] {0, 7}) {
			var def = BuildingDefs.piece(base);
			var peer = BuildingDefs.piece(neighbour);
			var join = peer.sockets.stream().filter(s -> s.type == 1 && s.male && !s.maleDummy).findFirst().orElseThrow();
			for (float yaw : new float[] {0, -.8751361f, (float) Math.PI / 4, (float) -Math.PI / 2}) for (var ramp : def.sockets) {
				if (ramp.type != 7 || !ramp.female) continue;
				var side = def.sockets.stream().filter(s -> s.type == 1 && s.pos.distance(ramp.pos) < .02f).findFirst().orElseThrow();
				var rotation = new Quaternionf().rotationY(yaw);
				var facing = new Quaternionf(rotation).mul(side.rot).rotateX((float) Math.PI).rotateZ((float) Math.PI).mul(new Quaternionf(join.rot).invert());
				var direction = facing.transform(new Vector3f(1, 0, 0));
				float turn = (float) Math.atan2(-direction.z, direction.x);
				var offset = facing.transform(new Vector3f(join.pos));
				var point = rotation.transform(new Vector3f(side.pos));
				Vec3 origin = new Vec3(16, 64, -20);
				var mine = new RoofShape.Candidate(def, origin, yaw, 0);
				var other = new RoofShape.Candidate(peer, origin.add(point.x - offset.x, point.y - offset.y, point.z - offset.z), turn, 0);
				require(RoofPlacement.closedFoundationEdge(mine, ramp, List.of(other)), "Shared foundation side remains offered: " + def.name + "/" + peer.name);
				require(!RoofPlacement.closedFoundationEdge(mine, ramp, List.of()), "Exposed side disappeared");
				require(!RoofPlacement.closedFoundationEdge(mine, ramp, List.of(new RoofShape.Candidate(peer, other.pos().add(6, 0, 0), turn, 0))), "Distant foundation closes the side");
				for (var exposed : def.sockets) if (exposed.type == 7 && exposed != ramp)
					require(!RoofPlacement.closedFoundationEdge(mine, exposed, List.of(other)), "Shared side also hides another exposed side");
			}
		}
		System.out.println("Foundation edge fixtures passed: " + checks);
	}
}
