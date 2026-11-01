package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import ru.gora.musicbattle.client.TrackEffectEditScreen.InspectorLayout;
import ru.gora.musicbattle.client.TrackEffectEditScreen.InspectorLayout.Cell;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * The DJ preset browser and the FX editor are on the toolkit, in English and in Russian, at every window from 320x240
 * up and every GUI scale, with Minecraft's own glyph metrics (GameTextMeasurer).
 *
 * <ul>
 *   <li>The preset browser: no bitmap-font text and no flat panel of the first look; its heading, its folder note and
 *       its four buttons' every word (Save also reads "Overwrite?", Delete "Delete?") fit whole, the buttons stay one row
 *       inside the panel and keep equal widths wherever every word fits an equal share; an answer on the status line
 *       keeps its sentence whole and shortens only the preset's name inside it; the name field, the list and the
 *       buttons keep the places they always had, and the panel stays inside the window.</li>
 *   <li>The FX editor: none of the first look's colours, no bitmap-font text, no scissor of its own (UiClip, which
 *       flushes the queued text first), no check-box symbols Nunito lacks, the toolkit's slider; the wash over the world
 *       behind it stays the light one it always was (an effect is judged against the world), in the page colour; and
 *       its panel stays inside the window from 320x240 up (a floor of 360 units pushed it off a 320-unit window).</li>
 *   <li>The FX editor's rows (TrackEffectEditScreen.InspectorLayout), for every effect, with and without an effect on
 *       the timeline, under the editor's host and the DJ's two, in English and Russian, at every inner width the panel
 *       can have on a window from 320x240 up and every GUI scale: every label whole over its cell in at most two lines,
 *       no word broken; every word each button can show whole in its line's face, one face to a line; a slider, a colour
 *       button and the text field never under their floor; every cell inside the panel, in reading order; a row broken
 *       into more lines only when it does not fit one; and the real screen sets its widgets, labels and faces where the
 *       layout put them. At 427x240 in Russian the old equal cells cut 90 labels and switches such as «Плавный
 *       въезд/выезд».</li>
 *   <li>Every language key the FX editor names is in both language files, read as they are: a key they lack would be
 *       measured here, and shown in the game, as the bare key.</li>
 * </ul>
 * NOT COVERED: how they look in the game (the visual session); a colour button's code, which dj-fx-leftovers measures
 * (the layout here only keeps its cell at the floor that check proves wide enough).
 */
