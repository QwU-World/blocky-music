package ru.gora.musicbattle.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;

/** Batch cable tool used to bind speakers and battle cameras to a DJ panel. */
public final class SpeakerWireItem extends Item {
    private static final double MAX_CABLE_DISTANCE_SQUARED = 64.0D * 64.0D;

    public SpeakerWireItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = handleTarget(
                context.getLevel(), context.getClickedPos(), context.getPlayer(), context.getItemInHand());
        Player player = context.getPlayer();
        if (result == InteractionResult.PASS && player != null
                && SpeakerWireSelection.shouldConsumeIgnoredTarget(
                        player.isShiftKeyDown(), SpeakerWireSelection.load(customData(context.getItemInHand())))) {
            return InteractionResult.CONSUME;
        }
        return result;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                   InteractionHand hand) {
        if (SpeakerWireSelection.shouldConsumeIgnoredTarget(
                player.isShiftKeyDown(), SpeakerWireSelection.load(customData(stack)))) {
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack wire = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(wire);
        }
        SpeakerWireSelection.Selection selection = SpeakerWireSelection.load(customData(wire));
        if (selection.isEmpty()) {
            return InteractionResultHolder.pass(wire);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            saveSelection(wire, SpeakerWireSelection.empty());
            serverPlayer.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.selection_cleared")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResultHolder.sidedSuccess(wire, level.isClientSide);
    }

    public static InteractionResult handleTarget(Level level, BlockPos targetPos, Player player, ItemStack wire) {
        if (player == null) {
            return InteractionResult.PASS;
        }
        targetPos = primarySpeakerPos(level, targetPos);
        boolean panelTarget = level.getBlockEntity(targetPos) instanceof DjPanelBlockEntity;
        boolean speakerTarget = level.getBlockEntity(targetPos) instanceof SpeakerBlockEntity;
        boolean cameraTarget = level.getBlockEntity(targetPos) instanceof BattleCameraBlockEntity;
        if (!panelTarget && !speakerTarget && !cameraTarget) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                || !level.hasChunkAt(targetPos)) {
            return InteractionResult.FAIL;
        }

        String currentDimension = serverLevel.dimension().location().toString();
        SpeakerWireSelection.Selection selection = SpeakerWireSelection.load(customData(wire));
        if (speakerTarget && player.isShiftKeyDown()) {
            unlinkSpeaker(serverLevel, targetPos, serverPlayer);
            saveSelection(wire, selection.withoutRemote(SpeakerWireSelection.Kind.SPEAKER, targetPos));
            return InteractionResult.SUCCESS;
        }
        if (cameraTarget && player.isShiftKeyDown()) {
            unlinkCamera(serverLevel, targetPos, serverPlayer, selection);
            saveSelection(wire, selection.withoutRemote(SpeakerWireSelection.Kind.CAMERA, targetPos));
            return InteractionResult.SUCCESS;
        }

        if (selection.hasPanel() && !currentDimension.equals(selection.dimension())) {
            saveSelection(wire, SpeakerWireSelection.empty());
            serverPlayer.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.cross_dimension")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        if (panelTarget) {
            if (selection.hasRemotes()) {
                if (!currentDimension.equals(selection.dimension())) {
                    saveSelection(wire, SpeakerWireSelection.empty());
                    serverPlayer.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.cross_dimension")
                            .withStyle(ChatFormatting.RED), true);
                    return InteractionResult.FAIL;
                }
                DjPanelBlockEntity panel = (DjPanelBlockEntity) serverLevel.getBlockEntity(targetPos);
                BatchResult result = connectBatch(serverLevel, targetPos, panel, selection);
                saveSelection(wire, SpeakerWireSelection.empty());
                serverPlayer.displayClientMessage(Component.translatable(
                                "message.music_battle.speaker_wire.batch_result", result.connected(), result.skipped())
                        .withStyle(result.connected() > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
                return InteractionResult.SUCCESS;
            }
            SpeakerWireSelection.Selection panelSelection = selection.withPanel(currentDimension, targetPos);
            saveSelection(wire, panelSelection);
            serverPlayer.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.panel_selected")
                    .withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.SUCCESS;
        }

        SpeakerWireSelection.Kind remoteKind = speakerTarget
                ? SpeakerWireSelection.Kind.SPEAKER : SpeakerWireSelection.Kind.CAMERA;
        if (selection.hasPanel()) {
            BlockPos panelPos = selection.panel();
            if (!serverLevel.hasChunkAt(panelPos)
                    || !(serverLevel.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
                saveSelection(wire, SpeakerWireSelection.empty());
                serverPlayer.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.endpoint_missing")
                        .withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            if (!connectRemote(serverLevel, panelPos, panel, targetPos, remoteKind)) {
                serverPlayer.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.connect_failed")
                        .withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            serverPlayer.displayClientMessage(Component.translatable(remoteKind == SpeakerWireSelection.Kind.SPEAKER
                            ? "message.music_battle.speaker_wire.linked"
                            : "message.music_battle.speaker_wire.camera_linked")
                    .withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.SUCCESS;
        }

        SpeakerWireSelection.Toggle toggle = selection.toggleRemote(remoteKind, currentDimension, targetPos);
        if (toggle.change() == SpeakerWireSelection.Change.LIMIT) {
            int limit = remoteKind == SpeakerWireSelection.Kind.SPEAKER
                    ? SpeakerWireSelection.MAX_PENDING_SPEAKERS : SpeakerWireSelection.MAX_PENDING_CAMERAS;
            serverPlayer.displayClientMessage(Component.translatable(
                            "message.music_battle.speaker_wire.selection_limit", limit)
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        saveSelection(wire, toggle.selection());
        String key = toggle.change() == SpeakerWireSelection.Change.REMOVED
                ? "message.music_battle.speaker_wire.selection_removed"
                : "message.music_battle.speaker_wire.selection_added";
        serverPlayer.displayClientMessage(Component.translatable(key,
                        toggle.selection().speakerCount(), toggle.selection().cameraCount())
                .withStyle(toggle.change() == SpeakerWireSelection.Change.REMOVED
                        ? ChatFormatting.YELLOW : ChatFormatting.AQUA), true);
        return InteractionResult.SUCCESS;
    }

    private static BatchResult connectBatch(ServerLevel level, BlockPos panelPos, DjPanelBlockEntity panel,
                                            SpeakerWireSelection.Selection selection) {
        int connected = 0;
        int skipped = 0;
        for (BlockPos speakerPos : selection.speakers()) {
            if (connectRemote(level, panelPos, panel, speakerPos, SpeakerWireSelection.Kind.SPEAKER)) connected++;
            else skipped++;
        }
        for (BlockPos cameraPos : selection.cameras()) {
            if (connectRemote(level, panelPos, panel, cameraPos, SpeakerWireSelection.Kind.CAMERA)) connected++;
            else skipped++;
        }
        return new BatchResult(connected, skipped);
    }

    private static boolean connectRemote(ServerLevel level, BlockPos panelPos, DjPanelBlockEntity panel,
                                         BlockPos remotePos, SpeakerWireSelection.Kind kind) {
        if (panelPos.distSqr(remotePos) > MAX_CABLE_DISTANCE_SQUARED || !level.hasChunkAt(remotePos)) {
            return false;
        }
        if (kind == SpeakerWireSelection.Kind.CAMERA) {
            return level.getBlockEntity(remotePos) instanceof BattleCameraBlockEntity && panel.linkCamera(remotePos);
        }
        if (!(level.getBlockEntity(remotePos) instanceof SpeakerBlockEntity speaker)) {
            return false;
        }
        if (!panel.getLinkedSpeakers().contains(remotePos) && !panel.linkSpeaker(remotePos)) {
            return false;
        }
        BlockPos previousPanelPos = speaker.getDjControllerPos();
        if (previousPanelPos != null && !previousPanelPos.equals(panelPos)
                && level.hasChunkAt(previousPanelPos)
                && level.getBlockEntity(previousPanelPos) instanceof DjPanelBlockEntity previousPanel) {
            previousPanel.unlinkSpeaker(remotePos);
        }
        speaker.setDjController(panelPos);
        return true;
    }

    private record BatchResult(int connected, int skipped) {
    }

    private static BlockPos primarySpeakerPos(Level level, BlockPos clickedPos) {
        BlockState clickedState = level.getBlockState(clickedPos);
        if (!(clickedState.getBlock() instanceof SpeakerBlock speakerBlock)
                || !clickedState.getValue(SpeakerBlock.EXTENSION)) {
            return clickedPos;
        }
        BlockPos primaryPos = speakerBlock.primaryPos(clickedState, clickedPos);
        BlockState primaryState = level.getBlockState(primaryPos);
        return primaryState.is(speakerBlock)
                && !primaryState.getValue(SpeakerBlock.EXTENSION)
                && primaryState.getValue(SpeakerBlock.FACING) == clickedState.getValue(SpeakerBlock.FACING)
                ? primaryPos : clickedPos;
    }

    private static void unlinkCamera(ServerLevel level, BlockPos cameraPos, ServerPlayer player,
                                     SpeakerWireSelection.Selection selection) {
        if (!selection.hasPanel()
                || !level.dimension().location().toString().equals(selection.dimension())) {
            player.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.camera_unlink_select_panel")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        BlockPos panelPos = selection.panel();
        if (panelPos.distSqr(cameraPos) > MAX_CABLE_DISTANCE_SQUARED
                || !level.hasChunkAt(panelPos)
                || !(level.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)
                || !panel.unlinkCamera(cameraPos)) {
            player.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.camera_not_linked")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        player.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.camera_unlinked")
                .withStyle(ChatFormatting.YELLOW), true);
    }

    private static void unlinkSpeaker(ServerLevel level, BlockPos speakerPos, ServerPlayer player) {
        if (!(level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
            return;
        }
        BlockPos panelPos = speaker.getDjControllerPos();
        if (panelPos == null) {
            player.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.not_linked")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (level.hasChunkAt(panelPos) && level.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel) {
            panel.unlinkSpeaker(speakerPos);
        }
        speaker.setDjController(null);
        player.displayClientMessage(Component.translatable("message.music_battle.speaker_wire.unlinked")
                .withStyle(ChatFormatting.YELLOW), true);
    }

    private static void saveSelection(ItemStack stack, SpeakerWireSelection.Selection selection) {
        CompoundTag tag = customData(stack);
        SpeakerWireSelection.save(tag, selection);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        SpeakerWireSelection.Selection selection = SpeakerWireSelection.load(customData(stack));
        if (selection.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.music_battle.speaker_wire.select").withStyle(ChatFormatting.GRAY));
        } else if (selection.hasPanel()) {
            tooltip.add(Component.translatable("tooltip.music_battle.speaker_wire.panel_selected",
                            selection.panel().toShortString())
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("tooltip.music_battle.speaker_wire.panel_mode")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.music_battle.speaker_wire.selection_counts",
                            selection.speakerCount(), selection.cameraCount()).withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("tooltip.music_battle.speaker_wire.commit")
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.music_battle.speaker_wire.clear")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
