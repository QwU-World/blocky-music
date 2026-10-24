import java.lang.reflect.Field;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import ru.gora.musicbattle.client.ClientFnfChartImporter;
import ru.gora.musicbattle.client.ClientTrackExporter;
import ru.gora.musicbattle.data.BattleNote;

/**
 * The rules that keep the chart editor from crashing or losing work over something a player pasted or picked: what
 * the file boxes accept, how an FNF chart is read when it is broken, and how many notes a chart may hold.
 *
 * <p>Windows rejects characters macOS and Linux accept, and that is where the pasted-URL crash lived, so the path
 * rules run against a stand-in for the Windows parser as well as this machine's own. Everything here calls the real
 * methods of the compiled mod; none of it needs a running game.
 */
public final class EditorImportChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        cleaning();
        webAddresses();
        windowsPaths();
        requiredPaths();
        fnfCharts();
        noteLimit();
        Path game = Files.createTempDirectory("blocky-editor-import-");
        try {
            // A throwaway game folder, so the exporter and the importer write where nothing of the player's lives.
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
            saving(game);
            osuWithUnconvertibleSong(game);
        } finally {
            deleteTree(game);
        }
        sources(root);
        System.out.println("Editor import checks passed: " + checks);
        System.out.println("NOT COVERED: the drawn editor, the exit dialog and the osu! import on a machine without ffmpeg;"
                + " those need Minecraft, and the last one a computer whose ffmpeg is really missing");
    }

    private static void cleaning() {
        equal(ClientTrackExporter.cleanPathInput("\"C:\\Users\\me\\Music\\song.ogg\""), "C:\\Users\\me\\Music\\song.ogg",
                "Explorer's Copy as path loses its quotes");
        equal(ClientTrackExporter.cleanPathInput("  'song.ogg'  "), "song.ogg", "single quotes from a terminal go too");
        equal(ClientTrackExporter.cleanPathInput("\" song.ogg \""), "song.ogg", "spaces inside the quotes are trimmed");
        equal(ClientTrackExporter.cleanPathInput("\"song.ogg"), "\"song.ogg", "an unmatched quote is left for the parser to judge");
        equal(ClientTrackExporter.cleanPathInput("\""), "\"", "a lone quote is not a pair");
        equal(ClientTrackExporter.cleanPathInput(null), "", "an empty box is empty");
        equal(ClientTrackExporter.cleanPathInput("cover.png"), "cover.png", "a plain name is untouched");
    }

    private static void webAddresses() {
        check(ClientTrackExporter.isWebAddress("https://i.imgur.com/x.png"), "https is a web address");
        check(ClientTrackExporter.isWebAddress("HTTP://example.com/a.ogg"), "the scheme is case-insensitive");
        check(ClientTrackExporter.isWebAddress("www.example.com/a.png"), "www. without a scheme is a web address");
        check(ClientTrackExporter.isWebAddress("ftp://host/a.ogg"), "any scheme:// is a web address");
        check(!ClientTrackExporter.isWebAddress("file:///C:/a.png"), "file: is a file");
        check(!ClientTrackExporter.isWebAddress("C:\\Users\\a.png"), "a drive path is a file");
        check(!ClientTrackExporter.isWebAddress("song.ogg"), "a name is a file");
        check(ClientTrackExporter.parsePath("https://i.imgur.com/x.png") == null, "a web address never becomes a path");
    }

    /** The characters and colons Windows' Path.of rejects, thrown the way WindowsPathParser throws them. */
    private static final Function<String, Path> WINDOWS = value -> {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean badColon = c == ':' && i != 1;
            if (c < 32 || "<>\"|?*".indexOf(c) >= 0 || badColon) {
                throw new InvalidPathException(value, "Illegal char <" + c + ">", i);
            }
        }
        return Path.of(value.replace('\\', '/'));
    };

    private static void windowsPaths() {
        // What a player pastes; none of it may throw, whatever the platform makes of it.
        String[] pasted = {
                "https://i.imgur.com/x.png", "\"C:\\Users\\me\\song.ogg\"", "C:\\Users\\me\\song.ogg", "a|b.png",
                "what?.png", "*.ogg", "<cover>.png", "C:\\music\\a:b.ogg", "song.ogg:Zone.Identifier", "\"", "''",
                "\"half", "C:", "\u0001", "file:///C:/Users/me/a%20b.png", "file:not a uri%", "", "   "
        };
        for (String value : pasted) {
            String cleaned = ClientTrackExporter.cleanPathInput(value);
            try {
                ClientTrackExporter.parsePath(cleaned, WINDOWS);
                ClientTrackExporter.parsePath(cleaned);
                checks++;
            } catch (RuntimeException exception) {
                throw new AssertionError("parsing a pasted value threw: [" + value + "] " + exception, exception);
            }
        }
        check(ClientTrackExporter.parsePath(ClientTrackExporter.cleanPathInput("\"C:\\Users\\me\\song.ogg\""), WINDOWS) != null,
                "a quoted Windows path works once its quotes are gone");
        check(ClientTrackExporter.parsePath("\"C:\\Users\\me\\song.ogg\"", WINDOWS) == null,
                "the same path with its quotes left on is 'not a file' instead of a crash");
        check(ClientTrackExporter.parsePath("a|b.png", WINDOWS) == null, "a pipe is 'not a file' on Windows");
        check(ClientTrackExporter.parsePath("C:\\music\\a:b.ogg", WINDOWS) == null, "a second colon is 'not a file' on Windows");
        check(ClientTrackExporter.parsePath("cover.png", WINDOWS) != null, "an ordinary name still parses");
        // NUL is refused by every platform's parser, so this one is the real exception on this machine too.
        check(ClientTrackExporter.parsePath("a\u0000b.png") == null, "this machine's own InvalidPathException is caught");
        check(ClientTrackExporter.parsePath("file:not a uri%") == null, "a broken file: URI is 'not a file'");
        Path fromUri = ClientTrackExporter.parsePath("file:///tmp/a%20b.png");
        check(fromUri != null && fromUri.getFileName().toString().equals("a b.png"), "a file: URI from a browser becomes its path");
    }

    private static void requiredPaths() {
        problem(() -> ClientTrackExporter.requirePath("https://example.com/a.ogg"), "music_battle.l10n.editor.path_web_address",
                "a click on a web address says to download it first");
        problem(() -> ClientTrackExporter.requirePath("a\u0000b.ogg"), "music_battle.l10n.editor.path_unusable",
                "a click on an unusable path says the path is unusable");
        try {
            check(ClientTrackExporter.requirePath("song.ogg") != null, "a name passes");
        } catch (Exception exception) {
            throw new AssertionError("a plain name was refused", exception);
        }
    }

    private static final String CHART = """
            {"song": {"song": "Bopeebo", "bpm": 100, "notes": [
              {"mustHitSection": true, "sectionNotes": [[0, 0, 0], [600, 5, 200], [1200, 3]]},
              {"mustHitSection": false, "sectionNotes": [[1800, 1, 0]]}
            ]}}""";

    private static void fnfCharts() throws Exception {
        ClientFnfChartImporter.ImportedChart clean = ClientFnfChartImporter.parse(CHART, "bopeebo.json");
        equal(clean.notes().size(), 4, "a clean chart has all four notes");
        equal(clean.bpm(), 100, "bpm is read");
        equal(clean.title(), "Bopeebo", "title is read");
        BattleNote flipped = clean.notes().get(1);
        check(flipped.owner() == 1 && flipped.lane() == 1 && flipped.tick() == 12 && flipped.durationTicks() == 4,
                "lane 5 in a must-hit section belongs to the other side, as before");

        ClientFnfChartImporter.ImportedChart junk = ClientFnfChartImporter.parse(CHART + "\n  garbage\u0000\u0000", "bopeebo.json");
        equal(junk.notes().size(), 4, "junk after the last brace is cut off, as FNF itself does");
        // A stray brace after the end leaves the document unbalanced even after the cut - FNF refuses it too - so it
        // has to come back as an error line, not as an exception.
        problem(() -> ClientFnfChartImporter.parse(CHART + "\n}}", "bopeebo.json"),
                "music_battle.l10n.editor.chart_unreadable", "a stray closing brace is an unreadable chart");
        ClientFnfChartImporter.ImportedChart trailing = ClientFnfChartImporter.parse(CHART + "\r\n\r\nsaved by chart editor v2", "x.json");
        equal(trailing.notes().size(), 4, "text after the chart is ignored");
        equal(ClientFnfChartImporter.parse("\uFEFF" + CHART, "x.json").notes().size(), 4, "a byte-order mark is ignored");

        String[] broken = {"{\"song\": {\"notes\": [", "not json at all", "[1, 2, 3]", "\"just a string\"", "", "{\"song\": }"};
        for (String text : broken) {
            try {
                ClientFnfChartImporter.parse(text, "broken.json");
                throw new AssertionError("a broken chart imported: " + text);
            } catch (java.io.IOException expected) {
                checks++;
            } catch (RuntimeException exception) {
                throw new AssertionError("a broken chart threw past the editor's catch: [" + text + "] " + exception, exception);
            }
        }
        problem(() -> ClientFnfChartImporter.parse("{\"song\": {\"notes\": [", "broken.json"),
                "music_battle.l10n.editor.chart_unreadable", "unbalanced JSON is reported as an unreadable chart");
        problem(() -> ClientFnfChartImporter.parse("[1, 2]", "broken.json"),
                "music_battle.l10n.editor.chart_unreadable", "an array is not a chart");

        String messy = """
                {"song": {"song": "Mess", "bpm": "fast", "notes": [
                  {"mustHitSection": true, "sectionNotes": [
                    null, [null, 1], [0, null], [100, 2, null], ["300", "1", "50"],
                    [400, -1, "Hey!", "BF", "0.6"], [500, "x"], [true, 2], [1e300, 0], [700, 0, 1e300], [], [800],
                    [900, 3, "not a length"]
                  ]},
                  "not a section", null,
                  {"mustHitSection": "maybe", "sectionNotes": "nope"}
                ]}}""";
        ClientFnfChartImporter.ImportedChart mess = ClientFnfChartImporter.parse(messy, "mess.json");
        equal(mess.bpm(), 120, "a bpm that is not a number falls back to 120");
        List<Integer> ticks = new ArrayList<>();
        for (BattleNote note : mess.notes()) {
            ticks.add(note.tick());
        }
        equal(ticks, List.of(2, 6, 14, 18), "only the real notes survive: 100, \"300\", 700 and 900 ms");
        check(mess.notes().get(1).durationTicks() == 1, "a sustain written as a string is still a sustain");
        check(mess.notes().get(2).durationTicks() < 2_000_000, "an absurd sustain is capped, not overflowed");
        check(mess.notes().get(3).durationTicks() == 0, "a sustain that is not a number is a tap");

        try {
            ClientFnfChartImporter.parse("{\"song\": {\"bpm\": 120}}", "empty.json");
            throw new AssertionError("a chart without notes imported");
        } catch (java.io.IOException expected) {
            checks++;
        }
    }

    private static void noteLimit() throws Exception {
        equal(ClientTrackExporter.MAX_NOTES, 4096, "the editor's limit is 4096");
        Field payloadLimit = Class.forName("ru.gora.musicbattle.network.BattleStartPayload").getDeclaredField("MAX_NOTES");
        payloadLimit.setAccessible(true);
        equal(payloadLimit.getInt(null), ClientTrackExporter.MAX_NOTES, "the editor's limit is what a battle carries");
        Field readerLimit = Class.forName("ru.gora.musicbattle.server.MusicBattleTracks", false,
                EditorImportChecks.class.getClassLoader()).getDeclaredField("MAX_NOTES");
        equal(readerLimit.getInt(null), ClientTrackExporter.MAX_NOTES, "the editor's limit is what the loader reads");
        equal(ClientTrackExporter.notesOverLimit(0), 0, "an empty chart fits");
        equal(ClientTrackExporter.notesOverLimit(4096), 0, "exactly 4096 fits");
        equal(ClientTrackExporter.notesOverLimit(4097), 1, "4097 is one over");
        equal(ClientTrackExporter.notesOverLimit(5000), 904, "5000 is 904 over");

        for (int count : new int[] {4097, 5000}) {
            List<BattleNote> notes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                notes.add(new BattleNote(i % 2, i % 4, i * 2, 0));
            }
            // Refused before the exporter touches the disk, so this runs with no game directory at all.
            problem(() -> ClientTrackExporter.export("too_long", "Too long", "", 120, 1.0F, "", "", false, false, notes, List.of()),
                    "music_battle.l10n.editor.too_many_notes_save", count + " notes are refused at save");
        }
    }

    private static void saving(Path game) throws Exception {
        Path raw = game.resolve("music_battle_raw");
        Files.createDirectories(raw);
        // The exporter only copies the song, so any bytes named .ogg are a song as far as saving is concerned.
        Files.write(raw.resolve("song.ogg"), new byte[] {'O', 'g', 'g', 'S', 0, 1, 2, 3});
        List<BattleNote> notes = new ArrayList<>();
        for (int i = 0; i < ClientTrackExporter.MAX_NOTES; i++) {
            notes.add(new BattleNote(i % 2, i % 4, i * 2, 0));
        }
        Path chart = ClientTrackExporter.export("full", "Full", "", 120, 1.0F, "\"song.ogg\"", "", false, false, notes, List.of());
        check(Files.isRegularFile(chart), "a chart of exactly 4096 notes with a song is saved");
        Path folder = chart.getParent();
        check(Files.isRegularFile(folder.resolve("full.ogg")), "its song is copied next to it, from a quoted name");
        try (var files = Files.list(folder)) {
            List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
            equal(names, List.of("full.json", "full.ogg"), "saving writes the chart and the song, and no sped-up copies");
        }
        check(Files.readString(chart).contains("\"tick\": " + (ClientTrackExporter.MAX_NOTES - 1) * 2),
                "the last note is in the file");

        try {
            ClientTrackExporter.export("half", "Half", "", 120, 1.0F, "song.ogg", "missing.png", false, false, notes, List.of());
            throw new AssertionError("a save with a missing picture succeeded");
        } catch (java.io.IOException expected) {
            checks++;
        }
        check(!Files.exists(folder.resolveSibling("half").resolve("half.ogg")),
                "a missing picture stops the save before the song is copied, so no folder is left without a chart");
        problem(() -> ClientTrackExporter.export("web", "Web", "", 120, 1.0F, "song.ogg", "https://i.imgur.com/x.png",
                false, false, notes, List.of()), "music_battle.l10n.editor.path_web_address", "a web address at save says what to do");
    }

    private static final String OSU_MAP = """
            osu file format v14

            [General]
            AudioFilename: song.mp3
            Mode: 3

            [Metadata]
            Title:Check
            Version:Easy

            [Difficulty]
            CircleSize:4

            [TimingPoints]
            0,500,4,2,0,100,1,0

            [HitObjects]
            64,192,1000,1,0,0:0:0:0:
            192,192,1500,1,0,0:0:0:0:
            320,192,2000,1,0,0:0:0:0:
            """;

    /**
     * A map whose MP3 cannot become OGG still brings its notes. This machine either has no ffmpeg or one that fails on
     * these bytes, and both reach the same branch; which one it was is in {@code ffmpegMissing}.
     */
    private static void osuWithUnconvertibleSong(Path game) throws Exception {
        Path folder = Files.createDirectories(game.resolve("maps"));
        Path map = folder.resolve("check.osu");
        Files.writeString(map, OSU_MAP);
        Files.write(folder.resolve("song.mp3"), "this is not an mp3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var imported = ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartWithAudio(map, -1);
        equal(imported.chart().notes().size(), 3, "the notes are imported although the song could not be converted");
        equal(imported.audioFile(), "", "no song is put in the box");
        var left = imported.audioLeftBehind();
        check(left != null, "the editor is told the song was left behind");
        equal(left.sourceExtension(), ".mp3", "and what it was");
        check(!left.keptFile().isBlank() && Files.isRegularFile(game.resolve("music_battle_raw").resolve(left.keptFile())),
                "the original is kept in music_battle_raw for the Audio button");
        System.out.println("osu! song conversion on this machine: " + (left.ffmpegMissing() ? "no ffmpeg" : "ffmpeg failed: " + left.reason()));
    }

    private static void deleteTree(Path root) throws java.io.IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** The save path must not need ffmpeg again, and the editor must not parse a box with the bare Path.of. */
    private static void sources(Path root) throws Exception {
        Path client = root.resolve("src/main/java/ru/gora/musicbattle/client");
        String exporter = Files.readString(client.resolve("ClientTrackExporter.java"));
        check(!exporter.contains("createSpeedVariant"), "saving no longer builds the 1.5x/2x copies (that needed ffmpeg)");
        String editor = Files.readString(client.resolve("TrackEditorScreen.java"));
        check(!editor.contains("Path.of("), "the editor parses no box with the bare Path.of");
        String fnf = Files.readString(client.resolve("ClientFnfChartImporter.java"));
        check(!fnf.contains("Path.of("), "the FNF importer parses no box with the bare Path.of");
        String osu = Files.readString(client.resolve("ClientOsuManiaImporter.java"));
        check(!osu.contains("Path.of("), "names from inside an osu! map never meet the bare Path.of");
    }

    private interface Action {
        Object run() throws Exception;
    }

    private static void problem(Action action, String key, String label) {
        try {
            action.run();
        } catch (ClientTrackExporter.Problem problem) {
            equal(problem.key(), key, label);
            return;
        } catch (Exception exception) {
            throw new AssertionError(label + ": expected " + key + ", got " + exception, exception);
        }
        throw new AssertionError(label + ": expected " + key + ", nothing was thrown");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
        checks++;
    }

    private static void equal(Object actual, Object expected, String label) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
        checks++;
    }
}
