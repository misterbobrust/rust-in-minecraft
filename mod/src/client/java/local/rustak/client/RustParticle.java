package local.rustak.client;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** One Shuriken particle: Unity units (metres, seconds) stepped at 20 Hz, flipbook frames, colour/size over life. */
final class RustParticle extends SingleQuadParticle {
	private static final int FULL_BRIGHT = LightTexture.pack(15, 15);

	private final FxDef.Sys sys;
	private final List<TextureAtlasSprite> frames;
	private final FxManager.Emitter owner;
	private final boolean follow;
	private final int index;
	private final float life, baseSize, startFrame, rndSize, rndColor, rndFrame, rndRot, rndVel;
	private final float[] baseColor, tmp = new float[4];
	private double vx, vy, vz; // m/s
	private float age, oSize;
	private Vec3 lastOwnerPos;
	private boolean counted = true;

	RustParticle(ClientLevel level, Vec3 at, Vec3 velocity, float life, float size, float rotation, float[] color,
			FxDef.Sys sys, List<TextureAtlasSprite> frames, FxManager.Emitter owner, int index, RandomSource random) {
		super(level, at.x, at.y, at.z, frames.get(0));
		this.sys = sys;
		this.frames = frames;
		this.owner = owner == null ? null : owner;
		this.follow = owner != null && !sys.worldSpace;
		this.index = index;
		this.life = life;
		this.baseSize = size;
		this.baseColor = color;
		this.vx = velocity.x;
		this.vy = velocity.y;
		this.vz = velocity.z;
		this.rndSize = random.nextFloat();
		this.rndColor = random.nextFloat();
		this.rndFrame = random.nextFloat();
		this.rndRot = random.nextFloat();
		this.rndVel = random.nextFloat();
		this.startFrame = sys.randomFrame ? random.nextFloat() : sys.startFrame == null ? 0 : sys.startFrame.eval(0, random.nextFloat());
		this.roll = this.oRoll = rotation;
		this.hasPhysics = false;
		this.friction = 1;
		this.gravity = 0;
		this.lifetime = Mth.ceil(life * 20 / Math.max(0.01f, sys.simSpeed)) + 1;
		this.lastOwnerPos = owner == null ? null : owner.pos;
		apply(0);
		this.oSize = this.quadSize;
	}

	@Override
	public void tick() {
		xo = x;
		yo = y;
		zo = z;
		oRoll = roll;
		oSize = quadSize;
		float dt = FxManager.DT * sys.simSpeed;
		age += dt;
		if (age >= life) {
			remove();
			return;
		}
		float t = age / life;
		vy -= gravity * 9.81f * dt;
		if (sys.force != null) {
			vx += sys.force[0].eval(t, rndVel) * dt;
			vy += sys.force[1].eval(t, rndVel) * dt;
			vz += sys.force[2].eval(t, rndVel) * dt;
		}
		if (sys.drag != null) {
			double k = Math.max(0, 1 - sys.drag.eval(t, rndVel) * dt);
			vx *= k;
			vy *= k;
			vz *= k;
		}
		if (sys.limit != null) {
			double speed = Math.sqrt(vx * vx + vy * vy + vz * vz), max = sys.limit.eval(t, rndVel);
			if (speed > max && speed > 1e-6) {
				double target = Mth.lerp(1 - Math.pow(1 - sys.dampen, dt * 60), speed, max);
				vx *= target / speed;
				vy *= target / speed;
				vz *= target / speed;
			}
		}
		double ex = 0, ey = 0, ez = 0;
		if (sys.velocity != null && owner != null) { // velocity over lifetime moves position, it doesn't accumulate
			Vec3 v = owner.dirToWorld(sys.velocity[0].eval(t, rndVel), sys.velocity[1].eval(t, rndVel), sys.velocity[2].eval(t, rndVel));
			ex = v.x;
			ey = v.y;
			ez = v.z;
		}
		double nx = x + (vx + ex) * dt, ny = y + (vy + ey) * dt, nz = z + (vz + ez) * dt;
		if (follow) { // local simulation space: ride along with the emitter
			nx += owner.pos.x - lastOwnerPos.x;
			ny += owner.pos.y - lastOwnerPos.y;
			nz += owner.pos.z - lastOwnerPos.z;
			lastOwnerPos = owner.pos;
		}
		setPos(nx, ny, nz);
		if (sys.rotationOverLife != null) roll += sys.rotationOverLife.eval(t, rndRot) * dt;
		apply(t);
	}

	private void apply(float t) {
		float sizeMul = sys.sizeOverLife == null ? 1 : sys.sizeOverLife.eval(t, rndSize);
		quadSize = baseSize * sizeMul / 2; // Unity size is the full width
		float r = baseColor[0], g = baseColor[1], b = baseColor[2], a = baseColor[3];
		if (sys.colorOverLife != null) {
			sys.colorOverLife.eval(t, rndColor, tmp);
			r *= tmp[0];
			g *= tmp[1];
			b *= tmp[2];
			a *= tmp[3];
		}
		rCol = r;
		gCol = g;
		bCol = b;
		alpha = Mth.clamp(a, 0, 1);
		int n = frames.size();
		float f = startFrame;
		if (sys.frameOverTime != null && !sys.randomFrame) f += (sys.frameOverTime.eval(t, rndFrame) * sys.cycles) % 1f;
		setSprite(frames.get(Math.floorMod((int) (f * n), n)));
	}

	/** Gravity multiplier (times 9.81 m/s^2). */
	void setGravity(float modifier) {
		gravity = modifier;
	}

	@Override
	public void remove() {
		if (counted && owner != null) {
			owner.particleDied(index);
			counted = false;
		}
		super.remove();
	}

	@Override
	public float getQuadSize(float partialTick) {
		return Mth.lerp(partialTick, oSize, quadSize);
	}

	@Override
	protected int getLightColor(float partialTick) {
		if (sys.emissive) return FULL_BRIGHT;
		int world = super.getLightColor(partialTick);
		if (!sys.emissionFade) return world;
		// fire baked in from the emission map glows early in the flipbook and fades as it turns to smoke
		int glow = (int) (15 * Mth.clamp(1 - age / life * 1.6f, 0, 1));
		return LightTexture.pack(Math.max(LightTexture.block(world), glow), LightTexture.sky(world));
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}
}
