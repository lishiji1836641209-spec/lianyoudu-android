package com.lianyoudu.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.InputStream;
import java.util.*;

/** Local, illustrated catalog images. Decode each atlas once at bounded resolution. */
final class GuideImages extends View {
  // Atlas cells are bound to this frozen identity order, independent of catalog order.
  static final List<String> IDS = List.of(
      "squat",
      "push",
      "aerobics",
      "stretch",
      "plank",
      "row",
      "band",
      "bell",
      "bench",
      "pull",
      "press",
      "leg",
      "run",
      "trainer",
      "bike",
      "walk",
      "reverse_lunge",
      "glute_bridge",
      "calf_raise",
      "wall_sit",
      "dead_bug",
      "bird_dog",
      "side_plank",
      "mountain_climber",
      "db_goblet_squat",
      "db_rdl",
      "db_split_squat",
      "step_up",
      "prone_leg_curl",
      "leg_extension",
      "hip_abduction",
      "back_extension",
      "db_shoulder_press",
      "db_lateral_raise",
      "db_curl",
      "db_triceps_extension",
      "db_floor_press",
      "cable_row",
      "cable_face_pull",
      "assisted_pullup",
      "elliptical",
      "stationary_bike",
      "jump_rope",
      "cat_cow",
      "hamstring_stretch",
      "hip_flexor_stretch",
      "chest_stretch",
      "calf_stretch",
      "incline_pushup",
      "kneeling_pushup",
      "chair_squat",
      "lateral_lunge",
      "barbell_rdl",
      "barbell_row",
      "cable_fly",
      "straight_arm_pulldown",
      "hammer_curl",
      "db_front_raise",
      "db_reverse_fly",
      "db_shrug",
      "seated_calf_raise",
      "crunch",
      "heel_touch",
      "child_pose",
      "supported_leg_raise",
      "incline_treadmill");
  private static final Map<String, String> SINGLE_IMAGES = Map.of(
      "aerobics", "aerobics-v13.png",
      "supported_leg_raise", "supported-leg-raise-v15.png",
      "incline_treadmill", "incline-treadmill-v6.png");
  private static final Map<String, Bitmap> atlases = new HashMap<>();
  static final List<String> CORRECTED =
      List.of(
          "kneeling_pushup",
          "hip_abduction",
          "cable_face_pull",
          "hamstring_stretch",
          "db_front_raise",
          "db_shrug",
          "straight_arm_pulldown",
          "hammer_curl");
  private static final int[] ORIGINAL_ROWS = {0, 313, 613, 931, 1254};
  private static final int[] LOWER_ROWS = {0, 313, 613, 914, 1254};
  private static final int[] UPPER_ROWS = {0, 321, 628, 932, 1254};
  private final String file;
  private Bitmap atlas;
  private static final java.util.concurrent.ExecutorService decoder =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private final int index, columns;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Rect source = new Rect();
  private final RectF target = new RectF();
  private final Path clip = new Path();

  GuideImages(Context c, boolean food, int index) {
    super(c);
    boolean exercise = !food && index >= 0 && index < IDS.size();
    String singleImage = exercise ? SINGLE_IMAGES.get(IDS.get(index)) : null;
    boolean single = singleImage != null;
    int corrected = exercise ? CORRECTED.indexOf(IDS.get(index)) : -1;
    this.index =
        single
            ? 0
            : corrected >= 0
                ? corrected
                : food ? (index < 64 ? index : (index - 64) % 16) : index < 0 ? index : index % 16;
    file =
        single
            ? singleImage
            : food
                ? (index < 64 ? "foods.png" : "foods-v15-" + (1 + (index - 64) / 16) + ".png")
                : corrected >= 0
                    ? "exercises-corrected-v11.png"
                    : index < 16
                        ? "exercises.png"
                        : index < 32
                            ? "exercises-lower-v08.png"
                            : index < 48 ? "exercises-upper-v08.png" : "exercises-v10.png";
    columns = single ? 1 : food && index < 64 ? 8 : 4;
    synchronized (GuideImages.class) {
      atlas = atlases.get(file);
    }
    if (atlas == null && index >= 0) {
      java.lang.ref.WeakReference<GuideImages> target = new java.lang.ref.WeakReference<>(this);
      Context app = c.getApplicationContext();
      decoder.execute(
          () -> {
            Bitmap bitmap;
            synchronized (GuideImages.class) {
              bitmap = atlases.get(file);
            }
            if (bitmap == null) {
              bitmap = decode(app, file);
              synchronized (GuideImages.class) {
                if (bitmap != null) atlases.put(file, bitmap);
              }
            }
            Bitmap ready = bitmap;
            new android.os.Handler(android.os.Looper.getMainLooper())
                .post(
                    () -> {
                      GuideImages view = target.get();
                      if (view != null) {
                        view.atlas = ready;
                        view.invalidate();
                      }
                    });
          });
    }
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  static Bitmap decode(Context c, String name) {
    try (InputStream in = c.getAssets().open("guides/" + name)) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inPreferredConfig = Bitmap.Config.RGB_565;
      return BitmapFactory.decodeStream(in, null, options);
    } catch (Exception ignored) {
      return null;
    }
  }

