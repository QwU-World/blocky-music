package ru.gora.musicbattle.client.workshop.layout;

/**
 * A rectangle in GUI units, floats, top-left origin. Pure data shared by the Workshop layout model and the screens
 * that draw it; the {@code workshop-screens} check measures the very same rectangles without a GPU.
 */
public record Rect(float x, float y, float w, float h) {
    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public static Rect of(float x, float y, float w, float h) {
        return new Rect(x, y, Math.max(0, w), Math.max(0, h));
    }

    public float right() {
        return x + w;
    }

    public float bottom() {
        return y + h;
    }

    public float centerX() {
        return x + w * 0.5F;
    }

    public float centerY() {
        return y + h * 0.5F;
    }

    public boolean empty() {
        return !(w > 0) || !(h > 0);
    }

    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    /** True when this rectangle lies inside {@code other}, allowing a small rounding slack. */
    public boolean inside(Rect other, float slack) {
        return x >= other.x - slack && y >= other.y - slack && right() <= other.right() + slack && bottom() <= other.bottom() + slack;
    }

    /** True when both rectangles share more than {@code slack} units in each axis. */
    public boolean overlaps(Rect other, float slack) {
        return x + slack < other.right() && other.x + slack < right() && y + slack < other.bottom() && other.y + slack < bottom();
    }

    public Rect inset(float dx, float dy) {
        return new Rect(x + dx, y + dy, Math.max(0, w - dx * 2), Math.max(0, h - dy * 2));
    }

    public Rect offset(float dx, float dy) {
        return new Rect(x + dx, y + dy, w, h);
    }

    public Rect withY(float value) {
        return new Rect(x, value, w, h);
    }

    public Rect withHeight(float value) {
        return new Rect(x, y, w, Math.max(0, value));
    }

    public Rect withWidth(float value) {
        return new Rect(x, y, Math.max(0, value), h);
    }

    /** The intersection, or {@link #EMPTY} when they do not touch. */
    public Rect intersect(Rect other) {
        float left = Math.max(x, other.x);
        float top = Math.max(y, other.y);
        float right = Math.min(right(), other.right());
        float bottom = Math.min(bottom(), other.bottom());
        return right > left && bottom > top ? new Rect(left, top, right - left, bottom - top) : EMPTY;
    }
}
