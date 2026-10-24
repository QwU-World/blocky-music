package ru.gora.musicbattle.client.ui;

/**
 * Layout model for a virtualised, responsive grid of equal cards inside a scrolling viewport (osu! lazer's
 * beatmap listing). Floats in GUI units; nothing here draws. Screens lay the grid out on resize, then per
 * frame iterate {@link #firstVisible}..{@link #endVisible} and hit-test against the same model, never
 * against drawn pixels.
 *
 * <pre>
 *   columns   = clamp(floor((viewportW + gap) / (minCardW + gap)), 1, maxColumns)
 *   cardWidth = (viewportW - gap * (columns - 1)) / columns
 *   rowStep   = cardHeight + gap
 * </pre>
 */
public final class CardGrid {
    private final float minCardWidth;
    private final float cardHeight;
    private final float gap;
    private final int maxColumns;

    private float x;
    private float y;
    private float width;
    private float height;
    private int count;
    private int columns = 1;
    private float cardWidth;

    public CardGrid(float minCardWidth, float cardHeight, float gap, int maxColumns) {
        if (!(minCardWidth > 0) || !(cardHeight > 0) || gap < 0 || maxColumns < 1) {
            throw new IllegalArgumentException("invalid card grid metrics");
        }
        this.minCardWidth = minCardWidth;
        this.cardHeight = cardHeight;
        this.gap = gap;
        this.maxColumns = maxColumns;
    }

    /** Viewport rectangle in screen GUI units and the number of items. */
    public void layout(float viewportX, float viewportY, float viewportWidth, float viewportHeight, int itemCount) {
        x = viewportX;
        y = viewportY;
        width = Math.max(0.0F, viewportWidth);
        height = Math.max(0.0F, viewportHeight);
        count = Math.max(0, itemCount);
        columns = Math.max(1, Math.min(maxColumns, (int) Math.floor((width + gap) / (minCardWidth + gap))));
        cardWidth = Math.max(0.0F, (width - gap * (columns - 1)) / columns);
    }

    public void setCount(int itemCount) {
        count = Math.max(0, itemCount);
    }

    public int count() {
        return count;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return (count + columns - 1) / columns;
    }

    public float cardWidth() {
        return cardWidth;
    }

    public float cardHeight() {
        return cardHeight;
    }

    public float gap() {
        return gap;
    }

    public float rowStep() {
        return cardHeight + gap;
    }

    public float viewportX() {
        return x;
    }

    public float viewportY() {
        return y;
    }

    public float viewportWidth() {
        return width;
    }

    public float viewportHeight() {
        return height;
    }

    /** Total content height: rows of cards with gaps between them, none after the last row. */
    public float contentHeight() {
        int rows = rows();
        return rows == 0 ? 0.0F : rows * cardHeight + (rows - 1) * gap;
    }

    /** How far the content can scroll; feed to {@link Motion.SmoothScroll#setExtent}. */
    public float scrollExtent() {
        return Math.max(0.0F, contentHeight() - height);
    }

    /** First item whose card intersects the viewport at this scroll offset. */
    public int firstVisible(double scroll) {
        if (count == 0) {
            return 0;
        }
        int row = (int) Math.floor((scroll + gap) / rowStep());
        return Math.min(count, Math.max(0, row) * columns);
    }

    /** One past the last item whose card intersects the viewport at this scroll offset. */
    public int endVisible(double scroll) {
        if (count == 0 || height <= 0) {
            return 0;
        }
        double bottom = scroll + height;
        if (bottom <= 0) {
            return 0;
        }
        int rowsStarted = (int) Math.ceil(bottom / rowStep());
        // A row starting exactly at the bottom edge is not visible.
        if (rowsStarted > 0 && (rowsStarted - 1) * rowStep() >= bottom) {
            rowsStarted--;
        }
        return Math.min(count, Math.max(0, rowsStarted) * columns);
    }

    /** One past the last item to prepare (covers, text) when looking {@code extraRows} rows ahead. */
    public int endPrepared(double scroll, int extraRows) {
        int end = endVisible(scroll);
        return Math.min(count, end + Math.max(0, extraRows) * columns);
    }

    public int column(int index) {
        return index % columns;
    }

    public int row(int index) {
        return index / columns;
    }

    public float cardX(int index) {
        return x + column(index) * (cardWidth + gap);
    }

    /** Card top in screen units for a scroll offset (may be above the viewport). */
    public float cardY(int index, double scroll) {
        return (float) (y + row(index) * rowStep() - scroll);
    }

    public boolean insideViewport(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * The item under the pointer, or -1 when the pointer is outside the viewport, in a gap, past the last
     * item, or over the part of a card that is scrolled out of the viewport.
     */
    public int hitTest(double mouseX, double mouseY, double scroll) {
        if (count == 0 || !insideViewport(mouseX, mouseY)) {
            return -1;
        }
        double localX = mouseX - x;
        double localY = mouseY - y + scroll;
        if (localY < 0) {
            return -1;
        }
        int column = (int) Math.floor(localX / (cardWidth + gap));
        int row = (int) Math.floor(localY / rowStep());
        if (column < 0 || column >= columns) {
            return -1;
        }
        if (localX - column * (cardWidth + gap) >= cardWidth || localY - row * rowStep() >= cardHeight) {
            return -1;
        }
        int index = row * columns + column;
        return index < count ? index : -1;
    }
}
