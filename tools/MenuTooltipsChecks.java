package ru.gora.musicbattle.client.workshop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * The {@code menu-tooltips} regression: the last vanilla tooltips of the beatmap editor (the beat-1 field, its "1"
 * button, the grid button) and of the mod's buttons on vanilla's menus ("Workshop", "BM", and "Surrender" in a battle's
 * pause menu) are the toolkit's card (UiTooltip), and the narrator still reads them.
 *
 * <ul>
 *   <li>The editor makes no tooltip vanilla would draw: its three hints come from its UiTooltip.Deferred, made once and
 *       kept (init() runs on every page scroll, and the holder keeps every tooltip it made), its
 *       setTooltipForNextRenderPass offers to the holder first, and the card is drawn after every control, in the
 *       controls' foreground layer. The real screen's grid hint is the same tooltip each time, one per grid size, and
 *       the holder takes it.</li>
 *   <li>The menu buttons keep their hint out of vanilla's holder (a real one has no vanilla tooltip, so the menu never
 *       draws one), the hint is a tooltip the shared holder takes, made once per key, and the button narrates it.</li>
 *   <li>The menu buttons offer the hint under the pointer or on keyboard focus, with vanilla's positioners, and the card
 *       is drawn after the whole menu (ScreenEvent.Render.Post); the Workshop button, BM and Surrender are made by it.</li>
 * </ul>
 *
 * <p>NOT COVERED: the card on a screen (the visual session looked at it), the offer during a real render pass.
 */
