package local.rustak.building;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A box rotated about the vertical axis only: Rust building blocks only ever yaw. world = centre + Ry(yaw) * local. */
public record Obb(Vec3 center, double ex, double ey, double ez, float yaw, Vec3 topPlane, Vec3 bottomPlane) {
	public Obb(Vec3 center, double ex, double ey, double ez, float yaw) {
		this(center, ex, ey, ez, yaw, null, null);
	}

	static Obb at(org.joml.Vector3f[] data, Vec3 pos, float yaw) {
		var c = data[0]; var e = data[1];
		double cs = Math.cos(yaw), sn = Math.sin(yaw);
		Vec3 top = null, bottom = null;
		if (data.length > 2) {
			var t = data[2]; var b = data[3];
			top = new Vec3(t.x, t.y, t.x * c.x + t.y * c.z + t.z - c.y);
			bottom = new Vec3(b.x, b.y, b.x * c.x + b.y * c.z + b.z - c.y);
		}
		return new Obb(pos.add(c.x * cs + c.z * sn, c.y, -c.x * sn + c.z * cs), e.x, e.y, e.z, yaw, top, bottom);
	}

	public boolean sloped() {
		return topPlane != null && Math.abs(topPlane.x) + Math.abs(topPlane.y) > 1e-5;
	}

	/** Highest/lowest contact over the overlap of the foot rectangle and this box's plan. */
	public double topAt(AABB box) { return heightAt(box, topPlane, true); }
	public double bottomAt(AABB box) { return heightAt(box, bottomPlane, false); }

	/** First horizontal contact with the body's full swept rectangle, or -1 when the path is clear. */
	public double horizontalHit(AABB body, Vec3 move) {
		var contact = horizontalContact(body, move);
		return contact == null ? -1 : contact.fraction;
	}

	public record HorizontalContact(double fraction, Vec3 normal) { }

