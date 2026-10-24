import java.awt.Color;

public final class ColorPickerChecks {
    public static void main(String[] args) throws Exception {
        Class<?> helper;
        try { helper = Class.forName("ru.gora.musicbattle.data.ColorPickerMath"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Hue endpoint normalization missing", missing); }
        var convert = helper.getMethod("hsvToRgb", float.class, float.class, float.class);
        int[][] endpoints = {{0, 0xFF0000}, {1, 0xFF0000}, {2, 0xFF0000}, {-1, 0xFF0000}};
        for (int[] test : endpoints) {
            int actual = (int) convert.invoke(null, (float) test[0], 1F, 1F);
            if (actual != test[1]) throw new AssertionError("Red endpoint: " + Integer.toHexString(actual));
        }
        int cases = 0;
        for (int h = 0; h <= 720; h++) for (int s = 0; s <= 10; s++) for (int v = 0; v <= 10; v++) {
            float hue = h / 360F, saturation = s / 10F, value = v / 10F;
            int actual = (int) convert.invoke(null, hue, saturation, value);
            int expected = Color.HSBtoRGB(hue, saturation, value);
            for (int shift : new int[] {0, 8, 16}) {
                if (Math.abs(((actual >>> shift) & 255) - ((expected >>> shift) & 255)) > 1) {
                    throw new AssertionError("HSV disagreement at " + hue + ", " + saturation + ", " + value);
                }
            }
            cases++;
        }
        System.out.println("Color picker checks passed: " + cases + " samples and red endpoints");
    }
}
