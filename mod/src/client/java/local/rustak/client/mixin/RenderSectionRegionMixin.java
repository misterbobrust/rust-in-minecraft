package local.rustak.client.mixin;

import local.rustak.decor.Decor;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunk meshing sees a decor block's anchor cell as air: the block shows only where its decor entity draws it. */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void rustak$hideAnchor(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (Decor.clientAnchored(pos)) cir.setReturnValue(Blocks.AIR.defaultBlockState());
	}
}
