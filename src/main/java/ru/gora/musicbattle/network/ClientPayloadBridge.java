package ru.gora.musicbattle.network;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;

public final class ClientPayloadBridge {
    private ClientPayloadBridge() {
    }

    public static void openVote(OpenVotePayload payload) {
        invokeClient("openVote", new Class<?>[]{OpenVotePayload.class}, payload);
    }

    public static void openRapGodConfig(OpenRapGodConfigPayload payload) {
        invokeClient("openRapGodConfig", new Class<?>[]{OpenRapGodConfigPayload.class}, payload);
    }

    public static void openCameraEditor(OpenCameraEditorPayload payload) {
        invokeClient("openCameraEditor", new Class<?>[]{OpenCameraEditorPayload.class}, payload);
    }

    public static void closeCameraEditor(CameraEditorClosedPayload payload) {
        invokeClient("closeCameraEditor", new Class<?>[]{CameraEditorClosedPayload.class}, payload);
    }

    public static void acceptCameraFollowState(CameraFollowStatePayload payload) {
        invokeClient("acceptCameraFollowState", new Class<?>[]{CameraFollowStatePayload.class}, payload);
    }

    public static void openSpeakerConfig(OpenSpeakerConfigPayload payload) {
        invokeClient("openSpeakerConfig", new Class<?>[]{OpenSpeakerConfigPayload.class}, payload);
    }

    public static void openDjPanel(OpenDjPanelPayload payload) {
        invokeClient("openDjPanel", new Class<?>[]{OpenDjPanelPayload.class}, payload);
    }

    /**
     * The DJ panel's own answer, so it goes straight to the screen that asked rather than through the battle
     * controller, which has no part in it.
     */
    public static void djWorkshopAddResult(DjWorkshopAddResultPayload payload) {
        try {
            Class.forName("ru.gora.musicbattle.client.DjPanelScreen")
                    .getMethod("acceptWorkshopAddResult", DjWorkshopAddResultPayload.class).invoke(null, payload);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            MusicBattleMod.LOGGER.warn("Unable to dispatch the DJ Workshop answer.", exception);
        }
    }

    public static void acceptDjCameraPreview(DjCameraPreviewPayload payload) {
        invokeClient("acceptDjCameraPreview", new Class<?>[]{DjCameraPreviewPayload.class}, payload);
    }

    public static void publishTrackResult(PublishTrackResultPayload payload) {
        invokeClient("acceptPublishResult", new Class<?>[]{PublishTrackResultPayload.class}, payload);
    }

    public static void deletePublishedTrackResult(DeletePublishedTrackResultPayload payload) {
        invokeClient("acceptDeletePublishedTrackResult", new Class<?>[]{DeletePublishedTrackResultPayload.class}, payload);
    }

    public static void publishedTracks(PublishedTracksPayload payload) {
        invokeClient("acceptPublishedTracks", new Class<?>[]{PublishedTracksPayload.class}, payload);
    }

    public static void preloadTrack(TrackPreloadPayload payload) {
        invokeClient("preloadTrack", new Class<?>[]{TrackPreloadPayload.class}, payload);
    }

    public static void startBattle(BattleStartPayload payload) {
        invokeClient("start", new Class<?>[]{BattleStartPayload.class}, payload);
    }

    public static void stopBattle(BattleStopPayload payload) {
        invokeClient("stop", new Class<?>[]{BattleStopPayload.class}, payload);
    }

    public static void acceptBattleAction(BattleActionPayload payload) {
        invokeClient("acceptAction", new Class<?>[]{BattleActionPayload.class}, payload);
    }

    public static void acceptBattleLaneAnimation(BattleLaneAnimationPayload payload) {
        invokeClient("acceptLaneAnimation", new Class<?>[]{BattleLaneAnimationPayload.class}, payload);
    }

    public static void acceptBattleAppearance(BattleAppearancePayload payload) {
        invokeClient("acceptBattleAppearance", new Class<?>[]{BattleAppearancePayload.class}, payload);
    }

    public static void acceptBattleCameraSync(BattleCameraSyncPayload payload) {
        invokeClient("acceptBattleCameraSync", new Class<?>[]{BattleCameraSyncPayload.class}, payload);
    }

    public static void acceptDjAudienceCamera(DjAudienceCameraPayload payload) {
        invokeClient("acceptDjAudienceCamera", new Class<?>[]{DjAudienceCameraPayload.class}, payload);
    }

    public static void playSpectatorMusic(SpectatorMusicPayload payload) {
        invokeClient("playSpectatorMusic", new Class<?>[]{SpectatorMusicPayload.class}, payload);
    }

    public static void startSpectatorBattle(SpectatorBattlePayload payload) {
        invokeClient("startSpectatorBattle", new Class<?>[]{SpectatorBattlePayload.class}, payload);
    }

    public static void stopSpectating(SpectatorStopPayload payload) {
        invokeClient("stopSpectating", new Class<?>[]{SpectatorStopPayload.class}, payload);
    }

    public static void acceptBattleAudience(BattleAudiencePayload payload) {
        invokeClient("acceptBattleAudience", new Class<?>[]{BattleAudiencePayload.class}, payload);
    }

    public static void updateVoteStatus(VoteStatusPayload payload) {
        invokeClient("updateVoteStatus", new Class<?>[]{VoteStatusPayload.class}, payload);
    }

