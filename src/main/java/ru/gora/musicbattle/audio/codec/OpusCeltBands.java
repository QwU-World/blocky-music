/*
 * Java port of part of the libopus 1.6.1 decoder. libopus is Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO,
 * Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other
 * contributors to libopus, under the BSD-3-Clause licence (its CELT, src/ and include/ files under the two-clause form
 * of it). The copyright notices of the files this follows and the full licence text are in NOTICE.md, under
 * "libopus". QwU World's MIT licence does not cover this file.
 */
package ru.gora.musicbattle.audio.codec;

/**
 * Decodes CELT's normalised band shapes: the recursive band splitting, stereo coding, folding and the PVQ codebook.
 * Ported from libopus 1.6.1 celt/bands.c (quant_all_bands and the functions it calls, decoder side, without the
 * QEXT extension), celt/vq.c (alg_unquant, exp_rotation, renormalise_vector), celt/cwrs.c (decode_pulses) and
 * celt/mathops.c (isqrt32), in their float form.
 *
 * <p>Wherever the C code passes a pointer into one of its buffers, this passes the array and an offset. The C float
 * macros (MULT16_32_Q15 and friends) are plain multiplications in the float build and appear here as such, in the
 * same order, so the arithmetic is the same float for float.
 */
final class OpusCeltBands {
    private static final int BITRES = OpusRangeDecoder.BITRES;
    private static final int NB_EBANDS = 21;
    private static final short[] EBANDS = OpusCeltTables.EBANDS;
    static final int SPREAD_NONE = 0;
    static final int SPREAD_AGGRESSIVE = 3;
    private static final int QTHETA_OFFSET = 4;
    private static final int QTHETA_OFFSET_TWOPHASE = 16;
    private static final double PI = 3.1415926535897931;
    private static final float EPSILON = 1e-15f;
    private static final int[] SPREAD_FACTOR = {15, 10, 5};
    private static final short[] EXP2_TABLE8 = {16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048};
    private static final int[] ORDERY_TABLE = {
            1, 0,
            3, 0, 2, 1,
            7, 0, 4, 3, 6, 1, 5, 2,
            15, 0, 8, 7, 12, 3, 11, 4, 14, 1, 9, 6, 13, 2, 10, 5,
    };
    private static final int[] BIT_INTERLEAVE_TABLE = {0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3};
    private static final int[] BIT_DEINTERLEAVE_TABLE = {
            0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F,
            0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF,
    };

    /** Row length of {@link #PVQ_U}: one more than the largest N or K it answers for. */
    private static final int PVQ_COLUMNS = 210;

    /**
     * U(N, K) of the PVQ codebook at [min(N, K) * PVQ_COLUMNS + max(N, K)]: the table CELT_PVQ_U_DATA of cwrs.c,
     * computed here from its recurrence U(n, k) = U(n-1, k) + U(n, k-1) + U(n-1, k-1). Entries too large for 32 bits
     * are never read for a valid allocation; they saturate. It is one flat array rather than an array of rows because
     * cwrsi walks down a column, and every step through rows cost a row load and two bounds checks (cwrsi was a fifth
     * of the decoding time).
     */
    private static final long[] PVQ_U = new long[15 * PVQ_COLUMNS];

    static {
        long cap = 1L << 60;
        // U(0, 0) = 1; the rest of row 0 and column 0 is 0. Rows 0 to 14 only depend on each other.
        PVQ_U[0] = 1;
        for (int n = 1; n < 15; n++) {
            for (int k = 1; k < PVQ_COLUMNS; k++) {
                int at = n * PVQ_COLUMNS + k;
                PVQ_U[at] = Math.min(cap, PVQ_U[at - PVQ_COLUMNS] + PVQ_U[at - 1] + PVQ_U[at - PVQ_COLUMNS - 1]);
            }
        }
    }

    // The band context (struct band_ctx), decoder side.
    private OpusRangeDecoder ec;
    private int band;
    private int intensity;
    private int spread;
    private int tfChange;
    private int remainingBits;
    int seed;
    private boolean disableInv;

    // Split context (struct split_ctx) results of computeTheta.
    private int sInv;
    private int sImid;
    private int sIside;
    private int sDelta;
    private int sItheta;
    private int sQalloc;
    private int thetaB;
    private int thetaFill;

    private final int[] iy = new int[176];
    private final float[] hadamardTmp = new float[176];
    private final float[] norm = new float[2 * 8 * 78];

