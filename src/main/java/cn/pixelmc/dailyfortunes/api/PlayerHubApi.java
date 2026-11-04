package cn.pixelmc.dailyfortunes.api;

import cn.pixelmc.dailyfortunes.forge.GameRuntime;
import cn.pixelmc.dailyfortunes.service.PlayerHubRequests;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional own-player channel. UUID must originate from the authenticated game connection. */
public final class PlayerHubApi {
  public static int protocolVersion() { return 1; }

  public static CompletableFuture<String> request(UUID player, String action, String payload) {
    try {
      if (player == null) throw new IllegalArgumentException("玩家会话已失效");
      return GameRuntime.playerHub(player, action, PlayerHubRequests.parse(action, payload));
    } catch (RuntimeException invalid) {
      return CompletableFuture.failedFuture(new IllegalStateException("签历请求参数无效"));
    }
  }

  private PlayerHubApi() { }
}
