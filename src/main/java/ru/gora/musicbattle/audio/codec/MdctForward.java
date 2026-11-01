package ru.gora.musicbattle.audio.codec;

/**
 * The forward MDCT matching the Vorbis inverse transform, computed through an n/4-point complex FFT.
 *
 * <p>Vorbis defines the inverse as {@code y[i] = sum_k X[k] cos(2 pi / n (i + 1/2 + n/4)(k + 1/2))} with no scale
 * factor, and windows both sides with a power-complementary window. For the overlap-added halves to give back the
 * input exactly, the forward transform carries the whole scale, {@code 4/n}. That also makes a coefficient's square
 * a sample-energy measure that does not depend on the block size, which the psychoacoustic model leans on.
 *
 * <p>The method: fold the n windowed samples into n/2 values whose DCT-IV is the MDCT, then compute the DCT-IV from
 * a complex FFT of n/4 points with a twiddle before and after.
 */
final class MdctForward {
    private final int half;
    private final int quarter;
    private final float scale;
    private final float[] preCos;
    private final float[] preSin;
    private final float[] postCos;
    private final float[] postSin;
    private final float[] fftCos;
    private final float[] fftSin;
    private final int[] bitReverse;
    private final float[] folded;
    private final float[] re;
    private final float[] im;

    MdctForward(int n) {
        if (n < 16 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("MDCT size must be a power of two: " + n);
        }
        this.half = n / 2;
        this.quarter = n / 4;
        this.scale = 4f / n;
        preCos = new float[quarter];
        preSin = new float[quarter];
        postCos = new float[quarter];
        postSin = new float[quarter];
        for (int m = 0; m < quarter; m++) {
            double a = Math.PI * m / half;
            preCos[m] = (float) Math.cos(a);
            preSin[m] = (float) Math.sin(a);
            double b = Math.PI * (m + 0.25) / half;
            postCos[m] = (float) (Math.cos(b) * scale);
            postSin[m] = (float) (Math.sin(b) * scale);
        }
        fftCos = new float[quarter / 2];
        fftSin = new float[quarter / 2];
        for (int i = 0; i < quarter / 2; i++) {
            double a = 2 * Math.PI * i / quarter;
            fftCos[i] = (float) Math.cos(a);
            fftSin[i] = (float) Math.sin(a);
        }
        int bits = Integer.numberOfTrailingZeros(quarter);
        bitReverse = new int[quarter];
        for (int i = 0; i < quarter; i++) {
            bitReverse[i] = bits == 0 ? 0 : Integer.reverse(i) >>> (32 - bits);
        }
        folded = new float[half];
        re = new float[quarter];
        im = new float[quarter];
    }

    /**
     * Transforms {@code in[0..n)}, already windowed, into {@code out[0..n/2)}.
     */
    void forward(float[] in, float[] out) {
        // The MDCT of (a, b, c, d), four quarters of the block, is the DCT-IV of (-c_r - d, a - b_r).
        int q = quarter;
        int h = half;
        int threeQ = 3 * q;
        for (int i = 0; i < q; i++) {
            folded[i] = -in[threeQ - 1 - i] - in[threeQ + i];
            folded[q + i] = in[i] - in[h - 1 - i];
        }
        // DCT-IV of length h: pair even inputs with mirrored odd ones as complex values, rotate, FFT, rotate.
        for (int m = 0; m < q; m++) {
            float a = folded[2 * m];
            float b = folded[h - 1 - 2 * m];
            float c = preCos[m];
            float s = preSin[m];
            int r = bitReverse[m];
            re[r] = a * c + b * s;
            im[r] = b * c - a * s;
        }
        fft();
        for (int k = 0; k < q; k++) {
            float a = re[k];
            float b = im[k];
            float c = postCos[k];
            float s = postSin[k];
            float wr = a * c + b * s;
            float wi = b * c - a * s;
            out[2 * k] = wr;
            out[h - 1 - 2 * k] = -wi;
        }
    }

    /** In-place radix-2 decimation-in-time FFT on bit-reversed input, forward sign. */
    private void fft() {
        int size = quarter;
        for (int len = 2; len <= size; len <<= 1) {
            int halfLen = len >> 1;
            int step = size / len;
            for (int start = 0; start < size; start += len) {
                for (int j = 0; j < halfLen; j++) {
                    float c = fftCos[j * step];
                    float s = fftSin[j * step];
                    int p = start + j;
                    int o = p + halfLen;
                    float xr = re[o];
                    float xi = im[o];
                    // Multiply by exp(-i theta).
                    float tr = xr * c + xi * s;
                    float ti = xi * c - xr * s;
                    re[o] = re[p] - tr;
                    im[o] = im[p] - ti;
                    re[p] += tr;
                    im[p] += ti;
                }
            }
        }
    }
}
