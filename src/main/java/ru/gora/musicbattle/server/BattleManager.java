package ru.gora.musicbattle.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.BattleCameraFocus;
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.BattleSkipClock;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.BattlePlacementMath;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.BattleScoreMath;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.NoteSpeed;
import ru.gora.musicbattle.data.PlayerPanelDefaults;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.item.MicrophoneItem;
import ru.gora.musicbattle.item.MusicLinkItem;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.data.VoteTiming;
import ru.gora.musicbattle.network.BattleActionPayload;
import ru.gora.musicbattle.network.BattleLaneAnimationPayload;
import ru.gora.musicbattle.network.BattleAppearancePayload;
import ru.gora.musicbattle.network.BattleAudiencePayload;
import ru.gora.musicbattle.network.BattleCameraSyncPayload;
import ru.gora.musicbattle.network.BattleStartPayload;
import ru.gora.musicbattle.network.BattleStopPayload;
import ru.gora.musicbattle.network.ClientJudgementPayload;
import ru.gora.musicbattle.network.OpenChallengeSidePayload;
import ru.gora.musicbattle.network.BattleMicrophonePayload;
import ru.gora.musicbattle.network.OpenVotePayload;
import ru.gora.musicbattle.network.PauseSkipStatusPayload;
import ru.gora.musicbattle.network.SpectatorBattlePayload;
import ru.gora.musicbattle.network.SpectatorMusicPayload;
import ru.gora.musicbattle.network.SpectatorStopPayload;
import ru.gora.musicbattle.network.TrackAssetAnswerPayload;
import ru.gora.musicbattle.network.TrackAssetChunkPayload;
import ru.gora.musicbattle.network.TrackAssetOfferPayload;
import ru.gora.musicbattle.network.TrackPreloadPayload;
import ru.gora.musicbattle.network.VoteStatusPayload;
import ru.gora.musicbattle.block.BattleCameraBlock;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.entity.SmileyBallEntity;
import ru.gora.musicbattle.registry.ModBlocks;
import ru.gora.musicbattle.registry.ModItems;

public final class BattleManager {
    /**
     * Joins the two mob names in a mob-vs-mob vote, so the client can title it "A vs B" in the player's language
     * without guessing where a name containing " vs " ends; a display name never holds a line break.
     */
    private static final String MOB_PAIR_SEPARATOR = "\n";
    private static final String MOB_AI_LOCKED_TAG = "music_battle:ai_locked";
    private static final String MOB_ORIGINAL_NO_AI_TAG = "music_battle:original_no_ai";
    private static final String MOB_ORIGINAL_NO_GRAVITY_TAG = "music_battle:original_no_gravity";
    private static final double ANCHOR_CORRECTION_DISTANCE_SQR = 0.0625D;
    /** Orbit degradation: full swing, half swing, then the centred target itself. */
    private static final double[] CAMERA_ORBIT_SCALES = {1.0D, 0.5D, 0.0D};
    /** How far the shot turns towards the playing fighter, as a share of the half-distance between the two. */
    private static final double CAMERA_FOCUS_PAN = 0.6D;
    /** How far the camera itself slides after them, the same way; small, so the turn does the work. */
    private static final double CAMERA_FOCUS_TRUCK = 0.25D;
    private static final int CHALLENGE_TICKS = 60 * 20;
    /** 60 seconds; the vote screen reads the same length from VoteTiming. */
    private static final int VOTE_TICKS = VoteTiming.VOTE_TICKS;
    /** How long an asset offer waits for its answer before the bytes are pushed anyway. */
    private static final int ASSET_OFFER_TIMEOUT_TICKS = 40;
    /** Files offered to a player and not yet answered, keyed by player, track, kind, name and hash. */
    private static final Map<String, PendingAsset> PENDING_ASSETS = new HashMap<>();
    private static final int ROULETTE_TICKS = 3 * 20;
    private static final int HIT_WINDOW_TICKS = 16;
    private static final int MISS_AFTER_PANEL_EXIT_TICKS = 7;
    private static final int CLIENT_JUDGEMENT_FALLBACK_TICKS = 20 * 3;
    private static final int PERFECT_WINDOW_TICKS = 2;
    private static final int EARLY_HIT_WINDOW_TICKS = 6;
    private static final int EARLY_FAIL_TICKS = 11;
    private static final int SAME_LANE_HIT_LOCKOUT_TICKS = 2;
    private static final int HOLD_RELEASE_EARLY_TICKS = 2;
    private static final int MUSIC_SYNC_LEAD_TICKS = 40;
    private static final int PAUSE_SKIP_PREP_TICKS = 3 * 20;
    private static final int PAUSE_SKIP_MIN_GAP_TICKS = 6 * 20;
    private static final int TRACK_PREPARE_TIMEOUT_TICKS = 20 * 60 * 5;
    /**
     * How long a battle that starts while its 1.5x/2x copy is still being converted waits for it before it plays at
     * normal speed. The copy is asked for when the vote arrives, but a vote both players answer closes on that same
     * tick, so without a wait the first sped-up battle on a track was always 1x. A three-minute song takes about a
     * second to convert at 2x on a current desktop; eight seconds covers a host several times slower and a song
     * twice as long, and is still short enough for two players standing frozen in the arena with a countdown.
     */
    private static final int SPEED_AUDIO_WAIT_TICKS = 8 * 20;
    private static final int ASSET_CHUNK_BYTES = 48 * 1024;
    private static final long MAX_AUDIO_ASSET_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_PREVIEW_ASSET_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_CHART_ASSET_BYTES = 2L * 1024L * 1024L;
    private static final double MAX_ACCEPT_DISTANCE = 6.0D;
    private static final double SPECTATOR_MUSIC_DISTANCE = 14.0D;
    private static final double CROWD_RADIUS = 18.0D;
    private static final double BATTLE_EXCLUSION_RADIUS = 24.0D;
    private static final int BATTLE_CAMERA_RADIUS = 16;
    private static final double HOSTILE_KEEP_AWAY_DISTANCE = 3.0D;
    private static final List<String> DEFAULT_KEY_LABELS = List.of("<", "v", "^", ">");

    private static final Map<UUID, Challenge> CHALLENGES_BY_TARGET = new HashMap<>();
    /** Challengers who have been asked which side to take, by challenger; only an asked choice is accepted. */
    private static final Map<UUID, Challenge> SIDE_CHOICES_BY_CHALLENGER = new HashMap<>();
    private static final Map<UUID, Battle> BATTLES = new HashMap<>();
    private static final Map<UUID, UUID> BATTLE_BY_PLAYER = new HashMap<>();
    private static final Map<UUID, UUID> BATTLE_BY_ENTITY = new HashMap<>();
    /** Pictures already sent to a player for the Workshop's Server tab outside a battle, so each goes only once. */
    private static final Map<UUID, Set<String>> SERVER_TAB_PREVIEWS_SENT = new HashMap<>();
    /** The battle id the Workshop's Server tab sends, since its picture requests belong to no battle. */
    private static final UUID NO_BATTLE = new UUID(0L, 0L);
    private static final Map<UUID, Float> NOTE_SPEEDS = new HashMap<>();
    private static final Map<UUID, NoteAppearance> NOTE_APPEARANCES = new HashMap<>();
    private static final Map<UUID, List<String>> KEY_LABELS = new HashMap<>();
    private static final Map<UUID, Integer> PANEL_COLORS = new HashMap<>();
    private static final Map<UUID, PanelStyle> PANEL_STYLES = new HashMap<>();

    private BattleManager() {
    }

    /** Supplies immutable emitters without rediscovering speaker networks per mob. */
    static void appendMusicMobSources(ServerLevel level, List<MusicMobRhythm.Source> sources) {
        for (Battle battle : BATTLES.values()) {
            if (battle.phase != Phase.PLAYING || battle.track == null
                    || battle.playTicks < 0 || battle.playTicks >= battle.durationTicks
                    || battle.leftEntity.level() != level || battle.rightEntity.level() != level
                    || battle.leftAnchor == null || battle.rightAnchor == null) {
                continue;
            }
            MusicMobRhythm.Beat beat = new MusicMobRhythm.Beat(
                    battle.id, ChartBeat.beatTicks(battle.playTicks, battle.effectiveOffsetMs), battle.track.bpm() * (double) normalizeSpeedMultiplier(battle.speedMultiplier));
            Vec3 center = battle.leftAnchor.add(battle.rightAnchor).scale(0.5D);
            MusicMobRhythm.addSource(sources, beat, center.add(0.0D, 1.0D, 0.0D),
                    SPECTATOR_MUSIC_DISTANCE, battle.track.volume());
            for (BlockPos speakerPos : battle.activeSpeakers) {
                if (level.hasChunkAt(speakerPos)
                        && level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker) {
                    MusicMobRhythm.addSource(sources, beat,
                            Vec3.atCenterOf(speakerPos).add(0.0D, 0.65D, 0.0D),
                            speaker.getSoundRadius(), battle.track.volume() * speaker.getVolume());
                }
            }
        }
    }

    public static void challenge(ServerPlayer challenger, LivingEntity target) {
        if (isBusy(challenger.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.already_battling"));
            return;
        }

        if (target instanceof ServerPlayer targetPlayer) {
            challengePlayer(challenger, targetPlayer);
            return;
        }

        if (isBusyEntity(target)) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.entity_busy"));
            return;
        }

        if (!(target instanceof LivingEntity)) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.microphone_target"));
            return;
        }

