package local.rustak.client.mixin;

import local.rustak.client.RustAkClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With the AK in hand, left click fires and right click aims instead of mining, hitting and using. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void rustak$noAttack(CallbackInfoReturnable<Boolean> cir) {
		var player = ((Minecraft) (Object) this).player;
		if (RustAkClient.holding(player)) {
			RustAkClient.attackClicked(player); // each call is one click: semi-automatics fire on these
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void rustak$noMining(boolean attacking, CallbackInfo ci) {
		if (RustAkClient.holding(((Minecraft) (Object) this).player)) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void rustak$noUse(CallbackInfo ci) {
		if (RustAkClient.holding(((Minecraft) (Object) this).player) || local.rustak.client.building.DoorClient.onUse() || local.rustak.client.decor.DecorClient.onUse()) ci.cancel();
	}
}
