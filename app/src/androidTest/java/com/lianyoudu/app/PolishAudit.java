package com.lianyoudu.app;

import android.app.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** Geometry + user paths. Fixtures must never run against a physical user's database. */
final class PolishAudit {
  final Instrumentation test;
  final Activity activity;
  final FitnessApp app;
  final Store db;
  final DesignAudit design;
  int checks;

  PolishAudit(Instrumentation t, Activity a, FitnessApp f) {
    test = t;
    activity = a;
    app = f;
    db = f.store;
    design = new DesignAudit(t, a, f);
  }

  void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }

  void invoke(String name, Class<?>[] types, Object... args) {
    test.runOnMainSync(
        () -> {
          try {
            Method method = MainActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    test.waitForIdleSync();
    SystemClock.sleep(180);
  }

  void call(String name) {
    invoke(name, new Class<?>[0]);
  }

  List<View> views(View view) {
    List<View> result = new ArrayList<>();
    result.add(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) result.addAll(views(group.getChildAt(i)));
    return result;
  }

  View body() throws Exception {
    return (View) design.field("body");
  }

  TextView label(View root, String label) {
    for (View view : views(root))
      if (view instanceof TextView text
          && (label.contentEquals(text.getText())
              || label.contentEquals(
                  text.getContentDescription() == null ? "" : text.getContentDescription())))
        return text;
    throw new AssertionError("Missing label " + label);
  }

  void click(View view) {
    test.runOnMainSync(view::performClick);
    test.waitForIdleSync();
    SystemClock.sleep(220);
  }

  void geometry(View root) {
    for (View view : views(root)) {
      if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0) continue;
      if (view.getParent() instanceof LinearLayout parent
          && parent.getOrientation() == LinearLayout.HORIZONTAL) {
        check(
            view.getRight() <= parent.getWidth() - parent.getPaddingRight() + 2,
            "horizontal child fits: "
                + (view instanceof TextView t ? t.getText() : view.getClass().getSimpleName()));
      }
      if (view instanceof TextView text
          && text.isClickable()
          && text.getEllipsize() == null
          && text.getLayout() != null) {
        for (int i = 0; i < text.getLayout().getLineCount(); i++)
          check(
              text.getLayout().getLineWidth(i)
                  <= text.getWidth() - text.getPaddingLeft() - text.getPaddingRight() + 2,
              "button label fits: " + text.getText());
      }
    }
  }

  DesignDialog dialog() throws Exception {
    List<?> dialogs = (List<?>) design.field("dialogs");
    for (int i = dialogs.size() - 1; i >= 0; i--)
      if (dialogs.get(i) instanceof DesignDialog d && d.isShowing()) return d;
    throw new AssertionError("No owned dialog");
  }

  void equalActions(DesignDialog dialog) {
    View previous = null;
    for (int which : new int[] {-2, -3, -1}) {
      View action = dialog.getButton(which);
      if (action == null) continue;
      if (previous != null) {
        check(Math.abs(previous.getWidth() - action.getWidth()) <= 1, "dialog actions equal width");
        check(previous.getHeight() == action.getHeight(), "dialog actions equal height");
      }
      previous = action;
    }
    geometry(dialog.getWindow().getDecorView());
  }

  void dismiss(DesignDialog dialog) {
    test.runOnMainSync(dialog::dismiss);
    test.waitForIdleSync();
  }

  void shot(String name) throws Exception {
    SystemClock.sleep(500); // Capture settled dialogs, not a partially transparent entrance frame.
    android.view.accessibility.AccessibilityNodeInfo root = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      root = test.getUiAutomation().getRootInActiveWindow();
      if (root != null && activity.getPackageName().contentEquals(root.getPackageName())) break;
      SystemClock.sleep(100);
    }
    if (root == null || !activity.getPackageName().contentEquals(root.getPackageName()))
      design.shot(name + "-obscured");
    check(
        root != null && activity.getPackageName().contentEquals(root.getPackageName()),
        "screenshot is not obscured by permission or other app: "
            + name
            + " window="
            + (root == null ? "null" : root.getPackageName()));
    design.shot(name);
  }

  int run() throws Exception {
    check(
        activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED,
        "pregrant notifications before visual test process starts");
    check(app.active == null, "no active fixture at start");
    db.setSetting("workoutPreparation", "[]");
    db.setSetting("workoutPreparation.kind", "综合");
    for (String theme : new String[] {"light", "dark"}) {
      db.setSetting("theme", theme);
      for (int page : new int[] {0, 1, 2, 3, 6, 7, 8, 9, 11, 12, 14, 15, 16, 17, 18, 19}) {
        design.page(page);
        geometry(body());
        shot("v16-" + theme + "-" + page);
      }
    }
    db.setSetting("theme", "light");
    design.page(15);
    String installedVersion =
        activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
    check(
        views(body()).stream()
            .anyMatch(
                v ->
                    v instanceof TextView t
                        && t.getText().toString().contains("练有度 " + installedVersion)),
        "settings displays the actual installed version");
    design.page(16);
    TextView lastCategory = label(body(), "运动分类：拉伸");
    HorizontalScrollView categories = (HorizontalScrollView) lastCategory.getParent().getParent();
    click(lastCategory);
    SystemClock.sleep(450);
    check(lastCategory.isSelected(), "last exercise category can be selected");
    if (categories.getChildAt(0).getWidth() > categories.getWidth())
      check(categories.getScrollX() > 0, "selected category scrolls into view");
    design.page(17);
    design.page(16);
    SystemClock.sleep(350);
    TextView restoredCategory = label(body(), "运动分类：拉伸");
    HorizontalScrollView restoredCategories =
        (HorizontalScrollView) restoredCategory.getParent().getParent();
    check(restoredCategory.isSelected(), "selected exercise category survives navigation");
    if (restoredCategories.getChildAt(0).getWidth() > restoredCategories.getWidth())
      check(restoredCategories.getScrollX() > 0, "selected category is visible on return");
    click(label(body(), "运动分类：全部"));
    Method cacheKeyMethod = MainActivity.class.getDeclaredMethod("mainPageKey");
    cacheKeyMethod.setAccessible(true);
    View cachedHome = null;
    boolean reused = false;
    for (int attempt = 0; attempt < 3 && !reused; attempt++) {
      design.page(0);
      cachedHome = body();
      String key = (String) cacheKeyMethod.invoke(activity);
      design.page(17);
      View cachedBrowse = body();
      design.page(0);
      if (!key.equals(cacheKeyMethod.invoke(activity))) {
        check(
            body() != cachedHome,
            "minute/data expiry refreshes summary instead of reusing stale values");
        continue;
      }
      check(body() == cachedHome, "unchanged home reuses measured view tree");
      design.page(17);
      if (!key.equals(cacheKeyMethod.invoke(activity))) continue;
      check(body() == cachedBrowse, "unchanged browse reuses measured view tree");
      reused = true;
    }
    check(reused, "primary pages are reused within a stable data interval");
    db.weight(null, LocalDate.now().toString(), 76.5, true);
    design.page(0);
    check(body() != cachedHome, "weight mutation invalidates cached summary");
    label(body(), "76.5 千克");
    checks++;
    View updatedHome = body();
    db.removeWeight(LocalDate.now().toString());
    design.page(17);
    design.page(0);
    check(body() != updatedHome, "deleted measurement invalidates cached summary");
    check(
        views(body()).stream()
            .noneMatch(v -> v instanceof TextView t && t.getText().toString().equals("76.5 千克")),
        "deleted value cannot survive in summary cache");
    JSONArray many = new JSONArray();
    for (Domain.Exercise exercise : db.exerciseLibrary().subList(0, 30))
      many.put(Store.exercise(exercise));
    db.setSetting("workoutPreparation", many.toString());
    design.page(19);
    View fixed = (View) design.field("preparationFooter");
    check(
        ((View) design.field("navBottomMask")).getVisibility() == View.GONE,
        "bottom mask hides with the navigation during preparation");
    Rect before = new Rect(), after = new Rect();
    fixed.getGlobalVisibleRect(before);
    ScrollView preparationScroll = (ScrollView) design.field("scroll");
    test.runOnMainSync(() -> preparationScroll.fullScroll(View.FOCUS_DOWN));
    test.waitForIdleSync();
    SystemClock.sleep(250);
    fixed.getGlobalVisibleRect(after);
    check(
        before.equals(after) && after.height() == fixed.getHeight(),
        "30 selected movements do not push start out of viewport");
    check(app.active == null, "scrolling and selecting do not begin timing");
    shot("v16-preparation-30-bottom");
    design.page(0);
    View bottomMask = (View) design.field("navBottomMask");
    check(
        bottomMask.getVisibility() == View.VISIBLE
            && bottomMask.getHeight() == AppViews.dp(activity, 12),
        "the area below the floating navigation has a visible background mask");
    int[] maskPosition = new int[2];
    test.runOnMainSync(() -> bottomMask.getLocationOnScreen(maskPosition));
    android.graphics.Bitmap maskShot = test.getUiAutomation().takeScreenshot();
    int maskedPixel =
        maskShot.getPixel(
            maskPosition[0] + bottomMask.getWidth() / 2,
            maskPosition[1] + bottomMask.getHeight() / 2);
    maskShot.recycle();
    check(
        maskedPixel == HealthDesign.background(false),
        "content below the navigation is covered by the page background");
    check(
        design.field("preparationFooter") == null && fixed.getParent() == null,
        "leaving preparation removes fixed action area");
    check(
        ((View) design.field("nav")).getVisibility() == View.VISIBLE,
        "main navigation returns after preparation");
    db.setSetting("workoutPreparation", "[]");
    design.page(0);
    check(
        label(body(), "开始").getWidth() == AppViews.dp(activity, 72),
        "home action has fixed compact geometry");
    click(label(body(), "开始"));
    check(
        (Integer) design.field("page") == 19 && app.active == null,
        "home opens preparation without counting");
    click(label(body(), "运动类型：力量"));
    check(
        db.setting("workoutPreparation.kind", "").equals("力量") && app.active == null,
        "group selection persists without starting");
    click(label(body(), "添加运动"));
    equalActions(dialog());
    ExercisePicker picker = (ExercisePicker) design.field("exercisePicker");
    check(picker.category.equals("力量"), "picker defaults to selected training group");
    check(picker.chosen.isEmpty(), "group never auto-adds exercises");
    shot("v16-add-movement");
    dismiss(dialog());
    View footer = (View) design.field("preparationFooter");
    check(
        footer != null && ((View) design.field("nav")).getVisibility() == View.GONE,
        "preparation has one fixed action area");
    android.graphics.Rect frame = new android.graphics.Rect();
    check(
        footer.getGlobalVisibleRect(frame) && frame.height() == footer.getHeight(),
        "start action is fully visible before scrolling");
    geometry(footer);
    click(label(footer, "开始计时"));
    check(
        app.active != null && app.active.items.isEmpty() && app.active.trainingKind.equals("力量"),
        "group-only timed workout starts explicitly");
    Domain.Session restored = Store.session(Store.session(app.active));
    check(restored.trainingKind.equals("力量"), "training group round trips");
    JSONObject old = Store.session(restored);
    old.remove("trainingKind");
    check(Store.session(old).trainingKind.equals("综合"), "legacy workout import remains valid");
    geometry(body());
    check(
        views(body()).stream()
            .noneMatch(v -> v instanceof TextView t && t.getText().toString().contains("训练容量")),
        "no misleading cross-exercise kg total");
    click(label(body(), "暂停"));
    click(label(body(), "添加运动"));
    equalActions(dialog());
    check(!app.active.running, "adding does not resume paused workout");
    shot("v16-paused-picker");
    dismiss(dialog());
    test.runOnMainSync(
        () ->
            app.appendExercises(
                List.of(
                    new Domain.Exercise("squat", "徒手深蹲", "", false, ""),
                    new Domain.Exercise("row", "哑铃划船", "哑铃", false, "每只"))));
    design.page(3);
    check(
        views(body()).stream()
            .noneMatch(
                v ->
                    "徒手深蹲第1组重量"
                        .contentEquals(
                            v.getContentDescription() == null ? "" : v.getContentDescription())),
        "bodyweight exercise never offers a misleading zero-weight input");
    check(
        views(body()).stream()
            .anyMatch(
                v ->
                    "哑铃划船第1组重量"
                        .contentEquals(
                            v.getContentDescription() == null ? "" : v.getContentDescription())),
        "weighted exercises retain their load input in the same session");
    click(label(body(), "完成徒手深蹲第1组"));
    check(
        app.active.items.get(0).logs.size() == 1
            && app.active.items.get(0).logs.get(0).weight == 0
            && app.active.items.get(0).logs.get(0).reps == 12,
        "bodyweight set saves its reps without inventing a load");
    test.runOnMainSync(
        () -> {
          app.finish();
        });
    design.page(9);
    click(label(body(), "记一餐"));
    equalActions(dialog());
    shot("v16-food-form");
    click(label(dialog().getWindow().getDecorView(), "添加食物"));
    DesignDialog foodPicker = dialog();
    check(
        views(foodPicker.getWindow().getDecorView()).stream()
            .noneMatch(v -> v instanceof TextView t && t.getText().toString().contains("USDA")),
        "food picker keeps source detail behind the info control");
    click(label(foodPicker.getWindow().getDecorView(), "查看食物热量来源"));
    check(
        views(dialog().getWindow().getDecorView()).stream()
            .anyMatch(
                v ->
                    v instanceof TextView t
                        && t.getText().toString().contains("USDA SR Legacy 2018")),
        "food provenance remains accessible in the info dialog");
    dismiss(dialog());
    dismiss(foodPicker);
    dismiss(dialog());
    check(
        views(body()).stream()
            .noneMatch(v -> v instanceof TextView t && t.getText().toString().equals("餐食照片")),
        "no empty camera funnel");
    design.page(8);
    check(
        views(body()).stream()
            .noneMatch(
                v ->
                    v instanceof TextView t
                        && List.of("拍照", "从相册选择").contains(t.getText().toString())),
        "photo archive offers no unnecessary capture steps");
    db.setSetting("heart.device.address", "00:11:22:33:44:55");
    String longName = "WATCH HR-07E 训练心率广播设备 ABCDEFGHIJKLMNOPQRSTUVWXYZ 123456789";
    db.setSetting("heart.device.name", longName);
    design.page(6);
    TextView name = (TextView) design.field("deviceName");
    check(
        name.getMaxLines() == 2 && name.getEllipsize() != null,
        "long device name bounded and ellipsized");
    check(longName.contentEquals(name.getContentDescription()), "full device name accessible");
    geometry(body());
    shot("v16-device-long-name");
    View status = (View) design.field("deviceStatus");
    int oldHeight = status.getHeight();
    test.runOnMainSync(() -> app.ble.status = "正在重新连接 · 请保持设备靠近手机");
    call("updateDevices");
    check(status.getHeight() == oldHeight, "connection status update has stable row height");
    test.runOnMainSync(() -> app.ble.status = "正在搜索");
    call("updateDevices");
    TextView statusText = (TextView) status;
    View spinner = (View) design.field("connectionProgress");
    int[] statusPosition = new int[2], spinnerPosition = new int[2];
    test.runOnMainSync(
        () -> {
          statusText.getLocationOnScreen(statusPosition);
          spinner.getLocationOnScreen(spinnerPosition);
        });
    android.graphics.Paint.FontMetrics fm = statusText.getPaint().getFontMetrics();
    float textCenter = statusPosition[1] + statusText.getBaseline() + (fm.ascent + fm.descent) / 2;
    float spinnerCenter = spinnerPosition[1] + spinner.getHeight() / 2f;
    check(
        spinner.getVisibility() == View.VISIBLE
            && Math.abs(textCenter - spinnerCenter) <= AppViews.dp(activity, 3),
        "single-line connection status aligns with the progress indicator");
    JSONObject day =
        new JSONObject()
            .put("date", LocalDate.now().minusDays(1).toString())
            .put("updated", System.currentTimeMillis())
            .put("intake", 1800)
            .put("expenditure", JSONObject.NULL)
            .put("steps", 12345)
            .put("foodComplete", false);
    db.setSetting("energy.day." + LocalDate.now().minusDays(1), day.toString());
    design.page(18);
    geometry(body());
    label(body(), "12,345 步");
    checks++;
    for (String label : new String[] {"趋势指标：消耗", "趋势指标：摄入", "趋势指标：步数", "趋势范围：近7天", "趋势范围：近30天"}) {
      click(label(body(), label));
      geometry(body());
    }
    call("energyHistory");
    shot("v16-energy-history");
    geometry(dialog().getWindow().getDecorView());
    dismiss(dialog());
    design.page(18);
    test.runOnMainSync(
        () -> {
          try {
            ScrollView sc = (ScrollView) design.field("scroll");
            sc.fullScroll(View.FOCUS_DOWN);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    test.waitForIdleSync();
    SystemClock.sleep(200);
    shot("v16-energy-bottom");
    db.setSetting("heart.device.address", "");
    db.setSetting("heart.device.name", "");
    db.setSetting("workoutPreparation.kind", "综合");
    return checks;
  }
}
