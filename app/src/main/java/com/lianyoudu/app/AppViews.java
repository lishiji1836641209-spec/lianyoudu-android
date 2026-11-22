package com.lianyoudu.app;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;

final class AppViews {
  static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable round(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  static Drawable button(int color, int fg, float radius) {
    GradientDrawable mask = round(Color.WHITE, radius);
    return new RippleDrawable(
        android.content.res.ColorStateList.valueOf((fg & 0x00ffffff) | 0x18000000),
        round(color, radius),
        mask);
  }

  static final class Equipment extends View {
    private final Bitmap sheet;
    private final int index;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final Rect src = new Rect();
    private final RectF dest = new RectF();

    Equipment(Context c, Bitmap b, int i) {
      super(c);
      sheet = b;
      index = i;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      if (sheet == null) return;
      clip.reset();
      dest.set(0, 0, getWidth(), getHeight());
      clip.addRoundRect(
          dest,
          dp(getContext(), GlassEffects.CONTROL),
          dp(getContext(), GlassEffects.CONTROL),
          Path.Direction.CW);
      c.save();
      c.clipPath(clip);
      int w = sheet.getWidth() / 3, h = sheet.getHeight() / 2;
      src.set(index % 3 * w, index / 3 * h, (index % 3 + 1) * w, (index / 3 + 1) * h);
      c.drawBitmap(sheet, src, dest, p);
      c.restore();
    }
  }

  static final class WeightChart extends View {
    private final java.util.List<Store.Weight> data;
    private final int ink, accent, line;
    private final Path path = new Path();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeightChart(Context c, List<Store.Weight> rows, int ink, int accent, int line) {
      super(c);
      this.data = new ArrayList<>(rows.subList(0, Math.min(90, rows.size())));
      Collections.reverse(data);
      this.ink = ink;
      this.accent = accent;
      this.line = line;
      setContentDescription("最近" + data.size() + "条体重记录趋势，详细数值见下方列表");
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float den = getResources().getDisplayMetrics().density;
      float l = 48 * den, r = getWidth() - 12 * den, t = 26 * den, b = getHeight() - 38 * den;
      p.setTypeface(Typeface.DEFAULT);
      p.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
      p.setColor(ink);
      if (data.isEmpty()) {
        c.drawText("暂无数据", l, (t + b) / 2, p);
        return;
      }
      double min = Double.MAX_VALUE, max = -1;
      for (Store.Weight w : data) {
        min = Math.min(min, w.kg);
        max = Math.max(max, w.kg);
      }
      min -= .5;
      max += .5;
      long d0 = java.time.LocalDate.parse(data.get(0).date).toEpochDay(),
          d1 = java.time.LocalDate.parse(data.get(data.size() - 1).date).toEpochDay();
      for (int i = 0; i < 3; i++) {
        double value = min + (max - min) * i / 2;
        float y = b - (b - t) * i / 2;
        p.setColor(line);
        p.setStrokeWidth(den);
        c.drawLine(l, y, r, y, p);
        p.setColor(ink);
        c.drawText(String.format(Locale.ROOT, "%.1f", value), 0, y + 4 * den, p);
      }
      path.reset();
      for (int i = 0; i < data.size(); i++) {
        Store.Weight w = data.get(i);
        float x =
            d0 == d1
                ? (l + r) / 2
                : l
                    + 5 * den
                    + (r - l - 10 * den)
                        * (java.time.LocalDate.parse(w.date).toEpochDay() - d0)
                        / (d1 - d0);
        float y = (float) (b - (w.kg - min) / (max - min) * (b - t));
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
        p.setColor(accent);
        c.drawCircle(x, y, 3 * den, p);
      }
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(2 * den);
      p.setColor(accent);
      c.drawPath(path, p);
      p.setStyle(Paint.Style.FILL);
      p.setColor(ink);
      c.drawText(data.get(0).date.substring(5), l, getHeight() - 12 * den, p);
      if (data.size() > 1) {
        String date = data.get(data.size() - 1).date.substring(5);
        c.drawText(date, r - p.measureText(date), getHeight() - 12 * den, p);
      }
      c.drawText("千克", 0, 14 * den, p);
    }
  }

  static final class PageScrollView extends ScrollView {
    boolean sampleContentOnly;

    PageScrollView(Context context) {
      super(context);
    }

    void drawBackdrop(Canvas canvas) {
      if (sampleContentOnly) {
        // The viewport's own draw pass applies native edge stretch to the recording canvas.
        // Draw the scrolling children directly so only the page gets that edge feedback.
        dispatchDraw(canvas);
      } else {
        draw(canvas);
      }
    }
  }

  /** Live optical surface and direct-manipulation navigation. */
  static final class GlassBar extends FrameLayout implements ViewTreeObserver.OnPreDrawListener {
    private final LinearLayout controls;
    private final LiveBackdropView backdrop, selection;
    private final List<Runnable> actions = new ArrayList<>();
    private int current, origin, pointer = -1;
    private boolean dragging;
    private boolean gestureMotion;
    private float startX, startY;
    private final int slop;
    private int accentColor, mutedColor, hover = -1;
    private boolean committingDrag;
    private float[] releasePose;

    void releaseFromDrag(float[] pose) {
      releasePose = pose;
    }

    float[] dragReleasePose() {
      return new float[] {
        selection.getTranslationX() / cell(),
        selection.getScaleX(),
        selection.getScaleY(),
        selection.getTranslationZ()
      };
    }

    boolean isCommittingDrag() {
      return committingDrag;
    }

    GlassBar(Context c, View source, boolean dark, boolean solid) {
      super(c);
      slop = ViewConfiguration.get(c).getScaledTouchSlop();
      setBackground(round(dark ? 0x5524252E : 0x44FFFFFF, dp(c, 35)));
      setClipToOutline(false);
      setClipChildren(false);
      setClipToPadding(false);
      backdrop = new LiveBackdropView(c, source, dark, solid, dp(c, 35));
      addView(backdrop, new LayoutParams(-1, -1));
      View tint = new View(c);
      GradientDrawable rim = round(dark ? 0x2024252E : 0x10FFFFFF, dp(c, 35));
      rim.setStroke(dp(c, 1), dark ? 0x33FFFFFF : 0xD0FFFFFF);
      tint.setBackground(rim);
      addView(tint, new LayoutParams(-1, -1));
      selection = new LiveBackdropView(c, source, dark, solid, dp(c, 28), true);
      selection.sharedSource = backdrop;
      GradientDrawable lens = round(dark ? 0x15FFFFFF : 0x15FFFFFF, dp(c, 28));
      lens.setStroke(dp(c, 1), dark ? 0x55FFFFFF : 0xE0FFFFFF);
      selection.setForeground(lens);
      addView(selection, new LayoutParams(0, -1));

      controls = new LinearLayout(c);
      controls.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
      addView(controls, new LayoutParams(-1, -1));

      setElevation(dp(c, 2));
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      // Register before child optical nodes: drawing one source must not clear the other's dirty
      // state.
      getViewTreeObserver().addOnPreDrawListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
      if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
      super.onDetachedFromWindow();
    }

    @Override
    public boolean onPreDraw() {
      if (!backdrop.solid && backdrop.source.isDirty()) {
        backdrop.sourceChanged = true;
        selection.sourceChanged = true;
      }
      return true;
    }

    void tab(String label, boolean selected, int accent, int muted, Runnable action) {
      accentColor = accent;
      mutedColor = muted;
      TextView view = new TextView(getContext());
      view.setText(label);
      view.setTextSize(11);
      view.setIncludeFontPadding(false);
      Visuals.Icon icon = new Visuals.Icon(label, selected ? accent : muted);
      icon.setBounds(0, 0, dp(getContext(), 22), dp(getContext(), 22));
      view.setCompoundDrawables(null, icon, null, null);
      view.setCompoundDrawablePadding(dp(getContext(), 4));
      view.setPadding(0, dp(getContext(), 5), 0, 0);
      view.setTextColor(selected ? accent : muted);
      view.setGravity(Gravity.CENTER);
      view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      view.setContentDescription(label + (selected ? "，已选" : ""));
      view.setSelected(selected);
      view.setClickable(true);
      view.setFocusable(true);
      view.setBackgroundColor(Color.TRANSPARENT);
      view.setOnClickListener(v -> action.run());
      actions.add(action);
      controls.addView(view, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private float cell() {
      return Math.max(1, (getWidth() - dp(getContext(), 12)) / Math.max(1, actions.size()));
    }

    void selectTab(int index) {
      tintHover(index);
      for (int i = 0; i < controls.getChildCount(); i++) {
        TextView tab = (TextView) controls.getChildAt(i);
        boolean selected = i == index;
        if (tab.isSelected() != selected) {
          tab.setSelected(selected);
          tab.setContentDescription(tab.getText() + (selected ? "，已选" : ""));
        }
      }
    }

    void animateSelection(int from, int to) {
      current = to;
      if (getVisibility() != View.VISIBLE) return;
      Runnable position =
          () -> {
            LayoutParams old = (LayoutParams) selection.getLayoutParams();
            int width = Math.round(cell()), height = getHeight() - dp(getContext(), 12);
            boolean sizeChanged = old.width != width || old.height != height;
            if (sizeChanged) {
              LayoutParams p = new LayoutParams(width, height);
              p.leftMargin = dp(getContext(), 6);
              p.topMargin = dp(getContext(), 6);
              selection.setLayoutParams(p);
            }
            // Drag release already started in ACTION_UP, before the destination page is built.
            // Never reset its pose or enqueue a second animation a frame later.
            if (committingDrag) {
              releasePose = null;
              return;
            }
            // Live-data refreshes must not restart an in-flight release with another duration.
            if (!sizeChanged && from == to && releasePose == null) return;
            if (releasePose != null && HealthDesign.motion(getContext())) {
              cancelControlAnimations();
              liftControls(selection.getTranslationX() / cell());
              selection.setTranslationX(cell() * releasePose[0]);
              selection.setScaleX(releasePose[1]);
              selection.setScaleY(releasePose[2]);
              selection.setTranslationZ(releasePose[3]);
              releasePose = null;
            } else if (old.width == 0) selection.setTranslationX(cell() * from);
            settle(to);
          };
      if (getWidth() > 0 && getHeight() > 0) position.run();
      else post(position);
    }

    private void settle(int index) {
      settle(index, HealthDesign.motion(getContext()));
    }

    private void settle(int index, boolean motion) {
      selection.animate().cancel();
      android.animation.TimeInterpolator easing = t -> 1 - (float) Math.pow(1 - t, 4);
      long duration = motion ? 380 : 0;
      // Keep both the lens and live labels in their original layer order throughout release.
      // Previously the last frame removed an optical label copy and brought controls forward.
      selection
          .animate()
          .translationX(cell() * index)
          .scaleX(1)
          .scaleY(1)
          .translationZ(0)
          .setDuration(duration)
          .setInterpolator(easing)
          .start();
      for (int i = 0; i < controls.getChildCount(); i++) {
        View tab = controls.getChildAt(i);
        tab.animate().cancel();
        tab.animate().scaleX(1).scaleY(1).setDuration(duration).setInterpolator(easing).start();
      }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        // Read the preference once per gesture, never from SQLite on every MOVE or release.
        gestureMotion = HealthDesign.motion(getContext());
        pointer = event.getPointerId(0);
        startX = event.getX();
        startY = event.getY();
        origin = current;
        dragging = false;
      }
      if (event.getPointerCount() > 1) {
        cancelDrag();
        return false;
      }
      if (event.getActionMasked() == MotionEvent.ACTION_MOVE
          && pointer >= 0
          && Math.abs(event.getX() - startX) > slop
          && Math.abs(event.getX() - startX) > Math.abs(event.getY() - startY) * 1.2f) {
        dragging = true;
        selection.animate().cancel();
        cancelControlAnimations();
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
      return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (event.getPointerCount() > 1) {
        cancelDrag();
        return true;
      }
      if (!dragging) return super.onTouchEvent(event);
      if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
        float width = cell();
        float x =
            Math.max(
                0,
                Math.min(
                    width * (actions.size() - 1), event.getX() - dp(getContext(), 6) - width / 2));
        float stretch = Math.min(.10f, Math.abs(x - selection.getTranslationX()) / width * .3f);
        selection.setTranslationX(x);
        boolean motion = gestureMotion;
        if (motion) liftControls(x / width);
        selection.setScaleX(motion ? 1.18f + stretch * .4f : 1);
        selection.setScaleY(motion ? 1.22f : 1);
        selection.setTranslationZ(0);
        int over = Math.max(0, Math.min(actions.size() - 1, Math.round(x / width)));
        tintHover(over);
        invalidate();
        return true;
      }
      if (event.getActionMasked() == MotionEvent.ACTION_UP) {
        int target =
            Math.max(
                0, Math.min(actions.size() - 1, Math.round(selection.getTranslationX() / cell())));
        dragging = false;
        pointer = -1;
        // ViewPropertyAnimator's first display frame can start at fraction zero. Move the
        // optical pose a small amount immediately so release has visible response that frame.
        if (gestureMotion) {
          float handoff = .025f;
          selection.setTranslationX(
              selection.getTranslationX()
                  + (cell() * target - selection.getTranslationX()) * handoff);
          selection.setScaleX(1 + (selection.getScaleX() - 1) * (1 - handoff));
          selection.setScaleY(1 + (selection.getScaleY() - 1) * (1 - handoff));
          selection.setTranslationZ(selection.getTranslationZ() * (1 - handoff));
        }
        if (target != origin) {
          performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
          committingDrag = true;
          settle(target, gestureMotion);
          try {
            actions.get(target).run();
          } finally {
            committingDrag = false;
          }
        } else settle(target, gestureMotion);
        return true;
      }
      if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        cancelDrag();
        return true;
      }
      return true;
    }

    private void liftControls(float position) {
      // Actual labels remain sharp above the refracting background; never duplicate glyphs.
      for (int i = 0; i < controls.getChildCount(); i++) {
        View tab = controls.getChildAt(i);
        float focus = Math.max(0, 1 - Math.abs(position - i));
        tab.setScaleX(1 + .08f * focus);
        tab.setScaleY(1 + .08f * focus);
      }
    }

    private void cancelControlAnimations() {
      for (int i = 0; i < controls.getChildCount(); i++) controls.getChildAt(i).animate().cancel();
    }

    private void tintHover(int index) {
      if (index == hover) return;
      int previous = hover;
      hover = index;
      for (int i : new int[] {previous, index}) {
        if (i < 0 || i >= controls.getChildCount()) continue;
        TextView tab = (TextView) controls.getChildAt(i);
        int color = i == index ? accentColor : mutedColor;
        tab.setTextColor(color);
        android.graphics.drawable.Drawable icon = tab.getCompoundDrawables()[1];
        if (icon instanceof Visuals.Icon) ((Visuals.Icon) icon).color(color);
      }
    }

    private void cancelDrag() {
      tintHover(current);
      if (dragging) settle(origin, gestureMotion);
      dragging = false;
      pointer = -1;
    }

    void refresh() {
      if (backdrop.solid) return;
      backdrop.sourceChanged = true;
      selection.sourceChanged = true;
      invalidate();
    }

    void setSolid(boolean solid) {
      backdrop.setSolid(solid);
      selection.setSolid(solid);
    }

    long liveFramesForAudit() {
      return backdrop.frames;
    }
  }
}
