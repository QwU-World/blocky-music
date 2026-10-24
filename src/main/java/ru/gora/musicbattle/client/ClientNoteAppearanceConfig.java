package ru.gora.musicbattle.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.data.NoteAppearance;

/** Reads optional appearance fields independently so old or partial configs keep their colors. */
final class ClientNoteAppearanceConfig {
    private ClientNoteAppearanceConfig() { }

    static NoteAppearance read(JsonObject object, int panelColor) {
        int skin = NoteAppearance.BARS;
        try { if (object.has("noteSkin")) skin = object.get("noteSkin").getAsInt(); }
        catch (RuntimeException ignored) { }
        List<Integer> colors = new ArrayList<>(NoteAppearance.solid(panelColor).colors());
        if (object.has("laneColors") && object.get("laneColors").isJsonArray()) {
            JsonArray array = object.getAsJsonArray("laneColors");
            for (int lane = 0; lane < Math.min(4, array.size()); lane++) {
                try { colors.set(lane, array.get(lane).getAsInt()); }
                catch (RuntimeException ignored) { }
            }
        }
        return new NoteAppearance(skin, colors);
    }

    static void write(JsonObject object, NoteAppearance appearance) {
        object.addProperty("noteSkin", appearance.skin());
        JsonArray colors = new JsonArray();
        for (int color : appearance.colors()) colors.add(color);
        object.add("laneColors", colors);
    }
}
