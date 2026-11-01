package ru.gora.musicbattle.audio.codec;

/**
 * The inverse MDCT and the overlap window for one Vorbis block size.
 *
 * <p>The transform is stb_vorbis's inverse_mdct (Sean Barrett, public domain / MIT), which implements the algorithm
 * of the paper "The use of multirate filter banks for coding of high quality digital audio". It is ported step for
 * step, in the same order of float operations, and comes out bit-identical to LWJGL's STBVorbis. The twiddle factors
 * and the window are computed with {@link StrictMath} so the output is also bit-identical on every machine and JVM,
 * which lets the regression checks pin exact checksums.
 *
 * <p>stb_vorbis's fast path assumes a block of at least 256 samples (its last three butterfly stages are fused, and
 * for 64 and 128 they overlap the first ones). Vorbis allows 64 and 128 too, and no common encoder uses them, so those
 * two sizes take a direct O(n^2) evaluation of the specification's formula instead, which the fast path matches.
 */
final class VorbisDecoderMdct {
    final int n;
    /** The rising half of the Vorbis window for a block of n samples: n / 2 values. */
    final float[] window;
    private final float[] a;
    private final float[] b;
    private final float[] c;
    private final int[] bitReverse;
    private final float[] buf2;
    private final float[] cosTable;

    VorbisDecoderMdct(int n) {
        this.n = n;
        int n2 = n >> 1;
        int n4 = n >> 2;
        int n8 = n >> 3;
        window = new float[n2];
        for (int i = 0; i < n2; i++) {
            float s = (float) StrictMath.sin((i + 0.5) / n2 * 0.5 * Math.PI);
            window[i] = (float) StrictMath.sin(0.5 * Math.PI * (s * s));
        }
        if (n < 256) {
            a = b = c = buf2 = null;
            bitReverse = null;
            // cos(2*pi/n * (i + 1/2 + n/4) * (k + 1/2)) depends only on (2i + 1 + n/2)(2k + 1) mod 4n.
            cosTable = new float[4 * n];
            for (int i = 0; i < cosTable.length; i++) {
                cosTable[i] = (float) StrictMath.cos(Math.PI * i / (2.0 * n));
            }
            return;
        }
        cosTable = null;
        a = new float[n2];
        b = new float[n2];
        c = new float[n4];
        for (int k = 0, k2 = 0; k < n4; k++, k2 += 2) {
            a[k2] = (float) StrictMath.cos(4 * k * Math.PI / n);
            a[k2 + 1] = (float) -StrictMath.sin(4 * k * Math.PI / n);
            b[k2] = (float) StrictMath.cos((k2 + 1) * Math.PI / n / 2) * 0.5f;
            b[k2 + 1] = (float) StrictMath.sin((k2 + 1) * Math.PI / n / 2) * 0.5f;
        }
        for (int k = 0, k2 = 0; k < n8; k++, k2 += 2) {
            c[k2] = (float) StrictMath.cos(2 * (k2 + 1) * Math.PI / n);
            c[k2 + 1] = (float) -StrictMath.sin(2 * (k2 + 1) * Math.PI / n);
        }
        int ld = VorbisDecoderSetup.ilog(n) - 1;
        bitReverse = new int[n8];
        for (int i = 0; i < n8; i++) {
            bitReverse[i] = (Integer.reverse(i) >>> (32 - ld + 3)) << 2;
        }
        buf2 = new float[n2];
    }

