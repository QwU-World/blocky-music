package ru.gora.musicbattle.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Immutable, validated note skin and one opaque color for each input lane. */
public record NoteAppearance(int skin, List<Integer> colors) {
    public static final int BARS = 0;
    public static final int ARROWS = 1;
    public static final int CIRCLES = 2;
    private static final int DEFAULT_COLOR = 0xFFFF77A9;

    public NoteAppearance {
        skin = skin >= BARS && skin <= CIRCLES ? skin : BARS;
        List<Integer> safe = new ArrayList<>(4);
        for (int lane = 0; lane < 4; lane++) {
            Integer color = colors != null && lane < colors.size() ? colors.get(lane) : null;
            safe.add(color == null ? DEFAULT_COLOR : 0xFF000000 | (color & 0xFFFFFF));
        }
        colors = List.copyOf(safe);
    }

    public int color(int lane) {
        return colors.get(Math.max(0, Math.min(3, lane)));
    }

    public static NoteAppearance solid(int color) {
        return new NoteAppearance(BARS, List.of(color, color, color, color));
    }

    /** Called once by the server when a mob joins a battle, then sent unchanged to listeners. */
    public static NoteAppearance pastel(Random random) {
        int skin = random.nextInt(3);
        float hue = random.nextFloat();
        float saturation = 0.25F + random.nextFloat() * 0.16F;
        float value = 0.90F + random.nextFloat() * 0.10F;
        List<Integer> colors = new ArrayList<>(4);
        for (int lane = 0; lane < 4; lane++) colors.add(hsv(hue + (lane - 1.5F) * 0.09F, saturation, value));
        return new NoteAppearance(skin, colors);
    }

    private static int hsv(float hue, float saturation, float value) {
        float sector = (hue - (float)Math.floor(hue)) * 6.0F;
        int index = (int)sector;
        float f = sector - index;
        float p = value * (1.0F - saturation);
        float q = value * (1.0F - saturation * f);
        float t = value * (1.0F - saturation * (1.0F - f));
        float[] rgb = switch (index) {
            case 0 -> new float[]{value,t,p}; case 1 -> new float[]{q,value,p};
            case 2 -> new float[]{p,value,t}; case 3 -> new float[]{p,q,value};
            case 4 -> new float[]{t,p,value}; default -> new float[]{value,p,q};
        };
        return 0xFF000000 | Math.round(rgb[0]*255) << 16 | Math.round(rgb[1]*255) << 8 | Math.round(rgb[2]*255);
    }
}
