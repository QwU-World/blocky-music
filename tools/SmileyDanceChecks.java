import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import ru.gora.musicbattle.data.MusicBeatMath;
import ru.gora.musicbattle.data.SmileyDancePattern;

/** Musical diversity and transport regressions, independent of Minecraft physics. */
public final class SmileyDanceChecks {
    private static int checks;

    public static void main(String[] args) {
        Random ids = new Random(0x5A11E1L);
        Set<Double> offsets = new HashSet<>();
        Set<Double> simultaneousPhases = new HashSet<>();
        Set<Double> heights = new HashSet<>();
        for (int entity = 0; entity < 4096; entity++) {
            UUID id = new UUID(ids.nextLong(), ids.nextLong());
            SmileyDancePattern pattern = SmileyDancePattern.forEntity(id);
            SmileyDancePattern restored = SmileyDancePattern.forEntity(UUID.fromString(id.toString()));
            require(pattern.equals(restored), "same saved/spawn UUID keeps the dance pattern");
            offsets.add(pattern.phaseOffset());
            simultaneousPhases.add(pattern.phase(0.125));
            heights.add(pattern.hopHeight(0.5));
            close(pattern.phaseOffset() * 4, Math.rint(pattern.phaseOffset() * 4),
                    "phase belongs to quarter-beat grid");
            require(pattern.phaseOffset() >= 0 && pattern.phaseOffset() < 1,
                    "offset stays inside one beat");

            // Exactly one takeoff/landing and one apex per musical beat, all on the grid.
            int contacts = 0;
            int peaks = 0;
            for (double beat : new double[] {0, 0.25, 0.5, 0.75}) {
                double height = pattern.hopHeight(pattern.phase(beat));
                if (Math.abs(height) < 1.0E-9) contacts++;
                if (Math.abs(height - pattern.hopHeight(0.5)) < 1.0E-9) peaks++;
                require(pattern.hopHeight(pattern.phase(beat + 0.125)) > 0.0,
                        "no jittered/off-grid landing");
                close(pattern.hopHeight(pattern.phase(beat + 10000)), height,
                        "height never accumulates over the track");
            }
            require(contacts == 1, "one physical floor contact per beat");
            require(peaks == 1, "one physical apex per beat");
            close(pattern.hopHeight(0), 0, "floor contact height");
            require(pattern.hopHeight(0.5) >= 0.40 && pattern.hopHeight(0.5) <= 0.53,
                    "individual hop height stays modest");
            for (double phase : new double[] {-100.25, -0.01, 0, 0.125, 0.5, 0.99, 1,
                    Double.NaN, Double.POSITIVE_INFINITY}) {
                double height = pattern.hopHeight(phase);
                require(Double.isFinite(height) && height >= 0 && height <= SmileyDancePattern.MAX_HOP_HEIGHT,
                        "finite bounded collision target");
            }
        }
        require(offsets.size() >= 4, "crowd must have distinct musical phases instead of moving in unison");
        require(simultaneousPhases.size() >= 4, "individual offsets must change actual motion at the same music time");
        require(heights.size() >= 8, "crowd must have visibly varied hop heights");

        UUID dancer = UUID.fromString("759a6e24-cf3b-4b2d-881b-29553039802c");
        SmileyDancePattern pattern = SmileyDancePattern.forEntity(dancer);
        for (double sourceBpm : new double[] {60, 120, 137, 173, 300}) {
            for (double sourceTick : new double[] {0, 23.5, 247.25, 850.75, 50000.25}) {
                double originalPhase = pattern.phase(MusicBeatMath.phase(sourceTick, sourceBpm));
                for (double speed : new double[] {1, 1.5, 2}) {
                    double runtimeTick = sourceTick / speed;
                    double bpm = sourceBpm * speed;
                    double rawAnchor = MusicBeatMath.phase(runtimeTick, bpm);
                    close(MusicBeatMath.phaseDistance(pattern.phase(rawAnchor), originalPhase), 0,
                            "speed changes preserve each dancer's source phase");
                    for (long resumeTick : new long[] {1000, 9_000_000}) {
                        close(MusicBeatMath.phaseDistance(pattern.phase(
                                MusicBeatMath.phaseAt(rawAnchor, resumeTick, resumeTick, bpm)), originalPhase),
                                0, "seek/resume reanchors without changing individual offset");
                        for (double elapsed : new double[] {0.25, 1, 50, 99.75}) {
                            double clientPhase = pattern.phase(MusicBeatMath.phaseAt(
                                    (float) rawAnchor, resumeTick, resumeTick + elapsed, bpm));
                            double serverPhase = pattern.phase(MusicBeatMath.phase(runtimeTick + elapsed, bpm));
                            close(MusicBeatMath.phaseDistance(clientPhase, serverPhase), 0,
                                    "synced raw anchor gives same individual phase on client/server");
                        }
                    }
                }
            }
        }
        System.out.println("Smiley dance checks passed: " + checks
                + " (" + offsets.size() + " phases, " + heights.size() + " heights)");
    }

    private static void close(double actual, double expected, String name) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 0.000001,
                name + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
