package local.rustak.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import local.rustak.RustSteps;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rust's third-person player (default male skin) in place of Minecraft's player model, driven by
 * Rust's unarmed locomotion: idle, 8-way walk and jog, sprint, crouch idle and 8-way crouch walk, and the jump's midair
 * loop.
 */
public final class RustPlayers {
	private static final String[] DIRS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
	// ground speed (m/s) each clip plays at rate 1, from its stride
	private static final float WALK = 1.4f, JOG = 2.4f, SPRINT = 4.2f, CROUCH = 1.3f;
	// seconds per locomotion cycle (two steps) at Minecraft's walk and sprint speeds
	private static final float WALK_CYCLE = 0.72f, SPRINT_CYCLE = 0.6f;
	private static ViewmodelRig rig;
	private static boolean failed;
	private static int pelvis, spine1, spine2, spine3, neck, head, lArm, rArm, lFoot = -1, rFoot = -1;
	// clip -> phases (0..1) where each foot is lowest: the footfalls the step sounds play on
	private static final Map<String, float[]> PLANTS = new HashMap<>();
	/** Rust's player stands about 1.75 m; Minecraft's model is 1.875. */
	private static final float SCALE = 1.1f;
	private static final Map<Integer, Anim> ANIMS = new HashMap<>();
	// players submitted inside the world's entity pass are GPU-skinned and drawn together right after it
	private static boolean inWorld;

