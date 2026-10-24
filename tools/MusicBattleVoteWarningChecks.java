import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ClientBattleSettings;
import ru.gora.musicbattle.client.MusicBattleVoteScreen;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.BattleSetup;
import ru.gora.musicbattle.client.workshop.layout.BattleSetupLayout;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.network.OpenVotePayload;

/**
 * Exercises the real warning input handlers and real client-settings IO without launching Minecraft.
 *
 * <p>The warning about other players' work used to sit behind the first category button of the old vote menu. It now
 * sits behind the map slot of the battle setup screen, and only on a server that carries a track one of its players
 * published. The screen's frame is laid out the way {@code vote-layout} lays it out - the pure
 * {@link BattleSetupLayout}, the bundled fonts through java.awt, the real language file - and handed to the screen, so
 * every press here goes through the screen's own {@code mouseClicked} and {@code keyPressed} on the rectangles it
 * would draw. The Workshop picker behind the slot needs a window, so the screen's opener is swapped for a counter:
 * what the check proves is that the player is handed on, or held at the warning.
 */
public final class MusicBattleVoteWarningChecks {
    private static int checks;
    private static Path config;
    private static TextMeasurer measurer;
    private static Tr tr;

    /** One screen and the number of times it handed the player to the Workshop picker. */
    private record Harness(MusicBattleVoteScreen screen, AtomicInteger pickerOpens) {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        measurer = new AwtMeasurer(root.resolve("src/main/resources/assets/music_battle/font"));
        tr = new LangTr(root.resolve("src/main/resources/assets/music_battle/lang/en_us.json"));
        Path game = Files.createTempDirectory("blocky-vote-warning-check-");
        FMLPaths.loadAbsolutePaths(game);
        Files.createDirectories(FMLPaths.CONFIGDIR.get());
        config = FMLPaths.CONFIGDIR.get().resolve("music_battle-client.json");

        checkedMouseAcceptPersistsAcrossRestart();
        checkedKeyboardAcceptPersists();
        uncheckedAcceptStaysInThisScreenOnly();
        checkedCancelDoesNotPersist();
        checkedEscapeDoesNotPersist();
        noPlayerTracksNoWarning();
        serverCloseEndsHeldVote();

        System.out.println("Vote warning checks PASS: " + checks);
        System.out.println("Isolated test config retained: " + config);
    }

    private static void checkedMouseAcceptPersistsAcrossRestart() throws Exception {
        resetSettings();
        Harness harness = newScreen(true);
        openWarning(harness);
        clickCheckbox(harness.screen());
        clickAccept(harness.screen());
        check(savedHidePreference(), "checked mouse accept saves the warning preference");
        check(!warningVisible(harness.screen()), "checked mouse accept closes the warning");
        check(harness.pickerOpens().get() == 1, "accepting the warning opens the Workshop picker");

        resetSettingsCacheOnly();
        Harness restarted = newScreen(true);
        pressSlot(restarted);
        check(!warningVisible(restarted.screen()), "disk-loaded preference skips the warning after restart");
        check(restarted.pickerOpens().get() == 1, "disk-loaded preference opens the Workshop picker directly");
    }

