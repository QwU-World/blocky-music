/*
 * Java port of part of the libopus 1.6.1 decoder. libopus is Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO,
 * Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other
 * contributors to libopus, under the BSD-3-Clause licence (its CELT, src/ and include/ files under the two-clause form
 * of it). The copyright notices of the files this follows and the full licence text are in NOTICE.md, under
 * "libopus". QwU World's MIT licence does not cover this file.
 */
package ru.gora.musicbattle.audio.codec;

/**
 * CELT's inverse MDCT for the four sizes Opus uses (1920, 960, 480 and 240 points), built on the mixed-radix
 * KISS FFT, ported from libopus 1.6.1 celt/mdct.c (clt_mdct_backward_c) and celt/kiss_fft.c (opus_fft_impl and the
 * radix 2, 3, 4 and 5 butterflies) in their float form.
 *
 * <p>The FFT works in place on interleaved real and imaginary floats inside the caller's output buffer, exactly
 * where the C code puts it, and every multiply and add is written in the same order as the C source, so the result
 * is the same float for float as a libopus build without fused multiply-adds.
 */
final class OpusCeltMdct {
    private static final int OVERLAP = 120;

    /** Shift and radix/stride factors of the four FFT states (480, 240, 120, 60 points; fft_state48000_960_0..3). */
    private static final int[] SHIFT = {-1, 1, 2, 3};
    private static final int[][] FACTORS = {
            {5, 96, 3, 32, 4, 8, 2, 4, 4, 1},
            {5, 48, 3, 16, 4, 4, 4, 1},
            {5, 24, 3, 8, 2, 4, 4, 1},
            {5, 12, 3, 4, 4, 1},
    };
    private static final short[][] BITREV = {
            OpusCeltTables.FFT_BITREV_480, OpusCeltTables.FFT_BITREV_240,
            OpusCeltTables.FFT_BITREV_120, OpusCeltTables.FFT_BITREV_60,
    };
    private static final float[] TW = OpusCeltTables.FFT_TWIDDLES;
    /** The cumulative strides and the number of stages of each state, computed once as opus_fft_impl does. */
    private static final int[][] FSTRIDE = new int[4][9];
    private static final int[] STAGES = new int[4];

    static {
        for (int s = 0; s < 4; s++) {
            int[] factors = FACTORS[s];
            int[] fstride = FSTRIDE[s];
            fstride[0] = 1;
            int l = 0;
            int m;
            do {
                int p = factors[2 * l];
                m = factors[2 * l + 1];
                fstride[l + 1] = fstride[l] * p;
                l++;
            } while (m != 1);
            STAGES[s] = l;
        }
    }

    private OpusCeltMdct() {
    }

