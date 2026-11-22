package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Search and batch selection with explicit, editable quantities. No implicit plan changes. */
final class ExercisePicker {
  interface Commit {
    void add(List<Domain.Exercise> exercises);
  }

  final Activity activity;
  final boolean dark;
  final int ink, muted, accent, soft, line;
  final String scene;
  final int target;
  final Bitmap equipment;
  final Store store;
  final Commit commit;
  final Runnable custom;
  final Set<String> existing;
  final List<Domain.Exercise> catalog;
  final LinkedHashMap<String, Domain.Exercise> chosen = new LinkedHashMap<>();
  final List<Dialog> owned;
  AlertDialog dialog, detail;
  EditText search, detailSets, detailAmount, detailWeight, detailRest;
  Bundle draft;
  LinearLayout rows, filters;
  ScrollView list;
  Button add;
  String category = "全部", query = "";
  Domain.Exercise editing;
  boolean closed;

  ExercisePicker(
      Activity a,
      Store store,
      String scene,
      int target,
      boolean dark,
      int ink,
      int muted,
      int accent,
      int soft,
      int line,
      Bitmap equipment,
      Set<String> existing,
      List<Dialog> owned,
      Commit commit,
      Runnable custom,
      Bundle saved) {
    activity = a;
    this.store = store;
    this.scene = scene;
    this.target = target;
    this.dark = dark;
    this.ink = ink;
    this.muted = muted;
    this.accent = accent;
    this.soft = soft;
    this.line = line;
    this.equipment = equipment;
    this.existing = existing;
    this.owned = owned;
    this.commit = commit;
    this.custom = custom;
    catalog = store.exerciseLibrary();
    List<Domain.Exercise> personalPlan = store.plan("all");
    for (int i = 0; i < catalog.size(); i++) {
      Domain.Exercise e = catalog.get(i);
      try {
        String json = store.setting("exercise.last." + scene + "." + e.id, "");
        if (!json.isEmpty()) {
          Domain.Exercise prior = Store.exercise(new JSONObject(json));
          // Catalog owns name, equipment and supported record type; last values supply quantities.
          e.sets = prior.sets;
          e.reps = prior.reps;
          e.seconds = prior.seconds;
          e.restSeconds = prior.restSeconds;
          e.weight = prior.weight;
          e.validate();
        }
      } catch (Exception ignored) {
      }
      catalog.set(i, ExerciseChoices.preferred(e, personalPlan));
    }
    if (saved != null) {
      draft = saved.getBundle("draft");
      query = saved.getString("query", "");
      category = saved.getString("category", "全部");
      try {
        JSONArray values = new JSONArray(saved.getString("chosen", "[]"));
        for (int i = 0; i < values.length(); i++) {
          Domain.Exercise e = Store.exercise(values.getJSONObject(i));
          if (catalog.stream().anyMatch(c -> c.id.equals(e.id)) && !exists(e)) chosen.put(e.id, e);
        }
        if (saved.containsKey("editing"))
          editing = Store.exercise(new JSONObject(saved.getString("editing")));
      } catch (Exception ignored) {
      }
    }
  }

  boolean exists(Domain.Exercise e) {
    return existing.contains(e.name)
        || (!e.id.equals("custom") && existing.contains(ExerciseChoices.key(e)));
  }

  int dp(float n) {
    return AppViews.dp(activity, n);
  }

  TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(activity);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
    return t;
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(activity);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  void track(Dialog d) {
    owned.add(d);
    d.setOnDismissListener(
        x -> {
          owned.remove(d);
          if (d == detail) {
            editing = null;
            draft = null;
            detailSets = detailAmount = detailWeight = null;
          }
        });
  }

