package ru.gora.musicbattle.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.network.CameraFollowModePayload;
import ru.gora.musicbattle.network.CameraFollowStatePayload;
import ru.gora.musicbattle.network.CloseCameraEditorPayload;
import ru.gora.musicbattle.network.CameraEditorClosedPayload;
import ru.gora.musicbattle.network.OpenCameraEditorPayload;
import ru.gora.musicbattle.network.UpdateCameraEditorPayload;
import ru.gora.musicbattle.registry.ModBlocks;
import ru.gora.musicbattle.data.CameraCollision;

public final class CameraEditorManager {
    private static final double MAX_INTERACTION_DISTANCE_SQR = 8.0D * 8.0D;
    private static final int MAX_UPDATES_PER_TICK = 2;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private CameraEditorManager() {
    }

    public static void start(ServerPlayer player, BlockPos blockPos, BattleCameraBlockEntity blockEntity) {
        if (blockEntity.isRemoved()
                || blockEntity.getLevel() != player.level()
                || !player.level().hasChunkAt(blockPos)
                || !player.level().getBlockState(blockPos).is(ModBlocks.BATTLE_CAMERA.get())
                || player.distanceToSqr(Vec3.atCenterOf(blockPos)) > MAX_INTERACTION_DISTANCE_SQR) {
            return;
        }
        startValidated(player, blockPos, blockEntity, null);
    }

