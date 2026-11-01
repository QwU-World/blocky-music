package ru.gora.musicbattle.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.font.FontRenderContext;
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
import java.util.function.UnaryOperator;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import ru.gora.musicbattle.client.ui.FancyEditBox;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * The interface leftovers of the second fix wave, without a Minecraft window: field hints, the exact colour picker, the
 * playlist entry's effects, the speaker dialog's units and numbers, and the name of a beatmap's own effects.
 *
 * <ul>
 *   <li>A FancyEditBox hint that brings no colour of its own is muted, one that does keeps it (the real setHint on an
 *       instance; vanilla draws an uncoloured hint in the near-white of typed text).</li>
 *   <li>The colour picker and the playlist's effects list take their colours from the palette: no literal of the old
 *       teal look and no bitmap-font call is left in either source, the picker's dim is the page colour at the dim it
 *       had, and the effects list's wash is the light one the DJ effect editor lays over the camera and the world.</li>
 *   <li>At every window from 320x240 up, in English and Russian (text measured in Nunito through java.awt, as the other
 *       layout checks do), every label, title and value of those screens fits where it is drawn without being cut, and
 *       the picker's controls stay inside its panel and clear of each other.</li>
 *   <li>The speaker dialog takes its units from the language files and writes numbers with the language's decimal
 *       separator, but never its digits.</li>
 *   <li>English calls a beatmap's own effects "Beatmap FX" everywhere and Russian "FX карты"; no Russian string writes
 *       the decibel as "dB".</li>
 * </ul>
 * What the screens look like in the game this check does not show; the lang assertions need the keys of this wave
 * merged into both language files.
 */
