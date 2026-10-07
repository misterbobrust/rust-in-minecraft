package local.rustak.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import local.rustak.RustAk;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Skins a Rust viewmodel on the CPU every frame and draws it in first person. Placement follows the prefab:
 * view (Unity camera space) = R + D * rig point; in ADS, the sights shift it so the aim point sits on the view axis.
 */
public final class ViewmodelRenderer {
	private static final Vector3f PIVOT = new Vector3f(0.05f, -0.1f, -0.25f); // view space, around the grip
	private static final Map<ViewmodelRig, Buffers> BUFFERS = new IdentityHashMap<>();
	private static final Vector3f offPos = new Vector3f(), offRot = new Vector3f();

	private static final class Buffers {
		final Matrix4f[] world;
		final float[][] skin; // per part: bone matrices
		final float[][] verts; // per part: skinned vertices, for the CPU path

		Buffers(ViewmodelRig rig) {
			world = new Matrix4f[rig.boneCount];
			for (int i = 0; i < world.length; i++) world[i] = new Matrix4f();
			skin = new float[rig.parts.length][];
			verts = new float[rig.parts.length][];
			for (int p = 0; p < rig.parts.length; p++) {
				skin[p] = new float[rig.parts[p].bones.length * 16];
				verts[p] = new float[rig.parts[p].pos.length * 2];
			}
		}
	}

