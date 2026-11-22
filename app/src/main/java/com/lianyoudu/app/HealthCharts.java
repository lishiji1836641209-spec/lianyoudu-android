package com.lianyoudu.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

final class HealthCharts {
  static final class Heart extends View {
    final Domain.Session session;
    final int tint, muted;
    final Paint paint = new Paint(3);

    Heart(Context context, Domain.Session session, int tint, int muted) {
      super(context);
      this.session = session;
      this.tint = tint;
      this.muted = muted;
      setContentDescription("心率曲线，断线处留空，横轴为运动时间");
    }

    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (session.heartSamples.isEmpty()) return;
      float den = getResources().getDisplayMetrics().density;
      float sp = getResources().getDisplayMetrics().scaledDensity;
      paint.setStyle(Paint.Style.FILL);
      paint.setTextSize(12 * sp);
      int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
      for (Domain.HeartSample sample : session.heartSamples) {
        min = Math.min(min, sample.bpm()); max = Math.max(max, sample.bpm());
      }
      int low = Math.max(0, min - 5), high = Math.max(low + 1, max + 5);
      float textHeight = paint.descent() - paint.ascent();
      float left = Math.max(paint.measureText(String.valueOf(high)), paint.measureText("次/分")) + 12 * den;
      float right = getWidth() - 8 * den;
      float top = textHeight + 12 * den, bottom = getHeight() - textHeight - 14 * den;
      if (right <= left || bottom <= top) return;
      paint.setColor(muted);
      canvas.drawText("次/分", 0, -paint.ascent() + 2 * den, paint);
      for (int i = 0; i < 3; i++) {
        float y = top + (bottom - top) * i / 2;
        int value = Math.round(high - (high - low) * i / 2f);
        paint.setColor((muted & 0x00ffffff) | 0x30000000);
        paint.setStrokeWidth(den);
        canvas.drawLine(left, y, right, y, paint);
        paint.setColor(muted);
        canvas.drawText(String.valueOf(value), 0, y - (paint.ascent() + paint.descent()) / 2, paint);
      }
      List<Domain.HeartSample> samples = session.heartSamples;
      float[] sampleX = new float[samples.size()], sampleY = new float[samples.size()];
      for (int i = 0; i < samples.size(); i++) {
        Domain.HeartSample sample = samples.get(i);
        float ratio = Math.max(0, Math.min(1, sample.elapsed() / Math.max(1f, session.elapsed)));
        sampleX[i] = left + (right - left) * ratio;
        sampleY[i] = top + (bottom - top) * (high - sample.bpm()) / (float) (high - low);
      }
      for (int start = 0; start < samples.size();) {
        int end = start + 1;
        while (end < samples.size()) {
          Domain.HeartSample before = samples.get(end - 1), after = samples.get(end);
          if (!HeartCurve.canConnect(before.elapsed(), after.elapsed(), before.segment(), after.segment(),
              sampleX[end - 1], sampleX[end], sampleY[end - 1], sampleY[end])) break;
          end++;
        }
        int count = end - start;
        float[] x = Arrays.copyOfRange(sampleX, start, end);
        float[] y = Arrays.copyOfRange(sampleY, start, end);
        Path path = new Path();
        path.moveTo(x[0], y[0]);
        float[] controls = HeartCurve.controls(x, y);
        for (int i = 1; i < count; i++) {
          if (controls == null) path.lineTo(x[i], y[i]);
          else {
            int k = (i - 1) * 4;
            path.cubicTo(controls[k], controls[k + 1], controls[k + 2], controls[k + 3], x[i], y[i]);
          }
        }
        paint.setColor(tint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * den);
        paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) canvas.drawCircle(x[i], y[i], 2.5f * den, paint);
        start = end;
      }
      paint.setColor(muted); paint.setTextSize(12 * sp);
      String begin = "00:00", end = Domain.clock(session.elapsed);
      canvas.drawText(begin, left, getHeight() - 4 * den, paint);
      float endX = right - paint.measureText(end);
      if (endX >= left + paint.measureText(begin) + 6 * den)
        canvas.drawText(end, endX, getHeight() - 4 * den, paint);
    }

  }

  static final class DailyBars extends View {
    final List<String> labels;
    final List<Double> values;
    final int tint, muted;
    final java.util.function.IntConsumer select;
    final Paint paint = new Paint(3);
    int selected;
    float downX, downY;

    DailyBars(
        Context c,
        List<String> labels,
        List<Double> values,
        int tint,
        int muted,
        java.util.function.IntConsumer select) {
      super(c);
      this.labels = labels;
      this.values = values;
      this.tint = tint;
      this.muted = muted;
      this.select = select;
      selected = Math.max(0, values.size() - 1);
      setClickable(true);
      setFocusable(true);
      setContentDescription("每日趋势，点击柱形查看当天记录；空心标记表示无记录");
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float den = getResources().getDisplayMetrics().density,
          sp = getResources().getDisplayMetrics().scaledDensity;
      float cell = getWidth() / (float) Math.max(1, values.size()),
          baseline = getHeight() - 28 * sp,
          available = baseline - 18 * den;
      double max = 1;
      for (Double v : values) if (v != null) max = Math.max(max, v);
      for (int i = 0; i < values.size(); i++) {
        float x = cell * (i + .5f), w = Math.max(2 * den, cell * .48f);
        Double value = values.get(i);
        paint.setColor(i == selected ? tint : (tint & 0xffffff) | 0x70000000);
        if (value == null) {
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(den);
          c.drawCircle(x, baseline - 3 * den, 2 * den, paint);
          paint.setStyle(Paint.Style.FILL);
        } else {
          float h = (float) (available * value / max);
          c.drawRoundRect(
              x - w / 2,
              baseline - Math.max(2 * den, h),
              x + w / 2,
              baseline,
              3 * den,
              3 * den,
              paint);
        }
        if (labels.size() <= 7 || (i % 7 == 0 && i < labels.size() - 4) || i == labels.size() - 1) {
          paint.setColor(muted);
          paint.setTextSize(10 * sp);
          String label = labels.get(i);
          float labelX =
              Math.max(
                  0,
                  Math.min(
                      getWidth() - paint.measureText(label), x - paint.measureText(label) / 2));
          c.drawText(label, labelX, getHeight() - 5 * den, paint);
        }
      }
    }

    public boolean onTouchEvent(android.view.MotionEvent e) {
      if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
        downX = e.getX();
        downY = e.getY();
        return true;
      }
      if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
          && Math.hypot(e.getX() - downX, e.getY() - downY)
              < android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
        selected =
            Math.max(
                0,
                Math.min(
                    values.size() - 1, (int) (e.getX() / Math.max(1, getWidth()) * values.size())));
        performClick();
        select.accept(selected);
        invalidate();
        return true;
      }
      return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(
        android.view.accessibility.AccessibilityNodeInfo info) {
      super.onInitializeAccessibilityNodeInfo(info);
      info.addAction(
          android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
              .ACTION_SCROLL_BACKWARD);
      info.addAction(
          android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
              .ACTION_SCROLL_FORWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle args) {
      if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
          || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
        int next =
            Math.max(
                0,
                Math.min(
                    values.size() - 1,
                    selected
                        + (action
                                == android.view.accessibility.AccessibilityNodeInfo
                                    .ACTION_SCROLL_FORWARD
                            ? 1
                            : -1)));
        if (next == selected) return false;
        selected = next;
        select.accept(selected);
        invalidate();
        return true;
      }
      return super.performAccessibilityAction(action, args);
    }

    @Override
    public boolean onKeyDown(int key, android.view.KeyEvent event) {
      if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
          || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        return performAccessibilityAction(
            key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                ? android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            null);
      return super.onKeyDown(key, event);
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }

  static class Spark extends View {
    final List<Store.Weight> data;
    final Paint p = new Paint(3);
    final Path path = new Path();
    final int tint;

    Spark(Context c, List<Store.Weight> data, int tint) {
      super(c);
      this.data = new ArrayList<>(data.subList(0, Math.min(data.size(), 7)));
      Collections.reverse(this.data);
      this.tint = tint;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    protected void onDraw(Canvas c) {
      if (data.isEmpty()) return;
      double lo = data.stream().mapToDouble(w -> w.kg).min().orElse(0) - .1,
          hi = data.stream().mapToDouble(w -> w.kg).max().orElse(1) + .1;
      float pad = AppViews.dp(getContext(), 4);
      path.reset();
      float x = 0, y = 0;
      for (int i = 0; i < data.size(); i++) {
        x =
            data.size() == 1
                ? getWidth() / 2f
                : pad + (getWidth() - 2 * pad) * i / (data.size() - 1);
        y = (float) (pad + (getHeight() - 2 * pad) * (hi - data.get(i).kg) / (hi - lo));
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      }
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(AppViews.dp(getContext(), 2));
      p.setColor(tint);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      c.drawPath(path, p);
      p.setStyle(Paint.Style.FILL);
      c.drawCircle(x, y, AppViews.dp(getContext(), 3), p);
    }
  }

  static class Comparison extends View {
    final long before, now;
    final int tint, muted;
    final Paint p = new Paint(3);

    Comparison(Context c, long a, long b, int tint, int muted) {
      super(c);
      before = a;
      now = b;
      this.tint = tint;
      this.muted = muted;
      setContentDescription("上周" + a + "分钟，本周" + b + "分钟");
    }

    protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density,
          scale = getResources().getDisplayMetrics().scaledDensity;
      float left = 42 * d, right = getWidth() - 72 * d, max = Math.max(1, Math.max(before, now));
      p.setTextSize(12 * scale);
      for (int i = 0; i < 2; i++) {
        float y = (19 + i * 34) * d;
        long n = i == 0 ? before : now;
        p.setColor(muted);
        c.drawText(i == 0 ? "上周" : "本周", 0, y + 4 * d, p);
        if (n > 0) {
          p.setColor((tint & 0xffffff) | 0x22000000);
          c.drawRoundRect(left, y - 6 * d, right, y + 6 * d, 6 * d, 6 * d, p);
        }
        p.setColor(i == 0 ? (tint & 0xffffff) | 0x88000000 : tint);
        if (n > 0)
          c.drawRoundRect(
              left, y - 6 * d, left + (right - left) * n / max, y + 6 * d, 6 * d, 6 * d, p);
        p.setColor(muted);
        String value = n + " 分钟";
        c.drawText(value, getWidth() - p.measureText(value), y + 4 * d, p);
      }
    }
  }

  static final class Trend extends View {
    final List<Store.Weight> data;
    final Paint paint = new Paint(3);
    final Path path = new Path();
    final int tint, muted, line;
    final Consumer<Store.Weight> select;
    int index;
    float startX, startY;
    boolean dragging;

    Trend(
        Context c,
        List<Store.Weight> source,
        int tint,
        int muted,
        int line,
        Consumer<Store.Weight> select) {
      super(c);
      data = new ArrayList<>(source);
      Collections.reverse(data);
      index = Math.max(0, data.size() - 1);
      this.tint = tint;
      this.muted = muted;
      this.line = line;
      this.select = select;
      setFocusable(true);
      setContentDescription("体重趋势，左右滑动或用方向键选择记录");
    }

    float dp(float n) {
      return AppViews.dp(getContext(), n);
    }

    float px(int i) {
      float left = dp(6), right = getWidth() - dp(40);
      if (data.size() < 2) return (left + right) / 2;
      long a = LocalDate.parse(data.get(0).date).toEpochDay(),
          b = LocalDate.parse(data.get(data.size() - 1).date).toEpochDay();
      return left
          + (right - left)
              * (LocalDate.parse(data.get(i).date).toEpochDay() - a)
              / Math.max(1, b - a);
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      paint.setTypeface(Typeface.DEFAULT);
      paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(muted);
      if (data.isEmpty()) {
        c.drawText("此时间范围暂无记录", dp(12), getHeight() / 2f, paint);
        return;
      }
      double lo = Math.floor((data.stream().mapToDouble(w -> w.kg).min().orElse(0) - .2) * 2) / 2,
          hi = Math.ceil((data.stream().mapToDouble(w -> w.kg).max().orElse(1) + .2) * 2) / 2;
      float top = dp(20), bottom = getHeight() - dp(30), right = getWidth() - dp(40);
      for (int i = 0; i < 3; i++) {
        float y = top + (bottom - top) * i / 2;
        paint.setColor(line);
        paint.setStrokeWidth(dp(1));
        c.drawLine(dp(6), y, right, y, paint);
        paint.setColor(muted);
        c.drawText(
            String.format(Locale.CHINA, "%.1f", hi - (hi - lo) * i / 2),
            right + dp(8),
            y + dp(4),
            paint);
      }
      path.reset();
      float selectedY = 0;
      for (int i = 0; i < data.size(); i++) {
        float x = px(i), y = (float) (top + (bottom - top) * (hi - data.get(i).kg) / (hi - lo));
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
        if (i == index) selectedY = y;
      }
      paint.setColor(tint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(2.5f));
      paint.setStrokeJoin(Paint.Join.ROUND);
      c.drawPath(path, paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(muted);
      paint.setStrokeWidth(dp(1));
      c.drawLine(px(index), top, px(index), bottom, paint);
      paint.setColor(tint);
      c.drawCircle(px(index), selectedY, dp(4), paint);
      paint.setColor(muted);
      String a = data.get(0).date.substring(5), b = data.get(data.size() - 1).date.substring(5);
      c.drawText(a, dp(6), getHeight() - dp(7), paint);
      if (data.size() > 1) c.drawText(b, right - paint.measureText(b), getHeight() - dp(7), paint);
    }

    void choose(float x) {
      if (data.isEmpty()) return;
      int closest = 0;
      for (int i = 1; i < data.size(); i++)
        if (Math.abs(px(i) - x) < Math.abs(px(closest) - x)) closest = i;
      index = closest;
      select.accept(data.get(index));
      invalidate();
    }

    public boolean onTouchEvent(android.view.MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          startX = e.getX();
          startY = e.getY();
          choose(e.getX());
          return true;
        case MotionEvent.ACTION_MOVE:
          if (Math.abs(e.getX() - startX) > Math.abs(e.getY() - startY)
              && Math.abs(e.getX() - startX) > dp(6)) {
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
          }
          if (dragging) choose(e.getX());
          return true;
        case MotionEvent.ACTION_UP:
          performClick();
        case MotionEvent.ACTION_CANCEL:
          dragging = false;
          getParent().requestDisallowInterceptTouchEvent(false);
          return true;
      }
      return super.onTouchEvent(e);
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }

    public boolean onKeyDown(int key, KeyEvent event) {
      if (!data.isEmpty()
          && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
        index =
            Math.max(
                0, Math.min(data.size() - 1, index + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
        select.accept(data.get(index));
        invalidate();
        return true;
      }
      return super.onKeyDown(key, event);
    }
  }
}
