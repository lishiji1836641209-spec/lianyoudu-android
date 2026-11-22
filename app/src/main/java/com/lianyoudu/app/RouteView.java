package com.lianyoudu.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** WGS84 route geometry without web maps, key requirements, or coordinate-system mismatches. */
final class RouteView extends View {
  private List<RouteTrack.Point> points;
  private final Paint paint = new Paint(3);
  private final Path path = new Path();
  private final int color, grid;
  private float[] xy;

  RouteView(Context context, List<RouteTrack.Point> points, int color, int grid) {
    super(context);
    this.color = color;
    this.grid = grid;
    update(points);
    setContentDescription("运动路线，" + points.size() + "个有效定位点；轨迹图不含道路底图");
  }

  void update(List<RouteTrack.Point> rows) {
    points = rows;
    setContentDescription("运动路线，" + rows.size() + "个有效定位点；轨迹图不含道路底图");
    xy = null;
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    xy = null;
  }

  private void project() {
    xy = new float[points.size() * 2];
    if (points.isEmpty()) return;
    double lat0 = points.get(0).latitude,
        lon0 = points.get(0).longitude,
        minX = 0,
        maxX = 0,
        minY = 0,
        maxY = 0;
    double[] coords = new double[xy.length];
    for (int i = 0; i < points.size(); i++) {
      RouteTrack.Point p = points.get(i);
      double dx = p.longitude - lon0;
      if (dx > 180) dx -= 360;
      if (dx < -180) dx += 360;
      double x = dx * Math.cos(Math.toRadians(lat0)), y = p.latitude - lat0;
      coords[2 * i] = x;
      coords[2 * i + 1] = y;
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
    float pad = AppViews.dp(getContext(), 24);
    double scale =
        Math.min(
            (getWidth() - 2 * pad) / Math.max(maxX - minX, .0001),
            (getHeight() - 2 * pad) / Math.max(maxY - minY, .0001));
    for (int i = 0; i < points.size(); i++) {
      xy[2 * i] = (float) (getWidth() / 2 + (coords[2 * i] - (minX + maxX) / 2) * scale);
      xy[2 * i + 1] = (float) (getHeight() / 2 - (coords[2 * i + 1] - (minY + maxY) / 2) * scale);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    paint.setColor(grid);
    paint.setStrokeWidth(density);
    for (int i = 1; i < 4; i++) {
      canvas.drawLine(getWidth() * i / 4f, 0, getWidth() * i / 4f, getHeight(), paint);
      canvas.drawLine(0, getHeight() * i / 4f, getWidth(), getHeight() * i / 4f, paint);
    }
    if (xy == null) project();
    if (points.isEmpty()) {
      paint.setColor(color);
      paint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
      canvas.drawText("等待有效定位", 20 * density, getHeight() / 2f, paint);
      return;
    }
    path.reset();
    for (int i = 0; i < points.size(); i++) {
      if (i == 0 || points.get(i).segment != points.get(i - 1).segment)
        path.moveTo(xy[2 * i], xy[2 * i + 1]);
      else path.lineTo(xy[2 * i], xy[2 * i + 1]);
    }
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(3 * density);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    canvas.drawPath(path, paint);
    paint.setStyle(Paint.Style.FILL);
    canvas.drawCircle(xy[0], xy[1], 5 * density, paint);
    canvas.drawCircle(xy[xy.length - 2], xy[xy.length - 1], 6 * density, paint);
  }
}
