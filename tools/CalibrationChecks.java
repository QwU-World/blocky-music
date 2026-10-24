package ru.gora.musicbattle.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import ru.gora.musicbattle.data.CalibrationTiming;

/** Standalone regression checks against the real mapped AudioStream interface. */
public final class CalibrationChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        require(CalibrationTiming.clampOffsetMs(Integer.MIN_VALUE) == -500, "negative setting bound");
        require(CalibrationTiming.clampOffsetMs(Integer.MAX_VALUE) == 500, "positive setting bound");
        for (int offset : new int[] {-500, -173, -1, 0, 1, 127, 500}) {
            require(CalibrationTiming.clampOffsetMs(offset) == offset, "persisted setting precision");
            for (double pitch : new double[] {1.0, 1.5, 2.0}) {
                double sourceTicks = 320.0 * pitch;
                double seconds = CalibrationTiming.sourceAudioSeconds(sourceTicks, pitch, offset);
                close(seconds / pitch, 16.0 - offset / 1000.0, "offset is runtime milliseconds at every pitch");
                close(CalibrationTiming.sourceAudioSeconds(0.0, pitch, offset),
                        -offset * pitch / 1000.0, "signed start padding/advance");
            }
        }
        require(CalibrationTiming.inputTick(20, 100) == 18, "positive input compensates late press");
        require(CalibrationTiming.inputTick(20, -100) == 22, "negative input compensates early press");
        require(CalibrationTiming.inputTick(0, 500) == -10, "negative judgement is not clamped toward zero");
        require(CalibrationTiming.inputTick(20.99, 49) == 20, "subtick input precision");
        require(CalibrationTiming.inputTick(20.01, 49) == 19, "subtick threshold preserved");
        require(CalibrationTiming.inputTick(20, Integer.MIN_VALUE) == 30, "input bound negative");
        require(CalibrationTiming.inputTick(20, Integer.MAX_VALUE) == 10, "input bound positive");
        require(Double.isFinite(CalibrationTiming.sourceAudioSeconds(Double.NaN, Double.NaN, 0)),
                "invalid audio clock is finite");

        // Decoder deliberately returns more than requested, just like FloatSampleSource.
        for (int channels : new int[] {1, 2}) {
            for (double seek : new double[] {0.0, 0.001, 0.0125, 0.077, 0.25, 1.8}) {
                FakePcm source = new FakePcm(channels, 1000, 2300);
                try (CalibratedAudioStream stream = new CalibratedAudioStream(source, seek, () -> false)) {
                    byte[] result = readAll(stream, 37);
                    int skipBytes = (int) Math.round(seek * 1000) * source.getFormat().getFrameSize();
                    require(Arrays.equals(result, Arrays.copyOfRange(source.data, skipBytes, source.data.length)),
                            "exact seek preserves all overread bytes, channels=" + channels + " seek=" + seek);
                }
                require(source.closeCount == 1, "seek closes delegate");
            }
            FakePcm source = new FakePcm(channels, 1000, 80);
            try (CalibratedAudioStream stream = new CalibratedAudioStream(source, -0.1, () -> false)) {
                byte[] result = readAll(stream, 37);
                int silenceBytes = 100 * source.getFormat().getFrameSize();
                require(result.length == silenceBytes + source.data.length, "padding length exact");
                require(Arrays.equals(Arrays.copyOf(result, silenceBytes), new byte[silenceBytes]), "padding is silent PCM");
                require(Arrays.equals(Arrays.copyOfRange(result, silenceBytes, result.length), source.data),
                        "padding preserves full audio after silence");
            }
            require(source.closeCount == 1, "padding closes delegate");
        }
        FakePcm pitched = new FakePcm(2, 1000, 20);
        try (CalibratedAudioStream stream = new CalibratedAudioStream(pitched,
                CalibrationTiming.sourceAudioSeconds(0, 2, 100), () -> false)) {
            require(readAll(stream, 128).length == pitched.data.length + 200 * 4, "2x pitch pads 200 source frames for 100ms");
        }
        FakePcm exhausted = new FakePcm(2, 1000, 10);
        try (CalibratedAudioStream stream = new CalibratedAudioStream(exhausted, 10, () -> false)) {
            require(readAll(stream, 64).length == 0, "seek past end terminates");
        }
        FakePcm canceled = new FakePcm(2, 1000, 100000);
        try {
            new CalibratedAudioStream(canceled, 90, () -> canceled.readCount > 0);
            throw new AssertionError("Canceled seek completed");
        } catch (CancellationException expected) {
            require(canceled.readCount == 1, "cancellation interrupts long seek after first decoder read");
        } finally {
            // Constructor failure retains delegate ownership in ExternalOggSoundInstance.
            canceled.close();
        }
        require(canceled.closeCount == 1, "canceled construction closes source via owner");
        FakePcm preCanceled = new FakePcm(2, 1000, 10);
        try {
            new CalibratedAudioStream(preCanceled, -0.5, () -> true);
            throw new AssertionError("Pre-canceled stream was created");
        } catch (CancellationException expected) {
            require(preCanceled.readCount == 0, "pre-canceled padding never decodes");
        } finally {
            preCanceled.close();
        }
        System.out.println("Calibration checks passed: " + checks);
    }

    private static byte[] readAll(AudioStream stream, int requestSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int read = 0; read < 100000; read++) {
            ByteBuffer bytes = stream.read(requestSize);
            if (bytes == null || !bytes.hasRemaining()) {
                return output.toByteArray();
            }
            require(bytes.remaining() % stream.getFormat().getFrameSize() == 0, "all returned PCM chunks stay frame aligned");
            while (bytes.hasRemaining()) {
                output.write(bytes.get() & 255);
            }
        }
        throw new AssertionError("PCM stream never ended");
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 0.0000001, message + ": " + actual + " != " + expected);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakePcm implements AudioStream {
        private final AudioFormat format;
        private final byte[] data;
        private int position;
        private int readCount;
        private int closeCount;

        private FakePcm(int channels, int sampleRate, int frames) {
            format = new AudioFormat(sampleRate, 16, channels, true, false);
            data = new byte[frames * format.getFrameSize()];
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) (1 + index % 251);
            }
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override public ByteBuffer read(int size) {
            readCount++;
            int frameSize = format.getFrameSize();
            int requested = ((Math.max(1, size) + frameSize - 1) / frameSize + 7) * frameSize;
            int count = Math.min(data.length - position, requested);
            ByteBuffer output = ByteBuffer.allocateDirect(count);
            output.put(data, position, count).flip();
            position += count;
            return output;
        }

        @Override public void close() { closeCount++; }
    }
}
