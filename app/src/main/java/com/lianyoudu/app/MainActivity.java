package com.lianyoudu.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import org.json.*;

/**
 * Native Android views; UI observes the single application session. No WebView or hosted runtime.
 */
public final class MainActivity extends Activity {
  private final List<Dialog> dialogs = new ArrayList<>();
  private int previousTab = -1;
  private float[] releaseNav;
  private FoodCatalog foodCatalog;
  private String libraryQuery = "";
  private String libraryCategory = "全部", libraryMuscle = "全部部位", libraryGear = "全部器材";
  private Runnable libraryRefresh, libraryLoadNext;
  private final HealthData healthData = new HealthData();
  private FitnessApp app;
  private Store db;
  private FrameLayout root;
  private AppViews.PageScrollView scroll;
  private View navBottomMask;
  private LinearLayout body;
  private AppViews.GlassBar nav;
  private boolean shellDark, shellSolid;

  private record MainPage(LinearLayout content, TextView clock, TextView state, String key) {}

  private final Map<Integer, MainPage> mainPages = new HashMap<>();

  private String mainPageKey() {
    Domain.Session active = app.active;
    return db.uiRevision
        + ":"
        + app.dataRevision
        + ":"
        + System.currentTimeMillis() / 60000
        + ":"
        + System.identityHashCode(healthData.value)
        + ":"
        + healthData.loading
        + ":"
        + energyRange
        + ":"
        + energyMetric
        + ":"
        + app.failure
        + ":"
        + (active == null ? "" : active.id + ":" + active.running + ":" + active.items.size());
  }

