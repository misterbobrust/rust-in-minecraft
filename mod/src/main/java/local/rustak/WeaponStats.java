package local.rustak;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A Rust gun's stats and recoil values (weapons/<name>.json, from the installer). */
public final class WeaponStats {
	public final String name;
	public final float repeatDelay, reloadTime, deployDelay, aimCone, hipAimCone, damageScale, bulletDamage, zoom;
	public final int magazine;
	public final boolean automatic, punch;
	public final float yawMin, yawMax, pitchMin, pitchMax, timeToTakeMin, timeToTakeMax, adsScale, movementPenalty;

	public WeaponStats(String name) {
		this.name = name;
		JsonObject o;
		try (var in = WeaponStats.class.getResourceAsStream("/assets/rustak/weapons/" + name + ".json")) {
			o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw new RuntimeException("rustak: can't read weapons/" + name + ".json", e);
		}
		repeatDelay = o.get("repeatDelay").getAsFloat();
		reloadTime = o.get("reloadTime").getAsFloat();
		deployDelay = o.get("deployDelay").getAsFloat();
		aimCone = o.get("aimCone").getAsFloat();
		hipAimCone = o.get("hipAimCone").getAsFloat();
		damageScale = o.get("damageScale").getAsFloat();
		bulletDamage = o.get("bulletDamage").getAsFloat();
		magazine = o.get("magazine").getAsInt();
		automatic = o.get("automatic").getAsBoolean();
		JsonObject iron = o.getAsJsonObject("ironsights");
		zoom = iron.has("zoomFactor") ? iron.get("zoomFactor").getAsFloat() : 1;
		punch = o.getAsJsonObject("punch").size() > 0;
		JsonObject r = o.getAsJsonObject("recoil");
		yawMin = r.get("recoilYawMin").getAsFloat();
		yawMax = r.get("recoilYawMax").getAsFloat();
		pitchMin = r.get("recoilPitchMin").getAsFloat();
		pitchMax = r.get("recoilPitchMax").getAsFloat();
		timeToTakeMin = r.get("timeToTakeMin").getAsFloat();
		timeToTakeMax = r.get("timeToTakeMax").getAsFloat();
		adsScale = r.get("ADSScale").getAsFloat();
		movementPenalty = r.get("movementPenalty").getAsFloat();
	}

	/** Rust damage per bullet (ammo Bullet 50 times the gun's damageScale). */
	public float rustDamage() {
		return bulletDamage * damageScale;
	}
}
