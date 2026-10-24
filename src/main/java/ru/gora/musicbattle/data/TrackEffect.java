package ru.gora.musicbattle.data;

import java.util.List;
import java.util.Locale;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record TrackEffect(Type type, int lane, int layer, int tick, int durationTicks, float intensity, float softness,
                          int colorA, int colorB, String text, String font, float positionX, float positionY,
                          String animation, String exitAnimation, String variant, boolean beatReactive, float opacity, float textSpeed,
                          float outlineThickness, float glowStrength, TrackEffectSettings settings, int djOrder) {
    public static final int MAX_EFFECTS = 512;
    public static final int FX_ROWS_PER_LAYER = 6;
    public static final int FX_LAYER_COUNT = 8;
    public static final int MAX_FX_ROWS = FX_ROWS_PER_LAYER * FX_LAYER_COUNT;
    public static final int LEGACY_FX_ROWS_PER_LAYER = 4;

    public TrackEffect {
        djOrder = Math.max(0, Math.min(MAX_EFFECTS, djOrder));
        type = type == null ? Type.FLASH : type;
        lane = Math.max(0, Math.min(FX_ROWS_PER_LAYER - 1, lane));
        layer = Math.max(0, Math.min(FX_LAYER_COUNT - 1, layer));
        tick = Math.max(0, tick);
        durationTicks = Math.max(0, durationTicks);
        // Bounds live in FxRange so the inspector sliders and these clamps cannot drift apart.
        intensity = FxRange.INTENSITY.clamp(intensity);
        softness = FxRange.SOFTNESS.clamp(softness);
        text = safeText(text, 160, "Blocky Music");
        font = safeText(font, 40, "minecraft:default");
        positionX = FxRange.POSITION_X.clamp(positionX);
        positionY = FxRange.POSITION_Y.clamp(positionY);
        animation = safeText(animation, 24, "letters");
        exitAnimation = safeText(exitAnimation, 24, "none");
        variant = safeText(variant, 24, "both");
        opacity = FxRange.OPACITY.clamp(opacity);
        textSpeed = FxRange.TEXT_SPEED.clamp(textSpeed);
        outlineThickness = FxRange.outlineThickness(type).clamp(outlineThickness);
        glowStrength = FxRange.GLOW_STRENGTH.clamp(glowStrength);
        settings = settings == null ? TrackEffectSettings.DEFAULT : settings;
        if (type == Type.OBJECT_OUTLINE && !settings.objectOutlineColors().isEmpty()) {
            colorA = settings.objectOutlineColors().getFirst();
            if (settings.objectOutlineColors().size() == 1) {
                settings = settings.withObjectOutlinePalette(List.of(), settings.objectOutlineColorSpeed());
            }
        }
    }

    /** Persisted/editor positions stay untouched; only materialized DJ effects use this rank. */
    public int renderOrder() { return djOrder > 0 ? -djOrder : layer * FX_ROWS_PER_LAYER + lane; }

    public TrackEffect withDjOrder(int order) {
        return new TrackEffect(type, lane, layer, tick, durationTicks, intensity, softness, colorA, colorB,
                text, font, positionX, positionY, animation, exitAnimation, variant, beatReactive, opacity,
                textSpeed, outlineThickness, glowStrength, settings, order);
    }

    public TrackEffect(Type type, int lane, int layer, int tick, int durationTicks, float intensity, float softness,
                       int colorA, int colorB, String text, String font, float positionX, float positionY,
                       String animation, String exitAnimation, String variant, boolean beatReactive, float opacity,
                       float textSpeed, float outlineThickness, float glowStrength, TrackEffectSettings settings) {
        this(type, lane, layer, tick, durationTicks, intensity, softness, colorA, colorB, text, font,
                positionX, positionY, animation, exitAnimation, variant, beatReactive, opacity, textSpeed,
                outlineThickness, glowStrength, settings, 0);
    }

    public TrackEffect(Type type, int tick, int durationTicks, float intensity) {
        this(type, 0, tick, durationTicks, intensity, 0.65F);
    }

    public TrackEffect(Type type, int lane, int tick, int durationTicks, float intensity) {
        this(type, lane, tick, durationTicks, intensity, 0.65F);
    }

    public TrackEffect(Type type, int lane, int tick, int durationTicks, float intensity, float softness) {
        this(type, lane, 0, tick, durationTicks, intensity, softness,
                type == null ? Type.FLASH.color() : type.color(), 0xFFFF55C8, "Blocky Music",
                "minecraft:default", 0.5F, 0.32F, "letters", "none", "both", false, 1.0F, 1.0F,
                type == Type.TEXT ? 0.0F : 2.0F, 1.0F, TrackEffectSettings.DEFAULT);
    }

    /** Backward-compatible full constructor used by charts created before true FX layers. */
    public TrackEffect(Type type, int lane, int tick, int durationTicks, float intensity, float softness,
                       int colorA, int colorB, String text, String font, float positionX, float positionY,
                       String animation, String variant) {
        this(type, lane, 0, tick, durationTicks, intensity, softness, colorA, colorB, text, font,
                positionX, positionY, animation, "none", variant, false, 1.0F, 1.0F,
                type == Type.TEXT ? 0.0F : 2.0F, 1.0F, TrackEffectSettings.DEFAULT);
    }

    /** Backward-compatible constructor for callers and presets created before exit animations. */
    public TrackEffect(Type type, int lane, int layer, int tick, int durationTicks, float intensity, float softness,
                       int colorA, int colorB, String text, String font, float positionX, float positionY,
                       String animation, String variant, boolean beatReactive, float opacity, float textSpeed,
                       float outlineThickness, float glowStrength) {
        this(type, lane, layer, tick, durationTicks, intensity, softness, colorA, colorB, text, font,
                positionX, positionY, animation, "none", variant, beatReactive, opacity, textSpeed,
                outlineThickness, glowStrength, TrackEffectSettings.DEFAULT);
    }

    /** Backward-compatible full constructor for charts without type-specific controls. */
    public TrackEffect(Type type, int lane, int layer, int tick, int durationTicks, float intensity, float softness,
                       int colorA, int colorB, String text, String font, float positionX, float positionY,
                       String animation, String exitAnimation, String variant, boolean beatReactive, float opacity, float textSpeed,
                       float outlineThickness, float glowStrength) {
        this(type, lane, layer, tick, durationTicks, intensity, softness, colorA, colorB, text, font,
                positionX, positionY, animation, exitAnimation, variant, beatReactive, opacity, textSpeed,
                outlineThickness, glowStrength, TrackEffectSettings.DEFAULT);
    }

    public int visibleDurationTicks() {
        return durationTicks > 0 ? durationTicks : type.defaultDurationTicks();
    }

    /** FLASH used to be split into beat-driven "flash" and a redundant steady "color" effect. */
    public boolean flashFollowsBeat() {
        return type != Type.FLASH || !"steady".equalsIgnoreCase(variant);
    }

    public int endTick() {
        return tick + visibleDurationTicks();
    }

    /** Keeps an effect on the same absolute row when loading a chart made with an older grid. */
    public static FxPosition migrateFxPosition(int lane, int layer, int sourceRowsPerLayer) {
        int rows = sourceRowsPerLayer == FX_ROWS_PER_LAYER ? FX_ROWS_PER_LAYER : LEGACY_FX_ROWS_PER_LAYER;
        int absoluteRow = Math.max(0, layer) * rows + Math.max(0, lane);
        return new FxPosition(absoluteRow % FX_ROWS_PER_LAYER, absoluteRow / FX_ROWS_PER_LAYER);
    }

    public record FxPosition(int lane, int layer) {
    }

    public TrackEffect withTimeline(int newLane, int newTick, int newDurationTicks) {
        return new TrackEffect(type, newLane, layer, newTick, newDurationTicks, intensity, softness, colorA, colorB,
                text, font, positionX, positionY, animation, exitAnimation, variant, beatReactive, opacity, textSpeed,
                outlineThickness, glowStrength, settings, djOrder);
    }

    public TrackEffect withLayer(int newLayer) {
        return new TrackEffect(type, lane, newLayer, tick, durationTicks, intensity, softness, colorA, colorB,
                text, font, positionX, positionY, animation, exitAnimation, variant, beatReactive, opacity, textSpeed,
                outlineThickness, glowStrength, settings, djOrder);
    }

    public TrackEffect withSettings(TrackEffectSettings newSettings) {
        return new TrackEffect(type, lane, layer, tick, durationTicks, intensity, softness, colorA, colorB,
                text, font, positionX, positionY, animation, exitAnimation, variant, beatReactive, opacity,
                textSpeed, outlineThickness, glowStrength, newSettings, djOrder);
    }

    /** Materialized outline palette; legacy effects expose their original solid color as one stop. */
    public List<Integer> objectOutlinePalette() {
        return settings.objectOutlineColors().size() >= 2
                ? settings.objectOutlineColors() : List.of(0xFF000000 | (colorA & 0x00FFFFFF));
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(type.id(), 24);
        buffer.writeVarInt(lane);
        buffer.writeVarInt(layer);
        buffer.writeVarInt(tick);
        buffer.writeVarInt(durationTicks);
        buffer.writeFloat(intensity);
        buffer.writeFloat(softness);
        buffer.writeInt(colorA);
        buffer.writeInt(colorB);
        buffer.writeUtf(text, 160);
        buffer.writeUtf(font, 40);
        buffer.writeFloat(positionX);
        buffer.writeFloat(positionY);
        buffer.writeUtf(animation, 24);
        buffer.writeUtf(exitAnimation, 24);
        buffer.writeUtf(variant, 24);
        buffer.writeBoolean(beatReactive);
        buffer.writeFloat(opacity);
        buffer.writeFloat(textSpeed);
        buffer.writeFloat(outlineThickness);
        buffer.writeFloat(glowStrength);
        settings.write(buffer);
        buffer.writeVarInt(djOrder);
    }

    public static TrackEffect read(RegistryFriendlyByteBuf buffer) {
        return new TrackEffect(Type.byId(buffer.readUtf(24)), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readFloat(), buffer.readFloat(), buffer.readInt(), buffer.readInt(), buffer.readUtf(160),
                buffer.readUtf(40), buffer.readFloat(), buffer.readFloat(), buffer.readUtf(24), buffer.readUtf(24), buffer.readUtf(24),
                buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                TrackEffectSettings.read(buffer), buffer.readVarInt());
    }

    private static String safeText(String value, int maxLength, String fallback) {
        String safe = value == null ? fallback : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        if (safe.isEmpty()) {
            safe = fallback;
        }
        return safe.length() > maxLength ? safe.substring(0, maxLength) : safe;
    }

    public enum Type {
        FLASH("flash", "Flash", 10, 0xFFFFFFFF),
        OLD_TV("old_tv", "Old TV", 20, 0xFF8FB8A0),
        FISH_EYE("fish_eye", "Fisheye", 80, 0xFF62C4FF),
        CINEMATIC_BARS("cinematic_bars", "Cinematic bars", 80, 0xFF000000),
        SHAKE("shake", "Shake", 12, 0xFFFFB45C),
        PULSE("pulse", "Pulse", 20, 0xFF55E6FF),
        VISUALIZER("visualizer", "Visualizer", 80, 0xFF55E6FF),
        TEXT("text", "Text", 60, 0xFFFFFFFF),
        PIXEL_FIRE("pixel_fire", "Pixel fire", 60, 0xFFFF7A32),
        STARS("stars", "Stars", 80, 0xFF8AE7FF),
        PLAYER_HIGHLIGHT("player_highlight", "Player highlight", 40, 0xFFFFFF66),
        OBJECT_OUTLINE("object_outline", "Object outline", 80, 0xFF55E6FF),
        HIDE_BATTLE_PANELS("hide_battle_panels", "Hide overhead battle panels", 80, 0xFF8B9CB8),
        COLOR_CORRECTION("color_correction", "Color correction", 80, 0xFFFFB26B),
        VIGNETTE("vignette", "Vignette", 20, 0xFF9B6CFF),
        HIDE_PARTICLES_HAND("hide_particles_hand", "Hiding", 80, 0xFF91B9B6);

        private final String id;
        private final String displayName;
        private final int defaultDurationTicks;
        private final int color;

        Type(String id, String displayName, int defaultDurationTicks, int color) {
            this.id = id;
            this.displayName = displayName;
            this.defaultDurationTicks = defaultDurationTicks;
            this.color = color;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return net.minecraft.network.chat.Component.translatableWithFallback(translationKey(), displayName).getString();
        }

        public int defaultDurationTicks() {
            return defaultDurationTicks;
        }

        public int color() {
            return color;
        }

        public String translationKey() {
            return "effect.music_battle." + id;
        }

        public static Type byId(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            for (Type type : values()) {
                if (type.id.equals(normalized)) {
                    return type;
                }
            }
            return FLASH;
        }
    }
}
