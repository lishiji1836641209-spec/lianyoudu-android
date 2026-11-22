package com.lianyoudu.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import java.time.LocalDate;
import java.util.*;

/** Shared materials, original line symbols, and charts driven exclusively by saved records. */
final class Visuals {
  static Drawable surface(boolean dark, int tint, float radius) {
    return AppViews.round(HealthDesign.card(dark), radius);
  }

  static Drawable backdrop(boolean dark) {
    return new ColorDrawable(HealthDesign.background(dark));
  }

  static final class Icon extends Drawable {
    final String kind;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    int color;

    Icon(String kind, int color) {
      this.kind = kind;
      this.color = color;
    }

    void color(int value) {
      if (color == value) return;
      color = value;
      invalidateSelf();
    }

    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.7f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      path.reset();
      switch (kind) {
        case "bluetooth":
          path.moveTo(7, 7); path.lineTo(17, 17); path.lineTo(12, 22);
          path.lineTo(12, 2); path.lineTo(17, 7); path.lineTo(7, 17);
          c.drawPath(path, p);
          break;
        case "摘要":
        case "heart":
          path.moveTo(12, 21);
          path.cubicTo(9, 18, 2, 13, 2, 7.5f);
          path.cubicTo(2, 2, 9, 1, 12, 6);
          path.cubicTo(15, 1, 22, 2, 22, 7.5f);
          path.cubicTo(22, 13, 15, 18, 12, 21);
          c.drawPath(path, p);
          break;
        case "能量":
        case "每日能量":
        case "运动时间":
          path.moveTo(12, 2);
          path.cubicTo(14, 7, 20, 10, 20, 15);
          path.cubicTo(20, 24, 4, 24, 4, 15);
          path.cubicTo(4, 10, 7, 8, 8, 6);
          path.lineTo(10, 12);
          path.cubicTo(13, 9, 13, 5, 12, 2);
          c.drawPath(path, p);
          break;
        case "bike":
          c.drawCircle(5, 16, 4, p);
          c.drawCircle(19, 16, 4, p);
          path.moveTo(5, 16);
          path.lineTo(10, 7);
          path.lineTo(15, 16);
          path.lineTo(5, 16);
          path.moveTo(10, 7);
          path.lineTo(17, 7);
          path.lineTo(19, 16);
          path.moveTo(8, 4);
          path.lineTo(12, 4);
          path.moveTo(16, 3);
          path.lineTo(18, 3);
          path.lineTo(17, 7);
          c.drawPath(path, p);
          break;
        case "今日":
        case "activity":
          c.drawCircle(12, 12, 9, p);
          path.moveTo(5, 13);
          path.lineTo(9, 13);
          path.lineTo(11, 8);
          path.lineTo(14, 17);
          path.lineTo(16, 12);
          path.lineTo(20, 12);
          c.drawPath(path, p);
          break;
        case "训练":
          c.save();
          c.rotate(-32, 12, 12);
          c.drawRoundRect(3, 7, 7, 17, 1.4f, 1.4f, p);
          c.drawRoundRect(17, 7, 21, 17, 1.4f, 1.4f, p);
          c.drawLine(7, 12, 17, 12, p);
          c.drawLine(1, 10, 1, 14, p);
          c.drawLine(23, 10, 23, 14, p);
          c.restore();
          break;
        case "饮食能量":
        case "饮食":
          c.drawCircle(13, 12, 7, p);
          c.drawLine(2, 3, 2, 20, p);
          c.drawLine(5, 3, 5, 7, p);
          c.drawLine(2, 8, 5, 8, p);
          break;
        case "体重":
          c.drawRoundRect(3, 3, 21, 21, 5, 5, p);
          c.drawArc(7, 6, 17, 16, 210, 120, false, p);
          c.drawLine(12, 11, 15, 8, p);
          c.drawLine(8, 18, 16, 18, p);
          break;
        case "浏览":
          for (int x : new int[] {3, 14})
            for (int y : new int[] {3, 14}) c.drawRoundRect(x, y, x + 7, y + 7, 2, 2, p);
          break;
        case "settings":
          c.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 12, 12);
            c.drawLine(12, 2, 12, 4, p);
            c.restore();
          }
          c.drawCircle(12, 12, 8, p);
          break;
        case "check":
          path.moveTo(5, 12);
          path.lineTo(10, 17);
          path.lineTo(20, 7);
          c.drawPath(path, p);
          break;
        case "delete":
          c.drawRoundRect(6, 7, 18, 21, 2, 2, p);
          c.drawLine(4, 6, 20, 6, p);
          c.drawLine(9, 3, 15, 3, p);
          c.drawLine(10, 10, 10, 17, p);
          c.drawLine(14, 10, 14, 17, p);
          break;
        case "search":
          c.drawCircle(10, 10, 6, p);
          c.drawLine(15, 15, 21, 21, p);
          break;
        case "plus":
          c.drawLine(12, 5, 12, 19, p);
          c.drawLine(5, 12, 19, 12, p);
          break;
        case "plan":
          c.drawRoundRect(5, 3, 19, 21, 2, 2, p);
          c.drawLine(8, 8, 16, 8, p);
          c.drawLine(8, 12, 16, 12, p);
          c.drawLine(8, 16, 14, 16, p);
          break;
        case "update":
          c.drawArc(4, 4, 20, 20, 35, 285, false, p);
          c.drawLine(17, 4, 20, 6, p);
          c.drawLine(20, 6, 20, 10, p);
          break;
        case "export":
          c.drawRoundRect(5, 3, 19, 21, 2, 2, p);
          c.drawLine(12, 15, 12, 7, p);
          c.drawLine(9, 10, 12, 7, p);
          c.drawLine(12, 7, 15, 10, p);
          break;
        case "clock":
          c.drawCircle(12, 12, 9, p);
          c.drawLine(12, 6, 12, 12, p);
          c.drawLine(12, 12, 16, 14, p);
          break;
        default:
          c.drawCircle(12, 12, 9, p);
          c.drawLine(12, 7, 12, 13, p);
          c.drawPoint(12, 17, p);
      }
      c.restore();
    }

    public void setAlpha(int a) {
      p.setAlpha(a);
      invalidateSelf();
    }

    public void setColorFilter(ColorFilter f) {
      p.setColorFilter(f);
      invalidateSelf();
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static final class WeekChart extends View {
    final long[] duration = new long[7];
    final int accent, muted, line;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeekChart(Context c, List<Domain.Session> sessions, int accent, int muted, int line) {
      super(c);
      this.accent = accent;
      this.muted = muted;
      this.line = line;
      LocalDate today = LocalDate.now();
      for (Domain.Session s : sessions) {
        long i =
            LocalDate.parse(s.date).toEpochDay()
                - today.with(java.time.DayOfWeek.MONDAY).toEpochDay();
        if (i >= 0 && i < 7) duration[(int) i] += s.elapsed;
      }
      setContentDescription("本周运动时长，详细记录见训练记录");
    }

    protected void onDraw(Canvas c) {
      float den = getResources().getDisplayMetrics().density;
      float bottom = getHeight() - 22 * den, top = 12 * den;
      long max = 60000;
      for (long n : duration) max = Math.max(max, n);
      float cell = getWidth() / 7f, bar = cell * .30f;
      p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      p.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
      p.setTextAlign(Paint.Align.CENTER);
      String[] day = {"一", "二", "三", "四", "五", "六", "日"};
      for (int i = 0; i < 7; i++) {
        float x = cell * (i + .5f), h = (bottom - top) * duration[i] / max;
        p.setColor(line);
        c.drawRoundRect(x - bar / 2, bottom - 2 * den, x + bar / 2, bottom, bar / 2, bar / 2, p);
        if (h > 0) {
          p.setColor(accent);
          c.drawRoundRect(
              x - bar / 2, bottom - Math.max(h, bar), x + bar / 2, bottom, bar / 2, bar / 2, p);
          p.setShader(null);
        }
        p.setColor(i == LocalDate.now().getDayOfWeek().getValue() - 1 ? accent : muted);
        LocalDate date = LocalDate.now().with(java.time.DayOfWeek.MONDAY).plusDays(i);
        c.drawText(day[date.getDayOfWeek().getValue() - 1], x, getHeight() - 4 * den, p);
      }
    }
  }
}
