package com.lianyoudu.app;

/** Estimates, never measurements. Equations and applicability are exposed in the UI. */
final class EnergyMath {
  static double resting(int age, double cm, double kg, boolean male) {
    Domain.require(age >= 19 && age <= 78, "当前公式适用于19至78岁的普通成年人");
    Domain.require(Double.isFinite(cm) && cm >= 100 && cm <= 250, "身高应在100至250厘米之间");
    Domain.require(Double.isFinite(kg) && kg >= 25 && kg <= 300, "当前估算的体重范围为25至300千克");
    return 10 * kg + 6.25 * cm - 5 * age + (male ? 5 : -161);
  }

  static double netKcal(double met, double kg, long milliseconds) {
    Domain.require(
        Double.isFinite(met) && met >= 1 && Double.isFinite(kg) && kg > 0 && milliseconds >= 0,
        "运动估算参数无效");
    return (met - 1) * 3.5 * kg / 200 * (milliseconds / 60000.0);
  }

  static double met(Domain.Item i) {
    return switch (i.exercise.id) {
      case "stretch",
              "cat_cow",
              "hamstring_stretch",
              "hip_flexor_stretch",
              "chest_stretch",
              "calf_stretch" ->
          2.3;
      case "plank", "dead_bug", "bird_dog", "side_plank" -> 2.8;
      case "squat", "push", "reverse_lunge", "glute_bridge", "wall_sit", "step_up" -> 3.0;
      case "aerobics" -> 4.8;
      case "bell" -> 5.0;
      case "row",
              "band",
              "bench",
              "pull",
              "press",
              "leg",
              "calf_raise",
              "db_goblet_squat",
              "db_rdl",
              "db_split_squat",
              "prone_leg_curl",
              "leg_extension",
              "hip_abduction",
              "back_extension",
              "db_shoulder_press",
              "db_lateral_raise",
              "db_curl",
              "db_triceps_extension",
              "db_floor_press",
              "cable_row",
              "cable_face_pull",
              "assisted_pullup" ->
          3.5;
      case "trainer", "stationary_bike" -> 6.8;
      case "elliptical" -> 5.0;
      // Outdoor speed depends on terrain/wind; use a conservative generic activity intensity.
      case "bike" -> 4.3;
      case "walk" -> 3.5;
      default -> Double.NaN; // No assumed running speed or arbitrary custom-activity intensity.
    };
  }

  static Double workout(Domain.Session session, int age, double kg) {
    if (session.calories != null) return session.calories;
    if (age < 19 || age > 59) return null;
    double total = 0;
    boolean any = false;
    for (Domain.Item item : session.items) {
      long ms = item.activeMs;
      if (ms <= 0) continue; // Legacy strength records do not contain active duration.
      double met = met(item);
      if (!Double.isFinite(met)) return null;
      total += netKcal(met, kg, ms);
      any = true;
    }
    return any ? total : null;
  }

  static record Ledger(Double kcal, String source, boolean fullDeviceTotal) {}

  static Ledger ledger(
      Double deviceTotal, Double deviceActive, Double restingElapsed, double localExercise) {
    // One mutually exclusive source path: never add steps or local workouts on top of device
    // calories.
    if (deviceTotal != null && Double.isFinite(deviceTotal) && deviceTotal >= 0)
      return new Ledger(deviceTotal, "设备总消耗", true);
    if (restingElapsed == null) return new Ledger(null, "需要身体资料或设备总消耗", false);
    if (deviceActive != null && Double.isFinite(deviceActive) && deviceActive >= 0)
      return new Ledger(restingElapsed + deviceActive, "静息估算 + 设备活动热量", false);
    return new Ledger(restingElapsed + Math.max(0, localExercise), "静息估算 + 已记录运动", false);
  }
}
