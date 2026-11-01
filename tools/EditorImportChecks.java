import java.lang.reflect.Field;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import ru.gora.musicbattle.client.ClientFnfChartImporter;
import ru.gora.musicbattle.client.ClientTrackChart;
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
        Path base = Files.createTempDirectory("blocky-editor-import-");
        // A throwaway game folder, so the exporter and the importer write where nothing of the player's lives. It sits
        // where a Mac keeps it, below a home folder: both have a space in their names, as players' folders often do.
        Path game = Files.createDirectories(base.resolve("John Smith/Library/Application Support/minecraft"));
        try {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
            saving(game);
            pathsInMessages(game);
            osuWithUnconvertibleSong(game);
            osuSongsByContent(game);
            osuSurroundSong(root, game);
            downloadedBeatmaps(game);
        } finally {
            deleteTree(base);
        }
        sources(root);
        System.out.println("Editor import checks passed: " + checks);
        System.out.println("NOT COVERED: the drawn editor and the exit dialog; those need Minecraft. Converting real songs is"
                + " the native-audio check's");
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

        // Every refusal reaches the status line as a key, so it is in the player's language and says "beatmap".
        problem(() -> ClientFnfChartImporter.parse("{\"song\": {\"bpm\": 120}}", "empty.json"),
                "music_battle.l10n.editor.fnf_no_notes", "a chart without notes is refused in the player's language");
        problem(() -> ClientFnfChartImporter.importChart(""), "music_battle.l10n.editor.chart_path_empty",
                "an empty FNF path is a translated line");
        check(!clean.description().startsWith("Imported from FNF chart"),
                "an FNF import's description is the translated imported_description, not English: " + clean.description());
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

    /**
     * Status lines and error messages fold an absolute path to what the player may see (inside the game folder: the part
     * below it; outside: the file name), and leave a path that is already relative alone. The relative case is the one a
     * player saw broken: "File not found: music_battle_tracks/mp4_opus.ogg" came out as "music_battle_tracksmp4_opus.ogg".
     */
    private static void pathsInMessages(Path game) {
        Function<String, String> safe = ru.gora.musicbattle.server.MusicBattleTracks::safeText;
        equal(safe.apply("File not found: music_battle_tracks/mp4_opus.ogg"), "File not found: music_battle_tracks/mp4_opus.ogg",
                "a relative path keeps its folder and its slash");
        equal(safe.apply("music_battle_tracks/raw/song.ogg"), "music_battle_tracks/raw/song.ogg", "a relative path at the start stays whole");
        equal(safe.apply("songs\\mix/x.ogg is missing"), "songs\\mix/x.ogg is missing", "a slash after a backslash folder is inside the path");
        equal(safe.apply("Open https://example.com/a/b.ogg"), "Open https://example.com/a/b.ogg", "a web address is not a path");
        equal(safe.apply("File not found: " + game.resolve("music_battle_tracks/x.ogg")), "File not found: music_battle_tracks/x.ogg",
                "an absolute path inside the game folder shows the part below it");
        equal(safe.apply("ERR: /Users/someone/Music/secret/a.ogg"), "ERR: a.ogg", "an absolute path outside shows its file name only");
        equal(safe.apply("/Users/someone/a.ogg"), "a.ogg", "an absolute path that starts the text is folded");
        equal(safe.apply("cannot read (/home/me/x.flac)"), "cannot read (x.flac)", "after a bracket");
        equal(safe.apply("path=/home/me/y.wav"), "path=y.wav", "after =");
        equal(safe.apply("'/home/me/z.mp3' and /home/me/w.mp3"), "'z.mp3' and w.mp3", "after a quote, and every path in the text");
        equal(safe.apply("a/b and c/d"), "a/b and c/d", "slashes inside words are never paths");
        // Right after ':' or inside a file: URI a path used to stay whole once the '/' after a word stopped starting one.
        equal(safe.apply("Failed:/Users/someone/x.ogg"), "Failed:x.ogg", "after a colon");
        equal(safe.apply("ERR:C:/Users/someone/y.ogg"), "ERR:y.ogg", "a drive path after a colon");
        equal(safe.apply("file:///Users/someone/a.ogg"), "a.ogg", "a file: URI is folded whole");
        equal(safe.apply("Cannot open file:///C:/Users/someone/b.ogg now"), "Cannot open b.ogg now", "a Windows file: URI");
        equal(safe.apply("Failed:file:/Users/someone/c.ogg"), "Failed:c.ogg", "a one-slash file: URI after a colon");
        equal(safe.apply("Bad: file://" + game.resolve("music_battle_tracks/x.ogg")), "Bad: music_battle_tracks/x.ogg",
                "a file: URI inside the game folder shows the part below it");
        equal(safe.apply("see https://qwu.world/mods/a.ogg or minecraft:textures/b.png"),
                "see https://qwu.world/mods/a.ogg or minecraft:textures/b.png", "a web address and a resource name stay whole");
        equal(safe.apply("music_battle_tracks/x.ogg:Zone.Identifier"), "music_battle_tracks/x.ogg:Zone.Identifier",
                "a colon inside a relative name starts nothing");
        equal(safe.apply("ERR:C:\\Users\\someone\\y.ogg"), "ERR:y.ogg", "a Windows path with backslashes, read anywhere");
        spacesInFolders(game, safe);
    }

    /**
     * The general rule stops a path at a space, so "/Users/bob/Library/Application Support/minecraft/a.ogg" used to show
     * "Application Support/minecraft/a.ogg", and a home folder like "/Users/John Smith" showed the player's name. The
     * game folder (here below a home folder, both with a space) and every folder above it are folded by their text.
     */
    private static void spacesInFolders(Path game, Function<String, String> safe) {
        Path home = game.getParent().getParent().getParent();
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            check(game.toString().contains(" ") && home.getFileName().toString().equals("John Smith"),
                    "the check's game folder and home folder have spaces");
            equal(safe.apply(game.resolve("music_battle_raw/song.ogg") + ": Operation not permitted"),
                    "music_battle_raw/song.ogg: Operation not permitted", "a game folder with a space shows only what is below it");
            equal(safe.apply("Failed:" + game.resolve("a.ogg")), "Failed:a.ogg", "also right after ':'");
            equal(safe.apply("'" + game.resolve("my songs/b c.ogg") + "' is locked"), "'my songs/b c.ogg' is locked",
                    "names with spaces below the game folder stay whole");
            equal(safe.apply(game.resolve("x.ogg") + " -> " + game.resolve("y.ogg") + ": exists"), "x.ogg -> y.ogg: exists",
                    "both paths of a move");
            equal(safe.apply("ERR: " + game.toString().replace('/', '\\') + "\\music_battle_raw\\sub\\a.ogg: denied"),
                    "ERR: music_battle_raw/sub/a.ogg: denied", "with backslashes, as Windows writes it, shown with '/'");
            equal(safe.apply(game.toUri().toString() + "music_battle_tracks/d.ogg"), "music_battle_tracks/d.ogg",
                    "its file: address, with %20 for the space");
            equal(safe.apply("FILE:" + game.toString().replace(" ", "%20") + "/e.ogg"), "e.ogg", "in any case, with one slash");
            equal(safe.apply(home.resolve("Desktop/song.ogg") + ": Permission denied"), "song.ogg: Permission denied",
                    "a home folder with a space never shows the player's name");
            equal(safe.apply("cannot read (" + home.resolve("Music/f.flac") + ")"), "cannot read (f.flac)", "after a bracket");
            equal(safe.apply(game.getParent().resolve("Other/g.ogg") + " is missing"), "g.ogg is missing",
                    "a path next to the game folder, below a folder with a space, shows only the file name");
            // Where a name with a space ends is a guess: the last folder's own name may show, never the ones above it.
            equal(safe.apply(home.resolve("My Music") + ": denied"), "My Music: denied",
                    "a folder below home shows its own name and nothing above it");
            String other = safe.apply(home + "y/h.ogg");
            check(!other.startsWith("y") && !other.contains(home.getParent().toString()),
                    "another user's longer name is not cut at this home folder's name: " + other);
            equal(safe.apply("see https://example.com" + home.resolve("w.ogg")), "see https://example.com" + home.resolve("w.ogg"),
                    "a web address that holds the same text is left alone");
            // A game folder kept outside home (another drive, a launcher's folder): home is folded on its own.
            Path elsewhere = home.resolveSibling("Jane Doe");
            System.setProperty("user.home", elsewhere.toString());
            equal(safe.apply(elsewhere.resolve("Downloads/k.ogg") + ": denied"), "k.ogg: denied",
                    "a home folder that does not hold the game folder hides its name too");
            System.setProperty("user.home", home.toString());
            try {
                Path real = game.toRealPath();
                if (!real.equals(game)) {
                    equal(safe.apply(real.resolve("r.ogg") + ": denied"), "r.ogg: denied", "the game folder's real path too");
                }
            } catch (java.io.IOException unexpected) {
                throw new AssertionError(unexpected);
            }
        } finally {
            if (savedHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", savedHome);
            }
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

        // After a save the song box named the copy by its absolute path, every folder above the game included.
        String shown = ClientTrackExporter.boxValueOf(folder.resolve("full.ogg"));
        equal(shown, "music_battle_tracks/my_tracks/full/full.ogg", "the song box shows the saved song below the game folder");
        check(!shown.contains(game.toString()) && !Path.of(shown).isAbsolute(), "and nothing above it");
        Path again = ClientTrackExporter.export("full", "Full", "", 120, 1.0F, shown, "", false, false, notes, List.of());
        check(Files.isRegularFile(again.resolveSibling("full.ogg")), "saving again with that box finds the song");
        Path copy = ClientTrackExporter.export("copy", "Copy", "", 120, 1.0F, shown, "", false, false, notes, List.of());
        check(Files.isRegularFile(copy.resolveSibling("copy.ogg")), "and so does a save under a new ID, which copies it");
        check(ru.gora.musicbattle.server.MusicBattleTracks.gameFolderFile(Path.of("../outside.ogg")) == null,
                "a box path never leaves the game folder");
        ClientTrackExporter.deleteTrack("copy");

        problem(() -> ClientTrackExporter.export("half", "Half", "", 120, 1.0F, "song.ogg", "missing.png", false, false, notes,
                List.of()), "music_battle.l10n.editor.asset_not_found", "a save with a missing picture names the file");
        check(!Files.exists(folder.resolveSibling("half").resolve("half.ogg")),
                "a missing picture stops the save before the song is copied, so no folder is left without a chart");
        problem(() -> ClientTrackExporter.export("web", "Web", "", 120, 1.0F, "song.ogg", "https://i.imgur.com/x.png",
                false, false, notes, List.of()), "music_battle.l10n.editor.path_web_address", "a web address at save says what to do");
        Files.write(raw.resolve("song.mp3"), new byte[] {1, 2, 3});
        Files.write(raw.resolve("cover.txt"), new byte[] {1, 2, 3});
        problem(() -> ClientTrackExporter.export("mp3", "Mp3", "", 120, 1.0F, "song.mp3", "", false, false, notes, List.of()),
                "music_battle.l10n.editor.asset_not_ogg", "a song that is not .ogg at save is a translated line");
        problem(() -> ClientTrackExporter.export("txt", "Txt", "", 120, 1.0F, "song.ogg", "cover.txt", false, false, notes,
                List.of()), "music_battle.l10n.editor.asset_not_image", "a cover that is not an image is a translated line");
        problem(() -> ClientTrackExporter.export("", "No id", "", 120, 1.0F, "song.ogg", "", false, false, notes, List.of()),
                "music_battle.l10n.editor.beatmap_id_empty", "a beatmap with no ID is refused in the player's language");
        problem(() -> { ClientTrackExporter.deleteTrack(""); return null; }, "music_battle.l10n.editor.beatmap_id_empty",
                "deleting with no ID is refused in the player's language");
        problem(() -> ClientFnfChartImporter.importChart(raw.resolve("missing.json").toString()),
                "music_battle.l10n.editor.chart_not_found", "a missing FNF file is a translated line");
        Files.write(raw.resolve("notes.txt"), new byte[] {1});
        problem(() -> ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(raw.resolve("notes.txt"), -1),
                "music_battle.l10n.editor.import_unsupported_type", "a file the importer does not take is a translated line");
        Path emptyOsz = raw.resolve("empty.osz");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(emptyOsz))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            zip.write(new byte[] {'h', 'i'});
            zip.closeEntry();
        }
        problem(() -> ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(emptyOsz, -1),
                "music_battle.l10n.editor.osz_no_beatmap", "an .osz with no beatmap in it is a translated line");
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
     * A map whose song cannot become OGG still brings its notes. These bytes are no audio at all, so the mod's own
     * converter refuses them as a format it does not read, and the original stays for the player.
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
        check(left.unsupportedFormat(), "and that it was not audio the mod reads, rather than a failed conversion");
        check(left.reason().isEmpty(), "which has no failure to report");
    }

    /**
     * The importer decides by a song's bytes, never by its name. An MP3 that a map calls .ogg used to be copied as .ogg
     * unconverted, so the beatmap could never play it, and its conversion would have collided with it; an Opus song
     * called .ogg is converted like the MP3, and a WMA song called .ogg must not sit in the folder as a song the editor
     * would offer. Nothing is converted here (that is the native-audio check's); this is the decision and the names.
     */
    private static void osuSongsByContent(Path game) throws Exception {
        Path folder = Files.createDirectories(game.resolve("maps-by-content"));
        Path raw = game.resolve("music_battle_raw");
        // An MPEG frame header twice, a frame apart: MP3 by content, whatever the name says.
        byte[] frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) 0x90;
        frame[3] = (byte) 0x64;
        byte[] mp3 = new byte[417 * 8];
        for (int i = 0; i < 8; i++) {
            System.arraycopy(frame, 0, mp3, i * 417, 417);
        }
        Files.writeString(folder.resolve("mp3.osu"), OSU_MAP.replace("song.mp3", "named.ogg"));
        Files.write(folder.resolve("named.ogg"), mp3);
        var named = ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(folder.resolve("mp3.osu"), -1);
        check(named.pendingSong() != null, "an MP3 named .ogg is taken for the MP3 it is and left to convert");
        if (named.pendingSong() != null) {
            var pending = named.pendingSong();
            equal(pending.sourceExtension(), ".mp3", "the editor calls it by its content");
            check(!pending.original().equals(pending.target()), "its bytes never wait where its OGG goes");
            check(!pending.original().getFileName().toString().endsWith(".ogg"),
                    "nor under any name the Audio button would offer: " + pending.original().getFileName());
            check(pending.keptName().endsWith(".mp3"), "should it fail, it is kept as .mp3: " + pending.keptName());
            check(!Files.exists(pending.target()), "and no .ogg is written before it is converted");
        }
        equal(named.audioFile(), "", "the box stays empty until the conversion is done");

        byte[] opus = new byte[47 + 19];
        System.arraycopy("OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, opus, 0, 4);
        opus[5] = 2;
        opus[26] = 1;
        opus[27] = 19;
        System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, opus, 28, 8);
        Files.writeString(folder.resolve("opus.osu"), OSU_MAP.replace("song.mp3", "voice.ogg"));
        Files.write(folder.resolve("voice.ogg"), opus);
        var voice = ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(folder.resolve("opus.osu"), -1);
        check(voice.pendingSong() != null && voice.audioLeftBehind() == null,
                "an Opus song named .ogg is taken for the Opus it is and left to convert");
        if (voice.pendingSong() != null) {
            equal(voice.pendingSong().sourceExtension(), ".opus", "the editor calls it by its content");
            check(voice.pendingSong().keptName().endsWith(".opus"), "should it fail, it is kept as .opus: "
                    + voice.pendingSong().keptName());
        }
        try (var files = Files.list(raw)) {
            check(files.noneMatch(path -> path.getFileName().toString().startsWith("check_voice")
                    && path.getFileName().toString().endsWith(".ogg")), "no .ogg is left for the Opus song");
        }

        // The ASF header's GUID: a WMA song, which the mod does not read, called .ogg.
        byte[] wma = new byte[64];
        byte[] guid = {0x30, 0x26, (byte) 0xB2, 0x75, (byte) 0x8E, 0x66, (byte) 0xCF, 0x11, (byte) 0xA6, (byte) 0xD9, 0x00,
                (byte) 0xAA, 0x00, 0x62, (byte) 0xCE, 0x6C};
        System.arraycopy(guid, 0, wma, 0, guid.length);
        Files.writeString(folder.resolve("wma.osu"), OSU_MAP.replace("song.mp3", "music.ogg"));
        Files.write(folder.resolve("music.ogg"), wma);
        var music = ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(folder.resolve("wma.osu"), -1);
        var left = music.audioLeftBehind();
        check(music.pendingSong() == null && left != null && left.unsupportedFormat(),
                "a WMA song named .ogg is a format the mod does not read, said at the import");
        check(left != null && left.keptFile().endsWith(".wma") && Files.isRegularFile(raw.resolve(left.keptFile())),
                "and is kept as .wma, so the message names a file that says what it is: " + (left == null ? "" : left.keptFile()));
        try (var files = Files.list(raw)) {
            check(files.noneMatch(path -> path.getFileName().toString().startsWith("check_music")
                    && path.getFileName().toString().endsWith(".ogg")), "no .ogg is left for the WMA song");
        }
    }

    /**
     * A 5.1 Ogg Vorbis song is Ogg Vorbis, but Minecraft's decoder refuses it, so the beatmap stayed silent at 1x (and
     * played at x1.5 and x2 only, from copies folded to stereo). The importer now converts it like an MP3, keeping a
     * stereo one as the OGG it is.
     */
    private static void osuSurroundSong(Path root, Path game) throws Exception {
        Path folder = Files.createDirectories(game.resolve("maps-surround"));
        Path support = root.resolve("tools/vorbis-decoder-support");
        Files.writeString(folder.resolve("surround.osu"), OSU_MAP.replace("song.mp3", "surround.ogg"));
        Files.copy(support.resolve("lv-6ch-48k.ogg"), folder.resolve("surround.ogg"));
        var surround = ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(folder.resolve("surround.osu"), -1);
        check(surround.pendingSong() != null, "a 5.1 Ogg song is left to convert, not copied as it is");
        if (surround.pendingSong() != null) {
            var pending = surround.pendingSong();
            check(!pending.original().getFileName().toString().endsWith(".ogg"),
                    "its bytes wait under a name the Audio button never offers: " + pending.original().getFileName());
            check(pending.keptName().endsWith(".oga"), "should it fail, it is kept as .oga: " + pending.keptName());
            check(!Files.exists(pending.target()), "and no .ogg is written before it is converted");
        }
        Files.writeString(folder.resolve("stereo.osu"), OSU_MAP.replace("song.mp3", "stereo.ogg"));
        Files.copy(support.resolve("lv-stereo-44k-transients.ogg"), folder.resolve("stereo.ogg"));
        var stereo = ru.gora.musicbattle.client.ClientOsuManiaImporter.importChartDeferringSong(folder.resolve("stereo.osu"), -1);
        check(stereo.pendingSong() == null && stereo.audioFile().endsWith(".ogg"),
                "a stereo Ogg song is copied as the OGG it is: " + stereo.audioFile());
    }

    /**
     * What a battle's downloaded beatmap is refused for reaches the server's log and a failing spectator's own chat after
     * «карта повреждена: » (client.006), so each refusal is a translation key; an English "Chart has too many notes" used
     * to follow the Russian prefix.
     */
    private static void downloadedBeatmaps(Path game) throws Exception {
        Path file = game.resolve("downloaded-beatmap.json");
        String prefix = "music_battle.l10n.client.beatmap_";
        String[][] cases = {
                {"{\"id\":\"other\",\"notes\":[]}", "mismatch", "a file of another beatmap"},
                {"{\"id\":\"w5\"}", "no_notes", "a beatmap without notes"},
                {"{\"id\":\"w5\",\"notes\":5}", "no_notes", "notes that are not a list"},
                {"{\"notes\":[]}", "mismatch", "no id at all"},
                {"{\"id\":\"w5\",\"notes\":[" + "1,".repeat(8192) + "1]}", "too_many_notes", "8193 notes"},
                {"{\"id\":\"w5\",\"notes\":[1]}", "bad_note", "a note that is not an object"},
                {"{\"id\":\"w5\",\"notes\":[{\"owner\":0,\"lane\":9,\"tick\":0}]}", "note_out_of_range", "lane 9"},
                {"{\"id\":\"w5\",\"notes\":[{\"owner\":0,\"lane\":0}]}", "missing_field", "a note without a tick"},
                {"{\"id\":\"w5\",\"notes\":[],\"effects\":[],\"fxRowsPerLayer\":5}", "fx_grid", "an FX grid of 5 rows"},
                {"{\"id\":\"w5\",\"notes\":[],\"effects\":[" + "1,".repeat(512) + "1]}", "too_many_effects", "513 effects"},
                {"{\"id\":\"w5\",\"notes\":[],\"effects\":[1]}", "bad_effect", "an effect that is not an object"},
                {"{\"id\":\"w5\",\"notes\":[],\"effects\":[{\"tick\":-5}]}", "effect_out_of_range", "an effect at tick -5"},
                {"{\"id\":\"w5\",\"notes\":[],\"effects\":[{\"durationTicks\":2}]}", "missing_field", "an effect without a tick"},
                {"not a beatmap {", "unreadable", "a file that is not JSON"},
                {"[]", "unreadable", "a JSON list instead of a beatmap"},
        };
        for (String[] beatmap : cases) {
            Files.writeString(file, beatmap[0]);
            problem(() -> ClientTrackChart.read(file, "w5"), prefix + beatmap[1], "a downloaded beatmap with " + beatmap[2]);
        }
        Files.deleteIfExists(file);
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

    /** The save path must not build speed copies again, and the editor must not parse a box with the bare Path.of. */
    private static void sources(Path root) throws Exception {
        Path client = root.resolve("src/main/java/ru/gora/musicbattle/client");
        String exporter = Files.readString(client.resolve("ClientTrackExporter.java"));
        check(!exporter.contains("createSpeedVariant"), "saving does not build the 1.5x/2x copies (the server does)");
        String editor = Files.readString(client.resolve("TrackEditorScreen.java"));
        check(!editor.contains("Path.of("), "the editor parses no box with the bare Path.of");
        int conversion = editor.indexOf("private static Path conversionOf(");
        check(conversion >= 0 && editor.indexOf("NativeAudio.playsAsIs(file)", conversion) > conversion
                && editor.indexOf("NativeAudio.playsAsIs(file)", conversion) < editor.indexOf("\n    }\n", conversion),
                "the Audio button converts an Ogg the game does not play as it is (5.1), not only what is not Ogg");
        check(editor.contains("\"music_battle.l10n.editor.audio_unsupported_named\", name, formatName"),
                "a file the mod does not read is told what it is (a WMA song named .ogg is \"WMA/WMV\")");
        int save = editor.indexOf("private void saveTrack()");
        check(save >= 0 && editor.indexOf("draftAudio = ClientTrackExporter.boxValueOf(", save) > save
                        && editor.indexOf("draftPreview = ClientTrackExporter.boxValueOf(", save) > save,
                "after a save the editor's song and picture boxes show the saved files below the game folder");
        int resolve = editor.indexOf("private Path resolveRawOrAbsolute(String typed)");
        check(resolve >= 0 && editor.indexOf("MusicBattleTracks.gameFolderFile(path)", resolve) > resolve
                        && editor.indexOf("MusicBattleTracks.gameFolderFile(path)", resolve)
                        < editor.indexOf("MusicBattleTracks.tracksDirectory().resolve(path)", resolve),
                "and the editor's Play and preview find them there again");
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
