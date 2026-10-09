package local.rustak.client.mixin;

import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Movement defaults also apply when controls are reset in the settings screen. */
@Mixin(Options.class)
abstract class OptionsMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = GLFW.GLFW_KEY_LEFT_SHIFT), require = 1, allow = 1)
	private int rustak$crouchDefault(int key) {
		return GLFW.GLFW_KEY_LEFT_CONTROL;
	}

	@ModifyConstant(method = "<init>", constant = @Constant(intValue = GLFW.GLFW_KEY_LEFT_CONTROL), require = 1, allow = 1)
	private int rustak$sprintDefault(int key) {
		return GLFW.GLFW_KEY_LEFT_SHIFT;
	}
}
