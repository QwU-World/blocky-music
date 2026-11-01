import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod's words for what the player plays, in both languages, and the editor's status lines.
 *
 * <ul>
 *   <li>English says "beatmap" for a playable map: never "track", "map" or "chart" as a word. Folder names keep their
 *       on-disk spelling (music_battle_tracks, my_tracks, server_tracks, user_tracks): they are joined by underscores, so
 *       they are not words here. "Mapper" and "mapped by" name the person and stay, as on the Workshop site. The old
 *       command name "/musicbattle tracks" is quoted where the guide says it still works.</li>
 *   <li>Russian says «карта» for a beatmap and «песня» for its audio: never «трек» or «чарт», no Latin "chart",
 *       "track" or "map" inside a Russian line, and no «музыка» where the English means the audio file (it says
 *       "audio" and not "music"). Nor «аудио», «аудиофайл» or «звук» for a beatmap's song: in the Workshop's lines,
 *       the editor's, client and server lines ({@link #SONG_SCOPES}) and any line that names a «карта», the song is
 *       «песня» («Песня этой карты удалена», «файл песни»). Sound in general (calibration, the DJ's sound FX, a
 *       speaker) keeps «звук»; the lines in {@link #SOUND_KEPT} say why they stay.</li>
 *   <li>The command is /musicbattle beatmaps (tracks stays as the old name), and the guide says so in both
 *       languages.</li>
 *   <li>No editor status line carries an English level word ("OK:", "WARN:", "ERR:") or "Osu:", in either language
 *       file or in TrackEditorScreen: the editor gives the level as a colour.</li>
 *   <li>The beatmap importers, the exporter and the reader of a battle's downloaded beatmap ({@link #STATUS_SOURCES})
 *       refuse with a translation key (ClientTrackExporter.Problem) that both language files have, never with an
 *       English literal: the editor and the battle show their message as it is, so "No .osu chart found in …" reached
 *       the Russian status line and «карта повреждена: Chart has too many notes» a Russian battle. The description an
 *       import starts with is translated too (no "Imported from … chart" literal).</li>
 * </ul>
 * Fixed lines test the rules themselves, so a rule that stops matching fails too. NOT COVERED: Java literals outside
 * the editor's status marks and the files in {@link #STATUS_SOURCES}; whether a sentence reads well.
 */
public final class WordingChecks {
    private static final Pattern EN_WORD = Pattern.compile("\\b(tracks?|maps?|charts?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RU_WORD = Pattern.compile("(?<![а-яё])(трек|чарт)[а-яё]*|\\b(tracks?|maps?|charts?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STATUS_MARK = Pattern.compile("^\\s*(OK|WARN|ERR)\\s*:|\\bOsu:");
    /** An exception or a description written as an English literal instead of a translation key. */
    private static final Pattern ENGLISH_LITERAL = Pattern.compile(
            "new\\s+(?:IOException|IllegalArgumentException|IllegalStateException)\\(\\s*\"|\"Imported from ");
    /** The translation key a refusal carries: {@code new ClientTrackExporter.Problem("music_battle.…"}. */
    private static final Pattern PROBLEM_KEY = Pattern.compile(
            "new\\s+(?:ClientTrackExporter\\.)?Problem\\(\\s*\"([a-z0-9_.]+)\"");
    /**
     * Client files whose exception messages reach a player as they are: the editor's status line (the importers, the
     * exporter) and a battle's error line (the downloaded beatmap's reader, after «Карта повреждена: »).
     */
    private static final List<String> STATUS_SOURCES = List.of("ClientFnfChartImporter.java", "ClientOsuManiaImporter.java",
            "ClientTrackExporter.java", "ClientTrackChart.java");
    /** «аудио», «аудиофайл», «звук…» as a word: the song called something else. */
    private static final Pattern RU_AUDIO = Pattern.compile("(?<![а-яё])(аудио(?:файл[а-яё]*)?|звук[а-яё]*)(?![а-яё])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Key prefixes whose «аудио» can only mean a beatmap's song. */
    private static final List<String> SONG_SCOPES = List.of("screen.music_battle.workshop.", "music_battle.workshop.",
            "music_battle.l10n.editor.", "music_battle.l10n.server.", "music_battle.l10n.client.");
    private static final Set<String> SOUND_KEPT = Set.of(
            // The editor's button that picks the song file is named «Аудио»; the guide quotes it as «Аудио».
            "music_battle.l10n.editor.button_audio",
            // The content warning: "loud sounds" are sound in general, not a song.
            "music_battle.l10n.client.078");
    /** Text quoted on purpose: the old command name, which the guide names as still working. */
    private static final Set<String> QUOTED = Set.of("/musicbattle tracks");

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        Map<String, String> en = read(lang.resolve("en_us.json"));
        Map<String, String> ru = read(lang.resolve("ru_ru.json"));

        // The rules on fixed lines first.
        expect(englishWords("Share your map with the world!"), "map");
        expect(englishWords("Two-sided charts"), "charts");
        expect(englishWords("Tracks are listed below."), "Tracks");
        expect(englishWords("Mapped by %s · the beatmap folder music_battle_tracks/my_tracks/<id>/"));
        expect(englishWords("/musicbattle beatmaps lists them (/musicbattle tracks still works)."));
        expect(russianWords("В чарте есть ноты."), "чарте");
        expect(russianWords("Загрузи трек."), "трек");
        expect(russianWords("в chart отсутствуют ноты"), "chart");
        expect(russianWords("В папке music_battle_tracks/my_tracks лежит карта; маппер — автор."));
        require(audioSaysMusic("The music must be at least ten seconds long.", "Музыка должна быть не короче."), false,
                "an English line about music may say «музыка»");
        require(audioSaysMusic("Hosted audio", "С музыкой"), true, "«музыка» for the audio file is caught");
        require(audioSaysMusic("Hosted audio", "С песней"), false, "«песня» for the audio file passes");
        require(songCalledAudio("music_battle.workshop.error.audio_removed", "Аудио этой карты удалено."), true,
                "«аудио» for a Workshop beatmap's song is caught");
        require(songCalledAudio("guide.x", "Набор, где игроки приносят свой звук в карту."), true,
                "«звук» in a line about a «карта» is caught");
        require(songCalledAudio("music_battle.l10n.server.audio_missing", "Blocky Music: аудиофайл карты не найден."), true,
                "«аудиофайл» is caught");
        require(songCalledAudio("music_battle.calibration.muted", "Звук выключен! Проверь громкость."), false,
                "sound in general keeps «звук»");
        require(songCalledAudio("music_battle.l10n.guide.text_051", "Выбери их кнопками «Аудио» и «Карта»."), false,
                "the quoted button name «Аудио» passes");
        require(songCalledAudio("music_battle.workshop.error.audio_removed", "Песня этой карты удалена."), false,
                "«песня» passes");
        require(STATUS_MARK.matcher("OK: copied %s items").find(), true, "a status mark is caught");
        require(STATUS_MARK.matcher("ERR:нечего отменять").find(), true, "a status mark without a space is caught");
        require(STATUS_MARK.matcher("Скопировано %s элементов").find(), false, "a plain status line passes");
        require(ENGLISH_LITERAL.matcher("throw new IOException(\"No .osu chart found in \" + name);").find(), true,
                "an English exception literal is caught");
        require(ENGLISH_LITERAL.matcher("new ImportedChart(id, title, \"Imported from FNF chart: \" + file, bpm, notes)").find(),
                true, "an English import description is caught");
        require(ENGLISH_LITERAL.matcher("throw new Problem(\"music_battle.l10n.editor.osz_no_beatmap\", name);").find(), false,
                "a translation key passes");
        require(ENGLISH_LITERAL.matcher("throw new IOException(text(\"music_battle.l10n.client.017\") + id);").find(), false,
                "a translated message passes");
        require(ENGLISH_LITERAL.matcher("throw new IOException(\"Chart has too many notes\");").find(), true,
                "the downloaded beatmap's English refusal is caught");
        Matcher sample = PROBLEM_KEY.matcher("throw new ClientTrackExporter.Problem(\"music_battle.l10n.client.beatmap_bad_note\");");
        require(sample.find() && "music_battle.l10n.client.beatmap_bad_note".equals(sample.group(1)), true,
                "a refusal's translation key is read");

        for (Map.Entry<String, String> entry : en.entrySet()) {
            checks++;
            List<String> words = englishWords(entry.getValue());
            if (!words.isEmpty()) {
                failures.add("en " + entry.getKey() + " says " + words + " for a beatmap: " + entry.getValue());
            }
            checks++;
            if (STATUS_MARK.matcher(entry.getValue()).find()) {
                failures.add("en " + entry.getKey() + " carries a status mark: " + entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : ru.entrySet()) {
            checks++;
            List<String> words = russianWords(entry.getValue());
            if (!words.isEmpty()) {
                failures.add("ru " + entry.getKey() + " says " + words + " for a beatmap: " + entry.getValue());
            }
            checks++;
            if (STATUS_MARK.matcher(entry.getValue()).find()) {
                failures.add("ru " + entry.getKey() + " carries an English status mark: " + entry.getValue());
            }
            checks++;
            if (!SOUND_KEPT.contains(entry.getKey()) && songCalledAudio(entry.getKey(), entry.getValue())) {
                failures.add("ru " + entry.getKey() + " calls the beatmap's song «аудио»/«звук», not «песня»: "
                        + entry.getValue());
            }
            String english = en.get(entry.getKey());
            if (english != null) {
                checks++;
                if (audioSaysMusic(english, entry.getValue())) {
                    failures.add("ru " + entry.getKey() + " says «музыка» for the audio file (\"" + english + "\"): "
                            + entry.getValue());
                }
            }
        }

        String commands = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/command/MusicBattleCommands.java"),
                StandardCharsets.UTF_8);
        checks++;
        if (!commands.contains("Commands.literal(\"beatmaps\")")) {
            failures.add("/musicbattle beatmaps is not registered");
        }
        checks++;
        if (!commands.contains("Commands.literal(\"tracks\")")) {
            failures.add("/musicbattle tracks, the old name, no longer works");
        }
        for (Map.Entry<String, Map<String, String>> file : Map.of("en", en, "ru", ru).entrySet()) {
            checks++;
            String guide = file.getValue().getOrDefault("music_battle.l10n.guide.text_130", "");
            if (!guide.contains("/musicbattle beatmaps")) {
                failures.add(file.getKey() + " guide text_130 does not name /musicbattle beatmaps: " + guide);
            }
        }

        String editor = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEditorScreen.java"),
                StandardCharsets.UTF_8);
        Matcher literal = Pattern.compile("\"(?:OK|WARN|ERR): |\"Osu: |\"\\.\\.\\. \"").matcher(editor);
        while (literal.find()) {
            checks++;
            int line = editor.substring(0, literal.start()).split("\n", -1).length;
            failures.add("TrackEditorScreen.java:" + line + " writes a status mark into the text: " + literal.group());
        }
        for (String name : STATUS_SOURCES) {
            String source = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client").resolve(name),
                    StandardCharsets.UTF_8);
            checks++;
            Matcher english = ENGLISH_LITERAL.matcher(source);
            while (english.find()) {
                int line = source.substring(0, english.start()).split("\n", -1).length;
                failures.add(name + ":" + line + " gives a player an English literal, not a translation key: "
                        + source.substring(english.start(), Math.min(source.length(), source.indexOf('\n', english.start()))).trim());
            }
            Matcher problem = PROBLEM_KEY.matcher(source);
            while (problem.find()) {
                checks++;
                if (!en.containsKey(problem.group(1)) || !ru.containsKey(problem.group(1))) {
                    failures.add(name + " refuses with " + problem.group(1) + ", which "
                            + (en.containsKey(problem.group(1)) ? "ru_ru" : "en_us") + ".json does not have");
                }
            }
        }
        checks++;
        if (!editor.contains("enum StatusLevel")) {
            failures.add("TrackEditorScreen has no StatusLevel: status colours would come from the text again");
        }

        failures.stream().limit(40).forEach(failure -> System.err.println("FAIL: " + failure));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " wording failures");
        }
        System.out.println("Wording PASS: " + checks + " checks over " + en.size() + " English and " + ru.size()
                + " Russian strings: beatmap/«карта», «песня» for the audio file, /musicbattle beatmaps, no status marks");
        System.out.println("NOT COVERED: Java literals outside the editor's status marks and the importers/exporter; how a"
                + " sentence reads");
    }

    static List<String> englishWords(String text) {
        String scrubbed = text;
        for (String quoted : QUOTED) {
            scrubbed = scrubbed.replace(quoted, "");
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = EN_WORD.matcher(scrubbed);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    static List<String> russianWords(String text) {
        String scrubbed = text;
        for (String quoted : QUOTED) {
            scrubbed = scrubbed.replace(quoted, "");
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = RU_WORD.matcher(scrubbed);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** A line about a beatmap (by its key or its «карта») that calls the song «аудио», «аудиофайл» or «звук». */
    static boolean songCalledAudio(String key, String russian) {
        String scrubbed = russian.replace("«Аудио»", "");
        boolean aboutBeatmap = SONG_SCOPES.stream().anyMatch(key::startsWith)
                || Pattern.compile("(?<![а-яё])карт[а-яё]*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        .matcher(scrubbed).find();
        return aboutBeatmap && RU_AUDIO.matcher(scrubbed).find();
    }

    /** The English means the audio file (it says audio, not music) and the Russian calls it «музыка». */
    static boolean audioSaysMusic(String english, String russian) {
        boolean audio = Pattern.compile("\\baudio\\b", Pattern.CASE_INSENSITIVE).matcher(english).find();
        boolean music = Pattern.compile("\\bmusic", Pattern.CASE_INSENSITIVE).matcher(english).find();
        return audio && !music && Pattern.compile("музык", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(russian).find();
    }

    private static void expect(List<String> found, String... words) {
        checks++;
        if (!found.equals(List.of(words))) {
            failures.add("the rules found " + found + ", expected " + List.of(words));
        }
    }

    private static void require(boolean actual, boolean expected, String what) {
        checks++;
        if (actual != expected) {
            failures.add("rule broken: " + what);
        }
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> strings = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject().entrySet()) {
            strings.put(entry.getKey(), entry.getValue().getAsString());
        }
        return strings;
    }
}
