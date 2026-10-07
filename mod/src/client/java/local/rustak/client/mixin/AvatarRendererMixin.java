package local.rustak.client.mixin;

import local.rustak.client.RustPlayers;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players animate as Rust's player model; vanilla's layers (held items, armour, cape) belong to its own model. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void rustak$animate(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		RustPlayers.extract(avatar, state);
	}

	@Inject(method = "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Z", at = @At("HEAD"), cancellable = true)
	private void rustak$noLayers(AvatarRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (RustPlayers.active(state)) cir.setReturnValue(false);
	}
}
