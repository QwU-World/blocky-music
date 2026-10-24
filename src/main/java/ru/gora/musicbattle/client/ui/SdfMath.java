package ru.gora.musicbattle.client.ui;

/**
 * The parameter encoding of the {@code ui_sdf} shader and a CPU copy of its coverage maths. No Minecraft
 * classes, so the ui-toolkit check can prove the encoding and the ring wedge on a grid without a GPU.
 *
 * <p>Per vertex (see {@code assets/music_battle/shaders/core/ui_sdf.fsh}):
 * <pre>
 *   UV0 (float x2)  local position relative to the shape centre, GUI units
 *   UV1 (short x2)  rect: half width * 8, half height * 8     ring: outer radius * 8, inner radius * 8
 *   UV2 (short x2)  rect: corner radius * 8                    ring: sweep 0..32767 (fraction of a turn)
 *                   y = (param * 8) << 2 | kind   param = stroke width (stroke) or softness (shadow)
 * </pre>
 * Shorts are signed, so every packed value stays within 0..32767: sizes up to 4095.875 GUI units, stroke
 * width and softness up to 1023.875.
 */
public final class SdfMath {
    public static final int KIND_FILL = 0;
    public static final int KIND_STROKE = 1;
    public static final int KIND_RING = 2;
    public static final int KIND_SHADOW = 3;
    /** Fixed-point scale for sizes. Must match the {@code / 8.0} in ui_sdf.fsh. */
    public static final float FIXED = 8.0F;
    /** Fraction scale for the ring sweep. Must match the {@code / 32767.0} in ui_sdf.fsh. */
    public static final int SWEEP_ONE = 32767;
    public static final int MAX_SHORT = 32767;
    /** Largest packed parameter before {@code << 2} would leave the positive short range. */
    public static final int MAX_PARAM_Q8 = MAX_SHORT >> 2;
    /** Room for the anti-aliased fringe around every quad, GUI units. */
    public static final float PAD = 2.0F;

    private SdfMath() {
    }

    public static int q8(float value) {
        if (!(value > 0.0F)) {
            return 0;
        }
        return Math.min(MAX_SHORT, Math.round(value * FIXED));
    }

    static int packParam(float param, int kind) {
        int q = Math.min(MAX_PARAM_Q8, q8(param));
        return (q << 2) | (kind & 3);
    }

    /** {a0, a1, b0, b1} for a filled rounded rectangle of the given half extents. */
    public static int[] fill(float halfW, float halfH, float radius) {
        return fill(new int[4], halfW, halfH, radius);
    }

    /** Allocation-free form for per-frame drawing: writes into and returns {@code out}. */
    public static int[] fill(int[] out, float halfW, float halfH, float radius) {
        return put(out, q8(halfW), q8(halfH), q8(clampRadius(radius, halfW, halfH)), KIND_FILL);
    }

    /** {a0, a1, b0, b1} for an inner stroke of the given width. */
    public static int[] stroke(float halfW, float halfH, float radius, float width) {
        return stroke(new int[4], halfW, halfH, radius, width);
    }

    public static int[] stroke(int[] out, float halfW, float halfH, float radius, float width) {
        return put(out, q8(halfW), q8(halfH), q8(clampRadius(radius, halfW, halfH)), packParam(width, KIND_STROKE));
    }

    /** {a0, a1, b0, b1} for a soft shadow whose edge fades over {@code softness} units on each side. */
    public static int[] shadow(float halfW, float halfH, float radius, float softness) {
        return shadow(new int[4], halfW, halfH, radius, softness);
    }

    public static int[] shadow(int[] out, float halfW, float halfH, float radius, float softness) {
        return put(out, q8(halfW), q8(halfH), q8(clampRadius(radius, halfW, halfH)), packParam(softness, KIND_SHADOW));
    }

    /**
     * {a0, a1, b0, b1} for a progress ring. {@code innerRatio} follows osu!'s CircularProgress.InnerRadius:
     * the ring thickness as a fraction of the outer radius (0.2 = thin ring, 1 = pie).
     */
    public static int[] ring(float outerRadius, float innerRatio, float progress) {
        return ring(new int[4], outerRadius, innerRatio, progress);
    }

