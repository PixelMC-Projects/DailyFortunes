// SPDX-License-Identifier: AGPL-3.0-only
import com.google.gson.*;
import java.nio.file.*;

/** Local temporary-file fixtures; does not open a database, network connection or game. */
public final class DeploymentConfigChecks {
  private static int checks;
  private static void check(boolean value) {
    if (!value) throw new AssertionError("Future configuration fixture failed");
    checks++;
  }
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("dailyfortunes-config-check-");
    Path file = root.resolve("manager.json"), db = root.resolve("db.json"), backup = root.resolve("before.json");
    String original = "{\"database\":{\"url\":\"jdbc:mariadb://fixture/manager\"},"
        + "\"titlesDatabase\":{\"url\":\"jdbc:mariadb://fixture/titles\"},"
        + "\"port\":18800,\"owners\":[\"fixture-owner\"],\"dataDirectory\":\"data\",\"custom\":true}";
    String database = "{\"url\":\"jdbc:mariadb://fixture/dailyfortunes\",\"user\":\"dailyfortunes\",\"password\":\"fixture-only\"}";
    Files.writeString(file, original); Files.writeString(db, database);
    JsonObject out = DailyFortunesPrepare.configure(file, db, backup);
    JsonObject old = JsonParser.parseString(original).getAsJsonObject();
    for (String key : old.keySet()) check(old.get(key).equals(out.get(key)));
    check(out.get("fortunesEnabled").getAsBoolean());
    check(out.get("fortunesDatabase").equals(JsonParser.parseString(database)));
    check(Files.readString(backup).equals(original));
    check(DailyFortunesPrepare.configure(file, db, backup).equals(out));
    String beforeConflict = Files.readString(file);
    Files.writeString(db, database.replace("fixture-only", "changed-fixture"));
    try { DailyFortunesPrepare.configure(file, db, backup); throw new AssertionError("Conflict accepted"); }
    catch (IllegalArgumentException expected) { checks++; }
    check(Files.readString(file).equals(beforeConflict));
    Files.writeString(file, original);
    Files.writeString(db, database.replace("dailyfortunes\",\"user", "titles\",\"user"));
    try { DailyFortunesPrepare.configure(file, db, backup); throw new AssertionError("Shared database accepted"); }
    catch (IllegalArgumentException expected) { checks++; }
    check(Files.readString(file).equals(original));
    try (var files = Files.list(root)) {
      check(files.noneMatch(p -> p.getFileName().toString().startsWith(".manager.dailyfortunes-")));
    }
    System.out.println("Future web configuration fixtures passed: " + checks);
  }
}
