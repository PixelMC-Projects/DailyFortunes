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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.InventoryMenu;
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
  public static void reload(RegisterClientReloadListenersEvent e) {
    e.registerReloadListener(
        (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
            manager -> SlipShape.clear());
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
    private boolean dragging;

    ReadingScreen(JsonObject r, Screen parent) {
      super(Component.literal("解签"));
      record = r;
      this.parent = parent;
    }

    protected void init() {
      lastScroll = null;
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

    @Override
    public boolean mouseClicked(double x, double y, int button) {
      if (button == 0
          && lastScroll != null
          && lastScroll.maximum > 0
          && lastScroll.contains(x, y)) {
        dragging = true;
        scroll = lastScroll.at(y);
        return true;
      }
      return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
      if (dragging && lastScroll != null) {
        scroll = lastScroll.at(y);
        return true;
      }
      return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
      dragging = false;
      return super.mouseReleased(x, y, button);
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
    lastScroll =
        new ScrollArea(
            right + 7,
            top + 31,
            4,
            Math.max(16, bottom - top - 49),
            Math.max(0, lines.size() - rows));
    if (lastScroll.maximum > 0) {
      int thumb = Math.max(12, lastScroll.height * rows / lines.size());
      int sy = lastScroll.y + (lastScroll.height - thumb) * scroll / lastScroll.maximum;
      g.fill(
          lastScroll.x,
          lastScroll.y,
          lastScroll.x + 4,
          lastScroll.y + lastScroll.height,
          0x386F5B42);
      g.fill(lastScroll.x, sy, lastScroll.x + 4, sy + thumb, 0xC5A68B59);
    }
    return scroll;
  }

  private record ScrollArea(int x, int y, int width, int height, int maximum) {
    boolean contains(double mx, double my) {
      return mx >= x - 3 && mx < x + width + 3 && my >= y && my < y + height;
    }

    int at(double mouseY) {
      return Math.max(
          0,
          Math.min(
              maximum, (int) Math.round((mouseY - y) / (double) Math.max(1, height) * maximum)));
    }
  }

  private static ScrollArea lastScroll;

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

    private int weeks() {
      return (month.atDay(1).getDayOfWeek().getValue() % 7 + month.lengthOfMonth() + 6) / 7;
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
      renderBackground(g);
      int x = width / 2 - 144, y = height / 2 - 106;
      art(g, texture("gui/fortune_page_background"), width / 2 - 193, y - 33, 386, 270);
      String heading = month.toString().replace('-', '/');
      g.drawString(font, heading, width / 2 - font.width(heading) / 2, y + 18, 0x514738, false);
      int offset = month.atDay(1).getDayOfWeek().getValue() % 7;
      int rows = weeks(), cellHeight = 132 / rows, row0 = y + 34;
      for (int row = 0; row < rows; row++) {
        for (int col = 0; col < 9; col++) {
          int px = x + col * 32, py = row0 + row * cellHeight;
          if (col == 0 || col == 8) {
            if (row == rows / 2) {
              if (mx >= px && mx < px + 32 && my >= py && my < py + cellHeight)
                g.fill(px + 5, py + 2, px + 27, py + cellHeight - 2, 0x22564C3E);
              g.drawString(
                  font, col == 0 ? "<" : ">", px + 13, py + cellHeight / 2 - 4, 0x80643E, false);
            }
            continue;
          }
          int day = row * 7 + col - offset;
          if (day < 1 || day > month.lengthOfMonth()) continue;
          g.fill(px + 1, py + 1, px + 31, py + cellHeight - 1, 0x33564C3E);
          String date = month.atDay(day).toString();
          JsonObject r = records.get(date);
          int itemY = py + (cellHeight - 16) / 2;
          if (r != null && s(r, "state").equals("archived")) {
            g.renderItem(FortuneItem.stack(r), px + 8, itemY);
            int color = FortuneItem.gradeColor(s(r.getAsJsonObject("snapshot"), "grade"));
            g.fill(px + 25, py + cellHeight - 7, px + 30, py + cellHeight - 2, 0xFF000000 | color);
          } else {
            RenderSystem.setShaderColor(1, 1, 1, .22F);
            art(g, texture("item/fortune_slip"), px + 8, itemY, 16, 16);
            RenderSystem.setShaderColor(1, 1, 1, 1);
          }
          g.drawString(font, String.valueOf(day), px + 3, py + 3, 0x6A5944, false);
          if (date.equals(selected)) g.renderOutline(px, py, 32, cellHeight, 0xFFD6B66A);
        }
      }
      String help = "左键阅读 · 右键展示 · 中键清除展示";
      g.drawString(font, help, width / 2 - font.width(help) / 2, y + 174, 0x806A48, false);
      super.render(g, mx, my, pt);
      int hoverCol = (mx - x) / 32, hoverRow = (my - row0) / cellHeight;
      if (mx >= x + 32 && mx < x + 256 && my >= row0 && hoverRow >= 0 && hoverRow < rows) {
        int day = hoverRow * 7 + hoverCol - offset;
        if (day >= 1 && day <= month.lengthOfMonth()) {
          JsonObject hovered = records.get(month.atDay(day).toString());
          if (hovered != null && s(hovered, "state").equals("archived"))
            g.renderTooltip(font, FortuneItem.stack(hovered), mx, my);
        }
      }
    }

    public boolean mouseClicked(double mx, double my, int button) {
      int x = width / 2 - 144, y = height / 2 - 106, rows = weeks(), cellHeight = 132 / rows;
      int col = (int) Math.floor((mx - x) / 32), row = (int) Math.floor((my - y - 34) / cellHeight);
      if (row >= 0 && row < rows && col >= 0 && col < 9) {
        if ((col == 0 || col == 8) && row == rows / 2) {
          FortuneNetwork.request("calendar", month.plusMonths(col == 0 ? -1 : 1).toString());
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
        pose.pushPose();
        pose.translate(.5, 2.05, .5);
        Direction facing = chest.getBlockState().getValue(FortuneBlock.FACING);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(.62F, .62F, .62F);
        var mc = Minecraft.getInstance();
        TextureAtlasSprite paper =
            mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(new ResourceLocation("dailyfortunes", "item/fortune_slip"));
        TextureAtlasSprite outline =
            mc.getModelManager()
                .getModel(new ResourceLocation("dailyfortunes", "item/fortune_slip_outline"))
                .getParticleIcon();
        paperQuad(pose, buffers, outline, .001F, 15728880, overlay);
        paperQuad(pose, buffers, paper, 0, light, overlay);
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

  private static void paperQuad(
      PoseStack pose,
      MultiBufferSource buffers,
      TextureAtlasSprite sprite,
      float z,
      int light,
      int overlay) {
    VertexConsumer v = buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
    var matrix = pose.last().pose();
    var normal = pose.last().normal();
    float[][] positions = {{-.5F, -.5F}, {.5F, -.5F}, {.5F, .5F}, {-.5F, .5F}};
    float[][] uv = {
      {sprite.getU0(), sprite.getV1()},
      {sprite.getU1(), sprite.getV1()},
      {sprite.getU1(), sprite.getV0()},
      {sprite.getU0(), sprite.getV0()}
    };
    for (int i = 0; i < 4; i++)
      v.vertex(matrix, positions[i][0], positions[i][1], z)
          .color(255, 255, 255, 255)
          .uv(uv[i][0], uv[i][1])
          .overlayCoords(overlay)
          .uv2(light)
          .normal(normal, 0, 0, 1)
          .endVertex();
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
        Direction facing = chest.getBlockState().getValue(FortuneBlock.FACING);
        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 ray = end.subtract(eye);
        double denominator = ray.dot(normal);
        if (Math.abs(denominator) < .000001) continue;
        double t = mid.subtract(eye).dot(normal) / denominator;
        if (t < 0 || t > 1) continue;
        Vec3 point = eye.add(ray.scale(t));
        double distance = eye.distanceToSqr(point);
        Vec3 local = point.subtract(mid);
        Vec3 right = new Vec3(normal.z, 0, -normal.x);
        double u = .5 + local.dot(right) / .62, v = .5 - local.y / .62;
        if (distance < nearest && SlipShape.opaque(u, v)) {
          target = chest;
          nearest = distance;
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
