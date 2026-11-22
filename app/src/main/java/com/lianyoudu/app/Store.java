package com.lianyoudu.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** SQLite transactions are the source of truth. Failed writes are never reported as success. */
public final class Store extends SQLiteOpenHelper {
  // Main-page view reuse is invalidated by every persistent mutation, including failed attempts.
  // It is not persisted and never substitutes for a database transaction.
  volatile long uiRevision;

  public Store(Context c) {
    super(c, "lianyoudu.db", null, 2);
    setWriteAheadLoggingEnabled(true);
  }

  @Override
  public void onConfigure(SQLiteDatabase d) {
    d.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase d) {
    d.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    d.execSQL("CREATE TABLE plans (scene TEXT PRIMARY KEY, data TEXT NOT NULL)");
    d.execSQL(
        "CREATE TABLE workouts (id TEXT PRIMARY KEY, date TEXT NOT NULL, started INTEGER NOT NULL,"
            + " active INTEGER NOT NULL, data TEXT NOT NULL)");
    d.execSQL("CREATE UNIQUE INDEX one_active ON workouts(active) WHERE active=1");
    createRouteTable(d);
    d.execSQL(
        "CREATE TABLE weights (date TEXT PRIMARY KEY, kg REAL NOT NULL CHECK(kg>=1 AND kg<=500))");
    d.execSQL(
        "CREATE TABLE meals (id TEXT PRIMARY KEY, date TEXT NOT NULL, slot TEXT NOT NULL, data TEXT"
            + " NOT NULL, UNIQUE(date,slot))");
    d.execSQL(
        "CREATE TABLE photos (id TEXT PRIMARY KEY, path TEXT NOT NULL, created INTEGER NOT NULL,"
            + " meal TEXT REFERENCES meals(id) ON DELETE SET NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int from, int to) {
    if (from < 2) createRouteTable(d);
  }

  private static void createRouteTable(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE IF NOT EXISTS route_points (id INTEGER PRIMARY KEY AUTOINCREMENT, workout"
            + " TEXT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE, lat REAL NOT NULL, lon"
            + " REAL NOT NULL, accuracy REAL NOT NULL, wall INTEGER NOT NULL, elapsed INTEGER NOT"
            + " NULL, segment INTEGER NOT NULL)");
    d.execSQL("CREATE INDEX IF NOT EXISTS route_workout ON route_points(workout,id)");
  }

  public synchronized long routeRevision(String id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id FROM route_points WHERE workout=? ORDER BY id DESC LIMIT 1",
                new String[] {id})) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }

  public synchronized java.util.List<RouteTrack.Point> routePoints(String id) {
    java.util.List<RouteTrack.Point> list = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT lat,lon,accuracy,wall,elapsed,segment FROM route_points WHERE workout=?"
                    + " ORDER BY id",
                new String[] {id})) {
      while (c.moveToNext())
        list.add(
            new RouteTrack.Point(
                c.getDouble(0),
                c.getDouble(1),
                c.getDouble(2),
                c.getLong(3),
                c.getLong(4),
                c.getInt(5)));
    }
    return list;
  }

  public synchronized void routePoint(Domain.Session session, RouteTrack.Point point) {
    uiRevision++;
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("workout", session.id);
      v.put("lat", point.latitude);
      v.put("lon", point.longitude);
      v.put("accuracy", point.accuracy);
      v.put("wall", point.wall);
      v.put("elapsed", point.elapsed);
      v.put("segment", point.segment);
      d.insertOrThrow("route_points", null, v);
      saveSession(session, true);
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public synchronized String setting(String key, String fallback) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT value FROM settings WHERE key=?", new String[] {key})) {
      return c.moveToFirst() ? c.getString(0) : fallback;
    }
  }

  public synchronized void setSetting(String k, String v) {
    uiRevision++;
    ContentValues c = new ContentValues();
    c.put("key", k);
    c.put("value", v);
    if (getWritableDatabase()
            .insertWithOnConflict("settings", null, c, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new IllegalStateException("设置保存失败");
  }

  public synchronized List<Domain.Exercise> customExercises() {
    List<Domain.Exercise> result = new ArrayList<>();
    try {
      JSONArray rows = new JSONArray(setting("customExercises", "[]"));
      for (int i = 0; i < rows.length(); i++) result.add(exercise(rows.getJSONObject(i)));
      return result;
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized List<Domain.Exercise> exerciseLibrary() {
    List<Domain.Exercise> result = Domain.catalog("all", gear());
    result.addAll(customExercises());
    return result;
  }

  /** Only a unique identity in the current library can be used as a favorite key. */
  public synchronized String favoriteUnavailableReason(String id) {
    return favoriteUnavailableReason(id, exerciseLibrary());
  }

  static String favoriteUnavailableReason(String id, List<Domain.Exercise> library) {
    if (id == null || id.isBlank())
      return "该动作不在运动库中，暂不能收藏。";
    long matches = library.stream().filter(e -> id.equals(e.id)).count();
    if (matches == 0) return "该动作不在运动库中，暂不能收藏。";
    if (matches != 1 || id.equals("custom")) return "该动作暂不能收藏，请重新打开运动库查看。";
    return "";
  }

  public synchronized boolean toggleExerciseFavorite(String id) {
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransaction();
    try {
      String reason = favoriteUnavailableReason(id);
      Domain.require(reason.isEmpty(), reason);
      Set<String> favorites = ExerciseCatalog.favorites(setting("exercise.favorites", ""));
      if (!favorites.remove(id)) favorites.add(id);
      setSetting("exercise.favorites", String.join(",", favorites));
      boolean selected = favorites.contains(id);
      database.setTransactionSuccessful();
      return selected;
    } finally {
      database.endTransaction();
    }
  }

  public synchronized void saveCustomExercise(Domain.Exercise value) {
    saveCustomExercise(value, false);
  }

  public synchronized void saveCustomExercise(Domain.Exercise value, boolean requireExisting) {
    saveCustomExercise(value, requireExisting, null);
  }

  static final class ExerciseConflict extends IllegalStateException {
    ExerciseConflict(String message) { super(message); }
  }

  /** Read the current library entry, not a workout's historical snapshot. */
  public synchronized Domain.Exercise currentCustomExercise(String id) {
    return uniqueCustomExercise(id, customExercises()).copy();
  }

  private static Domain.Exercise uniqueCustomExercise(String id, List<Domain.Exercise> rows) {
    if (id == null || !id.startsWith("user_"))
      throw new ExerciseConflict("该动作不在运动库中，请重新打开运动库。");
    List<Domain.Exercise> matches = new ArrayList<>();
    for (Domain.Exercise row : rows) if (id.equals(row.id)) matches.add(row);
    if (matches.size() != 1)
      throw new ExerciseConflict("该动作已从运动库移除或信息有误，请重新打开运动库。");
    return matches.get(0);
  }

  public synchronized void saveCustomExercise(Domain.Exercise value, Domain.Exercise expected) {
    if (expected == null || !Objects.equals(value.id, expected.id))
      throw new ExerciseConflict("无法确认原始内容，请重新载入最新内容。");
    saveCustomExercise(value, true, expected);
  }

  private void saveCustomExercise(
      Domain.Exercise value, boolean requireExisting, Domain.Exercise expected) {
    uiRevision++;
    value.validate();
    Domain.require(value.id.startsWith("user_"), "自定义运动编号无效");
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransaction();
    try {
      List<Domain.Exercise> existing = customExercises();
      if (requireExisting) {
        Domain.Exercise current = uniqueCustomExercise(value.id, existing);
        if (expected != null && !Domain.sameExerciseContents(current, expected))
          throw new ExerciseConflict("运动库内容已更新，当前修改未保存。请重新载入最新内容。");
      }
      Domain.require(
          existing.size() < 200 || existing.stream().anyMatch(e -> e.id.equals(value.id)),
          "最多保存200项自定义运动");
      for (Domain.Exercise e : exerciseLibrary())
        Domain.require(e.id.equals(value.id) || !e.name.equals(value.name.trim()), "已有同名运动");
      existing.removeIf(e -> e.id.equals(value.id));
      existing.add(value.copy());
      writeCustomExercises(existing);
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  public synchronized void removeCustomExercise(String id) {
    removeCustomExercise(id, null);
  }

  public synchronized void removeCustomExercise(String id, Domain.Exercise expected) {
    SQLiteDatabase database = getWritableDatabase();
    database.beginTransaction();
    try {
      List<Domain.Exercise> rows = customExercises();
      Domain.Exercise current = uniqueCustomExercise(id, rows);
      if (expected != null && !Domain.sameExerciseContents(current, expected))
        throw new ExerciseConflict("运动库内容已更新，请重新确认要删除的运动。");
      Set<String> favorites = ExerciseCatalog.favorites(setting("exercise.favorites", ""));
      rows.removeIf(e -> id.equals(e.id));
      favorites.remove(id);
      writeCustomExercises(rows);
      setSetting("exercise.favorites", String.join(",", favorites));
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  private void writeCustomExercises(List<Domain.Exercise> rows) {
    try {
      JSONArray data = new JSONArray();
      for (Domain.Exercise e : rows) data.put(exercise(e));
      setSetting("customExercises", data.toString());
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized List<JSONObject> energyDays() {
    List<JSONObject> days = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT value FROM settings WHERE key LIKE 'energy.day.%' ORDER BY key DESC LIMIT"
                    + " 30",
                null)) {
      while (c.moveToNext()) days.add(new JSONObject(c.getString(0)));
    } catch (JSONException e) {
      throw corrupt(e);
    }
    return days;
  }

  /** Copy old scene plans once. Keep original rows and all workout history untouched. */
  public synchronized void unifyPlans() {
    uiRevision++;
    if (setting("unifiedPlanVersion", "").equals("1")) return;
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      List<Domain.Exercise> combined = new ArrayList<>(plan("all"));
      Set<String> names = new HashSet<>();
      for (Domain.Exercise e : combined) names.add(e.name);
      int overflow = 0;
      for (String legacy : List.of("gym", "home", "outdoor"))
        for (Domain.Exercise e : plan(legacy))
          if (names.add(e.name)) {
            if (combined.size() < 30) {
              Domain.Exercise copy = e.copy();
              if (copy.distanceBased()) copy.sets = 1;
              combined.add(copy);
            } else overflow++;
          }
      setSetting("planMigrationOverflow", String.valueOf(overflow));
      plan("all", combined);
      setSetting("unifiedPlanVersion", "1");
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public synchronized void planWithDefaults(
      String scene, List<Domain.Exercise> plan, List<Domain.Exercise> added) {
    uiRevision++;
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      plan(scene, plan);
      exerciseDefaults(scene, added);
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public synchronized void sessionWithDefaults(
      Domain.Session session, List<Domain.Exercise> added) {
    uiRevision++;
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      saveSession(session, true);
      exerciseDefaults(session.scene, added);
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  private void exerciseDefaults(String scene, List<Domain.Exercise> added) {
    try {
      for (Domain.Exercise exercise : added)
        setSetting("exercise.last." + scene + "." + exercise.id, exercise(exercise).toString());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public Set<String> gear() {
    return new LinkedHashSet<>(Arrays.asList(setting("gear", "瑜伽垫").split(",")));
  }

  public static JSONObject exercise(Domain.Exercise e) throws JSONException {
    return new JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .put("gear", e.gear)
        .put("unit", e.unit)
        .put("timed", e.timed)
        .put("sets", e.sets)
        .put("reps", e.reps)
        .put("seconds", e.seconds)
        .put("restSeconds", e.restSeconds)
        .put("goalSeconds", e.goalSeconds == null ? JSONObject.NULL : e.goalSeconds)
        .put("weight", e.weight);
  }

  public static Domain.Exercise exercise(JSONObject o) throws JSONException {
    Domain.Exercise e =
        new Domain.Exercise(
            o.getString("id"),
            o.getString("name"),
            o.getString("gear"),
            o.getBoolean("timed"),
            o.getString("unit"));
    if (e.id.equals("bike") && e.name.equals("公路车骑行")) e.name = "骑行";
    e.sets = o.getInt("sets");
    e.reps = o.getInt("reps");
    e.seconds = o.getInt("seconds");
    e.restSeconds = !o.has("restSeconds") || o.isNull("restSeconds")
        ? Domain.DEFAULT_REST_SECONDS : o.getInt("restSeconds");
    e.goalSeconds = !o.has("goalSeconds") || o.isNull("goalSeconds") ? null : o.getInt("goalSeconds");
    e.weight = o.getDouble("weight");
    e.validate();
    return e;
  }

  public synchronized List<Domain.Exercise> plan(String scene) {
    List<Domain.Exercise> p = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT data FROM plans WHERE scene=?", new String[] {scene})) {
      if (c.moveToFirst()) {
        JSONArray a = new JSONArray(c.getString(0));
        for (int i = 0; i < a.length(); i++) p.add(exercise(a.getJSONObject(i)));
      }
      return p;
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized void plan(String scene, List<Domain.Exercise> p) {
    uiRevision++;
    try {
      JSONArray a = new JSONArray();
      for (Domain.Exercise e : p) {
        e.validate();
        a.put(exercise(e));
      }
      ContentValues c = new ContentValues();
      c.put("scene", scene);
      c.put("data", a.toString());
      if (getWritableDatabase()
              .insertWithOnConflict("plans", null, c, SQLiteDatabase.CONFLICT_REPLACE)
          < 0) throw new IllegalStateException("计划保存失败");
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public static JSONObject session(Domain.Session q) throws JSONException {
    JSONObject o =
        new JSONObject()
            .put("id", q.id)
            .put("scene", q.scene)
            .put("trainingKind", q.trainingKind)
            .put("date", q.date)
            .put("started", q.startedWall)
            .put("ended", q.endedWall)
            .put("elapsed", q.elapsed)
            .put("valid", q.heartValid)
            .put("rest", q.rest)
            .put("running", q.running)
            .put("current", q.current)
            .put("calories", q.calories == null ? JSONObject.NULL : q.calories);
    o.put("routeEnabled", q.routeEnabled)
        .put("routeMeters", q.route.meters)
        .put("routeSegment", q.route.segment);
    if (q.route.last != null) {
      RouteTrack.Point p = q.route.last;
      o.put(
          "routeLast",
          new JSONArray()
              .put(p.latitude)
              .put(p.longitude)
              .put(p.accuracy)
              .put(p.wall)
              .put(p.elapsed)
              .put(p.segment));
    }
    JSONArray history = new JSONArray();
    for (Double n : q.calorieHistory) history.put(n == null ? JSONObject.NULL : n);
    o.put("calorieHistory", history);
    JSONArray heart = new JSONArray();
    for (Domain.HeartSample sample : q.heartSamples)
      heart.put(new JSONArray().put(sample.elapsed()).put(sample.bpm()).put(sample.segment()));
    o.put("heartSamples", heart)
        .put("heartSum", q.heartSum)
        .put("heartCount", q.heartCount)
        .put("heartMin", q.heartMin)
        .put("heartMax", q.heartMax)
        .put("heartSegment", q.heartSegment);
    JSONArray a = new JSONArray();
    for (Domain.Item i : q.items) {
      JSONObject x = new JSONObject().put("exercise", exercise(i.exercise)).put("ms", i.currentMs);
      x.put("activeMs", i.activeMs);
      x.put("inclinePercent", i.inclinePercent == null ? JSONObject.NULL : i.inclinePercent);
      x.put("stairSteps", i.stairSteps == null ? JSONObject.NULL : i.stairSteps);
      x.put("gpsMeters", i.gpsMeters)
          .put("manualMeters", i.manualMeters == null ? JSONObject.NULL : i.manualMeters);
      JSONArray ls = new JSONArray();
      for (Domain.Log l : i.logs)
        ls.put(new JSONArray().put(l.reps).put(l.weight).put(l.ms).put(l.partial));
      x.put("logs", ls);
      a.put(x);
    }
    return o.put("items", a);
  }

  public static Domain.Session session(JSONObject o) throws JSONException {
    JSONArray a = o.getJSONArray("items");
    Domain.require(a.length() >= 0 && a.length() <= 30, "训练记录损坏");
    List<Domain.Exercise> es = new ArrayList<>();
    for (int i = 0; i < a.length(); i++)
      es.add(exercise(a.getJSONObject(i).getJSONObject("exercise")));
    Domain.Session q = new Domain.Session(o.getString("scene"), es);
    String kind = o.optString("trainingKind", "综合");
    q.trainingKind = List.of("综合", "力量", "有氧", "核心", "拉伸").contains(kind) ? kind : "综合";
    q.id = o.getString("id");
    q.date = o.getString("date");
    q.startedWall = o.getLong("started");
    q.endedWall = o.optLong("ended", q.startedWall + o.getLong("elapsed"));
    q.elapsed = o.getLong("elapsed");
    q.heartValid = o.getLong("valid");
    q.heartSum = o.optLong("heartSum", 0);
    q.heartCount = o.optLong("heartCount", 0);
    q.heartMin = o.optInt("heartMin", 0);
    q.heartMax = o.optInt("heartMax", 0);
    q.heartSegment = o.optInt("heartSegment", 0);
    JSONArray heart = o.optJSONArray("heartSamples");
    if (heart != null) {
      Domain.require(heart.length() <= 4096, "心率记录过长");
      long previous = -1;
      for (int i = 0; i < heart.length(); i++) {
        JSONArray sample = heart.getJSONArray(i);
        long elapsed = sample.getLong(0);
        int bpm = sample.getInt(1), segment = sample.getInt(2);
        Domain.require(
            elapsed >= previous && elapsed <= q.elapsed && bpm > 0 && bpm <= 250 && segment >= 0,
            "心率记录损坏");
        q.heartSamples.add(new Domain.HeartSample(elapsed, bpm, segment));
        previous = elapsed;
      }
    }
    q.rest = o.getLong("rest");
    q.current = o.getInt("current");
    q.running = o.optBoolean("running");
    q.calories = o.isNull("calories") ? null : o.getDouble("calories");
    q.routeEnabled = o.optBoolean("routeEnabled");
    q.route.meters = o.optDouble("routeMeters", 0);
    q.route.segment = o.optInt("routeSegment", 0);
    Domain.require(Double.isFinite(q.route.meters) && q.route.meters >= 0, "轨迹数据损坏");
    JSONArray routeLast = o.optJSONArray("routeLast");
    if (routeLast != null)
      q.route.last =
          new RouteTrack.Point(
              routeLast.getDouble(0),
              routeLast.getDouble(1),
              routeLast.getDouble(2),
              routeLast.getLong(3),
              routeLast.getLong(4),
              routeLast.getInt(5));
    Domain.require(
        q.current >= 0
            && (es.isEmpty() ? q.current == 0 : q.current < es.size())
            && q.elapsed >= 0
            && q.heartValid >= 0
            && q.heartValid <= q.elapsed,
        "训练记录损坏");
    JSONArray hist = o.optJSONArray("calorieHistory");
    if (hist != null)
      for (int i = 0; i < hist.length(); i++)
        q.calorieHistory.add(hist.isNull(i) ? null : hist.getDouble(i));
    for (int i = 0; i < a.length(); i++) {
      Domain.Item item = q.items.get(i);
      item.currentMs = a.getJSONObject(i).getLong("ms");
      item.activeMs = Math.max(0, a.getJSONObject(i).optLong("activeMs", 0));
      JSONObject itemData = a.getJSONObject(i);
      item.inclinePercent = !itemData.has("inclinePercent") || itemData.isNull("inclinePercent") ? null : itemData.getDouble("inclinePercent");
      item.stairSteps = !itemData.has("stairSteps") || itemData.isNull("stairSteps") ? null : itemData.getInt("stairSteps");
      Domain.require(item.inclinePercent == null || Double.isFinite(item.inclinePercent) && item.inclinePercent >= 0, "坡度数据损坏");
      Domain.require(item.stairSteps == null || item.stairSteps >= 0, "台阶数据损坏");
      item.gpsMeters =
          a.getJSONObject(i)
              .optDouble(
                  "gpsMeters", es.size() == 1 && item.exercise.usesGps() ? q.route.meters : 0);
      item.manualMeters =
          a.getJSONObject(i).isNull("manualMeters")
              ? null
              : a.getJSONObject(i).getDouble("manualMeters");
      Domain.require(
          Double.isFinite(item.gpsMeters)
              && item.gpsMeters >= 0
              && (item.manualMeters == null
                  || Double.isFinite(item.manualMeters)
                      && item.manualMeters >= 0
                      && item.manualMeters <= 2000000),
          "距离数据损坏");
      JSONArray ls = a.getJSONObject(i).getJSONArray("logs");
      Domain.require(ls.length() <= 21, "训练记录损坏");
      for (int j = 0; j < ls.length(); j++) {
        JSONArray l = ls.getJSONArray(j);
        item.logs.add(new Domain.Log(l.getInt(0), l.getDouble(1), l.getLong(2), l.getBoolean(3)));
      }
    }
    return q;
  }

  public synchronized Domain.Session active() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT data FROM workouts WHERE active=1", null)) {
      if (!c.moveToFirst()) return null;
      Domain.Session q = session(new JSONObject(c.getString(0)));
      // The row's active flag is authoritative, including legacy rows without "ended".
      q.endedWall = 0;
      q.running = false;
      q.disconnect();
      return q;
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized void saveSession(Domain.Session q, boolean active) {
    uiRevision++;
    try {
      ContentValues c = new ContentValues();
      c.put("id", q.id);
      c.put("date", q.date);
      c.put("started", q.startedWall);
      c.put("active", active ? 1 : 0);
      JSONObject data = session(q);
      if (active) data.put("ended", 0);
      c.put("data", data.toString());
      SQLiteDatabase d = getWritableDatabase();
      d.beginTransaction();
      try {
        int updated = d.update("workouts", c, "id=?", new String[] {q.id});
        if (updated == 0) d.insertOrThrow("workouts", null, c);
        d.setTransactionSuccessful();
      } finally {
        d.endTransaction();
      }
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized List<Domain.Session> workouts() {
    return workouts(100, 0);
  }

  public synchronized List<Domain.Session> workouts(int limit, int offset) {
    Domain.require(limit >= 1 && limit <= 100 && offset >= 0, "记录分页无效");
    return readWorkouts(
        "SELECT data FROM workouts WHERE active=0 ORDER BY started DESC,id DESC LIMIT ? OFFSET ?",
        new String[] {String.valueOf(limit), String.valueOf(offset)});
  }

  public synchronized List<Domain.Session> workoutsBetween(LocalDate start, LocalDate end) {
    return readWorkouts(
        "SELECT data FROM workouts WHERE active=0 AND date>=? AND date<? ORDER BY started DESC,id"
            + " DESC",
        new String[] {start.toString(), end.toString()});
  }

  public synchronized int workoutCount() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT COUNT(*) FROM workouts WHERE active=0", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  /** Bounded memory; called on a worker, and searches older workouts beyond the first page. */
  public List<ExerciseHistory.Entry> exerciseHistory(Domain.Exercise exercise, int limit) {
    Domain.require(limit > 0 && limit <= 20, "历史记录数量无效");
    List<ExerciseHistory.Entry> result = new ArrayList<>();
    for (int offset = 0; ; offset += 100) {
      if (Thread.currentThread().isInterrupted()) return result;
      List<Domain.Session> batch = workouts(100, offset);
      for (Domain.Session session : batch) {
        ExerciseHistory.Entry entry = ExerciseHistory.from(session, exercise);
        if (entry != null) result.add(entry);
        if (result.size() == limit) return result;
      }
      if (batch.size() < 100) return result;
    }
  }

  public synchronized Domain.Session workout(String id) {
    List<Domain.Session> found =
        readWorkouts("SELECT data FROM workouts WHERE active=0 AND id=?", new String[] {id});
    return found.isEmpty() ? null : found.get(0);
  }

  private List<Domain.Session> readWorkouts(String query, String[] args) {
    List<Domain.Session> a = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(query, args)) {
      while (c.moveToNext()) a.add(session(new JSONObject(c.getString(0))));
      return a;
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized Domain.Session latestWorkout() {
    List<Domain.Session> all = workouts(1, 0);
    return all.isEmpty() ? null : all.get(0);
  }

  public synchronized void removeWorkout(String id) {
    uiRevision++;
    getWritableDatabase().delete("workouts", "id=? AND active=0", new String[] {id});
  }

  public synchronized List<Weight> weights() {
    List<Weight> a = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT date,kg FROM weights ORDER BY date DESC", null)) {
      while (c.moveToNext()) a.add(new Weight(c.getString(0), c.getDouble(1)));
    }
    return a;
  }

  public synchronized Weight weightOn(String date) {
    return firstWeight("SELECT date,kg FROM weights WHERE date=?", date);
  }

  public synchronized Weight latestWeightOnOrBefore(LocalDate day) {
    return firstWeight("SELECT date,kg FROM weights WHERE date<=? ORDER BY date DESC LIMIT 1",
        day.toString());
  }

  private Weight firstWeight(String query, String date) {
    try (Cursor c = getReadableDatabase().rawQuery(query, new String[] {date})) {
      return c.moveToFirst() ? new Weight(c.getString(0), c.getDouble(1)) : null;
    }
  }

  public static final class Weight {
    public final String date;
    public final double kg;

    public Weight(String d, double k) {
      date = d;
      kg = k;
    }
  }

  public synchronized void weight(String oldDate, String date, double kg, boolean replace) {
    uiRevision++;
    Domain.date(date, LocalDate.now());
    Domain.weight(Double.toString(kg));
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      if (!date.equals(oldDate) && !replace) {
        try (Cursor c = d.rawQuery("SELECT date FROM weights WHERE date=?", new String[] {date})) {
          if (c.moveToFirst()) throw new IllegalArgumentException("该日期已有体重记录");
        }
      }
      ContentValues v = new ContentValues();
      v.put("date", date);
      v.put("kg", kg);
      if (d.insertWithOnConflict("weights", null, v, SQLiteDatabase.CONFLICT_REPLACE) < 0)
        throw new IllegalStateException("体重保存失败");
      if (oldDate != null && !date.equals(oldDate))
        d.delete("weights", "date=?", new String[] {oldDate});
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public synchronized void removeWeight(String date) {
    uiRevision++;
    getWritableDatabase().delete("weights", "date=?", new String[] {date});
  }

  public synchronized List<JSONObject> meals() {
    return readMeals("SELECT data FROM meals ORDER BY date DESC, rowid DESC", null);
  }

  public synchronized List<JSONObject> meals(LocalDate day) {
    return readMeals("SELECT data FROM meals WHERE date=? ORDER BY rowid DESC",
        new String[] {day.toString()});
  }

  public synchronized List<JSONObject> meals(int limit) {
    return readMeals("SELECT data FROM meals ORDER BY date DESC, rowid DESC LIMIT ?",
        new String[] {Integer.toString(limit)});
  }

  private List<JSONObject> readMeals(String query, String[] args) {
    List<JSONObject> a = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(query, args)) {
      while (c.moveToNext()) a.add(new JSONObject(c.getString(0)));
      return a;
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized String conflictingMeal(String date, String slot, String id) {
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT id FROM meals WHERE date=? AND slot=? AND id<>?", new String[] {date, slot, id})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  public synchronized void meal(JSONObject meal, List<String> photos, boolean replace) {
    uiRevision++;
    try {
      String id = meal.getString("id"),
          date = Domain.date(meal.getString("date"), LocalDate.now()),
          slot = meal.getString("slot");
      Domain.require(Arrays.asList("早餐", "午餐", "晚餐", "加餐").contains(slot), "请选择餐次");
      JSONArray foods = meal.getJSONArray("foods");
      Domain.require(foods.length() > 0 && foods.length() <= 30, "请添加1至30项食物");
      for (int i = 0; i < foods.length(); i++) {
        JSONObject f = foods.getJSONObject(i);
        Domain.require(
            !f.getString("name").trim().isEmpty() && f.getString("name").length() <= 50, "请填写食物名称");
        double g = f.getDouble("grams");
        Domain.require(Double.isFinite(g) && g > 0 && g <= 10000, "食用量应大于0且不超过10000克");
        if (f.has("kcal") || f.has("kcal100")) {
          double rate = f.getDouble("kcal100"), energy = f.getDouble("kcal");
          Domain.require(
              Double.isFinite(rate)
                  && rate >= 0
                  && rate <= 900
                  && Double.isFinite(energy)
                  && Math.abs(energy - Math.round(rate * g) / 100.0) < .001,
              "食物热量与克数不一致");
        }
      }
      SQLiteDatabase d = getWritableDatabase();
      d.beginTransaction();
      try {
        String conflict = conflictingMeal(date, slot, id);
        if (conflict != null) {
          if (!replace) throw new IllegalArgumentException("这餐已有记录");
          ContentValues move = new ContentValues();
          move.putNull("meal");
          d.update("photos", move, "meal=?", new String[] {conflict});
          d.delete("meals", "id=?", new String[] {conflict});
        }
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("date", date);
        v.put("slot", slot);
        v.put("data", meal.toString());
        if (d.update("meals", v, "id=?", new String[] {id}) == 0) d.insertOrThrow("meals", null, v);
        for (String photo : photos) {
          ContentValues pv = new ContentValues();
          pv.put("meal", id);
          d.update("photos", pv, "id=? AND meal IS NULL", new String[] {photo});
        }
        d.setTransactionSuccessful();
      } finally {
        d.endTransaction();
      }
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  public synchronized void removeMeal(String id) {
    uiRevision++;
    getWritableDatabase().delete("meals", "id=?", new String[] {id});
  }

  public synchronized boolean pendingHash(String hash) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id FROM photos WHERE meal IS NULL AND (id=? OR id LIKE ?) LIMIT 1",
                new String[] {hash, hash + "-%"})) {
      return c.moveToFirst();
    }
  }

  public synchronized void photo(String id, String path) {
    uiRevision++;
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("path", path);
    v.put("created", System.currentTimeMillis());
    getWritableDatabase().insertOrThrow("photos", null, v);
  }

  public synchronized String mealPhoto(String meal) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT path FROM photos WHERE meal=? ORDER BY created DESC, rowid LIMIT 1",
                new String[] {meal})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  public synchronized Map<String, String> mealPhotos(List<String> meals) {
    Map<String, String> photos = new HashMap<>();
    if (meals.isEmpty()) return photos;
    String placeholders = String.join(",", Collections.nCopies(meals.size(), "?"));
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT meal,path FROM photos WHERE meal IN (" + placeholders + ") ORDER BY created DESC, rowid",
        meals.toArray(String[]::new))) {
      while (c.moveToNext()) photos.putIfAbsent(c.getString(0), c.getString(1));
    }
    return photos;
  }

  public synchronized int pendingPhotoCount() {
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT COUNT(*) FROM photos WHERE meal IS NULL", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  public synchronized List<String[]> photos() {
    return readPhotos("SELECT id,path FROM photos WHERE meal IS NULL ORDER BY created DESC", null);
  }

  public synchronized List<String[]> photos(int limit) {
    return readPhotos("SELECT id,path FROM photos WHERE meal IS NULL ORDER BY created DESC LIMIT ?",
        new String[] {Integer.toString(limit)});
  }

  private List<String[]> readPhotos(String query, String[] args) {
    List<String[]> a = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(query, args)) {
      while (c.moveToNext()) a.add(new String[] {c.getString(0), c.getString(1)});
    }
    return a;
  }

  public synchronized String removePhoto(String id) {
    uiRevision++;
    String path = null;
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT path FROM photos WHERE id=? AND meal IS NULL", new String[] {id})) {
      if (c.moveToFirst()) path = c.getString(0);
    }
    getWritableDatabase().delete("photos", "id=? AND meal IS NULL", new String[] {id});
    return path;
  }

  public synchronized JSONObject exportData() {
    try {
      JSONObject all =
          new JSONObject().put("version", 2).put("exportedAt", System.currentTimeMillis());
      for (String table :
          new String[] {
            "settings", "plans", "workouts", "weights", "meals", "photos", "route_points"
          }) {
        JSONArray a = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM " + table, null)) {
          while (c.moveToNext()) {
            JSONObject row = new JSONObject();
            for (int i = 0; i < c.getColumnCount(); i++)
              row.put(
                  c.getColumnName(i),
                  switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL -> JSONObject.NULL;
                    case Cursor.FIELD_TYPE_FLOAT -> Double.toString(c.getDouble(i));
                    case Cursor.FIELD_TYPE_INTEGER -> Long.toString(c.getLong(i));
                    default -> c.getString(i);
                  });
            a.put(row);
          }
        }
        all.put(table, a);
      }
      return all;
    } catch (JSONException e) {
      throw corrupt(e);
    }
  }

  private static IllegalStateException corrupt(Exception e) {
    return new IllegalStateException("记录读取失败，已保留原始数据。请导出备份后再处理。", e);
  }
}
