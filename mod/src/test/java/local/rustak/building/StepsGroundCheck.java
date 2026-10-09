package local.rustak.building;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Exterior attachments over actual voxel ground, including a small gap from a saved scene. */
public final class StepsGroundCheck {
	private static int checks;
	private static BlockGetter ground;

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}

	public static void check(BuildingDefs.Piece def, BuildingDefs.Socket male, Vec3 pos, float yaw, Quaternionf attachment, Vec3 look, boolean expected) {
		if (ground == null) {
			SharedConstants.tryDetectVersion();
			Bootstrap.bootStrap();
			ground = new BlockGetter() {
				@Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
				@Override public BlockState getBlockState(BlockPos p) { return (p.getY() < 0 ? Blocks.SAND : Blocks.AIR).defaultBlockState(); }
				@Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
				@Override public int getHeight() { return 384; }
				@Override public int getMinY() { return -64; }
			};
			require(PlacementRules.naturalGround(Blocks.SAND.defaultBlockState()), "Sand is not classified as supporting terrain");
			require(!PlacementRules.naturalGround(Blocks.OAK_PLANKS.defaultBlockState()), "Artificial obstacles are classified as terrain");
		}
		require(PlacementRules.socketAllowed(ground, male, pos, yaw, attachment, look) == expected,
			"Actual voxel rays reject the exterior attachment at " + pos + " yaw " + yaw);
		if (!expected) return;
		double top = PlacementRules.supportingGround(ground, def, pos, yaw);
		require(Math.abs(top) < 1e-6, "The supporting ground height is not sampled from voxel surfaces");
		for (var clearance : def.placementChecks) if (clearance.blocksWorld) {
			var bounds = clearance.bounds(pos, yaw);
			for (int x = (int) Math.floor(bounds.minX); x <= Math.floor(bounds.maxX); x++)
				for (int y = (int) Math.floor(bounds.minY); y <= Math.floor(bounds.maxY); y++)
					for (int z = (int) Math.floor(bounds.minZ); z <= Math.floor(bounds.maxZ); z++) {
						var at = new BlockPos(x, y, z);
						var state = ground.getBlockState(at);
						for (var box : state.getCollisionShape(ground, at).toAabbs()) {
							var obstacle = new Obb(box.getCenter().add(x, y, z), (box.maxX - box.minX) / 2,
								(box.maxY - box.minY) / 2, (box.maxZ - box.minZ) / 2, 0);
							require(!PlacementRules.worldBlocked(clearance, pos, yaw, obstacle, PlacementRules.naturalGround(state), top),
								"Supporting sand blocks exterior steps at " + at + " " + pos);
						}
					}
		}
	}

	public static void report() {
		System.out.println("Exterior step voxel-ground fixtures passed: " + checks);
	}
}
