package local.rustak.building;

import local.rustak.BuildPayload;
import local.rustak.FxPayload;
import local.rustak.RustAk;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server side of Rust's Planner and Hammer: placing, upgrading, demolishing, repairing and destroying blocks. */
public final class Building {
	/** fx/building/<x>_gib by grade: twig is thatch, metal is metal_sheet, top tier is fort_metal. */
	public static final String[] GIB = {"thatch", "wood", "stone", "metal", "toptier"};
	/** fx/impacts/blunt/<x> by grade, from each BuildingGrade's physicMaterial (Wood, Wood, Concrete, Metal, Metal). */
	public static final String[] IMPACT = {"wood", "wood", "concrete", "metal", "metal"};

	public static void handle(ServerPlayer player, BuildPayload p) {
		ServerLevel level = player.level();
		switch (p.action()) {
			case BuildPayload.PLACE -> place(level, player, p);
			case BuildPayload.PLACE_DOOR -> placeDoor(level, player, p);
			case BuildPayload.DOOR_TOGGLE, BuildPayload.DOOR_KNOCK -> {
				if (level.getEntity(p.entity()) instanceof DoorEntity d && d.box().closest(player.getEyePosition()).distanceTo(player.getEyePosition()) <= 3.5) {
					if (p.action() == BuildPayload.DOOR_TOGGLE) d.toggle();
					else d.knock();
				}
			}
			case BuildPayload.UPGRADE, BuildPayload.DEMOLISH, BuildPayload.REPAIR, BuildPayload.ROTATE -> {
				if (!player.getMainHandItem().is(RustAk.HAMMER)) return;
				if (p.action() == BuildPayload.DEMOLISH && level.getEntity(p.entity()) instanceof DoorEntity door) {
					if (door.box().closest(player.getEyePosition()).distanceTo(player.getEyePosition()) <= 6) door.pickup(player);
					return;
				}
				if (p.action() == BuildPayload.DEMOLISH && level.getEntity(p.entity()) instanceof local.rustak.decor.DecorEntity d) {
					if (d.visual().closest(player.getEyePosition()).distanceTo(player.getEyePosition()) <= 6) d.breakBlock(level, player);
					return;
				}
				if (!(level.getEntity(p.entity()) instanceof BuildingEntity b) || b.obb().closest(player.getEyePosition()).distanceTo(player.getEyePosition()) > 6) return;
				if (p.action() == BuildPayload.UPGRADE) upgrade(level, b, p.grade());
				else if (p.action() == BuildPayload.DEMOLISH) destroy(level, b);
				else if (p.action() == BuildPayload.ROTATE) {
					if (b.def().canRotate) b.rotate((float) Math.toRadians(b.def().rotationAmount > 0 ? b.def().rotationAmount : 180));
				}
				else repair(level, b, p.pos());
			}
			default -> {
			}
		}
	}

	private static void place(ServerLevel level, ServerPlayer player, BuildPayload p) {
		if (!player.getMainHandItem().is(RustAk.PLANNER) || p.piece() < 0 || p.piece() >= BuildingDefs.ALL.size()) return;
		BuildingDefs.Piece def = BuildingDefs.piece(p.piece());
		if (p.pos().distanceTo(player.getEyePosition()) > def.maxDistance + 4) return;
		Obb box = BuildingEntity.obbAt(def, p.pos(), p.yaw());
		// one block per spot: Rust's sockets are monogamous, so a second identical piece is refused
		for (BuildingEntity other : BuildingCollision.blocksNear(level, new AABB(box.center(), box.center()).inflate(0.5))) {
			if (other.piece() == p.piece() && other.obb().center().distanceTo(box.center()) < 0.3) return;
		}
		BuildingEntity b = BuildingEntity.place(level, p.piece(), p.pos(), p.yaw());
		FxPayload.send(level, "frame_place", box.center(), new Vec3(0, 1, 0), b.getId());
		play(level, box.center(), "fx_frame_place", 1.5f);
	}

	private static void upgrade(ServerLevel level, BuildingEntity b, int grade) {
		if (grade <= b.grade() || grade >= BuildingDefs.GRADES.length) return; // Rust upgrades only go up
		b.setGrade(grade);
		String g = BuildingDefs.GRADES[grade];
		Vec3 c = b.obb().center();
		FxPayload.send(level, "promote_" + g, c, new Vec3(0, 1, 0), b.getId());
		play(level, c, "fx_upgrade_" + g, 1.5f);
	}

	/** Destroyed with gibs: clients break the block into its gib pieces; the gib effect carries the sound. */
	/** A door from the hand into the doorway (or wall frame) the client's socket placement found. */
	private static void placeDoor(ServerLevel level, ServerPlayer player, BuildPayload p) {
		int kind = -1;
		for (int i = 0; i < RustAk.DOOR_ITEMS.length; i++) if (player.getMainHandItem().is(RustAk.DOOR_ITEMS[i])) kind = i;
		if (kind != p.piece() || DoorDefs.door(kind) == null || player.getEyePosition().distanceTo(p.pos()) > 6) return;
		if (!level.getEntitiesOfClass(DoorEntity.class, new net.minecraft.world.phys.AABB(p.pos(), p.pos()).inflate(0.5),
			d -> d.position().distanceTo(p.pos()) < 0.3).isEmpty()) return;
		DoorEntity.place(level, kind, p.pos(), p.yaw());
		player.getMainHandItem().consume(1, player);
	}

	public static void destroy(ServerLevel level, BuildingEntity b) {
		Vec3 c = b.obb().center();
		FxPayload.send(level, "destroy", c, new Vec3(0, 1, 0), b.getId());
		play(level, c, "fx_gib_" + GIB[b.grade()], 2f);
		b.discard();
	}

	/** A hammer strike: the material's blunt impact, and the repair sound only when there was damage to repair. */
	private static void repair(ServerLevel level, BuildingEntity b, Vec3 hit) {
		Obb box = b.obb();
		Vec3 at = hit.distanceTo(box.closest(hit)) < 0.5 ? hit : box.closest(hit);
		FxPayload.send(level, "impact_" + IMPACT[b.grade()], at, box.normalAt(at), -1);
		play(level, at, "fx_impact_" + IMPACT[b.grade()], 1f);
		if (b.health() < b.maxHealth() - 0.01f) {
			b.repair();
			play(level, at, "fx_repair", 1f);
		}
	}

	static void play(ServerLevel level, Vec3 at, String sound, float volume) {
		SoundEvent e = RustAk.SOUNDS.get(sound);
		if (e != null) level.playSound(null, at.x, at.y, at.z, e, SoundSource.BLOCKS, volume, 0.95f + level.random.nextFloat() * 0.1f);
	}
}
