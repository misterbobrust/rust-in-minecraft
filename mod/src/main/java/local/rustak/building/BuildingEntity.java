package local.rustak.building;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import local.rustak.RustAk;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A placed Rust building block: piece, grade, yaw and health. Its position is the prefab's
 * pivot. Collision and hit tests use the rotated box from the prefab's bounds (BuildingCollision, Obb).
 */
public class BuildingEntity extends Entity {
	private static final EntityDataAccessor<Integer> PIECE = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> GRADE = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEALTH = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> STABILITY = SynchedEntityData.defineId(BuildingEntity.class, EntityDataSerializers.FLOAT);
	private int distanceFromGround = Integer.MAX_VALUE;
	/** Stability checks left under the collapse threshold before it falls (Stability). */
	int strikes;
	/** Loaded without a saved stability: rated by its first checks but not felled by them. */
	boolean settling;
	private boolean checkOnTick;

	public BuildingEntity(EntityType<? extends BuildingEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	public static BuildingEntity place(ServerLevel level, int piece, Vec3 pos, float yaw) {
		BuildingEntity b = new BuildingEntity(RustAk.BUILDING, level);
		b.entityData.set(PIECE, piece);
		b.entityData.set(YAW, yaw);
		b.entityData.set(GRADE, 0);
		b.entityData.set(HEALTH, b.def().grades[0].health);
		b.setPos(pos);
		level.addFreshEntity(b);
		Stability.update(b);
		return b;
	}

	public BuildingDefs.Piece def() {
		return BuildingDefs.piece(entityData.get(PIECE));
	}

	public int piece() {
		return entityData.get(PIECE);
	}

	public int grade() {
		return entityData.get(GRADE);
	}

	/** Hammer "Rotate": turns the block in place about its origin. */
	public void rotate(float radians) {
		entityData.set(YAW, (float) ((yawRad() + radians) % (Math.PI * 2)));
		setPos(position()); // refresh the bounding box
		if (level() instanceof ServerLevel level) Stability.neighboursChanged(level, obb().bounds());
	}

	/** Rust's stability, 0..1 (synced for the hammer's readout). */
	public float stability() {
		return entityData.get(STABILITY);
	}

	void setStability(float s) {
		entityData.set(STABILITY, s);
	}

	int distanceFromGround() {
		return distanceFromGround;
	}

	void setDistanceFromGround(int d) {
		distanceFromGround = d;
	}

	@Override
	public void tick() {
		super.tick();
		if (checkOnTick && level() instanceof ServerLevel) {
			checkOnTick = false;
			settling = true;
			Stability.update(this);
		}
	}

	/** A block gone (destroyed, demolished, collapsed): the blocks around it check whether they still stand. */
	@Override
	public void onRemoval(RemovalReason reason) {
		super.onRemoval(reason);
		if (reason.shouldDestroy() && level() instanceof ServerLevel level) Stability.neighboursChanged(level, obb().bounds());
	}

	public float yawRad() {
		return entityData.get(YAW);
	}

	public float health() {
		return entityData.get(HEALTH);
	}

	public float maxHealth() {
		return def().grades[grade()].health;
	}

	/** The piece's bounds, placed in the world. */
	public Obb obb() {
		return obbAt(def(), position(), yawRad());
	}

	/** The collision boxes in the world (one for most pieces; a doorway's sides and lintel, a window's frame). */
	public List<Obb> solids() {
		return solidsAt(def(), position(), yawRad(), grade(), RoofShape.mask(this));
	}

	/** Placement uses the section's tighter clearance geometry when walking cells extend beyond the mesh. */
	public List<Obb> placementSolids() {
		return placementSolidsAt(def(), position(), yawRad(), grade(), RoofShape.mask(this));
	}

	public static List<Obb> solidsAt(BuildingDefs.Piece def, Vec3 pos, float yaw, int grade, long mask) {
		return solidsAt(def, pos, yaw, grade, mask, false);
	}

	public static List<Obb> placementSolidsAt(BuildingDefs.Piece def, Vec3 pos, float yaw, int grade, long mask) {
		return solidsAt(def, pos, yaw, grade, mask, true);
	}

	private static List<Obb> solidsAt(BuildingDefs.Piece def, Vec3 pos, float yaw, int grade, long mask, boolean placement) {
		List<BuildingDefs.Section> sections = def.grades[grade].sections;
		if (!sections.isEmpty()) {
			List<Obb> out = new ArrayList<>();
			for (int i = 0; i < sections.size(); i++) if ((mask & (1L << i)) != 0) {
				var section = sections.get(i);
				out.addAll(solidsAt(placement ? section.placementColliders : section.colliders, pos, yaw));
			}
			return out;
		}
		return solidsAt(def, pos, yaw);
	}

	/** The collision boxes a piece would have at this spot. */
	public static List<Obb> solidsAt(BuildingDefs.Piece def, Vec3 pos, float yaw) {
		return solidsAt(def.colliders, pos, yaw);
	}

	private static List<Obb> solidsAt(List<Vector3f[]> boxes, Vec3 pos, float yaw) {
		List<Obb> out = new ArrayList<>(boxes.size());
		for (Vector3f[] box : boxes) out.add(Obb.at(box, pos, yaw));
		return out;
	}

	public static Obb obbAt(BuildingDefs.Piece def, Vec3 pos, float yaw) {
		Vector3f c = def.boundsCenter, e = def.boundsExtents;
		double cs = Math.cos(yaw), sn = Math.sin(yaw);
		Vec3 centre = pos.add(c.x * cs + c.z * sn, c.y, -c.x * sn + c.z * cs);
		return new Obb(centre, e.x, e.y, e.z, yaw);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (entityData == null) return super.makeBoundingBox(pos);
		Vector3f c = def().collisionBoundsCenter, e = def().collisionBoundsExtents;
		double cs = Math.cos(yawRad()), sn = Math.sin(yawRad());
		return new Obb(pos.add(c.x * cs + c.z * sn, c.y, -c.x * sn + c.z * cs), e.x, e.y, e.z, yawRad()).bounds();
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (key == PIECE || key == YAW) setBoundingBox(makeBoundingBox(position()));
	}

	public void setGrade(int grade) {
		entityData.set(GRADE, grade);
		entityData.set(HEALTH, def().grades[grade].health);
	}

	public void repair() {
		entityData.set(HEALTH, maxHealth());
	}

	/** Rust damage by type through this grade's protection values; destroys the block at zero health. */
	public void damage(Map<Integer, Float> byType) {
		if (!(level() instanceof ServerLevel level) || isRemoved()) return;
		BuildingDefs.Grade g = def().grades[grade()];
		float total = 0;
		for (var e : byType.entrySet()) total += e.getValue() * (1 - g.protection(e.getKey()));
		float hp = health() - total;
		if (hp > 0) {
			entityData.set(HEALTH, hp);
			return;
		}
		Building.destroy(level, this);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(PIECE, 0);
		builder.define(GRADE, 0);
		builder.define(YAW, 0f);
		builder.define(HEALTH, 10f);
		builder.define(STABILITY, 0f);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		entityData.set(PIECE, in.getIntOr("Piece", 0));
		entityData.set(GRADE, in.getIntOr("Grade", 0));
		entityData.set(YAW, in.getFloatOr("Yaw", 0));
		entityData.set(HEALTH, in.getFloatOr("Health", 10));
		entityData.set(STABILITY, in.getFloatOr("Stability", 0));
		distanceFromGround = in.getIntOr("Distance", Integer.MAX_VALUE);
		// saved before stability existed, or before its first check finished
		checkOnTick = in.getFloatOr("Stability", -1) < 0 || in.getIntOr("Distance", 0) == 0;
		setBoundingBox(makeBoundingBox(position()));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putInt("Piece", piece());
		out.putInt("Grade", grade());
		out.putFloat("Yaw", yawRad());
		out.putFloat("Health", health());
		out.putFloat("Stability", stability());
		if (distanceFromGround != Integer.MAX_VALUE) out.putInt("Distance", distanceFromGround);
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double d) {
		return d < 160 * 160;
	}
}
