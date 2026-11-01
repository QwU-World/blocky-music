package ru.gora.musicbattle.audio.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.SequenceInputStream;
import java.util.Arrays;

/**
 * The codecs other than Opus that WebM and Matroska files carry (.webm, .weba, .mka, .mkv), each handed to the
 * decoder this package already has for it, frame by frame as {@link WebmDecoder} finds them:
 * <ul>
 *   <li>Vorbis (older WebM files, and .mka/.mkv): the three headers of the CodecPrivate and the frames are put back
 *       into Ogg pages, with the granule positions an Ogg muxer writes, and read by {@link VorbisDecoder} on the
 *       specification's timeline. Every frame's samples are kept, as ffmpeg keeps them: Matroska has no granule
 *       position to cut the last packet with;</li>
 *   <li>AAC (CodecPrivate is the AudioSpecificConfig; the old codec IDs without one name the profile) by
 *       {@link AacDecoder}, with HE-AAC found from the first frame as in MP4;</li>
 *   <li>MP3 and MPEG Layer I/II as bare frames by {@link Mp3Decoder}; FLAC, whose CodecPrivate is the stream's
 *       "fLaC" header and metadata, by {@link FlacDecoder}; integer and float PCM by {@link WavDecoder}, behind a WAV
 *       header made from the track's audio settings.</li>
 * </ul>
 * The track's CodecDelay is dropped from the start, as ffmpeg drops it (its Matroska muxer writes the encoder delay
 * there for every codec); for Vorbis, less the first packet's samples, which the Vorbis decoder already leaves out
 * (see {@link VorbisTrack}). Blocks are decoded back to back, as for Opus. These codecs give their channels in WAVE
 * order, and Matroska is read as Vorbis order (the order of its Opus and Vorbis), so more than two channels are put
 * into Vorbis order here.
 */
final class MatroskaAudio {
    private MatroskaAudio() {
    }

    /** Whether a Matroska codec ID is one a decoder here reads. */
    static boolean reads(String codecId) {
        return switch (codecId) {
            case "A_OPUS", "A_VORBIS", "A_FLAC", "A_MPEG/L1", "A_MPEG/L2", "A_MPEG/L3", "A_PCM/INT/LIT",
                 "A_PCM/INT/BIG", "A_PCM/FLOAT/IEEE" -> true;
            default -> codecId.startsWith("A_AAC");
        };
    }

    static PcmStream open(WebmDecoder.Packets packets, WebmDecoder.Track track) throws IOException {
        PcmStream stream = switch (track.codec) {
            case "A_VORBIS" -> vorbis(packets, track);
            case "A_FLAC" -> {
                if (track.codecPrivate == null || track.codecPrivate.length < 4) {
                    throw new AudioFormatException("The Matroska FLAC track has no FLAC header.");
                }
                yield FlacDecoder.open(new SequenceInputStream(new ByteArrayInputStream(track.codecPrivate),
                        frames(packets)));
            }
            case "A_MPEG/L1", "A_MPEG/L2", "A_MPEG/L3" -> Mp3Decoder.openFrames(frames(packets));
            case "A_PCM/INT/LIT", "A_PCM/INT/BIG", "A_PCM/FLOAT/IEEE" -> pcm(packets, track);
            default -> new AacStream(packets, aacConfig(track));
        };
        long delay = packets.codecDelayNs;
        int rate = stream.sampleRate();
        if (delay > 0 && rate > 0) {
            long frames = nsToFrames(delay, rate);
            if (stream instanceof VorbisTrack vorbis) {
                // The CodecDelay of a Vorbis track counts the first packet's samples, which the Vorbis decoder has
                // already left out (see VorbisTrack), so only the rest of it is dropped here.
                frames -= vorbis.firstPacketFrames;
            }
            if (frames > 0) {
                stream = new SkipStart(stream, frames);
            } else if (frames < 0) {
                stream = new LeadSilence(stream, -frames);
            }
        }
        stream = new TrimEnd(stream, () -> nsToFrames(packets.discardEndNs, rate));
        return track.codec.equals("A_VORBIS") ? stream : ChannelOrder.toVorbisOrder(stream);
    }

