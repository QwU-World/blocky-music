package ru.gora.musicbattle.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.server.MusicBattleTracks;

public final class ClientFnfChartImporter {
    private ClientFnfChartImporter() {
    }

    /** A note later than this is a broken value, not music: two hours is longer than any song a battle plays. */
    private static final double MAX_NOTE_TIME_MS = 2.0D * 60.0D * 60.0D * 1000.0D;

    public static ImportedChart importChart(String rawPath) throws IOException {
        String cleaned = ClientTrackExporter.cleanPathInput(rawPath);
        if (cleaned.isEmpty()) {
            throw new IOException("FNF JSON path is empty");
        }

        Path path = ClientTrackExporter.requirePath(cleaned).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("FNF JSON not found: " + MusicBattleTracks.displayPath(path));
        }
        return parse(Files.readString(path), path.getFileName().toString());
    }

    /**
     * Reads an FNF chart from its text. Every way the text can be wrong ends in an {@link IOException} the editor
     * shows in its status line; before, Gson's unchecked exceptions escaped the Import button and crashed the game.
     *
     * @param fileName the file's name, for the title when the chart has none and for the description
     */
    public static ImportedChart parse(String text, String fileName) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(trimToLastBrace(text));
            if (!parsed.isJsonObject()) {
                throw new ClientTrackExporter.Problem("music_battle.l10n.editor.chart_unreadable", fileName);
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new ClientTrackExporter.Problem("music_battle.l10n.editor.chart_unreadable", fileName);
        }
        JsonObject song = root.has("song") && root.get("song").isJsonObject() ? root.getAsJsonObject("song") : root;
        int bpm = clamp(number(song, "bpm", 120.0D), 20, 400);
        String title = string(song, "song", stripExtension(fileName));
        String id = safeId(title);
        List<BattleNote> notes = parseNotes(song, bpm);
        return new ImportedChart(id, title, "Imported from FNF chart: " + fileName, bpm, notes);
    }

    /**
     * The text up to its last closing brace. FNF and Psych Engine cut a chart the same way before parsing it, because
     * charts saved by their editors and passed around can carry stray characters after the end; a chart the games
     * themselves load must not be one this importer refuses. A byte-order mark in front is dropped for the same reason.
     */
    static String trimToLastBrace(String text) {
        if (text == null) {
            return "";
        }
        String value = text.startsWith("\uFEFF") ? text.substring(1) : text;
        int end = value.lastIndexOf('}');
        return end >= 0 ? value.substring(0, end + 1) : value;
    }

    private static List<BattleNote> parseNotes(JsonObject song, int bpm) throws IOException {
        if (!song.has("notes") || !song.get("notes").isJsonArray()) {
            throw new IOException("FNF chart has no song.notes array");
        }

        List<BattleNote> notes = new ArrayList<>();
        Set<String> unique = new HashSet<>();

        for (JsonElement sectionElement : song.getAsJsonArray("notes")) {
            if (!sectionElement.isJsonObject()) {
                continue;
            }
            JsonObject section = sectionElement.getAsJsonObject();
            boolean mustHitSection = bool(section, "mustHitSection", false);
            if (!section.has("sectionNotes") || !section.get("sectionNotes").isJsonArray()) {
                continue;
            }

            for (JsonElement noteElement : section.getAsJsonArray("sectionNotes")) {
                if (!noteElement.isJsonArray()) {
                    continue;
                }
                JsonArray note = noteElement.getAsJsonArray();
                if (note.size() < 2) {
                    continue;
                }
                double timeMs = numberOf(note.get(0));
                double laneValue = numberOf(note.get(1));
                // Older Psych charts keep their events in sectionNotes as [time, -1, "name", ...]. Those, entries whose
                // fields are not numbers, and times the game could never reach are not notes; skipping them keeps
                // the rest of the chart instead of failing the whole import on one entry.
                if (!Double.isFinite(timeMs) || !Double.isFinite(laneValue)
                        || laneValue < 0.0D || laneValue > 1000.0D || timeMs > MAX_NOTE_TIME_MS) {
                    continue;
                }
                int rawLane = (int) laneValue;
                double sustainValue = note.size() >= 3 ? numberOf(note.get(2)) : 0.0D;
                double sustainMs = Double.isFinite(sustainValue) ? Math.min(MAX_NOTE_TIME_MS, Math.max(0.0D, sustainValue)) : 0.0D;
                int lane = Math.floorMod(rawLane, 4);
                int owner = mustHitSection ? 0 : 1;
                if (rawLane >= 4) {
                    owner = owner == 0 ? 1 : 0;
                }

                addUnique(notes, unique, owner, lane, msToTick(timeMs), msToTick(sustainMs));
            }
        }

        notes.sort(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::owner).thenComparingInt(BattleNote::lane));
        return List.copyOf(notes);
    }

    private static void addUnique(List<BattleNote> notes, Set<String> unique, int owner, int lane, int tick, int durationTicks) {
        String key = owner + ":" + lane + ":" + tick;
        if (unique.add(key)) {
            notes.add(new BattleNote(owner, lane, tick, durationTicks));
        }
    }

    private static int msToTick(double timeMs) {
        return Math.max(0, (int) Math.round(timeMs / 50.0D));
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private static int number(JsonObject object, String key, double fallback) {
        // A bpm written as "150" is read like 150; one that is not a number at all keeps the fallback.
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            try {
                double value = object.get(key).getAsDouble();
                if (Double.isFinite(value)) {
                    return (int) Math.round(value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return (int) Math.round(fallback);
    }

    /** A note field as a number: a JSON number, or a string holding one. Anything else is NaN. */
    private static double numberOf(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return Double.NaN;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return Double.NaN;
        }
        try {
            return primitive.getAsDouble();
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeId(String input) {
        String id = input == null ? "" : input.toLowerCase().trim().replaceAll("[^a-z0-9_\\-]", "_");
        return id.isBlank() ? "fnf_import" : id;
    }

    public record ImportedChart(String id, String title, String description, int bpm, List<BattleNote> notes) {
    }
}
