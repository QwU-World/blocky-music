package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.MusicStreamRetry;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.SongDriftAnchor;
import ru.gora.musicbattle.data.BattleSkipClock;
import ru.gora.musicbattle.data.MusicStartAlignment;
import ru.gora.musicbattle.data.BattleCameraShot;
import ru.gora.musicbattle.data.BattleScoreMath;
import ru.gora.musicbattle.client.workshop.layout.MenuCornerButtons;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.data.BattleAnimationInput;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.NoteSpeed;
import ru.gora.musicbattle.data.CalibrationTiming;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.network.BattleActionPayload;
import ru.gora.musicbattle.network.BattleLaneAnimationPayload;
import ru.gora.musicbattle.network.BattleAppearancePayload;
import ru.gora.musicbattle.network.BattleAudiencePayload;
import ru.gora.musicbattle.network.BattleCameraSyncPayload;
import ru.gora.musicbattle.network.BattleClientErrorPayload;
import ru.gora.musicbattle.network.BattleStartPayload;
import ru.gora.musicbattle.network.BattleStopPayload;
import ru.gora.musicbattle.network.CameraEditorClosedPayload;
import ru.gora.musicbattle.network.CameraFollowStatePayload;
import ru.gora.musicbattle.network.ClientJudgementPayload;
import ru.gora.musicbattle.network.NoteInputPayload;
import ru.gora.musicbattle.network.OpenVotePayload;
import ru.gora.musicbattle.network.OpenRapGodConfigPayload;
import ru.gora.musicbattle.network.OpenSpeakerConfigPayload;
import ru.gora.musicbattle.network.OpenDjPanelPayload;
import ru.gora.musicbattle.network.OpenCameraEditorPayload;
import ru.gora.musicbattle.network.PauseSkipJumpPayload;
import ru.gora.musicbattle.network.PauseSkipStatusPayload;
import ru.gora.musicbattle.network.OpenChallengeSidePayload;
import ru.gora.musicbattle.network.BattleMicrophonePayload;
import ru.gora.musicbattle.network.PauseSkipVotePayload;
import ru.gora.musicbattle.network.PublishTrackResultPayload;
import ru.gora.musicbattle.network.DeletePublishedTrackResultPayload;
import ru.gora.musicbattle.network.DjAssetReadyPayload;
import ru.gora.musicbattle.network.DjAudienceCameraPayload;
import ru.gora.musicbattle.network.DjCameraPreviewPayload;
import ru.gora.musicbattle.network.PublishedTracksPayload;
import ru.gora.musicbattle.network.SurrenderPayload;
import ru.gora.musicbattle.network.SpectatorMusicPayload;
import ru.gora.musicbattle.network.SpectatorBattlePayload;
import ru.gora.musicbattle.network.SpectatorStopPayload;
import ru.gora.musicbattle.network.TrackPreloadPayload;
import ru.gora.musicbattle.network.TrackPreviewRequestPayload;
import ru.gora.musicbattle.network.TrackReadyPayload;
import ru.gora.musicbattle.network.VoteStatusPayload;
import ru.gora.musicbattle.server.MusicBattleTracks;

public final class ClientBattleController {
    private static final int HIT_WINDOW_TICKS = 16;
    private static final int MISS_AFTER_PANEL_EXIT_TICKS = 7;
    private static final int EARLY_HIT_WINDOW_TICKS = 6;
    private static final int EARLY_FAIL_TICKS = 11;
    private static final int SAME_LANE_HIT_LOCKOUT_TICKS = 2;
    private static final int HOLD_RELEASE_EARLY_TICKS = 2;
    private static final int INTRO_TICKS = 40;
    /** Share of the way to the newest frame of the automatic shot each tick; see tickBattleCameraSync. */
    private static final float AUTOMATIC_SHOT_FOLLOW = 0.5F;
    /** The same for a camera block's shot, whose edits arrive seldom and are worth easing in. */
    private static final float BLOCK_SHOT_FOLLOW = 0.42F;
    private static final int MUSIC_DOWNLOAD_TIMEOUT_TICKS = 20 * 60 * 5;
    /** How long a file the player was said to already have may take to appear before the claim is disbelieved. */
    private static final int STALE_CONTENT_GRACE_TICKS = 20 * 8;
    private static final int SPECTATOR_DOWNLOAD_TIMEOUT_TICKS = 20 * 45;
    private static final int SPECTATOR_STREAM_START_TIMEOUT_TICKS = 20 * 6;
    private static final String[] DEFAULT_LABELS = {"<", "v", "^", ">"};
    private static boolean active;
    private static boolean activeSolo;
    private static UUID battleId;
    private static UUID surrenderedBattleId;
    private static int durationTicks;
    private static int trackBpm = 120;
    private static int localTicks;
    private static String trackTitle = "";
    private static String leftName = "";
    private static String rightName = "";
    private static int ownOwner;
    private static float musicPlaybackSpeed = 1.0F;
    private static float ownSpeed = 1.0F;
    private static float opponentSpeed = 1.0F;
    private static float trackVolume = 1.0F;
    private static int ownColor = 0xFF37BFE8;
    private static final NoteAppearance[] noteAppearances = {NoteAppearance.solid(0xFF37BFE8), NoteAppearance.solid(0xFFFF77A9)};
    private static final NoteAppearance[] spectatorNoteAppearances = {NoteAppearance.solid(0xFF37BFE8), NoteAppearance.solid(0xFFFF77A9)};
    private static int opponentColor = 0xFFFF5C7A;
    private static final float[][] panelStyles = {
            {1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F},
            {1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F}
    };
    private static final float[][] spectatorPanelStyles = {
            {1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F},
            {1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F}
    };
    private static int leftScore;
    private static int rightScore;
    private static int ownCombo;
    private static int ownMistakes;
    private static int judgementSequence;
    private static int opponentCombo;
    private static int opponentEntityId;
    private static int leftEntityId;
    private static int rightEntityId;
    private static int cameraEntityId;
    private static float cameraYaw;
    private static float cameraPitch;
    /**
     * The direction of the entity-driven battle shot one client tick ago and now. The server re-aims
     * an automatic shot at the arena centre every tick as it swings; stepping this pair once a tick and
     * blending it per frame keeps the turn as smooth as the position the camera entity interpolates.
     */
    private static float aimYawFrom;
    private static float aimYawTo;
    private static float aimPitchFrom;
    private static float aimPitchTo;
    private static float cameraFov;
    private static final BattleCameraInterpolation activeCameraInterpolation = new BattleCameraInterpolation();
    private static float startYaw;
    private static float startPitch;
    private static SoundInstance music;
    private static SoundInstance spectatorMusic;
    private static String spectatorMusicTrackId = "";
    private static String spectatorMusicAudioFile = "";
    private static String spectatorMusicAudioHash = "";
    private static float spectatorPlaybackSpeed = 1.0F;
    private static volatile long musicStartedAtNanos;
    /**
     * When this battle's tick zero falls on this machine's clock, from the delay the server sent.
     *
     * The chart used to be driven by whenever this machine's ogg actually began streaming, which is late by however
     * long the file took to open, and differently late on each computer. Now the clock is the battle's, the audio
     * is steered to match it, and both players are on the same note at the same moment.
     */
    private static volatile long startAtNanos;
    private static volatile long spectatorMusicStartedAtNanos;
    /** Volatile because the song's stream reads the battle clock on the sound thread; see battleTickForStreamStart. */
    private static volatile long musicPausedAtNanos;
    private static int musicClockStartTick;
    /**
     * The battle tick the playing song's first sample belongs to, as its stream decided when it started (see
     * AlignedStartAudioStream); NaN until then. The drift check measures the song from here, not from the tick the
     * seek asked for, which the stream may have moved by however long it took to open.
     */
    private static volatile double musicStreamStartTick = Double.NaN;
    /**
     * How long this machine takes from lining a battle song's stream up to the speakers starting it, in ticks: the
     * stream is lined up that far ahead of the clock (battleTickForStreamStart). Minecraft decodes the stream's first
     * four seconds in between, so without it every start came out that much behind. Learnt from each start
     * (MusicStartAlignment#nextStartLead), kept for the session, read on the sound thread.
     */
    private static volatile double musicStartLeadTicks = MusicStartAlignment.DEFAULT_START_LEAD_TICKS;
    private static int spectatorMusicClockStartTick;
    private static PendingSpectatorMusic pendingSpectatorMusic;
    private static SpectatorMusicPayload lastSpectatorMusicPayload;
    private static boolean spectatorMusicAwaitingStart;
    private static int spectatorMusicStartWaitTicks;
    private static boolean spectatorMusicStartFreezeDisabled;
    /**
     * The sound engine turned the spectator stream down on the spot (see startSpectatorClockWithoutStream), so the FX
     * and notes clock runs from the moment it was asked for instead of waiting for a stream that never comes.
     */
    private static boolean spectatorMusicRefused;
    /** And it did so because the game's master volume was 0: turning the volume up brings the song in again. */
    private static boolean spectatorMusicRefusedMuted;
    private static int spectatorMusicInactiveTicks;
    private static int spectatorMusicRecoveryCooldown;
    private static boolean preloadingTrack;
    private static UUID preloadBattleId;
    private static String preloadTrackId = "";
    private static String preloadTrackTitle = "";
    private static String preloadChartFile = "";
    private static String preloadChartHash = "";
    private static int preloadChartBytes;
    private static String preloadAudioFile = "";
    private static String preloadAudioHash = "";
    private static int preloadAudioBytes;
    private static int preloadTicks;
    private static boolean preloadReadySent;
    private static List<BattleNote> preloadedChartNotes = List.of();
    private static List<TrackEffect> preloadedChartEffects = List.of();
    private static boolean preloadedChartLoaded;
    private static boolean preloadedDisableStageBars;
    private static int preloadedOffsetMs;
    private static boolean preloadedHideStandardDimming;
    private static UUID spectatorBattleId;
    private static int spectatorDurationTicks;
    private static int spectatorLocalTicks;
    private static int spectatorTrackBpm = 120;
    /** Beat 1 of the watched battle or DJ song on the spectator clock, in ms at its speed (SpectatorBattlePayload). */
    private static int spectatorBeatOffsetMs;
    private static int spectatorLeftEntityId;
    private static int spectatorRightEntityId;
    private static int spectatorCameraEntityId;
    private static float spectatorCameraYaw;
    private static float spectatorCameraPitch;
    private static float spectatorCameraFov;
    private static boolean spectatorCameraEnabled;
    /**
     * The viewer's persistent intent to use the show camera.  This is kept
     * separate from {@link #spectatorCameraEnabled}: a DJ camera can be
     * temporarily unavailable while its chunk or updated snapshot arrives.
     * In that case we return rendering to the player's body without silently
     * forgetting the toggle, then resume the selected shot automatically.
     */
    private static boolean spectatorCameraRequested;
    private static UUID syncedCameraBattleId;
    private static int syncedCameraEntityId;
    private static double syncedCameraX;
    private static double syncedCameraY;
    private static double syncedCameraZ;
    private static float syncedCameraYaw;
    private static float syncedCameraPitch;
    private static float syncedCameraFov;
    private static long syncedCameraRevision = -1L;
    /** The battle uses a camera block's shot (a positive field of view); its frames are blended, not stepped. */
    private static boolean syncedCameraAvailable;
    /**
     * The battle uses the automatic shot, which the server now sends frame by frame with a zero field of view (the
     * viewer's own). Kept apart from {@link #syncedCameraAvailable} because that one also decides the field of view.
     */
    private static boolean syncedCameraAutomatic;
    /**
     * Added to the battle time for the view's own slow sway, less every tick a skipped rest jumped over, so a skip
     * does not snap the sway to where it would have stood after the rest. Reset with each battle.
     */
    private static float cameraSwayShift;
    private static boolean standaloneDjPlayback;
    private static BlockPos djAudienceCameraPos = BlockPos.ZERO;
    private static Vec3 djAudienceCameraTargetPosition = Vec3.ZERO;
    private static float djAudienceCameraTargetYaw;
    private static float djAudienceCameraTargetPitch;
    private static float djAudienceCameraTargetFov = 70.0F;
    private static long djAudienceCameraRevision = -1L;
    private static boolean djAudienceCameraAvailable;
    private static Vec3 spectatorCameraViewPosition = Vec3.ZERO;
    private static Vec3 spectatorCameraPreviousViewPosition = Vec3.ZERO;
    private static boolean spectatorCameraViewPositionValid;
    private static float spectatorCameraPreviousYaw;
    private static float spectatorCameraPreviousPitch;
    private static float spectatorCameraPreviousFov;
    private static float spectatorFxVisibility;
    private static float spectatorFxVisibilityPrevious;
    private static int spectatorLeftColor;
    private static int spectatorRightColor;
    private static float spectatorLeftSpeed = 1.0F;
    private static float spectatorRightSpeed = 1.0F;
    private static double spectatorSoundX;
    private static double spectatorSoundY;
    private static double spectatorSoundZ;
    private static float spectatorSoundRadius = 14.0F;
    private static float spectatorTrackVolume = 1.0F;
    private static float spectatorLowGainDb;
    private static float spectatorMidGainDb;
    private static float spectatorHighGainDb;
    private static int spectatorLeftScore;
    private static int spectatorRightScore;
    private static final int[] spectatorCombo = new int[2];
    private static final List<ClientNote> spectatorNotes = new ArrayList<>();
    private static List<TrackEffect> spectatorEffects = List.of();
    private static int djReleaseEndTick;
    private static final ClientDjFxTransition djTransitions = new ClientDjFxTransition();
    private static Set<Integer> battleSpectatorEntityIds = Set.of();
    private static Set<Integer> battleMobEntityIds = Set.of();
    private static final int[][] spectatorLaneFlashes = new int[2][4];
    private static final int[][] spectatorHitBursts = new int[2][4];
    private static final int[] spectatorAnimationTriggerSerial = new int[2];
    private static final int[] spectatorAnimationTriggerLane = {-1, -1};
    private static final int[] spectatorAnimationHeldMask = new int[2];
    private static final int[] spectatorAnimationInputHeldMask = new int[2];
    private static final float[] spectatorAnimationTriggerTick = {-Float.MAX_VALUE, -Float.MAX_VALUE};
    private static final int[] spectatorAnimationJudgedSerial = new int[2];
    private static final int[] spectatorAnimationJudgedLane = {-1, -1};
    private static final float[] spectatorAnimationJudgedTick = {-Float.MAX_VALUE, -Float.MAX_VALUE};
    private static String activeTrackId = "";
    private static String activeAudioFile = "";
    private static String activePreviewFile = "";
    private static boolean musicPausedForGame;
    private static boolean spectatorMusicPausedForGame;
    private static long spectatorMusicPausedAtNanos;
    /**
     * The instant the sounds came back from the pause menu (onScreenClosing), for the battle clock's release at the end
     * of that frame; 0 when none is waiting. See gameResumeInstant.
     */
    private static long gameResumedAtNanos;
    /** The screen the one now closing makes way for, from the ScreenEvent.Opening before it; see onScreenClosing. */
    private static Screen screenComingNext;
    private static boolean musicStarted;
    /** The battle's song got no channel when it was last played, and whether it was silenced then; see MusicStreamRetry. */
    private static boolean musicRefused;
    private static boolean musicRefusedSilenced;
    private static int musicWaitTicks;
    private static boolean clientErrorReported;
    private static volatile String pendingAudioError;
    private static final List<ClientNote> notes = new ArrayList<>();
    private static List<TrackEffect> activeEffects = List.of();
    private static boolean activeDisableStageBars;
    /** Where beat 1 falls on this battle's timeline; the beat pulses count from it instead of from 0. */
    private static int activeOffsetMs;
    private static boolean activeHideStandardDimming;
    private static final int[][] laneFlashes = new int[2][4];
    private static final int[][] hitBursts = new int[2][4];
    /**
     * A mob's actions that came before their tick (BattleActionPayload#showAtTick), in the order they came, each
     * waiting for the battle clock to reach it; see showDueActions. The spectator's queue follows the spectator clock.
     */
    private static final List<HeldAction> heldActions = new ArrayList<>();
    private static final List<HeldAction> spectatorHeldActions = new ArrayList<>();
    /** The battle song's real place against the battle clock, read once a client tick: see SongDriftAnchor. */
    private static final SongDriftAnchor songDrift = new SongDriftAnchor();
    /**
     * When a short hold of the battle clock ends, 0 for none: the drift correction holds the clock back this long
     * (musicPausedAtNanos is its start) when the song has fallen behind the chart, the song playing on meanwhile.
     */
    private static long clockHoldUntilNanos;
    /**
     * The same for the song of the battle or DJ booth this client watches, against the spectator clock: the pause
     * menu pauses and resumes that song on OpenAL's mixing steps too, and the spectator clock is held and let go on the
     * exact instants, so in single player the watched song drifted from its beatmap and effects by every visit, about
     * 58 ms after twenty of them, and stayed there for the rest of the song. See followSpectatorSongPosition.
     */
    private static final SongDriftAnchor spectatorSongDrift = new SongDriftAnchor();
    /** When a short hold of the spectator clock ends, 0 for none; spectatorMusicPausedAtNanos is its start. */
    private static long spectatorClockHoldUntilNanos;
    /** Whether that hold holds the integrated server's battle time too (BattlePauseClock): a watched battle's. */
    private static boolean spectatorClockHoldMovesServer;
    /** The lane flashes and hit bursts of actions shown from those queues, counted down on the battle clock. */
    private static final TimedLaneFlashes timedLaneFlashes = new TimedLaneFlashes();
    private static final TimedLaneFlashes spectatorTimedLaneFlashes = new TimedLaneFlashes();
    /**
     * The other side's actions numbered in the order they come here, which for each side is the order the server made
     * them and the order they are shown in (a side's held actions are shown in turn, and a player's as they come), and
     * per side the number of the action whose score and combo are on show; see showRemoteState. A spectator's refresh of
     * the battle it watches (startSpectatorBattle) takes a number too, for both sides. Never reset: a new battle's
     * numbers are higher.
     *
     * <p>An action held for its tick takes its number as it comes, not as it is shown (HeldAction): numbered when shown,
     * a mob's actions that were still waiting when a refresh of the watched battle came took numbers after the
     * refresh's, and their older score and combo were shown over its newer ones for up to AI_LEAD_TICKS.
     */
    private static long remoteStateArrivals;
    private static final long[] activeRemoteStateShown = new long[2];
    private static final long[] spectatorRemoteStateShown = new long[2];
    private static final int[] animationTriggerSerial = new int[2];
    private static final int[] animationTriggerLane = {-1, -1};
    private static final int[] animationHeldMask = new int[2];
    private static final int[] animationInputHeldMask = new int[2];
    private static final float[] animationTriggerTick = {-Float.MAX_VALUE, -Float.MAX_VALUE};
    /**
     * The judgements of key presses, kept apart from the presses above (key-downs and mobs' hits): on a side that plays
     * with keys the key going down is the press, and its judgement, before or long after it, only confirms it. The
     * same serial, lane and tick as a press; see ClientBattleAnimation.update.
     */
    private static final int[] animationJudgedSerial = new int[2];
    private static final int[] animationJudgedLane = {-1, -1};
    private static final float[] animationJudgedTick = {-Float.MAX_VALUE, -Float.MAX_VALUE};
    private static final String[][] laneLabels = {
            DEFAULT_LABELS.clone(),
            DEFAULT_LABELS.clone()
    };
    private static final boolean[] previousLaneDown = new boolean[4];
    private static final int[] laneHitLockoutUntil = new int[4];
    private static int settingsSyncTicks;
    private static boolean pauseSkipAvailable;
    private static int pauseSkipVotes;
    private static int pauseSkipRequired = 1;
    private static boolean pauseSkipSpaceDown;
    private static final SceneVisibilityCache sceneVisibility = new SceneVisibilityCache();

    private ClientBattleController() {
    }

    /** True only for the local player actively playing a battle, never for spectators. */
    public static boolean blocksParticipantInput() {
        return active;
    }

    public static boolean shouldHideEntityNameTags() {
        return active || spectatorBattleId != null && !standaloneDjPlayback || hidesGameHud();
    }

    public static boolean shouldForceVanillaPlayer(Entity entity) {
        if (!(entity instanceof AbstractClientPlayer)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TrackGameplayPreviewScreen && entity == minecraft.player) {
            return true;
        }
        return isParticipantEntity(entity.getId());
    }

    public static boolean shouldRenderBattleMicrophone(Entity entity) {
        return entity != null && isParticipantEntity(entity.getId());
    }

    public static void openCameraEditor(OpenCameraEditorPayload payload) {
        CameraEditorClient.open(payload);
    }

    public static void closeCameraEditor(CameraEditorClosedPayload payload) {
        CameraEditorClient.closeFromServer(payload);
    }

    public static void acceptCameraFollowState(CameraFollowStatePayload payload) {
        CameraEditorClient.acceptFollowState(payload);
    }

    /** The microphone asks which side to take before a challenge to a player, or a battle with a mob, begins. */
    public static void openChallengeSide(OpenChallengeSidePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        // Over a menu the player is already busy with, the question would steal it; the challenge is simply not sent.
        if (minecraft.player != null && minecraft.screen == null) {
            minecraft.setScreen(new ChallengeSideScreen(null, payload.targetId(), payload.targetName()));
        }
    }

    /** Who to draw holding a microphone during a battle; see {@link ClientBattleMicrophone}. */
    public static void acceptBattleMicrophone(BattleMicrophonePayload payload) {
        ClientBattleMicrophone.accept(payload.entityId(), payload.holding());
    }

    public static void openVote(OpenVotePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientTrackAssets.cacheTrackSummaries(payload.tracks());
        if (minecraft.player != null) {
            minecraft.setScreen(new MusicBattleVoteScreen(payload));
        }
    }

    public static void openRapGodConfig(OpenRapGodConfigPayload payload) {
        Minecraft.getInstance().setScreen(new RapGodConfigScreen(payload));
    }

    public static void openSpeakerConfig(OpenSpeakerConfigPayload payload) {
        Minecraft.getInstance().setScreen(new SpeakerConfigScreen(payload));
    }

    public static void openDjPanel(OpenDjPanelPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        DjPanelScreen panel = ScreenStack.find(DjPanelScreen.class);
        DjCameraGalleryScreen galleryScreen = ScreenStack.find(DjCameraGalleryScreen.class);
        if (panel != null && panel.isForPanel(payload.panelPos())) {
            panel.applyServerState(payload);
        } else if (galleryScreen != null && galleryScreen.isForPanel(payload.panelPos())) {
            DjCameraGalleryScreen gallery = galleryScreen;
            gallery.applyServerState(payload);
        } else if (minecraft.screen instanceof DjPanelOwnedScreen nested) {
            DjPanelScreen owner = nested.owningDjPanel();
            if (owner != null && owner.isForPanel(payload.panelPos())) {
                // Keep an in-progress FX inspector or preset browser open while
                // refreshing its underlying panel with authoritative server state.
                owner.applyServerState(payload);
            } else if (ru.gora.musicbattle.block.DjPanelOpenRequests.consume(payload.panelPos())) {
                minecraft.setScreen(new DjPanelScreen(payload));
            }
        } else if (ru.gora.musicbattle.block.DjPanelOpenRequests.consume(payload.panelPos())) {
            // Only the answer to a right-click opens the panel: a refresh still on its way after Esc used to put the
            // closed panel back on screen.
            minecraft.setScreen(new DjPanelScreen(payload));
        }
    }

    public static void acceptDjCameraPreview(DjCameraPreviewPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        DjPanelScreen previewPanel = ScreenStack.find(DjPanelScreen.class);
        if (previewPanel != null && previewPanel.isForPanel(payload.panelPos())) {
            previewPanel.acceptCameraPreview(payload);
        } else if (minecraft.screen instanceof DjPanelOwnedScreen nested) {
            DjPanelScreen owner = nested.owningDjPanel();
            if (owner != null && owner.isForPanel(payload.panelPos())) {
                owner.acceptCameraPreview(payload);
            }
        }
    }

    public static void acceptBattleCameraSync(BattleCameraSyncPayload payload) {
        if (payload.cameraEntityId() <= 0
                || !Double.isFinite(payload.x())
                || !Double.isFinite(payload.y())
                || !Double.isFinite(payload.z())
                || !Float.isFinite(payload.yaw())
                || !Float.isFinite(payload.pitch())
                || !Float.isFinite(payload.fov())) {
            return;
        }
        boolean activeMatch = active && battleId != null && battleId.equals(payload.battleId())
                && cameraEntityId == payload.cameraEntityId();
        boolean spectatorMatch = !active && spectatorBattleId != null && spectatorBattleId.equals(payload.battleId())
                && spectatorCameraEntityId == payload.cameraEntityId();
        if (!activeMatch && !spectatorMatch) {
            return;
        }
        boolean sameCamera = payload.battleId().equals(syncedCameraBattleId)
                && syncedCameraEntityId == payload.cameraEntityId();
        if (sameCamera && payload.revision() <= syncedCameraRevision) {
            return;
        }
        // A trusted, identity/revision-checked server update uses zero FOV for the automatic arena shot, which keeps
        // the viewer's own field of view; every positive camera FOV remains clamped.
        boolean blockShot = payload.fov() > 0;
        // A cut (another battle or camera, a switch between a camera block's shot and the automatic one, or a frame
        // further away than any shot moves in a tick) is shown at once; anything else is the next frame of the same
        // shot and is blended in tickBattleCameraSync. The automatic shot used to be shown here as it came, which
        // stepped its direction twenty times a second while its position slid along with the helper stand three ticks
        // behind: that was the jerky turning.
        boolean cut = BattleCameraShot.isCut(sameCamera, blockShot, syncedCameraAvailable, syncedCameraAutomatic,
                payload.x() - syncedCameraX, payload.y() - syncedCameraY, payload.z() - syncedCameraZ);
        syncedCameraBattleId = payload.battleId();
        syncedCameraEntityId = payload.cameraEntityId();
        syncedCameraX = payload.x();
        syncedCameraY = payload.y();
        syncedCameraZ = payload.z();
        syncedCameraYaw = Mth.wrapDegrees(payload.yaw());
        syncedCameraPitch = Mth.clamp(payload.pitch(), -89.0F, 89.0F);
        syncedCameraFov = blockShot ? Mth.clamp(payload.fov(), 30.0F, 110.0F) : 0.0F;
        syncedCameraRevision = payload.revision();
        syncedCameraAvailable = blockShot;
        syncedCameraAutomatic = !blockShot;
        if (activeMatch && (cut || !activeCameraInterpolation.valid())) {
            activeCameraInterpolation.snap(payload.x(), payload.y(), payload.z(),
                    syncedCameraYaw, syncedCameraPitch, syncedCameraFov);
            cameraYaw = syncedCameraYaw;
            cameraPitch = syncedCameraPitch;
            cameraFov = syncedCameraFov;
            if (!blockShot) {
                // The intro blends from startYaw, and the aim pair is what a frame falls back on: both have to be
                // the new shot's at once, or the view keeps the camera block's angle and then swings across.
                startYaw = cameraYaw;
                startPitch = cameraPitch;
                aimYawFrom = aimYawTo = cameraYaw;
                aimPitchFrom = aimPitchTo = cameraPitch;
            }
        }
        if (spectatorMatch && (cut || !spectatorCameraViewPositionValid)) {
            spectatorCameraFov = syncedCameraFov;
            spectatorCameraYaw = syncedCameraYaw;
            spectatorCameraPitch = syncedCameraPitch;
            spectatorCameraViewPosition = new Vec3(payload.x(), payload.y(), payload.z());
            spectatorCameraViewPositionValid = true;
            snapSpectatorCameraInterpolation();
        }
    }

    public static void acceptDjAudienceCamera(DjAudienceCameraPayload payload) {
        if (active || spectatorBattleId == null || !standaloneDjPlayback
                || !spectatorBattleId.equals(payload.playbackId())) {
            return;
        }
        boolean sameCamera = payload.cameraPos().equals(djAudienceCameraPos);
        Vec3 incomingPosition = new Vec3(payload.x(), payload.y(), payload.z());
        boolean exactDuplicate = sameCamera
                && payload.revision() == djAudienceCameraRevision
                && payload.available() == djAudienceCameraAvailable
                && (!payload.available() || incomingPosition.equals(djAudienceCameraTargetPosition)
                && Float.compare(Mth.wrapDegrees(payload.yaw()), djAudienceCameraTargetYaw) == 0
                && Float.compare(Mth.clamp(payload.pitch(), -89.0F, 89.0F), djAudienceCameraTargetPitch) == 0
                && Float.compare(Mth.clamp(payload.fov(), 30.0F, 110.0F), djAudienceCameraTargetFov) == 0);
        if (payload.revision() < djAudienceCameraRevision || exactDuplicate) {
            return;
        }
        boolean cameraChanged = !sameCamera;
        djAudienceCameraPos = payload.cameraPos().immutable();
        djAudienceCameraRevision = payload.revision();
        djAudienceCameraAvailable = payload.available();
        if (!payload.available()) {
            // Remember that this is a cut even if the destination chunk has
            // not arrived yet.  The first usable snapshot must snap to the new
            // camera instead of easing across the whole venue from the old one.
            if (cameraChanged) {
                spectatorCameraViewPositionValid = false;
            }
            if (spectatorCameraEnabled) {
                suspendSpectatorCamera(Minecraft.getInstance());
                showSpectatorCameraMessage(Component.translatable(
                        "message.music_battle.spectator_camera.unavailable"));
            }
            return;
        }

        djAudienceCameraTargetPosition = incomingPosition;
        djAudienceCameraTargetYaw = Mth.wrapDegrees(payload.yaw());
        djAudienceCameraTargetPitch = Mth.clamp(payload.pitch(), -89.0F, 89.0F);
        djAudienceCameraTargetFov = Mth.clamp(payload.fov(), 30.0F, 110.0F);
        // A different camera is an intentional cut made by the DJ. Snap to it
        // immediately; live edits of the same camera are smoothed in tick().
        if (cameraChanged || !spectatorCameraViewPositionValid) {
            spectatorCameraViewPosition = djAudienceCameraTargetPosition;
            spectatorCameraViewPositionValid = true;
            spectatorCameraYaw = djAudienceCameraTargetYaw;
            spectatorCameraPitch = djAudienceCameraTargetPitch;
            spectatorCameraFov = djAudienceCameraTargetFov;
            snapSpectatorCameraInterpolation();
        }
    }

