package ru.gora.musicbattle.client;

import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.ShellLayout;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * Where the in-game guide puts its parts, and how one section's article wraps. Pure geometry and text fitting:
 * no {@code GuiGraphics}, no Minecraft, so the guide-navigation check measures the very rectangles the screen
 * draws into, at any window size, without a window.
 *
 * <p>The shell proportions are the Workshop's on purpose - the same panel width, title bar and spacing tokens.
 * The guide is one click from the Workshop in the settings screen, and the two should not read as two products.
 */
public final class GuideLayout {
    /** What a paragraph of the guide is, which is what decides how it is drawn. */
    public enum Kind {
        HEADING, TEXT, BULLET, PATH, TIP, WARN
    }

    /** One paragraph of a section. Its text is already translated by the time it gets here. */
    public record Block(String text, Kind kind) {
    }

    /** Parent of every box of an article; also the prefix of the per-block ids. */
    public static final String BODY = "guide.body";
    /** Width of the accent rule beside a heading and down the side of a callout. */
    public static final float MARK = 2F;
    /** Radius of the bullet dot. */
    public static final float DOT = 1.4F;

    private static final float HEADING_INDENT = Metrics.GAP;
    private static final float BULLET_INDENT = Metrics.PAD;
    private static final float CHIP_PAD = Metrics.PAD_SM;
    private static final float CALLOUT_PAD = Metrics.PAD_SM + 1F;
    /**
     * A paragraph this long is a bug in the text, not a long sentence: the cap keeps one runaway string from
     * pushing everything after it out of reach instead of being noticed.
     */
    private static final int MAX_LINES = 40;
    /**
     * Widest the text column gets, whatever the window. A line of body text that runs the full width of a
     * maximised window is around 180 characters, and the eye loses the start of the next line; the guide is
     * prose, so it keeps a column of roughly eighty and centres it, the way the website sets its articles.
     */
    private static final float MAX_MEASURE = 400F;
    private static final float SIDEBAR_MIN = 74F;
    private static final float SIDEBAR_MAX = 168F;
    private static final float SIDEBAR_MAX_COMPACT = 118F;
    private static final float SIDEBAR_FRACTION = 0.28F;
    private static final float NAV_MAX = 112F;
    private static final float NAV_MIN = 30F;
    private static final float NAV_FRACTION = 0.24F;

    private GuideLayout() {
    }

    /**
     * Every rectangle of the screen for a window of this size. {@code rowHeight} and {@code rowStep} are the
     * section list's; {@link Frame#row} places one row, {@link Frame#visibleRows} says how many fit.
     */
    public record Frame(float width, float height, boolean compact, float pad, float gap, Rect panel, Rect titleBar,
                        Rect titleIcon, Rect titleText, Rect close, Rect sidebar, Rect list, Rect article, Rect header,
                        Rect rule, Rect body, Rect column, Rect footer, Rect back, Rect prev, Rect next,
                        float rowHeight, float rowStep) {
        /** How many section rows fit in the list at once; at least one, however short the window is. */
        public int visibleRows() {
            return Math.max(1, (int) Math.floor((list.h() + Metrics.GAP_SM) / rowStep));
        }

        /** The row at a slot of the list, counting from the first section shown. */
        public Rect row(int slot) {
            return new Rect(list.x(), list.y() + slot * rowStep, list.w(), rowHeight);
        }
    }

