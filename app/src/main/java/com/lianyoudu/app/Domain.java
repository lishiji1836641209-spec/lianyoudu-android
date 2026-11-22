package com.lianyoudu.app;

import java.time.LocalDate;
import java.util.*;

/** Pure domain rules. No Android API and no inferred health data. */
public final class Domain {
  private Domain() {}
  static final String TIMED_UNDO_MESSAGE =
      "本组已开始计时，撤销会覆盖本组时长，暂时无法撤销。请返回训练继续记录。";
  static final int DEFAULT_REST_SECONDS = 60;
  static final int MAX_REST_SECONDS = 14400;

  public static final class Exercise {
    public String id, name, gear, unit;
    public boolean timed;
    public int sets = 3, reps = 12, seconds = 60;
    public int restSeconds = DEFAULT_REST_SECONDS;
    public double weight;
    public Integer goalSeconds;

    public boolean distanceBased() {
      return Set.of("bike", "walk", "run", "trainer", "elliptical", "stationary_bike", "incline_treadmill", "stair_climb").contains(id)
          || (id.startsWith("user_") && unit.equals("距离"));
    }

    public boolean usesGps() {
      return id.equals("bike") || id.equals("walk");
    }

    public Exercise(String id, String name, String gear, boolean timed, String unit) {
      this.id = id;
      this.name = name;
      this.gear = gear;
      this.timed = timed;
      this.unit = unit;
    }

    public Exercise copy() {
      Exercise e = new Exercise(id, name, gear, timed, unit);
      e.sets = sets;
      e.reps = reps;
      e.seconds = seconds;
      e.restSeconds = restSeconds;
      e.weight = weight;
      e.goalSeconds = goalSeconds;
      return e;
    }

    public void validate() {
      require(name != null && !name.trim().isEmpty() && name.length() <= 30, "运动名称为1至30个字");
      require(sets >= 1 && sets <= 20, "组数为1至20组");
      require(reps >= 1 && reps <= 200, "次数为1至200次");
      require(seconds >= 1 && seconds <= 14400, "时长为1秒至4小时");
      require(restSeconds >= 0 && restSeconds <= MAX_REST_SECONDS, "组间休息为0秒至4小时");
      require(Double.isFinite(weight) && weight >= 0 && weight <= 500, "重量为0至500千克");
      require(goalSeconds == null || goalSeconds >= 1 && goalSeconds <= 14400, "目标时长为1秒至4小时");
    }
  }

  /** Compare every persisted field before replacing or deleting a library entry. */
  static boolean sameExerciseContents(Exercise first, Exercise second) {
    return first != null && second != null
        && Objects.equals(first.id, second.id)
        && Objects.equals(first.name, second.name)
        && Objects.equals(first.gear, second.gear)
        && Objects.equals(first.unit, second.unit)
        && Objects.equals(first.goalSeconds, second.goalSeconds)
        && first.timed == second.timed
        && first.sets == second.sets
        && first.reps == second.reps
        && first.seconds == second.seconds
        && first.restSeconds == second.restSeconds
        && Double.compare(first.weight, second.weight) == 0;
  }

  public static List<Exercise> catalog(String scene, Set<String> gear) {
    if (scene.equals("all")) {
      LinkedHashMap<String, Exercise> items = new LinkedHashMap<>();
      for (String old : List.of("gym", "home", "outdoor"))
        for (Exercise e : catalog(old, Set.of("瑜伽垫", "哑铃", "弹力带", "壶铃", "骑行台"))) {
          if (e.distanceBased()) e.sets = 1;
          items.putIfAbsent(e.id, e);
        }
      for (Exercise e : ExerciseCatalog.additions()) items.putIfAbsent(e.id, e);
      return new ArrayList<>(items.values());
    }
    List<Exercise> a = new ArrayList<>();
    if (scene.equals("outdoor")) {
      a.add(new Exercise("bike", "骑行", "自行车", true, ""));
      a.add(new Exercise("walk", "户外步行", "", true, ""));
    } else {
      a.add(new Exercise("squat", "徒手深蹲", "", false, ""));
      a.add(new Exercise("push", "俯卧撑", "", false, ""));
      a.add(new Exercise("aerobics", "健身操", "", true, ""));
      a.add(new Exercise("stretch", "拉伸", "瑜伽垫", true, ""));
      a.add(new Exercise("plank", "平板支撑", "瑜伽垫", true, ""));
      a.add(new Exercise("row", "哑铃划船", "哑铃", false, "每只"));
      a.add(new Exercise("band", "弹力带划船", "弹力带", false, ""));
      a.add(new Exercise("bell", "壶铃硬拉", "壶铃", false, "总重"));
      if (scene.equals("gym")) {
        a.add(new Exercise("bench", "杠铃卧推", "卧推架", false, "总重"));
        a.add(new Exercise("pull", "高位下拉", "高位下拉机", false, "器械标称"));
        a.add(new Exercise("press", "器械推胸", "推胸机", false, "器械标称"));
        a.add(new Exercise("leg", "腿举", "腿举机", false, "器械标称"));
        a.add(new Exercise("run", "跑步机", "跑步机", true, ""));
      } else a.add(new Exercise("trainer", "骑行台骑行", "骑行台", true, ""));
    }
    if (scene.equals("home")) a.removeIf(e -> !e.gear.isEmpty() && !gear.contains(e.gear));
    return a;
  }

