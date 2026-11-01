package ru.gora.musicbattle.server;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import ru.gora.musicbattle.data.NpcSkinPng;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.network.ConfigureRapGodPayload;
import ru.gora.musicbattle.network.RapGodSkinUploadPayload;
import ru.gora.musicbattle.registry.ModItems;

public final class RapGodConfigHandler {
    /**
     * One skin file a second per player. The screen sends one per save; anything faster is a client resending in a
     * loop, and every accepted skin goes out to everyone who can see the NPC.
     */
    private static final int SKIN_UPLOAD_COOLDOWN_TICKS = 20;
    private static final Map<ServerPlayer, Integer> LAST_SKIN_UPLOAD = new WeakHashMap<>();

    private RapGodConfigHandler() {
    }

    public static void configure(Player player, ConfigureRapGodPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        TestRapGodMasterEntity npc = configurableNpc(serverPlayer, payload.entityId());
        if (npc == null) {
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
            // A blank nickname leaves a skin from a file alone: the screen follows this payload with a skin upload
            // when the file skin is to change or go.
            npc.clearSkinProfile();
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.npc_saved", npc.getDisplayName()).withStyle(ChatFormatting.AQUA));
            return;
        }
        if (!skinName.matches("[A-Za-z0-9_]{1,16}")) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.skin_name_invalid").withStyle(ChatFormatting.RED));
            return;
        }
        // A nickname replaces a file skin; the renderer would otherwise keep showing the file.
        npc.clearSkinFile();

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

    /**
     * A skin from a file, or an empty file to take it off. The server keeps the PNG with the NPC (it is saved in the
     * NPC's own data) and the NPC's synched data hands it to everyone who sees it, now and after a restart.
     */
    public static void uploadSkin(ServerPlayer player, RapGodSkinUploadPayload payload) {
        TestRapGodMasterEntity npc = configurableNpc(player, payload.entityId());
        if (npc == null) {
            return;
        }
        byte[] png = payload.png();
        if (png.length == 0) {
            npc.clearSkinFile();
            return;
        }
        int now = player.server.getTickCount();
        Integer last = LAST_SKIN_UPLOAD.get(player);
        if (last != null && now - last >= 0 && now - last < SKIN_UPLOAD_COOLDOWN_TICKS) {
            player.sendSystemMessage(Component.translatable("message.music_battle.npc.skin_file.too_soon").withStyle(ChatFormatting.RED));
            return;
        }
        LAST_SKIN_UPLOAD.put(player, now);

        NpcSkinPng.Result check = NpcSkinPng.check(png);
        if (!check.ok()) {
            player.sendSystemMessage(refusal(check).withStyle(ChatFormatting.RED));
            return;
        }
        npc.setSkinFile(png);
        // The file is now the skin; a nickname left behind would come back the moment the file was taken off.
        npc.clearSkinProfile();
        player.sendSystemMessage(Component.translatable("message.music_battle.npc.skin_file.applied", npc.getDisplayName())
                .withStyle(ChatFormatting.GREEN));
    }

    /** Why the server would not take the file, in the uploader's language. */
    static MutableComponent refusal(NpcSkinPng.Result check) {
        return switch (check.problem()) {
            case TOO_LARGE -> Component.translatable("message.music_battle.npc.skin_file.too_large",
                    String.valueOf(NpcSkinPng.MAX_BYTES / 1024));
            case WRONG_SIZE -> Component.translatable("message.music_battle.npc.skin_file.wrong_size",
                    String.valueOf(check.width()), String.valueOf(check.height()));
            case CORRUPT -> Component.translatable("message.music_battle.npc.skin_file.damaged");
            case EMPTY, NOT_PNG, NONE -> Component.translatable("message.music_battle.npc.skin_file.not_png");
        };
    }

    /**
     * The NPC this player may configure: they hold the configuration wand and stand within eight blocks of it. The
     * screen can only be opened that way, and a payload that names any other entity is ignored.
     */
    private static TestRapGodMasterEntity configurableNpc(ServerPlayer player, int entityId) {
        if (!player.getMainHandItem().is(ModItems.RAP_GOD_CONFIG_WAND.get())
                && !player.getOffhandItem().is(ModItems.RAP_GOD_CONFIG_WAND.get())) {
            return null;
        }
        Entity target = player.level().getEntity(entityId);
        if (!(target instanceof TestRapGodMasterEntity npc) || player.distanceToSqr(npc) > 64.0D) {
            return null;
        }
        return npc;
    }
}
