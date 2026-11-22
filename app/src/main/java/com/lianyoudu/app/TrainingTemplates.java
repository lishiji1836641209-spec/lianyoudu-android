package com.lianyoudu.app;

import java.util.*;

/** Optional, editable starting points. Never changes an existing plan's settings. */
final class TrainingTemplates {
  record Template(
      String id,
      String name,
      String description,
      List<String> ids,
      int sets,
      int reps,
      int seconds) {
    List<Domain.Exercise> exercises() {
      List<Domain.Exercise> catalog = Domain.catalog("all", Set.of());
      List<Domain.Exercise> result = new ArrayList<>();
      for (String id : ids) {
        Domain.Exercise e =
            catalog.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow().copy();
        e.sets = e.timed && ExerciseCatalog.perSide(e) ? 2 : sets;
        e.reps = reps;
        e.seconds = seconds;
        e.weight = 0;
        result.add(e);
      }
      return result;
    }
  }

  static final List<Template> ALL =
      List.of(
          new Template(
              "core",
              "器械核心",
              "提膝架与瑜伽垫 · 可按能力调整组数",
              List.of("supported_leg_raise", "dead_bug", "plank"),
              2,
              8,
              30),
          new Template(
              "home",
              "徒手基础",
              "瑜伽垫即可 · 腿臀、胸与核心",
              List.of("squat", "kneeling_pushup", "glute_bridge", "dead_bug"),
              2,
              8,
              30),
          new Template(
              "dumbbell",
              "哑铃全身",
              "哑铃、稳固训练凳和瑜伽垫",
              List.of("db_goblet_squat", "row", "db_floor_press", "db_rdl"),
              2,
              10,
              30),
          new Template(
              "gym",
              "器械全身",
              "腿举、推胸、下拉与划船 · 先调好座椅和靠垫",
              List.of("leg", "press", "pull", "cable_row"),
              2,
              10,
              30),
          new Template(
              "mobility",
              "舒缓伸展",
              "轻微牵拉即可 · 单侧动作分别计时",
              List.of("cat_cow", "hamstring_stretch", "hip_flexor_stretch", "child_pose"),
              1,
              8,
              30));

  static List<Domain.Exercise> merge(List<Domain.Exercise> existing, Template template) {
    List<Domain.Exercise> result = new ArrayList<>();
    for (Domain.Exercise e : existing) result.add(e.copy());
    for (Domain.Exercise e : template.exercises()) {
      if (result.stream().anyMatch(x -> x.id.equals(e.id) || x.name.equals(e.name))) continue;
      Domain.add(result, e, -1);
    }
    return result;
  }
}
