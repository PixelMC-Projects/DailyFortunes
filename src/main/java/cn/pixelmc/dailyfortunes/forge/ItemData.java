package cn.pixelmc.dailyfortunes.forge;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
public final class ItemData {
 public static CompoundTag tag(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
 public static void update(ItemStack stack,java.util.function.Consumer<CompoundTag> action){CustomData.update(DataComponents.CUSTOM_DATA,stack,action);}
}