    /**
     * clt_mdct_backward: transforms {@code N2} coefficients read from {@code in[inOff]} with the given stride into
     * {@code out[outOff .. outOff + overlap/2 + N2)}, overlap-adding the first {@code overlap} samples with what is
     * already there.
     *
     * @param shift 0 for the 1920-point transform (20 ms), up to 3 for 240 points (2.5 ms)
     */
    static void backward(float[] in, int inOff, float[] out, int outOff, int shift, int stride) {
        float[] trigTab = OpusCeltTables.MDCT_TWIDDLES;
        int n = 1920;
        int trig = 0;
        for (int i = 0; i < shift; i++) {
            n >>= 1;
            trig += n;
        }
        int n2 = n >> 1;
        int n4 = n >> 2;
        float[] window = OpusCeltTables.WINDOW;

        // Pre-rotate, writing straight into bit-reversed order.
        {
            int xp1 = inOff;
            int xp2 = inOff + stride * (n2 - 1);
            int yp = outOff + (OVERLAP >> 1);
            short[] bitrev = BITREV[shift];
            for (int i = 0; i < n4; i++) {
                int rev = bitrev[i];
                float x1 = in[xp1];
                float x2 = in[xp2];
                float yr = x2 * trigTab[trig + i] + x1 * trigTab[trig + n4 + i];
                float yi = x1 * trigTab[trig + i] - x2 * trigTab[trig + n4 + i];
                // Real and imaginary are swapped because a forward FFT stands in for the inverse one.
                out[yp + 2 * rev + 1] = yr;
                out[yp + 2 * rev] = yi;
                xp1 += 2 * stride;
                xp2 -= 2 * stride;
            }
        }

        fft(shift, out, outOff + (OVERLAP >> 1));

        // Post-rotate and de-shuffle from both ends at once, in place.
        {
            int yp0 = outOff + (OVERLAP >> 1);
            int yp1 = outOff + (OVERLAP >> 1) + n2 - 2;
            for (int i = 0; i < (n4 + 1) >> 1; i++) {
                float re = out[yp0 + 1];
                float im = out[yp0];
                float t0 = trigTab[trig + i];
                float t1 = trigTab[trig + n4 + i];
                float yr = re * t0 + im * t1;
                float yi = re * t1 - im * t0;
                re = out[yp1 + 1];
                im = out[yp1];
                out[yp0] = yr;
                out[yp1 + 1] = yi;
                t0 = trigTab[trig + (n4 - i - 1)];
                t1 = trigTab[trig + (n2 - i - 1)];
                yr = re * t0 + im * t1;
                yi = re * t1 - im * t0;
                out[yp1] = yr;
                out[yp0 + 1] = yi;
                yp0 += 2;
                yp1 -= 2;
            }
        }

        // Mirror on both sides for TDAC.
        {
            int xp1 = outOff + OVERLAP - 1;
            int yp1 = outOff;
            int wp1 = 0;
            int wp2 = OVERLAP - 1;
            for (int i = 0; i < OVERLAP / 2; i++) {
                float x1 = out[xp1];
                float x2 = out[yp1];
                out[yp1++] = x2 * window[wp2] - x1 * window[wp1];
                out[xp1--] = x2 * window[wp1] + x1 * window[wp2];
                wp1++;
                wp2--;
            }
        }
    }

    /** opus_fft_impl for state {@code s} on the complex samples at {@code f[off]} (interleaved re, im). */
    private static void fft(int s, float[] f, int off) {
        int[] factors = FACTORS[s];
        int shift = SHIFT[s] > 0 ? SHIFT[s] : 0;
        int[] fstride = FSTRIDE[s];
        int l = STAGES[s];
        int m = factors[2 * l - 1];
        for (int i = l - 1; i >= 0; i--) {
            int m2 = i != 0 ? factors[2 * i - 1] : 1;
            switch (factors[2 * i]) {
                case 2 -> bfly2(f, off, m, fstride[i]);
                case 4 -> bfly4(f, off, fstride[i] << shift, m, fstride[i], m2);
                case 3 -> bfly3(f, off, fstride[i] << shift, m, fstride[i], m2);
                case 5 -> bfly5(f, off, fstride[i] << shift, m, fstride[i], m2);
                default -> throw new IllegalStateException("radix");
            }
            m = m2;
        }
    }

    private static void bfly2(float[] f, int off, int m, int n) {
        // m is 4 here, because the radix-2 step always follows a radix-4 one.
        float tw = 0.7071067812f;
        int fo = off;
        for (int i = 0; i < n; i++) {
            int fo2 = fo + 8;
            float tr;
            float ti;
            tr = f[fo2];
            ti = f[fo2 + 1];
            f[fo2] = f[fo] - tr;
            f[fo2 + 1] = f[fo + 1] - ti;
            f[fo] += tr;
            f[fo + 1] += ti;

            tr = (f[fo2 + 2] + f[fo2 + 3]) * tw;
            ti = (f[fo2 + 3] - f[fo2 + 2]) * tw;
            f[fo2 + 2] = f[fo + 2] - tr;
            f[fo2 + 3] = f[fo + 3] - ti;
            f[fo + 2] += tr;
            f[fo + 3] += ti;

            tr = f[fo2 + 5];
            ti = -f[fo2 + 4];
            f[fo2 + 4] = f[fo + 4] - tr;
            f[fo2 + 5] = f[fo + 5] - ti;
            f[fo + 4] += tr;
            f[fo + 5] += ti;

            tr = (f[fo2 + 7] - f[fo2 + 6]) * tw;
            ti = -(f[fo2 + 7] + f[fo2 + 6]) * tw;
            f[fo2 + 6] = f[fo + 6] - tr;
            f[fo2 + 7] = f[fo + 7] - ti;
            f[fo + 6] += tr;
            f[fo + 7] += ti;
            fo += 16;
        }
    }

