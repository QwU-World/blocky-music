package ru.gora.musicbattle.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.CalibrationTiming;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.NoteSpeed;
import ru.gora.musicbattle.data.PlayerPanelDefaults;
import ru.gora.musicbattle.network.SettingsPayload;

public final class ClientBattleSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Version 2 moved the default note speed from the legacy value to 5; version 3 moved the default panel and lane
     * colours from cyan to the Workshop palette; version 4 moved the default panel from violet to the settings screen's
     * dark background. Files without a version predate all three.
     */
    static final int CONFIG_VERSION = 4;
    // Initial General Settings for a new config only; load() preserves saved preferences.
    private static float noteSpeed = PlayerPanelDefaults.NOTE_SPEED;
    private static float musicVolume = 0.35F;
    private static int audioOffsetMs;
    private static int inputOffsetMs;
    private static boolean spectatorEffects = true;
    private static boolean hideUserTrackWarning;
    private static int panelColor = PlayerPanelDefaults.PANEL_COLOR;
    private static NoteAppearance appearance = PlayerPanelDefaults.APPEARANCE;
    private static float panelOpacity = PlayerPanelDefaults.PANEL_OPACITY;
    private static float laneOpacity = PlayerPanelDefaults.LANE_OPACITY;
    private static float panelWidthScale = PlayerPanelDefaults.PANEL_WIDTH_SCALE;
    private static float panelHeightScale = PlayerPanelDefaults.PANEL_HEIGHT_SCALE;
    private static float noteWidthScale = PlayerPanelDefaults.NOTE_WIDTH_SCALE;
    private static float noteHeightScale = PlayerPanelDefaults.NOTE_HEIGHT_SCALE;
    private static boolean loaded;

    private ClientBattleSettings() {
    }

    public static float noteSpeed() {
        load();
        return noteSpeed;
    }

    public static void setNoteSpeed(float speed) {
        load();
        noteSpeed = NoteSpeed.clamp(speed);
        save();
        sendToServer();
    }

    public static float musicVolume() {
        load();
        return musicVolume;
    }

    public static void setMusicVolume(float volume) {
        load();
        musicVolume = Mth.clamp(volume, 0.0F, 2.0F);
        save();
        ClientBattleController.refreshMusicVolume();
        TrackEditorScreen.refreshOpenPreviewVolume();
        TrackGameplayPreviewScreen.refreshOpenPreviewVolume();
    }

    public static boolean spectatorEffects() {
        load();
        return spectatorEffects;
    }

    public static int audioOffsetMs() {
        load();
        return audioOffsetMs;
    }

    public static int inputOffsetMs() {
        load();
        return inputOffsetMs;
    }

    public static void setAudioOffsetMs(int milliseconds) {
        load();
        int next = CalibrationTiming.clampOffsetMs(milliseconds);
        if (audioOffsetMs == next) {
            return;
        }
        audioOffsetMs = next;
        save();
        ClientBattleController.refreshAudioCalibration();
        TrackEditorScreen.refreshOpenPreviewCalibration();
        TrackGameplayPreviewScreen.refreshOpenPreviewCalibration();
    }

    public static void setInputOffsetMs(int milliseconds) {
        load();
        int next = CalibrationTiming.clampOffsetMs(milliseconds);
        if (inputOffsetMs == next) {
            return;
        }
        inputOffsetMs = next;
        save();
    }

    public static void setSpectatorEffects(boolean enabled) {
        load();
        spectatorEffects = enabled;
        save();
    }

    public static boolean hideUserTrackWarning() {
        load();
        return hideUserTrackWarning;
    }

    public static void setHideUserTrackWarning(boolean hidden) {
        load();
        if (hideUserTrackWarning == hidden) {
            return;
        }
        hideUserTrackWarning = hidden;
        save();
    }

    public static int panelColor() {
        load();
        return panelColor;
    }

    public static NoteAppearance appearance() {
        load();
        return appearance;
    }

    public static void setNoteSkin(int skin) {
        load();
        NoteAppearance next = new NoteAppearance(skin, appearance.colors());
        if (appearance.equals(next)) return;
        appearance = next;
        save();
        sendToServer();
    }

    public static void setLaneColor(int lane, int color) {
        load();
        if (lane < 0 || lane >= 4) return;
        List<Integer> colors = new ArrayList<>(appearance.colors());
        colors.set(lane, color);
        NoteAppearance next = new NoteAppearance(appearance.skin(), colors);
        if (appearance.equals(next)) return;
        appearance = next;
        save();
        sendToServer();
    }

    public static void setPanelColor(int color) {
        load();
        panelColor = 0xFF000000 | (color & 0x00FFFFFF);
        save();
        sendToServer();
    }

    public static float panelOpacity() {
        load();
        return panelOpacity;
    }

    public static void setPanelOpacity(float opacity) {
        load();
        panelOpacity = Mth.clamp(opacity, 0.0F, 1.0F);
        save();
    }

    public static float laneOpacity() {
        load();
        return laneOpacity;
    }

    public static void setLaneOpacity(float opacity) {
        load();
        laneOpacity = Mth.clamp(opacity, 0.0F, 1.5F);
        save();
    }

    public static float panelWidthScale() {
        load();
        return panelWidthScale;
    }

    public static void setPanelWidthScale(float scale) {
        load();
        panelWidthScale = Mth.clamp(scale, 0.45F, 1.6F);
        save();
    }

    public static float panelHeightScale() {
        load();
        return panelHeightScale;
    }

    public static void setPanelHeightScale(float scale) {
        load();
        panelHeightScale = Mth.clamp(scale, 0.55F, 1.6F);
        save();
    }

    public static float noteSize() {
        load();
        return (noteWidthScale + noteHeightScale) * 0.5F;
    }

    public static void setNoteSize(float scale) {
        load();
        noteWidthScale = Mth.clamp(scale, 0.5F, 1.8F);
        noteHeightScale = Mth.clamp(scale, 0.5F, 1.8F);
        save();
    }

    public static float noteWidthScale() {
        load();
        return noteWidthScale;
    }

    public static void setNoteWidthScale(float scale) {
        load();
        noteWidthScale = Mth.clamp(scale, 0.35F, 2.2F);
        save();
    }

    public static float noteHeightScale() {
        load();
        return noteHeightScale;
    }

    public static void setNoteHeightScale(float scale) {
        load();
        noteHeightScale = Mth.clamp(scale, 0.35F, 2.2F);
        save();
    }

    public static void sendToServer() {
        try {
            PacketDistributor.sendToServer(new SettingsPayload(
                    noteSpeed(),
                    panelColor(),
                    panelOpacity(),
                    laneOpacity(),
                    panelWidthScale(),
                    panelHeightScale(),
                    noteWidthScale(),
                    noteHeightScale(),
                    keyLabels(),
                    appearance()
            ));
        } catch (Exception ignored) {
        }
    }

    public static List<String> keyLabels() {
        return Arrays.stream(MusicBattleKeys.lanes())
                .map(mapping -> mapping.getTranslatedKeyMessage().getString())
                .toList();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            int version = object.has("configVersion") ? object.get("configVersion").getAsInt() : 1;
            boolean migrated = false;
            if (object.has("noteSpeed")) {
                noteSpeed = NoteSpeed.clamp(object.get("noteSpeed").getAsFloat());
                // Only a version-1 file still holding the untouched old default moves to the new one; any speed the
                // player picked, and every file written since version 2, is theirs and stays as it is.
                if (version < 2 && Float.compare(object.get("noteSpeed").getAsFloat(),
                        PlayerPanelDefaults.LEGACY_NOTE_SPEED) == 0) {
                    noteSpeed = PlayerPanelDefaults.NOTE_SPEED;
                    migrated = true;
                }
            }
            if (object.has("musicVolume")) {
                musicVolume = Mth.clamp(object.get("musicVolume").getAsFloat(), 0.0F, 2.0F);
            }
            audioOffsetMs = readCalibrationOffset(object, "audioOffsetMs");
            inputOffsetMs = readCalibrationOffset(object, "inputOffsetMs");
            if (object.has("spectatorEffects")) {
                spectatorEffects = object.get("spectatorEffects").getAsBoolean();
            }
            if (object.has("hideUserTrackWarning")) {
                hideUserTrackWarning = object.get("hideUserTrackWarning").getAsBoolean();
            }
            boolean defaultPanel = true;
            if (object.has("panelColor")) {
                panelColor = 0xFF000000 | (object.get("panelColor").getAsInt() & 0x00FFFFFF);
                defaultPanel = false;
                // As with the speed, only an untouched earlier default (the cyan, or the short-lived violet) moves to
                // the dark backdrop; a colour the player picked is theirs.
                if (version < 4 && (panelColor == PlayerPanelDefaults.LEGACY_PANEL_COLOR
                        || panelColor == PlayerPanelDefaults.VIOLET_PANEL_COLOR)) {
                    panelColor = PlayerPanelDefaults.PANEL_COLOR;
                    defaultPanel = true;
                    migrated = true;
                }
            }
            appearance = ClientNoteAppearanceConfig.read(object, panelColor);
            if (version < 3) {
                // A file from before lane colours derives them from the panel colour, so an untouched panel there
                // means untouched lanes too, and both get the palette rather than four violet lanes.
                boolean untouchedLanes = object.has("laneColors")
                        ? appearance.colors().equals(PlayerPanelDefaults.LEGACY_LANE_COLORS) : defaultPanel;
                if (untouchedLanes) {
                    appearance = new NoteAppearance(appearance.skin(), PlayerPanelDefaults.APPEARANCE.colors());
                    migrated |= object.has("laneColors");
                }
            }
            if (object.has("panelOpacity")) {
                panelOpacity = Mth.clamp(object.get("panelOpacity").getAsFloat(), 0.0F, 1.0F);
            }
            if (object.has("laneOpacity")) {
                laneOpacity = Mth.clamp(object.get("laneOpacity").getAsFloat(), 0.0F, 1.5F);
            }
            if (object.has("panelWidthScale")) {
                panelWidthScale = Mth.clamp(object.get("panelWidthScale").getAsFloat(), 0.45F, 1.6F);
            }
            if (object.has("panelHeightScale")) {
                panelHeightScale = Mth.clamp(object.get("panelHeightScale").getAsFloat(), 0.55F, 1.6F);
            }
            if (object.has("noteSize")) {
                float oldNoteSize = Mth.clamp(object.get("noteSize").getAsFloat(), 0.5F, 1.8F);
                noteWidthScale = oldNoteSize;
                noteHeightScale = oldNoteSize;
            }
            if (object.has("noteWidthScale")) {
                noteWidthScale = Mth.clamp(object.get("noteWidthScale").getAsFloat(), 0.35F, 2.2F);
            }
            if (object.has("noteHeightScale")) {
                noteHeightScale = Mth.clamp(object.get("noteHeightScale").getAsFloat(), 0.35F, 2.2F);
            }
            // A file that needed no migration is left byte for byte; its next ordinary save stamps the version.
            if (migrated) {
                save();
            }
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to read Blocky Music client settings '{}'.", path, exception);
        }
    }

    private static int readCalibrationOffset(JsonObject object, String key) {
        try {
            double value = object.has(key) ? object.get(key).getAsDouble() : 0.0D;
            return Double.isFinite(value) ? (int) Math.round(Math.max(-CalibrationTiming.MAX_OFFSET_MS,
                    Math.min(CalibrationTiming.MAX_OFFSET_MS, value))) : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("configVersion", CONFIG_VERSION);
            object.addProperty("noteSpeed", noteSpeed);
            object.addProperty("musicVolume", musicVolume);
            object.addProperty("audioOffsetMs", audioOffsetMs);
            object.addProperty("inputOffsetMs", inputOffsetMs);
            object.addProperty("spectatorEffects", spectatorEffects);
            object.addProperty("hideUserTrackWarning", hideUserTrackWarning);
            object.addProperty("panelColor", panelColor);
            ClientNoteAppearanceConfig.write(object, appearance);
            object.addProperty("panelOpacity", panelOpacity);
            object.addProperty("laneOpacity", laneOpacity);
            object.addProperty("panelWidthScale", panelWidthScale);
            object.addProperty("panelHeightScale", panelHeightScale);
            object.addProperty("noteWidthScale", noteWidthScale);
            object.addProperty("noteHeightScale", noteHeightScale);
            Files.writeString(path, GSON.toJson(object));
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to save Blocky Music client settings '{}'.", path, exception);
        }
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("music_battle-client.json").toAbsolutePath().normalize();
    }
}
