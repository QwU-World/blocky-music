package ru.gora.musicbattle.audio.codec;

/**
 * The file is audio this package does not read, or not audio at all: a format the player has to convert elsewhere,
 * not a song that broke. A subclass of {@link AudioFormatException} so callers that only care about "cannot use this
 * file" need no second catch. It has a file of its own because the decoders throw it too (a WAV can carry a codec
 * nobody here decodes), and their checks compile them without the rest of the conversion.
 */
public final class UnsupportedFormatException extends AudioFormatException {
    private final String formatName;

    public UnsupportedFormatException(String formatName) {
        super(formatName == null
                ? "Not a format Blocky Music reads (it reads MP3, Ogg Vorbis, Opus, WAV, FLAC, M4A, AAC, WebM, MKV and"
                        + " MPEG-TS)."
                : formatName + " is not a format Blocky Music reads (it reads MP3, Ogg Vorbis, Opus, WAV, FLAC, M4A, AAC,"
                        + " WebM, MKV and MPEG-TS).");
        this.formatName = formatName;
    }

    /** What the file was recognised as, such as "WMA/WMV", or {@code null} when it was not recognised at all. */
    public String formatName() {
        return formatName;
    }
}
