package local.rustak.client.building;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import local.rustak.building.DoorEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-vertex lighting for building blocks, so they sit in the world like its blocks do. Minecraft's own light (block
 * and sky, blended between the cells around each vertex) gives torch glow and shade from terrain. Building blocks
 * don't block Minecraft's sky light, so on top of that each vertex's view of the sky past the building blocks is
 * traced (on a worker thread, again whenever blocks come or go) and dims the sky light: rooms get dark inside,
 * windows and doorways let light in, eaves shade the walls under them.
 */
public final class BuildLight {
	/** Per mesh part, per vertex: packed light and ARGB colour for MeshOut.lit. */
	public record Lit(int[][] light, int[][] color) {
	}

	private static final double REACH = 16;
	// sky directions: straight up, then rings at 60, 30 and 8 degrees above the horizon
	private static final List<Vector3f> DIRS = new ArrayList<>();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "rustak-building-light");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static final Map<BuildingEntity, Cache> CACHE = new WeakHashMap<>();
	private static final Map<DoorEntity, Cache[]> DOORS = new WeakHashMap<>();
	private static final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap CELLS = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
	private static int generation, cellsGeneration = -1;
	private static Level cellsLevel;

	static {
		DIRS.add(new Vector3f(0, 1, 0));
		ring(60, 4, 45);
		ring(30, 8, 0);
		ring(8, 8, 22.5f);
	}

	private static void ring(float elevation, int n, float offset) {
		double e = Math.toRadians(elevation);
		for (int i = 0; i < n; i++) {
			double a = Math.toRadians(offset + i * 360.0 / n);
			DIRS.add(new Vector3f((float) (Math.cos(e) * Math.cos(a)), (float) Math.sin(e), (float) (Math.cos(e) * Math.sin(a))));
		}
	}

	private static final class Cache {
		BuildMesh mesh;
		Matrix4f m;
		float[][] world; // per part: x, y, z, nx, ny, nz per vertex
		int gen = -1, jobGen;
		Future<float[][]> job;
		float[][] vis;
		Lit lit;
		long sampled;
	}

	private BuildLight() {
	}

	public static void init() {
		ClientEntityEvents.ENTITY_LOAD.register((e, level) -> {
			if (e instanceof BuildingEntity) generation++;
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((e, level) -> {
			if (e instanceof BuildingEntity) generation++;
		});
	}

	/** This block's per-vertex lighting, or null until its sky view is traced (the entity's own light is used then). */
	public static Lit get(BuildingEntity b) {
		Cache c = CACHE.computeIfAbsent(b, k -> new Cache());
		BuildMesh mesh = BuildMesh.get(b.piece(), b.grade());
		Matrix4f m = new Matrix4f().translation((float) b.getX(), (float) b.getY(), (float) b.getZ()).rotateY(b.yawRad());
		if (c.m != null && !m.equals(c.m)) generation++; // it shades its neighbours differently now
		return lit(c, b.level(), mesh, m, b.obb().bounds());
	}

	/**
	 * A door's part lit like building blocks: the frame (part 0) where it stands, a leaf (part 1 + hinge) as it
	 * hangs closed or open, whichever way the door was last set (kept while it swings).
	 */
	public static Lit door(DoorEntity d, int part, BuildMesh mesh) {
		Cache[] cs = DOORS.computeIfAbsent(d, k -> new Cache[1 + 2 * d.def().hinges.size()]);
		boolean open = d.isOpen();
		int slot = part == 0 ? 0 : 1 + 2 * (part - 1) + (open ? 1 : 0);
		if (cs[slot] == null) cs[slot] = new Cache();
		Matrix4f m = new Matrix4f().translation((float) d.getX(), (float) d.getY(), (float) d.getZ()).rotateY(d.yawRad());
		if (part > 0) {
			Vector3f pv = d.def().hinges.get(part - 1).pivot;
			m.translate(pv).rotateY(d.def().turn(part - 1, open, 100)).translate(-pv.x, -pv.y, -pv.z);
		}
		return lit(cs[slot], d.level(), mesh, m, d.getBoundingBox());
	}

	private static Lit lit(Cache c, Level level, BuildMesh mesh, Matrix4f m, AABB box) {
		if (c.mesh != mesh || !m.equals(c.m)) {
			c.mesh = mesh;
			c.m = m;
			c.world = world(mesh, m);
			c.vis = null;
			c.lit = null;
			c.gen = -1;
			if (c.job != null) c.job.cancel(false);
			c.job = null;
		}
		if (c.job != null && c.job.isDone()) {
			try {
				c.vis = c.job.get();
				c.lit = null;
			} catch (Exception ignored) {
				// keep what there was; not retried until blocks change again
			}
			c.gen = c.jobGen;
			c.job = null;
		}
		if (c.gen != generation && c.job == null) {
			List<Obb> solids = occluders(level, box);
			float[][] world = c.world;
			c.jobGen = generation;
			c.job = WORKER.submit(() -> visibility(world, solids));
		}
		if (c.vis == null) return null;
		long now = level.getGameTime();
		if (c.lit == null || now - c.sampled >= 10) {
			c.lit = sample(level, c.world, c.vis);
			c.sampled = now;
		}
		return c.lit;
	}

	/**
	 * How much of a point's sky light gets past the building blocks over and around it (1 in the open), as the
	 * multiplier the blocks' own surfaces get: 0.25 in a closed room. Traced once per cell, again when blocks change.
	 */
	public static float skyFactor(Level level, Vec3 p) {
		if (level != cellsLevel || cellsGeneration != generation) {
			CELLS.clear();
			cellsLevel = level;
			cellsGeneration = generation;
		}
		long key = BlockPos.asLong((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
		if (CELLS.containsKey(key)) return CELLS.get(key);
		Vec3 from = new Vec3(Math.floor(p.x) + 0.5, Math.floor(p.y) + 0.5, Math.floor(p.z) + 0.5);
		AABB around = new AABB(from.x - REACH, from.y - 1, from.z - REACH, from.x + REACH, from.y + REACH, from.z + REACH);
		List<Obb> solids = new ArrayList<>();
		for (BuildingEntity b : BuildingCollision.blocksNear(level, around)) solids.addAll(b.solids());
		float f = solids.isEmpty() ? 1 : (float) (0.25 + 0.75 * sky(from, 0, 1, 0, solids));
		CELLS.put(key, f);
		return f;
	}

	/** Mesh vertices and normals in world space (m: the mesh's local space to the world, as it's drawn). */
	private static float[][] world(BuildMesh mesh, Matrix4f m) {
		float[][] out = new float[mesh.parts.size()][];
		Vector3f a = new Vector3f();
		for (int p = 0; p < out.length; p++) {
			float[] v = mesh.parts.get(p).v();
			float[] w = new float[v.length / 8 * 6];
			for (int i = 0, j = 0; i < v.length; i += 8, j += 6) {
				m.transformPosition(a.set(v[i], v[i + 1], v[i + 2]));
				w[j] = a.x;
				w[j + 1] = a.y;
				w[j + 2] = a.z;
				m.transformDirection(a.set(v[i + 5], v[i + 6], v[i + 7])).normalize();
				w[j + 3] = a.x;
				w[j + 4] = a.y;
				w[j + 5] = a.z;
			}
			out[p] = w;
		}
		return out;
	}

	/** Collision boxes of every building block that can stand between the box and the sky. */
	private static List<Obb> occluders(Level level, AABB box) {
		AABB around = new AABB(box.minX - REACH, box.minY - 1, box.minZ - REACH, box.maxX + REACH, box.maxY + REACH, box.maxZ + REACH);
		List<Obb> out = new ArrayList<>();
		for (BuildingEntity o : BuildingCollision.blocksNear(level, around)) out.addAll(o.solids());
		return out;
	}

	private record Probe(int x, int y, int z, int nx, int ny, int nz) {
	}

	/**
	 * Per vertex, the share of sky it sees, weighted toward the zenith with a little for the horizon. Only directions
	 * in front of the surface (and a bit past grazing) count; the ray starts just off the surface, so its own block
	 * shades it too (a ceiling's underside sees only past the floor's edge).
	 */
	private static float[][] visibility(float[][] world, List<Obb> solids) {
		Map<Probe, Float> memo = new HashMap<>();
		float[][] out = new float[world.length][];
		for (int p = 0; p < world.length; p++) {
			float[] w = world[p];
			float[] vis = new float[w.length / 6];
			for (int i = 0; i < vis.length; i++) {
				int j = i * 6;
				float x = w[j], y = w[j + 1], z = w[j + 2], nx = w[j + 3], ny = w[j + 4], nz = w[j + 5];
				Probe key = new Probe(Math.round(x * 8), Math.round(y * 8), Math.round(z * 8), Math.round(nx * 4), Math.round(ny * 4), Math.round(nz * 4));
				Float known = memo.get(key);
				if (known == null) {
					known = sky(new Vec3(x + nx * 0.15, y + ny * 0.15 + 0.01, z + nz * 0.15), nx, ny, nz, solids);
					memo.put(key, known);
				}
				vis[i] = known;
			}
			out[p] = vis;
		}
		return out;
	}

	private static float sky(Vec3 from, float nx, float ny, float nz, List<Obb> solids) {
		float seen = 0, total = 0;
		for (Vector3f d : DIRS) {
			if (d.x * nx + d.y * ny + d.z * nz < -0.55f) continue;
			float weight = d.y + 0.15f;
			total += weight;
			Vec3 dir = new Vec3(d.x, d.y, d.z);
			boolean blocked = false;
			for (Obb o : solids) {
				if (o.raycast(from, dir, REACH) >= 0) {
					blocked = true;
					break;
				}
			}
			if (!blocked) seen += weight;
		}
		return total == 0 ? 1 : seen / total;
	}

	/**
	 * Minecraft's light at each vertex, half a block out along its normal, blended over the eight cells around that
	 * point (opaque cells left out, like smooth lighting); the sky light dimmed by the traced sky view. The vertex
	 * colour takes the face shade blocks get and a soft occlusion shade.
	 */
	private static Lit sample(Level level, float[][] world, float[][] vis) {
		Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int[][] light = new int[world.length][], color = new int[world.length][];
		for (int p = 0; p < world.length; p++) {
			float[] w = world[p];
			int n = w.length / 6;
			int[] l = new int[n], c = new int[n];
			for (int i = 0; i < n; i++) {
				int j = i * 6;
				double qx = w[j] + w[j + 3] * 0.5 - 0.5, qy = w[j + 1] + w[j + 4] * 0.5 - 0.5, qz = w[j + 2] + w[j + 5] * 0.5 - 0.5;
				int bx = (int) Math.floor(qx), by = (int) Math.floor(qy), bz = (int) Math.floor(qz);
				double tx = qx - bx, ty = qy - by, tz = qz - bz;
				double sky = 0, block = 0, weight = 0;
				for (int k = 0; k < 8; k++) {
					int cell = cell(level, cells, m, bx + (k & 1), by + (k >> 1 & 1), bz + (k >> 2 & 1));
					if (cell < 0) continue; // opaque
					double f = ((k & 1) == 1 ? tx : 1 - tx) * ((k >> 1 & 1) == 1 ? ty : 1 - ty) * ((k >> 2 & 1) == 1 ? tz : 1 - tz);
					sky += f * (cell >> 4);
					block += f * (cell & 15);
					weight += f;
				}
				if (weight < 1e-4) { // buried: take the cell above
					int cell = Math.max(0, cell(level, cells, m, bx, by + 2, bz));
					sky = cell >> 4;
					block = cell & 15;
				} else {
					sky /= weight;
					block /= weight;
				}
				float v = vis[p][i];
				l[i] = LightTexture.pack((int) Math.round(block), (int) Math.round(sky * (0.25 + 0.75 * v)));
				// Minecraft's block face shades: top 1, bottom 0.5, north/south 0.8, east/west 0.6
				float nx = w[j + 3], ny = w[j + 4], nz = w[j + 5];
				float face = nx * nx * 0.6f + nz * nz * 0.8f + ny * ny * (ny > 0 ? 1 : 0.5f);
				int g = Math.min(255, Math.round(255 * face * (0.72f + 0.28f * v)));
				c[i] = 0xFF000000 | g << 16 | g << 8 | g;
			}
			light[p] = l;
			color[p] = c;
		}
		return new Lit(light, color);
	}

	/** sky << 4 | block for a cell, or -1 when it's opaque. */
	private static int cell(Level level, Long2IntOpenHashMap cells, BlockPos.MutableBlockPos m, int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		if (cells.containsKey(key)) return cells.get(key);
		m.set(x, y, z);
		int v = level.getBlockState(m).isSolidRender() ? -1 : level.getBrightness(LightLayer.SKY, m) << 4 | level.getBrightness(LightLayer.BLOCK, m);
		cells.put(key, v);
		return v;
	}
}
