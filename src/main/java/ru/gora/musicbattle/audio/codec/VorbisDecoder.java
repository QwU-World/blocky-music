package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Decodes Ogg Vorbis in plain Java, so the mod can read songs without ffmpeg: on a dedicated server, which has none of
 * Minecraft's client audio classes, and in the checks. (Minecraft itself decodes with JOrbis, which is LGPL and so
 * cannot be copied into this MIT mod.)
 *
 * <p>This is a port of stb_vorbis by Sean Barrett (public domain / MIT, version 1.22): the floor, residue, coupling,
 * transform and overlap steps follow it so closely that the output is bit-identical to LWJGL's STBVorbis on every test
 * file stb_vorbis decodes correctly. Where stb_vorbis departs from the Vorbis I specification, this follows the
 * specification and libvorbis instead: a type 2 residue shared by more than two channels (libvorbis's 5.1), blocks of
 * 64 and 128 samples, floor 1 with multiplier 3, residue type 0, codebooks with sequence_p, a floor cut off by the end
 * of its packet, and, on the specification's timeline, the samples that the first granule position says to drop from
 * the start of the stream. Floor type 0, which libvorbis stopped writing before its 1.0 release, is refused, as in
 * stb_vorbis.
 *
 * <p>Only the first logical Vorbis stream of a file is decoded; a chained file stops at the end of its first link.
 * The Ogg checksums are verified, so any damage to the file comes out as an {@link AudioFormatException}, never as a
 * click or a hole in the audio. (A file cut off part way through a page is the one exception on Minecraft's timeline:
 * it ends there, as the game plays it.)
 *
 * <p>Which samples count as the song is a separate choice, {@link Timeline}: by default the ones Minecraft plays, so a
 * copy made from a song lines up with the game's own playback of it, sample for sample.
 */
public final class VorbisDecoder implements PcmStream {
    /**
     * Where the decoded song starts and ends. On files that start at zero and end on their last granule position,
     * which is what libvorbis, ffmpeg and this mod's encoder write, both give the same samples; they part on files cut
     * from a longer one without re-encoding, files whose first block is a long one, and files cut off part way.
     */
    public enum Timeline {
        /**
         * The samples Minecraft plays: what its JOrbisAudioStream (JOrbis 0.0.17, as the game reads every Ogg song)
         * returns, as measured file by file against it. Nothing is dropped at the start, whatever the first granule
         * position says. When the first block is a long one, the song starts with a quarter of a long block less a
         * quarter of a short one (448 samples at the usual 256/2048) from that first block, which the specification
         * throws away. The end is the centre of the last block, pulled back to where the last granule position says
         * the stream ends counted from the first page that carried one (libvorbis's own rule, which JOrbis, a port of
         * libvorbis, keeps), and never past the end-of-stream page's granule position counted from the first sample
         * played. A file cut off in the middle of a page ends at its last whole page. Damage the game would stumble
         * over part way and then play the rest out of step (a bad checksum, a missing page, another stream's page, a
         * packet that is not audio) is refused, since a copy could not follow it.
         */
        MINECRAFT,
        /**
         * The Vorbis I specification, as libvorbis reads it: the samples the first granule position says to drop are
         * dropped, and the stream ends exactly at its last granule position. stb_vorbis decodes the same samples
         * except that it keeps the dropped ones. Any damage, a cut-off last page included, is an error.
         */
        SPECIFICATION
    }

    /** Range of the first floor value, indexed by the floor's multiplier minus one. */
    private static final int[] FLOOR1_RANGE = {256, 128, 86, 64};

    /** The floor curve's decibel steps as amplitudes, copied from the Vorbis I specification. */
    private static final float[] INVERSE_DB_TABLE = {
            1.0649863e-07f, 1.1341951e-07f, 1.2079015e-07f, 1.2863978e-07f,
            1.3699951e-07f, 1.4590251e-07f, 1.5538408e-07f, 1.6548181e-07f,
            1.7623575e-07f, 1.8768855e-07f, 1.9988561e-07f, 2.1287530e-07f,
            2.2670913e-07f, 2.4144197e-07f, 2.5713223e-07f, 2.7384213e-07f,
            2.9163793e-07f, 3.1059021e-07f, 3.3077411e-07f, 3.5226968e-07f,
            3.7516214e-07f, 3.9954229e-07f, 4.2550680e-07f, 4.5315863e-07f,
            4.8260743e-07f, 5.1396998e-07f, 5.4737065e-07f, 5.8294187e-07f,
            6.2082472e-07f, 6.6116941e-07f, 7.0413592e-07f, 7.4989464e-07f,
            7.9862701e-07f, 8.5052630e-07f, 9.0579828e-07f, 9.6466216e-07f,
            1.0273513e-06f, 1.0941144e-06f, 1.1652161e-06f, 1.2409384e-06f,
            1.3215816e-06f, 1.4074654e-06f, 1.4989305e-06f, 1.5963394e-06f,
            1.7000785e-06f, 1.8105592e-06f, 1.9282195e-06f, 2.0535261e-06f,
            2.1869758e-06f, 2.3290978e-06f, 2.4804557e-06f, 2.6416497e-06f,
            2.8133190e-06f, 2.9961443e-06f, 3.1908506e-06f, 3.3982101e-06f,
            3.6190449e-06f, 3.8542308e-06f, 4.1047004e-06f, 4.3714470e-06f,
            4.6555282e-06f, 4.9580707e-06f, 5.2802740e-06f, 5.6234160e-06f,
            5.9888572e-06f, 6.3780469e-06f, 6.7925283e-06f, 7.2339451e-06f,
            7.7040476e-06f, 8.2047000e-06f, 8.7378876e-06f, 9.3057248e-06f,
            9.9104632e-06f, 1.0554501e-05f, 1.1240392e-05f, 1.1970856e-05f,
            1.2748789e-05f, 1.3577278e-05f, 1.4459606e-05f, 1.5399272e-05f,
            1.6400004e-05f, 1.7465768e-05f, 1.8600792e-05f, 1.9809576e-05f,
            2.1096914e-05f, 2.2467911e-05f, 2.3928002e-05f, 2.5482978e-05f,
            2.7139006e-05f, 2.8902651e-05f, 3.0780908e-05f, 3.2781225e-05f,
            3.4911534e-05f, 3.7180282e-05f, 3.9596466e-05f, 4.2169667e-05f,
            4.4910090e-05f, 4.7828601e-05f, 5.0936773e-05f, 5.4246931e-05f,
            5.7772202e-05f, 6.1526565e-05f, 6.5524908e-05f, 6.9783085e-05f,
            7.4317983e-05f, 7.9147585e-05f, 8.4291040e-05f, 8.9768747e-05f,
            9.5602426e-05f, 0.00010181521f, 0.00010843174f, 0.00011547824f,
            0.00012298267f, 0.00013097477f, 0.00013948625f, 0.00014855085f,
            0.00015820453f, 0.00016848555f, 0.00017943469f, 0.00019109536f,
            0.00020351382f, 0.00021673929f, 0.00023082423f, 0.00024582449f,
            0.00026179955f, 0.00027881276f, 0.00029693158f, 0.00031622787f,
            0.00033677814f, 0.00035866388f, 0.00038197188f, 0.00040679456f,
            0.00043323036f, 0.00046138411f, 0.00049136745f, 0.00052329927f,
            0.00055730621f, 0.00059352311f, 0.00063209358f, 0.00067317058f,
            0.00071691700f, 0.00076350630f, 0.00081312324f, 0.00086596457f,
            0.00092223983f, 0.00098217216f, 0.0010459992f, 0.0011139742f,
            0.0011863665f, 0.0012634633f, 0.0013455702f, 0.0014330129f,
            0.0015261382f, 0.0016253153f, 0.0017309374f, 0.0018434235f,
            0.0019632195f, 0.0020908006f, 0.0022266726f, 0.0023713743f,
            0.0025254795f, 0.0026895994f, 0.0028643847f, 0.0030505286f,
            0.0032487691f, 0.0034598925f, 0.0036847358f, 0.0039241906f,
            0.0041792066f, 0.0044507950f, 0.0047400328f, 0.0050480668f,
            0.0053761186f, 0.0057254891f, 0.0060975636f, 0.0064938176f,
            0.0069158225f, 0.0073652516f, 0.0078438871f, 0.0083536271f,
            0.0088964928f, 0.009474637f, 0.010090352f, 0.010746080f,
            0.011444421f, 0.012188144f, 0.012980198f, 0.013823725f,
            0.014722068f, 0.015678791f, 0.016697687f, 0.017782797f,
            0.018938423f, 0.020169149f, 0.021479854f, 0.022875735f,
            0.024362330f, 0.025945531f, 0.027631618f, 0.029427276f,
            0.031339626f, 0.033376252f, 0.035545228f, 0.037855157f,
            0.040315199f, 0.042935108f, 0.045725273f, 0.048696758f,
            0.051861348f, 0.055231591f, 0.058820850f, 0.062643361f,
            0.066714279f, 0.071049749f, 0.075666962f, 0.080584227f,
            0.085821044f, 0.091398179f, 0.097337747f, 0.10366330f,
            0.11039993f, 0.11757434f, 0.12521498f, 0.13335215f,
            0.14201813f, 0.15124727f, 0.16107617f, 0.17154380f,
            0.18269168f, 0.19456402f, 0.20720788f, 0.22067342f,
            0.23501402f, 0.25028656f, 0.26655159f, 0.28387361f,
            0.30232132f, 0.32196786f, 0.34289114f, 0.36517414f,
            0.38890521f, 0.41417847f, 0.44109412f, 0.46975890f,
            0.50028648f, 0.53279791f, 0.56742212f, 0.60429640f,
            0.64356699f, 0.68538959f, 0.72993007f, 0.77736504f,
            0.82788260f, 0.88168307f, 0.9389798f, 1.0f
    };

    /**
     * How much of the stream is read ahead to learn how many samples the first page says to drop. A file whose pages
     * never carry a granule position would otherwise be read into memory whole; past this the drop is skipped.
     */
    private static final int MAX_LOOKAHEAD_PACKETS = 4096;
    private static final long MAX_LOOKAHEAD_BYTES = 8L << 20;

    private final OggPageReader ogg;
    private final InputStream input;
    private final VorbisDecoderSetup setup;
    private final int channels;
    private final VorbisDecoderBits bits = new VorbisDecoderBits();
    private final VorbisDecoderMdct[] transforms;
    private final float[][] buffers;
    private final float[][] previous;
    private int previousLength;
    private boolean started;

    private final int[][] floorY;
    private final boolean[][] floorStep2;
    private final boolean[] floorUnused;
    private final boolean[] noResidue;
    private final float[][] submapVectors;
    private final boolean[] submapSkip;
    private int[][] classifications = new int[0][];
    private int[] type0Entries = new int[0];

    /** Samples of the current packet not yet handed out: buffers[c][outStart..outEnd). */
    private int outStart;
    private int outEnd;

    /**
     * Sample positions count from the centre of the first audio packet, which is where the first returned sample
     * comes from. centre is the position of the current packet's centre; positionOffset turns a position into a
     * granule position once a page has told us one.
     */
    private long centre;
    private int previousBlocksize;
    private long positionOffset;
    private boolean offsetKnown;
    private long startTrim;

    private final ArrayDeque<Packet> lookahead = new ArrayDeque<>();
    private boolean lookaheadDone;
    private boolean finished;
    private boolean closed;
    private AudioFormatException failure;

    // The Minecraft timeline. Samples are queued in the order the game plays them and handed out only as far as JOrbis
    // would have returned them by now: up to the centre of the newest block, since the samples after it may still be
    // cut when it turns out to be the last. The counts below are in samples played, from the first one.
    private final boolean minecraft;
    private float[][] queue;
    private int queueStart;
    private int queueEnd;
    /** How many of the queued samples, from queueStart, the game has played by now. */
    private int queueReady;
    /** Samples the game plays from the first block when it is a long one; 0 otherwise. */
    private int leadIn;
    /** How far, in samples played, the game has been handed the song: up to the centre of the newest block. */
    private long playedReturned;
    /** The decoder's own count of the granule position, -1 until a page has told it one. */
    private long trackedGranule = -1;
    /** The centre of the previous block, as {@link #centre} counts it. */
    private long queuedCentre;
    /** Minecraft's count of the samples it has played, which the end-of-stream page's granule position caps. */
    private long played;

    private VorbisDecoder(OggPageReader ogg, InputStream input, VorbisDecoderSetup setup, Timeline timeline) {
        this.ogg = ogg;
        this.input = input;
        this.setup = setup;
        this.minecraft = timeline == Timeline.MINECRAFT;
        this.channels = setup.channels;
        VorbisDecoderMdct small = new VorbisDecoderMdct(setup.blocksize0);
        VorbisDecoderMdct large = setup.blocksize1 == setup.blocksize0 ? small : new VorbisDecoderMdct(setup.blocksize1);
        this.transforms = new VorbisDecoderMdct[] {small, large};
        this.buffers = new float[channels][setup.blocksize1];
        this.previous = new float[channels][setup.blocksize1 / 2];
        this.floorY = new int[channels][setup.maxFloorValues];
        this.floorStep2 = new boolean[channels][setup.maxFloorValues];
        this.floorUnused = new boolean[channels];
        this.noResidue = new boolean[channels];
        this.submapVectors = new float[channels][];
        this.submapSkip = new boolean[channels];
        this.queue = minecraft ? new float[channels][2 * setup.blocksize1] : null;
    }

    /** {@link #open(InputStream, Timeline)} on the samples Minecraft plays, {@link Timeline#MINECRAFT}. */
    public static PcmStream open(InputStream in) throws IOException {
        return open(in, Timeline.MINECRAFT);
    }

    /**
     * Reads the Vorbis headers and returns a stream of the decoded samples. On success the returned stream owns
     * {@code in} and closes it; on failure {@code in} is left open for the caller.
     *
     * @throws AudioFormatException when the bytes are not Ogg Vorbis or the headers are damaged
     */
    public static PcmStream open(InputStream in, Timeline timeline) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(timeline, "timeline");
        OggPageReader ogg = new OggPageReader(in, timeline == Timeline.MINECRAFT);
        try {
            ogg.chooseVorbisStream();
            if (!ogg.nextPacket(true)) {
                throw new AudioFormatException("The Vorbis stream ends before its headers.");
            }
            byte[] id = Arrays.copyOf(ogg.packetData(), ogg.packetLength());
            if (!ogg.nextPacket(false)) {
                throw new AudioFormatException("The Vorbis stream ends before its headers.");
            }
            byte[] comment = ogg.packetData();
            if (ogg.packetLength() < 7 || comment[0] != 3 || comment[1] != 'v' || comment[2] != 'o'
                    || comment[3] != 'r' || comment[4] != 'b' || comment[5] != 'i' || comment[6] != 's') {
                throw new AudioFormatException("The second Vorbis header is not the comment header.");
            }
            if (!ogg.nextPacket(true)) {
                throw new AudioFormatException("The Vorbis stream ends before its headers.");
            }
            VorbisDecoderSetup setup = VorbisDecoderSetup.parse(id, id.length, ogg.packetData(), ogg.packetLength());
            ogg.audioStarts();
            return new VorbisDecoder(ogg, in, setup, timeline);
        } catch (RuntimeException e) {
            throw new AudioFormatException("The Vorbis headers are damaged.", e);
        }
    }

    /**
     * Whether the granule positions of an Ogg Vorbis stream agree with its packets, read without decoding any audio:
     * every page that ends a packet carries the number of samples the packets up to it complete (a quarter of each
     * block and of the block before it), and the last one cuts the last block by less than half a long block. Every
     * encoder's own files agree. A recording cut from a broadcast, and a file cut with ffmpeg's "-c copy", do not:
     * their granule positions start elsewhere or follow the container's clock, so the last one does not say how long
     * the song plays, and only decoding it on Minecraft's timeline tells. Damaged files are answered with false.
     * It does not close {@code in}.
     */
    public static boolean granulesMatchPackets(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        OggPageReader ogg = new OggPageReader(in, false);
        try {
            ogg.chooseVorbisStream();
            if (!ogg.nextPacket(true)) {
                return false;
            }
            byte[] id = Arrays.copyOf(ogg.packetData(), ogg.packetLength());
            if (!ogg.nextPacket(false) || !ogg.nextPacket(true)) {
                return false;
            }
            VorbisDecoderSetup setup = VorbisDecoderSetup.parse(id, id.length, ogg.packetData(), ogg.packetLength());
            ogg.audioStarts();
            VorbisDecoderBits bits = new VorbisDecoderBits();
            long total = 0;
            int previous = 0;
            while (ogg.nextPacket(false)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Reading the Vorbis granule positions was interrupted.");
                }
                byte[] data = ogg.packetData();
                int size = 0;
                // Only the first bytes are kept (nextPacket(false)); the mode number is in the first one or two.
                bits.reset(data, 0, Math.min(ogg.packetLength(), data.length));
                if (bits.read(1) == 0 && !bits.eop) {
                    int mode = bits.read(setup.modeBits);
                    if (!bits.eop && mode < setup.modes.length) {
                        size = setup.modes[mode].blockflag ? setup.blocksize1 : setup.blocksize0;
                    }
                }
                if (size > 0) {
                    if (previous > 0) {
                        total += previous / 4 + size / 4;
                    }
                    previous = size;
                }
                long granule = ogg.packetGranule();
                if (granule != -1) {
                    if (ogg.packetEndsStream()) {
                        return granule <= total && total - granule < setup.blocksize1 / 2;
                    }
                    if (granule != total) {
                        return false;
                    }
                }
                if (ogg.packetEndsStream()) {
                    return false;
                }
            }
            return false;
        } catch (AudioFormatException | RuntimeException damaged) {
            return false;
        }
    }

    /**
     * The fields of an Ogg Vorbis stream's comment header, each as written ({@code TITLE=...}), read without decoding
     * any audio; the mod marks its sped-up copies with one. It does not close {@code in}.
     *
     * @throws AudioFormatException when the bytes are not Ogg Vorbis or the comment header is damaged
     */
    public static List<String> comments(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        OggPageReader ogg = new OggPageReader(in, false);
        ogg.chooseVorbisStream();
        if (!ogg.nextPacket(false) || !ogg.nextPacket(true)) {
            throw new AudioFormatException("The Vorbis stream ends before its headers.");
        }
        byte[] data = ogg.packetData();
        int length = ogg.packetLength();
        if (length < 7 || data[0] != 3 || data[1] != 'v' || data[2] != 'o' || data[3] != 'r' || data[4] != 'b'
                || data[5] != 'i' || data[6] != 's') {
            throw new AudioFormatException("The second Vorbis header is not the comment header.");
        }
        int at = 7;
        long vendor = commentInt(data, length, at);
        at = commentSkip(length, at + 4, vendor);
        long count = commentInt(data, length, at);
        at += 4;
        List<String> fields = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            long size = commentInt(data, length, at);
            int start = at + 4;
            at = commentSkip(length, start, size);
            fields.add(new String(data, start, at - start, StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** A little-endian unsigned 32-bit length in the comment header, which has to fit in it. */
    private static long commentInt(byte[] data, int length, int at) throws AudioFormatException {
        if (at < 0 || at + 4 > length) {
            throw new AudioFormatException("The Vorbis comment header is cut short.");
        }
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }

    private static int commentSkip(int length, int at, long size) throws AudioFormatException {
        if (size > length - at) {
            throw new AudioFormatException("The Vorbis comment header is cut short.");
        }
        return at + (int) size;
    }

    @Override
    public int sampleRate() {
        return setup.sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int read(float[][] out, int offset, int frames) throws IOException {
        if (closed) {
            throw new IOException("The Vorbis stream is closed.");
        }
        if (out.length < channels || offset < 0 || frames < 0) {
            throw new IllegalArgumentException("The output does not fit " + channels + " channels at " + offset + ".");
        }
        for (int c = 0; c < channels; c++) {
            if (out[c].length - offset < frames) {
                throw new IllegalArgumentException("Channel " + c + " has no room for " + frames + " frames.");
            }
        }
        if (failure != null) {
            throw new AudioFormatException(failure.getMessage(), failure);
        }
        if (frames == 0) {
            return 0;
        }
        int done = 0;
        while (done < frames) {
            int available = minecraft ? queueReady : outEnd - outStart;
            if (available == 0) {
                if (finished || !advance()) {
                    break;
                }
                continue;
            }
            int count = Math.min(available, frames - done);
            if (minecraft) {
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(queue[c], queueStart, out[c], offset + done, count);
                }
                queueStart += count;
                queueReady -= count;
            } else {
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(buffers[c], outStart, out[c], offset + done, count);
                }
                outStart += count;
            }
            done += count;
        }
        return done == 0 ? -1 : done;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            input.close();
        }
    }

    /** Decodes packets until one leaves samples to hand out; false at the end of the stream. */
    private boolean advance() throws IOException {
        try {
            if (!lookaheadDone && !minecraft) {
                readFirstPage();
            }
            while (!finished) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Vorbis decoding was interrupted.");
                }
                Packet packet = lookahead.poll();
                boolean ok;
                if (packet != null) {
                    ok = decodePacket(packet.data, packet.data.length, packet.granule, packet.endsStream);
                } else {
                    if (!ogg.nextPacket(true)) {
                        finished = true;
                        return false;
                    }
                    ok = decodePacket(ogg.packetData(), ogg.packetLength(), ogg.packetGranule(),
                            ogg.packetEndsStream());
                }
                if (ok && (minecraft ? queueReady > 0 : outEnd > outStart)) {
                    return true;
                }
            }
            return false;
        } catch (AudioFormatException e) {
            failure = e;
            throw e;
        } catch (RuntimeException e) {
            failure = new AudioFormatException("The Vorbis audio is damaged.", e);
            throw failure;
        }
    }

    /**
     * Reads ahead to the first page that carries a granule position and works out how many samples it says the
     * stream starts with. An encoder that begins a song part way into its first block (to line it up with video, or
     * to cut it from a longer one) writes a smaller granule there, and the specification asks for the difference to
     * be dropped from the front. stb_vorbis and ffmpeg keep those samples and libvorbis drops only the ones inside the
     * packet that carries the granule, so on such a file this decoder starts later than they do; on files that start
     * at zero, which is every encoder's default, all of them agree. When that page is also the last one, the excess
     * is cut from the end instead, which is the specification's (and libvorbis's) rule for a stream that short. Only
     * the specification's timeline reads ahead: Minecraft drops nothing at the start.
     */
    private void readFirstPage() throws IOException {
        lookaheadDone = true;
        long total = 0;
        int previousSize = 0;
        long bytes = 0;
        while (lookahead.size() < MAX_LOOKAHEAD_PACKETS && bytes < MAX_LOOKAHEAD_BYTES) {
            if (!ogg.nextPacket(true)) {
                return;
            }
            Packet packet = new Packet(Arrays.copyOf(ogg.packetData(), ogg.packetLength()), ogg.packetGranule(),
                    ogg.packetEndsStream());
            lookahead.add(packet);
            bytes += packet.data.length;
            int size = blocksizeOf(packet.data);
            if (size > 0) {
                if (previousSize > 0) {
                    total += previousSize / 4 + size / 4;
                }
                previousSize = size;
            }
            if (packet.granule != -1 || packet.endsStream) {
                if (packet.granule != -1 && !packet.endsStream && previousSize > 0 && packet.granule < total) {
                    startTrim = total - packet.granule;
                }
                return;
            }
        }
    }

    /** The block size an audio packet will be decoded with, or 0 for a packet the decoder skips. */
    private int blocksizeOf(byte[] data) {
        bits.reset(data, 0, data.length);
        if (bits.read(1) != 0 || bits.eop) {
            return 0;
        }
        int modeNumber = bits.read(setup.modeBits);
        if (bits.eop || modeNumber >= setup.modes.length) {
            return 0;
        }
        if (!setup.modes[modeNumber].blockflag) {
            return setup.blocksize0;
        }
        bits.read(2);
        return bits.eop ? 0 : setup.blocksize1;
    }

    /**
     * Decodes one packet into buffers and marks the samples it completes as ready.
     *
     * @return false for a packet that is skipped without changing anything: a header packet, an empty one, or one
     *         whose mode cannot be read, which libvorbis also skips
     */
    private boolean decodePacket(byte[] data, int length, long granule, boolean endsStream) throws AudioFormatException {
        outStart = outEnd = 0;
        bits.reset(data, 0, length);
        if (bits.read(1) != 0 || bits.eop) {
            return skipped(endsStream);
        }
        int modeNumber = bits.read(setup.modeBits);
        if (bits.eop || modeNumber >= setup.modes.length) {
            return skipped(endsStream);
        }
        VorbisDecoderSetup.Mode mode = setup.modes[modeNumber];
        int n;
        int leftStart;
        int leftEnd;
        int rightStart;
        int rightEnd;
        int blocksize0 = setup.blocksize0;
        boolean previousLong = false;
        if (mode.blockflag) {
            n = setup.blocksize1;
            previousLong = bits.readFlag();
            boolean nextLong = bits.readFlag();
            if (bits.eop) {
                return skipped(endsStream);
            }
            leftStart = previousLong ? 0 : (n - blocksize0) >> 2;
            leftEnd = previousLong ? n >> 1 : (n + blocksize0) >> 2;
            rightStart = nextLong ? n >> 1 : (n * 3 - blocksize0) >> 2;
            rightEnd = nextLong ? n : (n * 3 + blocksize0) >> 2;
        } else {
            n = blocksize0;
            leftStart = 0;
            leftEnd = n >> 1;
            rightStart = n >> 1;
            rightEnd = n;
        }
        int n2 = n >> 1;
        VorbisDecoderSetup.Mapping mapping = setup.mappings[mode.mapping];

        for (int c = 0; c < channels; c++) {
            Arrays.fill(buffers[c], 0, n2, 0f);
            decodeFloor(c, setup.floors[mapping.submapFloor[mapping.mux[c]]]);
        }

        // A channel whose floor is unused still needs its residue when it is coupled to one that is used.
        System.arraycopy(floorUnused, 0, noResidue, 0, channels);
        for (int i = 0; i < mapping.couplingSteps; i++) {
            int m = mapping.magnitude[i];
            int a = mapping.angle[i];
            if (!noResidue[m] || !noResidue[a]) {
                noResidue[m] = noResidue[a] = false;
            }
        }

        for (int s = 0; s < mapping.submaps; s++) {
            int count = 0;
            for (int c = 0; c < channels; c++) {
                if (mapping.mux[c] == s) {
                    submapVectors[count] = buffers[c];
                    submapSkip[count] = noResidue[c];
                    count++;
                }
            }
            decodeResidue(setup.residues[mapping.submapResidue[s]], count, n2);
        }

        for (int i = mapping.couplingSteps - 1; i >= 0; i--) {
            float[] magnitude = buffers[mapping.magnitude[i]];
            float[] angle = buffers[mapping.angle[i]];
            for (int j = 0; j < n2; j++) {
                float m = magnitude[j];
                float a = angle[j];
                if (m > 0) {
                    if (a > 0) {
                        angle[j] = m - a;
                    } else {
                        angle[j] = m;
                        magnitude[j] = m + a;
                    }
                } else {
                    if (a > 0) {
                        angle[j] = m + a;
                    } else {
                        angle[j] = m;
                        magnitude[j] = m - a;
                    }
                }
            }
        }

        // A silent channel still goes through the transform, as in stb_vorbis: it turns some zeros into negative
        // zeros, and keeping that keeps the output bit-identical to stb_vorbis, which the checks pin.
        VorbisDecoderMdct transform = transforms[mode.blockflag ? 1 : 0];
        for (int c = 0; c < channels; c++) {
            float[] buffer = buffers[c];
            if (floorUnused[c]) {
                Arrays.fill(buffer, 0, n2, 0f);
            } else {
                renderFloor(c, setup.floors[mapping.submapFloor[mapping.mux[c]]], buffer, n2);
            }
            transform.inverse(buffer);
        }

        // Overlap the rising slope with what the previous packet left, as stb_vorbis's vorbis_finish_frame does.
        int regionStart;
        if (started) {
            if (previousLength != leftEnd - leftStart) {
                throw new AudioFormatException("Two neighbouring Vorbis packets disagree about their window size.");
            }
            float[] window = (previousLength * 2 == blocksize0 ? transforms[0] : transforms[1]).window;
            int last = previousLength - 1;
            for (int c = 0; c < channels; c++) {
                float[] buffer = buffers[c];
                float[] tail = previous[c];
                for (int j = 0; j < previousLength; j++) {
                    buffer[leftStart + j] = buffer[leftStart + j] * window[j] + tail[j] * window[last - j];
                }
            }
            centre += previousBlocksize / 4 + n / 4;
            regionStart = leftStart;
        } else {
            // The first packet only primes the overlap; its samples before the centre are not part of the song.
            started = true;
            centre = 0;
            regionStart = n2;
            if (minecraft && n > blocksize0) {
                queueLeadIn(n, previousLong);
            }
        }
        previousBlocksize = n;
        previousLength = rightEnd - rightStart;
        for (int c = 0; c < channels; c++) {
            System.arraycopy(buffers[c], rightStart, previous[c], 0, previousLength);
        }

        long regionPosition = centre - n2 + regionStart;
        long regionEndPosition = centre - n2 + rightStart;
        if (minecraft) {
            queueMinecraft(regionStart, regionStart + (int) (regionEndPosition - regionPosition), granule, endsStream);
            return true;
        }
        if (granule != -1) {
            if (endsStream) {
                // libvorbis never returns samples past the centre of the last packet, and the last page's granule
                // position may cut the packet shorter still.
                long end = granule - (offsetKnown ? positionOffset : 0);
                regionEndPosition = Math.min(regionEndPosition, Math.min(end, centre));
            } else {
                positionOffset = granule - centre;
                offsetKnown = true;
            }
        }
        if (endsStream) {
            finished = true;
        }
        long from = Math.max(regionPosition, startTrim);
        if (regionEndPosition > from) {
            outStart = (int) (regionStart + (from - regionPosition));
            outEnd = (int) (regionStart + (regionEndPosition - regionPosition));
            // Only a damaged or hostile stream can make codebook values big enough to overflow; the encoder and the
            // tempo change after this must never be handed an infinity or a NaN.
            for (int c = 0; c < channels; c++) {
                float[] buffer = buffers[c];
                for (int i = outStart; i < outEnd; i++) {
                    if (!Float.isFinite(buffer[i])) {
                        throw new AudioFormatException("The Vorbis audio decodes to invalid sample values.");
                    }
                }
            }
        }
        return true;
    }

    /**
     * A packet that is not audio, or whose mode cannot be read. libvorbis passes over it, and so does this decoder when
     * it follows the specification. JOrbis refuses it, and the game then loses the part of the song it had decoded
     * but not yet played and goes on with the next packet, early by that much: a copy cannot follow that, so on the
     * Minecraft timeline such a packet in the middle of the song is refused. As the very last packet it only ends the
     * song, which is where the game stops too.
     */
    private boolean skipped(boolean endsStream) throws AudioFormatException {
        if (minecraft && ogg.minecraftPlayable() && !endsStream) {
            throw new AudioFormatException("A packet in the middle of the Vorbis audio is not audio: Minecraft would"
                    + " skip part of the song there and play the rest early.");
        }
        return false;
    }

    /**
     * Queues the samples Minecraft plays from a first block that is a long one: the part of its left half after the
     * short window's slope, under the long block's own window. JOrbis, which the game decodes with, starts the overlap
     * of the first block as if the block before it were a short one, and so returns this part where the specification
     * returns nothing; it then plays the whole song that many samples later.
     */
    private void queueLeadIn(int n, boolean previousLong) throws AudioFormatException {
        int from = (n + setup.blocksize0) >> 2;
        int to = n >> 1;
        float[] slope = transforms[1].window;
        reserveQueue(to - from);
        for (int c = 0; c < channels; c++) {
            float[] buffer = buffers[c];
            float[] target = queue[c];
            for (int i = from, at = queueEnd; i < to; i++, at++) {
                float value = previousLong ? buffer[i] * slope[i] : buffer[i];
                if (!Float.isFinite(value)) {
                    throw new AudioFormatException("The Vorbis audio decodes to invalid sample values.");
                }
                target[at] = value;
            }
        }
        queueEnd += to - from;
        leadIn = to - from;
    }

    /**
     * Queues one packet's new samples and works out how far Minecraft has played: JOrbis returns samples up to the
     * centre of the newest block. At the packet that ends the stream it pulls that centre back to where the last
     * granule position says the song ends, counting from the first page that carried a granule position, so a song cut
     * from a longer one keeps its first samples and loses as many at the end. That count is libvorbis's own (BSD):
     * vorbis_synthesis_blockin starts it at the first granule position a packet carries, adds the distance between
     * block centres for every packet after it, and at the end of the stream trims the samples it counted past the
     * page's granule position; JOrbis, a port of libvorbis, keeps that rule, and this follows libvorbis. And the game
     * itself stops once it has played as many samples as the end-of-stream page's granule position. Both rules were
     * measured against Minecraft's JOrbisAudioStream on cut, lowered and long-first-block files.
     */
    private void queueMinecraft(int from, int to, long granule, boolean endsStream) throws AudioFormatException {
        for (int c = 0; c < channels; c++) {
            float[] buffer = buffers[c];
            for (int i = from; i < to; i++) {
                if (!Float.isFinite(buffer[i])) {
                    throw new AudioFormatException("The Vorbis audio decodes to invalid sample values.");
                }
            }
        }
        if (to > from) {
            reserveQueue(to - from);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(buffers[c], from, queue[c], queueEnd, to - from);
            }
            queueEnd += to - from;
        }
        long blockCentre = leadIn + centre;
        if (trackedGranule == -1) {
            trackedGranule = granule;
        } else {
            trackedGranule += centre - queuedCentre;
            if (granule != -1 && trackedGranule != granule) {
                if (trackedGranule > granule && endsStream) {
                    blockCentre -= trackedGranule - granule;
                }
                trackedGranule = granule;
            }
        }
        queuedCentre = centre;
        if (endsStream) {
            finished = true;
        }
        long batch = blockCentre - playedReturned;
        if (batch <= 0) {
            return;
        }
        playedReturned = blockCentre;
        long end = ogg.endGranule();
        long count = end == Long.MIN_VALUE ? batch : Math.min(end - played, batch);
        played += count;
        if (count > 0) {
            queueReady += (int) count;
        }
        if (count < batch) {
            // The game has played all the end-of-stream page allows; it never plays another sample of this stream.
            finished = true;
        }
    }

    /** Makes room for {@code count} more queued samples, moving the ones not yet handed out to the front first. */
    private void reserveQueue(int count) {
        if (queueEnd + count <= queue[0].length) {
            return;
        }
        int kept = queueEnd - queueStart;
        int capacity = Math.max(queue[0].length, kept + count);
        for (int c = 0; c < channels; c++) {
            float[] target = capacity == queue[c].length ? queue[c] : new float[Math.max(capacity, 2 * queue[c].length)];
            System.arraycopy(queue[c], queueStart, target, 0, kept);
            queue[c] = target;
        }
        queueStart = 0;
        queueEnd = kept;
    }

    /**
     * Reads one channel's floor 1 curve: its post amplitudes and which of them are drawn. The curve itself is applied
     * after the residue, when the spectrum is in place. Running out of packet part way marks the floor unused, as the
     * specification and libvorbis say.
     */
    private void decodeFloor(int channel, VorbisDecoderSetup.Floor1 floor) {
        VorbisDecoderBits bits = this.bits;
        floorUnused[channel] = true;
        if (!bits.readFlag()) {
            return;
        }
        int[] y = floorY[channel];
        boolean[] step2 = floorStep2[channel];
        int range = FLOOR1_RANGE[floor.multiplier - 1];
        int rangeBits = VorbisDecoderSetup.ilog(range - 1);
        y[0] = bits.read(rangeBits);
        y[1] = bits.read(rangeBits);
        int offset = 2;
        VorbisDecoderSetup.Codebook[] books = setup.codebooks;
        for (int j = 0; j < floor.partitions; j++) {
            int partitionClass = floor.partitionClass[j];
            int dimensions = floor.classDimensions[partitionClass];
            int subclassBits = floor.classSubclasses[partitionClass];
            int subclassMask = (1 << subclassBits) - 1;
            int classValue = 0;
            if (subclassBits != 0) {
                classValue = bits.decode(books[floor.classMasterbook[partitionClass]]);
                if (classValue < 0) {
                    return;
                }
            }
            int[] subclassBooks = floor.subclassBooks[partitionClass];
            for (int k = 0; k < dimensions; k++) {
                int book = subclassBooks[classValue & subclassMask];
                classValue >>>= subclassBits;
                if (book >= 0) {
                    int value = bits.decode(books[book]);
                    if (value < 0) {
                        return;
                    }
                    y[offset++] = value;
                } else {
                    y[offset++] = 0;
                }
            }
        }
        if (bits.eop) {
            return;
        }
        int[] x = floor.x;
        step2[0] = step2[1] = true;
        for (int j = 2; j < floor.values; j++) {
            int low = floor.lowNeighbor[j];
            int high = floor.highNeighbor[j];
            int predicted = predictPoint(x[j], x[low], x[high], y[low], y[high]);
            int value = y[j];
            int highRoom = range - predicted;
            int lowRoom = predicted;
            int room = (highRoom < lowRoom ? highRoom : lowRoom) * 2;
            if (value != 0) {
                step2[low] = step2[high] = true;
                step2[j] = true;
                int amplitude;
                if (value >= room) {
                    amplitude = highRoom > lowRoom ? value - lowRoom + predicted : predicted - value + highRoom - 1;
                } else {
                    amplitude = (value & 1) != 0 ? predicted - ((value + 1) >> 1) : predicted + (value >> 1);
                }
                // A valid stream stays inside the range; for one that does not, libvorbis keeps the low 15 bits, and
                // doing the same keeps this decoder's output equal to the reference implementation's even then.
                y[j] = amplitude & 0x7fff;
            } else {
                step2[j] = false;
                y[j] = predicted;
            }
        }
        floorUnused[channel] = false;
    }

    private static int predictPoint(int x, int x0, int x1, int y0, int y1) {
        int dy = y1 - y0;
        int adx = x1 - x0;
        int off = Math.abs(dy) * (x - x0) / adx;
        return dy < 0 ? y0 - off : y0 + off;
    }

    /**
     * Multiplies the spectrum by the floor curve: straight lines in decibels between the used posts, drawn with the
     * specification's integer line algorithm, which encoders rely on bit for bit. Amplitudes outside the table are
     * clamped, as libvorbis does, so a damaged value cannot index past it.
     */
    private void renderFloor(int channel, VorbisDecoderSetup.Floor1 floor, float[] target, int n2) {
        int[] y = floorY[channel];
        boolean[] step2 = floorStep2[channel];
        int[] x = floor.x;
        int[] order = floor.sortedOrder;
        int multiplier = floor.multiplier;
        int lx = 0;
        int ly = clampDb(y[0] * multiplier);
        for (int q = 1; q < floor.values; q++) {
            int j = order[q];
            if (!step2[j]) {
                continue;
            }
            int hy = clampDb(y[j] * multiplier);
            int hx = x[j];
            if (lx != hx) {
                drawLine(target, lx, ly, hx, hy, n2);
            }
            lx = hx;
            ly = hy;
        }
        if (lx < n2) {
            float value = INVERSE_DB_TABLE[ly];
            for (int j = lx; j < n2; j++) {
                target[j] *= value;
            }
        }
    }

    private static int clampDb(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static void drawLine(float[] output, int x0, int y0, int x1, int y1, int n) {
        int dy = y1 - y0;
        int adx = x1 - x0;
        int ady = Math.abs(dy);
        int base = dy / adx;
        int sy = dy < 0 ? base - 1 : base + 1;
        int x = x0;
        int y = y0;
        int err = 0;
        ady -= Math.abs(base) * adx;
        if (x1 > n) {
            x1 = n;
        }
        if (x < x1) {
            output[x] *= INVERSE_DB_TABLE[y];
            for (x++; x < x1; x++) {
                err += ady;
                if (err >= adx) {
                    err -= adx;
                    y += sy;
                } else {
                    y += base;
                }
                output[x] *= INVERSE_DB_TABLE[y];
            }
        }
    }

    /**
     * Adds one submap's residue to its channels' spectra. The end of the packet, or a class number the classbook
     * cannot mean, stops the residue there and keeps what was decoded, as in stb_vorbis and libvorbis.
     */
    private void decodeResidue(VorbisDecoderSetup.Residue residue, int ch, int n) {
        boolean allSkipped = true;
        for (int j = 0; j < ch; j++) {
            if (!submapSkip[j]) {
                allSkipped = false;
                break;
            }
        }
        if (allSkipped) {
            return;
        }
        int type = residue.type;
        // Residue type 2 is type 1 run over the channels interleaved into one vector, so it has one set of classes.
        int vectors = type == 2 ? 1 : ch;
        int actualSize = type == 2 ? n * ch : n;
        int begin = Math.min(residue.begin, actualSize);
        int end = Math.min(residue.end, actualSize);
        int partitionSize = residue.partitionSize;
        int partitions = (end - begin) / partitionSize;
        if (partitions <= 0) {
            return;
        }
        VorbisDecoderSetup.Codebook classbook = setup.codebooks[residue.classbook];
        int classwords = classbook.dimensions;
        int classCount = residue.classifications;
        if (classifications.length < vectors || classifications[0].length < partitions) {
            classifications = new int[Math.max(vectors, classifications.length)][Math.max(partitions, 64)];
        }
        VorbisDecoderBits bits = this.bits;
        VorbisDecoderSetup.Codebook[] books = setup.codebooks;
        for (int pass = 0; pass < 8; pass++) {
            int partition = 0;
            while (partition < partitions) {
                if (pass == 0) {
                    for (int j = 0; j < vectors; j++) {
                        if (type != 2 && submapSkip[j]) {
                            continue;
                        }
                        int value = bits.decode(classbook);
                        if (value < 0 || value >= residue.partitionValues) {
                            return;
                        }
                        int[] classes = classifications[j];
                        for (int i = classwords - 1; i >= 0; i--) {
                            if (partition + i < partitions) {
                                classes[partition + i] = value % classCount;
                            }
                            value /= classCount;
                        }
                    }
                }
                for (int i = 0; i < classwords && partition < partitions; i++, partition++) {
                    int offset = residue.begin + partition * partitionSize;
                    for (int j = 0; j < vectors; j++) {
                        if (type != 2 && submapSkip[j]) {
                            continue;
                        }
                        int book = residue.books[classifications[j][partition]][pass];
                        if (book < 0) {
                            continue;
                        }
                        VorbisDecoderSetup.Codebook codebook = books[book];
                        boolean ok;
                        if (type == 2) {
                            ok = decodeInterleaved(codebook, ch, offset, partitionSize);
                        } else if (type == 1) {
                            ok = decodeType1(codebook, submapVectors[j], offset, partitionSize);
                        } else {
                            ok = decodeType0(codebook, submapVectors[j], offset, partitionSize);
                        }
                        if (!ok) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Residue type 0 spreads each codeword across the partition with a stride. Like libvorbis, a partition whose
     * codewords run out part way adds nothing at all.
     */
    private boolean decodeType0(VorbisDecoderSetup.Codebook book, float[] target, int offset, int size) {
        float[] vq = book.vq;
        if (vq == null) {
            return false;
        }
        if (book.sortedCodes.length == 0) {
            return true;
        }
        int dimensions = book.dimensions;
        int step = size / dimensions;
        if (type0Entries.length < step) {
            type0Entries = new int[step];
        }
        for (int i = 0; i < step; i++) {
            type0Entries[i] = bits.decode(book);
            if (type0Entries[i] < 0) {
                return false;
            }
        }
        for (int i = 0; i < step; i++) {
            int base = type0Entries[i] * dimensions;
            for (int j = 0; j < dimensions; j++) {
                target[offset + i + j * step] += vq[base + j];
            }
        }
        return true;
    }

    /**
     * A book with no codewords in a residue pass is skipped without reading anything, as libvorbis does; any other
     * book without vectors cannot be decoded here, which ends the residue like the end of the packet.
     */
    private boolean decodeType1(VorbisDecoderSetup.Codebook book, float[] target, int offset, int size) {
        float[] vq = book.vq;
        if (vq == null) {
            return false;
        }
        if (book.sortedCodes.length == 0) {
            return true;
        }
        int dimensions = book.dimensions;
        for (int k = 0; k < size; k += dimensions) {
            int entry = bits.decode(book);
            if (entry < 0) {
                return false;
            }
            int base = entry * dimensions;
            int count = Math.min(dimensions, size - k);
            int at = offset + k;
            for (int j = 0; j < count; j++) {
                target[at + j] += vq[base + j];
            }
        }
        return true;
    }

    private boolean decodeInterleaved(VorbisDecoderSetup.Codebook book, int ch, int offset, int size) {
        float[] vq = book.vq;
        if (vq == null) {
            return false;
        }
        if (book.sortedCodes.length == 0) {
            return true;
        }
        int dimensions = book.dimensions;
        float[][] vectors = submapVectors;
        if (ch == 1) {
            return decodeType1(book, vectors[0], offset, size);
        }
        if (ch == 2) {
            float[] left = vectors[0];
            float[] right = vectors[1];
            for (int k = 0; k < size; k += dimensions) {
                int entry = bits.decode(book);
                if (entry < 0) {
                    return false;
                }
                int base = entry * dimensions;
                int count = Math.min(dimensions, size - k);
                int index = offset + k;
                for (int j = 0; j < count; j++, index++) {
                    if ((index & 1) == 0) {
                        left[index >> 1] += vq[base + j];
                    } else {
                        right[index >> 1] += vq[base + j];
                    }
                }
            }
            return true;
        }
        int channel = offset % ch;
        int position = offset / ch;
        for (int k = 0; k < size; k += dimensions) {
            int entry = bits.decode(book);
            if (entry < 0) {
                return false;
            }
            int base = entry * dimensions;
            int count = Math.min(dimensions, size - k);
            for (int j = 0; j < count; j++) {
                vectors[channel][position] += vq[base + j];
                if (++channel == ch) {
                    channel = 0;
                    position++;
                }
            }
        }
        return true;
    }

    private record Packet(byte[] data, long granule, boolean endsStream) {
    }
}
