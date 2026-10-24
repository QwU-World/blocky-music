package ru.gora.musicbattle.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Read-only server rhythm sampling for mobs near an audible music source. */
public final class MusicMobRhythm {
    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private MusicMobRhythm() {
    }

    /**
     * Returns the loudest audible battle rhythm, otherwise the loudest audible DJ
     * rhythm, or {@code null} in silence. Call only from the server thread.
     * Source discovery is shared by all mobs in a dimension for one server tick.
     */
    public static Beat sample(ServerLevel level, Vec3 position) {
        return sample(level, position, null);
    }

    public static Beat sample(ServerLevel level, Vec3 position, UUID currentSource) {
        int serverTick = level.getServer().getTickCount();
        Snapshot snapshot = SNAPSHOTS.get(level);
        if (snapshot == null || snapshot.serverTick() != serverTick) {
            List<Source> battleSources = new ArrayList<>();
            List<Source> djSources = new ArrayList<>();
            BattleManager.appendMusicMobSources(level, battleSources);
            DjPlaybackManager.appendMusicMobSources(level, djSources);
            snapshot = new Snapshot(serverTick, List.copyOf(battleSources), List.copyOf(djSources));
            SNAPSHOTS.put(level, snapshot);
        }
        // Real battles already own listener playback and shared speaker BPM in
        // overlap zones. Only an actually audible battle receives this priority.
        Beat battle = loudest(snapshot.battleSources(), position, currentSource);
        return battle != null ? battle : loudest(snapshot.djSources(), position, currentSource);
    }

    private static Beat loudest(List<Source> sources, Vec3 position, UUID currentSource) {
        Beat best = null;
        Beat current = null;
        double bestGain = 0.0D;
        double currentGain = 0.0D;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Source source : sources) {
            double distance = position.distanceToSqr(source.position());
            // Linear audio attenuation reaches zero at the exact radius edge.
            if (distance >= source.radius() * source.radius()) {
                continue;
            }
            // Match DJ audio arbitration: track gain x speaker gain x linear distance attenuation.
            double gain = source.volume() * Math.max(0.0D, 1.0D - Math.sqrt(distance) / source.radius());
            if (best == null || gain > bestGain + 0.0001D
                    || Math.abs(gain - bestGain) <= 0.0001D && (distance < bestDistance - 0.0001D
                    || Math.abs(distance - bestDistance) <= 0.0001D
                    && source.beat().sourceId().compareTo(best.sourceId()) < 0)) {
                best = source.beat();
                bestGain = gain;
                bestDistance = distance;
            }
            if (source.beat().sourceId().equals(currentSource) && gain > currentGain) {
                current = source.beat();
                currentGain = gain;
            }
        }
        // Like the listener transport, retain the current song within a 5% gain margin.
        return current != null && currentGain >= bestGain * 0.95D ? current : best;
    }

    static void addSource(List<Source> sources, Beat beat, Vec3 position, double radius, double volume) {
        if (Double.isFinite(radius) && radius > 0.0D
                && Double.isFinite(volume) && volume > 0.0D
                && Double.isFinite(beat.bpm()) && beat.bpm() > 0.0D) {
            sources.add(new Source(beat, position, radius, volume));
        }
    }

    /**
     * Both values use the effective playback timeline: ticks advance at 20/s and
     * BPM already includes playback speed. Beat phase is trackTicks * bpm / 1200.
     * Source identity changes when DJ transport restarts, seeks or changes speed.
     */
    public record Beat(UUID sourceId, double trackTicks, double bpm) { }

    record Source(Beat beat, Vec3 position, double radius, double volume) { }

    private record Snapshot(int serverTick, List<Source> battleSources, List<Source> djSources) { }
}
