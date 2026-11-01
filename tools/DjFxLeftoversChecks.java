package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.geom.Path2D;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.gora.musicbattle.client.DjPanelScreen.PanelLayout;
import ru.gora.musicbattle.client.DjPanelScreen.PanelLayout.Control;
import ru.gora.musicbattle.client.TrackEffectEditScreen.InspectorLayout;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiSdf;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;
import ru.gora.musicbattle.network.OpenDjPanelPayload;
import ru.gora.musicbattle.network.OpenRapGodConfigPayload;

/**
 * The DJ and FX leftovers the reviews of the third wave found, in English and Russian, measured with Minecraft's own glyph
 * provider at every GUI scale's tier (GameTextMeasurer). The screens are built from their own constructors and their own
 * init, and the buttons drawn by their own renderWidget, in just enough of a game: Minecraft's registries bootstrapped, a
 * window with a GUI scale, the options' language and a font that measures the toolkit's faces with the game's glyphs and
 * records what it is asked to draw. That game has no shader, so the toolkit draws its fallback look (square fills, as in a
 * game whose shader failed to load or with Simple UI on); the slash is also drawn with a stand-in shader, as quads.
 *
 * <ul>
 *   <li>On the library page of a narrow DJ panel the header's Close sat right above the camera row's disconnect, and both
 *       were the same cross. Close keeps the cross; both disconnects (camera and speaker) are the link struck through,
 *       and no other control is. As drawn, idle, under the keyboard's focus and switched off, in both looks: Close is the
 *       cross alone; a disconnect is the link, then the slash in the icon's colour at 45 degrees through its centre and
 *       across its whole diagonal, over a wider band of the button's own surface (as WorkshopDraw.button filled it that
 *       frame) when the button is on and none when it is off. In the fallback look the slash is a whole unit thick with a
 *       unit of gap either side, and at GUI scales 1 to 4 its pixels run down the icon's diagonal: before, it rounded to
 *       nothing and a disabled disconnect showed the plain link, the glyph for connecting.</li>
 *   <li>The tooltips of the DJ panel, the test NPC's settings, the camera gallery and the visual FX list are the
 *       toolkit's card (UiTooltip.Deferred), not vanilla's purple frame in the bitmap font. On each real screen, every
 *       widget's tip is one its holder made, and the screen hands it to the holder for the pointer and the keyboard and
 *       never to vanilla; a rebuild of the widgets (a page switch, a resize) takes a new holder that knows the new tips
 *       and no old one. On the DJ panel every icon has a tip, and Play and Pause swap tips without the holder growing as
 *       the state is refreshed every second. In the sources: no vanilla tooltip is made, and the card is drawn after the
 *       widgets inside the foreground layer (ClientUiForeground.render), over the NPC drawn with depth and the FX layer at
 *       +400; the DJ panel's own hints (a card's "×", its author, a cut status line) go the same way. The visual FX list
 *       takes no tip while its type selector covers the buttons.</li>
 *   <li>The OLD_TV sweep row's switch has words of its own, not its label's ("Пролетающая полоса" twice).</li>
 *   <li>The exact-colour button draws its "#RRGGBB" in the toolkit's face, not the bitmap font: the widest code there is
 *       fits beside the swatch at every width the inspector gives it, from its floor up, in the face the button picks
 *       (body from 18 units high wherever body fits, else captions), at every GUI scale. As drawn: the code once, whole,
 *       in that face, centred in the room between the swatch and the right margin, in the colour of the button's state,
 *       with the swatch the colour itself.</li>
 *   <li>An FX label's parenthesis is never broken across two lines («Приближение 0.25–8 (1 = без» / «приближения)»):
 *       inside every parenthesis of every inspector label the words are joined by U+00A0, Nunito has that glyph and it
 *       is exactly as wide as a space in every face at every tier, and the inspector's wrapping
 *       (InspectorLayout.wrapWhole) never ends a line inside a parenthesis at any width. The labels are read from every
 *       effect's rows, the outline's with 1 to 16 colours (the colour cycle's speed shows from two), and every label with a
 *       parenthesis the editor names must be among them.</li>
 *   <li>Every language key the six classes name is in both language files: a key the files lack shows as the bare key.</li>
 * </ul>
 * The language files are read as they are, so this wave's keys (the sweep switch's and the U+00A0s) must be merged before
 * this passes.
 * NOT COVERED: how the cards, the struck link and the colour button look in the game, the shader's anti-aliased slash
 * above all (the visual session); the foreground layer, which is read from the sources.
 */
