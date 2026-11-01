import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.CRC32;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;
import ru.gora.musicbattle.audio.codec.AudioFormatException;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.VorbisDecoder;

/**
 * The pure-Java Ogg Vorbis decoder (audio/codec/VorbisDecoder) against three references, without ffmpeg and without
 * a game:
 *
 * <ul>
 *     <li>LWJGL's STBVorbis, the build of stb_vorbis (the decoder this one is ported from) that comes with Minecraft's
 *         LWJGL, decoding the same file in this process when its natives load: the same number of frames and every
 *         sample within 1e-4 (in practice bit-identical). When the natives do not load, the frame counts and CRC-32
 *         checksums recorded from STBVorbis 3.3.3 stand in, and the output says so. (Minecraft 1.21 itself plays Ogg
 *         through JOrbis; on every file JOrbis could open during development it agreed with this decoder within 6e-7.)
 *     <li>libvorbisfile 1.3.7, the reference implementation, recorded as every 61st frame of its output in
 *         tools/vorbis-decoder-support/*.libvorbis.f32: every recorded sample within 1e-4. This is the reference for
 *         the files stb_vorbis decodes wrongly (5.1 audio, blocks of 64 and 128 samples).
 *     <li>The decoder's own exact output, as a CRC-32 of every sample's bits. The decoder uses StrictMath and Java
 *         float arithmetic, so this checksum is the same on every machine; a change to it means the output changed,
 *         and the new output has to be compared with the references above before the checksum is updated.
 *     <li>Minecraft's own JOrbisAudioStream, from the merged jar, for the timeline the game plays (where a song starts
 *         and ends), which the speed copies of a beatmap's song are made on: cut, trimmed and cut-off files included.
 * </ul>
 *
 * The fixtures are synthetic signals (clicks, noise bursts, sweeps; tools/fixtures/gen/README.md) generated for this
 * check, encoded with libvorbis 1.3.7 and with ffmpeg's native Vorbis encoder; two are deliberately rewritten: one with
 * its granule positions moved back 1000 samples (a stream that asks for its first 1000 samples to be dropped) and one
 * whose identification header declares blocks of 64 and 128 samples. lv-cut-long-first.ogg is a four-second chord with
 * noise bursts, encoded with libvorbis and then cut 1.37 s in with ffmpeg's stream copy ({@code -ss 1.37 -c copy}), as
 * players cut songs. The shipped sound smiley_damage.ogg is read from the resources. The public source repository
 * leaves that sound and its libvorbis reference out (the mod uses the sound under the SampleFocus Standard License; see
 * NOTICE.md); without them the parts that read them print a SKIP, STBVorbis is probed with another fixture, and
 * everything else runs as it does with them, the damage tests on the same random inputs.
 *
 * Then it feeds the decoder damage: files cut at every byte, flipped bits, pages dropped or doubled, garbage, and
 * corruption with the page checksums repaired so it reaches the Vorbis layer. Every one must end in an
 * AudioFormatException or a clean decode with finite samples: never another exception, never a hang.
 *
 * What it cannot prove: how the decoded audio sounds. The harness has no audio device.
 */
