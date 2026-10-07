package local.rustak.client.building;

import java.util.List;
import java.util.function.IntConsumer;

import local.rustak.RustAk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;

/**
 * Rust's pie menu: held open with the right mouse button, the mouse steers a cursor
 * instead of the camera (MouseHandlerMixin), the left button picks the hovered option, releasing the right button
 * cancels. Layout, sizes and colours come from the prefab (a 1280x720 canvas matched on height); segment 0 is at
 * the top, then clockwise.
 */
public final class RadialMenu {
	public record Option(Identifier icon, String title, String description, boolean enabled) {
	}

	// layout values from Rust's pie menu
	private static final float OUTER = 0.973f, INNER = 0.637f, SEL_OUTER = 1f, SEL_INNER = 0.654f, ICON = 0.607f, PANEL = 500, CENTER = 230;
	private static final int RING = argb(0.969f, 0.922f, 0.882f, 0.9f), RING_DISABLED = argb(0.809f, 0.770f, 0.737f, 0.716f);
	private static final int SELECTION = argb(0.804f, 0.255f, 0.169f, 1f), DARKEN = argb(0.169f, 0.162f, 0.143f, 0.5f);
	private static final int ICON_NORMAL = argb(0.213f, 0.213f, 0.213f, 1f), ICON_HOVERED = argb(0.969f, 0.922f, 0.882f, 1f);
	private static final int ICON_DISABLED = argb(0.169f, 0.162f, 0.143f, 0.322f), ICON_ACTIVE = argb(0.804f, 0.255f, 0.169f, 0.784f);
	private static final int CENTER_DISC = argb(1f, 1f, 1f, 0.392f), TEXT = argb(0.969f, 0.922f, 0.882f, 1f), TEXT_DIM = argb(0.67f, 0.6f, 0.54f, 1f);
	private static final int DESCRIPTION = argb(0.969f, 0.922f, 0.882f, 0.8f);
	private static final FontDescription BOLD = new FontDescription.Resource(RustAk.id("rust_bold"));
	private static final FontDescription REGULAR = new FontDescription.Resource(RustAk.id("rust_regular"));

	private static List<Option> options = List.of();
	private static IntConsumer onPick;
	private static boolean open, lmbWasDown, swallowAttack;
	private static double cx, cy;
	private static int selected = -1;
	private static float shownAngle, openTime;
	private static long lastFrame;

	public static boolean isOpen() {
		return open;
	}

	/** True while the left click that picked an option is still held, so tools don't also act on it. */
	public static boolean swallowsAttack() {
		if (swallowAttack && !Minecraft.getInstance().options.keyAttack.isDown()) swallowAttack = false;
		return swallowAttack;
	}

	public static void open(List<Option> opts, IntConsumer pick) {
		options = opts;
		onPick = pick;
		open = true;
		cx = cy = 0;
		selected = -1;
		openTime = 0;
		lmbWasDown = Minecraft.getInstance().options.keyAttack.isDown();
		sound("piemenu_open");
	}

	/** Mouse movement while open (raw accumulated deltas). */
	public static void mouse(double dx, double dy) {
		cx += dx;
		cy += dy;
		double r = Math.sqrt(cx * cx + cy * cy);
		if (r > 200) {
			cx *= 200 / r;
			cy *= 200 / r;
		}
		int n = options.size();
		int sel = -1;
		if (r > 25 && n > 0) {
			double a = Math.atan2(cx, -cy); // 0 at the top, clockwise
			sel = Math.floorMod((int) Math.floor((a + Math.PI / n) / (2 * Math.PI / n)), n);
		}
		if (sel != selected) {
			if (selected < 0 && sel >= 0) shownAngle = sel * 360f / n;
			selected = sel;
			if (sel >= 0) sound("piemenu_selectionchange");
		}
	}

	/** For menus held on a key (a door's E): releasing it picks the hovered option, or closes without one. */
	public static void pickHovered() {
		if (!open) return;
		if (selected >= 0 && options.get(selected).enabled()) pick();
		else cancel();
	}

	/** Right button released: closes without picking (Rust picks with the left button). */
	public static void cancel() {
		if (!open) return;
		open = false;
		sound("piemenu_cancel");
	}

	private static void pick() {
		open = false;
		swallowAttack = true;
		sound("piemenu_select");
		onPick.accept(selected);
	}

