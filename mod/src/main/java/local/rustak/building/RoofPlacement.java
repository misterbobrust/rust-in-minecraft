package local.rustak.building;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared socket and clearance checks for pitched pieces. */
public final class RoofPlacement {
	public static boolean isRoof(BuildingDefs.Piece def) {
		return def.name.equals("roof") || def.name.equals("roof.triangle");
	}

	static Vector3f position(BuildingEntity b, BuildingDefs.Socket s) {
		return new Quaternionf().rotationY(b.yawRad()).transform(new Vector3f(s.pos)).add((float) b.getX(), (float) b.getY(), (float) b.getZ());
	}

	static boolean connects(BuildingDefs.Socket a, Vector3f at, Quaternionf ar, BuildingDefs.Socket b, Vector3f bt, Quaternionf br) {
		if (!a.compatible(b) || (!a.male && !b.male) || (!a.female && !b.female) || at.distance(bt) > 0.02f) return false;
		float angle = ar.transform(new Vector3f(0, 0, 1)).angle(br.transform(new Vector3f(0, 0, 1)));
		if (a.male && a.female || b.male && b.female) angle = Math.min(angle, (float) Math.PI - angle);
		return angle <= Math.toRadians(2);
	}

	public static boolean occupiedSocket(BuildingEntity owner, BuildingDefs.Socket s) {
		if (closedFoundationEdge(owner, s)) return true;
		if (!s.monogamous) return false;
		Vector3f at = position(owner, s);
		Quaternionf ar = new Quaternionf().rotationY(owner.yawRad()).mul(s.rot);
		for (BuildingEntity b : BuildingCollision.blocksNear(owner.level(), owner.getBoundingBox().inflate(4))) {
			if (b == owner) continue;
			for (BuildingDefs.Socket t : b.def().sockets) {
				if (connects(s, at, ar, t, position(b, t), new Quaternionf().rotationY(b.yawRad()).mul(t.rot))) return true;
			}
		}
		return false;
	}

	/** Exterior access is offered only on exposed foundation sides. */
	public static boolean closedFoundationEdge(BuildingEntity owner, BuildingDefs.Socket socket) {
		if (socket.type != 7 || owner.def().sockets.stream().noneMatch(s -> s.terrain)) return false;
		var mine = new RoofShape.Candidate(owner.def(), owner.position(), owner.yawRad(), owner.grade());
		var nearby = BuildingCollision.blocksNear(owner.level(), owner.getBoundingBox().inflate(4)).stream()
			.filter(b -> b != owner).map(b -> new RoofShape.Candidate(b.def(), b.position(), b.yawRad(), b.grade())).toList();
		return closedFoundationEdge(mine, socket, nearby);
	}

	static boolean closedFoundationEdge(RoofShape.Candidate owner, BuildingDefs.Socket socket, List<RoofShape.Candidate> nearby) {
		if (socket.type != 7 || owner.def().sockets.stream().noneMatch(s -> s.terrain)) return false;
		Quaternionf rotation = new Quaternionf().rotationY(owner.yaw());
		Vector3f side = rotation.transform(new Vector3f(socket.pos));
		for (var edge : owner.def().sockets) {
			if (edge.type != 1 || !edge.female || edge.pos.distance(socket.pos) > .02f) continue;
			Vector3f at = new Vector3f(side).add((float) owner.pos().x, (float) owner.pos().y, (float) owner.pos().z);
			Quaternionf facing = new Quaternionf(rotation).mul(edge.rot);
			for (var other : nearby) {
				if (other.def().sockets.stream().noneMatch(s -> s.terrain)) continue;
				Quaternionf turn = new Quaternionf().rotationY(other.yaw());
				for (var peer : other.def().sockets) {
					if (peer.type != 1) continue;
					Vector3f point = turn.transform(new Vector3f(peer.pos)).add((float) other.pos().x, (float) other.pos().y, (float) other.pos().z);
					if (connects(edge, at, facing, peer, point, new Quaternionf(turn).mul(peer.rot))) return true;
				}
			}
		}
		return false;
	}

	public static boolean valid(Level level, BuildingDefs.Piece def, Vec3 pos, float yaw) {
		boolean attached = false;
		var nearby = BuildingCollision.blocksNear(level, BuildingEntity.obbAt(def, pos, yaw).bounds().inflate(4));
		Quaternionf rot = new Quaternionf().rotationY(yaw);
		for (BuildingDefs.Socket male : def.sockets) {
			if (!male.male || male.maleDummy || male.neighbour) continue;
			Vector3f at = rot.transform(new Vector3f(male.pos)).add((float) pos.x, (float) pos.y, (float) pos.z);
			Quaternionf ar = new Quaternionf(rot).mul(male.rot);
			for (BuildingEntity b : nearby) for (BuildingDefs.Socket female : b.def().sockets) {
				if (!female.female || female.femaleDummy || female.neighbour) continue;
				if (connects(male, at, ar, female, position(b, female), new Quaternionf().rotationY(b.yawRad()).mul(female.rot)) && !occupiedSocket(b, female)) attached = true;
			}
		}
		if (!attached) return false;
		return !def.placementChecks.isEmpty() && PlacementRules.spatial(level, def, pos, yaw);
	}
}
