package ru.gora.musicbattle.client;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.FloatSampleSource;

/** Decoder-level regression: live DSP changes must not move or attenuate the dry timeline. */
public final class DjAudioEffectsChecks {
    public static void main(String[] args) throws Exception {
        LiveDjAudioEffects effects = new LiveDjAudioEffects();
        effects.setSettings(true,
                false, 0.0F, 0.0F, 0.0F,
                true, 0.75F, 40.0F, 0.0F,
                false, 20_000.0F);
        FloatSampleSource stream = effects.wrap(new OneChunkSource(new float[]{0.8F, -0.4F, 0.2F, -0.1F}));
        float[] output = new float[4];
        int[] index = {0};
        require(stream.readChunk(value -> output[index[0]++] = value), "DSP stream returned no data");
        require(index[0] == output.length, "DSP stream changed sample count");
        for (int sample = 0; sample < output.length; sample++) {
            require(Math.abs(output[sample] - new float[]{0.8F, -0.4F, 0.2F, -0.1F}[sample]) < 0.00001F,
                    "Enabling echo attenuated or time-shifted dry sample " + sample + ": " + output[sample]);
        }
        System.out.println("DJ live audio effects regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class OneChunkSource implements FloatSampleSource {
        private final float[] samples;
        private boolean emitted;

        private OneChunkSource(float[] samples) {
            this.samples = samples;
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(44_100.0F, 16, 1, true, false);
        }

        @Override
        public boolean readChunk(FloatConsumer output) {
            if (emitted) return false;
            emitted = true;
            for (float sample : samples) output.accept(sample);
            return true;
        }

        @Override
        public void close() throws IOException {
        }
    }
}
