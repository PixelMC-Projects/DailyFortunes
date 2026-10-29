package cn.pixelmc.dailyfortunes;

import cn.pixelmc.dailyfortunes.forge.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.*;
import net.minecraft.core.registries.BuiltInRegistries;

@Mod(DailyFortunes.ID)
public final class DailyFortunes {
  public static final String ID = "dailyfortunes";
  public static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(BuiltInRegistries.ITEM, ID);
  public static final DeferredRegister<Block> BLOCKS =
      DeferredRegister.create(BuiltInRegistries.BLOCK, ID);
  public static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID);
  public static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
  public static final java.util.function.Supplier<Item> BARREL =
      ITEMS.register("fortune_barrel", () -> new FortuneItem(FortuneItem.Kind.BARREL));
  public static final java.util.function.Supplier<Item> UNREAD =
      ITEMS.register("unread_slip", () -> new FortuneItem(FortuneItem.Kind.UNREAD));
  public static final java.util.function.Supplier<Item> SLIP =
      ITEMS.register("fortune_slip", () -> new FortuneItem(FortuneItem.Kind.SLIP));
  public static final java.util.function.Supplier<Block> SHRINE =
      BLOCKS.register("interpretation_shrine", () -> new FortuneBlock(false));
  public static final java.util.function.Supplier<Block> CHEST =
      BLOCKS.register("collection_chest", () -> new FortuneBlock(true));
  public static final java.util.function.Supplier<Item> SHRINE_ITEM =
      ITEMS.register(
          "interpretation_shrine", () -> new BlockItem(SHRINE.get(), new Item.Properties()));
  public static final java.util.function.Supplier<Item> CHEST_ITEM =
      ITEMS.register("collection_chest", () -> new BlockItem(CHEST.get(), new Item.Properties()));
  public static final java.util.function.Supplier<BlockEntityType<ChestEntity>> CHEST_ENTITY =
      ENTITIES.register(
          "collection_chest",
          () -> BlockEntityType.Builder.of(ChestEntity::new, CHEST.get()).build(null));

  public DailyFortunes(IEventBus bus) {
    ITEMS.register(bus);
    BLOCKS.register(bus);
    ENTITIES.register(bus);
    TABS.register(
        "dailyfortunes",
        () ->
            CreativeModeTab.builder()
                .title(net.minecraft.network.chat.Component.translatable("itemGroup.dailyfortunes"))
                .icon(() -> new ItemStack(BARREL.get()))
                .displayItems(
                    (p, o) -> {
                      o.accept(BARREL.get());
                      o.accept(SHRINE_ITEM.get());
                      o.accept(CHEST_ITEM.get());
                    })
                .build());
    TABS.register(bus);
    bus.addListener(FortuneNetwork::register);
  }
}
