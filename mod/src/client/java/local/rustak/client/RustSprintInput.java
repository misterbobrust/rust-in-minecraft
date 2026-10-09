package local.rustak.client;

import local.rustak.RustSprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Use the existing input and sprint-state packets; aiming and firing take priority over running. */
public final class RustSprintInput {
	private RustSprintInput() {
	}

	public static boolean landMovement(LocalPlayer player) {
		return player != null && RustSprint.landMovement(player.getAbilities().flying,
			player.isInWater() || player.isInLava(), player.isPassenger(),
			player.isFallFlying() || player.isVisuallyCrawling() || player.onClimbable());
	}

	public static boolean held(LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		Gun gun = RustAkClient.held(player);
		boolean firearm = gun instanceof HitscanGun || gun instanceof RocketGun;
		boolean weaponBusy = firearm && ((mc.options.keyUse.isDown() && !gun.reloading()) || gun.trigger(mc));
		return mc.screen == null && RustSprint.held(player.input.keyPresses.sprint(), player.input.getMoveVector().y,
			player.input.keyPresses.shift() || player.isMovingSlowly(), weaponBusy);
	}
}
