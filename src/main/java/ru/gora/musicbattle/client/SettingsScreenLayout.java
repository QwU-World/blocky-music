package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * Where the settings screen's parts sit, in GUI units: the tabs in the header, the scrolling controls and the preview
 * of a battle, which never overlap. The screen builds its widgets from these rectangles and draws from the same ones.
 *
 * <p>Pure, like the Workshop's layouts: text is measured through {@link TextMeasurer}, so the settings-layout check runs
 * this with the bundled Nunito in both languages at every window size, without Minecraft.
 */
record SettingsScreenLayout(Rect controls, Rect preview, Nav nav) {
    /** The tabs' lang keys in reading order: General (this screen), Calibration, Beatmap editor, Guide, Workshop. */
    static final List<String> NAVIGATION = List.of("music_battle.calibration.general", "music_battle.calibration.title",
            "music_battle.editor", "music_battle.settings.guide", "screen.music_battle.workshop.nav");
    /** The header with one row of tabs: as tall as the calibration screen's, so the tabs stay put between the two. */
    static final int HEADER = 32;
    /** The bar under everything that holds Done; the controls and the preview end {@code FOOTER + 2} above the bottom. */
    static final int FOOTER = 38;
    static final int NAV_MARGIN = 12;
    static final int NAV_GAP = 4;
    static final int NAV_ROW_GAP = 6;
    /** The panel's distance from the screen edge in the battle HUD (BattlePanelRenderer.layout, side 0). */
    static final int PANEL_MARGIN = 20;
    /** The panel width slider's largest value; the preview keeps room for the panel the battle draws at it. */
    static final float MAX_PANEL_WIDTH_SCALE = 1.6F;
    /** The caption's line above the preview picture, and the gap beside it when it sits to the picture's left. */
    static final int CAPTION_ROW = 13;
    static final int CAPTION_GAP = 10;
    /** How much bigger the picture must get before the caption moves beside it; see {@link #preview}. */
    static final float BESIDE_GAIN = 1.15F;

    /** From this width the controls and the preview sit side by side; below it the preview sits above the controls. */
    private static final int WIDE = 620;

    /**
     * The header's tabs. Each is as wide as its label in the style it is drawn in, plus padding, and every row is
     * centred: the Workshop sizes its tabs the same way. When the labels do not fit one row the tabs wrap into two, then
     * three; on a window the Workshop calls compact the labels first drop to the kit's caption size, the size its own
     * short buttons use, before a second row costs the controls their space. A label is cut with an ellipsis only when
     * even three rows of captions cannot hold it, which neither shipped language needs at 320 units.
     */
    record Nav(List<Rect> tabs, List<String> labels, int selected, int header, boolean small) {
        Nav {
            tabs = List.copyOf(tabs);
            labels = List.copyOf(labels);
        }

        /** The style a tab's label is measured and drawn in: the selected one bold, as the Workshop's active tab is. */
        TextStyle style(int index) {
            return SettingsScreenLayout.style(index == selected, small);
        }

        /**
         * The accent rule under the selected tab: on the header's lower edge when the tab is in the last row, where the
         * calibration screen and the track library draw theirs, or in the gap right under it on an earlier row.
         */
        float ruleY() {
            if (selected < 0 || selected >= tabs.size()) {
                return header - 1.5F;
            }
            Rect tab = tabs.get(selected);
            boolean lastRow = tabs.stream().noneMatch(other -> other.y() > tab.y());
            return lastRow ? header - 1.5F : tab.bottom() + (NAV_ROW_GAP - 1.5F) * 0.5F;
        }
    }

    /**
     * The battle the preview shows. The picture is a miniature of the player's own battle screen (the canvas
     * {@link BattleHudScale} gives the HUD in this window) at its full height, so both stage bars sit at its edges as
     * they will in a battle; on the right it is cut where the viewport ends, but never short of {@link #needed}, the room
     * the widest panel the width slider allows takes with its margins. The caption sits above the picture, or beside it
     * when that leaves the picture bigger (the short strip of the stacked layout).
     */
    record Preview(Rect card, float scale, int canvasWidth, int canvasHeight, int visibleWidth, int needed,
                   float captionX, float captionY, boolean captionBeside) {
        boolean empty() {
            return card.width() <= 0 || card.height() <= 0 || !(scale > 0);
        }
    }

