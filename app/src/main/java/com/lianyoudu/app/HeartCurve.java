package com.lianyoudu.app;

/** Drawing controls only; never modifies samples or their statistics. */
final class HeartCurve {
  private HeartCurve() {}

  static boolean canConnect(long before, long after, int beforeSegment, int afterSegment,
      float x0, float x1, float y0, float y1) {
    if (beforeSegment != afterSegment || after <= before
        || !Float.isFinite(x0) || !Float.isFinite(x1) || !(x1 > x0)
        || !Float.isFinite(y0) || !Float.isFinite(y1)) return false;
    return Double.isFinite(((double) y1 - y0) / ((double) x1 - x0));
  }

  /** Returns cubic control points, or null when this run must use straight lines. */
  static float[] controls(float[] x, float[] y) {
    int n = x.length;
    if (n < 3 || y.length != n) return null;
    double[] width = new double[n - 1], slope = new double[n - 1], tangent = new double[n];
    for (int i = 0; i < n - 1; i++) {
      width[i] = x[i + 1] - x[i];
      if (!(width[i] > 0) || !Float.isFinite(x[i]) || !Float.isFinite(x[i + 1])
          || !Float.isFinite(y[i]) || !Float.isFinite(y[i + 1])) return null;
      slope[i] = (y[i + 1] - y[i]) / width[i];
      if (!Double.isFinite(slope[i])) return null;
    }
    tangent[0] = slope[0];
    tangent[n - 1] = slope[n - 2];
    for (int i = 1; i < n - 1; i++) {
      if (slope[i - 1] * slope[i] <= 0) tangent[i] = 0;
      else {
        double w1 = 2 * width[i] + width[i - 1], w2 = width[i] + 2 * width[i - 1];
        tangent[i] = (w1 + w2) / (w1 / slope[i - 1] + w2 / slope[i]);
      }
    }
    float[] result = new float[(n - 1) * 4];
    for (int i = 0; i < n - 1; i++) {
      double min = Math.min(y[i], y[i + 1]), max = Math.max(y[i], y[i + 1]);
      result[i * 4] = (float) (x[i] + width[i] / 3);
      result[i * 4 + 1] = (float) Math.max(min, Math.min(max, y[i] + tangent[i] * width[i] / 3));
      result[i * 4 + 2] = (float) (x[i + 1] - width[i] / 3);
      result[i * 4 + 3] = (float) Math.max(min, Math.min(max, y[i + 1] - tangent[i + 1] * width[i] / 3));
      for (int k = i * 4; k < i * 4 + 4; k++) if (!Float.isFinite(result[k])) return null;
    }
    return result;
  }
}
