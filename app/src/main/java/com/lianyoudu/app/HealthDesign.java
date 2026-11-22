package com.lianyoudu.app;

import android.content.Context;

/** Tokens frozen against the approved Health Studio design, in logical dp/sp. */
final class HealthDesign {
  static final int CARD = 24, BUTTON = 24, INPUT = 12, SHEET = 32;

  static int background(boolean dark) {
    return dark ? 0xFF08090B : 0xFFF2F2F7;
  }

  static int card(boolean dark) {
    return dark ? 0xFF1C1C1E : 0xFFFFFFFF;
  }

  static int ink(boolean dark) {
    return dark ? 0xFFF7F7FA : 0xFF19191D;
  }

  static int muted(boolean dark) {
    return dark ? 0xFFAAAAB3 : 0xFF6B6B74;
  }

  static int line(boolean dark) {
    return dark ? 0xFF343438 : 0xFFE9E9EE;
  }

  static int well(boolean dark) {
    return dark ? 0xFF2B2B30 : 0xFFF2F2F7;
  }

  static int blue(boolean dark) {
    return dark ? 0xFF66A9FF : 0xFF0868DC;
  }

  static int orange(boolean dark) {
    return dark ? 0xFFFF9A52 : 0xFFBC4A09;
  }

  static int teal(boolean dark) {
    return dark ? 0xFF66D7D0 : 0xFF06797E;
  }

  static int purple(boolean dark) {
    return dark ? 0xFFB69AFF : 0xFF7552CC;
  }

  static boolean motion(Context context) {
    if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return false;
    Context app = context.getApplicationContext();
    return !(app instanceof FitnessApp)
        || ((FitnessApp) app).store == null
        || !((FitnessApp) app).store.setting("reduceMotion", "false").equals("true");
  }
}
