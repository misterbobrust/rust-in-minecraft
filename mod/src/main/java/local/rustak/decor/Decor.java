package local.rustak.decor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import local.rustak.RustAk;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import local.rustak.mixin.StandingAndWallBlockItemAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft blocks set down freely in Rust buildings, at any spot and turn, like Rust's deployables. Each is a
 * DecorEntity that draws the block where it was put, backed by the real block at a nearby free grid cell (its
 * anchor): that block does the work (a chest's inventory, a furnace's smelting, a torch's light) while the anchor
 * cell itself is hidden, walked through and never targeted (the BlockStateBase mixins and the client's render
 * region mixin check it here).
 */
public final class Decor {
	public static final int FLOOR = 0, WALL = 1, CEILING = 2;
	private static final Map<Long, DecorEntity> CLIENT = new ConcurrentHashMap<>();
	private static final Map<ResourceKey<Level>, Map<Long, DecorEntity>> SERVER = new ConcurrentHashMap<>();

	private Decor() {
	}

	static Map<Long, DecorEntity> anchors(Level level) {
		return level.isClientSide() ? CLIENT : SERVER.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
	}

	/** Whether the cell holds a decor block's anchor (for any getter that knows its level). */
	public static boolean anchored(BlockGetter getter, BlockPos pos) {
		Level level = getter instanceof Level l ? l : getter instanceof LevelChunk c ? c.getLevel() : null;
		if (level == null) return false;
		Map<Long, DecorEntity> m = level.isClientSide() ? CLIENT : SERVER.get(level.dimension());
		return m != null && !m.isEmpty() && m.containsKey(pos.asLong());
	}

	/** The client's anchors, for chunk meshing (which runs off-thread with no level at hand). */
	public static boolean clientAnchored(BlockPos pos) {
		return !CLIENT.isEmpty() && CLIENT.containsKey(pos.asLong());
	}

	public static boolean clientAnchored(long pos) {
		return !CLIENT.isEmpty() && CLIENT.containsKey(pos);
	}

	public static DecorEntity clientDecor(BlockPos pos) {
		return CLIENT.isEmpty() ? null : CLIENT.get(pos.asLong());
	}

	/**
	 * The block state an item sets down attached this way, facing local north (the entity's yaw turns it), or null
	 * for what can't be: not a block, or a block that spans two cells (doors, beds, tall plants).
	 */
	public static BlockState stateFor(ItemStack stack, int attach) {
		if (!(stack.getItem() instanceof BlockItem item)) return null;
		Block block = item.getBlock();
		if (attach == WALL && item instanceof StandingAndWallBlockItem sw) block = ((StandingAndWallBlockItemAccessor) sw).rustak$wallBlock();
		BlockState s = block.defaultBlockState();
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART)) return null;
		if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		if (s.hasProperty(BlockStateProperties.FACING)) {
			s = s.setValue(BlockStateProperties.FACING, attach == FLOOR ? Direction.UP : attach == CEILING ? Direction.DOWN : Direction.NORTH);
		}
		if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
			s = s.setValue(BlockStateProperties.ATTACH_FACE, attach == FLOOR ? AttachFace.FLOOR : attach == CEILING ? AttachFace.CEILING : AttachFace.WALL);
		}
		if (s.hasProperty(BlockStateProperties.HANGING)) s = s.setValue(BlockStateProperties.HANGING, attach == CEILING);
		if (s.hasProperty(BlockStateProperties.ROTATION_16)) s = s.setValue(BlockStateProperties.ROTATION_16, 0);
		return s;
	}

	/** The block's outline as a box in its cell: [0, 1] cube coordinates. A full cube for shapeless blocks. */
	static AABB shapeBounds(BlockState s, boolean collision) {
		VoxelShape shape = collision ? s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) : s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (shape.isEmpty()) return collision ? null : new AABB(0.25, 0, 0.25, 0.75, 0.5, 0.75);
		return shape.bounds();
	}

	/** The cell box [0, 1] placed at pos (bottom centre of the cell) turned by yaw. */
	public static Obb box(AABB local, Vec3 pos, float yaw) {
		double cx = (local.minX + local.maxX) / 2 - 0.5, cy = (local.minY + local.maxY) / 2, cz = (local.minZ + local.maxZ) / 2 - 0.5;
		double c = Math.cos(yaw), s = Math.sin(yaw);
		return new Obb(pos.add(cx * c + cz * s, cy, -cx * s + cz * c), local.getXsize() / 2, local.getYsize() / 2, local.getZsize() / 2, yaw);
	}

	public static Obb visualBox(BlockState s, Vec3 pos, float yaw) {
		return box(shapeBounds(s, false), pos, yaw);
	}

	/** Whether a block's box cuts into a building block or another decor block. */
	public static boolean blocked(Level level, Obb box) {
		AABB area = box.bounds().inflate(0.1);
		for (BuildingEntity b : BuildingCollision.blocksNear(level, area)) for (Obb o : b.solids()) if (box.overlaps(o, 0.03)) return true;
		for (DecorEntity d : level.getEntitiesOfClass(DecorEntity.class, area, d -> !d.isRemoved())) if (box.overlaps(d.visual(), 0.03)) return true;
		return false;
	}

	/** A free cell for the anchor: the one at the box's centre, else the nearest free one around it. */
	public static BlockPos findAnchor(Level level, Vec3 centre) {
		BlockPos at = BlockPos.containing(centre);
		List<BlockPos> cells = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) cells.add(p.immutable());
		cells.sort((a, b) -> Double.compare(a.getCenter().distanceToSqr(centre), b.getCenter().distanceToSqr(centre)));
		for (BlockPos p : cells) {
			if (level.getBlockState(p).isAir() && !anchors(level).containsKey(p.asLong())) return p;
		}
		return null;
	}

	/** The server side of a placement the client worked out (DecorClient), checked again here. */
	public static void place(ServerPlayer player, DecorPayload p) {
		ServerLevel level = player.level();
		ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
		BlockState state = stateFor(stack, p.attach());
		if (state == null || player.getEyePosition().distanceTo(p.pos()) > player.blockInteractionRange() + 2) return;
		Obb box = visualBox(state, p.pos(), p.yaw());
		if (blocked(level, box)) return;
		BlockPos anchor = findAnchor(level, box.center());
		if (anchor == null) return;
		DecorEntity d = new DecorEntity(RustAk.DECOR, level);
		d.setup(state, anchor, p.yaw(), p.attach());
		d.setPos(p.pos());
		level.addFreshEntity(d); // registers the anchor before the block goes in, so it can stand there
		level.setBlock(anchor, state, Block.UPDATE_ALL);
		state.getBlock().setPlacedBy(level, anchor, state, player, stack);
		SoundType sound = state.getSoundType();
		level.playSound(null, p.pos().x, p.pos().y, p.pos().z, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
		stack.consume(1, player);
	}
}
