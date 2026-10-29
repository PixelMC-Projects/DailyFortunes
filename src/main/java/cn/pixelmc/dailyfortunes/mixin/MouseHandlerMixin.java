package cn.pixelmc.dailyfortunes.mixin;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
 @Inject(method="onScroll",at=@At("HEAD"),cancellable=true)
 private void dailyfortunes$scroll(long window,double horizontal,double vertical,CallbackInfo ci){if(cn.pixelmc.dailyfortunes.client.FortuneClient.Overlay.scroll(vertical))ci.cancel();}
}
