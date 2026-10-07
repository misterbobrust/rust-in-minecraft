package local.rustak.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.rustak.decor.DecorEntity;
import local.rustak.client.building.BuildLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a decor block at its own spot and turn: the block's model, and its block entity renderer (a chest, a sign)
 * fed by the real block entity at the anchor, whose own drawing there is suppressed.
 */
public class DecorRenderer extends EntityRenderer<DecorEntity, DecorRenderer.State> {
	public static class State extends EntityRenderState {
		BlockState block;
		float yaw;
		BlockEntityRenderState blockEntity;
	}

	public DecorRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(DecorEntity d, State state, float partialTick) {
		super.extractRenderState(d, state, partialTick);
		state.block = d.state();
		state.lightCoords = light(d);
		state.yaw = d.yawRad();
		state.blockEntity = null;
		BlockEntity be = d.level().getBlockEntity(d.anchor());
		if (be != null) {
			DecorClient.drawingDecor = true;
			try {
				state.blockEntity = Minecraft.getInstance().getBlockEntityRenderDispatcher().tryExtractRenderState(be, partialTick, null);
				if (state.blockEntity != null) state.blockEntity.lightCoords = state.lightCoords;
			} finally {
				DecorClient.drawingDecor = false;
			}
		}
	}

	/**
	 * The brightest light in the cells around the block, leaving out its anchor: a solid anchor block is dark inside,
	 * and the entity's own cell is often that very cell.
	 */
	private static int light(DecorEntity d) {
		Level level = d.level();
		BlockPos centre = BlockPos.containing(d.visual().center());
		int sky = 0, block = 0;
		for (BlockPos p : new BlockPos[] {centre, centre.above(), centre.below(), centre.north(), centre.south(), centre.east(), centre.west()}) {
			if (p.equals(d.anchor()) && level.getBlockState(p).isSolidRender()) continue;
			sky = Math.max(sky, level.getBrightness(LightLayer.SKY, p));
			block = Math.max(block, level.getBrightness(LightLayer.BLOCK, p));
		}
		return LightTexture.pack(block, Math.round(sky * BuildLight.skyFactor(level, d.visual().center())));
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		pose.pushPose();
		pose.mulPose(Axis.YP.rotation(state.yaw));
		pose.translate(-0.5f, 0, -0.5f);
		if (state.block.getRenderShape() == RenderShape.MODEL) {
			collector.submitBlock(pose, state.block, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
		}
		if (state.blockEntity != null) Minecraft.getInstance().getBlockEntityRenderDispatcher().submit(state.blockEntity, pose, collector, camera);
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