    /** The frames of the track joined, as bytes. Closing it closes the file. */
    private static InputStream frames(WebmDecoder.Packets packets) {
        return new InputStream() {
            private boolean has;
            private int at;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                while (!has || at >= packets.length()) {
                    if (!packets.next()) {
                        return -1;
                    }
                    has = true;
                    at = 0;
                }
                int n = Math.min(len, packets.length() - at);
                System.arraycopy(packets.data(), packets.offset() + at, b, off, n);
                at += n;
                return n;
            }

            @Override
            public void close() throws IOException {
                packets.close();
            }
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Vorbis

    /**
     * Vorbis: the CodecPrivate holds the identification, comment and setup headers in Xiph lacing (a count less one,
     * then every size but the last as runs of 255 and a final byte). They and the frames are written into Ogg pages
     * as an Ogg muxer would, granule positions included, so the Ogg Vorbis decoder reads them unchanged.
     */
    private static PcmStream vorbis(WebmDecoder.Packets packets, WebmDecoder.Track track) throws IOException {
        byte[] p = track.codecPrivate;
        if (p == null || p.length < 3 || (p[0] & 0xFF) != 2) {
            throw new AudioFormatException("The Matroska Vorbis track has no Vorbis headers.");
        }
        int at = 1;
        int[] sizes = new int[3];
        for (int i = 0; i < 2; i++) {
            int size = 0;
            int b;
            do {
                if (at >= p.length) {
                    throw new AudioFormatException("The Matroska Vorbis headers are damaged.");
                }
                b = p[at++] & 0xFF;
                size += b;
            } while (b == 255);
            sizes[i] = size;
        }
        sizes[2] = p.length - at - sizes[0] - sizes[1];
        if (sizes[2] <= 0) {
            throw new AudioFormatException("The Matroska Vorbis headers are damaged.");
        }
        byte[] id = Arrays.copyOfRange(p, at, at + sizes[0]);
        byte[] comment = Arrays.copyOfRange(p, at + sizes[0], at + sizes[0] + sizes[1]);
        byte[] setupHeader = Arrays.copyOfRange(p, at + sizes[0] + sizes[1], p.length);
        VorbisDecoderSetup setup;
        try {
            setup = VorbisDecoderSetup.parse(id, id.length, setupHeader, setupHeader.length);
        } catch (RuntimeException e) {
            throw new AudioFormatException("The Matroska Vorbis headers are damaged.", e);
        }
        OggVorbisStream ogg = new OggVorbisStream(packets, setup, id, comment, setupHeader);
        int firstPacketFrames = ogg.prime();
        return new VorbisTrack(VorbisDecoder.open(ogg, VorbisDecoder.Timeline.SPECIFICATION), firstPacketFrames);
    }

    /**
     * A Matroska Vorbis track as {@link VorbisDecoder} reads it, with what its first audio packet would have given.
     * <p>Matroska's CodecDelay is in the track's own clock, where every packet, the first one too, stands for
     * (previous block + this block) / 4 samples. Only ffmpeg's own Vorbis encoder writes it, as half a long block
     * (1024 samples), and ffmpeg's decoder outputs that first packet and drops CodecDelay samples from what it outputs.
     * The specification, which VorbisDecoder follows, outputs nothing for the first packet: that count is already off
     * the start, so CodecDelay less that count is what is left to drop. A file without a CodecDelay starts where the
     * specification says on both, as ffmpeg's decoder then drops the first packet itself. Measured on ffmpeg 9.0.1 with
     * a CodecDelay of 500, 1023, 1024, 1025, 1100, 1500, 2048 and 4096 samples: always CodecDelay less 1024 frames
     * fewer than the specification's count, and with none, the same count.
     */
    static final class VorbisTrack implements PcmStream {
        private final PcmStream source;
        /**
         * The first audio packet's count, worked out as ffmpeg's decoder does: its block and, for a long block, the
         * block named by the second window bit as the previous one; a short block counts a short one before it.
         */
        final int firstPacketFrames;

        VorbisTrack(PcmStream source, int firstPacketFrames) {
            this.source = source;
            this.firstPacketFrames = firstPacketFrames;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public long frameCountHint() {
            return source.frameCountHint();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            return source.read(out, offset, frames);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    /** The Ogg pages of the headers and the Matroska frames, made as they are read. */
    private static final class OggVorbisStream extends InputStream {
        private final WebmDecoder.Packets packets;
        private final VorbisDecoderSetup setup;
        private final Sink sink = new Sink();
        private final OggPageWriter writer = new OggPageWriter(sink, 0x4D4B5642);
        private byte[] pending;
        private int pendingSize;
        private boolean started;
        private boolean ended;
        private long granule;
        private int previousBlock;
        private final byte[] id;
        private final byte[] comment;
        private final byte[] setupHeader;

        OggVorbisStream(WebmDecoder.Packets packets, VorbisDecoderSetup setup, byte[] id, byte[] comment,
                        byte[] setupHeader) {
            this.packets = packets;
            this.setup = setup;
            this.id = id;
            this.comment = comment;
            this.setupHeader = setupHeader;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (sink.available() == 0) {
                if (ended) {
                    return -1;
                }
                produce();
            }
            return sink.drain(b, off, len);
        }

        /** Writes the next page or pages into the sink. */
        private void produce() throws IOException {
            if (!started) {
                started = true;
                // The identification header alone on the first page, the other two on the next, and the first audio
                // packet on a fresh page, as the Vorbis I specification asks of an Ogg stream.
                writer.packet(id, 0, false);
                writer.flush();
                writer.packet(comment, 0, false);
                writer.packet(setupHeader, 0, false);
                writer.flush();
                prime();
                if (pending == null) {
                    // Headers and no audio: an empty song.
                    writer.packet(new byte[0], 0, true);
                    ended = true;
                }
                return;
            }
            // One packet is held back so the last one can carry the end-of-stream flag.
            byte[] next = nextFrame();
            int nextSize = lastSize;
            byte[] packet = pending;
            int size = pendingSize;
            granule += samplesOf(packet);
            writer.packet(size == packet.length ? packet : Arrays.copyOf(packet, size), granule, next == null);
            pending = next;
            pendingSize = nextSize;
            if (next == null) {
                ended = true;
            }
        }

        private int lastSize;
        private boolean primed;

        /**
         * Reads the first audio frame ahead (once) and returns what ffmpeg's decoder counts for it: (this block + the
         * previous one) / 4, where the previous one of a long first block is the one its second window bit names (ffmpeg
         * reads the two bits as one little-endian number and keeps the high bit) and that of a short one is short.
         * 0 when there is no audio frame or it is not one this setup reads.
         */
        int prime() throws IOException {
            if (!primed) {
                primed = true;
                pending = nextFrame();
                pendingSize = lastSize;
                firstPacketFrames = ffmpegFirstCount(pending, pendingSize);
            }
            return firstPacketFrames;
        }

        private int firstPacketFrames;

        private int ffmpegFirstCount(byte[] packet, int size) {
            if (packet == null || size == 0 || (packet[0] & 1) != 0) {
                return 0;
            }
            long bits = 0;
            for (int i = 0; i < Math.min(size, 3); i++) {
                bits |= (long) (packet[i] & 0xFF) << (8 * i);
            }
            int modeBits = setup.modeBits;
            int mode = (int) ((bits >> 1) & ((1 << modeBits) - 1));
            if (mode >= setup.modes.length) {
                return 0;
            }
            if (!setup.modes[mode].blockflag) {
                return (setup.blocksize0 + setup.blocksize0) / 4;
            }
            if (size * 8 < 1 + modeBits + 2) {
                return 0;
            }
            boolean nextLong = ((bits >> (1 + modeBits + 1)) & 1) != 0;
            return (setup.blocksize1 + (nextLong ? setup.blocksize1 : setup.blocksize0)) / 4;
        }

        private byte[] nextFrame() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Matroska Vorbis decoding interrupted");
            }
            while (packets.next()) {
                if (packets.length() == 0) {
                    continue;
                }
                lastSize = packets.length();
                return Arrays.copyOfRange(packets.data(), packets.offset(), packets.offset() + packets.length());
            }
            return null;
        }

        /**
         * The samples a packet completes, as an Ogg muxer counts them for the granule position: a quarter of the
         * previous block and a quarter of this one, nothing for the first block or a packet that is not audio.
         */
        private int samplesOf(byte[] packet) {
            if (packet.length == 0 || (packet[0] & 1) != 0) {
                return 0;
            }
            // The packet type bit, then the mode number in modeBits bits (at most 6), read from the low bit up.
            int bitsAvailable = (packet.length > 1 ? (packet[1] & 0xFF) << 8 : 0) | (packet[0] & 0xFF);
            if (packet.length == 1 && 1 + setup.modeBits > 8) {
                return 0;
            }
            int mode = (bitsAvailable >> 1) & ((1 << setup.modeBits) - 1);
            if (mode >= setup.modes.length) {
                return 0;
            }
            int block = setup.modes[mode].blockflag ? setup.blocksize1 : setup.blocksize0;
            int samples = previousBlock == 0 ? 0 : previousBlock / 4 + block / 4;
            previousBlock = block;
            return samples;
        }

        @Override
        public void close() throws IOException {
            packets.close();
        }
    }

    /** An OutputStream the page writer fills and the reader drains. */
    private static final class Sink extends java.io.OutputStream {
        private byte[] data = new byte[16384];
        private int start;
        private int end;

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (end + len > data.length) {
                System.arraycopy(data, start, data, 0, end - start);
                end -= start;
                start = 0;
                if (end + len > data.length) {
                    data = Arrays.copyOf(data, Math.max(data.length * 2, end + len));
                }
            }
            System.arraycopy(b, off, data, end, len);
            end += len;
        }

        int available() {
            return end - start;
        }

        int drain(byte[] b, int off, int len) {
            int n = Math.min(len, end - start);
            System.arraycopy(data, start, b, off, n);
            start += n;
            if (start == end) {
                start = end = 0;
            }
            return n;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AAC

    /**
     * The track's AudioSpecificConfig; for the old codec IDs that come without one (A_AAC/MPEG2/LC, A_AAC/MPEG4/LC,
     * with /SBR for HE-AAC), one is made from the profile they name and the track's rate and channels, as ffmpeg makes
     * it.
     */
    private static AacConfig aacConfig(WebmDecoder.Track track) throws AudioFormatException {
        byte[] p = track.codecPrivate;
        if (p != null && p.length >= 2) {
            return AacConfig.parse(p, 0, p.length);
        }
        String id = track.codec;
        int objectType;
        if (id.contains("/MAIN")) {
            throw new UnsupportedFormatException("AAC Main");
        } else if (id.contains("/SSR")) {
            throw new UnsupportedFormatException("AAC SSR");
        } else if (id.contains("/LTP")) {
            throw new UnsupportedFormatException("AAC LTP");
        } else if (id.contains("/LC") || id.equals("A_AAC")) {
            objectType = 2;
        } else {
            throw new UnsupportedFormatException("WebM/MKV (" + id + ")");
        }
        int rate = (int) Math.round(track.sampleRate);
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        boolean sbr = id.endsWith("/SBR");
        int core = sbr ? rate / 2 : rate;
        int index = -1;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == core) {
                index = i;
            }
        }
        if (index < 0 || track.channels < 1 || track.channels > 7) {
            throw new AudioFormatException("The Matroska AAC track has no usable configuration.");
        }
        int channels = track.channels == 7 ? 7 : track.channels;
        int bits = objectType << 11 | index << 7 | channels << 3;
        byte[] asc = {(byte) (bits >> 8), (byte) bits};
        AacConfig config = AacConfig.parse(asc, 0, 2);
        if (sbr) {
            config.sbr = 1;
        }
        return config;
    }

    /** Raw AAC frames, one per Matroska frame, as {@link Mp4Decoder} decodes the packets of an MP4 track. */
    private static final class AacStream implements PcmStream {
        private final WebmDecoder.Packets packets;
        private AacDecoder decoder;
        private final float[][] pcm;
        private int pcmAt;
        private int pcmEnd;
        private boolean ended;

        AacStream(WebmDecoder.Packets packets, AacConfig config) throws IOException {
            this.packets = packets;
            decoder = new AacDecoder(config, config.sbr == 1);
            if (!nextFrame()) {
                throw new AudioFormatException("The Matroska AAC track has no frames.");
            }
            float[][] first = new float[Math.max(2, decoder.outputChannels())][2048];
            decoder.decode(unit, 0, unitLength, first, 0);
            int frames = decoder.frameLength();
            if (config.sbr == -1 && decoder.sbrSeen() && config.sampleRate <= 24000) {
                // SBR found in the first frame of a stream whose configuration did not say: implicit HE-AAC, decoded
                // at twice the core rate, as ffmpeg decodes it after probing.
                config.sbr = 1;
                decoder = new AacDecoder(config, true);
                first = new float[Math.max(2, decoder.outputChannels())][2048];
                decoder.decode(unit, 0, unitLength, first, 0);
                frames = decoder.frameLength();
            }
            pcm = new float[decoder.outputChannels()][2048];
            for (int c = 0; c < pcm.length; c++) {
                System.arraycopy(first[c], 0, pcm[c], 0, frames);
            }
            pcmEnd = frames;
        }

        /** The current frame, copied with the zero padding the AAC bit reader may read past its end. */
        private byte[] unit = new byte[4096];
        private int unitLength;

        private boolean nextFrame() throws IOException {
            while (packets.next()) {
                int n = packets.length();
                if (n > 0) {
                    if (unit.length < n + AacBits.PADDING) {
                        unit = new byte[n + AacBits.PADDING];
                    }
                    System.arraycopy(packets.data(), packets.offset(), unit, 0, n);
                    Arrays.fill(unit, n, n + AacBits.PADDING, (byte) 0);
                    unitLength = n;
                    return true;
                }
            }
            return false;
        }

        @Override
        public int sampleRate() {
            return decoder.outputRate();
        }

        @Override
        public int channels() {
            return pcm.length;
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (frames <= 0) {
                return 0;
            }
            while (pcmAt >= pcmEnd) {
                if (ended) {
                    return -1;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Matroska AAC decoding interrupted");
                }
                if (!nextFrame()) {
                    ended = true;
                    return -1;
                }
                pcmAt = 0;
                // Every AAC frame decodes to a whole frame of samples (decode() returns the bytes it read).
                decoder.decode(unit, 0, unitLength, pcm, 0);
                pcmEnd = decoder.frameLength();
            }
            int n = Math.min(frames, pcmEnd - pcmAt);
            for (int c = 0; c < pcm.length; c++) {
                System.arraycopy(pcm[c], pcmAt, out[c], offset, n);
            }
            pcmAt += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            packets.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // PCM

    /**
     * PCM: a WAV header (RIFF, or RIFX for big-endian samples) for the track's channels, rate and bit depth, with a
     * data chunk "to the end of the stream", ahead of the frames.
     */
    private static PcmStream pcm(WebmDecoder.Packets packets, WebmDecoder.Track track) throws IOException {
        int channels = track.channels;
        int rate = (int) Math.round(track.sampleRate);
        int bits = track.bitDepth;
        boolean floating = track.codec.equals("A_PCM/FLOAT/IEEE");
        boolean big = track.codec.equals("A_PCM/INT/BIG");
        if (channels < 1 || rate <= 0 || (bits != 8 && bits != 16 && bits != 24 && bits != 32 && bits != 64)
                || (floating && bits != 32 && bits != 64) || (!floating && bits == 64)) {
            throw new AudioFormatException("The Matroska PCM track has no usable audio settings.");
        }
        int blockAlign = channels * bits / 8;
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.writeBytes((big ? "RIFX" : "RIFF").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        int32(h, 0xFFFFFFFF, big);
        h.writeBytes("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        int32(h, 16, big);
        int16(h, floating ? 3 : 1, big);
        int16(h, channels, big);
        int32(h, rate, big);
        int32(h, rate * blockAlign, big);
        int16(h, blockAlign, big);
        int16(h, bits, big);
        h.writeBytes("data".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        int32(h, 0xFFFFFFFF, big);
        return WavDecoder.open(new SequenceInputStream(new ByteArrayInputStream(h.toByteArray()), frames(packets)));
    }

    private static void int32(ByteArrayOutputStream out, int v, boolean big) {
        if (big) {
            out.write(v >>> 24);
            out.write(v >>> 16);
            out.write(v >>> 8);
            out.write(v);
        } else {
            out.write(v);
            out.write(v >>> 8);
            out.write(v >>> 16);
            out.write(v >>> 24);
        }
    }

    private static void int16(ByteArrayOutputStream out, int v, boolean big) {
        if (big) {
            out.write(v >>> 8);
            out.write(v);
        } else {
            out.write(v);
            out.write(v >>> 8);
        }
    }

    /** Nanoseconds as frames at {@code rate}, rounded to the nearest, as ffmpeg rescales them. */
    private static long nsToFrames(long ns, int rate) {
        if (ns <= 0 || rate <= 0) {
            return 0;
        }
        return Math.round(ns / 1e9 * rate);
    }

    /**
     * Cuts the DiscardPadding of the last blocks from the end. The padding is known only once the last block has been
     * read, so the last {@link #HOLD} frames are held back until the source ends; padding longer than that (no encoder
     * pads by more than a frame) is cut only as far as that.
     */
    private static final class TrimEnd implements PcmStream {
        private static final int HOLD = 16384;
        private final PcmStream source;
        private final java.util.function.LongSupplier padding;
        private final float[][] ring;
        private final float[][] scratch;
        private long written;
        private long handedOut;
        private long end = -1;

        TrimEnd(PcmStream source, java.util.function.LongSupplier padding) {
            this.source = source;
            this.padding = padding;
            this.ring = new float[source.channels()][2 * HOLD];
            this.scratch = new float[source.channels()][4096];
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (frames <= 0) {
                return 0;
            }
            while (true) {
                long limit = end >= 0 ? end : written - HOLD;
                if (handedOut < limit) {
                    int n = (int) Math.min(frames, limit - handedOut);
                    int at = (int) (handedOut % ring[0].length);
                    n = Math.min(n, ring[0].length - at);
                    for (int c = 0; c < ring.length; c++) {
                        System.arraycopy(ring[c], at, out[c], offset, n);
                    }
                    handedOut += n;
                    return n;
                }
                if (end >= 0) {
                    return -1;
                }
                int room = (int) Math.min(scratch[0].length, ring[0].length - (written - handedOut));
                int n = source.read(scratch, 0, room);
                if (n < 0) {
                    end = Math.max(handedOut, written - Math.min(HOLD, padding.getAsLong()));
                    continue;
                }
                for (int i = 0; i < n; ) {
                    int at = (int) ((written + i) % ring[0].length);
                    int k = Math.min(n - i, ring[0].length - at);
                    for (int c = 0; c < ring.length; c++) {
                        System.arraycopy(scratch[c], i, ring[c], at, k);
                    }
                    i += k;
                }
                written += n;
            }
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    /**
     * Puts silent frames in front of a stream: a Vorbis track whose CodecDelay is shorter than its first packet, where
     * ffmpeg plays the end of that first packet, which the specification never outputs. No muxer is known to write
     * such a file; the silence keeps the song on ffmpeg's timeline, which is what a beatmap made with it is timed to.
     */
    private static final class LeadSilence implements PcmStream {
        private final PcmStream source;
        private long lead;

        LeadSilence(PcmStream source, long lead) {
            this.source = source;
            this.lead = lead;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public long frameCountHint() {
            long hint = source.frameCountHint();
            return hint < 0 ? -1 : hint + lead;
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (lead > 0 && frames > 0) {
                int n = (int) Math.min(lead, frames);
                for (float[] channel : out) {
                    Arrays.fill(channel, offset, offset + n, 0f);
                }
                lead -= n;
                return n;
            }
            return source.read(out, offset, frames);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    /** Drops the first frames of a stream: the track's CodecDelay. */
    private static final class SkipStart implements PcmStream {
        private final PcmStream source;
        private long skip;
        private float[][] scratch;

        SkipStart(PcmStream source, long skip) {
            this.source = source;
            this.skip = skip;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public long frameCountHint() {
            long hint = source.frameCountHint();
            return hint < 0 ? -1 : Math.max(0, hint - skip);
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            while (skip > 0) {
                if (scratch == null) {
                    scratch = new float[source.channels()][4096];
                }
                int n = source.read(scratch, 0, (int) Math.min(skip, 4096));
                if (n < 0) {
                    skip = 0;
                    return -1;
                }
                skip -= n;
            }
            return source.read(out, offset, frames);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
