package cn.pixelmc.dailyfortunes.smoke;
import cn.pixelmc.dailyfortunes.DailyFortunes;
import cn.pixelmc.dailyfortunes.forge.*;
import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
@net.neoforged.fml.common.EventBusSubscriber(modid="dailyfortunes_smoke")
public final class FlowSmoke {
 private static int phase;private static long since;private static JsonObject drawn;private static String date;private static BlockPos chest;
 @net.neoforged.bus.api.SubscribeEvent public static void post(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){tick(e.getServer());}
 static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
 private static long count(ServerPlayer p,net.minecraft.world.item.Item item){long count=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))count+=p.getInventory().getItem(i).getCount();return count;}
 public static void tick(MinecraftServer server){
  if(!Boolean.getBoolean("dailyfortunes.flow")||phase==99||server.getPlayerList().getPlayers().isEmpty())return;
  ServerPlayer p=server.getPlayerList().getPlayers().get(0);long now=System.currentTimeMillis();if(since==0)since=now;
  try{
   if(now-since>70000)throw new AssertionError("game adapter phase timed out: "+phase);
   if(phase==0&&now-since>14000){ var field=GameRuntime.class.getDeclaredField("current");field.setAccessible(true);var runtime=field.get(null);System.out.println("[Neo joined diagnostic] current="+runtime);if(runtime!=null)for(String key:new String[]{"remote","backend","next","registered"}){var v=GameRuntime.class.getDeclaredField(key);v.setAccessible(true);System.out.println("[Neo joined diagnostic] "+key+"="+v.get(runtime));}
    p.getInventory().clearContent();p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(DailyFortunes.BARREL.get()));
    DailyFortunes.BARREL.get().use(p.level(),p,InteractionHand.MAIN_HAND);phase=1;since=now;
   }else if(phase==1&&count(p,DailyFortunes.UNREAD.get())==1){
    for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack item=p.getInventory().getItem(i);if(item.is(DailyFortunes.UNREAD.get())){drawn=FortuneItem.receipt(item);p.getInventory().setItem(i,ItemStack.EMPTY);p.setItemInHand(InteractionHand.OFF_HAND,item);break;}}
    check(drawn!=null,"draw grants a real receipt");date=s(drawn,"date");
    BlockPos shrine=p.blockPosition().offset(1,0,0);p.serverLevel().setBlockAndUpdate(shrine,DailyFortunes.SHRINE.get().defaultBlockState());
    var state=p.level().getBlockState(shrine);var hit=new BlockHitResult(Vec3.atCenterOf(shrine),Direction.UP,shrine,false);
    state.useItemOn(p.getOffhandItem(),p.level(),p,InteractionHand.OFF_HAND,hit);
    // A second immediate interaction must be serialized by the game adapter's busy guard.
    state.useItemOn(p.getOffhandItem(),p.level(),p,InteractionHand.OFF_HAND,hit);
    phase=2;since=now;
   }else if(phase==2&&count(p,DailyFortunes.SLIP.get())==1){
    check(count(p,DailyFortunes.UNREAD.get())==0,"resolve replaces unread token rather than duplicating it");
    var loose=FortuneItem.receipt(p.getOffhandItem());check(loose!=null&&s(loose,"token").equals(s(drawn,"token")),"off-hand interpretation preserves token");
    chest=p.blockPosition().offset(0,0,1);p.serverLevel().setBlockAndUpdate(chest,DailyFortunes.CHEST.get().defaultBlockState());
    var state=p.level().getBlockState(chest);var hit=new BlockHitResult(Vec3.atCenterOf(chest),Direction.UP,chest,false);
    state.useItemOn(p.getMainHandItem(),p.level(),p,InteractionHand.MAIN_HAND,hit);
    check(count(p,DailyFortunes.SLIP.get())==1,"physical slip retained while asynchronous archive is pending");
    phase=3;since=now;
   }else if(phase==3&&count(p,DailyFortunes.SLIP.get())==0){
    GameRuntime.request(p,"display",date);phase=4;since=now;
   }else if(phase==4&&p.level().getBlockEntity(chest) instanceof ChestEntity e&&!e.display.isBlank()){
    var display=e.displayData();check(display!=null&&s(display,"player").equals(p.getUUID().toString())&&s(display,"date").equals(date),"display snapshot belongs to the archived draw");
    check(count(p,DailyFortunes.SLIP.get())==0&&count(p,DailyFortunes.UNREAD.get())==0,"archive consumes exactly one receipt after commit");
    phase=99;System.out.println("[DailyFortunes FlowSmoke] PASS: real player "+p.getUUID()+" barrel use -> off-hand shrine -> single-token slip -> item-in-hand chest -> post-commit consumption -> display snapshot.");
   }
  }catch(Throwable e){phase=99;e.printStackTrace();System.err.println("[DailyFortunes FlowSmoke] FAIL");server.halt(false);throw new RuntimeException(e);}
 }
}
