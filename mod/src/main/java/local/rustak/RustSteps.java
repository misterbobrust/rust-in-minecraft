package local.rustak;

import java.util.HashMap;
import java.util.Map;
import local.rustak.building.BuildingCollision;
import local.rustak.building.BuildingEntity;
import local.rustak.building.Obb;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rust's barefoot footsteps for players, in place of vanilla's step sounds. Like Rust, each client plays them from the
 * player animation (RustPlayers: footfalls, jump, land); this holds the sound events and the ground material: the
 * building block under the feet, else the Minecraft block's sound type.
 */
public final class RustSteps {
	private static final String[] MATERIALS = {"cloth", "concrete", "dirt", "forest", "grass", "gravel", "metal", "sand", "snow", "stones", "wood"};
	private static final String[] KINDS = {"walk", "run", "jump", "land"};
	private static final Map<String, SoundEvent> EVENTS = new HashMap<>();
	// building grades (twig, wood, stone, metal, toptier) -> material
	private static final String[] GRADE_MATERIAL = {"wood", "wood", "concrete", "metal", "metal"};

	private RustSteps() {
	}

	public static void register() {
		for (String m : MATERIALS) for (String k : KINDS) {
			Identifier id = RustAk.id("steps." + m + "." + k);
			EVENTS.put(m + "." + k, Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id)));
		}
	}

	/** Plays a footstep sound for this client only, at the entity's feet (each client times other players' steps). */
	public static void playLocal(Entity e, String material, String kind, float volume) {
		SoundEvent ev = EVENTS.get(material + "." + kind);
		if (ev != null) {
			e.level().playLocalSound(e.getX(), e.getY(), e.getZ(), ev, SoundSource.PLAYERS, volume, 0.95f + e.getRandom().nextFloat() * 0.1f, false);
		}
	}

	/** What the feet stand on: a building block whose top is at the feet, else the block below. */
	public static String material(Entity p) {
		Vec3 at = p.position();
		for (BuildingEntity b : BuildingCollision.blocksNear(p.level(), new AABB(at.x - 0.3, at.y - 0.25, at.z - 0.3, at.x + 0.3, at.y + 0.05, at.z + 0.3))) for (Obb o : b.solids()) {
			double top = o.center().y + o.ey();
			double[] c = o.toLocal(at.x - o.center().x, at.z - o.center().z);
			if (Math.abs(top - at.y) < 0.2 && Math.abs(c[0]) <= o.ex() + 0.3 && Math.abs(c[1]) <= o.ez() + 0.3) {
				return GRADE_MATERIAL[Math.min(b.grade(), GRADE_MATERIAL.length - 1)];
			}
		}
		// a thin layer at the feet (snow, carpet, moss carpet) wins over the block it lies on
		BlockState feet = p.level().getBlockState(BlockPos.containing(at));
		if (feet.is(Blocks.SNOW) || feet.is(BlockTags.WOOL_CARPETS) || feet.is(Blocks.MOSS_CARPET)) return material(feet);
		return material(p.getBlockStateOn());
	}

	private static String material(BlockState s) {
		if (s.is(Blocks.GRASS_BLOCK)) return "grass";
		if (s.is(BlockTags.LEAVES) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.PODZOL)) return "forest";
		if (s.is(BlockTags.SAND)) return "sand";
		if (s.is(BlockTags.SNOW)) return "snow";
		if (s.is(BlockTags.DIRT) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.CLAY)) return "dirt";
		if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS)) return "cloth";
		SoundType t = s.getSoundType();
		if (t == SoundType.GRASS || t == SoundType.CROP || t == SoundType.SWEET_BERRY_BUSH || t == SoundType.VINE || t == SoundType.WET_GRASS) return "grass";
		if (t == SoundType.AZALEA || t == SoundType.AZALEA_LEAVES || t == SoundType.FLOWERING_AZALEA || t == SoundType.LEAF_LITTER || t == SoundType.CHERRY_LEAVES) return "forest";
		if (t == SoundType.SAND || t == SoundType.SUSPICIOUS_SAND || t == SoundType.SOUL_SAND || t == SoundType.SOUL_SOIL) return "sand";
		if (t == SoundType.GRAVEL || t == SoundType.SUSPICIOUS_GRAVEL) return "gravel";
		if (t == SoundType.SNOW || t == SoundType.POWDER_SNOW) return "snow";
		if (t == SoundType.MUD || t == SoundType.ROOTED_DIRT || t == SoundType.MUDDY_MANGROVE_ROOTS) return "dirt";
		if (t == SoundType.WOOL) return "cloth";
		if (t == SoundType.WOOD || t == SoundType.NETHER_WOOD || t == SoundType.BAMBOO_WOOD || t == SoundType.CHERRY_WOOD || t == SoundType.LADDER
			|| t == SoundType.SCAFFOLDING || t == SoundType.BAMBOO || t == SoundType.STEM || t == SoundType.CHISELED_BOOKSHELF || t == SoundType.SHELF) return "wood";
		if (t == SoundType.METAL || t == SoundType.COPPER || t == SoundType.COPPER_BULB || t == SoundType.COPPER_GRATE || t == SoundType.CHAIN
			|| t == SoundType.ANVIL || t == SoundType.NETHERITE_BLOCK || t == SoundType.IRON || t == SoundType.LANTERN || t == SoundType.HEAVY_CORE) return "metal";
		return "concrete"; // stone and everything hard: the same as stone building blocks
	}
}
