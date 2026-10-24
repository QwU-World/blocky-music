package ru.gora.musicbattle.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.item.SpeakerWireItem;
import ru.gora.musicbattle.network.OpenSpeakerConfigPayload;
import ru.gora.musicbattle.registry.ModItems;

public class SpeakerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    public static final BooleanProperty EXTENSION = BooleanProperty.create("extension");
    private static final VoxelShape NORTH_SHAPE = Block.box(0.0D, 0.0D, 4.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 12.0D);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 12.0D, 16.0D, 16.0D);
    private static final VoxelShape WEST_SHAPE = Block.box(4.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private final boolean horizontal;

    public SpeakerBlock(Properties properties) {
        this(properties, false);
    }

    protected SpeakerBlock(Properties properties, boolean horizontal) {
        super(properties);
        this.horizontal = horizontal;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ACTIVE, false)
                .setValue(EXTENSION, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ACTIVE, false)
                .setValue(EXTENSION, false);
        BlockPos extensionPos = extensionPos(state, context.getClickedPos());
        if (context.getLevel().isOutsideBuildHeight(extensionPos)
                || !context.getLevel().getBlockState(extensionPos).canBeReplaced(context)) {
            return null;
        }
        return state;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        level.setBlock(extensionPos(state, pos), state.setValue(EXTENSION, true), Block.UPDATE_ALL);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(EXTENSION) ? null : new SpeakerBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return switch (facing) {
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        openSettings(level, primaryPos(state, pos), player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModItems.SPEAKER_WIRE.get())) {
            InteractionResult result = SpeakerWireItem.handleTarget(level, primaryPos(state, pos), player, stack);
            if (result == InteractionResult.FAIL) {
                return ItemInteractionResult.FAIL;
            }
            return result.consumesAction()
                    ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        openSettings(level, primaryPos(state, pos), player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void openSettings(Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            if (speaker.isDjControlled()) {
                BlockPos panelPos = speaker.getDjControllerPos();
                if (panelPos != null && level.hasChunkAt(panelPos)
                        && (!(level.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)
                        || !panel.getLinkedSpeakers().contains(pos))) {
                    // Recover an orphaned cable left by a panel destroyed while this
                    // speaker's chunk was unloaded, or replaced at the same position.
                    speaker.setDjController(null);
                }
            }
            if (speaker.isDjControlled()) {
                serverPlayer.displayClientMessage(Component.translatable(
                        "message.music_battle.speaker.dj_controlled")
                        .withStyle(ChatFormatting.YELLOW), true);
                return;
            }
            PacketDistributor.sendToPlayer(serverPlayer,
                    new OpenSpeakerConfigPayload(pos, speaker.getSoundRadius(), speaker.getVolume(),
                            speaker.getLowGainDb(), speaker.getMidGainDb(), speaker.getHighGainDb()));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            BlockPos panelPos = speaker.getDjControllerPos();
            if (panelPos != null && level.hasChunkAt(panelPos)
                    && level.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel) {
                panel.unlinkSpeaker(pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getValue(EXTENSION)) {
            BlockPos primaryPos = primaryPos(state, pos);
            BlockState primaryState = level.getBlockState(primaryPos);
            boolean matchingPrimary = primaryState.is(this)
                    && !primaryState.getValue(EXTENSION)
                    && primaryState.getValue(FACING) == state.getValue(FACING);
            if (matchingPrimary) {
                if (player.isCreative()) {
                    level.setBlock(primaryPos, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction extensionDirection = SpeakerPairing.extensionDirection(horizontal, state.getValue(FACING));
        boolean extension = state.getValue(EXTENSION);
        Direction counterpartDirection = extension ? extensionDirection.getOpposite() : extensionDirection;
        if (direction == counterpartDirection) {
            boolean matching = neighborState.is(this)
                    && neighborState.getValue(EXTENSION) != extension
                    && neighborState.getValue(FACING) == state.getValue(FACING);
            return matching ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    public void ensureExtension(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide || state.getValue(EXTENSION)) {
            return;
        }
        BlockPos extensionPos = extensionPos(state, pos);
        if (level.isOutsideBuildHeight(extensionPos)) {
            return;
        }
        BlockState existing = level.getBlockState(extensionPos);
        if (existing.is(this) && existing.getValue(EXTENSION)
                && existing.getValue(FACING) == state.getValue(FACING)) {
            return;
        }
        if (existing.canBeReplaced()) {
            level.setBlock(extensionPos, state.setValue(EXTENSION, true), Block.UPDATE_ALL);
        }
    }

    private BlockPos extensionPos(BlockState state, BlockPos pos) {
        return SpeakerPairing.extensionPos(horizontal, state.getValue(FACING), pos);
    }

    public BlockPos primaryPos(BlockState state, BlockPos pos) {
        return SpeakerPairing.primaryPos(horizontal, state.getValue(EXTENSION), state.getValue(FACING), pos);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE, EXTENSION);
    }
}
