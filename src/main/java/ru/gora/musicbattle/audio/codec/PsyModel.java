package ru.gora.musicbattle.audio.codec;

/**
 * How much quantization noise each MDCT coefficient of one block can carry without being heard.
 *
 * <p>The classic simultaneous-masking recipe (Johnston 1988, the model behind MPEG psychoacoustic model 2), run on the
 * MDCT coefficients the encoder already has rather than on a separate FFT:
 * <ol>
 * <li>Coefficient energies are summed into bands about a third of a Bark wide (half a Bark for short blocks).</li>
 * <li>Each band's tonality comes from the spectral flatness of the coefficients around it: a peak masks far less
 * than noise of the same energy, so a tonal masker is offset by {@code 14.5 + z} dB and a noisy one by about 6 dB.</li>
 * <li>Every band's energy, less that offset, is spread over its neighbours with Schroeder's spreading function
 * (about +25 dB per Bark below the masker, -10 dB per Bark above) and renormalised by the spreading sum, so a flat
 * spectrum is not credited with more masking than its own energy.</li>
 * <li>For long blocks the threshold may not jump above a few times the previous block's: the noise of a long block
 * spreads over all of its 46 ms, so an onset the transient detector did not catch must not hide noise in the
 * quiet part before it (MPEG's pre-echo control).</li>
 * <li>The band threshold is shared evenly among its coefficients and never set below the absolute threshold of
 * hearing (Terhardt's formula, capped), itself shared over the coefficients of one critical band.</li>
 * </ol>
 *
 * <p>Energies are in the units of {@link MdctForward}: a full-scale sine carries an energy of about 1 in a block of
 * either size, which is what ties the absolute threshold to a playback level.
 */
final class PsyModel {
    /**
     * Masking offset of a noise-like masker, dB: 6 (as in MPEG model 2) up to Bark 14, rising to 8.5 by Bark 22.
     * Johnston's tonality index never quite reaches zero for real noise, which makes his noise offset grow with the
     * tonal one's {@code 14.5 + z}; measured against his model, a flat 6 dB left the treble 2 to 3 dB less protected
     * than the bass, and treble noise is what a transform codec is first heard by.
     */
    private static final float NOISE_MASKER_DB = 6f;
    private static final float NOISE_MASKER_TILT_DB = 2.5f;
    /**
     * The least a masker is offset by, whatever its tonality, as MPEG model 2's minimum SMR: 24 dB up to Bark 3
     * (about 300 Hz), falling to 0 by Bark 9 (about 1 kHz). The tonality estimate is least reliable in the narrow
     * low bands, and Johnston's tonal offset is smallest exactly there; without it a pure bass tone came out at only
     * 18 dB SNR, with its phase visibly bent. On the test songs it costs about 10 percent more bits at quality 5;
     * reaching on to Bark 16 cost 3 to 5 percent more again, only to lower noise that was already 5 dB under the mask.
     */
    private static final float MIN_OFFSET_DB = 24f;
    /** Spectral flatness (dB below 0) of white noise seen through squared MDCT coefficients, and of a clear tone. */
    private static final float FLATNESS_NOISE_DB = 6f;
    private static final float FLATNESS_TONE_DB = 26f;
    /** Spreading weights below this are dropped; 50 dB down they cannot change a threshold. */
    private static final double SPREAD_FLOOR_DB = -50.0;
    /** A long block's band threshold may rise at most this much over the previous long block's (MPEG's rpelev). */
    private static final float PRE_ECHO_RATIO = 4f;
    /** ...but never fall below this share of its own, so an onset after silence does not cost a lossless block. */
    private static final float PRE_ECHO_LIMIT = 1f / 16f;

    private final int bins;
    private final int bands;
    private final int[] bandStart;
    private final int[] bandEnd;
    private final int[] flatStart;
    private final int[] flatEnd;
    private final float[] maskerOffsetTonal;
    private final float[] maskerOffsetNoise;
    private final float[] maskerOffsetMinimum;
    private final int[] spreadFrom;
    private final float[][] spread;
    private final float[] bandShare;
    private final float[] ath;

    private final float[] energy;
    private final float[] maskerEnergy;
    private final float[] logEnergyPrefix;
    private final float[] energyPrefix;
    private final float[] threshold;

