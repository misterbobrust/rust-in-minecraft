package local.rustak.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import local.rustak.ExplosionFxPayload;
import local.rustak.RocketEntity;
import local.rustak.RustAk;
import local.rustak.client.building.BuildClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Client entry: the Rust weapons, which one is in hand, recoil, and the rocket's renderer and effects. */
public class RustAkClient implements ClientModInitializer {
	static final List<Gun> GUNS = new java.util.ArrayList<>(List.of(new AkGun(),
		new HitscanGun(RustAk.GUNS.get(RustAk.SAR), new String[] {"attack-1", "attack-2"}, "attack-1_ads", "dryfire", "dryfire", 80, 0.5f),
		new HitscanGun(RustAk.GUNS.get(RustAk.SAP), new String[] {"fire-1", "fire-2", "fire-3"}, "fire_ads", "dryfire", "dryfire", 70, 0.5f),
		new RocketGun(), new PlannerTool(), new HammerTool()));
	static {
		// doors and the code lock are held as the building plan
		for (var door : RustAk.DOOR_ITEMS) GUNS.add(new PlaceableTool(door));
		GUNS.add(new PlaceableTool(RustAk.CODE_LOCK));
	}
	public static KeyMapping reloadKey;
	private static Gun selected;

	// recoil still to apply, spread over its duration on render frames
	static float recoilYaw, recoilPitch, recoilLeft;

	@Override
	public void onInitializeClient() {
		reloadKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.rustak.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KeyMapping.Category.GAMEPLAY));
		ClientTickEvents.END_CLIENT_TICK.register(RustAkClient::tick);
		RustPlayers.register();
		RustAk.PARTICLES.forEach((name, type) -> ParticleFactoryRegistry.getInstance().register(type, sprites -> {
			FxManager.SPRITES.put(name, sprites);
			return (options, level, x, y, z, dx, dy, dz, random) -> null; // spawned directly by FxManager
		}));
		EntityRendererRegistry.register(RustAk.ROCKET, RocketRenderer::new);
		RocketEntity.clientTick = FxManager::rocketTick;
		BuildClient.init();
		local.rustak.client.decor.DecorClient.init();
		ClientPlayNetworking.registerGlobalReceiver(ExplosionFxPayload.TYPE,
			(payload, ctx) -> FxManager.play("rocket_explosion", payload.pos(), payload.normal()));
	}

	/** The Rust weapon for this stack, or null. */
	public static Gun gunFor(ItemStack stack) {
		for (Gun g : GUNS) if (stack.is(g.item)) return g;
		return null;
	}

	/** The Rust weapon in the local player's main hand, or null. */
	public static Gun held(LocalPlayer p) {
		return p == null ? null : gunFor(p.getMainHandItem());
	}

	/** Minecraft's own count of left clicks while a Rust weapon is held. */
	public static void attackClicked(LocalPlayer p) {
		Gun gun = held(p);
		if (gun != null) gun.click();
	}

	public static boolean holding(LocalPlayer p) {
		return held(p) != null;
	}

	private static void tick(Minecraft mc) {
		RustMovementKeys.apply(mc.options);
		FxManager.tick(mc);
		LocalPlayer p = mc.player;
		if (p != null) CrouchJump.tick(mc, p);
		Gun gun = held(p);
		if (gun != selected) {
			if (selected != null) selected.onDeselect();
			selected = gun;
			if (gun != null) gun.onSelect();
		}
		while (reloadKey.consumeClick()) if (gun != null) gun.startReload();
		if (gun != null) gun.tick(mc, p);
	}

	static void addRecoil(float yaw, float pitchUp, float timeToTake) {
		recoilYaw += yaw;
		recoilPitch += pitchUp;
		recoilLeft = timeToTake;
	}

	/** Called every render frame: feeds pending recoil into the camera smoothly. */
	static void applyRecoil(LocalPlayer p, float dt) {
		if (recoilLeft <= 0) return;
		float k = Math.min(1, dt / recoilLeft);
		float y = recoilYaw * k, x = recoilPitch * k;
		p.setYRot(p.getYRot() + y);
		p.setXRot(Math.max(-90, p.getXRot() - x));
		recoilYaw -= y;
		recoilPitch -= x;
		recoilLeft -= dt;
		if (recoilLeft <= 0) recoilYaw = recoilPitch = 0;
	}
}
