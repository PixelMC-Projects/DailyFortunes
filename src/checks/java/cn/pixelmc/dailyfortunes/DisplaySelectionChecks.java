package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.ServiceChecks.*;
import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.forge.DisplaySelection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

public final class DisplaySelectionChecks {
  private static JsonObject response(UUID player, String date, String state) {
    return obj("record", obj("player", player, "date", date, "state", state,
        "snapshot", obj("id", 13, "grade", "上吉签")), "settings", obj("hover", true));
  }

  public static void main(String[] args) {
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    JsonObject yesterday = response(alice, "2026-10-26", "archived");
    String shown = DisplaySelection.serialize(alice, "2026-10-26", yesterday);
    check(!yesterday.getAsJsonObject("record").has("hover"), "render settings do not mutate authoritative history");
    shown = DisplaySelection.serialize(bob, "2026-10-27", response(bob, "2026-10-27", "archived"));
    JsonObject replaced = JsonParser.parseString(shown).getAsJsonObject();
    check(s(replaced, "player").equals(bob.toString()), "another visitor replaces the shared display using their own collection");
    shown = DisplaySelection.serialize(alice, "2026-10-26", yesterday);
    shown = DisplaySelection.serialize(alice, "2026-10-27", response(alice, "2026-10-27", "archived"));
    check(s(JsonParser.parseString(shown).getAsJsonObject(), "date").equals("2026-10-27"), "the same player replaces yesterday's display with today's archived fortune");
    rejected(() -> DisplaySelection.serialize(bob, "2026-10-26", yesterday), "visitor cannot display somebody else's private collection");
    rejected(() -> DisplaySelection.serialize(alice, "2026-10-27", yesterday), "stale response cannot select a different date");
    rejected(() -> DisplaySelection.serialize(alice, "2026-10-27", response(alice, "2026-10-27", "unread")), "unread fortune cannot be displayed");
    rejected(() -> DisplaySelection.serialize(alice, "2026-10-27", response(alice, "2026-10-27", "loose")), "uncollected fortune cannot be displayed");
    System.out.println("PASS: " + checks + " shared display selection assertions");
  }
}
