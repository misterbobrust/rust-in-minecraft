package local.rustak.mixin;

import local.rustak.RustSprint;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerSprintMixin {
	@Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
	private void rustak$groundSpeed(CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		if (RustSprint.landMovement(self.getAbilities().flying, self.isInWater() || self.isInLava(), self.isPassenger(),
			self.isFallFlying() || self.isVisuallyCrawling() || self.onClimbable())) {
			cir.setReturnValue(RustSprint.movementSpeed(cir.getReturnValueF(), self.isSprinting(), self.isCrouching()));
		}
	}
}