public final class DjDialogsChecks {
    private static final int[] HEIGHTS = {240, 270, 300, 360, 480, 540, 720, 1080};
    private static final List<String> FAILURES = new ArrayList<>();
    /** The first look's colours that the FX editor and its pickers used to draw with. */
    private static final List<String> OLD_FX_LITERALS = List.of("0x30070A10", "0xFF121C25", "0xFF92C5D8", "0xFFFF8798",
            "0xFFE3A6FF", "0xFF263847", "0xFF081018", "0xFA111B25", "0xFF304658", "0xFF263947", "0xFF182631", "0xFFFFD7FF",
            "0xFF0B1218", "0xFF3C6B7C", "0xFF8AA0AC", "0xFF121A20", "0xFF2A3A44", "0xFF090C12");
    private static final String LONG_NAME = "Вечеринка у бассейна, версия для очень длинного вечера с друзьями";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        presetSource(root);
        fxSource(root);
        fxPanelFitsTheWindow();
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (String code : List.of("en_us", "ru_ru")) {
                Map<String, String> strings = read(lang.resolve(code + ".json"));
                presets(measurer, strings, code + " at GUI scale " + tier);
                pickers(measurer, strings, code + " at GUI scale " + tier);
            }
        }
        GameTextMeasurer[] tiers = new GameTextMeasurer[TextStyle.MAX_TIER + 1];
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            tiers[tier] = new GameTextMeasurer(fonts, tier);
        }
        for (String code : List.of("en_us", "ru_ru")) {
            Map<String, String> strings = read(lang.resolve(code + ".json"));
            editorKeys(root, strings, code);
            inject(strings);
            for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
                inspectorRows(tiers[tier], strings, code + " at GUI scale " + tier);
            }
            inspectorScreens(tiers, strings, code);
        }
        System.out.println("DJ dialogs checks: " + checks);
        FAILURES.stream().limit(40).forEach(System.err::println);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("DJ dialogs PASS: the preset browser and the FX editor with every row of every effect, en and ru, "
                + "320x240 to 1920x1080, GUI scales 1-9");
        System.out.println("NOT COVERED: the dialogs in the game; a colour button's code (dj-fx-leftovers measures it)");
    }

    // ---------------------------------------------------------------- the preset browser

    private static void presets(GameTextMeasurer measurer, Map<String, String> strings, String name) throws Exception {
        DjPresetBrowserScreen screen = allocate(DjPresetBrowserScreen.class);
        String p = "screen.music_battle.dj_presets.";
        List<List<String>> labels = List.of(List.of(value(strings, p + "save"), value(strings, p + "overwrite_confirm")),
                List.of(value(strings, p + "load")), List.of(value(strings, p + "delete"), value(strings, p + "delete_confirm")),
                List.of(value(strings, p + "back")));
        int equal = 0;
        int windows = 0;
        for (int width = 320; width <= 1920; width += width < 700 ? 1 : 9) {
            for (int height : HEIGHTS) {
                windows++;
                String where = name + " " + width + "x" + height;
                screen.width = width;
                screen.height = height;
                int left = call(screen, "panelLeft");
                int top = call(screen, "panelTop");
                int panelWidth = call(screen, "panelWidth");
                int panelHeight = call(screen, "panelHeight");
                int content = panelWidth - 28;
                require(left >= 8 && top >= 8 && left + panelWidth <= width - 8 && top + panelHeight <= height - 8,
                        where + ": the panel stays inside the window");
                for (String title : List.of("equalizer_title", "audio_title", "effects_title")) {
                    fits(measurer, value(strings, p + title), TextStyle.TITLE, content, where + " title " + title);
                }
                fits(measurer, value(strings, p + "folder_hint"), TextStyle.CAPTION, content, where + " folder note");
                // The heading and the note sit above the name field, which keeps its place at 43.
                require(25 + TextStyle.CAPTION.capHeight() + TextFit.DESCENDER_EM * TextStyle.CAPTION.size() < 43 - 1,
                        where + ": the folder note ends above the name field");
                DjPresetBrowserScreen.Buttons buttons = DjPresetBrowserScreen.Buttons.arrange(measurer, content, 5, labels);
                int total = 15;
                boolean same = true;
                for (int index = 0; index < 4; index++) {
                    int buttonWidth = buttons.widths()[index];
                    total += buttonWidth;
                    TextStyle face = buttons.height() >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
                    for (String label : labels.get(index)) {
                        fits(measurer, label, face, buttonWidth - 10, where + " button '" + label + "'");
                    }
                    same &= buttonWidth == buttons.widths()[0] || index == 3 && Math.abs(buttonWidth - buttons.widths()[0]) <= 3;
                }
                require(total <= content, where + ": the four buttons stay one row inside the panel, " + total + " > " + content);
                require(buttons.height() == 20, where + ": the buttons keep their 20-unit body face");
                equal += same ? 1 : 0;
                int listTop = call(screen, "listTop");
                int listBottom = call(screen, "listBottom");
                require(listTop >= top + 43 + 20 + 6 && listBottom + 12 <= top + panelHeight - 30 - 1,
                        where + ": the list sits between the name field and the status line");
                require(call(screen, "visibleRows") >= 1, where + ": at least one preset row shows");
                List<String> empty = TextFit.wrap(measurer, value(strings, p + "empty"), TextStyle.BODY, content - 16.0F);
                require(18 + empty.size() * Metrics.lineHeight(TextStyle.BODY) <= listBottom - listTop,
                        where + ": the empty list's sentence and icon fit the well");
                // A language pack may number its placeholder: the line substitutes it as Minecraft does, and still
                // shortens only the name inside it.
                for (String template : List.of("«%1$s» — 100%% saved", "Saved: %1$s")) {
                    String shown = DjPresetBrowserScreen.Status.fit(measurer, template, LONG_NAME, content);
                    String[] around = template.replace("%%", "%").split("%1\\$s", -1);
                    fits(measurer, shown, TextStyle.CAPTION, content, where + " numbered status");
                    require(!shown.contains("%1$s") && !shown.contains("%%") && shown.startsWith(around[0])
                                    && shown.endsWith(around[1]),
                            where + ": a numbered placeholder is substituted, the sentence kept whole: " + shown);
                }
                for (String key : List.of("confirm_overwrite", "confirm_delete", "saved", "loaded", "deleted", "error")) {
                    String template = value(strings, p + key);
                    String shown = DjPresetBrowserScreen.Status.fit(measurer, template, LONG_NAME, content);
                    fits(measurer, shown, TextStyle.CAPTION, content, where + " status " + key);
                    String[] around = template.split("%s", -1);
                    require(shown.startsWith(around[0]) && shown.endsWith(around[around.length - 1]),
                            where + ": the answer '" + key + "' keeps its sentence whole and shortens only the name: " + shown);
                }
            }
        }
        System.out.println(name + ": the preset buttons are equal on " + equal + " of " + windows + " windows");
    }

    private static void presetSource(Path root) throws Exception {
        String code = code(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPresetBrowserScreen.java"));
        require(!code.contains("drawString(") && !code.contains("drawCenteredString(") && !code.contains("plainSubstrByWidth("),
                "the preset browser draws no word in the bitmap font");
        require(!code.contains("graphics.fill(left, top, left + panelWidth"), "the preset browser's panel is not a flat slab");
        require(code.contains("RoundedRect.fill(graphics, left, top, panelWidth, panelHeight, Palette.RADIUS_LG, Palette.BG_1)"),
                "the preset browser's panel is the toolkit's raised dialog");
        require(code.contains("WorkshopDraw.scrollbar("), "a long preset list shows the toolkit's scrollbar");
    }

    // ---------------------------------------------------------------- the FX editor's pickers

    /**
     * The effect and font pickers on every window: inside the window (the old effect picker ran sixteen cards off the bottom
     * of a 320x240 window), the heading in at most two whole lines, at least one card in the viewport and the last one
     * reachable by scrolling, and every effect's and font's name whole in its card in one of the name faces.
     */
    private static void pickers(GameTextMeasurer measurer, Map<String, String> strings, String name) {
        List<String> effects = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (TrackEffect.Type type : TrackEffect.Type.values()) {
            effects.add(value(strings, type.translationKey()));
            descriptions.add(value(strings, type.translationKey() + ".description"));
        }
        List<String> fonts = new ArrayList<>();
        for (String font : List.of("default", "uniform", "enchanting", "illager", "bold", "italic", "bold_italic", "narrow",
                "wide", "retro", "arcade", "poster")) {
            fonts.add(value(strings, "music_battle.l10n.fx.font." + font));
        }
        int captions = 0;
        int cutDescriptions = 0;
        int placements = 0;
        int single = 0;
        for (int width = 320; width <= 1920; width += width < 900 ? 1 : 11) {
            for (int height : HEIGHTS) {
                String where = name + " " + width + "x" + height;
                for (boolean effect : new boolean[]{true, false}) {
                    int columns = effect ? width >= 820 ? 4 : width >= 520 ? 3 : 2 : width >= 600 ? 3 : 2;
                    List<String> names = effect ? effects : fonts;
                    String title = value(strings, effect ? "music_battle.l10n.fx.label_114" : "music_battle.l10n.fx.label_115");
                    TrackEffectEditScreen.PickerLayout picker = TrackEffectEditScreen.PickerLayout.fitting(width, height,
                            names, columns, effect ? TrackEffectEditScreen.PickerLayout.EFFECT_CARD
                                    : TrackEffectEditScreen.PickerLayout.FONT_CARD,
                            effect ? TrackEffectEditScreen.PickerLayout.EFFECT_TEXT_PAD : TrackEffectEditScreen.PickerLayout.FONT_TEXT_PAD,
                            effect ? TextStyle.CAPTION : TextStyle.LABEL, title, measurer);
                    require(picker.columns() <= columns, where + ": the picker never has more columns than the window allows");
                    single += picker.columns() == 1 ? 1 : 0;
                    String which = where + (effect ? " effect picker" : " font picker");
                    require(picker.left() >= 0 && picker.right() <= width && picker.top() >= 4
                            && picker.top() + picker.height() <= height - 4, which + ": the dialog stays inside the window");
                    require(picker.heading().size() <= (picker.headingFace() == TextStyle.TITLE ? 2 : 3)
                                    && String.join(" ", picker.heading()).equals(String.join(" ", title.split(" +"))),
                            which + ": the heading is whole, in two lines of the title face or three of the strong one: "
                                    + picker.heading());
                    for (String line : picker.heading()) {
                        fits(measurer, line, picker.headingFace(), picker.width() - 28, which + " heading line");
                    }
                    require(picker.viewportBottom() - picker.viewportTop() >= picker.cardHeight(),
                            which + ": at least one whole card shows");
                    var last = picker.card(names.size() - 1, picker.maxScroll());
                    require(last.bottom() <= picker.viewportBottom() + 0.01F && picker.card(0, 0).y() >= picker.viewportTop(),
                            which + ": scrolling reaches the last card and starts at the first");
                    float textWidth = picker.cardWidth() - (effect ? TrackEffectEditScreen.PickerLayout.EFFECT_TEXT_PAD
                            : TrackEffectEditScreen.PickerLayout.FONT_TEXT_PAD);
                    for (int index = 0; index < names.size(); index++) {
                        placements++;
                        TextStyle face = effect ? TrackEffectEditScreen.PickerLayout.nameFace(measurer, names.get(index), textWidth)
                                : TextStyle.LABEL;
                        fits(measurer, names.get(index), face, textWidth, which + " name");
                        captions += face == TextStyle.CAPTION ? 1 : 0;
                        if (effect) {
                            List<String> lines = TextFit.wrap(measurer, descriptions.get(index), TextStyle.CAPTION, textWidth);
                            cutDescriptions += lines.size() > 2 ? 1 : 0;
                            require(lines.size() <= 2 && 21 + Metrics.lineHeight(TextStyle.CAPTION) * (lines.size() - 1)
                                            + TextStyle.CAPTION.capHeight() <= picker.cardHeight() - 4,
                                    which + ": the description of " + names.get(index) + " is whole in its card: " + lines);
                        }
                    }
                }
            }
        }
        System.out.println(name + ": " + captions + " of " + placements + " picker names take captions, " + single
                + " pickers are one column; " + cutDescriptions + " effect descriptions would need a third line");
    }

    // ---------------------------------------------------------------- the FX editor

    /**
     * Every language key the FX editor names is in the language file. The rows below are measured with the file as it is,
     * and a key it lacks would be measured, and shown in the game, as the bare key; this names it.
     */
    private static void editorKeys(Path root, Map<String, String> strings, String code) throws Exception {
        java.util.regex.Matcher literal = java.util.regex.Pattern.compile("\"((?:[a-z0-9_]+\\.)+[a-z0-9_]+)\"")
                .matcher(code(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java")));
        int keys = 0;
        while (literal.find()) {
            String key = literal.group(1);
            if (key.contains("music_battle") && !key.endsWith("_")) {
                keys++;
                require(strings.containsKey(key), code + ": the FX editor names " + key + ", which the language file does "
                        + "not have (merge this wave's lang_keys)");
            }
        }
        require(keys > 100, code + ": the FX editor's language keys are there to check, " + keys);
    }

    private static void fxSource(Path root) throws Exception {
        String code = code(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java"));
        String upper = code.toUpperCase(Locale.ROOT);
        for (String literal : OLD_FX_LITERALS) {
            require(!upper.contains(literal.toUpperCase(Locale.ROOT)), "the FX editor has no " + literal + " of the first look");
        }
        require(!code.contains("drawString(") && !code.contains("drawCenteredString("), "the FX editor draws no word in the bitmap font");
        require(!code.contains("enableScissor(") && !code.contains("disableScissor("),
                "the FX editor clips through UiClip, which flushes the queued text before the scissor changes");
        require(!code.contains("☑") && !code.contains("☐"), "the FX editor's switches draw a check box, not a symbol Nunito lacks");
        require(code.contains("ru.gora.musicbattle.client.ui.Slider.paint("), "the FX editor's sliders are the toolkit's");
        require(!code.contains("FancyButton.create("), "every inspector button sets a long label in captions before it is cut");
        int wash = staticInt(TrackEffectEditScreen.class, "WASH");
        require(wash == Palette.withAlpha(Palette.BG_0, 48 / 255.0F) && wash >>> 24 == 0x30,
                "the wash over the world behind the editor stays the light one it was (alpha 0x30), in the page colour");
        require(staticInt(TrackEffectEditScreen.class, "DARK_BACKGROUND") == Palette.BG_0, "the dark test ground is the page colour");
        int backdrop = staticInt(TrackEffectEditScreen.class, "PICKER_BACKDROP");
        require((backdrop & 0xFFFFFF) == (Palette.BG_0 & 0xFFFFFF) && backdrop >>> 24 >= 0xE0,
                "a picker hides the preview behind the page colour, as it always did");
    }

    /** The real screen, built as FxHudEditorChecks builds it: init on a window of each size, its panel inside the window. */
    private static void fxPanelFitsTheWindow() throws Exception {
        Font font = new Font(ignored -> null, false) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
            }
        };
        int[][] windows = {{320, 240}, {360, 240}, {400, 300}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {1280, 720},
                {1920, 1080}};
        for (TrackEffect.Type type : List.of(TrackEffect.Type.FLASH, TrackEffect.Type.PLAYER_HIGHLIGHT, TrackEffect.Type.TEXT)) {
            for (int[] window : windows) {
                TrackEffect seed = DjVisualEffectConfig.of(type).effect();
                TrackEffectEditScreen screen = new TrackEffectEditScreen(new Host(), seed, seed, "point");
                screen.width = window[0];
                screen.height = window[1];
                Field fontField = Screen.class.getDeclaredField("font");
                fontField.setAccessible(true);
                fontField.set(screen, font);
                screen.init();
                String where = type + " at " + window[0] + "x" + window[1];
                int left = (int) get(screen, "panelLeft");
                int top = (int) get(screen, "panelTop");
                int width = (int) get(screen, "panelWidth");
                int height = (int) get(screen, "panelHeight");
                require(left >= 0 && left + width <= window[0], where + ": the inspector stays inside the window's width ("
                        + left + "+" + width + ")");
                require(top >= 0 && top + height <= window[1], where + ": the inspector stays inside the window's height");
                // Dragged off the edge, it is put back inside.
                Method relayout = TrackEffectEditScreen.class.getDeclaredMethod("relayout");
                relayout.setAccessible(true);
                set(screen, "panelLeft", window[0]);
                set(screen, "panelTop", window[1]);
                relayout.invoke(screen);
                left = (int) get(screen, "panelLeft");
                top = (int) get(screen, "panelTop");
                require(left + (int) get(screen, "panelWidth") <= window[0] && top + (int) get(screen, "panelHeight") <= window[1],
                        where + ": a panel dragged past the corner comes back inside");
            }
        }
    }

    // ---------------------------------------------------------------- the FX editor's rows

    /** The editor's host, then the DJ's two: no brush and no timeline (visual FX), no brush with a timeline (playlist). */
    private static final boolean[][] HOSTS = {{true, true}, {false, false}, {false, true}};

    /**
     * Every inspector the FX editor can show, as the real screen builds its rows in the language injected: each effect,
     * with and without an effect on the timeline, under each host, and the outline effect with a full palette of sixteen
     * colours. The rows are measured with {@code measurer}, which a palette colour's label also depends on.
     */
    private static Map<String, List<List<Cell>>> inspectors(GameTextMeasurer measurer) throws Exception {
        Map<String, List<List<Cell>>> inspectors = new java.util.LinkedHashMap<>();
        for (boolean[] host : HOSTS) {
            for (TrackEffect.Type type : TrackEffect.Type.values()) {
                for (boolean original : new boolean[]{true, false}) {
                    TrackEffectEditScreen screen = screen(type, original, host, 1920, 1080, measurer);
                    String name = type + (original ? " on the timeline" : " brush") + (host[0] ? "" : " (DJ")
                            + (host[0] ? "" : host[1] ? ", timeline)" : ")");
                    inspectors.put(name, screen.inspectorCells());
                    if (type == TrackEffect.Type.OBJECT_OUTLINE) {
                        @SuppressWarnings("unchecked")
                        List<Integer> colors = (List<Integer>) get(screen, "objectOutlineColors");
                        colors.clear();
                        for (int index = 0; index < 16; index++) {
                            colors.add(0xFF000000 | index * 0x0F0F0F);
                        }
                        set(screen, "selectedObjectOutlineColor", 15);
                        inspectors.put(name + " with sixteen colours", screen.inspectorCells());
                    }
                }
            }
        }
        return inspectors;
    }

    private static TrackEffectEditScreen screen(TrackEffect.Type type, boolean original, boolean[] host, int width, int height,
                                                GameTextMeasurer measurer) throws Exception {
        TrackEffect seed = DjVisualEffectConfig.of(type).effect();
        TrackEffectEditScreen screen = new TrackEffectEditScreen(new Host(host[0], host[1]), original ? seed : null, seed,
                "point");
        screen.width = width;
        screen.height = height;
        set(screen, Screen.class, "font", FAKE_FONT);
        set(screen, "measurer", measurer);
        screen.init();
        return screen;
    }

    /**
     * Every row of every inspector at every inner width the panel can have on a window from 320 wide (the panel is the
     * window less ten, at least 400 where the window allows, so its inner width runs from 276 up): laid out by the pure
     * layout and re-measured here.
     */
    private static void inspectorRows(GameTextMeasurer measurer, Map<String, String> strings, String name) throws Exception {
        Map<List<Cell>, String> rows = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<List<Cell>>> inspector : inspectors(measurer).entrySet()) {
            measuresEveryForm(inspector.getValue(), strings, name + " " + inspector.getKey());
            for (List<Cell> row : inspector.getValue()) {
                rows.putIfAbsent(row, inspector.getKey());
            }
        }
        int arrangements = 0;
        int broken = 0;
        int twoLines = 0;
        for (int width = 276; width <= 1876; width += width < 1100 ? 1 : 9) {
            for (Map.Entry<List<Cell>, String> row : rows.entrySet()) {
                arrangements++;
                String where = name + " " + row.getValue() + " at inner width " + width;
                InspectorLayout layout = InspectorLayout.arrange(List.of(row.getKey()), width, measurer);
                Verdict verdict = row(layout, row.getKey(), width, measurer, where);
                broken += verdict.lines() > 1 ? 1 : 0;
                twoLines += verdict.twoLineLabels();
            }
        }
        // The panel's default width on the owner's 640x360 window: no row there needs to break into more lines.
        for (Map.Entry<List<Cell>, String> row : rows.entrySet()) {
            InspectorLayout layout = InspectorLayout.arrange(List.of(row.getKey()), 540 - 34, measurer);
            require(layout.cells().stream().map(InspectorLayout.Placed::y).distinct().count() == 1,
                    name + " " + row.getValue() + ": on the default 540-unit panel the row " + labels(row.getKey())
                            + " stays one line of cells");
        }
        System.out.println(name + ": " + rows.size() + " distinct inspector rows, " + arrangements + " arrangements; "
                + broken + " break into more lines of cells, " + twoLines + " labels take two lines");
    }

    private record Verdict(int lines, int twoLineLabels) {
    }

    /**
     * How many values each cycling button of the inspector steps through, by the key of its label. The effect's own button
     * opens the picker instead, and the inspector is laid out anew for the effect chosen, so it shows one name.
     */
    private static final Map<String, Integer> CYCLES = Map.of("music_battle.l10n.fx.label_017", 1,
            "music_battle.l10n.fx.label_018", 6, "music_battle.l10n.fx.label_090", 12, "music_battle.l10n.fx.label_091", 11,
            "music_battle.l10n.fx.label_092", 9, "music_battle.l10n.fx.label_097", 5, "music_battle.l10n.fx.label_054", 2,
            "music_battle.l10n.fx.label_055", 7, "music_battle.l10n.fx.label_049", 4,
            "effect.music_battle.stars.setting.direction", 8);
    /**
     * The narrowest each control may be, as this check reads the widgets: a box of thirty for the exact value, four of
     * gap and a slider of forty-two to drag; a swatch of twelve and the widest "#RRGGBB" in captions (41 units at most)
     * with ExactColorButton's margins, which dj-fx-leftovers measures at every tier; room to type in the text field.
     */
    private static final Map<InspectorLayout.Kind, Integer> FLOORS = Map.of(InspectorLayout.Kind.SLIDER, 76,
            InspectorLayout.Kind.COLOR, 68, InspectorLayout.Kind.FIELD, 80, InspectorLayout.Kind.BUTTON, 0,
            InspectorLayout.Kind.SWITCH, 0);

    /**
     * The screen hands the layout every form of its words, not just the ones showing: each cycling button all its values
     * (else the row would rearrange itself as the DJ clicks through them), and each palette colour its label selected and
     * not, with the widest code.
     */
    private static void measuresEveryForm(List<List<Cell>> rows, Map<String, String> strings, String where) {
        for (List<Cell> row : rows) {
            for (Cell cell : row) {
                for (Map.Entry<String, Integer> cycle : CYCLES.entrySet()) {
                    if (cell.label().equals(value(strings, cycle.getKey())) && cell.kind() == InspectorLayout.Kind.BUTTON) {
                        require(new java.util.HashSet<>(cell.texts()).size() == cycle.getValue(), where + ": the button under '"
                                + cell.label() + "' is measured with all " + cycle.getValue() + " of its values, not "
                                + cell.texts().size());
                    }
                }
                if (cell.kind() == InspectorLayout.Kind.COLOR && where.contains("sixteen colours")
                        && !cell.label().equals(value(strings, "music_battle.l10n.fx.label_039"))) {
                    String selected = value(strings, "music_battle.l10n.fx.selected_palette_color").split("%s")[0];
                    boolean bothForms = cell.labels().size() == 3 && cell.labels().stream().anyMatch(form -> form.startsWith(selected));
                    require(bothForms, where + ": a palette colour's label is measured selected and not: " + cell.labels());
                }
            }
        }
    }

    /** One row laid out at one width, re-measured. */
    private static Verdict row(InspectorLayout layout, List<Cell> cells, int width, GameTextMeasurer measurer, String where) {
        require(layout.problems().isEmpty(), where + ": the layout reports " + layout.problems());
        require(layout.cells().size() == cells.size(), where + ": every control of the row is placed once");
        Map<Integer, List<InspectorLayout.Placed>> lines = new java.util.TreeMap<>();
        int twoLineLabels = 0;
        int previousColumn = -1;
        for (InspectorLayout.Placed placed : layout.cells()) {
            Cell cell = cells.get(placed.column());
            String at = where + " '" + cell.label() + "' (" + cell.kind() + ", " + placed.width() + " wide)";
            require(placed.column() == previousColumn + 1, at + ": the controls keep their order");
            previousColumn = placed.column();
            require(placed.x() >= 0 && placed.x() + placed.width() <= width, at + ": the cell stays inside the panel");
            require(placed.width() >= FLOORS.get(cell.kind()), at + ": the control keeps its floor of " + FLOORS.get(cell.kind()));
            // The label: at most two lines, each inside the cell, its own words in its own order, none broken.
            List<String> label = placed.label();
            require(label.size() <= InspectorLayout.MAX_LABEL_LINES, at + ": the label takes at most two lines: " + label);
            twoLineLabels += label.size() == 2 ? 1 : 0;
            for (String line : label) {
                fits(measurer, line, TextStyle.CAPTION, placed.width(), at + " label line");
            }
            require(String.join(" ", label).equals(cell.label().isBlank() ? "" : String.join(" ", cell.label().trim().split(" +"))),
                    at + ": the label is whole, wrapped at spaces only: " + label);
            for (String form : cell.labels()) {
                List<String> wrapped = InspectorLayout.wrapWhole(measurer, form, placed.width());
                require(wrapped != null && wrapped.size() <= InspectorLayout.MAX_LABEL_LINES,
                        at + ": the label's other form '" + form + "' fits the same room");
            }
            if (cell.kind() == InspectorLayout.Kind.BUTTON || cell.kind() == InspectorLayout.Kind.SWITCH) {
                require(!cell.texts().isEmpty(), at + ": the button has words to measure");
                float room = placed.width() - InspectorLayout.BUTTON_PAD
                        - (cell.kind() == InspectorLayout.Kind.SWITCH ? InspectorLayout.CHECK : 0.0F);
                for (String text : cell.texts()) {
                    fits(measurer, text, placed.face(), room, at + " button word");
                }
            }
            lines.computeIfAbsent(placed.y(), y -> new ArrayList<>()).add(placed);
        }
        int top = 0;
        int lineIndex = 0;
        for (Map.Entry<Integer, List<InspectorLayout.Placed>> line : lines.entrySet()) {
            List<InspectorLayout.Placed> placed = line.getValue();
            int labelLines = 1;
            TextStyle face = null;
            boolean buttons = false;
            for (int index = 0; index < placed.size(); index++) {
                labelLines = Math.max(labelLines, placed.get(index).label().size());
                Cell cell = cells.get(placed.get(index).column());
                if (cell.kind() == InspectorLayout.Kind.BUTTON || cell.kind() == InspectorLayout.Kind.SWITCH) {
                    require(face == null || face == placed.get(index).face(), where + ": one face for every button of a line "
                            + "(line " + (lineIndex + 1) + ")");
                    face = placed.get(index).face();
                    buttons = true;
                }
                require(placed.get(index).width() == placed.getFirst().width(), where + ": a line's cells are equal");
                if (index > 0) {
                    InspectorLayout.Placed before = placed.get(index - 1);
                    require(before.x() + before.width() + InspectorLayout.GAP <= placed.get(index).x(),
                            where + ": cells keep their gap and do not overlap");
                }
            }
            if (buttons) {
                // Body where every button's every word fits it; captions only where one does not.
                boolean bodyFits = true;
                for (InspectorLayout.Placed cellPlaced : placed) {
                    Cell cell = cells.get(cellPlaced.column());
                    if (cell.kind() == InspectorLayout.Kind.BUTTON || cell.kind() == InspectorLayout.Kind.SWITCH) {
                        for (String text : cell.texts()) {
                            bodyFits &= measurer.width(text, TextStyle.BODY) <= InspectorLayout.room(cell, cellPlaced.width());
                        }
                    }
                }
                require(face == (bodyFits ? TextStyle.BODY : TextStyle.CAPTION), where + ": the buttons of line "
                        + (lineIndex + 1) + " take " + face + " where " + (bodyFits ? "BODY" : "CAPTION") + " is right");
            }
            // The control sits under its line's labels: one line of label, or one more for a two-line label, and the
            // next line starts seven units under the control.
            int expected = top + InspectorLayout.ABOVE + InspectorLayout.LABEL_LINE * (labelLines - 1);
            require(line.getKey() == expected, where + ": line " + (lineIndex + 1) + " has its controls under its labels, "
                    + line.getKey() + " != " + expected);
            top += InspectorLayout.ROW + InspectorLayout.LABEL_LINE * (labelLines - 1);
            lineIndex++;
        }
        require(layout.height() == top, where + ": the content is as tall as its lines");
        // A row breaks into more lines only when it does not fit one: equal cells with labels of up to two lines.
        if (lines.size() > 1) {
            require(!fitsOneLine(cells, width, measurer), where + ": the row " + labels(cells)
                    + " fits one line of cells but was broken into " + lines.size());
        }
        return new Verdict(lines.size(), twoLineLabels);
    }

    /** The check's own reading of "fits one line": equal cells, whole labels of up to two lines, words in captions. */
    private static boolean fitsOneLine(List<Cell> cells, int width, GameTextMeasurer measurer) {
        int cellWidth = (width - InspectorLayout.GAP * (cells.size() - 1)) / cells.size();
        for (Cell cell : cells) {
            if (cellWidth < FLOORS.get(cell.kind())) {
                return false;
            }
            for (String form : cell.labels()) {
                for (String word : form.trim().split(" +")) {
                    if (measurer.width(word, TextStyle.CAPTION) > cellWidth) {
                        return false;
                    }
                }
                if (TextFit.wrap(measurer, form.trim(), TextStyle.CAPTION, cellWidth).size() > InspectorLayout.MAX_LABEL_LINES) {
                    return false;
                }
            }
            for (String text : cell.texts()) {
                if (measurer.width(text, TextStyle.CAPTION) > InspectorLayout.room(cell, cellWidth)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> labels(List<Cell> cells) {
        List<String> labels = new ArrayList<>();
        for (Cell cell : cells) {
            labels.add(cell.label());
        }
        return labels;
    }

    /**
     * The real screen on the windows players have, at the GUI scale the game picks for each: its widgets and their labels
     * where the layout put them, its buttons in their line's face, the action buttons and the message line whole, the
     * heading inside the panel. Also at the narrowest and the widest the panel can be dragged to.
     */
    private static void inspectorScreens(GameTextMeasurer[] tiers, Map<String, String> strings, String code) throws Exception {
        int[][] windows = {{320, 240, 1}, {320, 240, 2}, {427, 240, 2}, {384, 240, 5}, {480, 270, 4}, {640, 360, 2},
                {854, 480, 2}, {1280, 720, 3}, {1920, 1080, 4}};
        List<String> messages = new ArrayList<>();
        for (int label = 107; label <= 113; label++) {
            messages.add(value(strings, String.format(Locale.ROOT, "music_battle.l10n.fx.label_%03d", label)));
        }
        int screens = 0;
        for (int[] window : windows) {
            GameTextMeasurer measurer = tiers[window[2]];
            for (boolean[] host : HOSTS) {
                for (TrackEffect.Type type : TrackEffect.Type.values()) {
                    for (boolean original : new boolean[]{true, false}) {
                        TrackEffectEditScreen screen = screen(type, original, host, window[0], window[1], measurer);
                        for (int resize = 0; resize < 3; resize++) {
                            if (resize > 0) {
                                // Dragged to the narrowest the panel goes, then to the widest.
                                set(screen, "panelWidth", resize == 1 ? 0 : window[0]);
                                java.lang.reflect.Method relayout = TrackEffectEditScreen.class.getDeclaredMethod("relayout");
                                relayout.setAccessible(true);
                                relayout.invoke(screen);
                            }
                            screens++;
                            String where = code + " " + window[0] + "x" + window[1] + " at GUI scale " + window[2] + " " + type
                                    + (original ? " on the timeline" : " brush") + (host[0] ? "" : " (DJ)")
                                    + (resize == 0 ? "" : resize == 1 ? " narrowest" : " widest");
                            followsTheLayout(screen, measurer, messages, where);
                        }
                    }
                }
            }
        }
        System.out.println(code + ": " + screens + " FX editor screens set their widgets where the layout puts them");
    }

    private static void followsTheLayout(TrackEffectEditScreen screen, GameTextMeasurer measurer, List<String> messages,
                                         String where) throws Exception {
        int panelLeft = (int) get(screen, "panelLeft");
        int panelWidth = (int) get(screen, "panelWidth");
        int contentTop = (int) get(screen, "contentTop");
        int contentBottom = (int) get(screen, "contentBottom");
        int scroll = (int) get(screen, "scrollOffset");
        InspectorLayout layout = InspectorLayout.arrange(screen.inspectorCells(), panelWidth - 34, measurer);
        require(layout.problems().isEmpty(), where + ": the layout reports " + layout.problems());
        require((int) get(screen, "maxScroll") == Math.max(0, layout.height() - Math.max(40, contentBottom - contentTop)),
                where + ": the viewport scrolls through exactly the content's height");
        Map<String, InspectorLayout.Placed> byPosition = new HashMap<>();
        for (InspectorLayout.Placed placed : layout.cells()) {
            byPosition.put((panelLeft + 14 + placed.x()) + "," + (contentTop + placed.y() - scroll), placed);
        }
        List<?> shown = (List<?>) get(screen, "visibleLabels");
        require(!shown.isEmpty(), where + ": some controls show in the viewport");
        for (Object label : shown) {
            Object control = invoke(label, "control");
            net.minecraft.client.gui.components.AbstractWidget widget =
                    (net.minecraft.client.gui.components.AbstractWidget) invoke(control, "widget");
            net.minecraft.client.gui.components.AbstractWidget companion =
                    (net.minecraft.client.gui.components.AbstractWidget) invoke(control, "companion");
            InspectorLayout.Placed placed = byPosition.get(widget.getX() + "," + widget.getY());
            String at = where + " '" + invoke(control, "label") + "'";
            require(placed != null, at + ": the control sits where the layout placed a cell");
            if (placed == null) {
                continue;
            }
            int right = companion == null ? widget.getX() + widget.getWidth() : companion.getX() + companion.getWidth();
            require(right == panelLeft + 14 + placed.x() + placed.width() && right <= panelLeft + panelWidth - 20,
                    at + ": the control fills its cell and stays clear of the scrollbar");
            require(invoke(label, "lines").equals(placed.label()), at + ": the screen draws the layout's label lines");
            if (widget instanceof net.minecraft.client.gui.components.Button button && companion == null
                    && button.getClass().getSimpleName().equals("InspectorButton")) {
                require(get(button, "face") == placed.face(), at + ": the button takes its line's face");
            }
        }
        List<?> actions = (List<?>) get(screen, "actionWidgets");
        for (Object action : actions) {
            net.minecraft.client.gui.components.AbstractWidget button = (net.minecraft.client.gui.components.AbstractWidget) action;
            TextStyle face = (TextStyle) get(button, "face");
            require(face != null, where + ": the action buttons have a face their words fit");
            if (face != null) {
                fits(measurer, button.getMessage().getString(), face, button.getWidth() - InspectorLayout.BUTTON_PAD,
                        where + " action");
            }
            require(button.getX() >= panelLeft + 14 && button.getX() + button.getWidth() <= panelLeft + panelWidth - 14,
                    where + ": the action buttons stay inside the panel");
        }
        for (String message : messages) {
            fits(measurer, message, TextStyle.CAPTION, panelWidth - 24.0F, where + " message");
        }
        fits(measurer, screen.getTitle().getString(), TextStyle.TITLE, panelWidth - 48.0F, where + " heading");
    }

    private static final Font FAKE_FONT = new Font(ignored -> null, false) {
        @Override public int width(String text) { return text.length() * 6; }
        @Override public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
        }
    };

    /** The language the screens read their words from: the game's Language and I18n, as LocalizationChecks injects them. */
    private static void inject(Map<String, String> strings) throws Exception {
        Language language = new Language() {
            @Override public String getOrDefault(String key, String fallback) { return strings.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return strings.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        };
        Language.inject(language);
        Field i18n = I18n.class.getDeclaredField("language");
        i18n.setAccessible(true);
        i18n.set(null, language);
    }

    private static final class Host implements TrackEffectEditHost {
        private final boolean brush;
        private final boolean timeline;

        private Host() {
            this(true, true);
        }

        private Host(boolean brush, boolean timeline) {
            this.brush = brush;
            this.timeline = timeline;
        }

        @Override public Screen parentScreen() { return null; }
        @Override public Path effectPreviewAudioPath() { return null; }
        @Override public boolean managesPreviewAudio() { return false; }
        @Override public void saveEffect(TrackEffect original, TrackEffect edited, String placementMode) { }
        @Override public boolean showBrushActions() { return brush; }
        @Override public boolean showTimelineControls() { return timeline; }
        @Override public boolean allowTypeChange() { return brush; }
    }

    // ---------------------------------------------------------------- helpers

    private static String code(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static void fits(GameTextMeasurer measurer, String text, TextStyle style, float room, String what) {
        float width = measurer.width(text, style);
        require(width <= room + 0.01F, what + ": '" + text + "' is " + width + " in " + style + ", room " + room);
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject().entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return values;
    }

    private static String value(Map<String, String> strings, String key) {
        String value = strings.get(key);
        if (value == null) {
            throw new AssertionError("missing language key " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }

    private static int call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (int) method.invoke(target);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        set(target, target.getClass(), name, value);
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static int staticInt(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition && FAILURES.size() < 300) {
            FAILURES.add("FAIL: " + message);
        }
    }
}
