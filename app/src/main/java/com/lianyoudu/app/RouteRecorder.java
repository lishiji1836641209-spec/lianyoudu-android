package com.lianyoudu.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;

/** Phone GNSS capture only for an explicitly enabled, running outdoor session. */
final class RouteRecorder implements LocationListener {
  private final FitnessApp app;
  private final LocationManager manager;
  private boolean listening;
  private final RouteTrack.MotionGate motion = new RouteTrack.MotionGate();
  String status = "未开启路线记录";

  RouteRecorder(FitnessApp app) {
    this.app = app;
    manager = app.getSystemService(LocationManager.class);
  }

  private void state(String value) {
    status = value;
    app.routeStatus = value;
  }

  boolean permitted() {
    return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  void sync() {
    Domain.Session session = app.active;
    boolean should =
        session != null
            && session.routeEnabled
            && session.running
            && permitted()
            && !session.items.isEmpty()
            && session.item().exercise.usesGps();
    if (!should) {
      stop();
      if (session != null && session.routeEnabled) state(permitted() ? "路线记录已暂停" : "需要精确位置权限");
      return;
    }
    if (listening) return;
    try {
      if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        state("请开启系统定位服务");
        return;
      }
      manager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER, 3000, 0, this, Looper.getMainLooper());
      listening = true;
      state("正在定位，首个有效位置后开始画线");
    } catch (SecurityException | IllegalArgumentException e) {
      state("无法获取定位，请检查权限和系统定位开关");
    }
  }

  void stop() {
    motion.reset();
    if (listening) {
      try {
        manager.removeUpdates(this);
      } catch (SecurityException ignored) {
      }
      listening = false;
    }
    if (app.active != null) app.active.route.breakSegment();
  }

  @Override
  public void onLocationChanged(Location location) {
    Domain.Session q = app.active;
    if (q == null
        || !q.routeEnabled
        || !q.running
        || q.items.isEmpty()
        || !q.item().exercise.usesGps()) return;
    int quality =
        motion.evaluate(
            location.getLatitude(),
            location.getLongitude(),
            location.hasAccuracy() ? location.getAccuracy() : Double.NaN,
            location.hasSpeed() ? location.getSpeed() : Double.NaN,
            location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : Double.NaN,
            location.getElapsedRealtimeNanos() / 1000000,
            SystemClock.elapsedRealtime());
    if (quality != RouteTrack.MotionGate.MOVING) {
      q.route.breakSegment();
      state(
          quality == RouteTrack.MotionGate.STILL
              ? "定位正常，静止时不累计距离"
              : quality == RouteTrack.MotionGate.ACQUIRING ? "正在确认定位，暂不累计距离" : "GPS信号较弱，请移至开阔处");
      app.changed();
      return;
    }
    RouteTrack before = new RouteTrack(q.route.meters, q.route.segment, q.route.last);
    before.gap = q.route.gap;
    RouteTrack.Point point =
        q.route.accept(
            location.getLatitude(),
            location.getLongitude(),
            location.hasAccuracy() ? location.getAccuracy() : 999,
            location.getTime(),
            location.getElapsedRealtimeNanos() / 1000000,
            SystemClock.elapsedRealtime());
    if (point == null) {
      q.route.breakSegment();
      state("定位精度不足或位置跳变，等待有效位置");
      return;
    }
    try {
      double previousItemMeters = q.item().gpsMeters;
      q.item().gpsMeters += Math.max(0, q.route.meters - before.meters);
      try {
        app.store.routePoint(q, point);
      } catch (Exception error) {
        q.item().gpsMeters = previousItemMeters;
        throw error;
      }
      state("正在记录路线");
      app.changed();
    } catch (Exception e) {
      q.route = before;
      q.running = false;
      app.noteTrainingFailure();
      stop();
      app.failure = "路线保存失败，运动已暂停";
      app.changed();
    }
  }

  @Override
  public void onProviderDisabled(String provider) {
    stop();
    state("定位服务已关闭，路线已分段");
    app.changed();
  }

  @Override
  public void onProviderEnabled(String provider) {
    sync();
    app.changed();
  }
}
