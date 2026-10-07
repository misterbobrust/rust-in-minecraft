package local.rustak.client.mixin;

import local.rustak.client.decor.DecorClient;
import local.rustak.decor.Decor;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A decor block's anchor block entity (a chest) is drawn by its decor renderer only, not in its own cell. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {
	@Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
	private void rustak$hideAnchor(BlockEntity be, float partialTick, ModelFeatureRenderer.CrumblingOverlay crumbling,
		CallbackInfoReturnable<BlockEntityRenderState> cir) {
		if (!DecorClient.drawingDecor && Decor.clientAnchored(be.getBlockPos())) cir.setReturnValue(null);
	}
}