  public static void add(List<Exercise> plan, Exercise e, int editIndex) {
    e.validate();
    require(plan.size() < 30 || editIndex >= 0, "每个计划最多30项运动");
    for (int i = 0; i < plan.size(); i++)
      if (i != editIndex) require(!plan.get(i).name.equals(e.name.trim()), "此运动已在计划中");
    e.name = e.name.trim();
    if (editIndex >= 0) {
      require(editIndex < plan.size(), "项目已被修改，请重新打开");
      plan.set(editIndex, e.copy());
    } else plan.add(e.copy());
  }

  public static final class Log {
    public int reps;
    public double weight;
    public long ms;
    public boolean partial;

    public Log(int r, double w, long m, boolean p) {
      reps = r;
      weight = w;
      ms = m;
      partial = p;
    }
  }

  public static final class Item {
    public Exercise exercise;
    public List<Log> logs = new ArrayList<>();
    public long currentMs, activeMs;
    public double gpsMeters;
    public Double manualMeters, inclinePercent;
    public Integer stairSteps;

    public double meters() {
      return manualMeters == null ? gpsMeters : manualMeters;
    }

    public long durationMs() {
      return currentMs + logs.stream().mapToLong(l -> l.ms).sum();
    }

    public Double speedKmh() {
      if (durationMs() < 10000) return null;
      double speed = meters() * 3600.0 / durationMs();
      double limit = exercise.id.equals("walk") ? 25 : exercise.id.equals("run") ? 50 : 150;
      return Double.isFinite(speed) && speed >= 0 && speed <= limit ? speed : null;
    }

    public Item(Exercise e) {
      exercise = e.copy();
    }

    public boolean done() {
      return logs.stream().filter(l -> !l.partial).count() >= exercise.sets;
    }
  }

  public record HeartSample(long elapsed, int bpm, int segment) {}

  public static final class Session {
    // User-chosen activity group, independent of equipment/location and calorie classification.
    public String trainingKind = "综合";
    public String id = UUID.randomUUID().toString(), scene, date = LocalDate.now().toString();
    public long startedWall = System.currentTimeMillis(), elapsed, heartValid, rest;
    public long endedWall;
    public boolean running;
    public int current;
    public boolean routeEnabled;
    public RouteTrack route = new RouteTrack();
    public Double calories;
    public List<Double> calorieHistory = new ArrayList<>();
    public List<Item> items = new ArrayList<>();
    public int heart;
    public final List<HeartSample> heartSamples = new ArrayList<>();
    public long heartSum, heartCount;
    public int heartMin, heartMax, heartSegment;
    public long lastHeartElapsed = -1;
    private long lastTick = -1;

    public Session(String scene, List<Exercise> plan) {
      require(plan.size() <= 30, "最多30项运动");
      this.scene = scene;
      for (Exercise e : plan) {
        e.validate();
        items.add(new Item(e));
      }
    }

    void copyLiveClock(Session from) {
      lastTick = from.lastTick;
      lastHeartElapsed = from.lastHeartElapsed;
      heart = from.heart;
      route.gap = from.route.gap;
    }

    public void append(Exercise exercise, long now) {
      exercise.validate();
      require(items.size() < 30, "每次训练最多30项运动");
      pause(now);
      items.add(new Item(exercise));
      current = items.size() - 1;
      rest = 0;
      resume(now);
    }

