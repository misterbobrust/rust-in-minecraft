package local.rustak.client;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import local.rustak.RustAk;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * A Rust viewmodel from models/<name>_rig.bin: skeleton, skinned parts, clips with their animator
 * speeds, camera clips, and the prefab's placement: view (Unity camera space) = R + D * rig point.
 */
public final class ViewmodelRig {
	public final int boneCount;
	public final int[] parent;
	public final float[] bindPos, bindRot, bindScale; // local bind pose per bone: 3, 4 (xyzw), 3 floats
	public final Part[] parts;
	public final Map<String, Clip> clips = new HashMap<>();
	/** Camera clips by animator state: camera euler angles (degrees, Unity axes) per frame at 30 fps. */
	public final Map<String, float[]> cameraClips = new HashMap<>();
	public final Map<String, Float> cameraSpeed = new HashMap<>();
	public final float[] R = new float[3], D = new float[3], aim = new float[3], muzzle = new float[3];
	public int muzzleBone;

	public static final class Part {
		public String name;
		public Identifier texture;
		public int[] bones; // global bone index per part-local bone
		public Matrix4f[] bind;
		public float[] pos, nrm, uv, weights; // 3, 3, 2, 4 per vertex
		public byte[] idx; // 4 part-local bone indices per vertex
		public int[] tris;
	}

	public static final class Clip {
		public String name;
		public int frames;
		public float fps, speed;
		public int[] trackBone;
		public int[] trackMask; // 1 position, 2 rotation, 4 scale
		public float[][] trackData; // per frame: [pos3][rot4][scale3], present parts only
		public float[] eventTime;
		public String[] eventName;

		public float length() {
			return (frames - 1) / fps;
		}
	}

	private static String str(ByteBuffer b) {
		byte[] s = new byte[b.getInt()];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}

