package ru.gora.musicbattle.client;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import net.minecraft.Util;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.MusicBattleMod;

/** Decodes cached OGG audio off-thread into real logarithmic spectrum frames for FX visualizers. */
public final class ClientAudioAnalyzer {
    private static final int WINDOW = 1024;
    private static final int BAND_COUNT = 32;
    private static final Map<Path, CompletableFuture<Analysis>> CACHE = new ConcurrentHashMap<>();
    private static volatile Path activePath;

    private ClientAudioAnalyzer() {
    }

    public static void activate(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            activePath = null;
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        activePath = normalized;
        CACHE.computeIfAbsent(normalized, ClientAudioAnalyzer::decodeAsync);
    }

    public static Spectrum sample(float trackTick) {
        Path path = activePath;
        if (path == null) {
            return Spectrum.EMPTY;
        }
        CompletableFuture<Analysis> future = CACHE.get(path);
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return Spectrum.EMPTY;
        }
        return future.getNow(Analysis.EMPTY).sample(Math.max(0.0F, trackTick) / 20.0F);
    }

    private static CompletableFuture<Analysis> decodeAsync(Path path) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream input = Files.newInputStream(path); JOrbisAudioStream stream = new JOrbisAudioStream(input)) {
                AudioFormat format = stream.getFormat();
                int channels = Math.max(1, format.getChannels());
                int frameSize = Math.max(channels * 2, format.getFrameSize());
                float sampleRate = Math.max(8000.0F, format.getSampleRate());
                float[] window = new float[WINDOW];
                int windowPos = 0;
                List<float[]> rawFrames = new ArrayList<>();
                while (true) {
                    ByteBuffer decoded = stream.read(64 * 1024);
                    if (!decoded.hasRemaining()) {
                        break;
                    }
                    decoded.order(ByteOrder.LITTLE_ENDIAN);
                    while (decoded.remaining() >= frameSize) {
                        int frameStart = decoded.position();
                        float mono = 0.0F;
                        for (int channel = 0; channel < channels; channel++) {
                            mono += decoded.getShort(frameStart + channel * 2) / 32768.0F;
                        }
                        decoded.position(frameStart + frameSize);
                        window[windowPos++] = mono / channels;
                        if (windowPos == WINDOW) {
                            rawFrames.add(analyzeWindow(window, sampleRate));
                            windowPos = 0;
                        }
                    }
                }
                if (windowPos > WINDOW / 4) {
                    java.util.Arrays.fill(window, windowPos, WINDOW, 0.0F);
                    rawFrames.add(analyzeWindow(window, sampleRate));
                }
                return normalize(rawFrames, sampleRate);
            } catch (Exception exception) {
                MusicBattleMod.LOGGER.warn("Unable to analyze Blocky Music audio '{}'.", path, exception);
                return Analysis.EMPTY;
            }
        }, Util.nonCriticalIoPool());
    }

    private static float[] analyzeWindow(float[] samples, float sampleRate) {
        float[] real = new float[WINDOW];
        float[] imaginary = new float[WINDOW];
        float rms = 0.0F;
        for (int i = 0; i < WINDOW; i++) {
            float hann = 0.5F - 0.5F * Mth.cos((float) (Math.PI * 2.0D * i / (WINDOW - 1)));
            real[i] = samples[i] * hann;
            rms += samples[i] * samples[i];
        }
        fft(real, imaginary);
        float[] bands = new float[BAND_COUNT + 1];
        float minHz = 45.0F;
        float maxHz = Math.min(16000.0F, sampleRate * 0.48F);
        for (int band = 0; band < BAND_COUNT; band++) {
            float lowHz = minHz * (float) Math.pow(maxHz / minHz, band / (double) BAND_COUNT);
            float highHz = minHz * (float) Math.pow(maxHz / minHz, (band + 1.0D) / BAND_COUNT);
            int lowBin = Mth.clamp(Math.round(lowHz * WINDOW / sampleRate), 1, WINDOW / 2 - 1);
            int highBin = Mth.clamp(Math.round(highHz * WINDOW / sampleRate), lowBin + 1, WINDOW / 2);
            float energy = 0.0F;
            for (int bin = lowBin; bin < highBin; bin++) {
                energy += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            }
            bands[band] = (float) Math.sqrt(energy / Math.max(1, highBin - lowBin));
        }
        bands[BAND_COUNT] = (float) Math.sqrt(rms / WINDOW);
        return bands;
    }

    private static void fft(float[] real, float[] imaginary) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float temp = real[i]; real[i] = real[j]; real[j] = temp;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2.0D * Math.PI / length;
            float wLenReal = (float) Math.cos(angle);
            float wLenImaginary = (float) Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                float wReal = 1.0F;
                float wImaginary = 0.0F;
                for (int offset = 0; offset < length / 2; offset++) {
                    int even = start + offset;
                    int odd = even + length / 2;
                    float oddReal = real[odd] * wReal - imaginary[odd] * wImaginary;
                    float oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal;
                    real[odd] = real[even] - oddReal;
                    imaginary[odd] = imaginary[even] - oddImaginary;
                    real[even] += oddReal;
                    imaginary[even] += oddImaginary;
                    float nextReal = wReal * wLenReal - wImaginary * wLenImaginary;
                    wImaginary = wReal * wLenImaginary + wImaginary * wLenReal;
                    wReal = nextReal;
                }
            }
        }
    }

    private static Analysis normalize(List<float[]> raw, float sampleRate) {
        if (raw.isEmpty()) {
            return Analysis.EMPTY;
        }
        float[] maxima = new float[BAND_COUNT + 1];
        for (float[] frame : raw) {
            for (int i = 0; i < maxima.length; i++) maxima[i] = Math.max(maxima[i], frame[i]);
        }
        float[][] frames = new float[raw.size()][BAND_COUNT];
        float[] levels = new float[raw.size()];
        float[] previous = new float[BAND_COUNT];
        for (int frameIndex = 0; frameIndex < raw.size(); frameIndex++) {
            float[] source = raw.get(frameIndex);
            for (int band = 0; band < BAND_COUNT; band++) {
                float normalized = Mth.clamp(source[band] / Math.max(0.00001F, maxima[band] * 0.72F), 0.0F, 1.0F);
                normalized = (float) Math.sqrt(normalized);
                float smoothed = Math.max(normalized, previous[band] * 0.74F);
                frames[frameIndex][band] = smoothed;
                previous[band] = smoothed;
            }
            levels[frameIndex] = Mth.clamp(source[BAND_COUNT] / Math.max(0.00001F, maxima[BAND_COUNT] * 0.68F), 0.0F, 1.0F);
        }
        return new Analysis(frames, levels, sampleRate / WINDOW);
    }

    private record Analysis(float[][] frames, float[] levels, float framesPerSecond) {
        private static final Analysis EMPTY = new Analysis(new float[0][], new float[0], 1.0F);

        Spectrum sample(float seconds) {
            if (frames.length == 0) return Spectrum.EMPTY;
            float frame = Mth.clamp(seconds * framesPerSecond, 0.0F, frames.length - 1.0F);
            int first = Mth.floor(frame);
            int second = Math.min(frames.length - 1, first + 1);
            float mix = frame - first;
            float[] result = new float[BAND_COUNT];
            for (int band = 0; band < BAND_COUNT; band++) {
                result[band] = Mth.lerp(mix, frames[first][band], frames[second][band]);
            }
            return new Spectrum(result, Mth.lerp(mix, levels[first], levels[second]), true);
        }
    }

    public record Spectrum(float[] bands, float level, boolean ready) {
        private static final Spectrum EMPTY = new Spectrum(new float[BAND_COUNT], 0.0F, false);
    }
}
