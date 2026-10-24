package ru.gora.musicbattle.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.gora.musicbattle.client.LowLatencyAudioStream;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    /** Four 100 ms buffers keep EQ/DSP responsive while retaining hitch protection. */
    private static final float MUSIC_BATTLE_BUFFER_SECONDS = 0.10F;

    @Shadow
    @Nullable
    private AudioStream stream;

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
