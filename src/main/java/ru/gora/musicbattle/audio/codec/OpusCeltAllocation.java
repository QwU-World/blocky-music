/*
 * Java port of part of the libopus 1.6.1 decoder. libopus is Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO,
 * Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other
 * contributors to libopus, under the BSD-3-Clause licence (its CELT, src/ and include/ files under the two-clause form
 * of it). The copyright notices of the files this follows and the full licence text are in NOTICE.md, under
 * "libopus". QwU World's MIT licence does not cover this file.
 */
package ru.gora.musicbattle.audio.codec;

/**
 * CELT's bit allocation and band energy decoding, ported from libopus 1.6.1 celt/rate.c (clt_compute_allocation,
 * interp_bits2pulses and the pulse-cache helpers of rate.h), celt/celt.c (init_caps) and celt/quant_bands.c
 * (unquant_coarse_energy, unquant_fine_energy, unquant_energy_finalise), decoder side only.
 *
 * <p>The allocation is integer arithmetic that the encoder and decoder must agree on bit for bit, so it is a
 * line-by-line port. Divisions that are unsigned in C stay unsigned here.
 */
final class OpusCeltAllocation {
    static final int NB_EBANDS = 21;
    static final int MAX_FINE_BITS = 8;
    private static final int BITRES = OpusRangeDecoder.BITRES;
    private static final int FINE_OFFSET = 21;
    private static final int ALLOC_STEPS = 6;
    private static final int LOG_MAX_PSEUDO = 6;
    private static final int NB_ALLOC_VECTORS = 11;
    private static final short[] EBANDS = OpusCeltTables.EBANDS;
    private static final int[] LOG2_FRAC_TABLE = {
            0,
            8, 13,
            16, 19, 21, 23,
            24, 26, 27, 28, 29, 30, 31, 32,
            32, 33, 34, 34, 35, 36, 36, 37, 37,
    };
    /** Prediction coefficients and decay of the coarse energy per LM, and the decay of intra frames. */
    private static final float[] PRED_COEF = {29440 / 32768f, 26112 / 32768f, 21248 / 32768f, 16384 / 32768f};
    private static final float[] BETA_COEF = {30147 / 32768f, 22282 / 32768f, 12124 / 32768f, 6554 / 32768f};
    private static final float BETA_INTRA = 4915 / 32768f;
    private static final short[] SMALL_ENERGY_ICDF = {2, 1, 0};

    private OpusCeltAllocation() {
    }

    /** get_pulses: the number of pulses a pseudo-pulse index stands for. */
    static int getPulses(int i) {
        return i < 8 ? i : (8 + (i & 7)) << ((i >> 3) - 1);
    }