    private static void bfly4(float[] f, int off, int fstride, int m, int n, int mm) {
        if (m == 1) {
            // Degenerate case where all the twiddles are 1.
            int fo = off;
            for (int i = 0; i < n; i++) {
                float s0r = f[fo] - f[fo + 4];
                float s0i = f[fo + 1] - f[fo + 5];
                f[fo] += f[fo + 4];
                f[fo + 1] += f[fo + 5];
                float s1r = f[fo + 2] + f[fo + 6];
                float s1i = f[fo + 3] + f[fo + 7];
                f[fo + 4] = f[fo] - s1r;
                f[fo + 5] = f[fo + 1] - s1i;
                f[fo] += s1r;
                f[fo + 1] += s1i;
                s1r = f[fo + 2] - f[fo + 6];
                s1i = f[fo + 3] - f[fo + 7];
                f[fo + 2] = s0r + s1i;
                f[fo + 3] = s0i - s1r;
                f[fo + 6] = s0r - s1i;
                f[fo + 7] = s0i + s1r;
                fo += 8;
            }
            return;
        }
        int m2 = 2 * m;
        int m3 = 3 * m;
        for (int i = 0; i < n; i++) {
            int fo = off + 2 * i * mm;
            int tw1 = 0;
            int tw2 = 0;
            int tw3 = 0;
            for (int j = 0; j < m; j++) {
                int a1 = fo + 2 * m;
                int a2 = fo + 2 * m2;
                int a3 = fo + 2 * m3;
                float s0r = f[a1] * TW[2 * tw1] - f[a1 + 1] * TW[2 * tw1 + 1];
                float s0i = f[a1] * TW[2 * tw1 + 1] + f[a1 + 1] * TW[2 * tw1];
                float s1r = f[a2] * TW[2 * tw2] - f[a2 + 1] * TW[2 * tw2 + 1];
                float s1i = f[a2] * TW[2 * tw2 + 1] + f[a2 + 1] * TW[2 * tw2];
                float s2r = f[a3] * TW[2 * tw3] - f[a3 + 1] * TW[2 * tw3 + 1];
                float s2i = f[a3] * TW[2 * tw3 + 1] + f[a3 + 1] * TW[2 * tw3];
                float s5r = f[fo] - s1r;
                float s5i = f[fo + 1] - s1i;
                f[fo] += s1r;
                f[fo + 1] += s1i;
                float s3r = s0r + s2r;
                float s3i = s0i + s2i;
                float s4r = s0r - s2r;
                float s4i = s0i - s2i;
                f[a2] = f[fo] - s3r;
                f[a2 + 1] = f[fo + 1] - s3i;
                tw1 += fstride;
                tw2 += fstride * 2;
                tw3 += fstride * 3;
                f[fo] += s3r;
                f[fo + 1] += s3i;
                f[a1] = s5r + s4i;
                f[a1 + 1] = s5i - s4r;
                f[a3] = s5r - s4i;
                f[a3 + 1] = s5i + s4r;
                fo += 2;
            }
        }
    }

