package ru.gora.musicbattle.data;

import net.minecraft.util.Mth;

/**
 * One source of truth for every numeric FX bound.
 *
 * <p>The canonical clamps in {@link TrackEffect} and {@link TrackEffectSettings} and the inspector
 * sliders all read these ranges, so a control can no longer offer a value the data model would
 * silently clamp away. {@link #clamp} reproduces the plain min/max used by {@code TrackEffect}
 * (a non-finite value stays non-finite), while {@link #clampFinite} reproduces the
 * {@code TrackEffectSettings} behaviour of replacing NaN/Infinity with a known default.</p>
 */
public record FxRange(float minimum, float maximum, float fallback, float step) {
    /** tick and durationTicks are only clamped from below, so a slider needs its own upper bound. */
    public static final int DEFAULT_TICK_SLIDER_MAX = 2400;

    public FxRange(float minimum, float maximum, float fallback) {
        this(minimum, maximum, fallback, 0.0F);
    }

    // TrackEffect bounds.
    public static final FxRange INTENSITY = new FxRange(0.1F, 5.0F, 1.0F);
    public static final FxRange SOFTNESS = new FxRange(0.0F, 1.0F, 0.65F);
    public static final FxRange POSITION_X = new FxRange(0.0F, 1.0F, 0.5F);
    public static final FxRange POSITION_Y = new FxRange(0.0F, 1.0F, 0.32F);
    public static final FxRange OPACITY = new FxRange(0.0F, 1.0F, 1.0F);
    public static final FxRange TEXT_SPEED = new FxRange(0.1F, 5.0F, 1.0F);
    public static final FxRange OUTLINE_THICKNESS = new FxRange(0.5F, 5.0F, 2.0F);
    /** Text is the one effect whose outline may be switched off entirely. */
    public static final FxRange TEXT_OUTLINE_THICKNESS = new FxRange(0.0F, 5.0F, 0.0F);
    public static final FxRange GLOW_STRENGTH = new FxRange(0.0F, 5.0F, 1.0F);
    public static final FxRange TICK = new FxRange(0.0F, DEFAULT_TICK_SLIDER_MAX, 0.0F, 1.0F);
    public static final FxRange DURATION_TICKS = new FxRange(0.0F, DEFAULT_TICK_SLIDER_MAX, 0.0F, 1.0F);

