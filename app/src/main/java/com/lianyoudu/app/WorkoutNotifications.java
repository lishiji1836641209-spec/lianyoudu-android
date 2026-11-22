package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.Locale;

/** Standard promoted ongoing notifications; the system owns HONOR capsule layout. */
final class WorkoutNotifications {
  static String label(Domain.Session q) {
    return q.items.isEmpty() ? "自由训练" : q.item().exercise.name;
  }

  static String phase(Domain.Session q, String routeStatus) {
    if (!q.running) return "已暂停";
    if (q.rest > 0) return "组间休息";
    if (q.items.isEmpty()) return "自由训练";
    if (q.item().exercise.distanceBased())
      return q.item().exercise.id.equals("walk")
          ? "步行中"
          : q.item().exercise.id.equals("run") ? "跑步中" : "骑行中";
    if (q.item().done()) return "本项完成";
    return q.item().exercise.timed ? "计时运动" : "力量训练";
  }

  static String signature(FitnessApp app) {
    Domain.Session q = app.active;
    return q == null
        ? ""
        : q.id
            + ":"
            + phase(q, app.routeStatus)
            + ":"
            + q.current
            + ":"
            + q.count()
            + ":"
            + q.target()
            + ":"
            + (q.liveHeart(SystemClock.elapsedRealtime()) > 0);
  }

  private static PendingIntent control(Context c, Domain.Session q, String action, int code) {
    return PendingIntent.getService(
        c,
        code,
        new Intent(c, WorkoutService.class)
            .setAction(action)
            .setData(android.net.Uri.parse("lianyoudu://session/" + q.id + "/" + action))
            .putExtra("session", q.id),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static Notification create(Context context, Domain.Session q) {
    FitnessApp app = (FitnessApp) context.getApplicationContext();
    PendingIntent open =
        PendingIntent.getActivity(
            context,
            1,
            new Intent(context, MainActivity.class).putExtra("training", true),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String phase = phase(q, app.routeStatus), label = label(q);
    String title = label.equals(phase) ? label : label + " · " + phase;
    String detail;
    if (!q.items.isEmpty() && q.item().exercise.distanceBased()) {
      Domain.Item current = q.item();
      String value = current.exercise.id.equals("stair_climb")
          ? current.stairSteps == null ? "台阶数未记录" : "台阶 " + current.stairSteps + "级"
          : current.manualMeters == null && current.gpsMeters <= 0 ? "距离未记录"
              : String.format(Locale.CHINA, "%.2f 公里", current.meters() / 1000);
      detail = value + " · 项目时长 " + Domain.clock(current.durationMs());
    } else if (q.items.isEmpty()) detail = "运动 " + Domain.clock(q.elapsed) + " · 随时返回添加运动";
    else {
      Domain.Item item = q.item();
      detail =
          item.exercise.name
              + " · 完成 "
              + item.logs.stream().filter(l -> !l.partial).count()
              + " / "
              + item.exercise.sets
              + " 组";
      if (q.rest > 0)
        detail += "\n休息剩余 " + Domain.clock(q.rest) + " · 运动 " + Domain.clock(q.elapsed);
      else if (item.exercise.timed && !item.done())
        detail +=
            "\n本组 "
                + Domain.clock(item.currentMs)
                + " / "
                + Domain.clock(item.exercise.seconds * 1000L)
                + " · 运动 "
                + Domain.clock(q.elapsed);
      else detail += "\n累计 " + q.count() + " 组 · 运动 " + Domain.clock(q.elapsed);
    }
    int heart = q.liveHeart(SystemClock.elapsedRealtime());
    if (heart > 0) detail += " · 心率 " + heart;
    boolean rest = q.running && q.rest > 0;
    boolean timed =
        q.running
            && q.rest == 0
            && !q.items.isEmpty()
            && q.item().exercise.timed
            && !q.item().done();
    long shown =
        !q.items.isEmpty() && q.item().exercise.distanceBased()
            ? q.item().durationMs()
            : timed ? q.item().currentMs : q.elapsed;
    Notification.Builder builder =
        new Notification.Builder(context, "workout")
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(
                rest ? System.currentTimeMillis() + q.rest : System.currentTimeMillis() - shown)
            .setUsesChronometer(q.running)
            .setChronometerCountDown(rest)
            .setShowWhen(true)
            .setColor(HealthDesign.blue(false))
            .addAction(
                new Notification.Action.Builder(
                        null,
                        q.running ? "暂停" : "继续",
                        control(context, q, q.running ? "pause" : "resume", 2))
                    .build());
    if (rest)
      builder.addAction(
          new Notification.Action.Builder(null, "结束休息", control(context, q, "skip_rest", 4))
              .build());
    if (Build.VERSION.SDK_INT >= 36 && !app.store.setting("liveMutedSession", "").equals(q.id)) {
      Bundle extras = new Bundle();
      extras.putBoolean("android.requestPromotedOngoing", true);
      builder.addExtras(extras);
      if (!q.running) builder.setShortCriticalText("暂停");
    }
    builder.setDeleteIntent(control(context, q, "dismiss_live", 3));
    return builder.build();
  }
}
