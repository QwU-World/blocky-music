package ru.gora.musicbattle.network;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.server.CameraEditorManager;
import ru.gora.musicbattle.server.RapGodConfigHandler;
import ru.gora.musicbattle.server.TrackPublicationManager;
import ru.gora.musicbattle.server.DjPlaybackManager;
import ru.gora.musicbattle.server.workshop.ServerBeatmapTransfers;
import ru.gora.musicbattle.server.workshop.ServerWorkshop;

public final class MusicBattleNetworking {
    private MusicBattleNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("67");
        registrar.playToClient(OpenVotePayload.TYPE, OpenVotePayload.STREAM_CODEC, MusicBattleNetworking::handleOpenVote);
        registrar.playToClient(TrackPreloadPayload.TYPE, TrackPreloadPayload.STREAM_CODEC, MusicBattleNetworking::handleTrackPreload);
        registrar.playToClient(TrackAssetChunkPayload.TYPE, TrackAssetChunkPayload.STREAM_CODEC, MusicBattleNetworking::handleTrackAssetChunk);
        registrar.playToClient(BattleStartPayload.TYPE, BattleStartPayload.STREAM_CODEC, MusicBattleNetworking::handleBattleStart);
        registrar.playToClient(BattleStopPayload.TYPE, BattleStopPayload.STREAM_CODEC, MusicBattleNetworking::handleBattleStop);
        registrar.playToClient(BattleActionPayload.TYPE, BattleActionPayload.STREAM_CODEC, MusicBattleNetworking::handleBattleAction);
        registrar.playToClient(BattleLaneAnimationPayload.TYPE, BattleLaneAnimationPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.acceptBattleLaneAnimation(payload)));
        registrar.playToClient(BattleAppearancePayload.TYPE, BattleAppearancePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.acceptBattleAppearance(payload)));
        registrar.playToClient(BattleCameraSyncPayload.TYPE, BattleCameraSyncPayload.STREAM_CODEC, MusicBattleNetworking::handleBattleCameraSync);
        registrar.playToClient(DjAudienceCameraPayload.TYPE, DjAudienceCameraPayload.STREAM_CODEC, MusicBattleNetworking::handleDjAudienceCamera);
        registrar.playToClient(SpectatorMusicPayload.TYPE, SpectatorMusicPayload.STREAM_CODEC, MusicBattleNetworking::handleSpectatorMusic);
        registrar.playToClient(SpectatorBattlePayload.TYPE, SpectatorBattlePayload.STREAM_CODEC, MusicBattleNetworking::handleSpectatorBattle);
        registrar.playToClient(SpectatorStopPayload.TYPE, SpectatorStopPayload.STREAM_CODEC, MusicBattleNetworking::handleSpectatorStop);
        registrar.playToClient(BattleAudiencePayload.TYPE, BattleAudiencePayload.STREAM_CODEC, MusicBattleNetworking::handleBattleAudience);
        registrar.playToClient(VoteStatusPayload.TYPE, VoteStatusPayload.STREAM_CODEC, MusicBattleNetworking::handleVoteStatus);
        registrar.playToClient(PauseSkipStatusPayload.TYPE, PauseSkipStatusPayload.STREAM_CODEC, MusicBattleNetworking::handlePauseSkipStatus);
        registrar.playToClient(OpenChallengeSidePayload.TYPE, OpenChallengeSidePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.openChallengeSide(payload)));
        registrar.playToClient(BattleMicrophonePayload.TYPE, BattleMicrophonePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.acceptBattleMicrophone(payload)));
        registrar.playToClient(OpenRapGodConfigPayload.TYPE, OpenRapGodConfigPayload.STREAM_CODEC, MusicBattleNetworking::handleOpenRapGodConfig);
        registrar.playToClient(OpenCameraEditorPayload.TYPE, OpenCameraEditorPayload.STREAM_CODEC, MusicBattleNetworking::handleOpenCameraEditor);
        registrar.playToClient(CameraEditorClosedPayload.TYPE, CameraEditorClosedPayload.STREAM_CODEC, MusicBattleNetworking::handleCameraEditorClosed);
        registrar.playToClient(OpenSpeakerConfigPayload.TYPE, OpenSpeakerConfigPayload.STREAM_CODEC, MusicBattleNetworking::handleOpenSpeakerConfig);
        registrar.playToClient(OpenDjPanelPayload.TYPE, OpenDjPanelPayload.STREAM_CODEC, MusicBattleNetworking::handleOpenDjPanel);
        registrar.playToClient(DjCameraPreviewPayload.TYPE, DjCameraPreviewPayload.STREAM_CODEC, MusicBattleNetworking::handleDjCameraPreview);
        registrar.playToClient(DjPreloadOfferPayload.TYPE, DjPreloadOfferPayload.STREAM_CODEC, MusicBattleNetworking::handleDjPreloadOffer);
        registrar.playToClient(PublishTrackResultPayload.TYPE, PublishTrackResultPayload.STREAM_CODEC, MusicBattleNetworking::handlePublishTrackResult);
        registrar.playToClient(DeletePublishedTrackResultPayload.TYPE, DeletePublishedTrackResultPayload.STREAM_CODEC, MusicBattleNetworking::handleDeletePublishedTrackResult);
        registrar.playToClient(PublishedTracksPayload.TYPE, PublishedTracksPayload.STREAM_CODEC, MusicBattleNetworking::handlePublishedTracks);
        registrar.playToServer(VoteTrackPayload.TYPE, VoteTrackPayload.STREAM_CODEC, MusicBattleNetworking::handleVoteTrack);
        registrar.playToServer(PauseSkipVotePayload.TYPE, PauseSkipVotePayload.STREAM_CODEC, MusicBattleNetworking::handlePauseSkipVote);
        registrar.playToServer(ChallengeSidePayload.TYPE, ChallengeSidePayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) BattleManager.challengeWithSide(player, payload.targetId(), payload.challengerFirst()); }));
        registrar.playToServer(NoteInputPayload.TYPE, NoteInputPayload.STREAM_CODEC, MusicBattleNetworking::handleNoteInput);
        registrar.playToServer(ClientJudgementPayload.TYPE, ClientJudgementPayload.STREAM_CODEC, MusicBattleNetworking::handleClientJudgement);
        registrar.playToServer(BattleClientErrorPayload.TYPE, BattleClientErrorPayload.STREAM_CODEC, MusicBattleNetworking::handleBattleClientError);
        registrar.playToServer(SettingsPayload.TYPE, SettingsPayload.STREAM_CODEC, MusicBattleNetworking::handleSettings);
        registrar.playToServer(SurrenderPayload.TYPE, SurrenderPayload.STREAM_CODEC, MusicBattleNetworking::handleSurrender);
        registrar.playToServer(TrackReadyPayload.TYPE, TrackReadyPayload.STREAM_CODEC, MusicBattleNetworking::handleTrackReady);
        registrar.playToServer(TrackPreviewRequestPayload.TYPE, TrackPreviewRequestPayload.STREAM_CODEC, MusicBattleNetworking::handleTrackPreviewRequest);
        registrar.playToServer(ConfigureRapGodPayload.TYPE, ConfigureRapGodPayload.STREAM_CODEC, MusicBattleNetworking::handleConfigureRapGod);
        registrar.playToServer(ConfigureSpeakerPayload.TYPE, ConfigureSpeakerPayload.STREAM_CODEC, MusicBattleNetworking::handleConfigureSpeaker);
        registrar.playToServer(ConfigureDjPanelPayload.TYPE, ConfigureDjPanelPayload.STREAM_CODEC, MusicBattleNetworking::handleConfigureDjPanel);
        registrar.playToServer(ConfigureDjPlaylistPayload.TYPE, ConfigureDjPlaylistPayload.STREAM_CODEC, MusicBattleNetworking::handleConfigureDjPlaylist);
        registrar.playToServer(DjWorkshopAddPayload.TYPE, DjWorkshopAddPayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) DjPlaybackManager.handleWorkshopAdd(player, payload); }));
        registrar.playToClient(DjWorkshopAddResultPayload.TYPE, DjWorkshopAddResultPayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> ClientPayloadBridge.djWorkshopAddResult(payload)));
        registrar.playToServer(EditDjPlaylistMixerPayload.TYPE, EditDjPlaylistMixerPayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) DjPlaybackManager.editPlaylistMixer(player, payload); }));
        registrar.playToServer(EditDjPlaylistEffectPayload.TYPE, EditDjPlaylistEffectPayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) DjPlaybackManager.editPlaylistEffect(player, payload); }));
        registrar.playToServer(DjTrackPreviewRequestPayload.TYPE, DjTrackPreviewRequestPayload.STREAM_CODEC, MusicBattleNetworking::handleDjTrackPreviewRequest);
        registrar.playToServer(DjCameraPreviewRequestPayload.TYPE, DjCameraPreviewRequestPayload.STREAM_CODEC, MusicBattleNetworking::handleDjCameraPreviewRequest);
        registrar.playToServer(EditDjCameraRequestPayload.TYPE, EditDjCameraRequestPayload.STREAM_CODEC, MusicBattleNetworking::handleEditDjCameraRequest);
        registrar.playToServer(DjAssetReadyPayload.TYPE, DjAssetReadyPayload.STREAM_CODEC, MusicBattleNetworking::handleDjAssetReady);
        registrar.playToServer(DjPreloadAnswerPayload.TYPE, DjPreloadAnswerPayload.STREAM_CODEC, MusicBattleNetworking::handleDjPreloadAnswer);
        registrar.playToServer(UpdateCameraEditorPayload.TYPE, UpdateCameraEditorPayload.STREAM_CODEC, MusicBattleNetworking::handleUpdateCameraEditor);
        registrar.playToServer(CloseCameraEditorPayload.TYPE, CloseCameraEditorPayload.STREAM_CODEC, MusicBattleNetworking::handleCloseCameraEditor);
        registrar.playToServer(PublishTrackBeginPayload.TYPE, PublishTrackBeginPayload.STREAM_CODEC, MusicBattleNetworking::handlePublishTrackBegin);
        registrar.playToServer(PublishTrackChunkPayload.TYPE, PublishTrackChunkPayload.STREAM_CODEC, MusicBattleNetworking::handlePublishTrackChunk);
        registrar.playToServer(PublishTrackFinishPayload.TYPE, PublishTrackFinishPayload.STREAM_CODEC, MusicBattleNetworking::handlePublishTrackFinish);
        registrar.playToServer(DeletePublishedTrackPayload.TYPE, DeletePublishedTrackPayload.STREAM_CODEC, MusicBattleNetworking::handleDeletePublishedTrack);
        registrar.playToServer(RequestPublishedTracksPayload.TYPE, RequestPublishedTracksPayload.STREAM_CODEC, MusicBattleNetworking::handleRequestPublishedTracks);

        // Wave M3: server beatmaps, server comments and the installed-set vote (spec 06 sections 9 and 10).
        registrar.playToClient(WorkshopServerInfoPayload.TYPE, WorkshopServerInfoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.workshopServerInfo(payload)));
        registrar.playToClient(ServerBeatmapsPagePayload.TYPE, ServerBeatmapsPagePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverBeatmapsPage(payload)));
        registrar.playToClient(ServerBeatmapOfferResultPayload.TYPE, ServerBeatmapOfferResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverBeatmapOfferResult(payload)));
        registrar.playToClient(ServerBeatmapTransferAbortPayload.TYPE, ServerBeatmapTransferAbortPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverBeatmapTransferAbort(payload)));
        registrar.playToClient(ServerBeatmapResultPayload.TYPE, ServerBeatmapResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverBeatmapResult(payload)));
        registrar.playToClient(ServerCommentsPagePayload.TYPE, ServerCommentsPagePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverCommentsPage(payload)));
        registrar.playToClient(ServerCommentResultPayload.TYPE, ServerCommentResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverCommentResult(payload)));
        registrar.playToClient(TrackAssetOfferPayload.TYPE, TrackAssetOfferPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.answerTrackAssetOffer(payload)));

        registrar.playToClient(ServerTracksPayload.TYPE, ServerTracksPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPayloadBridge.serverTracks(payload)));
        registrar.playToServer(ServerTracksRequestPayload.TYPE, ServerTracksRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.requestTracks(player);
                }));
        registrar.playToServer(ServerBeatmapsRequestPayload.TYPE, ServerBeatmapsRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.requestPage(player, payload);
                }));
        registrar.playToServer(ServerBeatmapOfferPayload.TYPE, ServerBeatmapOfferPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerBeatmapTransfers.offer(player, payload);
                }));
        registrar.playToServer(ServerBeatmapChunkPayload.TYPE, ServerBeatmapChunkPayload.STREAM_CODEC,
                (payload, context) -> {
                    ServerBeatmapChunkPayload copy = new ServerBeatmapChunkPayload(payload.transferId(),
                            payload.chunkIndex(), payload.data().clone());
                    context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer player) ServerBeatmapTransfers.acceptChunk(player, copy);
                    });
                });
        registrar.playToServer(ServerBeatmapTransferCancelPayload.TYPE, ServerBeatmapTransferCancelPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerBeatmapTransfers.cancel(player, payload.transferId());
                }));
        registrar.playToServer(ServerBeatmapRemovePayload.TYPE, ServerBeatmapRemovePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.remove(player, payload.setId());
                }));
        registrar.playToServer(ServerCommentsRequestPayload.TYPE, ServerCommentsRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.requestComments(player, payload);
                }));
        registrar.playToServer(ServerCommentPostPayload.TYPE, ServerCommentPostPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.postComment(player, payload.key(), payload.text());
                }));
        registrar.playToServer(ServerCommentDeletePayload.TYPE, ServerCommentDeletePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.deleteComment(player, payload.key(), payload.commentId());
                }));
        registrar.playToServer(ServerCommentReportPayload.TYPE, ServerCommentReportPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.reportComment(player, payload.key(), payload.commentId(), payload.reason());
                }));
        registrar.playToServer(VoteInstalledPayload.TYPE, VoteInstalledPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ServerBeatmapTransfers.voteInstalled(player, payload).ifPresent(player::sendSystemMessage);
                    }
                }));
        registrar.playToServer(WorkshopSetStateReportPayload.TYPE, WorkshopSetStateReportPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) ServerWorkshop.acceptStateReport(player, payload);
                }));
        registrar.playToServer(TrackAssetAnswerPayload.TYPE, TrackAssetAnswerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) BattleManager.confirmTrackAssetAnswer(player, payload);
                }));
    }

    private static void handleOpenVote(OpenVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.openVote(payload));
    }

    private static void handleOpenRapGodConfig(OpenRapGodConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.openRapGodConfig(payload));
    }

    private static void handleOpenCameraEditor(OpenCameraEditorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.openCameraEditor(payload));
    }

    private static void handleCameraEditorClosed(CameraEditorClosedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.closeCameraEditor(payload));
    }

    private static void handleOpenSpeakerConfig(OpenSpeakerConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.openSpeakerConfig(payload));
    }

    private static void handleOpenDjPanel(OpenDjPanelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.openDjPanel(payload));
    }

    private static void handleDjCameraPreview(DjCameraPreviewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.acceptDjCameraPreview(payload));
    }

    private static void handlePublishTrackResult(PublishTrackResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.publishTrackResult(payload));
    }

    private static void handleDeletePublishedTrackResult(DeletePublishedTrackResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.deletePublishedTrackResult(payload));
    }

    private static void handlePublishedTracks(PublishedTracksPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.publishedTracks(payload));
    }

    private static void handleTrackPreload(TrackPreloadPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.preloadTrack(payload));
    }

    private static void handleTrackAssetChunk(TrackAssetChunkPayload payload, IPayloadContext context) {
        byte[] data = payload.data().clone();
        context.enqueueWork(() -> ClientPayloadBridge.acceptTrackAssetChunk(
                payload.trackId(),
                payload.assetKind(),
                payload.fileName(),
                payload.chunkIndex(),
                payload.totalChunks(),
                payload.totalBytes(),
                payload.sha256(),
                data
        ));
    }

    private static void handleBattleStart(BattleStartPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.startBattle(payload));
    }

    private static void handleBattleStop(BattleStopPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.stopBattle(payload));
    }

    private static void handleBattleAction(BattleActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.acceptBattleAction(payload));
    }

    private static void handleBattleCameraSync(BattleCameraSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.acceptBattleCameraSync(payload));
    }

    private static void handleDjAudienceCamera(DjAudienceCameraPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.acceptDjAudienceCamera(payload));
    }

    private static void handleSpectatorMusic(SpectatorMusicPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.playSpectatorMusic(payload));
    }

    private static void handleSpectatorBattle(SpectatorBattlePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.startSpectatorBattle(payload));
    }

    private static void handleSpectatorStop(SpectatorStopPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.stopSpectating(payload));
    }

    private static void handleBattleAudience(BattleAudiencePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.acceptBattleAudience(payload));
    }

    private static void handleVoteStatus(VoteStatusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.updateVoteStatus(payload));
    }

    private static void handlePauseSkipStatus(PauseSkipStatusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.updatePauseSkipStatus(payload));
    }

    private static void handleVoteTrack(VoteTrackPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.submitVote(context.player(), payload.battleId(), payload.trackId(), payload.mobSkill(), payload.unifiedNotes(), payload.deadlyParty(), payload.speedMultiplier()));
    }

    private static void handlePauseSkipVote(PauseSkipVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.submitPauseSkipVote(context.player(), payload.battleId()));
    }

    private static void handleNoteInput(NoteInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.submitInput(context.player(), payload.battleId(), payload.lane(), payload.clientTick(), payload.down(), payload.noteIndex()));
    }

    private static void handleClientJudgement(ClientJudgementPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.submitClientJudgement(context.player(), payload));
    }

    private static void handleBattleClientError(BattleClientErrorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.reportClientError(context.player(), payload.battleId(), payload.reason()));
    }

    private static void handleSettings(SettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.updateSettings(
                context.player(),
                payload.noteSpeed(),
                payload.panelColor(),
                payload.panelOpacity(),
                payload.laneOpacity(),
                payload.panelWidthScale(),
                payload.panelHeightScale(),
                payload.noteWidthScale(),
                payload.noteHeightScale(),
                payload.keyLabels(),
                payload.appearance()
        ));
    }

    private static void handleSurrender(SurrenderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.surrender(context.player(), payload.battleId()));
    }

    private static void handleTrackReady(TrackReadyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.confirmTrackReady(context.player(), payload.battleId(), payload.trackId(), payload.chartFile(), payload.audioFile()));
    }

    private static void handleTrackPreviewRequest(TrackPreviewRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BattleManager.requestTrackPreviews(context.player(), payload.battleId(), payload.trackIds()));
    }

    private static void handleConfigureRapGod(ConfigureRapGodPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RapGodConfigHandler.configure(context.player(), payload));
    }

    private static void handleConfigureSpeaker(ConfigureSpeakerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
                    && player.level().hasChunkAt(payload.pos())
                    && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(payload.pos())) <= 64.0D
                    && player.level().getBlockEntity(payload.pos()) instanceof ru.gora.musicbattle.block.entity.SpeakerBlockEntity speaker
                    && !speaker.isDjControlled()) {
                speaker.setSettings(payload.radius(), payload.volume(),
                        payload.lowGainDb(), payload.midGainDb(), payload.highGainDb());
            }
        });
    }

    private static void handleConfigureDjPanel(ConfigureDjPanelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                DjPlaybackManager.handleAction(player, payload);
            }
        });
    }

    private static void handleConfigureDjPlaylist(ConfigureDjPlaylistPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                DjPlaybackManager.handlePlaylistAction(player, payload);
            }
        });
    }

    private static void handleDjTrackPreviewRequest(DjTrackPreviewRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                DjPlaybackManager.requestTrackPreviews(player, payload.panelPos(), payload.trackIds());
            }
        });
    }

    private static void handleDjCameraPreviewRequest(DjCameraPreviewRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                DjPlaybackManager.requestCameraPreview(player, payload.panelPos(), payload.cameraPos());
            }
        });
    }

    private static void handleEditDjCameraRequest(EditDjCameraRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                CameraEditorManager.startFromDjPanel(player, payload.panelPos(), payload.cameraPos());
            }
        });
    }

    private static void handleDjAssetReady(DjAssetReadyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                DjPlaybackManager.confirmAssetReady(player, payload);
            }
        });
    }

    private static void handleDjPreloadOffer(DjPreloadOfferPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadBridge.answerDjPreloadOffer(payload));
    }

    private static void handleDjPreloadAnswer(DjPreloadAnswerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                DjPlaybackManager.confirmPreloadAnswer(player, payload);
            }
        });
    }

    private static void handleUpdateCameraEditor(UpdateCameraEditorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> CameraEditorManager.update(context.player(), payload));
    }

    private static void handleCloseCameraEditor(CloseCameraEditorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> CameraEditorManager.close(context.player(), payload));
    }

    private static void handlePublishTrackBegin(PublishTrackBeginPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                TrackPublicationManager.begin(player, payload);
            }
        });
    }

    private static void handlePublishTrackChunk(PublishTrackChunkPayload payload, IPayloadContext context) {
        byte[] data = payload.data().clone();
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                TrackPublicationManager.acceptChunk(player, payload.uploadId(), payload.assetKind(), payload.chunkIndex(), data);
            }
        });
    }

    private static void handlePublishTrackFinish(PublishTrackFinishPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                TrackPublicationManager.finish(player, payload.uploadId());
            }
        });
    }

    private static void handleDeletePublishedTrack(DeletePublishedTrackPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                TrackPublicationManager.deletePublished(player, payload.trackId());
            }
        });
    }

    private static void handleRequestPublishedTracks(RequestPublishedTracksPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                TrackPublicationManager.sendPublishedTracks(player);
            }
        });
    }
}
