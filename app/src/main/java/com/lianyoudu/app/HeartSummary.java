package com.lianyoudu.app;

/** Coverage is not the same thing as whether any heart-rate observations were saved. */
final class HeartSummary {
  private HeartSummary() {}

  static String coverage(Domain.Session session) {
    long valid = Math.max(0, session.heartValid);
    long missing = Math.max(0, session.elapsed - valid);
    boolean hasPoints = !session.heartSamples.isEmpty();
    boolean hasObservations = session.heartCount > 0 || hasPoints;
    if (valid == 0 && !hasObservations)
      return "未记录心率 · 缺失 " + Domain.clock(missing);
    String result = "心率有效 " + Domain.clock(valid) + " · 缺失 " + Domain.clock(missing);
    if (valid == 0 && hasObservations) result += " · 已记录心率采样";
    if (!hasPoints) result += " · 无逐点心率数据";
    return result;
  }
}
