package cn.pixelmc.dailyfortunes.mixin;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
 @org.spongepowered.asm.mixin.Unique private static final ThreadLocal<Boolean> dailyfortunes$icon=ThreadLocal.withInitial(()->false);
 @Inject(method="render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",at=@At("HEAD"),cancellable=true)
 private void dailyfortunes$gui(ItemStack stack,ItemDisplayContext context,boolean left,PoseStack pose,MultiBufferSource buffers,int light,int overlay,BakedModel model,CallbackInfo ci){
  if(context!=ItemDisplayContext.GUI||dailyfortunes$icon.get())return;
  ResourceLocation id=BuiltInRegistries.ITEM.getKey(stack.getItem());
  if(!id.getNamespace().equals("dailyfortunes")||!java.util.Set.of("fortune_barrel","interpretation_shrine","collection_chest").contains(id.getPath()))return;
  BakedModel icon=net.minecraft.client.Minecraft.getInstance().getModelManager().getModel(new ResourceLocation(id.getNamespace(),"item/"+id.getPath()+"_gui"));
  dailyfortunes$icon.set(true);
  try{((ItemRenderer)(Object)this).render(stack,context,left,pose,buffers,light,overlay,icon);}finally{dailyfortunes$icon.set(false);}
  ci.cancel();
 }
}
