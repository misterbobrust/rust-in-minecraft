package local.rustak.client.building;

import local.rustak.CodeLockPayload;
import local.rustak.RustAk;
import local.rustak.building.DoorEntity;
import local.rustak.client.RustAkClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Rust's code entry dialog over the blurred game: the code's name (master or guest) on top, the display, a keypad laid
 * out like a phone's upside down (7 8 9 first, a wide 0 and C for clear at the bottom) and, once a code has been used,
 * a wide LAST key that types it (Reload does the same). Digits also come from the number keys, Backspace takes one
 * back, Escape cancels; the fourth digit sends the code and closes it. Each key beeps like the lock. Laid out on a
 * 1280x720 canvas matched on height.
 */
public final class KeyCodeScreen extends Screen {
	private static final float PITCH = 49, GAP = 2, KEY = PITCH - GAP, WIDTH = 3 * PITCH - GAP, DISPLAY_H = 40, LAST_H = 44;
	private static final float TITLE_Y = -157, DISPLAY_Y = -139, KEYS_Y = -86, LAST_Y = KEYS_Y + 4 * PITCH + 2;
	private static final int TITLE = argb(0.87f, 0.85f, 0.82f, 0.95f), DISPLAY = argb(0.2f, 0.2f, 0.2f, 0.45f);
	private static final int KEY_BG = argb(0.8f, 0.8f, 0.78f, 0.3f), KEY_HOVER = argb(0.9f, 0.9f, 0.88f, 0.45f), KEY_DOWN = argb(0.55f, 0.55f, 0.53f, 0.45f);
	private static final int DIGIT = argb(0.18f, 0.18f, 0.18f, 0.7f), TYPED = argb(0.95f, 0.93f, 0.9f, 1f);
	private static final int TIP_BG = argb(0.11f, 0.42f, 0.68f, 0.95f), TIP_TEXT = argb(0.92f, 0.96f, 1f, 1f);
	private static final FontDescription BOLD = new FontDescription.Resource(RustAk.id("rust_bold"));
	private static final FontDescription REGULAR = new FontDescription.Resource(RustAk.id("rust_regular"));
	/** The keys: label, column, row and width in columns (row 4 is LAST, under the pad). */
	private record Key(String label, int col, int row, int span) {
	}

	private static final Key[] KEYS = {
		new Key("7", 0, 0, 1), new Key("8", 1, 0, 1), new Key("9", 2, 0, 1),
		new Key("4", 0, 1, 1), new Key("5", 1, 1, 1), new Key("6", 2, 1, 1),
		new Key("1", 0, 2, 1), new Key("2", 1, 2, 1), new Key("3", 2, 2, 1),
		new Key("0", 0, 3, 2), new Key("C", 2, 3, 1), new Key("LAST", 0, 4, 3)};
	private static final int LAST = 11, CLEAR = 10;
	/** The last code used on this client, for LAST. */
	private static String memory = "";

	private final DoorEntity door;
	private final boolean guest, enter;
	private final StringBuilder code = new StringBuilder();
	private int pressedKey = -1;
	private long pressedAt;

	private KeyCodeScreen(DoorEntity door, boolean guest, boolean enter) {
		super(Component.translatable("item.rustak.code_lock"));
		this.door = door;
		this.guest = guest;
		this.enter = enter;
	}

	/** Opens the pad on a door's lock: setting its master or guest code, or entering a code to get past it. */
	public static void open(DoorEntity door, boolean guest, boolean enter) {
		Minecraft.getInstance().setScreen(new KeyCodeScreen(door, guest, enter));
	}

	private float unit() {
		return height / 720f;
	}

	/** Key i's box in canvas units around the screen's centre: x0, y0, x1, y1. */
	private static float[] box(int i) {
		Key k = KEYS[i];
		float x0 = -WIDTH / 2 + k.col() * PITCH, y0 = k.row() == 4 ? LAST_Y : KEYS_Y + k.row() * PITCH;
		return new float[] {x0, y0, x0 + k.span() * PITCH - GAP, y0 + (k.row() == 4 ? LAST_H : KEY)};
	}

	private static boolean shown(int i) {
		return i != LAST || !memory.isEmpty();
	}

