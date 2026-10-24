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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
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
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.BattleSkipClock;
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
import ru.gora.musicbattle.network.ClientJudgementPayload;
import ru.gora.musicbattle.network.NoteInputPayload;
import ru.gora.musicbattle.network.OpenVotePayload;
import ru.gora.musicbattle.network.OpenRapGodConfigPayload;
import ru.gora.musicbattle.network.OpenSpeakerConfigPayload;
import ru.gora.musicbattle.network.OpenDjPanelPayload;
import ru.gora.musicbattle.network.OpenCameraEditorPayload;
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
    private static long musicPausedAtNanos;
    private static int musicClockStartTick;
    private static int spectatorMusicClockStartTick;
    private static PendingSpectatorMusic pendingSpectatorMusic;
    private static SpectatorMusicPayload lastSpectatorMusicPayload;
    private static boolean spectatorMusicAwaitingStart;
    private static int spectatorMusicStartWaitTicks;
    private static boolean spectatorMusicStartFreezeDisabled;
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
    private static boolean syncedCameraAvailable;
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
    private static String activeTrackId = "";
    private static String activeAudioFile = "";
    private static String activePreviewFile = "";
    private static boolean musicPausedForGame;
    private static boolean spectatorMusicPausedForGame;
    private static long spectatorMusicPausedAtNanos;
    private static boolean musicStarted;
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
    private static final int[] animationTriggerSerial = new int[2];
    private static final int[] animationTriggerLane = {-1, -1};
    private static final int[] animationHeldMask = new int[2];
    private static final int[] animationInputHeldMask = new int[2];
    private static final float[] animationTriggerTick = {-Float.MAX_VALUE, -Float.MAX_VALUE};
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
            } else {
                minecraft.setScreen(new DjPanelScreen(payload));
            }
        } else {
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
        syncedCameraBattleId = payload.battleId();
        syncedCameraEntityId = payload.cameraEntityId();
        syncedCameraX = payload.x();
        syncedCameraY = payload.y();
        syncedCameraZ = payload.z();
        syncedCameraYaw = Mth.wrapDegrees(payload.yaw());
        syncedCameraPitch = Mth.clamp(payload.pitch(), -89.0F, 89.0F);
        // A trusted, identity/revision-checked server update uses zero FOV to restore the
        // ordinary entity-driven arena camera; all positive camera FOVs remain clamped.
        syncedCameraFov = payload.fov() <= 0 ? 0 : Mth.clamp(payload.fov(), 30.0F, 110.0F);
        syncedCameraRevision = payload.revision();
        syncedCameraAvailable = payload.fov() > 0;
        if (activeMatch && syncedCameraAvailable && (!sameCamera || !activeCameraInterpolation.valid())) {
            activeCameraInterpolation.snap(payload.x(), payload.y(), payload.z(),
                    syncedCameraYaw, syncedCameraPitch, syncedCameraFov);
            cameraYaw = syncedCameraYaw;
            cameraPitch = syncedCameraPitch;
            cameraFov = syncedCameraFov;
        }
        if (!syncedCameraAvailable) {
            if (activeMatch) {
                activeCameraInterpolation.clear();
                cameraFov = 0;
                cameraYaw = startYaw = syncedCameraYaw;
                cameraPitch = startPitch = syncedCameraPitch;
                // The frame is drawn from the aim pair, not from cameraYaw, and the tick that copies one
                // into the other has not run yet. Without this the view keeps the camera block's angle for
                // the rest of the tick and then swings across to the arena, which reads as a jolt.
                aimYawFrom = aimYawTo = cameraYaw;
                aimPitchFrom = aimPitchTo = cameraPitch;
            }
            if (spectatorMatch) {
                spectatorCameraFov = 0;
                spectatorCameraYaw = syncedCameraYaw;
                spectatorCameraPitch = syncedCameraPitch;
                spectatorCameraViewPosition = new Vec3(payload.x(), payload.y(), payload.z());
                spectatorCameraViewPositionValid = true;
                snapSpectatorCameraInterpolation();
            }
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

    public static void updatePauseSkipStatus(PauseSkipStatusPayload payload) {
        boolean activeBattle = battleId != null && battleId.equals(payload.battleId());
        boolean spectatorBattle = !active && spectatorBattleId != null && spectatorBattleId.equals(payload.battleId());
        if (!activeBattle && !spectatorBattle) {
            return;
        }
        if (payload.applied()) {
            int targetTick = Math.max(0, payload.targetTick());
            if (activeBattle) {
                if (startAtNanos != 0L) {
                    // The battle clock is read from the epoch every tick, and once a second the music is seeked back
                    // to it, so setting only the counter undid the skip within a second.
                    long now = musicPausedAtNanos > 0L ? musicPausedAtNanos : System.nanoTime();
                    startAtNanos = BattleSkipClock.clientEpochAfterSkip(now, targetTick, ownOneWayMillis());
                    targetTick = Math.max(0, Mth.floor(activeVisualTick(0.0F)));
                }
                localTicks = targetTick;
                musicStarted = activeAudioFile == null || activeAudioFile.isBlank() || playMusic(Minecraft.getInstance(), activeTrackId, activeAudioFile, localTicks);
            } else {
                spectatorMusicStartFreezeDisabled = false;
                restartSpectatorMusicAt(targetTick);
            }
        }
        if (!activeBattle) {
            return;
        }
        pauseSkipAvailable = payload.available();
        pauseSkipVotes = payload.votes();
        pauseSkipRequired = Math.max(1, payload.required());
    }

    /**
     * This player's one-way delay as the server reckons it: the latency the server measures is what reaches every
     * client in the player list, and the start of the battle was planned around the same figure.
     */
    private static int ownOneWayMillis() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return 0;
        }
        net.minecraft.client.multiplayer.PlayerInfo info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        return info == null ? 0 : BattleClock.oneWayMillis(info.getLatency());
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
            for (int lane = 0; lane < 4; lane++) {
                laneFlashes[owner][lane] = 0;
                hitBursts[owner][lane] = 0;
            }
        }
        for (int lane = 0; lane < previousLaneDown.length; lane++) {
            previousLaneDown[lane] = false;
            laneHitLockoutUntil[lane] = 0;
        }

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

    public static void acceptAction(BattleActionPayload payload) {
        boolean handled = false;
        if (active && battleId != null && battleId.equals(payload.battleId())) {
            int owner = Mth.clamp(payload.owner(), 0, 1);
            int lane = Mth.clamp(payload.lane(), 0, 3);
            if (owner != ownOwner) {
                boolean visualApplied = payload.noteIndex() < 0;
                boolean flashAction = shouldFlashAction(notes, payload.noteIndex(), payload.hit(), payload.complete());
                ClientNote animationNote = noteByIndex(notes, payload.noteIndex());
                if (payload.noteIndex() >= 0) {
                    visualApplied = applyNoteAction(notes, owner, lane, payload.noteIndex(), payload.noteTick(), localTicks, payload.hit(), payload.complete());
                }
                if (visualApplied) {
                    if (animationNote != null && animationNote.durationTicks > 0) {
                        setPlayerAnimationHeld(false, owner, lane,
                                animationNote.started && !animationNote.resolved);
                    }
                    applyActiveRemoteState(owner, payload.score(), payload.combo());
                    if (flashAction) {
                        laneFlashes[owner][lane] = payload.hit() ? 8 : 4;
                        triggerPlayerAnimation(false, owner, lane);
                    }
                    if (flashAction && payload.hit() && payload.noteIndex() >= 0) {
                        hitBursts[owner][lane] = 8;
                    }
                } else {
                    queuePendingRemoteState(notes, payload.noteIndex(), payload.leftScore(), payload.rightScore(), payload.combo());
                }
            } else {
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
                        && applyNoteAction(notes, owner, lane, payload.noteIndex(), payload.noteTick(), localTicks,
                                payload.hit(), payload.complete())) {
                    applyOwnCorrection(owner, payload.score(), payload.combo(), countsAsOwnMistake);
                    if (shouldFlashAction(notes, payload.noteIndex(), payload.hit(), payload.complete())) {
                        laneFlashes[owner][lane] = payload.hit() ? 8 : 4;
                    }
                }
            }
            handled = true;
        }
        if (spectatorBattleId != null && spectatorBattleId.equals(payload.battleId())) {
            int owner = Mth.clamp(payload.owner(), 0, 1);
            int lane = Mth.clamp(payload.lane(), 0, 3);
            boolean visualApplied = payload.noteIndex() < 0;
            boolean flashAction = shouldFlashAction(spectatorNotes, payload.noteIndex(), payload.hit(), payload.complete());
            ClientNote animationNote = noteByIndex(spectatorNotes, payload.noteIndex());
            if (payload.noteIndex() >= 0) {
                visualApplied = applyNoteAction(spectatorNotes, owner, lane, payload.noteIndex(), payload.noteTick(), spectatorLocalTicks, payload.hit(), payload.complete());
            }
            if (visualApplied) {
                if (animationNote != null && animationNote.durationTicks > 0) {
                    setPlayerAnimationHeld(true, owner, lane,
                            animationNote.started && !animationNote.resolved);
                }
                applySpectatorRemoteState(owner, payload.leftScore(), payload.rightScore(), payload.combo());
                if (flashAction) {
                    spectatorLaneFlashes[owner][lane] = payload.hit() ? 8 : 4;
                    triggerPlayerAnimation(true, owner, lane);
                }
                if (flashAction && payload.hit() && payload.noteIndex() >= 0) {
                        spectatorHitBursts[owner][lane] = 8;
                    }
            } else {
                queuePendingRemoteState(spectatorNotes, payload.noteIndex(), payload.leftScore(), payload.rightScore(), payload.combo());
            }
            handled = true;
        }
        if (!handled) {
            return;
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
                    spectatorTrackBpm, spectatorPlaybackSpeed, System.nanoTime());
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
        spectatorLeftScore = payload.leftScore();
        spectatorRightScore = payload.rightScore();
        spectatorCombo[0] = payload.leftCombo();
        spectatorCombo[1] = payload.rightCombo();
        spectatorNotes.clear();
        for (int i = 0; i < payload.notes().size(); i++) {
            BattleNote note = payload.notes().get(i);
            ClientNote clientNote = new ClientNote(i, note.owner(), note.lane(), note.tick(), note.durationTicks());
            int noteEnd = note.tick() + Math.max(0, note.durationTicks());
            if (payload.startTick() > noteEnd + HIT_WINDOW_TICKS) {
                clientNote.resolved = true;
            } else if (note.durationTicks() > 0 && payload.startTick() >= note.tick()
                    && payload.startTick() <= noteEnd + HOLD_RELEASE_EARLY_TICKS) {
                int heldMask = note.owner() == 0 ? payload.leftHeldMask() : payload.rightHeldMask();
                clientNote.started = (heldMask & (1 << Mth.clamp(note.lane(), 0, 3))) != 0;
            }
            spectatorNotes.add(clientNote);
        }
        spectatorEffects = payload.effects();
        for (int owner = 0; owner < 2; owner++) {
            spectatorAnimationTriggerLane[owner] = -1;
            spectatorAnimationInputHeldMask[owner] = owner == 0 ? payload.leftHeldMask() : payload.rightHeldMask();
            spectatorAnimationTriggerTick[owner] = -Float.MAX_VALUE;
            for (int lane = 0; lane < 4; lane++) {
                spectatorLaneFlashes[owner][lane] = 0;
                spectatorHitBursts[owner][lane] = 0;
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
                if (!spectatorMusicPausedForGame) {
                    spectatorMusicPausedForGame = true;
                    spectatorMusicPausedAtNanos = spectatorMusicStartedAtNanos > 0L ? System.nanoTime() : 0L;
                    minecraft.getSoundManager().pause();
                }
                return;
            }
            if (spectatorMusicPausedForGame) {
                spectatorMusicPausedForGame = false;
                minecraft.getSoundManager().resume();
                if (spectatorMusicPausedAtNanos > 0L && spectatorMusicStartedAtNanos > 0L) {
                    spectatorMusicStartedAtNanos += Math.max(0L, System.nanoTime() - spectatorMusicPausedAtNanos);
                }
                spectatorMusicPausedAtNanos = 0L;
            }
            tickSpectatorBattle();
            tryPlayPendingSpectatorMusic();
            tickSpectatorMusicRecovery(minecraft);
            return;
        }

        if (minecraft.level == null || minecraft.player == null) {
            active = false;
            activeSolo = false;
            return;
        }

        if (minecraft.isPaused()) {
            minecraft.player.setShiftKeyDown(false);
            releaseLaneStates();
            if (!musicPausedForGame) {
                minecraft.getSoundManager().pause();
                musicPausedForGame = true;
                // Stamped whether or not the music has started. The server freezes for as long as this client
                // does, so the battle clock has to be held back by the same amount. This used to be stamped only
                // once the audio was playing, and a pause during the count-in or while the file was still being
                // opened left the chart running on the wall clock while the server stood still: everything that
                // went by in that window came back as a miss, a whole phrase lost to standing in the menu.
                musicPausedAtNanos = System.nanoTime();
            }
            return;
        }
        if (pendingAudioError != null) {
            reportBattleError(minecraft, pendingAudioError);
            return;
        }
        minecraft.player.setShiftKeyDown(false);
        if (musicPausedForGame) {
            musicPausedForGame = false;
            minecraft.getSoundManager().resume();
            // Both origins move by the whole pause, so the battle resumes on the tick it was stopped at - which
            // is the tick the server is still on. Shifting only one of them, or neither, puts the two clocks a
            // menu visit apart for the rest of the song.
            long paused = musicPausedAtNanos > 0L ? Math.max(0L, System.nanoTime() - musicPausedAtNanos) : 0L;
            if (musicStartedAtNanos > 0L) {
                musicStartedAtNanos += paused;
            }
            if (startAtNanos != 0L) {
                startAtNanos += paused;
            }
            musicPausedAtNanos = 0L;
        }

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
                if (laneFlashes[owner][lane] > 0) {
                    laneFlashes[owner][lane]--;
                }
                if (hitBursts[owner][lane] > 0) {
                    hitBursts[owner][lane]--;
                }
            }
        }

        if (minecraft.screen == null) {
            boolean spaceDown = InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
            if (pauseSkipAvailable && spaceDown && !pauseSkipSpaceDown && battleId != null) {
                PacketDistributor.sendToServer(new PauseSkipVotePayload(battleId));
            }
            pauseSkipSpaceDown = spaceDown;
            KeyMapping[] keys = MusicBattleKeys.lanes();
            for (int lane = 0; lane < keys.length; lane++) {
                boolean down = keys[lane].isDown();
                if (down != previousLaneDown[lane]) {
                    sendLaneState(lane, down);
                    previousLaneDown[lane] = down;
                }
            }
        } else {
            pauseSkipSpaceDown = false;
            for (int lane = 0; lane < previousLaneDown.length; lane++) {
                if (previousLaneDown[lane]) {
                    sendLaneState(lane, false);
                    previousLaneDown[lane] = false;
                }
            }
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
                        spectatorTrackBpm, spectatorPlaybackSpeed, spectatorFxVisibility);
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
                    musicPlaybackSpeed);
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
        int animationBpm = spectating ? spectatorTrackBpm : trackBpm;
        float animationTick = spectating ? spectatorVisualTick(event.getPartialTick()) : activeVisualTick(event.getPartialTick());
        int safeOwner = Mth.clamp(owner, 0, 1);
        ClientPlayerAnimatorBridge.updateBattleAnimation(clientPlayer, triggerSerials[safeOwner], triggerLanes[safeOwner],
                heldMasks[safeOwner] | inputHeldMasks[safeOwner], animationBpm, animationTick, triggerTicks[safeOwner]);
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
        int animationBpm = spectating ? spectatorTrackBpm : trackBpm;
        float animationTick = spectating ? spectatorVisualTick(event.getPartialTick()) : activeVisualTick(event.getPartialTick());
        int safeOwner = Mth.clamp(owner, 0, 1);
        if (event.getRenderer().getModel() instanceof HumanoidModel<?> humanoidModel
                && ClientHumanoidAnimatorBridge.supportsAnimation(humanoidModel)) {
            ClientMobFallbackAnimator.stop(entity);
            ClientHumanoidAnimatorBridge.prepareBattleAnimation(entity, humanoidModel, triggerSerials[safeOwner],
                    triggerLanes[safeOwner], heldMasks[safeOwner] | inputHeldMasks[safeOwner], animationBpm, animationTick,
                    triggerTicks[safeOwner], event.getPartialTick());
        } else {
            ClientHumanoidAnimatorBridge.stopBattleAnimation(entity);
            ClientMobFallbackAnimator.prepare(entity, triggerSerials[safeOwner],
                    heldMasks[safeOwner] | inputHeldMasks[safeOwner], animationBpm, animationTick);
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
                    musicPlaybackSpeed);
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
        return highlight != null && highlight.effect().settings().highlightModelGlow()
                ? LightTexture.FULL_BRIGHT : packedLight;
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
        ClientTrackEffects.EffectState effectState = ClientTrackEffects.sample(activeEffects, time, trackBpm, musicPlaybackSpeed);
        boolean hideHud = minecraft.options.hideGui || effectState.hidesBlockyMusicHud();

        if (!hud.identity()) {
            graphics.pose().pushPose();
            graphics.pose().scale(hud.factor(), hud.factor(), 1.0F);
        }
        ClientTrackEffects.canvas(width, height);
        if (!hideHud && !activeHideStandardDimming) {
            graphics.fill(0, 0, width, height, argb(70, 0, 0, 0));
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

    private static void renderPreloadBar(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int width = graphics.guiWidth();
        int barWidth = Mth.clamp(width - 96, 160, 520);
        int x = (width - barWidth) / 2;
        int y = 8;
        float chartProgress = ClientTrackAssets.transferProgress(preloadTrackId, "chart", preloadChartFile, preloadChartHash);
        float audioProgress = preloadAudioFile.isBlank() ? 1.0F : ClientTrackAssets.transferProgress(preloadTrackId, "audio", preloadAudioFile, preloadAudioHash);
        int totalBytes = Math.max(1, preloadChartBytes + preloadAudioBytes);
        float progress = (chartProgress * preloadChartBytes + audioProgress * preloadAudioBytes) / totalBytes;
        String title = preloadTrackTitle == null || preloadTrackTitle.isBlank() ? preloadTrackId : preloadTrackTitle;
        Component label = Component.literal(text("music_battle.l10n.client.003") + title + " " + Math.round(progress * 100.0F) + "%");
        int panelColor = argb(205, 6, 12, 18);
        int fillColor = argb(245, 56, 205, 105);
        graphics.fill(x - 2, y - 2, x + barWidth + 2, y + 22, panelColor);
        graphics.fill(x, y + 12, x + barWidth, y + 18, argb(220, 12, 28, 38));
        graphics.fill(x, y + 12, x + Math.round(barWidth * Mth.clamp(progress, 0.0F, 1.0F)), y + 18, fillColor);
        drawCenteredFittedString(graphics, font, label.getString(), width / 2, y, barWidth - 8, 0xFFFFFFFF);
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
                active ? 1.0F : spectatorFxVisibility);
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
                float orbitYaw = standaloneDjPlayback || spectatorLocalTicks < 0
                        ? 0.0F : Mth.sin(time / 58.0F) * 1.35F;
                float orbitPitch = standaloneDjPlayback || spectatorLocalTicks < 0
                        ? 0.0F : Mth.sin(time / 47.0F) * 0.22F;
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
        float orbitYaw = localTicks < 0 ? 0.0F : Mth.sin(time / 58.0F) * 1.35F;
        float orbitPitch = localTicks < 0 ? 0.0F : Mth.sin(time / 47.0F) * 0.22F;
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
                active ? 1.0F : spectatorFxVisibility);
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
        event.setFOV(baseFov + beatPulse(time, active ? sinceBeatOne(time) : time, bpm) * amplitude);
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
        sceneVisibility.invalidate();
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
        if (active) return ClientTrackEffects.sample(activeEffects, activeVisualTick(partial), trackBpm, musicPlaybackSpeed);
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
            Rect bounds = MenuCornerButtons.settings();
            Button button = Button.builder(Component.literal("BM"), ignored -> Minecraft.getInstance().setScreen(new MusicBattleSettingsScreen(screen)))
                    .bounds((int) bounds.x(), (int) bounds.y(), (int) bounds.w(), (int) bounds.h())
                    .tooltip(Tooltip.create(Component.translatable("music_battle.options")))
                    .build();
            event.addListener(button);
        }

        if (active && event.getScreen() instanceof PauseScreen screen) {
            Button button = Button.builder(Component.literal(text("music_battle.l10n.client.004")), ignored -> surrender())
                    .bounds(screen.width / 2 - 44, 8, 88, 20)
                    .tooltip(Tooltip.create(Component.literal(text("music_battle.l10n.client.005"))))
                    .build();
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
                // No local path may leave this machine: the server shows the reason to the opponent.
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
            if (note.resolved || note.started || note.owner != ownOwner || note.lane != lane) {
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
            return -1;
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

    private static ClientNote activeOwnHold(int lane) {
        for (ClientNote note : notes) {
            if (!note.resolved && note.started && note.owner == ownOwner && note.lane == lane && note.durationTicks > 0) {
                return note;
            }
        }
        return null;
    }

    private static boolean shouldBreakComboForBadPress(int pressedLane, int inputTick) {
        for (ClientNote note : notes) {
            if (note.resolved || note.started || note.owner != ownOwner) {
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
        if (noteTick >= 0 && currentTick < note.tick - 1) {
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
                boolean held = note.durationTicks > 0 && (!note.pendingComplete || currentTick < note.tick + note.durationTicks);
                if (held) {
                    note.started = true;
                } else {
                    note.resolved = true;
                    note.started = false;
                }
                setPlayerAnimationHeld(spectator, note.owner, note.lane, held);
                triggerPlayerAnimation(spectator, note.owner, note.lane);
                flashes[note.owner][note.lane] = 8;
                bursts[note.owner][note.lane] = 8;
                note.pendingHit = false;
                if (!held || !note.pendingComplete) {
                    note.pendingComplete = false;
                }
                note.pendingRemoteStateReady = true;
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

    private static void queuePendingRemoteState(List<ClientNote> noteList, int noteIndex, int left, int right, int combo) {
        ClientNote note = noteByIndex(noteList, noteIndex);
        if (note == null) {
            return;
        }
        note.pendingRemoteState = true;
        note.pendingLeftScore = left;
        note.pendingRightScore = right;
        note.pendingCombo = combo;
    }

    private static void flushPendingRemoteStates(List<ClientNote> noteList, boolean spectator) {
        for (ClientNote note : noteList) {
            if (!note.pendingRemoteState || !note.pendingRemoteStateReady) {
                continue;
            }
            if (spectator) {
                applySpectatorRemoteState(note.owner, note.pendingLeftScore, note.pendingRightScore, note.pendingCombo);
            } else if (note.owner != ownOwner) {
                applyActiveRemoteState(note.owner, note.owner == 0 ? note.pendingLeftScore : note.pendingRightScore, note.pendingCombo);
            }
            note.pendingRemoteState = false;
            note.pendingRemoteStateReady = false;
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

    private static void applySpectatorRemoteState(int owner, int left, int right, int combo) {
        spectatorLeftScore = left;
        spectatorRightScore = right;
        spectatorCombo[owner] = combo;
    }

    private static void resolveLocalMissedNotes() {
        int inputTick = calibratedInputTick();
        for (ClientNote note : notes) {
            if (note.resolved || note.started || note.owner != ownOwner) {
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
            if (note.resolved || !note.started || note.owner != ownOwner || note.durationTicks <= 0) {
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

    private static float noteTravelY(float time, int noteTick, int leadTicks, float startY, float hitY, float lateY) {
        float approach = (time - noteTick + leadTicks) / Math.max(1.0F, leadTicks);
        if (approach <= 1.0F) {
            return Mth.lerp(Mth.clamp(approach, 0.0F, 1.0F), startY, hitY);
        }
        float late = (time - noteTick) / Math.max(1.0F, HIT_WINDOW_TICKS);
        return Mth.lerp(Mth.clamp(late, 0.0F, 1.0F), hitY, lateY);
    }

    private static boolean noteVisibleAt(float time, int noteTick, int durationTicks, int leadTicks) {
        int endTick = noteTick + Math.max(0, durationTicks);
        return time >= noteTick - leadTicks - 6.0F && time <= endTick + HIT_WINDOW_TICKS + 6.0F;
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
        BattlePanelRenderer.draw(graphics, font, width, height, time, trackBpm, intro, notes,
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

    private static void drawHeader(GuiGraphics graphics, Font font, int width, float time, float intro) {
        int barWidth = Math.min(width - 80, 420);
        int x = width / 2 - barWidth / 2;
        int y = 12;
        float progress = Mth.clamp(Math.max(0.0F, time) / Math.max(1.0F, durationTicks), 0.0F, 1.0F);
        graphics.fill(x, y, x + barWidth, y + 24, argb(150, 8, 10, 16));
        graphics.fill(x, y + 22, x + Math.round(barWidth * progress), y + 24, argb(230, 90, 210, 235));
        ClientTrackAssets.cachedAsset(activeTrackId, activePreviewFile).ifPresent(path -> {
            ResourceLocation texture = ClientPreviewTextures.get(activeTrackId, path);
            if (texture != null) {
                int baseSize = 10;
                int size = Math.round(baseSize * (1.0F + beatPulse(time) * 0.20F));
                int titleLeft = width / 2 - font.width(trackTitle) / 2;
                int imageX = titleLeft - 4 - size;
                int imageY = y + 2 - (size - baseSize) / 2;
                graphics.fill(imageX - 1, imageY - 1, imageX + size + 1, imageY + size + 1, 0xDDFFFFFF);
                graphics.blit(texture, imageX, imageY, 0.0F, 0.0F, size, size, size, size);
            }
        });
        graphics.drawCenteredString(font, trackTitle, width / 2, y + 4, 0xFFFFFFFF);
        int remaining = Math.max(0, (durationTicks - Math.max(0, (int) time) + 19) / 20);
        String timer = String.format("%02d:%02d", remaining / 60, remaining % 60);
        graphics.drawCenteredString(font, timer, width / 2, y + 14, 0xFFB8DCE8);
        if (time < 0.0F) {
            graphics.drawCenteredString(font, "READY " + Math.max(1, (int) Math.ceil(-time / 20.0F)), width / 2, y + 28, withAlpha(0xFFFFFFFF, Math.round(255 * intro)));
        }
    }

    private static void drawStageBars(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, 36, argb(185, 0, 0, 0));
        graphics.fill(0, height - 30, width, height, argb(185, 0, 0, 0));
    }

    private static boolean playMusic(Minecraft minecraft, String trackId, String audioFile, int skipTicks) {
        if (audioFile == null || audioFile.isBlank()) {
            return false;
        }

        Optional<Path> path = ClientTrackAssets.cachedAsset(trackId, audioFile);
        if (path.isEmpty()) {
            return false;
        }

        stopMusic(minecraft);
        musicClockStartTick = Math.max(0, skipTicks);
        Path audioPath = path.get();
        ClientAudioAnalyzer.activate(audioPath);
        float pitch = fallbackPlaybackPitch(audioFile, musicPlaybackSpeed);
        int audioSkipTicks = Math.round(Math.max(0, skipTicks) * pitch);
        music = new ExternalOggSoundInstance(trackId, audioPath.getFileName().toString(), audioPath, audioSkipTicks, true, 0.0D, 0.0D, 0.0D, SoundInstance.Attenuation.NONE,
                trackVolume * ClientBattleSettings.musicVolume(), pitch,
                reason -> pendingAudioError = text("music_battle.l10n.client.008") + audioFile + "': " + reason);
        minecraft.getSoundManager().play(music);
        return true;
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
        resetMusicClock();
    }

    private static void stopSpectatorMusic(Minecraft minecraft) {
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
        spectatorMusicInactiveTicks++;
        if (spectatorMusicInactiveTicks < 8 || spectatorMusicRecoveryCooldown > 0) {
            return;
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
        // The shared effect editor is also used for charts, where there is no rack and nothing to silence.
        return screen instanceof TrackEffectEditScreen edit && edit.owningDjPanel() != null;
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

        boolean synced = syncedCameraAvailable
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
        boolean synced = syncedCameraAvailable
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
        if (!syncedCameraAvailable) {
            return;
        }
        boolean activeMatch = active && battleId != null && battleId.equals(syncedCameraBattleId)
                && cameraEntityId == syncedCameraEntityId;
        boolean spectatorMatch = !active && spectatorBattleId != null && spectatorBattleId.equals(syncedCameraBattleId)
                && spectatorCameraEntityId == syncedCameraEntityId;
        if (!activeMatch && !spectatorMatch) {
            return;
        }

        if (activeMatch) {
            activeCameraInterpolation.approach(syncedCameraX, syncedCameraY, syncedCameraZ,
                    syncedCameraYaw, syncedCameraPitch, syncedCameraFov, 0.42D, 0.42F, 0.36F);
            BattleCameraInterpolation.Sample current = activeCameraInterpolation.sample(1.0F);
            cameraYaw = current.yaw();
            cameraPitch = current.pitch();
            cameraFov = current.fov();
        } else {
            spectatorCameraYaw = Mth.rotLerp(0.42F, spectatorCameraYaw, syncedCameraYaw);
            spectatorCameraPitch = Mth.lerp(0.42F, spectatorCameraPitch, syncedCameraPitch);
            spectatorCameraFov = Mth.lerp(0.36F, spectatorCameraFov, syncedCameraFov);
            Vec3 target = new Vec3(syncedCameraX, syncedCameraY, syncedCameraZ);
            spectatorCameraViewPosition = spectatorCameraEnabled && spectatorCameraViewPositionValid
                    ? lerpPosition(spectatorCameraViewPosition, target, 0.42D) : target;
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
        if (!active || !syncedCameraAvailable || !activeCameraInterpolation.valid()
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
        return musicClockStartTick + Math.max(0L, now - startedAt) / 50_000_000.0F;
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
        long now = spectatorMusicPausedAtNanos > 0L ? spectatorMusicPausedAtNanos : System.nanoTime();
        return spectatorMusicClockStartTick + Math.max(0L, now - startedAt) / 50_000_000.0F;
    }

    private static ClientTrackEffects.EffectState sampleSpectatorEffects(float partial) {
        return djTransitions.attach(ClientTrackEffects.sample(spectatorEffects, spectatorVisualTick(partial),
                spectatorTrackBpm, spectatorPlaybackSpeed, spectatorFxVisibility(partial)), System.nanoTime());
    }

    private static void resetMusicClock() {
        musicStartedAtNanos = 0L;
        musicPausedAtNanos = 0L;
        musicClockStartTick = 0;
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
                if (spectatorLaneFlashes[owner][lane] > 0) {
                    spectatorLaneFlashes[owner][lane]--;
                }
                if (spectatorHitBursts[owner][lane] > 0) {
                    spectatorHitBursts[owner][lane]--;
                }
            }
        }
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
        spectatorMusicPausedAtNanos = 0L;
        spectatorMusicPausedForGame = false;
        spectatorTrackBpm = 120;
        spectatorNotes.clear();
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
            for (int lane = 0; lane < 4; lane++) {
                spectatorHitBursts[owner][lane] = 0;
            }
        }
    }

    private static void triggerPlayerAnimation(boolean spectator, int owner, int lane) {
        int safeOwner = Mth.clamp(owner, 0, 1);
        int safeLane = Mth.clamp(lane, 0, 3);
        int[] serials = spectator ? spectatorAnimationTriggerSerial : animationTriggerSerial;
        int[] lanes = spectator ? spectatorAnimationTriggerLane : animationTriggerLane;
        float[] triggerTicks = spectator ? spectatorAnimationTriggerTick : animationTriggerTick;
        serials[safeOwner]++;
        lanes[safeOwner] = safeLane;
        triggerTicks[safeOwner] = spectator ? spectatorVisualTick(0.0F) : activeVisualTick(0.0F);
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
                globalVisibility);
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
        if (!soloView && !highlightApplies(highlight, sinceBeatOne(state.trackTick()), state.bpm(),
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

    private static void drawCenteredFittedString(GuiGraphics graphics, Font font, String text, int centerX, int y, int maxWidth, int color) {
        int textWidth = Math.max(1, font.width(text));
        float scale = Math.min(1.0F, maxWidth / (float) textWidth);
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
        drawQuad(matrix, -0.62F, -0.58F, 0.62F, 0.58F, argb(255, 4, 7, 12));
        drawQuad(matrix, -0.60F, -0.56F, 0.60F, 0.56F, argb(255, 11, 15, 20));
        drawQuad(matrix, -0.60F, -0.56F, 0.60F, 0.56F, withAlpha(color, 58));
        float progress = Mth.clamp(Math.max(0.0F, time) / Math.max(1.0F, duration), 0.0F, 1.0F);
        drawQuad(matrix, -0.56F, -0.50F, -0.56F + 1.12F * progress, -0.47F, withAlpha(color, 230));

        float laneWidth = 0.24F;
        float availableLaneWidth = laneWidth - 0.03F;
        float firstLaneX = -0.48F;
        NoteAppearance appearance = spectatorNoteAppearances[owner];
        for (int lane = 0; lane < 4; lane++) {
            float laneX = firstLaneX + lane * laneWidth;
            int laneColor = appearance.color(lane);
            int laneAlpha = flashes[owner][lane] > 0 ? 165 + flashes[owner][lane] * 8 : 72;
            drawQuad(matrix, laneX, -0.40F, laneX + laneWidth - 0.03F, 0.40F, withAlpha(laneColor, laneAlpha));
            drawWorldNoteShape(matrix, laneX + 0.105F, 0.34F, availableLaneWidth, 0.085F, 0.045F, appearance.skin(), lane, withAlpha(laneColor, 225));
            int burst = bursts[owner][lane];
            if (burst > 0) {
                float pulse = burst / 8.0F;
                float spread = 0.015F + (1.0F - pulse) * 0.035F;
                drawWorldNoteShape(matrix, laneX + 0.105F, 0.34F, availableLaneWidth + spread * 2.0F, 0.095F + spread, 0.050F + spread, appearance.skin(), lane, withAlpha(lighten(laneColor, 255), Math.round(245 * pulse)));
            }
        }

        int leadTicks = NoteSpeed.leadTicks(speed);
        float spawnY = worldNoteSpawnY(appearance.skin());
        for (ClientNote note : noteList) {
            if (note.resolved || note.owner != owner) {
                continue;
            }
            if (!noteVisibleAt(time, note.tick, note.durationTicks, leadTicks)) {
                continue;
            }
            float laneX = firstLaneX + note.lane * laneWidth;
            int laneColor = appearance.color(note.lane);
            float noteY = note.started ? 0.34F : noteTravelY(time, note.tick, leadTicks, spawnY, 0.34F, 0.67F);
            if (note.durationTicks > 0) {
                float tailY = noteTravelY(time, note.tick + note.durationTicks, leadTicks, spawnY, 0.34F, 0.67F);
                float top = Math.min(noteY, tailY);
                float bottom = Math.max(noteY, tailY);
                drawQuad(matrix, laneX + 0.08F, top, laneX + 0.13F, bottom, withAlpha(laneColor, 230));
            }
            if (!note.started) {
                float noteHalf = 0.035F;
                drawWorldNoteShape(matrix, laneX + 0.105F, noteY, availableLaneWidth, 0.075F, noteHalf + 0.005F, appearance.skin(), note.lane, withAlpha(laneColor, 245));
                drawWorldNoteShape(matrix, laneX + 0.105F, noteY, availableLaneWidth * 0.60F, 0.045F, noteHalf * 0.5F, appearance.skin(), note.lane, lighten(laneColor, 245));
            }
        }

        drawWorldPanelStats(pose, score, combo, color);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        pose.popPose();
    }

    private static float worldNoteSpawnY(int skin) {
        // Full-width figures need room above their center to clear the progress strip at -0.47.
        return skin == NoteAppearance.BARS ? -0.38F : -0.34F;
    }

    private static void drawWorldNoteShape(Matrix4f matrix, float centerX, float centerY, float availableLaneWidth, float halfWidth,
                                           float halfHeight, int skin, int lane, int color) {
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        emitWorldNoteShape(buffer, matrix, centerX, centerY, availableLaneWidth, halfWidth, halfHeight, skin, lane, color);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void emitWorldNoteShape(BufferBuilder buffer, Matrix4f matrix, float centerX, float centerY,
                                           float availableLaneWidth, float barHalfWidth, float barHalfHeight,
                                           int skin, int lane, int color) {
        // Non-bar heads and receptors fill 93% of the lane; compensate any unequal X/Y pose scale.
        float halfWidth = skin == NoteAppearance.BARS ? barHalfWidth : availableLaneWidth * 0.465F;
        float basisX = (float)Math.sqrt(matrix.m00()*matrix.m00() + matrix.m01()*matrix.m01() + matrix.m02()*matrix.m02());
        float basisY = (float)Math.sqrt(matrix.m10()*matrix.m10() + matrix.m11()*matrix.m11() + matrix.m12()*matrix.m12());
        float halfHeight = skin == NoteAppearance.BARS ? barHalfHeight : halfWidth * (basisY > 0.00001F ? basisX / basisY : 1.0F);
        BattleNoteShape.drawTriangles((x1, y1, x2, y2, x3, y3, fillColor) -> {
            buffer.addVertex(matrix, x1, y1, 0.0F).setColor(fillColor);
            buffer.addVertex(matrix, x2, y2, 0.0F).setColor(fillColor);
            buffer.addVertex(matrix, x3, y3, 0.0F).setColor(fillColor);
            buffer.addVertex(matrix, x3, y3, 0.0F).setColor(fillColor);
        }, skin, lane, centerX-halfWidth, centerY-halfHeight, centerX+halfWidth, centerY+halfHeight, color);
    }

    private static void drawWorldPanelStats(PoseStack pose, int score, int combo, int color) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        String scoreText = "SCORE " + Math.max(0, score);
        String comboText = "COMBO x" + Math.max(0, combo);
        pose.pushPose();
        pose.translate(0.0F, -0.72F, 0.01F);
        pose.scale(0.018F, 0.018F, 0.018F);
        font.drawInBatch(scoreText, -font.width(scoreText) / 2.0F, 0.0F, 0xFFFFFFFF, false,
                pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0, 0x00F000F0);
        font.drawInBatch(comboText, -font.width(comboText) / 2.0F, -11.0F, lighten(color, 255), false,
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
