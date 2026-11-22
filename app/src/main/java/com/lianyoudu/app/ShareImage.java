package com.lianyoudu.app;

import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.util.*;

/** A single theme-aware bitmap is used for preview, saving and sharing. */
final class ShareImage {
  private static final int WIDTH = 1080, MARGIN = 48, INNER_WIDTH = WIDTH - MARGIN * 4;
  // A single image can accommodate 30 normal exercise rows while bounding unusually long records.
  private static final long MAX_BYTES = 48L * 1024 * 1024;

  private static StaticLayout paragraph(String text, int size, int color, boolean bold) {
    TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    p.setTextSize(size);
    p.setColor(color);
    p.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    return StaticLayout.Builder.obtain(text, 0, text.length(), p, INNER_WIDTH)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .setLineSpacing(8, 1)
        .build();
  }

  private static void draw(Canvas canvas, StaticLayout layout, float x, float y) {
    canvas.save();
    canvas.translate(x, y);
    layout.draw(canvas);
    canvas.restore();
  }

  private record Row(StaticLayout name, StaticLayout details, int height) {}

  static Bitmap draw(Domain.Session q, boolean heart, Store.Weight weight) {
    return draw(q, heart, weight, false);
  }

  static Bitmap draw(Domain.Session q, boolean heart, Store.Weight weight, boolean dark) {
    int ink = dark ? 0xFFF7F7FA : 0xFF19191D;
    int muted = dark ? 0xFFBCB6C5 : 0xFF6B6B74;
    int background = dark ? 0xFF16131F : Color.WHITE;
    int rowBackground = dark ? 0xFF26232D : 0xFFF4F1F8;
    int hero = dark ? 0xFF513C78 : 0xFF5E458B;
    StaticLayout brand = paragraph("练有度 · 运动记录", 48, Color.WHITE, true);
    StaticLayout label = paragraph("运动时长", 48, Color.WHITE, false);
    StaticLayout duration = paragraph(Domain.clock(q.elapsed), 96, Color.WHITE, true);
    StaticLayout metadata = paragraph(summary(q), 48, Color.WHITE, false);
    int heroHeight = 48 + brand.getHeight() + 32 + label.getHeight() + 12
        + duration.getHeight() + 24 + metadata.getHeight() + 48;
    List<Row> rows = new ArrayList<>();
    for (Domain.Item item : q.items) {
      StaticLayout name = paragraph(item.exercise.name, 48, ink, true);
      StaticLayout details = paragraph(details(item), 48, muted, false);
      rows.add(new Row(name, details, Math.max(176, 48 + name.getHeight() + 16 + details.getHeight() + 48)));
    }
    if (rows.isEmpty()) {
      StaticLayout name = paragraph("未记录具体运动", 48, ink, true);
      rows.add(new Row(name, null, Math.max(176, 96 + name.getHeight())));
    }
    List<StaticLayout> optional = new ArrayList<>();
    if (heart) {
      optional.add(paragraph(heartLabel(q), 48, muted, false));
    }
    if (weight != null)
      optional.add(paragraph(weightLabel(weight), 48, muted, false));
    long height = MARGIN + heroHeight + 32L;
    for (Row row : rows) height += row.height + 24L;
    for (StaticLayout line : optional) height += line.getHeight() + 24L;
    height += MARGIN - 24L;
    if (height <= 0 || height > 16384 || height * WIDTH * 4L > MAX_BYTES)
      throw new IllegalArgumentException("记录内容过长，无法生成图片。请返回后重试。");
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, (int) height, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bitmap);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(background);
    c.drawRoundRect(0, 0, WIDTH, height, 72, 72, p);
    c.save();
    Path outline = new Path();
    outline.addRoundRect(0, 0, WIDTH, height, 72, 72, Path.Direction.CW);
    c.clipPath(outline);
    p.setColor(hero);
    c.drawRoundRect(MARGIN, MARGIN, WIDTH - MARGIN, MARGIN + heroHeight, 32, 32, p);
    int x = MARGIN * 2, y = MARGIN * 2;
    draw(c, brand, x, y); y += brand.getHeight() + 32;
    draw(c, label, x, y); y += label.getHeight() + 12;
    draw(c, duration, x, y); y += duration.getHeight() + 24;
    draw(c, metadata, x, y);
    y = MARGIN + heroHeight + 32;
    for (Row row : rows) {
      p.setColor(rowBackground);
      c.drawRoundRect(MARGIN, y, WIDTH - MARGIN, y + row.height, 28, 28, p);
      draw(c, row.name, x, y + 48);
      if (row.details != null) draw(c, row.details, x, y + 48 + row.name.getHeight() + 16);
      y += row.height + 24;
    }
    for (StaticLayout line : optional) { draw(c, line, x, y); y += line.getHeight() + 24; }
    c.restore();
    return bitmap;
  }

  private static String details(Domain.Item item) {
    if (item.exercise.distanceBased()) {
      if (item.exercise.id.equals("stair_climb")) return (item.stairSteps == null ? "台阶数未记录" : "台阶 " + item.stairSteps + "级") + " · 时长 " + Domain.clock(item.durationMs());
      String incline = item.exercise.id.equals("incline_treadmill") ? (item.inclinePercent == null ? " · 坡度未记录" : " · 坡度 " + item.inclinePercent + "%") : "";
      if (item.manualMeters == null && item.gpsMeters <= 0)
        return item.durationMs() > 0 ? "未记录距离 · 时长 " + Domain.clock(item.durationMs()) + incline : "未记录" + incline;
      String source = item.manualMeters != null ? "手动记录 · " : "历史GPS记录 · ";
      return source + String.format(Locale.CHINA, "%.2f 公里 · 时长 %s", item.meters() / 1000, Domain.clock(item.durationMs())) + incline;
    }
    if (item.logs.isEmpty()) return "未记录";
    int completed = 0, reps = 0;
    long ms = 0;
    boolean partial = false;
    for (Domain.Log log : item.logs) {
      if (!log.partial) completed++;
      partial |= log.partial;
      reps += log.reps;
      ms += log.ms;
    }
    String amount = item.exercise.timed
        ? Domain.clock(ms)
        : ExerciseCatalog.perSide(item.exercise) ? "每侧合计 " + reps + " 次" : reps + " 次";
    String weight = "";
    if (!item.exercise.timed && !item.exercise.unit.isEmpty()) {
      java.util.LinkedHashSet<String> recordedWeights = new java.util.LinkedHashSet<>();
      for (Domain.Log log : item.logs) recordedWeights.add(String.valueOf(log.weight));
      if (!recordedWeights.isEmpty()) weight = " · " + ExerciseCatalog.weightLabel(item.exercise) + " " + String.join("/", recordedWeights) + "千克";
    }
    return "完成 " + completed + " / " + item.exercise.sets + " 组 · "
        + amount + weight + (partial ? "（含未完成组）" : "");
  }

  private static String summary(Domain.Session q) {
    String result = q.date + (q.items.isEmpty() ? "" : " · " + q.items.size() + " 项运动");
    return q.target() > 0 ? result + " · 完成 " + q.count() + " / " + q.target() + " 组" : result;
  }

  private static String heartLabel(Domain.Session q) {
    return HeartSummary.coverage(q);
  }

  private static String weightLabel(Store.Weight weight) {
    return String.format(Locale.CHINA, "最近体重 %.1f 千克 · %s", weight.kg, weight.date);
  }

  static String accessibleSummary(Domain.Session q, boolean heart, Store.Weight weight) {
    StringBuilder result = new StringBuilder("练有度 · 运动记录\n运动时长 ")
        .append(Domain.clock(q.elapsed)).append('\n').append(summary(q));
    if (q.items.isEmpty()) result.append("\n\n未记录具体运动");
    for (Domain.Item item : q.items)
      result.append("\n\n").append(item.exercise.name).append("\n").append(details(item));
    if (heart) result.append("\n\n").append(heartLabel(q));
    if (weight != null) result.append("\n\n").append(weightLabel(weight));
    return result.toString();
  }

  static String stateKey(Domain.Session q, boolean heart, Store.Weight weight, boolean dark) {
    return q.id + "\0" + dark + "\0" + heart + "\0" + (weight != null)
        + "\0" + accessibleSummary(q, heart, weight);
  }
}