	private int keyAt(double mouseX, double mouseY) {
		float u = unit();
		double x = (mouseX - width / 2.0) / u, y = (mouseY - height / 2.0) / u;
		for (int i = 0; i < KEYS.length; i++) {
			float[] b = box(i);
			if (shown(i) && x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) return i;
		}
		return -1;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		float u = unit();
		g.pose().pushMatrix();
		g.pose().translate(width / 2f, height / 2f);
		g.pose().scale(u, u);
		String type = I18n.get(enter ? "rustak.codelock.enter" : guest ? "rustak.codelock.guest" : "rustak.codelock.master").toUpperCase();
		text(g, type, 0, TITLE_Y, 26, BOLD, TITLE);
		fill(g, -WIDTH / 2, DISPLAY_Y, WIDTH / 2, DISPLAY_Y + DISPLAY_H, DISPLAY);
		if (!code.isEmpty()) text(g, code.toString(), 0, DISPLAY_Y + DISPLAY_H / 2, 30, BOLD, TYPED);
		int hovered = keyAt(mouseX, mouseY);
		boolean flash = pressedKey >= 0 && Util.getMillis() - pressedAt < 120;
		for (int i = 0; i < KEYS.length; i++) {
			if (!shown(i)) continue;
			float[] b = box(i);
			fill(g, b[0], b[1], b[2], b[3], flash && pressedKey == i ? KEY_DOWN : hovered == i ? KEY_HOVER : KEY_BG);
			float cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
			if (i == LAST) {
				// the history icon, then LAST
				float icon = 22, w = this.font.width(Component.literal("LAST").withStyle(st -> st.withFont(BOLD))) * 26f / this.font.lineHeight;
				float x = cx - (icon + 8 + w) / 2;
				blit(g, x, cy - icon / 2, icon, DIGIT);
				text(g, "LAST", x + icon + 8 + w / 2, cy, 26, BOLD, DIGIT);
			} else {
				text(g, KEYS[i].label(), cx, cy, 30, BOLD, DIGIT);
			}
		}
		if (hovered == LAST) {
			// Rust's tooltip: a blue strip with the hint, over the key's top edge
			String tip = I18n.get("rustak.codelock.last_tip");
			float th = 14, tw = this.font.width(Component.literal(tip).withStyle(st -> st.withFont(REGULAR))) * th / this.font.lineHeight + 14;
			float[] b = box(LAST);
			fill(g, -tw / 2, b[1] - 26, tw / 2, b[1] - 4, TIP_BG);
			text(g, tip, 0, b[1] - 15, th, REGULAR, TIP_TEXT);
		}
		g.pose().popMatrix();
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBlurredBackground(g); // the game stays in view behind the pad, blurred
	}

	private static void fill(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x0, y0);
		g.pose().scale((x1 - x0) / 64f, (y1 - y0) / 64f);
		g.fill(0, 0, 64, 64, color);
		g.pose().popMatrix();
	}

	private static void blit(GuiGraphics g, float x, float y, float size, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(size / 64f, size / 64f);
		g.blit(RenderPipelines.GUI_TEXTURED, RustAk.id("textures/gui/lock_last.png"), 0, 0, 0, 0, 64, 64, 1, 1, 1, 1, color);
		g.pose().popMatrix();
	}

	/** Centred text, height in canvas units. */
	private void text(GuiGraphics g, String s, float x, float y, float height, FontDescription font, int color) {
		Component c = Component.literal(s).withStyle(st -> st.withFont(font));
		float scale = height / this.font.lineHeight;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.drawString(this.font, c, -this.font.width(c) / 2, -this.font.lineHeight / 2, color, false);
		g.pose().popMatrix();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			int i = keyAt(event.x(), event.y());
			if (i >= 0) {
				press(i);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int c = event.codepoint();
		for (int i = 0; i < 10; i++) {
			if (KEYS[i < 9 ? i : 9].label().charAt(0) == c) {
				press(i < 9 ? i : 9);
				return true;
			}
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
			beep(-1);
			if (!code.isEmpty()) code.setLength(code.length() - 1);
			return true;
		}
		if (RustAkClient.reloadKey != null && RustAkClient.reloadKey.matches(event) && shown(LAST)) {
			press(LAST);
			return true;
		}
		return super.keyPressed(event);
	}

	/** The key's flash and the lock's beep. */
	private void beep(int i) {
		pressedKey = i;
		pressedAt = Util.getMillis();
		SoundEvent e = RustAk.SOUNDS.get("lock-code-unlock");
		if (e != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, 1f, 0.5f));
	}

	private void press(int i) {
		beep(i);
		if (i == CLEAR) code.setLength(0);
		else if (i == LAST) {
			code.setLength(0);
			code.append(memory);
		} else if (code.length() < 4) code.append(KEYS[i].label());
		if (code.length() == 4) submit();
	}

	/** Four digits: off to the server (a new code for the lock, or a try at it), and the pad closes. */
	private void submit() {
		String c = code.toString();
		memory = c;
		ClientPlayNetworking.send(new CodeLockPayload(enter ? CodeLockPayload.ENTER_CODE : CodeLockPayload.CHANGE_CODE, door.getId(), c, guest));
		onClose();
	}

	@Override
	public void tick() {
		// the door gone or left behind: the pad goes too
		Minecraft mc = Minecraft.getInstance();
		if (door.isRemoved() || mc.player == null || door.box().closest(mc.player.getEyePosition()).distanceTo(mc.player.getEyePosition()) > 4) onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static int argb(float r, float g, float b, float a) {
		return Mth.clamp(Math.round(a * 255), 0, 255) << 24 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
	}
}
