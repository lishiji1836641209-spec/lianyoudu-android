package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

public final class WorkoutService extends Service {
  private FitnessApp app;
  private final Handler h = new Handler(Looper.getMainLooper());
  private long saved;
  private boolean postedRunning;
  private String postedSignature = "";
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (app.active == null) {
            stopSelf();
            return;
          }
          app.active.tick(SystemClock.elapsedRealtime());
          app.ble.checkSilent(SystemClock.elapsedRealtime());
          if (SystemClock.elapsedRealtime() - saved >= 5000
              || postedRunning != app.active.running
              || !postedSignature.equals(WorkoutNotifications.signature(app))) {
            postedRunning = app.active.running;
            postedSignature = WorkoutNotifications.signature(app);
            app.checkpoint();
            saved = SystemClock.elapsedRealtime();
            getSystemService(NotificationManager.class).notify(1, notification());
          }
          app.changed();
          h.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    app = (FitnessApp) getApplication();
    NotificationChannel channel =
        new NotificationChannel("workout", "训练计时", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("训练进行时显示计时及暂停入口");
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    if (app.active == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (i != null && i.getAction() != null && !app.active.id.equals(i.getStringExtra("session")))
      return START_NOT_STICKY;
    if (i != null && "skip_rest".equals(i.getAction())) {
      try {
        app.mutate(q -> q.endRest(SystemClock.elapsedRealtime()));
      } catch (Exception ignored) {
        /* app exposes persistence failure */
      }
    }
    if (i != null && "pause".equals(i.getAction())) {
      try {
        app.setTrainingRunning(app.active.id, false);
      } catch (Exception ignored) {
        /* app exposes persistence failure */
      }
    }
    if (i != null && "resume".equals(i.getAction())) {
      try {
        app.setTrainingRunning(app.active.id, true);
      } catch (Exception ignored) {
        /* app exposes persistence failure */
      }
    }
    if (i != null && "dismiss_live".equals(i.getAction()))
      app.store.setSetting("liveMutedSession", app.active.id);
    int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
    if (android.os.Build.VERSION.SDK_INT >= 34 && app.ble.connected())
      type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
    try {
      startForeground(1, notification(), type);
    } catch (SecurityException e) {
      startForeground(
          1,
          notification(),
          Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
      app.failure = "部分设备连接权限不可用，训练计时继续";
    }
    h.removeCallbacks(tick);
    h.post(tick);
    return START_NOT_STICKY;
  }

  private Notification notification() {
    return WorkoutNotifications.create(this, app.active);
  }

  @Override
  public void onDestroy() {
    h.removeCallbacks(tick);
    app.checkpoint();
    app.ble.close();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
