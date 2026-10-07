package local.rustak;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Client -> server: fire the rocket launcher along dir (the client applies the aim cone, like Rust). */
public record FireRocketPayload(Vec3 dir) implements CustomPacketPayload {
	public static final Type<FireRocketPayload> TYPE = new Type<>(RustAk.id("fire_rocket"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FireRocketPayload> CODEC = StreamCodec.of(
		(buf, p) -> buf.writeVec3(p.dir), buf -> new FireRocketPayload(buf.readVec3()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
