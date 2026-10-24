package ru.gora.musicbattle.client;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import java.io.IOException;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.util.Mth;

/**
 * Streaming panel-level DJ effects applied after a speaker's three-band EQ.
 *
 * <p>One immutable settings snapshot is published for the decoder thread, so a
 * live UI change cannot expose a half-updated effect chain. The wrapper always
 * stays attached to the decoded stream: bypassing or changing an effect only
 * moves smoothed DSP targets and never reopens or seeks the OGG file.</p>
 */
final class LiveDjAudioEffects {
    static final float MIN_ECHO_TIME_MS = 40.0F;
    static final float MAX_ECHO_TIME_MS = 1_000.0F;
    static final float MIN_LOW_PASS_HZ = 200.0F;
    static final float MAX_LOW_PASS_HZ = 20_000.0F;

    private volatile Settings settings = Settings.disabled();

    void setSettings(boolean masterEnabled,
                     boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                     boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                     boolean lowPassEnabled, float lowPassCutoffHz) {
        settings = Settings.sanitized(masterEnabled,
                reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                echoEnabled, echoWet, echoTimeMs, echoFeedback,
                lowPassEnabled, lowPassCutoffHz);
    }

    FloatSampleSource wrap(FloatSampleSource source) {
        return new EffectStream(source, this);
    }

    private static final class EffectStream implements FloatSampleSource {
        private static final int[] COMB_TUNINGS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        private static final int[] ALL_PASS_TUNINGS = {556, 441, 341, 225};
        private static final float PARAMETER_SMOOTHING = 0.0015F;
        private static final float DELAY_TIME_SMOOTHING = 0.00045F;

        private final FloatSampleSource source;
        private final LiveDjAudioEffects owner;
        private final int channels;
        private final float sampleRate;
        private final float[] lowPassState;
        private final float[][] echoBuffers;
        private final int[] echoWriteIndices;
        private final CombFilter[][] combFilters;
        private final AllPassFilter[][] allPassFilters;
        private int channelIndex;
        private Settings observedSettings;
        private float targetLowPassAlpha;
        private float targetEchoWet;
        private float targetEchoFeedback;
        private float targetEchoDelaySamples;
        private float targetReverbWet;
        private float targetReverbFeedback;
        private float targetReverbDamping;
        private float currentLowPassAlpha;
        private float currentEchoWet;
        private float currentEchoFeedback;
        private float currentEchoDelaySamples;
        private float currentReverbWet;
        private float currentReverbFeedback;
        private float currentReverbDamping;
        private boolean echoStateDirty;
        private boolean reverbStateDirty;

        private EffectStream(FloatSampleSource source, LiveDjAudioEffects owner) {
            this.source = source;
            this.owner = owner;
            AudioFormat format = source.getFormat();
            this.channels = Math.max(1, format.getChannels());
            float decodedRate = format.getSampleRate();
            this.sampleRate = Mth.clamp(Float.isFinite(decodedRate) ? decodedRate : 44_100.0F,
                    8_000.0F, 192_000.0F);
            this.lowPassState = new float[channels];
            int echoCapacity = Math.max(4, (int) Math.ceil(sampleRate * MAX_ECHO_TIME_MS / 1_000.0F) + 3);
            this.echoBuffers = new float[channels][echoCapacity];
            this.echoWriteIndices = new int[channels];
            this.combFilters = new CombFilter[channels][COMB_TUNINGS.length];
            this.allPassFilters = new AllPassFilter[channels][ALL_PASS_TUNINGS.length];
            float rateScale = sampleRate / 44_100.0F;
            for (int channel = 0; channel < channels; channel++) {
                int channelSpread = Math.round(channel * 23.0F * rateScale);
                for (int index = 0; index < COMB_TUNINGS.length; index++) {
                    combFilters[channel][index] = new CombFilter(
                            Math.max(2, Math.round(COMB_TUNINGS[index] * rateScale) + channelSpread));
                }
                for (int index = 0; index < ALL_PASS_TUNINGS.length; index++) {
                    allPassFilters[channel][index] = new AllPassFilter(
                            Math.max(2, Math.round(ALL_PASS_TUNINGS[index] * rateScale) + channelSpread));
                }
            }
            observeSettings(true);
        }

        @Override
        public AudioFormat getFormat() {
            return source.getFormat();
        }

        @Override
        public boolean readChunk(FloatConsumer output) throws IOException {
            return source.readChunk(sample -> output.accept(process(sample)));
        }

