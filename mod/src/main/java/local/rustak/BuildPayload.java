package local.rustak;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client -> server building actions. PLACE: piece at pos/yaw (from the client's socket placement, like Rust's
 * planner). UPGRADE: block to grade. DEMOLISH. REPAIR: a hammer hit.
 */
public record BuildPayload(int action, int piece, Vec3 pos, float yaw, int entity, int grade) implements CustomPacketPayload {
	public static final int PLACE = 0, UPGRADE = 1, DEMOLISH = 2, REPAIR = 3, ROTATE = 4, PLACE_DOOR = 5, DOOR_TOGGLE = 6, DOOR_KNOCK = 7;
	public static final Type<BuildPayload> TYPE = new Type<>(RustAk.id("build"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BuildPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVarInt(p.action);
			buf.writeVarInt(p.piece);
			buf.writeVec3(p.pos);
			buf.writeFloat(p.yaw);
			buf.writeVarInt(p.entity + 1);
			buf.writeVarInt(p.grade);
		},
		buf -> new BuildPayload(buf.readVarInt(), buf.readVarInt(), buf.readVec3(), buf.readFloat(), buf.readVarInt() - 1, buf.readVarInt()));

	public static BuildPayload place(int piece, Vec3 pos, float yaw) {
		return new BuildPayload(PLACE, piece, pos, yaw, -1, 0);
	}

	public static BuildPayload place(int piece, Vec3 pos, float yaw, int target) {
		return new BuildPayload(PLACE, piece, pos, yaw, target, 0);
	}

	public static BuildPayload placeDoor(int kind, Vec3 pos, float yaw) {
		return new BuildPayload(PLACE_DOOR, kind, pos, yaw, -1, 0);
	}

	public static BuildPayload onBlock(int action, int entity, int grade) {
		return new BuildPayload(action, 0, Vec3.ZERO, 0, entity, grade);
	}

	public static BuildPayload repair(int entity, Vec3 hit) {
		return new BuildPayload(REPAIR, 0, hit, 0, entity, 0);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
