package ru.gora.musicbattle.block.entity;

import net.minecraft.core.BlockPos;
import ru.gora.musicbattle.data.PlacementAnimationClock;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ru.gora.musicbattle.block.BattleCameraBlock;
import ru.gora.musicbattle.registry.ModBlockEntities;
import ru.gora.musicbattle.server.BattleCameraNetwork;

public final class BattleCameraBlockEntity extends BlockEntity {
    public static final float DEFAULT_FOV = 70.0F;
    public static final float DEFAULT_PITCH = 0.0F;
    private static final String OPTICAL_POSITION_TAG = "CameraPositionIsOptical";
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private float yaw;
    private float pitch;
    private float fov = DEFAULT_FOV;
    private long placementGameTime = Long.MIN_VALUE;
    /** Client only, never saved: when this client began drawing the placement clip, on its own clock. */
    private long clientAnimationStartNanos;
    /** Client only: the placement this client already latched onto, so a correction does not restart the clip. */
    private long latchedPlacementGameTime = Long.MIN_VALUE;
    private boolean legacyPositionMigrated;

    public BattleCameraBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BATTLE_CAMERA.get(), pos, state);
        Vec3 placementOffset = placementCameraPosition(pos, state).subtract(Vec3.atCenterOf(pos));
        offsetX = placementOffset.x;
        offsetY = placementOffset.y;
        offsetZ = placementOffset.z;
        yaw = placementCameraYaw(state);
        pitch = DEFAULT_PITCH;
    }

    /** Defaults depend only on the block's current placement, never saved edits. */
    public static Vec3 placementCameraPosition(BlockPos pos, BlockState state) {
        Direction facing = placementFacing(state);
        return Vec3.atCenterOf(pos).add(facing.getStepX() * 0.58D, 0.0D, facing.getStepZ() * 0.58D);
    }

    public static float placementCameraYaw(BlockState state) {
        return placementFacing(state).toYRot();
    }

    private static Direction placementFacing(BlockState state) {
        return state.hasProperty(BattleCameraBlock.FACING)
                ? state.getValue(BattleCameraBlock.FACING) : Direction.NORTH;
    }

    /** Returns the saved optical/lens position shared by every camera view. */
    public Vec3 getCameraPosition() {
        return Vec3.atCenterOf(worldPosition).add(offsetX, offsetY, offsetZ);
    }

    public float getCameraYaw() {
        return yaw;
    }

    public float getCameraPitch() {
        return pitch;
    }

    public float getCameraFov() {
        return fov;
    }

    public void markPlaced(long gameTime) {
        placementGameTime = gameTime;
        setChanged();
    }

    public float getPlacementAnimationSeconds(float partialTick) {
        if (level == null || placementGameTime == Long.MIN_VALUE) {
            return Float.POSITIVE_INFINITY;
        }
        if (!level.isClientSide) {
            return Math.max(0.0F, (level.getGameTime() - placementGameTime + partialTick) / 20.0F);
        }
        // On a client the game time is re-stamped from the server, and whoever placed the block stamped a guess of
        // their own before the real one arrived. Both land inside the one second this clip lasts, and each jump
        // shows as a stutter. The server's clock decides where in the clip this client joins, once; after that the
        // clip runs off a clock nothing can move.
        long now = System.nanoTime();
        if (PlacementAnimationClock.shouldLatch(latchedPlacementGameTime, placementGameTime)) {
            latchedPlacementGameTime = placementGameTime;
            clientAnimationStartNanos = PlacementAnimationClock.startNanos(placementGameTime, level.getGameTime(), now);
        }
        return PlacementAnimationClock.seconds(clientAnimationStartNanos, now);
    }

    public void setCameraSettings(Vec3 position, float yaw, float pitch, float fov) {
        Vec3 offset = position.subtract(Vec3.atCenterOf(worldPosition));
        offsetX = offset.x;
        offsetY = offset.y;
        offsetZ = offset.z;
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -89.0F, 89.0F);
        this.fov = Mth.clamp(fov, 30.0F, 110.0F);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("CameraOffsetX", offsetX);
        tag.putDouble("CameraOffsetY", offsetY);
        tag.putDouble("CameraOffsetZ", offsetZ);
        tag.putFloat("CameraYaw", yaw);
        tag.putFloat("CameraPitch", pitch);
        tag.putFloat("CameraFov", fov);
        tag.putBoolean(OPTICAL_POSITION_TAG, true);
        if (placementGameTime != Long.MIN_VALUE) {
            tag.putLong("PlacementGameTime", placementGameTime);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("CameraOffsetX")) {
            offsetX = tag.getDouble("CameraOffsetX");
            offsetY = tag.getDouble("CameraOffsetY");
            offsetZ = tag.getDouble("CameraOffsetZ");
            if (!tag.getBoolean(OPTICAL_POSITION_TAG)) {
                // Older versions saved the feet of the temporary editor
                // ArmorStand even though the user framed the shot from its
                // eyes. Convert that legacy value once so all new consumers
                // share the optical camera position the user actually saw.
                offsetY += EntityType.ARMOR_STAND.getDimensions().eyeHeight();
                legacyPositionMigrated = true;
            }
        }
        if (tag.contains("CameraYaw")) {
            yaw = Mth.wrapDegrees(tag.getFloat("CameraYaw"));
            pitch = Mth.clamp(tag.getFloat("CameraPitch"), -89.0F, 89.0F);
        }
        if (tag.contains("CameraFov")) {
            fov = Mth.clamp(tag.getFloat("CameraFov"), 30.0F, 110.0F);
        }
        placementGameTime = tag.contains("PlacementGameTime") ? tag.getLong("PlacementGameTime") : Long.MIN_VALUE;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            if (legacyPositionMigrated) {
                setChanged();
                legacyPositionMigrated = false;
            }
            BattleCameraNetwork.register(serverLevel, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            BattleCameraNetwork.unregister(serverLevel, worldPosition);
        }
        super.setRemoved();
    }
}
