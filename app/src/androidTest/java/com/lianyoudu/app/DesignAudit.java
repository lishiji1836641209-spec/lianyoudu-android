package com.lianyoudu.app;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual display-list pixels, gesture injection, all 12 pages, and owned-dialog geometry. */
final class DesignAudit {
  static final class CountingScroll extends ScrollView {
    int draws;

    CountingScroll(Activity activity) {
      super(activity);
    }

    @Override
    public void draw(Canvas canvas) {
      draws++;
      super.draw(canvas);
    }
  }

  final Instrumentation test;
  final Activity activity;
  final FitnessApp app;
  int checks;

  DesignAudit(Instrumentation t, Activity a, FitnessApp app) {
    test = t;
    activity = a;
    this.app = app;
  }

  void check(boolean result, String message) {
    if (!result) throw new AssertionError(message);
    checks++;
  }

  Object field(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  void page(int number) throws Exception {
    test.runOnMainSync(
        () -> {
          try {
            Field p = MainActivity.class.getDeclaredField("page");
            p.setAccessible(true);
            p.setInt(activity, number);
            Method render = MainActivity.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(activity);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    test.waitForIdleSync();
    // Capture the settled page, after the 460 ms glass transition has completed.
    Thread.sleep(700);
  }

  void shot(String name) throws Exception {
    Bitmap image = test.getUiAutomation().takeScreenshot();
    try (FileOutputStream out =
        new FileOutputStream(new File(activity.getFilesDir(), name + ".png"))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    image.recycle();
  }

  void swipe(AppViews.GlassBar nav, int from, int to, boolean cancel) throws Exception {
    int[] pos = new int[2];
    test.runOnMainSync(() -> nav.getLocationOnScreen(pos));
    float x0 = pos[0] + nav.getWidth() * (from + .5f) / 4f,
        x1 = pos[0] + nav.getWidth() * (to + .5f) / 4f,
        y = pos[1] + nav.getHeight() / 2f;
    long start = SystemClock.uptimeMillis();
    for (int i = 0; i <= 16; i++) {
      int action = i == 0 ? 0 : i == 16 ? (cancel ? 3 : 1) : 2;
      MotionEvent e =
          MotionEvent.obtain(
              start, SystemClock.uptimeMillis(), action, x0 + (x1 - x0) * i / 16, y, 0);
      test.getUiAutomation().injectInputEvent(e, true);
      e.recycle();
      if (i == 8 && from == 0 && to == 3 && !cancel) {
        Field lensField = AppViews.GlassBar.class.getDeclaredField("selection");
        lensField.setAccessible(true);
        View lens = (View) lensField.get(nav);
        check(
            lens.getScaleX() > 1.1f && lens.getScaleY() > 1.1f,
            "drag enlarges optical lens in both axes");
        shot("drag-lens-mid");
      }
      Thread.sleep(12);
    }
    if (from == 0 && to == 3 && !cancel) checkReleaseMotion();
    test.waitForIdleSync();
    Thread.sleep(180);
  }

  void checkReleaseMotion() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    java.util.List<Float> scales = new ArrayList<>();
    java.util.List<Boolean> stableLayers = new ArrayList<>();
    test.runOnMainSync(
        () -> {
          try {
            AppViews.GlassBar latest = (AppViews.GlassBar) field("nav");
            Field f = AppViews.GlassBar.class.getDeclaredField("selection");
            f.setAccessible(true);
            View lens = (View) f.get(latest);
            Choreographer.getInstance()
                .postFrameCallback(
                    new Choreographer.FrameCallback() {
                      long start;

                      public void doFrame(long frame) {
                        if (start == 0) start = frame;
                        scales.add(lens.getScaleY());
                        stableLayers.add(
                            latest.indexOfChild(lens) < latest.getChildCount() - 1
                                && ((LiveBackdropView) lens).overlay == null);
                        if (frame - start < 650_000_000L)
                          Choreographer.getInstance().postFrameCallback(this);
                        else done.countDown();
                      }
                    });
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    check(done.await(3, java.util.concurrent.TimeUnit.SECONDS), "release motion completes");
    check(scales.size() >= 8, "release sampled across display frames");
    check(
        stableLayers.stream().allMatch(Boolean::booleanValue),
        "release never swaps text layers or duplicates glyphs, including final frame");
    check(scales.get(0) > 1.03f, "release preserves enlargement after navigation");
    boolean continuous = true;
    for (int i = 1; i < scales.size(); i++)
      continuous &=
          scales.get(i) >= .999f
              && scales.get(i) <= scales.get(i - 1) + .002f
              && scales.get(i - 1) - scales.get(i) < .08f;
    check(continuous, "release shrinks continuously without undershoot or jumps");
    check(Math.abs(scales.get(scales.size() - 1) - 1) < .002f, "release rests at natural size");
    try (FileWriter out = new FileWriter(new File(activity.getFilesDir(), "release-motion.txt"))) {
      out.write(scales.toString());
    }
  }

  void firstReleaseFrames() throws Exception {
    page(0);
    AppViews.GlassBar nav = (AppViews.GlassBar) field("nav");
    Field lensField = AppViews.GlassBar.class.getDeclaredField("selection");
    lensField.setAccessible(true);
    View lens = (View) lensField.get(nav);
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    java.util.List<String> frames = new ArrayList<>();
    long[] release = new long[1];
    float[] initial = new float[1];
    long[] moved = {-1};
    test.runOnMainSync(
        () -> {
          long down = SystemClock.uptimeMillis();
          float y = nav.getHeight() / 2f;
          for (int step = 0; step <= 8; step++) {
            MotionEvent event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    step == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE,
                    nav.getWidth() * (.125f + .75f * step / 8),
                    y,
                    0);
            nav.dispatchTouchEvent(event);
            event.recycle();
          }
          initial[0] = lens.getScaleY();
          release[0] = SystemClock.elapsedRealtimeNanos();
          MotionEvent up =
              MotionEvent.obtain(
                  down,
                  SystemClock.uptimeMillis(),
                  MotionEvent.ACTION_UP,
                  nav.getWidth() * .875f,
                  y,
                  0);
          nav.dispatchTouchEvent(up);
          up.recycle();
          Choreographer.getInstance()
              .postFrameCallback(
                  new Choreographer.FrameCallback() {
                    public void doFrame(long frame) {
                      long elapsed = SystemClock.elapsedRealtimeNanos() - release[0];
                      frames.add(elapsed / 1e6 + "," + lens.getScaleY());
                      if (moved[0] < 0 && lens.getScaleY() < initial[0] - .003f) moved[0] = elapsed;
                      if (elapsed < 600_000_000L)
                        Choreographer.getInstance().postFrameCallback(this);
                      else done.countDown();
                    }
                  });
        });
    check(
        done.await(3, java.util.concurrent.TimeUnit.SECONDS),
        "first release frame probe completes");
    check(initial[0] > 1.1f, "probe starts from actual dragged lens");
    check(
        !frames.isEmpty() && Float.parseFloat(frames.get(0).split(",")[1]) < initial[0] - .003f,
        "first observed frame starts release rather than holding drag pose");
    // An input frame plus one display interval is allowed; there must be no extra posted reset.
    check(
        moved[0] >= 0 && moved[0] < 65_000_000L,
        "release begins shrinking within two 60Hz frame intervals plus page work");
    check(nav == field("nav"), "release keeps the same attached optical node across navigation");
    try (FileWriter out =
        new FileWriter(new File(activity.getFilesDir(), "release-first-frames.csv"))) {
      out.write("milliseconds,scaleY\n" + String.join("\n", frames));
    }
  }

  void run() throws Exception {
    for (String theme : new String[] {"light", "dark"}) {
      app.store.setSetting("theme", theme);
      for (int number : new int[] {0, 1, 2, 3, 6, 7, 8, 9, 11, 12, 14, 15, 16, 17, 18, 19}) {
        page(number);
        check(
            activity.findViewById(android.R.id.content).getWidth() > 0, "page laid out " + number);
        check(
            ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0)
                == field("root"),
            "page renders actual content " + theme + number);
        AppViews.GlassBar bar = (AppViews.GlassBar) field("nav");
        Field lensField = AppViews.GlassBar.class.getDeclaredField("selection");
        lensField.setAccessible(true);
        Field currentField = AppViews.GlassBar.class.getDeclaredField("current");
        currentField.setAccessible(true);
        Method cellMethod = AppViews.GlassBar.class.getDeclaredMethod("cell");
        cellMethod.setAccessible(true);
        View lens = (View) lensField.get(bar);
        float target = (Float) cellMethod.invoke(bar) * currentField.getInt(bar);
        if (bar.getVisibility() == View.VISIBLE)
          check(
              Math.abs(lens.getTranslationX() - target) < 1f,
              "settled glass aligns with active tab " + theme + number);
        else
          check(number == 19 && app.active == null, "only preparation replaces global navigation");
        if (number == 0 && app.active == null) {
          ArrayList<View> starts = new ArrayList<>();
          activity
              .getWindow()
              .getDecorView()
              .findViewsWithText(starts, "开始", View.FIND_VIEWS_WITH_TEXT);
          boolean fits = false;
          for (View v : starts)
            if (v instanceof TextView tv && tv.getText().toString().equals("开始")) {
              fits =
                  tv.getHeight() >= AppViews.dp(activity, 48)
                      && tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight()
                          >= tv.getPaint().measureText("开始") - 1;
            }
          check(fits, "home start button fits full label at current font scale " + theme);
        }
        shot("design-" + theme + "-page-" + number);
      }
    }
    app.store.setSetting("theme", "auto");
    firstReleaseFrames();
    page(0);
    AppViews.GlassBar retainedBar = (AppViews.GlassBar) field("nav");
    Field controlsField = AppViews.GlassBar.class.getDeclaredField("controls");
    controlsField.setAccessible(true);
    LinearLayout controls = (LinearLayout) controlsField.get(retainedBar);
    android.graphics.drawable.Drawable originalIcon =
        ((TextView) controls.getChildAt(0)).getCompoundDrawables()[1];
    swipe(retainedBar, 0, 3, false);
    check(
        ((TextView) controls.getChildAt(0)).getCompoundDrawables()[1] == originalIcon,
        "hover changes tint without replacing icon or relayout");
    check(((Integer) field("page")) == 17, "drag glass selects browse");
    swipe((AppViews.GlassBar) field("nav"), 3, 0, true);
    check(((Integer) field("page")) == 17, "cancel drag retains page");
    swipe((AppViews.GlassBar) field("nav"), 3, 0, false);
    check(((Integer) field("page")) == 0, "reverse drag selects today");
    FrameLayout scene = new FrameLayout(activity);
    ScrollView source = new CountingScroll(activity);
    source.setBackgroundColor(Color.RED);
    scene.addView(source, new FrameLayout.LayoutParams(-1, -1));
    AppViews.GlassBar glass = new AppViews.GlassBar(activity, source, false, false);
    FrameLayout.LayoutParams p =
        new FrameLayout.LayoutParams(-1, AppViews.dp(activity, 64), Gravity.BOTTOM);
    p.setMargins(24, 0, 24, 140);
    scene.addView(glass, p);
    test.runOnMainSync(
        () -> {
          for (int i = 0; i < 4; i++) glass.tab("", i == 0, Color.BLACK, Color.GRAY, () -> {});
          activity.setContentView(scene);
          glass.animateSelection(0, 0);
        });
    test.waitForIdleSync();
    Thread.sleep(250);
    int[] position = new int[2];
    test.runOnMainSync(() -> glass.getLocationOnScreen(position));
    int x = position[0] + glass.getWidth() / 2, y = position[1] + glass.getHeight() / 2;
    Bitmap red = test.getUiAutomation().takeScreenshot();
    int lensX =
        position[0]
            + AppViews.dp(activity, 6)
            + (glass.getWidth() - AppViews.dp(activity, 12)) / 16;
    int redLens = red.getPixel(lensX, y);
    int redPixel = red.getPixel(x, y);
    red.recycle();
    test.runOnMainSync(() -> source.setBackgroundColor(Color.BLUE));
    test.waitForIdleSync();
    Thread.sleep(200);
    Bitmap blue = test.getUiAutomation().takeScreenshot();
    int blueLens = blue.getPixel(lensX, y);
    int bluePixel = blue.getPixel(x, y);
    blue.recycle();
    check(
        Color.red(redPixel) > Color.blue(redPixel) && Color.blue(bluePixel) > Color.red(bluePixel),
        "glass updates actual background pixels without scrolling");
    check(
        Color.red(redLens) > Color.blue(redLens) && Color.blue(blueLens) > Color.red(blueLens),
        "stationary selection and base both refresh when source changes");
    long frames = glass.liveFramesForAudit();
    Thread.sleep(350);
    check(glass.liveFramesForAudit() - frames <= 2, "glass does not poll at idle");
    shot("live-glass-blue");
    test.runOnMainSync(
        () -> {
          LinearLayout bands = new LinearLayout(activity);
          bands.setOrientation(LinearLayout.VERTICAL);
          for (int color : new int[] {Color.RED, Color.GREEN, Color.BLUE}) {
            View band = new View(activity);
            band.setBackgroundColor(color);
            bands.addView(band, new LinearLayout.LayoutParams(-1, scene.getHeight()));
          }
          source.setBackgroundColor(Color.YELLOW);
          source.addView(bands);
        });
    test.waitForIdleSync();
    Thread.sleep(200);
    Bitmap beforeScroll = test.getUiAutomation().takeScreenshot();
    int beforeScrollPixel = beforeScroll.getPixel(x, y);
    beforeScroll.recycle();
    test.runOnMainSync(() -> source.scrollTo(0, scene.getHeight()));
    test.waitForIdleSync();
    Thread.sleep(200);
    Bitmap afterScroll = test.getUiAutomation().takeScreenshot();
    int afterScrollPixel = afterScroll.getPixel(x, y);
    afterScroll.recycle();
    check(
        Color.red(beforeScrollPixel) > Color.blue(beforeScrollPixel)
            && Color.green(afterScrollPixel) > Color.red(afterScrollPixel)
            && Color.green(afterScrollPixel) > Color.blue(afterScrollPixel),
        "glass samples scrolled viewport rather than original content position");
    test.runOnMainSync(() -> source.fullScroll(View.FOCUS_DOWN));
    test.waitForIdleSync();
    Thread.sleep(250);
    Bitmap bottom = test.getUiAutomation().takeScreenshot();
    int bottomPixel = bottom.getPixel(x, y);
    bottom.recycle();
    check(
        Color.blue(bottomPixel) - Color.red(bottomPixel) > 40,
        "bottom viewport retains blue source rather than stale or outside pixels");
    shot("glass-scroll-bottom");
    test.runOnMainSync(() -> source.scrollTo(0, 0));
    test.waitForIdleSync();
    Thread.sleep(200);
    Bitmap returned = test.getUiAutomation().takeScreenshot();
    int returnedPixel = returned.getPixel(x, y);
    returned.recycle();
    check(
        Color.red(returnedPixel) - Color.blue(returnedPixel) > 40,
        "return from bottom refreshes original viewport");
    checkGesturePreferences(glass);
    checkScrolledBackdropAlignment(scene, source, glass);
    optics();
    page(0);
    final AlertDialog[] sample = new AlertDialog[1];
    test.runOnMainSync(
        () -> {
          sample[0] =
              new DesignDialog.Builder(
                      activity, false, Color.BLACK, Color.GRAY, 0xFF7957CD, 0xFFEFE7FB)
                  .setTitle("结束本次训练？")
                  .setMessage("测试弹窗")
                  .setPositiveButton("结束并保存", null)
                  .setNegativeButton("继续训练", null)
                  .create();
          sample[0].show();
        });
    test.waitForIdleSync();
    Thread.sleep(150);
    test.runOnMainSync(
        () -> {
          AlertDialog dialog = sample[0];
          check(dialog instanceof DesignDialog, "confirmation owned by app");
          check(
              dialog.getButton(-1).getHeight() >= AppViews.dp(activity, 48),
              "primary target height");
          check(
              dialog.getButton(-2).getHeight() >= AppViews.dp(activity, 48),
              "secondary target height");
          dialog.dismiss();
        });
    try (FileWriter out = new FileWriter(new File(activity.getFilesDir(), "design-audit.txt"))) {
      out.write(
          "PASS "
              + checks
              + " design assertions; 16 pages in light and dark; live pixels; drag/reverse/cancel;"
              + " idle rendering\n");
    }
  }

  void alignmentOnly() throws Exception {
    FrameLayout scene = new FrameLayout(activity);
    ScrollView source = new CountingScroll(activity);
    scene.addView(source, new FrameLayout.LayoutParams(-1, -1));
    AppViews.GlassBar glass = new AppViews.GlassBar(activity, source, false, false);
    FrameLayout.LayoutParams p =
        new FrameLayout.LayoutParams(-1, AppViews.dp(activity, 64), Gravity.BOTTOM);
    p.setMargins(24, 0, 24, 140);
    scene.addView(glass, p);
    test.runOnMainSync(
        () -> {
          for (int i = 0; i < 4; i++) glass.tab("", i == 0, Color.BLACK, Color.GRAY, () -> {});
          activity.setContentView(scene);
        });
    test.waitForIdleSync();
    Thread.sleep(250);
    test.runOnMainSync(() -> glass.animateSelection(0, 0));
    test.waitForIdleSync();
    Thread.sleep(450);
    checkScrolledBackdropAlignment(scene, source, glass);
  }

  private void checkScrolledBackdropAlignment(
      FrameLayout scene, ScrollView source, AppViews.GlassBar glass) throws Exception {
    for (int scrollBy : new int[] {0, AppViews.dp(activity, 170), source.getHeight()})
      checkBackdropAt(scene, source, glass, scrollBy);
    if (source instanceof CountingScroll counting) checkLensDragDraws(glass, counting);
  }

  private void checkLensDragDraws(AppViews.GlassBar glass, CountingScroll source) throws Exception {
    Field controlsField = AppViews.GlassBar.class.getDeclaredField("controls");
    controlsField.setAccessible(true);
    View controls = (View) controlsField.get(glass);
    Field selectionField = AppViews.GlassBar.class.getDeclaredField("selection");
    selectionField.setAccessible(true);
    View selection = (View) selectionField.get(glass);
    test.runOnMainSync(() -> controls.setVisibility(View.VISIBLE));
    test.waitForIdleSync();
    Thread.sleep(300);
    int before = source.draws;
    long down = SystemClock.uptimeMillis();
    for (int i = 0; i <= 12; i++) {
      int step = i;
      test.runOnMainSync(
          () -> {
            MotionEvent event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    step == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE,
                    glass.getWidth() * (.125f + .65f * step / 12),
                    glass.getHeight() / 2f,
                    0);
            glass.dispatchTouchEvent(event);
            event.recycle();
          });
      Thread.sleep(32);
    }
    float[] position = new float[1];
    test.runOnMainSync(() -> position[0] = selection.getTranslationX());
    test.runOnMainSync(
        () -> {
          MotionEvent event =
              MotionEvent.obtain(
                  down,
                  SystemClock.uptimeMillis(),
                  MotionEvent.ACTION_CANCEL,
                  glass.getWidth() * .775f,
                  glass.getHeight() / 2f,
                  0);
          glass.dispatchTouchEvent(event);
          event.recycle();
        });
    test.waitForIdleSync();
    int repeated = source.draws - before;
    check(
        position[0] > AppViews.dp(activity, 60), "lens actually moved during the draw-count probe");
    check(
        repeated <= 3,
        "lens-only drag does not redraw the underlying page on every frame: " + repeated);
  }

  private void checkBackdropAt(
      FrameLayout scene, ScrollView source, AppViews.GlassBar glass, int scrollBy)
      throws Exception {
    final int[] position = new int[2];
    final int[] sourcePosition = new int[2];
    final int[] line = new int[1];
    Field controlsField = AppViews.GlassBar.class.getDeclaredField("controls");
    controlsField.setAccessible(true);
    View controls = (View) controlsField.get(glass);
    test.runOnMainSync(
        () -> {
          glass.getLocationOnScreen(position);
          source.getLocationOnScreen(sourcePosition);
          line[0] =
              position[1]
                  - sourcePosition[1]
                  + glass.getHeight()
                  - AppViews.dp(activity, 10)
                  + scrollBy;
          View pattern =
              new View(activity) {
                final Paint p = new Paint();

                @Override
                protected void onDraw(Canvas canvas) {
                  canvas.drawColor(Color.WHITE);
                  p.setColor(Color.BLACK);
                  canvas.drawRect(0, line[0], getWidth(), line[0] + AppViews.dp(activity, 4), p);
                }
              };
          pattern.setWillNotDraw(false);
          source.removeAllViews();
          source.addView(pattern, new ScrollView.LayoutParams(-1, scene.getHeight() * 2));
          controls.setVisibility(View.INVISIBLE);
          glass.setVisibility(View.INVISIBLE);
        });
    test.waitForIdleSync();
    Thread.sleep(250);
    test.runOnMainSync(
        () -> {
          source.measure(
              View.MeasureSpec.makeMeasureSpec(source.getWidth(), View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(source.getHeight(), View.MeasureSpec.EXACTLY));
          source.layout(source.getLeft(), source.getTop(), source.getRight(), source.getBottom());
          View pattern = source.getChildAt(0);
          pattern.measure(
              View.MeasureSpec.makeMeasureSpec(source.getWidth(), View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(source.getHeight() * 2, View.MeasureSpec.EXACTLY));
          pattern.layout(0, 0, source.getWidth(), source.getHeight() * 2);
          source.scrollTo(0, scrollBy);
        });
    test.waitForIdleSync();
    Thread.sleep(200);
    Bitmap bare = test.getUiAutomation().takeScreenshot();
    test.runOnMainSync(() -> glass.setVisibility(View.VISIBLE));
    test.waitForIdleSync();
    Thread.sleep(300);
    Bitmap covered = test.getUiAutomation().takeScreenshot();
    try (FileOutputStream out =
        new FileOutputStream(new File(activity.getFilesDir(), "align-bare.png"))) {
      bare.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    try (FileOutputStream out =
        new FileOutputStream(new File(activity.getFilesDir(), "align-covered.png"))) {
      covered.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    int x = position[0] + glass.getWidth() / 2;
    int expected = position[1] + glass.getHeight() - AppViews.dp(activity, 10);
    int radius = AppViews.dp(activity, 25);
    int referenceY = darkestRow(bare, x, expected, radius);
    int glassY = darkestRow(covered, x, expected, radius);
    StringBuilder probes = new StringBuilder();
    boolean[] aligned = new boolean[4];
    for (int i = 0; i < 4; i++) {
      int sampleX = position[0] + Math.round(glass.getWidth() * (i + .5f) / 4f);
      int plain = darkestRow(bare, sampleX, expected, radius);
      int optical = darkestRow(covered, sampleX, expected, radius);
      probes.append(i).append(':').append(plain).append('/').append(optical).append(' ');
      aligned[i] = Math.abs(optical - plain) <= AppViews.dp(activity, 6);
    }
    try (FileWriter out =
        new FileWriter(new File(activity.getFilesDir(), "glass-alignment.txt"), scrollBy != 0)) {
      out.write(
          "reference="
              + referenceY
              + " glass="
              + glassY
              + " expected="
              + expected
              + " scroll="
              + source.getScrollY()
              + " line="
              + line[0]
              + " sourceY="
              + sourcePosition[1]
              + " sourceHeight="
              + source.getHeight()
              + " childHeight="
              + source.getChildAt(0).getHeight()
              + " probes="
              + probes
              + "\n");
    }
    bare.recycle();
    covered.recycle();
    for (int i = 0; i < aligned.length; i++)
      check(aligned[i], "glass layer tracks text position at tab " + i);
    check(
        Math.abs(referenceY - expected) <= AppViews.dp(activity, 2),
        "scrolled pattern reaches the intended viewport position");
    check(
        Math.abs(glassY - referenceY) <= AppViews.dp(activity, 6),
        "glass samples a thin feature at its real scrolled position");
  }

  private int darkestRow(Bitmap bitmap, int x, int center, int radius) {
    int best = center, min = Integer.MAX_VALUE;
    for (int y = Math.max(0, center - radius);
        y < Math.min(bitmap.getHeight(), center + radius);
        y++) {
      int color = bitmap.getPixel(x, y);
      int light = Color.red(color) + Color.green(color) + Color.blue(color);
      if (light < min) {
        min = light;
        best = y;
      }
    }
    return best;
  }

  private void checkGesturePreferences(AppViews.GlassBar glass) throws Exception {
    Field field = AppViews.GlassBar.class.getDeclaredField("selection");
    field.setAccessible(true);
    View lens = (View) field.get(glass);
    String original = app.store.setting("reduceMotion", "false");
    try {
      for (boolean reduced : new boolean[] {true, false}) {
        List<Throwable> diskReads = new ArrayList<>();
        float[] scale = new float[1];
        test.runOnMainSync(
            () -> {
              app.store.setSetting("reduceMotion", String.valueOf(reduced));
              long down = SystemClock.uptimeMillis();
              MotionEvent start =
                  MotionEvent.obtain(
                      down,
                      down,
                      MotionEvent.ACTION_DOWN,
                      glass.getWidth() * .125f,
                      glass.getHeight() / 2f,
                      0);
              glass.dispatchTouchEvent(start);
              start.recycle();
              StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
              try {
                StrictMode.setThreadPolicy(
                    new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .penaltyListener(Runnable::run, diskReads::add)
                        .build());
                for (int i = 1; i <= 8; i++) {
                  MotionEvent move =
                      MotionEvent.obtain(
                          down,
                          SystemClock.uptimeMillis(),
                          MotionEvent.ACTION_MOVE,
                          glass.getWidth() * (.125f + .5f * i / 8),
                          glass.getHeight() / 2f,
                          0);
                  glass.dispatchTouchEvent(move);
                  move.recycle();
                }
                scale[0] = lens.getScaleY();
              } finally {
                StrictMode.setThreadPolicy(previous);
                MotionEvent cancel =
                    MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        glass.getWidth() * .625f,
                        glass.getHeight() / 2f,
                        0);
                glass.dispatchTouchEvent(cancel);
                cancel.recycle();
              }
            });
        check(diskReads.isEmpty(), "drag MOVE performs no disk reads");
        check(
            reduced ? scale[0] == 1 : scale[0] > 1.1f,
            "each gesture reads the latest reduced-motion preference");
        long deadline = SystemClock.uptimeMillis() + 2500;
        boolean[] settled = {false};
        while (!settled[0] && SystemClock.uptimeMillis() < deadline) {
          test.runOnMainSync(
              () ->
                  settled[0] =
                      Math.abs(lens.getScaleY() - 1) < .001f
                          && Math.abs(lens.getTranslationX()) < .1f);
          Thread.sleep(32);
        }
        check(settled[0], "cancelled gesture settles before next probe");
      }
      test.waitForIdleSync();
      long frames = glass.liveFramesForAudit();
      Thread.sleep(350);
      check(glass.liveFramesForAudit() - frames <= 2, "glass stays idle after gestures settle");
    } finally {
      test.runOnMainSync(() -> app.store.setSetting("reduceMotion", original));
    }
  }

  void optics() throws Exception {
    // A black stripe outside the rim probe must be optically displaced into it.
    final float den = activity.getResources().getDisplayMetrics().density;
    FrameLayout optical = new FrameLayout(activity);
    View pattern =
        new View(activity) {
          final Paint paint = new Paint();

          protected void onDraw(Canvas c) {
            c.drawColor(Color.WHITE);
            paint.setColor(Color.BLACK);
            c.drawRect(48 * den, 0, 56 * den, getHeight(), paint);
          }
        };
    optical.addView(pattern, new FrameLayout.LayoutParams(-1, -1));
    LiveBackdropView rim = new LiveBackdropView(activity, pattern, false, false, 28 * den);
    FrameLayout.LayoutParams rimParams =
        new FrameLayout.LayoutParams((int) (160 * den), (int) (72 * den));
    rimParams.leftMargin = (int) (40 * den);
    rimParams.topMargin = (int) (150 * den);
    optical.addView(rim, rimParams);
    test.runOnMainSync(() -> activity.setContentView(optical));
    test.waitForIdleSync();
    Thread.sleep(300);
    int[] rimPosition = new int[2];
    test.runOnMainSync(() -> rim.getLocationOnScreen(rimPosition));
    Bitmap optics = test.getUiAutomation().takeScreenshot();
    int bent = optics.getPixel(rimPosition[0] + (int) (5 * den), rimPosition[1] + (int) (36 * den));
    int clear =
        optics.getPixel(rimPosition[0] + (int) (80 * den), rimPosition[1] + (int) (36 * den));
    optics.recycle();
    shot("glass-refraction-probe");
    try (FileWriter opticsLog =
        new FileWriter(new File(activity.getFilesDir(), "optics-values.txt"))) {
      opticsLog.write(
          "bent="
              + Integer.toHexString(bent)
              + " clear="
              + Integer.toHexString(clear)
              + " shaderFallback="
              + GlassEffects.shaderFallback);
    }
    check(
        Color.luminance(bent) < .55f && Color.luminance(clear) > .9f,
        "rim refracts offset stripe while clear center stays clear");
    shot("glass-refraction-probe");
  }
}
