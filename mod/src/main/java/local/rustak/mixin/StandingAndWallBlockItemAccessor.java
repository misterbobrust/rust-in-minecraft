package local.rustak.mixin;

import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The wall variant of torches, signs, heads and banners, for decor set on walls. */
@Mixin(StandingAndWallBlockItem.class)
public interface StandingAndWallBlockItemAccessor {
	@Accessor("wallBlock")
	Block rustak$wallBlock();
}
