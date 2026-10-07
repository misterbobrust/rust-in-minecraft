package local.rustak.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import local.rustak.client.Gun;
import local.rustak.client.RustAkClient;
import local.rustak.client.ViewmodelRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void rustak$renderAk(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
			ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		Gun gun = hand == InteractionHand.MAIN_HAND ? RustAkClient.gunFor(stack) : null;
		if (gun != null) {
			ViewmodelRenderer.render(gun, pose, collector, light, partialTick);
			ci.cancel();
		}
	}
}
