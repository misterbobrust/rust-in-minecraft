package local.rustak.decor;

import java.util.Map;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A Minecraft block set down freely (Decor): drawn at its position (bottom centre of its cell) turned by yaw, while
 * the real block sits at the anchor cell. Clicks go through to that block; a hit breaks it, dropping what the block
 * drops; it breaks too when what it rests on goes, or when its anchor block is gone.
 */
public class DecorEntity extends Entity {
	private static final EntityDataAccessor<BlockState> STATE = SynchedEntityData.defineId(DecorEntity.class, EntityDataSerializers.BLOCK_STATE);
	private static final EntityDataAccessor<BlockPos> ANCHOR = SynchedEntityData.defineId(DecorEntity.class, EntityDataSerializers.BLOCK_POS);
	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(DecorEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> ATTACH = SynchedEntityData.defineId(DecorEntity.class, EntityDataSerializers.INT);
	private BlockPos registered;

	public DecorEntity(EntityType<? extends DecorEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	void setup(BlockState state, BlockPos anchor, float yaw, int attach) {
		entityData.set(STATE, state);
		entityData.set(YAW, yaw);
		entityData.set(ATTACH, attach);
		entityData.set(ANCHOR, anchor);
	}

	/** The block as it stands now: the anchor's (a furnace lit, a lamp on), else the one set down. */
	public BlockState state() {
		BlockState placed = entityData.get(STATE);
		BlockState live = level().getBlockState(anchor());
		return live.getBlock() == placed.getBlock() ? live : placed;
	}

	public BlockPos anchor() {
		return entityData.get(ANCHOR);
	}

	public float yawRad() {
		return entityData.get(YAW);
	}

	public int attach() {
		return entityData.get(ATTACH);
	}

	public Obb visual() {
		return Decor.visualBox(entityData.get(STATE), position(), yawRad());
	}

	/** What the player collides with, or null for blocks without collision (torches, flowers). */
	public Obb solid() {
		AABB b = Decor.shapeBounds(entityData.get(STATE), true);
		return b == null ? null : Decor.box(b, position(), yawRad());
	}

	/** World position of a point in the block's cell coordinates ([0, 1] cube, as the anchor block sees it). */
	public Vec3 fromCell(double x, double y, double z) {
		double lx = x - 0.5, lz = z - 0.5, c = Math.cos(yawRad()), s = Math.sin(yawRad());
		return position().add(lx * c + lz * s, y, -lx * s + lz * c);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(STATE, Blocks.STONE.defaultBlockState());
		builder.define(ANCHOR, BlockPos.ZERO);
		builder.define(YAW, 0f);
		builder.define(ATTACH, Decor.FLOOR);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (key == ANCHOR) register();
		if (key == STATE || key == YAW) setBoundingBox(makeBoundingBox(position()));
	}

	private void register() {
		unregister();
		registered = anchor();
		Decor.anchors(level()).put(registered.asLong(), this);
		if (level().isClientSide()) DecorHooks.anchorChanged(registered);
	}

	private void unregister() {
		if (registered == null) return;
		Map<Long, DecorEntity> m = Decor.anchors(level());
		m.remove(registered.asLong(), this);
		if (level().isClientSide()) DecorHooks.anchorChanged(registered);
		registered = null;
	}

	@Override
	public void onRemoval(RemovalReason reason) {
		super.onRemoval(reason);
		unregister();
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (entityData == null) return super.makeBoundingBox(pos);
		return Decor.visualBox(entityData.get(STATE), pos, yawRad()).bounds();
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level) || (tickCount + getId()) % 10 != 0) return;
		if (level.getBlockState(anchor()).getBlock() != entityData.get(STATE).getBlock()) {
			discard(); // the block went another way (burnt, washed off, exploded): its drops are already out
			return;
		}
		if (!supported()) breakBlock(level, null);
	}

	/** Something holds it up: a building block, another decor block or a Minecraft block where it rests. */
	private boolean supported() {
		Vec3 p = switch (attach()) {
			case Decor.WALL -> fromCell(0.5, 0.5, 1.06);
			case Decor.CEILING -> fromCell(0.5, 1.06, 0.5);
			default -> fromCell(0.5, -0.06, 0.5);
		};
		AABB probe = new AABB(p, p).inflate(0.08);
		for (BuildingEntity b : BuildingCollision.blocksNear(level(), probe)) for (Obb o : b.solids()) if (o.contains(p, 0.08)) return true;
		for (DecorEntity d : level().getEntitiesOfClass(DecorEntity.class, probe, d -> d != this && !d.isRemoved())) if (d.visual().contains(p, 0.08)) return true;
		BlockPos cell = BlockPos.containing(p);
		return !Decor.anchored(level(), cell) && !level().getBlockState(cell).getCollisionShape(level(), cell).isEmpty();
	}

	/** Breaks the anchor block like a player would (drops, contents, sound), then goes. */
	public void breakBlock(ServerLevel level, Player by) {
		BlockPos a = anchor();
		if (level.getBlockState(a).getBlock() == entityData.get(STATE).getBlock()) {
			level.destroyBlock(a, by == null || !by.getAbilities().instabuild, by);
		}
		discard();
	}

	/** A player's hit breaks it outright, with the block's own break sound and none of the hitting-an-entity ones. */
	@Override
	public boolean skipAttackInteraction(Entity attacker) {
		if (attacker instanceof Player player && level() instanceof ServerLevel level && !isRemoved()) breakBlock(level, player);
		return true;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	/**
	 * Clicks work the anchor block, as if it had been clicked where it really is: on the server, and on the client too,
	 * like Minecraft predicts block use (that's where the clicking player's own sounds come from: a gate, a lever).
	 */
	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		Level level = level();
		BlockPos a = anchor();
		BlockState s = level.getBlockState(a);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(a), Direction.UP, a, false);
		InteractionResult r = s.useItemOn(player.getItemInHand(hand), level, player, hand, hit);
		if (r instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) r = s.useWithoutItem(level, player, hit);
		return r;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		entityData.set(STATE, in.read("State", BlockState.CODEC).orElse(Blocks.STONE.defaultBlockState()));
		entityData.set(YAW, in.getFloatOr("Yaw", 0));
		entityData.set(ATTACH, in.getIntOr("Attach", Decor.FLOOR));
		entityData.set(ANCHOR, in.read("Anchor", BlockPos.CODEC).orElse(BlockPos.ZERO));
		setBoundingBox(makeBoundingBox(position()));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.store("State", BlockState.CODEC, entityData.get(STATE));
		out.putFloat("Yaw", yawRad());
		out.putInt("Attach", attach());
		out.store("Anchor", BlockPos.CODEC, anchor());
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double d) {
		return d < 96 * 96;
	}
}
