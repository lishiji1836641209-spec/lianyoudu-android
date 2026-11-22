package com.lianyoudu.app;

import android.app.Application;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class FitnessApp extends Application {
  public boolean operationBusy;
  public int dataRevision;
  public String routeStatus = "未开启路线记录";
  public Store store;
  public Domain.Session active;
  public String failure = "";
  // Distinguish user decisions and safety stops from a dialog's temporary pause.
  final String trainingStateEpoch = UUID.randomUUID().toString();
  long trainingStateRevision;
  long trainingSafetyRevision;
  public BleHeart ble;
  public final Handler main = new Handler(Looper.getMainLooper());
  public final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Set<Runnable> observers = new HashSet<>();

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      store = new Store(this);
      active = store.active();
      if (active != null) store.saveSession(active, true);
    } catch (Exception e) {
      failure = e.getMessage();
    }
    ble = new BleHeart(this);
  }

  int observerCountForAudit() {
    return observers.size();
  }

  public void subscribe(Runnable r) {
    observers.add(r);
  }

  public void unsubscribe(Runnable r) {
    observers.remove(r);
  }

  public void changed() {
    for (Runnable r : new ArrayList<>(observers)) r.run();
  }

  public void start(String scene, java.util.List<Domain.Exercise> plan) {
    start(scene, plan, "综合");
  }

  public void start(String scene, java.util.List<Domain.Exercise> plan, String kind) {
    Domain.require(active == null, "已有进行中的训练");
    if (scene.equals("home")) {
      Set<String> gear = store.gear();
      for (Domain.Exercise e : plan)
        Domain.require(e.gear.isEmpty() || gear.contains(e.gear), "家中缺少器材：" + e.gear);
    }
    Domain.Session q = new Domain.Session(scene, plan);
    q.trainingKind = java.util.List.of("综合", "力量", "有氧", "核心", "拉伸").contains(kind) ? kind : "综合";
    q.resume(SystemClock.elapsedRealtime());
    store.saveSession(q, true);
    active = q;
    changed();
  }

  public interface Mutation {
    void apply(Domain.Session q);
  }

  void noteTrainingFailure() {
    trainingSafetyRevision++;
  }

  void setTrainingRunning(String sessionId, boolean running) {
    Domain.require(active != null && active.id.equals(sessionId), "训练状态已变化，请重新查看");
    trainingStateRevision++;
    mutate(
        q -> {
          if (running) q.resume(SystemClock.elapsedRealtime());
          else q.pause(SystemClock.elapsedRealtime());
        });
  }

  void toggleTrainingRunning(String sessionId) {
    Domain.require(active != null && active.id.equals(sessionId), "训练状态已变化，请重新查看");
    setTrainingRunning(sessionId, !active.running);
  }

  private void clearSavedFailure() {
    if (failure.startsWith("保存失败，训练已暂停。")) failure = "";
  }

  public void appendExercises(java.util.List<Domain.Exercise> exercises) {
    Domain.require(active != null, "训练已结束，请重新打开运动列表");
    Domain.Session before;
    try {
      before = Store.session(Store.session(active));
      before.copyLiveClock(active);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    try {
      active.appendAll(exercises, SystemClock.elapsedRealtime());
      store.sessionWithDefaults(active, exercises);
    } catch (Exception e) {
      active = before;
      throw e;
    }
    changed();
  }

  public void mutate(Mutation mutation) {
    Domain.require(active != null, "没有进行中的训练");
    Domain.Session before;
    try {
      before = Store.session(Store.session(active));
      before.copyLiveClock(active);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    try {
      mutation.apply(active);
    } catch (IllegalArgumentException e) {
      active = before;
      throw e;
    }
    try {
      store.saveSession(active, true);
      clearSavedFailure();
      changed();
    } catch (Exception e) {
      active = before;
      active.running = false;
      noteTrainingFailure();
      failure = "保存失败，训练已暂停。" + e.getMessage();
      changed();
      throw e;
    }
  }

  public Domain.Session finish() {
    Domain.require(active != null, "训练已结束");
    active.tick(SystemClock.elapsedRealtime());
    Domain.Session q;
    try {
      q = Store.session(Store.session(active));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    q.finish(SystemClock.elapsedRealtime());
    q.endedWall = System.currentTimeMillis();
    store.saveSession(q, false);
    active = null;
    ble.close();
    changed();
    return q;
  }

  public void checkpoint() {
    if (active == null) return;
    try {
      store.saveSession(active, true);
      clearSavedFailure();
    } catch (Exception e) {
      active.running = false;
      noteTrainingFailure();
      failure = "保存失败，训练已暂停。请检查手机存储空间。";
      changed();
    }
  }
}
