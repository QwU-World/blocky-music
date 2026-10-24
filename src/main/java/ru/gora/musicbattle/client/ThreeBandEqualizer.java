package ru.gora.musicbattle.client;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.util.Mth;

/**
 * Lightweight streaming three-band EQ for decoded OGG PCM.
 *
 * <p>The two one-pole crossovers split the signal into low (below ~250 Hz),
 * mid (~250-4000 Hz), and high (above ~4000 Hz) bands. Gain targets are
 * volatile so a playing stream can adopt server-side speaker changes without
 * reopening or seeking the OGG file.</p>
 */
final class ThreeBandEqualizer {
    private static final float MIN_GAIN_DB = -12.0F;
    private static final float MAX_GAIN_DB = 12.0F;

    private volatile Gains gains = new Gains(1.0F, 1.0F, 1.0F);

    void setGains(float lowGainDb, float midGainDb, float highGainDb) {
        // Publish one immutable snapshot so the decode thread cannot observe a
        // half-updated combination while a live speaker setting is applied.
        this.gains = new Gains(linearGain(validGain(lowGainDb)),
                linearGain(validGain(midGainDb)), linearGain(validGain(highGainDb)));
    }

    FloatSampleSource wrap(FloatSampleSource source) {
        return new EqualizedStream(source, this);
    }

    private static float validGain(float gainDb) {
        return Mth.clamp(Float.isFinite(gainDb) ? gainDb : 0.0F, MIN_GAIN_DB, MAX_GAIN_DB);
    }

    private static float linearGain(float gainDb) {
        return (float) Math.pow(10.0D, gainDb / 20.0D);
    }

    private static final class EqualizedStream implements FloatSampleSource {
        private static final float GAIN_SMOOTHING = 0.0025F;

        private final FloatSampleSource source;
        private final ThreeBandEqualizer settings;
        private final int channels;
        private final float[] lowState;
        private final float[] highCutState;
        private final float lowAlpha;
        private final float highAlpha;
        private int channelIndex;
        private float currentLowGain;
        private float currentMidGain;
        private float currentHighGain;

        private EqualizedStream(FloatSampleSource source, ThreeBandEqualizer settings) {
            this.source = source;
            this.settings = settings;
            AudioFormat format = source.getFormat();
            this.channels = Math.max(1, format.getChannels());
            this.lowState = new float[channels];
            this.highCutState = new float[channels];
            float decodedSampleRate = format.getSampleRate();
            float sampleRate = Float.isFinite(decodedSampleRate) && decodedSampleRate >= 8_000.0F
                    ? decodedSampleRate : 8_000.0F;
            this.lowAlpha = onePoleAlpha(250.0F, sampleRate);
            this.highAlpha = onePoleAlpha(4_000.0F, sampleRate);
            Gains initial = settings.gains;
            this.currentLowGain = initial.low;
            this.currentMidGain = initial.mid;
            this.currentHighGain = initial.high;
        }

        @Override
        public AudioFormat getFormat() {
            return source.getFormat();
        }

        @Override
        public boolean readChunk(FloatConsumer output) throws IOException {
            return source.readChunk(sample -> output.accept(process(sample)));
        }

        private float process(float input) {
            int channel = channelIndex;
            channelIndex = (channelIndex + 1) % channels;
            // Advance gain interpolation once per PCM frame, rather than once
            // per interleaved sample. All channels in a frame therefore receive
            // exactly the same gain and mono/stereo ramps have the same duration.
            if (channel == 0) {
                Gains target = settings.gains;
                currentLowGain += (target.low - currentLowGain) * GAIN_SMOOTHING;
                currentMidGain += (target.mid - currentMidGain) * GAIN_SMOOTHING;
                currentHighGain += (target.high - currentHighGain) * GAIN_SMOOTHING;
            }
            float low = lowState[channel] + lowAlpha * (input - lowState[channel]);
            float highCut = highCutState[channel] + highAlpha * (input - highCutState[channel]);
            lowState[channel] = low;
            highCutState[channel] = highCut;
            float mid = highCut - low;
            float high = input - highCut;
            return Mth.clamp(low * currentLowGain + mid * currentMidGain + high * currentHighGain,
                    -1.0F, 1.0F);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }

        private static float onePoleAlpha(float cutoff, float sampleRate) {
            return 1.0F - (float) Math.exp(-2.0D * Math.PI * cutoff / sampleRate);
        }
    }

    private record Gains(float low, float mid, float high) { }
}
