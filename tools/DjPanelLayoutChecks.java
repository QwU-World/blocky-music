package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import ru.gora.musicbattle.client.DjPanelScreen.PanelLayout;
import ru.gora.musicbattle.client.DjPanelScreen.PanelLayout.Control;
import ru.gora.musicbattle.client.DjPanelScreen.PanelLayout.Kind;
import ru.gora.musicbattle.client.DjPanelScreen.PanelLayout.Placed;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * Every word on the DJ panel is drawn whole, in English and in Russian, on every window from 320x240 up and at every GUI
 * scale: measured as the game measures it, with Minecraft's own FreeType glyph provider at each oversample tier 1 to 9
 * (GameTextMeasurer), and set by the panel's own rules ({@code DjPanelScreen.PanelLayout}).
 *
 * <p>At 640x360 in Russian the old fixed fractions of the mixer cut nine labels ("Сброс коло…", "Звуковые F…",
 * "Ревербера…", "Играть /…", "Повтор:…", "Отключить…", "Визуальны…", "Нет подключённых колоно…", "Средние: +0,0…"), and
 * at 320 wide the library's column was 98 units, too narrow for its own words. This check re-measures what the layout
 * placed instead of trusting it: every text a button can show against its button in the face its height gives it, every
 * value a slider can show over its whole range against the slider, the monitor's six messages, the library's heading,
 * cards and empty states, the tabs and the title. It also pins that the controls of a page stay inside the window and
 * their column and clear of each other, that the mixer's rows and the line under them fit the window's height or scroll
 * within it (every row reachable whole, the view under the title), that the two pages show only for a width (never for
 * a height, and never for a full card the compact one would hold), that a laptop's 427x240, 384x240, 378x246, 456x256,
 * 480x270, 480x300 and the owner's 640x360 keep both columns, that the real screen scrolls its mixer with the wheel and
 * with the keyboard's focus and presses no row outside the view, that the sliders write their numbers the way the
 * layout measured them, and that the screen draws no word in the bitmap font and no symbol Nunito lacks.
 *
 * <p>NOT COVERED: how it looks in the game (the visual session does that), data the panel may shorten (a map's title, a
 * speaker's or a camera's coordinates, a status about a "+" pick, which keeps its whole text as a tooltip), and
 * windows lower than 240.
 */
