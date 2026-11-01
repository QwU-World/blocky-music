package ru.gora.musicbattle.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * The general settings screen measured without a window: SettingsScreenLayout's tabs, controls and preview at every GUI
 * scale Minecraft offers on common screens (from 320x240 up), in English and Russian, with the bundled Nunito measured
 * through java.awt; and BattleBackdrop, through which the battle HUD and the preview both draw the world dimming and the
 * stage bars.
 *
 * <p>Proves: every tab label is drawn whole, never cut, inside its tab, and the tabs keep their reading order, stay in
 * the header and never overlap, on one row whenever one row of full-size labels fits; the controls, the preview, the
 * header and the footer never overlap; the preview's picture is the player's battle screen at its full height with room
 * for the widest panel the width slider allows; every new or renamed control label fits the width the screen gives it;
 * the backdrop's defaults are exactly the old alphas, a setting shown as 0% draws nothing, and the percent a slider shows
 * is the one that is drawn.
 *
 * <p>The strings come from the lang files; a key this change adds or rewords is measured in the proposed wording until
 * the files carry it, so the check means the same before and after the strings are merged.
 *
 * <p>Does not prove: how the screen looks in the game.
 *
 * <p>Usage: java -cp &lt;classpath&gt; ru.gora.musicbattle.client.SettingsLayoutChecks &lt;project root&gt;
 */
