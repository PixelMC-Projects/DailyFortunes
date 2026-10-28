package cn.pixelmc.dailyfortunes.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import com.mojang.logging.LogUtils;

/** Resource reload selects visual dimensions; it never selects or enables packs. */
public final class ClientArtProfile {
  private static volatile ArtProfile current = ArtProfile.DEFAULT;

  private ClientArtProfile() {}

  public static ArtProfile current() { return current; }

  public static void reload(ResourceManager manager) {
    ArtProfile next = ArtProfile.DEFAULT;
    try (var reader = manager.getResourceOrThrow(new ResourceLocation("dailyfortunes", "visuals/model_profile.json")).openAsReader()) {
      next = ArtProfile.read(reader);
    } catch (Exception error) {
      LogUtils.getLogger().warn("Invalid DailyFortunes art profile; using default dimensions", error);
    }
    current = next;
    BarrelRenderer.clearMotion();
  }
}
