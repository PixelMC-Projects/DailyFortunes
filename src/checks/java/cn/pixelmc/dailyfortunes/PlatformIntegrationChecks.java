package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.ServiceChecks.*;
import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;

/** Uses an isolated local database and a trusted-core stub, never a production endpoint. */
public final class PlatformIntegrationChecks {
  private static final class Core implements Function<JsonObject, JsonObject> {
    final Map<String, JsonObject> profiles = new LinkedHashMap<>();
    final Set<String> admitted = new HashSet<>();
    boolean failed;

    void player(UUID id, String name) {
      profiles.put(id.toString(), obj("id", id, "name", name, "firstJoinedAt", 0, "lastSeen", 0));
    }

    public JsonObject apply(JsonObject request) {
      if (failed) throw new IllegalStateException("core unavailable");
      String id = s(request, "player");
      return switch (s(request, "op")) {
        case "profiles.list" -> obj("players", profiles.values(), "revision", 1);
        case "profiles.get" -> obj("profile", profiles.get(id));
        case "profiles.loginContext" ->
            obj(
                "admitted",
                admitted.contains(id),
                "eventId",
                admitted.contains(id) ? "trusted-" + id : "",
                "firstGlobalLogin",
                false,
                "firstArchiveLogin",
                false,
                "archiveId",
                "archive-test");
        default -> throw new IllegalArgumentException("unexpected platform operation");
      };
    }
  }

