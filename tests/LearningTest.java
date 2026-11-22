package com.lianyoudu.app;

import java.util.*;

public final class LearningTest {
  static int checks;

  static void check(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
    checks++;
  }

  static Domain.Exercise get(String id) {
    return Domain.catalog("all", Set.of()).stream()
        .filter(e -> e.id.equals(id))
        .findFirst()
        .orElseThrow();
  }

  public static void main(String[] args) {
    for (Domain.Exercise e : Domain.catalog("all", Set.of())) {
      var l = ExerciseLearning.lesson(e);
      check(l.steps().size() >= 2, "at least two teaching steps " + e.id);
      check(
          !l.cue().isBlank() && !l.avoid().isBlank() && !l.breathing().isBlank(),
          "complete teaching " + e.id);
      check(
          !ExerciseLearning.alternatives(e).isEmpty()
              && ExerciseLearning.alternatives(e).stream().noneMatch(a -> a.id.equals(e.id)),
          "resolvable non-self alternatives " + e.id);
    }
    var baseChoice = get("hammer_curl");
    var myChoice = baseChoice.copy();
    myChoice.name = "我的弯举";
    myChoice.sets = 4;
    myChoice.reps = 9;
    myChoice.weight = 7.5;
    var resolved = ExerciseChoices.preferred(baseChoice, List.of(myChoice));
    check(
        resolved.name.equals("我的弯举") && resolved.sets == 4 && resolved.weight == 7.5,
        "renamed plan identity and values win");
    resolved.weight = 99;
    check(myChoice.weight == 7.5, "choice resolver deep copies");
    var customA = new Domain.Exercise("custom", "甲", "", false, "");
    var customB = new Domain.Exercise("custom", "乙", "", false, "");
    check(!ExerciseChoices.same(customA, customB), "custom choices remain distinct");
    for (var t : TrainingTemplates.ALL) {
      var exercises = t.exercises();
      check(exercises.size() == t.ids().size(), "template declared exercise count");
      exercises.forEach(Domain.Exercise::validate);
      exercises.get(0).reps = 199;
      check(t.exercises().get(0).reps != 199, "template deep copies");
      var chosen = t.exercises().get(0);
      chosen.reps = 17;
      chosen.weight = 8;
      chosen.sets = 5;
      var merged = TrainingTemplates.merge(List.of(chosen), t);
      check(
          merged.size() == t.ids().size()
              && merged.get(0).reps == 17
              && merged.get(0).weight == 8
              && merged.get(0).sets == 5,
          "template keeps personal choices");
      check(TrainingTemplates.merge(merged, t).size() == t.ids().size(), "template idempotent");
      merged.get(0).weight = 99;
      check(chosen.weight == 8, "merge independent of input");
    }
    List<Domain.Exercise> full = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      var e = get("db_curl");
      e.id = "custom";
      e.name = "定制" + i;
      full.add(e);
    }
    boolean rejected = false;
    try {
      TrainingTemplates.merge(full, TrainingTemplates.ALL.get(0));
    } catch (IllegalArgumentException ex) {
      rejected = true;
    }
    check(rejected && full.size() == 30, "overflow leaves original untouched");
    var squat = get("squat");
    var q = new Domain.Session("all", List.of(squat));
    check(
        ExerciseHistory.from(q, squat) == null,
        "uncompleted items not counted as completed history");
    q.item().logs.add(new Domain.Log(8, 0, 0, false));
    check(ExerciseHistory.from(q, squat).lines().get(0).contains("8次"), "reps recorded exactly");
    var rename = squat.copy();
    rename.name = "我的深蹲";
    check(ExerciseHistory.matches(squat, rename), "stable ID survives renaming");
    rename.timed = true;
    check(
        ExerciseHistory.from(q, rename).lines().get(0).contains("8次"),
        "old mode retained after mode change");
    var curl = get("db_curl");
    var total = curl.copy();
    total.unit = "总重";
    var loaded = new Domain.Session("all", List.of(curl));
    loaded.item().logs.add(new Domain.Log(8, 12.5, 0, false));
    check(
        ExerciseHistory.from(loaded, total).lines().get(0).contains("每只 12.5 千克"),
        "old load unit retained after unit change");
    var custom = new Domain.Exercise("custom", "自定A", "", false, "");
    var other = custom.copy();
    other.name = "自定B";
    check(!ExerciseHistory.matches(custom, other), "custom exercise names distinguish history");
    var per = get("lateral_lunge");
    var s = new Domain.Session("all", List.of(per));
    s.item().logs.add(new Domain.Log(8, 0, 0, false));
    check(
        ExerciseHistory.from(s, per).lines().get(0).contains("8次/侧"), "per-side history explicit");
    var hold = get("plank");
    var h = new Domain.Session("all", List.of(hold));
    h.item().logs.add(new Domain.Log(0, 0, 12500, true));
    check(
        ExerciseHistory.from(h, hold).lines().get(0).contains("提前结束"),
        "partial holds distinguished");
    var cycle = get("stationary_bike");
    var b = new Domain.Session("all", List.of(cycle));
    b.item().logs.add(new Domain.Log(0, 0, 60000, false));
    b.item().manualMeters = 500.;
    var line = ExerciseHistory.from(b, cycle).lines().get(0);
    check(
        line.contains("0.50 千米") && line.contains("30.0 千米/时") && !line.contains("千克"),
        "cardio history uses distance duration speed");
    System.out.println("PASS " + checks + " teaching, template and exercise-history assertions");
  }
}
