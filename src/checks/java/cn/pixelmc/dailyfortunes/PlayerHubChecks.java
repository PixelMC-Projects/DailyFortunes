package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.ServiceChecks.*;
import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import cn.pixelmc.dailyfortunes.service.PlayerHubRequests;
import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** H2 files are isolated under build/checks. No production service, socket, or Minecraft startup. */
public final class PlayerHubChecks {
  private static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-11-04T15:59:00Z");
    final ZoneId zone;
    TestClock() { this(ZoneOffset.UTC); }
    TestClock(ZoneId zone) { this.zone = zone; }
    public ZoneId getZone() { return zone; }
    public Clock withZone(ZoneId next) { return Clock.fixed(now, next); }
    public Instant instant() { return now; }
  }
  private static String url(Path root) {
    return "jdbc:h2:file:" + root.resolve("fortunes").toAbsolutePath().toString().replace('\\', '/')
        + ";MODE=MySQL;NON_KEYWORDS=DAY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
  }
  private static JsonObject hub(FortuneBackend backend, UUID player, String action, JsonObject body) {
    return rpc(backend, player, "playerHub", action, body);
  }
  private static JsonObject snapshot(FortuneBackend backend, UUID player) { return hub(backend, player, "snapshot", obj()); }
  private static JsonObject feature(FortuneBackend backend, UUID player, String day) {
    return hub(backend, player, "feature", obj("date", day, "expectedRevision", s(snapshot(backend, player), "revision")));
  }
  private static void safe(JsonObject record, JsonObject receipt) {
    check(record.keySet().equals(Set.of("id", "date", "at", "fortune")), "Record uses exact safe field allowlist");
    check(record.getAsJsonObject("fortune").keySet().equals(Set.of("id", "title", "grade", "poem", "interpretation")), "Fortune excludes unpublished/internal fields");
    check(l(record, "at") == l(receipt, "at"), "Real draw millisecond timestamp retained");
    check(!record.toString().contains(s(receipt, "token")) && !record.has("snapshot"), "No validity token or raw snapshot escapes");
  }
  private static final class Core implements Function<JsonObject, JsonObject> {
    final UUID player;
    String selected = "";
    long revision;
    boolean admitted = true;
    Core(UUID player) { this.player = player; }
    public JsonObject apply(JsonObject request) {
      if (!s(request, "player").equals(player.toString())) throw new IllegalArgumentException("foreign identity");
      return switch (s(request, "op")) {
        case "profiles.get" -> obj("profile", obj("id", player, "name", "Managed", "lastSeen", 1));
        case "profiles.loginContext" -> obj("admitted", admitted, "eventId", admitted ? "trusted-event" : "");
        case "storage.get" -> {
          check(s(request, "field").equals("featured_date"), "Managed storage uses registered typed field");
          yield obj("value", selected, "revision", revision);
        }
        case "storage.set" -> {
          if (l(request, "expectedRevision") != revision) throw new IllegalStateException("revision conflict");
          selected = s(request, "value");
          yield obj("value", selected, "revision", ++revision);
        }
        default -> throw new IllegalArgumentException("unexpected core operation");
      };
    }
  }

  public static void main(String[] args) throws Exception {
    int before = checks;
    Path root = Path.of("build/checks/player-hub-" + UUID.randomUUID()).toAbsolutePath();
    TestClock clock = new TestClock();
    UUID player = UUID.randomUUID(), stranger = UUID.randomUUID();
    Path backup;
    String firstDay;
    JsonObject original;
    try (FortuneBackend backend = new FortuneBackend(url(root), "sa", "", root, clock)) {
      rpc(backend, player, "login", "HubPlayer");
      rpc(backend, stranger, "login", "OtherPlayer");
      check(s(snapshot(backend, player), "todayState").equals("undrawn"), "No draw exposes undrawn state");
      rejected(() -> hub(backend, player, "draw", obj()), "Hub cannot remotely draw");
      rejected(() -> hub(backend, player, "snapshot", obj("player", stranger)), "Payload cannot select another player");
      rejected(() -> PlayerHubRequests.parse("calendar", "{\"month\":\"2026-11\",\"admin\":true}"), "Unknown keys rejected");
      rejected(() -> PlayerHubRequests.parse("feature", "{\"date\":\"\",\"expectedRevision\":0}"), "Typed revision required");
      rejected(() -> backend.rpc("playerHub", obj("args", List.of(player, "snapshot", obj())), stranger.toString(), Set.of(player)), "Managed proof cannot impersonate player");
      original = rpc(backend, player, "draw").getAsJsonObject("record");
      firstDay = s(original, "date");
      JsonObject unread = snapshot(backend, player);
      check(s(unread, "todayState").equals("unread") && unread.get("todayRecord").isJsonNull(), "Unread result never disclosed");
      check(unread.getAsJsonArray("recent").isEmpty() && l(unread, "archiveCount") == 0, "Unread draw does not enter collection");
      rejected(() -> hub(backend, player, "read", obj("date", firstDay)), "Unread record cannot be remotely read");
      check(hub(backend, player, "calendar", obj("month", "2026-11")).getAsJsonArray("records").isEmpty(), "Calendar hides unread draws");
      rpc(backend, player, "resolve", firstDay, s(original, "token"));
      JsonObject read = snapshot(backend, player);
      check(s(read, "todayState").equals("read") && l(read, "archiveCount") == 0 && read.getAsJsonArray("recent").isEmpty(), "Interpreted today remains outside collection");
      safe(hub(backend, player, "read", obj("date", firstDay)), original);
      rejected(() -> feature(backend, player, firstDay), "Feature requires archived own record");
      clock.now = clock.now.plusSeconds(120);
      check(s(snapshot(backend, player), "today").equals("2026-11-05") && s(snapshot(backend, player), "todayState").equals("undrawn"), "Online midnight uses configured Asia/Shanghai timezone");
      rejected(() -> hub(backend, player, "read", obj("date", firstDay)), "Unarchived previous-day result stays unavailable");
      rpc(backend, player, "archive", firstDay, s(original, "token"));
      JsonObject archived = snapshot(backend, player);
      check(l(archived, "archiveCount") == 1 && archived.getAsJsonArray("recent").size() == 1, "Archived-only counts and recent collection");
      safe(hub(backend, player, "read", obj("date", firstDay)), original);
      rejected(() -> hub(backend, stranger, "read", obj("date", firstDay)), "Other player cannot read owned date");
      rejected(() -> feature(backend, stranger, firstDay), "Other player cannot feature owned date");
      String stale = s(archived, "revision");
      JsonObject selected = feature(backend, player, firstDay);
      check(s(selected, "featuredDate").equals(firstDay) && selected.getAsJsonObject("featuredRecord") != null, "Feature returns committed selected record");
      rejected(() -> hub(backend, player, "feature", obj("date", "", "expectedRevision", stale)), "Stale feature write rejected");
      for (int day = 0; day < 9; day++) {
        JsonObject receipt = rpc(backend, player, "draw").getAsJsonObject("record");
        rpc(backend, player, "resolve", s(receipt, "date"), s(receipt, "token"));
        rpc(backend, player, "archive", s(receipt, "date"), s(receipt, "token"));
        clock.now = clock.now.plus(Duration.ofDays(1));
      }
      check(l(snapshot(backend, player), "archiveCount") == 10 && snapshot(backend, player).getAsJsonArray("recent").size() == 8, "Recent list explicitly bounded while total stays exact");
      check(hub(backend, player, "calendar", obj("month", "2026-11")).getAsJsonArray("records").size() == 10, "Full archived month contains every owned date");
      String file = s(web(backend, "POST", "/backup", obj(), Map.of()), "file");
      backup = root.resolve("backups").resolve(file);
      check(JsonParser.parseString(Files.readString(backup)).getAsJsonObject().getAsJsonArray("hubPreferences").size() == 1, "Standalone preference included in existing backup");
    }
    try (FortuneBackend reopened = new FortuneBackend(url(root), "sa", "", root, clock)) {
      check(s(snapshot(reopened, player), "featuredDate").equals(firstDay), "Selection persists across backend reopen");
      Core core = new Core(player);
      reopened.attachPlatform(core);
      check(snapshot(reopened, player).get("featuredDate").isJsonNull(), "Managed mode uses shared storage without local fallback");
      feature(reopened, player, firstDay);
      check(core.selected.equals(firstDay) && core.revision > 0, "Managed selection committed through optimistic shared field");
      core.admitted = false;
      rejected(() -> snapshot(reopened, player), "Managed core admission is rechecked");
      core.admitted = true;
      reopened.attachPlatform(null);
      try (Connection connection = DriverManager.getConnection(url(root), "sa", "");
          PreparedStatement delete = connection.prepareStatement("DELETE FROM df_records WHERE player=? AND day=?")) {
        delete.setString(1, player.toString()); delete.setString(2, firstDay); delete.executeUpdate();
      }
      check(snapshot(reopened, player).get("featuredDate").isJsonNull(), "Deleted reference is safely cleared and committed");
    }
    Path restoredRoot = root.resolve("restored");
    try (FortuneBackend restored = new FortuneBackend(url(restoredRoot), "sa", "", restoredRoot, clock)) {
      restored.restoreEmpty(backup, "RESTORE-EMPTY-DAILYFORTUNES");
      check(s(snapshot(restored, player), "featuredDate").equals(firstDay), "Preference survives backup/restore with owned record");
    }
    JsonObject legacy = JsonParser.parseString(Files.readString(backup)).getAsJsonObject();
    legacy.remove("hubPreferences");
    Path oldBackup = root.resolve("old-format.json");
    Files.writeString(oldBackup, JSON.toJson(legacy));
    Files.writeString(oldBackup.resolveSibling(oldBackup.getFileName() + ".sha256"), hash(JSON.toJson(legacy)));
    Path legacyRoot = root.resolve("legacy");
    try (FortuneBackend restored = new FortuneBackend(url(legacyRoot), "sa", "", legacyRoot, clock)) {
      restored.restoreEmpty(oldBackup, "RESTORE-EMPTY-DAILYFORTUNES");
      check(snapshot(restored, player).get("featuredDate").isJsonNull(), "Old backup without new optional array restores safely");
    }
    Path isolatedRoot = root.resolve("other-world");
    try (FortuneBackend isolated = new FortuneBackend(url(isolatedRoot), "sa", "", isolatedRoot, clock)) {
      rpc(isolated, player, "login", "HubPlayer");
      check(snapshot(isolated, player).get("featuredDate").isJsonNull() && l(snapshot(isolated, player), "archiveCount") == 0, "Same player remains isolated in another world database");
    }
    check(i(FortuneBackend.storageManifest(), "version") == 2 && FortuneBackend.storageManifest().getAsJsonArray("fields").size() == 1, "Manifest registers upgraded preference schema");
    System.out.println("Player Hub Fortune checks passed: " + (checks - before));
  }
}
