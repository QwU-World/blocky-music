package ru.gora.musicbattle.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/**
 * Reads the chart the server built for one battle.
 *
 * <p>The order of {@link LoadedChart#notes()} is the battle's note numbering: every judgement names a note by its
 * position in this list, and the server resolves that number against its own runtime notes. Each note in the file
 * carries the number the server gave it, and this reader puts it there, so the two numberings are the same list by
 * construction. Nothing here may sort, filter or merge the notes on top of that.
 *
 * <p>What it refuses is said with a translation key ({@link ClientTrackExporter.Problem}), in the player's own language:
 * the reason, after «карта повреждена: » / "Invalid beatmap: ", goes to the server's log and, for a spectator whose
 * download failed, to that spectator's own chat. The other players only learn whose client failed
 * ({@code BattleManager.reportClientError}, {@code server.client_error_player}).
 */
public final class ClientTrackChart {
    /**
     * Twice the server's own chart limit, because a shared-notes battle mirrors every note of the map onto both
     * sides and the runtime chart it then writes is twice as long as the map it came from.
     */
    private static final int MAX_NOTES = 8192;

    private ClientTrackChart() {
    }

    public static LoadedChart read(Path path, String expectedTrackId) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            String trackId = root.has("id") ? root.get("id").getAsString() : "";
            if (!trackId.equals(expectedTrackId)) {
                throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_mismatch");
            }
            // The right beatmap with no list of notes is broken, not another beatmap's file, which it used to be called.
            if (!root.has("notes") || !root.get("notes").isJsonArray()) {
                throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_no_notes");
            }
            JsonArray array = root.getAsJsonArray("notes");
            if (array.size() > MAX_NOTES) {
                throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_too_many_notes");
            }
            List<BattleNote> notes = new ArrayList<>(array.size());
            BattleNote[] byServerIndex = new BattleNote[array.size()];
            boolean numbered = array.size() > 0;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_bad_note");
                }
                JsonObject note = element.getAsJsonObject();
                int owner = integer(note, "owner");
                int lane = integer(note, "lane");
                int tick = integer(note, "tick");
                int duration = integer(note, "durationTicks");
                if (owner < 0 || owner > 1 || lane < 0 || lane > 3 || tick < 0 || duration < 0) {
                    throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_note_out_of_range");
                }
                BattleNote parsed = new BattleNote(owner, lane, tick, duration);
                notes.add(parsed);
                int serverIndex = note.has("index") && note.get("index").isJsonPrimitive()
                        ? note.get("index").getAsInt()
                        : -1;
                if (serverIndex < 0 || serverIndex >= byServerIndex.length || byServerIndex[serverIndex] != null) {
                    numbered = false;
                } else {
                    byServerIndex[serverIndex] = parsed;
                }
            }
            // Each note goes where the server numbered it, and otherwise stays where the file put it. This list
            // used to be sorted by tick, owner and lane, which looked harmless because the server sorts its map
            // the same way - but the server numbers the notes it actually plays, after mirroring them for a
            // shared-notes battle and after dividing every tick by the speed. Both of those produce runs the
            // comparator reorders, so note 7 here became a different note from note 7 on the server, and every
            // hit on it was dropped and came back three seconds later as a miss.
            List<BattleNote> ordered = numbered ? List.of(byServerIndex) : List.copyOf(notes);
            if (!numbered && !notes.isEmpty()) {
                MusicBattleMod.LOGGER.warn("Battle chart '{}' does not number its {} notes; judging them by the "
                        + "order they are written in.", path.getFileName(), notes.size());
            }
            List<TrackEffect> effects = new ArrayList<>();
            if (root.has("effects") && root.get("effects").isJsonArray()) {
                int fxRowsPerLayer = root.has("fxRowsPerLayer")
                        ? root.get("fxRowsPerLayer").getAsInt()
                        : TrackEffect.LEGACY_FX_ROWS_PER_LAYER;
                if (fxRowsPerLayer != TrackEffect.LEGACY_FX_ROWS_PER_LAYER
                        && fxRowsPerLayer != TrackEffect.FX_ROWS_PER_LAYER) {
                    throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_fx_grid");
                }
                JsonArray effectArray = root.getAsJsonArray("effects");
                if (effectArray.size() > TrackEffect.MAX_EFFECTS) {
                    throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_too_many_effects");
                }
                for (JsonElement element : effectArray) {
                    if (!element.isJsonObject()) {
                        throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_bad_effect");
                    }
                    JsonObject effect = element.getAsJsonObject();
                    String type = effect.has("type") ? effect.get("type").getAsString() : "flash";
                    // The retired glitch effect has no replacement. Drop those rows instead of
                    // letting Type.byId fall back to FLASH and turn them into flashes.
                    if ("glitch".equalsIgnoreCase(type)) {
                        continue;
                    }
                    boolean removedColor = "color".equalsIgnoreCase(type);
                    TrackEffect.Type effectType = TrackEffect.Type.byId(type);
                    int sourceLane = effect.has("lane") ? effect.get("lane").getAsInt() : 0;
                    int sourceLayer = effect.has("layer") ? effect.get("layer").getAsInt() : 0;
                    int tick = integer(effect, "tick");
                    int duration = integer(effect, "durationTicks");
                    float intensity = effect.has("intensity") ? effect.get("intensity").getAsFloat() : 1.0F;
                    float softness = effect.has("softness") ? effect.get("softness").getAsFloat() : 0.65F;
                    int colorA = effect.has("colorA") ? effect.get("colorA").getAsInt() : 0xFFFFFFFF;
                    colorA = TrackEffectSettings.migrateLegacyColor(effectType, effect, colorA);
                    int colorB = effect.has("colorB") ? effect.get("colorB").getAsInt() : 0xFFFF55C8;
                    String text = effect.has("text") ? effect.get("text").getAsString() : "Blocky Music";
                    String font = effect.has("font") ? effect.get("font").getAsString() : "minecraft:default";
                    float positionX = effect.has("positionX") ? effect.get("positionX").getAsFloat() : 0.5F;
                    float positionY = effect.has("positionY") ? effect.get("positionY").getAsFloat() : 0.32F;
                    String animation = effect.has("animation") ? effect.get("animation").getAsString() : "letters";
                    String exitAnimation = effect.has("exitAnimation") ? effect.get("exitAnimation").getAsString() : "none";
                    String variant = removedColor ? "steady" : effect.has("variant") ? effect.get("variant").getAsString() : "both";
                    boolean beatReactive = effect.has("beatReactive") && effect.get("beatReactive").getAsBoolean();
                    float opacity = effect.has("opacity") ? effect.get("opacity").getAsFloat() : 1.0F;
                    float textSpeed = effect.has("textSpeed") ? effect.get("textSpeed").getAsFloat() : 1.0F;
                    float outlineThickness = effect.has("outlineThickness") ? effect.get("outlineThickness").getAsFloat() : 2.0F;
                    float glowStrength = effect.has("glowStrength") ? effect.get("glowStrength").getAsFloat() : 1.0F;
                    if (sourceLane < 0 || sourceLane >= fxRowsPerLayer || sourceLayer < 0 || sourceLayer > 7 || tick < 0 || duration < 0 || duration > 20 * 60 * 30 || !Float.isFinite(intensity) || !Float.isFinite(softness)) {
                        throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_effect_out_of_range");
                    }
                    TrackEffect.FxPosition position = TrackEffect.migrateFxPosition(sourceLane, sourceLayer, fxRowsPerLayer);
                    effects.add(new TrackEffect(effectType, position.lane(), position.layer(), tick, duration, intensity, softness,
                            colorA, colorB, text, font, positionX, positionY, animation, exitAnimation, variant, beatReactive,
                            opacity, textSpeed, outlineThickness, glowStrength, TrackEffectSettings.readJson(effect)));
                }
                effects.sort(Comparator.comparingInt(TrackEffect::tick).thenComparing(effect -> effect.type().id()));
            }
            boolean disableStageBars = root.has("disableStageBars") && root.get("disableStageBars").getAsBoolean();
            boolean hideStandardDimming = root.has("hideStandardDimming") && root.get("hideStandardDimming").getAsBoolean();
            return new LoadedChart(ordered, List.copyOf(effects), disableStageBars, hideStandardDimming,
                    ChartBeat.readOffsetMs(root));
        } catch (RuntimeException exception) {
            IOException unreadable = new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_unreadable");
            unreadable.initCause(exception);
            throw unreadable;
        }
    }

    private static int integer(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            if ("durationTicks".equals(key)) {
                return 0;
            }
            throw new ClientTrackExporter.Problem("music_battle.l10n.client.beatmap_missing_field", key);
        }
        return object.get(key).getAsInt();
    }

    /** {@code offsetMs} is where beat 1 falls on the battle's timeline, 0 for a chart written before it existed. */
    public record LoadedChart(List<BattleNote> notes, List<TrackEffect> effects, boolean disableStageBars, boolean hideStandardDimming,
                              int offsetMs) {
    }
}