    /** bits2pulses: the largest pseudo-pulse count whose cost fits {@code bits} (1/8 bits) in this band. */
    static int bits2pulses(int band, int lm, int bits) {
        lm++;
        int cache = OpusCeltTables.CACHE_INDEX[lm * NB_EBANDS + band];
        int[] cb = OpusCeltTables.CACHE_BITS;
        int lo = 0;
        int hi = cb[cache];
        bits--;
        for (int i = 0; i < LOG_MAX_PSEUDO; i++) {
            int mid = (lo + hi + 1) >> 1;
            if (cb[cache + mid] >= bits) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        if (bits - (lo == 0 ? -1 : cb[cache + lo]) <= cb[cache + hi] - bits) {
            return lo;
        }
        return hi;
    }

    /** pulses2bits: the cost in 1/8 bits of a pseudo-pulse count in this band. */
    static int pulses2bits(int band, int lm, int pulses) {
        lm++;
        int cache = OpusCeltTables.CACHE_INDEX[lm * NB_EBANDS + band];
        return pulses == 0 ? 0 : OpusCeltTables.CACHE_BITS[cache + pulses] + 1;
    }

    /** The first byte of a band's cache row: the most pulses it can take. */
    static int cacheMax(int band, int lm) {
        return OpusCeltTables.CACHE_BITS[OpusCeltTables.CACHE_INDEX[(lm + 1) * NB_EBANDS + band]];
    }

    /** cache.bits[cache.index[...] + k], for quant_partition's split decision. */
    static int cacheBits(int band, int lm, int k) {
        return OpusCeltTables.CACHE_BITS[OpusCeltTables.CACHE_INDEX[(lm + 1) * NB_EBANDS + band] + k];
    }

    /** init_caps: the most bits each band can use. */
    static void initCaps(int[] cap, int lm, int c) {
        for (int i = 0; i < NB_EBANDS; i++) {
            int n = (EBANDS[i + 1] - EBANDS[i]) << lm;
            cap[i] = (OpusCeltTables.CACHE_CAPS[NB_EBANDS * (2 * lm + c - 1) + i] + 64) * c * n >> 2;
        }
    }

    /** The outputs of {@link #compute} that are not arrays. */
    static final class Result {
        int intensity;
        int dualStereo;
        int balance;
        int codedBands;
    }

    /** clt_compute_allocation, decoder side. */
    static void compute(int start, int end, int[] offsets, int[] cap, int allocTrim, int total, int[] pulses,
                        int[] ebits, int[] finePriority, int c, int lm, OpusRangeDecoder ec, Result result,
                        int[] scratch) {
        total = Math.max(total, 0);
        int len = NB_EBANDS;
        int skipStart = start;
        // Reserve a bit to signal the end of manually skipped bands.
        int skipRsv = total >= 1 << BITRES ? 1 << BITRES : 0;
        total -= skipRsv;
        // Reserve bits for the intensity and dual stereo parameters.
        int intensityRsv = 0;
        int dualStereoRsv = 0;
        if (c == 2) {
            intensityRsv = LOG2_FRAC_TABLE[end - start];
            if (intensityRsv > total) {
                intensityRsv = 0;
            } else {
                total -= intensityRsv;
                dualStereoRsv = total >= 1 << BITRES ? 1 << BITRES : 0;
                total -= dualStereoRsv;
            }
        }
        // bits1, bits2, thresh and trim_offset share one scratch array.
        int bits1 = 0;
        int bits2 = len;
        int thresh = 2 * len;
        int trimOffset = 3 * len;
        for (int j = start; j < end; j++) {
            // Below this threshold, we're sure not to allocate any PVQ bits.
            scratch[thresh + j] = Math.max(c << BITRES, (3 * (EBANDS[j + 1] - EBANDS[j]) << lm << BITRES) >> 4);
            // Tilt of the allocation curve.
            scratch[trimOffset + j] = c * (EBANDS[j + 1] - EBANDS[j]) * (allocTrim - 5 - lm) * (end - j - 1)
                    * (1 << (lm + BITRES)) >> 6;
            // Giving less resolution to single-coefficient bands because they get more benefit from having one
            // coarse value per coefficient.
            if ((EBANDS[j + 1] - EBANDS[j]) << lm == 1) {
                scratch[trimOffset + j] -= c << BITRES;
            }
        }
        int lo = 1;
        int hi = NB_ALLOC_VECTORS - 1;
        do {
            boolean done = false;
            int psum = 0;
            int mid = (lo + hi) >> 1;
            for (int j = end; j-- > start; ) {
                int n = EBANDS[j + 1] - EBANDS[j];
                int bitsj = c * n * OpusCeltTables.BAND_ALLOCATION[mid * len + j] << lm >> 2;
                if (bitsj > 0) {
                    bitsj = Math.max(0, bitsj + scratch[trimOffset + j]);
                }
                bitsj += offsets[j];
                if (bitsj >= scratch[thresh + j] || done) {
                    done = true;
                    // Don't allocate more than we can actually use.
                    psum += Math.min(bitsj, cap[j]);
                } else if (bitsj >= c << BITRES) {
                    psum += c << BITRES;
                }
            }
            if (psum > total) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        } while (lo <= hi);
        hi = lo--;
        for (int j = start; j < end; j++) {
            int n = EBANDS[j + 1] - EBANDS[j];
            int bits1j = c * n * OpusCeltTables.BAND_ALLOCATION[lo * len + j] << lm >> 2;
            int bits2j = hi >= NB_ALLOC_VECTORS ? cap[j] : c * n * OpusCeltTables.BAND_ALLOCATION[hi * len + j] << lm >> 2;
            if (bits1j > 0) {
                bits1j = Math.max(0, bits1j + scratch[trimOffset + j]);
            }
            if (bits2j > 0) {
                bits2j = Math.max(0, bits2j + scratch[trimOffset + j]);
            }
            if (lo > 0) {
                bits1j += offsets[j];
            }
            bits2j += offsets[j];
            if (offsets[j] > 0) {
                skipStart = j;
            }
            bits2j = Math.max(0, bits2j - bits1j);
            scratch[bits1 + j] = bits1j;
            scratch[bits2 + j] = bits2j;
        }
        interpBits2Pulses(start, end, skipStart, scratch, bits1, bits2, thresh, cap, total, skipRsv, intensityRsv,
                dualStereoRsv, pulses, ebits, finePriority, c, lm, ec, result);
    }

    private static void interpBits2Pulses(int start, int end, int skipStart, int[] s, int bits1, int bits2,
                                          int thresh, int[] cap, int total, int skipRsv, int intensityRsv,
                                          int dualStereoRsv, int[] bits, int[] ebits, int[] finePriority, int c,
                                          int lm, OpusRangeDecoder ec, Result result) {
        int allocFloor = c << BITRES;
        int stereo = c > 1 ? 1 : 0;
        int logM = lm << BITRES;
        int lo = 0;
        int hi = 1 << ALLOC_STEPS;
        for (int i = 0; i < ALLOC_STEPS; i++) {
            int mid = (lo + hi) >> 1;
            int psum = 0;
            boolean done = false;
            for (int j = end; j-- > start; ) {
                int tmp = s[bits1 + j] + (mid * s[bits2 + j] >> ALLOC_STEPS);
                if (tmp >= s[thresh + j] || done) {
                    done = true;
                    // Don't allocate more than we can actually use.
                    psum += Math.min(tmp, cap[j]);
                } else if (tmp >= allocFloor) {
                    psum += allocFloor;
                }
            }
            if (psum > total) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        int psum = 0;
        boolean done = false;
        for (int j = end; j-- > start; ) {
            int tmp = s[bits1 + j] + (lo * s[bits2 + j] >> ALLOC_STEPS);
            if (tmp < s[thresh + j] && !done) {
                tmp = tmp >= allocFloor ? allocFloor : 0;
            } else {
                done = true;
            }
            // Don't allocate more than we can actually use.
            tmp = Math.min(tmp, cap[j]);
            bits[j] = tmp;
            psum += tmp;
        }
        // Decide which bands to skip, working backwards from the end.
        int codedBands;
        for (codedBands = end; ; codedBands--) {
            int j = codedBands - 1;
            // Never skip the first band, nor a band that has been boosted by dynalloc. In the first case, we'd be
            // coding a bit to signal we're going to waste all the other bits. In the second case, we'd be coding a
            // bit to redistribute all the bits we just signaled should be concentrated in this band.
            if (j <= skipStart) {
                // Give the bit we reserved to end skipping back.
                total += skipRsv;
                break;
            }
            // Figure out how many left-over bits we would be adding to this band. This can include bits we've
            // stolen back from higher, skipped bands.
            int left = total - psum;
            int percoeff = Integer.divideUnsigned(left, EBANDS[codedBands] - EBANDS[start]);
            left -= (EBANDS[codedBands] - EBANDS[start]) * percoeff;
            int rem = Math.max(left - (EBANDS[j] - EBANDS[start]), 0);
            int bandWidth = EBANDS[codedBands] - EBANDS[j];
            int bandBits = bits[j] + percoeff * bandWidth + rem;
            // Only code a skip decision if we're above the threshold for this band. Otherwise it is force-skipped.
            // This ensures that we have enough bits to code the skip flag.
            if (bandBits >= Math.max(s[thresh + j], allocFloor + (1 << BITRES))) {
                if (ec.decodeBitLogp(1)) {
                    break;
                }
                // We used a bit to skip this band.
                psum += 1 << BITRES;
                bandBits -= 1 << BITRES;
            }
            // Reclaim the bits originally allocated to this band.
            psum -= bits[j] + intensityRsv;
            if (intensityRsv > 0) {
                intensityRsv = LOG2_FRAC_TABLE[j - start];
            }
            psum += intensityRsv;
            if (bandBits >= allocFloor) {
                // If we have enough for a fine energy bit per channel, use it.
                psum += allocFloor;
                bits[j] = allocFloor;
            } else {
                // Otherwise this band gets nothing at all.
                bits[j] = 0;
            }
        }
        // Code the intensity and dual stereo parameters.
        int intensity;
        if (intensityRsv > 0) {
            intensity = start + (int) ec.decodeUint(codedBands + 1 - start);
        } else {
            intensity = 0;
        }
        if (intensity <= start) {
            total += dualStereoRsv;
            dualStereoRsv = 0;
        }
        int dualStereo = dualStereoRsv > 0 && ec.decodeBitLogp(1) ? 1 : 0;

        // Allocate the remaining bits.
        int left = total - psum;
        int percoeff = Integer.divideUnsigned(left, EBANDS[codedBands] - EBANDS[start]);
        left -= (EBANDS[codedBands] - EBANDS[start]) * percoeff;
        for (int j = start; j < codedBands; j++) {
            bits[j] += percoeff * (EBANDS[j + 1] - EBANDS[j]);
        }
        for (int j = start; j < codedBands; j++) {
            int tmp = Math.min(left, EBANDS[j + 1] - EBANDS[j]);
            bits[j] += tmp;
            left -= tmp;
        }

        int balance = 0;
        int j;
        for (j = start; j < codedBands; j++) {
            int n0 = EBANDS[j + 1] - EBANDS[j];
            int n = n0 << lm;
            int bit = bits[j] + balance;
            int excess;
            if (n > 1) {
                excess = Math.max(bit - cap[j], 0);
                bits[j] = bit - excess;
                // Compensate for the extra DoF in stereo.
                int den = c * n + (c == 2 && n > 2 && dualStereo == 0 && j < intensity ? 1 : 0);
                int nclogn = den * (OpusCeltTables.LOG_N[j] + logM);
                // Offset for the number of fine bits by log2(N)/2 + FINE_OFFSET compared to their "fair share"
                // of total/N.
                int offset = (nclogn >> 1) - den * FINE_OFFSET;
                // N=2 is the only point that doesn't match the curve.
                if (n == 2) {
                    offset += den << BITRES >> 2;
                }
                // Changing the offset for allocating the second and third fine energy bit.
                if (bits[j] + offset < den * 2 << BITRES) {
                    offset += nclogn >> 2;
                } else if (bits[j] + offset < den * 3 << BITRES) {
                    offset += nclogn >> 3;
                }
                // Divide with rounding.
                ebits[j] = Math.max(0, bits[j] + offset + (den << (BITRES - 1)));
                ebits[j] = Integer.divideUnsigned(ebits[j], den) >> BITRES;
                // Make sure not to bust.
                if (c * ebits[j] > (bits[j] >> BITRES)) {
                    ebits[j] = bits[j] >> stereo >> BITRES;
                }
                // More than that is useless because that's about as far as PVQ can go.
                ebits[j] = Math.min(ebits[j], MAX_FINE_BITS);
                // If we rounded down or capped this band, make it a candidate for the final fine energy pass.
                finePriority[j] = ebits[j] * (den << BITRES) >= bits[j] + offset ? 1 : 0;
                // Remove the allocated fine bits; the rest are assigned to PVQ.
                bits[j] -= c * ebits[j] << BITRES;
            } else {
                // For N=1, all bits go to fine energy except for a single sign bit.
                excess = Math.max(0, bit - (c << BITRES));
                bits[j] = bit - excess;
                ebits[j] = 0;
                finePriority[j] = 1;
            }
            // Fine energy can't take advantage of the re-balancing in quant_all_bands(). Instead, do the
            // re-balancing here.
            if (excess > 0) {
                int extraFine = Math.min(excess >> (stereo + BITRES), MAX_FINE_BITS - ebits[j]);
                ebits[j] += extraFine;
                int extraBits = extraFine * c << BITRES;
                finePriority[j] = extraBits >= excess - balance ? 1 : 0;
                excess -= extraBits;
            }
            balance = excess;
        }
        // The skipped bands use all their bits for fine energy.
        for (; j < end; j++) {
            ebits[j] = bits[j] >> stereo >> BITRES;
            bits[j] = 0;
            finePriority[j] = ebits[j] < 1 ? 1 : 0;
        }
        result.intensity = intensity;
        result.dualStereo = dualStereo;
        result.balance = balance;
        result.codedBands = codedBands;
    }

    /** unquant_coarse_energy. */
    static void unquantCoarseEnergy(int start, int end, float[] oldEBands, boolean intra, OpusRangeDecoder dec,
                                    int c, int lm) {
        int prob = (lm * 2 + (intra ? 1 : 0)) * 42;
        int[] probModel = OpusCeltTables.E_PROB_MODEL;
        float[] prev = {0, 0};
        float coef;
        float beta;
        if (intra) {
            coef = 0;
            beta = BETA_INTRA;
        } else {
            beta = BETA_COEF[lm];
            coef = PRED_COEF[lm];
        }
        int budget = dec.storage * 8;
        // Decode at a fixed coarse resolution.
        for (int i = start; i < end; i++) {
            int ch = 0;
            do {
                int qi;
                int tell = dec.tell();
                if (budget - tell >= 15) {
                    int pi = 2 * Math.min(i, 20);
                    qi = dec.laplaceDecode(probModel[prob + pi] << 7, probModel[prob + pi + 1] << 6);
                } else if (budget - tell >= 2) {
                    qi = dec.decodeIcdf(SMALL_ENERGY_ICDF, 2);
                    qi = (qi >> 1) ^ -(qi & 1);
                } else if (budget - tell >= 1) {
                    qi = dec.decodeBitLogp(1) ? -1 : 0;
                } else {
                    qi = -1;
                }
                float q = qi;
                int idx = i + ch * NB_EBANDS;
                // MAXG is a plain comparison in C; Math.max would differ on signed zeros and NaN.
                oldEBands[idx] = -9f > oldEBands[idx] ? -9f : oldEBands[idx];
                float tmp = coef * oldEBands[idx] + prev[ch] + q;
                oldEBands[idx] = tmp;
                prev[ch] = prev[ch] + q - beta * q;
            } while (++ch < c);
        }
    }

    /** unquant_fine_energy without the extension layer's previous quantisation. */
    static void unquantFineEnergy(int start, int end, float[] oldEBands, int[] fineQuant, OpusRangeDecoder dec,
                                  int c) {
        for (int i = start; i < end; i++) {
            int extra = (short) fineQuant[i];
            if (fineQuant[i] <= 0) {
                continue;
            }
            if (dec.tell() + c * fineQuant[i] > dec.storage * 8) {
                continue;
            }
            int ch = 0;
            do {
                int q2 = dec.decodeBits(extra);
                float offset = (q2 + .5f) * (1 << (14 - extra)) * (1.f / 16384) - .5f;
                offset *= (1 << 14) * (1.f / 16384);
                oldEBands[i + ch * NB_EBANDS] += offset;
            } while (++ch < c);
        }
    }

    /** unquant_energy_finalise: spends the bits left at the end on one more fine energy bit per band. */
    static void unquantEnergyFinalise(int start, int end, float[] oldEBands, int[] fineQuant, int[] finePriority,
                                      int bitsLeft, OpusRangeDecoder dec, int c) {
        for (int prio = 0; prio < 2; prio++) {
            for (int i = start; i < end && bitsLeft >= c; i++) {
                if (fineQuant[i] >= MAX_FINE_BITS || finePriority[i] != prio) {
                    continue;
                }
                int ch = 0;
                do {
                    int q2 = dec.decodeBits(1);
                    float offset = (q2 - .5f) * (1 << (14 - fineQuant[i] - 1)) * (1.f / 16384);
                    if (oldEBands != null) {
                        oldEBands[i + ch * NB_EBANDS] += offset;
                    }
                    bitsLeft--;
                } while (++ch < c);
            }
        }
    }
}
