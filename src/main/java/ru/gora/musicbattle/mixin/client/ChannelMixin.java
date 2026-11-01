package ru.gora.musicbattle.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.gora.musicbattle.client.BattleSongPosition;
import ru.gora.musicbattle.client.LowLatencyAudioStream;

@Mixin(Channel.class)
public abstract class ChannelMixin implements BattleSongPosition.Source {
    /** Four 100 ms buffers keep EQ/DSP responsive while retaining hitch protection. */
    private static final float MUSIC_BATTLE_BUFFER_SECONDS = 0.10F;

    @Shadow
    @Nullable
    private AudioStream stream;

    @Shadow
    @Final
    private int source;

    /** Bytes of the buffers this source has played and given back, for where it really is (BattleSongPosition). */
    @Unique
    private long musicbattle$playedBytes;

    @ModifyArg(method = "removeProcessedBuffers", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/AL10;alDeleteBuffers([I)V"))
    private int[] musicbattle$countPlayedBuffers(int[] buffers) {
        for (int buffer : buffers) {
            musicbattle$playedBytes += AL10.alGetBufferi(buffer, AL10.AL_SIZE);
        }
        return buffers;
    }

    /** On the sound thread only, like every other call on a channel. */
    @Override
    public double musicbattle$playedSeconds() {
        AudioStream current = stream;
        if (current == null || AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            return Double.NaN;
        }
        AudioFormat format = current.getFormat();
        double bytesPerSecond = (double) format.getFrameSize() * format.getFrameRate();
        if (bytesPerSecond <= 0.0D) {
            return Double.NaN;
        }
        return (musicbattle$playedBytes + AL10.alGetSourcei(source, AL11.AL_BYTE_OFFSET)) / bytesPerSecond;
    }

    @Redirect(
            method = "attachBufferStream",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/audio/Channel;calculateBufferSize(Ljavax/sound/sampled/AudioFormat;I)I"
            )
    )
    private int musicbattle$liveDjBufferSize(AudioFormat format, int sampleAmount) {
        if (!(stream instanceof LowLatencyAudioStream)) {
            return (int) ((float) (sampleAmount * format.getSampleSizeInBits()) / 8.0F
                    * format.getChannels() * format.getSampleRate());
        }
        int frameSize = Math.max(1, format.getFrameSize());
        int frames = Math.max(1, Math.round(format.getFrameRate() * MUSIC_BATTLE_BUFFER_SECONDS));
        return frames * frameSize;
    }
}
