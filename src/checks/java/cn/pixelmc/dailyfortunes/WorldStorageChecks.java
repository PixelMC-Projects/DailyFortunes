package cn.pixelmc.dailyfortunes;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.forge.WorldFortuneId;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public final class WorldStorageChecks {
  public static void main(String[] args) throws Exception {
    WorldFortuneId first = new WorldFortuneId(), second = new WorldFortuneId();
    if (first.id().equals(second.id()))
      throw new AssertionError("New saves must have different identities");
    if (!WorldFortuneId.load(first.save(new CompoundTag())).id().equals(first.id()))
      throw new AssertionError("Reloading or moving a save must preserve identity");
    Path root = Path.of("build/checks/world-isolation-" + UUID.randomUUID()).toAbsolutePath();
    UUID player = UUID.randomUUID();
    for (WorldFortuneId world : new WorldFortuneId[] {first, second}) {
      Path data = root.resolve(world.id().toString());
      String url =
          "jdbc:h2:file:"
              + data.resolve("fortunes").toString().replace('\\', '/')
              + ";MODE=MySQL;NON_KEYWORDS=DAY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
      try (FortuneBackend backend = new FortuneBackend(url, "sa", "", data)) {
        ServiceChecks.rpc(backend, player, "login", "WorldIsolation");
        if (!ServiceChecks.rpc(backend, player, "draw").has("record"))
          throw new AssertionError("Same player must draw once in each save");
      }
      try (FortuneBackend reopened = new FortuneBackend(url, "sa", "", data)) {
        boolean refused = false;
        try {
          ServiceChecks.rpc(reopened, player, "draw");
        } catch (IllegalArgumentException expected) {
          refused = true;
        }
        if (!refused)
          throw new AssertionError("Reopening the same save must not reset its daily quota");
      }
    }
    System.out.println("World identity, persistence and independent daily quotas passed.");
  }
}
