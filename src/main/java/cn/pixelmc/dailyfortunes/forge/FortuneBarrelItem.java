package cn.pixelmc.dailyfortunes.forge;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** Held draw/physics stay intact; sneak placement uses the ordinary BlockItem checks. */
public final class FortuneBarrelItem extends BlockItem {
  public FortuneBarrelItem(Block block) {
    super(block, new Item.Properties().stacksTo(1));
  }

  @Override
  public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
    if (!level.isClientSide && !stack.getOrCreateTag().hasUUID("BarrelId"))
      stack.getOrCreateTag().putUUID("BarrelId", UUID.randomUUID());
  }

  @Override
  public void initializeClient(
      java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
    consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
      @Override
      public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
        return cn.pixelmc.dailyfortunes.client.BarrelRenderer.instance();
      }
    });
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    Player player = context.getPlayer();
    if (player == null) return InteractionResult.FAIL;
    // Return placement failures unchanged; never turn a failed placement into a draw.
    if (player.isShiftKeyDown()) return super.useOn(context);
    return use(context.getLevel(), player, context.getHand()).getResult();
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    ItemStack stack = player.getItemInHand(hand);
    // Also guards an air-use fallback after a rejected placement.
    if (player.isShiftKeyDown()) return InteractionResultHolder.fail(stack);
    if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) GameRuntime.draw(serverPlayer);
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
  }

  @Override
  public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.dailyfortunes.fortune_barrel.draw").withStyle(ChatFormatting.GRAY));
    lines.add(Component.translatable("tooltip.dailyfortunes.fortune_barrel.place").withStyle(ChatFormatting.GRAY));
  }
}
