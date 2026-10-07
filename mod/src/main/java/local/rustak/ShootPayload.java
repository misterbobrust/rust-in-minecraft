package local.rustak;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ShootPayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShootPayload> TYPE = new CustomPacketPayload.Type<>(RustAk.id("shoot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ShootPayload> CODEC = StreamCodec.unit(new ShootPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
