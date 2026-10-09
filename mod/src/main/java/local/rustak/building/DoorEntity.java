package local.rustak.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import local.rustak.RustAk;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * A Rust hinged door (Door) set in a doorway or, double, in a wall frame: its position and yaw are the door
 * prefab's, from its socket placement. Opening and closing play the door's own animation (DoorDefs) from the tick
 * it was toggled, on both sides, so the leaves' colliders follow what is drawn. It goes with the block it hangs in.
 */
public class DoorEntity extends Entity {
	private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> OPEN = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.BOOLEAN);
	/** Game time of the last open or close (Long.MIN_VALUE as an int pair isn't needed: -100000 means long ago). */
	private static final EntityDataAccessor<Integer> TOGGLED = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> HEALTH = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.FLOAT);
	/** The code lock as clients see it: LOCK_* bits, and its users and guests ("users|guests", UUIDs) but never a code. */
	private static final EntityDataAccessor<Byte> LOCK = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<String> LOCK_USERS = SynchedEntityData.defineId(DoorEntity.class, EntityDataSerializers.STRING);
	private static final int LOCK_PRESENT = 1, LOCK_LOCKED = 2, LOCK_BLOCKED = 4, LOCK_HAS_CODE = 8, LOCK_HAS_GUEST_CODE = 16;
	/** Server side: the lock's codes and who it lets through. */
	final CodeLock.State lock = new CodeLock.State();
	private long nextKnock;
	/** Ticks since the toggle at which the pending sound plays (Rust's clip events), and its key. */
	private int soundAt = -1;
	private String soundKey;
	/** On the client: game time the toggle arrived, so the leaves start moving when its sound does. */
	private long localToggled = -100000;

	public DoorEntity(EntityType<? extends DoorEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	public static DoorEntity place(ServerLevel level, int kind, Vec3 pos, float yaw) {
		DoorEntity d = new DoorEntity(RustAk.DOOR, level);
		d.entityData.set(KIND, kind);
		d.entityData.set(YAW, yaw);
		d.entityData.set(HEALTH, d.def().health);
		d.setPos(pos);
		level.addFreshEntity(d);
		d.sound("door_" + d.def().material + "_deploy");
		return d;
	}

	public int kind() {
		return entityData.get(KIND);
	}

	public DoorDefs.Door def() {
		return DoorDefs.door(kind());
	}

	public float yawRad() {
		return entityData.get(YAW);
	}

	public boolean isOpen() {
		return entityData.get(OPEN);
	}

	public float health() {
		return entityData.get(HEALTH);
	}

	/** Seconds since the last open or close, at partialTick. */
	public float sinceToggle(float partialTick) {
		long from = level().isClientSide() ? localToggled : entityData.get(TOGGLED);
		return (level().getGameTime() - from + partialTick) / 20f;
	}

	/** Each hinge's turn now (radians about +Y in the door's space). */
	public float[] turns(float partialTick) {
		DoorDefs.Door d = def();
		float[] out = new float[d.hinges.size()];
		float t = sinceToggle(partialTick);
		for (int i = 0; i < out.length; i++) out[i] = d.turn(i, isOpen(), t);
		return out;
	}

	/** The leaves' collision boxes where they are now. */
	public List<Obb> solids() {
		DoorDefs.Door d = def();
		float[] turn = turns(0);
		List<Obb> out = new ArrayList<>();
		double c = Math.cos(yawRad()), s = Math.sin(yawRad());
		for (int i = 0; i < d.hinges.size(); i++) {
			DoorDefs.Hinge h = d.hinges.get(i);
			// the leaf turned about its pivot, then the whole door turned by its yaw (both about +Y)
			double tc = Math.cos(turn[i]), ts = Math.sin(turn[i]);
			double rx = h.centre.x - h.pivot.x, rz = h.centre.z - h.pivot.z;
			double lx = h.pivot.x + rx * tc + rz * ts, lz = h.pivot.z - rx * ts + rz * tc;
			Vec3 centre = position().add(lx * c + lz * s, h.centre.y, -lx * s + lz * c);
			out.add(new Obb(centre, h.half.x, h.half.y, h.half.z, yawRad() + turn[i]));
		}
		return out;
	}

	/** The door's whole box at rest, for picking and the hammer. */
	public Obb box() {
		DoorDefs.Door d = def();
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (DoorDefs.Hinge h : d.hinges) {
			minX = Math.min(minX, h.centre.x - h.half.x);
			maxX = Math.max(maxX, h.centre.x + h.half.x);
			minY = Math.min(minY, h.centre.y - h.half.y);
			maxY = Math.max(maxY, h.centre.y + h.half.y);
			minZ = Math.min(minZ, h.centre.z - h.half.z);
			maxZ = Math.max(maxZ, h.centre.z + h.half.z);
		}
		double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2, c = Math.cos(yawRad()), s = Math.sin(yawRad());
		return new Obb(position().add(cx * c + cz * s, (minY + maxY) / 2, -cx * s + cz * c), (maxX - minX) / 2, (maxY - minY) / 2,
			(maxZ - minZ) / 2, yawRad());
	}

	/** Opens or closes it (E): not while it's still swinging, nor through a lock that doesn't know the player. */
	public void toggle(Player player) {
		DoorDefs.Door d = def();
		if (sinceToggle(0) < (isOpen() ? d.openTime : d.closeTime)) return;
		if (!CodeLock.mayPass(this, player)) return;
		boolean open = !isOpen();
		entityData.set(OPEN, open);
		entityData.set(TOGGLED, (int) level().getGameTime());
		// Rust plays its door sounds from the clip's events: the start one as the leaf moves off, and on closing the
		// slam when it meets the frame; the open "end" (the leaf settling) is left out, it reads as a second opening
		sound(d.sound(open ? "open_start" : "close_start"));
		soundKey = open ? null : d.sound("close_end");
		soundAt = open ? -1 : tickCount + Math.max(1, Math.round(d.closeHit * 20) - 1);
	}

	/** Knocking, like Rust: the knock effect, at most every half second. */
	public void knock() {
		long now = level().getGameTime();
		if (now < nextKnock) return;
		nextKnock = now + 10;
		sound("door_" + def().material + "_knock");
	}

	public boolean hasLock() {
		return (entityData.get(LOCK) & LOCK_PRESENT) != 0;
	}

	public boolean isLocked() {
		return (entityData.get(LOCK) & LOCK_LOCKED) != 0;
	}

	/** Too many wrong codes: no code entry for a while. */
	public boolean lockBlocked() {
		return (entityData.get(LOCK) & LOCK_BLOCKED) != 0;
	}

	public boolean lockHasCode() {
		return (entityData.get(LOCK) & LOCK_HAS_CODE) != 0;
	}

	public boolean lockHasGuestCode() {
		return (entityData.get(LOCK) & LOCK_HAS_GUEST_CODE) != 0;
	}

	/** Whether the lock lets this player through: 2 knows the code, 1 knows the guest code, 0 neither. */
	public int lockAccess(java.util.UUID player) {
		String[] parts = entityData.get(LOCK_USERS).split("\\|", -1);
		String id = player.toString();
		if (java.util.Arrays.asList(parts[0].split(",")).contains(id)) return 2;
		return parts.length > 1 && java.util.Arrays.asList(parts[1].split(",")).contains(id) ? 1 : 0;
	}

	/** Sends the lock's state (not its codes) to clients. */
	void syncLock() {
		int bits = 0;
		if (lock.present) {
			bits = LOCK_PRESENT | (lock.locked ? LOCK_LOCKED : 0) | (lock.blocked ? LOCK_BLOCKED : 0) | (lock.code.isEmpty() ? 0 : LOCK_HAS_CODE)
				| (lock.guestCode.isEmpty() ? 0 : LOCK_HAS_GUEST_CODE);
		}
		entityData.set(LOCK, (byte) bits);
		entityData.set(LOCK_USERS, lock.present ? CodeLock.join(lock.users) + "|" + CodeLock.join(lock.guests) : "");
	}

	/** The lock's box where it is now (it turns with its leaf), or null when the door has no lock place. */
	public Obb lockBox(float partialTick) {
		DoorDefs.Lock l = def() == null ? null : def().lock;
		if (l == null) return null;
		float turn = l.hinge >= 0 && l.hinge < def().hinges.size() ? turns(partialTick)[l.hinge] : 0;
		Vector3f pv = l.hinge >= 0 && l.hinge < def().hinges.size() ? def().hinges.get(l.hinge).pivot : new Vector3f();
		double tc = Math.cos(turn), ts = Math.sin(turn), c = Math.cos(yawRad()), s = Math.sin(yawRad());
		double rx = l.centre.x - pv.x, rz = l.centre.z - pv.z;
		double lx = pv.x + rx * tc + rz * ts, lz = pv.z - rx * ts + rz * tc;
		Vec3 centre = position().add(lx * c + lz * s, l.centre.y, -lx * s + lz * c);
		return new Obb(centre, l.half.x, l.half.y, l.half.z, yawRad() + turn);
	}

	/** Where the lock's sounds and sparks come from. */
	public Vec3 lockCentre() {
		Obb box = lockBox(0);
		return box != null ? box.center() : position().add(0, 1, 0);
	}

	void lockSound(String key) {
		SoundEvent e = RustAk.SOUNDS.get(key);
		Vec3 at = lockCentre();
		if (e != null) level().playSound(null, at.x, at.y, at.z, e, SoundSource.BLOCKS, 1, 1);
	}

	private void sound(String key) {
		SoundEvent e = RustAk.SOUNDS.get(key);
		if (e != null) level().playSound(null, getX(), getY() + 1, getZ(), e, SoundSource.BLOCKS, 1, 1);
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) return;
		if (tickCount == soundAt && soundKey != null) sound(soundKey);
		CodeLock.tick(this, level.getGameTime());
		if ((tickCount + getId()) % 20 == 0 && !hung()) destroy(level);
	}

	/** Where the door's socket is in the world: inside the doorway (or wall frame) it hangs in. */
	public Vec3 socketPos() {
		Vector3f p = def().socket.pos;
		double c = Math.cos(yawRad()), s = Math.sin(yawRad());
		return position().add(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
	}

	/** It hangs in a building block: one whose box takes in the door's socket. */
	private boolean hung() {
		Vec3 at = socketPos();
		for (BuildingEntity b : BuildingCollision.blocksNear(level(), new AABB(at, at).inflate(0.5))) if (b.obb().contains(at, 0.2)) return true;
		return false;
	}

	/** Rust damage by type through the door's protection values; broken at zero health. */
	public void damage(Map<Integer, Float> byType) {
		if (!(level() instanceof ServerLevel level) || isRemoved()) return;
		float[] prot = def().protection;
		float total = 0;
		for (var e : byType.entrySet()) total += e.getValue() * (1 - (e.getKey() < prot.length ? prot[e.getKey()] : 0));
		float hp = health() - total;
		if (hp > 0) entityData.set(HEALTH, hp);
		else destroy(level);
	}

	/** Broken (or its doorway gone): the break sound, no item back (nor the lock's). */
	public void destroy(ServerLevel level) {
		sound("fx_gib_" + (def().material.equals("wood") ? "wood" : "metal"));
		discard();
	}

	/** The hammer's pickup: the door item back in the player's hands (or at their feet). */
	public void pickup(Player player) {
		ItemStack stack = new ItemStack(RustAk.DOOR_ITEMS[kind()]);
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		discard();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(KIND, 0);
		builder.define(YAW, 0f);
		builder.define(OPEN, false);
		builder.define(TOGGLED, -100000);
		builder.define(HEALTH, 200f);
		builder.define(LOCK, (byte) 0);
		builder.define(LOCK_USERS, "");
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (key == KIND || key == YAW) setBoundingBox(makeBoundingBox(position()));
		if (key == TOGGLED && level().isClientSide()) {
			// a fresh toggle starts now (its packet's delay and the client's clock drift don't matter); an old one,
			// sent as the door came into view, keeps its time
			long now = level().getGameTime(), at = entityData.get(TOGGLED);
			localToggled = Math.abs(now - at) < 40 ? now : at;
		}
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (entityData == null || def() == null) return super.makeBoundingBox(pos);
		double c = Math.cos(yawRad()), s = Math.sin(yawRad());
		AABB box = null;
		for (DoorDefs.Hinge h : def().hinges) {
			// everything a leaf can sweep: a circle of its length round the pivot
			double r = Math.hypot(h.centre.x - h.pivot.x, h.centre.z - h.pivot.z) + Math.max(h.half.x, h.half.z);
			Vec3 p = pos.add(h.pivot.x * c + h.pivot.z * s, 0, -h.pivot.x * s + h.pivot.z * c);
			AABB a = new AABB(p.x - r, pos.y + h.centre.y - h.half.y, p.z - r, p.x + r, pos.y + h.centre.y + h.half.y, p.z + r);
			box = box == null ? a : box.minmax(a);
		}
		return box == null ? super.makeBoundingBox(pos) : box;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		entityData.set(KIND, in.getIntOr("Kind", 0));
		entityData.set(YAW, in.getFloatOr("Yaw", 0));
		entityData.set(OPEN, in.getBooleanOr("Open", false));
		entityData.set(HEALTH, in.getFloatOr("Health", 200));
		lock.clear();
		lock.present = in.getBooleanOr("Lock", false);
		if (lock.present) {
			lock.locked = in.getBooleanOr("Locked", false);
			lock.code = in.getStringOr("Code", "");
			lock.guestCode = in.getStringOr("GuestCode", "");
			CodeLock.read(lock.users, in.getStringOr("LockUsers", ""));
			CodeLock.read(lock.guests, in.getStringOr("LockGuests", ""));
		}
		syncLock();
		setBoundingBox(makeBoundingBox(position()));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putInt("Kind", kind());
		out.putFloat("Yaw", yawRad());
		out.putBoolean("Open", isOpen());
		out.putFloat("Health", health());
		if (lock.present) {
			out.putBoolean("Lock", true);
			out.putBoolean("Locked", lock.locked);
			out.putString("Code", lock.code);
			out.putString("GuestCode", lock.guestCode);
			out.putString("LockUsers", CodeLock.join(lock.users));
			out.putString("LockGuests", CodeLock.join(lock.guests));
		}
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double d) {
		return d < 128 * 128;
	}
}
