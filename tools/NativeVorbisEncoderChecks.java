package ru.gora.musicbattle.audio.codec;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.sounds.JOrbisAudioStream;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The pure-Java Vorbis encoder (VorbisEncoder), from its smallest parts to whole streams read back by real decoders.
 *
 * <ul>
 *     <li>Parts: the forward MDCT against the textbook sum and the specification's unscaled inverse, the windows'
 *         power complementarity, length-limited Huffman codes (complete trees, the decoders' codeword rule), every
 *         floor-1 post value against the specification's decoding, square-polar coupling against its inverse, and
 *         the codebook float format.</li>
 *     <li>Streams: silence, tones, a sweep, white noise and clicks, mono and stereo, 8 to 192 kHz, from 0 samples
 *         through one short block, one long block and odd lengths to seconds. Every Ogg page is checked (capture,
 *         CRC, sequence, first and last flags, granules), so are the three headers, and then each stream is decoded
 *         by Minecraft's own decoder (JOrbisAudioStream), by LWJGL's stb_vorbis both whole-file and in pushdata
 *         mode, and by the mod's pure-Java VorbisDecoder when this build has it. Each must return exactly the
 *         source's frame count with no offset (cross-correlation peak at lag 0), the decoders must agree with each
 *         other, and tones and noise must clear SNR floors.</li>
 *     <li>Behaviour: identical bytes for identical input, prompt InterruptedIOException, refusals for formats it
 *         does not write.</li>
 * </ul>
 *
 * <p>No ffmpeg and no game: the decoders run in this JVM. What it cannot prove is how the result sounds; the
 * listening-free evidence for that (noise-to-mask ratios on real songs) is in the encoder's notes.
 */
public final class NativeVorbisEncoderChecks {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static Method javaDecoder;

    private NativeVorbisEncoderChecks() {
    }

    public static void main(String[] args) throws Exception {
        try {
            javaDecoder = Class.forName("ru.gora.musicbattle.audio.codec.VorbisDecoder")
                    .getMethod("open", InputStream.class);
            System.out.println("pure-Java VorbisDecoder: present, decoding with it too");
        } catch (ReflectiveOperationException absent) {
            System.out.println("pure-Java VorbisDecoder: not in this build, decoding with JOrbis and stb_vorbis only");
        }
        mdct();
        windows();
        huffman();
        floorValues();
        coupling();
        packedFloats();
        streams();
        determinism();
        refusals();
        interruption();
        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " of " + checks + " checks:");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
        System.out.println("PASS " + checks + " checks");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    // ---------------------------------------------------------------- parts

    private static void mdct() {
        Random random = new Random(11);
        for (int n : new int[] {256, 2048}) {
            MdctForward forward = new MdctForward(n);
            float[] in = new float[n];
            for (int i = 0; i < n; i++) {
                in[i] = (float) random.nextGaussian();
            }
            float[] out = new float[n / 2];
            forward.forward(in, out);
            double worst = 0;
            for (int k = 0; k < n / 2; k++) {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += in[i] * Math.cos(2 * Math.PI / n * (i + 0.5 + n / 4.0) * (k + 0.5));
                }
                worst = Math.max(worst, Math.abs(sum * 4.0 / n - out[k]));
            }
            check(worst < 1e-5, "MDCT " + n + " differs from the direct sum by " + worst);

            // Two overlapping blocks through the specification's unscaled inverse give the input back.
            float[] window = VorbisEncoderCore.window(n, true, true);
            int half = n / 2;
            float[] signal = new float[3 * half];
            for (int i = 0; i < signal.length; i++) {
                signal[i] = (float) random.nextGaussian();
            }
            double[] rebuilt = new double[3 * half];
            for (int b = 0; b < 2; b++) {
                float[] block = new float[n];
                for (int i = 0; i < n; i++) {
                    block[i] = signal[b * half + i] * window[i];
                }
                float[] coefficients = new float[half];
                forward.forward(block, coefficients);
                for (int i = 0; i < n; i++) {
                    double sum = 0;
                    for (int k = 0; k < half; k++) {
                        sum += coefficients[k] * Math.cos(2 * Math.PI / n * (i + 0.5 + n / 4.0) * (k + 0.5));
                    }
                    rebuilt[b * half + i] += sum * window[i];
                }
            }
            double error = 0;
            for (int i = half; i < 2 * half; i++) {
                error = Math.max(error, Math.abs(rebuilt[i] - signal[i]));
            }
            check(error < 1e-4, "MDCT " + n + " with the Vorbis inverse does not reconstruct: " + error);
        }
    }

    private static void windows() {
        float[] shortWindow = VorbisEncoderCore.window(256, false, false);
        float[] longLong = VorbisEncoderCore.window(2048, true, true);
        float[] longShortBoth = VorbisEncoderCore.window(2048, false, false);
        double worst = 0;
        for (int i = 0; i < 128; i++) {
            worst = Math.max(worst, Math.abs(shortWindow[i] * shortWindow[i]
                    + shortWindow[i + 128] * shortWindow[i + 128] - 1));
        }
        for (int i = 0; i < 1024; i++) {
            worst = Math.max(worst, Math.abs(longLong[i] * longLong[i] + longLong[i + 1024] * longLong[i + 1024] - 1));
        }
        // A long block next to a short one: its slope must be the short block's own, 128 samples at the quarter.
        for (int i = 0; i < 128; i++) {
            worst = Math.max(worst, Math.abs(longShortBoth[448 + i] - shortWindow[i]));
            worst = Math.max(worst, Math.abs(longShortBoth[1472 + i] - shortWindow[128 + i]));
        }
        check(longShortBoth[447] == 0f && longShortBoth[576] == 1f && longShortBoth[1471] == 1f
                && longShortBoth[1600] == 0f, "long window beside short blocks has the wrong flat parts");
        check(worst < 1e-6, "Vorbis windows are not power-complementary: " + worst);
    }

    private static void huffman() {
        Random random = new Random(5);
        int[] sizes = {8, 25, 32, 64, 81, 100, 128, 289, 625};
        for (int size : sizes) {
            for (int trial = 0; trial < 4; trial++) {
                long[] weights = new long[size];
                for (int i = 0; i < size; i++) {
                    // Real counts are wildly skewed: mostly unused entries, a few heavy ones.
                    weights[i] = trial == 0 ? 1 : 1 + (random.nextInt(4) == 0 ? (long) Math.pow(10, random.nextInt(9)) : 0);
                }
                int[] lengths = HuffmanLengths.lengths(weights, VorbisEncoderLayout.MAX_CODEWORD_LENGTH);
                double kraft = 0;
                int longest = 0;
                for (int length : lengths) {
                    kraft += Math.pow(2, -length);
                    longest = Math.max(longest, length);
                }
                check(Math.abs(kraft - 1) < 1e-12, "Huffman tree of " + size + " entries is not complete: " + kraft);
                check(longest <= VorbisEncoderLayout.MAX_CODEWORD_LENGTH, "Huffman code longer than the limit");
                int[] words = HuffmanLengths.codewords(lengths);
                check(prefixFree(words, lengths), "codewords of " + size + " entries are not prefix-free");
                check(Arrays.equals(words, referenceCodewords(lengths)),
                        "codewords of " + size + " entries differ from the decoders' assignment");
            }
        }
    }

    private static boolean prefixFree(int[] words, int[] lengths) {
        for (int a = 0; a < words.length; a++) {
            for (int b = 0; b < words.length; b++) {
                if (a == b || lengths[a] > lengths[b]) {
                    continue;
                }
                if ((words[b] >>> (lengths[b] - lengths[a])) == words[a]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The assignment as a bit tree, the way the specification describes it: lowest free node of each length. */
    private static int[] referenceCodewords(int[] lengths) {
        List<long[]> free = new ArrayList<>();
        free.add(new long[] {0, 0});
        int[] words = new int[lengths.length];
        for (int i = 0; i < lengths.length; i++) {
            int len = lengths[i];
            int best = -1;
            for (int f = 0; f < free.size(); f++) {
                long[] node = free.get(f);
                if (node[1] <= len && (best < 0
                        || (node[0] << (32 - node[1])) < (free.get(best)[0] << (32 - free.get(best)[1])))) {
                    best = f;
                }
            }
            if (best < 0) {
                return null;
            }
            long[] node = free.remove(best);
            long word = node[0];
            int depth = (int) node[1];
            while (depth < len) {
                free.add(new long[] {(word << 1) | 1, depth + 1});
                word <<= 1;
                depth++;
            }
            words[i] = (int) word;
        }
        return words;
    }

    private static void floorValues() {
        int range = VorbisEncoderFloor.RANGE;
        boolean ok = true;
        for (int predicted = 0; predicted < range; predicted++) {
            for (int wanted = 0; wanted < range; wanted++) {
                if (wanted == predicted) {
                    continue;
                }
                int value = VorbisEncoderFloor.encodeValue(wanted, predicted);
                // The specification's decoding of a non-zero floor-1 value.
                int highRoom = range - predicted;
                int lowRoom = predicted;
                int room = highRoom < lowRoom ? highRoom * 2 : lowRoom * 2;
                int decoded;
                if (value >= room) {
                    decoded = highRoom > lowRoom ? value - lowRoom + predicted : predicted - value + highRoom - 1;
                } else {
                    decoded = (value & 1) != 0 ? predicted - ((value + 1) >> 1) : predicted + (value >> 1);
                }
                if (value <= 0 || value >= range || decoded != wanted) {
                    ok = false;
                }
            }
        }
        check(ok, "a floor-1 post value does not decode back to the wanted value");
    }

    private static void coupling() {
        Random random = new Random(3);
        int[] left = new int[4000];
        int[] right = new int[4000];
        for (int i = 0; i < left.length; i++) {
            left[i] = random.nextInt(61) - 30;
            right[i] = i % 7 == 0 ? left[i] : random.nextInt(61) - 30;
        }
        int[] magnitude = left.clone();
        int[] angle = right.clone();
        VorbisEncoderCore.couple(magnitude, angle, left.length);
        boolean ok = true;
        for (int i = 0; i < left.length; i++) {
            int m = magnitude[i];
            int a = angle[i];
            int l;
            int r;
            if (m > 0) {
                if (a > 0) {
                    l = m;
                    r = m - a;
                } else {
                    r = m;
                    l = m + a;
                }
            } else if (a > 0) {
                l = m;
                r = m + a;
            } else {
                r = m;
                l = m - a;
            }
            ok &= l == left[i] && r == right[i];
        }
        check(ok, "square-polar coupling does not invert");
    }

    private static void packedFloats() {
        boolean ok = true;
        for (int v = -1024; v <= 1024; v++) {
            long packed = VorbisEncoderLayout.packFloat(v);
            long mantissa = packed & 0x1FFFFF;
            long exponent = (packed & 0x7FE00000L) >>> 21;
            double value = Math.scalb((double) mantissa, (int) exponent - 788) * ((packed & 0x80000000L) != 0 ? -1 : 1);
            ok &= value == v;
        }
        check(ok, "codebook floats do not round-trip");
    }

    // ---------------------------------------------------------------- streams

    private static void streams() throws Exception {
        String[] kinds = {"silence", "sine", "sweep", "noise", "clicks"};
        int[] lengths = {0, 1, 255, 256, 2048, 44101, 100003};
        for (String kind : kinds) {
            for (int channels = 1; channels <= 2; channels++) {
                for (int length : lengths) {
                    stream(kind, channels, 44100, length, 5f);
                }
                stream(kind, channels, 22050, 33077, 5f);
                stream(kind, channels, 48000, 72001, 5f);
            }
        }
        for (int rate : new int[] {8000, 11025, 16000, 32000, 96000, 192000}) {
            stream("noise", 2, rate, rate + 17, 5f);
        }
        for (float quality : new float[] {-1f, 0f, 3f, 7f, 10f}) {
            stream("noise", 2, 44100, 50001, quality);
        }
        stream("left-only", 2, 44100, 50001, 5f);
    }

    private static void stream(String kind, int channels, int rate, int length, float quality) throws Exception {
        String name = kind + " " + (channels == 1 ? "mono" : "stereo") + " " + rate + " Hz " + length + " q" + quality;
        float[][] source = signal(kind, channels, rate, length);
        byte[] ogg = encode(source, rate, new VorbisEncoder.Settings().quality(quality).title(name));
        pages(ogg, name, channels, rate, length);

        Map<String, float[][]> decoded = new LinkedHashMap<>();
        try {
            decoded.put("JOrbis", jorbis(ogg));
        } catch (Exception e) {
            check(false, name + ": JOrbis failed: " + e);
        }
        try {
            decoded.put("stb_vorbis", stbWhole(ogg));
        } catch (Exception e) {
            check(false, name + ": stb_vorbis failed: " + e);
        }
        try {
            decoded.put("stb_vorbis pushdata", stbPush(ogg));
        } catch (Exception e) {
            check(false, name + ": stb_vorbis pushdata failed: " + e);
        }
        if (javaDecoder != null) {
            try {
                decoded.put("VorbisDecoder", javaDecode(ogg));
            } catch (Exception e) {
                check(false, name + ": VorbisDecoder failed: " + e);
            }
        }
        float[][] reference = null;
        for (Map.Entry<String, float[][]> entry : decoded.entrySet()) {
            float[][] out = entry.getValue();
            String who = name + " via " + entry.getKey();
            check(out.length == channels, who + ": " + out.length + " channels");
            check(out[0].length == length, who + ": " + out[0].length + " frames instead of " + length);
            boolean finite = true;
            for (float[] c : out) {
                for (float v : c) {
                    finite &= Float.isFinite(v);
                }
            }
            check(finite, who + ": non-finite samples");
            if (reference == null) {
                reference = out;
            } else {
                double diff = 0;
                for (int c = 0; c < channels; c++) {
                    for (int i = 0; i < Math.min(out[c].length, reference[c].length); i++) {
                        diff = Math.max(diff, Math.abs(out[c][i] - reference[c][i]));
                    }
                }
                check(diff < 1e-4, who + ": differs from JOrbis by " + diff);
            }
            if (kind.equals("silence")) {
                double energy = 0;
                for (float[] c : out) {
                    for (float v : c) {
                        energy += v * v;
                    }
                }
                check(energy == 0, who + ": silence decodes to energy " + energy);
            }
            if (kind.equals("left-only")) {
                double right = 0;
                for (float v : out[1]) {
                    right += v * v;
                }
                check(right == 0, who + ": a silent right channel decodes to energy " + right);
            }
            // The other decoders must match JOrbis sample for sample (above), so where JOrbis is aligned, so are they.
            boolean aperiodic = kind.equals("noise") || kind.equals("clicks") || kind.equals("left-only")
                    || (kind.equals("sweep") && quality >= 5f);
            if (out == reference && aperiodic && length >= 2048 && out[0].length == length) {
                int lag = lag(source, out, 1100);
                check(lag == 0, who + ": decoded audio is offset by " + lag + " samples");
            }
        }
        if (reference != null && reference[0].length == length && length >= 44100 && quality == 5f
                && rate <= 48000) {
            double snr = snr(source, reference);
            double floor = switch (kind) {
                case "sine" -> 20.0;
                case "sweep" -> 15.0;
                case "noise" -> 5.0;
                case "clicks" -> 6.0;
                default -> Double.NEGATIVE_INFINITY;
            };
            check(snr >= floor, name + ": SNR " + String.format("%.1f", snr) + " dB under its floor of " + floor);
            if (rate == 44100 && length == 100003) {
                System.out.printf("  %-34s %7.1f kbit/s with headers, %s%n", name, ogg.length * 8.0 * rate / length / 1000,
                        kind.equals("silence") ? "decodes to exact zeros" : String.format("SNR %5.1f dB", snr));
            }
        }
    }

    /** Pages: capture, version, CRC, serial, sequence, flags, granules; then the three headers' fields. */
    private static void pages(byte[] ogg, String name, int channels, int rate, int length) {
        List<byte[]> packets = new ArrayList<>();
        List<Long> packetGranules = new ArrayList<>();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        int at = 0;
        int sequence = 0;
        long lastGranule = 0;
        boolean ended = false;
        boolean open = false;
        boolean ok = true;
        String why = "";
        int pageCount = 0;
        while (at < ogg.length) {
            if (at + 27 > ogg.length || ogg[at] != 'O' || ogg[at + 1] != 'g' || ogg[at + 2] != 'g' || ogg[at + 3] != 'S'
                    || ogg[at + 4] != 0) {
                ok = false;
                why = "bad page header at " + at;
                break;
            }
            int flags = ogg[at + 5] & 0xFF;
            long granule = readLong(ogg, at + 6);
            int serial = readInt(ogg, at + 14);
            int seq = readInt(ogg, at + 18);
            int crc = readInt(ogg, at + 22);
            int segments = ogg[at + 26] & 0xFF;
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) {
                bodyLength += ogg[at + 27 + i] & 0xFF;
            }
            int pageLength = 27 + segments + bodyLength;
            byte[] page = Arrays.copyOfRange(ogg, at, at + pageLength);
            page[22] = page[23] = page[24] = page[25] = 0;
            if (OggPageWriter.crc(page) != crc) {
                ok = false;
                why = "CRC mismatch on page " + seq;
            }
            if (serial != VorbisEncoder.Settings.DEFAULT_SERIAL || seq != sequence++) {
                ok = false;
                why = "serial or sequence wrong on page " + seq;
            }
            if (((flags & 2) != 0) != (pageCount == 0) || ended) {
                ok = false;
                why = "first-page flag misplaced or data after the last page";
            }
            if ((flags & 4) != 0) {
                ended = true;
            }
            if (pageCount == 0 && (segments != 1 || bodyLength != 30)) {
                ok = false;
                why = "identification header is not alone on the first page";
            }
            // A page continues a packet exactly when the previous page's last lacing value was 255.
            if (((flags & 1) != 0) != open) {
                ok = false;
                why = "continuation flag wrong on page " + seq;
            }
            int body = at + 27 + segments;
            boolean packetEnded = false;
            for (int i = 0; i < segments; i++) {
                int lace = ogg[at + 27 + i] & 0xFF;
                packet.write(ogg, body, lace);
                body += lace;
                if (lace < 255) {
                    packets.add(packet.toByteArray());
                    packetGranules.add(granule);
                    packet.reset();
                    packetEnded = true;
                }
            }
            if (segments > 0) {
                open = (ogg[at + 27 + segments - 1] & 0xFF) == 255;
            }
            if (packetEnded && granule != -1) {
                if (granule < lastGranule) {
                    ok = false;
                    why = "granule positions go backwards";
                }
                lastGranule = granule;
            }
            at += pageLength;
            pageCount++;
        }
        check(ok, name + ": " + why);
        check(ended, name + ": no end-of-stream page");
        check(lastGranule == length, name + ": last granule " + lastGranule + " instead of " + length);
        check(packets.size() >= 4, name + ": only " + packets.size() + " packets");
        if (packets.size() < 3) {
            return;
        }
        byte[] id = packets.get(0);
        check(id.length == 30 && id[0] == 1 && new String(id, 1, 6, StandardCharsets.US_ASCII).equals("vorbis")
                && readInt(id, 7) == 0 && (id[11] & 0xFF) == channels && readInt(id, 12) == rate
                && (id[28] & 0xFF) == 0xB8 && (id[29] & 1) == 1, name + ": identification header fields");
        byte[] comment = packets.get(1);
        int vendorLength = readInt(comment, 7);
        String vendor = new String(comment, 11, vendorLength, StandardCharsets.UTF_8);
        int comments = readInt(comment, 11 + vendorLength);
        int titleLength = readInt(comment, 15 + vendorLength);
        String title = new String(comment, 19 + vendorLength, titleLength, StandardCharsets.UTF_8);
        check(comment[0] == 3 && vendor.equals("Blocky Music") && comments == 1 && title.equals("TITLE=" + name)
                && (comment[19 + vendorLength + titleLength] & 1) == 1, name + ": comment header fields");
        byte[] setup = packets.get(2);
        check(setup[0] == 5 && new String(setup, 1, 6, StandardCharsets.US_ASCII).equals("vorbis"),
                name + ": setup header type");
        check(packetGranules.get(0) == 0 && packetGranules.get(1) == 0 && packetGranules.get(2) == 0,
                name + ": header pages carry a non-zero granule");
    }

    // ---------------------------------------------------------------- behaviour

    private static void determinism() throws IOException {
        float[][] source = signal("noise", 2, 44100, 60000);
        byte[] a = encode(source, 44100, new VorbisEncoder.Settings());
        byte[] b = encode(source, 44100, new VorbisEncoder.Settings());
        check(Arrays.equals(a, b), "the same input encodes to different bytes");
    }

    private static void refusals() throws IOException {
        check(throwsFormat(new float[3][100], 44100), "three channels are accepted");
        check(throwsFormat(new float[2][100], 7999), "7999 Hz is accepted");
        check(throwsFormat(new float[2][100], 192001), "192001 Hz is accepted");
        boolean refused = false;
        try {
            new VorbisEncoder.Settings().quality(10.5f);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "quality 10.5 is accepted");
        // A source whose second opening changes format must not produce a file that lies about it.
        int[] opened = {0};
        PcmSource changing = () -> source(new float[2][5000], opened[0]++ == 0 ? 44100 : 48000);
        boolean caught = false;
        try {
            VorbisEncoder.encode(changing, OutputStream.nullOutputStream(), new VorbisEncoder.Settings());
        } catch (AudioFormatException expected) {
            caught = true;
        }
        check(caught, "a source that changes format between passes is not refused");
    }

    private static boolean throwsFormat(float[][] data, int rate) throws IOException {
        try {
            VorbisEncoder.encode(() -> source(data, rate), OutputStream.nullOutputStream(), new VorbisEncoder.Settings());
            return false;
        } catch (AudioFormatException expected) {
            return true;
        }
    }

    private static void interruption() throws InterruptedException {
        // An endless source: the encoder must stop because it was interrupted, not because the input ran out.
        PcmSource endless = () -> new PcmStream() {
            private final Random random = new Random(1);

            @Override
            public int sampleRate() {
                return 44100;
            }

            @Override
            public int channels() {
                return 2;
            }

            @Override
            public int read(float[][] out, int offset, int frames) {
                int n = Math.min(frames, 1024);
                for (int i = 0; i < n; i++) {
                    out[0][offset + i] = (float) random.nextGaussian() * 0.1f;
                    out[1][offset + i] = out[0][offset + i];
                }
                return n;
            }

            @Override
            public void close() {
            }
        };
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                VorbisEncoder.encode(endless, OutputStream.nullOutputStream(), new VorbisEncoder.Settings());
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "native-vorbis-interrupt-check");
        worker.start();
        Thread.sleep(200);
        long start = System.nanoTime();
        worker.interrupt();
        worker.join(5000);
        double ms = (System.nanoTime() - start) / 1e6;
        check(!worker.isAlive() && outcome.get() instanceof InterruptedIOException,
                "interrupt did not stop the encoder with InterruptedIOException: " + outcome.get());
        check(ms < 250, "the encoder took " + ms + " ms to notice an interrupt");
    }

    // ---------------------------------------------------------------- helpers

    private static float[][] signal(String kind, int channels, int rate, int length) {
        float[][] out = new float[channels][length];
        Random random = new Random(kind.hashCode() * 31L + rate);
        for (int c = 0; c < channels; c++) {
            double phase = 0;
            for (int i = 0; i < length; i++) {
                double t = i / (double) rate;
                double v = switch (kind) {
                    case "sine" -> 0.5 * Math.sin(2 * Math.PI * (c == 0 ? 440 : 1000) * t);
                    case "sweep" -> {
                        double f = 40 * Math.pow(400, i / (double) Math.max(1, length));
                        phase += 2 * Math.PI * Math.min(f, rate * 0.45) / rate;
                        yield 0.4 * Math.sin(phase);
                    }
                    case "noise", "left-only" -> c == 1 && kind.equals("left-only") ? 0 : 0.25 * random.nextGaussian();
                    case "clicks" -> i % 4410 == 100 + 7 * c ? (c == 0 ? 0.9 : -0.9) : 0;
                    default -> 0;
                };
                out[c][i] = (float) v;
            }
        }
        return out;
    }

    /** Delivers the data in uneven pieces, as a real decoder would, so the encoder's buffering is exercised. */
    private static PcmStream source(float[][] data, int rate) {
        return new PcmStream() {
            private int position;

            @Override
            public int sampleRate() {
                return rate;
            }

            @Override
            public int channels() {
                return data.length;
            }

            @Override
            public int read(float[][] out, int offset, int frames) {
                int n = Math.min(frames, data[0].length - position);
                if (n <= 0) {
                    return -1;
                }
                n = (int) Math.min(n, 1 + (position * 7919L + 13) % 3000);
                for (int c = 0; c < data.length; c++) {
                    System.arraycopy(data[c], position, out[c], offset, n);
                }
                position += n;
                return n;
            }

            @Override
            public void close() {
            }
        };
    }

    private static byte[] encode(float[][] data, int rate, VorbisEncoder.Settings settings) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VorbisEncoder.encode(() -> source(data, rate), out, settings);
        return out.toByteArray();
    }

    private static float[][] jorbis(byte[] ogg) throws IOException {
        try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(ogg))) {
            int channels = stream.getFormat().getChannels();
            FloatCollector collector = new FloatCollector();
            while (stream.readChunk(collector)) {
                // Keep reading until the decoder reports the end.
            }
            return collector.planar(channels);
        }
    }

    private static final class FloatCollector implements FloatConsumer {
        private float[] values = new float[1 << 16];
        private int count;

        @Override
        public void accept(float value) {
            if (count == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[count++] = value;
        }

        float[][] planar(int channels) {
            int frames = count / channels;
            float[][] out = new float[channels][frames];
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < channels; c++) {
                    out[c][i] = values[i * channels + c];
                }
            }
            return out;
        }
    }

    private static float[][] stbWhole(byte[] ogg) {
        ByteBuffer memory = MemoryUtil.memAlloc(ogg.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            memory.put(ogg).flip();
            IntBuffer error = stack.mallocInt(1);
            long handle = STBVorbis.stb_vorbis_open_memory(memory, error, null);
            if (handle == 0) {
                throw new IllegalStateException("stb_vorbis_open_memory error " + error.get(0));
            }
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);
                int channels = info.channels();
                FloatCollector collector = new FloatCollector();
                FloatBuffer chunk = MemoryUtil.memAllocFloat(4096 * channels);
                try {
                    while (true) {
                        chunk.clear();
                        int frames = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, chunk);
                        if (frames <= 0) {
                            break;
                        }
                        for (int i = 0; i < frames * channels; i++) {
                            collector.accept(chunk.get(i));
                        }
                    }
                } finally {
                    MemoryUtil.memFree(chunk);
                }
                int failure = STBVorbis.stb_vorbis_get_error(handle);
                if (failure != STBVorbis.VORBIS__no_error) {
                    throw new IllegalStateException("stb_vorbis error " + failure);
                }
                return collector.planar(channels);
            } finally {
                STBVorbis.stb_vorbis_close(handle);
            }
        } finally {
            MemoryUtil.memFree(memory);
        }
    }

    /** stb_vorbis fed 4 KiB at a time, the way a streaming player feeds it. */
    private static float[][] stbPush(byte[] ogg) {
        ByteBuffer memory = MemoryUtil.memAlloc(ogg.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            memory.put(ogg).flip();
            IntBuffer used = stack.mallocInt(1);
            IntBuffer error = stack.mallocInt(1);
            int position = 0;
            int window = Math.min(4096, ogg.length);
            long handle;
            while (true) {
                handle = STBVorbis.stb_vorbis_open_pushdata(MemoryUtil.memSlice(memory, 0, window), used, error, null);
                if (handle != 0) {
                    position = used.get(0);
                    break;
                }
                if (error.get(0) != STBVorbis.VORBIS_need_more_data || window == ogg.length) {
                    throw new IllegalStateException("stb_vorbis_open_pushdata error " + error.get(0));
                }
                window = Math.min(ogg.length, window + 4096);
            }
            try {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);
                int channels = info.channels();
                List<float[]> planes = new ArrayList<>();
                for (int c = 0; c < channels; c++) {
                    planes.add(new float[1 << 16]);
                }
                int total = 0;
                IntBuffer frameChannels = stack.mallocInt(1);
                IntBuffer samples = stack.mallocInt(1);
                PointerBuffer output = stack.mallocPointer(1);
                int feed = 4096;
                while (position < ogg.length) {
                    int available = Math.min(feed, ogg.length - position);
                    int consumed = STBVorbis.stb_vorbis_decode_frame_pushdata(handle,
                            MemoryUtil.memSlice(memory, position, available), frameChannels, output, samples);
                    if (consumed == 0) {
                        if (available == ogg.length - position) {
                            break;
                        }
                        feed += 4096;
                        continue;
                    }
                    feed = 4096;
                    position += consumed;
                    int n = samples.get(0);
                    if (n > 0) {
                        PointerBuffer pointers = MemoryUtil.memPointerBuffer(output.get(0), channels);
                        for (int c = 0; c < channels; c++) {
                            float[] plane = planes.get(c);
                            if (total + n > plane.length) {
                                plane = Arrays.copyOf(plane, Math.max(plane.length * 2, total + n));
                                planes.set(c, plane);
                            }
                            MemoryUtil.memFloatBuffer(pointers.get(c), n).get(plane, total, n);
                        }
                        total += n;
                    }
                }
                float[][] out = new float[channels][];
                for (int c = 0; c < channels; c++) {
                    out[c] = Arrays.copyOf(planes.get(c), total);
                }
                return out;
            } finally {
                STBVorbis.stb_vorbis_close(handle);
            }
        } finally {
            MemoryUtil.memFree(memory);
        }
    }

    private static float[][] javaDecode(byte[] ogg) throws Exception {
        try (PcmStream stream = (PcmStream) javaDecoder.invoke(null, new ByteArrayInputStream(ogg))) {
            int channels = stream.channels();
            float[][] chunk = new float[channels][4096];
            float[][] out = new float[channels][1 << 16];
            int total = 0;
            while (true) {
                int n = stream.read(chunk, 0, 4096);
                if (n < 0) {
                    break;
                }
                for (int c = 0; c < channels; c++) {
                    if (total + n > out[c].length) {
                        out[c] = Arrays.copyOf(out[c], Math.max(out[c].length * 2, total + n));
                    }
                    System.arraycopy(chunk[c], 0, out[c], total, n);
                }
                total += n;
            }
            for (int c = 0; c < channels; c++) {
                out[c] = Arrays.copyOf(out[c], total);
            }
            return out;
        }
    }

    /**
     * The lag of {@code b} against {@code a} where their cross-correlation peaks, over +/- range samples, on up to
     * 30000 samples from the middle of the signal. A timing error would shift the whole stream by the same whole
     * number of samples, so any stretch shows it; the middle is used because the start of a sweep is a bass tone,
     * whose broad correlation peak lets the coding noise of a pure low tone tip the maximum by a sample.
     */
    private static int lag(float[][] a, float[][] b, int range) {
        int from = Math.max(0, a[0].length / 2 - 15000);
        int to = Math.min(a[0].length, a[0].length / 2 + 15000);
        int best = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int shift = -range; shift <= range; shift++) {
            double sum = 0;
            for (int c = 0; c < a.length; c++) {
                for (int i = Math.max(from, -shift); i < to && i + shift < b[c].length; i++) {
                    sum += a[c][i] * (double) b[c][i + shift];
                }
            }
            if (sum > bestValue) {
                bestValue = sum;
                best = shift;
            }
        }
        return best;
    }

    private static double snr(float[][] a, float[][] b) {
        double signal = 0;
        double noise = 0;
        for (int c = 0; c < a.length; c++) {
            for (int i = 0; i < a[c].length; i++) {
                signal += a[c][i] * (double) a[c][i];
                double d = a[c][i] - b[c][i];
                noise += d * d;
            }
        }
        return 10 * Math.log10(signal / Math.max(noise, 1e-30));
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    private static long readLong(byte[] b, int at) {
        return (readInt(b, at) & 0xFFFFFFFFL) | ((long) readInt(b, at + 4) << 32);
    }
}