    /** quant_all_bands, decoder side, with resynthesis always on. Y is the second half of {@code x} when stereo. */
    void quantAllBands(int start, int end, float[] x, int yOff, int[] collapseMasks, int[] pulses, boolean shortBlocks,
                       int spreadDecision, int dualStereo, int intensityBand, int[] tfRes, int totalBits, int balance,
                       OpusRangeDecoder dec, int lm, int codedBands, boolean disableInversion) {
        int m = 1 << lm;
        int b0 = shortBlocks ? m : 1;
        int c = yOff >= 0 ? 2 : 1;
        int normOffset = m * EBANDS[start];
        // No need to keep norm for the last band because it is never folded from.
        int norm2 = m * EBANDS[NB_EBANDS - 1] - normOffset;
        // The decoder uses the last band of X as scratch space: nothing there matters until that band is decoded.
        int lowbandScratch = m * EBANDS[NB_EBANDS - 1];
        boolean scratchOn = true;
        int lowbandOffset = 0;
        boolean updateLowband = true;
        this.ec = dec;
        this.intensity = intensityBand;
        this.spread = spreadDecision;
        this.disableInv = disableInversion;
        for (int i = start; i < end; i++) {
            band = i;
            boolean last = i == end - 1;
            int xOff = m * EBANDS[i];
            int yBand = yOff >= 0 ? yOff + m * EBANDS[i] : -1;
            int n = m * EBANDS[i + 1] - m * EBANDS[i];
            int tell = dec.tellFrac();
            // Compute how many bits we want to allocate to this band.
            if (i != start) {
                balance -= tell;
            }
            int remaining = totalBits - tell - 1;
            remainingBits = remaining;
            int b;
            if (i <= codedBands - 1) {
                int currBalance = balance / Math.min(3, codedBands - i);
                b = Math.max(0, Math.min(16383, Math.min(remaining + 1, pulses[i] + currBalance)));
            } else {
                b = 0;
            }
            if ((m * EBANDS[i] - n >= m * EBANDS[start] || i == start + 1) && (updateLowband || lowbandOffset == 0)) {
                lowbandOffset = i;
            }
            if (i == start + 1) {
                specialHybridFolding(norm, norm2, start, m, dualStereo != 0);
            }
            tfChange = tfRes[i];
            if (last) {
                scratchOn = false;
            }
            int effectiveLowband = -1;
            int xCm;
            int yCm;
            // Get a conservative estimate of the collapse masks of the bands we're going to be folding from.
            if (lowbandOffset != 0 && (spread != SPREAD_AGGRESSIVE || b0 > 1 || tfChange < 0)) {
                // This ensures we never repeat spectral content within one band.
                effectiveLowband = Math.max(0, m * EBANDS[lowbandOffset] - normOffset - n);
                int foldStart = lowbandOffset;
                while (m * EBANDS[--foldStart] > effectiveLowband + normOffset) {
                    // Searching down for the first band that reaches below the folding source.
                }
                int foldEnd = lowbandOffset - 1;
                while (++foldEnd < i && m * EBANDS[foldEnd] < effectiveLowband + normOffset + n) {
                    // Searching up for the band after the folding source.
                }
                xCm = 0;
                yCm = 0;
                int foldI = foldStart;
                do {
                    xCm |= collapseMasks[foldI * c];
                    yCm |= collapseMasks[foldI * c + c - 1];
                } while (++foldI < foldEnd);
            } else {
                // Otherwise, we'll be using the LCG to fold, so all blocks will (almost always) be non-zero.
                xCm = (1 << b0) - 1;
                yCm = xCm;
            }
            if (dualStereo != 0 && i == intensity) {
                // Switch off dual stereo to do intensity.
                dualStereo = 0;
                for (int j = 0; j < m * EBANDS[i] - normOffset; j++) {
                    norm[j] = .5f * (norm[j] + norm[norm2 + j]);
                }
            }
            int lowbandOut = last ? -1 : m * EBANDS[i] - normOffset;
            int scratch = scratchOn ? lowbandScratch : -1;
            if (dualStereo != 0) {
                xCm = quantBand(x, xOff, n, b / 2, b0, effectiveLowband != -1 ? norm : null, effectiveLowband,
                        lm, lowbandOut >= 0 ? norm : null, lowbandOut, 1f, x, scratch, xCm);
                yCm = quantBand(x, yBand, n, b / 2, b0, effectiveLowband != -1 ? norm : null,
                        norm2 + effectiveLowband, lm, lowbandOut >= 0 ? norm : null, norm2 + lowbandOut, 1f, x,
                        scratch, yCm);
            } else {
                if (yBand >= 0) {
                    xCm = quantBandStereo(x, xOff, yBand, n, b, b0, effectiveLowband != -1 ? norm : null,
                            effectiveLowband, lm, lowbandOut >= 0 ? norm : null, lowbandOut, x, scratch, xCm | yCm);
                } else {
                    xCm = quantBand(x, xOff, n, b, b0, effectiveLowband != -1 ? norm : null, effectiveLowband, lm,
                            lowbandOut >= 0 ? norm : null, lowbandOut, 1f, x, scratch, xCm | yCm);
                }
                yCm = xCm;
            }
            collapseMasks[i * c] = xCm & 0xFF;
            collapseMasks[i * c + c - 1] = yCm & 0xFF;
            balance += pulses[i] + tell;
            // Update the folding position only as long as we have 1 bit/sample depth.
            updateLowband = b > (n << BITRES);
        }
    }

    private static void specialHybridFolding(float[] norm, int norm2, int start, int m, boolean dualStereo) {
        int n1 = m * (EBANDS[start + 1] - EBANDS[start]);
        int n2 = m * (EBANDS[start + 2] - EBANDS[start + 1]);
        // Duplicate enough of the first band folding data to be able to fold the second band. Copies no data for
        // CELT-only mode.
        if (n2 - n1 > 0) {
            System.arraycopy(norm, 2 * n1 - n2, norm, n1, n2 - n1);
            if (dualStereo) {
                System.arraycopy(norm, norm2 + 2 * n1 - n2, norm, norm2 + n1, n2 - n1);
            }
        }
    }