public final class DjFxLeftoversChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static final String NBSP = "\u00A0";
    /** The OLD_TV sweep switch's own words, a key this wave adds. */
    private static final String SWEEP_SWITCH = "music_battle.l10n.fx.tv_sweep_switch";
    /** The classes whose language keys must all be in both files. */
    private static final List<String> SOURCES = List.of("DjPanelScreen", "TrackEffectEditScreen", "DjVisualFxScreen",
            "RapGodConfigScreen", "DjCameraGalleryScreen", "ExactColorButton");
    private static final MenuTooltipPositioner POINTER = new MenuTooltipPositioner(new ScreenRectangle(0, 0, 10, 10));
    private static int checks;
    private static GameTextMeasurer[] tiers;
    /** GUI scale 2's metrics, for the tooltip cards whose placement only has to exist. */
    private static GameTextMeasurer cards;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        tiers = new GameTextMeasurer[TextStyle.MAX_TIER + 1];
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            tiers[tier] = new GameTextMeasurer(fonts, tier);
        }
        cards = tiers[2];
        // The files as they are: a key this wave adds is read only once it is merged, so a merge that drops it fails here.
        Map<String, Map<String, String>> languages = new LinkedHashMap<>();
        languages.put("en_us", read(lang.resolve("en_us.json")));
        languages.put("ru_ru", read(lang.resolve("ru_ru.json")));

        startGame();
        keysExist(root, languages);
        glyphs();
        tooltipSources(root);
        colourButtonSource(root);
        colourButton(tiers);
        noBreakSpace(tiers);
        inject(languages.get("en_us"));
        colourButtonDrawn();
        for (Map.Entry<String, Map<String, String>> language : languages.entrySet()) {
            inject(language.getValue());
            panel(language.getKey());
            otherScreens(language.getKey());
            sweepSwitch(tiers[2], language.getValue(), language.getKey());
            switchWords(tiers[2], language.getKey());
            parentheses(root, tiers, language.getValue(), language.getKey());
            rangesKeepTheirUnits(root, tiers, language.getValue(), language.getKey());
        }
        visualFxTips();

        System.out.println("DJ and FX leftovers checks: " + checks);
        FAILURES.stream().limit(40).forEach(System.err::println);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("DJ and FX leftovers PASS: Close and disconnect apart and the slash drawn in both looks, the "
                + "toolkit's tooltip on four real screens, every FX switch's own words, the colour code in Nunito and "
                + "centred, no FX label broken inside its parenthesis or between a range and its unit, every key in both "
                + "files; en and ru, GUI scales 1-9");
        System.out.println("NOT COVERED: how they look in the game, the shader's slash above all; the foreground layer by "
                + "the sources only");
    }

    // ---------------------------------------------------------------- the language keys

    /**
     * Every language key the six classes name as a literal is in both language files. A key the files lack shows in the
     * game as the bare key, and the rows measured here would measure the key, so a dropped or renamed key fails here by
     * name. A literal that ends in "_" is a prefix the code completes.
     */
    private static void keysExist(Path root, Map<String, Map<String, String>> languages) throws Exception {
        for (String name : SOURCES) {
            Set<String> keys = keys(root, name);
            for (String key : keys) {
                for (Map.Entry<String, Map<String, String>> language : languages.entrySet()) {
                    require(language.getValue().containsKey(key), name + " names " + key + ", which " + language.getKey()
                            + ".json does not have (merge this wave's lang_keys)");
                }
            }
            require(!keys.isEmpty() || name.equals("ExactColorButton"), name + ": its language keys are there to check");
        }
    }

    /** The language keys a class of the client names as string literals, outside its comments. */
    private static Set<String> keys(Path root, String name) throws Exception {
        String code = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/" + name + ".java"));
        Matcher literal = Pattern.compile("\"((?:[a-z0-9_]+\\.)+[a-z0-9_]+)\"").matcher(code);
        Set<String> keys = new LinkedHashSet<>();
        while (literal.find()) {
            String key = literal.group(1);
            if (key.contains("music_battle") && !key.endsWith("_")) {
                keys.add(key);
            }
        }
        return keys;
    }

    // ---------------------------------------------------------------- Close and disconnect

    /** The glyph of each control: the cross for Close only, the struck link for the two disconnects and nothing else. */
    private static void glyphs() {
        require(DjPanelScreen.icon(Control.LIBRARY_CLOSE) == UiIcon.X && !DjPanelScreen.struck(Control.LIBRARY_CLOSE),
                "the library page's Close is the cross, not struck");
        for (Control disconnect : List.of(Control.UNLINK_CAMERA, Control.UNLINK_SPEAKER)) {
            require(DjPanelScreen.icon(disconnect) == UiIcon.LINK && DjPanelScreen.struck(disconnect),
                    disconnect + " is the link struck through");
        }
        for (Control control : Control.values()) {
            if (control != Control.UNLINK_CAMERA && control != Control.UNLINK_SPEAKER) {
                require(!DjPanelScreen.struck(control), control + " is not struck through");
            }
        }
    }

    // ---------------------------------------------------------------- the real DJ panel

    /**
     * The real panel from its own constructor and its own init, with an empty booth, at six windows: two pages at 320x240
     * (GUI scales 1 and 2), both columns at 427x240 and 480x270 (their mixer scrolls), 640x360 and 1280x720. Each page is built as the game builds
     * it: every widget's tip is its holder's and handed to it, every button that shows an icon other than an arrow has a
     * tip, Close and the camera's disconnect are apart, Play and Pause swap tips without the holder growing, the cross and
     * the struck link are drawn as they should be, and a rebuild takes a new holder.
     */
    private static void panel(String code) throws Exception {
        int[][] windows = {{320, 240, 1}, {320, 240, 2}, {427, 240, 2}, {480, 270, 4}, {640, 360, 2}, {1280, 720, 3}};
        Field page = field(DjPanelScreen.class, "page");
        Object before = page.get(null);
        Class<?> panelButton = Class.forName("ru.gora.musicbattle.client.DjPanelScreen$PanelButton");
        try {
            for (int[] window : windows) {
                enterGame(window[2], code);
                String where = code + " " + window[0] + "x" + window[1] + " at GUI scale " + window[2];
                int tips = 0;
                int disconnects = 0;
                boolean paged = false;
                for (PanelLayout.Page shown : PanelLayout.Page.values()) {
                    page.set(null, shown);
                    DjPanelScreen screen = built(new DjPanelScreen(payload()), window[0], window[1]);
                    PanelLayout layout = (PanelLayout) field(DjPanelScreen.class, "layout").get(screen);
                    paged = layout.paged();
                    boolean libraryPage = paged && shown == PanelLayout.Page.LIBRARY;
                    String at = where + (paged ? ", " + shown.name().toLowerCase(Locale.ROOT) + " page" : "");
                    UiTooltip.Deferred holder = holder(screen);
                    tips += widgetTips(screen, holder, at);
                    for (Object renderable : screen.children()) {
                        if (!panelButton.isInstance(renderable)) {
                            continue;
                        }
                        AbstractWidget widget = (AbstractWidget) renderable;
                        Object icon = field(panelButton, "icon").get(widget);
                        if (icon != null && icon != UiIcon.CHEVRON_LEFT && icon != UiIcon.CHEVRON_RIGHT) {
                            require(widget.getTooltip() != null, at + " '" + widget.getMessage().getString()
                                    + "': a button that shows an icon other than an arrow has a tip with its words");
                        }
                        boolean struck = field(panelButton, "struck").getBoolean(widget);
                        if (icon == UiIcon.X || struck) {
                            glyphDrawn(widget, (UiIcon) icon, struck, at);
                            disconnects += struck ? 1 : 0;
                        }
                    }
                    if (libraryPage) {
                        closeAndDisconnect(screen, layout, panelButton, at);
                    }
                    playPauseTips(screen, holder, libraryPage, paged, at);
                    rebuilt(screen, at);
                    if (!paged) {
                        break;
                    }
                }
                require(tips >= (paged ? 12 : 10), where + ": the panel's tips are there to check, " + tips);
                require(disconnects == 2, where + ": both disconnects are drawn and checked, " + disconnects);
                System.out.println(where + ": " + (paged ? "two pages" : "both columns") + ", " + tips
                        + " widget tips, every one the toolkit's; Close and the disconnects drawn in both looks");
            }
        } finally {
            page.set(null, before);
            leaveGame();
        }
    }

    /**
     * Every widget tip on a real screen is one its holder made, and the screen's own override takes it, under the pointer
     * and from the keyboard, and never hands it to vanilla: the purple frame and the bitmap font would draw it over the
     * card. Returns how many tips the screen has.
     */
    private static int widgetTips(Screen screen, UiTooltip.Deferred holder, String at) throws Exception {
        int tips = 0;
        for (Object renderable : screen.children()) {
            if (!(renderable instanceof AbstractWidget widget) || widget.getTooltip() == null) {
                continue;
            }
            tips++;
            String name = at + " '" + widget.getMessage().getString() + "'";
            require(holder.offer(widget.getTooltip(), new MenuTooltipPositioner(widget.getRectangle())),
                    name + ": the tip is one the screen's holder made, drawn as the toolkit's card");
            handedToHolder(screen, holder, widget.getTooltip(), widget, name);
        }
        return tips;
    }

    /**
     * The screen's own override takes a tip its holder made, under the pointer and from the keyboard, and vanilla never
     * gets it.
     */
    private static void handedToHolder(Screen screen, UiTooltip.Deferred holder, Tooltip tip, AbstractWidget widget,
                                       String name) throws Exception {
        Field deferred = field(Screen.class, "deferredTooltipRendering");
        Field pending = field(UiTooltip.Deferred.class, "pending");
        for (boolean keyboard : new boolean[]{false, true}) {
            deferred.set(screen, null);
            pending.set(holder, null);
            try {
                screen.setTooltipForNextRenderPass(tip, keyboard
                        ? new BelowOrAboveWidgetTooltipPositioner(widget.getRectangle())
                        : new MenuTooltipPositioner(widget.getRectangle()), keyboard);
                require(deferred.get(screen) == null && !holder.placement(cards, 10, 10, 400, 300, null).empty(),
                        name + ": the screen hands the tip to its holder, not to vanilla" + (keyboard ? " (keyboard)" : ""));
            } catch (RuntimeException failure) {
                require(false, name + ": the screen hands the tip to vanilla: " + failure);
            }
        }
    }

    /**
     * A rebuild of the widgets (a page switch, a resize, a return from a child screen) takes a new holder that knows the
     * new widgets' tips and no other. The old one, kept, would gather the tips of every build for as long as the screen
     * stays open.
     */
    private static void rebuilt(Screen screen, String at) throws Exception {
        UiTooltip.Deferred before = holder(screen);
        int known = texts(before).size();
        List<Tooltip> old = tips(screen);
        invoke(screen, "clearWidgets");
        screen.clearFocus();
        invoke(screen, "init");
        invoke(screen, "setInitialFocus");
        UiTooltip.Deferred after = holder(screen);
        require(after != before, at + ": a rebuild of the widgets takes a new tooltip holder");
        require(texts(after).size() == known, at + ": after a rebuild the holder knows " + texts(after).size()
                + " tips, the first build's " + known);
        for (Tooltip tip : old) {
            require(!after.offer(tip, POINTER), at + ": the new holder knows no tip of the widgets thrown away");
        }
        List<Tooltip> renewed = tips(screen);
        require(renewed.size() == old.size(), at + ": the rebuild has as many tips as the first build, " + renewed.size());
        for (Tooltip tip : renewed) {
            require(after.offer(tip, POINTER), at + ": the new widgets' tips are the new holder's");
        }
    }

    /**
     * On the library page the header's Close and the camera row's disconnect, which sits right under it, show different
     * glyphs, and each has its own tip.
     */
    private static void closeAndDisconnect(DjPanelScreen screen, PanelLayout layout, Class<?> panelButton, String where)
            throws Exception {
        AbstractWidget close = widgetAt(screen, layout.placed(Control.LIBRARY_CLOSE).rect());
        AbstractWidget disconnect = widgetAt(screen, layout.placed(Control.UNLINK_CAMERA).rect());
        require(close != null && disconnect != null && panelButton.isInstance(close) && panelButton.isInstance(disconnect),
                where + ": the library page has the header's Close and the camera row's disconnect");
        if (close == null || disconnect == null || !panelButton.isInstance(close) || !panelButton.isInstance(disconnect)) {
            return;
        }
        Object closeIcon = field(panelButton, "icon").get(close);
        Object disconnectIcon = field(panelButton, "icon").get(disconnect);
        boolean closeStruck = field(panelButton, "struck").getBoolean(close);
        boolean disconnectStruck = field(panelButton, "struck").getBoolean(disconnect);
        require(closeIcon == UiIcon.X && !closeStruck, where + ": Close is the cross");
        require(disconnectIcon == UiIcon.LINK && disconnectStruck, where + ": the camera's disconnect is the struck link");
        require(closeIcon != disconnectIcon || closeStruck != disconnectStruck,
                where + ": Close and the camera's disconnect never show the same glyph");
        Tooltip closeTip = close.getTooltip();
        Tooltip disconnectTip = disconnect.getTooltip();
        require(closeTip != null && disconnectTip != null, where + ": Close and the camera's disconnect each have a tip");
        if (closeTip != null && disconnectTip != null) {
            String closeWords = tipText(closeTip);
            String disconnectWords = tipText(disconnectTip);
            require(!closeWords.isBlank() && !disconnectWords.isBlank() && !closeWords.equals(disconnectWords),
                    where + ": their tips say different things: '" + closeWords + "' / '" + disconnectWords + "'");
        }
    }

    /**
     * The library page's one Play/Pause button follows the state with the tip of what it does now, and the state, which the
     * server refreshes every second, never piles new tips into the holder.
     */
    private static void playPauseTips(DjPanelScreen screen, UiTooltip.Deferred holder, boolean libraryPage, boolean paged,
                                      String where) throws Exception {
        Map<?, ?> made = texts(holder);
        int before = made.size();
        Field playing = field(DjPanelScreen.class, "playing");
        Object libraryPlay = field(DjPanelScreen.class, "libraryPlayButton").get(screen);
        for (int refresh = 0; refresh < 40; refresh++) {
            playing.setBoolean(screen, refresh % 2 == 0);
            invoke(screen, "refreshWidgetsFromState");
            if (libraryPlay instanceof AbstractWidget button) {
                Tooltip tip = button.getTooltip();
                String expected = Component.translatable(refresh % 2 == 0 ? "screen.music_battle.dj_panel.pause"
                        : "screen.music_battle.dj_panel.play_resume").getString();
                require(tip != null && tipText(tip).equals(expected) && holder.offer(tip, new MenuTooltipPositioner(
                        button.getRectangle())), where + ": the library page's button tells what it does now: " + expected);
            }
        }
        playing.setBoolean(screen, false);
        invoke(screen, "refreshWidgetsFromState");
        require(libraryPage == (libraryPlay != null), where + ": the library page has its Play/Pause only on two pages"
                + (paged ? "" : " (both columns: none)"));
        require(made.size() == before, where + ": refreshing the state forty times leaves the holder's tips as they were, "
                + before + " -> " + made.size());
    }

    private static AbstractWidget widgetAt(Screen screen, Rect rect) {
        for (Object renderable : screen.children()) {
            if (renderable instanceof AbstractWidget widget && widget.getX() == Math.round(rect.x())
                    && widget.getY() == Math.round(rect.y()) && widget.getWidth() == Math.round(rect.w())
                    && widget.getHeight() == Math.round(rect.h())) {
                return widget;
            }
        }
        return null;
    }

    /** A tooltip's words as the narrator reads them, which are the words the card shows. */
    private static String tipText(Tooltip tip) {
        StringBuilder text = new StringBuilder();
        tip.updateNarration(new net.minecraft.client.gui.narration.NarrationElementOutput() {
            @Override
            public void add(net.minecraft.client.gui.narration.NarratedElementType type,
                            net.minecraft.client.gui.narration.NarrationThunk<?> contents) {
                contents.getText(text::append);
            }

            @Override
            public net.minecraft.client.gui.narration.NarrationElementOutput nest() {
                return this;
            }
        });
        return text.toString();
    }

    /** An empty booth: every list empty, every number nought, the panel at the origin. */
    private static OpenDjPanelPayload payload() throws Exception {
        return record(OpenDjPanelPayload.class);
    }

    // ---------------------------------------------------------------- the cross and the struck link as drawn

    /**
     * Close or a disconnect as its own renderWidget draws it, idle, under the keyboard's focus and switched off, in the
     * fallback look and with a stand-in shader. The button's surface comes first, then the one icon, centred, in the
     * colour of the state; Close draws nothing more. A disconnect draws, after the icon, a band of the surface's own
     * colour this frame while it is on (none while it is off) and over it the slash in the icon's colour: both at 45
     * degrees through the icon's centre, the slash at least the icon's diagonal long and the band wider than the slash.
     * In the fallback look the slash is a whole unit thick with a whole unit of band either side, and rasterised at GUI
     * scales 1 to 4 it covers a pixel in every row down the icon's diagonal.
     */
    private static void glyphDrawn(AbstractWidget button, UiIcon icon, boolean struck, String where) throws Exception {
        boolean wasActive = button.active;
        String[] states = {"idle", "focused", "switched off"};
        try {
            // The fallback is reached two ways, and both must draw the slash: the shader failed to load, or Simple UI
            // forces the square fills while the shader is there.
            for (int look = 0; look < 3; look++) {
                boolean smooth = look == 2;
                UiSdf.setForceFallback(look == 1);
                shader(smooth);
                if (look == 1) {
                    field(UiSdf.class, "shader").set(null, allocate(ShaderInstance.class));
                    require(!UiSdf.available(), "Simple UI draws the fallback look although the shader is there");
                }
                for (int state = 0; state < states.length; state++) {
                    boolean active = state != 2;
                    boolean focused = state == 1;
                    button.active = active;
                    button.setFocused(focused);
                    String at = where + " '" + button.getMessage().getString() + "' " + states[state] + " in the "
                            + (smooth ? "shader's" : look == 1 ? "Simple UI fallback" : "fallback") + " look";
                    Canvas canvas = Canvas.create();
                    renderWidget(button, canvas);
                    List<Shape> shapes = canvas.shapes;
                    List<Shape> icons = shapes.stream().filter(shape -> shape.kind() == Kind.ICON).toList();
                    require(!shapes.isEmpty() && shapes.getFirst().kind() == (smooth ? Kind.SHAPE : Kind.FILL)
                            && icons.size() == 1, at + ": the surface, then one icon, not " + shapes);
                    if (shapes.isEmpty() || icons.size() != 1) {
                        continue;
                    }
                    int surface = shapes.getFirst().colour();
                    int colour = !active ? Palette.TEXT_MUTE : focused ? Palette.TEXT_1 : Palette.TEXT_2;
                    Shape drawn = icons.getFirst();
                    float size = Math.max(6.0F, Math.min(10.0F, button.getHeight() - 6.0F));
                    float centreX = button.getX() + button.getWidth() / 2.0F;
                    float centreY = button.getY() + button.getHeight() / 2.0F;
                    int tier = UiIcon.tierFor(size * window());
                    require(drawn.colour() == colour && near(drawn.centreX(), centreX) && near(drawn.centreY(), centreY)
                                    && near(drawn.length(), size) && near(drawn.u(), icon.uv(tier, 0), 0.00001)
                                    && near(drawn.v(), icon.uv(tier, 1), 0.00001),
                            at + ": the icon is " + icon + ", " + size + " across in the button's middle, in the state's "
                                    + "colour");
                    List<Shape> after = shapes.subList(shapes.indexOf(drawn) + 1, shapes.size());
                    if (!struck) {
                        require(after.isEmpty(), at + ": nothing is drawn over the cross, not " + after);
                        continue;
                    }
                    require(after.size() == (active ? 2 : 1), at + ": over the link, " + (active ? "the band and the slash"
                            : "the slash alone") + ", not " + after);
                    if (after.size() != (active ? 2 : 1)) {
                        continue;
                    }
                    Shape slash = after.getLast();
                    float diagonal = size * 20.0F / 24.0F * (float) Math.sqrt(2.0);
                    require(slash.colour() == colour && slash.kind() == shapes.getFirst().kind(),
                            at + ": the slash is in the icon's colour");
                    require(near(slash.angle(), 45.0) && near(slash.centreX(), centreX) && near(slash.centreY(), centreY),
                            at + ": the slash runs at 45 degrees through the icon's centre: " + slash.angle() + " at ("
                                    + slash.centreX() + ", " + slash.centreY() + ")");
                    require(slash.length() >= diagonal - 0.5F, at + ": the slash crosses the icon's diagonal, "
                            + slash.length() + " of " + diagonal);
                    // The shader draws Lucide's own stroke, 2 of the icon's 24-unit grid as on EYE_OFF; a hairline of a
                    // whole unit there is the fallback's geometry leaking into the smooth look. A shader quad is recorded
                    // with the SDF's padding on either side of the shape it draws.
                    float drawnSlash = smooth ? slash.thickness() - 2.0F * ru.gora.musicbattle.client.ui.SdfMath.PAD
                            : slash.thickness();
                    require(smooth ? near(drawnSlash, size * 2.0F / 24.0F) : near(drawnSlash, 1.0F),
                            at + ": the slash is " + drawnSlash + " thick, "
                                    + (smooth ? "Lucide's stroke " + size * 2.0F / 24.0F + " in the shader's look"
                                    : "where the fallback's square fills need a whole unit"));
                    if (active) {
                        Shape band = after.getFirst();
                        require(band.colour() == surface, at + ": the gap is the button's own surface this frame, "
                                + Integer.toHexString(band.colour()) + " on " + Integer.toHexString(surface));
                        require(near(band.angle(), 45.0) && near(band.centreX(), centreX) && near(band.centreY(), centreY)
                                        && band.length() >= slash.length() - 0.001F,
                                at + ": the gap runs with the slash along its whole length");
                        require(smooth ? band.thickness() > slash.thickness() : near(band.thickness(), slash.thickness() + 2.0F),
                                at + ": the gap is " + band.thickness() + " wide round a slash " + slash.thickness()
                                        + (smooth ? "" : ", a whole unit either side in the fallback"));
                    }
                    if (!smooth) {
                        for (int scale = 1; scale <= 4; scale++) {
                            diagonalPixels(slash, centreX - size / 2.0F, centreY - size / 2.0F, size, scale, at);
                        }
                    }
                }
            }
        } finally {
            button.active = wasActive;
            button.setFocused(false);
            UiSdf.setForceFallback(false);
            shader(false);
        }
    }

    /**
     * The fallback's slash rasterised at a GUI scale as the rasteriser fills a quad, by the pixels whose centres it covers:
     * every row of pixels down the icon's diagonal, from (2, 2) to (22, 22) of its 24-unit grid less half a unit at either
     * end, where a square end may fall between two pixel centres, has one of the slash.
     */
    private static void diagonalPixels(Shape slash, float left, float top, float size, int scale, String at) {
        int rows = 0;
        int covered = 0;
        float first = top + size * 2.0F / 24.0F + 0.5F;
        float last = top + size * 22.0F / 24.0F - 0.5F;
        for (int row = (int) Math.floor(first * scale); row <= (int) Math.ceil(last * scale); row++) {
            float y = (row + 0.5F) / scale;
            if (y < first || y > last) {
                continue;
            }
            rows++;
            for (int column = (int) Math.floor((left - 1.0F) * scale); column <= (int) Math.ceil((left + size + 1.0F) * scale);
                 column++) {
                if (slash.contains((column + 0.5F) / scale, y)) {
                    covered++;
                    break;
                }
            }
        }
        require(rows > 0 && covered == rows, at + ": at GUI scale " + scale + " the slash has a pixel in " + covered + " of the "
                + rows + " rows down the icon's diagonal");
    }

    // ---------------------------------------------------------------- the other three screens

    /**
     * The test NPC's settings, the camera gallery and the visual FX list, each from its own constructor and its own init in
     * a window of 320x240 and of 960x540: every widget tip is its holder's and handed to it, never to vanilla (the NPC's
     * file zone and three switches, the gallery's Select and Control, the list's two arrows), and a rebuild takes a new
     * holder.
     */
    private static void otherScreens(String code) throws Exception {
        try {
            for (int[] window : new int[][]{{320, 240, 2}, {960, 540, 2}}) {
                enterGame(window[2], code);
                String where = code + " " + window[0] + "x" + window[1] + " ";
                screenTips(new RapGodConfigScreen(record(OpenRapGodConfigPayload.class)), window, where + "the NPC settings", 4);
                DjCameraGalleryScreen gallery = new DjCameraGalleryScreen(null, BlockPos.ZERO, List.of(), 0);
                screenTips(gallery, window, where + "the camera gallery", 2);
                kitButtons(gallery, 4, where + "the camera gallery");
                DjVisualFxScreen fx = new DjVisualFxScreen(null, true, false, List.of(), 120, 1.0F, state -> { });
                screenTips(fx, window, where + "the visual FX list", 2);
                kitButtons(fx, 13, where + "the visual FX list");
                switchNarration(fx, where + "the visual FX list");
            }
        } finally {
            leaveGame();
        }
    }

    /**
     * The gallery and the FX list as the game builds them: every widget is the toolkit's KitButton, none vanilla's stone
     * button, each where the screen's own layout put it and as wide as the layout measured its words.
     */
    private static void kitButtons(Screen screen, int expected, String at) throws Exception {
        Object layout = field(screen.getClass(), "layout").get(screen);
        List<Rect> placed = new ArrayList<>();
        if (layout instanceof DjCameraGalleryScreen.GalleryLayout gallery) {
            placed.addAll(List.of(gallery.back(), gallery.select(), gallery.control(), gallery.unlink()));
        } else if (layout instanceof DjVisualFxScreen.FxLayout fx) {
            placed.addAll(fx.controls().values());
        }
        int buttons = 0;
        for (Object child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) {
                continue;
            }
            buttons++;
            require(widget instanceof KitButton, at + ": '" + widget.getMessage().getString() + "' is the toolkit's button, not "
                    + widget.getClass().getSimpleName());
            Rect rect = new Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
            require(placed.stream().anyMatch(where -> Math.round(where.x()) == widget.getX()
                            && Math.round(where.y()) == widget.getY() && Math.round(where.w()) == widget.getWidth()
                            && Math.round(where.h()) == widget.getHeight()),
                    at + ": '" + widget.getMessage().getString() + "' sits where the layout put it, " + rect);
        }
        require(buttons == expected && placed.size() == expected, at + ": " + expected + " buttons, " + buttons + " built and "
                + placed.size() + " placed");
    }

    /** A switch of the FX list reads its state, as vanilla's option buttons do, and its words carry no "[ON]" any more. */
    private static void switchNarration(DjVisualFxScreen screen, String at) throws Exception {
        for (String name : List.of("trackFxButton", "djFxButton", "previewButton")) {
            KitButton button = (KitButton) field(DjVisualFxScreen.class, name).get(screen);
            Method narration = AbstractWidget.class.getDeclaredMethod("createNarrationMessage");
            narration.setAccessible(true);
            String before = spelled((Component) narration.invoke(button));
            button.onPress();
            String after = spelled((Component) narration.invoke(button));
            boolean read = before.contains(spelled(button.getMessage())) && (before.contains("options.on")
                    ? after.contains("options.off") : before.contains("options.off") && after.contains("options.on"));
            require(button.isToggle() && !button.getMessage().getString().contains("[") && read, at + ": the switch " + name
                    + " is read with its words and its state, which a press turns over: '" + before + "' then '" + after + "'");
            button.onPress();
        }
    }

    /** A component as its keys and words, the arguments of each translation inside parentheses. */
    private static String spelled(Component component) {
        StringBuilder out = new StringBuilder();
        if (component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable) {
            out.append(translatable.getKey()).append('(');
            for (Object argument : translatable.getArgs()) {
                out.append(argument instanceof Component part ? spelled(part) : String.valueOf(argument)).append(", ");
            }
            out.append(')');
        } else {
            out.append(component.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents plain
                    ? plain.text() : "");
        }
        for (Component sibling : component.getSiblings()) {
            out.append(spelled(sibling));
        }
        return out.toString();
    }

    private static void screenTips(Screen screen, int[] window, String at, int expected) throws Exception {
        built(screen, window[0], window[1]);
        int tips = widgetTips(screen, holder(screen), at);
        require(tips == expected, at + ": " + expected + " tips to check, " + tips);
        rebuilt(screen, at);
        System.out.println(at + ": " + tips + " widget tips, every one the toolkit's, a new holder with every build");
    }

    // ---------------------------------------------------------------- the visual FX list's arrows

    /**
     * The real visual FX list takes the tips its holder made (its arrows'), and none while its type selector covers the
     * buttons: vanilla's tip, four hundred up, lay under the selector's veil eight hundred up and never showed there.
     */
    private static void visualFxTips() throws Exception {
        DjVisualFxScreen screen = new DjVisualFxScreen(null, true, false, List.of(), 120, 1.0F, state -> { });
        Field holderField = field(DjVisualFxScreen.class, "tooltips");
        Field deferred = field(Screen.class, "deferredTooltipRendering");
        Field selector = field(DjVisualFxScreen.class, "typeSelectionMode");
        Object add = selector.getType().getEnumConstants()[0];
        AbstractWidget arrow = Button.builder(Component.literal("up"), button -> { }).bounds(10, 10, 20, 20).build();
        for (Object mode : new Object[]{null, add}) {
            UiTooltip.Deferred holder = new UiTooltip.Deferred();
            holderField.set(screen, holder);
            selector.set(screen, mode);
            deferred.set(screen, null);
            Tooltip tip = holder.create(Component.literal("Move the selected effect up"));
            String where = "the visual FX list" + (mode == null ? "" : " under its type selector");
            try {
                screen.setTooltipForNextRenderPass(tip, new MenuTooltipPositioner(arrow.getRectangle()), false);
                boolean pending = !holder.placement(cards, 10, 10, 400, 300, null).empty();
                require(deferred.get(screen) == null && pending == (mode == null),
                        where + (mode == null ? " takes an arrow's tip into its holder" : " takes no tip at all"));
            } catch (RuntimeException failure) {
                require(false, where + " hands the tip to vanilla, which needs a game: " + failure);
            }
        }
    }

    // ---------------------------------------------------------------- the four screens' sources

    /**
     * The DJ panel, the NPC settings, the camera gallery and the visual FX list make no tooltip vanilla would draw, hand
     * every tip to their holder first, and draw the card after their widgets inside the foreground layer: every call that
     * draws the card is among the arguments of a ClientUiForeground.render, after super.render.
     */
    private static void tooltipSources(Path root) throws Exception {
        String layer = "ClientUiForeground.render(";
        for (String name : List.of("DjPanelScreen", "RapGodConfigScreen", "DjCameraGalleryScreen", "DjVisualFxScreen")) {
            String code = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/" + name + ".java"));
            require(!code.contains("Tooltip.create("), name + " makes no tooltip vanilla would draw");
            require(code.contains("public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, "
                            + "boolean focused) {") && code.contains("if (!tooltips.offer(tooltip, positioner)) { "
                            + "super.setTooltipForNextRenderPass(tooltip, positioner, focused); }"),
                    name + " hands every tooltip to its holder before vanilla");
            require(count(code, "setTooltipForNextRenderPass(") == 2,
                    name + " sets no tooltip of its own past the holder (vanilla's frame and bitmap font)");
            int render = code.indexOf("public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {");
            int superRender = code.indexOf("super.render(graphics, mouseX, mouseY, partialTick);", render);
            String card = "tooltips.draw(graphics, mouseX, mouseY, width, height, getFocused())";
            require(render >= 0 && superRender > render && code.contains(card), name + " draws the card in render");
            for (int draw = code.indexOf(card); draw >= 0; draw = code.indexOf(card, draw + 1)) {
                require(draw > superRender && insideCall(code, layer, draw),
                        name + " draws the card after its widgets, inside the foreground layer's own call");
            }
        }
        String panel = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/DjPanelScreen.java"));
        int hint = panel.indexOf("UiTooltip.draw(graphics, hintPlacement(TextStyles.measurer(), hint.getString(), mouseX, mouseY, over, inner, width, height));");
        require(count(panel, "pointerHint = ") >= 4 && hint >= 0 && insideCall(panel, layer, hint),
                "the DJ panel's own hints (a card's x, its author, a cut status line) are the toolkit's card too, in the "
                        + "foreground layer");
        String fx = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/DjVisualFxScreen.java"));
        require(fx.contains("if (typeSelectionMode != null) { return; } if (!tooltips.offer(tooltip, positioner))"),
                "the visual FX list takes no tip while its type selector is open");
    }

    /** Whether {@code at} lies between the parentheses of a call that starts with {@code call} (which ends in "("). */
    private static boolean insideCall(String code, String call, int at) {
        for (int start = code.indexOf(call); start >= 0 && start < at; start = code.indexOf(call, start + 1)) {
            int depth = 0;
            for (int index = start + call.length() - 1; index < code.length(); index++) {
                char c = code.charAt(index);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    if (at < index) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the exact-colour button

    private static void colourButtonSource(Path root) throws Exception {
        String code = flat(root.resolve("src/main/java/ru/gora/musicbattle/client/ExactColorButton.java"));
        for (String bitmap : List.of("drawString(", "drawCenteredString(", "plainSubstrByWidth(", ".font")) {
            require(!code.contains(bitmap), "the colour button draws nothing in the bitmap font (" + bitmap + ")");
        }
        require(!code.contains("graphics.fill("), "the colour button is no square slab of the first look");
        require(code.contains("TextStyles.queueCentered(") && code.contains("WorkshopDraw.button("),
                "the colour button draws its code in the toolkit's face on the toolkit's button");
        // The colour's own alpha and RGB masks are the only literals left; every colour it draws is a palette token.
        Matcher literal = Pattern.compile("0x[0-9A-Fa-f]{6,8}").matcher(code);
        while (literal.find()) {
            require(literal.group().equalsIgnoreCase("0xFF000000") || literal.group().equalsIgnoreCase("0x00FFFFFF"),
                    "the colour button draws in palette tokens, not " + literal.group());
        }
    }

    /**
     * At every width the inspector gives a colour cell (its floor COLOR_MIN up to a whole panel) and every tier, the
     * widest code there is fits beside the swatch in the face the button picks; the button picks body wherever body fits
     * in a button 18 units high or more, and captions in a lower one, as FancyButton does; and that widest code is no
     * narrower than any code of any colour, so the face never changes with the colour.
     */
    private static void colourButton(GameTextMeasurer[] tiers) {
        String digits = "0123456789ABCDEF";
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = tiers[tier];
            for (TextStyle face : new TextStyle[]{TextStyle.BODY, TextStyle.CAPTION}) {
                String widest = ExactColorButton.widestCode(measurer, face);
                for (int index = 0; index < digits.length(); index++) {
                    String code = "#" + String.valueOf(digits.charAt(index)).repeat(6);
                    require(measurer.width(code, face) <= measurer.width(widest, face) + 0.001F,
                            "tier " + tier + " " + face + ": no code is wider than " + widest + " (" + code + ")");
                }
            }
            for (int height : new int[]{14, 16, 17, 18, 20, 22}) {
                for (int width = InspectorLayout.COLOR_MIN; width <= 520; width++) {
                    String where = "tier " + tier + " colour button " + width + "x" + height;
                    TextStyle face = ExactColorButton.codeFace(measurer, width, height);
                    float room = ExactColorButton.codeRoom(width, height);
                    String widest = ExactColorButton.widestCode(measurer, face);
                    if (height <= InspectorLayout.CONTROL) {
                        require(measurer.width(widest, face) <= room + 0.01F, where + ": the widest code " + widest
                                + " is " + measurer.width(widest, face) + " in " + face + ", room " + room);
                    }
                    boolean bodyFits = height >= 18 && measurer.width(ExactColorButton.widestCode(measurer, TextStyle.BODY),
                            TextStyle.BODY) <= room;
                    require(face == (bodyFits ? TextStyle.BODY : TextStyle.CAPTION),
                            where + ": the code is in " + face + " where " + (bodyFits ? "BODY" : "CAPTION") + " is right");
                }
            }
        }
    }

    /**
     * The colour button as its own renderWidget draws it (the fallback look), at every tier, at the inspector's height of
     * 20 and a low 16, at widths from its floor up, with a black, a white and a cyan, idle, focused and switched off: the
     * code is written once, whole, in the toolkit's font in the face codeFace picks, centred in the room between the swatch
     * and the right margin and inside it, in the colour of the state (TEXT_2, TEXT_1, TEXT_MUTE); and the swatch, right of
     * the inset, is the colour itself, faded with the button when it is switched off.
     */
    private static void colourButtonDrawn() throws Exception {
        Screen parent = new Screen(Component.literal("parent")) { };
        int[] colours = {0xFF000000, 0xFFFFFFFF, 0xFF55E6FF};
        String[] states = {"idle", "focused", "switched off"};
        int drawn = 0;
        try {
            for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
                enterGame(tier, "en_us");
                for (int height : new int[]{16, InspectorLayout.CONTROL}) {
                    for (int width = InspectorLayout.COLOR_MIN; width <= 520; width += width < 160 ? 1 : 60) {
                        TextStyle face = ExactColorButton.codeFace(tiers[tier], width, height);
                        float swatch = ExactColorButton.swatch(height);
                        float left = 7 + ExactColorButton.INSET + swatch + ExactColorButton.CODE_GAP;
                        float room = ExactColorButton.codeRoom(width, height);
                        for (int colour : colours) {
                            ExactColorButton button = new ExactColorButton(parent, 7, 11, width, height,
                                    Component.literal("Colour"), colour, picked -> { });
                            for (int state = 0; state < states.length; state++) {
                                button.active = state != 2;
                                button.setFocused(state == 1);
                                String where = "tier " + tier + " colour button " + width + "x" + height + " "
                                        + ExactColorButton.hex(colour) + " " + states[state];
                                WRITTEN.clear();
                                Canvas canvas = Canvas.create();
                                renderWidget(button, canvas);
                                drawn++;
                                require(WRITTEN.size() == 1, where + ": the code is written once, not " + WRITTEN);
                                if (WRITTEN.size() != 1) {
                                    continue;
                                }
                                Written code = WRITTEN.getFirst();
                                float pixel = 1.0F / tier;
                                float codeWidth = tiers[tier].width(code.text(), face);
                                require(code.text().equals(ExactColorButton.hex(colour)),
                                        where + ": the code is written whole, '" + code.text() + "'");
                                require(TextStyles.fontId(face, tier).equals(code.font()),
                                        where + ": the code is in the toolkit's " + face + " at tier " + tier + ", not "
                                                + code.font());
                                require(Math.abs(code.x() + codeWidth / 2.0F - (left + room / 2.0F)) <= pixel / 2.0F + 0.001F,
                                        where + ": the code is centred in the room beside the swatch, at "
                                                + (code.x() + codeWidth / 2.0F) + " for " + (left + room / 2.0F));
                                require(code.x() >= left - pixel && code.x() + codeWidth <= left + room + pixel,
                                        where + ": the code stays between the swatch and the right margin");
                                int expected = state == 2 ? Palette.TEXT_MUTE : state == 1 ? Palette.TEXT_1 : Palette.TEXT_2;
                                require(code.colour() == expected, where + ": the code is in the state's colour, "
                                        + Integer.toHexString(code.colour()));
                                Shape swatchFill = canvas.shapes.size() > 1 ? canvas.shapes.get(1) : null;
                                require(swatchFill != null && swatchFill.colour() == (state == 2 ? Palette.fade(colour, 0.45F)
                                                : colour) && near(swatchFill.centreX(), 7 + ExactColorButton.INSET + swatch / 2.0F, 0.51F),
                                        where + ": the swatch right of the inset is the colour itself");
                            }
                        }
                    }
                }
            }
        } finally {
            leaveGame();
        }
        System.out.println("colour button: " + drawn + " drawings, the code in Nunito and centred beside the swatch");
    }

    // ---------------------------------------------------------------- the OLD_TV sweep switch

    /** The sweep row's switch says what it does in words of its own, never its label's. */
    private static void sweepSwitch(GameTextMeasurer measurer, Map<String, String> strings, String code) throws Exception {
        String label = value(strings, "music_battle.l10n.fx.label_061");
        String words = value(strings, SWEEP_SWITCH);
        TrackEffectEditScreen screen = fxScreen(TrackEffect.Type.OLD_TV, measurer);
        boolean found = false;
        for (List<InspectorLayout.Cell> row : screen.inspectorCells()) {
            for (InspectorLayout.Cell cell : row) {
                if (cell.label().equals(label)) {
                    found = true;
                    require(cell.kind() == InspectorLayout.Kind.SWITCH && cell.texts().equals(List.of(words)),
                            code + ": the sweep row's switch says '" + words + "', not " + cell.texts());
                }
            }
        }
        require(found, code + ": the OLD_TV inspector has its sweep row under '" + label + "'");
        require(!words.equals(label) && !words.isBlank(), code + ": the sweep switch's words are not its label's");
    }

    /**
     * Every switch of the FX inspector, in every effect's rows and in the outline's with every number of colours, says what
     * it does in words of its own, never the label of its cell: the fade rows' switches said "Fade in" under "Fade in" and
     * «Плавное появление» under «Плавное появление», as the sweep's had.
     */
    private static void switchWords(GameTextMeasurer measurer, String code) throws Exception {
        int switches = 0;
        for (TrackEffect.Type type : TrackEffect.Type.values()) {
            for (List<InspectorLayout.Cell> row : everyRow(fxScreen(type, measurer), type, code)) {
                for (InspectorLayout.Cell cell : row) {
                    if (cell.kind() != InspectorLayout.Kind.SWITCH || cell.labels().isEmpty()) {
                        continue;
                    }
                    switches++;
                    for (String text : cell.texts()) {
                        for (String label : cell.labels()) {
                            require(!plain(text).equalsIgnoreCase(plain(label)), code + " " + type + ": the switch under '"
                                    + label + "' says '" + text + "', not its label again");
                        }
                    }
                }
            }
        }
        require(switches >= 20, code + ": the inspector's switches are there to check, " + switches);
        System.out.println(code + ": " + switches + " FX switches under a label, each in words of its own");
    }

    /** A label as read: its no-break spaces as spaces. */
    private static String plain(String text) {
        return text.replace(NBSP, " ").strip();
    }

    /** The rows a screen shows now, and for the outline, the rows with every number of colours a player can add. */
    private static List<List<InspectorLayout.Cell>> everyRow(TrackEffectEditScreen screen, TrackEffect.Type type, String code)
            throws Exception {
        List<List<InspectorLayout.Cell>> rows = new ArrayList<>(screen.inspectorCells());
        if (type == TrackEffect.Type.OBJECT_OUTLINE) {
            Button add = (Button) field(TrackEffectEditScreen.class, "objectOutlineAddColorButton").get(screen);
            List<?> colours = (List<?>) field(TrackEffectEditScreen.class, "objectOutlineColors").get(screen);
            while (colours.size() < TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS) {
                int before = colours.size();
                add.onPress();
                if (colours.size() != before + 1) {
                    require(false, code + ": the outline's Add takes a colour, " + before + " -> " + colours.size());
                    break;
                }
                rows.addAll(screen.inspectorCells());
            }
        }
        return rows;
    }

    /** A number range with its unit after it, the two joined by a plain space: "0–200 ticks", "1–160 px". */
    private static final Pattern RANGE_THEN_UNIT = Pattern.compile("\\d[–…][−-]?[\\d.,]+ (?=[\\p{L}%°])");
    /** A line that ends on a number range, and one that starts with a word. */
    private static final Pattern ENDS_ON_RANGE = Pattern.compile("\\d[–…][−-]?[\\d.,]+$");

    /**
     * A range stays with its unit: in every label the FX editor shows and every value of a key it names, a number range
     * and the unit after it are joined by U+00A0, and at every width from one unit to a whole panel and every tier no line
     * the inspector wraps ends on a range while the next starts with its unit. At 320 to 359 wide the fade rows read
     * «0–200» / «тиков (0 = стандарт)» and the old TV's "1–160" / "px".
     */
    private static void rangesKeepTheirUnits(Path root, GameTextMeasurer[] tiers, Map<String, String> strings, String code)
            throws Exception {
        Set<String> labels = new LinkedHashSet<>();
        for (TrackEffect.Type type : TrackEffect.Type.values()) {
            for (List<InspectorLayout.Cell> row : everyRow(fxScreen(type, tiers[2]), type, code)) {
                for (InspectorLayout.Cell cell : row) {
                    labels.addAll(cell.labels());
                }
            }
        }
        for (String key : keys(root, "TrackEffectEditScreen")) {
            if (strings.containsKey(key)) {
                labels.add(strings.get(key));
            }
        }
        int ranged = 0;
        for (String label : labels) {
            Matcher split = RANGE_THEN_UNIT.matcher(label);
            boolean found = split.find();
            require(!found, code + ": '" + label + "' joins its range and unit with U+00A0, not a space at "
                    + (found ? split.start() : -1));
            if (!label.matches("(?s).*\\d[–…][−-]?[\\d.,]+\u00A0[\\p{L}%°].*")) {
                continue;
            }
            ranged++;
            for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
                for (int width = 1; width <= 600; width++) {
                    List<String> lines = InspectorLayout.wrapWhole(tiers[tier], label, width);
                    if (lines == null) {
                        continue;
                    }
                    for (int index = 0; index + 1 < lines.size(); index++) {
                        require(!(ENDS_ON_RANGE.matcher(lines.get(index).strip()).find()
                                        && Character.isLetter(lines.get(index + 1).strip().charAt(0))),
                                code + " tier " + tier + " width " + width + ": '" + label + "' splits a range from its unit: "
                                        + lines);
                    }
                }
            }
        }
        require(ranged >= 6, code + ": the FX labels with a range and a unit are there to check, " + ranged);
        System.out.println(code + ": " + ranged + " FX labels with a range and its unit, each at widths 1-600 and tiers 1-9");
    }

    // ---------------------------------------------------------------- parentheses in the FX labels

    /** U+00A0 is in Nunito in every face, and at every tier exactly as wide as a space: the layout measures it right. */
    private static void noBreakSpace(GameTextMeasurer[] tiers) {
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            for (TextStyle face : TextStyle.values()) {
                try {
                    float space = tiers[tier].width("0 =", face);
                    float joined = tiers[tier].width("0" + NBSP + "=", face);
                    require(Math.abs(space - joined) < 0.0001F, "tier " + tier + " " + face
                            + ": U+00A0 is as wide as a space, " + joined + " / " + space);
                } catch (AssertionError missing) {
                    require(false, "tier " + tier + " " + face + ": Nunito has U+00A0: " + missing.getMessage());
                }
            }
        }
    }

    /**
     * Every label the FX editor shows: every effect's rows, and the outline's with every number of colours from one to the
     * most it takes, added with its own button as a player adds them (the colour cycle's speed shows from two). Inside a
     * parenthesis the words are joined by U+00A0, so the inspector's wrapping, which breaks at plain spaces only, keeps the
     * parenthesis on one line; and at every width from one unit to a whole panel, at every tier, no line it makes ends
     * inside a parenthesis. Every label with a parenthesis that the editor names must be among those measured: a row that
     * shows only in some state is otherwise easy to miss.
     */
    private static void parentheses(Path root, GameTextMeasurer[] tiers, Map<String, String> strings, String code)
            throws Exception {
        Set<String> labels = new LinkedHashSet<>();
        for (TrackEffect.Type type : TrackEffect.Type.values()) {
            TrackEffectEditScreen screen = fxScreen(type, tiers[2]);
            parenthesised(screen, labels);
            if (type == TrackEffect.Type.OBJECT_OUTLINE) {
                Button add = (Button) field(TrackEffectEditScreen.class, "objectOutlineAddColorButton").get(screen);
                List<?> colours = (List<?>) field(TrackEffectEditScreen.class, "objectOutlineColors").get(screen);
                while (colours.size() < TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS) {
                    int before = colours.size();
                    add.onPress();
                    require(colours.size() == before + 1, code + ": the outline's Add takes a colour, " + before + " -> "
                            + colours.size());
                    if (colours.size() != before + 1) {
                        break;
                    }
                    parenthesised(screen, labels);
                }
            }
        }
        for (String key : keys(root, "TrackEffectEditScreen")) {
            String value = strings.get(key);
            if (value != null && value.contains("(")) {
                require(labels.contains(value), code + ": the sweep measures '" + value + "' (" + key
                        + "), a label with a parenthesis that the FX editor names");
            }
        }
        require(labels.size() >= 11, code + ": the FX labels with a parenthesis are there to check, " + labels.size());
        System.out.println(code + ": " + labels.size() + " FX labels with a parenthesis, each at widths 1-600 and tiers 1-9");
        for (String label : labels) {
            Matcher group = Pattern.compile("\\(([^)]*)\\)").matcher(label);
            while (group.find()) {
                require(!group.group(1).contains(" "), code + ": '" + label.replace(NBSP, "\u2423")
                        + "' joins the words inside its parenthesis with U+00A0 (shown as \u2423)");
            }
            for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
                for (int width = 1; width <= 600; width++) {
                    List<String> lines = InspectorLayout.wrapWhole(tiers[tier], label, width);
                    if (lines == null) {
                        continue;
                    }
                    int depth = 0;
                    for (int index = 0; index < lines.size(); index++) {
                        String line = lines.get(index);
                        for (int at = 0; at < line.length(); at++) {
                            depth += line.charAt(at) == '(' ? 1 : line.charAt(at) == ')' ? -1 : 0;
                        }
                        require(depth == 0 || index == lines.size() - 1, code + " tier " + tier + " width " + width + ": '"
                                + label + "' breaks inside its parenthesis: " + lines);
                    }
                }
            }
        }
    }

    /** The labels with a parenthesis among the rows the screen shows now. */
    private static void parenthesised(TrackEffectEditScreen screen, Set<String> labels) {
        for (List<InspectorLayout.Cell> row : screen.inspectorCells()) {
            for (InspectorLayout.Cell cell : row) {
                for (String form : cell.labels()) {
                    if (form.contains("(")) {
                        labels.add(form);
                    }
                }
            }
        }
    }

    /** The real FX editor for an effect, under the editor's own host, laid out with {@code measurer}. */
    private static TrackEffectEditScreen fxScreen(TrackEffect.Type type, GameTextMeasurer measurer) throws Exception {
        TrackEffect seed = DjVisualEffectConfig.of(type).effect();
        TrackEffectEditScreen screen = new TrackEffectEditScreen(new Host(), seed, seed, "point");
        screen.width = 640;
        screen.height = 360;
        field(Screen.class, "font").set(screen, GAME_FONT);
        field(TrackEffectEditScreen.class, "measurer").set(screen, measurer);
        screen.init();
        return screen;
    }

    private static final class Host implements TrackEffectEditHost {
        @Override public Screen parentScreen() { return null; }
        @Override public Path effectPreviewAudioPath() { return null; }
        @Override public boolean managesPreviewAudio() { return false; }
        @Override public void saveEffect(TrackEffect original, TrackEffect edited, String placementMode) { }
        @Override public boolean showBrushActions() { return true; }
        @Override public boolean showTimelineControls() { return true; }
        @Override public boolean allowTypeChange() { return true; }
    }

    // ---------------------------------------------------------------- a game without a window

    private static Minecraft game;
    private static Window window;
    /** The toolkit's font ids, each with its face and tier, so the game font below measures them as the game does. */
    private static final Map<ResourceLocation, TextStyle> FACES = new HashMap<>();
    private static final Map<ResourceLocation, Integer> FACE_TIERS = new HashMap<>();

    /** A line the game font was asked to draw: its words, where it starts, its colour and the font of its first glyph. */
    record Written(String text, float x, float y, int colour, ResourceLocation font) { }

    private static final List<Written> WRITTEN = new ArrayList<>();

    /**
     * Minecraft's registries bootstrapped, which the render types of the icon batch need, and a Minecraft with a window
     * (its GUI scale), options (the language) and the game font, made without its constructor. It becomes the game's
     * instance only between {@link #enterGame} and {@link #leaveGame}; the FX editor's rows are measured without it, as
     * they always were.
     */
    private static void startGame() throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The bootstrap hands the standard streams to the game's logger; this check prints its own lines.
        System.setOut(out);
        System.setErr(err);
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            for (TextStyle face : TextStyle.values()) {
                FACES.put(TextStyles.fontId(face, tier), face);
                FACE_TIERS.put(TextStyles.fontId(face, tier), tier);
            }
        }
        game = allocate(Minecraft.class);
        window = allocate(Window.class);
        field(Minecraft.class, "window").set(game, window);
        field(Minecraft.class, "font").set(game, GAME_FONT);
        field(Minecraft.class, "options").set(game, allocate(Options.class));
        field(Minecraft.class, "lastInputType").set(game, InputType.MOUSE);
        require(!UiSdf.available(), "the game here has no shader, so the toolkit draws its fallback look");
    }

    private static void enterGame(int guiScale, String code) throws Exception {
        field(Window.class, "guiScale").setDouble(window, guiScale);
        game.options.languageCode = code;
        field(Minecraft.class, "instance").set(null, game);
    }

    private static void leaveGame() throws Exception {
        field(Minecraft.class, "instance").set(null, null);
        shader(false);
    }

    private static double window() {
        return window.getGuiScale();
    }

    /**
     * The toolkit's shapes as the shader draws them (a stand-in shader, so UiSdf queues its quads) or as the fallback's
     * square fills.
     */
    private static void shader(boolean smooth) throws Exception {
        field(UiSdf.class, "shader").set(null, smooth ? allocate(ShaderInstance.class) : null);
        require(UiSdf.available() == smooth, "the toolkit draws the " + (smooth ? "shader's" : "fallback") + " look");
    }

    /** A screen as the game opens it: its size, the game's font and Minecraft, then its own init. */
    private static <T extends Screen> T built(T screen, int width, int height) throws Exception {
        screen.width = width;
        screen.height = height;
        field(Screen.class, "font").set(screen, GAME_FONT);
        field(Screen.class, "minecraft").set(screen, game);
        invoke(screen, "init");
        return screen;
    }

    private static UiTooltip.Deferred holder(Screen screen) throws Exception {
        return (UiTooltip.Deferred) field(screen.getClass(), "tooltips").get(screen);
    }

    private static Map<?, ?> texts(UiTooltip.Deferred holder) throws Exception {
        return (Map<?, ?>) field(UiTooltip.Deferred.class, "texts").get(holder);
    }

    private static List<Tooltip> tips(Screen screen) {
        List<Tooltip> tips = new ArrayList<>();
        for (Object renderable : screen.children()) {
            if (renderable instanceof AbstractWidget widget && widget.getTooltip() != null) {
                tips.add(widget.getTooltip());
            }
        }
        return tips;
    }

    private static void renderWidget(AbstractWidget widget, GuiGraphics graphics) throws Exception {
        Method render = AbstractWidget.class.getDeclaredMethod("renderWidget", GuiGraphics.class, int.class, int.class,
                float.class);
        render.setAccessible(true);
        render.invoke(widget, graphics, -1000, -1000, 0.0F);
    }

    /**
     * The game's font for these screens: six units a letter in the bitmap font, as the panel's search hint and the editors'
     * boxes need only a width; the toolkit's faces measured with the game's glyph provider at their tier, as TextStyles
     * measures them in play; and every line it is asked to draw recorded in WRITTEN rather than drawn.
     */
    private static final Font GAME_FONT = new Font(ignored -> null, false) {
        private final StringSplitter splitter = new StringSplitter((codePoint, style) -> {
            TextStyle face = FACES.get(style.getFont());
            return face == null ? 6.0F : tiers[FACE_TIERS.get(style.getFont())].width(Character.toString(codePoint), face);
        });

        @Override public StringSplitter getSplitter() { return splitter; }
        @Override public int width(String text) { return text == null ? 0 : text.length() * 6; }
        @Override public int width(FormattedText text) { return text.getString().length() * 6; }
        @Override public int width(FormattedCharSequence text) { return 60; }
        @Override public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
        }

        @Override
        public int drawInBatch(FormattedCharSequence text, float x, float y, int colour, boolean shadow, Matrix4f pose,
                               MultiBufferSource buffers, Font.DisplayMode mode, int background, int light) {
            StringBuilder words = new StringBuilder();
            ResourceLocation[] font = new ResourceLocation[1];
            text.accept((index, style, codePoint) -> {
                words.appendCodePoint(codePoint);
                if (font[0] == null) {
                    font[0] = style.getFont();
                }
                return true;
            });
            WRITTEN.add(new Written(words.toString(), x, y, colour, font[0]));
            return 0;
        }
    };

    /** What a button drew: vanilla's fills (the fallback look), the shader's quads (padded boxes) and icon quads. */
    enum Kind { FILL, SHAPE, ICON }

    /** One shape as drawn, by its four corners in the order they were given, its colour and an icon's top-left UV. */
    record Shape(Kind kind, float[] corners, int colour, float u, float v) {
        float centreX() {
            return (corners[0] + corners[2] + corners[4] + corners[6]) / 4.0F;
        }

        float centreY() {
            return (corners[1] + corners[3] + corners[5] + corners[7]) / 4.0F;
        }

        float side(int index) {
            int next = (index + 1) % 4;
            return (float) Math.hypot(corners[next * 2] - corners[index * 2], corners[next * 2 + 1] - corners[index * 2 + 1]);
        }

        float length() {
            return Math.max(side(0), side(1));
        }

        float thickness() {
            return Math.min(side(0), side(1));
        }

        /** The direction of its long side, in degrees from the x axis with y down, folded into [0, 180). */
        double angle() {
            int index = side(0) >= side(1) ? 0 : 1;
            int next = index + 1;
            double degrees = Math.toDegrees(Math.atan2(corners[next * 2 + 1] - corners[index * 2 + 1],
                    corners[next * 2] - corners[index * 2]));
            return ((degrees % 180.0) + 180.0) % 180.0;
        }

        boolean contains(float x, float y) {
            Path2D.Float path = new Path2D.Float();
            path.moveTo(corners[0], corners[1]);
            for (int index = 1; index < 4; index++) {
                path.lineTo(corners[index * 2], corners[index * 2 + 1]);
            }
            path.closePath();
            return path.contains(x, y);
        }

        @Override
        public String toString() {
            return kind + " " + Integer.toHexString(colour) + " at (" + centreX() + ", " + centreY() + ")";
        }
    }

    /** GuiGraphics with its sinks replaced: vanilla's fills, the shader's quads and icons are recorded as drawn. */
    static final class Canvas extends GuiGraphics {
        PoseStack poses;
        List<Shape> shapes;
        MultiBufferSource.BufferSource buffers;

        private Canvas() {
            super(null, (MultiBufferSource.BufferSource) null);
        }

        static Canvas create() throws Exception {
            // GuiGraphics' constructor touches the game; recording what is drawn does not need it.
            Canvas canvas = allocate(Canvas.class);
            canvas.poses = new PoseStack();
            canvas.shapes = new ArrayList<>();
            VertexConsumer shapes = new Sink(canvas, Kind.SHAPE);
            VertexConsumer icons = new Sink(canvas, Kind.ICON);
            canvas.buffers = new MultiBufferSource.BufferSource(null, new LinkedHashMap<>()) {
                @Override public VertexConsumer getBuffer(RenderType type) { return type == UiSdf.SHAPES ? shapes : icons; }
            };
            return canvas;
        }

        @Override public PoseStack pose() { return poses; }
        @Override public MultiBufferSource.BufferSource bufferSource() { return buffers; }
        @Override public void enableScissor(int x, int y, int right, int bottom) { }
        @Override public void disableScissor() { }
        @Override public void flush() { }

        @Override
        public void fill(int left, int top, int right, int bottom, int colour) {
            Matrix4f pose = poses.last().pose();
            int[][] points = {{left, top}, {right, top}, {right, bottom}, {left, bottom}};
            float[] corners = new float[8];
            for (int index = 0; index < 4; index++) {
                Vector3f at = pose.transformPosition(points[index][0], points[index][1], 0.0F, new Vector3f());
                corners[index * 2] = at.x;
                corners[index * 2 + 1] = at.y;
            }
            shapes.add(new Shape(Kind.FILL, corners, colour, 0.0F, 0.0F));
        }
    }

    /** A buffer that records each quad it is given (four vertices, each ended by its colour) as a shape of one kind. */
    static final class Sink implements VertexConsumer {
        private final Canvas canvas;
        private final Kind kind;
        private final float[] corners = new float[8];
        private float u = Float.MAX_VALUE;
        private float v = Float.MAX_VALUE;
        private int count;

        Sink(Canvas canvas, Kind kind) {
            this.canvas = canvas;
            this.kind = kind;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            corners[count * 2] = x;
            corners[count * 2 + 1] = y;
            return this;
        }

        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            if (++count == 4) {
                canvas.shapes.add(new Shape(kind, corners.clone(), alpha << 24 | red << 16 | green << 8 | blue, u, v));
                count = 0;
                u = Float.MAX_VALUE;
                v = Float.MAX_VALUE;
            }
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) {
            if (kind == Kind.ICON) {
                this.u = Math.min(this.u, u);
                this.v = Math.min(this.v, v);
            }
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    // ---------------------------------------------------------------- helpers

    /** The language the screens read their words from: the game's Language and I18n, as the other checks inject them. */
    private static void inject(Map<String, String> strings) throws Exception {
        Language language = new Language() {
            @Override public String getOrDefault(String key, String fallback) { return strings.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return strings.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }

            // Left to right as written, as the game's own default language orders it.
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) {
                return sink -> text.visit((style, part) -> StringDecomposer.iterateFormatted(part, style, sink)
                        ? Optional.empty() : FormattedText.STOP_ITERATION, Style.EMPTY).isPresent();
            }
        };
        Language.inject(language);
        Field i18n = I18n.class.getDeclaredField("language");
        i18n.setAccessible(true);
        i18n.set(null, language);
    }

    /** A record with every component at its empty value: lists and sets empty, numbers nought, positions the origin. */
    private static <T> T record(Class<T> type) throws Exception {
        RecordComponent[] parts = type.getRecordComponents();
        Class<?>[] types = new Class<?>[parts.length];
        Object[] values = new Object[parts.length];
        for (int index = 0; index < parts.length; index++) {
            Class<?> part = parts[index].getType();
            types[index] = part;
            values[index] = part == boolean.class ? Boolean.FALSE : part == int.class ? 0 : part == long.class ? 0L
                    : part == float.class ? 0.0F : part == double.class ? 0.0 : part == String.class ? ""
                    : List.class.isAssignableFrom(part) ? List.of() : Set.class.isAssignableFrom(part) ? Set.of()
                    : part == BlockPos.class ? BlockPos.ZERO : null;
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }

    private static String flat(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ").replaceAll("\\s+", " ");
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            found++;
        }
        return found;
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
        require(value != null, "the language file has " + key + " (merge this wave's lang_keys first)");
        return value == null ? key : value;
    }

    private static boolean near(double a, double b) {
        return near(a, b, 0.01);
    }

    private static boolean near(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafe = unsafeClass.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), type);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Calls a method without arguments where the class or a superclass declares it, as the game would call it. */
    private static void invoke(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(target);
                return;
            } catch (NoSuchMethodException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchMethodException(target.getClass() + "." + name);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition && FAILURES.size() < 400) {
            FAILURES.add("FAIL: " + message);
        }
    }
}
