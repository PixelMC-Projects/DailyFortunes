package cn.pixelmc.dailyfortunes.forge;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Fixed wooden-body envelopes, measured from the approved models in 1/16 block units. */
public final class BlockGeometry {
  /** Highest solid part of the cabinet lid; foliage and the floating paper do not affect it. */
  public static final double CHEST_TOP = 9.61 / 16.0;

  private static final Map<Direction, VoxelShape> SHRINE =
      rotations(
          new double[][] {
            // Four feet, plinth and its narrower upper step.
            {2.2, 0, 3.2, 4.2, 1.1, 5.2},
            {2.2, 0, 10.4, 4.2, 1.1, 12.4},
            {11.8, 0, 3.2, 13.8, 1.1, 5.2},
            {11.8, 0, 10.4, 13.8, 1.1, 12.4},
            {1.65, .8, 2.65, 14.35, 1.61, 13.45},
            {2, 1.61, 3, 14, 2.15, 13.15},
            // Cabinet sides/rear and front panel; the open mouth and tray stay open.
            {2.3, 2.1, 4.1, 3.35, 11.7, 12.8},
            {12.65, 2.1, 4.1, 13.7, 11.7, 12.8},
            {2.8, 2.1, 3.2, 13.2, 11.6, 4.1},
            {3.35, 4.75, 11.15, 12.65, 8.75, 12.8},
            {3.35, 10.94, 11.9, 12.65, 11.7, 12.48},
            {3.4, 8.92, 10.2, 8.2, 9.16, 12.82},
            {3.2, 2.15, 4.18, 12.8, 2.64, 14.8},
            {3.2, 2.5, 14.35, 12.8, 3.1, 14.9},
            {3.2, 2.5, 10.2, 3.6, 3.55, 14.8},
            {12.4, 2.5, 10.2, 12.8, 3.55, 14.8},
            // The load-bearing beam ring below the roof.
            {1.375, 11.5, 12.725, 14.625, 12.08, 13.525},
            {1.375, 11.5, 2.575, 14.625, 12.08, 3.375},
            {1.375, 11.5, 3.325, 2.175, 12.08, 12.775},
            {13.825, 11.5, 3.325, 14.625, 12.08, 12.775},
            // Separate slope bands follow the tiled roof instead of enclosing its empty corners.
            {1.5, 12.62, 2, 2.845, 13.39, 14},
            {2.8, 12.78, 2, 4.145, 13.55, 14},
            {4.1, 13.26, 2, 5.445, 14.03, 14},
            {5.4, 13.74, 2, 6.745, 14.51, 14},
            {6.7, 14.22, 2, 8.045, 14.99, 14},
            {8, 14.22, 2, 9.345, 14.99, 14},
            {9.3, 13.74, 2, 10.645, 14.51, 14},
            {10.6, 13.26, 2, 11.945, 14.03, 14},
            {11.9, 12.78, 2, 13.245, 13.55, 14},
            {13.2, 12.62, 2, 14.545, 13.39, 14},
            {0.85, 13.05, 1.85, 1.55, 13.65, 14.15},
            {14.45, 13.05, 1.85, 15.15, 13.65, 14.15},
            {7.55, 14.85, 1.7, 8.45, 15.7, 14.3}
          });

  private static final Map<Direction, VoxelShape> CHEST =
      rotations(
          new double[][] {
            {2.1, 0, 3.05, 3.95, .85, 4.7},
            {2.1, 0, 11.2, 3.95, .85, 12.85},
            {12.05, 0, 3.05, 13.9, .85, 4.7},
            {12.05, 0, 11.2, 13.9, .85, 12.85},
            {1.96, .65, 2.93, 4.09, 1.22, 4.82},
            {1.96, .65, 11.08, 4.09, 1.22, 12.97},
            {11.91, .65, 2.93, 14.04, 1.22, 4.82},
            {11.91, .65, 11.08, 14.04, 1.22, 12.97},
            {1.55, 1.1, 2.5, 14.45, 1.66, 13.65},
            {1.85, 1.66, 2.8, 14.15, 2.02, 13.35},
            {2.115, 2, 3.115, 13.885, 8.57, 13.2},
            // Only the opened right drawer protrudes, and only at its own height.
            {8.03, 5.19, 12.7, 12.89, 7.99, 14.66},
            {1.58, 8.57, 2.51, 14.42, 9.06, 13.61},
            {1.76, 9.06, 2.69, 14.24, 9.26, 13.43},
            {2.02, 9.26, 2.95, 13.98, CHEST_TOP * 16, 13.17}
          });

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