    // TrackEffectSettings bounds.
    public static final FxRange TV_SCANLINE_SPACING = new FxRange(1.0F, 16.0F, 3.0F);
    public static final FxRange TV_SCANLINE_OPACITY = new FxRange(0.0F, 1.0F, 0.692647F);
    public static final FxRange TV_SWEEP_WIDTH = new FxRange(1.0F, 160.0F, 18.0F);
    public static final FxRange TV_SWEEP_OPACITY = new FxRange(0.0F, 1.0F, 0.37F);
    public static final FxRange TV_SWEEP_SPEED = new FxRange(0.1F, 20.0F, 3.2F);
    public static final FxRange TV_NOISE_AMOUNT = new FxRange(0.0F, 1.0F, 0.475F);
    public static final FxRange TV_NOISE_OPACITY = new FxRange(0.0F, 1.0F, 0.45F);
    public static final FxRange TV_FLICKER_AMOUNT = new FxRange(0.0F, 1.0F, 0.14F);
    public static final FxRange VIGNETTE_RADIUS = new FxRange(0.1F, 1.4F, 0.72F);
    public static final FxRange VIGNETTE_SOFTNESS = new FxRange(0.01F, 1.2F, 0.34F);
    public static final FxRange VIGNETTE_ROUNDNESS = new FxRange(0.0F, 1.0F, 0.82F);
    public static final FxRange FISH_EYE_STRENGTH = new FxRange(0.0F, 1.5F, 0.24F);
    public static final FxRange FISH_EYE_RADIUS = new FxRange(0.25F, 2.0F, 1.0F);
    public static final FxRange FISH_EYE_ZOOM = new FxRange(0.25F, 8.0F, 1.0F);
    public static final FxRange FISH_EYE_CHROMATIC = new FxRange(0.0F, 1.0F, 0.10F);
    public static final FxRange FISH_EYE_EDGE_DARKENING = new FxRange(0.0F, 1.0F, 0.28F);
    public static final FxRange CINEMATIC_TOP_HEIGHT = new FxRange(0.0F, 0.45F, 0.12F);
    public static final FxRange CINEMATIC_BOTTOM_HEIGHT = new FxRange(0.0F, 0.45F, 0.12F);
    public static final FxRange CINEMATIC_FEATHER = new FxRange(0.0F, 0.15F, 0.025F);
    public static final FxRange CINEMATIC_ANIMATION_SPEED = new FxRange(0.1F, 5.0F, 1.0F);
    public static final FxRange CINEMATIC_CENTER_OFFSET = new FxRange(-0.25F, 0.25F, 0.0F);
    public static final FxRange CINEMATIC_SHADOW = new FxRange(0.0F, 1.0F, 0.55F);
    public static final FxRange FADE_IN_TICKS = new FxRange(0.0F, 200.0F, 0.0F, 1.0F);
    public static final FxRange FADE_OUT_TICKS = new FxRange(0.0F, 200.0F, 0.0F, 1.0F);
    public static final FxRange VISUALIZER_OFFSET = new FxRange(-1.0F, 1.0F, 0.0F);
    public static final FxRange COLOR_BRIGHTNESS = new FxRange(-1.0F, 1.0F, 0.0F);
    public static final FxRange COLOR_CONTRAST = new FxRange(0.0F, 3.0F, 1.0F);
    public static final FxRange COLOR_SATURATION = new FxRange(0.0F, 3.0F, 1.0F);
    public static final FxRange COLOR_HUE = new FxRange(-180.0F, 180.0F, 0.0F);
    public static final FxRange COLOR_GAMMA = new FxRange(0.2F, 3.0F, 1.0F);
    public static final FxRange COLOR_TEMPERATURE = new FxRange(-1.0F, 1.0F, 0.0F);
    public static final FxRange STAR_SIZE = new FxRange(0.03F, 6.0F, 1.0F);
    public static final FxRange STAR_DENSITY = new FxRange(0.05F, 3.0F, 1.0F);
    public static final FxRange STAR_TWINKLE_SPEED = new FxRange(0.0F, 10.0F, 2.0F);
    public static final FxRange STAR_DRIFT_SPEED = new FxRange(-5.0F, 5.0F, 0.35F);
    public static final FxRange STAR_SPREAD = new FxRange(0.1F, 1.0F, 1.0F);
    public static final FxRange STAR_BRIGHTNESS = new FxRange(0.1F, 3.0F, 1.0F);
    public static final FxRange STAR_IRIDESCENCE = new FxRange(0.0F, 1.0F, 0.75F);
    public static final FxRange OBJECT_OUTLINE_COLOR_SPEED = new FxRange(0.0F, 10.0F, 0.0F);

    /** Only TEXT may switch its outline off; every other effect keeps a visible minimum. */
    public static FxRange outlineThickness(TrackEffect.Type type) {
        return type == TrackEffect.Type.TEXT ? TEXT_OUTLINE_THICKNESS : OUTLINE_THICKNESS;
    }

    /** Plain bound check; a non-finite value is preserved, exactly like the TrackEffect clamps. */
    public float clamp(float value) {
        return Mth.clamp(value, minimum, maximum);
    }

    /** Bound check that substitutes the known default for NaN/Infinity, as persisted data needs. */
    public float clampFinite(float value) {
        return Float.isFinite(value) ? Mth.clamp(value, minimum, maximum) : fallback;
    }

    /** Whole-number controls (ticks) so a drag cannot produce 37.4 frames. */
    public boolean integral() {
        return step >= 1.0F;
    }

    /** Slider position (0..1) to a real value, snapped to the step when there is one. */
    public float fromFraction(double fraction) {
        float value = (float) (minimum + Mth.clamp(fraction, 0.0D, 1.0D) * (maximum - minimum));
        if (step > 0.0F) {
            value = minimum + Math.round((value - minimum) / step) * step;
        }
        return clamp(value);
    }

    /** Value to slider position (0..1); a degenerate range parks the handle at the start. */
    public double toFraction(float value) {
        float span = maximum - minimum;
        if (!(span > 0.0F) || !Float.isFinite(value)) {
            return 0.0D;
        }
        return Mth.clamp((clamp(value) - minimum) / span, 0.0F, 1.0F);
    }
}
