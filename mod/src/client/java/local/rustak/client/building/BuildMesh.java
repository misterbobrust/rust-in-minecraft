package local.rustak.client.building;

import local.rustak.client.MeshOut;
import local.rustak.client.MipTexture;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.rustak.RustAk;
import local.rustak.building.BuildingDefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** A grade's mesh from models/build/<piece>_<grade>.bin, mirrored into Minecraft space. */
public final class BuildMesh {
	private static final Map<String, BuildMesh> CACHE = new HashMap<>();
	private static final java.util.Set<Identifier> MIPPED = new java.util.HashSet<>();
	final List<Part> parts = new ArrayList<>();

	record Part(Identifier texture, float[] v) { // per vertex: x, y, z, u, v, nx, ny, nz; triangles
	}

	public static BuildMesh get(int piece, int grade) {
		return at("models/build/" + BuildingDefs.PIECES[piece] + "_" + BuildingDefs.GRADES[grade] + ".bin");
	}

	/** Any mesh in this format, by its path under assets/rustak (a door's frame or leaf, models/doors). */
	public static BuildMesh at(String path) {
		return CACHE.computeIfAbsent(path, BuildMesh::load);
	}

	private static BuildMesh load(String key) {
		BuildMesh m = new BuildMesh();
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(RustAk.id(key))) {
			ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
			int n = b.getInt();
			for (int i = 0; i < n; i++) {
				byte[] name = new byte[b.getInt()];
				b.get(name);
				float[] v = new float[b.getInt() * 8];
				b.asFloatBuffer().get(v);
				b.position(b.position() + v.length * 4);
				for (int k = 0; k < v.length; k += 8) { // Unity is left-handed: mirror Z
					v[k + 2] = -v[k + 2];
					v[k + 7] = -v[k + 7];
				}
				Identifier tex = RustAk.id("textures/build/" + new String(name, StandardCharsets.UTF_8) + ".png");
				if (MIPPED.add(tex)) Minecraft.getInstance().getTextureManager().registerAndLoad(tex, new MipTexture(tex));
				m.parts.add(new Part(tex, v));
			}
		} catch (Exception e) {
			throw new RuntimeException("rustak: can't load " + key, e);
		}
		return m;
	}

	/** Submits the mesh at the current pose turned by yaw; colour is ARGB (a tint for the placement ghost). */
	public void submit(PoseStack pose, OrderedSubmitNodeCollector collector, float yaw, int light, int color, boolean translucent) {
		submit(pose, collector, yaw, light, color, translucent, null);
	}

	/** With lit (BuildLight), each vertex takes its own light and shade in place of light and color. */
	public void submit(PoseStack pose, OrderedSubmitNodeCollector collector, float yaw, int light, int color, boolean translucent, BuildLight.Lit lit) {
		pose.pushPose();
		pose.mulPose(Axis.YP.rotation(yaw));
		for (int p = 0; p < parts.size(); p++) {
			Part part = parts.get(p);
			float[] v = part.v();
			var type = translucent ? MeshOut.ghost(part.texture()) : MeshOut.solid(part.texture());
			if (lit != null) {
				int[] lights = lit.light()[p], colors = lit.color()[p];
				var lt = MeshOut.building(part.texture());
				boolean q = MeshOut.quads(lt);
				// a shader pack shades faces itself: only the light (with BuildLight's sky view) goes to it
				int[] cs = q ? null : colors;
				collector.submitCustomGeometry(pose, lt, (ps, vc) -> MeshOut.lit(vc, ps, v, cs, lights, q));
			} else {
				boolean q = MeshOut.quads(type);
				collector.submitCustomGeometry(pose, type, (ps, vc) -> MeshOut.flat(vc, ps, v, color, light, q));
			}
		}
		pose.popPose();
	}
}
