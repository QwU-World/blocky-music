import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import ru.gora.musicbattle.block.SpeakerBlock;

public final class SpeakerShapeChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Method getShape = SpeakerBlock.class.getDeclaredMethod("getShape", BlockState.class,
                net.minecraft.world.level.BlockGetter.class, BlockPos.class, CollisionContext.class);
        getShape.setAccessible(true);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        var allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        SpeakerBlock upright = (SpeakerBlock) allocate.invoke(unsafe, SpeakerBlock.class);
        SpeakerBlock horizontal = (SpeakerBlock) allocate.invoke(unsafe, SpeakerBlock.class);
        var horizontalField = SpeakerBlock.class.getDeclaredField("horizontal");
        horizontalField.setAccessible(true);
        horizontalField.setBoolean(horizontal, true);
        verify(upright, false, getShape);
        verify(horizontal, true, getShape);
        verifyPairing();
        System.out.println("Speaker shape checks PASS: " + checks);
    }

    private static void verifyPairing() throws Exception {
        Class<?> pairing;
        try {
            pairing = Class.forName("ru.gora.musicbattle.block.SpeakerPairing");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Speaker pairing behavior is missing", missing);
        }
        Method extensionPos = pairing.getMethod("extensionPos", boolean.class, Direction.class, BlockPos.class);
        Method primaryPos = pairing.getMethod("primaryPos", boolean.class, boolean.class, Direction.class, BlockPos.class);
        BlockPos origin = new BlockPos(10, 20, 30);
        BlockPos verticalExtension = new BlockPos(10, 21, 30);
        check(extensionPos.invoke(null, false, Direction.NORTH, origin).equals(verticalExtension),
                "upright extension occupies the block above");
        check(primaryPos.invoke(null, false, true, Direction.SOUTH, verticalExtension).equals(origin),
                "upright extension routes back to the lower primary");
        Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        BlockPos[] expected = {
                new BlockPos(11,20,30), new BlockPos(10,20,31),
                new BlockPos(9,20,30), new BlockPos(10,20,29)
        };
        for (int i = 0; i < facings.length; i++) {
            BlockPos extension = (BlockPos) extensionPos.invoke(null, true, facings[i], origin);
            check(extension.equals(expected[i]), "horizontal extension follows the speaker's right side: " + facings[i]);
            check(primaryPos.invoke(null, true, true, facings[i], extension).equals(origin),
                    "horizontal extension routes back to primary: " + facings[i]);
            check(primaryPos.invoke(null, true, false, facings[i], origin).equals(origin),
                    "primary position remains stable: " + facings[i]);
        }
    }

    private static void verify(SpeakerBlock block, boolean horizontal, Method getShape) throws Exception {
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState state = Blocks.FURNACE.defaultBlockState().setValue(SpeakerBlock.FACING, facing);
            VoxelShape shape = (VoxelShape) getShape.invoke(block, state, null, BlockPos.ZERO, CollisionContext.empty());
            AABB actual = shape.bounds();
            double[] expected = expected(horizontal, facing);
            double[] values = {actual.minX, actual.minY, actual.minZ, actual.maxX, actual.maxY, actual.maxZ};
            for (int i = 0; i < values.length; i++) {
                check(Math.abs(values[i] - expected[i]) < 0.000001,
                        (horizontal ? "horizontal " : "upright ") + facing + " axis " + i);
            }
        }
    }

    private static double[] expected(boolean horizontal, Direction facing) {
        if (horizontal) {
            return switch (facing) {
                case NORTH -> new double[]{0,0,.25,1,1,1};
                case SOUTH -> new double[]{0,0,0,1,1,.75};
                case EAST -> new double[]{0,0,0,.75,1,1};
                case WEST -> new double[]{.25,0,0,1,1,1};
                default -> throw new AssertionError(facing);
            };
        }
        return switch (facing) {
            case NORTH -> new double[]{0,0,.25,1,1,1};
            case SOUTH -> new double[]{0,0,0,1,1,.75};
            case EAST -> new double[]{0,0,0,.75,1,1};
            case WEST -> new double[]{.25,0,0,1,1,1};
            default -> throw new AssertionError(facing);
        };
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