public final class UiLeftoversChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static final int[][] WINDOWS = {{320, 240}, {360, 240}, {400, 300}, {427, 240}, {480, 270}, {512, 384},
            {640, 360}, {640, 480}, {683, 384}, {800, 450}, {960, 540}, {1280, 720}, {1920, 1080}};
    /** The vanilla Done and Cancel the picker's buttons carry, from Minecraft's own language files. */
    private static final Map<String, String[]> VANILLA_BUTTONS = Map.of(
            "en_us", new String[]{"Done", "Cancel"}, "ru_ru", new String[]{"Готово", "Отмена"});
    /** The first look's colours, which the toolkit's palette replaced. */
    private static final List<String> OLD_LITERALS = List.of("0xFF12232D", "0xFF55D8F4", "0xFFE8F6FF", "0xB8080C12",
            "0xFFB9D8E4", "0xFFFF8A8A", "0xFF72EFA6", "0xFF77C7FF", "0xFF101820", "0xFF080C12", "0xD012232D",
            "0xFF72DFF5", "0xFF24566A", "0xCC162832");
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Map<String, Map<String, String>> lang = new HashMap<>();
        for (String code : List.of("en_us", "ru_ru")) {
            lang.put(code, readLang(root.resolve("src/main/resources/assets/music_battle/lang/" + code + ".json")));
        }
        Measurer measurer = new Measurer(root.resolve("src/main/resources/assets/music_battle/font"));
        fieldHints(root);
        colourPicker(root, lang, measurer);
        panelColourTitle(root, lang);
        playlistEffects(root, lang, measurer);
        speakerDialog(lang, measurer);
        djPanelNumbers(root, lang);
        wording(lang);
        kitTooltip(root, lang);
        hoveredControlClear(root, lang);
        fxEditorTips(root);
        djPanelHints(root, lang);
        fxEditorBackground(root);
        leaveDialogTips();
        System.out.println("UI leftovers checks: " + checks);
        FAILURES.stream().limit(40).forEach(System.err::println);
        if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " failed checks");
        System.out.println("UI leftovers regression PASS");
    }

    // ---------------------------------------------------------------- item 2: hints

    private static void fieldHints(Path root) throws Exception {
        FancyEditBox box = allocate(FancyEditBox.class);
        Field hint = EditBox.class.getDeclaredField("hint");
        hint.setAccessible(true);
        int muted = Palette.TEXT_MUTE & 0xFFFFFF;

        box.setHint(Component.literal("Search beatmaps"));
        Component shown = (Component) hint.get(box);
        check(colour(shown) == muted, "a hint without a colour is drawn muted, not in the colour of typed text");
        check(shown.getString().equals("Search beatmaps"), "muting keeps the hint's words");

        box.setHint(Component.translatable("screen.music_battle.dj_panel.search_hint"));
        check(colour((Component) hint.get(box)) == muted, "a translated hint is muted too");

        Component own = Component.literal("NPC").withColor(0x00C0FF);
        box.setHint(own);
        check(hint.get(box) == own && colour(own) == 0x00C0FF, "a hint with its own colour keeps it, as RapGodConfigScreen's do");

        Component mixed = Component.literal("Find ").append(Component.literal("maps").withColor(0x40FF40));
        box.setHint(mixed);
        Component stored = (Component) hint.get(box);
        check(colour(stored) == muted && colour(stored.getSiblings().getFirst()) == 0x40FF40,
                "an uncoloured hint with a coloured part: the rest is muted, the part keeps its colour");
        check(FancyEditBox.mutedHint(null) == null, "no hint stays no hint");

        // The track library's and the playlist picker's search fields are plain EditBoxes (TextStyles.editBox), which
        // the override above does not reach, so they mute their hint where they set it.
        EditBox plain = allocate(EditBox.class);
        plain.setHint(FancyEditBox.mutedHint(Component.translatable("screen.music_battle.track_search")));
        check(colour((Component) hint.get(plain)) == muted, "a plain EditBox given mutedHint draws its hint muted");
        for (String screen : List.of("TrackLibraryScreen", "DjPlaylistTrackPickerScreen")) {
            String code = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/" + screen + ".java"),
                    StandardCharsets.UTF_8);
            int calls = code.split("\\.setHint\\(", -1).length - 1;
            int muting = code.split("\\.setHint\\(FancyEditBox\\.mutedHint\\(", -1).length - 1;
            check(calls > 0 && calls == muting, screen + " sets every hint through FancyEditBox.mutedHint (" + muting
                    + " of " + calls + ")");
        }
    }

    // ---------------------------------------------------------------- the toolkit's tooltip

    /**
     * The settings' note-shape button still had the vanilla tooltip, purple frame and bitmap font, after the rest of the
     * settings had the toolkit's look. It keeps its vanilla Tooltip - the button still decides when the tip shows, under
     * the pointer or on keyboard focus, and the narrator still reads it - but the screen draws it as the toolkit's card
     * (UiTooltip), the card the calibration screen's help was already drawn in, now one class for both.
     *
     * <ul>
     *   <li>The settings screen makes no tooltip vanilla would draw, hands every tooltip to the toolkit's holder before
     *       vanilla sees it and draws the card after every widget; the calibration help is that card, not a copy.</li>
     *   <li>The real holder takes the tooltips it made and leaves any other to vanilla, and the narrator still reads the
     *       hint of one it made.</li>
     *   <li>Measured with Minecraft's own glyph provider at GUI scales 1 to 9, in English and Russian, at every window from
     *       320x240 up with the pointer anywhere in the window's body: the card stays inside the window, never lies under
     *       the pointer, and holds every word of the hint, each line inside it.</li>
     *   <li>When the keyboard brought the tip up (vanilla's below-or-above positioner), the real holder centres the card
     *       under the focused widget - found inside the container that holds it, too - wherever the pointer is: directly
     *       under it, or directly over it where it would run past the bottom, never over the widget itself, and off its
     *       middle only where the window's edge moved it. The card used to start beside the widget's middle as if the
     *       pointer were there and hang off to the right of a wide button, and nothing measured it.</li>
     * </ul>
     */
    private static void kitTooltip(Path root, Map<String, Map<String, String>> lang) throws Exception {
        String settings = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/MusicBattleSettingsScreen.java"));
        check(!settings.contains("Tooltip.create("), "the settings screen makes no tooltip that vanilla would draw");
        check(settings.contains("skin.setTooltip(tooltips.create(Component.translatable(\"music_battle.settings.skin.size_hint\")));"),
                "the note-shape button's hint is made by the toolkit's tooltip holder");
        check(settings.contains("if (!tooltips.offer(tooltip, positioner)) { super.setTooltipForNextRenderPass(tooltip, positioner, focused); }"),
                "the settings screen hands its tooltips to the toolkit before vanilla");
        check(settings.contains("super.render(graphics, mouseX, mouseY, partialTick); "
                        + "tooltips.draw(graphics, mouseX, mouseY, width, height, getFocused());"),
                "the settings screen draws the card after every widget");
        String calibration = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/MusicBattleCalibrationScreen.java"));
        check(calibration.contains("UiTooltip.draw(graphics, hint, hintX, hintY, hintHovered, width, height);")
                        && !calibration.contains("RoundedRect.shadow("),
                "the calibration screen's help is the shared card, not a copy of it");
        check(calibration.contains("UiTooltip.draw(graphics, UiTooltip.placeUnder(TextStyles.measurer(), hint.getString(), "
                        + "hintWidget, width, height));"),
                "the calibration help the keyboard brought up is centred under its slider as the holder's card is");
        String holderSource = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/ui/UiTooltip.java"));
        check(holderSource.contains("Placement placement = placement(TextStyles.measurer(), mouseX, mouseY, width, height, focused);"),
                "the holder draws its card where placement, which this check measures, puts it");
        String card = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/ui/UiTooltip.java"));
        check(!java.util.regex.Pattern.compile("0x[0-9A-Fa-f]{6,8}").matcher(card).find()
                        && card.contains("Palette.BG_3") && card.contains("Palette.LINE") && card.contains("Palette.TEXT_1"),
                "the card is drawn in palette tokens");

        UiTooltip.Deferred holder = new UiTooltip.Deferred();
        Component hint = Component.literal("The hint");
        net.minecraft.client.gui.components.Tooltip ours = holder.create(hint);
        check(holder.offer(ours, new net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner(
                new net.minecraft.client.gui.navigation.ScreenRectangle(0, 0, 10, 10))), "the holder takes a tooltip it made");
        check(!holder.offer(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Other")),
                        net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE),
                "the holder leaves any other tooltip to vanilla");
        List<String> narrated = new ArrayList<>();
        ours.updateNarration(new net.minecraft.client.gui.narration.NarrationElementOutput() {
            @Override
            public void add(net.minecraft.client.gui.narration.NarratedElementType type,
                            net.minecraft.client.gui.narration.NarrationThunk<?> contents) {
                if (type == net.minecraft.client.gui.narration.NarratedElementType.HINT) {
                    contents.getText(narrated::add);
                }
            }

            @Override
            public net.minecraft.client.gui.narration.NarrationElementOutput nest() {
                return this;
            }
        });
        check(narrated.contains("The hint"), "the narrator still reads the hint of a tooltip the toolkit draws");

        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (String code : List.of("en_us", "ru_ru")) {
                for (String key : List.of("music_battle.settings.skin.size_hint", "music_battle.calibration.audio_help",
                        "music_battle.calibration.input_help")) {
                    String text = value(lang.get(code), key);
                    for (int[] window : WINDOWS) {
                        int width = window[0];
                        int height = window[1];
                        String where = code + " " + key + " at " + width + "x" + height + ", GUI scale " + tier;
                        boolean inside = true;
                        boolean clear = true;
                        boolean whole = true;
                        for (int x = 0; x <= width; x += 13) {
                            for (int y = 36; y <= height - 36; y += 13) {
                                UiTooltip.Placement placed = UiTooltip.place(measurer, text, x, y, width, height);
                                ru.gora.musicbattle.client.workshop.layout.Rect panel = placed.panel();
                                inside &= panel.x() >= UiTooltip.MARGIN && panel.right() <= width - UiTooltip.MARGIN
                                        && panel.y() >= UiTooltip.MARGIN && panel.bottom() <= height - UiTooltip.MARGIN;
                                clear &= !panel.contains(x, y);
                                for (String line : placed.lines()) {
                                    whole &= measurer.width(line, TextStyle.CAPTION) <= panel.w() - UiTooltip.PAD_X * 2 + 0.01F;
                                }
                                whole &= String.join(" ", placed.lines()).equals(text.replaceAll("\\s+", " ").strip());
                            }
                        }
                        check(inside, "the card stays inside the window: " + where);
                        check(clear, "the card never lies under the pointer: " + where);
                        check(whole, "the card holds every word of the hint, each line inside it: " + where);
                        keyboardCard(measurer, text, width, height, where);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- the card keeps off the hovered control

    /**
     * A card that did not fit below the pointer flipped above it with its bottom 6 units over the pointer, so on a
     * 20-unit button with the pointer in its lower half it covered the top of the very control it explained (about 4
     * units, measured in the wave-4 shots). UiTooltip.place now takes the hovered control and never lies over it; the
     * holder reads the control from vanilla's own positioner, which AbstractWidget gives every tooltip.
     *
     * <p>Measured with the game's glyph metrics at GUI scales 1, 2, 4 and 9, in English and Russian, for buttons and
     * cards across the whole window (rows at the top, the middle and the bottom, narrow ones at both edges) with the
     * pointer on several points of each: the card stays inside the window, never under the pointer, and never over the
     * control wherever either side of it has room; the holder's pointer card is that card. The old rule is reproduced once
     * to show the case it got wrong. A null or empty control gives the old card exactly.
     */
    private static void hoveredControlClear(Path root, Map<String, Map<String, String>> lang) throws Exception {
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        boolean oldCovered = false;
        int[] tiers = {1, 2, 4, 9};
        for (int tier : tiers) {
            if (tier < TextStyle.MIN_TIER || tier > TextStyle.MAX_TIER) {
                continue;
            }
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (String code : List.of("en_us", "ru_ru")) {
                for (String key : List.of("music_battle.settings.skin.size_hint", "music_battle.calibration.audio_help")) {
                    String text = value(lang.get(code), key);
                    for (int[] window : new int[][]{{320, 240}, {427, 240}, {640, 360}, {960, 540}, {1920, 1080}}) {
                        int width = window[0];
                        int height = window[1];
                        String where = code + " " + key + " at " + width + "x" + height + ", GUI scale " + tier;
                        boolean inside = true;
                        boolean offPointer = true;
                        boolean clear = true;
                        boolean holderSame = true;
                        boolean plainSame = true;
                        int[][] controls = {{8, 30, width - 16, 20}, {8, height / 2 - 10, width - 16, 20},
                                {8, height - 60, width - 16, 20}, {8, height - 26, width - 16, 20}, {4, height - 24, 40, 20},
                                {width - 44, height - 24, 40, 20}, {width / 2 - 60, height - 80, 120, 48},
                                {width - 120, 40, 110, 44}, {width / 2 - 20, height - 40, 40, 16}};
                        UiTooltip.Deferred holder = new UiTooltip.Deferred();
                        net.minecraft.client.gui.components.Tooltip tip = holder.create(Component.literal(text));
                        for (int[] c : controls) {
                            ru.gora.musicbattle.client.workshop.layout.Rect box =
                                    ru.gora.musicbattle.client.workshop.layout.Rect.of(c[0], c[1], c[2], c[3]);
                            for (double fx : new double[]{0.05, 0.5, 0.95}) {
                                for (double fy : new double[]{0.1, 0.5, 0.9}) {
                                    double x = c[0] + c[2] * fx;
                                    double y = c[1] + c[3] * fy;
                                    UiTooltip.Placement placed = UiTooltip.place(measurer, text, x, y, box, width, height);
                                    var panel = placed.panel();
                                    inside &= panel.x() >= UiTooltip.MARGIN - 0.01F && panel.right() <= width - UiTooltip.MARGIN + 0.01F
                                            && panel.y() >= UiTooltip.MARGIN - 0.01F && panel.bottom() <= height - UiTooltip.MARGIN + 0.01F;
                                    offPointer &= !panel.contains(x, y);
                                    boolean room = box.bottom() + UiTooltip.HOVER_GAP + panel.h() <= height - UiTooltip.MARGIN
                                            || box.y() - UiTooltip.HOVER_GAP - panel.h() >= UiTooltip.MARGIN;
                                    boolean overlaps = panel.right() > box.x() && panel.x() < box.right()
                                            && panel.bottom() > box.y() && panel.y() < box.bottom();
                                    clear &= !room || !overlaps;
                                    var old = UiTooltip.place(measurer, text, x, y, width, height).panel();
                                    oldCovered |= room && old.right() > box.x() && old.x() < box.right()
                                            && old.bottom() > box.y() && old.y() < box.bottom();
                                    plainSame &= UiTooltip.place(measurer, text, x, y, null, width, height).panel().equals(old)
                                            && UiTooltip.place(measurer, text, x, y,
                                            ru.gora.musicbattle.client.workshop.layout.Rect.EMPTY, width, height).panel().equals(old);
                                    holder.offer(tip, new net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner(
                                            new net.minecraft.client.gui.navigation.ScreenRectangle(c[0], c[1], c[2], c[3])));
                                    holderSame &= holder.placement(measurer, x, y, width, height, null).panel().equals(panel);
                                }
                            }
                        }
                        check(inside, "the card for a hovered control stays inside the window: " + where);
                        check(offPointer, "the card for a hovered control never lies under the pointer: " + where);
                        check(clear, "the card never covers the hovered control where either side has room: " + where);
                        check(holderSame, "the holder's pointer card keeps off the widget vanilla's positioner names: " + where);
                        check(plainSame, "without a control the card is the pointer's card as before: " + where);
                    }
                }
            }
        }
        check(oldCovered, "the old rule covered a hovered control (reproduced)");
        // The other positioners the holder may be handed carry no widget: the plain pointer card.
        UiTooltip.Deferred holder = new UiTooltip.Deferred();
        net.minecraft.client.gui.components.Tooltip tip = holder.create(Component.literal("A hint"));
        GameTextMeasurer measurer = new GameTextMeasurer(fonts, 2);
        holder.offer(tip, net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE);
        check(holder.placement(measurer, 100, 230, 400, 240, null).panel()
                        .equals(UiTooltip.place(measurer, "A hint", 100, 230, 400, 240).panel()),
                "a tip offered with vanilla's default positioner is the plain pointer card");
    }

    /**
     * The FX editor showed a name the layout had to cut, and a picker card that could not say its name or its
     * description whole, in vanilla's tooltip, purple frame and bitmap font, in exactly the languages dj-dialogs never
     * measures. All four paths now go to the screen's own tip, drawn as the toolkit's card over everything and kept off
     * the label, card or button it explains.
     */
    private static void fxEditorTips(Path root) throws Exception {
        String fx = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java"),
                StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        check(!fx.contains("setTooltipForNextRenderPass("), "the FX editor hands no tip to vanilla's tooltip");
        check(fx.split("\\btip\\(Component\\.literal\\(", -1).length - 1 == 3 && fx.contains("editor.tip(getMessage(), rect);"),
                "the cut label, both pickers' cards and a cut button go to the screen's tip, with what they explain");
        check(fx.contains("UiTooltip.draw(graphics, shownTip, mouseX, mouseY, shownOver, this.width, this.height);"),
                "the FX editor draws the tip as the toolkit's card, kept off what it explains");
        // In the inspector's own layer (+800) a number box's value, which vanilla draws with a shadow
        // and 0.03 forward, printed through the card. The tip goes in the tip layer over it (ui-foreground measures the
        // depths), in the FX editor and for both of the chart editor's tips.
        String flatFx = fx.replaceAll("\\s+", " ");
        check(flatFx.contains("ClientUiForeground.renderTip(graphics.pose(), graphics::flush, () -> { "
                        + "UiTooltip.draw(graphics, shownTip, mouseX, mouseY, shownOver, this.width, this.height);")
                        && !flatFx.contains("ClientUiForeground.render(graphics.pose(), graphics::flush, () -> { "
                        + "UiTooltip.draw(graphics, shownTip"),
                "the FX editor's tip lies in the tip layer, over the inspector's shadowed numbers");
        String editor = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEditorScreen.java"));
        check(editor.contains("ClientUiForeground.renderTip(graphics.pose(), graphics::flush, () -> renderEffectTooltip(graphics, mouseX, mouseY));")
                        && editor.contains("ClientUiForeground.renderTip(graphics.pose(), graphics::flush, () -> tooltips.draw(graphics, mouseX, mouseY, this.width, this.height, getFocused()));"),
                "the chart editor's effect tip and its controls' hints lie in the tip layer, over the BPM and beat-1 boxes");
    }

    /**
     * The DJ panel's own hints - a library card's "×", the card's author, a cut status line -
     * still took the plain pointer card. Placed below, it starts 12 under the pointer; flipped above near the window's
     * bottom, it ends 6 over it, so with the pointer low on the 14-unit "×" it covered about 4 units of the button, and
     * the library card it is about as well. Now the card keeps off the whole library card where either side has room, and
     * off the "×" always. Measured with the game's glyph metrics on the panel's real card sizes (74 and the compact 43,
     * the "×" 14 square, 5 in from the corner) at the top, middle and bottom of windows from 320x240, in English and
     * Russian; the old rule is reproduced once.
     */
    private static void djPanelHints(Path root, Map<String, Map<String, String>> lang) throws Exception {
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Class<?> panel = Class.forName("ru.gora.musicbattle.client.DjPanelScreen");
        Method placement = panel.getDeclaredMethod("hintPlacement", ru.gora.musicbattle.client.ui.TextMeasurer.class,
                String.class, double.class, double.class, ru.gora.musicbattle.client.workshop.layout.Rect.class,
                ru.gora.musicbattle.client.workshop.layout.Rect.class, int.class, int.class);
        placement.setAccessible(true);
        boolean oldCoveredRemove = false;
        for (int tier : new int[] {1, 2}) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (String code : List.of("en_us", "ru_ru")) {
                String remove = value(lang.get(code), "screen.music_battle.dj_panel.library_remove");
                String author = value(lang.get(code), "screen.music_battle.dj_panel.published_by").replace("%s", "QA Author");
                for (int[] window : new int[][] {{320, 240}, {360, 240}, {427, 240}, {640, 360}, {960, 540}}) {
                    int width = window[0];
                    int height = window[1];
                    String where = code + " at " + width + "x" + height + ", GUI scale " + tier;
                    boolean inside = true;
                    boolean offPointer = true;
                    boolean offRemove = true;
                    boolean offCard = true;
                    for (int cardHeight : new int[] {74, 43}) {
                        float cardWidth = Math.min(220, width - 16);
                        for (float cardY : new float[] {34, (height - cardHeight) / 2F, height - cardHeight - 4}) {
                            for (float cardX : new float[] {8, width - 8 - cardWidth}) {
                                var card = ru.gora.musicbattle.client.workshop.layout.Rect.of(cardX, cardY, cardWidth, cardHeight);
                                var cross = ru.gora.musicbattle.client.workshop.layout.Rect.of(cardX + cardWidth - 14 - 5,
                                        cardY + 5, 14, 14);
                                for (double fx : new double[] {0.15, 0.5, 0.9}) {
                                    for (double fy : new double[] {0.2, 0.6, 0.95}) {
                                        double x = cross.x() + cross.w() * fx;
                                        double y = cross.y() + cross.h() * fy;
                                        var placed = ((UiTooltip.Placement) placement.invoke(null, measurer, remove, x, y, card,
                                                cross, width, height)).panel();
                                        inside &= placed.x() >= UiTooltip.MARGIN - 0.01F && placed.right() <= width - UiTooltip.MARGIN + 0.01F
                                                && placed.y() >= UiTooltip.MARGIN - 0.01F && placed.bottom() <= height - UiTooltip.MARGIN + 0.01F;
                                        offPointer &= !placed.contains(x, y);
                                        offRemove &= !placed.overlaps(cross, 0F);
                                        boolean fits = card.bottom() + UiTooltip.HOVER_GAP + placed.h() <= height - UiTooltip.MARGIN
                                                || card.y() - UiTooltip.HOVER_GAP - placed.h() >= UiTooltip.MARGIN;
                                        offCard &= !fits || !placed.overlaps(card, 0F);
                                        oldCoveredRemove |= UiTooltip.place(measurer, remove, x, y, width, height).panel()
                                                .overlaps(cross, 0F);
                                    }
                                }
                                for (double[] at : new double[][] {{0.3, 0.5}, {0.6, 0.9}, {0.1, 0.1}}) {
                                    double x = card.x() + card.w() * at[0];
                                    double y = card.y() + card.h() * at[1];
                                    var placed = ((UiTooltip.Placement) placement.invoke(null, measurer, author, x, y, card, null,
                                            width, height)).panel();
                                    offPointer &= !placed.contains(x, y);
                                    boolean fits = card.bottom() + UiTooltip.HOVER_GAP + placed.h() <= height - UiTooltip.MARGIN
                                            || card.y() - UiTooltip.HOVER_GAP - placed.h() >= UiTooltip.MARGIN;
                                    offCard &= !fits || !placed.overlaps(card, 0F);
                                }
                            }
                        }
                    }
                    // The status line under the library heading, its full width, with a long refusal.
                    var line = ru.gora.musicbattle.client.workshop.layout.Rect.of(8, height - 30, width - 16, 12);
                    String refusal = "QA: this map cannot be added to the booth, because its song is missing on the server";
                    var placedLine = ((UiTooltip.Placement) placement.invoke(null, measurer, refusal, width * 0.4, height - 24.0,
                            line, null, width, height)).panel();
                    // A card nearly as tall as the window leaves no room on either side of it: the hint then keeps off
                    // the "×" at least.
                    var tall = ru.gora.musicbattle.client.workshop.layout.Rect.of(8, 6, width - 16, height - 12);
                    var tallCross = ru.gora.musicbattle.client.workshop.layout.Rect.of(tall.right() - 19, height - 30, 14, 14);
                    for (double fy : new double[] {0.2, 0.6, 0.95}) {
                        double x = tallCross.x() + 7;
                        double y = tallCross.y() + 14 * fy;
                        var placed = ((UiTooltip.Placement) placement.invoke(null, measurer, remove, x, y, tall, tallCross,
                                width, height)).panel();
                        offRemove &= !placed.overlaps(tallCross, 0F) && !placed.contains(x, y);
                    }
                    check(inside, "the DJ panel's hint card stays inside the window: " + where);
                    check(offPointer, "the DJ panel's hint card never lies under the pointer: " + where);
                    check(offRemove, "the hint of a library card's \"×\" never covers the \"×\": " + where);
                    check(offCard, "a library card's hints keep off the card wherever either side has room: " + where);
                    check(!placedLine.overlaps(line, 0F), "a cut status line's card keeps off the line: " + where);
                }
            }
        }
        check(oldCoveredRemove, "the old plain pointer card covered a library card's \"×\" (reproduced)");
        String code = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java"));
        check(code.contains("UiTooltip.draw(graphics, hintPlacement(TextStyles.measurer(), hint.getString(), mouseX, mouseY, over, inner, width, height));")
                        && !code.contains("UiTooltip.draw(graphics, hint, mouseX, mouseY, width, height);"),
                "the DJ panel draws its own hints where hintPlacement puts them, kept off what they explain");
        check(code.contains("pointerHint = workshopStatus; pointerHintOver = new Rect(left, y - 2, maxWidth, TextStyle.CAPTION.capHeight() + 6);")
                        && code.contains("pointerHint = Component.translatable(\"screen.music_battle.dj_panel.library_remove\"); "
                        + "pointerHintOver = hoveredCard; pointerHintInner = removeRect(cardX(hoveredSlot), cardY(hoveredSlot));")
                        && code.contains("pointerHint = Component.translatable(\"screen.music_battle.dj_panel.published_by\", hovered.publishedBy()); "
                        + "pointerHintOver = hoveredCard;"),
                "each of the DJ panel's hints names what it explains: the status line, the card and its \"×\", the card");
    }

    /**
     * D04. The FX editor opened from the title screen has no world behind it: it opens on "Dark", and "World" there shows
     * the title's panorama under the wash instead of a black frame; in a world it opens on "World" as before.
     */
    private static void fxEditorBackground(Path root) throws Exception {
        Class<?> fx = Class.forName("ru.gora.musicbattle.client.TrackEffectEditScreen");
        Field names = fx.getDeclaredField("BACKGROUNDS");
        names.setAccessible(true);
        String[] backgrounds = (String[]) names.get(null);
        Method starting = fx.getDeclaredMethod("startingBackground", boolean.class);
        starting.setAccessible(true);
        int noWorld = (Integer) starting.invoke(null, false);
        int inWorld = (Integer) starting.invoke(null, true);
        check(noWorld >= 0 && noWorld < backgrounds.length && "dark".equals(backgrounds[noWorld]),
                "the FX editor opened without a world starts on the dark background");
        check("world".equals(backgrounds[inWorld]), "the FX editor opened in a world starts on the world");
        String code = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java"));
        check(code.contains("this.backgroundIndex = startingBackground();")
                        && code.contains("return startingBackground(game == null || game.level != null);"),
                "the FX editor picks its first background by whether a world is loaded");
        int world = code.indexOf("if (\"world\".equals(mode)) {");
        int panorama = code.indexOf("if (this.minecraft == null || this.minecraft.level == null) { renderPanorama(graphics, partialTick); "
                + "graphics.fill(0, 0, this.width, this.height, WASH); return false; }");
        int inWorldWash = code.indexOf("graphics.fill(0, 0, this.width, this.height, WASH); return false; } int first", panorama + 1);
        check(world >= 0 && panorama > world && inWorldWash > panorama,
                "\"World\" without a world shows the title's panorama under the wash");
    }

    /**
     * A widget asks the screen on top for its tip, so with the beatmap editor's Leave dialog open the editor's
     * control the keyboard had reached put its hint in vanilla's frame over the dialog. The dialog now drops tips while it
     * draws the editor behind itself and keeps every other. Run on the real dialog class without a game.
     */
    private static void leaveDialogTips() throws Exception {
        Class<?> type = Class.forName("ru.gora.musicbattle.client.TrackEditorScreen$LeaveDialog");
        Object dialog = allocate(type);
        Field deferred = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("deferredTooltipRendering");
        deferred.setAccessible(true);
        net.minecraft.client.gui.screens.Screen screen = (net.minecraft.client.gui.screens.Screen) dialog;
        Method behind = type.getDeclaredMethod("behind", Runnable.class);
        behind.setAccessible(true);
        var positioner = net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE;
        net.minecraft.client.gui.components.Tooltip hint = net.minecraft.client.gui.components.Tooltip.create(Component.literal("Hint"));
        boolean[] threw = {false};
        behind.invoke(dialog, (Runnable) () -> {
            try {
                screen.setTooltipForNextRenderPass(hint, new net.minecraft.client.gui.screens.inventory.tooltip
                        .BelowOrAboveWidgetTooltipPositioner(new net.minecraft.client.gui.navigation.ScreenRectangle(0, 0, 40, 20)), true);
                screen.setTooltipForNextRenderPass(List.of(), positioner, true);
            } catch (RuntimeException reachedVanilla) {
                threw[0] = true;
            }
        });
        check(!threw[0] && deferred.get(dialog) == null, "the editor drawn behind the Leave dialog puts no tip over it");
        screen.setTooltipForNextRenderPass(List.of(), positioner, true);
        check(deferred.get(dialog) != null, "the Leave dialog's own tips still show");
    }

    /**
     * The real holder, offered the tip with vanilla's keyboard positioner for widgets along the whole window - full rows
     * like the note-shape button and narrow ones at both edges, from the top to the bottom - on their own and inside a
     * container, with the pointer anywhere: the card is the same wherever the pointer is, inside the window, directly
     * under the widget (or directly over it where it would run past the bottom) and never over it, and centred on it
     * unless the window's edge moved it. Offered with the pointer's positioner, the card is the pointer's.
     */
    private static void keyboardCard(TextMeasurer measurer, String text, int width, int height, String where) {
        UiTooltip.Deferred holder = new UiTooltip.Deferred();
        net.minecraft.client.gui.components.Tooltip tip = holder.create(Component.literal(text));
        boolean held = true;
        boolean pointerFree = true;
        boolean inside = true;
        boolean beside = true;
        boolean clear = true;
        boolean centred = true;
        boolean pointerCard = true;
        int[][] widgets = {{8, 36, width - 16, 20}, {8, height / 2 - 10, width - 16, 20}, {8, height - 70, width - 16, 20},
                {8, height - 28, width - 16, 20}, {4, 40, 40, 20}, {width - 44, 40, 40, 20}, {width / 2 - 20, height - 40, 40, 16},
                {4, height - 24, 40, 20}, {width - 44, height - 24, 40, 20}};
        for (int[] spot : widgets) {
            Spot widget = new Spot(spot[0], spot[1], spot[2], spot[3]);
            ru.gora.musicbattle.client.workshop.layout.Rect box = ru.gora.musicbattle.client.workshop.layout.Rect.of(
                    spot[0], spot[1], spot[2], spot[3]);
            UiTooltip.Placement first = null;
            for (double[] pointer : new double[][]{{0, 0}, {width * 0.5, height * 0.5}, {width, height}, {spot[0], spot[1]}}) {
                for (net.minecraft.client.gui.components.events.GuiEventListener focused
                        : List.<net.minecraft.client.gui.components.events.GuiEventListener>of(widget, new Holder(widget))) {
                    held &= holder.offer(tip, new net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner(
                            widget.getRectangle()));
                    UiTooltip.Placement placed = holder.placement(measurer, pointer[0], pointer[1], width, height, focused);
                    if (first == null) {
                        first = placed;
                    }
                    pointerFree &= placed.panel().equals(first.panel());
                }
            }
            ru.gora.musicbattle.client.workshop.layout.Rect panel = first.panel();
            inside &= panel.x() >= UiTooltip.MARGIN - 0.01F && panel.right() <= width - UiTooltip.MARGIN + 0.01F
                    && panel.y() >= UiTooltip.MARGIN - 0.01F && panel.bottom() <= height - UiTooltip.MARGIN + 0.01F;
            boolean under = Math.abs(panel.y() - (box.bottom() + UiTooltip.WIDGET_GAP)) < 0.01F;
            boolean over = Math.abs(panel.bottom() - (box.y() - UiTooltip.WIDGET_GAP)) < 0.01F;
            boolean fitsUnder = box.bottom() + UiTooltip.WIDGET_GAP + panel.h() <= height - UiTooltip.MARGIN;
            boolean fitsOver = box.y() - UiTooltip.WIDGET_GAP - panel.h() >= UiTooltip.MARGIN;
            beside &= fitsUnder ? under : !fitsOver || over;
            clear &= !(fitsUnder || fitsOver) || panel.right() <= box.x() || panel.x() >= box.right()
                    || panel.bottom() <= box.y() || panel.y() >= box.bottom();
            boolean pushed = Math.abs(panel.x() - UiTooltip.MARGIN) < 0.01F
                    || Math.abs(panel.right() - (width - UiTooltip.MARGIN)) < 0.01F;
            centred &= Math.abs(panel.centerX() - box.centerX()) < 0.01F || pushed;
            for (double[] pointer : new double[][]{{width * 0.3, height * 0.6}, {spot[0] + 2, spot[1] + 2}}) {
                holder.offer(tip, new net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner(widget.getRectangle()));
                pointerCard &= holder.placement(measurer, pointer[0], pointer[1], width, height, widget).panel()
                        .equals(UiTooltip.place(measurer, text, pointer[0], pointer[1], box, width, height).panel());
            }
        }
        check(held, "the holder takes its tooltip from vanilla's keyboard positioner: " + where);
        check(pointerFree, "the keyboard's card is the same wherever the pointer is, and inside a container: " + where);
        check(inside, "the keyboard's card stays inside the window: " + where);
        check(beside, "the keyboard's card is directly under the widget, or over it where it would run past the bottom: " + where);
        check(clear, "the keyboard's card never lies over its widget: " + where);
        check(centred, "the keyboard's card is centred on its widget unless the window's edge moved it: " + where);
        check(pointerCard, "a tip the pointer brought up is the pointer's card, kept off its widget: " + where);
    }

    /** A focusable widget with a place on the screen, as a FancyButton is to the screen's focus. */
    private static final class Spot implements net.minecraft.client.gui.components.events.GuiEventListener,
            net.minecraft.client.gui.layouts.LayoutElement {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        Spot(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override public void setFocused(boolean focused) { }
        @Override public boolean isFocused() { return true; }
        @Override public void setX(int x) { }
        @Override public void setY(int y) { }
        @Override public int getX() { return x; }
        @Override public int getY() { return y; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public void visitWidgets(java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> visitor) { }

        @Override
        public net.minecraft.client.gui.navigation.ScreenRectangle getRectangle() {
            return new net.minecraft.client.gui.navigation.ScreenRectangle(x, y, width, height);
        }
    }

    /** A container holding the focused widget, as a scrolling list does: a screen's getFocused() is its own child. */
    private static final class Holder implements net.minecraft.client.gui.components.events.ContainerEventHandler {
        private final net.minecraft.client.gui.components.events.GuiEventListener focused;

        Holder(net.minecraft.client.gui.components.events.GuiEventListener focused) {
            this.focused = focused;
        }

        @Override public List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() { return List.of(focused); }
        @Override public boolean isDragging() { return false; }
        @Override public void setDragging(boolean dragging) { }
        @Override public net.minecraft.client.gui.components.events.GuiEventListener getFocused() { return focused; }
        @Override public void setFocused(net.minecraft.client.gui.components.events.GuiEventListener listener) { }
    }

    /** The source with comments taken out and every run of whitespace made one space. */
    private static String flat(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ").replaceAll("\\s+", " ");
    }

    private static int colour(Component component) {
        TextColor colour = component.getStyle().getColor();
        return colour == null ? -1 : colour.getValue();
    }

    // ---------------------------------------------------------------- item 1: the colour picker

    private static void colourPicker(Path root, Map<String, Map<String, String>> lang, Measurer measurer) throws Exception {
        check(staticInt(ExactColorPickerScreen.class, "SCRIM") == Palette.withAlpha(Palette.BG_0, 0.72F)
                        && staticInt(ExactColorPickerScreen.class, "SCRIM") >>> 24 == 0xB8,
                "the picker dims the screen under it with the page colour, at the 72% it always used");
        noOldLook(root.resolve("src/main/java/ru/gora/musicbattle/client/ExactColorPickerScreen.java"));

        ExactColorPickerScreen picker = allocate(ExactColorPickerScreen.class);
        int margin = staticInt(ExactColorPickerScreen.class, "PANEL_MARGIN");
        int hueWidth = staticInt(ExactColorPickerScreen.class, "HUE_WIDTH");
        for (int[] window : WINDOWS) {
            picker.width = window[0];
            picker.height = window[1];
            String at = window[0] + "x" + window[1];
            int left = call(picker, "panelLeft");
            int top = call(picker, "panelTop");
            int width = call(picker, "panelWidth");
            int height = call(picker, "panelHeight");
            int fieldLeft = call(picker, "pickerLeft");
            int fieldTop = call(picker, "pickerTop");
            int size = call(picker, "pickerSize");
            int hueLeft = call(picker, "hueLeft");
            int controlsX = call(picker, "controlsX");
            int controlsWidth = call(picker, "controlsWidth");
            int buttonsY = top + height - 28;
            check(left >= 8 && top >= 8 && left + width <= window[0] - 8 && top + height <= window[1] - 8,
                    at + ": the panel stays 8 units inside the window");
            check(fieldTop >= top + 34 && top + 8 + TextFit.boxHeight(TextStyle.TITLE) <= top + 26,
                    at + ": the heading sits above its hairline and the controls below it");
            check(fieldLeft + size + 8 <= hueLeft && hueLeft + hueWidth + 10 <= controlsX,
                    at + ": the colour field, the hue strip and the controls do not touch");
            check(controlsX + controlsWidth <= left + width - margin, at + ": the controls end inside the panel");
            check(fieldTop + size + 6 <= buttonsY, at + ": the colour field ends above the buttons");
            int swatchTop = fieldTop + 94;
            int swatchBottom = Math.max(swatchTop + 20, Math.min(fieldTop + size, top + height - 40));
            check(swatchBottom + 6 <= buttonsY, at + ": the swatch ends above the buttons");
            int channel = Math.max(24, (controlsWidth - 8) / 3);
            int buttonWidth = (width - margin * 2 - 6) / 2;
            for (String code : lang.keySet()) {
                Map<String, String> strings = lang.get(code);
                String where = at + " " + code;
                fits(measurer, value(strings, "screen.music_battle.color_picker.hex"), TextStyle.LABEL, controlsWidth,
                        where + " HEX label");
                for (String key : List.of("red", "green", "blue")) {
                    fits(measurer, value(strings, "screen.music_battle.color_picker." + key), TextStyle.LABEL, channel,
                            where + " " + key + " label");
                }
                fits(measurer, value(strings, "music_battle.l10n.client.055"), TextStyle.LABEL, controlsWidth,
                        where + " colour label");
                fits(measurer, "#FFFFFF", TextStyle.STRONG, controlsWidth - 8, where + " swatch value");
                for (String label : VANILLA_BUTTONS.get(code)) {
                    fits(measurer, label, TextStyle.BODY, buttonWidth - 10, where + " button " + label);
                }
                for (String title : pickerTitles(strings)) {
                    fits(measurer, title, TextStyle.TITLE, width - margin * 2, where + " title '" + title + "'");
                }
            }
        }
    }

    /** Every title the mod opens the picker with: the note and panel colours, the preview background, the effect colours. */
    private static List<String> pickerTitles(Map<String, String> strings) {
        List<String> titles = new ArrayList<>();
        for (String lane : List.of("left", "down", "up", "right")) {
            titles.add(format(value(strings, "music_battle.settings.lane_color"), value(strings, "music_battle.settings.lane." + lane)));
        }
        titles.add(value(strings, "music_battle.settings.panel_color_title"));
        titles.add(value(strings, "music_battle.l10n.editor.preview_background_title"));
        for (int index = 1; index <= 4; index++) {
            titles.add(value(strings, "music_battle.l10n.fx.label_00" + index));
        }
        titles.add(format(value(strings, "music_battle.l10n.fx.outline_color_number"), "8"));
        return titles;
    }

    /**
     * The panel colour's picker is titled by a key of its own: the button's "Background color: %s" with an empty value
     * used to leave the title hanging on a colon.
     */
    private static void panelColourTitle(Path root, Map<String, Map<String, String>> lang) throws Exception {
        String settings = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/MusicBattleSettingsScreen.java"),
                StandardCharsets.UTF_8);
        check(settings.contains("Component.translatable(\"music_battle.settings.panel_color_title\")")
                        && !settings.contains("\"music_battle.settings.panel_color\", \"\""),
                "the panel colour's picker opens with its own title, not the button's template with an empty value");
        for (String code : lang.keySet()) {
            String title = value(lang.get(code), "music_battle.settings.panel_color_title");
            check(!title.contains("%") && !title.strip().endsWith(":") && !title.isBlank(),
                    code + ": the picker title is whole words without a placeholder or a dangling colon: '" + title + "'");
        }
    }

    // ---------------------------------------------------------------- item 3: the playlist entry's effects

    private static void playlistEffects(Path root, Map<String, Map<String, String>> lang, Measurer measurer) throws Exception {
        int wash = staticInt(DjPlaylistEffectsScreen.class, "WASH");
        check(wash == Palette.withAlpha(Palette.BG_0, 0.28F) && wash >>> 24 <= 0x48,
                "the effects list lies over the camera or the world under the DJ editor's light wash, not an opaque fill");
        int card = staticInt(DjPlaylistEffectsScreen.class, "CARD");
        check(card >>> 24 < 0xFF && (card & 0xFFFFFF) == (Palette.BG_1 & 0xFFFFFF),
                "the card is the palette's panel colour and lets what is behind read through");
        noOldLook(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPlaylistEffectsScreen.java"));
        String source = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPlaylistEffectsScreen.java"),
                StandardCharsets.UTF_8);
        check(!source.contains("@ \"") && !source.contains("\" s\""), "no hard-coded seconds unit is left in a row");

        Method ours = DjPlaylistEffectsScreen.class.getDeclaredMethod("formatTicks", int.class);
        ours.setAccessible(true);
        Method playlist = DjPlaylistScreen.class.getDeclaredMethod("formatTicks", int.class);
        playlist.setAccessible(true);
        boolean same = true;
        for (int ticks = -20; ticks <= 20 * 60 * 20; ticks += 7) {
            same &= ours.invoke(null, ticks).equals(playlist.invoke(null, ticks));
        }
        check(same, "a beatmap effect's start reads like a cue in the playlist the screen is opened from");
        check("00:01.25".equals(ours.invoke(null, 25)), "25 ticks read 00:01.25");

        for (String code : lang.keySet()) {
            Map<String, String> strings = lang.get(code);
            List<String> effects = new ArrayList<>();
            for (Map.Entry<String, String> entry : strings.entrySet()) {
                if (entry.getKey().matches("effect\\.music_battle\\.[a-z_]+")) effects.add(entry.getValue());
            }
            check(effects.size() >= 10, code + ": the effect names are there to measure, " + effects.size());
            for (int[] window : WINDOWS) {
                int width = window[0];
                int height = window[1];
                String where = width + "x" + height + " " + code;
                fits(measurer, value(strings, "screen.music_battle.dj_playlist.entry_fx"), TextStyle.TITLE, width - 48,
                        where + " title");
                for (String mode : List.of("track", "dj", "none", "inherit")) {
                    fits(measurer, value(strings, "screen.music_battle.dj_playlist.fx_" + mode), TextStyle.LABEL,
                            width - 48, where + " source " + mode);
                }
                // The narrowest row: the list scrolls, so the scrollbar takes its 6 units, and the start is the widest.
                int rowRight = width - 16 - 6;
                float start = measurer.width("99:59.95", TextStyle.CAPTION);
                float nameRoom = rowRight - 8 - (start + 6 + 4) - (16 + 10);
                for (String effect : effects) {
                    fits(measurer, "512.  " + effect, TextStyle.BODY, nameRoom, where + " row '" + effect + "'");
                }
                List<String> empty = TextFit.wrap(measurer, value(strings, "screen.music_battle.dj_playlist.no_entry_fx"),
                        TextStyle.BODY, width - 48);
                check(empty.size() <= 2, where + ": the empty list's line wraps to at most two lines, " + empty.size());
                int rows = Math.max(1, (height - 86) / 24);
                check(48 + rows * 24 <= height - 30 - 6, where + ": the rows end above the buttons");
            }
        }
    }

    // ---------------------------------------------------------------- item 6: the speaker dialog

    private static void speakerDialog(Map<String, Map<String, String>> lang, Measurer measurer) {
        Locale ru = Locale.forLanguageTag("ru-ru");
        Locale en = Locale.forLanguageTag("en-us");
        check(SpeakerConfigScreen.decimal(1.5F, true, ru).equals("+1,5"), "Russian writes a decimal comma");
        check(SpeakerConfigScreen.decimal(1.5F, true, en).equals("+1.5"), "English writes a decimal point");
        check(SpeakerConfigScreen.decimal(14.0F, false, Locale.forLanguageTag("de-de")).equals("14,0"), "German, a comma");
        check(SpeakerConfigScreen.decimal(-1.5F, true, Locale.forLanguageTag("ar-sa")).equals("-1.5"),
                "Arabic keeps the digits and the point the fonts can draw: " + SpeakerConfigScreen.decimal(-1.5F, true,
                        Locale.forLanguageTag("ar-sa")));
        check(SpeakerConfigScreen.decimal(2.0F, false, Locale.forLanguageTag("bn-bd")).equals("2.0"), "Bengali keeps ASCII digits");
        check(SpeakerConfigScreen.decimal(-0.04F, true, ru).equals("+0,0"), "a hair under zero is zero, not -0,0");
        check(SpeakerConfigScreen.decimal(-0.04F, false, en).equals("0.0"), "unsigned, a hair under zero is 0.0");
        check(SpeakerConfigScreen.decimal(-12.0F, true, ru).equals("-12,0"), "a cut keeps its minus");
        check(SpeakerConfigScreen.decimal(64.0F, false, Locale.ROOT).equals("64.0"), "the root locale writes a point");

        Map<String, String[]> expected = Map.of(
                "en_us", new String[]{"Bass: +1.5 dB", "Range: 14.0 blocks", "Volume: 100%", "Treble: -12.0 dB"},
                "ru_ru", new String[]{"Низкие: +1,5 дБ", "Дальность: 14,0 блока", "Громкость: 100%", "Высокие: -12,0 дБ"});
        for (String code : lang.keySet()) {
            Map<String, String> strings = lang.get(code);
            UnaryOperator<String> templates = key -> raw(strings, key);
            Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
            String[] want = expected.get(code);
            String[] got = {
                    SpeakerConfigScreen.message(SpeakerConfigScreen.Unit.DECIBELS, value(strings, "music_battle.l10n.client.112"), 1.5F, locale, templates),
                    SpeakerConfigScreen.message(SpeakerConfigScreen.Unit.BLOCKS, value(strings, "music_battle.l10n.client.110"), 14.0F, locale, templates),
                    SpeakerConfigScreen.message(SpeakerConfigScreen.Unit.PERCENT, value(strings, "music_battle.l10n.client.111"), 1.0F, locale, templates),
                    SpeakerConfigScreen.message(SpeakerConfigScreen.Unit.DECIBELS, value(strings, "music_battle.l10n.client.114"), -12.0F, locale, templates)};
            for (int index = 0; index < want.length; index++) {
                check(got[index].equals(want[index]), code + ": the dialog reads '" + want[index] + "', not '" + got[index] + "'");
            }
            check(SpeakerConfigScreen.valuePart(got[0], value(strings, "music_battle.l10n.client.112"))
                    .equals(want[0].substring(want[0].indexOf(": ") + 2)), code + ": the value line shows the value alone");

            String[] bands = {value(strings, "music_battle.l10n.client.112"), value(strings, "music_battle.l10n.client.113"),
                    value(strings, "music_battle.l10n.client.114")};
            float bandText = SpeakerConfigScreen.bandText(measurer, bands, locale, templates);
            for (int[] window : WINDOWS) {
                String where = window[0] + "x" + window[1] + " " + code;
                int panel = SpeakerConfigScreen.panelWidth(window[0], bandText);
                int inner = panel - 24;
                int band = (inner - 12) / 3;
                int last = inner - (band + 6) * 2;
                check((window[0] - panel) / 2 >= 6, where + ": the dialog keeps at least 6 units from the window's sides");
                check(panel <= 380, where + ": the dialog is never wider than a Workshop dialog, " + panel);
                check(218 <= window[1] - 12, where + ": the dialog fits the window's height");
                for (int index = 0; index < 3; index++) {
                    int sliderWidth = index == 2 ? last : band;
                    for (float end : new float[]{-12.0F, 12.0F, 0.0F}) {
                        String message = SpeakerConfigScreen.message(SpeakerConfigScreen.Unit.DECIBELS, bands[index], end, locale, templates);
                        float valueWidth = measurer.width(SpeakerConfigScreen.valuePart(message, bands[index]), TextStyle.BODY);
                        fits(measurer, bands[index], TextStyle.BODY, sliderWidth - valueWidth - 6,
                                where + " band '" + bands[index] + "' beside " + end);
                    }
                }
                rowFits(measurer, strings, locale, templates, SpeakerConfigScreen.Unit.BLOCKS, "music_battle.l10n.client.110",
                        new float[]{2.0F, 64.0F}, inner, where);
                rowFits(measurer, strings, locale, templates, SpeakerConfigScreen.Unit.PERCENT, "music_battle.l10n.client.111",
                        new float[]{0.0F, 2.0F}, inner, where);
                fits(measurer, value(strings, "music_battle.l10n.client.109"), TextStyle.TITLE, panel - 24 - 17, where + " heading");
            }
        }
        // Before the widening the narrowest window cut the Russian band names; this pins that it no longer can.
        check(SpeakerConfigScreen.panelWidth(320, 89.4F) == 306 && SpeakerConfigScreen.panelWidth(640, 89.4F) == 380,
                "only a window too narrow for the bands widens the dialog");
    }

    /**
     * The DJ panel's speaker sliders write the range and the EQ gains as the speaker dialog does, so one setting does not
     * read "+1,5 дБ" in the dialog and "+1.5 дБ" on the panel.
     */
    private static void djPanelNumbers(Path root, Map<String, Map<String, String>> lang) throws Exception {
        String code = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java"),
                StandardCharsets.UTF_8);
        check(code.contains("case DECIBELS -> SpeakerConfigScreen.decimal(setting(), true, SpeakerConfigScreen.playerLocale());")
                        && code.contains("case NUMBER -> SpeakerConfigScreen.decimal(setting(), false, SpeakerConfigScreen.playerLocale());"),
                "the DJ panel's gain and range sliders format their number through the speaker dialog's decimal()");
        Map<String, String[]> expected = Map.of(
                "en_us", new String[]{"Bass: +1.5 dB", "Range: 14.0 blocks"},
                "ru_ru", new String[]{"Низкие: +1,5 дБ", "Дальность: 14,0 блока"});
        for (String language : lang.keySet()) {
            Map<String, String> strings = lang.get(language);
            Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
            String gain = String.format(Locale.ROOT, raw(strings, "screen.music_battle.dj_panel.eq_low"),
                    SpeakerConfigScreen.decimal(1.5F, true, locale));
            String range = String.format(Locale.ROOT, raw(strings, "screen.music_battle.dj_panel.radius"),
                    SpeakerConfigScreen.decimal(14.0F, false, locale));
            check(gain.equals(expected.get(language)[0]), language + ": the DJ panel's bass reads '" + expected.get(language)[0]
                    + "', not '" + gain + "'");
            check(range.equals(expected.get(language)[1]), language + ": the DJ panel's range reads '"
                    + expected.get(language)[1] + "', not '" + range + "'");
        }
    }

    private static void rowFits(Measurer measurer, Map<String, String> strings, Locale locale, UnaryOperator<String> templates,
                                SpeakerConfigScreen.Unit unit, String nameKey, float[] ends, int width, String where) {
        String name = value(strings, nameKey);
        for (float end : ends) {
            String message = SpeakerConfigScreen.message(unit, name, end, locale, templates);
            float valueWidth = measurer.width(SpeakerConfigScreen.valuePart(message, name), TextStyle.BODY);
            fits(measurer, name, TextStyle.BODY, width - valueWidth - 6, where + " row '" + name + "' at " + end);
        }
    }

    // ---------------------------------------------------------------- item 7 and the decibel: one term each

    private static void wording(Map<String, Map<String, String>> lang) {
        Map<String, String> en = lang.get("en_us");
        Map<String, String> ru = lang.get("ru_ru");
        check(value(en, "screen.music_battle.dj_visual_fx.track_fx").equals("Beatmap FX")
                        && value(en, "screen.music_battle.dj_playlist.fx_track").equals("Beatmap FX"),
                "the DJ effect toggle and the playlist button both say Beatmap FX");
        check(value(ru, "screen.music_battle.dj_visual_fx.track_fx").equals("FX карты")
                        && value(ru, "screen.music_battle.dj_playlist.fx_track").equals("FX карты"),
                "Russian says FX карты on both");
        check(value(en, "guide.music_battle.dj.fx.edit").contains("Beatmap FX")
                        && value(en, "guide.music_battle.dj.playlist.fx").contains("Beatmap FX"),
                "the guide names the switch as the buttons do");
        check(value(ru, "guide.music_battle.dj.fx.edit").contains("FX карты")
                        && value(ru, "guide.music_battle.dj.playlist.fx").contains("FX карты"),
                "the Russian guide names it as the Russian buttons do");
        for (Map.Entry<String, String> entry : en.entrySet()) {
            check(!entry.getValue().contains("Chart FX") && !entry.getValue().contains("chart and DJ effects"),
                    "no English string calls a beatmap's effects chart effects: " + entry.getKey());
        }
        for (Map.Entry<String, String> entry : ru.entrySet()) {
            check(!entry.getValue().matches("(?s).*\\d\\s?dB.*") && !entry.getValue().contains("%s dB"),
                    "Russian writes the decibel as дБ: " + entry.getKey());
        }
        // The editor's gameplay preview showed the English word under the battle in Russian too.
        check(value(ru, "music_battle.l10n.client.119").startsWith("ПРЕДПРОСМОТР")
                        && !value(ru, "music_battle.l10n.client.119").contains("PREVIEW"),
                "the gameplay preview's status line is Russian in Russian: " + value(ru, "music_battle.l10n.client.119"));
    }

    // ---------------------------------------------------------------- helpers

    /** No colour of the first look, and no bitmap-font text call, in the code (comments aside). */
    private static void noOldLook(Path file) throws Exception {
        String code = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        String name = file.getFileName().toString();
        String upper = code.toUpperCase(Locale.ROOT);
        for (String literal : OLD_LITERALS) {
            check(!upper.contains(literal.toUpperCase(Locale.ROOT)), name + " has no " + literal + " of the old look left");
        }
        check(!code.contains("drawString(") && !code.contains("drawCenteredString("),
                name + " draws no text in the bitmap font");
    }

    private static void fits(TextMeasurer measurer, String text, TextStyle style, float room, String what) {
        float width = measurer.width(text, style);
        check(width <= room + 0.01F, what + " fits: " + String.format(Locale.ROOT, "%.1f of %.1f", width, room));
    }

    private static String value(Map<String, String> strings, String key) {
        String value = strings.get(key);
        check(value != null, "the language file has " + key);
        return value == null ? key : value;
    }

    /** What ClientTranslations.text returns: the template as Language loaded it, %d and %.1f already turned into %s. */
    private static String raw(Map<String, String> strings, String key) {
        String value = strings.get(key);
        return value == null ? key : value.replaceAll("%(\\d+\\$)?[\\d.]*[df]", "%$1s");
    }

    private static String format(String template, String argument) {
        return template.replace("%s", argument).replace("%%", "%");
    }

    private static Map<String, String> readLang(Path file) throws Exception {
        JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> strings = new HashMap<>();
        object.entrySet().forEach(entry -> strings.put(entry.getKey(), entry.getValue().getAsString()));
        return strings;
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

    private static int staticInt(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }

    /** Nunito through java.awt, the stand-in the other layout checks use for Minecraft's TTF provider. */
    private static final class Measurer implements TextMeasurer {
        private final Map<String, Font> faces = new HashMap<>();
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
            return text == null || text.isEmpty() ? 0F
                    : (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
        }
    }
}
