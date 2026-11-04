package cn.pixelmc.dailyfortunes.client;

import com.google.gson.JsonObject;
import com.pixelmc.hub.client.ClientHubSession;

public final class HubInvalidationChecks {
  private static int checks;
  private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
  public static void main(String[] args) {
    JsonObject empty = new JsonObject();
    HubInvalidation.received("error", empty);
    HubInvalidation.received("draw", empty);
    HubInvalidation.received(null, empty);
    HubInvalidation.received("changed", null);
    check(ClientHubSession.notifications == 0, "Errors, requests, and invalid payloads do not signal success");
    JsonObject unexpected = new JsonObject(); unexpected.addProperty("token", "must-not-forward");
    HubInvalidation.received("changed", unexpected);
    check(ClientHubSession.notifications == 0, "The new changed notification is strictly data-free");
    for (String type : new String[] {"read", "calendar", "changed"}) HubInvalidation.received(type, empty);
    check(ClientHubSession.notifications == 3, "Successful existing replies and empty changed invoke the zero-argument hook");
    ClassLoader absent = new ClassLoader(null) { };
    HubInvalidation.received("changed", empty, absent);
    check(ClientHubSession.notifications == 3, "An absent optional Hub remains harmless");
    ClientHubSession.fail = true;
    HubInvalidation.received("changed", empty);
    check(ClientHubSession.notifications == 4, "A failing optional hook does not escape into Fortune gameplay");
    ClientHubSession.fail = false;
    HubInvalidation.received("read", empty);
    check(ClientHubSession.notifications == 5, "A later valid reply still works after hook failure");
    System.out.println("Optional Hub invalidation checks passed: " + checks);
  }
}
