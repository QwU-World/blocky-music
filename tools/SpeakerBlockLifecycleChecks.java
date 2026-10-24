import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import ru.gora.musicbattle.block.HorizontalSpeakerBlock;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.block.SpeakerPairing;

public final class SpeakerBlockLifecycleChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ((net.minecraft.core.MappedRegistry<Block>) BuiltInRegistries.BLOCK).unfreeze();
        verify(new SpeakerBlock(BlockBehaviour.Properties.of()), false);
        verify(new HorizontalSpeakerBlock(BlockBehaviour.Properties.of()), true);
        System.out.println("Speaker lifecycle checks PASS: " + checks);
    }

    private static void verify(SpeakerBlock block, boolean horizontal) throws Exception {
        var property = block.getStateDefinition().getProperty("extension");
        check(property instanceof BooleanProperty, "speaker exposes a two-block extension state");
        BooleanProperty extension = (BooleanProperty) property;
        BlockState primary = block.defaultBlockState().setValue(SpeakerBlock.FACING, Direction.NORTH);
        BlockState secondary = primary.setValue(extension, true);
        check(!primary.getValue(extension), "primary is the default part");
        check(block.newBlockEntity(BlockPos.ZERO, secondary) == null, "extension never creates a duplicate block entity");

        Method updateShape = SpeakerBlock.class.getDeclaredMethod("updateShape", BlockState.class, Direction.class,
                BlockState.class, net.minecraft.world.level.LevelAccessor.class, BlockPos.class, BlockPos.class);
        updateShape.setAccessible(true);
        Direction towardExtension = SpeakerPairing.extensionDirection(horizontal, Direction.NORTH);
        BlockState intact = (BlockState) updateShape.invoke(block, primary, towardExtension, secondary, null,
                BlockPos.ZERO, BlockPos.ZERO.relative(towardExtension));
        check(intact == primary, "valid pair remains intact");
        BlockState removed = (BlockState) updateShape.invoke(block, primary, towardExtension,
                Blocks.AIR.defaultBlockState(), null, BlockPos.ZERO, BlockPos.ZERO.relative(towardExtension));
        check(removed.isAir(), "primary disappears when its extension is removed");
        BlockState removedExtension = (BlockState) updateShape.invoke(block, secondary, towardExtension.getOpposite(),
                Blocks.AIR.defaultBlockState(), null, BlockPos.ZERO.relative(towardExtension), BlockPos.ZERO);
        check(removedExtension.isAir(), "extension disappears when its primary is removed");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