        private float process(float rawInput) {
            int channel = channelIndex;
            channelIndex = (channelIndex + 1) % channels;
            if (channel == 0) {
                observeSettings(false);
                currentLowPassAlpha = approach(currentLowPassAlpha, targetLowPassAlpha, PARAMETER_SMOOTHING);
                currentEchoWet = approach(currentEchoWet, targetEchoWet, PARAMETER_SMOOTHING);
                currentEchoFeedback = approach(currentEchoFeedback, targetEchoFeedback, PARAMETER_SMOOTHING);
                currentEchoDelaySamples = approach(currentEchoDelaySamples, targetEchoDelaySamples, DELAY_TIME_SMOOTHING);
                currentReverbWet = approach(currentReverbWet, targetReverbWet, PARAMETER_SMOOTHING);
                currentReverbFeedback = approach(currentReverbFeedback, targetReverbFeedback, PARAMETER_SMOOTHING);
                currentReverbDamping = approach(currentReverbDamping, targetReverbDamping, PARAMETER_SMOOTHING);
            }

            float input = finiteSample(rawInput);
            boolean lowPassActive = currentLowPassAlpha < 0.99999F || targetLowPassAlpha < 0.99999F;
            boolean echoActive = currentEchoWet > 0.00001F || targetEchoWet > 0.00001F;
            boolean reverbActive = currentReverbWet > 0.00001F || targetReverbWet > 0.00001F;
            if (!lowPassActive && !echoActive && !reverbActive) {
                lowPassState[channel] = input;
                clearInactiveState(channel);
                return input;
            }

            float lowPassed = input;
            if (lowPassActive) {
                lowPassed = lowPassState[channel]
                        + currentLowPassAlpha * (input - lowPassState[channel]);
                lowPassState[channel] = finiteSample(lowPassed);
            } else {
                lowPassState[channel] = input;
            }

            float echoed = lowPassed;
            if (echoActive) {
                echoStateDirty = true;
                float delayed = readEcho(channel, currentEchoDelaySamples);
                float echoWrite = Mth.clamp(lowPassed + delayed * currentEchoFeedback, -1.5F, 1.5F);
                writeEcho(channel, echoWrite);
                // Echo is a parallel effect: the dry sample must keep its exact place on the
                // timeline while the delayed tap fades in or out. Crossfading dry -> delayed
                // sounded like playback jumped whenever the live toggle changed.
                echoed = lowPassed + delayed * currentEchoWet;
            } else if (channel == channels - 1 && echoStateDirty) {
                clearEchoState();
            }

            float output = echoed;
            if (reverbActive) {
                reverbStateDirty = true;
                float reverbInput = echoed * 0.28F;
                float reverberated = 0.0F;
                for (CombFilter filter : combFilters[channel]) {
                    reverberated += filter.process(reverbInput, currentReverbFeedback, currentReverbDamping);
                }
                reverberated /= combFilters[channel].length;
                for (AllPassFilter filter : allPassFilters[channel]) {
                    reverberated = filter.process(reverberated);
                }
                // The parallel-comb average is deliberately conservative. This
                // makeup gain keeps full-wet output useful without driving feedback.
                reverberated *= 1.45F;
                output = echoed + (reverberated - echoed) * currentReverbWet;
            } else if (channel == channels - 1 && reverbStateDirty) {
                clearReverbState();
            }
            return softLimit(output);
        }

        private void clearInactiveState(int channel) {
            if (channel != channels - 1) {
                return;
            }
            if (echoStateDirty) {
                clearEchoState();
            }
            if (reverbStateDirty) {
                clearReverbState();
            }
        }

        private void clearEchoState() {
            for (float[] buffer : echoBuffers) {
                Arrays.fill(buffer, 0.0F);
            }
            Arrays.fill(echoWriteIndices, 0);
            echoStateDirty = false;
        }

        private void clearReverbState() {
            for (CombFilter[] channelFilters : combFilters) {
                for (CombFilter filter : channelFilters) {
                    filter.clear();
                }
            }
            for (AllPassFilter[] channelFilters : allPassFilters) {
                for (AllPassFilter filter : channelFilters) {
                    filter.clear();
                }
            }
            reverbStateDirty = false;
        }

        private void observeSettings(boolean initialize) {
            Settings snapshot = owner.settings;
            if (!initialize && snapshot == observedSettings) {
                return;
            }
            observedSettings = snapshot;
            boolean master = snapshot.masterEnabled;
            targetLowPassAlpha = master && snapshot.lowPassEnabled
                    ? onePoleAlpha(snapshot.lowPassCutoffHz, sampleRate) : 1.0F;
            targetEchoWet = master && snapshot.echoEnabled ? snapshot.echoWet : 0.0F;
            targetEchoFeedback = master && snapshot.echoEnabled ? snapshot.echoFeedback : 0.0F;
            targetEchoDelaySamples = Mth.clamp(snapshot.echoTimeMs * sampleRate / 1_000.0F,
                    1.0F, echoBuffers[0].length - 2.0F);
            targetReverbWet = master && snapshot.reverbEnabled ? snapshot.reverbWet : 0.0F;
            targetReverbFeedback = master && snapshot.reverbEnabled
                    ? 0.28F + snapshot.reverbRoom * 0.54F : 0.0F;
            targetReverbDamping = 0.05F + snapshot.reverbDamping * 0.90F;
            if (initialize) {
                currentLowPassAlpha = targetLowPassAlpha;
                currentEchoWet = targetEchoWet;
                currentEchoFeedback = targetEchoFeedback;
                currentEchoDelaySamples = targetEchoDelaySamples;
                currentReverbWet = targetReverbWet;
                currentReverbFeedback = targetReverbFeedback;
                currentReverbDamping = targetReverbDamping;
            }
        }

