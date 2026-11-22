package com.lianyoudu.app;

import java.util.*;

/** Discovery and metric regressions that exercise user-facing catalog contracts. */
public final class CatalogTest {
  static int checks;

  static void check(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
    checks++;
  }

  static List<Domain.Exercise> all = Domain.catalog("all", Set.of());

  static Domain.Exercise get(String id) {
    return all.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow();
  }

  static boolean match(String id, String q, String category, String muscle, String gear) {
    return ExerciseCatalog.matches(get(id), q, category, muscle, gear, Set.of());
  }

  public static void main(String[] args) {
    check(
        all.size() == 65 && new HashSet<>(all.stream().map(e -> e.name).toList()).size() == 65,
        "distinct names and IDs");
    check(
        all.stream().map(e -> e.id).toList().equals(ExerciseCatalog.IDS),
        "every catalog image has stable matching order");
    check(
        Domain.catalog("gym", Set.of()).size() == 13, "legacy scene catalog not silently expanded");
    check(
        match("db_rdl", " ｒｄｌ ", "全部", "腿臀", "哑铃"),
        "aliases ignore case/fullwidth/space and intersect filters");
    check(!match("db_rdl", "RDL", "全部", "胸", "哑铃"), "body filter intersects search");
    check(match("db_curl", "哑铃 二头肌", "力量", "全部部位", "全部器材"), "multiword body and gear search");
    check(match("plank", "", "核心", "核心", "徒手"), "mat exercise included in bodyweight");
    check(
        match("stationary_bike", "", "器械", "全部部位", "自行车"),
        "indoor cardio is equipment, not lost from picker");
    check(!match("bike", "", "全部", "全部部位", "固定器械"), "road bike not a fixed machine");
    check(
        ExerciseCatalog.favorites("db_rdl,bad,db_rdl,").equals(Set.of("db_rdl")),
        "stale and duplicate favorites removed");
    check(
        ExerciseCatalog.matches(get("db_rdl"), "", "收藏", "全部部位", "全部器材", Set.of("db_rdl")),
        "favorite visible");
    check(!match("db_rdl", "", "收藏", "全部部位", "全部器材"), "empty favorites no false match");
    check(get("assisted_pullup").unit.isEmpty(), "assistance must not masquerade as lifted load");
    var assisted = new Domain.Session("all", List.of(get("assisted_pullup")));
    assisted.resume(0);
    assisted.recordInline(0, 0, 10, 30, 10000);
    check(assisted.item().logs.get(0).weight == 0, "assistance excluded even if weight supplied");
    check(
        ExerciseCatalog.countNote(get("db_split_squat")).contains("左右两侧完成后记一组"),
        "unilateral reps unambiguous");
    check(
        ExerciseCatalog.countNote(get("side_plank")).contains("完成一侧记一组"),
        "unilateral time is not doubled");
    check(
        get("side_plank").sets == 2 && get("calf_stretch").sets == 2,
        "default unilateral timed exercise covers both sides once");
    check(!match("step_up", "", "全部", "全部部位", "固定器械"), "portable step is not a fixed machine");
    check(
        EnergyMath.met(new Domain.Item(get("elliptical"))) == 5.0,
        "elliptical generic moderate MET");
    check(
        Double.isNaN(EnergyMath.met(new Domain.Item(get("mountain_climber")))),
        "unknown interval intensity not automatically treated as vigorous exercise");
    for (String id : List.of("elliptical", "stationary_bike")) {
      var e = get(id);
      check(e.distanceBased() && e.sets == 1 && !e.usesGps(), "indoor distance metric " + id);
      var session = new Domain.Session("all", List.of(e));
      session.resume(0);
      session.tick(60000);
      session.item().manualMeters = 500.0;
      check(
          session.item().speedKmh() == 30 && session.count() == 0 && session.target() == 0,
          "indoor distance not reps " + id);
      session.pause(60000);
      session.tick(90000);
      check(session.item().durationMs() == 60000, "paused indoor clock freezes " + id);
      session.finish(90000);
      check(
          session.item().durationMs() == 60000 && session.count() == 0,
          "indoor finalize no time duplication " + id);
    }
    var modified = get("db_curl");
    modified.reps = 99;
    check(
        Domain.catalog("all", Set.of()).stream()
                .filter(e -> e.id.equals("db_curl"))
                .findFirst()
                .get()
                .reps
            == 12,
        "catalog defaults not mutated by user edits");
    System.out.println("PASS " + checks + " expanded library assertions");
  }
}
