package cn.pixelmc.dailyfortunes.smoke;
public final class ClientSmoke implements net.fabricmc.api.ClientModInitializer {
 public void onInitializeClient(){net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(m->tick());}
 static void tick(){try{if(Boolean.getBoolean("dailyfortunes.capture")){var m=net.minecraft.client.Minecraft.getInstance();if(m.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)m.screen.onClose();if(m.screen instanceof net.minecraft.client.gui.screens.TitleScreen&&m.getOverlay()==null)m.stop();return;}if(ConnectProbe.tick())return;cn.pixelmc.dailyfortunes.client.VisualProbe.tick();cn.pixelmc.dailyfortunes.client.StyleInspection.onClientTick();}catch(Throwable e){e.printStackTrace();System.err.println("DAILYFORTUNES_CLIENT_SMOKE_FAIL");net.minecraft.client.Minecraft.getInstance().stop();throw new RuntimeException(e);}}
}