  static GuideImages exercise(Context c, Domain.Exercise e) {
    return new GuideImages(c, false, IDS.indexOf(e.id));
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    target.set(0, 0, getWidth(), getHeight());
    clip.reset();
    clip.addRoundRect(
        target, AppViews.dp(getContext(), 12), AppViews.dp(getContext(), 12), Path.Direction.CW);
    c.save();
    c.clipPath(clip);
    c.drawColor(0xFFF5F2EB);
    if (index < 0) {
      // A neutral missing-image mark scales with the available region at any system font size.
      float size = Math.min(getWidth(), getHeight()) * 0.42f;
      float x = (getWidth() - size) / 2f, y = (getHeight() - size) / 2f;
      paint.setColor(0xFF6B6B74); paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Math.max(1f, size * 0.06f));
      c.drawRoundRect(x, y, x + size, y + size, size * 0.12f, size * 0.12f, paint);
      c.drawLine(x + size * 0.2f, y + size * 0.2f, x + size * 0.8f, y + size * 0.8f, paint);
      paint.setStyle(Paint.Style.FILL);
    } else if (atlas != null) {
      int width = atlas.getWidth(), height = atlas.getHeight();
      source.set(
          index % columns * width / columns,
          index / columns * height / columns,
          (index % columns + 1) * width / columns,
          (index / columns + 1) * height / columns);
      if (file.equals("exercises.png")
          || file.equals("exercises-lower-v08.png")
          || file.equals("exercises-upper-v08.png")) {
        int[] rows =
            file.equals("exercises.png")
                ? ORIGINAL_ROWS
                : file.equals("exercises-lower-v08.png") ? LOWER_ROWS : UPPER_ROWS;
        source.top = rows[index / 4] * height / 1254;
        source.bottom = rows[index / 4 + 1] * height / 1254;
        source.inset(2, 2);
      }
      if (file.equals("exercises-v10.png")) source.inset(2, 2);
      if (file.equals("exercises-corrected-v11.png")) {
        // Measured atlas row boundary: 407 / 887, not an assumed equal-height grid.
        source.top = index < 4 ? 0 : 410 * height / 887;
        source.bottom = index < 4 ? 405 * height / 887 : height;
        source.inset(3, 2);
      }
      int w = source.width(), h = source.height();
      // Preserve the image's aspect ratio: no stretched bodies or dishes.
      float scale = Math.min(getWidth() / (float) w, getHeight() / (float) h);
      float dw = w * scale, dh = h * scale;
      target.set(
          (getWidth() - dw) / 2,
          (getHeight() - dh) / 2,
          (getWidth() + dw) / 2,
          (getHeight() + dh) / 2);
      c.drawBitmap(atlas, source, target, paint);
    }
    c.restore();
  }

  static String guidance(Domain.Exercise e) {
    String note = ExerciseCatalog.countNote(e);
    return ExerciseLearning.basic(e) + (note.isEmpty() ? "" : "\n\n" + note);
  }
}
