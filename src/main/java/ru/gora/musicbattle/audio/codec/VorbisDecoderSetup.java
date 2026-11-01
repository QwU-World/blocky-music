package ru.gora.musicbattle.audio.codec;

import java.io.InterruptedIOException;
import java.util.Arrays;

/**
 * The three Vorbis headers turned into the tables the decoder works from: codebooks, floors, residues, mappings and
 * modes, following the Vorbis I specification and stb_vorbis's start_decoder.
 *
 * <p>Every count and index is checked before it is used. Codebook sizes and residue classbooks get libvorbis's limits,
 * which are stricter than stb_vorbis's and keep a hostile header from asking for gigabytes; floors may have up to 250
 * posts, as in stb_vorbis, where libvorbis stops at 65. On top of that the memory a header can make this allocate is
 * capped, so a damaged or hostile file ends in an {@link AudioFormatException} rather than an exception from deep
 * inside or a server running out of memory.
 */
final class VorbisDecoderSetup {
    /** Far above anything an encoder writes (libvorbis's own books total a few tens of thousands of entries). */
    private static final long MAX_TOTAL_ENTRIES = 1L << 22;
    private static final long MAX_TOTAL_VQ_VALUES = 1L << 22;

    final int channels;
    final int sampleRate;
    final int blocksize0;
    final int blocksize1;
    final Codebook[] codebooks;
    final Floor1[] floors;
    final Residue[] residues;
    final Mapping[] mappings;
    final Mode[] modes;
    final int modeBits;
    final int maxFloorValues;