    static TextStyle style(boolean selected, boolean small) {
        return small ? (selected ? TextStyle.LABEL : TextStyle.CAPTION) : (selected ? TextStyle.STRONG : TextStyle.BODY);
    }

    static int tabHeight(boolean small) {
        return small ? 16 : 20;
    }

    /** Room either side of a label: enough that the hover fill reads as a tab and not as a highlight on the word. */
    static int tabPadding(boolean small) {
        return small ? 7 : 9;
    }

    static Nav nav(int width, int height, TextMeasurer measurer, List<String> labels, int selected) {
        int room = Math.max(1, width - NAV_MARGIN * 2);
        boolean compact = Metrics.compact(width, height);
        int[][] shapes = compact
                ? new int[][]{{1, 0}, {1, 1}, {2, 0}, {2, 1}, {3, 1}}
                : new int[][]{{1, 0}, {2, 0}, {2, 1}, {3, 1}};
        for (int[] shape : shapes) {
            Nav nav = arrange(width, measurer, labels, selected, shape[0], shape[1] == 1, room, false);
            if (nav != null) {
                return nav;
            }
        }
        return arrange(width, measurer, labels, selected, 3, true, room, true);
    }

    /** One shape of the tab rows, or null when a row is wider than {@code room} and cutting labels is not allowed. */
    private static Nav arrange(int width, TextMeasurer measurer, List<String> labels, int selected, int rows,
                               boolean small, int room, boolean cut) {
        int count = labels.size();
        int tabHeight = tabHeight(small);
        int pad = tabPadding(small);
        // One row centres a 20-unit tab like the calibration screen's (7 above, 5 below) and a 16-unit one exactly.
        int top = small ? 8 : 7;
        int bottom = small ? 8 : 5;
        List<Rect> tabs = new ArrayList<>(count);
        List<String> shown = new ArrayList<>(count);
        int first = 0;
        int filled = 0;
        for (int row = 0; row < rows && first < count; row++) {
            int inRow = (count - first + (rows - row) - 1) / (rows - row);
            int[] widths = new int[inRow];
            String[] text = new String[inRow];
            int total = (inRow - 1) * NAV_GAP;
            for (int i = 0; i < inRow; i++) {
                text[i] = labels.get(first + i);
                widths[i] = (int) Math.ceil(TextFit.width(measurer, text[i], style(first + i == selected, small))) + pad * 2;
                total += widths[i];
            }
            if (total > room) {
                if (!cut) {
                    return null;
                }
                int share = Math.max(pad * 2 + 1, (room - (inRow - 1) * NAV_GAP) / inRow);
                total = (inRow - 1) * NAV_GAP;
                for (int i = 0; i < inRow; i++) {
                    if (widths[i] > share) {
                        text[i] = TextFit.ellipsize(measurer, text[i], style(first + i == selected, small), share - pad * 2);
                        widths[i] = share;
                    }
                    total += widths[i];
                }
            }
            int x = (width - total) / 2;
            int y = top + row * (tabHeight + NAV_ROW_GAP);
            for (int i = 0; i < inRow; i++) {
                tabs.add(new Rect(x, y, widths[i], tabHeight));
                shown.add(text[i]);
                x += widths[i] + NAV_GAP;
            }
            first += inRow;
            filled++;
        }
        int header = Math.max(HEADER, top + filled * tabHeight + Math.max(0, filled - 1) * NAV_ROW_GAP + bottom);
        return new Nav(tabs, shown, selected, header, small);
    }

