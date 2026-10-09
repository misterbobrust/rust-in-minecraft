package local.rustak.client.mixin;

import local.rustak.RustSprint;
import local.rustak.client.RustSprintInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerSprintMixin {
	@Inject(method = "canStartSprinting", at = @At("RETURN"), cancellable = true)
	private void rustak$heldStart(CallbackInfoReturnable<Boolean> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (RustSprintInput.landMovement(self)) cir.setReturnValue(cir.getReturnValueZ() && RustSprintInput.held(self));
	}

	@Inject(method = "shouldStopRunSprinting", at = @At("RETURN"), cancellable = true)
	private void rustak$heldStop(CallbackInfoReturnable<Boolean> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (RustSprintInput.landMovement(self)) cir.setReturnValue(cir.getReturnValueZ() || !RustSprintInput.held(self));
	}

	@ModifyArg(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec2;scale(F)Lnet/minecraft/world/phys/Vec2;", ordinal = 2), index = 0)
	private float rustak$crouchSpeed(float factor) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		return RustSprintInput.landMovement(self) && self.isCrouching() ? RustSprint.crouchInput(factor) : factor;
	}

	// The input vector is normalized already; avoid the native square-direction speed boost.
	@Inject(method = "modifyInputSpeedForSquareMovement", at = @At("HEAD"), cancellable = true)
	private static void rustak$equalDiagonalSpeed(Vec2 input, CallbackInfoReturnable<Vec2> cir) {
		if (RustSprintInput.landMovement(Minecraft.getInstance().player)) cir.setReturnValue(input);
	}
}
