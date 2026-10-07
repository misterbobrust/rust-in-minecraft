package local.rustak.client.building;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import local.rustak.building.BuildingDefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * Pie menu texts. The installer writes Rust's own names and descriptions in English and Russian
 * (building/menu_text.json, from the game's localization), picked by Minecraft's language; without them the mod's
 * own lang entries (rustak.piece.*, rustak.grade.*) are used.
 */
public final class MenuText {
	private static JsonObject json;
	private static boolean loaded;

	private MenuText() {
	}

	public static String pieceName(int piece) {
		return get("pieces", BuildingDefs.PIECES[piece], 0, I18n.get("rustak.piece." + BuildingDefs.PIECES[piece]));
	}

	public static String pieceDescription(int piece) {
		return get("pieces", BuildingDefs.PIECES[piece], 1, I18n.get("rustak.piece." + BuildingDefs.PIECES[piece] + ".desc"));
	}

	public static String gradeName(int grade) {
		return get("grades", BuildingDefs.GRADES[grade], 0, I18n.get("rustak.grade." + BuildingDefs.GRADES[grade]));
	}

	public static String gradeDescription(int grade) {
		return get("grades", BuildingDefs.GRADES[grade], 1, "");
	}

	private static String get(String group, String key, int i, String fallback) {
		if (!loaded) {
			loaded = true;
			try (var in = MenuText.class.getResourceAsStream("/assets/rustak/building/menu_text.json")) {
				if (in != null) json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception ignored) {
				json = null;
			}
		}
		String lang = Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ru") ? "ru" : "en";
		JsonObject texts = json == null ? null : json.has(lang) ? json.getAsJsonObject(lang) : json.has("pieces") && lang.equals("ru") ? json : null;
		if (texts == null || !texts.has(group) || !texts.getAsJsonObject(group).has(key)) return fallback;
		String s = texts.getAsJsonObject(group).getAsJsonArray(key).get(i).getAsString();
		return s.isEmpty() ? fallback : s;
	}
}
