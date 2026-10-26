package cn.pixelmc.dailyfortunes.client;

import java.io.DataInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Prebaked alpha hit mask. One bit lookup per candidate, no per-frame image/contour scan. */
public final class SlipShape {
  private static byte[] bits;
  private static int width, height;
  private static boolean attempted;

  public static void clear() {
    bits = null;
    attempted = false;
  }

  public static boolean opaque(double u, double v) {
    if (u < 0 || v < 0 || u >= 1 || v >= 1) return false;
    if (!attempted) {
      attempted = true;
      try (var stream =
              Minecraft.getInstance()
                  .getResourceManager()
                  .open(new ResourceLocation("dailyfortunes", "slip-mask.bin"));
          var data = new DataInputStream(stream)) {
        width = data.readUnsignedShort();
        height = data.readUnsignedShort();
        if (width < 1 || height < 1 || width > 4096 || height > 4096) return false;
        bits = data.readNBytes((width * height + 7) / 8);
        if (bits.length != (width * height + 7) / 8) bits = null;
      } catch (Exception ignored) {
        bits = null;
      }
    }
    if (bits == null) return false;
    int index = (int) (v * height) * width + (int) (u * width);
    return ((bits[index >>> 3] >>> (index & 7)) & 1) != 0;
  }
}
