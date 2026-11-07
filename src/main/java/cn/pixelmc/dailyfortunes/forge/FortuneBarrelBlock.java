package cn.pixelmc.dailyfortunes.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Static shared draw point. Qualifications and results belong to players, never the block. */
public final class FortuneBarrelBlock extends HorizontalDirectionalBlock {
  // Approved model's solid base, body, hoops and rim; sticks, leaves and cord are decorative.
  private static final VoxelShape BODY = Shapes.or(
      Block.box(2, 0, 2, 14, 1, 14),
      Block.box(3, 1, 3, 13, 11, 13),
      Block.box(2.5, 2, 2.5, 13.5, 3, 13.5),
      Block.box(2.5, 9, 2.5, 13.5, 10, 13.5),
      Block.box(2, 11, 2, 14, 12, 14)).optimize();

  public FortuneBarrelBlock() {
    super(Properties.of().strength(2.5F).sound(SoundType.WOOD).noOcclusion());
    registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
  }

  @Override
  public BlockState rotate(BlockState state, Rotation rotation) {
    return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
  }

  @Override
  public BlockState mirror(BlockState state, Mirror mirror) {
    return state.rotate(mirror.getRotation(state.getValue(FACING)));
  }

  @Override
  public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return BODY;
  }

  @Override
  public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return BODY;
  }

  @Override
  public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
      InteractionHand hand, BlockHitResult hit) {
    if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) GameRuntime.draw(serverPlayer);
    return InteractionResult.sidedSuccess(level.isClientSide);
  }
}
