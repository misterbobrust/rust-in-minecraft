package local.rustak;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Server -> client: play the rocket explosion effect at a point, facing the hit normal. */
public record ExplosionFxPayload(Vec3 pos, Vec3 normal) implements CustomPacketPayload {
	public static final Type<ExplosionFxPayload> TYPE = new Type<>(RustAk.id("explosion_fx"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ExplosionFxPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVec3(p.pos);
			buf.writeVec3(p.normal);
		},
		buf -> new ExplosionFxPayload(buf.readVec3(), buf.readVec3()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
