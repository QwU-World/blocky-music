import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/**
 * Writes the synthetic signal the decoder fixtures are encoded from, as a PCM WAV, and prints the SHA-256 of its
 * sample data (little-endian integers, interleaved), which the ALAC fixtures must decode back to exactly.
 *
 * <p>The material cycles every 1.6 s so a short clip exercises every coding tool: different content in each channel
 * (sweeps and tones), identical channels with clicks (M/S, intensity stereo, short windows), noise with an anti-phase
 * right channel (PNS, TNS), silence, and drum-like hits with high-frequency content (SBR). Only java.util.Random and
 * StrictMath are used, so the samples are the same on every machine.
 *
 * Usage: FixtureSignal out.wav rate channels seconds bits [seed] [level]
 */
public final class FixtureSignal {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int rate = Integer.parseInt(args[1]);
        int channels = Integer.parseInt(args[2]);
        double seconds = Double.parseDouble(args[3]);
        int bits = Integer.parseInt(args[4]);
        long seed = args.length > 5 ? Long.parseLong(args[5]) : 1;
        double level = args.length > 6 ? Double.parseDouble(args[6]) : 1.0;
        int n = (int) StrictMath.round(seconds * rate);
        double[][] x = new double[channels][n];
        Random r = new Random(seed);
        double phaseA = 0;
        double phaseB = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double seg = t % 1.6;
            double fA = 150 * StrictMath.pow(2, seg * 3.5);
            double fB = 9000 * StrictMath.pow(2, -seg * 3.0);
            phaseA += 2 * StrictMath.PI * fA / rate;
            phaseB += 2 * StrictMath.PI * fB / rate;
            double noise = r.nextGaussian();
            for (int c = 0; c < channels; c++) {
                double v;
                if (seg < 0.4) {
                    v = c % 2 == 0 ? 0.35 * StrictMath.sin(phaseA + c) + 0.1 * StrictMath.sin(2 * StrictMath.PI * 5000 * t)
                            : 0.3 * StrictMath.sin(phaseB + c) + 0.08 * StrictMath.sin(2 * StrictMath.PI * 15000 * t);
                } else if (seg < 0.8) {
                    v = 0.3 * StrictMath.sin(phaseA) + 0.12 * StrictMath.sin(phaseB)
                            + 0.05 * StrictMath.sin(2 * StrictMath.PI * 13000 * t);
                    double sinceClick = t % 0.1;
                    if (sinceClick < 0.004) {
                        v += 0.6 * StrictMath.exp(-sinceClick * 1500) * StrictMath.sin(2 * StrictMath.PI * 2500 * sinceClick);
                    }
                } else if (seg < 1.2) {
                    v = 0.08 * noise + 0.2 * StrictMath.sin(2 * StrictMath.PI * (440 + 220 * (c % 3)) * t)
                            + 0.04 * StrictMath.sin(2 * StrictMath.PI * 11000 * t + c);
                    if (c % 2 == 1) {
                        v = -v * 0.9 + 0.02 * r.nextGaussian();
                    }
                } else if (seg < 1.3) {
                    v = 0;
                } else {
                    double beat = t % 0.125;
                    v = 0.5 * StrictMath.exp(-beat * 40) * StrictMath.sin(2 * StrictMath.PI * 60 * beat)
                            + 0.15 * StrictMath.exp(-beat * 300) * r.nextGaussian()
                            + 0.1 * StrictMath.sin(2 * StrictMath.PI * 7000 * t) * (c + 1) / channels;
                }
                x[c][i] = v * level;
            }
        }
        int fade = Math.min(n / 4, rate / 200);
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < fade; i++) {
                double g = i / (double) fade;
                x[c][i] *= g;
                x[c][n - 1 - i] *= g;
            }
        }
        byte[] data = pcm(x, bits);
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)) + " " + n);
        writeWav(out, data, rate, channels, bits);
    }

    static byte[] pcm(double[][] x, int bits) {
        int channels = x.length;
        int n = x[0].length;
        int bytes = bits / 8;
        ByteBuffer data = ByteBuffer.allocate(n * channels * bytes).order(ByteOrder.LITTLE_ENDIAN);
        double max = (1L << (bits - 1)) - 1;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < channels; c++) {
                long v = StrictMath.round(Math.max(-1, Math.min(1, x[c][i])) * max);
                if (bytes == 2) {
                    data.putShort((short) v);
                } else if (bytes == 3) {
                    data.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16));
                } else {
                    data.putInt((int) v);
                }
            }
        }
        return data.array();
    }

    static void writeWav(Path out, byte[] data, int rate, int channels, int bits) throws IOException {
        int bytes = bits / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + data.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * bytes)
                .putShort((short) (channels * bytes)).putShort((short) bits).put("data".getBytes()).putInt(data.length);
        o.write(h.array());
        o.write(data);
        Files.write(out, o.toByteArray());
    }
}