    /** Turns the n / 2 spectral values at the start of buffer into n time samples, in place. */
    void inverse(float[] buffer) {
        if (cosTable != null) {
            inverseDirect(buffer);
            return;
        }
        final int n = this.n;
        final int n2 = n >> 1;
        final int n4 = n >> 2;
        final int n8 = n >> 3;
        final float[] a = this.a;
        final float[] v = buf2;
        final float[] u = buffer;

        // Copy and reflect the spectral data, merged with step 0. stb_vorbis notes that this adds each value to
        // itself, so everything after is half size; the B table carries the compensation.
        {
            int d = n2 - 2;
            int aa = 0;
            int e = 0;
            while (e != n2) {
                v[d + 1] = buffer[e] * a[aa] - buffer[e + 2] * a[aa + 1];
                v[d] = buffer[e] * a[aa + 1] + buffer[e + 2] * a[aa];
                d -= 2;
                aa += 2;
                e += 4;
            }
            e = n2 - 3;
            while (d >= 0) {
                v[d + 1] = -buffer[e + 2] * a[aa] - -buffer[e] * a[aa + 1];
                v[d] = -buffer[e + 2] * a[aa + 1] + -buffer[e] * a[aa];
                d -= 2;
                aa += 2;
                e -= 4;
            }
        }

        // Step 2.
        {
            int aa = n2 - 8;
            int e0 = n4;
            int e1 = 0;
            int d0 = n4;
            int d1 = 0;
            while (aa >= 0) {
                float v41_21 = v[e0 + 1] - v[e1 + 1];
                float v40_20 = v[e0] - v[e1];
                u[d0 + 1] = v[e0 + 1] + v[e1 + 1];
                u[d0] = v[e0] + v[e1];
                u[d1 + 1] = v41_21 * a[aa + 4] - v40_20 * a[aa + 5];
                u[d1] = v40_20 * a[aa + 4] + v41_21 * a[aa + 5];

                v41_21 = v[e0 + 3] - v[e1 + 3];
                v40_20 = v[e0 + 2] - v[e1 + 2];
                u[d0 + 3] = v[e0 + 3] + v[e1 + 3];
                u[d0 + 2] = v[e0 + 2] + v[e1 + 2];
                u[d1 + 3] = v41_21 * a[aa] - v40_20 * a[aa + 1];
                u[d1 + 2] = v40_20 * a[aa] + v41_21 * a[aa + 1];

                aa -= 8;
                d0 += 4;
                d1 += 4;
                e0 += 4;
                e1 += 4;
            }
        }

        // Step 3, with stb_vorbis's loop orders: r inside s while r repeats a lot, s inside r after that, and the
        // last three iterations fused because their twiddles are all 0, 1 and one constant.
        int ld = VorbisDecoderSetup.ilog(n) - 1;
        step3Iter0(n >> 4, u, n2 - 1, -(n >> 3), a);
        step3Iter0(n >> 4, u, n2 - 1 - n4, -(n >> 3), a);

        step3InnerR(n >> 5, u, n2 - 1, -(n >> 4), a, 16);
        step3InnerR(n >> 5, u, n2 - 1 - n8, -(n >> 4), a, 16);
        step3InnerR(n >> 5, u, n2 - 1 - n8 * 2, -(n >> 4), a, 16);
        step3InnerR(n >> 5, u, n2 - 1 - n8 * 3, -(n >> 4), a, 16);

        int l = 2;
        for (; l < (ld - 3) >> 1; l++) {
            int k0 = n >> (l + 2);
            int k0Half = k0 >> 1;
            int lim = 1 << (l + 1);
            for (int i = 0; i < lim; i++) {
                step3InnerR(n >> (l + 4), u, n2 - 1 - k0 * i, -k0Half, a, 1 << (l + 3));
            }
        }
        for (; l < ld - 6; l++) {
            int k0 = n >> (l + 2);
            int k1 = 1 << (l + 3);
            int k0Half = k0 >> 1;
            int rlim = n >> (l + 6);
            int lim = 1 << (l + 1);
            int aBase = 0;
            int iOff = n2 - 1;
            for (int r = rlim; r > 0; r--) {
                step3InnerS(lim, u, iOff, -k0Half, a, aBase, k1, k0);
                aBase += k1 * 4;
                iOff -= 8;
            }
        }
        step3InnerSLd654(n >> 5, u, n2 - 1, a, n);

        // Steps 4, 5 and 6: the bit reversal, from u back into v.
        {
            int br = 0;
            int d0 = n4 - 4;
            int d1 = n2 - 4;
            int[] bitReverse = this.bitReverse;
            while (d0 >= 0) {
                int k4 = bitReverse[br];
                v[d1 + 3] = u[k4];
                v[d1 + 2] = u[k4 + 1];
                v[d0 + 3] = u[k4 + 2];
                v[d0 + 2] = u[k4 + 3];

                k4 = bitReverse[br + 1];
                v[d1 + 1] = u[k4];
                v[d1] = u[k4 + 1];
                v[d0 + 1] = u[k4 + 2];
                v[d0] = u[k4 + 3];

                d0 -= 4;
                d1 -= 4;
                br += 2;
            }
        }

        // Step 7, in place in v.
        {
            float[] c = this.c;
            int ci = 0;
            int d = 0;
            int e = n2 - 4;
            while (d < e) {
                float a02 = v[d] - v[e + 2];
                float a11 = v[d + 1] + v[e + 3];

                float b0 = c[ci + 1] * a02 + c[ci] * a11;
                float b1 = c[ci + 1] * a11 - c[ci] * a02;

                float b2 = v[d] + v[e + 2];
                float b3 = v[d + 1] - v[e + 3];

                v[d] = b2 + b0;
                v[d + 1] = b3 + b1;
                v[e + 2] = b2 - b0;
                v[e + 3] = b1 - b3;

                a02 = v[d + 2] - v[e];
                a11 = v[d + 3] + v[e + 1];

                b0 = c[ci + 3] * a02 + c[ci + 2] * a11;
                b1 = c[ci + 3] * a11 - c[ci + 2] * a02;

                b2 = v[d + 2] + v[e];
                b3 = v[d + 3] - v[e + 1];

                v[d + 2] = b2 + b0;
                v[d + 3] = b3 + b1;
                v[e] = b2 - b0;
                v[e + 1] = b1 - b3;

                ci += 4;
                d += 4;
                e -= 4;
            }
        }

        // Step 8 and the decode kernel: pairs from v pushed straight into the four quarters of the output.
        {
            float[] b = this.b;
            int bi = n2 - 8;
            int e = n2 - 8;
            int d0 = 0;
            int d1 = n2 - 4;
            int d2 = n2;
            int d3 = n - 4;
            while (e >= 0) {
                float p3 = v[e + 6] * b[bi + 7] - v[e + 7] * b[bi + 6];
                float p2 = -v[e + 6] * b[bi + 6] - v[e + 7] * b[bi + 7];

                buffer[d0] = p3;
                buffer[d1 + 3] = -p3;
                buffer[d2] = p2;
                buffer[d3 + 3] = p2;

                float p1 = v[e + 4] * b[bi + 5] - v[e + 5] * b[bi + 4];
                float p0 = -v[e + 4] * b[bi + 4] - v[e + 5] * b[bi + 5];

                buffer[d0 + 1] = p1;
                buffer[d1 + 2] = -p1;
                buffer[d2 + 1] = p0;
                buffer[d3 + 2] = p0;

                p3 = v[e + 2] * b[bi + 3] - v[e + 3] * b[bi + 2];
                p2 = -v[e + 2] * b[bi + 2] - v[e + 3] * b[bi + 3];

                buffer[d0 + 2] = p3;
                buffer[d1 + 1] = -p3;
                buffer[d2 + 2] = p2;
                buffer[d3 + 1] = p2;

                p1 = v[e] * b[bi + 1] - v[e + 1] * b[bi];
                p0 = -v[e] * b[bi] - v[e + 1] * b[bi + 1];

                buffer[d0 + 3] = p1;
                buffer[d1] = -p1;
                buffer[d2 + 3] = p0;
                buffer[d3] = p0;

                bi -= 8;
                e -= 8;
                d0 += 4;
                d2 += 4;
                d1 -= 4;
                d3 -= 4;
            }
        }
    }

