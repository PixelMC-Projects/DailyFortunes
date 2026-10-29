package cn.pixelmc.dailyfortunes.forge;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
public final class FortuneNetwork {
 public static final ResourceLocation REQUEST=new ResourceLocation("dailyfortunes","request"),REPLY=new ResourceLocation("dailyfortunes","reply");
 public static void register(){ServerPlayNetworking.registerGlobalReceiver(REQUEST,(server,player,handler,buf,response)->{
  String action=buf.readUtf(24),value=buf.readUtf(64);
  server.execute(()->GameRuntime.request(player,action,value));
 });}
 public static void send(ServerPlayer p,String type,String json){if(p.connection!=null){var b=PacketByteBufs.create();b.writeUtf(type,24);b.writeUtf(json,262144);ServerPlayNetworking.send(p,REPLY,b);}}
 public static void request(String action,String value){cn.pixelmc.dailyfortunes.client.FabricClient.send(action,value);}
}