  private static long count(String url, String table) throws Exception {
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static String first(JsonObject result) {
    return s(result.getAsJsonArray("rows").get(0).getAsJsonObject(), "name");
  }

  private static JsonObject players(FortuneBackend backend, Set<UUID> online, String... q) {
    Map<String, String> query = new HashMap<>();
    for (int n = 0; n < q.length; n += 2) query.put(q[n], q[n + 1]);
    return backend.request("GET", "/players", obj(), query, "checks", online);
  }

  public static void main(String[] args) throws Exception {
    Path folder = Path.of("build/checks/platform-" + UUID.randomUUID()).toAbsolutePath();
    String url =
        "jdbc:h2:file:"
            + folder.resolve("fortunes").toString().replace('\\', '/')
            + ";MODE=MySQL;NON_KEYWORDS=DAY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    UUID old = UUID.randomUUID(),
        bob = UUID.randomUUID(),
        carla = UUID.randomUUID(),
        aaron = UUID.randomUUID(),
        unknown = UUID.randomUUID();
    Core core = new Core();
    core.player(old, "Zulu");
    core.player(bob, "Bob");
    core.player(carla, "Carla");
    core.player(aaron, "Aaron");
    core.admitted.add(old.toString());
    core.admitted.add(bob.toString());
    Set<UUID> online = Set.of(old, carla);
    try (FortuneBackend backend = new FortuneBackend(url, "sa", "", folder)) {
      rpc(backend, old, "login", "OldName");
      JsonObject original = rpc(backend, old, "draw").getAsJsonObject("record");
      rpc(backend, old, "resolve", s(original, "date"), s(original, "token"));
      rpc(backend, old, "archive", s(original, "date"), s(original, "token"));
      var scheduled = FortuneBackend.class.getDeclaredField("automaticBackup");
      scheduled.setAccessible(true);
      ScheduledFuture<?> standaloneTask = (ScheduledFuture<?>) scheduled.get(backend);
      check(!standaloneTask.isCancelled(), "standalone retains scheduled backup");
      backend.attachPlatform(core);
      check(standaloneTask.isCancelled(), "managed attachment cancels private scheduler");
      check(
          b(backend.status(), "managed") && s(backend.status(), "backupOwner").equals("manager"),
          "managed backup ownership explicit");
      JsonObject list = players(backend, online);
      check(
          i(list, "total") == 4 && first(list).equals("Carla"),
          "global roster and online grouping");
      check(i(list.getAsJsonObject("presenceCounts"), "offline") == 2, "core presence counts");
      check(
          first(players(backend, online, "sort", "status", "direction", "desc")).equals("Aaron"),
          "reverse presence keeps A-Z inside group");
      check(first(players(backend, online, "sort", "name")).equals("Aaron"), "name ascending");
      check(
          first(players(backend, online, "sort", "name", "direction", "desc")).equals("Zulu"),
          "name descending uses core rename");
      check(
          first(players(backend, online, "sort", "total", "direction", "desc")).equals("Zulu"),
          "existing history joins global identity");
      check(
          first(players(backend, online, "sort", "total")).equals("Aaron"), "zero statistics sort");
      check(
          first(players(backend, online, "sort", "latestAt")).equals("Zulu"),
          "null recent draw sorts last");
      check(i(players(backend, online, "presence", "offline"), "total") == 2, "offline filter");
      check(i(players(backend, online, "q", "bo"), "total") == 1, "core name search");
      check(
          players(backend, online, "sort", "name", "size", "1", "page", "1")
                  .getAsJsonArray("rows")
                  .size()
              == 1,
          "global pagination");
      JsonObject empty =
          web(backend, "GET", "/calendar", obj(), Map.of("player", bob.toString(), "month", ""));
      check(
          empty.getAsJsonArray("records").isEmpty()
              && s(empty.getAsJsonObject("profile"), "name").equals("Bob"),
          "empty global calendar");
      check(
          i(web(backend, "GET", "/state", obj(), Map.of()).getAsJsonObject("counts"), "players")
              == 4,
          "state uses global directory count");
      check(
          count(url, "df_players") == 1
              && count(url, "df_records") == 1
              && count(url, "df_requests") == 0,
          "projection never creates participation, records or tokens");
      rejected(() -> rpc(backend, aaron, "login", "Spoof"), "core non-admitted rejects login");
      core.admitted.add(unknown.toString());
      rejected(() -> rpc(backend, unknown, "draw"), "missing global identity rejects write");
      check(
          count(url, "df_players") == 1 && count(url, "df_records") == 1,
          "rejections have no writes");
      rpc(backend, bob, "login", "SpoofedName");
      JsonObject drawn = rpc(backend, bob, "draw").getAsJsonObject("record");
      check(s(drawn, "owner").equals("Bob"), "core name wins over supplied name");
      rpc(backend, bob, "resolve", s(drawn, "date"), s(drawn, "token"));
      check(
          rpc(backend, bob, "pending").getAsJsonArray("records").size() == 1,
          "managed pending delivery");
      rpc(backend, bob, "archive", s(drawn, "date"), s(drawn, "token"));
      check(
          rpc(backend, bob, "pending").getAsJsonArray("records").isEmpty(),
          "archive clears pending");
      JsonObject historical =
          rpc(backend, old, "calendar", "").getAsJsonArray("records").get(0).getAsJsonObject();
      check(s(historical, "owner").equals("OldName"), "immutable historical owner survives rename");
      check(
          s(rpc(backend, old, "display", s(original, "date")).getAsJsonObject("record"), "owner")
              .equals("OldName"),
          "historical display immutable");
      core.failed = true;
      rejected(
          () -> players(backend, online),
          "directory failure does not fall back to stale plugin roster");
      rejected(() -> rpc(backend, bob, "draw"), "admission failure rejects business operation");
      core.failed = false;
      backend.attachPlatform(null);
      check(
          !b(backend.status(), "managed")
              && !((ScheduledFuture<?>) scheduled.get(backend)).isCancelled(),
          "detach restores standalone scheduler");
      check(
          s(rpc(backend, old, "calendar", "").getAsJsonObject("profile"), "name").equals("OldName"),
          "standalone identity and collection remain available");
    }
    JsonObject manifest = storageManifest();
    check(
        i(manifest, "version") == 1 && s(manifest, "scope").equals("global"),
        "manifest schema and scope");
    check(
        manifest.getAsJsonArray("tables").size() == 8
            && manifest.getAsJsonArray("fields").isEmpty(),
        "all eight namespace business tables declared");
    System.out.println("Unified platform integration checks passed: " + checks);
  }
}
