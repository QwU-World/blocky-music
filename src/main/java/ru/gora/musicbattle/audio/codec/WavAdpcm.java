package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/**
 * The ADPCM codecs WAV files carry, decoded block by block from the data chunk: IMA ADPCM (format 0x11, the "IMA/DVI
 * ADPCM" of the IMA's Recommended Practices for Enhancing Digital Audio Compatibility, 1992, in Microsoft's WAV
 * block layout) and Microsoft ADPCM (format 0x02, as Microsoft's Multimedia Standards Update of 1994 describes it).
 * Both are written from those documents; the step and adaptation tables are theirs.
 *
 * <p>Output is what {@code ffmpeg -i song.wav -f f32le} gives, sample for sample: 16-bit samples scaled by 2^-15,
 * every block's samples in order, and a last block cut short by the end of the data decoded as far as its bytes go
 * (ffmpeg's rule: whole bytes of nibbles for Microsoft ADPCM, whole four-byte groups for IMA).
 *
 * <p>IMA block, per channel: a 16-bit first sample, the step index, a reserved byte; then groups of four bytes for
 * each channel in turn, eight samples a group, low nibble first. Microsoft block: for every channel a predictor
 * number, then every channel's 16-bit delta, then its second sample, then its first; the samples come out second
 * first, and the nibbles follow, high nibble first, the channels taking turns.
 */
