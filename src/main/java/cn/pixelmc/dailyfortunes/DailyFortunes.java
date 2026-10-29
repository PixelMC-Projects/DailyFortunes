package cn.pixelmc.dailyfortunes;
import cn.pixelmc.dailyfortunes.forge.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.function.Supplier;
public final class DailyFortunes implements ModInitializer {
 public static final String ID="dailyfortunes";
 public record Entry<T>(T value) implements Supplier<T> { public T get(){return value;} }
 private static <T,V extends T> Entry<V> add(Registry<T> registry,String id,V value){ Registry.register(registry,ResourceLocation.fromNamespaceAndPath(ID,id),value);return new Entry<>(value); }
 public static final Entry<Item> BARREL=add(BuiltInRegistries.ITEM,"fortune_barrel",new FortuneItem(FortuneItem.Kind.BARREL));
 public static final Entry<Item> UNREAD=add(BuiltInRegistries.ITEM,"unread_slip",new FortuneItem(FortuneItem.Kind.UNREAD));
 public static final Entry<Item> SLIP=add(BuiltInRegistries.ITEM,"fortune_slip",new FortuneItem(FortuneItem.Kind.SLIP));
 public static final Entry<Block> SHRINE=add(BuiltInRegistries.BLOCK,"interpretation_shrine",new FortuneBlock(false));
 public static final Entry<Block> CHEST=add(BuiltInRegistries.BLOCK,"collection_chest",new FortuneBlock(true));
 public static final Entry<Item> SHRINE_ITEM=add(BuiltInRegistries.ITEM,"interpretation_shrine",new BlockItem(SHRINE.get(),new Item.Properties()));
 public static final Entry<Item> CHEST_ITEM=add(BuiltInRegistries.ITEM,"collection_chest",new BlockItem(CHEST.get(),new Item.Properties()));
 public static final Entry<BlockEntityType<ChestEntity>> CHEST_ENTITY=add(BuiltInRegistries.BLOCK_ENTITY_TYPE,"collection_chest",BlockEntityType.Builder.of(ChestEntity::new,CHEST.get()).build(null));
 public void onInitialize(){
  Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,ResourceLocation.fromNamespaceAndPath(ID,ID),FabricItemGroup.builder().title(net.minecraft.network.chat.Component.translatable("itemGroup.dailyfortunes")).icon(()->new ItemStack(BARREL.get())).displayItems((p,o)->{o.accept(BARREL.get());o.accept(SHRINE_ITEM.get());o.accept(CHEST_ITEM.get());}).build());
  FortuneNetwork.register();GameRuntime.register();
 }
}
