package local.rustak;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.building.DoorEntity;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Rust's rocket: a projectile (speed 18, gravity x0.25, drag 0, ray hit test) and an explosive
 * (fuse 8-10 s, radius 3.8, full damage within 1, damage falling off between them, line of sight).
 * The movement step runs at a fixed 100 Hz inside each 20 Hz tick.
 */
public class RocketEntity extends Entity {
	public static final float SPEED = 18f, GRAVITY = 9.81f * 0.25f;
	static final float MIN_RADIUS = 1f, RADIUS = 3.8f;
	// playerDamage 350 against Rust's 100 hp, scaled to Minecraft's 20 hp
	static final float DAMAGE = 350f * 0.2f;
	private static final int SUBSTEPS = 5;
	private static final float DT = 0.05f / SUBSTEPS;

	/** Client hook (trail effects, engine sound), set by the client initializer. */
	public static Consumer<RocketEntity> clientTick = r -> {
	};

	private UUID owner;
	private int fuse;
	private boolean exploded;

	public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
		super(type, level);
		fuse = 160 + level.random.nextInt(41);
		noPhysics = true;
	}

	public static RocketEntity launch(ServerLevel level, LivingEntity shooter, Vec3 from, Vec3 dir) {
		RocketEntity r = new RocketEntity(RustAk.ROCKET, level);
		r.owner = shooter.getUUID();
		r.setPos(from);
		r.setDeltaMovement(dir.normalize().scale(SPEED / 20)); // blocks per tick, as Minecraft syncs it
		r.faceVelocity();
		level.addFreshEntity(r);
		return r;
	}

	void faceVelocity() {
		Vec3 v = getDeltaMovement();
		if (v.lengthSqr() < 1e-6) return;
		setYRot((float) (Mth.atan2(-v.x, v.z) * Mth.RAD_TO_DEG));
		setXRot((float) (Mth.atan2(-v.y, v.horizontalDistance()) * Mth.RAD_TO_DEG));
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			// predict the flight between server updates
			for (int i = 0; i < SUBSTEPS; i++) {
				Vec3 v = getDeltaMovement().scale(20).add(0, -GRAVITY * DT, 0);
				setDeltaMovement(v.scale(1 / 20.0));
				setPos(position().add(v.scale(DT)));
			}
			faceVelocity();
			clientTick.accept(this);
			return;
		}
		if (exploded) return;
		if (tickCount > fuse) {
			explode(position(), new Vec3(0, 1, 0));
			return;
		}
		ServerLevel level = (ServerLevel) level();
		for (int i = 0; i < SUBSTEPS && !exploded; i++) {
			Vec3 v = getDeltaMovement().scale(20).add(0, -GRAVITY * DT, 0); // m/s
			setDeltaMovement(v.scale(1 / 20.0));
			Vec3 from = position(), to = from.add(v.scale(DT));
			HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
			EntityHitResult eh = ProjectileUtil.getEntityHitResult(level, this, from, end,
				new AABB(from, end).inflate(0.5), e -> e.isPickable() && !e.isSpectator() && (tickCount > 4 || !e.getUUID().equals(owner)), 0.1f);
			if (eh != null) hit = eh;
			BuildingCollision.Hit block = BuildingCollision.raycast(level, from, to);
			BuildingCollision.DoorHit door = BuildingCollision.raycastDoors(level, from, to);
			double other = from.distanceTo(hit.getType() == HitResult.Type.MISS ? to : hit.getLocation());
			if (door != null && door.distance() < other && (block == null || door.distance() < block.distance())) {
				// a door's leaf stops it like a wall
				Vec3 at = door.location().subtract(v.normalize().scale(0.1));
				setPos(at);
				explode(at, v.normalize().reverse());
				return;
			}
			if (block != null && block.distance() < other) {
				Vec3 at = block.location().subtract(v.normalize().scale(0.1));
				setPos(at);
				explode(at, block.block().obb().normalAt(block.location()));
				return;
			}
			if (hit.getType() != HitResult.Type.MISS) {
				// on a hit: move to 0.1 m before it, then explode
				Vec3 dir = v.normalize();
				Vec3 at = hit.getLocation().subtract(dir.scale(0.1));
				Vec3 normal = hit instanceof BlockHitResult bh ? Vec3.atLowerCornerOf(bh.getDirection().getUnitVec3i()) : dir.reverse();
				setPos(at);
				explode(at, normal);
				return;
			}
			setPos(to);
		}
		faceVelocity();
	}

	private void explode(Vec3 at, Vec3 normal) {
		exploded = true;
		ServerLevel level = (ServerLevel) level();
		Entity shooter = owner == null ? null : level.getEntity(owner);
		DamageSource source = level.damageSources().explosion(this, shooter);
		AABB area = new AABB(at, at).inflate(RADIUS);
		for (Entity e : level.getEntities(this, area, e -> e instanceof LivingEntity && e.isAlive())) {
			// radius damage: distance from the closest point of the target, full damage within minradius
			AABB box = e.getBoundingBox();
			Vec3 closest = new Vec3(Mth.clamp(at.x, box.minX, box.maxX), Mth.clamp(at.y, box.minY, box.maxY), Mth.clamp(at.z, box.minZ, box.maxZ));
			float falloff = Mth.clamp((float) (closest.distanceTo(at) - MIN_RADIUS) / (RADIUS - MIN_RADIUS), 0, 1);
			Vec3 centre = box.getCenter();
			boolean visible = level.clip(new ClipContext(at, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
			if (falloff < 1 && visible) {
				((LivingEntity) e).invulnerableTime = 0;
				e.hurtServer(level, source, DAMAGE * (1 - falloff));
			}
		}
		// building blocks: the rocket's damage (Explosion 275, Blunt 75) with the same falloff
		for (BuildingEntity b : BuildingCollision.blocksNear(level, area)) {
			float f = Mth.clamp((float) (b.obb().closest(at).distanceTo(at) - MIN_RADIUS) / (RADIUS - MIN_RADIUS), 0, 1);
			if (f < 1) b.damage(Map.of(BuildingDefs.EXPLOSION, 275f * (1 - f), BuildingDefs.BLUNT, 75f * (1 - f)));
		}
		for (DoorEntity d : level.getEntitiesOfClass(DoorEntity.class, area.inflate(2), d -> !d.isRemoved() && d.def() != null)) {
			float f = Mth.clamp((float) (d.box().closest(at).distanceTo(at) - MIN_RADIUS) / (RADIUS - MIN_RADIUS), 0, 1);
			if (f < 1) d.damage(Map.of(BuildingDefs.EXPLOSION, 275f * (1 - f), BuildingDefs.BLUNT, 75f * (1 - f)));
		}
		// Minecraft blocks: a crater, a little smaller than TNT's
		BlockDamage.explosion(level, shooter != null ? shooter : this, at.subtract(normal.scale(0.2)), 3.2f, 4f);
		level.playSound(null, at.x, at.y, at.z, RustAk.ROCKET_EXPLOSION, SoundSource.BLOCKS, 8f, 0.95f + random.nextFloat() * 0.1f);
		ExplosionFxPayload fx = new ExplosionFxPayload(at, normal);
		for (ServerPlayer p : PlayerLookup.around(level, at, 160)) ServerPlayNetworking.send(p, fx);
		discard();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
