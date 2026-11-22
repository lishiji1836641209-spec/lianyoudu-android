package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class AuditInstrumentation extends Instrumentation {
  private int checks;
  private boolean snapshotOnly;
  private boolean evidenceOnly;
  private boolean mediaOnly;
  private boolean energyOnly;
  private boolean refinementOnly;
  private boolean polishOnly;
  private boolean heartOnly;
  private boolean continuityOnly;
  private boolean frameOnly;
  private boolean reuseOnly;
  private boolean opticsOnly;
  private boolean deviceVisual;
  private boolean designOnly;
  private boolean alignmentOnly;
  private boolean unifiedOnly;
  private boolean catalogOnly;
  private boolean adversarialOnly;
  private boolean learningOnly;
  private Activity currentActivity;
  private final List<Long> timings = new ArrayList<>();

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    snapshotOnly = arguments != null && arguments.getString("mode", "").equals("upgradeSnapshot");
    evidenceOnly = arguments != null && arguments.getString("mode", "").equals("exportEvidence");
    heartOnly = arguments != null && arguments.getString("mode", "").equals("heart");
    energyOnly = arguments != null && arguments.getString("mode", "").equals("energy");
    mediaOnly = arguments != null && arguments.getString("mode", "").equals("media");
    continuityOnly = arguments != null && arguments.getString("mode", "").equals("continuity");
    reuseOnly = arguments != null && arguments.getString("mode", "").equals("reuse");
    frameOnly = arguments != null && arguments.getString("mode", "").equals("frames");
    opticsOnly = arguments != null && arguments.getString("mode", "").equals("optics");
    deviceVisual = arguments != null && arguments.getString("mode", "").equals("deviceVisual");
    designOnly = arguments != null && arguments.getString("mode", "").equals("design");
    alignmentOnly = arguments != null && arguments.getString("mode", "").equals("alignment");
    unifiedOnly = arguments != null && arguments.getString("mode", "").equals("unified");
    catalogOnly = arguments != null && arguments.getString("mode", "").equals("catalog");
    adversarialOnly = arguments != null && arguments.getString("mode", "").equals("adversarial");
    learningOnly = arguments != null && arguments.getString("mode", "").equals("learning");
    refinementOnly = arguments != null && arguments.getString("mode", "").equals("refinement");
    polishOnly = arguments != null && arguments.getString("mode", "").equals("polish");
    start();
  }

  private void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      waitForIdleSync();
      FitnessApp app = (FitnessApp) getTargetContext().getApplicationContext();
      if (snapshotOnly) {
        // Explicit read-only export: no fixture, page navigation or active-session operation.
        JSONObject snapshot = app.store.exportData();
        // Older installed versions converted SQLite doubles through a lossy string formatter.
        // Read raw coordinates without mutating the DB so upgrade evidence retains full precision.
        JSONArray routes = new JSONArray();
        try (android.database.Cursor cursor =
            app.store.getReadableDatabase().rawQuery("SELECT * FROM route_points", null)) {
          while (cursor.moveToNext()) {
            JSONObject row = new JSONObject();
            for (int i = 0; i < cursor.getColumnCount(); i++)
              row.put(
                  cursor.getColumnName(i),
                  switch (cursor.getType(i)) {
                    case android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL;
                    case android.database.Cursor.FIELD_TYPE_FLOAT ->
                        Double.toString(cursor.getDouble(i));
                    case android.database.Cursor.FIELD_TYPE_INTEGER ->
                        Long.toString(cursor.getLong(i));
                    default -> cursor.getString(i);
                  });
            routes.put(row);
          }
        }
        snapshot.put("route_points", routes);
        result.putString("stream", "SNAPSHOT " + snapshot.toString() + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      boolean emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk");
      boolean isolated =
          getTargetContext().getPackageName().matches("com\\.lianyoudu\\.app\\.(audit|qa)");
      if (evidenceOnly) {
        if (!isolated) throw new IllegalStateException("Evidence export requires isolated app");
        File archive = new File(app.getExternalFilesDir(null), "audit-evidence.zip");
        try (java.util.zip.ZipOutputStream zip =
            new java.util.zip.ZipOutputStream(new FileOutputStream(archive))) {
          File[] files = app.getFilesDir().listFiles();
          if (files != null)
            for (File file : files) {
              if (!file.isFile() || !file.getName().matches(".+\\.(png|json|txt)")) continue;
              zip.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
              java.nio.file.Files.copy(file.toPath(), zip);
              zip.closeEntry();
            }
        }
        result.putString("stream", "EXPORTED " + archive.getAbsolutePath() + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (!emulator && !getTargetContext().getPackageName().equals("com.lianyoudu.app.qa"))
        throw new IllegalStateException(
            "Fixture suite requires emulator or isolated audit package");
      Store db = app.store;
      if (reuseOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int passed = DeviceReuseAudit.run(this, currentActivity, app);
        result.putString("stream", "PASS " + passed + " rollback-protected reuse assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (polishOnly) {
        if (!emulator)
          throw new IllegalStateException("Polish fixtures require emulator; protect real records");
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int passed = new PolishAudit(this, currentActivity, app).run();
        result.putString("stream", "PASS " + passed + " polish assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (refinementOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        refinementFlows(app, db);
        result.putString("stream", "PASS " + checks + " refinement assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (heartOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Domain.Session sample = new Domain.Session("all", List.of());
        sample.resume(0);
        sample.heart(110, 1000);
        sample.heart(140, 6000);
        sample.disconnect();
        sample.heart(120, 12000);
        sample.finish(20000);
        java.lang.reflect.Field selected = MainActivity.class.getDeclaredField("selected");
        selected.setAccessible(true);
        runOnMainSync(
            () -> {
              try {
                selected.set(currentActivity, sample);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        auditPage(7, app);
        check(hasText("采样平均 · 最低 110 · 最高 140"), "heart statistics displayed with chart");
        check(hasText("曲线按运动时间显示，断线处留空。"), "chart meaning remains explicit");
        screenshot("v14-heart-chart");
        Domain.Session legacy = new Domain.Session("all", List.of());
        legacy.elapsed = 20000;
        legacy.heartValid = 10000;
        runOnMainSync(
            () -> {
              try {
                selected.set(currentActivity, legacy);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
        auditPage(7, app);
        check(hasText("这条旧记录仅保存了有效连接时长，没有逐点心率数据。"), "legacy heart view does not invent a curve");
        screenshot("v14-heart-legacy");
        result.putString("stream", "PASS " + checks + " heart chart and legacy UI assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (energyOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        energyFlows(app, db);
        result.putString("stream", "PASS " + checks + " energy and preparation assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (mediaOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        mediaFlows(app, db);
        result.putString("stream", "PASS " + checks + " camera result lifecycle assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (continuityOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ContinuityAudit.run(this, currentActivity, app);
        result.putString("stream", "PASS real service controls and short screen-off continuity\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (frameOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        FrameAudit.run(this, currentActivity, app);
        result.putString(
            "stream",
            "DONE frame collection; inspect frame-audit.json, not a device-performance pass\n");
        finish(Activity.RESULT_OK, result);
        return;
      }

      if (learningOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        learningFlows(app, db);
        result.putString("stream", "PASS " + checks + " teaching native assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (adversarialOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        adversarialFlows(app, db);
        result.putString("stream", "PASS " + checks + " adversarial native assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (catalogOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        catalogFlows(app, db);
        result.putString("stream", "PASS " + checks + " expanded catalog native assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (unifiedOnly) {
        currentActivity =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        unifiedFlows(app, db);
        result.putString("stream", "PASS " + checks + " unified native assertions\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (alignmentOnly) {
        Activity a =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        new DesignAudit(this, a, app).alignmentOnly();
        result.putString("stream", "PASS scrolled glass alignment\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (designOnly) {
        Activity a =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        new DesignAudit(this, a, app).run();
        result.putString("stream", "PASS design-only\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (deviceVisual) {
        DeviceVisualAudit.run(this, app);
        result.putString("stream", "DONE isolated device presentation probes\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (opticsOnly) {
        Activity a =
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        new DesignAudit(this, a, app).optics();
        result.putString("stream", "PASS optics\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      // Use the target's fresh test installation; the host test script clears app data first.
      db.weight(null, "2026-01-01", 70, false);
      boolean conflict = false;
      try {
        db.weight(null, "2026-01-01", 69, false);
      } catch (IllegalArgumentException e) {
        conflict = true;
      }
      check(
          conflict && db.weights().get(0).kg == 70, "weight conflict must preserve previous value");
      db.weight("2026-01-01", "2026-01-02", 69.5, true);
      check(
          db.weights().size() == 1 && db.weights().get(0).date.equals("2026-01-02"),
          "date edit atomic");
      JSONObject meal =
          new JSONObject()
              .put("id", "meal-a")
              .put("date", "2026-01-02")
              .put("slot", "午餐")
              .put(
                  "foods",
                  new JSONArray().put(new JSONObject().put("name", "米饭").put("grams", 100)));
      db.meal(meal, List.of(), false);
      JSONObject second = new JSONObject(meal.toString()).put("id", "meal-b");
      conflict = false;
      try {
        db.meal(second, List.of(), false);
      } catch (IllegalArgumentException e) {
        conflict = true;
      }
      check(conflict && db.meals().size() == 1, "meal conflict transaction");
      db.meal(second, List.of(), true);
      check(
          db.meals().size() == 1 && db.meals().get(0).getString("id").equals("meal-b"),
          "meal replace");
      Domain.Exercise ex = new Domain.Exercise("squat", "徒手深蹲", "", false, "");
      ex.sets = 1;
      db.plan("gym", List.of(ex));
      Domain.Session q = new Domain.Session("gym", List.of(ex));
      q.resume(0);
      q.tick(2000);
      q.record(10, 0, 2000);
      db.saveSession(q, true);
      Domain.Session restored = db.active();
      check(
          restored != null && !restored.running && restored.count() == 1,
          "cold restore pauses and retains logs");
      JSONObject legacyActive = Store.session(q);
      legacyActive.remove("ended");
      android.content.ContentValues legacyRow = new android.content.ContentValues();
      legacyRow.put("data", legacyActive.toString());
      db.getWritableDatabase().update("workouts", legacyRow, "id=?", new String[] {q.id});
      check(db.active().endedWall == 0, "legacy active training has no end time");
      legacyActive.put("ended", q.startedWall + q.elapsed);
      legacyRow.put("data", legacyActive.toString());
      db.getWritableDatabase().update("workouts", legacyRow, "id=?", new String[] {q.id});
      check(db.active().endedWall == 0, "active row repairs previously inferred end time");
      q.endedWall = q.startedWall + q.elapsed;
      db.saveSession(q, true);
      try (android.database.Cursor row =
          db.getReadableDatabase()
              .rawQuery("SELECT data FROM workouts WHERE id=?", new String[] {q.id})) {
        row.moveToFirst();
        check(
            new JSONObject(row.getString(0)).getLong("ended") == 0,
            "active persistence never writes a completion timestamp");
      }
      db.saveSession(q, false);
      check(db.workouts().get(0).count() == 1, "history commits");
      check(db.workouts().get(0).endedWall == q.endedWall, "completed end time retained");
      legacyActive.remove("ended");
      legacyRow.put("data", legacyActive.toString());
      db.getWritableDatabase().update("workouts", legacyRow, "id=?", new String[] {q.id});
      check(
          db.workouts().get(0).endedWall == q.startedWall + q.elapsed,
          "legacy completed training keeps estimated end time");
      Bitmap share = ShareImage.draw(q, true, new Store.Weight("2026-01-02", 69.5));
      check(share.getWidth() == 1080 && share.getHeight() > 600, "share bitmap");
      share.recycle();
      byte[] invalid = new byte[] {1, 5};
      check(Domain.heartRate(invalid) == 0, "truncated BLE");
      // Import is bounded, normalizes pixels, rejects invalid input, and deduplicates pending
      // photos.
      File photo = new File(getTargetContext().getCacheDir(), "test-photo.png");
      Bitmap pixels = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
      pixels.eraseColor(android.graphics.Color.GREEN);
      try (FileOutputStream out = new FileOutputStream(photo)) {
        pixels.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      pixels.recycle();
      check(
          PhotoFiles.importPhoto(getTargetContext(), db, android.net.Uri.fromFile(photo)),
          "photo imported");
      check(
          !PhotoFiles.importPhoto(getTargetContext(), db, android.net.Uri.fromFile(photo)),
          "duplicate photo skipped");
      File bad = new File(getTargetContext().getCacheDir(), "invalid.jpg");
      try (FileOutputStream out = new FileOutputStream(bad)) {
        out.write(new byte[] {1, 2, 3});
      }
      boolean rejected = false;
      try {
        PhotoFiles.importPhoto(getTargetContext(), db, android.net.Uri.fromFile(bad));
      } catch (IOException e) {
        rejected = true;
      }
      check(rejected, "invalid photo rejected");
      android.net.Uri gallery =
          PhotoFiles.saveGallery(getTargetContext(), ShareImage.draw(q, false, null));
      check(gallery != null, "share saved to gallery");
      getTargetContext().getContentResolver().delete(gallery, null, null);
      FoodCatalog catalog = new FoodCatalog(getTargetContext());
      check(catalog.foods.size() == 119, "offline source coverage");
      JSONArray rice = catalog.parse("米饭，150");
      check(rice.getJSONObject(0).getDouble("kcal") == 195, "rice energy per edible grams");
      check(rice.getJSONObject(0).getString("foodId").equals("169757"), "provenance snapshot");
      JSONArray mixed = catalog.parse("米饭，100\n家常炒菜，120");
      check(FoodCatalog.summary(mixed).contains("1项未计入"), "unknown food is not zero calories");
      check(
          catalog.parse("包装食品，50，400").getJSONObject(0).getDouble("kcal") == 200,
          "nutrition label entry");
      boolean badPortion = false;
      try {
        catalog.parse("米饭，NaN");
      } catch (IllegalArgumentException e) {
        badPortion = true;
      }
      check(badPortion, "invalid food amount");
      JSONObject inconsistent =
          new JSONObject()
              .put("id", "bad-energy")
              .put("date", "2026-01-01")
              .put("slot", "早餐")
              .put(
                  "foods",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("name", "米饭")
                              .put("grams", 100)
                              .put("kcal100", 130)
                              .put("kcal", 999)));
      boolean energyRejected = false;
      try {
        db.meal(inconsistent, List.of(), false);
      } catch (IllegalArgumentException e) {
        energyRejected = true;
      }
      check(energyRejected, "inconsistent energy is rejected before write");
      runOnMainSync(
          () -> {
            Domain.Session rollback = new Domain.Session("gym", List.of(ex));
            rollback.resume(100);
            rollback.heart(120, 100);
            rollback.tick(1100);
            app.active = rollback;
            try {
              app.mutate(s -> s.record(0, 0, 1600));
            } catch (IllegalArgumentException expected) {
            }
            app.active.tick(2100);
            check(
                app.active.elapsed == 2000 && app.active.heart == 120,
                "invalid mutation preserves live clock and heart");
            app.active = null;
          });
      Domain.Session empty = new Domain.Session("gym", Collections.emptyList());
      db.saveSession(empty, true);
      check(db.active() != null && db.active().items.isEmpty(), "empty session cold restore");
      db.removeWorkout(q.id);
      check(db.latestWorkout() == null, "delete last history resets latest");
      android.database.sqlite.SQLiteDatabase migration =
          android.database.sqlite.SQLiteDatabase.create(null);
      try {
        migration.execSQL("CREATE TABLE workouts(id TEXT PRIMARY KEY, data TEXT)");
        migration.execSQL("INSERT INTO workouts VALUES('old-record','preserve-me')");
        db.onUpgrade(migration, 1, 2);
        try (android.database.Cursor old =
            migration.rawQuery("SELECT data FROM workouts WHERE id='old-record'", null)) {
          check(
              old.moveToFirst() && old.getString(0).equals("preserve-me"),
              "v1 migration preserves workout records");
        }
        try (android.database.Cursor route =
            migration.rawQuery("SELECT count(*) FROM route_points", null)) {
          check(route.moveToFirst() && route.getInt(0) == 0, "v1 migration adds empty route table");
        }
      } finally {
        migration.close();
      }
      Domain.Session notificationSession = new Domain.Session("home", List.of(ex));
      notificationSession.elapsed = 123000;
      notificationSession.running = true;
      android.app.Notification notification =
          WorkoutNotifications.create(getTargetContext(), notificationSession);
      check(
          notification.extras.getString(android.app.Notification.EXTRA_TITLE).contains("徒手深蹲"),
          "notification scenario label");
      check(
          notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER),
          "notification uses system live timer");
      notificationSession.running = false;
      check(
          !WorkoutNotifications.create(getTargetContext(), notificationSession)
              .extras
              .getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER),
          "paused notification freezes timer");
      // Clear test fixture before end-to-end interaction.
      db.getWritableDatabase().execSQL("DELETE FROM workouts");
      db.getWritableDatabase().execSQL("DELETE FROM meals");
      db.getWritableDatabase().execSQL("DELETE FROM weights");
      db.getWritableDatabase().execSQL("DELETE FROM plans");
      Intent intent =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      Activity activity = startActivitySync(intent);
      currentActivity = activity;
      waitForIdleSync();
      Thread.sleep(500);
      screenshot("home");
      checks += CapsuleAudit.run(activity, app);
      new DesignAudit(this, activity, app).run();
      // Mock GPS is a device-wide provider: never inject it on a personal phone.
      if (emulator) RouteAudit.run(this, activity, app);

      press("摘要");
      String oldPins = db.setting("healthPins", "activity,weight,food");
      press("编辑");
      press("上移体重");
      press("取消");
      check(
          db.setting("healthPins", "activity,weight,food").equals(oldPins),
          "pin cancellation leaves ordering unchanged");
      press("编辑");
      press("上移体重");
      press("完成");
      check(db.setting("healthPins", "").startsWith("weight,"), "pin ordering commits once");
      press("训练");
      press("开始运动");
      press("开始计时");
      check(app.active != null && app.active.items.isEmpty(), "free training needs no plan");
      runOnMainSync(
          () -> app.mutate(activeSession -> activeSession.pause(SystemClock.elapsedRealtime())));
      waitForIdleSync();
      check(hasText("继续"), "notification pause refreshes foreground action");
      runOnMainSync(
          () -> app.mutate(activeSession -> activeSession.resume(SystemClock.elapsedRealtime())));
      waitForIdleSync();
      check(hasText("暂停"), "notification resume refreshes foreground action");
      press("摘要");
      long overviewElapsed = app.active.elapsed;
      Thread.sleep(1400);
      check(
          app.active.elapsed > overviewElapsed && hasText(Domain.clock(app.active.elapsed)),
          "today page clock follows background workout without navigation");
      runOnMainSync(
          () -> app.mutate(activeSession -> activeSession.pause(SystemClock.elapsedRealtime())));
      waitForIdleSync();
      check(hasText("训练已暂停"), "today page follows capsule pause state");
      runOnMainSync(
          () -> app.mutate(activeSession -> activeSession.resume(SystemClock.elapsedRealtime())));
      press("返回本次训练");
      press("连接心率设备");
      java.lang.reflect.Field rootField = MainActivity.class.getDeclaredField("body");
      rootField.setAccessible(true);
      Object originalBody = rootField.get(activity);
      runOnMainSync(
          () -> {
            for (int i = 0; i < 100; i++) {
              app.ble.status = "连接状态 " + i;
              app.changed();
            }
          });
      check(rootField.get(activity) == originalBody, "100 BLE updates retain page tree");
      check(hasText("连接状态 99"), "BLE status refreshes in place");
      screenshot("heart-stable");
      press("‹ 返回");
      press("暂停");
      press("添加运动");
      set("搜索运动或器材", "深蹲");
      screenshot("exercise-menu");
      press("调整徒手深蹲");
      set("组数", "1");
      screenshot("exercise-dialog");
      press("选用此设置");
      press("添加所选运动");
      check(
          app.active.items.size() == 1 && db.plan("all").isEmpty(),
          "ad hoc item independent from plan");
      check(!app.active.running, "adding exercise preserves explicit pause");
      press("继续");
      press("添加运动");
      set("搜索运动或器材", "俯卧撑");
      press("俯卧撑");
      set("搜索运动或器材", "深蹲");
      java.lang.reflect.Field pickerField = MainActivity.class.getDeclaredField("exercisePicker");
      pickerField.setAccessible(true);
      ExercisePicker picker = (ExercisePicker) pickerField.get(activity);
      boolean[] selectedState = {false};
      runOnMainSync(
          () ->
              selectedState[0] =
                  picker.chosen.size() == 1 && picker.add.getText().toString().equals("添加（1）"));
      check(selectedState[0], "selection and button count survive search changes");
      screenshot("exercise-selection-retained");
      check(hasText("已添加"), "existing exercise cannot be added twice");
      press("取消");
      check(app.active.items.size() == 1, "cancelling selection leaves training unchanged");
      set("徒手深蹲第1组次数", "8");
      rotateActivity();
      check(
          hasText("8") && app.active.count() == 0,
          "inline draft survives activity recreation without logging");
      press("完成徒手深蹲第1组");
      press("暂停");
      press("为徒手深蹲增加一组");
      check(app.active.item().exercise.sets == 2, "UI extra set");
      check(!app.active.running, "extra set preserves paused state");
      press("结束");
      rotateActivity();
      check(
          hasText("结束本次训练？") && !app.active.running, "finish dialog restored with paused session");
      press("返回训练");
      check(!app.active.running, "cancel finish preserves prior pause");
      press("继续");
      press("结束");
      check(!app.active.running, "finish confirmation pauses workout");
      press("继续训练");
      check(app.active.running, "cancel finish resumes workout");
      press("结束");
      screenshot("finish-confirmation");
      press("保存训练");
      press("删除记录");
      press("删除");
      check(db.latestWorkout() == null, "UI delete clears latest");
      press("摘要");
      check(!hasText("最近保存的运动"), "home clears deleted workout");
      press("训练");
      press("我的计划");
      press("添加运动");
      set("搜索运动或器材", "深蹲");
      press("调整徒手深蹲");
      set("组数", "1");
      press("选用此设置");
      press("添加所选运动");
      check(db.plan("all").size() == 1, "UI plan saved");
      press("开始训练");
      press("开始计时");
      Thread.sleep(1500);
      set("徒手深蹲第1组次数", "9");
      press("完成徒手深蹲第1组");
      check(app.active != null && app.active.count() == 1, "UI group log");
      press("结束");
      screenshot("finish-confirmation");
      press("保存训练");
      check(app.active == null && db.workouts().size() == 1, "UI finish");
      screenshot("summary");
      press("浏览");
      press("身体测量");
      press("添加数据");
      set("体重（千克）", "68.8");
      press("保存");
      check(db.weights().size() == 1, "UI weight saved");
      screenshot("weight");
      press("浏览");
      press("营养");
      press("记一餐");
      press("添加食物");
      set("搜索食物", "米饭");
      check(!hasText("大米（生）"), "food search filters results");
      screenshot("food-catalog");
      press("选择米饭（熟、无盐）");
      set("食用克数", "150");
      press("添加");
      check(hasText("195"), "meal live calorie preview");
      press("手动填写 / 包装食品");
      set("食物名称，食用量（克）", "米饭，100\n鸡肉，80");
      press("保存");
      check(db.meals().size() == 1, "UI meal saved");
      screenshot("food");
      press("浏览");
      press("身体测量");
      press("添加数据");
      set("体重（千克）", "67.2");
      rotateActivity();
      check(hasText("67.2"), "weight draft survives recreation");
      press("取消");
      extendedFlows(app, db);
      unifiedFlows(app, db);
      for (int i = 0; i < 60; i++) {
        String tab = new String[] {"摘要", "训练", "能量", "浏览"}[i % 4];
        long start = SystemClock.elapsedRealtime();
        press(tab);
        timings.add(SystemClock.elapsedRealtime() - start);
      }
      check(app.observerCountForAudit() == 1, "no observer leak");
      check(!GlassEffects.shaderFallback, "AGSL glass compiled successfully");
      press("显示与数据");
      press("主题：跟随系统");
      press("深色");
      check(db.setting("theme", "").equals("dark"), "dark theme");
      screenshot("dark-settings");
      Bitmap darkShot = getUiAutomation().takeScreenshot();
      check(
          android.graphics.Color.luminance(darkShot.getPixel(darkShot.getWidth() / 2, 20)) < .3,
          "dark status bar background");
      darkShot.recycle();
      press("主题：深色");
      press("跟随系统");
      press("液态玻璃底栏");
      check(db.setting("solid", "false").equals("true"), "solid toggle");
      press("液态玻璃底栏");
      check(db.setting("solid", "true").equals("false"), "glass restored");
      runOnMainSync(
          () -> {
            app.start("gym", db.plan("all"));
            activity.startForegroundService(new Intent(activity, WorkoutService.class));
          });
      Thread.sleep(1200);
      long before = app.active.elapsed;
      getUiAutomation()
          .performGlobalAction(
              android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
      Thread.sleep(2500);
      check(app.active.elapsed >= before + 1500, "background foreground-service timer");
      if (emulator) {
        long lockStart = app.active.elapsed;
        getUiAutomation()
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        Thread.sleep(2500);
        check(app.active.elapsed >= lockStart + 1500, "screen-off timer");
        getUiAutomation().executeShellCommand("input keyevent KEYCODE_WAKEUP").close();
      }
      runOnMainSync(() -> app.mutate(s -> s.pause(SystemClock.elapsedRealtime())));
      JSONObject report =
          new JSONObject()
              .put("passed", checks)
              .put("navigationSamples", new JSONArray(timings))
              .put(
                  "note",
                  emulator
                      ? "Emulator: simulated GPS and screen-off timing checked."
                      : "Isolated phone installation. Real outdoor route, lock-screen power"
                          + " management and wearable connection remain unverified.");
      try (FileOutputStream out =
          new FileOutputStream(new File(getTargetContext().getFilesDir(), "audit.json"))) {
        out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      result.putString(
          "stream", "PASS " + checks + " native assertions; 60 navigation repetitions\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      try {
        screenshot("audit-failure");
      } catch (Exception ignored) {
      }
      result.putString("stream", "FAIL " + e + "\n" + android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void rotateActivity() throws Exception {
    ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
    runOnMainSync(() -> currentActivity.recreate());
    Activity next = waitForMonitorWithTimeout(monitor, 5000);
    removeMonitor(monitor);
    check(next != null, "activity recreation completed");
    currentActivity = next;
    waitForIdleSync();
    Thread.sleep(500);
  }

  private void refinementFlows(FitnessApp app, Store db) throws Exception {
    check(app.active == null, "refinement begins without running workout");
    check(
        BleHeart.cleanName("HONOR Watch 6 Pro-0HONOR Watch 6 Pro-07E")
            .equals("HONOR Watch 6 Pro-07E"),
        "duplicated Bluetooth name is repaired");
    check(
        BleHeart.cleanName("WATCH HR-07E").equals("WATCH HR-07E"),
        "normal Bluetooth name is preserved");
    db.setSetting("heart.device.address", "00:11:22:33:44:55");
    db.setSetting("heart.device.name", "WATCH HR-07E");
    auditPage(6, app);
    check(hasText("WATCH HR-07E"), "previously connected device is discoverable");
    android.bluetooth.BluetoothAdapter adapter =
        app.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
    check(adapter != null, "emulator provides a Bluetooth adapter for the device list");
    runOnMainSync(
        () -> {
          app.ble.devices.clear();
          app.ble.devices.put("00:11:22:33:44:55", adapter.getRemoteDevice("00:11:22:33:44:55"));
          app.ble.devices.put("00:11:22:33:44:66", adapter.getRemoteDevice("00:11:22:33:44:66"));
          app.changed();
        });
    check(
        ((LinearLayout) activityField("deviceRows")).getChildCount() == 1,
        "remembered device is not duplicated but a different nearby device stays visible");
    runOnMainSync(
        () -> {
          app.ble.devices.remove("00:11:22:33:44:66");
          app.ble.status = "请选择设备";
          app.changed();
        });
    check(
        ((TextView) activityField("deviceStatus")).getText().toString().contains("重新连接"),
        "a remembered-only scan directs the user to the visible reconnect action");
    runOnMainSync(
        () -> {
          app.ble.devices.put("00:11:22:33:44:66", adapter.getRemoteDevice("00:11:22:33:44:66"));
          app.changed();
        });
    check(
        ((TextView) activityField("deviceStatus")).getText().toString().equals("请选择设备")
            && ((LinearLayout) activityField("deviceRows")).getChildCount() == 1,
        "other nearby devices remain selectable after a remembered-only scan");
    runOnMainSync(
        () -> {
          app.ble.devices.clear();
          app.ble.status = "未连接";
          app.changed();
        });
    List<AccessibilityNodeInfo> memoryNodes = new ArrayList<>();
    collect(appWindow(), memoryNodes);
    check(
        memoryNodes.stream()
            .anyMatch(
                n ->
                    "WATCH HR-07E"
                        .contentEquals(
                            n.getContentDescription() == null ? "" : n.getContentDescription())),
        "remembered device accessibility name updates with label");
    press("忘记设备");
    press("确定");
    check(
        !app.ble.hasRemembered() && hasText("连接心率设备"),
        "forget cancels connection and clears remembered device");
    db.setSetting("customExercises", "[]");
    db.setSetting("exercise.favorites", "");
    db.plan("all", List.of());
    db.setSetting("workoutPreparation", "[]");
    auditPage(0, app);
    bottom();
    check(!hasText("心率设备"), "summary has no device-management card");
    auditPage(1, app);
    check(
        hasText("我的收藏") && hasText("选择训练模板"),
        "collection and templates discoverable on training home");
    press("选择训练模板");
    press("器械核心");
    check(hasText("支撑提腿"), "core template includes supported knee raise");
    screenshot("v15-core-template");
    press("取消");
    auditPage(16, app);
    set("搜索运动", "支撑提腿");
    press("收藏支撑提腿");
    check(
        ExerciseCatalog.favorites(db.setting("exercise.favorites", ""))
            .contains("supported_leg_raise"),
        "favorite saved directly from library");
    press("查看支撑提腿指引");
    screenshot("v15-supported-leg-guide");
    press("关闭");
    press("＋ 自定义运动");
    set("运动名称", "验收自定义动作");
    set("组数", "4");
    set("每组次数", "10");
    screenshot("v15-custom-form");
    rotateActivity();
    check(hasText("验收自定义动作"), "custom draft survives recreation");
    press("保存");
    check(
        db.customExercises().size() == 1 && db.customExercises().get(0).sets == 4,
        "custom library entry persists");
    Domain.Exercise custom = db.customExercises().get(0);
    check(
        db.plan("all").isEmpty() && app.active == null,
        "library creation does not start or alter plan");
    press("收藏验收自定义动作");
    check(
        ExerciseCatalog.favorites(db.setting("exercise.favorites", "")).contains(custom.id),
        "custom favorite ID retained");
    press("查看验收自定义动作指引");
    press("选用运动");
    check(app.active == null && hasText("开始计时"), "custom selection waits for explicit start");
    press("开始计时");
    check(
        app.active.items.size() == 1 && app.active.item().exercise.id.equals(custom.id),
        "custom workout records selected identity");
    BleHeart silenceProbe = new BleHeart(app);
    java.lang.reflect.Field readyField = BleHeart.class.getDeclaredField("ready");
    java.lang.reflect.Field subscribedField = BleHeart.class.getDeclaredField("subscribedAt");
    java.lang.reflect.Field attemptsField = BleHeart.class.getDeclaredField("silentReconnects");
    readyField.setAccessible(true);
    subscribedField.setAccessible(true);
    attemptsField.setAccessible(true);
    runOnMainSync(
        () -> {
          try {
            readyField.setBoolean(silenceProbe, true);
            subscribedField.setLong(silenceProbe, 1000L);
            attemptsField.setInt(silenceProbe, 3);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    runOnMainSync(() -> silenceProbe.checkSilent(45_999L));
    check(silenceProbe.status.equals("未连接"), "silent connection is not declared stale early");
    runOnMainSync(() -> silenceProbe.checkSilent(46_000L));
    check(
        silenceProbe.status.contains("暂无心率"),
        "bounded silent retry exhaustion offers actionable status");
    java.lang.reflect.Field readingField = BleHeart.class.getDeclaredField("readingAt");
    java.lang.reflect.Method clearStatus = BleHeart.class.getDeclaredMethod("clearStaleDisplay");
    readingField.setAccessible(true);
    clearStatus.setAccessible(true);
    runOnMainSync(
        () -> {
          try {
            readingField.setLong(silenceProbe, SystemClock.elapsedRealtime() - 15_100);
            silenceProbe.status = "已连接 · 88 次/分钟";
            clearStatus.invoke(silenceProbe);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    check(silenceProbe.status.equals("已连接，等待心率"), "stale displayed heart rate is cleared");
    java.lang.reflect.Field retryField = BleHeart.class.getDeclaredField("retryConnection");
    retryField.setAccessible(true);
    boolean[] unexpectedRetry = {false};
    Runnable pendingRetry = () -> unexpectedRetry[0] = true;
    runOnMainSync(
        () -> {
          try {
            retryField.set(silenceProbe, pendingRetry);
            app.main.postDelayed(pendingRetry, 200);
            silenceProbe.close();
            check(
                !app.main.hasCallbacks(pendingRetry),
                "manual disconnect cancels pending retry callback");
            check(retryField.get(silenceProbe) == null, "disconnect clears retry ownership");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    SystemClock.sleep(300);
    check(!unexpectedRetry[0], "cancelled retry cannot reconnect later");

    press("暂停");
    press("添加运动");
    press("自定义");
    set("运动名称", "验收暂停添加");
    press("次数与组数");
    press("距离与时长");
    press("保存");
    check(
        !app.active.running && app.active.items.size() == 2,
        "creating during paused session does not resume");
    check(
        app.active.items.get(1).exercise.distanceBased()
            && !app.active.items.get(1).exercise.usesGps(),
        "custom distance uses manual distance without inventing a GPS route");
    press("填写距离");
    set("距离（公里）", "2.5");
    press("保存");
    check(
        app.active.items.get(1).manualMeters == 2500 && !app.active.running,
        "manual custom distance persists while paused");
    press("结束");
    press("保存训练");
    String sessionId = db.latestWorkout().id;
    db.removeCustomExercise(custom.id);
    check(
        db.workout(sessionId).items.get(0).exercise.name.equals(custom.name),
        "custom deletion preserves historical name and data");
    check(
        !ExerciseCatalog.favorites(db.setting("exercise.favorites", "")).contains(custom.id),
        "deleting custom clears favorite");
    press("删除记录");
    press("删除");
    auditPage(18, app);
    check(
        !hasText("Mifflin") && !hasText("活动系数"), "algorithm rules hidden from main energy screen");
    press("近30天");
    press("趋势指标：步数");
    check(
        (Integer) activityField("energyRange") == 30
            && activityField("energyMetric").equals("steps"),
        "energy range and metric switches work");
    screenshot("v15-energy-steps");
    rotateActivity();
    check(
        (Integer) activityField("energyRange") == 30
            && activityField("energyMetric").equals("steps"),
        "energy chart selection survives recreation");
    press("能量与步数说明");
    check(hasText("消耗如何计算"), "method explanations accessible on demand");
    screenshot("v15-energy-info");
    press("关闭");
    FoodCatalog foods = new FoodCatalog(getTargetContext());
    check(foods.foods.size() == 119, "119 offline foods");
    for (JSONObject food : foods.foods) {
      check(
          foods.imageIndex(food.optString("name")) >= 0,
          "food image mapped " + food.optString("name"));
      check(
          food.optDouble("kcal100", -1) >= 0 && !food.optString("url").isBlank(),
          "food source and energy present " + food.optString("name"));
    }
    for (int i = 1; i <= 4; i++) {
      Bitmap bitmap = GuideImages.decode(getTargetContext(), "foods-v15-" + i + ".png");
      check(bitmap != null, "food atlas decodes");
      if (bitmap != null) bitmap.recycle();
    }
    db.setSetting("customExercises", "[]");
    db.setSetting("exercise.favorites", "");
    db.setSetting("workoutPreparation", "[]");
  }

  private void energyFlows(FitnessApp app, Store db) throws Exception {
    Domain.Session heart = new Domain.Session("all", List.of());
    heart.resume(0);
    heart.heart(110, 1000);
    heart.heart(140, 6000);
    heart.disconnect();
    heart.heart(120, 12000);
    Domain.Session roundTrip = Store.session(Store.session(heart));
    check(
        roundTrip.heartSamples.size() == 3
            && roundTrip.heartSum == 370
            && roundTrip.heartCount == 3,
        "heart samples and statistics survive serialization");
    check(
        roundTrip.heartSamples.get(2).segment() > roundTrip.heartSamples.get(1).segment(),
        "heart disconnection boundary survives serialization");
    JSONObject legacyHeart = Store.session(heart);
    for (String key :
        List.of("heartSamples", "heartSum", "heartCount", "heartMin", "heartMax", "heartSegment"))
      legacyHeart.remove(key);
    Domain.Session legacy = Store.session(legacyHeart);
    check(
        legacy.heartSamples.isEmpty() && legacy.heartValid == heart.heartValid,
        "old coverage stays intact without invented heart samples");
    Domain.Session precise = new Domain.Session("all", List.of());
    double latitude = 31.2345678912345, longitude = 121.9876543210987;
    db.saveSession(precise, true);
    db.routePoint(precise, new RouteTrack.Point(latitude, longitude, 4.123456789, 1000, 1000, 0));
    JSONObject exported = db.exportData().getJSONArray("route_points").getJSONObject(0);
    check(
        Double.parseDouble(exported.getString("lat")) == latitude
            && Double.parseDouble(exported.getString("lon")) == longitude,
        "export preserves GPS double precision");
    db.saveSession(precise, false);
    db.removeWorkout(precise.id);
    check(db.active() == null, "precision fixture cannot leave an unfinished workout");
    check(
        db.setting("healthPins", "").contains("energy"), "energy appears in migrated default pins");
    press("能量");
    check((Integer) activityField("page") == 18, "fourth navigation model opens energy tab");
    check(!hasText("管理数据权限"), "permission management is not repeated in main content");
    press("数据来源与权限");
    check(
        hasText("管理数据权限") && hasText("数据使用说明"), "permission and privacy entries remain reachable");
    press("关闭");
    db.setSetting(
        "bodyProfile",
        new JSONObject()
            .put("birth", "1990-01-01")
            .put("cm", 175)
            .put("kg", 70)
            .put("sex", 1)
            .put("scope", 0)
            .toString());
    db.weight(null, java.time.LocalDate.now().toString(), 70, true);
    auditPage(18, app);
    check(db.energyDays().size() == 1, "today energy snapshot persists");
    JSONObject first = db.energyDays().get(0);
    check(
        !first.isNull("expenditure") && first.isNull("difference"),
        "partial estimates never become a claimed deficit");
    auditPage(0, app);
    check(db.energyDays().size() == 1, "home and energy page update one record per day");
    check(hasText("每日能量"), "pinned energy card is visible in summary hierarchy");
    press("能量");
    press("查看全部");
    check(hasText("每日能量记录"), "history opens from daily records");
    press(java.time.LocalDate.now() + "记录说明");
    check(hasText("静息估算 + 已记录运动"), "history retains source labels behind info");
    press("关闭");
    screenshot("v14-energy-history");
    press("关闭");
    press("训练");
    press("运动库");
    set("搜索运动", "公路车");
    press("查看骑行指引");
    press("选用运动");
    check(
        app.active == null && hasText("开始计时"),
        "selecting exercise does not create an active workout");
    int history = db.workoutCount();
    Thread.sleep(1500);
    check(
        app.active == null && db.workoutCount() == history,
        "preparation time cannot accumulate as exercise");
    rotateActivity();
    check(hasText("骑行") && app.active == null, "preparation survives recreation without starting");
    screenshot("v14-preparation");
    press("心率设备");
    check(
        hasText("搜索设备") && app.active == null, "heart pairing is available before workout timing");
    press("‹ 返回");
    press("添加运动");
    set("搜索运动或器材", "深蹲");
    press("调整徒手深蹲");
    set("组数", "2");
    press("选用此设置");
    press("添加所选运动");
    check(
        app.active == null && db.plan("all").isEmpty(), "preparation does not alter personal plan");
    press("调整待开始的徒手深蹲");
    set("组数", "3");
    press("保存");
    check(app.active == null, "adjusting preparation never starts timing");
    long requestedStart = System.currentTimeMillis();
    long requestedMonotonic = SystemClock.elapsedRealtime();
    press("开始计时");
    check(
        app.active != null
            && app.active.items.size() == 2
            && app.active.startedWall >= requestedStart
            && app.active.elapsed <= SystemClock.elapsedRealtime() - requestedMonotonic + 100,
        "explicit start begins a fresh timer without preparation time");
    check(app.active.items.get(1).exercise.sets == 3, "adjusted quantities reach workout");
    press("暂停");
    press("添加运动");
    set("搜索运动或器材", "俯卧撑");
    press("俯卧撑");
    press("添加所选运动");
    check(
        !app.active.running && app.active.items.size() == 3,
        "mid-session addition preserves pause");
    press("结束");
    press("保存训练");
    check(app.active == null, "prepared workout can finish normally");
    press("能量");
    screenshot("v14-energy");
  }

  private void mediaFlows(FitnessApp app, Store db) throws Exception {
    MainActivity a = (MainActivity) currentActivity;
    File folder = new File(a.getCacheDir(), "shared");
    folder.mkdirs();
    File canceled = new File(folder, "camera-audit-cancel.jpg");
    try (FileOutputStream out = new FileOutputStream(canceled)) {
      out.write(new byte[] {1, 2});
    }
    a.getPreferences(0).edit().putString("cameraPath", canceled.getAbsolutePath()).commit();
    runOnMainSync(() -> a.onActivityResult(202, Activity.RESULT_CANCELED, null));
    check(!canceled.exists(), "cancel removes only temporary camera output");
    check(!a.getPreferences(0).contains("cameraPath"), "cancel clears pending camera reference");
    File external = new File(a.getFilesDir(), "camera-audit-protected.jpg");
    external.createNewFile();
    a.getPreferences(0).edit().putString("cameraPath", external.getAbsolutePath()).commit();
    runOnMainSync(() -> a.onActivityResult(202, Activity.RESULT_CANCELED, null));
    check(external.exists(), "camera cleanup cannot delete files outside camera cache");
    external.delete();
    a.getPreferences(0).edit().remove("cameraPath").commit();
    File successful = new File(folder, "camera-audit-success.jpg");
    Bitmap pixels = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
    pixels.eraseColor(android.graphics.Color.BLUE);
    try (FileOutputStream out = new FileOutputStream(successful)) {
      pixels.compress(Bitmap.CompressFormat.JPEG, 90, out);
    }
    pixels.recycle();
    int before = db.photos().size();
    a.getPreferences(0).edit().putString("cameraPath", successful.getAbsolutePath()).commit();
    runOnMainSync(() -> a.onActivityResult(202, Activity.RESULT_OK, null));
    for (int i = 0; i < 50 && (db.photos().size() == before || successful.exists()); i++)
      Thread.sleep(100);
    check(db.photos().size() == before + 1, "successful camera result imports one app copy");
    check(
        !successful.exists() && !a.getPreferences(0).contains("cameraPath"),
        "successful import clears temporary capture");
    check(new File(db.photos().get(0)[1]).isFile(), "durable photo survives temporary cleanup");
  }

  private void extendedFlows(FitnessApp app, Store db) throws Exception {
    press("‹ 返回");
    check(hasText("健康与运动数据"), "secondary back destination survives recreation");
    press("身体测量");
    press("月");
    press("半年");
    press("周");
    press("添加数据");
    set("体重（千克）", "0");
    press("保存");
    check(hasText("添加体重") && db.weights().size() == 1, "invalid weight keeps draft and records");
    screenshot("weight-invalid");
    set("体重（千克）", "66.5");
    press("保存");
    check(hasText("替换当天体重？"), "duplicate date requires replacement confirmation");
    press("返回编辑");
    check(
        db.weights().get(0).kg == 68.8 && hasText("66.5"),
        "cancel replacement keeps original and draft");
    press("保存");
    press("替换记录");
    check(
        db.weights().size() == 1 && db.weights().get(0).kg == 66.5,
        "confirmed replacement is atomic");
    press("目标体重");
    set("目标体重（千克）", "65");
    press("保存");
    check(db.setting("weightGoal", "").equals("65.0"), "goal saved");
    press("目标体重");
    press("清除目标");
    check(db.setting("weightGoal", "x").isEmpty(), "goal cleared");
    press("删除");
    press("取消");
    check(db.weights().size() == 1, "cancel weight deletion preserves record");
    press("删除");
    screenshot("weight-delete-confirm");
    press("删除");
    check(db.weights().isEmpty(), "weight deletion clears series");
    press("浏览");
    press("营养");
    String previousMeal = db.meals().get(0).toString();
    press("编辑");
    press("手动填写 / 包装食品");
    set("食物名称，食用量（克）", "米饭，0");
    press("保存");
    check(
        hasText("编辑饮食记录") && db.meals().get(0).toString().equals(previousMeal),
        "invalid food grams keep original");
    set("食物名称，食用量（克）", "自制便当，200\n包装酸奶，150，75");
    press("保存");
    check(hasText("1项未计入"), "unknown food remains explicit after editing");
    press("编辑");
    press("手动填写 / 包装食品");
    check(hasText("75.0"), "manual nutrition rate survives re-edit");
    press("取消");
    press("删除");
    press("取消");
    check(db.meals().size() == 1, "cancel meal deletion preserves data");
    press("删除");
    press("删除");
    check(db.meals().isEmpty() && hasText("还没有饮食记录"), "meal deletion updates empty state");
    press("训练");
    press("我的计划");
    press("编辑");
    set("组数", "0");
    press("保存");
    check(
        hasText("编辑运动") && db.plan("all").get(0).sets == 1,
        "invalid plan edit preserves saved plan");
    set("组数", "3");
    press("保存");
    check(db.plan("all").get(0).sets == 3, "plan edit commits");
    press("移除");
    press("取消");
    check(db.plan("all").size() == 1, "cancel exercise removal preserves plan");
    press("移除");
    press("移除");
    check(db.plan("all").isEmpty(), "exercise removal empties plan");
    press("添加运动");
    press("自定义");
    set("运动名称", "验收计时动作");
    press("记录方式");
    press("时长与组数");
    set("组数", "2");
    set("每组时长（秒）", "30");
    press("保存");
    check(
        db.plan("all").size() == 1 && db.plan("all").get(0).timed,
        "custom timed exercise saves record mode");
    press("开始训练");
    press("开始计时");
    Thread.sleep(1500);
    press("暂停");
    long paused = app.active.elapsed;
    Thread.sleep(1200);
    check(app.active.elapsed == paused, "timed workout stays frozen when paused");
    press("添加运动");
    set("搜索运动或器材", "深蹲");
    press("徒手深蹲");
    press("添加所选运动");
    check(
        app.active.items.size() == 2 && !app.active.running && db.plan("all").size() == 1,
        "adding during paused timed workout preserves pause and original plan");
    set("验收计时动作第1组秒数", "2");
    press("完成验收计时动作第1组");
    check(
        app.active.count() == 1 && app.active.items.get(0).logs.get(0).ms == 2000,
        "timed group records chosen duration");
    press("撤销最后一组");
    check(app.active.count() == 0 && !app.active.running, "timed undo restores paused draft");
    press("继续");
    press("结束");
    press("保存训练");
    press("填写热量");
    set("活动热量（千卡）", "120");
    press("保存");
    check(db.latestWorkout().calories == 120, "workout energy saved");
    press("撤销上次修改");
    check(db.latestWorkout().calories == null, "energy undo restores unknown rather than zero");
    screenshot("timed-workout-summary");
    press("删除记录");
    press("删除");
    press("浏览");
  }

  private Object activityField(String name) throws Exception {
    java.lang.reflect.Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(currentActivity);
  }

  private void auditPage(int page, FitnessApp app) throws Exception {
    new DesignAudit(this, currentActivity, app).page(page);
  }

  private void bottom() throws Exception {
    ScrollView scroll = (ScrollView) activityField("scroll");
    runOnMainSync(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight()));
    waitForIdleSync();
    Thread.sleep(200);
  }

  private void adversarialFlows(FitnessApp app, Store db) throws Exception {
    auditPage(12, app);
    check(hasText("此时间范围暂无记录"), "empty weight range explained");
    check(!hasText("左右拖动查看记录"), "empty weight chart has no impossible gesture hint");
    screenshot("v09-weight-empty");
    BleHeart scanProbe = new BleHeart(app);
    java.lang.reflect.Method callbackFactory =
        BleHeart.class.getDeclaredMethod("callbackFor", int.class);
    callbackFactory.setAccessible(true);
    android.bluetooth.le.ScanCallback oldScan =
        (android.bluetooth.le.ScanCallback) callbackFactory.invoke(scanProbe, 0);
    runOnMainSync(
        () -> {
          scanProbe.stopScan();
          scanProbe.status = "下一轮搜索";
        });
    oldScan.onScanFailed(5);
    waitForIdleSync();
    check(scanProbe.status.equals("下一轮搜索"), "stale BLE scan failure cannot overwrite later state");
    android.bluetooth.le.ScanCallback currentScan =
        (android.bluetooth.le.ScanCallback) callbackFactory.invoke(scanProbe, 1);
    currentScan.onScanFailed(5);
    waitForIdleSync();
    check(scanProbe.status.contains("搜索失败（5）"), "current scan failure remains explicit");
    currentScan.onScanFailed(6);
    waitForIdleSync();
    check(scanProbe.status.contains("搜索失败（5）"), "settled scan ignores duplicate delayed callback");
    java.time.LocalDate today = java.time.LocalDate.now();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 151; i++) {
      Domain.Session q = new Domain.Session("all", List.of());
      q.id = String.format(Locale.ROOT, "v09-history-%03d", i);
      q.date = (i < 120 ? today : today.minusDays(7)).toString();
      q.startedWall =
          java.time.LocalDate.parse(q.date)
                  .atStartOfDay(java.time.ZoneId.systemDefault())
                  .toInstant()
                  .toEpochMilli()
              + i * 60000L;
      q.elapsed = 60000;
      q.endedWall = q.startedWall + 60000;
      q.calories = 10.0;
      db.saveSession(q, false);
      ids.add(q.id);
    }
    check(db.workoutCount() == 151, "long-lived database retains all workouts");
    check(
        db.workoutsBetween(today, today.plusDays(1)).size() == 120,
        "today aggregation not truncated to latest 100");
    Set<String> paged = new HashSet<>();
    for (int offset = 0; offset < 151; offset += 50)
      for (Domain.Session q : db.workouts(50, offset)) paged.add(q.id);
    check(paged.size() == 151, "pagination reaches every record");
    auditPage(14, app);
    check(hasText("共 151 次训练"), "history exposes total");
    press("下一页");
    press("下一页");
    press("下一页");
    check(hasText("第 151–151 条"), "oldest history page reachable");
    screenshot("v09-history-last-page");
    rotateActivity();
    check((Integer) activityField("historyOffset") == 150, "history position survives recreation");
    Domain.Session oldest = db.workouts(1, 150).get(0);
    check(db.workout(oldest.id) != null, "direct lookup beyond latest 100");
    java.lang.reflect.Field selected = MainActivity.class.getDeclaredField("selected");
    selected.setAccessible(true);
    runOnMainSync(
        () -> {
          try {
            selected.set(currentActivity, oldest);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    auditPage(7, app);
    rotateActivity();
    check(
        ((Domain.Session) activityField("selected")).id.equals(oldest.id),
        "old detail survives recreation without opening a newer workout");
    press("删除记录");
    press("取消");
    check(db.workoutCount() == 151, "cancel old-record deletion leaves all data");
    press("删除记录");
    press("删除");
    check(
        db.workoutCount() == 150 && db.workout(oldest.id) == null,
        "old-record delete removes only selected ID");
    for (String id : ids) db.removeWorkout(id);
    check(db.latestWorkout() == null, "latest workout clears after final deletion");
    auditPage(2, app);
    press("添加运动");
    press("自定义");
    set("运动名称", "测试定制训练");
    set("组数", "5");
    set("每组次数", "9");
    press("不记录重量");
    press("总重");
    set("重量（千克）", "15.5");
    rotateActivity();
    check(hasText("测试定制训练") && hasText("15.5"), "custom name and weight survive recreation");
    press("保存");
    check(
        db.plan("all").size() == 1
            && db.plan("all").get(0).name.equals("测试定制训练")
            && db.plan("all").get(0).sets == 5
            && db.plan("all").get(0).reps == 9
            && db.plan("all").get(0).weight == 15.5,
        "custom draft commits exact values after recreation");
    press("编辑");
    press("测试定制训练");
    press("哑铃分腿蹲");
    check(hasText("12") && hasText("0.0"), "switch exercise resets quantities and load");
    set("组数", "4");
    set("每组次数", "8");
    set("重量（千克）", "12.5");
    rotateActivity();
    press("保存");
    check(
        db.plan("all").get(0).id.equals("db_split_squat")
            && db.plan("all").get(0).sets == 4
            && db.plan("all").get(0).weight == 12.5,
        "changed exercise draft survives recreation");
    auditPage(16, app);
    set("搜索运动", "分腿蹲");
    press("查看哑铃分腿蹲指引");
    check(hasText("查看计划") && !hasText("加入计划"), "already-planned guide avoids duplicate action");
    press("查看计划");
    check(db.plan("all").size() == 1, "guide existing plan never duplicates it");
    auditPage(16, app);
    press("查看哑铃分腿蹲指引");
    press("选用运动");
    press("开始计时");
    check(
        app.active.item().exercise.sets == 4
            && app.active.item().exercise.reps == 8
            && app.active.item().exercise.weight == 12.5,
        "library start prioritizes user's matching plan settings");
    press("暂停");
    auditPage(16, app);
    press("查看哑铃分腿蹲指引");
    check(hasText("返回训练") && !hasText("添加到本次"), "active guide avoids duplicate action");
    press("返回训练");
    check(
        app.active.items.size() == 1 && !app.active.running,
        "guide return preserves paused session");
    press("添加运动");
    set("搜索运动或器材", "平板支撑");
    press("调整平板支撑");
    set("组数", "0");
    press("选用此设置");
    check(app.active.items.size() == 1, "invalid adjustment not committed");
    rotateActivity();
    check(hasText("0"), "invalid adjustment draft preserved for correction");
    press("取消");
    set("搜索运动或器材", "动感单车");
    press("调整动感单车");
    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    waitForIdleSync();
    rotateActivity();
    ExercisePicker picker = (ExercisePicker) activityField("exercisePicker");
    check(
        picker.editing == null && (picker.detail == null || !picker.detail.isShowing()),
        "cancelled distance detail does not reopen on recreation");
    check(picker.chosen.isEmpty(), "cancelled invalid edit adds nothing");
    press("取消");
    press("添加运动");
    set("搜索运动或器材", "哑铃弯举");
    press("哑铃弯举");
    press("添加所选运动");
    press("开始哑铃弯举");
    Thread.sleep(1200);
    check(
        app.active.current == 1 && app.active.running && app.active.item().activeMs > 0,
        "strength selection starts per-item duration before first completed set");
    press("暂停");
    long frozen = app.active.item().activeMs;
    Thread.sleep(1200);
    check(app.active.item().activeMs == frozen, "selected strength duration freezes on pause");
    set("哑铃分腿蹲第1组次数", "8");
    press("完成哑铃分腿蹲第1组");
    check(
        app.active.count() == 1 && !app.active.running,
        "recording on pause does not restart workout");
    screenshot("v09-live-projects");
    press("结束");
    press("保存训练");
    check(hasText("8次/侧"), "saved unilateral log retains per-side meaning");
    screenshot("v09-unilateral-history");
    press("删除记录");
    press("删除");
    db.plan("all", List.of());
    auditPage(2, app);
    press("添加运动");
    set("搜索运动或器材", "动感单车");
    press("动感单车");
    press("添加所选运动");
    press("编辑动感单车");
    set("运动名称", "轻松骑行");
    rotateActivity();
    press("保存");
    Domain.Exercise renamed = db.plan("all").get(0);
    check(
        renamed.id.equals("stationary_bike")
            && renamed.distanceBased()
            && renamed.sets == 1
            && renamed.name.equals("轻松骑行"),
        "renamed distance activity keeps identity and metrics after recreation");
    db.plan("all", List.of());
    check(
        db.workoutCount() == 0 && app.active == null,
        "test fixtures cleaned without lingering workout");
    auditPage(0, app);
  }

  private void learningFlows(FitnessApp app, Store db) throws Exception {
    Bitmap atlas = GuideImages.decode(getTargetContext(), "exercises-v10.png");
    check(atlas != null && atlas.getWidth() == atlas.getHeight(), "new atlas bundled and square");
    atlas.recycle();
    Domain.Exercise personal =
        Domain.catalog("all", Set.of()).stream()
            .filter(e -> e.id.equals("squat"))
            .findFirst()
            .orElseThrow();
    personal.sets = 5;
    personal.reps = 17;
    db.plan("all", List.of(personal));
    auditPage(2, app);
    auditPage(1, app);
    press("选择训练模板");
    press("徒手基础");
    check(hasText("保留 5组 × 17次"), "preview reflects preserved personal settings");
    screenshot("v10-template-preview");
    press("取消");
    check(db.plan("all").size() == 1, "template preview cancellation changes nothing");
    auditPage(1, app);
    press("选择训练模板");
    press("徒手基础");
    press("添加到我的计划");
    check(db.plan("all").size() == 4, "template appends missing exercises");
    check(
        db.plan("all").get(0).sets == 5 && db.plan("all").get(0).reps == 17,
        "template keeps personal quantities");
    auditPage(1, app);
    press("选择训练模板");
    press("徒手基础");
    press("添加到我的计划");
    check(db.plan("all").size() == 4, "repeat template never duplicates plan");
    auditPage(16, app);
    set("搜索运动", "锤式");
    press("查看哑铃锤式弯举指引");
    check(hasText("动作步骤") && hasText("动作要点"), "structured teaching present");
    screenshot("v10-teaching-top");
    press("最近训练");
    check(hasText("还没有完成此动作的记录"), "history empty state");
    press("关闭");
    // Scroll the actual sheet to inspect the lower teaching and alternatives.
    for (int round = 0; round < 4; round++) {
      List<AccessibilityNodeInfo> nodes = new ArrayList<>();
      collect(getUiAutomation().getRootInActiveWindow(), nodes);
      for (AccessibilityNodeInfo n : nodes)
        if (n.isScrollable()) {
          n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
          break;
        }
      waitForIdleSync();
      Thread.sleep(100);
    }
    check(hasText("常见错误") && hasText("相关动作"), "lower teaching reachable");
    screenshot("v10-teaching-alternatives");
    press("哑铃弯举");
    List<Dialog> openGuides = (List<Dialog>) activityField("dialogs");
    List<Dialog> showing = new ArrayList<>();
    for (Dialog dialog : openGuides) if (dialog.isShowing()) showing.add(dialog);
    java.lang.reflect.Field spec = DesignDialog.class.getDeclaredField("spec");
    spec.setAccessible(true);
    check(
        showing.size() == 1
            && ((DesignDialog.Builder) spec.get(showing.get(0))).heading.toString().equals("哑铃弯举"),
        "alternative opens correct guide without stacked old sheet");
    press("关闭");
    Domain.Exercise target =
        Domain.catalog("all", Set.of()).stream()
            .filter(e -> e.id.equals("hammer_curl"))
            .findFirst()
            .orElseThrow();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 106; i++) {
      Domain.Session q = new Domain.Session("all", i == 0 ? List.of(target) : List.of());
      q.startedWall = 100000L + i * 60000L;
      q.endedWall = q.startedWall + 1000;
      q.elapsed = 1000;
      if (i == 0) q.item().logs.add(new Domain.Log(8, 12.5, 0, false));
      db.saveSession(q, false);
      ids.add(q.id);
    }
    check(
        db.exerciseHistory(target, 10).size() == 1,
        "history reaches exercise beyond first 100 workouts");
    auditPage(16, app);
    set("搜索运动", "锤式");
    press("查看哑铃锤式弯举指引");
    press("最近训练");
    check(hasText("每只 12.5 千克") && hasText("8次"), "history preserves actual quantities and unit");
    screenshot("v10-exercise-history");
    press("关闭");
    press("关闭");
    db.removeWorkout(ids.get(0));
    check(db.exerciseHistory(target, 10).isEmpty(), "deleted history immediately absent");
    for (String id : ids) db.removeWorkout(id);
    Domain.Session active = new Domain.Session("all", List.of(personal));
    app.active = active;
    auditPage(3, app);
    press("查看徒手深蹲指引");
    press("最近训练");
    check(hasText("还没有完成此动作的记录"), "history opens inside current workout");
    press("关闭");
    press("关闭");
    check(
        app.active == active && !app.active.running,
        "reading history keeps active workout and pause state");
    auditPage(2, app);
    auditPage(1, app);
    press("选择训练模板");
    press("哑铃全身");
    press("添加到我的计划");
    check(
        app.active == active && app.active.items.size() == 1 && !app.active.running,
        "template edits do not alter current paused session");
    app.active = null;
    db.plan("all", List.of());
    auditPage(0, app);
    check(db.workoutCount() == 0, "learning fixtures cleaned");
    learningReviewFlows(app, db);
  }

  private void learningReviewFlows(FitnessApp app, Store db) throws Exception {
    Bitmap corrections = GuideImages.decode(getTargetContext(), "exercises-corrected-v11.png");
    check(
        corrections != null && corrections.getWidth() == corrections.getHeight() * 2,
        "correction atlas decoded with actual landscape proportions");
    corrections.recycle();
    java.lang.reflect.Method guide =
        MainActivity.class.getDeclaredMethod("exerciseGuide", Domain.Exercise.class);
    guide.setAccessible(true);
    for (String theme : List.of("light", "dark")) {
      db.setSetting("theme", theme);
      auditPage(16, app);
      for (Domain.Exercise e : Domain.catalog("all", Set.of())) {
        runOnMainSync(
            () -> {
              try {
                guide.invoke(currentActivity, e);
              } catch (Exception x) {
                throw new RuntimeException(x);
              }
            });
        waitForIdleSync();
        check(hasText(e.name) && hasText("动作步骤"), "guide opens " + theme + e.id);
        Thread.sleep(250);
        screenshot("guide-" + theme + "-" + e.id);
        press("关闭");
      }
      auditPage(2, app);
      for (TrainingTemplates.Template t : TrainingTemplates.ALL) {
        auditPage(1, app);
        press("选择训练模板");
        press(t.name());
        check(hasText("添加到我的计划"), "template preview " + theme + t.id());
        screenshot("v11-" + theme + "-template-" + t.id());
        press("取消");
      }
      check(db.plan("all").isEmpty(), "all previews cancel without writing " + theme);
    }
    db.setSetting("theme", "auto");
    Domain.Exercise personal =
        Domain.catalog("all", Set.of()).stream()
            .filter(e -> e.id.equals("hammer_curl"))
            .findFirst()
            .orElseThrow();
    personal.name = "我的锤式弯举";
    personal.sets = 4;
    personal.reps = 9;
    personal.weight = 7.5;
    db.plan("all", List.of(personal));
    Domain.Exercise stale = personal.copy();
    stale.sets = 2;
    stale.weight = 99;
    db.setSetting("exercise.last.all.hammer_curl", Store.exercise(stale).toString());
    Domain.Exercise squat = Domain.catalog("all", Set.of()).get(0);
    Domain.Session active = new Domain.Session("all", List.of(squat));
    app.active = active;
    auditPage(3, app);
    press("添加运动");
    set("搜索运动或器材", "锤式");
    press("调整我的锤式弯举");
    ExercisePicker picker = (ExercisePicker) activityField("exercisePicker");
    check(
        picker.detailSets.getText().toString().equals("4")
            && picker.detailWeight.getText().toString().equals("7.5"),
        "picker prefers renamed personal plan over stale last values");
    set("组数", "0");
    press("选用此设置");
    check(
        picker.detail.isShowing() && active.items.size() == 1,
        "invalid adjustment stays open and never adds");
    set("组数", "3");
    set("每组次数", "11");
    set("重量（千克）", "8.5");
    press("选用此设置");
    set("搜索运动或器材", "不存在的运动");
    press("添加所选运动");
    check(
        app.active == active && active.items.size() == 2 && !active.running,
        "filtered selection adds once and retains pause");
    check(
        active.items.get(1).exercise.sets == 3
            && active.items.get(1).exercise.reps == 11
            && active.items.get(1).exercise.weight == 8.5,
        "training receives chosen quantities");
    check(
        db.plan("all").get(0).sets == 4 && db.plan("all").get(0).weight == 7.5,
        "one-session adjustment does not overwrite personal plan");
    press("添加运动");
    set("搜索运动或器材", "锤式");
    check(hasText("已添加"), "renamed active movement cannot be selected again");
    press("取消");
    auditPage(16, app);
    set("搜索运动", "锤式");
    press("查看哑铃锤式弯举指引");
    check(hasText("返回训练") && !hasText("添加到本次"), "guide uses same identity as picker");
    press("返回训练");
    check(active.items.size() == 2 && !active.running, "guide return is side-effect free");
    auditPage(16, app);
    set("搜索运动", "坐椅");
    press("查看坐椅深蹲指引");
    runOnMainSync(() -> app.active = null);
    press("添加到本次");
    check(app.active == null, "stale guide cannot start a new workout after session ends");
    List<Domain.Exercise> full = new ArrayList<>();
    for (int i = 0; i < 30; i++) full.add(new Domain.Exercise("custom", "自定" + i, "", false, ""));
    db.plan("all", full);
    auditPage(2, app);
    auditPage(1, app);
    press("选择训练模板");
    press("徒手基础");
    press("添加到我的计划");
    check(
        hasText("每个计划最多30项运动") && hasText("添加到我的计划"),
        "overflow remains in preview with inline error");
    check(db.plan("all").size() == 30, "overflow never partially saves template");
    Thread.sleep(2500);
    screenshot("v11-template-overflow");
    press("取消");
    db.plan("all", List.of());
    db.setSetting("exercise.last.all.hammer_curl", "");
    // Clear only the active fixture written by adding test movements.
    runOnMainSync(
        () -> {
          app.active = active;
          Domain.Session finished = app.finish();
          db.removeWorkout(finished.id);
        });
    auditPage(0, app);
  }

  private void catalogFlows(FitnessApp app, Store db) throws Exception {
    for (String file :
        List.of(
            "exercises.png",
            "exercises-lower-v08.png",
            "exercises-upper-v08.png",
            "exercises-v10.png")) {
      Bitmap bitmap = GuideImages.decode(getTargetContext(), file);
      check(
          bitmap != null && bitmap.getWidth() >= 1000 && bitmap.getHeight() >= 1000,
          "packaged atlas " + file);
      bitmap.recycle();
    }
    String planBefore = db.exportData().getJSONArray("plans").toString();
    press("训练");
    press("运动库");
    check(hasText("共 65 项运动"), "all exercises discoverable");
    for (int i = 0; i < 12; i++) bottom();
    Domain.Exercise lastInLibrary = Domain.catalog("all", Set.of()).get(64);
    check(hasText(lastInLibrary.name), "scroll reaches final movement in incremental library");
    check(!hasText("查看更多运动"), "incremental library ends without a dead-end more button");
    press("查看" + lastInLibrary.name + "指引");
    check(hasText("动作步骤"), "last movement teaching is reachable from actual list");
    press("关闭");
    ScrollView libraryScroll = (ScrollView) activityField("scroll");
    runOnMainSync(() -> libraryScroll.scrollTo(0, 0));
    waitForIdleSync();
    screenshot("v08-library");
    press("运动分类：拉伸");
    check(hasText("共 7 项运动"), "stretch category count");
    screenshot("v08-stretch");
    press("运动分类：全部");
    set("搜索运动", "RDL");
    check(hasText("共 2 项运动"), "alias search");
    press("查看哑铃罗马尼亚硬拉指引");
    check(hasText("哑铃罗马尼亚硬拉"), "guide names match image");
    press("收藏哑铃罗马尼亚硬拉");
    check(
        ExerciseCatalog.favorites(db.setting("exercise.favorites", "")).contains("db_rdl"),
        "favorite persisted");
    screenshot("v08-guide");
    press("关闭");
    set("搜索运动", "");
    press("运动分类：收藏");
    check(hasText("共 1 项运动"), "favorites filter");
    screenshot("v08-favorites");
    press("查看哑铃罗马尼亚硬拉指引");
    press("收藏哑铃罗马尼亚硬拉");
    press("关闭");
    check(hasText("还没有收藏"), "unfavorite refreshes list without leaving page");
    check(
        db.exportData().getJSONArray("plans").toString().equals(planBefore),
        "browsing/favorite never mutates plan");
    press("运动分类：全部");
    press("筛选部位与器材");
    press("全部部位");
    press("肩");
    press("全部器材");
    press("哑铃");
    screenshot("v08-filters");
    press("查看结果");
    check(hasText("共 5 项运动"), "combined shoulder/dumbbell filters");
    press("筛选部位与器材");
    press("清除筛选");
    set("搜索运动", "动感单车");
    press("查看动感单车指引");
    press("选用运动");
    press("开始计时");
    Thread.sleep(1200);
    check(
        app.active != null && app.active.item().exercise.id.equals("stationary_bike"),
        "start new indoor distance activity");
    check(
        !app.active.routeEnabled && !app.active.item().exercise.usesGps(),
        "indoor cycling does not request route");
    press("填写距离");
    set("距离（公里）", "1.2");
    press("保存");
    check(app.active.item().manualMeters == 1200.0, "indoor distance commits");
    press("暂停运动");
    press("添加其他运动");
    set("搜索运动或器材", "分腿蹲");
    press("调整哑铃分腿蹲");
    check(hasText("次数按每侧记录"), "picker explains unilateral convention");
    screenshot("v08-adjust");
    press("选用此设置");
    press("添加所选运动");
    check(
        app.active.items.size() == 2 && !app.active.running,
        "append new strength preserves paused session");
    check(hasText("次数 / 侧"), "live set uses per-side label");
    screenshot("v08-mixed");
    Domain.Session restored = Store.session(Store.session(app.active));
    check(
        restored.items.get(1).exercise.id.equals("db_split_squat")
            && restored.items.get(0).manualMeters == 1200.0,
        "new exercise and metrics roundtrip");
    press("结束");
    press("保存训练");
    check(app.active == null, "expanded catalog workout saves");
    screenshot("v08-history");
    press("删除记录");
    press("删除");
    press("训练");
    press("运动库");
    set("搜索运动", "");
    screenshot("v08-final-library");
  }

  private void unifiedFlows(FitnessApp app, Store db) throws Exception {
    FoodCatalog catalog = new FoodCatalog(getTargetContext());
    check(catalog.foods.size() == 119, "offline catalog asset count");
    for (JSONObject food : catalog.foods)
      check(
          catalog.imageIndex(food.optString("name")) >= 0,
          "food image mapping " + food.optString("id"));
    check(
        FoodCatalog.portions(catalog.lookup("米饭")).get(1).grams() == 150,
        "rice bowl explicit editable reference");
    check(catalog.imageIndex("未收录食材") == -1, "unknown food never uses unrelated image");
    Collections.reverse(catalog.foods);
    check(catalog.imageIndex("米饭") == 0, "image map survives catalog reorder");
    Collections.reverse(catalog.foods);
    var exerciseCatalog = Domain.catalog("all", Set.of());
    for (var exercise : exerciseCatalog)
      check(GuideImages.IDS.contains(exercise.id), "exercise visual mapping " + exercise.id);
    // Migration is repeatable and leaves old plans/active session unchanged.
    String planBefore = new JSONArray(db.exportData().getJSONArray("plans").toString()).toString();
    db.unifyPlans();
    db.unifyPlans();
    check(
        planBefore.equals(db.exportData().getJSONArray("plans").toString()),
        "repeat migration no plan mutation");
    press("训练");
    press("运动库");
    screenshot("v07-library");
    check(!hasText("我的训练场景"), "no scene gate in library");
    set("搜索运动", "公路车");
    press("查看骑行指引");
    screenshot("v07-bike-guide");
    press("选用运动");
    press("开始计时");
    Thread.sleep(1200);
    check(
        app.active != null && app.active.item().exercise.id.equals("bike"),
        "direct start from unified library");
    check(!hasText("每组次数"), "distance activity omits rep fields");
    press("填写距离");
    set("距离（公里）", "-1");
    press("保存");
    check(app.active.item().manualMeters == null, "invalid distance does not mutate session");
    set("距离（公里）", "2.5");
    press("保存");
    check(app.active.item().meters() == 2500, "distance saved independently from route");
    check(app.active.item().speedKmh() == null, "implausible manually entered speed not displayed");
    screenshot("v07-bike-live");
    press("暂停运动");
    long time = app.active.item().durationMs();
    Thread.sleep(1200);
    check(app.active.item().durationMs() == time, "distance timer freezes on pause");
    press("添加其他运动");
    set("搜索运动或器材", "哑铃");
    press("哑铃划船");
    press("添加所选运动");
    check(
        app.active.items.size() == 2 && !app.active.running,
        "mixed movement append preserves pause");
    check(hasText("重量 / 千克") && hasText("填写距离"), "mixed view exposes appropriate metrics");
    screenshot("v07-mixed-session");
    Domain.Session roundTrip = Store.session(Store.session(app.active));
    check(
        roundTrip.items.get(0).manualMeters == 2500 && roundTrip.items.get(0).activeMs > 0,
        "new metrics survive serialization");
    press("结束");
    press("保存训练");
    check(app.active == null, "mixed workout finalizes");
    press("删除记录");
    press("删除");
    press("浏览");
    press("每日能量");
    screenshot("v07-energy-empty");
    check(!hasText("记录中的能量差"), "missing meals/device never shows a deficit");
    press("身体资料");
    set("出生日期（年-月-日）", "1990-01-01");
    set("身高（厘米）", "175");
    set("体重（千克）", "70");
    press("请选择");
    press("男性");
    screenshot("v07-profile");
    press("保存");
    check(
        new JSONObject(db.setting("bodyProfile", "{}")).getDouble("cm") == 175,
        "profile committed");
    check(
        hasText("消耗估算") && !hasText("消耗多于摄入") && !hasText("摄入多于消耗"),
        "profile alone does not imply total coverage");
    java.lang.reflect.Method estimate =
        MainActivity.class.getDeclaredMethod(
            "estimatedWorkout", Domain.Session.class, JSONObject.class);
    estimate.setAccessible(true);
    JSONObject normal = new JSONObject(db.setting("bodyProfile", "{}"));
    Domain.Session sample =
        new Domain.Session("all", List.of(new Domain.Exercise("row", "哑铃划船", "哑铃", false, "每只")));
    sample.item().activeMs = 60000;
    check(
        estimate.invoke(currentActivity, sample, normal) != null,
        "ordinary adult exercise estimate available");
    normal.put("scope", 1);
    check(
        estimate.invoke(currentActivity, sample, normal) == null,
        "special profile suppresses generic exercise estimate");
    sample.calories = 25.0;
    check(
        (Double) estimate.invoke(currentActivity, sample, normal) == 25.0,
        "special profile retains explicitly entered active calories");
    screenshot("v07-energy-estimate");
    press("浏览");
    press("营养");
    press("记一餐");
    press("添加食物");
    set("搜索食物", "米饭");
    press("选择米饭（熟、无盐）");
    press("小碗 · 150克 · 约195千卡");
    screenshot("v07-food-portions");
    press("添加");
    check(hasText("195千卡"), "portion card updates calorie preview");
    press("改份量");
    set("修改食用克数", "200");
    press("保存");
    check(hasText("260千卡"), "grams edit updates calories");
    press("保存");
    press("浏览");
    press("每日能量");
    press("截至目前已记全");
    check(!db.setting("foodComplete", "").isEmpty(), "food completeness explicit");
    press("去记饮食");
    press("编辑");
    press("改份量");
    set("修改食用克数", "150");
    press("保存");
    press("保存");
    press("浏览");
    press("每日能量");
    check(hasText("饮食记录尚未确认完整"), "editing food invalidates completeness");
    screenshot("v07-energy-edited");
    press("浏览");
  }

  private AccessibilityNodeInfo appWindow() {
    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
    return root != null
            && getTargetContext()
                .getPackageName()
                .contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
        ? root
        : null;
  }

  private boolean hasText(String text) {
    for (int attempt = 0; attempt < 12; attempt++) {
      AccessibilityNodeInfo root = appWindow();
      if (root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return true;
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  private void press(String label) throws Exception {
    for (int attempt = 0; attempt < 20; attempt++) {
      waitForIdleSync();
      AccessibilityNodeInfo root = appWindow();
      if (root != null)
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
          if (n.isEditable()) continue;
          if (label.contentEquals(n.getText() == null ? "" : n.getText())
              || label.contentEquals(
                  n.getContentDescription() == null ? "" : n.getContentDescription())) {
            AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
              waitForIdleSync();
              Thread.sleep(70);
              return;
            }
          }
        }
      Thread.sleep(100);
    }
    throw new AssertionError("Cannot click: " + label);
  }

  private void set(String label, String value) throws Exception {
    AccessibilityNodeInfo root = appWindow();
    List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    collect(root, nodes);
    for (AccessibilityNodeInfo n : nodes)
      if (label.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription())
          && n.isEditable()) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        check(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b), "edit " + label);
        waitForIdleSync();
        Thread.sleep(180);
        return;
      }
    throw new AssertionError("Cannot edit: " + label);
  }

  private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> a) {
    if (n == null) return;
    a.add(n);
    for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), a);
  }

  private void screenshot(String name) throws Exception {
    waitForIdleSync();
    Thread.sleep(280);
    Bitmap b = getUiAutomation().takeScreenshot();
    if (b == null) return;
    try (FileOutputStream out =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), name + ".png"))) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
  }
}
