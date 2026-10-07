package local.rustak.client.building;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * Rust's pie segment graphic: a ring sector between two radii and two angles (degrees, 0 at the top, clockwise),
 * built from thin quads for Minecraft's GUI pipeline.
 */
public record PieShapeState(Matrix3x2f pose, float cx, float cy, float inner, float outer, float startDeg, float endDeg, int color,
		@Nullable ScreenRectangle scissorArea) implements GuiElementRenderState {
	@Override
	public void buildVertices(VertexConsumer vc) {
		int steps = Math.max(2, (int) Math.ceil(Math.abs(endDeg - startDeg) / 4));
		for (int i = 0; i < steps; i++) {
			double a0 = Math.toRadians(startDeg + (endDeg - startDeg) * i / steps);
			double a1 = Math.toRadians(startDeg + (endDeg - startDeg) * (i + 1) / steps);
			float s0 = (float) Math.sin(a0), c0 = (float) Math.cos(a0), s1 = (float) Math.sin(a1), c1 = (float) Math.cos(a1);
			// counter-clockwise on screen, like vanilla's rectangles: the GUI pipeline culls the other winding
			vc.addVertexWith2DPose(pose, cx + s0 * outer, cy - c0 * outer).setColor(color);
			vc.addVertexWith2DPose(pose, cx + s0 * inner, cy - c0 * inner).setColor(color);
			vc.addVertexWith2DPose(pose, cx + s1 * inner, cy - c1 * inner).setColor(color);
			vc.addVertexWith2DPose(pose, cx + s1 * outer, cy - c1 * outer).setColor(color);
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI;
	}

	@Override
	public TextureSetup textureSetup() {
		return TextureSetup.noTexture();
	}

	@Override
	public @Nullable ScreenRectangle bounds() {
		int r = (int) Math.ceil(outer) + 1;
		return new ScreenRectangle((int) cx - r, (int) cy - r, 2 * r, 2 * r).transformMaxBounds(pose);
	}
}
