package cn.pixelmc.dailyfortunes.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared wire contract between the game and the standalone Manager service. */
public final class FortuneRpcResponse {
  private FortuneRpcResponse() {}

  public static JsonObject success(JsonObject result) {
    // 0.2.0 reads business fields directly. Keep them while preserving the RPC envelope.
    JsonObject response = result.deepCopy();
    response.add("result", result.deepCopy());
    return response;
  }

  public static JsonObject decode(String json) {
    JsonObject response;
    try {
      response = JsonParser.parseString(json).getAsJsonObject();
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("抽签服务返回数据不完整，请稍后重试", invalid);
    }
    JsonElement error = response.get("error");
    if (error != null && !error.isJsonNull()) {
      String message = error.isJsonPrimitive() ? error.getAsString() : "抽签操作未完成";
      throw new IllegalStateException(message);
    }
    JsonElement result = response.get("result");
    if (result == null || !result.isJsonObject())
      throw new IllegalStateException("抽签服务返回数据不完整，请稍后重试");
    return result.getAsJsonObject();
  }
}
