package local.rustak.client.building;

import com.mojang.blaze3d.vertex.PoseStack;
import local.rustak.FxPayload;
import local.rustak.RustAk;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingEntity;
import local.rustak.client.FxManager;
import local.rustak.client.Gun;
import local.rustak.client.PlannerTool;
import local.rustak.client.RustAkClient;
import local.rustak.client.decor.DecorClient;
import local.rustak.decor.DecorEntity;
import local.rustak.building.DoorEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Client wiring for building: block renderer, the planner's ghost, the pie menu and hammer HUD, building effects. */
public final class BuildClient {
	private static final int GHOST_OK = 0x8C55A0FF, GHOST_BAD = 0x8CFF4A40, HIGHLIGHT = 0x4C55A0FF;

	public static void init() {
		EntityRendererRegistry.register(RustAk.BUILDING, BuildingRenderer::new);
		BuildLight.init();
		DoorClient.init();
		HudElementRegistry.addLast(RustAk.id("building_hud"), (g, tick) -> {
			hammerInfo(g);
			DoorClient.prompt(g);
			RadialMenu.render(g);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (g, tick) -> {
			if (!DoorClient.crosshair(g)) vanilla.render(g, tick);
		});
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
			Minecraft mc = Minecraft.getInstance();
			GibSystem.render(ctx.matrices(), ctx.commandQueue(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			hammerHighlight(ctx.matrices(), ctx.commandQueue());
			if (!(RustAkClient.held(mc.player) instanceof PlannerTool planner) || planner.placement == null) return;
			BuildingPlacementGhost.submit(ctx.matrices(), ctx.commandQueue(), planner);
		});
		ClientTickEvents.END_CLIENT_TICK.register(GibSystem::tick);
		ClientPlayNetworking.registerGlobalReceiver(FxPayload.TYPE, (p, ctx) -> {
			var level = Minecraft.getInstance().level;
			if (level != null && p.entity() >= 0 && level.getEntity(p.entity()) instanceof BuildingEntity b) {
				if (p.effect().equals("destroy")) GibSystem.spawn(b);
				else FxManager.playInBox(p.effect(), p.pos(), p.dir(), b.obb());
			} else if (!p.effect().equals("destroy")) { // a block's gibs need the block: gone or not loaded, nothing to show
				FxManager.play(p.effect(), p.pos(), p.dir());
			}
		});
	}

	/** What the hammer is aimed at: a building block or a decor block, whichever is nearer (null for neither). */
	public static Object hammerTarget(LocalPlayer p) {
		Gun gun = RustAkClient.held(p);
		if (gun == null || gun.item != RustAk.HAMMER || RadialMenu.isOpen()) return null;
		return aimTarget(p);
	}

	/** The building block, door or decor block aimed at within 4 m, whatever is in hand (null for none). */
	static Object aimTarget(LocalPlayer p) {
		if (p == null) return null;
		Vec3 eye = p.getEyePosition(), dir = p.getViewVector(1);
		BuildingCollision.Hit hit = BuildingCollision.raycast(p.level(), eye, eye.add(dir.scale(4)));
		double best = hit == null ? 4 : hit.distance();
		Object target = hit == null ? null : hit.block();
		DoorEntity door = DoorClient.aimed(p, best);
		if (door != null) {
			target = door;
			best = door.box().closest(eye).distanceTo(eye);
		}
		for (DecorEntity d : p.level().getEntitiesOfClass(DecorEntity.class, new AABB(eye, eye.add(dir.scale(4))).inflate(1), d -> !d.isRemoved())) {
			double t = d.visual().raycast(eye, dir, best);
			if (t >= 0 && t < best) {
				best = t;
				target = d;
			}
		}
		return target;
	}

	/** Like Rust, the block the hammer is aimed at lights up: a see-through blue copy drawn over it. */
	private static void hammerHighlight(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Object target = hammerTarget(mc.player);
		if (target instanceof DoorEntity door) {
			Vec3 cam = mc.gameRenderer.getMainCamera().position();
			pose.pushPose();
			pose.translate(door.getX() - cam.x, door.getY() - cam.y, door.getZ() - cam.z);
			DoorClient.draw(pose, queue, door.kind(), door.yawRad(), door.turns(1), LightTexture.FULL_BRIGHT, HIGHLIGHT, true, null, -1);
			pose.popPose();
		} else if (target instanceof DecorEntity d) {
			DecorClient.highlight(pose, queue, d, HIGHLIGHT);
		} else if (target instanceof BuildingEntity b) {
			Vec3 cam = mc.gameRenderer.getMainCamera().position();
			pose.pushPose();
			pose.translate(b.getX() - cam.x, b.getY() - cam.y, b.getZ() - cam.z);
			BuildMesh.get(b.piece(), b.grade(), local.rustak.building.RoofShape.mask(b)).submit(pose, queue, b.yawRad(), LightTexture.FULL_BRIGHT, HIGHLIGHT, true, null);
			pose.popPose();
		}
	}

	/** The hammer readout sits above the crosshair, a little below the middle of the screen's upper half. */
	private static final float HUD_SCALE = 0.7f;

	/**
	 * Rust's readout: a label on the left ("87% STABLE"), health on the right, a health bar under them; drawn at a
	 * fixed share of the GUI scale's size, so it stays modest at large interface scales.
	 */
	private static void readout(GuiGraphics g, String left, float health, float max) {
		Minecraft mc = Minecraft.getInstance();
		g.pose().pushMatrix();
		g.pose().translate(g.guiWidth() / 2f, HUD_Y(g));
		g.pose().scale(HUD_SCALE, HUD_SCALE);
		String hp = Math.round(health) + " / " + Math.round(max);
		int w = 150, x = -w / 2, y = 0;
		// a name too long for the room left of the numbers ends in an ellipsis
		int room = w - mc.font.width(hp) - 8;
		if (mc.font.width(left) > room) left = mc.font.plainSubstrByWidth(left, room - mc.font.width("...")).stripTrailing() + "...";
		g.drawString(mc.font, left, x, y, 0xFFFFFFFF, true);
		g.drawString(mc.font, hp, x + w - mc.font.width(hp), y, 0xFFFFFFFF, true);
		float f = Math.clamp(health / max, 0, 1);
		g.fill(x, y + 11, x + w, y + 14, 0x60000000);
		g.fill(x, y + 11, x + Math.round(w * f), y + 14, 0xEEFFFFFF);
		g.pose().popMatrix();
	}

	private static int HUD_Y(GuiGraphics g) {
		return Math.round(g.guiHeight() * 0.2f);
	}

	/** Rust shows the aimed block's name, grade and health while holding the hammer. */
	private static void hammerInfo(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		Object target = hammerTarget(p);
		if (target == null && p != null && !RadialMenu.isOpen() && mc.screen == null) {
			// without the hammer, like Rust: only something damaged shows its health
			Object aimed = aimTarget(p);
			if (aimed instanceof DoorEntity d && d.health() < d.def().health || aimed instanceof BuildingEntity b && b.health() < b.maxHealth()) target = aimed;
		}
		if (target instanceof DecorEntity d) {
			g.drawCenteredString(mc.font, d.state().getBlock().getName(), g.guiWidth() / 2, HUD_Y(g), 0xFFFFFFFF);
			return;
		}
		if (target instanceof DoorEntity door) {
			readout(g, RustAk.DOOR_ITEMS[door.kind()].getName().getString(), door.health(), door.def().health);
			return;
		}
		if (!(target instanceof BuildingEntity b)) return;
		readout(g, I18n.get("rustak.hud.stable", Math.round(b.stability() * 100)), b.health(), b.maxHealth());
	}

	static final class BuildingPlacementGhost {
		static void submit(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, PlannerTool planner) {
			Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
			BuildingPlacement.Placement pl = planner.placement;
			pose.pushPose();
			pose.translate(pl.pos().x - cam.x, pl.pos().y - cam.y, pl.pos().z - cam.z);
			long shape = local.rustak.building.RoofShape.mask(Minecraft.getInstance().level,
				local.rustak.building.BuildingDefs.piece(planner.piece), pl.pos(), pl.yaw(), 0, null);
			BuildMesh.get(planner.piece, 0, shape).submit(pose, queue, pl.yaw(), LightTexture.FULL_BRIGHT, pl.valid() ? GHOST_OK : GHOST_BAD, true);
			pose.popPose();
		}
	}
}
