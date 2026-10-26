package cn.pixelmc.dailyfortunes.forge;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

public final class FortuneBlock extends BaseEntityBlock {
  public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
  private final boolean chest;

  public FortuneBlock(boolean chest) {
    super(Properties.of().strength(2.5F).sound(SoundType.WOOD).noOcclusion());
    this.chest = chest;
    registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
  }

  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
    b.add(FACING);
  }

  public BlockState getStateForPlacement(BlockPlaceContext c) {
    return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
  }

  public BlockState rotate(BlockState s, Rotation r) {
    return s.setValue(FACING, r.rotate(s.getValue(FACING)));
  }

  public BlockState mirror(BlockState s, Mirror m) {
    return s.rotate(m.getRotation(s.getValue(FACING)));
  }

  public RenderShape getRenderShape(BlockState s) {
    return RenderShape.MODEL;
  }

  public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
    return chest ? new ChestEntity(p, s) : null;
  }

  public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity by, ItemStack stack) {
    if (!l.isClientSide
        && chest
        && by instanceof Player
        && l.getBlockEntity(p) instanceof ChestEntity e) {
      e.owner = by.getUUID();
      e.changed();
    }
  }

  public InteractionResult use(
      BlockState s, Level l, BlockPos pos, Player p, InteractionHand hand, BlockHitResult hit) {
    if (!l.isClientSide && p instanceof ServerPlayer sp) {
      if (chest) GameRuntime.open(sp, pos);
      else GameRuntime.resolve(sp, p.getItemInHand(hand));
    }
    return InteractionResult.sidedSuccess(l.isClientSide);
  }
}
