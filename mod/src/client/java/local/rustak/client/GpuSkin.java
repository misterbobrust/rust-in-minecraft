package local.rustak.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import local.rustak.RustAk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * GPU skinning for Rust's rigs. Each part's mesh (position, uv, normal, 4 bone ids, 4 weights) is uploaded once; a
 * draw only writes the part's bone matrices (bone * bind, already multiplied into the pose's space) with its light and
 * overlay into its block of a shared uniform buffer, and shaders/core/skinned.vsh blends them. Draws are queued and
 * flushed in one pass. Fragments go through vanilla's
 * core/entity, so it looks like the entity cutout path it replaces.
 */
public final class GpuSkin {
	private static final int MAX_BONES = 128;
	private static final int SKIN_BYTES = 16 + MAX_BONES * 64;
	static final VertexFormatElement BONE_IDS = VertexFormatElement.register(29, 0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.UV, 4);
	static final VertexFormatElement BONE_WEIGHTS = VertexFormatElement.register(30, 0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4);
	static final VertexFormat FORMAT = VertexFormat.builder()
		.add("Position", VertexFormatElement.POSITION)
		.add("UV0", VertexFormatElement.UV0)
		.add("Normal", VertexFormatElement.NORMAL)
		.padding(1)
		.add("BoneIds", BONE_IDS)
		.add("BoneWeights", BONE_WEIGHTS)
		.build();
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
		.withLocation(RustAk.id("pipeline/skinned"))
		.withVertexShader(RustAk.id("core/skinned"))
		.withFragmentShader(Identifier.withDefaultNamespace("core/entity"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.withShaderDefine("PER_FACE_LIGHTING")
		.withSampler("Sampler0")
		.withSampler("Sampler1")
		.withSampler("Sampler2")
		.withUniform("Skin", UniformType.UNIFORM_BUFFER)
		.withCull(false)
		.withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES)
		.build();
	private static final Map<ViewmodelRig.Part, Mesh> MESHES = new IdentityHashMap<>();

	private GpuSkin() {
	}

	/** A part's static buffers. */
	private static final class Mesh {
		final GpuBuffer vertices, indices;
		final int indexCount;
		final boolean shortIndices;

		Mesh(ViewmodelRig.Part part) {
			int n = part.pos.length / 3;
			if (part.bones.length > MAX_BONES) throw new IllegalStateException("rustak: " + part.name + " has " + part.bones.length + " bones");
			ByteBuffer v = ByteBuffer.allocateDirect(n * FORMAT.getVertexSize()).order(ByteOrder.nativeOrder());
			for (int i = 0; i < n; i++) {
				v.putFloat(part.pos[i * 3]).putFloat(part.pos[i * 3 + 1]).putFloat(part.pos[i * 3 + 2]);
				v.putFloat(part.uv[i * 2]).putFloat(part.uv[i * 2 + 1]);
				for (int k = 0; k < 3; k++) v.put((byte) Math.round(Math.max(-1, Math.min(1, part.nrm[i * 3 + k])) * 127));
				v.put((byte) 0);
				v.put(part.idx, i * 4, 4);
				// weights as normalized bytes; the rounding remainder goes to the largest so they still sum to 1
				int[] w = new int[4];
				int sum = 0, big = 0;
				for (int k = 0; k < 4; k++) {
					w[k] = Math.round(part.weights[i * 4 + k] * 255);
					sum += w[k];
					if (w[k] > w[big]) big = k;
				}
				w[big] += 255 - sum;
				for (int k = 0; k < 4; k++) v.put((byte) w[k]);
			}
			v.flip();
			boolean shortIdx = n <= 65535;
			ByteBuffer ix = ByteBuffer.allocateDirect(part.tris.length * (shortIdx ? 2 : 4)).order(ByteOrder.nativeOrder());
			for (int t : part.tris) {
				if (shortIdx) ix.putShort((short) t);
				else ix.putInt(t);
			}
			ix.flip();
			var device = RenderSystem.getDevice();
			vertices = device.createBuffer(() -> "rustak " + part.name + " vertices", GpuBuffer.USAGE_VERTEX, v);
			indices = device.createBuffer(() -> "rustak " + part.name + " indices", GpuBuffer.USAGE_INDEX, ix);
			indexCount = part.tris.length;
			shortIndices = shortIdx;
		}
	}

	private record Draw(Mesh mesh, AbstractTexture texture, int offset) {
	}

	private static final List<Draw> QUEUE = new ArrayList<>();
	private static ByteBuffer staging = ByteBuffer.allocateDirect(SKIN_BYTES * 16).order(ByteOrder.nativeOrder());
	private static GpuBuffer skins;

	/**
	 * Queues the rig's parts (minus hidden ones) for the next flush. skins[p] holds part p's bone matrices
	 * (column-major, 16 floats each) in the space flush's model-view expects. Each draw gets its own block, so the same
	 * rig can be queued for several players.
	 */
	public static void add(ViewmodelRig rig, float[][] boneMats, Set<String> hidden, int light, int overlay) {
		Minecraft mc = Minecraft.getInstance();
		int align = RenderSystem.getDevice().getUniformOffsetAlignment();
		for (int p = 0; p < rig.parts.length; p++) {
			ViewmodelRig.Part part = rig.parts[p];
			if (hidden.contains(part.name)) continue;
			int size = 16 + part.bones.length * 64, offset = (staging.position() + align - 1) / align * align;
			if (offset + size > staging.capacity()) {
				ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(staging.capacity() * 2, offset + size)).order(ByteOrder.nativeOrder());
				staging.flip();
				staging = bigger.put(staging);
			}
			staging.position(offset);
			staging.putInt(overlay & 0xFFFF).putInt(overlay >>> 16).putInt(light & 0xFFFF).putInt(light >>> 16);
			float[] s = boneMats[p];
			for (int i = 0, n = part.bones.length * 16; i < n; i++) staging.putFloat(s[i]);
			// the texture may upload on first use, which can't happen inside the pass
			QUEUE.add(new Draw(MESHES.computeIfAbsent(part, Mesh::new), mc.getTextureManager().getTexture(part.texture), offset));
		}
	}

