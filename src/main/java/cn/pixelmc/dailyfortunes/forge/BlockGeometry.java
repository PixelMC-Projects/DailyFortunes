package cn.pixelmc.dailyfortunes.forge;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Fixed wooden-body envelopes, measured from the approved models in 1/16 block units. */
public final class BlockGeometry {
  /** Highest solid part of the cabinet lid; foliage and the floating paper do not affect it. */
  public static final double CHEST_TOP = 10.5 / 16.0;

  private static final Map<Direction, VoxelShape> SHRINE = rotations(ModelBounds.SHRINE);
  private static final Map<Direction, VoxelShape> CHEST = rotations(ModelBounds.CHEST);

  private BlockGeometry() {}

  public static VoxelShape shape(boolean chest, Direction facing) {
    return (chest ? CHEST : SHRINE).get(facing);
  }

  private static Map<Direction, VoxelShape> rotations(double[][] boxes) {
    Map<Direction, VoxelShape> result = new EnumMap<>(Direction.class);
    // The unrotated model faces SOUTH. This matches blockstates' 0/90/180/270 rotations,
    // including their NORTH=180 correction, rather than assuming NORTH is the model base.
    Direction[] directions = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    for (int turn = 0; turn < directions.length; turn++) {
      VoxelShape shape = Shapes.empty();
      for (double[] box : boxes) {
        double x0 = box[0], z0 = box[2], x1 = box[3], z1 = box[5];
        for (int i = 0; i < turn; i++) {
          double previousX0 = x0, previousX1 = x1;
          x0 = 16 - z1;
          x1 = 16 - z0;
          z0 = previousX0;
          z1 = previousX1;
        }
        shape =
            Shapes.or(
                shape, Shapes.box(x0 / 16, box[1] / 16, z0 / 16, x1 / 16, box[4] / 16, z1 / 16));
      }
      result.put(directions[turn], shape.optimize());
    }
    return Map.copyOf(result);
  }
}