    public static int[] ring(int[] out, float outerRadius, float innerRatio, float progress) {
        float inner = outerRadius * (1.0F - clamp01(innerRatio));
        return put(out, q8(outerRadius), q8(inner), Math.round(clamp01(progress) * SWEEP_ONE), KIND_RING);
    }

    private static int[] put(int[] out, int a0, int a1, int b0, int b1) {
        out[0] = a0;
        out[1] = a1;
        out[2] = b0;
        out[3] = b1;
        return out;
    }

    /** Extra quad size beyond the shape's half extents: the AA pad, plus the softness of a shadow. */
    public static float quadPadding(int[] params) {
        int kind = params[3] & 3;
        return kind == KIND_SHADOW ? PAD + (params[3] >> 2) / FIXED : PAD;
    }

    public static int kind(int b1) {
        return b1 & 3;
    }

    public static float param(int b1) {
        return (b1 >> 2) / FIXED;
    }

    static float clampRadius(float radius, float halfW, float halfH) {
        return Math.max(0.0F, Math.min(radius, Math.min(halfW, halfH)));
    }

    static float clamp01(float v) {
        return v != v ? 0.0F : Math.max(0.0F, Math.min(1.0F, v));
    }

    // ---- CPU mirror of ui_sdf.fsh -------------------------------------------------------------------------

    /** Signed distance to a rounded box centred on the origin (negative inside). */
    public static double roundBox(double px, double py, double halfW, double halfH, double radius) {
        double qx = Math.abs(px) - halfW + radius;
        double qy = Math.abs(py) - halfH + radius;
        double outside = Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0));
        return outside + Math.min(Math.max(qx, qy), 0.0) - radius;
    }

    /**
     * Distance field the shader evaluates for one fragment, from the packed parameters exactly as the GPU
     * receives them. Kind 3 (shadow) returns the plain box distance; its coverage differs, see
     * {@link #coverage}.
     */
    public static double distance(int[] params, double lx, double ly) {
        int kind = params[3] & 3;
        if (kind == KIND_RING) {
            double outer = params[0] / (double) FIXED;
            double inner = params[1] / (double) FIXED;
            double r = Math.hypot(lx, ly);
            double d = Math.max(r - outer, inner - r);
            double sweep = params[2] / (double) SWEEP_ONE;
            if (sweep < 0.9999) {
                double a = sweep * Math.PI * 2.0;
                // GUI y points down: angle 0 is 12 o'clock and the sweep runs clockwise on screen.
                double startSide = -lx;
                double endSide = lx * Math.cos(a) + ly * Math.sin(a);
                double wedge = sweep <= 0.5 ? Math.max(startSide, endSide) : Math.min(startSide, endSide);
                d = Math.max(d, wedge);
            }
            return d;
        }
        double halfW = params[0] / (double) FIXED;
        double halfH = params[1] / (double) FIXED;
        double radius = params[2] / (double) FIXED;
        double d = roundBox(lx, ly, halfW, halfH, radius);
        if (kind == KIND_STROKE) {
            double width = (params[3] >> 2) / (double) FIXED;
            d = Math.abs(d + width * 0.5) - width * 0.5;
        }
        return d;
    }

    /**
     * Fragment coverage 0..1 as ui_sdf.fsh computes it. {@code px} is GUI units per physical pixel (the
     * shader's fwidth), e.g. 0.5 at GUI scale 2.
     */
    public static double coverage(int[] params, double lx, double ly, double px) {
        double d = distance(params, lx, ly);
        if ((params[3] & 3) == KIND_SHADOW) {
            double soft = Math.max((params[3] >> 2) / (double) FIXED, px);
            return 1.0 - smoothstep(-soft, soft, d);
        }
        return Math.max(0.0, Math.min(1.0, 0.5 - d / Math.max(px, 1.0e-4)));
    }

    static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }
}
