package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Opens every screen the mod can build from the title screen, one after another, and says so in the log.
 *
 * <p>The regression harnesses under {@code tools/} measure layout without a window, which is what makes them fast
 * and what they cannot do is prove that a screen renders: no real font, no GPU, no screen lifecycle. After the
 * interface was moved onto the toolkit that gap was the whole risk of a release - a screen that throws on its first
 * frame is a dead beta - so this walks them in a real client and either finishes with a line saying how many
 * rendered or dies with a crash report naming the one that did not.
 *
 * <p>Inert unless asked for: nothing happens without {@code -Dmusicbattle.screenSmoke=true}, which only a developer
 * passes. Run it with:
 *
 * <pre>gradle runClient -Dmusicbattle.screenSmoke=true</pre>
 *
 * <p>Screens that need a world, a battle or a server payload are deliberately absent: they cannot be built from the
 * title screen, and pretending otherwise would test a fiction. They are reached by playing.
 */
@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ScreenSmokeTest {
    /** Frames each screen is left on display: enough for several real render passes, short enough to stay quick. */
    private static final int TICKS_PER_SCREEN = 12;

    private static List<Entry> queue;
    private static int index = -1;
    private static int ticks;

    private record Entry(String name, Supplier<Screen> open) {
    }

    private ScreenSmokeTest() {
    }

    /**
     * Whether a developer asked for this run.
     *
     * Read from the environment rather than a system property: the property would have to be threaded through the
     * run configuration to reach the game's own JVM, while the environment is inherited by it for free.
     */
    private static boolean asked() {
        String value = System.getenv("MUSICBATTLE_SCREEN_SMOKE");
        return value != null && !value.isBlank() && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!asked()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (queue == null) {
            // Wait for the title screen: before it there is no window to render into and no resources loaded.
            if (!(minecraft.screen instanceof TitleScreen)) {
                return;
            }
            queue = build(minecraft);
            MusicBattleMod.LOGGER.info("Screen smoke: {} screens to open", queue.size());
        }
        if (++ticks < TICKS_PER_SCREEN) {
            return;
        }
        ticks = 0;
        index++;
        if (index >= queue.size()) {
            MusicBattleMod.LOGGER.info("Screen smoke: all {} screens rendered without throwing", queue.size());
            minecraft.stop();
            return;
        }
        Entry entry = queue.get(index);
        MusicBattleMod.LOGGER.info("Screen smoke: opening {}", entry.name());
        minecraft.setScreen(entry.open().get());
    }

    private static List<Entry> build(Minecraft minecraft) {
        Screen title = minecraft.screen;
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("guide", () -> new MusicBattleGuideScreen(title)));
        entries.add(new Entry("settings", () -> new MusicBattleSettingsScreen(title)));
        // Calibration is reached from the settings screen and takes it as its parent, which is also how a player
        // gets there.
        entries.add(new Entry("calibration", () -> new MusicBattleCalibrationScreen(new MusicBattleSettingsScreen(title))));
        entries.add(new Entry("track library", () -> new TrackLibraryScreen(title)));
        entries.add(new Entry("workshop", () -> new ru.gora.musicbattle.client.workshop.WorkshopScreen(title)));
        return entries;
    }
}