    private static void bfly3(float[] f, int off, int fstride, int m, int n, int mm) {
        int m2 = 2 * m;
        float epi3i = TW[2 * (fstride * m) + 1];
        for (int i = 0; i < n; i++) {
            int fo = off + 2 * i * mm;
            int tw1 = 0;
            int tw2 = 0;
            int k = m;
            do {
                int a1 = fo + 2 * m;
                int a2 = fo + 2 * m2;
                float s1r = f[a1] * TW[2 * tw1] - f[a1 + 1] * TW[2 * tw1 + 1];
                float s1i = f[a1] * TW[2 * tw1 + 1] + f[a1 + 1] * TW[2 * tw1];
                float s2r = f[a2] * TW[2 * tw2] - f[a2 + 1] * TW[2 * tw2 + 1];
                float s2i = f[a2] * TW[2 * tw2 + 1] + f[a2 + 1] * TW[2 * tw2];
                float s3r = s1r + s2r;
                float s3i = s1i + s2i;
                float s0r = s1r - s2r;
                float s0i = s1i - s2i;
                tw1 += fstride;
                tw2 += fstride * 2;
                f[a1] = f[fo] - s3r * .5f;
                f[a1 + 1] = f[fo + 1] - s3i * .5f;
                s0r *= epi3i;
                s0i *= epi3i;
                f[fo] += s3r;
                f[fo + 1] += s3i;
                f[a2] = f[a1] + s0i;
                f[a2 + 1] = f[a1 + 1] - s0r;
                f[a1] = f[a1] - s0i;
                f[a1 + 1] = f[a1 + 1] + s0r;
                fo += 2;
            } while (--k != 0);
        }
    }

    private static void bfly5(float[] f, int off, int fstride, int m, int n, int mm) {
        float yar = TW[2 * (fstride * m)];
        float yai = TW[2 * (fstride * m) + 1];
        float ybr = TW[2 * (fstride * 2 * m)];
        float ybi = TW[2 * (fstride * 2 * m) + 1];
        for (int i = 0; i < n; i++) {
            int f0 = off + 2 * i * mm;
            int f1 = f0 + 2 * m;
            int f2 = f0 + 4 * m;
            int f3 = f0 + 6 * m;
            int f4 = f0 + 8 * m;
            for (int u = 0; u < m; ++u) {
                float s0r = f[f0];
                float s0i = f[f0 + 1];
                int t1 = 2 * (u * fstride);
                int t2 = 2 * (2 * u * fstride);
                int t3 = 2 * (3 * u * fstride);
                int t4 = 2 * (4 * u * fstride);
                float s1r = f[f1] * TW[t1] - f[f1 + 1] * TW[t1 + 1];
                float s1i = f[f1] * TW[t1 + 1] + f[f1 + 1] * TW[t1];
                float s2r = f[f2] * TW[t2] - f[f2 + 1] * TW[t2 + 1];
                float s2i = f[f2] * TW[t2 + 1] + f[f2 + 1] * TW[t2];
                float s3r = f[f3] * TW[t3] - f[f3 + 1] * TW[t3 + 1];
                float s3i = f[f3] * TW[t3 + 1] + f[f3 + 1] * TW[t3];
                float s4r = f[f4] * TW[t4] - f[f4 + 1] * TW[t4 + 1];
                float s4i = f[f4] * TW[t4 + 1] + f[f4 + 1] * TW[t4];

                float s7r = s1r + s4r;
                float s7i = s1i + s4i;
                float s10r = s1r - s4r;
                float s10i = s1i - s4i;
                float s8r = s2r + s3r;
                float s8i = s2i + s3i;
                float s9r = s2r - s3r;
                float s9i = s2i - s3i;

                f[f0] = f[f0] + (s7r + s8r);
                f[f0 + 1] = f[f0 + 1] + (s7i + s8i);

                float s5r = s0r + (s7r * yar + s8r * ybr);
                float s5i = s0i + (s7i * yar + s8i * ybr);
                float s6r = s10i * yai + s9i * ybi;
                float s6i = -(s10r * yai + s9r * ybi);

                f[f1] = s5r - s6r;
                f[f1 + 1] = s5i - s6i;
                f[f4] = s5r + s6r;
                f[f4 + 1] = s5i + s6i;

                float s11r = s0r + (s7r * ybr + s8r * yar);
                float s11i = s0i + (s7i * ybr + s8i * yar);
                float s12r = s9i * yai - s10i * ybi;
                float s12i = s10r * ybi - s9r * yai;

                f[f2] = s11r + s12r;
                f[f2 + 1] = s11i + s12i;
                f[f3] = s11r - s12r;
                f[f3 + 1] = s11i - s12i;

                f0 += 2;
                f1 += 2;
                f2 += 2;
                f3 += 2;
                f4 += 2;
            }
        }
    }
}
