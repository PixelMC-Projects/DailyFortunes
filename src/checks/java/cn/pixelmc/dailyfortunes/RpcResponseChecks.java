package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.ServiceChecks.*;
import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import cn.pixelmc.dailyfortunes.service.FortuneRpcResponse;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.UUID;

/** The game-facing receipt contract, including recovery after a committed but undelivered draw. */
public final class RpcResponseChecks {
  private static JsonObject wire(FortuneBackend backend, UUID player, String op, Object... args) {
    return FortuneRpcResponse.success(rpc(backend, player, op, args));
  }

  public static void main(String[] args) throws Exception {
    Path folder = Path.of("build/checks/rpc-" + UUID.randomUUID()).toAbsolutePath();
    String url = "jdbc:h2:file:" + folder.resolve("fortunes").toString().replace('\\', '/')
        + ";MODE=MySQL;NON_KEYWORDS=DAY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    UUID player = UUID.randomUUID();
    try (FortuneBackend backend = new FortuneBackend(url, "sa", "", folder)) {
      JsonObject login = wire(backend, player, "login", "ReceiptTester");
      check(FortuneRpcResponse.decode(JSON.toJson(login)).has("today"), "game receives login payload");
      JsonObject drawn = wire(backend, player, "draw");
      JsonObject original = drawn.getAsJsonObject("record");
      check(original != null, "running 0.2.0 game can generate an item directly from the response");
      check(original.equals(FortuneRpcResponse.decode(JSON.toJson(drawn)).getAsJsonObject("record")), "updated game reads the same committed receipt");
      JsonObject pending = wire(backend, player, "pending");
      check(pending.getAsJsonArray("records").get(0).equals(original), "unacknowledged draw is retried without replacing its result or token");
      check(FortuneRpcResponse.decode(JSON.toJson(pending)).getAsJsonArray("records").size() == 1, "updated game can deliver queued receipts");
      rejected(() -> wire(backend, player, "draw"), "retry cannot consume another draw or reroll");
      rejected(() -> FortuneRpcResponse.decode("{\"error\":\"今日已抽过签\",\"result\":{\"record\":{}}}"), "errors take priority over result fields");
      rejected(() -> FortuneRpcResponse.decode("{\"healthy\":true}"), "health is not a successful business result");
      rejected(() -> FortuneRpcResponse.decode("{\"result\":null}"), "null result rejected before delivery");
      rejected(() -> FortuneRpcResponse.decode("[]"), "malformed response rejected before delivery");
      rpc(backend, player, "ack", s(original, "date"), s(original, "token"));
      check(wire(backend, player, "pending").getAsJsonArray("records").isEmpty(), "ack removes receipt from delivery queue only after the item was accepted");
    }
    System.out.println("PASS: " + checks + " RPC receipt contract assertions");
  }
}
