package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TrackLibraryLayout;

/**
 * The beatmap library's three tabs show their labels whole in English and Russian on every window from 320x240 up, at
 * every GUI scale: the labels measured by Minecraft's own FreeType glyph provider at each oversample tier
 * (GameTextMeasurer), the tabs set by the screen's own rule ({@code TrackLibraryScreen.Tabs}) inside the real
 * {@code TrackLibraryLayout}.
 *
 * <p>The layout's equal thirds cut "Опубликованные карты" to "Опубликованные кар…" at 320x240; this pins the fix
 * (each label against the room FancyButton leaves it - the tab's whole-unit width less ten - in the face its height
 * gives it), that the tabs stay one row inside the column and the bar and clear of each other, that they keep their
 * equal widths wherever every label fits an equal share, and that the old thirds did cut the label, so the check still
 * measures what it was written for.
 *
 * <p>NOT COVERED: the screen in the game, and a language whose captions overflow the column (it would get equal tabs
 * with its labels cut, as the old rule did; a second row needs TrackLibraryLayout to grow the bar).
 */
public final class LibraryTabsChecks {
    private static final int[] HEIGHTS = {240, 270, 299, 300, 360, 480, 720, 1080};
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        int layouts = 0;
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (String code : List.of("en_us", "ru_ru")) {
                Map<String, String> strings = read(lang.resolve(code + ".json"));
                List<String> labels = List.of(value(strings, "music_battle.l10n.client.120"),
                        value(strings, "music_battle.l10n.client.121"), value(strings, "screen.music_battle.library.installed"));
                String create = value(strings, "music_battle.l10n.client.122");
                int oldCut = 0;
                int equal = 0;
                int windows = 0;
                for (int width = 320; width <= 1920; width++) {
                    for (int height : HEIGHTS) {
                        String where = code + " at GUI scale " + tier + " " + width + "x" + height;
                        TrackLibraryLayout frame = TrackLibraryLayout.compute(width, height, measurer, create, "Back");
                        TrackLibraryScreen.Tabs.Strip strip = TrackLibraryScreen.Tabs.arrange(frame, measurer, labels);
                        layouts++;
                        windows++;
                        require(!strip.cut(), where + ": no tab label is cut");
                        require(strip.tabs().size() == 3, where + ": three tabs");
                        Rect bar = frame.tabBar();
                        Rect column = frame.column();
                        boolean same = true;
                        for (int index = 0; index < 3; index++) {
                            Rect tab = strip.tabs().get(index);
                            // FancyButton gets whole units and leaves its label the width less ten.
                            int x = Math.round(tab.x());
                            int w = Math.round(tab.w());
                            int h = Math.round(tab.h());
                            TextStyle face = TrackLibraryScreen.Tabs.face(h);
                            require(face == strip.face(), where + ": the strip's face is the one its buttons draw in");
                            float label = measurer.width(labels.get(index), face);
                            require(label <= w - 10 + 0.01F, where + ": '" + labels.get(index) + "' is " + label
                                    + " wide in " + face + ", its tab leaves " + (w - 10));
                            require(tab.y() >= bar.y() && tab.bottom() <= bar.bottom() + 0.01F, where + ": tab "
                                    + index + " sits inside the bar");
                            require(x >= Math.round(column.x()) && x + w <= Math.round(column.right()) + 1,
                                    where + ": tab " + index + " stays inside the column");
                            if (index > 0) {
                                Rect previous = strip.tabs().get(index - 1);
                                require(Math.round(previous.x()) + Math.round(previous.w()) <= x, where
                                        + ": tab " + index + " starts after the one before it");
                            }
                            same &= Math.abs(tab.w() - strip.tabs().get(0).w()) < 0.01F;
                        }
                        // Where every label fits an equal share the tabs keep the equal widths they always had.
                        boolean fitsThirds = true;
                        for (int index = 0; index < 3; index++) {
                            Rect third = frame.tabs().get(index);
                            float label = measurer.width(labels.get(index), TrackLibraryScreen.Tabs.face(third.h()));
                            fitsThirds &= Math.ceil(label + 2 * TrackLibraryScreen.Tabs.PAD + 0.05F)
                                    <= (column.w() - 6) / 3F && third.h() == strip.tabs().get(0).h();
                            if (label > Math.round(third.w()) - 10 + 0.01F) {
                                oldCut++;
                            }
                        }
                        require(!fitsThirds || same, where + ": tabs that all fit an equal share stay equal");
                        equal += same ? 1 : 0;
                    }
                }
                if (code.equals("ru_ru")) {
                    // What the screen drew before, measured: the layout's thirds at 320x240.
                    TrackLibraryLayout narrow = TrackLibraryLayout.compute(320, 240, measurer, create, "Back");
                    Rect third = narrow.tabs().get(1);
                    float published = measurer.width(labels.get(1), TrackLibraryScreen.Tabs.face(Math.round(third.h())));
                    require(published > Math.round(third.w()) - 10, "ru at GUI scale " + tier + ": the old equal thirds "
                            + "did cut '" + labels.get(1) + "' at 320x240 (" + published + " in " + (Math.round(third.w()) - 10)
                            + "), so this check still measures what it was written for");
                }
                System.out.println(code + " at GUI scale " + tier + ": equal tabs on " + equal + " of " + windows
                        + " windows; the old thirds cut a label on " + oldCut + " tab placements");
            }
        }
        System.out.println("Library tabs: " + layouts + " layouts, " + checks + " checks");
        FAILURES.stream().limit(30).forEach(System.err::println);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("Library tabs PASS: en and ru, every width from 320 to 1920, heights 240 to 1080, GUI scales 1-9");
        System.out.println("NOT COVERED: the screen in the game; a language whose captions overflow the column");
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject().entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return values;
    }

    private static String value(Map<String, String> strings, String key) {
        String value = strings.get(key);
        if (value == null) {
            throw new AssertionError("missing language key " + key);
        }
        return value;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition && FAILURES.size() < 300) {
            FAILURES.add("FAIL: " + message);
        }
    }
}
