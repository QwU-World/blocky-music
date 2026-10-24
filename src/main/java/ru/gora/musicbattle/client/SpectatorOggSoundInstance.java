package ru.gora.musicbattle.client;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public final class SpectatorOggSoundInstance extends ExternalOggSoundInstance implements TickableSoundInstance {
    private float maxDistance;
    private double targetX;
    private double targetY;
    private double targetZ;
    private float targetMaxDistance;
    private float targetBaseVolume;
    private float audibleVolume;
    private boolean audibleVolumeInitialized;

    public SpectatorOggSoundInstance(String trackId, String fileName, Path file, int skipTicks, double x, double y, double z,
                                     float maxDistance, float volume, float pitch,
                                     float lowGainDb, float midGainDb, float highGainDb) {
        this(trackId, fileName, file, skipTicks, x, y, z, maxDistance, volume, pitch,
                lowGainDb, midGainDb, highGainDb,
                false,
                false, 0.30F, 0.65F, 0.45F,
                false, 0.25F, 280.0F, 0.35F,
                false, 12_000.0F);
    }

    public SpectatorOggSoundInstance(String trackId, String fileName, Path file, int skipTicks, double x, double y, double z,
                                     float maxDistance, float volume, float pitch,
                                     float lowGainDb, float midGainDb, float highGainDb,
                                     boolean dspEnabled,
                                     boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                                     boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                                     boolean lowPassEnabled, float lowPassCutoffHz) {
        super(trackId, fileName, file, skipTicks, false, x, y, z, SoundInstance.Attenuation.NONE, volume, pitch, null);
        this.maxDistance = Math.max(1.0F, maxDistance);
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.targetMaxDistance = this.maxDistance;
        this.targetBaseVolume = Math.max(0.0F, volume);
        setEqualizer(lowGainDb, midGainDb, highGainDb);
        setDjEffects(dspEnabled,
                reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                echoEnabled, echoWet, echoTimeMs, echoFeedback,
                lowPassEnabled, lowPassCutoffHz);
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            float distance = distanceToPlayer(player, targetX, targetY, targetZ);
            float falloff = Mth.clamp(1.0F - distance / targetMaxDistance, 0.0F, 1.0F);
            this.audibleVolume = targetBaseVolume * falloff;
            this.audibleVolumeInitialized = true;
        }
    }

    @Override
    public boolean canStartSilent() {
        // A listener may receive the session exactly on a coverage boundary.
        // Keep the streaming channel alive so walking inward fades it in instead
        // of requiring an OGG restart or another asset packet.
        return true;
    }

    public void setPosition(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public void setMaxDistance(float maxDistance) {
        this.targetMaxDistance = Math.max(1.0F, maxDistance);
    }

    public void setSource(double x, double y, double z, float maxDistance, float baseVolume,
                          float lowGainDb, float midGainDb, float highGainDb) {
        setSource(x, y, z, maxDistance, baseVolume,
                lowGainDb, midGainDb, highGainDb,
                false,
                false, 0.30F, 0.65F, 0.45F,
                false, 0.25F, 280.0F, 0.35F,
                false, 12_000.0F);
    }

    public void setSource(double x, double y, double z, float maxDistance, float baseVolume,
                          float lowGainDb, float midGainDb, float highGainDb,
                          boolean dspEnabled,
                          boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                          boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                          boolean lowPassEnabled, float lowPassCutoffHz) {
        setPosition(x, y, z);
        setMaxDistance(maxDistance);
        targetBaseVolume = Math.max(0.0F, baseVolume);
        setEqualizer(lowGainDb, midGainDb, highGainDb);
        setDjEffects(dspEnabled,
                reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                echoEnabled, echoWet, echoTimeMs, echoFeedback,
                lowPassEnabled, lowPassCutoffHz);
    }

    public void setTargetBaseVolume(float baseVolume) {
        targetBaseVolume = Math.max(0.0F, baseVolume);
    }

    @Override
    public void tick() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            requestStop();
            return;
        }
        // Position interpolation keeps stereo panning from snapping between two
        // speakers. Loudness is calculated from the winning target source, not
        // the interpolated point, so the transition cannot create an artificial dip.
        x = Mth.lerp(0.20D, x, targetX);
        y = Mth.lerp(0.20D, y, targetY);
        z = Mth.lerp(0.20D, z, targetZ);
        maxDistance = Mth.lerp(0.20F, maxDistance, targetMaxDistance);
        float distance = distanceToPlayer(player, targetX, targetY, targetZ);
        float falloff = Mth.clamp(1.0F - distance / targetMaxDistance, 0.0F, 1.0F);
        float targetAudibleVolume = targetBaseVolume * falloff;
        if (!audibleVolumeInitialized) {
            audibleVolume = targetAudibleVolume;
            audibleVolumeInitialized = true;
        } else {
            audibleVolume = Mth.lerp(0.18F, audibleVolume, targetAudibleVolume);
        }
    }

    @Override
    public float getVolume() {
        Player player = Minecraft.getInstance().player;
        if (player == null || isCalibrationMuted()) {
            return 0.0F;
        }
        return audibleVolumeInitialized ? Math.max(0.0F, audibleVolume) : 0.0F;
    }

    private static float distanceToPlayer(Player player, double x, double y, double z) {
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
