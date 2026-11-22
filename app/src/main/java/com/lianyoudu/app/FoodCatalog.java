package com.lianyoudu.app;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** Offline, provenance-preserving nutritional lookup. Never infer food or portion from a photo. */
final class FoodCatalog {
  final List<JSONObject> foods = new ArrayList<>();
  private JSONObject imageMap = new JSONObject();
  private JSONObject household = new JSONObject();

  FoodCatalog(Context context) {
    try (InputStream in = context.getAssets().open("household-portions.json")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      household = new JSONObject(out.toString("UTF-8")).getJSONObject("foods");
    } catch (Exception ignored) {}
    try (InputStream in = context.getAssets().open("guides/food-map.json")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] bytes = new byte[4096];
      int count;
      while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
      imageMap = new JSONObject(out.toString("UTF-8"));
    } catch (Exception ignored) {
    }
    try (InputStream in = context.getAssets().open("foods.json")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      JSONArray data = new JSONObject(out.toString("UTF-8")).getJSONArray("foods");
      for (int i = 0; i < data.length(); i++) foods.add(data.getJSONObject(i));
    } catch (Exception e) {
      throw new IllegalStateException("食物库读取失败", e);
    }
  }

  int imageIndex(String name) {
    JSONObject f = lookup(name);
    return f == null ? -1 : imageMap.optInt(f.optString("id"), -1);
  }

  static record Portion(String label, int grams) {}

  static List<Portion> portions(JSONObject food) {
    return List.of(new Portion("50克", 50), new Portion("100克", 100), new Portion("150克", 150));
  }

  JSONArray householdReferences(JSONObject food) {
    String id = food.optString("foodId", food.optString("id", ""));
    JSONObject entry = household.optJSONObject(id);
    JSONArray references = entry == null ? null : entry.optJSONArray("references");
    return references == null ? new JSONArray() : references;
  }

  JSONObject lookup(String name) {
    for (JSONObject f : foods) {
      if (f.optString("name").equals(name)) return f;
      for (String alias : f.optString("aliases").split(","))
        if (!alias.isEmpty() && alias.equals(name)) return f;
    }
    return null;
  }

  static String normalized(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
  }

  List<JSONObject> search(String query) {
    String q = normalized(query);
    String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
    List<JSONObject> found = new ArrayList<>();
    for (JSONObject f : foods) {
      String haystack = normalized(f.optString("name") + " " + f.optString("aliases") + " " + f.optString("group"));
      boolean matches = true;
      for (String word : words) if (!haystack.contains(word)) { matches = false; break; }
      if (matches) found.add(f);
    }
    found.sort(Comparator.comparingInt(f -> {
      String name = normalized(f.optString("name")), aliases = normalized(f.optString("aliases"));
      if (q.isEmpty()) return 2;
      if (name.equals(q) || Arrays.asList(aliases.split(",")).contains(q)) return 0;
      return name.startsWith(q) ? 1 : 2;
    }));
    return found;
  }

  static String category(JSONObject food) {
    String name = food.optString("name"), group = food.optString("group");
    if (group.isEmpty() && !food.optString("category").isEmpty()) return food.optString("category");
    if (group.equals("蛋奶豆")) {
      if (name.contains("蛋")) return "蛋类";
      if (name.contains("奶") || name.contains("酸乳")) return "奶及奶制品";
      return "豆及豆制品";
    }
    if (group.equals("肉鱼虾")) return name.matches(".*(鱼|虾|蟹|贝|鱿|三文鱼).*") ? "水产" : "肉禽";
    if (group.equals("坚果调味")) return name.matches(".*(油|盐|酱).*") ? "油脂调味" : "坚果种子";
    if (group.equals("饮品")) return "饮料";
    return group.isEmpty() ? "菜肴及其他" : group;
  }

  static boolean rawIngredient(JSONObject food) {
    String n = food.optString("name"), group = food.optString("group");
    if (!n.contains("（生")) return false;
    if (group.equals("水果") || group.equals("坚果调味") || group.equals("饮品") || n.contains("豆腐")) return false;
    if (group.equals("蔬菜")) return List.of("西兰花", "菠菜", "茄子", "芦笋", "青豌豆", "南瓜", "四季豆", "香菇", "海带", "竹笋", "秋葵", "芋头", "菜花", "白蘑菇").stream().anyMatch(n::contains);
    return group.equals("主食") || group.equals("肉鱼虾") || n.contains("鸡蛋");
  }

  String snapshotLine(JSONObject food) {
    String snapshot = android.util.Base64.encodeToString(food.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    return food.optString("name") + "，" + food.optString("grams") + "，"
        + (food.isNull("kcal100") ? "" : food.optString("kcal100", "")) + "，"
        + (food.isNull("protein100") ? "" : food.optString("protein100", "")) + "，"
        + (food.isNull("fat100") ? "" : food.optString("fat100", "")) + "，"
        + (food.isNull("carbs100") ? "" : food.optString("carbs100", "")) + "，"
        + food.optString("category", "菜肴及其他") + "，" + snapshot;
  }

  static String nutrientSource(JSONObject food, String key) {
    String explicit = food.optString(key + "Source", "");
    if (!explicit.isEmpty()) return explicit;
    if (key.equals("kcal")) return food.optString("source", "来源未记录");
    String legacy = food.optString("macroSource", "");
    if (legacy.isEmpty()) return "来源未记录";
    if (food.has("foodId") && legacy.contains("用户")) return "历史来源未细分";
    return legacy;
  }

  private JSONObject snapshotEntry(String[] parts, double grams) throws JSONException {
    JSONObject old = new JSONObject(new String(android.util.Base64.decode(parts[7], android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8));
    Domain.require(old.optString("name").equals(parts[0].trim()), "食物名称与记录不一致");
    JSONObject result = new JSONObject(old.toString()); result.put("grams", grams);
    String[] keys = {"kcal", "protein", "fat", "carbs"};
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i], raw = parts[i + 2].trim();
      Double oldValue = old.has(key + "100") && !old.isNull(key + "100") ? old.getDouble(key + "100") : null;
      Double value = raw.isEmpty() ? null : Domain.number(raw, key);
      Domain.require(value == null || value >= 0 && value <= (i == 0 ? 900 : 100), "请检查营养数值");
      boolean changed = !java.util.Objects.equals(oldValue, value);
      if (value == null) {
        result.remove(key + "100");
        if (!(oldValue == null && old.has(key) && !old.isNull(key) && old.optDouble("grams", -1) == grams)) result.remove(key);
      } else {
        result.put(key + "100", value);
        result.put(key, i == 0 ? Math.round(value * grams) / 100.0 : value * grams / 100);
      }
      if (value == null) result.remove(key + "Source");
      else result.put(key + "Source", changed ? "用户填写/包装标签" : nutrientSource(old, key));
      if (changed && i == 0) {
        if (old.has("sourceUrl")) result.put("catalogSourceUrl", old.get("sourceUrl"));
        if (old.has("sourceVersion")) result.put("catalogSourceVersion", old.get("sourceVersion"));
        result.put("source", "用户填写/包装标签"); result.remove("sourceUrl"); result.remove("sourceVersion");
      }
    }
    result.put("category", parts[6].trim().isEmpty() ? "菜肴及其他" : parts[6].trim());
    return result;
  }

  JSONArray parse(String text) throws JSONException {
    JSONArray result = new JSONArray();
    for (String line : text.split("\\n")) {
      if (line.trim().isEmpty()) continue;
      String[] parts = line.trim().split("[，,]", -1);
      Domain.require(parts.length == 2 || parts.length == 3 || parts.length == 7 || parts.length == 8, "请检查食物、克数和营养信息");
      String name = parts[0].trim();
      double grams = Domain.number(parts[1].trim(), "食用量");
      Domain.require(
          !name.isEmpty() && name.length() <= 50 && grams > 0 && grams <= 10000, "请检查名称和克数");
      if (parts.length == 8) { result.put(snapshotEntry(parts, grams)); continue; }
      JSONObject entry = new JSONObject().put("name", name).put("grams", grams),
          match = lookup(name);
      if ((parts.length == 3 || parts.length == 7) && !parts[2].trim().isEmpty()) {
        double rate = Domain.number(parts[2].trim(), "每100克热量");
        Domain.require(rate >= 0 && rate <= 900, "每100克热量应为0至900千卡");
        entry.put("kcal100", rate).put("source", "用户填写").put("kcalSource", "用户填写/包装标签");
      } else if (match != null && parts.length != 7) {
        entry
            .put("name", match.getString("name"))
            .put("foodId", match.getString("id"))
            .put("kcal100", match.getDouble("kcal100"))
            .put("source", match.getString("source"))
            .put("sourceVersion", "USDA-SR-2018-curated-2026-10-27")
            .put("preparation", match.getString("name"))
            .put("sourceUrl", match.getString("url"));
      }
      if (parts.length == 2 && match != null) {
        for (String key : new String[] {"protein", "fat", "carbs"})
          if (match.has(key + "100") && !match.isNull(key + "100")) {
            double rate = match.getDouble(key + "100");
            entry.put(key + "100", rate).put(key, rate * grams / 100);
            entry.put(key + "Source", "USDA SR Legacy April 2018");
            entry.put("macroSource", "USDA SR Legacy April 2018");
          }
      }
      if (parts.length == 7) {
        String[] keys = {"protein", "fat", "carbs"};
        for (int i = 0; i < keys.length; i++) if (!parts[i + 3].trim().isEmpty()) {
          double value = Domain.number(parts[i + 3].trim(), keys[i]);
          Domain.require(value >= 0 && value <= 100, "每100克营养素应为0至100克");
          entry.put(keys[i] + "100", value).put(keys[i], value * grams / 100);
          entry.put(keys[i] + "Source", "用户填写/包装标签");
          entry.put("macroSource", "用户填写/包装标签");
        }
        entry.put("category", parts[6].trim().isEmpty() ? "菜肴及其他" : parts[6].trim());
      } else if (match != null) entry.put("category", category(match));
      if (entry.has("kcal100"))
        entry.put("kcal", Math.round(entry.getDouble("kcal100") * grams) / 100.0);
      result.put(entry);
    }
    Domain.require(result.length() > 0 && result.length() <= 30, "每餐可填写1至30项食物");
    return result;
  }

  static String summary(JSONArray foods) {
    double total = 0;
    int known = 0;
    if (foods == null || foods.length() == 0) return "暂无食物";
    for (int i = 0; i < foods.length(); i++) {
      JSONObject f = foods.optJSONObject(i);
      if (f != null && f.has("kcal")) {
        known++;
        total += f.optDouble("kcal");
      }
    }
    if (known == 0) return "热量未填写";
    return String.format(
        Locale.CHINA,
        "约 %.0f 千卡%s",
        total,
        known == foods.length() ? "" : " · " + (foods.length() - known) + "项未计入");
  }
}