        if (!validateDuelStart(challenger, target, true)) {
            return;
        }
        // A mob gets the same side question as a player, so the challenger can take the chart's second part against
        // it too; the battle starts when they answer (challengeWithSide), and Esc in the dialog starts nothing.
        SIDE_CHOICES_BY_CHALLENGER.put(challenger.getUUID(),
                new Challenge(target.getUUID(), challenger.getServer().getTickCount(), true));
        PacketDistributor.sendToPlayer(challenger, new OpenChallengeSidePayload(target.getUUID(), displayName(target)));
    }

    public static void startSolo(ServerPlayer player) {
        if (isBusy(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.finish_current")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // Nothing published here is fine: the player's own installed maps fill the list (see startVoting).
        List<MusicBattleTracks.Track> tracks = MusicBattleTracks.availableTracks();
        ServerLevel level = player.serverLevel();
        if (hasNearbyBattle(level, player.position())) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.nearby_battle")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        ArmorStand soloPartner = new ArmorStand(level, player.getX(), player.getY(), player.getZ());
        TemporaryBattleEntities.mark(soloPartner);
        soloPartner.setInvisible(true);
        soloPartner.noPhysics = true;
        soloPartner.setNoGravity(true);
        soloPartner.setInvulnerable(true);
        soloPartner.setSilent(true);
        soloPartner.setCustomName(Component.translatable("music_battle.l10n.server.solo"));
        soloPartner.setCustomNameVisible(false);
        Vec3[] soloSpots = soloArenaSpots(player);
        if (BattleArenaPlacement.find(level, player, soloPartner, soloSpots, true) == null) {
            player.sendSystemMessage(Component.translatable("music_battle.battle.no_safe_placement")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        level.addFreshEntity(soloPartner);

        UUID battleId = UUID.randomUUID();
        Battle battle = Battle.forSolo(battleId, player, soloPartner, tracks);
        BATTLES.put(battleId, battle);
        BATTLE_BY_PLAYER.put(player.getUUID(), battleId);
        BATTLE_BY_ENTITY.put(player.getUUID(), battleId);
        BATTLE_BY_ENTITY.put(soloPartner.getUUID(), battleId);
        List<TrackSummary> summaries = voteSummaries(tracks);
        PacketDistributor.sendToPlayer(player,
                new OpenVotePayload(battleId, "Solo", true, false, true, battle.voteRemainingTicks(), summaries, truncated(tracks, summaries)));
        player.sendSystemMessage(Component.translatable("music_battle.l10n.server.solo_choose_track")
                .withStyle(ChatFormatting.AQUA));
    }

    public static void startMobBattle(ServerPlayer organizer, LivingEntity first, LivingEntity second) {
        startMobBattle(organizer, first, second, false);
    }

    public static void startMobBattle(ServerPlayer organizer, LivingEntity first, LivingEntity second, boolean configure) {
        if (first.getUUID().equals(second.getUUID())) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.two_different_mobs"));
            return;
        }
        if (controlledBattle(organizer.getUUID()) != null) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.finish_mob_battle").withStyle(ChatFormatting.RED));
            return;
        }
        if (isBusyEntity(first) || isBusyEntity(second)) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.mob_busy"));
            return;
        }
        if (first.level() != second.level() || first.level() != organizer.level()) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.mobs_same_dimension"));
            return;
        }
        double maxDistance = first instanceof EnderDragon || second instanceof EnderDragon ? 80.0D : MAX_ACCEPT_DISTANCE;
        if (first.distanceToSqr(second) > maxDistance * maxDistance) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.mobs_nearby"));
            return;
        }
        if (!(first.level() instanceof ServerLevel level) || !hasArenaSpace(level, first, second)) {
            organizer.sendSystemMessage(Component.translatable("music_battle.battle.no_safe_placement").withStyle(ChatFormatting.RED));
            return;
        }
        if (hasNearbyBattle(level, first.position().add(second.position()).scale(0.5D))) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.nearby_battle").withStyle(ChatFormatting.RED));
            return;
        }

        List<MusicBattleTracks.Track> tracks = MusicBattleTracks.availableTracks();
        // A fresh world has nothing of its own, so the quick battle has no map to draw. It used to draw from the empty
        // list anyway, which threw after both mobs were already held for a vote nobody saw. Opening the vote instead
        // lets the player hand the mobs one of their own installed maps; only when this server refuses those too
        // is there nothing to offer, and then it says so before touching either mob.
        boolean noServerTracks = tracks.isEmpty();
        if (noServerTracks && !MusicBattleServerConfig.allowPlayerMaps()) {
            organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.no_tracks").withStyle(ChatFormatting.RED));
            return;
        }
        Battle battle = Battle.forMobBattle(UUID.randomUUID(), first, second, tracks);
        battle.styleSourcePlayerId = organizer.getUUID();
        // Every mob battle belongs to the player who started it. This ensures a
        // logout cancels even the quick/random variant and restores both mobs.
        battle.mobControllerId = organizer.getUUID();
        battle.mobSkill = MobSkill.values()[battle.random.nextInt(MobSkill.values().length)];
        battle.unifiedNotes = false;
        battle.deadlyParty = false;
        BATTLES.put(battle.id, battle);
        BATTLE_BY_ENTITY.put(first.getUUID(), battle.id);
        BATTLE_BY_ENTITY.put(second.getUUID(), battle.id);
        if (configure || noServerTracks) {
            List<TrackSummary> summaries = voteSummaries(tracks);
            PacketDistributor.sendToPlayer(organizer, new OpenVotePayload(battle.id, displayName(first) + MOB_PAIR_SEPARATOR + displayName(second), true, true, false, battle.voteRemainingTicks(), summaries, truncated(tracks, summaries)));
            organizer.sendSystemMessage(Component.translatable(configure
                    ? "music_battle.l10n.server.mob_choose_settings"
                    : "music_battle.l10n.server.mob_pick_own_map"));
            return;
        }
        MusicBattleTracks.Track track = battle.tracks.get(battle.random.nextInt(battle.tracks.size()));
        organizer.sendSystemMessage(Component.translatable("music_battle.l10n.server.mobs_start", track.title()));
        beginPlaying(battle, track);
    }

    public static boolean cancelMobBattle(ServerPlayer player, LivingEntity target) {
        UUID battleId = BATTLE_BY_ENTITY.get(target.getUUID());
        Battle battle = battleId == null ? null : BATTLES.get(battleId);
        if (battle == null || battle.hasAnyPlayer()) {
            return false;
        }
        finishBattle(battle, Component.translatable("music_battle.l10n.server.mob_battle_canceled"), true);
        return true;
    }

    public static boolean isEntityInBattle(LivingEntity entity) {
        return isBusyEntity(entity);
    }

    /** Shares cached audible-source arbitration with the ball's rhythm; DJ music has no actors. */
    public static LivingEntity audienceLookTarget(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level) || isEntityInBattle(mob)) return null;
        MusicMobRhythm.Beat beat = MusicMobRhythm.sample(level, mob.position());
        Battle battle = beat == null ? null : BATTLES.get(beat.sourceId());
        if (battle == null) return null;
        LivingEntity left = battle.leftEntity();
        LivingEntity right = battle.rightEntity();
        double leftDistance = left != null && left.isAlive() && left.level() == level
                ? mob.distanceToSqr(left) : Double.POSITIVE_INFINITY;
        double rightDistance = right != null && right.isAlive() && right.level() == level
                ? mob.distanceToSqr(right) : Double.POSITIVE_INFINITY;
        return switch (ru.gora.musicbattle.data.BattleAudienceFocus.choose(false, true, leftDistance, rightDistance)) {
            case 0 -> left;
            case 1 -> right;
            default -> null;
        };
    }

    /** Authoritative chart clock for a participant, independent of audible emitters. */
    public static MusicMobRhythm.Beat musicBeatForParticipant(LivingEntity entity) {
        UUID battleId = BATTLE_BY_ENTITY.get(entity.getUUID());
        Battle battle = battleId == null ? null : BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.PLAYING || battle.track == null
                || battle.playTicks < 0 || battle.playTicks >= battle.durationTicks) return null;
        return new MusicMobRhythm.Beat(battle.id, ChartBeat.beatTicks(battle.playTicks, battle.effectiveOffsetMs),
                battle.track.bpm() * (double) normalizeSpeedMultiplier(battle.speedMultiplier));
    }

    /** Keeps ambient DJ sessions from replacing a participant or real spectator session. */
    public static boolean hasActiveBattleContext(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (BATTLE_BY_PLAYER.containsKey(playerId)) {
            return true;
        }
        for (Battle battle : BATTLES.values()) {
            if (playerId.equals(battle.mobControllerId)
                    || battle.spectatorIds.contains(playerId)
                    || battle.preloadRecipientIds.contains(playerId)
                    || battle.preloadSpectatorIds.contains(playerId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies an authoritative block-camera edit to every active battle that is
     * currently using that exact block. This also covers cameras discovered
     * through a chained speaker's coverage because the chosen block position is
     * retained when the arena is prepared.
     */
    public static void onBattleCameraBlockUpdated(ServerLevel level, BlockPos blockPos, Vec3 position,
                                                  float yaw, float pitch, float fov) {
        for (Battle battle : BATTLES.values()) {
            if (!battle.fixedCamera
                    || battle.camera == null
                    || battle.camera.isRemoved()
                    || battle.camera.level() != level
                    || !blockPos.equals(battle.fixedCameraBlockPos)) {
                continue;
            }

            battle.lastBroadcastCamera = new BattleCameraPlacement(blockPos, position,
                    Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89.0F, 89.0F), Mth.clamp(fov, 30.0F, 110.0F));

            battle.cameraTarget = position;
            battle.cameraStart = position;
            battle.cameraYaw = Mth.wrapDegrees(yaw);
            battle.cameraPitch = Mth.clamp(pitch, -89.0F, 89.0F);
            battle.cameraFov = Mth.clamp(fov, 30.0F, 110.0F);
            Vec3 cameraEntityPos = cameraEntityPosition(battle.camera, position);
            battle.camera.absMoveTo(
                    cameraEntityPos.x,
                    cameraEntityPos.y,
                    cameraEntityPos.z,
                    battle.cameraYaw,
                    battle.cameraPitch
            );

            BattleCameraSyncPayload payload = new BattleCameraSyncPayload(
                    battle.id,
                    battle.camera.getId(),
                    position.x,
                    position.y,
                    position.z,
                    battle.cameraYaw,
                    battle.cameraPitch,
                    battle.cameraFov,
                    ++battle.cameraSyncRevision
            );
            for (ServerPlayer watcher : battle.watchers()) {
                PacketDistributor.sendToPlayer(watcher, payload);
            }
        }
    }

    public static int accept(CommandSourceStack source, UUID challengerId) {
        ServerPlayer target;
        try {
            target = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.accept_player_only"));
            return 0;
        }

        Challenge challenge = CHALLENGES_BY_TARGET.get(target.getUUID());
        if (challenge == null || !challenge.challengerId().equals(challengerId)) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.challenge_missing"));
            return 0;
        }

        ServerPlayer challenger = source.getServer().getPlayerList().getPlayer(challengerId);
        if (challenger == null) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.challenger_offline"));
            CHALLENGES_BY_TARGET.remove(target.getUUID());
            return 0;
        }

        if (!validateDuelStart(challenger, target, false)) {
            return 0;
        }

        CHALLENGES_BY_TARGET.remove(target.getUUID());
        if (challenge.challengerFirst()) {
            startVoting(challenger, target);
        } else {
            // Player 1 is simply whoever stands on the left of the battle, so a challenger who chose to be Player 2
            // starts it from the other side; every rule after this follows the sides, not who asked.
            startVoting(target, challenger);
        }
        return 1;
    }

    public static int decline(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.decline_player_only"));
            return 0;
        }

        Challenge challenge = CHALLENGES_BY_TARGET.remove(player.getUUID());
        if (challenge == null) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.no_challenge"));
            return 0;
        }

        ServerPlayer challenger = source.getServer().getPlayerList().getPlayer(challenge.challengerId());
        if (challenger != null) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.player_declined", player.getDisplayName()));
        }
        player.sendSystemMessage(Component.translatable("music_battle.l10n.server.challenge_declined"));
        return 1;
    }

    public static void updateSettings(Player player, float noteSpeed, int panelColor, float panelOpacity, float laneOpacity, float panelWidthScale, float panelHeightScale, float noteWidthScale, float noteHeightScale, List<String> keyLabels, NoteAppearance appearance) {
        if (player instanceof ServerPlayer serverPlayer) {
            NOTE_SPEEDS.put(serverPlayer.getUUID(), NoteSpeed.clamp(noteSpeed));
            NOTE_APPEARANCES.put(serverPlayer.getUUID(), appearance == null ? NoteAppearance.solid(panelColor) : appearance);
            PANEL_COLORS.put(serverPlayer.getUUID(), 0xFF000000 | (panelColor & 0x00FFFFFF));
            PANEL_STYLES.put(serverPlayer.getUUID(), new PanelStyle(
                    Mth.clamp(panelOpacity, 0.0F, 1.0F),
                    Mth.clamp(laneOpacity, 0.0F, 1.5F),
                    Mth.clamp(panelWidthScale, 0.45F, 1.6F),
                    Mth.clamp(panelHeightScale, 0.55F, 1.6F),
                    Mth.clamp(noteWidthScale, 0.35F, 2.2F),
                    Mth.clamp(noteHeightScale, 0.35F, 2.2F)
            ));
            KEY_LABELS.put(serverPlayer.getUUID(), normalizeKeyLabels(keyLabels));
            Battle battle = BATTLES.get(BATTLE_BY_PLAYER.get(serverPlayer.getUUID()));
            if (battle != null) {
                ParticipantState state = battle.leftState != null && serverPlayer.getUUID().equals(battle.leftState.playerId)
                        ? battle.leftState : battle.rightState != null && serverPlayer.getUUID().equals(battle.rightState.playerId)
                        ? battle.rightState : null;
                if (state != null) {
                    state.speed = playerSpeed(serverPlayer.getUUID());
                    state.appearance = playerAppearance(serverPlayer.getUUID());
                    if (battle.phase == Phase.PLAYING) {
                        BattleAppearancePayload update = new BattleAppearancePayload(battle.id, state.owner, state.speed, state.appearance);
                        for (ServerPlayer receiver : battle.players()) PacketDistributor.sendToPlayer(receiver, update);
                        for (UUID spectatorId : battle.spectatorIds) {
                            ServerPlayer spectator = serverPlayer.server.getPlayerList().getPlayer(spectatorId);
                            if (spectator != null) PacketDistributor.sendToPlayer(spectator, update);
                        }
                    }
                }
            }
        }
    }

    public static void submitVote(Player player, UUID battleId, String trackId, String mobSkill, boolean unifiedNotes, boolean deadlyParty, float speedMultiplier) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.VOTING || !battle.canVote(serverPlayer.getUUID())) {
            return;
        }

        Optional<MusicBattleTracks.Track> votedTrack = battle.trackById(trackId);
        if (votedTrack.isEmpty()) {
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.track_unavailable"));
            return;
        }

        boolean selectedUnifiedNotes = !battle.solo && unifiedNotes;
        boolean selectedDeadlyParty = !battle.solo && deadlyParty;
        String previousVote = battle.votes.put(serverPlayer.getUUID(), trackId);
        battle.unifiedVotes.put(serverPlayer.getUUID(), selectedUnifiedNotes);
        battle.deadlyVotes.put(serverPlayer.getUUID(), selectedDeadlyParty);
        battle.speedVotes.put(serverPlayer.getUUID(), normalizeSpeedMultiplier(speedMultiplier));
        // A sped-up track has to be converted, which takes long enough that doing it when the battle starts
        // would stall the server. Asking for it here gives the conversion the rest of the vote to finish in.
        // Only a player's first vote asks: our client votes once, and a client that kept re-voting every track
        // at 1.5x and 2x would otherwise queue a minute of ffmpeg per track and fill the track folders with
        // copies. A changed vote still gets its copy, because the battle asks again when it starts.
        if (previousVote == null) {
            MusicBattleTracks.requestSpeedVariant(votedTrack.get(), normalizeSpeedMultiplier(speedMultiplier));
        }
        // Any battle with a mob in it takes the mob's skill from the vote, on whichever side the mob stands.
        if ((battle.rightPlayerId == null || battle.leftPlayerId == null) && !battle.solo) {
            battle.mobSkill = selectedDeadlyParty ? MobSkill.deadlyAllowed(mobSkill) : MobSkill.byId(mobSkill);
            battle.mobVoteId = trackId;
        }
        notifyModeVotes(battle, serverPlayer, votedTrack.get(), selectedUnifiedNotes, selectedDeadlyParty, normalizeSpeedMultiplier(speedMultiplier));
        serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.vote_accepted", trackId));
        // The last expected choice closes the vote on this very tick instead of waiting out the timer.
        if (battle.everyoneVoted()) {
            resolveVotes(battle);
        }
    }

    public static void submitInput(Player player, UUID battleId, int lane, int clientTick, boolean down, int noteIndex) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.PLAYING) {
            return;
        }

        int owner = battle.ownerFor(serverPlayer.getUUID());
        if (owner < 0) {
            return;
        }
        ParticipantState state = battle.state(owner);
        int clampedLane = Mth.clamp(lane, 0, 3);
        if (down && state.laneDown[clampedLane]) {
            return;
        }
        state.laneDown[clampedLane] = down;
        BattleLaneAnimationPayload animationPayload = new BattleLaneAnimationPayload(
                battle.id, owner, clampedLane, down);
        for (ServerPlayer watcher : battle.watchers()) {
            PacketDistributor.sendToPlayer(watcher, animationPayload);
        }
    }

    public static void submitClientJudgement(Player player, ClientJudgementPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Battle battle = BATTLES.get(payload.battleId());
        if (battle == null || battle.phase != Phase.PLAYING) {
            return;
        }
        int owner = battle.ownerFor(serverPlayer.getUUID());
        if (owner < 0 || payload.lane() < 0 || payload.lane() > 3) {
            return;
        }
        ParticipantState state = battle.state(owner);
        if (state == null || state.ai || payload.sequence() <= state.lastClientJudgementSequence) {
            return;
        }
        int clientTick = payload.clientTick();
        if (clientTick < -MUSIC_SYNC_LEAD_TICKS
                || clientTick > battle.durationTicks + CLIENT_JUDGEMENT_FALLBACK_TICKS + 80
                || payload.score() < 0
                || payload.score() > 1_000_000_000
                || payload.combo() < 0
                || payload.combo() > battle.runtimeNotes.size() + 1
                || payload.mistakes() < 0
                || payload.mistakes() > 100_000) {
            return;
        }

        RuntimeNote note = payload.noteIndex() >= 0 ? battle.noteByIndex(payload.noteIndex()) : null;
        if (payload.noteIndex() >= 0 && !namesNoteOnPanel(note, owner, payload.lane())) {
            // The number the client sent names no note on its own panel, so the two sides did not build the same
            // chart - a mixed build, or a chart format one of them reads differently. Dropping the judgement was
            // silent and brutal: the note then timed out as a miss three seconds later, so the combo died on
            // notes the player had hit. Resolving it by timing instead, the way the mob path always has, loses
            // some precision on a skewed build and keeps the battle playable.
            RuntimeNote byTiming = battle.noteByTiming(payload.kind(), state, owner, payload.lane(), clientTick);
            if (byTiming != null) {
                warnAboutNoteIndexSkew(battle, serverPlayer, payload.noteIndex(), byTiming.index);
                note = byTiming;
            }
        }
        boolean hit = false;
        boolean complete = false;
        switch (payload.kind()) {
            case ClientJudgementPayload.NOTE_HIT -> {
                if (!canAcceptClientHit(note, state, owner, payload.lane(), clientTick)) {
                    return;
                }
                note.started = true;
                note.hitResolved = true;
                note.missedResolved = false;
                if (note.note.durationTicks() <= 0) {
                    note.resolved = true;
                    complete = true;
                } else {
                    state.activeHolds[payload.lane()] = note;
                }
                state.laneHitLockoutUntil[payload.lane()] = clientTick + SAME_LANE_HIT_LOCKOUT_TICKS;
                hit = true;
                animateParticipant(battle, owner);
            }
            case ClientJudgementPayload.HOLD_COMPLETE -> {
                if (!canAcceptClientHold(note, owner, payload.lane())
                        || clientTick < note.note.tick() + note.note.durationTicks() - HOLD_RELEASE_EARLY_TICKS) {
                    return;
                }
                note.resolved = true;
                note.started = false;
                note.hitResolved = true;
                note.missedResolved = false;
                if (state.activeHolds[payload.lane()] == note) {
                    state.activeHolds[payload.lane()] = null;
                }
                hit = true;
                complete = true;
            }
            case ClientJudgementPayload.NOTE_MISS -> {
                if (note == null
                        || note.resolved
                        || note.started
                        || note.note.owner() != owner
                        || note.note.lane() != payload.lane()
                        || clientTick - note.note.tick() < MISS_AFTER_PANEL_EXIT_TICKS) {
                    return;
                }
                note.resolved = true;
                note.started = false;
                note.hitResolved = false;
                note.missedResolved = true;
                complete = true;
            }
            case ClientJudgementPayload.BAD_PRESS -> {
                if (payload.noteIndex() >= 0 || !shouldBreakComboForBadPress(battle, owner, payload.lane(), clientTick)) {
                    return;
                }
            }
            case ClientJudgementPayload.HOLD_BREAK -> {
                if (!canAcceptClientHold(note, owner, payload.lane())
                        || clientTick >= note.note.tick() + note.note.durationTicks() - HOLD_RELEASE_EARLY_TICKS) {
                    return;
                }
                note.resolved = true;
                note.started = false;
                note.hitResolved = false;
                note.missedResolved = true;
                if (state.activeHolds[payload.lane()] == note) {
                    state.activeHolds[payload.lane()] = null;
                }
                complete = true;
            }
            default -> {
                return;
            }
        }

        state.lastClientJudgementSequence = payload.sequence();
        state.score = payload.score();
        state.combo = payload.combo();
        state.maxCombo = Math.max(state.maxCombo, state.combo);
        state.mistakes = payload.mistakes();
        // The note this was resolved against, not the number it arrived with: after a fallback those differ, and
        // the opponent and the spectators have to be told which note actually changed.
        broadcastAction(battle, owner, payload.lane(), note == null ? -1 : note.index, hit, complete, serverPlayer.getUUID());
    }

    /** Whether a number from a client named a note that is really on that player's own panel. */
    private static boolean namesNoteOnPanel(RuntimeNote note, int owner, int lane) {
        return note != null && note.note.owner() == owner && note.note.lane() == lane;
    }

    /**
     * Says once per battle that a client is judging notes by numbers this build does not use.
     *
     * <p>One line, because it is per judgement otherwise: hundreds of them in a song.
     */
    private static void warnAboutNoteIndexSkew(Battle battle, ServerPlayer player, int claimedIndex, int resolvedIndex) {
        if (battle.noteIndexSkewWarned) {
            return;
        }
        battle.noteIndexSkewWarned = true;
        MusicBattleMod.LOGGER.warn("Blocky Music battle '{}': {} judged note {}, which is not theirs here; "
                        + "resolving by timing as note {}. The client's chart does not match this server's - check that "
                        + "both sides run the same build.",
                battle.id, player.getGameProfile().getName(), claimedIndex, resolvedIndex);
    }

    private static boolean canAcceptClientHit(RuntimeNote note, ParticipantState state, int owner, int lane, int clientTick) {
        if (note == null
                || note.resolved
                || note.started
                || note.note.owner() != owner
                || note.note.lane() != lane
                || clientTick < state.laneHitLockoutUntil[lane]) {
            return false;
        }
        int earlyDistance = note.note.tick() - clientTick;
        int lateDistance = clientTick - note.note.tick();
        return earlyDistance >= 0
                ? earlyDistance <= earlyHitWindowTicks(state.speed)
                : lateDistance <= MISS_AFTER_PANEL_EXIT_TICKS;
    }

    private static boolean canAcceptClientHold(RuntimeNote note, int owner, int lane) {
        return note != null
                && !note.resolved
                && note.started
                && note.note.durationTicks() > 0
                && note.note.owner() == owner
                && note.note.lane() == lane;
    }

    public static void submitPauseSkipVote(Player player, UUID battleId) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.PLAYING || !battle.hasPlayer(serverPlayer.getUUID())) {
            return;
        }
        int targetTick = battle.pauseSkipTargetTick();
        if (targetTick < 0) {
            sendPauseSkipStatus(battle, false, -1, false);
            return;
        }
        battle.pauseSkipVotes.add(serverPlayer.getUUID());
        battle.addAutomaticPauseSkipVotes();
        int required = battle.requiredPauseSkipVotes();
        if (battle.pauseSkipVotes.size() >= required) {
            battle.applyPauseSkip(targetTick);
            sendPauseSkipStatus(battle, false, targetTick, true);
        } else {
            sendPauseSkipStatus(battle, true, targetTick, false);
        }
    }

    public static void surrender(Player player, UUID battleId) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Battle battle = BATTLES.get(battleId);
        if (battle == null) {
            return;
        }
        // Only someone in this battle may end it: a participant, or the player running a mob battle.
        // The battle id reaches every spectator, so without this any watcher could cancel the vote
        // or hand the win to one side.
        if (!battle.canVote(serverPlayer.getUUID())) {
            return;
        }

        if (battle.phase == Phase.VOTING || battle.phase == Phase.ROULETTE || battle.phase == Phase.PREPARING) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.vote_canceled", serverPlayer.getDisplayName()), true);
            return;
        }
        if (battle.phase != Phase.PLAYING) {
            return;
        }

        if (battle.solo) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.solo_canceled"), true);
            return;
        }

        int owner = battle.ownerFor(serverPlayer.getUUID());
        LivingEntity winnerEntity = battle.entity(owner == 0 ? 1 : 0);
        Component winner = winnerEntity == null ? Component.translatable("music_battle.l10n.server.opponent") : winnerEntity.getDisplayName();
        recordSurrenderStats(battle, serverPlayer, owner);
        finishBattle(battle, Component.translatable("music_battle.l10n.server.surrendered", serverPlayer.getDisplayName(), winner), true);
        if (battle.deadlyParty && serverPlayer.isAlive()) {
            if (serverPlayer.level() instanceof ServerLevel level) {
                level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("music_battle.l10n.server.deadly_loser", serverPlayer.getDisplayName()), false);
                serverPlayer.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            } else {
                serverPlayer.kill();
            }
        }
    }

    public static void reportClientError(Player player, UUID battleId, String reason) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Battle battle = BATTLES.get(battleId);
        if (battle == null || !battle.hasPlayer(serverPlayer.getUUID())) {
            return;
        }

        String rawReason = reason == null ? "" : MusicBattleTracks.safeText(reason).strip();
        if (rawReason.length() > 180) {
            rawReason = rawReason.substring(0, 180);
        }
        Component safeReason = rawReason.isBlank()
                ? Component.translatable("music_battle.l10n.server.unknown_client_error")
                : Component.literal(rawReason);
        // One client's own error text is server log material. Other players and spectators only
        // learn whose client failed, never a string that client composed.
        MusicBattleMod.LOGGER.warn("Blocky Music client error reported by {}: {}",
                serverPlayer.getGameProfile().getName(), rawReason.isBlank() ? "(empty)" : rawReason);
        if (battle.hasPlayer(serverPlayer.getUUID())) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.client_error_player", serverPlayer.getDisplayName()), true);
            return;
        }
        if (battle.phase == Phase.PREPARING && battle.preloadSpectatorIds.contains(serverPlayer.getUUID())) {
            battle.preloadFailedSpectatorIds.add(serverPlayer.getUUID());
            removeSpectatorSession(battle, serverPlayer.getUUID());
            PacketDistributor.sendToPlayer(serverPlayer, new SpectatorStopPayload(battle.id));
            serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.spectator_load_failed", safeReason)
                    .withStyle(ChatFormatting.RED));
            refreshPreparingSpectators(battle);
        }
    }

    public static void confirmTrackReady(Player player, UUID battleId, String trackId, String chartFile, String audioFile) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.PREPARING || battle.track == null
                || !battle.preloadRecipientIds.contains(serverPlayer.getUUID())) {
            return;
        }
        String expectedChartFile = transferFileName(battle.runtimeChartFile);
        String expectedAudioFile = transferFileName(battle.runtimeAudioFile);
        if (!battle.track.id().equals(trackId) || !expectedChartFile.equals(chartFile) || !expectedAudioFile.equals(audioFile)) {
            return;
        }

        battle.readyPlayers.add(serverPlayer.getUUID());
        MusicBattleMod.LOGGER.info("Blocky Music track '{}' is ready on {} ({}/{} synchronized clients).", trackId,
                serverPlayer.getGameProfile().getName(), battle.readyPlayers.size(), battle.preloadRecipientIds.size());
        serverPlayer.displayClientMessage(Component.translatable("music_battle.l10n.server.track_ready").withStyle(ChatFormatting.AQUA), true);
        refreshPreparingSpectators(battle);
        if (battle.allPreloadRecipientsReady()) {
            startPreparedBattle(battle);
        }
    }

    public static void requestTrackPreviews(Player player, UUID battleId, List<String> trackIds) {
        if (!(player instanceof ServerPlayer serverPlayer) || trackIds == null || trackIds.isEmpty()) {
            return;
        }
        if (NO_BATTLE.equals(battleId)) {
            // The Server tab lists every track a vote could offer, so it may see the picture of each of them and no more.
            Set<String> sent = SERVER_TAB_PREVIEWS_SENT.computeIfAbsent(serverPlayer.getUUID(), ignored -> new HashSet<>());
            int count = 0;
            for (String trackId : trackIds) {
                if (++count > 8 || trackId == null || !sent.add(trackId)) {
                    continue;
                }
                MusicBattleTracks.availableTracks().stream()
                        .filter(track -> track.id().equals(trackId))
                        .filter(track -> !ru.gora.musicbattle.server.workshop.ServerBeatmaps.hiddenFromVotes(track))
                        .findFirst()
                        .ifPresent(track -> MusicBattleTracks.previewPath(track)
                                .ifPresent(path -> sendAssetAsync(serverPlayer, track.id(), "preview", track.previewFile(), path)));
            }
            return;
        }
        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.VOTING || !battle.canVote(serverPlayer.getUUID())) {
            return;
        }
        Set<String> sent = battle.previewIdsSent.computeIfAbsent(serverPlayer.getUUID(), ignored -> new HashSet<>());
        int count = 0;
        for (String trackId : trackIds) {
            if (++count > 8 || trackId == null || !sent.add(trackId)) {
                continue;
            }
            battle.tracks.stream()
                    .filter(track -> track.id().equals(trackId))
                    .findFirst()
                    .ifPresent(track -> MusicBattleTracks.previewPath(track)
                            .ifPresent(path -> sendAssetAsync(serverPlayer, track.id(), "preview", track.previewFile(), path)));
        }
    }

    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (isProtectedParticipant(event.getEntity()) || BattleCrowdSafety.absorbFall(event.getEntity(), event.getSource())) {
            event.setAmount(0.0F);
            event.setCanceled(true);
        }
    }

    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (handleEnderDragonPartInteract(event.getEntity(), event.getHand(), event.getTarget())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (handleEnderDragonPartInteract(event.getEntity(), event.getHand(), event.getTarget())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        flushExpiredAssetOffers(event.getServer());
        BattleCrowdSafety.tick(now);
        CHALLENGES_BY_TARGET.entrySet().removeIf(entry -> now - entry.getValue().createdTick() > CHALLENGE_TICKS);
        SIDE_CHOICES_BY_CHALLENGER.entrySet().removeIf(entry -> now - entry.getValue().createdTick() > CHALLENGE_TICKS);

        List<Battle> snapshot = new ArrayList<>(BATTLES.values());
        for (Battle battle : snapshot) {
            battle.tick();
            if (now % 5L == 0L && BATTLES.containsKey(battle.id)) refreshDjBattleCamera(battle);
        }
        if (now % 5L == 0L) {
            refreshSpeakerNetworks(event.getServer());
        }
    }

    /**
     * The rows of a vote screen (spec 06 section 9.4): withdrawn server beatmaps are left out, every other row
     * carries its source chip and, for a workshop set, the identity a client needs to answer an asset offer.
     */
    private static List<TrackSummary> voteSummaries(List<MusicBattleTracks.Track> tracks) {
        List<TrackSummary> summaries = new ArrayList<>(Math.min(tracks.size(), OpenVotePayload.MAX_TRACKS));
        for (MusicBattleTracks.Track track : tracks) {
            if (summaries.size() >= OpenVotePayload.MAX_TRACKS) {
                break;
            }
            if (ru.gora.musicbattle.server.workshop.ServerBeatmaps.hiddenFromVotes(track)) {
                continue;
            }
            summaries.add(ru.gora.musicbattle.server.workshop.ServerBeatmaps.summary(track));
        }
        return List.copyOf(summaries);
    }

    /** Honest truncation (spec 06 section 9.4): the screen says so instead of silently showing a short list. */
    private static boolean truncated(List<MusicBattleTracks.Track> tracks, List<TrackSummary> summaries) {
        return summaries.size() < tracks.size();
    }

    /**
     * An INSTALLED vote whose package still has to travel (spec 06 section 9.4): the vote stays open for it, by at
     * most 30 extra seconds, and {@code everyoneVoted} ignores the voter until the transfer lands or is dropped.
     */
    public static boolean markInstalledVotePending(UUID battleId, UUID playerId) {
        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.VOTING || !battle.canVote(playerId)) {
            return false;
        }
        battle.pendingTransferVoters.add(playerId);
        battle.voteExtensionTicks = Math.min(VoteTiming.TICKS_PER_SECOND * 30, battle.voteExtensionTicks + VoteTiming.TICKS_PER_SECOND * 30);
        return true;
    }

    /** The transfer failed or was cancelled: the vote closes as if the player had not chosen. */
    public static void cancelInstalledVote(UUID battleId, UUID playerId) {
        Battle battle = BATTLES.get(battleId);
        if (battle == null) {
            return;
        }
        battle.pendingTransferVoters.remove(playerId);
        if (battle.phase == Phase.VOTING && battle.everyoneVoted()) {
            resolveVotes(battle);
        }
    }

    /** Whether this battle is still collecting votes; the transfer manager stops early once it is not. */
    public static boolean isVoting(UUID battleId) {
        Battle battle = BATTLES.get(battleId);
        return battle != null && battle.phase == Phase.VOTING;
    }

    /**
     * Records a vote for a chart the server has just taken in (a server beatmap, or a set fetched from the voter).
     * The chart is playable in this battle only; it never enters {@code availableTracks()}.
     */
    public static void applyInstalledVote(ServerPlayer player, UUID battleId, MusicBattleTracks.Track track,
                                          String mobSkill, boolean unifiedNotes, boolean deadlyParty, float speedMultiplier) {
        Battle battle = BATTLES.get(battleId);
        if (battle == null || battle.phase != Phase.VOTING || track == null) {
            return;
        }
        battle.pendingTransferVoters.remove(player.getUUID());
        if (battle.trackById(track.id()).isEmpty()) {
            battle.extraTracks.add(track);
        }
        submitVote(player, battleId, track.id(), mobSkill, unifiedNotes, deadlyParty, speedMultiplier);
    }

    /**
     * Whether a running battle is playing, preloading or still offering this chart. Removing a server beatmap is
     * refused while it is (spec 06 section 9.1): the participants have already been told which files to expect.
     */
    public static boolean isTrackInUse(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return false;
        }
        for (Battle battle : BATTLES.values()) {
            if (battle.track != null && trackId.equals(battle.track.id())) {
                return true;
            }
            for (MusicBattleTracks.Track candidate : battle.tracks) {
                if (trackId.equals(candidate.id())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (Battle battle : new ArrayList<>(BATTLES.values())) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.server_stopping"), true);
        }
        CHALLENGES_BY_TARGET.clear();
        SIDE_CHOICES_BY_CHALLENGER.clear();
        PENDING_ASSETS.clear();
        BATTLES.clear();
        BATTLE_BY_PLAYER.clear();
        BATTLE_BY_ENTITY.clear();
        BattleCrowdSafety.clear();
        SpeakerNetwork.deactivateAll();
    }

    private static void refreshSpeakerNetworks(MinecraftServer server) {
        Map<ServerLevel, Map<BlockPos, Integer>> activeByLevel = new HashMap<>();
        for (Battle battle : BATTLES.values()) {
            if (battle.phase != Phase.PLAYING || battle.leftAnchor == null || battle.rightAnchor == null
                    || !(battle.leftEntity.level() instanceof ServerLevel level)) {
                battle.activeSpeakers = Set.of();
                continue;
            }
            Vec3 center = battle.leftAnchor.add(battle.rightAnchor).scale(0.5D);
            battle.activeSpeakers = SpeakerNetwork.connectedSpeakers(level, center, SPECTATOR_MUSIC_DISTANCE);
            if (battle.playTicks >= 0 && battle.playTicks <= battle.durationTicks) {
                Map<BlockPos, Integer> active = activeByLevel.computeIfAbsent(level, ignored -> new HashMap<>());
                for (BlockPos pos : battle.activeSpeakers) {
                    active.merge(pos, battle.effectiveBpm, Math::max);
                }
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            SpeakerNetwork.applyActiveStates(level, activeByLevel.getOrDefault(level, Map.of()));
        }
    }

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity dropped) {
            dropped.setItem(ru.gora.musicbattle.item.SoloMicrophoneItem.migrate(dropped.getItem()));
        }
        if (event.getEntity() instanceof LivingEntity living) {
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                ItemStack old = living.getItemBySlot(slot);
                ItemStack replacement = ru.gora.musicbattle.item.SoloMicrophoneItem.migrate(old);
                if (old != replacement) living.setItemSlot(slot, replacement);
            }
        }
        if (event.loadedFromDisk() && event.getEntity() instanceof ArmorStand
                && TemporaryBattleEntities.isMarked(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (mob.getPersistentData().getBoolean(MOB_AI_LOCKED_TAG)
                && !BATTLE_BY_ENTITY.containsKey(mob.getUUID())) {
            restoreMobState(
                    mob,
                    mob.getPersistentData().getBoolean(MOB_ORIGINAL_NO_AI_TAG),
                    mob.getPersistentData().getBoolean(MOB_ORIGINAL_NO_GRAVITY_TAG)
            );
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        CHALLENGES_BY_TARGET.remove(player.getUUID());
        SIDE_CHOICES_BY_CHALLENGER.remove(player.getUUID());
        SERVER_TAB_PREVIEWS_SENT.remove(player.getUUID());
        MusicLinkItem.clearSelection(player.getUUID());
        TrackPublicationManager.cancelPlayer(player.getUUID());
        Battle battle = controlledBattle(player.getUUID());
        if (battle != null) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.participant_logout"), true);
            return;
        }
        for (Battle activeBattle : new ArrayList<>(BATTLES.values())) {
            removeSpectatorSession(activeBattle, player.getUUID());
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ru.gora.musicbattle.item.SoloMicrophoneItem.migrateInventory(player);
        for (Battle battle : new ArrayList<>(BATTLES.values())) {
            battle.preloadFailedSpectatorIds.remove(player.getUUID());
            removeSpectatorSession(battle, player.getUUID());
            if (battle.phase == Phase.PREPARING) {
                refreshPreparingSpectators(battle);
            } else if (battle.phase == Phase.PLAYING && battle.track != null
                    && player.level() instanceof ServerLevel level && battle.leftEntity.level() == level) {
                updateSpectators(level, battle, battle.track);
            }
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MusicLinkItem.clearSelection(player.getUUID());
        Battle battle = controlledBattle(player.getUUID());
        if (battle != null) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.dimension_changed"), true);
            return;
        }
        for (Battle activeBattle : new ArrayList<>(BATTLES.values())) {
            removeSpectatorSession(activeBattle, player.getUUID());
        }
    }

    private static void removeSpectatorSession(Battle battle, UUID playerId) {
        battle.spectatorIds.remove(playerId);
        battle.spectatorSourceKeys.remove(playerId);
        battle.preloadSpectatorIds.remove(playerId);
        if (!battle.hasPlayer(playerId)) {
            battle.preloadRecipientIds.remove(playerId);
            battle.readyPlayers.remove(playerId);
        }
    }

    private static Battle controlledBattle(UUID playerId) {
        UUID battleId = BATTLE_BY_PLAYER.get(playerId);
        Battle participantBattle = battleId == null ? null : BATTLES.get(battleId);
        if (participantBattle != null) {
            return participantBattle;
        }
        for (Battle battle : BATTLES.values()) {
            if (playerId.equals(battle.mobControllerId)) {
                return battle;
            }
        }
        return null;
    }

    /**
     * The microphone's way in: before challenging another player, ask the challenger which side they want. The
     * same checks as the challenge itself run first, so nobody picks a side for a challenge that cannot be sent.
     */
    public static void offerChallengeSide(ServerPlayer challenger, ServerPlayer target) {
        if (isBusy(challenger.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.already_battling"));
            return;
        }
        if (!canChallengePlayer(challenger, target)) {
            return;
        }
        SIDE_CHOICES_BY_CHALLENGER.put(challenger.getUUID(),
                new Challenge(target.getUUID(), challenger.getServer().getTickCount(), true));
        PacketDistributor.sendToPlayer(challenger, new OpenChallengeSidePayload(target.getUUID(), target.getGameProfile().getName()));
    }

    /** The challenger's answer to {@link #offerChallengeSide}; anything not asked for is ignored. */
    public static void challengeWithSide(ServerPlayer challenger, UUID targetId, boolean challengerFirst) {
        Challenge asked = SIDE_CHOICES_BY_CHALLENGER.get(challenger.getUUID());
        if (asked == null || !asked.challengerId().equals(targetId)) {
            return;
        }
        SIDE_CHOICES_BY_CHALLENGER.remove(challenger.getUUID());
        ServerPlayer target = challenger.getServer().getPlayerList().getPlayer(targetId);
        if (target == null) {
            if (challenger.serverLevel().getEntity(targetId) instanceof LivingEntity mob && !(mob instanceof Player)) {
                challengeMobWithSide(challenger, mob, challengerFirst);
                return;
            }
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.challenge_target_offline"));
            return;
        }
        if (isBusy(challenger.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.already_battling"));
            return;
        }
        challengePlayer(challenger, target, challengerFirst);
    }

    /**
     * The mob was checked when the dialog opened, but the challenger may have taken their time over it: the mob can
     * have died, wandered off or been drawn into another battle since, so every check runs again before it starts.
     */
    private static void challengeMobWithSide(ServerPlayer challenger, LivingEntity mob, boolean challengerFirst) {
        if (isBusy(challenger.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.already_battling"));
            return;
        }
        if (!mob.isAlive() || mob.isRemoved()) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.challenge_mob_gone"));
            return;
        }
        if (isBusyEntity(mob)) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.entity_busy"));
            return;
        }
        if (!validateDuelStart(challenger, mob, true)) {
            return;
        }
        if (challengerFirst) {
            startVoting(challenger, mob);
        } else {
            // As with two players, Player 1 is whoever stands on the left, so the mob takes that place.
            startVotingAgainstMobFirst(mob, challenger);
        }
    }

    private static void challengePlayer(ServerPlayer challenger, ServerPlayer target) {
        challengePlayer(challenger, target, true);
    }

    private static boolean canChallengePlayer(ServerPlayer challenger, ServerPlayer target) {
        if (challenger.getUUID().equals(target.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.cannot_challenge_self"));
            return false;
        }
        if (isBusy(target.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.player_busy"));
            return false;
        }
        if (challenger.level() instanceof ServerLevel level
                && target.level() == level
                && hasNearbyBattle(level, challenger.position().add(target.position()).scale(0.5D))) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.nearby_battle").withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    private static void challengePlayer(ServerPlayer challenger, ServerPlayer target, boolean challengerFirst) {
        if (!canChallengePlayer(challenger, target)) {
            return;
        }

        CHALLENGES_BY_TARGET.put(target.getUUID(), new Challenge(challenger.getUUID(), challenger.getServer().getTickCount(), challengerFirst));
        MutableComponent accept = Component.translatable("music_battle.l10n.server.accept_button")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/musicbattle accept " + challenger.getUUID()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("music_battle.l10n.server.accept_hover"))));
        MutableComponent decline = Component.translatable("music_battle.l10n.server.decline_button")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/musicbattle decline"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("music_battle.l10n.server.decline_hover"))));

        // Both of them are told the sides up front, so accepting is agreeing to them.
        String targetSide = challengerFirst ? "2" : "1";
        String challengerSide = challengerFirst ? "1" : "2";
        target.sendSystemMessage(Component.translatable("music_battle.l10n.server.challenge_offer", challenger.getDisplayName())
                .append(Component.translatable("music_battle.l10n.server.challenge_side", targetSide))
                .append(accept)
                .append(Component.literal(" "))
                .append(decline));
        challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.challenge_sent", target.getDisplayName())
                .append(Component.literal(" "))
                .append(Component.translatable("music_battle.l10n.server.challenge_side", challengerSide)));
    }

    private static boolean handleEnderDragonPartInteract(Player player, InteractionHand hand, Entity target) {
        if (!(target instanceof EnderDragonPart part)) {
            return false;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModItems.MICROPHONE.get()) && !stack.is(ModItems.MUSIC_LINK.get())) {
            return false;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            EnderDragon dragon = part.parentMob;
            if (dragon == null || !dragon.isAlive()) {
                serverPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.dragon_unavailable"));
                return true;
            }
            if (stack.is(ModItems.MUSIC_LINK.get())) {
                MusicLinkItem.useOnTarget(stack, player, dragon, hand);
            } else {
                MicrophoneItem.bindToPlayerIfNeeded(stack, serverPlayer, hand);
                MicrophoneItem.refreshStatsSnapshot(stack);
                challenge(serverPlayer, dragon);
                player.getCooldowns().addCooldown(stack.getItem(), 20);
            }
        }
        return true;
    }

    private static boolean validateDuelStart(ServerPlayer challenger, LivingEntity target, boolean directMobStart) {
        if (!(challenger.level() instanceof ServerLevel level) || target.level() != level) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.same_dimension"));
            return false;
        }
        if (isBusy(challenger.getUUID()) || isBusyEntity(target) || target instanceof ServerPlayer targetPlayer && isBusy(targetPlayer.getUUID())) {
            challenger.sendSystemMessage(Component.translatable("music_battle.l10n.server.participant_busy"));
            return false;
        }
        double maxDistance = target instanceof EnderDragon ? 80.0D : MAX_ACCEPT_DISTANCE;
        if (challenger.distanceToSqr(target) > maxDistance * maxDistance) {
            challenger.sendSystemMessage(directMobStart ? Component.translatable("music_battle.l10n.server.move_closer") : Component.translatable("music_battle.l10n.server.accept_nearby"));
            return false;
        }
        if (!hasArenaSpace(level, challenger, target)) {
            challenger.sendSystemMessage(Component.translatable("music_battle.battle.no_safe_placement").withStyle(ChatFormatting.RED));
            if (target instanceof ServerPlayer targetPlayer) {
                targetPlayer.sendSystemMessage(Component.translatable("music_battle.battle.no_safe_placement").withStyle(ChatFormatting.RED));
            }
            return false;
        }
        Vec3 battleCenter = challenger.position().add(target.position()).scale(0.5D);
        if (hasNearbyBattle(level, battleCenter)) {
            Component message = Component.translatable("music_battle.l10n.server.nearby_battle").withStyle(ChatFormatting.RED);
            challenger.sendSystemMessage(message);
            if (target instanceof ServerPlayer targetPlayer) {
                targetPlayer.sendSystemMessage(message);
            }
            return false;
        }
        return true;
    }

    private static boolean hasNearbyBattle(ServerLevel level, Vec3 center) {
        double radiusSqr = BATTLE_EXCLUSION_RADIUS * BATTLE_EXCLUSION_RADIUS;
        for (Battle battle : BATTLES.values()) {
            LivingEntity left = battle.leftEntity();
            LivingEntity right = battle.rightEntity();
            if (left == null || right == null || left.level() != level || right.level() != level) {
                continue;
            }
            Vec3 existingCenter = left.position().add(right.position()).scale(0.5D);
            if (existingCenter.distanceToSqr(center) < radiusSqr) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasArenaSpace(ServerLevel level, LivingEntity leftEntity, LivingEntity rightEntity) {
        return BattleArenaPlacement.find(level, leftEntity, rightEntity, arenaSpots(leftEntity, rightEntity), false) != null;
    }

    private static Vec3[] soloArenaSpots(LivingEntity performer) {
        Vec3 performerSpot = performer.position();
        Vec3 facing = Vec3.directionFromRotation(0.0F, performer.getYRot()).multiply(1.0D, 0.0D, 1.0D);
        if (facing.lengthSqr() < 0.01D) {
            facing = new Vec3(0.0D, 0.0D, 1.0D);
        }
        // The hidden helper stays almost at the performer so the camera, FX radius and
        // spectator framing are centered on the actual soloist rather than an empty side.
        return new Vec3[]{performerSpot, performerSpot.add(facing.normalize().scale(0.05D))};
    }

    private static Vec3[] arenaSpots(LivingEntity leftEntity, LivingEntity rightEntity) {
        if (leftEntity instanceof EnderDragon && !(rightEntity instanceof EnderDragon)) {
            Vec3 playerSpot = rightEntity.position();
            Vec3 axis = leftEntity.position().subtract(playerSpot).multiply(1.0D, 0.0D, 1.0D);
            if (axis.lengthSqr() < 0.01D) {
                axis = Vec3.directionFromRotation(0.0F, rightEntity.getYRot() + 180.0F).multiply(1.0D, 0.0D, 1.0D);
            }
            axis = axis.normalize();
            return new Vec3[]{playerSpot.add(axis.scale(7.5D)), playerSpot};
        }
        if (rightEntity instanceof EnderDragon && !(leftEntity instanceof EnderDragon)) {
            Vec3 playerSpot = leftEntity.position();
            Vec3 axis = rightEntity.position().subtract(playerSpot).multiply(1.0D, 0.0D, 1.0D);
            if (axis.lengthSqr() < 0.01D) {
                axis = Vec3.directionFromRotation(0.0F, leftEntity.getYRot()).multiply(1.0D, 0.0D, 1.0D);
            }
            axis = axis.normalize();
            return new Vec3[]{playerSpot, playerSpot.add(axis.scale(7.5D))};
        }
        Vec3 center = leftEntity.position().add(rightEntity.position()).scale(0.5D);
        Vec3 axis = rightEntity.position().subtract(leftEntity.position()).multiply(1.0D, 0.0D, 1.0D);
        if (axis.lengthSqr() < 0.01D) {
            axis = Vec3.directionFromRotation(0.0F, leftEntity.getYRot()).multiply(1.0D, 0.0D, 1.0D);
        }
        axis = axis.normalize();
        double y = Math.min(leftEntity.getY(), rightEntity.getY());
        return new Vec3[]{
                new Vec3(center.x, y, center.z).subtract(axis.scale(1.75D)),
                new Vec3(center.x, y, center.z).add(axis.scale(1.75D))
        };
    }

    private static void startVoting(ServerPlayer leftPlayer, LivingEntity rightEntity) {
        // A server with nothing published is the normal case, not an error: maps come from the Workshop and live on
        // each player's own machine, and publishing to the server is the extra. The vote opens either way; whoever
        // picks one of their own installed maps sends it across, and only a vote that ends with nothing at all is
        // called off - with a line saying where maps come from.
        List<MusicBattleTracks.Track> tracks = MusicBattleTracks.availableTracks();
        UUID battleId = UUID.randomUUID();
        // Whoever is passed in on the left is Player 1 (owner 0) for every track: the challenger, unless they chose
        // Player 2. This belongs to battle creation, never to a chart-specific exception or migration.
        Battle battle = Battle.forPlayerChallenge(battleId, leftPlayer, rightEntity, tracks);
        BATTLES.put(battleId, battle);
        BATTLE_BY_PLAYER.put(leftPlayer.getUUID(), battleId);
        BATTLE_BY_ENTITY.put(leftPlayer.getUUID(), battleId);
        BATTLE_BY_ENTITY.put(rightEntity.getUUID(), battleId);
        if (rightEntity instanceof ServerPlayer rightPlayer) {
            BATTLE_BY_PLAYER.put(rightPlayer.getUUID(), battleId);
        }

        List<TrackSummary> summaries = voteSummaries(tracks);
        boolean testNpc = battle.testNpc() != null;
        PacketDistributor.sendToPlayer(leftPlayer, new OpenVotePayload(battleId, displayName(rightEntity), true, !(rightEntity instanceof ServerPlayer), false, battle.voteRemainingTicks(), summaries, truncated(tracks, summaries)));
        leftPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.choose_track"));

        if (testNpc) {
            submitTestNpcVote(battle);
        }

        if (rightEntity instanceof ServerPlayer rightPlayer) {
            PacketDistributor.sendToPlayer(rightPlayer, new OpenVotePayload(battleId, leftPlayer.getGameProfile().getName(), false, false, false, battle.voteRemainingTicks(), summaries, truncated(tracks, summaries)));
            rightPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.choose_track"));
        }
    }

    /**
     * A challenger who chose to be Player 2 against a mob: the mob stands on the left as Player 1 and the challenger,
     * on the right, is the only one asked to vote. Mirrors {@link #startVoting(ServerPlayer, LivingEntity)}.
     */
    private static void startVotingAgainstMobFirst(LivingEntity leftMob, ServerPlayer rightPlayer) {
        List<MusicBattleTracks.Track> tracks = MusicBattleTracks.availableTracks();
        UUID battleId = UUID.randomUUID();
        Battle battle = Battle.forMobFirst(battleId, leftMob, rightPlayer, tracks);
        BATTLES.put(battleId, battle);
        BATTLE_BY_PLAYER.put(rightPlayer.getUUID(), battleId);
        BATTLE_BY_ENTITY.put(rightPlayer.getUUID(), battleId);
        BATTLE_BY_ENTITY.put(leftMob.getUUID(), battleId);

        List<TrackSummary> summaries = voteSummaries(tracks);
        PacketDistributor.sendToPlayer(rightPlayer, new OpenVotePayload(battleId, displayName(leftMob), false, true, false,
                battle.voteRemainingTicks(), summaries, truncated(tracks, summaries)));
        rightPlayer.sendSystemMessage(Component.translatable("music_battle.l10n.server.choose_track"));
        if (battle.testNpc() != null) {
            submitTestNpcVote(battle);
        }
    }

    private static void submitTestNpcVote(Battle battle) {
        TestRapGodMasterEntity npc = battle.testNpc();
        if (npc == null || battle.tracks.isEmpty()) {
            return;
        }
        MusicBattleTracks.Track track = battle.tracks.get(battle.random.nextInt(battle.tracks.size()));
        boolean unified = npc.hasRandomVotes() && battle.random.nextBoolean();
        boolean deadly = npc.hasRandomVotes() && battle.random.nextInt(4) == 0;
        float speed = npc.hasRandomVotes() ? switch (battle.random.nextInt(3)) {
            case 1 -> 1.5F;
            case 2 -> 2.0F;
            default -> 1.0F;
        } : 1.0F;
        UUID voter = npc.getUUID();
        battle.votes.put(voter, track.id());
        battle.unifiedVotes.put(voter, unified);
        battle.deadlyVotes.put(voter, deadly);
        battle.speedVotes.put(voter, speed);
        for (ServerPlayer player : battle.players()) {
            PacketDistributor.sendToPlayer(player, new VoteStatusPayload(
                    battle.id,
                    displayName(npc),
                    track.title(),
                    unified,
                    deadly,
                    speed,
                    battle.voteRemainingTicks(),
                    battle.everyoneVoted()
            ));
            player.displayClientMessage(Component.translatable("music_battle.l10n.server.participant_settings", npc.getDisplayName()).withStyle(ChatFormatting.GOLD), true);
        }
    }

    private static void resolveVotes(Battle battle) {
        // A vote landing on the tick the timer runs out, or two votes in one tick, reach here twice.
        if (battle.votesResolved || battle.phase != Phase.VOTING) {
            return;
        }
        battle.votesResolved = true;
        // Screens still open stop their countdown now, not when their own clock would reach zero.
        VoteStatusPayload closed = VoteStatusPayload.closed(battle.id, battle.everyoneVoted());
        for (ServerPlayer holder : battle.voteScreenHolders()) {
            PacketDistributor.sendToPlayer(holder, closed);
        }

        List<String> votedIds = new ArrayList<>(battle.votes.values());
        if (battle.mobVoteId != null) {
            votedIds.add(battle.mobVoteId);
        }
        if (votedIds.isEmpty()) {
            if (battle.tracks.isEmpty()) {
                // Nobody chose, and this server has nothing of its own to fall back on.
                finishBattle(battle, Component.translatable("music_battle.l10n.server.nothing_chosen"), true);
                return;
            }
            resolveModeVotes(battle);
            startSelectedTrack(battle, battle.tracks.getFirst().id());
            return;
        }

        Map<String, Integer> counts = new HashMap<>();
        for (String vote : votedIds) {
            counts.merge(vote, 1, Integer::sum);
        }

        int bestCount = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> winners = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == bestCount)
                .map(Map.Entry::getKey)
                .toList();
        if (winners.size() > 1) {
            resolveModeVotes(battle);
            startRoulette(battle, winners);
            return;
        }

        resolveModeVotes(battle);
        startSelectedTrack(battle, winners.getFirst());
    }

    private static void resolveModeVotes(Battle battle) {
        battle.speedMultiplier = 1.0F;
        if (battle.solo) {
            battle.unifiedNotes = false;
            battle.deadlyParty = false;
            battle.speedMultiplier = normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(battle.leftPlayerId, 1.0F));
            return;
        }
        if (!battle.hasAnyPlayer()) {
            UUID voter = battle.mobControllerId;
            battle.unifiedNotes = voter != null && Boolean.TRUE.equals(battle.unifiedVotes.get(voter));
            battle.deadlyParty = voter != null && Boolean.TRUE.equals(battle.deadlyVotes.get(voter));
            battle.speedMultiplier = voter == null ? 1.0F : normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(voter, 1.0F));
            if (battle.deadlyParty && battle.mobSkill.ordinal() < MobSkill.GOD.ordinal()) {
                battle.mobSkill = MobSkill.GOD;
            }
            return;
        }
        if (battle.rightPlayerId == null || battle.leftPlayerId == null) {
            // One player against a mob, on whichever side they chose; only their votes, and the rap god's, count.
            UUID human = battle.leftPlayerId != null ? battle.leftPlayerId : battle.rightPlayerId;
            if (battle.testNpc() != null) {
                TestRapGodMasterEntity npcEntity = battle.testNpc();
                UUID npc = npcEntity.getUUID();
                float leftSpeedVote = normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(human, 1.0F));
                if (npcEntity.hasRandomVotes()) {
                    battle.unifiedNotes = Boolean.TRUE.equals(battle.unifiedVotes.get(human))
                            && Boolean.TRUE.equals(battle.unifiedVotes.get(npc));
                    battle.deadlyParty = Boolean.TRUE.equals(battle.deadlyVotes.get(human))
                            && Boolean.TRUE.equals(battle.deadlyVotes.get(npc));
                    float npcSpeedVote = normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(npc, 1.0F));
                    battle.speedMultiplier = Math.abs(leftSpeedVote - npcSpeedVote) < 0.01F ? leftSpeedVote : 1.0F;
                } else {
                    battle.unifiedNotes = Boolean.TRUE.equals(battle.unifiedVotes.get(human));
                    battle.deadlyParty = Boolean.TRUE.equals(battle.deadlyVotes.get(human));
                    battle.speedMultiplier = leftSpeedVote;
                }
                if (battle.deadlyParty && battle.mobSkill.ordinal() < MobSkill.GOD.ordinal()) {
                    battle.mobSkill = MobSkill.GOD;
                }
                return;
            }
            battle.unifiedNotes = Boolean.TRUE.equals(battle.unifiedVotes.get(human));
            battle.deadlyParty = Boolean.TRUE.equals(battle.deadlyVotes.get(human));
            battle.speedMultiplier = normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(human, 1.0F));
            if (battle.deadlyParty && battle.mobSkill.ordinal() < MobSkill.GOD.ordinal()) {
                battle.mobSkill = MobSkill.GOD;
            }
            return;
        }
        battle.unifiedNotes = Boolean.TRUE.equals(battle.unifiedVotes.get(battle.leftPlayerId))
                && Boolean.TRUE.equals(battle.unifiedVotes.get(battle.rightPlayerId));
        battle.deadlyParty = Boolean.TRUE.equals(battle.deadlyVotes.get(battle.leftPlayerId))
                && Boolean.TRUE.equals(battle.deadlyVotes.get(battle.rightPlayerId));
        float leftSpeedVote = normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(battle.leftPlayerId, 1.0F));
        float rightSpeedVote = normalizeSpeedMultiplier(battle.speedVotes.getOrDefault(battle.rightPlayerId, 1.0F));
        battle.speedMultiplier = Math.abs(leftSpeedVote - rightSpeedVote) < 0.01F ? leftSpeedVote : 1.0F;
    }

    private static void notifyModeVotes(Battle battle, ServerPlayer voter, MusicBattleTracks.Track track, boolean unifiedNotes, boolean deadlyParty, float speedMultiplier) {
        for (ServerPlayer other : battle.players()) {
            if (other.getUUID().equals(voter.getUUID())) {
                continue;
            }
            PacketDistributor.sendToPlayer(other, new VoteStatusPayload(
                    battle.id,
                    voter.getGameProfile().getName(),
                    track.title(),
                    unifiedNotes,
                    deadlyParty,
                    speedMultiplier,
                    battle.voteRemainingTicks(),
                    battle.everyoneVoted()
            ));
            if (unifiedNotes) {
                other.displayClientMessage(Component.translatable("music_battle.l10n.server.offer_unified", voter.getDisplayName()).withStyle(ChatFormatting.AQUA), true);
            }
            if (deadlyParty) {
                other.displayClientMessage(Component.translatable("music_battle.l10n.server.offer_deadly", voter.getDisplayName()).withStyle(ChatFormatting.RED), true);
            }
            if (speedMultiplier > 1.01F) {
                other.displayClientMessage(Component.translatable("music_battle.l10n.server.offer_speed", voter.getDisplayName(), formatSpeedMultiplier(speedMultiplier)).withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    private static float normalizeSpeedMultiplier(float multiplier) {
        if (multiplier >= 1.9F) {
            return 2.0F;
        }
        if (multiplier >= 1.4F) {
            return 1.5F;
        }
        return 1.0F;
    }

    private static String formatSpeedMultiplier(float multiplier) {
        return multiplier >= 1.9F ? "2" : multiplier >= 1.4F ? "1.5" : "1";
    }

    private static int scaledTick(int tick, float speedMultiplier) {
        return Math.max(0, Math.round(tick / Math.max(1.0F, speedMultiplier)));
    }

    /**
     * The notes a battle really plays, in the order they are numbered in.
     *
     * <p>Every judgement names a note by its place in this list, and the chart written for the clients carries
     * the same list in the same order, so this order is the contract between the two sides. It is kept pure and
     * visible so {@code tools/BattleReliabilityChecks} can build a chart from it and read that chart back through
     * the client's own reader: that round trip is what catches a numbering that has drifted apart again.
     *
     * @param solo one panel, with notes that would land on the same lane and tick folded into one
     * @param unified both players play the same notes, so every source note becomes a pair - left, then right
     * @param speedMultiplier the voted speed, which divides every tick and hold length
     */
    public static List<BattleNote> runtimeNoteOrder(List<BattleNote> trackNotes, boolean solo, boolean unified,
                                                    float speedMultiplier) {
        List<BattleNote> ordered = new ArrayList<>(trackNotes.size() * (!solo && unified ? 2 : 1));
        Set<String> seenSoloNotes = solo ? new HashSet<>() : Set.of();
        for (BattleNote note : trackNotes) {
            int tick = scaledTick(note.tick(), speedMultiplier);
            int duration = scaledTick(note.durationTicks(), speedMultiplier);
            if (solo) {
                if (seenSoloNotes.add(note.lane() + ":" + tick + ":" + duration)) {
                    ordered.add(new BattleNote(0, note.lane(), tick, duration));
                }
            } else if (unified) {
                ordered.add(new BattleNote(0, note.lane(), tick, duration));
                ordered.add(new BattleNote(1, note.lane(), tick, duration));
            } else {
                ordered.add(new BattleNote(note.owner(), note.lane(), tick, duration));
            }
        }
        return ordered;
    }

    private static void startRoulette(Battle battle, List<String> choices) {
        battle.phase = Phase.ROULETTE;
        battle.rouletteTicks = 0;
        battle.rouletteChoices = List.copyOf(choices);
        for (ServerPlayer player : battle.players()) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.roulette_start"));
        }
    }

    private static void finishRoulette(Battle battle) {
        if (battle.rouletteChoices.isEmpty()) {
            startSelectedTrack(battle, battle.tracks.getFirst().id());
            return;
        }
        String chosenId = battle.rouletteChoices.get(battle.random.nextInt(battle.rouletteChoices.size()));
        for (ServerPlayer player : battle.players()) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.roulette_chosen", chosenId));
        }
        startSelectedTrack(battle, chosenId);
    }

    private static void startSelectedTrack(Battle battle, String chosenId) {
        if (battle.phase != Phase.VOTING && battle.phase != Phase.ROULETTE) {
            return;
        }

        String selectedId = battle.trackById(chosenId).isPresent() ? chosenId : battle.tracks.getFirst().id();
        Optional<MusicBattleTracks.Track> selected = battle.trackById(selectedId);
        if (selected.isEmpty()) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.chosen_track_missing"), false);
            return;
        }
        beginPlaying(battle, selected.get());
    }

    /** The line the players see while their sped-up song is still converting, with the seconds the wait has left. */
    private static void showSpeedAudioWait(Battle battle) {
        int secondsLeft = Math.max(1, (SPEED_AUDIO_WAIT_TICKS - battle.speedAudioWaitTicks + 19) / 20);
        Component waiting = Component.translatable("music_battle.l10n.server.speed_audio_waiting",
                formatSpeedMultiplier(normalizeSpeedMultiplier(battle.speedMultiplier)), secondsLeft)
                .withStyle(ChatFormatting.AQUA);
        for (ServerPlayer player : battle.players()) {
            player.displayClientMessage(waiting, true);
        }
    }

    private static boolean hasNotesForSingleOwner(MusicBattleTracks.Track track) {
        int owner = -1;
        for (BattleNote note : track.notes()) {
            int noteOwner = Mth.clamp(note.owner(), 0, 1);
            if (owner < 0) {
                owner = noteOwner;
            } else if (owner != noteOwner) {
                return false;
            }
        }
        return owner >= 0;
    }

    private static void beginPlaying(Battle battle, MusicBattleTracks.Track track) {
        LivingEntity leftEntity = battle.leftEntity();
        LivingEntity rightEntity = battle.rightEntity();
        if (leftEntity == null || rightEntity == null || !leftEntity.isAlive() || !rightEntity.isAlive()) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.participant_missing"), false);
            return;
        }
        if (!(leftEntity.level() instanceof ServerLevel level) || rightEntity.level() != level) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.participants_split"), false);
            return;
        }

        battle.phase = Phase.PREPARING;
        battle.prepareTicks = 0;
        battle.readyPlayers.clear();
        battle.preloadRecipientIds.clear();
        battle.preloadSpectatorIds.clear();
        battle.preloadFailedSpectatorIds.clear();
        battle.preparedTransfer = null;
        battle.track = track;
        battle.runtimeAudioFile = track.audioFile();
        battle.runtimeChartFile = transferBaseName(track.id()) + "_battle.json";
        battle.awaitingSpeedAudio = false;
        battle.speedAudioWaitTicks = 0;
        if (!battle.solo && !battle.unifiedNotes && hasNotesForSingleOwner(track)) {
            battle.unifiedNotes = true;
            for (ServerPlayer player : battle.players()) {
                player.displayClientMessage(Component.translatable("music_battle.l10n.server.single_chart_side").withStyle(ChatFormatting.AQUA), true);
            }
        }

        // The arena comes before the audio, because nothing in it depends on the speed and it is where the players
        // wait: a sped-up copy that is still converting is waited for here, with the players already in place.
        if (!setupArena(battle, level, leftEntity, rightEntity)) {
            finishBattle(battle, Component.translatable("music_battle.battle.no_safe_placement").withStyle(ChatFormatting.RED));
            return;
        }

        float leftSpeed = leftEntity instanceof ServerPlayer leftPlayer
                ? playerSpeed(leftPlayer.getUUID())
                : mobSpeed(battle);
        PanelStyle leftPanelStyle = leftEntity instanceof ServerPlayer leftPlayer
                ? playerPanelStyle(leftPlayer.getUUID())
                : mobPanelStyle(battle);
        battle.leftState = new ParticipantState(
                0,
                leftEntity instanceof ServerPlayer leftPlayer ? leftPlayer.getUUID() : null,
                leftEntity.getId(),
                leftSpeed,
                leftEntity instanceof ServerPlayer leftPlayer ? playerColor(leftPlayer.getUUID()) : randomMobColor(battle.random),
                leftEntity instanceof ServerPlayer leftPlayer ? keyLabels(leftPlayer.getUUID()) : DEFAULT_KEY_LABELS,
                !(leftEntity instanceof ServerPlayer),
                leftPanelStyle,
                leftEntity instanceof ServerPlayer leftPlayer ? playerAppearance(leftPlayer.getUUID()) : NoteAppearance.pastel(battle.random)
        );
        if (rightEntity instanceof ServerPlayer rightPlayer) {
            battle.rightState = new ParticipantState(
                    1,
                    rightPlayer.getUUID(),
                    rightPlayer.getId(),
                    playerSpeed(rightPlayer.getUUID()),
                    playerColor(rightPlayer.getUUID()),
                    keyLabels(rightPlayer.getUUID()),
                    false,
                    playerPanelStyle(rightPlayer.getUUID()),
                    playerAppearance(rightPlayer.getUUID())
            );
        } else {
            battle.rightState = new ParticipantState(1, null, rightEntity.getId(), mobSpeed(battle), randomMobColor(battle.random), DEFAULT_KEY_LABELS, true, mobPanelStyle(battle), NoteAppearance.pastel(battle.random));
        }

        prepareBattleTransfer(battle);
    }

    /**
     * Picks the audio, scales the chart to the speed it really plays at and sends both to everybody who needs them.
     *
     * <p>Called once by {@link #beginPlaying}, and again from the battle tick while a sped-up copy that is being
     * converted is waited for. It never waits itself: when the copy is on its way and the wait has time left, it
     * marks the battle as waiting and returns, so the server thread never stands still for ffmpeg.
     */
    private static void prepareBattleTransfer(Battle battle) {
        MusicBattleTracks.Track track = battle.track;
        if (track == null || battle.phase != Phase.PREPARING) {
            return;
        }
        // The audio is chosen before anything is scaled to the speed, because which file exists is what decides
        // whether that speed can be played at all. Speeding a track up needs ffmpeg and a conversion that the
        // vote asked for in the background; when the result is not there the battle drops to normal speed and
        // says so, instead of ending a duel the two of them already agreed to.
        MusicBattleTracks.BattleAudio audio = track.audioFile().isBlank()
                ? new MusicBattleTracks.BattleAudio(null, 1.0F, null, null)
                : MusicBattleTracks.battleAudio(track, normalizeSpeedMultiplier(battle.speedMultiplier));
        if (audio.refusal() == MusicBattleTracks.SpeedRefusal.PREPARING
                && battle.speedAudioWaitTicks < SPEED_AUDIO_WAIT_TICKS) {
            if (!battle.awaitingSpeedAudio) {
                battle.awaitingSpeedAudio = true;
                showSpeedAudioWait(battle);
            }
            return;
        }
        battle.awaitingSpeedAudio = false;
        if (audio.speedRefused()) {
            String requestedSpeed = formatSpeedMultiplier(normalizeSpeedMultiplier(battle.speedMultiplier));
            MusicBattleMod.LOGGER.warn("Blocky Music battle '{}' falls back to normal speed ({}): {}",
                    battle.id, audio.refusal(), audio.detail() == null ? "not prepared in time" : audio.detail());
            Component refused = Component.translatable(audio.refusal().messageKey(), requestedSpeed)
                    .withStyle(ChatFormatting.GOLD);
            for (ServerPlayer player : battle.players()) {
                player.sendSystemMessage(refused);
            }
            // The chart, the BPM and the start packet all follow the file that is really going to play.
            battle.speedMultiplier = audio.speed();
        }
        float speedMultiplier = normalizeSpeedMultiplier(battle.speedMultiplier);
        battle.durationTicks = scaledTick(track.durationTicks(), speedMultiplier);
        battle.effectiveBpm = Math.max(20, Math.min(800, Math.round(track.bpm() * speedMultiplier)));
        battle.effectiveOffsetMs = ChartBeat.effectiveOffsetMs(track.offsetMs(), speedMultiplier);
        battle.runtimeNotes.clear();
        for (BattleNote note : runtimeNoteOrder(track.notes(), battle.solo, battle.unifiedNotes, speedMultiplier)) {
            // The number a note is judged by is its place in this list and nothing else. It used to be worked out
            // per branch - i for one chart, i*2 and i*2+1 for a shared one - which happened to agree with the
            // position and left nothing to say so.
            battle.runtimeNotes.add(new RuntimeNote(battle.runtimeNotes.size(), note));
        }
        battle.runtimeEffects.clear();
        for (TrackEffect effect : track.effects()) {
            battle.runtimeEffects.add(new TrackEffect(
                    effect.type(),
                    effect.lane(),
                    effect.layer(),
                    scaledTick(effect.tick(), speedMultiplier),
                    scaledTick(effect.durationTicks(), speedMultiplier),
                    effect.intensity(),
                    effect.softness(), effect.colorA(), effect.colorB(), effect.text(), effect.font(),
                    effect.positionX(), effect.positionY(), effect.animation(), effect.exitAnimation(), effect.variant(), effect.beatReactive(),
                    effect.opacity(), effect.textSpeed(), effect.outlineThickness(), effect.glowStrength(), effect.settings()
            ));
        }

        byte[] chartBytes = runtimeChartBytes(battle, track);
        if (chartBytes.length <= 0 || chartBytes.length > MAX_CHART_ASSET_BYTES) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.chart_invalid"), false);
            return;
        }
        String chartHash = sha256(chartBytes);
        String chartFile = transferFileName(battle.runtimeChartFile);

        Optional<MusicBattleTracks.AudioAsset> audioAsset = Optional.ofNullable(audio.asset());
        if (!track.audioFile().isBlank() && audioAsset.isEmpty()) {
            // Not the speed: the map names an audio file that is not there to send at any speed.
            finishBattle(battle, Component.translatable("music_battle.l10n.server.audio_missing"), false);
            return;
        }
        battle.runtimeAudioFile = audioAsset.map(MusicBattleTracks.AudioAsset::fileName).orElse("");
        String audioFile = transferFileName(battle.runtimeAudioFile);
        if (audioAsset.isPresent() && !validOutgoingAsset("audio", audioFile, audioAsset.get().path())) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.audio_unsafe"), false);
            return;
        }
        int audioBytes = audioAsset.map(asset -> assetBytes(asset.path())).orElse(0);
        String audioHash = "";
        try {
            if (audioAsset.isPresent()) {
                audioHash = sha256(audioAsset.get().path());
            }
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to hash music battle audio '{}'.", audioAsset.get().path(), exception);
            finishBattle(battle, Component.translatable("music_battle.l10n.server.audio_check_failed"), false);
            return;
        }
        battle.preparedTransfer = new PreparedTrackTransfer(
                chartBytes, chartFile, chartHash, audioFile, audioBytes, audioHash, audioAsset.orElse(null));
        for (ServerPlayer receiver : battle.players()) {
            addPreloadRecipient(battle, receiver, false);
        }
        refreshPreparingSpectators(battle);
        if (battle.allPreloadRecipientsReady()) {
            startPreparedBattle(battle);
        }
    }

    private static void refreshPreparingSpectators(Battle battle) {
        if (battle.phase != Phase.PREPARING || battle.preparedTransfer == null
                || battle.leftAnchor == null || battle.rightAnchor == null
                || !(battle.leftEntity.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = battle.leftAnchor.add(battle.rightAnchor).scale(0.5D);
        battle.activeSpeakers = SpeakerNetwork.connectedSpeakers(level, center, SPECTATOR_MUSIC_DISTANCE);

        Set<UUID> audibleSpectators = new HashSet<>();
        if (MusicBattleServerConfig.waitForSpectatorDownloads()) {
            for (ServerPlayer player : level.players()) {
                if (!battle.hasPlayer(player.getUUID())
                        && !battle.preloadFailedSpectatorIds.contains(player.getUUID())
                        && nearestAudioSource(player, center, battle.activeSpeakers) != null) {
                    audibleSpectators.add(player.getUUID());
                    addPreloadRecipient(battle, player, true);
                }
            }
        }

        for (UUID spectatorId : new HashSet<>(battle.preloadSpectatorIds)) {
            if (audibleSpectators.contains(spectatorId)) {
                continue;
            }
            battle.preloadSpectatorIds.remove(spectatorId);
            battle.preloadRecipientIds.remove(spectatorId);
            battle.readyPlayers.remove(spectatorId);
            ServerPlayer spectator = level.getServer().getPlayerList().getPlayer(spectatorId);
            if (spectator != null) {
                PacketDistributor.sendToPlayer(spectator, new SpectatorStopPayload(battle.id));
            }
        }
    }

    private static void addPreloadRecipient(Battle battle, ServerPlayer receiver, boolean spectator) {
        PreparedTrackTransfer transfer = battle.preparedTransfer;
        MusicBattleTracks.Track track = battle.track;
        if (transfer == null || track == null || !battle.preloadRecipientIds.add(receiver.getUUID())) {
            return;
        }
        battle.readyPlayers.remove(receiver.getUUID());
        if (spectator) {
            battle.preloadSpectatorIds.add(receiver.getUUID());
        }
        PacketDistributor.sendToPlayer(receiver, new TrackPreloadPayload(
                battle.id,
                track.id(),
                track.title(),
                transfer.chartFile(),
                transfer.chartBytes().length,
                transfer.chartHash(),
                transfer.audioFile(),
                transfer.audioBytes(),
                transfer.audioHash()
        ));
        sendAssetBytes(receiver, track.id(), "chart", transfer.chartFile(), transfer.chartBytes());
        if (transfer.audioAsset() != null) {
            sendAssetAsync(receiver, track.id(), "audio", transfer.audioAsset().fileName(), transfer.audioAsset().path());
        }
        receiver.displayClientMessage(Component.translatable("music_battle.l10n.server.track_loading", track.title())
                .withStyle(ChatFormatting.AQUA), true);
    }

    /**
     * How long a packet takes to reach this player, in milliseconds.
     *
     * Half the round trip the server already measures for every connection. It is a rolling average rather than a
     * fresh measurement, which is accurate enough here: it is corrected by the lead, and the client re-seeks its
     * audio to the shared clock afterwards anyway. Clamped so that one wild sample cannot push a battle a second
     * into the future.
     */
    private static int oneWayDelayMillis(ServerPlayer player) {
        return BattleClock.oneWayMillis(player.connection == null ? 0 : player.connection.latency());
    }

    private static void startPreparedBattle(Battle battle) {
        if (!BATTLES.containsKey(battle.id) || battle.track == null) {
            return;
        }
        refreshPreparingSpectators(battle);
        if (!battle.allPreloadRecipientsReady()) {
            return;
        }
        LivingEntity leftEntity = battle.leftEntity();
        LivingEntity rightEntity = battle.rightEntity();
        if (leftEntity == null || rightEntity == null || !leftEntity.isAlive() || !rightEntity.isAlive()) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.participant_missing"), false);
            return;
        }
        if (!(leftEntity.level() instanceof ServerLevel level) || rightEntity.level() != level) {
            finishBattle(battle, Component.translatable("music_battle.l10n.server.participants_split"), false);
            return;
        }

        MusicBattleTracks.Track track = battle.track;
        battle.phase = Phase.PLAYING;

        // One instant for everybody. The lead has to cover the slowest player's packet plus the time their client
        // needs to open the audio file, so it is generous on purpose; each player is then told to wait the lead
        // minus their own one-way delay, which is what makes the countdowns line up rather than merely look alike.
        List<ServerPlayer> receivers = battle.players();
        Map<UUID, Integer> oneWayMillis = new HashMap<>();
        int worstOneWay = 0;
        for (ServerPlayer receiver : receivers) {
            int oneWay = oneWayDelayMillis(receiver);
            oneWayMillis.put(receiver.getUUID(), oneWay);
            worstOneWay = Math.max(worstOneWay, oneWay);
        }
        int leadMillis = Math.max(MUSIC_SYNC_LEAD_TICKS * 50, BattleClock.leadMillis(worstOneWay));
        // Battle time, not wall time: see BattlePauseClock for the single-player pause it leaves out.
        battle.startEpochNanos = BattlePauseClock.GAME.now() + leadMillis * 1_000_000L;
        battle.playTicks = -(leadMillis / 50);
        MusicBattleMod.LOGGER.info("Starting Blocky Music battle '{}' with track '{}' after all players finished downloading.", battle.id, track.id());

        for (ServerPlayer receiver : receivers) {
            receiver.connection.send(new ClientboundSetCameraPacket(battle.camera));
        }

        String audioFile = transferFileName(battle.runtimeAudioFile);
        String previewFile = transferFileName(track.previewFile());
        for (ServerPlayer receiver : receivers) {
            boolean leftSide = battle.playerOnePlayerId != null && receiver.getUUID().equals(battle.playerOnePlayerId);
            ParticipantState own = leftSide ? battle.leftState : battle.rightState;
            ParticipantState opponent = leftSide ? battle.rightState : battle.leftState;
            int startDelayMillis = BattleClock.startDelayMillis(leadMillis, oneWayMillis.getOrDefault(receiver.getUUID(), 50));
            PacketDistributor.sendToPlayer(receiver, new BattleStartPayload(
                    battle.id,
                    battle.durationTicks,
                    startDelayMillis,
                    battle.effectiveBpm,
                    track.id(),
                    track.title(),
                    track.description(),
                    track.volume(),
                    normalizeSpeedMultiplier(battle.speedMultiplier),
                    audioFile,
                    previewFile,
                    displayName(leftEntity),
                    battle.solo ? "" : displayName(rightEntity),
                    own.speed,
                    opponent.speed,
                    own.color,
                    opponent.color,
                    own.panelStyle.panelOpacity(),
                    own.panelStyle.laneOpacity(),
                    own.panelStyle.widthScale(),
                    own.panelStyle.heightScale(),
                    own.panelStyle.noteWidthScale(),
                    own.panelStyle.noteHeightScale(),
                    opponent.panelStyle.panelOpacity(),
                    opponent.panelStyle.laneOpacity(),
                    opponent.panelStyle.widthScale(),
                    opponent.panelStyle.heightScale(),
                    opponent.panelStyle.noteWidthScale(),
                    opponent.panelStyle.noteHeightScale(),
                    battle.leftState.speed,
                    battle.rightState.speed,
                    own.keyLabels,
                    opponent.keyLabels,
                    leftSide,
                    leftEntity.getId(),
                    battle.solo ? 0 : rightEntity.getId(),
                    battle.solo ? 0 : opponent.entityId,
                    battle.camera.getId(),
                    battle.cameraYaw,
                    battle.cameraPitch,
                    battle.cameraFov,
                    List.of(),
                    own.appearance,
                    opponent.appearance
            ));
        }
        Set<UUID> preloadedSpectators = Set.copyOf(battle.preloadSpectatorIds);
        updateSpectators(level, battle, track);
        for (UUID spectatorId : preloadedSpectators) {
            if (battle.spectatorIds.contains(spectatorId)) {
                continue;
            }
            ServerPlayer spectator = level.getServer().getPlayerList().getPlayer(spectatorId);
            if (spectator != null) {
                PacketDistributor.sendToPlayer(spectator, new SpectatorStopPayload(battle.id));
            }
        }
        battle.preloadSpectatorIds.clear();
        battle.preloadFailedSpectatorIds.clear();
        battle.preloadRecipientIds.clear();
        battle.readyPlayers.clear();
        battle.preparedTransfer = null;
    }

    private static boolean setupArena(Battle battle, ServerLevel level, LivingEntity leftEntity, LivingEntity rightEntity) {
        Vec3[] preferred = battle.solo ? soloArenaSpots(leftEntity) : arenaSpots(leftEntity, rightEntity);
        Vec3[] spots = BattleArenaPlacement.find(level, leftEntity, rightEntity, preferred, battle.solo);
        // Resolve and reserve BOTH full bodies before either participant is moved or locked.
        if (spots == null) return false;
        Vec3 leftPos = spots[0];
        Vec3 rightPos = spots[1];
        Vec3 center = leftPos.add(rightPos).scale(0.5D);
        Vec3 axis = rightPos.subtract(leftPos).multiply(1.0D, 0.0D, 1.0D);
        if (axis.lengthSqr() < 0.01D) {
            axis = Vec3.directionFromRotation(0.0F, leftEntity.getYRot()).multiply(1.0D, 0.0D, 1.0D);
        }
        axis = axis.normalize();
        Vec3 cameraAxis = new Vec3(-axis.z, 0.0D, axis.x).normalize();
        float leftYaw = yawToFace(leftPos, rightPos);
        float rightYaw = yawToFace(rightPos, leftPos);
        float leftPitch = pitchToFace(focusPoint(leftEntity, leftPos), focusPoint(rightEntity, rightPos));
        float rightPitch = pitchToFace(focusPoint(rightEntity, rightPos), focusPoint(leftEntity, leftPos));
        if (leftEntity instanceof ServerPlayer leftPlayer) {
            leftPlayer.teleportTo(level, leftPos.x, leftPos.y, leftPos.z, leftYaw, leftPitch);
        } else {
            leftEntity.moveTo(leftPos.x, leftPos.y, leftPos.z, leftYaw, leftPitch);
            if (leftEntity instanceof Mob mob) {
                lockMobForBattle(mob);
            }
        }
        if (rightEntity instanceof ServerPlayer rightPlayer) {
            rightPlayer.teleportTo(level, rightPos.x, rightPos.y, rightPos.z, rightYaw, rightPitch);
        } else {
            rightEntity.moveTo(rightPos.x, rightPos.y, rightPos.z, rightYaw, rightPitch);
            if (rightEntity instanceof Mob mob) {
                lockMobForBattle(mob);
            }
        }

        Vec3 lookAt = focusPoint(leftEntity, leftPos).add(focusPoint(rightEntity, rightPos)).scale(0.5D);
        Optional<BattleCameraPlacement> fixedCamera = findNearbyBattleCamera(level, center);
        Vec3 cameraPos = fixedCamera.map(BattleCameraPlacement::position).orElseGet(() -> findCameraPosition(level, center, cameraAxis, lookAt));
        // Every shot opens near the arena centre and dollies out along the line to its own position,
        // a placed camera block included: it used to open above the left fighter and glide across.
        Vec3 cameraStart = introCameraStart(center, cameraPos);
        float cameraYaw = fixedCamera.map(BattleCameraPlacement::yaw).orElseGet(() -> yawToFace(cameraPos, lookAt));
        float cameraPitch = fixedCamera.map(BattleCameraPlacement::pitch).orElseGet(() -> pitchToFace(cameraPos, lookAt));
        float cameraFov = fixedCamera.map(BattleCameraPlacement::fov).orElse(0.0F);
        ArmorStand camera = new ArmorStand(level, cameraStart.x, cameraStart.y, cameraStart.z);
        TemporaryBattleEntities.mark(camera);
        camera.setInvisible(true);
        camera.setNoGravity(true);
        camera.setInvulnerable(true);
        camera.setSilent(true);
        // Dynamic cameras historically store ArmorStand feet. A fixed block
        // camera stores an optical point, so promote the intro start to the
        // same coordinate space while keeping the original opening frame.
        Vec3 storedCameraStart = fixedCamera.isPresent()
                ? cameraStart.add(0.0D, camera.getEyeHeight(), 0.0D) : cameraStart;
        Vec3 initialCameraEntityPosition = fixedCamera.isPresent()
                ? cameraEntityPosition(camera, storedCameraStart) : cameraStart;
        camera.absMoveTo(initialCameraEntityPosition.x, initialCameraEntityPosition.y,
                initialCameraEntityPosition.z, cameraYaw, cameraPitch);
        level.addFreshEntity(camera);

        battle.leftAnchor = leftPos;
        battle.rightAnchor = rightPos;
        battle.leftYaw = leftYaw;
        battle.rightYaw = rightYaw;
        battle.camera = camera;
        battle.cameraStart = storedCameraStart;
        battle.cameraTarget = cameraPos;
        battle.cameraCenter = lookAt.subtract(0.0D, 1.22D, 0.0D);
        battle.fixedCamera = fixedCamera.isPresent();
        // Phase 0 or π: the swing starts from the centred target the intro ends on, in a random
        // direction, instead of jumping sideways on the first tick of the song.
        battle.cameraOrbitPhase = battle.random.nextBoolean() ? 0.0D : Math.PI;
        battle.cameraOrbitAmplitude = 0.12D + battle.random.nextDouble() * 0.12D;
        battle.cameraOrbitSpeed = 52.0D + battle.random.nextDouble() * 42.0D;
        battle.cameraOrbitVertical = 0.05D + battle.random.nextDouble() * 0.08D;
        battle.leftStatus = null;
        battle.rightStatus = null;
        battle.cameraYaw = cameraYaw;
        battle.cameraPitch = cameraPitch;
        battle.cameraFov = cameraFov;
        battle.fixedCameraBlockPos = fixedCamera.map(BattleCameraPlacement::blockPos).orElse(null);
        battle.arenaCameraFallback = new BattleCameraPlacement(battle.fixedCameraBlockPos, cameraPos, cameraYaw, cameraPitch, cameraFov);
        Vec3 dynamicPosition = fixedCamera.isEmpty() ? cameraPos : findCameraPosition(level, center, cameraAxis, lookAt);
        battle.dynamicCameraFallback = new BattleCameraPlacement(null, dynamicPosition,
                yawToFace(dynamicPosition, lookAt), pitchToFace(dynamicPosition, lookAt), 0);
        battle.lastBroadcastCamera = battle.arenaCameraFallback;
        return true;
    }

    /** Explicit selection is the only action that changes booth priority; edits only refresh its transform. */
    public static void refreshDjBattleCameras(ServerLevel level) {
        for (Battle battle : BATTLES.values()) {
            if (battle.leftEntity.level() == level) refreshDjBattleCamera(battle);
        }
    }

    private static void refreshDjBattleCamera(Battle battle) {
        if (battle.camera == null || battle.camera.isRemoved() || battle.leftAnchor == null || battle.rightAnchor == null
                || !(battle.camera.level() instanceof ServerLevel level) || battle.arenaCameraFallback == null) return;
        Vec3 center = battle.leftAnchor.add(battle.rightAnchor).scale(0.5D);
        BlockPos origin = BlockPos.containing(center);
        Set<BlockPos> speakers = SpeakerNetwork.connectedSpeakers(level, center, SPECTATOR_MUSIC_DISTANCE);
        java.util.function.Predicate<BlockPos> inBattleArea = pos ->
                Math.abs(pos.getX() - origin.getX()) <= BATTLE_CAMERA_RADIUS
                && Math.abs(pos.getY() - origin.getY()) <= 8
                && Math.abs(pos.getZ() - origin.getZ()) <= BATTLE_CAMERA_RADIUS
                || insideConnectedSpeakerCoverage(level, pos, speakers);
        BlockPos selected = DjPlaybackManager.selectedBattleCamera(level, inBattleArea);
        if (selected == null && battle.arenaCameraFallback.blockPos() != null
                && level.hasChunkAt(battle.arenaCameraFallback.blockPos())
                && level.getBlockEntity(battle.arenaCameraFallback.blockPos()) instanceof BattleCameraBlockEntity
                && inBattleArea.test(battle.arenaCameraFallback.blockPos())) selected = battle.arenaCameraFallback.blockPos();
        BattleCameraPlacement desired = battle.dynamicCameraFallback;
        if (selected != null && level.getBlockEntity(selected) instanceof BattleCameraBlockEntity camera) {
            desired = new BattleCameraPlacement(selected, camera.getCameraPosition(), camera.getCameraYaw(), camera.getCameraPitch(), camera.getCameraFov());
        }
        if (desired == null || desired.equals(battle.lastBroadcastCamera)) return;
        battle.lastBroadcastCamera = desired;
        battle.fixedCamera = desired.blockPos() != null;
        battle.fixedCameraBlockPos = desired.blockPos();
        battle.cameraTarget = desired.position();
        battle.cameraStart = desired.position();
        battle.cameraYaw = desired.yaw();
        battle.cameraPitch = desired.pitch();
        battle.cameraFov = desired.fov();
        Vec3 entityPosition = battle.fixedCamera ? cameraEntityPosition(battle.camera, desired.position()) : desired.position();
        battle.camera.absMoveTo(entityPosition.x, entityPosition.y, entityPosition.z, desired.yaw(), desired.pitch());
        Vec3 optical = entityPosition.add(0, battle.camera.getEyeHeight(), 0);
        BattleCameraSyncPayload update = new BattleCameraSyncPayload(battle.id, battle.camera.getId(),
                optical.x, optical.y, optical.z, desired.yaw(), desired.pitch(), desired.fov(), ++battle.cameraSyncRevision);
        for (ServerPlayer watcher : battle.watchers()) PacketDistributor.sendToPlayer(watcher, update);
    }

    private static Vec3 focusPoint(LivingEntity entity, Vec3 anchor) {
        double height = entity instanceof EnderDragon ? 3.2D : Mth.clamp(entity.getBbHeight() * 0.62D, 1.05D, 2.25D);
        return anchor.add(0.0D, height, 0.0D);
    }

    /** Opening frame of a battle camera, at the camera's own height; see {@link BattlePlacementMath#introOffset}. */
    private static Vec3 introCameraStart(Vec3 center, Vec3 cameraPos) {
        BattlePlacementMath.Offset step = BattlePlacementMath.introOffset(cameraPos.x - center.x, cameraPos.z - center.z);
        return new Vec3(center.x + step.x(), cameraPos.y, center.z + step.z());
    }

    private static Vec3 findCameraPosition(ServerLevel level, Vec3 center, Vec3 preferredAxis, Vec3 lookAt) {
        List<Vec3> axes = new ArrayList<>();
        for (BattlePlacementMath.Direction direction : BattlePlacementMath.cameraDirections(preferredAxis.x, preferredAxis.z)) {
            axes.add(new Vec3(direction.x(), 0.0D, direction.z()));
        }
        double[] distances = {3.6D, 4.2D, 4.8D};
        double[] heights = {1.00D, 1.25D, 1.50D};
        for (Vec3 axis : axes) {
            for (double distance : distances) {
                for (double height : heights) {
                    Vec3 candidate = center.add(axis.scale(distance)).add(0.0D, height, 0.0D);
                    if (hasCameraSpace(level, candidate) && hasLineOfSight(level, candidate, lookAt)) {
                        return candidate;
                    }
                }
            }
        }
        return findFallbackCameraPosition(level, center, preferredAxis, lookAt);
    }

    private static Optional<BattleCameraPlacement> findNearbyBattleCamera(ServerLevel level, Vec3 center) {
        BlockPos origin = BlockPos.containing(center);
        Set<BlockPos> candidates = new HashSet<>(BattleCameraNetwork.loadedPositions(level));
        // Preserve the original bounded lookup as a fallback for a camera that
        // was placed in the same tick and has not reached BlockEntity#onLoad yet.
        for (BlockPos candidate : BlockPos.betweenClosed(
                origin.offset(-BATTLE_CAMERA_RADIUS, -8, -BATTLE_CAMERA_RADIUS),
                origin.offset(BATTLE_CAMERA_RADIUS, 8, BATTLE_CAMERA_RADIUS))) {
            if (level.getBlockState(candidate).is(ModBlocks.BATTLE_CAMERA.get())) {
                candidates.add(candidate.immutable());
            }
        }

        Set<BlockPos> connectedSpeakers = SpeakerNetwork.connectedSpeakers(level, center, SPECTATOR_MUSIC_DISTANCE);
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            BlockState state = level.getBlockState(candidate);
            if (!state.is(ModBlocks.BATTLE_CAMERA.get())) {
                continue;
            }
            boolean inCoreRange = Math.abs(candidate.getX() - origin.getX()) <= BATTLE_CAMERA_RADIUS
                    && Math.abs(candidate.getY() - origin.getY()) <= 8
                    && Math.abs(candidate.getZ() - origin.getZ()) <= BATTLE_CAMERA_RADIUS;
            if (!inCoreRange && !insideConnectedSpeakerCoverage(level, candidate, connectedSpeakers)) {
                continue;
            }
            double distance = candidate.distToCenterSqr(center.x, center.y, center.z);
            if (distance < closestDistance
                    || distance == closestDistance && (closest == null || candidate.asLong() < closest.asLong())) {
                closestDistance = distance;
                closest = candidate.immutable();
            }
        }
        if (closest == null) {
            return Optional.empty();
        }
        if (level.getBlockEntity(closest) instanceof BattleCameraBlockEntity camera) {
            return Optional.of(new BattleCameraPlacement(
                    closest,
                    camera.getCameraPosition(),
                    camera.getCameraYaw(),
                    camera.getCameraPitch(),
                    camera.getCameraFov()
            ));
        }
        Direction facing = level.getBlockState(closest).getValue(BattleCameraBlock.FACING);
        Vec3 direction = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        Vec3 position = Vec3.atCenterOf(closest).add(direction.scale(0.58D));
        return Optional.of(new BattleCameraPlacement(closest, position, facing.toYRot(), 0.0F, BattleCameraBlockEntity.DEFAULT_FOV));
    }

    private static boolean insideConnectedSpeakerCoverage(ServerLevel level, BlockPos cameraPos,
                                                           Set<BlockPos> connectedSpeakers) {
        Vec3 cameraCenter = Vec3.atCenterOf(cameraPos);
        for (BlockPos speakerPos : connectedSpeakers) {
            float radius = SpeakerNetwork.soundRadius(level, speakerPos);
            if (cameraCenter.distanceToSqr(Vec3.atCenterOf(speakerPos)) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCameraSpace(ServerLevel level, Vec3 cameraPos) {
        AABB cameraBox = new AABB(
                cameraPos.x - 0.28D,
                cameraPos.y - 0.24D,
                cameraPos.z - 0.28D,
                cameraPos.x + 0.28D,
                cameraPos.y + 0.42D,
                cameraPos.z + 0.28D
        );
        return level.noCollision(cameraBox);
    }

    private static boolean hasLineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        if (!hasCameraSpace(level, from)) {
            return false;
        }
        BlockHitResult result = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (result.getType() != HitResult.Type.MISS) {
            return false;
        }
        Vec3 step = to.subtract(from).scale(1.0D / 5.0D);
        for (int i = 1; i < 5; i++) {
            if (!hasCameraSpace(level, from.add(step.scale(i)))) {
                return false;
            }
        }
        return true;
    }

    private static Vec3 findFallbackCameraPosition(ServerLevel level, Vec3 center, Vec3 preferredAxis, Vec3 lookAt) {
        // Both sides of the bisector stay centred between the fighters, so the opposite side is
        // always tried before the shot is allowed to collapse onto the arena itself.
        List<Vec3> axes = List.of(preferredAxis, preferredAxis.scale(-1.0D));
        for (double distance = 4.2D; distance >= 2.2D; distance -= 0.45D) {
            for (double height = 2.4D; height >= 1.1D; height -= 0.35D) {
                for (Vec3 axis : axes) {
                    Vec3 candidate = center.add(axis.scale(distance)).add(0.0D, height, 0.0D);
                    if (hasCameraSpace(level, candidate) && hasLineOfSight(level, candidate, lookAt)) {
                        return candidate;
                    }
                }
            }
        }
        for (Vec3 axis : axes) {
            Vec3 compact = center.add(axis.scale(2.2D)).add(0.0D, 1.35D, 0.0D);
            if (hasCameraSpace(level, compact)) {
                return compact;
            }
        }
        return lookAt.add(0.0D, 0.65D, 0.0D);
    }

    private static void sendTrackAssets(ServerPlayer player, MusicBattleTracks.Track track) {
        MusicBattleTracks.audioPath(track).ifPresent(path -> sendAsset(player, track.id(), "audio", track.audioFile(), path));
        MusicBattleTracks.previewPath(track).ifPresent(path -> sendAsset(player, track.id(), "preview", track.previewFile(), path));
    }

    private static void sendTrackAssets(ServerPlayer player, MusicBattleTracks.Track track, MusicBattleTracks.AudioAsset audioAsset) {
        sendAssetAsync(player, track.id(), "audio", audioAsset.fileName(), audioAsset.path());
        MusicBattleTracks.previewPath(track).ifPresent(path -> sendAssetAsync(player, track.id(), "preview", track.previewFile(), path));
    }

    private static void sendPauseSkipStatus(Battle battle, boolean available, int targetTick, boolean applied) {
        int required = battle.requiredPauseSkipVotes();
        int votes = Math.min(required, battle.pauseSkipVotes.size());
        // Availability/voting belongs to participants, but an applied timeline
        // jump must also reach spectators. Otherwise their audio/visual clock
        // remains in the skipped rest while AI actions already arrive from the
        // later server tick.
        List<ServerPlayer> receivers = applied ? battle.watchers() : battle.players();
        for (ServerPlayer player : receivers) {
            PacketDistributor.sendToPlayer(player, new PauseSkipStatusPayload(battle.id, available, votes, required, Math.max(0, targetTick), applied));
        }
    }

    private static void sendAsset(ServerPlayer player, String trackId, String kind, String fileName, Path path) {
        if (!validOutgoingAsset(kind, transferFileName(fileName), path)) {
            MusicBattleMod.LOGGER.warn("Refused unsafe or oversized Blocky Music {} asset '{}'.", kind, path);
            return;
        }
        try {
            sendAssetBytes(player, trackId, kind, fileName, Files.readAllBytes(path));
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to send music battle asset '{}' to {}.", path, player.getGameProfile().getName(), exception);
        }
    }

    private static void sendAssetAsync(ServerPlayer player, String trackId, String kind, String fileName, Path path) {
        if (!validOutgoingAsset(kind, transferFileName(fileName), path)) {
            MusicBattleMod.LOGGER.warn("Refused unsafe or oversized Blocky Music {} asset '{}'.", kind, path);
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readAllBytes(path);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, Util.nonCriticalIoPool()).thenAccept(bytes -> player.server.execute(() -> {
            if (player.connection != null) {
                sendAssetBytes(player, trackId, kind, fileName, bytes);
            }
        })).exceptionally(exception -> {
            MusicBattleMod.LOGGER.warn("Unable to send music battle asset '{}' to {}.", path, player.getGameProfile().getName(), exception);
            return null;
        });
    }

    /**
     * Offers the file before pushing it (spec 06 section 9.4): a player who already owns exactly these bytes — from
     * the Workshop, or from an earlier battle — answers {@code have} and nothing is sent. An answer that never
     * arrives is not allowed to stall the battle: {@link #ASSET_OFFER_TIMEOUT_TICKS} later the bytes go out anyway.
     */
    private static void sendAssetBytes(ServerPlayer player, String trackId, String kind, String fileName, byte[] bytes) {
        String transferFileName = transferFileName(fileName);
        long maxBytes = maxAssetBytes(kind);
        if (maxBytes <= 0L || bytes.length <= 0 || bytes.length > maxBytes || !validAssetFileName(kind, transferFileName)) {
            MusicBattleMod.LOGGER.warn("Refused invalid Blocky Music {} payload '{}' ({} bytes).", kind, transferFileName, bytes.length);
            return;
        }
        String hash = sha256(bytes);
        UUID battleId = BATTLE_BY_PLAYER.get(player.getUUID());
        if (battleId != null) {
            String key = assetKey(player.getUUID(), trackId, kind, transferFileName, hash);
            PENDING_ASSETS.put(key, new PendingAsset(player.getUUID(), trackId, kind, transferFileName, hash, bytes,
                    player.server.getTickCount()));
            PacketDistributor.sendToPlayer(player, new TrackAssetOfferPayload(battleId, trackId, kind, transferFileName, hash));
            return;
        }
        pushAssetBytes(player, trackId, kind, transferFileName, hash, bytes);
    }

    /** The answer to an asset offer. {@code have} skips the push entirely. */
    public static void confirmTrackAssetAnswer(ServerPlayer player, TrackAssetAnswerPayload payload) {
        String key = assetKey(player.getUUID(), payload.trackId(), payload.assetKind(), payload.fileName(), payload.sha256());
        PendingAsset pending = PENDING_ASSETS.remove(key);
        if (pending == null) {
            return;
        }
        if (payload.have()) {
            MusicBattleMod.LOGGER.debug("{} already holds Blocky Music {} '{}'; skipping the transfer.",
                    player.getGameProfile().getName(), pending.kind(), pending.fileName());
            return;
        }
        pushAssetBytes(player, pending.trackId(), pending.kind(), pending.fileName(), pending.sha256(), pending.bytes());
    }

    /** A client that never answered an offer still gets its file; nothing waits on a silent client. */
    private static void flushExpiredAssetOffers(MinecraftServer server) {
        if (PENDING_ASSETS.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (PendingAsset pending : new ArrayList<>(PENDING_ASSETS.values())) {
            if (now - pending.offeredAtTick() < ASSET_OFFER_TIMEOUT_TICKS) {
                continue;
            }
            PENDING_ASSETS.remove(assetKey(pending.playerId(), pending.trackId(), pending.kind(), pending.fileName(),
                    pending.sha256()));
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player != null && player.connection != null) {
                pushAssetBytes(player, pending.trackId(), pending.kind(), pending.fileName(), pending.sha256(), pending.bytes());
            }
        }
    }

    private static String assetKey(UUID playerId, String trackId, String kind, String fileName, String sha256) {
        return playerId + "/" + trackId + "/" + kind + "/" + fileName + "/" + sha256;
    }

    private record PendingAsset(UUID playerId, String trackId, String kind, String fileName, String sha256,
                                byte[] bytes, int offeredAtTick) {
    }

    private static void pushAssetBytes(ServerPlayer player, String trackId, String kind, String transferFileName,
                                       String hash, byte[] bytes) {
        int totalChunks = Math.max(1, (bytes.length + ASSET_CHUNK_BYTES - 1) / ASSET_CHUNK_BYTES);
        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int start = chunkIndex * ASSET_CHUNK_BYTES;
            int end = Math.min(bytes.length, start + ASSET_CHUNK_BYTES);
            PacketDistributor.sendToPlayer(player, new TrackAssetChunkPayload(
                    trackId,
                    kind,
                    transferFileName,
                    chunkIndex,
                    totalChunks,
                    bytes.length,
                    hash,
                    Arrays.copyOfRange(bytes, start, end)
            ));
        }
        if ("audio".equals(kind)) {
            MusicBattleMod.LOGGER.info("Sent Blocky Music audio '{}' for track '{}' to {} ({} bytes, {} chunks).", transferFileName, trackId, player.getGameProfile().getName(), bytes.length, totalChunks);
        }
    }

    private static String transferFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String normalized = fileName.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private static boolean validOutgoingAsset(String kind, String fileName, Path path) {
        long maxBytes = maxAssetBytes(kind);
        if (path == null || maxBytes <= 0L || !validAssetFileName(kind, fileName)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            long size = Files.size(path);
            return size > 0L && size <= maxBytes;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean validAssetFileName(String kind, String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]{1,128}")) {
            return false;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "audio" -> lower.endsWith(".ogg");
            case "preview" -> lower.endsWith(".png");
            case "chart" -> lower.endsWith(".json");
            default -> false;
        };
    }

    private static long maxAssetBytes(String kind) {
        return switch (kind) {
            case "audio" -> MAX_AUDIO_ASSET_BYTES;
            case "preview" -> MAX_PREVIEW_ASSET_BYTES;
            case "chart" -> MAX_CHART_ASSET_BYTES;
            default -> -1L;
        };
    }

    private static String transferBaseName(String value) {
        String safe = value == null ? "track" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (safe.isBlank()) {
            safe = "track";
        }
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    /**
     * One note of a runtime chart, carrying the number this battle judges it by.
     *
     * <p>The number used to be left implicit in the order of the array, and the client read it back as the
     * position in the list it had built - after sorting that list, which gave the same note two different
     * numbers on the two sides and dropped every hit on it. Now the number travels with the note.
     *
     * <p>Visible so {@code tools/BattleReliabilityChecks} writes its charts with the real writer.
     */
    public static JsonObject chartNote(int index, BattleNote note) {
        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("owner", note.owner());
        json.addProperty("lane", note.lane());
        json.addProperty("tick", note.tick());
        json.addProperty("durationTicks", note.durationTicks());
        return json;
    }

    private static byte[] runtimeChartBytes(Battle battle, MusicBattleTracks.Track track) {
        JsonObject root = new JsonObject();
        root.addProperty("id", track.id());
        root.addProperty("bpm", battle.effectiveBpm);
        // On the battle's own timeline, which a faster speed has already compressed like every note tick.
        ChartBeat.writeOffsetMs(root, battle.effectiveOffsetMs);
        root.addProperty("durationTicks", battle.durationTicks);
        root.addProperty("disableStageBars", track.disableStageBars());
        root.addProperty("hideStandardDimming", track.hideStandardDimming());
        root.addProperty("fxRowsPerLayer", TrackEffect.FX_ROWS_PER_LAYER);
        JsonArray notes = new JsonArray();
        for (RuntimeNote runtimeNote : battle.runtimeNotes) {
            notes.add(chartNote(runtimeNote.index, runtimeNote.note));
        }
        root.add("notes", notes);
        JsonArray effects = new JsonArray();
        for (TrackEffect runtimeEffect : battle.runtimeEffects) {
            JsonObject effect = new JsonObject();
            effect.addProperty("type", runtimeEffect.type().id());
            effect.addProperty("lane", runtimeEffect.lane());
            effect.addProperty("layer", runtimeEffect.layer());
            effect.addProperty("tick", runtimeEffect.tick());
            effect.addProperty("durationTicks", runtimeEffect.durationTicks());
            effect.addProperty("intensity", runtimeEffect.intensity());
            effect.addProperty("softness", runtimeEffect.softness());
            effect.addProperty("colorA", runtimeEffect.colorA());
            effect.addProperty("colorB", runtimeEffect.colorB());
            effect.addProperty("text", runtimeEffect.text());
            effect.addProperty("font", runtimeEffect.font());
            effect.addProperty("positionX", runtimeEffect.positionX());
            effect.addProperty("positionY", runtimeEffect.positionY());
            effect.addProperty("animation", runtimeEffect.animation());
            effect.addProperty("exitAnimation", runtimeEffect.exitAnimation());
            effect.addProperty("variant", runtimeEffect.variant());
            effect.addProperty("beatReactive", runtimeEffect.beatReactive());
            effect.addProperty("opacity", runtimeEffect.opacity());
            effect.addProperty("textSpeed", runtimeEffect.textSpeed());
            effect.addProperty("outlineThickness", runtimeEffect.outlineThickness());
            effect.addProperty("glowStrength", runtimeEffect.glowStrength());
            runtimeEffect.settings().writeJson(effect);
            effects.add(effect);
        }
        root.add("effects", effects);
        return root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int assetBytes(Path path) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Files.size(path)));
        } catch (IOException exception) {
            return 0;
        }
    }

    private static void updateSpectators(ServerLevel level, Battle battle, MusicBattleTracks.Track track) {
        if (battle.runtimeAudioFile.isBlank() || battle.leftAnchor == null || battle.rightAnchor == null) {
            return;
        }
        Vec3 center = battle.leftAnchor.add(battle.rightAnchor).scale(0.5D);
        battle.activeSpeakers = SpeakerNetwork.connectedSpeakers(level, center, SPECTATOR_MUSIC_DISTANCE);
        // Only what is already on disk: this runs inside the battle tick, and the battle has settled on a speed
        // whose file exists by now - it dropped to normal speed at the start if it did not.
        Optional<MusicBattleTracks.AudioAsset> audioAsset =
                MusicBattleTracks.preparedAudio(track, normalizeSpeedMultiplier(battle.speedMultiplier));
        if (audioAsset.isEmpty()) {
            return;
        }
        String audioHash = battle.preparedTransfer == null ? "" : battle.preparedTransfer.audioHash();
        if (audioHash.isBlank()) {
            try {
                audioHash = sha256(audioAsset.get().path());
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Unable to hash spectator music asset '{}'.", audioAsset.get().path(), exception);
                return;
            }
        }
        for (ServerPlayer player : level.players()) {
            if (battle.hasPlayer(player.getUUID())) {
                continue;
            }
            BattleAudioSource source = nearestAudioSource(player, center, battle.activeSpeakers,
                    battle.spectatorSourceKeys.get(player.getUUID()), battle.spectatorIds.contains(player.getUUID()));
            if (source == null) {
                battle.spectatorSourceKeys.remove(player.getUUID());
                continue;
            }
            boolean alreadyWatching = battle.spectatorIds.contains(player.getUUID());
            BattleAudioSourceKey previousSource = battle.spectatorSourceKeys.put(player.getUUID(), source.key());
            if (alreadyWatching && source.key().equals(previousSource)) {
                continue;
            }
            if (alreadyWatching) {
                sendSpectatorMusic(player, battle, track, audioAsset.get(), audioHash, source);
                continue;
            }
            sendTrackAssets(player, track, audioAsset.get());
            battle.spectatorIds.add(player.getUUID());
            PacketDistributor.sendToPlayer(player, new SpectatorBattlePayload(
                    battle.id,
                    battle.durationTicks,
                    battle.playTicks,
                    battle.effectiveBpm,
                    normalizeSpeedMultiplier(battle.speedMultiplier),
                    battle.leftEntity.getId(),
                    battle.solo ? 0 : battle.rightEntity.getId(),
                    battle.camera == null ? 0 : battle.camera.getId(),
                    battle.cameraYaw,
                    battle.cameraPitch,
                    battle.cameraFov,
                    battle.leftState.color,
                    battle.rightState.color,
                    battle.leftState.panelStyle.panelOpacity(),
                    battle.leftState.panelStyle.laneOpacity(),
                    battle.leftState.panelStyle.widthScale(),
                    battle.leftState.panelStyle.heightScale(),
                    battle.leftState.panelStyle.noteWidthScale(),
                    battle.leftState.panelStyle.noteHeightScale(),
                    battle.rightState.panelStyle.panelOpacity(),
                    battle.rightState.panelStyle.laneOpacity(),
                    battle.rightState.panelStyle.widthScale(),
                    battle.rightState.panelStyle.heightScale(),
                    battle.rightState.panelStyle.noteWidthScale(),
                    battle.rightState.panelStyle.noteHeightScale(),
                    battle.leftState.speed,
                    battle.rightState.speed,
                    battle.leftState.score,
                    battle.rightState.score,
                    battle.leftState.combo,
                    battle.rightState.combo,
                    heldMask(battle.leftState),
                    heldMask(battle.rightState),
                    battle.runtimeNotes.stream()
                            .map(runtimeNote -> runtimeNote.note)
                            .toList(),
                    battle.runtimeEffects,
                    battle.leftState.appearance,
                    battle.rightState.appearance
            ));
            sendSpectatorMusic(player, battle, track, audioAsset.get(), audioHash, source);
        }
        if (battle.playTicks % 20 == 0) {
            sendBattleAudience(level, battle, center);
        }
    }

    private static void sendBattleAudience(ServerLevel level, Battle battle, Vec3 center) {
        List<Integer> spectatorEntityIds = battle.spectatorIds.stream()
                .map(level.getServer().getPlayerList()::getPlayer)
                .filter(player -> player != null && player.level() == level && player.isAlive())
                .map(Entity::getId)
                .sorted()
                .toList();

        AABB battleZone = new AABB(
                center.x - CROWD_RADIUS, center.y - 5.0D, center.z - CROWD_RADIUS,
                center.x + CROWD_RADIUS, center.y + 8.0D, center.z + CROWD_RADIUS
        );
        List<Integer> mobEntityIds = level.getEntitiesOfClass(Mob.class, battleZone, mob ->
                        mob.isAlive()
                                && !mob.getUUID().equals(battle.leftEntity.getUUID())
                                && !mob.getUUID().equals(battle.rightEntity.getUUID()))
                .stream()
                .map(Entity::getId)
                .sorted()
                .toList();

        BattleAudiencePayload payload = new BattleAudiencePayload(battle.id, spectatorEntityIds, mobEntityIds);
        for (ServerPlayer watcher : battle.watchers()) {
            PacketDistributor.sendToPlayer(watcher, payload);
        }
    }

    private static BattleAudioSource nearestAudioSource(ServerPlayer player, Vec3 battleCenter,
                                                        Set<BlockPos> activeSpeakers) {
        return nearestAudioSource(player, battleCenter, activeSpeakers, null, false);
    }

    private static BattleAudioSource nearestAudioSource(ServerPlayer player, Vec3 battleCenter,
                                                         Set<BlockPos> activeSpeakers,
                                                         BattleAudioSourceKey currentKey, boolean alreadyListening) {
        Vec3 coreSource = battleCenter.add(0.0D, 1.0D, 0.0D);
        double coreDistanceSquared = player.distanceToSqr(coreSource);
        BattleAudioSource loudest = null;
        BattleAudioSource current = null;
        // A connected speaker network replaces the arena emitter entirely,
        // including when its gain is zero or the listener is outside its range.
        if (activeSpeakers.isEmpty() && (alreadyListening
                || coreDistanceSquared <= SPECTATOR_MUSIC_DISTANCE * SPECTATOR_MUSIC_DISTANCE)) {
            BattleAudioSourceKey key = new BattleAudioSourceKey(Long.MIN_VALUE,
                    (float) SPECTATOR_MUSIC_DISTANCE, 1.0F, 0.0F, 0.0F, 0.0F);
            loudest = new BattleAudioSource(coreSource, (float) SPECTATOR_MUSIC_DISTANCE, 1.0F,
                    0.0F, 0.0F, 0.0F,
                    effectiveSourceVolume(1.0F, Math.sqrt(coreDistanceSquared), (float) SPECTATOR_MUSIC_DISTANCE), key);
            if (key.equals(currentKey)) {
                current = loudest;
            }
        }
        for (BlockPos pos : activeSpeakers) {
            Vec3 speakerSource = Vec3.atCenterOf(pos).add(0.0D, 0.65D, 0.0D);
            float radius = SpeakerNetwork.soundRadius(player.serverLevel(), pos);
            float volume = SpeakerNetwork.volume(player.serverLevel(), pos);
            double distanceSquared = player.distanceToSqr(speakerSource);
            // Existing listeners must receive a new speaker position even in
            // silence; otherwise a previously audible arena emitter stays alive.
            // New listeners still need to enter speaker coverage to join.
            if (alreadyListening || distanceSquared <= radius * radius) {
                SpeakerBlockEntity speaker = player.serverLevel().getBlockEntity(pos) instanceof SpeakerBlockEntity found
                        ? found : null;
                float lowGainDb = speaker == null ? 0.0F : speaker.getLowGainDb();
                float midGainDb = speaker == null ? 0.0F : speaker.getMidGainDb();
                float highGainDb = speaker == null ? 0.0F : speaker.getHighGainDb();
                BattleAudioSourceKey key = new BattleAudioSourceKey(pos.asLong(), radius, volume,
                        lowGainDb, midGainDb, highGainDb);
                BattleAudioSource candidate = new BattleAudioSource(speakerSource, radius, volume,
                        lowGainDb, midGainDb, highGainDb,
                        effectiveSourceVolume(volume, Math.sqrt(distanceSquared), radius), key);
                if (key.equals(currentKey)) {
                    current = candidate;
                }
                if (loudest == null || candidate.effectiveVolume() > loudest.effectiveVolume() + 0.0001F
                        || Math.abs(candidate.effectiveVolume() - loudest.effectiveVolume()) <= 0.0001F
                        && key.id() < loudest.key().id()) {
                    loudest = candidate;
                }
            }
        }
        // Relative hysteresis behaves consistently for quiet and loud speaker
        // configurations. The old absolute +0.03 margin could keep a 1%-volume
        // source selected even when another source was several times louder.
        return current != null && loudest != null
                && current.effectiveVolume() >= loudest.effectiveVolume() * 0.95F ? current : loudest;
    }

    private static void sendSpectatorMusic(ServerPlayer player, Battle battle, MusicBattleTracks.Track track,
                                           MusicBattleTracks.AudioAsset audioAsset, String audioHash,
                                           BattleAudioSource source) {
        PacketDistributor.sendToPlayer(player, new SpectatorMusicPayload(
                battle.id,
                track.id(),
                transferFileName(audioAsset.fileName()),
                audioHash,
                track.volume() * source.volume(),
                normalizeSpeedMultiplier(battle.speedMultiplier),
                source.position().x,
                source.position().y,
                source.position().z,
                source.radius(),
                source.lowGainDb(),
                source.midGainDb(),
                source.highGainDb(),
                Math.max(0, -battle.playTicks),
                Math.max(0, battle.playTicks)
        ));
    }

    private static int heldMask(ParticipantState state) {
        int mask = 0;
        for (int lane = 0; lane < state.laneDown.length; lane++) {
            if (state.laneDown[lane]) {
                mask |= 1 << lane;
            }
        }
        return mask;
    }

    private static float effectiveSourceVolume(float sourceVolume, double distance, float radius) {
        if (radius <= 0.0F || distance >= radius) {
            return 0.0F;
        }
        return Math.max(0.0F, sourceVolume) * Mth.clamp(1.0F - (float) distance / radius, 0.0F, 1.0F);
    }

    private record BattleAudioSource(Vec3 position, float radius, float volume,
                                     float lowGainDb, float midGainDb, float highGainDb,
                                     float effectiveVolume, BattleAudioSourceKey key) { }
    private record BattleAudioSourceKey(long id, float radius, float volume,
                                        float lowGainDb, float midGainDb, float highGainDb) { }
    private record PreparedTrackTransfer(byte[] chartBytes, String chartFile, String chartHash,
                                         String audioFile, int audioBytes, String audioHash,
                                         MusicBattleTracks.AudioAsset audioAsset) { }

    private static Vec3 rotateY(Vec3 vector, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vec3(vector.x * cos - vector.z * sin, vector.y, vector.x * sin + vector.z * cos);
    }

    private static void orientLiving(LivingEntity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
    }

    private static void lockMobForBattle(Mob mob) {
        if (!mob.getPersistentData().getBoolean(MOB_AI_LOCKED_TAG)) {
            mob.getPersistentData().putBoolean(MOB_ORIGINAL_NO_AI_TAG, mob.isNoAi());
            mob.getPersistentData().putBoolean(MOB_ORIGINAL_NO_GRAVITY_TAG, mob.isNoGravity());
            mob.getPersistentData().putBoolean(MOB_AI_LOCKED_TAG, true);
        }
        mob.setNoAi(true);
        mob.setNoGravity(true);
        if (mob instanceof SmileyBallEntity ball) {
            ball.setBattleDanceControlled(true);
        }
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0.0F;
    }

    private static void restoreMobState(Mob mob, boolean fallbackNoAi, boolean fallbackNoGravity) {
        boolean locked = mob.getPersistentData().getBoolean(MOB_AI_LOCKED_TAG);
        boolean originalNoAi = locked ? mob.getPersistentData().getBoolean(MOB_ORIGINAL_NO_AI_TAG) : fallbackNoAi;
        boolean originalNoGravity = locked ? mob.getPersistentData().getBoolean(MOB_ORIGINAL_NO_GRAVITY_TAG) : fallbackNoGravity;
        mob.setNoAi(originalNoAi);
        mob.setNoGravity(originalNoGravity);
        if (mob instanceof SmileyBallEntity ball) {
            ball.setBattleDanceControlled(false);
        }
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0.0F;
        mob.getPersistentData().remove(MOB_AI_LOCKED_TAG);
        mob.getPersistentData().remove(MOB_ORIGINAL_NO_AI_TAG);
        mob.getPersistentData().remove(MOB_ORIGINAL_NO_GRAVITY_TAG);
    }

    private static void resolveInput(Battle battle, int owner, int lane, boolean ai, int inputTick, int claimedNoteIndex) {
        ParticipantState state = battle.state(owner);
        if (inputTick < state.laneHitLockoutUntil[lane]) {
            return;
        }
        RuntimeNote closest = claimedNoteIndex >= 0 ? battle.noteByIndex(claimedNoteIndex) : null;
        int closestDistance = Math.max(MISS_AFTER_PANEL_EXIT_TICKS, EARLY_HIT_WINDOW_TICKS) + 1;
        if (closest != null && canHitClaimedNote(closest, state, owner, lane, inputTick)) {
            if (closest.missedResolved) {
                closest.resolved = false;
                closest.missedResolved = false;
                state.mistakes = Math.max(0, state.mistakes - 1);
            }
            closestDistance = Math.abs(closest.note.tick() - inputTick);
        } else {
            closest = null;
            for (RuntimeNote note : battle.runtimeNotes) {
                if (note.resolved || note.started || note.note.owner() != owner || note.note.lane() != lane) {
                    continue;
                }

                int earlyDistance = note.note.tick() - inputTick;
                int lateDistance = inputTick - note.note.tick();
                boolean hittable = earlyDistance >= 0
                        ? earlyDistance <= earlyHitWindowTicks(state.speed)
                        : lateDistance <= MISS_AFTER_PANEL_EXIT_TICKS;
                int distance = Math.abs(earlyDistance);
                if (hittable && distance < closestDistance) {
                    closest = note;
                    closestDistance = distance;
                }
            }
        }

        if (closest == null) {
            if (shouldBreakComboForBadPress(battle, owner, lane, inputTick)) {
                state.combo = 0;
                state.mistakes++;
                state.score = Math.max(0, state.score - (ai ? 0 : 24));
                broadcastAction(battle, owner, lane, -1, false, false);
            }
            return;
        }

        closest.started = true;
        closest.hitResolved = true;
        closest.missedResolved = false;
        if (closest.note.durationTicks() <= 0) {
            closest.resolved = true;
        } else {
            state.activeHolds[lane] = closest;
        }
        int comboBeforeHit = state.combo;
        state.combo++;
        state.maxCombo = Math.max(state.maxCombo, state.combo);
        int base = timingScore(closest.note.tick(), inputTick);
        state.score = BattleScoreMath.saturatingAdd(state.score,
                BattleScoreMath.comboAdjustedHitScore(base, comboBeforeHit));
        state.laneHitLockoutUntil[lane] = inputTick + SAME_LANE_HIT_LOCKOUT_TICKS;
        animateParticipant(battle, owner);
        broadcastAction(battle, owner, lane, closest.index, true, closest.note.durationTicks() <= 0);
    }

    private static void resolveHoldRelease(Battle battle, int owner, int lane, int inputTick, int claimedNoteIndex) {
        ParticipantState state = battle.state(owner);
        RuntimeNote hold = battle.noteByIndex(claimedNoteIndex);
        if (hold == null || hold.note.owner() != owner || hold.note.lane() != lane || hold.note.durationTicks() <= 0) {
            return;
        }
        if (hold.resolved || !hold.started) {
            return;
        }

        int endTick = hold.note.tick() + hold.note.durationTicks();
        hold.resolved = true;
        hold.started = false;
        hold.hitResolved = inputTick >= endTick - HOLD_RELEASE_EARLY_TICKS;
        hold.missedResolved = !hold.hitResolved;
        if (state.activeHolds[lane] == hold) {
            state.activeHolds[lane] = null;
        }

        if (inputTick >= endTick - HOLD_RELEASE_EARLY_TICKS) {
            state.score += Math.max(20, hold.note.durationTicks() / 2);
            broadcastAction(battle, owner, lane, hold.index, true, true);
            return;
        }

        state.combo = 0;
        state.mistakes++;
        state.score = Math.max(0, state.score - 24);
        broadcastAction(battle, owner, lane, hold.index, false, true);
    }

    private static boolean canHitClaimedNote(RuntimeNote note, ParticipantState state, int owner, int lane, int inputTick) {
        if ((note.resolved && !note.missedResolved) || note.started || note.note.owner() != owner || note.note.lane() != lane) {
            return false;
        }
        int earlyDistance = note.note.tick() - inputTick;
        int lateDistance = inputTick - note.note.tick();
        return earlyDistance >= 0 ? earlyDistance <= earlyHitWindowTicks(state.speed) : lateDistance <= MISS_AFTER_PANEL_EXIT_TICKS;
    }

    private static boolean shouldBreakComboForBadPress(Battle battle, int owner, int pressedLane, int inputTick) {
        ParticipantState state = battle.state(owner);
        int earlyHitWindow = state == null ? EARLY_HIT_WINDOW_TICKS : earlyHitWindowTicks(state.speed);
        for (RuntimeNote note : battle.runtimeNotes) {
            if (note.resolved || note.started || note.note.owner() != owner) {
                continue;
            }
            int earlyDistance = note.note.tick() - inputTick;
            int lateDistance = inputTick - note.note.tick();
            if (note.note.lane() == pressedLane) {
                if (earlyDistance > earlyHitWindow && earlyDistance <= earlyHitWindow + EARLY_FAIL_TICKS) {
                    return true;
                }
                continue;
            }
            if (earlyDistance <= earlyHitWindow && lateDistance <= MISS_AFTER_PANEL_EXIT_TICKS) {
                return true;
            }
        }
        return false;
    }

    private static int earlyHitWindowTicks(float speed) {
        return NoteSpeed.earlyHitWindowTicks(speed);
    }

    private static int timingScore(int noteTick, int inputTick) {
        int earlyDistance = noteTick - inputTick;
        int lateDistance = inputTick - noteTick;
        int distance = Math.abs(noteTick - inputTick);
        if (distance <= PERFECT_WINDOW_TICKS) {
            return 100;
        }
        if (earlyDistance > 0 && earlyDistance <= 3) {
            return 78;
        }
        if (earlyDistance > 0) {
            return 48;
        }
        if (lateDistance <= 6) {
            return 70;
        }
        if (lateDistance <= 14) {
            return 48;
        }
        return 26;
    }

    private static void animateParticipant(Battle battle, int owner) {
        ParticipantState state = battle.state(owner);
        if (state != null) {
            state.bopTicks = 0;
        }
    }

    private static void broadcastAction(Battle battle, int owner, int lane, int noteIndex, boolean hit, boolean complete) {
        broadcastAction(battle, owner, lane, noteIndex, hit, complete, null);
    }

    private static void broadcastAction(Battle battle, int owner, int lane, int noteIndex, boolean hit, boolean complete, UUID excludedPlayerId) {
        ParticipantState state = battle.state(owner);
        RuntimeNote runtimeNote = noteIndex >= 0 ? battle.noteByIndex(noteIndex) : null;
        BattleActionPayload payload = new BattleActionPayload(
                battle.id,
                owner,
                lane,
                noteIndex,
                runtimeNote == null ? -1 : runtimeNote.note.tick(),
                hit,
                complete,
                state.score,
                state.combo,
                battle.leftState.score,
                battle.rightState.score
        );
        for (ServerPlayer player : battle.watchers()) {
            if (excludedPlayerId != null && excludedPlayerId.equals(player.getUUID())) {
                continue;
            }
            PacketDistributor.sendToPlayer(player, payload);
        }
        updateStatusWindow(battle.leftStatus, battle.leftAnchor, battle.leftState, displayName(battle.leftEntity), battle.playTicks, battle.runtimeNotes);
        updateStatusWindow(battle.rightStatus, battle.rightAnchor, battle.rightState, displayName(battle.rightEntity), battle.playTicks, battle.runtimeNotes);
    }

    private static void updateStatusWindow(ArmorStand stand, Vec3 anchor, ParticipantState state, String name, int playTicks, List<RuntimeNote> runtimeNotes) {
        if (stand == null || anchor == null || state == null) {
            return;
        }
        stand.absMoveTo(anchor.x, anchor.y + 2.55D, anchor.z, 0.0F, 0.0F);
        stand.setCustomNameVisible(false);
    }

    private static String lanePreview(int owner, int playTicks, List<RuntimeNote> runtimeNotes) {
        String[] lanes = {"_", "_", "_", "_"};
        for (RuntimeNote note : runtimeNotes) {
            if (note.resolved || note.note.owner() != owner) {
                continue;
            }
            int distance = note.note.tick() - playTicks;
            if (distance < -HIT_WINDOW_TICKS || distance > 24) {
                continue;
            }
            int lane = Mth.clamp(note.note.lane(), 0, 3);
            lanes[lane] = distance <= HIT_WINDOW_TICKS ? "#" : distance <= 16 ? "+" : ".";
        }
        return lanes[0] + " " + lanes[1] + " " + lanes[2] + " " + lanes[3];
    }

    /**
     * Draws, or stops drawing, a microphone in each fighter's main hand for everyone who can see them. Only players
     * and mobs: the solo partner is an invisible armour stand, and an armour stand still draws what it holds.
     */
    private static void sendBattleMicrophones(Battle battle, boolean holding) {
        for (LivingEntity entity : new LivingEntity[]{battle.leftEntity, battle.rightEntity}) {
            if (entity != null && !entity.isRemoved() && (entity instanceof ServerPlayer || entity instanceof Mob)) {
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new BattleMicrophonePayload(entity.getId(), holding));
            }
        }
    }

    private static void finishBattle(Battle battle, Component fallbackMessage, boolean forceMessage) {
        Component result = forceMessage ? fallbackMessage.copy().withStyle(ChatFormatting.AQUA) : battle.resultMessage(fallbackMessage);
        finishBattle(battle, result);
    }

    private static void finishBattle(Battle battle, Component result) {
        if (!BATTLES.containsKey(battle.id)) {
            return;
        }
        for (ServerPlayer player : battle.players()) {
            player.connection.send(new ClientboundSetCameraPacket(player));
        }
        for (ServerPlayer player : battle.watchers()) {
            PacketDistributor.sendToPlayer(player, new BattleStopPayload(battle.id, result));
        }
        sendBattleMicrophones(battle, false);

        if (battle.leftEntity() instanceof Mob mob) {
            restoreMobState(mob, battle.leftMobWasNoAi, battle.leftMobWasNoGravity);
        }
        if (battle.rightEntity() instanceof Mob mob) {
            restoreMobState(mob, battle.rightMobWasNoAi, battle.rightMobWasNoGravity);
        }
        battle.leftEntity.setShiftKeyDown(false);
        battle.rightEntity.setShiftKeyDown(false);
        if (battle.camera != null) {
            battle.camera.discard();
        }
        if (battle.leftStatus != null) {
            battle.leftStatus.discard();
        }
        if (battle.rightStatus != null) {
            battle.rightStatus.discard();
        }
        if (battle.solo && !battle.rightEntity.isRemoved()) {
            battle.rightEntity.discard();
        }

        BATTLES.remove(battle.id, battle);
        if (battle.leftPlayerId != null) {
            BATTLE_BY_PLAYER.remove(battle.leftPlayerId, battle.id);
        }
        if (battle.rightPlayerId != null) {
            BATTLE_BY_PLAYER.remove(battle.rightPlayerId, battle.id);
        }
        BATTLE_BY_ENTITY.remove(battle.leftEntity.getUUID(), battle.id);
        BATTLE_BY_ENTITY.remove(battle.rightEntity.getUUID(), battle.id);
    }

    private static void recordCompletedBattleStats(Battle battle) {
        if (battle.solo || battle.statsRecorded || battle.leftState == null || battle.rightState == null) {
            return;
        }
        battle.statsRecorded = true;
        if (battle.leftState.score == battle.rightState.score) {
            return;
        }
        int winnerOwner = battle.leftState.score > battle.rightState.score ? 0 : 1;
        int loserOwner = winnerOwner == 0 ? 1 : 0;
        recordWinIfPlayer(battle.entity(winnerOwner), battle.state(winnerOwner));
        recordLossIfPlayer(battle.entity(loserOwner));
        grantMasterWin(battle.entity(winnerOwner), battle.entity(loserOwner));
    }

    private static void recordSurrenderStats(Battle battle, ServerPlayer surrenderer, int owner) {
        if (battle.statsRecorded) {
            return;
        }
        battle.statsRecorded = true;
        PlayerBattleStats.recordSurrender(surrenderer);
        MicrophoneItem.refreshPlayerMicrophones(surrenderer);
        int winnerOwner = owner == 0 ? 1 : 0;
        recordWinIfPlayer(battle.entity(winnerOwner), battle.state(winnerOwner));
    }

    private static void recordWinIfPlayer(LivingEntity entity, ParticipantState state) {
        if (entity instanceof ServerPlayer player) {
            boolean flawless = state != null && state.mistakes == 0;
            PlayerBattleStats.recordWin(player, flawless);
            MicrophoneItem.refreshPlayerMicrophones(player);
            MusicBattleAdvancements.grant(player, "first_win");
            if (flawless) {
                MusicBattleAdvancements.grant(player, "full_combo");
            }
            if (PlayerBattleStats.get(player).wins() >= 25) {
                MusicBattleAdvancements.grant(player, "headliner");
            }
        }
    }

    /** The rap god is the mod's own opponent; beating it is worth saying so. */
    private static void grantMasterWin(LivingEntity winner, LivingEntity loser) {
        if (winner instanceof ServerPlayer player && loser instanceof TestRapGodMasterEntity) {
            MusicBattleAdvancements.grant(player, "beat_the_master");
        }
    }

    private static void recordLossIfPlayer(LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            PlayerBattleStats.recordLoss(player);
            MicrophoneItem.refreshPlayerMicrophones(player);
        }
    }

    private static boolean isProtectedParticipant(LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            UUID battleId = BATTLE_BY_PLAYER.get(player.getUUID());
            Battle battle = battleId == null ? null : BATTLES.get(battleId);
            return battle != null && battle.isActivePhase();
        }

        UUID entityId = entity.getUUID();
        for (Battle battle : BATTLES.values()) {
            if (battle.isActivePhase()
                    && (battle.leftEntity.getUUID().equals(entityId) || battle.rightEntity.getUUID().equals(entityId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBusy(UUID playerId) {
        return BATTLE_BY_PLAYER.containsKey(playerId);
    }

    private static boolean isBusyEntity(LivingEntity entity) {
        return BATTLE_BY_ENTITY.containsKey(entity.getUUID());
    }

    private static List<String> keyLabels(UUID playerId) {
        return KEY_LABELS.getOrDefault(playerId, DEFAULT_KEY_LABELS);
    }

    private static int playerColor(UUID playerId) {
        return PANEL_COLORS.getOrDefault(playerId, PlayerPanelDefaults.PANEL_COLOR);
    }

    private static float playerSpeed(UUID playerId) {
        return NoteSpeed.clamp(NOTE_SPEEDS.getOrDefault(playerId, PlayerPanelDefaults.NOTE_SPEED));
    }

    private static NoteAppearance playerAppearance(UUID playerId) {
        return NOTE_APPEARANCES.getOrDefault(playerId, PlayerPanelDefaults.APPEARANCE);
    }

    private static PanelStyle playerPanelStyle(UUID playerId) {
        return PANEL_STYLES.getOrDefault(playerId, PanelStyle.DEFAULT);
    }

    private static float mobSpeed(Battle battle) {
        UUID source = styleSourcePlayerId(battle);
        return source == null ? 1.0F : playerSpeed(source);
    }

    private static PanelStyle mobPanelStyle(Battle battle) {
        UUID source = styleSourcePlayerId(battle);
        return source == null ? PanelStyle.DEFAULT : playerPanelStyle(source);
    }

    private static UUID styleSourcePlayerId(Battle battle) {
        if (battle.leftPlayerId != null) {
            return battle.leftPlayerId;
        }
        if (battle.rightPlayerId != null) {
            return battle.rightPlayerId;
        }
        return battle.styleSourcePlayerId;
    }

    private static int randomMobColor(Random random) {
        int[] colors = {0xFFFF5C7A, 0xFFFFD166, 0xFF7DDE92, 0xFFC084FC, 0xFFFF8A3D};
        return colors[random.nextInt(colors.length)];
    }

    private static List<String> normalizeKeyLabels(List<String> labels) {
        if (labels == null) {
            return DEFAULT_KEY_LABELS;
        }
        List<String> normalized = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String label = i < labels.size() ? labels.get(i) : DEFAULT_KEY_LABELS.get(i);
            if (label == null || label.isBlank()) {
                label = DEFAULT_KEY_LABELS.get(i);
            }
            normalized.add(label.length() > 32 ? label.substring(0, 32) : label);
        }
        return List.copyOf(normalized);
    }

    private static String displayName(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return player.getGameProfile().getName();
        }
        return entity.getDisplayName().getString();
    }

    private static String coloredName(Entity entity, ParticipantState state) {
        int color = state == null ? 0xFFFFFF : state.color & 0xFFFFFF;
        return hexColor(color) + displayName(entity) + "§r";
    }

    private static MutableComponent coloredNameComponent(Entity entity, ParticipantState state) {
        int color = state == null ? 0xFFFFFF : state.color & 0xFFFFFF;
        return Component.literal(displayName(entity)).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
    }

    private static String hexColor(int rgb) {
        String hex = String.format("%06X", rgb & 0xFFFFFF);
        return "§x§" + hex.charAt(0) + "§" + hex.charAt(1) + "§" + hex.charAt(2)
                + "§" + hex.charAt(3) + "§" + hex.charAt(4) + "§" + hex.charAt(5);
    }

    private static float yawToFace(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Mth.wrapDegrees((float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F);
    }

    private static float pitchToFace(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return Mth.wrapDegrees((float) (-(Math.atan2(dy, horizontal) * 180.0D / Math.PI)));
    }

    /** Converts an authoritative optical block-camera point to ArmorStand feet. */
    private static Vec3 cameraEntityPosition(ArmorStand camera, Vec3 opticalPosition) {
        return opticalPosition.subtract(0.0D, camera.getEyeHeight(), 0.0D);
    }

    /**
     * A challenge on its way to {@code targetId}'s answer. {@code challengerFirst} is the challenger's side: Player 1
     * unless they chose otherwise when they sent it. In {@link #SIDE_CHOICES_BY_CHALLENGER} the first field is the
     * player who was challenged instead, and the side is not chosen yet.
     */
    private record Challenge(UUID challengerId, long createdTick, boolean challengerFirst) {
    }

    private record BattleCameraPlacement(BlockPos blockPos, Vec3 position, float yaw, float pitch, float fov) {
    }

    private enum Phase {
        VOTING,
        ROULETTE,
        PREPARING,
        PLAYING
    }

    private enum MobSkill {
        EASY("easy", 0.48F, 0.55F),
        MEDIUM("medium", 0.68F, 0.28F),
        HARD("hard", 0.82F, 0.14F),
        GOD("god", 0.998F, 0.01F),
        PERFECT("perfect", 1.0F, 0.0F);

        private final String id;
        private final float hitChance;
        private final float releaseChance;

        MobSkill(String id, float hitChance, float releaseChance) {
            this.id = id;
            this.hitChance = hitChance;
            this.releaseChance = releaseChance;
        }

        private static MobSkill byId(String id) {
            for (MobSkill skill : values()) {
                if (skill.id.equals(id)) {
                    return skill;
                }
            }
            return MEDIUM;
        }

        private static MobSkill deadlyAllowed(String id) {
            MobSkill skill = byId(id);
            return skill.ordinal() < GOD.ordinal() ? GOD : skill;
        }
    }

    private static final class Battle {
        private final UUID id;
        /** Player 1/owner 0 for player-initiated battles; always the challenger. */
        private final UUID playerOnePlayerId;
        private final UUID leftPlayerId;
        private final UUID rightPlayerId;
        private final LivingEntity leftEntity;
        private final LivingEntity rightEntity;
        private UUID styleSourcePlayerId;
        private UUID mobControllerId;
        private final List<MusicBattleTracks.Track> tracks;
        private final Map<UUID, String> votes = new HashMap<>();
        private final Map<UUID, Boolean> unifiedVotes = new HashMap<>();
        private final Map<UUID, Boolean> deadlyVotes = new HashMap<>();
        private final Map<UUID, Float> speedVotes = new HashMap<>();
        private String mobVoteId;
        private boolean unifiedNotes;
        private boolean deadlyParty;
        private boolean solo;
        private float speedMultiplier = 1.0F;
        private boolean statsRecorded;
        /** Set once a client has judged a note by a number this build does not give that note. */
        private boolean noteIndexSkewWarned;
        private final Set<UUID> spectatorIds = new HashSet<>();
        private final Map<UUID, BattleAudioSourceKey> spectatorSourceKeys = new HashMap<>();
        private Set<BlockPos> activeSpeakers = Set.of();
        private final Set<UUID> readyPlayers = new HashSet<>();
        private final Set<UUID> preloadRecipientIds = new HashSet<>();
        private final Set<UUID> preloadSpectatorIds = new HashSet<>();
        private final Set<UUID> preloadFailedSpectatorIds = new HashSet<>();
        private PreparedTrackTransfer preparedTransfer;
        private final Set<UUID> pauseSkipVotes = new HashSet<>();
        private int pauseSkipOfferTargetTick = -1;
        private boolean pauseSkipStatusVisible;
        private final Map<UUID, Set<String>> previewIdsSent = new HashMap<>();
        private List<String> rouletteChoices = List.of();
        private final List<RuntimeNote> runtimeNotes = new ArrayList<>();
        private final List<TrackEffect> runtimeEffects = new ArrayList<>();
        private final Random random = new Random();
        private Phase phase = Phase.VOTING;
        private MobSkill mobSkill = MobSkill.MEDIUM;
        private MusicBattleTracks.Track track;
        private String runtimeAudioFile = "";
        private String runtimeChartFile = "";
        private ParticipantState leftState;
        private ParticipantState rightState;
        private int voteTicks;
        /** Extra ticks the vote runs while a voter's package is still arriving; at most 30 seconds. */
        private int voteExtensionTicks;
        /** Voters whose INSTALLED choice is still being transferred; the vote does not close while one is here. */
        private final Set<UUID> pendingTransferVoters = new HashSet<>();
        /** Sets a voter brought from their own install, playable in this battle only. */
        private final List<MusicBattleTracks.Track> extraTracks = new ArrayList<>();
        /** Set by the first resolveVotes; the vote result is applied exactly once. */
        private boolean votesResolved;
        private int rouletteTicks;
        private int prepareTicks;
        /** Set while the battle waits for its sped-up copy to finish converting; no transfer has started yet. */
        private boolean awaitingSpeedAudio;
        /** How long it has waited for that copy, against {@link #SPEED_AUDIO_WAIT_TICKS}. */
        private int speedAudioWaitTicks;
        private int playTicks;
        /** Counts held ticks so the microphone is announced at once and then every second. */
        private int microphoneTicks;
        /**
         * When tick zero of this battle falls, on the server's own clock.
         *
         * The battle used to have no agreed instant at all: each client began counting when the start packet
         * happened to arrive and began playing when its own ogg happened to start decoding, so the two of them
         * were several ticks apart and, with a hit window of six ticks, effectively playing different charts.
         * One epoch here, and a per-player delay that cancels that player's own latency, is what makes the two
         * sides start together.
         */
        private long startEpochNanos;
        private int durationTicks;
        private int effectiveBpm = 120;
        /** Where beat 1 falls on this battle's timeline, in ms; the beat visuals count beats from it. */
        private int effectiveOffsetMs;
        private Vec3 leftAnchor;
        private Vec3 rightAnchor;
        private Vec3 leftVotingPos;
        private Vec3 rightVotingPos;
        private float leftYaw;
        private float rightYaw;
        private float leftVotingYaw;
        private float rightVotingYaw;
        private ArmorStand camera;
        private Vec3 cameraStart;
        private Vec3 cameraTarget;
        private Vec3 cameraCenter;
        private double cameraOrbitPhase;
        private double cameraOrbitAmplitude = 0.28D;
        private double cameraOrbitSpeed = 42.0D;
        private double cameraOrbitVertical = 0.10D;
        /** Leans the automatic shot towards whoever is playing when the two sides take turns. */
        private final BattleCameraFocus cameraFocus = new BattleCameraFocus();
        private ArmorStand leftStatus;
        private ArmorStand rightStatus;
        private float cameraYaw;
        private float cameraPitch;
        private float cameraFov;
        private boolean fixedCamera;
        private BlockPos fixedCameraBlockPos;
        private long cameraSyncRevision;
        private BattleCameraPlacement arenaCameraFallback;
        private BattleCameraPlacement dynamicCameraFallback;
        private BattleCameraPlacement lastBroadcastCamera;
        private final boolean leftMobWasNoAi;
        private final boolean rightMobWasNoAi;
        private final boolean leftMobWasNoGravity;
        private final boolean rightMobWasNoGravity;

        private static Battle forPlayerChallenge(UUID id, ServerPlayer challenger, LivingEntity opponent,
                                                 List<MusicBattleTracks.Track> tracks) {
            return new Battle(id, challenger, opponent, tracks, challenger.getUUID());
        }

        /** A challenger who chose Player 2 against a mob: the mob is on the left, and no player is Player 1. */
        private static Battle forMobFirst(UUID id, LivingEntity mob, ServerPlayer challenger,
                                          List<MusicBattleTracks.Track> tracks) {
            return new Battle(id, mob, challenger, tracks, null);
        }

        /** The rap god NPC on either side, or null; it votes for itself, so it counts as a voter. */
        private TestRapGodMasterEntity testNpc() {
            if (rightEntity instanceof TestRapGodMasterEntity npc) {
                return npc;
            }
            return leftEntity instanceof TestRapGodMasterEntity npc ? npc : null;
        }

        private static Battle forMobBattle(UUID id, LivingEntity first, LivingEntity second,
                                           List<MusicBattleTracks.Track> tracks) {
            return new Battle(id, first, second, tracks, null);
        }

        private static Battle forSolo(UUID id, ServerPlayer player, ArmorStand hiddenPartner,
                                      List<MusicBattleTracks.Track> tracks) {
            Battle battle = new Battle(id, player, hiddenPartner, tracks, player.getUUID());
            battle.solo = true;
            battle.unifiedNotes = false;
            battle.deadlyParty = false;
            return battle;
        }

        private Battle(UUID id, LivingEntity leftEntity, LivingEntity rightEntity,
                       List<MusicBattleTracks.Track> tracks, UUID playerOnePlayerId) {
            this.id = id;
            this.leftEntity = leftEntity;
            this.rightEntity = rightEntity;
            if (playerOnePlayerId != null && !playerOnePlayerId.equals(leftEntity.getUUID())) {
                throw new IllegalArgumentException("Player 1 must be the first/challenging participant");
            }
            this.playerOnePlayerId = playerOnePlayerId;
            this.leftVotingPos = leftEntity.position();
            this.rightVotingPos = rightEntity.position();
            this.leftVotingYaw = leftEntity.getYRot();
            this.rightVotingYaw = rightEntity.getYRot();
            this.leftMobWasNoAi = leftEntity instanceof Mob mob && mob.isNoAi();
            this.rightMobWasNoAi = rightEntity instanceof Mob mob && mob.isNoAi();
            this.leftMobWasNoGravity = leftEntity instanceof Mob mob && mob.isNoGravity();
            this.rightMobWasNoGravity = rightEntity instanceof Mob mob && mob.isNoGravity();
            this.leftPlayerId = leftEntity instanceof ServerPlayer player ? player.getUUID() : null;
            this.rightPlayerId = rightEntity instanceof ServerPlayer player ? player.getUUID() : null;
            this.tracks = List.copyOf(tracks);
        }

        private void tick() {
            LivingEntity currentLeft = leftEntity();
            LivingEntity currentRight = rightEntity();
            if (currentLeft == null || currentRight == null
                    || !currentLeft.isAlive() || !currentRight.isAlive()
                    || !(currentLeft.level() instanceof ServerLevel currentLevel)
                    || currentRight.level() != currentLeft.level()) {
                finishBattle(this, Component.translatable("music_battle.l10n.server.participant_missing_or_moved"), true);
                return;
            }

            // Blocks, borders and dimensions may change after voting/setup. Never
            // force anyone back into an anchor that has since become obstructed.
            boolean voting = phase == Phase.VOTING || phase == Phase.ROULETTE;
            boolean holding = phase == Phase.PREPARING || phase == Phase.PLAYING;
            if (voting && (!BattleArenaPlacement.clearVotingAnchor(currentLevel, currentLeft,
                    currentLeft instanceof ServerPlayer ? currentLeft.position() : leftVotingPos)
                    || !solo && !BattleArenaPlacement.clearVotingAnchor(currentLevel, currentRight,
                    currentRight instanceof ServerPlayer ? currentRight.position() : rightVotingPos))
                    || holding && !BattleArenaPlacement.safePair(currentLevel, currentLeft, currentRight, leftAnchor, rightAnchor, solo)) {
                finishBattle(this, Component.translatable("music_battle.battle.placement_obstructed").withStyle(ChatFormatting.RED));
                return;
            }
            // Once a second from the moment both stand in place: a viewer who walks up, or joins, mid-song sees
            // it too, and the client lets a microphone lapse on its own if the repeats stop.
            if (holding && microphoneTicks++ % 20 == 0) {
                sendBattleMicrophones(this, true);
            }

            if (phase == Phase.VOTING) {
                voteTicks++;
                freezeVotingMobs();
                // submitVote already closes a complete vote at once; this also catches a choice
                // recorded any other way, so a full vote never waits out the timer.
                if (voteTicks >= VOTE_TICKS + voteExtensionTicks || everyoneVoted()) {
                    resolveVotes(this);
                }
                return;
            }

            if (phase == Phase.ROULETTE) {
                rouletteTicks++;
                freezeVotingMobs();
                if (rouletteTicks % 10 == 0 && !rouletteChoices.isEmpty()) {
                    String spinning = rouletteChoices.get((rouletteTicks / 10) % rouletteChoices.size());
                    for (ServerPlayer player : players()) {
                        player.displayClientMessage(Component.translatable("music_battle.l10n.server.roulette_spinning", spinning), true);
                    }
                }
                if (rouletteTicks >= ROULETTE_TICKS) {
                    finishRoulette(this);
                }
                return;
            }

            if (phase == Phase.PREPARING) {
                prepareTicks++;
                freezeParticipants();
                if (awaitingSpeedAudio) {
                    // Nothing has been sent yet, so there is nobody to be ready: the preload check below would
                    // find an empty list and start the battle with no chart.
                    speedAudioWaitTicks++;
                    if (speedAudioWaitTicks % 5 == 0 || speedAudioWaitTicks >= SPEED_AUDIO_WAIT_TICKS) {
                        prepareBattleTransfer(this);
                    }
                    if (awaitingSpeedAudio && speedAudioWaitTicks % 20 == 0) {
                        showSpeedAudioWait(this);
                    }
                    return;
                }
                if (prepareTicks == 1 || prepareTicks % 5 == 0) {
                    refreshPreparingSpectators(this);
                    if (allPreloadRecipientsReady()) {
                        startPreparedBattle(this);
                        return;
                    }
                }
                if (prepareTicks >= TRACK_PREPARE_TIMEOUT_TICKS) {
                    finishBattle(this, Component.translatable("music_battle.l10n.server.track_load_timeout"), true);
                }
                return;
            }

            if (phase != Phase.PLAYING) {
                return;
            }

            // Anchored to the epoch rather than counted, so a server that misses ticks does not drag the whole
            // battle away from the clients, which keep their own clock on the same instant. The instant is read on
            // the battle clock, which stops with a single-player pause menu just as the client's chart does.
            playTicks = startEpochNanos == 0L ? playTicks + 1 : BattleClock.ticksSince(startEpochNanos, BattlePauseClock.GAME.now());
            freezeParticipants();
            tickCrowd();
            tickHolds();
            if (playTicks >= 0) {
                tickMobAi(leftState);
                tickMobAi(rightState);
                resolveMissedNotes();
                tickPauseSkipStatus();
            }
            updateStatusWindows();
            if (playTicks % 5 == 0 && track != null && leftEntity.level() instanceof ServerLevel level) {
                updateSpectators(level, this, track);
            }

            if (playTicks >= durationTicks + 30) {
                recordCompletedBattleStats(this);
                finishBattle(this, Component.translatable("music_battle.l10n.server.battle_finished"), false);
                applyDeadlyResult();
            }
        }

        private void tickPauseSkipStatus() {
            int targetTick = pauseSkipTargetTick();
            if (targetTick < 0) {
                if (pauseSkipStatusVisible || !pauseSkipVotes.isEmpty()) {
                    pauseSkipStatusVisible = false;
                    pauseSkipVotes.clear();
                    BattleManager.sendPauseSkipStatus(this, false, -1, false);
                }
                return;
            }
            boolean newlyVisible = !pauseSkipStatusVisible;
            pauseSkipStatusVisible = true;
            addAutomaticPauseSkipVotes();
            if (pauseSkipVotes.size() >= requiredPauseSkipVotes()) {
                applyPauseSkip(targetTick);
                BattleManager.sendPauseSkipStatus(this, false, targetTick, true);
            } else if (newlyVisible || playTicks % 10 == 0) {
                BattleManager.sendPauseSkipStatus(this, true, targetTick, false);
            }
        }

        private int pauseSkipTargetTick() {
            // A mob-vs-mob/NPC battle has nobody who deliberately requested a
            // skip. Auto-voting both AI participants used to jump every long
            // instrumental break (including the intro and outro), while nearby
            // spectators kept playing the unskipped audio timeline.
            if (!hasAnyPlayer()) {
                return -1;
            }
            if (playTicks < 0) {
                return -1;
            }
            if (pauseSkipOfferTargetTick >= 0) {
                if (playTicks >= pauseSkipOfferTargetTick || hasActivePauseSkipBlocker()) {
                    pauseSkipOfferTargetTick = -1;
                    return -1;
                }
                return pauseSkipOfferTargetTick;
            }
            if (hasActivePauseSkipBlocker()) {
                return -1;
            }
            int nextTick = Integer.MAX_VALUE;
            for (RuntimeNote note : runtimeNotes) {
                int end = note.note.tick() + Math.max(0, note.note.durationTicks());
                if (!note.resolved && end >= playTicks && note.note.tick() > playTicks) {
                    nextTick = Math.min(nextTick, note.note.tick());
                }
            }
            if (nextTick == Integer.MAX_VALUE) {
                if (durationTicks - playTicks >= PAUSE_SKIP_MIN_GAP_TICKS) {
                    pauseSkipOfferTargetTick = durationTicks;
                }
                return pauseSkipOfferTargetTick;
            }
            if (nextTick - playTicks < PAUSE_SKIP_MIN_GAP_TICKS) {
                return -1;
            }
            pauseSkipOfferTargetTick = nextTick - PAUSE_SKIP_PREP_TICKS;
            return pauseSkipOfferTargetTick;
        }

        private boolean hasActivePauseSkipBlocker() {
            for (RuntimeNote note : runtimeNotes) {
                if (note.resolved) {
                    continue;
                }
                int start = note.note.tick();
                int end = start + Math.max(0, note.note.durationTicks());
                if (start <= playTicks && end + MISS_AFTER_PANEL_EXIT_TICKS >= playTicks) {
                    return true;
                }
            }
            return false;
        }

        private void addAutomaticPauseSkipVotes() {
            if (leftState != null && leftState.ai) {
                pauseSkipVotes.add(leftEntity.getUUID());
            }
            if (rightState != null && rightState.ai) {
                pauseSkipVotes.add(rightEntity.getUUID());
            }
        }

        private int requiredPauseSkipVotes() {
            int required = 0;
            // Each side counts once, whoever plays it: a mob on the left (a challenger who chose Player 2) has to be
            // counted like one on the right, or its automatic vote alone would carry the skip.
            if (leftPlayerId != null || leftEntity instanceof TestRapGodMasterEntity || leftState != null && leftState.ai) {
                required++;
            }
            if (rightPlayerId != null || rightEntity instanceof TestRapGodMasterEntity || rightState != null && rightState.ai) {
                required++;
            }
            return Math.max(1, required);
        }

        private void applyPauseSkip(int targetTick) {
            int safeTarget = Math.max(playTicks, Math.min(targetTick, durationTicks));
            // The tick is read from the epoch every tick, so moving only the counter was undone a tick later and
            // the battle carried on from inside the rest it had just skipped.
            if (startEpochNanos != 0L) {
                startEpochNanos = BattleSkipClock.serverEpochAfterSkip(startEpochNanos, playTicks, safeTarget);
            }
            playTicks = safeTarget;
            pauseSkipVotes.clear();
            pauseSkipOfferTargetTick = -1;
            pauseSkipStatusVisible = false;
        }

        private void applyDeadlyResult() {
            if (!deadlyParty || leftState == null || rightState == null) {
                return;
            }
            if (leftState.score == rightState.score) {
                return;
            }
            LivingEntity loser = leftState.score < rightState.score ? leftEntity() : rightEntity();
            killDeadlyLoser(loser);
        }

        private void killDeadlyLoser(LivingEntity loser) {
            if (loser == null || !loser.isAlive()) {
                return;
            }
            if (loser.level() instanceof ServerLevel level) {
                level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("music_battle.l10n.server.deadly_loser", loser.getDisplayName()), false);
                if (loser instanceof EnderDragon dragon) {
                    killDragonWithAnimation(level, dragon);
                } else {
                    loser.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                }
            } else {
                loser.kill();
            }
        }

        private void killDragonWithAnimation(ServerLevel level, EnderDragon dragon) {
            dragon.nearestCrystal = null;
            dragon.hurt(dragon.head, level.damageSources().magic(), Math.max(dragon.getMaxHealth() * 4.0F, dragon.getHealth() + 40.0F));
            if (dragon.isAlive()) {
                dragon.setHealth(0.0F);
            }
        }

        private void freezeVotingMobs() {
            LivingEntity left = leftEntity();
            LivingEntity right = rightEntity();
            // Participants already look at each other while the vote runs. Players are left to
            // their own mouse here: their input is not blocked yet, and forcing a yaw would fight it.
            Vec3 leftFacingPos = votingFacingPosition(left, leftVotingPos);
            Vec3 rightFacingPos = votingFacingPosition(right, rightVotingPos);
            boolean facing = leftFacingPos != null && rightFacingPos != null;
            freezeVotingMob(left, leftVotingPos, facing ? yawToFace(leftFacingPos, rightFacingPos) : leftVotingYaw);
            freezeVotingMob(right, rightVotingPos, facing ? yawToFace(rightFacingPos, leftFacingPos) : rightVotingYaw);
        }

        /** A player opponent keeps moving during the vote, so a mob faces where that player stands now. */
        private Vec3 votingFacingPosition(LivingEntity entity, Vec3 votingPos) {
            if (entity instanceof ServerPlayer player) {
                return player.position();
            }
            return votingPos;
        }

        private void freezeVotingMob(LivingEntity entity, Vec3 pos, float yaw) {
            if (entity == null || entity instanceof ServerPlayer || pos == null) {
                return;
            }
            entity.setDeltaMovement(Vec3.ZERO);
            if (entity instanceof Mob mob) {
                mob.getNavigation().stop();
                mob.setTarget(null);
            }
            entity.moveTo(pos.x, pos.y, pos.z, yaw, entity.getXRot());
            entity.hurtMarked = true;
        }

        private void tickCrowd() {
            LivingEntity left = leftEntity();
            LivingEntity right = rightEntity();
            if (left == null || right == null || !(left.level() instanceof ServerLevel level)) {
                return;
            }
            Vec3 center = leftAnchor != null && rightAnchor != null
                    ? leftAnchor.add(rightAnchor).scale(0.5D)
                    : left.position().add(right.position()).scale(0.5D);
            AABB area = new AABB(
                    center.x - CROWD_RADIUS,
                    center.y - 5.0D,
                    center.z - CROWD_RADIUS,
                    center.x + CROWD_RADIUS,
                    center.y + 8.0D,
                    center.z + CROWD_RADIUS
            );
            for (Mob mob : level.getEntitiesOfClass(Mob.class, area, mob -> isCrowdMob(mob))) {
                keepCrowdBackFromParticipants(mob, left, right);
                if (mob instanceof Creeper creeper && isNearOrTargetingParticipant(creeper, left, right)) {
                    creeper.setSwellDir(-1);
                }
                if (shouldCrowdJump(mob)) {
                    BattleCrowdSafety.jump(mob);
                }
            }
        }

        private boolean isCrowdMob(Mob mob) {
            if (!mob.isAlive() || mob.isNoAi()) {
                return false;
            }
            UUID id = mob.getUUID();
            return !leftEntity.getUUID().equals(id) && !rightEntity.getUUID().equals(id);
        }

        private void keepCrowdBackFromParticipants(Mob mob, LivingEntity left, LivingEntity right) {
            LivingEntity nearest = nearestBattleParticipant(mob, left, right);
            if (nearest == null) {
                return;
            }
            double minDistanceSqr = HOSTILE_KEEP_AWAY_DISTANCE * HOSTILE_KEEP_AWAY_DISTANCE;
            if (mob.distanceToSqr(nearest) >= minDistanceSqr) {
                return;
            }
            Vec3 away = mob.position().subtract(nearest.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() < 0.01D) {
                away = Vec3.directionFromRotation(0.0F, mob.getYRot() + 180.0F).multiply(1.0D, 0.0D, 1.0D);
            }
            BattleCrowdSafety.push(mob, away);
        }

        private LivingEntity nearestBattleParticipant(Mob mob, LivingEntity left, LivingEntity right) {
            double leftDistance = left == null ? Double.MAX_VALUE : mob.distanceToSqr(left);
            double rightDistance = right == null ? Double.MAX_VALUE : mob.distanceToSqr(right);
            if (leftDistance == Double.MAX_VALUE && rightDistance == Double.MAX_VALUE) {
                return null;
            }
            return leftDistance <= rightDistance ? left : right;
        }

        private boolean isNearOrTargetingParticipant(Mob mob, LivingEntity left, LivingEntity right) {
            LivingEntity target = mob.getTarget();
            if (target != null && isBattleParticipant(target)) {
                return true;
            }
            double minDistanceSqr = HOSTILE_KEEP_AWAY_DISTANCE * HOSTILE_KEEP_AWAY_DISTANCE;
            return left != null && mob.distanceToSqr(left) < minDistanceSqr
                    || right != null && mob.distanceToSqr(right) < minDistanceSqr;
        }

        private boolean isBattleParticipant(LivingEntity entity) {
            UUID id = entity.getUUID();
            return leftEntity.getUUID().equals(id) || rightEntity.getUUID().equals(id);
        }

        private boolean shouldCrowdJump(Mob mob) {
            // Smiley balls follow their own audible-source beat clock.
            if (mob instanceof SmileyBallEntity || track == null || playTicks < 0 || !mob.onGround()) {
                return false;
            }
            double ticksPerBeat = 1200.0D / Mth.clamp(track.bpm(), 20, 400);
            int beatTicks = Math.max(2, (int) Math.round(ticksPerBeat));
            int offset = Math.floorMod(mob.getId() * 7, beatTicks);
            int sinceBeatOne = (int) Math.round(ChartBeat.beatTicks(playTicks, effectiveOffsetMs));
            int phase = Math.floorMod(sinceBeatOne + offset, beatTicks);
            return phase == 0;
        }

        private void freezeParticipants() {
            LivingEntity left = leftEntity();
            LivingEntity right = rightEntity();
            if (left != null && leftAnchor != null) {
                left.setDeltaMovement(Vec3.ZERO);
                left.setShiftKeyDown(false);
                if (rightAnchor != null) {
                    leftYaw = yawToFace(leftAnchor, rightAnchor);
                }
                float leftPitch = right != null && rightAnchor != null ? pitchToFace(focusPoint(left, leftAnchor), focusPoint(right, rightAnchor)) : 0.0F;
                // Read what the client last reported before the server-side rotation is overwritten.
                boolean leftDrifted = BattlePlacementMath.rotationDrifted(left.getYRot(), left.getXRot(), leftYaw, leftPitch);
                orientLiving(left, leftYaw, leftPitch);
                if (left instanceof ServerPlayer player) {
                    if (player.position().distanceToSqr(leftAnchor) > ANCHOR_CORRECTION_DISTANCE_SQR) {
                        player.connection.teleport(leftAnchor.x, leftAnchor.y, leftAnchor.z, leftYaw, leftPitch);
                    } else if (leftDrifted) {
                        // setYRot is never transmitted and the next movement packet overwrites it,
                        // so a turned-away participant is faced back by a rotation-only teleport.
                        player.connection.teleport(player.getX(), player.getY(), player.getZ(), leftYaw, leftPitch);
                    }
                } else {
                    Vec3 held = heldMobPosition(left, leftAnchor);
                    double tolerance = left instanceof SmileyBallEntity ? 1.0E-10D : ANCHOR_CORRECTION_DISTANCE_SQR;
                    if (left.position().distanceToSqr(held) > tolerance) {
                        left.moveTo(held.x, held.y, held.z, leftYaw, leftPitch);
                        left.setYHeadRot(leftYaw);
                        left.setYBodyRot(leftYaw);
                        left.hurtMarked = true;
                    }
                    if (leftState != null && leftState.bopTicks > 0) {
                        leftState.bopTicks--;
                    }
                }
            }
            if (right != null && rightAnchor != null) {
                right.setDeltaMovement(Vec3.ZERO);
                right.setShiftKeyDown(false);
                if (leftAnchor != null) {
                    rightYaw = yawToFace(rightAnchor, leftAnchor);
                }
                float rightPitch = left != null && leftAnchor != null ? pitchToFace(focusPoint(right, rightAnchor), focusPoint(left, leftAnchor)) : 0.0F;
                boolean rightDrifted = BattlePlacementMath.rotationDrifted(right.getYRot(), right.getXRot(), rightYaw, rightPitch);
                orientLiving(right, rightYaw, rightPitch);
                if (right instanceof ServerPlayer player) {
                    if (player.position().distanceToSqr(rightAnchor) > ANCHOR_CORRECTION_DISTANCE_SQR) {
                        player.connection.teleport(rightAnchor.x, rightAnchor.y, rightAnchor.z, rightYaw, rightPitch);
                    } else if (rightDrifted) {
                        player.connection.teleport(player.getX(), player.getY(), player.getZ(), rightYaw, rightPitch);
                    }
                } else {
                    Vec3 held = heldMobPosition(right, rightAnchor);
                    double tolerance = right instanceof SmileyBallEntity ? 1.0E-10D : ANCHOR_CORRECTION_DISTANCE_SQR;
                    if (right.position().distanceToSqr(held) > tolerance) {
                        right.moveTo(held.x, held.y, held.z, rightYaw, rightPitch);
                        right.setYHeadRot(rightYaw);
                        right.setYBodyRot(rightYaw);
                        right.hurtMarked = true;
                    }
                    if (rightState != null && rightState.bopTicks > 0) {
                        rightState.bopTicks--;
                    }
                }
            }
            if (camera != null) {
                Vec3 pos = cameraTarget == null ? camera.position() : cameraTarget;
                if (playTicks < 0 && cameraStart != null && cameraTarget != null) {
                    float progress = Mth.clamp((playTicks + MUSIC_SYNC_LEAD_TICKS) / (float) MUSIC_SYNC_LEAD_TICKS, 0.0F, 1.0F);
                    progress = progress * progress * (3.0F - 2.0F * progress);
                    pos = cameraStart.lerp(cameraTarget, progress);
                } else if (!fixedCamera && cameraCenter != null && cameraTarget != null
                        && camera.level() instanceof ServerLevel level) {
                    Vec3 offset = cameraTarget.subtract(cameraCenter);
                    double orbit = Math.sin(playTicks / cameraOrbitSpeed + cameraOrbitPhase) * cameraOrbitAmplitude;
                    double vertical = Math.sin(playTicks / (cameraOrbitSpeed * 0.72D)) * cameraOrbitVertical;
                    tickCameraFocus();
                    // Half the way from one fighter to the other, flat. Leaning by a share of it turns the shot
                    // towards whoever is playing and slides the camera a little after them; at zero the shot is
                    // the centred one, which is all shared notes and solo play ever get.
                    Vec3 side = leftAnchor == null || rightAnchor == null ? Vec3.ZERO
                            : new Vec3((rightAnchor.x - leftAnchor.x) * 0.5D, 0.0D, (rightAnchor.z - leftAnchor.z) * 0.5D);
                    double lean = cameraFocus.focus();
                    Vec3 orbitCenter = cameraCenter.add(side.scale(lean * CAMERA_FOCUS_TRUCK));
                    Vec3 lookAt = cameraCenter.add(0.0D, 1.05D, 0.0D).add(side.scale(lean * CAMERA_FOCUS_PAN));
                    // A blocked swing used to freeze the shot wherever it last fitted, off to one
                    // side of the centre. Degrade to a half swing and then to the centred target.
                    for (double scale : CAMERA_ORBIT_SCALES) {
                        Vec3 candidate = orbitCenter.add(rotateY(offset, orbit * scale)).add(0.0D, vertical * scale, 0.0D);
                        if (hasCameraSpace(level, candidate) && hasLineOfSight(level, candidate, lookAt)) {
                            pos = candidate;
                            break;
                        }
                    }
                    // Aim at the centre from wherever the shot ended up, the target included when no
                    // swing fits, so direction and position always belong to the same frame.
                    cameraYaw = yawToFace(pos, lookAt);
                    cameraPitch = pitchToFace(pos, lookAt);
                    // The client follows the camera entity for an automatic shot's position, but learns its
                    // direction only from a sync packet, and an automatic shot never sent one. The swing
                    // therefore moved the camera sideways while it kept looking straight ahead, sliding the
                    // far fighter towards the edge of the frame. A zero field of view keeps the position
                    // entity-driven and carries only the direction, to the participants who view through it.
                    BattleCameraSyncPayload aim = new BattleCameraSyncPayload(id, camera.getId(),
                            pos.x, pos.y, pos.z, cameraYaw, cameraPitch, 0.0F, ++cameraSyncRevision);
                    for (ServerPlayer viewer : players()) {
                        PacketDistributor.sendToPlayer(viewer, aim);
                    }
                }
                Vec3 entityPosition = fixedCamera ? cameraEntityPosition(camera, pos) : pos;
                camera.absMoveTo(entityPosition.x, entityPosition.y, entityPosition.z,
                        cameraYaw, cameraPitch);
            }
        }

        private void tickCameraFocus() {
            boolean separate = !solo && !unifiedNotes && playTicks >= 0;
            boolean leftActive = false;
            boolean rightActive = false;
            if (separate) {
                for (RuntimeNote note : runtimeNotes) {
                    if (!BattleCameraFocus.keepsSideOnCamera(note.note.tick(), note.note.durationTicks(), playTicks)) {
                        continue;
                    }
                    if (note.note.owner() == 0) {
                        leftActive = true;
                    } else {
                        rightActive = true;
                    }
                    if (leftActive && rightActive) {
                        break;
                    }
                }
            }
            cameraFocus.tick(separate, leftActive, rightActive);
        }

        private static Vec3 heldMobPosition(LivingEntity entity, Vec3 anchor) {
            // The ball owns Y through collision-resolved travel; only X/Z remain
            // locked. As soon as its chart stops, put it back on the reserved floor.
            return entity instanceof SmileyBallEntity ball && ball.isDancing()
                    ? new Vec3(anchor.x, entity.getY(), anchor.z) : anchor;
        }

        private boolean shouldBeatCrouch() {
            if (track == null || playTicks < 0) {
                return false;
            }
            double ticksPerBeat = 1200.0D / Mth.clamp(track.bpm(), 20, 400);
            double sinceBeatOne = ChartBeat.beatTicks(playTicks, effectiveOffsetMs);
            double phase = sinceBeatOne - Math.floor(sinceBeatOne / ticksPerBeat) * ticksPerBeat;
            return phase < ticksPerBeat * 0.52D;
        }

        private void updateStatusWindows() {
            updateStatusWindow(leftStatus, leftAnchor, leftState, displayName(leftEntity));
            updateStatusWindow(rightStatus, rightAnchor, rightState, displayName(rightEntity));
        }

        private void updateStatusWindow(ArmorStand stand, Vec3 anchor, ParticipantState state, String name) {
            if (stand == null || anchor == null || state == null) {
                return;
            }
            BattleManager.updateStatusWindow(stand, anchor, state, name, playTicks, runtimeNotes);
        }

        private void resolveMissedNotes() {
            for (RuntimeNote note : runtimeNotes) {
                ParticipantState state = state(note.note.owner());
                int missAfterTicks = MISS_AFTER_PANEL_EXIT_TICKS + (state != null && !state.ai ? CLIENT_JUDGEMENT_FALLBACK_TICKS : 0);
                if (!note.resolved && !note.started && playTicks - note.note.tick() >= missAfterTicks) {
                    note.resolved = true;
                    note.hitResolved = false;
                    note.missedResolved = true;
                    if (state == null) {
                        continue;
                    }
                    state.combo = 0;
                    state.mistakes++;
                    if (!state.ai) {
                        state.score = Math.max(0, state.score - 24);
                    }
                    broadcastAction(this, note.note.owner(), note.note.lane(), note.index, false, true);
                }
            }
        }

        private void tickHolds() {
            tickHold(leftState);
            tickHold(rightState);
        }

        private void tickHold(ParticipantState state) {
            if (state == null) {
                return;
            }

            for (int lane = 0; lane < state.activeHolds.length; lane++) {
                RuntimeNote hold = state.activeHolds[lane];
                if (hold == null || hold.resolved) {
                    state.activeHolds[lane] = null;
                    continue;
                }

                int endTick = hold.note.tick() + hold.note.durationTicks();
                if (!state.ai) {
                    if (playTicks < endTick + CLIENT_JUDGEMENT_FALLBACK_TICKS) {
                        continue;
                    }
                    hold.resolved = true;
                    hold.started = false;
                    hold.hitResolved = true;
                    hold.missedResolved = false;
                    state.activeHolds[lane] = null;
                    state.score += Math.max(20, hold.note.durationTicks() / 2);
                    broadcastAction(this, state.owner, hold.note.lane(), hold.index, true, true);
                    continue;
                }
                if (playTicks >= endTick) {
                    hold.resolved = true;
                    hold.hitResolved = true;
                    hold.missedResolved = false;
                    state.activeHolds[lane] = null;
                    state.score += Math.max(20, hold.note.durationTicks() / 2);
                    broadcastAction(this, state.owner, hold.note.lane(), hold.index, true, true);
                    continue;
                }

                if (state.ai && playTicks > hold.note.tick() + 2 && !state.laneDown[hold.note.lane()]) {
                    hold.resolved = true;
                    hold.hitResolved = false;
                    hold.missedResolved = true;
                    state.activeHolds[lane] = null;
                    state.combo = 0;
                    state.mistakes++;
                    broadcastAction(this, state.owner, hold.note.lane(), hold.index, false, true);
                    continue;
                }

                if (playTicks % 5 == 0) {
                    state.score += 4;
                    broadcastAction(this, state.owner, hold.note.lane(), hold.index, true, false);
                }
            }
        }

        private void tickMobAi(ParticipantState aiState) {
            if (aiState == null || !aiState.ai) {
                return;
            }

            int attemptedLanes = 0;
            for (RuntimeNote note : runtimeNotes) {
                if (note.resolved || note.started || note.aiAttempted || note.note.owner() != aiState.owner) {
                    continue;
                }
                if (playTicks < note.note.tick()) {
                    continue;
                }
                // One decision per note, including blocked notes. Leaving them pending
                // would reroll difficulty or turn same-lane chords into delayed hits.
                note.aiAttempted = true;
                int lane = note.note.lane();
                if (!canHitClaimedNote(note, aiState, aiState.owner, lane, playTicks)) {
                    continue;
                }
                int laneMask = 1 << lane;
                boolean alreadyAttempted = (attemptedLanes & laneMask) != 0;
                attemptedLanes |= laneMask;
                RuntimeNote activeHold = aiState.activeHolds[lane];
                if (alreadyAttempted || playTicks < aiState.laneHitLockoutUntil[lane]
                        || activeHold != null && !activeHold.resolved) {
                    continue;
                }
                if (random.nextFloat() < mobSkill.hitChance) {
                    aiState.laneDown[lane] = true;
                    resolveInput(this, aiState.owner, lane, true, playTicks, note.index);
                    if (note.note.durationTicks() <= 0 || random.nextFloat() < mobSkill.releaseChance) {
                        aiState.laneDown[lane] = false;
                    }
                } else {
                    note.resolved = true;
                    note.hitResolved = false;
                    note.missedResolved = true;
                    aiState.combo = 0;
                    aiState.mistakes++;
                    broadcastAction(this, aiState.owner, note.note.lane(), note.index, false, true);
                }
            }
        }

        /**
         * Duel: both players. Solo or against a mob: the one player. Against the test Rap God master:
         * the player and the NPC, whose choice startVoting records at once. Two mobs: the organizer.
         */
        private Set<UUID> expectedVoters() {
            return VoteTiming.expectedVoters(leftPlayerId, rightPlayerId,
                    testNpc() == null ? null : testNpc().getUUID(), mobControllerId);
        }

        private boolean everyoneVoted() {
            return pendingTransferVoters.isEmpty() && VoteTiming.everyoneVoted(expectedVoters(), votes.keySet());
        }

        /** Tracks of the battle plus the sets voters brought from their own installs (spec 06 section 9.4). */
        private Optional<MusicBattleTracks.Track> trackById(String trackId) {
            for (MusicBattleTracks.Track track : tracks) {
                if (track.id().equals(trackId)) {
                    return Optional.of(track);
                }
            }
            for (MusicBattleTracks.Track track : extraTracks) {
                if (track.id().equals(trackId)) {
                    return Optional.of(track);
                }
            }
            return Optional.empty();
        }

        /** Ticks the vote has left; zero once it has closed, early or on time. */
        private int voteRemainingTicks() {
            return phase != Phase.VOTING || votesResolved ? 0 : VoteTiming.remainingTicks(voteTicks);
        }

        /** Online players who were sent this vote's screen: the participants and a mob battle's organizer. */
        private List<ServerPlayer> voteScreenHolders() {
            List<ServerPlayer> holders = players();
            if (mobControllerId != null && leftEntity.level() instanceof ServerLevel level) {
                ServerPlayer organizer = level.getServer().getPlayerList().getPlayer(mobControllerId);
                if (organizer != null && holders.stream().noneMatch(player -> player.getUUID().equals(mobControllerId))) {
                    holders.add(organizer);
                }
            }
            return holders;
        }

        private boolean hasPlayer(UUID playerId) {
            return playerId != null && (playerId.equals(leftPlayerId) || playerId.equals(rightPlayerId));
        }

        private boolean canVote(UUID playerId) {
            return hasPlayer(playerId) || playerId != null && playerId.equals(mobControllerId);
        }

        private int ownerFor(UUID playerId) {
            if (playerId != null && playerId.equals(playerOnePlayerId)) {
                return 0;
            }
            return playerId != null && playerId.equals(rightPlayerId) ? 1 : -1;
        }

        private ParticipantState state(int owner) {
            return owner == 0 ? leftState : rightState;
        }

        private LivingEntity entity(int owner) {
            return owner == 0 ? leftEntity() : rightEntity();
        }

        private RuntimeNote noteByIndex(int index) {
            for (RuntimeNote note : runtimeNotes) {
                if (note.index == index) {
                    return note;
                }
            }
            return null;
        }

        /**
         * The note a judgement is about when the number it came with names none on that player's panel.
         *
         * <p>Only reached when the client's numbering has drifted from this one. The caller still checks the
         * note it gets back against the same windows a numbered note goes through, so this only has to offer
         * the candidate the player most plausibly meant - and a lane that is holding something can only mean
         * the note it is holding.
         */
        private RuntimeNote noteByTiming(int kind, ParticipantState state, int owner, int lane, int clientTick) {
            if (kind == ClientJudgementPayload.HOLD_COMPLETE || kind == ClientJudgementPayload.HOLD_BREAK) {
                return state.activeHolds[lane];
            }
            RuntimeNote closest = null;
            int closestDistance = Integer.MAX_VALUE;
            for (RuntimeNote note : runtimeNotes) {
                if (note.resolved || note.started || note.note.owner() != owner || note.note.lane() != lane) {
                    continue;
                }
                if (kind == ClientJudgementPayload.NOTE_MISS
                        && clientTick - note.note.tick() < MISS_AFTER_PANEL_EXIT_TICKS) {
                    continue;
                }
                int distance = Math.abs(note.note.tick() - clientTick);
                if (distance < closestDistance) {
                    closest = note;
                    closestDistance = distance;
                }
            }
            return closest;
        }

        private LivingEntity leftEntity() {
            return leftEntity.isRemoved() ? null : leftEntity;
        }

        private LivingEntity rightEntity() {
            return rightEntity.isRemoved() ? null : rightEntity;
        }

        private List<ServerPlayer> players() {
            List<ServerPlayer> players = new ArrayList<>();
            if (leftEntity() instanceof ServerPlayer left) {
                players.add(left);
            }
            if (rightEntity() instanceof ServerPlayer right) {
                players.add(right);
            }
            return players;
        }

        private boolean allPreloadRecipientsReady() {
            for (UUID recipientId : preloadRecipientIds) {
                if (!readyPlayers.contains(recipientId)) {
                    return false;
                }
            }
            return true;
        }

        private List<ServerPlayer> watchers() {
            List<ServerPlayer> watchers = new ArrayList<>(players());
            if (leftEntity.level() instanceof ServerLevel level) {
                if (mobControllerId != null) {
                    ServerPlayer controller = level.getServer().getPlayerList().getPlayer(mobControllerId);
                    if (controller != null && watchers.stream().noneMatch(player -> player.getUUID().equals(mobControllerId))) {
                        watchers.add(controller);
                    }
                }
                for (UUID spectatorId : spectatorIds) {
                    if (hasPlayer(spectatorId)) {
                        continue;
                    }
                    ServerPlayer spectator = level.getServer().getPlayerList().getPlayer(spectatorId);
                    if (spectator != null && watchers.stream().noneMatch(player -> player.getUUID().equals(spectatorId))) {
                        watchers.add(spectator);
                    }
                }
                for (UUID spectatorId : preloadSpectatorIds) {
                    if (hasPlayer(spectatorId)) {
                        continue;
                    }
                    ServerPlayer spectator = level.getServer().getPlayerList().getPlayer(spectatorId);
                    if (spectator != null && watchers.stream().noneMatch(player -> player.getUUID().equals(spectatorId))) {
                        watchers.add(spectator);
                    }
                }
            }
            return watchers;
        }

        private boolean hasAnyPlayer() {
            return leftPlayerId != null || rightPlayerId != null;
        }

        private boolean isActivePhase() {
            return phase == Phase.VOTING || phase == Phase.ROULETTE || phase == Phase.PREPARING || phase == Phase.PLAYING;
        }

        private Component resultMessage(Component fallback) {
            if (leftState == null || rightState == null || track == null) {
                return fallback.copy().withStyle(ChatFormatting.AQUA);
            }
            if (solo) {
                MutableComponent soloMessage = Component.literal("Blocky Music: ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(track.title()).withStyle(ChatFormatting.WHITE))
                        .append(Component.translatable("music_battle.l10n.server.solo_completed_score").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(Integer.toString(leftState.score)).withStyle(ChatFormatting.WHITE));
                if (leftState.mistakes == 0) {
                    soloMessage.append(Component.literal(" FULL COMBO").withStyle(ChatFormatting.GREEN));
                }
                if (speedMultiplier > 1.01F) {
                    soloMessage.append(Component.translatable("music_battle.l10n.server.speed_suffix", formatSpeedMultiplier(speedMultiplier))
                            .withStyle(ChatFormatting.GOLD));
                }
                return soloMessage.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
            }
            Component winner;
            if (leftState.score == rightState.score) {
                winner = Component.translatable("music_battle.l10n.server.tie").withStyle(ChatFormatting.YELLOW);
            } else {
                winner = leftState.score > rightState.score ? coloredNameComponent(leftEntity, leftState) : coloredNameComponent(rightEntity, rightState);
            }
            MutableComponent message = Component.literal("Blocky Music: ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(track.title()).withStyle(ChatFormatting.WHITE))
                    .append(Component.translatable("music_battle.l10n.server.track_completed").withStyle(ChatFormatting.GRAY))
                    .append(coloredNameComponent(leftEntity, leftState))
                    .append(Component.literal(" " + leftState.score).withStyle(ChatFormatting.WHITE));
            if (leftState.mistakes == 0) {
                message.append(Component.literal(" FULL COMBO").withStyle(ChatFormatting.GREEN));
            }
            message.append(Component.literal(" : " + rightState.score + " ").withStyle(ChatFormatting.GRAY))
                    .append(coloredNameComponent(rightEntity, rightState));
            if (rightState.mistakes == 0) {
                message.append(Component.literal(" FULL COMBO").withStyle(ChatFormatting.GREEN));
            }
            message.append(Component.translatable("music_battle.l10n.server.winner_label").withStyle(ChatFormatting.GRAY))
                    .append(winner)
                    .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
            if (deadlyParty && leftState.score == rightState.score) {
                message.append(Component.translatable("music_battle.l10n.server.both_survived").withStyle(ChatFormatting.GREEN));
            }
            List<Component> modifiers = new ArrayList<>();
            if (unifiedNotes) modifiers.add(Component.translatable("music_battle.l10n.server.modifier_unified"));
            if (deadlyParty) modifiers.add(Component.translatable("music_battle.l10n.server.modifier_deadly"));
            if (speedMultiplier > 1.01F) modifiers.add(Component.translatable("music_battle.l10n.server.modifier_speed", formatSpeedMultiplier(speedMultiplier)));
            if (leftState.ai || rightState.ai) modifiers.add(Component.translatable("music_battle.l10n.server.modifier_mob_skill", mobSkillDisplayName(mobSkill)));
            MutableComponent modifierList = Component.empty();
            for (Component modifier : modifiers) {
                if (!modifierList.getSiblings().isEmpty()) modifierList.append(", ");
                modifierList.append(modifier);
            }
            message.append(Component.translatable("music_battle.l10n.server.modifiers", modifiers.isEmpty() ? Component.translatable("music_battle.l10n.server.no_modifiers") : modifierList)
                    .withStyle(ChatFormatting.DARK_AQUA));
            return message;
        }

        private static Component mobSkillDisplayName(MobSkill skill) {
            return switch (skill) {
                case EASY -> Component.translatable("music_battle.l10n.server.mob_skill_easy");
                case MEDIUM -> Component.translatable("music_battle.l10n.server.mob_skill_medium");
                case HARD -> Component.translatable("music_battle.l10n.server.mob_skill_hard");
                case GOD -> Component.translatable("music_battle.l10n.server.mob_skill_god");
                case PERFECT -> Component.translatable("music_battle.l10n.server.mob_skill_perfect");
            };
        }
    }

    private static final class RuntimeNote {
        private final int index;
        private final BattleNote note;
        private boolean started;
        private boolean resolved;
        private boolean hitResolved;
        private boolean missedResolved;
        private boolean aiAttempted;

        private RuntimeNote(int index, BattleNote note) {
            this.index = index;
            this.note = note;
        }
    }

    private static final class ParticipantState {
        private final int owner;
        private final UUID playerId;
        private final int entityId;
        private float speed;
        private NoteAppearance appearance;
        private final int color;
        private final List<String> keyLabels;
        private final boolean ai;
        private final PanelStyle panelStyle;
        private final boolean[] laneDown = new boolean[4];
        private final RuntimeNote[] activeHolds = new RuntimeNote[4];
        private final int[] laneHitLockoutUntil = new int[4];
        private int score;
        private int combo;
        private int maxCombo;
        private int mistakes;
        private int bopTicks;
        private int lastClientJudgementSequence;

        private ParticipantState(int owner, UUID playerId, int entityId, float speed, int color, List<String> keyLabels, boolean ai, PanelStyle panelStyle, NoteAppearance appearance) {
            this.owner = owner;
            this.playerId = playerId;
            this.entityId = entityId;
            this.speed = speed;
            this.appearance = appearance;
            this.color = color;
            this.keyLabels = keyLabels;
            this.ai = ai;
            this.panelStyle = panelStyle;
        }

    }

    private record PanelStyle(float panelOpacity, float laneOpacity, float widthScale, float heightScale, float noteWidthScale, float noteHeightScale) {
        private static final PanelStyle DEFAULT = new PanelStyle(PlayerPanelDefaults.PANEL_OPACITY,
                PlayerPanelDefaults.LANE_OPACITY, PlayerPanelDefaults.PANEL_WIDTH_SCALE,
                PlayerPanelDefaults.PANEL_HEIGHT_SCALE, PlayerPanelDefaults.NOTE_WIDTH_SCALE,
                PlayerPanelDefaults.NOTE_HEIGHT_SCALE);

        private static PanelStyle random(Random random) {
            return new PanelStyle(
                    0.45F + random.nextFloat() * 0.55F,
                    0.45F + random.nextFloat() * 0.75F,
                    0.72F + random.nextFloat() * 0.55F,
                    0.82F + random.nextFloat() * 0.38F,
                    0.78F + random.nextFloat() * 0.42F,
                    0.78F + random.nextFloat() * 0.42F
            );
        }
    }
}
