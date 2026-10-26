package cn.pixelmc.dailyfortunes.client;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import cn.pixelmc.dailyfortunes.forge.*;
import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.time.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
    modid = DailyFortunes.ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FortuneClient {
  public static ResourceLocation texture(String path) {
    return new ResourceLocation(DailyFortunes.ID, "textures/" + path + ".png");
  }

  @SubscribeEvent
  public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
    e.registerBlockEntityRenderer(DailyFortunes.CHEST_ENTITY.get(), DisplayRenderer::new);
  }

  public static void receive(String type, String json) {
    try {
      JsonObject data = JsonParser.parseString(json).getAsJsonObject();
      Minecraft mc = Minecraft.getInstance();
      if (type.equals("read")) mc.setScreen(new ReadingScreen(data, mc.screen));
      else if (type.equals("calendar")) {
        if (mc.screen instanceof CalendarScreen calendar) calendar.update(data);
        else mc.setScreen(new CalendarScreen(data));
      }
    } catch (Exception ignored) {
    }
  }

  private static void art(GuiGraphics g, ResourceLocation image, int x, int y, int w, int h) {
    RenderSystem.enableBlend();
    g.blit(image, x, y, 0, 0, w, h, w, h);
  }

  private static final class CloseButton extends AbstractButton {
    private final Runnable close;

    CloseButton(int x, int y, Runnable close) {
      super(x, y, 22, 22, Component.literal("关闭"));
      this.close = close;
    }

    public void onPress() {
      close.run();
    }

    protected void renderWidget(GuiGraphics g, int x, int y, float pt) {
      String state =
          isHoveredOrFocused()
              ? (Minecraft.getInstance().mouseHandler.isLeftPressed() ? "pressed" : "hover")
              : "normal";
      art(g, texture("gui/close_" + state), getX(), getY(), width, height);
    }

    protected void updateWidgetNarration(NarrationElementOutput out) {
      defaultButtonNarrationText(out);
    }
  }

  public static final class ReadingScreen extends Screen {
    private final JsonObject record;
    private final Screen parent;
    private int scroll;

    ReadingScreen(JsonObject r, Screen parent) {
      super(Component.literal("解签"));
      record = r;
      this.parent = parent;
    }

    protected void init() {
      int w = Math.min(560, width - 20), h = w * 2 / 3;
      addRenderableWidget(
          new CloseButton((width + w) / 2 - 44, (height - h) / 2 + 25, this::onClose));
    }

    public boolean isPauseScreen() {
      return false;
    }

    public void onClose() {
      minecraft.setScreen(parent instanceof CalendarScreen ? parent : null);
    }

    public boolean mouseScrolled(double x, double y, double delta) {
      scroll = Math.max(0, scroll - (int) Math.signum(delta) * 2);
      return true;
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
      renderBackground(g);
      int w = Math.min(560, width - 20), h = w * 2 / 3;
      scroll = panel(g, (width - w) / 2, (height - h) / 2, w, h, record, false, scroll);
      super.render(g, mx, my, pt);
    }
  }

  /** All text is real rendered text, kept inside the approved artwork's paper safe area. */
  private static int panel(
      GuiGraphics g, int x, int y, int w, int h, JsonObject r, boolean hover, int requestedScroll) {
    art(
        g,
        texture(hover ? "gui/fortune_hover_background" : "gui/fortune_page_background"),
        x,
        y,
        w,
        h);
    var font = Minecraft.getInstance().font;
    JsonObject f = r.getAsJsonObject("snapshot");
    if (f == null) return 0;
    int left = x + (int) (w * (hover ? .145 : .195)),
        right = x + (int) (w * (hover ? .87 : .80)),
        top = y + (int) (h * (hover ? .21 : .215)),
        bottom = y + (int) (h * (hover ? .78 : .76));
    int width = right - left;
    g.drawString(font, s(f, "title"), left, top, 0x514738, false);
    g.drawString(
        font,
        FortuneItem.heading(r)
            .copy()
            .withStyle(style -> style.withColor(darken(FortuneItem.gradeColor(s(f, "grade"))))),
        left,
        top + 14,
        0x66543E,
        false);
    List<FormattedCharSequence> lines = new ArrayList<>();
    for (JsonElement line : f.getAsJsonArray("poem"))
      lines.addAll(font.split(Component.literal(line.getAsString()), width));
    lines.add(FormattedCharSequence.EMPTY);
    lines.addAll(font.split(Component.literal(s(f, "interpretation")), width));
    int rows = Math.max(1, (bottom - top - 49) / 11),
        scroll = Math.min(requestedScroll, Math.max(0, lines.size() - rows));
    for (int n = 0; n < rows && n + scroll < lines.size(); n++)
      g.drawString(font, lines.get(n + scroll), left, top + 31 + n * 11, 0x665F55, false);
    Component owner =
        Component.literal(s(r, "owner"))
            .withStyle(style -> style.withColor(0x8E721E))
            .append(
                Component.literal(" 所得    " + s(r, "date").replace('-', '/'))
                    .withStyle(style -> style.withColor(0x99733E)));
    g.drawString(font, owner, left, bottom - 8, 0x99733E, false);
    if (lines.size() > rows)
      g.drawString(
          font,
          (hover ? "滚轮查看全部解签" : "滚轮翻阅")
              + "  "
              + (scroll + 1)
              + "/"
              + Math.max(1, lines.size() - rows + 1),
          left,
          bottom + 8,
          0x8A806E,
          false);
    return scroll;
  }

  private static int darken(int rgb) {
    return ((int) (((rgb >> 16) & 255) * .62) << 16)
        | ((int) (((rgb >> 8) & 255) * .62) << 8)
        | (int) ((rgb & 255) * .62);
  }

  public static final class CalendarScreen extends Screen {
    private JsonObject data;
    private YearMonth month;
    private Map<String, JsonObject> records = new HashMap<>();
    private String selected = "";

    CalendarScreen(JsonObject data) {
      super(Component.literal("藏签匣"));
      update(data);
    }

    void update(JsonObject value) {
      data = value;
      month = YearMonth.parse(s(data, "month"));
      records.clear();
      for (JsonElement e : data.getAsJsonArray("records")) {
        JsonObject r = e.getAsJsonObject();
        records.put(s(r, "date"), r);
      }
    }

    protected void init() {
      addRenderableWidget(new CloseButton(width / 2 + 137, height / 2 - 112, this::onClose));
    }

    public boolean isPauseScreen() {
      return false;
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
      renderBackground(g);
      int x = width / 2 - 144, y = height / 2 - 106;
      art(g, texture("gui/fortune_page_background"), width / 2 - 193, y - 33, 386, 270);
      g.drawString(
          font, month.toString().replace('-', '/'), width / 2 - 18, y + 7, 0x514738, false);
      int offset = month.atDay(1).getDayOfWeek().getValue() % 7;
      int row0 = y + 28;
      for (int row = 0; row < 6; row++)
        for (int col = 0; col < 9; col++) {
          int px = x + col * 32, py = row0 + row * 26;
          g.fill(px + 1, py + 1, px + 31, py + 25, 0x33564C3E);
          if (col == 0 || col == 8) {
            if (row == 2)
              g.drawCenteredString(font, col == 0 ? "‹" : "›", px + 16, py + 9, 0xFFBC985A);
            continue;
          }
          int day = row * 7 + col - offset;
          if (day < 1 || day > month.lengthOfMonth()) continue;
          String date = month.atDay(day).toString();
          JsonObject r = records.get(date);
          if (r != null && s(r, "state").equals("archived")) {
            g.fill(px + 2, py + 2, px + 30, py + 24, 0x55333333);
            g.renderItem(FortuneItem.stack(r), px + 8, py + 6);
            int color = FortuneItem.gradeColor(s(r.getAsJsonObject("snapshot"), "grade"));
            g.fill(px + 25, py + 19, px + 30, py + 24, 0xFF000000 | color);
          } else {
            RenderSystem.setShaderColor(1, 1, 1, .22F);
            art(g, texture("item/fortune_slip"), px + 8, py + 6, 16, 16);
            RenderSystem.setShaderColor(1, 1, 1, 1);
          }
          g.drawString(font, String.valueOf(day), px + 3, py + 3, 0x6A5944, false);
          if (date.equals(selected)) g.renderOutline(px, py, 32, 26, 0xFFD6B66A);
        }
      g.drawCenteredString(font, "左键阅读 · 右键展示 · 中键清除展示", width / 2, y + 192, 0x9F865C);
      super.render(g, mx, my, pt);
    }

    public boolean mouseClicked(double mx, double my, int button) {
      int x = width / 2 - 144,
          y = height / 2 - 106,
          col = (int) Math.floor((mx - x) / 32),
          row = (int) Math.floor((my - y - 28) / 26);
      if (row >= 0 && row < 6 && col >= 0 && col < 9) {
        if ((col == 0 || col == 8) && row == 2) {
          month = month.plusMonths(col == 0 ? -1 : 1);
          FortuneNetwork.request("calendar", month.toString());
          return true;
        }
        if (button == 2 && b(data, "canDisplay")) {
          FortuneNetwork.request("clear", "");
          return true;
        }
        if (col > 0 && col < 8) {
          int day = row * 7 + col - month.atDay(1).getDayOfWeek().getValue() % 7;
          if (day >= 1 && day <= month.lengthOfMonth()) {
            selected = month.atDay(day).toString();
            JsonObject r = records.get(selected);
            if (r != null && s(r, "state").equals("archived")) {
              if (button == 1 && b(data, "canDisplay")) FortuneNetwork.request("display", selected);
              else if (button == 0) minecraft.setScreen(new ReadingScreen(r, this));
            } else if (r != null && button == 1) FortuneNetwork.request("recover", selected);
            return true;
          }
        }
      }
      return super.mouseClicked(mx, my, button);
    }
  }

  private static final LinkedHashMap<net.minecraft.core.BlockPos, ChestEntity> VISIBLE_CHESTS =
      new LinkedHashMap<>();

  public static final class DisplayRenderer implements BlockEntityRenderer<ChestEntity> {
    public DisplayRenderer(BlockEntityRendererProvider.Context c) {}

    public void render(
        ChestEntity chest,
        float pt,
        PoseStack pose,
        MultiBufferSource buffers,
        int light,
        int overlay) {
      if (chest.display.isBlank()) return;
      VISIBLE_CHESTS.put(chest.getBlockPos(), chest);
      while (VISIBLE_CHESTS.size() > 256)
        VISIBLE_CHESTS.remove(VISIBLE_CHESTS.keySet().iterator().next());
      try {
        JsonObject r = JsonParser.parseString(chest.display).getAsJsonObject();
        pose.pushPose();
        pose.translate(.5, 2.05, .5);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.scale(.62F, .62F, .62F);
        Minecraft.getInstance()
            .getItemRenderer()
            .renderStatic(
                FortuneItem.stack(r),
                ItemDisplayContext.FIXED,
                light,
                overlay,
                pose,
                buffers,
                chest.getLevel(),
                0);
        // Thin physical gold frame: ordinary depth-tested geometry, never vanilla glowing outline.
        VertexConsumer v = buffers.getBuffer(RenderType.lines());
        var matrix = pose.last().pose();
        var normal = pose.last().normal();
        float[][] points = {{-.51F, -.51F}, {.51F, -.51F}, {.51F, .51F}, {-.51F, .51F}};
        for (int k = 0; k < 4; k++) {
          float[] a = points[k], b = points[(k + 1) % 4];
          float nx = b[0] - a[0], ny = b[1] - a[1];
          v.vertex(matrix, a[0], a[1], .015F)
              .color(224, 185, 88, 255)
              .normal(normal, nx, ny, 0)
              .endVertex();
          v.vertex(matrix, b[0], b[1], .015F)
              .color(224, 185, 88, 255)
              .normal(normal, nx, ny, 0)
              .endVertex();
        }
        pose.popPose();
      } catch (Exception ignored) {
      }
    }

    public boolean shouldRenderOffScreen(ChestEntity c) {
      return true;
    }

    public int getViewDistance() {
      return 32;
    }
  }

  @Mod.EventBusSubscriber(modid = DailyFortunes.ID, value = Dist.CLIENT)
  public static final class Overlay {
    private static JsonObject hovered;
    private static int scroll;
    private static String key = "";

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent e) {
      if (hovered != null && Minecraft.getInstance().screen == null) {
        scroll = Math.max(0, scroll - (int) Math.signum(e.getScrollDelta()) * 2);
        e.setCanceled(true);
      }
    }

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post e) {
      if (!e.getOverlay().id().equals(new ResourceLocation("minecraft", "crosshair"))) return;
      Minecraft mc = Minecraft.getInstance();
      hovered = null;
      if (mc.level == null || mc.player == null) {
        VISIBLE_CHESTS.clear();
        return;
      }
      if (mc.screen != null || mc.options.hideGui) return;
      Vec3 eye = mc.player.getEyePosition(), end = eye.add(mc.player.getViewVector(1).scale(6));
      BlockHitResult hit =
          mc.level.clip(
              new ClipContext(
                  eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
      double nearest = eye.distanceToSqr(hit.getLocation());
      ChestEntity target = null;
      // Candidates come from the renderer; no per-frame volume or chunk scan.
      var candidates = VISIBLE_CHESTS.entrySet().iterator();
      while (candidates.hasNext()) {
        var candidate = candidates.next();
        ChestEntity chest = candidate.getValue();
        var pos = candidate.getKey();
        if (chest.isRemoved()
            || chest.getLevel() != mc.level
            || pos.distSqr(mc.player.blockPosition()) > 1024) {
          candidates.remove();
          continue;
        }
        if (chest.display.isBlank()) continue;
        Vec3 mid = Vec3.atCenterOf(pos).add(0, 1.55, 0);
        if (mid.distanceToSqr(eye) > 49) continue;
        var intersection =
            new AABB(mid.x - .36, mid.y - .36, mid.z - .36, mid.x + .36, mid.y + .36, mid.z + .36)
                .clip(eye, end);
        if (intersection.isPresent() && eye.distanceToSqr(intersection.get()) < nearest) {
          target = chest;
          nearest = eye.distanceToSqr(intersection.get());
        }
      }
      if (target == null) {
        key = "";
        scroll = 0;
        return;
      }
      try {
        JsonObject r = JsonParser.parseString(target.display).getAsJsonObject();
        if (r.has("hover") && !b(r, "hover")) return;
        String id = s(r, "id");
        if (!id.equals(key)) {
          key = id;
          scroll = 0;
        }
        hovered = r;
        int w = Math.min(320, mc.getWindow().getGuiScaledWidth() - 20),
            h = w * 3 / 4,
            x =
                Math.min(
                    mc.getWindow().getGuiScaledWidth() - w - 8,
                    mc.getWindow().getGuiScaledWidth() / 2 + 18),
            y = Math.max(5, (mc.getWindow().getGuiScaledHeight() - h) / 2);
        scroll = panel(e.getGuiGraphics(), x, y, w, h, r, true, scroll);
      } catch (Exception ignored) {
      }
    }
  }
}
