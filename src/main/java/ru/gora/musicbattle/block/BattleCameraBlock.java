package ru.gora.musicbattle.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;
import ru.gora.musicbattle.item.SpeakerWireItem;
import ru.gora.musicbattle.registry.ModItems;
import ru.gora.musicbattle.server.CameraEditorManager;

public final class BattleCameraBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<BattleCameraBlock> CODEC = simpleCodec(BattleCameraBlock::new);

    public BattleCameraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BattleCameraBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof BattleCameraBlockEntity camera) {
            camera.markPlaced(level.getGameTime());
            if (!level.isClientSide) {
                level.sendBlockUpdated(pos, state, state, UPDATE_CLIENTS);
            }
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        openEditor(state, level, pos, player);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModItems.SPEAKER_WIRE.get())) {
            InteractionResult result = SpeakerWireItem.handleTarget(level, pos, player, stack);
            if (result == InteractionResult.FAIL) {
                return ItemInteractionResult.FAIL;
            }
            return result.consumesAction()
                    ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        openEditor(state, level, pos, player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void openEditor(BlockState state, Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof BattleCameraBlockEntity)) {
                blockEntity = new BattleCameraBlockEntity(pos, state);
                level.setBlockEntity(blockEntity);
            }
            CameraEditorManager.start(serverPlayer, pos, (BattleCameraBlockEntity) blockEntity);
        }
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
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }
}
