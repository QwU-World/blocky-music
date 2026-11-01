package ru.gora.musicbattle.audio.codec;

/**
 * Apple Lossless (ALAC) frame decoder, a Java port of Apple's reference decoder: ALACDecoder.cpp, ag_dec.c (adaptive
 * Golomb), dp_dec.c (dynamic predictor), matrix_dec.c (stereo unmixing) and ALACBitUtilities.c from
 * https://github.com/macosforge/alac at commit c38887c5c5e64a4b31108733bd79ca9b2496d987, Copyright (c) 2011 Apple
 * Inc., Apache License 2.0.
 *
 * <p>The arithmetic is Apple's, integer for integer, so the decoded samples are exactly the encoder's input. The
 * port keeps the reference's structure and names. Where it differs, the comment there says why: every read is
 * bounds-checked against the packet (the C code trusts the encoder), a frame may not claim more samples than the
 * stream's frame length, and the four-coefficient and eight-coefficient fast paths of {@code unpc_block} are folded
 * into the general loop, which computes the same values.
 *
 * <p>Output is float in [-1, 1) the way ffmpeg converts ALAC: 16-bit samples over 2^15, 20 and 24-bit ones over
 * their own full scale, 32-bit ones rounded to float and scaled by 2^-31. More than two channels are reordered from
 * Apple's layouts (C L R ...) into the WAVE order ffmpeg gives (L R C LFE ...).
 */
final class AlacDecoder {
    private static final int ID_SCE = 0;
    private static final int ID_CPE = 1;
    private static final int ID_CCE = 2;
    private static final int ID_LFE = 3;
    private static final int ID_DSE = 4;
    private static final int ID_PCE = 5;
    private static final int ID_FIL = 6;
    private static final int ID_END = 7;

    // aglib.h
    private static final int QBSHIFT = 9;
    private static final int QB = 1 << QBSHIFT;
    private static final int MMULSHIFT = 2;
    private static final int MDENSHIFT = QBSHIFT - MMULSHIFT - 1;
    private static final int MOFF = 1 << (MDENSHIFT - 2);
    private static final int BITOFF = 24;
    private static final int MAX_PREFIX_16 = 9;
    private static final int MAX_PREFIX_32 = 9;
    private static final int MAX_DATATYPE_BITS_16 = 16;
    private static final int N_MAX_MEAN_CLAMP = 0xffff;
    private static final int N_MEAN_CLAMP_VAL = 0xffff;
    private static final int K_ALAC_VERSION = 0;
    private static final int MAX_CHANNELS = 8;

    /** Apple's channel order (C L R ...) to the WAVE order, by channel count: output c takes input ORDER[n][c]. */
    private static final int[][] ORDER = {
            {}, {0}, {0, 1}, {1, 2, 0}, {1, 2, 0, 3}, {1, 2, 0, 3, 4}, {1, 2, 0, 5, 3, 4}, {1, 2, 0, 6, 5, 3, 4},
            {3, 4, 0, 7, 5, 6, 1, 2}};

    // ALACSpecificConfig
    final int frameLength;
    final int bitDepth;
    final int pb;
    final int mb;
    final int kb;
    final int numChannels;
    final int maxRun;
    final int sampleRate;

    private final int[] mixBufferU;
    private final int[] mixBufferV;
    private final int[] predictor;
    private final int[] shiftBuffer;
    private final short[] coefsU = new short[32];
    private final short[] coefsV = new short[32];
    /** Decoded integer samples, Apple's order, before conversion: [channel][sample]. */
    private final int[][] decoded;

    // The bit buffer: Apple's BitBuffer as a byte array, a bit position and an end.
    private byte[] data;
    private int bitPos;
    private int endBit;

