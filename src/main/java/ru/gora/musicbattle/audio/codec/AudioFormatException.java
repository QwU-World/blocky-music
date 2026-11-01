package ru.gora.musicbattle.audio.codec;

import java.io.IOException;

/**
 * The bytes are not audio this package can read: an unknown format, a variant it does not support, or a damaged file.
 * Its own type, because a player is told to pick another file rather than that something broke.
 */
public class AudioFormatException extends IOException {
    public AudioFormatException(String message) {
        super(message);
    }

    public AudioFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
