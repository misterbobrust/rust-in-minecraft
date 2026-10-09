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
	/** The camera animation actually shown, and the blend into the current one (from, start, length in seconds). */
	@Unique
	private static final org.joml.Vector3f rustak$camShown = new org.joml.Vector3f(), rustak$camFrom = new org.joml.Vector3f();
	@Unique
	private static Viewmodel rustak$camVm;
	@Unique
	private static int rustak$camPlays;
	@Unique
	private static long rustak$camStart;
	@Unique
	private static float rustak$camBlend = 0.25f;

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
		if (vm == null || !vm.cameraEuler(rustak$euler)) rustak$euler.zero();
		// a new item or a new action: blend from where the camera was instead of jumping to the clip's first frame
		int plays = vm == null ? 0 : vm.plays();
		long now = System.nanoTime();
		if (vm != rustak$camVm || plays != rustak$camPlays) {
			rustak$camBlend = vm != rustak$camVm ? 0.25f : Math.max(0.05f, vm.fadeSeconds());
			rustak$camVm = vm;
			rustak$camPlays = plays;
			rustak$camFrom.set(rustak$camShown);
			rustak$camStart = now;
		}
		float t = Math.min(1, (now - rustak$camStart) / 1e9f / rustak$camBlend);
		rustak$camFrom.lerp(rustak$euler, t * t * (3 - 2 * t), rustak$camShown);
		if (rustak$camShown.lengthSquared() > 1e-8f) {
			pose.mulPose(Axis.ZP.rotationDegrees(rustak$camShown.z));
			pose.mulPose(Axis.XP.rotationDegrees(rustak$camShown.x));
			pose.mulPose(Axis.YP.rotationDegrees(rustak$camShown.y));
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