    /**
     * Reads the ALACSpecificConfig ("magic cookie"), optionally wrapped in 'frma' and 'alac' atoms as older encoders
     * vended it.
     */
    AlacDecoder(byte[] cookie) throws AudioFormatException {
        if (cookie == null) {
            throw new AudioFormatException("The ALAC track has no configuration");
        }
        int p = 0;
        if (cookie.length >= 12 && cookie[4] == 'f' && cookie[5] == 'r' && cookie[6] == 'm' && cookie[7] == 'a') {
            p += 12;
        }
        if (cookie.length >= p + 12 && cookie[p + 4] == 'a' && cookie[p + 5] == 'l' && cookie[p + 6] == 'a'
                && cookie[p + 7] == 'c') {
            p += 12;
        }
        if (cookie.length - p < 24) {
            throw new AudioFormatException("The ALAC configuration is too short");
        }
        frameLength = (int) Mp4Demuxer.u32(cookie, p);
        int compatibleVersion = cookie[p + 4] & 0xFF;
        bitDepth = cookie[p + 5] & 0xFF;
        pb = cookie[p + 6] & 0xFF;
        mb = cookie[p + 7] & 0xFF;
        kb = cookie[p + 8] & 0xFF;
        numChannels = cookie[p + 9] & 0xFF;
        maxRun = Mp4Demuxer.u16(cookie, p + 10);
        sampleRate = (int) Mp4Demuxer.u32(cookie, p + 20);
        if (compatibleVersion > K_ALAC_VERSION) {
            throw new UnsupportedFormatException("ALAC version " + compatibleVersion);
        }
        if (bitDepth != 16 && bitDepth != 20 && bitDepth != 24 && bitDepth != 32) {
            throw new AudioFormatException("Unsupported ALAC bit depth " + bitDepth);
        }
        if (numChannels < 1 || numChannels > MAX_CHANNELS) {
            throw new AudioFormatException("Unsupported ALAC channel count " + numChannels);
        }
        if (frameLength < 1 || frameLength > 1 << 16) {
            throw new AudioFormatException("Unsupported ALAC frame length " + frameLength);
        }
        if (sampleRate < 1 || sampleRate > 768000) {
            throw new AudioFormatException("Unsupported ALAC sampling rate " + sampleRate);
        }
        if (kb > 32 || pb > 255) {
            throw new AudioFormatException("Damaged ALAC configuration");
        }
        mixBufferU = new int[frameLength];
        mixBufferV = new int[frameLength];
        predictor = new int[frameLength];
        shiftBuffer = new int[frameLength * 2];
        decoded = new int[numChannels][frameLength];
    }

    int channels() {
        return numChannels;
    }

    private static AudioFormatException damaged(String what) {
        return new AudioFormatException("Damaged ALAC frame: " + what);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ALACBitUtilities.c

    private void need(int bit, int bytes) throws AudioFormatException {
        // The C code reads up to five bytes past the bit position without looking; here they must exist.
        if (bit < 0 || (bit >>> 3) + bytes > data.length) {
            throw damaged("reads past the end of the packet");
        }
    }

    /** BitBufferRead: up to 16 bits. */
    private int read(int numBits) throws AudioFormatException {
        need(bitPos, 3);
        int at = bitPos >>> 3;
        int v = (data[at] & 0xFF) << 16 | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF);
        v = (v << (bitPos & 7)) & 0x00FFFFFF;
        bitPos += numBits;
        return v >>> (24 - numBits);
    }

    private int readSmall(int numBits) throws AudioFormatException {
        need(bitPos, 2);
        int at = bitPos >>> 3;
        int v = ((data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF)) << (bitPos & 7);
        bitPos += numBits;
        return (v & 0xFFFF) >>> (16 - numBits);
    }

    private int readOne() throws AudioFormatException {
        need(bitPos, 1);
        int v = (data[bitPos >>> 3] >> (7 - (bitPos & 7))) & 1;
        bitPos++;
        return v;
    }

