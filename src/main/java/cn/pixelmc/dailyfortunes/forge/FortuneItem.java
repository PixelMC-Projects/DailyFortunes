package cn.pixelmc.dailyfortunes.forge;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import com.google.gson.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class FortuneItem extends Item {
  public enum Kind {
    BARREL,
    UNREAD,
    SLIP
  }

  private final Kind kind;

  public FortuneItem(Kind kind) {
    super(new Item.Properties().stacksTo(1));
    this.kind = kind;
  }

  public void inventoryTick(
      ItemStack stack,
      Level level,
      net.minecraft.world.entity.Entity entity,
      int slot,
      boolean selected) {
    if (kind == Kind.BARREL && !level.isClientSide && !ItemData.tag(stack).hasUUID("BarrelId"))
      ItemData.update(stack,t->t.putUUID("BarrelId",UUID.randomUUID()));
  }

  public void initializeClient(
      java.util.function.Consumer<net.neoforged.neoforge.client.extensions.common.IClientItemExtensions>
          consumer) {
    consumer.accept(
        new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() {
          public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return cn.pixelmc.dailyfortunes.client.BarrelRenderer.instance();
          }
        });
  }

  public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
    ItemStack stack = p.getItemInHand(hand);
    if (!level.isClientSide && p instanceof ServerPlayer sp) {
      if (kind == Kind.BARREL) GameRuntime.draw(sp);
      else if (kind == Kind.SLIP) GameRuntime.read(sp, stack);
      else sp.sendSystemMessage(Component.literal("请将未解之签交给解签龛。").withStyle(ChatFormatting.GRAY));
    }
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
  }

  public static JsonObject receipt(ItemStack s) {
    try {
      if (!s.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return null;
      String json = ItemData.tag(s).getString("DailyFortunes");
      if (json.length() > 16384) return null;
      return JsonParser.parseString(json).getAsJsonObject();
    } catch (Exception e) {
      return null;
    }
  }

  public static ItemStack stack(JsonObject r) {
    ItemStack item =
        new ItemStack(
            s(r, "state").equals("unread") ? DailyFortunes.UNREAD.get() : DailyFortunes.SLIP.get());
    ItemData.update(item,t->t.putString("DailyFortunes",JSON.toJson(r)));
    return item;
  }

  public static int gradeColor(String grade) {
    return switch (grade) {
      case "上上签" -> 0xD7B765;
      case "上吉签" -> 0x89BAA0;
      case "中平签" -> 0x8AABC0;
      case "小凶签" -> 0xD8A16D;
      default -> 0xC97C7D;
    };
  }

  public static Component heading(JsonObject r) {
    JsonObject f = r.getAsJsonObject("snapshot");
    return Component.literal("第" + cn(i(f, "id")) + "签 · " + s(f, "grade"))
        .withStyle(st -> st.withColor(gradeColor(s(f, "grade"))));
  }

  public static String cn(int n) {
    String[] a = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    if (n < 10) return a[Math.max(n, 0)];
    if (n < 100) return (n / 10 == 1 ? "" : a[n / 10]) + "十" + (n % 10 == 0 ? "" : a[n % 10]);
    return String.valueOf(n);
  }

  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
    JsonObject r = receipt(stack);
    if (r == null) {
      if (kind == Kind.BARREL)
        lines.add(Component.literal("每日一次，右键问签").withStyle(ChatFormatting.GRAY));
      return;
    }
    if (kind == Kind.UNREAD)
      lines.add(Component.literal("尚未解读 · 请前往解签龛").withStyle(ChatFormatting.GRAY));
    else {
      JsonObject f = r.getAsJsonObject("snapshot");
      if (f == null) return;
      lines.add(heading(r));
      for (JsonElement e : f.getAsJsonArray("poem"))
        lines.add(Component.literal(e.getAsString()).withStyle(ChatFormatting.GRAY));
      String interpretation = s(f, "interpretation");
      for (int start = 0; start < interpretation.length(); start += 24)
        lines.add(
            Component.literal(
                    interpretation.substring(start, Math.min(start + 24, interpretation.length())))
                .withStyle(ChatFormatting.GRAY));
    }
    lines.add(
        Component.literal(s(r, "owner"))
            .withStyle(ChatFormatting.YELLOW)
            .append(
                Component.literal(" 所得    " + s(r, "date").replace('-', '/'))
                    .withStyle(ChatFormatting.GOLD)));
  }
}