	private static void sound(String name) {
		SoundEvent e = RustAk.SOUNDS.get(name);
		if (e != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, 1f, 0.6f));
	}

	public static void render(GuiGraphics g) {
		if (!open) return;
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		openTime += dt;
		// the left button picks the hovered option (checked every frame so quick clicks aren't lost)
		boolean lmb = mc.options.keyAttack.isDown();
		if (lmb && !lmbWasDown && selected >= 0 && options.get(selected).enabled()) {
			pick();
			return;
		}
		lmbWasDown = lmb;

		int n = options.size();
		float unit = g.guiHeight() / 720f * Math.min(1, 0.85f + openTime * 2.5f); // quick pop on open
		float x = g.guiWidth() / 2f, y = g.guiHeight() / 2f, R = PANEL / 2 * unit, seg = 360f / n;
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		pie(g, pose, x, y, 0, 0.55f * 300 * unit, 0, 360, DARKEN);
		for (int i = 0; i < n; i++) {
			int col = options.get(i).enabled() ? RING : RING_DISABLED;
			pie(g, pose, x, y, INNER * R, OUTER * R, i * seg - seg / 2, i * seg + seg / 2, col);
		}
		if (selected >= 0) { // the selection slides round to the hovered segment
			float target = selected * seg, diff = Mth.wrapDegrees(target - shownAngle);
			shownAngle += diff * Math.min(1, dt * 18);
			pie(g, pose, x, y, SEL_INNER * R, SEL_OUTER * R, shownAngle - seg / 2, shownAngle + seg / 2, SELECTION);
		}
		int icon = Math.round(ICON * (OUTER - INNER) * R);
		float mid = (OUTER + INNER) / 2 * R;
		for (int i = 0; i < n; i++) {
			double a = Math.toRadians(i * seg);
			Option o = options.get(i);
			int col = !o.enabled() ? ICON_DISABLED : i == selected ? ICON_HOVERED : ICON_NORMAL;
			blit(g, o.icon(), Math.round(x + (float) Math.sin(a) * mid) - icon / 2, Math.round(y - (float) Math.cos(a) * mid) - icon / 2, icon, col);
		}
		pie(g, pose, x, y, 0, CENTER / 2 * unit, 0, 360, CENTER_DISC);
		if (selected >= 0) { // inside the centre disc: icon, title, then the description wrapped to the disc's width
			Option o = options.get(selected);
			int big = Math.round(44 * unit);
			blit(g, o.icon(), Math.round(x) - big / 2, Math.round(y - 62 * unit) - big / 2, big, ICON_ACTIVE);
			text(g, o.title(), BOLD, x, y - 22 * unit, 19 * unit, o.enabled() ? TEXT : TEXT_DIM);
			wrapped(g, o.description(), REGULAR, x, y + 4 * unit, 12 * unit, 175 * unit, DESCRIPTION);
		}
	}

	private static void pie(GuiGraphics g, Matrix3x2f pose, float x, float y, float inner, float outer, float a0, float a1, int color) {
		g.guiRenderState.submitGuiElement(new PieShapeState(pose, x, y, inner, outer, a0, a1, color, null));
	}

	private static void text(GuiGraphics g, String s, FontDescription font, float x, float y, float height, int color) {
		Minecraft mc = Minecraft.getInstance();
		Component c = Component.literal(s).withStyle(st -> st.withFont(font));
		float scale = height / mc.font.lineHeight;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.drawString(mc.font, c, -mc.font.width(c) / 2, -mc.font.lineHeight / 2, color, false);
		g.pose().popMatrix();
	}

	/** Centred lines of text from y down, wrapped to width. */
	private static void wrapped(GuiGraphics g, String s, FontDescription font, float x, float y, float height, float width, int color) {
		Minecraft mc = Minecraft.getInstance();
		float scale = height / mc.font.lineHeight;
		var lines = mc.font.split(Component.literal(s).withStyle(st -> st.withFont(font)), Math.round(width / scale));
		for (int i = 0; i < lines.size() && i < 4; i++) {
			g.pose().pushMatrix();
			g.pose().translate(x, y + i * height * 1.2f);
			g.pose().scale(scale, scale);
			g.drawString(mc.font, lines.get(i), -mc.font.width(lines.get(i)) / 2, 0, color, false);
			g.pose().popMatrix();
		}
	}

	private static void blit(GuiGraphics g, Identifier tex, int x, int y, int size, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, size, size, 1, 1, 1, 1, color);
	}

	private static int argb(float r, float g, float b, float a) {
		return (Math.round(a * 255) << 24) | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}
}
