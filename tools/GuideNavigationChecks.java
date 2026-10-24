import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.GuideLayout;
import ru.gora.musicbattle.client.MusicBattleGuideScreen;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * Actual guide widget layout/input, without starting a Minecraft client. The screen keeps its geometry in
 * {@link GuideLayout}, which is pure, so this measures the very rectangles the game draws into: the section
 * rows the screen places itself, the frame they sit in, and one article wrapped with a stand-in for the font.
 */
public final class GuideNavigationChecks {
    static final int SECTIONS = 14;
    /** Rounding slack: the screen places rows with whole-unit widget coordinates. */
    static final float SLACK = 1.0F;
    /** A stand-in for Minecraft's font: every glyph half the em wide. */
    static final TextMeasurer MEASURER = (text, style) -> text == null ? 0F : text.length() * style.size() * 0.5F;
    static int checks;

    static Field field(String name) throws Exception {
        Field f = MusicBattleGuideScreen.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        var allocate = unsafeType.getMethod("allocateInstance", Class.class);
        Method refresh = MusicBattleGuideScreen.class.getDeclaredMethod("refreshSectionButtons");
        refresh.setAccessible(true);
        for (int[] size : new int[][] {{320, 240}, {640, 180}, {640, 240}, {640, 360}, {640, 720}, {1280, 800}}) {
            int width = size[0];
            int height = size[1];
            GuideLayout.Frame frame = GuideLayout.frame(width, height);
            assertFrame(frame);
            assertArticle(frame);

            var screen = (MusicBattleGuideScreen) allocate.invoke(unsafe, MusicBattleGuideScreen.class);
            screen.width = width; screen.height = height;
            List<Button> buttons = new ArrayList<>();
            // Deliberately misplaced: the screen is the one that has to put every row where it belongs.
            for (int i = 0; i < SECTIONS; i++) buttons.add(Button.builder(Component.literal("Section " + i), b -> {})
                    .bounds(0, 0, 8, 8).build());
            field("sectionButtons").set(screen, buttons);
            field("sections").set(screen, Collections.nCopies(SECTIONS, null));
            field("bodyScroll").set(screen, new Motion.SmoothScroll(System::nanoTime));
            refresh.invoke(screen);
            assertRows(screen, frame, buttons);
            check(buttons.get(0).visible, "first section available initially");

            int rows = frame.visibleRows();
            double insideSidebar = frame.sidebar().centerX();
            double sidebarY = frame.sidebar().centerY();
            // Real wheel handler must scroll the sidebar without changing the selected article.
            check(screen.mouseScrolled(insideSidebar, sidebarY, 0, -40), "sidebar consumes its wheel event");
            assertRows(screen, frame, buttons);
            check(buttons.get(SECTIONS - 1).visible, "wheel reaches final section");
            check((int) field("selectedSection").get(screen) == 0, "sidebar wheel does not switch article");
            check(((Motion.SmoothScroll) field("bodyScroll").get(screen)).target() == 0.0,
                    "sidebar wheel leaves the article where it was");
            screen.mouseScrolled(insideSidebar, sidebarY, 0, 40);
            assertRows(screen, frame, buttons);
            check(buttons.get(0).visible, "reverse wheel reaches first section");
            check((int) field("firstSection").get(screen) == 0, "reverse wheel stops at the first section");

            // The article takes its own wheel: it scrolls the text and leaves the section list alone.
            check(screen.mouseScrolled(frame.article().centerX(), frame.article().centerY(), 0, -1),
                    "article consumes its wheel event");
            check(((Motion.SmoothScroll) field("bodyScroll").get(screen)).target() > 0.0, "article wheel scrolls the article");
            check((int) field("firstSection").get(screen) == 0, "article wheel does not page the section list");
            check(rows >= 1, "at least one section row fits at " + width + "x" + height);

            field("selectedSection").setInt(screen, SECTIONS - 1);
            refresh.invoke(screen);
            check(buttons.get(SECTIONS - 1).visible && !buttons.get(SECTIONS - 1).active,
                    "keyboard selection reveals final section");
            assertRows(screen, frame, buttons);
        }
        System.out.println("Guide navigation checks PASS: " + checks);
    }