final class WavAdpcm implements PcmStream {
    /** IMA step sizes, from the IMA recommendation. */
    private static final int[] IMA_STEPS = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88,
            97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660,
            4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818,
            18500, 20350, 22385, 24623, 27086, 29794, 32767};
    /** How the IMA step index moves for each magnitude of a nibble. */
    private static final int[] IMA_INDEX = {-1, -1, -1, -1, 2, 4, 6, 8};
    /** Microsoft ADPCM: how the delta adapts to each nibble, in 1/256. */
    private static final int[] MS_ADAPTATION = {
            230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230};
    /** The seven standard predictor coefficient pairs, in 1/256, which every encoder writes into the format chunk. */
    private static final short[] MS_STANDARD = {256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232};
    private static final float SCALE = 1.0f / (1 << 15);

    private final InputStream data;
    private final boolean microsoft;
    private final int channels;
    private final int sampleRate;
    private final int blockAlign;
    private final int samplesPerBlock;
    private final short[] coefficients;
    private final long frameCountHint;
    private final byte[] block;
    private final short[][] pcm;
    private int pcmAt;
    private int pcmEnd;
    private boolean ended;
    private boolean closed;

    WavAdpcm(InputStream data, boolean microsoft, int channels, int sampleRate, int blockAlign, int samplesPerBlock,
             short[] coefficients, long dataSize) {
        this.data = data;
        this.microsoft = microsoft;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.blockAlign = blockAlign;
        this.samplesPerBlock = samplesPerBlock;
        this.coefficients = coefficients != null ? coefficients : MS_STANDARD;
        this.block = new byte[blockAlign];
        this.pcm = new short[channels][samplesPerBlock];
        this.frameCountHint = dataSize < 0 ? -1
                : dataSize / blockAlign * samplesPerBlock + samplesIn((int) (dataSize % blockAlign));
    }

    /** The samples a block of {@code bytes} bytes holds, for a whole block and one cut short. */
    private int samplesIn(int bytes) {
        if (microsoft) {
            int header = 7 * channels;
            return bytes < header ? 0 : 2 + (bytes - header) * 2 / channels;
        }
        int header = 4 * channels;
        return bytes < header ? 0 : 1 + (bytes - header) / (4 * channels) * 8;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public long frameCountHint() {
        return frameCountHint;
    }

    @Override
    public int read(float[][] out, int offset, int frames) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (frames <= 0) {
            return 0;
        }
        while (pcmAt >= pcmEnd) {
            if (ended) {
                return -1;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("WAV decoding interrupted");
            }
            int got = data.readNBytes(block, 0, blockAlign);
            if (got < blockAlign) {
                ended = true;
            }
            int samples = Math.min(samplesPerBlock, samplesIn(got));
            if (samples == 0) {
                ended = true;
                return -1;
            }
            if (microsoft) {
                decodeMicrosoft(got, samples);
            } else {
                decodeIma(samples);
            }
            pcmAt = 0;
            pcmEnd = samples;
        }
        int n = Math.min(frames, pcmEnd - pcmAt);
        for (int c = 0; c < channels; c++) {
            short[] from = pcm[c];
            float[] to = out[c];
            for (int i = 0; i < n; i++) {
                to[offset + i] = from[pcmAt + i] * SCALE;
            }
        }
        pcmAt += n;
        return n;
    }

    private void decodeIma(int samples) throws AudioFormatException {
        int[] predictor = new int[channels];
        int[] index = new int[channels];
        for (int c = 0; c < channels; c++) {
            int p = 4 * c;
            predictor[c] = (short) ((block[p] & 0xFF) | block[p + 1] << 8);
            index[c] = block[p + 2] & 0xFF;
            if (index[c] > 88) {
                // ffmpeg reads a step index past the table as the last one, with a warning; so does this.
                index[c] = 88;
            }
            pcm[c][0] = (short) predictor[c];
        }
        int groups = (samples - 1) / 8;
        int p = 4 * channels;
        for (int g = 0; g < groups; g++) {
            for (int c = 0; c < channels; c++) {
                short[] target = pcm[c];
                int at = 1 + 8 * g;
                for (int k = 0; k < 4; k++) {
                    int b = block[p++] & 0xFF;
                    for (int half = 0; half < 2; half++) {
                        int nibble = half == 0 ? b & 0x0F : b >> 4;
                        int step = IMA_STEPS[index[c]];
                        // The recommendation's way: an eighth of the step, plus the step, a half and a quarter of it
                        // for each magnitude bit, so the rounding is that of the shifts.
                        int diff = step >> 3;
                        if ((nibble & 4) != 0) {
                            diff += step;
                        }
                        if ((nibble & 2) != 0) {
                            diff += step >> 1;
                        }
                        if ((nibble & 1) != 0) {
                            diff += step >> 2;
                        }
                        int value = (nibble & 8) != 0 ? predictor[c] - diff : predictor[c] + diff;
                        predictor[c] = Math.max(-32768, Math.min(32767, value));
                        index[c] = Math.max(0, Math.min(88, index[c] + IMA_INDEX[nibble & 7]));
                        target[at++] = (short) predictor[c];
                    }
                }
            }
        }
    }

    private void decodeMicrosoft(int bytes, int samples) throws AudioFormatException {
        int[] coef1 = new int[channels];
        int[] coef2 = new int[channels];
        int[] delta = new int[channels];
        int[] sample1 = new int[channels];
        int[] sample2 = new int[channels];
        int pairs = coefficients.length / 2;
        for (int c = 0; c < channels; c++) {
            int predictor = block[c] & 0xFF;
            if (predictor >= pairs) {
                throw new AudioFormatException("Damaged MS ADPCM block: predictor " + predictor);
            }
            coef1[c] = coefficients[2 * predictor];
            coef2[c] = coefficients[2 * predictor + 1];
            delta[c] = s16(channels + 2 * c);
            sample1[c] = s16(3 * channels + 2 * c);
            sample2[c] = s16(5 * channels + 2 * c);
            pcm[c][0] = (short) sample2[c];
            pcm[c][1] = (short) sample1[c];
        }
        int p = 7 * channels;
        int c = 0;
        int at = 2;
        for (int k = 0; k < (samples - 2) * channels && p < bytes; k++) {
            int b = block[p];
            int nibble = (k & 1) == 0 ? (b >> 4) & 0x0F : b & 0x0F;
            if ((k & 1) == 1) {
                p++;
            }
            int signed = nibble >= 8 ? nibble - 16 : nibble;
            // The prediction in 1/256 and truncated toward zero, as the standard's integer division does.
            int predicted = (sample1[c] * coef1[c] + sample2[c] * coef2[c]) / 256;
            long value = predicted + (long) signed * delta[c];
            int clamped = (int) Math.max(-32768, Math.min(32767, value));
            sample2[c] = sample1[c];
            sample1[c] = clamped;
            pcm[c][at] = (short) clamped;
            delta[c] = (int) ((long) MS_ADAPTATION[nibble] * delta[c] >> 8);
            if (delta[c] < 16) {
                delta[c] = 16;
            } else if (delta[c] > Integer.MAX_VALUE / 768) {
                // Only a damaged stream gets here; the delta is held where the products above cannot overflow.
                delta[c] = Integer.MAX_VALUE / 768;
            }
            if (++c == channels) {
                c = 0;
                at++;
            }
        }
    }

    private int s16(int at) {
        return (short) ((block[at] & 0xFF) | block[at + 1] << 8);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            data.close();
        }
    }
}
