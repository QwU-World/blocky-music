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
        System.out.println("Note settings persistence PASS: " + checks);
        System.out.println("Isolated test config retained: " + config);
    }
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