    /** The parts of the screen must tile the window: nothing over the title bar, nothing under the footer. */
    static void assertFrame(GuideLayout.Frame frame) {
        String at = " at " + (int) frame.width() + "x" + (int) frame.height();
        check(frame.panel().w() > 0 && frame.panel().w() <= frame.width() + SLACK, "panel fits the window" + at);
        check(frame.titleText().right() <= frame.close().x() + SLACK, "title stops before the close button" + at);
        check(frame.close().inside(frame.titleBar(), SLACK), "close button stays in the title bar" + at);
        check(frame.sidebar().y() >= frame.titleBar().bottom() - SLACK, "section list starts below the title bar" + at);
        check(frame.sidebar().bottom() <= frame.footer().y() + SLACK, "section list ends above the footer" + at);
        check(frame.article().y() >= frame.titleBar().bottom() - SLACK, "article starts below the title bar" + at);
        check(frame.article().bottom() <= frame.footer().y() + SLACK, "article ends above the footer" + at);
        check(frame.article().x() >= frame.sidebar().right(), "article does not overlap the section list" + at);
        check(frame.article().right() <= frame.panel().right() + SLACK, "article stays in the panel" + at);
        check(frame.list().inside(frame.sidebar(), SLACK), "list viewport stays in the section panel" + at);
        check(frame.header().inside(frame.article(), SLACK), "article header stays in the article" + at);
        check(frame.rule().y() >= frame.header().bottom(), "the rule sits under the article title" + at);
        check(frame.body().inside(frame.article(), SLACK) && frame.body().y() >= frame.rule().bottom(),
                "body viewport stays under the rule and in the article" + at);
        check(frame.body().w() > 20 && frame.body().h() > 0, "body viewport has room" + at);
        check(frame.column().inside(frame.body(), SLACK), "text column stays in the body viewport" + at);
        check(frame.column().right() <= frame.body().right() - Metrics.SCROLLBAR,
                "text column stops before the scrollbar" + at);
        check(frame.header().x() == frame.column().x() && frame.rule().x() == frame.column().x(),
                "article title and its rule sit over the column" + at);
        check(frame.back().inside(frame.footer(), SLACK) && frame.prev().inside(frame.footer(), SLACK)
                && frame.next().inside(frame.footer(), SLACK), "footer buttons stay in the footer" + at);
        check(frame.back().right() <= frame.prev().x(), "back does not overlap previous" + at);
        check(frame.prev().right() <= frame.next().x(), "previous does not overlap next" + at);
        check(frame.rowHeight() > 0 && frame.rowStep() > frame.rowHeight(), "rows have a height and a gap" + at);
    }

    /** One article of every block kind must wrap inside the body and stay in reading order. */
    static void assertArticle(GuideLayout.Frame frame) {
        String at = " at " + (int) frame.width() + "x" + (int) frame.height();
        List<GuideLayout.Block> blocks = new ArrayList<>();
        for (GuideLayout.Kind kind : GuideLayout.Kind.values()) {
            blocks.add(new GuideLayout.Block("A " + kind + " paragraph long enough to wrap onto several lines "
                    + "of the article, whatever width the window happens to have right now.", kind));
        }
        Layout layout = GuideLayout.article(frame, MEASURER, blocks);
        float bottom = 0F;
        for (int index = 0; index < blocks.size(); index++) {
            List<Layout.Box> lines = layout.group(GuideLayout.blockId(index) + ".text.");
            check(!lines.isEmpty(), "block " + index + " has text" + at);
            for (Layout.Box line : lines) {
                Rect rect = line.rect();
                check(rect.x() >= 0 && rect.right() <= frame.body().w() + SLACK,
                        "line of block " + index + " stays in the body" + at + ": " + rect);
                check(rect.y() >= bottom - SLACK, "blocks stay in reading order" + at);
                float measured = MEASURER.width(line.text(), line.style() == null ? TextStyle.BODY : line.style());
                check(measured <= rect.w() + SLACK, "no line is wider than its box" + at);
                bottom = Math.max(bottom, rect.bottom());
            }
        }
        check(layout.contentHeight() >= bottom - SLACK, "content height covers every line" + at);
    }

    /** Visible rows must fill the list viewport in order, and hidden ones must not be drawn anywhere. */
    static void assertRows(MusicBattleGuideScreen screen, GuideLayout.Frame frame, List<Button> buttons) {
        String at = " at " + (int) frame.width() + "x" + (int) frame.height();
        Rect previous = null;
        int visible = 0;
        for (Button button : buttons) {
            if (!button.visible) continue;
            visible++;
            Rect rect = Rect.of(button.getX(), button.getY(), button.getWidth(), button.getHeight());
            check(rect.inside(frame.list(), SLACK), "visible section stays in the list viewport" + at + ": " + rect);
            check(previous == null || rect.y() >= previous.bottom(), "section rows do not overlap" + at);
            previous = rect;
        }
        check(visible > 0 && visible <= frame.visibleRows(), "the list shows a page of sections" + at);
    }
}
