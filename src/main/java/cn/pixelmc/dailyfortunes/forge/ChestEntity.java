package cn.pixelmc.dailyfortunes.forge;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ChestEntity extends BlockEntity {
  public UUID owner;
  public String display = "";

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
