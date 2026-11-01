package ru.gora.musicbattle.audio.codec;

/**
 * The fixed structure of one stream: block sizes, the two floors and residues, every codebook's shape, the quality
 * settings, and the setup header that describes them to a decoder. Only the codeword lengths are left open: they
 * come from the first pass over the song.
 */
final class VorbisEncoderLayout {
    static final int SHORT_BLOCK = 256;
    static final int LONG_BLOCK = 2048;
    static final int SHORT_FLOOR_POSTS = 12;
    static final int LONG_FLOOR_POSTS = 30;
    /** Longest codeword. Every decoder handles 31; 20 keeps their lookup tables small and costs nothing measurable. */
    static final int MAX_CODEWORD_LENGTH = 20;

    final int channels;
    final int sampleRate;
    final double lowpassHz;
    /** Quantization noise allowed relative to the masking threshold, as an energy ratio. */
    final float noiseGain;
    /** Squared floor over allowed noise: rounding to a step s leaves about s^2/12 of noise. */
    final float stepToNoise;
    final double athReferenceDb;
    final double athCapDb;

    final VorbisEncoderFloor shortFloor;
    final VorbisEncoderFloor longFloor;
    final VorbisEncoderResidue shortResidue;
    final VorbisEncoderResidue longResidue;
    /** Entries of every book, by book number. */
    final int[] bookEntries;
    /** {dimensions, values per dimension (0 = scalar book), step} by book number. */
    final int[][] bookShapes;

    VorbisEncoderLayout(int channels, int sampleRate, float quality) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.lowpassHz = Math.min(lowpassForQuality(quality), sampleRate * 0.5 * 0.98);
        this.noiseGain = (float) Math.pow(10, noiseOffsetDbForQuality(quality) / 10);
        this.stepToNoise = 12f;
        this.athReferenceDb = 96.0;
        this.athCapDb = 60.0;

