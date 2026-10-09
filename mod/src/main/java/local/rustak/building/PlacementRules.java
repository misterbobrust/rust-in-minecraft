package local.rustak.building;

import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Placement clearance, nearby attachment points and visibility, shared by the planner and server. */
public final class PlacementRules {
	private static final double GROUND_TOLERANCE = 0.05;
	private static Vec3 world(Vector3f p, Vec3 pos, float yaw) {
		Vector3f v = new Quaternionf().rotationY(yaw).transform(new Vector3f(p));
		return pos.add(v.x, v.y, v.z);
	}

	/** The selected attachment can have its own aiming and ground requirements. */
	public static boolean socketAllowed(BlockGetter level, BuildingDefs.Socket socket, Vec3 pos, float yaw, Quaternionf attachmentRotation, Vec3 look) {
		return angleAllowed(socket, attachmentRotation, look) && terrainMatches(socket, pos, yaw, point ->
			level.clip(new ClipContext(point.add(0, 3, 0), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.BLOCK);
	}

	static boolean angleAllowed(BuildingDefs.Socket socket, Quaternionf attachmentRotation, Vec3 look) {
		if (!socket.restrictAngle) return true;
		// Aim restrictions use the attachment frame, before the piece's own attachment offset is applied.
		Vector3f forward = new Quaternionf().rotationY(-(float) Math.toRadians(socket.faceAngle)).mul(attachmentRotation).transform(new Vector3f(0, 0, -1));
		double length = Math.hypot(look.x, look.z);
		if (length < 1e-8) return true;
		double dot = (look.x * forward.x + look.z * forward.z) / length;
		return Math.acos(Math.clamp(dot, -1, 1)) <= Math.toRadians(socket.angleAllowed * 0.5) + 1e-6;
	}

	static boolean terrainMatches(BuildingDefs.Socket socket, Vec3 pos, float yaw, Predicate<Vec3> ground) {
		for (var check : socket.terrainChecks) {
			Vec3 point = world(check.pos(), pos, yaw);
			if (ground.test(point) == check.wantsGround()) continue;
			if (!check.wantsGround() || socket.type != 7 || !ground.test(point.add(0, -GROUND_TOLERANCE, 0))) return false;
		}
		return true;
	}

	static boolean naturalGround(BlockState state) {
		return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
			|| state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
			|| state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.SNOW);
	}

	/** Ground-supported slopes can enter natural terrain below their sampled support surface. */
	static double supportingGround(BlockGetter level, BuildingDefs.Piece def, Vec3 pos, float yaw) {
		for (var socket : def.sockets) {
			if (!socket.male || socket.maleDummy || socket.type != 7 || socket.terrainChecks.isEmpty()) continue;
			double top = Double.NEGATIVE_INFINITY;
			for (var check : socket.terrainChecks) if (check.wantsGround()) {
				Vec3 point = world(check.pos(), pos, yaw);
				var hit = level.clip(new ClipContext(point.add(0, 3, 0), point.add(0, -GROUND_TOLERANCE, 0),
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
				if (hit.getType() != HitResult.Type.BLOCK || !naturalGround(level.getBlockState(hit.getBlockPos()))) return Double.NaN;
				top = Math.max(top, hit.getLocation().y);
			}
			return top;
		}
		return Double.NaN;
	}

	static boolean worldBlocked(BuildingDefs.PlacementCheck check, Vec3 pos, float yaw, Obb obstacle, boolean terrain, double groundTop) {
		return !(terrain && obstacle.bounds().maxY <= groundTop + 1e-6) && check.overlaps(pos, yaw, obstacle);
	}

	public static boolean spatial(Level level, BuildingDefs.Piece def, Vec3 pos, float yaw) {
		var nearby = BuildingCollision.blocksNear(level, BuildingEntity.obbAt(def, pos, yaw).bounds().inflate(2));
		var proposed = new RoofShape.Candidate(def, pos, yaw, 0);
		for (var b : nearby) if (tooClose(proposed, new RoofShape.Candidate(b.def(), b.position(), b.yawRad(), b.grade()))) return false;
		double groundTop = supportingGround(level, def, pos, yaw);
		for (var check : def.placementChecks) {
			if (check.blocksWorld) for (var shape : level.getBlockCollisions(null, check.bounds(pos, yaw))) for (var box : shape.toAabbs()) {
				Vec3 c = box.getCenter();
				boolean terrain = !Double.isNaN(groundTop) && naturalGround(level.getBlockState(BlockPos.containing(c)));
				if (worldBlocked(check, pos, yaw, new Obb(c, (box.maxX - box.minX) / 2, (box.maxY - box.minY) / 2, (box.maxZ - box.minZ) / 2, 0), terrain, groundTop)) return false;
			}
			for (var b : nearby) if (check.blocks(b.def().name)) for (var box : b.placementSolids()) if (check.overlaps(pos, yaw, box)) return false;
		}
		return true;
	}

	static boolean tooClose(RoofShape.Candidate a, RoofShape.Candidate b) {
		if (RoofShape.connected(a, b)) return false;
		Vec3 first = nearest(a, b), second = nearest(b, a);
		Vec3 delta = first == null ? second : second == null || first.lengthSqr() <= second.lengthSqr() ? first : second;
		return delta != null && Math.abs(delta.y) <= 1.49 && Math.hypot(delta.x, delta.z) <= 1.49;
	}

	private static Vec3 nearest(RoofShape.Candidate a, RoofShape.Candidate b) {
		if (a.def().proximityPoints.isEmpty()) return null;
		Vec3 nearest = null;
		for (var socket : a.def().sockets) {
			if (socket.type != 5 || !socket.cls.equals("ConstructionSocket")) continue;
			Vec3 at = world(socket.pos, a.pos(), a.yaw());
			for (var point : b.def().proximityPoints) {
				Vec3 d = world(point, b.pos(), b.yaw()).subtract(at);
				if (nearest == null || d.lengthSqr() < nearest.lengthSqr()) nearest = d;
			}
		}
		return nearest;
	}

	public static boolean visible(Level level, Vec3 eye, BuildingDefs.Piece def, Vec3 pos, float yaw, BuildingEntity target, Vec3 socket) {
		return sightPaths(def, pos, yaw, socket, (point, padding) -> clear(level, eye, point, padding,
			def.alternateSight && !def.sightPoints.isEmpty() || def.checkParentSight ? null : target));
	}

	static boolean sightPaths(BuildingDefs.Piece def, Vec3 pos, float yaw, Vec3 socket, BiPredicate<Vec3, Double> clear) {
		if (def.alternateSight && !def.sightPoints.isEmpty()) {
			if (clear.test(socket, 0.5)) return true;
			for (var p : def.sightPoints) if (clear.test(world(p, pos, yaw), 0.0)) return true;
			return false;
		}
		return clear.test(pos, 0.2);
	}

	public static boolean visibleAttachment(Level level, Vec3 eye, Vec3 look, BuildingDefs.Piece def, Vec3 pos, float yaw, BuildingEntity target) {
		if (target == null || target.isRemoved()) return false;
		Quaternionf rot = new Quaternionf().rotationY(yaw);
		for (var male : def.sockets) {
			if (!male.male || male.maleDummy || male.terrain || male.neighbour || male.type <= 0) continue;
			Vec3 at = world(male.pos, pos, yaw);
			for (var female : target.def().sockets) {
				if (!female.female || female.femaleDummy || female.neighbour) continue;
				Vector3f point = RoofPlacement.position(target, female);
				Quaternionf attachmentRotation = new Quaternionf().rotationY(target.yawRad()).mul(female.rot);
				if (RoofPlacement.connects(male, new Vector3f((float) at.x, (float) at.y, (float) at.z), new Quaternionf(rot).mul(male.rot),
					female, point, attachmentRotation) && !RoofPlacement.occupiedSocket(target, female)) {
					if (female.male && female.female) attachmentRotation.rotateX((float) Math.PI).rotateZ((float) Math.PI);
					if (socketAllowed(level, male, pos, yaw, attachmentRotation, look)
						&& visible(level, eye, def, pos, yaw, target, new Vec3(point.x, point.y, point.z))) return true;
				}
			}
		}
		return false;
	}

	private static boolean clear(Level level, Vec3 from, Vec3 to, double padding, BuildingEntity ignored) {
		var hit = BuildingCollision.raycast(level, from, to);
		double len = from.distanceTo(to);
		if (hit != null && hit.block() != ignored && hit.distance() < len - padding - 1e-6) return false;
		var door = BuildingCollision.raycastDoors(level, from, to);
		return door == null || door.distance() >= len - padding - 1e-6;
	}
}
