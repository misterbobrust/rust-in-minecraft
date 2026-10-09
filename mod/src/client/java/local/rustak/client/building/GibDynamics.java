package local.rustak.client.building;

/** Bounded debris cleanup and contact damping, independent of the render frame rate. */
final class GibDynamics {
	static final int MAX_PIECES = 192, MAX_VERTICES = 200_000;
	static final double GRAVITY = 9.81;

	static int sampleIndex(int index, int total, int emitted) {
		return (int) ((long) index * total / emitted);
	}

	static float lifetime(float variation) { return 6 + 2 * variation; }
	static float cleanupDuration(float variation) { return 1.25f + .5f * variation; }
	static float cleanupStart(float lifetime, float settledAt, float variation) {
		float last = lifetime - cleanupDuration(variation);
		return settledAt < 0 ? last : Math.min(last, settledAt + .35f + .45f * variation);
	}

	static float scale(float age, float cleanupStart, float duration) {
		float t = Math.clamp((age - cleanupStart) / duration, 0, 1);
		return 1 - t * t * (3 - 2 * t);
	}

	static double frictionSpeed(double speed, double friction, double dt) {
		return Math.max(0, speed - Math.max(0, friction) * GRAVITY * dt);
	}
}
