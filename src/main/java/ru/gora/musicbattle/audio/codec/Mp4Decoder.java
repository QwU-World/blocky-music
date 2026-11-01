package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * MP4, M4A and MOV audio in plain Java: AAC (LC, HE-AAC and HE-AAC v2), Apple Lossless and Opus, out of plain and
 * fragmented files, found by {@link Mp4Demuxer} and decoded by {@link AacDecoder}, {@link AlacDecoder} or the Opus
 * decoder ({@link OpusMultistreamDecoder}).
 *
 * <p>Two ways in. {@link #open(Path)} reads the file wherever the index points, so every layout works, including
 * the common one where the index (moov) comes after the audio (mdat). {@link #open(InputStream)} reads once from the
 * start, which works for files written for streaming (moov first) and for fragmented files, and refuses the
 * moov-at-the-end layout with an {@link AudioFormatException} that says to open the file by path.
 *
 * <p>The decoded samples line up with what {@code ffmpeg -i song.m4a} gives, start and length, because charts are
 * timed against it:
 * <ul>
 *   <li>An edit list (edts/elst) wins: its first non-empty edit's media time is skipped at the start (the encoder
 *       delay, 1024 for ffmpeg's encoder, 2112 for Apple's), and its duration ends the audio, which removes the
 *       padding of the last frame. A duration of 0, as fragmented files write it, means "to the end".</li>
 *   <li>Without an edit list, iTunes' iTunSMPB tag gives the priming to skip and the remainder to cut from the
 *       end, which is how afconvert and iTunes store gapless information.</li>
 *   <li>With neither, the last AAC frame is cut to the duration the sample table gives it.</li>
 * </ul>
 * All of them count in the track's timescale. For HE-AAC that is usually the core rate, half the output rate, so the
 * values are scaled to the output rate.
 *
 * <p>Opus in MP4 ("Encapsulation of Opus in ISO Base Media File Format", the 'Opus' sample entry and its 'dOps'
 * box) follows the same edit list: its media time is the pre-skip and its duration ends the song, as the
 * specification has the muxer write them (ffmpeg writes both). Without an edit list the pre-skip of the dOps box is
 * skipped. ffmpeg 9 skips the pre-skip but does not apply the edit's duration, so its decode keeps the last packet's
 * padding (48 648 frames for a one-second song where this gives 48 000, which the Ogg Opus file of the same packets
 * also gives). Surround Opus comes in Vorbis channel order and is handed out in WAVE order, like the AAC this
 * container usually carries.
 *
 * <p>HE-AAC has one more delay: its SBR filter banks put the output 962 samples (at the output rate) after where
 * the core's would be. ffmpeg leaves that delay in, and so does this decoder, so a song converted here lines up with
 * one ffmpeg converted. Apple's decoder takes it out together with the priming, so its decode of an Apple HE-AAC
 * file starts 962 samples (about 20 ms) earlier, on the encoder's input.
 */
public final class Mp4Decoder {
    /**
     * How much audio an edit list may skip and still be decoded from the first packet where it has to be (HE-AAC, and
     * fragmented files; see {@code Mp4Stream.startNearEdit}). A song's edit list skips the encoder's priming, a few
     * thousand samples; five minutes of HE-AAC decode in about a second on a machine that is not busy.
     */
    private static final long MAX_DECODED_SKIP_SECONDS = 300;

    private Mp4Decoder() {
    }

    /**
     * Whether the first bytes of a file are an ISO base media file: an 'ftyp' box first, or for older QuickTime files
     * one of the boxes that can start one ('moov', 'mdat', 'wide', 'free', 'skip') with a plausible size.
     */
    public static boolean looksLike(byte[] head) {
        if (head == null || head.length < 12) {
            return false;
        }
        long size = Mp4Demuxer.u32(head, 0);
        String type = Mp4Demuxer.fourcc((int) Mp4Demuxer.u32(head, 4));
        for (int i = 4; i < 8; i++) {
            if (head[i] < 0x20 || head[i] > 0x7E) {
                return false;
            }
        }
        if (type.equals("ftyp")) {
            return size >= 8;
        }
        if (type.equals("moov") || type.equals("mdat") || type.equals("wide") || type.equals("free")
                || type.equals("skip")) {
            return size == 1 || size >= 8;
        }
        return false;
    }

    /**
     * Opens an MP4 stream read once from the start. On success the returned stream owns {@code in} and closes it; on
     * failure {@code in} is left to the caller.
     *
     * @throws UnsupportedFormatException when the file holds no audio this decoder reads (no audio
     *     track, Opus, AC-3, AAC Main, ...), named so the player can be told
     * @throws AudioFormatException when it is damaged, or its index comes after the audio (open it by path instead)
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return new Mp4Stream(Mp4Demuxer.open(in));
    }

    /**
     * Opens an MP4 file with random access, which reads every layout. The returned stream owns the file.
     *
     * @throws UnsupportedFormatException when the file holds no audio this decoder reads
     * @throws AudioFormatException when it is damaged
     */
    public static PcmStream open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        SeekableByteChannel channel = Files.newByteChannel(file);
        try {
            return new Mp4Stream(Mp4Demuxer.open(channel));
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** One packet in, frames out: the codec behind an MP4 track. */
    private interface Codec {
        int channels();

        int sampleRate();

        int maxFrames();

        int decode(byte[] data, int length, float[][] out) throws IOException;

        /**
         * The samples a packet decodes to without decoding it, for passing packets by; -1 when every packet but the
         * last decodes to {@link #maxFrames()}.
         */
        default int packetFrames(byte[] data, int length) {
            return -1;
        }
    }

    /**
     * The OpusHead (RFC 7845 section 5.1) that a 'dOps' box stands for. The box holds the same fields in the same
     * order, big-endian where OpusHead is little-endian, with version 0 where OpusHead says 1, and no magic.
     */
    static byte[] opusHeadFromDops(byte[] dops) throws AudioFormatException {
        if (dops == null || dops.length < 11) {
            throw new AudioFormatException("The MP4 Opus track has no Opus specific box (dOps).");
        }
        if (dops[0] != 0) {
            throw new UnsupportedFormatException("Opus in MP4 (dOps version " + (dops[0] & 0xFF) + ")");
        }
        int family = dops[10] & 0xFF;
        int channels = dops[1] & 0xFF;
        int tableLength = family == 0 ? 0 : 2 + channels;
        if (dops.length < 11 + tableLength) {
            throw new AudioFormatException("The MP4 Opus channel mapping table is cut short.");
        }
        byte[] head = new byte[19 + tableLength];
        byte[] magic = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
        System.arraycopy(magic, 0, head, 0, 8);
        head[8] = 1;
        head[9] = dops[1];
        head[10] = dops[3];
        head[11] = dops[2];
        head[12] = dops[7];
        head[13] = dops[6];
        head[14] = dops[5];
        head[15] = dops[4];
        head[16] = dops[9];
        head[17] = dops[8];
        head[18] = dops[10];
        System.arraycopy(dops, 11, head, 19, tableLength);
        return head;
    }

    private static Codec opus(Mp4Demuxer.Track t) throws AudioFormatException {
        byte[] bytes = opusHeadFromDops(t.config);
        OpusHead head = OpusHead.parse(bytes, 0, bytes.length);
        OpusMultistreamDecoder decoder = head.newDecoder();
        int channels = head.channels;
        // Vorbis order in the stream, WAVE order out (see the class comment); null when they are the same.
        int[] wave = ChannelOrder.waveIndexOfVorbis(channels);
        float[] interleaved = new float[OpusPacketDecoder.MAX_FRAME_SIZE * channels];
        return new Codec() {
            @Override
            public int channels() {
                return channels;
            }

            @Override
            public int sampleRate() {
                return 48000;
            }

            @Override
            public int maxFrames() {
                return OpusPacketDecoder.MAX_FRAME_SIZE;
            }

            @Override
            public int decode(byte[] data, int length, float[][] out) throws IOException {
                int n;
                try {
                    n = decoder.decode(data, 0, length, interleaved, 0, OpusPacketDecoder.MAX_FRAME_SIZE);
                } catch (RuntimeException e) {
                    throw new AudioFormatException("The MP4 Opus audio is damaged.", e);
                }
                if (n < 0) {
                    throw new AudioFormatException("The MP4 Opus audio is damaged (Opus error " + n + ").");
                }
                for (int c = 0; c < channels; c++) {
                    float[] o = out[wave == null ? c : wave[c]];
                    for (int i = 0, j = c; i < n; i++, j += channels) {
                        o[i] = interleaved[j];
                    }
                }
                return n;
            }

            @Override
            public int packetFrames(byte[] data, int length) {
                int n = length == 0 ? 0 : OpusPacketDecoder.packetSamples(data, 0, length);
                return Math.max(0, n);
            }
        };
    }

    /**
     * The AudioSpecificConfig of an 'mp4a' track, or the reason it is not AAC: the esds names the codec by its
     * object type indication (ISO/IEC 14496-1, Table 5), and only MPEG-4 audio (0x40) and MPEG-2 AAC LC (0x67) are
     * AAC this decoder reads.
     */
    private static AacConfig aacConfig(Mp4Demuxer.Track t) throws AudioFormatException {
        switch (t.objectTypeIndication) {
            case 0x40, 0x67, -1 -> {
                if (t.config == null) {
                    throw new AudioFormatException("The MP4 AAC track has no AudioSpecificConfig");
                }
                return AacConfig.parse(t.config, 0, t.config.length);
            }
            case 0x66 -> throw new UnsupportedFormatException("AAC Main");
            case 0x68 -> throw new UnsupportedFormatException("AAC SSR");
            case 0x69, 0x6B -> throw new UnsupportedFormatException("MP3 in MP4");
            case 0xA5 -> throw new UnsupportedFormatException("AC-3");
            case 0xA6 -> throw new UnsupportedFormatException("E-AC-3");
            case 0xA9, 0xAA, 0xAB, 0xAC -> throw new UnsupportedFormatException("DTS");
            case 0xAD -> throw new UnsupportedFormatException("Opus");
            case 0xDD -> throw new UnsupportedFormatException("Vorbis in MP4");
            default -> throw new UnsupportedFormatException(String.format(
                    "MP4 audio (object type 0x%02X)", t.objectTypeIndication));
        }
    }

    /** Names the sample entries players meet that hold something other than AAC or ALAC. */
    private static UnsupportedFormatException unsupportedEntry(String format) {
        return new UnsupportedFormatException(switch (format) {
            case "Opus" -> "Opus";
            case "ac-3" -> "AC-3";
            case "ec-3" -> "E-AC-3";
            case "fLaC" -> "FLAC in MP4";
            case ".mp3" -> "MP3 in MP4";
            case "samr", "sawb" -> "AMR";
            case "lpcm", "sowt", "twos", "in24", "in32", "fl32", "fl64", "raw " -> "PCM in MOV";
            // FairPlay-protected iTunes purchases (.m4p) and encrypted streams: the audio cannot be read at all.
            case "drms" -> "DRM-protected AAC";
            case "enca" -> "encrypted audio";
            default -> "MP4 audio (" + format.replaceAll("[^ -~]", "?") + ")";
        });
    }

    private static Codec wrap(AacDecoder d) {
        return new Codec() {
            @Override
            public int channels() {
                return d.outputChannels();
            }

            @Override
            public int sampleRate() {
                return d.outputRate();
            }

            @Override
            public int maxFrames() {
                return d.frameLength();
            }

            @Override
            public int decode(byte[] data, int length, float[][] out) throws IOException {
                d.decode(data, 0, length, out, 0);
                return d.frameLength();
            }
        };
    }

    private static Codec wrap(AlacDecoder alac) {
        return new Codec() {
            @Override
            public int channels() {
                return alac.channels();
            }

            @Override
            public int sampleRate() {
                return alac.sampleRate;
            }

            @Override
            public int maxFrames() {
                return alac.frameLength;
            }

            @Override
            public int decode(byte[] data, int length, float[][] out) throws IOException {
                return alac.decode(data, 0, length, out, 0);
            }
        };
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static final class Mp4Stream implements PcmStream {
        private final Mp4Demuxer demuxer;
        private final Mp4Demuxer.PacketBuffer packet = new Mp4Demuxer.PacketBuffer();
        /** Replaced once, by a decoder that has seen nothing, when decoding starts near a late edit (see below). */
        private Codec codec;
        private final float[][] pcm;
        private int pcmAt;
        private int pcmEnd;
        private long toSkip;
        /** Samples still to hand out, or -1 for "to the end". */
        private long remaining;
        /** Whether {@link #remaining} came from sample tables that movie fragments may continue. */
        private boolean tentativeEnd;
        private final long hint;
        private boolean ended;
        private boolean closed;

        Mp4Stream(Mp4Demuxer demuxer) throws IOException {
            this.demuxer = demuxer;
            Mp4Demuxer.Track t = demuxer.track();
            Codec c;
            AacConfig config = null;
            AacDecoder aac = null;
            switch (t.format) {
                case "mp4a" -> {
                    config = aacConfig(t);
                    // Without SBR first: when the configuration does not say whether SBR is there, the first packet
                    // shows it, as it shows ffmpeg's decoder when it probes the stream.
                    aac = new AacDecoder(config, config.sbr == 1);
                    c = wrap(aac);
                }
                case "alac" -> c = wrap(new AlacDecoder(t.config));
                case "Opus" -> {
                    c = opus(t);
                    // A song cut off part way keeps its last packet, as ffmpeg keeps it: Opus makes a frame of
                    // whatever part of a packet there is.
                    demuxer.keepCutPacket(true);
                }
                default -> throw unsupportedEntry(t.format);
            }
            // The first packet is decoded now, so a file with no playable audio fails here.
            int length = demuxer.nextPacket(packet);
            if (length < 0) {
                throw new AudioFormatException("The MP4 audio track has no packets");
            }
            float[][] first = new float[Math.max(2, c.channels())][Math.max(2048, c.maxFrames())];
            int frames = c.decode(packet.data(), length, first);
            if (aac != null && config.sbr == -1 && aac.sbrSeen() && config.sampleRate <= 24000) {
                config.sbr = 1;
                c = wrap(new AacDecoder(config, true));
                first = new float[Math.max(2, c.channels())][2048];
                frames = c.decode(packet.data(), length, first);
            }
            this.codec = c;
            this.pcm = new float[c.channels()][Math.max(2048, c.maxFrames())];
            copy(first, frames);
            if (config != null) {
                aacFrame = c.maxFrames();
                aacCoreRate = config.sampleRate;
            }
            hint = setUpTrim(t);
            if (skipIsEdit) {
                if (t.format.equals("Opus")) {
                    startNearOpusEdit(t);
                } else {
                    startNearEdit(config, demuxer.hasFragments() && t.sampleCount == 0);
                }
            }
        }

        /**
         * Where decoding starts for an edit list that starts the song more than a second into its media. For a plain
         * file ffmpeg does not decode from the first packet then: it begins at the last packet that starts at least one
         * second (of media time) before the edit, with a decoder that has seen nothing, and drops what decodes before
         * the edit. Measured on ffmpeg 9 with AAC files whose noise bands (PNS) show where its decoder began, for edits
         * from exactly one second and one frame on; an edit within that distance, and any edit of a fragmented file
         * (empty_moov and DASH layouts, 5 s), it decodes from the first packet.
         *
         * <p>AAC LC and ALAC in a plain file start where ffmpeg does. Nothing of their state outlives one frame, so the
         * samples are those of a decode from the first packet (bit for bit on a file without noise bands), and the
         * noise bands are ffmpeg's. The packets before are passed over without being read or decoded.
         *
         * <p>HE-AAC and HE-AAC v2 are decoded from the first packet, because their SBR and parametric stereo data are
         * mostly coded as changes from the frame before: a decoder that starts a second early rebuilds them wrongly
         * for as long as the encoder keeps sending changes. On Apple HE-AAC files with an edit 5 s in and HE-AAC v2
         * 2.3 s in, Apple's own decoder equals this decode from the first packet (130 and 128 dB, beside its usual
         * 962-sample SBR offset), while ffmpeg's late start is 34 and 26 dB from it and logs "icc_mode 7 is reserved".
         * A fragmented file is decoded from the first packet too, as ffmpeg decodes it.
         *
         * <p>Past {@link #MAX_DECODED_SKIP_SECONDS}, which no song's edit list reaches, every stream starts a second
         * before the edit, so that a hostile edit list cannot make one read() decode a whole file of tiny packets only
         * to throw them away. Every AAC packet decodes to one frame, so packets and samples convert exactly; for ALAC
         * this counts full frames, which is what every packet but the last holds.
         */
        private void startNearEdit(AacConfig config, boolean fragmentedFromStart) throws IOException {
            long frames = codec.maxFrames();
            long oneSecond = codec.sampleRate();
            if (toSkip - oneSecond < frames) {
                return;
            }
            boolean fromFirstPacket = fragmentedFromStart || (config != null && config.sbr == 1);
            if (fromFirstPacket && toSkip <= MAX_DECODED_SKIP_SECONDS * oneSecond) {
                return;
            }
            long notDecoded = (toSkip - oneSecond) / frames;
            // The first packet was decoded to learn what the stream is; its samples go, and the packets after it up
            // to the new start are passed over.
            long passed = 1 + demuxer.skipPackets(notDecoded - 1);
            toSkip -= passed * frames;
            pcmAt = pcmEnd;
            if (config != null) {
                codec = wrap(new AacDecoder(config, config.sbr == 1));
            }
        }

        /**
         * The same for Opus, whose packets differ in length: up to {@link #MAX_DECODED_SKIP_SECONDS} it is decoded from
         * the first packet, which an Opus decoder needs anyway to settle (RFC 7845 asks for 80 ms of pre-roll at
         * least; a song's edit list skips only the pre-skip, a few hundred samples). Past that, packets are passed by
         * the durations their first bytes give, without decoding them, up to about a second before the edit, so that
         * the work stays in proportion to the file's bytes.
         */
        private void startNearOpusEdit(Mp4Demuxer.Track t) throws IOException {
            long oneSecond = codec.sampleRate();
            if (toSkip <= MAX_DECODED_SKIP_SECONDS * oneSecond) {
                return;
            }
            long passed = pcmEnd - pcmAt;
            pcmAt = pcmEnd;
            while (toSkip - passed > oneSecond + codec.maxFrames()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("MP4 decoding interrupted");
                }
                int length = demuxer.nextPacket(packet);
                if (length < 0) {
                    ended = true;
                    break;
                }
                passed += codec.packetFrames(packet.data(), length);
            }
            toSkip = Math.max(0, toSkip - passed);
            codec = opus(t);
        }

        private void copy(float[][] first, int frames) {
            for (int ch = 0; ch < pcm.length; ch++) {
                System.arraycopy(first[ch], 0, pcm[ch], 0, frames);
            }
            pcmAt = 0;
            pcmEnd = frames;
        }

        /** Output samples per AAC frame (1024, or 2048 with SBR), and the core rate; 0 for other codecs. */
        private int aacFrame;
        private int aacCoreRate;
        /** Whether {@link #toSkip} is an edit list's start, rather than the iTunes tag's priming (at most 16383). */
        private boolean skipIsEdit;

        /**
         * Works out how many decoded samples to skip at the start and how many to hand out after that, and returns
         * the frame count hint. The rules are ffmpeg 9's wherever it reads the file the way its format means, each
         * measured on files patched box by box. tools/NativeAacDecoderChecks.java builds every case and holds the
         * lengths ffmpeg gave, and names the cases where ffmpeg misreads a file and this follows the format instead.
         */
        private long setUpTrim(Mp4Demuxer.Track t) {
            long rate = codec.sampleRate();
            long mediaScale = t.mediaTimescale;
            // A fragmented movie only reveals its packets fragment by fragment, so its length is not known up front.
            boolean complete = !demuxer.hasFragments();
            long sttsTotal = complete ? t.sampleDurationTotal : -1;
            // What decoding produces: every AAC packet is a whole frame whatever the tables say, while an ALAC packet
            // decodes exactly the samples the time-to-sample table gives it.
            long decoded = !complete ? -1
                    : aacFrame > 0 ? t.sampleCount * aacFrame
                    : sttsTotal >= 0 ? rescale(sttsTotal, rate, mediaScale) : -1;
            long skip = 0;
            long length = -1;
            if (!t.edits.isEmpty()) {
                // The edit list is the container's own word on what to play, so it wins over the iTunes tag. ffmpeg
                // lets the tag's priming replace the edit's start when a file has both; the two agree in every file
                // an encoder writes.
                long[] first = t.edits.get(0);
                skip = rescale(first[0], rate, mediaScale);
                skipIsEdit = true;
                long duration = first[1];
                long mediaEnd = first[0] + rescale(duration, mediaScale, Math.max(1, t.movieTimescale));
                // Edits that continue exactly where the previous one ended are one stretch of audio. A later edit
                // that jumps elsewhere is not played: no encoder writes one for a song.
                for (int i = 1; i < t.edits.size() && duration > 0; i++) {
                    long[] e = t.edits.get(i);
                    if (e[0] != mediaEnd || e[1] <= 0) {
                        break;
                    }
                    duration += e[1];
                    mediaEnd += rescale(e[1], mediaScale, Math.max(1, t.movieTimescale));
                }
                // A duration of 0 means "to the end": fragmented files cannot know their length when the moov is
                // written. ffmpeg also ignores a non-zero duration in a fragmented file, which the format does not
                // allow it to; this applies it.
                if (duration > 0 && t.movieTimescale > 0) {
                    length = rescale(duration, rate, t.movieTimescale);
                    if (sttsTotal >= 0) {
                        // An edit cannot reach past the media: the track ends where its last sample does.
                        length = Math.min(length, Math.max(0, rescale(sttsTotal - first[0], rate, mediaScale)));
                    }
                }
            } else if (t.format.equals("Opus")) {
                // No edit list: the dOps pre-skip is the only word on where the song starts.
                skip = ((t.config[2] & 0xFF) << 8) | (t.config[3] & 0xFF);
            } else if (t.itunSmpb != null) {
                // iTunes' gapless tag, read the way ffmpeg reads it: the priming is skipped when it is plausible
                // for AAC (1 to 16383 samples; ALAC has no encoder delay, and ffmpeg skips nothing for it), and
                // the remainder is cut from the end unless the tag claims every sample is valid. Apple writes
                // priming + remainder + valid = the samples in the file, so this always keeps exactly the valid
                // samples of an Apple file. The tag is applied even when other tracks follow the audio track in
                // the moov, as Apple's own decoder does. ffmpeg gives the moov's tag to the last track it read and
                // so loses it then: on an Apple ringtone with two haptic tracks after the audio it decoded 92160
                // samples, and the 89341 Apple's decoder gives once those tracks were renamed away.
                long priming = t.itunSmpb[0];
                long remainder = t.itunSmpb[1];
                long valid = t.itunSmpb[2];
                if (aacFrame > 0 && priming > 0 && priming < 16384) {
                    skip = rescale(priming, rate, mediaScale);
                }
                if (decoded >= 0 && sttsTotal >= 0 && valid < sttsTotal) {
                    length = Math.max(0, decoded - skip - rescale(remainder, rate, mediaScale));
                }
            } else if (aacFrame > 0 && t.sampleCount > 0 && t.sampleDurationTotal > 0 && t.lastSampleDuration >= 0
                    && t.mediaDuration > 0) {
                // No edit list and no gapless tag: ffmpeg still trims the last AAC frame by what the tables say
                // is not part of the track, the frame's samples beyond the last sample's duration plus whatever
                // runs past the media header's duration, as long as that is less than one frame, and only when
                // the media header gives a duration (0 or "unknown" leaves every sample). Measured on ffmpeg 9
                // with an untouched file, mdhd durations shortened, lengthened, zeroed and unknown, and a screen
                // recording whose last packet ends 4 samples past its media duration.
                long frameUnits = Math.round(1024.0 * mediaScale / aacCoreRate);
                long pad = Math.max(0, frameUnits - t.lastSampleDuration)
                        + Math.max(0, t.sampleDurationTotal - t.mediaDuration);
                if (pad > 0 && pad < frameUnits) {
                    length = t.sampleCount * aacFrame - rescale(pad, rate, mediaScale);
                    // A movie with an mvex box may keep every sample in the moov or only the first ones; if
                    // fragments turn out to follow, the trimmed packet was not the last and the trim is dropped,
                    // as ffmpeg (which trims from the moov's tables only) leaves the fragments untrimmed.
                    tentativeEnd = demuxer.hasFragments();
                }
            }
            toSkip = skip;
            remaining = length;
            if (tentativeEnd) {
                return -1;
            }
            if (decoded >= 0) {
                long available = Math.max(0, decoded - skip);
                return length >= 0 ? Math.min(length, available) : available;
            }
            return length;
        }

        private static long rescale(long value, long to, long from) {
            if (from <= 0) {
                return value;
            }
            return Math.round((double) value * to / from);
        }

        @Override
        public int sampleRate() {
            return codec.sampleRate();
        }

        @Override
        public int channels() {
            return codec.channels();
        }

        @Override
        public long frameCountHint() {
            return hint;
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (frames <= 0) {
                return 0;
            }
            while (true) {
                if (tentativeEnd && remaining >= 0 && remaining <= pcmEnd - pcmAt) {
                    // The end falls in the packet at hand, which is the last one the tables list; but they may
                    // cover only the start of the movie.
                    tentativeEnd = false;
                    if (demuxer.fragmentsFollow()) {
                        remaining = -1;
                    }
                }
                if (remaining == 0) {
                    return -1;
                }
                if (pcmAt < pcmEnd) {
                    if (toSkip > 0) {
                        int drop = (int) Math.min(toSkip, pcmEnd - pcmAt);
                        pcmAt += drop;
                        toSkip -= drop;
                        continue;
                    }
                    int n = Math.min(frames, pcmEnd - pcmAt);
                    if (remaining > 0) {
                        n = (int) Math.min(n, remaining);
                        remaining -= n;
                    }
                    for (int c = 0; c < pcm.length; c++) {
                        System.arraycopy(pcm[c], pcmAt, out[c], offset, n);
                    }
                    pcmAt += n;
                    return n;
                }
                if (ended) {
                    return -1;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("MP4 decoding interrupted");
                }
                int length = demuxer.nextPacket(packet);
                if (length < 0) {
                    ended = true;
                    return -1;
                }
                pcmAt = 0;
                try {
                    pcmEnd = codec.decode(packet.data(), length, pcm);
                } catch (AudioFormatException e) {
                    if (!demuxer.lastPacketCut() || e instanceof UnsupportedFormatException) {
                        throw e;
                    }
                    // What is left of a last packet the end of the file cut short is no Opus packet: the song ends.
                    ended = true;
                    return -1;
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                demuxer.close();
            }
        }
    }
}
