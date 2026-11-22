package com.lianyoudu.app;

import android.app.*;
import android.content.DialogInterface;
import android.database.sqlite.SQLiteDatabase;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/**
 * All fixture writes are rolled back, including on failure. Never operates on a real app package.
 */
final class DeviceReuseAudit {
  private final Activity activity;
  private final FitnessApp app;
  private final Store db;
  private int checks;

  private DeviceReuseAudit(Activity activity, FitnessApp app) {
    this.activity = activity;
    this.app = app;
    db = app.store;
  }

  static int run(Instrumentation test, Activity activity, FitnessApp app) throws Exception {
    if (!activity.getPackageName().equals("com.lianyoudu.app.qa"))
      throw new IllegalStateException("Rollback probe requires QA package");
    DeviceReuseAudit probe = new DeviceReuseAudit(activity, app);
    Throwable[] failure = new Throwable[1];
    test.runOnMainSync(
        () -> {
          try {
            probe.run();
          } catch (Throwable e) {
            failure[0] = e;
          }
        });
    if (failure[0] != null) throw new AssertionError("Reuse probe failed", failure[0]);
    return probe.checks;
  }

  private Object field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(activity, args);
  }

  private void page(int page) throws Exception {
    Field field = MainActivity.class.getDeclaredField("page");
    field.setAccessible(true);
    field.setInt(activity, page);
    call("render", new Class<?>[0]);
    check(app.failure.isEmpty(), "page renders without application failure");
  }

  private void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }

  private String snapshot() {
    JSONObject data = db.exportData();
    data.remove("exportedAt");
    return data.toString();
  }

  private boolean contains(View view, String text) {
    if (view instanceof TextView label && label.getText().toString().contains(text)) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), text)) return true;
    return false;
  }

  private void run() throws Exception {
    check(app.active == null || !app.active.running, "must not interrupt running training");
    Domain.Session active = app.active;
    long elapsed = active == null ? 0 : active.elapsed;
    String before = snapshot();
    SQLiteDatabase sql = db.getWritableDatabase();
    sql.beginTransaction();
    try {
      // The complete sequence runs in one main-thread database transaction. No fixture commits.
      db.setSetting("healthPins", "weight,food");
      db.setSetting("bodyProfile", "{}");
      String today = LocalDate.now().toString();
      db.weight(null, today, 81.3, true);
      page(0);
      Object original = field("body");
      String cacheKey = (String) call("mainPageKey", new Class<?>[0]);
      page(17);
      page(0);
      if (cacheKey.equals(call("mainPageKey", new Class<?>[0])))
        check(original == field("body"), "unchanged primary page is reused");
      check(contains((View) field("body"), "81.3"), "initial weight visible");
      page(17);
      db.weight(today, today, 82.4, true);
      page(0);
      check(original != field("body"), "weight update invalidates old page");
      check(contains((View) field("body"), "82.4"), "updated weight visible");
      page(17);
      db.removeWeight(today);
      page(0);
      check(!contains((View) field("body"), "82.4"), "deleted weight does not survive reuse");

      // Exercise the actual weight form's save action as well as storage invalidation.
      page(12);
      call("weightForm", new Class<?>[] {String.class}, today);
      Map<?, ?> fields = (Map<?, ?>) field("fields");
      ((EditText) fields.get("kg")).setText("83.5");
      ((AlertDialog) field("formDialog")).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      check(
          db.weights().stream().anyMatch(w -> w.date.equals(today) && w.kg == 83.5),
          "weight save button persists the entered value");
      page(17);
      page(0);
      check(contains((View) field("body"), "83.5"), "form save propagates to summary");

      for (JSONObject meal : db.meals())
        if (meal.optString("date").equals(today)) db.removeMeal(meal.optString("id"));
      JSONObject food =
          new JSONObject()
              .put("name", "验收食物")
              .put("grams", 100)
              .put("kcal100", 123)
              .put("kcal", 123);
      JSONObject meal =
          new JSONObject()
              .put("id", "reuse-rollback-probe")
              .put("date", today)
              .put("slot", "加餐")
              .put("foods", new JSONArray().put(food));
      db.meal(meal, List.of(), true);
      page(18);
      String key = "energy.day." + today;
      check(
          new JSONObject(db.setting(key, "{}")).optDouble("intake") == 123,
          "energy day stores new food intake");
      page(17);
      db.removeMeal("reuse-rollback-probe");
      page(18);
      check(
          new JSONObject(db.setting(key, "{}")).isNull("intake"),
          "deleting last meal clears daily intake instead of retaining old calories");
      check(
          ((Number) call("todayFood", new Class<?>[0])).doubleValue() == 0,
          "energy calculation agrees with empty meals");
      check(
          call("todayFoodKnown", new Class<?>[0]) == null,
          "no food record is not displayed as a known zero intake");
      JSONObject zeroFood =
          new JSONObject().put("name", "零热量测试").put("grams", 100).put("kcal100", 0).put("kcal", 0);
      JSONObject zeroMeal =
          new JSONObject()
              .put("id", "reuse-zero-probe")
              .put("date", today)
              .put("slot", "加餐")
              .put("foods", new JSONArray().put(zeroFood));
      db.meal(zeroMeal, List.of(), true);
      page(18);
      check(
          ((Number) call("todayFoodKnown", new Class<?>[0])).doubleValue() == 0,
          "explicit zero-calorie food remains a known zero");
      check(
          !new JSONObject(db.setting(key, "{}")).isNull("intake"),
          "explicit zero intake is persisted in daily energy");
      db.removeMeal("reuse-zero-probe");
      JSONObject unknownFood = new JSONObject().put("name", "未知热量测试").put("grams", 100);
      JSONObject unknownMeal =
          new JSONObject()
              .put("id", "reuse-unknown-probe")
              .put("date", today)
              .put("slot", "加餐")
              .put("foods", new JSONArray().put(unknownFood));
      db.meal(unknownMeal, List.of(), true);
      page(18);
      check(
          call("todayFoodKnown", new Class<?>[0]) == null
              && new JSONObject(db.setting(key, "{}")).isNull("intake"),
          "unknown food calories remain unknown in both live and daily records");
      check(
          app.active == active
              && (active == null || (!active.running && active.elapsed == elapsed)),
          "preserved paused training untouched");
    } finally {
      try {
        call("closeForm", new Class<?>[0]);
      } finally {
        sql.endTransaction();
      } // Deliberately never setTransactionSuccessful.
      db.uiRevision++;
      ((Map<?, ?>) field("mainPages")).clear();
      check(before.equals(snapshot()), "all stored records and settings restored exactly");
      page(0);
    }
  }
}
