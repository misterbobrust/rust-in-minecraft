package local.rustak;

import net.minecraft.world.entity.EntityDimensions;

/**
 * Rust's crouch: a shorter body than Minecraft's 1.5 m, short enough for Rust's window openings (about 1.1 m); the
 * eyes stay a little higher than the box's top so the view doesn't sink too far. The
 * in-air half (Rust's capsule shrinking from below) is client side: local.rustak.client.CrouchJump.
 */
public final class RustCrouch {
	public static final float HEIGHT = 0.9F, STAND = 1.8F;
	public static final EntityDimensions DIMENSIONS = EntityDimensions.scalable(0.6F, HEIGHT).withEyeHeight(1.0F);

	private RustCrouch() {
	}
}
