package local.rustak.client.building;

import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.rustak.BuildPayload;
import local.rustak.RustAk;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import local.rustak.building.DoorDefs;
import local.rustak.building.DoorEntity;
import local.rustak.building.Obb;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Doors on the client: setting a door from the hand into a doorway (a single door) or a wall frame (a double one)
 * by Rust's socket placement, with a see-through copy where it would go; and using doors with E like Rust: a tap
 * opens or closes the door aimed at, holding E a moment brings up its pie menu (open/close, knock), releasing E
 * picks the option under the cursor. While a door is aimed at, E doesn't open the inventory.
 */
public final class DoorClient {
	public record Placement(int kind, Vec3 pos, float yaw, boolean valid) {
	}

	private static final int GHOST_OK = 0x8C55A0FF, GHOST_BAD = 0x8CFF4A40;
	private static final float REACH = 4, USE_REACH = 3;
	private static final int HOLD_TICKS = 5;
	private static Placement current;
	private static long lastPlace;
	private static DoorEntity eTarget;
	private static long ePressed;
	private static boolean eWasDown, eMenu;

	private DoorClient() {
	}

	public static void init() {
		EntityRendererRegistry.register(RustAk.DOOR, DoorRenderer::new);
		ClientTickEvents.START_CLIENT_TICK.register(DoorClient::useKey);
		WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
			Minecraft mc = Minecraft.getInstance();
			current = mc.player == null ? null : compute(mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			if (current == null) return;
			Vec3 cam = mc.gameRenderer.getMainCamera().position();
			PoseStack pose = ctx.matrices();
			pose.pushPose();
			pose.translate(current.pos().x - cam.x, current.pos().y - cam.y, current.pos().z - cam.z);
			DoorDefs.Door d = DoorDefs.door(current.kind());
			float[] closed = new float[d.hinges.size()];
			for (int i = 0; i < closed.length; i++) closed[i] = d.turn(i, false, 100);
			draw(pose, ctx.commandQueue(), current.kind(), current.yaw(), closed, LightTexture.FULL_BRIGHT, current.valid() ? GHOST_OK : GHOST_BAD, true, null);
			pose.popPose();
		});
	}

	/**
	 * The door's frame (double doors have their own) and its leaves, each turned about its hinge; lit, when given,
	 * holds BuildLight's lighting for the frame (0) and each leaf (1 + hinge), null entries taking light.
	 */
	public static void draw(PoseStack pose, OrderedSubmitNodeCollector queue, int kind, float yaw, float[] turns, int light, int color, boolean ghost,
		BuildLight.Lit[] lit) {
		DoorDefs.Door d = DoorDefs.door(kind);
		if (d == null) return;
		String base = "models/doors/" + DoorDefs.DOORS[kind];
		pose.pushPose();
		pose.mulPose(Axis.YP.rotation(yaw));
		if (d.hasFrame) BuildMesh.at(base + "_frame.bin").submit(pose, queue, 0, light, color, ghost, lit == null ? null : lit[0]);
		for (int i = 0; i < d.hinges.size(); i++) {
			Vector3f p = d.hinges.get(i).pivot;
			pose.pushPose();
			pose.translate(p.x, p.y, p.z);
			pose.mulPose(Axis.YP.rotation(turns[i]));
			pose.translate(-p.x, -p.y, -p.z);
			BuildMesh.at(base + "_leaf" + i + ".bin").submit(pose, queue, 0, light, color, ghost, lit == null ? null : lit[1 + i]);
			pose.popPose();
		}
		pose.popPose();
	}

	private static int heldKind(LocalPlayer p) {
		for (int i = 0; i < RustAk.DOOR_ITEMS.length; i++) if (p.getMainHandItem().is(RustAk.DOOR_ITEMS[i])) return i;
		return -1;
	}

	/** Rust's door placement: the doorway (or wall frame) socket the aim passes through, the door's socket onto it. */
	static Placement compute(LocalPlayer p, float partialTick) {
		int kind = heldKind(p);
		DoorDefs.Door def = kind < 0 ? null : DoorDefs.door(kind);
		if (def == null) return null;
		Vec3 eye = p.getEyePosition(partialTick), dir = p.getViewVector(partialTick);
		Vector3f rayDir = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
		BuildingEntity target = null;
		BuildingDefs.Socket female = null;
		double best = Double.MAX_VALUE;
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(eye, eye).inflate(REACH + 4))) {
			Quaternionf bRot = new Quaternionf().rotateY(b.yawRad());
			for (BuildingDefs.Socket f : b.def().sockets) {
				if (!f.female || f.femaleDummy || f.type != def.socket.type) continue;
				Vector3f fPos = bRot.transform(new Vector3f(f.pos)).add((float) b.getX(), (float) b.getY(), (float) b.getZ());
				Quaternionf fRot = new Quaternionf(bRot).mul(f.rot);
				Vector3f centre = fRot.transform(new Vector3f(f.selectCenter)).add(fPos);
				if (BuildingPlacement.rayBox(eye, rayDir, centre, fRot, f.selectSize, REACH) < 0) continue;
				Vec3 c = new Vec3(centre.x, centre.y, centre.z);
				double miss = eye.add(dir.scale(Math.max(0, c.subtract(eye).dot(dir)))).distanceTo(c);
				if (miss < best) {
					best = miss;
					target = b;
					female = f;
				}
			}
		}
		if (target == null) return null;
		Quaternionf bRot = new Quaternionf().rotateY(target.yawRad());
		Vector3f fPos = bRot.transform(new Vector3f(female.pos)).add((float) target.getX(), (float) target.getY(), (float) target.getZ());
		Quaternionf fRot = new Quaternionf(bRot).mul(female.rot);
		BuildingPlacement.Placement pl = BuildingPlacement.doPlacement(def.socket, female, fPos, fRot, rayDir, 0);
		boolean taken = !p.level().getEntitiesOfClass(DoorEntity.class, new AABB(pl.pos(), pl.pos()).inflate(1), d -> d.position().distanceTo(pl.pos()) < 0.3).isEmpty();
		return new Placement(kind, pl.pos(), pl.yaw(), !taken);
	}

	/** Right click with a door in hand: puts it in. True when a door is held (the click is the door's either way). */
	public static boolean onUse() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || heldKind(mc.player) < 0) return false;
		Placement pl = current;
		long now = mc.level.getGameTime();
		if (pl != null && pl.valid() && now - lastPlace > 4) {
			lastPlace = now;
			ClientPlayNetworking.send(BuildPayload.placeDoor(pl.kind(), pl.pos(), pl.yaw()));
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
		return true;
	}

	/** The door the player looks at within reach, or null. */
	public static DoorEntity aimed(LocalPlayer p, double reach) {
		Vec3 eye = p.getEyePosition(), dir = p.getViewVector(1);
		DoorEntity hit = null;
		double best = reach;
		for (DoorEntity d : p.level().getEntitiesOfClass(DoorEntity.class, new AABB(eye, eye.add(dir.scale(reach))).inflate(2), d -> !d.isRemoved() && d.def() != null)) {
			for (Obb o : d.solids()) {
				double t = o.raycast(eye, dir, best);
				if (t >= 0 && t < best) {
					best = t;
					hit = d;
				}
			}
		}
		// a building block in front of it hides it
		BuildingCollision.Hit wall = BuildingCollision.raycast(p.level(), eye, eye.add(dir.scale(best)));
		return wall != null && wall.distance() < best - 0.05 ? null : hit;
	}

	/** E on a door: a tap toggles it, a hold opens its pie menu and releasing picks from it. */
	private static void useKey(Minecraft mc) {
		LocalPlayer p = mc.player;
		boolean down = p != null && mc.screen == null && mc.options.keyInventory.isDown();
		if (p != null && down && !eWasDown && eTarget == null && !RadialMenu.isOpen()) {
			DoorEntity d = aimed(p, USE_REACH);
			if (d != null) {
				eTarget = d;
				ePressed = mc.level.getGameTime();
				eMenu = false;
			}
		}
		if (eTarget != null) {
			while (mc.options.keyInventory.consumeClick()) {
				// E is the door's now, not the inventory's
			}
			DoorEntity d = eTarget;
			if (down && !eMenu && mc.level.getGameTime() - ePressed >= HOLD_TICKS) {
				eMenu = true;
				String name = RustAk.DOOR_ITEMS[d.kind()].getName().getString();
				RadialMenu.open(List.of(
					new RadialMenu.Option(RustAk.id(d.isOpen() ? "textures/gui/door_close.png" : "textures/gui/door_open.png"), I18n.get(d.isOpen() ? "rustak.door.close" : "rustak.door.open"), name, true),
					new RadialMenu.Option(RustAk.id("textures/gui/door_knock.png"), I18n.get("rustak.door.knock"), name, true)),
					i -> ClientPlayNetworking.send(BuildPayload.onBlock(i == 0 ? BuildPayload.DOOR_TOGGLE : BuildPayload.DOOR_KNOCK, d.getId(), 0)));
			}
			if (!down) {
				if (eMenu) RadialMenu.pickHovered();
				else ClientPlayNetworking.send(BuildPayload.onBlock(BuildPayload.DOOR_TOGGLE, d.getId(), 0));
				eTarget = null;
				eMenu = false;
			}
		}
		eWasDown = down;
	}

	/** Rust's crosshair: a small dot (vanilla's cross stays for third person and the debug screen's axes). */
	public static boolean crosshair(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.getCameraType() != CameraType.FIRST_PERSON || mc.getDebugOverlay().showDebugScreen()) return false;
		var held = local.rustak.client.RustAkClient.held(mc.player);
		if (held != null && !(held instanceof local.rustak.client.PlannerTool) && !(held instanceof local.rustak.client.HammerTool)) return true; // guns: none
		if (mc.options.hideGui || RadialMenu.isOpen()) return true;
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0x50000000);
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(0.5f, 0.5f);
		g.fill(-1, -1, 1, 1, 0xE6FFFFFF);
		g.pose().popMatrix();
		return true;
	}

	/** Rust's use prompt over the crosshair while a door is aimed at: its icon, then "[E] OPEN DOOR". */
	public static void prompt(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.screen != null || mc.options.hideGui || RadialMenu.isOpen() || eMenu) return;
		DoorEntity d = aimed(p, USE_REACH);
		if (d == null) return;
		boolean open = d.isOpen();
		String key = "[" + mc.options.keyInventory.getTranslatedKeyMessage().getString().toUpperCase() + "] ";
		String text = I18n.get(open ? "rustak.door.prompt.close" : "rustak.door.prompt.open");
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy - 12);
		g.pose().scale(0.85f, 0.85f);
		int w = mc.font.width(key) + mc.font.width(text), x = -w / 2;
		g.drawString(mc.font, key, x, -8, 0xFFFFB13B, true);
		g.drawString(mc.font, text, x + mc.font.width(key), -8, 0xFFFFFFFF, true);
		int icon = 16;
		g.blit(RenderPipelines.GUI_TEXTURED, RustAk.id(open ? "textures/gui/door_close.png" : "textures/gui/door_open.png"), -icon / 2, -10 - icon,
			0, 0, icon, icon, 1, 1, 1, 1, 0xFFFFFFFF);
		g.pose().popMatrix();
	}
}
