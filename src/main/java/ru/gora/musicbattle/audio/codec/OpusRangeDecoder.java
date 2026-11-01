/*
 * Java port of part of the libopus 1.6.1 decoder. libopus is Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO,
 * Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other
 * contributors to libopus, under the BSD-3-Clause licence (its CELT, src/ and include/ files under the two-clause form
 * of it). The copyright notices of the files this follows and the full licence text are in NOTICE.md, under
 * "libopus". QwU World's MIT licence does not cover this file.
 */
package ru.gora.musicbattle.audio.codec;

/**
 * The range decoder that every Opus frame is coded with (RFC 6716 section 4.1), ported from libopus 1.6.1
 * celt/entdec.c and celt/entcode.c, with the Laplace decoder of CELT's coarse energy from celt/laplace.c.
 *
 * <p>The C code keeps its state in unsigned 32-bit integers; here they are longs that never leave 0..2^32-1, so the
 * comparisons and divisions behave exactly as the unsigned C ones. Raw bits are read from the end of the frame and
 * range-coded symbols from the start, as in the reference, so the two can share one buffer.
 */
final class OpusRangeDecoder {
    static final int BITRES = 3;
    private static final int SYM_BITS = 8;
    private static final int CODE_BITS = 32;
    private static final int SYM_MAX = (1 << SYM_BITS) - 1;
    private static final int CODE_EXTRA = (CODE_BITS - 2) % SYM_BITS + 1;
    private static final long CODE_TOP = 1L << (CODE_BITS - 1);
    private static final long CODE_BOT = CODE_TOP >>> SYM_BITS;
    private static final int UINT_BITS = 8;
    private static final int WINDOW_SIZE = 32;
    private static final long MASK32 = 0xFFFFFFFFL;
    private static final int LAPLACE_MINP = 1;
    private static final int LAPLACE_NMIN = 16;
    private static final int[] TELL_CORRECTION = {35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535};

    private byte[] buf;
    private int base;
    int storage;
    private int endOffs;
    private long endWindow;
    private int nendBits;
    int nbitsTotal;
    private int offs;
    long rng;
    private long val;
    private long ext;
    private int rem;
    boolean error;

    /** Starts decoding {@code storage} bytes of {@code data} from {@code offset} (ec_dec_init). */
    void init(byte[] data, int offset, int storage) {
        this.buf = data;
        this.base = offset;
        this.storage = storage;
        endOffs = 0;
        endWindow = 0;
        nendBits = 0;
        nbitsTotal = CODE_BITS + 1 - ((CODE_BITS - CODE_EXTRA) / SYM_BITS) * SYM_BITS;
        offs = 0;
        rng = 1L << CODE_EXTRA;
        rem = readByte();
        val = rng - 1 - (rem >> (SYM_BITS - CODE_EXTRA));
        error = false;
        normalize();
    }

    /** Copies the whole state, so a caller can try a decode and go back (used for the redundancy check). */
    void copyFrom(OpusRangeDecoder other) {
        buf = other.buf;
        base = other.base;
        storage = other.storage;
        endOffs = other.endOffs;
        endWindow = other.endWindow;
        nendBits = other.nendBits;
        nbitsTotal = other.nbitsTotal;
        offs = other.offs;
        rng = other.rng;
        val = other.val;
        ext = other.ext;
        rem = other.rem;
        error = other.error;
    }

    private int readByte() {
        return offs < storage ? buf[base + offs++] & 0xFF : 0;
    }

    private int readByteFromEnd() {
        return endOffs < storage ? buf[base + storage - ++endOffs] & 0xFF : 0;
    }

    private void normalize() {
        while (rng <= CODE_BOT) {
            if (rng == 0) {
                // Only reachable through an inconsistent caller on a damaged frame; C would spin here forever.
                throw new IllegalStateException("The Opus range decoder collapsed.");
            }
            nbitsTotal += SYM_BITS;
            rng = (rng << SYM_BITS) & MASK32;
            int sym = rem;
            rem = readByte();
            sym = (sym << SYM_BITS | rem) >> (SYM_BITS - CODE_EXTRA);
            val = ((val << SYM_BITS) + (SYM_MAX & ~sym)) & (CODE_TOP - 1);
        }
    }

    /** ec_decode: the cumulative frequency of the next symbol in a distribution of total {@code ft}. */
    int decode(int ft) {
        ext = rng / ft;
        long s = val / ext;
        return (int) (ft - Math.min(s + 1, ft));
    }

    /** ec_decode_bin: as {@link #decode} with a total of {@code 1 << bits}. */
    int decodeBin(int bits) {
        ext = rng >>> bits;
        long s = val / ext;
        long ft = 1L << bits;
        return (int) (ft - Math.min(s + 1, ft));
    }