public final class SettingsLayoutChecks {
    static int checks;
    private static final float SLACK = 0.01F;
    /** Common framebuffers; each is checked at every GUI scale Minecraft lets it use, 1 up to the largest that fits. */
    private static final int[][] WINDOWS = {
            {640, 480}, {800, 600}, {854, 480}, {960, 540}, {1000, 700}, {1024, 768}, {1280, 720}, {1280, 800},
            {1280, 1024}, {1366, 768}, {1440, 900}, {1600, 900}, {1680, 1050}, {1920, 1080}, {1920, 1200},
            {2560, 1080}, {2560, 1440}, {2560, 1600}, {3440, 1440}, {3840, 2160}
    };
    /** Vanilla's options.on and options.off, which the toggles show; they are Minecraft's strings, not the mod's. */
    private static final Map<String, String[]> VANILLA_ON_OFF = Map.of("en_us", new String[]{"ON", "OFF"},
            "ru_ru", new String[]{"Вкл", "Выкл"});

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Measurer measurer = new Measurer(root.resolve("src/main/resources/assets/music_battle/font"));
        backdrop();
        backdropCallers(root);
        int configurations = 0;
        for (String language : new String[]{"en_us", "ru_ru"}) {
            Strings strings = new Strings(root, language);
            List<String> labels = SettingsScreenLayout.NAVIGATION.stream().map(strings::get).toList();
            for (int[] window : WINDOWS) {
                for (int scale = 1; scale <= largestScale(window[0], window[1]); scale++) {
                    int width = guiSize(window[0], scale);
                    int height = guiSize(window[1], scale);
                    String context = language + " " + window[0] + "x" + window[1] + " scale " + scale + " (" + width + "x" + height + ")";
                    BattleHudScale.Frame hud = BattleHudScale.frame(width, height, scale, largestScale(window[0], window[1]));
                    verify(width, height, hud, measurer, strings, labels, language, context);
                    configurations++;
                }
            }
        }
        System.out.println("Settings layout checks passed: " + checks + " (" + configurations + " window and scale pairs, "
                + "2 languages, backdrop arithmetic)");
    }

    private static void verify(int width, int height, BattleHudScale.Frame hud, TextMeasurer measurer, Strings strings,
                               List<String> labels, String language, String context) {
        SettingsScreenLayout.Nav nav = SettingsScreenLayout.nav(width, height, measurer, labels, 0);
        check(nav.tabs().size() == labels.size() && nav.labels().size() == labels.size(), context + ": a tab for every page");
        check(!nav.small() || Metrics.compact(width, height), context + ": caption-size tabs only on a compact window");
        int rows = (int) nav.tabs().stream().mapToInt(SettingsScreenLayout.Rect::y).distinct().count();
        check(rows == 1 || !fitsOneRow(width, measurer, labels, false), context + ": wraps although one row of tabs fits");
        check(rows == 1 ? nav.header() == SettingsScreenLayout.HEADER : nav.header() > SettingsScreenLayout.HEADER,
                context + ": the header is the calibration screen's height with one row, taller with more");
        int pad = SettingsScreenLayout.tabPadding(nav.small());
        for (int i = 0; i < nav.tabs().size(); i++) {
            SettingsScreenLayout.Rect tab = nav.tabs().get(i);
            String label = nav.labels().get(i);
            check(label.equals(labels.get(i)), context + ": tab " + i + " is cut to \"" + label + "\"");
            fits(measurer, label, nav.style(i), tab.width() - pad * 2, context + ": tab " + i);
            check(tab.x() >= SettingsScreenLayout.NAV_MARGIN - 1 && tab.right() <= width - SettingsScreenLayout.NAV_MARGIN + 1,
                    context + ": tab " + i + " keeps the side margins " + tab);
            check(tab.y() >= 4 && tab.bottom() <= nav.header() - 3, context + ": tab " + i + " stays in the header " + tab);
            check(tab.height() == SettingsScreenLayout.tabHeight(nav.small()), context + ": tab height");
            for (int j = i + 1; j < nav.tabs().size(); j++) {
                SettingsScreenLayout.Rect next = nav.tabs().get(j);
                check(!overlaps(tab, next), context + ": tabs " + i + " and " + j + " overlap");
                check(next.y() > tab.y() || next.y() == tab.y() && next.x() >= tab.right(),
                        context + ": tab " + j + " comes after tab " + i);
            }
        }
        SettingsScreenLayout.Rect selected = nav.tabs().get(nav.selected());
        float rule = nav.ruleY();
        check(rule >= selected.bottom() - SLACK && rule + 1.5F <= nav.header() + SLACK,
                context + ": the accent rule sits under the selected tab, inside the header");
        for (SettingsScreenLayout.Rect tab : nav.tabs()) {
            check(tab == selected || tab.y() <= selected.y() || rule + 1.5F <= tab.y() + SLACK,
                    context + ": the accent rule runs into the next row");
        }

        SettingsScreenLayout layout = SettingsScreenLayout.create(width, height, nav);
        SettingsScreenLayout.Rect controls = layout.controls();
        SettingsScreenLayout.Rect viewport = layout.preview();
        for (SettingsScreenLayout.Rect area : new SettingsScreenLayout.Rect[]{controls, viewport}) {
            check(area.width() > 0 && area.height() > 0, context + ": empty area " + area);
            check(area.x() >= 0 && area.right() <= width, context + ": area leaves the window " + area);
            check(area.y() >= nav.header() && area.bottom() <= height - SettingsScreenLayout.FOOTER,
                    context + ": area runs into the header or the footer " + area);
        }
        check(!overlaps(controls, viewport), context + ": the preview overlaps the controls");

        float captionWidth = measurer.width(strings.get("music_battle.settings.preview"), TextStyle.LABEL);
        // The screen asks BattlePanelRenderer how wide the widest panel is; any width it can answer has to fit, from the
        // smallest panel to the largest one the battle draws.
        for (int widest : new int[]{92, Math.min(320, hud.width() / 2), 248, 320}) {
            verifyPreview(viewport, hud, widest, captionWidth, context + " widest " + widest);
        }

        // The control labels this change adds or rewords, at their widest values, in the widths the screen gives them:
        // a slider cuts its label at its width less 12, a button at less 10, a colour button after its swatch.
        int full = controls.width() - 6;
        int half = (full - 6) / 2;
        String[] onOff = VANILLA_ON_OFF.get(language);
        for (String value : new String[]{"0", "27", "88", "90"}) {
            fits(measurer, percentLabel(strings, "world_dimming", value), TextStyle.CAPTION, full - 12, context + ": dimming");
        }
        for (String value : new String[]{"0", "73", "88", "100"}) {
            fits(measurer, percentLabel(strings, "stage_bar_opacity", value), TextStyle.CAPTION, half - 12, context + ": bar opacity");
            fits(measurer, percentLabel(strings, "panel_opacity", value), TextStyle.CAPTION, half - 12, context + ": panel background");
        }
        for (String state : onOff) {
            fits(measurer, strings.get("music_battle.settings.stage_bars").replace("%s", state), TextStyle.BODY, half - 10,
                    context + ": black bars");
        }
        for (String hex : new String[]{"#000000", "#888888", "#FFFFFF", "#110F14"}) {
            fits(measurer, strings.get("music_battle.settings.panel_color").replace("%s", hex), TextStyle.BODY, full - 25,
                    context + ": panel background colour");
            fits(measurer, hex, TextStyle.BODY, half - 25, context + ": lane colour");
        }
    }

    /** The preview's picture for one widest-panel width: inside its viewport, the full screen height, the panel in it. */
    private static void verifyPreview(SettingsScreenLayout.Rect viewport, BattleHudScale.Frame hud, int widest,
                                      float captionWidth, String context) {
        SettingsScreenLayout.Preview preview = SettingsScreenLayout.preview(viewport, hud.width(), hud.height(), widest, captionWidth);
        SettingsScreenLayout.Rect card = preview.card();
        check(!preview.empty(), context + ": no preview picture");
        check(card.x() >= viewport.x() && card.y() >= viewport.y() && card.right() <= viewport.right()
                && card.bottom() <= viewport.bottom(), context + ": the picture leaves the preview " + card + " in " + viewport);
        check(Math.abs(card.height() / preview.scale() - hud.height()) < 1E-3F,
                context + ": the picture is not the battle screen's full height");
        check(preview.visibleWidth() * preview.scale() >= card.width() - SLACK
                        && (preview.visibleWidth() - 1) * preview.scale() < card.width(),
                context + ": the dimming does not cover the picture, or is drawn far past it");
        // What lies past the picture's edge is clipped; the picture itself is the screen rounded up to whole units.
        check(card.width() < hud.width() * preview.scale() + 1 + SLACK,
                context + ": the picture shows more than a sliver past the battle screen's edge");
        check(preview.needed() == Math.min(hud.width(), SettingsScreenLayout.PANEL_MARGIN * 2 + widest),
                context + ": the room kept is not the widest panel with its margins");
        // As much of the widest panel as the battle screen itself shows: all of it, unless the panel is wider than the
        // screen it is drawn on.
        check(preview.visibleWidth() >= Math.min(hud.width(), SettingsScreenLayout.PANEL_MARGIN + widest),
                context + ": the widest panel the slider allows is cut off");
        int room = preview.captionBeside() ? (int) Math.floor(viewport.width() - captionWidth - SettingsScreenLayout.CAPTION_GAP)
                : viewport.width();
        check(card.width() >= Math.min(room, (int) Math.floor(hud.width() * preview.scale())) - 1,
                context + ": the picture is narrower than both the screen and the room beside its caption");
        float captionHeight = TextFit.boxHeight(TextStyle.LABEL);
        check(preview.captionX() >= viewport.x() - SLACK && preview.captionX() + captionWidth <= viewport.right() + SLACK
                && preview.captionY() >= viewport.y() - SLACK && preview.captionY() + captionHeight <= viewport.bottom() + SLACK,
                context + ": the caption leaves the preview");
        check(preview.captionBeside() ? preview.captionX() + captionWidth <= card.x() + SLACK
                        : preview.captionY() + captionHeight <= card.y() + SLACK,
                context + ": the caption runs into the picture");
    }

    /** One row of every tab in the regular or the small style: the shape the layout must use whenever it fits. */
    private static boolean fitsOneRow(int width, TextMeasurer measurer, List<String> labels, boolean small) {
        int total = (labels.size() - 1) * SettingsScreenLayout.NAV_GAP;
        for (int i = 0; i < labels.size(); i++) {
            total += (int) Math.ceil(TextFit.width(measurer, labels.get(i), SettingsScreenLayout.style(i == 0, small)))
                    + SettingsScreenLayout.tabPadding(small) * 2;
        }
        return total <= width - SettingsScreenLayout.NAV_MARGIN * 2;
    }

    private static String percentLabel(Strings strings, String key, String value) {
        return strings.get("music_battle.settings." + key).replace("%s", value).replace("%%", "%");
    }

    /**
     * The backdrop's arithmetic: the defaults are the battle's old alphas exactly, 0% draws nothing, the alpha only grows
     * with the setting and is never more than half a step from it, values from a hand-edited file are clamped, and the
     * fills cover exactly the old rectangles.
     */
    private static void backdrop() {
        check(BattleBackdrop.dimmingAlpha(BattleBackdrop.DEFAULT_DIMMING) == 70, "the default dimming is not argb(70, 0, 0, 0)");
        check(BattleBackdrop.barAlpha(true, BattleBackdrop.DEFAULT_BAR_OPACITY) == 185, "the default bars are not alpha 185");
        check(BattleBackdrop.percent(BattleBackdrop.DEFAULT_DIMMING) == 27 && BattleBackdrop.percent(BattleBackdrop.DEFAULT_BAR_OPACITY) == 73,
                "the defaults are not shown as 27% and 73%");
        check(BattleBackdrop.dimmingAlpha(0F) == 0 && BattleBackdrop.dimmingAlpha(-1F) == 0 && BattleBackdrop.dimmingAlpha(Float.NaN) == 0,
                "no dimming is not zero");
        check(BattleBackdrop.dimmingAlpha(5F) == Math.round(BattleBackdrop.MAX_DIMMING * 255F)
                        && BattleBackdrop.clampDimming(Float.POSITIVE_INFINITY) == BattleBackdrop.MAX_DIMMING,
                "dimming past the maximum is not clamped to it");
        check(BattleBackdrop.MAX_DIMMING > BattleBackdrop.DEFAULT_DIMMING && BattleBackdrop.MAX_DIMMING < 1F,
                "the dimming cannot be made darker than the default, or can black the world out");
        check(BattleBackdrop.barAlpha(false, 1F) == 0 && BattleBackdrop.barAlpha(true, 0F) == 0
                && BattleBackdrop.barAlpha(true, Float.NaN) == 0 && BattleBackdrop.barAlpha(true, 7F) == 255,
                "the bars' switch or opacity is not honoured");
        int previous = 0;
        for (int step = 0; step <= 90_000; step++) {
            float value = step / 100_000F;
            int alpha = BattleBackdrop.dimmingAlpha(value);
            check(alpha >= previous, "the dimming gets lighter as the setting rises at " + value);
            check((Math.round(value * 100F) == 0) == (alpha == 0), "the dimming shows 0% but draws, or draws nothing at " + value);
            check(Math.round(value * 100F) == BattleBackdrop.percent(value), "the slider shows another percent at " + value);
            check(alpha == 0 || Math.abs(alpha - value * 255F) <= 0.5F, "the dimming's alpha strays from the setting at " + value);
            previous = alpha;
        }
        previous = 0;
        for (int step = 0; step <= 100_000; step++) {
            float value = step / 100_000F;
            int alpha = BattleBackdrop.barAlpha(true, value);
            check(alpha >= previous && (Math.round(value * 100F) == 0) == (alpha == 0),
                    "the bars' alpha and their percent disagree at " + value);
            previous = alpha;
        }
        List<int[]> fills = new ArrayList<>();
        BattleBackdrop.dim((l, t, r, b, c) -> fills.add(new int[]{l, t, r, b, c}), 640, 360, 0);
        BattleBackdrop.bars((l, t, r, b, c) -> fills.add(new int[]{l, t, r, b, c}), 640, 360, 0);
        check(fills.isEmpty(), "0% still draws a fill");
        BattleBackdrop.dim((l, t, r, b, c) -> fills.add(new int[]{l, t, r, b, c}), 640, 360, 70);
        check(fills.size() == 1 && List.of(0, 0, 640, 360, 0x46000000).equals(List.of(fills.get(0)[0], fills.get(0)[1],
                fills.get(0)[2], fills.get(0)[3], fills.get(0)[4])), "the default dimming is not the old full-screen fill");
        fills.clear();
        BattleBackdrop.bars((l, t, r, b, c) -> fills.add(new int[]{l, t, r, b, c}), 640, 360, 185);
        check(fills.size() == 2 && fills.get(0)[0] == 0 && fills.get(0)[1] == 0 && fills.get(0)[2] == 640 && fills.get(0)[3] == 36
                        && fills.get(1)[0] == 0 && fills.get(1)[1] == 330 && fills.get(1)[2] == 640 && fills.get(1)[3] == 360
                        && fills.get(0)[4] == 0xB9000000 && fills.get(1)[4] == 0xB9000000,
                "the default bars are not the old 36 and 30 units at alpha 185");
    }

    /**
     * Both places that draw the standard dimming and bars, the battle HUD and the editor's gameplay preview, draw them
     * through BattleBackdrop with the player's settings, and still only where the beatmap's FX switches let them: the
     * dimming under "not hideStandardDimming", the bars under "not disableStageBars". The old fixed fills are gone.
     */
    private static void backdropCallers(Path root) throws Exception {
        String battle = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/ClientBattleController.java"));
        check(battle.contains("if (!hideHud && !activeHideStandardDimming) { BattleBackdrop.dim(graphics::fill, width, height, "
                        + "ClientBattleSettings.worldDimmingAlpha()); }"),
                "the battle HUD does not dim through BattleBackdrop with the player's dimming under the beatmap's switch");
        check(battle.contains("if (!hideHud && !activeDisableStageBars) { drawStageBars(graphics, width, height);")
                        && battle.contains("private static void drawStageBars(GuiGraphics graphics, int width, int height) { "
                        + "BattleBackdrop.bars(graphics::fill, width, height, ClientBattleSettings.stageBarAlpha()); }"),
                "the battle HUD does not draw the bars through BattleBackdrop with the player's bars under the beatmap's switch");
        String preview = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackGameplayPreviewScreen.java"));
        check(preview.contains("if (showHud && !hideStandardDimming) { BattleBackdrop.dim(graphics::fill, this.width, this.height, "
                        + "ClientBattleSettings.worldDimmingAlpha()); }"),
                "the editor's gameplay preview does not dim as the player's battle does, under the beatmap's switch");
        check(preview.contains("if (!state.hidesBlockyMusicHud() && !disableStageBars) { drawStageBars(graphics); }")
                        && preview.contains("private void drawStageBars(GuiGraphics graphics) { BattleBackdrop.bars(graphics::fill, "
                        + "this.width, this.height, ClientBattleSettings.stageBarAlpha()); }"),
                "the editor's gameplay preview does not draw the bars as the player's battle does, under the beatmap's switch");
        for (String source : new String[]{battle, preview}) {
            check(!source.contains("argb(70, 0, 0, 0)") && !source.contains("argb(185, 0, 0, 0)"),
                    "a fixed dimming or bar fill of the old look is left beside BattleBackdrop");
        }
    }

    /** The source with comments taken out and every run of whitespace made one space, so line breaks do not matter. */
    private static String flat(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ").replaceAll("\\s+", " ");
    }

    /** Minecraft's GUI size for a framebuffer side at a scale: the division rounded up (Window#setGuiScale). */
    static int guiSize(int framebuffer, int scale) {
        int size = framebuffer / scale;
        return framebuffer / (double) scale > size ? size + 1 : size;
    }

    /** Window#calculateScale(0, false): the largest scale at which the GUI is still at least 320 by 240. */
    static int largestScale(int width, int height) {
        int scale = 1;
        while (scale < width && scale < height && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) {
            scale++;
        }
        return scale;
    }

    private static boolean overlaps(SettingsScreenLayout.Rect a, SettingsScreenLayout.Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    private static void fits(TextMeasurer measurer, String text, TextStyle style, float room, String what) {
        float width = TextFit.width(measurer, text, style);
        check(width <= room + SLACK, what + " \"" + text + "\" is " + width + " wide in " + room);
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) {
            throw new AssertionError(message);
        }
    }

    /**
     * Lang file strings, with the wording this change proposes for the keys it adds, and for the keys it rewords while a
     * file still has their old wording.
     */
    private static final class Strings {
        private static final Map<String, String[]> PROPOSED = new HashMap<>();

        static {
            proposed("music_battle.settings.panel_opacity", "Panel background: %s%%",
                    "Фон панели: %s%%",
                    "Background: %s%%", "Фон: %s%%");
            proposed("music_battle.settings.panel_color", "Panel background color: %s",
                    "Цвет фона панели: %s",
                    "Background color: %s", "Цвет фона: %s");
            proposed("music_battle.settings.world_dimming", "World dimming: %s%%",
                    "Затемнение мира: %s%%", null, null);
            proposed("music_battle.settings.stage_bars", "Black bars: %s",
                    "Чёрные полосы: %s", null, null);
            proposed("music_battle.settings.stage_bar_opacity", "Bar opacity: %s%%",
                    "Непрозрачность полос: %s%%",
                    null, null);
        }

        private static void proposed(String key, String english, String russian, String oldEnglish, String oldRussian) {
            PROPOSED.put(key, new String[]{english, russian, oldEnglish, oldRussian});
        }

        private final JsonObject lang;
        private final boolean russian;

        Strings(Path root, String language) throws Exception {
            Path file = root.resolve("src/main/resources/assets/music_battle/lang/" + language + ".json");
            lang = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            russian = language.startsWith("ru");
        }

        String get(String key) {
            String[] proposed = PROPOSED.get(key);
            String current = lang.has(key) ? lang.get(key).getAsString() : null;
            if (proposed != null && (current == null || current.equals(proposed[russian ? 3 : 2]))) {
                return proposed[russian ? 1 : 0];
            }
            if (current == null) {
                throw new AssertionError("no text for " + key);
            }
            return current;
        }
    }

    /** Nunito through java.awt, the stand-in the other layout checks use for Minecraft's TTF provider. */
    private static final class Measurer implements TextMeasurer {
        private final Map<String, Font> faces = new HashMap<>();
        private final Map<String, Float> cache = new HashMap<>();
        private final FontRenderContext context = new FontRenderContext(null, true, true);

        Measurer(Path fontDirectory) throws Exception {
            for (TextStyle style : TextStyle.values()) {
                Font base = faces.get(style.fontFile());
                if (base == null) {
                    base = Font.createFont(Font.TRUETYPE_FONT, fontDirectory.resolve(style.fontFile() + ".ttf").toFile());
                    faces.put(style.fontFile(), base);
                }
                faces.put(style.name(), base.deriveFont(style.size()));
            }
        }

        @Override
        public float width(String text, TextStyle style) {
            if (text == null || text.isEmpty()) {
                return 0F;
            }
            String key = style.name() + "\u0000" + text;
            Float cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            float width = (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
            cache.put(key, width);
            return width;
        }
    }
}