        int[] floorBooks = VorbisEncoderFloor.bookEntries();
        int[] residueBooks = VorbisEncoderResidue.bookEntries();
        int[][] residueShapes = VorbisEncoderResidue.bookShapes();
        int total = 2 * floorBooks.length + 2 * residueBooks.length;
        bookEntries = new int[total];
        bookShapes = new int[total][];
        int at = 0;
        int shortFloorFirst = at;
        for (int e : floorBooks) {
            bookEntries[at] = e;
            bookShapes[at++] = new int[] {1, 0, 0};
        }
        int longFloorFirst = at;
        for (int e : floorBooks) {
            bookEntries[at] = e;
            bookShapes[at++] = new int[] {1, 0, 0};
        }
        int shortResidueFirst = at;
        for (int i = 0; i < residueBooks.length; i++) {
            bookEntries[at] = residueBooks[i];
            bookShapes[at++] = residueShapes[i];
        }
        int longResidueFirst = at;
        for (int i = 0; i < residueBooks.length; i++) {
            bookEntries[at] = residueBooks[i];
            bookShapes[at++] = residueShapes[i];
        }
        shortFloor = new VorbisEncoderFloor(SHORT_BLOCK, sampleRate, lowpassHz, SHORT_FLOOR_POSTS, shortFloorFirst);
        longFloor = new VorbisEncoderFloor(LONG_BLOCK, sampleRate, lowpassHz, LONG_FLOOR_POSTS, longFloorFirst);
        shortResidue = new VorbisEncoderResidue(SHORT_BLOCK, channels, lowpassBin(SHORT_BLOCK), shortResidueFirst);
        longResidue = new VorbisEncoderResidue(LONG_BLOCK, channels, lowpassBin(LONG_BLOCK), longResidueFirst);
    }

    /** The first coefficient at or above the lowpass. */
    int lowpassBin(int blockSize) {
        int bins = blockSize / 2;
        return Math.max(1, Math.min(bins, (int) Math.ceil(lowpassHz * blockSize / sampleRate)));
    }

    /**
     * Lowpass by quality: libvorbis's own values for 44.1 kHz ({@code _psy_lowpass_44} in its psych_44.h, BSD
     * licence), 13.9 kHz at -1 up to 20.1 kHz at 5 and the full band from 6. Content above them costs many bits that
     * few listeners could hear, and matching them keeps the quality numbers meaning what people expect of Vorbis.
     */
    static double lowpassForQuality(float q) {
        double[] table = {13900, 15100, 15800, 16500, 17200, 18900, 20100, 96000, 96000, 96000, 96000, 96000};
        return interpolate(table, q);
    }

    /**
     * Noise against the masking threshold by quality, dB: 0 is exactly at the threshold. Quality 5 keeps 3 dB below
     * it, which on the test songs put 1 to 4 percent of bands over an independent Johnston-style mask, at 160 to
     * 210 kbit/s for full-range stereo music.
     */
    static double noiseOffsetDbForQuality(float q) {
        double[] table = {9, 7, 5, 3, 1, -1, -3, -5, -7, -9.5, -12, -12};
        return interpolate(table, q);
    }

    private static double interpolate(double[] table, float q) {
        double position = Math.max(-1.0, Math.min(10.0, q)) + 1.0;
        int i = Math.min(table.length - 2, (int) Math.floor(position));
        double f = position - i;
        return table[i] + (table[i + 1] - table[i]) * f;
    }

    /** The setup header: codebooks with the given lengths, floors, residues, mappings and modes. */
    byte[] setupHeader(int[][] lengths) {
        VorbisEncoderBits w = new VorbisEncoderBits();
        w.write(5, 8);
        w.writeBytes(new byte[] {'v', 'o', 'r', 'b', 'i', 's'});
        w.write(bookEntries.length - 1, 8);
        for (int b = 0; b < bookEntries.length; b++) {
            writeCodebook(w, b, lengths[b]);
        }
        // Time domain transforms: one placeholder, as Vorbis I requires.
        w.write(0, 6);
        w.write(0, 16);
        w.write(1, 6);
        shortFloor.writeSetup(w);
        longFloor.writeSetup(w);
        w.write(1, 6);
        shortResidue.writeSetup(w);
        longResidue.writeSetup(w);
        w.write(1, 6);
        for (int mapping = 0; mapping < 2; mapping++) {
            w.write(0, 16);
            w.write(0, 1);
            if (channels == 2) {
                // One coupling step: channel 0 carries the magnitude, channel 1 the angle.
                w.write(1, 1);
                w.write(0, 8);
                w.write(0, 1);
                w.write(1, 1);
            } else {
                w.write(0, 1);
            }
            w.write(0, 2);
            w.write(0, 8);
            w.write(mapping, 8);
            w.write(mapping, 8);
        }
        w.write(1, 6);
        for (int mode = 0; mode < 2; mode++) {
            w.write(mode, 1);
            w.write(0, 16);
            w.write(0, 16);
            w.write(mode, 8);
        }
        w.write(1, 1);
        return w.toByteArray();
    }

    private void writeCodebook(VorbisEncoderBits w, int book, int[] lengths) {
        int[] shape = bookShapes[book];
        w.write(0x564342, 24);
        w.write(shape[0], 16);
        w.write(bookEntries[book], 24);
        w.write(0, 1);
        w.write(0, 1);
        for (int length : lengths) {
            w.write(length - 1, 5);
        }
        int valuesPerDimension = shape[1];
        if (valuesPerDimension == 0) {
            w.write(0, 4);
            return;
        }
        // Lookup type 1: entry e holds, in dimension d, min + delta * digit d of e in base valuesPerDimension.
        int step = shape[2];
        int reach = (valuesPerDimension - 1) / 2;
        w.write(1, 4);
        w.write(packFloat(-reach * (double) step), 32);
        w.write(packFloat(step), 32);
        int bits = 32 - Integer.numberOfLeadingZeros(valuesPerDimension - 1);
        w.write(bits - 1, 4);
        w.write(0, 1);
        for (int i = 0; i < valuesPerDimension; i++) {
            w.write(i, bits);
        }
    }

    /** Vorbis's 32-bit float: 21-bit mantissa, 10-bit exponent biased by 788, sign on top. */
    static long packFloat(double value) {
        if (value == 0) {
            return 0L;
        }
        long sign = value < 0 ? 0x80000000L : 0L;
        double magnitude = Math.abs(value);
        int exponent = Math.getExponent(magnitude);
        long mantissa = Math.round(Math.scalb(magnitude, 20 - exponent));
        if (mantissa >= (1L << 21)) {
            mantissa >>= 1;
            exponent++;
        }
        return sign | ((long) (exponent + 768) << 21) | mantissa;
    }
}
