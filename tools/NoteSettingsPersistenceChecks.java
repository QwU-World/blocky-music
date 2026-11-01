import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;
import com.google.gson.*;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.client.ClientBattleSettings;
import ru.gora.musicbattle.data.NoteAppearance;

/** Uses real settings IO in an isolated game directory, never the user's profile. */
public final class NoteSettingsPersistenceChecks {
    static int checks;
    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("blocky-note-settings-check-");
        FMLPaths.loadAbsolutePaths(game);
        Files.createDirectories(FMLPaths.CONFIGDIR.get());
        Path config = FMLPaths.CONFIGDIR.get().resolve("music_battle-client.json");
        Files.writeString(config, "{\"panelColor\":-15654349,\"noteSpeed\":20,\"audioOffsetMs\":67,\"inputOffsetMs\":-21}");
        check(ClientBattleSettings.appearance().equals(NoteAppearance.solid(0xFF112233)), "existing panel color initializes all four lanes");
        check(ClientBattleSettings.noteSpeed() == 20F, "settings load does not clamp high speed back to ten");
        ClientBattleSettings.setNoteSkin(NoteAppearance.ARROWS);
        ClientBattleSettings.setLaneColor(2, 0xFFABCDEF);
        ClientBattleSettings.setPanelColor(0xFF6789AB);
        JsonObject saved = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        check(saved.get("noteSkin").getAsInt() == 1, "actual settings save persists the chosen skin");
        check(saved.get("audioOffsetMs").getAsInt() == 67 && saved.get("inputOffsetMs").getAsInt() == -21,
                "editing appearance preserves calibration");
        Field loaded = ClientBattleSettings.class.getDeclaredField("loaded"); loaded.setAccessible(true); loaded.setBoolean(null, false);
        Field look = ClientBattleSettings.class.getDeclaredField("appearance"); look.setAccessible(true); look.set(null, NoteAppearance.solid(0));
        check(ClientBattleSettings.appearance().equals(new NoteAppearance(1, List.of(0xFF112233, 0xFF112233, 0xFFABCDEF, 0xFF112233))),
                "fresh reload restores skin and independent colors after changing background");
        check(ClientBattleSettings.panelColor() == 0xFF6789AB, "background remains independent after reload");
        ClientBattleSettings.setNoteSkin(NoteAppearance.CIRCLES);
        check(ClientBattleSettings.appearance().color(2) == 0xFFABCDEF, "switching shape preserves lane palette");
        backdrop(config, loaded);
        System.out.println("Note settings persistence PASS: " + checks);
        System.out.println("Isolated test config retained: " + config);
    }
    /**
     * How dark a battle is behind its panels: a file from before these settings keeps the battle's old look and is not
     * rewritten, choices survive a reload, the bars keep their opacity while switched off, and values a player typed
     * into the file are clamped to what the sliders offer or fall back to the default instead of drawing nothing or
     * everything.
     */
    static void backdrop(Path config, Field loaded) throws Exception {
        float dimmingDefault = 70 / 255F, barDefault = 185 / 255F, dimmingMax = .9F;
        String old = "{\"noteSpeed\":5.0,\"panelOpacity\":0.25}";
        Files.writeString(config, old);
        loaded.setBoolean(null, false);
        check(Float.compare(ClientBattleSettings.worldDimming(), dimmingDefault) == 0 && ClientBattleSettings.worldDimmingAlpha() == 70,
                "a file without the dimming does not get the old argb(70, 0, 0, 0)");
        check(ClientBattleSettings.stageBars() && Float.compare(ClientBattleSettings.stageBarOpacity(), barDefault) == 0
                && ClientBattleSettings.stageBarAlpha() == 185, "a file without the bars does not get the old bars at alpha 185");
        check(Files.readString(config).equals(old), "reading a file from before the backdrop settings rewrote it");
        ClientBattleSettings.setWorldDimming(0F);
        ClientBattleSettings.setStageBarOpacity(.2F);
        ClientBattleSettings.setStageBars(false);
        JsonObject saved = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        check(saved.get("worldDimming").getAsFloat() == 0F && !saved.get("stageBars").getAsBoolean()
                && saved.get("stageBarOpacity").getAsFloat() == .2F, "the backdrop settings are not saved");
        loaded.setBoolean(null, false);
        check(ClientBattleSettings.worldDimming() == 0F && ClientBattleSettings.worldDimmingAlpha() == 0,
                "a dimming of 0% does not survive a reload as no veil at all");
        check(!ClientBattleSettings.stageBars() && ClientBattleSettings.stageBarAlpha() == 0
                && ClientBattleSettings.stageBarOpacity() == .2F, "bars switched off lose their opacity or still draw after a reload");
        ClientBattleSettings.setStageBars(true);
        check(ClientBattleSettings.stageBarAlpha() == Math.round(.2F * 255F), "bars switched back on do not come back as they were");
        Files.writeString(config, "{\"worldDimming\":5,\"stageBars\":\"yes\",\"stageBarOpacity\":-3}");
        loaded.setBoolean(null, false);
        check(ClientBattleSettings.worldDimming() == dimmingMax && ClientBattleSettings.stageBars()
                && ClientBattleSettings.stageBarOpacity() == 0F && ClientBattleSettings.stageBarAlpha() == 0,
                "out-of-range backdrop values are not clamped to the sliders' ends");
        Files.writeString(config, "{\"worldDimming\":NaN,\"stageBars\":1,\"stageBarOpacity\":\"half\"}");
        loaded.setBoolean(null, false);
        check(Float.compare(ClientBattleSettings.worldDimming(), dimmingDefault) == 0 && ClientBattleSettings.stageBars()
                && Float.compare(ClientBattleSettings.stageBarOpacity(), barDefault) == 0,
                "a value that is not a number does not fall back to the default");
    }

    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
