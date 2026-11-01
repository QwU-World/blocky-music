package ru.gora.musicbattle.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.registry.ModBlockEntities;
import ru.gora.musicbattle.server.SpeakerNetwork;

public final class SpeakerBlockEntity extends BlockEntity {
    public static final float DEFAULT_RADIUS = 14.0F;
    public static final float DEFAULT_VOLUME = 1.0F;
    public static final float DEFAULT_EQ_GAIN_DB = 0.0F;
    public static final float MIN_EQ_GAIN_DB = -12.0F;
    public static final float MAX_EQ_GAIN_DB = 12.0F;
    private float soundRadius = DEFAULT_RADIUS;
    private float volume = DEFAULT_VOLUME;
    private float lowGainDb = DEFAULT_EQ_GAIN_DB;
    private float midGainDb = DEFAULT_EQ_GAIN_DB;
    private float highGainDb = DEFAULT_EQ_GAIN_DB;
    private int activeBpm = 120;
    /** No beat known: the cone pumps at the tempo from the world's tick 0, as it always did. */
    public static final long NO_BEAT = Long.MIN_VALUE;
    /**
     * A game tick a beat of what this speaker plays falls on (SpeakerNetwork works it out from beat 1 of the chart, its
     * offset included), so the cone pumps on the song's beats.
     */
    private long beatGameTick = NO_BEAT;
    private BlockPos djControllerPos;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEAKER.get(), pos, state);
    }

    public float getSoundRadius() { return soundRadius; }
    public float getVolume() { return volume; }
    public float getLowGainDb() { return lowGainDb; }
    public float getMidGainDb() { return midGainDb; }
    public float getHighGainDb() { return highGainDb; }
    public int getActiveBpm() { return activeBpm; }
    public long getBeatGameTick() { return beatGameTick; }
    public boolean isDjControlled() { return djControllerPos != null; }
    public BlockPos getDjControllerPos() { return djControllerPos; }

    public boolean isControlledBy(BlockPos panelPos) {
        return djControllerPos != null && djControllerPos.equals(panelPos);
    }

    public void setDjController(BlockPos panelPos) {
        BlockPos normalized = panelPos == null ? null : panelPos.immutable();
        if (java.util.Objects.equals(djControllerPos, normalized)) {
            return;
        }
        djControllerPos = normalized;
        sync();
    }

    public void setSettings(float soundRadius, float volume) {
        setSettings(soundRadius, volume, lowGainDb, midGainDb, highGainDb);
    }

    public void setSettings(float soundRadius, float volume, float lowGainDb, float midGainDb, float highGainDb) {
        this.soundRadius = validSoundRadius(soundRadius);
        this.volume = validVolume(volume);
        this.lowGainDb = validEqGain(lowGainDb);
        this.midGainDb = validEqGain(midGainDb);
        this.highGainDb = validEqGain(highGainDb);
        sync();
    }

    private static float validSoundRadius(float radius) {
        return Mth.clamp(Float.isFinite(radius) ? radius : DEFAULT_RADIUS, 2.0F, 64.0F);
    }

    private static float validVolume(float value) {
        return Mth.clamp(Float.isFinite(value) ? value : DEFAULT_VOLUME, 0.0F, 2.0F);
    }

    private static float validEqGain(float gainDb) {
        return Mth.clamp(Float.isFinite(gainDb) ? gainDb : DEFAULT_EQ_GAIN_DB,
                MIN_EQ_GAIN_DB, MAX_EQ_GAIN_DB);
    }

    public void setActiveBpm(int bpm) {
        setActiveBeat(bpm, beatGameTick);
    }

    /**
     * The tempo and a game tick one of its beats falls on. A beat that moved by a tick or less is the same beat, rounded
     * the other way: it is not sent again, or a battle's speakers would update every tick.
     */
    public void setActiveBeat(int bpm, long beatTick) {
        int clamped = Mth.clamp(bpm, 20, 600);
        boolean beatMoved = (beatTick == NO_BEAT) != (beatGameTick == NO_BEAT)
                || beatTick != NO_BEAT && Math.abs(beatTick - beatGameTick) > 1L;
        if (activeBpm != clamped || beatMoved) {
            activeBpm = clamped;
            beatGameTick = beatTick;
            sync();
        }
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("SoundRadius", soundRadius);
        tag.putFloat("Volume", volume);
        tag.putFloat("EqLowDb", lowGainDb);
        tag.putFloat("EqMidDb", midGainDb);
        tag.putFloat("EqHighDb", highGainDb);
        tag.putInt("ActiveBpm", activeBpm);
        if (beatGameTick != NO_BEAT) {
            tag.putLong("ActiveBeatTick", beatGameTick);
        }
        if (djControllerPos != null) {
            tag.putLong("DjController", djControllerPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Treat world NBT as untrusted too. Math/Mth clamp deliberately preserves
        // NaN, which would poison source-distance and loudness arbitration.
        soundRadius = validSoundRadius(tag.contains("SoundRadius") ? tag.getFloat("SoundRadius") : DEFAULT_RADIUS);
        volume = validVolume(tag.contains("Volume") ? tag.getFloat("Volume") : DEFAULT_VOLUME);
        lowGainDb = validEqGain(tag.contains("EqLowDb") ? tag.getFloat("EqLowDb") : DEFAULT_EQ_GAIN_DB);
        midGainDb = validEqGain(tag.contains("EqMidDb") ? tag.getFloat("EqMidDb") : DEFAULT_EQ_GAIN_DB);
        highGainDb = validEqGain(tag.contains("EqHighDb") ? tag.getFloat("EqHighDb") : DEFAULT_EQ_GAIN_DB);
        activeBpm = Mth.clamp(tag.contains("ActiveBpm") ? tag.getInt("ActiveBpm") : 120, 20, 600);
        beatGameTick = tag.contains("ActiveBeatTick") ? tag.getLong("ActiveBeatTick") : NO_BEAT;
        djControllerPos = tag.contains("DjController") ? BlockPos.of(tag.getLong("DjController")) : null;
    }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            if (getBlockState().getBlock() instanceof SpeakerBlock speakerBlock) {
                speakerBlock.ensureExtension(serverLevel, worldPosition, getBlockState());
            }
            SpeakerNetwork.register(serverLevel, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            SpeakerNetwork.unregister(serverLevel, worldPosition);
        }
        super.setRemoved();
    }
}
