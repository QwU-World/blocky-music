package ru.gora.musicbattle.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * What a skin uploaded for the test NPC from a file has to be, shared by the server that keeps it and the clients that
 * draw it. Pure Java with no Minecraft in it, so the npc-skin check runs these exact rules on their own.
 *
 * <p>A skin is a PNG of 64 by 64 pixels, or 64 by 32 in the layout Minecraft used before 1.8. The check walks the whole
 * file rather than reading the size from the header alone: every chunk has to carry the checksum it claims, the chunks
 * have to come in the order the format requires, and the compressed picture has to unpack to exactly the bytes a 64 by
 * 64 (or 64 by 32) image of its kind needs, no more and no fewer. A file the server stores and every client then
 * decodes is one nobody can cut short or pad with anything.
 *
 * <p>{@link #normalize} is vanilla's {@code HttpTexture.processLegacySkin} on a plain array: an old 64 by 32 skin gets
 * its left arm and leg mirrored from the right ones, and the base layer is made opaque, exactly as Minecraft treats the
 * skins it downloads for players. The npc-skin check compares the two on random images.
 */
public final class NpcSkinPng {
    /**
     * Largest PNG the server keeps. The client re-encodes whatever file it reads through the game's own PNG writer, and a
     * 64 by 64 RGBA picture stays under 18 KiB however noisy it is, so the cap only ever stops a file that is not a skin;
     * it also keeps the upload inside one custom payload, like the 24 KiB beatmap chunks.
     */
    public static final int MAX_BYTES = 24 * 1024;
    /**
     * Largest file the client reads before re-encoding it. Editors write colour profiles and other metadata into skins,
     * which can take a 2 KiB picture well past {@link #MAX_BYTES}; re-encoding drops all of it.
     */
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    public static final int WIDTH = 64;
    public static final int HEIGHT = 64;
    public static final int LEGACY_HEIGHT = 32;

    /** More chunks than this is not a skin from any editor, and walking them is all a hostile file could cost. */
    private static final int MAX_CHUNKS = 512;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** Adam7 passes: first column, first row, column step, row step. */
    private static final int[][] ADAM7 = {
            {0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}
    };
    private static final HexFormat HEX = HexFormat.of();

    /** Why a file cannot be a skin, or {@link #NONE} when it can. */
    public enum Problem {
        NONE, EMPTY, TOO_LARGE, NOT_PNG, WRONG_SIZE, CORRUPT
    }

    /** The verdict, with the image's size whenever the header could be read (0 by 0 otherwise). */
    public record Result(Problem problem, int width, int height) {
        public boolean ok() {
            return problem == Problem.NONE;
        }

        public boolean legacy() {
            return ok() && height == LEGACY_HEIGHT;
        }
    }

    private NpcSkinPng() {
    }

    /** The server's rule, and the one a client applies to what the server sent: at most {@link #MAX_BYTES}. */
    public static Result check(byte[] png) {
        return inspect(png, MAX_BYTES);
    }

    /** The same rules with another size limit, for a file the client reads before it re-encodes it. */
    public static Result inspect(byte[] png, int maxBytes) {
        if (png == null || png.length == 0) {
            return new Result(Problem.EMPTY, 0, 0);
        }
        if (png.length > maxBytes) {
            return new Result(Problem.TOO_LARGE, 0, 0);
        }
        if (png.length < SIGNATURE.length) {
            return new Result(Problem.NOT_PNG, 0, 0);
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                return new Result(Problem.NOT_PNG, 0, 0);
            }
        }
        Inflater inflater = new Inflater();
        try {
            return walk(png, inflater);
        } finally {
            inflater.end();
        }
    }

    private static Result walk(byte[] png, Inflater inflater) {
        int offset = SIGNATURE.length;
        int width = 0;
        int height = 0;
        int colourType = -1;
        Scanlines lines = null;
        boolean palette = false;
        boolean dataStarted = false;
        boolean dataEnded = false;
        boolean end = false;
        int chunks = 0;
        CRC32 crc = new CRC32();
        byte[] out = new byte[4096];
        while (offset < png.length) {
            if (end || png.length - offset < 12 || ++chunks > MAX_CHUNKS) {
                // Bytes after IEND, a chunk header cut off, or a file of nothing but tiny chunks.
                return corrupt(width, height);
            }
            int length = readInt(png, offset);
            int typeAt = offset + 4;
            int dataAt = offset + 8;
            if (length < 0 || length > png.length - dataAt - 4 || !isChunkType(png, typeAt)) {
                return corrupt(width, height);
            }
            crc.reset();
            crc.update(png, typeAt, 4 + length);
            if ((int) crc.getValue() != readInt(png, dataAt + length)) {
                return corrupt(width, height);
            }
            String type = new String(png, typeAt, 4, StandardCharsets.US_ASCII);
            if (lines == null) {
                if (!"IHDR".equals(type) || length != 13) {
                    return corrupt(0, 0);
                }
                width = readInt(png, dataAt);
                height = readInt(png, dataAt + 4);
                int bitDepth = png[dataAt + 8] & 0xFF;
                colourType = png[dataAt + 9] & 0xFF;
                int compression = png[dataAt + 10] & 0xFF;
                int filter = png[dataAt + 11] & 0xFF;
                int interlace = png[dataAt + 12] & 0xFF;
                int channels = channels(colourType, bitDepth);
                if (width <= 0 || height <= 0 || channels == 0 || compression != 0 || filter != 0 || interlace > 1) {
                    return corrupt(Math.max(0, width), Math.max(0, height));
                }
                if (width != WIDTH || height != HEIGHT && height != LEGACY_HEIGHT) {
                    return new Result(Problem.WRONG_SIZE, width, height);
                }
                lines = new Scanlines(width, height, channels * bitDepth, interlace == 1);
            } else {
                switch (type) {
                    case "IHDR" -> {
                        return corrupt(width, height);
                    }
                    case "PLTE" -> {
                        if (palette || dataStarted || colourType == 0 || colourType == 4
                                || length == 0 || length % 3 != 0 || length > 256 * 3) {
                            return corrupt(width, height);
                        }
                        palette = true;
                    }
                    case "IDAT" -> {
                        if (dataEnded || colourType == 3 && !palette) {
                            return corrupt(width, height);
                        }
                        dataStarted = true;
                        if (!lines.feed(inflater, png, dataAt, length, out)) {
                            return corrupt(width, height);
                        }
                    }
                    case "IEND" -> {
                        if (length != 0 || !dataStarted) {
                            return corrupt(width, height);
                        }
                        end = true;
                    }
                    default -> {
                        // An unknown chunk is fine only when the format lets a reader skip it (lower-case first
                        // letter); an unknown critical chunk means a picture this check cannot vouch for.
                        if ((png[typeAt] & 0x20) == 0) {
                            return corrupt(width, height);
                        }
                    }
                }
                if (dataStarted && !"IDAT".equals(type)) {
                    dataEnded = true;
                }
            }
            offset = dataAt + length + 4;
        }
        if (!end || lines == null || !lines.complete(inflater)) {
            return corrupt(width, height);
        }
        return new Result(Problem.NONE, width, height);
    }

    /** Channels times bit depth must be one of the combinations the PNG format defines; 0 when it is not. */
    private static int channels(int colourType, int bitDepth) {
        return switch (colourType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16 ? 1 : 0;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 ? 1 : 0;
            case 2 -> bitDepth == 8 || bitDepth == 16 ? 3 : 0;
            case 4 -> bitDepth == 8 || bitDepth == 16 ? 2 : 0;
            case 6 -> bitDepth == 8 || bitDepth == 16 ? 4 : 0;
            default -> 0;
        };
    }

    private static boolean isChunkType(byte[] png, int at) {
        for (int i = 0; i < 4; i++) {
            int c = png[at + i];
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        return true;
    }

    private static Result corrupt(int width, int height) {
        return new Result(Problem.CORRUPT, width, height);
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | bytes[at + 3] & 0xFF;
    }

    /**
     * The unpacked picture, checked as it streams out of the inflater: every scanline starts with a filter byte from 0
     * to 4, and the stream holds exactly the scanlines of the image's passes.
     */
    private static final class Scanlines {
        private final int[] rowBytes;
        private int row;
        private int inRow;

        Scanlines(int width, int height, int bitsPerPixel, boolean interlaced) {
            int[] sizes = new int[height * 7];
            int count = 0;
            if (interlaced) {
                for (int[] pass : ADAM7) {
                    int passWidth = width > pass[0] ? (width - pass[0] + pass[2] - 1) / pass[2] : 0;
                    int passHeight = height > pass[1] ? (height - pass[1] + pass[3] - 1) / pass[3] : 0;
                    if (passWidth == 0) {
                        continue;
                    }
                    for (int y = 0; y < passHeight; y++) {
                        sizes[count++] = 1 + (passWidth * bitsPerPixel + 7) / 8;
                    }
                }
            } else {
                for (int y = 0; y < height; y++) {
                    sizes[count++] = 1 + (width * bitsPerPixel + 7) / 8;
                }
            }
            rowBytes = Arrays.copyOf(sizes, count);
        }

        /** Feeds one IDAT; false as soon as the stream is broken or holds more than the image. */
        boolean feed(Inflater inflater, byte[] png, int at, int length, byte[] out) {
            if (inflater.finished()) {
                return length == 0;
            }
            inflater.setInput(png, at, length);
            try {
                while (!inflater.finished() && !inflater.needsInput()) {
                    int produced = inflater.inflate(out);
                    if (produced == 0 && (inflater.needsDictionary() || !inflater.needsInput() && !inflater.finished())) {
                        return false;
                    }
                    if (!accept(out, produced)) {
                        return false;
                    }
                }
            } catch (DataFormatException broken) {
                return false;
            }
            return true;
        }

        private boolean accept(byte[] out, int produced) {
            int index = 0;
            while (index < produced) {
                if (row >= rowBytes.length) {
                    return false;
                }
                if (inRow == 0 && (out[index] & 0xFF) > 4) {
                    return false;
                }
                int take = Math.min(produced - index, rowBytes[row] - inRow);
                index += take;
                inRow += take;
                if (inRow == rowBytes[row]) {
                    row++;
                    inRow = 0;
                }
            }
            return true;
        }

        boolean complete(Inflater inflater) {
            return inflater.finished() && row == rowBytes.length && inRow == 0;
        }
    }

    /** Lower-case hex SHA-256: the name a skin goes by on every client, and the key its texture is cached under. */
    public static String sha256(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    public static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * A skin's pixels in the modern 64 by 64 layout, processed the way Minecraft processes the skins it downloads.
     * Pixels are packed ints with alpha in the top byte; the order of the other three channels does not matter here,
     * so the client can hand over NativeImage's own ABGR values unchanged.
     *
     * @param pixels row-major, {@code width * height} long; not modified
     * @return a new array of 64 * 64 pixels
     */
    public static int[] normalize(int[] pixels, int width, int height) {
        if (width != WIDTH || height != HEIGHT && height != LEGACY_HEIGHT || pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("not a 64x64 or 64x32 skin: " + width + "x" + height);
        }
        int[] image = new int[WIDTH * HEIGHT];
        System.arraycopy(pixels, 0, image, 0, pixels.length);
        boolean legacy = height == LEGACY_HEIGHT;
        if (legacy) {
            fill(image, 0, 32, 64, 32, 0);
            copy(image, 4, 16, 16, 32, 4, 4);
            copy(image, 8, 16, 16, 32, 4, 4);
            copy(image, 0, 20, 24, 32, 4, 12);
            copy(image, 4, 20, 16, 32, 4, 12);
            copy(image, 8, 20, 8, 32, 4, 12);
            copy(image, 12, 20, 16, 32, 4, 12);
            copy(image, 44, 16, -8, 32, 4, 4);
            copy(image, 48, 16, -8, 32, 4, 4);
            copy(image, 40, 20, 0, 32, 4, 12);
            copy(image, 44, 20, -8, 32, 4, 12);
            copy(image, 48, 20, -16, 32, 4, 12);
            copy(image, 52, 20, -8, 32, 4, 12);
        }
        opaque(image, 0, 0, 32, 16);
        if (legacy) {
            notchTransparencyHack(image, 32, 0, 64, 32);
        }
        opaque(image, 0, 16, 64, 32);
        opaque(image, 16, 48, 48, 64);
        return image;
    }

    private static void fill(int[] image, int x, int y, int w, int h, int value) {
        for (int row = y; row < y + h; row++) {
            for (int column = x; column < x + w; column++) {
                image[row * WIDTH + column] = value;
            }
        }
    }

    /** NativeImage#copyRect(x, y, dx, dy, w, h, mirrorX = true, mirrorY = false), the only form the conversion uses. */
    private static void copy(int[] image, int x, int y, int dx, int dy, int w, int h) {
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int value = image[(y + i) * WIDTH + x + j];
                image[(y + dy + i) * WIDTH + x + dx + (w - 1 - j)] = value;
            }
        }
    }

    private static void opaque(int[] image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                image[y * WIDTH + x] |= 0xFF000000;
            }
        }
    }

    /**
     * Old skins drew their hat layer fully opaque where they meant "no hat"; when every pixel of the layer is opaque,
     * vanilla clears it, and so does this.
     */
    private static void notchTransparencyHack(int[] image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                if ((image[y * WIDTH + x] >>> 24) < 128) {
                    return;
                }
            }
        }
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                image[y * WIDTH + x] &= 0x00FFFFFF;
            }
        }
    }
}
