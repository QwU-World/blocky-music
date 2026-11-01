package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * The calibration screen's words fit at every window width from 320 up, in English and in Russian, at every GUI scale:
 * measured the way the game measures them, with Minecraft's own FreeType glyph provider at each oversample tier 1 to 9
 * (the glyphs are hinted at whole pixels of each tier, so a word is not the same width at every scale, and java.awt's
 * advances run 1-3% narrower), and set by the screen's own rules ({@code MusicBattleCalibrationScreen.Fit}).
 *
 * <p>In Russian the tap count and the median shared one line ("Замеры: 12 · медиана -123 мс") and were cut off beside
 * the five dots on any window narrower than about 390 units; the muted warning under the stage was cut to an ellipsis on
 * narrow windows too. Each figure now has its own line and the stage gives up height to the note under it; this pins
 * both with the longest figures the screen can show (24 taps, a median of -500 ms, a corrected tap of -1000 ms).
 *
 * <p>It also places the judgement word that follows a tap (see {@link #judgement}): in its chip under the hit line it
 * stays inside the stage, holds its ink, and never meets a note, at every width and the heights from 240 up.
 */
public final class CalibrationLayoutChecks {
    /** Keys this change adds; read from the language files once the integrator has merged them. */
    private static final Map<String, String[]> PROPOSED = Map.of(
            "music_battle.calibration.taps", new String[]{"Taps: %s", "Замеры: %s"},
            "music_battle.calibration.median", new String[]{"Median: %s ms", "Медиана: %s мс"});
    private static final int FOOTER = 34;
    private static final int STAGE_TOP = 42;
    private static final int STAGE_MIN_HEIGHT = 54;
    /** The stage's frame beside the column, read back from the screen's source (see {@link #frameConstants}). */
    private static final int STAGE_GAP = 28;
    private static final int STAGE_MARGIN = 12;
    /** drawNote's hit line: 20 units above the stage's bottom, 4 rows thick from the one above {@code hitY}. */
    private static final int HIT_ABOVE_BOTTOM = 20;
    /** The heights the judgement word is measured at: the 240-unit windows it ran into the next note on, and up. */
    private static final int[] WORD_HEIGHTS = {240, 241, 250, 260, 270, 300, 360, 480, 540, 720};
    private static final Map<Integer, Integer> LOWEST_NOTE_ROW = new HashMap<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        String screen = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/MusicBattleCalibrationScreen.java"),
                StandardCharsets.UTF_8);
        require(!screen.contains("tr(\"samples\""), "the screen still draws the one-line count and median");
        frameConstants(screen);
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            tier(measurer, lang);
            judgement(measurer, new GlyphInk(fonts, tier), lang);
        }
        System.out.println("Calibration layout PASS: " + checks + " checks, en and ru, every width from 320 to 1280, GUI scales 1-9");
        System.out.println("NOT COVERED: glyphs the Nunito faces lack (the check fails on them), the live screen");
    }

    /** The frame this check rebuilds is the screen's: its constants and drawNote's hit line, read from the source. */
    private static void frameConstants(String screen) {
        for (String constant : new String[]{"STAGE_TOP = " + STAGE_TOP + ";", "STAGE_GAP = " + STAGE_GAP + ";",
                "STAGE_MARGIN = " + STAGE_MARGIN + ";", "STAGE_MIN_HEIGHT = " + STAGE_MIN_HEIGHT + ";", "FOOTER = " + FOOTER + ";"}) {
            require(screen.contains("private static final int " + constant), "the screen's " + constant + " changed; update this check");
        }
        require(screen.contains("int hitY = bottom - " + HIT_ABOVE_BOTTOM + ";")
                        && screen.contains("graphics.fill(x + 6, hitY - 1, right - 6, hitY + 3,")
                        && screen.contains("int noteY = Fit.noteY(elapsed, top, hitY);")
                        && screen.contains("drawFeedback(graphics, tr(feedback), center, hitY + Fit.FEEDBACK_BELOW_LINE, feedbackColor);"),
                "drawNote no longer draws the line, the note and the judgement word where this check measures them");
    }

    /**
     * The judgement word after a tap (PERFECT, EARLY, LATE) used to stand 33 units above the hit line, clamped to the
     * stage's top, in the heaviest face: a note approaching during the 400 ms the word shows passes through every other
     * height of the lane, so the word covered it at every window size, and on a 240-unit window it sat on the next note
     * right under the stage's top. Now the word is in a chip under the hit line, the one part of the lane no note
     * reaches. At every width from 320 to 1280 and the heights from 240 up, in both languages, this places the chip with
     * the screen's own Fit and holds it against the stage, the line, the ink of the word (GlyphInk) and every note
     * position Fit.noteY gives during any tap's feedback, a tap being up to 500 ms either side of a beat.
     */
    private static void judgement(GameTextMeasurer measurer, GlyphInk ink, Path lang) throws Exception {
        boolean oldCovered = false;
        for (int language = 0; language < 2; language++) {
            Strings strings = new Strings(lang.resolve(language == 0 ? "en_us.json" : "ru_ru.json"), language);
            String name = language == 0 ? "en" : "ru";
            List<String> words = List.of(strings.get("music_battle.calibration.hit"), strings.get("music_battle.calibration.early"),
                    strings.get("music_battle.calibration.late"));
            List<String> notes = List.of(strings.get("music_battle.calibration.muted"), strings.get("music_battle.calibration.waiting"),
                    strings.get("music_battle.calibration.tap_hint"));
            for (int width = 320; width <= 1280; width++) {
                int controlWidth = MusicBattleCalibrationScreen.Fit.controlWidth(width);
                float textWidth = MusicBattleCalibrationScreen.Fit.statusTextWidth(width, controlWidth);
                int x = controlWidth + STAGE_GAP;
                int right = width - STAGE_MARGIN;
                int center = (x + right) / 2;
                for (int height : WORD_HEIGHTS) {
                    int foot = MusicBattleCalibrationScreen.Fit.stageFoot(measurer, notes, textWidth, height);
                    int bottom = Math.max(STAGE_TOP + STAGE_MIN_HEIGHT, height - foot);
                    int hitY = bottom - HIT_ABOVE_BOTTOM;
                    int lowestNote = lowestNoteRow(hitY);
                    String where = name + " at " + width + "x" + height + ", GUI scale " + ink.tier;
                    for (String word : words) {
                        MusicBattleCalibrationScreen.Fit.Chip chip = MusicBattleCalibrationScreen.Fit.feedbackChip(measurer, word,
                                center, hitY + MusicBattleCalibrationScreen.Fit.FEEDBACK_BELOW_LINE);
                        Rect box = chip.box();
                        require(box.x() >= x + 2 && box.right() <= right - 2, "the '" + word + "' chip leaves the stage " + where);
                        require(box.y() > hitY + 3 && box.y() > lowestNote + 1,
                                "the '" + word + "' chip reaches the hit line or a note's rows " + where);
                        require(box.bottom() <= bottom - 1, "the '" + word + "' chip runs over the stage's bottom edge " + where);
                        float[] wordInk = GlyphInk.bounds(ink.line(word, MusicBattleCalibrationScreen.Fit.FEEDBACK_STYLE,
                                chip.textX(), chip.textY()));
                        require(wordInk[0] >= box.x() + 2 && wordInk[2] <= box.right() - 2 && wordInk[1] >= box.y() + 0.5F
                                        && wordInk[3] <= box.bottom() - 0.5F,
                                "'" + word + "' does not sit inside its chip " + where + ": " + java.util.Arrays.toString(wordInk) + " in " + box);
                        if (height <= 241 && (width == 320 || width == 427)) {
                            oldCovered |= oldWordCoversANote(measurer, ink, word, center, hitY);
                        }
                    }
                }
            }
        }
        require(oldCovered, "the old placement of the word no longer covers a note on a 240-unit window at GUI scale " + ink.tier
                + ", so this check no longer measures what it was written for");
    }

    /**
     * The lowest row any note reaches while a judgement word is up: a tap up to 500 ms either side of a beat, and every
     * millisecond of the 400 its word shows, through the screen's own Fit.noteY. The note is 8 rows tall about its centre.
     */
    private static int lowestNoteRow(int hitY) {
        return LOWEST_NOTE_ROW.computeIfAbsent(hitY, line -> {
            int lowest = Integer.MIN_VALUE;
            for (int beat = 0; beat <= 2; beat++) {
                double hit = 1200 + beat * 1500.0D;
                for (int error = -500; error <= 500; error += 5) {
                    for (int age = 0; age < 400; age++) {
                        int noteY = MusicBattleCalibrationScreen.Fit.noteY(hit + error + age, STAGE_TOP, line);
                        if (noteY != MusicBattleCalibrationScreen.Fit.NO_NOTE) {
                            require(noteY <= line, "a note's centre passes the hit line");
                            lowest = Math.max(lowest, noteY + MusicBattleCalibrationScreen.Fit.NOTE_HALF - 1);
                        }
                    }
                }
            }
            return lowest;
        });
    }

    /** Whether the old word - the heaviest face, 33 units over the line, clamped to the stage's top - met a note. */
    private static boolean oldWordCoversANote(GameTextMeasurer measurer, GlyphInk ink, String word, int center, int hitY) {
        float y = Math.max(STAGE_TOP + 5, hitY - 33);
        float[] old = GlyphInk.bounds(ink.line(word, TextStyle.DISPLAY, center - measurer.width(word, TextStyle.DISPLAY) * 0.5F, y));
        for (int beat = 0; beat <= 2; beat++) {
            double hit = 1200 + beat * 1500.0D;
            for (int error = -500; error <= 500; error += 5) {
                for (int age = 0; age < 400; age += 2) {
                    int noteY = MusicBattleCalibrationScreen.Fit.noteY(hit + error + age, STAGE_TOP, hitY);
                    if (noteY != MusicBattleCalibrationScreen.Fit.NO_NOTE && old[1] < noteY + 4 && noteY - 4 < old[3]
                            && old[0] < center + 18 && center - 18 < old[2]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    private static void tier(GameTextMeasurer measurer, Path lang) throws Exception {
        for (int language = 0; language < 2; language++) {
            Strings strings = new Strings(lang.resolve(language == 0 ? "en_us.json" : "ru_ru.json"), language);
            String name = language == 0 ? "en" : "ru";
            String taps = strings.format("music_battle.calibration.taps", "24");
            String median = strings.format("music_battle.calibration.median", "-500");
            String corrected = strings.format("music_battle.calibration.corrected", "-1000");
            List<String> titles = List.of(strings.get("music_battle.calibration.audio"), strings.get("music_battle.calibration.input"));
            List<String> values = List.of(strings.format("music_battle.calibration.milliseconds", "+500"),
                    strings.format("music_battle.calibration.milliseconds", "-500"));
            int labelValues = 0;
            List<String> notes = List.of(strings.get("music_battle.calibration.muted"), strings.get("music_battle.calibration.waiting"),
                    strings.get("music_battle.calibration.tap_hint"));
            for (int width = 320; width <= 1280; width++) {
                int controlWidth = MusicBattleCalibrationScreen.Fit.controlWidth(width);
                float inner = MusicBattleCalibrationScreen.Fit.innerWidth(controlWidth);
                String where = name + " at " + width;
                fits(measurer, taps, TextStyle.STRONG, MusicBattleCalibrationScreen.Fit.tapsRoom(inner), "tap count", where);
                fits(measurer, median, TextStyle.CAPTION, inner, "median", where);
                fits(measurer, corrected, TextStyle.CAPTION, inner, "corrected tap", where);
                // The offset's face is decided for the window from the widest offset; every title fits beside it.
                TextStyle valueStyle = MusicBattleCalibrationScreen.Fit.valueStyle(measurer, titles, values, inner);
                float value = MusicBattleCalibrationScreen.Fit.valueWidth(measurer, values, valueStyle);
                labelValues += valueStyle == TextStyle.LABEL ? 1 : 0;
                for (String title : titles) {
                    fits(measurer, title, TextStyle.STRONG, MusicBattleCalibrationScreen.Fit.headingRoom(inner, value),
                            "card heading", where);
                }
                require(width < 400 || valueStyle == TextStyle.STRONG, "the offsets shrink on a window that has room, " + where);
                float textWidth = MusicBattleCalibrationScreen.Fit.statusTextWidth(width, controlWidth);
                for (int height : new int[]{240, 270, 300, 360, 540, 720}) {
                    int foot = MusicBattleCalibrationScreen.Fit.stageFoot(measurer, notes, textWidth, height);
                    int bottom = Math.max(STAGE_TOP + STAGE_MIN_HEIGHT, height - foot);
                    require(bottom - STAGE_TOP >= STAGE_MIN_HEIGHT, "the stage fell under its minimum height " + where + "x" + height);
                    float top = bottom + MusicBattleCalibrationScreen.Fit.STATUS_BELOW_STAGE;
                    float room = height - MusicBattleCalibrationScreen.Fit.STATUS_ABOVE_FOOTER - top;
                    for (String note : notes) {
                        MusicBattleCalibrationScreen.Fit.Note set = MusicBattleCalibrationScreen.Fit.status(measurer, note, textWidth, room);
                        require(set.lines().equals(TextFit.wrap(measurer, note, set.style(), textWidth)),
                                "the note under the stage is cut " + where + "x" + height + ": " + set.lines());
                        require(set.height() <= room + 0.01F, "the note under the stage overflows its room " + where + "x" + height);
                        for (String line : set.lines()) {
                            require(measurer.width(line, set.style()) <= textWidth + 0.01F, "a note line is too wide " + where);
                        }
                    }
                }
            }
            System.out.println(name + " at GUI scale " + measurer.tier + ": the offsets use the smaller label face on "
                    + labelValues + " of 961 widths");
            if (language == 1) {
                // What the screen drew before: the count and the median on one line beside the dots, in the old column
                // (55% of the window less 20). Measured here, the width under which that line was cut at this scale.
                String oldLine = "Замеры: 12 · медиана -123 мс";
                int cutBelow = 0;
                for (int width = 320; width <= 1280; width++) {
                    float room = MusicBattleCalibrationScreen.Fit.tapsRoom(Math.min(320, width * 55 / 100 - 20) - 12);
                    if (measurer.width(oldLine, TextStyle.STRONG) > room) {
                        cutBelow = width + 1;
                    }
                }
                require(cutBelow > 320, "the old one-line figure fits even at 320, so this check no longer measures what it was written for");
                System.out.println("ru at GUI scale " + measurer.tier + ": the old one-line figure was cut below " + cutBelow + " units");
            }
        }
    }

    private static void fits(TextMeasurer measurer, String text, TextStyle style, float room, String what, String where) {
        require(measurer.width(text, style) <= room + 0.01F, "the " + what + " '" + text + "' is cut " + where + ": "
                + measurer.width(text, style) + " > " + room);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** One language file with this change's new keys added where the file does not have them yet. */
    private static final class Strings {
        private final Map<String, String> values = new HashMap<>();

        Strings(Path file, int language) throws Exception {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getAsString());
            }
            for (Map.Entry<String, String[]> entry : PROPOSED.entrySet()) {
                values.putIfAbsent(entry.getKey(), entry.getValue()[language]);
            }
        }

        String get(String key) {
            String value = values.get(key);
            if (value == null) {
                throw new AssertionError("missing language key " + key);
            }
            return value;
        }

        /** Minecraft's %s substitution, with %% for a literal percent sign. */
        String format(String key, String argument) {
            return get(key).replace("%%", "\u0000").replace("%s", argument).replace("\u0000", "%");
        }
    }
}