public final class MenuTooltipsChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path client = root.resolve("src/main/java/ru/gora/musicbattle/client");
        editorSource(flat(client.resolve("TrackEditorScreen.java")));
        editorHolder();
        menuSource(flat(client.resolve("workshop/MenuButtonTooltips.java")), flat(client.resolve("workshop/WorkshopClientHooks.java")),
                flat(client.resolve("ClientBattleController.java")));
        menuButtons();
        FAILURES.forEach(System.err::println);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("Menu tooltips PASS: " + checks + " checks (the editor's three hints, Workshop, BM and Surrender)");
        System.out.println("NOT COVERED: the card on a screen, the offer during a real render pass");
    }

    private static void editorSource(String editor) {
        check(!editor.contains("Tooltip.create("), "the editor makes no tooltip that vanilla would draw");
        check(editor.contains("beatOneAtTooltip = tooltips.create(Component.translatable(\"music_battle.l10n.editor.beat_one_at_hint\"));")
                        && editor.contains("beatOneTooltip = tooltips.create(Component.translatable(\"music_battle.l10n.editor.beat_one_hint\"));")
                        && editor.contains("offsetBox.setTooltip(beatOneAtTooltip);")
                        && editor.contains("beatOneButton.setTooltip(beatOneTooltip);"),
                "the beat-1 field's and its button's hints are made by the toolkit's holder and set on them");
        check(editor.contains("if (beatOneAtTooltip == null) {"), "the beat-1 hints are made once, not on every page scroll");
        check(editor.contains("gridTooltips[index] = tooltips.create(Component.translatable(\"music_battle.l10n.editor.grid_tooltip\", "
                + "gridDivision(), next));") && editor.contains("button.setTooltip(gridDivisionTooltip());"),
                "the grid button's hint is made by the toolkit's holder, once per grid size");
        check(editor.contains("public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, "
                        + "boolean focused) { if (!tooltips.offer(tooltip, positioner)) { "
                        + "super.setTooltipForNextRenderPass(tooltip, positioner, focused); } }"),
                "the editor hands its tooltips to the toolkit before vanilla");
        int draw = editor.indexOf("() -> tooltips.draw(graphics, mouseX, mouseY, this.width, this.height, getFocused()));");
        int controls = editor.indexOf("drawTopBar(graphics, mouseX, mouseY, partialTick);");
        int effect = editor.indexOf("() -> renderEffectTooltip(graphics, mouseX, mouseY));");
        check(draw > controls && draw > effect && controls > 0 && effect > 0
                        && editor.lastIndexOf("ClientUiForeground.renderTip(graphics.pose(), graphics::flush,", draw) > effect,
                "the editor draws the card after every control and the effect tip, in the tip layer over the controls");
    }

    /** The real screen's grid hint: one tooltip per grid size, the same each time, and the holder takes it. */
    private static void editorHolder() throws Exception {
        Class<?> type = Class.forName("ru.gora.musicbattle.client.TrackEditorScreen");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object editor = ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
        field(type, "tooltips").set(editor, new UiTooltip.Deferred());
        field(type, "gridTooltips").set(editor, new Tooltip[((int[]) field(type, "GRID_DIVISIONS").get(null)).length]);
        Method grid = type.getDeclaredMethod("gridDivisionTooltip");
        grid.setAccessible(true);
        UiTooltip.Deferred holder = (UiTooltip.Deferred) field(type, "tooltips").get(editor);
        List<Tooltip> made = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            field(type, "gridDivisionIndex").set(editor, index);
            Tooltip first = (Tooltip) grid.invoke(editor);
            check(first == grid.invoke(editor), "the grid hint for size " + index + " is one tooltip, not a new one per call");
            check(holder.offer(first, new MenuTooltipPositioner(new ScreenRectangle(0, 0, 10, 10))),
                    "the editor's holder takes its grid hint");
            made.add(first);
        }
        check(made.get(0) != made.get(1), "the two grid sizes have their own hints");
    }

    private static void menuSource(String menu, String hooks, String controller) {
        check(menu.contains("public static void onScreenRendered(ScreenEvent.Render.Post event) { Screen screen = event.getScreen(); "
                        + "TOOLTIPS.draw(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), screen.width, screen.height, "
                        + "screen.getFocused()); }"),
                "the menu buttons' card is drawn after the whole menu has been");
        check(menu.contains("if (isHovered() || isFocused() && keyboard) { TOOLTIPS.offer(hint, !isHovered() && isFocused() && keyboard "
                        + "? new BelowOrAboveWidgetTooltipPositioner(getRectangle()) : new MenuTooltipPositioner(getRectangle())); }"),
                "a menu button offers its hint when vanilla's holder would show it, with vanilla's positioners");
        check(!hooks.contains("Tooltip.create(") && !hooks.contains("setTooltip("), "the Workshop button sets no vanilla tooltip");
        check(hooks.contains("private static final class TitleButton extends MenuButtonTooltips.HintedButton {")
                        && hooks.contains("super(x, y, width, height, message, \"screen.music_battle.workshop.title_button.tooltip\", onPress);"),
                "the Workshop button carries its hint as the toolkit's card");
        int start = controller.indexOf("public static void addOptionsButton(ScreenEvent.Init.Post event) {");
        int end = controller.indexOf("private static void tickPreloadingTrack() {", start);
        String options = start < 0 || end < 0 ? "" : controller.substring(start, end);
        check(!options.isEmpty() && !options.contains("Tooltip.create(") && !options.contains(".tooltip("),
                "the BM and Surrender buttons set no vanilla tooltip");
        check(options.contains("MenuButtonTooltips.button(MenuCornerButtons.settings(), Component.literal(\"BM\"), \"music_battle.options\",")
                        && options.contains("\"music_battle.l10n.client.005\", ignored -> surrender());"),
                "the BM and Surrender buttons are made with their hints as the toolkit's card");
    }

    /** A real menu button: no vanilla tooltip, a hint the shared holder takes and makes once, and narrated. */
    private static void menuButtons() throws Exception {
        Button button = MenuButtonTooltips.button(Rect.of(6, 8, 34, 20), Component.literal("BM"), "music_battle.options",
                ignored -> { });
        check(button.getTooltip() == null, "a menu button gives vanilla no tooltip to draw over the menu");
        Tooltip hint = MenuButtonTooltips.hint("music_battle.options");
        check(hint == MenuButtonTooltips.hint("music_battle.options"), "a hint is made once per key, not per menu set-up");
        Field shared = MenuButtonTooltips.class.getDeclaredField("TOOLTIPS");
        shared.setAccessible(true);
        UiTooltip.Deferred holder = (UiTooltip.Deferred) shared.get(null);
        check(holder.offer(hint, new BelowOrAboveWidgetTooltipPositioner(new ScreenRectangle(6, 8, 34, 20))),
                "the shared holder takes a menu button's hint");
        check(!holder.offer(Tooltip.create(Component.literal("Other")), new MenuTooltipPositioner(new ScreenRectangle(0, 0, 1, 1))),
                "the shared holder leaves any other tooltip to vanilla");
        List<String> hints = new ArrayList<>();
        button.updateNarration(new NarrationElementOutput() {
            @Override
            public void add(NarratedElementType type, NarrationThunk<?> contents) {
                if (type == NarratedElementType.HINT) {
                    contents.getText(hints::add);
                }
            }

            @Override
            public NarrationElementOutput nest() {
                return this;
            }
        });
        check(hints.size() == 1 && hints.get(0).equals(Component.translatable("music_battle.options").getString()),
                "the narrator reads a menu button's hint once: " + hints);
    }

    /** The source with comments removed and its whitespace folded, so the assertions read like the code. */
    private static String flat(Path file) throws Exception {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        source = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        return source.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            FAILURES.add("FAIL: " + message);
        }
    }
}
