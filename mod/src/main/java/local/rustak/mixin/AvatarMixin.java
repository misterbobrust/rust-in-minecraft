package local.rustak.mixin;

import local.rustak.RustCrouch;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rust's crouch height for players: Minecraft's 1.5 m crouch doesn't fit through Rust's window openings. */
@Mixin(Avatar.class)
abstract class AvatarMixin {
	@Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
	private void rustak$rustCrouch(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if (pose == Pose.CROUCHING) cir.setReturnValue(RustCrouch.DIMENSIONS);
	}
}
