package local.rustak.client.mixin;

import local.rustak.decor.Decor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RenderSectionRegionMixin for Sodium, which meshes chunks from its own copy of the level: a decor block's anchor
 * cell reads as air there too. Applied only when Sodium is installed; its names are taken as they are at runtime.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
abstract class SodiumLevelSliceMixin {
	@Inject(method = "getBlockState(III)Lnet/minecraft/class_2680;", at = @At("HEAD"), cancellable = true, require = 0)
	private void rustak$hideAnchor(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		if (Decor.clientAnchored(BlockPos.asLong(x, y, z))) cir.setReturnValue(Blocks.AIR.defaultBlockState());
	}

	@Inject(method = "method_8320(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;", at = @At("HEAD"), cancellable = true, require = 0)
	private void rustak$hideAnchorAt(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (Decor.clientAnchored(pos.asLong())) cir.setReturnValue(Blocks.AIR.defaultBlockState());
	}
}