	public static void register() {
		// in first person the own body isn't drawn, but its animation still times the footsteps
		WorldRenderEvents.END_MAIN.register(ctx -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer p = mc.player;
			if (p == null || !mc.options.getCameraType().isFirstPerson() || rig() == null) return;
			AvatarRenderState s = new AvatarRenderState();
			s.id = p.getId();
			s.bodyRot = p.yBodyRot;
			s.isCrouching = p.isCrouching();
			extract(p, s);
		});
		WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> inWorld = true);
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
			inWorld = false;
			GpuSkin.flush(RenderSystem.getModelViewMatrix());
		});
	}
	private static long lastPrune;

	private static ViewmodelRig rig() {
		if (rig == null && !failed) {
			try (var in = RustPlayers.class.getResourceAsStream("/assets/rustak/models/player_clips.json")) {
				rig = new ViewmodelRig("player");
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject b = o.getAsJsonObject("_bones");
				pelvis = b.get("pelvis").getAsInt();
				if (b.has("l_foot")) {
					lFoot = b.get("l_foot").getAsInt();
					rFoot = b.get("r_foot").getAsInt();
				}
				spine1 = b.get("spine1").getAsInt();
				spine2 = b.get("spine2").getAsInt();
				spine3 = b.get("spine3").getAsInt();
				neck = b.get("neck").getAsInt();
				head = b.get("head").getAsInt();
				lArm = b.get("l_upperarm").getAsInt();
				rArm = b.get("r_upperarm").getAsInt();
			} catch (Exception e) {
				failed = true;
				rig = null;
				org.slf4j.LoggerFactory.getLogger("rustak").error("rustak: player model", e);
			}
		}
		return rig;
	}

	/** Whether this player is drawn as Rust's: on foot, not gliding, swimming, riding, sleeping or spectating. */
	public static boolean active(AvatarRenderState s) {
		return !s.isFallFlying && s.swimAmount <= 0 && !s.isPassenger && !s.isSpectator && !s.hasPose(Pose.SLEEPING)
			&& !s.isAutoSpinAttack && ANIMS.containsKey(s.id) && rig() != null;
	}

	/** From AvatarRenderer.extractRenderState: advances that player's animation to this frame. */
	public static void extract(Avatar e, AvatarRenderState s) {
		if (rig() == null) return;
		long now = System.nanoTime();
		ANIMS.computeIfAbsent(e.getId(), id -> new Anim()).update(e, s, now);
		if (now - lastPrune > 5_000_000_000L) {
			lastPrune = now;
			for (Iterator<Anim> it = ANIMS.values().iterator(); it.hasNext(); ) if (now - it.next().last > 10_000_000_000L) it.remove();
		}
	}

	/** In place of the player model in LivingEntityRenderer.submit; pose is the model's (after its flip and offset). */
	public static void submit(AvatarRenderState s, PoseStack pose, SubmitNodeCollector collector) {
		Anim a = ANIMS.get(s.id);
		ViewmodelRig rig = rig();
		// undo the vanilla model's offset, 0.9375 scale and flip: back to the entity's rotated frame (forward -Z), then Unity -> Minecraft
		Matrix4f m = new Matrix4f(pose.last().pose()).translate(0, 1.501f, 0).scale(1 / 0.9375f).scale(-1, -1, 1);
		if (s.isCrouching) m.translate(0, 2 / 16f, 0); // the renderer's crouch offset; Rust's crouch lowers the body itself
		m.scale(SCALE, SCALE, -SCALE);
		int light = s.lightCoords, overlay = LivingEntityRenderer.getOverlayCoords(s, 0);
		// GPU skinning draws past the render types, which a shader pack wouldn't see: then the CPU path
		boolean gpu = inWorld && !MeshOut.shaders();
		for (int p = 0; p < rig.parts.length; p++) {
			ViewmodelRig.Part part = rig.parts[p];
			float[] v = a.verts[p];
			if (gpu) {
				GpuSkin.boneMatrices(part, a.world, a.skin[p], m, a.tmp);
			} else { // e.g. the inventory's preview: drawn by its own collector, outside the world pass
				ViewmodelRenderer.skinPart(part, a.world, a.skin[p], v, m, a.tmp);
				var type = MeshOut.solid(part.texture);
				boolean q = MeshOut.quads(type);
				collector.submitCustomGeometry(new PoseStack(), type, (ps, vc) -> MeshOut.skinned(vc, part, v, light, overlay, q));
			}
		}
		if (gpu) GpuSkin.add(rig, a.skin, Set.of(), light, overlay);
	}

	/** One player's animation state, with its own vertex buffers (geometry is drawn after all players are submitted). */
	private static final class Anim {
		final ViewmodelRig.Pose out = rig.newPose(), a = rig.newPose(), b = rig.newPose(), c = rig.newPose(), d = rig.newPose();
		final Matrix4f[] world = new Matrix4f[rig.boneCount];
		final float[][] skin = new float[rig.parts.length][], verts = new float[rig.parts.length][];
		final Matrix4f tmp = new Matrix4f();
		long last = System.nanoTime();
		float velX, velZ, phase, idleTime, airTime, fallSpeed;
		boolean fresh = true, wasOnGround = true;
		String ground = "concrete";

		Anim() {
			for (int i = 0; i < world.length; i++) world[i] = new Matrix4f();
			for (int p = 0; p < rig.parts.length; p++) {
				skin[p] = new float[rig.parts[p].bones.length * 16];
				verts[p] = new float[rig.parts[p].pos.length * 2];
			}
		}

		void update(Avatar e, AvatarRenderState s, long now) {
			float dt = Math.min(0.1f, (now - last) / 1e9f);
			last = now;
			float k = 1 - (float) Math.exp(-dt * 10);
			velX += ((float) (e.getX() - e.xo) * 20 - velX) * k;
			velZ += ((float) (e.getZ() - e.zo) * 20 - velZ) * k;
			float yaw = s.bodyRot * Mth.DEG_TO_RAD;
			float fwd = -Mth.sin(yaw) * velX + Mth.cos(yaw) * velZ, right = -Mth.cos(yaw) * velX - Mth.sin(yaw) * velZ;
			float speed = Mth.sqrt(fwd * fwd + right * right);
			airTime = e.onGround() || e.isInWater() ? 0 : airTime + dt;
			// jump and land: the jump sounds the ground it leaves (remembered while standing), the landing the ground hit
			boolean onGround = e.onGround();
			float velY = (float) (e.getY() - e.yo) * 20;
			if (!onGround) fallSpeed = Math.min(fallSpeed, velY);
			if (wasOnGround && !onGround && velY > 1 && !e.isInWater()) RustSteps.playLocal(e, ground, "jump", 0.6f);
			if (!wasOnGround && onGround) {
				ground = RustSteps.material(e);
				if (fallSpeed < -3) RustSteps.playLocal(e, ground, "land", Math.min(1, 0.5f - fallSpeed * 0.05f));
				fallSpeed = 0;
			}
			wasOnGround = onGround;
			idleTime += dt;

			ViewmodelRig.Pose target = a;
			if (airTime > 0.15f) {
				sampleLoop("3p_jump_midair", idleTime, a);
			} else if (speed < 0.4f) {
				sampleLoop(s.isCrouching ? "3p_crouch_idle" : "3p_idle_unarmed", idleTime, a);
			} else {
				// 8-way blend between the two clips around the direction of travel, all on one shared phase
				float angle = (float) Math.toDegrees(Math.atan2(right, fwd));
				float slot = ((angle % 360) + 360) % 360 / 45f;
				int i0 = (int) slot % 8, i1 = (i0 + 1) % 8;
				float t = slot - (int) slot;
				float natural, jog = 0, sprint = 0;
				if (s.isCrouching) {
					natural = CROUCH;
					dir("3p_crouch_", i0, i1, t, a, b);
				} else {
					jog = Mth.clamp((speed - 1.6f) / 1.6f, 0, 1);
					natural = Mth.lerp(jog, WALK, JOG);
					dir("3p_walk_", i0, i1, t, a, b);
					if (jog > 0) {
						dir("3p_jog_", i0, i1, t, c, b);
						ViewmodelRig.blend(a, c, jog, a);
					}
					sprint = e.isSprinting() && fwd > Math.abs(right) ? Mth.clamp((speed - 4.4f) / 0.8f, 0, 1) : 0;
					if (sprint > 0) {
						natural = Mth.lerp(sprint, natural, SPRINT);
						sampleAt("3p_sprint", phase, c);
						ViewmodelRig.blend(a, c, sprint, a);
					}
				}
				// Minecraft walks at Rust's jog pace and sprints past Rust's sprint: matching the stride exactly makes the
				// legs scurry, so the cadence is set directly (a cycle is two steps) and the feet slide some instead. It
				// grows with the square root of the speed: ~0.72 s per cycle at the 4.3 m/s walk, ~0.6 s at the 5.6 sprint.
				float cycle;
				if (s.isCrouching) {
					cycle = clipLength("3p_crouch_n") / Mth.clamp((float) Math.sqrt(speed / natural), 0.6f, 1.25f);
				} else {
					float walkCycle = WALK_CYCLE * (float) Math.sqrt(4.3f / speed), sprintCycle = SPRINT_CYCLE * (float) Math.sqrt(5.6f / speed);
					cycle = Mth.clamp(Mth.lerp(sprint, walkCycle, sprintCycle), 0.5f, 1.3f);
				}
				float before = phase;
				phase = (phase + dt / cycle) % 1f;
				String family = s.isCrouching ? "3p_crouch_n" : sprint > 0.5f ? "3p_sprint" : jog > 0.5f ? "3p_jog_n" : "3p_walk_n";
				if (onGround && !e.isInWater()) {
					for (float plant : plants(family)) {
						float moved = (phase - before + 1) % 1, to = (plant - before + 1) % 1;
						if (moved < 0.5f && to > 0 && to <= moved) {
							ground = RustSteps.material(e);
							if (!s.isCrouching) RustSteps.playLocal(e, ground, sprint > 0.5f ? "run" : "walk", 0.7f); // crouching is silent
						}
					}
				}
			}
			// crossfade toward this frame's target
			if (fresh) {
				ViewmodelRig.blend(target, target, 0, out);
				fresh = false;
			} else {
				ViewmodelRig.blend(out, target, 1 - (float) Math.exp(-dt * 12), out);
			}
			System.arraycopy(out.pos, 0, d.pos, 0, out.pos.length);
			System.arraycopy(out.rot, 0, d.rot, 0, out.rot.length);
			System.arraycopy(out.scale, 0, d.scale, 0, out.scale.length);
			look(s);
		}

		private void dir(String prefix, int i0, int i1, float t, ViewmodelRig.Pose into, ViewmodelRig.Pose scratch) {
			sampleAt(prefix + DIRS[i0], phase, into);
			if (t > 0.001f) {
				sampleAt(prefix + DIRS[i1], phase, scratch);
				ViewmodelRig.blend(into, scratch, t, into);
			}
		}

		/**
		 * Rust keeps the upper body on the aim: the locomotion clips turn and lean the whole body (the strafes and the
		 * crouch walk by 20-40 degrees) and its aim layer brings the chest back. Here the spine takes out most of the
		 * chest's turn and lean, then the spine, neck and head follow where the player looks.
		 */
		private void look(AvatarRenderState s) {
			rig.worldMatrices(d, world);
			Vector3f l = world[lArm].getTranslation(new Vector3f()), r = world[rArm].getTranslation(new Vector3f());
			Vector3f p = world[pelvis].getTranslation(new Vector3f()), n = world[neck].getTranslation(new Vector3f());
			// Unity model space: forward +Z, right +X; rotateY(+a) turns right, rotateX(+a) leans forward
			float chestYaw = (float) Math.atan2(-(r.z - l.z), r.x - l.x);
			float lean = (float) Math.atan2(n.z - p.z, n.y - p.y);
			float fixYaw = -chestYaw * 0.85f, fixLean = -(lean - 5 * Mth.DEG_TO_RAD) * 0.75f;
			float yaw = Mth.clamp(s.yRot, -80, 80) * Mth.DEG_TO_RAD, pitch = Mth.clamp(s.xRot, -80, 80) * Mth.DEG_TO_RAD;
			int[] bones = {spine1, spine2, spine3, neck, head};
			float[] fix = {1 / 3f, 1 / 3f, 1 / 3f, 0, 0}, aim = {0.1f, 0.1f, 0.1f, 0.35f, 0.35f};
			Quaternionf parentRot = new Quaternionf(), q = new Quaternionf(), rot = new Quaternionf();
			for (int i = 0; i < bones.length; i++) {
				if (i > 0) rig.worldMatrices(d, world);
				int bone = bones[i];
				world[rig.parent[bone]].getNormalizedRotation(parentRot);
				rot.identity().rotateY(fixYaw * fix[i] + yaw * aim[i]).rotateX(fixLean * fix[i] + pitch * aim[i]);
				// local' = parent^-1 * rot * parent * local
				q.set(d.rot[bone * 4], d.rot[bone * 4 + 1], d.rot[bone * 4 + 2], d.rot[bone * 4 + 3]);
				new Quaternionf(parentRot).conjugate().mul(rot).mul(parentRot).mul(q, q);
				d.rot[bone * 4] = q.x;
				d.rot[bone * 4 + 1] = q.y;
				d.rot[bone * 4 + 2] = q.z;
				d.rot[bone * 4 + 3] = q.w;
			}
			rig.worldMatrices(d, world);
		}
	}

	/** Where in the clip each foot is lowest (its footfall), found once by sampling the clip. */
	private static float[] plants(String name) {
		return PLANTS.computeIfAbsent(name, n -> {
			ViewmodelRig.Clip c = rig.clips.get(n);
			if (c == null || lFoot < 0) return new float[] {0, 0.5f};
			ViewmodelRig.Pose pose = rig.newPose();
			Matrix4f[] world = new Matrix4f[rig.boneCount];
			for (int i = 0; i < world.length; i++) world[i] = new Matrix4f();
			float[] best = {0, 0.5f}, low = {Float.MAX_VALUE, Float.MAX_VALUE};
			for (int i = 0; i < 64; i++) {
				float ph = i / 64f;
				rig.sample(c, ph * c.length(), pose);
				rig.worldMatrices(pose, world);
				int[] feet = {lFoot, rFoot};
				for (int f = 0; f < 2; f++) {
					float y = world[feet[f]].m31();
					if (y < low[f]) {
						low[f] = y;
						best[f] = ph;
					}
				}
			}
			return best;
		});
	}

	private static float clipLength(String name) {
		ViewmodelRig.Clip c = rig.clips.get(name);
		return c == null ? 1 : Math.max(0.1f, c.length());
	}

	private static void sampleAt(String name, float phase, ViewmodelRig.Pose out) {
		ViewmodelRig.Clip c = rig.clips.get(name);
		if (c != null) rig.sample(c, phase * c.length(), out);
	}

	private static void sampleLoop(String name, float time, ViewmodelRig.Pose out) {
		ViewmodelRig.Clip c = rig.clips.get(name);
		if (c != null) rig.sample(c, time % c.length(), out);
	}
}
