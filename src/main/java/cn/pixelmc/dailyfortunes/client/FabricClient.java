package cn.pixelmc.dailyfortunes.client;
import cn.pixelmc.dailyfortunes.DailyFortunes;
import cn.pixelmc.dailyfortunes.forge.FortuneNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.resource.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
public final class FabricClient implements ClientModInitializer {
 public void onInitializeClient(){
  ClientPlayNetworking.registerGlobalReceiver(FortuneNetwork.REPLY,(client,handler,buf,response)->{String type=buf.readUtf(24),json=buf.readUtf(262144);client.execute(()->FortuneClient.receive(type,json));});
  BlockEntityRendererRegistry.register(DailyFortunes.CHEST_ENTITY.get(),FortuneClient.DisplayRenderer::new);
  BuiltinItemRendererRegistry.INSTANCE.register(DailyFortunes.BARREL.get(),(stack,context,pose,buffers,light,overlay)->BarrelRenderer.instance().renderByItem(stack,context,pose,buffers,light,overlay));
  ModelLoadingPlugin.register(context->{context.addModels(BarrelRenderer.MODELS.toArray(ResourceLocation[]::new));context.modifyModelAfterBake().register((model,c)->c.id().equals(new net.minecraft.client.resources.model.ModelResourceLocation(DailyFortunes.ID,"fortune_barrel","inventory"))?new BarrelModel(model):model);});
  ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){public ResourceLocation getFabricId(){return new ResourceLocation(DailyFortunes.ID,"art");}public void onResourceManagerReload(ResourceManager manager){SlipShape.clear();ClientArtProfile.reload(manager);}});
  HudRenderCallback.EVENT.register((graphics,tickDelta)->FortuneClient.Overlay.render(graphics));
 }
 public static void send(String action,String value){var buf=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();buf.writeUtf(action,24);buf.writeUtf(value,64);ClientPlayNetworking.send(FortuneNetwork.REQUEST,buf);}
}
