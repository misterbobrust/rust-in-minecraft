package local.rustak.building;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A box rotated about the vertical axis only: Rust building blocks only ever yaw. world = centre + Ry(yaw) * local. */
public record Obb(Vec3 center, double ex, double ey, double ez, float yaw) {
	public double cos() {
		return Math.cos(yaw);
	}

	public double sin() {
		return Math.sin(yaw);
	}

	/** World offset (dx, dz) -> local (x, z). */
	public double[] toLocal(double dx, double dz) {
		double c = cos(), s = sin();
		return new double[] {dx * c - dz * s, dx * s + dz * c};
	}

	/** Local (x, z) -> world offset. */
	public double[] toWorld(double lx, double lz) {
		double c = cos(), s = sin();
		return new double[] {lx * c + lz * s, -lx * s + lz * c};
	}

	public AABB bounds() {
		double c = Math.abs(cos()), s = Math.abs(sin());
		double hx = c * ex + s * ez, hz = s * ex + c * ez;
		return new AABB(center.x - hx, center.y - ey, center.z - hz, center.x + hx, center.y + ey, center.z + hz);
	}

	/** Ray (from, unit dir, max length) -> distance to the first hit, or -1. Slab test in the box's frame. */
	public double raycast(Vec3 from, Vec3 dir, double max) {
		double[] o = toLocal(from.x - center.x, from.z - center.z);
		double[] d = toLocal(dir.x, dir.z);
		double[] ro = {o[0], from.y - center.y, o[1]}, rd = {d[0], dir.y, d[1]}, h = {ex, ey, ez};
		double tMin = 0, tMax = max;
		for (int i = 0; i < 3; i++) {
			if (Math.abs(rd[i]) < 1e-9) {
				if (Math.abs(ro[i]) > h[i]) return -1;
				continue;
			}
			double t1 = (-h[i] - ro[i]) / rd[i], t2 = (h[i] - ro[i]) / rd[i];
			tMin = Math.max(tMin, Math.min(t1, t2));
			tMax = Math.min(tMax, Math.max(t1, t2));
			if (tMin > tMax) return -1;
		}
		return tMin;
	}

	/** Whether p lies in the box grown by margin. */
	public boolean contains(Vec3 p, double margin) {
		double[] l = toLocal(p.x - center.x, p.z - center.z);
		return Math.abs(l[0]) <= ex + margin && Math.abs(p.y - center.y) <= ey + margin && Math.abs(l[1]) <= ez + margin;
	}

	/** Separating-axis test for two yaw-only boxes, each shrunk by margin so touching faces don't count. */
	public boolean overlaps(Obb b, double margin) {
		if (center.y + ey - margin <= b.center.y - b.ey || b.center.y + b.ey - margin <= center.y - ey) return false;
		double[][] axes = {{cos(), -sin()}, {sin(), cos()}, {b.cos(), -b.sin()}, {b.sin(), b.cos()}};
		for (double[] ax : axes) {
			double dc = Math.abs((b.center.x - center.x) * ax[0] + (b.center.z - center.z) * ax[1]);
			if (dc >= project(ax) + b.project(ax) - margin) return false;
		}
		return true;
	}

	private double project(double[] ax) {
		double[] x = toWorld(1, 0), z = toWorld(0, 1);
		return ex * Math.abs(x[0] * ax[0] + x[1] * ax[1]) + ez * Math.abs(z[0] * ax[0] + z[1] * ax[1]);
	}

	/** Closest point of the box to p (for Rust's distance-based explosion falloff). */
	public Vec3 closest(Vec3 p) {
		double[] l = toLocal(p.x - center.x, p.z - center.z);
		double lx = Mth.clamp(l[0], -ex, ex), lz = Mth.clamp(l[1], -ez, ez), ly = Mth.clamp(p.y - center.y, -ey, ey);
		double[] w = toWorld(lx, lz);
		return new Vec3(center.x + w[0], center.y + ly, center.z + w[1]);
	}

	/** Outward face normal nearest to a point on the box surface. */
	public Vec3 normalAt(Vec3 p) {
		double[] l = toLocal(p.x - center.x, p.z - center.z);
		double[] r = {Math.abs(l[0]) / ex, Math.abs(p.y - center.y) / ey, Math.abs(l[1]) / ez};
		if (r[1] >= r[0] && r[1] >= r[2]) return new Vec3(0, Math.signum(p.y - center.y), 0);
		double[] w = r[0] >= r[2] ? toWorld(Math.signum(l[0]), 0) : toWorld(0, Math.signum(l[1]));
		return new Vec3(w[0], 0, w[1]);
	}
}
