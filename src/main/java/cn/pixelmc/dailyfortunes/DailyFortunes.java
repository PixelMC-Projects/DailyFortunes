package cn.pixelmc.dailyfortunes;

import cn.pixelmc.dailyfortunes.forge.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(DailyFortunes.ID)
public final class DailyFortunes {
  public static final String ID = "dailyfortunes";
  public static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(ForgeRegistries.ITEMS, ID);
  public static final DeferredRegister<Block> BLOCKS =
      DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
  public static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ID);
  public static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
  public static final RegistryObject<Item> BARREL =
      ITEMS.register("fortune_barrel", () -> new FortuneItem(FortuneItem.Kind.BARREL));
  public static final RegistryObject<Item> UNREAD =
      ITEMS.register("unread_slip", () -> new FortuneItem(FortuneItem.Kind.UNREAD));
  public static final RegistryObject<Item> SLIP =
      ITEMS.register("fortune_slip", () -> new FortuneItem(FortuneItem.Kind.SLIP));
  public static final RegistryObject<Block> SHRINE =
      BLOCKS.register("interpretation_shrine", () -> new FortuneBlock(false));
  public static final RegistryObject<Block> CHEST =
      BLOCKS.register("collection_chest", () -> new FortuneBlock(true));
  public static final RegistryObject<Item> SHRINE_ITEM =
      ITEMS.register(
          "interpretation_shrine", () -> new BlockItem(SHRINE.get(), new Item.Properties()));
  public static final RegistryObject<Item> CHEST_ITEM =
      ITEMS.register("collection_chest", () -> new BlockItem(CHEST.get(), new Item.Properties()));
  public static final RegistryObject<BlockEntityType<ChestEntity>> CHEST_ENTITY =
      ENTITIES.register(
          "collection_chest",
          () -> BlockEntityType.Builder.of(ChestEntity::new, CHEST.get()).build(null));

  public DailyFortunes() {
    IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
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
    FortuneNetwork.register();
  }
}
