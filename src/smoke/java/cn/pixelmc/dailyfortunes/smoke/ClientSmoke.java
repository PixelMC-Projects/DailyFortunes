package cn.pixelmc.dailyfortunes.smoke;
@net.neoforged.fml.common.EventBusSubscriber(modid="dailyfortunes_smoke",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ClientSmoke {
 @net.neoforged.bus.api.SubscribeEvent public static void clientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){tick();}
 static void tick(){try{if(Boolean.getBoolean("dailyfortunes.capture")){var m=net.minecraft.client.Minecraft.getInstance();if(m.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)m.screen.onClose();if(m.screen instanceof net.minecraft.client.gui.screens.TitleScreen&&m.getOverlay()==null)m.stop();return;}if(ConnectProbe.tick())return;cn.pixelmc.dailyfortunes.smoke.visual.VisualProbe.tick();cn.pixelmc.dailyfortunes.smoke.visual.StyleInspection.onClientTick();}catch(Throwable e){e.printStackTrace();System.err.println("DAILYFORTUNES_CLIENT_SMOKE_FAIL");net.minecraft.client.Minecraft.getInstance().stop();throw new RuntimeException(e);}}
}
