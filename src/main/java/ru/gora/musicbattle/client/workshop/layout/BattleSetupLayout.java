package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * The battle setup screen: the title bar naming the battle, the live vote countdown under it, a left column with the
 * settings this battle offers and the other voter's choice, a framed map slot on the right, and the footer with the
 * confirm and cancel actions.
 *
 * <p>Pure geometry in the Workshop's own box model, like {@link ListingLayout} and {@link SetPageLayout}: the screen
 * reads these rectangles to draw and to hit-test, and {@code check-vote-layout} measures the very same ones without a
 * GPU. Coordinates are screen coordinates, because the screen does not scroll - everything it shows has to fit, and
 * the elements that may not fit (the other voter's card, the cover, the pills) are laid out only while there is room
 * for them.
 *
 * <p>Every control is named after what it is rather than where it sits ({@code setup.row.speed.opt.x2}), so a press
 * can be carried out by {@link BattleSetup} without either side knowing the order the rows came out in.
 *
 * <p>{@link #warning} lays out the warning about other players' work that the map slot raises, for the same reason:
 * the screen draws it and the {@code vote-warning} check presses it on the same rectangles.
 */
public final class BattleSetupLayout {
    public static final String PANEL = "setup.panel";
    public static final String TITLE_BAR = "setup.titlebar";
    public static final String TIMER = "setup.timer";
    public static final String TIMER_BAR = "setup.timer.bar";
    public static final String LEFT = "setup.left";
    public static final String MAP = "setup.map";
    public static final String SLOT = "setup.map.slot";
    public static final String SLOT_BAR = "setup.map.bar";
    public static final String COVER = "setup.map.cover";
    /** The facts row under the chosen map's name: tempo, length and who made it. */
    public static final String STATS = "setup.map.stats";
    /** Suffix of the card a settings row sits on where the column has the room for cards. */
    public static final String CARD = ".card";
    public static final String VOTER = "setup.voter";
    public static final String FOOTER = "setup.footer";
    public static final String CONFIRM = "setup.confirm";
    public static final String CANCEL = "setup.cancel";
    public static final String WARNING = "warning";
    public static final String WARNING_TOGGLE = "warning.toggle";
    public static final String WARNING_CHECKBOX = "warning.checkbox";
    public static final String WARNING_ACCEPT = "warning.accept";
    public static final String WARNING_CANCEL = "warning.cancel";
    /** The warning's height when none of its lines wraps, which is every window from 426 units up. */
    public static final float WARNING_HEIGHT = 182F;
    private static final String ROW = "setup.row.";
    private static final String OPTION = ".opt.";

    /** The map slot keeps its own column from this content width; below it the slot sits above the settings. */
    public static final float TWO_COLUMN_WIDTH = 400F;
    /** The map slot's share of the content width in two columns, and the bounds it stays inside. */
    private static final float MAP_FRACTION = 0.4F;
    private static final float MAP_MIN_W = 140F;
    private static final float MAP_MAX_W = 340F;
    /** The art's size in a compact slot, and its cap elsewhere, where it may take this share of the slot's height. */
    private static final float COVER_MAX = 96F;
    private static final float ART_MAX = 220F;
    private static final float ART_SHARE = 0.62F;
    private static final float COVER_MIN = 16F;
    /** The placeholder record reads as a picture only from about this size. */
    private static final float PLACEHOLDER_MIN = 30F;
    private static final float STAT_COLUMN_MAX = 92F;
    /** The settings names never take more than this share of the column, however long the longest is. */
    private static final float LABEL_FRACTION = 0.4F;
    /** An option on a settings card is a step larger than in a row: the card has the room, and the row has not. */
    private static final float CARD_OPTION_H = Metrics.BUTTON_H_SM;
    private static final float CARD_OPTION_PAD = 16F;
    private static final float BAR_H = 3F;
    /** An option is as wide as its own label, like the Workshop's filter options, and never narrower than this. */
    private static final float OPTION_MIN_W = 22F;
    private static final float OPTION_PAD = 10F;
    /** Where the warning's heading and its four lines have always started, and the styles they are set in. */
    private static final float[] WARNING_OFFSETS = {15F, 39F, 55F, 69F, 91F};
    private static final TextStyle[] WARNING_STYLES = {TextStyle.STRONG, TextStyle.BODY, TextStyle.CAPTION,
            TextStyle.CAPTION, TextStyle.BODY};
    /** The most lines a note in the map slot takes; every reason a map is refused fits in three at 320 units. */
    private static final int NOTE_LINES = 3;

    /** One choice in a settings row. A disabled option is drawn and hit-tested, and refuses the press. */
    public record Option(String id, String label, boolean enabled) {
    }

    /** One settings row: its label, then its options with the current one marked. */
    public record Row(String id, String label, List<Option> options, int selected) {
    }

    /**
     * What the map slot holds. {@code empty} is the invitation to choose; otherwise the chosen map's words, with
     * {@code note} carrying what is happening to it (its download, or why it cannot be used) and {@code bar} asking
     * for the progress bar at the foot of the slot. {@code cover} says the screen has a picture for the map; without
     * one the art box is still laid out where there is room, and the screen draws its placeholder there. {@code stats}
     * are the facts row. The progress itself is not geometry: the screen fills
     * {@link #SLOT_BAR} with whatever the downloader reports that frame, and the layout stays put.
     */
    public record Slot(boolean empty, String emptyTitle, String emptyHint, String title, String artist,
                       String difficulty, String badge, String note, boolean bar, boolean problem, boolean cover,
                       List<Stat> stats) {
        public Slot {
            stats = stats == null ? List.of() : List.copyOf(stats);
        }
    }

    /** One fact about the chosen map, already translated: "BPM" over "128". */
    public record Stat(String label, String value) {
    }

    /**
     * The words of the warning about other players' work, already translated: the heading, the four lines of the
     * warning itself, the "don't show again" box and the two buttons.
     */
    public record Warning(String heading, List<String> lines, String dontShow, String accept, String back) {
        public Warning {
            lines = List.copyOf(lines);
        }
    }

    /** Everything the setup screen shows. Nothing here touches Minecraft. */
    public record State(String title, String subtitle, String timerLabel, String clock, String settingsHeading,
                        List<Row> rows, String voterTitle, String voterTrack, String voterModes, String mapHeading,
                        Slot slot, String confirmLabel, boolean confirmEnabled, String cancelLabel) {
    }

    private BattleSetupLayout() {
    }

    public static Layout compute(float screenWidth, float screenHeight, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        boolean compact = Metrics.compact(screenWidth, screenHeight);
        float pad = Metrics.pad(screenWidth, screenHeight);
        // The same panel proportions as the Workshop shell, so the two screens read as one family.
        float panelWidth = screenWidth < ShellLayout.FULL_WIDTH_BELOW
                ? screenWidth : Math.round(screenWidth * ShellLayout.WIDE_FRACTION);
        Rect panel = new Rect((screenWidth - panelWidth) * 0.5F, 0, panelWidth, screenHeight);
        layout.add(PANEL, "", Layout.Kind.PANEL, panel);
        float contentX = panel.x() + pad;
        float contentWidth = Math.max(60F, panelWidth - pad * 2);

        float titleH = Metrics.titleBar(screenWidth, screenHeight);
        titleBar(layout, measurer, state, panel, contentX, contentWidth, titleH);
        Rect timer = timer(layout, measurer, state, contentX, contentWidth, titleH, compact);

        float buttonHeight = Metrics.buttonHeight(screenWidth, screenHeight);
        float footerH = buttonHeight + pad * 2;
        Rect footer = new Rect(panel.x(), screenHeight - footerH, panelWidth, footerH);
        layout.add(FOOTER, PANEL, Layout.Kind.PANEL, footer);
        footer(layout, measurer, state, footer, contentX, contentWidth, buttonHeight);

        float bodyTop = timer.bottom() + Metrics.GAP_SM;
        float bodyBottom = Math.max(bodyTop + 40F, footer.y() - Metrics.GAP_SM);
        boolean twoColumns = contentWidth >= TWO_COLUMN_WIDTH;
        Rect left;
        Rect map;
        if (twoColumns) {
            float mapWidth = Math.max(MAP_MIN_W, Math.min(MAP_MAX_W, contentWidth * MAP_FRACTION));
            left = new Rect(contentX, bodyTop, contentWidth - mapWidth - Metrics.GAP, bodyBottom - bodyTop);
            map = new Rect(left.right() + Metrics.GAP, bodyTop, mapWidth, bodyBottom - bodyTop);
        } else {
            // One column: the slot stays the first thing read, and the settings follow under it.
            float mapHeight = Math.min((bodyBottom - bodyTop) * 0.55F, compact ? 72F : 92F);
            map = new Rect(contentX, bodyTop, contentWidth, mapHeight);
            left = new Rect(contentX, map.bottom() + Metrics.GAP_SM, contentWidth,
                    Math.max(20F, bodyBottom - map.bottom() - Metrics.GAP_SM));
        }
        layout.add(LEFT, PANEL, Layout.Kind.PANEL, left);
        layout.add(MAP, PANEL, Layout.Kind.PANEL, map);
        settings(layout, measurer, state, left, twoColumns);
        mapSlot(layout, measurer, state, map, compact);
        return layout;
    }

    /**
     * The warning about other players' work, on the pixels it has always had: a panel 182 units tall in the middle of
     * the screen, its lines at fixed offsets, the "don't show again" row and the two buttons. Its answer is remembered
     * on disk and its handlers are a regression of their own, so the geometry does not move - except that a line too
     * long for a narrow window wraps and pushes what follows down, where it used to lose its last words to an ellipsis.
     * Russian at 320 units is such a window.
     */
    public static Layout warning(float screenWidth, float screenHeight, TextMeasurer measurer, Tr tr, Warning words) {
        Layout layout = new Layout(measurer, tr);
        float panelWidth = Math.min(520F, screenWidth - 28F);
        float room = panelWidth - 36F;
        List<String> texts = new ArrayList<>();
        texts.add(words.heading());
        texts.addAll(words.lines());
        List<List<String>> wrapped = new ArrayList<>(texts.size());
        float grown = 0F;
        for (int i = 0; i < texts.size(); i++) {
            List<String> lines = TextFit.wrap(measurer, texts.get(i), WARNING_STYLES[Math.min(i, WARNING_STYLES.length - 1)],
                    room);
            wrapped.add(lines);
            grown += Math.max(0, lines.size() - 1) * Metrics.lineHeight(WARNING_STYLES[Math.min(i, WARNING_STYLES.length - 1)]);
        }
        float height = WARNING_HEIGHT + grown;
        Rect panel = new Rect((screenWidth - panelWidth) * 0.5F, Math.max(0F, (screenHeight - height) * 0.5F), panelWidth,
                height);
        layout.add(WARNING, "", Layout.Kind.PANEL, panel);
        float shift = 0F;
        for (int i = 0; i < wrapped.size(); i++) {
            TextStyle style = WARNING_STYLES[Math.min(i, WARNING_STYLES.length - 1)];
            float offset = WARNING_OFFSETS[Math.min(i, WARNING_OFFSETS.length - 1)];
            List<String> lines = wrapped.get(i);
            for (int k = 0; k < lines.size(); k++) {
                layout.labelCentered("warning.line." + i + "." + k, WARNING, panel.centerX(),
                        panel.y() + offset + shift + k * Metrics.lineHeight(style), room, lines.get(k), style);
            }
            shift += Math.max(0, lines.size() - 1) * Metrics.lineHeight(style);
        }
        Rect toggle = new Rect(panel.x() + 18F, panel.y() + 109F + shift, panel.w() - 36F, 19F);
        layout.add(WARNING_TOGGLE, WARNING, Layout.Kind.BUTTON, toggle);
        Rect checkbox = new Rect(panel.x() + 18F, panel.y() + 112F + shift, 13F, 13F);
        layout.add(WARNING_CHECKBOX, WARNING_TOGGLE, Layout.Kind.ICON, checkbox);
        layout.label(WARNING_TOGGLE + ".text", WARNING_TOGGLE, checkbox.right() + 7F,
                ShellLayout.capY(TextStyle.CAPTION, checkbox.y(), checkbox.h()), panel.w() - 58F, words.dontShow(),
                TextStyle.CAPTION);
        float buttonWidth = Math.min(190F, (panel.w() - 36F) / 2F);
        float buttonY = panel.bottom() - 38F;
        warningButton(layout, WARNING_ACCEPT, new Rect(screenWidth / 2F - buttonWidth - 6F, buttonY, buttonWidth, 24F),
                words.accept());
        warningButton(layout, WARNING_CANCEL, new Rect(screenWidth / 2F + 6F, buttonY, buttonWidth, 24F), words.back());
        return layout;
    }

    private static void warningButton(Layout layout, String id, Rect box, String label) {
        layout.add(id, WARNING, Layout.Kind.BUTTON, box);
        layout.labelCentered(id + ".text", id, box.centerX(), ShellLayout.capY(TextStyle.BODY, box.y(), box.h()),
                box.w() - 8F, label, TextStyle.BODY);
    }

    /** The id of the clickable box under the pointer, or an empty string; the topmost one wins. */
    public static String hit(Layout layout, double mouseX, double mouseY) {
        List<Layout.Box> boxes = layout.boxes();
        for (int i = boxes.size() - 1; i >= 0; i--) {
            Layout.Box box = boxes.get(i);
            if (box.kind() == Layout.Kind.BUTTON && box.rect().contains(mouseX, mouseY)) {
                return box.id();
            }
        }
        return "";
    }

    /** The row a box belongs to ({@code setup.row.speed.opt.x2} is the speed row), or an empty string. */
    public static String rowOf(String boxId) {
        if (boxId == null || !boxId.startsWith(ROW)) {
            return "";
        }
        int option = boxId.indexOf(OPTION);
        return option < 0 ? boxId.substring(ROW.length()) : boxId.substring(ROW.length(), option);
    }

    /** The option a box is, or an empty string when the box is not one. */
    public static String optionOf(String boxId) {
        if (boxId == null || !boxId.startsWith(ROW)) {
            return "";
        }
        int option = boxId.indexOf(OPTION);
        return option < 0 ? "" : boxId.substring(option + OPTION.length());
    }

    public static String rowId(String row) {
        return ROW + row;
    }

    public static String optionId(String row, String option) {
        return ROW + row + OPTION + option;
    }

    private static void titleBar(Layout layout, TextMeasurer measurer, State state, Rect panel, float contentX,
                                 float contentWidth, float titleH) {
        Rect titleBar = new Rect(panel.x(), 0, panel.w(), titleH);
        layout.add(TITLE_BAR, PANEL, Layout.Kind.PANEL, titleBar);
        Rect icon = new Rect(contentX, (titleH - Metrics.ICON) * 0.5F, Metrics.ICON, Metrics.ICON);
        layout.add("setup.icon", TITLE_BAR, Layout.Kind.ICON, icon);
        // The name of the screen keeps the right end of the bar; who the battle is against takes what is left.
        float subtitleRoom = Math.min(contentWidth * 0.5F, TextFit.width(measurer, state.subtitle(), TextStyle.CAPTION));
        layout.labelRight("setup.subtitle", TITLE_BAR, contentX + contentWidth,
                ShellLayout.capY(TextStyle.CAPTION, 0, titleH), subtitleRoom, state.subtitle(), TextStyle.CAPTION);
        float titleX = icon.right() + Metrics.GAP_SM + 1;
        float titleRoom = Math.max(16F, contentX + contentWidth - subtitleRoom - Metrics.GAP - titleX);
        layout.label("setup.title", TITLE_BAR, titleX, ShellLayout.capY(TextStyle.TITLE, 0, titleH), titleRoom,
                state.title(), TextStyle.TITLE);
    }

    private static Rect timer(Layout layout, TextMeasurer measurer, State state, float contentX, float contentWidth,
                              float titleH, boolean compact) {
        float height = TextFit.boxHeight(TextStyle.CAPTION) + BAR_H + 2F;
        Rect timer = new Rect(contentX, titleH + (compact ? 1F : 2F), contentWidth, height);
        layout.add(TIMER, PANEL, Layout.Kind.PANEL, timer);
        float clockRoom = Math.min(contentWidth * 0.4F, TextFit.width(measurer, state.clock(), TextStyle.LABEL));
        layout.label("setup.timer.label", TIMER, contentX, timer.y(),
                Math.max(10F, contentWidth - clockRoom - Metrics.GAP), state.timerLabel(), TextStyle.CAPTION);
        layout.labelRight("setup.timer.clock", TIMER, contentX + contentWidth, timer.y(), clockRoom, state.clock(),
                TextStyle.LABEL);
        layout.add(TIMER_BAR, TIMER, Layout.Kind.BAR, new Rect(contentX, timer.bottom() - BAR_H, contentWidth, BAR_H));
        return timer;
    }

    /**
     * The settings rows, then the other voter's card under them while there is room for it.
     *
     * <p>Where the column has the room - two columns tall enough for every card and the other voter's - each row becomes
     * a card of its own, its name over its options, set a size larger: at 1920 wide the rows used to fill a sixth of the
     * column in caption type and leave the rest empty. A compact window gets them too when they fit: the solo battle at
     * GUI scale 3 has a single row, and without its card it floated bare beside the boxed map. Everywhere else a row is
     * its name in a measured column with the options beside it.
     * Options that do not fit on one line wrap onto the next while the column has the height for it, and are squeezed
     * only when it has not.
     */
    private static void settings(Layout layout, TextMeasurer measurer, State state, Rect left, boolean twoColumns) {
        float y = left.y();
        if (!state.settingsHeading().isEmpty()) {
            layout.label("setup.settings", LEFT, left.x(), y, left.w(), state.settingsHeading(), TextStyle.STRONG);
            y += TextFit.boxHeight(TextStyle.STRONG) + Metrics.GAP_SM;
        }
        boolean voterWanted = !state.voterTitle().isEmpty();
        float voterHeight = TextFit.boxHeight(TextStyle.LABEL) + TextFit.boxHeight(TextStyle.CAPTION) * 2
                + Metrics.GAP_SM * 2 + Metrics.PAD_SM * 2;
        float voterBlock = voterWanted ? voterHeight + Metrics.GAP : 0F;

        float cardRoom = left.w() - Metrics.PAD * 2;
        float cardsHeight = 0F;
        for (Row row : state.rows()) {
            cardsHeight += cardHeight(measurer, row, cardRoom) + Metrics.GAP_SM;
        }
        boolean cards = twoColumns && y + cardsHeight + voterBlock <= left.bottom();
        if (cards) {
            for (Row row : state.rows()) {
                float height = cardHeight(measurer, row, cardRoom);
                String rowId = rowId(row.id());
                Rect box = new Rect(left.x(), y, left.w(), height);
                layout.add(rowId, LEFT, Layout.Kind.PANEL, box);
                layout.add(rowId + CARD, rowId, Layout.Kind.PANEL, box);
                layout.label(rowId + ".label", rowId, box.x() + Metrics.PAD, box.y() + Metrics.PAD_SM, cardRoom,
                        row.label(), TextStyle.BODY);
                float optionsY = box.y() + Metrics.PAD_SM + TextFit.boxHeight(TextStyle.BODY) + Metrics.GAP_SM + 1F;
                options(layout, measurer, row, rowId, box.x() + Metrics.PAD, optionsY, cardRoom, CARD_OPTION_H,
                        CARD_OPTION_PAD, TextStyle.BODY, TextStyle.STRONG, true);
                y = box.bottom() + Metrics.GAP_SM;
            }
        } else {
            // The names share one column as wide as the widest of them, so "Deadly party" in Russian is read whole
            // instead of losing half a word in a fixed 76 units while the options beside it leave half the row free.
            float widest = 0F;
            for (Row row : state.rows()) {
                widest = Math.max(widest, TextFit.width(measurer, row.label(), TextStyle.CAPTION));
            }
            float labelColumn = Math.min(widest + 1F, left.w() * LABEL_FRACTION);
            float optionsX = left.x() + labelColumn + Metrics.GAP_SM;
            float room = left.right() - optionsX;
            float wrappedHeight = 0F;
            for (Row row : state.rows()) {
                wrappedHeight += inlineHeight(lines(measurer, row, room, OPTION_PAD)) + Metrics.GAP_SM;
            }
            // Wrapping costs height; a window too short for every wrapped row keeps them on one line each, squeezed,
            // because a row pushed off the screen is a setting nobody can change.
            boolean wrap = y + wrappedHeight <= left.bottom();
            for (Row row : state.rows()) {
                float rowHeight = inlineHeight(wrap ? lines(measurer, row, room, OPTION_PAD) : 1);
                if (y + rowHeight > left.bottom()) {
                    break;
                }
                String rowId = rowId(row.id());
                Rect box = new Rect(left.x(), y, left.w(), rowHeight);
                layout.add(rowId, LEFT, Layout.Kind.PANEL, box);
                layout.label(rowId + ".label", rowId, box.x(),
                        ShellLayout.capY(TextStyle.CAPTION, box.y(), Metrics.OPTION_H + 4F), labelColumn, row.label(),
                        TextStyle.CAPTION);
                options(layout, measurer, row, rowId, optionsX, box.y() + 2F, room, Metrics.OPTION_H, OPTION_PAD,
                        TextStyle.CAPTION, TextStyle.LABEL, wrap);
                y = box.bottom() + Metrics.GAP_SM;
            }
        }
        if (!voterWanted) {
            return;
        }
        if (y + voterHeight > left.bottom()) {
            // In a battle this narrow the countdown already says the vote is running; the card is the first thing
            // to give up its room rather than push the settings off the screen.
            return;
        }
        // The card follows the settings it answers rather than sinking to the foot of the column, where a tall window
        // left the two separated by an empty half screen.
        float top = y + (cards ? Metrics.GAP - Metrics.GAP_SM : 0F);
        Rect voter = new Rect(left.x(), top, left.w(), voterHeight);
        layout.add(VOTER, LEFT, Layout.Kind.PANEL, voter);
        float inner = voter.w() - Metrics.PAD_SM * 2;
        float vy = voter.y() + Metrics.PAD_SM + 1F;
        layout.label("setup.voter.title", VOTER, voter.x() + Metrics.PAD_SM, vy, inner, state.voterTitle(), TextStyle.LABEL);
        vy += TextFit.boxHeight(TextStyle.LABEL) + Metrics.GAP_SM;
        layout.label("setup.voter.track", VOTER, voter.x() + Metrics.PAD_SM, vy, inner, state.voterTrack(), TextStyle.CAPTION);
        vy += TextFit.boxHeight(TextStyle.CAPTION) + Metrics.GAP_SM - 1F;
        layout.label("setup.voter.modes", VOTER, voter.x() + Metrics.PAD_SM, vy, inner, state.voterModes(), TextStyle.CAPTION);
    }

    /** A row's options as wide as their own words, measured in the bold style the chosen one is set in. */
    private static float[] optionWidths(TextMeasurer measurer, Row row, float pad, TextStyle style) {
        float[] widths = new float[row.options().size()];
        for (int j = 0; j < widths.length; j++) {
            widths[j] = Math.max(OPTION_MIN_W, TextFit.width(measurer, row.options().get(j).label(), style) + pad);
        }
        return widths;
    }

    /** How many lines a row's options take when they wrap within {@code room}. */
    private static int lines(TextMeasurer measurer, Row row, float room, float pad) {
        return lines(optionWidths(measurer, row, pad, TextStyle.LABEL), room);
    }

    private static int lines(float[] widths, float room) {
        int lines = 1;
        float x = 0F;
        for (float width : widths) {
            if (x > 0F && x + width > room) {
                lines++;
                x = 0F;
            }
            x += width + Metrics.GAP_SM;
        }
        return lines;
    }

    private static float inlineHeight(int lines) {
        return lines * Metrics.OPTION_H + (lines - 1) * Metrics.GAP_SM + 4F;
    }

    private static float cardHeight(TextMeasurer measurer, Row row, float room) {
        int lines = lines(optionWidths(measurer, row, CARD_OPTION_PAD, TextStyle.STRONG), room);
        return Metrics.PAD_SM * 2 + TextFit.boxHeight(TextStyle.BODY) + Metrics.GAP_SM + 1F
                + lines * CARD_OPTION_H + (lines - 1) * Metrics.GAP_SM + 2F;
    }

    /**
     * Lays a row's options from {@code (x, y)}: each as wide as its word, wrapping onto a new line when {@code wrap}
     * allows, otherwise all on one line and squeezed equally when they do not fit.
     */
    private static void options(Layout layout, TextMeasurer measurer, Row row, String rowId, float x, float y, float room,
                                float optionH, float pad, TextStyle plain, TextStyle chosen, boolean wrap) {
        float[] widths = optionWidths(measurer, row, pad, chosen);
        float wanted = 0F;
        for (float width : widths) {
            wanted += width;
        }
        float gaps = Metrics.GAP_SM * Math.max(0, widths.length - 1);
        float scale = !wrap && wanted > 0F && wanted + gaps > room ? Math.max(0F, room - gaps) / wanted : 1F;
        float optionX = x;
        float optionY = y;
        for (int j = 0; j < row.options().size(); j++) {
            Option option = row.options().get(j);
            float optionWidth = Math.min(room, widths[j] * scale);
            if (wrap && optionX > x && optionX + optionWidth > x + room + 0.01F) {
                optionX = x;
                optionY += optionH + Metrics.GAP_SM;
            }
            String id = optionId(row.id(), option.id());
            Rect optionBox = new Rect(optionX, optionY, optionWidth, optionH);
            layout.add(id, rowId, Layout.Kind.BUTTON, optionBox);
            TextStyle style = j == row.selected() ? chosen : plain;
            layout.labelCentered(id + ".text", id, optionBox.centerX(), ShellLayout.capY(style, optionBox.y(), optionH),
                    optionWidth - 3F, option.label(), style);
            optionX = optionBox.right() + Metrics.GAP_SM;
        }
    }

    /**
     * The map slot. What it holds is measured first and then sits in the middle of it: the art (the map's cover, or
     * the screen's placeholder record when it has none), the title, the artist, the difficulty and origin pills, the
     * facts row (tempo, length, who made it) and the note. A slot too short for all of it gives up its elements in a
     * fixed order - the art, then the artist, then the facts, then the title's second line, then the pills - and never
     * the note: the note is the one place that says why a map cannot be played here, or that a vote is held until the
     * map arrives, and a slot that dropped it would show a broken map with no word why.
     */
    private static void mapSlot(Layout layout, TextMeasurer measurer, State state, Rect map, boolean compact) {
        layout.label("setup.map.heading", MAP, map.x(), map.y(), map.w(), state.mapHeading(), TextStyle.STRONG);
        float slotTop = map.y() + TextFit.boxHeight(TextStyle.STRONG) + Metrics.GAP_SM;
        Rect slot = new Rect(map.x(), slotTop, map.w(), Math.max(30F, map.bottom() - slotTop));
        layout.add(SLOT, MAP, Layout.Kind.BUTTON, slot);
        Slot content = state.slot();
        float inset = compact ? Metrics.PAD_SM : Metrics.PAD;
        float inner = slot.w() - inset * 2;
        float top = slot.y() + inset;
        float limit = slot.bottom() - inset - (content.bar() ? BAR_H + Metrics.GAP_SM : 0F);
        if (content.bar()) {
            layout.add(SLOT_BAR, SLOT, Layout.Kind.BAR,
                    new Rect(slot.x() + inset, slot.bottom() - inset - BAR_H, inner, BAR_H));
        }
        float room = Math.max(0F, limit - top);
        TextStyle titleStyle = compact ? TextStyle.STRONG : TextStyle.TITLE;
        if (content.empty()) {
            float iconSize = Math.min(inner * 0.3F, compact ? 18F : 26F);
            float stack = iconSize + Metrics.GAP_SM * 2
                    + linesHeight(measurer, content.emptyTitle(), titleStyle, inner, 2)
                    + linesHeight(measurer, content.emptyHint(), TextStyle.CAPTION, inner, 3);
            float y = top + Math.max(0F, (room - stack) * 0.5F);
            if (y + iconSize + TextFit.boxHeight(titleStyle) <= limit) {
                layout.add("setup.map.icon", SLOT, Layout.Kind.ICON,
                        new Rect(slot.centerX() - iconSize * 0.5F, y, iconSize, iconSize));
                y += iconSize + Metrics.GAP_SM;
            }
            // The hint wraps only once it has to; balanced lines keep a lone last word ("map") off a line of its own.
            y += centeredLines(layout, measurer, "setup.map.empty", slot.centerX(), y, inner, content.emptyTitle(),
                    titleStyle, 2, limit);
            y += Metrics.GAP_SM;
            centeredLines(layout, measurer, "setup.map.hint", slot.centerX(), y,
                    balancedWidth(measurer, content.emptyHint(), TextStyle.CAPTION, inner, 3), content.emptyHint(),
                    TextStyle.CAPTION, 3, limit);
            return;
        }
        float titleLine = Metrics.lineHeight(titleStyle);
        float noteLine = Metrics.lineHeight(TextStyle.CAPTION);
        int titleWanted = Math.max(1, Math.min(2, TextFit.wrap(measurer, content.title(), titleStyle, inner).size()));
        int noteWanted = content.note().isEmpty() ? 0
                : Math.min(NOTE_LINES, TextFit.wrap(measurer, content.note(), TextStyle.CAPTION, inner).size());
        // The title's first line and the note are what the slot is for; the note keeps every line that fits after it.
        int noteLines = Math.max(0, Math.min(noteWanted,
                (int) Math.floor((room - titleLine - Metrics.GAP_SM) / noteLine + 0.001F)));
        float used = titleLine + noteLines * noteLine + (noteLines > 0 ? Metrics.GAP_SM : 0F);
        float pillBlock = content.difficulty().isEmpty() && content.badge().isEmpty() ? 0F
                : Metrics.PILL_H + Metrics.GAP_SM;
        boolean pills = pillBlock > 0F && used + pillBlock <= room;
        used += pills ? pillBlock : 0F;
        int titleLines = titleWanted > 1 && used + titleLine <= room ? 2 : 1;
        used += (titleLines - 1) * titleLine;
        float statsBlock = content.stats().isEmpty() ? 0F
                : TextFit.boxHeight(TextStyle.CAPTION) + 1F + TextFit.boxHeight(TextStyle.LABEL) + Metrics.GAP + 1F;
        boolean stats = statsBlock > 0F && used + statsBlock <= room;
        used += stats ? statsBlock : 0F;
        float artistBlock = content.artist().isEmpty() ? 0F : TextFit.boxHeight(TextStyle.CAPTION) + Metrics.GAP_SM;
        boolean artist = artistBlock > 0F && used + artistBlock <= room;
        used += artist ? artistBlock : 0F;
        // The art takes what is left, up to a cap and a share of the slot, so a tall slot shows the map rather than an
        // empty frame. Without a cover the screen draws its placeholder record there, which needs a little more room
        // than a cover to read as a picture rather than a smudge.
        float artCap = Math.min(Math.min(inner, compact ? COVER_MAX : ART_MAX), room * ART_SHARE);
        float artSize = (float) Math.floor(Math.min(artCap, room - used - Metrics.GAP));
        boolean art = artSize >= (content.cover() ? COVER_MIN : PLACEHOLDER_MIN);
        used += art ? artSize + Metrics.GAP : 0F;

        float y = top + Math.max(0F, (room - used) * 0.5F);
        if (art) {
            layout.add(COVER, SLOT, Layout.Kind.IMAGE, new Rect(slot.centerX() - artSize * 0.5F, y, artSize, artSize));
            y += artSize + Metrics.GAP;
        }
        y += centeredLines(layout, measurer, "setup.map.title", slot.centerX(), y, inner, content.title(),
                titleStyle, titleLines, limit);
        if (artist) {
            layout.labelCentered("setup.map.artist", SLOT, slot.centerX(), y, inner, content.artist(), TextStyle.CAPTION);
            y += artistBlock;
        }
        if (pills) {
            y = pills(layout, measurer, content, slot, inner, y, limit, Metrics.PILL_H);
        }
        if (stats) {
            y = stats(layout, content.stats(), slot, inner, y + Metrics.GAP_SM);
        }
        if (noteLines > 0) {
            y += Metrics.GAP_SM;
            centeredLines(layout, measurer, "setup.map.note", slot.centerX(), y, inner, content.note(),
                    TextStyle.CAPTION, noteLines, limit);
        }
    }

    /**
     * The facts row: up to three columns, each a quiet caption over its value, centred under the pills with a hairline
     * above them ({@link #STATS} is the band the screen draws that line on). Returns the y below the row.
     */
    private static float stats(Layout layout, List<Stat> stats, Rect slot, float inner, float y) {
        int count = Math.min(3, stats.size());
        float column = Math.min(inner / count, STAT_COLUMN_MAX);
        float height = TextFit.boxHeight(TextStyle.CAPTION) + 1F + TextFit.boxHeight(TextStyle.LABEL) + 1F;
        float x = slot.centerX() - column * count * 0.5F;
        layout.add(STATS, SLOT, Layout.Kind.PANEL, new Rect(x, y, column * count, height + Metrics.GAP_SM));
        float rowY = y + Metrics.GAP_SM + 1F;
        for (int i = 0; i < count; i++) {
            Stat stat = stats.get(i);
            float centre = x + column * (i + 0.5F);
            layout.labelCentered(STATS + "." + i + ".label", SLOT, centre, rowY, column - 4F, stat.label(),
                    TextStyle.CAPTION);
            layout.labelCentered(STATS + "." + i + ".value", SLOT, centre, rowY + TextFit.boxHeight(TextStyle.CAPTION) + 1F,
                    column - 4F, stat.value(), TextStyle.LABEL);
        }
        return y + height + Metrics.GAP_SM;
    }

    /**
     * The narrowest width that still wraps {@code text} into as few lines as {@code maxWidth} does, so the lines come
     * out even ("Open the Workshop and choose / a map in it") rather than full lines and an orphan.
     */
    private static float balancedWidth(TextMeasurer measurer, String text, TextStyle style, float maxWidth, int maxLines) {
        if (text == null || text.isEmpty()) {
            return maxWidth;
        }
        int lines = TextFit.wrap(measurer, text, style, maxWidth, maxLines).size();
        if (lines <= 1) {
            return maxWidth;
        }
        float low = maxWidth / (lines + 1);
        float high = maxWidth;
        for (int i = 0; i < 12; i++) {
            float middle = (low + high) * 0.5F;
            if (TextFit.wrap(measurer, text, style, middle, maxLines + 1).size() <= lines) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return Math.min(maxWidth, high + 1F);
    }

    /** The difficulty and the origin as two pills on one centred line; returns the y below them. */
    private static float pills(Layout layout, TextMeasurer measurer, Slot content, Rect slot, float inner, float y,
                               float limit, float pillHeight) {
        if (y + pillHeight > limit) {
            return y;
        }
        float difficultyWidth = content.difficulty().isEmpty() ? 0F
                : Math.min(inner * 0.6F, TextFit.width(measurer, content.difficulty(), TextStyle.PILL) + 10F);
        float badgeWidth = content.badge().isEmpty() ? 0F
                : Math.min(inner * 0.6F, TextFit.width(measurer, content.badge(), TextStyle.PILL) + 10F);
        float total = difficultyWidth + badgeWidth + (difficultyWidth > 0 && badgeWidth > 0 ? Metrics.GAP_SM : 0F);
        if (total <= 0F) {
            return y;
        }
        if (total > inner) {
            // Two long words do not fit side by side; the origin badge is the one that can wait.
            badgeWidth = 0F;
            total = difficultyWidth;
        }
        float x = slot.centerX() - total * 0.5F;
        if (difficultyWidth > 0F) {
            Rect pill = new Rect(x, y, difficultyWidth, pillHeight);
            layout.add("setup.map.difficulty", SLOT, Layout.Kind.PILL, pill);
            layout.labelCentered("setup.map.difficulty.text", "setup.map.difficulty", pill.centerX(),
                    ShellLayout.capY(TextStyle.PILL, pill.y(), pillHeight), difficultyWidth - 4F, content.difficulty(),
                    TextStyle.PILL);
            x = pill.right() + Metrics.GAP_SM;
        }
        if (badgeWidth > 0F) {
            Rect pill = new Rect(x, y, badgeWidth, pillHeight);
            layout.add("setup.map.badge", SLOT, Layout.Kind.PILL, pill);
            layout.labelCentered("setup.map.badge.text", "setup.map.badge", pill.centerX(),
                    ShellLayout.capY(TextStyle.PILL, pill.y(), pillHeight), badgeWidth - 4F, content.badge(),
                    TextStyle.PILL);
        }
        return y + pillHeight + Metrics.GAP_SM;
    }

    private static void footer(Layout layout, TextMeasurer measurer, State state, Rect footer, float contentX,
                               float contentWidth, float buttonHeight) {
        float y = footer.y() + (footer.h() - buttonHeight) * 0.5F;
        float cancelWidth = Math.min(contentWidth * 0.35F,
                Math.max(44F, TextFit.width(measurer, state.cancelLabel(), TextStyle.BODY) + 24F));
        Rect cancel = new Rect(contentX, y, cancelWidth, buttonHeight);
        layout.add(CANCEL, FOOTER, Layout.Kind.BUTTON, cancel);
        layout.labelCentered(CANCEL + ".text", CANCEL, cancel.centerX(),
                ShellLayout.capY(TextStyle.BODY, y, buttonHeight), cancelWidth - 6F, state.cancelLabel(), TextStyle.BODY);
        float confirmWidth = Math.min(contentWidth - cancelWidth - Metrics.GAP,
                Math.max(60F, TextFit.width(measurer, state.confirmLabel(), TextStyle.BODY) + 34F));
        Rect confirm = new Rect(contentX + contentWidth - confirmWidth, y, confirmWidth, buttonHeight);
        layout.add(CONFIRM, FOOTER, Layout.Kind.BUTTON, confirm);
        layout.labelCentered(CONFIRM + ".text", CONFIRM, confirm.centerX(),
                ShellLayout.capY(TextStyle.BODY, y, buttonHeight), confirmWidth - 6F, state.confirmLabel(), TextStyle.BODY);
    }

    /** The height wrapped text will take, before anything is placed, so a slot can centre what it holds. */
    private static float linesHeight(TextMeasurer measurer, String text, TextStyle style, float maxWidth, int maxLines) {
        if (text == null || text.isEmpty()) {
            return 0F;
        }
        return TextFit.wrap(measurer, text, style, maxWidth, maxLines).size() * Metrics.lineHeight(style);
    }

    /**
     * Wrapped text centred on {@code centerX}, line by line ({@link Layout#paragraph} is left-aligned, and the slot
     * is the one place in the Workshop that centres its words). Lines that would pass {@code limit} are left out;
     * returns the height taken.
     */
    private static float centeredLines(Layout layout, TextMeasurer measurer, String id, float centerX, float y,
                                       float maxWidth, String text, TextStyle style, int maxLines, float limit) {
        if (text == null || text.isEmpty()) {
            return 0F;
        }
        List<String> lines = TextFit.wrap(measurer, text, style, maxWidth, maxLines);
        float lineHeight = Metrics.lineHeight(style);
        float used = 0F;
        for (int i = 0; i < lines.size(); i++) {
            float lineY = y + i * lineHeight;
            if (lineY + TextFit.boxHeight(style) > limit) {
                break;
            }
            layout.labelCentered(id + "." + i, SLOT, centerX, lineY, maxWidth, lines.get(i), style);
            used = lineY + lineHeight - y;
        }
        return used;
    }
}
