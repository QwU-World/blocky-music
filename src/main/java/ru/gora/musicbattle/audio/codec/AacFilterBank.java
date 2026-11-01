package ru.gora.musicbattle.audio.codec;

/**
 * The AAC synthesis filter bank (ISO/IEC 14496-3, 4.6.11): the inverse MDCT of one long or eight short windows, the
 * sine and Kaiser-Bessel-derived window shapes, and the overlap-add with the previous frame.
 *
 * <p>The inverse MDCT is computed as a DCT-IV of half its length, and the DCT-IV through a complex FFT of a quarter
 * of the length: pre-rotation, FFT, post-rotation. The derivation is the textbook one; the folding of the DCT-IV
 * output into the 2N-point MDCT frame follows from the cosine's symmetries and is spelled out at {@link #imdct}.
 * Everything inside runs in double precision so the result is limited by float output rounding, not by the
 * transform.
 */
final class AacFilterBank {
    static final int ONLY_LONG = 0;
    static final int LONG_START = 1;
    static final int EIGHT_SHORT = 2;
    static final int LONG_STOP = 3;

    /** Rising halves of the windows: [shape][n], shape 0 sine, 1 KBD. */
    static final double[][] LONG_WINDOW = new double[2][1024];
    static final double[][] SHORT_WINDOW = new double[2][128];

    private static final Transform LONG = new Transform(1024);
    private static final Transform SHORT = new Transform(128);

    static {
        for (int n = 0; n < 1024; n++) {
            LONG_WINDOW[0][n] = StrictMath.sin(Math.PI / 2048 * (n + 0.5));
        }
        for (int n = 0; n < 128; n++) {
            SHORT_WINDOW[0][n] = StrictMath.sin(Math.PI / 256 * (n + 0.5));
        }
        kbd(LONG_WINDOW[1], 2048, 4);
        kbd(SHORT_WINDOW[1], 256, 6);
    }

    private final double[] frame = new double[2048];
    private final double[] work = new double[1024];
    // Scratch for the transforms, which are shared tables: the real and imaginary FFT buffers and the DCT-IV output.
    private final double[] re = new double[512];
    private final double[] im = new double[512];
    private final double[] u = new double[1024];

    /**
     * The Kaiser-Bessel-derived window's rising half for a frame of {@code n} samples: the running sum of a Kaiser
     * window of length n/2 + 1, normalised and square-rooted.
     */
    private static void kbd(double[] out, int n, double alpha) {
        int half = n / 2;
        double[] kaiser = new double[half + 1];
        double total = 0;
        for (int p = 0; p <= half; p++) {
            double x = (p - n / 4.0) / (n / 4.0);
            kaiser[p] = besselI0(Math.PI * alpha * Math.sqrt(Math.max(0, 1 - x * x)));
            total += kaiser[p];
        }
        double sum = 0;
        for (int p = 0; p < half; p++) {
            sum += kaiser[p];
            out[p] = Math.sqrt(sum / total);
        }
    }

