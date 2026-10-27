package cn.pixelmc.dailyfortunes.forge;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import com.google.gson.JsonObject;
import java.util.UUID;

/** The public chest can show any visitor's own archived fortune. */
public final class DisplaySelection {
  private DisplaySelection() {}

  public static String serialize(UUID player, String date, JsonObject response) {
    JsonObject record = response.getAsJsonObject("record");
    if (record == null || !player.toString().equals(s(record, "player"))
        || !date.equals(s(record, "date")) || !"archived".equals(s(record, "state")))
      throw new IllegalArgumentException("只能展示自己在所选日期已收藏的签笺");
    JsonObject display = record.deepCopy();
    display.addProperty("hover", b(response.getAsJsonObject("settings"), "hover"));
    return JSON.toJson(display);
  }
}
