package local.rustak.mixin;

import local.rustak.decor.Decor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A decor block's anchor (Decor) is the real block, but in a cell where nothing shows: no outline to aim at, nothing
 * to bump into, and it stays put whatever is (or isn't) around it, since its decor rests on the building instead.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void rustak$noOutline(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		if (Decor.anchored(level, pos)) cir.setReturnValue(Shapes.empty());
	}

	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void rustak$noCollision(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		if (Decor.anchored(level, pos)) cir.setReturnValue(Shapes.empty());
	}

	// standing in an anchor cell doesn't black out the view as if the head were in a block
	@Inject(method = "isViewBlocking", at = @At("HEAD"), cancellable = true)
	private void rustak$seeThrough(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (Decor.anchored(level, pos)) cir.setReturnValue(false);
	}

	@Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
	private void rustak$survive(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (Decor.anchored(level, pos)) cir.setReturnValue(true);
	}

	@Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
	private void rustak$stay(LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir, BlockPos from, BlockState neighbour,
		RandomSource random, CallbackInfoReturnable<BlockState> cir) {
		if (Decor.anchored(level, pos)) cir.setReturnValue((BlockState) (Object) this);
	}
}
