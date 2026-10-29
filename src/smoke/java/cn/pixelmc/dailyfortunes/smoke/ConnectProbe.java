package cn.pixelmc.dailyfortunes.smoke;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
public final class ConnectProbe {
 static boolean started,joined;static long at,entered;
 public static boolean tick(){String address=System.getProperty("dailyfortunes.connect");if(address==null)return false;var mc=Minecraft.getInstance();long now=System.currentTimeMillis();
  if(mc.screen instanceof AccessibilityOnboardingScreen){mc.screen.onClose();return true;}
  if(!started&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){started=true;at=now;ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("DailyFortunes isolated",address,ServerData.Type.OTHER),false,null);System.out.println("[DailyFortunes ConnectProbe] connecting "+address);}
  if(mc.player!=null&&mc.getConnection()!=null){if(!joined){joined=true;entered=now;System.out.println("[DailyFortunes ConnectProbe] JOIN "+mc.player.getUUID());}if(now-entered>45000){System.out.println("[DailyFortunes ConnectProbe] completed play wait");mc.stop();}}
  else if(joined){System.out.println("[DailyFortunes ConnectProbe] server disconnected after JOIN");mc.stop();}
  if(started&&now-at>140000){System.err.println("[DailyFortunes ConnectProbe] TIMEOUT");mc.stop();}
  return true;
 }
}
