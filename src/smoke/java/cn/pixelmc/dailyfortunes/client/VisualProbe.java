package cn.pixelmc.dailyfortunes.client;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Isolated development screenshots; never packaged in the mod. */
public final class VisualProbe {
  private static int phase;
  private static long since;
  private static JsonObject record;

  public static void tick() throws Exception {
    Minecraft m = Minecraft.getInstance();
    long now = System.currentTimeMillis();
    if (m.screen instanceof AccessibilityOnboardingScreen) {
      m.screen.onClose();
      return;
    }
    if (phase == 0 && m.screen instanceof TitleScreen && m.getOverlay() == null) {
      m.options.guiScale().set(2);
      m.resizeDisplay();
      m.options.languageCode = "zh_cn";
      m.getLanguageManager().setSelected("zh_cn");
      m.reloadResourcePacks();
      phase = 1;
      since = now;
      return;
    }
    if (m.getOverlay() != null || now - since < 2500) return;
    switch (phase) {
      case 1 -> {
        try (var stream = VisualProbe.class.getResourceAsStream("/defaults/fortunes.json")) {
          var f =
              JsonParser.parseReader(
                      new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
                  .getAsJsonObject()
                  .getAsJsonArray("fortunes")
                  .get(0);
          record =
              obj(
                  "id",
                  "demo",
                  "player",
                  UUID.randomUUID().toString(),
                  "owner",
                  "FortuneTester",
                  "date",
                  "2026-10-26",
                  "state",
                  "archived",
                  "snapshot",
                  f);
        }
        if (Boolean.getBoolean("dailyfortunes.styles")) {
          m.setScreen(new StyleInspection(record));
          phase = 99;
          return;
        }
        m.setScreen(new Models());
        phase = 2;
        since = now;
      }
      case 2 -> {
        shot(m, "01-approved-models");
        FortuneClient.receive("read", JSON.toJson(record));
        phase = 3;
        since = now;
      }
      case 3 -> {
        shot(m, "02-reading-page");
        JsonArray records = new JsonArray();
        records.add(record);
        FortuneClient.receive(
            "calendar",
            JSON.toJson(
                obj(
                    "month",
                    "2026-10",
                    "today",
                    "2026-10-26",
                    "records",
                    records,
                    "canDisplay",
                    true)));
        phase = 4;
        since = now;
      }
      case 4 -> {
        shot(m, "03-calendar");
        m.setScreen(new ClientInspection(record));
        phase = 5;
        since = now;
      }
      case 5 -> {
        shot(m, "04-fixed-display");
        JsonObject longRecord = record.deepCopy();
        String text = s(longRecord.getAsJsonObject("snapshot"), "interpretation");
        longRecord.getAsJsonObject("snapshot").addProperty("interpretation", text.repeat(8));
        FortuneClient.receive("read", JSON.toJson(longRecord));
        phase = 6;
        since = now;
      }
      case 6 -> {
        shot(m, "05-reading-scrollbar");
        m.screen.mouseScrolled(300, 200, -8);
        phase = 7;
        since = now;
      }
      case 7 -> {
        shot(m, "06-reading-scrolled");
        System.out.println("DAILYFORTUNES_VISUAL_PASS");
        phase = 99;
        m.setScreen(new StyleInspection(record));
      }
    }
  }

  private static void shot(Minecraft m, String name) throws Exception {
    Path dir = Path.of("../build/verification/screenshots");
    Files.createDirectories(dir);
    try (var image = Screenshot.takeScreenshot(m.getMainRenderTarget())) {
      image.writeToFile(dir.resolve(name + ".png"));
    }
  }

  private static final class Models extends Screen {
    Models() {
      super(Component.literal("Models"));
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
      g.fill(0, 0, width, height, 0xFF242722);
      var items =
          List.of(
              DailyFortunes.BARREL.get(),
              DailyFortunes.SHRINE_ITEM.get(),
              DailyFortunes.CHEST_ITEM.get());
      for (int i = 0; i < 3; i++) {
        g.pose().pushPose();
        g.pose().translate(40 + i * 200, 85, 0);
        g.pose().scale(10, 10, 10);
        g.renderItem(new ItemStack(items.get(i)), 0, 0);
        g.pose().popPose();
      }
      g.drawCenteredString(font, "问津签筒 · 解签龛 · 藏签匣", width / 2, 30, 0xE5D7B9);
    }
  }
}
