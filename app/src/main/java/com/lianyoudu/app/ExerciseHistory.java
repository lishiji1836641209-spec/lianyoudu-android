package com.lianyoudu.app;

import java.util.*;

/** Read recorded values in their original units; never compare kg, reps and seconds. */
final class ExerciseHistory {
  record Entry(String sessionId, String date, List<String> lines) {}

  static boolean matches(Domain.Exercise a, Domain.Exercise b) {
    return a.id.equals(b.id)
        && (!a.id.equals("custom") || (a.name.equals(b.name) && a.gear.equals(b.gear)));
  }

  static Entry from(Domain.Session session, Domain.Exercise target) {
    List<String> lines = new ArrayList<>();
    for (Domain.Item item : session.items) {
      if (!matches(item.exercise, target)) continue;
      Domain.Exercise e = item.exercise;
      if (e.distanceBased()) {
        if (item.durationMs() == 0) continue;
        String speed =
            item.speedKmh() == null
                ? ""
                : String.format(Locale.CHINA, " · 均速 %.1f 千米/时", item.speedKmh());
        lines.add(
            String.format(
                Locale.CHINA,
                "%.2f 千米 · %s%s",
                item.meters() / 1000,
                Domain.clock(item.durationMs()),
                speed));
      } else {
        for (int i = 0; i < item.logs.size(); i++) {
          Domain.Log log = item.logs.get(i);
          String amount =
              e.timed
                  ? Domain.clock(log.ms)
                  : log.reps + (ExerciseCatalog.perSide(e) ? "次/侧" : "次");
          String load =
              e.unit.isEmpty()
                  ? ""
                  : String.format(Locale.CHINA, " · %s %.1f 千克", e.unit, log.weight);
          lines.add("第" + (i + 1) + "组 · " + amount + load + (log.partial ? " · 提前结束" : ""));
        }
      }
    }
    return lines.isEmpty() ? null : new Entry(session.id, session.date, List.copyOf(lines));
  }
}