  void show() {
    LinearLayout content = column();
    search = new EditText(activity);
    search.setSingleLine(true);
    search.setTextSize(15);
    search.setTextColor(ink);
    search.setHintTextColor(muted);
    search.setHint("搜索运动或器材");
    search.setContentDescription("搜索运动或器材");
    search.setPadding(dp(14), 0, dp(12), 0);
    search.setBackground(AppViews.round(HealthDesign.well(dark), dp(HealthDesign.INPUT)));
    Visuals.Icon icon = new Visuals.Icon("search", muted);
    icon.setBounds(0, 0, dp(19), dp(19));
    search.setCompoundDrawables(icon, null, null, null);
    search.setCompoundDrawablePadding(dp(10));
    content.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
    HorizontalScrollView horizontal = new HorizontalScrollView(activity);
    horizontal.setHorizontalScrollBarEnabled(false);
    filters = row();
    filters.setPadding(0, dp(12), 0, dp(12));
    horizontal.addView(filters);
    content.addView(horizontal);
    list = new ScrollView(activity);
    list.setFillViewport(false);
    list.setVerticalScrollBarEnabled(false);
    rows = column();
    list.addView(rows);
    int available = activity.getResources().getDisplayMetrics().heightPixels;
    content.addView(
        list, new LinearLayout.LayoutParams(-1, Math.min(dp(330), (int) (available * .34f))));
    TextView hint = text("点选运动；需要修改数量时点“调整”", 12, muted, false);
    hint.setPadding(0, dp(8), 0, 0);
    content.addView(hint);
    dialog =
        new DesignDialog.Builder(activity, dark, ink, muted, accent, soft)
            .setTitle("添加运动")
            .setView(content)
            .setNegativeButton("取消", null)
            .setNeutralButton(
                "自定义",
                (d, w) -> {
                  closed = true;
                  custom.run();
                })
            .setPositiveButton("添加", null)
            .create();
    track(dialog);
    dialog.setOnShowListener(
        d -> {
          add = dialog.getButton(-1);
          add.setOnClickListener(
              v -> {
                if (chosen.isEmpty()) return;
                try {
                  commit.add(new ArrayList<>(chosen.values()));
                  // Quantity preferences are written by the caller's transaction together with the
                  // addition.
                  closed = true;
                  dialog.dismiss();
                } catch (Exception e) {
                  Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
                }
              });
          refreshCount();
        });
    search.setText(query);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            query = s.toString();
            list.scrollTo(0, 0);
            renderRows();
          }