    public static void acceptPublishResult(PublishTrackResultPayload payload) {
        ClientTrackPublisher.acceptResult(payload);
    }

    public static void acceptDeletePublishedTrackResult(DeletePublishedTrackResultPayload payload) {
        ClientTrackPublisher.acceptDeleteResult(payload);
    }

    public static void acceptPublishedTracks(PublishedTracksPayload payload) {
        ClientTrackPublisher.acceptPublishedTracks(payload);
    }

    public static void preloadTrack(TrackPreloadPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        preloadingTrack = true;
        preloadBattleId = payload.battleId();
        preloadTrackId = payload.trackId();
        preloadTrackTitle = payload.title();
        preloadChartFile = payload.chartFile();
        preloadChartHash = payload.chartSha256();
        preloadChartBytes = Math.max(0, payload.chartBytes());
        preloadAudioFile = payload.audioFile();
        preloadAudioHash = payload.audioSha256();
        preloadAudioBytes = Math.max(0, payload.audioBytes());
        preloadTicks = 0;
        preloadReadySent = false;
        preloadedChartNotes = List.of();
        preloadedChartEffects = List.of();
        preloadedChartLoaded = false;
        preloadedDisableStageBars = false;
        preloadedOffsetMs = 0;
        preloadedHideStandardDimming = false;
        pendingSpectatorMusic = null;
        stopSpectatorMusic(minecraft);
        MusicBattleVoteScreen voteScreen = ScreenStack.find(MusicBattleVoteScreen.class);
        if (voteScreen != null && voteScreen.isForBattle(payload.battleId())) {
            voteScreen.closeFromServer();
            minecraft.setScreen(null);
        }
        tickPreloadingTrack();
    }

    /**
     * Another voter's choice and the server's ticks left, or the notice that the vote has closed.
     * A voter's own screen is already gone by then, so a status without an open screen is dropped.
     */
    public static void updateVoteStatus(VoteStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        MusicBattleVoteScreen voteScreen = ScreenStack.find(MusicBattleVoteScreen.class);
        if (voteScreen != null && voteScreen.isForBattle(payload.battleId())) {
            voteScreen.acceptVoteStatus(payload);
        }
    }

    /**
     * Whether a skip of the current rest is on offer and how many have voted. The skip itself arrives as a
     * {@link PauseSkipJumpPayload}; this payload's {@code applied} flag belongs to servers before protocol 68.
     */
    public static void updatePauseSkipStatus(PauseSkipStatusPayload payload) {
        if (battleId == null || !battleId.equals(payload.battleId())) {
            return;
        }
        pauseSkipAvailable = payload.available();
        pauseSkipVotes = payload.votes();
        pauseSkipRequired = Math.max(1, payload.required());
    }

    /**
     * The server has skipped a rest, from one tick straight to another. A player moves the battle clock by exactly
     * the same number of ticks, which keeps whatever lead over the server the start of the battle gave it, and starts
     * the song again at the new tick; the song's own stream lines itself up with the clock however long the seek
     * takes (AlignedStartAudioStream). A spectator, whose clock follows the song it hears, only starts the song again.
     */
    public static void acceptPauseSkipJump(PauseSkipJumpPayload payload) {
        boolean activeBattle = active && battleId != null && battleId.equals(payload.battleId());
        boolean spectatorBattle = !active && spectatorBattleId != null && spectatorBattleId.equals(payload.battleId());
        if (activeBattle || spectatorBattle) {
            cameraSwayShift -= Math.max(0, payload.toTick() - payload.fromTick());
        }
        if (activeBattle) {
            int targetTick = Math.max(0, payload.toTick());
            if (startAtNanos != 0L) {
                // The battle clock is read from the epoch every tick, and once a second the music is seeked back to
                // it, so setting only the counter undid the skip within a second.
                startAtNanos = BattleSkipClock.clientEpochAfterJump(startAtNanos, payload.fromTick(), payload.toTick());
                targetTick = Math.max(0, Mth.floor(activeVisualTick(0.0F)));
            }
            localTicks = targetTick;
            pauseSkipAvailable = false;
            pauseSkipVotes = 0;
            musicStarted = activeAudioFile == null || activeAudioFile.isBlank()
                    || playMusic(Minecraft.getInstance(), activeTrackId, activeAudioFile, localTicks);
        } else if (spectatorBattle) {
            spectatorMusicStartFreezeDisabled = false;
            restartSpectatorMusicAt(Math.max(0, payload.toTick()));
        }
    }

    public static void start(BattleStartPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        clearBattleCameraSync();
        if (spectatorBattleId != null) {
            clearSpectatorBattle();
            pendingSpectatorMusic = null;
            stopSpectatorMusic(minecraft);
        }
        clearSpectatorCameraRequest(minecraft);
        resetAnimationLayers();
        stopMusic(minecraft);
        preloadingTrack = false;
        preloadBattleId = null;
        preloadChartHash = "";
        preloadAudioHash = "";
        active = true;
        battleId = payload.battleId();
        clearBattleAudience();
        surrenderedBattleId = null;
        durationTicks = payload.durationTicks();
        trackBpm = Math.max(20, payload.bpm());
        int startDelayMillis = Math.max(0, payload.startDelayMillis());
        startAtNanos = System.nanoTime() + startDelayMillis * 1_000_000L;
        localTicks = -Math.max(1, startDelayMillis / 50);
        trackTitle = payload.title();
        trackVolume = Mth.clamp(payload.volume(), 0.0F, 2.0F);
        musicPlaybackSpeed = Mth.clamp(payload.speedMultiplier(), 1.0F, 2.0F);
        leftName = payload.leftName();
        rightName = payload.rightName();
        ownOwner = payload.leftSide() ? 0 : 1;
        ownSpeed = payload.ownSpeed();
        opponentSpeed = payload.opponentSpeed();
        ownColor = payload.ownColor();
        opponentColor = payload.opponentColor();
        noteAppearances[ownOwner] = payload.ownAppearance();
        noteAppearances[1 - ownOwner] = payload.opponentAppearance();
        panelStyles[ownOwner][0] = payload.ownPanelOpacity();
        panelStyles[ownOwner][1] = payload.ownLaneOpacity();
        panelStyles[ownOwner][2] = payload.ownPanelWidthScale();
        panelStyles[ownOwner][3] = payload.ownPanelHeightScale();
        panelStyles[ownOwner][4] = payload.ownNoteWidthScale();
        panelStyles[ownOwner][5] = payload.ownNoteHeightScale();
        panelStyles[1 - ownOwner][0] = payload.opponentPanelOpacity();
        panelStyles[1 - ownOwner][1] = payload.opponentLaneOpacity();
        panelStyles[1 - ownOwner][2] = payload.opponentPanelWidthScale();
        panelStyles[1 - ownOwner][3] = payload.opponentPanelHeightScale();
        panelStyles[1 - ownOwner][4] = payload.opponentNoteWidthScale();
        panelStyles[1 - ownOwner][5] = payload.opponentNoteHeightScale();
        leftScore = 0;
        rightScore = 0;
        ownCombo = 0;
        ownMistakes = 0;
        judgementSequence = 0;
        opponentCombo = 0;
        leftEntityId = payload.leftEntityId();
        rightEntityId = payload.rightEntityId();
        activeSolo = payload.rightEntityId() <= 0;
        opponentEntityId = payload.opponentEntityId();
        cameraEntityId = payload.cameraEntityId();
        cameraSwayShift = 0.0F;
        cameraYaw = payload.cameraYaw();
        cameraPitch = payload.cameraPitch();
        cameraFov = payload.cameraFov();
        activeCameraInterpolation.clear();
        // Every battle shot, automatic or a placed camera block, opens near the arena centre already
        // looking the way it will look, so the intro only dollies out. It must not swing in from
        // wherever the participant happened to be looking: for the right-hand fighter that is
        // straight at the left one, which is what made the opening read as off to the left.
        startYaw = cameraYaw;
        startPitch = cameraPitch;
        // A new battle must not blend its first frames from the previous battle's direction.
        aimYawFrom = aimYawTo = cameraYaw;
        aimPitchFrom = aimPitchTo = cameraPitch;
        activeTrackId = payload.trackId();
        activeAudioFile = payload.audioFile();
        activePreviewFile = payload.previewFile();
        if (!activePreviewFile.isBlank() && ClientTrackAssets.cachedAsset(activeTrackId, activePreviewFile).isEmpty()) {
            PacketDistributor.sendToServer(new TrackPreviewRequestPayload(payload.battleId(), List.of(activeTrackId)));
        }
        musicPausedForGame = false;
        musicStarted = false;
        resetMusicClock();
        musicWaitTicks = 0;
        clientErrorReported = false;
        pendingAudioError = null;
        pauseSkipAvailable = false;
        pauseSkipVotes = 0;
        pauseSkipRequired = 1;
        pauseSkipSpaceDown = false;
        laneLabels[ownOwner] = copyKeyLabels(payload.ownKeyLabels());
        laneLabels[1 - ownOwner] = copyKeyLabels(payload.opponentKeyLabels());
        List<BattleNote> battleNotes = payload.notes().isEmpty() ? preloadedChartNotes : payload.notes();
        activeEffects = preloadedChartEffects;
        activeDisableStageBars = preloadedDisableStageBars;
        activeOffsetMs = preloadedOffsetMs;
        activeHideStandardDimming = preloadedHideStandardDimming;
        notes.clear();
        for (int i = 0; i < battleNotes.size(); i++) {
            BattleNote note = battleNotes.get(i);
            notes.add(new ClientNote(i, note.owner(), note.lane(), note.tick(), note.durationTicks()));
        }
        preloadedChartNotes = List.of();
        preloadedChartEffects = List.of();
        preloadedChartLoaded = false;
        preloadedDisableStageBars = false;
        preloadedOffsetMs = 0;
        preloadedHideStandardDimming = false;
        for (int owner = 0; owner < 2; owner++) {
            animationTriggerLane[owner] = -1;
            animationHeldMask[owner] = 0;
            animationInputHeldMask[owner] = 0;
            animationTriggerTick[owner] = -Float.MAX_VALUE;
            animationJudgedLane[owner] = -1;
            animationJudgedTick[owner] = -Float.MAX_VALUE;
            for (int lane = 0; lane < 4; lane++) {
                laneFlashes[owner][lane] = 0;
                hitBursts[owner][lane] = 0;
            }
        }
        for (int lane = 0; lane < previousLaneDown.length; lane++) {
            previousLaneDown[lane] = false;
            laneHitLockoutUntil[lane] = 0;
        }
        // Presses counted before the battle took over the keys are not this battle's.
        BattleLaneLatch.LANES.clear();
        heldActions.clear();
        timedLaneFlashes.clear();

        if (ScreenStack.isOpen(MusicBattleVoteScreen.class)) {
            // The battle is starting: whatever the player opened over the vote goes with it.
            minecraft.setScreen(null);
        }
        ensureCamera(minecraft);
    }

    public static void stop(BattleStopPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean stoppedActive = (active && battleId != null && battleId.equals(payload.battleId()))
                || (surrenderedBattleId != null && surrenderedBattleId.equals(payload.battleId()));
        boolean stoppedSpectator = spectatorBattleId != null && spectatorBattleId.equals(payload.battleId())
                || hasSpectatorAudioFor(payload.battleId());
        boolean stoppedPreload = preloadingTrack && preloadBattleId != null && preloadBattleId.equals(payload.battleId());
        MusicBattleVoteScreen openVote = ScreenStack.find(MusicBattleVoteScreen.class);
        boolean stoppedVote = openVote != null && openVote.isForBattle(payload.battleId());
        if (!stoppedActive && !stoppedSpectator && !stoppedPreload && !stoppedVote) {
            return;
        }
        if (stoppedPreload) {
            preloadingTrack = false;
            preloadBattleId = null;
            preloadTrackId = "";
            preloadChartFile = "";
            preloadChartHash = "";
            preloadAudioFile = "";
            preloadAudioHash = "";
            preloadedChartNotes = List.of();
            preloadedChartEffects = List.of();
            preloadedChartLoaded = false;
            preloadedDisableStageBars = false;
            preloadedOffsetMs = 0;
            preloadedHideStandardDimming = false;
        }
        if (stoppedActive) {
            clearBattleCameraSync();
            active = false;
            activeSolo = false;
            // The epoch belongs to one battle; the next one brings its own.
            startAtNanos = 0L;
            battleId = null;
            activeTrackId = "";
            activeAudioFile = "";
            activePreviewFile = "";
            surrenderedBattleId = null;
            musicPausedForGame = false;
            musicStarted = false;
            resetMusicClock();
            musicWaitTicks = 0;
            clientErrorReported = false;
            pendingAudioError = null;
            pauseSkipAvailable = false;
            pauseSkipVotes = 0;
            pauseSkipSpaceDown = false;
            notes.clear();
            heldActions.clear();
            timedLaneFlashes.clear();
            activeEffects = List.of();
            activeDisableStageBars = false;
            activeOffsetMs = 0;
            activeHideStandardDimming = false;
            stopMusic(minecraft);
        }
        if (stoppedSpectator) {
            clearSpectatorBattle();
            stopSpectatorMusic(minecraft);
            pendingSpectatorMusic = null;
        }
        if (stoppedActive || stoppedSpectator) {
            clearBattleAudience();
        }
        resetAnimationLayers();
        if (minecraft.player != null) {
            MusicBattleVoteScreen voteScreen = ScreenStack.find(MusicBattleVoteScreen.class);
            if (voteScreen != null && voteScreen.isForBattle(payload.battleId())) {
                voteScreen.closeFromServer();
                minecraft.setScreen(null);
            }
            if (stoppedActive) {
                minecraft.setCameraEntity(minecraft.player);
            }
            minecraft.player.displayClientMessage(payload.message(), false);
        }
    }

    /**
     * A participant's action from the server. A mob's comes a few ticks before the tick it belongs to (BattleManager's
     * AI_LEAD_TICKS) and waits here until the battle clock reaches it, then shows in the first frame at or after that
     * tick (showDueActions): its hit lands on its note, wherever the server's ticks fell and however long any pause
     * was. An action that arrives on or after its tick - a player's, or a mob's held up on the way - shows at once.
     */
    public static void acceptAction(BattleActionPayload payload) {
        // Numbered as it comes, whether it is shown now or waits for its tick: see remoteStateArrivals.
        long arrival = ++remoteStateArrivals;
        if (active && battleId != null && battleId.equals(payload.battleId())) {
            float clock = activeVisualTick(0.0F);
            // Whatever was already due goes first, so that one side's actions keep their order.
            showDueActions(heldActions, false, clock);
            if (waitsForItsTick(heldActions, payload, clock)) {
                heldActions.add(new HeldAction(payload, arrival));
            } else {
                // Judged on the battle clock, the one the server's ticks follow, as a held action is when it is shown,
                // not on the last client tick: a hold's completion that came after the hold's end but before the next
                // client tick left the hold started until that tick.
                applyActiveAction(payload, Float.NaN, Mth.floor(clock), arrival);
            }
        }
        if (spectatorBattleId != null && spectatorBattleId.equals(payload.battleId())) {
            float clock = spectatorVisualTick(0.0F);
            showDueActions(spectatorHeldActions, true, clock);
            if (waitsForItsTick(spectatorHeldActions, payload, clock)) {
                spectatorHeldActions.add(new HeldAction(payload, arrival));
            } else {
                applySpectatorAction(payload, Float.NaN, Mth.floor(clock), arrival);
            }
        }
    }

    /** An action waiting for its tick, with the number it took as it came (remoteStateArrivals). */
    private record HeldAction(BattleActionPayload payload, long arrival) {
    }

