package cn.pixelmc.dailyfortunes.forge;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Stable identity travels with a singleplayer save; creating a new world creates a new quota. */
public final class WorldFortuneId extends SavedData {
  private final UUID id;

  public WorldFortuneId() {
    id = UUID.randomUUID();
    setDirty();
  }

  private WorldFortuneId(UUID id) {
    this.id = id;
  }

  public static WorldFortuneId load(CompoundTag tag) {
    return tag.hasUUID("WorldId")
        ? new WorldFortuneId(tag.getUUID("WorldId"))
        : new WorldFortuneId();
  }

  public UUID id() {
    return id;
  }

  @Override
  public CompoundTag save(CompoundTag tag) {
    tag.putUUID("WorldId", id);
    return tag;
  }
}
