package local.rustak.client.mixin;

import java.util.List;
import local.rustak.client.building.WorldSolids;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Particles with physics land on and stop at Rust building blocks too, not only Minecraft's. */
@Mixin(Particle.class)
abstract class ParticleMixin {
	@Redirect(method = "move", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 rustak$buildingCollision(Entity entity, Vec3 move, AABB box, Level level, List<VoxelShape> shapes) {
		Vec3 v = Entity.collideBoundingBox(entity, move, box, level, shapes);
		if (!WorldSolids.blocked(level, box.move(v))) return v;
		// fall first, then slide; whichever axis runs into a block stops (Particle.move zeroes that speed)
		double y = WorldSolids.blocked(level, box.move(0, v.y, 0)) ? 0 : v.y;
		boolean side = WorldSolids.blocked(level, box.move(v.x, y, v.z));
		return new Vec3(side ? 0 : v.x, y, side ? 0 : v.z);
	}
}
