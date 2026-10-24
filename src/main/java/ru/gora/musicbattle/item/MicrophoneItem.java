package ru.gora.musicbattle.item;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import ru.gora.musicbattle.registry.ModItems;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.server.PlayerBattleStats;

public final class MicrophoneItem extends Item {
    private static final String OWNER_UUID = "blocky_music_owner_uuid";
    private static final String OWNER_NAME = "blocky_music_owner_name";
    private static final String WINS = "blocky_music_stats_wins";
    private static final String FULL_COMBO_WINS = "blocky_music_stats_full_combo_wins";
    private static final String LOSSES = "blocky_music_stats_losses";
    private static final String SURRENDERS = "blocky_music_stats_surrenders";

    public MicrophoneItem(Properties properties) {
        super(properties);
    }

    public static boolean startsSolo(boolean crouching, boolean entityTarget) {
        return crouching && !entityTarget;
    }

    private InteractionResult solo(Player player, InteractionHand hand) {
        if (player == null || !startsSolo(player.isShiftKeyDown(), false)) return InteractionResult.PASS;
        if (player.getCooldowns().isOnCooldown(this)) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer serverPlayer) {
            ItemStack stack = player.getItemInHand(hand);
            bindToPlayerIfNeeded(stack, serverPlayer, hand);
            BattleManager.startSolo(serverPlayer);
            player.getCooldowns().addCooldown(this, 20);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(solo(player, hand), player.getItemInHand(hand));
    }

    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return solo(context.getPlayer(), context.getHand());
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        return solo(context.getPlayer(), context.getHand());
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        if (target == player) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.cannot_challenge_self"));
            return InteractionResult.FAIL;
        }

        bindToPlayerIfNeeded(stack, serverPlayer, hand);
        refreshStatsSnapshot(stack);
        if (target instanceof ServerPlayer targetPlayer) {
            // Another player is invited rather than drawn straight in, so the challenger picks a side first.
            BattleManager.offerChallengeSide(serverPlayer, targetPlayer);
        } else {
            BattleManager.challenge(serverPlayer, target);
        }
        player.getCooldowns().addCooldown(this, 20);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!level.isClientSide() && entity.tickCount % 40 == 0 && stack.is(ModItems.MICROPHONE.get())) {
            refreshStatsSnapshot(stack);
        }
        super.inventoryTick(stack, level, entity, slotId, isSelected);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.music_battle.microphone.solo_hint").withStyle(ChatFormatting.AQUA));
        CompoundTag tag = customData(stack);
        if (!tag.contains(OWNER_UUID)) {
            tooltip.add(Component.translatable("music_battle.l10n.server.microphone_unbound").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("music_battle.l10n.server.microphone_bind_hint").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        String owner = tag.getString(OWNER_NAME);

        tooltip.add(Component.translatable("music_battle.l10n.server.microphone_owner", owner.isBlank() ? Component.translatable("music_battle.l10n.server.unknown") : Component.literal(owner)).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("music_battle.l10n.server.microphone_wins", tag.getInt(WINS)).withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("music_battle.l10n.server.microphone_full_combo_wins", tag.getInt(FULL_COMBO_WINS)).withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("music_battle.l10n.server.microphone_losses", tag.getInt(LOSSES)).withStyle(ChatFormatting.RED));
        tooltip.add(Component.translatable("music_battle.l10n.server.microphone_surrenders", tag.getInt(SURRENDERS)).withStyle(ChatFormatting.RED));
    }

    public static void bindToPlayerIfNeeded(ItemStack stack, ServerPlayer player) {
        bindToPlayerIfNeeded(stack, player, null);
    }

    public static void bindToPlayerIfNeeded(ItemStack stack, ServerPlayer player, InteractionHand hand) {
        if (!stack.is(ModItems.MICROPHONE.get())) {
            return;
        }
        CompoundTag tag = customData(stack);
        if (tag.contains(OWNER_UUID)) {
            return;
        }
        tag.putString(OWNER_UUID, player.getUUID().toString());
        tag.putString(OWNER_NAME, player.getGameProfile().getName());
        setCustomData(stack, tag);
        refreshStatsSnapshot(stack);
        syncStackToHand(player, stack, hand);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.sendSystemMessage(Component.translatable("music_battle.l10n.server.microphone_bound", player.getDisplayName()).withStyle(ChatFormatting.AQUA));
    }

    public static void refreshStatsSnapshot(ItemStack stack) {
        if (!stack.is(ModItems.MICROPHONE.get())) {
            return;
        }
        CompoundTag tag = customData(stack);
        if (!tag.contains(OWNER_UUID)) {
            return;
        }
        try {
            UUID ownerId = UUID.fromString(tag.getString(OWNER_UUID));
            String ownerName = tag.getString(OWNER_NAME);
            PlayerBattleStats.Stats stats = PlayerBattleStats.get(ownerId, ownerName);
            if (!stats.name().isBlank()) {
                tag.putString(OWNER_NAME, stats.name());
            }
            tag.putInt(WINS, stats.wins());
            tag.putInt(FULL_COMBO_WINS, stats.fullComboWins());
            tag.putInt(LOSSES, stats.losses());
            tag.putInt(SURRENDERS, stats.surrenders());
            setCustomData(stack, tag);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public static void refreshPlayerMicrophones(ServerPlayer player) {
        refreshStacks(player.getInventory().items);
        refreshStacks(player.getInventory().offhand);
        refreshStacks(player.getInventory().armor);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void refreshStacks(NonNullList<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            refreshStatsSnapshot(stack);
        }
    }

    private static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void setCustomData(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void syncStackToHand(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        if (hand != null) {
            player.setItemInHand(hand, stack);
            return;
        }
        if (player.getMainHandItem() == stack) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        } else if (player.getOffhandItem() == stack) {
            player.setItemInHand(InteractionHand.OFF_HAND, stack);
        }
    }
}
