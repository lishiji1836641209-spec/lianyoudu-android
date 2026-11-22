package com.lianyoudu.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** System health-permission rationale, using the same native design tokens. */
public final class HealthPrivacyActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(state);
    int ink = HealthDesign.ink(dark), muted = HealthDesign.muted(dark);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(HealthDesign.background(dark));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = AppViews.dp(this, 24);
    body.setPadding(pad, pad, pad, pad);
    scroll.addView(body);
    TextView title = new TextView(this);
    title.setText("健康数据使用说明");
    title.setTextSize(28);
    title.setTextColor(ink);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    body.addView(title);
    TextView text = new TextView(this);
    text.setTextSize(16);
    text.setTextColor(muted);
    text.setLineSpacing(AppViews.dp(this, 5), 1);
    text.setPadding(0, pad, 0, pad);
    text.setText(
        "练有度仅在你授权后读取今天的步数、活动热量和总消耗，用于展示活动与能量估算。\n\n"
            + "不向健康数据服务写入训练，不自动上传这些数据，也不用于广告。部分已保存的步数和能量汇总可能包含在你手动导出的备份中。\n\n"
            + "你可随时在系统健康数据权限中撤回授权。未授权仍可记录运动与饮食。热量是估算值，不能替代医学测量。");
    body.addView(text);
    TextView back = new TextView(this);
    back.setText("返回");
    back.setTextColor(HealthDesign.blue(dark));
    back.setTextSize(17);
    back.setGravity(Gravity.CENTER);
    back.setMinHeight(AppViews.dp(this, 48));
    back.setBackground(
        AppViews.button(HealthDesign.well(dark), ink, AppViews.dp(this, GlassEffects.CONTROL)));
    GlassEffects.press(back);
    back.setOnClickListener(v -> finish());
    body.addView(back);
    setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
  }
}
