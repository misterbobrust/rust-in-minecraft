package local.rustak.building;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.rustak.CodeLockPayload;
import local.rustak.RustAk;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Rust's code lock on a door, server side. A lock starts unlocked with no code; setting the master code the first
 * time locks it and makes the setter its only user. Users open the door and lock or unlock it; anyone else needs the
 * code (or the guest code, which lets them through but not unlock): each wrong code shocks them a little harder,
 * after six a warning shows, after eight code entry is blocked for 15 minutes. Codes and users can change only while
 * it is unlocked, and only then can it come off.
 */
public final class CodeLock {
	static final int MAX_WRONG = 8;
	static final long LOCKOUT_TICKS = 900 * 20, WRONG_RESET_TICKS = 60 * 20;
	/** How far from the door a player can work its lock (Rust's 3 m, with room for the door's size). */
	static final double REACH = 3.5;

	/** The lock's private part: codes, who is let through, wrong tries. Never sent to clients. */
	public static final class State {
		boolean present, locked, blocked;
		String code = "", guestCode = "";
		final List<UUID> users = new ArrayList<>(), guests = new ArrayList<>();
		int wrong;
		long lastWrong = Long.MIN_VALUE / 2, blockedUntil;

		void clear() {
			present = locked = blocked = false;
			code = guestCode = "";
			users.clear();
			guests.clear();
			wrong = 0;
		}
	}

	private CodeLock() {
	}

	public static void handle(ServerPlayer player, CodeLockPayload p) {
		ServerLevel level = player.level();
		if (!(level.getEntity(p.door()) instanceof DoorEntity d) || d.isRemoved() || !player.isAlive()) return;
		Vec3 eye = player.getEyePosition();
		if (d.box().closest(eye).distanceTo(eye) > REACH) return;
		State s = d.lock;
		long now = level.getGameTime();
		switch (p.action()) {
			case CodeLockPayload.PLACE -> {
				ItemStack held = player.getMainHandItem();
				if (!held.is(RustAk.CODE_LOCK) || s.present) return;
				s.clear();
				s.present = true;
				if (!player.hasInfiniteMaterials()) held.shrink(1);
				d.syncLock();
				d.lockSound("lock-code-deploy");
			}
			case CodeLockPayload.LOCK -> {
				if (!s.present || s.locked || s.code.length() != 4 || !s.users.contains(player.getUUID())) return;
				s.locked = true;
				d.syncLock();
				d.lockSound("lock-code-lock");
			}
			case CodeLockPayload.UNLOCK -> {
				if (!s.present || !s.locked || s.blocked || !s.users.contains(player.getUUID())) return;
				s.locked = false;
				d.syncLock();
				d.lockSound("lock-code-unlock");
			}
			case CodeLockPayload.CHANGE_CODE -> {
				boolean hasCode = !s.code.isEmpty();
				if (!s.present || s.locked || !valid(p.code()) || (!hasCode && p.guest())) return;
				if (!hasCode) s.locked = true; // the first code locks it
				if (p.guest()) {
					s.guestCode = p.code();
					s.guests.clear();
					s.guests.add(player.getUUID());
				} else {
					s.code = p.code();
					s.users.clear();
					s.users.add(player.getUUID());
				}
				d.syncLock();
				d.lockSound("lock-code-updated");
			}
			case CodeLockPayload.ENTER_CODE -> {
				if (!s.present || !s.locked || s.blocked) return;
				String text = p.code();
				boolean master = text.equals(s.code), guest = !s.guestCode.isEmpty() && text.equals(s.guestCode);
				if (!master && !guest) {
					wrongCode(level, player, d, now);
					return;
				}
				if (master) {
					if (!s.users.contains(player.getUUID())) {
						s.users.add(player.getUUID());
						s.wrong = 0;
						d.lockSound("lock-code-updated");
					}
				} else if (!s.guests.contains(player.getUUID())) {
					s.guests.add(player.getUUID());
					d.lockSound("lock-code-updated");
				}
				d.syncLock();
			}
			case CodeLockPayload.TAKE -> {
				if (!s.present || s.locked) return;
				s.clear();
				d.syncLock();
				ItemStack stack = new ItemStack(RustAk.CODE_LOCK);
				if (!player.getInventory().add(stack)) player.drop(stack, false);
			}
			default -> {
			}
		}
	}

	/** A wrong code: denied, a shock that grows with each try, a warning after six and a lockout at eight. */
	private static void wrongCode(ServerLevel level, ServerPlayer player, DoorEntity d, long now) {
		State s = d.lock;
		if (now > s.lastWrong + WRONG_RESET_TICKS) s.wrong = 0;
		d.lockSound("lock-code-denied");
		d.lockSound("lock-code-shock");
		Vec3 at = d.lockCentre();
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 12, 0.08, 0.08, 0.08, 0.2);
		player.hurtServer(level, level.damageSources().lightningBolt(), (s.wrong + 1) * 5 * RustAk.DAMAGE_SCALE);
		s.wrong++;
		if (s.wrong > 5) player.displayClientMessage(Component.translatable("rustak.codelock.blockwarning").withStyle(ChatFormatting.RED), true);
		if (s.wrong >= MAX_WRONG) {
			s.blocked = true;
			s.blockedUntil = now + LOCKOUT_TICKS;
			d.syncLock();
		}
		s.lastWrong = now;
	}

	/** Opening or closing a door with a lock: free when unlocked, users and guests pass with a beep, others are denied. */
	static boolean mayPass(DoorEntity d, net.minecraft.world.entity.player.Player player) {
		State s = d.lock;
		if (!s.present || !s.locked) return true;
		if (s.users.contains(player.getUUID()) || s.guests.contains(player.getUUID())) {
			d.lockSound("lock-code-unlock");
			return true;
		}
		d.lockSound("lock-code-denied");
		return false;
	}

	/** The lockout ends after its time (and isn't kept over a restart, like Rust's). */
	static void tick(DoorEntity d, long now) {
		if (d.lock.blocked && now >= d.lock.blockedUntil) {
			d.lock.blocked = false;
			d.lock.wrong = 0;
			d.syncLock();
		}
	}

	static boolean valid(String code) {
		return code.length() == 4 && code.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	static String join(List<UUID> ids) {
		StringBuilder b = new StringBuilder();
		for (UUID id : ids) b.append(b.isEmpty() ? "" : ",").append(id);
		return b.toString();
	}

	static void read(List<UUID> into, String s) {
		into.clear();
		for (String part : s.split(",")) {
			try {
				if (!part.isEmpty()) into.add(UUID.fromString(part));
			} catch (IllegalArgumentException ignored) {
			}
		}
	}
}
