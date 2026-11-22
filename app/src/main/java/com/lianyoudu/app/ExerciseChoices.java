package com.lianyoudu.app;

import java.util.List;

/** One identity rule for library, picker and saved personal settings. */
final class ExerciseChoices {
  static boolean same(Domain.Exercise a, Domain.Exercise b) {
    return a.id.equals(b.id)
        && (!a.id.equals("custom") || (a.name.equals(b.name) && a.gear.equals(b.gear)));
  }

  static Domain.Exercise preferred(Domain.Exercise fallback, List<Domain.Exercise> plan) {
    return plan.stream().filter(e -> same(e, fallback)).findFirst().orElse(fallback).copy();
  }

  static String key(Domain.Exercise e) {
    return "id:" + e.id;
  }
}
