package local.rustak.client.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The OpenAL source behind a channel, for SoundMuffle's filter. */
@Mixin(Channel.class)
public interface ChannelAccessor {
	@Accessor("source")
	int rustak$source();
}
