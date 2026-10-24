import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.workshop.layout.MenuCornerButtons;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * The mod's corner buttons on vanilla's menus: "BM" on the title, pause and options screens, "Workshop" on the title
 * screen and the pause menu. The owner once found the title screen without its "BM" button, so this proves two things without starting
 * the game: the handler still names all three screens, and at every GUI size the game can produce neither button
 * covers anything vanilla or NeoForge 21.1.230 draws there.
 *
 * <p>Vanilla's rectangles are copied from its own formulas (TitleScreen, LogoRenderer, SplashRenderer, PauseScreen,
 * OptionsScreen, BrandingControl), rounded outwards where text widths depend on the language. Window sizes below
 * 320x240 pixels are left out: there vanilla overlaps its own logo and buttons.
 */
public final class MenuButtonsChecks {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        sources();
        everySize();
        if (!failures.isEmpty()) {
            failures.stream().limit(20).forEach(failure -> System.err.println("FAIL " + failure));
            System.err.println(failures.size() + " of " + checks + " menu button checks failed");
            System.exit(1);
        }
        System.out.println("menu-buttons: " + checks + " checks passed");
    }

    /** The regression the owner saw was a missing screen in the handler, which no geometry would catch. */
    private static void sources() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/ru/gora/musicbattle/client/ClientBattleController.java"));
        int start = controller.indexOf("public static void addOptionsButton(");
        check(start >= 0, "ClientBattleController.addOptionsButton exists");
        String handler = start < 0 ? "" : controller.substring(start, Math.min(controller.length(), start + 1500));
        for (String screen : List.of("TitleScreen", "PauseScreen", "OptionsScreen")) {
            check(handler.contains("instanceof " + screen), "the BM handler adds its button to " + screen);
        }
        check(handler.contains("MenuCornerButtons.settings()"), "the BM button takes its bounds from MenuCornerButtons");
        check(handler.contains("new MusicBattleSettingsScreen(screen)"), "BM opens the mod settings over the screen it is on");
        String events = Files.readString(Path.of("src/main/java/ru/gora/musicbattle/client/ClientGameEvents.java"));
        check(events.contains("ClientBattleController.addOptionsButton(event)"), "screen init still calls the BM handler");
        check(events.contains("WorkshopClientHooks.addTitleButton(event)"), "screen init still calls the Workshop handler");
        String hooks = Files.readString(Path.of("src/main/java/ru/gora/musicbattle/client/workshop/WorkshopClientHooks.java"));
        check(hooks.contains("MenuCornerButtons.workshop(screen.width)") && hooks.contains("MenuCornerButtons.pauseWorkshop()"),
                "both Workshop buttons take their bounds from MenuCornerButtons");
        check(hooks.contains("instanceof TitleScreen") && hooks.contains("instanceof PauseScreen"),
                "the Workshop handler adds its button to the title screen and the pause menu");
    }

    private static void everySize() {
        for (int width = 320; width <= 2560; width++) {
            for (int height = 240; height <= 1440; height += height < 400 ? 1 : 7) {
                size(width, height);
            }
        }
    }

    private static void size(int width, int height) {
        Rect screen = Rect.of(0, 0, width, height);
        Rect settings = MenuCornerButtons.settings();
        Rect workshop = MenuCornerButtons.workshop(width);
        String at = " at " + width + "x" + height;
        check(settings.inside(screen, 0), "BM is on screen" + at);
        check(workshop.inside(screen, 0), "Workshop is on screen" + at);
        check(!settings.overlaps(workshop, 0), "BM and Workshop stay apart" + at);

        // Title screen, NeoForge's normal (not demo) menu: the Mods button pushes the bottom row 22 down.
        int column = height / 4 + 32;
        List<Rect> title = List.of(
                Rect.of(width / 2 - 128, 30, 256, 44),                      // logo
                Rect.of(width / 2 - 64, 30 + 44 - 7, 128, 14),              // "Java Edition" under it
                Rect.of(width / 2 - 124, column, 248, 22 + 84 + 20),        // every button, language to accessibility
                Rect.of(2, height - 10 - 5 * 10, 300, 60),                  // version and NeoForge branding lines
                Rect.of(width - 302, height - 10 - 5 * 10, 302, 60));       // copyright and the lines above it
        // The splash: centred on (width / 2 + 123, 69), at most 180 wide after its scale, turned by 20 degrees.
        Rect splash = Rect.of(width / 2 + 123 - 95, 69 - 56, 190, 112);
        for (Rect vanilla : title) {
            check(!settings.overlaps(vanilla, 0), "BM clears the title screen's " + vanilla + at);
            check(!workshop.overlaps(vanilla, 0), "Workshop clears the title screen's " + vanilla + at);
        }
        check(!settings.overlaps(splash, 0), "BM clears the splash" + at);

        // Pause menu, on servers and in single-player alike: the title at y 40 (y 10 when F3+Esc hides the menu, and
        // then only BM is added), and a grid 212 wide and 190 high, a quarter of the way down, whose first button
        // starts 50 below the grid's top. Every variant - LAN or reporting, server links or feedback - has six rows.
        Rect pauseWorkshop = MenuCornerButtons.pauseWorkshop();
        int gridTop = (height - 190) / 4 - 1;
        List<Rect> pause = List.of(
                Rect.of(width / 2 - 100, 40, 200, 9),
                Rect.of(width / 2 - 106, gridTop + 50, 212, 140));
        for (Rect vanilla : pause) {
            check(!settings.overlaps(vanilla, 0), "BM clears the pause menu's " + vanilla + at);
            check(!pauseWorkshop.overlaps(vanilla, 0), "the pause Workshop button clears " + vanilla + at);
        }
        check(!settings.overlaps(Rect.of(width / 2 - 100, 10, 200, 9), 0), "BM clears the hidden pause menu's title" + at);
        check(!settings.overlaps(pauseWorkshop, 0) && pauseWorkshop.inside(screen, 0),
                "the pause Workshop button sits beside BM, on screen" + at);

        // Options screen: a 61-high header with the title over the FOV slider and Online button (150 + 8 + 150).
        check(!settings.overlaps(Rect.of(width / 2 - 100, 12, 200, 9), 0), "BM clears the options title" + at);
        check(!settings.overlaps(Rect.of(width / 2 - 154, 29, 308, 20), 0), "BM clears the FOV row" + at);
        check(!settings.overlaps(Rect.of(width / 2 - 155, 61, 310, Math.max(0, height - 61 - 33)), 0),
                "BM clears the options grid" + at);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures.add(message);
        }
    }
}
