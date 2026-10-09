package local.rustak;

/** Ground movement speeds, expressed in metres per second. */
public final class RustSprint {
	public static final float WALK = 2.8F, RUN = 5.5F, CROUCH = 1.7F;
	private static final float VANILLA_SPRINT_FACTOR = 1.3F;
	private static final float VANILLA_SNEAK_FACTOR = 0.3F;
	// Ordinary ground retains 0.6 * 0.91 of horizontal velocity each tick.
	private static final float VANILLA_WALK = 20 * 0.1F * 0.98F / (1 - 0.6F * 0.91F);

	private RustSprint() {
	}

	public static boolean landMovement(boolean flying, boolean inFluid, boolean passenger, boolean specialPose) {
		return !flying && !inFluid && !passenger && !specialPose;
	}

	/** Keep equipment and effect modifiers; remove the native sprint multiplier before applying the target speed. */
	public static float movementSpeed(float attributeSpeed, boolean sprinting, boolean crouching) {
		float walkingAttribute = attributeSpeed / (sprinting ? VANILLA_SPRINT_FACTOR : 1);
		return walkingAttribute * ((sprinting && !crouching ? RUN : WALK) / VANILLA_WALK);
	}

	/** The native input already includes item-use slowdown and the sneaking-speed attribute. */
	public static float crouchInput(float sneakingFactor) {
		return Math.min(1, sneakingFactor * (CROUCH / WALK) / VANILLA_SNEAK_FACTOR);
	}

	public static boolean held(boolean sprintKey, float forward, boolean crouching, boolean weaponBusy) {
		return sprintKey && forward > 1.0E-5F && !crouching && !weaponBusy;
	}
}