        private float readEcho(int channel, float delaySamples) {
            float readPosition = echoWriteIndices[channel] - delaySamples;
            int length = echoBuffers[channel].length;
            while (readPosition < 0.0F) {
                readPosition += length;
            }
            int first = (int) readPosition;
            int second = first + 1 == length ? 0 : first + 1;
            float fraction = readPosition - first;
            return echoBuffers[channel][first]
                    + (echoBuffers[channel][second] - echoBuffers[channel][first]) * fraction;
        }

        private void writeEcho(int channel, float sample) {
            int index = echoWriteIndices[channel];
            echoBuffers[channel][index] = finiteSample(sample);
            echoWriteIndices[channel] = index + 1 == echoBuffers[channel].length ? 0 : index + 1;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }

        private static float approach(float current, float target, float smoothing) {
            return current + (target - current) * smoothing;
        }

        private static float onePoleAlpha(float cutoff, float sampleRate) {
            float safeCutoff = Math.min(cutoff, sampleRate * 0.45F);
            return 1.0F - (float) Math.exp(-2.0D * Math.PI * safeCutoff / sampleRate);
        }

        private static float finiteSample(float sample) {
            return Float.isFinite(sample) ? sample : 0.0F;
        }

        private static float softLimit(float sample) {
            sample = finiteSample(sample);
            float absolute = Math.abs(sample);
            if (absolute <= 0.98F) {
                return sample;
            }
            float limited = 0.98F + 0.02F * (float) Math.tanh((absolute - 0.98F) / 0.02F);
            return Math.copySign(Math.min(1.0F, limited), sample);
        }
    }

    private static final class CombFilter {
        private final float[] buffer;
        private int index;
        private float dampedState;

        private CombFilter(int length) {
            buffer = new float[length];
        }

        private float process(float input, float feedback, float damping) {
            float delayed = buffer[index];
            dampedState = delayed * (1.0F - damping) + dampedState * damping;
            if (Math.abs(dampedState) < 1.0E-20F) {
                dampedState = 0.0F;
            }
            buffer[index] = Mth.clamp(input + dampedState * feedback, -1.5F, 1.5F);
            index = index + 1 == buffer.length ? 0 : index + 1;
            return delayed;
        }

        private void clear() {
            Arrays.fill(buffer, 0.0F);
            index = 0;
            dampedState = 0.0F;
        }
    }

    private static final class AllPassFilter {
        private static final float FEEDBACK = 0.5F;
        private final float[] buffer;
        private int index;

        private AllPassFilter(int length) {
            buffer = new float[length];
        }

        private float process(float input) {
            float delayed = buffer[index];
            float output = delayed - input;
            buffer[index] = Mth.clamp(input + delayed * FEEDBACK, -1.5F, 1.5F);
            index = index + 1 == buffer.length ? 0 : index + 1;
            return output;
        }

        private void clear() {
            Arrays.fill(buffer, 0.0F);
            index = 0;
        }
    }

    private record Settings(
            boolean masterEnabled,
            boolean reverbEnabled,
            float reverbWet,
            float reverbRoom,
            float reverbDamping,
            boolean echoEnabled,
            float echoWet,
            float echoTimeMs,
            float echoFeedback,
            boolean lowPassEnabled,
            float lowPassCutoffHz
    ) {
        private static Settings disabled() {
            return new Settings(false, false, 0.30F, 0.65F, 0.45F,
                    false, 0.25F, 280.0F, 0.35F,
                    false, 12_000.0F);
        }

        private static Settings sanitized(boolean masterEnabled,
                                          boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                                          boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                                          boolean lowPassEnabled, float lowPassCutoffHz) {
            return new Settings(
                    masterEnabled,
                    reverbEnabled,
                    unit(reverbWet, 0.30F),
                    unit(reverbRoom, 0.65F),
                    unit(reverbDamping, 0.45F),
                    echoEnabled,
                    Mth.clamp(finite(echoWet, 0.25F), 0.0F, 0.75F),
                    Mth.clamp(finite(echoTimeMs, 280.0F), MIN_ECHO_TIME_MS, MAX_ECHO_TIME_MS),
                    Mth.clamp(finite(echoFeedback, 0.35F), 0.0F, 0.85F),
                    lowPassEnabled,
                    Mth.clamp(finite(lowPassCutoffHz, 12_000.0F), MIN_LOW_PASS_HZ, MAX_LOW_PASS_HZ)
            );
        }

        private static float unit(float value, float fallback) {
            return Mth.clamp(finite(value, fallback), 0.0F, 1.0F);
        }

        private static float finite(float value, float fallback) {
            return Float.isFinite(value) ? value : fallback;
        }
    }
}