    /** ec_dec_update: consumes the symbol whose cumulative range is [fl, fh) of ft. */
    void update(int fl, int fh, int ft) {
        long s = (ext * (ft - fh)) & MASK32;
        val = (val - s) & MASK32;
        rng = fl > 0 ? (ext * (fh - fl)) & MASK32 : (rng - s) & MASK32;
        normalize();
    }

    /** ec_dec_bit_logp: one bit whose probability of being 1 is {@code 1/2^logp}. */
    boolean decodeBitLogp(int logp) {
        long r = rng;
        long d = val;
        long s = r >>> logp;
        boolean ret = d < s;
        if (!ret) {
            val = d - s;
        }
        rng = ret ? s : r - s;
        normalize();
        return ret;
    }

    /** ec_dec_icdf with an inverse cumulative table stored from {@code offset} of {@code icdf}. */
    int decodeIcdf(short[] icdf, int offset, int ftb) {
        long s = rng;
        long d = val;
        long r = s >>> ftb;
        int ret = -1;
        long t;
        do {
            t = s;
            s = r * icdf[offset + ++ret];
        } while (d < s);
        val = d - s;
        rng = t - s;
        normalize();
        return ret;
    }

    int decodeIcdf(short[] icdf, int ftb) {
        return decodeIcdf(icdf, 0, ftb);
    }

    /** ec_dec_icdf for CELT's byte tables, given as ints. */
    int decodeIcdf(int[] icdf, int ftb) {
        long s = rng;
        long d = val;
        long r = s >>> ftb;
        int ret = -1;
        long t;
        do {
            t = s;
            s = r * icdf[++ret];
        } while (d < s);
        val = d - s;
        rng = t - s;
        normalize();
        return ret;
    }

    /** ec_dec_uint: a uniformly distributed integer in [0, ft), ft up to 2^32 - 1. */
    long decodeUint(long ft) {
        ft--;
        int ftb = ilog(ft);
        if (ftb > UINT_BITS) {
            ftb -= UINT_BITS;
            int f = (int) (ft >>> ftb) + 1;
            int s = decode(f);
            update(s, s + 1, f);
            long t = ((long) s << ftb | decodeBits(ftb)) & MASK32;
            if (t <= ft) {
                return t;
            }
            error = true;
            return ft;
        }
        int f = (int) (ft + 1);
        int s = decode(f);
        update(s, s + 1, f);
        return s;
    }

    /** ec_dec_bits: {@code bits} raw bits from the end of the frame. */
    int decodeBits(int bits) {
        long window = endWindow;
        int available = nendBits;
        if (available < bits) {
            do {
                window |= (long) readByteFromEnd() << available;
                available += SYM_BITS;
            } while (available <= WINDOW_SIZE - SYM_BITS);
        }
        int ret = (int) (window & ((1L << bits) - 1));
        window >>>= bits;
        available -= bits;
        endWindow = window & MASK32;
        nendBits = available;
        nbitsTotal += bits;
        return ret;
    }

    /** ec_tell: bits used so far, rounded up. */
    int tell() {
        return nbitsTotal - ilog(rng);
    }

    /** ec_tell_frac: bits used so far in 1/8 bits, rounded up. */
    int tellFrac() {
        int nbits = nbitsTotal << BITRES;
        int l = ilog(rng);
        int r = (int) (rng >>> (l - 16));
        int b = (r >>> 12) - 8;
        if ((r & 0xFFFFFFFFL) > TELL_CORRECTION[b]) {
            b++;
        }
        l = (l << 3) + b;
        return nbits - l;
    }

    /** EC_ILOG: the number of bits needed to write v, 0 for 0. */
    static int ilog(long v) {
        return 64 - Long.numberOfLeadingZeros(v);
    }

    /** ec_laplace_decode: one coarse energy delta (celt/laplace.c). */
    int laplaceDecode(int fs, int decay) {
        int val = 0;
        int fl = 0;
        int fm = decodeBin(15);
        if (fm >= fs) {
            val++;
            fl = fs;
            fs = laplaceFreq1(fs, decay) + LAPLACE_MINP;
            while (fs > LAPLACE_MINP && fm >= fl + 2 * fs) {
                fs *= 2;
                fl += fs;
                fs = ((fs - 2 * LAPLACE_MINP) * decay) >> 15;
                fs += LAPLACE_MINP;
                val++;
            }
            if (fs <= LAPLACE_MINP) {
                int di = (fm - fl) >> 1;
                val += di;
                fl += 2 * di * LAPLACE_MINP;
            }
            if (fm < fl + fs) {
                val = -val;
            } else {
                fl += fs;
            }
        }
        update(fl, Math.min(fl + fs, 32768), 32768);
        return val;
    }

    private static int laplaceFreq1(int fs0, int decay) {
        int ft = 32768 - LAPLACE_MINP * (2 * LAPLACE_NMIN) - fs0;
        return (ft * (16384 - decay)) >> 15;
    }
}
