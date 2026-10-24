package ru.gora.musicbattle.server;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.network.ConfigureRapGodPayload;
import ru.gora.musicbattle.registry.ModItems;

public final class RapGodConfigHandler {
    private RapGodConfigHandler() {
    }

    public static void configure(Player player, ConfigureRapGodPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !serverPlayer.getMainHandItem().is(ModItems.RAP_GOD_CONFIG_WAND.get())
                        && !serverPlayer.getOffhandItem().is(ModItems.RAP_GOD_CONFIG_WAND.get())) {
            return;
        }
        Entity target = serverPlayer.level().getEntity(payload.entityId());
        if (!(target instanceof TestRapGodMasterEntity npc) || serverPlayer.distanceToSqr(npc) > 64.0D) {
            return;
        }

        String displayName = payload.displayName() == null ? "" : payload.displayName().replaceAll("[\\p{Cntrl}\\r\\n\\t]", "").trim();
        if (displayName.length() > 48) {
            displayName = displayName.substring(0, 48);
        }
        npc.setNpcDisplayName(displayName);
        npc.setSlimSkin(payload.slimSkin());
        npc.setPushableWhenStationary(payload.pushableWhenStationary());
        npc.setNoAi(payload.stationary());
        npc.setInvulnerable(payload.invulnerable());
        npc.setGlowingTag(payload.glowing());
        npc.setRandomVotes(payload.randomVotes());

        String skinName = payload.skinName() == null ? "" : payload.skinName().trim();
        if (skinName.isBlank()) {
            npc.clearSkinProfile();
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.npc_saved", npc.getDisplayName()).withStyle(ChatFormatting.AQUA));
            return;
        }
        if (!skinName.matches("[A-Za-z0-9_]{1,16}")) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_name_invalid").withStyle(ChatFormatting.RED));
            return;
        }

        if (skinName.equalsIgnoreCase(npc.getSkinName()) && npc.getSkinUuid().isPresent()) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.npc_saved", npc.getDisplayName()).withStyle(ChatFormatting.AQUA));
            return;
        }

        // Store the typed nickname immediately. Previously it only appeared after the asynchronous
        // profile lookup succeeded, so reopening the screen made the user's value look erased.
        npc.setPendingSkinName(skinName);
        ServerPlayer onlineSkinOwner = serverPlayer.server.getPlayerList().getPlayerByName(skinName);
        if (onlineSkinOwner != null) {
            npc.setSkinProfile(onlineSkinOwner.getGameProfile().getName(), onlineSkinOwner.getUUID());
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_set", onlineSkinOwner.getGameProfile().getName()).withStyle(ChatFormatting.GREEN));
            return;
        }

        int entityId = npc.getId();
        UUID entityUuid = npc.getUUID();
        serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_loading", skinName).withStyle(ChatFormatting.AQUA));
        serverPlayer.server.getProfileCache().getAsync(skinName).whenComplete((profile, error) -> serverPlayer.server.execute(() -> {
            Entity current = serverPlayer.level().getEntity(entityId);
            if (!(current instanceof TestRapGodMasterEntity currentNpc) || !currentNpc.getUUID().equals(entityUuid)) {
                return;
            }
            // A second save may have requested another skin while this lookup was still running.
            // Never let the older response overwrite that newer choice.
            if (!currentNpc.getSkinName().equalsIgnoreCase(skinName)) {
                return;
            }
            if (error != null) {
                serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_service_unavailable", skinName).withStyle(ChatFormatting.RED));
                return;
            }
            Optional.ofNullable(profile.orElse(null)).ifPresentOrElse(
                    found -> {
                        currentNpc.setSkinProfile(found.getName(), found.getId());
                        serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_set", found.getName()).withStyle(ChatFormatting.GREEN));
                    },
                    () -> serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_not_found", skinName).withStyle(ChatFormatting.RED))
            );
        }));
    }
}