    public void appendAll(List<Exercise> exercises, long now) {
      require(!exercises.isEmpty(), "请选择运动");
      require(items.size() + exercises.size() <= 30, "每次训练最多30项运动");
      Set<String> names = new HashSet<>();
      for (Item item : items) names.add(item.exercise.name);
      for (Exercise exercise : exercises) {
        exercise.validate();
        require(names.add(exercise.name.trim()), "此运动已在本次训练中");
      }
      tick(now);
      boolean next = items.isEmpty() || item().done();
      int firstNew = items.size();
      for (Exercise exercise : exercises) items.add(new Item(exercise));
      if (next) {
        current = firstNew;
        rest = 0;
        route.breakSegment();
      }
    }

    public void addSet() {
      Item i = item();
      require(i.exercise.sets < 20, "每项最多20组");
      i.exercise.sets++;
    }

    public Item item() {
      return items.get(current);
    }

    public void tick(long now) {
      if (lastTick < 0) {
        lastTick = now;
        return;
      }
      long delta = Math.max(0, now - lastTick);
      long previous = lastTick;
      lastTick = now;
      if (!running) return;
      elapsed += delta;
      long covered =
          lastHeartElapsed < 0
              ? 0
              : Math.max(
                  0,
                  Math.min(now, lastHeartElapsed + 15000) - Math.max(previous, lastHeartElapsed));
      heartValid += Math.min(delta, covered);
      long used = Math.min(rest, delta);
      rest -= used;
      if (!items.isEmpty() && !item().done()) item().activeMs += delta - used;
      if (!items.isEmpty() && item().exercise.timed && !item().done())
        item().currentMs += delta - used;
    }

    public void resume(long now) {
      if (running) {
        tick(now);
        return;
      }
      // Session time is independent of completion of the selected exercise.
      // Paused notifications may keep the live reading fresh, but must not bridge the chart.
      if (!running && !heartSamples.isEmpty()) heartSegment++;
      lastTick = now;
      running = true;
    }

    public void pause(long now) {
      tick(now);
      running = false;
    }

    public void select(int index, long now) {
      require(index >= 0 && index < items.size(), "运动项目不存在");
      pause(now);
      if (index != current) route.breakSegment();
      current = index;
      rest = 0;
    }

    public void record(int reps, double weight, long now) {
      tick(now);
      Item i = item();
      require(!i.done(), "本项已完成");
      require(rest == 0, "请先结束休息");
      if (i.exercise.timed) {
        require(i.currentMs >= 1000, "计时不足1秒");
        i.logs.add(new Log(0, 0, i.currentMs, false));
        i.currentMs = 0;
      } else {
        require(reps >= 1 && reps <= 200, "次数为1至200次");
        require(Double.isFinite(weight) && weight >= 0 && weight <= 500, "重量为0至500千克");
        i.logs.add(new Log(reps, i.exercise.unit.isEmpty() ? 0 : weight, 0, false));
      }
      beginRestAfterSet(i);
      if (i.done()) running = false;
    }

    public void recordInline(int index, int expectedSet, int amount, double weight, long now) {
      require(index >= 0 && index < items.size(), "运动项目不存在");
      Item item = items.get(index);
      require(item.logs.size() == expectedSet && !item.done(), "本组状态已变化，请重新查看");
      require(amount >= 1 && amount <= (item.exercise.timed ? 14400 : 200), "请检查次数或时长");
      require(Double.isFinite(weight) && weight >= 0 && weight <= 500, "重量为0至500千克");
      tick(now);
      require(rest == 0, "请先结束休息");
      item.logs.add(
          item.exercise.timed
              ? new Log(0, 0, amount * 1000L, false)
              : new Log(amount, item.exercise.unit.isEmpty() ? 0 : weight, 0, false));
      if (item.exercise.timed) item.currentMs = 0;
      if (current != index) route.breakSegment();
      current = index;
      beginRestAfterSet(item);
    }

    private void beginRestAfterSet(Item item) {
      rest = item.done() || item.exercise.distanceBased() ? 0 : item.exercise.restSeconds * 1000L;
    }

    public void setRestRemaining(int seconds, long now) {
      require(seconds >= 0 && seconds <= MAX_REST_SECONDS, "剩余休息为0秒至4小时");
      tick(now);
      require(rest > 0, "休息已结束，请返回训练");
      rest = seconds * 1000L;
    }

    public void endRest(long now) {
      tick(now);
      rest = 0;
    }

