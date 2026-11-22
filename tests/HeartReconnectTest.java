package com.lianyoudu.app;

public final class HeartReconnectTest {
  private static int checks;

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }

  public static void main(String[] args) {
    int attempt = 0;
    long elapsed = 0, previousDelay = 0;
    int attempts = 0;
    while (elapsed < 8 * 60 * 60 * 1000L) {
      long delay = HeartReconnectPolicy.delayMillis(attempt, true, true);
      if (delay < previousDelay || delay < 3000 || delay > 60000)
        throw new AssertionError("retry interval must stay bounded and never expire");
      elapsed += delay + 20000; // A failed connection's timeout also consumes wall time.
      previousDelay = delay;
      attempt = HeartReconnectPolicy.nextAttempt(attempt);
      attempts++;
    }
    check(attempts > 3, "a long broadcast outage cannot permanently exhaust recovery");
    check(attempts < 400, "eight-hour outage cannot become a hot retry loop");
    check(HeartReconnectPolicy.delayMillis(3, true, true) > 0, "fourth retry remains available");
    check(
        HeartReconnectPolicy.delayMillis(Integer.MAX_VALUE, true, true) == 60000,
        "large failure counts saturate safely");
    check(
        HeartReconnectPolicy.nextAttempt(Integer.MAX_VALUE) == 5,
        "attempt increment cannot overflow");
    check(HeartReconnectPolicy.delayMillis(5, false, true) == -1, "pause stops scheduled retries");
    check(HeartReconnectPolicy.delayMillis(5, true, false) == -1, "revocation stops retries");
    check(
        HeartReconnectPolicy.delayMillis(0, true, true) == 3000, "fresh connection resets backoff");
    System.out.println("PASS " + checks + " heart reconnection policy assertions");
  }
}