public final class DjPanelLayoutChecks {
    /** The keys this change adds; read from the language files once the integrator has merged them. */
    private static final Map<String, String[]> PROPOSED = Map.of(
            "screen.music_battle.dj_panel.no_speakers_short", new String[]{"No speakers", "Нет колонок"},
            "screen.music_battle.dj_panel.page_library", new String[]{"Library", "Библиотека"},
            "screen.music_battle.dj_panel.page_mixer", new String[]{"Mixer", "Микшер"},
            "screen.music_battle.dj_panel.track_library_short", new String[]{"Beatmap library", "Библиотека карт"},
            "screen.music_battle.dj_panel.camera_live_badge", new String[]{"LIVE", "LIVE"});
    private static final int[] HEIGHTS = {240, 250, 270, 279, 280, 300, 330, 359, 360, 400, 450, 480, 499, 500, 540, 720,
            1080};
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;
    private static int layouts;
    private static int paged;
    /** Why the windows that show two pages could not show both columns, by the first reason the layout gives. */
    private static final Map<String, Integer> PAGED_BECAUSE = new java.util.TreeMap<>();
    /** The widest window of a language and a scale that shows the two pages. */
    private static int widestPaged;
    /** Windows of laptops at GUI scale 2 and 3 that showed the two pages for the mixer's height alone before. */
    private static final int[][] SHORT_WINDOWS = {{427, 240}, {384, 240}, {378, 246}, {456, 256}, {480, 270}, {480, 300},
            {640, 360}};

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        source(root);
        sliderNumbers();
        substitution();
        List<Integer> widths = new ArrayList<>();
        for (int width = 320; width <= 1000; width++) {
            widths.add(width);
        }
        for (int width = 1002; width <= 1920; width += 7) {
            widths.add(width);
        }
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (int language = 0; language < 2; language++) {
                DjPanelScreen.Words words = words(lang, language);
                String name = (language == 0 ? "en" : "ru") + " at GUI scale " + tier;
                Map<Control, Float> widestSlider = widestSliders(measurer, words);
                int before = paged;
                PAGED_BECAUSE.clear();
                widestPaged = 0;
                for (int width : widths) {
                    for (int height : HEIGHTS) {
                        PanelLayout layout = PanelLayout.compute(width, height, measurer, words);
                        verify(layout, measurer, words, widestSlider, name + " " + width + "x" + height);
                    }
                }
                PanelLayout owner = PanelLayout.compute(640, 360, measurer, words);
                if (tier <= 3) {
                    require(!owner.paged(), name + ": 640x360 shows the camera, the library and the mixer side by side");
                }
                for (int[] window : SHORT_WINDOWS) {
                    shortWindow(PanelLayout.compute(window[0], window[1], measurer, words), measurer, words, name);
                }
                if (tier == 2) {
                    focusFollowsThePage(root, measurer, words, name);
                    mixerScrollFollowsTheFocus(measurer, words, name);
                }
                System.out.println(name + ": " + (paged - before) + " of " + widths.size() * HEIGHTS.length
                        + " windows show the panel as two pages, the widest " + widestPaged + " wide, because " + PAGED_BECAUSE);
            }
        }
        System.out.println("DJ panel layout: " + layouts + " layouts, " + checks + " checks");
        FAILURES.sort(java.util.Comparator.comparing(failure -> failure.contains(" 640x360") ? 0 : 1));
        FAILURES.stream().limit(40).forEach(System.err::println);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("DJ panel layout PASS: en and ru, every width from 320 to 1920, heights 240 to 1080, GUI scales 1-9");
        System.out.println("NOT COVERED: the panel in the game, data it may shorten (titles, coordinates, a pick's status), "
                + "windows lower than 240");
    }

    /** One layout: everything it placed, re-measured. */
    private static void verify(PanelLayout layout, GameTextMeasurer measurer, DjPanelScreen.Words words,
                               Map<Control, Float> widestSlider, String where) {
        layouts++;
        paged += layout.paged() ? 1 : 0;
        require(layout.problems().isEmpty(), where + ": the layout reports " + layout.problems());
        int width = layout.width();
        int height = layout.height();
        if (layout.paged()) {
            // The pages come only from a width: both columns at this window fail on a word, never on the height.
            PanelLayout columns = PanelLayout.build(width, height, measurer, words, false);
            List<String> because = columns.problems();
            require(!because.isEmpty(), where + ": the two pages show only where both columns do not fit");
            // A full card's cover is no reason for the pages where the compact card, with its smaller cover, holds a
            // card's words in the same column.
            float compact = PanelLayout.cardTextWidth(columns.libraryColumn().w(), columns.columns(),
                    PanelLayout.COMPACT_CARD_HEIGHT);
            boolean compactHolds = measurer.width(words.get("screen.music_battle.dj_panel.builtin"), TextStyle.CAPTION)
                    <= compact && PanelLayout.missingBlock(measurer, words.get("screen.music_battle.dj_panel.library_missing"),
                    compact) != null;
            for (String reason : because) {
                require(!reason.contains("height"), where + ": the two pages show for a height: " + reason);
                require(!(compactHolds && reason.startsWith("a card's")), where + ": the two pages show for a full card "
                        + "where the compact card holds its words: " + reason);
            }
            widestPaged = Math.max(widestPaged, width);
            if (!because.isEmpty()) {
                PAGED_BECAUSE.merge(because.getFirst().replaceAll("row \\d+", "row N").replaceAll("message \\S+", "message"),
                        1, Integer::sum);
            }
        }
        mixerView(layout, where);
        Rect window = new Rect(0, 0, width, height);
        for (boolean library : new boolean[]{true, false}) {
            List<Map.Entry<Control, Placed>> page = new ArrayList<>();
            for (Map.Entry<Control, Placed> entry : layout.controls().entrySet()) {
                if (entry.getKey().library() == library) {
                    page.add(entry);
                }
            }
            require(page.size() == (library ? layout.paged() ? 9 : 7 : 33), where + ": every control of the "
                    + (library ? "library" : "mixer") + " is placed, " + page.size());
            Rect column = library ? layout.libraryColumn() : layout.mixerColumn();
            for (int index = 0; index < page.size(); index++) {
                Control control = page.get(index).getKey();
                Rect rect = page.get(index).getValue().rect();
                // A scrolling mixer's rows lie in its scrolled column, from the top of its view to the end of its content.
                Rect bounds = library || layout.mixerScrollMax() == 0 ? window : new Rect(0, layout.mixerViewTop(), width,
                        layout.mixerContentBottom() - layout.mixerViewTop());
                require(rect.inside(bounds, 0.01F), where + ": " + control + " stays inside the "
                        + (bounds == window ? "window, " : "mixer's scrolled column, ") + rect);
                require(rect.x() >= column.x() - 0.01F && rect.right() <= column.right() + 0.01F,
                        where + ": " + control + " stays inside its column");
                for (int other = index + 1; other < page.size(); other++) {
                    require(!rect.overlaps(page.get(other).getValue().rect(), 0.01F), where + ": " + control
                            + " and " + page.get(other).getKey() + " do not overlap");
                }
                fits(layout, page.get(index).getValue(), control, measurer, words, widestSlider, where);
            }
        }
        // The line under the mixer: its words when nothing is loaded, inside the window once the mixer is scrolled down.
        require(layout.stateY() + 1 + TextStyle.BODY.capHeight() - layout.mixerScrollMax() <= height - 2,
                where + ": the line under the mixer fits");
        stateLines(measurer, words, TextStyle.BODY, layout.mixerColumn().w(), false, where + " mixer");
        if (!layout.paged()) {
            require(layout.placed(Control.LIBRARY_PLAY) == null && layout.placed(Control.LIBRARY_CLOSE) == null,
                    where + ": both columns show, so the library has no Play and Close of its own");
        }
        fitsText(measurer, words.get("screen.music_battle.dj_panel.no_track_loaded"), TextStyle.BODY,
                layout.mixerColumn().w(), where + " 'nothing loaded'");
        String title = words.get("screen.music_battle.dj_panel.title");
        if (layout.paged()) {
            float right = PanelLayout.MARGIN;
            for (int index = 0; index < layout.tabs().size(); index++) {
                Rect tab = layout.tabs().get(index);
                String label = words.get(PanelLayout.PAGE_KEYS.get(index));
                for (boolean selected : new boolean[]{true, false}) {
                    fitsText(measurer, label, layout.tabFace(selected), tab.w() - 2 * (layout.rowHeight() < 18 ? 7 : 9),
                            where + " tab '" + label + "'");
                }
                require(tab.inside(window, 0.01F) && tab.bottom() <= layout.header(), where + ": the tab sits in the header");
                right = tab.right();
            }
            if (layout.titleFace() != null) {
                require(measurer.width(title, layout.titleFace()) <= width - PanelLayout.RIGHT_MARGIN - right - 8 + 0.01F,
                        where + ": the title beside the tabs does not run into them");
            }
            require(layout.mixerColumn().y() >= 0 && layout.placed(Control.MODE).rect().y() >= layout.header(),
                    where + ": the mixer page starts under the tabs");
            require(layout.placed(Control.CAMERA).rect().y() >= layout.header(), where + ": the library page starts under the tabs");
            // The library page's end of the header: what is playing, then Play or Pause and Close, right of the tabs.
            Rect now = layout.nowPlaying();
            Placed playPlaced = layout.placed(Control.LIBRARY_PLAY);
            Placed closePlaced = layout.placed(Control.LIBRARY_CLOSE);
            require(playPlaced != null && closePlaced != null, where + ": the library page has Play and Close in its header");
            Rect play = playPlaced == null ? new Rect(width, 0, 0, 0) : playPlaced.rect();
            Rect close = closePlaced == null ? new Rect(width, 0, 0, 0) : closePlaced.rect();
            Rect lastTab = layout.tabs().getLast();
            require(now.x() >= lastTab.right() + 4 && now.right() + 6 <= play.x() && play.right() <= close.x()
                            && close.right() <= width - PanelLayout.RIGHT_MARGIN + 0.01F,
                    where + ": the header holds the tabs, what is playing, Play and Close in that order, apart");
            require(play.bottom() <= layout.header() && close.bottom() <= layout.header() && now.bottom() <= layout.header(),
                    where + ": Play, Close and what is playing sit in the header");
            require(layout.nowPlayingFace() == TextStyle.CAPTION, where + ": the header says what is playing in captions");
            stateLines(measurer, words, layout.nowPlayingFace(), now.w(), true, where + " library page");
        } else {
            fitsText(measurer, title, layout.titleFace(), width - layout.divider() - 16, where + " title");
            fitsText(measurer, words.get(PanelLayout.MONITOR_HEADING), TextStyle.LABEL, layout.libraryColumn().w() - 4,
                    where + " monitor heading");
            require(layout.libraryColumn().right() + 8 <= layout.divider() + 0.01F
                    && layout.divider() + 8 <= layout.mixerColumn().x() + 0.01F, where + ": the columns keep their gutter");
        }
        library(layout, measurer, words, where);
    }

    /** The left column's own words: the monitor's messages, the heading, the cards and the empty library. */
    private static void library(PanelLayout layout, GameTextMeasurer measurer, DjPanelScreen.Words words, String where) {
        Rect monitor = layout.monitor();
        require(monitor.bottom() + 13 <= layout.searchTop(), where + ": the monitor ends above the search row");
        for (String key : PanelLayout.MONITOR_KEYS) {
            String text = words.get(key);
            PanelLayout.Block block = PanelLayout.monitorBlock(measurer, text, monitor);
            require(block != null, where + ": the monitor holds " + key);
            if (block != null) {
                whole(measurer, text, block, Math.max(24.0F, monitor.w() - 12), where + " monitor " + key);
                require(block.height() <= monitor.h() - 6 + 0.01F, where + ": " + key + " fits the monitor's height");
            }
        }
        String heading = words.get(layout.libraryHeadingKey());
        fitsText(measurer, heading, TextStyle.LABEL, layout.libraryColumn().w() - 2, where + " library heading");
        if (layout.libraryHeadingKey().equals(PanelLayout.LIBRARY_HEADING_SHORT)) {
            require(measurer.width(words.get(PanelLayout.LIBRARY_HEADING), TextStyle.LABEL) > layout.libraryColumn().w() - 2,
                    where + ": the short heading is used only where the long one does not fit");
        }
        require(layout.trackTop() + PanelLayout.COMPACT_CARD_HEIGHT <= layout.trackBottom(),
                where + ": the library shows at least one whole card");
        float textWidth = PanelLayout.cardTextWidth(layout.libraryColumn().w(), layout.columns(), layout.cardHeight());
        fitsText(measurer, words.get("screen.music_battle.dj_panel.builtin"), TextStyle.CAPTION, textWidth,
                where + " card 'built-in'");
        String missing = words.get("screen.music_battle.dj_panel.library_missing");
        PanelLayout.Block block = PanelLayout.missingBlock(measurer, missing, textWidth);
        require(block != null && block.lines().size() <= 2, where + ": a missing map's card says why in two lines at most");
        if (block != null) {
            whole(measurer, missing, block, textWidth, where + " card 'missing'");
            float bottom = 6 + TextStyle.TITLE.capHeight() + 5 + (block.lines().size() - 1) * Metrics.lineHeight(TextStyle.CAPTION)
                    + TextStyle.CAPTION.capHeight();
            require(bottom <= layout.cardHeight() - 3, where + ": the missing card's lines end inside the card");
        }
        float panelHeight = PanelLayout.emptyPanelHeight(layout.trackTop(), layout.trackBottom());
        for (String key : PanelLayout.EMPTY_KEYS) {
            String text = words.get(key);
            PanelLayout.Block empty = PanelLayout.emptyBlock(measurer, text, layout.libraryColumn().w(), panelHeight);
            require(empty != null, where + ": the empty library holds " + key);
            if (empty != null) {
                whole(measurer, text, empty, layout.libraryColumn().w() - 24.0F, where + " empty " + key);
                require(20 + empty.height() + 8 <= panelHeight + 0.01F, where + ": " + key + " and its icon fit the panel");
            }
        }
    }

    /** A control's every text against the room its button or slider leaves it. */
    private static void fits(PanelLayout layout, Placed placed, Control control, GameTextMeasurer measurer,
                             DjPanelScreen.Words words, Map<Control, Float> widestSlider, String where) {
        Rect rect = placed.rect();
        TextStyle face = PanelLayout.face(rect.h());
        String at = where + " " + control + " (" + placed.kind() + ", " + rect.w() + " wide)";
        switch (placed.kind()) {
            case TEXT, SWITCH -> {
                float mark = placed.kind() == Kind.SWITCH ? PanelLayout.CHECK + PanelLayout.CHECK_GAP : 0.0F;
                List<String> texts = PanelLayout.texts(control, words);
                require(!texts.isEmpty(), at + " has words to measure");
                for (String text : texts) {
                    fitsText(measurer, text, face, rect.w() - PanelLayout.BUTTON_PAD - mark, at);
                }
            }
            case GLYPH -> fitsText(measurer, PanelLayout.ADD_GLYPH, face, rect.w() - PanelLayout.BUTTON_PAD, at);
            case ICON -> require(rect.w() >= 12 && rect.h() >= 12, at + ": the icon has room");
            case SLIDER -> {
                require(widestSlider.get(control) <= rect.w() + 0.01F, at + ": its widest label needs "
                        + widestSlider.get(control));
                kitShowsWhole(measurer, rect, WIDEST_LABEL.get(control), at);
            }
            case FIELD -> require(rect.w() >= PanelLayout.FIELD_MIN, at + ": the search field keeps room to type");
        }
        if (!control.library()) {
            require(rect.h() == layout.rowHeight(), at + ": the mixer's rows are one height");
        }
    }

    /**
     * The room each slider's widest label needs over its whole range, every value its unit writes: the layout only
     * measures the two ends, so this pins that an end is the widest. The label that needs it is kept for the kit's test.
     */
    private static Map<Control, Float> widestSliders(GameTextMeasurer measurer, DjPanelScreen.Words words) {
        Map<Control, Float> widest = new EnumMap<>(Control.class);
        WIDEST_LABEL.clear();
        for (Control control : Control.values()) {
            PanelLayout.SliderSpec spec = PanelLayout.slider(control);
            if (spec == null) {
                continue;
            }
            float max = 0.0F;
            for (float value : grid(spec)) {
                String text = words.format(spec.key(), PanelLayout.value(spec.unit(), value, words.locale()));
                float need = PanelLayout.sliderNeed(measurer, text);
                if (need > max) {
                    max = need;
                    WIDEST_LABEL.put(control, text);
                }
                // A slider that centres the whole label instead of splitting it needs no more than this.
                require(measurer.width(text, TextStyle.CAPTION) + PanelLayout.SLIDER_PAD <= need + 0.01F,
                        control + ": '" + text + "' measured as name and value covers the whole label too");
            }
            float measured = 0.0F;
            for (String text : PanelLayout.sliderTexts(control, words)) {
                measured = Math.max(measured, PanelLayout.sliderNeed(measurer, text));
            }
            require(measured + 0.01F >= max, control + ": the layout measures the widest of its labels, " + measured
                    + " < " + max);
            widest.put(control, max);
        }
        return widest;
    }

    /**
     * The toolkit's own verdict, when its slider exposes it: Slider.layout and Slider.label set the label in the field
     * exactly as Slider.paint draws it, and neither half may come back cut. Looked up by name, so a change to those
     * helpers is reported here rather than breaking the build of this check.
     */
    private static void kitShowsWhole(GameTextMeasurer measurer, Rect rect, String label, String where) {
        if (KIT_LAYOUT == null) {
            return;
        }
        try {
            Object layout = KIT_LAYOUT.invoke(null, rect, true);
            Object shown = KIT_LABEL.invoke(null, (ru.gora.musicbattle.client.ui.TextMeasurer) measurer::width, label, layout);
            String name = (String) shown.getClass().getMethod("name").invoke(shown);
            String value = (String) shown.getClass().getMethod("value").invoke(shown);
            int split = label.lastIndexOf(": ");
            require(name.equals(split > 0 ? label.substring(0, split).strip() : label)
                            && value.equals(split > 0 ? label.substring(split + 2).strip() : ""),
                    where + ": the kit's slider shows '" + label + "' whole, not '" + name + "' / '" + value + "'");
        } catch (ReflectiveOperationException exception) {
            require(false, "the kit's Slider.layout/label could not be called: " + exception);
        }
    }

    private static final Map<Control, String> WIDEST_LABEL = new EnumMap<>(Control.class);
    private static final Method KIT_LAYOUT;
    private static final Method KIT_LABEL;

    static {
        Method layout = null;
        Method label = null;
        try {
            Class<?> slider = Class.forName("ru.gora.musicbattle.client.ui.Slider");
            Class<?> layoutType = Class.forName("ru.gora.musicbattle.client.ui.Slider$Layout");
            layout = slider.getMethod("layout", Rect.class, boolean.class);
            label = slider.getMethod("label", ru.gora.musicbattle.client.ui.TextMeasurer.class, String.class, layoutType);
        } catch (ReflectiveOperationException absent) {
            System.out.println("NOTE: this kit's Slider has no layout/label helpers; its labels are measured by the "
                    + "panel's own rule only");
        }
        KIT_LAYOUT = layout;
        KIT_LABEL = label;
    }

    /** Every value a slider's label can show, at the resolution its unit writes. */
    private static float[] grid(PanelLayout.SliderSpec spec) {
        return switch (spec.unit()) {
            case PERCENT -> range(spec.minimum(), spec.maximum(), 0.01F);
            case DECIBELS, NUMBER -> range(spec.minimum(), spec.maximum(), 0.1F);
            case MILLISECONDS, HERTZ -> range(spec.minimum(), spec.maximum(), 1.0F);
            // Minutes and seconds up to 99:59 of a beatmap, as ticks.
            case TIME -> range(0.0F, 99 * 1200 + 59 * 20, 20.0F);
        };
    }

    private static float[] range(float minimum, float maximum, float step) {
        int count = Math.round((maximum - minimum) / step) + 1;
        float[] values = new float[count];
        for (int index = 0; index < count; index++) {
            values[index] = minimum + index * step;
        }
        return values;
    }

    /**
     * The sliders write their numbers as the layout measured them: a real SettingSlider, its message read back at a grid
     * of settings for each unit. Without a game the player's locale is the root one, as PanelLayout.value is given here.
     */
    private static void sliderNumbers() throws Exception {
        Class<?> sliderType = Class.forName("ru.gora.musicbattle.client.DjPanelScreen$SettingSlider");
        Class<?> setterType = Class.forName("ru.gora.musicbattle.client.DjPanelScreen$Setter");
        Object setter = Proxy.newProxyInstance(setterType.getClassLoader(), new Class<?>[]{setterType}, (proxy, method, args) -> null);
        Constructor<?> constructor = sliderType.getDeclaredConstructor(int.class, int.class, int.class, int.class, String.class,
                float.class, float.class, float.class, setterType, DjPanelScreen.Unit.class, boolean.class);
        constructor.setAccessible(true);
        Method set = sliderType.getDeclaredMethod("setSetting", float.class);
        set.setAccessible(true);
        java.lang.reflect.Field fraction = net.minecraft.client.gui.components.AbstractSliderButton.class.getDeclaredField("value");
        fraction.setAccessible(true);
        for (DjPanelScreen.Unit unit : DjPanelScreen.Unit.values()) {
            float minimum = unit == DjPanelScreen.Unit.DECIBELS ? -12.0F : 0.0F;
            float maximum = switch (unit) {
                case PERCENT -> 2.0F;
                case DECIBELS -> 12.0F;
                case NUMBER -> 64.0F;
                case MILLISECONDS -> 1000.0F;
                case HERTZ -> 20_000.0F;
                case TIME -> 72_000.0F;
            };
            Object slider = constructor.newInstance(0, 0, 100, 20, "key", minimum, minimum, maximum, setter, unit, false);
            for (int step = 0; step <= 40; step++) {
                float value = minimum + (maximum - minimum) * step / 40.0F;
                set.invoke(slider, value);
                Component message = ((net.minecraft.client.gui.components.AbstractWidget) slider).getMessage();
                Object shown = ((TranslatableContents) message.getContents()).getArgs()[0];
                // The slider stores a fraction of its range, so the value it writes back is the one it holds.
                float held = Math.max(minimum, Math.min(maximum,
                        (float) (minimum + fraction.getDouble(slider) * (maximum - minimum))));
                String expected = PanelLayout.value(unit, held, Locale.ROOT);
                require(String.valueOf(shown).equals(expected), unit + " writes " + shown + " where the layout measured "
                        + expected);
            }
        }
    }

    /**
     * After a page switch by keyboard the focus is on the tab of the page that opened, so a second Enter does not switch
     * back. Switching rebuilds the screen, and the game then focuses the first control, which was the Library tab. The real
     * screen is allocated without a game, given a two-page layout of a 320x240 window and a Minecraft whose last input was
     * the Tab key, and its own tabs and its own initial focus are asked where the focus lands; a pointer leaves it alone.
     */
    private static void focusFollowsThePage(Path root, GameTextMeasurer measurer, DjPanelScreen.Words words, String name)
            throws Exception {
        PanelLayout layout = PanelLayout.compute(320, 240, measurer, words);
        require(layout.paged(), name + ": 320x240 shows the two pages the focus check needs");
        Object unsafe = unsafe();
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        DjPanelScreen screen = (DjPanelScreen) allocate.invoke(unsafe, DjPanelScreen.class);
        net.minecraft.client.Minecraft minecraft = (net.minecraft.client.Minecraft) allocate.invoke(unsafe,
                net.minecraft.client.Minecraft.class);
        field(net.minecraft.client.gui.screens.Screen.class, "minecraft").set(screen, minecraft);
        field(DjPanelScreen.class, "layout").set(screen, layout);
        field(DjPanelScreen.class, "mixerWidgets").set(screen, new java.util.LinkedHashMap<>());
        Method addTabs = DjPanelScreen.class.getDeclaredMethod("addPageTabs");
        addTabs.setAccessible(true);
        // The game's own entry point, so that the screen's override, or the game's choice without one, is what runs.
        Method initialFocus = net.minecraft.client.gui.screens.Screen.class.getDeclaredMethod("setInitialFocus");
        initialFocus.setAccessible(true);
        java.lang.reflect.Field page = field(DjPanelScreen.class, "page");
        Object before = page.get(null);
        try {
            for (net.minecraft.client.InputType input : List.of(net.minecraft.client.InputType.KEYBOARD_TAB,
                    net.minecraft.client.InputType.KEYBOARD_ARROW, net.minecraft.client.InputType.MOUSE)) {
                field(net.minecraft.client.Minecraft.class, "lastInputType").set(minecraft, input);
                for (PanelLayout.Page shown : PanelLayout.Page.values()) {
                    page.set(null, shown);
                    for (String list : List.of("children", "narratables", "renderables")) {
                        field(net.minecraft.client.gui.screens.Screen.class, list).set(screen, new ArrayList<>());
                    }
                    field(net.minecraft.client.gui.components.events.AbstractContainerEventHandler.class, "focused")
                            .set(screen, null);
                    addTabs.invoke(screen);
                    initialFocus.invoke(screen);
                    Object tab = field(DjPanelScreen.class, "selectedTab").get(screen);
                    String where = name + " " + shown + " page after " + input;
                    require(tab != null && key(tab).equals(PanelLayout.PAGE_KEYS.get(shown.ordinal())),
                            where + ": the open page's tab is known");
                    if (input.isKeyboard()) {
                        require(screen.getFocused() == tab, where + ": the keyboard's focus is on the open page's tab, not "
                                + (screen.getFocused() == null ? "nothing" : key(screen.getFocused())));
                    } else {
                        require(screen.getFocused() == null, where + ": a pointer leaves the focus where it was");
                    }
                }
            }
        } finally {
            page.set(null, before);
        }
        String code = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java"),
                StandardCharsets.UTF_8);
        String tab = code.substring(code.indexOf("private static final class PageTab"));
        require(tab.substring(0, tab.indexOf("record Words")).contains("isFocused()"),
                name + ": the open page's tab shows the keyboard's focus");
    }

    /**
     * The mixer's view: under the title (or the tabs) and above the window's bottom, its scroll exactly the content that
     * does not fit, and every row shorter than the view, so that some scroll shows each row whole: nothing of a scrolled
     * mixer is out of reach.
     */
    private static void mixerView(PanelLayout layout, String where) {
        int top = layout.mixerViewTop();
        int bottom = layout.mixerViewBottom();
        float under = layout.paged() ? layout.header() : layout.titleY() + layout.titleFace().capHeight() + 1;
        require(top >= under - 0.01F, where + ": the mixer's view starts under the " + (layout.paged() ? "tabs" : "title")
                + ", " + top + " < " + under);
        require(bottom <= layout.height() - 2, where + ": the mixer's view ends inside the window");
        require(layout.mixerScrollMax() == Math.max(0, layout.mixerContentBottom() - bottom),
                where + ": the mixer scrolls exactly as far as its content runs past its view");
        require(layout.mixerStep() > layout.rowHeight(), where + ": a notch of the wheel moves the mixer by a row");
        for (Map.Entry<Control, Placed> entry : layout.controls().entrySet()) {
            if (entry.getKey().library()) {
                continue;
            }
            Rect rect = entry.getValue().rect();
            require(rect.y() >= top + 1 - 0.01F && rect.bottom() <= layout.mixerContentBottom() + 0.01F
                    && rect.h() <= bottom - top - 2, where + ": " + entry.getKey() + " can be scrolled into the view whole");
        }
    }

    /**
     * A laptop's window at GUI scale 2 or 3: both columns, the camera, the library and the mixer, on one screen, with the
     * mixer scrolling where it is taller than the window. These showed the two pages before, for the mixer's height alone.
     */
    private static void shortWindow(PanelLayout layout, GameTextMeasurer measurer, DjPanelScreen.Words words, String name) {
        String where = name + " " + layout.width() + "x" + layout.height();
        require(!layout.paged(), where + ": the camera, the library and the mixer show side by side, not as two pages; "
                + PanelLayout.build(layout.width(), layout.height(), measurer, words, false).problems());
    }

    /**
     * The real screen, without a game, on a 427x240 window whose mixer scrolls: the mixer's widgets are children but not
     * renderables (only the clipped mixer view draws them, never Screen.render over the title), its own methods move them by
     * the scroll, the keyboard's focus on a row below the view scrolls the mixer until that row is whole in the view and on
     * a row above it scrolls back, a pointer's focus leaves the scroll alone, the wheel moves a row a notch within the
     * range, and a press where a row is scrolled out of the view does not reach it.
     */
    private static void mixerScrollFollowsTheFocus(GameTextMeasurer measurer, DjPanelScreen.Words words, String name)
            throws Exception {
        PanelLayout layout = PanelLayout.compute(427, 240, measurer, words);
        require(!layout.paged() && layout.mixerScrollMax() > 0, name + ": 427x240 scrolls its mixer, as the scroll check needs");
        if (layout.paged() || layout.mixerScrollMax() == 0) {
            return;
        }
        Object unsafe = unsafe();
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        DjPanelScreen screen = (DjPanelScreen) allocate.invoke(unsafe, DjPanelScreen.class);
        net.minecraft.client.Minecraft minecraft = (net.minecraft.client.Minecraft) allocate.invoke(unsafe,
                net.minecraft.client.Minecraft.class);
        field(net.minecraft.client.gui.screens.Screen.class, "minecraft").set(screen, minecraft);
        field(net.minecraft.client.gui.screens.Screen.class, "width").set(screen, 427);
        field(net.minecraft.client.gui.screens.Screen.class, "height").set(screen, 240);
        field(DjPanelScreen.class, "layout").set(screen, layout);
        for (String list : List.of("children", "narratables", "renderables")) {
            field(net.minecraft.client.gui.screens.Screen.class, list).set(screen, new ArrayList<>());
        }
        @SuppressWarnings("unchecked")
        Map<net.minecraft.client.gui.components.AbstractWidget, Integer> mixer = new java.util.LinkedHashMap<>();
        field(DjPanelScreen.class, "mixerWidgets").set(screen, mixer);
        Method addControl = DjPanelScreen.class.getDeclaredMethod("addControl", Control.class,
                net.minecraft.client.gui.components.AbstractWidget.class);
        addControl.setAccessible(true);
        Method scroll = DjPanelScreen.class.getDeclaredMethod("scrollMixer", int.class);
        scroll.setAccessible(true);
        Method outside = DjPanelScreen.class.getDeclaredMethod("outsideMixerView", double.class, double.class);
        outside.setAccessible(true);
        java.lang.reflect.Field scrolled = field(DjPanelScreen.class, "mixerScroll");
        java.lang.reflect.Field input = field(net.minecraft.client.Minecraft.class, "lastInputType");
        java.lang.reflect.Field page = field(DjPanelScreen.class, "page");
        Map<Control, net.minecraft.client.gui.components.AbstractWidget> widgets = new EnumMap<>(Control.class);
        for (Control control : List.of(Control.MODE, Control.TIMELINE, Control.CLOSE, Control.CAMERA)) {
            Rect rect = layout.placed(control).rect();
            net.minecraft.client.gui.components.AbstractWidget widget = silentButton(Math.round(rect.x()),
                    Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), () -> { });
            addControl.invoke(screen, control, widget);
            widgets.put(control, widget);
        }
        String where = name + " 427x240 scrolled mixer";
        require(mixer.size() == 3 && !mixer.containsKey(widgets.get(Control.CAMERA)),
                where + ": the mixer's widgets, and only those, are the ones it scrolls");
        // The mixer's rows are drawn only by renderMixer, cut to the view. As one of the screen's renderables a row would be
        // drawn a second time by Screen.render, with no clip, over the title and past the view's bottom edge.
        @SuppressWarnings("unchecked")
        List<Object> renderables = (List<Object>) field(net.minecraft.client.gui.screens.Screen.class, "renderables")
                .get(screen);
        @SuppressWarnings("unchecked")
        List<Object> listeners = (List<Object>) field(net.minecraft.client.gui.screens.Screen.class, "children").get(screen);
        for (Control control : List.of(Control.MODE, Control.TIMELINE, Control.CLOSE)) {
            net.minecraft.client.gui.components.AbstractWidget row = widgets.get(control);
            require(!renderables.contains(row), where + ": the mixer's " + control
                    + " is not one of the screen's renderables, so only the clipped mixer view draws it");
            require(listeners.contains(row), where + ": the mixer's " + control
                    + " is one of the screen's children, so it takes the pointer and the keyboard");
        }
        require(renderables.contains(widgets.get(Control.CAMERA)) && listeners.contains(widgets.get(Control.CAMERA)),
                where + ": the library's camera row is one of the screen's renderables and children");
        int max = layout.mixerScrollMax();
        int top = layout.mixerViewTop();
        int bottom = layout.mixerViewBottom();
        net.minecraft.client.gui.components.AbstractWidget close = widgets.get(Control.CLOSE);
        net.minecraft.client.gui.components.AbstractWidget mode = widgets.get(Control.MODE);
        int closeY = Math.round(layout.placed(Control.CLOSE).rect().y());
        require(closeY + close.getHeight() > bottom, where + ": Close starts below the view, " + closeY);
        input.set(minecraft, net.minecraft.client.InputType.MOUSE);
        screen.setFocused(close);
        require((int) scrolled.get(screen) == 0, where + ": a pointer's focus leaves the scroll alone");
        input.set(minecraft, net.minecraft.client.InputType.KEYBOARD_TAB);
        screen.setFocused(close);
        int afterClose = (int) scrolled.get(screen);
        require(afterClose > 0 && afterClose <= max && close.getY() + close.getHeight() <= bottom - 1 && close.getY() >= top + 1,
                where + ": Tab to Close scrolls it whole into the view, scroll " + afterClose + ", y " + close.getY());
        require(close.getY() == closeY - afterClose && mode.getY() == Math.round(layout.placed(Control.MODE).rect().y())
                - afterClose, where + ": every mixer widget moves by the scroll");
        require(widgets.get(Control.CAMERA).getY() == Math.round(layout.placed(Control.CAMERA).rect().y()),
                where + ": the library's widgets do not move with the mixer");
        require((boolean) outside.invoke(screen, layout.mixerColumn().x() + 4, top - 2.0),
                where + ": a press above the view is outside it");
        require(!(boolean) outside.invoke(screen, layout.mixerColumn().x() + 4, (top + bottom) / 2.0),
                where + ": a press inside the view is inside it");
        require(!(boolean) outside.invoke(screen, layout.libraryColumn().x() + 4, top - 2.0),
                where + ": the library's column is never the mixer's outside");
        screen.setFocused(mode);
        require(mode.getY() >= top + 1 && (int) scrolled.get(screen) < afterClose,
                where + ": Shift+Tab back to the first row scrolls it into the view again, y " + mode.getY());
        scroll.invoke(screen, max + 50);
        require((int) scrolled.get(screen) == max, where + ": the scroll stops at its end");
        scroll.invoke(screen, -50);
        require((int) scrolled.get(screen) == 0, where + ": the scroll stops at its top");
        Object before = page.get(null);
        double middle = (top + bottom) / 2.0;
        require(screen.mouseScrolled(layout.mixerColumn().centerX(), middle, 0.0, -1.0)
                        && (int) scrolled.get(screen) == layout.mixerStep(),
                where + ": a notch of the wheel over the mixer moves it by a row, " + scrolled.get(screen));
        page.set(null, before);
        // A press on Close's place while Close is scrolled out of the view below: nothing is pressed.
        scroll.invoke(screen, 0);
        boolean[] pressed = {false};
        // The probe is the first child, so it takes a press before any other widget under the pointer.
        net.minecraft.client.gui.components.Button probe = silentButton(Math.round(layout.mixerColumn().x()), bottom - 4,
                40, 16, () -> pressed[0] = true);
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) field(net.minecraft.client.gui.screens.Screen.class, "children").get(screen);
        addControl.invoke(screen, Control.PLAY, probe);
        children.remove(probe);
        children.addFirst(probe);
        // A press plays the click through Minecraft.getInstance(), which a check without a game does not have.
        java.lang.reflect.Field instance = field(net.minecraft.client.Minecraft.class, "instance");
        Object game = instance.get(null);
        instance.set(null, minecraft);
        try {
            boolean taken = screen.mouseClicked(layout.mixerColumn().x() + 2, bottom + 1, 0);
            require(!pressed[0] && !taken, where + ": a press on a row's part cut off below the view does not press it");
            screen.mouseClicked(layout.mixerColumn().x() + 2, bottom - 2, 0);
            require(pressed[0], where + ": a press on the row's part inside the view does press it");
        } finally {
            instance.set(null, game);
        }
    }

    /** A button that makes no click sound, which needs the game's sound registry. */
    private static net.minecraft.client.gui.components.Button silentButton(int x, int y, int width, int height,
                                                                         Runnable press) {
        return new net.minecraft.client.gui.components.Button(x, y, width, height, Component.literal("probe"),
                button -> press.run(), supplier -> Component.literal("probe")) {
            @Override
            public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
            }
        };
    }

    /**
     * The panel substitutes its templates as Minecraft's TranslatableContents does: {@code %s} in turn, a numbered
     * {@code %2$s} by its number without moving the turn on, {@code %%} as a percent sign, anything else as written. A
     * language pack that numbers its placeholders printed them as they stood when only {@code %s} was substituted.
     */
    private static void substitution() {
        String[][] cases = {
                {"%s and %s", "a and b"},
                {"%2$s, then %1$s", "b, then a"},
                {"%1$s %s", "a a"},
                {"%2$s %s %s", "b a b"},
                {"100%%: %s", "100%: a"},
                {"%d stays", "%d stays"},
                {"%3$s is not given", " is not given"},
                {"ends with %", "ends with %"}};
        for (String[] test : cases) {
            String shown = DjPanelScreen.Words.substitute(test[0], "a", "b");
            require(shown.equals(test[1]), "'" + test[0] + "' with a, b reads '" + test[1] + "', not '" + shown + "'");
        }
        DjPanelScreen.Words words = new DjPanelScreen.Words(key -> "%2$s / %1$s", Locale.ROOT);
        require(words.format("any", "1", "2").equals("2 / 1"), "Words.format takes a numbered placeholder by its number");
    }

    private static final String LONG_TITLE = "Вечеринка у бассейна, версия для очень длинного вечера с друзьями";

    /**
     * The line that says what is playing keeps its words and the times whole in its width and cuts only a long title,
     * playing, paused or stopped. It used to be cut at its end, which took the times first. The library page's header
     * says the title and the times alone ({@code titleOnly}).
     */
    private static void stateLines(GameTextMeasurer measurer, DjPanelScreen.Words words, TextStyle face, float width,
                                   boolean titleOnly, String where) {
        // Nunito's digits are one width, so the longest a time can be is any four of them; 99 minutes is the most.
        String times = titleOnly ? "88:88" : "88:88 / 88:88";
        for (String state : List.of("playing", "paused", "stopped")) {
            String template = titleOnly ? "%s" : words.get("screen.music_battle.dj_panel." + state);
            for (String title : List.of("Song", LONG_TITLE)) {
                String line = PanelLayout.stateLine(measurer, template, title, times, face, width);
                fitsText(measurer, line, face, width, where + " state line");
                String[] around = template.split("%s", -1);
                require(line.startsWith(around[0]) && line.endsWith(around[1] + "  " + times),
                        where + ": the state's words and the times stay whole, only the title is cut: " + line);
                String shown = line.substring(around[0].length(), line.length() - (around[1] + "  " + times).length());
                // A cut title keeps a few letters before its ellipsis: twenty units, two or three letters and the dots.
                require(shown.equals(title) || shown.endsWith("…") && measurer.width(shown, face) >= 20.0F,
                        where + ": the title keeps some room: '" + shown + "'");
            }
        }
    }

    /** A tab's language key: its words without the game's language, which this check does not load. */
    private static String key(Object widget) {
        Component message = ((net.minecraft.client.gui.components.AbstractWidget) widget).getMessage();
        return message.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "?";
    }

    private static Object unsafe() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field singleton = type.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return singleton.get(null);
    }

    private static java.lang.reflect.Field field(Class<?> owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** No bitmap-font text, no symbol Nunito lacks, none of the first look's colours, no button set out by hand. */
    private static void source(Path root) throws Exception {
        String code = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java"),
                StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        require(!code.contains("drawString(") && !code.contains("drawCenteredString("), "the panel draws no word in the bitmap font");
        for (String symbol : List.of("✓", "↺", "♫", "★", "↓", "●")) {
            require(!code.contains(symbol), "the panel spells nothing with '" + symbol + "', which Nunito does not have");
        }
        for (String literal : List.of("0x88000000", "0xFFFF77D4")) {
            require(!code.toUpperCase(Locale.ROOT).contains(literal.toUpperCase(Locale.ROOT)),
                    "the monitor's LIVE strip of the first look (" + literal + ") is gone");
        }
        require(!code.contains("FancyButton.create("), "every button of the panel is set out by the layout (PanelButton)");
    }

    /** Every line of a block within the width, and the lines together the text's own words, none broken. */
    private static void whole(GameTextMeasurer measurer, String text, PanelLayout.Block block, float width, String where) {
        for (String line : block.lines()) {
            fitsText(measurer, line, block.face(), width, where + " line");
        }
        require(String.join(" ", block.lines()).equals(String.join(" ", text.split(" +"))), where
                + ": wrapped at spaces only, no word broken: " + block.lines());
    }

    private static void fitsText(GameTextMeasurer measurer, String text, TextStyle face, float room, String where) {
        float width = measurer.width(text, face);
        require(width <= room + 0.01F, where + ": '" + text + "' is " + width + " wide in " + face + ", room " + room);
    }

    private static DjPanelScreen.Words words(Path lang, int language) throws Exception {
        Map<String, String> values = new HashMap<>();
        Path file = lang.resolve(language == 0 ? "en_us.json" : "ru_ru.json");
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject().entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        for (Map.Entry<String, String[]> entry : PROPOSED.entrySet()) {
            values.putIfAbsent(entry.getKey(), entry.getValue()[language]);
        }
        return new DjPanelScreen.Words(key -> {
            String value = values.get(key);
            if (value == null) {
                throw new AssertionError("missing language key " + key);
            }
            return value;
        }, Locale.forLanguageTag(language == 0 ? "en-US" : "ru-RU"));
    }

    private static void require(boolean condition, String message) {
        checks++;
        // The first few hundred are enough to read, but those of the owner's own window are always kept.
        if (!condition && (FAILURES.size() < 400 || message.contains(" 640x360") && FAILURES.size() < 800)) {
            FAILURES.add("FAIL: " + message);
        }
    }

}
