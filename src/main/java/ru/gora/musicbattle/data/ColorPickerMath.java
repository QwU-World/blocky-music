package ru.gora.musicbattle.data;

/** Cyclic HSV conversion: both ends of the hue strip must produce the same red. */
public final class ColorPickerMath {
    private ColorPickerMath() { }

    public static int hsvToRgb(float hue, float saturation, float value) {
        float safeHue = Float.isFinite(hue) ? hue : 0;
        float wrapped = safeHue - (float) Math.floor(safeHue);
        float s = clamp01(saturation);
        float v = clamp01(value);
        float sector = wrapped * 6;
        int whole = (int) Math.floor(sector);
        float fraction = sector - whole;
        float low = v * (1 - s);
        float falling = v * (1 - fraction * s);
        float rising = v * (1 - (1 - fraction) * s);
        return switch (whole % 6) {
            case 0 -> rgb(v, rising, low);
            case 1 -> rgb(falling, v, low);
            case 2 -> rgb(low, v, rising);
            case 3 -> rgb(low, falling, v);
            case 4 -> rgb(rising, low, v);
            default -> rgb(v, low, falling);
        };
    }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static int rgb(float red, float green, float blue) {
        return (Math.round(red * 255) << 16) | (Math.round(green * 255) << 8) | Math.round(blue * 255);
    }
}