public final class NativeVorbisDecoderChecks {
    /**
     * The tests against stb_vorbis and libvorbis, and the damage tests, read on the specification's timeline, which is
     * theirs; the Minecraft timeline is checked against JOrbisAudioStream on its own (checkMinecraftTimeline).
     */
    private static final VorbisDecoder.Timeline SPEC = VorbisDecoder.Timeline.SPECIFICATION;
    /**
     * How many values checkDamage draws from its random source for smiley_damage.ogg, the first fixture. Without the
     * sound the source skips this many draws, so every other fixture meets the same damage as when it is there; with
     * the sound the check counts the draws and fails if this number no longer holds.
     */
    private static final long SMILEY_DAMAGE_DRAWS = 10_456;
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static final ExecutorService WATCHDOG = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vorbis-decoder-check");
        thread.setDaemon(true);
        return thread;
    });

    private record Fixture(String name, Path path, int channels, int sampleRate, int frames, long crc,
                           boolean stbAgrees, String libvorbis) {
    }

    private record Decoded(int channels, int sampleRate, float[][] samples) {
        int frames() {
            return samples[0].length;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path support = root.resolve("tools/vorbis-decoder-support");
        Path smiley = root.resolve("src/main/resources/assets/music_battle/sounds/entity/smiley_damage.ogg");
        boolean haveSmiley = Files.isRegularFile(smiley);
        boolean haveSmileyReference = Files.isRegularFile(support.resolve("smiley_damage.libvorbis.f32"));
        List<Fixture> fixtures = new ArrayList<>();
        if (haveSmiley) {
            fixtures.add(new Fixture("smiley_damage.ogg (shipped)", smiley, 1, 48000, 34535, 0x5cf798fbL, true,
                    haveSmileyReference ? "smiley_damage.libvorbis.f32" : null));
            if (!haveSmileyReference) {
                System.out.println("SKIP smiley_damage.ogg against libvorbisfile: its reference "
                        + "smiley_damage.libvorbis.f32 is not in this copy");
            }
        } else {
            System.out.println("SKIP smiley_damage.ogg (shipped): the sound is not in this copy (the public source leaves"
                    + " it out); its fixture, its damage tests and sameAsMinecraft(smiley_damage.ogg) are skipped");
        }
        fixtures.addAll(List.of(
                new Fixture("lv-stereo-44k-transients", support.resolve("lv-stereo-44k-transients.ogg"), 2, 44100,
                        88200, 0x5884c725L, true, "lv-stereo-44k-transients.libvorbis.f32"),
                new Fixture("lv-mono-22k-sweep", support.resolve("lv-mono-22k-sweep.ogg"), 1, 22050, 44100,
                        0x5ae8ae6aL, true, "lv-mono-22k-sweep.libvorbis.f32"),
                new Fixture("ff-stereo-48k", support.resolve("ff-stereo-48k.ogg"), 2, 48000, 48000, 0x9e0b281bL,
                        true, "ff-stereo-48k.libvorbis.f32"),
                // stb_vorbis sizes a type 2 residue as two channels' worth, so it decodes libvorbis's 5.1 wrongly.
                new Fixture("lv-6ch-48k", support.resolve("lv-6ch-48k.ogg"), 6, 48000, 48000, 0x705ef1b8L, false,
                        "lv-6ch-48k.libvorbis.f32"),
                // stb_vorbis's transform assumes blocks of at least 256 samples.
                new Fixture("lv-blocks-64-128", support.resolve("lv-blocks-64-128.ogg"), 1, 44100, 3824,
                        0x39a2a6a8L, false, "lv-blocks-64-128.libvorbis.f32"),
                // stb_vorbis does not drop the samples a first granule position asks to drop.
                new Fixture("lv-start-trim-1000", support.resolve("lv-start-trim-1000.ogg"), 2, 44100, 87200,
                        0x2f97dc3dL, false, null)));

        Stb stb = Stb.load(haveSmiley ? smiley : support.resolve("lv-stereo-44k-transients.ogg"));
        System.out.println(stb != null
                ? "STBVorbis loaded: comparing with it live."
                : "STBVorbis natives did not load: comparing with the frame counts and checksums recorded from "
                + "LWJGL 3.3.3 STBVorbis instead.");

        for (Fixture fixture : fixtures) {
            checkFixture(fixture, support, stb);
        }
        checkStartTrim(support, stb);
        checkStreamingAndContainers(support);
        checkContract(support);
        checkInterrupt(support);
        checkDamage(fixtures, smiley, haveSmiley);
        checkMinecraftTimeline(support, smiley);
        checkSpeed(support);

        System.out.println(checks + " checks, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("FAIL " + failure);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void checkFixture(Fixture fixture, Path support, Stb stb) throws Exception {
        byte[] bytes = Files.readAllBytes(fixture.path());
        Decoded ours = decode(bytes);
        String name = fixture.name();
        check(ours.channels() == fixture.channels(), name + ": " + ours.channels() + " channels");
        check(ours.sampleRate() == fixture.sampleRate(), name + ": " + ours.sampleRate() + " Hz");
        check(ours.frames() == fixture.frames(), name + ": " + ours.frames() + " frames, expected " + fixture.frames());
        long crc = crc(ours.samples());
        check(crc == fixture.crc(), name + ": output checksum " + Long.toHexString(crc) + ", recorded "
                + Long.toHexString(fixture.crc()) + " (the output changed: compare it with STBVorbis and libvorbis "
                + "before updating the checksum)");
        if (fixture.libvorbis() != null) {
            float[] reference = readFloats(support.resolve(fixture.libvorbis()));
            int points = reference.length / fixture.channels();
            check(points == (fixture.frames() + 60) / 61, name + ": " + points + " libvorbis reference points");
            double worst = 0;
            for (int p = 0; p < points && p * 61 < ours.frames(); p++) {
                for (int c = 0; c < fixture.channels(); c++) {
                    worst = Math.max(worst, Math.abs(ours.samples()[c][p * 61] - reference[p * fixture.channels() + c]));
                }
            }
            check(worst < 1e-4, name + ": differs from libvorbisfile by " + worst);
            System.out.printf("%s: %d frames, libvorbisfile max difference %.2g%n", name, ours.frames(), worst);
        }
        if (fixture.stbAgrees()) {
            if (stb != null) {
                float[][] reference = stb.decode(bytes);
                check(reference != null && reference[0].length == ours.frames(), name + ": STBVorbis gave "
                        + (reference == null ? "nothing" : reference[0].length + " frames"));
                if (reference != null && reference[0].length == ours.frames()) {
                    double worst = maxDifference(ours.samples(), reference, 0);
                    check(worst < 1e-4, name + ": differs from STBVorbis by " + worst);
                    System.out.printf("%s: STBVorbis max difference %.2g%s%n", name, worst,
                            crc(reference) == crc ? ", bit-identical" : "");
                }
            } else {
                // Recorded from STBVorbis 3.3.3 (macOS arm64): the same frames, and its output's checksum is this
                // decoder's, because the two were bit-identical on every one of these files.
                check(crc == fixture.crc(), name + ": no longer bit-identical to the recorded STBVorbis output");
            }
        }
    }

    /** The start-trimmed fixture is the transients fixture with its first 1000 samples dropped, sample for sample. */
    private static void checkStartTrim(Path support, Stb stb) throws Exception {
        Decoded full = decode(Files.readAllBytes(support.resolve("lv-stereo-44k-transients.ogg")));
        byte[] trimmedBytes = Files.readAllBytes(support.resolve("lv-start-trim-1000.ogg"));
        Decoded trimmed = decode(trimmedBytes);
        check(trimmed.frames() == full.frames() - 1000, "start trim: " + trimmed.frames() + " frames");
        boolean same = true;
        for (int c = 0; c < 2 && same; c++) {
            for (int i = 0; i < trimmed.frames(); i++) {
                if (Float.floatToRawIntBits(trimmed.samples()[c][i]) != Float.floatToRawIntBits(full.samples()[c][i + 1000])) {
                    same = false;
                    break;
                }
            }
        }
        check(same, "start trim: the trimmed stream is not the full one minus its first 1000 samples");
        if (stb != null) {
            float[][] reference = stb.decode(trimmedBytes);
            System.out.println("start trim: STBVorbis returns " + (reference == null ? "nothing" : reference[0].length)
                    + " frames; it does not drop the 1000 samples, this decoder does (as the specification asks)");
        }
    }

    private static void checkStreamingAndContainers(Path support) throws Exception {
        byte[] stereo = Files.readAllBytes(support.resolve("lv-stereo-44k-transients.ogg"));
        byte[] mono = Files.readAllBytes(support.resolve("lv-mono-22k-sweep.ogg"));
        long expected = crc(decode(stereo).samples());

        // A slow, fragmented input and odd read sizes must not change a single sample.
        Random random = new Random(11);
        float[][] collected = new float[2][100_000];
        int total = 0;
        try (PcmStream stream = VorbisDecoder.open(new TrickleInputStream(stereo, 23), SPEC)) {
            float[][] buffer = new float[2][5000];
            while (true) {
                int want = 1 + random.nextInt(random.nextBoolean() ? 7 : 4000);
                int offset = random.nextInt(1000);
                int read = stream.read(buffer, offset, want);
                if (read < 0) {
                    break;
                }
                check(read > 0 && read <= want, "streaming: read " + read + " of " + want);
                for (int c = 0; c < 2; c++) {
                    System.arraycopy(buffer[c], offset, collected[c], total, read);
                }
                total += read;
            }
        }
        float[][] streamed = {Arrays.copyOf(collected[0], total), Arrays.copyOf(collected[1], total)};
        check(crc(streamed) == expected, "streaming: tiny input reads and odd output sizes change the samples");

        // A chained file decodes as its first link only.
        byte[] chained = concat(stereo, mono);
        check(crc(decode(chained).samples()) == expected, "chained file: not the first link alone");

        // A multiplexed file: a foreign stream's pages around ours are skipped, wherever they sit.
        List<byte[]> pages = splitPages(stereo);
        byte[] foreignHead = page(777, 0, OggFlags.FIRST, 0, "OpusHead\u0001\u00028\u0001\u0080»\0\0\0\0\0"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        byte[] foreignData = page(777, 1, 0, 960, new byte[300]);
        ByteArrayOutputStream mixed = new ByteArrayOutputStream();
        mixed.write(pages.get(0));
        mixed.write(foreignHead);
        for (int i = 1; i < pages.size(); i++) {
            mixed.write(pages.get(i));
            if (i == 3) {
                mixed.write(foreignData);
            }
        }
        check(crc(decode(mixed.toByteArray()).samples()) == expected, "multiplexed file: foreign pages changed the audio");
        ByteArrayOutputStream foreignFirst = new ByteArrayOutputStream();
        foreignFirst.write(foreignHead);
        foreignFirst.write(stereo);
        check(crc(decode(foreignFirst.toByteArray()).samples()) == expected,
                "multiplexed file: a foreign stream announced first hides the Vorbis one");
    }

    private static void checkContract(Path support) throws Exception {
        byte[] mono = Files.readAllBytes(support.resolve("lv-mono-22k-sweep.ogg"));
        TrackingInputStream input = new TrackingInputStream(mono);
        PcmStream stream = VorbisDecoder.open(input, SPEC);
        check(stream.channels() == 1 && stream.sampleRate() == 22050, "contract: format");
        check(stream.frameCountHint() == -1, "contract: the frame count hint is -1, it is not known up front");
        float[][] buffer = new float[3][1024];
        check(stream.read(buffer, 0, 0) == 0, "contract: reading zero frames returns 0");
        expectThrows(IllegalArgumentException.class, () -> stream.read(new float[0][], 0, 10), "contract: no channels");
        expectThrows(IllegalArgumentException.class, () -> stream.read(buffer, 1000, 100), "contract: past the end");
        while (stream.read(buffer, 0, 1024) > 0) {
            // drain
        }
        check(stream.read(buffer, 0, 1024) == -1 && stream.read(buffer, 0, 1) == -1, "contract: -1 stays -1 at the end");
        check(!input.closed, "contract: the input is closed before the stream is");
        stream.close();
        check(input.closed, "contract: closing the stream closes its input");
        expectThrows(IOException.class, () -> stream.read(buffer, 0, 10), "contract: reading after close");

        TrackingInputStream bad = new TrackingInputStream("RIFF\0\0\0\0WAVEfmt ".getBytes());
        expectThrows(AudioFormatException.class, () -> VorbisDecoder.open(bad, SPEC), "contract: a WAV header");
        check(!bad.closed, "contract: a failed open leaves the caller's input open");

        // The comment header alone, as the server reads the mark on a sped-up copy: the fields as written, the vendor
        // string left out, the input left to the caller; a header whose lengths run past its end is damage.
        TrackingInputStream commented = new TrackingInputStream(mono);
        List<String> fields = VorbisDecoder.comments(commented);
        check(fields.equals(List.of("ENCODER=scratch venc")), "comments: the libvorbis fixture's one field, got " + fields);
        check(!commented.closed, "comments: the input is left to the caller");
        List<byte[]> pages = splitPages(mono);
        byte[] second = pages.get(1).clone();
        int body = 27 + (second[26] & 0xFF);
        second[body + 7] = (byte) 0xF0;
        fixCrc(second);
        ByteArrayOutputStream damaged = new ByteArrayOutputStream();
        damaged.write(pages.get(0));
        damaged.write(second);
        expectThrows(AudioFormatException.class, () -> VorbisDecoder.comments(new ByteArrayInputStream(damaged.toByteArray())),
                "comments: a vendor string longer than its header");
        expectThrows(AudioFormatException.class, () -> VorbisDecoder.comments(new ByteArrayInputStream("OggS".getBytes())),
                "comments: four bytes of Ogg");
    }

    private static void checkInterrupt(Path support) throws Exception {
        byte[] stereo = Files.readAllBytes(support.resolve("lv-stereo-44k-transients.ogg"));
        try {
            Thread.currentThread().interrupt();
            expectThrows(InterruptedIOException.class, () -> VorbisDecoder.open(new ByteArrayInputStream(stereo), SPEC),
                    "interrupt: open on an interrupted thread");
            check(Thread.currentThread().isInterrupted(), "interrupt: open cleared the interrupt flag");
            Thread.interrupted();

            try (PcmStream stream = VorbisDecoder.open(new ByteArrayInputStream(stereo), SPEC)) {
                float[][] buffer = new float[2][4096];
                check(stream.read(buffer, 0, 4096) > 0, "interrupt: first read");
                Thread.currentThread().interrupt();
                expectThrows(InterruptedIOException.class, () -> {
                    while (stream.read(buffer, 0, 4096) > 0) {
                        // the decoder must notice before the song ends
                    }
                }, "interrupt: read on an interrupted thread");
                check(Thread.currentThread().isInterrupted(), "interrupt: read cleared the interrupt flag");
            }
        } finally {
            Thread.interrupted();
        }
    }

    private static void checkDamage(List<Fixture> fixtures, Path smiley, boolean haveSmiley) throws Exception {
        expectFormatError(new byte[0], "empty input");
        expectFormatError("RIFF\0\0\0\0WAVEfmt ".getBytes(), "a WAV header");
        byte[] opus = page(5, 0, OggFlags.FIRST, 0, "OpusHead\u0001\u00028\u0001\u0080»\0\0\0\0\0"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        String opusMessage = expectFormatError(opus, "an Ogg Opus file");
        check(opusMessage != null && opusMessage.contains("Opus"), "Ogg Opus: the message does not name Opus: " + opusMessage);

        DrawCountingRandom random = new DrawCountingRandom(1);
        if (!haveSmiley) {
            random.skip(SMILEY_DAMAGE_DRAWS);
        }
        int outcomes = 0;
        for (Fixture fixture : fixtures) {
            long drawsBefore = random.draws();
            byte[] original = Files.readAllBytes(fixture.path());
            List<byte[]> pages = splitPages(original);
            List<Integer> boundaries = new ArrayList<>();
            int offset = 0;
            for (byte[] page : pages) {
                boundaries.add(offset);
                offset += page.length;
            }
            boundaries.add(offset);

            // Cut short: inside a page is damage; at a page boundary it is an ordinary stream without its last pages.
            boolean small = original.length < 8000;
            for (int length = 0; length < original.length; length += small ? 1 : 1 + random.nextInt(97)) {
                String result = run(Arrays.copyOf(original, length));
                outcomes++;
                if (boundaries.contains(length)) {
                    check(result.equals("OK") || result.startsWith("AFE"), fixture.name() + " cut at page boundary "
                            + length + ": " + result);
                } else {
                    check(result.startsWith("AFE") && result.contains("cut off"), fixture.name() + " cut at " + length
                            + ": " + result);
                }
            }

            // A flipped bit is caught by the page checksum (or the capture pattern) wherever it lands.
            for (int i = 0; i < 150; i++) {
                byte[] damaged = original.clone();
                int at = random.nextInt(damaged.length);
                damaged[at] ^= (byte) (1 << random.nextInt(8));
                String result = run(damaged);
                outcomes++;
                check(result.startsWith("AFE"), fixture.name() + " bit flip at " + at + ": " + result);
            }

            // A dropped or doubled page breaks the page sequence. The last page is the exception both ways: without
            // it the stream just ends early, and a copy of it after the end of the stream is never read.
            for (int i = 0; i < 6 && pages.size() > 3; i++) {
                int index = 2 + random.nextInt(pages.size() - 2);
                boolean last = index == pages.size() - 1;
                List<byte[]> dropped = new ArrayList<>(pages);
                dropped.remove(index);
                String result = run(join(dropped));
                outcomes++;
                check(last ? result.equals("OK") || result.startsWith("AFE ") : result.contains("missing"),
                        fixture.name() + " without page " + index + ": " + result);
                List<byte[]> doubled = new ArrayList<>(pages);
                doubled.add(index, pages.get(index));
                result = run(join(doubled));
                outcomes++;
                check(last ? result.equals("OK") : result.contains("missing"),
                        fixture.name() + " with page " + index + " twice: " + result);
            }

            // Corruption with the checksum repaired reaches the Vorbis headers and packets themselves.
            for (int i = 0; i < 250; i++) {
                List<byte[]> mutated = new ArrayList<>(pages);
                int index = random.nextInt(4) == 0 ? random.nextInt(Math.min(3, pages.size())) : random.nextInt(pages.size());
                byte[] page = mutated.get(index).clone();
                int segments = page[26] & 0xFF;
                int bodyStart = 27 + segments;
                int flips = 1 + random.nextInt(random.nextBoolean() ? 3 : 40);
                for (int k = 0; k < flips && page.length > bodyStart; k++) {
                    int at = bodyStart + random.nextInt(page.length - bodyStart);
                    if (random.nextInt(4) == 0) {
                        page[at] = (byte) random.nextInt(256);
                    } else {
                        page[at] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                if (random.nextInt(8) == 0) {
                    int at = 5 + random.nextInt(17);
                    page[at] ^= (byte) (1 << random.nextInt(8));
                }
                fixCrc(page);
                mutated.set(index, page);
                String result = run(join(mutated));
                outcomes++;
                check(result.equals("OK") || result.startsWith("AFE "), fixture.name() + " page " + index
                        + " corrupted with " + flips + " changes and a valid checksum: " + result);
            }
            if (fixture.path().equals(smiley)) {
                check(random.draws() - drawsBefore == SMILEY_DAMAGE_DRAWS, "smiley_damage.ogg damage drew "
                        + (random.draws() - drawsBefore) + " random values; SMILEY_DAMAGE_DRAWS says " + SMILEY_DAMAGE_DRAWS);
            }
        }
        for (int i = 0; i < 200; i++) {
            byte[] garbage = new byte[random.nextInt(70_000)];
            random.nextBytes(garbage);
            if (i % 2 == 0 && garbage.length > 5) {
                garbage[0] = 'O';
                garbage[1] = 'g';
                garbage[2] = 'g';
                garbage[3] = 'S';
                garbage[4] = 0;
            }
            String result = run(garbage);
            outcomes++;
            check(result.startsWith("AFE"), "garbage " + i + ": " + result);
        }
        System.out.println("damage: " + outcomes + " damaged inputs, each an AudioFormatException or a clean decode");
    }

    /**
     * The Minecraft timeline against the game's own decoder: JOrbisAudioStream, from the merged jar, decodes each file
     * in this process, and this decoder on {@code Timeline.MINECRAFT} must give the same number of frames and every
     * sample within 1e-4. The speed copies of a beatmap's song are made on this timeline, so a copy lines up with what
     * the game plays of the original only if this holds. The files: the fixtures of three encoders; a song cut from a
     * longer one with ffmpeg's stream copy (its first block is a long one, it asks for 29121 samples to be dropped, and
     * its last page holds fewer samples than that, so the game plays well past the last granule position); the first
     * granule moved back; leading pages dropped; the end-of-stream flag missing or its granule moved back; and the files
     * cut off at many bytes. Damage the game skips over and then plays out of step must be refused, and JOrbis must be
     * seen to do just that.
     */
    private static void checkMinecraftTimeline(Path support, Path smiley) throws Exception {
        byte[] transients = Files.readAllBytes(support.resolve("lv-stereo-44k-transients.ogg"));
        byte[] cut = Files.readAllBytes(support.resolve("lv-cut-long-first.ogg"));
        sameAsMinecraft("transients (libvorbis)", transients);
        sameAsMinecraft("mono sweep (libvorbis, 512/1024 blocks)", Files.readAllBytes(support.resolve("lv-mono-22k-sweep.ogg")));
        sameAsMinecraft("ffmpeg's encoder", Files.readAllBytes(support.resolve("ff-stereo-48k.ogg")));
        if (Files.isRegularFile(smiley)) {
            sameAsMinecraft("smiley_damage.ogg", Files.readAllBytes(smiley));
        } else {
            System.out.println("SKIP sameAsMinecraft(smiley_damage.ogg): the sound is not in this copy");
        }
        sameAsMinecraft("start trim of 1000", Files.readAllBytes(support.resolve("lv-start-trim-1000.ogg")));

        // The cut song: Minecraft plays 448 samples of the long first block, then the whole stream without the drop.
        Decoded played = sameAsMinecraft("cut with a stream copy", cut);
        Decoded specification = decode(cut, SPEC);
        check(played.frames() == 133696, "cut: Minecraft plays " + played.frames() + " frames, measured 133696");
        check(specification.frames() == 115983, "cut: the specification keeps " + specification.frames() + " frames");
        // The two overlap where both play: the game stops early here (see above), the specification later.
        boolean aligned = true;
        int overlap = Math.min(specification.frames(), played.frames() - 448 - 29121);
        for (int c = 0; c < 2 && aligned; c++) {
            for (int i = 0; i < overlap; i++) {
                if (Float.floatToRawIntBits(specification.samples()[c][i])
                        != Float.floatToRawIntBits(played.samples()[c][i + 448 + 29121])) {
                    aligned = false;
                    break;
                }
            }
        }
        check(aligned, "cut: what the specification keeps is not what Minecraft plays 448 + 29121 samples in");

        List<byte[]> pages = splitPages(transients);
        List<byte[]> lowered = copyPages(pages);
        setGranule(lowered.get(2), granule(lowered.get(2)) - 1000);
        sameAsMinecraft("first granule moved back 1000", join(lowered));
        List<byte[]> noEnd = copyPages(pages);
        byte[] last = noEnd.get(noEnd.size() - 1);
        last[5] &= ~4;
        fixCrc(last);
        sameAsMinecraft("no end-of-stream flag", join(noEnd));
        List<byte[]> shortEnd = copyPages(pages);
        byte[] end = shortEnd.get(shortEnd.size() - 1);
        setGranule(end, granule(end) - 3000);
        sameAsMinecraft("last granule moved back 3000", join(shortEnd));
        List<byte[]> cutPages = splitPages(cut);
        List<byte[]> dropped = copyPages(cutPages);
        dropped.remove(2);
        renumber(dropped);
        sameAsMinecraft("cut without its first audio page", join(dropped));

        // Cut off anywhere after the headers: the game plays up to the last whole page, and so must this.
        Random random = new Random(3);
        int headers = pages.get(0).length + pages.get(1).length;
        int cuts = 0;
        for (byte[] file : List.of(transients, cut)) {
            int start = file == cut ? cutPages.get(0).length + cutPages.get(1).length : headers;
            for (int length = start + 1; length < file.length; length += 1 + random.nextInt(180)) {
                sameAsMinecraft("cut off at byte " + length, Arrays.copyOf(file, length));
                cuts++;
            }
        }

        // Damage in the middle: JOrbis stumbles, loses what it had decoded and goes on, early. Refused.
        int middle = pages.size() / 2;
        List<byte[]> badChecksum = copyPages(pages);
        badChecksum.get(middle)[40] ^= 0x10;
        refusedAsMinecraftWould("a page with a bad checksum", join(badChecksum));
        List<byte[]> junk = copyPages(pages);
        junk.add(middle, new byte[100]);
        refusedAsMinecraftWould("100 bytes of junk between pages", join(junk));
        List<byte[]> missing = copyPages(pages);
        missing.remove(middle);
        refusedAsMinecraftWould("a missing page", join(missing));
        List<byte[]> foreign = copyPages(pages);
        foreign.add(middle, page(777, 1, 0, 960, new byte[300]));
        refusedAsMinecraftWould("another stream's page among the audio", join(foreign));
        System.out.println("Minecraft timeline: " + cuts + " cut-off files and " + (minecraftFiles - cuts)
                + " others played as JOrbisAudioStream plays them; 4 kinds of damage in the middle refused");
    }

    private static int minecraftFiles;

    /** This decoder on the Minecraft timeline gives exactly what JOrbisAudioStream gives; returns that. */
    private static Decoded sameAsMinecraft(String what, byte[] bytes) throws Exception {
        minecraftFiles++;
        Played game = jorbis(bytes);
        Decoded ours = decode(bytes, VorbisDecoder.Timeline.MINECRAFT);
        check(!game.threw(), what + ": JOrbisAudioStream itself failed on it");
        check(ours.frames() == game.samples()[0].length, what + ": " + ours.frames() + " frames, Minecraft plays "
                + game.samples()[0].length);
        if (ours.frames() == game.samples()[0].length) {
            double worst = maxDifference(ours.samples(), game.samples(), 0);
            check(worst <= 1e-4, what + ": differs from Minecraft's decode by " + worst);
        }
        return ours;
    }

    private static void refusedAsMinecraftWould(String what, byte[] bytes) throws Exception {
        String result = run(bytes, VorbisDecoder.Timeline.MINECRAFT);
        check(result.startsWith("AFE "), what + ": " + result + ", expected a refusal");
        Played game = jorbis(bytes);
        check(game.threw() && game.playedAfterThrowing(), what + ": JOrbisAudioStream was expected to fail part way and"
                + " then play on, which is why such a file is refused");
    }

    private record Played(float[][] samples, boolean threw, boolean playedAfterThrowing) {
    }

    /**
     * Minecraft's own decode. When it throws part way, it is called again, as the game's next buffer would call it, to
     * see whether it plays on.
     */
    private static Played jorbis(byte[] bytes) throws Exception {
        float[] all = new float[1 << 16];
        int[] count = {0};
        boolean threw = false;
        boolean after = false;
        int channels;
        try (net.minecraft.client.sounds.JOrbisAudioStream stream =
                     new net.minecraft.client.sounds.JOrbisAudioStream(new ByteArrayInputStream(bytes))) {
            channels = stream.getFormat().getChannels();
            float[][] holder = {all};
            it.unimi.dsi.fastutil.floats.FloatConsumer sink = value -> {
                if (count[0] == holder[0].length) {
                    holder[0] = Arrays.copyOf(holder[0], holder[0].length * 2);
                }
                holder[0][count[0]++] = value;
            };
            for (int failures = 0; failures < 50; ) {
                int before = count[0];
                try {
                    if (!stream.readChunk(sink)) {
                        break;
                    }
                    after |= threw && count[0] > before;
                } catch (Exception exception) {
                    threw = true;
                    failures++;
                }
            }
            all = holder[0];
        }
        int frames = count[0] / channels;
        float[][] samples = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                samples[c][i] = all[i * channels + c];
            }
        }
        return new Played(samples, threw, after);
    }

    private static List<byte[]> copyPages(List<byte[]> pages) {
        List<byte[]> copy = new ArrayList<>();
        for (byte[] page : pages) {
            copy.add(page.clone());
        }
        return copy;
    }

    private static long granule(byte[] page) {
        return ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getLong(6);
    }

    private static void setGranule(byte[] page, long granule) {
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putLong(6, granule);
        fixCrc(page);
    }

    private static void renumber(List<byte[]> pages) {
        for (int i = 0; i < pages.size(); i++) {
            ByteBuffer.wrap(pages.get(i)).order(ByteOrder.LITTLE_ENDIAN).putInt(18, i);
            fixCrc(pages.get(i));
        }
    }

    /**
     * The requirement is four minutes of 44.1 kHz stereo in under 1.5 s. Only a regression ten times worse than that
     * fails here, so a slow or busy machine does not; the measured figure is printed either way.
     */
    private static void checkSpeed(Path support) throws Exception {
        byte[] stereo = Files.readAllBytes(support.resolve("lv-stereo-44k-transients.ogg"));
        for (int i = 0; i < 30; i++) {
            decode(stereo);
        }
        long frames = 0;
        long start = System.nanoTime();
        while (System.nanoTime() - start < 700_000_000L) {
            frames += decode(stereo).frames();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double fourMinutes = 240.0 * 44100 / (frames / seconds);
        System.out.printf("speed: %.0fx real time; four minutes of 44.1 kHz stereo would take %.0f ms (limit 1500 ms)%n",
                frames / seconds / 44100, fourMinutes * 1000);
        check(fourMinutes < 15.0, "speed: four minutes of stereo would take " + fourMinutes + " s");
    }

    // ---- decoding helpers ----

    private static Decoded decode(byte[] bytes) throws IOException {
        return decode(bytes, SPEC);
    }

    private static Decoded decode(byte[] bytes, VorbisDecoder.Timeline timeline) throws IOException {
        try (PcmStream stream = VorbisDecoder.open(new ByteArrayInputStream(bytes), timeline)) {
            int channels = stream.channels();
            float[][] all = new float[channels][1 << 16];
            float[][] buffer = new float[channels][4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer, 0, 4096)) > 0) {
                if (total + read > all[0].length) {
                    for (int c = 0; c < channels; c++) {
                        all[c] = Arrays.copyOf(all[c], all[c].length * 2);
                    }
                }
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(buffer[c], 0, all[c], total, read);
                }
                total += read;
            }
            for (int c = 0; c < channels; c++) {
                all[c] = Arrays.copyOf(all[c], total);
            }
            return new Decoded(channels, stream.sampleRate(), all);
        }
    }

    /** OK, AFE, or a description of anything that should not happen; runs under a watchdog so a hang fails. */
    private static String run(byte[] bytes) throws Exception {
        return run(bytes, SPEC);
    }

    private static String run(byte[] bytes, VorbisDecoder.Timeline timeline) throws Exception {
        Future<String> future = WATCHDOG.submit(() -> {
            try (PcmStream stream = VorbisDecoder.open(new ByteArrayInputStream(bytes), timeline)) {
                float[][] buffer = new float[stream.channels()][4096];
                long total = 0;
                int read;
                while ((read = stream.read(buffer, 0, 4096)) > 0) {
                    total += read;
                    for (float[] channel : buffer) {
                        for (int i = 0; i < read; i++) {
                            if (!Float.isFinite(channel[i])) {
                                return "a non-finite sample";
                            }
                        }
                    }
                    if (total > 100_000_000L) {
                        return "endless output";
                    }
                }
                return "OK";
            } catch (AudioFormatException e) {
                // A cause means a RuntimeException escaped the decoder's own checks and was only caught by its last
                // line of defence: still safe for callers, but a gap in validation worth fixing.
                return e.getCause() == null ? "AFE " + e.getMessage() : "unchecked " + e.getCause();
            } catch (Throwable t) {
                return "unexpected " + t;
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            failures.add("a damaged input hung the decoder for 10 s; stopping");
            System.out.println("FAIL a damaged input hung the decoder for 10 s");
            System.exit(1);
            return "hang";
        }
    }

    private static String expectFormatError(byte[] bytes, String what) throws Exception {
        String result = run(bytes);
        check(result.startsWith("AFE"), what + ": " + result);
        return result;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void expectThrows(Class<? extends Throwable> type, Action action, String what) {
        try {
            action.run();
            check(false, what + ": nothing was thrown, expected " + type.getSimpleName());
        } catch (Throwable t) {
            check(type.isInstance(t), what + ": threw " + t + ", expected " + type.getSimpleName());
        }
    }

    // ---- Ogg helpers: the check builds and repairs pages itself so it does not lean on the code under test ----

    private static final class OggFlags {
        static final int FIRST = 2;
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }

    private static void fixCrc(byte[] page) {
        page[22] = page[23] = page[24] = page[25] = 0;
        int crc = 0;
        for (byte b : page) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
        }
        page[22] = (byte) crc;
        page[23] = (byte) (crc >>> 8);
        page[24] = (byte) (crc >>> 16);
        page[25] = (byte) (crc >>> 24);
    }

    /** One page holding one packet shorter than 255 bytes times 255. */
    private static byte[] page(int serial, int sequence, int flags, long granule, byte[] packet) {
        int segments = packet.length / 255 + 1;
        ByteBuffer page = ByteBuffer.allocate(27 + segments + packet.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes()).put((byte) 0).put((byte) flags).putLong(granule).putInt(serial).putInt(sequence)
                .putInt(0).put((byte) segments);
        for (int i = 0; i < segments - 1; i++) {
            page.put((byte) 255);
        }
        page.put((byte) (packet.length % 255));
        page.put(packet);
        byte[] bytes = page.array();
        fixCrc(bytes);
        return bytes;
    }

    private static List<byte[]> splitPages(byte[] data) {
        List<byte[]> pages = new ArrayList<>();
        int position = 0;
        while (position + 27 <= data.length) {
            int segments = data[position + 26] & 0xFF;
            int length = 27 + segments;
            for (int i = 0; i < segments; i++) {
                length += data[position + 27 + i] & 0xFF;
            }
            pages.add(Arrays.copyOfRange(data, position, position + length));
            position += length;
        }
        return pages;
    }

    private static byte[] join(List<byte[]> pages) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] page : pages) {
            out.writeBytes(page);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- numbers ----

    private static long crc(float[][] samples) {
        CRC32 crc = new CRC32();
        ByteBuffer frame = ByteBuffer.allocate(4 * samples.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples[0].length; i++) {
            frame.clear();
            for (float[] channel : samples) {
                frame.putFloat(channel[i]);
            }
            crc.update(frame.array());
        }
        return crc.getValue();
    }

    private static double maxDifference(float[][] a, float[][] b, int offset) {
        double worst = 0;
        for (int c = 0; c < a.length; c++) {
            for (int i = 0; i < a[c].length && i + offset < b[c].length; i++) {
                worst = Math.max(worst, Math.abs(a[c][i] - b[c][i + offset]));
            }
        }
        return worst;
    }

    private static float[] readFloats(Path path) throws IOException {
        FloatBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures.add(message);
        }
    }

    // ---- inputs ----

    /** Hands out a few bytes per call, and now and then none at all, as a network or a slow disk might. */
    private static final class TrickleInputStream extends InputStream {
        private final byte[] data;
        private final Random random;
        private int position;

        TrickleInputStream(byte[] data, long seed) {
            this.data = data;
            this.random = new Random(seed);
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            if (random.nextInt(20) == 0) {
                return 0;
            }
            int count = Math.min(Math.min(length, 1 + random.nextInt(7)), data.length - position);
            System.arraycopy(data, position, target, offset, count);
            position += count;
            return count;
        }
    }

    private static final class TrackingInputStream extends FilterInputStream {
        boolean closed;

        TrackingInputStream(byte[] data) {
            super(new ByteArrayInputStream(data));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /**
     * java.util.Random that counts its steps: every value it hands out, whatever its type, comes from one or more
     * calls of next(), and each call moves the generator one step on, so skipping n steps lands exactly where n draws
     * would have.
     */
    private static final class DrawCountingRandom extends Random {
        private long draws;

        DrawCountingRandom(long seed) {
            super(seed);
        }

        @Override
        protected int next(int bits) {
            draws++;
            return super.next(bits);
        }

        long draws() {
            return draws;
        }

        void skip(long steps) {
            for (long i = 0; i < steps; i++) {
                next(1);
            }
        }
    }

    /** LWJGL's STBVorbis, if its natives load on this machine. */
    private static final class Stb {
        static Stb load(Path probe) {
            try {
                Stb stb = new Stb();
                return stb.decode(Files.readAllBytes(probe)) != null ? stb : null;
            } catch (Throwable t) {
                System.out.println("STBVorbis unavailable: " + t);
                return null;
            }
        }

        float[][] decode(byte[] bytes) {
            ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
            IntBuffer error = MemoryUtil.memAllocInt(1);
            try {
                data.put(bytes).flip();
                long handle = STBVorbis.stb_vorbis_open_memory(data, error, null);
                if (handle == 0) {
                    return null;
                }
                int channels;
                try (STBVorbisInfo info = STBVorbisInfo.malloc()) {
                    STBVorbis.stb_vorbis_get_info(handle, info);
                    channels = info.channels();
                }
                FloatBuffer chunk = MemoryUtil.memAllocFloat(4096 * channels);
                float[][] all = new float[channels][1 << 16];
                int total = 0;
                try {
                    while (true) {
                        chunk.clear();
                        int read = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, chunk);
                        if (read <= 0) {
                            break;
                        }
                        if (total + read > all[0].length) {
                            for (int c = 0; c < channels; c++) {
                                all[c] = Arrays.copyOf(all[c], Math.max(all[c].length * 2, total + read));
                            }
                        }
                        for (int i = 0; i < read; i++) {
                            for (int c = 0; c < channels; c++) {
                                all[c][total + i] = chunk.get(i * channels + c);
                            }
                        }
                        total += read;
                    }
                } finally {
                    MemoryUtil.memFree(chunk);
                    STBVorbis.stb_vorbis_close(handle);
                }
                for (int c = 0; c < channels; c++) {
                    all[c] = Arrays.copyOf(all[c], total);
                }
                return all;
            } finally {
                MemoryUtil.memFree(data);
                MemoryUtil.memFree(error);
            }
        }
    }
}
