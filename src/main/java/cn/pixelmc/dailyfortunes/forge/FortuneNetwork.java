package cn.pixelmc.dailyfortunes.forge;

import cn.pixelmc.dailyfortunes.client.FortuneClient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

public final class FortuneNetwork {
  private static final SimpleChannel CHANNEL =
      NetworkRegistry.newSimpleChannel(
          new ResourceLocation("dailyfortunes", "main"), () -> "1", "1"::equals, "1"::equals);

  public record Request(String action, String value) {}

  public record Reply(String type, String json) {}

  public static void register() {
    CHANNEL
        .messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
        .encoder(
            (p, b) -> {
              b.writeUtf(p.action, 24);
              b.writeUtf(p.value, 64);
            })
        .decoder(b -> new Request(b.readUtf(24), b.readUtf(64)))
        .consumerMainThread(
            (p, c) -> {
              ServerPlayer sp = c.get().getSender();
              if (sp != null) GameRuntime.request(sp, p.action, p.value);
              c.get().setPacketHandled(true);
            })
        .add();
    CHANNEL
        .messageBuilder(Reply.class, 1, NetworkDirection.PLAY_TO_CLIENT)
        .encoder(
            (p, b) -> {
              b.writeUtf(p.type, 24);
              b.writeUtf(p.json, 262144);
            })
        .decoder(b -> new Reply(b.readUtf(24), b.readUtf(262144)))
        .consumerMainThread(
            (p, c) -> {
              DistExecutor.unsafeRunWhenOn(
                  Dist.CLIENT, () -> () -> FortuneClient.receive(p.type, p.json));
              c.get().setPacketHandled(true);
            })
        .add();
  }

  public static void send(ServerPlayer p, String type, String json) {
    if (p.connection != null)
      CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Reply(type, json));
  }

  public static void request(String action, String value) {
    CHANNEL.sendToServer(new Request(action, value));
  }
}