	/** Sweep against the convex slice that can touch the body's vertical interval. */
	public HorizontalContact horizontalContact(AABB body, Vec3 move) {
		final double epsilon = 1e-7;
		double feet = body.minY - center.y, head = body.maxY - center.y;
		if (feet >= ey - epsilon || head <= -ey + epsilon || !bounds().intersects(body.expandTowards(move))) return null;
		java.util.List<double[]> polygon = new java.util.ArrayList<>();
		for (int[] corner : new int[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}})
			polygon.add(new double[] {corner[0] * ex, corner[1] * ez});
		if (topPlane != null) {
			polygon = clipPlan(polygon, topPlane.x, topPlane.y, topPlane.z - feet - epsilon);
			polygon = clipPlan(polygon, -bottomPlane.x, -bottomPlane.y, head - bottomPlane.z - epsilon);
			polygon = clipPlan(polygon, topPlane.x - bottomPlane.x, topPlane.y - bottomPlane.y, topPlane.z - bottomPlane.z);
			polygon = clipPlan(polygon, topPlane.x, topPlane.y, topPlane.z + ey);
			polygon = clipPlan(polygon, -bottomPlane.x, -bottomPlane.y, ey - bottomPlane.z);
		}
		if (polygon.size() < 3) return null;
		double cs = cos(), sn = sin();
		for (var p : polygon) {
			double x = p[0], z = p[1];
			p[0] = center.x + x * cs + z * sn;
			p[1] = center.z - x * sn + z * cs;
		}
		double hx = (body.maxX - body.minX) / 2, hz = (body.maxZ - body.minZ) / 2;
		double cx = (body.minX + body.maxX) / 2, cz = (body.minZ + body.maxZ) / 2;
		double enter = Double.NEGATIVE_INFINITY, leave = Double.POSITIVE_INFINITY, escapeDepth = Double.POSITIVE_INFINITY;
		Vec3 normal = null, escapeNormal = null;
		boolean inside = true;
		for (int i = -2; i < polygon.size(); i++) {
			double ax, az;
			if (i < 0) { ax = i == -2 ? 1 : 0; az = i == -1 ? 1 : 0; }
			else {
				var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
				ax = a[1] - b[1]; az = b[0] - a[0];
				double length = Math.hypot(ax, az);
				if (length < 1e-10) continue;
				ax /= length; az /= length;
			}
			double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
			for (var p : polygon) { double projection = ax * p[0] + az * p[1]; low = Math.min(low, projection); high = Math.max(high, projection); }
			double mid = ax * cx + az * cz, radius = Math.abs(ax) * hx + Math.abs(az) * hz;
			double min = mid - radius, max = mid + radius, speed = ax * move.x + az * move.z;
			inside &= max > low + epsilon && min < high - epsilon;
			if (max - low < escapeDepth) { escapeDepth = max - low; escapeNormal = new Vec3(-ax, 0, -az); }
			if (high - min < escapeDepth) { escapeDepth = high - min; escapeNormal = new Vec3(ax, 0, az); }
			if (Math.abs(speed) < 1e-12) { if (max <= low + epsilon || min >= high - epsilon) return null; }
			else {
				double first = (low - max) / speed, last = (high - min) / speed;
				double entry = Math.min(first, last);
				if (entry > enter) { enter = entry; normal = new Vec3(speed > 0 ? -ax : ax, 0, speed > 0 ? -az : az); }
				leave = Math.min(leave, Math.max(first, last));
				if (enter > leave) return null;
			}
		}
		// An interval entered before this move is an existing contact, even below the overlap tolerance.
		if (inside || enter < 0) return move.dot(escapeNormal) < -epsilon ? new HorizontalContact(0, escapeNormal) : null;
		return normal == null || enter >= 1 || leave <= 0 ? null : new HorizontalContact(Math.max(0, enter), normal);
	}

	private static java.util.List<double[]> clipPlan(java.util.List<double[]> polygon, double ax, double az, double constant) {
		java.util.List<double[]> clipped = new java.util.ArrayList<>();
		for (int i = 0; i < polygon.size(); i++) {
			var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
			double va = ax * a[0] + az * a[1] + constant, vb = ax * b[0] + az * b[1] + constant;
			boolean inA = va >= 0, inB = vb >= 0;
			if (inA) clipped.add(a);
			if (inA != inB) { double t = va / (va - vb); clipped.add(new double[] {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])}); }
		}
		return clipped;
	}

	private double heightAt(AABB box, Vec3 plane, boolean highest) {
		if (plane == null) return center.y + (highest ? ey : -ey);
		double cs = cos(), sn = sin(), ac = Math.abs(cs), as = Math.abs(sn);
		double hx = (box.maxX - box.minX) / 2, hz = (box.maxZ - box.minZ) / 2;
		double dx = (box.minX + box.maxX) / 2 - center.x, dz = (box.minZ + box.maxZ) / 2 - center.z;
		if (Math.abs(dx) >= hx + ac * ex + as * ez || Math.abs(dz) >= hz + as * ex + ac * ez
				|| Math.abs(dx * cs - dz * sn) >= ex + ac * hx + as * hz
				|| Math.abs(dx * sn + dz * cs) >= ez + as * hx + ac * hz)
			return highest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
		java.util.List<double[]> polygon = new java.util.ArrayList<>();
		for (int[] corner : new int[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			double x = corner[0] * ex, z = corner[1] * ez;
			polygon.add(new double[] {center.x + x * cs + z * sn, center.z - x * sn + z * cs});
		}
		for (int side = 0; side < 4 && !polygon.isEmpty(); side++) {
			int axis = side / 2;
			double edge = axis == 0 ? (side % 2 == 0 ? box.minX : box.maxX) : (side % 2 == 0 ? box.minZ : box.maxZ);
			double sign = side % 2 == 0 ? 1 : -1;
			java.util.List<double[]> clipped = new java.util.ArrayList<>();
			for (int i = 0; i < polygon.size(); i++) {
				var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
				boolean insideA = sign * (a[axis] - edge) >= -1e-9, insideB = sign * (b[axis] - edge) >= -1e-9;
				if (insideA) clipped.add(a);
				if (insideA != insideB) {
					double t = (edge - a[axis]) / (b[axis] - a[axis]);
					clipped.add(new double[] {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])});
				}
			}
			polygon = clipped;
		}
		double height = highest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
		for (var point : polygon) {
			double x = point[0] - center.x, z = point[1] - center.z;
			double y = center.y + plane.x * (x * cs - z * sn) + plane.y * (x * sn + z * cs) + plane.z;
			height = highest ? Math.max(height, y) : Math.min(height, y);
		}
		return highest ? Math.min(height, center.y + ey) : Math.max(height, center.y - ey);
	}
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
		if (topPlane != null) for (int side = 0; side < 2; side++) {
			var plane = side == 0 ? topPlane : bottomPlane;
			double sign = side == 0 ? 1 : -1;
			double distance = sign * (ro[1] - plane.x * ro[0] - plane.y * ro[2] - plane.z);
			double speed = sign * (rd[1] - plane.x * rd[0] - plane.y * rd[2]);
			if (Math.abs(speed) < 1e-9) { if (distance > 0) return -1; }
			else if (speed < 0) tMin = Math.max(tMin, -distance / speed);
			else tMax = Math.min(tMax, -distance / speed);
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
