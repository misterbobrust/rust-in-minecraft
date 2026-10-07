package local.rustak.client;

import java.util.ArrayList;
import java.util.List;

import local.rustak.BuildPayload;
import local.rustak.RustAk;
import local.rustak.building.BuildingDefs;
import local.rustak.client.building.BuildingPlacement;
import local.rustak.client.building.MenuText;
import local.rustak.client.building.RadialMenu;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Rust's Building Plan: right mouse picks the piece from a pie menu, R rotates, left mouse places. */
public final class PlannerTool extends Gun {
	// Rust's building plan order (Planner.buildableList): foundation, floor, wall, doorway, window, half wall
	private static final int[] MENU_ORDER = {0, 2, 1, 4, 3, 6, 5}; // Rust's planner order
	public int piece;
	float rotation;
	public BuildingPlacement.Placement placement;
	private boolean rmb, lmb;
	private float cooldown;

	PlannerTool() {
		super(RustAk.PLANNER, "planner", 1, 0, 0.5f, 1, 5, 5, false);
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
	void startReload() { // R rotates the piece by its rotation step, like Rust
		float step = BuildingDefs.piece(piece).rotationAmount;
		rotation = (rotation + (step > 0 ? step : 90)) % 360;
	}

	@Override
	void onDeselect() {
		super.onDeselect();
		if (RadialMenu.isOpen()) RadialMenu.cancel();
		placement = null;
	}

	@Override
	void tick(Minecraft mc, LocalPlayer p) {
		aiming = false;
		boolean down = mc.options.keyUse.isDown() && mc.screen == null;
		if (down && !rmb) {
			List<RadialMenu.Option> opts = new ArrayList<>();
			for (int i : MENU_ORDER) {
				opts.add(new RadialMenu.Option(RustAk.id("textures/gui/" + BuildingDefs.PIECES[i] + ".png"), MenuText.pieceName(i), MenuText.pieceDescription(i), true));
			}
			RadialMenu.open(opts, i -> {
				piece = MENU_ORDER[i];
				rotation = 0;
			});
		} else if (!down && rmb) {
			RadialMenu.cancel();
		}
		rmb = down;
	}

	@Override
	void frame(LocalPlayer p, float dt) {
		Minecraft mc = Minecraft.getInstance();
		cooldown = Math.max(0, cooldown - dt);
		// every frame, so the ghost follows the camera smoothly
		placement = RadialMenu.isOpen() ? null : BuildingPlacement.compute(p, piece, rotation, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		boolean swallowed = RadialMenu.swallowsAttack(); // evaluated every frame so it can clear once the button is up
		boolean down = mc.options.keyAttack.isDown() && mc.screen == null && !RadialMenu.isOpen() && !swallowed; // no wait for the draw animation: Rust lets you build right away
		if (down && !lmb && cooldown <= 0 && placement != null && placement.valid()) {
			ClientPlayNetworking.send(BuildPayload.place(piece, placement.pos(), placement.yaw()));
			viewmodel().play("apply", 0.05f);
			cooldown = 0.25f;
		}
		lmb = down;
	}
}
