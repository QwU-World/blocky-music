import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.client.ClientBattleSettings;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.data.PlayerPanelDefaults;

/**
 * Loads the real client settings from an isolated config to prove that only untouched earlier defaults move: the old
 * cyan panel and lanes, and the short-lived violet panel of version 3. It also proves the shared defaults really are
 * palette colours: the settings screen's background behind the notes, lavender and gold lanes.
 */
public final class NoteColorMigrationChecks {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static final int OLD_PANEL = 0xFF77D7FF;
    private static final int VIOLET_PANEL = 0xFF8349D4;
    private static final List<Integer> OLD_LANES = List.of(0xFF77D7FF, 0xFFC9F3F5, 0xFF77D7FF, 0xFFC9F3F5);
    private static final List<Integer> NEW_LANES = List.of(Palette.ACCENT, Palette.ST_FEATURED, Palette.ACCENT, Palette.ST_FEATURED);
    private static final List<Integer> MINE = List.of(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00);

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "fresh";
        require(PlayerPanelDefaults.PANEL_COLOR == Palette.BG_0, "default panel colour is not the settings background Palette.BG_0");
        require(PlayerPanelDefaults.VIOLET_PANEL_COLOR == Palette.ACCENT_STRONG, "the version 3 violet is not Palette.ACCENT_STRONG");
        require(PlayerPanelDefaults.APPEARANCE.colors().equals(NEW_LANES), "default lanes are not the palette accent and gold");
        Path game = Files.createTempDirectory("blocky-note-color-migration-");
        FMLPaths.loadAbsolutePaths(game);
        Path config = FMLPaths.CONFIGDIR.get().resolve("music_battle-client.json");
        Files.createDirectories(config.getParent());
        String original = switch (mode) {
            // A version-2 file written this week by the first-run defaults, never touched since.
            case "legacy" -> "{\"configVersion\":2,\"noteSpeed\":5.0,\"panelColor\":" + OLD_PANEL + ",\"noteSkin\":2,"
                    + "\"laneColors\":" + OLD_LANES + ",\"musicVolume\":0.6}";
            // The player recoloured the lanes but kept the panel: the panel moves, their lanes stay.
            case "partial" -> "{\"configVersion\":2,\"panelColor\":" + OLD_PANEL + ",\"noteSkin\":1,\"laneColors\":" + MINE + "}";
            case "chosen" -> "{\"configVersion\":2,\"panelColor\":-15654349,\"noteSkin\":1,\"laneColors\":" + MINE + "}";
            // A version-3 file written by the violet first-run default an hour before it was replaced.
            case "violet" -> "{\"configVersion\":3,\"noteSpeed\":6.5,\"panelColor\":" + VIOLET_PANEL + ",\"noteSkin\":1,"
                    + "\"laneColors\":" + MINE + "}";
            // A player who picked a colour of their own in version 3 keeps it.
            case "chosen3" -> "{\"configVersion\":3,\"panelColor\":-15654349,\"noteSkin\":1,\"laneColors\":" + NEW_LANES + "}";
            // Since version 4 both earlier defaults are deliberate choices and stay.
            case "current" -> "{\"configVersion\":4,\"panelColor\":" + OLD_PANEL + ",\"noteSkin\":1,\"laneColors\":" + OLD_LANES + "}";
            case "current-violet" -> "{\"configVersion\":4,\"panelColor\":" + VIOLET_PANEL + ",\"noteSkin\":1,\"laneColors\":" + NEW_LANES + "}";
            // A file from before lane colours, whose lanes followed the untouched panel colour.
            case "prelane" -> "{\"noteSpeed\":5.0,\"panelColor\":" + OLD_PANEL + "}";
            default -> null;
        };
        if (original != null) Files.writeString(config, original);
        int wantPanel = switch (mode) {
            case "chosen", "chosen3" -> -15654349; case "current" -> OLD_PANEL; case "current-violet" -> VIOLET_PANEL;
            default -> Palette.BG_0;
        };
        List<Integer> wantLanes = switch (mode) {
            case "partial", "chosen", "violet" -> MINE; case "current" -> OLD_LANES; default -> NEW_LANES;
        };
        int wantSkin = switch (mode) { case "legacy" -> 2; case "prelane" -> 0; default -> 1; };
        require(ClientBattleSettings.panelColor() == wantPanel, mode + ": live panel colour " + Integer.toHexString(ClientBattleSettings.panelColor()));
        require(ClientBattleSettings.appearance().colors().equals(wantLanes), mode + ": live lanes " + ClientBattleSettings.appearance().colors());
        require(ClientBattleSettings.appearance().skin() == wantSkin, mode + ": note shape changed by the colour migration");
        String text = Files.readString(config);
        JsonObject saved = JsonParser.parseString(text).getAsJsonObject();
        switch (mode) {
            case "chosen", "chosen3", "current", "current-violet" -> require(text.equals(original), mode + ": a file needing no migration was rewritten");
            default -> {
                require(saved.get("configVersion").getAsInt() == 4, mode + ": file not stamped with config version 4");
                require(saved.get("panelColor").getAsInt() == wantPanel, mode + ": saved panel colour differs");
                List<Integer> lanes = new ArrayList<>();
                for (JsonElement lane : saved.getAsJsonArray("laneColors")) lanes.add(lane.getAsInt());
                require(lanes.equals(wantLanes), mode + ": saved lanes differ " + lanes);
            }
        }
        if (mode.equals("legacy")) {
            require(ClientBattleSettings.musicVolume() == 0.6F, "legacy: migration lost other saved settings");
        }
        if (mode.equals("violet")) {
            require(ClientBattleSettings.noteSpeed() == 6.5F, "violet: migration lost the chosen note speed");
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        System.out.println("Note colour migration (" + mode + ") PASS: " + checks);
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) failures.add(message); }
}
