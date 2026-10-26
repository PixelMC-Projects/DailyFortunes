package cn.pixelmc.dailyfortunes.client;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import cn.pixelmc.dailyfortunes.forge.ChestEntity;
import cn.pixelmc.dailyfortunes.forge.FortuneBlock;
import com.google.gson.JsonObject;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit views of the corrected render paths, only in the development client. */
public final class ClientInspection extends Screen {
  private final JsonObject record;

  public ClientInspection(JsonObject record) {
    super(Component.literal("Display inspection"));
    this.record = record;
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    g.fill(0, 0, width, height, 0xFF252825);
    g.drawString(
        font,
        "Fixed-facing display / alpha contour / ordinary depth test",
        12,
        12,
        0xE8DDC8,
        false);
    Minecraft mc = Minecraft.getInstance();
    var buffers = mc.renderBuffers().bufferSource();
    BlockState state =
        DailyFortunes.CHEST
            .get()
            .defaultBlockState()
            .setValue(FortuneBlock.FACING, Direction.SOUTH);
    ChestEntity chest = new ChestEntity(BlockPos.ZERO, state);
    chest.display = JSON.toJson(record);
    g.pose().pushPose();
    g.pose().translate(width / 2.0, height - 60, 120);
    g.pose().scale(115, -115, 115);
    g.pose().mulPose(Axis.XP.rotationDegrees(12));
    g.pose().mulPose(Axis.YP.rotationDegrees(25));
    g.pose().translate(-.5, 0, -.5);
    mc.getBlockRenderer()
        .renderSingleBlock(state, g.pose(), buffers, 15728880, OverlayTexture.NO_OVERLAY);
    new FortuneClient.DisplayRenderer(null)
        .render(chest, partial, g.pose(), buffers, 15728880, OverlayTexture.NO_OVERLAY);
    buffers.endBatch();
    g.pose().popPose();
    if (!SlipShape.opaque(.5, .5) || SlipShape.opaque(.02, .02))
      throw new AssertionError("Alpha hit mask must reject transparent corners");
  }
}