	public static void render(Gun gun, PoseStack pose, SubmitNodeCollector collector, int light, float pt) {
		LocalPlayer player = Minecraft.getInstance().player;
		Viewmodel vm = gun.viewmodel();
		ViewmodelRig rig = vm.rig;
		Buffers buf = BUFFERS.computeIfAbsent(rig, Buffers::new);
		rig.worldMatrices(vm.update(player, pt), buf.world);

		vm.proceduralOffset(offPos, offRot);
		pose.pushPose();
		pose.translate(offPos.x, offPos.y, offPos.z);
		pose.translate(PIVOT.x, PIVOT.y, PIVOT.z);
		pose.mulPose(Axis.YP.rotationDegrees(offRot.y));
		pose.mulPose(Axis.XP.rotationDegrees(offRot.x));
		pose.mulPose(Axis.ZP.rotationDegrees(offRot.z));
		pose.translate(-PIVOT.x, -PIVOT.y, -PIVOT.z);
		// Everything up to the final vertex goes into the skin matrices: pose * (Unity -> view: flip Z) *
		// (prefab placement, ADS shift) * bone * bind. Vertices then leave already transformed.
		float ads = vm.ads();
		float[] R = gun.viewOffset(rig), D = gun.viewScale(rig);
		float aimX = R[0] + D[0] * rig.aim[0], aimY = R[1] + D[1] * rig.aim[1];
		Matrix4f view = new Matrix4f(pose.last().pose()).scale(1, 1, -1)
			.translate(R[0] - aimX * ads, R[1] - aimY * ads, R[2] + gun.adsDepth() * ads).scale(D[0], D[1], D[2]);
		pose.popPose();
		// the muzzle in true camera space: vanilla's hand pose starts from the inverse of the camera rotation that the
		// model-view stack then applies, so it's taken through the model-view too
		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack());
		Vector3f m = buf.world[rig.muzzleBone].transformPosition(new Vector3f(rig.muzzle[0], rig.muzzle[1], rig.muzzle[2]));
		modelView.transformPosition(view.transformPosition(m), vm.muzzleView);
		if (gun instanceof HitscanGun h) flash(h, vm.muzzleView, modelView, collector);
		Matrix4f tmp = new Matrix4f();
		if (MeshOut.shaders()) {
			// a shader pack only draws render types it knows: skinned here and submitted as an entity type
			for (int p = 0; p < rig.parts.length; p++) {
				ViewmodelRig.Part part = rig.parts[p];
				if (gun.hiddenParts().contains(part.name)) continue;
				float[] v = buf.verts[p];
				skinPart(part, buf.world, buf.skin[p], v, view, tmp);
				var type = MeshOut.solid(part.texture);
				boolean q = MeshOut.quads(type);
				collector.submitCustomGeometry(new PoseStack(), type, (ps, vc) -> MeshOut.skinned(vc, part, v, light, OverlayTexture.NO_OVERLAY, q));
			}
			return;
		}
		for (int p = 0; p < rig.parts.length; p++) GpuSkin.boneMatrices(rig.parts[p], buf.world, buf.skin[p], view, tmp);
		GpuSkin.add(rig, buf.skin, gun.hiddenParts(), light, OverlayTexture.NO_OVERLAY);
		GpuSkin.flush(RenderSystem.getModelViewMatrix());
	}

	private static final Identifier[] FLASH_FRONT = new Identifier[9], FLASH_SIDE = new Identifier[4];
	static {
		for (int i = 0; i < 9; i++) FLASH_FRONT[i] = RustAk.id("textures/particle/muzzle_flash_front_3x3_3x3_" + i + ".png");
		for (int i = 0; i < 4; i++) FLASH_SIDE[i] = RustAk.id("textures/particle/muzzle_flash_side_1x4_1x4_" + i + ".png");
	}

	/**
	 * The gun's muzzle flash (muzzle_flash_front billboard and the stretched muzzle_flash_side) at the muzzle, additive
	 * and full bright. Rust's lives 0.05 s; it's shown at least one frame.
	 */
	private static void flash(HitscanGun gun, Vector3f at, Matrix4f modelView, SubmitNodeCollector collector) {
		float age = (System.nanoTime() - gun.flashAt) / 1e9f;
		if (age > 0.05f && gun.flashDrawn) return;
		gun.flashDrawn = true;
		float life = Math.min(1, age / 0.05f), grow = 0.6f + 0.4f * life;
		Matrix4f toPose = modelView.invert(new Matrix4f());
		// front: a disc facing the camera, rolled
		Vector3f ray = new Vector3f(at).normalize();
		Vector3f u = new Vector3f(0, 1, 0).cross(ray).normalize(), v = new Vector3f(ray).cross(u).normalize();
		float s = gun.flashSize * grow * 0.5f, c = Mth.cos(gun.flashRoll) * s, n = Mth.sin(gun.flashRoll) * s;
		Vector3f x = new Vector3f(u).mul(c).add(new Vector3f(v).mul(n)), y = new Vector3f(v).mul(c).sub(new Vector3f(u).mul(n));
		quad(collector, FLASH_FRONT[gun.flashFrame], toPose,
			new Vector3f(at).sub(x).sub(y), new Vector3f(at).add(x).sub(y), new Vector3f(at).add(x).add(y), new Vector3f(at).sub(x).add(y));
		// side: stretched forward from the muzzle, turned toward the camera around its own axis
		Vector3f axis = new Vector3f(0, 0, -1), w = new Vector3f(axis).cross(ray).normalize().mul(gun.flashSize * 0.35f * grow);
		Vector3f tip = new Vector3f(at).add(new Vector3f(axis).mul(gun.flashLength * grow));
		quad(collector, FLASH_SIDE[gun.flashSide], toPose,
			new Vector3f(at).sub(w), new Vector3f(tip).sub(w), new Vector3f(tip).add(w), new Vector3f(at).add(w));
	}

	/** One textured quad given in camera space, both windings (the pipeline culls). */
	private static void quad(SubmitNodeCollector collector, Identifier tex, Matrix4f toPose, Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
		Vector3f[] q = {toPose.transformPosition(a), toPose.transformPosition(b), toPose.transformPosition(c), toPose.transformPosition(d)};
		float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
		collector.submitCustomGeometry(new PoseStack(), RenderTypes.eyes(tex), (ps, vc) -> {
			for (int i = 0; i < 4; i++) flashVertex(vc, q[i], uv[i]);
			for (int i = 3; i >= 0; i--) flashVertex(vc, q[i], uv[i]);
		});
	}

	private static void flashVertex(VertexConsumer vc, Vector3f p, float[] uv) {
		vc.addVertex(p.x, p.y, p.z).setColor(-1).setUv(uv[0], uv[1]).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightTexture.FULL_BRIGHT).setNormal(0, 0, 1);
	}

	/** Linear blend skinning straight into the final view space. */
	static void skinPart(ViewmodelRig.Part part, Matrix4f[] world, float[] skin, float[] out, Matrix4f view, Matrix4f tmp) {
		for (int b = 0; b < part.bones.length; b++) view.mul(world[part.bones[b]], tmp).mul(part.bind[b]).get(skin, b * 16);
		float[] pos = part.pos, nrm = part.nrm, w = part.weights;
		byte[] idx = part.idx;
		for (int v = 0, n = pos.length / 3; v < n; v++) {
			float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
			float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
			float ox = 0, oy = 0, oz = 0, onx = 0, ony = 0, onz = 0;
			for (int k = 0; k < 4; k++) {
				float wk = w[v * 4 + k];
				if (wk == 0) continue;
				int o = (idx[v * 4 + k] & 0xff) * 16; // column-major 4x4
				ox += wk * (skin[o] * x + skin[o + 4] * y + skin[o + 8] * z + skin[o + 12]);
				oy += wk * (skin[o + 1] * x + skin[o + 5] * y + skin[o + 9] * z + skin[o + 13]);
				oz += wk * (skin[o + 2] * x + skin[o + 6] * y + skin[o + 10] * z + skin[o + 14]);
				onx += wk * (skin[o] * nx + skin[o + 4] * ny + skin[o + 8] * nz);
				ony += wk * (skin[o + 1] * nx + skin[o + 5] * ny + skin[o + 9] * nz);
				onz += wk * (skin[o + 2] * nx + skin[o + 6] * ny + skin[o + 10] * nz);
			}
			float len = Mth.invSqrt(onx * onx + ony * ony + onz * onz + 1e-12f);
			out[v * 6] = ox;
			out[v * 6 + 1] = oy;
			out[v * 6 + 2] = oz;
			out[v * 6 + 3] = onx * len;
			out[v * 6 + 4] = ony * len;
			out[v * 6 + 5] = onz * len;
		}
	}
}