    /**
     * @param blockSize 256 or 2048
     * @param athReferenceDb the sound pressure level a full-scale sine is assumed to play at
     * @param athCapDb no frequency's absolute threshold is taken above this level, dB SPL
     */
    PsyModel(int blockSize, int sampleRate, double athReferenceDb, double athCapDb) {
        bins = blockSize / 2;
        double binHz = sampleRate / (double) blockSize;
        double bandBark = blockSize > 256 ? 1.0 / 3.0 : 0.5;

        int[] start = new int[bins];
        int[] end = new int[bins];
        int count = 0;
        int k = 0;
        while (k < bins) {
            int from = k;
            double barkFrom = bark(from * binHz);
            k++;
            while (k < bins && bark(k * binHz) - barkFrom < bandBark) {
                k++;
            }
            start[count] = from;
            end[count] = k;
            count++;
        }
        bands = count;
        bandStart = java.util.Arrays.copyOf(start, count);
        bandEnd = java.util.Arrays.copyOf(end, count);
        double[] z = new double[count];
        flatStart = new int[count];
        flatEnd = new int[count];
        maskerOffsetTonal = new float[count];
        maskerOffsetNoise = new float[count];
        maskerOffsetMinimum = new float[count];
        bandShare = new float[count];
        for (int b = 0; b < count; b++) {
            double centre = (bandStart[b] + bandEnd[b]) * 0.5 * binHz;
            z[b] = bark(centre);
            // Flatness needs a handful of coefficients to mean anything; narrow low bands borrow their neighbours'.
            int width = bandEnd[b] - bandStart[b];
            int pad = Math.max(0, (9 - width + 1) / 2);
            flatStart[b] = Math.max(0, bandStart[b] - pad);
            flatEnd[b] = Math.min(bins, bandEnd[b] + pad);
            maskerOffsetTonal[b] = (float) (14.5 + z[b]);
            maskerOffsetNoise[b] = (float) (NOISE_MASKER_DB
                    + NOISE_MASKER_TILT_DB * Math.max(0.0, Math.min(1.0, (z[b] - 14.0) / 8.0)));
            maskerOffsetMinimum[b] = (float) (MIN_OFFSET_DB * Math.max(0.0, Math.min(1.0, (9.0 - z[b]) / 6.0)));
            bandShare[b] = 1f / width;
        }

        spreadFrom = new int[count];
        spread = new float[count][];
        for (int b = 0; b < count; b++) {
            int lo = b;
            while (lo > 0 && spreadingDb(z[b] - z[lo - 1]) > SPREAD_FLOOR_DB) {
                lo--;
            }
            int hi = b;
            while (hi + 1 < count && spreadingDb(z[b] - z[hi + 1]) > SPREAD_FLOOR_DB) {
                hi++;
            }
            float[] w = new float[hi - lo + 1];
            double sum = 0;
            for (int m = lo; m <= hi; m++) {
                double v = Math.pow(10, spreadingDb(z[b] - z[m]) / 10);
                w[m - lo] = (float) v;
                sum += v;
            }
            for (int m = 0; m < w.length; m++) {
                w[m] /= (float) sum;
            }
            spreadFrom[b] = lo;
            spread[b] = w;
        }

        ath = new float[bins];
        for (int i = 0; i < bins; i++) {
            double f = Math.max(20.0, (i + 0.5) * binHz);
            double level = Math.min(terhardtDb(f), athCapDb) - athReferenceDb;
            double criticalBins = Math.max(1.0, criticalBandwidthHz(f) / binHz);
            ath[i] = (float) (Math.pow(10, level / 10) / criticalBins);
        }

        energy = new float[count];
        maskerEnergy = new float[count];
        logEnergyPrefix = new float[bins + 1];
        energyPrefix = new float[bins + 1];
        threshold = new float[count];
    }

    int bands() {
        return bands;
    }

    int bandStart(int band) {
        return bandStart[band];
    }

    int bandEnd(int band) {
        return bandEnd[band];
    }

