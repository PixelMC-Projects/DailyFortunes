package cn.pixelmc.dailyfortunes.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/** Strict player-only request grammar, shared by the optional Java boundary and backend. */
public final class PlayerHubRequests {
  public static JsonObject parse(String action, String json) {
    try {
      if (json == null || json.length() > 4096) throw new IllegalArgumentException();
      JsonObject value = JsonParser.parseString(json).getAsJsonObject();
      validate(action, value);
      return value;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("签历请求参数无效");
    }
  }

  public static void validate(String action, JsonObject value) {
    Set<String> keys = switch (action == null ? "" : action) {
      case "snapshot" -> Set.of();
      case "calendar" -> Set.of("month");
      case "read" -> Set.of("date");
      case "feature" -> Set.of("date", "expectedRevision");
      default -> throw new IllegalArgumentException("签历操作未注册");
    };
    if (value == null || !value.keySet().equals(keys) || value.toString().length() > 4096)
      throw new IllegalArgumentException("签历请求参数无效");
    for (String key : keys)
      if (!value.get(key).isJsonPrimitive() || !value.get(key).getAsJsonPrimitive().isString())
        throw new IllegalArgumentException("签历请求参数无效");
    if (value.has("month")) {
      String month = value.get("month").getAsString();
      if (!month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException("月份无效");
      YearMonth parsed = YearMonth.parse(month);
      if (parsed.getYear() < 1970 || parsed.getYear() > 2200) throw new IllegalArgumentException("月份超出范围");
    }
    if (value.has("date")) {
      String date = value.get("date").getAsString();
      if (!(action.equals("feature") && date.isEmpty())) {
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("日期无效");
        LocalDate parsed = LocalDate.parse(date);
        if (parsed.getYear() < 1970 || parsed.getYear() > 2200) throw new IllegalArgumentException("日期超出范围");
      }
    }
    if (value.has("expectedRevision") && !value.get("expectedRevision").getAsString().matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("签历版本无效，请刷新后重试");
  }

  private PlayerHubRequests() { }
}
