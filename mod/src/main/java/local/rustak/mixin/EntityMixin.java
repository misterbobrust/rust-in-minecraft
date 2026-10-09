package local.rustak.mixin;

import local.rustak.building.BuildingCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Building blocks collide at any yaw (vanilla's axis-aligned result is clipped once more); players' vanilla steps give way to Rust's (RustSteps). */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "move", at = @At("TAIL"))
	private void rustak$slopeContact(MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!self.noPhysics && movement.y <= 0 && self.getDeltaMovement().y <= 0
				&& (type == MoverType.SELF || type == MoverType.PLAYER)
				&& BuildingCollision.supportedSlope(self.level(), self.getBoundingBox())) {
			self.setOnGroundWithMovement(true, self.horizontalCollision, movement);
			self.setDeltaMovement(self.getDeltaMovement().multiply(1, 0, 1));
			self.resetFallDistance();
		}
	}
	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void rustak$buildingCollision(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		Vec3 result = cir.getReturnValue();
		Vec3 clipped = BuildingCollision.clip((Entity) (Object) this, result);
		if (clipped != result) cir.setReturnValue(clipped);
	}

	@Inject(method = "walkingStepSound", at = @At("HEAD"), cancellable = true)
	private void rustak$noVanillaSteps(BlockPos pos, BlockState state, CallbackInfo ci) {
		if ((Object) this instanceof Player) ci.cancel();
	}
}
