package com.lianyoudu.app;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.units.Energy;
import android.os.*;
import java.time.*;
import java.util.*;

/**
 * Read-only aggregate access. Android deduplicates steps; no raw-record sum, inferred steps, or
 * writes.
 */
final class HealthData {
  static final String STEPS = "android.permission.health.READ_STEPS";
  static final String ACTIVE = "android.permission.health.READ_ACTIVE_CALORIES_BURNED";
  static final String TOTAL = "android.permission.health.READ_TOTAL_CALORIES_BURNED";

  static final class Snapshot {
    Long steps;
    Double active, total;
    Instant at;
    LocalDate date;
    String message = "未连接健康数据";
    String sources = "";
  }

  Snapshot value = new Snapshot();
  boolean loading;

  boolean permitted(Activity a, String permission) {
    return a.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  void request(Activity a) {
    if (Build.VERSION.SDK_INT >= 34) a.requestPermissions(new String[] {STEPS, ACTIVE, TOTAL}, 104);
    else
      android.widget.Toast.makeText(a, "此系统版本暂不支持连接健康数据", android.widget.Toast.LENGTH_LONG).show();
  }

  void manage(Activity a) {
    if (Build.VERSION.SDK_INT < 34) {
      request(a);
      return;
    }
    try {
      a.startActivity(
          new android.content.Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
              .putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, a.getPackageName()));
    } catch (Exception e) {
      android.widget.Toast.makeText(a, "请在系统设置中管理健康数据权限", android.widget.Toast.LENGTH_LONG).show();
    }
  }

  void refresh(Activity a, Runnable done) {
    if (loading) return;
    if (!permitted(a, STEPS) && !permitted(a, ACTIVE) && !permitted(a, TOTAL)) {
      value = new Snapshot();
      value.date = LocalDate.now();
      done.run();
      return;
    }
    value = new Snapshot();
    value.date = LocalDate.now();
    if (Build.VERSION.SDK_INT < 34) {
      value.message = "此系统版本暂不支持连接";
      done.run();
      return;
    }
    HealthConnectManager manager = a.getSystemService(HealthConnectManager.class);
    if (manager == null) {
      value.message = "设备未提供健康数据服务";
      done.run();
      return;
    }
    Snapshot snapshot = value;
    loading = true;
    snapshot.message = "正在读取";
    Instant end = Instant.now(),
        start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    TimeInstantRangeFilter range =
        new TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build();
    List<String> errors = new ArrayList<>();
    Set<String> origins = new TreeSet<>();
    int[] pending = {3};
    boolean[] settled = {false};
    new Handler(Looper.getMainLooper())
        .postDelayed(
            () -> {
              if (settled[0]) return;
              settled[0] = true;
              loading = false;
              value = new Snapshot();
              value.date = LocalDate.now();
              value.message = "读取超时，请重试";
              done.run();
            },
            15000);
    Runnable finish =
        () -> {
          if (settled[0]) return;
          if (--pending[0] == 0) {
            settled[0] = true;
            loading = false;
            snapshot.at = end;
            snapshot.sources = String.join("、", origins);
            snapshot.message =
                !errors.isEmpty()
                    ? String.join("；", errors)
                    : (snapshot.steps == null && snapshot.active == null && snapshot.total == null
                        ? "今天暂无共享数据"
                        : "已读取今天的数据");
            done.run();
          }
        };
    if (permitted(a, STEPS)) {
      var type = StepsRecord.STEPS_COUNT_TOTAL;
      try {
        manager.aggregate(
            new AggregateRecordsRequest.Builder<Long>(range).addAggregationType(type).build(),
            a.getMainExecutor(),
            new OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException>() {
              public void onResult(AggregateRecordsResponse<Long> r) {
                if (!r.getDataOrigins(type).isEmpty()) {
                  snapshot.steps = r.get(type);
                  for (var o : r.getDataOrigins(type)) origins.add(o.getPackageName());
                }
                finish.run();
              }

              public void onError(HealthConnectException e) {
                errors.add("步数读取失败");
                finish.run();
              }
            });
      } catch (Exception e) {
        errors.add("步数暂不可用");
        finish.run();
      }
    } else {
      errors.add("步数未授权");
      finish.run();
    }
    for (boolean total : new boolean[] {false, true}) {
      String permission = total ? TOTAL : ACTIVE;
      var type =
          total
              ? TotalCaloriesBurnedRecord.ENERGY_TOTAL
              : ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL;
      if (!permitted(a, permission)) {
        finish.run();
        continue;
      }
      try {
        manager.aggregate(
            new AggregateRecordsRequest.Builder<Energy>(range).addAggregationType(type).build(),
            a.getMainExecutor(),
            new OutcomeReceiver<AggregateRecordsResponse<Energy>, HealthConnectException>() {
              public void onResult(AggregateRecordsResponse<Energy> r) {
                Energy energy = r.get(type);
                // The framework may derive totals without supplied records. Do not call those
                // device measurements.
                if (energy != null && !r.getDataOrigins(type).isEmpty()) {
                  double kcal = energy.getInCalories() / 1000.0;
                  if (Double.isFinite(kcal) && kcal >= 0) {
                    if (total) snapshot.total = kcal;
                    else snapshot.active = kcal;
                  }
                  for (var o : r.getDataOrigins(type)) origins.add(o.getPackageName());
                }
                finish.run();
              }

              public void onError(HealthConnectException e) {
                errors.add("热量读取失败");
                finish.run();
              }
            });
      } catch (Exception e) {
        errors.add("热量暂不可用");
        finish.run();
      }
    }
  }
}
