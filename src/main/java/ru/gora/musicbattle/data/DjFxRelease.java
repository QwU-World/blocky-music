package ru.gora.musicbattle.data;

import java.util.ArrayList;
import java.util.List;

/** Pure lifetime rules; releasing never freezes or resets the animation clock. */
public final class DjFxRelease {
    private DjFxRelease() {}

    public static int tailTicks(TrackEffect effect, float speed) {
        if (!effect.settings().fadeOutEnabled()) return 0;
        float ticks = effect.settings().fadeOutTicks();
        return Math.max(1, (int)Math.ceil((ticks > 0 ? ticks : 3) / Math.max(1, speed)));
    }

    public static List<TrackEffect> at(List<TrackEffect> effects, float stopTick, float speed) {
        List<TrackEffect> result = new ArrayList<>();
        for (TrackEffect effect : effects) {
            float end = effect.tick() + (effect.durationTicks() > 0 ? effect.durationTicks() : effect.visibleDurationTicks() / Math.max(1, speed));
            if (effect.tick() > stopTick || end <= stopTick) continue;
            int tail = tailTicks(effect, speed);
            if (tail == 0) continue;
            int releaseEnd = Math.min((int)Math.ceil(end), (int)Math.ceil(stopTick) + tail);
            result.add(effect.withTimeline(effect.lane(), effect.tick(), Math.max(1, releaseEnd - effect.tick())));
        }
        return List.copyOf(result);
    }

    public static int endTick(List<TrackEffect> effects) {
        return effects.stream().mapToInt(TrackEffect::endTick).max().orElse(0);
    }
}
