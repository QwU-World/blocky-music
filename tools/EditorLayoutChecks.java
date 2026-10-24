import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Runs the chart editor's real layout()/grid()/maxScroll() at the window sizes players use and proves that the grid,
 * its timeline header and the transport row can all be reached under the top bar. It also places the form the way
 * init() does and measures every English and Russian label in the editor's own Nunito with AWT, so a label that would
 * be cut or run past its field fails here; AWT's advances are not Minecraft's to the pixel, hence a small margin.
 * Then it proves which presses grab the playhead, and that the grid's lines stand evenly at the exact beat fractions,
 * with every placed note drawn on its line and every page on a bar line. It proves geometry, not pixels.
 */
public final class EditorLayoutChecks {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("ru.gora.musicbattle.client.TrackEditorScreen");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object editor = ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
        Class<?> pageType = Class.forName("ru.gora.musicbattle.client.TrackEditorScreen$EditorPage");
        int topBar = (int) field(type, "TOP_BAR").get(null);
        // GUI sizes: 854x480 at scale 2, 1366x768 at 2, 1920x1080 at 3 and 4, 1920x1080 and 2560x1440 at 2.
        int[][] sizes = {{427, 240}, {683, 384}, {640, 360}, {480, 270}, {960, 540}, {1280, 720}};
        for (int[] size : sizes) {
            for (Object page : pageType.getEnumConstants()) {
                String at = size[0] + "x" + size[1] + " " + page + ": ";
                field(net.minecraft.client.gui.screens.Screen.class, "width").set(editor, size[0]);
                field(net.minecraft.client.gui.screens.Screen.class, "height").set(editor, size[1]);
                field(type, "editorPage").set(editor, page);
                field(type, "scrollY").set(editor, 0);
                Object layout = call(type, editor, "layout");
                int pad = (int) get(layout, "pad");
                int panelTop = page.toString().equals("NOTES") ? (int) get(layout, "formTop") - pad : (int) get(layout, "controlsY") - pad;
                check(panelTop >= topBar + 2, at + "the first panel starts under the top bar, not across it");
                int max = (int) call(type, editor, "maxScroll");
                if (size[1] >= 540) check(max == 0, at + "a large window shows the whole editor without scrolling");
                field(type, "scrollY").set(editor, max);
                layout = call(type, editor, "layout");
                Object grid = call(type, editor, "grid");
                int gridY = (int) get(grid, "y");
                int gridHeight = (int) get(grid, "height");
                check(gridHeight >= 64, at + "grid is at least 64 high, got " + gridHeight);
                check((int) get(grid, "width") >= 200, at + "grid is at least 200 wide");
                check(gridY - 32 >= topBar, at + "timeline header is below the top bar when scrolled to the end");
                check(gridY + gridHeight <= size[1], at + "grid bottom is inside the window when scrolled to the end");
                int lastRowTop = (int) get(layout, "controlsY") + (int) get(layout, "controlsHeight") - (int) get(layout, "buttonHeight");
                check(lastRowTop >= topBar, at + "the play/page row is visible together with the grid");
            }
        }
        checkForm(type, editor, pageType);
        checkPlayhead(type, editor, pageType);
        checkGridDivision(type, editor, pageType);
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        System.out.println("Editor layout PASS: " + checks);
    }

    private static void checkForm(Class<?> type, Object editor, Class<?> pageType) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path assets = root.resolve("src/main/resources/assets/music_battle");
        Font caption = Font.createFont(Font.TRUETYPE_FONT, assets.resolve("font/nunito-semibold.ttf").toFile()).deriveFont(8.0F);
        Font label = Font.createFont(Font.TRUETYPE_FONT, assets.resolve("font/nunito-bold.ttf").toFile()).deriveFont(8.0F);
        List<Map<String, String>> languages = new ArrayList<>();
        for (String code : new String[]{"en_us", "ru_ru"}) {
            languages.add(new com.google.gson.Gson().fromJson(Files.readString(assets.resolve("lang/" + code + ".json")),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType()));
        }
        Object notes = Enum.valueOf((Class) pageType, "NOTES");
        // 1280x720 at GUI scale 2 and 3 are the owner's screenshot; the rest are the sizes the grid check uses.
        int[][] sizes = {{640, 360}, {427, 240}, {683, 384}, {480, 270}, {960, 540}, {1280, 720}};
        for (int[] size : sizes) {
            field(net.minecraft.client.gui.screens.Screen.class, "width").set(editor, size[0]);
            field(net.minecraft.client.gui.screens.Screen.class, "height").set(editor, size[1]);
            field(type, "editorPage").set(editor, notes);
            field(type, "scrollY").set(editor, 0);
            Object layout = call(type, editor, "layout");
            Method formSlots = type.getDeclaredMethod("formSlots", layout.getClass());
            formSlots.setAccessible(true);
            Object slots = formSlots.invoke(editor, layout);
            boolean twoColumns = (boolean) get(layout, "twoColumns");
            int left = (int) get(layout, "left");
            int right = left + (int) get(layout, "panelWidth");
            String[][] fields = {
                    {"id", twoColumns ? "music_battle.l10n.editor.text_025" : "music_battle.l10n.editor.text_032"},
                    {"title", "music_battle.l10n.editor.text_026"},
                    {"description", twoColumns ? "music_battle.l10n.editor.text_027" : "music_battle.l10n.editor.text_033"},
                    {"bpm", null}, {"offset", "music_battle.l10n.editor.offset"}, {"volume", "music_battle.l10n.editor.text_028"},
                    {"audio", "music_battle.l10n.editor.text_029"}, {"preview", "music_battle.l10n.editor.text_030"},
                    {"chart", "music_battle.l10n.editor.text_031"}};
            List<int[]> rects = new ArrayList<>();
            for (String[] entry : fields) {
                Object slot = get(slots, entry[0]);
                int x = (int) get(slot, "x"), y = (int) get(slot, "y"), width = (int) get(slot, "width");
                String at = size[0] + "x" + size[1] + " " + entry[0] + ": ";
                check(x >= left && x + width <= right, at + "field stays inside the panel");
                for (int[] other : rects) {
                    check(other[1] != y || x >= other[0] + other[2] + 4 || x + width + 4 <= other[0], at + "field does not touch its row neighbour");
                }
                rects.add(new int[]{x, y, width});
                for (Map<String, String> language : languages) {
                    String text = entry[1] == null ? "BPM" : language.get(entry[1]);
                    double room = width;
                    if (entry[0].equals("volume")) {
                        room -= measure(label, "2.00") + 4; // the value shares the label row, right-aligned
                    }
                    double needed = measure(caption, text) + 2;
                    check(needed <= room, at + "label '" + text + "' needs " + Math.round(needed) + " of " + Math.round(room));
                }
            }
            int previewSize = (int) get(layout, "previewSize");
            if (previewSize > 0) {
                Object preview = get(slots, "preview");
                int px = (int) get(preview, "x"), py = (int) get(preview, "y"), pw = (int) get(preview, "width");
                int imageX = twoColumns ? right - previewSize : px + pw + 4;
                for (int[] rect : rects) {
                    boolean apart = rect[0] + rect[2] <= imageX || imageX + previewSize <= rect[0]
                            || rect[1] + 18 <= py || py + previewSize <= rect[1];
                    check(apart, size[0] + "x" + size[1] + ": the preview picture does not cover a field at x " + rect[0]);
                }
                check(imageX + previewSize <= right, size[0] + "x" + size[1] + ": the preview picture stays inside the panel");
            }
        }
    }

    private static double measure(Font font, String text) {
        return font.getStringBounds(text, new FontRenderContext(null, true, true)).getWidth();
    }

    private static void checkPlayhead(Class<?> type, Object editor, Class<?> pageType) throws Exception {
        field(net.minecraft.client.gui.screens.Screen.class, "width").set(editor, 640);
        field(net.minecraft.client.gui.screens.Screen.class, "height").set(editor, 360);
        Method over = type.getDeclaredMethod("overPlayhead", double.class, double.class);
        over.setAccessible(true);
        Method tickX = type.getDeclaredMethod("timelineTickX", Class.forName("ru.gora.musicbattle.client.TrackEditorScreen$Grid"), float.class);
        tickX.setAccessible(true);
        Method pageTicks = type.getDeclaredMethod("pageTicks");
        pageTicks.setAccessible(true);
        for (Object page : pageType.getEnumConstants()) {
            field(type, "editorPage").set(editor, page);
            field(type, "scrollY").set(editor, 0);
            field(type, "pageStartTick").set(editor, 0);
            field(type, "previewTick").set(editor, 12);
            Object grid = call(type, editor, "grid");
            int gy = (int) get(grid, "y"), gh = (int) get(grid, "height");
            double line = (int) tickX.invoke(editor, grid, 12.0F) + 0.5D;
            double mid = gy + gh / 2.0D;
            String at = "playhead " + page + ": ";
            check((boolean) over.invoke(editor, line, mid), at + "a press on the line grabs it");
            check((boolean) over.invoke(editor, line + 3.0D, mid) && (boolean) over.invoke(editor, line - 3.0D, mid), at + "three pixels either side still grab it");
            check(!(boolean) over.invoke(editor, line + 5.0D, mid) && !(boolean) over.invoke(editor, line - 5.0D, mid), at + "five pixels away is the grid's");
            check((boolean) over.invoke(editor, line, gy - 1.0D), at + "the overhang above the grid grabs it");
            check(!(boolean) over.invoke(editor, line, gy - 8.0D), at + "the scrubber bar above stays the scrubber's");
            check(!(boolean) over.invoke(editor, line, gy + gh + 3.0D), at + "below the grid is not the line");
            field(type, "pageStartTick").set(editor, (int) pageTicks.invoke(editor));
            check(!(boolean) over.invoke(editor, line, mid), at + "a line on another page cannot be grabbed here");
        }
    }

    /**
     * A grid cell is a quarter (fine: an eighth) of a beat, 1200 / BPM / 4 ticks, seldom a whole number: 3.125 at
     * 96 BPM. The grid used to round that step to whole ticks, so its lines walked off the music (3 instead of 3.125
     * drifts a whole beat in 25 beats), and a later fix drew lines on those rounded ticks, unevenly. Now lines stand
     * evenly at the exact beat fractions, a click on a line places the nearest whole tick (at most half a tick away),
     * that note is drawn on the very pixel of its line, and pages stay on bar lines however far into the song.
     */
    private static void checkGridDivision(Class<?> type, Object editor, Class<?> pageType) throws Exception {
        field(net.minecraft.client.gui.screens.Screen.class, "width").set(editor, 640);
        field(net.minecraft.client.gui.screens.Screen.class, "height").set(editor, 360);
        Class<?> gridType = Class.forName("ru.gora.musicbattle.client.TrackEditorScreen$Grid");
        Method tickAt = method(type, "gridTickAtColumn", int.class);
        Method columnAt = method(type, "gridColumnAt", gridType, double.class);
        Method snap = method(type, "snapTimelineTick", int.class);
        Method tickX = method(type, "timelineTickX", gridType, float.class);
        Method lineX = method(type, "gridLineX", gridType, double.class);
        Method after = method(type, "gridTickAfter", int.class);
        Method before = method(type, "gridTickBefore", int.class);
        Method pageStart = method(type, "pageStart", long.class);
        field(type, "editorPage").set(editor, pageType.getEnumConstants()[0]);
        field(type, "scrollY").set(editor, 0);
        for (String bpm : new String[] {"120", "96", "100", "128", "140", "150", "240"}) {
            int beatsPerMinute = Integer.parseInt(bpm);
            field(type, "draftBpm").set(editor, bpm);
            List<TreeSet<Integer>> lineSets = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            // Page 37 is far enough in for a rounded page length to have slid its bar line off the edge.
            for (long page : new long[] {1L, 37L}) {
                int start = (int) pageStart.invoke(editor, page);
                check(start == (int) Math.floor(page * 9600.0D / beatsPerMinute + 0.5D), "grid " + bpm + " BPM: page " + page + " starts on the rounded bar line");
                for (int division = 0; division < 2; division++) {
                    int precision = division + 1;
                    field(type, "gridDivisionIndex").set(editor, division);
                    field(type, "pageStartTick").set(editor, start);
                    int pageTicks = (int) call(type, editor, "pageTicks");
                    check(start + pageTicks == (int) pageStart.invoke(editor, page + 1), "grid " + bpm + " BPM: page " + page + " ends where the next begins");
                    if (page == 1L) pages.add(pageTicks);
                    Object grid = call(type, editor, "grid");
                    String at = "grid " + bpm + " BPM 1/" + (4 * precision) + " page " + page + ": ";
                    int columns = (int) call(type, editor, "gridColumns");
                    double cell = 1200.0D / (beatsPerMinute * 4.0D * precision);
                    TreeSet<Integer> lines = new TreeSet<>();
                    int previousX = Integer.MIN_VALUE, minGap = Integer.MAX_VALUE, maxGap = 0;
                    for (int column = 0; column < columns; column++) {
                        int x = (int) lineX.invoke(editor, grid, column / (double) columns);
                        if (previousX != Integer.MIN_VALUE) {
                            minGap = Math.min(minGap, x - previousX);
                            maxGap = Math.max(maxGap, x - previousX);
                        }
                        previousX = x;
                        double exact = (page * columns + column) * cell;
                        int tick = (int) tickAt.invoke(editor, column);
                        lines.add(tick - start);
                        check(Math.abs(tick - exact) <= 0.5D + 1e-9 || tick == start + pageTicks - 1, at + "line " + column + " places tick " + tick + ", nearest to " + exact);
                        check(tick >= start && tick < start + pageTicks, at + "line " + column + " places on this page");
                        check((int) snap.invoke(editor, tick) == tick, at + "the line at tick " + tick + " is a snap point");
                        if (cell >= 1.0D) {
                            check((int) tickX.invoke(editor, grid, (float) tick) == x, at + "the note at tick " + tick + " is drawn on its line at x " + x);
                            int clicked = (int) tickAt.invoke(editor, (int) columnAt.invoke(editor, grid, x + 0.5D));
                            check(clicked == tick, at + "a click just right of line " + column + " places on tick " + tick + ", got " + clicked);
                        }
                    }
                    check(maxGap - minGap <= 1, at + "lines are evenly spaced, gaps " + minGap + ".." + maxGap + " px");
                    check(column0X(lineX, editor, grid) == (int) tickX.invoke(editor, grid, (float) start), at + "the page's first tick is drawn on its bar line");
                    int walked = start;
                    for (int line : lines) {
                        check(walked == line + start, at + "the arrow keys step line by line, expected " + (line + start) + " got " + walked);
                        walked = (int) after.invoke(editor, walked);
                    }
                    check(walked == start + pageTicks, at + "the last step reaches the next page");
                    check((int) before.invoke(editor, walked) == lines.last() + start, at + "a step back returns to the last line");
                    if (page == 1L) lineSets.add(lines);
                }
            }
            String at = "grid " + bpm + " BPM: ";
            check(pages.get(0).equals(pages.get(1)), at + "switching the grid never zooms or repages");
            check(lineSets.get(1).containsAll(lineSets.get(0)), at + "every 1/4 line stays a 1/8 line");
            check(lineSets.get(1).size() > lineSets.get(0).size(), at + "1/8 adds positions notes snap to");
        }
        // 96 BPM, the owner's case: a beat is 12.5 ticks, so the quarter cells place 0, 3, 6, 9, 13 (12.5 rounds up),
        // and the step the timeline reports is the real 3.125, not 3.
        field(type, "draftBpm").set(editor, "96");
        field(type, "gridDivisionIndex").set(editor, 0);
        field(type, "pageStartTick").set(editor, 0);
        List<Integer> placed = new ArrayList<>();
        for (int column = 0; column <= 4; column++) placed.add((int) tickAt.invoke(editor, column));
        check(placed.equals(List.of(0, 3, 6, 9, 13)), "grid 96 BPM: quarter cells place " + placed);
        check("3.125".equals(call(type, editor, "exactStepLabel")), "grid 96 BPM: the timeline reports a 3.125-tick step");
        // A BPM typed while on a later page moves the page onto the new BPM's page that holds it.
        field(type, "pageStartTick").set(editor, 300);
        field(type, "draftBpm").set(editor, "120");
        call(type, editor, "alignPageToBpm");
        check((int) field(type, "pageStartTick").get(editor) == 240, "grid: a BPM change re-pages onto a bar line");
        field(type, "draftBpm").set(editor, null);
        field(type, "gridDivisionIndex").set(editor, 0);
        field(type, "pageStartTick").set(editor, 0);
    }

    private static int column0X(Method lineX, Object editor, Object grid) throws Exception {
        return (int) lineX.invoke(editor, grid, 0.0D);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object call(Class<?> type, Object target, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object get(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) failures.add(message); }
}
