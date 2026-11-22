package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import java.io.*;
import java.util.*;

/** Emulator-only provider injection exercises the same public GNSS callbacks as a physical ride. */
final class RouteAudit {
  static void shell(Instrumentation test, String command) throws Exception {
    try (InputStream in =
        new ParcelFileDescriptor.AutoCloseInputStream(
            test.getUiAutomation().executeShellCommand(command))) {
      byte[] b = new byte[1024];
      while (in.read(b) != -1) {}
    }
  }

  static void run(Instrumentation test, Activity activity, FitnessApp app) throws Exception {
    if (!android.os.Build.FINGERPRINT.contains("generic")
        && !android.os.Build.MODEL.startsWith("sdk"))
      throw new IllegalStateException("Mock location provider is emulator-only");
    test.getUiAutomation()
        .grantRuntimePermission(
            activity.getPackageName(), "android.permission.ACCESS_COARSE_LOCATION");
    test.getUiAutomation()
        .grantRuntimePermission(
            activity.getPackageName(), "android.permission.ACCESS_FINE_LOCATION");
    shell(test, "appops set " + activity.getPackageName() + " android:mock_location allow");
    LocationManager manager = activity.getSystemService(LocationManager.class);
    String[] id = {null};
    int[] checks = {0};
    try {
      manager.addTestProvider(
          "gps",
          false,
          false,
          false,
          false,
          true,
          true,
          true,
          Criteria.POWER_LOW,
          Criteria.ACCURACY_FINE);
      manager.setTestProviderEnabled("gps", true);
      test.runOnMainSync(
          () -> {
            Domain.Exercise bike = Domain.catalog("outdoor", app.store.gear()).get(0);
            bike.sets = 1;
            app.start("outdoor", Collections.singletonList(bike));
            app.mutate(q -> q.routeEnabled = true);
            id[0] = app.active.id;
            activity.startForegroundService(new Intent(activity, WorkoutService.class));
          });
      test.runOnMainSync(
          () -> {
            try {
              java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page");
              page.setAccessible(true);
              page.setInt(activity, 3);
              java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
              render.setAccessible(true);
              render.invoke(activity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      Thread.sleep(1000);
      send(manager, 39, 116);
      Thread.sleep(3400);
      send(manager, 39.0001, 116);
      Thread.sleep(3400);
      send(manager, 39.0002, 116);
      Thread.sleep(3400);
      send(manager, 39.0003, 116);
      Thread.sleep(800);
      if (app.store.routePoints(id[0]).size() != 2)
        throw new AssertionError("GNSS callbacks did not persist two points");
      checks[0]++;
      android.graphics.Bitmap routeShot = test.getUiAutomation().takeScreenshot();
      try (FileOutputStream file =
          new FileOutputStream(new File(activity.getFilesDir(), "route-recording.png"))) {
        routeShot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
      }
      routeShot.recycle();
      double before = app.active.route.meters;
      test.runOnMainSync(() -> app.mutate(q -> q.pause(SystemClock.elapsedRealtime())));
      Thread.sleep(1400);
      send(manager, 39.0002, 116);
      Thread.sleep(400);
      if (app.store.routePoints(id[0]).size() != 2)
        throw new AssertionError("paused ride recorded location");
      checks[0]++;
      test.runOnMainSync(
          () -> {
            app.mutate(q -> q.resume(SystemClock.elapsedRealtime()));
            activity.startForegroundService(new Intent(activity, WorkoutService.class));
          });
      Thread.sleep(1000);
      send(manager, 39.1, 116);
      Thread.sleep(3400);
      send(manager, 39.1001, 116);
      Thread.sleep(3400);
      send(manager, 39.1002, 116);
      Thread.sleep(700);
      List<RouteTrack.Point> points = app.store.routePoints(id[0]);
      if (points.size() < 3
          || app.active.route.meters != before
          || points.get(points.size() - 1).segment == points.get(0).segment)
        throw new AssertionError("resume connected missing route segment");
      checks[0]++;
      test.runOnMainSync(
          () -> {
            app.finish();
            activity.stopService(new Intent(activity, WorkoutService.class));
          });
      if (app.store.latestWorkout().route.meters != before)
        throw new AssertionError("ride distance not retained in history");
      checks[0]++;
      if (app.store.latestWorkout().count() != 0
          || app.store.latestWorkout().item().logs.isEmpty()
          || app.store.latestWorkout().item().logs.get(0).partial)
        throw new AssertionError("finished ride marked incomplete");
      checks[0]++;
      app.store.removeWorkout(id[0]);
      if (!app.store.routePoints(id[0]).isEmpty())
        throw new AssertionError("route points survived record deletion");
      checks[0]++;
    } finally {
      test.runOnMainSync(
          () -> {
            if (app.active != null) {
              app.active.running = false;
              app.active = null;
            }
            activity.stopService(new Intent(activity, WorkoutService.class));
          });
      try {
        manager.removeTestProvider("gps");
      } catch (Exception ignored) {
      }
      shell(test, "appops set " + activity.getPackageName() + " android:mock_location default");
      test.runOnMainSync(
          () -> {
            try {
              java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page");
              page.setAccessible(true);
              page.setInt(activity, 0);
              java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render");
              render.setAccessible(true);
              render.invoke(activity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
    }
    try (FileWriter out = new FileWriter(new File(activity.getFilesDir(), "route-audit.txt"))) {
      out.write(
          "PASS "
              + checks[0]
              + " GNSS pipeline assertions using injected test-provider positions; not a physical"
              + " outdoor ride\n");
    }
  }

  private static void send(LocationManager manager, double latitude, double longitude) {
    Location location = new Location("gps");
    location.setLatitude(latitude);
    location.setLongitude(longitude);
    location.setAccuracy(4);
    location.setAltitude(0);
    location.setSpeed(4);
    location.setSpeedAccuracyMetersPerSecond(.1f);
    location.setBearing(0);
    location.setTime(System.currentTimeMillis());
    location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    manager.setTestProviderLocation("gps", location);
  }
}
