package local.rustak.client.mixin;

import local.rustak.client.building.RadialMenu;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Rust's pie menu is open the mouse steers its cursor and the camera stays put. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void rustak$pieMenu(double time, CallbackInfo ci) {
		if (!RadialMenu.isOpen()) return;
		RadialMenu.mouse(accumulatedDX, accumulatedDY);
		accumulatedDX = 0;
		accumulatedDY = 0;
		ci.cancel();
	}
}
