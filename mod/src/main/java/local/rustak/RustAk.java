package local.rustak;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;
import local.rustak.building.Building;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingDefs;
import local.rustak.building.BuildingEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class RustAk implements ModInitializer {
	public static final String ID = "rustak";
	public static final ResourceKey<Item> AK_KEY = ResourceKey.create(Registries.ITEM, id("ak47"));
	public static final Item AK = Registry.register(BuiltInRegistries.ITEM, AK_KEY, new Item(new Item.Properties().setId(AK_KEY).stacksTo(1)));

	/** Every sound by name; Rust clip events refer to sounds by these names. */
	public static final Map<String, SoundEvent> SOUNDS = new HashMap<>();
	public static final SoundEvent SHOT = sound("shot");
	public static final SoundEvent DEPLOY = sound("deploy");
	public static final SoundEvent DRYFIRE = sound("dryfire");
	public static final SoundEvent RELOAD_START = sound("reload_start");
	public static final SoundEvent GRAB_MAG = sound("grab_magazine");
	public static final SoundEvent INSERT_MAG = sound("insert_magazine");
	public static final SoundEvent BOLT_BACK = sound("bolt_back");
	public static final SoundEvent BOLT_FORWARD = sound("bolt_forward");

	// rocket launcher: items, the rocket entity, and its sounds under their Rust names
	public static final ResourceKey<Item> LAUNCHER_KEY = ResourceKey.create(Registries.ITEM, id("rocket_launcher"));
	public static final Item ROCKET_LAUNCHER = Registry.register(BuiltInRegistries.ITEM, LAUNCHER_KEY,
		new Item(new Item.Properties().setId(LAUNCHER_KEY).stacksTo(1)));
	public static final ResourceKey<EntityType<?>> ROCKET_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("rocket"));
	public static final EntityType<RocketEntity> ROCKET = Registry.register(BuiltInRegistries.ENTITY_TYPE, ROCKET_KEY,
		EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(16)
			.updateInterval(1).noSave().build(ROCKET_KEY));
	public static final SoundEvent ROCKET_ENGINE = sound("rocket_engine");
	public static final SoundEvent ROCKET_EXPLOSION = sound("rocket_explosion");
	public static final SoundEvent ROCKET_ATTACK = sound("rocketlauncher_attack");
	static {
		for (String n : new String[] {"rocket-launcher-deploy", "rocket-launcher-reload-start", "rocket-launcher-reload-open-hatch",
			"rocket-launcher-reload-insert-rocket", "rocket-launcher-reload-close-hatch", "rocket-launcher-reload-finish"}) sound(n);
	}
	// building: Rust's building plan and hammer, the building block entity, and its sounds
	public static final ResourceKey<Item> PLANNER_KEY = ResourceKey.create(Registries.ITEM, id("building_plan"));
	public static final Item PLANNER = Registry.register(BuiltInRegistries.ITEM, PLANNER_KEY, new Item(new Item.Properties().setId(PLANNER_KEY).stacksTo(1)));
	public static final ResourceKey<Item> HAMMER_KEY = ResourceKey.create(Registries.ITEM, id("hammer"));
	public static final Item HAMMER = Registry.register(BuiltInRegistries.ITEM, HAMMER_KEY, new Item(new Item.Properties().setId(HAMMER_KEY).stacksTo(1)));
	// doors: the items in DoorDefs.DOORS order, and the door entity
	public static final Item[] DOOR_ITEMS = {doorItem("door_wood"), doorItem("door_metal"), doorItem("door_double_wood"), doorItem("door_double_metal")};
	public static final ResourceKey<EntityType<?>> DOOR_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("door"));
	public static final EntityType<local.rustak.building.DoorEntity> DOOR = Registry.register(BuiltInRegistries.ENTITY_TYPE, DOOR_KEY,
		EntityType.Builder.<local.rustak.building.DoorEntity>of(local.rustak.building.DoorEntity::new, MobCategory.MISC).sized(1f, 2f)
			.clientTrackingRange(10).updateInterval(40).build(DOOR_KEY));

	private static Item doorItem(String name) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
	}

	public static final ResourceKey<EntityType<?>> DECOR_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("decor_block"));
	public static final EntityType<local.rustak.decor.DecorEntity> DECOR = Registry.register(BuiltInRegistries.ENTITY_TYPE, DECOR_KEY,
		EntityType.Builder.<local.rustak.decor.DecorEntity>of(local.rustak.decor.DecorEntity::new, MobCategory.MISC).sized(1f, 1f)
			.clientTrackingRange(10).updateInterval(40).build(DECOR_KEY));
	public static final ResourceKey<EntityType<?>> BUILDING_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("building_block"));
	public static final EntityType<BuildingEntity> BUILDING = Registry.register(BuiltInRegistries.ENTITY_TYPE, BUILDING_KEY,
		EntityType.Builder.<BuildingEntity>of(BuildingEntity::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(12)
			.updateInterval(40).build(BUILDING_KEY));
	static {
		try (var in = RustAk.class.getResourceAsStream("/assets/rustak/building/sounds.json")) {
			for (var e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) sound(e.getAsString());
		} catch (Exception ex) {
			throw new RuntimeException("rustak: can't read building/sounds.json", ex);
		}
	}

	/** One particle type per Rust flipbook (the installer writes the list). */
	public static final Map<String, SimpleParticleType> PARTICLES = new HashMap<>();
	static final int REPEAT_TICKS = 40; // 2 s between shots
	private static final Map<UUID, Long> lastRocket = new HashMap<>();

	// Rust guns: damage against Rust's 100 hp scaled to Minecraft's 20 (the AK's 50 is 8 here)
	static final float DAMAGE_SCALE = 0.16f;
	public static final ResourceKey<Item> SAR_KEY = ResourceKey.create(Registries.ITEM, id("semi_auto_rifle"));
	public static final Item SAR = Registry.register(BuiltInRegistries.ITEM, SAR_KEY, new Item(new Item.Properties().setId(SAR_KEY).stacksTo(1)));
	public static final ResourceKey<Item> SAP_KEY = ResourceKey.create(Registries.ITEM, id("semi_auto_pistol"));
	public static final Item SAP = Registry.register(BuiltInRegistries.ITEM, SAP_KEY, new Item(new Item.Properties().setId(SAP_KEY).stacksTo(1)));
	/** Hitscan guns by item, with their Rust stats. */
	public static final Map<Item, WeaponStats> GUNS = Map.of(AK, new WeaponStats("ak47"), SAR, new WeaponStats("sar"), SAP, new WeaponStats("sap"));
	private static final Map<UUID, Long> lastShot = new HashMap<>();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(ID, path);
	}

	private static SoundEvent sound(String name) {
		Identifier id = id(name);
		SoundEvent e = Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
		SOUNDS.put(name, e);
		return e;
	}

	private static void registerParticles() {
		try (var in = RustAk.class.getResourceAsStream("/assets/rustak/fx/sprites.json")) {
			for (var e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
				String name = e.getAsString();
				PARTICLES.put(name, Registry.register(BuiltInRegistries.PARTICLE_TYPE, id(name), FabricParticleTypes.simple(true)));
			}
		} catch (Exception ex) {
			throw new RuntimeException("rustak: can't read fx/sprites.json", ex);
		}
	}

	@Override
	public void onInitialize() {
		registerParticles();
		RustSteps.register();
		PayloadTypeRegistry.playC2S().register(ShootPayload.TYPE, ShootPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(FireRocketPayload.TYPE, FireRocketPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ExplosionFxPayload.TYPE, ExplosionFxPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FxPayload.TYPE, FxPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(BuildPayload.TYPE, BuildPayload.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK.register(local.rustak.building.Stability::tick);
		PayloadTypeRegistry.playC2S().register(local.rustak.decor.DecorPayload.TYPE, local.rustak.decor.DecorPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(local.rustak.decor.DecorPayload.TYPE, (payload, ctx) -> local.rustak.decor.Decor.place(ctx.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(BuildPayload.TYPE, (payload, ctx) -> Building.handle(ctx.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ShootPayload.TYPE, (payload, ctx) -> shoot(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(FireRocketPayload.TYPE, (payload, ctx) -> fireRocket(ctx.player(), payload.dir()));
		// the mod's own creative tab, with the AK's icon
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("items"), FabricItemGroup.builder()
			.title(Component.translatable("itemGroup.rustak.items"))
			.icon(() -> new ItemStack(AK))
			.displayItems((params, entries) -> {
				entries.accept(AK);
				entries.accept(SAR);
				entries.accept(SAP);
				entries.accept(ROCKET_LAUNCHER);
				entries.accept(PLANNER);
				entries.accept(HAMMER);
				for (Item door : DOOR_ITEMS) entries.accept(door);
			})
			.build());
	}

	private static void fireRocket(ServerPlayer player, Vec3 dir) {
		if (!player.getMainHandItem().is(ROCKET_LAUNCHER) || dir.lengthSqr() < 1e-6) return;
		ServerLevel level = player.level();
		long now = level.getGameTime();
		Long last = lastRocket.get(player.getUUID());
		if (last != null && now - last < REPEAT_TICKS - 2) return;
		lastRocket.put(player.getUUID(), now);
		dir = dir.normalize();
		RocketEntity.launch(level, player, player.getEyePosition().add(dir.scale(0.5)), dir);
		level.playSound(player, player.getX(), player.getEyeY(), player.getZ(), ROCKET_ATTACK, SoundSource.PLAYERS, 4f, 1f);
	}

	private static void shoot(ServerPlayer player) {
		WeaponStats gun = GUNS.get(player.getMainHandItem().getItem());
		if (gun == null) return;
		ServerLevel level = player.level();
		long now = level.getGameTime();
		Long last = lastShot.get(player.getUUID());
		// the AK's 450 rpm is 2.67 ticks and packets can bunch up; semi-automatics fire 6-7 times a second at most
		if (last != null && now - last < 1) return;
		lastShot.put(player.getUUID(), now);
		float damage = gun.rustDamage() * DAMAGE_SCALE;

		// everyone else hears the shot and sees the muzzle flash; the shooter plays both locally
		SoundEvent shot = gun.name.equals("ak47") ? SHOT : SOUNDS.get(gun.name + "_attack");
		level.playSound(player, player.getX(), player.getEyeY(), player.getZ(), shot, SoundSource.PLAYERS, 4f, 0.95f + level.random.nextFloat() * 0.1f);
		Vec3 look = player.getViewVector(1);
		FxPayload.sendExcept(level, player, "attack_" + gun.name, player.getEyePosition().add(look.scale(0.9)).add(0, -0.15, 0), look);
		HitResult hit = ProjectileUtil.getHitResultOnViewVector(player, e -> e.isPickable() && !e.isSpectator(), 200);
		Vec3 at = hit.getLocation();
		BuildingCollision.Hit bh0 = BuildingCollision.raycast(level, player.getEyePosition(), player.getEyePosition().add(player.getViewVector(1).scale(200)));
		BuildingCollision.DoorHit dh = BuildingCollision.raycastDoors(level, player.getEyePosition(), player.getEyePosition().add(player.getViewVector(1).scale(200)));
		if (dh != null && dh.distance() < player.getEyePosition().distanceTo(at) && (bh0 == null || dh.distance() < bh0.distance())) {
			dh.door().damage(Map.of(BuildingDefs.BULLET, gun.rustDamage()));
			Vec3 p = dh.location();
			level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.02, 0.02, 0.02, 0.01);
			return;
		}
		if (bh0 != null && bh0.distance() < player.getEyePosition().distanceTo(at)) {
			// the gun's Rust bullet damage through the grade's protection
			bh0.block().damage(Map.of(BuildingDefs.BULLET, gun.rustDamage()));
			Vec3 p = bh0.location();
			level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.02, 0.02, 0.02, 0.01);
			return;
		}
		if (hit instanceof EntityHitResult eh) {
			Entity target = eh.getEntity();
			if (at.y > target.getEyeY() - 0.25) damage *= 2;
			if (target instanceof LivingEntity living) living.invulnerableTime = 0;
			target.hurtServer(level, level.damageSources().playerAttack(player), damage);
			level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, at.x, at.y, at.z, 3, 0.1, 0.1, 0.1, 0.1);
		} else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(bh.getBlockPos())), at.x, at.y, at.z, 8, 0.05, 0.05, 0.05, 0.1);
			level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 2, 0.02, 0.02, 0.02, 0.01);
			BlockDamage.bullet(level, player, bh.getBlockPos(), gun.rustDamage());
		}
	}
}
