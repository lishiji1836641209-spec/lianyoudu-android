package com.lianyoudu.app;

import android.app.*;
import android.os.*;
import java.util.*;

/** Validate notification contracts independently of OEM capsule presentation. */
final class CapsuleAudit {
  static int run(android.content.Context c, FitnessApp app) {
    int checks = 0;
    Domain.Session saved = app.active;
    String status = app.routeStatus;
    try {
      Domain.Session q = new Domain.Session("gym", List.of());
      app.active = q;
      q.resume(SystemClock.elapsedRealtime());
      Notification n = WorkoutNotifications.create(c, q);
      require(
          n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("自由训练"),
          "free phase");
      checks++;
      require(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER), "running chronometer");
      checks++;
      Domain.Exercise e = new Domain.Exercise("squat", "深蹲", "", false, "");
      e.sets = 2;
      q.appendAll(List.of(e), SystemClock.elapsedRealtime());
      q.recordInline(0, 0, 10, 0, SystemClock.elapsedRealtime());
      n = WorkoutNotifications.create(c, q);
      require(
          n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("组间休息"),
          "rest phase");
      checks++;
      require(
          n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) && n.actions.length == 2,
          "rest countdown and skip action");
      checks++;
      q.pause(SystemClock.elapsedRealtime());
      n = WorkoutNotifications.create(c, q);
      require(
          !n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
              && n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("已暂停"),
          "pause freezes clock");
      checks++;
      Domain.Exercise timed = new Domain.Exercise("plank", "平板支撑", "", true, "");
      q = new Domain.Session("home", List.of(timed));
      app.active = q;
      q.resume(SystemClock.elapsedRealtime());
      n = WorkoutNotifications.create(c, q);
      require(
          n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("计时运动"),
          "timed phase");
      checks++;
      q.heart(120, SystemClock.elapsedRealtime());
      n = WorkoutNotifications.create(c, q);
      require(
          n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("心率 120"),
          "fresh heart shown");
      checks++;
      q.lastHeartElapsed = SystemClock.elapsedRealtime() - 20000;
      n = WorkoutNotifications.create(c, q);
      require(
          !n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("心率"),
          "stale heart omitted");
      checks++;
      Domain.Exercise bike = new Domain.Exercise("bike", "户外骑行", "", true, "");
      q = new Domain.Session("outdoor", List.of(bike));
      app.active = q;
      q.resume(SystemClock.elapsedRealtime());
      q.routeEnabled = true;
      for (String state : new String[] {"等待定位", "定位信号弱", "静止中", "正在记录路线"}) {
        app.routeStatus = state;
        n = WorkoutNotifications.create(c, q);
        require(
            n.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                .toString()
                .contains(state.equals("正在记录路线") ? "骑行中" : state),
            "route phase " + state);
        checks++;
      }
      require(
          n.contentView == null
              && n.bigContentView == null
              && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0,
          "standard promotable layout");
      checks++;
      if (Build.VERSION.SDK_INT >= 36) {
        require(n.extras.getBoolean("android.requestPromotedOngoing"), "promotion requested");
        checks++;
      }
      PendingIntent old = n.actions[0].actionIntent;
      Domain.Session fresh = new Domain.Session("gym", List.of());
      app.active = fresh;
      fresh.resume(SystemClock.elapsedRealtime());
      Notification freshN = WorkoutNotifications.create(c, fresh);
      require(!old.equals(freshN.actions[0].actionIntent), "controls isolated per session");
      checks++;
      return checks;
    } finally {
      app.active = saved;
      app.routeStatus = status;
    }
  }

  static void require(boolean b, String s) {
    if (!b) throw new AssertionError("capsule: " + s);
  }
}
