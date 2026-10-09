package local.rustak;

/** Standalone fixture: java local.rustak.RustSprintCheck. */
public final class RustSprintCheck {
	private static int assertions;

	public static void main(String[] args) {
		check(RustSprint.held(true, 1, false, false), "held forward starts running");
		check(RustSprint.held(true, (float) Math.sqrt(0.5), false, false), "forward diagonal runs");
		check(!RustSprint.held(false, 1, false, false), "release stops; double-tap alone cannot run");
		check(!RustSprint.held(true, 0, false, false), "sideways cannot run");
		check(!RustSprint.held(true, -1, false, false), "backward cannot run");
		check(!RustSprint.held(true, 1, true, false), "crouching stops running");
		check(!RustSprint.held(true, 1, false, true), "aiming or shooting stops running");

		for (int excluded = 0; excluded < 4; excluded++) {
			check(!RustSprint.landMovement(excluded == 0, excluded == 1, excluded == 2, excluded == 3), "native special movement remains");
		}
		check(RustSprint.landMovement(false, false, false, false), "normal ground movement");

		// Integrate native horizontal acceleration and drag, rather than comparing the helper with its own formula.
		close(terminal(0.1F, false, false, 1), 2.8, "walking terminal speed");
		close(terminal(0.13F, true, false, 1), 5.5, "running terminal speed");
		close(terminal(0.1F, false, true, RustSprint.crouchInput(0.3F)), 1.7, "crouching terminal speed");
		close(terminal(0.13F, true, true, RustSprint.crouchInput(0.3F)), 1.7, "crouch cancels a stale sprint flag");
		close(terminal(0.12F, false, false, 1), 3.36, "speed effects retained while walking");
		close(terminal(0.156F, true, false, 1), 6.6, "speed effects retained while running");
		close(terminal(0.1F, false, false, 0.2F), 0.56, "item-use slowdown retained");
		close(terminal(0.1F, false, true, 0.2F * RustSprint.crouchInput(0.3F)), 0.34, "item slowdown combines with crouch");

		double velocity = 2.8 / 20 * 0.546, previous = 2.8 / 20;
		for (int tick = 0; tick < 30; tick++) {
			double distance = velocity + RustSprint.movementSpeed(0.13F, true, false) * 0.98;
			check(distance > previous && distance < 5.5 / 20 + 1e-7, "run acceleration is gradual without overshoot");
			previous = distance;
			velocity = distance * 0.546;
		}
		close(previous * 20, 5.5, "acceleration converges to running speed");
		check(RustSprint.crouchInput(0.75F) <= 1, "sneaking effects cannot make input exceed unit length");
		close(terminal(0.1F, false, false, 1) * Math.sqrt(0.5 * 2), 2.8, "normalized diagonal speed");
		System.out.println("Sprint checks passed: " + assertions);
	}

	private static double terminal(float attribute, boolean running, boolean crouching, float inputScale) {
		double velocity = 0, distance = 0;
		for (int tick = 0; tick < 200; tick++) {
			distance = velocity + RustSprint.movementSpeed(attribute, running, crouching) * 0.98 * inputScale;
			velocity = distance * 0.546;
		}
		return distance * 20;
	}

	private static void close(double actual, double expected, String message) {
		check(Math.abs(actual - expected) < 1e-5, message + ": " + actual);
	}

	private static void check(boolean condition, String message) {
		assertions++;
		if (!condition) throw new AssertionError(message);
	}
}
