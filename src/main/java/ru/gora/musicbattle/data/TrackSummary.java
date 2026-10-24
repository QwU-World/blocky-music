package ru.gora.musicbattle.data;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One row of the vote screen and of the DJ track picker.
 *
 * <p>Wave M3 (spec 06 section 9.4) added {@link #source}, {@link #setId}, {@link #contentSha256} and {@link #mapper}
 * so a row can say where it came from: a server beatmap installed from the Workshop, a legacy server track, a track a
 * player published on this server, or — built by the client, never sent by the server — one of the voter's own
 * installed sets. The chips and the players' warning in the vote screen read only {@link #source}.
 */
public record TrackSummary(
        String id,
        String title,
        String description,
        int bpm,
        int durationTicks,
        String previewFile,
        String publishedBy,
        Source source,
        /** The workshop set this row belongs to, 0 when it is not a workshop set. */
        long setId,
        /** {@link ru.gora.musicbattle.workshop.BeatmapManifest#contentSha256()}, empty when unknown. */
        String contentSha256,
        /** The mapper's name as the package declared it; empty for legacy tracks. */
        String mapper
) {
    /** Where a row comes from. The wire form is the ordinal as one byte. */
    public enum Source {
        /** A legacy track in {@code server_tracks} (operator-installed). */
        SERVER,
        /** A legacy track a player published on this server ({@code user_tracks}); carries the players' warning. */
        USER,
        /** A Workshop set installed on the server ({@code blocky_music/workshop/ws_<setId>}). */
        WORKSHOP,
        /** The voter's own installed set, offered by their client only (never sent by the server). */
        INSTALLED;

        private static final Source[] VALUES = values();

        public static Source byIndex(int index) {
            return index >= 0 && index < VALUES.length ? VALUES[index] : SERVER;
        }
    }

    private static final int MAX_ID = 255;
    private static final int MAX_TITLE = 128;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_PREVIEW_FILE = 255;
    private static final int MAX_PUBLISHED_BY = 64;
    private static final int MAX_CONTENT_SHA = 64;
    private static final int MAX_MAPPER = 32;

    public TrackSummary {
        id = networkString(id, MAX_ID);
        title = networkString(title, MAX_TITLE);
        description = networkString(description, MAX_DESCRIPTION);
        previewFile = networkString(previewFile, MAX_PREVIEW_FILE);
        publishedBy = networkString(publishedBy, MAX_PUBLISHED_BY);
        source = source == null ? Source.SERVER : source;
        setId = Math.max(0L, setId);
        contentSha256 = networkString(contentSha256, MAX_CONTENT_SHA);
        mapper = networkString(mapper, MAX_MAPPER);
    }

    /** A legacy row without workshop identity (server or player track). */
    public static TrackSummary legacy(String id, String title, String description, int bpm, int durationTicks,
                                      String previewFile, String publishedBy, boolean userTrack) {
        return new TrackSummary(id, title, description, bpm, durationTicks, previewFile, publishedBy,
                userTrack ? Source.USER : Source.SERVER, 0L, "", "");
    }

    /** Kept from before M3: the players' warning and the publisher tooltip ask exactly this. */
    public boolean userTrack() {
        return source == Source.USER;
    }

    /** The WORKSHOP category of the vote screen: server beatmaps plus both legacy sources. */
    public boolean workshopCategory() {
        return source != Source.INSTALLED;
    }

    public static TrackSummary read(RegistryFriendlyByteBuf buffer) {
        return new TrackSummary(
                buffer.readUtf(MAX_ID),
                buffer.readUtf(MAX_TITLE),
                buffer.readUtf(MAX_DESCRIPTION),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readUtf(MAX_PREVIEW_FILE),
                buffer.readUtf(MAX_PUBLISHED_BY),
                Source.byIndex(buffer.readByte()),
                buffer.readVarLong(),
                buffer.readUtf(MAX_CONTENT_SHA),
                buffer.readUtf(MAX_MAPPER)
        );
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(this.id, MAX_ID);
        buffer.writeUtf(this.title, MAX_TITLE);
        buffer.writeUtf(this.description, MAX_DESCRIPTION);
        buffer.writeVarInt(this.bpm);
        buffer.writeVarInt(this.durationTicks);
        buffer.writeUtf(this.previewFile, MAX_PREVIEW_FILE);
        buffer.writeUtf(this.publishedBy, MAX_PUBLISHED_BY);
        buffer.writeByte(this.source.ordinal());
        buffer.writeVarLong(this.setId);
        buffer.writeUtf(this.contentSha256, MAX_CONTENT_SHA);
        buffer.writeUtf(this.mapper, MAX_MAPPER);
    }

    private static String networkString(String value, int maximumLength) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maximumLength) {
            return safe;
        }
        int end = maximumLength;
        if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))
                && end < safe.length() && Character.isLowSurrogate(safe.charAt(end))) {
            end--;
        }
        return safe.substring(0, end);
    }
}
