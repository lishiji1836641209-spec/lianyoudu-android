package com.lianyoudu.app;

import java.time.LocalDate;
import java.util.*;

public final class DomainTest {
  static int passed;

  static void check(boolean b, String m) {
    if (!b) throw new AssertionError(m);
    passed++;
  }

  static void rejects(Runnable r, String m) {
    try {
      r.run();
      throw new AssertionError(m);
    } catch (IllegalArgumentException expected) {
      passed++;
    }
  }

  public static void main(String[] args) {
    Domain.Session pausedHeart = new Domain.Session("all", List.of());
    pausedHeart.resume(0);
    pausedHeart.heart(100, 1000);
    int beforePause = pausedHeart.heartSegment;
    pausedHeart.pause(2000);
    pausedHeart.heart(180, 100000);
    pausedHeart.resume(101000);
    pausedHeart.heart(120, 101100);
    check(pausedHeart.heartSamples.size() == 2
        && pausedHeart.heartSamples.get(1).segment() > beforePause,
        "fresh paused readings cannot bridge the pre-pause curve");
    check(pausedHeart.heartCount == 2 && pausedHeart.heartMax == 120,
        "paused reading stays out of resumed statistics");
    int resumedSegment = pausedHeart.heartSegment;
    pausedHeart.resume(101200);
    check(pausedHeart.heartSegment == resumedSegment,
        "repeated resume does not split a running curve");
    Domain.Session heart = new Domain.Session("all", List.of());
    heart.resume(0);
    heart.heart(100, 1000);
    heart.heart(140, 6000);
    heart.pause(7000);
    heart.heart(200, 8000);
    check(heart.heartCount == 2 && heart.heartSum == 240, "paused readings do not enter workout heart statistics");
    heart.resume(9000);
    heart.disconnect();
    heart.heart(120, 10000);
    check(heart.heartSamples.size() == 3 && heart.heartSamples.get(2).segment() > heart.heartSamples.get(1).segment(), "heart reconnect starts a separate curve segment");
    check(heart.heartMin == 100 && heart.heartMax == 140, "heart range excludes pause");
    heart.heart(300, 11000);
    check(heart.heartCount == 3, "invalid heart reading excluded");
    for (int i = 0; i < 10000; i++) heart.heart(120, 15000L + i * 5000L);
    check(heart.heartSamples.size() <= 4096 && heart.heartSamples.get(0).elapsed() == 1000, "long recording bounds samples and retains start");
    check(Domain.heartRate(new byte[] {0, (byte) 128}) == 128, "8-bit HR");
    check(Domain.heartRate(new byte[] {1, (byte) 200, 0}) == 200, "16-bit HR");
    check(Domain.heartRate(new byte[] {1, 12}) == 0, "truncated HR");
    check(Domain.heartRate(new byte[] {1, 0, 2}) == 0, "outlier HR");
    rejects(() -> Domain.weight("NaN"), "NaN");
    rejects(() -> Domain.weight("Infinity"), "Infinity");
    rejects(() -> Domain.weight("69.55"), "precision");
    check(Domain.weight("69.5") == 69.5, "weight");
    rejects(() -> Domain.date("2025-02-30", LocalDate.now()), "invalid date");
    rejects(() -> Domain.date(LocalDate.now().plusDays(1).toString(), LocalDate.now()), "future");
    List<Domain.Exercise> p = new ArrayList<>();
    Domain.Exercise e = new Domain.Exercise("squat", "徒手深蹲", "", false, "");
    e.sets = 2;
    Domain.add(p, e, -1);
    rejects(() -> Domain.add(p, e, -1), "duplicate");
    e.sets = 0;
    rejects(() -> e.validate(), "sets bounds");
    e.sets = 2;
    Domain.Session q = new Domain.Session("home", p);
    p.get(0).sets = 8;
    check(q.item().exercise.sets == 2, "snapshot isolation");
    q.resume(1000);
    q.tick(2000);
    check(q.elapsed == 1000, "timer");
    q.record(10, 0, 2000);
    check(q.count() == 1 && q.rest == 60000, "log");
    Domain.Session strength = q;
    rejects(() -> strength.record(10, 0, 2000), "double tap");
    q.tick(62000);
    check(q.rest == 0, "rest expiry");
    q.record(12, 0, 62000);
    check(!q.running && q.count() == 2, "done pause");
    strength.resume(62000);
    check(strength.running, "session may resume after exercise completion");
    q.undo(62000);
    check(q.count() == 1 && !q.running, "undo pause");
    Domain.Exercise time = new Domain.Exercise("stretch", "拉伸", "瑜伽垫", true, "");
    time.sets = 2;
    q = new Domain.Session("home", List.of(time));
    q.resume(0);
    q.tick(1500);
    q.pause(2000);
    q.tick(200000);
    check(q.elapsed == 2000 && q.item().currentMs == 2000, "paused gap");
    q.resume(300000);
    q.heart(120, 300000);
    q.tick(320000);
    check(q.heartValid == 15000, "stale HR cap");
    check(q.liveHeart(320000) == 0, "stale hides");
    q.finish(321000);
    check(q.count() == 0 && q.item().logs.get(0).partial, "partial not complete");
    Domain.Session stress = new Domain.Session("home", List.of(time));
    stress.resume(0);
    for (int i = 1; i <= 28800; i++) stress.tick(i * 1000L);
    check(stress.elapsed == 28800000L, "8h timer");
    check(stress.heartValid == 0, "missing stays missing");
    check(
        Domain.catalog("home", Set.of("瑜伽垫")).stream().noneMatch(x -> x.gear.equals("哑铃")),
        "gear filter");
    Domain.Session free = new Domain.Session("gym", Collections.emptyList());
    free.resume(0);
    free.tick(5000);
    check(free.elapsed == 5000 && free.items.isEmpty(), "free timer without a plan");
    free.append(e, 5000);
    check(free.items.size() == 1 && free.running, "append into live training");
    free.record(8, 0, 6000);
    int originalSets = e.sets;
    free.addSet();
    check(
        free.item().exercise.sets == 3 && e.sets == originalSets,
        "extra set does not mutate template");
    free.append(time, 7000);
    check(free.current == 1 && free.count() == 1, "switch keeps recorded sets");
    free.tick(8500);
    free.select(0, 9000);
    check(free.items.get(1).currentMs == 2000, "switch retains partial time");
    Domain.Session batch = new Domain.Session("gym", Collections.singletonList(e));
    batch.resume(0);
    batch.pause(5000);
    batch.appendAll(Collections.singletonList(time), 6000);
    check(
        !batch.running && batch.current == 0 && batch.elapsed == 5000,
        "batch append preserves paused timer and current exercise");
    int sizeBefore = batch.items.size();
    try {
      batch.appendAll(Arrays.asList(e, time), 7000);
      throw new AssertionError("duplicate accepted");
    } catch (IllegalArgumentException expected) {
    }
    check(batch.items.size() == sizeBefore, "duplicate batch does not partially append");
    RouteTrack route = new RouteTrack();
    RouteTrack.MotionGate gate = new RouteTrack.MotionGate();
    check(
        gate.evaluate(39, 116, 5, 0, .1, 10000, 10000) == RouteTrack.MotionGate.STILL,
        "stationary Doppler speed does not accumulate position drift");
    check(
        gate.evaluate(39.003, 116, 5, 0, .1, 13000, 13000) == RouteTrack.MotionGate.STILL,
        "stationary 340m jump rejected despite good claimed precision");
    check(
        gate.evaluate(39, 116, 5, 4, Double.NaN, 16000, 16000) == RouteTrack.MotionGate.WEAK,
        "missing velocity uncertainty cannot establish reliable movement");
    check(
        gate.evaluate(39, 116, 5, 4, .1, 20000, 20000) == RouteTrack.MotionGate.ACQUIRING,
        "first fix waits for stabilization");
    check(
        gate.evaluate(39.0001, 116, 5, 4, .1, 23000, 23000) == RouteTrack.MotionGate.ACQUIRING,
        "two fixes still wait");
    check(
        gate.evaluate(39.0002, 116, 5, 4, .1, 26000, 26000) == RouteTrack.MotionGate.MOVING,
        "three consistent moving fixes establish motion");
    check(
        gate.evaluate(39.0032, 116, 5, 4, .1, 29000, 29000) == RouteTrack.MotionGate.ACQUIRING,
        "distance inconsistent with GNSS speed restarts stabilization");
    gate.reset();
    check(
        gate.evaluate(39.0033, 116, 5, 4, .1, 32000, 32000) == RouteTrack.MotionGate.ACQUIRING,
        "pause resets motion gate");
    check(route.accept(39, 116, 5, 100000, 10000, 10000) != null, "GPS initial point");
    check(
        route.accept(39.0001, 116, 5, 103000, 13000, 13000) != null
            && route.meters > 11
            && route.meters < 12,
        "GPS distance");
    check(
        route.accept(39.0002, 116, 100, 106000, 16000, 16000) == null, "GPS rejects poor accuracy");
    check(route.accept(40, 120, 5, 106000, 16000, 16000) == null, "GPS rejects teleport");
    double distance = route.meters;
    route.breakSegment();
    check(
        route.accept(40, 120, 5, 107000, 17000, 17000) != null
            && route.meters == distance
            && route.segment == 1,
        "pause gap not connected");
    check(route.accept(40.0001, 120, 5, 110000, 20000, 40000) == null, "stale GPS ignored");
    route.breakSegment();
    check(
        route.accept(40.0002, 120, 5, 200000, 1000, 1000) != null && route.meters == distance,
        "reboot clock starts new segment");
    check(RouteTrack.distance(0, 179.9999, 0, -179.9999) < 23, "dateline distance");
    Domain.Exercise inlineEx = new Domain.Exercise("test", "测试动作", "", false, "总重");
    inlineEx.sets = 2;
    Domain.Session inline = new Domain.Session("gym", List.of(inlineEx));
    inline.resume(0);
    inline.recordInline(0, 0, 10, 20, 1000);
    check(
        inline.count() == 1 && inline.running && inline.rest == 60000,
        "inline keeps overall timer running");
    rejects(() -> inline.recordInline(0, 0, 10, 20, 1100), "duplicate inline action");
    rejects(() -> inline.recordInline(0, 1, 10, 20, 1100), "rest requires explicit skip");
    inline.pause(1200);
    inline.rest = 0;
    inline.recordInline(0, 1, 12, 22, 1300);
    check(inline.count() == 2 && !inline.running, "inline recording preserves pause");
    rejects(() -> inline.recordInline(0, 2, 12, 22, 1400), "completed item cannot overflow");
    Domain.Session badInline = new Domain.Session("gym", List.of(inlineEx));
    rejects(
        () -> badInline.recordInline(0, 0, 10, Double.NaN, 1), "reject non-finite inline weight");
    check(badInline.count() == 0, "failed inline record atomic");
    Domain.Exercise one = new Domain.Exercise("squat", "徒手深蹲", "", false, "");
    one.sets = 1;
    Domain.Exercise nextExercise = new Domain.Exercise("push", "俯卧撑", "", false, "");
    Domain.Session progression = new Domain.Session("all", List.of(one));
    progression.resume(0);
    progression.recordInline(0, 0, 12, 0, 10000);
    progression.appendAll(List.of(nextExercise), 11000);
    check(
        progression.current == 1 && progression.running,
        "append after completed item selects next without stopping session");
    progression.tick(15000);
    check(
        progression.items.get(1).activeMs == 4000 && progression.items.get(0).activeMs == 10000,
        "completed item does not swallow next activity time");
    progression.pause(15000);
    Domain.Exercise third = new Domain.Exercise("plank", "平板支撑", "瑜伽垫", true, "");
    progression.appendAll(List.of(third), 18000);
    check(
        !progression.running && progression.current == 1,
        "append during unfinished pause preserves current and pause");
    Domain.Session restBoundary = new Domain.Session("all", List.of(nextExercise));
    restBoundary.resume(0);
    restBoundary.recordInline(0, 0, 12, 0, 1000);
    restBoundary.recordInline(0, 1, 12, 0, 61000);
    check(
        restBoundary.count() == 2,
        "completed rest recognized without an intermediate service tick");
    System.out.println(
        "PASS " + passed + " domain assertions; 8-hour deterministic timing simulation");
  }
}