    public static void updatePauseSkipStatus(PauseSkipStatusPayload payload) {
        invokeClient("updatePauseSkipStatus", new Class<?>[]{PauseSkipStatusPayload.class}, payload);
    }

    public static void acceptPauseSkipJump(PauseSkipJumpPayload payload) {
        invokeClient("acceptPauseSkipJump", new Class<?>[]{PauseSkipJumpPayload.class}, payload);
    }

    public static void openChallengeSide(OpenChallengeSidePayload payload) {
        invokeClient("openChallengeSide", new Class<?>[]{OpenChallengeSidePayload.class}, payload);
    }

    public static void acceptBattleMicrophone(BattleMicrophonePayload payload) {
        invokeClient("acceptBattleMicrophone", new Class<?>[]{BattleMicrophonePayload.class}, payload);
    }

    public static void acceptTrackAssetChunk(String trackId, String assetKind, String fileName, int chunkIndex, int totalChunks, int totalBytes, String sha256, byte[] data) {
        invokeClient(
                "acceptTrackAssetChunk",
                new Class<?>[]{String.class, String.class, String.class, int.class, int.class, int.class, String.class, byte[].class},
                trackId,
                assetKind,
                fileName,
                chunkIndex,
                totalChunks,
                totalBytes,
                sha256,
                data
        );
    }

    /**
     * Answers a preload offer straight from the local cache. Unlike the acknowledgement of the
     * active song, this answer needs no battle/spectator session state, so the lookup itself is
     * the whole answer and stays on the bridge that already owns the hop to client-only classes.
     */
    public static void answerDjPreloadOffer(DjPreloadOfferPayload payload) {
        PacketDistributor.sendToServer(new DjPreloadAnswerPayload(
                payload.panelPos(), payload.trackId(), payload.fileName(), payload.sha256(),
                hasCachedTrackAudio(payload.trackId(), payload.fileName(), payload.sha256())));
    }

    private static boolean hasCachedTrackAudio(String trackId, String fileName, String sha256) {
        try {
            Class<?> assets = Class.forName("ru.gora.musicbattle.client.ClientTrackAssets");
            Object cached = assets.getMethod("cachedAsset", String.class, String.class, String.class)
                    .invoke(null, trackId, fileName, sha256);
            return cached instanceof Optional<?> optional && optional.isPresent();
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException exception) {
            MusicBattleMod.LOGGER.warn("Unable to inspect the Blocky Music track cache.", exception);
            return false;
        }
    }

    // ---------------------------------------------------------------- workshop

    public static void workshopServerInfo(WorkshopServerInfoPayload payload) {
        invokeWorkshop("acceptServerInfo", new Class<?>[]{WorkshopServerInfoPayload.class}, payload);
    }

    public static void serverBeatmapsPage(ServerBeatmapsPagePayload payload) {
        invokeWorkshop("acceptServerBeatmapsPage", new Class<?>[]{ServerBeatmapsPagePayload.class}, payload);
    }

    public static void serverTracks(ServerTracksPayload payload) {
        invokeWorkshop("acceptServerTracks", new Class<?>[]{ServerTracksPayload.class}, payload);
    }

    public static void serverBeatmapOfferResult(ServerBeatmapOfferResultPayload payload) {
        invokeWorkshop("acceptServerBeatmapOfferResult", new Class<?>[]{ServerBeatmapOfferResultPayload.class}, payload);
    }

    public static void serverBeatmapTransferAbort(ServerBeatmapTransferAbortPayload payload) {
        invokeWorkshop("acceptServerBeatmapAbort", new Class<?>[]{ServerBeatmapTransferAbortPayload.class}, payload);
    }

    public static void serverBeatmapResult(ServerBeatmapResultPayload payload) {
        invokeWorkshop("acceptServerBeatmapResult", new Class<?>[]{ServerBeatmapResultPayload.class}, payload);
    }

    public static void serverCommentsPage(ServerCommentsPagePayload payload) {
        invokeWorkshop("acceptServerCommentsPage", new Class<?>[]{ServerCommentsPagePayload.class}, payload);
    }

    public static void serverCommentResult(ServerCommentResultPayload payload) {
        invokeWorkshop("acceptServerCommentResult", new Class<?>[]{ServerCommentResultPayload.class}, payload);
    }

    /**
     * Answers a battle asset offer from the client's hash index only: no file is hashed
     * and no cache lookup that could touch the disk runs on this thread.
     */
    public static void answerTrackAssetOffer(TrackAssetOfferPayload payload) {
        invokeWorkshop("answerTrackAssetOffer", new Class<?>[]{TrackAssetOfferPayload.class}, payload);
    }

    private static void invokeClient(String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
            controller.getMethod(method, parameterTypes).invoke(null, args);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            MusicBattleMod.LOGGER.warn("Unable to dispatch client music battle payload '{}'.", method, exception);
        }
    }

    /**
     * The second owner of client-only handlers: everything the Workshop screens answer lives on
     * {@code client.workshop.WorkshopClientHooks}, so the battle controller keeps its own surface. The
     * {@code client-payload-bridge} check proves both owners' methods exist with these exact parameter types.
     */
    private static void invokeWorkshop(String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> hooks = Class.forName("ru.gora.musicbattle.client.workshop.WorkshopClientHooks");
            hooks.getMethod(method, parameterTypes).invoke(null, args);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            MusicBattleMod.LOGGER.warn("Unable to dispatch client workshop payload '{}'.", method, exception);
        }
    }
}
