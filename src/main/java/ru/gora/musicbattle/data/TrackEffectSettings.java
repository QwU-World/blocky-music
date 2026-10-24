package ru.gora.musicbattle.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;

/** Type-specific controls shared by saved charts, network payloads and FX tools. */
public record TrackEffectSettings(
        float tvScanlineSpacing,
        float tvScanlineOpacity,
        float tvSweepWidth,
        float tvSweepOpacity,
        float tvSweepSpeed,
        boolean tvSweepEnabled,
        float tvNoiseAmount,
        float tvNoiseOpacity,
        float tvFlickerAmount,
        float vignetteRadius,
        float vignetteSoftness,
        float vignetteRoundness,
        float fishEyeStrength,
        float fishEyeRadius,
        float fishEyeZoom,
        float fishEyeChromatic,
        float fishEyeEdgeDarkening,
        boolean fishEyeRemoveBlackEdges,
        float cinematicTopHeight,
        float cinematicBottomHeight,
        float cinematicFeather,
        float cinematicAnimationSpeed,
        boolean cinematicAnimated,
        float cinematicCenterOffset,
        float cinematicShadow,
        boolean overlayText,
        float fadeInTicks,
        float fadeOutTicks,
        boolean fadeInEnabled,
        boolean fadeOutEnabled,
        String visualizerPosition,
        float visualizerOffset,
        boolean overlayInterface,
        boolean overlayPlayers,
        boolean overlayEntities,
        boolean highlightSpectators,
        int highlightSpectatorColor,
        boolean highlightEntities,
        int highlightEntityColor,
        boolean highlightModelGlow,
        float colorBrightness,
        float colorContrast,
        float colorSaturation,
        float colorHue,
        float colorGamma,
        float colorTemperature,
        float starSize,
        float starDensity,
        float starTwinkleSpeed,
        float starDriftSpeed,
        float starSpread,
        float starBrightness,
        float starIridescence,
        String starDirection,
        List<Integer> objectOutlineColors,
        float objectOutlineColorSpeed,
        boolean hideParticles,
        boolean hideFirstPersonHand,
        boolean hideHud,
        boolean hideBlockyMusicHud
) {
    public static final int MAX_OBJECT_OUTLINE_COLORS = 16;

    public static final TrackEffectSettings DEFAULT = new TrackEffectSettings(
            3.0F, 0.692647F, 18.0F, 0.37F, 3.2F, true, 0.475F, 0.45F, 0.14F,
            0.72F, 0.34F, 0.82F,
            0.24F, 1.0F, 1.0F, 0.10F, 0.28F, false,
            0.12F, 0.12F, 0.025F, 1.0F, true, 0.0F, 0.55F,
            true, 0.0F, 0.0F, true, true, "bottom", 0.0F, false, true, true,
            false, 0xFF55E6FF,
            false, 0xFFFFB347,
            false,
            0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F,
            1.0F, 1.0F, 2.0F, 0.35F, 1.0F, 1.0F, 0.75F, "down_right",
            List.of(), 0.0F, true, true, false, false
    );

    public TrackEffectSettings {
        // Bounds live in FxRange so the inspector sliders and these clamps cannot drift apart.
        tvScanlineSpacing = FxRange.TV_SCANLINE_SPACING.clampFinite(tvScanlineSpacing);
        tvScanlineOpacity = FxRange.TV_SCANLINE_OPACITY.clampFinite(tvScanlineOpacity);
        tvSweepWidth = FxRange.TV_SWEEP_WIDTH.clampFinite(tvSweepWidth);
        tvSweepOpacity = FxRange.TV_SWEEP_OPACITY.clampFinite(tvSweepOpacity);
        tvSweepSpeed = FxRange.TV_SWEEP_SPEED.clampFinite(tvSweepSpeed);
        tvNoiseAmount = FxRange.TV_NOISE_AMOUNT.clampFinite(tvNoiseAmount);
        tvNoiseOpacity = FxRange.TV_NOISE_OPACITY.clampFinite(tvNoiseOpacity);
        tvFlickerAmount = FxRange.TV_FLICKER_AMOUNT.clampFinite(tvFlickerAmount);
        vignetteRadius = FxRange.VIGNETTE_RADIUS.clampFinite(vignetteRadius);
        vignetteSoftness = FxRange.VIGNETTE_SOFTNESS.clampFinite(vignetteSoftness);
        vignetteRoundness = FxRange.VIGNETTE_ROUNDNESS.clampFinite(vignetteRoundness);
        fishEyeStrength = FxRange.FISH_EYE_STRENGTH.clampFinite(fishEyeStrength);
        fishEyeRadius = FxRange.FISH_EYE_RADIUS.clampFinite(fishEyeRadius);
        fishEyeZoom = FxRange.FISH_EYE_ZOOM.clampFinite(fishEyeZoom);
        fishEyeChromatic = FxRange.FISH_EYE_CHROMATIC.clampFinite(fishEyeChromatic);
        fishEyeEdgeDarkening = FxRange.FISH_EYE_EDGE_DARKENING.clampFinite(fishEyeEdgeDarkening);
        cinematicTopHeight = FxRange.CINEMATIC_TOP_HEIGHT.clampFinite(cinematicTopHeight);
        cinematicBottomHeight = FxRange.CINEMATIC_BOTTOM_HEIGHT.clampFinite(cinematicBottomHeight);
        cinematicFeather = FxRange.CINEMATIC_FEATHER.clampFinite(cinematicFeather);
        cinematicAnimationSpeed = FxRange.CINEMATIC_ANIMATION_SPEED.clampFinite(cinematicAnimationSpeed);
        cinematicCenterOffset = FxRange.CINEMATIC_CENTER_OFFSET.clampFinite(cinematicCenterOffset);
        cinematicShadow = FxRange.CINEMATIC_SHADOW.clampFinite(cinematicShadow);
        fadeInTicks = FxRange.FADE_IN_TICKS.clampFinite(fadeInTicks);
        fadeOutTicks = FxRange.FADE_OUT_TICKS.clampFinite(fadeOutTicks);
        visualizerPosition = normalizeVisualizerPosition(visualizerPosition);
        visualizerOffset = FxRange.VISUALIZER_OFFSET.clampFinite(visualizerOffset);
        colorBrightness = FxRange.COLOR_BRIGHTNESS.clampFinite(colorBrightness);
        colorContrast = FxRange.COLOR_CONTRAST.clampFinite(colorContrast);
        colorSaturation = FxRange.COLOR_SATURATION.clampFinite(colorSaturation);
        colorHue = FxRange.COLOR_HUE.clampFinite(colorHue);
        colorGamma = FxRange.COLOR_GAMMA.clampFinite(colorGamma);
        colorTemperature = FxRange.COLOR_TEMPERATURE.clampFinite(colorTemperature);
        starSize = FxRange.STAR_SIZE.clampFinite(starSize);
        starDensity = FxRange.STAR_DENSITY.clampFinite(starDensity);
        starTwinkleSpeed = FxRange.STAR_TWINKLE_SPEED.clampFinite(starTwinkleSpeed);
        starDriftSpeed = FxRange.STAR_DRIFT_SPEED.clampFinite(starDriftSpeed);
        starSpread = FxRange.STAR_SPREAD.clampFinite(starSpread);
        starBrightness = FxRange.STAR_BRIGHTNESS.clampFinite(starBrightness);
        starIridescence = FxRange.STAR_IRIDESCENCE.clampFinite(starIridescence);
        starDirection = normalizeStarDirection(starDirection);
        objectOutlineColors = sanitizeObjectOutlineColors(objectOutlineColors);
        objectOutlineColorSpeed = FxRange.OBJECT_OUTLINE_COLOR_SPEED.clampFinite(objectOutlineColorSpeed);
    }

    // The JSON fallbacks are the same values the shared ranges already carry, read from there so
    // a default can never drift away from the bound that accepts it.
    private static final float DEFAULT_VALUE_SCANLINE_SPACING = FxRange.TV_SCANLINE_SPACING.fallback();
    private static final float DEFAULT_VALUE_SCANLINE_OPACITY = FxRange.TV_SCANLINE_OPACITY.fallback();
    private static final float DEFAULT_VALUE_SWEEP_WIDTH = FxRange.TV_SWEEP_WIDTH.fallback();
    private static final float DEFAULT_VALUE_SWEEP_OPACITY = FxRange.TV_SWEEP_OPACITY.fallback();
    private static final float DEFAULT_VALUE_SWEEP_SPEED = FxRange.TV_SWEEP_SPEED.fallback();
    private static final float DEFAULT_VALUE_NOISE = FxRange.TV_NOISE_AMOUNT.fallback();
    /** Pre-split charts stored one combined noise value; it is not a current control default. */
    private static final float DEFAULT_VALUE_LEGACY_NOISE = 0.45F;
    private static final float DEFAULT_VALUE_NOISE_OPACITY = FxRange.TV_NOISE_OPACITY.fallback();
    private static final float DEFAULT_VALUE_FLICKER = FxRange.TV_FLICKER_AMOUNT.fallback();
    private static final float DEFAULT_VALUE_VIGNETTE_RADIUS = FxRange.VIGNETTE_RADIUS.fallback();
    private static final float DEFAULT_VALUE_VIGNETTE_SOFTNESS = FxRange.VIGNETTE_SOFTNESS.fallback();
    private static final float DEFAULT_VALUE_VIGNETTE_ROUNDNESS = FxRange.VIGNETTE_ROUNDNESS.fallback();
    private static final float DEFAULT_VALUE_FISH_STRENGTH = FxRange.FISH_EYE_STRENGTH.fallback();
    private static final float DEFAULT_VALUE_FISH_RADIUS = FxRange.FISH_EYE_RADIUS.fallback();
    private static final float DEFAULT_VALUE_FISH_ZOOM = FxRange.FISH_EYE_ZOOM.fallback();
    private static final float DEFAULT_VALUE_FISH_CHROMATIC = FxRange.FISH_EYE_CHROMATIC.fallback();
    private static final float DEFAULT_VALUE_FISH_EDGE = FxRange.FISH_EYE_EDGE_DARKENING.fallback();
    private static final float DEFAULT_VALUE_CINEMATIC_TOP = FxRange.CINEMATIC_TOP_HEIGHT.fallback();
    private static final float DEFAULT_VALUE_CINEMATIC_BOTTOM = FxRange.CINEMATIC_BOTTOM_HEIGHT.fallback();
    private static final float DEFAULT_VALUE_CINEMATIC_FEATHER = FxRange.CINEMATIC_FEATHER.fallback();
    private static final float DEFAULT_VALUE_CINEMATIC_SPEED = FxRange.CINEMATIC_ANIMATION_SPEED.fallback();
    private static final float DEFAULT_VALUE_CINEMATIC_OFFSET = FxRange.CINEMATIC_CENTER_OFFSET.fallback();
    private static final float DEFAULT_VALUE_CINEMATIC_SHADOW = FxRange.CINEMATIC_SHADOW.fallback();
    private static final float DEFAULT_VALUE_STAR_SIZE = FxRange.STAR_SIZE.fallback();
    private static final float DEFAULT_VALUE_STAR_DENSITY = FxRange.STAR_DENSITY.fallback();
    private static final float DEFAULT_VALUE_STAR_TWINKLE_SPEED = FxRange.STAR_TWINKLE_SPEED.fallback();
    private static final float DEFAULT_VALUE_STAR_DRIFT_SPEED = FxRange.STAR_DRIFT_SPEED.fallback();
    private static final float DEFAULT_VALUE_STAR_SPREAD = FxRange.STAR_SPREAD.fallback();
    private static final float DEFAULT_VALUE_STAR_BRIGHTNESS = FxRange.STAR_BRIGHTNESS.fallback();
    private static final float DEFAULT_VALUE_STAR_IRIDESCENCE = FxRange.STAR_IRIDESCENCE.fallback();

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeFloat(tvScanlineSpacing);
        buffer.writeFloat(tvScanlineOpacity);
        buffer.writeFloat(tvSweepWidth);
        buffer.writeFloat(tvSweepOpacity);
        buffer.writeFloat(tvSweepSpeed);
        buffer.writeBoolean(tvSweepEnabled);
        buffer.writeFloat(tvNoiseAmount);
        buffer.writeFloat(tvNoiseOpacity);
        buffer.writeFloat(tvFlickerAmount);
        buffer.writeFloat(vignetteRadius);
        buffer.writeFloat(vignetteSoftness);
        buffer.writeFloat(vignetteRoundness);
        buffer.writeFloat(fishEyeStrength);
        buffer.writeFloat(fishEyeRadius);
        buffer.writeFloat(fishEyeZoom);
        buffer.writeFloat(fishEyeChromatic);
        buffer.writeFloat(fishEyeEdgeDarkening);
        buffer.writeBoolean(fishEyeRemoveBlackEdges);
        buffer.writeFloat(cinematicTopHeight);
        buffer.writeFloat(cinematicBottomHeight);
        buffer.writeFloat(cinematicFeather);
        buffer.writeFloat(cinematicAnimationSpeed);
        buffer.writeBoolean(cinematicAnimated);
        buffer.writeFloat(cinematicCenterOffset);
        buffer.writeFloat(cinematicShadow);
        buffer.writeBoolean(overlayText);
        buffer.writeFloat(fadeInTicks);
        buffer.writeFloat(fadeOutTicks);
        buffer.writeBoolean(fadeInEnabled);
        buffer.writeBoolean(fadeOutEnabled);
        buffer.writeUtf(visualizerPosition, 8);
        buffer.writeFloat(visualizerOffset);
        buffer.writeBoolean(overlayInterface);
        buffer.writeBoolean(overlayPlayers);
        buffer.writeBoolean(overlayEntities);
        buffer.writeBoolean(highlightSpectators);
        buffer.writeInt(highlightSpectatorColor);
        buffer.writeBoolean(highlightEntities);
        buffer.writeInt(highlightEntityColor);
        buffer.writeBoolean(highlightModelGlow);
        buffer.writeFloat(colorBrightness);
        buffer.writeFloat(colorContrast);
        buffer.writeFloat(colorSaturation);
        buffer.writeFloat(colorHue);
        buffer.writeFloat(colorGamma);
        buffer.writeFloat(colorTemperature);
        buffer.writeFloat(starSize);
        buffer.writeFloat(starDensity);
        buffer.writeFloat(starTwinkleSpeed);
        buffer.writeFloat(starDriftSpeed);
        buffer.writeFloat(starSpread);
        buffer.writeFloat(starBrightness);
        buffer.writeFloat(starIridescence);
        buffer.writeUtf(starDirection, 16);
        buffer.writeVarInt(objectOutlineColors.size());
        for (int color : objectOutlineColors) {
            buffer.writeInt(color);
        }
        buffer.writeFloat(objectOutlineColorSpeed);
        buffer.writeBoolean(hideParticles);
        buffer.writeBoolean(hideFirstPersonHand);
        buffer.writeBoolean(hideHud);
        buffer.writeBoolean(hideBlockyMusicHud);
    }

    public static TrackEffectSettings read(RegistryFriendlyByteBuf buffer) {
        return new TrackEffectSettings(
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(),
                buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(8), buffer.readFloat(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readInt(),
                buffer.readBoolean(), buffer.readInt(),
                buffer.readBoolean(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readUtf(16), readObjectOutlineColors(buffer), buffer.readFloat(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean()
        );
    }

    public void writeJson(JsonObject object) {
        object.addProperty("tvScanlineSpacing", tvScanlineSpacing);
        object.addProperty("tvScanlineOpacity", tvScanlineOpacity);
        object.addProperty("tvSweepWidth", tvSweepWidth);
        object.addProperty("tvSweepOpacity", tvSweepOpacity);
        object.addProperty("tvSweepSpeed", tvSweepSpeed);
        object.addProperty("tvSweepEnabled", tvSweepEnabled);
        object.addProperty("tvNoiseAmount", tvNoiseAmount);
        object.addProperty("tvNoiseOpacity", tvNoiseOpacity);
        object.addProperty("tvFlickerAmount", tvFlickerAmount);
        object.addProperty("vignetteRadius", vignetteRadius);
        object.addProperty("vignetteSoftness", vignetteSoftness);
        object.addProperty("vignetteRoundness", vignetteRoundness);
        object.addProperty("fishEyeStrength", fishEyeStrength);
        object.addProperty("fishEyeRadius", fishEyeRadius);
        object.addProperty("fishEyeZoom", fishEyeZoom);
        object.addProperty("fishEyeChromatic", fishEyeChromatic);
        object.addProperty("fishEyeEdgeDarkening", fishEyeEdgeDarkening);
        object.addProperty("fishEyeRemoveBlackEdges", fishEyeRemoveBlackEdges);
        object.addProperty("cinematicTopHeight", cinematicTopHeight);
        object.addProperty("cinematicBottomHeight", cinematicBottomHeight);
        object.addProperty("cinematicFeather", cinematicFeather);
        object.addProperty("cinematicAnimationSpeed", cinematicAnimationSpeed);
        object.addProperty("cinematicAnimated", cinematicAnimated);
        object.addProperty("cinematicCenterOffset", cinematicCenterOffset);
        object.addProperty("cinematicShadow", cinematicShadow);
        object.addProperty("overlayText", overlayText);
        object.addProperty("fadeInTicks", fadeInTicks);
        object.addProperty("fadeOutTicks", fadeOutTicks);
        object.addProperty("fadeInEnabled", fadeInEnabled);
        object.addProperty("fadeOutEnabled", fadeOutEnabled);
        object.addProperty("visualizerPosition", visualizerPosition);
        object.addProperty("visualizerOffset", visualizerOffset);
        object.addProperty("overlayInterface", overlayInterface);
        object.addProperty("overlayPlayers", overlayPlayers);
        object.addProperty("overlayEntities", overlayEntities);
        object.addProperty("highlightSpectators", highlightSpectators);
        object.addProperty("highlightSpectatorColor", highlightSpectatorColor);
        object.addProperty("highlightEntities", highlightEntities);
        object.addProperty("highlightEntityColor", highlightEntityColor);
        object.addProperty("highlightModelGlow", highlightModelGlow);
        object.addProperty("colorBrightness", colorBrightness);
        object.addProperty("colorContrast", colorContrast);
        object.addProperty("colorSaturation", colorSaturation);
        object.addProperty("colorHue", colorHue);
        object.addProperty("colorGamma", colorGamma);
        object.addProperty("colorTemperature", colorTemperature);
        object.addProperty("starSize", starSize);
        object.addProperty("starDensity", starDensity);
        object.addProperty("starTwinkleSpeed", starTwinkleSpeed);
        object.addProperty("starDriftSpeed", starDriftSpeed);
        object.addProperty("starSpread", starSpread);
        object.addProperty("starBrightness", starBrightness);
        object.addProperty("starIridescence", starIridescence);
        object.addProperty("starDirection", starDirection);
        object.addProperty("hideParticles", hideParticles);
        object.addProperty("hideFirstPersonHand", hideFirstPersonHand);
        object.addProperty("hideHud", hideHud);
        object.addProperty("hideBlockyMusicHud", hideBlockyMusicHud);
        if (objectOutlineColors.size() >= 2) {
            JsonArray colors = new JsonArray();
            for (int color : objectOutlineColors) {
                colors.add(color);
            }
            object.add("objectOutlineColors", colors);
            object.addProperty("objectOutlineColorSpeed", objectOutlineColorSpeed);
        }
    }

    public static TrackEffectSettings readJson(JsonObject object) {
        if (object == null) return DEFAULT;
        boolean splitNoiseControls = object.has("tvScanlineOpacity") || object.has("tvNoiseOpacity");
        float storedNoise = number(object, "tvNoiseAmount",
                splitNoiseControls ? DEFAULT_VALUE_NOISE : DEFAULT_VALUE_LEGACY_NOISE);
        float noiseAmount = splitNoiseControls ? storedNoise : legacyNoiseDensity(storedNoise);
        return new TrackEffectSettings(
                number(object, "tvScanlineSpacing", DEFAULT_VALUE_SCANLINE_SPACING),
                number(object, "tvScanlineOpacity", splitNoiseControls
                        ? DEFAULT_VALUE_SCANLINE_OPACITY : legacyScanlineOpacity(storedNoise)),
                number(object, "tvSweepWidth", DEFAULT_VALUE_SWEEP_WIDTH),
                number(object, "tvSweepOpacity", DEFAULT_VALUE_SWEEP_OPACITY),
                number(object, "tvSweepSpeed", DEFAULT_VALUE_SWEEP_SPEED),
                bool(object, "tvSweepEnabled", true),
                noiseAmount,
                number(object, "tvNoiseOpacity", splitNoiseControls
                        ? DEFAULT_VALUE_NOISE_OPACITY : storedNoise),
                number(object, "tvFlickerAmount", DEFAULT_VALUE_FLICKER),
                number(object, "vignetteRadius", DEFAULT_VALUE_VIGNETTE_RADIUS),
                number(object, "vignetteSoftness", DEFAULT_VALUE_VIGNETTE_SOFTNESS),
                number(object, "vignetteRoundness", DEFAULT_VALUE_VIGNETTE_ROUNDNESS),
                number(object, "fishEyeStrength", DEFAULT_VALUE_FISH_STRENGTH),
                number(object, "fishEyeRadius", DEFAULT_VALUE_FISH_RADIUS),
                number(object, "fishEyeZoom", DEFAULT_VALUE_FISH_ZOOM),
                number(object, "fishEyeChromatic", DEFAULT_VALUE_FISH_CHROMATIC),
                number(object, "fishEyeEdgeDarkening", DEFAULT_VALUE_FISH_EDGE),
                bool(object, "fishEyeRemoveBlackEdges", false),
                number(object, "cinematicTopHeight", DEFAULT_VALUE_CINEMATIC_TOP),
                number(object, "cinematicBottomHeight", DEFAULT_VALUE_CINEMATIC_BOTTOM),
                number(object, "cinematicFeather", DEFAULT_VALUE_CINEMATIC_FEATHER),
                number(object, "cinematicAnimationSpeed", DEFAULT_VALUE_CINEMATIC_SPEED),
                bool(object, "cinematicAnimated", true),
                number(object, "cinematicCenterOffset", DEFAULT_VALUE_CINEMATIC_OFFSET),
                number(object, "cinematicShadow", DEFAULT_VALUE_CINEMATIC_SHADOW),
                bool(object, "overlayText", true),
                number(object, "fadeInTicks", 0.0F),
                number(object, "fadeOutTicks", 0.0F),
                bool(object, "fadeInEnabled", true),
                bool(object, "fadeOutEnabled", true),
                string(object, "visualizerPosition", "bottom"),
                number(object, "visualizerOffset", 0.0F),
                bool(object, "overlayInterface", false),
                bool(object, "overlayPlayers", bool(object, "flashOverlayPlayers", true)),
                bool(object, "overlayEntities", bool(object, "flashOverlayEntities", true)),
                bool(object, "highlightSpectators", false),
                integer(object, "highlightSpectatorColor", 0xFF55E6FF),
                bool(object, "highlightEntities", false),
                integer(object, "highlightEntityColor", 0xFFFFB347),
                bool(object, "highlightModelGlow", false),
                number(object, "colorBrightness", 0.0F),
                number(object, "colorContrast", 1.0F),
                number(object, "colorSaturation", 1.0F),
                number(object, "colorHue", 0.0F),
                number(object, "colorGamma", 1.0F),
                number(object, "colorTemperature", 0.0F),
                number(object, "starSize", DEFAULT_VALUE_STAR_SIZE),
                number(object, "starDensity", DEFAULT_VALUE_STAR_DENSITY),
                number(object, "starTwinkleSpeed", DEFAULT_VALUE_STAR_TWINKLE_SPEED),
                number(object, "starDriftSpeed", DEFAULT_VALUE_STAR_DRIFT_SPEED),
                number(object, "starSpread", DEFAULT_VALUE_STAR_SPREAD),
                number(object, "starBrightness", DEFAULT_VALUE_STAR_BRIGHTNESS),
                number(object, "starIridescence", DEFAULT_VALUE_STAR_IRIDESCENCE),
                string(object, "starDirection", "down_right"),
                objectOutlineColors(object),
                number(object, "objectOutlineColorSpeed", 0.0F),
                bool(object, "hideParticles", true), bool(object, "hideFirstPersonHand", true), bool(object, "hideHud", false),
                bool(object, "hideBlockyMusicHud", false)
        );
    }

    public TrackEffectSettings withOverlayInterface(boolean value) {
        return new TrackEffectSettings(tvScanlineSpacing, tvScanlineOpacity, tvSweepWidth, tvSweepOpacity,
                tvSweepSpeed, tvSweepEnabled, tvNoiseAmount, tvNoiseOpacity, tvFlickerAmount,
                vignetteRadius, vignetteSoftness, vignetteRoundness, fishEyeStrength, fishEyeRadius,
                fishEyeZoom, fishEyeChromatic, fishEyeEdgeDarkening, fishEyeRemoveBlackEdges, cinematicTopHeight,
                cinematicBottomHeight, cinematicFeather, cinematicAnimationSpeed, cinematicAnimated,
                cinematicCenterOffset, cinematicShadow, overlayText, fadeInTicks, fadeOutTicks,
                fadeInEnabled, fadeOutEnabled, visualizerPosition, visualizerOffset, value,
                overlayPlayers, overlayEntities, highlightSpectators, highlightSpectatorColor,
                highlightEntities, highlightEntityColor, highlightModelGlow,
                colorBrightness, colorContrast, colorSaturation, colorHue, colorGamma, colorTemperature,
                starSize, starDensity, starTwinkleSpeed, starDriftSpeed, starSpread, starBrightness, starIridescence,
                starDirection, objectOutlineColors, objectOutlineColorSpeed, hideParticles, hideFirstPersonHand, hideHud, hideBlockyMusicHud);
    }

    public TrackEffectSettings withColorCorrection(float brightness, float contrast, float saturation,
                                                    float hue, float gamma, float temperature) {
        return new TrackEffectSettings(tvScanlineSpacing, tvScanlineOpacity, tvSweepWidth, tvSweepOpacity,
                tvSweepSpeed, tvSweepEnabled, tvNoiseAmount, tvNoiseOpacity, tvFlickerAmount,
                vignetteRadius, vignetteSoftness, vignetteRoundness, fishEyeStrength, fishEyeRadius,
                fishEyeZoom, fishEyeChromatic, fishEyeEdgeDarkening, fishEyeRemoveBlackEdges, cinematicTopHeight,
                cinematicBottomHeight, cinematicFeather, cinematicAnimationSpeed, cinematicAnimated,
                cinematicCenterOffset, cinematicShadow, overlayText, fadeInTicks, fadeOutTicks,
                fadeInEnabled, fadeOutEnabled, visualizerPosition, visualizerOffset, overlayInterface,
                overlayPlayers, overlayEntities, highlightSpectators, highlightSpectatorColor,
                highlightEntities, highlightEntityColor, highlightModelGlow,
                brightness, contrast, saturation, hue, gamma, temperature,
                starSize, starDensity, starTwinkleSpeed, starDriftSpeed, starSpread, starBrightness, starIridescence,
                starDirection, objectOutlineColors, objectOutlineColorSpeed, hideParticles, hideFirstPersonHand, hideHud, hideBlockyMusicHud);
    }

    public TrackEffectSettings withStars(float size, float density, float twinkleSpeed, float driftSpeed,
                                         float spread, float brightness, float iridescence, String direction) {
        return new TrackEffectSettings(tvScanlineSpacing, tvScanlineOpacity, tvSweepWidth, tvSweepOpacity,
                tvSweepSpeed, tvSweepEnabled, tvNoiseAmount, tvNoiseOpacity, tvFlickerAmount,
                vignetteRadius, vignetteSoftness, vignetteRoundness, fishEyeStrength, fishEyeRadius,
                fishEyeZoom, fishEyeChromatic, fishEyeEdgeDarkening, fishEyeRemoveBlackEdges, cinematicTopHeight,
                cinematicBottomHeight, cinematicFeather, cinematicAnimationSpeed, cinematicAnimated,
                cinematicCenterOffset, cinematicShadow, overlayText, fadeInTicks, fadeOutTicks,
                fadeInEnabled, fadeOutEnabled, visualizerPosition, visualizerOffset, overlayInterface,
                overlayPlayers, overlayEntities, highlightSpectators, highlightSpectatorColor,
                highlightEntities, highlightEntityColor, highlightModelGlow,
                colorBrightness, colorContrast, colorSaturation, colorHue, colorGamma, colorTemperature,
                size, density, twinkleSpeed, driftSpeed, spread, brightness, iridescence, direction,
                objectOutlineColors, objectOutlineColorSpeed, hideParticles, hideFirstPersonHand, hideHud, hideBlockyMusicHud);
    }

    public TrackEffectSettings withObjectOutlinePalette(List<Integer> colors, float speed) {
        return new TrackEffectSettings(tvScanlineSpacing, tvScanlineOpacity, tvSweepWidth, tvSweepOpacity,
                tvSweepSpeed, tvSweepEnabled, tvNoiseAmount, tvNoiseOpacity, tvFlickerAmount,
                vignetteRadius, vignetteSoftness, vignetteRoundness, fishEyeStrength, fishEyeRadius,
                fishEyeZoom, fishEyeChromatic, fishEyeEdgeDarkening, fishEyeRemoveBlackEdges, cinematicTopHeight,
                cinematicBottomHeight, cinematicFeather, cinematicAnimationSpeed, cinematicAnimated,
                cinematicCenterOffset, cinematicShadow, overlayText, fadeInTicks, fadeOutTicks,
                fadeInEnabled, fadeOutEnabled, visualizerPosition, visualizerOffset, overlayInterface,
                overlayPlayers, overlayEntities, highlightSpectators, highlightSpectatorColor,
                highlightEntities, highlightEntityColor, highlightModelGlow,
                colorBrightness, colorContrast, colorSaturation, colorHue, colorGamma, colorTemperature,
                starSize, starDensity, starTwinkleSpeed, starDriftSpeed, starSpread, starBrightness,
                starIridescence, starDirection, colors, speed, hideParticles, hideFirstPersonHand, hideHud, hideBlockyMusicHud);
    }

    private static List<Integer> readObjectOutlineColors(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_OBJECT_OUTLINE_COLORS) {
            throw new DecoderException("Invalid object outline palette size: " + count);
        }
        List<Integer> colors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            colors.add(buffer.readInt());
        }
        return colors;
    }

    private static List<Integer> objectOutlineColors(JsonObject object) {
        try {
            if (!object.has("objectOutlineColors") || !object.get("objectOutlineColors").isJsonArray()) {
                return List.of();
            }
            JsonArray array = object.getAsJsonArray("objectOutlineColors");
            List<Integer> colors = new ArrayList<>(Math.min(array.size(), MAX_OBJECT_OUTLINE_COLORS));
            for (int index = 0; index < array.size() && colors.size() < MAX_OBJECT_OUTLINE_COLORS; index++) {
                try {
                    colors.add(array.get(index).getAsInt());
                } catch (RuntimeException ignored) {
                }
            }
            return colors.size() >= 2 ? colors : List.of();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<Integer> sanitizeObjectOutlineColors(List<Integer> colors) {
        if (colors == null || colors.isEmpty()) {
            return List.of();
        }
        List<Integer> safe = new ArrayList<>(Math.min(colors.size(), MAX_OBJECT_OUTLINE_COLORS));
        for (Integer color : colors) {
            if (color == null || safe.size() >= MAX_OBJECT_OUTLINE_COLORS) {
                continue;
            }
            safe.add(0xFF000000 | (color & 0x00FFFFFF));
        }
        return List.copyOf(safe);
    }

    private static float legacyScanlineOpacity(float legacyNoise) {
        return Mth.clamp((30.0F + 38.0F * legacyNoise) / 68.0F, 0.0F, 1.0F);
    }

    private static float legacyNoiseDensity(float legacyNoise) {
        return Mth.clamp((2.0F + 42.0F * legacyNoise) / 44.0F, 0.0F, 1.0F);
    }

    /**
     * Before vignette colors became configurable its saved color was only a placeholder and the
     * renderer always used near-black. Keep old charts visually identical while allowing every
     * newly exported vignette (which contains the type-specific settings) to use its chosen color.
     */
    public static int migrateLegacyColor(TrackEffect.Type type, JsonObject object, int color) {
        if (type == TrackEffect.Type.VIGNETTE && (object == null || !object.has("vignetteRadius"))) {
            return 0xFF050009;
        }
        return color;
    }

    private static float number(JsonObject object, String name, float fallback) {
        try {
            return object.has(name) ? object.get(name).getAsFloat() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        try {
            return object.has(name) ? object.get(name).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String name, String fallback) {
        try {
            return object.has(name) ? object.get(name).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String normalizeVisualizerPosition(String value) {
        String normalized = value == null ? "bottom" : value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "top", "left", "right" -> normalized;
            default -> "bottom";
        };
    }

    private static String normalizeStarDirection(String value) {
        String normalized = value == null ? "down_right"
                : value.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "right", "left", "down", "up", "down_right", "down_left", "up_right", "up_left" -> normalized;
            default -> "down_right";
        };
    }
}
