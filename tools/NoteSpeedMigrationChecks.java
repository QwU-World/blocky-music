import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.client.ClientBattleSettings;

/** Loads the real client settings from an isolated config to prove only an untouched old default speed moves to 5. */
public final class NoteSpeedMigrationChecks {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "fresh";
        Path game = Files.createTempDirectory("blocky-note-speed-migration-");
        FMLPaths.loadAbsolutePaths(game);
        Path config = FMLPaths.CONFIGDIR.get().resolve("music_battle-client.json");
        Files.createDirectories(config.getParent());
        String original = switch (mode) {
            case "legacy" -> "{\"noteSpeed\":1.0035211,\"musicVolume\":0.6,\"panelColor\":-15654349}";
            case "chosen" -> "{\"noteSpeed\":7.5,\"musicVolume\":0.6}";
            // After the migration a player may still drag back to the old value; version 2 marks it as their choice.
            case "current" -> "{\"configVersion\":2,\"noteSpeed\":1.0035211}";
            default -> null;
        };
        if (original != null) Files.writeString(config, original);
        float want = switch (mode) { case "chosen" -> 7.5F; case "current" -> 1.0035211F; default -> 5.0F; };
        require(Float.compare(ClientBattleSettings.noteSpeed(), want) == 0, mode + ": live speed " + ClientBattleSettings.noteSpeed() + " != " + want);
        JsonObject saved = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        require(Float.compare(saved.get("noteSpeed").getAsFloat(), want) == 0, mode + ": saved speed differs");
        switch (mode) {
            case "fresh", "legacy" -> require(saved.has("configVersion") && saved.get("configVersion").getAsInt() == 4,
                    mode + ": file not stamped with the current config version 4");
            default -> require(Files.readString(config).equals(original), mode + ": a file needing no migration was rewritten");
        }
        if (mode.equals("legacy")) {
            require(ClientBattleSettings.musicVolume() == 0.6F && ClientBattleSettings.panelColor() == -15654349,
                    "legacy: migration lost other saved settings");
            require(Float.compare(saved.get("musicVolume").getAsFloat(), 0.6F) == 0, "legacy: rewritten file lost music volume");
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        System.out.println("Note speed migration (" + mode + ") PASS: " + checks);
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) failures.add(message); }
}