	public ViewmodelRig(String name) {
		ByteBuffer b;
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(RustAk.id("models/" + name + "_rig.bin"))) {
			b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
		} catch (Exception e) {
			throw new RuntimeException("rustak: can't load " + name + "_rig.bin", e);
		}
		int version = b.getInt();
		if (version < 3) throw new IllegalStateException("rustak: " + name + "_rig.bin is an old format, rebuild the mod with the installer");
		for (float[] v : new float[][] {R, D, aim}) for (int k = 0; k < 3; k++) v[k] = b.getFloat();
		if (version >= 4) { // muzzle point: a bone and the offset under it
			muzzleBone = b.getInt();
			for (int k = 0; k < 3; k++) muzzle[k] = b.getFloat();
		}
		boneCount = b.getInt();
		parent = new int[boneCount];
		bindPos = new float[boneCount * 3];
		bindRot = new float[boneCount * 4];
		bindScale = new float[boneCount * 3];
		for (int i = 0; i < boneCount; i++) {
			parent[i] = b.getInt();
			for (int k = 0; k < 3; k++) bindPos[i * 3 + k] = b.getFloat();
			for (int k = 0; k < 4; k++) bindRot[i * 4 + k] = b.getFloat();
			for (int k = 0; k < 3; k++) bindScale[i * 3 + k] = b.getFloat();
		}
		parts = new Part[b.getInt()];
		for (int p = 0; p < parts.length; p++) {
			Part part = parts[p] = new Part();
			part.name = str(b);
			part.texture = RustAk.id("textures/w/" + str(b) + ".png");
			int nb = b.getInt();
			part.bones = new int[nb];
			part.bind = new Matrix4f[nb];
			for (int i = 0; i < nb; i++) {
				part.bones[i] = b.getInt();
				float[] m = new float[16];
				for (int k = 0; k < 16; k++) m[k] = b.getFloat();
				part.bind[i] = new Matrix4f().set(m).transpose(); // stored row-major
			}
			int nv = b.getInt();
			part.pos = new float[nv * 3];
			part.nrm = new float[nv * 3];
			part.uv = new float[nv * 2];
			part.idx = new byte[nv * 4];
			part.weights = new float[nv * 4];
			for (int v = 0; v < nv; v++) {
				for (int k = 0; k < 3; k++) part.pos[v * 3 + k] = b.getFloat();
				for (int k = 0; k < 3; k++) part.nrm[v * 3 + k] = b.getFloat();
				part.uv[v * 2] = b.getFloat();
				part.uv[v * 2 + 1] = b.getFloat();
				b.get(part.idx, v * 4, 4);
				for (int k = 0; k < 4; k++) part.weights[v * 4 + k] = b.getFloat();
			}
			part.tris = new int[b.getInt()];
			for (int i = 0; i < part.tris.length; i++) part.tris[i] = b.getInt();
		}
		int nc = b.getInt();
		for (int c = 0; c < nc; c++) {
			Clip clip = new Clip();
			clip.name = str(b);
			clip.frames = b.getInt();
			clip.fps = b.getFloat();
			clip.speed = b.getFloat();
			int nt = b.getInt();
			clip.trackBone = new int[nt];
			clip.trackMask = new int[nt];
			clip.trackData = new float[nt][];
			for (int t = 0; t < nt; t++) {
				clip.trackBone[t] = b.getInt();
				int mask = clip.trackMask[t] = b.get();
				int width = ((mask & 1) != 0 ? 3 : 0) + ((mask & 2) != 0 ? 4 : 0) + ((mask & 4) != 0 ? 3 : 0);
				float[] d = clip.trackData[t] = new float[width * clip.frames];
				for (int i = 0; i < d.length; i++) d[i] = b.getFloat();
			}
			int ne = b.getInt();
			clip.eventTime = new float[ne];
			clip.eventName = new String[ne];
			for (int e = 0; e < ne; e++) {
				clip.eventTime[e] = b.getFloat();
				clip.eventName[e] = str(b);
			}
			clips.put(clip.name, clip);
		}
		int ncam = b.getInt();
		for (int c = 0; c < ncam; c++) {
			String state = str(b);
			float[] euler = new float[b.getInt() * 3];
			cameraSpeed.put(state, b.getFloat());
			for (int i = 0; i < euler.length; i++) euler[i] = b.getFloat();
			cameraClips.put(state, euler);
		}
	}

	/** A local pose for every bone. */
	public static final class Pose {
		public final float[] pos, rot, scale;

		Pose(int n) {
			pos = new float[n * 3];
			rot = new float[n * 4];
			scale = new float[n * 3];
		}
	}

	public Pose newPose() {
		Pose p = new Pose(boneCount);
		System.arraycopy(bindPos, 0, p.pos, 0, bindPos.length);
		System.arraycopy(bindRot, 0, p.rot, 0, bindRot.length);
		System.arraycopy(bindScale, 0, p.scale, 0, bindScale.length);
		return p;
	}

	/** Writes clip at time (seconds, clamped) into out; bones without tracks keep their bind pose. */
	public void sample(Clip clip, float time, Pose out) {
		System.arraycopy(bindPos, 0, out.pos, 0, bindPos.length);
		System.arraycopy(bindRot, 0, out.rot, 0, bindRot.length);
		System.arraycopy(bindScale, 0, out.scale, 0, bindScale.length);
		float f = Math.max(0, Math.min(clip.frames - 1, time * clip.fps));
		int f0 = (int) f, f1 = Math.min(clip.frames - 1, f0 + 1);
		float a = f - f0;
		for (int t = 0; t < clip.trackBone.length; t++) {
			int bone = clip.trackBone[t], mask = clip.trackMask[t];
			float[] d = clip.trackData[t];
			int width = d.length / clip.frames, o = 0;
			if ((mask & 1) != 0) {
				for (int k = 0; k < 3; k++) out.pos[bone * 3 + k] = lerp(d[f0 * width + o + k], d[f1 * width + o + k], a);
				o += 3;
			}
			if ((mask & 2) != 0) {
				nlerp(d, f0 * width + o, d, f1 * width + o, a, out.rot, bone * 4);
				o += 4;
			}
			if ((mask & 4) != 0) {
				for (int k = 0; k < 3; k++) out.scale[bone * 3 + k] = lerp(d[f0 * width + o + k], d[f1 * width + o + k], a);
			}
		}
	}

	/** out = lerp(a, b, t) per bone; out may alias a. */
	public static void blend(Pose a, Pose b, float t, Pose out) {
		for (int i = 0; i < a.pos.length; i++) {
			out.pos[i] = lerp(a.pos[i], b.pos[i], t);
			out.scale[i] = lerp(a.scale[i], b.scale[i], t);
		}
		for (int i = 0; i < a.rot.length; i += 4) nlerp(a.rot, i, b.rot, i, t, out.rot, i);
	}

	static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	static void nlerp(float[] a, int ai, float[] b, int bi, float t, float[] out, int oi) {
		float dot = a[ai] * b[bi] + a[ai + 1] * b[bi + 1] + a[ai + 2] * b[bi + 2] + a[ai + 3] * b[bi + 3];
		float s = dot < 0 ? -1 : 1;
		float x = lerp(a[ai], s * b[bi], t), y = lerp(a[ai + 1], s * b[bi + 1], t);
		float z = lerp(a[ai + 2], s * b[bi + 2], t), w = lerp(a[ai + 3], s * b[bi + 3], t);
		float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		out[oi] = x / len;
		out[oi + 1] = y / len;
		out[oi + 2] = z / len;
		out[oi + 3] = w / len;
	}

	/** Model-space bone matrices for a local pose. Bones are stored parent-first. */
	public void worldMatrices(Pose p, Matrix4f[] out) {
		for (int i = 0; i < boneCount; i++) {
			Matrix4f m = out[i].translationRotateScale(p.pos[i * 3], p.pos[i * 3 + 1], p.pos[i * 3 + 2],
				p.rot[i * 4], p.rot[i * 4 + 1], p.rot[i * 4 + 2], p.rot[i * 4 + 3],
				p.scale[i * 3], p.scale[i * 3 + 1], p.scale[i * 3 + 2]);
			if (parent[i] >= 0) out[parent[i]].mul(m, m);
		}
	}
}
