package cn.pixelmc.dailyfortunes.forge;
import cn.pixelmc.dailyfortunes.client.FortuneClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
public final class FortuneNetwork {
 public record Request(String action,String value) implements CustomPacketPayload {
  public static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("dailyfortunes","request"));
  public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.action,24);b.writeUtf(p.value,64);},b->new Request(b.readUtf(24),b.readUtf(64)));
  public Type<Request> type(){return TYPE;}
 }
 public record Reply(String typeName,String json) implements CustomPacketPayload {
  public static final Type<Reply> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("dailyfortunes","reply"));
  public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.typeName,24);b.writeUtf(p.json,262144);},b->new Reply(b.readUtf(24),b.readUtf(262144)));
  public Type<Reply> type(){return TYPE;}
 }
 public static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("1");
  r.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer sp)GameRuntime.request(sp,p.action,p.value);}));
  r.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->c.enqueueWork(()->FortuneClient.receive(p.typeName,p.json)));
 }
 public static void send(ServerPlayer p,String type,String json){if(p.connection!=null)PacketDistributor.sendToPlayer(p,new Reply(type,json));}
 public static void request(String action,String value){PacketDistributor.sendToServer(new Request(action,value));}
}