    private VorbisDecoderSetup(int channels, int sampleRate, int blocksize0, int blocksize1, Codebook[] codebooks,
                               Floor1[] floors, Residue[] residues, Mapping[] mappings, Mode[] modes) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.blocksize0 = blocksize0;
        this.blocksize1 = blocksize1;
        this.codebooks = codebooks;
        this.floors = floors;
        this.residues = residues;
        this.mappings = mappings;
        this.modes = modes;
        this.modeBits = ilog(modes.length - 1);
        int maxValues = 2;
        for (Floor1 floor : floors) {
            maxValues = Math.max(maxValues, floor.values);
        }
        this.maxFloorValues = maxValues;
    }

    static final class Codebook {
        int dimensions;
        int entries;
        /** Indexed by the low {@link VorbisDecoderBits#FAST_BITS} bits of the stream: (entry << 5) | length, or -1. */
        int[] fast;
        /** Every codeword, left-aligned and most significant bit first, sorted as unsigned numbers. */
        int[] sortedCodes;
        int[] sortedEntries;
        byte[] sortedLengths;
        /** A book with one codeword always decodes to it, whatever the bits, as in libvorbis. */
        boolean single;
        /** The vectors of a lookup book, {@code dimensions} floats per entry; null for a scalar-only book. */
        float[] vq;
    }

    static final class Floor1 {
        int partitions;
        int[] partitionClass;
        int[] classDimensions;
        int[] classSubclasses;
        int[] classMasterbook;
        int[][] subclassBooks;
        int multiplier;
        int values;
        int[] x;
        /** Post indices in increasing x order, which is the order the curve is drawn in. */
        int[] sortedOrder;
        int[] lowNeighbor;
        int[] highNeighbor;
    }

    static final class Residue {
        int type;
        int begin;
        int end;
        int partitionSize;
        int classifications;
        int classbook;
        /** [classification][pass]: the book to decode with, or -1. */
        int[][] books;
        /** classifications to the power of the classbook's dimensions: a larger class number is damage. */
        int partitionValues;
    }

    static final class Mapping {
        int submaps;
        int couplingSteps;
        int[] magnitude;
        int[] angle;
        int[] mux;
        int[] submapFloor;
        int[] submapResidue;
    }

    static final class Mode {
        boolean blockflag;
        int mapping;
    }

    static VorbisDecoderSetup parse(byte[] id, int idLength, byte[] setup, int setupLength)
            throws AudioFormatException, InterruptedIOException {
        if (idLength < 30) {
            throw new AudioFormatException("The Vorbis identification header is too short.");
        }
        if (readInt(id, 7) != 0) {
            throw new AudioFormatException("The file uses a Vorbis version this decoder does not know.");
        }
        int channels = id[11] & 0xFF;
        long rate = readInt(id, 12) & 0xFFFFFFFFL;
        int blocksizes = id[28] & 0xFF;
        int log0 = blocksizes & 15;
        int log1 = blocksizes >> 4;
        if (channels == 0) {
            throw new AudioFormatException("The Vorbis stream has no channels.");
        }
        if (rate == 0 || rate > Integer.MAX_VALUE) {
            throw new AudioFormatException("The Vorbis stream has an invalid sample rate.");
        }
        if (log0 < 6 || log0 > 13 || log1 < 6 || log1 > 13 || log0 > log1) {
            throw new AudioFormatException("The Vorbis stream has invalid block sizes.");
        }
        if ((id[29] & 1) == 0) {
            throw new AudioFormatException("The Vorbis identification header is damaged.");
        }
        if (setupLength < 7 || setup[0] != 5 || setup[1] != 'v' || setup[2] != 'o' || setup[3] != 'r'
                || setup[4] != 'b' || setup[5] != 'i' || setup[6] != 's') {
            throw new AudioFormatException("The third Vorbis header is not the setup header.");
        }
        VorbisDecoderBits bits = new VorbisDecoderBits();
        bits.reset(setup, 7, setupLength - 7);
        Budget budget = new Budget();

        Codebook[] codebooks = new Codebook[bits.read(8) + 1];
        for (int i = 0; i < codebooks.length; i++) {
            checkInterrupt();
            codebooks[i] = readCodebook(bits, i, setupLength, budget);
        }
        int timeCount = bits.read(6) + 1;
        for (int i = 0; i < timeCount; i++) {
            if (bits.read(16) != 0) {
                throw invalid("a time domain transform");
            }
        }
        Floor1[] floors = new Floor1[bits.read(6) + 1];
        for (int i = 0; i < floors.length; i++) {
            floors[i] = readFloor(bits, codebooks.length);
        }
        Residue[] residues = new Residue[bits.read(6) + 1];
        for (int i = 0; i < residues.length; i++) {
            residues[i] = readResidue(bits, codebooks);
        }
        Mapping[] mappings = new Mapping[bits.read(6) + 1];
        for (int i = 0; i < mappings.length; i++) {
            mappings[i] = readMapping(bits, channels, floors.length, residues.length);
        }
        Mode[] modes = new Mode[bits.read(6) + 1];
        for (int i = 0; i < modes.length; i++) {
            Mode mode = new Mode();
            mode.blockflag = bits.readFlag();
            int windowType = bits.read(16);
            int transformType = bits.read(16);
            mode.mapping = bits.read(8);
            if (windowType != 0 || transformType != 0 || mode.mapping >= mappings.length) {
                throw invalid("a mode");
            }
            modes[i] = mode;
        }
        if (!bits.readFlag() || bits.eop) {
            throw new AudioFormatException("The Vorbis setup header is cut short.");
        }
        return new VorbisDecoderSetup(channels, (int) rate, 1 << log0, 1 << log1, codebooks, floors, residues,
                mappings, modes);
    }

    private static Codebook readCodebook(VorbisDecoderBits bits, int index, int packetLength, Budget budget)
            throws AudioFormatException {
        if (bits.read(24) != 0x564342) {
            throw invalid("codebook " + index);
        }
        Codebook book = new Codebook();
        book.dimensions = bits.read(16);
        book.entries = bits.read(24);
        // libvorbis's bound: it keeps entries times dimensions under 2^24 for every book.
        if (ilog(book.dimensions) + ilog(book.entries) > 24 || (book.dimensions == 0 && book.entries != 0)) {
            throw invalid("codebook " + index);
        }
        budget.entries += book.entries;
        if (budget.entries > MAX_TOTAL_ENTRIES) {
            throw new AudioFormatException("The Vorbis codebooks are implausibly large.");
        }
        byte[] lengths = new byte[book.entries];
        boolean ordered = bits.readFlag();
        if (ordered) {
            int length = bits.read(5) + 1;
            int entry = 0;
            while (entry < book.entries) {
                int count = bits.read(ilog(book.entries - entry));
                if (length > 31 || count > book.entries - entry || bits.eop) {
                    throw invalid("codebook " + index);
                }
                Arrays.fill(lengths, entry, entry + count, (byte) length);
                entry += count;
                length++;
            }
        } else {
            boolean sparse = bits.readFlag();
            // A header cannot describe more lengths than it has bits for; checking first keeps a short hostile
            // packet from making this loop run millions of times.
            if (((long) book.entries * (sparse ? 1 : 5) + 7) / 8 > packetLength) {
                throw invalid("codebook " + index);
            }
            for (int i = 0; i < book.entries; i++) {
                if (!sparse || bits.readFlag()) {
                    int length = bits.read(5) + 1;
                    if (length == 32) {
                        throw invalid("codebook " + index);
                    }
                    lengths[i] = (byte) length;
                }
            }
        }
        if (bits.eop) {
            throw new AudioFormatException("The Vorbis setup header is cut short.");
        }
        buildHuffman(book, lengths, index);

        int lookupType = bits.read(4);
        if (lookupType > 2) {
            throw invalid("codebook " + index);
        }
        if (lookupType > 0) {
            float minimum = unpackFloat(bits.readUnsigned32());
            float delta = unpackFloat(bits.readUnsigned32());
            int valueBits = bits.read(4) + 1;
            boolean sequence = bits.readFlag();
            long lookupValues = lookupType == 1
                    ? lookup1Values(book.entries, book.dimensions)
                    : (long) book.entries * book.dimensions;
            if (lookupValues <= 0) {
                throw invalid("codebook " + index);
            }
            if ((lookupValues * valueBits + 7) / 8 > packetLength) {
                throw invalid("codebook " + index);
            }
            long vqValues = (long) book.entries * book.dimensions;
            budget.vqValues += vqValues;
            if (budget.vqValues > MAX_TOTAL_VQ_VALUES) {
                throw new AudioFormatException("The Vorbis codebooks are implausibly large.");
            }
            int[] multiplicands = new int[(int) lookupValues];
            for (int i = 0; i < multiplicands.length; i++) {
                multiplicands[i] = bits.read(valueBits);
            }
            if (bits.eop) {
                throw new AudioFormatException("The Vorbis setup header is cut short.");
            }
            float[] vq = new float[(int) vqValues];
            int dimensions = book.dimensions;
            for (int entry = 0; entry < book.entries; entry++) {
                float last = 0;
                long divisor = 1;
                for (int i = 0; i < dimensions; i++) {
                    int offset = lookupType == 1
                            ? (int) ((entry / divisor) % lookupValues)
                            : entry * dimensions + i;
                    // The same order of float operations as the specification, stb_vorbis and libvorbis.
                    float value = multiplicands[offset] * delta + minimum + last;
                    vq[entry * dimensions + i] = value;
                    if (sequence) {
                        last = value;
                    }
                    divisor *= lookupValues;
                }
            }
            book.vq = vq;
        }
        return book;
    }

    /**
     * Assigns the codewords the way the specification does (stb_vorbis's compute_codewords): each entry in order gets
     * the lowest free branch of its length. A set of lengths that does not fit in a binary tree is damage; one that
     * leaves branches unused is accepted, like stb_vorbis does, and its unused codes decode as the end of the packet.
     */
    private static void buildHuffman(Codebook book, byte[] lengths, int index) throws AudioFormatException {
        int used = 0;
        for (byte length : lengths) {
            if (length != 0) {
                used++;
            }
        }
        int[] codes = new int[used];
        int[] entries = new int[used];
        byte[] codeLengths = new byte[used];
        int[] available = new int[32];
        int m = 0;
        for (int i = 0; i < lengths.length; i++) {
            int length = lengths[i];
            if (length == 0) {
                continue;
            }
            int code;
            if (m == 0) {
                code = 0;
                for (int j = 1; j <= length; j++) {
                    available[j] = 1 << (32 - j);
                }
            } else {
                int z = length;
                while (z > 0 && available[z] == 0) {
                    z--;
                }
                if (z == 0) {
                    throw invalid("codebook " + index + " (its code lengths do not form a tree)");
                }
                code = available[z];
                available[z] = 0;
                for (int y = length; y > z; y--) {
                    available[y] = code + (1 << (32 - y));
                }
            }
            codes[m] = code;
            entries[m] = i;
            codeLengths[m] = (byte) length;
            m++;
        }
        book.single = used == 1;

        int[] fast = new int[1 << VorbisDecoderBits.FAST_BITS];
        Arrays.fill(fast, -1);
        for (int k = 0; k < used; k++) {
            int length = codeLengths[k];
            if (length > VorbisDecoderBits.FAST_BITS) {
                continue;
            }
            int packed = entries[k] << 5 | length;
            if (book.single) {
                Arrays.fill(fast, packed);
                break;
            }
            for (int z = Integer.reverse(codes[k]); z < fast.length; z += 1 << length) {
                fast[z] = packed;
            }
        }
        book.fast = fast;

        long[] keys = new long[used];
        for (int k = 0; k < used; k++) {
            keys[k] = (codes[k] & 0xFFFFFFFFL) << 24 | entries[k];
        }
        Arrays.sort(keys);
        book.sortedCodes = new int[used];
        book.sortedEntries = new int[used];
        book.sortedLengths = new byte[used];
        for (int k = 0; k < used; k++) {
            int entry = (int) (keys[k] & 0xFFFFFF);
            book.sortedCodes[k] = (int) (keys[k] >>> 24);
            book.sortedEntries[k] = entry;
            book.sortedLengths[k] = lengths[entry];
        }
    }

    private static Floor1 readFloor(VorbisDecoderBits bits, int codebookCount) throws AudioFormatException {
        int type = bits.read(16);
        if (type == 0) {
            throw new AudioFormatException("The file uses Vorbis floor type 0, which this decoder does not support.");
        }
        if (type != 1) {
            throw invalid("a floor");
        }
        Floor1 floor = new Floor1();
        floor.partitions = bits.read(5);
        floor.partitionClass = new int[floor.partitions];
        int maxClass = -1;
        for (int j = 0; j < floor.partitions; j++) {
            floor.partitionClass[j] = bits.read(4);
            maxClass = Math.max(maxClass, floor.partitionClass[j]);
        }
        floor.classDimensions = new int[maxClass + 1];
        floor.classSubclasses = new int[maxClass + 1];
        floor.classMasterbook = new int[maxClass + 1];
        floor.subclassBooks = new int[maxClass + 1][];
        for (int j = 0; j <= maxClass; j++) {
            floor.classDimensions[j] = bits.read(3) + 1;
            floor.classSubclasses[j] = bits.read(2);
            if (floor.classSubclasses[j] != 0) {
                floor.classMasterbook[j] = bits.read(8);
                if (floor.classMasterbook[j] >= codebookCount) {
                    throw invalid("a floor");
                }
            }
            floor.subclassBooks[j] = new int[1 << floor.classSubclasses[j]];
            for (int k = 0; k < floor.subclassBooks[j].length; k++) {
                floor.subclassBooks[j][k] = bits.read(8) - 1;
                if (floor.subclassBooks[j][k] >= codebookCount) {
                    throw invalid("a floor");
                }
            }
        }
        floor.multiplier = bits.read(2) + 1;
        int rangeBits = bits.read(4);
        int values = 2;
        for (int j = 0; j < floor.partitions; j++) {
            values += floor.classDimensions[floor.partitionClass[j]];
        }
        floor.values = values;
        floor.x = new int[values];
        floor.x[0] = 0;
        floor.x[1] = 1 << rangeBits;
        for (int j = 2; j < values; j++) {
            floor.x[j] = bits.read(rangeBits);
        }
        if (bits.eop) {
            throw new AudioFormatException("The Vorbis setup header is cut short.");
        }
        long[] order = new long[values];
        for (int j = 0; j < values; j++) {
            order[j] = (long) floor.x[j] << 16 | j;
        }
        Arrays.sort(order);
        floor.sortedOrder = new int[values];
        for (int j = 0; j < values; j++) {
            floor.sortedOrder[j] = (int) (order[j] & 0xFFFF);
            // Two posts at the same x would make a zero-width line segment and a division by zero.
            if (j > 0 && (order[j] >>> 16) == (order[j - 1] >>> 16)) {
                throw invalid("a floor");
            }
        }
        floor.lowNeighbor = new int[values];
        floor.highNeighbor = new int[values];
        for (int j = 2; j < values; j++) {
            int low = -1;
            int high = Integer.MAX_VALUE;
            for (int i = 0; i < j; i++) {
                int x = floor.x[i];
                if (x < floor.x[j] && x > low) {
                    low = x;
                    floor.lowNeighbor[j] = i;
                }
                if (x > floor.x[j] && x < high) {
                    high = x;
                    floor.highNeighbor[j] = i;
                }
            }
        }
        return floor;
    }

    private static Residue readResidue(VorbisDecoderBits bits, Codebook[] codebooks) throws AudioFormatException {
        Residue residue = new Residue();
        residue.type = bits.read(16);
        if (residue.type > 2) {
            throw invalid("a residue");
        }
        residue.begin = bits.read(24);
        residue.end = bits.read(24);
        residue.partitionSize = bits.read(24) + 1;
        residue.classifications = bits.read(6) + 1;
        residue.classbook = bits.read(8);
        if (residue.end < residue.begin || residue.classbook >= codebooks.length) {
            throw invalid("a residue");
        }
        int[] cascade = new int[residue.classifications];
        for (int j = 0; j < residue.classifications; j++) {
            int low = bits.read(3);
            int high = bits.readFlag() ? bits.read(5) : 0;
            cascade[j] = high << 3 | low;
        }
        residue.books = new int[residue.classifications][8];
        for (int j = 0; j < residue.classifications; j++) {
            for (int k = 0; k < 8; k++) {
                if ((cascade[j] & (1 << k)) != 0) {
                    residue.books[j][k] = bits.read(8);
                    if (residue.books[j][k] >= codebooks.length) {
                        throw invalid("a residue");
                    }
                } else {
                    residue.books[j][k] = -1;
                }
            }
        }
        // libvorbis refuses a classbook that cannot name every combination of classes; it also keeps the
        // classification loop from spinning on a book with no dimensions.
        Codebook classbook = codebooks[residue.classbook];
        if (classbook.dimensions < 1) {
            throw invalid("a residue");
        }
        long partitionValues = 1;
        for (int d = 0; d < classbook.dimensions; d++) {
            partitionValues *= residue.classifications;
            if (partitionValues > classbook.entries) {
                throw invalid("a residue");
            }
        }
        residue.partitionValues = (int) partitionValues;
        return residue;
    }

    private static Mapping readMapping(VorbisDecoderBits bits, int channels, int floorCount, int residueCount)
            throws AudioFormatException {
        if (bits.read(16) != 0) {
            throw invalid("a mapping");
        }
        Mapping mapping = new Mapping();
        mapping.submaps = bits.readFlag() ? bits.read(4) + 1 : 1;
        if (bits.readFlag()) {
            mapping.couplingSteps = bits.read(8) + 1;
            mapping.magnitude = new int[mapping.couplingSteps];
            mapping.angle = new int[mapping.couplingSteps];
            int channelBits = ilog(channels - 1);
            for (int k = 0; k < mapping.couplingSteps; k++) {
                mapping.magnitude[k] = bits.read(channelBits);
                mapping.angle[k] = bits.read(channelBits);
                if (mapping.magnitude[k] >= channels || mapping.angle[k] >= channels
                        || mapping.magnitude[k] == mapping.angle[k]) {
                    throw invalid("a mapping");
                }
            }
        } else {
            mapping.magnitude = new int[0];
            mapping.angle = new int[0];
        }
        if (bits.read(2) != 0) {
            throw invalid("a mapping");
        }
        mapping.mux = new int[channels];
        if (mapping.submaps > 1) {
            for (int j = 0; j < channels; j++) {
                mapping.mux[j] = bits.read(4);
                if (mapping.mux[j] >= mapping.submaps) {
                    throw invalid("a mapping");
                }
            }
        }
        mapping.submapFloor = new int[mapping.submaps];
        mapping.submapResidue = new int[mapping.submaps];
        for (int j = 0; j < mapping.submaps; j++) {
            bits.read(8);
            mapping.submapFloor[j] = bits.read(8);
            mapping.submapResidue[j] = bits.read(8);
            if (mapping.submapFloor[j] >= floorCount || mapping.submapResidue[j] >= residueCount) {
                throw invalid("a mapping");
            }
        }
        return mapping;
    }

    /** The largest r with r to the power of dimensions no more than entries, found with exact integer arithmetic. */
    static long lookup1Values(int entries, int dimensions) {
        if (entries < 1 || dimensions < 1) {
            return 0;
        }
        long r = Math.max(1, (long) Math.floor(Math.pow(entries, 1.0 / dimensions)));
        while (r > 1 && power(r, dimensions, entries) > entries) {
            r--;
        }
        while (power(r + 1, dimensions, entries) <= entries) {
            r++;
        }
        return r;
    }

    /** base to the power of exponent, or anything above cap once it passes cap. */
    private static long power(long base, int exponent, long cap) {
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= base;
            if (result > cap) {
                return cap + 1;
            }
        }
        return result;
    }

    /** The specification's float32_unpack: a 21-bit mantissa, a sign bit and a 10-bit exponent biased by 788. */
    static float unpackFloat(long x) {
        long mantissa = x & 0x1fffff;
        boolean negative = (x & 0x80000000L) != 0;
        int exponent = (int) ((x & 0x7fe00000L) >>> 21);
        double value = negative ? -(double) mantissa : (double) mantissa;
        return (float) Math.scalb(value, exponent - 788);
    }

    /** The specification's ilog: the number of bits needed to write n, so ilog(0) is 0 and ilog(4) is 3. */
    static int ilog(int n) {
        return n <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(n);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    private static AudioFormatException invalid(String what) {
        return new AudioFormatException("The Vorbis setup header has an invalid " + what + ".");
    }

    private static void checkInterrupt() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Vorbis decoding was interrupted.");
        }
    }

    private static final class Budget {
        long entries;
        long vqValues;
    }
}