    private static void step3Iter0(int n, float[] e, int iOff, int kOff, float[] a) {
        int ee0 = iOff;
        int ee2 = ee0 + kOff;
        int ai = 0;
        for (int i = n >> 2; i > 0; i--) {
            float k00_20 = e[ee0] - e[ee2];
            float k01_21 = e[ee0 - 1] - e[ee2 - 1];
            e[ee0] += e[ee2];
            e[ee0 - 1] += e[ee2 - 1];
            e[ee2] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[ee2 - 1] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += 8;

            k00_20 = e[ee0 - 2] - e[ee2 - 2];
            k01_21 = e[ee0 - 3] - e[ee2 - 3];
            e[ee0 - 2] += e[ee2 - 2];
            e[ee0 - 3] += e[ee2 - 3];
            e[ee2 - 2] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[ee2 - 3] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += 8;

            k00_20 = e[ee0 - 4] - e[ee2 - 4];
            k01_21 = e[ee0 - 5] - e[ee2 - 5];
            e[ee0 - 4] += e[ee2 - 4];
            e[ee0 - 5] += e[ee2 - 5];
            e[ee2 - 4] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[ee2 - 5] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += 8;

            k00_20 = e[ee0 - 6] - e[ee2 - 6];
            k01_21 = e[ee0 - 7] - e[ee2 - 7];
            e[ee0 - 6] += e[ee2 - 6];
            e[ee0 - 7] += e[ee2 - 7];
            e[ee2 - 6] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[ee2 - 7] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += 8;
            ee0 -= 8;
            ee2 -= 8;
        }
    }

