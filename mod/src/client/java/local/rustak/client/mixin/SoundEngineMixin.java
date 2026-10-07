package local.rustak.client.mixin;

import java.util.Map;
import local.rustak.client.SoundMuffle;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SoundMuffle on every positioned sound: set as it starts (queued before it plays), and again a few times a second
 * while it lasts, as the listener or the sound moves in or out of a building.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@Shadow
	@Final
	private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

	@Unique
	private int rustak$ticks;

	@Inject(method = "play", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 1, shift = At.Shift.AFTER))
	private void rustak$muffleNew(SoundInstance sound, CallbackInfoReturnable<?> cir) {
		ChannelAccess.ChannelHandle handle = instanceToChannel.get(sound);
		int level = SoundMuffle.level(sound);
		if (handle != null && level > 0) handle.execute(ch -> SoundMuffle.apply(ch, level));
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void rustak$muffleUpdate(boolean paused, CallbackInfo ci) {
		if (paused || ++rustak$ticks % 5 != 0) return;
		instanceToChannel.forEach((sound, handle) -> {
			int level = SoundMuffle.level(sound);
			handle.execute(ch -> SoundMuffle.apply(ch, level));
		});
	}
}
