package local.rustak.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import local.rustak.client.decor.DecorClient;
import local.rustak.decor.Decor;
import local.rustak.decor.DecorEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ambient particles of a decor block's anchor (a torch's flame and smoke) rise from where the block is drawn. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Unique
	private DecorEntity rustak$decor;
	@Unique
	private BlockPos rustak$anchor;

	@Shadow
	protected abstract void doAddParticle(ParticleOptions options, boolean force, boolean decreased, double x, double y, double z, double dx, double dy, double dz);

	@WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/Block;animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
	private void rustak$fromDecor(Block block, BlockState state, Level level, BlockPos pos, RandomSource random, Operation<Void> original) {
		DecorEntity d = Decor.clientDecor(pos);
		if (d == null) {
			original.call(block, state, level, pos, random);
			return;
		}
		rustak$decor = d;
		rustak$anchor = pos.immutable();
		try {
			original.call(block, state, level, pos, random);
		} finally {
			rustak$decor = null;
		}
	}

	@Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
	private void rustak$moveParticle(ParticleOptions options, boolean force, boolean decreased, double x, double y, double z, double dx, double dy,
		double dz, CallbackInfo ci) {
		DecorEntity d = rustak$decor;
		if (d == null) return;
		rustak$decor = null;
		Vec3 p = DecorClient.particleAt(d, rustak$anchor, x, y, z);
		doAddParticle(options, force, decreased, p.x, p.y, p.z, dx, dy, dz);
		rustak$decor = d;
		ci.cancel();
	}
}
