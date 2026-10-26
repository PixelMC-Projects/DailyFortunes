package cn.pixelmc.dailyfortunes.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline operator entry point; never exposes credentials in arguments or output. */
public final class FortuneRestore {
  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !"RESTORE-EMPTY-DAILYFORTUNES".equals(args[2])) {
      throw new IllegalArgumentException(
          "Usage: FortuneRestore backup.json connection.json RESTORE-EMPTY-DAILYFORTUNES");
    }
    Path config = Path.of(args[1]).toAbsolutePath();
    if (Files.size(config) > 65536)
      throw new IllegalArgumentException("Connection config too large");
    JsonObject db = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
    try (FortuneBackend backend =
        new FortuneBackend(
            FortuneBackend.s(db, "url"),
            FortuneBackend.s(db, "user"),
            FortuneBackend.s(db, "password"),
            config.getParent())) {
      System.out.println(
          FortuneBackend.JSON.toJson(backend.restoreEmpty(Path.of(args[0]), args[2])));
    } catch (Exception e) {
      System.err.println(
          "Restore failed. Verify the backup checksum, empty destination and local connection"
              + " configuration.");
      System.exit(1);
    }
  }
}
