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
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Explicit views of the corrected render paths, only in the development client. */
public final class ClientInspection extends Screen {
  private final JsonObject record;
  private boolean shapesChecked;

  public ClientInspection(JsonObject record) {
    super(Component.literal("Display inspection"));
    this.record = record;
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float partial) {
    g.fill(0, 0, width, height, 0xFF252825);
    g.drawString(
        font,
        "Body-matched collision / lid-height display / foliage excluded",
        12,
        12,
        0xE8DDC8,
        false);
    BlockState chestState =
        DailyFortunes.CHEST
            .get()
            .defaultBlockState()
            .setValue(FortuneBlock.FACING, Direction.SOUTH);
    BlockState shrineState =
        DailyFortunes.SHRINE
            .get()
            .defaultBlockState()
            .setValue(FortuneBlock.FACING, Direction.SOUTH);
    if (!shapesChecked) {
      verifyShapes(chestState);
      verifyShapes(shrineState);
      if (chestState.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds().maxY >= .7)
        throw new AssertionError("Chest must not keep a full-height invisible collider");
      shapesChecked = true;
      System.out.println("DAILYFORTUNES_BLOCK_SHAPES_PASS");
    }
    drawBlock(g, shrineState, width * .27, false, partial);
    drawBlock(g, chestState, width * .73, true, partial);
    if (!SlipShape.opaque(.5, .5) || SlipShape.opaque(.02, .02))
      throw new AssertionError("Alpha hit mask must reject transparent corners");
  }

  private void drawBlock(
      GuiGraphics g, BlockState state, double x, boolean showSlip, float partial) {
    Minecraft mc = Minecraft.getInstance();
    var buffers = mc.renderBuffers().bufferSource();
    g.pose().pushPose();
    g.pose().translate(x, height - 55, 120);
    g.pose().scale(155, -155, 155);
    g.pose().mulPose(Axis.XP.rotationDegrees(12));
    g.pose().mulPose(Axis.YP.rotationDegrees(25));
    g.pose().translate(-.5, 0, -.5);
    mc.getBlockRenderer()
        .renderSingleBlock(state, g.pose(), buffers, 15728880, OverlayTexture.NO_OVERLAY);
    if (showSlip) {
      ChestEntity chest = new ChestEntity(BlockPos.ZERO, state);
      chest.display = JSON.toJson(record);
      new FortuneClient.DisplayRenderer(null)
          .render(chest, partial, g.pose(), buffers, 15728880, OverlayTexture.NO_OVERLAY);
    }
    for (var box : state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs())
      LevelRenderer.renderLineBox(
          g.pose(), buffers.getBuffer(RenderType.lines()), box, .75F, .88F, 1F, .7F);
    buffers.endBatch();
    g.pose().popPose();
  }

  private static void verifyShapes(BlockState base) {
    double volume = -1;
    for (Direction facing : Direction.Plane.HORIZONTAL) {
      BlockState state = base.setValue(FortuneBlock.FACING, facing);
      var shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
      var collision =
          state.getCollisionShape(
              EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
      if (shape.isEmpty() || !Shapes.joinIsNotEmpty(shape, Shapes.block(), BooleanOp.NOT_SAME))
        throw new AssertionError("Expected body-shaped geometry, not the default full cube");
      if (Shapes.joinIsNotEmpty(shape, collision, BooleanOp.NOT_SAME))
        throw new AssertionError("Selection and collision must agree");
      if (shape != state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
        throw new AssertionError("Shape queries should use the cached facing geometry");
      if (state.is(DailyFortunes.CHEST.get())) {
        if (shape.clip(new Vec3(-.5, .8, .5), new Vec3(1.5, .8, .5), BlockPos.ZERO) != null)
          throw new AssertionError("Air above the cabinet lid must not intercept selection rays");
        double[] drawer =
            switch (facing) {
              case SOUTH -> new double[] {10.5 / 16, 14.4 / 16};
              case WEST -> new double[] {1.6 / 16, 10.5 / 16};
              case NORTH -> new double[] {5.5 / 16, 1.6 / 16};
              case EAST -> new double[] {14.4 / 16, 5.5 / 16};
              default -> throw new AssertionError("Unexpected vertical block facing");
            };
        var drawerPoint =
            Shapes.box(
                drawer[0] - .001, .405, drawer[1] - .001, drawer[0] + .001, .407, drawer[1] + .001);
        if (!Shapes.joinIsNotEmpty(shape, drawerPoint, BooleanOp.AND))
          throw new AssertionError("The opened drawer must rotate with the rendered model");
      }
      double current =
          shape.toAabbs().stream()
              .mapToDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())
              .sum();
      if (volume >= 0 && Math.abs(volume - current) > .0000001)
        throw new AssertionError("Facing rotation must preserve the collision volume");
      volume = current;
    }
  }
}