    public static Frame frame(float width, float height) {
        boolean compact = Metrics.compact(width, height);
        float pad = Metrics.pad(width, height);
        float gap = compact ? Metrics.GAP_SM : Metrics.GAP;
        float titleHeight = Metrics.titleBar(width, height);
        float buttonHeight = Metrics.buttonHeight(width, height);

        float panelWidth = width < ShellLayout.FULL_WIDTH_BELOW ? width : Math.round(width * ShellLayout.WIDE_FRACTION);
        Rect panel = new Rect((width - panelWidth) * 0.5F, 0, panelWidth, height);
        Rect titleBar = new Rect(panel.x(), 0, panelWidth, titleHeight);
        float closeSize = Math.max(14F, titleHeight - 8F);
        Rect close = new Rect(titleBar.right() - pad - closeSize, (titleHeight - closeSize) * 0.5F, closeSize, closeSize);
        Rect titleIcon = new Rect(titleBar.x() + pad, (titleHeight - Metrics.ICON) * 0.5F, Metrics.ICON, Metrics.ICON);
        float titleX = titleIcon.right() + Metrics.GAP_SM + 1F;
        Rect titleText = new Rect(titleX, ShellLayout.capY(TextStyle.TITLE, 0, titleHeight),
                Math.max(20F, close.x() - Metrics.GAP - titleX), TextFit.boxHeight(TextStyle.TITLE));

        float footerHeight = buttonHeight + pad * 2F;
        Rect footer = new Rect(panel.x(), Math.max(titleBar.bottom(), height - footerHeight), panelWidth, footerHeight);

        float top = titleBar.bottom() + gap;
        float middle = Math.max(buttonHeight, footer.y() - gap - top);
        float sidebarWidth = Math.max(SIDEBAR_MIN, Math.min(compact ? SIDEBAR_MAX_COMPACT : SIDEBAR_MAX,
                panelWidth * SIDEBAR_FRACTION));
        Rect sidebar = new Rect(panel.x() + pad, top, sidebarWidth, middle);
        Rect list = sidebar.inset(Metrics.GAP_SM, Metrics.GAP_SM);
        float articleX = sidebar.right() + gap;
        Rect article = new Rect(articleX, top, Math.max(80F, panel.right() - pad - articleX), middle);

        float headerHeight = TextFit.boxHeight(TextStyle.TITLE);
        float ruleY = article.y() + pad + headerHeight + Metrics.GAP_SM;
        float bodyTop = ruleY + 1F + gap;
        Rect body = new Rect(article.x() + pad, bodyTop, Math.max(20F, article.w() - pad * 2F),
                Math.max(buttonHeight, article.bottom() - pad - bodyTop));
        // The measure is decided once, here, and the title and its rule sit over the column rather than over the
        // whole panel: a heading that starts a hundred units left of its own text does not read as one article.
        float measure = Math.min(Math.max(40F, body.w() - Metrics.SCROLLBAR - Metrics.GAP), MAX_MEASURE);
        Rect column = new Rect(body.x() + Math.max(0F, (body.w() - Metrics.SCROLLBAR - Metrics.GAP - measure) * 0.5F),
                body.y(), measure, body.h());
        Rect header = new Rect(column.x(), article.y() + pad, column.w(), headerHeight);
        Rect rule = new Rect(column.x(), ruleY, column.w(), 1F);

        // The nav pair is measured from the right edge first, so "Back" is the one that gives up room in a
        // narrow window: losing a word off "Previous" would leave two buttons that no longer say which is which.
        float navWidth = Math.max(NAV_MIN, Math.min(NAV_MAX, (footer.w() - pad * 2F) * NAV_FRACTION));
        Rect next = new Rect(footer.right() - pad - navWidth, footer.y() + pad, navWidth, buttonHeight);
        Rect prev = new Rect(next.x() - Metrics.GAP_SM - navWidth, next.y(), navWidth, buttonHeight);
        float backWidth = Math.max(NAV_MIN, Math.min(sidebarWidth, prev.x() - gap - (footer.x() + pad)));
        Rect back = new Rect(footer.x() + pad, next.y(), backWidth, buttonHeight);

        float rowHeight = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
        return new Frame(width, height, compact, pad, gap, panel, titleBar, titleIcon, titleText, close, sidebar, list,
                article, header, rule, body, column, footer, back, prev, next, rowHeight, rowHeight + Metrics.GAP_SM);
    }

    /** The id of one block's boxes: the text lines are {@code blockId(i) + ".text.<line>"}. */
    public static String blockId(int index) {
        return BODY + "." + index;
    }