    /** quant_band_n1: a band of a single sample per channel carries only its sign. */
    private int quantBandN1(float[] x, int xOff, int yOff, float[] lowbandOut, int lowbandOutOff) {
        int stereo = yOff >= 0 ? 1 : 0;
        int off = xOff;
        int ch = 0;
        do {
            int sign = 0;
            if (remainingBits >= 1 << BITRES) {
                sign = ec.decodeBits(1);
                remainingBits -= 1 << BITRES;
            }
            x[off] = sign != 0 ? -1f : 1f;
            off = yOff;
        } while (++ch < 1 + stereo);
        if (lowbandOut != null) {
            // SHR32 is the identity in the float build.
            lowbandOut[lowbandOutOff] = x[xOff];
        }
        return 1;
    }

    /** compute_theta, decoder side: the split angle between two halves (or mid and side) and its bit cost. */
    private void computeTheta(int n, int b, int bb, int b0, int lm, boolean stereo, int fill) {
        int itheta = 0;
        int inv = 0;
        // Decide on the resolution to give to the split parameter theta.
        int pulseCap = OpusCeltTables.LOG_N[band] + lm * (1 << BITRES);
        int offset = (pulseCap >> 1) - (stereo && n == 2 ? QTHETA_OFFSET_TWOPHASE : QTHETA_OFFSET);
        int qn = computeQn(n, b, offset, pulseCap, stereo);
        if (stereo && band >= intensity) {
            qn = 1;
        }
        int tell = ec.tellFrac();
        if (qn != 1) {
            // Entropy coding of the angle. We use a uniform pdf for the time split, a step for stereo, and a
            // triangular one for the rest.
            if (stereo && n > 2) {
                int p0 = 3;
                int x0 = qn / 2;
                int ft = p0 * (x0 + 1) + x0;
                int fs = ec.decode(ft);
                int x = fs < (x0 + 1) * p0 ? fs / p0 : x0 + 1 + (fs - (x0 + 1) * p0);
                ec.update(x <= x0 ? p0 * x : (x - 1 - x0) + (x0 + 1) * p0,
                        x <= x0 ? p0 * (x + 1) : (x - x0) + (x0 + 1) * p0, ft);
                itheta = x;
            } else if (b0 > 1 || stereo) {
                itheta = (int) ec.decodeUint(qn + 1);
            } else {
                int fs;
                int fl;
                int ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
                int fm = ec.decode(ft);
                if (fm < ((qn >> 1) * ((qn >> 1) + 1) >> 1)) {
                    itheta = (isqrt32(8 * fm + 1) - 1) >> 1;
                    fs = itheta + 1;
                    fl = itheta * (itheta + 1) >> 1;
                } else {
                    itheta = (2 * (qn + 1) - isqrt32(8 * (ft - fm - 1) + 1)) >> 1;
                    fs = qn + 1 - itheta;
                    fl = ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                }
                ec.update(fl, fl + fs, ft);
            }
            itheta = Integer.divideUnsigned(itheta * 16384, qn);
        } else if (stereo) {
            if (b > 2 << BITRES && remainingBits > 2 << BITRES) {
                inv = ec.decodeBitLogp(2) ? 1 : 0;
            }
            // The inversion flag is overridden to avoid problems with downmixing.
            if (disableInv) {
                inv = 0;
            }
            itheta = 0;
        }
        int qalloc = ec.tellFrac() - tell;
        b -= qalloc;
        int imid;
        int iside;
        int delta;
        if (itheta == 0) {
            imid = 32767;
            iside = 0;
            fill &= (1 << bb) - 1;
            delta = -16384;
        } else if (itheta == 16384) {
            imid = 0;
            iside = 32767;
            fill &= ((1 << bb) - 1) << bb;
            delta = 16384;
        } else {
            imid = bitexactCos((short) itheta);
            iside = bitexactCos((short) (16384 - itheta));
            // This is the mid vs side allocation that minimizes squared error in that band.
            delta = fracMul16((n - 1) << 7, bitexactLog2tan(iside, imid));
        }
        sInv = inv;
        sImid = imid;
        sIside = iside;
        sDelta = delta;
        sItheta = itheta;
        sQalloc = qalloc;
        thetaB = b;
        thetaFill = fill;
    }

    private static int computeQn(int n, int b, int offset, int pulseCap, boolean stereo) {
        int n2 = 2 * n - 1;
        if (stereo && n == 2) {
            n2--;
        }
        // The upper limit ensures that in a stereo split with itheta==16384, we'll always have enough bits left
        // over to code at least one pulse in the side; otherwise it would collapse, since it doesn't get folded.
        int qb = (b + n2 * offset) / n2;
        qb = Math.min(b - pulseCap - (4 << BITRES), qb);
        qb = Math.min(8 << BITRES, qb);
        int qn;
        if (qb < (1 << BITRES >> 1)) {
            qn = 1;
        } else {
            qn = EXP2_TABLE8[qb & 0x7] >> (14 - (qb >> BITRES));
            qn = (qn + 1) >> 1 << 1;
        }
        return qn;
    }

