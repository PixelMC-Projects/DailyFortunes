package cn.pixelmc.dailyfortunes.client;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Optional client-only invalidation. Never forwards a record or starts its own queries/timers. */
public final class HubInvalidation {
  public static void received(String type, JsonObject data) {
    received(type, data, HubInvalidation.class.getClassLoader());
  }

  static void received(String type, JsonObject data, ClassLoader loader) {
    if (data == null || !("read".equals(type) || "calendar".equals(type) || "changed".equals(type))) return;
    if ("changed".equals(type) && !data.entrySet().isEmpty()) return;
    try {
      Class<?> session = Class.forName("com.pixelmc.hub.client.ClientHubSession", false, loader);
      Method notification = session.getMethod("fortunesChanged");
      if (Modifier.isStatic(notification.getModifiers()) && notification.getReturnType() == void.class)
        notification.invoke(null);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
      // The Hub is optional. Missing/incompatible/failing integrations must not affect physical play.
    }
  }

  private HubInvalidation() { }
}
