package local.rustak.client.building;

import local.rustak.client.MeshOut;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import local.rustak.RustAk;
import local.rustak.building.Building;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.client.FxManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rust's gib destruction on the client: the grade model's gib pieces fly
 * apart from the block like Rust's (outward from their local position, random spin), fall, bounce
 * off blocks and fade. Each piece plays the material's fx/building/*_gib effect.
 */
public final class GibSystem {
	private static final Map<String, List<Piece>> SOURCES = new HashMap<>();
	private static final List<Gib> GIBS = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static final float LIFE = 10, SPREAD = 2.5f, DT = 0.05f;

	record Piece(Identifier texture, Vector3f pos, Quaternionf rot, Vector3f size, float[] v) {
	}

	static final class Gib {
		final Piece piece;
		Vec3 pos, prev, vel;
		final Quaternionf rot, prevRot = new Quaternionf();
		final Vector3f spin;
		float age;

		Gib(Piece piece, Vec3 pos, Quaternionf rot, Vec3 vel, Vector3f spin) {
			this.piece = piece;
			this.pos = this.prev = pos;
			this.rot = rot;
			this.prevRot.set(rot);
			this.vel = vel;
			this.spin = spin;
		}
	}

	private static List<Piece> source(int piece, int grade) {
		String key = BuildingDefs.PIECES[piece] + "_" + BuildingDefs.GRADES[grade];
		return SOURCES.computeIfAbsent(key, k -> {
			List<Piece> out = new ArrayList<>();
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(RustAk.id("models/build/gibs/" + k + ".bin"))) {
				ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				int n = b.getInt();
				for (int i = 0; i < n; i++) {
					byte[] name = new byte[b.getInt()];
					b.get(name);
					// Unity is left-handed: mirror Z on positions, rotations and vertices
					Vector3f pos = new Vector3f(b.getFloat(), b.getFloat(), -b.getFloat());
					float qx = b.getFloat(), qy = b.getFloat(), qz = b.getFloat(), qw = b.getFloat();
					Quaternionf rot = new Quaternionf(-qx, -qy, qz, qw).normalize();
					Vector3f size = new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
					float[] v = new float[b.getInt() * 8];
					b.asFloatBuffer().get(v);
					b.position(b.position() + v.length * 4);
					for (int k2 = 0; k2 < v.length; k2 += 8) {
						v[k2 + 2] = -v[k2 + 2];
						v[k2 + 7] = -v[k2 + 7];
					}
					out.add(new Piece(RustAk.id("textures/build/" + new String(name, StandardCharsets.UTF_8) + ".png"), pos, rot, size, v));
				}
			} catch (Exception e) {
				// no gibs for this grade (Rust's twig wall has none): the block just vanishes with its effect
			}
			return out;
		});
	}

	public static void spawn(BuildingEntity b) {
		Quaternionf yaw = new Quaternionf().rotateY(b.yawRad());
		String fx = "gib_" + Building.GIB[b.grade()];
		Vec3 origin = b.position();
		List<Piece> pieces = source(b.piece(), b.grade());
		for (Piece p : pieces) {
			Vector3f lp = yaw.transform(new Vector3f(p.pos()));
			Vec3 at = origin.add(lp.x, lp.y, lp.z);
			Vector3f out = new Vector3f(p.pos()).normalize(SPREAD);
			if (!out.isFinite()) out.set(0, SPREAD, 0);
			yaw.transform(out);
			Vec3 vel = new Vec3(out.x, out.y + 1.5 * RANDOM.nextFloat(), out.z);
			Vector3f spin = new Vector3f(RANDOM.nextFloat() - 0.5f, RANDOM.nextFloat() - 0.5f, RANDOM.nextFloat() - 0.5f).normalize(3f);
			GIBS.add(new Gib(p, at, new Quaternionf(yaw).mul(p.rot()), vel, spin));
		}
		// the gib effect's systems have no emission of their own: Rust emits them per piece
		int perPiece = Math.max(1, 12 / Math.max(1, pieces.size()));
		if (pieces.isEmpty()) FxManager.burst(fx, b.obb().center(), new Vec3(0, 1, 0), 6, b.obb());
		for (int i = 0; i < pieces.size(); i += Math.max(1, pieces.size() / 12)) {
			Gib g = GIBS.get(GIBS.size() - pieces.size() + i);
			FxManager.burst(fx, g.pos, new Vec3(0, 1, 0), perPiece, null);
		}
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			GIBS.clear();
			return;
		}
		if (mc.isPaused()) return;
		for (Iterator<Gib> it = GIBS.iterator(); it.hasNext(); ) {
			Gib g = it.next();
			g.prev = g.pos;
			g.prevRot.set(g.rot);
			if ((g.age += DT) > LIFE) {
				it.remove();
				continue;
			}
			g.vel = g.vel.add(0, -9.81 * DT, 0);
			Vec3 move = g.vel.scale(DT);
			float h = Mth.clamp((g.piece.size().x + g.piece.size().y + g.piece.size().z) / 6, 0.04f, 0.4f);
			AABB box = new AABB(g.pos.x - h, g.pos.y - h, g.pos.z - h, g.pos.x + h, g.pos.y + h, g.pos.z + h);
			Vec3 done = Entity.collideBoundingBox(null, move, box, mc.level, List.of());
			double vx = g.vel.x, vy = g.vel.y, vz = g.vel.z;
			boolean hit = false;
			if (done.y != move.y) {
				vy = -vy * 0.25;
				vx *= 0.6;
				vz *= 0.6;
				hit = true;
			}
			if (done.x != move.x) vx = -vx * 0.3;
			if (done.z != move.z) vz = -vz * 0.3;
			g.vel = new Vec3(vx, vy, vz);
			if (hit) g.spin.mul(0.6f);
			g.pos = g.pos.add(done);
			float angle = g.spin.length() * DT;
			if (angle > 1e-4f) g.rot.premul(new Quaternionf().rotateAxis(angle, new Vector3f(g.spin).normalize()));
		}
	}

	public static void render(PoseStack pose, OrderedSubmitNodeCollector queue, float pt) {
		if (GIBS.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		for (Gib g : GIBS) {
			Vec3 p = g.prev.lerp(g.pos, pt);
			float fade = Math.min(1, (LIFE - g.age) / 1.5f); // shrink away over the last moments
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p));
			pose.pushPose();
			pose.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
			pose.mulPose(new Quaternionf(g.prevRot).slerp(g.rot, pt));
			pose.scale(fade, fade, fade);
			float[] v = g.piece.v();
			var type = MeshOut.solid(g.piece.texture());
			boolean q = MeshOut.quads(type);
			queue.submitCustomGeometry(pose, type, (ps, vc) -> MeshOut.flat(vc, ps, v, -1, light, q));
			pose.popPose();
		}
	}
}
