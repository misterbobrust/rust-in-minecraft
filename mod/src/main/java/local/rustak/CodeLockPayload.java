package local.rustak;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server code lock actions on a door, as Rust's lock offers them: PLACE (from the hand onto the door), LOCK,
 * UNLOCK (for a player who knows the code), CHANGE_CODE (the master or guest code), ENTER_CODE (a player trying a
 * code), TAKE (the lock back as an item).
 */
public record CodeLockPayload(int action, int door, String code, boolean guest) implements CustomPacketPayload {
	public static final int PLACE = 0, LOCK = 1, UNLOCK = 2, CHANGE_CODE = 3, ENTER_CODE = 4, TAKE = 5;
	public static final Type<CodeLockPayload> TYPE = new Type<>(RustAk.id("code_lock"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CodeLockPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVarInt(p.action);
			buf.writeVarInt(p.door);
			buf.writeUtf(p.code, 8);
			buf.writeBoolean(p.guest);
		},
		buf -> new CodeLockPayload(buf.readVarInt(), buf.readVarInt(), buf.readUtf(8), buf.readBoolean()));

	public static CodeLockPayload of(int action, int door) {
		return new CodeLockPayload(action, door, "", false);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
