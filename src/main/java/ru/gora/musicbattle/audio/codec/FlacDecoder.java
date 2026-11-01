package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * FLAC decoder in plain Java, written from RFC 9639, so a song picked in FLAC no longer needs ffmpeg.
 *
 * <p>Handles 4 to 32 bits per sample, 1 to 8 channels, every subframe type (constant, verbatim, fixed and LPC with
 * both Rice codings and escaped partitions), wasted bits, the three stereo decorrelations and variable block sizes.
 * An ID3v2 tag in front of {@code fLaC} is skipped, as ffmpeg does. The MD5 in STREAMINFO is not checked.
 *
 * <p>Samples come out exactly as {@code ffmpeg -i song.flac -f f32le} gives them: the integer sample scaled by
 * 2^-(bits-1), rounded to float only for more than 24 bits.
 *
 * <p>The length is what the frames hold, as in ffmpeg, which also ignores STREAMINFO's total when decoding; the total
 * only becomes {@link PcmStream#frameCountHint()}.
 *
 * <p>Damage: a frame header is only believed when its CRC-8 holds and its fields agree with STREAMINFO, so junk
 * between frames is skipped and a lost frame is replaced with silence of its length (the next frame's number says how
 * long), which keeps the beatmap's timing. A frame whose contents cannot be decoded or whose CRC-16 fails is also
 * silence, where ffmpeg would play the damaged frame as noise. A file with no decodable frame at all is an
 * {@link AudioFormatException}.
 */
public final class FlacDecoder {
    /**
     * No legal frame comes near this (65535 samples in 8 channels of 33 bits is about 2.2 MB); damage that claims more
     * is cut off.
     */
    private static final int MAX_FRAME_BYTES = 4 << 20;
    /** The bit reader loads eight bytes at a time, so the buffer keeps this much room past its data. */
    private static final int SLACK = 8;
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    /** A gap longer than this between frames is not filled: the numbering is more likely damaged than the audio. */
    private static final long MAX_GAP_SECONDS = 60;

    private FlacDecoder() {
    }

    /** Whether the first bytes of a file look like FLAC: {@code fLaC}, perhaps behind an ID3v2 tag short enough to see past. */
    public static boolean looksLike(byte[] head) {
        if (head == null) {
            return false;
        }
        int off = 0;
        if (head.length >= 10 && id3v2Length(head) >= 0) {
            off = id3v2Length(head);
            if (off > head.length - 4) {
                return false;
            }
        }
        return head.length >= off + 4 && head[off] == 'f' && head[off + 1] == 'L' && head[off + 2] == 'a' && head[off + 3] == 'C';
    }

    /**
     * Opens a FLAC stream, reading its metadata and decoding the first frame now so that a broken file fails here. On
     * success the returned stream owns {@code in} and closes it; on failure {@code in} is left to the caller.
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return new FlacStream(in);
    }

    /** The whole length of the ID3v2 tag at the start of {@code b}, by ffmpeg's rules, or -1 when it is not one. */
    private static int id3v2Length(byte[] b) {
        if (b[0] != 'I' || b[1] != 'D' || b[2] != '3' || (b[3] & 0xFF) == 0xFF || (b[4] & 0xFF) == 0xFF
                || (b[6] | b[7] | b[8] | b[9]) < 0) {
            return -1;
        }
        int size = (b[6] & 0x7F) << 21 | (b[7] & 0x7F) << 14 | (b[8] & 0x7F) << 7 | (b[9] & 0x7F);
        // Only version 2.4 has a footer, and only there does ffmpeg count it.
        return 10 + size + (b[3] == 4 && (b[5] & 0x10) != 0 ? 10 : 0);
    }

    private static void checkInterrupt() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("FLAC decoding interrupted");
        }
    }

    /** A frame's contents broke a rule of the format; caught inside the frame loop, which turns the frame into silence. */
    private static final class BadFrame extends Exception {
        BadFrame(String message) {
            super(message, null, false, false);
        }
    }

    private static final int[] CRC8 = new int[256];
    private static final int[] CRC16 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c8 = i;
            int c16 = i << 8;
            for (int k = 0; k < 8; k++) {
                c8 = (c8 & 0x80) != 0 ? (c8 << 1) ^ 0x07 : c8 << 1;
                c16 = (c16 & 0x8000) != 0 ? (c16 << 1) ^ 0x8005 : c16 << 1;
            }
            CRC8[i] = c8 & 0xFF;
            CRC16[i] = c16 & 0xFFFF;
        }
    }

    private static final class FlacStream implements PcmStream {
        private final InputStream in;
        private byte[] buf = new byte[(1 << 16) + SLACK];
        private int pos;
        private int end;
        private boolean eof;
        private boolean closed;

        // STREAMINFO
        private int sampleRate;
        private int channels;
        private int bitsPerSample;
        private int minBlockSize;
        private int maxBlockSize;
        private long totalSamples;

        private long[][] samples = new long[8][];
        private final float[][] pcm;
        private int avail;
        private int availOff;
        private long nextSample;     // the first sample the next frame should carry
        private int nominalBlockSize; // a fixed-blocksize stream numbers frames in units of this
        private long gapRemaining;   // silence still owed for frames that were lost
        private boolean ended;

        // the bit reader over the current frame
        private int bitPos;          // absolute bit position in buf
        private int bitLimit;        // bits available in buf

        FlacStream(InputStream in) throws IOException {
            this.in = in;
            readHeader();
            pcm = new float[channels][];
            for (int c = 0; c < channels; c++) {
                pcm[c] = new float[samples[0].length];
            }
            while (avail == 0) {
                if (!nextFrame()) {
                    throw new AudioFormatException("No decodable FLAC frames");
                }
            }
        }

        // ----- buffered input

        /** Buffers up to {@code n} bytes from {@code pos}; fewer only at the end of the stream. */
        private int fill(int n) throws IOException {
            if (end - pos >= n) {
                return n;
            }
            if (!eof) {
                int capacity = buf.length - SLACK;
                if (pos + n > capacity) {
                    int have = end - pos;
                    byte[] target = n > capacity ? new byte[Math.max(n, capacity * 2) + SLACK] : buf;
                    System.arraycopy(buf, pos, target, 0, have);
                    buf = target;
                    capacity = buf.length - SLACK;
                    end = have;
                    pos = 0;
                }
                while (end - pos < n) {
                    checkInterrupt();
                    int r = in.read(buf, end, capacity - end);
                    if (r < 0) {
                        eof = true;
                        break;
                    }
                    if (r == 0) {
                        int b = in.read();
                        if (b < 0) {
                            eof = true;
                            break;
                        }
                        buf[end++] = (byte) b;
                    } else {
                        end += r;
                    }
                }
            }
            return Math.min(n, end - pos);
        }

        private int u8(int i) {
            return buf[pos + i] & 0xFF;
        }

        private void skip(long n) throws IOException {
            while (n > 0) {
                int have = fill((int) Math.min(n, 1 << 16));
                if (have == 0) {
                    throw new AudioFormatException("FLAC stream ends inside its metadata");
                }
                pos += have;
                n -= have;
            }
        }

        private void readHeader() throws IOException {
            while (fill(10) >= 10) {
                int len = id3v2Length(Arrays.copyOfRange(buf, pos, pos + 10));
                if (len < 0) {
                    break;
                }
                skip(len);
            }
            if (fill(4) < 4 || u8(0) != 'f' || u8(1) != 'L' || u8(2) != 'a' || u8(3) != 'C') {
                throw new AudioFormatException("Not a FLAC stream");
            }
            pos += 4;
            boolean last;
            boolean sawInfo = false;
            do {
                if (fill(4) < 4) {
                    throw new AudioFormatException("FLAC stream ends inside its metadata");
                }
                last = (u8(0) & 0x80) != 0;
                int type = u8(0) & 0x7F;
                int length = u8(1) << 16 | u8(2) << 8 | u8(3);
                pos += 4;
                if (type == 127) {
                    throw new AudioFormatException("Invalid FLAC metadata block");
                }
                if (type == 0 && !sawInfo) {
                    if (length < 34 || fill(34) < 34) {
                        throw new AudioFormatException("Damaged FLAC STREAMINFO");
                    }
                    minBlockSize = u8(0) << 8 | u8(1);
                    maxBlockSize = u8(2) << 8 | u8(3);
                    sampleRate = u8(10) << 12 | u8(11) << 4 | u8(12) >> 4;
                    channels = ((u8(12) >> 1) & 7) + 1;
                    bitsPerSample = (((u8(12) & 1) << 4) | (u8(13) >> 4)) + 1;
                    totalSamples = (long) (u8(13) & 0x0F) << 32 | (long) u8(14) << 24 | u8(15) << 16 | u8(16) << 8 | u8(17);
                    sawInfo = true;
                    skip(length);
                } else {
                    skip(length);
                }
            } while (!last);
            if (!sawInfo) {
                throw new AudioFormatException("FLAC stream without STREAMINFO");
            }
            if (sampleRate == 0 || bitsPerSample < 4) {
                throw new AudioFormatException("Unsupported FLAC STREAMINFO");
            }
            // STREAMINFO's maximum only sizes the first buffers: a frame larger than it promised still decodes.
            int capacity = Math.max(16, maxBlockSize == 0 ? 4096 : maxBlockSize);
            for (int c = 0; c < channels; c++) {
                samples[c] = new long[capacity];
            }
        }

        // ----- frames

        private static final int[] BLOCK_SIZES = {0, 192, 576, 1152, 2304, 4608, -1, -2, 256, 512, 1024, 2048, 4096,
                8192, 16384, 32768};
        private static final int[] SAMPLE_RATES = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100,
                48000, 96000, -1, -2, -3, -4};
        private static final int[] SAMPLE_SIZES = {0, 8, 12, -1, 16, 20, 24, 32};

        private int frameBlockSize;
        private int frameChannelMode;
        private int frameBps;
        private long frameFirstSample;
        private int headerLength;

        /**
         * Reads a frame header at {@code pos} without consuming it. False when the bytes there are not a frame header
         * this stream could have written: a bad CRC-8, a reserved code, or values that contradict STREAMINFO.
         */
        private boolean parseHeader() throws IOException {
            int have = fill(16);
            if (have < 6 || u8(0) != 0xFF || (u8(1) & 0xFE) != 0xF8) {
                return false;
            }
            boolean variable = (u8(1) & 1) != 0;
            int bsCode = u8(2) >> 4;
            int srCode = u8(2) & 0x0F;
            int chCode = u8(3) >> 4;
            int ssCode = (u8(3) >> 1) & 7;
            if (bsCode == 0 || srCode == 15 || chCode > 10 || ssCode == 3 || (u8(3) & 1) != 0) {
                return false;
            }
            int p = 4;
            int first = u8(p++);
            int extra;
            long number;
            if (first < 0x80) {
                number = first;
                extra = 0;
            } else if (first >= 0xC0 && first < 0xE0) {
                number = first & 0x1F;
                extra = 1;
            } else if (first >= 0xE0 && first < 0xF0) {
                number = first & 0x0F;
                extra = 2;
            } else if (first >= 0xF0 && first < 0xF8) {
                number = first & 0x07;
                extra = 3;
            } else if (first >= 0xF8 && first < 0xFC) {
                number = first & 0x03;
                extra = 4;
            } else if (first >= 0xFC && first < 0xFE) {
                number = first & 0x01;
                extra = 5;
            } else if (first == 0xFE) {
                number = 0;
                extra = 6;
            } else {
                return false;
            }
            if (!variable && extra > 5) {
                return false;
            }
            if (have < p + extra + 5) {
                have = fill(p + extra + 5);
                if (have < p + extra + 1) {
                    return false;
                }
            }
            for (int i = 0; i < extra; i++) {
                int b = u8(p++);
                if ((b & 0xC0) != 0x80) {
                    return false;
                }
                number = number << 6 | (b & 0x3F);
            }
            int blockSize = BLOCK_SIZES[bsCode];
            if (blockSize == -1) {
                if (fill(p + 1) < p + 1) {
                    return false;
                }
                blockSize = u8(p++) + 1;
            } else if (blockSize == -2) {
                if (fill(p + 2) < p + 2) {
                    return false;
                }
                blockSize = (u8(p) << 8 | u8(p + 1)) + 1;
                p += 2;
            }
            int rate = SAMPLE_RATES[srCode];
            if (rate < 0) {
                int bytes = rate == -1 ? 1 : 2;
                if (fill(p + bytes) < p + bytes) {
                    return false;
                }
                int v = bytes == 1 ? u8(p) : u8(p) << 8 | u8(p + 1);
                p += bytes;
                rate = rate == -1 ? v * 1000 : rate == -2 ? v : v * 10;
            }
            if (fill(p + 1) < p + 1) {
                return false;
            }
            int crc = 0;
            for (int i = 0; i < p; i++) {
                crc = CRC8[crc ^ u8(i)];
            }
            if (crc != u8(p)) {
                return false;
            }
            int bps = SAMPLE_SIZES[ssCode];
            if (bps == 0) {
                bps = bitsPerSample;
            }
            int nch = chCode < 8 ? chCode + 1 : 2;
            // A header that disagrees with STREAMINFO is a false sync inside audio data, or a stream this decoder
            // cannot give one shape to; either way it is not a frame of this stream.
            if (nch != channels || bps != bitsPerSample || (rate != 0 && rate != sampleRate)) {
                return false;
            }
            frameBlockSize = blockSize;
            frameChannelMode = chCode;
            frameBps = bps;
            if (!variable && nominalBlockSize == 0) {
                nominalBlockSize = minBlockSize == maxBlockSize && maxBlockSize != 0 ? maxBlockSize : blockSize;
            }
            frameFirstSample = variable ? number : number * nominalBlockSize;
            headerLength = p + 1;
            return true;
        }

        /** Finds and decodes the next frame (or owed silence) into {@code pcm}; false at the end of the stream. */
        private boolean nextFrame() throws IOException {
            if (ended) {
                return false;
            }
            if (gapRemaining > 0) {
                emitSilence();
                return true;
            }
            int scanned = 0;
            while (true) {
                if (fill(2) < 2) {
                    ended = true;
                    return false;
                }
                if (parseHeader()) {
                    break;
                }
                pos++;
                if ((++scanned & 0xFFFF) == 0) {
                    checkInterrupt();
                }
            }
            checkInterrupt();
            if (frameFirstSample > nextSample && frameFirstSample - nextSample <= MAX_GAP_SECONDS * sampleRate
                    && (totalSamples == 0 || frameFirstSample < totalSamples)) {
                // Frames went missing between the last good one and this one: their length is owed as silence first,
                // and this header is read again once it has been paid.
                gapRemaining = frameFirstSample - nextSample;
                emitSilence();
                return true;
            }
            int blockSize = frameBlockSize;
            if (blockSize > samples[0].length) {
                for (int c = 0; c < channels; c++) {
                    samples[c] = new long[blockSize];
                    pcm[c] = new float[blockSize];
                }
            }
            try {
                decodeFrame();
            } catch (BadFrame e) {
                // The header was good, the body was not: its block is silence, and the search for the next frame starts
                // just past this header (decodeFrame leaves pos on it) so a frame hiding inside the damage is found.
                pos += 2;
                for (int c = 0; c < channels; c++) {
                    Arrays.fill(pcm[c], 0, blockSize, 0f);
                }
            }
            nextSample = frameFirstSample + blockSize;
            availOff = 0;
            avail = blockSize;
            return true;
        }

        private void emitSilence() {
            int n = (int) Math.min(gapRemaining, pcm[0].length);
            for (int c = 0; c < channels; c++) {
                Arrays.fill(pcm[c], 0, n, 0f);
            }
            gapRemaining -= n;
            nextSample += n;
            availOff = 0;
            avail = n;
        }

        private void decodeFrame() throws IOException, BadFrame {
            // A frame's length is only known once it is decoded; most of one is buffered now and the bit reader asks
            // for more if it runs out.
            fill(headerLength + channels * (frameBlockSize * frameBps / 8 + 64));
            bitPos = (pos + headerLength) * 8;
            bitLimit = end * 8;
            int bs = frameBlockSize;
            for (int c = 0; c < channels; c++) {
                int bps = frameBps;
                if (frameChannelMode == 8 && c == 1 || frameChannelMode == 9 && c == 0 || frameChannelMode == 10 && c == 1) {
                    bps++;
                }
                decodeSubframe(samples[c], bs, bps);
            }
            bitPos = (bitPos + 7) & ~7;
            int crcEnd = bitPos >> 3;
            if (crcEnd + 2 > end && !more(16)) {
                throw new BadFrame("FLAC frame runs past the end of the stream");
            }
            crcEnd = bitPos >> 3;
            // ffmpeg plays a frame whose CRC-16 fails; here it is silence instead. Its own parser finds where the next
            // frame starts by itself, but here the end of a damaged frame is only where decoding stopped, and the next
            // header is looked for from the damaged one's.
            int crc = 0;
            for (int i = pos; i < crcEnd; i++) {
                crc = (crc << 8 & 0xFFFF) ^ CRC16[(crc >> 8) ^ (buf[i] & 0xFF)];
            }
            if (crc != ((buf[crcEnd] & 0xFF) << 8 | (buf[crcEnd + 1] & 0xFF))) {
                throw new BadFrame("FLAC frame CRC-16 mismatch");
            }
            pos = crcEnd + 2;
            decorrelate(bs);
            toFloat(bs);
        }

        private void decorrelate(int bs) {
            long[] a = samples[0];
            long[] b = channels > 1 ? samples[1] : null;
            switch (frameChannelMode) {
                case 8 -> {
                    for (int i = 0; i < bs; i++) {
                        b[i] = a[i] - b[i];
                    }
                }
                case 9 -> {
                    for (int i = 0; i < bs; i++) {
                        a[i] += b[i];
                    }
                }
                case 10 -> {
                    for (int i = 0; i < bs; i++) {
                        long mid = a[i] << 1 | (b[i] & 1);
                        long side = b[i];
                        a[i] = (mid + side) >> 1;
                        b[i] = (mid - side) >> 1;
                    }
                }
                default -> {
                }
            }
        }

        private void toFloat(int bs) {
            float scale = 1f / (1L << (bitsPerSample - 1));
            for (int c = 0; c < channels; c++) {
                long[] s = samples[c];
                float[] out = pcm[c];
                for (int i = 0; i < bs; i++) {
                    out[i] = (float) s[i] * scale;
                }
            }
        }

        // ----- bit reading inside the buffered frame

        /** Buffers at least {@code bits} more bits of the current frame; false when the stream ends first. */
        private boolean more(int bits) throws IOException, BadFrame {
            if (eof) {
                return bitPos + bits <= bitLimit;
            }
            int frameBytes = end - pos;
            if (frameBytes > MAX_FRAME_BYTES) {
                throw new BadFrame("FLAC frame is impossibly long");
            }
            int oldPos = pos;
            fill(frameBytes + Math.max(1 << 16, (bits + 7) / 8));
            bitPos -= (oldPos - pos) * 8;
            bitLimit = end * 8;
            return bitPos + bits <= bitLimit;
        }

        private long bits(int n) throws IOException, BadFrame {
            if (bitPos + n > bitLimit && !more(n)) {
                throw new BadFrame("FLAC frame runs past its data");
            }
            if (n == 0) {
                return 0;
            }
            long w = (long) LONG_BE.get(buf, bitPos >> 3);
            long v = (w << (bitPos & 7)) >>> (64 - n);
            bitPos += n;
            return v;
        }

        private long signed(int n) throws IOException, BadFrame {
            if (n == 0) {
                return 0;
            }
            long v = bits(n);
            return v << (64 - n) >> (64 - n);
        }

        private long unary() throws IOException, BadFrame {
            long count = 0;
            while (true) {
                if (bitPos >= bitLimit && !more(1)) {
                    throw new BadFrame("FLAC frame runs past its data");
                }
                int off = bitPos & 7;
                long w = (long) LONG_BE.get(buf, bitPos >> 3) << off;
                int real = Math.min(64 - off, bitLimit - bitPos);
                int zeros = Long.numberOfLeadingZeros(w);
                if (zeros >= real) {
                    count += real;
                    bitPos += real;
                    continue;
                }
                count += zeros;
                bitPos += zeros + 1;
                return count;
            }
        }

        // ----- subframes

        private void decodeSubframe(long[] out, int bs, int bps) throws IOException, BadFrame {
            if (bits(1) != 0) {
                throw new BadFrame("FLAC subframe padding bit set");
            }
            int type = (int) bits(6);
            int wasted = 0;
            if (bits(1) != 0) {
                wasted = (int) Math.min(unary() + 1, 64);
                if (wasted >= bps) {
                    throw new BadFrame("FLAC wasted bits exceed the sample size");
                }
                bps -= wasted;
            }
            if (type == 0) {
                long v = signed(bps);
                Arrays.fill(out, 0, bs, v);
            } else if (type == 1) {
                for (int i = 0; i < bs; i++) {
                    out[i] = signed(bps);
                }
            } else if (type >= 8 && type <= 12) {
                int order = type - 8;
                if (order > bs) {
                    throw new BadFrame("FLAC predictor order exceeds the block");
                }
                for (int i = 0; i < order; i++) {
                    out[i] = signed(bps);
                }
                residual(out, bs, order);
                fixedPredict(out, bs, order);
            } else if (type >= 32) {
                int order = type - 31;
                if (order > bs) {
                    throw new BadFrame("FLAC predictor order exceeds the block");
                }
                for (int i = 0; i < order; i++) {
                    out[i] = signed(bps);
                }
                int precision = (int) bits(4);
                if (precision == 15) {
                    throw new BadFrame("FLAC LPC precision code 15 is invalid");
                }
                precision++;
                int shift = (int) signed(5);
                if (shift < 0) {
                    throw new BadFrame("FLAC LPC shift is negative");
                }
                long[] coef = new long[order];
                for (int i = 0; i < order; i++) {
                    coef[i] = signed(precision);
                }
                residual(out, bs, order);
                lpcPredict(out, bs, order, coef, shift);
            } else {
                throw new BadFrame("Reserved FLAC subframe type " + type);
            }
            if (wasted > 0) {
                for (int i = 0; i < bs; i++) {
                    out[i] <<= wasted;
                }
            }
        }

        /** Reads the residual into {@code out[order..bs)}. */
        private void residual(long[] out, int bs, int order) throws IOException, BadFrame {
            int method = (int) bits(2);
            if (method > 1) {
                throw new BadFrame("Reserved FLAC residual coding");
            }
            int paramBits = method == 0 ? 4 : 5;
            int escape = method == 0 ? 15 : 31;
            int partitionOrder = (int) bits(4);
            int partitions = 1 << partitionOrder;
            if ((bs & (partitions - 1)) != 0 || (bs >> partitionOrder) < order) {
                throw new BadFrame("FLAC partition order does not fit the block");
            }
            int partSize = bs >> partitionOrder;
            int i = order;
            for (int p = 0; p < partitions; p++) {
                int count = p == 0 ? partSize - order : partSize;
                int k = (int) bits(paramBits);
                if (k == escape) {
                    int raw = (int) bits(5);
                    for (int j = 0; j < count; j++) {
                        out[i++] = signed(raw);
                    }
                } else {
                    for (int j = 0; j < count; j++) {
                        long q = unary();
                        if (q > (Long.MAX_VALUE >>> 1 >> k)) {
                            throw new BadFrame("FLAC residual out of range");
                        }
                        long v = q << k | bits(k);
                        out[i++] = (v >>> 1) ^ -(v & 1);
                    }
                }
            }
        }

        private static void fixedPredict(long[] s, int bs, int order) {
            switch (order) {
                case 1 -> {
                    for (int i = 1; i < bs; i++) {
                        s[i] += s[i - 1];
                    }
                }
                case 2 -> {
                    for (int i = 2; i < bs; i++) {
                        s[i] += 2 * s[i - 1] - s[i - 2];
                    }
                }
                case 3 -> {
                    for (int i = 3; i < bs; i++) {
                        s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3];
                    }
                }
                case 4 -> {
                    for (int i = 4; i < bs; i++) {
                        s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4];
                    }
                }
                default -> {
                }
            }
        }

        private static void lpcPredict(long[] s, int bs, int order, long[] coef, int shift) {
            for (int i = order; i < bs; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) {
                    sum += coef[j] * s[i - 1 - j];
                }
                s[i] += sum >> shift;
            }
        }

        // ----- PcmStream

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
            return totalSamples > 0 ? totalSamples : -1L;
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (frames <= 0) {
                return 0;
            }
            while (avail == 0) {
                if (!nextFrame()) {
                    return -1;
                }
            }
            int n = Math.min(frames, avail);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(pcm[c], availOff, out[c], offset, n);
            }
            availOff += n;
            avail -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                in.close();
            }
        }
    }
}
