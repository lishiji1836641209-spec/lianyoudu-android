package com.lianyoudu.app;

import android.accessibilityservice.AccessibilityService;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.service.notification.StatusBarNotification;
import java.io.*;
import java.util.*;
import org.json.*;

/** Real service and system-posted controls; isolated app only, no mock location or heart data. */
final class ContinuityAudit {
  private static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  static Notification posted(FitnessApp app) {
    for (StatusBarNotification entry :
        app.getSystemService(NotificationManager.class).getActiveNotifications())
      if (entry.getId() == 1) return entry.getNotification();
    throw new AssertionError("Workout notification missing");
  }

  static void shot(Instrumentation test, FitnessApp app, String name) throws Exception {
    Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    if (bitmap == null) throw new AssertionError("No system screenshot");
    try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }

  static void run(Instrumentation test, Activity activity, FitnessApp app) throws Exception {
    if (!app.getPackageName().equals("com.lianyoudu.app.qa") || app.active != null)
      throw new IllegalStateException("Requires a clean isolated installation");
    List<String> created = new ArrayList<>();
    JSONObject report = new JSONObject();
    checks = 0;
    try {
      test.runOnMainSync(
          () -> {
            Domain.Exercise exercise =
                Domain.catalog("all", Set.of()).stream()
                    .filter(e -> e.id.equals("squat"))
                    .findFirst()
                    .orElseThrow();
            exercise.sets = 3;
            app.start("all", List.of(exercise));
            created.add(app.active.id);
            activity.startForegroundService(new Intent(activity, WorkoutService.class));
          });
      Thread.sleep(1600);
      Notification running = posted(app);
      check(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER), "posted live clock");
      check((running.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0, "real foreground service");
      report.put("promoted", (running.flags & Notification.FLAG_PROMOTED_ONGOING) != 0);
      PendingIntent stalePause = running.actions[0].actionIntent;
      test.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
      Thread.sleep(600);
      shot(test, app, "continuity-capsule-running");
      test.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
      Thread.sleep(600);
      shot(test, app, "continuity-notification-running");
      test.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
      long before = app.active.elapsed;
      Thread.sleep(4000);
      check(app.active.elapsed >= before + 3000, "background elapsed advances");
      running.actions[0].actionIntent.send();
      Thread.sleep(1200);
      check(!app.active.running, "system-posted pause controls actual session");
      long paused = app.active.elapsed;
      Thread.sleep(1800);
      check(app.active.elapsed == paused, "paused elapsed remains unchanged");
      Notification pausedNotification = posted(app);
      check(
          !pausedNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
          "posted pause freezes clock");
      shot(test, app, "continuity-capsule-paused");
      pausedNotification.actions[0].actionIntent.send();
      Thread.sleep(1500);
      check(
          app.active.running && app.active.elapsed > paused,
          "system-posted resume advances session");
      test.runOnMainSync(
          () -> app.mutate(q -> q.recordInline(0, 0, 10, 0, SystemClock.elapsedRealtime())));
      Thread.sleep(1300);
      Notification resting = posted(app);
      check(
          app.active.rest > 0 && resting.actions.length == 2,
          "real rest notification exposes skip");
      check(
          resting.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
          "rest countdown enabled");
      resting.actions[1].actionIntent.send();
      Thread.sleep(1200);
      check(app.active.rest == 0, "posted skip-rest action works");
      long beforeLock = app.active.elapsed;
      test.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
      Thread.sleep(8000);
      long afterLock = app.active.elapsed;
      check(afterLock >= beforeLock + 6500, "short screen-off interval preserves timing");
      report.put("screenOffMs", afterLock - beforeLock);
      try (ParcelFileDescriptor ignored =
          test.getUiAutomation().executeShellCommand("input keyevent KEYCODE_WAKEUP")) {}
      test.runOnMainSync(
          () -> {
            app.finish();
            app.start("all", List.of());
            created.add(app.active.id);
          });
      stalePause.send();
      Thread.sleep(1300);
      check(
          app.active.running && app.active.id.equals(created.get(1)),
          "old notification cannot pause a new session");
      report.put("checks", checks);
      report.put(
          "scope",
          "Actual foreground service, system-posted PendingIntent actions, background and 8-second"
              + " screen-off timing. Long-duration power management, outdoor GPS and wearable"
              + " connection are separate pending tests.");
    } finally {
      test.runOnMainSync(
          () -> {
            if (app.active != null && created.contains(app.active.id)) app.finish();
            activity.stopService(new Intent(activity, WorkoutService.class));
            for (String id : created) app.store.removeWorkout(id);
          });
      try (FileWriter out = new FileWriter(new File(app.getFilesDir(), "continuity-audit.json"))) {
        out.write(report.toString(2));
      }
    }
  }
}