    /** Starts the same editor remotely, but only through a nearby panel that explicitly links the camera. */
    public static void startFromDjPanel(ServerPlayer player, BlockPos panelPos, BlockPos cameraPos) {
        if (panelPos == null || cameraPos == null
                || !player.level().hasChunkAt(panelPos)
                || !player.level().hasChunkAt(cameraPos)
                || player.distanceToSqr(Vec3.atCenterOf(panelPos)) > MAX_INTERACTION_DISTANCE_SQR
                || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)
                || !panel.getLinkedCameras().contains(cameraPos)
                || !(player.level().getBlockEntity(cameraPos) instanceof BattleCameraBlockEntity camera)
                || !player.level().getBlockState(cameraPos).is(ModBlocks.BATTLE_CAMERA.get())) {
            return;
        }
        startValidated(player, cameraPos, camera, panelPos.immutable());
    }

    private static void startValidated(ServerPlayer player, BlockPos blockPos,
                                       BattleCameraBlockEntity blockEntity, BlockPos controlPanelPos) {
        if (BattleManager.isEntityInBattle(player)) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.camera_battle_lock").withStyle(ChatFormatting.RED));
            return;
        }
        if (SESSIONS.values().stream().anyMatch(session -> session.blockPos.equals(blockPos) && session.player != player)) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.camera_editor_busy").withStyle(ChatFormatting.RED));
            return;
        }
        finish(player, false);

        ServerLevel level = player.serverLevel();
        Vec3 position = blockEntity.getCameraPosition();
        ArmorStand camera = new ArmorStand(level, position.x, position.y, position.z);
        TemporaryBattleEntities.mark(camera);
        camera.setInvisible(true);
        camera.setNoGravity(true);
        camera.setInvulnerable(true);
        camera.setSilent(true);
        camera.absMoveTo(position.x, position.y, position.z, blockEntity.getCameraYaw(), blockEntity.getCameraPitch());
        level.addFreshEntity(camera);

        Session session = new Session(
                player,
                blockPos.immutable(),
                camera,
                player.position(),
                player.getYRot(),
                player.getXRot(),
                controlPanelPos
        );
        SESSIONS.put(player.getUUID(), session);
        player.setCamera(camera);
        sendEditorState(session, blockEntity, false);
        PacketDistributor.sendToPlayer(player, new CameraFollowStatePayload(session.blockPos, blockEntity.isFollowPlayers()));
    }

    private static void sendEditorState(Session session, BattleCameraBlockEntity blockEntity, boolean resetResponse) {
        Vec3 position = blockEntity.getCameraPosition();
        PacketDistributor.sendToPlayer(session.player, new OpenCameraEditorPayload(
                session.blockPos,
                session.camera.getId(),
                position.x,
                position.y,
                position.z,
                blockEntity.getCameraYaw(),
                blockEntity.getCameraPitch(),
                blockEntity.getCameraFov(),
                session.originalPosition.x,
                session.originalPosition.y,
                session.originalPosition.z,
                session.originalYaw,
                session.originalPitch,
                resetResponse
        ));
    }

    public static void update(Player player, UpdateCameraEditorPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Session session = SESSIONS.get(serverPlayer.getUUID());
        if (session == null
                || session.player != serverPlayer
                || !session.blockPos.equals(payload.blockPos())
                || serverPlayer.level() != session.camera.level()
                || !sessionControlStillValid(session)
                || !isFinite(payload)
                || !payload.resetToPlacement() && !isPositionInsideWorld(serverPlayer.serverLevel(),
                        new Vec3(payload.x(), payload.y(), payload.z()))) {
            return;
        }
        long gameTime = serverPlayer.level().getGameTime();
        if (gameTime != session.lastAcceptedGameTime) {
            session.lastAcceptedGameTime = gameTime;
            session.acceptedUpdatesThisTick = 0;
        }
        if (session.acceptedUpdatesThisTick >= MAX_UPDATES_PER_TICK) {
            if (payload.resetToPlacement()
                    && serverPlayer.level().getBlockEntity(session.blockPos) instanceof BattleCameraBlockEntity cameraBlock) {
                sendEditorState(session, cameraBlock, true);
            }
            return;
        }
        session.acceptedUpdatesThisTick++;
        BlockEntity blockEntity = serverPlayer.level().getBlockEntity(session.blockPos);
        if (!(blockEntity instanceof BattleCameraBlockEntity cameraBlock)) {
            finish(serverPlayer, true);
            return;
        }

        // ServerPlayer#setCamera temporarily follows the camera entity on the
        // server. Consequently the player's live coordinates are not a valid
        // security boundary for this editor and must not constrain the camera.
        // Access is instead tied to the authenticated session that was opened
        // while the player stood next to the camera, or next to a DJ panel that
        // still explicitly links that camera.
        var blockState = serverPlayer.level().getBlockState(session.blockPos);
        Vec3 requestedPosition = payload.resetToPlacement()
                ? BattleCameraBlockEntity.placementCameraPosition(session.blockPos, blockState)
                : new Vec3(payload.x(), payload.y(), payload.z());
        Vec3 position = payload.resetToPlacement()
                ? requestedPosition
                : CameraCollision.resolve(serverPlayer.serverLevel(), session.camera,
                        cameraBlock.getCameraPosition(), requestedPosition);
        float yaw = payload.resetToPlacement()
                ? BattleCameraBlockEntity.placementCameraYaw(blockState) : Mth.wrapDegrees(payload.yaw());
        float pitch = payload.resetToPlacement()
                ? BattleCameraBlockEntity.DEFAULT_PITCH : Mth.clamp(payload.pitch(), -89.0F, 89.0F);
        float fov = payload.resetToPlacement()
                ? BattleCameraBlockEntity.DEFAULT_FOV : Mth.clamp(payload.fov(), 30.0F, 110.0F);
        if (!isPositionInsideWorld(serverPlayer.serverLevel(), position)) {
            if (payload.resetToPlacement()) {
                sendEditorState(session, cameraBlock, true);
            }
            return;
        }
        cameraBlock.setCameraSettings(position, yaw, pitch, fov);
        session.camera.absMoveTo(position.x, position.y, position.z, yaw, pitch);
        BattleManager.onBattleCameraBlockUpdated(serverPlayer.serverLevel(), session.blockPos, position, yaw, pitch, fov,
                cameraBlock.isFollowPlayers());
        if (payload.resetToPlacement()) {
            // The camera block may be outside the client's loaded chunks.
            // Return the authoritative defaults without replacing its editor session.
            sendEditorState(session, cameraBlock, true);
        }
    }

    /**
     * Switches the camera's follow mode from its open editor. Held to the same session as a move: the player who
     * opened the editor, on the camera they opened it on, still near it or its panel, and no more often than moves.
     */
    public static void setFollowMode(Player player, CameraFollowModePayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Session session = SESSIONS.get(serverPlayer.getUUID());
        if (session == null
                || session.player != serverPlayer
                || !session.blockPos.equals(payload.blockPos())
                || serverPlayer.level() != session.camera.level()
                || !sessionControlStillValid(session)) {
            return;
        }
        if (!(serverPlayer.level().getBlockEntity(session.blockPos) instanceof BattleCameraBlockEntity cameraBlock)) {
            finish(serverPlayer, true);
            return;
        }
        long gameTime = serverPlayer.level().getGameTime();
        if (gameTime != session.lastAcceptedGameTime) {
            session.lastAcceptedGameTime = gameTime;
            session.acceptedUpdatesThisTick = 0;
        }
        if (session.acceptedUpdatesThisTick < MAX_UPDATES_PER_TICK) {
            session.acceptedUpdatesThisTick++;
            cameraBlock.setFollowPlayers(payload.followPlayers());
            BattleManager.onBattleCameraBlockUpdated(serverPlayer.serverLevel(), session.blockPos,
                    cameraBlock.getCameraPosition(), cameraBlock.getCameraYaw(), cameraBlock.getCameraPitch(),
                    cameraBlock.getCameraFov(), cameraBlock.isFollowPlayers());
        }
        // Answered either way, so a refused switch shows the mode the block really kept.
        PacketDistributor.sendToPlayer(serverPlayer,
                new CameraFollowStatePayload(session.blockPos, cameraBlock.isFollowPlayers()));
    }

    public static void close(Player player, CloseCameraEditorPayload payload) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Session session = SESSIONS.get(serverPlayer.getUUID());
        if (session != null && session.blockPos.equals(payload.blockPos())) {
            finish(serverPlayer, true);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            ServerPlayer player = session.player;
            if (!player.isAlive()
                    || session.camera.isRemoved()
                    || !session.camera.isAlive()
                    || player.serverLevel() != session.camera.level()
                    || !sessionControlStillValid(session)
                    || !player.level().getBlockState(session.blockPos).is(ModBlocks.BATTLE_CAMERA.get())) {
                finish(player, true);
                continue;
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.absMoveTo(
                    session.originalPosition.x,
                    session.originalPosition.y,
                    session.originalPosition.z,
                    session.originalYaw,
                    session.originalPitch
            );
            player.setYHeadRot(session.originalYaw);
            player.setYBodyRot(session.originalYaw);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            finish(player, false);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            session.camera.discard();
        }
        SESSIONS.clear();
    }

    private static void finish(ServerPlayer player, boolean restorePlayer) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return;
        }
        if (restorePlayer) {
            player.setCamera(player);
            // A command/portal may have moved the editor to another dimension.
            // The saved coordinates belong only to the dimension where the
            // session started; applying them in the new level is unsafe.
            if (player.serverLevel() == session.camera.level()) {
                player.teleportTo(
                        player.serverLevel(),
                        session.originalPosition.x,
                        session.originalPosition.y,
                        session.originalPosition.z,
                        session.originalYaw,
                        session.originalPitch
                );
            }
            player.setDeltaMovement(Vec3.ZERO);
            PacketDistributor.sendToPlayer(player, new CameraEditorClosedPayload(session.blockPos));
        }
        session.camera.discard();
    }

    private static boolean isFinite(UpdateCameraEditorPayload payload) {
        return Double.isFinite(payload.x())
                && Double.isFinite(payload.y())
                && Double.isFinite(payload.z())
                && Float.isFinite(payload.yaw())
                && Float.isFinite(payload.pitch())
                && Float.isFinite(payload.fov());
    }

    private static boolean sessionStartedNearBlock(Session session) {
        return session.originalPosition.distanceToSqr(Vec3.atCenterOf(session.blockPos))
                <= MAX_INTERACTION_DISTANCE_SQR;
    }

    private static boolean sessionControlStillValid(Session session) {
        if (session.controlPanelPos == null) {
            return sessionStartedNearBlock(session);
        }
        return session.originalPosition.distanceToSqr(Vec3.atCenterOf(session.controlPanelPos))
                <= MAX_INTERACTION_DISTANCE_SQR
                && session.player.level().hasChunkAt(session.controlPanelPos)
                && session.player.level().getBlockEntity(session.controlPanelPos) instanceof DjPanelBlockEntity panel
                && panel.getLinkedCameras().contains(session.blockPos);
    }

    private static boolean isPositionInsideWorld(ServerLevel level, Vec3 position) {
        var border = level.getWorldBorder();
        return position.x >= border.getMinX() && position.x < border.getMaxX()
                && position.z >= border.getMinZ() && position.z < border.getMaxZ()
                && position.y >= level.getMinBuildHeight()
                && position.y < level.getMaxBuildHeight();
    }

    private static final class Session {
        private final ServerPlayer player;
        private final BlockPos blockPos;
        private final ArmorStand camera;
        private final Vec3 originalPosition;
        private final float originalYaw;
        private final float originalPitch;
        private final BlockPos controlPanelPos;
        private long lastAcceptedGameTime = Long.MIN_VALUE;
        private int acceptedUpdatesThisTick;

        private Session(ServerPlayer player, BlockPos blockPos, ArmorStand camera, Vec3 originalPosition,
                        float originalYaw, float originalPitch, BlockPos controlPanelPos) {
            this.player = player;
            this.blockPos = blockPos;
            this.camera = camera;
            this.originalPosition = originalPosition;
            this.originalYaw = originalYaw;
            this.originalPitch = originalPitch;
            this.controlPanelPos = controlPanelPos;
        }
    }
}
