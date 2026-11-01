package ru.gora.musicbattle.audio.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The formats and fixes that completed Blocky Music's own converter for 0.2.0-beta.2, so players never need ffmpeg:
 * MPEG transport streams (HLS downloads joined without ffmpeg, .ts, Blu-ray and camcorder .m2ts) with AAC, MPEG audio or
 * Opus; Opus in MP4; Vorbis, AAC, MP3, FLAC and PCM in WebM/Matroska; WAV as RIFX and with IMA or Microsoft ADPCM;
 * free-format MP3 and MP3 whose sample rate changes part way; the limiter that keeps the 1.5x/2x copies of loud songs
 * from clipping; and the granule test the server uses to measure stream rips exactly. The script compiles the codec
 * package with nothing else on the classpath (no Minecraft, no LWJGL): this runs on a dedicated server's classpath too.
 *
 * <p>Fixtures (tools/fixtures/extras) were made by ffmpeg 9 and LAME 4.0 from the computed signal of
 * tools/fixtures/gen/FixtureSignal.java (no recorded music); the recipes are in tools/fixtures/gen (make-fixtures.sh
 * extras, and README.md for the one file without one). Each one's expected length is ffmpeg 9's own decode
 * ({@code ffmpeg -i FILE -f f32le -c:a pcm_f32le -}), except where the comment says this follows the format's
 * specification instead and why; the SHA-256 of every decode is this decoder's, recorded once it was verified against
 * ffmpeg (same length, lag 0; the dB figures are in the comments), so any change to its arithmetic shows. Everything
 * else is built here from the fixtures: other packet sizes and junk in front of a transport stream, damaged sync
 * bytes, cuts, null-packet padding, an AC-3 program table, laced Matroska blocks, RIFX copies, truncated ADPCM, joined
 * MP3s of two rates, streams for the speed and memory limits, and seeded fuzzing. Nothing here runs ffmpeg or the game.
 *
 * <p>Usage: java ru.gora.musicbattle.audio.codec.NativeAudioExtrasChecks PROJECT_ROOT [record]
 */
public final class NativeAudioExtrasChecks {
    private static int checks;
    private static boolean record;
    private static Path root;

    /** A fixture: its kind, rate, channels, frames, and the SHA-256 of its decode. */
    private record Expected(String file, AudioDecoders.Kind kind, int rate, int channels, long frames, String sha256) {
    }