    private static void step3InnerR(int lim, float[] e, int d0, int kOff, float[] a, int k1) {
        int e0 = d0;
        int e2 = e0 + kOff;
        int ai = 0;
        for (int i = lim >> 2; i > 0; i--) {
            float k00_20 = e[e0] - e[e2];
            float k01_21 = e[e0 - 1] - e[e2 - 1];
            e[e0] += e[e2];
            e[e0 - 1] += e[e2 - 1];
            e[e2] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[e2 - 1] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += k1;

            k00_20 = e[e0 - 2] - e[e2 - 2];
            k01_21 = e[e0 - 3] - e[e2 - 3];
            e[e0 - 2] += e[e2 - 2];
            e[e0 - 3] += e[e2 - 3];
            e[e2 - 2] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[e2 - 3] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += k1;

            k00_20 = e[e0 - 4] - e[e2 - 4];
            k01_21 = e[e0 - 5] - e[e2 - 5];
            e[e0 - 4] += e[e2 - 4];
            e[e0 - 5] += e[e2 - 5];
            e[e2 - 4] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[e2 - 5] = k01_21 * a[ai] + k00_20 * a[ai + 1];
            ai += k1;

            k00_20 = e[e0 - 6] - e[e2 - 6];
            k01_21 = e[e0 - 7] - e[e2 - 7];
            e[e0 - 6] += e[e2 - 6];
            e[e0 - 7] += e[e2 - 7];
            e[e2 - 6] = k00_20 * a[ai] - k01_21 * a[ai + 1];
            e[e2 - 7] = k01_21 * a[ai] + k00_20 * a[ai + 1];

            e0 -= 8;
            e2 -= 8;
            ai += k1;
        }
    }

    private static void step3InnerS(int n, float[] e, int iOff, int kOff, float[] a, int aBase, int aOff, int k0) {
        float a0 = a[aBase];
        float a1 = a[aBase + 1];
        float a2 = a[aBase + aOff];
        float a3 = a[aBase + aOff + 1];
        float a4 = a[aBase + aOff * 2];
        float a5 = a[aBase + aOff * 2 + 1];
        float a6 = a[aBase + aOff * 3];
        float a7 = a[aBase + aOff * 3 + 1];
        int ee0 = iOff;
        int ee2 = ee0 + kOff;
        for (int i = n; i > 0; i--) {
            float k00 = e[ee0] - e[ee2];
            float k11 = e[ee0 - 1] - e[ee2 - 1];
            e[ee0] = e[ee0] + e[ee2];
            e[ee0 - 1] = e[ee0 - 1] + e[ee2 - 1];
            e[ee2] = k00 * a0 - k11 * a1;
            e[ee2 - 1] = k11 * a0 + k00 * a1;

            k00 = e[ee0 - 2] - e[ee2 - 2];
            k11 = e[ee0 - 3] - e[ee2 - 3];
            e[ee0 - 2] = e[ee0 - 2] + e[ee2 - 2];
            e[ee0 - 3] = e[ee0 - 3] + e[ee2 - 3];
            e[ee2 - 2] = k00 * a2 - k11 * a3;
            e[ee2 - 3] = k11 * a2 + k00 * a3;

            k00 = e[ee0 - 4] - e[ee2 - 4];
            k11 = e[ee0 - 5] - e[ee2 - 5];
            e[ee0 - 4] = e[ee0 - 4] + e[ee2 - 4];
            e[ee0 - 5] = e[ee0 - 5] + e[ee2 - 5];
            e[ee2 - 4] = k00 * a4 - k11 * a5;
            e[ee2 - 5] = k11 * a4 + k00 * a5;

            k00 = e[ee0 - 6] - e[ee2 - 6];
            k11 = e[ee0 - 7] - e[ee2 - 7];
            e[ee0 - 6] = e[ee0 - 6] + e[ee2 - 6];
            e[ee0 - 7] = e[ee0 - 7] + e[ee2 - 7];
            e[ee2 - 6] = k00 * a6 - k11 * a7;
            e[ee2 - 7] = k11 * a6 + k00 * a7;

            ee0 -= k0;
            ee2 -= k0;
        }
    }