    /**
     * Writes the allowed noise energy of every coefficient into {@code out}, scaled by {@code noiseGain}.
     *
     * @param previous the band thresholds of this channel's previous long block, updated here; {@code null} for short
     *     blocks or when there is no previous long block to compare with
     * @param previousValid whether {@code previous} holds a real block
     */
    void allowedNoise(float[] coefficients, float noiseGain, float[] out, float[] previous, boolean previousValid) {
        float eLog = 0f;
        float eSum = 0f;
        logEnergyPrefix[0] = 0f;
        energyPrefix[0] = 0f;
        for (int i = 0; i < bins; i++) {
            float x = coefficients[i];
            float e = x * x + 1e-20f;
            eSum += e;
            eLog += fastLog(e);
            energyPrefix[i + 1] = eSum;
            logEnergyPrefix[i + 1] = eLog;
        }
        for (int b = 0; b < bands; b++) {
            float e = energyPrefix[bandEnd[b]] - energyPrefix[bandStart[b]];
            energy[b] = Math.max(e, 0f);
            int lo = flatStart[b];
            int hi = flatEnd[b];
            int m = hi - lo;
            float arithmetic = (energyPrefix[hi] - energyPrefix[lo]) / m;
            float geometricLog = (logEnergyPrefix[hi] - logEnergyPrefix[lo]) / m;
            // Flatness in dB below 0: ln(geometric / arithmetic) * 10 / ln 10.
            float flatnessDb = -(geometricLog - fastLog(arithmetic + 1e-30f)) * 4.3429448f;
            float tonality = (flatnessDb - FLATNESS_NOISE_DB) / (FLATNESS_TONE_DB - FLATNESS_NOISE_DB);
            tonality = Math.max(0f, Math.min(1f, tonality));
            float offsetDb = Math.max(maskerOffsetMinimum[b],
                    tonality * maskerOffsetTonal[b] + (1f - tonality) * maskerOffsetNoise[b]);
            maskerEnergy[b] = energy[b] * fastExp10(-offsetDb * 0.1f);
        }
        for (int b = 0; b < bands; b++) {
            float[] w = spread[b];
            int from = spreadFrom[b];
            float sum = 0f;
            for (int m = 0; m < w.length; m++) {
                sum += maskerEnergy[from + m] * w[m];
            }
            float limited = sum;
            if (previous != null) {
                if (previousValid) {
                    limited = Math.min(sum, Math.max(PRE_ECHO_RATIO * previous[b], sum * PRE_ECHO_LIMIT));
                }
                previous[b] = sum;
            }
            threshold[b] = limited;
        }
        for (int b = 0; b < bands; b++) {
            float perBin = threshold[b] * bandShare[b];
            for (int i = bandStart[b]; i < bandEnd[b]; i++) {
                out[i] = Math.max(perBin, ath[i]) * noiseGain;
            }
        }
    }

    /** Zwicker and Terhardt's Bark scale. */
    static double bark(double hz) {
        return 13.0 * Math.atan(0.00076 * hz) + 3.5 * Math.atan((hz / 7500.0) * (hz / 7500.0));
    }

    /** Schroeder's spreading function, dz = maskee minus masker in Bark. */
    static double spreadingDb(double dz) {
        double t = dz + 0.474;
        return 15.81 + 7.5 * t - 17.5 * Math.sqrt(1.0 + t * t);
    }

    /** Terhardt's absolute threshold of hearing for a tone, dB SPL. */
    static double terhardtDb(double hz) {
        double f = hz / 1000.0;
        return 3.64 * Math.pow(f, -0.8) - 6.5 * Math.exp(-0.6 * (f - 3.3) * (f - 3.3)) + 1e-3 * f * f * f * f;
    }

    /** Zwicker's critical bandwidth in Hz. */
    static double criticalBandwidthHz(double hz) {
        double f = hz / 1000.0;
        return 25.0 + 75.0 * Math.pow(1.0 + 1.4 * f * f, 0.69);
    }

    /**
     * Natural logarithm within 0.006 (0.025 dB) for positive normal floats: the exponent field gives the integer part
     * of log2, and a quadratic in the mantissa's fraction t the rest, {@code -0.34267148 t^2 + 1.34267148 t}, a
     * least-squares fit to log2(1 + t) that is exact at t = 0 and t = 1 so the pieces meet at powers of two. A
     * Math.log per coefficient would cost more than the transform itself.
     */
    static float fastLog(float x) {
        int bits = Float.floatToRawIntBits(x);
        int exponent = ((bits >>> 23) & 0xFF) - 127;
        float t = Float.intBitsToFloat((bits & 0x007FFFFF) | 0x3F800000) - 1f;
        float log2 = exponent + (-0.34267148f * t + 1.34267148f) * t;
        return log2 * 0.69314718f;
    }

    /** 10^x for the modest range the masking offsets need. */
    static float fastExp10(float x) {
        return (float) Math.exp(x * 2.302585093);
    }
}
