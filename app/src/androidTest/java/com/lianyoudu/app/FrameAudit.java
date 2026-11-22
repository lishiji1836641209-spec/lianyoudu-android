package com.lianyoudu.app;

import android.app.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Frame timing collection only. Never equates an emulator sample to device acceptance. */
final class FrameAudit {
  static void run(Instrumentation test, Activity activity, FitnessApp app) throws Exception {
    if (!activity.getPackageName().equals("com.lianyoudu.app.qa"))
      throw new IllegalStateException("Frame probe requires isolated audit app");
    DesignAudit navigation = new DesignAudit(test, activity, app);
    navigation.page(0);
    HandlerThread sampler = new HandlerThread("audit-frame-metrics");
    sampler.start();
    Handler handler = new Handler(sampler.getLooper());
    List<Long> totals = new ArrayList<>();
    List<Long> ui = new ArrayList<>(), gpu = new ArrayList<>();
    java.util.concurrent.atomic.AtomicReference<String> phase =
        new java.util.concurrent.atomic.AtomicReference<>("pages");
    ConcurrentSkipListMap<Long, String> phaseMarks = new ConcurrentSkipListMap<>();
    phaseMarks.put(0L, "pages");
    java.util.function.Consumer<String> mark =
        label -> {
          phase.set(label);
          phaseMarks.put(System.nanoTime(), label);
        };
    Map<String, List<Long>> phases = new LinkedHashMap<>();
    Map<String, Integer> misses = new LinkedHashMap<>();
    Map<String, Integer> interactionMisses = new LinkedHashMap<>();
    List<double[]> slowFrames = new ArrayList<>();
    List<String> slowContexts = new ArrayList<>();
    long[] counts =
        new long
            [5]; // missed deadlines, reports dropped, first draws, unavailable deadlines, cap drops
    Window.OnFrameMetricsAvailableListener listener =
        (window, metrics, dropped) -> {
          counts[1] += dropped;
          if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) counts[2]++;
          long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
          long deadline = metrics.getMetric(FrameMetrics.DEADLINE);
          if (totals.size() >= 10000) {
            counts[4]++;
            return;
          }
          if (total >= 0) {
            totals.add(total);
            ui.add(
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                    + metrics.getMetric(FrameMetrics.DRAW_DURATION));
            gpu.add(Math.max(0, metrics.getMetric(FrameMetrics.GPU_DURATION)));
            long intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
            Map.Entry<Long, String> marker = intended > 0 ? phaseMarks.floorEntry(intended) : null;
            String context = marker == null ? phase.get() : marker.getValue();
            String section = context.split("#", 2)[0];
            phases.computeIfAbsent(section, key -> new ArrayList<>()).add(total);
            if (deadline <= 0) counts[3]++;
            else if (total >= deadline) {
              counts[0]++;
              misses.merge(section, 1, Integer::sum);
              interactionMisses.merge(context, 1, Integer::sum);
              if (slowFrames.size() < 80) {
                slowContexts.add(context);
                slowFrames.add(
                    new double[] {
                      total / 1e6,
                      deadline / 1e6,
                      metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.ANIMATION_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.SYNC_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) / 1e6,
                      metrics.getMetric(FrameMetrics.GPU_DURATION) / 1e6
                    });
              }
            }
          }
        };
    test.runOnMainSync(
        () -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, handler));
    Map<Integer, Object> bodies = new HashMap<>();
    int reuseHits = 0, reuseMisses = 0;
    try {
      for (int round = 0; round < 3; round++) {
        for (int page : new int[] {1, 17, 16, 2, 18, 0}) {
          mark.accept("page-" + page);
          navigation.page(page);
          bodies.put(page, navigation.field("body"));
        }
      }
      // Physical touch injection, without screenshots inside the measured gesture.
      int from = 0;
      int[] destinations = {1, 2, 3, 2, 1, 0, 3, 0, 2, 0, 1, 0};
      for (int to : destinations) {
        mark.accept("glass-drag#" + from + "-" + to);
        AppViews.GlassBar nav = (AppViews.GlassBar) navigation.field("nav");
        int[] pos = new int[2];
        test.runOnMainSync(() -> nav.getLocationOnScreen(pos));
        float x0 = pos[0] + nav.getWidth() * (from + .5f) / 4f;
        float x1 = pos[0] + nav.getWidth() * (to + .5f) / 4f;
        float y = pos[1] + nav.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 20; step++) {
          int action =
              step == 0
                  ? MotionEvent.ACTION_DOWN
                  : step == 20 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
          if (step == 20) mark.accept("glass-release#" + from + "-" + to);
          MotionEvent event =
              MotionEvent.obtain(
                  down, SystemClock.uptimeMillis(), action, x0 + (x1 - x0) * step / 20f, y, 0);
          try {
            test.getUiAutomation().injectInputEvent(event, true);
          } finally {
            event.recycle();
          }
          Thread.sleep(12);
        }
        Thread.sleep(650);
        int destination = new int[] {0, 1, 18, 17}[to];
        Object rendered = navigation.field("body");
        if (bodies.get(destination) == rendered) reuseHits++;
        else reuseMisses++;
        bodies.put(destination, rendered);
        from = to;
      }
      test.waitForIdleSync();
    } finally {
      test.runOnMainSync(
          () -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
      CountDownLatch drained = new CountDownLatch(1);
      handler.post(drained::countDown);
      if (!drained.await(5, TimeUnit.SECONDS)) {
        sampler.quitSafely();
        throw new IllegalStateException("Frame collector did not drain");
      }
      sampler.quitSafely();
      sampler.join(2000);
    }
    if (totals.size() < 30)
      throw new IllegalStateException("Insufficient frame samples: " + totals.size());
    Collections.sort(totals);
    Collections.sort(ui);
    Collections.sort(gpu);
    JSONObject sections = new JSONObject();
    for (Map.Entry<String, List<Long>> entry : phases.entrySet()) {
      Collections.sort(entry.getValue());
      sections.put(
          entry.getKey(),
          new JSONObject()
              .put("samples", entry.getValue().size())
              .put("missedDeadlineSamples", misses.getOrDefault(entry.getKey(), 0))
              .put("p95Ms", percentile(entry.getValue(), .95))
              .put("maxMs", percentile(entry.getValue(), 1)));
    }
    JSONObject report =
        new JSONObject()
            .put("model", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT)
            .put(
                "scope",
                "Main window only; 18 direct page renders plus 12 injected glass swipes across all"
                    + " four tabs; no dialogs/capsule/cold start. Collection success is not"
                    + " performance acceptance.")
            .put("warmPageReuses", reuseHits)
            .put("rebuiltSwipePages", reuseMisses)
            .put("samples", totals.size())
            .put("missedDeadlineSamples", counts[0])
            .put("droppedMetricReports", counts[1])
            .put("firstDrawSamples", counts[2])
            .put("unavailableDeadlineSamples", counts[3])
            .put("cappedSamples", counts[4])
            .put("p50Ms", percentile(totals, .5))
            .put("p95Ms", percentile(totals, .95))
            .put("p99Ms", percentile(totals, .99))
            .put("maxMs", totals.get(totals.size() - 1) / 1_000_000.0)
            .put("layoutDrawP95Ms", percentile(ui, .95))
            .put("gpuP95Ms", percentile(gpu, .95))
            .put("phases", sections)
            .put("interactionMisses", new JSONObject(interactionMisses))
            .put(
                "phaseAttribution",
                "Intended vsync timestamp mapped to the latest interaction marker; unavailable"
                    + " timestamps use the current phase.")
            .put(
                "slowFrameColumns",
                "total,deadline,unknown,input,animation,layout,draw,sync,command,gpu (ms)")
            .put("slowFrames", new JSONArray(slowFrames))
            .put("slowFrameContexts", new JSONArray(slowContexts))
            .put("reference", "https://developer.android.com/reference/android/view/FrameMetrics")
            .put("physicalDeviceAcceptance", false);
    try (FileWriter out = new FileWriter(new File(app.getFilesDir(), "frame-audit.json"))) {
      out.write(report.toString(2));
    }
  }

  private static double percentile(List<Long> values, double percentile) {
    return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1))
        / 1_000_000.0;
  }
}
