package ru.gora.musicbattle.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Persistent, timeline-independent DJ visual-effect template.
 *
 * <p>The effect's lane/layer/tick/duration are deliberately discarded. A DJ installation owns
 * the lifetime of its effects, while {@link DjVisualEffects} materializes a safe timeline copy for
 * the current track. Keeping the full remaining {@link TrackEffect} data means every existing FX
 * inspector control can be reused without inventing a second settings model.</p>
 */
public record DjVisualEffectConfig(String presetId, boolean enabled, TrackEffect effect) {
    public static final int MAX_CONFIGS = 8;
    public static final int MAX_SERIALIZED_CHARS = 131_072;
    private static final int FORMAT_VERSION = 1;

    public DjVisualEffectConfig {
        effect = template(effect == null ? defaultEffect(TrackEffect.Type.FLASH) : effect);
        presetId = sanitizePresetId(presetId, effect.type().id());
    }

    public static DjVisualEffectConfig of(TrackEffect.Type type) {
        TrackEffect.Type safeType = type == null ? TrackEffect.Type.FLASH : type;
        return new DjVisualEffectConfig(safeType.id(), true, defaultEffect(safeType));
    }

    public DjVisualEffectConfig withEnabled(boolean value) {
        return new DjVisualEffectConfig(presetId, value, effect);
    }

    public DjVisualEffectConfig withPresetId(String value) {
        return new DjVisualEffectConfig(value, enabled, effect);
    }

    public DjVisualEffectConfig withEffect(TrackEffect value) {
        return new DjVisualEffectConfig(presetId, enabled, value);
    }

    /** Creates an always-on copy whose lifetime is controlled by the DJ playback session. */
    public TrackEffect materialize(int sessionDurationTicks) {
        return materialize(0, sessionDurationTicks);
    }

    /**
     * Creates a session copy starting at the last applied/enabled playback tick. This preserves
     * configured fade-in when a DJ changes an effect while music is already playing.
     */
    public TrackEffect materialize(int activationTick, int sessionDurationTicks) {
        int safeSessionDuration = Math.max(1, sessionDurationTicks);
        int start = Math.max(0, Math.min(safeSessionDuration - 1, activationTick));
        int duration = Math.max(1, safeSessionDuration - start + DjFxRelease.tailTicks(effect, 1));
        return new TrackEffect(effect.type(), 0, 0, start, duration, effect.intensity(), effect.softness(),
                effect.colorA(), effect.colorB(), effect.text(), effect.font(), effect.positionX(), effect.positionY(),
                effect.animation(), effect.exitAnimation(), effect.variant(), effect.beatReactive(), effect.opacity(),
                effect.textSpeed(), effect.outlineThickness(), effect.glowStrength(), effect.settings());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(presetId, 32);
        buffer.writeBoolean(enabled);
        effect.write(buffer);
    }

    public static DjVisualEffectConfig read(RegistryFriendlyByteBuf buffer) {
        return new DjVisualEffectConfig(buffer.readUtf(32), buffer.readBoolean(), TrackEffect.read(buffer));
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("version", FORMAT_VERSION);
        object.addProperty("presetId", presetId);
        object.addProperty("enabled", enabled);
        object.addProperty("type", effect.type().id());
        object.addProperty("intensity", effect.intensity());
        object.addProperty("softness", effect.softness());
        object.addProperty("colorA", effect.colorA());
        object.addProperty("colorB", effect.colorB());
        object.addProperty("text", effect.text());
        object.addProperty("font", effect.font());
        object.addProperty("positionX", effect.positionX());
        object.addProperty("positionY", effect.positionY());
        object.addProperty("animation", effect.animation());
        object.addProperty("exitAnimation", effect.exitAnimation());
        object.addProperty("variant", effect.variant());
        object.addProperty("beatReactive", effect.beatReactive());
        object.addProperty("opacity", effect.opacity());
        object.addProperty("textSpeed", effect.textSpeed());
        object.addProperty("outlineThickness", effect.outlineThickness());
        object.addProperty("glowStrength", effect.glowStrength());
        effect.settings().writeJson(object);
        return object;
    }

