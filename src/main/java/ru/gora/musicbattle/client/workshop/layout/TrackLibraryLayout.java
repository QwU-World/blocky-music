package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Where the track library's chrome and its rows sit, in GUI units: the header, the tab strip, the search row, the list
 * viewport with its scrollbar gutter and the footer.
 *
 * <p>The screen builds its vanilla widgets from these rectangles in {@code init} and draws the cards from the very same
 * ones every frame, which is why this is a separate pure class: the row a player sees and the buttons they click are
 * then one calculation, and a button can no longer drift away from the card it belongs to when a size changes.
 *
 * <p>Nothing here touches Minecraft, like the other layouts in this package; text is measured through
 * {@link TextMeasurer}, so the widths are the ones the Workshop font will actually draw.
 */
public record TrackLibraryLayout(Rect header, Rect tabBar, List<Rect> tabs, Rect search, Rect create, Rect list,
                                 Rect footer, Rect back, Rect status, Rect progress, float pad, float rowHeight,
                                 float rowGap, int rowsPerPage, float buttonHeight) {
    /** The content column never grows past this, so a card on an ultrawide screen is still one glance wide. */
    public static final float PANEL_MAX_W = 720F;
    /** Room kept to the right of the column for the scrollbar, so a thumb never sits on a row. */
    public static final float GUTTER = 14F;
    public static final float ROW_MIN_H = 60F;
    public static final float ROW_MAX_H = 88F;
    public static final float COVER_MAX = 56F;
    public static final float ACTION_MIN_W = 34F;
    public static final float ACTION_MAX_W = 132F;
    public static final float ACTION_GAP = 4F;
    /** How much of a row the action strip may take before its buttons are squeezed to fit. */
    public static final float ACTION_SHARE = 0.55F;

    public TrackLibraryLayout {
        tabs = List.copyOf(tabs);
    }

    public static TrackLibraryLayout compute(float width, float height, TextMeasurer measurer, String createLabel,
                                             String backLabel) {
        float pad = Metrics.pad(width, height);
        float buttonHeight = Metrics.buttonHeight(width, height);
        float panelWidth = Math.max(80F, Math.min(width - pad * 2 - GUTTER, PANEL_MAX_W));
        float panelX = (width - panelWidth) * 0.5F;

        Rect header = new Rect(0, 0, width, Metrics.titleBar(width, height));
        Rect tabBar = new Rect(0, header.bottom(), width, Metrics.tabBar(width, height));
        List<Rect> tabs = new ArrayList<>(3);
        float tabWidth = (panelWidth - Metrics.GAP_SM * 2) / 3F;
        for (int i = 0; i < 3; i++) {
            tabs.add(new Rect(panelX + i * (tabWidth + Metrics.GAP_SM), tabBar.y() + 1, tabWidth, tabBar.h() - 2));
        }

        float toolbarY = tabBar.bottom() + Metrics.GAP;
        float createWidth = Math.min(panelWidth * 0.42F, actionWidth(measurer, createLabel, buttonHeight) + 18);
        Rect search = new Rect(panelX, toolbarY, panelWidth - createWidth - Metrics.GAP, buttonHeight);
        Rect create = new Rect(search.right() + Metrics.GAP, toolbarY, createWidth, buttonHeight);

        float footerHeight = buttonHeight + 8;
        Rect footer = new Rect(0, height - footerHeight, width, footerHeight);
        float backWidth = Math.min(panelWidth * 0.4F, actionWidth(measurer, backLabel, buttonHeight) + 30);
        Rect back = new Rect((width - backWidth) * 0.5F, footer.y() + (footerHeight - buttonHeight) * 0.5F,
                backWidth, buttonHeight);
        float statusWidth = Math.max(48F, back.x() - Metrics.GAP - pad);
        Rect status = new Rect(pad, footer.y() + 4, statusWidth, TextFit.boxHeight(TextStyle.CAPTION));
        Rect progress = new Rect(pad, footer.bottom() - 6, Math.min(statusWidth, 180F), 3F);

        float listTop = search.bottom() + Metrics.GAP;
        Rect list = new Rect(panelX, listTop, panelWidth, Math.max(24F, footer.y() - Metrics.GAP - listTop));
        // A row is a fraction of the window so three of them fill a tall screen, but it never shrinks below what the
        // three text lines and the action strip need, and never past the viewport itself on a 240 unit window.
        float rowHeight = Math.min(list.h(), Math.max(44F, Math.min(ROW_MAX_H, Math.max(ROW_MIN_H, height / 7F))));
        float rowGap = Metrics.CARD_GAP;
        int rowsPerPage = Math.max(1, (int) Math.floor((list.h() + rowGap) / (rowHeight + rowGap)));
        return new TrackLibraryLayout(header, tabBar, tabs, search, create, list, footer, back, status, progress,
                pad, rowHeight, rowGap, rowsPerPage, buttonHeight);
    }

    /** The content column: the header text, the tabs and the rows all line up with it. */
    public Rect column() {
        return new Rect(list.x(), 0, list.w(), header.h());
    }

    public Rect row(int visibleRow) {
        return new Rect(list.x(), list.y() + visibleRow * (rowHeight + rowGap), list.w(), rowHeight);
    }

    public Rect cover(Rect row) {
        float size = Math.min(COVER_MAX, row.h() - pad * 2);
        return new Rect(row.x() + pad, row.y() + (row.h() - size) * 0.5F, size, size);
    }

    public Rect searchIcon() {
        return new Rect(search.right() - Metrics.ICON - 5, search.y() + (search.h() - Metrics.ICON) * 0.5F,
                Metrics.ICON, Metrics.ICON);
    }

    /** The scrollbar lives in the gutter beside the column, never over a row. */
    public Rect scrollbar() {
        return new Rect(list.right() + 4, list.y(), Metrics.SCROLLBAR, list.h());
    }

    /** Pixels the list is scrolled by when {@code rows} whole rows are above the viewport. */
    public float scrollPixels(int rows) {
        return rows * (rowHeight + rowGap);
    }

    /**
     * On a narrow window four buttons beside the text leave the title a stump, so there the strip drops to the foot of
     * the row and the text gets the whole width above it instead.
     */
    public boolean stackedActions() {
        return list.w() < Metrics.COMPACT_WIDTH;
    }

    /** The y where a row's text has to stop: the top of a stacked strip, or the bottom padding beside one. */
    public float textBottom(Rect row) {
        return stackedActions() ? actionY(row) - 2 : row.bottom() - pad;
    }

    private float actionY(Rect row) {
        return stackedActions() ? row.bottom() - pad - buttonHeight : row.centerY() - buttonHeight * 0.5F;
    }

    /**
     * The row's buttons, right aligned, in the order given. Widths come from the labels, so a long Russian caption
     * gets the room it needs; when the strip would still eat the card it is scaled down as a whole, which keeps the
     * buttons a matched set instead of cutting the last one off.
     */
    public List<Rect> actions(Rect row, float... widths) {
        List<Rect> out = new ArrayList<>(widths.length);
        if (widths.length == 0) {
            return out;
        }
        float buttons = 0;
        for (float width : widths) {
            buttons += width;
        }
        float gaps = ACTION_GAP * (widths.length - 1);
        // A stacked strip runs the width of the row, but never back under the cover it would otherwise sit on.
        float share = stackedActions()
                ? row.right() - pad - (cover(row).right() + Metrics.GAP)
                : row.w() * ACTION_SHARE;
        float room = Math.max(ACTION_MIN_W, share) - gaps;
        float scale = buttons > room && buttons > 0 ? room / buttons : 1F;
        float x = row.right() - pad - (buttons * scale + gaps);
        float y = actionY(row);
        for (float width : widths) {
            float scaled = width * scale;
            out.add(new Rect(x, y, scaled, buttonHeight));
            x += scaled + ACTION_GAP;
        }
        return out;
    }

    /** Width of a button that holds {@code label} in the style {@code FancyButton} draws at this height. */
    public static float actionWidth(TextMeasurer measurer, String label, float buttonHeight) {
        TextStyle style = buttonHeight >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
        return Math.max(ACTION_MIN_W, Math.min(ACTION_MAX_W, TextFit.width(measurer, label, style) + 16));
    }
}
