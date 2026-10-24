package ru.gora.musicbattle.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import ru.gora.musicbattle.server.BattleManager;

public final class MusicLinkItem extends Item {
    private static final Map<UUID, UUID> FIRST_MOB = new HashMap<>();
    private static final Map<UUID, Boolean> CONFIGURE_NEXT = new HashMap<>();

    public MusicLinkItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        return useOnTarget(stack, player, target, hand);
    }

    public static InteractionResult useOnTarget(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (target instanceof Player) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.link_target"));
            return InteractionResult.FAIL;
        }
        if (BattleManager.cancelMobBattle(serverPlayer, target)) {
            FIRST_MOB.remove(serverPlayer.getUUID());
            CONFIGURE_NEXT.remove(serverPlayer.getUUID());
            player.getCooldowns().addCooldown(stack.getItem(), 20);
            return InteractionResult.SUCCESS;
        }
        if (BattleManager.isEntityInBattle(target)) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.entity_busy"));
            return InteractionResult.FAIL;
        }

        UUID playerId = serverPlayer.getUUID();
        UUID firstId = FIRST_MOB.get(playerId);
        if (firstId == null) {
            FIRST_MOB.put(playerId, target.getUUID());
            CONFIGURE_NEXT.put(playerId, player.isShiftKeyDown());
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.link_first"));
            player.getCooldowns().addCooldown(stack.getItem(), 10);
            return InteractionResult.SUCCESS;
        }

        LivingEntity first = findMob(serverPlayer, firstId);
        boolean configure = player.isShiftKeyDown() || Boolean.TRUE.equals(CONFIGURE_NEXT.remove(playerId));
        FIRST_MOB.remove(playerId);
        if (first == null || first.isRemoved() || !first.isAlive() || BattleManager.isEntityInBattle(first)) {
            FIRST_MOB.put(playerId, target.getUUID());
            CONFIGURE_NEXT.put(playerId, player.isShiftKeyDown());
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.link_first_replaced"));
            return InteractionResult.SUCCESS;
        }

        BattleManager.startMobBattle(serverPlayer, first, target, configure);
        player.getCooldowns().addCooldown(stack.getItem(), 20);
        return InteractionResult.SUCCESS;
    }

    public static void clearSelection(UUID playerId) {
        FIRST_MOB.remove(playerId);
        CONFIGURE_NEXT.remove(playerId);
    }

    private static LivingEntity findMob(ServerPlayer player, UUID uuid) {
        return player.serverLevel().getEntity(uuid) instanceof LivingEntity living ? living : null;
    }
}
