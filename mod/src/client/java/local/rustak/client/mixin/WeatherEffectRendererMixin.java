package local.rustak.client.mixin;

import local.rustak.client.building.WorldSolids;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rain and snow stop on Rust building blocks like on Minecraft's: the top of each column (its heightmap) is raised to
 * the roof over it. The splashes land on the roof's surface, and under a roof the rain is heard from above.
 */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
	/** The roof top found by the last heightmap lookup in tickRainParticles, for its splash; NaN for none. */
	@Unique
	private double rustak$splashY = Double.NaN;

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
	private int rustak$roofColumn(Level level, Heightmap.Types type, int x, int z) {
		return Math.max(level.getHeight(type, x, z), WorldSolids.roofBlock(level, x, z));
	}

	@Redirect(method = "tickRainParticles", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"))
	private BlockPos rustak$roofPos(ClientLevel level, Heightmap.Types type, BlockPos pos) {
		BlockPos ground = level.getHeightmapPos(type, pos);
		double roof = WorldSolids.roof(level, pos.getX(), pos.getZ());
		rustak$splashY = Double.NaN;
		if (roof <= ground.getY()) return ground;
		rustak$splashY = roof;
		// one above the block the roof top is in: the splash goes there less one, and is lifted onto the roof below
		return new BlockPos(pos.getX(), (int) Math.floor(roof) + 1, pos.getZ());
	}

	@ModifyArg(method = "tickRainParticles", index = 2, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private double rustak$splashOnRoof(double y) {
		return Double.isNaN(rustak$splashY) ? y : rustak$splashY;
	}
}
