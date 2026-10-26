package cn.pixelmc.dailyfortunes.client;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import com.google.gson.*;
import com.mojang.blaze3d.platform.Lighting;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

/** Isolated rendering cost comparison. No worlds, accounts or player saves are opened. */
public final class ModelBenchmark extends Screen {
  private static final String[] NAMES = {
    "fortune_barrel", "interpretation_shrine", "collection_chest"
  };
  private final List<Long> samples = new ArrayList<>();
  private final JsonArray results = new JsonArray();
  private int stage, frames;

  public ModelBenchmark() {
    super(Component.literal("Model benchmark"));
  }

  @Mod.EventBusSubscriber(
      modid = DailyFortunes.ID,
      value = Dist.CLIENT,
      bus = Mod.EventBusSubscriber.Bus.MOD)
  public static final class Registration {
    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional e) {
      for (String name : NAMES)
        e.register(new ResourceLocation(DailyFortunes.ID, "item/baseline_" + name));
    }
  }

  public void render(GuiGraphics g, int mx, int my, float partial) {
    Minecraft mc = Minecraft.getInstance();
    if (stage >= 6) return;
    boolean baseline = stage % 2 == 0;
    ItemDisplayContext context =
        stage < 2
            ? ItemDisplayContext.GUI
            : stage < 4 ? ItemDisplayContext.GROUND : ItemDisplayContext.FIXED;
    g.fill(0, 0, width, height, 0xFF252825);
    g.drawString(
        font, (baseline ? "MASTER" : "RUNTIME") + " / " + context.name(), 12, 12, 0xE8DDC8, false);
    g.flush();
    GL11.glFinish();
    long start = System.nanoTime();
    var buffers = mc.renderBuffers().bufferSource();
    Lighting.setupFor3DItems();
    Item[] items = {
      DailyFortunes.BARREL.get(), DailyFortunes.SHRINE_ITEM.get(), DailyFortunes.CHEST_ITEM.get()
    };
    for (int row = 0; row < 3; row++)
      for (int col = 0; col < 3; col++)
        for (int kind = 0; kind < 3; kind++) {
          if (context == ItemDisplayContext.GUI && !baseline) Lighting.setupForFlatItems();
          else Lighting.setupFor3DItems();
          ItemStack stack = new ItemStack(items[kind]);
          BakedModel model =
              baseline
                  ? mc.getModelManager()
                      .getModel(
                          new ResourceLocation(DailyFortunes.ID, "item/baseline_" + NAMES[kind]))
                  : mc.getItemRenderer().getModel(stack, null, null, 0);
          g.pose().pushPose();
          g.pose().translate(48 + col * 180 + kind * 48, 82 + row * 95, 140);
          g.pose().scale(44, -44, 44);
          mc.getItemRenderer()
              .render(
                  stack,
                  context,
                  false,
                  g.pose(),
                  buffers,
                  15728880,
                  OverlayTexture.NO_OVERLAY,
                  model);
          g.pose().popPose();
        }
    buffers.endBatch();
    GL11.glFinish();
    long elapsed = System.nanoTime() - start;
    if (frames++ >= 25) samples.add(elapsed);
    if (samples.size() < 100) return;
    try {
      List<Long> ordered = new ArrayList<>(samples);
      Collections.sort(ordered);
      JsonObject row =
          obj(
              "variant",
              baseline ? "master" : "runtime",
              "context",
              context.name(),
              "modelsPerFrame",
              27,
              "frames",
              samples.size(),
              "medianMs",
              ordered.get(ordered.size() / 2) / 1e6,
              "p95Ms",
              ordered.get(ordered.size() * 95 / 100) / 1e6);
      results.add(row);
      Path dir = Path.of("../build/verification/render-benchmark");
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve("environment.json"),
          JSON.toJson(
              obj(
                  "java",
                  System.getProperty("java.version"),
                  "renderer",
                  GL11.glGetString(GL11.GL_RENDERER),
                  "vendor",
                  GL11.glGetString(GL11.GL_VENDOR),
                  "openGL",
                  GL11.glGetString(GL11.GL_VERSION))));
      try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
        image.writeToFile(
            dir.resolve(
                stage + "-" + context.name() + "-" + (baseline ? "master" : "runtime") + ".png"));
      }
      Files.writeString(dir.resolve("results.json"), JSON.toJson(results));
      System.out.println("DAILYFORTUNES_BENCHMARK " + JSON.toJson(row));
      stage++;
      frames = 0;
      samples.clear();
      if (stage == 6) {
        System.out.println("DAILYFORTUNES_BENCHMARK_PASS");
        mc.stop();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
