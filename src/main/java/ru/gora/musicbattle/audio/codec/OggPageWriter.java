package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps Vorbis packets in Ogg pages (RFC 3533): one logical stream, lacing values, the page CRC, granule positions.
 *
 * <p>Pages are cut at about 4 KiB, which keeps a streaming decoder's buffering small and costs under one percent in
 * page headers. The caller decides where a page must end ({@link #flush}): the identification header has to be
 * alone on the first page, and the first audio packet has to start a fresh page, or stb_vorbis refuses the file.
 */
final class OggPageWriter {
    private static final int TARGET_PAGE_BYTES = 4096;
    private static final int[] CRC_TABLE = crcTable();

    private final OutputStream out;
    private final int serial;
    private int sequence;
    private boolean firstPage = true;

    private byte[] body = new byte[TARGET_PAGE_BYTES * 2];
    private int bodyLength;
    private final int[] lacing = new int[255];
    private int segments;
    /** The granule of the last packet that ended on the page being built, or -1 when none has. */
    private long pageGranule = -1L;
    /** Whether the page being built begins with the rest of a packet started on the previous page. */
    private boolean continued;

    OggPageWriter(OutputStream out, int serial) {
        this.out = out;
        this.serial = serial;
    }

    /**
     * Adds one packet.
     *
     * @param granule the stream position once this packet is decoded (0 for the headers)
     * @param last whether this is the final packet; its page then carries the end-of-stream flag and goes out at once
     */
    void packet(byte[] data, long granule, boolean last) throws IOException {
        int offset = 0;
        int remaining = data.length;
        while (true) {
            if (segments == 255) {
                emit(false);
                // Only a page that opens inside this packet is a continuation; one that opens on its first byte
                // starts clean.
                continued = offset > 0;
            }
            int chunk = Math.min(255, remaining);
            append(data, offset, chunk);
            lacing[segments++] = chunk;
            offset += chunk;
            remaining -= chunk;
            // A segment shorter than 255 ends the packet, which is why a packet whose length is a multiple of 255
            // needs a final empty segment.
            if (chunk < 255) {
                break;
            }
        }
        pageGranule = granule;
        if (last) {
            emit(true);
        } else if (bodyLength >= TARGET_PAGE_BYTES) {
            emit(false);
        }
    }

    /** Ends the current page here, if it holds anything. */
    void flush() throws IOException {
        if (segments > 0) {
            emit(false);
        }
    }

    private void append(byte[] data, int offset, int count) {
        if (bodyLength + count > body.length) {
            body = java.util.Arrays.copyOf(body, Math.max(body.length * 2, bodyLength + count));
        }
        System.arraycopy(data, offset, body, bodyLength, count);
        bodyLength += count;
    }

    private void emit(boolean endOfStream) throws IOException {
        byte[] page = new byte[27 + segments + bodyLength];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[4] = 0;
        int flags = (continued ? 1 : 0) | (firstPage ? 2 : 0) | (endOfStream ? 4 : 0);
        page[5] = (byte) flags;
        putLong(page, 6, pageGranule);
        putInt(page, 14, serial);
        putInt(page, 18, sequence++);
        putInt(page, 22, 0);
        page[26] = (byte) segments;
        for (int i = 0; i < segments; i++) {
            page[27 + i] = (byte) lacing[i];
        }
        System.arraycopy(body, 0, page, 27 + segments, bodyLength);
        putInt(page, 22, crc(page));
        out.write(page);
        firstPage = false;
        segments = 0;
        bodyLength = 0;
        pageGranule = -1L;
        continued = false;
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }

    private static void putLong(byte[] b, int at, long v) {
        putInt(b, at, (int) v);
        putInt(b, at + 4, (int) (v >>> 32));
    }

    /** Ogg's CRC: polynomial 0x04C11DB7, not reflected, zero start, no final inversion. */
    static int crc(byte[] page) {
        int crc = 0;
        for (byte b : page) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
        }
        return crc;
    }

    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            table[i] = r;
        }
        return table;
    }
}