    void requireUndoSafe(int index, long now) {
      require(index >= 0 && index < items.size(), "运动项目不存在");
      Item i = items.get(index);
      require(!i.logs.isEmpty(), "暂无记录");
      long pending = 0;
      if (running && index == current && !i.done() && lastTick >= 0)
        pending = Math.max(0, Math.max(0, now - lastTick) - rest);
      require(
          !i.exercise.timed || (i.currentMs == 0 && pending == 0),
          TIMED_UNDO_MESSAGE);
    }

    public void undoAt(int index, long now) {
      requireUndoSafe(index, now);
      boolean wasRunning = running;
      select(index, now);
      undo(now);
      if (wasRunning) resume(now);
    }

    public void undo(long now) {
      requireUndoSafe(current, now);
      pause(now);
      Item i = item();
      Log l = i.logs.remove(i.logs.size() - 1);
      if (i.exercise.timed) i.currentMs = l.ms;
      rest = 0;
    }

    public void finish(long now) {
      pause(now);
      for (Item i : items)
        if (i.currentMs >= 1000) {
          i.logs.add(new Log(0, 0, i.currentMs, !i.exercise.distanceBased()));
          i.currentMs = 0;
        }
    }

    public int count() {
      int n = 0;
      for (Item i : items)
        if (!i.exercise.distanceBased()) for (Log l : i.logs) if (!l.partial) n++;
      return n;
    }

    public int target() {
      int n = 0;
      for (Item i : items) if (!i.exercise.distanceBased()) n += i.exercise.sets;
      return n;
    }

    public int liveHeart(long now) {
      return lastHeartElapsed >= 0 && now - lastHeartElapsed <= 15000 ? heart : 0;
    }

    public void heart(int value, long now) {
      tick(now);
      if (value > 0 && value <= 250) {
        if (running) {
          if (lastHeartElapsed < 0 || now - lastHeartElapsed > 15000) heartSegment++;
          heartSum += value;
          heartCount++;
          heartMin = heartMin == 0 ? value : Math.min(heartMin, value);
          heartMax = Math.max(heartMax, value);
          HeartSample prior =
              heartSamples.isEmpty() ? null : heartSamples.get(heartSamples.size() - 1);
          if (prior == null
              || prior.segment() != heartSegment
              || elapsed - prior.elapsed() >= 5000) {
            if (heartSamples.size() >= 4096)
              for (int i = heartSamples.size() - 2; i > 0; i -= 2) heartSamples.remove(i);
            heartSamples.add(new HeartSample(elapsed, value, heartSegment));
          }
        }
        heart = value;
        lastHeartElapsed = now;
      }
    }

    public void disconnect() {
      heart = 0;
      lastHeartElapsed = -1;
    }
  }

  public static String date(String s, LocalDate today) {
    try {
      LocalDate d = LocalDate.parse(s);
      require(!d.isAfter(today) && d.getYear() >= 1900, "请选择1900年至今天的日期");
      return d.toString();
    } catch (java.time.DateTimeException e) {
      throw new IllegalArgumentException("日期格式为年-月-日");
    }
  }

  public static double weight(String s) {
    double n = number(s, "体重");
    require(
        n >= 1 && n <= 500 && Math.abs(n * 10 - Math.rint(n * 10)) < 0.000001, "体重为1至500千克，保留一位小数");
    return n;
  }

  public static double number(String s, String label) {
    try {
      double n = Double.parseDouble(s.trim());
      require(Double.isFinite(n), label + "格式不正确");
      return n;
    } catch (Exception e) {
      throw new IllegalArgumentException("请填写有效的" + label);
    }
  }

  public static int integer(String s, int min, int max, String label) {
    double n = number(s, label);
    require(n == Math.rint(n) && n >= min && n <= max, label + "为" + min + "至" + max + "的整数");
    return (int) n;
  }

  public static int heartRate(byte[] b) {
    if (b == null || b.length < 2) return 0;
    boolean wide = (b[0] & 1) != 0;
    if (wide && b.length < 3) return 0;
    int v = wide ? (b[1] & 255) | ((b[2] & 255) << 8) : b[1] & 255;
    return v >= 1 && v <= 250 ? v : 0;
  }

  public static String clock(long ms) {
    long n = Math.max(0, ms) / 1000;
    return n >= 3600 ? String.format(Locale.ROOT, "%d小时%02d分%02d秒", n / 3600, n / 60 % 60, n % 60)
        : String.format(Locale.ROOT, "%d分%02d秒", n / 60, n % 60);
  }

  public static void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(message);
  }
}
