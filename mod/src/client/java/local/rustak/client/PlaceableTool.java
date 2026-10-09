package local.rustak.client;

import local.rustak.client.building.DoorClient;
import local.rustak.client.building.RadialMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

/**
 * Something set down from the hand (a door, a code lock): like Rust, it is held as the building plan and goes in with
 * the left button (DoorClient works out where).
 */
public final class PlaceableTool extends Gun {
	PlaceableTool(Item item) {
		super(item, "planner", 1, 0, 0.5f, 1, 5, 5, false);
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
	void tick(Minecraft mc, LocalPlayer p) {
		aiming = false;
	}

	private boolean lmb;

	@Override
	void frame(LocalPlayer p, float dt) {
		Minecraft mc = Minecraft.getInstance();
		boolean swallowed = RadialMenu.swallowsAttack(); // every frame, so it clears once the button is up
		boolean down = mc.options.keyAttack.isDown() && mc.screen == null && !RadialMenu.isOpen() && !swallowed;
		if (down && !lmb) DoorClient.place();
		lmb = down;
	}

	/** The plan's placing motion, after something went down. */
	public void placed() {
		viewmodel().play("apply", 0.05f);
	}
}