    public static DjVisualEffectConfig fromJson(JsonObject object) {
        if (object == null) {
            return of(TrackEffect.Type.FLASH);
        }
        TrackEffect.Type type = TrackEffect.Type.byId(string(object, "type", "flash"));
        TrackEffect defaults = defaultEffect(type);
        TrackEffect effect = new TrackEffect(type, 0, 0, 0, type.defaultDurationTicks(),
                number(object, "intensity", defaults.intensity()),
                number(object, "softness", defaults.softness()),
                integer(object, "colorA", defaults.colorA()),
                integer(object, "colorB", defaults.colorB()),
                string(object, "text", defaults.text()),
                string(object, "font", defaults.font()),
                number(object, "positionX", defaults.positionX()),
                number(object, "positionY", defaults.positionY()),
                string(object, "animation", defaults.animation()),
                string(object, "exitAnimation", defaults.exitAnimation()),
                string(object, "variant", defaults.variant()),
                bool(object, "beatReactive", defaults.beatReactive()),
                number(object, "opacity", defaults.opacity()),
                number(object, "textSpeed", defaults.textSpeed()),
                number(object, "outlineThickness", defaults.outlineThickness()),
                number(object, "glowStrength", defaults.glowStrength()),
                TrackEffectSettings.readJson(object));
        return new DjVisualEffectConfig(string(object, "presetId", type.id()),
                bool(object, "enabled", true), effect);
    }

    /** JSON text is convenient for a single bounded BlockEntity NBT string. */
    public static String encodeList(List<DjVisualEffectConfig> configs) {
        JsonArray array = new JsonArray();
        if (configs != null) {
            for (DjVisualEffectConfig config : configs) {
                if (config == null) continue;
                array.add(config.toJson());
                if (array.size() >= MAX_CONFIGS) break;
            }
        }
        return array.toString();
    }

    public static List<DjVisualEffectConfig> decodeList(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_SERIALIZED_CHARS) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(encoded);
            if (!root.isJsonArray()) return List.of();
            List<DjVisualEffectConfig> result = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (result.size() >= MAX_CONFIGS) break;
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (isRetiredType(object)) continue;
                result.add(fromJson(object));
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    public static void writeList(RegistryFriendlyByteBuf buffer, List<DjVisualEffectConfig> configs) {
        List<DjVisualEffectConfig> safe = configs == null ? List.of() : configs.stream()
                .filter(config -> config != null)
                .limit(MAX_CONFIGS)
                .toList();
        buffer.writeVarInt(safe.size());
        safe.forEach(config -> config.write(buffer));
    }

    public static List<DjVisualEffectConfig> readList(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_CONFIGS) {
            throw new IllegalArgumentException("Invalid DJ visual FX count: " + count);
        }
        List<DjVisualEffectConfig> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(read(buffer));
        }
        return List.copyOf(result);
    }

    /**
     * The retired glitch effect has no replacement, so a saved panel simply loses those rows.
     * Without this, {@link TrackEffect.Type#byId} would silently turn each one into a flash.
     */
    private static boolean isRetiredType(JsonObject object) {
        return "glitch".equalsIgnoreCase(string(object, "type", ""));
    }

    private static TrackEffect defaultEffect(TrackEffect.Type type) {
        return new TrackEffect(type, 0, 0, type.defaultDurationTicks(), 1.0F,
                type == TrackEffect.Type.FLASH ? 0.65F : 0.5F);
    }

    private static TrackEffect template(TrackEffect source) {
        return new TrackEffect(source.type(), 0, 0, 0, source.type().defaultDurationTicks(),
                source.intensity(), source.softness(), source.colorA(), source.colorB(), source.text(), source.font(),
                source.positionX(), source.positionY(), source.animation(), source.exitAnimation(), source.variant(),
                source.beatReactive(), source.opacity(), source.textSpeed(), source.outlineThickness(),
                source.glowStrength(), source.settings());
    }

    private static String sanitizePresetId(String value, String fallback) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        StringBuilder safe = new StringBuilder(Math.min(32, normalized.length()));
        for (int index = 0; index < normalized.length() && safe.length() < 32; index++) {
            char character = normalized.charAt(index);
            if (character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
                    || character == '_' || character == '-' || character == '.') {
                safe.append(character);
            }
        }
        return safe.isEmpty() ? fallback : safe.toString();
    }

    private static float number(JsonObject object, String name, float fallback) {
        try {
            float value = object.has(name) ? object.get(name).getAsFloat() : fallback;
            return Float.isFinite(value) ? value : fallback;
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

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        try {
            return object.has(name) ? object.get(name).getAsBoolean() : fallback;
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
}
