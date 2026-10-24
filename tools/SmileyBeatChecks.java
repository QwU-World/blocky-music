import ru.gora.musicbattle.data.MusicBeatMath;

/** Standalone deterministic checks: javac + java, without a running Minecraft instance. */
public final class SmileyBeatChecks {
    private static int checks;

    public static void main(String[] args) {
        for (double bpm : new double[] {20, 60, 90, 120, 137, 180, 240, 300, 600, 800}) {
            double ticksPerBeat = 1200.0 / bpm;
            for (int beat = 0; beat < 1000; beat++) {
                double contact = MusicBeatMath.phase(beat * ticksPerBeat, bpm);
                close(MusicBeatMath.phaseDistance(contact, 0), 0, "beat contact " + bpm);
                close(MusicBeatMath.hopHeight(contact), 0, "no floor drift " + bpm);
                double apex = MusicBeatMath.phase((beat + 0.5) * ticksPerBeat, bpm);
                close(apex, 0.5, "half-beat apex " + bpm);
                close(MusicBeatMath.hopHeight(apex), MusicBeatMath.HOP_HEIGHT, "fixed apex height " + bpm);
            }
        }

        // Changing tempo at the same source-audio position must not change the beat phase.
        double sourceTick = 247.25;
        for (double sourceBpm : new double[] {120, 137, 173}) {
            for (double speed : new double[] {1, 1.5, 2}) {
                close(MusicBeatMath.phase(sourceTick / speed, sourceBpm * speed),
                        MusicBeatMath.phase(sourceTick, sourceBpm), "speed preserves source phase");
            }
        }

        // Forward/backward seeks create fresh clock anchors; do not integrate old elapsed ticks.
        long anchor = 9_000_000L;
        for (double target : new double[] {850.75, 23.5, 0, 50000.25}) {
            double phase = MusicBeatMath.phase(target, 137);
            close(MusicBeatMath.phaseAt(phase, anchor, anchor, 137), phase, "seek anchor");
            for (double elapsed : new double[] {0.25, 1, 50, 99.75}) {
                close(MusicBeatMath.phaseDistance(MusicBeatMath.phaseAt(phase, anchor, anchor + elapsed, 137),
                        MusicBeatMath.phase(target + elapsed, 137)), 0, "fractional clock progress");
            }
        }
        close(MusicBeatMath.phaseDistance(0.99, 0.01), 0.02, "wrap-aware resync threshold");
        for (double phase : new double[] {-10.25, -0.01, 0, 0.25, 0.5, 0.99, 1, 10000.2,
                Double.NaN, Double.POSITIVE_INFINITY}) {
            double height = MusicBeatMath.hopHeight(phase);
            require(Double.isFinite(height) && height >= 0 && height <= MusicBeatMath.HOP_HEIGHT,
                    "bounded finite hop");
        }
        System.out.println("Smiley beat checks passed: " + checks);
    }

    private static void close(double actual, double expected, String name) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 0.000001, name
                + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
