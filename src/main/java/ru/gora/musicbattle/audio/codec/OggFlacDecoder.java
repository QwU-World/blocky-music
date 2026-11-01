package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * FLAC in Ogg, the usual content of an .oga file and what {@code ffmpeg -c:a flac -f ogg} writes.
 *
 * <p>The mapping (Ogg FLAC 1.0) puts a native FLAC stream into packets: the first packet is {@code 0x7F "FLAC"}, the
 * mapping's version (1.0), the number of header packets that follow, and then the native stream's {@code "fLaC"}
 * signature with its STREAMINFO block; every other header packet is one more metadata block, the last one flagged as
 * last; and every audio packet is exactly one FLAC frame. Those bytes put back to back are a native FLAC stream, which
 * {@link FlacDecoder} reads as it reads a .flac file, the way {@link MatroskaAudio} hands it a Matroska FLAC track.
 * The pages' checksums and sequence numbers are checked by {@link OggPageReader}, as for Ogg Vorbis.
 */
final class OggFlacDecoder {
    /** {@code 0x7F "FLAC"}, the major and minor version, the header count, then {@code "fLaC"}. */
    private static final int SIGNATURE_OFFSET = 9;

    private OggFlacDecoder() {
    }

    /**
     * Opens an Ogg FLAC stream. On success the returned stream owns {@code in} and closes it; on failure {@code in} is
     * left to the caller.
     */
    static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        OggPageReader ogg = new OggPageReader(in, false);
        ogg.chooseFlacStream();
        if (!ogg.nextPacket(true)) {
            throw new AudioFormatException("The Ogg FLAC stream has no header.");
        }
        byte[] first = ogg.packetData();
        int length = ogg.packetLength();
        if (length < SIGNATURE_OFFSET + 4 || first[0] != 0x7F || first[1] != 'F' || first[2] != 'L' || first[3] != 'A'
                || first[4] != 'C') {
            throw new AudioFormatException("The Ogg FLAC header is damaged.");
        }
        if (first[5] != 1) {
            throw new UnsupportedFormatException("Ogg FLAC " + (first[5] & 0xFF) + "." + (first[6] & 0xFF));
        }
        if (first[SIGNATURE_OFFSET] != 'f' || first[SIGNATURE_OFFSET + 1] != 'L' || first[SIGNATURE_OFFSET + 2] != 'a'
                || first[SIGNATURE_OFFSET + 3] != 'C') {
            throw new AudioFormatException("The Ogg FLAC header has no FLAC signature.");
        }
        return FlacDecoder.open(new Packets(in, ogg, SIGNATURE_OFFSET));
    }

    /** The stream's packets back to back, the first one from its "fLaC" signature on. */
    private static final class Packets extends InputStream {
        private final InputStream source;
        private final OggPageReader ogg;
        private int position;
        private boolean ended;

        Packets(InputStream source, OggPageReader ogg, int start) {
            this.source = source;
            this.ogg = ogg;
            this.position = start;
        }

        /** Whether a byte is left, fetching the next packet once this one is used up. */
        private boolean more() throws IOException {
            while (!ended && position >= ogg.packetLength()) {
                if (!ogg.nextPacket(true)) {
                    ended = true;
                    return false;
                }
                position = 0;
            }
            return !ended;
        }

        @Override
        public int read() throws IOException {
            if (!more()) {
                return -1;
            }
            return ogg.packetData()[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (!more()) {
                return -1;
            }
            int count = Math.min(length, ogg.packetLength() - position);
            System.arraycopy(ogg.packetData(), position, target, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
