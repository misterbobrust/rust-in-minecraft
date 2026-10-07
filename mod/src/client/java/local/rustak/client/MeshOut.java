package local.rustak.client;

import java.util.HashMap;
import java.util.Map;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import local.rustak.RustAk;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Writes Rust's triangle meshes into Minecraft's buffers. Vanilla's entity render types draw quads, which costs a
 * doubled vertex per triangle; these draw triangles with the same entity shader (cutout, no cull, lightmap, overlay).
 * Vertices go out through the single-call addVertex, which BufferBuilder writes straight to memory for the entity
 * format instead of six chained setters.
 */
public final class MeshOut {
	private static final RenderPipeline TRIANGLES = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(RustAk.id("pipeline/entity_cutout_no_cull_triangles"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.withShaderDefine("PER_FACE_LIGHTING")
		.withSampler("Sampler1")
		.withCull(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES)
		.build();
	// placement ghosts: translucent without depth writes, so no layer of the piece hides another behind it whatever
	// order the triangles come in (with depth writes, parts vanished and came back with the viewing distance)
	private static final RenderPipeline GHOST = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(RustAk.id("pipeline/entity_ghost_triangles"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.withShaderDefine("PER_FACE_LIGHTING")
		.withSampler("Sampler1")
		.withBlend(BlendFunction.TRANSLUCENT)
		.withDepthWrite(false)
		.withCull(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES)
		.build();
	// building blocks: shaded like Minecraft's blocks (a fixed shade per face direction, baked into the vertex colour
	// with BuildLight's light) instead of the entity lighting that turns with the camera
	private static final RenderPipeline BUILDING = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
		.withLocation(RustAk.id("pipeline/building_triangles"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.withShaderDefine("NO_CARDINAL_LIGHTING")
		.withSampler("Sampler1")
		.withCull(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES)
		.build();
	private static final Map<Identifier, RenderType> TYPES = new HashMap<>(), GHOSTS = new HashMap<>(), BUILDINGS = new HashMap<>();
	/** The triangle types above; everything else these writers feed is a vanilla quad type. */
	private static final java.util.Set<RenderType> TRIANGLE_TYPES = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static java.lang.reflect.Method irisInstance, irisInUse;
	private static boolean irisMissing;
	private static boolean shaders;
	private static long shadersChecked;

	private MeshOut() {
	}

	/**
	 * Whether an Iris shader pack is on (checked through Iris's API by reflection, twice a second). Shader packs only
	 * draw the render types they know, so then everything here goes through vanilla's entity types, as quads.
	 */
	public static boolean shaders() {
		long now = System.nanoTime();
		if (now - shadersChecked < 500_000_000L) return shaders;
		shadersChecked = now;
		if (irisMissing) return shaders = false;
		try {
			if (irisInUse == null) {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisInstance = api.getMethod("getInstance");
				irisInUse = api.getMethod("isShaderPackInUse");
			}
			shaders = (boolean) irisInUse.invoke(irisInstance.invoke(null));
		} catch (ReflectiveOperationException | LinkageError e) {
			irisMissing = true;
			shaders = false;
		}
		return shaders;
	}

	/** Whether a type from here takes quads (a vanilla type): the writers then double each triangle's last vertex. */
	public static boolean quads(RenderType type) {
		return !TRIANGLE_TYPES.contains(type);
	}

	/** entityCutoutNoCull, drawn as triangles. */
	public static RenderType solid(Identifier texture) {
		if (shaders()) return RenderTypes.entityCutoutNoCull(texture);
		return TYPES.computeIfAbsent(texture, t -> triangles(RenderType.create("rustak_entity_cutout_no_cull_triangles",
			RenderSetup.builder(TRIANGLES).withTexture("Sampler0", t).useLightmap().useOverlay().affectsCrumbling()
				.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE).createRenderSetup())));
	}

	/** A building block lit per vertex (BuildLight): colour and light carry all the shading. */
	public static RenderType building(Identifier texture) {
		if (shaders()) return RenderTypes.entityCutoutNoCull(texture);
		return BUILDINGS.computeIfAbsent(texture, t -> triangles(RenderType.create("rustak_building_triangles",
			RenderSetup.builder(BUILDING).withTexture("Sampler0", t).useLightmap().useOverlay().affectsCrumbling()
				.setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE).createRenderSetup())));
	}

	/** A see-through placement ghost, drawn as triangles without depth writes. */
	public static RenderType ghost(Identifier texture) {
		if (shaders()) return RenderTypes.entityTranslucent(texture);
		return GHOSTS.computeIfAbsent(texture, t -> triangles(RenderType.create("rustak_entity_ghost_triangles",
			RenderSetup.builder(GHOST).withTexture("Sampler0", t).useLightmap().useOverlay().createRenderSetup())));
	}

	private static RenderType triangles(RenderType type) {
		TRIANGLE_TYPES.add(type);
		return type;
	}

	/** Skinned vertices (x, y, z, nx, ny, nz, already in their final space) indexed by tris, for a solid() type. */
	public static void skinned(VertexConsumer vc, ViewmodelRig.Part part, float[] v, int light, int overlay, boolean quads) {
		int[] tris = part.tris;
		float[] uv = part.uv;
		for (int t = 0; t < tris.length; t++) {
			int i = tris[t], o = i * 6;
			vc.addVertex(v[o], v[o + 1], v[o + 2], -1, uv[i * 2], uv[i * 2 + 1], overlay, light, v[o + 3], v[o + 4], v[o + 5]);
			if (quads && t % 3 == 2) vc.addVertex(v[o], v[o + 1], v[o + 2], -1, uv[i * 2], uv[i * 2 + 1], overlay, light, v[o + 3], v[o + 4], v[o + 5]);
		}
	}

	/** Like flat, with each vertex's own ARGB colour (white without colors) and packed light. */
	public static void lit(VertexConsumer vc, PoseStack.Pose pose, float[] v, int[] colors, int[] lights, boolean quads) {
		Matrix4f m = pose.pose();
		Matrix3f n = pose.normal();
		int overlay = OverlayTexture.NO_OVERLAY;
		for (int o = 0, i = 0; o < v.length; o += 8, i++) {
			float x = v[o], y = v[o + 1], z = v[o + 2], nx = v[o + 5], ny = v[o + 6], nz = v[o + 7];
			float tx = n.m00() * nx + n.m10() * ny + n.m20() * nz, ty = n.m01() * nx + n.m11() * ny + n.m21() * nz,
				tz = n.m02() * nx + n.m12() * ny + n.m22() * nz;
			float len = (float) (1 / Math.sqrt(tx * tx + ty * ty + tz * tz + 1e-12f));
			vc.addVertex(m.m00() * x + m.m10() * y + m.m20() * z + m.m30(), m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
				m.m02() * x + m.m12() * y + m.m22() * z + m.m32(), colors == null ? -1 : colors[i], v[o + 3], v[o + 4], overlay, lights[i],
				tx * len, ty * len, tz * len);
			if (quads && i % 3 == 2) {
				vc.addVertex(m.m00() * x + m.m10() * y + m.m20() * z + m.m30(), m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
					m.m02() * x + m.m12() * y + m.m22() * z + m.m32(), colors == null ? -1 : colors[i], v[o + 3], v[o + 4], overlay, lights[i],
					tx * len, ty * len, tz * len);
			}
		}
	}

	/**
	 * A triangle list of interleaved (x, y, z, u, v, nx, ny, nz) vertices, transformed by pose. With quads (for vanilla
	 * types such as entityTranslucent) each triangle's last vertex is doubled.
	 */
	public static void flat(VertexConsumer vc, PoseStack.Pose pose, float[] v, int color, int light, boolean quads) {
		Matrix4f m = pose.pose();
		Matrix3f n = pose.normal();
		int overlay = OverlayTexture.NO_OVERLAY;
		for (int i = 0; i < v.length; i += 24) {
			for (int k = 0; k < (quads ? 4 : 3); k++) {
				int o = i + Math.min(k, 2) * 8;
				float x = v[o], y = v[o + 1], z = v[o + 2], nx = v[o + 5], ny = v[o + 6], nz = v[o + 7];
				float tx = n.m00() * nx + n.m10() * ny + n.m20() * nz, ty = n.m01() * nx + n.m11() * ny + n.m21() * nz,
					tz = n.m02() * nx + n.m12() * ny + n.m22() * nz;
				float len = (float) (1 / Math.sqrt(tx * tx + ty * ty + tz * tz + 1e-12f));
				vc.addVertex(m.m00() * x + m.m10() * y + m.m20() * z + m.m30(), m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
					m.m02() * x + m.m12() * y + m.m22() * z + m.m32(), color, v[o + 3], v[o + 4], overlay, light,
					tx * len, ty * len, tz * len);
			}
		}
	}
}
