package local.rustak;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft blocks take damage from Rust weapons (Rust's own terrain doesn't, so the numbers are ours): bullets wear a
 * block down by its hardness (crack stages show the progress), rockets blow a crater sized by explosion resistance.
 */
public final class BlockDamage {
	/** Block health per point of hardness, in Rust damage: dirt 50 (one AK bullet), stone 150, planks 200, iron 500. */
	private static final float HEALTH_PER_HARDNESS = 100;
	private static final long FORGET_TICKS = 600;
	private static final Map<ServerLevel, Map<BlockPos, Worn>> WORN = new HashMap<>();

	private static final class Worn {
		float damage;
		long time;
	}

	private BlockDamage() {
	}

	/** A bullet with this much Rust damage hit the block at pos. */
	public static void bullet(ServerLevel level, ServerPlayer shooter, BlockPos pos, float damage) {
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (state.isAir() || hardness < 0 || state.getBlock() instanceof LiquidBlock || !level.mayInteract(shooter, pos) || !shooter.mayBuild()) return;
		Map<BlockPos, Worn> worn = WORN.computeIfAbsent(level, l -> new HashMap<>());
		long now = level.getGameTime();
		forget(level, worn, now);
		float health = Math.max(1, hardness * HEALTH_PER_HARDNESS);
		Worn w = worn.computeIfAbsent(pos.immutable(), p -> new Worn());
		w.damage += damage;
		w.time = now;
		if (w.damage >= health) {
			worn.remove(pos);
			level.destroyBlockProgress(crackId(pos), pos, -1);
			level.destroyBlock(pos, true, shooter);
		} else {
			level.destroyBlockProgress(crackId(pos), pos, (int) (w.damage / health * 10));
		}
	}

	/**
	 * Rocket explosion at `at`: blocks within radius break when the blast left at their distance (vanilla TNT's power 4,
	 * randomised the same way) beats their explosion resistance. A third of them drop.
	 */
	public static void explosion(ServerLevel level, Entity source, Vec3 at, float radius, float power) {
		int r = (int) Math.ceil(radius);
		BlockPos centre = BlockPos.containing(at);
		Map<BlockPos, Worn> worn = WORN.get(level);
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
			double d = Vec3.atCenterOf(pos).distanceTo(at);
			if (d > radius) continue;
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getDestroySpeed(level, pos) < 0) continue;
			float blast = power * (float) (1 - d / radius) * (0.7f + level.random.nextFloat() * 0.6f);
			if (blast <= (state.getBlock().getExplosionResistance() + 0.3f) * 0.3f) continue;
			BlockPos p = pos.immutable();
			if (level.random.nextFloat() < 0.33f) Block.dropResources(state, level, p, level.getBlockEntity(p), source, net.minecraft.world.item.ItemStack.EMPTY);
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			if (worn != null && worn.remove(p) != null) level.destroyBlockProgress(crackId(p), p, -1);
		}
	}

	private static void forget(ServerLevel level, Map<BlockPos, Worn> worn, long now) {
		for (Iterator<Map.Entry<BlockPos, Worn>> it = worn.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<BlockPos, Worn> e = it.next();
			if (now - e.getValue().time > FORGET_TICKS) {
				level.destroyBlockProgress(crackId(e.getKey()), e.getKey(), -1);
				it.remove();
			}
		}
	}

	/** A breaker id per block for the crack overlay, clear of entity ids. */
	private static int crackId(BlockPos pos) {
		return Integer.MIN_VALUE / 2 + (int) (pos.asLong() & 0x3fffffff);
	}
}
