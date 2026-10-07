package local.rustak.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.rustak.client.Gun;
import local.rustak.client.Viewmodel;
import local.rustak.client.RustAkClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Unique
	private static final Vector3f rustak$euler = new Vector3f();

	@Unique
	private static Gun rustak$gun() {
		return RustAkClient.held(Minecraft.getInstance().player);
	}

	@Unique
	private static boolean rustak$worldBob;

	/** Marks renderLevel's bobView call: the world camera keeps vanilla's bob. */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
	private void rustak$worldBob(GameRenderer self, PoseStack pose, float partialTick, Operation<Void> original) {
		rustak$worldBob = true;
		try {
			original.call(self, pose, partialTick);
		} finally {
			rustak$worldBob = false;
		}
	}

	/**
	 * Rust weapons have Rust's own viewmodel bob, so vanilla's hand bob is skipped. Cancelled in bobView itself rather
	 * than at renderItemInHand's call, because Iris's HandRenderer calls bobView directly when a shader pack is on.
	 */
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void rustak$noHandBob(PoseStack pose, float partialTick, CallbackInfo ci) {
		if (!rustak$worldBob && RustAkClient.holding(Minecraft.getInstance().player)) ci.cancel();
	}

	/** The weapon's camera clips move the camera itself, so they go where vanilla shakes the view on damage. */
	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void rustak$cameraAnimation(PoseStack pose, float partialTick, CallbackInfo ci) {
		Gun gun = rustak$gun();
		Viewmodel vm = gun == null ? null : gun.loadedViewmodel();
		if (vm != null && vm.cameraEuler(rustak$euler)) {
			pose.mulPose(Axis.ZP.rotationDegrees(rustak$euler.z));
			pose.mulPose(Axis.XP.rotationDegrees(rustak$euler.x));
			pose.mulPose(Axis.YP.rotationDegrees(rustak$euler.y));
		}
	}

	/** Aim zoom (AK 1.667, launcher 1.25) on the world view only. */
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void rustak$adsFov(Camera camera, float partialTick, boolean world, CallbackInfoReturnable<Float> cir) {
		Gun gun = rustak$gun();
		if (gun != null && !world) { // the viewmodel's own projection
			cir.setReturnValue(cir.getReturnValueF() * gun.handFovDegrees() / 70f);
			return;
		}
		Viewmodel vm = gun == null ? null : gun.loadedViewmodel();
		if (vm == null) return;
		float ads = vm.ads();
		if (ads > 0) cir.setReturnValue(cir.getReturnValueF() / Mth.lerp(ads, 1f, gun.zoom()));
	}
}
