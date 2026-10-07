package local.rustak.mixin;

import local.rustak.building.BuildingCollision;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pose changes need room among building blocks too. Sneaking keeps players from stepping off edges by asking whether they could fall there; that only looks at blocks,
 * so on a building block every step looked like an edge and a crouching player couldn't move.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	/** Standing up (or any pose change) also needs room among building blocks: no standing up under a window's top. */
	@Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("RETURN"), cancellable = true)
	private void rustak$roomAmongBuildings(Pose pose, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) return;
		Player self = (Player) (Object) this;
		if (BuildingCollision.intersects(self.level(), self.getDimensions(pose).makeBoundingBox(self.position()).deflate(1e-4))) cir.setReturnValue(false);
	}

	@Inject(method = "canFallAtLeast", at = @At("RETURN"), cancellable = true)
	private void rustak$buildingsHoldYou(double dx, double dz, double depth, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) return;
		Player self = (Player) (Object) this;
		AABB b = self.getBoundingBox();
		AABB below = new AABB(b.minX + 1e-7 + dx, b.minY - depth - 1e-7, b.minZ + 1e-7 + dz, b.maxX - 1e-7 + dx, b.minY, b.maxZ - 1e-7 + dz);
		if (BuildingCollision.intersects(self.level(), below)) cir.setReturnValue(false);
	}
}
