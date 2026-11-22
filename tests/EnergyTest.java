package com.lianyoudu.app;

import java.util.*;

public final class EnergyTest {
  static int n;

  static void check(boolean b, String why) {
    if (!b) throw new AssertionError(why);
    n++;
  }

  static void rejects(Runnable work) {
    try {
      work.run();
      throw new AssertionError("expected rejection");
    } catch (IllegalArgumentException e) {
      n++;
    }
  }

  public static void main(String[] args) {
    check(EnergyMath.resting(30, 175, 70, true) == 1648.75, "male reference formula");
    check(EnergyMath.resting(30, 175, 70, false) == 1482.75, "female reference formula");
    rejects(() -> EnergyMath.resting(18, 175, 70, true));
    rejects(() -> EnergyMath.resting(79, 175, 70, true));
    rejects(() -> EnergyMath.resting(30, Double.NaN, 70, true));
    check(
        EnergyMath.ledger(2000.0, 600.0, 1000.0, 300).kcal() == 2000,
        "total excludes ALL additional calories");
    check(
        EnergyMath.ledger(null, 600.0, 1000.0, 300).kcal() == 1600,
        "active excludes local duplicate");
    check(EnergyMath.ledger(null, null, 1000.0, 300).kcal() == 1300, "local fallback");
    check(
        !EnergyMath.ledger(null, null, 1000.0, 300).fullDeviceTotal(),
        "partial ledger not deficit");
    check(
        EnergyMath.ledger(null, 600.0, null, 300).kcal() == null,
        "missing profile never zero resting");
    check(
        EnergyMath.ledger(Double.NaN, null, 1000.0, 300).kcal() == 1300, "invalid source rejected");
    check(
        Math.abs(EnergyMath.netKcal(3.5, 70, 3600000) - 183.75) < .0001,
        "MET removes resting component");
    var list = Domain.catalog("all", Set.of());
    check(list.size() == 65, "all 65 exercises available regardless gear");
    check(list.stream().map(e -> e.id).distinct().count() == 65, "no duplicate catalog entries");
    var bike = list.stream().filter(e -> e.id.equals("bike")).findFirst().get();
    var row = list.stream().filter(e -> e.id.equals("row")).findFirst().get();
    check(bike.distanceBased() && bike.sets == 1 && bike.usesGps(), "bike metrics");
    var q = new Domain.Session("all", List.of(bike, row));
    q.resume(0);
    q.tick(10000);
    check(
        q.item().activeMs == 10000 && q.item().durationMs() == 10000, "distance accumulates time");
    q.item().gpsMeters = 200;
    q.item().manualMeters = 500.0;
    check(q.item().meters() == 500, "manual overrides without adding GPS");
    q.item().manualMeters = null;
    check(q.item().meters() == 200, "restore GPS distance");
    q.select(1, 10000);
    q.resume(10000);
    q.tick(20000);
    check(
        q.items.get(0).activeMs == 10000 && q.item().activeMs == 10000,
        "mixed session duration not duplicated");
    q.recordInline(1, 0, 12, 20, 20000);
    q.tick(80000);
    check(q.item().activeMs == 10000, "rest excluded from active estimate");
    q.finish(80000);
    check(q.count() == 1 && q.target() == 3, "continuous sports do not count as sets");
    check(EnergyMath.workout(q, 60, 70) == null, "older adults no inappropriate MET");
    q.calories = 120.0;
    check(EnergyMath.workout(q, 60, 70) == 120, "device manual active calories allowed");
    var old = new Domain.Session("gym", List.of(row));
    old.item().logs.add(new Domain.Log(12, 50, 0, false));
    check(EnergyMath.workout(old, 30, 70) == null, "legacy weights are not converted to calories");
    var bikeItem = new Domain.Item(bike);
    bikeItem.currentMs = 1000;
    bikeItem.manualMeters = 2500.0;
    check(bikeItem.speedKmh() == null, "short time has no misleading speed");
    bikeItem.currentMs = 10000;
    check(bikeItem.speedKmh() == null, "implausible speed hidden");
    bikeItem.currentMs = 300000;
    check(bikeItem.speedKmh() == 30, "valid speed in km/h");
    System.out.println("PASS " + n + " energy and unified activity assertions");
  }
}
