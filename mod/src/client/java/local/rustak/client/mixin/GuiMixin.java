package local.rustak.client.mixin;

import local.rustak.client.RustAkClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Minecraft crosshair with a Rust weapon in hand. */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void rustak$noCrosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
		if (RustAkClient.holding(Minecraft.getInstance().player)) ci.cancel();
	}
}