  private int seenRevision;
  private int page = 0, ink, muted, bg, card, accent, soft, line;
  private boolean dark, busy, foreground;
  private String scene = "gym", selectedPhoto;
  private Domain.Session selected;
  private TextView clockText, roundText, heartText, restText;
  private TextView sessionState;
  private TextView pauseControl;
  private String pauseControlSuffix = "";
  private EditText actualReps, actualWeight;
  private final Deque<Integer> back = new ArrayDeque<>();
  private final Runnable observer = this::refreshLive;
  private Dialog operationDialog;
  private TextView deviceStatus;
  private ProgressBar connectionProgress;
  private LinearLayout deviceRows;
  private FrameLayout preparationFooter;
  private String lastConnection = "";
  private int lastDeviceCount;
  private AlertDialog formDialog;
  private java.util.function.Consumer<Runnable> formExitRequest;
  private String formKind = "";
  private String formTarget = "";
  private int formIndex = -1;
  private final LinkedHashMap<String, EditText> fields = new LinkedHashMap<>();
  private final LinkedHashMap<String, Spinner> choices = new LinkedHashMap<>();
  private Bundle restoredForm, restoringExercise;
  private String formExitBaseline, restoringFormExitBaseline;
  private int historyOffset;
  private Integer historyReturnScroll;
  private ExercisePicker exercisePicker;
  private int weightDays = 7, renderedPage = -1;
  private AlertDialog finishConfirmation;
  private TextView finishStateText;
  private boolean finishResume;
  private String finishSession = "";
  private String finishStateEpoch = "";
  private long finishStateRevision;
  private long finishSafetyRevision;
  private View restPanel;
  private boolean updatingInline;
  private final List<Runnable> distanceLive = new ArrayList<>();
  private EditText liveTimedAmount;
  private String liveTimedKey;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    app = (FitnessApp) getApplication();
    db = app.store;
    foodCatalog = new FoodCatalog(this);
    seenRevision = app.dataRevision;
    if (!app.failure.isEmpty() && db == null) {
      fatal(app.failure);
      return;
    }
    try {
      db.unifyPlans();
      if (!db.setting("energyPinVersion", "").equals("1")) {
        List<String> initialPins = new ArrayList<>(pins());
        if (!initialPins.contains("energy")) initialPins.add("energy");
        db.setSetting("healthPins", String.join(",", initialPins));
        db.setSetting("energyPinVersion", "1");
      }
      scene = "all";
      if (saved != null) {
        page = saved.getInt("page", 0);
        libraryQuery = saved.getString("libraryQuery", "");
        libraryCategory = saved.getString("libraryCategory", "全部");
        libraryMuscle = saved.getString("libraryMuscle", "全部部位");
        libraryGear = saved.getString("libraryGear", "全部器材");
        int[] savedBack = saved.getIntArray("backStack");
        if (savedBack != null) for (int destination : savedBack) back.addLast(destination);
        weightDays = saved.getInt("weightDays", 7);
        energyRange = saved.getInt("energyRange", 7) == 30 ? 30 : 7;
        energyMetric = saved.getString("energyMetric", "expenditure");
        historyOffset = Math.max(0, saved.getInt("historyOffset", 0));
        Bundle inline = saved.getBundle("inlineValues");
        if (inline != null)
          for (String key : inline.keySet()) inlineValues.put(key, inline.getString(key));
        scene = "all";
        selectedPhoto = saved.getString("photo");
        String sid = saved.getString("session");
        if (sid != null) selected = db.workout(sid);
        restoredForm = saved.getBundle("form");
      }
      if (getIntent().getBooleanExtra("training", false) && app.active != null) page = 3;
      render();
      if (saved != null) {
        int restoredScroll = saved.getInt("scrollY", 0);
        ScrollView restoredView = scroll;
        restoredView.post(() -> restoredView.scrollTo(0, restoredScroll));
      }
      if (saved != null) {
        if (actualReps != null && saved.containsKey("actualReps"))
          actualReps.setText(saved.getString("actualReps"));
        if (actualWeight != null && saved.containsKey("actualWeight"))
          actualWeight.setText(saved.getString("actualWeight"));
      }
      if (saved != null
          && saved.getBoolean("finishConfirmation", false)
          && app.active != null
          && app.active.id.equals(saved.getString("finishSession"))) {
        finishResume = saved.getBoolean("finishResume");
        finishStateEpoch = saved.getString("finishStateEpoch", "");
        finishStateRevision = saved.getLong("finishStateRevision", -1);
        finishSafetyRevision = saved.getLong("finishSafetyRevision", -1);
        String restoredSession = saved.getString("finishSession");
        getWindow()
            .getDecorView()
            .post(
                () -> {
                  if (app.active != null && app.active.id.equals(restoredSession))
                    showFinishDialog(finishResume, true);
                });
      }
      if (restoredForm != null) getWindow().getDecorView().post(this::restoreForm);
      if (saved != null && saved.containsKey("exercisePicker")) {
        Bundle pickerState = saved.getBundle("exercisePicker");
        getWindow()
            .getDecorView()
            .post(() -> openExercisePicker(pickerState.getInt("target", -1), pickerState));
      }
    } catch (Exception e) {
      fatal(e.getMessage());
    }
  }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    if (i.getBooleanExtra("training", false) && app.active != null) go(3);
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (app != null) app.subscribe(observer);
    updateNavigationMaterial();
    refreshLive();
    if (page == 18 || page == 0) refreshHealth();
  }

  @Override
  protected void onPause() {
    mainPages.clear();
    foreground = false;
    if (app != null) {
      app.unsubscribe(observer);
      app.checkpoint();
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (Build.VERSION.SDK_INT >= 33 && backCallbackRegistered) {
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(pageBackCallback);
      backCallbackRegistered = false;
    }
    for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    dialogs.clear();
    if (formDialog != null) formDialog.dismiss();
    if (operationDialog != null) operationDialog.dismiss();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (exercisePicker != null) {
      Bundle pickerState = exercisePicker.state();
      if (pickerState != null) out.putBundle("exercisePicker", pickerState);
    }
    if (actualReps != null) out.putString("actualReps", actualReps.getText().toString());
    if (actualWeight != null) out.putString("actualWeight", actualWeight.getText().toString());
    Bundle inline = new Bundle();
    for (Map.Entry<String, String> entry : inlineValues.entrySet())
      inline.putString(entry.getKey(), entry.getValue());
    out.putBundle("inlineValues", inline);
    out.putInt("page", page);
    out.putIntArray("backStack", back.stream().mapToInt(Integer::intValue).toArray());
    out.putInt("scrollY", scroll == null ? 0 : scroll.getScrollY());
    out.putInt("weightDays", weightDays);
    out.putInt("energyRange", energyRange);
    out.putString("energyMetric", energyMetric);
    out.putInt("historyOffset", historyOffset);
    if (finishConfirmation != null && finishConfirmation.isShowing()) {
      out.putBoolean("finishConfirmation", true);
      out.putBoolean("finishResume", finishResume);
      out.putString("finishSession", finishSession);
      out.putString("finishStateEpoch", finishStateEpoch);
      out.putLong("finishStateRevision", finishStateRevision);
      out.putLong("finishSafetyRevision", finishSafetyRevision);
    }
    out.putString("scene", scene);
    out.putString("libraryQuery", libraryQuery);
    out.putString("libraryCategory", libraryCategory);
    out.putString("libraryMuscle", libraryMuscle);
    out.putString("libraryGear", libraryGear);
    out.putString("photo", selectedPhoto);
    if (selected != null) out.putString("session", selected.id);
    if (formDialog != null && formDialog.isShowing()) {
      Bundle f = new Bundle();
      f.putString("kind", formKind);
      if (formExitBaseline != null) f.putString("exitBaseline", formExitBaseline);
      f.putString("target", formTarget);
      f.putInt("index", formIndex);
      f.putString("customSession", customFormSession);
      if (formKind.equals("customExercise")) f.putString("customBaseline", customEditBaseline);
      for (Map.Entry<String, EditText> e : fields.entrySet())
        f.putString("f:" + e.getKey(), e.getValue().getText().toString());
      for (Map.Entry<String, Spinner> e : choices.entrySet())
        f.putInt("s:" + e.getKey(), e.getValue().getSelectedItemPosition());
      out.putBundle("form", f);
    }
    super.onSaveInstanceState(out);
  }

  private void restoreForm() {
    Bundle f = restoredForm;
    restoredForm = null;
    if (f == null) return;
    String kind = f.getString("kind", "");
    formTarget = f.getString("target", "");
    formIndex = f.getInt("index", -1);
    restoringFormExitBaseline = f.getString("exitBaseline");
    try {
      switch (kind) {
        case "exercise":
          restoringExercise = f;
          try {
            exerciseEditor(formIndex, false);
          } finally {
            restoringExercise = null;
          }
          return;
        case "customExercise":
          Domain.Exercise savedBaseline = null;
          if (!formTarget.isEmpty()) {
            try {
              String serialized = f.getString("customBaseline", "");
              if (!serialized.isEmpty()) {
                Domain.Exercise parsed = Store.exercise(new JSONObject(serialized));
                if (formTarget.equals(parsed.id)) savedBaseline = parsed;
              }
            } catch (JSONException | RuntimeException ignored) {
              // An unknown baseline must never be treated as the current library value.
            }
          }
          Domain.Exercise initial = formTarget.isEmpty() ? null : savedBaseline != null
              ? savedBaseline.copy() : new Domain.Exercise(formTarget, "", "", false, "");
          customExerciseForm(
              initial,
              formIndex,
              f.getString("customSession", ""),
              savedBaseline);
          break;
        case "weight":
          weightForm(formTarget.isEmpty() ? null : formTarget);
          break;
        case "goal":
          goalForm();
          break;

        case "calorie":
          if (selected == null) return;
          calorieForm(selected);
          break;
        case "distance":
          if (app.active == null || !app.active.id.equals(formTarget)) return;
          distanceForm(formIndex);
          break;
        case "continuousMetrics":
          if (app.active == null || !app.active.id.equals(formTarget)) return;
          continuousMetricsForm(formIndex);
          break;
        case "continuousGoal":
          continuousGoalForm(formIndex, formTarget.equals("preparation"));
          break;
        case "rest":
          restForm(formTarget);
          break;
        case "bodyProfile":
          profileForm();
          break;
        case "meal":
          JSONObject meal = null;
          for (JSONObject o : db.meals()) if (o.optString("id").equals(formTarget)) meal = o;
          mealForm(meal);
          break;
        default:
          return;
      }
      for (Map.Entry<String, EditText> e : fields.entrySet())
        if (f.containsKey("f:" + e.getKey())) e.getValue().setText(f.getString("f:" + e.getKey()));
      for (Map.Entry<String, Spinner> e : choices.entrySet())
        if (f.containsKey("s:" + e.getKey())) e.getValue().setSelection(f.getInt("s:" + e.getKey()));
    } finally {
      restoringFormExitBaseline = null;
    }
  }

  private boolean backCallbackRegistered;
  private android.window.OnBackInvokedCallback pageBackCallback;

  private void syncBackCallback() {
    if (Build.VERSION.SDK_INT < 33) return;
    if (pageBackCallback == null) pageBackCallback = this::handlePageBack;
    boolean needed = busy || app != null && app.operationBusy || page != 0 || !back.isEmpty();
    if (needed == backCallbackRegistered) return;
    if (needed) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, pageBackCallback);
    else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(pageBackCallback);
    backCallbackRegistered = needed;
  }

  @Override public void onBackPressed() { handlePageBack(); }

  private void handlePageBack() {
    if (busy || app.operationBusy) { toast("正在保存，请稍候"); return; }
    if (!back.isEmpty()) { page = back.pop(); render(); }
    else if (page != 0) { page = 0; render(); }
    else super.onBackPressed();
  }

  private int d(float n) {
    return AppViews.dp(this, n);
  }

  private int touchTarget() {
    return (int) Math.ceil(48f * getResources().getDisplayMetrics().density);
  }

  private int color(String hex) {
    return Color.parseColor(hex);
  }

  private String sceneName() {
    return sceneName(scene);
  }

  private String sceneName(String s) {
    return s.equals("all") ? "综合" : s.equals("home") ? "家中" : s.equals("outdoor") ? "户外" : "健身房";
  }

  private void palette() {
    String theme = db.setting("theme", "auto");
    dark =
        theme.equals("dark")
            || theme.equals("auto")
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
    setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
    ink = HealthDesign.ink(dark);
    muted = HealthDesign.muted(dark);
    bg = HealthDesign.background(dark);
    card = HealthDesign.card(dark);
    line = HealthDesign.line(dark);
    accent = HealthDesign.blue(dark);
    soft = HealthDesign.well(dark);
  }

  private void go(int p) {
    if (busy || app.operationBusy) return;
    if (page != p) {
      if (page == 14 && p == 7) historyReturnScroll = scroll.getScrollY();
      if (back.size() >= 32) back.removeLast();
      back.push(page);
      page = p;
    }
    render();
  }

  private void updateNavigationMaterial() {
    if (nav != null) nav.setSolid(shellSolid || !HealthDesign.motion(this));
  }

  private void render() {
    if (isFinishing()) return;
    boolean samePage = renderedPage == page;
    int savedScroll = samePage && scroll != null ? scroll.getScrollY() : 0;
    Integer restoredHistoryScroll = !samePage && page == 14 && renderedPage == 7
        ? historyReturnScroll : null;
    if (restoredHistoryScroll != null) historyReturnScroll = null;
    renderedPage = page;
    try {
      if (preparationFooter != null && preparationFooter.getParent() instanceof ViewGroup parent)
        parent.removeView(preparationFooter);
      preparationFooter = null;
      palette();
      restPanel = null;
      liveTimedAmount = null;
      liveTimedKey = null;
      clockText = roundText = heartText = restText = null;
      sessionState = null;
      pauseControl = null;
      actualReps = actualWeight = null;
      boolean solid = db.setting("solid", "false").equals("true");
      boolean motion = HealthDesign.motion(this);
      boolean newShell =
          root == null
              || root.getParent() == null
              || nav == null
              || shellDark != dark
              || shellSolid != solid;
      if (newShell) {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        mainPages.clear();
        shellDark = dark;
        shellSolid = solid;
        root = new FrameLayout(this);
        root.setBackground(Visuals.backdrop(dark));
        scroll = new AppViews.PageScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // Hide clipped content in the narrow space below the floating bar; the glass itself
        // still samples the live scroll view above this mask.
        navBottomMask = new View(this);
        navBottomMask.setBackgroundColor(bg);
        navBottomMask.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(navBottomMask, new FrameLayout.LayoutParams(-1, d(12), Gravity.BOTTOM));
        setContentView(root);
      } else {
        // Keep the window, scroll viewport and optical nodes attached across navigation.
        scroll.removeAllViews();
        scroll.scrollTo(0, 0);
      }
      boolean primaryPage = page == 0 || page == 1 || page == 17 || page == 18;
      MainPage cached = !samePage && primaryPage ? mainPages.get(page) : null;
      if (cached != null && !cached.key().equals(mainPageKey())) cached = null;
      if (cached == null) {
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(d(20), d(8), d(20), d(112));
      } else {
        body = cached.content();
        clockText = cached.clock();
        sessionState = cached.state();
        body.animate().cancel();
        body.setAlpha(1);
        body.setTranslationY(0);
        if (app.active != null) {
          if (clockText != null) clockText.setText(Domain.clock(app.active.elapsed));
          if (sessionState != null) sessionState.setText(app.active.running ? "训练进行中" : "训练已暂停");
        }
      }
      scroll.sampleContentOnly = page == 8;
      scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
      if (newShell) {
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow()
            .getDecorView()
            .getWindowInsetsController()
            .setSystemBarsAppearance(
                dark
                    ? 0
                    : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        root.setOnApplyWindowInsetsListener(
            (v, insets) -> {
              android.graphics.Insets bar = insets.getInsets(WindowInsets.Type.systemBars());
              v.setPadding(bar.left, bar.top, bar.right, bar.bottom);
              return insets;
            });
      }
      distanceLive.clear();
      libraryLoadNext = null;
      if (cached == null) {
        if (page != 0 && page != 1 && page != 17 && page != 18 && page != 3) {
          LinearLayout header = row();
          TextView backButton = headerAction("‹ 返回", this::onBackPressed);
          backButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
          header.addView(backButton, new LinearLayout.LayoutParams(0, -2, 1));
          if (page == 12) header.addView(headerAction("添加数据", () -> weightForm(null)));
          else if (page != 15) header.addView(headerAction("设置", () -> go(15)));
          body.addView(header);
        }
        if (!app.failure.isEmpty()) body.addView(note(app.failure));
        distanceLive.clear();
        libraryLoadNext = null;
        switch (page) {
          case 0:
            home();
            break;
          case 1:
            scenes();
            break;
          case 2:
            plan();
            break;
          case 3:
            training();
            break;
          case 6:
            devices();
            break;
          case 7:
            summary();
            break;
          case 8:
            photos();
            break;
          case 9:
            meals();
            break;
          case 11:
            sharing();
            break;
          case 12:
            weights();
            break;
          case 14:
            history();
            break;
          case 16:
            equipmentScreen();
            break;
          case 17:
            browse();
            break;
          case 19:
            preparationScreen();
            break;
          case 18:
            energyScreen();
            break;
          case 15:
            settings();
            break;
          default:
            page = 0;
            home();
        }
      }
      if (primaryPage)
        mainPages.put(page, new MainPage(body, clockText, sessionState, mainPageKey()));
      int tab =
          page == 0
              ? 0
              : (page == 1 || page == 2 || page == 3 || page == 16 || page == 19)
                  ? 1
                  : page == 18 ? 2 : page == 15 ? Math.max(0, previousTab) : 3;
      if (newShell) {
        nav = new AppViews.GlassBar(this, scroll, dark, solid || !motion);
        String[] labels = {"摘要", "训练", "能量", "浏览"};
        int[] dest = {0, 1, 18, 17};
        for (int i = 0; i < labels.length; i++) {
          final int pos = i;
          nav.tab(
              labels[i],
              tab == i,
              accent,
              muted,
              () -> {
                if (nav.isCommittingDrag()) {
                  previousTab = pos;
                  releaseNav = nav.dragReleasePose();
                }
                back.clear();
                historyReturnScroll = null;
                page = dest[pos];
                render();
                if (page == 18 || page == 0) refreshHealth();
              });
        }
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, d(73), Gravity.BOTTOM);
        np.setMargins(d(15), 0, d(15), d(12));
        root.addView(nav, np);
        scroll.setOnScrollChangeListener(
            (v, x, y, ox, oy) -> {
              nav.refresh();
              if (page == 16
                  && libraryLoadNext != null
                  && scroll.getChildCount() > 0
                  && y + scroll.getHeight() >= scroll.getChildAt(0).getHeight() - d(320)) {
                Runnable next = libraryLoadNext;
                scroll.post(
                    () -> {
                      if (page == 16 && libraryLoadNext == next) next.run();
                    });
              }
            });
      } else {
        nav.setSolid(solid || !motion);
      }
      nav.setVisibility(page == 19 && app.active == null ? View.GONE : View.VISIBLE);
      navBottomMask.setVisibility(nav.getVisibility());
      nav.selectTab(tab);
      if (releaseNav != null) {
        nav.releaseFromDrag(releaseNav);
        releaseNav = null;
      }
      nav.animateSelection(previousTab < 0 ? tab : previousTab, tab);
      previousTab = tab;
      nav.post(nav::refresh);
      syncBackCallback();
      if (restoredHistoryScroll != null && restoredHistoryScroll > 0) {
        ScrollView target = scroll;
        View content = body;
        target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
          @Override public boolean onPreDraw() {
            target.getViewTreeObserver().removeOnPreDrawListener(this);
            if (scroll == target && body == content) target.scrollTo(0, restoredHistoryScroll);
            return true;
          }
        });
      } else if (savedScroll > 0) {
        ScrollView target = scroll;
        target.post(() -> target.scrollTo(0, savedScroll));
      }
      if (!samePage && !primaryPage && motion && !nav.isCommittingDrag()) {
        body.setAlpha(.94f);
        body.setTranslationY(d(9));
        body.animate().alpha(1f).translationY(0).setDuration(280).start();
      }
    } catch (Exception e) {
      fatal(e.getMessage());
    }
  }

  private TextView text(String value, int size, int tint, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(tint);
    t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
    t.setLineSpacing(d(size <= 15 ? 4 : 2), 1);
    t.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
    t.setIncludeFontPadding(false);
    return t;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    GradientDrawable gap = new GradientDrawable();
    gap.setColor(Color.TRANSPARENT);
    gap.setSize(d(8), d(1));
    l.setDividerDrawable(gap);
    l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    return l;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout panel() {
    LinearLayout l = column();
    l.setPadding(d(18), d(18), d(18), d(18));
    l.setBackground(Visuals.surface(dark, accent, d(GlassEffects.CARD)));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, d(10), 0, 0);
    l.setLayoutParams(p);
    return l;
  }

  private LinearLayout emptyState(String symbol, String heading, String detail) {
    LinearLayout card = panel();
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setPadding(d(24), d(30), d(24), d(30));
    ImageView badge = new ImageView(this);
    badge.setImageDrawable(new Visuals.Icon(symbol, accent));
    badge.setPadding(d(17), d(17), d(17), d(17));
    badge.setBackground(AppViews.round(soft, d(32)));
    card.addView(badge, new LinearLayout.LayoutParams(d(64), d(64)));
    TextView headingView = text(heading, 19, ink, true);
    headingView.setGravity(Gravity.CENTER);
    headingView.setPadding(0, d(18), 0, d(4));
    card.addView(headingView);
    TextView detailView = note(detail);
    detailView.setGravity(Gravity.CENTER);
    card.addView(detailView);
    return card;
  }

  private void title(String s) {
    TextView t = text(s, 34, ink, true);
    t.setPadding(0, d(page == 0 || page == 1 || page == 17 || page == 18 ? 0 : 14), 0, d(6));
    body.addView(t);
  }

  private void section(String s) {
    body.addView(sectionLabel(s));
  }

  private TextView sectionLabel(String s) {
    TextView t = text(s, 21, ink, true);
    t.setPadding(0, d(GlassEffects.CARD), 0, d(2));
    return t;
  }

  private TextView note(String s) {
    TextView t = text(s, 14, muted, false);
    t.setPadding(0, d(10), 0, d(10));
    return t;
  }

  private TextView button(String label, Runnable action, boolean primary) {
    TextView b =
        text(label, 15, primary ? (dark ? color("#10151E") : Color.WHITE) : accent, primary);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(d(48));
    b.setLetterSpacing(0);
    b.setPadding(d(12), d(10), d(12), d(10));
    b.setBackground(
        AppViews.button(primary ? accent : HealthDesign.well(dark), ink, d(GlassEffects.CONTROL)));
    GlassEffects.press(b);
    b.setClickable(true);
    b.setFocusable(true);
    b.setContentDescription(label);
    if (label.equals("暂停")
        || label.equals("继续")
        || label.equals("暂停骑行")
        || label.equals("继续骑行")
        || label.equals("暂停运动")
        || label.equals("继续运动")) {
      pauseControl = b;
      pauseControlSuffix = label.length() > 2 ? label.substring(2) : "";
    }
    b.setOnClickListener(
        v -> {
          if (busy || app.operationBusy) return;
          try {
            action.run();
          } catch (Exception e) {
            toast(e.getMessage());
          }
        });
    return b;
  }

  private void action(LinearLayout into, String label, Runnable r, boolean primary) {
    TextView v = button(label, r, primary);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = d(12);
    into.addView(v, p);
  }

  private Domain.Session copySession(Domain.Session q) {
    try {
      return Store.session(Store.session(q));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s == null ? "操作失败，请重试" : s, Toast.LENGTH_LONG).show();
  }

  private void fatal(String msg) {
    LinearLayout l = column();
    l.setPadding(d(24), d(64), d(24), d(24));
    boolean systemNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    boolean night = systemNight;
    if (db != null) {
      try {
        String preference = db.setting("theme", "auto");
        night = preference.equals("dark") || (preference.equals("auto") && systemNight);
      } catch (RuntimeException ignored) {
        // The storage failure itself must not prevent the recovery message from rendering.
      }
    }
    l.setBackgroundColor(HealthDesign.background(night));
    l.addView(text("暂时无法打开记录", 24, HealthDesign.ink(night), true));
    l.addView(text(msg == null ? "请重新打开应用，原始数据不会被清除。" : msg, 16, HealthDesign.muted(night), false));
    setContentView(l);
  }

  private final class MenuSpinner extends Spinner {
    MenuSpinner() {
      super(MainActivity.this);
    }

    @Override
    public boolean performClick() {
      android.widget.SpinnerAdapter adapter = getAdapter();
      String[] labels = new String[adapter.getCount()];
      for (int i = 0; i < labels.length; i++) labels[i] = adapter.getItem(i).toString();
      new GlassDialogBuilder()
          .setTitle(getContentDescription())
          .setSingleChoiceItems(
              labels,
              getSelectedItemPosition(),
              (d, which) -> {
                setSelection(which);
                d.dismiss();
              })
          .setNegativeButton("取消", null)
          .show();
      return true;
    }
  }

  private final class GlassDialogBuilder extends DesignDialog.Builder {
    GlassDialogBuilder() {
      super(
          MainActivity.this,
          MainActivity.this.dark,
          MainActivity.this.ink,
          MainActivity.this.muted,
          MainActivity.this.accent,
          MainActivity.this.soft);
    }

    @Override
    public AlertDialog create() {
      AlertDialog dialog = super.create();
      dialogs.add(dialog);
      dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
      return dialog;
    }

    @Override
    public AlertDialog show() {
      AlertDialog d = super.show();
      GlassEffects.decorate(d, dark, accent);
      return d;
    }
  }

  private void confirm(String kind, String title, String message, Runnable yes) {
    DesignDialog.Builder builder = new GlassDialogBuilder().setDestructive(kind.equals("delete"));
    confirm(builder, kind, title, message, "取消",
        title.contains("删除") ? "删除" : title.contains("移除") ? "移除" : "确定", yes);
  }

  private void confirmRecordReplacement(String title, String message, Runnable yes) {
    DesignDialog.Builder builder = new GlassDialogBuilder()
        .setDestructive(true)
        .setNegativeButtonFirst();
    confirm(builder, "update", title, message, "返回编辑", "替换记录", yes);
  }

  private void confirm(DesignDialog.Builder builder, String kind, String title, String message,
      String negative, String positive, Runnable yes) {
    AlertDialog confirmation =
        builder
            .setWholeContentScrollable()
            .setIconKind(kind)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(negative, null)
            .setPositiveButton(
                positive,
                (d, w) -> {
                  try {
                    yes.run();
                  } catch (Exception e) {
                    toast(e.getMessage());
                  }
                })
            .create();
    showScrollableConfirmation(confirmation);
  }

  private void showScrollableConfirmation(AlertDialog dialog) {
    dialog.setOnShowListener(
        ignored ->
            ((DesignDialog) dialog).focusSafeAction(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)));
    dialog.show();
    GlassEffects.decorate(dialog, dark, accent);
  }

  private int orange() {
    return HealthDesign.orange(dark);
  }

  private int teal() {
    return HealthDesign.teal(dark);
  }

  private int purple() {
    return HealthDesign.purple(dark);
  }

  private TextView link(String label, Runnable run) {
    TextView t = button(label, run, false);
    t.setBackground(AppViews.button(Color.TRANSPARENT, accent, d(12)));
    t.setTextSize(14);
    t.setPadding(d(3), d(8), d(3), d(8));
    return t;
  }

  private TextView headerAction(String label, Runnable run) {
    TextView action = link(label, run);
    action.setTextSize(16);
    action.setMinWidth(d(48));
    action.setMinHeight(d(48));
    return action;
  }

  private void classificationTitle(String classification, String heading) {
    TextView top = text(classification, 34, ink, true);
    top.setPadding(0, d(14), 0, 0);
    body.addView(top);
    TextView sub = text(heading, 21, ink, true);
    LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
    spacing.topMargin = d(8);
    spacing.bottomMargin = d(6);
    body.addView(sub, spacing);
  }

  private void addWithTopGap(LinearLayout parent, View child, int dp) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = d(dp);
    parent.addView(child, lp);
  }

  private void sectionAction(String title, String action, Runnable run) {
    LinearLayout r = row();
    r.setPadding(d(2), d(22), d(2), 0);
    r.addView(text(title, 21, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
    r.addView(link(action, run));
    body.addView(r);
  }

  private TextView metric(String value, String unit, int size) {
    android.text.SpannableString str =
        new android.text.SpannableString(value + (unit.isEmpty() ? "" : " " + unit));
    if (!unit.isEmpty()) {
      str.setSpan(
          new android.text.style.AbsoluteSizeSpan(13, true), value.length(), str.length(), 0);
      str.setSpan(
          new android.text.style.ForegroundColorSpan(muted), value.length(), str.length(), 0);
    }
    TextView t = text("", size, ink, true);
    t.setText(str);
    t.setFontFeatureSettings("tnum");
    return t;
  }

  private LinearLayout dataCard(String label, String stamp, int tint, Runnable run) {
    LinearLayout c = panel(), r = row();
    TextView name = text(label, 14, tint, true);
    Visuals.Icon icon = new Visuals.Icon(label, tint);
    icon.setBounds(0, 0, d(17), d(17));
    name.setCompoundDrawables(icon, null, null, null);
    name.setCompoundDrawablePadding(d(6));
    r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    r.addView(text(stamp + "  ›", 11, muted, false));
    c.addView(r);
    c.setClickable(true);
    c.setFocusable(true);
    c.setContentDescription(label + "，查看详情");
    c.setBackground(AppViews.button(card, accent, d(HealthDesign.CARD)));
    GlassEffects.press(c);
    c.setOnClickListener(v -> run.run());
    return c;
  }

  private LinearLayout listRow(
      String label, String subtitle, String symbol, int tint, Runnable run) {
    LinearLayout r = row();
    r.setPadding(d(16), d(14), d(16), d(14));
    r.setBackground(AppViews.button(card, accent, d(HealthDesign.CARD)));
    ImageView icon = new ImageView(this);
    icon.setImageDrawable(new Visuals.Icon(symbol, tint));
    icon.setPadding(d(9), d(9), d(9), d(9));
    icon.setBackground(AppViews.round((tint & 0xffffff) | 0x16000000, d(12)));
    r.addView(icon, new LinearLayout.LayoutParams(d(40), d(40)));
    LinearLayout words = column();
    words.setPadding(d(4), 0, 0, 0);
    words.addView(text(label, 15, ink, true));
    if (!subtitle.isEmpty()) {
      TextView sub = text(subtitle, 13, muted, false);
      sub.setPadding(0, d(5), 0, 0);
      words.addView(sub);
    }
    r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    r.addView(text("›", 22, muted, false));
    r.setMinimumHeight(d(72));
    r.setClickable(true);
    r.setFocusable(true);
    r.setContentDescription(label);
    r.setOnClickListener(v -> guard(run));
    GlassEffects.press(r);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = d(10);
    r.setLayoutParams(p);
    return r;
  }

  private List<String> pins() {
    List<String> result = new ArrayList<>();
    for (String k : db.setting("healthPins", "activity,weight,food,energy").split(","))
      if (Arrays.asList("activity", "weight", "food", "energy").contains(k) && !result.contains(k))
        result.add(k);
    return result;
  }

  private void editPins() {
    List<String> draft = new ArrayList<>(pins());
    List<String> initial = new ArrayList<>(draft);
    LinearLayout content = column();
    Runnable redraw =
        new Runnable() {
          public void run() {
            content.removeAllViews();
            List<String> ordered = new ArrayList<>(draft);
            for (String id : Arrays.asList("activity", "weight", "food", "energy"))
              if (!ordered.contains(id)) ordered.add(id);
            for (String id : ordered) {
              String label =
                  id.equals("activity")
                      ? "运动时间"
                      : id.equals("weight") ? "体重" : id.equals("energy") ? "每日能量" : "饮食能量";
              LinearLayout r = row();
              r.setPadding(0, d(4), 0, d(4));
              r.addView(
                  link(
                      draft.contains(id) ? "−" : "+",
                      () -> {
                        if (!draft.remove(id)) draft.add(id);
                        run();
                      }));
              r.addView(text(label, 16, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
              if (draft.contains(id)) {
                int at = draft.indexOf(id);
                TextView up =
                    link(
                        "↑",
                        () -> {
                          Collections.swap(draft, at, at - 1);
                          run();
                        });
                up.setContentDescription("上移" + label);
                up.setEnabled(at > 0);
                up.setAlpha(at > 0 ? 1 : .3f);
                r.addView(up);
                TextView down =
                    link(
                        "↓",
                        () -> {
                          Collections.swap(draft, at, at + 1);
                          run();
                        });
                down.setContentDescription("下移" + label);
                down.setEnabled(at < draft.size() - 1);
                down.setAlpha(at < draft.size() - 1 ? 1 : .3f);
                r.addView(down);
              }
              content.addView(r);
            }
          }
        };
    redraw.run();
    AlertDialog editor = new GlassDialogBuilder()
        .setTitle("编辑置顶")
        .setMessage("选择摘要中显示的数据，并调整顺序。")
        .setView(content)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "完成",
            (x, w) -> {
              db.setSetting("healthPins", String.join(",", draft));
              render();
            })
        .show();
    protectUnsaved(editor, () -> !initial.equals(draft), editor::dismiss);
  }

  private long weekMillis(List<Domain.Session> sessions, boolean previous) {
    LocalDate start = LocalDate.now().with(java.time.DayOfWeek.MONDAY).minusDays(previous ? 7 : 0);
    LocalDate end = start.plusDays(7);
    long total = 0;
    for (Domain.Session q : sessions) {
      LocalDate day = LocalDate.parse(q.date);
      if (!day.isBefore(start) && day.isBefore(end)) total += q.elapsed;
    }
    return total;
  }

  private record MealEnergy(Double intake, int unknown) {}

  private Double todayFoodKnown() {
    return summarizeMealEnergy(db.meals(LocalDate.now())).intake();
  }

  private MealEnergy summarizeMealEnergy(List<JSONObject> meals) {
    double sum = 0;
    boolean known = false;
    int unknown = 0;
    for (JSONObject meal : meals) {
      JSONArray foods = meal.optJSONArray("foods");
      if (foods == null || foods.length() == 0) {
        unknown++;
        continue;
      }
      for (int i = 0; i < foods.length(); i++) {
        JSONObject food = foods.optJSONObject(i);
        if (food != null && food.has("kcal") && !food.isNull("kcal")) {
          sum += food.optDouble("kcal", 0);
          known = true;
        } else unknown++;
      }
    }
    return new MealEnergy(known ? sum : null, unknown);
  }

  private record FoodDay(LocalDate date, int meals, MealEnergy energy, boolean complete, String completeAt) {}

  private FoodDay foodDay(LocalDate date) {
    List<JSONObject> meals = db.meals(date);
    String saved = meals.isEmpty() ? "" : db.setting("foodComplete", "");
    boolean complete = !saved.isEmpty() && saved.equals(mealFingerprint(date, meals));
    return new FoodDay(date, meals.size(), summarizeMealEnergy(meals), complete,
        complete ? db.setting("foodCompleteAt", "") : "");
  }

  private void home() {
    LocalDate today = LocalDate.now();
    body.addView(
        note(today.format(DateTimeFormatter.ofPattern("M月d日，EEEE", Locale.CHINA))));
    title("摘要");
    int pending = app.active == null ? preparation().size() : 0;
    LinearLayout quick =
        listRow(
            app.active == null ? "我的训练" : app.active.running ? "训练进行中" : "训练已暂停",
            app.active == null
                ? (pending == 0 ? "选择运动" : pending + " 项待开始")
                : Domain.clock(app.active.elapsed),
            "训练",
            orange(),
            () -> go(app.active == null ? 19 : 3));
    if (app.active != null) {
      LinearLayout words = (LinearLayout) quick.getChildAt(1);
      sessionState = (TextView) words.getChildAt(0);
      clockText = (TextView) words.getChildAt(1);
      quick.setContentDescription("返回本次训练");
    } else {
      quick.removeViewAt(quick.getChildCount() - 1);
      TextView start = button("开始", () -> go(19), false);
      start.setBackground(
          AppViews.button(dark ? 0xFF1A3551 : 0xFFE5F0FD, accent, d(HealthDesign.BUTTON)));
      start.setSingleLine(true);
      start.setMinHeight(d(48));
      start.setMinWidth(d(58));
      quick.addView(start, new LinearLayout.LayoutParams(d(72), d(48)));
    }
    body.addView(quick);
    sectionAction("置顶", "编辑", this::editPins);
    LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
    List<Domain.Session> workouts = db.workoutsBetween(monday.minusDays(7), today.plusDays(1));
    List<Store.Weight> ws = db.weights();
    FoodDay food = foodDay(today);
    Double intake = food.energy().intake();
    List<String> pinned = pins();
    for (String pin : pinned) {
      LinearLayout c;
      if (pin.equals("activity")) {
        c = dataCard("运动时间", "本周", orange(), () -> go(14));
        LinearLayout r = row(), words = column();
        words.setPadding(0, d(16), 0, 0);
        words.addView(metric(String.valueOf(weekMillis(workouts, false) / 60000), "分钟", 34));
        long count =
            workouts.stream()
                .filter(
                    q ->
                        !LocalDate.parse(q.date).isBefore(monday)
                            && !LocalDate.parse(q.date).isAfter(today))
                .count();
        words.addView(note(count + " 次运动"));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(
            new Visuals.WeekChart(this, workouts, orange(), muted, line),
            new LinearLayout.LayoutParams(d(125), d(72)));
        c.addView(r);
      } else if (pin.equals("weight")) {
        c = dataCard("体重", ws.isEmpty() ? "暂无记录" : ws.get(0).date, teal(), () -> go(12));
        LinearLayout r = row(), words = column();
        words.setPadding(0, d(16), 0, 0);
        words.addView(
            metric(
                ws.isEmpty() ? "—" : String.format(Locale.CHINA, "%.1f", ws.get(0).kg), "千克", 34));
        words.addView(note(ws.isEmpty() ? "添加第一条体重记录" : "最近一次测量"));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(
            new HealthCharts.Spark(this, ws, teal()), new LinearLayout.LayoutParams(d(110), d(54)));
        c.addView(r);
      } else if (pin.equals("energy")) {
        EnergyReading reading = energyReading(today, workouts);
        Double delta = energyDelta(reading, food);
        saveEnergyDay(reading, food, delta);
        c =
            dataCard(
                "每日能量",
                "今天",
                orange(),
                () -> {
                  go(18);
                  refreshHealth();
                });
        TextView value =
            metric(
                reading.ledger().kcal() == null
                    ? "—"
                    : String.format(Locale.CHINA, "%,.0f", reading.ledger().kcal()),
                "千卡",
                34);
        addWithTopGap(c, value, 16);
        c.addView(note(reading.ledger().fullDeviceTotal() ? "设备总消耗" : "截至目前的消耗估算"));
        c.addView(
            text(
                intake == null
                    ? "饮食摄入 —"
                    : "饮食摄入 " + String.format(Locale.CHINA, "%,.0f 千卡", intake),
                15,
                ink,
                false));
        c.addView(note(energyDifference(delta)));
      } else {
        c = dataCard("饮食能量", "今天", purple(), () -> go(9));
        TextView value =
            metric(intake == null ? "—" : String.format(Locale.CHINA, "%,.0f", intake), "千卡", 34);
        value.setPadding(0, d(16), 0, 0);
        c.addView(value);
        int meals = food.meals();
        c.addView(note(meals == 0 ? "今天还没有饮食记录" : meals + " 笔饮食记录 · 已知热量合计"));
      }
      body.addView(c);
    }
    if (pinned.isEmpty()) body.addView(emptyState("plus", "置顶常看的数据", "点右上角“编辑”添加。"));
    section("提要");
    long current = weekMillis(workouts, false) / 60000;
    long prior =
        workouts.stream()
                .filter(
                    q -> {
                      LocalDate date = LocalDate.parse(q.date);
                      return !date.isBefore(monday.minusDays(7))
                          && !date.isAfter(today.minusDays(7));
                    })
                .mapToLong(q -> q.elapsed)
                .sum()
            / 60000;
    LinearLayout insight = dataCard("运动时间", "上周同期对比", orange(), () -> go(14));
    addWithTopGap(insight, text(current + " 分钟", 26, ink, true), 16);
    insight.addView(
        note(
            current == prior
                ? "与上周同期相同"
                : "比上周同期" + (current > prior ? "多 " : "少 ") + Math.abs(current - prior) + " 分钟"));
    insight.addView(
        new HealthCharts.Comparison(this, prior, current, orange(), muted),
        new LinearLayout.LayoutParams(-1, d(72)));
    long activeDays =
        workouts.stream()
            .filter(
                q ->
                    !LocalDate.parse(q.date).isBefore(monday)
                        && !LocalDate.parse(q.date).isAfter(today))
            .map(q -> q.date)
            .distinct()
            .count();
    insight.addView(note("本周已运动 " + activeDays + " 天"));
    body.addView(insight);
    List<Store.Weight> month =
        ws.stream()
            .filter(
                w ->
                    !LocalDate.parse(w.date).isBefore(today.minusDays(29))
                        && !LocalDate.parse(w.date).isAfter(today))
            .collect(java.util.stream.Collectors.toList());
    if (month.size() >= 2) {
      Store.Weight latest = month.get(0), first = month.get(month.size() - 1);
      double change = latest.kg - first.kg;
      LinearLayout weightTrend = dataCard("体重变化", "近30天", teal(), () -> go(12));
      addWithTopGap(weightTrend, metric(String.format(Locale.CHINA, "%+.1f", change), "千克", 28), 16);
      weightTrend.addView(
          note(
              first.date.substring(5)
                  + " 至 "
                  + latest.date.substring(5)
                  + " · "
                  + month.size()
                  + " 次测量"));
      body.addView(weightTrend);
    }
    LinearLayout nutrition = dataCard("饮食记录", "今天", purple(), () -> go(9));
    addWithTopGap(nutrition,
        text(food.meals() == 0 ? "今天还没记饮食" : food.meals() + " 餐已记录", 20, ink, true), 16);
    nutrition.addView(
        note(food.meals() == 0 ? "从食物库选择食物和份量" : food.complete() ? "截至目前已确认记全" : "饮食完整性未确认"));
    body.addView(nutrition);
    Domain.Session latest = db.latestWorkout();
    if (latest != null) {
      LinearLayout recentRow = listRow(
              "最近一次运动",
              latest.date + " · 时长 " + Domain.clock(latest.elapsed),
              "训练",
              orange(),
              () -> {
                selected = latest;
                go(7);
              });
      TextView subtitle = (TextView) ((LinearLayout) recentRow.getChildAt(1)).getChildAt(1);
      subtitle.setPadding(0, 0, 0, 0);
      LinearLayout.LayoutParams subtitleSpacing = new LinearLayout.LayoutParams(-1, -2);
      subtitleSpacing.topMargin = d(16);
      subtitle.setLayoutParams(subtitleSpacing);
      recentRow.setContentDescription("最近一次运动，" + latest.date + "，时长 " + Domain.clock(latest.elapsed));
      body.addView(recentRow);
    }
  }

  private void scenes() {
    body.addView(note("运动与训练记录"));
    title("训练");
    List<Domain.Exercise> plan = db.plan(scene);
    LinearLayout hero = panel();
    hero.addView(text(app.active == null ? "准备开始" : "本次运动", 14, orange(), true));
    TextView name =
        text(
            app.active != null
                ? (app.active.running ? "运动进行中" : "运动已暂停")
                : plan.isEmpty() ? "开始运动" : "我的训练计划",
            27,
            ink,
            true);
    name.setPadding(0, d(15), 0, d(8));
    hero.addView(name);
    List<Domain.Exercise> shown =
        app.active == null
            ? plan
            : app.active.items.stream()
                .map(i -> i.exercise)
                .collect(java.util.stream.Collectors.toList());
    if (app.active != null) {
      sessionState = name;
      clockText = note(Domain.clock(app.active.elapsed));
      hero.addView(clockText);
      hero.addView(note(shown.size() + " 项运动"));
    } else hero.addView(note(plan.isEmpty() ? "选择本次运动" : plan.size() + " 项运动"));
    for (Domain.Exercise e : shown.subList(0, Math.min(3, shown.size())))
      hero.addView(note(e.name + " · " + desc(e)));
    action(
        hero,
        app.active != null ? "返回本次训练" : plan.isEmpty() ? "自行选择运动" : "使用计划",
        () -> {
          if (app.active != null) {
            go(3);
            return;
          }
          if (plan.isEmpty()) {
            freeTraining();
            return;
          }
          prepareFromPlan();
        },
        true);
    if (app.active == null && !plan.isEmpty()) {
      TextView direct = link("自行选择运动", this::freeTraining);
      direct.setGravity(Gravity.CENTER);
      hero.addView(direct);
    }
    if (app.active == null && !preparation().isEmpty())
      hero.addView(link("继续准备", () -> go(19)));
    if (app.active == null && !plan.isEmpty())
      hero.addView(note("使用计划会载入我的计划；自行选择运动会追加到待开始运动。"));
    body.addView(hero);
    section("我的运动");
    body.addView(listRow("运动库", "动作指引 · 力量、有氧与拉伸", "训练", orange(), () -> go(16)));
    body.addView(listRow("我的计划", "编辑运动、组数与时长", "训练", orange(), () -> go(2)));
    body.addView(
        listRow(
            "我的收藏",
            "常练的运动，随时添加",
            "heart",
            orange(),
            () -> {
              libraryCategory = "收藏";
              libraryQuery = "";
              libraryMuscle = "全部部位";
              libraryGear = "全部器材";
              go(16);
            }));
    section("训练模板");
    body.addView(listRow("选择训练模板", "徒手、哑铃、器械与核心", "训练", purple(), this::trainingTemplates));
    sectionAction("最近记录", "查看全部", () -> go(14));
    List<Domain.Session> recent = db.workouts(3, 0);
    if (recent.isEmpty()) body.addView(note("完成训练后，记录会显示在这里。"));
    for (Domain.Session q : recent.subList(0, Math.min(3, recent.size())))
      body.addView(
          listRow(
              q.trainingKind + "训练",
              q.date + " · 时长 " + Domain.clock(q.elapsed),
              "训练",
              orange(),
              () -> {
                selected = q;
                go(7);
              }));
  }

  private void browse() {
    body.addView(note("健康与运动数据"));
    title("浏览");
    EditText query = new EditText(this);
    query.setSingleLine(true);
    query.setTextSize(16);
    query.setTextColor(ink);
    query.setHintTextColor(muted);
    query.setHint("搜索健康与设置入口");
    query.setContentDescription("搜索健康与设置入口");
    query.setPadding(d(12), d(12), d(12), d(12));
    query.setMinHeight(d(52));
    GradientDrawable searchBackground = AppViews.round(card, d(HealthDesign.INPUT));
    searchBackground.setStroke(d(1), line);
    query.setBackground(searchBackground);
    Visuals.Icon searchIcon = new Visuals.Icon("search", muted);
    searchIcon.setBounds(0, 0, d(20), d(20));
    query.setCompoundDrawablesRelative(searchIcon, null, null, null);
    query.setCompoundDrawablePadding(d(8));
    body.addView(query);
    LinearLayout results = column();
    body.addView(results);
    record BrowseEntry(String title, String subtitle, String icon, String aliases, int color,
        Runnable open) {}
    String mealPhotoAliases = db.pendingPhotoCount() > 0 ? "相册 历史餐食照片" : "";
    List<BrowseEntry> entries = List.of(
        new BrowseEntry("活动", "运动时长、训练记录", "训练", "运动时间", orange(), () -> go(14)),
        new BrowseEntry("身体测量", "体重、测量记录", "体重", "", teal(), () -> go(12)),
        new BrowseEntry("营养", "饮食能量、餐次记录", "饮食", mealPhotoAliases, purple(), () -> go(9)),
        new BrowseEntry("心率设备", "连接手表或心率带", "activity", "心脏 设备连接 运动心率",
            dark ? 0xFFFF78A7 : 0xFFD12663, () -> go(6)),
        new BrowseEntry("每日能量", "身体资料、设备步数与能量差", "每日能量", "消耗 摄入", orange(),
            () -> { go(18); refreshHealth(); }),
        new BrowseEntry("显示与数据", "外观、实时通知与备份", "settings", "设置 导出备份", accent, () -> go(15)));
    Runnable update =
        () -> {
          results.removeAllViews();
          String key = query.getText().toString().trim();
          for (BrowseEntry entry : entries)
            if (entry.title().contains(key) || entry.subtitle().contains(key) || entry.aliases().contains(key)) {
              if (key.isEmpty() && entry.icon().equals("settings"))
                results.addView(sectionLabel("数据管理"));
              results.addView(listRow(entry.title(), entry.subtitle(), entry.icon(), entry.color(), entry.open()));
            }
          if (results.getChildCount() == 0)
            results.addView(note("未找到相关入口，试试“体重”或“运动”。"));
        };
    query.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence x, int st, int c, int a) {}

          public void onTextChanged(CharSequence x, int st, int before, int count) {
            update.run();
          }

          public void afterTextChanged(android.text.Editable x) {}
        });
    update.run();
  }

  private void equipmentScreen() {
    TextView libraryTitle = text("运动库", 34, ink, true);
    libraryTitle.setPadding(0, d(14), 0, d(6));
    body.addView(libraryTitle);
    TextView totalCount = note("");
    body.addView(totalCount);
    TextView customAction = headerAction("＋ 自定义运动", () -> customExerciseForm(null, -4));
    customAction.setTextSize(17);
    customAction.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    customAction.setPadding(0, d(8), 0, d(8));
    LinearLayout.LayoutParams customLayout = new LinearLayout.LayoutParams(-1, -2);
    customLayout.bottomMargin = d(12);
    body.addView(customAction, customLayout);
    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setTextSize(16);
    search.setTextColor(ink);
    search.setHintTextColor(muted);
    search.setHint("搜索运动、部位或器材");
    search.setContentDescription("搜索运动");
    search.setPadding(d(12), d(12), d(12), d(12));
    search.setMinHeight(d(52));
    search.setBackground(AppViews.round(soft, d(HealthDesign.INPUT)));
    body.addView(search);
    HorizontalScrollView horizontal = new HorizontalScrollView(this);
    horizontal.setHorizontalScrollBarEnabled(false);
    horizontal.setHorizontalFadingEdgeEnabled(true);
    horizontal.setFadingEdgeLength(d(16));
    horizontal.setContentDescription("运动分类，可左右滑动");
    LinearLayout categories = row();
    categories.setPadding(0, d(10), 0, d(6));
    horizontal.addView(categories);
    body.addView(horizontal);
    LinearLayout summary = row();
    TextView count = note("");
    summary.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    TextView filter = link("筛选", this::libraryFilter);
    filter.setContentDescription("筛选部位与器材");
    summary.addView(filter);
    body.addView(summary);
    if (!libraryMuscle.equals("全部部位") || !libraryGear.equals("全部器材"))
      body.addView(
          link(
              libraryMuscle + " · " + libraryGear + "  ×",
              () -> {
                libraryMuscle = "全部部位";
                libraryGear = "全部器材";
                render();
              }));
    LinearLayout results = column();
    body.addView(results);
    Runnable update =
        () -> {
          libraryQuery = search.getText().toString();
          results.removeAllViews();
          Set<String> favorites = ExerciseCatalog.favorites(db.setting("exercise.favorites", ""));
          List<Domain.Exercise> matching = new ArrayList<>();
          Map<String, Domain.Exercise> library = new LinkedHashMap<>();
          Map<String, Integer> identityCounts = new HashMap<>();
          for (Domain.Exercise e : db.exerciseLibrary()) {
            identityCounts.merge(e.id, 1, Integer::sum);
            library.putIfAbsent(e.id, e);
          }
          Set<String> validFavorites = new HashSet<>();
          for (String id : favorites)
            if (id != null && !id.isBlank() && !id.equals("custom")
                && identityCounts.getOrDefault(id, 0) == 1) validFavorites.add(id);
          totalCount.setText("共 " + library.size() + " 项运动 · 内置与自定义");
          for (Domain.Exercise e : library.values())
            if (ExerciseCatalog.matches(
                e, libraryQuery, libraryCategory, libraryMuscle, libraryGear, validFavorites))
              matching.add(e);
          count.setText(String.format(Locale.CHINA, "当前结果 %d 项", matching.size()));
          int[] shown = {0};
          TextView more =
              link(
                  "查看更多运动",
                  () -> {
                    if (libraryLoadNext != null) libraryLoadNext.run();
                  });
          more.setGravity(Gravity.CENTER);
          Runnable append =
              () -> {
                results.removeView(more);
                int end = Math.min(matching.size(), shown[0] + 6);
                for (; shown[0] < end; shown[0]++) {
                  Domain.Exercise e = matching.get(shown[0]);
                  LinearLayout c = panel(), r = row(), words = column();
                  r.addView(
                      GuideImages.exercise(this, e), new LinearLayout.LayoutParams(d(80), d(80)));
                  words.setPadding(d(14), 0, 0, 0);
                  words.addView(text(e.name, 17, ink, true));
                  words.addView(note(ExerciseCatalog.subtitle(e)));
                  TextView metric =
                      text(
                          e.distanceBased()
                              ? "距离 · 时长 · 均速"
                              : e.timed ? "时长 · 组数" : e.unit.isEmpty() ? "次数 · 组数" : "重量 · 次数 · 组数",
                          12,
                          accent,
                          false);
                  metric.setPadding(0, d(5), 0, 0);
                  words.addView(metric);
                  r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                  TextView favorite =
                      link(
                          favorites.contains(e.id) ? "★" : "☆",
                          () -> {
                            db.toggleExerciseFavorite(e.id);
                            if (libraryRefresh != null) libraryRefresh.run();
                          });
                  boolean canFavorite = e.id != null && !e.id.isBlank() && !e.id.equals("custom")
                      && identityCounts.getOrDefault(e.id, 0) == 1;
                  if (!canFavorite) {
                    favorite.setText("☆");
                    favorite.setTextColor(muted);
                    favorite.setEnabled(false);
                    favorite.setContentDescription(e.name + "，暂不能收藏");
                    favorite.setStateDescription("动作信息不可用于收藏");
                  } else {
                    favorite.setContentDescription(
                        (favorites.contains(e.id) ? "取消收藏" : "收藏") + e.name);
                  }
                  favorite.setSelected(canFavorite && favorites.contains(e.id));
                  r.addView(favorite, new LinearLayout.LayoutParams(d(48), d(48)));
                  c.addView(r);
                  c.setOnClickListener(v -> guard(() -> exerciseGuide(e)));
                  c.setContentDescription("查看" + e.name + "指引");
                  results.addView(c);
                }
                if (shown[0] < matching.size()) results.addView(more);
                else libraryLoadNext = null;
              };
          libraryLoadNext = append;
          append.run();
          if (matching.isEmpty())
            results.addView(
                note(
                    libraryCategory.equals("收藏") && validFavorites.isEmpty()
                        ? "还没有收藏。打开动作详情，收藏常练的运动。"
                        : "没有符合条件的运动，试试其他关键词或清除筛选。"));
          for (int i = 0; i < categories.getChildCount(); i++) {
            TextView chip = (TextView) categories.getChildAt(i);
            boolean selected = chip.getText().toString().equals(libraryCategory);
            chip.setSelected(selected);
            chip.setTextColor(selected ? accent : muted);
            chip.setBackground(AppViews.button(selected ? soft : Color.TRANSPARENT, accent, d(22)));
          }
        };
    libraryRefresh = update;
    for (String name : ExerciseCatalog.CATEGORIES) {
      TextView chip = text(name, 14, muted, true);
      chip.setGravity(Gravity.CENTER);
      chip.setMinHeight(touchTarget());
      chip.setMinWidth(touchTarget());
      chip.setPadding(d(16), d(10), d(16), d(10));
      chip.setContentDescription("运动分类：" + name);
      chip.setOnClickListener(
          v -> {
            libraryCategory = name;
            update.run();
            horizontal.smoothScrollTo(
                Math.max(0, chip.getLeft() - (horizontal.getWidth() - chip.getWidth()) / 2), 0);
          });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.rightMargin = d(5);
      categories.addView(chip, lp);
    }
    search.setText(libraryQuery);
    horizontal.post(
        () -> {
          for (int i = 0; i < categories.getChildCount(); i++) {
            TextView chip = (TextView) categories.getChildAt(i);
            if (chip.getText().toString().equals(libraryCategory)) {
              horizontal.scrollTo(
                  Math.max(0, chip.getLeft() - (horizontal.getWidth() - chip.getWidth()) / 2), 0);
              break;
            }
          }
        });
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            update.run();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    update.run();
  }

  private void libraryFilter() {
    LinearLayout content = column();
    Spinner muscle =
        select(
            content,
            "library.muscle",
            "训练部位",
            ExerciseCatalog.MUSCLES,
            Arrays.asList(ExerciseCatalog.MUSCLES).indexOf(libraryMuscle));
    Spinner gear =
        select(
            content,
            "library.gear",
            "训练器材",
            ExerciseCatalog.EQUIPMENT,
            Arrays.asList(ExerciseCatalog.EQUIPMENT).indexOf(libraryGear));
    new GlassDialogBuilder()
        .setTitle("筛选运动")
        .setView(content)
        .setNegativeButton("取消", null)
        .setNeutralButton(
            "清除筛选",
            (dialog, which) -> {
              libraryMuscle = "全部部位";
              libraryGear = "全部器材";
              render();
            })
        .setPositiveButton(
            "查看结果",
            (dialog, which) -> {
              libraryMuscle = muscle.getSelectedItem().toString();
              libraryGear = gear.getSelectedItem().toString();
              render();
            })
        .show();
  }

  private void guard(Runnable work) {
    try {
      work.run();
    } catch (Exception e) {
      toast(e.getMessage());
    }
  }

  private final class HeadingActions extends ViewGroup {
    private boolean stacked;
    HeadingActions() { super(MainActivity.this); }
    @Override protected LayoutParams generateDefaultLayoutParams() {
      return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec);
      int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
      int childWidth = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
      int childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      getChildAt(0).measure(childWidth, childHeight);
      getChildAt(1).measure(childWidth, childHeight);
      stacked = getChildAt(0).getMeasuredWidth() + d(12) + getChildAt(1).getMeasuredWidth() > available;
      int contentHeight = stacked ? getChildAt(0).getMeasuredHeight() + getChildAt(1).getMeasuredHeight()
          : Math.max(getChildAt(0).getMeasuredHeight(), getChildAt(1).getMeasuredHeight());
      setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
      View title = getChildAt(0), action = getChildAt(1);
      int left = getPaddingLeft(), top = getPaddingTop();
      title.layout(left, top, left + title.getMeasuredWidth(), top + title.getMeasuredHeight());
      int x = stacked ? left : getWidth() - getPaddingRight() - action.getMeasuredWidth();
      int y = stacked ? top + title.getMeasuredHeight() : top + (getHeight() - getPaddingTop() - getPaddingBottom() - action.getMeasuredHeight()) / 2;
      action.layout(x, y, x + action.getMeasuredWidth(), y + action.getMeasuredHeight());
    }
  }

  private final class WrappingActions extends ViewGroup {
    WrappingActions() { super(MainActivity.this); }
    @Override protected LayoutParams generateDefaultLayoutParams() {
      return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, rowHeight = 0;
      int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
      int childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        child.measure(childWidth, childHeight);
        if (x > 0 && x + child.getMeasuredWidth() > width) {
          y += rowHeight + d(12); x = 0; rowHeight = 0;
        }
        x += child.getMeasuredWidth() + d(12);
        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
      }
      setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + rowHeight, heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int x = 0, y = 0, rowHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (x > 0 && x + child.getMeasuredWidth() > getWidth()) {
          y += rowHeight + d(12); x = 0; rowHeight = 0;
        }
        child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        x += child.getMeasuredWidth() + d(12);
        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
      }
    }
  }

  /** The backdrop follows the foreground's size, never the height offered by the parent. */
  private final class ContentSizedDock extends FrameLayout {
    private View content;

    ContentSizedDock() { super(MainActivity.this); }

    void addContent(View foreground) {
      content = foreground;
      addView(foreground, new FrameLayout.LayoutParams(-1, -2));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      if (content == null) {
        super.onMeasure(widthSpec, heightSpec);
        return;
      }
      measureChild(content, widthSpec, heightSpec);
      int wantedWidth = Math.max(getSuggestedMinimumWidth(),
          content.getMeasuredWidth() + getPaddingLeft() + getPaddingRight());
      int wantedHeight = Math.max(getSuggestedMinimumHeight(),
          content.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
      setMeasuredDimension(resolveSize(wantedWidth, widthSpec), resolveSize(wantedHeight, heightSpec));
      int innerWidth = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
      int innerHeight = Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom());
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child != content)
          child.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY),
              MeasureSpec.makeMeasureSpec(innerHeight, MeasureSpec.EXACTLY));
      }
    }
  }

  private String safeFavoriteUnavailableReason(String id) {
    try {
      return db.favoriteUnavailableReason(id);
    } catch (RuntimeException failure) {
      return "暂时无法检查运动库，请重新打开后重试。";
    }
  }

  private View exerciseQuickActions(Domain.Exercise exercise) {
    return exerciseQuickActions(exercise, safeFavoriteUnavailableReason(exercise.id));
  }

  private View exerciseQuickActions(Domain.Exercise exercise, String initialReason) {
    LinearLayout group = column();
    WrappingActions actions = new WrappingActions();
    TextView favorite = headerAction("☆ 收藏动作", () -> {});
    TextView explanation = note("");
    final String[] firstReason = {initialReason};
    Runnable updateFavorite = () -> {
      String problem;
      boolean saved = false;
      try {
        problem = firstReason[0];
        firstReason[0] = null;
        if (problem == null) problem = safeFavoriteUnavailableReason(exercise.id);
        if (problem.isEmpty())
          saved = ExerciseCatalog.favorites(db.setting("exercise.favorites", "")).contains(exercise.id);
      } catch (RuntimeException failure) {
        problem = "暂时无法检查运动库，请重新打开后重试。";
      }
      boolean available = problem.isEmpty();
      favorite.setEnabled(available);
      favorite.setText(available && saved ? "★ 已收藏" : "☆ 收藏动作");
      favorite.setTextColor(available ? accent : muted);
      favorite.setSelected(available && saved);
      favorite.setContentDescription(available ? (saved ? "取消收藏" : "收藏") + exercise.name
          : exercise.name + "，暂不能收藏");
      favorite.setStateDescription(available ? null : problem);
      explanation.setText(problem);
      explanation.setVisibility(available ? View.GONE : View.VISIBLE);
    };
    updateFavorite.run();
    favorite.setOnClickListener(v -> guard(() -> {
      try {
        db.toggleExerciseFavorite(exercise.id);
      } finally {
        updateFavorite.run();
      }
      if (page == 16 && libraryRefresh != null) libraryRefresh.run();
    }));
    actions.addView(favorite);
    TextView history = headerAction("查看最近训练 ›", () -> showExerciseHistory(exercise));
    history.setContentDescription("查看" + exercise.name + "的最近训练");
    actions.addView(history);
    group.addView(actions);
    group.addView(explanation);
    return group;
  }

  private void exerciseGuide(Domain.Exercise selectedExercise) {
    exerciseGuide(selectedExercise, null);
  }

  private void exerciseGuide(Domain.Exercise selectedExercise, Runnable returnToPrevious) {
    if (selectedExercise.id.startsWith("user_")) {
      customExerciseDetails(selectedExercise);
      return;
    }
    List<Domain.Exercise> activeExercises = new ArrayList<>();
    if (app.active != null)
      for (Domain.Item item : app.active.items) activeExercises.add(item.exercise);
    final Domain.Exercise e =
        ExerciseChoices.preferred(
            ExerciseChoices.preferred(selectedExercise, db.plan("all")), activeExercises);
    final String openedSessionId = app.active == null ? "" : app.active.id;
    LinearLayout content = column();
    content.addView(exerciseQuickActions(e));
    if (GuideImages.IDS.contains(e.id)) {
      content.addView(GuideImages.exercise(this, e), new LinearLayout.LayoutParams(-1, d(190)));
      content.addView(note("动作示意 · " + ExerciseCatalog.subtitle(e)));
      content.addView(note("图示为动作中的一个姿势"));
    } else {
      content.addView(note("暂无动作示意图 · " + ExerciseCatalog.subtitle(e)));
    }
    content.addView(note(desc(e)));
    final AlertDialog[] guideDialog = new AlertDialog[1];
    ExerciseLearning.Lesson lesson = ExerciseLearning.lesson(e);
    content.addView(text("动作步骤", 18, ink, true));
    for (int i = 0; i < lesson.steps().size(); i++) {
      TextView step = text((i + 1) + ". " + lesson.steps().get(i), 15, ink, false);
      step.setPadding(0, d(10), 0, d(4));
      content.addView(step);
    }
    teachingNote(content, "动作要点", lesson.cue());
    teachingNote(content, "呼吸", lesson.breathing());
    teachingNote(content, "常见错误", lesson.avoid());
    String countNote = ExerciseCatalog.countNote(e);
    if (!countNote.isEmpty()) teachingNote(content, "记录方式", countNote);
    boolean inPlan = db.plan("all").stream().anyMatch(x -> ExerciseChoices.same(x, e));
    boolean inTraining =
        app.active != null
            && app.active.items.stream().anyMatch(x -> ExerciseChoices.same(x.exercise, e));
    boolean inDraft = app.active == null && preparation().stream().anyMatch(x -> ExerciseChoices.same(x, e));
    guideDialog[0] =
        new GlassDialogBuilder()
            .setAdaptiveDetail()
            .setTitle(e.name)
            .setView(content)
            .setNegativeButton(returnToPrevious == null ? "关闭" : "返回", (dialog, which) -> {
              if (returnToPrevious != null) returnToPrevious.run();
            })
            .setNeutralButton(
                inPlan ? "查看计划" : "加入计划",
                (dialog, which) ->
                    guard(
                        () -> {
                          List<Domain.Exercise> p = db.plan("all");
                          if (p.stream().anyMatch(x -> ExerciseChoices.same(x, e))) {
                            go(2);
                            return;
                          }
                          Domain.add(p, e, -1);
                          db.planWithDefaults("all", p, Collections.singletonList(e));
                          toast("已加入计划");
                        }))
            .setPositiveButton(
                inTraining ? "返回训练" : app.active == null ? (inDraft ? "已在待开始" : "加入待开始") : "添加到本次",
                (dialog, which) ->
                    guard(
                        () -> {
                          Domain.require(
                              openedSessionId.equals(app.active == null ? "" : app.active.id),
                              "训练状态已变化，请关闭后重新选择");
                          if (app.active == null) {
                            List<Domain.Exercise> draft = preparation();
                            if (draft.stream().noneMatch(x -> ExerciseChoices.same(x, e)))
                              Domain.add(draft, e, -1);
                            savePreparation(draft);
                            go(19);
                            return;
                          } else if (app.active.items.stream()
                              .noneMatch(x -> ExerciseChoices.same(x.exercise, e)))
                            app.appendExercises(Collections.singletonList(e));
                          startService();
                          go(3);
                        }))
            .show();
    if (returnToPrevious != null) guideDialog[0].setOnCancelListener(ignored -> returnToPrevious.run());
  }

  private void teachingNote(LinearLayout content, String heading, String message) {
    TextView label = text(heading, 17, ink, true);
    label.setPadding(0, d(18), 0, d(5));
    content.addView(label);
    content.addView(note(message));
  }

  private void showExerciseHistory(Domain.Exercise source) {
    Domain.Exercise target = source.copy();
    LinearLayout content = column();
    content.addView(note("正在读取记录…"));
    AlertDialog dialog =
        new GlassDialogBuilder()
            .setTitle(target.name + " · 最近训练")
            .setView(content)
            .setNegativeButton("关闭", null)
            .show();
    Thread worker =
        new Thread(
            () -> {
              try {
                List<ExerciseHistory.Entry> history = db.exerciseHistory(target, 10);
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                      content.removeAllViews();
                      if (history.isEmpty()) content.addView(note("还没有完成此动作的记录。训练结束并保存后，会显示在这里。"));
                      else {
                        content.addView(note("最近 " + history.size() + " 次 · 按当时的记录方式显示"));
                        for (ExerciseHistory.Entry entry : history) {
                          TextView date = text(entry.date(), 17, ink, true);
                          date.setPadding(0, d(18), 0, d(6));
                          content.addView(date);
                          for (String line : entry.lines()) content.addView(note(line));
                        }
                      }
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                      content.removeAllViews();
                      content.addView(note("记录读取失败，请关闭后重试。"));
                    });
              }
            },
            "exercise-history");
    dialog.setOnDismissListener(
        ignored -> {
          dialogs.remove(dialog);
          worker.interrupt();
        });
    worker.start();
  }

  private void trainingTemplates() {
    LinearLayout content = column();
    content.addView(note("先查看动作与数量，再按自己的安排加入计划。"));
    final AlertDialog[] dialog = new AlertDialog[1];
    for (TrainingTemplates.Template template : TrainingTemplates.ALL)
      content.addView(
          listRow(
              template.name(),
              template.description(),
              "训练",
              accent,
              () -> {
                dialog[0].dismiss();
                trainingTemplate(template);
              }));
    dialog[0] =
        new GlassDialogBuilder()
            .setTitle("训练模板")
            .setView(content)
            .setNegativeButton("关闭", null)
            .show();
  }

  private void trainingTemplate(TrainingTemplates.Template template) {
    LinearLayout content = column();
    content.addView(note(template.description()));
    content.addView(note("已有动作及其设置会保留，只添加计划中缺少的动作。"));
    List<Domain.Exercise> plan = db.plan("all");
    for (Domain.Exercise e : template.exercises()) {
      LinearLayout item = row();
      item.addView(GuideImages.exercise(this, e), new LinearLayout.LayoutParams(d(64), d(64)));
      LinearLayout words = column();
      words.setPadding(d(12), d(8), 0, d(8));
      words.addView(text(e.name, 16, ink, true));
      Domain.Exercise existing =
          plan.stream()
              .filter(x -> x.id.equals(e.id) || x.name.equals(e.name))
              .findFirst()
              .orElse(null);
      words.addView(note(existing == null ? desc(e) : "已在计划 · 保留 " + desc(existing)));
      item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
      content.addView(item);
    }
    TextView error = note("");
    error.setVisibility(View.GONE);
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    content.addView(error);
    AlertDialog preview =
        new GlassDialogBuilder()
            .setTitle(template.name())
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加到我的计划", null)
            .create();
    preview.setOnShowListener(
        ignored ->
            preview
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        List<Domain.Exercise> old = db.plan("all");
                        List<Domain.Exercise> merged = TrainingTemplates.merge(old, template);
                        int added = merged.size() - old.size();
                        if (added == 0) {
                          preview.dismiss();
                          toast("这些动作已在计划中");
                          return;
                        }
                        db.planWithDefaults(
                            "all", merged, merged.subList(old.size(), merged.size()));
                        preview.dismiss();
                        toast("已添加 " + added + " 项运动");
                        go(2);
                      } catch (Exception failure) {
                        error.setText(
                            failure.getMessage() == null ? "添加失败，请重试" : failure.getMessage());
                        error.setVisibility(View.VISIBLE);
                        error.post(
                            () ->
                                error.requestRectangleOnScreen(
                                    new android.graphics.Rect(
                                        0, 0, error.getWidth(), error.getHeight()),
                                    false));
                      }
                    }));
    preview.show();
    GlassEffects.decorate(preview, dark, accent);
  }

  private String desc(Domain.Exercise e) {
    if (e.id.equals("stair_climb")) return "台阶数 · 时长" + (e.goalSeconds == null ? "" : " · 目标 " + Domain.clock(e.goalSeconds * 1000L));
    if (e.id.equals("incline_treadmill")) return "坡度 · 距离 · 时长" + (e.goalSeconds == null ? "" : " · 目标 " + Domain.clock(e.goalSeconds * 1000L));
    if (e.distanceBased()) return "距离 · 时长 · 均速";
    return e.sets
        + "组 × "
        + (e.timed
            ? Domain.clock(e.seconds * 1000L)
            : e.reps + (ExerciseCatalog.perSide(e) ? "次/侧" : "次"))
        + (e.unit.isEmpty()
            ? ""
            : e.weight == 0 ? " · 未加重量" : " · " + ExerciseCatalog.weightLabel(e) + " " + e.weight + " 千克")
        + ExercisePicker.restSummary(e);
  }

  private void plan() {
    title("我的训练计划");
    body.addView(note("力量、骑行和有氧可放在同一份计划中"));
    if (app.active != null) body.addView(note("修改将在下次训练时使用"));
    if (!db.setting("planMigrationOverflow", "0").equals("0"))
      body.addView(note("旧计划合并后超过30项，额外项目仍保留在本机旧计划数据和备份中。"));
    List<Domain.Exercise> plan = db.plan(scene);
    if (plan.isEmpty()) body.addView(emptyState("训练", "把常练的运动放在一起", "添加运动后，可设置组数、次数、重量和组间休息。"));
    for (int i = 0; i < plan.size(); i++) {
      final int index = i;
      Domain.Exercise e = plan.get(i);
      LinearLayout c = panel();
      c.addView(GuideImages.exercise(this, e), new LinearLayout.LayoutParams(-1, d(136)));
      c.addView(text((i + 1) + ". " + e.name, 18, ink, true));
      c.addView(note(desc(e)));
      LinearLayout controls = row();
      TextView edit = button("编辑", () -> { if (e.id.equals("incline_treadmill") || e.id.equals("stair_climb")) continuousGoalForm(index, false); else exerciseForm(index); }, false);
      edit.setContentDescription("编辑" + e.name);
      controls.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
      controls.addView(
          button(
              "移除",
              () ->
                  confirm(
                      "delete", "移除运动？",
                      e.name,
                      () -> {
                        List<Domain.Exercise> p = db.plan(scene);
                        p.remove(index);
                        db.plan(scene, p);
                        render();
                      }),
              false),
          new LinearLayout.LayoutParams(0, -2, 1));
      if (i > 0)
        controls.addView(
            button(
                "上移",
                () -> {
                  List<Domain.Exercise> p = db.plan(scene);
                  Collections.swap(p, index, index - 1);
                  db.plan(scene, p);
                  render();
                },
                false),
            new LinearLayout.LayoutParams(0, -2, 1));
      c.addView(controls);
      body.addView(c);
    }
    action(body, "添加运动", () -> exerciseForm(-1), plan.isEmpty());
    if (!plan.isEmpty() || app.active != null)
      action(
          body,
          app.active == null ? "开始训练" : "继续本次训练",
          () -> {
            if (app.active == null) {
              prepareFromPlan();
              return;
            }
            go(3);
          },
          true);
  }

  private LinearLayout beginForm(String kind, String target, int index) {
    formKind = kind;
    formTarget = target == null ? "" : target;
    formIndex = index;
    fields.clear();
    choices.clear();
    LinearLayout l = column();
    l.setPadding(0, d(4), 0, d(8));
    return l;
  }

  private EditText input(LinearLayout l, String key, String label, String value, int type) {
    if (formKind.equals("bodyProfile")) {
      TextView caption = text(label, 15, muted, false); caption.setPadding(0, d(16), 0, d(8)); l.addView(caption);
    } else l.addView(note(label));
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(ink);
    e.setTextSize(17);
    e.setInputType(type);
    e.setText(restoringExercise == null ? value : restoringExercise.getString("f:" + key, value));
    e.setPadding(d(12), d(10), d(12), d(10));
    e.setBackground(AppViews.round(HealthDesign.well(dark), d(HealthDesign.INPUT)));
    e.setContentDescription(label);
    e.setSelectAllOnFocus(true);
    fields.put(key, e);
    e.setMinHeight(d(52));
    l.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private Spinner select(LinearLayout l, String key, String label, String[] values, int index) {
    if (formKind.equals("bodyProfile")) {
      TextView caption = text(label, 15, muted, false); caption.setPadding(0, d(16), 0, d(8)); l.addView(caption);
    } else l.addView(note(label));
    Spinner sp = new MenuSpinner();
    sp.setBackground(AppViews.button(soft, accent, d(HealthDesign.INPUT)));
    sp.setPadding(d(12), 0, d(12), 0);
    GradientDrawable popup = AppViews.round(card, d(GlassEffects.CONTROL));
    popup.setStroke(d(1), line);
    sp.setPopupBackgroundDrawable(popup);
    GlassEffects.press(sp);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
          @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
            if (!formKind.equals("bodyProfile")) return super.getView(position, convert, parent);
            TextView label = text(getItem(position), 17, ink, false); label.setGravity(Gravity.CENTER_VERTICAL);
            return label;
          }
        };
    sp.setAdapter(adapter);
    int selectedIndex =
        restoringExercise == null ? index : restoringExercise.getInt("s:" + key, index);
    sp.setSelection(Math.max(0, Math.min(values.length - 1, selectedIndex)));
    sp.setContentDescription(label);
    choices.put(key, sp);
    sp.setMinimumHeight(d(52));
    l.addView(sp, new LinearLayout.LayoutParams(-1, -2));
    return sp;
  }

  private void showForm(String title, LinearLayout l, Runnable save) {
    TextView error = note("");
    error.setTextColor(dark ? 0xFFFF817C : 0xFFC73232);
    error.setVisibility(View.GONE);
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    l.addView(error, 0);
    formDialog =
        new GlassDialogBuilder()
            .setWholeContentScrollable()
            .setTitle(title)
            .setView(l)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    formDialog.show();
    AlertDialog shown = formDialog;
    Map<String,EditText> editingFields = new LinkedHashMap<>(fields);
    Map<String,Spinner> editingChoices = new LinkedHashMap<>(choices);
    String baseline = restoringFormExitBaseline != null
        ? restoringFormExitBaseline : formValues(editingFields, editingChoices);
    formExitBaseline = baseline;
    formExitRequest = protectUnsaved(shown, () -> !baseline.equals(formValues(editingFields, editingChoices)), () -> {
      if (formDialog == shown) closeForm(); else shown.dismiss();
    });
    shown
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              if (formDialog != shown || !shown.isShowing()) return;
              try {
                error.setVisibility(View.GONE);
                save.run();
              } catch (Exception e) {
                error.setText(e.getMessage() == null ? "保存失败，请重试" : e.getMessage());
                error.setVisibility(View.VISIBLE);
                error.post(
                    () ->
                        error.requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, error.getWidth(), error.getHeight()),
                            false));
              }
            });
  }

  private String formValues(Map<String,EditText> editingFields, Map<String,Spinner> editingChoices) {
    TreeMap<String,String> values = new TreeMap<>();
    for (Map.Entry<String,EditText> field : editingFields.entrySet()) values.put("f:" + field.getKey(), field.getValue().getText().toString());
    for (Map.Entry<String,Spinner> choice : editingChoices.entrySet()) values.put("s:" + choice.getKey(), String.valueOf(choice.getValue().getSelectedItemPosition()));
    return new JSONObject(values).toString();
  }

  private java.util.function.Consumer<Runnable> protectUnsaved(AlertDialog editing, java.util.function.BooleanSupplier dirty, Runnable discard) {
    AlertDialog[] confirmation = new AlertDialog[1];
    java.util.function.Consumer<Runnable> request = next -> {
      if (!editing.isShowing()) return;
      if (busy || app.operationBusy) { toast("正在保存，请稍候"); return; }
      if (!dirty.getAsBoolean()) { next.run(); return; }
      if (confirmation[0] != null && confirmation[0].isShowing()) return;
      confirmation[0] = new GlassDialogBuilder().setWholeContentScrollable()
          .setIconKind("info").setDestructive(true)
          .setTitle("放弃未保存的修改？")
          .setMessage("放弃后，本次修改不会保存。")
          .setNegativeButton("继续编辑", null)
          .setPositiveButton("放弃修改", (d,w) -> { if (editing.isShowing()) next.run(); }).show();
    };
    Runnable exit = () -> request.accept(discard);
    if (editing instanceof DesignDialog designed) designed.setCancelRequest(exit);
    editing.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> exit.run());
    return request;
  }

  private void closeForm() {
    if (formDialog != null) formDialog.dismiss();
    formDialog = null;
    formExitBaseline = null;
    formExitRequest = null;
    formKind = "";
  }

  private String val(String key) {
    return fields.get(key).getText().toString().trim();
  }

  private void exerciseForm(int index) {
    if (index < 0) openExercisePicker(index, null);
    else exerciseEditor(index, false);
  }

  private void openExercisePicker(int index, Bundle saved) {
    String targetScene = "all";
    String activeId = app.active == null ? "" : app.active.id;
    Set<String> names = new HashSet<>();
    if (index == -2 && app.active != null)
      for (Domain.Item item : app.active.items) {
        names.add(item.exercise.name);
        if (!item.exercise.id.equals("custom")) names.add(ExerciseChoices.key(item.exercise));
      }
    else
      for (Domain.Exercise e : index == -3 ? preparation() : db.plan(targetScene)) {
        names.add(e.name);
        if (!e.id.equals("custom")) names.add(ExerciseChoices.key(e));
      }
    exercisePicker =
        new ExercisePicker(
            this,
            db,
            targetScene,
            index,
            dark,
            ink,
            muted,
            accent,
            soft,
            line,
            null,
            names,
            dialogs,
            exercises -> {
              if (index == -3) {
                List<Domain.Exercise> draft = preparation();
                for (Domain.Exercise e : exercises) Domain.add(draft, e, -1);
                savePreparation(draft);
                if (page != 19) go(19);
              } else if (index == -2) {
                Domain.require(
                    app.active != null && app.active.id.equals(activeId), "训练已结束，请重新打开运动列表");
                app.appendExercises(exercises);
                startService();
              } else {
                List<Domain.Exercise> plan = db.plan(targetScene);
                for (Domain.Exercise e : exercises) Domain.add(plan, e, -1);
                db.planWithDefaults(targetScene, plan, exercises);
              }
              render();
              toast("已添加" + exercises.size() + "项运动");
            },
            () -> customExerciseForm(null, index),
            saved);
    exercisePicker.show();
  }

  private String customFormSession = "";
  private String customEditBaseline = "";

  private void customExerciseForm(Domain.Exercise existing, int target) {
    customExerciseForm(existing, target, app.active == null ? "" : app.active.id);
  }

  private void customExerciseForm(Domain.Exercise existing, int target, String expectedSession) {
    customExerciseForm(existing, target, expectedSession, existing == null ? null : existing.copy());
  }

  private void customExerciseForm(
      Domain.Exercise existing, int target, String expectedSession, Domain.Exercise expectedBaseline) {
    customFormSession = expectedSession;
    try {
      customEditBaseline = expectedBaseline == null ? "" : Store.exercise(expectedBaseline).toString();
    } catch (JSONException error) {
      throw new IllegalStateException("无法读取运动信息，请重试。", error);
    }
    LinearLayout form = beginForm("customExercise", existing == null ? "" : existing.id, target);
    input(form, "name", "运动名称", existing == null ? "" : existing.name, InputType.TYPE_CLASS_TEXT);
    Spinner mode =
        select(
            form,
            "mode",
            "记录方式",
            new String[] {"次数与组数", "时长与组数", "距离与时长"},
            existing != null && existing.distanceBased()
                ? 2
                : existing != null && existing.timed ? 1 : 0);
    Spinner unit =
        select(
            form,
            "unit",
            "重量记录",
            new String[] {"不记录重量", "总重量", "单件重量"},
            existing == null
                ? 0
                : existing.unit.equals("每只") ? 2 : existing.unit.equals("总重") ? 1 : 0);
    input(form, "gear", "器材（选填）", existing == null ? "" : existing.gear, InputType.TYPE_CLASS_TEXT);
    EditText sets =
        input(form, "sets", "组数", existing == null ? "3" : String.valueOf(existing.sets), 2);
    EditText reps =
        input(form, "reps", "每组次数", existing == null ? "12" : String.valueOf(existing.reps), 2);
    EditText seconds =
        input(
            form,
            "seconds",
            "每组时长（秒）",
            existing == null ? "60" : String.valueOf(existing.seconds),
            2);
    EditText restSeconds = input(form, "restSeconds", "组间休息（秒）",
        String.valueOf(existing == null ? Domain.DEFAULT_REST_SECONDS : existing.restSeconds), 2);
    EditText weight =
        input(
            form,
            "weight",
            "重量（千克）",
            existing == null ? "0" : String.valueOf(existing.weight),
            8194);
    Runnable visibility =
        () -> {
          boolean distance = mode.getSelectedItemPosition() == 2;
          boolean timed = mode.getSelectedItemPosition() != 0;
          showInput(sets, !distance);
          showInput(reps, !timed);
          showInput(seconds, timed && !distance);
          showInput(restSeconds, !distance);
          showInput(unit, !timed);
          showInput(weight, !timed && unit.getSelectedItemPosition() > 0);
        };
    AdapterView.OnItemSelectedListener changed =
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            visibility.run();
          }
        };
    mode.setOnItemSelectedListener(changed);
    unit.setOnItemSelectedListener(changed);
    visibility.run();
    TextView reload =
        button(
            "重新载入最新内容",
            () -> {
              AlertDialog confirmation =
                  new GlassDialogBuilder()
                      .setWholeContentScrollable()
                      .setTitle("重新载入运动？")
                      .setMessage("未保存的修改将被运动库中的当前内容替换。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "重新载入",
                          (d, w) ->
                              guard(
                                  () -> {
                                    Domain.Exercise current = db.currentCustomExercise(existing.id);
                                    closeForm();
                                    customExerciseForm(current, target, expectedSession);
                                  }))
                      .create();
              showScrollableConfirmation(confirmation);
            },
            false);
    reload.setVisibility(existing != null && expectedBaseline == null ? View.VISIBLE : View.GONE);
    form.addView(reload, 0);
    showForm(
        existing == null ? "新建运动" : "编辑自定义运动",
        form,
        () -> {
          boolean distance = mode.getSelectedItemPosition() == 2;
          boolean timed = mode.getSelectedItemPosition() != 0;
          Domain.Exercise e =
              new Domain.Exercise(
                  existing == null ? "user_" + UUID.randomUUID() : existing.id,
                  val("name").trim(),
                  val("gear").trim(),
                  timed,
                  distance
                      ? "距离"
                      : timed || unit.getSelectedItemPosition() == 0
                          ? ""
                          : unit.getSelectedItemPosition() == 1 ? "总重" : "每只");
          Domain.require(e.gear.length() <= 30, "器材名称最多30个字");
          e.sets = distance ? 1 : Domain.integer(val("sets"), 1, 20, "组数");
          e.reps = timed ? 12 : Domain.integer(val("reps"), 1, 200, "次数");
          e.seconds = timed && !distance ? Domain.integer(val("seconds"), 1, 14400, "时长") : 60;
          e.restSeconds = distance ? Domain.DEFAULT_REST_SECONDS
              : Domain.integer(val("restSeconds"), 0, Domain.MAX_REST_SECONDS, "组间休息");
          e.weight = timed || e.unit.isEmpty() ? 0 : Domain.number(val("weight"), "重量");
          e.validate();
          if (target == -2) {
            Domain.require(
                app.active != null && app.active.id.equals(expectedSession), "训练已变化，请重新添加");
            Domain.require(app.active.items.size() < 30, "每次训练最多添加30项运动");
          } else if (target == -3) Domain.require(preparation().size() < 30, "最多添加30项运动");
          else if (target == -1) Domain.require(db.plan("all").size() < 30, "计划最多添加30项运动");
          if (existing == null) db.saveCustomExercise(e);
          else {
            try {
              db.saveCustomExercise(e, expectedBaseline);
            } catch (Store.ExerciseConflict conflict) {
              reload.setVisibility(View.VISIBLE);
              throw conflict;
            }
          }
          closeForm();
          if (target == -2 && app.active != null) {
            app.appendExercises(List.of(e));
            startService();
            go(3);
          } else if (target == -3) {
            List<Domain.Exercise> draft = preparation();
            Domain.add(draft, e, -1);
            savePreparation(draft);
            go(19);
          } else if (target == -1) {
            List<Domain.Exercise> plan = db.plan("all");
            Domain.add(plan, e, -1);
            db.plan("all", plan);
            render();
          } else {
            libraryCategory = "全部";
            libraryQuery = e.name;
            go(16);
          }
        });
  }

  private void customExerciseDetails(Domain.Exercise e) {
    LinearLayout content = column();
    String availability = safeFavoriteUnavailableReason(e.id);
    content.addView(exerciseQuickActions(e, availability));
    content.addView(note("自定义运动 · " + (e.gear.isEmpty() ? "无需器材" : e.gear)));
    content.addView(text(desc(e), 20, ink, true));
    content.addView(note("按你填写的内容记录，不自动估算运动消耗。"));
    final AlertDialog[] dialog = new AlertDialog[1];
    if (availability.isEmpty()) {
      action(
          content,
          "编辑运动",
          () -> {
            Domain.Exercise current = db.currentCustomExercise(e.id);
            dialog[0].dismiss();
            customExerciseForm(current, -4);
          },
          false);
      action(
          content,
          "删除自定义运动",
          () -> {
            Domain.Exercise current = db.currentCustomExercise(e.id);
            confirm(
                "delete", "删除“" + current.name + "”？",
                "已保存的训练记录和计划会保留。",
                () -> {
                  db.removeCustomExercise(e.id, current);
                  dialog[0].dismiss();
                  render();
                });
          },
          false);
    }
    final String session = app.active == null ? "" : app.active.id;
    boolean inDraft = app.active == null && preparation().stream().anyMatch(x -> ExerciseChoices.same(x, e));
    dialog[0] =
        new GlassDialogBuilder()
            .setAdaptiveDetail()
            .setTitle(e.name)
            .setView(content)
            .setNegativeButton("关闭", null)
            .setPositiveButton(
                app.active == null ? (inDraft ? "已在待开始" : "加入待开始") : "添加到本次",
                (d, w) ->
                    guard(
                        () -> {
                          Domain.require(
                              session.equals(app.active == null ? "" : app.active.id),
                              "训练状态已变化，请重新选择");
                          if (app.active != null) {
                            app.appendExercises(List.of(e));
                            startService();
                            go(3);
                          } else {
                            List<Domain.Exercise> draft = preparation();
                            if (draft.stream().noneMatch(x -> ExerciseChoices.same(x, e)))
                              Domain.add(draft, e, -1);
                            savePreparation(draft);
                            go(19);
                          }
                        }))
            .show();
  }

  private void exerciseEditor(int index, boolean custom) {
    boolean editingPreparation = index <= -100;
    int editIndex = editingPreparation ? -100 - index : index;
    List<Domain.Exercise> plan = editingPreparation ? preparation() : db.plan(scene);
    Domain.Exercise current = editIndex >= 0 ? plan.get(editIndex) : null;
    List<Domain.Exercise> catalog = Domain.catalog("all", db.gear());
    if (current != null && catalog.stream().noneMatch(e -> e.id.equals(current.id)))
      catalog.add(current);
    catalog.add(new Domain.Exercise("custom", "自定义运动", "", false, "总重"));
    int initial = 0;
    if (custom) initial = catalog.size() - 1;
    for (int i = 0; i < catalog.size(); i++)
      if (current != null && catalog.get(i).id.equals(current.id)) initial = i;
    if (restoringExercise != null)
      initial =
          Math.max(
              0, Math.min(catalog.size() - 1, restoringExercise.getInt("s:exercise", initial)));
    LinearLayout l = beginForm("exercise", null, index);
    Spinner exercise =
        select(
            l, "exercise", "运动", catalog.stream().map(e -> e.name).toArray(String[]::new), initial);
    Domain.Exercise first =
        current != null && current.id.equals(catalog.get(initial).id)
            ? current
            : catalog.get(initial);
    input(
        l, "name", "运动名称", first.id.equals("custom") ? "" : first.name, InputType.TYPE_CLASS_TEXT);
    Spinner mode = select(l, "mode", "记录方式", new String[] {"按次数", "按时长"}, first.timed ? 1 : 0);
    EditText sets = input(l, "sets", "组数", String.valueOf(first.sets), 2);
    EditText reps = input(l, "reps", "每组次数", String.valueOf(first.reps), 2);
    EditText seconds = input(l, "seconds", "每组时长（秒）", String.valueOf(first.seconds), 2);
    EditText restSeconds = input(l, "restSeconds", "组间休息（秒）",
        String.valueOf(first.restSeconds), 2);
    EditText weight = input(l, "weight", ExerciseCatalog.weightLabel(first) + "（千克）", String.valueOf(first.weight), 8194);
    showInput(weight, !first.unit.isEmpty() && mode.getSelectedItemPosition() == 0);
    showInput(mode, !first.distanceBased());
    showInput(sets, !first.distanceBased());
    showInput(reps, !first.distanceBased() && !first.timed);
    showInput(seconds, !first.distanceBased() && first.timed);
    showInput(restSeconds, !first.distanceBased());
    TextView recordNote = note(exerciseRecordNote(first, mode.getSelectedItemPosition() == 1));
    l.addView(recordNote);
    exercise.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          boolean firstEvent = true;

          public void onNothingSelected(AdapterView<?> a) {}

          public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
            if (firstEvent) {
              firstEvent = false;
              return;
            }
            Domain.Exercise e = catalog.get(pos);
            recordNote.setText(exerciseRecordNote(e, e.timed));
            fields.get("name").setText(e.id.equals("custom") ? "" : e.name);
            sets.setText(String.valueOf(e.sets));
            reps.setText(String.valueOf(e.reps));
            seconds.setText(String.valueOf(e.seconds));
            restSeconds.setText(String.valueOf(e.restSeconds));
            weight.setText(String.valueOf(e.weight));
            ViewGroup weightParent = (ViewGroup) weight.getParent();
            View weightCaption = weightParent.getChildAt(weightParent.indexOfChild(weight) - 1);
            if (weightCaption instanceof TextView caption) caption.setText(ExerciseCatalog.weightLabel(e) + "（千克）");
            weight.setContentDescription(ExerciseCatalog.weightLabel(e) + "（千克）");
            mode.setSelection(e.timed ? 1 : 0);
            showInput(weight, !e.unit.isEmpty() && !e.timed);
            showInput(mode, !e.distanceBased());
            showInput(sets, !e.distanceBased());
            showInput(reps, !e.distanceBased() && !e.timed);
            showInput(seconds, !e.distanceBased() && e.timed);
            showInput(restSeconds, !e.distanceBased());
          }
        });
    mode.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> a) {}

          public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
            boolean distance = catalog.get(exercise.getSelectedItemPosition()).distanceBased();
            recordNote.setText(
                exerciseRecordNote(catalog.get(exercise.getSelectedItemPosition()), p == 1));
            showInput(reps, !distance && p == 0);
            showInput(seconds, !distance && p == 1);
            showInput(
                weight, p == 0 && !catalog.get(exercise.getSelectedItemPosition()).unit.isEmpty());
          }
        });
    showForm(
        current == null ? "添加运动" : "编辑运动",
        l,
        () -> {
          Domain.Exercise e = catalog.get(exercise.getSelectedItemPosition()).copy();
          String name = val("name");
          // A user label does not change the selected activity's image or record semantics.
          e.name = name;
          e.timed = e.distanceBased() || mode.getSelectedItemPosition() == 1;
          e.sets = e.distanceBased() ? 1 : Domain.integer(val("sets"), 1, 20, "组数");
          e.reps = e.timed ? 12 : Domain.integer(val("reps"), 1, 200, "次数");
          e.seconds =
              e.timed && !e.distanceBased() ? Domain.integer(val("seconds"), 1, 14400, "时长") : 60;
          e.restSeconds = e.distanceBased() ? Domain.DEFAULT_REST_SECONDS
              : Domain.integer(val("restSeconds"), 0, Domain.MAX_REST_SECONDS, "组间休息");
          e.weight = e.timed || e.unit.isEmpty() ? 0 : Domain.number(val("weight"), "重量");
          if (editingPreparation) {
            List<Domain.Exercise> draft = preparation();
            Domain.require(editIndex < draft.size(), "运动列表已变化，请重新打开");
            for (int i = 0; i < draft.size(); i++)
              Domain.require(i == editIndex || !ExerciseChoices.same(draft.get(i), e), "该运动已添加");
            Domain.add(draft, e, editIndex);
            savePreparation(draft);
          } else if (index == -3) {
            List<Domain.Exercise> draft = preparation();
            Domain.add(draft, e, -1);
            savePreparation(draft);
          } else if (index == -2) {
            app.appendExercises(java.util.Collections.singletonList(e));
            startService();
          } else {
            List<Domain.Exercise> p = db.plan(scene);
            Domain.add(p, e, index);
            db.planWithDefaults(scene, p, List.of(e));
          }
          closeForm();
          render();
        });
  }

  private String exerciseRecordNote(Domain.Exercise exercise, boolean timed) {
    Domain.Exercise shown = exercise.copy();
    shown.timed = timed;
    return (timed || exercise.unit.isEmpty() ? "" : exercise.unit.equals("每只")
        ? exercise.gear.contains("哑铃") ? "重量按单只哑铃填写。" : "重量按单件填写。"
        : "重量按" + ExerciseCatalog.weightLabel(exercise) + "填写。")
        + ExerciseCatalog.countNote(shown);
  }

  private void showInput(View field, boolean show) {
    ViewGroup parent = (ViewGroup) field.getParent();
    int at = parent.indexOfChild(field);
    field.setVisibility(show ? View.VISIBLE : View.GONE);
    if (at > 0) parent.getChildAt(at - 1).setVisibility(show ? View.VISIBLE : View.GONE);
  }

  private void startService() {
    try {
      startForegroundService(new Intent(this, WorkoutService.class));
      if (Build.VERSION.SDK_INT >= 33
          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 100);
    } catch (Exception e) {
      if (app.active != null) app.mutate(q -> q.pause(SystemClock.elapsedRealtime()));
      throw new IllegalStateException("无法启动后台计时，训练已暂停。", e);
    }
  }

  private final Map<String, String> inlineValues = new HashMap<>();

  private EditText setInput(
      String key, String value, String label, boolean decimal, boolean enabled) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextSize(17);
    e.setTextColor(enabled ? ink : teal());
    e.setGravity(Gravity.CENTER);
    e.setInputType(
        InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
    e.setPadding(d(4), d(8), d(4), d(8));
    e.setMinHeight(touchTarget());
    e.setMinWidth(touchTarget());
    e.setSelectAllOnFocus(true);
    e.setBackground(
        AppViews.round(
            enabled ? HealthDesign.well(dark) : (teal() & 0xffffff) | 0x14000000,
            d(HealthDesign.INPUT)));
    e.setText(inlineValues.getOrDefault(key, value));
    e.setContentDescription(label);
    e.setEnabled(enabled);
    if (enabled)
      e.addTextChangedListener(
          new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence x, int st, int c, int a) {}

            public void onTextChanged(CharSequence x, int st, int before, int count) {
              if (!updatingInline) inlineValues.put(key, x.toString());
            }

            public void afterTextChanged(android.text.Editable x) {}
          });
    return e;
  }

  private void training() {
    Domain.Session q = app.active;
    if (q != null && q.items.size() == 1 && q.item().exercise.distanceBased()) {
      trainingLegacy();
      return;
    }
    LinearLayout top = row();
    TextView backTraining = link("‹ 返回训练", () -> go(1));
    backTraining.setContentDescription("返回训练");
    backTraining.setTextSize(17);
    top.addView(backTraining, new LinearLayout.LayoutParams(0, -2, 1));
    if (q != null) {
      TextView end = link("结束", this::finishDialog);
      end.setTextSize(17);
      end.setTextColor(dark ? 0xFFFF817C : 0xFFC73232);
      end.setMinWidth(d(48));
      top.addView(end, new LinearLayout.LayoutParams(-2, -2));
    }
    body.addView(top);
    TextView trainingTitle = text("本次训练", 34, ink, true);
    trainingTitle.setPadding(0, d(8), 0, d(16));
    body.addView(trainingTitle);
    if (q == null) {
      body.addView(emptyState("训练", "准备开始", "无需提前安排，训练中也能添加运动。"));
      action(body, "开始运动", this::freeTraining, true);
      return;
    }
    LinearLayout overview = panel();
    sessionState = text(q.running ? "训练进行中" : "训练已暂停", 17, orange(), true);
    overview.addView(sessionState);
    overview.addView(text("已训练时长", 15, muted, false));
    clockText = text(Domain.clock(q.elapsed), 38, ink, true);
    clockText.setFontFeatureSettings("tnum");
    clockText.setPadding(0, d(12), 0, d(16));
    overview.addView(clockText);
    LinearLayout stats = row(), completed = column(), load = column();
    completed.addView(
        metric(
            q.target() == 0 ? String.valueOf(q.items.size()) : q.count() + " / " + q.target(),
            q.target() == 0 ? "项" : "组",
            20));
    completed.addView(note(q.target() == 0 ? "本次运动" : "已完成组数"));
    long recorded = q.items.stream().filter(i -> i.logs.stream().anyMatch(l -> !l.partial)).count();
    load.addView(metric(String.valueOf(recorded), "项", 20));
    load.addView(note("已记录运动"));
    stats.addView(completed, new LinearLayout.LayoutParams(0, -2, 1));
    stats.addView(load, new LinearLayout.LayoutParams(0, -2, 1));
    overview.addView(stats);
    MetricRow actions = new MetricRow(8);
    actions.setPadding(0, d(14), 0, 0);
    actions.addView(
        button(
            q.running ? "暂停" : "继续",
            () -> {
              app.toggleTrainingRunning(q.id);
              startService();
              render();
            },
            false),
        new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(
        button("添加运动", () -> exerciseForm(-2), true), new LinearLayout.LayoutParams(0, -2, 1));
    for (int i = 0; i < actions.getChildCount(); i++) ((TextView) actions.getChildAt(i)).setTextSize(17);
    overview.addView(actions);
    body.addView(overview);
    if (q.rest > 0) {
      LinearLayout rest = panel();
      restText = text("休息 " + Domain.clock(q.rest), 17, accent, true);
      rest.addView(restText);
      String target = restTarget(q);
      MetricRow restActions = new MetricRow(8);
      restActions.addView(button("调整本次休息", () -> restForm(target), false));
      restActions.addView(button("结束休息", () -> {
        app.mutate(s -> {
          Domain.require(target.equals(restTarget(s)), "休息状态已变化，请返回训练");
          s.endRest(SystemClock.elapsedRealtime());
        });
        startService();
        render();
      }, false));
      rest.addView(restActions);
      body.addView(rest);
      restPanel = rest;
    }
    section("运动项目");
    if (q.items.isEmpty()) body.addView(note("还没有添加运动项目"));
    for (int n = 0; n < q.items.size(); n++) {
      final int index = n;
      Domain.Item item = q.items.get(n);
      Domain.Exercise e = item.exercise;
      if (e.distanceBased()) {
        distanceCard(q, index);
        continue;
      }
      LinearLayout c = panel(), head = row();
      GuideImages guide = GuideImages.exercise(this, e);
      guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      guide.setContentDescription("查看" + e.name + "指引");
      guide.setFocusable(true);
      guide.setOnClickListener(v -> guard(() -> exerciseGuide(e)));
      head.addView(guide, new LinearLayout.LayoutParams(d(58), d(58)));
      LinearLayout names = column();
      names.addView(text(e.name, 17, ink, true));
      names.addView(note((e.gear.isEmpty() ? "自重" : e.gear) + (e.unit.isEmpty() || e.unit.equals("每只") ? "" : " · " + e.unit)));
      head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
      TextView more =
          link(
              "+",
              () -> {
                app.mutate(
                    s -> {
                      Domain.require(s.items.get(index).exercise.sets < 20, "每项最多20组");
                      s.items.get(index).exercise.sets++;
                    });
                render();
              });
      more.setContentDescription("为" + e.name + "增加一组");
      head.addView(more);
      c.addView(head);
      if (!e.timed && !item.done()) {
        TextView activate =
            link(
                n == q.current ? (q.running ? "正在训练" : "继续此项") : "开始此项",
                () -> {
                  app.mutate(
                      state -> {
                        Domain.require(state.id.equals(q.id), "训练状态已变化，请重新查看");
                        Domain.require(!state.items.get(index).done(), "本项已完成");
                        Domain.require(state.rest == 0, "请先结束休息");
                        state.select(index, SystemClock.elapsedRealtime());
                        state.resume(SystemClock.elapsedRealtime());
                      });
                  startService();
                  render();
                });
        activate.setContentDescription("开始" + e.name);
        activate.setEnabled(n != q.current || !q.running);
        c.addView(activate);
      }
      if (ExerciseCatalog.perSide(e)) c.addView(note(ExerciseCatalog.countNote(e)));
      if (e.unit.equals("每只")) c.addView(note(e.gear.contains("哑铃") ? "重量按单只哑铃填写，左右各12.5千克时填12.5。" : "重量按单件填写。"));
      boolean showWeight = !e.timed && !e.unit.isEmpty();
      int fieldColumns = e.timed || showWeight ? 2 : 1;
      int touch = touchTarget();
      HorizontalScrollView setViewport = new HorizontalScrollView(this);
      setViewport.setFillViewport(true);
      setViewport.setHorizontalFadingEdgeEnabled(true);
      setViewport.setFadingEdgeLength(d(12));
      LinearLayout setTable = column();
      // Keep every input at least 48 dp wide, even in a window narrower than the table.
      setTable.setMinimumWidth(
          d(24) + d(80) + fieldColumns * touch + (fieldColumns + 1) * d(8));
      setViewport.addView(setTable, new FrameLayout.LayoutParams(-2, -2));
      LinearLayout labels = row();
      labels.addView(text("组", 12, muted, false), new LinearLayout.LayoutParams(d(24), -2));
      if (e.timed || showWeight) {
        TextView wLabel = text(e.timed ? "方式" : e.unit.equals("每只") ? (e.gear.contains("哑铃") ? "单只重量（千克）" : "单件重量（千克）") : "总重量（千克）", 12, muted, false);
        wLabel.setGravity(Gravity.CENTER);
        labels.addView(wLabel, new LinearLayout.LayoutParams(0, -2, 1));
      }
      TextView rLabel =
          text(e.timed ? "时长 / 秒" : ExerciseCatalog.perSide(e) ? "次数 / 侧" : "次数", 12, muted, false);
      rLabel.setGravity(Gravity.CENTER);
      labels.addView(rLabel, new LinearLayout.LayoutParams(0, -2, 1));
      labels.addView(text("记录状态", 12, muted, false), new LinearLayout.LayoutParams(d(80), -2));
      setTable.addView(labels);
      for (int j = 0; j < e.sets; j++) {
        final int setIndex = j;
        boolean done = j < item.logs.size(), next = j == item.logs.size();
        Domain.Log log = done ? item.logs.get(j) : null;
        String key = q.id + ":" + index + ":" + j;
        LinearLayout r = row();
        r.setPadding(0, d(8), 0, 0);
        TextView groupNumber = text(String.valueOf(j + 1), 12, done ? teal() : muted, false);
        groupNumber.setMinHeight(touch);
        groupNumber.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(groupNumber, new LinearLayout.LayoutParams(d(24), -2));
        EditText weight =
            showWeight
                ? setInput(
                    key + ":weight",
                    String.valueOf(done ? log.weight : e.weight),
                    e.name + "第" + (j + 1) + "组重量",
                    true,
                    next)
                : null;
        if (e.timed) {
          TextView mode = text("计时", 13, muted, false);
          mode.setGravity(Gravity.CENTER);
          mode.setMinHeight(touch);
          r.addView(mode, new LinearLayout.LayoutParams(0, -2, 1));
        } else if (showWeight) r.addView(weight, new LinearLayout.LayoutParams(0, -2, 1));
        EditText amount =
            setInput(
                key + ":amount",
                String.valueOf(
                    e.timed
                        ? (done
                            ? log.ms / 1000
                            : n == q.current ? item.currentMs / 1000 : e.seconds)
                        : (done ? log.reps : e.reps)),
                e.name + "第" + (j + 1) + "组" + (e.timed ? "秒数" : "次数"),
                false,
                next);
        r.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        TextView check =
            button(
                done ? "已记录" : next ? "记录" : "待记录",
                () -> {
                  int reps =
                      Domain.integer(
                          amount.getText().toString(),
                          1,
                          e.timed ? 14400 : 200,
                          e.timed ? "时长" : "次数");
                  double loadKg =
                      e.timed || e.unit.isEmpty()
                          ? 0
                          : Domain.number(weight.getText().toString(), "重量");
                  app.mutate(
                      s -> {
                        Domain.require(s.id.equals(q.id), "训练状态已变化，请重新查看");
                        s.recordInline(
                            index, setIndex, reps, loadKg, SystemClock.elapsedRealtime());
                      });
                  inlineValues.remove(key + ":weight");
                  inlineValues.remove(key + ":amount");
                  startService();
                  render();
                },
                false);
        check.setEnabled(next);
        check.setText(done ? "已记录" : next ? "记录" : "待记录");
        check.setTextSize(14);
        check.setTextColor(next ? (dark ? 0xFF10151E : Color.WHITE) : muted);
        check.setContentDescription(done ? "已记录" + e.name + "第" + (j + 1) + "组"
            : next ? "记录" + e.name + "第" + (j + 1) + "组"
            : e.name + "第" + (j + 1) + "组待记录，请先记录前一组");
        check.setSelected(done);
        check.setMinHeight(touch);
        check.setBackground(AppViews.button(next ? accent : HealthDesign.well(dark), accent, d(HealthDesign.INPUT)));
        r.addView(check, new LinearLayout.LayoutParams(d(80), -2));
        setTable.addView(r);
        if (next && n == q.current) {
          if (e.timed) {
            liveTimedAmount = amount;
            liveTimedKey = key + ":amount";
          }
          actualReps = amount;
          actualWeight = e.unit.isEmpty() ? null : weight;
        }
      }
      c.addView(setViewport);
      if (e.timed && !item.done()) {
        LinearLayout timed = row();
        TextView elapsed = text("本组 " + Domain.clock(item.currentMs), 14, teal(), true);
        if (n == q.current) roundText = elapsed;
        timed.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        TextView timedControl =
            link(
                n == q.current && q.running ? "计时中" : "计时此项",
                () -> {
                  app.mutate(
                      s -> {
                        Domain.require(s.id.equals(q.id), "训练状态已变化，请重新查看");
                        Domain.require(s.rest == 0, "请先结束休息");
                        s.select(index, SystemClock.elapsedRealtime());
                        s.resume(SystemClock.elapsedRealtime());
                      });
                  startService();
                  render();
                });
        Runnable updateTimedControl =
            () -> {
              Domain.Session live = app.active;
              if (live == null || !live.id.equals(q.id) || index >= live.items.size()) return;
              boolean currentItem = index == live.current;
              String label =
                  currentItem && live.rest > 0
                      ? "休息中"
                      : currentItem && live.running ? "计时中" : "计时此项";
              if (!label.contentEquals(timedControl.getText())) timedControl.setText(label);
              boolean enabled = live.rest == 0 && !(currentItem && live.running);
              if (timedControl.isEnabled() != enabled) timedControl.setEnabled(enabled);
            };
        updateTimedControl.run();
        distanceLive.add(updateTimedControl);
        timed.addView(timedControl);
        c.addView(timed);
      }
      if (!item.logs.isEmpty())
        c.addView(
            link(
                "撤销最后一组",
                () -> {
                  Domain.require(
                      app.active != null && app.active.id.equals(q.id) && index < app.active.items.size(),
                      "训练状态已变化，请重新查看");
                  long now = SystemClock.elapsedRealtime();
                  try {
                    app.active.requireUndoSafe(index, now);
                  } catch (IllegalArgumentException error) {
                    if (!Domain.TIMED_UNDO_MESSAGE.equals(error.getMessage())) throw error;
                    new GlassDialogBuilder()
                        .setTitle("无法撤销")
                        .setMessage(error.getMessage())
                        .setPositiveButton("返回训练", null)
                        .show();
                    return;
                  }
                  String pendingKey = q.id + ":" + index + ":" + app.active.items.get(index).logs.size();
                  if (!Objects.toString(inlineValues.get(pendingKey + ":amount"), "").isEmpty()
                      || !Objects.toString(inlineValues.get(pendingKey + ":weight"), "").isEmpty()) {
                    new GlassDialogBuilder()
                        .setTitle("无法撤销")
                        .setMessage("本项有尚未完成的输入，暂时无法撤销。请返回训练继续记录。")
                        .setPositiveButton("返回训练", null)
                        .show();
                    return;
                  }
                  try {
                    app.mutate(
                        s -> {
                          Domain.require(s.id.equals(q.id), "训练状态已变化，请重新查看");
                          s.undoAt(index, now);
                        });
                  } catch (IllegalArgumentException error) {
                    if (!Domain.TIMED_UNDO_MESSAGE.equals(error.getMessage())) throw error;
                    new GlassDialogBuilder()
                        .setTitle("无法撤销")
                        .setMessage(error.getMessage())
                        .setPositiveButton("返回训练", null)
                        .show();
                    return;
                  }
                  if (Objects.toString(inlineValues.get(pendingKey + ":amount"), "").isEmpty())
                    inlineValues.remove(pendingKey + ":amount");
                  if (Objects.toString(inlineValues.get(pendingKey + ":weight"), "").isEmpty())
                    inlineValues.remove(pendingKey + ":weight");
                  render();
                }));
      body.addView(c);
    }
    LinearLayout heart = panel(), r = row();
    heartText =
        text(
            q.liveHeart(SystemClock.elapsedRealtime()) == 0 ? "— 次/分钟" : q.heart + " 次/分钟",
            16,
            ink,
            true);
    r.addView(heartText, new LinearLayout.LayoutParams(0, -2, 1));
    r.addView(link("连接心率设备", () -> go(6)));
    heart.addView(r);
    body.addView(heart);
  }

  private String restTarget(Domain.Session session) {
    return session.items.isEmpty() ? ""
        : session.id + ":" + session.current + ":" + session.item().logs.size();
  }

  private void restForm(String target) {
    Domain.Session session = app.active;
    if (session == null || session.rest <= 0 || !target.equals(restTarget(session))) {
      toast("休息已结束，请返回训练");
      return;
    }
    LinearLayout form = beginForm("rest", target, -1);
    input(form, "remainingRest", "剩余休息（秒）", String.valueOf((session.rest + 999) / 1000), 2);
    form.addView(note("仅调整当前这次休息。填 0 可结束休息。"));
    showForm("调整本次休息", form, () -> {
      int seconds = Domain.integer(val("remainingRest"), 0, Domain.MAX_REST_SECONDS, "剩余休息");
      app.mutate(s -> {
        Domain.require(target.equals(restTarget(s)), "休息状态已变化，请返回训练");
        s.setRestRemaining(seconds, SystemClock.elapsedRealtime());
      });
      closeForm();
      startService();
      render();
    });
  }

  private String distanceDescription(Domain.Item i) {
    if (i.manualMeters == null && i.gpsMeters <= 0) return "距离未记录";
    double km = i.meters() / 1000;
    Double average = i.speedKmh();
    String speed = average == null ? "—" : String.format(Locale.CHINA, "%.1f 公里/小时", average);
    return String.format(Locale.CHINA, "%.2f 公里 · 均速 %s", km, speed);
  }

  private void distanceCard(Domain.Session q, int index) {
    Domain.Item item = q.items.get(index);
    LinearLayout c = panel(), heading = row();
    GuideImages guide = GuideImages.exercise(this, item.exercise);
    guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    guide.setContentDescription("查看" + item.exercise.name + "指引");
    guide.setFocusable(true);
    guide.setOnClickListener(v -> guard(() -> exerciseGuide(item.exercise)));
    heading.addView(guide, new LinearLayout.LayoutParams(d(70), d(70)));
    LinearLayout words = column();
    words.setPadding(d(14), 0, 0, 0);
    words.addView(text(item.exercise.name, 18, ink, true));
    if (!item.exercise.id.equals("stair_climb")) words.addView(
        note(
            item.manualMeters != null
                ? "手动距离"
                : item.gpsMeters > 0 ? "历史GPS记录的距离"
                : item.exercise.usesGps() || item.exercise.id.startsWith("user_")
                    ? "手动填写距离" : "填写器械上的距离"));
    heading.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    c.addView(heading);
    TextView stats = text(distanceDescription(item), 20, ink, true);
    stats.setPadding(0, d(12), 0, d(8));
    if (!item.exercise.id.equals("stair_climb")) c.addView(stats);
    if (item.exercise.goalSeconds != null) c.addView(note("目标时长 " + Domain.clock(item.exercise.goalSeconds * 1000L)));
    TextView duration = note("项目时长 " + Domain.clock(item.durationMs()));
    c.addView(duration);
    if (item.manualMeters != null) c.addView(note("均速按本项目用时计算，时间过短或数值异常时暂不显示。"));
    distanceLive.add(
        () -> {
          stats.setText(distanceDescription(item));
          duration.setText("项目时长 " + Domain.clock(item.durationMs()));
        });
    if (q.current != index)
      action(
          c,
          "开始此项",
          () -> {
            app.mutate(
                s -> {
                  s.select(index, SystemClock.elapsedRealtime());
                  s.resume(SystemClock.elapsedRealtime());
                });
            startService();
            render();
          },
          true);
    if (item.exercise.id.equals("incline_treadmill") || item.exercise.id.equals("stair_climb")) {
      c.addView(note(item.exercise.id.equals("incline_treadmill")
          ? item.inclinePercent == null ? "坡度未记录" : "坡度 " + item.inclinePercent + "%"
          : item.stairSteps == null ? "台阶数未记录" : "台阶 " + item.stairSteps + "级"));
      action(c, "填写本次数据", () -> continuousMetricsForm(index), false);
    } else action(c, "填写距离", () -> distanceForm(index), false);
    body.addView(c);
  }

  private void continuousGoalForm(int index, boolean draft) {
    List<Domain.Exercise> values = draft ? preparation() : db.plan(scene);
    if (index < 0 || index >= values.size()) return;
    Domain.Exercise original = values.get(index).copy();
    LinearLayout form = beginForm("continuousGoal", draft ? "preparation" : "plan", index);
    input(form, "goalSeconds", "目标时长（秒，可留空）", original.goalSeconds == null ? "" : original.goalSeconds.toString(), 2);
    form.addView(note("目标不会自动计入本次实际运动。"));
    showForm("设置目标", form, () -> {
      String entered = val("goalSeconds");
      Integer goal = entered.isEmpty() ? null : Domain.integer(entered, 1, 14400, "目标时长");
      List<Domain.Exercise> fresh = draft ? preparation() : db.plan(scene);
      Domain.require(index < fresh.size() && Domain.sameExerciseContents(original, fresh.get(index)), "运动已变化，请重新打开");
      fresh.get(index).goalSeconds = goal;
      if (draft) savePreparation(fresh); else db.plan(scene, fresh);
      closeForm(); render();
    });
  }

  private void continuousMetricsForm(int index) {
    if (app.active == null || index >= app.active.items.size()) return;
    String sessionId = app.active.id;
    Domain.Item item = app.active.items.get(index);
    String exerciseId = item.exercise.id;
    boolean stair = item.exercise.id.equals("stair_climb");
    LinearLayout form = beginForm("continuousMetrics", sessionId, index);
    if (stair) input(form, "steps", "台阶数（级）", item.stairSteps == null ? "" : item.stairSteps.toString(), 2);
    else {
      input(form, "incline", "坡度（%）", item.inclinePercent == null ? "" : item.inclinePercent.toString(), 8194);
      input(form, "kilometers", "距离（公里）", item.manualMeters == null ? "" : String.valueOf(item.manualMeters / 1000), 8194);
      form.addView(note("坡度填写百分比；设备仅显示档位时请留空。"));
    }
    showForm("填写本次数据", form, () -> {
      String first = val(stair ? "steps" : "incline");
      Integer steps = stair && !first.isEmpty() ? Domain.integer(first, 0, Integer.MAX_VALUE, "台阶数") : null;
      Double incline = !stair && !first.isEmpty() ? Domain.number(first, "坡度") : null;
      String distanceText = stair ? "" : val("kilometers");
      Double meters = distanceText.isEmpty() ? null : Domain.number(distanceText, "距离") * 1000;
      Domain.require(incline == null || Double.isFinite(incline) && incline >= 0, "请检查坡度");
      Domain.require(meters == null || Double.isFinite(meters) && meters >= 0 && meters <= 2000000, "请检查距离");
      Domain.require(app.active != null && app.active.id.equals(sessionId), "训练状态已变化");
      app.mutate(q -> {
        Domain.require(index >= 0 && index < q.items.size() && q.items.get(index).exercise.id.equals(exerciseId), "运动项目已变化，请重新打开");
        Domain.Item target = q.items.get(index); target.stairSteps = steps; target.inclinePercent = incline; target.manualMeters = meters;
      });
      closeForm(); render();
    });
  }

  private void distanceForm(int index) {
    if (app.active == null || index >= app.active.items.size()) return;
    String sessionId = app.active.id;
    Domain.Item i = app.active.items.get(index);
    LinearLayout form = beginForm("distance", sessionId, index);
    input(
        form, "kilometers", "距离（公里）", String.format(Locale.ROOT, "%.2f", i.meters() / 1000), 8194);
    form.addView(note("填写本项目的累计距离；均速按项目运动时间计算。"));
    showForm(
        "填写距离",
        form,
        () -> {
          double km = Domain.number(val("kilometers"), "距离");
          Domain.require(km >= 0 && km <= 2000, "距离应在0至2000公里之间");
          Domain.require(app.active != null && app.active.id.equals(sessionId), "训练已结束");
          app.mutate(s -> s.items.get(index).manualMeters = km * 1000);
          closeForm();
          render();
        });
  }

  private void trainingLegacy() {
    Domain.Session q = app.active;
    LinearLayout top = row();
    TextView backTraining = link("‹ 返回训练", () -> go(1)); backTraining.setTextSize(17);
    backTraining.setContentDescription("返回训练");
    top.addView(backTraining, new LinearLayout.LayoutParams(0, -2, 1));
    TextView finish = link("结束", this::finishDialog); finish.setTextSize(17);
    finish.setTextColor(dark ? 0xFFFF817C : 0xFFC73232);
    finish.setMinWidth(d(48));
    top.addView(finish, new LinearLayout.LayoutParams(-2, -2));
    body.addView(top);
    TextView title = text("本次训练", 34, ink, true); title.setPadding(0, d(8), 0, d(16)); body.addView(title);
    LinearLayout ride = panel();
    sessionState = text(q.running ? "训练进行中" : "训练已暂停", 17, orange(), true);
    ride.addView(sessionState);
    ride.addView(text("已训练时长", 15, muted, false));
    clockText = text(Domain.clock(q.elapsed), 38, ink, true);
    clockText.setFontFeatureSettings("tnum");
    clockText.setPadding(0, d(16), 0, d(8));
    ride.addView(clockText);
    action(
        ride,
        q.running ? "暂停运动" : "继续运动",
        () -> {
          app.toggleTrainingRunning(q.id);
          startService();
          render();
        },
        false);
    body.addView(ride);
    distanceCard(q, 0);
    LinearLayout heart = panel(), r = row();
    heartText =
        text(
            q.liveHeart(SystemClock.elapsedRealtime()) == 0 ? "— 次/分钟" : q.heart + " 次/分钟",
            16,
            ink,
            true);
    r.addView(heartText, new LinearLayout.LayoutParams(0, -2, 1));
    r.addView(link("连接心率设备", () -> go(6)));
    heart.addView(r);
    body.addView(heart);
    action(body, "添加其他运动", () -> exerciseForm(-2), false);
  }

  private void finishDialog() {
    if (app.active != null) showFinishDialog(app.active.running, false);
  }

  private void showFinishDialog(boolean resume, boolean restoring) {
    if (finishConfirmation != null && finishConfirmation.isShowing()) return;
    Domain.Session current = app.active;
    if (current == null) return;
    finishResume = resume;
    finishSession = current.id;
    String id = current.id;
    if (!restoring) {
      app.mutate(q -> q.pause(SystemClock.elapsedRealtime()));
      finishStateEpoch = app.trainingStateEpoch;
      finishStateRevision = app.trainingStateRevision;
      finishSafetyRevision = app.trainingSafetyRevision;
    }
    final String stateEpoch = finishStateEpoch;
    final long stateRevision = finishStateRevision;
    final long safetyRevision = finishSafetyRevision;
    final boolean[] explicitReturn = {false};
    LinearLayout stats = row();
    stats.setPadding(d(16), d(16), d(16), d(16));
    stats.setBackground(AppViews.round(HealthDesign.well(dark), d(HealthDesign.INPUT)));
    LinearLayout duration = column(), count = column();
    duration.addView(text(Domain.clock(app.active.elapsed), 24, ink, true));
    duration.addView(note("运动时长"));
    boolean grouped = current.target() > 0;
    long recordedItems = current.items.stream().filter(i -> i.durationMs() > 0 || !i.logs.isEmpty() || i.manualMeters != null || i.stairSteps != null).count();
    count.addView(text(grouped ? current.count() + " / " + current.target() : String.valueOf(recordedItems), 24, ink, true));
    count.addView(note(grouped ? "完成组数" : "已记录项目"));
    stats.addView(duration, new LinearLayout.LayoutParams(0, -2, 1));
    if (!current.items.isEmpty()) stats.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout finishContent = column();
    finishStateText = note(app.active.running ? "训练进行中" : "训练已暂停");
    finishContent.addView(finishStateText);
    finishContent.addView(stats);
    for (Domain.Item item : current.items) if (item.exercise.distanceBased()) {
      String actual;
      if (item.exercise.id.equals("stair_climb"))
        actual = item.stairSteps == null ? "台阶数未记录" : "台阶 " + item.stairSteps + "级";
      else if (item.manualMeters == null && item.gpsMeters <= 0) actual = "尚未填写距离";
      else actual = String.format(Locale.CHINA, "已记录距离 %.2f 公里", item.meters() / 1000)
          + (item.manualMeters != null ? "（手动）" : "（历史GPS）");
      if (item.exercise.id.equals("incline_treadmill"))
        actual += item.inclinePercent == null ? " · 坡度未记录" : " · 坡度 " + item.inclinePercent + "%";
      finishContent.addView(note(item.exercise.name + " · " + actual + " · 项目时长 " + Domain.clock(item.durationMs())));
    }
    String finishMessage = "保存后结束本次训练。";
    if (grouped) finishMessage += "已记录的组会保存；未点击记录的手动输入不会保存。";
    if (current.items.stream().anyMatch(item -> !item.exercise.distanceBased() && item.exercise.timed && item.currentMs >= 1000))
      finishMessage += "已计时满1秒的未完成计时组会作为未完成记录保留。";
    AlertDialog dialog =
        new GlassDialogBuilder()
            .setWholeContentScrollable()
            .setCentered()
            .setIconKind("clock")
            .setTitle("结束本次训练？")
            .setMessage(finishMessage)
            .setView(finishContent)
            .setNegativeButtonFirst()
            .setPositiveButton("保存训练", null)
            .setNegativeButton(resume ? "继续训练" : "返回训练", null)
            .create();
    dialog.setOnShowListener(
        x -> {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        Domain.require(
                            app.active != null && app.active.id.equals(id), "训练状态已变化，请重新查看");
                        selected = app.finish();
                      } catch (Exception error) {
                        toast("保存失败，训练未结束，请重试");
                        return;
                      }
                      inlineValues.clear();
                      stopService(new Intent(this, WorkoutService.class));
                      dialog.dismiss();
                      go(7);
                    });
            dialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        Domain.require(
                            app.active != null && app.active.id.equals(id), "训练状态已变化，请重新查看");
                        if (finishOffersContinue()) {
                          app.setTrainingRunning(id, true);
                          startService();
                        }
                        explicitReturn[0] = true;
                        dialog.dismiss();
                      } catch (Exception error) {
                        toast(error.getMessage() == null ? "操作未完成，请重试" : error.getMessage());
                      }
                    });
            updateFinishDialog();
        });
    dialog.setOnDismissListener(
        x -> {
          dialogs.remove(dialog);
          if (finishConfirmation == dialog) {
            finishConfirmation = null;
            finishStateText = null;
          }
          if (!isChangingConfigurations()
              && !explicitReturn[0]
              && app.active != null
              && app.active.id.equals(id)
              && resume
              && !app.active.running
              && app.trainingStateEpoch.equals(stateEpoch)
              && app.trainingStateRevision == stateRevision
              && app.trainingSafetyRevision == safetyRevision) {
            try {
              app.mutate(q -> q.resume(SystemClock.elapsedRealtime()));
              startService();
            } catch (Exception error) {
              toast(error.getMessage() == null ? "继续训练失败，请重试" : error.getMessage());
            }
          }
          refreshLive();
        });
    finishConfirmation = dialog;
    dialog.show();
  }

  private void updateFinishDialog() {
    if (finishConfirmation == null || !finishConfirmation.isShowing()) return;
    Domain.Session live = app.active;
    if (live == null || !live.id.equals(finishSession)) return;
    if (finishStateText != null)
      finishStateText.setText(live.running ? "训练进行中" : "训练已暂停");
    Button back = finishConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE);
    if (back != null) back.setText(finishOffersContinue() ? "继续训练" : "返回训练");
  }

  private boolean finishOffersContinue() {
    return finishResume
        && app.trainingStateEpoch.equals(finishStateEpoch)
        && app.trainingStateRevision == finishStateRevision;
  }

  private void refreshLive() {
    if (!foreground || app == null) return;
    updateFinishDialog();
    if (seenRevision != app.dataRevision) {
      seenRevision = app.dataRevision;
      render();
    }
    Domain.Session q = app.active;
    if (q != null) {
      for (Runnable update : distanceLive) update.run();
      if (sessionState != null) {
        String state = q.running ? "训练进行中" : "训练已暂停";
        if (!state.contentEquals(sessionState.getText())) sessionState.setText(state);
      }
      if (pauseControl != null) {
        String label = (q.running ? "暂停" : "继续") + pauseControlSuffix;
        if (!label.contentEquals(pauseControl.getText())) {
          pauseControl.setText(label);
          pauseControl.setContentDescription(label);
        }
      }
      if (clockText != null) clockText.setText(Domain.clock(q.elapsed));
      if (liveTimedAmount != null
          && !liveTimedAmount.hasFocus()
          && !inlineValues.containsKey(liveTimedKey)) {
        updatingInline = true;
        liveTimedAmount.setText(String.valueOf(q.item().currentMs / 1000));
        updatingInline = false;
      }
      if (roundText != null) roundText.setText("本组 " + Domain.clock(q.item().currentMs));
      if (restText != null) restText.setText(q.rest > 0 ? "休息 " + Domain.clock(q.rest) : "休息结束");
      if (restPanel != null && q.rest == 0) restPanel.setVisibility(View.GONE);
      if (heartText != null) {
        int hr = q.liveHeart(SystemClock.elapsedRealtime());
        heartText.setText(hr == 0 ? "— 次/分钟" : hr + " 次/分钟");
      }
    }
    if (page == 6) updateDevices();
  }

  private LinearLayout knownDevices;
  private TextView knownHeading, searchAction, devicePrerequisite, emptyKnownDevice;
  private final Map<String, DeviceEntry> deviceEntries = new LinkedHashMap<>();
  private final Map<String,DeviceEntry> knownDeviceEntries = new LinkedHashMap<>();

  private String pendingDeviceAddress = "";

  private String currentDeviceAddress() {
    String target = app.ble.targetAddress();
    return target.isEmpty() ? db.setting("heart.device.address", "") : target;
  }

  private String deviceLabel(String address) {
    android.bluetooth.BluetoothDevice found = app.ble.devices.get(address);
    if (address.equals(app.ble.targetAddress())) return app.ble.targetName();
    if (found != null) return app.ble.displayName(found);
    return address.equals(db.setting("heart.device.address", ""))
        ? app.ble.rememberedName() : "心率设备";
  }

  private String deviceConnectionLabel(String address) {
    if (!address.equals(currentDeviceAddress())) return "未连接";
    if (app.ble.connected()) {
      int hr = app.ble.freshHeart();
      return hr > 0 ? "已连接\n心率 " + hr + " 次/分" : "已连接，等待心率";
    }
    if (app.ble.connecting()) return app.ble.connectionStatus();
    String state = app.ble.connectionStatus();
    return state.isEmpty() ? "未连接" : state;
  }

  private final class DeviceEntry {
    final LinearLayout view = row();
    final LinearLayout main = column();
    final TextView name = text("", 17, ink, true), state = text("", 14, muted, false);
    final TextView identifier = text("", 14, muted, false);
    final ProgressBar progress = new ProgressBar(MainActivity.this);
    final String address;
    DeviceEntry(String id) {
      address = id;
      view.setPadding(d(16), d(12), d(16), d(12));
      view.setMinimumHeight(d(72));
      ImageView symbol = new ImageView(MainActivity.this);
      symbol.setImageDrawable(new Visuals.Icon("heart", muted));
      symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams symbolLayout = new LinearLayout.LayoutParams(d(24), d(24));
      symbolLayout.setMarginEnd(d(4)); // row already supplies the other 8dp.
      view.addView(symbol, symbolLayout);
      main.setMinimumHeight(touchTarget());
      name.setMaxLines(getResources().getConfiguration().fontScale >= 2 ? 3 : 2);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      main.addView(name);
      identifier.setPadding(0, d(4), 0, 0);
      identifier.setVisibility(View.GONE);
      main.addView(identifier);
      state.setPadding(0, d(4), 0, 0);
      main.addView(state);
      main.setOnClickListener(v -> connectDevice(address));
      GlassEffects.press(main);
      view.addView(main, new LinearLayout.LayoutParams(0, -2, 1));
      view.addView(progress, new LinearLayout.LayoutParams(d(20), d(20)));
      TextView info = headerAction("ⓘ", () -> deviceInformation(address));
      info.setContentDescription("查看设备信息");
      view.addView(info, new LinearLayout.LayoutParams(touchTarget(), touchTarget()));
      update();
    }
    void update() {
      String label = deviceLabel(address), status = deviceConnectionLabel(address);
      boolean duplicate = "心率设备".equals(label);
      Set<String> visibleAddresses = new LinkedHashSet<>(app.ble.devices.keySet());
      String current = currentDeviceAddress(), remembered = db.setting("heart.device.address", "");
      if (!current.isEmpty()) visibleAddresses.add(current);
      if (!remembered.isEmpty()) visibleAddresses.add(remembered);
      for (String other : visibleAddresses)
        if (!other.equals(address) && label.equals(deviceLabel(other))) duplicate = true;
      String identity = duplicate ? address.isEmpty() ? "标识暂不可用"
          : "标识 " + address.substring(Math.max(0, address.length() - 5)) : "";
      if (!label.contentEquals(name.getText())) name.setText(label);
      if (!identity.contentEquals(identifier.getText())) identifier.setText(identity);
      identifier.setVisibility(identity.isEmpty() ? View.GONE : View.VISIBLE);
      String accessibleLabel = label + (identity.isEmpty() ? "" : "，" + identity);
      if (!status.contentEquals(state.getText())) state.setText(status);
      boolean working = address.equals(currentDeviceAddress()) && app.ble.connecting();
      progress.setVisibility(working ? View.VISIBLE : View.GONE);
      main.setClickable(!working && !(address.equals(currentDeviceAddress()) && app.ble.connected()));
      main.setContentDescription(accessibleLabel + "，" + status + (main.isClickable() ? "，连接此设备" : ""));
      view.getChildAt(view.getChildCount() - 1).setContentDescription("查看" + accessibleLabel + "的设备信息");
    }
  }

  private void connectDevice(String address) {
    if (app.ble.connecting() || app.ble.connected() && address.equals(app.ble.targetAddress())) return;
    if (!app.ble.permitted()) { pendingDeviceAddress = address; requestDevicePermission(); return; }
    guard(() -> {
      android.bluetooth.BluetoothDevice found = app.ble.devices.get(address);
      if (found != null) app.ble.connect(found);
      else if (address.equals(db.setting("heart.device.address", ""))) app.ble.reconnectRemembered();
    });
  }

  private boolean devicePermissionBlocked() {
    return db.setting("heart.permission.requested", "false").equals("true")
        && !app.ble.permitted()
        && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT);
  }

  private void requestDevicePermission() {
    if (devicePermissionBlocked()) {
      pendingDeviceAddress = "";
      guard(() -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          android.net.Uri.parse("package:" + getPackageName()))));
      return;
    }
    db.setSetting("heart.permission.requested", "true");
    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, pendingDeviceAddress.isEmpty() ? 101 : 106);
  }

  private void searchDevices() {
    pendingDeviceAddress = "";
    if (!app.ble.permitted()) { requestDevicePermission(); return; }
    if (!app.ble.scanning()) guard(app.ble::scan);
  }

  private void deviceInformation(String address) {
    LinearLayout details = column();
    details.addView(text(deviceLabel(address), 20, ink, true));
    details.addView(note("标识 " + address));
    details.addView(note(deviceConnectionLabel(address)));
    TextView feedback = note(""); feedback.setVisibility(View.GONE); details.addView(feedback);
    Runnable stale = () -> { feedback.setText("设备状态已变化，请重新打开设备信息"); feedback.setVisibility(View.VISIBLE); };
    boolean remembered = address.equals(db.setting("heart.device.address", ""));
    DesignDialog.Builder builder = new GlassDialogBuilder().setAdaptiveDetail().setVerticalActions(16)
        .setTitle("设备信息").setView(details).setNegativeButton("关闭", null);
    boolean active = address.equals(currentDeviceAddress()) && (app.ble.connected() || app.ble.connecting());
    boolean wasConnecting = app.ble.connecting();
    if (remembered) details.addView(note("本应用记住此设备，便于下次连接。"));
    if (active) {
      details.addView(note("断开后将停止接收心率，训练计时继续。"));
      builder.setPositiveButton(wasConnecting ? "取消连接" : "断开连接", null);
    }
    boolean different = remembered && !app.ble.targetAddress().isEmpty()
        && !address.equals(app.ble.targetAddress()) && (app.ble.connecting() || app.ble.connected());
    if (remembered) {
      if (different) {
        TextView explanation = note("正在连接或使用其他设备，暂不能忘记此设备");
        explanation.setTextSize(15);
        details.addView(explanation);
      }
      builder.setNeutralButton("忘记设备", null);
    }
    AlertDialog shown = builder.show();
    if (active) shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (!address.equals(app.ble.targetAddress()) || wasConnecting != app.ble.connecting()
          || !(app.ble.connected() || app.ble.connecting())) { stale.run(); return; }
      app.ble.close(); app.changed(); shown.dismiss();
    });
    if (remembered) {
      shown.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!different);
      if (different) shown.getButton(AlertDialog.BUTTON_NEUTRAL)
          .setTextColor(HealthDesign.muted(dark));
      shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
        if (!canForgetDevice(address)) { stale.run(); return; }
        confirm("info", "忘记这台心率设备？",
            "将断开当前连接并移除本应用记住的设备，下次连接需重新搜索。已保存的训练和心率记录会保留。",
            () -> { if (!canForgetDevice(address)) { stale.run(); return; }
              app.ble.forget(); shown.dismiss(); updateDevices(); });
      });
    }
  }

  private boolean canForgetDevice(String address) {
    return address.equals(db.setting("heart.device.address", ""))
        && (!(app.ble.connected() || app.ble.connecting()) || address.equals(app.ble.targetAddress()));
  }

  private void updateDevices() {
    if (deviceRows == null || knownDevices == null || searchAction == null) return;
    android.bluetooth.BluetoothManager manager = getSystemService(android.bluetooth.BluetoothManager.class);
    android.bluetooth.BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    boolean permitted = app.ble.permitted(), enabled = false;
    try { enabled = adapter != null && permitted && adapter.isEnabled(); } catch (SecurityException ignored) {}
    String prerequisite = adapter == null ? "此设备不支持蓝牙心率连接"
        : !permitted ? "需要附近设备权限，用于搜索和连接心率设备。"
        : !enabled ? "手机蓝牙未开启，开启蓝牙后再搜索心率设备。" : "";
    devicePrerequisite.setText(prerequisite);
    devicePrerequisite.setVisibility(prerequisite.isEmpty() ? View.GONE : View.VISIBLE);
    searchAction.setText(adapter == null ? "暂不支持连接" : !permitted
        ? devicePermissionBlocked() ? "前往应用设置" : "允许附近设备权限"
        : !enabled ? "前往蓝牙设置" : app.ble.scanning() ? "正在搜索…"
        : app.ble.searchStatus().equals("尚未搜索设备") ? "搜索设备" : "重新搜索");
    searchAction.setEnabled(adapter != null && !app.ble.scanning());
    searchAction.setOnClickListener(v -> {
      if (!app.ble.permitted()) requestDevicePermission();
      else {
        try {
          if (adapter != null && !adapter.isEnabled()) {
            guard(() -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))); return;
          }
        } catch (SecurityException ignored) { requestDevicePermission(); return; }
        searchDevices();
      }
    });
    String current = currentDeviceAddress();
    Set<String> known = new LinkedHashSet<>();
    if (!current.isEmpty()) known.add(current);
    String remembered = db.setting("heart.device.address", "");
    if (!remembered.isEmpty()) known.add(remembered);
    emptyKnownDevice.setVisibility(known.isEmpty() ? View.VISIBLE : View.GONE);
    knownHeading.setText("我的设备");
    for (String id : new ArrayList<>(knownDeviceEntries.keySet())) if (!known.contains(id)) {
      knownDevices.removeView(knownDeviceEntries.remove(id).view);
    }
    for (String id : known) {
      DeviceEntry entry = knownDeviceEntries.get(id);
      if (entry == null) { entry = new DeviceEntry(id); knownDeviceEntries.put(id,entry); knownDevices.addView(entry.view); }
      entry.update();
    }
    Set<String> wanted = new LinkedHashSet<>(app.ble.devices.keySet()); wanted.removeAll(known);
    for (String id : new ArrayList<>(deviceEntries.keySet())) if (!wanted.contains(id)) {
      deviceRows.removeView(deviceEntries.remove(id).view);
    }
    for (String id : wanted) {
      DeviceEntry entry = deviceEntries.get(id);
      if (entry == null) { entry = new DeviceEntry(id); deviceEntries.put(id, entry); deviceRows.addView(entry.view); }
      entry.update();
    }
    String search = app.ble.scanning() ? "正在查找心率设备…" : app.ble.searchStatus();
    if (search.equals("正在搜索")) search = "搜索已结束";
    if (!search.contentEquals(deviceStatus.getText())) deviceStatus.setText(search);
    connectionProgress.setVisibility(app.ble.scanning() ? View.VISIBLE : View.GONE);
  }

  private void devices() {
    body.setPadding(d(16), d(8), d(16), d(112));
    title("心率设备");
    LinearLayout intro = panel();
    intro.setPadding(d(16), d(16), d(16), d(16));
    intro.setBackground(AppViews.round(card, d(24)));
    LinearLayout heading = row();
    heading.setGravity(Gravity.TOP);
    ImageView bluetooth = new ImageView(this);
    bluetooth.setImageDrawable(new Visuals.Icon("bluetooth", Color.WHITE));
    bluetooth.setPadding(d(6),d(6),d(6),d(6));
    bluetooth.setBackground(AppViews.round(accent,d(8)));
    bluetooth.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams bluetoothLayout = new LinearLayout.LayoutParams(d(32),d(32));
    bluetoothLayout.setMarginEnd(d(4));
    heading.addView(bluetooth, bluetoothLayout);
    heading.addView(text("连接心率设备", 20, ink, true),new LinearLayout.LayoutParams(0,-2,1));
    intro.addView(heading);
    TextView introHint = text("开启设备心率广播后，靠近手机搜索。", 15, muted, false);
    introHint.setPadding(0, d(12), 0, 0);
    intro.addView(introHint);
    body.addView(intro);
    devicePrerequisite = note(""); body.addView(devicePrerequisite);
    knownHeading = text("我的设备", 16, ink, true); knownHeading.setPadding(0,d(24),0,d(8));
    body.addView(knownHeading); knownDevices = deviceGroup(); knownDeviceEntries.clear(); body.addView(knownDevices);
    emptyKnownDevice = text("尚未连接设备", 16, muted, false);
    emptyKnownDevice.setPadding(d(16), d(16), d(16), d(16));
    emptyKnownDevice.setMinimumHeight(d(72));
    emptyKnownDevice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    knownDevices.addView(emptyKnownDevice);
    TextView nearby = text("附近设备", 16, ink, true);
    searchAction = link("搜索设备", this::searchDevices);
    searchAction.setTextSize(17); searchAction.setMinHeight(touchTarget());
    searchAction.setMinimumWidth(touchTarget());
    DeviceSearchHeading nearbyHeading = new DeviceSearchHeading(nearby, searchAction);
    nearbyHeading.setPadding(0, d(16), 0, d(8));
    body.addView(nearbyHeading, new LinearLayout.LayoutParams(-1, -2));
    connectionProgress = new ProgressBar(this); body.addView(connectionProgress, new LinearLayout.LayoutParams(d(20),d(20)));
    deviceStatus = note("尚未搜索设备"); deviceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    body.addView(deviceStatus);
    deviceRows = deviceGroup(); deviceEntries.clear(); body.addView(deviceRows);
    body.addView(note("确认设备已开启心率广播，并保持靠近手机后重试。"));
    updateDevices();
  }

  /** Measures the actual title and action; neither font scale nor device size is a breakpoint. */
  private final class DeviceSearchHeading extends android.view.ViewGroup {
    private final View title, action;
    private boolean stacked;
    DeviceSearchHeading(View title, View action) {
      super(MainActivity.this);
      this.title = title; this.action = action;
      addView(title); addView(action);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int available = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
      int childWidth = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
      int naturalHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      title.measure(childWidth, naturalHeight);
      action.measure(childWidth, naturalHeight);
      stacked = title.getMeasuredWidth() + d(12) + action.getMeasuredWidth() > available;
      int contentHeight = stacked ? title.getMeasuredHeight() + d(8) + action.getMeasuredHeight()
          : Math.max(title.getMeasuredHeight(), action.getMeasuredHeight());
      setMeasuredDimension(resolveSize(available + getPaddingLeft() + getPaddingRight(), widthSpec),
          resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int top = getPaddingTop(), right = getWidth() - getPaddingRight();
      int rowHeight = Math.max(title.getMeasuredHeight(), action.getMeasuredHeight());
      int titleTop = top + (stacked ? 0 : (rowHeight - title.getMeasuredHeight()) / 2);
      title.layout(getPaddingLeft(), titleTop, getPaddingLeft() + title.getMeasuredWidth(), titleTop + title.getMeasuredHeight());
      int actionTop = stacked ? top + title.getMeasuredHeight() + d(8)
          : top + (rowHeight - action.getMeasuredHeight()) / 2;
      int actionLeft = stacked ? getPaddingLeft() : right - action.getMeasuredWidth();
      action.layout(actionLeft, actionTop, actionLeft + action.getMeasuredWidth(), actionTop + action.getMeasuredHeight());
    }
  }

  private LinearLayout deviceGroup() {
    LinearLayout group = column();
    group.setBackground(AppViews.round(card, d(24)));
    group.setClipToOutline(true);
    GradientDrawable divider = new GradientDrawable();
    divider.setColor(line); divider.setSize(1,1);
    group.setDividerDrawable(divider); group.setDividerPadding(d(16));
    group.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    return group;
  }

  private List<Domain.Exercise> preparation() {
    List<Domain.Exercise> result = new ArrayList<>();
    try {
      JSONArray data = new JSONArray(db.setting("workoutPreparation", "[]"));
      for (int i = 0; i < Math.min(30, data.length()); i++)
        result.add(Store.exercise(data.getJSONObject(i)));
    } catch (JSONException e) {
      throw new IllegalStateException("待开始的运动读取失败", e);
    }
    return result;
  }

  private void savePreparation(List<Domain.Exercise> exercises) {
    try {
      JSONArray data = new JSONArray();
      for (Domain.Exercise exercise : exercises) data.put(Store.exercise(exercise));
      db.setSetting("workoutPreparation", data.toString());
    } catch (JSONException e) {
      throw new IllegalStateException("运动未保存", e);
    }
  }

  private boolean samePreparation(List<Domain.Exercise> a, List<Domain.Exercise> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      Domain.Exercise left = a.get(i), right = b.get(i);
      if (!Objects.equals(left.id, right.id)
          || !Objects.equals(left.name, right.name)
          || !Objects.equals(left.gear, right.gear)
          || !Objects.equals(left.unit, right.unit)
          || left.timed != right.timed
          || left.sets != right.sets
          || left.reps != right.reps
          || left.seconds != right.seconds
          || left.restSeconds != right.restSeconds
          || !Objects.equals(left.goalSeconds, right.goalSeconds)
          || left.weight != right.weight) return false;
    }
    return true;
  }

  private void prepareFromPlan() {
    Domain.require(app.active == null, "已有进行中的训练");
    List<Domain.Exercise> plan = db.plan(scene);
    List<Domain.Exercise> draft = preparation();
    if (plan.isEmpty() || samePreparation(draft, plan)) {
      go(19);
      return;
    }
    if (draft.isEmpty()) {
      savePreparation(plan);
      go(19);
      return;
    }
    AlertDialog choice =
        new GlassDialogBuilder()
            .setWholeContentScrollable()
            .setIconKind("plan")
            .setTitle("使用计划替换待开始运动？")
            .setMessage("已有待开始的运动。使用计划会替换这些项目和设置；现在不会开始计时。")
            .setNegativeButton("取消", null)
            .setNeutralButton("使用计划替换", null)
            .setPositiveButton("保留待开始运动", null)
            .create();
    choice.setOnShowListener(
        ignored -> {
          choice
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    if (app.active != null) {
                      toast("已有进行中的训练");
                      return;
                    }
                    choice.dismiss();
                    go(19);
                  });
          choice
              .getButton(AlertDialog.BUTTON_NEUTRAL)
              .setOnClickListener(
                  v -> {
                    try {
                      Domain.require(app.active == null, "已有进行中的训练");
                      Domain.require(
                          samePreparation(preparation(), draft) && samePreparation(db.plan(scene), plan),
                          "计划或待开始运动已变化，请重新查看");
                      savePreparation(plan);
                      choice.dismiss();
                      go(19);
                    } catch (IllegalArgumentException error) {
                      toast(error.getMessage());
                    } catch (Exception error) {
                      toast("替换失败，待开始运动已保留");
                    }
                  });
          choice.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(dark ? 0xFFFF817C : 0xFFC73232);
          ((DesignDialog) choice).focusSafeAction(choice.getButton(AlertDialog.BUTTON_POSITIVE));
        });
    choice.show();
  }

  private void preparationScreen() {
    title("准备训练");
    if (app.active != null) {
      body.addView(note("已有进行中的训练"));
      action(body, "返回训练", () -> go(3), true);
      return;
    }
    body.addView(
        listRow(
            "心率设备",
            app.ble.connected() ? app.ble.status : "可在开始前连接",
            "heart",
            orange(),
            () -> go(6)));
    sectionAction("本次运动", "添加运动", () -> openExercisePicker(-3, null));
    List<Domain.Exercise> selectedExercises = preparation();
    if (selectedExercises.isEmpty()) body.addView(note("还没有选择具体项目，仅记录时长也可以开始。"));
    for (Domain.Exercise exercise : selectedExercises) {
      LinearLayout entry = panel(), r = row(), words = column();
      r.addView(GuideImages.exercise(this, exercise), new LinearLayout.LayoutParams(d(70), d(70)));
      words.addView(text(exercise.name, 17, ink, true));
      words.addView(note(desc(exercise)));
      r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
      entry.addView(r);
      LinearLayout controls = row();
      int draftIndex = selectedExercises.indexOf(exercise);
      if (!exercise.distanceBased() || exercise.id.equals("incline_treadmill") || exercise.id.equals("stair_climb")) {
        TextView adjust = link("调整", () -> {
          if (exercise.id.equals("incline_treadmill") || exercise.id.equals("stair_climb")) continuousGoalForm(draftIndex, true);
          else exerciseEditor(-100 - draftIndex, false);
        });
        adjust.setContentDescription("调整待开始的" + exercise.name);
        controls.addView(adjust, new LinearLayout.LayoutParams(0, -2, 1));
      }
      TextView remove =
          link(
              "移除",
              () -> {
                List<Domain.Exercise> draft = preparation();
                draft.removeIf(e -> ExerciseChoices.same(e, exercise));
                savePreparation(draft);
                render();
              });
      remove.setContentDescription("移除" + exercise.name);
      controls.addView(remove, new LinearLayout.LayoutParams(0, -2, 1));
      entry.addView(controls);
      body.addView(entry);
    }
    ContentSizedDock dock = new ContentSizedDock();
    preparationFooter = dock;
    preparationFooter.setBackground(AppViews.round(card, d(24)));
    preparationFooter.setClipToOutline(true);
    boolean solid = db.setting("solid", "false").equals("true");
    LiveBackdropView dockBackdrop = new LiveBackdropView(this, scroll, dark, solid, d(24));
    preparationFooter.addView(dockBackdrop, new FrameLayout.LayoutParams(-1, -1));
    View veil = new View(this);
    GradientDrawable veilShape = AppViews.round(solid ? card : dark ? 0xE0221E2B : 0xD1FFFDFF, d(24));
    veilShape.setStroke(d(1), dark ? 0x707D778A : 0xA0FFFFFF);
    veil.setBackground(veilShape);
    veil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    preparationFooter.addView(veil, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout footerRow = row(), footerWords = column();
    footerRow.setPadding(d(16), d(14), d(16), d(14));
    footerWords.addView(text("本次训练", 17, ink, true));
    footerWords.addView(
        text(
            selectedExercises.isEmpty() ? "仅计时" : selectedExercises.size() + " 项运动",
            13,
            dark ? 0xFFBCB6C5 : 0xFF50505A,
            false));
    footerRow.addView(footerWords, new LinearLayout.LayoutParams(0, -2, 1));
    TextView start =
        button(
            "开始计时",
            () -> {
              Domain.require(app.active == null, "已有进行中的训练");
              app.start("all", preparation(), "综合");
              try {
                savePreparation(List.of());
              } catch (Exception error) {
                toast("训练已开始，待开始运动未清空。");
              } finally {
                try {
                  startService();
                } finally {
                  go(3);
                }
              }
            },
            true);
    start.setMinWidth(d(132));
    footerRow.addView(start, new LinearLayout.LayoutParams(-2, -2));
    dock.addContent(footerRow);
    FrameLayout.LayoutParams footer = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    footer.setMargins(d(15), 0, d(15), d(12));
    root.addView(preparationFooter, footer);
    LinearLayout footerBody = body;
    preparationFooter.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int bottomPadding = bottom - top + d(28);
      if (footerBody.getPaddingBottom() != bottomPadding)
        footerBody.setPadding(d(20), d(8), d(20), bottomPadding);
    });
    body.setPadding(d(20), d(8), d(20), d(128));
  }

  private Double energyDelta(EnergyReading reading, FoodDay food) {
    return food.complete()
            && food.energy().unknown() == 0
            && food.energy().intake() != null
            && reading.ledger().fullDeviceTotal()
            && reading.snapshot().at != null
            && reading.sameDay()
            && Duration.between(reading.snapshot().at, reading.now()).toMinutes() < 5
        ? reading.ledger().kcal() - food.energy().intake()
        : null;
  }

  private String energyDifference(Double delta) {
    if (delta == null) return "能量差 — · 饮食或设备记录尚不完整";
    return String.format(
        Locale.CHINA, "%s %.0f 千卡 · 截至目前", delta >= 0 ? "消耗多于摄入" : "摄入多于消耗", Math.abs(delta));
  }

  private void saveEnergyDay(EnergyReading reading, FoodDay food, Double delta) {
    // A previously saved day must also be refreshed when its last meal is removed.
    // Otherwise the empty live page and persisted daily record disagree.
    String key = "energy.day." + food.date();
    String saved = db.setting(key, "");
    if (reading.ledger().kcal() == null
        && reading.snapshot().steps == null
        && food.meals() == 0
        && saved.isEmpty()) return;
    try {
      JSONObject day =
          new JSONObject()
              .put("date", food.date().toString())
              .put("updated", reading.now().toEpochMilli() / 60000 * 60000)
              .put(
                  "expenditureUpdated",
                  ((reading.deviceTotal() != null || reading.deviceActive() != null)
                                  && reading.snapshot().at != null
                              ? reading.snapshot().at
                              : reading.now())
                          .toEpochMilli()
                      / 60000
                      * 60000)
              .put(
                  "stepsUpdated",
                  reading.sameDay()
                          && reading.snapshot().at != null
                          && reading.snapshot().steps != null
                      ? reading.snapshot().at.toEpochMilli() / 60000 * 60000
                      : JSONObject.NULL)
              .put("intake", food.energy().intake() == null ? JSONObject.NULL : food.energy().intake())
              .put(
                  "expenditure",
                  reading.ledger().kcal() == null
                      ? JSONObject.NULL
                      : Math.round(reading.ledger().kcal()))
              .put("source", reading.ledger().source())
              .put("deviceTotal", reading.ledger().fullDeviceTotal())
              .put("foodComplete", food.complete())
              .put("unknownFoodCalories", food.energy().unknown())
              .put("foodCompleteAt", food.completeAt().isEmpty() ? JSONObject.NULL : food.completeAt())
              .put("difference", delta == null ? JSONObject.NULL : Math.round(delta))
              .put(
                  "steps",
                  reading.sameDay()
                          && healthData.permitted(this, HealthData.STEPS)
                          && reading.snapshot().steps != null
                      ? reading.snapshot().steps
                      : JSONObject.NULL);
      String value = day.toString();
      if (!value.equals(saved)) db.setSetting(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException("每日能量记录保存失败", e);
    }
  }

  private final class MetricRow extends android.view.ViewGroup {
    private boolean stacked;
    private final int gap;
    MetricRow() { this(16); }
    MetricRow(int gapDp) { super(MainActivity.this); gap = d(gapDp); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec), available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
      int count = getChildCount(), largest = 0;
      for (int i = 0; i < count; i++) {
        View child = getChildAt(i);
        child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        largest = Math.max(largest, child.getMeasuredWidth());
      }
      stacked = count > 0 && largest * count + gap * (count - 1) > available;
      int childWidth = stacked || count == 0 ? available : Math.max(0, (available - gap * (count - 1)) / count);
      int contentHeight = 0;
      for (int i = 0; i < count; i++) {
        View child = getChildAt(i);
        child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        contentHeight = stacked ? contentHeight + child.getMeasuredHeight() + (i > 0 ? gap : 0) : Math.max(contentHeight, child.getMeasuredHeight());
      }
      setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      int x = getPaddingLeft(), y = getPaddingTop();
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i); child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        if (stacked) y += child.getMeasuredHeight() + gap; else x += child.getMeasuredWidth() + gap;
      }
    }
  }

  private LinearLayout energyDayCard(JSONObject day) {
    LinearLayout c = panel();
    MetricRow values = new MetricRow();
    String date = day.optString("date");
    LinearLayout heading = row();
    heading.addView(
        text(date.equals(LocalDate.now().toString()) ? "今天" : date, 17, ink, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    TextView info =
        link(
            "ⓘ",
            () ->
                new GlassDialogBuilder()
                    .setTitle("记录说明")
                    .setMessage(day.optString("source", "数据来源未记录") + "。数值截至该条记录的更新时间；未连接的数据不记为零。")
                    .setNegativeButton("关闭", null)
                    .show());
    info.setContentDescription(date + "记录说明");
    heading.addView(info, new LinearLayout.LayoutParams(d(48), d(48)));
    c.addView(heading);
    values.setPadding(0, d(16), 0, 0);
    String[] fields = {"intake", "expenditure", "steps"}, titles = {"摄入", "消耗", "步数"};
    for (int i = 0; i < fields.length; i++) {
      LinearLayout item = column();
      TextView label = text(titles[i], 15, muted, false); item.addView(label);
      TextView value = text(day.isNull(fields[i]) || !day.has(fields[i]) ? "—" : String.format(Locale.CHINA, "%,.0f", day.optDouble(fields[i])), 24, ink, true);
      value.setPadding(0, d(8), 0, 0); item.addView(value);
      TextView unit = text(i == 2 ? "步" : "千卡", 15, muted, false); unit.setPadding(0, d(4), 0, 0); item.addView(unit);
      values.addView(item, new android.view.ViewGroup.LayoutParams(-2, -2));
    }
    c.addView(values);
    c.addView(
        note(
            "记录于 "
                + DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(day.optLong("updated")))
                + (day.isNull("expenditure")
                    ? " · 消耗暂无数据"
                    : day.optBoolean("deviceTotal") ? " · 设备消耗" : " · 消耗估算")));
    if (!day.optBoolean("foodComplete")) c.addView(text("截至记录时饮食完整性未确认", 12, muted, false));
    else c.addView(text(day.has("foodCompleteAt") && !day.isNull("foodCompleteAt") ? "截至" + day.optString("foodCompleteAt") + "已确认记全" : "截至记录时已确认记全", 12, muted, false));
    if (day.has("unknownFoodCalories") && day.optInt("unknownFoodCalories") > 0)
      c.addView(note(day.optInt("unknownFoodCalories") + "项食物热量未知"));
    if (!day.isNull("difference") && day.has("difference"))
      c.addView(note(String.format(Locale.CHINA, "能量差 %.0f 千卡", day.optDouble("difference"))));
    return c;
  }

  private String preparationKind() {
    String kind = db.setting("workoutPreparation.kind", "综合");
    return List.of("综合", "力量", "有氧", "核心", "拉伸").contains(kind) ? kind : "综合";
  }

  private final class AdaptiveSegments extends LinearLayout {
    private final int count;

    AdaptiveSegments(int count) {
      super(MainActivity.this);
      this.count = count;
      setOrientation(getResources().getConfiguration().fontScale > 1.3f ? VERTICAL : HORIZONTAL);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      boolean constrained = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
      int contentWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
      boolean stacked = getResources().getConfiguration().fontScale > 1.3f
          || (constrained && contentWidth < count * touchTarget());
      int orientation = stacked ? VERTICAL : HORIZONTAL;
      if (getOrientation() != orientation) {
        setOrientation(orientation);
        for (int i = 0; i < getChildCount(); i++) {
          View child = getChildAt(i);
          LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
          lp.width = stacked ? LayoutParams.MATCH_PARENT : 0;
          lp.height = stacked ? LayoutParams.WRAP_CONTENT : touchTarget();
          lp.weight = stacked ? 0 : 1;
          child.setLayoutParams(lp);
        }
      }
      super.onMeasure(widthSpec, heightSpec);
    }
  }

  private LinearLayout segments(
      String[] labels, int selected, java.util.function.IntConsumer choose, String description) {
    LinearLayout group = new AdaptiveSegments(labels.length);
    boolean large = getResources().getConfiguration().fontScale > 1.3f;
    group.setPadding(d(4), d(4), d(4), d(4));
    group.setBackground(AppViews.round(soft, d(16)));
    group.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, oright, ob) -> {
      if (r - l != oright - ol || b - t != ob - ot)
        v.setBackground(AppViews.round(soft, Math.min(d(16), Math.min(r - l, b - t) / 2f)));
    });
    LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, -2);
    box.topMargin = d(12);
    box.bottomMargin = d(8);
    group.setLayoutParams(box);
    group.setContentDescription(description);
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      TextView choice =
          button(
              labels[i],
              () -> {
                if (selected != index) choose.accept(index);
              },
              false);
      choice.setTextColor(i == selected ? ink : muted);
      choice.setTypeface(null, i == selected ? Typeface.BOLD : Typeface.NORMAL);
      choice.setMinWidth(touchTarget());
      choice.setPadding(d(4), d(8), d(4), d(8));
      choice.setTextSize(14);
      choice.setSelected(i == selected);
      choice.setContentDescription(description + "：" + labels[i]);
      choice.setBackground(
          AppViews.button(
              i == selected ? card : Color.TRANSPARENT, accent, d(12)));
      final int choiceColor = i == selected ? card : Color.TRANSPARENT;
      choice.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, oright, ob) -> {
        if (r - l != oright - ol || b - t != ob - ot)
          v.setBackground(AppViews.button(choiceColor, accent, Math.min(d(12), Math.min(r - l, b - t) / 2f)));
      });
      group.addView(
          choice,
          large
              ? new LinearLayout.LayoutParams(-1, -2)
              : new LinearLayout.LayoutParams(0, d(48), 1));
    }
    return group;
  }

  private void energyHistory() {
    LinearLayout content = column();
    List<JSONObject> days = db.energyDays();
    if (days.isEmpty()) content.addView(note("还没有每日能量记录"));
    for (JSONObject day : days) {
      content.addView(energyDayCard(day));
    }
    new GlassDialogBuilder()
        .setTitle("每日能量记录")
        .setMessage("最近30条记录 · 各项数值截至记录时间")
        .setView(content)
        .setNegativeButton("关闭", null)
        .show();
  }

  private LinearLayout healthAccessRow(String label, String subtitle, String icon, int tint, Runnable action) {
    LinearLayout r = listRow(label, subtitle, icon, tint, action);
    r.setPadding(0, d(14), 0, d(14));
    LinearLayout words = (LinearLayout) r.getChildAt(1);
    ((TextView) words.getChildAt(0)).setTextSize(17);
    if (words.getChildCount() > 1) ((TextView) words.getChildAt(1)).setTextSize(15);
    return r;
  }

  private void healthAccessSheet() {
    LinearLayout content = column();
    TextView description = text("连接后读取步数和热量汇总，用于每日能量统计。不会写入或修改其他应用的健康数据。", 15, muted, false);
    description.setPadding(0, d(12), 0, d(12)); content.addView(description);
    content.addView(healthAccessRow("连接健康数据", "选择允许读取的数据", "activity", teal(), () -> healthData.request(this)));
    content.addView(healthAccessRow("管理数据权限", "随时修改或撤回授权", "settings", accent, () -> healthData.manage(this)));
    content.addView(healthAccessRow("数据使用说明", "查看数据用途与保存方式", "activity", muted,
        () -> startActivity(new Intent(this, HealthPrivacyActivity.class))));
    new GlassDialogBuilder().setTitle("数据来源与权限").setView(content).setNegativeButton("关闭", null).show();
  }

  private void freeTraining() {
    if (app.active == null) openExercisePicker(-3, null); else go(3);
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 104) {
      refreshHealth();
      return;
    }
    if (r == 106) {
      String target = pendingDeviceAddress; pendingDeviceAddress = "";
      if (app.ble.permitted() && page == 6 && !target.isEmpty()) connectDevice(target);
      if (page == 6) updateDevices();
      return;
    }
    if (r == 101 || r == 105) {
      if (app.ble.permitted()) {
        try {
          if (r == 105) app.ble.reconnectRemembered();
          else app.ble.scan();
        } catch (Exception e) {
          toast(e.getMessage());
        }
      } else toast("未获得附近设备权限，可继续手动训练");
    }
  }

  private boolean isRide(Domain.Session q) {
    return q.items.size() == 1 && q.items.get(0).exercise.id.equals("bike");
  }

  private String completion(Domain.Session q) {
    return q.target() == 0
        ? q.items.size() + " 项运动"
        : "完成 " + q.count() + " / " + q.target() + " 组";
  }

  private void history() {
    classificationTitle("活动", "运动时间");
    int total = db.workoutCount();
    historyOffset = total == 0 ? 0 : Math.min(historyOffset, ((total - 1) / 50) * 50);
    List<Domain.Session> list = db.workouts(50, historyOffset);
    LocalDate start = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
    List<Domain.Session> week = db.workoutsBetween(start, start.plusDays(7));
    double[] minutes = new double[7];
    for (Domain.Session q : week) {
      long index = LocalDate.parse(q.date).toEpochDay() - start.toEpochDay();
      if (index >= 0 && index < 7) minutes[(int) index] += q.elapsed / 60000.0;
    }
    LinearLayout overview = panel();
    overview.addView(note(start + " 至 " + start.plusDays(6)));
    overview.addView(metric(String.valueOf(weekMillis(week, false) / 60000), "分钟", 34));
    String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    addBars(overview, names, minutes, orange(), "分钟");
    body.addView(overview);
    section("训练记录");
    if (list.isEmpty()) body.addView(emptyState("activity", "还没有训练记录", "完成训练后，可在这里查看时长和运动详情。"));
    if (total > 0) {
      body.addView(
          note(
              String.format(
                  Locale.CHINA,
                  "第 %d–%d 条 · 共 %d 次训练",
                  historyOffset + 1,
                  historyOffset + list.size(),
                  total)));
      LinearLayout pages = row();
      if (historyOffset > 0)
        pages.addView(
            button(
                "上一页",
                () -> {
                  historyOffset -= 50;
                  scroll.scrollTo(0, 0);
                  render();
                },
                false),
            new LinearLayout.LayoutParams(0, -2, 1));
      if (historyOffset + list.size() < total)
        pages.addView(
            button(
                "下一页",
                () -> {
                  historyOffset += 50;
                  scroll.scrollTo(0, 0);
                  render();
                },
                false),
            new LinearLayout.LayoutParams(0, -2, 1));
      body.addView(pages);
    }
    for (Domain.Session q : list)
      body.addView(
          listRow(
              isRide(q) ? "户外骑行" : sceneName(q.scene) + "训练",
              q.date + " · 时长 " + Domain.clock(q.elapsed) + " · " + completion(q),
              "训练",
              orange(),
              () -> {
                selected = q;
                go(7);
              }));
  }

  private void addBars(LinearLayout into, String[] labels, double[] values, int tint, String unit) {
    double max = 1;
    for (double v : values) max = Math.max(max, v);
    for (int i = 0; i < labels.length; i++) {
      LinearLayout r = row();
      r.setPadding(0, d(8), 0, d(8));
      TextView label = text(labels[i], 12, muted, false);
      r.addView(label, new LinearLayout.LayoutParams(d(40), -2));
      FrameLayout track = new FrameLayout(this);
      track.setBackground(AppViews.round((tint & 0xffffff) | 0x18000000, d(6)));
      final float fraction = (float) (values[i] / max);
      View fill = new View(this);
      fill.setBackground(AppViews.round(tint, d(6)));
      track.addView(fill, new FrameLayout.LayoutParams(0, -1));
      track.addOnLayoutChangeListener(
          (v, l, t, rr, b, ol, ot, or, ob) -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) fill.getLayoutParams();
            int width = Math.round(track.getWidth() * fraction);
            if (p.width != width) {
              p.width = width;
              fill.setLayoutParams(p);
            }
          });
      r.addView(track, new LinearLayout.LayoutParams(0, d(12), 1));
      TextView value =
          text(String.format(Locale.CHINA, "%.0f %s", values[i], unit), 12, ink, false);
      value.setGravity(Gravity.END);
      r.addView(value, new LinearLayout.LayoutParams(d(78), -2));
      into.addView(r);
    }
  }

  private void summary() {
    title("训练记录");
    if (selected == null) {
      selected = db.latestWorkout();
    }
    if (selected == null) {
      body.addView(emptyState("activity", "还没有训练记录", "完成训练后，可在这里查看时长和运动详情。"));
      return;
    }
    Domain.Session q = selected;
    List<RouteTrack.Point> points = db.routePoints(q.id);
    if (!points.isEmpty()) routePanel(q, points);
    LinearLayout c = panel();
    c.addView(note(q.date + " · " + q.trainingKind + "训练"));
    c.addView(note("运动时长"));
    c.addView(text(Domain.clock(q.elapsed), 38, accent, true));
    c.addView(note(completion(q)));
    body.addView(c);
    for (Domain.Item i : q.items) {
      LinearLayout r = panel();
      r.addView(text(i.exercise.name, 18, ink, true));
      boolean continuousRide = i.exercise.distanceBased();
      r.addView(GuideImages.exercise(this, i.exercise), new LinearLayout.LayoutParams(-1, d(130)));
      if (continuousRide && !i.exercise.id.equals("stair_climb") && i.manualMeters == null && i.gpsMeters > 0)
        r.addView(note("历史GPS记录的距离"));
      else if (continuousRide && i.manualMeters != null) r.addView(note("手动记录的距离"));
      if (continuousRide) r.addView(note(i.exercise.id.equals("stair_climb")
          ? i.stairSteps == null ? "台阶数未记录" : "台阶 " + i.stairSteps + "级"
          : distanceDescription(i)));
      if (i.exercise.id.equals("incline_treadmill")) r.addView(note(i.inclinePercent == null ? "坡度未记录" : "坡度 " + i.inclinePercent + "%"));
      if (!continuousRide) r.addView(note("计划 " + desc(i.exercise)));
      if (ExerciseCatalog.perSide(i.exercise))
        r.addView(note(ExerciseCatalog.countNote(i.exercise)));
      for (int j = 0; j < i.logs.size(); j++) {
        Domain.Log log = i.logs.get(j);
        r.addView(
            note(
                (continuousRide ? "运动时长" : log.partial ? "未完成组" : "第" + (j + 1) + "组")
                    + " · "
                    + (i.exercise.timed
                        ? Domain.clock(log.ms)
                        : log.reps
                            + (ExerciseCatalog.perSide(i.exercise) ? "次/侧" : "次")
                            + (i.exercise.unit.isEmpty()
                                ? ""
                                : " · " + ExerciseCatalog.weightLabel(i.exercise) + " " + log.weight + " 千克"))));
      }
      if (i.logs.isEmpty()) r.addView(note("项目时长未记录"));
      body.addView(r);
    }
    LinearLayout hr = panel();
    hr.addView(text("心率记录", 17, ink, true));
    hr.addView(note(HeartSummary.coverage(q)));
    if (q.heartCount > 0) {
      hr.addView(metric(String.valueOf(Math.round(q.heartSum / (double) q.heartCount)), "次/分", 32));
      hr.addView(note(q.heartMin > 0 && q.heartMax > 0
          ? "采样平均 · 最低 " + q.heartMin + " · 最高 " + q.heartMax : "采样平均"));
    }
    if (!q.heartSamples.isEmpty()) {
      hr.addView(
          new HealthCharts.Heart(this, q, purple(), muted),
          new LinearLayout.LayoutParams(-1, d(getResources().getConfiguration().fontScale > 1.3f ? 220 : 180)));
      hr.addView(note("曲线连接采样点，不代表连续测量；断线处留空。"));
    }
    body.addView(hr);
    LinearLayout energy = panel();
    energy.addView(text("运动消耗", 17, ink, true));
    JSONObject profile = bodyProfile();
    Double estimate = estimatedWorkout(q, profile);
    addWithTopGap(energy,
        text(
            estimate == null ? "暂无法估算" : String.format(Locale.CHINA, q.calories == null ? "约 %.0f 千卡" : "%.0f 千卡", estimate),
            28,
            accent,
            true), 16);
    energy.addView(
        note(
            q.calories != null
                ? "手动填写的活动热量"
                : estimate != null ? "按项目有效时长和一般强度估算，已扣除静息部分" : "完善身体资料并记录有效时长后可估算；也可填写设备的活动热量"));
    action(energy, "填写热量", () -> calorieForm(q), false);
    if (!q.calorieHistory.isEmpty())
      action(
          energy,
          "撤销上次修改",
          () -> {
            try {
              Domain.Session edit = Store.session(Store.session(q));
              edit.calories = edit.calorieHistory.remove(edit.calorieHistory.size() - 1);
              db.saveSession(edit, false);
              selected = edit;
              render();
            } catch (JSONException e) {
              throw new IllegalStateException(e);
            }
          },
          false);
    body.addView(energy);
    action(body, "分享记录", () -> go(11), true);
    action(
        body,
        "删除记录",
        () ->
            confirm(
                "delete", "删除这次训练？",
                q.date + "的记录将被删除。",
                () -> {
                  db.removeWorkout(q.id);
                  historyReturnScroll = null;
                  selected = null;
                  back.clear();
                  seenRevision = ++app.dataRevision;
                  page = 14;
                  render();
                }),
        false);
  }

  private void weights() {
    classificationTitle("身体测量", "体重");
    List<Store.Weight> rows = db.weights();
    LinearLayout chart = panel(), segments = row();
    String[] labels = {"周", "月", "半年"};
    int[] spans = {7, 30, 183};
    segments.setPadding(d(3), d(3), d(3), d(3));
    segments.setMeasureWithLargestChildEnabled(true);
    segments.setMinimumWidth(3 * touchTarget() + 2 * d(8) + 2 * d(3));
    segments.setBackground(AppViews.round(HealthDesign.well(dark), d(12)));
    for (int i = 0; i < labels.length; i++) {
      final int days = spans[i];
      TextView tab =
          button(
              labels[i],
              () -> {
                weightDays = days;
                render();
              },
              false);
      tab.setMinHeight(touchTarget());
      tab.setMinWidth(touchTarget());
      tab.setTextColor(ink);
      tab.setBackground(
          AppViews.button(days == weightDays ? card : Color.TRANSPARENT, accent, d(9)));
      tab.setSelected(days == weightDays);
      segments.addView(
          tab,
          new LinearLayout.LayoutParams(0, -1, 1));
    }
    HorizontalScrollView periodViewport = new HorizontalScrollView(this);
    periodViewport.setFillViewport(true);
    periodViewport.setHorizontalFadingEdgeEnabled(true);
    periodViewport.setFadingEdgeLength(d(12));
    periodViewport.addView(segments, new FrameLayout.LayoutParams(-2, -2));
    chart.addView(periodViewport);
    List<Store.Weight> filtered = new ArrayList<>();
    LocalDate limit = LocalDate.now().minusDays(weightDays - 1);
    for (Store.Weight w : rows)
      if (!LocalDate.parse(w.date).isBefore(limit)
          && !LocalDate.parse(w.date).isAfter(LocalDate.now())) filtered.add(w);
    TextView date = note(filtered.isEmpty() ? "此时间范围暂无记录" : filtered.get(0).date);
    TextView value =
        metric(
            filtered.isEmpty() ? "—" : String.format(Locale.CHINA, "%.1f", filtered.get(0).kg),
            "千克",
            34);
    chart.addView(date);
    chart.addView(value);
    if (!filtered.isEmpty()) {
      chart.addView(
          new HealthCharts.Trend(
              this,
              filtered,
              teal(),
              muted,
              line,
              w -> {
                date.setText(w.date);
                value.setText(
                    metric(String.format(Locale.CHINA, "%.1f", w.kg), "千克", 34).getText());
              }),
          new LinearLayout.LayoutParams(-1, d(230)));
      chart.addView(note(filtered.size() > 1 ? "左右拖动查看记录 · 手动记录" : "手动记录"));
    }
    body.addView(chart);
    if (rows.size() > 1) {
      section("近期变化");
      LinearLayout change = panel();
      change.addView(text("最近两次记录", 14, teal(), true));
      double delta = rows.get(0).kg - rows.get(1).kg;
      TextView trend =
          text(
              String.format(
                  Locale.CHINA,
                  "%s %.1f 千克",
                  delta == 0 ? "无变化" : delta > 0 ? "增加" : "减少",
                  Math.abs(delta)),
              21,
              ink,
              true);
      trend.setPadding(0, d(14), 0, d(8));
      change.addView(trend);
      change.addView(note(rows.get(1).date + " 至 " + rows.get(0).date));
      body.addView(change);
    }
    section("选项");
    body.addView(
        listRow(
            "目标体重",
            db.setting("weightGoal", "").isEmpty() ? "未设置" : db.setting("weightGoal", "") + " 千克",
            "体重",
            teal(),
            this::goalForm));
    body.addView(
        listRow(
            pins().contains("weight") ? "取消在摘要中置顶" : "在摘要中置顶",
            "",
            "plus",
            accent,
            () -> {
              List<String> p = pins();
              if (!p.remove("weight")) p.add("weight");
              db.setSetting("healthPins", String.join(",", p));
              render();
            }));
    section("历史记录");
    for (Store.Weight w : rows.subList(0, Math.min(100, rows.size()))) {
      LinearLayout c = panel();
      c.addView(text(String.format(Locale.CHINA, "%.1f 千克", w.kg), 21, ink, true));
      c.addView(note(w.date));
      LinearLayout controls = row();
      controls.addView(
          button("编辑", () -> weightForm(w.date), false), new LinearLayout.LayoutParams(0, -2, 1));
      controls.addView(
          button(
              "删除",
              () ->
                  confirm(
                      "delete", "删除体重记录？",
                      w.date,
                      () -> {
                        db.removeWeight(w.date);
                        render();
                      }),
              false),
          new LinearLayout.LayoutParams(0, -2, 1));
      c.addView(controls);
      body.addView(c);
    }
    if (rows.size() > 100) body.addView(note("显示最近100条，备份包含全部记录。"));
  }

  private void dateInput(LinearLayout l, String key, String date) {
    EditText e = input(l, key, "日期", date, InputType.TYPE_CLASS_DATETIME);
    e.setFocusable(false);
    e.setOnClickListener(
        v -> {
          LocalDate x;
          try {
            x = LocalDate.parse(e.getText());
          } catch (Exception ignored) {
            x = LocalDate.now();
          }
          DatePicker picker = new DatePicker(this);
          picker.setMaxDate(System.currentTimeMillis());
          picker.init(x.getYear(), x.getMonthValue() - 1, x.getDayOfMonth(), null);
          int calendarRadius = d(HealthDesign.INPUT);
          picker.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
              outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), calendarRadius);
            }
          });
          picker.setClipToOutline(true);
          new GlassDialogBuilder()
              .setRoundedWindow()
              .setTitle("选择日期")
              .setView(picker)
              .setPositiveButton(
                  "确定",
                  (dialog, which) ->
                      e.setText(
                          LocalDate.of(
                                  picker.getYear(), picker.getMonth() + 1, picker.getDayOfMonth())
                              .toString()))
              .setNegativeButton("取消", null)
              .show();
        });
  }

  private void weightForm(String old) {
    Store.Weight found = old == null ? null : db.weightOn(old);
    LinearLayout l = beginForm("weight", old, -1);
    dateInput(l, "date", found == null ? LocalDate.now().toString() : found.date);
    EditText kgInput =
        input(l, "kg", "体重（千克）", found == null ? "" : String.valueOf(found.kg), 8194);
    kgInput.setTextSize(44);
    kgInput.setGravity(Gravity.CENTER);
    kgInput.setMinHeight(d(86));
    l.addView(note("每天保存一条，可补记。"));
    showForm(
        old == null ? "添加体重" : "修改体重",
        l,
        () -> {
          String date = Domain.date(val("date"), LocalDate.now());
          double kg = Domain.weight(val("kg"));
          boolean conflict = !date.equals(old) && db.weightOn(date) != null;
          Runnable save =
              () -> {
                db.weight(old, date, kg, conflict);
                closeForm();
                render();
              };
          if (conflict) {
            String message = old == null
                ? date + "已有体重记录。本次保存将替换该记录。"
                : "将 " + old + " 的记录改到 " + date + "。原日期将不再显示这条记录，目标日期已有记录将被替换。";
            confirmRecordReplacement("替换当天体重？", message, save);
          } else save.run();
        });
  }

  private void goalForm() {
    LinearLayout l = beginForm("goal", null, -1);
    input(l, "kg", "目标体重（千克）", db.setting("weightGoal", ""), 8194);
    action(
        l,
        "清除目标",
        () -> {
          db.setSetting("weightGoal", "");
          closeForm();
          render();
        },
        false);
    showForm(
        "目标体重",
        l,
        () -> {
          db.setSetting("weightGoal", String.format(Locale.ROOT, "%.1f", Domain.weight(val("kg"))));
          closeForm();
          render();
        });
  }

  private String nutritionNumber(double value) {
    if (value > 0 && value < 0.01) return "<0.01";
    return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  private String nutritionValue(JSONObject food, String key) {
    return !food.has(key) || food.isNull(key) ? "未提供" : nutritionNumber(food.optDouble(key)) + "克";
  }

  private void addMealBars(LinearLayout into, String[] labels, double[] totals, int[] known, int[] missing) {
    double maximum = 1;
    for (double total : totals) maximum = Math.max(maximum, total);
    for (int i = 0; i < labels.length; i++) {
      LinearLayout line = row(); line.setPadding(0, d(8), 0, d(8));
      TextView name = text(labels[i], 12, muted, false); line.addView(name, new LinearLayout.LayoutParams(d(40), -2));
      FrameLayout track = new FrameLayout(this); track.setBackground(AppViews.round((purple() & 0xffffff) | 0x18000000, d(6)));
      float fraction = (float) (totals[i] / maximum); View fill = new View(this); fill.setBackground(AppViews.round(purple(), d(6)));
      track.addView(fill, new FrameLayout.LayoutParams(0, -1));
      track.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob) -> {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) fill.getLayoutParams();
        int width = Math.round(track.getWidth() * fraction); if (params.width != width) { params.width = width; fill.setLayoutParams(params); }
      });
      line.addView(track, new LinearLayout.LayoutParams(0, d(12), 1));
      String label = known[i] == 0 ? (missing[i] == 0 ? "未记录" : "热量未知")
          : nutritionNumber(totals[i]) + " 千卡" + (missing[i] > 0 ? " · 部分未知" : "");
      TextView value = text(label, 12, muted, false); value.setGravity(Gravity.END);
      line.addView(value, new LinearLayout.LayoutParams(-2, -2)); into.addView(line);
    }
  }

  private String nutrientSummary(List<JSONObject> meals, String date) {
    String[] keys = {"protein", "fat", "carbs"}, labels = {"蛋白质", "脂肪", "碳水"};
    StringBuilder result = new StringBuilder();
    for (int k = 0; k < keys.length; k++) {
      double sum = 0; int known = 0, missing = 0;
      for (JSONObject meal : meals) if (date.equals(meal.optString("date"))) {
        JSONArray foods = meal.optJSONArray("foods"); if (foods == null) continue;
        for (int i = 0; i < foods.length(); i++) {
          JSONObject f = foods.optJSONObject(i);
          if (f == null || !f.has(keys[k]) || f.isNull(keys[k])) missing++;
          else { sum += f.optDouble(keys[k]); known++; }
        }
      }
      if (k > 0) result.append(" · ");
      result.append(labels[k]).append(" ");
      if (known == 0) result.append("未记录");
      else if (missing > 0) result.append("已知合计").append(nutritionNumber(sum)).append("克 · ").append(missing).append("项未计入");
      else result.append(nutritionNumber(sum)).append("克");
    }
    return result.toString();
  }

  private void meals() {
    classificationTitle("营养", "饮食能量");
    LocalDate day = LocalDate.now();
    String today = day.toString();
    List<JSONObject> todayMeals = db.meals(day);
    MealEnergy mealEnergy = summarizeMealEnergy(todayMeals);
    String[] slots = {"早餐", "午餐", "晚餐", "加餐"};
    double[] calories = new double[4];
    int[] slotKnown = new int[4], slotMissing = new int[4];
    for (JSONObject meal : todayMeals) {
      int slot = Arrays.asList(slots).indexOf(meal.optString("slot"));
      if (slot < 0) continue;
      JSONArray foods = meal.optJSONArray("foods");
      if (foods == null || foods.length() == 0) {
        slotMissing[slot]++;
        continue;
      }
      for (int i = 0; i < foods.length(); i++) {
        JSONObject food = foods.optJSONObject(i);
        if (food != null && food.has("kcal") && !food.isNull("kcal")) {
          calories[slot] += food.optDouble("kcal");
          slotKnown[slot]++;
        } else slotMissing[slot]++;
      }
    }
    LinearLayout total = panel();
    total.addView(note("今天 · " + today));
    Double intake = mealEnergy.intake();
    total.addView(
        metric(intake == null ? "—" : String.format(Locale.CHINA, "%,.0f", intake), "千卡", 34));
    addMealBars(total, slots, calories, slotKnown, slotMissing);
    total.addView(note(nutrientSummary(todayMeals, today)));
    total.addView(
        note(
            mealEnergy.unknown() > 0
                ? mealEnergy.unknown() + " 项食物未计入热量"
                : intake == null ? "今天还没有饮食记录" : "食物库估算 · 按实际份量记录"));
    body.addView(total);
    action(
        body,
        "记一餐",
        () -> {
          selectedPhoto = null;
          mealForm(null);
        },
        true);
    int pendingPhotos = db.pendingPhotoCount();
    if (pendingPhotos > 0)
      body.addView(listRow("历史餐食照片", pendingPhotos + " 张待整理", "饮食", purple(), () -> go(8)));
    section("饮食记录");
    List<JSONObject> meals = db.meals(101);
    if (meals.isEmpty()) body.addView(note("还没有饮食记录"));
    List<JSONObject> displayed = meals.subList(0, Math.min(100, meals.size()));
    List<String> mealIds = displayed.stream().map(m -> m.optString("id"))
        .collect(java.util.stream.Collectors.toList());
    Map<String, String> mealPhotos = db.mealPhotos(mealIds);
    for (JSONObject m : displayed) {
      LinearLayout c = panel();
      c.addView(text(m.optString("date") + " · " + m.optString("slot"), 17, ink, true));
      String photo = mealPhotos.get(m.optString("id"));
      if (photo != null) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(AppViews.round(soft, d(HealthDesign.INPUT)));
        image.setClipToOutline(true);
        image.setContentDescription("这餐的照片");
        ThumbLoader.load(app, image, photo);
        c.addView(image, new LinearLayout.LayoutParams(-1, d(160)));
      }
      JSONArray fs = m.optJSONArray("foods");
      c.addView(text(FoodCatalog.summary(fs), 25, purple(), true));
      c.addView(note(nutrientSummary(List.of(m), m.optString("date"))));
      if (fs != null)
        for (int i = 0; i < fs.length(); i++) {
          JSONObject food = fs.optJSONObject(i);
          LinearLayout foodWords = column();
          foodWords.setPadding(0, d(8), 0, d(4));
          foodWords.addView(text(food.optString("name"), 17, ink, true));
          foodWords.addView(note(food.optDouble("grams") + "克 · "
              + (food.has("kcal") && !food.isNull("kcal") ? "约" + food.optDouble("kcal") + "千卡" : "热量未提供")));
          foodWords.addView(note("蛋白质 " + nutritionValue(food, "protein")
              + " · 脂肪 " + nutritionValue(food, "fat")
              + " · 碳水 " + nutritionValue(food, "carbs")));
          StringBuilder historySources = new StringBuilder();
          String[] sourceKeys = {"kcal", "protein", "fat", "carbs"}, sourceNames = {"热量", "蛋白质", "脂肪", "碳水"};
          for (int sourceIndex = 0; sourceIndex < sourceKeys.length; sourceIndex++)
            if (food.has(sourceKeys[sourceIndex]) && !food.isNull(sourceKeys[sourceIndex])) {
              if (historySources.length() > 0) historySources.append("\n");
              historySources.append(sourceNames[sourceIndex]).append("来源：").append(FoodCatalog.nutrientSource(food, sourceKeys[sourceIndex]));
            }
          if (food.has("foodId")) foodWords.addView(note("食物库参考 ID：" + food.optString("foodId")));
          if (historySources.length() > 0) foodWords.addView(note(historySources.toString()));
          c.addView(foodWords);
        }
      LinearLayout controls = row();
      controls.addView(
          button(
              "编辑",
              () -> {
                selectedPhoto = null;
                mealForm(m);
              },
              false),
          new LinearLayout.LayoutParams(0, -2, 1));
      controls.addView(
          button(
              "删除",
              () ->
                  confirm(
                      "delete", "删除饮食记录？",
                      db.mealPhoto(m.optString("id")) == null ? "删除后无法恢复。" : "关联照片会保留在历史餐食照片中。",
                      () -> {
                        db.removeMeal(m.optString("id"));
                        render();
                      }),
              false),
          new LinearLayout.LayoutParams(0, -2, 1));
      c.addView(controls);
      body.addView(c);
    }
    if (meals.size() > 100) body.addView(note("显示最近100餐，备份包含全部记录。"));
  }

  private JSONObject recognizedDraft;

  private void mealForm(JSONObject existing) {
    LinearLayout l = beginForm("meal", existing == null ? null : existing.optString("id"), -1);
    dateInput(
        l, "date", existing == null ? LocalDate.now().toString() : existing.optString("date"));
    String[] slots = {"早餐", "午餐", "晚餐", "加餐"};
    select(
        l,
        "slot",
        "餐次",
        slots,
        existing == null ? 1 : Arrays.asList(slots).indexOf(existing.optString("slot")));
    String foodText = "";
    JSONArray foods =
        existing == null
            ? (recognizedDraft == null ? null : recognizedDraft.optJSONArray("foods"))
            : existing.optJSONArray("foods");
    if (existing == null) recognizedDraft = null; // The opened form owns this one-time input.
    if (foods != null)
      for (int i = 0; i < foods.length(); i++) {
        JSONObject f = foods.optJSONObject(i);
        foodText += foodCatalog.snapshotLine(f) + "\n";
      }
    EditText names =
        input(
            l,
            "foods",
            "食物名称，食用量（克）",
            foodText,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    names.setSingleLine(false);
    names.setMinLines(4);
    names.setGravity(Gravity.TOP);
    names.setLayoutParams(new LinearLayout.LayoutParams(-1, d(160)));
    names.setHint("每行一项：米饭，100\n包装食品：食品名称，克数，每100克千卡");
    showInput(names, false);
    action(l, "添加食物", () -> foodPicker(names), true);
    action(l, "手动填写 / 包装食品", () -> manualFoodForm(names), false);
    LinearLayout selectedFoods = column();
    TextView estimate = text("", 14, muted, false);
    Runnable updateEstimate =
        () -> {
          try {
            JSONArray entries = foodCatalog.parse(names.getText().toString());
            estimate.setText(FoodCatalog.summary(entries));
            renderMealFoods(selectedFoods, names, entries);
          } catch (Exception e) {
            selectedFoods.removeAllViews();
            estimate.setText(
                names.getText().toString().isBlank() ? "从食物库选择，按份量添加" : e.getMessage());
          }
        };
    names.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            updateEstimate.run();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    updateEstimate.run();
    TextView foodInfo =
        link(
            "ⓘ 食物热量说明",
            () ->
                new GlassDialogBuilder()
                    .setTitle("食物热量说明")
                    .setMessage(
                        "按实际食用的可食部分填写重量。食物库按每100克估算，生熟状态、用油和品牌会影响结果；没有热量数据的食物不计入合计。食物库数据来源：USDA SR"
                            + " Legacy 2018。")
                    .setNegativeButton("关闭", null)
                    .show());
    foodInfo.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    foodInfo.setPadding(0, 0, 0, 0);
    foodInfo.setMinHeight(touchTarget());
    LinearLayout.LayoutParams estimateLayout = new LinearLayout.LayoutParams(-1, -2);
    estimateLayout.topMargin = d(12);
    l.addView(estimate, estimateLayout);
    LinearLayout.LayoutParams infoLayout = new LinearLayout.LayoutParams(-1, -2);
    infoLayout.topMargin = d(4);
    l.addView(foodInfo, infoLayout);
    LinearLayout.LayoutParams foodsLayout = new LinearLayout.LayoutParams(-1, -2);
    foodsLayout.topMargin = d(12);
    l.addView(selectedFoods, foodsLayout);
    if (selectedPhoto != null) l.addView(note("已关联历史餐食照片"));
    showForm(
        existing == null ? "记一餐" : "编辑饮食记录",
        l,
        () -> {
          try {
            String date = Domain.date(val("date"), LocalDate.now()),
                slot = (String) choices.get("slot").getSelectedItem();
            JSONArray fs = foodCatalog.parse(val("foods"));
            String id = existing == null ? UUID.randomUUID().toString() : existing.getString("id");
            JSONObject meal =
                new JSONObject().put("id", id).put("date", date).put("slot", slot).put("savedAt", System.currentTimeMillis()).put("foods", fs);
            List<String> photos =
                selectedPhoto == null
                    ? Collections.emptyList()
                    : Collections.singletonList(selectedPhoto);
            String conflict = db.conflictingMeal(date, slot, id);
            Runnable save =
                () -> {
                  db.meal(meal, photos, conflict != null);
                  selectedPhoto = null;
                  recognizedDraft = null;
                  closeForm();
                  go(9);
                };
            if (conflict != null) {
              String destination = date + " " + slot;
              String message = existing == null
                  ? destination + "已有记录。本次保存将替换该记录。"
                  : "将 " + existing.optString("date") + " " + existing.optString("slot")
                      + " 的记录改到 " + destination
                      + "。原位置将不再显示这条记录，目标餐次已有记录将被替换。";
              if (db.mealPhoto(conflict) != null) message += " 目标餐次的关联照片将回到待整理。";
              confirmRecordReplacement("替换这餐记录？", message, save);
            } else save.run();
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private void photos() {
    title("餐食照片");
    body.addView(note("已保存的照片可关联饮食记录，或单独删除。"));
    List<String[]> list = db.photos(51);
    if (list.isEmpty()) body.addView(note("暂无待整理照片"));
    for (String[] photo : list.subList(0, Math.min(50, list.size()))) {
      LinearLayout c = panel();
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      ThumbLoader.load(app, image, photo[1]);
      image.setContentDescription("待整理餐食照片");
      image.setClipToOutline(true);
      image.setBackground(AppViews.round(card, d(GlassEffects.CONTROL)));
      c.addView(image, new LinearLayout.LayoutParams(-1, d(160)));
      action(
          c,
          "记录这一餐",
          () -> {
            selectedPhoto = photo[0];
            recognizedDraft = null;
            mealForm(null);
          },
          true);

      action(
          c,
          "删除照片",
          () ->
              confirm(
                  "delete", "删除这张照片？",
                  "只删除应用内副本。",
                  () -> {
                    String path = db.removePhoto(photo[0]);
                    if (path != null) new File(path).delete();
                    render();
                  }),
          false);
      body.addView(c);
    }
    if (list.size() > 50) body.addView(note("显示最近50张，整理后可继续查看更早照片。"));
  }

  private String cameraPath;

  private void discardCameraCapture(String path) {
    if (path == null) return;
    try {
      File file = new File(path).getCanonicalFile();
      File folder = new File(getCacheDir(), "shared").getCanonicalFile();
      if (!folder.equals(file.getParentFile()) || !file.getName().startsWith("camera-")) return;
      if (file.exists() && !file.delete()) return;
      if (path.equals(getPreferences(0).getString("cameraPath", null)))
        getPreferences(0).edit().remove("cameraPath").commit();
    } catch (IOException ignored) {
      // Never delete a path outside our own camera cache.
    }
  }

  private void camera() {
    try {
      File dir = new File(getCacheDir(), "shared");
      dir.mkdirs();
      File file = new File(dir, "camera-" + System.currentTimeMillis() + ".jpg");
      if (!file.createNewFile()) throw new IOException("无法创建照片");
      cameraPath = file.getAbsolutePath();
      getPreferences(0).edit().putString("cameraPath", cameraPath).commit();
      Uri uri = Uri.parse("content://" + getPackageName() + ".share/" + file.getName());
      Intent i =
          new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
              .putExtra(MediaStore.EXTRA_OUTPUT, uri)
              .addFlags(
                  Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      i.setClipData(ClipData.newRawUri("拍照", uri));
      startActivityForResult(i, 202);
    } catch (Exception e) {
      toast("无法打开相机，请从相册选择");
    }
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    String capture = req == 202 ? getPreferences(0).getString("cameraPath", null) : null;
    if (req == 202 && result != RESULT_OK) discardCameraCapture(capture);
    if (result != RESULT_OK) return;
    List<Uri> uris = new ArrayList<>();
    if (req == 201 && data != null) {
      if (data.getClipData() != null) {
        for (int i = 0; i < Math.min(20, data.getClipData().getItemCount()); i++)
          uris.add(data.getClipData().getItemAt(i).getUri());
      } else if (data.getData() != null) uris.add(data.getData());
    } else if (req == 202) {
      String path = getPreferences(0).getString("cameraPath", null);
      if (path != null) uris.add(Uri.fromFile(new File(path)));
    }
    if (uris.isEmpty()) return;
    task(
        () -> {
          int n = 0;
          for (Uri u : uris) {
            if (PhotoFiles.importPhoto(this, db, u)) n++;
          }
          // Keep the original on import failure; discard only once the app copy is durable.
          if (req == 202) discardCameraCapture(capture);
          return n;
        },
        n -> {
          getPreferences(0).edit().remove("cameraPath").apply();
          toast(n == 0 ? "照片已在待记录列表中" : "已导入 " + n + " 张照片");
          page = 8;
          render();
        });
  }

  private void settings() {
    title("设置");
    LinearLayout live = panel();
    live.addView(text("实时运动状态", 18, ink, true));
    live.addView(
        note(
            Build.VERSION.SDK_INT >= 36
                ? "使用系统实时通知显示场景与计时；荣耀胶囊是否展示由系统决定。"
                : "Android 16及以上可使用系统实时通知；当前显示运动通知。"));
    if (Build.VERSION.SDK_INT >= 36)
      action(
          live,
          "系统实时通知设置",
          () -> {
            Intent settings =
                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            try {
              startActivity(settings);
            } catch (ActivityNotFoundException unavailable) {
              toast("当前系统未提供此设置入口");
            }
          },
          false);
    body.addView(live);
    LinearLayout appearance = panel();
    appearance.addView(text("外观", 18, ink, true));
    action(
        appearance,
        "主题："
            + (db.setting("theme", "auto").equals("auto")
                ? "跟随系统"
                : db.setting("theme", "auto").equals("dark") ? "深色" : "浅色"),
        () ->
            new GlassDialogBuilder()
                .setTitle("主题")
                .setSingleChoiceItems(
                    new String[] {"跟随系统", "浅色", "深色"},
                    Arrays.asList("auto", "light", "dark").indexOf(db.setting("theme", "auto")),
                    (d, w) -> {
                      db.setSetting("theme", new String[] {"auto", "light", "dark"}[w]);
                      d.dismiss();
                      render();
                    })
                .show(),
        false);
    Switch glass = new Switch(this);
    glass.setText("液态玻璃底栏");
    glass.setMinHeight(d(52));
    glass.setTextColor(ink);
    glass.setChecked(!db.setting("solid", "false").equals("true"));
    glass.setPadding(0, d(18), 0, d(10));
    glass.setOnCheckedChangeListener(
        (v, on) -> {
          db.setSetting("solid", String.valueOf(!on));
          render();
        });
    appearance.addView(glass);
    Switch reduce = new Switch(this);
    reduce.setText("减少动态效果");
    reduce.setTextColor(ink);
    reduce.setMinHeight(d(52));
    reduce.setChecked(db.setting("reduceMotion", "false").equals("true"));
    reduce.setOnCheckedChangeListener((v, on) -> {
      db.setSetting("reduceMotion", String.valueOf(on));
      updateNavigationMaterial();
    });
    appearance.addView(reduce);
    body.addView(appearance);
    LinearLayout data = panel();
    data.addView(text("数据", 18, ink, true));
    data.addView(note("记录保存在本机；备份导出后目前无法在应用内恢复。"));
    action(data, "导出备份", this::backup, false);
    body.addView(data);
    LinearLayout library = panel();
    library.addView(text("离线食物库", 18, ink, true));
    library.addView(note(foodCatalog.foods.size() + " 项常见食物，可按食用量计算"));
    action(library, "浏览食物库", () -> foodPicker(null), false);
    body.addView(library);
    String version = "";
    try {
      version = " " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
      // The app remains usable if package metadata is unavailable.
    }
    body.addView(note("练有度" + version + "\n食物热量为参考估算。"));
  }

  private void sharing() {
    title("分享运动记录");
    if (selected == null) {
      action(body, "选择训练记录", () -> go(14), true);
      return;
    }
    Switch hr = new Switch(this), weight = new Switch(this);
    hr.setText("显示心率记录时长");
    weight.setText("显示最近体重和日期");
    hr.setTextColor(ink);
    weight.setTextColor(ink);
    hr.setMinHeight(d(48));
    weight.setMinHeight(d(48));
    weight.setEnabled(!db.weights().isEmpty());
    body.addView(hr);
    body.addView(weight);
    action(body, "查看文字记录", () -> {
      Domain.require(selected != null, "训练记录已变化，请重新选择");
      List<Store.Weight> records = weight.isChecked() ? db.weights() : List.of();
      Domain.require(!weight.isChecked() || !records.isEmpty(), "没有可显示的体重记录");
      Store.Weight displayedWeight = records.isEmpty() ? null : records.get(0);
      LinearLayout content = column();
      content.addView(text(ShareImage.accessibleSummary(selected, hr.isChecked(), displayedWeight), 16, ink, false));
      new GlassDialogBuilder().setTitle("运动记录内容").setView(content).setNegativeButton("关闭", null).show();
    }, false);
    ImageView preview = new ImageView(this);
    preview.setAdjustViewBounds(true);
    preview.setContentDescription("运动记录图片预览，可查看文字记录阅读完整内容");
    body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
    TextView imageError = note("");
    imageError.setVisibility(View.GONE);
    imageError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    body.addView(imageError);
    final Bitmap[] image = new Bitmap[1];
    final String[] generatedKey = {""};
    final Runnable[] update = new Runnable[1];
    final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    java.util.function.Supplier<Store.Weight> chosenWeight = () -> {
      if (!weight.isChecked()) return null;
      List<Store.Weight> records = db.weights();
      Domain.require(!records.isEmpty(), "没有可显示的体重记录");
      return records.get(0);
    };
    java.util.function.Supplier<String> key = () -> selected == null ? ""
        : ShareImage.stateKey(selected, hr.isChecked(), chosenWeight.get(), dark)
            + "\0" + app.dataRevision;
    TextView retry = button("重新生成", () -> update[0].run(), false);
    TextView save = button("保存到相册", () -> {}, true);
    TextView send = button("分享图片", () -> {}, false);
    retry.setVisibility(View.GONE);
    addWithTopGap(body, retry, 12);
    addWithTopGap(body, save, 12);
    addWithTopGap(body, send, 12);
    java.util.function.BiConsumer<Boolean, String> exportState = (enabled, description) -> {
      save.setEnabled(enabled);
      send.setEnabled(enabled);
      int disabled = HealthDesign.muted(dark);
      save.setTextColor(enabled ? (dark ? color("#10151E") : Color.WHITE) : disabled);
      send.setTextColor(enabled ? accent : disabled);
      save.setBackground(AppViews.button(enabled ? accent : HealthDesign.well(dark), ink, d(GlassEffects.CONTROL)));
      send.setBackground(AppViews.button(HealthDesign.well(dark), ink, d(GlassEffects.CONTROL)));
      save.setStateDescription(enabled ? null : description);
      send.setStateDescription(enabled ? null : description);
    };
    update[0] = () -> {
      // Invalidate first: a failed regeneration must never leave the previous private image exportable.
      long request = generation.incrementAndGet();
      image[0] = null;
      generatedKey[0] = "";
      preview.setImageDrawable(null);
      exportState.accept(false, "图片生成中，暂不可用");
      retry.setVisibility(View.GONE);
      imageError.setText("正在生成图片…");
      imageError.setVisibility(View.VISIBLE);
      try {
        String before = key.get();
        Store.Weight selectedWeight = chosenWeight.get();
        Domain.Session snapshot = copySession(selected);
        boolean showHeart = hr.isChecked(), useDark = dark;
        app.io.execute(() -> {
          if (generation.get() != request) return;
          Bitmap rendered = null;
          try {
            rendered = ShareImage.draw(snapshot, showHeart, selectedWeight, useDark);
          } catch (RuntimeException | OutOfMemoryError failure) {
            // The main thread below exposes the failure and leaves export disabled.
          }
          Bitmap result = rendered;
          app.main.post(() -> {
            if (generation.get() != request || isDestroyed() || !preview.isAttachedToWindow()) {
              if (result != null) result.recycle();
              return;
            }
            boolean stillCurrent;
            try { stillCurrent = before.equals(key.get()); }
            catch (RuntimeException changed) { stillCurrent = false; }
            if (!stillCurrent) {
              if (result != null) result.recycle();
              update[0].run();
            } else if (result == null) {
              imageError.setText("图片暂时无法生成，请重新生成或返回后重试。");
              exportState.accept(false, "图片生成失败，暂不可用");
              retry.setVisibility(View.VISIBLE);
            } else {
              image[0] = result;
              generatedKey[0] = before;
              preview.setImageBitmap(result);
              imageError.setVisibility(View.GONE);
              exportState.accept(true, "");
            }
          });
        });
      } catch (RuntimeException | OutOfMemoryError failure) {
        imageError.setText("图片暂时无法生成，请重新生成或返回后重试。");
        imageError.setVisibility(View.VISIBLE);
        exportState.accept(false, "图片生成失败，暂不可用");
        retry.setVisibility(View.VISIBLE);
      }
    };
    java.util.function.BooleanSupplier checkCurrent = () -> {
      boolean current;
      try {
        current = image[0] != null && generatedKey[0].equals(key.get());
      } catch (RuntimeException failure) {
        current = false;
      }
      if (!current) {
        update[0].run();
        toast("记录或选项已变化，请核对预览后重试。");
      }
      return current;
    };
    save.setOnClickListener(v -> guard(() -> {
      if (busy || app.operationBusy) return;
      if (!checkCurrent.getAsBoolean()) return;
      final Bitmap shot = copyShareImage(image[0]);
      if (shot == null) return;
      task(() -> {
        try { return PhotoFiles.saveGallery(this, shot); }
        finally { shot.recycle(); }
      }, uri -> toast("已保存到相册"), false);
    }));
    send.setOnClickListener(v -> guard(() -> {
      if (busy || app.operationBusy) return;
      if (!checkCurrent.getAsBoolean()) return;
      final Bitmap shot = copyShareImage(image[0]);
      if (shot == null) return;
      task(() -> {
        File dir = new File(getCacheDir(), "shared");
        File pending = null, out = null;
        boolean complete = false;
        try {
          if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建分享目录");
          pending = File.createTempFile("workout-", ".tmp", dir);
          out = File.createTempFile("workout-", ".png", dir);
          try (OutputStream stream = new FileOutputStream(pending)) {
            if (!shot.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("图片保存失败");
          }
          if (!pending.renameTo(out)) throw new IOException("图片保存失败");
          complete = true;
          return out;
        } catch (OutOfMemoryError failure) {
          throw new IOException("图片编码失败", failure);
        } finally {
          shot.recycle();
          if (pending != null && pending.exists()) pending.delete();
          if (!complete && out != null && out.exists()) out.delete();
        }
      }, file -> share(file, "image/png"), false);
    }));
    hr.setOnCheckedChangeListener((b, on) -> update[0].run());
    weight.setOnCheckedChangeListener((b, on) -> update[0].run());
    update[0].run();
  }

  private Bitmap copyShareImage(Bitmap image) {
    if (image == null) {
      toast("图片未生成，请返回后重试。");
      return null;
    }
    try {
      Bitmap copy = image.copy(Bitmap.Config.ARGB_8888, false);
      if (copy == null) toast("图片暂时无法处理，请重试。");
      return copy;
    } catch (OutOfMemoryError failure) {
      toast("图片暂时无法处理，请重试。");
      return null;
    }
  }

  private void share(File file, String type) {
    Uri uri = Uri.parse("content://" + getPackageName() + ".share/" + file.getName());
    Intent i =
        new Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    i.setClipData(ClipData.newRawUri("分享", uri));
    startActivity(Intent.createChooser(i, "分享"));
  }

  private void backup() {
    AlertDialog disclosure =
        new GlassDialogBuilder()
            .setWholeContentScrollable()
            .setIconKind("export")
            .setTitle("导出备份")
            .setMessage(
                "备份可能包含训练和已记录的心率、体重、饮食、旧版历史路线位置数据、已保存的照片（含旧照片）、计划和设置，以及已保存的步数和能量汇总。文件未加密，请谨慎保管。目前无法在应用内直接恢复此备份。继续后将生成文件并打开系统分享，由你选择去向。")
            .setNegativeButtonFirst()
            .setNegativeButton("取消", null)
            .setPositiveButton("继续导出", (dialog, which) -> createBackup())
            .create();
    disclosure.setOnShowListener(
        ignored ->
            ((DesignDialog) disclosure)
                .focusSafeAction(disclosure.getButton(AlertDialog.BUTTON_NEGATIVE)));
    disclosure.show();
  }

  private void createBackup() {
    task(
        () -> {
          File dir = new File(getCacheDir(), "shared");
          dir.mkdirs();
          File out = new File(dir, "backup-" + System.currentTimeMillis() + ".zip");
          JSONObject data = db.exportData();
          try (java.util.zip.ZipOutputStream zip =
              new java.util.zip.ZipOutputStream(new FileOutputStream(out))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("records.json"));
            zip.write(data.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            JSONArray photos = data.getJSONArray("photos");
            for (int i = 0; i < photos.length(); i++) {
              JSONObject p = photos.getJSONObject(i);
              File file = new File(p.getString("path"));
              if (!file.isFile()) throw new IOException("照片文件缺失，备份未完成");
              zip.putNextEntry(new java.util.zip.ZipEntry("photos/" + p.getString("id") + ".jpg"));
              try (InputStream in = new FileInputStream(file)) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) != -1) zip.write(chunk, 0, count);
              }
              zip.closeEntry();
            }
          } catch (Exception error) {
            if (out.exists()) out.delete();
            throw error;
          }
          return out;
        },
        f -> share(f, "application/zip"));
  }

  private <T> void task(Callable<T> work, java.util.function.Consumer<T> success) {
    task(work, success, true);
  }

  private <T> void task(Callable<T> work, java.util.function.Consumer<T> success, boolean changesData) {
    if (busy || app.operationBusy) return;
    busy = true;
    syncBackCallback();
    app.operationBusy = true;
    AlertDialog progress =
        new GlassDialogBuilder()
            .setTitle("正在处理")
            .setView(new ProgressBar(this))
            .setCancelable(false)
            .create();
    operationDialog = progress;
    progress.show();
    app.io.execute(
        () -> {
          try {
            T result = work.call();
            app.main.post(
                () -> {
                  busy = false;
                  app.operationBusy = false;
                  syncBackCallback();
                  if (changesData) {
                    app.dataRevision++;
                    app.changed();
                  }
                  if (!isDestroyed()) {
                    progress.dismiss();
                    try {
                      success.accept(result);
                    } catch (Exception e) {
                      toast("操作未完成：" + e.getMessage());
                    }
                  }
                });
          } catch (Exception e) {
            app.main.post(
                () -> {
                  busy = false;
                  app.operationBusy = false;
                  syncBackCallback();
                  if (changesData) {
                    app.dataRevision++;
                    app.changed();
                  }
                  if (!isDestroyed()) {
                    progress.dismiss();
                    toast("操作未完成：" + e.getMessage());
                  }
                });
          }
        });
  }

  private void calorieForm(Domain.Session q) {
    LinearLayout l = beginForm("calorie", q.id, -1);
    input(l, "calories", "活动热量（千卡）", q.calories == null ? "" : q.calories.toString(), 8194);
    showForm(
        "活动热量",
        l,
        () -> {
          double n = Domain.number(val("calories"), "热量");
          Domain.require(n > 0 && n <= 9999, "活动热量为1至9999千卡");
          Domain.Session edit = copySession(q);
          edit.calorieHistory.add(q.calories);
          edit.calories = n;
          db.saveSession(edit, false);
          selected = edit;
          closeForm();
          render();
        });
  }

  private void manualFoodForm(EditText target) {
    LinearLayout form = column();
    String[] labels = {"食物名称", "食用量（克）", "每100克热量（千卡，可留空）", "每100克蛋白质（克，可留空）", "每100克脂肪（克，可留空）", "每100克碳水（克，可留空）"};
    EditText[] values = new EditText[labels.length];
    for (int i = 0; i < labels.length; i++) {
      form.addView(note(labels[i]));
      EditText value = new EditText(this); value.setTextColor(ink); value.setInputType(i == 0 ? InputType.TYPE_CLASS_TEXT : 8194);
      value.setBackground(AppViews.round(soft, d(HealthDesign.INPUT))); value.setPadding(d(12),d(10),d(12),d(10));
      form.addView(value); values[i] = value;
    }
    String[] categories = {"菜肴及其他", "主食", "蔬菜", "水果", "肉禽", "蛋类", "水产", "奶及奶制品", "豆及豆制品", "坚果种子", "油脂调味", "饮料"};
    Spinner category = new Spinner(this); category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));
    form.addView(note("食物类别")); form.addView(category);
    AlertDialog dialog = new GlassDialogBuilder().setWholeContentScrollable().setTitle("手动添加食物").setView(form)
        .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
    dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      try {
        String name = values[0].getText().toString().trim();
        Domain.require(!name.contains(",") && !name.contains("，") && !name.contains("\n"), "名称不能包含逗号或换行");
        StringBuilder line = new StringBuilder(name);
        for (int i = 1; i < values.length; i++) line.append("，").append(values[i].getText().toString().trim());
        line.append("，").append(category.getSelectedItem());
        foodCatalog.parse(line.toString());
        List<String> lines = new ArrayList<>(Arrays.asList(target.getText().toString().split("\\n")));
        lines.removeIf(old -> old.isBlank() || old.split("[，,]", -1)[0].trim().equals(name));
        lines.add(line.toString()); Domain.require(lines.size() <= 30, "每餐最多30项食物");
        target.setText(String.join("\n", lines)); dialog.dismiss();
      } catch (Exception error) { toast(error.getMessage()); }
    });
  }

  private void editFoodNutrients(EditText target, int index, JSONObject food) {
    LinearLayout form = column();
    String[] keys = {"kcal100", "protein100", "fat100", "carbs100"};
    String[] labels = {"每100克热量（千卡）", "每100克蛋白质（克）", "每100克脂肪（克）", "每100克碳水（克）"};
    EditText[] values = new EditText[keys.length];
    for (int i = 0; i < keys.length; i++) {
      form.addView(note(labels[i] + " · 可留空"));
      values[i] = new EditText(this); values[i].setInputType(8194); values[i].setTextColor(ink);
      if (food.has(keys[i]) && !food.isNull(keys[i])) values[i].setText(food.optString(keys[i]));
      form.addView(values[i]);
    }
    String[] categories = {"菜肴及其他", "主食", "蔬菜", "水果", "肉禽", "蛋类", "水产", "奶及奶制品", "豆及豆制品", "坚果种子", "油脂调味", "饮料"};
    Spinner category = new Spinner(this); category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));
    int categoryIndex = Arrays.asList(categories).indexOf(food.optString("category")); category.setSelection(Math.max(0, categoryIndex));
    form.addView(note("食物类别")); form.addView(category);
    form.addView(note("修改数值后，修改项来源标记为用户填写。"));
    AlertDialog dialog = new GlassDialogBuilder().setWholeContentScrollable().setTitle("编辑营养信息")
        .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
    dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      try {
        String[] lines = Arrays.stream(target.getText().toString().split("\\n"))
            .filter(line -> !line.isBlank()).toArray(String[]::new);
        Domain.require(index < lines.length, "食物已变化，请重新打开");
        String[] existing = lines[index].split("[，,]", -1);
        Domain.require(existing[0].trim().equals(food.optString("name")), "食物已变化，请重新打开");
        String[] parts = foodCatalog.snapshotLine(food).split("[，,]", -1);
        for (int i = 0; i < keys.length; i++) parts[i + 2] = values[i].getText().toString().trim();
        parts[6] = String.valueOf(category.getSelectedItem());
        String line = String.join("，", parts); foodCatalog.parse(line);
        lines[index] = line; target.setText(String.join("\n", lines)); dialog.dismiss();
      } catch (Exception error) { toast(error.getMessage()); }
    });
  }

  private void addShortHouseholdReference(LinearLayout parent, JSONObject food) {
    String id = food.optString("foodId", food.optString("id", ""));
    String label = id.equals("169757") ? "参考：1碗熟米饭 ≈158克（参考碗约240毫升，平口、不堆尖、不压实）"
        : id.equals("173944") ? "参考：中等香蕉1根 ≈118克（去皮可食部）" : "";
    if (label.isEmpty()) return;
    TextView reference = text(label, 15, muted, false);
    reference.setPadding(0, d(6), 0, 0); parent.addView(reference);
  }

  private void addHouseholdReferences(LinearLayout parent, JSONObject food, EditText grams) {
    JSONArray refs = foodCatalog.householdReferences(food);
    if (refs.length() == 0) return;
    TextView title = text("生活份量参考", 15, muted, true); title.setPadding(0, d(8), 0, 0); parent.addView(title);
    for (int i = 0; i < refs.length(); i++) {
      JSONObject ref = refs.optJSONObject(i); if (ref == null) continue;
      String label = ref.optString("label_zh"), definition = ref.optString("definition_zh");
      String amount = nutritionNumber(ref.optDouble("grams"));
      TextView detail = text(label + " ≈" + amount + "克 · " + definition, 15, muted, false);
      detail.setPadding(0, d(6), 0, 0); parent.addView(detail);
      if (grams != null) {
        TextView apply = link("用" + label + "参考量（约" + amount + "克）", () -> grams.setText(amount));
        apply.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); apply.setPadding(0, d(8), 0, d(8)); parent.addView(apply);
      }
    }
    TextView note = text("仅为份量参考，实际大小和装盛方式会有差异；可直接修改克数。来源：USDA SR Legacy 2018。", 15, muted, false);
    note.setPadding(0, d(4), 0, 0); parent.addView(note);
  }

  private void addPortionPreview(LinearLayout parent, JSONObject food, EditText grams) {
    TextView preview = note(""); parent.addView(preview);
    Runnable update = () -> {
      try {
        double amount = Domain.number(grams.getText().toString(), "食用量");
        if (food.has("kcal100") && !food.isNull("kcal100"))
          preview.setText("约 " + nutritionNumber(food.optDouble("kcal100") * amount / 100) + " 千卡");
        else preview.setText("热量未提供");
      } catch (Exception ignored) { preview.setText("请输入有效克数"); }
    };
    grams.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
      public void onTextChanged(CharSequence value, int start, int before, int count) { update.run(); }
      public void afterTextChanged(android.text.Editable value) {}
    });
    update.run();
  }

  private void renderMealFoods(LinearLayout rows, EditText source, JSONArray entries) {
    rows.removeAllViews();
    for (int i = 0; i < entries.length(); i++) {
      final int index = i;
      JSONObject food = entries.optJSONObject(i);
      LinearLayout card = column(), r = row();
      card.setPadding(0, d(8), 0, d(8));
      int image = foodCatalog.imageIndex(food.optString("name"));
      if (image >= 0)
        r.addView(new GuideImages(this, true, image), new LinearLayout.LayoutParams(d(62), d(62)));
      LinearLayout labels = column();
      labels.setPadding(d(12), 0, 0, 0);
      labels.addView(text(food.optString("name"), 15, ink, true));
      labels.addView(
          note(
              food.optDouble("grams")
                  + "克 · "
                  + (food.has("kcal") ? Math.round(food.optDouble("kcal")) + "千卡" : "热量未填写")));
      addShortHouseholdReference(labels, food);
      r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(r);
      if (food.has("foodId")) card.addView(note("食物库参考 ID：" + food.optString("foodId")));
      StringBuilder sources = new StringBuilder();
      String[] sourceKeys = {"kcal", "protein", "fat", "carbs"}, sourceLabels = {"热量", "蛋白质", "脂肪", "碳水"};
      for (int sourceIndex = 0; sourceIndex < sourceKeys.length; sourceIndex++)
        if (food.has(sourceKeys[sourceIndex]) && !food.isNull(sourceKeys[sourceIndex])) {
          if (sources.length() > 0) sources.append("\n");
          sources.append(sourceLabels[sourceIndex]).append("来源：").append(FoodCatalog.nutrientSource(food, sourceKeys[sourceIndex]));
        }
      if (sources.length() > 0) card.addView(note(sources.toString()));
      card.addView(note("蛋白质 " + nutritionValue(food, "protein")
          + " · 脂肪 " + nutritionValue(food, "fat")
          + " · 碳水 " + nutritionValue(food, "carbs")));
      card.addView(link("编辑营养信息", () -> editFoodNutrients(source, index, food)));
      LinearLayout buttons = row();
      buttons.addView(
          button(
              "改份量",
              () -> {
                LinearLayout content = column();
                EditText grams = new EditText(this);
                grams.setInputType(8194);
                grams.setText(String.valueOf(food.optDouble("grams")));
                grams.setTextColor(ink);
                grams.setBackground(AppViews.round(soft, d(HealthDesign.INPUT)));
                grams.setContentDescription("修改食用克数");
                grams.setPadding(d(14), d(12), d(14), d(12));
                content.addView(note("食用量（克）"));
                content.addView(grams);
                addHouseholdReferences(content, food, grams);
                addPortionPreview(content, food, grams);
                AlertDialog dialog =
                    new GlassDialogBuilder()
                        .setTitle(food.optString("name"))
                        .setView(content)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", null)
                        .create();
                dialog.setOnShowListener(
                    x ->
                        dialog
                            .getButton(-1)
                            .setOnClickListener(
                                v -> {
                                  try {
                                    double g = Domain.number(grams.getText().toString(), "克数");
                                    Domain.require(g > 0 && g <= 10000, "请填写0至10000之间的克数");
                                    String[] lines =
                                        Arrays.stream(source.getText().toString().split("\\n"))
                                            .filter(foodLine -> !foodLine.isBlank())
                                            .toArray(String[]::new);
                                    String[] parts = lines[index].split("[，,]", -1);
                                    lines[index] =
                                        parts[0]
                                            + "，"
                                            + g
                                            + (parts.length > 2 ? "，" + String.join("，", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : "");
                                    source.setText(String.join("\n", lines));
                                    dialog.dismiss();
                                  } catch (Exception error) {
                                    toast(error.getMessage());
                                  }
                                }));
                dialog.show();
                String initialGrams = grams.getText().toString();
                protectUnsaved(dialog, () -> !initialGrams.equals(grams.getText().toString()), dialog::dismiss);
              },
              false),
          new LinearLayout.LayoutParams(0, -2, 1));
      buttons.addView(
          button(
              "移除",
              () -> {
                List<String> lines =
                    new ArrayList<>(
                        Arrays.stream(source.getText().toString().split("\\n"))
                            .filter(foodLine -> !foodLine.isBlank())
                            .collect(java.util.stream.Collectors.toList()));
                lines.remove(index);
                source.setText(String.join("\n", lines));
              },
              false),
          new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(buttons);
      rows.addView(card);
    }
  }

  private JSONObject bodyProfile() {
    try {
      return new JSONObject(db.setting("bodyProfile", "{}"));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  private Double profileWeight(JSONObject p, Store.Weight latest) {
    if (latest != null) return latest.kg;
    return p.has("kg") ? p.optDouble("kg") : null;
  }

  private Double estimatedWorkout(Domain.Session session, JSONObject profile) {
    if (session.calories != null) return session.calories;
    if (profile.optInt("scope", 0) != 0) return null;
    LocalDate today = LocalDate.now();
    return estimatedWorkout(session, true, profileAge(profile, today),
        profileWeight(profile, db.latestWeightOnOrBefore(today)));
  }

  private Double estimatedWorkout(Domain.Session session, boolean estimationAllowed, int age, Double kg) {
    if (session.calories != null) return session.calories;
    if (!estimationAllowed || kg == null || kg < 25 || kg > 300) return null;
    return EnergyMath.workout(session, age, kg);
  }

  private int profileAge(JSONObject p, LocalDate today) {
    try {
      return Period.between(LocalDate.parse(p.getString("birth")), today).getYears();
    } catch (Exception e) {
      return -1;
    }
  }

  private void profileForm() {
    JSONObject p = bodyProfile();
    LinearLayout content = beginForm("bodyProfile", null, -1);
    input(content, "birth", "出生日期（年-月-日）", p.optString("birth", ""), InputType.TYPE_CLASS_DATETIME);

    input(content, "height", "身高（厘米）", p.has("cm") ? String.valueOf(p.optDouble("cm")) : "", 8194);
    Store.Weight latestWeight = db.latestWeightOnOrBefore(LocalDate.now());
    Double weight = profileWeight(p, latestWeight);
    EditText weightField = input(content, "bodyWeight", latestWeight == null ? "初始体重（千克）" : "最近体重 · " + latestWeight.date, weight == null ? "" : String.valueOf(weight), 8194);
    if (latestWeight != null) {
      weightField.setEnabled(false);
      content.addView(link("记录体重", () -> {
        if (formExitRequest != null) formExitRequest.accept(() -> { closeForm(); go(12); weightForm(null); });
      }));
    }
    select(content, "sex", "静息公式使用的生理参数", new String[] {"请选择", "男性", "女性"}, p.optInt("sex", 0));
    select(content, "scope", "适用情况", new String[] {"普通成年人", "孕期、哺乳期或需个别评估"}, p.optInt("scope", 0));
    select(
        content,
        "activity",
        "平时活动量（包含日常运动）",
        new String[] {"久坐为主", "轻度活动", "中等活动", "活动较多"},
        p.optInt("activity", 0));
    content.addView(note("体重更新后，估算随最近一次测量调整。活动量仅用于全天参考，不再叠加运动热量。"));
    showForm(
        "身体资料",
        content,
        () -> {
          String birth = Domain.date(val("birth"), LocalDate.now());
          int age = Period.between(LocalDate.parse(birth), LocalDate.now()).getYears();
          Domain.require(age >= 0 && age <= 120, "请检查出生日期");
          double cm = Domain.number(val("height"), "身高"), kg = Domain.weight(val("bodyWeight"));
          Domain.require(cm >= 50 && cm <= 250, "身高应在50至250厘米之间");
          int sex = choices.get("sex").getSelectedItemPosition();
          Domain.require(sex > 0, "请选择公式参数");
          try {
            JSONObject value =
                new JSONObject()
                    .put("birth", birth)
                    .put("cm", cm)
                    .put("kg", kg)
                    .put("sex", sex)
                    .put("scope", choices.get("scope").getSelectedItemPosition())
                    .put("activity", choices.get("activity").getSelectedItemPosition());
            // Profile weight is the initial value only; subsequent measurements live in the weight
            // log.
            if (!db.weights().isEmpty() && weight != null && Math.abs(kg - weight) > .001)
              throw new IllegalArgumentException("已有体重记录，请在“体重”页面添加新测量；此处保留最近体重");
            db.setSetting("bodyProfile", value.toString());
            closeForm();
            render();
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private String mealFingerprint(LocalDate date, List<JSONObject> meals) {
    StringBuilder data = new StringBuilder(date.toString());
    for (JSONObject meal : meals) data.append(meal.toString());
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void refreshHealth() {
    boolean anyPermission =
        healthData.permitted(this, HealthData.STEPS)
            || healthData.permitted(this, HealthData.ACTIVE)
            || healthData.permitted(this, HealthData.TOTAL);
    HealthData.Snapshot current = healthData.value;
    if (!anyPermission
        && !healthData.loading
        && current.steps == null
        && current.active == null
        && current.total == null
        && current.message.equals("未连接健康数据")) return;
    healthData.refresh(
        this,
        () -> {
          if (!isFinishing() && !isDestroyed() && (page == 18 || page == 0)) render();
        });
  }

  private record EnergyReading(
      JSONObject profile,
      Double resting,
      String reason,
      HealthData.Snapshot snapshot,
      boolean sameDay,
      Double deviceTotal,
      Double deviceActive,
      Instant now,
      EnergyMath.Ledger ledger,
      int excluded) {}

  private EnergyReading energyReading(LocalDate today, List<Domain.Session> workouts) {
    JSONObject profile = bodyProfile();
    Double kg = profileWeight(profile, db.latestWeightOnOrBefore(today)), resting = null;
    int age = profileAge(profile, today);
    String reason = "完善身体资料后，可估算静息消耗";
    try {
      if (profile.optInt("sex") == 0) throw new IllegalArgumentException(reason);
      if (profile.optInt("scope", 0) != 0) throw new IllegalArgumentException("当前情况不使用通用热量公式");
      if (kg == null) throw new IllegalArgumentException("先添加体重");
      resting = EnergyMath.resting(age, profile.optDouble("cm"), kg, profile.optInt("sex") == 1);
    } catch (Exception e) {
      reason = e.getMessage();
    }
    HealthData.Snapshot snapshot = healthData.value;
    boolean sameDay = today.equals(snapshot.date);
    Double
        deviceTotal =
            sameDay && healthData.permitted(this, HealthData.TOTAL) ? snapshot.total : null,
        deviceActive =
            sameDay && healthData.permitted(this, HealthData.ACTIVE) ? snapshot.active : null;
    Instant now = Instant.now(),
        start = today.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    // Align resting estimate to the same snapshot cutoff as device data; don't grow it against
    // stale activity.
    Instant cutoff =
        (deviceTotal != null || deviceActive != null) && snapshot.at != null ? snapshot.at : now;
    double fraction =
        Math.max(
            0,
            Math.min(
                1,
                Duration.between(start, cutoff).toMillis()
                    / (double) Duration.between(start, end).toMillis()));
    double local = 0;
    int excluded = 0;
    String date = today.toString();
    boolean estimationAllowed = profile.optInt("scope", 0) == 0;
    for (Domain.Session q : workouts)
      if (q.date.equals(date)) {
        Double kcal = estimatedWorkout(q, estimationAllowed, age, kg);
        if (kcal != null
            && q.startedWall >= start.toEpochMilli()
            && q.endedWall <= cutoff.toEpochMilli()) local += kcal;
        else excluded++;
      }
    EnergyMath.Ledger ledger =
        EnergyMath.ledger(
            deviceTotal, deviceActive, resting == null ? null : resting * fraction, local);
    return new EnergyReading(
        profile,
        resting,
        reason,
        snapshot,
        sameDay,
        deviceTotal,
        deviceActive,
        now,
        ledger,
        excluded);
  }

  private int energyRange = 7;
  private String energyMetric = "expenditure";

  private void energyExplanation() {
    LinearLayout content = column();
    content.addView(note("消耗估算与设备记录分开标注。缺少设备总消耗或饮食记录未完整时，不计算能量差。"));
    teachingNote(
        content,
        "消耗如何计算",
        "优先采用设备总消耗；否则使用静息估算与设备活动热量，或静息估算与本机运动记录。步数、设备热量和本机训练不会重复相加。只有步数时，不用固定系数换算热量。");
    teachingNote(
        content,
        "估算范围",
        "静息估算使用 Mifflin–St Jeor"
            + " 公式，适用年龄19—78岁。已记录运动使用2024成人运动代谢当量表估算净活动消耗，适用年龄19—59岁。未包含未记录的步行、家务和食物热效应，不能视为全天实测总消耗。");
    teachingNote(
        content,
        "每日记录与图表",
        "每天保留最后一次查看时的数值，截止时间会一同保存；空心点表示没有记录，不代表0。图表不是全天最终结算。自定义动作、未知强度和缺少时长的项目不自动估算热量。");
    teachingNote(
        content, "全天参考", "静息估算乘以日常活动系数，作为粗略维持体重参考；它已包含平时活动，不叠加到能量差中。实际消耗会因个体差异和记录完整程度而变化。");
    action(
        content,
        "静息公式来源",
        () -> openSource("https://www.sciencedirect.com/science/article/pii/S0002916523166986"),
        false);
    action(
        content, "运动代谢当量来源", () -> openSource("https://pacompendium.com/adult-compendium/"), false);
    new GlassDialogBuilder()
        .setTitle("能量与步数说明")
        .setView(content)
        .setNegativeButton("关闭", null)
        .show();
  }

  private LinearLayout energyTrend(LocalDate today, List<JSONObject> recentDays) {
    LinearLayout card = panel();
    card.addView(text("每日趋势", 18, ink, true));
    String[] keys = {"expenditure", "intake", "steps"};
    card.addView(
        segments(
            new String[] {"消耗", "摄入", "步数"},
            Arrays.asList(keys).indexOf(energyMetric),
            index -> {
              energyMetric = keys[index];
              render();
            },
            "趋势指标"));
    card.addView(
        segments(
            new String[] {"近7天", "近30天"},
            energyRange == 7 ? 0 : 1,
            index -> {
              energyRange = index == 0 ? 7 : 30;
              render();
            },
            "趋势范围"));
    Map<String, JSONObject> days = new HashMap<>();
    for (JSONObject day : recentDays) days.put(day.optString("date"), day);
    List<String> labels = new ArrayList<>(), dates = new ArrayList<>();
    List<Double> values = new ArrayList<>();
    for (int i = energyRange - 1; i >= 0; i--) {
      LocalDate date = today.minusDays(i);
      dates.add(date.toString());
      labels.add(date.getMonthValue() + "/" + date.getDayOfMonth());
      JSONObject day = days.get(date.toString());
      values.add(day == null || day.isNull(energyMetric) ? null : day.optDouble(energyMetric));
    }
    TextView selected = text("", 25, ink, true), detail = note("");
    selected.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    card.addView(selected);
    card.addView(detail);
    java.util.function.IntConsumer choose =
        index -> {
          String date = dates.get(index);
          Double value = values.get(index);
          selected.setText(
              value == null
                  ? "暂无记录"
                  : String.format(
                      Locale.CHINA, "%,.0f %s", value, energyMetric.equals("steps") ? "步" : "千卡"));
          JSONObject day = days.get(date);
          String timeKey =
              energyMetric.equals("steps")
                  ? "stepsUpdated"
                  : energyMetric.equals("expenditure") ? "expenditureUpdated" : "updated";
          long cutoff = day == null ? 0 : day.optLong(timeKey, 0);
          String timeLabel = "截至";
          if (day != null && value != null && cutoff <= 0) {
            cutoff = day.optLong("updated", 0);
            timeLabel = "记录于";
          }
          detail.setText(
              date
                  + (value == null || cutoff <= 0
                      ? ""
                      : " · "
                          + timeLabel
                          + " "
                          + DateTimeFormatter.ofPattern("HH:mm")
                              .withZone(ZoneId.systemDefault())
                              .format(Instant.ofEpochMilli(cutoff))));
        };
    choose.accept(values.size() - 1);
    card.addView(
        new HealthCharts.DailyBars(
            this,
            labels,
            values,
            energyMetric.equals("steps")
                ? teal()
                : energyMetric.equals("intake") ? purple() : orange(),
            muted,
            choose),
        new LinearLayout.LayoutParams(
            -1, d(values.stream().anyMatch(Objects::nonNull) ? 170 : 96)));
    return card;
  }

  private void energyScreen() {
    LocalDate today = LocalDate.now();
    body.addView(note(today.format(DateTimeFormatter.ofPattern("M月d日，EEEE", Locale.CHINA))));
    LinearLayout heading = row();
    TextView energyTitle = text("每日能量", 34, ink, true);
    energyTitle.setPadding(0, 0, 0, d(6));
    heading.addView(energyTitle, new LinearLayout.LayoutParams(0, -2, 1));
    TextView info = link("ⓘ", this::energyExplanation);
    info.setContentDescription("能量与步数说明");
    heading.addView(info, new LinearLayout.LayoutParams(d(48), d(48)));
    body.addView(heading);
    FoodDay food = foodDay(today);
    EnergyReading reading = energyReading(today, db.workoutsBetween(today, today.plusDays(1)));
    Double delta = energyDelta(reading, food);
    saveEnergyDay(reading, food, delta);
    List<JSONObject> days = db.energyDays();
    LinearLayout overview = panel();
    overview.addView(text(reading.deviceTotal() == null ? "消耗估算" : "设备总消耗", 14, orange(), true));
    overview.addView(
        metric(
            reading.ledger().kcal() == null
                ? "—"
                : String.format(Locale.CHINA, "%,.0f", reading.ledger().kcal()),
            "千卡",
            38));
    overview.addView(
        note(
            "截至 "
                + DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(
                        (reading.deviceTotal() != null || reading.deviceActive() != null)
                                && reading.snapshot().at != null
                            ? reading.snapshot().at
                            : reading.now())));
    overview.addView(text(energyDifference(delta), 14, muted, false));
    body.addView(overview);
    body.addView(energyTrend(today, days));
    LinearLayout meals = panel();
    meals.addView(text("饮食摄入", 17, ink, true));
    Double intake = food.energy().intake();
    meals.addView(
        metric(intake == null ? "—" : String.format(Locale.CHINA, "%,.0f", intake), "千卡", 30));
    boolean complete = food.complete();
    boolean hasToday = food.meals() > 0;
    meals.addView(note(!hasToday ? "暂无饮食记录" : complete ? (food.completeAt().isEmpty() ? "饮食完整性已确认" : "截至" + food.completeAt() + "已确认记全") : "饮食完整性未确认"));
    action(meals, "去记饮食", () -> go(9), false);
    int unknownCount = food.energy().unknown();
    if (unknownCount > 0) meals.addView(note(unknownCount + "项食物热量未知"));
    if (hasToday)
      action(meals, complete ? "撤销完整确认" : "确认截至目前已记全", () -> {
        LocalDate currentDay = LocalDate.now();
        List<JSONObject> currentMeals = db.meals(currentDay);
        if (!today.equals(currentDay) || currentMeals.isEmpty()) { render(); return; }
        db.setSetting("foodComplete", complete ? "" : mealFingerprint(currentDay, currentMeals));
        db.setSetting("foodCompleteAt", complete ? "" : java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        render();
      }, false);
    body.addView(meals);
    HealthData.Snapshot snapshot = reading.snapshot();
    LinearLayout device = panel();
    device.addView(text("步数", 17, ink, true));
    device.addView(
        metric(
            reading.sameDay()
                    && healthData.permitted(this, HealthData.STEPS)
                    && snapshot.steps != null
                ? String.format(Locale.CHINA, "%,d", snapshot.steps)
                : "—",
            "步",
            32));
    device.addView(note(healthData.loading ? "正在读取" : snapshot.message));
    if (snapshot.at != null && reading.sameDay())
      device.addView(
          note(
              "更新于 "
                  + DateTimeFormatter.ofPattern("HH:mm")
                      .withZone(ZoneId.systemDefault())
                      .format(snapshot.at)));
    boolean connected =
        healthData.permitted(this, HealthData.STEPS)
            || healthData.permitted(this, HealthData.ACTIVE)
            || healthData.permitted(this, HealthData.TOTAL);
    action(
        device,
        connected ? "刷新数据" : "连接健康数据",
        connected ? this::refreshHealth : () -> healthData.request(this),
        !connected);
    device.addView(link("数据来源与权限", this::healthAccessSheet));
    body.addView(device);
    LinearLayout reference = panel();
    reference.addView(text("全天参考", 17, ink, true));
    if (reading.resting() != null) {
      double pal =
          new double[] {1.2, 1.375, 1.55, 1.725}
              [Math.max(0, Math.min(3, reading.profile().optInt("activity")))];
      reference.addView(
          metric(String.format(Locale.CHINA, "%,.0f", reading.resting() * pal), "千卡/天", 30));
      reference.addView(note("维持体重的估算参考"));
    } else reference.addView(note(reading.reason()));
    action(reference, "身体资料", this::profileForm, false);
    action(reference, "记录体重", () -> go(12), false);
    body.addView(reference);
    sectionAction("每日记录", "查看全部", this::energyHistory);
    if (days.isEmpty()) body.addView(note("记录饮食或同步健康数据后，可在这里查看。"));
    for (JSONObject day : days.subList(0, Math.min(3, days.size())))
      body.addView(energyDayCard(day));
  }

  private void openSource(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception e) {
      toast("未找到浏览器");
    }
  }

  private void foodPicker(EditText target) {
    LinearLayout content = column();
    EditText search = new EditText(this);
    search.setSingleLine(true); search.setTextColor(ink); search.setHintTextColor(muted);
    search.setHint("搜索食物"); search.setPadding(d(12), d(12), d(12), d(12));
    search.setBackground(AppViews.round(soft, d(HealthDesign.INPUT))); search.setMinHeight(d(52));
    content.addView(search);
    String[] groups = {"全部", "已选", "本餐次最近", "全部餐次最近", "主食", "蔬菜", "水果", "肉禽", "蛋类", "水产", "奶及奶制品", "豆及豆制品", "坚果种子", "油脂调味", "饮料", "菜肴及其他", "生重/原料"};
    final Runnable[] refreshFood = new Runnable[1];
    final String slotContext = target != null && choices.containsKey("slot") ? String.valueOf(choices.get("slot").getSelectedItem()) : "";
    final String[] selectedGroup = {"全部"};
    HorizontalScrollView groupScroll = new HorizontalScrollView(this); groupScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout groupRow = row();
    for (String group : groups) {
      if (slotContext.isEmpty() && group.equals("本餐次最近")) continue;
      TextView filter = link(group, () -> {
        selectedGroup[0] = group;
        for (int i = 0; i < groupRow.getChildCount(); i++) {
          View child = groupRow.getChildAt(i);
          if (child instanceof TextView label) label.setTextColor(group.equals(label.getText().toString()) ? accent : muted);
        }
        if (refreshFood[0] != null) refreshFood[0].run();
      });
      filter.setTextColor(group.equals("全部") ? accent : muted);
      filter.setMinHeight(touchTarget()); filter.setContentDescription("筛选" + group); groupRow.addView(filter);
    }
    groupScroll.addView(groupRow); content.addView(groupScroll);
    TextView selectedLabel = note(""); content.addView(selectedLabel);
    // The dialog already scrolls its content. Keep one vertical scroller so a
    // drag on an item scrolls the same surface as the search and filters.
    LinearLayout rows = column();
    content.addView(rows);
    EditText manualDraft = new EditText(this);
    LinkedHashMap<String, JSONObject> chosen = new LinkedHashMap<>();
    LinkedHashMap<String, Double> chosenGrams = new LinkedHashMap<>();
    Set<String> explicitlyEditedGrams = new HashSet<>();
    Map<String, Double> existingGrams = new HashMap<>();
    Map<String, JSONObject> existingFoods = new HashMap<>();
    if (target != null) for (String line : target.getText().toString().split("\\n")) if (!line.isBlank()) {
      try {
        JSONObject item = foodCatalog.parse(line).getJSONObject(0);
        String existingId = item.optString("foodId", "manual:" + item.optString("name"));
        existingGrams.put(existingId, item.getDouble("grams"));
        existingFoods.put(existingId, item);
      } catch (Exception ignored) { /* The existing form retains and validates its input. */ }
    }
    AlertDialog picker = new GlassDialogBuilder().setTitle(target == null ? "食物库" : "选择食物")
        .setView(content).setNegativeButton("取消", null)
        .setPositiveButton(target == null ? "关闭" : "添加到本餐", null).create();
    Runnable update = () -> {
      rows.removeAllViews();
      String group = selectedGroup[0];
      List<JSONObject> results = foodCatalog.search(search.getText().toString());
      if (group.equals("已选")) {
        results = new ArrayList<>(chosen.values());
        String q = FoodCatalog.normalized(search.getText().toString());
        if (!q.isEmpty()) results.removeIf(f -> !FoodCatalog.normalized(f.optString("name")).contains(q));
      } else if (group.contains("最近")) {
        String slot = slotContext;
        List<JSONObject> meals = new ArrayList<>(db.meals());
        meals.sort((x,y) -> Long.compare(y.optLong("savedAt"), x.optLong("savedAt")));
        LinkedHashSet<String> recent = new LinkedHashSet<>();
        for (JSONObject meal : meals) {
          if (group.equals("本餐次最近") && !slot.equals(meal.optString("slot"))) continue;
          JSONArray foods = meal.optJSONArray("foods");
          if (foods != null) for (int i=0; i<foods.length(); i++) {
            JSONObject f = foods.optJSONObject(i); if (f != null) recent.add(f.optString("name"));
          }
        }
        results.removeIf(f -> !recent.contains(f.optString("name")));
        for (JSONObject meal : meals) {
          if (group.equals("本餐次最近") && !slot.equals(meal.optString("slot"))) continue;
          JSONArray saved = meal.optJSONArray("foods"); if (saved == null) continue;
          for (int i = 0; i < saved.length(); i++) {
            JSONObject f = saved.optJSONObject(i); if (f == null) continue;
            String query = FoodCatalog.normalized(search.getText().toString());
            String hay = FoodCatalog.normalized(f.optString("name")); boolean matches = true;
            for (String word : query.split("\\s+")) if (!hay.contains(word)) { matches = false; break; }
            if (matches && results.stream().noneMatch(x -> x.optString("name").equals(f.optString("name")))) results.add(f);
          }
        }
        results.sort(Comparator.comparingInt(f -> new ArrayList<>(recent).indexOf(f.optString("name"))));
      } else if (group.equals("生重/原料")) results.removeIf(f -> !FoodCatalog.rawIngredient(f));
      else {
        boolean explicitRaw = search.getText().toString().contains("生") || search.getText().toString().contains("原料");
        results.removeIf(f -> (!explicitRaw && FoodCatalog.rawIngredient(f)) || (!group.equals("全部") && !FoodCatalog.category(f).equals(group)));
      }
      if (group.equals("已选") && !manualDraft.getText().toString().isBlank()) {
        for (String line : manualDraft.getText().toString().split("\\n")) if (!line.isBlank()) {
          String name = line.split("[，,]", -1)[0].trim();
          LinearLayout item = row(); item.addView(text(name + " · 手动添加", 17, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
          item.addView(link("改份量", () -> {
            String[] parts = line.split("[，,]", -1);
            EditText grams = new EditText(this); grams.setInputType(8194); grams.setText(parts[1]);
            AlertDialog amount = new GlassDialogBuilder().setTitle("修改份量").setView(grams)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            amount.show(); amount.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
              try {
                double g = Domain.number(grams.getText().toString(), "食用量");
                Domain.require(g > 0 && g <= 10000, "食用量应大于0且不超过10000克");
                List<String> lines = new ArrayList<>(Arrays.asList(manualDraft.getText().toString().split("\\n")));
                int index = lines.indexOf(line); Domain.require(index >= 0, "食物已变化，请重新打开");
                parts[1] = String.valueOf(g); lines.set(index, String.join("，", parts));
                manualDraft.setText(String.join("\n", lines)); amount.dismiss();
              } catch (Exception error) { toast(error.getMessage()); }
            });
          }));
          item.addView(link("移除", () -> {
            List<String> remaining = new ArrayList<>(Arrays.asList(manualDraft.getText().toString().split("\\n")));
            remaining.remove(line); manualDraft.setText(String.join("\n", remaining));
          }));
          rows.addView(item);
        }
      }
      if (results.isEmpty() && !(group.equals("已选") && !manualDraft.getText().toString().isBlank())) {
        rows.addView(note("未找到食物"));
        if (target != null) rows.addView(link("手动添加", () -> manualFoodForm(manualDraft)));
      }
      for (JSONObject food : results) {
        String catalogName = food.optString("name"), id = food.optString("foodId", food.optString("id", "manual:" + catalogName));
        JSONObject displayFood = existingFoods.getOrDefault(id, food);
        String name = displayFood.optString("name");
        LinearLayout foodRow = row(); foodRow.setPadding(0, d(12), 0, d(12));
        foodRow.addView(new GuideImages(this, true, foodCatalog.imageIndex(name)), new LinearLayout.LayoutParams(d(64), d(64)));
        LinearLayout words = column(); words.setPadding(d(12), 0, 0, 0);
        words.addView(text(name, 17, ink, true));
        words.addView(note((existingFoods.containsKey(id) ? "本餐已记录 · " : "") + (FoodCatalog.rawIngredient(displayFood) ? "烹饪前称重 · " : "") + (displayFood.has("kcal100") && !displayFood.isNull("kcal100") ? displayFood.optDouble("kcal100") + " 千卡 / 100克" : "热量未提供")));
        final TextView[] portion = new TextView[1];
        if (target != null) {
          portion[0] = link("份量 " + chosenGrams.getOrDefault(id, 100.0) + "克", () -> {
            EditText grams = new EditText(this); grams.setInputType(8194); grams.setText(String.valueOf(chosenGrams.getOrDefault(id, 100.0)));
            LinearLayout amountForm = column(); amountForm.addView(note("食用量（克）")); amountForm.addView(grams);
            addHouseholdReferences(amountForm, food, grams); addPortionPreview(amountForm, displayFood, grams);
            AlertDialog amount = new GlassDialogBuilder().setWholeContentScrollable().setTitle("修改份量").setView(amountForm).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            amount.show(); amount.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
              try { double g = Domain.number(grams.getText().toString(), "食用量"); Domain.require(g > 0 && g <= 10000, "食用量应大于0且不超过10000克"); chosenGrams.put(id, g); explicitlyEditedGrams.add(id); amount.dismiss(); portion[0].setText("份量 " + g + "克"); }
              catch (Exception error) { toast(error.getMessage()); }
            });
          });
          portion[0].setVisibility(chosen.containsKey(id) ? View.VISIBLE : View.GONE);
          words.addView(portion[0]);
        }
        addShortHouseholdReference(words, food);
        foodRow.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (target != null) {
          CheckBox selected = new CheckBox(this); selected.setChecked(chosen.containsKey(id));
          selected.setContentDescription("选择" + name); selected.setMinWidth(touchTarget()); selected.setMinHeight(touchTarget());
          selected.setOnCheckedChangeListener((button, checked) -> {
            if (checked) { chosen.put(id, food); chosenGrams.putIfAbsent(id, existingGrams.getOrDefault(id, 100.0)); } else { chosen.remove(id); chosenGrams.remove(id); explicitlyEditedGrams.remove(id); }
            portion[0].setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) portion[0].setText("份量 " + chosenGrams.get(id) + "克");
            int manualCount = manualDraft.getText().toString().isBlank() ? 0 : manualDraft.getText().toString().split("\\n").length;
            selectedLabel.setText("已选" + (chosen.size() + manualCount) + "项，可在添加后修改份量");
          });
          foodRow.addView(selected);
        }
        if (target == null) foodRow.setOnClickListener(v -> {
          String source = food.optString("source", "来源未提供");
          LinearLayout details = column();
          details.addView(note(source + (food.has("kcal100") ? " · 每100克 " + food.optString("kcal100") + " 千卡" : " · 热量未提供")));
          addHouseholdReferences(details, food, null);
          new GlassDialogBuilder().setWholeContentScrollable().setTitle(name).setView(details)
              .setNegativeButton("返回", null).setPositiveButton("记一餐", (d,w) -> {
                try {
                  JSONObject item = new JSONObject(food.toString()); item.put("grams", 100);
                  if (item.has("id")) item.put("foodId", item.getString("id"));
                  if (item.has("url")) item.put("sourceUrl", item.getString("url"));
                  if (item.has("id")) item.put("sourceVersion", "USDA-SR-2018-curated-2026-10-27");
                  item.put("preparation", item.optString("name"));
                  item.put("category", FoodCatalog.category(item));
                  if (item.has("kcal100")) item.put("kcal", item.getDouble("kcal100"));
                  recognizedDraft = new JSONObject().put("foods", new JSONArray().put(item));
                  selectedPhoto = null; // This entry starts a food-only meal.
                  picker.dismiss(); mealForm(null);
                } catch (JSONException error) { toast("食物信息读取失败"); }
              }).show();
        });
        rows.addView(foodRow);
      }
      int manualCount = manualDraft.getText().toString().isBlank() ? 0 : manualDraft.getText().toString().split("\\n").length;
      selectedLabel.setText(target == null ? "每100克可食部分" : "已选" + (chosen.size() + manualCount) + "项，默认100克，可修改");
      if (target != null && picker.isShowing() && picker.getButton(AlertDialog.BUTTON_POSITIVE) != null)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setText("添加到" + (slotContext.isEmpty() ? "本餐" : slotContext));
    };
    refreshFood[0] = update;
    manualDraft.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
      public void onTextChanged(CharSequence text, int start, int before, int count) { if (refreshFood[0] != null) refreshFood[0].run(); }
      public void afterTextChanged(android.text.Editable text) {}
    });
    search.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
      public void onTextChanged(CharSequence text, int start, int before, int count) { update.run(); }
      public void afterTextChanged(android.text.Editable text) {}
    });
    picker.show();
    picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (target == null) { picker.dismiss(); return; }
      LinkedHashMap<String,String> lines = new LinkedHashMap<>();
      for (String line : target.getText().toString().split("\\n")) if (!line.isBlank()) {
        try {
          JSONObject old = foodCatalog.parse(line).getJSONObject(0);
          lines.put(old.optString("foodId", "manual:" + old.optString("name")), line);
        } catch (Exception error) { toast("已有食物信息需要先修正"); return; }
      }
      for (String line : manualDraft.getText().toString().split("\\n")) if (!line.isBlank()) {
        try { JSONObject entry = foodCatalog.parse(line).getJSONObject(0); lines.put("manual:" + entry.optString("name"), line); }
        catch (Exception error) { toast("手动食物信息需要修正"); return; }
      }
      for (java.util.Map.Entry<String, JSONObject> selection : chosen.entrySet()) {
        JSONObject f = selection.getValue();
        try {
          JSONObject item = new JSONObject(f.toString());
          if (item.has("id") && !item.has("foodId")) item.put("foodId", item.getString("id"));
          if (item.has("url") && !item.has("sourceUrl")) item.put("sourceUrl", item.getString("url"));
          if (item.has("id") && !item.has("sourceVersion")) item.put("sourceVersion", "USDA-SR-2018-curated-2026-10-27");
          if (!item.has("preparation")) item.put("preparation", item.optString("name"));
          item.put("grams", chosenGrams.getOrDefault(selection.getKey(), 100.0));
          item.put("category", FoodCatalog.category(item));
          if (lines.containsKey(selection.getKey()) && explicitlyEditedGrams.contains(selection.getKey())) {
            String[] existing = lines.get(selection.getKey()).split("[，,]", -1);
            existing[1] = String.valueOf(chosenGrams.get(selection.getKey()));
            lines.put(selection.getKey(), String.join("，", existing));
          } else lines.putIfAbsent(selection.getKey(), foodCatalog.snapshotLine(item));
        } catch (JSONException error) { toast("食物信息读取失败"); return; }
      }
      if (lines.size() > 30) { toast("每餐最多30项食物"); return; }
      target.setText(String.join("\n", lines.values())); picker.dismiss();
    });
    update.run();
  }

  private void routePanel(Domain.Session q, List<RouteTrack.Point> points) {
    LinearLayout card = panel();
    card.addView(text("旧版记录的运动路线", 18, ink, true));
    card.addView(text(String.format(Locale.CHINA, "%.2f 公里", q.route.meters / 1000), 28, accent, true));
    card.addView(new RouteView(this, points, accent, line), new LinearLayout.LayoutParams(-1, d(180)));
    action(card, "导出GPX路线", () -> exportRoute(q), false);
    body.addView(card);
  }

  private void exportRoute(Domain.Session q) {
    task(
        () -> {
          List<RouteTrack.Point> points = db.routePoints(q.id);
          Domain.require(!points.isEmpty(), "暂无有效定位点");
          File dir = new File(getCacheDir(), "shared");
          dir.mkdirs();
          File out = new File(dir, "route-" + q.id + ".gpx");
          try (java.io.Writer writer =
              new java.io.OutputStreamWriter(
                  new FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\""
                    + " creator=\"LianYouDu\""
                    + " xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>运动路线</name>");
            int segment = -1;
            for (RouteTrack.Point p : points) {
              if (p.segment != segment) {
                if (segment >= 0) writer.write("</trkseg>");
                writer.write("<trkseg>");
                segment = p.segment;
              }
              writer.write(
                  String.format(
                      Locale.ROOT,
                      "<trkpt lat=\"%.8f\" lon=\"%.8f\"><time>%s</time></trkpt>",
                      p.latitude,
                      p.longitude,
                      java.time.Instant.ofEpochMilli(p.wall)));
            }
            writer.write("</trkseg></trk></gpx>");
          }
          return out;
        },
        f -> share(f, "application/gpx+xml"));
  }
}