	/** Draws the queue into the main target with the current projection and the given model-view, then empties it. */
	public static void flush(Matrix4f modelView) {
		if (QUEUE.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		var device = RenderSystem.getDevice();
		var encoder = device.createCommandEncoder();
		int used = staging.position();
		// each draw binds a whole block (the shader's size) but only its bones are written and read: keep one block of slack
		if (skins == null || skins.size() < used + SKIN_BYTES) {
			if (skins != null) skins.close();
			skins = device.createBuffer(() -> "rustak skins", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, staging.capacity() + SKIN_BYTES);
		}
		staging.flip();
		encoder.writeToBuffer(skins.slice(0, used), staging);
		staging.clear();
		RenderTarget target = mc.getMainRenderTarget();
		GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
		GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
			.writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
		var samplers = RenderSystem.getSamplerCache();
		try (RenderPass pass = encoder.createRenderPass(() -> "rustak skinned", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.bindTexture("Sampler1", mc.gameRenderer.overlayTexture().getTextureView(), samplers.getClampToEdge(FilterMode.LINEAR));
			pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), samplers.getClampToEdge(FilterMode.LINEAR));
			for (Draw d : QUEUE) {
				pass.bindTexture("Sampler0", d.texture.getTextureView(), d.texture.getSampler());
				pass.setUniform("Skin", skins.slice(d.offset, SKIN_BYTES));
				pass.setVertexBuffer(0, d.mesh.vertices);
				pass.setIndexBuffer(d.mesh.indices, d.mesh.shortIndices ? VertexFormat.IndexType.SHORT : VertexFormat.IndexType.INT);
				pass.drawIndexed(0, 0, d.mesh.indexCount, 1);
			}
		} finally {
			QUEUE.clear();
		}
	}

	/** Bone matrices of a part: view * world[bone] * bind, column-major into out. */
	static void boneMatrices(ViewmodelRig.Part part, Matrix4f[] world, float[] out, Matrix4f view, Matrix4f tmp) {
		for (int b = 0; b < part.bones.length; b++) view.mul(world[part.bones[b]], tmp).mul(part.bind[b]).get(out, b * 16);
	}
}
