import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import ru.gora.musicbattle.block.DjPanelBlock;

/** Ensures the interaction/collision outline matches the thin authored DJ booth. */
public final class DjPanelShapeChecks {
    private static int checks;

    public static void main(String[] args) {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ((net.minecraft.core.MappedRegistry<Block>) BuiltInRegistries.BLOCK).unfreeze();
        DjPanelBlock block = new DjPanelBlock(BlockBehaviour.Properties.of());
        verify(block, Direction.NORTH, new AABB(0.0, 0.0, 2.0 / 16.0, 1.0, 1.0 / 16.0, 13.0 / 16.0));
        verify(block, Direction.SOUTH, new AABB(0.0, 0.0, 3.0 / 16.0, 1.0, 1.0 / 16.0, 14.0 / 16.0));
        verify(block, Direction.EAST, new AABB(3.0 / 16.0, 0.0, 0.0, 14.0 / 16.0, 1.0 / 16.0, 1.0));
        verify(block, Direction.WEST, new AABB(2.0 / 16.0, 0.0, 0.0, 13.0 / 16.0, 1.0 / 16.0, 1.0));
        System.out.println("DJ panel shape checks PASS: " + checks);
    }

    private static void verify(DjPanelBlock block, Direction facing, AABB expected) {
        BlockState state = block.defaultBlockState().setValue(DjPanelBlock.FACING, facing);
        List<AABB> boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).toAabbs();
        check(boxes.size() == 1, "DJ panel uses one authored outline for " + facing);
        AABB actual = boxes.getFirst();
        check(close(actual.minX, expected.minX) && close(actual.minY, expected.minY) && close(actual.minZ, expected.minZ)
                        && close(actual.maxX, expected.maxX) && close(actual.maxY, expected.maxY) && close(actual.maxZ, expected.maxZ),
                "DJ panel shape follows model for " + facing + ": " + actual + " != " + expected);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 0.000001; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
