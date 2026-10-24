package ru.gora.musicbattle.server;

import java.util.Set;
import java.util.UUID;

/** Chooses a stable audience snapshot for DJ audio preloading. */
public final class DjPreloadAudience {
    private DjPreloadAudience() {
    }

    public static Set<UUID> choose(boolean liveSessionMatches, Set<UUID> liveAudience,
                                   Set<UUID> currentCoverageAudience) {
        Set<UUID> source = liveSessionMatches ? liveAudience : currentCoverageAudience;
        return source == null || source.isEmpty() ? Set.of() : Set.copyOf(source);
    }
}
