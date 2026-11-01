package ru.gora.musicbattle.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The Workshop set a DJ booth's beatmap was read from when the server holds that set only in
 * {@code <world>/blocky_music/workshop_cache/<contentSha256>/}: a DJ without map rights brought their installed copy,
 * or a battle vote did. Such a set is not in the catalogue, so nothing else on the server remembers where its charts
 * are. The booth keeps this next to each such {@code ws_} id in its list and its playlist, and the server finds the
 * charts again from it after a restart and keeps the cache clean-up away from them (see
 * {@code server.DjWorkshopCache}).
 */
public record DjWorkshopSource(long setId, String contentSha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** NBT list of {@code {Track, Set, Sha}} compounds; a booth saved before 0.2.0-beta.2 has none. */
    public static final String TAG = "DjWorkshopSources";

    public DjWorkshopSource {
        if (setId <= 0 || contentSha256 == null || !SHA256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("not a cached Workshop set");
        }
    }

    /** Whether these two values could name a cached set at all; an invalid pair is dropped, never thrown. */
    public static boolean valid(long setId, String contentSha256) {
        return setId > 0 && contentSha256 != null && SHA256.matcher(contentSha256).matches();
    }

    /** Writes {@code sources} (track id -> set) as a list, in their order. */
    public static ListTag write(Map<String, DjWorkshopSource> sources) {
        ListTag list = new ListTag();
        sources.forEach((trackId, source) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Track", trackId);
            entry.putLong("Set", source.setId());
            entry.putString("Sha", source.contentSha256());
            list.add(entry);
        });
        return list;
    }

    /**
     * Reads a list written by {@link #write}. Entries with a blank or overlong id, or one that does not belong to the
     * set it names ({@code ws_<setId>_...}), are dropped, so a damaged tag cannot point a beatmap at another set's files.
     */
    public static Map<String, DjWorkshopSource> read(CompoundTag tag, String key, int maxTrackIdLength) {
        Map<String, DjWorkshopSource> sources = new LinkedHashMap<>();
        if (!tag.contains(key, Tag.TAG_LIST)) {
            return sources;
        }
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            String trackId = entry.getString("Track");
            long setId = entry.getLong("Set");
            String sha = entry.getString("Sha");
            if (trackId.isBlank() || trackId.length() > maxTrackIdLength || !valid(setId, sha)
                    || !trackId.startsWith("ws_" + setId + "_")) {
                continue;
            }
            sources.putIfAbsent(trackId, new DjWorkshopSource(setId, sha));
        }
        return sources;
    }

    /** The ids a booth still uses: its list, its playlist rows, the song on its deck and the selected card. */
    public static Set<String> referenced(Collection<String> library, Collection<String> playlistTrackIds,
                                         String activeTrackId, String selectedTrackId) {
        Set<String> ids = new LinkedHashSet<>(library);
        ids.addAll(playlistTrackIds);
        if (activeTrackId != null && !activeTrackId.isBlank()) {
            ids.add(activeTrackId);
        }
        if (selectedTrackId != null && !selectedTrackId.isBlank()) {
            ids.add(selectedTrackId);
        }
        return ids;
    }
}
