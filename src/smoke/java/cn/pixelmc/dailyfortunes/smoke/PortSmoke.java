package cn.pixelmc.dailyfortunes.smoke;
import cn.pixelmc.dailyfortunes.DailyFortunes;
import cn.pixelmc.dailyfortunes.forge.*;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.UUID;
@net.neoforged.fml.common.EventBusSubscriber(modid="dailyfortunes_smoke")
public final class PortSmoke {
 @net.neoforged.bus.api.SubscribeEvent public static void start(net.neoforged.neoforge.event.server.ServerStartedEvent e){run(e.getServer());}
 static int count;
 static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);count++;}
 public static void run(MinecraftServer server){ if(Boolean.getBoolean("dailyfortunes.requirePackaged")){String location=GameRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString();System.out.println("[DailyFortunes PackagedSmoke] source="+location);check(location.contains("dailyfortunes-neoforge-1.21.1-0.3.1.jar"),"formal main code must come from the release JAR");} server.execute(()->{try{for(String name:new String[]{"current"}){var f=GameRuntime.class.getDeclaredField(name);f.setAccessible(true);Object r=f.get(null);System.out.println("[Neo lifecycle diagnostic] current="+r+" config="+net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());if(r!=null)for(String field:new String[]{"remote","bridgeReady","backend","next","registered"}){var v=GameRuntime.class.getDeclaredField(field);v.setAccessible(true);System.out.println("[Neo lifecycle diagnostic] "+field+"="+v.get(r));}}}catch(Exception e){e.printStackTrace();}});
  try {
   for(String name:new String[]{"fortune_barrel","interpretation_shrine","collection_chest"}) check(server.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("dailyfortunes",name)).isPresent(),"recipe loaded: "+name);
   UUID owner=UUID.randomUUID();
   var record=FortuneBackend.obj("state","loose","player",owner.toString(),"date","2026-10-29","token",UUID.randomUUID().toString(),"owner","PortSmoke");
   ItemStack original=FortuneItem.stack(record);
   ItemStack restored=ItemStack.parse(server.registryAccess(),original.save(server.registryAccess())).orElseThrow();
   check(FortuneItem.receipt(restored).equals(record),"physical receipt persistent round trip");
   ItemStack barrel=new ItemStack(DailyFortunes.BARREL.get());
   ItemData.update(barrel,t->t.putUUID("BarrelId",owner));
   ItemStack restoredBarrel=ItemStack.parse(server.registryAccess(),barrel.save(server.registryAccess())).orElseThrow();
   check(ItemData.tag(restoredBarrel).getUUID("BarrelId").equals(owner),"barrel physics identity persistent round trip");
   ChestEntity chest=new ChestEntity(BlockPos.ZERO,DailyFortunes.CHEST.get().defaultBlockState());
   chest.owner=owner;chest.display=FortuneBackend.JSON.toJson(record);
   CompoundTag saved=chest.saveWithoutMetadata(server.registryAccess());
   ChestEntity loaded=new ChestEntity(BlockPos.ZERO,DailyFortunes.CHEST.get().defaultBlockState());
   loaded.loadWithComponents(saved,server.registryAccess());
   check(owner.equals(loaded.owner)&&chest.display.equals(loaded.display),"display owner/snapshot persistence");
   check(chest.getUpdateTag(server.registryAccess()).getString("Display").equals(chest.display),"display update tag sync");
   for(Direction d:Direction.Plane.HORIZONTAL)check(!BlockGeometry.shape(true,d).isEmpty()&&!BlockGeometry.shape(false,d).isEmpty(),"furniture collision "+d);
   var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),server.registryAccess());
   try{var request=new FortuneNetwork.Request("display","2026-10-29");FortuneNetwork.Request.CODEC.encode(buffer,request);check(FortuneNetwork.Request.CODEC.decode(buffer).equals(request),"C2S payload round trip");
   var reply=new FortuneNetwork.Reply("read",FortuneBackend.JSON.toJson(record));FortuneNetwork.Reply.CODEC.encode(buffer,reply);check(FortuneNetwork.Reply.CODEC.decode(buffer).equals(reply),"S2C payload round trip");}finally{buffer.release();}
   System.out.println("[DailyFortunes PortSmoke] PASS "+count+" runtime assertions: recipes, receipts, barrel identity, display NBT/sync, collision, payloads.");
  }catch(Throwable e){e.printStackTrace();System.err.println("[DailyFortunes PortSmoke] FAIL");server.halt(false);throw new RuntimeException(e);}
 }
}