    /** quant_partition: decodes a mono partition, splitting it in two while that saves bits. */
    private int quantPartition(float[] x, int xOff, int n, int b, int bb, float[] lowband, int lowbandOff, int lm,
                               float gain, int fill) {
        int b0 = bb;
        int cm;
        // If we need 1.5 more bit than we can produce, split the band in two.
        if (lm != -1 && n > 2 && b > OpusCeltAllocation.cacheBits(band, lm, OpusCeltAllocation.cacheMax(band, lm)) + 12) {
            n >>= 1;
            int yOff = xOff + n;
            lm -= 1;
            if (bb == 1) {
                fill = (fill & 1) | (fill << 1);
            }
            bb = (bb + 1) >> 1;
            computeTheta(n, b, bb, b0, lm, false, fill);
            b = thetaB;
            fill = thetaFill;
            int imid = sImid;
            int iside = sIside;
            int delta = sDelta;
            int itheta = sItheta;
            int qalloc = sQalloc;
            float mid = (1.f / 32768) * imid;
            float side = (1.f / 32768) * iside;
            // Give more bits to low-energy MDCTs than they would otherwise deserve.
            if (b0 > 1 && (itheta & 0x3fff) != 0) {
                if (itheta > 8192) {
                    // Rough approximation for pre-echo masking.
                    delta -= delta >> (4 - lm);
                } else {
                    // Corresponds to a forward-masking slope of 1.5 dB per 10 ms.
                    delta = Math.min(0, delta + (n << BITRES >> (5 - lm)));
                }
            }
            int mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            int sbits = b - mbits;
            remainingBits -= qalloc;
            int nextLowband2 = lowband != null ? lowbandOff + n : -1;
            int rebalance = remainingBits;
            if (mbits >= sbits) {
                cm = quantPartition(x, xOff, n, mbits, bb, lowband, lowbandOff, lm, gain * mid, fill);
                rebalance = mbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 0) {
                    sbits += rebalance - (3 << BITRES);
                }
                cm |= quantPartition(x, yOff, n, sbits, bb, lowband, nextLowband2, lm, gain * side, fill >> bb)
                        << (b0 >> 1);
            } else {
                cm = quantPartition(x, yOff, n, sbits, bb, lowband, nextLowband2, lm, gain * side, fill >> bb)
                        << (b0 >> 1);
                rebalance = sbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 16384) {
                    mbits += rebalance - (3 << BITRES);
                }
                cm |= quantPartition(x, xOff, n, mbits, bb, lowband, lowbandOff, lm, gain * mid, fill);
            }
            return cm;
        }
        // This is the basic no-split case.
        int q = OpusCeltAllocation.bits2pulses(band, lm, b);
        int currBits = OpusCeltAllocation.pulses2bits(band, lm, q);
        remainingBits -= currBits;
        // Ensures we can never bust the budget.
        while (remainingBits < 0 && q > 0) {
            remainingBits += currBits;
            q--;
            currBits = OpusCeltAllocation.pulses2bits(band, lm, q);
            remainingBits -= currBits;
        }
        if (q != 0) {
            int k = OpusCeltAllocation.getPulses(q);
            return algUnquant(x, xOff, n, k, spread, bb, gain);
        }
        // If there's no pulse, fill the band anyway.
        int cmMask = (1 << bb) - 1;
        fill &= cmMask;
        cm = 0;
        if (fill == 0) {
            java.util.Arrays.fill(x, xOff, xOff + n, 0f);
        } else {
            if (lowband == null) {
                // Noise.
                for (int j = 0; j < n; j++) {
                    seed = lcgRand(seed);
                    x[xOff + j] = (float) (seed >> 20);
                }
                cm = cmMask;
            } else {
                // Folded spectrum.
                for (int j = 0; j < n; j++) {
                    seed = lcgRand(seed);
                    // About 48 dB below the "normal" folding level.
                    float tmp = 1.0f / 256;
                    tmp = (seed & 0x8000) != 0 ? tmp : -tmp;
                    x[xOff + j] = lowband[lowbandOff + j] + tmp;
                }
                cm = fill;
            }
            renormaliseVector(x, xOff, n, gain);
        }
        return cm;
    }

    /** quant_band: decodes one mono band, including the time-frequency changes around the partition. */
    private int quantBand(float[] x, int xOff, int n, int b, int bb, float[] lowband, int lowbandOff, int lm,
                          float[] lowbandOut, int lowbandOutOff, float gain, float[] scratch, int scratchOff,
                          int fill) {
        int n0 = n;
        int nb = n;
        int b0 = bb;
        int timeDivide = 0;
        int recombine = 0;
        boolean longBlocks = b0 == 1;
        int tf = tfChange;
        nb = Integer.divideUnsigned(nb, bb);
        // Special case for one sample.
        if (n == 1) {
            return quantBandN1(x, xOff, -1, lowbandOut, lowbandOutOff);
        }
        if (tf > 0) {
            recombine = tf;
        }
        // Band recombining to increase frequency resolution.
        if (scratchOff >= 0 && lowband != null && (recombine != 0 || ((nb & 1) == 0 && tf < 0) || b0 > 1)) {
            System.arraycopy(lowband, lowbandOff, scratch, scratchOff, n);
            lowband = scratch;
            lowbandOff = scratchOff;
        }
        for (int k = 0; k < recombine; k++) {
            if (lowband != null) {
                haar1(lowband, lowbandOff, n >> k, 1 << k);
            }
            fill = BIT_INTERLEAVE_TABLE[fill & 0xF] | BIT_INTERLEAVE_TABLE[fill >> 4] << 2;
        }
        bb >>= recombine;
        nb <<= recombine;
        // Increasing the time resolution.
        while ((nb & 1) == 0 && tf < 0) {
            if (lowband != null) {
                haar1(lowband, lowbandOff, nb, bb);
            }
            fill |= fill << bb;
            bb <<= 1;
            nb >>= 1;
            timeDivide++;
            tf++;
        }
        b0 = bb;
        int nb0 = nb;
        // Reorganize the samples in time order instead of frequency order.
        if (b0 > 1 && lowband != null) {
            deinterleaveHadamard(lowband, lowbandOff, nb >> recombine, b0 << recombine, longBlocks);
        }
        int cm = quantPartition(x, xOff, n, b, bb, lowband, lowbandOff, lm, gain, fill);
        // Undo the sample reorganization going from time order to frequency order.
        if (b0 > 1) {
            interleaveHadamard(x, xOff, nb >> recombine, b0 << recombine, longBlocks);
        }
        // Undo time-freq changes that we did earlier.
        nb = nb0;
        bb = b0;
        for (int k = 0; k < timeDivide; k++) {
            bb >>= 1;
            nb <<= 1;
            cm |= cm >>> bb;
            haar1(x, xOff, nb, bb);
        }
        for (int k = 0; k < recombine; k++) {
            cm = BIT_DEINTERLEAVE_TABLE[cm];
            haar1(x, xOff, n0 >> k, 1 << k);
        }
        bb <<= recombine;
        // Scale output for later folding.
        if (lowbandOut != null) {
            float g = (float) Math.sqrt(n0);
            for (int j = 0; j < n0; j++) {
                lowbandOut[lowbandOutOff + j] = g * x[xOff + j];
            }
        }
        cm &= (1 << bb) - 1;
        return cm;
    }

    /** quant_band_stereo: decodes a stereo band as mid and side (or intensity) and turns it back into left/right. */
    private int quantBandStereo(float[] x, int xOff, int yOff, int n, int b, int bb, float[] lowband, int lowbandOff,
                                int lm, float[] lowbandOut, int lowbandOutOff, float[] scratch, int scratchOff,
                                int fill) {
        // Special case for one sample.
        if (n == 1) {
            return quantBandN1(x, xOff, yOff, lowbandOut, lowbandOutOff);
        }
        int origFill = fill;
        computeTheta(n, b, bb, bb, lm, true, fill);
        b = thetaB;
        fill = thetaFill;
        int inv = sInv;
        int imid = sImid;
        int iside = sIside;
        int delta = sDelta;
        int itheta = sItheta;
        int qalloc = sQalloc;
        float mid = (1.f / 32768) * imid;
        float side = (1.f / 32768) * iside;
        int cm;
        if (n == 2) {
            // A special case for N=2 that only works for stereo and takes advantage of the fact that mid and side
            // are orthogonal to encode the side with just one bit.
            int mbits = b;
            int sbits = 0;
            // Only need one bit for the side.
            if (itheta != 0 && itheta != 16384) {
                sbits = 1 << BITRES;
            }
            mbits -= sbits;
            boolean c = itheta > 8192;
            remainingBits -= qalloc + sbits;
            int x2 = c ? yOff : xOff;
            int y2 = c ? xOff : yOff;
            int sign = 0;
            if (sbits != 0) {
                sign = ec.decodeBits(1);
            }
            sign = 1 - 2 * sign;
            // We use origFill here because we want to fold the side, but if itheta==16384, we'll have cleared the
            // low bits of fill.
            cm = quantBand(x, x2, n, mbits, bb, lowband, lowbandOff, lm, lowbandOut, lowbandOutOff, 1f, scratch,
                    scratchOff, origFill);
            // We don't split N=2 bands, so cm is either 1 or 0 (for a fold-collapse), and there's no need to worry
            // about mixing with the other channel.
            x[y2] = -sign * x[x2 + 1];
            x[y2 + 1] = sign * x[x2];
            x[xOff] = mid * x[xOff];
            x[xOff + 1] = mid * x[xOff + 1];
            x[yOff] = side * x[yOff];
            x[yOff + 1] = side * x[yOff + 1];
            float tmp = x[xOff];
            x[xOff] = tmp - x[yOff];
            x[yOff] = tmp + x[yOff];
            tmp = x[xOff + 1];
            x[xOff + 1] = tmp - x[yOff + 1];
            x[yOff + 1] = tmp + x[yOff + 1];
        } else {
            // "Normal" split code.
            int mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            int sbits = b - mbits;
            remainingBits -= qalloc;
            int rebalance = remainingBits;
            if (mbits >= sbits) {
                // In stereo mode, we do not apply a scaling to the mid because we need the normalized mid for
                // folding later.
                cm = quantBand(x, xOff, n, mbits, bb, lowband, lowbandOff, lm, lowbandOut, lowbandOutOff, 1f,
                        scratch, scratchOff, fill);
                rebalance = mbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 0) {
                    sbits += rebalance - (3 << BITRES);
                }
                // For a stereo split, the high bits of fill are always zero, so no folding will be done to the side.
                cm |= quantBand(x, yOff, n, sbits, bb, null, 0, lm, null, 0, side, null, -1, fill >> bb);
            } else {
                // For a stereo split, the high bits of fill are always zero, so no folding will be done to the side.
                cm = quantBand(x, yOff, n, sbits, bb, null, 0, lm, null, 0, side, null, -1, fill >> bb);
                rebalance = sbits - (rebalance - remainingBits);
                if (rebalance > 3 << BITRES && itheta != 16384) {
                    mbits += rebalance - (3 << BITRES);
                }
                // In stereo mode, we do not apply a scaling to the mid because we need the normalized mid for
                // folding later.
                cm |= quantBand(x, xOff, n, mbits, bb, lowband, lowbandOff, lm, lowbandOut, lowbandOutOff, 1f,
                        scratch, scratchOff, fill);
            }
        }
        if (n != 2) {
            stereoMerge(x, xOff, yOff, mid, n);
        }
        if (inv != 0) {
            for (int j = 0; j < n; j++) {
                x[yOff + j] = -x[yOff + j];
            }
        }
        return cm;
    }

    private static void stereoMerge(float[] x, int xOff, int yOff, float mid, int n) {
        // Compute the norm of X+Y and X-Y as |X|^2 + |Y|^2 +/- sum(xy).
        float xp = innerProd(x, yOff, x, xOff, n);
        float side = innerProd(x, yOff, x, yOff, n);
        // Compensating for the mid normalization.
        xp = mid * xp;
        // mid and side are in Q15, not Q14 like X and Y.
        float el = mid * mid + side - 2 * xp;
        float er = mid * mid + side + 2 * xp;
        if (er < 6e-4f || el < 6e-4f) {
            System.arraycopy(x, xOff, x, yOff, n);
            return;
        }
        float lgain = 1.f / (float) Math.sqrt(el);
        float rgain = 1.f / (float) Math.sqrt(er);
        for (int j = 0; j < n; j++) {
            // Apply mid scaling (side is already scaled).
            float l = mid * x[xOff + j];
            float r = x[yOff + j];
            x[xOff + j] = lgain * (l - r);
            x[yOff + j] = rgain * (l + r);
        }
    }

    /** celt_inner_prod_c: the plain sequential dot product, as the scalar C code sums it. */
    static float innerProd(float[] a, int aOff, float[] b, int bOff, int n) {
        float xy = 0;
        for (int i = 0; i < n; i++) {
            xy = xy + a[aOff + i] * b[bOff + i];
        }
        return xy;
    }

    /** renormalise_vector: scales a band to the given norm. */
    static void renormaliseVector(float[] x, int off, int n, float gain) {
        float e = EPSILON + innerProd(x, off, x, off, n);
        float g = (1.f / (float) Math.sqrt(e)) * gain;
        for (int i = 0; i < n; i++) {
            x[off + i] = g * x[off + i];
        }
    }

    static void haar1(float[] x, int off, int n0, int stride) {
        n0 >>= 1;
        for (int i = 0; i < stride; i++) {
            for (int j = 0; j < n0; j++) {
                float tmp1 = .70710678f * x[off + stride * 2 * j + i];
                float tmp2 = .70710678f * x[off + stride * (2 * j + 1) + i];
                x[off + stride * 2 * j + i] = tmp1 + tmp2;
                x[off + stride * (2 * j + 1) + i] = tmp1 - tmp2;
            }
        }
    }

    private void deinterleaveHadamard(float[] x, int off, int n0, int stride, boolean hadamard) {
        int n = n0 * stride;
        float[] tmp = hadamardTmp;
        if (hadamard) {
            int ordery = stride - 2;
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    tmp[ORDERY_TABLE[ordery + i] * n0 + j] = x[off + j * stride + i];
                }
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    tmp[i * n0 + j] = x[off + j * stride + i];
                }
            }
        }
        System.arraycopy(tmp, 0, x, off, n);
    }

    private void interleaveHadamard(float[] x, int off, int n0, int stride, boolean hadamard) {
        int n = n0 * stride;
        float[] tmp = hadamardTmp;
        if (hadamard) {
            int ordery = stride - 2;
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    tmp[j * stride + i] = x[off + ORDERY_TABLE[ordery + i] * n0 + j];
                }
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < n0; j++) {
                    tmp[j * stride + i] = x[off + i * n0 + j];
                }
            }
        }
        System.arraycopy(tmp, 0, x, off, n);
    }

    /** alg_unquant: decodes K pulses in N dimensions, normalises them to {@code gain} and spreads them. */
    private int algUnquant(float[] x, int off, int n, int k, int spreadDecision, int bb, float gain) {
        int[] y = iy;
        float ryy = decodePulses(y, n, k);
        // normalise_residual
        float g = (1.f / (float) Math.sqrt(ryy)) * gain;
        int i = 0;
        do {
            x[off + i] = y[i] * g;
        } while (++i < n);
        expRotation(x, off, n, -1, bb, k, spreadDecision);
        return extractCollapseMask(y, n, bb);
    }

    private static int extractCollapseMask(int[] y, int n, int bb) {
        if (bb <= 1) {
            return 1;
        }
        int n0 = Integer.divideUnsigned(n, bb);
        int collapseMask = 0;
        int i = 0;
        do {
            int tmp = 0;
            int j = 0;
            do {
                tmp |= y[i * n0 + j];
            } while (++j < n0);
            collapseMask |= (tmp != 0 ? 1 : 0) << i;
        } while (++i < bb);
        return collapseMask;
    }

    static void expRotation(float[] x, int off, int len, int dir, int stride, int k, int spreadDecision) {
        if (2 * k >= len || spreadDecision == SPREAD_NONE) {
            return;
        }
        int factor = SPREAD_FACTOR[spreadDecision - 1];
        float gain = (1.f * len) / (float) (len + factor * k);
        float theta = .5f * (gain * gain);
        // celt_cos_norm of the float build: a libm cos() in double, rounded to float. StrictMath gives the same bits
        // on every platform and JIT, which Math.cos does not promise, and the check's fingerprints rely on that. For
        // all 119 400 (len, spread, K) inputs this code can see, its floats equal macOS libm's, so libopus is still
        // matched bit for bit.
        float c = (float) StrictMath.cos((.5f * PI) * theta);
        float s = (float) StrictMath.cos((.5f * PI) * (1.0f - theta));
        int stride2 = 0;
        if (len >= 8 * stride) {
            stride2 = 1;
            // This is just a simple (equivalent) way of computing sqrt(len/stride) with rounding. It's basically
            // incrementing long as (stride2+0.5)^2 < len/stride.
            while ((stride2 * stride2 + stride2) * stride + (stride >> 2) < len) {
                stride2++;
            }
        }
        len = Integer.divideUnsigned(len, stride);
        for (int i = 0; i < stride; i++) {
            if (dir < 0) {
                if (stride2 != 0) {
                    expRotation1(x, off + i * len, len, stride2, s, c);
                }
                expRotation1(x, off + i * len, len, 1, c, s);
            } else {
                expRotation1(x, off + i * len, len, 1, c, -s);
                if (stride2 != 0) {
                    expRotation1(x, off + i * len, len, stride2, s, -c);
                }
            }
        }
    }

    private static void expRotation1(float[] x, int off, int len, int stride, float c, float s) {
        float ms = -s;
        int p = off;
        for (int i = 0; i < len - stride; i++) {
            float x1 = x[p];
            float x2 = x[p + stride];
            x[p + stride] = c * x2 + s * x1;
            x[p++] = c * x1 + ms * x2;
        }
        p = off + len - 2 * stride - 1;
        for (int i = len - 2 * stride - 1; i >= 0; i--) {
            float x1 = x[p];
            float x2 = x[p + stride];
            x[p + stride] = c * x2 + s * x1;
            x[p--] = c * x1 + ms * x2;
        }
    }

    private static long pvqU(int n, int k) {
        return n < k ? PVQ_U[n * PVQ_COLUMNS + k] : PVQ_U[k * PVQ_COLUMNS + n];
    }

    /** decode_pulses: reads the codeword index and expands it into the pulse vector; returns its squared norm. */
    private float decodePulses(int[] y, int n, int k) {
        long v = pvqU(n, k) + pvqU(n, k + 1);
        return cwrsi(n, k, ec.decodeUint(v), y);
    }

    private static float cwrsi(int n, int k, long i, int[] y) {
        long[] u = PVQ_U;
        int yp = 0;
        float yy = 0;
        long p;
        int s;
        int k0;
        int val;
        while (n > 2) {
            long q;
            if (k >= n) {
                // Lots of pulses case: U(n, k) is in row n.
                int row = n * PVQ_COLUMNS;
                // Are the pulses in this dimension negative?
                p = u[row + k + 1];
                s = i >= p ? -1 : 0;
                i -= p & s;
                // Count how many pulses were placed in this dimension.
                k0 = k;
                q = u[row + n];
                if (q > i) {
                    // Down column n from row n: U(--k, n) until it fits, with at tracking k * PVQ_COLUMNS + n.
                    k = n;
                    int at = row + n;
                    do {
                        k--;
                        at -= PVQ_COLUMNS;
                        p = u[at];
                    } while (p > i);
                } else {
                    for (p = u[row + k]; p > i; p = u[row + k]) {
                        k--;
                    }
                }
                i -= p;
                val = (k0 - k + s) ^ s;
                y[yp++] = val;
                yy = yy + (float) val * (float) val;
            } else {
                // Lots of dimensions case: U(k, n) is in row k. Are there any pulses in this dimension at all?
                int at = k * PVQ_COLUMNS + n;
                p = u[at];
                q = u[at + PVQ_COLUMNS];
                if (p <= i && i < q) {
                    i -= p;
                    y[yp++] = 0;
                } else {
                    // Are the pulses in this dimension negative?
                    s = i >= q ? -1 : 0;
                    i -= q & s;
                    // Count how many pulses were placed in this dimension.
                    k0 = k;
                    do {
                        k--;
                        at -= PVQ_COLUMNS;
                        p = u[at];
                    } while (p > i);
                    i -= p;
                    val = (k0 - k + s) ^ s;
                    y[yp++] = val;
                    yy = yy + (float) val * (float) val;
                }
            }
            n--;
        }
        // n == 2
        p = 2L * k + 1;
        s = i >= p ? -1 : 0;
        i -= p & s;
        k0 = k;
        k = (int) ((i + 1) >> 1);
        if (k != 0) {
            i -= 2L * k - 1;
        }
        val = (k0 - k + s) ^ s;
        y[yp++] = val;
        yy = yy + (float) val * (float) val;
        // n == 1
        s = -(int) i;
        val = (k + s) ^ s;
        y[yp] = val;
        yy = yy + (float) val * (float) val;
        return yy;
    }

    /** anti_collapse: fills the short blocks that got no pulses with noise, at an energy derived from history. */
    static void antiCollapse(float[] x, int[] collapseMasks, int lm, int c, int size, int start, int end, float[] logE,
                             float[] prev1logE, float[] prev2logE, int[] pulses, int seed) {
        // The seed is a local copy in C as well: the noise drawn here does not advance the decoder's state.
        for (int i = start; i < end; i++) {
            int n0 = EBANDS[i + 1] - EBANDS[i];
            // Depth in 1/8 bits.
            int depth = Integer.divideUnsigned(1 + pulses[i], EBANDS[i + 1] - EBANDS[i]) >> lm;
            float thresh = .5f * OpusCeltDecoder.celtExp2(-.125f * depth);
            float sqrt1 = 1.f / (float) Math.sqrt(n0 << lm);
            int ch = 0;
            do {
                boolean renormalize = false;
                float prev1 = prev1logE[ch * NB_EBANDS + i];
                float prev2 = prev2logE[ch * NB_EBANDS + i];
                if (c == 1) {
                    prev1 = prev1 > prev1logE[NB_EBANDS + i] ? prev1 : prev1logE[NB_EBANDS + i];
                    prev2 = prev2 > prev2logE[NB_EBANDS + i] ? prev2 : prev2logE[NB_EBANDS + i];
                }
                float minPrev = prev1 < prev2 ? prev1 : prev2;
                float ediff = logE[ch * NB_EBANDS + i] - minPrev;
                ediff = 0 > ediff ? 0 : ediff;
                // r needs to be multiplied by 2 or 2*sqrt(2) depending on LM because short blocks don't have the
                // same energy as long.
                float r = 2.f * OpusCeltDecoder.celtExp2(-ediff);
                if (lm == 3) {
                    r *= 1.41421356f;
                }
                r = thresh < r ? thresh : r;
                r = r * sqrt1;
                int xb = ch * size + (EBANDS[i] << lm);
                for (int k = 0; k < 1 << lm; k++) {
                    // Detect collapse.
                    if ((collapseMasks[i * c + ch] & 1 << k) == 0) {
                        // Fill with noise.
                        for (int j = 0; j < n0; j++) {
                            seed = lcgRand(seed);
                            x[xb + (j << lm) + k] = (seed & 0x8000) != 0 ? r : -r;
                        }
                        renormalize = true;
                    }
                }
                // We just added some energy, so we need to renormalise.
                if (renormalize) {
                    renormaliseVector(x, xb, n0 << lm, 1f);
                }
            } while (++ch < c);
        }
    }

    /** denormalise_bands: multiplies the unit-norm bands by their decoded energies into an MDCT spectrum. */
    static void denormaliseBands(float[] x, int xOff, float[] freq, int freqOff, float[] bandLogE, int bandLogEOff,
                                 int start, int end, int m, int downsample, boolean silence) {
        int n = m * 120;
        int bound = m * EBANDS[end];
        if (downsample != 1) {
            bound = Math.min(bound, n / downsample);
        }
        if (silence) {
            bound = 0;
            start = 0;
            end = 0;
        }
        int f = freqOff;
        int xp = xOff + m * EBANDS[start];
        if (start != 0) {
            for (int i = 0; i < m * EBANDS[start]; i++) {
                freq[f++] = 0;
            }
        } else {
            f += m * EBANDS[start];
        }
        float[] eMeans = OpusCeltTables.E_MEANS;
        for (int i = start; i < end; i++) {
            int j = m * EBANDS[i];
            int bandEnd = m * EBANDS[i + 1];
            float lg = bandLogE[bandLogEOff + i] + eMeans[i];
            float g = OpusCeltDecoder.celtExp2(32.f < lg ? 32.f : lg);
            do {
                freq[f++] = x[xp] * g;
                xp++;
            } while (++j < bandEnd);
        }
        java.util.Arrays.fill(freq, freqOff + bound, freqOff + n, 0f);
    }

    static int lcgRand(int seed) {
        return 1664525 * seed + 1013904223;
    }

    /** FRAC_MUL16, with the C macro's casts of both arguments to 16 bits. */
    private static int fracMul16(int a, int b) {
        return (16384 + (short) a * (short) b) >> 15;
    }

    /** A cos() approximation that is bit-exact on every platform, because it feeds the bit allocation. */
    static int bitexactCos(short x) {
        int tmp = (4096 + x * x) >> 13;
        short x2 = (short) tmp;
        x2 = (short) ((32767 - x2) + fracMul16(x2, (-7651 + fracMul16(x2, (8277 + fracMul16(-626, x2))))));
        return 1 + x2;
    }

    static int bitexactLog2tan(int isin, int icos) {
        int lc = OpusRangeDecoder.ilog(icos & 0xFFFFFFFFL);
        int ls = OpusRangeDecoder.ilog(isin & 0xFFFFFFFFL);
        icos <<= 15 - lc;
        isin <<= 15 - ls;
        return (ls - lc) * (1 << 11)
                + fracMul16(isin, fracMul16(isin, -2597) + 7932)
                - fracMul16(icos, fracMul16(icos, -2597) + 7932);
    }

    static int isqrt32(int value) {
        // mathops.c finds floor(sqrt(value)) bit by bit, which showed up as 4% of decoding time. A correctly rounded
        // double square root (Math.sqrt is exact IEEE on every platform) never rounds up past an integer for inputs
        // below 2^32, so truncating it gives the same result: checked against the bit-by-bit loop for every value
        // below 2^28 and on both sides of every perfect square up to 2^32 (work/pass3/isqrt in the scratch tree).
        return (int) Math.sqrt((double) (value & 0xFFFFFFFFL));
    }
}
