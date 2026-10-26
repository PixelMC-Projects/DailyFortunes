// SPDX-License-Identifier: AGPL-3.0-only
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** Read-only authenticated capability gate; schema preflight belongs to the future web launcher. */
public final class DailyFortunesReadiness {
  public static void main(String[] args) {
    try {
      JsonObject bridge = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
      if (!bridge.get("enabled").getAsBoolean()) throw new IllegalStateException();
      String base = bridge.get("baseUrl").getAsString().replaceAll("/+$", "");
      HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/bridge/capabilities"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + bridge.get("token").getAsString())
          .header("X-PixelMC-Instance", bridge.get("instanceId").getAsString())
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
          .send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException();
      JsonObject value = JsonParser.parseString(response.body()).getAsJsonObject();
      if (!value.get("fortunesEnabled").getAsBoolean() || value.get("titlesApi").getAsInt() < 2
          || !value.get("titlesHealthy").getAsBoolean()) throw new IllegalStateException();
      System.out.println("[DailyFortunes staging] Authenticated web extension is enabled.");
    } catch (Exception unavailable) {
      System.out.println("[DailyFortunes staging] Web extension is not confirmed; installation deferred.");
      System.exit(2);
    }
  }
}
