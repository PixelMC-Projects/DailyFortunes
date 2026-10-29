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
  ClientPlayNetworking.registerGlobalReceiver(FortuneNetwork.Reply.TYPE,(p,c)->c.client().execute(()->FortuneClient.receive(p.typeName(),p.json())));
  BlockEntityRendererRegistry.register(DailyFortunes.CHEST_ENTITY.get(),FortuneClient.DisplayRenderer::new);
  BuiltinItemRendererRegistry.INSTANCE.register(DailyFortunes.BARREL.get(),(stack,context,pose,buffers,light,overlay)->BarrelRenderer.instance().renderByItem(stack,context,pose,buffers,light,overlay));
  ModelLoadingPlugin.register(context->{context.addModels(BarrelRenderer.MODELS.toArray(ResourceLocation[]::new));context.modifyModelAfterBake().register((model,c)->net.minecraft.client.resources.model.ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID,"fortune_barrel")).equals(c.topLevelId())?new BarrelModel(model):model);});
  ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener(){public ResourceLocation getFabricId(){return ResourceLocation.fromNamespaceAndPath(DailyFortunes.ID,"art");}public void onResourceManagerReload(ResourceManager manager){SlipShape.clear();ClientArtProfile.reload(manager);}});
  HudRenderCallback.EVENT.register((graphics,tickDelta)->FortuneClient.Overlay.render(graphics));
 }
 public static void send(String action,String value){ClientPlayNetworking.send(new FortuneNetwork.Request(action,value));}
}
