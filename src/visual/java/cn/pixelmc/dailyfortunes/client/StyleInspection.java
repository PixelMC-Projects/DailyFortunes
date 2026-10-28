package cn.pixelmc.dailyfortunes.client;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Isolated native renderer and optional-pack reload acceptance; no world is opened. */
@Mod.EventBusSubscriber(modid = DailyFortunes.ID, value = Dist.CLIENT)
public final class StyleInspection extends Screen {
  private final JsonObject record;
  private static StyleInspection active;
  private int phase, frames;
  private Collection<String> defaults;

  public StyleInspection(JsonObject record) {
    super(Component.literal("DailyFortunes art profiles"));
    this.record = record;
    active = this;
  }

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) throws Exception {
    Minecraft mc = Minecraft.getInstance();
    if (active == null || event.phase != TickEvent.Phase.END || mc.getOverlay() != null) return;
    StyleInspection s = active;
    if (++s.frames < 60) return;
    s.frames = 0;
    Path output = Path.of("../build/verification/art-profiles");
    Files.createDirectories(output);
    if (s.phase == 0) {
      assertStyle("vanilla", 134, 91);
      s.defaults = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
      if (s.defaults.stream().anyMatch(id -> id.contains("DailyFortunes-Classic")))
        throw new AssertionError("Optional pack was enabled by default");
      screenshot(output.resolve("01-vanilla.png"));
      mc.getResourcePackRepository().reload();
      String pack = mc.getResourcePackRepository().getAvailableIds().stream()
          .filter(id -> id.contains("DailyFortunes-Classic-0.3.0.zip")).findFirst().orElseThrow();
      List<String> chosen = new ArrayList<>(s.defaults); chosen.add(pack);
      mc.getResourcePackRepository().setSelected(chosen);
      s.phase = 1;
      mc.reloadResourcePacks();
    } else if (s.phase == 1) {
      assertStyle("classic", 613, 402);
      screenshot(output.resolve("02-classic.png"));
      mc.getResourcePackRepository().setSelected(s.defaults);
      s.phase = 2;
      mc.reloadResourcePacks();
    } else if (s.phase == 2) {
      assertStyle("vanilla", 134, 91);
      screenshot(output.resolve("03-default-restored.png"));
      s.phase = 3;
      mc.setScreen(new ClientInspection(s.record));
    } else {
      screenshot(output.resolve("04-collision-and-slip.png"));
      Files.writeString(output.resolve("passed.txt"), "Default vanilla; optional classic; hot reload back; matching block shapes and slip plane. No worlds or accounts modified.\n");
      System.out.println("DAILYFORTUNES_ART_PROFILES_PASS");
      active = null;
      mc.stop();
    }
  }

  private static void assertStyle(String expected, int shrineQuads, int chestQuads) {
    if (!ClientArtProfile.current().style.equals(expected)) throw new AssertionError("Wrong art profile");
    var renderer = Minecraft.getInstance().getBlockRenderer();
    for (var row : List.of(Map.entry(DailyFortunes.SHRINE.get(), shrineQuads), Map.entry(DailyFortunes.CHEST.get(), chestQuads))) {
      int count = renderer.getBlockModel(row.getKey().defaultBlockState())
          .getQuads(null, null, RandomSource.create(0)).size();
      if (count != row.getValue()) throw new AssertionError("Wrong baked model: " + row.getKey() + " " + count);
    }
  }

  private static void screenshot(Path path) throws Exception {
    try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(path); }
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    g.fill(0, 0, width, height, 0xFF252825);
    g.drawCenteredString(font, "DailyFortunes / " + ClientArtProfile.current().style, width / 2, 16, 0xEEE3CD);
    Minecraft mc = Minecraft.getInstance();
    Item[] items = {DailyFortunes.BARREL.get(), DailyFortunes.SHRINE_ITEM.get(), DailyFortunes.CHEST_ITEM.get()};
    var buffers = mc.renderBuffers().bufferSource();
    Lighting.setupFor3DItems();
    for (int i = 0; i < 3; i++) {
      ItemStack stack = new ItemStack(items[i]);
      var model = mc.getItemRenderer().getModel(stack, null, null, 0);
      g.pose().pushPose();
      g.pose().translate(width * (i + .5) / 3, height * .55, 150);
      g.pose().scale(155, -155, 155);
      g.pose().mulPose(Axis.XP.rotationDegrees(20));
      g.pose().mulPose(Axis.YP.rotationDegrees(25));
      mc.getItemRenderer().render(stack, ItemDisplayContext.FIXED, false, g.pose(), buffers, 15728880,
          OverlayTexture.NO_OVERLAY, model);
      buffers.endBatch();
      g.pose().popPose();
      g.pose().pushPose();
      g.pose().translate(width * (i + .5) / 3 - 32, height - 90, 0);
      g.pose().scale(4, 4, 4);
      g.renderItem(stack, 0, 0);
      g.pose().popPose();
    }
  }
}
