package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public final class ServiceChecks {
  static int checks;

  static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    checks++;
  }

  static void rejected(Runnable work, String label) {
    try {
      work.run();
      throw new AssertionError(label);
    } catch (IllegalArgumentException | IllegalStateException expected) {
      checks++;
    }
  }

  static JsonObject rpc(FortuneBackend b, UUID id, String op, Object... args) {
    JsonArray a = new JsonArray();
    a.add(id.toString());
    for (Object x : args) a.add(JSON.toJsonTree(x));
    return b.rpc(op, obj("args", a), id.toString(), Set.of(id));
  }

  static JsonObject web(
      FortuneBackend b, String method, String path, JsonObject body, Map<String, String> q) {
    return b.request(method, path, body, q, "integration", Set.of());
  }

  public static void main(String[] args) throws Exception {
    if (Boolean.getBoolean("dailyfortunes.packaged")) {
      check(
          FortuneBackend.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .getPath()
              .endsWith("dailyfortunes-forge-1.20.1-0.1.1.jar"),
          "packaged backend is loaded first");
      check(
          Class.forName("cn.pixelmc.dailyfortunes.vendor.h2.Driver") != null,
          "relocated H2 driver loads");
      check(
          Class.forName("cn.pixelmc.dailyfortunes.vendor.mariadb.Driver") != null,
          "relocated MariaDB driver loads");
    }
    JsonObject f =
        obj(
            "id",
            1,
            "title",
            "灯认归人",
            "grade",
            "上上签",
            "poem",
            List.of("远岫云收见旧津", "一灯隔水待归人", "柴门未启梅先觉", "暗递清香到客巾"),
            "interpretation",
            "灯守旧津，归有所依。",
            "weight",
            1,
            "enabled",
            true);
    validateFortune(f);
    check(i(choose(List.of(f), .999), "id") == 1, "weighted draw");
    JsonObject bad = f.deepCopy();
    bad.addProperty("weight", Double.NaN);
    rejected(() -> validateFortune(bad), "nonfinite weight");
    JsonArray changes = new JsonArray();
    changes.add(obj("field", "title", "before", "旧名", "after", "新名"));
    check(
        s(selectiveUndo(obj("title", "新名", "untouched", 5), changes), "title").equals("旧名"),
        "selective undo");
    rejected(() -> selectiveUndo(obj("title", "后改"), changes), "conflicting undo");
    try (FortuneBackend unavailable =
        new FortuneBackend(
            "jdbc:mariadb://127.0.0.1:1/unused", "x", "x", Path.of("build/checks/unavailable"))) {
      check(!b(unavailable.status(), "database"), "constructor has no IO");
      rejected(
          () ->
              unavailable.rpc(
                  "draw", obj("args", List.of(UUID.randomUUID().toString())), "", Set.of()),
          "admission required");
    }
    String embeddedPath =
        Path.of("build/checks/h2-" + UUID.randomUUID() + "/fortunes")
            .toAbsolutePath()
            .toString()
            .replace('\\', '/');
    UUID local = UUID.randomUUID();
    try (FortuneBackend embedded =
        new FortuneBackend(
            "jdbc:h2:file:"
                + embeddedPath
                + ";MODE=MySQL;NON_KEYWORDS=DAY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
            "sa",
            "",
            Path.of("build/checks/h2-backups"))) {
      rpc(embedded, local, "login", "Singleplayer");
      JsonObject r = rpc(embedded, local, "draw").getAsJsonObject("record");
      rpc(embedded, local, "resolve", s(r, "date"), s(r, "token"));
      rpc(embedded, local, "archive", s(r, "date"), s(r, "token"));
      check(
          rpc(embedded, local, "calendar", "").getAsJsonArray("records").size() == 1,
          "embedded complete game flow");
      web(embedded, "POST", "/backup", obj(), Map.of());
      checks++;
    }
    cn.pixelmc.dailyfortunes.client.StickPhysics physics =
        new cn.pixelmc.dailyfortunes.client.StickPhysics();
    for (int n = 0; n < 240; n++) physics.advance(1.0 / 120, 1.2, .6, n < 30 ? 5 : 0);
    for (int n = 0; n < 3000; n++) physics.advance(1.0 / 120, 0, 0, 0);
    double settled = physics.x[0];
    check(
        Math.abs(settled - cn.pixelmc.dailyfortunes.client.StickPhysics.PIVOTS[0][0]) > .001,
        "physics retains changed rest position");
    for (int n = 0; n < 1000; n++) physics.advance(1.0 / 120, 0, 0, 0);
    check(Math.abs(physics.x[0] - settled) < .00001, "physics sleeps without recentring");
    for (int n = 0; n < 10000; n++) {
      physics.advance(.03, Math.sin(n) * 100, Math.cos(n) * 100, 100);
      for (int j = 0; j < 7; j++)
        check(
            Double.isFinite(physics.x[j])
                && physics.y[j] >= 0
                && physics.y[j] <= .0451
                && Math.hypot(physics.x[j] - .5, physics.z[j] - .5) <= .16001,
            "confined particle");
    }
    String url = System.getenv("DF_TEST_JDBC");
    if (url == null) {
      System.out.println(
          "DailyFortunes pure checks passed: " + checks + "; SQL checks require DF_TEST_JDBC");
      return;
    }
    if (!url.startsWith("jdbc:mariadb://127.0.0.1:43317/"))
      throw new IllegalStateException("Tests restricted to isolated local port43317");
    String schema = "df_check_" + UUID.randomUUID().toString().replace("-", "");
    Properties props = new Properties();
    props.setProperty("user", "root");
    props.setProperty("password", "");
    try (Connection admin = new org.mariadb.jdbc.Driver().connect(url, props)) {
      try (Statement st = admin.createStatement()) {
        st.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      }
      Path folder = Path.of("build/checks", schema);
      UUID player = UUID.randomUUID(), other = UUID.randomUUID();
      try {
        String dburl = "jdbc:mariadb://127.0.0.1:43317/" + schema;
        try (FortuneBackend service = new FortuneBackend(dburl, "root", "", folder)) {
          JsonObject state = web(service, "GET", "/state", obj(), Map.of());
          check(i(state.getAsJsonObject("pool"), "active") == 60, "60 defaults");
          rejected(() -> rpc(service, player, "draw"), "no unknown profile writes");
          rpc(service, player, "login", "TestPlayer");
          rpc(service, other, "login", "OtherPlayer");
          ExecutorService pool = Executors.newFixedThreadPool(8);
          List<Future<JsonObject>> jobs = new ArrayList<>();
          for (int n = 0; n < 8; n++)
            jobs.add(
                pool.submit(
                    () -> {
                      try {
                        return rpc(service, player, "draw");
                      } catch (IllegalArgumentException e) {
                        return null;
                      }
                    }));
          int successful = 0;
          JsonObject receipt = null;
          for (Future<JsonObject> job : jobs) {
            JsonObject value = job.get();
            if (value != null) {
              successful++;
              receipt = value.getAsJsonObject("record");
            }
          }
          pool.shutdownNow();
          check(successful == 1, "concurrent draw exactly once");
          final JsonObject drawn = receipt;
          String day = s(drawn, "date"), oldToken = s(drawn, "token");
          rejected(() -> rpc(service, other, "resolve", day, oldToken), "foreign receipt refused");
          JsonObject definition =
              web(service, "GET", "/fortune", obj(), Map.of("id", s(drawn, "fortuneId")));
          String original = s(drawn.getAsJsonObject("snapshot"), "title");
          definition.addProperty("title", "后改签名");
          web(
              service,
              "POST",
              "/fortune",
              obj("fortune", definition, "expectedRevision", l(definition, "revision")),
              Map.of());
          check(
              s(
                      rpc(service, player, "pending")
                          .getAsJsonArray("records")
                          .get(0)
                          .getAsJsonObject()
                          .getAsJsonObject("snapshot"),
                      "title")
                  .equals(original),
              "draw snapshot immutable");
          String request = UUID.randomUUID().toString();
          JsonObject recovered =
              rpc(service, player, "recover", day, request).getAsJsonObject("record");
          check(!s(recovered, "token").equals(oldToken), "replacement rotates token");
          check(
              s(rpc(service, player, "recover", day, request).getAsJsonObject("record"), "token")
                  .equals(s(recovered, "token")),
              "retry idempotent");
          rejected(() -> rpc(service, player, "resolve", day, oldToken), "old duplicate invalid");
          JsonObject resolved =
              rpc(service, player, "resolve", day, s(recovered, "token")).getAsJsonObject("record");
          check(s(resolved, "state").equals("loose"), "resolve transition");
          rpc(service, player, "ack", day, s(resolved, "token"));
          check(
              rpc(service, player, "pending").getAsJsonArray("records").isEmpty(),
              "ack clears delivery queue");
          JsonObject archived =
              rpc(service, player, "archive", day, s(resolved, "token")).getAsJsonObject("record");
          check(s(archived, "state").equals("archived"), "archive transition");
          rpc(service, player, "archive", day, s(resolved, "token"));
          checks++;
          rejected(
              () -> rpc(service, player, "recover", day, UUID.randomUUID().toString()),
              "archived cannot recover");
          JsonObject cal =
              web(
                  service,
                  "GET",
                  "/calendar",
                  obj(),
                  Map.of("player", player.toString(), "month", day.substring(0, 7)));
          check(
              !cal.getAsJsonArray("records").get(0).getAsJsonObject().has("token"),
              "admin calendar no receipt token");
          check(cal.getAsJsonObject("profile").has("online"), "calendar presence supplied");
          JsonObject backup = web(service, "POST", "/backup", obj(), Map.of());
          String file = s(backup, "file");
          check(
              b(web(service, "GET", "/backup/verify", obj(), Map.of("file", file)), "verified"),
              "backup checksum verified");
          String backupText =
              s(web(service, "GET", "/backup/download", obj(), Map.of("file", file)), "text");
          check(
              JsonParser.parseString(backupText).getAsJsonObject().getAsJsonArray("requests").size()
                  == 1,
              "backup idempotency included");
          rejected(
              () ->
                  web(service, "GET", "/backup/download", obj(), Map.of("file", "../server.json")),
              "path traversal rejected");
          check(
              l(
                      web(
                          service,
                          "GET",
                          "/players",
                          obj(),
                          Map.of("presence", "offline", "size", "1")),
                      "total")
                  == 2,
              "player count before pagination");
        }
        try (FortuneBackend reopened = new FortuneBackend(dburl, "root", "", folder)) {
          check(
              rpc(reopened, player, "calendar", "").getAsJsonArray("records").size() == 1,
              "restart preserves archive");
        }
      } finally {
        try (Statement st = admin.createStatement()) {
          st.execute("DROP DATABASE " + schema);
        }
      }
    }
    System.out.println("DailyFortunes service checks passed: " + checks);
  }
}
