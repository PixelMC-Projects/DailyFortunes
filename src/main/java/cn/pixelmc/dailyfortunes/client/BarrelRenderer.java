package cn.pixelmc.dailyfortunes.client;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

@net.neoforged.fml.common.EventBusSubscriber(
    modid = DailyFortunes.ID,
    value = Dist.CLIENT,
    bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
public final class BarrelRenderer extends BlockEntityWithoutLevelRenderer {
  private static BarrelRenderer instance;
  private static final ModelResourceLocation SHELL =
      ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID, "item/barrel_shell"));
  private static final ModelResourceLocation[] STICKS = new ModelResourceLocation[7];

  static {
    for (int i = 0; i < 7; i++)
      STICKS[i] = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID, "item/barrel_stick_" + i));
  }

  private static final LinkedHashMap<String, Motion> MOTIONS = new LinkedHashMap<>(128, .75F, true);

  private static final class Motion {
    final StickPhysics physics = new StickPhysics(ClientArtProfile.current());
    long nanos;
    double vx, vz, vy;
    float yaw, pitch;
    boolean sampled;
  }

  private BarrelRenderer() {
    super(
        Minecraft.getInstance().getBlockEntityRenderDispatcher(),
        Minecraft.getInstance().getEntityModels());
  }

  public static void clearMotion() { MOTIONS.clear(); }

  public static BarrelRenderer instance() {
    if (instance == null) instance = new BarrelRenderer();
    return instance;
  }

  @SubscribeEvent
  public static void models(ModelEvent.RegisterAdditional e) {
    e.register(SHELL);
    for (var model : STICKS) e.register(model);
  }

  private static final String[] ITEM_NAMES = {
    "fortune_barrel", "interpretation_shrine", "collection_chest"
  };

  @SubscribeEvent
  public static void guiModels(ModelEvent.RegisterAdditional e) {
    for (String name : ITEM_NAMES)
      e.register(ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID, "item/" + name + "_gui")));
    e.register(ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID, "item/fortune_slip_outline")));
  }

  @SubscribeEvent
  public static void baked(ModelEvent.ModifyBakingResult e) {
    for (String name : ITEM_NAMES) {
      var key =
          ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID, name));
      BakedModel original = e.getModels().get(key);
      BakedModel gui =
          e.getModels().get(ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID, "item/" + name + "_gui")));
      if (original == null || gui == null) continue;
      boolean barrel = name.equals("fortune_barrel");
      e.getModels()
          .put(
              key,
              new BakedModelWrapper<>(original) {
                @Override
                public boolean isCustomRenderer() {
                  return barrel;
                }

                @Override
                public boolean usesBlockLight() {
                  return false;
                }

                @Override
                public BakedModel applyTransform(
                    ItemDisplayContext context, PoseStack pose, boolean leftHand) {
                  if (context == ItemDisplayContext.GUI)
                    return gui.applyTransform(context, pose, leftHand);
                  original.applyTransform(context, pose, leftHand);
                  return this;
                }
              });
    }
  }

  private static boolean sameBarrel(ItemStack stack, String id) {
    return stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
        && cn.pixelmc.dailyfortunes.forge.ItemData.tag(stack).hasUUID("BarrelId")
        && cn.pixelmc.dailyfortunes.forge.ItemData.tag(stack).getUUID("BarrelId").toString().equals(id);
  }

  // Item.initializeClient runs during its superclass constructor; registration is unconditional.
  @Override
  public void renderByItem(
      ItemStack stack,
      ItemDisplayContext context,
      PoseStack pose,
      MultiBufferSource buffers,
      int light,
      int overlay) {
    Minecraft mc = Minecraft.getInstance();
    var renderer = mc.getItemRenderer();
    var consumer =
        ItemRenderer.getFoilBufferDirect(buffers, Sheets.cutoutBlockSheet(), true, stack.hasFoil());
    renderer.renderModelLists(
        mc.getModelManager().getModel(SHELL), stack, light, overlay, pose, consumer);
    boolean hand =
        context.firstPerson()
            || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    String key =
        stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) && cn.pixelmc.dailyfortunes.forge.ItemData.tag(stack).hasUUID("BarrelId")
            ? cn.pixelmc.dailyfortunes.forge.ItemData.tag(stack).getUUID("BarrelId").toString()
            : "";
    Motion motion = null;
    if (hand
        && mc.player != null
        && !key.isBlank()
        && (sameBarrel(mc.player.getMainHandItem(), key)
            || sameBarrel(mc.player.getOffhandItem(), key))) {
      motion = MOTIONS.computeIfAbsent(key, k -> new Motion());
      while (MOTIONS.size() > 128) MOTIONS.remove(MOTIONS.keySet().iterator().next());
      long now = System.nanoTime();
      double dt = motion.nanos == 0 ? 0 : (now - motion.nanos) / 1e9;
      motion.nanos = now;
      var player = mc.player;
      double vx = player.getX() - player.xo,
          vz = player.getZ() - player.zo,
          vy = player.getY() - player.yo;
      float yaw = player.getYRot(), pitch = player.getXRot();
      if (dt > .25) motion.sampled = false;
      if (dt > 0 && dt < .25 && !mc.isPaused()) {
        double angle = Math.toRadians(yaw), dx = (vx - motion.vx) * 20, dz = (vz - motion.vz) * 20;
        if (motion.sampled) {
          double impulseX =
              -(dx * Math.cos(angle) + dz * Math.sin(angle)) * .11
                  - Mth.wrapDegrees(yaw - motion.yaw) * .018;
          double impulseZ =
              -(dz * Math.cos(angle) - dx * Math.sin(angle)) * .11 - (pitch - motion.pitch) * .018;
          motion.physics.impulse(impulseX, impulseZ, -(vy - motion.vy) * 1.4);
        }
        motion.physics.advance(dt, 0, 0, 0);
      }
      motion.vx = vx;
      motion.vz = vz;
      motion.vy = vy;
      motion.yaw = yaw;
      motion.pitch = pitch;
      motion.sampled = true;
    }
    for (int i = 0; i < 7; i++) {
      pose.pushPose();
      if (motion != null) {
        var phys = motion.physics;
        double[] pivot = phys.profile.pivots[i];
        pose.translate(pivot[0], pivot[1] + phys.y[i], pivot[2]);
        pose.mulPose(Axis.XP.rotation((float) ((phys.z[i] - pivot[2]) * 3.5)));
        pose.mulPose(Axis.ZP.rotation((float) (-(phys.x[i] - pivot[0]) * 3.5)));
        pose.translate(-pivot[0], -pivot[1], -pivot[2]);
      }
      renderer.renderModelLists(
          mc.getModelManager().getModel(STICKS[i]), stack, light, overlay, pose, consumer);
      pose.popPose();
    }
  }
}
