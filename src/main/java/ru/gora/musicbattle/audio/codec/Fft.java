package ru.gora.musicbattle.audio.codec;

/**
 * An in-place complex FFT of a fixed power-of-two size (at least 8), on separate real and imaginary arrays:
 * iterative radix 2 with the input in bit-reversed order. {@link #inverse} uses exp(+i 2 pi k n / N) and does not
 * scale, which is what the SBR QMF banks need.
 *
 * <p>The SBR banks run three of these per channel for every 64 output samples, so the transform is the decoder's
 * hottest loop and is written for speed: the bit reversal is a list of swaps, the first two stages (whose twiddle
 * factors are 1 and i) are done together without multiplications, and every later stage reads its own contiguous
 * run of twiddle factors.
 */
final class Fft {
    static final Fft FFT32 = new Fft(32);

    private final int n;
    /** The index pairs (i, j), i &lt; j, that bit reversal swaps. */
    private final int[] swapA;
    private final int[] swapB;
    /** exp(+i 2 pi k / size) for k &lt; size/2, for size = 8, 16, ... n, one stage after another. */
    private final double[] cos;
    private final double[] sin;

    private Fft(int n) {
        if (n < 8 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size " + n);
        }
        this.n = n;
        int bits = Integer.numberOfTrailingZeros(n);
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            if ((Integer.reverse(i) >>> (32 - bits)) > i) {
                pairs++;
            }
        }
        swapA = new int[pairs];
        swapB = new int[pairs];
        for (int i = 0, p = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (j > i) {
                swapA[p] = i;
                swapB[p] = j;
                p++;
            }
        }
        cos = new double[n - 4];
        sin = new double[n - 4];
        int t = 0;
        for (int size = 8; size <= n; size <<= 1) {
            for (int k = 0; k < size / 2; k++) {
                cos[t] = StrictMath.cos(2 * Math.PI * k / size);
                sin[t] = StrictMath.sin(2 * Math.PI * k / size);
                t++;
            }
        }
    }

    /** X(k) = sum x(n) exp(+i 2 pi k n / N), in place. */
    void inverse(double[] re, double[] im) {
        for (int p = 0; p < swapA.length; p++) {
            int i = swapA[p];
            int j = swapB[p];
            double t = re[i];
            re[i] = re[j];
            re[j] = t;
            t = im[i];
            im[i] = im[j];
            im[j] = t;
        }
        // Sizes 2 and 4 at once. In each run of four, the size-2 butterflies give A0, A1 from x0, x1 and B0, B1 from
        // x2, x3; the size-4 stage then adds B0 and i B1 (exp(+i 2 pi/4) = i) to A0 and A1, which needs no product.
        for (int s = 0; s < n; s += 4) {
            double r0 = re[s];
            double i0 = im[s];
            double r1 = re[s + 1];
            double i1 = im[s + 1];
            double r2 = re[s + 2];
            double i2 = im[s + 2];
            double r3 = re[s + 3];
            double i3 = im[s + 3];
            double a0r = r0 + r1;
            double a0i = i0 + i1;
            double a1r = r0 - r1;
            double a1i = i0 - i1;
            double b0r = r2 + r3;
            double b0i = i2 + i3;
            double b1r = r2 - r3;
            double b1i = i2 - i3;
            re[s] = a0r + b0r;
            im[s] = a0i + b0i;
            re[s + 2] = a0r - b0r;
            im[s + 2] = a0i - b0i;
            re[s + 1] = a1r - b1i;
            im[s + 1] = a1i + b1r;
            re[s + 3] = a1r + b1i;
            im[s + 3] = a1i - b1r;
        }
        int t = 0;
        for (int size = 8; size <= n; size <<= 1) {
            int half = size >> 1;
            for (int start = 0; start < n; start += size) {
                for (int k = 0; k < half; k++) {
                    int a = start + k;
                    int b = a + half;
                    double c = cos[t + k];
                    double s = sin[t + k];
                    double xr = re[b] * c - im[b] * s;
                    double xi = re[b] * s + im[b] * c;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
            t += half;
        }
    }
}
