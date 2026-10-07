package local.rustak.client;

import java.util.Set;

import local.rustak.RustAk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;

/** One Rust weapon on the client: its viewmodel, magazine, reload/deploy timing and trigger logic. */
public abstract class Gun {
	public final Item item;
	final String rig;
	final int magazine;
	final float reloadTime, deployTime, zoom, adsInSpeed, adsOutSpeed;

	public float zoom() {
		return zoom;
	}

	/** The viewmodel's projection FOV; vanilla draws hands at 70. */
	float handFov() {
		return 70;
	}

	public float handFovDegrees() {
		return handFov();
	}

	/** A left click as Minecraft counts it (its attack is cancelled for Rust weapons). */
	void click() {
	}
	final boolean punch;

	public int ammo;
	public boolean aiming;
	boolean held;
	long reloadStart = -1;
	private Viewmodel viewmodel;

	Gun(Item item, String rig, int magazine, float reloadTime, float deployTime, float zoom, float adsInSpeed, float adsOutSpeed, boolean punch) {
		this.item = item;
		this.rig = rig;
		this.magazine = this.ammo = magazine;
		this.reloadTime = reloadTime;
		this.deployTime = deployTime;
		this.zoom = zoom;
		this.adsInSpeed = adsInSpeed;
		this.adsOutSpeed = adsOutSpeed;
		this.punch = punch;
	}

	public Viewmodel viewmodel() {
		if (viewmodel == null) viewmodel = new Viewmodel(this);
		return viewmodel;
	}

	public Viewmodel loadedViewmodel() {
		return viewmodel;
	}

	boolean reloading() {
		return reloadStart >= 0;
	}

	boolean deploying() {
		Viewmodel vm = viewmodel();
		return vm.isPlaying(deployClip()) && vm.actionTime() < deployTime;
	}

	abstract String deployClip();

	abstract String reloadClip();

	/** ADS weight for progress 0..1 along the current transition (in = entering). Rust's curves here are flat-ended. */
	float adsEase(boolean in, float progress) {
		return in ? Viewmodel.smooth(progress) : 1 - Viewmodel.smooth(progress);
	}

	/** Viewmodel placement: view (Unity camera space) = offset + scale * rig point. Defaults to the prefab's. */
	float[] viewOffset(ViewmodelRig rig) {
		return rig.R;
	}

	float[] viewScale(ViewmodelRig rig) {
		return rig.D;
	}

	/** Extra depth shift toward the camera at full ADS, on top of the sideways aim-point shift. */
	float adsDepth() {
		return 0;
	}

	/** Parts the viewmodel should hide right now (e.g. an empty launcher's rocket). */
	Set<String> hiddenParts() {
		return Set.of();
	}

	void onSelect() {
		viewmodel().play(deployClip(), 0.05f);
	}

	void onDeselect() {
		aiming = false;
		reloadStart = -1;
	}

	void startReload() {
		if (ammo < magazine && !reloading() && !deploying()) {
			reloadStart = System.nanoTime();
			viewmodel().play(reloadClip(), 0.15f);
		}
	}

	/** Every client tick while held. */
	void tick(Minecraft mc, LocalPlayer p) {
		if (reloading() && (System.nanoTime() - reloadStart) / 1e9f >= reloadTime) {
			ammo = magazine;
			reloadStart = -1;
		}
		aiming = mc.options.keyUse.isDown() && !reloading() && mc.screen == null && !p.isSprinting();
	}

	/** Every render frame while held, before the viewmodel updates. */
	abstract void frame(LocalPlayer p, float dt);

	/** Viewmodel clip events: "sound:<name>" from Rust clips, or the weapon's own effect names. */
	void onClipEvent(String name) {
		if (name.startsWith("sound:")) play(RustAk.SOUNDS.get(name.substring(6)));
	}

	static void play(SoundEvent s) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && s != null) p.playSound(s, 0.8f, 0.95f + p.getRandom().nextFloat() * 0.1f);
	}

	boolean trigger(Minecraft mc) {
		return mc.options.keyAttack.isDown() && mc.screen == null && !reloading() && !deploying();
	}
}
