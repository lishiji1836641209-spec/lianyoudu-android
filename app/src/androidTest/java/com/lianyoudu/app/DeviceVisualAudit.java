package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Isolated HONOR presentation probes. Never save, resume, finish, or delete the user's session. */
final class DeviceVisualAudit {
  static void run(Instrumentation test, FitnessApp app) throws Exception {
    NotificationManager manager = app.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("workout", "训练计时", NotificationManager.IMPORTANCE_LOW));
    JSONArray report = new JSONArray();
    String[] cases = {"自由训练", "力量训练", "组间休息", "计时运动", "已暂停", "等待定位", "定位信号弱", "静止中", "骑行中"};
    test.getUiAutomation()
        .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
    try {
      for (String scenario : cases) {
        test.getUiAutomation()
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
        final Notification[] out = {null};
        test.runOnMainSync(
            () -> {
              Domain.Session q;
              Domain.Exercise e = new Domain.Exercise("squat", "徒手深蹲", "", false, "");
              e.sets = 3;
              if (scenario.equals("计时运动")) e = new Domain.Exercise("plank", "平板支撑", "", true, "");
              boolean ride = Arrays.asList("等待定位", "定位信号弱", "静止中", "骑行中").contains(scenario);
              if (ride) e = new Domain.Exercise("bike", "户外骑行", "", true, "");
              q =
                  new Domain.Session(
                      ride ? "outdoor" : "gym", scenario.equals("自由训练") ? List.of() : List.of(e));
              q.elapsed = 183000;
              q.resume(SystemClock.elapsedRealtime());
              if (scenario.equals("组间休息")) q.rest = 45000;
              if (scenario.equals("计时运动")) q.item().currentMs = 18000;
              if (scenario.equals("已暂停")) q.pause(SystemClock.elapsedRealtime());
              q.routeEnabled = ride;
              if (scenario.equals("骑行中")) {
                q.route.meters = 1250;
                q.item().gpsMeters = 1250;
                q.item().activeMs = q.elapsed;
                q.item().currentMs = q.elapsed;
              }
              String original = app.routeStatus;
              app.routeStatus = scenario.equals("骑行中") ? "正在记录路线" : scenario;
              try {
                Notification n = WorkoutNotifications.create(app, q);
                out[0] =
                    Notification.Builder.recoverBuilder(app, n)
                        .setContentTitle(
                            "验收 · " + n.extras.getCharSequence(Notification.EXTRA_TITLE))
                        .build();
              } finally {
                app.routeStatus = original;
              }
              manager.notify(501, out[0]);
            });
        Thread.sleep(1800);
        boolean promoted = false;
        for (android.service.notification.StatusBarNotification n :
            manager.getActiveNotifications())
          if (n.getId() == 501)
            promoted = (n.getNotification().flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
        Bitmap screenshot = test.getUiAutomation().takeScreenshot();
        try (FileOutputStream f =
            new FileOutputStream(new File(app.getFilesDir(), "capsule-" + scenario + ".png"))) {
          screenshot.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        screenshot.recycle();
        test.getUiAutomation()
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        Thread.sleep(500);
        Bitmap expanded = test.getUiAutomation().takeScreenshot();
        try (FileOutputStream f =
            new FileOutputStream(
                new File(app.getFilesDir(), "capsule-expanded-" + scenario + ".png"))) {
          expanded.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        expanded.recycle();
        test.getUiAutomation()
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        report.put(
            new JSONObject()
                .put("scenario", scenario)
                .put("promoted", promoted)
                .put("title", out[0].extras.getCharSequence(Notification.EXTRA_TITLE))
                .put("clock", out[0].extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)));
      }
    } finally {
      manager.cancel(501);
    }
    try (FileWriter f = new FileWriter(new File(app.getFilesDir(), "capsule-visual-audit.json"))) {
      f.write(
          new JSONObject()
              .put(
                  "scope",
                  "Notification presentation only; physical ride and foreground service continuity"
                      + " are separate gates")
              .put("scenarios", report)
              .toString(2));
    }
  }
}
