package local.rustak.client.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {
	@Accessor("eyeHeight")
	float rustak$eyeHeight();

	@Accessor("eyeHeight")
	void rustak$setEyeHeight(float value);

	@Accessor("eyeHeightOld")
	float rustak$eyeHeightOld();

	@Accessor("eyeHeightOld")
	void rustak$setEyeHeightOld(float value);
}
