package com.lianyoudu.app;

/** Pure GPS acceptance rules: accepted GNSS points only, discontinuities remain separate. */
final class RouteTrack {
  /** Conservative motion gate: position precision alone cannot establish actual movement. */
  static final class MotionGate {
    static final int WEAK = 0, STILL = 1, ACQUIRING = 2, MOVING = 3;
    private Point candidate;
    private double previousSpeed;
    private int fixes;
    private long started;

    void reset() {
      candidate = null;
      fixes = 0;
    }

    int evaluate(
        double lat,
        double lon,
        double accuracy,
        double speed,
        double speedAccuracy,
        long elapsed,
        long now) {
      if (!Double.isFinite(lat)
          || !Double.isFinite(lon)
          || Math.abs(lat) > 90
          || Math.abs(lon) > 180
          || !Double.isFinite(accuracy)
          || accuracy <= 0
          || accuracy > 20
          || !Double.isFinite(speed)
          || speed < 0
          || speed > 35
          || !Double.isFinite(speedAccuracy)
          || speedAccuracy < 0
          || speedAccuracy > 1.5
          || elapsed < 0
          || now < elapsed
          || now - elapsed > 10000) {
        reset();
        return WEAK;
      }
      if (speed < .8 || speed - speedAccuracy < .4) {
        reset();
        return STILL;
      }
      if (candidate != null) {
        long dt = elapsed - candidate.elapsed;
        if (dt < 2000) return ACQUIRING;
        double distance = distance(candidate.latitude, candidate.longitude, lat, lon);
        double budget =
            (previousSpeed + speed) * .5 * dt / 1000.0 * 1.8 + candidate.accuracy + accuracy;
        if (dt > 10000 || distance > Math.max(12, budget)) {
          reset();
        }
      }
      if (candidate == null) started = elapsed;
      candidate = new Point(lat, lon, accuracy, 0, elapsed, 0);
      previousSpeed = speed;
      fixes++;
      return fixes >= 3 && elapsed - started >= 6000 ? MOVING : ACQUIRING;
    }
  }

  static final class Point {
    final double latitude, longitude, accuracy;
    final long wall, elapsed;
    final int segment;

    Point(double lat, double lon, double accuracy, long wall, long elapsed, int segment) {
      latitude = lat;
      longitude = lon;
      this.accuracy = accuracy;
      this.wall = wall;
      this.elapsed = elapsed;
      this.segment = segment;
    }
  }

  double meters;
  int segment;
  Point last;
  boolean gap = true;

  RouteTrack() {}

  RouteTrack(double meters, int segment, Point last) {
    this.meters = meters;
    this.segment = segment;
    this.last = last;
    gap = true;
  }

  void breakSegment() {
    gap = true;
  }

  Point accept(double lat, double lon, double accuracy, long wall, long elapsed, long now) {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lon)
        || lat < -90
        || lat > 90
        || lon < -180
        || lon > 180
        || !Double.isFinite(accuracy)
        || accuracy <= 0
        || accuracy > 35
        || wall <= 0
        || elapsed < 0
        || now < elapsed
        || now - elapsed > 15000) return null;
    if (last != null) {
      long dt = elapsed - last.elapsed;
      if ((!gap && dt < 2000) || (gap && wall <= last.wall)) return null;
      double d = distance(last.latitude, last.longitude, lat, lon);
      if (!gap && dt <= 60000 && d / (dt / 1000.0) > 35) return null;
      if (!gap && dt <= 60000 && d < Math.max(3, Math.min(accuracy, last.accuracy) * .6))
        return null;
      if (gap || dt > 60000) segment++;
      else meters += d;
    }
    Point point = new Point(lat, lon, accuracy, wall, elapsed, segment);
    last = point;
    gap = false;
    return point;
  }

  static double distance(double a, double b, double c, double d) {
    double lat = Math.toRadians(c - a),
        lon = Math.toRadians(d - b),
        v =
            Math.sin(lat / 2) * Math.sin(lat / 2)
                + Math.cos(Math.toRadians(a))
                    * Math.cos(Math.toRadians(c))
                    * Math.sin(lon / 2)
                    * Math.sin(lon / 2);
    return 6371008.8 * 2 * Math.atan2(Math.sqrt(v), Math.sqrt(Math.max(0, 1 - v)));
  }
}
