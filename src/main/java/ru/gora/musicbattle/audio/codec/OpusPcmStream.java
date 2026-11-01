package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Turns the Opus packets of a container into the mod's planar 48 kHz float stream: decodes the packets one after
 * another, drops the pre-skip from the start and whatever the container trims from each packet. Shared by the Ogg
 * ({@link OpusDecoder}) and Matroska ({@link WebmDecoder}) readers, which only find the packets and say how each one is
 * trimmed. Nothing is inserted between packets: the song is exactly the decoded packets, as ffmpeg and libopus's own
 * tools give it.
 *
 * <p>Trimming works on positions in the decoded output, counted from its first sample: a sample is kept when it lies
 * at or after the pre-skip and inside what its packet's own trimming keeps. Both rules describe samples that are not
 * part of the song, so where they overlap (a WebM block whose negative DiscardPadding covers the pre-skip) a sample is
 * dropped once, not twice. The MPEG transport stream reader ({@link MpegTsDecoder}) uses it too, with the trims of the
 * Opus control headers followed only at the two ends of the song ({@link #trimOnlyAtTheEdges}).
 *
 * <p>A zero-length packet is skipped: RFC 7845 section 3 asks for it to be treated as a malformed packet, which RFC 6716
 * section 3.4 says to discard. It holds no samples, so the Ogg granule positions do
 * not count it either. (ffmpeg 9 stops reading an Ogg Opus file at such a packet, losing the rest of the song.)
 *
 * <p>One packet is decoded at a time into a buffer of at most 120 ms, so memory does not grow with the song.
 */
final class OpusPcmStream implements PcmStream {
    /** Where the packets of one Opus track come from. */
    interface Packets {
        /**
         * Moves to the next audio packet.
         *
         * @return false at the end of the track
         */
        boolean next() throws IOException;

        byte[] data();

        int offset();

        /** The packet's length in bytes; 0 for a malformed empty packet, which is skipped. */
        int length();

        /** Samples the container cuts from the start of this packet's decoded output. */
        int trimStart();

        /** Samples the container cuts from the end of this packet's decoded output. */
        int trimEnd();

        void close() throws IOException;
    }

    private final Packets packets;
    private final OpusMultistreamDecoder decoder;
    private final int channels;
    private final String what;
    private final float[] interleaved;
    private final float[][] planar;
    /** Samples at the start of the decoded output that are not part of the song (RFC 7845 section 4.2). */
    private final long preSkip;
    /** Samples decoded so far: the position of the next decoded sample. */
    private long position;
    private int outStart;
    private int outEnd;
    private boolean finished;
    private boolean closed;
    private AudioFormatException failure;
    /** See {@link #tolerateDamagedPackets}. */
    private boolean tolerant;
    private int damagedInRow;
    /** See {@link #trimOnlyAtTheEdges}. */
    private boolean edgesOnly;
    /** Whether any sample has been kept so far (in edge mode, a held-back end counts: it is kept once more follows). */
    private boolean keptAny;
    /**
     * Edge mode: the end the last packet's own trimming cut, held back in {@code planar[outEnd, outEnd + heldTail)}
     * until the next packet shows it was not the last packet after all.
     */
    private int heldTail;
    /** Edge mode: a packet decoded into {@code interleaved} that waits until the held-back end has been read. */
    private boolean pendingPacket;
    private int pendingStart;
    private int pendingSamples;
    private int pendingTrimEnd;

    /**
     * @param what how the audio is called in messages, such as "Ogg Opus"
     * @param preSkip samples to drop from the start of the decoded audio
     */
    OpusPcmStream(Packets packets, OpusHead head, long preSkip, String what) {
        this.packets = packets;
        this.decoder = head.newDecoder();
        this.channels = head.channels;
        this.what = what;
        this.preSkip = preSkip;
        this.interleaved = new float[OpusPacketDecoder.MAX_FRAME_SIZE * channels];
        this.planar = new float[channels][OpusPacketDecoder.MAX_FRAME_SIZE];
    }

    /**
     * For a broadcast or recorded MPEG transport stream: a packet the decoder refuses as invalid is dropped, as ffmpeg
     * drops it, rather than failing the whole song; more than eight in a row is still an error.
     */
    void tolerateDamagedPackets() {
        tolerant = true;
    }

    /**
     * For an MPEG transport stream: a packet's start trim is followed only while nothing of the song has been kept yet,
     * and its end trim only when it is the last packet. Joined HLS or segment pieces (ffmpeg's segment muxer starts
     * every piece afresh) carry the pre-skip's start trim on the first packet of every piece, but the decoder runs on
     * across the join, so the samples there are the song itself: cutting them would lose 6.5 ms at every join and
     * put the rest of the song that much early. ffmpeg ignores every trim in a transport stream; this ignores the ones
     * inside the song, and keeps the specification's pre-skip and end padding at the two ends, so a joined download
     * decodes to exactly the samples of the same packets in one piece.
     */
    void trimOnlyAtTheEdges() {
        edgesOnly = true;
    }

    @Override
    public int sampleRate() {
        return 48000;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int read(float[][] out, int offset, int frames) throws IOException {
        if (closed) {
            throw new IOException("The " + what + " stream is closed.");
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
            int available = outEnd - outStart;
            if (available == 0) {
                if (finished || !advance()) {
                    break;
                }
                continue;
            }
            int count = Math.min(available, frames - done);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(planar[c], outStart, out[c], offset + done, count);
            }
            outStart += count;
            done += count;
        }
        return done == 0 ? -1 : done;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            packets.close();
        }
    }

    /** Decodes until some samples are ready; false at the end of the audio. */
    private boolean advance() throws IOException {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Opus decoding was interrupted.");
                }
                if (pendingPacket) {
                    pendingPacket = false;
                    if (place(pendingStart, pendingSamples, pendingTrimEnd)) {
                        return true;
                    }
                    continue;
                }
                if (!packets.next()) {
                    // Edge mode: a held-back end belonged to the last packet, so its trim stands.
                    heldTail = 0;
                    finished = true;
                    return false;
                }
                int length = packets.length();
                if (length == 0) {
                    continue;
                }
                int n = decoder.decode(packets.data(), packets.offset(), length, interleaved, 0,
                        OpusPacketDecoder.MAX_FRAME_SIZE);
                if (n < 0) {
                    if (tolerant && n == OpusPacketDecoder.OPUS_INVALID_PACKET && ++damagedInRow <= 8) {
                        continue;
                    }
                    throw new AudioFormatException("The " + what + " audio is damaged (" + errorName(n) + ").");
                }
                damagedInRow = 0;
                long first = position;
                position += n;
                // The first sample this packet keeps: past its own trimmed start and past the pre-skip.
                int trimStart = edgesOnly && keptAny ? 0 : Math.min(n, Math.max(0, packets.trimStart()));
                int start = (int) Math.max(trimStart, Math.min(n, preSkip - first));
                int trimEnd = Math.min(n, Math.max(0, packets.trimEnd()));
                if (heldTail > 0) {
                    // Edge mode: another packet follows the one whose end was held back, so that end is part of the
                    // song after all. It is read first; this packet waits in the interleaved buffer.
                    for (int c = 0; c < channels; c++) {
                        System.arraycopy(planar[c], outEnd, planar[c], 0, heldTail);
                    }
                    outStart = 0;
                    outEnd = heldTail;
                    heldTail = 0;
                    pendingPacket = true;
                    pendingStart = start;
                    pendingSamples = n;
                    pendingTrimEnd = trimEnd;
                    return true;
                }
                if (place(start, n, trimEnd)) {
                    return true;
                }
            }
        } catch (AudioFormatException e) {
            failure = e;
            throw e;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (RuntimeException e) {
            // The decoder is a faithful port of libopus, which takes any packet; this is the safety net that keeps a
            // bug in the port from leaving the caller with an unchecked exception.
            failure = new AudioFormatException("The " + what + " audio is damaged.", e);
            throw failure;
        }
    }

    /**
     * Puts the kept part of the packet decoded into {@code interleaved} ({@code samples} long, kept from {@code start})
     * up for reading, without the {@code trimEnd} samples its container cuts from its end; in edge mode those are held
     * back behind it instead. Returns whether there is something to read now.
     */
    private boolean place(int start, int samples, int trimEnd) {
        int end = Math.max(start, samples - trimEnd);
        int tail = edgesOnly ? samples - end : 0;
        if (end <= start && tail == 0) {
            return false;
        }
        deinterleave(start, end + tail);
        outEnd = end - start;
        heldTail = tail;
        keptAny = true;
        return outEnd > 0;
    }

    private void deinterleave(int start, int end) {
        int count = end - start;
        if (channels == 2) {
            float[] left = planar[0];
            float[] right = planar[1];
            for (int i = 0, j = 2 * start; i < count; i++, j += 2) {
                left[i] = interleaved[j];
                right[i] = interleaved[j + 1];
            }
        } else {
            for (int c = 0; c < channels; c++) {
                float[] p = planar[c];
                for (int i = 0, j = start * channels + c; i < count; i++, j += channels) {
                    p[i] = interleaved[j];
                }
            }
        }
        outStart = 0;
        outEnd = count;
    }

    private static String errorName(int code) {
        return switch (code) {
            case OpusPacketDecoder.OPUS_BAD_ARG -> "invalid argument";
            case OpusPacketDecoder.OPUS_BUFFER_TOO_SMALL -> "a packet longer than 120 ms";
            case OpusPacketDecoder.OPUS_INTERNAL_ERROR -> "a corrupted frame";
            case OpusPacketDecoder.OPUS_INVALID_PACKET -> "an invalid packet";
            default -> "error " + code;
        };
    }
}
