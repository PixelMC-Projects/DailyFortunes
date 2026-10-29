package cn.pixelmc.dailyfortunes.service;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.text.Collator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Shared authority for Manager and standalone Forge. Call only from bounded I/O workers. */
public final class FortuneBackend implements AutoCloseable {
  public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
  public static final List<String> GRADES = List.of("上上签", "上吉签", "中平签", "小凶签", "下下签");
  public static final String VERSION = "0.3.1";
  private final String url, user, password;
  private final Path folder;
  private final SecureRandom random = new SecureRandom();
  private boolean ready, closed;
  private volatile boolean healthy;
  private volatile long lastSuccess;
  private String lastError = "尚未连接";
  private volatile Function<JsonObject, JsonObject> platform;
  private ScheduledFuture<?> automaticBackup;
  private final ScheduledExecutorService backups =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "DailyFortunes-backup");
            t.setDaemon(true);
            return t;
          });

  public FortuneBackend(String url, String user, String password, Path folder) {
    if (url == null || !(url.startsWith("jdbc:mariadb://") || url.startsWith("jdbc:h2:file:")))
      throw new IllegalArgumentException("需要 MariaDB JDBC 地址");
    this.url = url;
    this.user = user;
    this.password = password;
    this.folder = folder.toAbsolutePath().normalize();
    scheduleBackup();
  }

  private void scheduleBackup() {
    automaticBackup =
        backups.scheduleWithFixedDelay(
            () -> {
              synchronized (this) {
                if (platform != null || closed) return;
                try {
                  request("POST", "/backup", new JsonObject(), Map.of(), "自动备份", Set.of());
                } catch (Exception ignored) {
                }
              }
            },
            12,
            12,
            TimeUnit.HOURS);
  }

  /** Bind a Manager-owned, namespace-scoped directory. Null restores standalone ownership. */
  public synchronized void attachPlatform(Function<JsonObject, JsonObject> platform) {
    if (closed) throw new IllegalStateException("抽签服务已停止");
    if (automaticBackup != null) automaticBackup.cancel(false);
    this.platform = platform;
    if (platform == null) scheduleBackup();
  }

  public static JsonObject storageManifest() {
    return obj(
        "version",
        1,
        "scope",
        "global",
        "label",
        "每日一签",
        "name",
        "DailyFortunes",
        "page",
        "fortunes",
        "preference",
        "fortunes",
        "tables",
        List.of(
            "df_schema",
            "df_settings",
            "df_fortunes",
            "df_players",
            "df_records",
            "df_audit",
            "df_ids",
            "df_requests"),
        "fields",
        List.of(),
        "files",
        List.of("definitions.json"),
        "capabilities",
        List.of("calendar", "receipts", "backup", "conflict-aware-undo"));
  }

  private JsonObject platformCall(JsonObject request) {
    Function<JsonObject, JsonObject> callback = platform;
    if (callback == null) throw new IllegalStateException("统一玩家库未连接");
    try {
      JsonObject response = callback.apply(request);
      if (response == null || response.has("error")) throw new IllegalStateException("统一玩家库暂不可用");
      return response;
    } catch (RuntimeException e) {
      throw new IllegalStateException("统一玩家库暂不可用，请稍后重试", e);
    }
  }

  private JsonObject platformProfile(String id) {
    JsonObject response = platformCall(obj("op", "profiles.get", "player", id));
    if (!response.has("profile") || response.get("profile").isJsonNull())
      throw new IllegalArgumentException("统一玩家库中没有该玩家");
    JsonObject profile = response.getAsJsonObject("profile");
    if (!id.equals(s(profile, "id")) || s(profile, "name").isBlank())
      throw new IllegalStateException("统一玩家资料不完整");
    return profile.deepCopy();
  }

  private List<JsonObject> platformProfiles() {
    JsonObject response = platformCall(obj("op", "profiles.list"));
    if (!response.has("players") || !response.get("players").isJsonArray())
      throw new IllegalStateException("统一玩家目录不完整");
    List<JsonObject> players = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (JsonElement entry : response.getAsJsonArray("players")) {
      JsonObject player = entry.getAsJsonObject();
      UUID id = UUID.fromString(s(player, "id"));
      if (s(player, "name").isBlank() || !seen.add(id)) throw new IllegalStateException("统一玩家目录无效");
      players.add(player.deepCopy());
    }
    return players;
  }

  public JsonObject status() {
    return obj(
        "database",
        healthy,
        "version",
        VERSION,
        "managed",
        platform != null,
        "backupOwner",
        platform == null ? "dailyfortunes" : "manager",
        "lastSuccess",
        lastSuccess,
        "message",
        healthy ? "已连接" : lastError);
  }

  public void close() {
    closed = true;
    backups.shutdownNow();
  }

  private Connection connect() throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", password);
    p.setProperty("connectTimeout", "3000");
    p.setProperty("socketTimeout", "10000");
    p.setProperty("allowLocalInfile", "false");
    Connection c =
        url.startsWith("jdbc:h2:")
            ? embeddedDriver().connect(url, p)
            : new org.mariadb.jdbc.Driver().connect(url, p);
    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return c;
  }

  private static java.sql.Driver embeddedDriver() throws SQLException {
    try {
      return (java.sql.Driver)
          Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new SQLException("Embedded database driver unavailable", e);
    }
  }

  private void init(Connection c) throws Exception {
    if (ready) return;
    if (!url.startsWith("jdbc:h2:")
        && number(c, "SELECT GET_LOCK('dailyfortunes_schema_v1',5)") != 1)
      throw new SQLException("schema lock");
    try {
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_schema (id INT PRIMARY KEY, version INT NOT NULL)"
              + " ENGINE=InnoDB");
      long version = number(c, "SELECT COALESCE(MAX(version),0) FROM df_schema");
      if (version > 1) throw new IllegalStateException("数据库版本高于程序版本");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_settings (id INT PRIMARY KEY, payload LONGTEXT NOT NULL)"
              + " ENGINE=InnoDB");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_fortunes (id INT PRIMARY KEY, payload LONGTEXT NOT NULL)"
              + " ENGINE=InnoDB");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_players (uuid CHAR(36) PRIMARY KEY,name VARCHAR(64) NOT"
              + " NULL,name_key VARCHAR(1024) NOT NULL,last_seen BIGINT NOT NULL) ENGINE=InnoDB"
              + " DEFAULT CHARSET=utf8mb4");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_records (player CHAR(36) NOT NULL,day CHAR(10) NOT"
              + " NULL,state VARCHAR(16) NOT NULL,at_ms BIGINT NOT NULL,fortune INT NOT"
              + " NULL,pending BOOLEAN NOT NULL,payload LONGTEXT NOT NULL,PRIMARY"
              + " KEY(player,day),INDEX(state),INDEX(fortune),INDEX(at_ms)) ENGINE=InnoDB DEFAULT"
              + " CHARSET=utf8mb4");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_audit (id BIGINT PRIMARY KEY AUTO_INCREMENT,at_ms BIGINT"
              + " NOT NULL,category VARCHAR(24) NOT NULL,subject VARCHAR(255) NOT NULL,payload"
              + " LONGTEXT NOT NULL,INDEX(at_ms),INDEX(category)) ENGINE=InnoDB DEFAULT"
              + " CHARSET=utf8mb4");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_ids (id INT PRIMARY KEY,next_id INT NOT NULL)"
              + " ENGINE=InnoDB");
      exec(
          c,
          "CREATE TABLE IF NOT EXISTS df_requests (id VARCHAR(100) PRIMARY KEY,player CHAR(36) NOT"
              + " NULL,day CHAR(10) NOT NULL,token CHAR(36) NOT NULL) ENGINE=InnoDB");
      if (number(c, "SELECT COUNT(*) FROM df_settings") == 0) {
        c.setAutoCommit(false);
        exec(c, "INSERT INTO df_settings VALUES(1,?)", JSON.toJson(defaultSettings()));
        try (InputStream stream =
            FortuneBackend.class.getResourceAsStream("/defaults/fortunes.json")) {
          if (stream == null) throw new IOException("缺少默认六十签资源");
          for (JsonElement e :
              JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                  .getAsJsonObject()
                  .getAsJsonArray("fortunes")) {
            JsonObject f = e.getAsJsonObject();
            f.addProperty("weight", 1);
            f.addProperty("enabled", true);
            f.addProperty("revision", 1);
            f.addProperty("createdAt", System.currentTimeMillis());
            exec(c, "INSERT INTO df_fortunes VALUES(?,?)", i(f, "id"), JSON.toJson(f));
          }
        }
        exec(c, "INSERT INTO df_schema VALUES(1,1)");
        c.commit();
        c.setAutoCommit(true);
      }
      if (number(c, "SELECT COUNT(*) FROM df_ids") == 0)
        exec(
            c,
            "INSERT INTO df_ids VALUES(1,?)",
            number(c, "SELECT COALESCE(MAX(id),0)+1 FROM df_fortunes"));
      ready = true;
    } finally {
      if (!url.startsWith("jdbc:h2:")) exec(c, "DO RELEASE_LOCK('dailyfortunes_schema_v1')");
    }
  }

  public static JsonObject defaultSettings() {
    return obj(
        "enabled",
        true,
        "timezone",
        "Asia/Shanghai",
        "recovery",
        true,
        "archive",
        true,
        "hover",
        true,
        "retentionDays",
        30,
        "revision",
        1);
  }

  @FunctionalInterface
  private interface Job {
    JsonObject run(Connection c) throws Exception;
  }

  private synchronized JsonObject transaction(Job job) {
    if (closed) throw new IllegalStateException("抽签服务已停止");
    try (Connection c = connect()) {
      init(c);
      c.setAutoCommit(false);
      try {
        one(c, "SELECT payload FROM df_settings WHERE id=1 FOR UPDATE");
        JsonObject out = job.run(c);
        c.commit();
        healthy = true;
        lastSuccess = System.currentTimeMillis();
        return out;
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      healthy = false;
      ready = false;
      lastError = "存储暂不可用，请稍后重试";
      throw new IllegalStateException(lastError, e);
    }
  }

  private JsonObject settings(Connection c) throws SQLException {
    return one(c, "SELECT payload FROM df_settings WHERE id=1");
  }

  private List<JsonObject> fortunes(Connection c) throws SQLException {
    return rows(c, "SELECT payload FROM df_fortunes ORDER BY id");
  }

  private JsonObject record(Connection c, String player, String date) throws SQLException {
    return one(
        c, "SELECT payload FROM df_records WHERE player=? AND day=? FOR UPDATE", player, date);
  }

  private void saveRecord(Connection c, JsonObject r) throws SQLException {
    exec(
        c,
        "UPDATE df_records SET state=?,pending=?,payload=? WHERE player=? AND day=?",
        s(r, "state"),
        b(r, "pending"),
        JSON.toJson(r),
        s(r, "player"),
        s(r, "date"));
  }

  private static JsonObject publicRecord(JsonObject r) {
    JsonObject copy = r.deepCopy();
    copy.remove("token");
    return copy;
  }

  private String today(Connection c) throws SQLException {
    return LocalDate.now(ZoneId.of(s(settings(c), "timezone"))).toString();
  }

  private static void date(String v) {
    try {
      LocalDate.parse(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
    }
  }

  private static void owner(JsonObject r, String token) {
    if (r == null || !s(r, "token").equals(token))
      throw new IllegalArgumentException("签笺已失效或不属于你，可补领原签");
  }

  public JsonObject rpc(String op, JsonObject data, String playerProof, Set<UUID> admitted) {
    JsonArray args = data.getAsJsonArray("args");
    if (args == null || args.isEmpty()) throw new IllegalArgumentException("缺少玩家身份");
    String id = args.get(0).getAsString();
    UUID uuid = UUID.fromString(id);
    if (!id.equals(playerProof) || !admitted.contains(uuid))
      throw new IllegalArgumentException("玩家尚未通过入服验证");
    JsonObject identity = null;
    if (platform != null) {
      JsonObject context = platformCall(obj("op", "profiles.loginContext", "player", id));
      if (!b(context, "admitted") || s(context, "eventId").isBlank())
        throw new IllegalArgumentException("玩家尚未通过统一入服验证");
      identity = platformProfile(id);
    }
    final JsonObject coreIdentity = identity;
    return transaction(
        c -> {
          if (op.equals("login")) {
            String name =
                coreIdentity == null ? args.get(1).getAsString() : s(coreIdentity, "name");
            if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("无效玩家名");
            exec(
                c,
                "INSERT INTO df_players VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " name=VALUES(name),name_key=VALUES(name_key),last_seen=VALUES(last_seen)",
                id,
                name,
                sortKey(name),
                coreIdentity == null ? System.currentTimeMillis() : l(coreIdentity, "lastSeen"));
            return obj("today", today(c), "settings", settings(c));
          }
          if (coreIdentity == null
              && number(c, "SELECT COUNT(*) FROM df_players WHERE uuid=?", id) == 0)
            throw new IllegalArgumentException("玩家资料尚未加载");
          if (op.equals("calendar")) return calendar(c, id, args.get(1).getAsString());
          if (op.equals("pending"))
            return obj(
                "records",
                rows(
                    c,
                    "SELECT payload FROM df_records WHERE player=? AND pending=TRUE AND"
                        + " state<>'archived' ORDER BY day LIMIT 32",
                    id));
          if (op.equals("draw")) {
            JsonObject cfg = settings(c);
            if (!b(cfg, "enabled")) throw new IllegalArgumentException("每日抽签暂未开放");
            String day = today(c);
            if (record(c, id, day) != null) throw new IllegalArgumentException("今日已抽过签，可补领原签，不可重抽");
            JsonObject f = choose(fortunes(c), random.nextDouble());
            String name =
                coreIdentity == null
                    ? scalar(c, "SELECT name FROM df_players WHERE uuid=?", id)
                    : s(coreIdentity, "name");
            JsonObject r =
                obj(
                    "id",
                    id + ":" + day,
                    "player",
                    id,
                    "owner",
                    name,
                    "date",
                    day,
                    "fortuneId",
                    i(f, "id"),
                    "state",
                    "unread",
                    "at",
                    System.currentTimeMillis(),
                    "snapshot",
                    f,
                    "token",
                    UUID.randomUUID().toString(),
                    "pending",
                    true);
            exec(
                c,
                "INSERT INTO df_records VALUES(?,?,?,?,?,?,?)",
                id,
                day,
                "unread",
                l(r, "at"),
                i(f, "id"),
                true,
                JSON.toJson(r));
            return obj("record", r);
          }
          String day = args.get(1).getAsString();
          date(day);
          JsonObject r = record(c, id, day);
          if (op.equals("display")) {
            if (r == null || !s(r, "state").equals("archived"))
              throw new IllegalArgumentException("只能展示已收藏的签笺");
            return obj("record", publicRecord(r), "settings", settings(c));
          }
          if (op.equals("recover"))
            return obj("record", recover(c, id, day, args.get(2).getAsString(), "玩家自助"));
          owner(r, args.get(2).getAsString());
          switch (op) {
            case "resolve" -> {
              if (s(r, "state").equals("archived")) throw new IllegalArgumentException("签笺已经归档");
              r.addProperty("state", "loose");
              r.addProperty("pending", true);
              saveRecord(c, r);
            }
            case "archive" -> {
              if (!b(settings(c), "archive")) throw new IllegalArgumentException("自动归档已关闭");
              if (s(r, "state").equals("unread")) throw new IllegalArgumentException("请先解签");
              r.addProperty("state", "archived");
              r.addProperty("pending", false);
              saveRecord(c, r);
            }
            case "ack" -> {
              r.addProperty("pending", false);
              saveRecord(c, r);
            }
            case "read" -> {
              if (s(r, "state").equals("unread")) throw new IllegalArgumentException("请先在解签龛解签");
            }
            default -> throw new IllegalArgumentException("未知操作");
          }
          return obj("record", r);
        });
  }

  public static JsonObject choose(List<JsonObject> list, double unit) {
    double sum = list.stream().filter(x -> b(x, "enabled")).mapToDouble(x -> d(x, "weight")).sum();
    if (!(sum > 0) || !Double.isFinite(sum)) throw new IllegalArgumentException("暂无可抽签文");
    double n = unit * sum;
    JsonObject last = null;
    for (JsonObject f : list)
      if (b(f, "enabled") && d(f, "weight") > 0) {
        last = f;
        n -= d(f, "weight");
        if (n < 0) return f.deepCopy();
      }
    return Objects.requireNonNull(last).deepCopy();
  }

  private JsonObject recover(Connection c, String id, String day, String requestId, String actor)
      throws Exception {
    if (!b(settings(c), "recovery")) throw new IllegalArgumentException("遗失补领已关闭");
    if (requestId.length() < 8 || requestId.length() > 100)
      throw new IllegalArgumentException("补领请求编号无效");
    JsonObject r = record(c, id, day);
    if (r == null || s(r, "state").equals("archived"))
      throw new IllegalArgumentException("只能补领自己已抽取且未归档的签");
    if (number(c, "SELECT COUNT(*) FROM df_requests WHERE id=?", requestId) > 0) {
      if (number(
              c,
              "SELECT COUNT(*) FROM df_requests WHERE id=? AND player=? AND day=? AND token=?",
              requestId,
              id,
              day,
              s(r, "token"))
          == 0) throw new IllegalArgumentException("补领请求已过期");
      return r;
    }
    r.addProperty("token", UUID.randomUUID().toString());
    r.addProperty("pending", true);
    saveRecord(c, r);
    exec(c, "INSERT INTO df_requests VALUES(?,?,?,?)", requestId, id, day, s(r, "token"));
    audit(
        c,
        "recovery",
        "补领签笺",
        s(r, "owner"),
        "恢复 " + day + " 原签，旧副本失效",
        actor,
        "receipt",
        id,
        new JsonArray(),
        false);
    return r;
  }

  private JsonObject calendar(Connection c, String id, String month) throws SQLException {
    YearMonth ym;
    try {
      ym = month.isBlank() ? YearMonth.from(LocalDate.parse(today(c))) : YearMonth.parse(month);
    } catch (Exception e) {
      throw new IllegalArgumentException("月份无效");
    }
    List<JsonObject> records =
        rows(
            c,
            "SELECT payload FROM df_records WHERE player=? AND day>=? AND day<=? ORDER BY day",
            id,
            ym.atDay(1).toString(),
            ym.atEndOfMonth().toString());
    return obj(
        "month",
        ym.toString(),
        "profile",
        profile(c, id),
        "records",
        records.stream().map(FortuneBackend::publicRecord).toList(),
        "today",
        today(c),
        "settings",
        settings(c));
  }

  private JsonObject profile(Connection c, String id) throws SQLException {
    if (platform != null) return platformProfile(id);
    try (PreparedStatement p =
            prepare(c, "SELECT uuid,name,last_seen FROM df_players WHERE uuid=?", id);
        ResultSet r = p.executeQuery()) {
      if (!r.next()) throw new IllegalArgumentException("玩家不存在");
      return obj("id", r.getString(1), "name", r.getString(2), "lastSeen", r.getLong(3));
    }
  }

  public JsonObject request(
      String method,
      String path,
      JsonObject body,
      Map<String, String> q,
      String actor,
      Set<UUID> online) {
    return transaction(
        c -> {
          boolean get = method.equals("GET");
          if (!get && !method.equals("POST")) throw new IllegalArgumentException("不支持的请求");
          if (get)
            return switch (path) {
              case "/state" ->
                  obj(
                      "settings",
                      settings(c),
                      "health",
                      obj(
                          "database",
                          true,
                          "version",
                          VERSION,
                          "managed",
                          platform != null,
                          "backupOwner",
                          platform == null ? "dailyfortunes" : "manager",
                          "lastSuccess",
                          System.currentTimeMillis()),
                      "today",
                      today(c),
                      "counts",
                      obj(
                          "players",
                          platform == null
                              ? number(c, "SELECT COUNT(*) FROM df_players")
                              : platformProfiles().size(),
                          "records",
                          number(c, "SELECT COUNT(*) FROM df_records")),
                      "pool",
                      pool(fortunes(c)));
              case "/fortunes" -> listFortunes(c, q);
              case "/fortune" -> {
                JsonObject f =
                    one(
                        c,
                        "SELECT payload FROM df_fortunes WHERE id=?",
                        Integer.parseInt(q.get("id")));
                if (f == null) throw new IllegalArgumentException("签文不存在");
                f.addProperty(
                    "draws",
                    number(c, "SELECT COUNT(*) FROM df_records WHERE fortune=?", i(f, "id")));
                f.add(
                    "recentDraws",
                    JSON.toJsonTree(
                        rows(
                                c,
                                "SELECT payload FROM df_records WHERE fortune=? ORDER BY at_ms DESC"
                                    + " LIMIT 40",
                                i(f, "id"))
                            .stream()
                            .map(FortuneBackend::publicRecord)
                            .toList()));
                yield f;
              }
              case "/players" -> players(c, q, online);
              case "/calendar" -> {
                String id = UUID.fromString(q.get("player")).toString();
                JsonObject result = calendar(c, id, q.get("month"));
                result
                    .getAsJsonObject("profile")
                    .addProperty("online", online.contains(UUID.fromString(id)));
                yield result;
              }
              case "/history" -> history(c, q);
              case "/backups" -> obj("rows", backupList());
              case "/backup/verify" -> verify(q.get("file"));
              case "/backup/download" -> downloadBackup(q.get("file"), false);
              case "/backup/checksum" -> downloadBackup(q.get("file"), true);
              case "/config/export" -> config(c);
              default -> throw new IllegalArgumentException("接口不存在");
            };
          return switch (path) {
            case "/fortune" -> saveFortune(c, body, actor);
            case "/fortune/delete" -> deleteFortune(c, body, actor);
            case "/settings" -> saveSettings(c, body, actor);
            case "/recover" -> {
              JsonObject r =
                  recover(
                      c,
                      UUID.fromString(s(body, "player")).toString(),
                      s(body, "date"),
                      s(body, "requestId"),
                      actor);
              yield obj("record", publicRecord(r), "delivery", "queued");
            }
            case "/undo" -> undo(c, s(body, "id"), actor);
            case "/backup" -> backup(c);
            case "/config/preview" -> {
              JsonObject file = fileObject(body);
              validateConfig(file);
              yield obj("file", file, "checksum", hash(JSON.toJson(file) + "|" + configStamp(c)));
            }
            case "/config/import" -> {
              JsonObject file = fileObject(body);
              if (!hash(JSON.toJson(file) + "|" + configStamp(c)).equals(s(body, "checksum")))
                throw new IllegalArgumentException("配置或服务器内容已改变，请重新预览");
              yield importConfig(c, file, actor);
            }
            case "/config/reload" -> {
              Path f = folder.resolve("definitions.json");
              if (!Files.exists(f))
                throw new IllegalArgumentException("请先导出并保存 definitions.json 到抽签配置目录");
              yield importConfig(c, parseBounded(f), actor);
            }
            default -> throw new IllegalArgumentException("接口不存在");
          };
        });
  }

  private JsonObject listFortunes(Connection c, Map<String, String> q) throws SQLException {
    List<JsonObject> all = fortunes(c);
    long active = all.stream().filter(FortuneBackend::active).count();
    double total =
        all.stream().filter(FortuneBackend::active).mapToDouble(x -> d(x, "weight")).sum();
    for (JsonObject f : all) {
      f.addProperty(
          "draws", number(c, "SELECT COUNT(*) FROM df_records WHERE fortune=?", i(f, "id")));
      f.addProperty("probability", active(f) ? d(f, "weight") / total * 100 : 0);
    }
    String search = q.getOrDefault("q", "").toLowerCase(Locale.ROOT),
        tab = q.getOrDefault("tab", "all"),
        grade = q.getOrDefault("grade", "");
    List<JsonObject> filtered =
        new ArrayList<>(
            all.stream()
                .filter(
                    f ->
                        (grade.isBlank() || grade.equals(s(f, "grade")))
                            && (tab.equals("all") || active(f) == tab.equals("active"))
                            && JSON.toJson(f).toLowerCase(Locale.ROOT).contains(search))
                .toList());
    String sort = q.getOrDefault("sort", "grade");
    int sign = q.getOrDefault("direction", "asc").equals("desc") ? -1 : 1;
    Collator collator = Collator.getInstance(Locale.CHINA);
    filtered.sort(
        (a, b) -> {
          int cmp =
              switch (sort) {
                case "title" -> collator.compare(s(a, "title"), s(b, "title"));
                case "grade" ->
                    Integer.compare(GRADES.indexOf(s(a, "grade")), GRADES.indexOf(s(b, "grade")));
                case "weight", "probability", "draws", "createdAt", "id" ->
                    Double.compare(d(a, sort), d(b, sort));
                default -> 0;
              };
          return cmp != 0 ? cmp * sign : collator.compare(s(a, "title"), s(b, "title"));
        });
    JsonObject out = page(filtered, q);
    out.add(
        "categoryCounts", obj("all", all.size(), "active", active, "paused", all.size() - active));
    out.add("pool", pool(all));
    return out;
  }

  private JsonObject players(Connection c, Map<String, String> q, Set<UUID> online)
      throws SQLException {
    if (platform != null) return managedPlayers(c, q, online);
    String onlineIn =
        online.isEmpty()
            ? "''"
            : String.join(",", online.stream().map(x -> "'" + x + "'").toList());
    String presence = q.getOrDefault("presence", "all");
    String where =
        " WHERE name LIKE ? "
            + (presence.equals("online")
                ? " AND uuid IN (" + onlineIn + ")"
                : presence.equals("offline") ? " AND uuid NOT IN (" + onlineIn + ")" : "");
    String sort = q.getOrDefault("sort", "status"),
        dir = q.getOrDefault("direction", "asc").equals("desc") ? "DESC" : "ASC";
    String order =
        switch (sort) {
          case "name" -> "p.name_key " + dir;
          case "total", "archived", "unread" -> sort + " " + dir;
          case "latest", "latestAt" -> "latestAt IS NULL ASC,latestAt " + dir;
          default -> "(p.uuid IN (" + onlineIn + ")) " + (dir.equals("ASC") ? "DESC" : "ASC");
        };
    int size = size(q), page = pageNumber(q);
    String query =
        "SELECT p.uuid,p.name,p.last_seen,COUNT(r.day) total,COALESCE(SUM(r.state='archived'),0)"
            + " archived,COALESCE(SUM(r.state='unread'),0) unread,MAX(r.at_ms) latestAt FROM"
            + " df_players p LEFT JOIN df_records r ON p.uuid=r.player"
            + where.replace("uuid", "p.uuid")
            + " GROUP BY p.uuid,p.name,p.last_seen,p.name_key ORDER BY "
            + order
            + ",p.name_key ASC,p.uuid ASC LIMIT ? OFFSET ?";
    JsonArray rows = new JsonArray();
    try (PreparedStatement ps =
            prepare(c, query, "%" + q.getOrDefault("q", "") + "%", size, page * size);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        Object latest = rs.getObject(7);
        rows.add(
            obj(
                "id",
                rs.getString(1),
                "name",
                rs.getString(2),
                "lastSeen",
                rs.getLong(3),
                "online",
                online.contains(UUID.fromString(rs.getString(1))),
                "total",
                rs.getLong(4),
                "archived",
                rs.getLong(5),
                "unread",
                rs.getLong(6),
                "latestAt",
                latest));
      }
    }
    long all = number(c, "SELECT COUNT(*) FROM df_players"),
        on = number(c, "SELECT COUNT(*) FROM df_players WHERE uuid IN (" + onlineIn + ")");
    return obj(
        "rows",
        rows,
        "total",
        number(c, "SELECT COUNT(*) FROM df_players" + where, "%" + q.getOrDefault("q", "") + "%"),
        "page",
        page,
        "size",
        size,
        "presenceCounts",
        obj("all", all, "online", on, "offline", all - on));
  }

  private JsonObject managedPlayers(Connection c, Map<String, String> q, Set<UUID> online)
      throws SQLException {
    List<JsonObject> profiles = platformProfiles();
    Map<String, JsonObject> statistics = new HashMap<>();
    try (PreparedStatement p =
            prepare(
                c,
                "SELECT player,COUNT(*),COALESCE(SUM(state='archived'),0),"
                    + "COALESCE(SUM(state='unread'),0),MAX(at_ms) FROM df_records GROUP BY player");
        ResultSet r = p.executeQuery()) {
      while (r.next())
        statistics.put(
            r.getString(1),
            obj(
                "total",
                r.getLong(2),
                "archived",
                r.getLong(3),
                "unread",
                r.getLong(4),
                "latestAt",
                r.getObject(5)));
    }
    String presence = q.getOrDefault("presence", "all");
    String search = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
    List<JsonObject> result = new ArrayList<>();
    int on = 0;
    for (JsonObject profile : profiles) {
      String id = s(profile, "id");
      boolean connected = online.contains(UUID.fromString(id));
      if (connected) on++;
      if ((presence.equals("online") && !connected)
          || (presence.equals("offline") && connected)
          || !s(profile, "name").toLowerCase(Locale.ROOT).contains(search)) continue;
      JsonObject row = profile.deepCopy();
      row.addProperty("online", connected);
      JsonObject stats =
          statistics.getOrDefault(
              id, obj("total", 0, "archived", 0, "unread", 0, "latestAt", null));
      for (var entry : stats.entrySet()) row.add(entry.getKey(), entry.getValue());
      result.add(row);
    }
    String sort = q.getOrDefault("sort", "status");
    int direction = q.getOrDefault("direction", "asc").equals("desc") ? -1 : 1;
    result.sort(
        (a, b) -> {
          int cmp;
          switch (sort) {
            case "name" -> cmp = sortKey(s(a, "name")).compareTo(sortKey(s(b, "name")));
            case "total", "archived", "unread" -> cmp = Long.compare(l(a, sort), l(b, sort));
            case "latest", "latestAt" -> {
              boolean missingA = a.get("latestAt").isJsonNull(),
                  missingB = b.get("latestAt").isJsonNull();
              if (missingA != missingB) return missingA ? 1 : -1;
              cmp = missingA ? 0 : Long.compare(l(a, "latestAt"), l(b, "latestAt"));
            }
            default -> cmp = Boolean.compare(b(b, "online"), b(a, "online"));
          }
          if (cmp != 0) return cmp * direction;
          cmp = sortKey(s(a, "name")).compareTo(sortKey(s(b, "name")));
          return cmp != 0 ? cmp : s(a, "id").compareTo(s(b, "id"));
        });
    JsonObject out = page(result, q);
    out.add(
        "presenceCounts",
        obj("all", profiles.size(), "online", on, "offline", profiles.size() - on));
    return out;
  }

  private JsonObject history(Connection c, Map<String, String> q) throws SQLException {
    String type = q.getOrDefault("type", "all"),
        where = " WHERE subject LIKE ?" + (type.equals("all") ? "" : " AND category=?");
    List<Object> params = new ArrayList<>();
    params.add("%" + q.getOrDefault("q", "") + "%");
    if (!type.equals("all")) params.add(type);
    long total = number(c, "SELECT COUNT(*) FROM df_audit" + where, params.toArray());
    params.add(size(q));
    params.add(pageNumber(q) * size(q));
    JsonObject counts = obj("all", number(c, "SELECT COUNT(*) FROM df_audit"));
    for (String cat : List.of("fortune", "settings", "recovery", "undo"))
      counts.addProperty(cat, number(c, "SELECT COUNT(*) FROM df_audit WHERE category=?", cat));
    return obj(
        "rows",
        rows(
            c,
            "SELECT payload FROM df_audit"
                + where
                + " ORDER BY at_ms DESC,id DESC LIMIT ? OFFSET ?",
            params.toArray()),
        "total",
        total,
        "categoryCounts",
        counts);
  }

  private JsonObject saveFortune(Connection c, JsonObject body, String actor) throws Exception {
    return saveFortune(c, body, actor, true);
  }

  private JsonObject saveFortune(Connection c, JsonObject body, String actor, boolean checkPool)
      throws Exception {
    JsonObject f = body.getAsJsonObject("fortune").deepCopy();
    int id = f.has("id") ? i(f, "id") : 0;
    JsonObject old =
        id > 0 ? one(c, "SELECT payload FROM df_fortunes WHERE id=? FOR UPDATE", id) : null;
    if (old == null && l(body, "expectedRevision") != 0)
      throw new IllegalStateException("签文已删除，旧草稿不能另建为新签");
    if (old != null && l(old, "revision") != l(body, "expectedRevision"))
      throw new IllegalStateException("签文已有后续修改，请刷新");
    if (old == null) {
      id = (int) number(c, "SELECT next_id FROM df_ids WHERE id=1 FOR UPDATE");
      if (id >= 2000000000) throw new IllegalStateException("签号序列已耗尽");
      exec(c, "UPDATE df_ids SET next_id=next_id+1 WHERE id=1");
      f.addProperty("createdAt", System.currentTimeMillis());
    } else f.add("createdAt", old.get("createdAt"));
    f.addProperty("id", id);
    f.addProperty("revision", old == null ? 1 : l(old, "revision") + 1);
    f.remove("draws");
    f.remove("probability");
    validateFortune(f);
    List<JsonObject> list = new ArrayList<>(fortunes(c));
    int savedId = id;
    list.removeIf(x -> i(x, "id") == savedId);
    list.add(f);
    if (checkPool) validatePool(list);
    stampChanges(old, f, List.of("title", "grade", "poem", "interpretation", "weight", "enabled"));
    exec(
        c,
        "INSERT INTO df_fortunes VALUES(?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload)",
        id,
        JSON.toJson(f));
    audit(
        c,
        "fortune",
        old == null ? "新建签文" : "修改签文",
        s(f, "title"),
        "更新签文定义",
        actor,
        "fortune",
        String.valueOf(id),
        changes(old, f, List.of("title", "grade", "poem", "interpretation", "weight", "enabled")),
        old == null);
    return obj("fortune", f);
  }

  private JsonObject deleteFortune(Connection c, JsonObject body, String actor) throws Exception {
    int id = i(body, "id");
    JsonObject old = one(c, "SELECT payload FROM df_fortunes WHERE id=? FOR UPDATE", id);
    if (old == null) throw new IllegalArgumentException("签文不存在");
    if (l(old, "revision") != l(body, "expectedRevision"))
      throw new IllegalStateException("签文已有后续修改");
    List<JsonObject> left = new ArrayList<>(fortunes(c));
    left.removeIf(x -> i(x, "id") == id);
    validatePool(left);
    exec(c, "DELETE FROM df_fortunes WHERE id=?", id);
    audit(
        c,
        "fortune",
        "删除签文",
        s(old, "title"),
        "历史签文快照保留",
        actor,
        "deleted",
        String.valueOf(id),
        changes(
            null, old, List.of("title", "grade", "poem", "interpretation", "weight", "enabled")),
        false);
    return obj("ok", true);
  }

  private JsonObject saveSettings(Connection c, JsonObject body, String actor) throws Exception {
    JsonObject old = settings(c), next = body.getAsJsonObject("settings").deepCopy();
    if (l(old, "revision") != l(body, "expectedRevision"))
      throw new IllegalStateException("设置已有后续修改，请刷新");
    validateSettings(next);
    next.addProperty("revision", l(old, "revision") + 1);
    stampChanges(
        old, next, List.of("enabled", "timezone", "recovery", "archive", "hover", "retentionDays"));
    exec(c, "UPDATE df_settings SET payload=? WHERE id=1", JSON.toJson(next));
    audit(
        c,
        "settings",
        "修改设置",
        "抽签设置",
        "更新设置",
        actor,
        "settings",
        "settings",
        changes(
            old,
            next,
            List.of("enabled", "timezone", "recovery", "archive", "hover", "retentionDays")),
        false);
    return obj("settings", next);
  }

  private JsonObject undo(Connection c, String id, String actor) throws Exception {
    JsonObject entry =
        one(c, "SELECT payload FROM df_audit WHERE id=? FOR UPDATE", Long.parseLong(id));
    if (entry == null || b(entry, "undone") || b(entry, "created"))
      throw new IllegalStateException("该记录不可撤回");
    String type = s(entry, "targetType"), target = s(entry, "targetId");
    if (!type.equals("settings") && !type.equals("fortune"))
      throw new IllegalStateException("此操作不支持撤回");
    JsonObject current =
        type.equals("settings")
            ? settings(c)
            : one(
                c,
                "SELECT payload FROM df_fortunes WHERE id=? FOR UPDATE",
                Integer.parseInt(target));
    if (current == null) throw new IllegalStateException("对象已删除，无法撤回");
    JsonObject next = selectiveUndo(current, entry.getAsJsonArray("changes"));
    next.addProperty("revision", l(current, "revision") + 1);
    stampChanges(
        current,
        next,
        entry.getAsJsonArray("changes").asList().stream()
            .map(e -> s(e.getAsJsonObject(), "field"))
            .toList());
    if (type.equals("settings")) {
      validateSettings(next);
      exec(c, "UPDATE df_settings SET payload=? WHERE id=1", JSON.toJson(next));
    } else {
      validateFortune(next);
      List<JsonObject> list = new ArrayList<>(fortunes(c));
      list.removeIf(f -> s(f, "id").equals(target));
      list.add(next);
      validatePool(list);
      exec(
          c,
          "UPDATE df_fortunes SET payload=? WHERE id=?",
          JSON.toJson(next),
          Integer.parseInt(target));
    }
    entry.addProperty("undone", true);
    exec(c, "UPDATE df_audit SET payload=? WHERE id=?", JSON.toJson(entry), Long.parseLong(id));
    audit(
        c,
        "undo",
        "撤回操作",
        s(entry, "subject"),
        "仅恢复本次改动字段",
        actor,
        "undo",
        id,
        new JsonArray(),
        false);
    return obj("ok", true);
  }

  public static JsonObject selectiveUndo(JsonObject current, JsonArray changes) {
    JsonObject next = current.deepCopy();
    for (JsonElement e : changes) {
      JsonObject ch = e.getAsJsonObject();
      String field = s(ch, "field");
      if ((ch.has("afterStamp")
              && !ch.get("afterStamp").isJsonNull()
              && (!current.has("_fieldVersions")
                  || !Objects.equals(
                      current.getAsJsonObject("_fieldVersions").get(field), ch.get("afterStamp"))))
          || !Objects.equals(current.get(field), ch.get("after")))
        throw new IllegalStateException("无法撤回：" + field + " 已有后续改动");
      next.add(field, ch.get("before"));
    }
    return next;
  }

  private void audit(
      Connection c,
      String category,
      String action,
      String subject,
      String summary,
      String actor,
      String type,
      String target,
      JsonArray changes,
      boolean created)
      throws SQLException {
    JsonObject a =
        obj(
            "at",
            System.currentTimeMillis(),
            "action",
            action,
            "subject",
            subject,
            "summary",
            summary,
            "actor",
            actor,
            "targetType",
            type,
            "targetId",
            target,
            "changes",
            changes,
            "created",
            created,
            "undone",
            false,
            "seed",
            false);
    exec(
        c,
        "INSERT INTO df_audit(at_ms,category,subject,payload) VALUES(?,?,?,?)",
        l(a, "at"),
        category,
        subject,
        JSON.toJson(a));
    long id = number(c, "SELECT LAST_INSERT_ID()");
    a.addProperty("id", String.valueOf(id));
    exec(c, "UPDATE df_audit SET payload=? WHERE id=?", JSON.toJson(a), id);
  }

  private static void stampChanges(JsonObject old, JsonObject next, List<String> keys) {
    JsonObject stamps =
        old != null && old.has("_fieldVersions")
            ? old.getAsJsonObject("_fieldVersions").deepCopy()
            : new JsonObject();
    for (String key : keys)
      if (old == null || !Objects.equals(old.get(key), next.get(key)))
        stamps.addProperty(key, UUID.randomUUID().toString());
    next.add("_fieldVersions", stamps);
  }

  private static JsonArray changes(JsonObject old, JsonObject next, List<String> keys) {
    JsonArray a = new JsonArray();
    for (String key : keys) {
      JsonElement before = old == null ? JsonNull.INSTANCE : old.get(key), after = next.get(key);
      if (!Objects.equals(before, after))
        a.add(
            obj(
                "field",
                key,
                "label",
                key,
                "before",
                before,
                "after",
                after,
                "afterStamp",
                next.has("_fieldVersions")
                    ? next.getAsJsonObject("_fieldVersions").get(key)
                    : JsonNull.INSTANCE));
    }
    return a;
  }

  public static void validateFortune(JsonObject f) {
    text(f, "title", 1, 64);
    text(f, "interpretation", 1, 1200);
    if (!GRADES.contains(s(f, "grade"))) throw new IllegalArgumentException("签文等级无效");
    JsonArray poem = f.getAsJsonArray("poem");
    if (poem == null || poem.size() != 4) throw new IllegalArgumentException("签诗必须四句");
    for (JsonElement e : poem) {
      String line = e.getAsString();
      if (line.isBlank() || line.codePointCount(0, line.length()) > 24)
        throw new IllegalArgumentException("每句签诗须为1–24字");
    }
    double weight = d(f, "weight");
    if (!Double.isFinite(weight) || weight < 0 || weight > 10000)
      throw new IllegalArgumentException("权重应为0–10000");
    if (!f.has("enabled") || !f.get("enabled").isJsonPrimitive())
      throw new IllegalArgumentException("缺少参与抽取设置");
  }

  private static void validateSettings(JsonObject s) {
    try {
      ZoneId.of(s(s, "timezone"));
    } catch (Exception e) {
      throw new IllegalArgumentException("时区无效");
    }
    for (String k : List.of("enabled", "recovery", "archive", "hover"))
      if (!s.has(k) || !s.getAsJsonPrimitive(k).isBoolean())
        throw new IllegalArgumentException("设置字段无效：" + k);
    if (!s.has("retentionDays")) s.addProperty("retentionDays", 30);
    if (i(s, "retentionDays") < 1 || i(s, "retentionDays") > 3650)
      throw new IllegalArgumentException("备份保留天数应为1–3650");
  }

  private static void validatePool(List<JsonObject> list) {
    if (list.size() > 4096 || list.stream().noneMatch(FortuneBackend::active))
      throw new IllegalArgumentException("须保留至少一支可抽签，最多4096支");
  }

  private static void validateConfig(JsonObject file) {
    if (file.has("format") && i(file, "format") != 1)
      throw new IllegalArgumentException("配置格式版本无效");
    validateSettings(file.getAsJsonObject("settings"));
    List<JsonObject> list = new ArrayList<>();
    Set<Integer> ids = new HashSet<>();
    for (JsonElement e : file.getAsJsonArray("fortunes")) {
      JsonObject f = e.getAsJsonObject();
      validateFortune(f);
      if (i(f, "id") <= 0 || i(f, "id") >= 2000000000 || !ids.add(i(f, "id")))
        throw new IllegalArgumentException("签号无效或重复");
      list.add(f);
    }
    validatePool(list);
  }

  private String configStamp(Connection c) throws SQLException {
    return hash(JSON.toJson(config(c)));
  }

  private JsonObject config(Connection c) throws SQLException {
    return obj(
        "format",
        1,
        "settings",
        settings(c),
        "fortunes",
        fortunes(c),
        "nextId",
        number(c, "SELECT next_id FROM df_ids WHERE id=1"));
  }

  private JsonObject importConfig(Connection c, JsonObject file, String actor) throws Exception {
    validateConfig(file);
    Map<Integer, JsonObject> merged = new LinkedHashMap<>();
    for (JsonObject f : fortunes(c)) merged.put(i(f, "id"), f);
    for (JsonElement e : file.getAsJsonArray("fortunes"))
      merged.put(i(e.getAsJsonObject(), "id"), e.getAsJsonObject());
    validatePool(new ArrayList<>(merged.values()));
    JsonObject old = settings(c);
    saveSettings(
        c,
        obj("settings", file.getAsJsonObject("settings"), "expectedRevision", l(old, "revision")),
        actor);
    for (JsonElement e : file.getAsJsonArray("fortunes")) {
      JsonObject f = e.getAsJsonObject(),
          prior = one(c, "SELECT payload FROM df_fortunes WHERE id=?", i(f, "id"));
      if (prior == null) {
        f = f.deepCopy();
        f.addProperty("createdAt", System.currentTimeMillis());
        f.addProperty("revision", 1);
        stampChanges(
            null, f, List.of("title", "grade", "poem", "interpretation", "weight", "enabled"));
        exec(c, "UPDATE df_ids SET next_id=GREATEST(next_id,?) WHERE id=1", i(f, "id") + 1);
        exec(c, "INSERT INTO df_fortunes VALUES(?,?)", i(f, "id"), JSON.toJson(f));
        audit(
            c,
            "fortune",
            "新建签文",
            s(f, "title"),
            "导入配置",
            actor,
            "fortune",
            s(f, "id"),
            changes(
                null, f, List.of("title", "grade", "poem", "interpretation", "weight", "enabled")),
            true);
      } else
        saveFortune(c, obj("fortune", f, "expectedRevision", l(prior, "revision")), actor, false);
    }
    return obj("ok", true);
  }

  private static JsonObject fileObject(JsonObject body) {
    JsonElement f = body.get("file");
    return f.isJsonObject()
        ? f.getAsJsonObject()
        : JsonParser.parseString(f.getAsString()).getAsJsonObject();
  }

  private JsonObject backup(Connection c) throws Exception {
    long bytes = 0, count = 0;
    for (String table : List.of("df_records", "df_audit", "df_fortunes", "df_settings")) {
      bytes += number(c, "SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM " + table);
      count += number(c, "SELECT COUNT(*) FROM " + table);
    }
    count +=
        number(c, "SELECT COUNT(*) FROM df_players")
            + number(c, "SELECT COUNT(*) FROM df_requests");
    if (bytes > 16L * 1024 * 1024 || count > 50000)
      throw new IllegalStateException("数据量超出内置备份容量，请使用数据库服务备份工具");
    JsonObject data = config(c);
    data.addProperty("createdAt", System.currentTimeMillis());
    data.add(
        "records", JSON.toJsonTree(rows(c, "SELECT payload FROM df_records ORDER BY player,day")));
    data.add("audit", JSON.toJsonTree(rows(c, "SELECT payload FROM df_audit ORDER BY id")));
    JsonArray players = new JsonArray();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT uuid,name,last_seen FROM df_players ORDER BY uuid")) {
      while (rs.next())
        players.add(obj("id", rs.getString(1), "name", rs.getString(2), "lastSeen", rs.getLong(3)));
    }
    data.add("players", players);
    JsonArray requests = new JsonArray();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT id,player,day,token FROM df_requests")) {
      while (rs.next())
        requests.add(
            obj(
                "id",
                rs.getString(1),
                "player",
                rs.getString(2),
                "day",
                rs.getString(3),
                "token",
                rs.getString(4)));
    }
    data.add("requests", requests);
    String text = JSON.toJson(data);
    if (text.getBytes(StandardCharsets.UTF_8).length > 64 * 1024 * 1024)
      throw new IllegalStateException("备份超过64MiB，请使用数据库服务的备份工具");
    Path dir = folder.resolve("backups");
    Files.createDirectories(dir);
    String name =
        "dailyfortunes-"
            + System.currentTimeMillis()
            + "-"
            + UUID.randomUUID().toString().substring(0, 8)
            + ".json";
    atomic(dir.resolve(name), text);
    atomic(dir.resolve(name + ".sha256"), hash(text));
    long before =
        System.currentTimeMillis() - Duration.ofDays(i(settings(c), "retentionDays")).toMillis();
    try (var files = Files.list(dir)) {
      for (Path p :
          files
              .filter(
                  p ->
                      p.getFileName()
                          .toString()
                          .matches("dailyfortunes-[0-9]+-[a-f0-9]{8}\\.json(\\.sha256)?"))
              .toList())
        if (Files.getLastModifiedTime(p).toMillis() < before) Files.deleteIfExists(p);
    }
    return obj("file", name, "checksum", hash(text));
  }

  private List<JsonObject> backupList() throws Exception {
    Path dir = folder.resolve("backups");
    if (!Files.exists(dir)) return List.of();
    try (var files = Files.list(dir)) {
      List<JsonObject> out = new ArrayList<>();
      for (Path p :
          files
              .filter(p -> p.toString().endsWith(".json"))
              .sorted(Comparator.reverseOrder())
              .limit(500)
              .toList())
        out.add(
            obj(
                "file",
                p.getFileName().toString(),
                "modified",
                Files.getLastModifiedTime(p).toMillis(),
                "bytes",
                Files.size(p),
                "checksum",
                Files.exists(p.resolveSibling(p.getFileName() + ".sha256"))));
      return out;
    }
  }

  private Path backupPath(String name) {
    if (name == null || !name.matches("dailyfortunes-[0-9]+-[a-f0-9]{8}\\.json"))
      throw new IllegalArgumentException("备份文件名无效");
    return folder.resolve("backups").resolve(name);
  }

  private record BackupFile(JsonObject data, String text, String digest) {}

  private static BackupFile readBackup(Path path) throws IOException {
    String text = readBounded(path, 64 * 1024 * 1024);
    String digest = hash(text),
        expected = readBounded(path.resolveSibling(path.getFileName() + ".sha256"), 256).trim();
    if (!MessageDigest.isEqual(
        digest.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII)))
      throw new IllegalArgumentException("SHA-256 校验失败");
    JsonObject data = JsonParser.parseString(text).getAsJsonObject();
    validateBackup(data);
    return new BackupFile(data, text, digest);
  }

  private JsonObject verify(String name) throws Exception {
    BackupFile checked = readBackup(backupPath(name));
    JsonObject file = checked.data;
    return obj(
        "file",
        name,
        "checksum",
        checked.digest,
        "createdAt",
        l(file, "createdAt"),
        "fortunes",
        file.getAsJsonArray("fortunes").size(),
        "players",
        file.getAsJsonArray("players").size(),
        "records",
        file.getAsJsonArray("records").size(),
        "audit",
        file.getAsJsonArray("audit").size(),
        "verified",
        true);
  }

  private static void validateBackup(JsonObject file) {
    if (i(file, "format") != 1) throw new IllegalArgumentException("不支持的备份格式");
    validateConfig(file);
    if (file.has("nextId")
        && (!s(file, "nextId").matches("[1-9][0-9]{0,9}") || l(file, "nextId") > 2000000000L))
      throw new IllegalArgumentException("签号序列无效");
    Set<String> players = new HashSet<>(),
        recordKeys = new HashSet<>(),
        tokens = new HashSet<>(),
        auditIds = new HashSet<>();
    Map<String, JsonObject> records = new HashMap<>();
    for (JsonElement e : file.getAsJsonArray("players")) {
      JsonObject p = e.getAsJsonObject();
      String id = UUID.fromString(s(p, "id")).toString();
      if (!players.add(id) || s(p, "name").isBlank() || s(p, "name").length() > 64)
        throw new IllegalArgumentException("玩家身份重复或无效");
    }
    for (JsonElement e : file.getAsJsonArray("records")) {
      JsonObject r = e.getAsJsonObject();
      String id = s(r, "player"), day = s(r, "date"), key = id + ":" + day;
      date(day);
      String token = UUID.fromString(s(r, "token")).toString();
      if (!players.contains(id)
          || !recordKeys.add(key)
          || !tokens.add(token)
          || !List.of("unread", "loose", "archived").contains(s(r, "state"))
          || l(r, "at") <= 0
          || i(r, "fortuneId") <= 0
          || !key.equals(s(r, "id"))) throw new IllegalArgumentException("签历引用或状态无效");
      validateFortune(r.getAsJsonObject("snapshot"));
      if (i(r.getAsJsonObject("snapshot"), "id") != i(r, "fortuneId"))
        throw new IllegalArgumentException("签历快照签号不符");
      records.put(key, r);
    }
    for (JsonElement e : file.getAsJsonArray("audit")) {
      JsonObject a = e.getAsJsonObject();
      if (!s(a, "id").matches("[1-9][0-9]{0,17}")
          || !auditIds.add(s(a, "id"))
          || l(a, "at") <= 0
          || !a.has("changes")
          || !a.get("changes").isJsonArray()) throw new IllegalArgumentException("审计记录无效");
      for (JsonElement change : a.getAsJsonArray("changes")) {
        JsonObject field = change.getAsJsonObject();
        if (!List.of(
                    "title",
                    "grade",
                    "poem",
                    "interpretation",
                    "weight",
                    "enabled",
                    "timezone",
                    "recovery",
                    "archive",
                    "hover",
                    "retentionDays")
                .contains(s(field, "field"))
            || !field.has("after")
            || (!field.has("before") && !b(a, "created") && !s(a, "targetType").equals("deleted")))
          throw new IllegalArgumentException("审计变更字段无效");
        if (!field.has("before")) field.add("before", JsonNull.INSTANCE);
      }
    }
    Set<String> requests = new HashSet<>();
    for (JsonElement e : file.getAsJsonArray("requests")) {
      JsonObject req = e.getAsJsonObject();
      String key = s(req, "player") + ":" + s(req, "day");
      if (!requests.add(s(req, "id")) || !records.containsKey(key))
        throw new IllegalArgumentException("补领票据引用无效");
      UUID.fromString(s(req, "token"));
    }
  }

  public JsonObject restoreEmpty(Path source, String confirmation) {
    if (!"RESTORE-EMPTY-DAILYFORTUNES".equals(confirmation))
      throw new IllegalArgumentException("恢复需要明确确认标记");
    final JsonObject snapshot;
    try {
      snapshot = readBackup(source).data;
    } catch (IOException e) {
      throw new IllegalArgumentException("无法读取备份和校验文件", e);
    }
    return transaction(
        c -> {
          if (number(c, "SELECT COUNT(*) FROM df_players")
                  + number(c, "SELECT COUNT(*) FROM df_records")
                  + number(c, "SELECT COUNT(*) FROM df_audit")
              != 0) throw new IllegalStateException("恢复仅允许目标数据库为空");
          exec(c, "DELETE FROM df_fortunes");
          for (JsonElement e : snapshot.getAsJsonArray("fortunes")) {
            JsonObject f = e.getAsJsonObject();
            exec(c, "INSERT INTO df_fortunes VALUES(?,?)", i(f, "id"), JSON.toJson(f));
          }
          exec(
              c,
              "UPDATE df_settings SET payload=? WHERE id=1",
              JSON.toJson(snapshot.get("settings")));
          int nextId = restoredNextId(snapshot);
          exec(c, "UPDATE df_ids SET next_id=? WHERE id=1", nextId);
          for (JsonElement e : snapshot.getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            exec(
                c,
                "INSERT INTO df_players VALUES(?,?,?,?)",
                s(p, "id"),
                s(p, "name"),
                sortKey(s(p, "name")),
                l(p, "lastSeen"));
          }
          for (JsonElement e : snapshot.getAsJsonArray("records")) {
            JsonObject r = e.getAsJsonObject();
            exec(
                c,
                "INSERT INTO df_records VALUES(?,?,?,?,?,?,?)",
                s(r, "player"),
                s(r, "date"),
                s(r, "state"),
                l(r, "at"),
                i(r, "fortuneId"),
                b(r, "pending"),
                JSON.toJson(r));
          }
          for (JsonElement e : snapshot.getAsJsonArray("requests")) {
            JsonObject r = e.getAsJsonObject();
            exec(
                c,
                "INSERT INTO df_requests VALUES(?,?,?,?)",
                s(r, "id"),
                s(r, "player"),
                s(r, "day"),
                s(r, "token"));
          }
          for (JsonElement e : snapshot.getAsJsonArray("audit")) {
            JsonObject a = e.getAsJsonObject();
            String type = s(a, "targetType"),
                category =
                    type.equals("settings")
                        ? "settings"
                        : type.equals("receipt")
                            ? "recovery"
                            : type.equals("undo") ? "undo" : "fortune";
            exec(
                c,
                "INSERT INTO df_audit(id,at_ms,category,subject,payload) VALUES(?,?,?,?,?)",
                Long.parseLong(s(a, "id")),
                l(a, "at"),
                category,
                s(a, "subject"),
                JSON.toJson(a));
          }
          return obj("restored", true, "records", snapshot.getAsJsonArray("records").size());
        });
  }

  private static int restoredNextId(JsonObject snapshot) {
    long highest = 0;
    for (JsonElement e : snapshot.getAsJsonArray("fortunes"))
      highest = Math.max(highest, l(e.getAsJsonObject(), "id"));
    for (JsonElement e : snapshot.getAsJsonArray("records"))
      highest = Math.max(highest, l(e.getAsJsonObject(), "fortuneId"));
    for (JsonElement e : snapshot.getAsJsonArray("audit")) {
      JsonObject a = e.getAsJsonObject();
      if (List.of("fortune", "deleted").contains(s(a, "targetType"))
          && s(a, "targetId").matches("[1-9][0-9]{0,9}"))
        highest = Math.max(highest, Long.parseLong(s(a, "targetId")));
    }
    if (highest >= 2000000000L) throw new IllegalArgumentException("签号序列已耗尽");
    return (int) Math.max(highest + 1, l(snapshot, "nextId"));
  }

  private JsonObject downloadBackup(String name, boolean checksum) throws Exception {
    BackupFile checked = readBackup(backupPath(name));
    return obj(
        "filename",
        name + (checksum ? ".sha256" : ""),
        "text",
        checksum ? checked.digest + "  " + name : checked.text,
        "checksum",
        checked.digest);
  }

  private static String readBounded(Path p, int limit) throws IOException {
    try (InputStream in = Files.newInputStream(p)) {
      byte[] bytes = in.readNBytes(limit + 1);
      if (bytes.length > limit) throw new IllegalArgumentException("文件超过允许大小");
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static JsonObject parseBounded(Path p) throws IOException {
    return JsonParser.parseString(readBounded(p, 64 * 1024 * 1024)).getAsJsonObject();
  }

  public static void atomic(Path p, String text) throws IOException {
    Files.createDirectories(p.toAbsolutePath().getParent());
    Path tmp = Files.createTempFile(p.toAbsolutePath().getParent(), "df-", ".tmp");
    try {
      Files.writeString(tmp, text);
      try {
        Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonObject pool(List<JsonObject> list) {
    double total =
        list.stream().filter(FortuneBackend::active).mapToDouble(f -> d(f, "weight")).sum();
    JsonArray grades = new JsonArray();
    for (String g : GRADES) {
      var fs = list.stream().filter(f -> active(f) && s(f, "grade").equals(g)).toList();
      grades.add(
          obj(
              "grade",
              g,
              "count",
              fs.size(),
              "probability",
              total == 0 ? 0 : fs.stream().mapToDouble(f -> d(f, "weight")).sum() / total * 100));
    }
    return obj(
        "active",
        list.stream().filter(FortuneBackend::active).count(),
        "totalWeight",
        total,
        "grades",
        grades);
  }

  private static boolean active(JsonObject f) {
    return b(f, "enabled") && d(f, "weight") > 0;
  }

  private static JsonObject page(List<JsonObject> all, Map<String, String> q) {
    int size = size(q), page = Math.min(pageNumber(q), Math.max(0, (all.size() - 1) / size));
    return obj(
        "rows",
        all.subList(Math.min(page * size, all.size()), Math.min((page + 1) * size, all.size())),
        "total",
        all.size(),
        "page",
        page,
        "size",
        size);
  }

  private static int size(Map<String, String> q) {
    return Math.max(1, Math.min(100, Integer.parseInt(q.getOrDefault("size", "20"))));
  }

  private static int pageNumber(Map<String, String> q) {
    return Math.max(0, Math.min(100000, Integer.parseInt(q.getOrDefault("page", "0"))));
  }

  private static String sortKey(String name) {
    return HexFormat.of()
        .formatHex(
            Collator.getInstance(Locale.CHINA)
                .getCollationKey(name.toLowerCase(Locale.ROOT))
                .toByteArray());
  }

  private static void text(JsonObject o, String k, int min, int max) {
    String v = s(o, k);
    if (v.isBlank() || v.length() < min || v.length() > max || v.indexOf('\u0000') >= 0)
      throw new IllegalArgumentException(k + " 长度无效");
  }

  public static JsonObject obj(Object... args) {
    JsonObject o = new JsonObject();
    for (int i = 0; i < args.length; i += 2) o.add((String) args[i], JSON.toJsonTree(args[i + 1]));
    return o;
  }

  public static String s(JsonObject o, String k) {
    return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
  }

  public static int i(JsonObject o, String k) {
    return o.has(k) ? o.get(k).getAsInt() : 0;
  }

  public static long l(JsonObject o, String k) {
    return o.has(k) ? o.get(k).getAsLong() : 0;
  }

  public static double d(JsonObject o, String k) {
    return o.has(k) ? o.get(k).getAsDouble() : 0;
  }

  public static boolean b(JsonObject o, String k) {
    return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
  }

  private static PreparedStatement prepare(Connection c, String sql, Object... args)
      throws SQLException {
    PreparedStatement p = c.prepareStatement(sql);
    p.setQueryTimeout(10);
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    return p;
  }

  private static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = prepare(c, sql, args)) {
      p.execute();
    }
  }

  private static long number(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = prepare(c, sql, args);
        ResultSet r = p.executeQuery()) {
      return r.next() ? r.getLong(1) : 0;
    }
  }

  private static String scalar(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = prepare(c, sql, args);
        ResultSet r = p.executeQuery()) {
      return r.next() ? r.getString(1) : "";
    }
  }

  private static JsonObject one(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = prepare(c, sql, args);
        ResultSet r = p.executeQuery()) {
      return r.next() ? JsonParser.parseString(r.getString(1)).getAsJsonObject() : null;
    }
  }

  private static List<JsonObject> rows(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement p = prepare(c, sql, args);
        ResultSet r = p.executeQuery()) {
      List<JsonObject> out = new ArrayList<>();
      while (r.next()) out.add(JsonParser.parseString(r.getString(1)).getAsJsonObject());
      return out;
    }
  }
}