    private static final List<Expected> FIXTURES = List.of(
            // Four HLS segments of 0.5 s joined, as yt-dlp leaves them without ffmpeg, under the .m4a name the format
            // had: = ffmpeg (71 680 frames), lag 0, 139.2 dB.
            new Expected("ts-hls-aac.m4a", AudioDecoders.Kind.TS, 44100, 2, 71680,
                    "69376595344e4e58213395b4ee6f2cabf3009f44cd9b329ce4b2e990c16d833d"),
            // MP3 in a transport stream: = ffmpeg, 128.5 dB.
            new Expected("ts-mp3.ts", AudioDecoders.Kind.TS, 44100, 2, 72576,
                    "45f900673e565304cd300fb2eb49dc68ec9eabc1f8047bb030ae039f86951aea"),
            // Opus in a transport stream: the control headers' start_trim (312, the pre-skip) and end_trim are applied,
            // as the Opus-in-TS specification says, so this is the 1.6 s that went in. ffmpeg 9 ignores both trims and
            // gives 77 760 frames, 312 late (lag 312, 46.9 dB there: ffmpeg's own Opus decoder against libopus's).
            new Expected("ts-opus.ts", AudioDecoders.Kind.TS, 48000, 2, 76800,
                    "8fed87df2e9a740e1e708e823b6fb960981f63d0fbd8b628413c56fe4a95db69"),
            // The same Opus packets cut into 9 pieces of 0.2 s by ffmpeg's segment muxer and joined back to back: each
            // piece's first access unit carries the 312-sample start trim again, which only counts at the very start,
            // so this is ts-opus.ts sample for sample (ffmpeg, which ignores every trim, gives 77 760 for both).
            new Expected("ts-opus-joined.ts", AudioDecoders.Kind.TS, 48000, 2, 76800,
                    "8fed87df2e9a740e1e708e823b6fb960981f63d0fbd8b628413c56fe4a95db69"),
            // 5.1 Opus in TS, handed out in WAVE order like the AAC transport streams carry (ffmpeg's order).
            new Expected("ts-opus51.ts", AudioDecoders.Kind.TS, 48000, 6, 24000,
                    "7f87df4abf56a170430b889ac6be9aa1d2942eca034ebd216e53d0e23a031c0a"),
            // ffmpeg's Blu-ray mode lists its AAC as a private stream (type 6) with no descriptor; the payload tells.
            // = ffmpeg (39 936), 138.9 dB.
            new Expected("ts-aac.m2ts", AudioDecoders.Kind.TS, 48000, 1, 39936,
                    "8094ba17f078252ed8471c4db3ab61b15f282ba703b176b5f7ffbf50dcb61491"),
            // Opus in MP4 with an edit list (media time 312, duration 76 800): the edit list is followed, giving the
            // song's exact length; ffmpeg 9 skips the pre-skip but not the padding (77 448). Lag 0.
            new Expected("mp4-opus.mp4", AudioDecoders.Kind.MP4, 48000, 2, 76800,
                    "8fed87df2e9a740e1e708e823b6fb960981f63d0fbd8b628413c56fe4a95db69"),
            // Fragmented, edit duration 0 ("to the end"): = ffmpeg (39 048), lag 0, 136.8 dB.
            new Expected("mp4-opus-frag.mp4", AudioDecoders.Kind.MP4, 48000, 1, 39048,
                    "3235b4d949522125e7256f09ca9485e3b9d941c2565565ec62ef67bb33a48001"),
            new Expected("mp4-opus51.mp4", AudioDecoders.Kind.MP4, 48000, 6, 24000,
                    "7f87df4abf56a170430b889ac6be9aa1d2942eca034ebd216e53d0e23a031c0a"),
            // Vorbis in WebM (ffmpeg's own Vorbis encoder): = ffmpeg's length (70 592) and Minecraft's JOrbis decode of
            // the same packets in Ogg to 140.5 dB; ffmpeg's own Vorbis decoder is 5.8 dB from both on these packets.
            new Expected("mkv-vorbis.webm", AudioDecoders.Kind.WEBM, 44100, 2, 70592,
                    "945d6e6d3116cb3a77193558d91b304b277f1e30a01dab60cba810363e29782b"),
            // Vorbis written by ffmpeg's own encoder straight into WebM, which stores a CodecDelay of 1024 that counts
            // the first packet the specification never outputs: = ffmpeg's length (22 080), lag 0 against both the
            // source signal and ffmpeg, and Minecraft's JOrbis decode of the same packets in Ogg to 140.6 dB.
            new Expected("mkv-vorbis-direct.webm", AudioDecoders.Kind.WEBM, 44100, 2, 22080,
                    "78fbad8f8ed3866b6e57e3bda0a802f86738904f557e03d548ca84389fff4155"),
            // AAC in .mka: CodecDelay (1024) dropped, as ffmpeg does: 70 656 = ffmpeg, 139.2 dB.
            new Expected("mkv-aac.mka", AudioDecoders.Kind.WEBM, 44100, 2, 70656,
                    "996ca1ba760f97f9906aa27468560f05910146cd794e013fe462c0983f4a9eee"),
            // 5.1 AAC in .mka, in Vorbis order (the order WebM's Opus and Vorbis use); = ffmpeg's samples remapped.
            new Expected("mkv-aac51.mka", AudioDecoders.Kind.WEBM, 48000, 6, 24576,
                    "ec1a1bea5579780975b373977d230c081b93ecc3b605aff55952fdda9bb9bee0"),
            // MP3 in .mka: CodecDelay and the last block's DiscardPadding applied, as ffmpeg: 38 400, 129.4 dB.
            new Expected("mkv-mp3.mka", AudioDecoders.Kind.WEBM, 48000, 1, 38400,
                    "4095e725e22930025ff45068eeb9b5e5f085f4f5ca624284143c533baf571575"),
            new Expected("mkv-flac.mka", AudioDecoders.Kind.WEBM, 22050, 1, 6615,
                    "7b3f9fa8e98044ec2d6050887d69ee6b7a8611894a79eb73aac3e7f92b4cddc6"),
            // Ogg FLAC, the usual content of an .oga file, which the converter used to refuse by name: the FLAC packets
            // of mkv-flac.mka copied into Ogg by ffmpeg (-c:a copy -f ogg), so the same samples; and a stereo 16-bit
            // one ffmpeg encoded itself (-c:a flac -f ogg). Both = ffmpeg's decode bit for bit (the same SHA-256 of
            // the f32le samples): FLAC is lossless.
            new Expected("ogg-flac.oga", AudioDecoders.Kind.OGG_FLAC, 22050, 1, 6615,
                    "7b3f9fa8e98044ec2d6050887d69ee6b7a8611894a79eb73aac3e7f92b4cddc6"),
            new Expected("ogg-flac-stereo.oga", AudioDecoders.Kind.OGG_FLAC, 44100, 2, 57330,
                    "d57b740de2c693c4dbb2e4e5e6956c7ba85754d866c6b29e961af7f24d1d7ba4"),
            new Expected("mkv-pcm.mka", AudioDecoders.Kind.WEBM, 22050, 1, 4410,
                    "686ccdb5b57a06d41052cf272d47c0eb4d4c7b7bba46ec03aaa109b53ce0de62"),
            // IMA and Microsoft ADPCM: bit for bit ffmpeg's decode.
            new Expected("wav-ima.wav", AudioDecoders.Kind.WAV, 48000, 1, 38779,
                    "5978a48345e8d887862308d259e6acba11fc1510aaaa9e8cb69d52633e182c97"),
            new Expected("wav-ms.wav", AudioDecoders.Kind.WAV, 44100, 2, 11132,
                    "6d74c86da8ed6928f919557e5f4b5571eb1896d947e53e3095a193527923fd52"),
            // Free-format MP3, which ffmpeg cannot read ("Failed to find two consecutive MPEG audio frames"): LAME's
            // own decoder gives the same length and lag 0 (87.5 dB, its 16-bit output) with the Info tag's trimming.
            new Expected("mp3-free.mp3", AudioDecoders.Kind.MP3, 22050, 1, 26460,
                    "2363903dfc9a91add9639e57c9baabedc7e9bcc38af20a026619a37102e661b2"),
            // Without a tag nothing is trimmed, ffmpeg's rule for every untagged MP3 (LAME's decoder drops 529).
            new Expected("mp3-free-notag.mp3", AudioDecoders.Kind.MP3, 48000, 1, 78336,
                    "e7428b8b650cc09f750144ac5ea4002397df996540ad405da1b1dbbcf6f84d64"));

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).toAbsolutePath();
        record = args.length > 1 && args[1].equals("record");
        long start = System.nanoTime();
        Path work = Files.createTempDirectory("blocky-extras-checks");
        try {
            fixtures(work);
            transportStreams(work);
            matroska(work);
            oggFlac(work);
            mp4Opus();
            wav(work);
            mp3(work);
            limiter();
            granules(work);
            fuzz(work);
            interrupt(work);
            speedAndMemory(work);
        } finally {
            deleteTree(work);
        }
        System.out.printf(Locale.ROOT, "%d checks passed in %.1f s%n", checks, (System.nanoTime() - start) / 1e9);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures

    private static Path fixture(String name) {
        return root.resolve("tools/fixtures/extras").resolve(name);
    }

    private static void fixtures(Path work) throws Exception {
        System.out.println("fixtures");
        for (Expected e : FIXTURES) {
            Path file = fixture(e.file());
            equal(AudioDecoders.detect(file), e.kind(), e.file() + " is sniffed as " + e.kind());
            // The name never decides: the same bytes under the name of another format.
            Path renamed = work.resolve(e.file().replace('.', '_') + ".ogg");
            Files.copy(file, renamed);
            equal(AudioDecoders.detect(renamed), e.kind(), e.file() + " named .ogg is still " + e.kind());
            Decoded d = decode(renamed);
            Files.delete(renamed);
            if (record) {
                System.out.printf("  RECORD %s %d Hz %d ch %d frames %s%n", e.file(), d.rate, d.channels, d.frames,
                        d.sha256);
                continue;
            }
            equal(d.rate, e.rate(), e.file() + ": sample rate");
            equal(d.channels, e.channels(), e.file() + ": channels");
            equal(d.frames, e.frames(), e.file() + ": frames");
            equal(d.sha256, e.sha256(), e.file() + ": the decoded samples are the verified ones");
            check(d.finite, e.file() + ": every sample is finite");
            if (d.hint >= 0) {
                equal(d.hint, d.frames, e.file() + ": the frame count hint is the length");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MPEG transport streams

    private static void transportStreams(Path work) throws Exception {
        System.out.println("transport streams");
        byte[] ts = Files.readAllBytes(fixture("ts-hls-aac.m4a"));
        Decoded whole = decode(write(work, "whole.ts", ts));
        // Other packet sizes carry the same packets: Blu-ray's 192 bytes (a time stamp in front of each) and 204 (16
        // bytes of error correction after each).
        sameAs(work, "m2ts.m2ts", repack(ts, 4, 0), whole, "192-byte packets");
        sameAs(work, "rs.ts", repack(ts, 0, 16), whole, "204-byte packets");
        // A tag, junk or zeros in front (a recording that starts part way into a packet, or a wrapped file): the
        // sync bytes are found further in. These went to the ADTS decoder before and failed as damaged AAC.
        sameAs(work, "tagged.ts", concat(id3v2(300), ts), whole, "behind an ID3 tag");
        byte[] junk = new byte[300];
        new Random(5).nextBytes(junk);
        sameAs(work, "junk.ts", concat(junk, ts), whole, "behind 300 random bytes");
        sameAs(work, "zeros.ts", concat(new byte[1000], ts), whole, "behind 1000 zero bytes");
        sameAs(work, "part.ts", concat(Arrays.copyOfRange(ts, 100, 188), ts), whole, "behind the end of a packet");
        // A damaged sync byte among the first packets: the packet is lost, the stream is still a transport stream.
        // Packet 1 is the program association table: read by path, the table is found further on and the audio is
        // read from the start, as ffmpeg reads it (all 71 680 frames). Packet 3 starts the first PES packet of the
        // audio, which is lost with it: 55 296 frames, = ffmpeg. Packet 7 is inside that PES packet: only the two
        // frames the hole cuts into are lost (69 632), and the PES packet's other frames are kept; ffmpeg loses
        // three around it (68 608), since its parser hands its decoder the cut frame and one more it misreads.
        long[] lostTo = {0, 71680, 0, 55296, 0, 0, 0, 69632};
        for (int bad : new int[] {1, 3, 7}) {
            byte[] broken = ts.clone();
            broken[bad * 188] = 0x48;
            Path file = write(work, "sync" + bad + ".ts", broken);
            equal(AudioDecoders.detect(file), AudioDecoders.Kind.TS, "a broken sync byte in packet " + bad + " is TS");
            Decoded d = decode(file);
            equal(d.frames, lostTo[bad], "and decodes without the frames the lost packet broke");
        }
        // A stream cut part way into its first HLS segment: from the next PES packet on, before the next program
        // table, as ffmpeg (55 296 frames for a cut at packet 10 of this file).
        equal(decode(write(work, "cut10.ts", Arrays.copyOfRange(ts, 10 * 188, ts.length))).frames, 55296L,
                "a stream cut at packet 10 keeps the audio before the next program table");
        // Shorter than eight packets: whole packets from the first byte and a program table make it TS.
        byte[] seven = Arrays.copyOf(ts, 7 * 188);
        equal(AudioDecoders.detect(write(work, "seven.ts", seven)), AudioDecoders.Kind.TS, "a 7-packet stream is TS");
        // A constant-rate stream padded with null packets, cut inside a run of nulls longer than 64 packets.
        byte[] padded = withNulls(ts, 12, 200);
        Decoded paddedWhole = decode(write(work, "padded.ts", padded));
        equal(paddedWhole.sha256, whole.sha256, "null packets change nothing");
        byte[] nullCut = Arrays.copyOfRange(padded, 12 * 188 + 100 * 188 + 37, padded.length);
        Path nullCutFile = write(work, "nullcut.ts", nullCut);
        equal(AudioDecoders.detect(nullCutFile), AudioDecoders.Kind.TS, "a cut inside a run of null packets is TS");
        check(decode(nullCutFile).frames > 0, "and decodes from the next program table on");
        // A duplicated packet (allowed once by ISO/IEC 13818-1, same continuity counter) is passed over.
        int audio = firstPayloadPacket(ts, audioPid(ts), 3);
        byte[] duplicated = concat(Arrays.copyOf(ts, audio + 188), Arrays.copyOfRange(ts, audio, ts.length));
        sameAs(work, "dup.ts", duplicated, whole, "with a duplicated packet");
        // An audio codec no decoder here reads is named at once, from the program table.
        byte[] ac3 = withStreamType(ts, 0x81);
        try {
            AudioDecoders.detect(write(work, "ac3.ts", ac3));
            fail("an AC-3 transport stream is accepted");
        } catch (UnsupportedFormatException expected) {
            equal(expected.formatName(), "AC-3 in MPEG-TS", "an AC-3 transport stream is named");
        }
        // Data that only repeats with a packet's length, sync byte included, is not a transport stream.
        byte[] packet = Arrays.copyOfRange(ts, audio, audio + 188);
        ByteArrayOutputStream repeated = new ByteArrayOutputStream();
        for (int i = 0; i < 70; i++) {
            repeated.writeBytes(packet);
        }
        try {
            AudioDecoders.detect(write(work, "repeat.bin", repeated.toByteArray()));
            fail("70 identical packets are taken for something");
        } catch (UnsupportedFormatException expected) {
            equal(expected.formatName(), null, "70 identical packets are no transport stream");
        }
        // The probe rule on its own: random data and real MP3 never pass, a transport stream from any offset does.
        Random random = new Random(11);
        for (int i = 0; i < 200; i++) {
            byte[] noise = new byte[MpegTsDecoder.PROBE_BYTES];
            random.nextBytes(noise);
            for (int k = random.nextInt(4096); k < noise.length; k += 188 + random.nextInt(3) * 4) {
                noise[k] = 0x47;
            }
            if (MpegTsDecoder.looksLike(noise)) {
                fail("random data salted with sync bytes passes for a transport stream (" + i + ")");
            }
        }
        checks++;
        for (String mp3 : new String[] {"cbr-lame.mp3", "layer2.mp2", "mono-lsf.mp3", "notag.mp3", "vbr-xing.mp3"}) {
            check(!MpegTsDecoder.looksLike(Files.readAllBytes(root.resolve("tools/fixtures/mp3").resolve(mp3))),
                    mp3 + " is no transport stream");
        }
        for (int from = 1; from < 188; from += 13) {
            check(MpegTsDecoder.looksLike(Arrays.copyOfRange(ts, from, ts.length)), "a cut at byte " + from + " is TS");
        }
        holesAndCuts(work);
        joinedPieces(work);
    }

    /**
     * Pieces joined back to back, as an HLS download or ffmpeg's segment muxer leaves them. Every piece is a transport
     * stream of its own: its continuity counters start again from 0, and an Opus piece's first access unit carries
     * the pre-skip's start trim again. The decoder runs on across a join, so the samples there are the song's own;
     * ffmpeg ignores every trim and loses nothing at a join, and nor must this (cutting the 312 samples there lost
     * 6.5 ms at every join, the rest of the song that much early; taking a restarted counter that lands on the
     * piece before's last for a duplicate packet lost the PES packet it starts, once in about sixteen joins).
     */
    private static void joinedPieces(Path work) throws Exception {
        System.out.println("joined pieces");
        Decoded opus = decode(fixture("ts-opus.ts"));
        Decoded joined = decode(fixture("ts-opus-joined.ts"));
        equal(joined.sha256, opus.sha256, "Opus TS joined from 9 segment pieces: the samples of the one-piece stream");
        // The trims of the control headers, rewritten: the access units of ts-opus.ts in a transport stream of this
        // check's own, one PES packet each, with the trims each case gives.
        MpegTsDecoder.Demuxer demuxer = new MpegTsDecoder.Demuxer(
                new ByteArrayInputStream(Files.readAllBytes(fixture("ts-opus.ts"))));
        demuxer.findAudio();
        OpusPcmStream.Packets packets = demuxer.opusPackets();
        List<byte[]> units = new ArrayList<>();
        int firstTrim = -1;
        int lastTrim = 0;
        while (packets.next()) {
            firstTrim = firstTrim < 0 ? packets.trimStart() : firstTrim;
            lastTrim = packets.trimEnd();
            units.add(Arrays.copyOfRange(packets.data(), packets.offset(), packets.offset() + packets.length()));
        }
        int n = units.size();
        check(n == 81 && firstTrim == 312 && lastTrim == 648, "ts-opus.ts: 81 access units, trims 312 and 648 ("
                + n + ", " + firstTrim + ", " + lastTrim + ")");
        int[] start = new int[n];
        int[] end = new int[n];
        start[0] = firstTrim;
        end[n - 1] = lastTrim;
        Decoded remuxed = decode(write(work, "opus-trims.ts", opusTs(units, start, end)));
        equal(remuxed.sha256, opus.sha256, "Opus access units remuxed with their own trims: the same samples");
        // Trims inside the song are ignored: start trims on access units 20 and 40, end trims on 30 (part) and 31
        // (all of it).
        int[] midStart = start.clone();
        int[] midEnd = end.clone();
        midStart[20] = 312;
        midStart[40] = 960;
        midEnd[30] = 500;
        midEnd[31] = 960;
        equal(decode(write(work, "opus-midtrims.ts", opusTs(units, midStart, midEnd))).sha256, opus.sha256,
                "start and end trims inside an Opus TS are ignored");
        // The end trim of the last access unit counts, up to the whole of it.
        for (int last : new int[] {lastTrim + 200, 960}) {
            int[] e = end.clone();
            e[n - 1] = last;
            Decoded d = decode(write(work, "opus-endtrim.ts", opusTs(units, start, e)));
            equal(d.frames, opus.frames - (last - lastTrim), "an end trim of " + last + " on the last access unit");
            check(prefixEqual(d, opus, (int) d.frames), "and the song before it unchanged");
        }
        // A pre-skip longer than the first access unit: start trims count until the first sample is kept (960 on
        // the first, 312 on the second; ffmpeg's muxer spreads a pre-skip that way over 2.5 ms frames).
        int[] spread = start.clone();
        spread[0] = 960;
        spread[1] = 312;
        Decoded later = decode(write(work, "opus-spread.ts", opusTs(units, spread, end)));
        equal(later.frames, opus.frames - 960, "a start trim spread over two access units");
        boolean shifted = true;
        for (int c = 0; c < 2 && shifted; c++) {
            for (int i = 0; i < later.frames && shifted; i++) {
                shifted = Float.floatToRawIntBits(later.pcm[c][i]) == Float.floatToRawIntBits(opus.pcm[c][i + 960]);
            }
        }
        check(shifted, "and the samples 960 further in");
        // A restarted counter that lands on the one the piece before ended on is no duplicate packet: joined there,
        // AAC, MPEG audio and Opus streams keep every PES packet (a real duplicate is passed over, see above).
        for (String name : new String[] {"ts-hls-aac.m4a", "ts-mp3.ts", "ts-opus.ts"}) {
            byte[] ts = Files.readAllBytes(fixture(name));
            Decoded whole = decode(write(work, "whole-" + name, ts));
            int pid = audioPid(ts);
            byte[] restarted = restartCounterAtPes(ts, pid);
            Decoded d = decode(write(work, "restart-" + name, restarted));
            equal(d.sha256, whole.sha256, name + ": a counter that restarts on the last one's value loses nothing");
        }
    }

    /** The stream without every {@code every}-th packet of {@code pid} that has a payload; counts them in lost[0]. */
    private static byte[] dropEvery(byte[] ts, int pid, int every, int[] lost) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int k = 0;
        for (int at = 0; at + 188 <= ts.length; at += 188) {
            if (pid(ts, at) == pid && (ts[at + 3] & 0x10) != 0 && ++k % every == 0) {
                lost[0]++;
                continue;
            }
            out.write(ts, at, 188);
        }
        return out.toByteArray();
    }

    /** An Opus transport stream of these access units, each in a PES packet of its own, with these trims. */
    private static byte[] opusTs(List<byte[]> units, int[] start, int[] end) {
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            byte[] u = units.get(i);
            ByteArrayOutputStream au = new ByteArrayOutputStream();
            // The control header: prefix 0x3FF, the two trim flags, the size in bytes of 255 and the rest.
            au.write(0x7F);
            au.write(0xE0 | (start[i] > 0 ? 0x10 : 0) | (end[i] > 0 ? 0x08 : 0));
            int size = u.length;
            while (size >= 255) {
                au.write(0xFF);
                size -= 255;
            }
            au.write(size);
            if (start[i] > 0) {
                au.write(start[i] >> 8 & 0x1F);
                au.write(start[i]);
            }
            if (end[i] > 0) {
                au.write(end[i] >> 8 & 0x1F);
                au.write(end[i]);
            }
            au.writeBytes(u);
            payloads.add(au.toByteArray());
        }
        // A registration descriptor "Opus" and the extension descriptor for two channels.
        byte[] esInfo = {0x05, 4, 'O', 'p', 'u', 's', 0x7F, 2, (byte) 0x80, 2};
        return tsMux(payloads, 0x06, esInfo, 0xBD);
    }

    /**
     * The stream with the audio PID's continuity counters renumbered from the PES packet nearest its middle on, so
     * that its first packet carries the counter of the packet before it, as at a join of two pieces.
     */
    private static byte[] restartCounterAtPes(byte[] ts, int pid) {
        byte[] out = ts.clone();
        int split = -1;
        int base = -1;
        int lastCounter = -1;
        for (int at = 0; at + 188 <= out.length; at += 188) {
            if (pid(out, at) != pid || (out[at + 3] & 0x10) == 0) {
                continue;
            }
            if (split < 0 && at >= out.length / 2 && (out[at + 1] & 0x40) != 0) {
                split = at;
                base = lastCounter;
            }
            lastCounter = out[at + 3] & 0x0F;
        }
        if (split < 0 || base < 0) {
            throw new IllegalStateException("no PES packet to restart the counter at");
        }
        int k = 0;
        for (int at = split; at + 188 <= out.length; at += 188) {
            if (pid(out, at) == pid && (out[at + 3] & 0x10) != 0) {
                out[at + 3] = (byte) ((out[at + 3] & 0xF0) | ((base + k++) & 0x0F));
            }
        }
        return out;
    }

    /**
     * Lost packets, damaged frames and cut ends, the way TV recordings and interrupted HLS downloads have them, for
     * every codec a transport stream carries here. The bounds are the frames the damage itself cuts into, which is
     * less than ffmpeg 9.0.1 loses on the same files: measured on 15 s songs with a lost packet every 997, ffmpeg
     * loses 4096 frames of AAC (this: 4096), 2304 of MP3 (1152) and 4800 of Opus (960).
     */
    private static void holesAndCuts(Path work) throws Exception {
        // name, the most frames one lost packet may cost (what it costs here: the two frames the hole runs through,
        // or one for the 5.1 Opus, whose PES packets hold several access units), and how close the stream's last
        // 2048 frames must come to the song without the loss. Measured: MP3 and stereo Opus settle bit for bit; AAC
        // to 3.4e-4, since its noise substitution draws from a generator that the lost frames no longer advance;
        // 5.1 Opus, whose energy prediction carries the lost packet on, does not settle within this 0.5 s fixture
        // (ffmpeg's decode of the same file, which loses 2880 frames there, settles no better), so only its length
        // and its start are checked.
        Object[][] streams = {{"ts-hls-aac.m4a", 2048, 1e-3}, {"ts-mp3.ts", 2304, 0.0}, {"ts-opus.ts", 1920, 1e-3},
                {"ts-opus51.ts", 960, Double.POSITIVE_INFINITY}};
        for (Object[] stream : streams) {
            String name = (String) stream[0];
            int bound = (Integer) stream[1];
            double tolerance = (Double) stream[2];
            byte[] ts = Files.readAllBytes(fixture(name));
            Decoded whole = decode(write(work, "whole-" + name, ts));
            int pid = audioPid(ts);
            int packets = 0;
            for (int at = 0; at + 188 <= ts.length; at += 188) {
                packets += pid(ts, at) == pid && (ts[at + 1] & 0x40) == 0 ? 1 : 0;
            }
            // One lost packet inside a PES packet, at a third and at two thirds of the stream.
            for (int part = 1; part <= 2; part++) {
                int at = firstPayloadPacket(ts, pid, packets * part / 3);
                byte[] holed = concat(Arrays.copyOf(ts, at), Arrays.copyOfRange(ts, at + 188, ts.length));
                Decoded d = decode(write(work, "hole-" + name, holed));
                long lost = whole.frames - d.frames;
                check(lost > 0 && lost <= bound, name + ": a lost packet costs " + lost + " frames, at most " + bound);
                // Where the lost packet was in the song: as far as the stream cut just before it decodes.
                long before = decode(write(work, "before-" + name, Arrays.copyOf(ts, at))).frames;
                check(before > 2L * bound && prefixEqual(d, whole, (int) before - 2 * bound),
                        name + ": the song before the lost packet is unchanged");
                int tail = 2048;
                double worst = 0;
                for (int c = 0; c < d.channels; c++) {
                    for (int i = 1; i <= tail; i++) {
                        worst = Math.max(worst, Math.abs(d.pcm[c][(int) d.frames - i]
                                - whole.pcm[c][(int) whole.frames - i]));
                    }
                }
                check(worst <= tolerance, name + ": after a lost packet the song goes on in step"
                        + " (largest difference in its last " + tail + " frames " + worst + ")");
            }
            // Cut off part way, inside a packet and on a packet boundary, at ten places: never refused, and the same
            // samples as the whole song for as long as the cut one lasts.
            for (int k = 1; k <= 10; k++) {
                int cut = (int) ((long) ts.length * k / 11) + (k % 2 == 0 ? 0 : 97);
                cut -= k % 2 == 0 ? cut % 188 : 0;
                Decoded d = decode(write(work, "cut-" + name, Arrays.copyOf(ts, cut)));
                check(d.frames > 0 && d.frames <= whole.frames && prefixEqual(d, whole, (int) d.frames - 2 * bound),
                        name + " cut at byte " + cut + " decodes as the start of the song");
            }
        }
        // MP3 with many packets lost (every 5th to 17th of the stream: each PES packet of this file is 13 packets, so
        // every 13th is the end of one): each hole costs the frames it runs through, and the frames after it whose bit
        // reservoir lay before it are silent, never bursts of the bytes before the hole read as their own. ffmpeg
        // 9.0.1 peaks at 6.8 to 523 times full scale on these files; before this rule, 4.6 to 27.
        Decoded mp3Whole = decode(fixture("ts-mp3.ts"));
        byte[] mp3Ts = Files.readAllBytes(fixture("ts-mp3.ts"));
        for (int every : new int[] {5, 7, 9, 13, 17}) {
            int[] lostPackets = new int[1];
            Decoded d = decode(write(work, "mp3-drop" + every + ".ts", dropEvery(mp3Ts, audioPid(mp3Ts), every,
                    lostPackets)));
            double peak = 0;
            for (int c = 0; c < d.channels; c++) {
                for (int i = 0; i < d.frames; i++) {
                    peak = Math.max(peak, Math.abs(d.pcm[c][i]));
                }
            }
            check(peak <= 1.0, "MP3 in TS, every " + every + "th packet lost: no sample above full scale (" + peak + ")");
            long lost = mp3Whole.frames - d.frames;
            // A lost packet of 184 bytes runs through at most three of this file's 156-byte frames.
            check(lost > 0 && lost <= 3L * 1152 * lostPackets[0], "MP3 in TS, every " + every + "th packet lost: "
                    + lost + " frames lost for " + lostPackets[0] + " packets");
        }
        // A damaged AAC frame, as a broadcast has them (no packet lost, bytes wrong): that frame is dropped, as ffmpeg
        // drops it, and the song goes on; a plain .aac file still refuses a damaged frame.
        byte[] ts = Files.readAllBytes(fixture("ts-hls-aac.m4a"));
        Decoded whole = decode(write(work, "whole-damage.ts", ts));
        int pid = audioPid(ts);
        // The first ADTS header in an audio packet from the 20th on: the rest of the packet after it is zeroed,
        // which this frame's decoder refuses ("spectral data runs past the end").
        byte[] damaged = ts.clone();
        boolean zeroed = false;
        for (int at = firstPayloadPacket(ts, pid, 20); !zeroed && at + 188 <= ts.length; at += 188) {
            if (pid(ts, at) != pid) {
                continue;
            }
            int from = at + 4 + ((ts[at + 3] & 0x20) != 0 ? 1 + (ts[at + 4] & 0xFF) : 0);
            for (int i = from; i + 7 < at + 188 - 40; i++) {
                if (AdtsDecoder.validHeader(ts, i)) {
                    Arrays.fill(damaged, i + 7, at + 188, (byte) 0);
                    zeroed = true;
                    break;
                }
            }
        }
        check(zeroed, "an ADTS header to damage the frame of");
        Decoded d = decode(write(work, "damaged-aac.ts", damaged));
        check(whole.frames - d.frames <= 2048 && d.frames < whole.frames,
                "a damaged AAC frame in a transport stream costs that frame and no more ("
                        + (whole.frames - d.frames) + " frames)");
        unalignedPes(work);
        // An Opus access unit whose packet is invalid (code 3 with no frames) in a transport stream: dropped, as
        // ffmpeg drops it, and the song goes on (Ogg Opus and WebM keep the strict rule).
        byte[] opusTs = Files.readAllBytes(fixture("ts-opus.ts"));
        Decoded opusWhole = decode(write(work, "whole-opus.ts", opusTs));
        int opusPid = audioPid(opusTs);
        byte[] invalid = opusTs.clone();
        boolean spoiled = false;
        for (int at = firstPayloadPacket(opusTs, opusPid, 30) - 188 * 4; !spoiled && at + 188 <= opusTs.length;
                at += 188) {
            if (pid(opusTs, at) != opusPid || (opusTs[at + 1] & 0x40) == 0) {
                continue;
            }
            int from = at + 4 + ((opusTs[at + 3] & 0x20) != 0 ? 1 + (opusTs[at + 4] & 0xFF) : 0);
            int au = from + 9 + (opusTs[from + 8] & 0xFF);
            if (au + 6 < at + 188 && (opusTs[au] & 0xFF) == 0x7F && (opusTs[au + 1] & 0xE0) == 0xE0
                    && (opusTs[au + 1] & 0x1C) == 0) {
                int p = au + 2;
                while ((opusTs[p] & 0xFF) == 0xFF) {
                    p++;
                }
                invalid[p + 1] = (byte) ((opusTs[p + 1] & 0xFC) | 3);
                invalid[p + 2] = 0;
                spoiled = true;
            }
        }
        check(spoiled, "an Opus access unit to spoil");
        Decoded spoilt = decode(write(work, "invalid-opus.ts", invalid));
        equal(opusWhole.frames - spoilt.frames, 960L, "an invalid Opus packet in a transport stream costs its 20 ms");
        // The rule itself, on a plain ADTS file: the transport stream reader's tolerant decoder drops a frame it
        // cannot decode (here a payload of zeros) and goes on; one whose payload names a tool this decoder lacks
        // (0x5A bytes read as coupling channels) is damage too; more than eight in a row is no song. A plain .aac
        // file keeps the strict rule and refuses the first damaged frame.
        byte[] adts = Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-stereo-44k.aac"));
        equal(adtsFrames(garble(adts, 20, 1, 0x00), true), 70656L, "a damaged ADTS frame costs that frame");
        equal(adtsFrames(garble(adts, 20, 3, 0x5A), true), 68608L,
                "three frames that name coupling channels part way are three damaged frames");
        equal(adtsFrames(garble(adts, 20, 20, 0x5A), true), -1L, "twenty damaged frames in a row are refused");
        equal(adtsFrames(garble(adts, 20, 1, 0x00), false), -1L, "a plain .aac file refuses a damaged frame");
        equal(adtsFrames(adts, false), adtsFrames(adts, true), "an undamaged stream is the same either way");
    }

    /**
     * Broadcast muxers cut the audio into PES packets of their own size, so a frame often runs on into the next PES
     * packet. Plain ADTS and MP3 files muxed here into PES packets of 1000 bytes (frames not aligned to them) decode
     * to the samples of the plain file; a lost packet costs the frames it runs through and the song then goes on in
     * step; and a PES packet whose start code is damaged (so the reader drops it whole) costs the frames it held
     * and the two it cut, never more, with the rest of the song in step after it.
     */
    private static void unalignedPes(Path work) throws Exception {
        Object[][] files = {{"tools/fixtures/aac/lc-stereo-44k.aac", 0x0F, 1024}, {"tools/fixtures/mp3/cbr-lame.mp3", 0x03, 1152}};
        for (Object[] f : files) {
            String name = (String) f[0];
            int frame = (Integer) f[2];
            byte[] es = Files.readAllBytes(root.resolve(name));
            if (name.endsWith(".mp3")) {
                // The MP3's ID3 tag stays out of the transport stream, as a muxer leaves it out.
                int skip = es[0] == 'I' && es[1] == 'D' && es[2] == '3' ? 10 + ((es[6] & 0x7F) << 21
                        | (es[7] & 0x7F) << 14 | (es[8] & 0x7F) << 7 | (es[9] & 0x7F)) : 0;
                es = Arrays.copyOfRange(es, skip, es.length);
            }
            // The frames as the transport stream reader hands them on, without a file's tag and its trims.
            Decoded plain = decode(name.endsWith(".mp3") ? Mp3Decoder.openFrames(new ByteArrayInputStream(es))
                    : AdtsDecoder.open(new ByteArrayInputStream(es)));
            byte[] ts = tsMux(es, (Integer) f[1], 1000);
            Decoded whole = decode(write(work, "unaligned.ts", ts));
            equal(whole.sha256, plain.sha256, name + " in 1000-byte PES packets decodes as the plain file");
            int pid = 0x100;
            int payloadPackets = 0;
            for (int at = 0; at + 188 <= ts.length; at += 188) {
                payloadPackets += pid(ts, at) == pid ? 1 : 0;
            }
            // A lost packet half way.
            int at = firstPayloadPacket(ts, pid, payloadPackets / 2);
            Decoded holed = decode(write(work, "unaligned-hole.ts",
                    concat(Arrays.copyOf(ts, at), Arrays.copyOfRange(ts, at + 188, ts.length))));
            long lost = whole.frames - holed.frames;
            check(lost > 0 && lost <= 2L * frame, name + ", unaligned: a lost packet costs " + lost + " frames");
            check(inStep(holed, whole, lost), name + ", unaligned: the song goes on in step after it");
            // A damaged PES start code half way: that PES packet is dropped, and the frames it cut on either side.
            int start = -1;
            for (int p = ts.length / 2 / 188 * 188; p + 188 <= ts.length && start < 0; p += 188) {
                if (pid(ts, p) == pid && (ts[p + 1] & 0x40) != 0) {
                    start = p;
                }
            }
            byte[] broken = ts.clone();
            broken[start + 4 + ((ts[start + 3] & 0x20) != 0 ? 1 + (ts[start + 4] & 0xFF) : 0) + 2] = 0x07;
            Decoded gap = decode(write(work, "unaligned-gap.ts", broken));
            long gapLost = whole.frames - gap.frames;
            long bytesPerFrame = es.length / (plain.frames / frame);
            long most = (1000L / bytesPerFrame + 3) * frame;
            check(gapLost > 0 && gapLost <= most, name + ", unaligned: a dropped PES packet costs " + gapLost
                    + " frames, at most " + most);
            check(inStep(gap, whole, gapLost), name + ", unaligned: the song goes on in step after"
                    + " the dropped PES packet");
            // A counter restart between two packets that both carry a whole 184-byte payload: PES packets of
            // 4 x 184 - 14 bytes fill their packets exactly, so no adaptation field tells the two apart by length.
            // The same counter with other bytes is a restart, not a duplicate, and nothing may be lost there.
            byte[] full = tsMux(es, (Integer) f[1], 4 * 184 - 14);
            Decoded fullWhole = decode(write(work, "full-payloads.ts", full));
            byte[] fullRestarted = restartCounterAtPes(full, pid);
            int split = -1;
            for (int p = (full.length / 2 + 187) / 188 * 188; p + 188 <= full.length && split < 0; p += 188) {
                if (pid(full, p) == pid && (full[p + 1] & 0x40) != 0) {
                    split = p;
                }
            }
            int before = split - 188;
            while (pid(full, before) != pid) {
                before -= 188;
            }
            check((fullRestarted[split + 3] & 0x30) == 0x10 && (fullRestarted[before + 3] & 0x30) == 0x10
                            && (fullRestarted[split + 3] & 0x0F) == (fullRestarted[before + 3] & 0x0F),
                    name + ": the restart lands on two packets with whole payloads and the same counter");
            equal(decode(write(work, "full-payloads-restart.ts", fullRestarted)).sha256, fullWhole.sha256,
                    name + ": a counter that restarts between two whole payloads loses nothing");
        }
    }

    /**
     * Whether a decode that lost {@code lost} frames somewhere has its last part where the whole song has it that
     * many frames later: the best match of its 0.25 s before the last 0.05 s, searched over a shift of 0 to 8192
     * frames, is at exactly {@code lost}, with a correlation above 0.95. (The noise that AAC's noise substitution
     * draws differs once frames are lost, so samples are not compared one by one.)
     */
    private static boolean inStep(Decoded d, Decoded whole, long lost) {
        int window = d.rate / 4;
        int from = (int) d.frames - d.rate / 20 - window;
        if (from < 0) {
            return false;
        }
        double best = -1;
        int bestShift = -1;
        for (int shift = 0; shift <= 8192 && from + shift + window <= whole.frames; shift++) {
            double xy = 0;
            double xx = 0;
            double yy = 0;
            for (int i = from; i < from + window; i++) {
                double x = 0;
                double y = 0;
                for (int c = 0; c < d.channels; c++) {
                    x += d.pcm[c][i];
                    y += whole.pcm[c][i + shift];
                }
                xy += x * y;
                xx += x * x;
                yy += y * y;
            }
            double r = xy / Math.sqrt(xx * yy + 1e-30);
            if (r > best) {
                best = r;
                bestShift = shift;
            }
        }
        return bestShift == lost && best > 0.95;
    }

    /**
     * A transport stream of one audio stream (PID 0x100, the given stream type) whose PES packets carry
     * {@code pesBytes} bytes of {@code es} each, cut wherever that falls: a program table, then the PES packets in
     * 188-byte packets with their continuity counters, the last of each padded with an adaptation field.
     */
    private static byte[] tsMux(byte[] es, int streamType, int pesBytes) {
        List<byte[]> payloads = new ArrayList<>();
        for (int at = 0; at < es.length; at += pesBytes) {
            payloads.add(Arrays.copyOfRange(es, at, Math.min(es.length, at + pesBytes)));
        }
        return tsMux(payloads, streamType, new byte[0], 0xC0);
    }

    /**
     * A transport stream of one audio stream (PID 0x100, the given stream type and ES descriptors, PES packets of the
     * given stream id), one PES packet for each of {@code payloads}.
     */
    private static byte[] tsMux(List<byte[]> payloads, int streamType, byte[] esInfo, int streamId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] pat = {0x00, (byte) 0xB0, 0x0D, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00, 0x00, 0x01, (byte) 0xF0, 0x00};
        int sectionLength = 0x12 + esInfo.length;
        byte[] pmt = concat(new byte[] {0x02, (byte) (0xB0 | sectionLength >> 8), (byte) sectionLength, 0x00, 0x01,
                (byte) 0xC1, 0x00, 0x00, (byte) 0xE1, 0x00, (byte) 0xF0, 0x00, (byte) streamType, (byte) 0xE1, 0x00,
                (byte) (0xF0 | esInfo.length >> 8), (byte) esInfo.length}, esInfo);
        out.writeBytes(psiPacket(0, pat));
        out.writeBytes(psiPacket(0x1000, pmt));
        int counter = 0;
        long pts = 90000;
        for (byte[] payload : payloads) {
            int n = payload.length;
            ByteArrayOutputStream pes = new ByteArrayOutputStream();
            pes.writeBytes(new byte[] {0, 0, 1, (byte) streamId, (byte) ((n + 8) >> 8), (byte) (n + 8), (byte) 0x80,
                    (byte) 0x80, 5, (byte) (0x21 | (pts >> 29 & 0x0E)), (byte) (pts >> 22), (byte) (pts >> 14 | 1),
                    (byte) (pts >> 7), (byte) (pts << 1 | 1)});
            pes.writeBytes(payload);
            pts += 1800;
            byte[] body = pes.toByteArray();
            for (int p = 0; p < body.length; p += 184) {
                int k = Math.min(184, body.length - p);
                byte[] packet = new byte[188];
                packet[0] = 0x47;
                packet[1] = (byte) ((p == 0 ? 0x40 : 0) | 0x01);
                packet[2] = 0x00;
                if (k < 184) {
                    // An adaptation field of stuffing in front of the last bytes.
                    int stuffing = 184 - k;
                    packet[3] = (byte) (0x30 | counter);
                    packet[4] = (byte) (stuffing - 1);
                    if (stuffing > 1) {
                        packet[5] = 0;
                        Arrays.fill(packet, 6, 4 + stuffing, (byte) 0xFF);
                    }
                    System.arraycopy(body, p, packet, 4 + stuffing, k);
                } else {
                    packet[3] = (byte) (0x10 | counter);
                    System.arraycopy(body, p, packet, 4, k);
                }
                counter = (counter + 1) & 0x0F;
                out.writeBytes(packet);
            }
        }
        return out.toByteArray();
    }

    /** One PSI section with its CRC-32 (the MPEG polynomial), in one packet of its PID. */
    private static byte[] psiPacket(int pid, byte[] section) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xFF);
        packet[0] = 0x47;
        packet[1] = (byte) (0x40 | pid >> 8);
        packet[2] = (byte) pid;
        packet[3] = 0x10;
        packet[4] = 0;
        System.arraycopy(section, 0, packet, 5, section.length);
        int crc = 0xFFFFFFFF;
        for (byte b : section) {
            crc ^= (b & 0xFF) << 24;
            for (int i = 0; i < 8; i++) {
                crc = crc < 0 ? crc << 1 ^ 0x04C11DB7 : crc << 1;
            }
        }
        for (int i = 0; i < 4; i++) {
            packet[5 + section.length + i] = (byte) (crc >>> (24 - 8 * i));
        }
        return packet;
    }

    /** The payload of {@code count} ADTS frames from frame {@code from} on, every byte after the header set to v. */
    private static byte[] garble(byte[] adts, int from, int count, int v) {
        byte[] b = adts.clone();
        for (int at = 0, k = 0; at + 7 <= b.length; k++) {
            int length = AdtsDecoder.frameLength(b, at);
            if (k >= from && k < from + count) {
                Arrays.fill(b, at + 7, Math.min(b.length, at + length), (byte) v);
            }
            at += length;
        }
        return b;
    }

    /** Frames decoded from ADTS bytes, strictly or tolerantly, or -1 when the decoder refuses them. */
    private static long adtsFrames(byte[] adts, boolean tolerant) throws IOException {
        try (PcmStream s = tolerant ? AdtsDecoder.openTolerant(new ByteArrayInputStream(adts))
                : AdtsDecoder.open(new ByteArrayInputStream(adts))) {
            return count(s);
        } catch (AudioFormatException e) {
            return -1;
        }
    }

    private static void sameAs(Path work, String name, byte[] bytes, Decoded whole, String what) throws IOException {
        Path file = write(work, name, bytes);
        equal(AudioDecoders.detect(file), AudioDecoders.Kind.TS, "a transport stream " + what + " is TS");
        equal(decode(file).sha256, whole.sha256, "and decodes to the same samples");
    }

    /** The packets of a 188-byte stream, each with {@code before} bytes in front and {@code after} bytes behind. */
    private static byte[] repack(byte[] ts, int before, int after) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int at = 0; at + 188 <= ts.length; at += 188) {
            out.writeBytes(new byte[before]);
            out.write(ts, at, 188);
            out.writeBytes(new byte[after]);
        }
        return out.toByteArray();
    }

    /** {@code count} null packets inserted after packet {@code after}. */
    private static byte[] withNulls(byte[] ts, int after, int count) {
        byte[] nullPacket = new byte[188];
        Arrays.fill(nullPacket, (byte) 0xFF);
        nullPacket[0] = 0x47;
        nullPacket[1] = 0x1F;
        nullPacket[2] = (byte) 0xFF;
        nullPacket[3] = 0x10;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ts, 0, after * 188);
        for (int i = 0; i < count; i++) {
            nullPacket[3] = (byte) (0x10 | (i & 0x0F));
            out.writeBytes(nullPacket);
        }
        out.write(ts, after * 188, ts.length - after * 188);
        return out.toByteArray();
    }

    private static int pid(byte[] ts, int at) {
        return (ts[at + 1] & 0x1F) << 8 | (ts[at + 2] & 0xFF);
    }

    /** The PID of the first stream of type 0x0F the program table lists. */
    private static int audioPid(byte[] ts) {
        for (int at = 0; at + 188 <= ts.length; at += 188) {
            int pid = pid(ts, at);
            if (pid != 0 && pid != 0x1FFF && pid != 0x11 && (ts[at + 1] & 0x40) != 0 && ts[at + 5] == 2) {
                int section = at + 5;
                int programInfo = (ts[section + 10] & 0x0F) << 8 | (ts[section + 11] & 0xFF);
                int p = section + 12 + programInfo;
                return (ts[p + 1] & 0x1F) << 8 | (ts[p + 2] & 0xFF);
            }
        }
        throw new IllegalStateException("no program table");
    }

    private static int firstPayloadPacket(byte[] ts, int pid, int skip) {
        for (int at = 0; at + 188 <= ts.length; at += 188) {
            if (pid(ts, at) == pid && (ts[at + 1] & 0x40) == 0 && skip-- <= 0) {
                return at;
            }
        }
        throw new IllegalStateException("no audio packet");
    }

    /** The stream with every program table's first stream type replaced. */
    private static byte[] withStreamType(byte[] ts, int type) {
        byte[] out = ts.clone();
        for (int at = 0; at + 188 <= out.length; at += 188) {
            int pid = pid(out, at);
            if (pid != 0 && pid != 0x1FFF && pid != 0x11 && (out[at + 1] & 0x40) != 0 && out[at + 5] == 2) {
                int section = at + 5;
                int programInfo = (out[section + 10] & 0x0F) << 8 | (out[section + 11] & 0xFF);
                out[section + 12 + programInfo] = (byte) type;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Matroska

    /**
     * mkvmerge's way of storing Vorbis and other small frames: several frames in one block with Xiph, EBML or
     * fixed-size lacing. The frames of mkv-vorbis.webm, re-muxed here in blocks of eight with each lacing, must decode
     * to exactly the samples of the unlaced file; and the CodecDelay and DiscardPadding of a track apply to every codec.
     */
    private static void matroska(Path work) throws Exception {
        System.out.println("matroska");
        vorbisCodecDelay(work);
        for (String name : new String[] {"mkv-vorbis.webm", "mkv-vorbis-direct.webm", "mkv-aac.mka", "mkv-mp3.mka"}) {
            Path file = fixture(name);
            Decoded plain = decode(file);
            WebmDecoder.Track track;
            List<byte[]> frames = new ArrayList<>();
            long delay;
            try (InputStream in = Files.newInputStream(file)) {
                WebmDecoder.Packets packets = new WebmDecoder.Packets(in);
                track = packets.readHeaders();
                delay = packets.codecDelayNs;
                while (packets.next()) {
                    frames.add(Arrays.copyOfRange(packets.data(), packets.offset(), packets.offset() + packets.length()));
                }
            }
            // The frames re-muxed one per block, without the original's DiscardPadding: the same samples, the last
            // packet's padding kept (less than one frame more).
            Decoded remuxed = decode(write(work, name + "-unlaced.mka", matroskaFile(track, delay, frames, 0, 0)));
            check(remuxed.frames >= plain.frames && remuxed.frames - plain.frames < 2048
                    && prefixEqual(remuxed, plain, (int) plain.frames), name + " re-muxed decodes the same samples");
            for (int lacing = 1; lacing <= 3; lacing++) {
                if (lacing == 2 && !sameSizes(frames)) {
                    continue;
                }
                byte[] mkv = matroskaFile(track, delay, frames, lacing, 0);
                Decoded laced = decode(write(work, name + "-laced" + lacing + ".mka", mkv));
                equal(laced.sha256, remuxed.sha256, name + " with " + lacingName(lacing) + " lacing decodes the same");
            }
            // DiscardPadding of 10 ms on the last block, written here: cut from the end, for any codec.
            byte[] padded = matroskaFile(track, delay, frames, 0, 10_000_000L);
            Decoded cut = decode(write(work, name + "-pad.mka", padded));
            equal(remuxed.frames - cut.frames, (long) Math.round(0.010 * plain.rate),
                    name + ": a block's DiscardPadding is cut from the end");
        }
        // A codec the mod does not read in Matroska is named, at detect().
        byte[] webm = Files.readAllBytes(fixture("mkv-aac.mka"));
        int at = new String(webm, StandardCharsets.ISO_8859_1).indexOf("A_AAC");
        byte[] ac3 = webm.clone();
        System.arraycopy("A_AC3".getBytes(StandardCharsets.US_ASCII), 0, ac3, at, 5);
        try {
            AudioDecoders.detect(write(work, "ac3.mka", ac3));
            fail("AC-3 in Matroska is accepted");
        } catch (UnsupportedFormatException expected) {
            equal(expected.formatName(), "WebM/MKV (AC-3)", "AC-3 in Matroska is named");
        }
    }

    /**
     * A Vorbis track's CodecDelay counts the first packet as (previous block + this block) / 4 samples, as ffmpeg's
     * decoder outputs it, while the specification outputs nothing for it; ffmpeg drops CodecDelay samples from its own
     * output. So on the specification's timeline only CodecDelay less that first count is dropped, and a shorter
     * CodecDelay leaves the rest of the first packet in front, here as silence. mkv-vorbis-direct.webm (long blocks of
     * 2048, so a first count of 1024, the CodecDelay its encoder wrote) is re-muxed with other delays; measured against
     * ffmpeg 9.0.1 on the same edits: 0 and 1024 give the same samples, 2048 is 1024 shorter, 500 is 524 longer.
     */
    private static void vorbisCodecDelay(Path work) throws Exception {
        Path file = fixture("mkv-vorbis-direct.webm");
        WebmDecoder.Track track;
        List<byte[]> frames = new ArrayList<>();
        long delay;
        try (InputStream in = Files.newInputStream(file)) {
            WebmDecoder.Packets packets = new WebmDecoder.Packets(in);
            track = packets.readHeaders();
            delay = packets.codecDelayNs;
            while (packets.next()) {
                frames.add(Arrays.copyOfRange(packets.data(), packets.offset(), packets.offset() + packets.length()));
            }
        }
        equal(Math.round(delay / 1e9 * 44100), 1024L, "mkv-vorbis-direct.webm carries ffmpeg's CodecDelay of 1024");
        Decoded asWritten = decode(write(work, "vd-1024.mka", matroskaFile(track, delay, frames, 0, 0)));
        Decoded none = decode(write(work, "vd-0.mka", matroskaFile(track, 0, frames, 0, 0)));
        equal(asWritten.sha256, none.sha256, "Vorbis: a CodecDelay of the first packet's count drops nothing more");
        Decoded longer = decode(write(work, "vd-2048.mka",
                matroskaFile(track, Math.round(2048 * 1e9 / 44100), frames, 0, 0)));
        equal(none.frames - longer.frames, 1024L, "Vorbis: a CodecDelay of 2048 drops 1024 more frames");
        check(sameChannel(none, 0, longer, 0, 1024) && sameChannel(none, 1, longer, 1, 1024),
                "Vorbis: what a longer CodecDelay leaves is the same samples, from frame 1024 on");
        Decoded shorter = decode(write(work, "vd-500.mka",
                matroskaFile(track, Math.round(500 * 1e9 / 44100), frames, 0, 0)));
        equal(shorter.frames - none.frames, 524L, "Vorbis: a CodecDelay of 500 leaves 524 frames in front");
        boolean silent = true;
        for (int c = 0; c < shorter.channels; c++) {
            for (int i = 0; i < 524; i++) {
                silent &= shorter.pcm[c][i] == 0f;
            }
        }
        check(silent && sameChannel(shorter, 0, none, 0, 524) && sameChannel(shorter, 1, none, 1, 524),
                "Vorbis: those 524 frames are silent and the song follows them unchanged");
        // libvorbis's block sizes of 256 and 2048, from the Vorbis decoder's own fixtures put into Matroska: a short
        // first block counts (256 + 256) / 4 = 128, and the long first block of a file cut from a longer one counts
        // (2048 + 2048) / 4 = 1024, its second window bit being set (ffmpeg's previous-window rule). Measured against
        // ffmpeg 9.0.1 on the same files with CodecDelays of 0, 100, 500, 1024 and 3000: the same lengths, lag 0.
        // The two last cases start the same file at a long block whose window bits differ (bits 1: previous long and
        // next short; bits 2: the other way round): ffmpeg counts the block named by the second bit, so 576 and then
        // 1024, which the same measurement showed (a CodecDelay of 3000 drops 2424 and 1976 frames of its output).
        Object[][] cases = {{"lv-stereo-44k-transients.ogg", 128, -1}, {"lv-cut-long-first.ogg", 1024, -1},
                {"lv-stereo-44k-transients.ogg", 576, 1}, {"lv-stereo-44k-transients.ogg", 1024, 2}};
        for (Object[] c : cases) {
            String name = (String) c[0];
            int first = (Integer) c[1];
            int bits = (Integer) c[2];
            List<byte[]> packets = new ArrayList<>();
            try (InputStream in = Files.newInputStream(root.resolve("tools/vorbis-decoder-support").resolve(name))) {
                OggPageReader ogg = new OggPageReader(in, false);
                ogg.chooseVorbisStream();
                while (ogg.nextPacket(true)) {
                    packets.add(Arrays.copyOf(ogg.packetData(), ogg.packetLength()));
                }
            }
            byte[] id = packets.get(0);
            ByteArrayOutputStream priv = new ByteArrayOutputStream();
            priv.write(2);
            for (int k = 0; k < 2; k++) {
                int size = packets.get(k).length;
                for (; size >= 255; size -= 255) {
                    priv.write(255);
                }
                priv.write(size);
            }
            for (int k = 0; k < 3; k++) {
                priv.writeBytes(packets.get(k));
            }
            int rate = (id[12] & 0xFF) | (id[13] & 0xFF) << 8 | (id[14] & 0xFF) << 16 | (id[15] & 0xFF) << 24;
            WebmDecoder.Track vorbis = new WebmDecoder.Track("A_VORBIS", priv.toByteArray(), rate, id[11] & 0xFF, 0);
            List<byte[]> audio = packets.subList(3, packets.size());
            if (bits >= 0) {
                VorbisDecoderSetup setup = VorbisDecoderSetup.parse(id, id.length, packets.get(2), packets.get(2).length);
                int start = -1;
                for (int k = 0; k < audio.size() && start < 0; k++) {
                    byte[] q = audio.get(k);
                    int v = (q[0] & 0xFF) | (q.length > 1 ? (q[1] & 0xFF) << 8 : 0);
                    int mode = (v >> 1) & ((1 << setup.modeBits) - 1);
                    if (setup.modes[mode].blockflag && ((v >> (1 + setup.modeBits)) & 3) == bits) {
                        start = k;
                    }
                }
                check(start > 0, name + ": a long block with window bits " + bits + " to start from");
                audio = audio.subList(start, audio.size());
                name = name + " from a long block with window bits " + bits;
            }
            Decoded base = decode(write(work, "vorbis-delay-0.mka", matroskaFile(vorbis, 0, audio, 0, 0)));
            for (int delayFrames : new int[] {first, 500, 3000}) {
                Decoded d = decode(write(work, "vorbis-delay-" + delayFrames + ".mka",
                        matroskaFile(vorbis, Math.round(delayFrames * 1e9 / rate), audio, 0, 0)));
                long dropped = delayFrames - first;
                equal(base.frames - d.frames, dropped, name + " in Matroska: a CodecDelay of " + delayFrames + " drops "
                        + dropped + " frames on the specification's timeline");
                if (dropped >= 0) {
                    boolean same = true;
                    for (int ch = 0; ch < d.channels; ch++) {
                        same &= sameChannel(base, ch, d, ch, (int) dropped);
                    }
                    check(same, name + " in Matroska with a CodecDelay of " + delayFrames + ": the rest is unchanged");
                }
            }
        }
    }

    private static boolean sameSizes(List<byte[]> frames) {
        for (int i = 0; i + 8 <= frames.size(); i += 8) {
            for (int k = 1; k < 8; k++) {
                if (frames.get(i + k).length != frames.get(i).length) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String lacingName(int lacing) {
        return switch (lacing) {
            case 1 -> "Xiph";
            case 2 -> "fixed-size";
            case 3 -> "EBML";
            default -> "no";
        };
    }

    /**
     * A minimal Matroska file of one audio track: the EBML header, a Segment of unknown size, the track entry (codec,
     * CodecPrivate, CodecDelay, the audio settings), and clusters of SimpleBlocks of eight frames with the lacing asked
     * for (0 none), the last block a BlockGroup with DiscardPadding when it is not 0.
     */
    private static byte[] matroskaFile(WebmDecoder.Track track, long delay, List<byte[]> frames, int lacing,
                                       long discardNs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(element(0x1A45DFA3, concat(element(0x4282, "matroska".getBytes(StandardCharsets.US_ASCII)))));
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.writeBytes(element(0xD7, new byte[] {1}));
        entry.writeBytes(element(0x83, new byte[] {2}));
        entry.writeBytes(element(0x86, track.codec.getBytes(StandardCharsets.US_ASCII)));
        if (track.codecPrivate != null) {
            entry.writeBytes(element(0x63A2, track.codecPrivate));
        }
        if (delay > 0) {
            entry.writeBytes(element(0x56AA, ByteBuffer.allocate(8).putLong(delay).array()));
        }
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        audio.writeBytes(element(0xB5, ByteBuffer.allocate(8).putDouble(track.sampleRate).array()));
        audio.writeBytes(element(0x9F, new byte[] {(byte) track.channels}));
        entry.writeBytes(element(0xE1, audio.toByteArray()));
        byte[] tracks = element(0x1654AE6B, element(0xAE, entry.toByteArray()));
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.writeBytes(tracks);
        int per = lacing == 0 ? 1 : 8;
        for (int first = 0; first < frames.size(); first += 64) {
            ByteArrayOutputStream cluster = new ByteArrayOutputStream();
            cluster.writeBytes(element(0xE7, new byte[] {0}));
            for (int i = first; i < Math.min(frames.size(), first + 64); i += per) {
                List<byte[]> group = frames.subList(i, Math.min(frames.size(), i + per));
                boolean last = i + per >= frames.size();
                byte[] block = block(group, group.size() == 1 ? 0 : lacing);
                if (last && discardNs != 0) {
                    cluster.writeBytes(element(0xA0, concat(element(0xA1, block),
                            element(0x75A2, ByteBuffer.allocate(8).putLong(discardNs).array()))));
                } else {
                    cluster.writeBytes(element(0xA3, block));
                }
            }
            segment.writeBytes(element(0x1F43B675, cluster.toByteArray()));
        }
        byte[] body = segment.toByteArray();
        out.writeBytes(id(0x18538067));
        out.writeBytes(new byte[] {0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF});
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] block(List<byte[]> frames, int lacing) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x81);
        b.write(0);
        b.write(0);
        b.write(0x80 | lacing << 1);
        if (lacing != 0) {
            b.write(frames.size() - 1);
            if (lacing == 1) {
                for (int i = 0; i < frames.size() - 1; i++) {
                    int size = frames.get(i).length;
                    while (size >= 255) {
                        b.write(255);
                        size -= 255;
                    }
                    b.write(size);
                }
            } else if (lacing == 3) {
                b.writeBytes(vint(frames.get(0).length));
                for (int i = 1; i < frames.size() - 1; i++) {
                    long diff = frames.get(i).length - frames.get(i - 1).length;
                    // A signed difference: biased by 2^(7n-1) - 1 in an n-byte EBML integer; two bytes hold +-8191.
                    long biased = diff + (1L << 13) - 1;
                    b.write((int) (0x40 | (biased >> 8)));
                    b.write((int) (biased & 0xFF));
                }
            }
        }
        for (byte[] f : frames) {
            b.writeBytes(f);
        }
        return b.toByteArray();
    }

    private static byte[] element(int id, byte[] body) {
        return concat(id(id), vint(body.length), body);
    }

    private static byte[] id(int id) {
        int bytes = id >= 0x1000000 ? 4 : id >= 0x10000 ? 3 : id >= 0x100 ? 2 : 1;
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (id >> (8 * (bytes - 1 - i)));
        }
        return out;
    }

    /** A size as an EBML variable-length integer of 4 bytes (or 2 for small lacing sizes). */
    private static byte[] vint(long value) {
        if (value < 0x3FFF) {
            return new byte[] {(byte) (0x40 | (value >> 8)), (byte) value};
        }
        return new byte[] {(byte) (0x10 | (value >> 24)), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Opus in MP4

    /**
     * Ogg FLAC beyond the fixtures: the pages are checked as an Ogg Vorbis file's are, so a cut or a flipped byte is
     * damage, never a shorter or noisier song; a mapping version this does not know is named, not guessed at.
     */
    private static void oggFlac(Path work) throws Exception {
        System.out.println("ogg flac");
        byte[] oga = Files.readAllBytes(fixture("ogg-flac-stereo.oga"));
        byte[] cut = Arrays.copyOf(oga, oga.length - 700);
        try {
            decode(write(work, "ogg-flac-cut.oga", cut));
            fail("an Ogg FLAC file cut in the middle of a page decodes");
        } catch (AudioFormatException expected) {
            checks++;
        }
        byte[] flipped = oga.clone();
        flipped[oga.length / 2] ^= 0x40;
        try {
            decode(write(work, "ogg-flac-flipped.oga", flipped));
            fail("an Ogg FLAC page with a flipped byte decodes");
        } catch (AudioFormatException expected) {
            checks++;
        }
        // The first packet's major version is byte 5 of the first page's body (27 header bytes and one lacing value).
        byte[] future = oga.clone();
        int body = 27 + (future[26] & 0xFF);
        check(future[body] == 0x7F && future[body + 5] == 1, "the first Ogg FLAC packet is version 1");
        future[body + 5] = 2;
        fixCrc(future, 0);
        try {
            decode(write(work, "ogg-flac-v2.oga", future));
            fail("an Ogg FLAC mapping 2.0 decodes");
        } catch (UnsupportedFormatException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains("Ogg FLAC"), "and it is named");
        }
        // Behind an ID3v2 tag, as taggers leave them, it is still Ogg FLAC with the same samples.
        Decoded whole = decode(fixture("ogg-flac-stereo.oga"));
        Path tagged = write(work, "ogg-flac-tagged.oga", concat(id3v2(300), oga));
        equal(AudioDecoders.detect(tagged), AudioDecoders.Kind.OGG_FLAC, "Ogg FLAC behind an ID3 tag is Ogg FLAC");
        equal(decode(tagged).sha256, whole.sha256, "and decodes to the same samples");
    }

    /** Recomputes the checksum of the Ogg page at {@code offset} after a change to it. */
    private static void fixCrc(byte[] ogg, int offset) {
        int segments = ogg[offset + 26] & 0xFF;
        int length = 27 + segments;
        for (int i = 0; i < segments; i++) {
            length += ogg[offset + 27 + i] & 0xFF;
        }
        Arrays.fill(ogg, offset + 22, offset + 26, (byte) 0);
        int crc = OggPageReader.crc(0, ogg, offset, length);
        ogg[offset + 22] = (byte) crc;
        ogg[offset + 23] = (byte) (crc >>> 8);
        ogg[offset + 24] = (byte) (crc >>> 16);
        ogg[offset + 25] = (byte) (crc >>> 24);
    }

    private static void mp4Opus() throws Exception {
        System.out.println("opus in mp4");
        // The dOps box is OpusHead big-endian, version 0, without the magic.
        byte[] dops = {0, 2, 0x01, 0x38, 0, 0, (byte) 0xBB, (byte) 0x80, (byte) 0xFF, 0x00, 0};
        byte[] head = Mp4Decoder.opusHeadFromDops(dops);
        OpusHead parsed = OpusHead.parse(head, 0, head.length);
        equal(parsed.channels, 2, "dOps: channels");
        equal(parsed.preSkip, 312, "dOps: pre-skip, big-endian");
        equal(parsed.inputSampleRate, 48000L, "dOps: input rate");
        equal(parsed.outputGain, -256, "dOps: output gain, signed");
        try {
            Mp4Decoder.opusHeadFromDops(new byte[] {1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            fail("dOps version 1 is read");
        } catch (UnsupportedFormatException expected) {
            checks++;
        }
        // A fragmented Opus MP4 cut off part way keeps the packet the cut runs through, as ffmpeg keeps it (Opus
        // makes a frame of part of a packet): = ffmpeg 9.0.1 at these three cuts, lag 0, 136.8-137.2 dB.
        byte[] frag = Files.readAllBytes(fixture("mp4-opus-frag.mp4"));
        Decoded fragWhole = decode(fixture("mp4-opus-frag.mp4"));
        long[][] fragCuts = {{frag.length - 50, 39048}, {frag.length * 2 / 3, 26568}, {frag.length / 2 + 13, 18888}};
        Path cuts = Files.createTempDirectory("blocky-extras-mp4cut");
        try {
            for (long[] cut : fragCuts) {
                Decoded d = decode(write(cuts, "frag" + cut[0] + ".mp4", Arrays.copyOf(frag, (int) cut[0])));
                equal(d.frames, cut[1], "fragmented Opus MP4 cut at byte " + cut[0] + ": frames");
                check(prefixEqual(d, fragWhole, (int) d.frames - 960), "and the same samples as the whole song");
            }
        } finally {
            deleteTree(cuts);
        }
        // Surround channels in WAVE order: the WAVE position of each Vorbis-order channel and back.
        int[] wave = ChannelOrder.waveIndexOfVorbis(6);
        equal(Arrays.toString(wave), "[0, 2, 1, 4, 5, 3]", "5.1: L C R Ls Rs LFE land on L R C LFE Ls Rs");
        equal(ChannelOrder.waveIndexOfVorbis(2), null, "stereo is not reordered");
        // The channel order against each codec's own: the 5.1 Opus of the transport stream put into an Ogg Opus file
        // (pre-skip 312 from the start trim, the end trim as the last granule position) decodes in Vorbis order, and
        // the TS's decode is the same samples in WAVE order; the MP4 holds the same packets and decodes the same.
        Path temp = Files.createTempDirectory("blocky-extras-order");
        try {
            byte[] ogg = oggFromTsOpus(Files.readAllBytes(fixture("ts-opus51.ts")));
            Decoded vorbisOrder = decode(write(temp, "ts51.opus", ogg));
            Decoded tsWave = decode(fixture("ts-opus51.ts"));
            equal(tsWave.frames, vorbisOrder.frames, "Opus 5.1: the transport stream is as long as its Ogg Opus copy");
            int[] vorbisAt = {0, 2, 1, 5, 3, 4};
            for (int w = 0; w < 6; w++) {
                check(sameChannel(tsWave, w, vorbisOrder, vorbisAt[w], 0), "Opus 5.1 in TS: WAVE channel " + w
                        + " is Vorbis channel " + vorbisAt[w]);
            }
            equal(decode(fixture("mp4-opus51.mp4")).sha256, tsWave.sha256, "Opus 5.1 in MP4 decodes as in TS");
            // AAC 5.1 in Matroska comes out in Vorbis order: its frames put into ADTS decode in WAVE order.
            WebmDecoder.Track track;
            List<byte[]> frames = new ArrayList<>();
            try (InputStream in = Files.newInputStream(fixture("mkv-aac51.mka"))) {
                WebmDecoder.Packets packets = new WebmDecoder.Packets(in);
                track = packets.readHeaders();
                while (packets.next()) {
                    frames.add(Arrays.copyOfRange(packets.data(), packets.offset(), packets.offset() + packets.length()));
                }
            }
            ByteArrayOutputStream adts = new ByteArrayOutputStream();
            AacConfig config = AacConfig.parse(track.codecPrivate, 0, track.codecPrivate.length);
            for (byte[] f : frames) {
                int length = f.length + 7;
                adts.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xF1,
                        (byte) ((config.objectType - 1) << 6 | config.rateIndex << 2 | config.channelConfiguration >> 2),
                        (byte) ((config.channelConfiguration & 3) << 6 | length >> 11), (byte) (length >> 3),
                        (byte) ((length & 7) << 5 | 0x1F), (byte) 0xFC});
                adts.writeBytes(f);
            }
            Decoded waveOrder = decode(write(temp, "51.aac", adts.toByteArray()));
            Decoded mkvVorbis = decode(fixture("mkv-aac51.mka"));
            equal(waveOrder.frames - 1024, mkvVorbis.frames, "AAC 5.1 in Matroska: CodecDelay's 1024 frames dropped");
            for (int w = 0; w < 6; w++) {
                check(sameChannel(waveOrder, w, mkvVorbis, vorbisAt[w], 1024), "AAC 5.1 in Matroska: WAVE channel " + w
                        + " is handed out as Vorbis channel " + vorbisAt[w]);
            }
        } finally {
            deleteTree(temp);
        }
    }

    /** Whether a's channel ca, from frame {@code skip} on, is b's channel cb, sample for sample. */
    private static boolean sameChannel(Decoded a, int ca, Decoded b, int cb, int skip) {
        for (int i = 0; i < b.frames; i++) {
            if (Float.floatToRawIntBits(a.pcm[ca][i + skip]) != Float.floatToRawIntBits(b.pcm[cb][i])) {
                return false;
            }
        }
        return true;
    }

    /** The Opus access units of a transport stream as an Ogg Opus file. */
    private static byte[] oggFromTsOpus(byte[] ts) throws IOException {
        MpegTsDecoder.Demuxer demuxer = new MpegTsDecoder.Demuxer(new ByteArrayInputStream(ts));
        demuxer.findAudio();
        OpusPcmStream.Packets packets = demuxer.opusPackets();
        List<byte[]> units = new ArrayList<>();
        int startTrim = -1;
        int endTrim = 0;
        while (packets.next()) {
            if (startTrim < 0) {
                startTrim = packets.trimStart();
            }
            endTrim = packets.trimEnd();
            units.add(Arrays.copyOfRange(packets.data(), packets.offset(), packets.offset() + packets.length()));
        }
        byte[] head = demuxer.opusHead.clone();
        head[10] = (byte) startTrim;
        head[11] = (byte) (startTrim >> 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OggPageWriter writer = new OggPageWriter(out, 1234);
        writer.packet(head, 0, false);
        writer.flush();
        writer.packet(concat("OpusTags".getBytes(StandardCharsets.US_ASCII), new byte[8]), 0, false);
        writer.flush();
        long granule = 0;
        for (int i = 0; i < units.size(); i++) {
            byte[] u = units.get(i);
            granule += OpusPacketDecoder.packetSamples(u, 0, u.length);
            boolean last = i == units.size() - 1;
            writer.packet(u, last ? granule - endTrim : granule, last);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // WAV

    private static void wav(Path work) throws Exception {
        System.out.println("wav");
        // RIFX: the same file big-endian decodes to exactly the same samples, for every sample format.
        float[][] pcm = sine(2, 44100, 4410, 0.7);
        for (int bits : new int[] {8, 16, 24, 32}) {
            byte[] riff = wavBytes(pcm, 44100, bits, false, false);
            byte[] rifx = wavBytes(pcm, 44100, bits, false, true);
            equal(decode(write(work, "rifx" + bits + ".wav", rifx)).sha256,
                    decode(write(work, "riff" + bits + ".wav", riff)).sha256, "RIFX " + bits + "-bit = RIFF");
        }
        for (int bits : new int[] {32, 64}) {
            equal(decode(write(work, "rifxf" + bits + ".wav", wavBytes(pcm, 44100, bits, true, true))).sha256,
                    decode(write(work, "rifff" + bits + ".wav", wavBytes(pcm, 44100, bits, true, false))).sha256,
                    "RIFX float " + bits + " = RIFF");
        }
        // ADPCM cut short: a last block is decoded as far as its bytes go (whole bytes of nibbles for Microsoft,
        // whole four-byte groups for IMA), as ffmpeg decodes it.
        byte[] ima = Files.readAllBytes(fixture("wav-ima.wav"));
        Decoded imaWhole = decode(fixture("wav-ima.wav"));
        check(dataOffset(ima) + dataSize(ima) == ima.length, "the IMA fixture ends with its data chunk");
        for (int cut : new int[] {1, 3, 4, 100, 1023, 1500}) {
            Decoded d = decode(write(work, "ima-cut" + cut + ".wav", Arrays.copyOf(ima, ima.length - cut)));
            // Mono, 1024-byte blocks of 2041 samples: the four-byte header's sample, then eight per four bytes.
            int bytes = dataSize(ima) - cut;
            int rest = bytes % 1024;
            long expected = bytes / 1024 * 2041L + (rest >= 4 ? 1 + (rest - 4) / 4 * 8 : 0);
            equal(d.frames, expected, "IMA ADPCM cut " + cut + " bytes short");
            check(prefixEqual(d, imaWhole, (int) d.frames), "and its samples are the whole file's");
        }
        byte[] ms = Files.readAllBytes(fixture("wav-ms.wav"));
        Decoded msWhole = decode(fixture("wav-ms.wav"));
        for (int cut : new int[] {1, 2, 13, 400}) {
            Decoded d = decode(write(work, "ms-cut" + cut + ".wav", Arrays.copyOf(ms, ms.length - cut)));
            check(d.frames < msWhole.frames && d.frames > msWhole.frames - 1200 && prefixEqual(d, msWhole, (int) d.frames),
                    "MS ADPCM cut " + cut + " bytes short keeps the samples before the cut: " + d.frames);
        }
        // An unknown predictor number is damage, not silence.
        byte[] badPredictor = ms.clone();
        badPredictor[dataOffset(ms)] = 9;
        try {
            decode(write(work, "ms-bad.wav", badPredictor));
            fail("an MS ADPCM block with predictor 9 decodes");
        } catch (AudioFormatException expected) {
            checks++;
        }
    }

    private static boolean prefixEqual(Decoded a, Decoded b, int frames) {
        for (int c = 0; c < a.channels; c++) {
            for (int i = 0; i < frames; i++) {
                if (Float.floatToRawIntBits(a.pcm[c][i]) != Float.floatToRawIntBits(b.pcm[c][i])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int dataOffset(byte[] wav) {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12;
        while (p + 8 <= wav.length) {
            if (wav[p] == 'd' && wav[p + 1] == 'a' && wav[p + 2] == 't' && wav[p + 3] == 'a') {
                return p + 8;
            }
            p += 8 + b.getInt(p + 4) + (b.getInt(p + 4) & 1);
        }
        throw new IllegalStateException("no data chunk");
    }

    private static int dataSize(byte[] wav) {
        return ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(dataOffset(wav) - 4);
    }

    private static float[][] sine(int channels, int rate, int frames, double level) {
        float[][] pcm = new float[channels][frames];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < frames; i++) {
                pcm[c][i] = (float) (level * StrictMath.sin(2 * Math.PI * (440 + 110 * c) * i / rate));
            }
        }
        return pcm;
    }

    /** A WAV (or RIFX) of the samples: integers of {@code bits} bits (8 unsigned) or IEEE floats. */
    private static byte[] wavBytes(float[][] pcm, int rate, int bits, boolean floating, boolean big) {
        int channels = pcm.length;
        int frames = pcm[0].length;
        int bytes = bits / 8;
        ByteBuffer b = ByteBuffer.allocate(44 + frames * channels * bytes)
                .order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        b.put((big ? "RIFX" : "RIFF").getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * channels * bytes);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) (floating ? 3 : 1));
        b.putShort((short) channels).putInt(rate).putInt(rate * channels * bytes).putShort((short) (channels * bytes));
        b.putShort((short) bits).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * channels * bytes);
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                double v = pcm[c][i];
                if (floating) {
                    if (bits == 32) {
                        b.putFloat((float) v);
                    } else {
                        b.putDouble(v);
                    }
                } else if (bits == 8) {
                    b.put((byte) (Math.round(v * 127) + 128));
                } else if (bits == 16) {
                    b.putShort((short) Math.round(v * 32767));
                } else if (bits == 24) {
                    int s = (int) Math.round(v * 8388607);
                    if (big) {
                        b.put((byte) (s >> 16)).put((byte) (s >> 8)).put((byte) s);
                    } else {
                        b.put((byte) s).put((byte) (s >> 8)).put((byte) (s >> 16));
                    }
                } else {
                    b.putInt((int) Math.round(v * 2147483647.0));
                }
            }
        }
        return b.array();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MP3

    private static void mp3(Path work) throws Exception {
        System.out.println("mp3");
        // Free format is found by the frame search too, which detect() uses for a file with no tag.
        check(Mp3Decoder.findsFrames(Files.readAllBytes(fixture("mp3-free-notag.mp3"))),
                "the frame search finds free-format MP3");
        // A stream whose sample rate changes part way keeps the rate it starts with; the frames at the other rate are
        // resampled and take exactly their own time. The first part is untouched.
        byte[] a = Files.readAllBytes(root.resolve("tools/fixtures/mp3/notag.mp3"));
        byte[] lsf = Files.readAllBytes(root.resolve("tools/fixtures/mp3/mono-lsf.mp3"));
        // The second part as bare frames: its ID3 tag and its Xing frame taken off (MPEG-2 Layer III at 22.05 kHz:
        // 72 000 * kbit/s / rate bytes, plus padding), so that every frame counts the same alone and behind another.
        int tag = 10 + ((lsf[6] & 0x7F) << 21 | (lsf[7] & 0x7F) << 14 | (lsf[8] & 0x7F) << 7 | (lsf[9] & 0x7F));
        int[] kbps = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
        int xing = 72000 * kbps[(lsf[tag + 2] & 0xF0) >> 4] / 22050 + ((lsf[tag + 2] >> 1) & 1);
        check(new String(lsf, tag + 13, 4, StandardCharsets.ISO_8859_1).equals("Xing"), "mono-lsf.mp3 has a Xing frame");
        byte[] b = Arrays.copyOfRange(lsf, tag + xing, lsf.length);
        Decoded da = decode(write(work, "a.mp3", a));
        long bFrames;
        int bRate;
        try (PcmStream s = Mp3Decoder.openFrames(new ByteArrayInputStream(b))) {
            bRate = s.sampleRate();
            bFrames = count(s);
        }
        check(da.rate != bRate, "the two parts have different rates: " + da.rate + ", " + bRate);
        Decoded joined = decode(write(work, "joined.mp3", concat(a, b)));
        equal(joined.rate, da.rate, "the joined stream keeps the first rate");
        long expected = da.frames + Math.round(bFrames * (double) da.rate / bRate);
        check(Math.abs(joined.frames - expected) <= 1, "and the second part takes its own time: " + joined.frames
                + " frames, " + expected + " expected");
        check(prefixEqual(joined, da, (int) da.frames - 1152), "the first part is decoded as it is on its own");
        // Back and forth: three stretches.
        Decoded aba = decode(write(work, "aba.mp3", concat(a, b, a)));
        long expected3 = 2 * da.frames + Math.round(bFrames * (double) da.rate / bRate);
        check(Math.abs(aba.frames - expected3) <= 2, "a stream that changes rate twice: " + aba.frames + " / " + expected3);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The limiter on the 1.5x/2x copies

    private static void limiter() throws Exception {
        System.out.println("limiter");
        int rate = 48000;
        // Below the ceiling nothing changes, sample for sample.
        float[][] quiet = sine(2, rate, 48000, 0.8);
        float[][] out = drain(PeakLimiter.wrap(memory(quiet, rate)), 2);
        equal(out[0].length, quiet[0].length, "the limiter keeps the length");
        check(Arrays.equals(out[0], quiet[0]) && Arrays.equals(out[1], quiet[1]), "a song below the ceiling is untouched");
        // A loud master: never above the ceiling, the same length, the gain smooth, the channels kept in balance.
        float[][] loud = sine(2, rate, 96000, 1.6);
        Random r = new Random(3);
        for (int i = 0; i < loud[0].length; i++) {
            loud[0][i] = (float) Math.max(-1.3, Math.min(1.3, loud[0][i] + 0.2 * r.nextGaussian()));
        }
        out = drain(PeakLimiter.wrap(memory(loud, rate)), 2);
        equal(out[0].length, loud[0].length, "a limited song keeps its length");
        float peak = 0f;
        double maxStep = 0;
        // The song starts loud, so its very first frame is already turned down: the steps are measured from there.
        double previous = Double.NaN;
        for (int i = 0; i < out[0].length; i++) {
            peak = Math.max(peak, Math.max(Math.abs(out[0][i]), Math.abs(out[1][i])));
            if (Math.abs(loud[1][i]) > 0.05) {
                double gain = out[1][i] / loud[1][i];
                double gain0 = loud[0][i] != 0 ? out[0][i] / loud[0][i] : gain;
                check(Math.abs(gain - gain0) < 1e-5, "both channels get the same gain at " + i);
                if (!Double.isNaN(previous)) {
                    maxStep = Math.max(maxStep, Math.abs(gain - previous));
                }
                previous = gain;
            }
        }
        check(peak <= PeakLimiter.CEILING + 1e-6f, "no sample above the ceiling: " + peak);
        check(maxStep < 0.02, "the gain never jumps: largest step " + maxStep);
        // A single over: the gain is back to 1 within a few release times.
        float[][] click = new float[1][rate];
        click[0][rate / 2] = 1.5f;
        Arrays.fill(click[0], rate / 2 + 1, rate, 0.5f);
        out = drain(PeakLimiter.wrap(memory(click, rate)), 1);
        check(Math.abs(out[0][rate / 2] - PeakLimiter.CEILING) < 1e-6, "an over lands on the ceiling");
        check(Math.abs(out[0][rate - 1] - 0.5f) < 2.5e-4, "and the gain recovers (60 ms time constant): " + out[0][rate - 1]);
    }

    private static PcmStream memory(float[][] pcm, int rate) {
        return new PcmStream() {
            private int at;

            @Override
            public int sampleRate() {
                return rate;
            }

            @Override
            public int channels() {
                return pcm.length;
            }

            @Override
            public int read(float[][] out, int offset, int frames) {
                if (at >= pcm[0].length) {
                    return -1;
                }
                int n = Math.min(Math.min(frames, 777), pcm[0].length - at);
                for (int c = 0; c < pcm.length; c++) {
                    System.arraycopy(pcm[c], at, out[c], offset, n);
                }
                at += n;
                return n;
            }

            @Override
            public void close() {
            }
        };
    }

    private static float[][] drain(PcmStream s, int channels) throws IOException {
        float[][] all = new float[channels][1 << 16];
        float[][] buf = new float[channels][1000];
        int n = 0;
        int k;
        while ((k = s.read(buf, 0, 1000)) > 0) {
            for (int c = 0; c < channels; c++) {
                if (n + k > all[c].length) {
                    all[c] = Arrays.copyOf(all[c], all[c].length * 2);
                }
                System.arraycopy(buf[c], 0, all[c], n, k);
            }
            n += k;
        }
        for (int c = 0; c < channels; c++) {
            all[c] = Arrays.copyOf(all[c], n);
        }
        return all;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Granule positions (the server's length of a stream rip)

    private static void granules(Path work) throws Exception {
        System.out.println("granules");
        Path support = root.resolve("tools/vorbis-decoder-support");
        for (String agree : new String[] {"lv-stereo-44k-transients.ogg", "lv-mono-22k-sweep.ogg", "lv-6ch-48k.ogg",
                "ff-stereo-48k.ogg"}) {
            check(granulesMatch(Files.readAllBytes(support.resolve(agree))), agree + ": granule positions follow its packets");
        }
        check(!granulesMatch(Files.readAllBytes(support.resolve("lv-start-trim-1000.ogg"))),
                "a stream that starts 1000 samples in does not");
        check(!granulesMatch(Files.readAllBytes(support.resolve("lv-cut-long-first.ogg"))), "a cut stream does not");
        // A broadcast recording: every granule position 30 s later. And a "-c copy" cut: audio pages dropped from the
        // front, the positions left as they were.
        byte[] song = Files.readAllBytes(support.resolve("lv-stereo-44k-transients.ogg"));
        check(!granulesMatch(shiftGranules(song, 30L * 44100)), "a stream whose positions start 30 s in does not");
        check(!granulesMatch(dropAudioPages(song, 3)), "a stream with its first audio pages cut away does not");
        check(!granulesMatch(Arrays.copyOf(song, song.length / 2)), "a stream cut off part way does not");
    }

    private static boolean granulesMatch(byte[] ogg) throws IOException {
        return VorbisDecoder.granulesMatchPackets(new ByteArrayInputStream(ogg));
    }

    /** The pages of an Ogg stream: [offset, length] each. */
    private static List<int[]> pages(byte[] ogg) {
        List<int[]> pages = new ArrayList<>();
        int at = 0;
        while (at + 27 <= ogg.length) {
            int segments = ogg[at + 26] & 0xFF;
            int body = 0;
            for (int i = 0; i < segments; i++) {
                body += ogg[at + 27 + i] & 0xFF;
            }
            int length = 27 + segments + body;
            pages.add(new int[] {at, length});
            at += length;
        }
        return pages;
    }

    private static byte[] shiftGranules(byte[] ogg, long by) {
        byte[] out = ogg.clone();
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> pages = pages(out);
        for (int p = 2; p < pages.size(); p++) {
            int at = pages.get(p)[0];
            long g = b.getLong(at + 6);
            if (g >= 0) {
                b.putLong(at + 6, g + by);
            }
            recrc(out, at, pages.get(p)[1]);
        }
        return out;
    }

    private static byte[] dropAudioPages(byte[] ogg, int count) {
        List<int[]> pages = pages(ogg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int p = 0; p < pages.size(); p++) {
            if (p >= 2 && p < 2 + count) {
                continue;
            }
            int[] page = pages.get(p);
            byte[] copy = Arrays.copyOfRange(ogg, page[0], page[0] + page[1]);
            // Pages renumbered and a page that continues a dropped packet left as it is: like a cut by a muxer that
            // keeps whole pages.
            ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(18, p < 2 ? p : p - count);
            recrc(copy, 0, copy.length);
            out.writeBytes(copy);
        }
        return out.toByteArray();
    }

    private static void recrc(byte[] b, int at, int length) {
        for (int i = 22; i < 26; i++) {
            b[at + i] = 0;
        }
        byte[] page = Arrays.copyOfRange(b, at, at + length);
        int crc = OggPageWriter.crc(page);
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(at + 22, crc);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fuzz: damaged copies of every fixture end in a decode or an AudioFormatException

    private static void fuzz(Path work) throws Exception {
        System.out.println("fuzz");
        Random random = new Random(1031);
        int ok = 0;
        int damaged = 0;
        int unsupported = 0;
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fuzz");
            t.setDaemon(true);
            return t;
        });
        try {
            for (Expected e : FIXTURES) {
                byte[] original = Files.readAllBytes(fixture(e.file()));
                for (int m = 0; m < 40; m++) {
                    byte[] mutant = mutate(original, random);
                    Path file = write(work, "fuzz-" + m + "-" + e.file(), mutant);
                    Future<String> result = pool.submit(() -> fuzzOne(file));
                    String verdict;
                    try {
                        verdict = result.get(20, TimeUnit.SECONDS);
                    } catch (TimeoutException hang) {
                        result.cancel(true);
                        fail(e.file() + " mutant " + m + " hangs");
                        continue;
                    } catch (java.util.concurrent.ExecutionException crash) {
                        fail(e.file() + " mutant " + m + " throws " + crash.getCause());
                        continue;
                    }
                    switch (verdict) {
                        case "ok" -> ok++;
                        case "damaged" -> damaged++;
                        case "unsupported" -> unsupported++;
                        default -> fail(e.file() + " mutant " + m + ": " + verdict);
                    }
                    Files.deleteIfExists(file);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.printf(Locale.ROOT, "  %d mutants: %d decode, %d damaged, %d unsupported%n", ok + damaged + unsupported,
                ok, damaged, unsupported);
        checks++;
    }

    private static String fuzzOne(Path file) throws IOException {
        try {
            AudioDecoders.Kind kind = AudioDecoders.detect(file);
            try (PcmStream s = AudioDecoders.open(file, kind)) {
                float[][] buf = new float[Math.max(1, s.channels())][4096];
                long total = 0;
                int n;
                while ((n = s.read(buf, 0, 4096)) >= 0) {
                    if (n == 0) {
                        return "read returned 0";
                    }
                    for (int c = 0; c < s.channels(); c++) {
                        for (int i = 0; i < n; i++) {
                            if (!Float.isFinite(buf[c][i])) {
                                return "a sample that is not finite";
                            }
                        }
                    }
                    total += n;
                    if (total > 48000L * 3600) {
                        return "endless output";
                    }
                }
            }
            return "ok";
        } catch (UnsupportedFormatException unsupported) {
            return "unsupported";
        } catch (AudioFormatException damaged) {
            return "damaged";
        } catch (IOException | RuntimeException | OutOfMemoryError other) {
            return "unexpected " + other;
        }
    }

    private static byte[] mutate(byte[] original, Random r) {
        byte[] b = original.clone();
        switch (r.nextInt(5)) {
            case 0 -> {
                for (int i = 0, n = 1 + r.nextInt(8); i < n; i++) {
                    int at = r.nextInt(b.length);
                    b[at] ^= (byte) (1 << r.nextInt(8));
                }
            }
            case 1 -> b = Arrays.copyOf(b, r.nextInt(b.length));
            case 2 -> {
                int at = r.nextInt(b.length);
                byte[] junk = new byte[1 + r.nextInt(600)];
                r.nextBytes(junk);
                b = concat(Arrays.copyOf(b, at), junk, Arrays.copyOfRange(b, at, b.length));
            }
            case 3 -> {
                for (int i = 0, n = 1 + r.nextInt(4); i < n; i++) {
                    int at = r.nextInt(Math.max(1, b.length - 4));
                    int v = r.nextBoolean() ? 0xFFFFFFFF : r.nextInt();
                    for (int k = 0; k < 4 && at + k < b.length; k++) {
                        b[at + k] = (byte) (v >> (8 * k));
                    }
                }
            }
            default -> {
                int from = r.nextInt(b.length);
                int to = Math.min(b.length, from + 1 + r.nextInt(4096));
                int at = r.nextInt(b.length);
                b = concat(Arrays.copyOf(b, at), Arrays.copyOfRange(b, from, to), Arrays.copyOfRange(b, at, b.length));
            }
        }
        return b;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Interrupts, speed and memory

    private static void interrupt(Path work) throws Exception {
        System.out.println("interrupt");
        Path file = write(work, "long.ts", repeat(Files.readAllBytes(fixture("ts-hls-aac.m4a")), 40));
        try (PcmStream s = AudioDecoders.open(file)) {
            float[][] buf = new float[2][4096];
            check(s.read(buf, 0, 4096) > 0, "a long transport stream reads");
            Thread.currentThread().interrupt();
            while (s.read(buf, 0, 4096) > 0) {
                // reads until the interrupt is noticed
            }
            fail("an interrupted decode of a transport stream runs to its end");
        } catch (InterruptedIOException expected) {
            checks++;
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Four minutes of stereo in the formats whose container work is new here, decoded within 1.5 s (a warm decode,
     * the best of three; a cold one is judged only while the machine is not busy, as in the other decoder checks), and
     * fifteen minutes of a transport stream decoded with under 32 MB of extra heap.
     */
    private static void speedAndMemory(Path work) throws Exception {
        System.out.println("speed and memory");
        byte[] ts = Files.readAllBytes(fixture("ts-hls-aac.m4a"));
        byte[] ima = Files.readAllBytes(fixture("wav-ima.wav"));
        List<Object[]> songs = new ArrayList<>();
        // Joined HLS segments are exactly a stream of repeated segments: 150 copies of 1.6 s.
        songs.add(new Object[] {"AAC in MPEG-TS", write(work, "speed.ts", repeat(ts, 150))});
        songs.add(new Object[] {"IMA ADPCM WAV (mono)", write(work, "speed.wav", repeatAdpcm(ima, 300))});
        songs.add(new Object[] {"MP3 in MPEG-TS", write(work, "speed-mp3.ts",
                repeat(Files.readAllBytes(fixture("ts-mp3.ts")), 146))});
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        int cores = Runtime.getRuntime().availableProcessors();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        boolean quiet = load < cores / 2.0;
        System.out.printf(Locale.ROOT, "  load average %.1f on %d cores: the cold limit is %s%n", load, cores,
                quiet ? "judged" : "not judged");
        for (int pass = 0; pass < 2; pass++) {
            for (Object[] song : songs) {
                double best = Double.MAX_VALUE;
                for (int run = 0; run < (pass == 0 ? 1 : 3) && best >= 1500; run++) {
                    Path file = (Path) song[1];
                    long cpu0 = threads.getCurrentThreadCpuTime();
                    long t0 = System.nanoTime();
                    long frames;
                    int rate;
                    try (PcmStream s = AudioDecoders.open(file)) {
                        rate = s.sampleRate();
                        frames = count(s);
                    }
                    double ms = (System.nanoTime() - t0) / 1e6;
                    long cpu = (threads.getCurrentThreadCpuTime() - cpu0) / 1_000_000;
                    best = Math.min(best, ms);
                    System.out.printf(Locale.ROOT, "  %s: %.0f s in %.0f ms (%d ms CPU), %s%n", song[0],
                            frames / (double) rate, ms, cpu, pass == 0 ? "cold" : "warm");
                    check(frames / (double) rate > 230, song[0] + " is about four minutes");
                    check(ms < 6000, song[0] + " decodes without stalling");
                }
                if (pass == 1 || quiet) {
                    check(best < 1500, song[0] + ": four minutes decode in under 1.5 s (" + Math.round(best) + " ms)");
                }
            }
        }
        // Memory: fifteen minutes of a transport stream, the extra heap sampled after collections every 30 s of audio.
        Path fifteen = write(work, "fifteen.ts", repeat(ts, 563));
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long base = rt.totalMemory() - rt.freeMemory();
        long peak = 0;
        try (PcmStream s = AudioDecoders.open(fifteen)) {
            float[][] buf = new float[2][4096];
            long frames = 0;
            long next = 0;
            int n;
            while ((n = s.read(buf, 0, 4096)) > 0) {
                frames += n;
                if (frames >= next) {
                    System.gc();
                    peak = Math.max(peak, rt.totalMemory() - rt.freeMemory() - base);
                    next += 30L * 44100;
                }
            }
            check(frames > 44100L * 890, "the fifteen-minute stream decodes whole: " + frames);
        }
        System.out.printf(Locale.ROOT, "  fifteen minutes of MPEG-TS: %.2f MB of extra heap at most%n", peak / 1048576.0);
        check(peak < 32L << 20, "a fifteen-minute transport stream needs under 32 MB of extra heap");
        Files.delete(fifteen);
    }

    private static byte[] repeat(byte[] b, int times) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length * times);
        for (int i = 0; i < times; i++) {
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    /** A WAV whose data chunk is the fixture's whole blocks repeated: every ADPCM block stands on its own. */
    private static byte[] repeatAdpcm(byte[] wav, int times) {
        int data = dataOffset(wav);
        int size = dataSize(wav) / 1024 * 1024;
        byte[] blocks = Arrays.copyOfRange(wav, data, data + size);
        byte[] header = Arrays.copyOf(wav, data);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(data - 4, size * times)
                .putInt(4, data - 8 + size * times);
        return concat(header, repeat(blocks, times));
    }

    private static long count(PcmStream s) throws IOException {
        float[][] buf = new float[s.channels()][8192];
        long frames = 0;
        int n;
        while ((n = s.read(buf, 0, 8192)) > 0) {
            frames += n;
        }
        return frames;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

    private static final class Decoded {
        int rate;
        int channels;
        long frames;
        long hint;
        float[][] pcm;
        String sha256;
        boolean finite = true;
    }

    /** Decodes a file through AudioDecoders, reading in awkward sizes, and hashes the interleaved float bits. */
    private static Decoded decode(Path file) throws IOException {
        return decode(AudioDecoders.open(file));
    }

    /** {@link #decode(Path)} of a stream already open; it is closed. */
    private static Decoded decode(PcmStream opened) throws IOException {
        Decoded d = new Decoded();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (PcmStream s = opened) {
            d.rate = s.sampleRate();
            d.channels = s.channels();
            d.hint = s.frameCountHint();
            d.pcm = new float[d.channels][1 << 14];
            int[] sizes = {4096, 1, 7, 1000, 333, 65536};
            float[][] buf = new float[d.channels][65536 + 3];
            ByteBuffer bytes = ByteBuffer.allocate((65536 + 3) * d.channels * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int call = 0; ; call++) {
                int n = s.read(buf, 3, sizes[call % sizes.length]);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    throw new IllegalStateException("read returned 0 for a non-empty request");
                }
                if (d.frames + n > d.pcm[0].length) {
                    for (int c = 0; c < d.channels; c++) {
                        d.pcm[c] = Arrays.copyOf(d.pcm[c], (int) Math.max(d.frames + n, d.pcm[c].length * 2L));
                    }
                }
                bytes.clear();
                for (int i = 0; i < n; i++) {
                    for (int c = 0; c < d.channels; c++) {
                        float v = buf[c][3 + i];
                        d.pcm[c][(int) d.frames + i] = v;
                        d.finite &= Float.isFinite(v);
                        bytes.putFloat(v);
                    }
                }
                sha.update(bytes.array(), 0, bytes.position());
                d.frames += n;
            }
            if (s.read(buf, 0, 10) != -1) {
                throw new IllegalStateException("read after the end did not stay at the end");
            }
        }
        d.sha256 = HexFormat.of().formatHex(sha.digest());
        return d;
    }

    private static Path write(Path folder, String name, byte[] bytes) throws IOException {
        Path file = folder.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] id3v2(int size) {
        byte[] tag = new byte[size];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 4;
        int body = size - 10;
        tag[6] = (byte) ((body >> 21) & 0x7F);
        tag[7] = (byte) ((body >> 14) & 0x7F);
        tag[8] = (byte) ((body >> 7) & 0x7F);
        tag[9] = (byte) (body & 0x7F);
        return tag;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            fail(label);
        }
        checks++;
    }

    private static void fail(String label) {
        throw new AssertionError("FAIL: " + label);
    }

    private static void equal(Object actual, Object expected, String label) {
        if (!java.util.Objects.equals(actual, expected)) {
            fail(label + ": expected " + expected + ", got " + actual);
        }
        checks++;
    }
}