    private void byteAlign() {
        bitPos = (bitPos + 7) & ~7;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ALACDecoder.cpp

    /**
     * Decodes one ALAC packet into {@code out[c][offset ..]} in the WAVE channel order.
     *
     * @param packet must carry {@link AacBits#PADDING} bytes after the packet
     * @return the number of samples per channel
     */
    int decode(byte[] packet, int offset, int length, float[][] out, int outOffset) throws AudioFormatException {
        data = packet;
        bitPos = offset * 8;
        endBit = (offset + length) * 8;
        int numSamples = frameLength;
        int channelIndex = 0;
        while (true) {
            if (bitPos >= endBit) {
                throw damaged("ran off the end of the packet");
            }
            int tag = readSmall(3);
            switch (tag) {
                case ID_SCE, ID_LFE -> {
                    numSamples = decodeSce(numSamples, channelIndex);
                    channelIndex += 1;
                }
                case ID_CPE -> {
                    if (channelIndex + 2 > numChannels) {
                        // Decoding this pair would go past the channel count: the reference stops here.
                        return finish(numSamples, channelIndex, out, outOffset);
                    }
                    numSamples = decodeCpe(numSamples, channelIndex);
                    channelIndex += 2;
                }
                case ID_CCE, ID_PCE -> throw damaged("unsupported element");
                case ID_DSE -> dataStreamElement();
                case ID_FIL -> fillElement();
                default -> {
                    // ID_END: frame end.
                    byteAlign();
                    return finish(numSamples, channelIndex, out, outOffset);
                }
            }
            if (bitPos > endBit) {
                throw damaged("an element runs past the end of the packet");
            }
            // The release build stops once every channel is decoded; this also protects against a config that does
            // not match the bitstream.
            if (channelIndex >= numChannels) {
                return finish(numSamples, channelIndex, out, outOffset);
            }
        }
    }

    private int decodeSce(int numSamples, int channelIndex) throws AudioFormatException {
        if (channelIndex >= numChannels) {
            throw damaged("more channels than the configuration");
        }
        readSmall(4); // element instance tag
        int unusedHeader = read(12);
        if (unusedHeader != 0) {
            throw damaged("non-zero reserved bits");
        }
        int headerByte = read(4);
        int partialFrame = headerByte >> 3;
        int bytesShifted = (headerByte >> 1) & 3;
        if (bytesShifted == 3) {
            throw damaged("shift of three bytes");
        }
        int escapeFlag = headerByte & 1;
        int chanBits = bitDepth - bytesShifted * 8;
        if (partialFrame != 0) {
            numSamples = read(16) << 16;
            numSamples |= read(16);
            checkSamples(numSamples);
        }
        int shiftStart = 0;
        if (escapeFlag == 0) {
            int mixBits = read(8);
            int mixRes = (byte) read(8);
            headerByte = read(8);
            int modeU = headerByte >> 4;
            int denShiftU = headerByte & 0xf;
            headerByte = read(8);
            int pbFactorU = headerByte >> 5;
            int numU = headerByte & 0x1f;
            for (int i = 0; i < numU; i++) {
                coefsU[i] = (short) read(16);
            }
            if (bytesShifted != 0) {
                shiftStart = bitPos;
                bitPos += bytesShifted * 8 * numSamples;
            }
            dynDecomp(mb, (pb * pbFactorU) / 4, kb, numSamples, chanBits);
            if (modeU == 0) {
                unpcBlock(predictor, mixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
            } else {
                unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
                unpcBlock(predictor, mixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
            }
        } else {
            readUncompressed(mixBufferU, null, numSamples, chanBits);
            bytesShifted = 0;
        }
        if (bytesShifted != 0) {
            int saved = bitPos;
            bitPos = shiftStart;
            int shift = bytesShifted * 8;
            for (int i = 0; i < numSamples; i++) {
                shiftBuffer[i] = read(shift);
            }
            bitPos = saved;
        }
        int[] target = decoded[channelIndex];
        int shift = bytesShifted * 8;
        for (int i = 0; i < numSamples; i++) {
            int v = mixBufferU[i];
            target[i] = bytesShifted != 0 && bitDepth >= 24 ? (v << shift) | shiftBuffer[i] : v;
        }
        return numSamples;
    }

    private int decodeCpe(int numSamples, int channelIndex) throws AudioFormatException {
        readSmall(4); // element instance tag
        int unusedHeader = read(12);
        if (unusedHeader != 0) {
            throw damaged("non-zero reserved bits");
        }
        int headerByte = read(4);
        int partialFrame = headerByte >> 3;
        int bytesShifted = (headerByte >> 1) & 3;
        if (bytesShifted == 3) {
            throw damaged("shift of three bytes");
        }
        int escapeFlag = headerByte & 1;
        int chanBits = bitDepth - bytesShifted * 8 + 1;
        if (partialFrame != 0) {
            numSamples = read(16) << 16;
            numSamples |= read(16);
            checkSamples(numSamples);
        }
        int mixBits;
        int mixRes;
        int shiftStart = 0;
        if (escapeFlag == 0) {
            mixBits = read(8);
            mixRes = (byte) read(8);
            headerByte = read(8);
            int modeU = headerByte >> 4;
            int denShiftU = headerByte & 0xf;
            headerByte = read(8);
            int pbFactorU = headerByte >> 5;
            int numU = headerByte & 0x1f;
            for (int i = 0; i < numU; i++) {
                coefsU[i] = (short) read(16);
            }
            headerByte = read(8);
            int modeV = headerByte >> 4;
            int denShiftV = headerByte & 0xf;
            headerByte = read(8);
            int pbFactorV = headerByte >> 5;
            int numV = headerByte & 0x1f;
            for (int i = 0; i < numV; i++) {
                coefsV[i] = (short) read(16);
            }
            if (bytesShifted != 0) {
                shiftStart = bitPos;
                bitPos += bytesShifted * 8 * 2 * numSamples;
            }
            dynDecomp(mb, (pb * pbFactorU) / 4, kb, numSamples, chanBits);
            if (modeU == 0) {
                unpcBlock(predictor, mixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
            } else {
                unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
                unpcBlock(predictor, mixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
            }
            dynDecomp(mb, (pb * pbFactorV) / 4, kb, numSamples, chanBits);
            if (modeV == 0) {
                unpcBlock(predictor, mixBufferV, numSamples, coefsV, numV, chanBits, denShiftV);
            } else {
                unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
                unpcBlock(predictor, mixBufferV, numSamples, coefsV, numV, chanBits, denShiftV);
            }
        } else {
            readUncompressed(mixBufferU, mixBufferV, numSamples, bitDepth);
            mixBits = 0;
            mixRes = 0;
            bytesShifted = 0;
        }
        if (bytesShifted != 0) {
            int saved = bitPos;
            bitPos = shiftStart;
            int shift = bytesShifted * 8;
            for (int i = 0; i < numSamples * 2; i += 2) {
                shiftBuffer[i] = read(shift);
                shiftBuffer[i + 1] = read(shift);
            }
            bitPos = saved;
        }
        // matrix_dec.c: unmix16/20/24/32. mixres 0 just interleaves. The shifted-off low bytes only exist for 24 and
        // 32-bit streams; unmix32 would also OR them in for a matrixed frame with no shift, where the reference
        // reads whatever its aliased buffer last held, a combination Apple's encoder never writes.
        int[] left = decoded[channelIndex];
        int[] right = decoded[channelIndex + 1];
        int shift = bytesShifted * 8;
        boolean withShift = bytesShifted != 0 && bitDepth >= 24;
        for (int j = 0, k = 0; j < numSamples; j++, k += 2) {
            int l;
            int r;
            if (mixRes != 0) {
                l = mixBufferU[j] + mixBufferV[j] - ((mixRes * mixBufferV[j]) >> mixBits);
                r = l - mixBufferV[j];
            } else {
                l = mixBufferU[j];
                r = mixBufferV[j];
            }
            if (withShift) {
                l = (l << shift) | shiftBuffer[k];
                r = (r << shift) | shiftBuffer[k + 1];
            }
            left[j] = l;
            right[j] = r;
        }
        return numSamples;
    }

    private void checkSamples(int numSamples) throws AudioFormatException {
        // The reference trusts this field and writes past its buffers when it lies.
        if (numSamples < 1 || numSamples > frameLength) {
            throw damaged("a frame of " + numSamples + " samples in a stream of " + frameLength);
        }
    }

    /** An escaped (uncompressed) element: samples stored verbatim, interleaved for a pair. */
    private void readUncompressed(int[] u, int[] v, int numSamples, int chanBits) throws AudioFormatException {
        int shift = 32 - chanBits;
        if (chanBits <= 16) {
            for (int i = 0; i < numSamples; i++) {
                u[i] = (read(chanBits) << shift) >> shift;
                if (v != null) {
                    v[i] = (read(chanBits) << shift) >> shift;
                }
            }
        } else {
            int extraBits = chanBits - 16;
            for (int i = 0; i < numSamples; i++) {
                int val = (read(16) << 16) >> shift;
                u[i] = val | read(extraBits);
                if (v != null) {
                    val = (read(16) << 16) >> shift;
                    v[i] = val | read(extraBits);
                }
            }
        }
    }

    private void fillElement() throws AudioFormatException {
        int count = readSmall(4);
        if (count == 15) {
            count += readSmall(8) - 1;
        }
        bitPos += count * 8;
        if (bitPos > endBit) {
            throw damaged("fill element past the end");
        }
    }

    private void dataStreamElement() throws AudioFormatException {
        readSmall(4);
        int align = readOne();
        int count = readSmall(8);
        if (count == 255) {
            count += readSmall(8);
        }
        if (align != 0) {
            byteAlign();
        }
        bitPos += count * 8;
        if (bitPos > endBit) {
            throw damaged("data stream element past the end");
        }
    }

    /** Fills channels the packet did not carry with zeros, converts and reorders. */
    private int finish(int numSamples, int channelIndex, float[][] out, int outOffset) {
        for (int c = channelIndex; c < numChannels; c++) {
            java.util.Arrays.fill(decoded[c], 0, numSamples, 0);
        }
        int[] order = ORDER[numChannels];
        for (int c = 0; c < numChannels; c++) {
            int[] in = decoded[order[c]];
            float[] o = out[c];
            switch (bitDepth) {
                case 16 -> {
                    for (int i = 0; i < numSamples; i++) {
                        o[outOffset + i] = (short) in[i] * (1.0f / (1 << 15));
                    }
                }
                case 20 -> {
                    for (int i = 0; i < numSamples; i++) {
                        // copyPredictorTo20 / unmix20 store the sample shifted into a 24-bit container.
                        o[outOffset + i] = ((in[i] << 12) >> 8) * (1.0f / (1 << 23));
                    }
                }
                case 24 -> {
                    for (int i = 0; i < numSamples; i++) {
                        o[outOffset + i] = ((in[i] << 8) >> 8) * (1.0f / (1 << 23));
                    }
                }
                default -> {
                    for (int i = 0; i < numSamples; i++) {
                        o[outOffset + i] = (float) in[i] * (1.0f / (1L << 31));
                    }
                }
            }
        }
        return numSamples;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ag_dec.c

    private static int lead(int m) {
        return Integer.numberOfLeadingZeros(m);
    }

    private static int lg3a(int x) {
        return 31 - lead(x + 3);
    }

    private int read32bit(int byteAt) throws AudioFormatException {
        if (byteAt < 0 || byteAt + 4 > data.length) {
            throw damaged("reads past the end of the packet");
        }
        return (data[byteAt] & 0xFF) << 24 | (data[byteAt + 1] & 0xFF) << 16 | (data[byteAt + 2] & 0xFF) << 8
                | (data[byteAt + 3] & 0xFF);
    }

    private int getStreamBits(int bitoffset, int numbits) throws AudioFormatException {
        int byteoffset = bitoffset >>> 3;
        int load1 = read32bit(byteoffset);
        int result;
        if (numbits + (bitoffset & 7) > 32) {
            if (byteoffset + 5 > data.length) {
                throw damaged("reads past the end of the packet");
            }
            result = load1 << (bitoffset & 7);
            int load2 = data[byteoffset + 4] & 0xFF;
            int load2shift = 8 - (numbits + (bitoffset & 7) - 32);
            load2 >>>= load2shift;
            result >>>= 32 - numbits;
            result |= load2;
        } else {
            result = load1 >>> (32 - numbits - (bitoffset & 7));
        }
        if (numbits != 32) {
            result &= ~(0xffffffff << numbits);
        }
        return result;
    }

    private int dynGetPos;

    /** dyn_get: the run-length code for zero runs. */
    private int dynGet(int m, int k) throws AudioFormatException {
        int tempbits = dynGetPos;
        int streamlong = read32bit(tempbits >>> 3) << (tempbits & 7);
        int pre = lead(~streamlong);
        int result;
        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16;
            tempbits += pre;
            streamlong <<= pre;
            result = streamlong >>> (32 - MAX_DATATYPE_BITS_16);
            tempbits += MAX_DATATYPE_BITS_16;
        } else {
            tempbits += pre;
            tempbits += 1;
            streamlong <<= pre + 1;
            int v = k == 0 ? 0 : streamlong >>> (32 - k);
            tempbits += k;
            result = pre * m + v - 1;
            if (Integer.compareUnsigned(v, 2) < 0) {
                result -= v - 1;
                tempbits -= 1;
            }
        }
        dynGetPos = tempbits;
        return result;
    }

    /** dyn_get_32bit: the adaptive Golomb code of one residual. */
    private int dynGet32(int m, int k, int maxbits) throws AudioFormatException {
        int tempbits = dynGetPos;
        int streamlong = read32bit(tempbits >>> 3) << (tempbits & 7);
        int result = lead(~streamlong);
        if (result >= MAX_PREFIX_32) {
            result = getStreamBits(tempbits + MAX_PREFIX_32, maxbits);
            tempbits += MAX_PREFIX_32 + maxbits;
        } else {
            tempbits += result;
            tempbits += 1;
            if (k != 1) {
                streamlong <<= result + 1;
                int v = k == 0 ? 0 : streamlong >>> (32 - k);
                tempbits += k;
                tempbits -= 1;
                result = result * m;
                if (Integer.compareUnsigned(v, 2) >= 0) {
                    result += v - 1;
                    tempbits += 1;
                }
            }
        }
        dynGetPos = tempbits;
        return result;
    }

    /** dyn_decomp: decodes numSamples residuals into the predictor buffer. */
    private void dynDecomp(int mb0, int pbLocal, int kbLocal, int numSamples, int maxSize)
            throws AudioFormatException {
        int wbLocal = (1 << kbLocal) - 1;
        int[] out = predictor;
        int startPos = bitPos;
        dynGetPos = startPos;
        int mbv = mb0;
        int zmode = 0;
        int c = 0;
        int o = 0;
        while (c < numSamples) {
            if (dynGetPos >= endBit) {
                throw damaged("residuals run past the end of the packet");
            }
            int m = mbv >>> QBSHIFT;
            int k = lg3a(m);
            k = Math.min(k, kbLocal);
            m = (1 << k) - 1;
            int n = dynGet32(m, k, maxSize);
            int ndecode = n + zmode;
            int multiplier = -(ndecode & 1);
            multiplier |= 1;
            int del = ((ndecode + 1) >>> 1) * multiplier;
            out[o++] = del;
            c++;
            mbv = pbLocal * (n + zmode) + mbv - ((pbLocal * mbv) >>> QBSHIFT);
            if (Integer.compareUnsigned(n, N_MAX_MEAN_CLAMP) > 0) {
                mbv = N_MEAN_CLAMP_VAL;
            }
            zmode = 0;
            if (Integer.compareUnsigned(mbv << MMULSHIFT, QB) < 0 && c < numSamples) {
                zmode = 1;
                k = lead(mbv) - BITOFF + ((mbv + MOFF) >>> MDENSHIFT);
                int mz = ((1 << k) - 1) & wbLocal;
                n = dynGet(mz, k);
                if (Integer.compareUnsigned(c + n, numSamples) > 0) {
                    throw damaged("a zero run past the end of the frame");
                }
                for (int j = 0; j < n; j++) {
                    out[o++] = 0;
                    ++c;
                }
                if (Integer.compareUnsigned(n, 65535) >= 0) {
                    zmode = 0;
                }
                mbv = 0;
            }
        }
        bitPos = dynGetPos;
        if (bitPos > endBit) {
            throw damaged("residuals run past the end of the packet");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // dp_dec.c

    private static int signOfInt(int i) {
        int negishift = (-i) >>> 31;
        return negishift | (i >> 31);
    }

    /** unpc_block: runs the adaptive FIR predictor over the residuals. */
    private static void unpcBlock(int[] pc1, int[] out, int num, short[] coefs, int numactive, int chanbits,
                                  int denshift) {
        int chanshift = 32 - chanbits;
        int denhalf = 1 << (denshift - 1);
        out[0] = pc1[0];
        if (numactive == 0) {
            if (num > 1 && pc1 != out) {
                System.arraycopy(pc1, 1, out, 1, num - 1);
            }
            return;
        }
        if (numactive == 31) {
            int prev = out[0];
            for (int j = 1; j < num; j++) {
                int del = pc1[j] + prev;
                prev = (del << chanshift) >> chanshift;
                out[j] = prev;
            }
            return;
        }
        for (int j = 1; j <= numactive && j < num; j++) {
            int del = pc1[j] + out[j - 1];
            out[j] = (del << chanshift) >> chanshift;
        }
        int lim = numactive + 1;
        for (int j = lim; j < num; j++) {
            int sum1 = 0;
            int pout = j - 1;
            int top = out[j - lim];
            for (int k = 0; k < numactive; k++) {
                sum1 += coefs[k] * (out[pout - k] - top);
            }
            int del = pc1[j];
            int del0 = del;
            int sg = signOfInt(del);
            del += top + ((sum1 + denhalf) >> denshift);
            out[j] = (del << chanshift) >> chanshift;
            if (sg > 0) {
                for (int k = numactive - 1; k >= 0; k--) {
                    int dd = top - out[pout - k];
                    int sgn = signOfInt(dd);
                    coefs[k] -= (short) sgn;
                    del0 -= (numactive - k) * ((sgn * dd) >> denshift);
                    if (del0 <= 0) {
                        break;
                    }
                }
            } else if (sg < 0) {
                for (int k = numactive - 1; k >= 0; k--) {
                    int dd = top - out[pout - k];
                    int sgn = signOfInt(dd);
                    coefs[k] += (short) sgn;
                    del0 -= (numactive - k) * ((-sgn * dd) >> denshift);
                    if (del0 >= 0) {
                        break;
                    }
                }
            }
        }
    }
}
