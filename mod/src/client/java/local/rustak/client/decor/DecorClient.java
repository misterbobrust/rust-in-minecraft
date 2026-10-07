package local.rustak.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import local.rustak.RustAk;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import local.rustak.decor.Decor;
import local.rustak.decor.DecorEntity;
import local.rustak.decor.DecorHooks;
import local.rustak.decor.DecorPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Setting Minecraft blocks down in Rust buildings (Decor): with a block in hand and a building block or decor block
 * in reach, a see-through copy of it follows the aim, blue where it fits and red where it doesn't, and use puts
 * it there. On a floor it turns to face the player; on a wall it backs
 * onto the wall (torches, signs and the like take their wall form); under a ceiling it hangs. On another decor block
 * a full block snaps alongside it, like Minecraft's building.
 */
public final class DecorClient {
	public record Placement(BlockState state, Vec3 pos, float yaw, int attach, boolean valid) {
	}

	private static final int GHOST_OK = 0x8C55A0FF, GHOST_BAD = 0x99FF4A40;
	/** Set while a decor renderer extracts its anchor's block entity, which is hidden otherwise. */
	public static boolean drawingDecor;
	private static Placement current;
	private static DecorEntity aimedDecor;
	private static long lastUse;

	private DecorClient() {
	}

	public static void init() {
		EntityRendererRegistry.register(RustAk.DECOR, DecorRenderer::new);
		DecorHooks.onAnchor = pos -> Minecraft.getInstance().execute(() -> {
			if (Minecraft.getInstance().levelRenderer != null) {
				Minecraft.getInstance().levelRenderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
			}
		});
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
			Minecraft mc = Minecraft.getInstance();
			current = mc.player == null ? null : compute(mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			if (current != null) ghost(ctx.matrices(), ctx.commandQueue(), current, current.valid() ? GHOST_OK : GHOST_BAD);
		});
	}

	/**
	 * Right click: sets the held block down when a placement is up, unless the player is aiming at a decor block
	 * that does something on click (a chest, a furnace, a crafting table) without sneaking. True if it did.
	 */
	public static boolean onUse() {
		Minecraft mc = Minecraft.getInstance();
		Placement pl = current;
		if (pl == null || mc.player == null) return false;
		if (aimedDecor != null && !mc.player.isShiftKeyDown() && interactive(aimedDecor)) return false;
		long now = mc.level.getGameTime();
		if (now - lastUse < 4) return true;
		lastUse = now;
		if (pl.valid()) {
			ClientPlayNetworking.send(new DecorPayload(pl.pos(), pl.yaw(), pl.attach()));
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
		return true;
	}

	private static boolean interactive(DecorEntity d) {
		BlockState s = d.state();
		return s.hasBlockEntity() || s.getMenuProvider(d.level(), d.anchor()) != null;
	}

	/** Where the held block would go, or null when it isn't a block or no building is aimed at before the terrain. */
	static Placement compute(LocalPlayer p, float partialTick) {
		aimedDecor = null;
		ItemStack stack = p.getMainHandItem();
		if (Decor.stateFor(stack, Decor.FLOOR) == null && Decor.stateFor(stack, Decor.WALL) == null) return null;
		Vec3 eye = p.getEyePosition(partialTick), dir = p.getViewVector(partialTick);
		double reach = p.blockInteractionRange();
		Vec3 end = eye.add(dir.scale(reach));

		// the nearest building or decor box along the aim
		double best = Double.MAX_VALUE;
		Obb hitBox = null;
		DecorEntity hitDecor = null;
		AABB area = new AABB(eye, end).inflate(1);
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), area)) for (Obb o : b.solids()) {
			double t = o.raycast(eye, dir, reach);
			if (t >= 0 && t < best) {
				best = t;
				hitBox = o;
				hitDecor = null;
			}
		}
		for (DecorEntity d : p.level().getEntitiesOfClass(DecorEntity.class, area, d -> !d.isRemoved())) {
			Obb o = d.visual();
			double t = o.raycast(eye, dir, reach);
			if (t >= 0 && t < best) {
				best = t;
				hitBox = o;
				hitDecor = d;
			}
		}
		if (hitBox == null) return null;
		HitResult terrain = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		if (terrain.getType() == HitResult.Type.BLOCK && terrain.getLocation().distanceTo(eye) < best) return null;
		aimedDecor = hitDecor;

		Vec3 at = eye.add(dir.scale(best));
		Vec3 n = hitBox.normalAt(at);
		int attach = n.y > 0.7 ? Decor.FLOOR : n.y < -0.7 ? Decor.CEILING : Decor.WALL;
		BlockState state = Decor.stateFor(stack, attach);
		if (state == null) return null;
		Vec3 pos;
		float yaw;
		if (hitDecor != null && full(state) && full(hitDecor.state())) {
			// block on block: alongside it on the face aimed at, in line with it
			pos = hitDecor.position().add(n);
			yaw = hitDecor.yawRad();
		} else if (attach == Decor.WALL) {
			yaw = (float) Math.atan2(-n.x, -n.z); // front (local north) out of the wall
			pos = at.add(n.scale(0.5)).subtract(0, 0.5, 0);
		} else {
			// facing the player
			Vec3 toPlayer = p.getEyePosition(partialTick).subtract(at);
			yaw = (float) Math.atan2(-toPlayer.x, -toPlayer.z);
			pos = attach == Decor.FLOOR ? at : at.subtract(0, 1, 0);
		}
		Obb box = Decor.visualBox(state, pos, yaw);
		boolean valid = !Decor.blocked(p.level(), box) && Decor.findAnchor(p.level(), box.center()) != null;
		return new Placement(state, pos, yaw, attach, valid);
	}

	private static boolean full(BlockState s) {
		AABB b = s.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds();
		return !s.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
			&& b.minX <= 0.001 && b.minY <= 0.001 && b.minZ <= 0.001 && b.maxX >= 0.999 && b.maxY >= 0.999 && b.maxZ >= 0.999;
	}

	/** The see-through copy: the block's model tinted blue or red; a tinted box for blocks drawn only by their renderer. */
	/** The hammer's highlight on an aimed decor block: its see-through copy drawn over it. */
	public static void highlight(PoseStack pose, OrderedSubmitNodeCollector queue, DecorEntity d, int tint) {
		// a hair larger than the block, so the two don't fight over the same depth
		Vec3 at = d.visual().center().subtract(Minecraft.getInstance().gameRenderer.getMainCamera().position());
		pose.pushPose();
		pose.translate(at.x, at.y, at.z);
		pose.scale(1.01f, 1.01f, 1.01f);
		pose.translate(-at.x, -at.y, -at.z);
		ghost(pose, queue, new Placement(d.state(), d.position(), d.yawRad(), d.attach(), true), tint);
		pose.popPose();
	}

	private static void ghost(PoseStack pose, OrderedSubmitNodeCollector queue, Placement pl, int tint) {
		Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(pl.state());
		pose.pushPose();
		pose.translate(pl.pos().x - cam.x, pl.pos().y - cam.y, pl.pos().z - cam.z);
		pose.mulPose(Axis.YP.rotation(pl.yaw()));
		pose.translate(-0.5f, 0, -0.5f);
		var type = RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
		if (pl.state().getRenderShape() == RenderShape.MODEL) {
			queue.submitCustomGeometry(pose, type, (ps, vc) ->
				ModelBlockRenderer.renderModel(ps, new Tinted(vc, tint), model, 1, 1, 1, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY));
		} else {
			AABB b = pl.state().getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() ? new AABB(0, 0, 0, 1, 1, 1)
				: pl.state().getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds();
			TextureAtlasSprite sprite = model.particleIcon();
			queue.submitCustomGeometry(pose, type, (ps, vc) -> box(ps, vc, b, sprite, tint));
		}
		pose.popPose();
	}

	private static void box(PoseStack.Pose ps, VertexConsumer vc, AABB b, TextureAtlasSprite s, int color) {
		float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ, x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
		float[][] faces = {
			{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0}, {x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0},
			{x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0, 0, 0, -1}, {x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1, 0, 0, 1},
			{x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, -1, 0, 0}, {x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0, 1, 0, 0}};
		float[][] uv = {{s.getU0(), s.getV0()}, {s.getU0(), s.getV1()}, {s.getU1(), s.getV1()}, {s.getU1(), s.getV0()}};
		for (float[] f : faces) for (int k = 0; k < 4; k++) {
			vc.addVertex(ps, f[k * 3], f[k * 3 + 1], f[k * 3 + 2]).setColor(color).setUv(uv[k][0], uv[k][1]).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightTexture.FULL_BRIGHT).setNormal(ps, f[12], f[13], f[14]);
		}
	}

	/** Multiplies every vertex colour by an ARGB tint. */
	private record Tinted(VertexConsumer vc, int tint) implements VertexConsumer {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			vc.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			vc.setColor(r * (tint >> 16 & 255) / 255, g * (tint >> 8 & 255) / 255, b * (tint & 255) / 255, a * (tint >>> 24) / 255);
			return this;
		}

		@Override
		public VertexConsumer setColor(int argb) {
			return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24);
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			vc.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			vc.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			vc.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			vc.setNormal(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float w) {
			vc.setLineWidth(w);
			return this;
		}
	}

	/** Particles a hidden anchor block gives off (a torch's flame) go where its decor block is drawn. */
	public static Vec3 particleAt(DecorEntity d, BlockPos anchor, double x, double y, double z) {
		return d.fromCell(x - anchor.getX(), y - anchor.getY(), z - anchor.getZ());
	}
}