    /**
     * Whether an action has to wait: it belongs to a tick the clock has not reached, or an earlier action of the same
     * side is still waiting and it has to come after that one.
     */
    private static boolean waitsForItsTick(List<HeldAction> held, BattleActionPayload payload, float clock) {
        if (payload.showAtTick() > clock) {
            return true;
        }
        for (HeldAction waiting : held) {
            if (waiting.payload().owner() == payload.owner()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows every waiting action whose tick the clock has reached, in the order they came, as if each had arrived
     * then: the hit and its flash at the frame that reaches the tick, the punch stamped with the tick itself, so the
     * body moves in phase with the note even though the frame came a little after it. Run at the start of every
     * frame (beginRenderFrame) and every client tick.
     */
    private static void showDueActions(List<HeldAction> held, boolean spectator, float clock) {
        while (!held.isEmpty() && held.get(0).payload().showAtTick() <= clock) {
            HeldAction waiting = held.remove(0);
            BattleActionPayload payload = waiting.payload();
            float at = payload.showAtTick();
            // The tick the frame is in, not the last client tick's: the note is judged on the clock it is shown at.
            int currentTick = Mth.floor(clock);
            if (spectator) {
                applySpectatorAction(payload, at, currentTick, waiting.arrival());
            } else {
                applyActiveAction(payload, at, currentTick, waiting.arrival());
            }
        }
    }

    /**
     * An action in the active battle, shown now. {@code at} is the battle tick it belongs to when it waited for it, and
     * NaN when it is shown as it came; {@code currentTick} is the tick the note is judged against, and {@code arrival}
     * the number the action took as it came (remoteStateArrivals).
     */
    private static void applyActiveAction(BattleActionPayload payload, float at, int currentTick, long arrival) {
        if (!active || battleId == null || !battleId.equals(payload.battleId())) {
            return;
        }
        int owner = Mth.clamp(payload.owner(), 0, 1);
        int lane = Mth.clamp(payload.lane(), 0, 3);
        if (owner != ownOwner) {
            boolean visualApplied = payload.noteIndex() < 0;
            ClientNote animationNote = noteByIndex(notes, payload.noteIndex());
            if (breaksWaitingHold(animationNote, owner, lane, payload.hit(), payload.complete())) {
                showWaitingHit(notes, animationNote, false, currentTick, laneFlashes, hitBursts);
            }
            boolean flashAction = shouldFlashAction(notes, payload.noteIndex(), payload.hit(), payload.complete());
            boolean holdBroken = breaksStartedHold(notes, payload.noteIndex(), payload.hit(), payload.complete());
            if (payload.noteIndex() >= 0) {
                visualApplied = applyNoteAction(notes, owner, lane, payload.noteIndex(), payload.noteTick(), currentTick, payload.hit(), payload.complete());
            }
            if (visualApplied) {
                if (animationNote != null && animationNote.durationTicks > 0) {
                    setPlayerAnimationHeld(false, owner, lane, holdsLane(notes, owner, lane));
                }
                showRemoteState(false, owner, payload.leftScore(), payload.rightScore(), payload.combo(), arrival);
                if (flashAction) {
                    // Timed from its tick, or from now when it comes as it is: see showLaneFlash.
                    showLaneFlash(false, owner, lane, payload.hit() ? 8 : 4, payload.hit() && payload.noteIndex() >= 0,
                            Float.isFinite(at) ? at : activeVisualTick(0.0F));
                    triggerJudgementAnimation(false, owner, lane, at);
                } else if (holdBroken) {
                    // Timed from its tick, or from now when it comes as it is: see showLaneFlash.
                    showLaneFlash(false, owner, lane, 4, false, Float.isFinite(at) ? at : activeVisualTick(0.0F));
                }
            } else {
                queuePendingRemoteState(notes, payload.noteIndex(), payload.leftScore(), payload.rightScore(),
                        payload.combo(), arrival);
            }
            return;
        }
        // Our own side. The score here is predicted locally and the server normally only echoes it back, so
        // most of these are ignored on purpose. The exception is a note the server resolved that we never
        // did - it ran out of patience waiting for our judgement - and that used to be dropped silently:
        // the server and the opponent saw a miss while our own HUD kept a full combo, and the two scores
        // never met again. Now the correction lands where it belongs, and it looks like the miss it is.
        //
        // A note we had already resolved ourselves is excluded, because then we have counted the mistake
        // once already and the server is only telling us about the same one.
        ClientNote correctedNote = noteByIndex(notes, payload.noteIndex());
        boolean countsAsOwnMistake = !payload.hit() && (correctedNote == null || !correctedNote.resolved);
        if (payload.noteIndex() >= 0
                && applyNoteAction(notes, owner, lane, payload.noteIndex(), payload.noteTick(), currentTick,
                        payload.hit(), payload.complete())) {
            applyOwnCorrection(owner, payload.score(), payload.combo(), countsAsOwnMistake);
            if (shouldFlashAction(notes, payload.noteIndex(), payload.hit(), payload.complete())) {
                showLaneFlash(false, owner, lane, payload.hit() ? 8 : 4, false, Float.NaN);
            }
        }
    }

    /** The same for a battle this client watches; see {@link #applyActiveAction}. */
    private static void applySpectatorAction(BattleActionPayload payload, float at, int currentTick, long arrival) {
        if (spectatorBattleId == null || !spectatorBattleId.equals(payload.battleId())) {
            return;
        }
        int owner = Mth.clamp(payload.owner(), 0, 1);
        int lane = Mth.clamp(payload.lane(), 0, 3);
        boolean visualApplied = payload.noteIndex() < 0;
        ClientNote animationNote = noteByIndex(spectatorNotes, payload.noteIndex());
        if (breaksWaitingHold(animationNote, owner, lane, payload.hit(), payload.complete())) {
            showWaitingHit(spectatorNotes, animationNote, true, currentTick, spectatorLaneFlashes, spectatorHitBursts);
        }
        boolean flashAction = shouldFlashAction(spectatorNotes, payload.noteIndex(), payload.hit(), payload.complete());
        boolean holdBroken = breaksStartedHold(spectatorNotes, payload.noteIndex(), payload.hit(), payload.complete());
        if (payload.noteIndex() >= 0) {
            visualApplied = applyNoteAction(spectatorNotes, owner, lane, payload.noteIndex(), payload.noteTick(), currentTick, payload.hit(), payload.complete());
        }
        if (visualApplied) {
            if (animationNote != null && animationNote.durationTicks > 0) {
                setPlayerAnimationHeld(true, owner, lane, holdsLane(spectatorNotes, owner, lane));
            }
            showRemoteState(true, owner, payload.leftScore(), payload.rightScore(), payload.combo(), arrival);
            if (flashAction) {
                showLaneFlash(true, owner, lane, payload.hit() ? 8 : 4, payload.hit() && payload.noteIndex() >= 0,
                        Float.isFinite(at) ? at : spectatorVisualTick(0.0F));
                triggerJudgementAnimation(true, owner, lane, at);
            } else if (holdBroken) {
                showLaneFlash(true, owner, lane, 4, false, Float.isFinite(at) ? at : spectatorVisualTick(0.0F));
            }
        } else {
            queuePendingRemoteState(spectatorNotes, payload.noteIndex(), payload.leftScore(), payload.rightScore(),
                    payload.combo(), arrival);
        }
    }

    /**
     * Flashes a lane, with the hit burst when {@code burst}. The other side's flashes all count down on the battle clock
     * (TimedLaneFlashes): from the tick of an action held for it, or from the moment one shown as it came was shown. A
     * NaN {@code at} - only one's own side now, whose correction from the server is shown here - counts down on client
     * ticks, as every flash did, and any timed flash on the lane stops: one's own keys set those lanes directly.
     *
     * <p>Why the battle clock for everything of the other side: a client tick counts its untimed flashes down right
     * after it has shown its due actions, and a frame handles its packets before its client ticks, so a flash shown
     * either way lost its brightest step before it was ever drawn, while one's own, set by a key after that count, keeps
     * it. Measured on the real controller in Minecraft's frame order, a remote player's bad press was first drawn at 3
     * instead of 4 in 66% of presses at 30 fps, 33% at 60 and 8% at 240, and lit for 156-173 ms on average against
     * one's own 200; a remote hit lost its first step of flash and burst in the same share.
     *
     * <p>A flash without a burst (a broken hold, a bad press) leaves the lane's burst going on: the timeline counts its
     * burst from the same start as its flash, so the start moves back by the part of the burst the lane has shown by
     * that tick, and the flash steps down from its own tick.
     */
    private static void showLaneFlash(boolean spectator, int owner, int lane, int flash, boolean burst, float at) {
        int[][] flashes = spectator ? spectatorLaneFlashes : laneFlashes;
        int[][] bursts = spectator ? spectatorHitBursts : hitBursts;
        TimedLaneFlashes timed = spectator ? spectatorTimedLaneFlashes : timedLaneFlashes;
        if (burst && Float.isFinite(at)) {
            timed.start(owner, lane, flash, at);
            flashes[owner][lane] = flash;
            bursts[owner][lane] = TimedLaneFlashes.BURST_TICKS;
            return;
        }
        if (Float.isFinite(at)) {
            // A timed lane's burst was last counted by the frame or the client tick before this tick. Read as it was,
            // the burst of a head hit three ticks before showed one step back, and for a tick longer than one's own,
            // so the timelines are brought to this tick first; every frame counts them again at its own clock before
            // it draws.
            timed.apply(at, flashes, bursts);
            int burstShown = TimedLaneFlashes.BURST_TICKS - Mth.clamp(bursts[owner][lane], 0, TimedLaneFlashes.BURST_TICKS);
            timed.start(owner, lane, flash + burstShown, at - burstShown);
            flashes[owner][lane] = flash;
            return;
        }
        timed.stop(owner, lane);
        flashes[owner][lane] = flash;
        if (burst) {
            bursts[owner][lane] = 8;
        }
    }

    public static void acceptLaneAnimation(BattleLaneAnimationPayload payload) {
        int owner = Mth.clamp(payload.owner(), 0, 1);
        int lane = Mth.clamp(payload.lane(), 0, 3);
        if (active && battleId != null && battleId.equals(payload.battleId()) && owner != ownOwner) {
            setPlayerAnimationInputHeld(false, owner, lane, payload.down());
            if (payload.down()) triggerPlayerAnimation(false, owner, lane);
        }
        if (spectatorBattleId != null && spectatorBattleId.equals(payload.battleId())) {
            setPlayerAnimationInputHeld(true, owner, lane, payload.down());
            if (payload.down()) triggerPlayerAnimation(true, owner, lane);
        }
    }

    public static void acceptTrackAssetChunk(String trackId, String assetKind, String fileName, int chunkIndex, int totalChunks, int totalBytes, String sha256, byte[] data) {
        ClientTrackAssets.acceptChunk(trackId, assetKind, fileName, chunkIndex, totalChunks, totalBytes, sha256, data);
    }

    public static void onTrackAssetReady(String trackId, String assetKind, String fileName, String sha256) {
        SpectatorMusicPayload djPayload = lastSpectatorMusicPayload;
        if (djPayload != null && "audio".equals(assetKind)
                && djPayload.trackId().equals(trackId)
                && djPayload.audioFile().equals(fileName)
                && djPayload.audioSha256().equalsIgnoreCase(sha256)) {
            acknowledgeDjAssetIfReady(djPayload);
        }
        if (!preloadingTrack || preloadReadySent || preloadBattleId == null) {
            return;
        }
        if (!preloadTrackId.equals(trackId)
                || !"audio".equals(assetKind) && !"chart".equals(assetKind)
                || "audio".equals(assetKind) && !preloadAudioFile.equals(fileName)
                || "chart".equals(assetKind) && !preloadChartFile.equals(fileName)) {
            return;
        }
        tickPreloadingTrack();
    }

    public static void playSpectatorMusic(SpectatorMusicPayload payload) {
        if (active) {
            return;
        }
        // Do not let a delayed ambient-DJ packet replace the audio of a real
        // spectator battle. DJ sends its marked FX session first, so a valid DJ
        // music packet either matches that session or arrives while no session exists.
        if (spectatorBattleId != null && !spectatorBattleId.equals(payload.battleId())) {
            // Track/speed/seek changes rotate the DJ playback id. A late music packet from the
            // previous id must not replace the payload used by stream recovery.
            return;
        }
        SpectatorMusicPayload previousMusicPayload = lastSpectatorMusicPayload;
        if (previousMusicPayload == null
                || !payload.battleId().equals(previousMusicPayload.battleId())
                || !payload.trackId().equals(previousMusicPayload.trackId())
                || !payload.audioFile().equals(previousMusicPayload.audioFile())
                || !payload.audioSha256().equalsIgnoreCase(previousMusicPayload.audioSha256())) {
            spectatorMusicStartFreezeDisabled = false;
        }
        lastSpectatorMusicPayload = payload;
        acknowledgeDjAssetIfReady(payload);
        // A spectator can be announced more than once while standing near the
        // arena (for example after a tracking/range refresh). Never restart an
        // already playing copy of the same track: doing so also rewinds the
        // visual clock used by entity animations for one render frame.
        boolean samePlayingTrack = spectatorMusic != null
                && previousMusicPayload != null
                && payload.battleId().equals(previousMusicPayload.battleId())
                && payload.trackId().equals(spectatorMusicTrackId)
                && payload.audioFile().equals(spectatorMusicAudioFile)
                && payload.audioSha256().equalsIgnoreCase(spectatorMusicAudioHash);
        boolean samePendingTrack = pendingSpectatorMusic != null
                && payload.battleId().equals(pendingSpectatorMusic.payload.battleId())
                && payload.trackId().equals(pendingSpectatorMusic.payload.trackId())
                && payload.audioFile().equals(pendingSpectatorMusic.payload.audioFile())
                && payload.audioSha256().equalsIgnoreCase(pendingSpectatorMusic.payload.audioSha256());
        if (samePlayingTrack || samePendingTrack) {
            spectatorSoundX = payload.x();
            spectatorSoundY = payload.y();
            spectatorSoundZ = payload.z();
            spectatorSoundRadius = Math.max(1.0F, payload.radius());
            spectatorTrackVolume = payload.volume();
            spectatorLowGainDb = payload.lowGainDb();
            spectatorMidGainDb = payload.midGainDb();
            spectatorHighGainDb = payload.highGainDb();
            spectatorPlaybackSpeed = Mth.clamp(payload.speedMultiplier(), 1.0F, 2.0F);
            if (spectatorMusic instanceof SpectatorOggSoundInstance spectatorSound) {
                spectatorSound.setSource(payload.x(), payload.y(), payload.z(), payload.radius(),
                        Mth.clamp(payload.volume() * ClientBattleSettings.musicVolume(), 0.0F, 4.0F),
                        payload.lowGainDb(), payload.midGainDb(), payload.highGainDb(),
                        payload.dspEnabled(),
                        payload.reverbEnabled(), payload.reverbWet(), payload.reverbRoom(), payload.reverbDamping(),
                        payload.echoEnabled(), payload.echoWet(), payload.echoTimeMs(), payload.echoFeedback(),
                        payload.lowPassEnabled(), payload.lowPassCutoffHz());
            }
            if (samePendingTrack && pendingSpectatorMusic != null) {
                pendingSpectatorMusic.payload = payload;
            }
            return;
        }
        spectatorSoundX = payload.x();
        spectatorSoundY = payload.y();
        spectatorSoundZ = payload.z();
        spectatorSoundRadius = Math.max(1.0F, payload.radius());
        spectatorTrackVolume = payload.volume();
        spectatorLowGainDb = payload.lowGainDb();
        spectatorMidGainDb = payload.midGainDb();
        spectatorHighGainDb = payload.highGainDb();
        spectatorPlaybackSpeed = Mth.clamp(payload.speedMultiplier(), 1.0F, 2.0F);
        pendingSpectatorMusic = new PendingSpectatorMusic(payload, SPECTATOR_DOWNLOAD_TIMEOUT_TICKS, Math.max(0, payload.delayTicks()));
        tryPlayPendingSpectatorMusic();
    }

    private static void acknowledgeDjAssetIfReady(SpectatorMusicPayload payload) {
        if (!standaloneDjPlayback || spectatorBattleId == null
                || !spectatorBattleId.equals(payload.battleId())
                || ClientTrackAssets.cachedAsset(
                        payload.trackId(), payload.audioFile(), payload.audioSha256()).isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new DjAssetReadyPayload(
                payload.battleId(), payload.trackId(), payload.audioFile(), payload.audioSha256()));
    }

    public static void acceptBattleAppearance(BattleAppearancePayload payload) {
        int owner = payload.owner();
        if (active && payload.battleId().equals(battleId)) {
            noteAppearances[owner] = payload.appearance();
            if (owner == ownOwner) ownSpeed = payload.speed();
            else opponentSpeed = payload.speed();
        } else if (!active && payload.battleId().equals(spectatorBattleId)) {
            spectatorNoteAppearances[owner] = payload.appearance();
            if (owner == 0) spectatorLeftSpeed = payload.speed();
            else spectatorRightSpeed = payload.speed();
        }
    }

    public static void startSpectatorBattle(SpectatorBattlePayload payload) {
        boolean standalonePayload = payload.cameraEntityId() < 0;
        if (standalonePayload && (active || spectatorBattleId != null && !standaloneDjPlayback)) {
            return;
        }
        if (active && battleId != null && battleId.equals(payload.battleId())) {
            return;
        }
        if (!active && spectatorBattleId != null && spectatorBattleId.equals(payload.battleId())) {
            // This is a refresh of the battle the client is already watching.
            // Resetting here restarted idle/action layers and made every model
            // visibly snap even when no note was being played.
            spectatorDurationTicks = payload.durationTicks();
            spectatorNoteAppearances[0] = payload.leftAppearance();
            spectatorNoteAppearances[1] = payload.rightAppearance();
            spectatorLeftSpeed = NoteSpeed.clamp(payload.leftSpeed());
            spectatorRightSpeed = NoteSpeed.clamp(payload.rightSpeed());
            spectatorTrackBpm = Math.max(20, payload.bpm());
            spectatorBeatOffsetMs = payload.beatOffsetMs();
            spectatorPlaybackSpeed = Mth.clamp(payload.speedMultiplier(), 1.0F, 2.0F);
            spectatorLeftEntityId = payload.leftEntityId();
            spectatorRightEntityId = payload.rightEntityId();
            if (!standalonePayload) {
                spectatorCameraEntityId = payload.cameraEntityId();
                spectatorCameraYaw = payload.cameraYaw();
                spectatorCameraPitch = payload.cameraPitch();
                spectatorCameraFov = payload.cameraFov();
            }
            spectatorLeftScore = payload.leftScore();
            spectatorRightScore = payload.rightScore();
            spectatorCombo[0] = payload.leftCombo();
            spectatorCombo[1] = payload.rightCombo();
            // A mob's side comes as of the battle's tick, with its actions from the ticks it played ahead. This panel
            // holds those already, from before the refresh, so the numbers of the newest of them are on show now.
            for (BattleActionPayload lead : payload.leadActions()) {
                int side = Mth.clamp(lead.owner(), 0, 1);
                if (side == 0) {
                    spectatorLeftScore = lead.score();
                } else {
                    spectatorRightScore = lead.score();
                }
                spectatorCombo[side] = lead.combo();
            }
            // These are the server's numbers as it sent this refresh, after every action that came before it, so both
            // sides count them as the newest on show (showRemoteState). Numbers still waiting on a note - a hit that
            // came before its note's tick - are older: shown at that tick, they took a side's score and combo back to
            // before actions the refresh already counts, which this client may never have got. So are those of the
            // actions held for their tick, which took their numbers as they came (HeldAction).
            long refreshed = ++remoteStateArrivals;
            spectatorRemoteStateShown[0] = refreshed;
            spectatorRemoteStateShown[1] = refreshed;
            spectatorAnimationInputHeldMask[0] = payload.leftHeldMask();
            spectatorAnimationInputHeldMask[1] = payload.rightHeldMask();
            // DJ panels can replace their visual stack live. A same-session refresh must update
            // only the FX list; resetting the whole spectator state would snap entity animations.
            spectatorEffects = payload.effects();
            standaloneDjPlayback = standalonePayload;
            if (standalonePayload && lastSpectatorMusicPayload != null) {
                acknowledgeDjAssetIfReady(lastSpectatorMusicPayload);
            }
            return;
        }
        boolean continuingDjAudience = standalonePayload && standaloneDjPlayback && djReleaseEndTick > 0;
        if (spectatorBattleId != null && !spectatorBattleId.equals(payload.battleId())) {
            if (djReleaseEndTick > 0) djTransitions.capture(spectatorEffects, spectatorVisualTick(0),
                    spectatorTrackBpm, spectatorPlaybackSpeed, spectatorBeatOrigin(), System.nanoTime());
            clearSpectatorCameraRequest(Minecraft.getInstance());
            pendingSpectatorMusic = null;
            stopSpectatorMusic(Minecraft.getInstance());
        }
        if (preloadingTrack && payload.battleId().equals(preloadBattleId)) {
            clearPreloadState();
        }
        resetAnimationLayers();
        Minecraft minecraft = Minecraft.getInstance();
        MusicBattleVoteScreen voteScreen = ScreenStack.find(MusicBattleVoteScreen.class);
        if (voteScreen != null && voteScreen.isForBattle(payload.battleId())) {
            voteScreen.closeFromServer();
            minecraft.setScreen(null);
        }
        clearBattleCameraSync();
        clearDjAudienceCamera();
        spectatorBattleId = payload.battleId();
        cameraSwayShift = 0.0F;
        standaloneDjPlayback = standalonePayload;
        djReleaseEndTick = 0;
        if (standalonePayload && lastSpectatorMusicPayload != null) {
            acknowledgeDjAssetIfReady(lastSpectatorMusicPayload);
        }
        clearBattleAudience();
        spectatorDurationTicks = payload.durationTicks();
        spectatorLocalTicks = payload.startTick();
        spectatorMusicClockStartTick = Math.max(0, payload.startTick());
        spectatorTrackBpm = Math.max(20, payload.bpm());
        spectatorBeatOffsetMs = payload.beatOffsetMs();
        spectatorPlaybackSpeed = Mth.clamp(payload.speedMultiplier(), 1.0F, 2.0F);
        spectatorLeftEntityId = payload.leftEntityId();
        spectatorRightEntityId = payload.rightEntityId();
        spectatorCameraEntityId = payload.cameraEntityId();
        spectatorCameraYaw = payload.cameraYaw();
        spectatorCameraPitch = payload.cameraPitch();
        spectatorCameraFov = payload.cameraFov();
        spectatorCameraEnabled = false;
        spectatorCameraRequested = false;
        spectatorCameraViewPosition = Vec3.ZERO;
        spectatorCameraViewPositionValid = false;
        if (!continuingDjAudience) {
            spectatorFxVisibility = 0.0F;
            spectatorFxVisibilityPrevious = 0.0F;
        }
        spectatorLeftColor = payload.leftColor();
        spectatorNoteAppearances[0] = payload.leftAppearance();
        spectatorNoteAppearances[1] = payload.rightAppearance();
        spectatorRightColor = payload.rightColor();
        spectatorPanelStyles[0][0] = payload.leftPanelOpacity();
        spectatorPanelStyles[0][1] = payload.leftLaneOpacity();
        spectatorPanelStyles[0][2] = payload.leftPanelWidthScale();
        spectatorPanelStyles[0][3] = payload.leftPanelHeightScale();
        spectatorPanelStyles[0][4] = payload.leftNoteWidthScale();
        spectatorPanelStyles[0][5] = payload.leftNoteHeightScale();
        spectatorPanelStyles[1][0] = payload.rightPanelOpacity();
        spectatorPanelStyles[1][1] = payload.rightLaneOpacity();
        spectatorPanelStyles[1][2] = payload.rightPanelWidthScale();
        spectatorPanelStyles[1][3] = payload.rightPanelHeightScale();
        spectatorPanelStyles[1][4] = payload.rightNoteWidthScale();
        spectatorPanelStyles[1][5] = payload.rightNoteHeightScale();
        spectatorLeftSpeed = payload.leftSpeed();
        spectatorRightSpeed = payload.rightSpeed();
        spectatorEffects = payload.effects();
        for (int owner = 0; owner < 2; owner++) {
            spectatorAnimationTriggerLane[owner] = -1;
            spectatorAnimationTriggerTick[owner] = -Float.MAX_VALUE;
            spectatorAnimationJudgedLane[owner] = -1;
            spectatorAnimationJudgedTick[owner] = -Float.MAX_VALUE;
        }
        takeSpectatorPanel(payload);
    }

    /**
     * The watched battle's panel as the server sends it to a new spectator: the numbers, the notes and what each of them
     * is on the battle's tick {@code startTick}, and a mob's actions from the ticks it has played ahead, held for their
     * tick (BattleManager.Battle#spectatorJoin).
     *
     * <p>A note that ended more than the hit window before is over. One the server names as settled is over too, or, if
     * its tick is still to come - a player's hit taken early - waits for it as such a hit does when it comes as an action
     * (applyNoteAction). A hold the server names as held is held, its pose with it, or, still to come, waits as a hold
     * taken early. Before, the panel only had the held keys to go on, which a mob's side sends three ticks ahead and
     * never lets go of, and every note settled in the last hit window, and every one the mob had played ahead, fell past
     * its button unsettled for the rest of the battle.
     */
    private static void takeSpectatorPanel(SpectatorBattlePayload payload) {
        spectatorLeftScore = payload.leftScore();
        spectatorRightScore = payload.rightScore();
        spectatorCombo[0] = payload.leftCombo();
        spectatorCombo[1] = payload.rightCombo();
        spectatorNotes.clear();
        spectatorHeldActions.clear();
        spectatorTimedLaneFlashes.clear();
        Set<Integer> settled = Set.copyOf(payload.settledNotes());
        Set<Integer> held = Set.copyOf(payload.startedNotes());
        for (int i = 0; i < payload.notes().size(); i++) {
            BattleNote note = payload.notes().get(i);
            ClientNote clientNote = new ClientNote(i, note.owner(), note.lane(), note.tick(), note.durationTicks());
            int noteEnd = note.tick() + Math.max(0, note.durationTicks());
            boolean toCome = note.tick() > payload.startTick() + 1;
            if (settled.contains(i)) {
                if (toCome) {
                    clientNote.pendingHit = true;
                    clientNote.pendingComplete = true;
                } else {
                    clientNote.resolved = true;
                }
            } else if (payload.startTick() > noteEnd + HIT_WINDOW_TICKS) {
                clientNote.resolved = true;
            } else if (held.contains(i) && note.durationTicks() > 0) {
                if (toCome) {
                    clientNote.pendingHit = true;
                } else {
                    clientNote.started = true;
                }
            }
            spectatorNotes.add(clientNote);
        }
        for (int owner = 0; owner < 2; owner++) {
            spectatorAnimationInputHeldMask[owner] = owner == 0 ? payload.leftHeldMask() : payload.rightHeldMask();
            int holding = 0;
            for (int lane = 0; lane < 4; lane++) {
                spectatorLaneFlashes[owner][lane] = 0;
                spectatorHitBursts[owner][lane] = 0;
                if (holdsLane(spectatorNotes, owner, lane)) {
                    holding |= 1 << lane;
                }
            }
            spectatorAnimationHeldMask[owner] = holding;
        }
        for (BattleActionPayload lead : payload.leadActions()) {
            if (payload.battleId().equals(lead.battleId())) {
                spectatorHeldActions.add(new HeldAction(lead, ++remoteStateArrivals));
            }
        }
    }

    public static void stopSpectating(SpectatorStopPayload payload) {
        if (payload.releaseEffects() && standaloneDjPlayback && payload.battleId().equals(spectatorBattleId)) {
            float releaseTick = Math.max(spectatorDurationTicks, spectatorVisualTick(0));
            spectatorEffects = ru.gora.musicbattle.data.DjFxRelease.at(spectatorEffects, releaseTick, spectatorPlaybackSpeed);
            djReleaseEndTick = ru.gora.musicbattle.data.DjFxRelease.endTick(spectatorEffects);
            pendingSpectatorMusic = null;
            lastSpectatorMusicPayload = null;
            stopSpectatorMusic(Minecraft.getInstance());
            spectatorLocalTicks = (int)releaseTick;
            if (djReleaseEndTick > releaseTick) return;
        }
        boolean stoppedPreload = preloadingTrack && payload.battleId().equals(preloadBattleId);
        boolean stoppedSpectator = payload.battleId().equals(spectatorBattleId) || hasSpectatorAudioFor(payload.battleId());
        if (!stoppedPreload && !stoppedSpectator) {
            return;
        }
        if (stoppedPreload) {
            clearPreloadState();
        }
        if (stoppedSpectator) {
            clearSpectatorBattle();
            pendingSpectatorMusic = null;
            stopSpectatorMusic(Minecraft.getInstance());
        }
        clearBattleAudience();
        resetAnimationLayers();
    }

    private static void clearPreloadState() {
        preloadingTrack = false;
        preloadBattleId = null;
        preloadTrackId = "";
        preloadTrackTitle = "";
        preloadChartFile = "";
        preloadChartHash = "";
        preloadChartBytes = 0;
        preloadAudioFile = "";
        preloadAudioHash = "";
        preloadAudioBytes = 0;
        preloadTicks = 0;
        preloadReadySent = false;
        preloadedChartNotes = List.of();
        preloadedChartEffects = List.of();
        preloadedChartLoaded = false;
        preloadedDisableStageBars = false;
        preloadedOffsetMs = 0;
        preloadedHideStandardDimming = false;
    }

    private static boolean hasSpectatorAudioFor(UUID expectedBattleId) {
        return expectedBattleId != null && (lastSpectatorMusicPayload != null
                && expectedBattleId.equals(lastSpectatorMusicPayload.battleId())
                || pendingSpectatorMusic != null
                && expectedBattleId.equals(pendingSpectatorMusic.payload.battleId()));
    }

    /**
     * The game has just paused or resumed (NeoForge's ClientPauseChangeEvent.Post, at the end of the frame the pause
     * menu opened or closed in). The battle clock is held and released here, at the same frame as the music, rather
     * than at the next client tick.
     *
     * <p>Minecraft pauses every sound the moment Esc opens the menu and resumes them the moment the menu closes, but the
     * client tick that used to hold the battle clock - and the integrated server's clock with it, BattlePauseClock -
     * runs up to a tick after either. The clock therefore ran on silently for part of a tick before the pause, and the
     * song played for part of a tick before the clock went on: every visit to the menu moved the song against the
     * chart and the bot by up to a tick either way, and the drift check never saw it, because it reckons the song's
     * place from these same stamps. tools/BattleSkipSyncChecks.java replays both orders frame by frame.
     *
     * <p>Registered in ClientGameEvents. The client tick still holds and releases on its own, as a fallback for a pause
     * this event does not report, and whichever comes first does the work once.
     *
     * <p>Even the end of the frame is late: Minecraft pauses the sounds while it handles the key or the click, and the
     * frame then sleeps for the frame-rate limit before the pause flag changes, so each visit still moved the song
     * against the chart by the difference of two such sleeps, a few milliseconds, and the visits added up. The pause
     * menu's own setup therefore holds the clock already (onScreenShown), and a release counts from the instant the
     * menu closed and the sounds came back (onScreenClosing, gameResumeInstant).
     */
    public static void onGamePauseChanged(boolean paused) {
        long now = System.nanoTime();
        long at = paused ? now : gameResumeInstant(now);
        BattlePauseClock.GAME.observe(paused, at);
        Minecraft minecraft = Minecraft.getInstance();
        if (active) {
            if (paused) {
                holdBattleForGamePause(minecraft, at);
            } else {
                releaseBattleFromGamePause(minecraft, at);
            }
        } else if (spectatorBattleId != null) {
            if (paused) {
                holdSpectatorForGamePause(minecraft, at);
            } else {
                releaseSpectatorFromGamePause(minecraft, at);
            }
        }
    }

    /**
     * A screen has just been set up (ScreenEvent.Init.Post, run last). When it is the pause menu of a single-player
     * world that is not open to LAN - the same test Minecraft makes for the pause flag at the end of the frame -
     * Minecraft#pauseGame pauses every sound the moment this returns, so the battle clock and the integrated server's
     * stop here, with the song, instead of a frame-rate sleep later at the flag. The flag's own event then finds them
     * held already. A pause that does not follow after all is let go by the next client tick, song and clock together.
     */
    public static void onScreenShown(Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || screen == null || minecraft.screen != screen || minecraft.isPaused()
                || !screen.isPauseScreen() || !minecraft.hasSingleplayerServer()
                || minecraft.getSingleplayerServer().isPublished()) {
            return;
        }
        onGamePauseChanged(true);
    }

    /** ScreenEvent.Opening, run last and only if nothing cancelled it: the screen about to replace the current one. */
    public static void onScreenOpening(Screen next) {
        screenComingNext = next;
    }

    /**
     * ScreenEvent.Closing, run last. Minecraft#setScreen posts Opening for the next screen before it closes the old
     * one, and nothing when there is no next screen, so a screen that closes with no Opening before it leaves the game
     * bare - and when the game is paused, Minecraft resumes every sound the moment this returns. That instant is kept
     * for the battle clock's release at the end of the frame (gameResumeInstant).
     */
    public static void onScreenClosing(Screen closing) {
        Screen next = screenComingNext;
        screenComingNext = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (next == null && minecraft != null && minecraft.isPaused()) {
            gameResumedAtNanos = System.nanoTime();
        }
    }

    /**
     * The instant a pause ending at {@code now} counts as having ended: when the sounds came back from the pause menu
     * during this pause (onScreenClosing), that instant, otherwise {@code now}. Releasing the clocks as of a moment a
     * frame ago is safe: the integrated server does not tick while Minecraft#isPaused, which holds until the end of
     * that frame, so the battle time it reads only ever moves on.
     */
    private static long gameResumeInstant(long now) {
        long resumed = gameResumedAtNanos;
        gameResumedAtNanos = 0L;
        return resumed > 0L && resumed <= now && BattlePauseClock.GAME.paused()
                && resumed >= BattlePauseClock.GAME.pausedSince() ? resumed : now;
    }

    /**
     * Holds the battle clock at {@code now} for a pause, once. Minecraft pauses the sounds itself at about this
     * moment - just after it, for the pause menu (onScreenShown), or just before it, at the pause flag; doing it here
     * too covers a pause screen opened without that, so the song and the clock always stop together.
     */
    private static void holdBattleForGamePause(Minecraft minecraft, long now) {
        if (musicPausedForGame) {
            return;
        }
        minecraft.getSoundManager().pause();
        musicPausedForGame = true;
        songDrift.restart();
        BattleSongPosition.clear();
        if (clockHoldUntilNanos != 0L && now < clockHoldUntilNanos) {
            // The pause comes during a short hold of the clock, which BattlePauseClock turns into the pause from where
            // the hold began: so does this clock.
            clockHoldUntilNanos = 0L;
            return;
        }
        finishClockHold(now);
        // Stamped whether or not the music has started. The server freezes for as long as this client does, so the
        // battle clock has to be held back by the same amount. This used to be stamped only once the audio was
        // playing, and a pause during the count-in or while the file was still being opened left the chart running
        // on the wall clock while the server stood still: everything that went by in that window came back as a miss,
        // a whole phrase lost to standing in the menu.
        musicPausedAtNanos = now;
    }

    /** Lets the battle clock go on at {@code now} after a pause, once, together with the sounds. */
    private static void releaseBattleFromGamePause(Minecraft minecraft, long now) {
        if (!musicPausedForGame) {
            return;
        }
        // While the clock still stands where the pause stopped it.
        resumeLanesAfterGamePause();
        musicPausedForGame = false;
        minecraft.getSoundManager().resume();
        songDrift.restart();
        BattleSongPosition.clear();
        // Both origins move by the whole pause, so the battle resumes on the tick it was stopped at - which is the tick
        // the server is still on. Shifting only one of them, or neither, puts the two clocks a menu visit apart for the
        // rest of the song.
        long paused = musicPausedAtNanos > 0L ? Math.max(0L, now - musicPausedAtNanos) : 0L;
        if (musicStartedAtNanos > 0L) {
            musicStartedAtNanos += paused;
        }
        if (startAtNanos != 0L) {
            startAtNanos += paused;
        }
        musicPausedAtNanos = 0L;
    }

    /**
     * Gives the newest reading of where the battle song's source really is to the drift anchor and asks for the next one,
     * once a client tick while the song plays and the clock runs. A drift the anchor finds is taken out by moving the
     * battle clock - forward at once when the song has run ahead of the chart, held back for as long when it has fallen
     * behind - and only where the integrated server shares the clock (BattlePauseClock), a single-player world not open
     * to LAN: that is where the pause menu pauses the song, and the server's battle time moves with the client's, so
     * the battle stays on one clock. On someone else's server the clock is the server's and stays as it is.
     */
    private static void followSongPosition(Minecraft minecraft) {
        long now = System.nanoTime();
        if (clockHoldUntilNanos != 0L && now >= clockHoldUntilNanos) {
            finishClockHold(now);
        }
        if (music == null || musicPausedForGame || musicPausedAtNanos > 0L || musicStartedAtNanos <= 0L
                || startAtNanos == 0L || !(music instanceof BattleMusicSoundInstance battleMusic)) {
            BattleSongPosition.clear();
            return;
        }
        BattleSongPosition.Reading reading = BattleSongPosition.take(music);
        double streamStart = musicStreamStartTick;
        if (reading != null && Double.isFinite(streamStart)) {
            float pitch = Math.max(0.1F, battleMusic.getPitch());
            double song = streamStart * 0.05D + reading.playedSeconds() / pitch;
            double clock = (reading.atNanos() - startAtNanos) / 1_000_000_000.0D;
            double correction = songDrift.add(music, song - clock);
            if (correction != 0.0D && sharesClockWithServer(minecraft)) {
                moveClockForSong(correction, now);
            }
        }
        BattleSongPosition.request(minecraft, music);
    }

    private static boolean sharesClockWithServer(Minecraft minecraft) {
        return minecraft != null && minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null
                && !minecraft.getSingleplayerServer().isPublished();
    }

    /** Moves the battle clock, and the integrated server's with it, by {@code seconds}: see followSongPosition. */
    private static void moveClockForSong(double seconds, long now) {
        long nanos = Math.round(Math.abs(seconds) * 1_000_000_000.0D);
        if (nanos <= 0L) {
            return;
        }
        if (seconds > 0.0D) {
            startAtNanos -= nanos;
            musicStartedAtNanos -= nanos;
            BattlePauseClock.GAME.advance(nanos);
        } else {
            musicPausedAtNanos = now;
            clockHoldUntilNanos = now + nanos;
            BattlePauseClock.GAME.holdBriefly(now, nanos);
        }
        BattleSongPosition.clear();
    }

    /** Lets a short hold of the clock go, as of when it ran out or {@code now} if that is earlier. */
    private static void finishClockHold(long now) {
        long until = clockHoldUntilNanos;
        if (until == 0L) {
            return;
        }
        clockHoldUntilNanos = 0L;
        long end = Math.min(now, until);
        long held = musicPausedAtNanos > 0L ? Math.max(0L, end - musicPausedAtNanos) : 0L;
        if (startAtNanos != 0L) {
            startAtNanos += held;
        }
        if (musicStartedAtNanos > 0L) {
            musicStartedAtNanos += held;
        }
        musicPausedAtNanos = 0L;
        BattlePauseClock.GAME.endHold(end);
    }

    /**
     * followSongPosition for the song of the battle or DJ booth this client watches, against the spectator clock
     * (spectatorVisualTick), once a client tick while that song plays and the clock runs. The watched battle's mob
     * actions, notes and effects all wait on that clock, so moving it puts them back on the song. Only in a
     * single-player world not open to LAN, where the pause menu pauses the song: a watched battle's server time
     * (BattlePauseClock) moves with it there, so the server's battle and the spectator's stay a constant distance
     * apart, as before any visit; a DJ booth's show runs on server ticks, and only the spectator clock moves.
     */
    private static void followSpectatorSongPosition(Minecraft minecraft) {
        long now = System.nanoTime();
        if (spectatorClockHoldUntilNanos != 0L && now >= spectatorClockHoldUntilNanos) {
            finishSpectatorClockHold(now);
        }
        SoundInstance sound = spectatorMusic;
        long startedAt = spectatorMusicStartedAtNanos;
        if (sound == null || spectatorBattleId == null || spectatorMusicPausedForGame
                || spectatorMusicPausedAtNanos > 0L || startedAt <= 0L || spectatorMusicAwaitingStart
                || spectatorMusicRefused || minecraft.isPaused()) {
            return;
        }
        BattleSongPosition.Reading reading = BattleSongPosition.take(sound);
        if (reading != null) {
            float pitch = Math.max(0.1F, sound.getPitch());
            // The stream's first sample belongs to spectatorMusicClockStartTick, as the clock's does: the two cancel.
            double offset = reading.playedSeconds() / pitch - (reading.atNanos() - startedAt) / 1_000_000_000.0D;
            double correction = spectatorSongDrift.add(sound, offset);
            if (correction != 0.0D && sharesClockWithServer(minecraft)) {
                moveSpectatorClockForSong(correction, now, !standaloneDjPlayback);
            }
        }
        BattleSongPosition.request(minecraft, sound);
    }

    /** Moves the spectator clock by {@code seconds}, and the server's battle time with it when asked: see above. */
    private static void moveSpectatorClockForSong(double seconds, long now, boolean server) {
        long nanos = Math.round(Math.abs(seconds) * 1_000_000_000.0D);
        if (nanos <= 0L) {
            return;
        }
        if (seconds > 0.0D) {
            spectatorMusicStartedAtNanos -= nanos;
            if (server) {
                BattlePauseClock.GAME.advance(nanos);
            }
        } else {
            spectatorMusicPausedAtNanos = now;
            spectatorClockHoldUntilNanos = now + nanos;
            spectatorClockHoldMovesServer = server && !BattlePauseClock.GAME.paused();
            if (spectatorClockHoldMovesServer) {
                BattlePauseClock.GAME.holdBriefly(now, nanos);
            }
        }
        BattleSongPosition.clear();
    }

    /** Lets a short hold of the spectator clock go, as of when it ran out or {@code now} if that is earlier. */
    private static void finishSpectatorClockHold(long now) {
        long until = spectatorClockHoldUntilNanos;
        if (until == 0L) {
            return;
        }
        spectatorClockHoldUntilNanos = 0L;
        long end = Math.min(now, until);
        long held = spectatorMusicPausedAtNanos > 0L ? Math.max(0L, end - spectatorMusicPausedAtNanos) : 0L;
        if (spectatorMusicStartedAtNanos > 0L) {
            spectatorMusicStartedAtNanos += held;
        }
        spectatorMusicPausedAtNanos = 0L;
        if (spectatorClockHoldMovesServer) {
            BattlePauseClock.GAME.endHold(end);
        }
        spectatorClockHoldMovesServer = false;
    }

    /**
     * Picks the lanes up again where a real pause left them. The pause screen let go of every key mapping as it opened
     * (KeyMapping.releaseAll), and on macOS closing it does not read them back, so each lane takes its key's state
     * from the window's own events (ClientInputIsolation#isHeld). A lane that was down when the game paused and whose
     * key is still down goes on as it was - a hold note in it carries on - and one whose key came up during the pause
     * is released now, while the battle clock still stands at the pause, so the release counts at the paused song time.
     *
     * <p>A key that went down during the pause is not a press, held or not: the click on "Back to Game" with a lane on
     * the mouse button is still down when the game resumes, and taken as a press it was a tap at the resume instant -
     * a bad press, and the combo, if another lane's note was due. Off macOS, Minecraft has just set the key mappings
     * from the keyboard (KeyMapping.setAll), so this overrides that too; the key's repeats are not presses either
     * (ClientInputIsolation#laneKeyEvent). Such a key plays again once it is let go and pressed.
     */
    private static void resumeLanesAfterGamePause() {
        KeyMapping[] keys = MusicBattleKeys.lanes();
        for (int lane = 0; lane < keys.length && lane < previousLaneDown.length; lane++) {
            boolean held = previousLaneDown[lane] && ClientInputIsolation.isHeld(keys[lane].getKey());
            keys[lane].setDown(held);
            if (previousLaneDown[lane] && !held) {
                sendLaneState(lane, false);
                previousLaneDown[lane] = false;
            }
        }
        BattleLaneLatch.LANES.clear();
    }

    private static void holdSpectatorForGamePause(Minecraft minecraft, long now) {
        if (spectatorMusicPausedForGame) {
            return;
        }
        spectatorMusicPausedForGame = true;
        spectatorSongDrift.restart();
        BattleSongPosition.clear();
        minecraft.getSoundManager().pause();
        if (spectatorClockHoldUntilNanos != 0L && now < spectatorClockHoldUntilNanos
                && spectatorMusicPausedAtNanos > 0L) {
            // The pause comes during a short hold of the clock: the hold becomes the pause, from where it began, as
            // BattlePauseClock makes it of the server's battle time when the hold holds that too.
            spectatorClockHoldUntilNanos = 0L;
            spectatorClockHoldMovesServer = false;
            return;
        }
        finishSpectatorClockHold(now);
        spectatorMusicPausedAtNanos = spectatorMusicStartedAtNanos > 0L ? now : 0L;
    }

    private static void releaseSpectatorFromGamePause(Minecraft minecraft, long now) {
        if (!spectatorMusicPausedForGame) {
            return;
        }
        spectatorMusicPausedForGame = false;
        minecraft.getSoundManager().resume();
        spectatorSongDrift.restart();
        BattleSongPosition.clear();
        if (spectatorMusicPausedAtNanos > 0L && spectatorMusicStartedAtNanos > 0L) {
            spectatorMusicStartedAtNanos += Math.max(0L, now - spectatorMusicPausedAtNanos);
        }
        spectatorMusicPausedAtNanos = 0L;
    }

    public static void acceptBattleAudience(BattleAudiencePayload payload) {
        boolean matchesActive = active && battleId != null && battleId.equals(payload.battleId());
        boolean matchesSpectator = spectatorBattleId != null && spectatorBattleId.equals(payload.battleId());
        if (!matchesActive && !matchesSpectator) {
            return;
        }
        battleSpectatorEntityIds = Set.copyOf(payload.spectatorEntityIds());
        battleMobEntityIds = Set.copyOf(payload.mobEntityIds());
    }

    public static void tick() {
        // Every tick, battle or not, so a pause that ended by leaving the world cannot leave the clock frozen for the
        // next one. The integrated server reads this same clock; see BattlePauseClock.
        BattlePauseClock.GAME.observe(Minecraft.getInstance().isPaused(), System.nanoTime());
        refreshMusicVolume();
        settingsSyncTicks++;
        if (settingsSyncTicks >= 100) {
            settingsSyncTicks = 0;
            ClientBattleSettings.sendToServer();
        }

        if (preloadingTrack) {
            tickPreloadingTrack();
        }

        Minecraft minecraft = Minecraft.getInstance();
        captureSpectatorCameraInterpolation();
        activeCameraInterpolation.captureTickStart();
        // Packets are handled before this tick runs, so cameraYaw already holds this tick's aim.
        aimYawFrom = aimYawTo;
        aimPitchFrom = aimPitchTo;
        aimYawTo = cameraYaw;
        aimPitchTo = cameraPitch;
        tickBattleCameraSync(minecraft);
        if (!active) {
            tickSpectatorFxVisibility();
            tickSpectatorCamera(minecraft);
            if (spectatorBattleId != null && minecraft.isPaused()) {
                holdSpectatorForGamePause(minecraft, System.nanoTime());
                return;
            }
            releaseSpectatorFromGamePause(minecraft, System.nanoTime());
            tickSpectatorBattle();
            tryPlayPendingSpectatorMusic();
            tickSpectatorMusicRecovery(minecraft);
            followSpectatorSongPosition(minecraft);
            return;
        }

        if (minecraft.level == null || minecraft.player == null) {
            active = false;
            activeSolo = false;
            return;
        }

        if (minecraft.isPaused()) {
            minecraft.player.setShiftKeyDown(false);
            // A real pause - single player, not open to LAN - stops the song and the integrated server, so the lanes
            // stop with them instead of being let go: letting go broke whatever hold note the menu was opened in,
            // and the combo with it. resumeLanesAfterGamePause picks them up again. Where the pause menu does not
            // pause the game, the screen branch further down still lets go, as the battle plays on behind it.
            BattleLaneLatch.LANES.clear();
            holdBattleForGamePause(minecraft, System.nanoTime());
            return;
        }
        if (pendingAudioError != null) {
            reportBattleError(minecraft, pendingAudioError);
            return;
        }
        minecraft.player.setShiftKeyDown(false);
        releaseBattleFromGamePause(minecraft, System.nanoTime());

        boolean hasAudio = activeAudioFile != null && !activeAudioFile.isBlank();
        if (startAtNanos != 0L) {
            // The battle's own clock runs whatever the audio is doing. The sound is started just before tick zero
            // and seeded at wherever the clock already is, so a file that took half a second to open comes in at the
            // right place instead of dragging the whole chart late - which is what put the two players out of step.
            float clock = activeVisualTick(0.0F);
            localTicks = Mth.floor(clock);
            if (hasAudio && !musicStarted && clock >= -2.0F) {
                musicStarted = playMusic(minecraft, activeTrackId, activeAudioFile, Math.max(0, Mth.floor(clock)));
                if (!musicStarted) {
                    if (!ClientTrackAssets.isTransferActive(activeTrackId, "audio", activeAudioFile)) {
                        musicWaitTicks++;
                    }
                    if (musicWaitTicks >= MUSIC_DOWNLOAD_TIMEOUT_TICKS) {
                        reportBattleError(minecraft, text("music_battle.l10n.client.001") + activeAudioFile);
                        return;
                    }
                    ensureCamera(minecraft);
                    releaseLaneStates();
                    return;
                }
                musicWaitTicks = 0;
            } else if (musicStarted && hasAudio && localTicks >= 0 && localTicks % 20 == 0) {
                // Once a second: OpenAL drifts, and a stream can stall. Anything worse than a fifth of a second is
                // worth the seek, which is the same call the pause and skip paths already make.
                float audio = audioTick();
                if (!Float.isNaN(audio) && Math.abs(audio - clock) > 4.0F) {
                    playMusic(minecraft, activeTrackId, activeAudioFile, Math.max(0, Mth.floor(clock)));
                }
            }
            if (musicStarted && hasAudio) {
                retryRefusedMusic(minecraft, Mth.floor(clock));
                followSongPosition(minecraft);
            }
        } else {
            if (localTicks < 0 || !hasAudio) {
                localTicks++;
            }
            if (!musicStarted && localTicks >= 0) {
                musicStarted = !hasAudio || playMusic(minecraft, activeTrackId, activeAudioFile, 0);
                if (!musicStarted) {
                    if (!ClientTrackAssets.isTransferActive(activeTrackId, "audio", activeAudioFile)) {
                        musicWaitTicks++;
                    }
                    if (musicWaitTicks >= MUSIC_DOWNLOAD_TIMEOUT_TICKS) {
                        reportBattleError(minecraft, text("music_battle.l10n.client.001") + activeAudioFile);
                        return;
                    }
                    localTicks = -1;
                    ensureCamera(minecraft);
                    releaseLaneStates();
                    return;
                }
                musicWaitTicks = 0;
            }
            if (musicStarted && hasAudio) {
                retryRefusedMusic(minecraft, musicClockStartTick);
                if (musicStartedAtNanos <= 0L) {
                    localTicks = Math.max(0, musicClockStartTick);
                    ensureCamera(minecraft);
                    releaseLaneStates();
                    return;
                }
                localTicks = Math.max(0, Mth.floor(activeVisualTick(0.0F)));
            }
        }
        ensureCamera(minecraft);
        // Normally done at the start of each frame (beginRenderFrame); here too, for a client that draws no frames.
        showDueActions(heldActions, false, activeVisualTick(0.0F));
        flushPendingNoteActions(notes, localTicks, laneFlashes, hitBursts, false);
        flushPendingRemoteStates(notes, false);
        if (localTicks >= 0) {
            resolveLocalMissedNotes();
            resolveLocalCompletedHolds();
            sanitizeAnimationHeldMasks(notes, animationHeldMask, localTicks);
        }
        if (localTicks < 0) {
            releaseLaneStates();
            return;
        }
        for (int owner = 0; owner < 2; owner++) {
            for (int lane = 0; lane < 4; lane++) {
                if (timedLaneFlashes.timed(owner, lane)) {
                    continue;
                }
                if (laneFlashes[owner][lane] > 0) {
                    laneFlashes[owner][lane]--;
                }
                if (hitBursts[owner][lane] > 0) {
                    hitBursts[owner][lane]--;
                }
            }
        }
        timedLaneFlashes.apply(activeVisualTick(0.0F), laneFlashes, hitBursts);

        if (minecraft.screen == null) {
            boolean spaceDown = InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
            if (pauseSkipAvailable && spaceDown && !pauseSkipSpaceDown && battleId != null) {
                PacketDistributor.sendToServer(new PauseSkipVotePayload(battleId));
            }
            pauseSkipSpaceDown = spaceDown;
            KeyMapping[] keys = MusicBattleKeys.lanes();
            for (int lane = 0; lane < keys.length; lane++) {
                // Every press since the last tick, a tap that has already come back up included, goes out once: as
                // a key going down and, for such a tap, up again (see BattleLaneLatch).
                int changes = BattleLaneLatch.LANES.drain(lane, previousLaneDown[lane], keys[lane].isDown());
                for (int change = 0; change < changes; change++) {
                    boolean down = !previousLaneDown[lane];
                    sendLaneState(lane, down);
                    previousLaneDown[lane] = down;
                }
            }
        } else {
            pauseSkipSpaceDown = false;
            releaseLaneStates();
        }

        if (localTicks > durationTicks + 60) {
            active = false;
            activeSolo = false;
            stopMusic(minecraft);
            minecraft.player.setShiftKeyDown(false);
            minecraft.setCameraEntity(minecraft.player);
        }
    }

    public static void renderWorldPanels(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        ClientTrackEffects.EffectState cameraPreviewState = DjCameraPreviewRenderer.activeEffectState();
        boolean cameraPreview = DjCameraPreviewRenderer.isRenderingPreview() && cameraPreviewState != null;
        if (spectatorBattleId == null || (!cameraPreview && standaloneDjPlayback)) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // A camera's DJ FX clock can belong to a different song. Notes always follow
        // the battle audio heard by this listener, even inside that camera preview.
        float time = prepareSpectatorPanelFrame(partial);
        ClientTrackEffects.EffectState panelState = cameraPreview ? cameraPreviewState
                : ClientTrackEffects.sample(spectatorEffects, time,
                        spectatorTrackBpm, spectatorPlaybackSpeed, spectatorFxVisibility, spectatorBeatOrigin());
        if (panelState.hideBattlePanels() || hidesWorldPanelHud(cameraPreview, panelState)) {
            return;
        }
        PoseStack worldPose = event.getPoseStack();
        worldPose.pushPose();
        worldPose.mulPose(event.getModelViewMatrix());
        try {
            renderBattlePanelPair(event, partial, spectatorLeftEntityId, spectatorRightEntityId, time,
                    spectatorDurationTicks, spectatorLeftColor, spectatorRightColor, spectatorLeftSpeed,
                    spectatorRightSpeed, spectatorLeftScore, spectatorRightScore, spectatorCombo,
                    spectatorLaneFlashes, spectatorHitBursts, spectatorNotes, spectatorPanelStyles);
        } finally {
            worldPose.popPose();
        }
    }

    private static float prepareSpectatorPanelFrame(float partial) {
        float time = spectatorVisualTick(partial);
        flushPendingNoteActions(spectatorNotes, Mth.floor(time), spectatorLaneFlashes, spectatorHitBursts, true);
        flushPendingRemoteStates(spectatorNotes, true);
        return time;
    }

    private static boolean hidesWorldPanelHud(boolean cameraPreview, ClientTrackEffects.EffectState cameraState) {
        return cameraPreview ? cameraState.hidesBlockyMusicHud() : hidesBlockyMusicHud();
    }

    public static void renderWorldObjectOutline(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        ClientTrackEffects.EffectState state = currentObjectOutlineState(partial);
        if (state == null || state.objectOutlineEffect() == null) {
            return;
        }
        // Capture after block entities and translucent geometry so signs, speakers, cameras,
        // DJ panels and other non-full blocks are present. The outline shader rejects the
        // separately captured player/entity stencil mask.
        ClientFxShaderEngine.captureObjectOutlineBlocks();
    }

    private static ClientTrackEffects.EffectState currentObjectOutlineState(float partial) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TrackGameplayPreviewScreen preview) {
            return preview.effectStateAt(preview.previewTrackTick());
        } else if (active) {
            return ClientTrackEffects.sample(activeEffects, activeVisualTick(partial), trackBpm,
                    musicPlaybackSpeed, 1.0F, activeBeatOrigin());
        } else if (spectatorBattleId != null && spectatorFxVisibility > 0.001F) {
            return sampleSpectatorEffects(partial).outlineState();
        }
        return null;
    }

