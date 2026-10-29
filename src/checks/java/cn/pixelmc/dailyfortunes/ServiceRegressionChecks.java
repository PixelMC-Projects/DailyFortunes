package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Isolated MariaDB regressions for stale writes, selective undo, imports and backups. */
public final class ServiceRegressionChecks {
  private static int checks;

  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    checks++;
    System.out.println("PASS " + label);
  }

  private static void rejected(Runnable work, String label) {
    boolean refused = false;
    try {
      work.run();
    } catch (IllegalArgumentException | IllegalStateException expected) {
      refused = true;
    }
    check(refused, label);
  }

  private static JsonObject request(
      FortuneBackend service,
      String method,
      String path,
      JsonObject body,
      Map<String, String> query) {
    return service.request(method, path, body, query, "regression fixture", Set.of());
  }

  private static JsonObject settings(FortuneBackend service) {
    return request(service, "GET", "/state", obj(), Map.of()).getAsJsonObject("settings");
  }

  private static void setting(FortuneBackend service, String key, boolean value) {
    JsonObject current = settings(service), next = current.deepCopy();
    next.addProperty(key, value);
    request(
        service,
        "POST",
        "/settings",
        obj("settings", next, "expectedRevision", l(current, "revision")),
        Map.of());
  }

  private static JsonObject export(FortuneBackend service) {
    return request(service, "GET", "/config/export", obj(), Map.of());
  }

  private static JsonObject rpc(
      FortuneBackend service, UUID player, String operation, Object... values) {
    JsonArray args = new JsonArray();
    args.add(player.toString());
    for (Object value : values) args.add(JSON.toJsonTree(value));
    return service.rpc(operation, obj("args", args), player.toString(), Set.of(player));
  }

  private static JsonObject fortune(FortuneBackend service, int id) {
    return request(service, "GET", "/fortune", obj(), Map.of("id", String.valueOf(id)));
  }

  private static JsonObject createFortune(FortuneBackend service, String name) {
    JsonObject fresh = fortune(service, 3);
    fresh.remove("id");
    fresh.addProperty("title", name);
    fresh.addProperty("enabled", true);
    return request(
            service, "POST", "/fortune", obj("fortune", fresh, "expectedRevision", 0), Map.of())
        .getAsJsonObject("fortune");
  }

  private static void deleteFortune(FortuneBackend service, JsonObject fortune) {
    request(
        service,
        "POST",
        "/fortune/delete",
        obj("id", i(fortune, "id"), "expectedRevision", l(fortune, "revision")),
        Map.of());
  }

  private record Fixture(
      Path backup,
      UUID pendingPlayer,
      UUID archivedPlayer,
      String day,
      String requestId,
      JsonObject pendingRecord,
      int deletedId) {}

  private static void importConfig(FortuneBackend service, JsonObject file) {
    JsonObject preview = request(service, "POST", "/config/preview", obj("file", file), Map.of());
    request(
        service,
        "POST",
        "/config/import",
        obj("file", file, "checksum", s(preview, "checksum")),
        Map.of());
  }

  private static void checkedBackup(
      FortuneBackend service, Path path, JsonObject value, String label) throws Exception {
    String text = JSON.toJson(value);
    Files.writeString(path, text);
    Files.writeString(path.resolveSibling(path.getFileName() + ".sha256"), hash(text));
    rejected(
        () ->
            request(
                service,
                "GET",
                "/backup/verify",
                obj(),
                Map.of("file", path.getFileName().toString())),
        label + " is refused despite matching SHA-256");
  }

  private static Fixture regressions(FortuneBackend service, Path folder) throws Exception {
    // A stale editor must preserve another administrator's deletion.
    JsonObject deleted = request(service, "GET", "/fortune", obj(), Map.of("id", "2"));
    request(
        service,
        "POST",
        "/fortune/delete",
        obj("id", 2, "expectedRevision", l(deleted, "revision")),
        Map.of());
    long count = l(request(service, "GET", "/fortunes", obj(), Map.of()), "total");
    rejected(
        () ->
            request(
                service,
                "POST",
                "/fortune",
                obj("fortune", deleted, "expectedRevision", l(deleted, "revision")),
                Map.of()),
        "deleted fortune's stale editor cannot create another fortune");
    check(
        l(request(service, "GET", "/fortunes", obj(), Map.of()), "total") == count,
        "refused stale save leaves the fortune collection unchanged");

    // Same-field ABA changes count as later edits even if their final value matches.
    setting(service, "hover", false);
    String first =
        s(
            request(service, "GET", "/history", obj(), Map.of("type", "settings"))
                .getAsJsonArray("rows")
                .get(0)
                .getAsJsonObject(),
            "id");
    setting(service, "hover", true);
    setting(service, "hover", false);
    rejected(
        () -> request(service, "POST", "/undo", obj("id", first), Map.of()),
        "selective undo rejects later same-field change-away-and-back");
    check(!b(settings(service), "hover"), "refused undo preserves the later field value");

    // A preview checksum must also cover the current database definition revision.
    JsonObject staleFile = export(service);
    JsonObject stalePreview =
        request(service, "POST", "/config/preview", obj("file", staleFile), Map.of());
    setting(service, "enabled", false);
    rejected(
        () ->
            request(
                service,
                "POST",
                "/config/import",
                obj("file", staleFile, "checksum", s(stalePreview, "checksum")),
                Map.of()),
        "configuration changed after preview requires another preview");
    check(!b(settings(service), "enabled"), "rejected import preserves later settings");
    setting(service, "enabled", true);

    // A valid final pool may temporarily have no active entry during a naive row loop.
    JsonObject onlyOne = export(service);
    for (JsonElement item : onlyOne.getAsJsonArray("fortunes")) {
      JsonObject fortune = item.getAsJsonObject();
      fortune.addProperty("enabled", i(fortune, "id") == 1);
    }
    importConfig(service, onlyOne);
    JsonObject swap = export(service);
    for (JsonElement item : swap.getAsJsonArray("fortunes")) {
      JsonObject fortune = item.getAsJsonObject();
      fortune.addProperty("enabled", i(fortune, "id") == 3);
    }
    importConfig(service, swap);
    check(
        b(request(service, "GET", "/fortune", obj(), Map.of("id", "3")), "enabled")
            && !b(request(service, "GET", "/fortune", obj(), Map.of("id", "1")), "enabled")
            && l(
                    request(service, "GET", "/state", obj(), Map.of()).getAsJsonObject("pool"),
                    "active")
                == 1,
        "bulk import validates the complete final pool and atomically swaps its active fortune");

    JsonObject firstNew = createFortune(service, "回归新建签文");
    deleteFortune(service, firstNew);
    JsonObject secondNew = createFortune(service, "回归历史签文");
    int deletedId = i(secondNew, "id");
    check(deletedId > i(firstNew, "id"), "deleting the highest fortune ID never reuses that ID");
    JsonObject onlyHistorical = export(service);
    for (JsonElement item : onlyHistorical.getAsJsonArray("fortunes")) {
      JsonObject value = item.getAsJsonObject();
      value.addProperty("enabled", i(value, "id") == deletedId);
    }
    importConfig(service, onlyHistorical);
    UUID pendingPlayer = UUID.randomUUID(), archivedPlayer = UUID.randomUUID();
    rpc(service, pendingPlayer, "login", "RegressionPending");
    JsonObject drawn = rpc(service, pendingPlayer, "draw").getAsJsonObject("record");
    String day = s(drawn, "date"), requestId = UUID.randomUUID().toString();
    JsonObject recovered =
        rpc(service, pendingPlayer, "recover", day, requestId).getAsJsonObject("record");
    JsonObject pending =
        rpc(service, pendingPlayer, "resolve", day, s(recovered, "token"))
            .getAsJsonObject("record");
    check(
        i(pending, "fortuneId") == deletedId,
        "fixture stores a fixed result for the later-deleted fortune");
    JsonObject active = fortune(service, 3);
    active.addProperty("enabled", true);
    request(
        service,
        "POST",
        "/fortune",
        obj("fortune", active, "expectedRevision", l(active, "revision")),
        Map.of());
    deleteFortune(service, fortune(service, deletedId));
    rpc(service, archivedPlayer, "login", "RegressionArchived");
    JsonObject second = rpc(service, archivedPlayer, "draw").getAsJsonObject("record");
    rpc(service, archivedPlayer, "resolve", s(second, "date"), s(second, "token"));
    rpc(service, archivedPlayer, "archive", s(second, "date"), s(second, "token"));

    // Check format, references and audit shape independently; a bad format must not
    // mask acceptance of malformed business records in an otherwise valid format.
    String backup = s(request(service, "POST", "/backup", obj(), Map.of()), "file");
    Path path = folder.resolve("backups").resolve(backup);
    JsonObject original = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    check(
        b(request(service, "GET", "/backup/verify", obj(), Map.of("file", backup)), "verified"),
        "unmodified backup with legitimate creation/deletion audits passes complete validation");
    JsonObject badFormat = original.deepCopy();
    badFormat.addProperty("format", 999);
    checkedBackup(service, path, badFormat, "unsupported backup format");
    JsonObject badRecord = original.deepCopy();
    badRecord
        .getAsJsonArray("records")
        .add(
            obj(
                "id",
                "missing:invalid",
                "player",
                "missing",
                "date",
                "invalid",
                "state",
                "invalid",
                "token",
                "invalid",
                "fortuneId",
                -1));
    checkedBackup(service, path, badRecord, "invalid or orphaned backup record");
    JsonObject badAudit = original.deepCopy();
    JsonObject audit = badAudit.getAsJsonArray("audit").get(0).getAsJsonObject();
    audit.addProperty("id", "not-a-positive-integer");
    audit.addProperty("changes", "not-an-array");
    checkedBackup(service, path, badAudit, "malformed backup audit");
    String restored = JSON.toJson(original);
    Files.writeString(path, restored);
    Files.writeString(path.resolveSibling(backup + ".sha256"), hash(restored));
    check(
        b(request(service, "GET", "/backup/verify", obj(), Map.of("file", backup)), "verified"),
        "validation failures do not prevent verifying a valid backup again");
    return new Fixture(path, pendingPlayer, archivedPlayer, day, requestId, pending, deletedId);
  }

  private static void restoreChecks(
      Connection admin, String password, FortuneBackend source, Fixture fixture) throws Exception {
    rejected(
        () -> source.restoreEmpty(fixture.backup(), "WRONG-CONFIRMATION"),
        "restore requires its exact confirmation marker");
    String before = JSON.toJson(export(source));
    rejected(
        () -> source.restoreEmpty(fixture.backup(), "RESTORE-EMPTY-DAILYFORTUNES"),
        "restore refuses a database containing business data");
    check(
        before.equals(JSON.toJson(export(source))),
        "refused restore preserves existing definitions and sequence");
    for (boolean legacy : List.of(false, true)) {
      Path snapshot = fixture.backup();
      if (legacy) {
        JsonObject oldFormat = JsonParser.parseString(Files.readString(snapshot)).getAsJsonObject();
        oldFormat.remove("nextId");
        snapshot = snapshot.resolveSibling("legacy-without-sequence.json");
        String text = JSON.toJson(oldFormat);
        Files.writeString(snapshot, text);
        Files.writeString(snapshot.resolveSibling(snapshot.getFileName() + ".sha256"), hash(text));
      }
      String schema = "df_regression_restore_" + UUID.randomUUID().toString().replace("-", "");
      try (Statement statement = admin.createStatement()) {
        statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      }
      try (FortuneBackend restored =
          new FortuneBackend(
              "jdbc:mariadb://127.0.0.1:43322/" + schema,
              "root",
              password,
              fixture.backup().getParent().getParent().resolve(schema))) {
        check(
            b(restored.restoreEmpty(snapshot, "RESTORE-EMPTY-DAILYFORTUNES"), "restored"),
            legacy
                ? "legacy backup restores into an empty database"
                : "complete backup restores into an empty database");
        JsonObject counts =
            request(restored, "GET", "/state", obj(), Map.of()).getAsJsonObject("counts");
        check(
            l(counts, "players") == 2 && l(counts, "records") == 2,
            "restore preserves all players and immutable daily records");
        JsonArray pending =
            rpc(restored, fixture.pendingPlayer(), "pending").getAsJsonArray("records");
        check(
            pending.size() == 1 && pending.get(0).equals(fixture.pendingRecord()),
            "restore preserves the delivery queue, validity token, state and deleted-fortune"
                + " snapshot");
        JsonObject retry =
            rpc(restored, fixture.pendingPlayer(), "recover", fixture.day(), fixture.requestId())
                .getAsJsonObject("record");
        check(
            s(retry, "token").equals(s(fixture.pendingRecord(), "token")),
            "restored recovery request remains idempotent without rotating its token");
        rejected(
            () ->
                rpc(
                    restored,
                    fixture.archivedPlayer(),
                    "recover",
                    fixture.day(),
                    UUID.randomUUID().toString()),
            "restored permanent archive cannot be recovered as a new physical receipt");
        JsonObject fresh = createFortune(restored, "恢复后的新签文");
        check(
            i(fresh, "id") > fixture.deletedId(),
            legacy
                ? "legacy restore derives its ID floor from deleted-fortune history"
                : "restore preserves the non-reusable fortune ID sequence");
        Path finalSnapshot = snapshot;
        rejected(
            () -> restored.restoreEmpty(finalSnapshot, "RESTORE-EMPTY-DAILYFORTUNES"),
            "a completed restore cannot be applied twice over business data");
      } finally {
        try (Statement statement = admin.createStatement()) {
          statement.execute("DROP DATABASE " + schema);
        }
      }
    }
  }

  public static void main(String[] args) throws Exception {
    String base = System.getenv("DF_TEST_JDBC");
    if (base == null) {
      System.out.println(
          "DailyFortunes SQL regression checks require DF_TEST_JDBC on isolated local port 43322");
      return;
    }
    if (!base.startsWith("jdbc:mariadb://127.0.0.1:43322/")) {
      throw new IllegalStateException(
          "Regression tests are restricted to isolated local port 43322");
    }
    Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", System.getenv().getOrDefault("DF_TEST_PASSWORD", ""));
    String schema = "df_regression_" + UUID.randomUUID().toString().replace("-", "");
    Path folder = Path.of("build/checks", schema);
    try (Connection admin = new org.mariadb.jdbc.Driver().connect(base, properties)) {
      try (Statement statement = admin.createStatement()) {
        statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      }
      try (FortuneBackend service =
          new FortuneBackend(
              "jdbc:mariadb://127.0.0.1:43322/" + schema,
              "root",
              properties.getProperty("password"),
              folder)) {
        Fixture fixture = regressions(service, folder);
        restoreChecks(admin, properties.getProperty("password"), service, fixture);
      } finally {
        try (Statement statement = admin.createStatement()) {
          statement.execute("DROP DATABASE " + schema);
        }
      }
    }
    System.out.println("DailyFortunes service regression checks passed: " + checks);
  }
}
