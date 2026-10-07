package local.rustak.decor;

import local.rustak.RustAk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Client -> server: set the block in the main hand down as decor at pos (bottom centre), turned by yaw. */
public record DecorPayload(Vec3 pos, float yaw, int attach) implements CustomPacketPayload {
	public static final Type<DecorPayload> TYPE = new Type<>(RustAk.id("decor"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DecorPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVec3(p.pos);
			buf.writeFloat(p.yaw);
			buf.writeVarInt(p.attach);
		},
		buf -> new DecorPayload(buf.readVec3(), buf.readFloat(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
