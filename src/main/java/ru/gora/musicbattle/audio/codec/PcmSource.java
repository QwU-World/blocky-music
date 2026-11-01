package ru.gora.musicbattle.audio.codec;

import java.io.IOException;

/**
 * Something that can be decoded from the start as often as needed. The Vorbis encoder reads its input twice, once to
 * learn the song's statistics and once to write it with codebooks made for exactly that song, so it takes a source
 * rather than a single stream.
 */
@FunctionalInterface
public interface PcmSource {
    PcmStream open() throws IOException;
}
