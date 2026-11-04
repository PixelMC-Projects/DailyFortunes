package com.pixelmc.hub.client;

/** Test-only optional provider, excluded from every distributable mod JAR. */
public final class ClientHubSession {
  public static int notifications;
  public static boolean fail;
  public static void fortunesChanged() {
    notifications++;
    if (fail) throw new IllegalStateException("Synthetic optional-hook failure");
  }
}
