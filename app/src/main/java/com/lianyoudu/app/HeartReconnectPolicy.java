package com.lianyoudu.app;

/** Reconnection remains available throughout a workout, without a tight retry loop. */
final class HeartReconnectPolicy {
  private static final long[] DELAYS = {3000, 6000, 9000, 20000, 30000, 60000};

  static long delayMillis(int failures, boolean running, boolean permitted) {
    if (!running || !permitted) return -1;
    return DELAYS[Math.min(DELAYS.length - 1, Math.max(0, failures))];
  }

  static int nextAttempt(int attempt) {
    int bounded = Math.max(0, Math.min(DELAYS.length - 1, attempt));
    return Math.min(DELAYS.length - 1, bounded + 1);
  }
}
