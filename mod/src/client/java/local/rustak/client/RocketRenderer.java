package local.rustak.client;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.rustak.RocketEntity;
import local.rustak.RustAk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** Draws the rocket's mesh along the rocket's flight direction. */
public class RocketRenderer extends EntityRenderer<RocketEntity, RocketRenderer.State> {
	private static final Identifier TEXTURE = RustAk.id("textures/w/rocket.png");
	private static float[] mesh; // per vertex: x, y, z, u, v, nx, ny, nz (Unity, forward +Z)

	public static class State extends EntityRenderState {
		float yRot, xRot;
	}

	public RocketRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	private static float[] mesh() {
		if (mesh == null) {
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(RustAk.id("models/rocket.bin"))) {
				ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				b.getInt(); // parts
				int nameLength = b.getInt();
				b.position(b.position() + nameLength); // skip the part name
				float[] v = new float[b.getInt() * 8];
				b.asFloatBuffer().get(v);
				mesh = v;
			} catch (Exception e) {
				throw new RuntimeException("rustak: can't load rocket.bin", e);
			}
		}
		return mesh;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(RocketEntity rocket, State state, float partialTick) {
		super.extractRenderState(rocket, state, partialTick);
		state.yRot = Mth.rotLerp(partialTick, rocket.yRotO, rocket.getYRot());
		state.xRot = Mth.lerp(partialTick, rocket.xRotO, rocket.getXRot());
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		float[] v = mesh();
		int light = state.lightCoords;
		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(-state.yRot));
		pose.mulPose(Axis.XP.rotationDegrees(state.xRot));
		pose.scale(-1, 1, 1); // Unity is left-handed
		var type = MeshOut.solid(TEXTURE);
		boolean q = MeshOut.quads(type);
		collector.submitCustomGeometry(pose, type, (ps, vc) -> MeshOut.flat(vc, ps, v, -1, light, q));
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
