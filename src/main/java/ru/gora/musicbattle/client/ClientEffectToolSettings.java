package ru.gora.musicbattle.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/** Persistent per-tool FX presets used by the editor. */
public final class ClientEffectToolSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<TrackEffect.Type, ToolPreset> PRESETS = new EnumMap<>(TrackEffect.Type.class);
    private static boolean loaded;
    private static TrackEffect.Type lastType = TrackEffect.Type.FLASH;

    private ClientEffectToolSettings() {
    }

    public static synchronized TrackEffect preset(TrackEffect.Type type) {
        load();
        return PRESETS.computeIfAbsent(type, ClientEffectToolSettings::defaultToolPreset).effect();
    }

    public static synchronized String placementMode(TrackEffect.Type type) {
        load();
        return PRESETS.computeIfAbsent(type, ClientEffectToolSettings::defaultToolPreset).placementMode();
    }

    public static synchronized TrackEffect.Type lastType() {
        load();
        return lastType;
    }

    public static synchronized void savePreset(TrackEffect effect, String placementMode) {
        load();
        String mode = normalizeMode(placementMode);
        PRESETS.put(effect.type(), new ToolPreset(effect.withLayer(0).withTimeline(effect.lane(), 0, effect.durationTicks()), mode));
        lastType = effect.type();
        save();
    }

    public static synchronized TrackEffect reset(TrackEffect.Type type) {
        load();
        ToolPreset reset = defaultToolPreset(type);
        PRESETS.put(type, reset);
        lastType = type;
        save();
        return reset.effect();
    }

    public static TrackEffect defaults(TrackEffect.Type type) {
        return defaultToolPreset(type).effect();
    }

    private static ToolPreset defaultToolPreset(TrackEffect.Type type) {
        TrackEffect effect = new TrackEffect(type, 0, 0, 0, 1.0F, type == TrackEffect.Type.FLASH ? 0.65F : 0.5F);
        if (type == TrackEffect.Type.VISUALIZER) {
            effect = new TrackEffect(type, 0, 0, 0, 80, 1.0F, 0.5F, type.color(), 0xFFFF55C8,
                    "Blocky Music", "minecraft:default", 0.55F, 0.32F, "letters", "none", "compact",
                    false, 1.0F, 1.0F, 2.0F, 1.0F);
        } else if (type == TrackEffect.Type.STARS) {
            effect = new TrackEffect(type, 0, 0, 0, 80, 1.0F, 0.5F, type.color(), 0xFFFF77D5,
                    "Blocky Music", "minecraft:default", 0.5F, 0.5F, "letters", "none", "both",
                    false, 0.9F, 1.0F, 2.0F, 1.0F,
                    TrackEffectSettings.DEFAULT.withStars(1.0F, 1.0F, 2.0F, 0.35F, 1.0F, 1.0F, 0.75F,
                            "down_right"));
        } else if (type == TrackEffect.Type.FISH_EYE) {
            effect = new TrackEffect(type, 0, 0, 0, 80, 1.0F, 0.5F, type.color(), 0xFFFF55C8,
                    "Blocky Music", "minecraft:default", 0.5F, 0.5F, "letters", "none", "both",
                    false, 1.0F, 1.0F, 2.0F, 1.0F, TrackEffectSettings.DEFAULT);
        } else if (type == TrackEffect.Type.VIGNETTE) {
            effect = new TrackEffect(type, 0, 0, 0, 20, 1.0F, 0.5F, 0xFF050009, 0xFFFF55C8,
                    "Blocky Music", "minecraft:default", 0.5F, 0.5F, "letters", "none", "both",
                    false, 1.0F, 1.0F, 2.0F, 1.0F, TrackEffectSettings.DEFAULT);
        } else if (type == TrackEffect.Type.CINEMATIC_BARS) {
            effect = new TrackEffect(type, 0, 0, 0, 80, 1.0F, 0.5F, 0xFF000000, 0xFF000000,
                    "Blocky Music", "minecraft:default", 0.5F, 0.5F, "letters", "none", "both",
                    false, 1.0F, 1.0F, 2.0F, 1.0F, TrackEffectSettings.DEFAULT);
        } else if (type == TrackEffect.Type.COLOR_CORRECTION) {
            effect = new TrackEffect(type, 0, 0, 0, 80, 1.0F, 0.5F, type.color(), 0xFFFFFFFF,
                    "Blocky Music", "minecraft:default", 0.5F, 0.5F, "letters", "none", "both",
                    false, 1.0F, 1.0F, 2.0F, 1.0F,
                    TrackEffectSettings.DEFAULT.withColorCorrection(0.02F, 1.10F, 1.12F, 0.0F, 1.0F, 0.0F));
        }
        return new ToolPreset(effect, "point");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path path = configPath();
        if (!Files.isRegularFile(path)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.has("lastType")) lastType = TrackEffect.Type.byId(root.get("lastType").getAsString());
            JsonObject tools = root.has("tools") && root.get("tools").isJsonObject() ? root.getAsJsonObject("tools") : new JsonObject();
            for (TrackEffect.Type type : TrackEffect.Type.values()) {
                JsonElement element = tools.get(type.id());
                if (element != null && element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    PRESETS.put(type, new ToolPreset(readEffect(type, object),
                            normalizeMode(string(object, "placementMode", "point"))));
                }
            }
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to read Blocky Music FX tool settings '{}'.", path, exception);
        }
    }

    private static TrackEffect readEffect(TrackEffect.Type type, JsonObject object) {
        int colorA = TrackEffectSettings.migrateLegacyColor(type, object, integer(object, "colorA", type.color()));
        return new TrackEffect(type, integer(object, "lane", 0), 0, 0,
                integer(object, "durationTicks", type.defaultDurationTicks()), number(object, "intensity", 1.0F),
                number(object, "softness", 0.5F), colorA,
                integer(object, "colorB", 0xFFFF55C8), string(object, "text", "Blocky Music"),
                string(object, "font", "minecraft:default"), number(object, "positionX", 0.5F),
                number(object, "positionY", 0.32F), string(object, "animation", "letters"),
                string(object, "exitAnimation", "none"),
                string(object, "variant", type == TrackEffect.Type.VISUALIZER ? "compact" : "both"),
                bool(object, "beatReactive", false), number(object, "opacity", 1.0F),
                number(object, "textSpeed", 1.0F),
                number(object, "outlineThickness", type == TrackEffect.Type.TEXT ? 0.0F : 2.0F),
                number(object, "glowStrength", 1.0F), TrackEffectSettings.readJson(object));
    }

    private static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("lastType", lastType.id());
            JsonObject tools = new JsonObject();
            for (TrackEffect.Type type : TrackEffect.Type.values()) {
                ToolPreset preset = PRESETS.computeIfAbsent(type, ClientEffectToolSettings::defaultToolPreset);
                JsonObject object = writeEffect(preset.effect());
                object.addProperty("placementMode", preset.placementMode());
                tools.add(type.id(), object);
            }
            root.add("tools", tools);
            Files.writeString(path, GSON.toJson(root));
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to save Blocky Music FX tool settings '{}'.", path, exception);
        }
    }

    private static JsonObject writeEffect(TrackEffect effect) {
        JsonObject object = new JsonObject();
        object.addProperty("lane", effect.lane());
        object.addProperty("durationTicks", effect.durationTicks());
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

    private static String normalizeMode(String mode) {
        return "length".equals(mode) || "range".equals(mode) ? mode : "point";
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static float number(JsonObject object, String key, float fallback) {
        return object.has(key) ? object.get(key).getAsFloat() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("music_battle-fx-tools.json").toAbsolutePath().normalize();
    }

    private record ToolPreset(TrackEffect effect, String placementMode) {
    }
}