    static SettingsScreenLayout create(int width, int height, Nav nav) {
        int top = nav.header() + 4;
        int bottom = Math.max(top + 2, height - FOOTER - 2);
        int bodyHeight = bottom - top;
        if (width >= WIDE) {
            int controlsWidth = Math.min(360, (width - 44) / 2);
            int left = Math.max(12, (width - controlsWidth - 340 - 24) / 2);
            int previewX = left + controlsWidth + 20;
            return new SettingsScreenLayout(new Rect(left, top, controlsWidth, bodyHeight),
                    new Rect(previewX, top, Math.min(340, width - previewX - 12), bodyHeight), nav);
        }
        int contentWidth = Math.max(1, Math.min(360, width - 24));
        int left = (width - contentWidth) / 2;
        // The preview is sized as if the tabs took one row; a second row comes out of the scrolling controls, which
        // can spare it, rather than out of the preview, which is short already.
        int previewHeight = Math.min(148, Math.max(48, (bottom - HEADER - 4) * 2 / 5));
        int controlsTop = top + previewHeight + 8;
        return new SettingsScreenLayout(new Rect(left, controlsTop, contentWidth, Math.max(1, bottom - controlsTop)),
                new Rect(left, top, contentWidth, previewHeight), nav);
    }

    /**
     * The preview's picture for a battle canvas of {@code canvasWidth} by {@code canvasHeight}. {@code widestPanel} is
     * the width the battle gives a panel at the width slider's maximum on that canvas; the screen asks
     * BattlePanelRenderer for it rather than this class keeping a copy of the battle's arithmetic.
     */
    static Preview preview(Rect viewport, int canvasWidth, int canvasHeight, int widestPanel, float captionWidth) {
        int canvasW = Math.max(1, canvasWidth);
        int canvasH = Math.max(1, canvasHeight);
        int needed = Math.min(canvasW, PANEL_MARGIN * 2 + Math.max(1, widestPanel));
        float aboveScale = Math.min((viewport.height() - CAPTION_ROW) / (float) canvasH, viewport.width() / (float) needed);
        float besideRoom = viewport.width() - captionWidth - CAPTION_GAP;
        float besideScale = Math.min(viewport.height() / (float) canvasH, besideRoom / needed);
        // Beside the picture the caption reads as a label on a strip; above it, as the heading of a column. Only a
        // clearly bigger picture is worth the strip, which in practice means the short preview of the stacked layout.
        boolean beside = besideScale > aboveScale * BESIDE_GAIN;
        float roomWidth = beside ? besideRoom : viewport.width();
        // Whole units, so the picture's edges and the scissor around it fall on the same pixels; the scale is then taken
        // back from the rounded height, which keeps the two bars exactly at the picture's edges.
        int cardHeight = Math.max(0, (int) Math.floor(canvasH * Math.max(0F, beside ? besideScale : aboveScale)));
        float scale = cardHeight / (float) canvasH;
        // A screen that fits whole is rounded up, so its right edge is in the picture too; one that does not is cut where
        // the room ends. Either way the battle's layers are drawn across every unit of the picture, at most a sliver of a
        // unit past the screen's own edge.
        int whole = (int) Math.ceil(canvasW * scale - 1E-3F);
        int cardWidth = Math.max(0, Math.min((int) Math.floor(roomWidth), whole));
        int visible = scale > 0 ? (int) Math.ceil(cardWidth / scale - 1E-3F) : 0;
        int cardX;
        int cardY;
        float captionX;
        float captionY;
        if (beside) {
            int areaX = viewport.x() + (int) Math.ceil(captionWidth) + CAPTION_GAP;
            cardX = areaX + Math.max(0, (viewport.right() - areaX - cardWidth) / 2);
            cardY = viewport.y() + Math.max(0, (viewport.height() - cardHeight) / 2);
            captionX = viewport.x();
            captionY = cardY + 2;
        } else {
            cardX = viewport.x() + Math.max(0, (viewport.width() - cardWidth) / 2);
            cardY = viewport.y() + CAPTION_ROW;
            captionX = cardX;
            captionY = viewport.y() + 1;
        }
        return new Preview(new Rect(cardX, cardY, cardWidth, cardHeight), scale, canvasW, canvasH, visible, needed,
                captionX, captionY, beside);
    }

    record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }
}