    /** The modified Bessel function of the first kind and order zero, by its power series. */
    private static double besselI0(double x) {
        double term = 1;
        double sum = 1;
        double quarter = x * x / 4;
        for (int k = 1; k < 100; k++) {
            term *= quarter / ((double) k * k);
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }

    /**
     * Turns one frame of spectral coefficients into 1024 output samples: inverse transform, windowing and overlap-add.
     *
     * @param spec the 1024 coefficients, or 8 x 128 for eight short windows; scaled so that the output is in [-1, 1]
     * @param overlap the second half of the previous frame, updated with this frame's
     */
    void synthesize(float[] spec, int sequence, int shape, int previousShape, float[] overlap, float[] out,
                    int outOffset) {
        double[] z = frame;
        if (sequence == EIGHT_SHORT) {
            java.util.Arrays.fill(z, 0.0);
            double[] current = SHORT_WINDOW[shape];
            for (int w = 0; w < 8; w++) {
                double[] rising = w == 0 ? SHORT_WINDOW[previousShape] : current;
                imdct(SHORT, spec, w * 128, work);
                // work holds 256 samples; they land 448 + 128 w into the frame.
                int base = 448 + 128 * w;
                for (int n = 0; n < 128; n++) {
                    z[base + n] += work[n] * rising[n];
                    z[base + 128 + n] += work[128 + n] * current[127 - n];
                }
            }
        } else {
            imdct(LONG, spec, 0, z);
            if (sequence == LONG_STOP) {
                double[] rising = SHORT_WINDOW[previousShape];
                for (int n = 0; n < 448; n++) {
                    z[n] = 0;
                }
                for (int n = 0; n < 128; n++) {
                    z[448 + n] *= rising[n];
                }
            } else {
                double[] rising = LONG_WINDOW[previousShape];
                for (int n = 0; n < 1024; n++) {
                    z[n] *= rising[n];
                }
            }
            if (sequence == LONG_START) {
                double[] falling = SHORT_WINDOW[shape];
                for (int n = 0; n < 128; n++) {
                    z[1024 + 448 + n] *= falling[127 - n];
                }
                for (int n = 1600; n < 2048; n++) {
                    z[n] = 0;
                }
            } else {
                double[] falling = LONG_WINDOW[shape];
                for (int n = 0; n < 1024; n++) {
                    z[1024 + n] *= falling[1023 - n];
                }
            }
        }
        for (int n = 0; n < 1024; n++) {
            out[outOffset + n] = (float) (z[n] + overlap[n]);
            overlap[n] = (float) z[1024 + n];
        }
    }

    /**
     * The inverse MDCT of {@code m} coefficients into 2m samples, scaled by 2/(2m) as the specification writes it.
     *
     * <p>With u the DCT-IV of the coefficients, u[n] = sum X[k] cos(pi/m (n + 1/2)(k + 1/2)), the MDCT's phase
     * offset n0 = m/2 + 1/2 makes the output u[n + m/2] for n &lt; m/2, -u[3m/2 - 1 - n] up to 3m/2 and
     * -u[n - 3m/2] after that.
     */
    private void imdct(Transform t, float[] spec, int offset, double[] out) {
        int m = t.m;
        t.dct4(spec, offset, re, im, u);
        int half = m / 2;
        double scale = 1.0 / m;
        for (int n = 0; n < half; n++) {
            out[n] = u[n + half] * scale;
        }
        for (int n = half; n < 3 * half; n++) {
            out[n] = -u[3 * half - 1 - n] * scale;
        }
        for (int n = 3 * half; n < 2 * m; n++) {
            out[n] = -u[n - 3 * half] * scale;
        }
    }

    /**
     * A DCT-IV of length m through a complex FFT of length m/2. With z[j] = X[2j] + i X[m-1-2j] rotated by
     * exp(-i pi (j + 1/4) / m), the FFT's output rotated by exp(-i pi k / m) holds u[2k] in its real part and
     * -u[m-1-2k] in its imaginary part.
     */
    private static final class Transform {
        final int m;
        private final int l;
        private final double[] preCos;
        private final double[] preSin;
        private final double[] postCos;
        private final double[] postSin;
        private final double[] fftCos;
        private final double[] fftSin;
        private final int[] reverse;

        Transform(int m) {
            this.m = m;
            this.l = m / 2;
            preCos = new double[l];
            preSin = new double[l];
            postCos = new double[l];
            postSin = new double[l];
            for (int j = 0; j < l; j++) {
                double a = -Math.PI * (j + 0.25) / m;
                preCos[j] = StrictMath.cos(a);
                preSin[j] = StrictMath.sin(a);
                double b = -Math.PI * j / m;
                postCos[j] = StrictMath.cos(b);
                postSin[j] = StrictMath.sin(b);
            }
            fftCos = new double[l / 2];
            fftSin = new double[l / 2];
            for (int k = 0; k < l / 2; k++) {
                fftCos[k] = StrictMath.cos(-2 * Math.PI * k / l);
                fftSin[k] = StrictMath.sin(-2 * Math.PI * k / l);
            }
            int bits = Integer.numberOfTrailingZeros(l);
            reverse = new int[l];
            for (int i = 0; i < l; i++) {
                reverse[i] = Integer.reverse(i) >>> (32 - bits);
            }
        }

        /** The DCT-IV of x[offset .. offset + m) into u, using re and im (at least m/2 long) as FFT buffers. */
        void dct4(float[] x, int offset, double[] re, double[] im, double[] u) {
            for (int j = 0; j < l; j++) {
                double a = x[offset + 2 * j];
                double b = x[offset + m - 1 - 2 * j];
                int r = reverse[j];
                re[r] = a * preCos[j] - b * preSin[j];
                im[r] = a * preSin[j] + b * preCos[j];
            }
            fft(re, im);
            for (int k = 0; k < l; k++) {
                double yr = re[k] * postCos[k] - im[k] * postSin[k];
                double yi = re[k] * postSin[k] + im[k] * postCos[k];
                u[2 * k] = yr;
                u[m - 1 - 2 * k] = -yi;
            }
        }

        /** In-place radix-2 FFT on bit-reversed input, forward sign. */
        private void fft(double[] re, double[] im) {
            int n = l;
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1;
                int step = n / size;
                for (int start = 0; start < n; start += size) {
                    for (int k = 0, t = 0; k < half; k++, t += step) {
                        int a = start + k;
                        int b = a + half;
                        double c = fftCos[t];
                        double sn = fftSin[t];
                        double xr = re[b] * c - im[b] * sn;
                        double xi = re[b] * sn + im[b] * c;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }
}