    /**
     * One section laid out for the body viewport, in coordinates relative to it, so the screen draws it at the
     * current scroll offset. {@link Layout#contentHeight()} is how far it scrolls.
     */
    public static Layout article(Frame frame, TextMeasurer measurer, List<Block> blocks) {
        Layout layout = new Layout(measurer, null);
        Rect body = frame.body();
        // The column the frame measured: centred in the viewport, clear of the scrollbar on the right edge.
        float width = frame.column().w();
        float left = frame.column().x() - body.x();
        layout.add(BODY, "", Layout.Kind.PANEL, new Rect(0, 0, body.w(), body.h()));
        Kind previous = null;
        float y = 0F;
        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            y += space(previous, block.kind());
            y += block(layout, blockId(index), block, left, y, width);
            previous = block.kind();
        }
        // A last line flush against the bottom edge reads as if the article were cut off.
        y += frame.pad();
        layout.setContentHeight(y);
        layout.resize(BODY, new Rect(0, 0, body.w(), Math.max(body.h(), y)));
        return layout;
    }

    /** One block with its decoration, in a column starting at {@code x}; returns the height it took. */
    private static float block(Layout layout, String id, Block block, float x, float y, float width) {
        switch (block.kind()) {
            case HEADING -> {
                float height = layout.paragraph(id + ".text", BODY, x + HEADING_INDENT, y, width - HEADING_INDENT,
                        block.text(), TextStyle.STRONG, Metrics.lineHeight(TextStyle.STRONG), MAX_LINES);
                layout.add(id + ".mark", BODY, Layout.Kind.ICON,
                        new Rect(x, y + 1F, MARK, Math.max(MARK, TextStyle.STRONG.capHeight())));
                return height;
            }
            case BULLET -> {
                float height = layout.paragraph(id + ".text", BODY, x + BULLET_INDENT, y, width - BULLET_INDENT,
                        block.text(), TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY), MAX_LINES);
                float center = y + TextStyle.BODY.capHeight() * 0.5F;
                layout.add(id + ".dot", BODY, Layout.Kind.ICON,
                        new Rect(x + BULLET_INDENT * 0.5F - DOT, center - DOT, DOT * 2F, DOT * 2F));
                return height;
            }
            case PATH -> {
                Rect chip = new Rect(x, y, width, 0);
                layout.add(id + ".chip", BODY, Layout.Kind.PANEL, chip);
                float height = layout.paragraph(id + ".text", id + ".chip", x + CHIP_PAD, y + CHIP_PAD,
                        width - CHIP_PAD * 2F, block.text(), TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY), MAX_LINES);
                layout.resize(id + ".chip", chip.withHeight(height + CHIP_PAD * 2F));
                return height + CHIP_PAD * 2F;
            }
            case TIP, WARN -> {
                Rect note = new Rect(x, y, width, 0);
                layout.add(id + ".note", BODY, Layout.Kind.PANEL, note);
                layout.add(id + ".icon", id + ".note", Layout.Kind.ICON,
                        new Rect(x + MARK + CALLOUT_PAD, y + CALLOUT_PAD, Metrics.ICON_SM, Metrics.ICON_SM));
                float textX = MARK + CALLOUT_PAD + Metrics.ICON_SM + Metrics.GAP_SM;
                float height = layout.paragraph(id + ".text", id + ".note", x + textX, y + CALLOUT_PAD,
                        width - textX - CALLOUT_PAD, block.text(), TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY),
                        MAX_LINES);
                float total = Math.max(Metrics.ICON_SM, height) + CALLOUT_PAD * 2F;
                layout.resize(id + ".note", note.withHeight(total));
                return total;
            }
            default -> {
                return layout.paragraph(id + ".text", BODY, x, y, width, block.text(), TextStyle.BODY,
                        Metrics.lineHeight(TextStyle.BODY), MAX_LINES);
            }
        }
    }

    /**
     * Space between two neighbouring blocks. It belongs to the pair, not to one block: a heading wants air above
     * it but sits tight on its own first line, and a run of bullets is one list rather than a stack of paragraphs.
     */
    private static float space(Kind previous, Kind current) {
        if (previous == null) {
            return 0F;
        }
        if (current == Kind.HEADING) {
            return Metrics.PAD + Metrics.GAP_SM;
        }
        if (previous == Kind.HEADING) {
            return Metrics.GAP_SM;
        }
        if (previous == Kind.BULLET && current == Kind.BULLET) {
            return Metrics.GAP_SM;
        }
        return Metrics.GAP;
    }
}
