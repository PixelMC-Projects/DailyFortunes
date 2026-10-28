package cn.pixelmc.dailyfortunes.client;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Pure visual dimensions shared by resource-pack loading and physics checks. */
public final class ArtProfile {
  public static final ArtProfile DEFAULT = defaults();
  public final String style;
  public final double[][] pivots, bounds;
  public final double roundWall, separation, chestTop;

  private ArtProfile(JsonObject json) {
    if (json.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Art profile schema");
    style = json.get("style").getAsString();
    pivots = matrix(json, "pivots", 3, 0, 1);
    bounds = matrix(json, "motionBounds", 4, -.1, .1);
    roundWall = number(json, "roundWall", 0, .25);
    separation = number(json, "minimumSeparation", .01, .15);
    chestTop = number(json, "chestTop", .25, 1.5);
    for (double[] row : bounds)
      if (row[0] > 0 || row[1] < 0 || row[2] > 0 || row[3] < 0)
        throw new IllegalArgumentException("Art motion bounds must contain rest pose");
  }

  public static ArtProfile read(Reader reader) {
    return new ArtProfile(JsonParser.parseReader(reader).getAsJsonObject());
  }

  private static ArtProfile defaults() {
    try (var stream = ArtProfile.class.getResourceAsStream("/assets/dailyfortunes/visuals/model_profile.json")) {
      if (stream == null) throw new IOException("Missing default art profile");
      return read(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static double number(JsonObject json, String key, double low, double high) {
    double value = json.get(key).getAsDouble();
    if (!Double.isFinite(value) || value < low || value > high)
      throw new IllegalArgumentException("Invalid art value: " + key);
    return value;
  }

  private static double[][] matrix(JsonObject json, String key, int width, double low, double high) {
    JsonArray rows = json.getAsJsonArray(key);
    if (rows.size() != 7) throw new IllegalArgumentException("Seven sticks required");
    double[][] result = new double[7][width];
    for (int i = 0; i < 7; i++) {
      JsonArray row = rows.get(i).getAsJsonArray();
      if (row.size() != width) throw new IllegalArgumentException("Invalid art matrix");
      for (int j = 0; j < width; j++) {
        double value = row.get(j).getAsDouble();
        if (!Double.isFinite(value) || value < low || value > high)
          throw new IllegalArgumentException("Invalid art coordinate");
        result[i][j] = value;
      }
    }
    return result;
  }
}
