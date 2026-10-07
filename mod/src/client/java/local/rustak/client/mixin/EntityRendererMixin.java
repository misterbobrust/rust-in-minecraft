package local.rustak.client.mixin;

import local.rustak.building.BuildingEntity;
import local.rustak.client.building.BuildLight;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities (players, mobs, dropped items, decor blocks, and the held item, which takes the player's light) take the
 * shade of Rust buildings over them like the buildings' own surfaces do (BuildLight): Minecraft's sky light alone
 * would leave them sunlit inside a closed room.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
	@Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
	private void rustak$buildingShade(Entity entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
		if (entity instanceof BuildingEntity) return;
		int light = cir.getReturnValueI(), sky = LightTexture.sky(light);
		if (sky == 0) return;
		float f = BuildLight.skyFactor(entity.level(), entity.getBoundingBox().getCenter());
		if (f < 1) cir.setReturnValue(LightTexture.pack(LightTexture.block(light), Math.round(sky * f)));
	}
}