    private static void checkedKeyboardAcceptPersists() throws Exception {
        resetSettings();
        Harness harness = newScreen(true);
        openWarning(harness);
        MusicBattleVoteScreen screen = harness.screen();
        check(screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0), "Space toggles the focused checkbox");
        check(screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0), "Tab moves focus to accept");
        check(screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0), "Enter activates accept");
        check(savedHidePreference(), "keyboard accept saves the checked preference");
        check(!warningVisible(screen), "keyboard accept closes the warning");
        check(harness.pickerOpens().get() == 1, "keyboard accept opens the Workshop picker");
    }

    private static void uncheckedAcceptStaysInThisScreenOnly() throws Exception {
        resetSettings();
        Harness harness = newScreen(true);
        openWarning(harness);
        clickAccept(harness.screen());
        check(!savedHidePreference(), "unchecked accept does not save the warning preference");
        check(harness.pickerOpens().get() == 1, "unchecked accept opens the Workshop picker all the same");
        // Back from the Workshop without a map, the player presses the slot again.
        pressSlot(harness);
        check(!warningVisible(harness.screen()), "unchecked accept keeps the existing per-screen acceptance");
        check(harness.pickerOpens().get() == 2, "the accepted warning does not stand between the slot and the picker");

        Harness nextBattle = newScreen(true);
        pressSlot(nextBattle);
        check(warningVisible(nextBattle.screen()), "an unchecked accept carries over to the next battle");
        check(nextBattle.pickerOpens().get() == 0, "the next battle opens the picker past the warning");
    }

    private static void checkedCancelDoesNotPersist() throws Exception {
        resetSettings();
        Harness harness = newScreen(true);
        openWarning(harness);
        clickCheckbox(harness.screen());
        clickCancel(harness.screen());
        check(!savedHidePreference(), "cancel does not save the checked preference");
        check(!warningVisible(harness.screen()), "cancel closes the warning");
        check(harness.pickerOpens().get() == 0, "cancel opens the Workshop picker anyway");
        openWarning(harness);
    }

    private static void checkedEscapeDoesNotPersist() throws Exception {
        resetSettings();
        Harness harness = newScreen(true);
        openWarning(harness);
        clickCheckbox(harness.screen());
        check(harness.screen().keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0), "Escape is consumed by the warning");
        check(!savedHidePreference(), "Escape does not save the checked preference");
        check(harness.pickerOpens().get() == 0, "Escape opens the Workshop picker anyway");
        openWarning(harness);
    }

    /**
     * The rule the old track list kept: the warning is about tracks the server's players published, so a server that
     * carries none of them lets the slot open the Workshop without a modal in the way.
     */
    private static void noPlayerTracksNoWarning() throws Exception {
        resetSettings();
        Harness harness = newScreen(false);
        pressSlot(harness);
        check(!warningVisible(harness.screen()), "a server without players' tracks raises the warning");
        check(harness.pickerOpens().get() == 1, "a server without players' tracks does not open the picker at once");
        check(!savedHidePreference(), "opening the picker without the warning saved an answer nobody gave");
        pressSlot(harness);
        check(harness.pickerOpens().get() == 2, "the slot stops opening the picker");
    }

    // ---------------------------------------------------------------- the screen

    /**
     * The battle closing ends a vote the player pressed for a map still on its way, through the screen's own hook: the
     * vote is no longer held, it cannot be claimed when the map lands, and the vote button no longer takes a press.
     */
    private static void serverCloseEndsHeldVote() throws Exception {
        resetSettings();
        Harness harness = newScreen(false);
        MusicBattleVoteScreen screen = harness.screen();
        BattleSetup setup = (BattleSetup) field("setup").get(screen);
        setup.setPick(BattleSetup.Pick.workshop(4242L, 1, "a".repeat(64), "Held", "", null, List.of("hard"),
                List.of("Hard"), 0, BattleSetup.Origin.WORKSHOP));
        setup.setFetch(BattleSetup.Fetch.WORKING);
        setup.setWaitingForMap(true);
        check(setup.waitingForMap(), "the vote is not held for a map that is still downloading");
        screen.closeFromServer();
        check(setup.ended() && !setup.waitingForMap(), "the battle closing leaves the vote held");
        setup.setFetch(BattleSetup.Fetch.READY);
        check(!setup.heldVoteDue() && !setup.claimVote(), "a vote is claimed after the battle closed");
        Rect confirm = ((Layout) field("frame").get(screen)).rect(BattleSetupLayout.CONFIRM);
        check(setup.press(BattleSetupLayout.hit((Layout) field("frame").get(screen), confirm.centerX(),
                confirm.centerY())) == BattleSetup.Action.NONE, "the vote takes a press after the battle closed");
    }

    /**
     * A solo battle on a server with one operator track, and with a track a player published when {@code playerTrack}
     * is set. The frame is the one the screen would compute at 640x360, so the slot is pressed where it is drawn.
     */
    private static Harness newScreen(boolean playerTrack) throws Exception {
        List<TrackSummary> rows = new ArrayList<>();
        rows.add(new TrackSummary("server_song", "Server Song", "", 120, 2400, "", "", TrackSummary.Source.SERVER,
                0L, "", ""));
        if (playerTrack) {
            rows.add(new TrackSummary("published", "Published", "", 120, 2400, "", "Someone",
                    TrackSummary.Source.USER, 0L, "", ""));
        }
        MusicBattleVoteScreen screen = new MusicBattleVoteScreen(new OpenVotePayload(
                UUID.randomUUID(), "", true, false, true, 30, List.copyOf(rows), false));
        screen.width = 640;
        screen.height = 360;
        BattleSetup setup = (BattleSetup) field("setup").get(screen);
        BattleSetupLayout.State state = setup.state(tr, new BattleSetup.Frame(600, false, null, false));
        field("frame").set(screen, BattleSetupLayout.compute(screen.width, screen.height, measurer, tr, state));
        field("frameState").set(screen, state);
        field("framedWidth").setInt(screen, screen.width);
        field("framedHeight").setInt(screen, screen.height);
        Layout warning = BattleSetupLayout.warning(screen.width, screen.height, measurer, tr, BattleSetup.warning(tr));
        field("warningFrame").set(screen, warning);
        field("warningWidth").setInt(screen, screen.width);
        field("warningHeight").setInt(screen, screen.height);
        // The presses below are the pixels players have always pressed; the layout has to keep them where they were.
        check(warning.rect(BattleSetupLayout.WARNING_TOGGLE).contains(80, 207), "the checkbox moved off its pixels");
        check(warning.rect(BattleSetupLayout.WARNING_ACCEPT).contains(220, 245), "the accept button moved off its pixels");
        check(warning.rect(BattleSetupLayout.WARNING_CANCEL).contains(420, 245), "the cancel button moved off its pixels");
        AtomicInteger opens = new AtomicInteger();
        Runnable counter = opens::incrementAndGet;
        field("showPicker").set(screen, counter);
        return new Harness(screen, opens);
    }

    private static void openWarning(Harness harness) throws Exception {
        int before = harness.pickerOpens().get();
        pressSlot(harness);
        check(warningVisible(harness.screen()), "the map slot opens the players' warning");
        check(harness.pickerOpens().get() == before, "the map slot opens the picker past the players' warning");
    }

    /** A press on the middle of the map slot, through the screen's own handler and hit test. */
    private static void pressSlot(Harness harness) throws Exception {
        Rect slot = ((Layout) field("frame").get(harness.screen())).rect(BattleSetupLayout.SLOT);
        check(!slot.empty(), "the map slot is laid out");
        check(BattleSetupLayout.SLOT.equals(BattleSetupLayout.hit((Layout) field("frame").get(harness.screen()),
                slot.centerX(), slot.centerY())), "a press on the map slot lands on something else");
        check(harness.screen().mouseClicked(slot.centerX(), slot.centerY(), 0), "the map slot press is handled");
    }

    /** The warning keeps the pixels it has always had, so these presses are the ones players have always made. */
    private static void clickCheckbox(MusicBattleVoteScreen screen) {
        check(screen.mouseClicked(80, 207, 0), "checkbox click is handled");
    }

    private static void clickAccept(MusicBattleVoteScreen screen) {
        check(screen.mouseClicked(220, 245, 0), "accept click is handled");
    }

    private static void clickCancel(MusicBattleVoteScreen screen) {
        check(screen.mouseClicked(420, 245, 0), "cancel click is handled");
    }

    private static boolean warningVisible(MusicBattleVoteScreen screen) throws Exception {
        return field("userTrackWarning").getBoolean(screen);
    }

    private static Field field(String name) throws Exception {
        Field result = MusicBattleVoteScreen.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    // ---------------------------------------------------------------- the settings file

    private static boolean savedHidePreference() throws Exception {
        if (!Files.isRegularFile(config)) {
            return false;
        }
        JsonObject object = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        return object.has("hideUserTrackWarning") && object.get("hideUserTrackWarning").getAsBoolean();
    }

    private static void resetSettings() throws Exception {
        Files.writeString(config, "{}");
        resetSettingsCacheOnly();
    }

    private static void resetSettingsCacheOnly() throws Exception {
        Field loaded = ClientBattleSettings.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
        Field hide = ClientBattleSettings.class.getDeclaredField("hideUserTrackWarning");
        hide.setAccessible(true);
        hide.setBoolean(null, false);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    // ---------------------------------------------------------------- measuring, as vote-layout does

    /** The bundled Nunito faces through java.awt: the same files Minecraft rasterises, at the same sizes. */
    private static final class AwtMeasurer implements TextMeasurer {
        private final Map<String, Font> faces = new HashMap<>();
        private final FontRenderContext context = new FontRenderContext(null, true, true);

        AwtMeasurer(Path fontDirectory) throws Exception {
            for (TextStyle style : TextStyle.values()) {
                Font base = faces.get(style.fontFile());
                if (base == null) {
                    base = Font.createFont(Font.TRUETYPE_FONT, fontDirectory.resolve(style.fontFile() + ".ttf").toFile());
                    faces.put(style.fontFile(), base);
                }
                faces.put(style.name(), base.deriveFont(style.size()));
            }
        }

        @Override
        public float width(String text, TextStyle style) {
            if (text == null || text.isEmpty()) {
                return 0F;
            }
            return (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
        }
    }

    /** Minecraft's language file as the screen sees it, with {@code %s} arguments substituted. */
    private static final class LangTr implements Tr {
        private final Map<String, String> strings = new HashMap<>();

        LangTr(Path file) throws Exception {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                strings.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        @Override
        public String get(String key, Object... args) {
            String value = strings.get(key);
            if (value == null) {
                return key;
            }
            if (args.length == 0) {
                return value;
            }
            try {
                return String.format(Locale.ROOT, value, args);
            } catch (RuntimeException notAFormat) {
                return value;
            }
        }
    }
}
