package cn.pixelmc.dailyfortunes.forge;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class ChestEntity extends BlockEntity {
  public UUID owner;
  public String display = "";
  private String cachedDisplay;
  private JsonObject cachedDisplayData;

  public JsonObject displayData() {
    if (!display.equals(cachedDisplay)) {
      cachedDisplay = display;
      cachedDisplayData = null;
      if (!display.isBlank()) {
        try {
          cachedDisplayData = JsonParser.parseString(display).getAsJsonObject();
        } catch (RuntimeException invalid) {
          // Invalid saved data must not trigger parsing exceptions every frame.
        }
      }
    }
    return cachedDisplayData;
  }


  public ChestEntity(BlockPos p, BlockState s) {
    super(DailyFortunes.CHEST_ENTITY.get(), p, s);
  }

  protected void saveAdditional(CompoundTag t) {
    super.saveAdditional(t);
    if (owner != null) t.putUUID("Owner", owner);
    t.putString("Display", display);
  }

  public void load(CompoundTag t) {
    super.load(t);
    owner = t.hasUUID("Owner") ? t.getUUID("Owner") : null;
    display = t.getString("Display");
    if (display.length() > 16384) display = "";
  }

  public CompoundTag getUpdateTag() {
    return saveWithoutMetadata();
  }

  public ClientboundBlockEntityDataPacket getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }

  public void changed() {
    setChanged();
    if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
  }
}
