package local.rustak.client;

import local.rustak.RustCrouch;
import local.rustak.building.BuildingCollision;
import local.rustak.client.mixin.CameraAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;

/**
 * Rust's crouch in the air: the capsule shrinks from below, so crouching mid-jump (or jumping already crouched) pulls
 * the feet up under a still head, which is how players get over half walls; standing up mid-air lets them back down.
 * Minecraft would keep the feet and move the head. The lift goes as high as there's room for, and the camera's eye
 * height and the interpolation base move with it so the view doesn't jump.
 */
public final class CrouchJump {
	private static final float LIFT = RustCrouch.STAND - RustCrouch.HEIGHT;
	private static boolean wasCrouching, wasOnGround = true, lifted;

	private CrouchJump() {
	}

	public static void tick(Minecraft mc, LocalPlayer p) {
		boolean crouching = p.getPose() == Pose.CROUCHING, onGround = p.onGround();
		boolean free = !p.getAbilities().flying && !p.isInWater() && !p.isPassenger();
		if (onGround) lifted = false;
		if (free && !onGround) {
			// crouched in the air, or took off already crouched: feet up (once per jump)
			boolean tookOffCrouched = wasOnGround && crouching && p.getDeltaMovement().y > 0;
			if (crouching && !lifted && (!wasCrouching || tookOffCrouched)) {
				AABB box = p.getBoundingBox();
				for (float dy = LIFT; dy > 0.04f; dy -= 0.05f) {
					if (fits(p, box.move(0, dy, 0))) {
						shift(mc, p, dy);
						lifted = true;
						break;
					}
				}
			} else if (!crouching && wasCrouching && lifted) {
				// stood up in the air: the tall box hangs down from the head instead of growing up from the feet
				AABB box = p.getBoundingBox();
				AABB down = new AABB(box.minX, box.minY - LIFT, box.minZ, box.maxX, box.minY - LIFT + RustCrouch.STAND, box.maxZ);
				if (fits(p, down)) shift(mc, p, -LIFT);
				lifted = false;
			}
		}
		wasCrouching = crouching;
		wasOnGround = onGround;
	}

	private static boolean fits(LocalPlayer p, AABB box) {
		return p.level().noCollision(p, box) && !BuildingCollision.intersects(p.level(), box.deflate(1e-4));
	}

	private static void shift(Minecraft mc, LocalPlayer p, float dy) {
		p.setPos(p.getX(), p.getY() + dy, p.getZ());
		p.yo += dy;
		if (mc.getCameraEntity() == p) {
			CameraAccessor cam = (CameraAccessor) (Object) mc.gameRenderer.getMainCamera();
			cam.rustak$setEyeHeight(cam.rustak$eyeHeight() - dy);
			cam.rustak$setEyeHeightOld(cam.rustak$eyeHeightOld() - dy);
		}
	}
}