          public void afterTextChanged(Editable e) {}
        });
    dialog.show();
    renderFilters();
    renderRows();
    if (editing != null) {
      Domain.Exercise restore = editing;
      editing = null;
      dialog.getWindow().getDecorView().post(() -> adjust(restore));
    }
  }

  void refreshCount() {
    if (add != null) {
      add.setText(chosen.isEmpty() ? "添加" : "添加（" + chosen.size() + "）");
      add.setContentDescription("添加所选运动");
      add.setStateDescription("已选" + chosen.size() + "项");
      add.setEnabled(!chosen.isEmpty());
      add.setAlpha(chosen.isEmpty() ? .45f : 1f);
    }
  }

  void renderFilters() {
    filters.removeAllViews();
    int touch = (int) Math.ceil(48f * activity.getResources().getDisplayMetrics().density);
    for (String name : new String[] {"全部", "收藏", "力量", "有氧", "核心", "拉伸", "徒手", "器械", "计时", "已选"}) {
      TextView chip = text(name, 12, category.equals(name) ? accent : muted, category.equals(name));
      chip.setPadding(dp(14), dp(10), dp(14), dp(10));
      chip.setMinHeight(touch);
      chip.setMinWidth(touch);
      chip.setGravity(Gravity.CENTER);
      chip.setBackground(
          AppViews.button(category.equals(name) ? soft : Color.TRANSPARENT, accent, dp(22)));
      chip.setContentDescription("分类：" + name);
      chip.setSelected(category.equals(name));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.rightMargin = dp(6);
      filters.addView(chip, lp);
      chip.setOnClickListener(
          v -> {
            category = name;
            list.scrollTo(0, 0);
            renderFilters();
            renderRows();
          });
    }
  }

  void renderRows() {
    int savedScroll = list.getScrollY();
    rows.removeAllViews();
    String q = query.trim().toLowerCase(Locale.ROOT);
    int count = 0;
    for (Domain.Exercise base : catalog) {
      if (!ExerciseCatalog.matches(
          base,
          q,
          category,
          "全部部位",
          "全部器材",
          ExerciseCatalog.favorites(store.setting("exercise.favorites", "")))) continue;
      if (category.equals("已选") && !chosen.containsKey(base.id)) continue;
      count++;
      boolean selected = chosen.containsKey(base.id), exists = exists(base);
      Domain.Exercise e = selected ? chosen.get(base.id) : base;
      LinearLayout card = row();
      card.setPadding(dp(10), dp(12), dp(10), dp(12));
      GradientDrawable bg =
          AppViews.round(selected ? soft : HealthDesign.card(dark), dp(HealthDesign.CARD));

      card.setBackground(bg);
      card.addView(
          GuideImages.exercise(activity, base), new LinearLayout.LayoutParams(dp(58), dp(58)));
      LinearLayout labels = column();
      labels.setPadding(dp(12), 0, dp(8), 0);
      labels.addView(text(e.name, 15, ink, true));
      TextView description = text(exists ? "已添加" : description(e), 12, muted, false);
      description.setPadding(0, dp(7), 0, 0);
      labels.addView(description);
      card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      TextView adjust = text("调整", 12, accent, true);
      adjust.setGravity(Gravity.CENTER);
      adjust.setMinHeight(dp(48));
      adjust.setMinWidth(dp(48));
      adjust.setContentDescription("调整" + e.name);
      adjust.setOnClickListener(v -> adjust(e.copy()));
      if (!exists) card.addView(adjust);
      TextView check =
          text(exists || selected ? "✓" : "＋", selected ? 18 : 20, selected ? accent : muted, true);
      check.setGravity(Gravity.CENTER);
      check.setBackground(
          AppViews.round(selected ? HealthDesign.card(dark) : Color.TRANSPARENT, dp(17)));
      card.addView(check, new LinearLayout.LayoutParams(dp(34), dp(34)));
      card.setAlpha(exists ? .5f : 1f);
      card.setContentDescription(e.name + (exists ? "，已添加" : selected ? "，已选择" : ""));
      card.setSelected(selected);
      card.setFocusable(!exists);
      card.setClickable(!exists);
      if (!exists)
        card.setOnClickListener(
            v -> {
              if (chosen.remove(e.id) == null) chosen.put(e.id, e.copy());
              card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
              renderRows();
              refreshCount();
            });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = dp(8);
      rows.addView(card, lp);
    }
    list.post(() -> list.scrollTo(0, savedScroll));
    if (count == 0) {
      TextView empty =
          text(
              category.equals("已选")
                  ? "还未选择运动，可返回“全部”选择。"
                  : category.equals("收藏") ? "还没有收藏动作，可在运动库中收藏。" : "没有找到运动，试试其他名称。",
              14,
              muted,
              false);
      empty.setPadding(dp(12), dp(40), dp(12), dp(40));
      rows.addView(empty);
    }
  }

  static String description(Domain.Exercise e) {
    if (e.id.equals("stair_climb")) return "台阶数 · 时长" + (e.goalSeconds == null ? "" : " · 目标 " + Domain.clock(e.goalSeconds * 1000L));
    if (e.id.equals("incline_treadmill")) return "坡度 · 距离 · 时长" + (e.goalSeconds == null ? "" : " · 目标 " + Domain.clock(e.goalSeconds * 1000L));
    if (e.distanceBased()) return "距离 · 时长 · 均速";
    return e.sets
        + "组 · "
        + (e.timed
            ? Domain.clock(e.seconds * 1000L)
            : e.reps + (ExerciseCatalog.perSide(e) ? "次/侧" : "次"))
        + (e.unit.isEmpty() ? "" : e.weight > 0 ? " · " + ExerciseCatalog.weightLabel(e) + " " + e.weight + "千克" : " · 未加重量")
        + restSummary(e);
  }

  static String restSummary(Domain.Exercise e) {
    if (e.distanceBased() || e.sets < 2) return "";
    return e.restSeconds == 0 ? " · 组间不休息" : " · 组间休息 " + e.restSeconds + " 秒";
  }

  interface Change {
    void value(String text);
  }

  EditText stepper(
      LinearLayout parent, String title, String value, boolean decimal, Change change) {
    return stepper(parent, title, value, decimal, 1, change);
  }

  private EditText stepper(LinearLayout parent, String title, String value, boolean decimal,
      int minimum, Change change) {
    TextView label = text(title, 13, muted, false);
    label.setPadding(0, dp(14), 0, dp(8));
    parent.addView(label);
    LinearLayout control = row();
    control.setBackground(AppViews.round(HealthDesign.well(dark), dp(HealthDesign.INPUT)));
    TextView minus = text("−", 24, accent, true), plus = text("＋", 23, accent, true);
    for (TextView v : new TextView[] {minus, plus}) {
      v.setGravity(Gravity.CENTER);
      v.setMinHeight(dp(52));
    }
    minus.setContentDescription("减少" + title);
    plus.setContentDescription("增加" + title);
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setInputType(decimal ? 8194 : 2);
    input.setText(value);
    input.setTextColor(ink);
    input.setTextSize(23);
    input.setGravity(Gravity.CENTER);
    input.setSelectAllOnFocus(true);
    input.setContentDescription(title);
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setPadding(0, dp(8), 0, dp(8));
    control.addView(minus, new LinearLayout.LayoutParams(dp(52), dp(52)));
    control.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
    control.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(52)));
    minus.setOnClickListener(v -> step(input, -1, decimal, minimum));
    plus.setOnClickListener(v -> step(input, 1, decimal, minimum));
    parent.addView(control);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int b, int c) {
            change.value(s.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
    return input;
  }

  private void step(EditText input, int delta, boolean decimal, int minimum) {
    try {
      double n = Double.parseDouble(input.getText().toString()) + (decimal ? 2.5 : 1) * delta;
      input.setText(
          decimal ? String.valueOf(Math.max(0, n)) : String.valueOf(Math.max(minimum, (int) n)));
    } catch (Exception ignored) {
    }
  }

  void adjust(Domain.Exercise e) {
    editing = e;
    detailSets = detailAmount = detailWeight = detailRest = null;
    LinearLayout form = column();
    form.addView(GuideImages.exercise(activity, e), new LinearLayout.LayoutParams(-1, dp(160)));
    form.addView(text(GuideImages.guidance(e), 13, muted, false));
    if (e.distanceBased()) {
      boolean newContinuous = e.id.equals("incline_treadmill") || e.id.equals("stair_climb");
      EditText goal = null;
      if (newContinuous) {
        form.addView(text("目标时长（秒，可留空）", 15, muted, false));
        goal = new EditText(activity); goal.setInputType(2); goal.setTextColor(ink);
        goal.setText(draft != null ? draft.getString("goal", "") : e.goalSeconds == null ? "" : String.valueOf(e.goalSeconds));
        draft = null;
        detailAmount = goal;
        form.addView(goal);
        form.addView(text("目标不作为本次实际记录。", 14, muted, false));
      }
      final EditText goalInput = goal;
      detail = new DesignDialog.Builder(activity, dark, ink, muted, accent, soft)
          .setTitle(e.name).setView(form).setNegativeButton("返回", (d, w) -> editing = null)
          .setPositiveButton("选择此项", null).create();
      track(detail); detail.setOnCancelListener(d -> editing = null); detail.show();
      detail.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        try {
          String entered = goalInput == null ? "" : goalInput.getText().toString().trim();
          Integer goalSeconds = entered.isEmpty() ? null : Domain.integer(entered, 1, 14400, "目标时长");
          Domain.Exercise selected = e.copy(); selected.sets = 1; selected.goalSeconds = goalSeconds;
          selected.validate(); chosen.put(selected.id, selected); editing = null;
          detail.dismiss(); renderRows(); refreshCount();
        } catch (Exception error) { Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show(); }
      });
      return;
    }
    TextView caption =
        text(
            e.timed ? "按时长记录" : ExerciseCatalog.perSide(e) ? "按每侧次数记录" : "按次数记录", 13, muted, false);
    if (!ExerciseCatalog.perSide(e)) form.addView(caption);
    EditText sets =
        stepper(
            form,
            "组数",
            String.valueOf(e.sets),
            false,
            x -> {
              try {
                e.sets = Integer.parseInt(x);
              } catch (Exception ignored) {
              }
            });
    EditText amount =
        stepper(
            form,
            e.timed ? "每组时长（秒）" : ExerciseCatalog.perSide(e) ? "每侧次数" : "每组次数",
            String.valueOf(e.timed ? e.seconds : e.reps),
            false,
            x -> {
              try {
                if (e.timed) e.seconds = Integer.parseInt(x);
                else e.reps = Integer.parseInt(x);
              } catch (Exception ignored) {
              }
            });
    EditText weight =
        e.unit.isEmpty()
            ? null
            : stepper(
                form,
                ExerciseCatalog.weightLabel(e) + "（千克）",
                String.valueOf(e.weight),
                true,
                x -> {
                  try {
                    e.weight = Double.parseDouble(x);
                  } catch (Exception ignored) {
                  }
                });
    EditText rest = stepper(form, "组间休息（秒）", String.valueOf(e.restSeconds), false, 0,
        x -> {
          try {
            e.restSeconds = Integer.parseInt(x);
          } catch (NumberFormatException ignored) {
          }
        });
    form.addView(text("填 0 时跳过组间休息。", 12, muted, false));
    detailSets = sets;
    detailAmount = amount;
    detailWeight = weight;
    detailRest = rest;
    if (draft != null) {
      sets.setText(draft.getString("sets", sets.getText().toString()));
      amount.setText(draft.getString("amount", amount.getText().toString()));
      if (weight != null) weight.setText(draft.getString("weight", weight.getText().toString()));
      rest.setText(draft.getString("restSeconds", rest.getText().toString()));
      draft = null;
    }
    if (weight != null) {
      TextView unit =
          text(
              e.unit.equals("每只") ? (e.gear.contains("哑铃") ? "重量按单只哑铃填写" : "重量按单件填写") : e.unit.equals("总重") ? "填写器材总重量" : "按器械标注的重量填写",
              12,
              muted,
              false);
      unit.setPadding(0, dp(10), 0, 0);
      form.addView(unit);
    }
    detail =
        new DesignDialog.Builder(activity, dark, ink, muted, accent, soft)
            .setTitle(e.name)
            .setView(form)
            .setPositiveButton("选用此设置", null)
            .setNegativeButton("取消", (d, w) -> editing = null)
            .create();
    track(detail);
    detail.setOnCancelListener(d -> editing = null);
    detail.setOnShowListener(
        d ->
            detail
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        e.sets = Domain.integer(sets.getText().toString(), 1, 20, "组数");
                        if (e.timed)
                          e.seconds = Domain.integer(amount.getText().toString(), 1, 14400, "时长");
                        else e.reps = Domain.integer(amount.getText().toString(), 1, 200, "次数");
                        if (weight != null)
                          e.weight = Domain.number(weight.getText().toString(), "重量");
                        e.restSeconds = Domain.integer(rest.getText().toString(), 0,
                            Domain.MAX_REST_SECONDS, "组间休息");
                        e.validate();
                        chosen.put(e.id, e.copy());
                        editing = null;
                        detail.dismiss();
                        renderRows();
                        refreshCount();
                      } catch (Exception x) {
                        Toast.makeText(activity, x.getMessage(), Toast.LENGTH_LONG).show();
                      }
                    }));
    detail.show();
  }

  Bundle state() {
    if (closed || dialog == null || !dialog.isShowing()) return null;
    Bundle b = new Bundle();
    if (editing != null && detailAmount != null) {
      Bundle raw = new Bundle();
      if (detailSets != null) {
        raw.putString("sets", detailSets.getText().toString());
        raw.putString("amount", detailAmount.getText().toString());
      } else raw.putString("goal", detailAmount.getText().toString());
      if (detailWeight != null) raw.putString("weight", detailWeight.getText().toString());
      if (detailRest != null) raw.putString("restSeconds", detailRest.getText().toString());
      b.putBundle("draft", raw);
    }
    b.putInt("target", target);
    b.putString("scene", scene);
    b.putString("query", query);
    b.putString("category", category);
    try {
      JSONArray values = new JSONArray();
      for (Domain.Exercise e : chosen.values()) values.put(Store.exercise(e));
      b.putString("chosen", values.toString());
      if (editing != null) {
        Domain.Exercise valid = editing.copy();
        valid.sets = 1;
        valid.reps = 1;
        valid.seconds = 60;
        valid.restSeconds = Domain.DEFAULT_REST_SECONDS;
        valid.weight = 0;
        b.putString("editing", Store.exercise(valid).toString());
      }
    } catch (JSONException ignored) {
    }
    return b;
  }
}