    private static void iter54(float[] e, int z) {
        float k00 = e[z] - e[z - 4];
        float y0 = e[z] + e[z - 4];
        float y2 = e[z - 2] + e[z - 6];
        float k22 = e[z - 2] - e[z - 6];

        e[z] = y0 + y2;
        e[z - 2] = y0 - y2;

        float k33 = e[z - 3] - e[z - 7];

        e[z - 4] = k00 + k33;
        e[z - 6] = k00 - k33;

        float k11 = e[z - 1] - e[z - 5];
        float y1 = e[z - 1] + e[z - 5];
        float y3 = e[z - 3] + e[z - 7];

        e[z - 1] = y1 + y3;
        e[z - 3] = y1 - y3;
        e[z - 5] = k11 - k22;
        e[z - 7] = k11 + k22;
    }

    private static void step3InnerSLd654(int n, float[] e, int iOff, float[] a, int baseN) {
        float a2 = a[baseN >> 3];
        int z = iOff;
        int base = z - 16 * n;
        while (z > base) {
            float k00 = e[z] - e[z - 8];
            float k11 = e[z - 1] - e[z - 9];
            float l00 = e[z - 2] - e[z - 10];
            float l11 = e[z - 3] - e[z - 11];
            e[z] = e[z] + e[z - 8];
            e[z - 1] = e[z - 1] + e[z - 9];
            e[z - 2] = e[z - 2] + e[z - 10];
            e[z - 3] = e[z - 3] + e[z - 11];
            e[z - 8] = k00;
            e[z - 9] = k11;
            e[z - 10] = (l00 + l11) * a2;
            e[z - 11] = (l11 - l00) * a2;

            k00 = e[z - 4] - e[z - 12];
            k11 = e[z - 5] - e[z - 13];
            l00 = e[z - 6] - e[z - 14];
            l11 = e[z - 7] - e[z - 15];
            e[z - 4] = e[z - 4] + e[z - 12];
            e[z - 5] = e[z - 5] + e[z - 13];
            e[z - 6] = e[z - 6] + e[z - 14];
            e[z - 7] = e[z - 7] + e[z - 15];
            e[z - 12] = k11;
            e[z - 13] = -k00;
            e[z - 14] = (l11 - l00) * a2;
            e[z - 15] = (l00 + l11) * -a2;

            iter54(e, z);
            iter54(e, z - 8);
            z -= 16;
        }
    }

    /**
     * The specification's inverse MDCT evaluated term by term. The fast path computes exactly this sum (its missing
     * factor of two is put back by the B table), which the checks confirm for every block size from 256 up.
     */
    private void inverseDirect(float[] buffer) {
        int n2 = n >> 1;
        int period = 4 * n;
        float[] spectrum = new float[n2];
        System.arraycopy(buffer, 0, spectrum, 0, n2);
        for (int i = 0; i < n; i++) {
            int t = 2 * i + 1 + n2;
            double sum = 0;
            for (int k = 0; k < n2; k++) {
                sum += spectrum[k] * cosTable[(int) ((long) t * (2 * k + 1) % period)];
            }
            buffer[i] = (float) sum;
        }
    }
}
