import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Self-made test signals for the AAC review, written as 24-bit PCM WAV (16-bit with "-16"). Nothing here is
 * recorded or copied music: every sample is computed from the formulas below, so the files can be shared freely.
 *
 * Usage: java Gen <kind> <rate> <channels> <seconds-or-frames> <out.wav> [bits]
 * A length ending in "f" is a frame count (for odd lengths), otherwise seconds.
 */
public final class Gen {
    public static void main(String[] a) throws IOException {
        String kind = a[0];
        int rate = Integer.parseInt(a[1]);
        int ch = Integer.parseInt(a[2]);
        long frames = a[3].endsWith("f") ? Long.parseLong(a[3].substring(0, a[3].length() - 1))
                : Math.round(Double.parseDouble(a[3]) * rate);
        Path out = Path.of(a[4]);
        int bits = a.length > 5 ? Integer.parseInt(a[5]) : 24;
        write(out, rate, ch, frames, bits, kind);
    }

    interface Source {
        void fill(long start, int n, double[][] buf);
    }

    static void write(Path out, int rate, int ch, long frames, int bits, String kind) throws IOException {
        Source s = source(kind, rate, ch);
        int bytes = bits / 8;
        long dataBytes = frames * ch * bytes;
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
            o.write("RIFF".getBytes());
            le32(o, 36 + dataBytes);
            o.write("WAVEfmt ".getBytes());
            le32(o, 16);
            le16(o, 1);
            le16(o, ch);
            le32(o, rate);
            le32(o, (long) rate * ch * bytes);
            le16(o, ch * bytes);
            le16(o, bits);
            o.write("data".getBytes());
            le32(o, dataBytes);
            int block = 4096;
            double[][] buf = new double[ch][block];
            byte[] pcm = new byte[block * ch * bytes];
            double full = (1L << (bits - 1)) - 1;
            for (long at = 0; at < frames; at += block) {
                int n = (int) Math.min(block, frames - at);
                for (double[] b : buf) {
                    java.util.Arrays.fill(b, 0);
                }
                s.fill(at, n, buf);
                int p = 0;
                for (int i = 0; i < n; i++) {
                    for (int c = 0; c < ch; c++) {
                        double v = Math.max(-1, Math.min(1, buf[c][i]));
                        long q = Math.round(v * full);
                        for (int k = 0; k < bytes; k++) {
                            pcm[p++] = (byte) (q >> (8 * k));
                        }
                    }
                }
                o.write(pcm, 0, p);
            }
        }
    }

    static void le32(OutputStream o, long v) throws IOException {
        o.write((int) v);
        o.write((int) (v >> 8));
        o.write((int) (v >> 16));
        o.write((int) (v >> 24));
    }

    static void le16(OutputStream o, int v) throws IOException {
        o.write(v);
        o.write(v >> 8);
    }

    static Source source(String kind, int rate, int ch) {
        switch (kind) {
            case "music":
                return music(rate, ch, 1);
            case "music2":
                return music(rate, ch, 2);
            case "castanets":
                return castanets(rate, ch);
            case "sweep":
                return (start, n, buf) -> {
                    double dur = 15.0;
                    for (int i = 0; i < n; i++) {
                        double t = ((start + i) % (long) (dur * rate)) / (double) rate;
                        for (int c = 0; c < ch; c++) {
                            double f0 = c == 0 ? 20 : 40;
                            double f1 = Math.min(rate / 2.0 - 200, 22000);
                            double k = Math.log(f1 / f0) / dur;
                            double phase = 2 * Math.PI * f0 * (Math.exp(k * t) - 1) / k;
                            buf[c][i] = 0.5 * Math.sin(phase);
                        }
                    }
                };
            case "noise":
                return noise(rate, ch);
            case "impulses":
                // A positive spike every 0.5 s starting at 0.25 s, each followed by a short ringing decay, so a
                // decoder's offset against the source can be read off the peak.
                return (start, n, buf) -> {
                    for (int i = 0; i < n; i++) {
                        long f = start + i;
                        long period = rate / 2;
                        long sinceSpike = (f - rate / 4) % period;
                        if (f >= rate / 4 && sinceSpike < 64) {
                            double env = Math.exp(-sinceSpike / 6.0);
                            double v = 0.8 * env * Math.cos(2 * Math.PI * 3000 * sinceSpike / rate);
                            for (int c = 0; c < ch; c++) {
                                buf[c][i] = c % 2 == 0 ? v : -v * 0.7;
                            }
                        }
                    }
                };
            case "speech":
                return speech(rate, ch);
            case "loud":
                return (start, n, buf) -> {
                    Random r = new Random(start * 31 + 7);
                    for (int i = 0; i < n; i++) {
                        double t = (start + i) / (double) rate;
                        double sq = Math.sin(2 * Math.PI * 110 * t) >= 0 ? 0.98 : -0.98;
                        for (int c = 0; c < ch; c++) {
                            buf[c][i] = c == 0 ? sq : 0.98 * Math.signum(Math.sin(2 * Math.PI * 233 * t + 0.3))
                                    * (0.9 + 0.1 * r.nextDouble());
                        }
                    }
                };
            case "quiet":
                return (start, n, buf) -> {
                    for (int i = 0; i < n; i++) {
                        double t = (start + i) / (double) rate;
                        for (int c = 0; c < ch; c++) {
                            buf[c][i] = 0.001 * Math.sin(2 * Math.PI * (440 + 110 * c) * t)
                                    + 0.0003 * Math.sin(2 * Math.PI * 5000 * t);
                        }
                    }
                };
            case "same":
                // Identical channels (M/S and intensity stereo get used).
                return (start, n, buf) -> {
                    Source m = music(rate, 1, 3);
                    double[][] mono = new double[1][n];
                    m.fill(start, n, mono);
                    for (int c = 0; c < ch; c++) {
                        System.arraycopy(mono[0], 0, buf[c], 0, n);
                    }
                };
            case "antiphase":
                return (start, n, buf) -> {
                    Source m = music(rate, 1, 4);
                    double[][] mono = new double[1][n];
                    m.fill(start, n, mono);
                    for (int c = 0; c < ch; c++) {
                        for (int i = 0; i < n; i++) {
                            buf[c][i] = c % 2 == 0 ? mono[0][i] : -mono[0][i];
                        }
                    }
                };
            case "surround":
                // A different instrument per channel, so a channel mix-up shows.
                return (start, n, buf) -> {
                    for (int c = 0; c < ch; c++) {
                        double f = 110 * (c + 2);
                        for (int i = 0; i < n; i++) {
                            double t = (start + i) / (double) rate;
                            double env = 0.5 + 0.5 * Math.sin(2 * Math.PI * (0.5 + 0.25 * c) * t);
                            double v = 0;
                            for (int h = 1; h <= 8; h++) {
                                v += Math.sin(2 * Math.PI * f * h * t + c) / h;
                            }
                            buf[c][i] = 0.25 * env * v;
                        }
                    }
                };
            default:
                throw new IllegalArgumentException(kind);
        }
    }

    /** A 120 BPM pattern: kick, snare, hi-hats, bass, pad chords and a lead, panned. */
    static Source music(int rate, int ch, int seed) {
        return (start, n, buf) -> {
            double beat = 0.5;
            int[] bassNotes = {45, 45, 48, 43, 41, 41, 43, 40};
            int[][] chords = {{57, 60, 64}, {55, 59, 62}, {53, 57, 60}, {52, 55, 59}};
            int[] lead = {69, 72, 74, 76, 74, 72, 69, 67, 69, 71, 72, 76, 79, 76, 74, 72};
            for (int i = 0; i < n; i++) {
                long f = start + i;
                double t = f / (double) rate;
                double inBeat = t % beat;
                int beatNo = (int) (t / beat);
                // Kick: a pitch-falling sine.
                double kick = Math.exp(-inBeat * 18) * Math.sin(2 * Math.PI * (50 * inBeat + 60 * (1 - Math.exp(-inBeat * 30)) / 30 * 3));
                // Snare on 2 and 4: noise burst plus a tone.
                double snare = 0;
                if (beatNo % 2 == 1) {
                    long h = (f * 2654435761L + seed * 97) & 0xFFFFFF;
                    double nz = (h / (double) 0xFFFFFF) * 2 - 1;
                    snare = Math.exp(-inBeat * 25) * (0.6 * nz + 0.4 * Math.sin(2 * Math.PI * 190 * inBeat));
                }
                // Hi-hat on eighths: hashed noise differentiated twice (a crude high-pass).
                double inEighth = t % (beat / 2);
                long h1 = ((f) * 0x9E3779B97F4A7C15L + seed) >>> 40;
                long h2 = ((f - 1) * 0x9E3779B97F4A7C15L + seed) >>> 40;
                double hat = Math.exp(-inEighth * 60) * ((h1 - h2) / (double) (1L << 24)) * 0.5;
                // Bass: a band-limited saw.
                double bf = 440 * Math.pow(2, (bassNotes[(beatNo / 2) % bassNotes.length] - 69) / 12.0);
                double bass = 0;
                for (int k = 1; k <= 12 && bf * k < rate / 2.0; k++) {
                    bass += Math.sin(2 * Math.PI * bf * k * t) / k;
                }
                bass *= 0.25 * (0.6 + 0.4 * Math.exp(-inBeat * 4));
                // Pad: chords with slow vibrato.
                int[] chord = chords[(beatNo / 8) % chords.length];
                double pad = 0;
                for (int note : chord) {
                    double pf = 440 * Math.pow(2, (note - 69) / 12.0) * (1 + 0.003 * Math.sin(2 * Math.PI * 5 * t));
                    for (int k = 1; k <= 6 && pf * k < rate / 2.0; k++) {
                        pad += Math.sin(2 * Math.PI * pf * k * t + k) / (k * k);
                    }
                }
                pad *= 0.08;
                // Lead: square-ish with harmonics up to 16 kHz, a note per eighth.
                int note = lead[(int) (t / (beat / 2)) % lead.length];
                double lf = 440 * Math.pow(2, (note - 69) / 12.0);
                double ld = 0;
                for (int k = 1; k <= 41 && lf * k < Math.min(16000, rate / 2.0); k += 2) {
                    ld += Math.sin(2 * Math.PI * lf * k * t) / k;
                }
                ld *= 0.12 * Math.exp(-inEighth * 3);
                double mid = 0.55 * kick + 0.35 * snare + 0.3 * bass;
                double left = mid + 0.8 * pad + 0.4 * ld + 0.6 * hat;
                double right = mid + 0.6 * pad + 0.8 * ld + 0.3 * hat;
                if (ch == 1) {
                    buf[0][i] = 0.5 * (left + right);
                } else {
                    buf[0][i] = left;
                    buf[1][i] = right;
                    for (int c = 2; c < ch; c++) {
                        buf[c][i] = 0.5 * (c % 2 == 0 ? pad : ld + hat);
                    }
                }
            }
        };
    }

    /** Sharp clicks at irregular intervals: the material that makes encoders switch to short windows. */
    static Source castanets(int rate, int ch) {
        return (start, n, buf) -> {
            for (int i = 0; i < n; i++) {
                long f = start + i;
                // Click positions: a pseudo-random spacing of 40-200 ms, computed from the click index.
                double t = f / (double) rate;
                long slot = (long) (t / 0.04);
                double v = 0;
                for (long s = Math.max(0, slot - 2); s <= slot; s++) {
                    long h = (s * 0x9E3779B97F4A7C15L) >>> 33;
                    if (h % 3 != 0) {
                        continue;
                    }
                    double clickAt = s * 0.04 + (h % 1000) / 1000.0 * 0.03;
                    double dt = t - clickAt;
                    if (dt >= 0 && dt < 0.03) {
                        double fr = 2000 + (h % 5000);
                        v += 0.9 * Math.exp(-dt * 400) * Math.sin(2 * Math.PI * fr * dt)
                                + 0.5 * Math.exp(-dt * 3000) * (((h >> 3) & 1) == 0 ? 1 : -1);
                    }
                }
                for (int c = 0; c < ch; c++) {
                    buf[c][i] = c == 0 ? v : v * 0.8 + 0.05 * Math.sin(2 * Math.PI * 220 * t);
                }
            }
        };
    }

    static Source noise(int rate, int ch) {
        return (start, n, buf) -> {
            Random r = new Random(1234 + start);
            double[] pink = new double[7];
            for (int i = 0; i < n; i++) {
                double w = r.nextGaussian() * 0.15;
                // Paul Kellet's economy pink filter coefficients are published numbers; a simple 3-pole version.
                pink[0] = 0.99765 * pink[0] + w * 0.0990460;
                pink[1] = 0.96300 * pink[1] + w * 0.2965164;
                pink[2] = 0.57000 * pink[2] + w * 1.0526913;
                double p = pink[0] + pink[1] + pink[2] + w * 0.1848;
                double t = (start + i) / (double) rate;
                double gate = (t % 2.0) < 1.5 ? 1 : 0.1;
                for (int c = 0; c < ch; c++) {
                    buf[c][i] = gate * (c == 0 ? w * 1.5 : p * 0.25);
                }
            }
        };
    }

    /** A crude voice: a glottal pulse train through three formant resonators, with pauses. */
    static Source speech(int rate, int ch) {
        return (start, n, buf) -> {
            for (int i = 0; i < n; i++) {
                long f = start + i;
                double t = f / (double) rate;
                double syl = t % 0.35;
                boolean voiced = syl < 0.25 && (t % 3.0) < 2.4;
                double pitch = 120 + 30 * Math.sin(2 * Math.PI * 0.7 * t);
                double v = 0;
                if (voiced) {
                    double ph = (t * pitch) % 1.0;
                    double[] form = {700 + 200 * Math.sin(t * 3), 1200 + 400 * Math.sin(t * 2.3), 2600};
                    for (int k = 0; k < form.length; k++) {
                        v += Math.exp(-ph / pitch * 900 * (k + 1)) * Math.sin(2 * Math.PI * form[k] * ph / pitch)
                                / (k + 1);
                    }
                    v *= 0.5 * Math.sin(Math.PI * syl / 0.25);
                } else if (syl > 0.27 && syl < 0.31) {
                    long h = (f * 0x9E3779B97F4A7C15L) >>> 40;
                    v = 0.1 * ((h / (double) (1L << 24)) * 2 - 1);
                }
                for (int c = 0; c < ch; c++) {
                    buf[c][i] = v;
                }
            }
        };
    }
}
