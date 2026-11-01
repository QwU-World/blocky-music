import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import ru.gora.musicbattle.client.ClientTrackExporter;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * The first-beat offset (ChartBeat): absent reads as 0, it round-trips, a chart that does not use it is written and
 * read exactly as before, and the grid follows it.
 *
 * <p>Every chart in tools/fixtures/charts (small hand-written beatmaps committed for this check) and in the local,
 * uncommitted run/ folder is read by the current MusicBattleTracks reader. With a previous build's jar (argument 2 or
 * BLOCKY_PREVIOUS_JAR, ideally a build from before offsets) each chart is also read by the old reader and the notes are
 * compared; the same chart with an offset added must still read in the old build with the same notes, which is what a
 * player on the older jar meets. Without that jar the old-build comparisons are skipped and the check says so.
 * Charts are copied to a temporary folder first, because the reader may migrate a file in place.
 */
public final class ChartOffsetChecks {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String oldJar = args.length > 1 ? args[1] : System.getenv().getOrDefault("BLOCKY_PREVIOUS_JAR", "");
        fieldRules();
        gridRules();
        Path game = Files.createTempDirectory("blocky-chart-offset-");
        try {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
            ClassLoader old = oldJar.isBlank() || !Files.isRegularFile(Path.of(oldJar)) ? null : oldLoader(Path.of(oldJar));
            if (old == null) {
                System.out.println("chart-offset: SKIP old-build comparisons (no previous jar given; set BLOCKY_PREVIOUS_JAR)");
            }
            exporter(game, old);
            List<Path> charts = new ArrayList<>();
            for (String folder : new String[] {"tools/fixtures/charts", "run"}) {
                collectCharts(root.resolve(folder).normalize(), charts);
            }
            check(!charts.isEmpty(), "found charts to compare");
            for (Path chart : charts) {
                fileUnchanged(chart, game, old);
            }
            System.out.println("chart-offset: " + charts.size() + " chart files compared");
        } finally {
            deleteTree(game);
        }
        if (!failures.isEmpty()) {
            failures.forEach(failure -> System.out.println("FAIL " + failure));
            System.out.println("chart-offset: " + failures.size() + " of " + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("chart-offset: PASS (" + checks + ")");
    }

    private static void fieldRules() {
        check(ChartBeat.readOffsetMs(json("{}")) == 0, "absent offset reads as 0");
        check(ChartBeat.readOffsetMs(json("{\"offsetMs\":\"500\"}")) == 0, "a string offset reads as 0");
        check(ChartBeat.readOffsetMs(json("{\"offsetMs\":true}")) == 0, "a boolean offset reads as 0");
        check(ChartBeat.readOffsetMs(json("{\"offsetMs\":[1]}")) == 0, "an array offset reads as 0");
        check(ChartBeat.readOffsetMs(json("{\"offsetMs\":-40}")) == 0, "a negative offset clamps to 0");
        check(ChartBeat.readOffsetMs(json("{\"offsetMs\":99999}")) == ChartBeat.MAX_OFFSET_MS, "a huge offset clamps to the bound");
        check(ChartBeat.readOffsetMs(json("{\"offsetMs\":1e400}")) == 0, "an infinite offset reads as 0");
        JsonObject zero = new JsonObject();
        ChartBeat.writeOffsetMs(zero, 0);
        check(!zero.has(ChartBeat.OFFSET_FIELD), "offset 0 is not written");
        for (int value : new int[] {1, 437, 2000, ChartBeat.MAX_OFFSET_MS}) {
            JsonObject written = new JsonObject();
            ChartBeat.writeOffsetMs(written, value);
            check(ChartBeat.readOffsetMs(json(written.toString())) == value, "offset " + value + " round-trips");
        }
        check(ChartBeat.effectiveOffsetMs(1000, 1.5F) == 667, "a 1.5x battle moves beat 1 to 667 ms");
        check(ChartBeat.effectiveOffsetMs(1000, 1.0F) == 1000, "a normal battle keeps beat 1");
        check(ChartBeat.offsetForBeatAt(30_500.0D, 120) == 500, "beat 1 at 30.5 s folds to 500 ms at 120 BPM");
        check(ChartBeat.offsetForBeatAt(30_000.0D, 120) == 0, "beat 1 on a bar line folds to 0");
        check(ChartBeat.offsetForBeatAt(250.0D, 120) == 250, "beat 1 inside the first bar stays");
        check(ChartBeat.offsetForBeatAt(11_999.0D, 20) == 11_999, "the slowest bar fits the bound");
        check(ChartBeat.beatTicks(30.0D, 500) == 20.0D, "beat time counts from beat 1");
    }

    private static void gridRules() {
        int[] bpms = {20, 60, 96, 120, 128, 140, 150, 240, 333, 400};
        // Offset 0 must be the grid the editor drew before offsets existed, formula for formula.
        for (int bpm : bpms) {
            for (int precision = 1; precision <= 2; precision++) {
                long perCell = (long) bpm * 4 * precision;
                for (long cell = 0; cell < 2000; cell++) {
                    int legacy = (int) Math.floorDiv(2L * 1200 * cell + perCell, 2L * perCell);
                    if (ChartBeat.cellTick(0, bpm, 4 * precision, cell) != legacy) {
                        check(false, bpm + " BPM 1/" + 4 * precision + ": offset 0 cell " + cell + " moved");
                        break;
                    }
                }
                for (double tick = 0; tick < 600; tick += 0.37D) {
                    long legacy = Math.round(tick / (1200.0D / perCell));
                    if (ChartBeat.nearestCell(0, bpm, 4 * precision, tick) != legacy) {
                        check(false, bpm + " BPM: offset 0 nearest cell at " + tick + " moved");
                        break;
                    }
                }
            }
            for (long page = 0; page < 300; page++) {
                int legacy = (int) Math.floorDiv(2L * 9600 * page + bpm, 2L * bpm);
                check(ChartBeat.pageStart(0, bpm, 8, page) == legacy, bpm + " BPM: offset 0 page " + page + " start");
                check(Math.abs(ChartBeat.pageOriginTick(0, bpm, 8, page) - page * 9600.0D / bpm) < 1e-6, bpm + " BPM: offset 0 page origin " + page);
            }
            for (int tick = 0; tick < 3000; tick += 7) {
                long legacy = Math.max(0L, Math.ceilDiv((2L * tick + 1L) * bpm, 2L * 9600) - 1L);
                check(ChartBeat.pageOfTick(0, bpm, 8, tick) == legacy, bpm + " BPM: offset 0 page of tick " + tick);
            }
        }
        // With an offset every beat line sits offset + n beats from the start, rounded once, and pages tile the song.
        for (int bpm : bpms) {
            for (int offset : new int[] {1, 25, 26, 437, 1000, 1999, 5000, ChartBeat.MAX_OFFSET_MS}) {
                String at = bpm + " BPM offset " + offset + ": ";
                for (long cell = -40; cell < 400; cell++) {
                    double exact = (offset + cell * 15_000.0D / bpm) / 50.0D;
                    int tick = ChartBeat.cellTick(offset, bpm, 4, cell);
                    check(Math.abs(tick - exact) <= 0.5D + 1e-9, at + "cell " + cell + " rounds to " + tick + " from " + exact);
                    check(ChartBeat.nearestCell(offset, bpm, 4, exact) == cell, at + "cell " + cell + " is nearest to its own time");
                }
                check(ChartBeat.pageStart(offset, bpm, 8, 0) == 0, at + "page 0 starts the song");
                long first = ChartBeat.pagesBeforeBeatOne(offset, bpm, 8);
                check(Math.abs(ChartBeat.pageOriginTick(offset, bpm, 8, first) - offset / 50.0D) < 1e-9, at + "a page starts on beat 1");
                check(ChartBeat.pageOriginTick(offset, bpm, 8, 0) <= 0.5D, at + "page 0 begins no later than the song");
                check(first == 0 || ChartBeat.pageStart(offset, bpm, 8, 1) >= 1, at + "page 1 begins after the song starts");
                for (int tick = 0; tick < 4000; tick += 3) {
                    long page = ChartBeat.pageOfTick(offset, bpm, 8, tick);
                    int start = ChartBeat.pageStart(offset, bpm, 8, page);
                    int end = ChartBeat.pageStart(offset, bpm, 8, page + 1);
                    if (!(start <= tick && tick < end)) {
                        check(false, at + "tick " + tick + " is on page " + page + " [" + start + ", " + end + ")");
                        break;
                    }
                }
            }
        }
        // The owner-facing example: 120 BPM with beat 1 at 0.5 s puts beats on ticks 10, 20, 30 and quarters between.
        check(ChartBeat.cellTick(500, 120, 4, 0) == 10 && ChartBeat.cellTick(500, 120, 4, 4) == 20
                && ChartBeat.cellTick(500, 120, 4, 1) == 13 && ChartBeat.cellTick(500, 120, 4, -1) == 8,
                "120 BPM offset 500: lines at 8, 10, 13 ... 20");
        check(ChartBeat.pageStart(500, 120, 8, 1) == 10 && ChartBeat.pageOfTick(500, 120, 8, 9) == 0
                && ChartBeat.pageOfTick(500, 120, 8, 10) == 1, "120 BPM offset 500: page 1 starts on beat 1 at tick 10");
    }

    /** Offset 0 writes byte for byte what the previous build wrote; an offset adds only its own field. */
    private static void exporter(Path game, ClassLoader old) throws Exception {
        List<BattleNote> notes = List.of(new BattleNote(0, 1, 10, 0), new BattleNote(1, 2, 25, 6), new BattleNote(0, 3, 40, 0));
        Path written = ClientTrackExporter.export("offset_none", "None", "", 128, 0, 1.0F, "", "", false, false, notes, List.of());
        String plain = Files.readString(written, StandardCharsets.UTF_8);
        check(!plain.contains(ChartBeat.OFFSET_FIELD), "a chart without an offset carries no offset field");
        Path legacyApi = ClientTrackExporter.export("offset_none", "None", "", 128, 1.0F, "", "", false, false, notes, List.of());
        check(plain.equals(Files.readString(legacyApi, StandardCharsets.UTF_8)), "the old export signature writes the same bytes");
        if (old != null) {
            Class<?> oldExporter = old.loadClass("ru.gora.musicbattle.client.ClientTrackExporter");
            Class<?> oldNote = old.loadClass("ru.gora.musicbattle.data.BattleNote");
            List<Object> oldNotes = new ArrayList<>();
            for (BattleNote note : notes) {
                oldNotes.add(oldNote.getConstructor(int.class, int.class, int.class, int.class)
                        .newInstance(note.owner(), note.lane(), note.tick(), note.durationTicks()));
            }
            Method export = oldExporter.getMethod("export", String.class, String.class, String.class, int.class, float.class,
                    String.class, String.class, boolean.class, boolean.class, List.class, List.class);
            Path oldWritten = (Path) export.invoke(null, "offset_none", "None", "", 128, 1.0F, "", "", false, false, oldNotes, List.of());
            check(plain.equals(Files.readString(oldWritten, StandardCharsets.UTF_8)), "offset 0 writes exactly what the previous build wrote");
        }
        Path shifted = ClientTrackExporter.export("offset_some", "Some", "", 128, 734, 1.0F, "", "", false, false, notes, List.of());
        JsonObject root = json(Files.readString(shifted, StandardCharsets.UTF_8));
        check(root.get(ChartBeat.OFFSET_FIELD).getAsInt() == 734, "an offset is written as whole ms");
        root.remove(ChartBeat.OFFSET_FIELD);
        root.addProperty("id", "offset_none");
        root.addProperty("title", "None");
        check(root.equals(json(plain)), "an offset adds nothing but its own field");
    }

    private static void fileUnchanged(Path chart, Path game, ClassLoader old) throws Exception {
        String name = chart.getFileName().toString();
        Path work = Files.createTempDirectory(game, "chart-");
        Path copy = work.resolve(name);
        Files.copy(chart, copy);
        Object fresh = readTrack(ChartOffsetChecks.class.getClassLoader(), copy, work);
        if (fresh == null) {
            // Fixtures that only the service's sanitizer accepts may lack what the game loader needs; nothing to compare.
            return;
        }
        boolean hasField = json(Files.readString(chart, StandardCharsets.UTF_8)).has(ChartBeat.OFFSET_FIELD);
        if (!hasField) {
            check((int) call(fresh, "offsetMs") == 0, name + ": a chart without the field reads as offset 0");
        }
        if (old == null) {
            return;
        }
        Path oldWork = Files.createTempDirectory(game, "chart-old-");
        Path oldCopy = oldWork.resolve(name);
        Files.copy(chart, oldCopy);
        Object before = readTrack(old, oldCopy, oldWork);
        check(before != null, name + ": the previous build reads it");
        if (before != null) {
            check(String.valueOf(call(fresh, "notes")).equals(String.valueOf(call(before, "notes"))), name + ": notes are identical to the previous build");
            check(String.valueOf(call(fresh, "effects")).equals(String.valueOf(call(before, "effects"))), name + ": effects are identical to the previous build");
            check(call(fresh, "bpm").equals(call(before, "bpm")) && call(fresh, "durationTicks").equals(call(before, "durationTicks")),
                    name + ": BPM and length are identical to the previous build");
        }
        // The same chart with beat 1 moved: the previous build must still load it, with the same notes.
        JsonObject shifted = json(Files.readString(chart, StandardCharsets.UTF_8));
        shifted.remove(ChartBeat.OFFSET_FIELD);
        ChartBeat.writeOffsetMs(shifted, 1250);
        Path shiftedWork = Files.createTempDirectory(game, "chart-shifted-");
        Path shiftedCopy = shiftedWork.resolve(name);
        Files.writeString(shiftedCopy, shifted.toString(), StandardCharsets.UTF_8);
        Path shiftedOldCopy = Files.createTempDirectory(game, "chart-shifted-old-").resolve(name);
        Files.writeString(shiftedOldCopy, shifted.toString(), StandardCharsets.UTF_8);
        Object shiftedNew = readTrack(ChartOffsetChecks.class.getClassLoader(), shiftedCopy, shiftedWork);
        Object shiftedOld = readTrack(old, shiftedOldCopy, shiftedOldCopy.getParent());
        check(shiftedNew != null && (int) call(shiftedNew, "offsetMs") == 1250, name + ": the new build reads the offset");
        check(shiftedOld != null, name + ": the previous build still loads a chart with an offset");
        if (shiftedNew != null && shiftedOld != null && before != null) {
            check(String.valueOf(call(shiftedOld, "notes")).equals(String.valueOf(call(before, "notes"))),
                    name + ": the previous build reads the same notes with an offset present");
            check(String.valueOf(call(shiftedNew, "notes")).equals(String.valueOf(call(fresh, "notes"))),
                    name + ": an offset leaves the notes where they are");
        }
    }

    private static Object readTrack(ClassLoader loader, Path chart, Path directory) throws Exception {
        Class<?> tracks = loader.loadClass("ru.gora.musicbattle.server.MusicBattleTracks");
        Class<?> source = loader.loadClass("ru.gora.musicbattle.server.MusicBattleTracks$Source");
        Method read = tracks.getDeclaredMethod("readTrack", Path.class, Path.class, source);
        read.setAccessible(true);
        Object sourceValue = source.getEnumConstants()[0];
        Optional<?> track = (Optional<?>) read.invoke(null, chart, directory, sourceValue);
        return track.orElse(null);
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** Mod classes come from the previous jar first; Minecraft, NeoForge and Gson are shared with this build. */
    private static ClassLoader oldLoader(Path jar) throws IOException {
        URL url = jar.toUri().toURL();
        ClassLoader parent = ChartOffsetChecks.class.getClassLoader();
        return new URLClassLoader(new URL[] {url}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("ru.gora.musicbattle.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
    }

    private static void collectCharts(Path folder, List<Path> charts) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p)).sorted().toList()) {
                try {
                    JsonObject root = json(Files.readString(path, StandardCharsets.UTF_8));
                    if (root.has("notes") && root.get("notes").isJsonArray()) {
                        charts.add(path);
                    }
                } catch (RuntimeException | IOException ignored) {
                    // Not a chart: configs, stats, broken fixtures.
                }
            }
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures.add(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
