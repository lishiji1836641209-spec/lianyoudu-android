package com.lianyoudu.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;

/** QA-only timing evidence; never logs device identity, payloads or heart-rate values. */
final class HeartDiagnostics {
  private final boolean enabled;
  private long lastCallback, lastValid, lastReport, callbacks, rejected, maxDispatchDelay;

  HeartDiagnostics(Context context) {
    enabled =
        context.getPackageName().endsWith(".qa")
            && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
  }

  synchronized void event(String event) {
    if (enabled) Log.i("HeartTiming", "at=" + SystemClock.elapsedRealtime() + " " + event);
  }

  synchronized void notification(long receivedAt, boolean valid) {
    if (!enabled) return;
    long gap = lastCallback == 0 ? 0 : receivedAt - lastCallback;
    callbacks++;
    if (!valid) rejected++;
    if (gap > 15000) event("notification_gap_ms=" + gap);
    lastCallback = receivedAt;
    if (valid) lastValid = receivedAt;
  }

  synchronized void dispatched(long receivedAt) {
    if (!enabled) return;
    maxDispatchDelay = Math.max(maxDispatchDelay, SystemClock.elapsedRealtime() - receivedAt);
  }

  synchronized void report(long now, boolean ready) {
    if (!enabled || now - lastReport < 30000) return;
    lastReport = now;
    event(
        "subscribed="
            + ready
            + " callbacks="
            + callbacks
            + " rejected="
            + rejected
            + " callback_age_ms="
            + (lastCallback == 0 ? -1 : now - lastCallback)
            + " valid_age_ms="
            + (lastValid == 0 ? -1 : now - lastValid)
            + " max_dispatch_ms="
            + maxDispatchDelay);
    maxDispatchDelay = 0;
  }
}
