// SPDX-License-Identifier: AGPL-3.0-only
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Future web-start preflight: same backend/config, defaults only, no invented player identity. */
public final class DailyFortunesPrepare {
  public static void main(String[] args) {
    try {
      Path file = Path.of(args[0]).toAbsolutePath();
      JsonObject config = configure(file, Path.of(args[1]), Path.of(args[2]));
      JsonObject db = config.getAsJsonObject("fortunesDatabase");
      Path data = file.getParent().resolve(config.get("dataDirectory").getAsString()).resolve("dailyfortunes");
      try (FortuneBackend backend = new FortuneBackend(db.get("url").getAsString(),
          db.get("user").getAsString(), db.get("password").getAsString(), data)) {
        backend.request("GET", "/state", new JsonObject(), Map.of(), "startup", Set.of());
        if (!backend.status().get("database").getAsBoolean()) throw new IllegalStateException();
      }
      System.out.println("[DailyFortunes staging] Independent schema and default definitions are ready.");
    } catch (Exception unavailable) {
      System.err.println("[DailyFortunes staging] Database preparation failed; inspect protected configuration.");
      System.exit(2);
    }
  }

  static JsonObject configure(Path file, Path databaseFile, Path backup) throws Exception {
    if (Files.isSymbolicLink(file) || Files.isSymbolicLink(databaseFile) || Files.isSymbolicLink(backup))
      throw new IllegalArgumentException();
    JsonObject config = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    JsonObject database = JsonParser.parseString(Files.readString(databaseFile)).getAsJsonObject();
    if (!database.keySet().equals(Set.of("url", "user", "password"))
        || !database.get("url").getAsString().endsWith("/dailyfortunes")
        || !database.get("user").getAsString().equals("dailyfortunes"))
      throw new IllegalArgumentException();
    for (String key : List.of("database", "titlesDatabase"))
      if (config.getAsJsonObject(key).get("url").equals(database.get("url")))
        throw new IllegalArgumentException();
    JsonElement prior = config.get("fortunesDatabase");
    if (prior != null && !prior.isJsonNull() && !prior.equals(database))
      throw new IllegalArgumentException();
    if (!Files.exists(backup)) {
      Files.copy(file, backup);
      protect(backup);
    }
    config.addProperty("fortunesEnabled", true);
    config.add("fortunesDatabase", database);
    Path temporary = Files.createTempFile(file.getParent(), ".manager.dailyfortunes-", ".new");
    try {
      protect(temporary);
      Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(config) + "\n");
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return config;
  }

  private static void protect(Path file) throws Exception {
    try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
    catch (UnsupportedOperationException windowsFixture) { /* Local fixtures use a temporary directory. */ }
  }
}
