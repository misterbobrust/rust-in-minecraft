package local.rustak;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Server -> client: play a Rust effect; entity >= 0 lends its building block's box to mesh-emitting systems. */
public record FxPayload(String effect, Vec3 pos, Vec3 dir, int entity) implements CustomPacketPayload {
	public static final Type<FxPayload> TYPE = new Type<>(RustAk.id("fx"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			ByteBufCodecs.STRING_UTF8.encode(buf, p.effect);
			buf.writeVec3(p.pos);
			buf.writeVec3(p.dir);
			buf.writeVarInt(p.entity + 1);
		},
		buf -> new FxPayload(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readVec3(), buf.readVec3(), buf.readVarInt() - 1));

	public static void send(ServerLevel level, String effect, Vec3 pos, Vec3 dir, int entity) {
		FxPayload p = new FxPayload(effect, pos, dir, entity);
		for (ServerPlayer player : PlayerLookup.around(level, pos, 96)) ServerPlayNetworking.send(player, p);
	}

	public static void sendExcept(ServerLevel level, ServerPlayer except, String effect, Vec3 pos, Vec3 dir) {
		FxPayload p = new FxPayload(effect, pos, dir, -1);
		for (ServerPlayer player : PlayerLookup.around(level, pos, 96)) if (player != except) ServerPlayNetworking.send(player, p);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
