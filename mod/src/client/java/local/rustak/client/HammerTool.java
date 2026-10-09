package local.rustak.client;

import java.util.ArrayList;
import java.util.List;

import local.rustak.BuildPayload;
import local.rustak.RustAk;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.client.building.MenuText;
import local.rustak.client.building.RadialMenu;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Rust's Hammer (0.5 s between hits, 1 s to draw, reach 1.5 m + 0.3 radius): left mouse
 * swings and repairs the block it strikes; right mouse on a block opens the upgrade pie menu.
 */
public final class HammerTool extends Gun {
	private static final String[] GRADE_ICONS = {"", "grade_wood", "grade_stone", "grade_metal", "grade_toptier"};
	private static final float REACH = 1.8f, MENU_REACH = 4f, STRIKE_TIME = 0.25f;
	private boolean rmb, alt;
	private float cooldown, strikeIn = -1;

	HammerTool() {
		super(RustAk.HAMMER, "hammer", 1, 0, 1.0f, 1, 5, 5, false);
	}

	@Override
	String deployClip() {
		return "deploy";
	}

	@Override
	String reloadClip() {
		return "deploy";
	}

	@Override
	void startReload() {
	}

	@Override
	void onDeselect() {
		super.onDeselect();
		if (RadialMenu.isOpen()) RadialMenu.cancel();
	}

	/** The building block under the crosshair within reach, or null. */
	static BuildingCollision.Hit target(LocalPlayer p, double reach) {
		Vec3 eye = p.getEyePosition();
		return BuildingCollision.raycast(p.level(), eye, eye.add(p.getViewVector(1).scale(reach)));
	}

	@Override
	void tick(Minecraft mc, LocalPlayer p) {
		aiming = false;
		boolean down = mc.options.keyUse.isDown() && mc.screen == null;
		if (down && !rmb) {
			Object aimed = local.rustak.client.building.BuildClient.hammerTarget(p);
			if (aimed instanceof local.rustak.building.DoorEntity door) {
				// Rust's hammer pickup: the door back as an item
				int id = door.getId();
				String name = RustAk.DOOR_ITEMS[door.kind()].getName().getString();
				// not while a lock is on it, as in Rust
				RadialMenu.open(List.of(new RadialMenu.Option(RustAk.id("textures/gui/door_open.png"), I18n.get("rustak.hammer.pickup"),
						door.hasLock() ? I18n.get("rustak.codelock.remove_first") : name, !door.hasLock())),
					i -> ClientPlayNetworking.send(BuildPayload.onBlock(BuildPayload.DEMOLISH, id, 0)));
			} else if (aimed instanceof local.rustak.decor.DecorEntity d) {
				// Minecraft blocks set down in the base: only demolish
				int id = d.getId();
				String name = d.state().getBlock().getName().getString();
				RadialMenu.open(List.of(new RadialMenu.Option(RustAk.id("textures/gui/demolish.png"), I18n.get("rustak.hammer.demolish"), name, true)),
					i -> ClientPlayNetworking.send(BuildPayload.onBlock(BuildPayload.DEMOLISH, id, 0)));
			} else if (aimed instanceof BuildingEntity b) {
				int id = b.getId(), current = b.grade();
				List<RadialMenu.Option> opts = new ArrayList<>();
				for (int g = 1; g < BuildingDefs.GRADES.length; g++) {
					opts.add(new RadialMenu.Option(RustAk.id("textures/gui/" + GRADE_ICONS[g] + ".png"), MenuText.gradeName(g),
						MenuText.gradeDescription(g), g > current));
				}
				boolean rotatable = b.def().canRotate;
				String name = MenuText.pieceName(b.piece());
				if (rotatable) opts.add(new RadialMenu.Option(RustAk.id("textures/gui/rotate.png"), I18n.get("rustak.hammer.rotate"), name, true));
				opts.add(new RadialMenu.Option(RustAk.id("textures/gui/demolish.png"), I18n.get("rustak.hammer.demolish"), name, true));
				int rotate = rotatable ? 4 : -1;
				RadialMenu.open(opts, i -> ClientPlayNetworking.send(i < 4 ? BuildPayload.onBlock(BuildPayload.UPGRADE, id, i + 1)
					: i == rotate ? BuildPayload.onBlock(BuildPayload.ROTATE, id, 0) : BuildPayload.onBlock(BuildPayload.DEMOLISH, id, 0)));
			}
		} else if (!down && rmb) {
			RadialMenu.cancel();
		}
		rmb = down;
	}

	@Override
	void frame(LocalPlayer p, float dt) {
		Minecraft mc = Minecraft.getInstance();
		cooldown = Math.max(0, cooldown - dt);
		if (strikeIn >= 0 && (strikeIn -= dt) < 0) {
			BuildingCollision.Hit hit = target(p, REACH);
			if (hit != null) {
				ClientPlayNetworking.send(BuildPayload.repair(hit.block().getId(), hit.location()));
				viewmodel().play("attack2_hit", 0.05f);
			}
		}
		boolean swallowed = RadialMenu.swallowsAttack(); // evaluated every frame so it can clear once the button is up
		boolean swing = mc.options.keyAttack.isDown() && mc.screen == null && !RadialMenu.isOpen() && !swallowed && !deploying();
		if (swing && cooldown <= 0) {
			viewmodel().play((alt = !alt) ? "attack1" : "attack2", 0.05f);
			cooldown = 0.5f;
			strikeIn = STRIKE_TIME;
		}
	}

	@Override
	void onClipEvent(String name) {
		switch (name) {
			case "deploy" -> play(RustAk.SOUNDS.get("hammer-deploy"));
			case "attack" -> play(RustAk.SOUNDS.get("hammer-attack"));
			default -> super.onClipEvent(name);
		}
	}
}