    public static void applyPlayerAnimation(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer clientPlayer)) {
            return;
        }
        if (Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview
                && Minecraft.getInstance().player == clientPlayer) {
            ClientBattleAnimation.setBeatOrigin(preview.previewBeatOrigin());
            ClientPlayerAnimatorBridge.updateBattleAnimation(clientPlayer, preview.previewAnimationTriggerSerial(),
                    preview.previewAnimationTriggerLane(), preview.previewAnimationHeldMask(), preview.previewBpm(),
                    preview.previewTrackTick());
            return;
        }
        if (!isParticipantEntity(event.getEntity().getId())) {
            ClientPlayerAnimatorBridge.stopBattleAnimation(clientPlayer);
            return;
        }
        int owner = ownerForEntity(event.getEntity().getId());
        boolean spectating = spectatorBattleId != null
                && (event.getEntity().getId() == spectatorLeftEntityId || event.getEntity().getId() == spectatorRightEntityId);
        int[] triggerSerials = spectating ? spectatorAnimationTriggerSerial : animationTriggerSerial;
        int[] triggerLanes = spectating ? spectatorAnimationTriggerLane : animationTriggerLane;
        int[] heldMasks = spectating ? spectatorAnimationHeldMask : animationHeldMask;
        int[] inputHeldMasks = spectating ? spectatorAnimationInputHeldMask : animationInputHeldMask;
        float[] triggerTicks = spectating ? spectatorAnimationTriggerTick : animationTriggerTick;
        int[] judgedSerials = spectating ? spectatorAnimationJudgedSerial : animationJudgedSerial;
        int[] judgedLanes = spectating ? spectatorAnimationJudgedLane : animationJudgedLane;
        float[] judgedTicks = spectating ? spectatorAnimationJudgedTick : animationJudgedTick;
        int animationBpm = spectating ? spectatorTrackBpm : trackBpm;
        float animationTick = spectating ? spectatorVisualTick(event.getPartialTick()) : activeVisualTick(event.getPartialTick());
        int safeOwner = Mth.clamp(owner, 0, 1);
        ClientBattleAnimation.setBeatOrigin(currentBeatOrigin(spectating));
        ClientPlayerAnimatorBridge.updateBattleAnimation(clientPlayer, triggerSerials[safeOwner], triggerLanes[safeOwner],
                triggerTicks[safeOwner], judgedSerials[safeOwner], judgedLanes[safeOwner], judgedTicks[safeOwner],
                heldMasks[safeOwner] | inputHeldMasks[safeOwner], animationBpm, animationTick);
    }

    public static void applyHumanoidAnimation(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof AbstractClientPlayer) {
            return;
        }
        if (!isParticipantEntity(entity.getId())) {
            ClientHumanoidAnimatorBridge.stopBattleAnimation(entity);
            ClientMobFallbackAnimator.stop(entity);
            return;
        }

        int owner = ownerForEntity(entity.getId());
        boolean spectating = spectatorBattleId != null
                && (entity.getId() == spectatorLeftEntityId || entity.getId() == spectatorRightEntityId);
        int[] triggerSerials = spectating ? spectatorAnimationTriggerSerial : animationTriggerSerial;
        int[] triggerLanes = spectating ? spectatorAnimationTriggerLane : animationTriggerLane;
        int[] heldMasks = spectating ? spectatorAnimationHeldMask : animationHeldMask;
        int[] inputHeldMasks = spectating ? spectatorAnimationInputHeldMask : animationInputHeldMask;
        float[] triggerTicks = spectating ? spectatorAnimationTriggerTick : animationTriggerTick;
        int[] judgedSerials = spectating ? spectatorAnimationJudgedSerial : animationJudgedSerial;
        int[] judgedLanes = spectating ? spectatorAnimationJudgedLane : animationJudgedLane;
        float[] judgedTicks = spectating ? spectatorAnimationJudgedTick : animationJudgedTick;
        int animationBpm = spectating ? spectatorTrackBpm : trackBpm;
        float animationTick = spectating ? spectatorVisualTick(event.getPartialTick()) : activeVisualTick(event.getPartialTick());
        int safeOwner = Mth.clamp(owner, 0, 1);
        if (event.getRenderer().getModel() instanceof HumanoidModel<?> humanoidModel
                && ClientHumanoidAnimatorBridge.supportsAnimation(humanoidModel)) {
            ClientMobFallbackAnimator.stop(entity);
            ClientBattleAnimation.setBeatOrigin(currentBeatOrigin(spectating));
            ClientHumanoidAnimatorBridge.prepareBattleAnimation(entity, humanoidModel, triggerSerials[safeOwner],
                    triggerLanes[safeOwner], triggerTicks[safeOwner], judgedSerials[safeOwner], judgedLanes[safeOwner],
                    judgedTicks[safeOwner], heldMasks[safeOwner] | inputHeldMasks[safeOwner], animationBpm,
                    animationTick, event.getPartialTick());
        } else {
            ClientHumanoidAnimatorBridge.stopBattleAnimation(entity);
            ClientMobFallbackAnimator.prepare(entity, triggerSerials[safeOwner],
                    heldMasks[safeOwner] | inputHeldMasks[safeOwner], animationBpm, animationTick,
                    currentBeatOrigin(spectating));
        }
    }

    public static boolean suppressParticipantLocomotion(LivingEntity entity) {
        return entity != null && isParticipantEntity(entity.getId());
    }

    public static void renderPlayerEffects(RenderPlayerEvent.Post event) {
        // LivingEntityRendererMixin draws the colored shell before the base model
        // while the exact vanilla body and layers join its ordered stencil mask.
    }

    public static void renderLivingEffects(RenderLivingEvent.Post<?, ?> event) {
        // See renderModelHighlightInRenderer.
    }

    public static void beginFxEntityMask(LivingEntity entity, MultiBufferSource buffers, float partialTick) {
        ClientModelGlowEngine.beginEntityFxMask(entity, buffers, needsFxEntityMask(entity, partialTick));
    }

    public static void finishFxEntityMask(LivingEntity entity, MultiBufferSource buffers) {
        ClientModelGlowEngine.finishEntityFxMask(entity, buffers);
    }

    private static boolean needsFxEntityMask(LivingEntity entity, float partialTick) {
        ClientTrackEffects.EffectState cameraState = DjCameraPreviewRenderer.activeEffectState();
        // Live monitors own an isolated stencil pass; editor previews still render only shells.
        if (cameraState != null && !DjCameraPreviewRenderer.isCompositingLiveMonitor()) {
            return false;
        }
        ClientTrackEffects.EffectState state;
        Minecraft minecraft = Minecraft.getInstance();
        if (cameraState != null) {
            state = cameraState;
        } else if (minecraft.screen instanceof TrackGameplayPreviewScreen preview) {
            state = preview.effectStateAt(preview.previewTrackTick());
        } else if (active) {
            state = ClientTrackEffects.sample(activeEffects, activeVisualTick(partialTick), trackBpm,
                    musicPlaybackSpeed, 1.0F, activeBeatOrigin());
        } else if (spectatorBattleId != null && spectatorFxVisibility > 0.001F) {
            state = sampleSpectatorEffects(partialTick);
        } else {
            return false;
        }
        boolean player = entity instanceof Player;
        for (TrackEffect effect : state.states().stream().flatMap(s->s.overlayEffects().stream()).toList()) {
            // OBJECT_OUTLINE is strictly block-only. Its player/entity switches are hidden in
            // the editor, so always record model masks for the late fullscreen composite.
            if (effect.type() == TrackEffect.Type.OBJECT_OUTLINE
                    || (player ? !effect.settings().overlayPlayers() : !effect.settings().overlayEntities())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderModelHighlightInRenderer(LivingEntity entity, Model model, PoseStack pose,
                                                       MultiBufferSource buffers, ResourceLocation texture,
                                                       int packedLight, float partialTick) {
        HighlightRender highlight = highlightFor(entity.getId(), partialTick);
        if (highlight != null) {
            renderOutlineModel(model, pose, buffers, highlight, texture, packedLight, entity.getId());
        }
    }

    public static int applyModelHighlightLight(LivingEntity entity, float partialTick, int packedLight) {
        if (ClientModelGlowEngine.isIrisShadowPass()) {
            return packedLight;
        }
        HighlightRender highlight = highlightFor(entity.getId(), partialTick);
        if (highlight == null || !highlight.effect().settings().highlightModelGlow()) {
            return packedLight;
        }
        // The body is lit exactly as strongly as its glow shell shows: ClientModelGlowEngine draws the shell with this
        // same product of the effect's own visibility and the fade over the last blocks of the area where the music is
        // heard. The light used to be all or nothing, so a mob in that band, or a crowd the listener walks away from,
        // stayed fully lit while its glow faded out and then dropped to the world's light in a single frame.
        float strength = Mth.clamp(highlight.visibility(), 0.0F, 1.0F) * FxAudibleAreaResolver.entityFade(entity.getId());
        if (strength <= 0.001F) {
            return packedLight;
        }
        if (strength >= 0.999F) {
            return LightTexture.FULL_BRIGHT;
        }
        // Packed light is block light in the low half and sky light in the high half, each a lightmap coordinate the
        // shader samples with filtering, so a value between two levels comes out as a light between them.
        int block = packedLight & 0xFFFF;
        int sky = packedLight >>> 16 & 0xFFFF;
        int fullBlock = LightTexture.FULL_BRIGHT & 0xFFFF;
        int fullSky = LightTexture.FULL_BRIGHT >>> 16 & 0xFFFF;
        return Math.round(Mth.lerp(strength, (float) block, (float) fullBlock))
                | Math.round(Mth.lerp(strength, (float) sky, (float) fullSky)) << 16;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void finishModelHighlightMask(LivingEntity entity, Object model, MultiBufferSource buffers) {
        ClientModelGlowEngine.finishModelStencilUnion(model, entity.getId(), buffers);
    }

    /** Renders an ordered glow shell for the separately transformed player cape. */
    public static void renderCapeHighlightInRenderer(LivingEntity entity, ModelPart cape, PoseStack pose,
                                                      MultiBufferSource buffers, float partialTick) {
        HighlightRender highlight = highlightFor(entity.getId(), partialTick);
        if (highlight != null) {
            ClientModelGlowEngine.renderPart(cape, pose, buffers, highlight.effect(), highlight.color(),
                    highlight.reactive(), highlight.visibility(), entity.getId());
        }
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        if (!active) {
            if (preloadingTrack && !hidesBlockyMusicHud()) {
                renderPreloadBar(graphics);
            }
            float spectatorVisibility = spectatorFxVisibility(partial);
            if (spectatorBattleId != null && spectatorVisibility > 0.001F) {
                ClientTrackEffects.renderOverlay(graphics, sampleSpectatorEffects(partial));
            }
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        // The playfield keeps the size it was designed at, whatever the player's GUI scale is; the mod's menus are
        // left alone and still follow the setting.
        BattleHudScale.Frame hud = BattleHudScale.frame(graphics.guiWidth(), graphics.guiHeight());
        int width = hud.width();
        int height = hud.height();
        float time = activeVisualTick(partial);
        float intro = introProgress(time);
        ClientTrackEffects.EffectState effectState = ClientTrackEffects.sample(activeEffects, time, trackBpm, musicPlaybackSpeed,
                1.0F, activeBeatOrigin());
        boolean hideHud = minecraft.options.hideGui || effectState.hidesBlockyMusicHud();

        if (!hud.identity()) {
            graphics.pose().pushPose();
            graphics.pose().scale(hud.factor(), hud.factor(), 1.0F);
        }
        ClientTrackEffects.canvas(width, height);
        if (!hideHud && !activeHideStandardDimming) {
            // The player's dimming (the old argb(70, 0, 0, 0) by default); at 0% nothing is drawn at all.
            BattleBackdrop.dim(graphics::fill, width, height, ClientBattleSettings.worldDimmingAlpha());
        }
        if (!hideHud && !activeDisableStageBars) {
            drawStageBars(graphics, width, height);
        }
        ClientTrackEffects.renderBelowInterface(graphics, effectState);
        graphics.flush();
        if (!hideHud) {
            if (activeSolo) {
                drawPanel(graphics, font, width, height, time, ownOwner, intro);
            } else {
                drawPanel(graphics, font, width, height, time, 0, intro);
                drawPanel(graphics, font, width, height, time, 1, intro);
            }
            drawHeader(graphics, font, width, time, intro);
            if (pauseSkipAvailable) {
                graphics.drawCenteredString(font, text("music_battle.l10n.client.002") + pauseSkipVotes + "/" + pauseSkipRequired + ")", width / 2, height - 22, 0xFFFFFF66);
            }
        }
        graphics.flush();
        ClientTrackEffects.renderAboveInterface(graphics, effectState);
        ClientTrackEffects.clearCanvas();
        if (!hud.identity()) {
            graphics.pose().popPose();
            graphics.flush();
        }
    }

    /**
     * The beatmap download before a battle, on the card the battle header is drawn on, in its place and at its size: the
     * battle HUD's scale is pushed here as it is for the header, so the bar does not jump when the battle takes over.
     */
    private static void renderPreloadBar(GuiGraphics graphics) {
        float chartProgress = ClientTrackAssets.transferProgress(preloadTrackId, "chart", preloadChartFile, preloadChartHash);
        float audioProgress = preloadAudioFile.isBlank() ? 1.0F : ClientTrackAssets.transferProgress(preloadTrackId, "audio", preloadAudioFile, preloadAudioHash);
        int totalBytes = Math.max(1, preloadChartBytes + preloadAudioBytes);
        float progress = (chartProgress * preloadChartBytes + audioProgress * preloadAudioBytes) / totalBytes;
        String title = preloadTrackTitle == null || preloadTrackTitle.isBlank() ? preloadTrackId : preloadTrackTitle;
        BattleHudScale.Frame hud = BattleHudScale.frame(graphics.guiWidth(), graphics.guiHeight());
        graphics.pose().pushPose();
        if (!hud.identity()) {
            graphics.pose().scale(hud.factor(), hud.factor(), 1.0F);
        }
        BattlePanelRenderer.drawLoading(graphics, hud.width(), title, progress);
        graphics.flush();
        graphics.pose().popPose();
    }

    public static void applyCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview) {
            ClientTrackEffects.EffectState state = preview.currentEffectState();
            event.setYaw(event.getYaw() + ClientTrackEffects.shakeYaw(state));
            event.setPitch(event.getPitch() + ClientTrackEffects.shakePitch(state));
            event.setRoll(event.getRoll() + ClientTrackEffects.shakeRoll(state));
            return;
        }
        if (!active && spectatorFxVisibility <= 0.001F && !spectatorCameraEnabled) {
            return;
        }
        float time = active ? activeVisualTick((float) event.getPartialTick()) : spectatorVisualTick((float) event.getPartialTick());
        List<TrackEffect> effects = active ? activeEffects : spectatorEffects;
        ClientTrackEffects.EffectState effectState = ClientTrackEffects.sample(effects, time,
                active ? trackBpm : spectatorTrackBpm, active ? musicPlaybackSpeed : spectatorPlaybackSpeed,
                active ? 1.0F : spectatorFxVisibility, active ? activeBeatOrigin() : spectatorBeatOrigin());
        if (!active) effectState = djTransitions.attach(effectState, System.nanoTime());
        if (!active) {
            if (spectatorCameraEnabled) {
                float cameraPartialTick = Mth.clamp((float) event.getPartialTick(), 0.0F, 1.0F);
                float renderedCameraYaw = Mth.rotLerp(cameraPartialTick,
                        spectatorCameraPreviousYaw, spectatorCameraYaw);
                float renderedCameraPitch = Mth.lerp(cameraPartialTick,
                        spectatorCameraPreviousPitch, spectatorCameraPitch);
                // Battle cameras retain their subtle stage orbit. A DJ camera
                // is an explicitly framed shot and must match the transform
                // selected in the gallery exactly.
                float swayTime = time + cameraSwayShift;
                float orbitYaw = standaloneDjPlayback || spectatorLocalTicks < 0
                        ? 0.0F : Mth.sin(swayTime / 58.0F) * 1.35F;
                float orbitPitch = standaloneDjPlayback || spectatorLocalTicks < 0
                        ? 0.0F : Mth.sin(swayTime / 47.0F) * 0.22F;
                event.setYaw(renderedCameraYaw + orbitYaw + ClientTrackEffects.shakeYaw(effectState));
                event.setPitch(renderedCameraPitch + orbitPitch + ClientTrackEffects.shakePitch(effectState));
            } else {
                event.setYaw(event.getYaw() + ClientTrackEffects.shakeYaw(effectState));
                event.setPitch(event.getPitch() + ClientTrackEffects.shakePitch(effectState));
            }
            event.setRoll(event.getRoll() + ClientTrackEffects.shakeRoll(effectState));
            return;
        }
        BattleCameraInterpolation.Sample activeCamera = activeCameraInterpolation.valid()
                ? activeCameraInterpolation.sample((float) event.getPartialTick()) : null;
        float partialTick = (float) event.getPartialTick();
        float renderedCameraYaw = activeCamera == null ? Mth.rotLerp(partialTick, aimYawFrom, aimYawTo) : activeCamera.yaw();
        float renderedCameraPitch = activeCamera == null ? Mth.lerp(partialTick, aimPitchFrom, aimPitchTo) : activeCamera.pitch();
        float intro = introProgress(time);
        float swayTime = time + cameraSwayShift;
        float orbitYaw = localTicks < 0 ? 0.0F : Mth.sin(swayTime / 58.0F) * 1.35F;
        float orbitPitch = localTicks < 0 ? 0.0F : Mth.sin(swayTime / 47.0F) * 0.22F;
        event.setYaw(Mth.rotLerp(intro, startYaw, renderedCameraYaw + orbitYaw) + ClientTrackEffects.shakeYaw(effectState));
        event.setPitch(Mth.lerp(intro, startPitch, renderedCameraPitch + orbitPitch) + ClientTrackEffects.shakePitch(effectState));
        event.setRoll(ClientTrackEffects.shakeRoll(effectState));
    }

    public static void applyFovPulse(ViewportEvent.ComputeFov event) {
        if (Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview) {
            event.setFOV(event.getFOV() + preview.currentFovOffset());
            return;
        }
        if (!active && !spectatorCameraEnabled && spectatorFxVisibility <= 0.001F) {
            return;
        }
        float time = active ? activeVisualTick((float) event.getPartialTick())
                : spectatorVisualTick((float) event.getPartialTick());
        int bpm = active ? trackBpm : spectatorTrackBpm;
        boolean battleCameraActive = active || spectatorCameraEnabled;
        float spectatorRenderedFov = Mth.lerp(Mth.clamp((float) event.getPartialTick(), 0.0F, 1.0F),
                spectatorCameraPreviousFov, spectatorCameraFov);
        float activeRenderedFov = activeCameraInterpolation.valid()
                ? activeCameraInterpolation.sample((float) event.getPartialTick()).fov() : cameraFov;
        float configuredFov = active ? activeRenderedFov : spectatorCameraEnabled ? spectatorRenderedFov : 0.0F;
        // A nearby battle may render FX for a free-view spectator, but it must not replace
        // that spectator's current (including modded/dynamic) FOV with the battle camera FOV.
        double baseFov = battleCameraActive && configuredFov > 0.0F ? configuredFov : event.getFOV();
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(active ? activeEffects : spectatorEffects,
                time, bpm, active ? musicPlaybackSpeed : spectatorPlaybackSpeed,
                active ? 1.0F : spectatorFxVisibility, active ? activeBeatOrigin() : spectatorBeatOrigin());
        if (!active) state = djTransitions.attach(state, System.nanoTime());
        baseFov += (state.cameraPulseBeat() - state.pulseBeat()) * 8.0;
        if (!active && standaloneDjPlayback && spectatorCameraEnabled) {
            // A DJ shot uses the exact FOV configured for that camera. It only
            // breathes when the active FX stack explicitly contains PULSE.
            event.setFOV(baseFov + state.pulseBeat() * 8.0D);
            return;
        }
        // The battle camera keeps its original subtle beat motion. A free-view spectator only
        // receives FOV motion when the chart explicitly contains an active PULSE effect.
        if (!battleCameraActive && state.pulse() <= 0.001F) {
            event.setFOV(baseFov);
            return;
        }
        double amplitude = 2.8D + state.pulse() * 8.0D;
        // A watcher's pulse lands on the same beats as the fighters': counted from the watched chart's beat 1.
        event.setFOV(baseFov + beatPulse(time, active ? sinceBeatOne(time)
                : (float) ChartBeat.beatTicks(time, spectatorBeatOffsetMs), bpm) * amplitude);
    }

    public static void hideHand(RenderHandEvent event) {
        if (active || spectatorCameraEnabled || Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen
                || hidesFirstPersonHand()) {
            event.setCanceled(true);
        }
    }

    public static void hideArm(RenderArmEvent event) {
        if (active || spectatorCameraEnabled || Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen
                || hidesFirstPersonHand()) {
            event.setCanceled(true);
        }
    }

    public static void hideGuiLayer(RenderGuiLayerEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean gameplayPreview = minecraft.screen instanceof TrackGameplayPreviewScreen;
        // Keep our layer alive: it also composites FX below/above the separately gated HUD.
        boolean hideVanilla = event.getName().getNamespace().equals("minecraft") && hidesGameHud();
        if ((active || gameplayPreview || minecraft.options.hideGui || hideVanilla)
                && !event.getName().equals(MusicBattleMod.id("battle_overlay"))) {
            event.setCanceled(true);
        }
    }

    public static void beginRenderFrame() {
        if (clockHoldUntilNanos != 0L && System.nanoTime() >= clockHoldUntilNanos) {
            finishClockHold(System.nanoTime());
        }
        if (spectatorClockHoldUntilNanos != 0L && System.nanoTime() >= spectatorClockHoldUntilNanos) {
            finishSpectatorClockHold(System.nanoTime());
        }
        sceneVisibility.invalidate();
        // Opening and Closing come from one Minecraft#setScreen call; one cancelled in between leaves this behind.
        screenComingNext = null;
        showActionsForFrame();
    }

    /**
     * Before anything is drawn: shows every held action the battle clock has now reached (a mob's hit lands in the
     * first frame at or after its tick, not at the next client tick), and every hit of the other side that came before
     * its note's tick and waited for it (showWaitingHitsDue), and steps the flashes they started.
     */
    private static void showActionsForFrame() {
        if (active) {
            float clock = activeVisualTick(0.0F);
            showWaitingHitsDue(notes, false, clock);
            if (!heldActions.isEmpty() || timedLaneFlashes.anyTimed()) {
                showDueActions(heldActions, false, clock);
                timedLaneFlashes.apply(clock, laneFlashes, hitBursts);
            }
        }
        if (spectatorBattleId != null) {
            float clock = spectatorVisualTick(0.0F);
            showWaitingHitsDue(spectatorNotes, true, clock);
            if (!spectatorHeldActions.isEmpty() || spectatorTimedLaneFlashes.anyTimed()) {
                showDueActions(spectatorHeldActions, true, clock);
                spectatorTimedLaneFlashes.apply(clock, spectatorLaneFlashes, spectatorHitBursts);
            }
        }
    }

    /**
     * Shows the other side's hits that came before their note's tick and wait for it (pendingHit) in the first frame
     * whose clock has reached that tick, as a held action is shown, with the score and combo they came with. Left to the
     * next client tick, such a hit came up to a tick after its note, and its flash, counted from the note's tick on the
     * battle clock, was first drawn from its second step whenever no frame fell between that client tick and the next
     * tick: in 17.7% of hits at 30 fps and 2.2% at 144. One's own waiting hits stay with the client tick, which counts
     * one's own lanes.
     */
    private static void showWaitingHitsDue(List<ClientNote> noteList, boolean spectator, float clock) {
        int currentTick = Mth.floor(clock);
        boolean shown = false;
        for (ClientNote note : noteList) {
            if (note.pendingHit && !note.resolved && currentTick >= note.tick && (spectator || note.owner != ownOwner)) {
                showWaitingHit(noteList, note, spectator, currentTick, spectator ? spectatorLaneFlashes : laneFlashes,
                        spectator ? spectatorHitBursts : hitBursts);
                shown = true;
            }
        }
        if (shown) {
            flushPendingRemoteStates(noteList, spectator);
        }
    }

    private static SceneVisibilityCache.Flags sceneVisibilityFlags() {
        return sceneVisibility.read(DjCameraPreviewRenderer.isRenderingPreview(),
                DjCameraPreviewRenderer.activeEffectState(), ClientBattleController::sampleSceneVisibilityEffects);
    }

    private static ClientTrackEffects.EffectState sampleSceneVisibilityEffects() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return null;
        if (minecraft.screen instanceof TrackGameplayPreviewScreen preview) {
            return preview.effectStateAt(preview.previewTrackTick());
        }
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        if (active) return ClientTrackEffects.sample(activeEffects, activeVisualTick(partial), trackBpm, musicPlaybackSpeed,
                1.0F, activeBeatOrigin());
        if (spectatorBattleId != null) return sampleSpectatorEffects(partial);
        return null;
    }

    public static boolean hidesWorldParticles() {
        return sceneVisibilityFlags().particles();
    }

    /** Rendering-only decision. Never write options.hideGui: manual F1 remains the user's choice. */
    public static boolean hidesGameHud() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.options != null && minecraft.options.hideGui) return true;
        return sceneVisibilityFlags().hud();
    }

    public static boolean hidesBlockyMusicHud() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.options != null && minecraft.options.hideGui) return true;
        return sceneVisibilityFlags().blockyMusicHud();
    }

    private static boolean hidesFirstPersonHand() {
        return sceneVisibilityFlags().hand();
    }

    public static void addOptionsButton(ScreenEvent.Init.Post event) {
        // The title screen is the first place a new player looks, before they have a world to pause: without the
        // button there, the settings, calibration, editor and guide could only be found after joining one.
        if (event.getScreen() instanceof TitleScreen || event.getScreen() instanceof OptionsScreen
                || event.getScreen() instanceof PauseScreen) {
            var screen = event.getScreen();
            // Its hint is the toolkit's card, like the Workshop button's beside it (MenuButtonTooltips).
            Button button = ru.gora.musicbattle.client.workshop.MenuButtonTooltips.button(MenuCornerButtons.settings(),
                    Component.literal("BM"), "music_battle.options",
                    ignored -> Minecraft.getInstance().setScreen(new MusicBattleSettingsScreen(screen)));
            event.addListener(button);
        }

        if (active && event.getScreen() instanceof PauseScreen screen) {
            Button button = ru.gora.musicbattle.client.workshop.MenuButtonTooltips.button(Rect.of(screen.width / 2 - 44, 8, 88, 20),
                    Component.literal(text("music_battle.l10n.client.004")), "music_battle.l10n.client.005", ignored -> surrender());
            event.addListener(button);
        }
    }

    private static void tickPreloadingTrack() {
        if (!preloadingTrack || preloadBattleId == null) {
            return;
        }
        preloadTicks++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // A file this player already has - usually from a map they installed in the Workshop - is put where the
        // battle looks for it instead of being asked for again. Without this the two sides disagreed: the client
        // told the server it had the content, the server skipped the transfer, and the battle waited five minutes
        // for a file that was never coming.
        ClientTrackAssets.materialiseFromContent(preloadTrackId, preloadChartFile, preloadChartHash);
        if (preloadAudioFile != null && !preloadAudioFile.isBlank()) {
            ClientTrackAssets.materialiseFromContent(preloadTrackId, preloadAudioFile, preloadAudioHash);
        }
        Optional<Path> chart = ClientTrackAssets.cachedAsset(preloadTrackId, preloadChartFile, preloadChartHash);
        boolean audioReady = preloadAudioFile == null
                || preloadAudioFile.isBlank()
                || ClientTrackAssets.cachedAsset(preloadTrackId, preloadAudioFile, preloadAudioHash).isPresent();
        boolean ready = chart.isPresent() && audioReady;
        if (ready && !preloadedChartLoaded) {
            try {
                ClientTrackChart.LoadedChart loadedChart = ClientTrackChart.read(chart.get(), preloadTrackId);
                preloadedChartNotes = loadedChart.notes();
                preloadedChartEffects = loadedChart.effects();
                preloadedDisableStageBars = loadedChart.disableStageBars();
                preloadedOffsetMs = loadedChart.offsetMs();
                preloadedHideStandardDimming = loadedChart.hideStandardDimming();
                preloadedChartLoaded = true;
            } catch (IOException exception) {
                UUID failedBattleId = preloadBattleId;
                preloadingTrack = false;
                preloadBattleId = null;
                // No local path may leave this machine: the server writes the reason to its log and, when this
                // client is a spectator, back into this player's own chat. Everyone else learns at most whose client failed.
                PacketDistributor.sendToServer(new BattleClientErrorPayload(failedBattleId,
                        text("music_battle.l10n.client.006") + MusicBattleTracks.safeMessage(exception)));
                return;
            }
        }
        if (ready && !preloadReadySent) {
            sendPreloadReady();
        }
        // The index said this player had the content, the server took them at their word and sent nothing, and the
        // file is still not here: the index was stale. Rebuild it and give up quickly with a reason, rather than
        // leaving them watching a progress bar for five minutes, which is what the owner saw as an endless download.
        boolean claimedToHave = ClientTrackAssets.hasContent(preloadChartHash)
                || preloadAudioFile != null && !preloadAudioFile.isBlank() && ClientTrackAssets.hasContent(preloadAudioHash);
        if (!ready && claimedToHave && preloadTicks > STALE_CONTENT_GRACE_TICKS
                && !ClientTrackAssets.isTransferActive(preloadTrackId, "chart", preloadChartFile)
                && (preloadAudioFile.isBlank() || !ClientTrackAssets.isTransferActive(preloadTrackId, "audio", preloadAudioFile))) {
            UUID staleBattleId = preloadBattleId;
            preloadingTrack = false;
            preloadBattleId = null;
            ClientTrackAssets.refreshHashIndex();
            PacketDistributor.sendToServer(new BattleClientErrorPayload(staleBattleId,
                    text("music_battle.l10n.client.007") + preloadTrackId));
            return;
        }
        if (!ready
                && preloadTicks > MUSIC_DOWNLOAD_TIMEOUT_TICKS
                && !ClientTrackAssets.isTransferActive(preloadTrackId, "chart", preloadChartFile)
                && (preloadAudioFile.isBlank() || !ClientTrackAssets.isTransferActive(preloadTrackId, "audio", preloadAudioFile))) {
            UUID failedBattleId = preloadBattleId;
            preloadingTrack = false;
            preloadBattleId = null;
            preloadAudioHash = "";
            PacketDistributor.sendToServer(new BattleClientErrorPayload(failedBattleId, text("music_battle.l10n.client.007") + preloadTrackId));
        }
    }

    private static void sendPreloadReady() {
        if (preloadBattleId == null || preloadReadySent) {
            return;
        }
        preloadReadySent = true;
        PacketDistributor.sendToServer(new TrackReadyPayload(
                preloadBattleId,
                preloadTrackId,
                preloadChartFile == null ? "" : preloadChartFile,
                preloadAudioFile == null ? "" : preloadAudioFile
        ));
    }

    private static void sendLaneState(int lane, boolean down) {
        if (battleId == null) {
            return;
        }

        if (down) {
            triggerPlayerAnimation(false, ownOwner, lane);
        }
        int inputTick = calibratedInputTick();
        int noteIndex = down ? predictLocalInput(lane, inputTick) : predictLocalRelease(lane, inputTick);
        if (down) {
            ClientNote pressedNote = noteByIndex(notes, noteIndex);
            setPlayerAnimationHeld(false, ownOwner, lane, pressedNote != null && pressedNote.durationTicks > 0);
            setPlayerAnimationInputHeld(false, ownOwner, lane, true);
            laneFlashes[ownOwner][lane] = 6;
        } else {
            setPlayerAnimationHeld(false, ownOwner, lane, false);
            setPlayerAnimationInputHeld(false, ownOwner, lane, false);
        }
        PacketDistributor.sendToServer(new NoteInputPayload(battleId, lane, inputTick, down, noteIndex));
    }

    private static int calibratedInputTick() {
        return CalibrationTiming.inputTick(activeVisualTick(0.0F), ClientBattleSettings.inputOffsetMs());
    }

    private static int predictLocalInput(int lane, int inputTick) {
        if (inputTick < laneHitLockoutUntil[lane]) {
            return -1;
        }

        ClientNote closest = null;
        int closestDistance = Math.max(MISS_AFTER_PANEL_EXIT_TICKS, EARLY_HIT_WINDOW_TICKS) + 1;
        int earlyHitWindow = earlyHitWindowTicks(ownSpeed);
        for (ClientNote note : notes) {
            // A note hit before its tick is hit already: only showing it waits for that tick (pendingHit), and the
            // server took it at the hit. Taken as still open, the next press on its lane hit it a second time, its
            // score counted twice here while the server turned the second hit down, and the note that press was for
            // went by as a miss: at note speed 1, taps on 150 and 152 pressed on 147 and 149.
            if (note.resolved || note.started || note.pendingHit || note.owner != ownOwner || note.lane != lane) {
                continue;
            }
            int earlyDistance = note.tick - inputTick;
            int lateDistance = inputTick - note.tick;
            boolean hittable = earlyDistance >= 0 ? earlyDistance <= earlyHitWindow : lateDistance <= MISS_AFTER_PANEL_EXIT_TICKS;
            int distance = Math.abs(earlyDistance);
            if (hittable && distance < closestDistance) {
                closest = note;
                closestDistance = distance;
            }
        }

        if (closest == null) {
            if (shouldBreakComboForBadPress(lane, inputTick)) {
                breakOwnComboForMistake(lane);
                sendLocalJudgement(ClientJudgementPayload.BAD_PRESS, lane, -1, inputTick);
            }
            return -1;
        }

        applyNoteAction(notes, ownOwner, lane, closest.index, closest.tick, localTicks, true, closest.durationTicks <= 0);
        int comboBeforeHit = ownCombo;
        ownCombo++;
        int scoreGain = BattleScoreMath.comboAdjustedHitScore(
                timingScore(closest.tick, inputTick), comboBeforeHit);
        if (ownOwner == 0) {
            leftScore = BattleScoreMath.saturatingAdd(leftScore, scoreGain);
        } else {
            rightScore = BattleScoreMath.saturatingAdd(rightScore, scoreGain);
        }
        laneFlashes[ownOwner][lane] = 8;
        hitBursts[ownOwner][lane] = 8;
        laneHitLockoutUntil[lane] = inputTick + SAME_LANE_HIT_LOCKOUT_TICKS;
        sendLocalJudgement(ClientJudgementPayload.NOTE_HIT, lane, closest.index, inputTick);
        return closest.index;
    }

    private static int predictLocalRelease(int lane, int inputTick) {
        ClientNote hold = activeOwnHold(lane);
        if (hold == null) {
            hold = waitingOwnHold(lane);
            if (hold == null) {
                return -1;
            }
            // Taken two or more client ticks before its tick, the hold waits for that tick to start (applyNoteAction),
            // and this key went up before it came. Letting go of a hold is judged here and nowhere else - the client
            // only ever completes a hold with its key down (resolveLocalCompletedHolds) - so passed over, the hold
            // started on its tick with the key already up, was never settled here, and the server, which had started
            // it with the hit, completed it as a hit with its hold score once its patience ran out. Taken now, it is
            // judged as any hold let go of: broken, with its lane flash and the combo, and the server is told.
            hold.pendingHit = false;
            hold.started = true;
        }

        int endTick = hold.tick + hold.durationTicks;
        boolean completed = inputTick >= endTick - HOLD_RELEASE_EARLY_TICKS;
        applyNoteAction(notes, ownOwner, lane, hold.index, hold.tick, localTicks, completed, true);
        if (completed) {
            if (ownOwner == 0) {
                leftScore += Math.max(20, hold.durationTicks / 2);
            } else {
                rightScore += Math.max(20, hold.durationTicks / 2);
            }
            sendLocalJudgement(ClientJudgementPayload.HOLD_COMPLETE, lane, hold.index, inputTick);
        } else {
            breakOwnComboForMistake(lane);
            sendLocalJudgement(ClientJudgementPayload.HOLD_BREAK, lane, hold.index, inputTick);
        }
        return hold.index;
    }

    /**
     * One's own hold on a lane that is being held and whose release is still to be judged. A hold let go of inside its
     * release window is judged complete at once and, with its hold score counted, stays started (pendingComplete) until
     * its end tick settles it (flushPendingNoteActions): it is passed over here. Found again, the next release on its
     * lane - the key pressed again for the next hold, taken early, and let go of before that hold's tick - judged it a
     * second time and counted its hold score again, while the next hold was never let go of on this screen: the server
     * broke it at the key-up, and every other screen showed x0 and a mistake where ours kept the combo.
     */
    private static ClientNote activeOwnHold(int lane) {
        for (ClientNote note : notes) {
            if (!note.resolved && note.started && !note.pendingComplete && note.owner == ownOwner && note.lane == lane
                    && note.durationTicks > 0) {
                return note;
            }
        }
        return null;
    }

    /** One's own hold on a lane that was hit before its tick and is still waiting for it (pendingHit). */
    private static ClientNote waitingOwnHold(int lane) {
        for (ClientNote note : notes) {
            if (!note.resolved && !note.started && note.pendingHit && note.owner == ownOwner && note.lane == lane
                    && note.durationTicks > 0) {
                return note;
            }
        }
        return null;
    }

    private static boolean shouldBreakComboForBadPress(int pressedLane, int inputTick) {
        for (ClientNote note : notes) {
            // A note hit before its tick (pendingHit) is no longer due: the server, by the same rule, passes over it as
            // started or resolved since the hit, and would turn down a bad press this one called for it.
            if (note.resolved || note.started || note.pendingHit || note.owner != ownOwner) {
                continue;
            }
            int earlyDistance = note.tick - inputTick;
            int lateDistance = inputTick - note.tick;
            if (note.lane == pressedLane) {
                int earlyHitWindow = earlyHitWindowTicks(ownSpeed);
                if (earlyDistance > earlyHitWindow && earlyDistance <= earlyHitWindow + EARLY_FAIL_TICKS) {
                    return true;
                }
                continue;
            }
            if (earlyDistance <= earlyHitWindowTicks(ownSpeed) && lateDistance <= MISS_AFTER_PANEL_EXIT_TICKS) {
                return true;
            }
        }
        return false;
    }

    private static int earlyHitWindowTicks(float speed) {
        return NoteSpeed.earlyHitWindowTicks(speed);
    }

    private static void breakOwnComboForMistake(int lane) {
        breakOwnComboForMistake(lane, true);
    }

    private static void breakOwnComboForMistake(int lane, boolean flashLane) {
        ownCombo = 0;
        ownMistakes++;
        if (ownOwner == 0) {
            leftScore = Math.max(0, leftScore - 24);
        } else {
            rightScore = Math.max(0, rightScore - 24);
        }
        if (flashLane) {
            laneFlashes[ownOwner][lane] = 4;
        }
    }

    private static boolean applyNoteAction(List<ClientNote> noteList, int owner, int lane, int noteIndex, int noteTick, int currentTick, boolean hit, boolean complete) {
        ClientNote note = noteByIndex(noteList, noteIndex);
        if (note != null && (note.owner != owner || note.lane != lane)) {
            note = null;
        }
        if (note != null && noteTick >= 0 && Math.abs(note.tick - noteTick) > 1) {
            note = null;
        }
        if (note == null && noteTick >= 0) {
            note = noteByOwnerLaneTick(noteList, owner, lane, noteTick);
        }
        if (note == null) {
            note = nearestUnresolvedNote(noteList, owner, lane, currentTick);
        }
        if (note == null) {
            return false;
        }
        // An action for a note still on its way waits for the note's tick. A hold already taken is settled whenever its
        // action comes: the break of a hold hit early, whose waiting hit has just been shown (showWaitingHit), is one.
        if (noteTick >= 0 && currentTick < note.tick - 1 && !note.started) {
            note.pendingHit = hit;
            note.pendingMiss = !hit;
            note.pendingComplete = complete;
            return false;
        }
        if (complete) {
            if (hit && note.durationTicks > 0 && currentTick < note.tick + note.durationTicks) {
                note.started = true;
                note.pendingComplete = true;
                return true;
            }
            note.resolved = true;
            note.started = false;
            note.pendingComplete = false;
            return true;
        }
        if (note.durationTicks > 0 && hit) {
            note.started = true;
            return true;
        }
        note.resolved = true;
        return true;
    }

    private static void flushPendingNoteActions(List<ClientNote> noteList, int currentTick, int[][] flashes, int[][] bursts, boolean spectator) {
        for (ClientNote note : noteList) {
            if (note.resolved) {
                note.pendingHit = false;
                note.pendingMiss = false;
                note.pendingComplete = false;
                if (note.pendingRemoteState) {
                    note.pendingRemoteStateReady = true;
                }
                continue;
            }
            if (note.started && note.pendingComplete
                    && currentTick >= note.tick + note.durationTicks) {
                note.resolved = true;
                note.started = false;
                note.pendingComplete = false;
                note.pendingRemoteStateReady = true;
                setPlayerAnimationHeld(spectator, note.owner, note.lane, false);
                continue;
            }
            if (note.pendingHit && currentTick >= note.tick) {
                showWaitingHit(noteList, note, spectator, currentTick, flashes, bursts);
                continue;
            }
            if (note.pendingMiss && currentTick - note.tick >= MISS_AFTER_PANEL_EXIT_TICKS) {
                note.resolved = true;
                note.started = false;
                note.pendingMiss = false;
                note.pendingComplete = false;
                note.pendingRemoteStateReady = true;
            }
        }
    }

    private static void queuePendingRemoteState(List<ClientNote> noteList, int noteIndex, int left, int right, int combo,
                                                long arrival) {
        ClientNote note = noteByIndex(noteList, noteIndex);
        if (note == null) {
            return;
        }
        note.pendingRemoteState = true;
        note.pendingLeftScore = left;
        note.pendingRightScore = right;
        note.pendingCombo = combo;
        note.pendingArrival = arrival;
    }

    private static void flushPendingRemoteStates(List<ClientNote> noteList, boolean spectator) {
        for (ClientNote note : noteList) {
            if (!note.pendingRemoteState || !note.pendingRemoteStateReady) {
                continue;
            }
            note.pendingRemoteState = false;
            note.pendingRemoteStateReady = false;
            if (spectator || note.owner != ownOwner) {
                showRemoteState(spectator, note.owner, note.pendingLeftScore, note.pendingRightScore, note.pendingCombo,
                        note.pendingArrival);
            }
        }
    }

    /**
     * Shows a side's score and combo from its action numbered {@code arrival} (remoteStateArrivals), unless one of its
     * actions that came later is on show already: a side's numbers only ever go forward. A hit that came before its
     * note's tick keeps its numbers on the note until the note gets there (queuePendingRemoteState), and these waiting
     * numbers were shown in the order of the notes, not of the actions: the other player hits B (tick 101) at 98 with
     * x5, then A (tick 100) with x6, and their combo read x6, then x5 again from tick 101 until their next action. A
     * later action for the same note drops them the same way: shown after the break of a hold taken early, the early
     * hit's x6 took the combo back up from x0.
     */
    private static void showRemoteState(boolean spectator, int owner, int left, int right, int combo, long arrival) {
        long[] shown = spectator ? spectatorRemoteStateShown : activeRemoteStateShown;
        int side = Mth.clamp(owner, 0, 1);
        if (arrival <= shown[side]) {
            return;
        }
        shown[side] = arrival;
        if (spectator) {
            applySpectatorRemoteState(side, left, right, combo);
        } else {
            applyActiveRemoteState(side, side == 0 ? left : right, combo);
        }
    }

    /**
     * Takes the server's number for our own side after it resolved something we did not.
     *
     * Only ever called for a correction, never for the echo of a hit we made: our own prediction is what the server
     * is repeating back, and applying that would make a fast player's score jump backwards for a frame.
     *
     * The mistake count is ours alone - the server has no field for it in the action and takes our number back from
     * the next judgement we send - so a miss the server resolved has to be counted here or it is counted nowhere,
     * which is how a run could end as a full combo with a miss in the server's own record of it.
     */
    private static void applyOwnCorrection(int owner, int score, int combo, boolean mistake) {
        if (owner == 0) {
            leftScore = score;
        } else {
            rightScore = score;
        }
        ownCombo = combo;
        if (mistake) {
            ownMistakes++;
        }
    }

    private static void applyActiveRemoteState(int owner, int score, int combo) {
        if (owner == 0) {
            leftScore = score;
        } else {
            rightScore = score;
        }
        opponentCombo = combo;
    }

    /**
     * Takes a side's score and combo from one of its own actions. Only that side's: the other side's score in the same
     * action is from the moment the server sent it, and a mob plays ahead of the battle, so it could be a mob's score
     * from a tick still to come, or, in an action held for its tick, a player's from before their latest hit. A side's
     * score only ever changes in that side's own actions, so each keeps its own up to date.
     */
    private static void applySpectatorRemoteState(int owner, int left, int right, int combo) {
        if (owner == 0) {
            spectatorLeftScore = left;
        } else {
            spectatorRightScore = right;
        }
        spectatorCombo[owner] = combo;
    }

    private static void resolveLocalMissedNotes() {
        int inputTick = calibratedInputTick();
        for (ClientNote note : notes) {
            // A note hit before its tick (pendingHit) is not missed, however far a large negative input offset puts
            // the input clock ahead of the tick that shows the hit.
            if (note.resolved || note.started || note.pendingHit || note.owner != ownOwner) {
                continue;
            }
            if (inputTick - note.tick >= MISS_AFTER_PANEL_EXIT_TICKS) {
                note.resolved = true;
                breakOwnComboForMistake(note.lane, false);
                sendLocalJudgement(ClientJudgementPayload.NOTE_MISS, note.lane, note.index, inputTick);
            }
        }
    }

    private static void resolveLocalCompletedHolds() {
        int inputTick = calibratedInputTick();
        for (ClientNote note : notes) {
            // A hold already judged complete (pendingComplete: let go of inside its release window) has had its hold
            // score and only waits for its end tick to be settled; a key down on its lane by then is the next note's.
            // Completed again here, its score counted twice: with a negative input offset the input clock reaches the
            // end before the tick that settles it.
            if (note.resolved || !note.started || note.pendingComplete || note.owner != ownOwner
                    || note.durationTicks <= 0) {
                continue;
            }
            int endTick = note.tick + note.durationTicks;
            if (inputTick < endTick || !previousLaneDown[note.lane]) {
                continue;
            }
            note.resolved = true;
            note.started = false;
            int holdScore = Math.max(20, note.durationTicks / 2);
            if (ownOwner == 0) {
                leftScore += holdScore;
            } else {
                rightScore += holdScore;
            }
            sendLocalJudgement(ClientJudgementPayload.HOLD_COMPLETE, note.lane, note.index, inputTick);
        }
    }

    private static void sendLocalJudgement(int kind, int lane, int noteIndex, int inputTick) {
        if (battleId == null) {
            return;
        }
        int score = ownOwner == 0 ? leftScore : rightScore;
        PacketDistributor.sendToServer(new ClientJudgementPayload(
                battleId,
                ++judgementSequence,
                kind,
                Mth.clamp(lane, 0, 3),
                noteIndex,
                inputTick,
                score,
                ownCombo,
                ownMistakes
        ));
    }

    private static ClientNote noteByOwnerLaneTick(List<ClientNote> noteList, int owner, int lane, int noteTick) {
        ClientNote closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (ClientNote note : noteList) {
            if (note.resolved || note.owner != owner || note.lane != lane) {
                continue;
            }
            int distance = Math.abs(note.tick - noteTick);
            if (distance < closestDistance) {
                closest = note;
                closestDistance = distance;
            }
        }
        return closestDistance <= 1 ? closest : null;
    }

    private static ClientNote nearestUnresolvedNote(List<ClientNote> noteList, int owner, int lane, int currentTick) {
        ClientNote closest = null;
        int closestDistance = Math.max(MISS_AFTER_PANEL_EXIT_TICKS, EARLY_HIT_WINDOW_TICKS) + 1;
        for (ClientNote note : noteList) {
            if (note.resolved || note.owner != owner || note.lane != lane) {
                continue;
            }
            int distance = Math.abs(note.tick - currentTick);
            if (distance < closestDistance) {
                closest = note;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static ClientNote noteByIndex(List<ClientNote> noteList, int noteIndex) {
        if (noteIndex >= 0 && noteIndex < noteList.size() && noteList.get(noteIndex).index == noteIndex) {
            return noteList.get(noteIndex);
        }
        for (ClientNote note : noteList) {
            if (note.index == noteIndex) {
                return note;
            }
        }
        return null;
    }

    private static boolean shouldFlashAction(List<ClientNote> noteList, int noteIndex, boolean hit, boolean complete) {
        if (noteIndex < 0) {
            return true;
        }
        ClientNote note = noteByIndex(noteList, noteIndex);
        if (note == null || !hit) {
            return false;
        }
        if (note.durationTicks <= 0) {
            return true;
        }
        return !complete && !note.started;
    }

    /**
     * Whether an action breaks a hold that was being held on this panel: a miss that ends a started hold. Asked
     * before the action is applied, as shouldFlashAction is. One's own broken hold flashes its lane once, at 4 and
     * without a burst (breakOwnComboForMistake), so the other side's broken hold - a mob's, or another player's that
     * the server passed on - flashes the same way here, and with no punch: letting go is not a press. A note that was
     * never started is a missed tap and leaves the panel without a flash, as one's own does.
     */
    private static boolean breaksStartedHold(List<ClientNote> noteList, int noteIndex, boolean hit, boolean complete) {
        if (hit || !complete || noteIndex < 0) {
            return false;
        }
        ClientNote note = noteByIndex(noteList, noteIndex);
        return note != null && note.durationTicks > 0 && note.started && !note.resolved;
    }

    /**
     * Whether an action breaks a hold whose head was hit before its tick and is still waiting for that tick to be shown
     * (pendingHit, see applyNoteAction): the other player took the hold early and let go of it early too. Their own
     * screen showed the hit and then the broken hold, so this one shows the waiting hit first (showWaitingHit) and then
     * the break, as any broken hold. Left waiting, the break turned the hold into a missed head, falling past its
     * button with no flash, or made it vanish with none; and the hit's score and combo, shown at the next client tick,
     * came after the break's and took the combo back up.
     */
    private static boolean breaksWaitingHold(ClientNote note, int owner, int lane, boolean hit, boolean complete) {
        return note != null && !hit && complete && note.owner == owner && note.lane == lane && note.durationTicks > 0
                && note.pendingHit && !note.started && !note.resolved;
    }

    /**
     * Shows a hit that came before its note's tick and waited for it (pendingHit): the note is struck, or its hold
     * taken, with the hit's flash, burst and judgement, and the score and combo it came with are ready to show. Done
     * when the note reaches its tick (flushPendingNoteActions), or at once when the hold it took is broken before then
     * (breaksWaitingHold).
     *
     * <p>One's own early hit flashed, burst and punched already at the press (predictLocalInput, and the key's own
     * animation), so at its tick it is only struck and its hold taken. It used to flash and burst a second time there,
     * and give a second, judged punch, which one's own hit on time never gets: two flashes for one press.
     */
    private static void showWaitingHit(List<ClientNote> noteList, ClientNote note, boolean spectator, int currentTick,
                                       int[][] flashes, int[][] bursts) {
        boolean held = note.durationTicks > 0 && (!note.pendingComplete || currentTick < note.tick + note.durationTicks);
        if (held) {
            note.started = true;
        } else {
            note.resolved = true;
            note.started = false;
        }
        setPlayerAnimationHeld(spectator, note.owner, note.lane, holdsLane(noteList, note.owner, note.lane));
        if (spectator || note.owner != ownOwner) {
            triggerJudgementAnimation(spectator, note.owner, note.lane);
            // The other side's hit lands on its note's tick and counts down from there on the battle clock, as a held
            // action's does (showLaneFlash): set in a client tick right before that tick counts its flashes down, it
            // was drawn from its second step every time. Shown before its tick (breaksWaitingHold), from the moment
            // it is shown.
            TimedLaneFlashes timed = spectator ? spectatorTimedLaneFlashes : timedLaneFlashes;
            timed.start(note.owner, note.lane, 8, Math.min(note.tick, spectator ? spectatorVisualTick(0.0F)
                    : activeVisualTick(0.0F)));
            flashes[note.owner][note.lane] = 8;
            bursts[note.owner][note.lane] = 8;
        }
        note.pendingHit = false;
        if (!held || !note.pendingComplete) {
            note.pendingComplete = false;
        }
        note.pendingRemoteStateReady = true;
    }

    /**
     * Whether a side holds a hold on a lane: one of its holds there was hit and is not over. The held pose follows this
     * rather than the note an action names. A mob's missed hold head is settled only once it has left the panel
     * (BattleManager#resolveMissedNotes), and by then the mob can be holding the next hold on that lane; settling the
     * miss lets go of nothing, yet it took the pose down, and a mob has no keys to hold it up (only a player's keys
     * come as BattleLaneAnimationPayload), so it stayed down until that hold's next action, up to four ticks later.
     */
    private static boolean holdsLane(List<ClientNote> noteList, int owner, int lane) {
        for (ClientNote note : noteList) {
            if (note.owner == owner && note.lane == lane && note.durationTicks > 0 && note.started && !note.resolved) {
                return true;
            }
        }
        return false;
    }

    private static int timingScore(int noteTick, int inputTick) {
        int earlyDistance = noteTick - inputTick;
        int lateDistance = inputTick - noteTick;
        int distance = Math.abs(noteTick - inputTick);
        if (distance <= 2) {
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

    private static void drawPanel(GuiGraphics graphics, Font font, int width, int height, float time, int owner, float intro) {
        boolean own = owner == ownOwner;
        float speed = own ? ownSpeed : opponentSpeed;
        float widthScale = own ? ClientBattleSettings.panelWidthScale() : panelStyles[owner][2];
        float heightScale = own ? ClientBattleSettings.panelHeightScale() : panelStyles[owner][3];
        float panelOpacity = own ? ClientBattleSettings.panelOpacity() : panelStyles[owner][0];
        float laneOpacity = own ? ClientBattleSettings.laneOpacity() : panelStyles[owner][1];
        float noteWidthScale = own ? ClientBattleSettings.noteWidthScale() : panelStyles[owner][4];
        float noteHeightScale = own ? ClientBattleSettings.noteHeightScale() : panelStyles[owner][5];
        int color = own ? ownColor : opponentColor;
        int score = owner == 0 ? leftScore : rightScore;
        int combo = own ? ownCombo : opponentCombo;
        BattlePanelRenderer.draw(graphics, font, width, height, time, trackBpm, activeBeatOrigin(), intro, notes,
                new BattlePanelRenderer.Panel(
                        owner,
                        screenSideForOwner(owner),
                        own,
                        owner == 0 ? leftName : rightName,
                        speed,
                        color,
                        panelOpacity,
                        laneOpacity,
                        widthScale,
                        heightScale,
                        noteWidthScale,
                        noteHeightScale,
                        score,
                        combo,
                        laneFlashes[owner],
                        hitBursts[owner],
                        laneLabels[owner],
                        noteAppearances[owner]
                ));
    }

    /**
     * Keeps each HUD panel on the same visual side as its participant. Battle ownership
     * (P1/P2 and note filtering) stays unchanged; only presentation follows the active
     * camera, so custom cameras may freely look at the stage from the opposite side.
     */
    private static int screenSideForOwner(int owner) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || leftEntityId <= 0 || rightEntityId <= 0) {
            return Mth.clamp(owner, 0, 1);
        }
        Entity first = minecraft.level.getEntity(leftEntityId);
        Entity second = minecraft.level.getEntity(rightEntityId);
        if (first == null || second == null) {
            return Mth.clamp(owner, 0, 1);
        }

        var camera = minecraft.gameRenderer.getMainCamera();
        var cameraPosition = camera.getPosition();
        float yaw = camera.getYRot() * Mth.DEG_TO_RAD;
        // Minecraft yaw 0 looks towards +Z. Its screen-right axis is therefore -X;
        // rotating that axis with the camera gives a stable horizontal projection.
        double rightX = -Mth.cos(yaw);
        double rightZ = -Mth.sin(yaw);
        double firstProjection = (first.getX() - cameraPosition.x) * rightX
                + (first.getZ() - cameraPosition.z) * rightZ;
        double secondProjection = (second.getX() - cameraPosition.x) * rightX
                + (second.getZ() - cameraPosition.z) * rightZ;
        if (Math.abs(firstProjection - secondProjection) < 1.0E-4D) {
            return Mth.clamp(owner, 0, 1);
        }
        boolean firstIsLeft = firstProjection < secondProjection;
        return owner == 0 ? (firstIsLeft ? 0 : 1) : (firstIsLeft ? 1 : 0);
    }

    /**
     * The song card over the battle: what plays, its cover, the time left and the progress, and the count-in under it
     * before the first note. Drawn by BattlePanelRenderer in the Workshop's own panel, fonts and accent; the vanilla
     * font this is handed is no longer needed here.
     */
    private static void drawHeader(GuiGraphics graphics, Font font, int width, float time, float intro) {
        float progress = Mth.clamp(Math.max(0.0F, time) / Math.max(1.0F, durationTicks), 0.0F, 1.0F);
        int remaining = Math.max(0, (durationTicks - Math.max(0, (int) time) + 19) / 20);
        ResourceLocation cover = ClientTrackAssets.cachedAsset(activeTrackId, activePreviewFile)
                .map(path -> ClientPreviewTextures.get(activeTrackId, path))
                .orElse(null);
        int countdown = time < 0.0F ? Math.max(1, (int) Math.ceil(-time / 20.0F)) : 0;
        BattlePanelRenderer.drawHeader(graphics, width, new BattlePanelRenderer.Header(trackTitle, cover, progress,
                remaining, countdown, beatPulse(time), intro));
    }

    /** The standard bars as the player has set them: 36 and 30 units at alpha 185 by default, or none when off. */
    private static void drawStageBars(GuiGraphics graphics, int width, int height) {
        BattleBackdrop.bars(graphics::fill, width, height, ClientBattleSettings.stageBarAlpha());
    }

    private static boolean playMusic(Minecraft minecraft, String trackId, String audioFile, int skipTicks) {
        if (audioFile == null || audioFile.isBlank()) {
            return false;
        }

        Optional<Path> path = ClientTrackAssets.cachedAsset(trackId, audioFile);
        if (path.isEmpty()) {
            return false;
        }

        // Stopping the old stream clears the music clock, the pause stamp with it; a seek made while the game sits in
        // its pause menu must keep the battle clock held, or the clock would run on through the rest of the pause.
        long heldPause = musicPausedForGame ? musicPausedAtNanos : 0L;
        stopMusic(minecraft);
        musicPausedAtNanos = heldPause;
        musicClockStartTick = Math.max(0, skipTicks);
        Path audioPath = path.get();
        ClientAudioAnalyzer.activate(audioPath);
        float pitch = fallbackPlaybackPitch(audioFile, musicPlaybackSpeed);
        music = new BattleMusicSoundInstance(trackId, audioPath.getFileName().toString(), audioPath, Math.max(0, skipTicks),
                pitch, trackVolume * ClientBattleSettings.musicVolume(), ClientBattleController::battleTickForStreamStart,
                reason -> pendingAudioError = text("music_battle.l10n.client.008") + audioFile + "': " + reason);
        minecraft.getSoundManager().play(music);
        // A stream the engine skipped - the game or the song muted when the battle began - is asked for again by
        // retryRefusedMusic once the volume is up; it used to stay silent until the battle ended.
        musicRefused = !minecraft.getSoundManager().isActive(music);
        musicRefusedSilenced = musicRefused && battleMusicSilenced(minecraft);
        return true;
    }

    /** Whether the sound engine would skip the battle's song at the volumes set now. */
    private static boolean battleMusicSilenced(Minecraft minecraft) {
        if (minecraft.options == null) {
            return false;
        }
        return MusicStreamRetry.silenced(
                minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER),
                minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.PLAYERS),
                trackVolume * ClientBattleSettings.musicVolume());
    }

    /** Plays a refused battle song again, at {@code tick}, on the first tick nothing silences it any more. */
    private static void retryRefusedMusic(Minecraft minecraft, int tick) {
        boolean playing = music != null && minecraft.getSoundManager().isActive(music);
        if (MusicStreamRetry.retryNow(musicRefused, musicRefusedSilenced, battleMusicSilenced(minecraft), playing)) {
            playMusic(minecraft, activeTrackId, activeAudioFile, Math.max(0, tick));
        }
    }

    /**
     * The battle tick a song stream that is lined up now will be heard from, asked on the sound thread at the stream's
     * first read: the clock plus the time a stream takes from there to the speakers. While the game sits in its pause
     * menu it is the held tick, with nothing added, because the stream is paused the moment it starts and resumes
     * together with the clock. NaN when there is no shared clock to follow (a battle from a server too old to send
     * one), in which case the stream starts where it was asked to.
     */
    static double battleTickForStreamStart() {
        long epoch = startAtNanos;
        if (!active || epoch == 0L) {
            return Double.NaN;
        }
        long paused = musicPausedAtNanos;
        if (paused > 0L) {
            return (paused - epoch) / 50_000_000.0D;
        }
        return (System.nanoTime() - epoch) / 50_000_000.0D + musicStartLeadTicks;
    }

    private static void tryPlayPendingSpectatorMusic() {
        if (pendingSpectatorMusic == null || active) {
            return;
        }
        SpectatorMusicPayload payload = pendingSpectatorMusic.payload;
        if (spectatorBattleId == null || !spectatorBattleId.equals(payload.battleId())) {
            // A download/decode completion from a superseded seek/session must
            // never become the new controllable sound for the current DJ feed.
            pendingSpectatorMusic = null;
            return;
        }
        if (pendingSpectatorMusic.delayTicks > 0) {
            pendingSpectatorMusic.delayTicks--;
            return;
        }
        Optional<Path> path = ClientTrackAssets.cachedAsset(payload.trackId(), payload.audioFile(), payload.audioSha256());
        if (path.isPresent()) {
            Minecraft minecraft = Minecraft.getInstance();
            Path audioPath = path.get();
            // The visualizer reads decoded spectrum data from the active audio
            // analysis path. Participant playback already activates it; the
            // spectator path must do the same.
            ClientAudioAnalyzer.activate(audioPath);
            // The server always sends the already time-compressed OGG selected for this
            // battle (atempo keeps its musical pitch). Applying speedMultiplier as OpenAL
            // pitch here would speed it up a second time and make spectator audio squeaky.
            // A reconnect may have to download the audio again. Advance the seek
            // position while that transfer is in flight instead of starting from
            // the stale server tick captured when the payload arrived.
            int audioSkipTicks = Math.max(Math.max(0, payload.skipTicks()), Math.max(0, spectatorLocalTicks));
            SoundInstance sound = new SpectatorOggSoundInstance(
                    payload.trackId(),
                    audioPath.getFileName().toString(),
                    audioPath,
                    audioSkipTicks,
                    payload.x(),
                    payload.y(),
                    payload.z(),
                    Math.max(1.0F, payload.radius()),
                    Mth.clamp(payload.volume() * ClientBattleSettings.musicVolume(), 0.0F, 4.0F),
                    1.0F,
                    payload.lowGainDb(), payload.midGainDb(), payload.highGainDb(),
                    payload.dspEnabled(),
                    payload.reverbEnabled(), payload.reverbWet(), payload.reverbRoom(), payload.reverbDamping(),
                    payload.echoEnabled(), payload.echoWet(), payload.echoTimeMs(), payload.echoFeedback(),
                    payload.lowPassEnabled(), payload.lowPassCutoffHz()
            );
            stopSpectatorMusic(minecraft);
            spectatorMusicClockStartTick = audioSkipTicks;
            spectatorMusic = sound;
            spectatorMusicTrackId = payload.trackId();
            spectatorMusicAudioFile = payload.audioFile();
            spectatorMusicAudioHash = payload.audioSha256();
            spectatorPlaybackSpeed = Mth.clamp(payload.speedMultiplier(), 1.0F, 2.0F);
            spectatorMusicAwaitingStart = true;
            spectatorMusicStartWaitTicks = 0;
            minecraft.getSoundManager().play(sound);
            spectatorMusicInactiveTicks = 0;
            spectatorMusicRecoveryCooldown = 30;
            pendingSpectatorMusic = null;
            if (!minecraft.getSoundManager().isActive(sound)) {
                startSpectatorClockWithoutStream(minecraft);
            }
            return;
        }
        if (!ClientTrackAssets.isTransferActive(payload.trackId(), "audio", payload.audioFile())) {
            pendingSpectatorMusic.ticksLeft--;
        }
        if (pendingSpectatorMusic.ticksLeft <= 0) {
            pendingSpectatorMusic = null;
        }
    }

    /**
     * Runs the spectator clock from now for a stream the sound engine has just turned down. SoundEngine.play takes a
     * sound or skips it there and then: with the game's master volume at 0 it skips every sound ("master volume was
     * zero") and gives it no channel, so isActive is false straight after the call and no PlayStreamingSourceEvent
     * will ever come for it. The clock used to wait for that event anyway: a player who had muted the game saw a
     * battle's or a DJ show's effects and notes stand on their seek point for SPECTATOR_STREAM_START_TIMEOUT_TICKS
     * (6 s) and then run that far behind for the rest of the song, without the music, which the timeout also stopped.
     *
     * <p>The clock now starts at the seek point exactly as if the stream had started at once. The silent instance is
     * kept, so a repeated music packet for the same song does not restart it; tickSpectatorMusicRecovery brings the song
     * in where the clock has got to as soon as the volume is turned up, and does not retry while it is still 0.
     */
    private static void startSpectatorClockWithoutStream(Minecraft minecraft) {
        long now = System.nanoTime();
        spectatorMusicStartedAtNanos = now;
        spectatorMusicAwaitingStart = false;
        spectatorMusicStartWaitTicks = 0;
        spectatorMusicRefused = true;
        spectatorMusicRefusedMuted = masterVolumeMuted(minecraft);
        if (spectatorMusicPausedForGame) {
            // The same as a stream that starts while the pause menu is open: the clock waits for the resume.
            spectatorMusicPausedAtNanos = now;
        }
    }

    /** The volume Minecraft gives its listener; at 0 SoundEngine.play skips every sound. */
    private static boolean masterVolumeMuted(Minecraft minecraft) {
        return minecraft.options != null
                && minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER) <= 0.0F;
    }

    /**
     * Server-generated speed variants are already time-compressed and stay at
     * pitch 1. Old servers may still send the original file with a speed vote;
     * pitch then keeps the music clock aligned with the accelerated chart.
     */
    private static float fallbackPlaybackPitch(String audioFile, float speedMultiplier) {
        String normalized = audioFile == null ? "" : audioFile.toLowerCase(java.util.Locale.ROOT);
        boolean encodedVariant = normalized.endsWith("_x15.ogg") || normalized.endsWith("_x2.ogg");
        return encodedVariant ? 1.0F : Mth.clamp(speedMultiplier, 1.0F, 2.0F);
    }

    private static void restartSpectatorMusicAt(int targetTick) {
        SpectatorMusicPayload currentPayload = pendingSpectatorMusic == null ? null : pendingSpectatorMusic.payload;
        SpectatorMusicPayload settingsPayload = currentPayload != null ? currentPayload : lastSpectatorMusicPayload;
        UUID audioBattleId = currentPayload != null ? currentPayload.battleId()
                : lastSpectatorMusicPayload != null ? lastSpectatorMusicPayload.battleId() : spectatorBattleId;
        String trackId = !spectatorMusicTrackId.isBlank()
                ? spectatorMusicTrackId
                : currentPayload == null ? "" : currentPayload.trackId();
        String audioFile = !spectatorMusicAudioFile.isBlank()
                ? spectatorMusicAudioFile
                : currentPayload == null ? "" : currentPayload.audioFile();
        String audioHash = !spectatorMusicAudioHash.isBlank()
                ? spectatorMusicAudioHash
                : currentPayload == null ? "" : currentPayload.audioSha256();
        float volume = currentPayload == null ? spectatorTrackVolume : currentPayload.volume();
        float playbackSpeed = currentPayload == null ? spectatorPlaybackSpeed : currentPayload.speedMultiplier();
        double x = currentPayload == null ? spectatorSoundX : currentPayload.x();
        double y = currentPayload == null ? spectatorSoundY : currentPayload.y();
        double z = currentPayload == null ? spectatorSoundZ : currentPayload.z();
        float radius = currentPayload == null ? spectatorSoundRadius : currentPayload.radius();
        float lowGainDb = currentPayload == null ? spectatorLowGainDb : currentPayload.lowGainDb();
        float midGainDb = currentPayload == null ? spectatorMidGainDb : currentPayload.midGainDb();
        float highGainDb = currentPayload == null ? spectatorHighGainDb : currentPayload.highGainDb();
        boolean dspEnabled = settingsPayload != null && settingsPayload.dspEnabled();
        boolean reverbEnabled = settingsPayload != null && settingsPayload.reverbEnabled();
        float reverbWet = settingsPayload == null ? 0.30F : settingsPayload.reverbWet();
        float reverbRoom = settingsPayload == null ? 0.65F : settingsPayload.reverbRoom();
        float reverbDamping = settingsPayload == null ? 0.45F : settingsPayload.reverbDamping();
        boolean echoEnabled = settingsPayload != null && settingsPayload.echoEnabled();
        float echoWet = settingsPayload == null ? 0.25F : settingsPayload.echoWet();
        float echoTimeMs = settingsPayload == null ? 280.0F : settingsPayload.echoTimeMs();
        float echoFeedback = settingsPayload == null ? 0.35F : settingsPayload.echoFeedback();
        boolean lowPassEnabled = settingsPayload != null && settingsPayload.lowPassEnabled();
        float lowPassCutoffHz = settingsPayload == null ? 12_000.0F : settingsPayload.lowPassCutoffHz();

        pendingSpectatorMusic = null;
        stopSpectatorMusic(Minecraft.getInstance());
        spectatorLocalTicks = targetTick;
        spectatorMusicClockStartTick = targetTick;
        if (trackId.isBlank() || audioFile.isBlank()) {
            return;
        }
        spectatorSoundX = x;
        spectatorSoundY = y;
        spectatorSoundZ = z;
        spectatorSoundRadius = Math.max(1.0F, radius);
        spectatorTrackVolume = volume;
        spectatorLowGainDb = lowGainDb;
        spectatorMidGainDb = midGainDb;
        spectatorHighGainDb = highGainDb;
        pendingSpectatorMusic = new PendingSpectatorMusic(
                new SpectatorMusicPayload(audioBattleId,
                        trackId, audioFile, audioHash, volume, playbackSpeed, x, y, z, radius,
                        lowGainDb, midGainDb, highGainDb,
                        dspEnabled,
                        reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                        echoEnabled, echoWet, echoTimeMs, echoFeedback,
                        lowPassEnabled, lowPassCutoffHz,
                        0, targetTick),
                SPECTATOR_DOWNLOAD_TIMEOUT_TICKS,
                0
        );
        tryPlayPendingSpectatorMusic();
    }

    private static void stopMusic(Minecraft minecraft) {
        if (music != null) {
            SoundInstance stoppedMusic = music;
            if (stoppedMusic instanceof ExternalOggSoundInstance external) {
                external.requestStop();
            }
            minecraft.getSoundManager().stop(stoppedMusic);
            music = null;
        }
        musicRefused = false;
        musicRefusedSilenced = false;
        resetMusicClock();
    }

    private static void stopSpectatorMusic(Minecraft minecraft) {
        finishSpectatorClockHold(System.nanoTime());
        spectatorSongDrift.clear();
        if (spectatorMusic != null) {
            SoundInstance stoppedMusic = spectatorMusic;
            if (stoppedMusic instanceof ExternalOggSoundInstance external) {
                external.requestStop();
            }
            minecraft.getSoundManager().stop(stoppedMusic);
            spectatorMusic = null;
        }
        spectatorMusicStartedAtNanos = 0L;
        spectatorMusicClockStartTick = 0;
        spectatorMusicAwaitingStart = false;
        spectatorMusicStartWaitTicks = 0;
        spectatorMusicTrackId = "";
        spectatorMusicAudioFile = "";
        spectatorMusicAudioHash = "";
        spectatorMusicPausedAtNanos = 0L;
        spectatorMusicPausedForGame = false;
        spectatorMusicInactiveTicks = 0;
        spectatorMusicRecoveryCooldown = 0;
        spectatorMusicRefused = false;
        spectatorMusicRefusedMuted = false;
    }

    /** Restarts a spectator stream if the audio engine drops it after a client stall or camera switch. */
    private static void tickSpectatorMusicRecovery(Minecraft minecraft) {
        if (spectatorMusicRecoveryCooldown > 0) {
            spectatorMusicRecoveryCooldown--;
        }
        if (spectatorMusicAwaitingStart) {
            spectatorMusicInactiveTicks = 0;
            return;
        }
        if (spectatorBattleId == null || minecraft.player == null || minecraft.isPaused()
                || !spectatorInRange() || spectatorLocalTicks > spectatorDurationTicks
                || pendingSpectatorMusic != null || spectatorMusic == null) {
            spectatorMusicInactiveTicks = 0;
            return;
        }
        if (minecraft.getSoundManager().isActive(spectatorMusic)) {
            spectatorMusicInactiveTicks = 0;
            return;
        }
        if (spectatorMusicRefused) {
            // A stream the engine refused is asked for again only once the reason has gone. Every retry while the game
            // is still muted would be refused in turn and would re-seek the clock each time; a refusal for any other
            // reason was never retried, and a later seek or song asks again anyway. Once the volume is up the song comes
            // in at once, without the eight ticks of grace a dropped stream gets, at the place the clock has reached.
            spectatorMusicInactiveTicks = 0;
            if (!spectatorMusicRefusedMuted || masterVolumeMuted(minecraft)) {
                return;
            }
        } else {
            spectatorMusicInactiveTicks++;
            if (spectatorMusicInactiveTicks < 8 || spectatorMusicRecoveryCooldown > 0) {
                return;
            }
        }
        int targetTick = Math.max(0, Math.min(spectatorDurationTicks, Mth.floor(spectatorVisualTick(0.0F))));
        SpectatorMusicPayload source = lastSpectatorMusicPayload;
        if (source != null) {
            spectatorSoundX = source.x();
            spectatorSoundY = source.y();
            spectatorSoundZ = source.z();
            spectatorSoundRadius = Math.max(1.0F, source.radius());
        }
        restartSpectatorMusicAt(targetTick);
        spectatorMusicRecoveryCooldown = 40;
        spectatorMusicInactiveTicks = 0;
    }

    /** Apply changed hardware latency to the local stream without changing server transport. */
    public static void refreshAudioCalibration() {
        Minecraft minecraft = Minecraft.getInstance();
        if (active && music != null && musicStarted) {
            int targetTick = Math.max(0, Mth.floor(activeVisualTick(0.0F)));
            musicStarted = playMusic(minecraft, activeTrackId, activeAudioFile, targetTick);
        } else if (spectatorBattleId != null && spectatorMusic != null) {
            restartSpectatorMusicAt(Math.max(0, Mth.floor(spectatorVisualTick(0.0F))));
        }
    }

    public static void refreshMusicVolume() {
        float setting = ClientBattleSettings.musicVolume();
        if (music instanceof ExternalOggSoundInstance external) {
            external.setVolume(Mth.clamp(trackVolume * setting, 0.0F, 4.0F));
        }
        if (spectatorMusic instanceof SpectatorOggSoundInstance spectatorSound) {
            spectatorSound.setTargetBaseVolume(Mth.clamp(spectatorTrackVolume * setting, 0.0F, 4.0F));
        } else if (spectatorMusic instanceof ExternalOggSoundInstance external) {
            external.setVolume(Mth.clamp(spectatorTrackVolume * setting, 0.0F, 4.0F));
        }
    }

    public static boolean shouldSuppressVanillaMusic() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MusicBattleCalibrationScreen) {
            return true;
        }
        if (minecraft.options == null
                || ClientBattleSettings.musicVolume() <= 0.0001F
                || minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER) <= 0.0001F
                || minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.PLAYERS) <= 0.0001F) {
            return false;
        }
        if (active && battleId != null && music != null
                && music.getVolume() > 0.0001F
                && minecraft.getSoundManager().isActive(music)) {
            return true;
        }
        return spectatorBattleId != null
                && spectatorLocalTicks <= spectatorDurationTicks
                && spectatorMusic != null
                && spectatorInRange()
                && spectatorMusic.getVolume() > 0.0001F
                && minecraft.getSoundManager().isActive(spectatorMusic);
    }

    private static boolean spectatorInRange() {
        if (spectatorBattleId == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        double dx = minecraft.player.getX() - spectatorSoundX;
        double dy = minecraft.player.getY() - spectatorSoundY;
        double dz = minecraft.player.getZ() - spectatorSoundZ;
        return dx * dx + dy * dy + dz * dz <= spectatorSoundRadius * spectatorSoundRadius;
    }

    private static boolean spectatorEffectsTargetVisible() {
        return ClientBattleSettings.spectatorEffects() && spectatorInRange();
    }

    private static void tickSpectatorFxVisibility() {
        spectatorFxVisibilityPrevious = spectatorFxVisibility;
        float target = spectatorEffectsTargetVisible() ? 1.0F : 0.0F;
        // Enter quickly, but give every effect roughly 0.65 seconds to disappear when
        // the spectator walks out of the audible battle radius.
        float step = target > spectatorFxVisibility ? 0.16F : 0.075F;
        spectatorFxVisibility = Mth.approach(spectatorFxVisibility, target, step);
    }

    private static float spectatorFxVisibility(float partialTick) {
        if (authoringDjFx()) {
            // While a DJ is dialling an effect in, the stack that is already running is their own previous version
            // of it, and it showed through everything the editor drew: the old effect and the new one at once,
            // including the parts no background can cover, like the camera shake and the glow on entities. For the
            // person editing, and only for them, the running stack is silent until they close the editor.
            return 0.0F;
        }
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
                spectatorFxVisibilityPrevious, spectatorFxVisibility);
    }

    /** True while this player has one of the DJ effect editors open. */
    public static boolean authoringDjFx() {
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof DjVisualFxScreen || screen instanceof DjPlaylistEffectsScreen) {
            return true;
        }
        // The shared effect editor also edits a beatmap's effects. Its World background shows the world around the
        // player, so a booth playing nearby would draw its stack under the effect being edited: silent there too.
        return screen instanceof TrackEffectEditScreen;
    }

    public static void handleSpectatorCameraKey() {
        Minecraft minecraft = Minecraft.getInstance();
        while (MusicBattleKeys.TOGGLE_SPECTATOR_CAMERA.consumeClick()) {
            if (minecraft.screen != null || active) {
                continue;
            }
            if (spectatorCameraRequested) {
                clearSpectatorCameraRequest(minecraft);
                showSpectatorCameraMessage(Component.translatable(
                        "message.music_battle.spectator_camera.free"));
                continue;
            }
            if (spectatorBattleId == null || !spectatorInRange()) {
                showSpectatorCameraMessage(Component.translatable(
                        "message.music_battle.spectator_camera.too_far"));
                continue;
            }

            if (standaloneDjPlayback) {
                if (!djAudienceCameraAvailable || !spectatorCameraViewPositionValid) {
                    showSpectatorCameraMessage(Component.translatable(
                            "message.music_battle.spectator_camera.dj_unavailable"));
                    continue;
                }
                if (!djAudienceCameraChunkLoaded(minecraft)) {
                    showSpectatorCameraMessage(Component.translatable(
                            "message.music_battle.spectator_camera.chunk_unavailable"));
                    continue;
                }
                spectatorCameraEnabled = true;
                spectatorCameraViewPosition = djAudienceCameraTargetPosition;
                spectatorCameraYaw = djAudienceCameraTargetYaw;
                spectatorCameraPitch = djAudienceCameraTargetPitch;
                spectatorCameraFov = djAudienceCameraTargetFov;
                snapSpectatorCameraInterpolation();
                spectatorCameraRequested = true;
                keepPlayerAsCameraEntity(minecraft);
                showSpectatorCameraMessage(Component.translatable(
                        "message.music_battle.spectator_camera.dj"));
                continue;
            }

            if (!prepareBattleSpectatorCamera(minecraft)) {
                showSpectatorCameraMessage(Component.translatable(
                        "message.music_battle.spectator_camera.loading"));
                continue;
            }
            spectatorCameraRequested = true;
            spectatorCameraEnabled = true;
            snapSpectatorCameraInterpolation();
            keepPlayerAsCameraEntity(minecraft);
            showSpectatorCameraMessage(Component.translatable(
                    "message.music_battle.spectator_camera.battle"));
        }
    }

    private static void tickSpectatorCamera(Minecraft minecraft) {
        if (!spectatorCameraRequested) {
            if (spectatorCameraEnabled) {
                suspendSpectatorCamera(minecraft);
            }
            return;
        }
        if (spectatorBattleId == null || minecraft.level == null) {
            clearSpectatorCameraRequest(minecraft);
            return;
        }
        if (!spectatorInRange()) {
            if (spectatorCameraEnabled) {
                suspendSpectatorCamera(minecraft);
                showSpectatorCameraMessage(Component.translatable(
                        "message.music_battle.spectator_camera.free"));
            }
            return;
        }

        if (standaloneDjPlayback) {
            if (!djAudienceCameraAvailable) {
                if (spectatorCameraEnabled) {
                    suspendSpectatorCamera(minecraft);
                    showSpectatorCameraMessage(Component.translatable(
                            "message.music_battle.spectator_camera.unavailable"));
                }
                return;
            }
            if (!djAudienceCameraChunkLoaded(minecraft)) {
                if (spectatorCameraEnabled) {
                    suspendSpectatorCamera(minecraft);
                    showSpectatorCameraMessage(Component.translatable(
                            "message.music_battle.spectator_camera.chunk_unavailable"));
                }
                return;
            }
            if (!spectatorCameraEnabled) {
                spectatorCameraEnabled = true;
                spectatorCameraViewPosition = djAudienceCameraTargetPosition;
                spectatorCameraViewPositionValid = true;
                spectatorCameraYaw = djAudienceCameraTargetYaw;
                spectatorCameraPitch = djAudienceCameraTargetPitch;
                spectatorCameraFov = djAudienceCameraTargetFov;
                snapSpectatorCameraInterpolation();
            }
            keepPlayerAsCameraEntity(minecraft);
            spectatorCameraViewPosition = lerpPosition(
                    spectatorCameraViewPosition, djAudienceCameraTargetPosition, 0.36D);
            spectatorCameraViewPositionValid = true;
            spectatorCameraYaw = Mth.rotLerp(0.42F, spectatorCameraYaw, djAudienceCameraTargetYaw);
            spectatorCameraPitch = Mth.lerp(0.42F, spectatorCameraPitch, djAudienceCameraTargetPitch);
            spectatorCameraFov = Mth.lerp(0.36F, spectatorCameraFov, djAudienceCameraTargetFov);
            return;
        }

        if (!spectatorCameraEnabled) {
            if (!prepareBattleSpectatorCamera(minecraft)) {
                return;
            }
            spectatorCameraEnabled = true;
            snapSpectatorCameraInterpolation();
        }
        keepPlayerAsCameraEntity(minecraft);

        boolean synced = (syncedCameraAvailable || syncedCameraAutomatic)
                && spectatorBattleId.equals(syncedCameraBattleId)
                && spectatorCameraEntityId == syncedCameraEntityId;
        if (!synced) {
            Entity camera = spectatorCameraEntityId > 0
                    ? minecraft.level.getEntity(spectatorCameraEntityId) : null;
            if (camera == null) {
                if (spectatorCameraEnabled) {
                    suspendSpectatorCamera(minecraft);
                    showSpectatorCameraMessage(Component.translatable(
                            "message.music_battle.spectator_camera.unavailable"));
                }
                return;
            }
            // The replicated entity stores ArmorStand feet; detached spectator
            // rendering needs the same optical point seen by active players.
            Vec3 target = camera.getEyePosition();
            spectatorCameraViewPosition = spectatorCameraViewPositionValid
                    ? lerpPosition(spectatorCameraViewPosition, target, 0.42D) : target;
            spectatorCameraViewPositionValid = true;
        }
    }

    private static boolean prepareBattleSpectatorCamera(Minecraft minecraft) {
        if (minecraft.level == null || spectatorCameraEntityId <= 0) {
            return false;
        }
        boolean synced = (syncedCameraAvailable || syncedCameraAutomatic)
                && spectatorBattleId != null
                && spectatorBattleId.equals(syncedCameraBattleId)
                && spectatorCameraEntityId == syncedCameraEntityId;
        if (synced) {
            spectatorCameraViewPosition = new Vec3(syncedCameraX, syncedCameraY, syncedCameraZ);
            spectatorCameraViewPositionValid = true;
            spectatorCameraYaw = syncedCameraYaw;
            spectatorCameraPitch = syncedCameraPitch;
            spectatorCameraFov = syncedCameraFov;
            return true;
        }
        Entity camera = minecraft.level.getEntity(spectatorCameraEntityId);
        if (camera == null) {
            return false;
        }
        spectatorCameraViewPosition = camera.getEyePosition();
        spectatorCameraViewPositionValid = true;
        return true;
    }

    /** Captures the preceding client-tick transform for render-frame interpolation. */
    private static void captureSpectatorCameraInterpolation() {
        if (!spectatorCameraEnabled || !spectatorCameraViewPositionValid) {
            return;
        }
        spectatorCameraPreviousViewPosition = spectatorCameraViewPosition;
        spectatorCameraPreviousYaw = spectatorCameraYaw;
        spectatorCameraPreviousPitch = spectatorCameraPitch;
        spectatorCameraPreviousFov = spectatorCameraFov;
    }

    /** Makes a deliberate camera cut instantaneous instead of interpolating from an old shot. */
    private static void snapSpectatorCameraInterpolation() {
        spectatorCameraPreviousViewPosition = spectatorCameraViewPosition;
        spectatorCameraPreviousYaw = spectatorCameraYaw;
        spectatorCameraPreviousPitch = spectatorCameraPitch;
        spectatorCameraPreviousFov = spectatorCameraFov;
    }

    private static Vec3 lerpPosition(Vec3 current, Vec3 target, double amount) {
        Vec3 result = new Vec3(
                Mth.lerp(amount, current.x, target.x),
                Mth.lerp(amount, current.y, target.y),
                Mth.lerp(amount, current.z, target.z));
        return result.distanceToSqr(target) < 1.0E-8D ? target : result;
    }

    private static void keepPlayerAsCameraEntity(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.getCameraEntity() != minecraft.player) {
            minecraft.setCameraEntity(minecraft.player);
        }
    }

    private static boolean djAudienceCameraChunkLoaded(Minecraft minecraft) {
        return minecraft.level != null
                && minecraft.level.hasChunkAt(BlockPos.containing(djAudienceCameraTargetPosition));
    }

    private static void tickBattleCameraSync(Minecraft minecraft) {
        if (!syncedCameraAvailable && !syncedCameraAutomatic) {
            return;
        }
        boolean activeMatch = active && battleId != null && battleId.equals(syncedCameraBattleId)
                && cameraEntityId == syncedCameraEntityId;
        boolean spectatorMatch = !active && spectatorBattleId != null && spectatorBattleId.equals(syncedCameraBattleId)
                && spectatorCameraEntityId == syncedCameraEntityId;
        if (!activeMatch && !spectatorMatch) {
            return;
        }

        // The automatic shot sends a frame every tick and moves smoothly, so it is followed closely; a camera block's
        // edits come seldom and are eased in more gently. Either way every rendered frame blends between two ticks.
        float follow = syncedCameraAutomatic ? AUTOMATIC_SHOT_FOLLOW : BLOCK_SHOT_FOLLOW;
        if (activeMatch) {
            activeCameraInterpolation.approach(syncedCameraX, syncedCameraY, syncedCameraZ,
                    syncedCameraYaw, syncedCameraPitch, syncedCameraFov, follow, follow, 0.36F);
            BattleCameraInterpolation.Sample current = activeCameraInterpolation.sample(1.0F);
            cameraYaw = current.yaw();
            cameraPitch = current.pitch();
            cameraFov = current.fov();
        } else {
            spectatorCameraYaw = Mth.rotLerp(follow, spectatorCameraYaw, syncedCameraYaw);
            spectatorCameraPitch = Mth.lerp(follow, spectatorCameraPitch, syncedCameraPitch);
            // Zero is "the viewer's own" and must not be blended towards: halfway there is a fish-eye.
            spectatorCameraFov = syncedCameraAutomatic ? 0.0F : Mth.lerp(0.36F, spectatorCameraFov, syncedCameraFov);
            Vec3 target = new Vec3(syncedCameraX, syncedCameraY, syncedCameraZ);
            spectatorCameraViewPosition = spectatorCameraEnabled && spectatorCameraViewPositionValid
                    ? lerpPosition(spectatorCameraViewPosition, target, follow) : target;
            spectatorCameraViewPositionValid = true;
            // The render camera is detached in CameraMixin while the camera
            // entity remains the local player, so walking and Blocky Music's
            // own distance checks continue to use the spectator's real body.
            return;
        }

        if (minecraft.level == null) {
            return;
        }
        Entity camera = minecraft.level.getEntity(syncedCameraEntityId);
        if (camera == null) {
            return;
        }
        // Camera sync coordinates are optical. Active participants still view
        // through the temporary ArmorStand, so its feet must stay one eye
        // height below that point. Detached spectators consume the same payload
        // directly in the branch above and deliberately do not subtract it.
        BattleCameraInterpolation.Sample current = activeCameraInterpolation.sample(1.0F);
        Vec3 cameraEntityTarget = new Vec3(current.x(), current.y() - camera.getEyeHeight(), current.z());
        double x = cameraEntityTarget.x;
        double y = cameraEntityTarget.y;
        double z = cameraEntityTarget.z;
        camera.setPos(x, y, z);
        camera.setYRot(cameraYaw);
        camera.setXRot(cameraPitch);
    }

    private static void clearBattleCameraSync() {
        syncedCameraBattleId = null;
        syncedCameraEntityId = 0;
        syncedCameraX = 0.0D;
        syncedCameraY = 0.0D;
        syncedCameraZ = 0.0D;
        syncedCameraYaw = 0.0F;
        syncedCameraPitch = 0.0F;
        syncedCameraFov = 0.0F;
        syncedCameraRevision = -1L;
        syncedCameraAvailable = false;
        syncedCameraAutomatic = false;
        activeCameraInterpolation.clear();
    }

    private static void clearDjAudienceCamera() {
        djAudienceCameraPos = BlockPos.ZERO;
        djAudienceCameraTargetPosition = Vec3.ZERO;
        djAudienceCameraTargetYaw = 0.0F;
        djAudienceCameraTargetPitch = 0.0F;
        djAudienceCameraTargetFov = 70.0F;
        djAudienceCameraRevision = -1L;
        djAudienceCameraAvailable = false;
    }

    /** Temporarily stops the remote shot but preserves the viewer's toggle. */
    private static void suspendSpectatorCamera(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.getCameraEntity() != minecraft.player) {
            minecraft.setCameraEntity(minecraft.player);
        }
        spectatorCameraEnabled = false;
    }

    /** Fully leaves camera mode, including its auto-resume intent. */
    private static void clearSpectatorCameraRequest(Minecraft minecraft) {
        spectatorCameraRequested = false;
        suspendSpectatorCamera(minecraft);
    }

    private static void showSpectatorCameraMessage(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "message.music_battle.spectator_camera.prefix", message), true);
        }
    }

    /** Position used by CameraMixin without replacing Minecraft's camera entity. */
    public static Vec3 spectatorCameraPositionOverride(float partialTick) {
        if (active || !spectatorCameraEnabled || !spectatorCameraViewPositionValid
                || CameraEditorClient.isActive() || DjCameraPreviewRenderer.isRenderingPreview()) {
            return null;
        }
        return lerpPosition(spectatorCameraPreviousViewPosition, spectatorCameraViewPosition,
                Mth.clamp(partialTick, 0.0F, 1.0F));
    }

    /** Optical position for active players, interpolated every rendered frame. */
    public static Vec3 activeBattleCameraPositionOverride(float partialTick) {
        if (!active || !syncedCameraAvailable && !syncedCameraAutomatic || !activeCameraInterpolation.valid()
                || CameraEditorClient.isActive() || DjCameraPreviewRenderer.isRenderingPreview()) {
            return null;
        }
        BattleCameraInterpolation.Sample sample = activeCameraInterpolation.sample(partialTick);
        return new Vec3(sample.x(), sample.y(), sample.z());
    }

    public static void onStreamingSoundStarted(SoundInstance startedSound, Channel channel) {
        if (startedSound instanceof ExternalOggSoundInstance external
                && (external.isStopped()
                || startedSound instanceof SpectatorOggSoundInstance && startedSound != spectatorMusic)) {
            // SoundEngine starts streaming futures even if stop(instance) won
            // the race before decoding completed. Stop that late channel while
            // it is still observable through PlayStreamingSourceEvent.
            channel.stop();
            return;
        }
        long now = System.nanoTime();
        if (startedSound == music) {
            // Before the start stamp, which is what the drift check reads first.
            musicStreamStartTick = startedSound instanceof BattleMusicSoundInstance battleMusic
                    ? battleMusic.startTick() : Double.NaN;
            if (startedSound instanceof BattleMusicSoundInstance battleMusic && battleMusic.alignedAtNanos() > 0L) {
                // This event comes straight after the source starts, so the time since the stream was lined up is
                // how long this machine took to get a stream to the speakers; the next one is lined up that far ahead.
                musicStartLeadTicks = MusicStartAlignment.nextStartLead(musicStartLeadTicks,
                        (now - battleMusic.alignedAtNanos()) / 50_000_000.0D);
            }
            musicStartedAtNanos = now;
            if (musicPausedForGame) {
                // A stream that finished opening while the game sits in the pause menu must not play on into it,
                // and the clock has to stay held at the instant the pause began - not at this one, or the resume
                // gives back only the part of the pause that came after the file opened. The spectator stream
                // below has always done this; the battle's own stream clearing the stamp here is what let the
                // chart walk away from the server while nobody was playing.
                if (musicPausedAtNanos <= 0L) {
                    musicPausedAtNanos = now;
                }
                // The stream really begins at the resume, and the resume moves this origin on by the whole pause,
                // so the origin has to start from the pause, not from the moment the file finished opening. Stamped
                // with "now", it came out late by however long the open took, and the chart ran that far behind the
                // music until the drift corrector re-seeked.
                musicStartedAtNanos = musicPausedAtNanos;
                channel.pause();
            } else {
                musicPausedAtNanos = 0L;
            }
        }
        if (startedSound == spectatorMusic) {
            spectatorMusicStartedAtNanos = now;
            spectatorMusicAwaitingStart = false;
            spectatorMusicStartWaitTicks = 0;
            if (spectatorMusicPausedForGame) {
                spectatorMusicPausedAtNanos = now;
                channel.pause();
            }
        }
    }

    /**
     * Where this battle is, in ticks, on the clock both players share. Negative during the count-in, by design.
     *
     * A battle without an epoch - a replay of an older server, or a state this build no longer produces - falls
     * back to the way it worked before: the audio's own start, and then the local counter.
     */
    private static float activeVisualTick(float partialTick) {
        long epoch = startAtNanos;
        if (epoch != 0L) {
            long now = musicPausedAtNanos > 0L ? musicPausedAtNanos : System.nanoTime();
            long holdUntil = clockHoldUntilNanos;
            if (holdUntil != 0L && musicPausedAtNanos > 0L) {
                // A short hold goes on by itself once it has run out, whenever the next tick or frame lets it go.
                long wall = System.nanoTime();
                if (wall >= holdUntil) {
                    now = wall - (holdUntil - musicPausedAtNanos);
                }
            }
            return (now - epoch) / 50_000_000.0F;
        }
        long startedAt = musicStartedAtNanos;
        if (startedAt <= 0L) {
            if (musicStarted && activeAudioFile != null && !activeAudioFile.isBlank() && localTicks >= 0) {
                return musicClockStartTick;
            }
            return localTicks + Mth.clamp(partialTick, 0.0F, 1.0F);
        }
        long now = musicPausedAtNanos > 0L ? musicPausedAtNanos : System.nanoTime();
        return musicClockStartTick + Math.max(0L, now - startedAt) / 50_000_000.0F;
    }

    /** Where the audio actually is, in ticks, so it can be compared with the battle clock and re-seeked. */
    private static float audioTick() {
        long startedAt = musicStartedAtNanos;
        if (startedAt <= 0L) {
            return Float.NaN;
        }
        long now = musicPausedAtNanos > 0L ? musicPausedAtNanos : System.nanoTime();
        double start = musicStreamStartTick;
        return (float) ((Double.isFinite(start) ? start : musicClockStartTick) + Math.max(0L, now - startedAt) / 50_000_000.0D);
    }

    /** A monitor shares the listener clock only for the same live DJ playback identity. */
    public static float djCameraVisualTick(UUID playbackId, float fallbackTicks, float partialTick) {
        return standaloneDjPlayback && playbackId != null && playbackId.equals(spectatorBattleId)
                ? spectatorVisualTick(partialTick) : fallbackTicks;
    }

    private static float spectatorVisualTick(float partialTick) {
        long startedAt = spectatorMusicStartedAtNanos;
        if (startedAt <= 0L) {
            if (spectatorMusicAwaitingStart && !spectatorMusicStartFreezeDisabled) {
                // Once a stream has been queued, hold the visual clock on its exact seek point
                // until OpenAL actually starts it. Advancing here used to jump FX backwards when
                // asynchronous decoding completed after a seek or rapid track change.
                return spectatorMusicClockStartTick;
            }
            return spectatorLocalTicks + Mth.clamp(partialTick, 0.0F, 1.0F);
        }
        long pausedAt = spectatorMusicPausedAtNanos;
        long now = pausedAt > 0L ? pausedAt : System.nanoTime();
        long holdUntil = spectatorClockHoldUntilNanos;
        if (holdUntil != 0L && pausedAt > 0L) {
            // A short hold goes on by itself once it has run out, whenever the next tick or frame lets it go.
            long wall = System.nanoTime();
            if (wall >= holdUntil) {
                now = wall - (holdUntil - pausedAt);
            }
        }
        return spectatorMusicClockStartTick + Math.max(0L, now - startedAt) / 50_000_000.0F;
    }

    private static ClientTrackEffects.EffectState sampleSpectatorEffects(float partial) {
        return djTransitions.attach(ClientTrackEffects.sample(spectatorEffects, spectatorVisualTick(partial),
                spectatorTrackBpm, spectatorPlaybackSpeed, spectatorFxVisibility(partial), spectatorBeatOrigin()),
                System.nanoTime());
    }

    private static void resetMusicClock() {
        finishClockHold(System.nanoTime());
        songDrift.clear();
        BattleSongPosition.clear();
        musicStartedAtNanos = 0L;
        musicPausedAtNanos = 0L;
        musicClockStartTick = 0;
        musicStreamStartTick = Double.NaN;
    }

    private static void tickSpectatorBattle() {
        if (spectatorBattleId == null) {
            return;
        }
        if (spectatorMusicAwaitingStart && spectatorMusicStartedAtNanos <= 0L
                && ++spectatorMusicStartWaitTicks > SPECTATOR_STREAM_START_TIMEOUT_TICKS) {
            // A failed decoder/OpenAL future must not freeze the whole spectator session forever.
            // Keep the failure scoped to this playback id; the next real seek/track can freeze
            // normally again, while a same-id retry is allowed to catch up without rewinding FX.
            spectatorMusicStartFreezeDisabled = true;
            stopSpectatorMusic(Minecraft.getInstance());
        }
        spectatorLocalTicks = spectatorMusicStartedAtNanos > 0L
                ? Math.max(0, Mth.floor(spectatorVisualTick(0.0F)))
                : spectatorMusicAwaitingStart && !spectatorMusicStartFreezeDisabled
                ? Math.max(0, spectatorMusicClockStartTick)
                : spectatorLocalTicks + 1;
        showDueActions(spectatorHeldActions, true, spectatorVisualTick(0.0F));
        flushPendingNoteActions(spectatorNotes, spectatorLocalTicks, spectatorLaneFlashes, spectatorHitBursts, true);
        flushPendingRemoteStates(spectatorNotes, true);
        sanitizeAnimationHeldMasks(spectatorNotes, spectatorAnimationHeldMask, spectatorLocalTicks);
        for (ClientNote note : spectatorNotes) {
            if (note.started && note.durationTicks > 0 && spectatorLocalTicks > note.tick + note.durationTicks + HIT_WINDOW_TICKS) {
                note.resolved = true;
            }
        }
        for (int owner = 0; owner < 2; owner++) {
            for (int lane = 0; lane < 4; lane++) {
                if (spectatorTimedLaneFlashes.timed(owner, lane)) {
                    continue;
                }
                if (spectatorLaneFlashes[owner][lane] > 0) {
                    spectatorLaneFlashes[owner][lane]--;
                }
                if (spectatorHitBursts[owner][lane] > 0) {
                    spectatorHitBursts[owner][lane]--;
                }
            }
        }
        spectatorTimedLaneFlashes.apply(spectatorVisualTick(0.0F), spectatorLaneFlashes, spectatorHitBursts);
        if (spectatorLocalTicks > (djReleaseEndTick > 0 ? djReleaseEndTick : spectatorDurationTicks + 220)) {
            clearSpectatorBattle();
            stopSpectatorMusic(Minecraft.getInstance());
        }
    }

    private static void clearSpectatorBattle() {
        djTransitions.clear();
        djReleaseEndTick = 0;
        clearSpectatorCameraRequest(Minecraft.getInstance());
        clearBattleCameraSync();
        clearDjAudienceCamera();
        resetAnimationLayers();
        spectatorBattleId = null;
        lastSpectatorMusicPayload = null;
        spectatorMusicStartFreezeDisabled = false;
        finishSpectatorClockHold(System.nanoTime());
        spectatorSongDrift.clear();
        spectatorMusicPausedAtNanos = 0L;
        spectatorMusicPausedForGame = false;
        spectatorTrackBpm = 120;
        spectatorBeatOffsetMs = 0;
        spectatorNotes.clear();
        spectatorHeldActions.clear();
        spectatorTimedLaneFlashes.clear();
        spectatorEffects = List.of();
        clearBattleAudience();
        spectatorLeftEntityId = 0;
        spectatorRightEntityId = 0;
        spectatorCameraEntityId = 0;
        spectatorCameraYaw = 0.0F;
        spectatorCameraPitch = 0.0F;
        spectatorCameraFov = 0.0F;
        spectatorCameraViewPosition = Vec3.ZERO;
        spectatorCameraPreviousViewPosition = Vec3.ZERO;
        spectatorCameraViewPositionValid = false;
        spectatorCameraPreviousYaw = 0.0F;
        spectatorCameraPreviousPitch = 0.0F;
        spectatorCameraPreviousFov = 0.0F;
        standaloneDjPlayback = false;
        spectatorFxVisibility = 0.0F;
        spectatorFxVisibilityPrevious = 0.0F;
        spectatorSoundX = 0.0D;
        spectatorSoundY = 0.0D;
        spectatorSoundZ = 0.0D;
        spectatorTrackVolume = 1.0F;
        spectatorLowGainDb = 0.0F;
        spectatorMidGainDb = 0.0F;
        spectatorHighGainDb = 0.0F;
        spectatorPlaybackSpeed = 1.0F;
        spectatorLeftScore = 0;
        spectatorRightScore = 0;
        spectatorCombo[0] = 0;
        spectatorCombo[1] = 0;
        for (int owner = 0; owner < 2; owner++) {
            spectatorAnimationTriggerLane[owner] = -1;
            spectatorAnimationHeldMask[owner] = 0;
            spectatorAnimationInputHeldMask[owner] = 0;
            spectatorAnimationTriggerTick[owner] = -Float.MAX_VALUE;
            spectatorAnimationJudgedLane[owner] = -1;
            spectatorAnimationJudgedTick[owner] = -Float.MAX_VALUE;
            for (int lane = 0; lane < 4; lane++) {
                spectatorHitBursts[owner][lane] = 0;
            }
        }
    }

    /** A press: a key going down, the player's own or another player's. */
    private static void triggerPlayerAnimation(boolean spectator, int owner, int lane) {
        triggerPlayerAnimation(spectator, owner, lane, Float.NaN);
    }

    private static void triggerPlayerAnimation(boolean spectator, int owner, int lane, float at) {
        if (spectator) {
            trigger(spectatorAnimationTriggerSerial, spectatorAnimationTriggerLane, spectatorAnimationTriggerTick,
                    true, owner, lane, at);
        } else {
            trigger(animationTriggerSerial, animationTriggerLane, animationTriggerTick, false, owner, lane, at);
        }
    }

    /**
     * A hit judged for this side. A mob has no keys, so its judgement is its press. A side with keys has already
     * pressed, when its key went down: the player's own early hit is let through here up to 6 ticks after its key
     * (more with a negative input offset), and the server judges an opponent's hit before it passes on the key,
     * because the client sends the judgement first. Counted as a press of its own, it punched the body twice and
     * could take it back to a lane already left, so it goes to the animation as a judgement, apart from the presses.
     */
    private static void triggerJudgementAnimation(boolean spectator, int owner, int lane) {
        triggerJudgementAnimation(spectator, owner, lane, Float.NaN);
    }

    /** With {@code at}, the battle tick the hit belongs to when it was held for that tick; NaN for now. */
    private static void triggerJudgementAnimation(boolean spectator, int owner, int lane, float at) {
        if (!keyedSide(spectator, owner)) {
            triggerPlayerAnimation(spectator, owner, lane, at);
        } else if (spectator) {
            trigger(spectatorAnimationJudgedSerial, spectatorAnimationJudgedLane, spectatorAnimationJudgedTick,
                    true, owner, lane, at);
        } else {
            trigger(animationJudgedSerial, animationJudgedLane, animationJudgedTick, false, owner, lane, at);
        }
    }

    /**
     * Counts a trigger and stamps it with the battle clock, or with {@code at} when the trigger was held for that tick
     * and is shown in the first frame after it: the pose and its punch then count from the note's own tick, so the
     * body is in phase with the note however late in the frame the tick fell.
     */
    private static void trigger(int[] serials, int[] lanes, float[] ticks, boolean spectator, int owner, int lane,
                                float at) {
        int safeOwner = Mth.clamp(owner, 0, 1);
        serials[safeOwner]++;
        lanes[safeOwner] = Mth.clamp(lane, 0, 3);
        float now = spectator ? spectatorVisualTick(0.0F) : activeVisualTick(0.0F);
        ticks[safeOwner] = Float.isFinite(at) ? Math.min(at, now) : now;
    }

    /**
     * Whether this side plays with keys: our own side, or a player. Only a player sends key presses, and the server
     * plays every other participant itself (BattleManager's ParticipantState.ai). An entity this client cannot see
     * counts as keyless, the side whose hits then show twice rather than never; so does every side when there is no
     * game or world to look in (a check run without Minecraft, or a frame drawn while leaving a world).
     */
    private static boolean keyedSide(boolean spectator, int owner) {
        if (!spectator && owner == ownOwner) {
            return true;
        }
        int entityId = spectator
                ? owner == 0 ? spectatorLeftEntityId : spectatorRightEntityId
                : owner == 0 ? leftEntityId : rightEntityId;
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.level != null && minecraft.level.getEntity(entityId) instanceof Player;
    }

    private static void setPlayerAnimationHeld(boolean spectator, int owner, int lane, boolean held) {
        int safeOwner = Mth.clamp(owner, 0, 1);
        int[] heldMasks = spectator ? spectatorAnimationHeldMask : animationHeldMask;
        heldMasks[safeOwner] = BattleAnimationInput.updateHeldMask(heldMasks[safeOwner], lane, held);
    }

    private static void setPlayerAnimationInputHeld(boolean spectator, int owner, int lane, boolean held) {
        int safeOwner = Mth.clamp(owner, 0, 1);
        int[] heldMasks = spectator ? spectatorAnimationInputHeldMask : animationInputHeldMask;
        heldMasks[safeOwner] = BattleAnimationInput.updateHeldMask(heldMasks[safeOwner], lane, held);
    }

    private static void sanitizeAnimationHeldMasks(List<ClientNote> noteList, int[] heldMasks, int currentTick) {
        for (int owner = 0; owner < 2; owner++) {
            int validMask = 0;
            for (ClientNote note : noteList) {
                if (note.owner == owner && note.started && !note.resolved && note.durationTicks > 0
                        && currentTick <= note.tick + note.durationTicks + HOLD_RELEASE_EARLY_TICKS) {
                    validMask |= 1 << note.lane;
                }
            }
            heldMasks[owner] &= validMask;
        }
    }

    private static float introProgress(float time) {
        return Mth.clamp((time + INTRO_TICKS) / INTRO_TICKS, 0.0F, 1.0F);
    }

    private static float beatPulse(float time) {
        return beatPulse(time, sinceBeatOne(time), trackBpm);
    }

    /** Beat 1 on the active battle's clock, in ticks: where every beat visual of the fighters counts from. */
    private static float activeBeatOrigin() {
        return (float) ChartBeat.originTicks(activeOffsetMs);
    }

    /** Beat 1 on the spectator clock (a watched battle or a DJ song), in ticks. */
    private static float spectatorBeatOrigin() {
        return (float) ChartBeat.originTicks(spectatorBeatOffsetMs);
    }

    /** Beat 1 of the active battle, or of what this client watches, for the poses and hops synced to the beat. */
    static float currentBeatOrigin(boolean spectating) {
        return spectating ? spectatorBeatOrigin() : activeBeatOrigin();
    }

    /** The active battle's time counted from beat 1, for the pulses that should land on the song's beats. */
    private static float sinceBeatOne(float time) {
        return (float) ChartBeat.beatTicks(time, activeOffsetMs);
    }

    /** Silent until the song starts; after that the pulse lands on beats counted from {@code sinceBeatOne}. */
    private static float beatPulse(float time, float sinceBeatOne, int bpm) {
        if (bpm <= 0 || time < 0.0F) {
            return 0.0F;
        }
        float beatTicks = 20.0F * 60.0F / bpm;
        float phase = (float) (sinceBeatOne - Math.floor(sinceBeatOne / beatTicks) * beatTicks) / beatTicks;
        return Mth.clamp(1.0F - phase * 4.0F, 0.0F, 1.0F);
    }

    private static void surrender() {
        if (battleId == null) {
            return;
        }
        PacketDistributor.sendToServer(new SurrenderPayload(battleId));
        surrenderedBattleId = battleId;
        Minecraft minecraft = Minecraft.getInstance();
        clearBattleCameraSync();
        active = false;
        activeSolo = false;
        battleId = null;
        activeTrackId = "";
        activeAudioFile = "";
        activePreviewFile = "";
        musicPausedForGame = false;
        musicStarted = false;
        musicWaitTicks = 0;
        clientErrorReported = false;
        pendingAudioError = null;
        notes.clear();
        heldActions.clear();
        timedLaneFlashes.clear();
        activeEffects = List.of();
        activeDisableStageBars = false;
        activeOffsetMs = 0;
        activeHideStandardDimming = false;
        resetAnimationLayers();
        stopMusic(minecraft);
        if (minecraft.player != null) {
            minecraft.player.setShiftKeyDown(false);
            minecraft.setCameraEntity(minecraft.player);
        }
        if (minecraft.screen instanceof PauseScreen) {
            minecraft.setScreen(null);
        }
    }

    private static void releaseLaneStates() {
        for (int lane = 0; lane < previousLaneDown.length; lane++) {
            if (previousLaneDown[lane]) {
                sendLaneState(lane, false);
                previousLaneDown[lane] = false;
            }
        }
        // The lanes are not being played (a pause, a screen, the song not started): presses made meanwhile are not
        // notes to send when play comes back.
        BattleLaneLatch.LANES.clear();
    }

    private static void reportBattleError(Minecraft minecraft, String reason) {
        if (clientErrorReported) {
            return;
        }
        clientErrorReported = true;
        UUID activeBattleId = battleId;
        if (activeBattleId != null) {
            PacketDistributor.sendToServer(new BattleClientErrorPayload(activeBattleId, reason == null ? text("music_battle.l10n.client.009") : reason));
        }
        surrenderedBattleId = activeBattleId;
        clearBattleCameraSync();
        active = false;
        activeSolo = false;
        battleId = null;
        activeTrackId = "";
        activeAudioFile = "";
        activePreviewFile = "";
        musicPausedForGame = false;
        musicStarted = false;
        musicWaitTicks = 0;
        pendingAudioError = null;
        notes.clear();
        activeEffects = List.of();
        activeDisableStageBars = false;
        activeOffsetMs = 0;
        activeHideStandardDimming = false;
        resetAnimationLayers();
        releaseLaneStates();
        stopMusic(minecraft);
        if (minecraft.player != null) {
            minecraft.player.setShiftKeyDown(false);
            minecraft.setCameraEntity(minecraft.player);
            minecraft.player.displayClientMessage(Component.literal(text("music_battle.l10n.client.010") + reason), false);
        }
    }

    private static void ensureCamera(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || cameraEntityId <= 0) {
            return;
        }
        Entity camera = minecraft.level.getEntity(cameraEntityId);
        if (camera != null && minecraft.getCameraEntity() != camera) {
            minecraft.setCameraEntity(camera);
        }
    }

    public static void resetForWorldTransition() {
        sceneVisibility.invalidate();
        Minecraft minecraft = Minecraft.getInstance();
        // A nested DJ editor can own the isolated camera framebuffer after its
        // parent panel has already received Screen#removed. World changes must
        // therefore release it explicitly instead of waiting for a parent that
        // may never be reopened.
        DjCameraPreviewRenderer.release();
        clearBattleCameraSync();
        active = false;
        activeSolo = false;
        battleId = null;
        surrenderedBattleId = null;
        preloadingTrack = false;
        preloadBattleId = null;
        preloadTrackId = "";
        preloadChartFile = "";
        preloadChartHash = "";
        preloadAudioFile = "";
        preloadAudioHash = "";
        preloadedChartNotes = List.of();
        preloadedChartEffects = List.of();
        preloadedChartLoaded = false;
        preloadedDisableStageBars = false;
        preloadedOffsetMs = 0;
        preloadedHideStandardDimming = false;
        activeTrackId = "";
        activeAudioFile = "";
        activePreviewFile = "";
        musicPausedForGame = false;
        musicStarted = false;
        pendingAudioError = null;
        notes.clear();
        heldActions.clear();
        timedLaneFlashes.clear();
        activeEffects = List.of();
        activeDisableStageBars = false;
        activeOffsetMs = 0;
        activeHideStandardDimming = false;
        clearSpectatorBattle();
        pendingSpectatorMusic = null;
        stopMusic(minecraft);
        stopSpectatorMusic(minecraft);
        resetAnimationLayers();
    }

    private static void resetAnimationLayers() {
        ClientPlayerAnimatorBridge.stopAllBattleAnimations();
        ClientHumanoidAnimatorBridge.stopAllBattleAnimations();
        ClientMobFallbackAnimator.stopAll();
    }

    private static void clearBattleAudience() {
        battleSpectatorEntityIds = Set.of();
        battleMobEntityIds = Set.of();
    }

    private static HighlightRender highlightFor(int entityId, float partialTick) {
        ClientTrackEffects.EffectState cameraPreviewState = DjCameraPreviewRenderer.activeEffectState();
        if (cameraPreviewState != null) {
            if (DjCameraPreviewRenderer.isCompositingLiveMonitor()) {
                // The DJ monitor owns its session audience even when this client is outside
                // its speaker range or currently receives another installation's audio.
                return highlightForState(entityId, cameraPreviewState, 0, 0,
                        DjCameraPreviewRenderer.activePlayerEntityIds(), DjCameraPreviewRenderer.activeMobEntityIds());
            }
            int previewLeftId = active ? leftEntityId : spectatorLeftEntityId;
            int previewRightId = active ? rightEntityId : spectatorRightEntityId;
            return highlightForState(entityId, cameraPreviewState, previewLeftId, previewRightId);
        }
        if (Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview
                && Minecraft.getInstance().player != null && Minecraft.getInstance().player.getId() == entityId) {
            float time = preview.previewTrackTick();
            ClientTrackEffects.EffectState previewState = preview.effectStateAt(time);
            TrackEffect effect = previewState.highlightEffect();
            if (effect == null) return null;
            int owner = preview.previewOwner();
            if (!highlightApplies(effect, time, preview.previewBpm(), 1.0F, owner)) return null;
            float reactive = effect.beatReactive() ? 1.0F + Mth.clamp(previewState.pulseBeat(), 0.0F, 5.0F) * 0.22F : 1.0F;
            return new HighlightRender(effect, owner == 0 ? effect.colorA() : effect.colorB(), reactive,
                    ClientTrackEffects.visibility(effect, time));
        }
        List<TrackEffect> effects = active ? activeEffects
                : spectatorFxVisibility > 0.001F ? spectatorEffects : List.of();
        float time = active ? activeVisualTick(partialTick) : spectatorVisualTick(partialTick);
        int bpm = active ? trackBpm : spectatorTrackBpm;
        int leftId = active ? leftEntityId : spectatorLeftEntityId;
        int rightId = active ? rightEntityId : spectatorRightEntityId;
        float playbackSpeed = active ? musicPlaybackSpeed : spectatorPlaybackSpeed;
        float globalVisibility = active ? 1.0F : spectatorFxVisibility;
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(effects, time, bpm, playbackSpeed,
                globalVisibility, active ? activeBeatOrigin() : spectatorBeatOrigin());
        if (!active) state = djTransitions.attach(state, System.nanoTime());
        return highlightForState(entityId, state, leftId, rightId);
    }

    private static HighlightRender highlightForState(int entityId, ClientTrackEffects.EffectState state,
                                                     int leftId, int rightId) {
        return highlightForState(entityId, state, leftId, rightId, battleSpectatorEntityIds, battleMobEntityIds);
    }

    private static HighlightRender highlightForState(int entityId, ClientTrackEffects.EffectState state,
                                                     int leftId, int rightId, Set<Integer> playerEntityIds,
                                                     Set<Integer> mobEntityIds) {
        HighlightRender current = highlightForSingleState(entityId, state, leftId, rightId, playerEntityIds, mobEntityIds);
        if (current != null) return current;
        for (int i=state.preceding().size()-1;i>=0;i--) {
            HighlightRender prior = highlightForSingleState(entityId, state.preceding().get(i), leftId, rightId, playerEntityIds, mobEntityIds);
            if (prior != null) return prior;
        }
        return null;
    }

    private static HighlightRender highlightForSingleState(int entityId, ClientTrackEffects.EffectState state,
                                                     int leftId, int rightId, Set<Integer> playerEntityIds,
                                                     Set<Integer> mobEntityIds) {
        TrackEffect highlight = state.highlightEffect();
        if (highlight == null) return null;
        if (entityId != leftId && entityId != rightId) {
            int color;
            if (playerEntityIds.contains(entityId)) {
                if (!highlight.settings().highlightSpectators()) return null;
                color = highlight.settings().highlightSpectatorColor();
            } else if (mobEntityIds.contains(entityId)) {
                if (!highlight.settings().highlightEntities()) return null;
                color = highlight.settings().highlightEntityColor();
            } else {
                return null;
            }
            float reactive = highlight.beatReactive()
                    ? 1.0F + Mth.clamp(state.pulseBeat(), 0.0F, 5.0F) * 0.22F : 1.0F;
            return new HighlightRender(highlight, color, reactive,
                    ClientTrackEffects.visibility(highlight, state.trackTick(), state.playbackSpeed())
                            * state.visibilityMultiplier());
        }
        int owner = entityId == leftId ? 0 : 1;
        boolean soloView = rightId <= 0;
        // Beats from the sampled chart's own beat 1: the fighters' chart, or the watched one for a spectator.
        if (!soloView && !highlightApplies(highlight, state.trackTick() - state.beatOriginTicks(), state.bpm(),
                state.playbackSpeed(), owner)) return null;
        int colorOwner = soloView && "p2".equals(highlight.variant()) ? 1 : owner;
        float reactive = highlight.beatReactive() ? 1.0F + Mth.clamp(state.pulseBeat(), 0.0F, 5.0F) * 0.22F : 1.0F;
        return new HighlightRender(highlight, colorOwner == 0 ? highlight.colorA() : highlight.colorB(), reactive,
                ClientTrackEffects.visibility(highlight, state.trackTick(), state.playbackSpeed())
                        * state.visibilityMultiplier());
    }

    private static boolean highlightApplies(TrackEffect effect, float time, int bpm, float playbackSpeed, int owner) {
        float beatTicks = ClientTrackEffects.beatDurationTicks(bpm, playbackSpeed);
        float songTick = Math.max(0.0F, time);
        return switch (effect.variant()) {
            case "p1" -> owner == 0;
            case "p2" -> owner == 1;
            case "alternate" -> (Mth.floor(songTick / beatTicks) & 1) == owner;
            case "pulse" -> ClientTrackEffects.beatPhase(songTick, bpm, playbackSpeed) < 0.35F;
            default -> true;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderOutlineModel(Model model, PoseStack pose, MultiBufferSource buffers, HighlightRender highlight,
                                           ResourceLocation texture, int packedLight, int entityId) {
        ClientModelGlowEngine.render(model, pose, buffers, highlight.effect(), highlight.color(),
                highlight.reactive(), highlight.visibility(), texture, packedLight, entityId);
    }

    private record HighlightRender(TrackEffect effect, int color, float reactive, float visibility) { }

    private static void drawCenteredScaled(GuiGraphics graphics, Font font, Component text, float centerX, float y, float scale, int color) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, text, 0, 0, color);
        pose.popPose();
    }

    private static String labelFor(int owner, int lane) {
        if (owner < 0 || owner >= laneLabels.length || lane < 0 || lane >= laneLabels[owner].length) {
            return DEFAULT_LABELS[Mth.clamp(lane, 0, 3)];
        }
        String label = laneLabels[owner][lane];
        return label == null || label.isBlank() ? DEFAULT_LABELS[lane] : label;
    }

    private static String[] copyKeyLabels(List<String> labels) {
        String[] copied = DEFAULT_LABELS.clone();
        for (int i = 0; i < copied.length && i < labels.size(); i++) {
            String label = labels.get(i);
            if (label != null && !label.isBlank()) {
                copied[i] = label;
            }
        }
        return copied;
    }

    private static void renderBattlePanelPair(RenderLevelStageEvent event, float partial, int leftId, int rightId, float time, int duration, int leftColor, int rightColor, float leftSpeed, float rightSpeed, int leftScoreValue, int rightScoreValue, int[] combos, int[][] flashes, int[][] bursts, List<ClientNote> noteList, float[][] styles) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        renderWorldPanel(event, partial, minecraft.level.getEntity(leftId), 0, time, duration, leftColor, leftSpeed, leftScoreValue, combos[0], flashes, bursts, noteList, styles);
        renderWorldPanel(event, partial, minecraft.level.getEntity(rightId), 1, time, duration, rightColor, rightSpeed, rightScoreValue, combos[1], flashes, bursts, noteList, styles);
    }

    private static void renderWorldPanel(RenderLevelStageEvent event, float partial, Entity entity, int owner, float time, int duration, int color, float speed, int score, int combo, int[][] flashes, int[][] bursts, List<ClientNote> noteList, float[][] styles) {
        if (entity == null || entity.distanceToSqr(event.getCamera().getPosition()) > 42.0D * 42.0D) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        double x = Mth.lerp(partial, entity.xOld, entity.getX()) - event.getCamera().getPosition().x;
        double y = Mth.lerp(partial, entity.yOld, entity.getY()) + entity.getBbHeight() + 0.98D - event.getCamera().getPosition().y;
        double z = Mth.lerp(partial, entity.zOld, entity.getZ()) - event.getCamera().getPosition().z;
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(event.getCamera().rotation());
        pose.scale(0.62F, -0.62F, 0.62F);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        Matrix4f matrix = pose.last().pose();
        // The panel's geometry and colours are BattlePanelRenderer.drawWorld's, which battle-panel-geometry measures;
        // here it goes into one buffer under the panel's pose and is drawn once, in the order it was filled.
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        BattlePanelRenderer.drawWorld(worldSink(buffer, matrix),
                new BattlePanelRenderer.World.Panel(owner, color, spectatorNoteAppearances[owner], speed, duration,
                        flashes[owner], bursts[owner]),
                time, noteList, worldAspect(matrix));
        // The panel and its score are marked, so an effect that leaves the interface uncovered leaves them too.
        boolean marked = ClientModelGlowEngine.beginPanelFxMaskWrite();
        try {
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            drawWorldPanelStats(pose, score, combo, color);
        } finally {
            if (marked) {
                ClientModelGlowEngine.endPanelFxMaskWrite();
            }
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        pose.popPose();
    }

    /** The controller's name for BattlePanelRenderer.World.spawnY; world-note-shape asks it by this name. */
    private static float worldNoteSpawnY(int skin) {
        return BattlePanelRenderer.World.spawnY(skin);
    }

    /** Writes a world panel into one buffer under the panel's pose. */
    private static BattlePanelRenderer.WorldSink worldSink(BufferBuilder buffer, Matrix4f matrix) {
        return new BattlePanelRenderer.WorldSink() {
            @Override
            public void quad(float left, float top, float right, float bottom, int color) {
                buffer.addVertex(matrix, left, bottom, 0.0F).setColor(color);
                buffer.addVertex(matrix, right, bottom, 0.0F).setColor(color);
                buffer.addVertex(matrix, right, top, 0.0F).setColor(color);
                buffer.addVertex(matrix, left, top, 0.0F).setColor(color);
            }

            @Override
            public void glyph(float centerX, float centerY, float room, float barHalfWidth, float barHalfHeight, int skin,
                              int lane, int color) {
                emitWorldNoteShape(buffer, matrix, centerX, centerY, room, barHalfWidth, barHalfHeight, skin, lane, color);
            }

            @Override
            public void insetArrow(int lane, float centerX, float centerY, float halfSize, float thickness, int color) {
                BattleNoteShape.drawInsetArrowTriangles(worldTriangles(buffer, matrix), lane, centerX, centerY, halfSize,
                        thickness, color);
            }
        };
    }

    private static void emitWorldNoteShape(BufferBuilder buffer, Matrix4f matrix, float centerX, float centerY,
                                           float availableLaneWidth, float barHalfWidth, float barHalfHeight,
                                           int skin, int lane, int color) {
        // Non-bar heads and receptors fill 93% of the lane, kept square under an unequal X/Y pose scale.
        BattlePanelRenderer.World.Area box = BattlePanelRenderer.World.glyph(centerX, centerY, availableLaneWidth,
                barHalfWidth, barHalfHeight, skin, worldAspect(matrix));
        BattleNoteShape.drawTriangles(worldTriangles(buffer, matrix), skin, lane, box.left(), box.top(), box.right(),
                box.bottom(), color);
    }

    private static BattleNoteShape.TriangleFill worldTriangles(BufferBuilder buffer, Matrix4f matrix) {
        return (x1, y1, x2, y2, x3, y3, fillColor) -> {
            buffer.addVertex(matrix, x1, y1, 0.0F).setColor(fillColor);
            buffer.addVertex(matrix, x2, y2, 0.0F).setColor(fillColor);
            buffer.addVertex(matrix, x3, y3, 0.0F).setColor(fillColor);
            buffer.addVertex(matrix, x3, y3, 0.0F).setColor(fillColor);
        };
    }

    /** The pose's x scale over its y scale: what keeps a world arrow or circle square on screen. */
    private static float worldAspect(Matrix4f matrix) {
        float basisX = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02());
        float basisY = (float) Math.sqrt(matrix.m10() * matrix.m10() + matrix.m11() * matrix.m11() + matrix.m12() * matrix.m12());
        return basisY > 0.00001F ? basisX / basisY : 1.0F;
    }

    /**
     * The combo and the score above a world panel: the HUD's own score line ("SCORE 12", "СЧЁТ 12") and its "x12" in the
     * HUD's combo colour, rather than English words in every language and a combo in the panel's colour, which is
     * near-black by default.
     */
    private static void drawWorldPanelStats(PoseStack pose, int score, int combo, int color) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Component scoreText = Component.translatable("music_battle.settings.score", Math.max(0, score));
        String comboText = "x" + Math.max(0, combo);
        pose.pushPose();
        pose.translate(0.0F, -0.72F, 0.01F);
        pose.scale(0.018F, 0.018F, 0.018F);
        font.drawInBatch(scoreText, -font.width(scoreText) / 2.0F, 0.0F, ru.gora.musicbattle.client.ui.Palette.TEXT_1, false,
                pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0, 0x00F000F0);
        font.drawInBatch(comboText, -font.width(comboText) / 2.0F, -11.0F, BattlePanelRenderer.comboColor(color, combo), false,
                pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0, 0x00F000F0);
        buffers.endBatch();
        pose.popPose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void drawQuad(Matrix4f matrix, float x1, float y1, float x2, float y2, int color) {
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        float r = ((color >>> 16) & 0xFF) / 255.0F;
        float g = ((color >>> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(matrix, x1, y2, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y1, 0.0F).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y1, 0.0F).setColor(r, g, b, a);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void drawNumber(Matrix4f matrix, float x, float y, int value, float scale, int color) {
        String text = Integer.toString(Math.max(0, value));
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            int digit = text.charAt(i) - '0';
            if (digit >= 0 && digit <= 9) {
                drawDigit(matrix, cursor, y, scale, digit, color);
            }
            cursor += scale * 4.4F;
        }
    }

    private static void drawNumberCentered(Matrix4f matrix, float centerX, float y, int value, float scale, int color) {
        String text = Integer.toString(Math.max(0, value));
        float width = text.length() <= 0 ? 0.0F : (text.length() - 1) * scale * 4.4F + scale * 3.0F;
        drawNumber(matrix, centerX - width / 2.0F, y, value, scale, color);
    }

    private static void drawDigit(Matrix4f matrix, float x, float y, float s, int digit, int color) {
        int mask = switch (digit) {
            case 0 -> 0b1111110;
            case 1 -> 0b0110000;
            case 2 -> 0b1101101;
            case 3 -> 0b1111001;
            case 4 -> 0b0110011;
            case 5 -> 0b1011011;
            case 6 -> 0b1011111;
            case 7 -> 0b1110000;
            case 8 -> 0b1111111;
            case 9 -> 0b1111011;
            default -> 0;
        };
        if ((mask & 0b1000000) != 0) drawSegment(matrix, x, y, s, true, color);
        if ((mask & 0b0100000) != 0) drawSegment(matrix, x + 3 * s, y + s, s, false, color);
        if ((mask & 0b0010000) != 0) drawSegment(matrix, x + 3 * s, y + 4 * s, s, false, color);
        if ((mask & 0b0001000) != 0) drawSegment(matrix, x, y + 6 * s, s, true, color);
        if ((mask & 0b0000100) != 0) drawSegment(matrix, x - s, y + 4 * s, s, false, color);
        if ((mask & 0b0000010) != 0) drawSegment(matrix, x - s, y + s, s, false, color);
        if ((mask & 0b0000001) != 0) drawSegment(matrix, x, y + 3 * s, s, true, color);
    }

    private static void drawSegment(Matrix4f matrix, float x, float y, float s, boolean horizontal, int color) {
        if (horizontal) {
            drawQuad(matrix, x, y, x + 3 * s, y + s, color);
        } else {
            drawQuad(matrix, x, y, x + s, y + 3 * s, color);
        }
    }

    private static boolean isParticipantEntity(int entityId) {
        return (active && (entityId == leftEntityId || entityId == rightEntityId))
                || (spectatorBattleId != null && (entityId == spectatorLeftEntityId || entityId == spectatorRightEntityId));
    }

    private static int ownerForEntity(int entityId) {
        if (entityId == leftEntityId || entityId == spectatorLeftEntityId) {
            return 0;
        }
        return 1;
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Mth.clamp(alpha, 0, 255) << 24)
                | (Mth.clamp(red, 0, 255) << 16)
                | (Mth.clamp(green, 0, 255) << 8)
                | Mth.clamp(blue, 0, 255);
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int lighten(int color, int alpha) {
        int red = Math.min(255, ((color >> 16) & 0xFF) + 95);
        int green = Math.min(255, ((color >> 8) & 0xFF) + 95);
        int blue = Math.min(255, (color & 0xFF) + 95);
        return argb(alpha, red, green, blue);
    }

    private static final class ClientNote implements BattlePanelRenderer.NoteView {
        private final int index;
        private final int owner;
        private final int lane;
        private final int tick;
        private final int durationTicks;
        private boolean started;
        private boolean resolved;
        private boolean pendingHit;
        private boolean pendingMiss;
        private boolean pendingComplete;
        private boolean pendingRemoteState;
        private boolean pendingRemoteStateReady;
        /** The number of the action whose score and combo wait here (remoteStateArrivals). */
        private long pendingArrival;
        private int pendingLeftScore;
        private int pendingRightScore;
        private int pendingCombo;

        private ClientNote(int index, int owner, int lane, int tick, int durationTicks) {
            this.index = index;
            this.owner = owner;
            this.lane = lane;
            this.tick = tick;
            this.durationTicks = durationTicks;
        }

        @Override
        public int owner() {
            return owner;
        }

        @Override
        public int lane() {
            return lane;
        }

        @Override
        public int tick() {
            return tick;
        }

        @Override
        public int durationTicks() {
            return durationTicks;
        }

        @Override
        public boolean started() {
            return started;
        }

        @Override
        public boolean resolved() {
            return resolved;
        }
    }

    /**
     * Lane flashes and hit bursts that count down on the battle clock from the tick they were shown at, one step per
     * battle tick, instead of on client ticks. An action held for its tick is shown at a frame between two client
     * ticks; counted down by the next client tick, its brightest step lasted anything from a moment to a whole tick,
     * and after each pause by a different amount, since a pause moves the battle clock against the client's ticks.
     * Lanes not started here are counted down on client ticks, as every flash always was.
     */
    private static final class TimedLaneFlashes {
        static final int BURST_TICKS = 8;
        private final float[][] startedAt = new float[2][4];
        private final int[][] flashTicks = new int[2][4];

        private TimedLaneFlashes() {
            clear();
        }

        void start(int owner, int lane, int flash, float tick) {
            startedAt[owner][lane] = tick;
            flashTicks[owner][lane] = flash;
        }

        void stop(int owner, int lane) {
            startedAt[owner][lane] = Float.NaN;
        }

        boolean timed(int owner, int lane) {
            return !Float.isNaN(startedAt[owner][lane]);
        }

        boolean anyTimed() {
            for (int owner = 0; owner < 2; owner++) {
                for (int lane = 0; lane < 4; lane++) {
                    if (timed(owner, lane)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Sets every timed lane's flash and burst for the battle clock {@code clock}; a lane that has run out stops. */
        void apply(float clock, int[][] flashes, int[][] bursts) {
            for (int owner = 0; owner < 2; owner++) {
                for (int lane = 0; lane < 4; lane++) {
                    if (!timed(owner, lane)) {
                        continue;
                    }
                    // A clock behind the start (a seek back) keeps the first step rather than growing past it.
                    int elapsed = Math.max(0, Mth.floor(clock - startedAt[owner][lane]));
                    flashes[owner][lane] = Math.max(0, flashTicks[owner][lane] - elapsed);
                    bursts[owner][lane] = Math.max(0, BURST_TICKS - elapsed);
                    if (flashes[owner][lane] == 0 && bursts[owner][lane] == 0) {
                        stop(owner, lane);
                    }
                }
            }
        }

        void clear() {
            for (float[] lanes : startedAt) {
                Arrays.fill(lanes, Float.NaN);
            }
        }
    }

    private static final class PendingSpectatorMusic {
        private SpectatorMusicPayload payload;
        private int ticksLeft;
        private int delayTicks;

        private PendingSpectatorMusic(SpectatorMusicPayload payload, int ticksLeft, int delayTicks) {
            this.payload = payload;
            this.ticksLeft = ticksLeft;
            this.delayTicks = delayTicks;
        }
    }
}
