package cn.pixelmc.dailyfortunes.client;

/** Seven damped, colliding tip particles. No centering spring: settled positions stay put. */
public final class StickPhysics {
  public static final double[][] PIVOTS = {
    {.5026312, .46875, .4298438},
    {.5601875, .46875, .54345},
    {.4847938, .46875, .5166063},
    {.5452375, .46875, .4594313},
    {.46985, .46875, .5976125},
    {.5808437, .46875, .421975},
    {.4294562, .46875, .4875687}
  };
  public final double[] x = new double[7],
      z = new double[7],
      y = new double[7],
      vx = new double[7],
      vz = new double[7],
      vy = new double[7];

  public StickPhysics() {
    for (int i = 0; i < 7; i++) {
      x[i] = PIVOTS[i][0];
      z[i] = PIVOTS[i][2];
    }
  }

  public void impulse(double dx, double dz, double lift) {
    dx = clamp(dx, -.35, .35);
    dz = clamp(dz, -.35, .35);
    lift = clamp(lift, -.3, .3);
    for (int i = 0; i < 7; i++) {
      double mass = 1 + i * .075;
      vx[i] += dx / mass;
      vz[i] += dz / mass;
      vy[i] += lift / mass;
    }
  }

  public void advance(double dt, double ax, double az, double lift) {
    if (!Double.isFinite(dt) || dt <= 0) return;
    dt = Math.min(dt, .1);
    int count = Math.max(1, (int) Math.ceil(dt * 120));
    double h = dt / count;
    ax = clamp(ax, -6, 6);
    az = clamp(az, -6, 6);
    lift = clamp(lift, -8, 8);
    for (int step = 0; step < count; step++) {
      for (int i = 0; i < 7; i++) {
        double mass = 1 + i * .075;
        vx[i] += ax * h / mass;
        vz[i] += az * h / mass;
        vy[i] += (lift / mass - 2.5) * h;
        double drag = Math.exp(-5 * h);
        vx[i] *= drag;
        vz[i] *= drag;
        vy[i] *= Math.exp(-3 * h);
        x[i] += vx[i] * h;
        z[i] += vz[i] * h;
        y[i] += vy[i] * h;
        if (y[i] < 0) {
          y[i] = 0;
          vy[i] = -vy[i] * .18;
        }
        if (y[i] > .045) {
          y[i] = .045;
          vy[i] = -Math.abs(vy[i]) * .2;
        }
        confine(i);
      }
      for (int iteration = 0; iteration < 3; iteration++)
        for (int i = 0; i < 7; i++)
          for (int j = i + 1; j < 7; j++) {
            double dx = x[j] - x[i],
                dz = z[j] - z[i],
                distance = Math.hypot(dx, dz),
                minimum = .039;
            if (distance < minimum && distance > 1e-8) {
              double nx = dx / distance, nz = dz / distance, overlap = (minimum - distance) * .5;
              x[i] -= nx * overlap;
              z[i] -= nz * overlap;
              x[j] += nx * overlap;
              z[j] += nz * overlap;
              double rel = (vx[j] - vx[i]) * nx + (vz[j] - vz[i]) * nz;
              if (rel < 0) {
                double impulse = -rel * .62;
                vx[i] -= nx * impulse;
                vz[i] -= nz * impulse;
                vx[j] += nx * impulse;
                vz[j] += nz * impulse;
              }
              confine(i);
              confine(j);
            }
          }
      for (int i = 0; i < 7; i++) {
        if (Math.abs(ax) + Math.abs(az) < .01 && Math.hypot(vx[i], vz[i]) < .002) {
          vx[i] = 0;
          vz[i] = 0;
        }
        if (y[i] < .0005 && Math.abs(vy[i]) < .02) {
          y[i] = 0;
          vy[i] = 0;
        }
      }
    }
  }

  private void confine(int i) {
    double dx = x[i] - PIVOTS[i][0], dz = z[i] - PIVOTS[i][2], r = Math.hypot(dx, dz), limit = .065;
    if (r > limit) {
      double nx = dx / r, nz = dz / r;
      x[i] = PIVOTS[i][0] + nx * limit;
      z[i] = PIVOTS[i][2] + nz * limit;
      double speed = vx[i] * nx + vz[i] * nz;
      if (speed > 0) {
        vx[i] -= nx * speed * 1.22;
        vz[i] -= nz * speed * 1.22;
      }
    }
    double cx = x[i] - .5, cz = z[i] - .5, wall = Math.hypot(cx, cz);
    if (wall > .16) {
      x[i] = .5 + cx / wall * .16;
      z[i] = .5 + cz / wall * .16;
      vx[i] *= .2;
      vz[i] *= .2;
    }
  }

  private static double clamp(double v, double min, double max) {
    return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : 0;
  }
}
