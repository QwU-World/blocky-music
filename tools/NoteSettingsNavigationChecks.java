package ru.gora.musicbattle.client;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Real screen initialization and scrolling, in English and Russian with the real lang files; does not render fonts or
 * open Minecraft. The tabs are measured with the bundled Nunito through java.awt, the way the game measures them.
 *
 * <p>Proves: the five tabs stay above the preview and the settings, inside the window, apart from each other and in
 * place while the settings scroll; the current tab is a tab that cannot be pressed, not a disabled button; every tab
 * shows its whole label; the dimming slider changes what a battle draws at once, its 0% draws nothing, and the bar
 * opacity slider can be used exactly while the black bars are on.
 *
 * <p>Usage: java -cp &lt;classpath&gt; ru.gora.musicbattle.client.NoteSettingsNavigationChecks [project root]
 */
public final class NoteSettingsNavigationChecks {
    /** The wording this change proposes for keys the lang files may not carry yet (SettingsLayoutChecks has the rest). */
    private static final Map<String, String[]> PROPOSED = Map.of(
            "music_battle.settings.world_dimming", new String[]{"World dimming: %s%%",
                    "Затемнение мира: %s%%"},
            "music_battle.settings.stage_bars", new String[]{"Black bars: %s",
                    "Чёрные полосы: %s"},
            "music_battle.settings.stage_bar_opacity", new String[]{"Bar opacity: %s%%",
                    "Непрозрачность полос: %s%%"});

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        FMLPaths.loadAbsolutePaths(Files.createTempDirectory("blocky-note-navigation-"));
        TextMeasurer measurer = new Measurer(root.resolve("src/main/resources/assets/music_battle/font"));
        var setLanguage = I18n.class.getDeclaredMethod("setLanguage", Language.class);
        setLanguage.setAccessible(true);
        for (String lang : List.of("en_us", "ru_ru")) {
            Map<String, String> strings = new HashMap<>();
            try (var input = Files.newInputStream(root.resolve("src/main/resources/assets/music_battle/lang/" + lang + ".json"))) {
                Language.loadFromJson(input, strings::put);
            }
            PROPOSED.forEach((key, text) -> strings.putIfAbsent(key, text[lang.equals("ru_ru") ? 1 : 0]));
            Language.inject(new Language() {
                public String getOrDefault(String key, String fallback) { return strings.getOrDefault(key, fallback); }
                public boolean has(String key) { return strings.containsKey(key); }
                public boolean isDefaultRightToLeft() { return false; }
                public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
            });
            setLanguage.invoke(null, Language.getInstance());
            for (int[] size : new int[][]{{320, 240}, {426, 240}, {640, 360}, {854, 480}, {1280, 720}}) {
                verify(lang + " " + size[0] + "x" + size[1], size[0], size[1], measurer);
            }
        }
        System.out.println("PASS: five fixed tabs, whole labels, the current one a tab not a disabled button, at five GUI sizes "
                + "in two languages; the dimming and bar controls drive the battle's backdrop at once");
    }

    private static void verify(String context, int width, int height, TextMeasurer measurer) throws Exception {
        MusicBattleSettingsScreen screen = new MusicBattleSettingsScreen(null);
        screen.width = width;
        screen.height = height;
        screen.build(measurer);
        SettingsScreenLayout layout = (SettingsScreenLayout) field(screen, "layout");
        List<AbstractWidget> tabs = new ArrayList<>();
        for (int index = 0; index < SettingsScreenLayout.NAVIGATION.size(); index++) {
            String key = SettingsScreenLayout.NAVIGATION.get(index);
            AbstractWidget tab = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(widget -> widget.getMessage().getContents() instanceof TranslatableContents tr && tr.getKey().equals(key))
                    .findFirst().orElseThrow(() -> new AssertionError(context + ": missing tab " + key));
            require(tab.visible && tab.getY() >= 0 && tab.getX() >= 0 && tab.getX() + tab.getWidth() <= width,
                    context + ": tab is hidden or outside the screen: " + key);
            require(tab.getY() + tab.getHeight() < Math.min(layout.controls().y(), layout.preview().y()),
                    context + ": tab sits inside the scrolling settings: " + key);
            for (AbstractWidget previous : tabs) {
                require(tab.getX() >= previous.getX() + previous.getWidth() || previous.getX() >= tab.getX() + tab.getWidth()
                        || tab.getY() >= previous.getY() + previous.getHeight() || previous.getY() >= tab.getY() + tab.getHeight(),
                        context + ": tabs overlap");
            }
            require(!(tab instanceof FancyButton), context + ": " + key + " is still a plain button, not a tab");
            require(tab.active == (index != 0), context + ": only the current tab, " + key + ", should be the one that cannot be pressed");
            require(field(tab, "label").equals(tab.getMessage().getString()), context + ": tab " + key + " cuts its label to \""
                    + field(tab, "label") + "\"");
            tabs.add(tab);
        }
        int[] y = tabs.stream().mapToInt(AbstractWidget::getY).toArray();
        screen.mouseScrolled(layout.controls().x() + 1, layout.controls().y() + 1, 0, -100);
        for (int i = 0; i < tabs.size(); i++) {
            require(tabs.get(i).visible && tabs.get(i).getY() == y[i], context + ": a tab moves or hides when the settings scroll");
        }

        AbstractSliderButton dimming = slider(screen, "music_battle.settings.world_dimming");
        AbstractSliderButton barOpacity = slider(screen, "music_battle.settings.stage_bar_opacity");
        Button bars = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(widget -> widget.getMessage().getContents() instanceof TranslatableContents tr
                        && tr.getKey().equals("music_battle.settings.stage_bars"))
                .findFirst().orElseThrow(() -> new AssertionError(context + ": no black bars switch"));
        // The dimming slider changes what the battle draws the moment it moves: the HUD and the preview both read the
        // setting every frame. Driven down by keyboard, the frame where it shows 0% draws no veil at all.
        dimming.visible = true;
        dimming.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        int before = ClientBattleSettings.worldDimmingAlpha();
        for (int step = 0; step < 2000 && percentShown(dimming) > 0; step++) {
            dimming.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0);
            int shown = percentShown(dimming);
            require((shown == 0) == (ClientBattleSettings.worldDimmingAlpha() == 0),
                    context + ": the dimming slider shows " + shown + "% while the battle draws alpha "
                            + ClientBattleSettings.worldDimmingAlpha());
        }
        require(percentShown(dimming) == 0 && ClientBattleSettings.worldDimmingAlpha() == 0 && before > 0,
                context + ": the dimming slider does not reach a 0% that draws nothing");
        for (int step = 0; step < 4000 && (double) field(dimming, "value") < 1.0; step++) {
            dimming.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        }
        require(percentShown(dimming) == 90 && ClientBattleSettings.worldDimmingAlpha() == Math.round(BattleBackdrop.MAX_DIMMING * 255F),
                context + ": the dimming slider's end is not the 90% the battle draws");
        ClientBattleSettings.setWorldDimming(BattleBackdrop.DEFAULT_DIMMING);

        boolean shown = ClientBattleSettings.stageBars();
        require(barOpacity.active == shown, context + ": the bar opacity slider can be used while the bars are off, or not while on");
        bars.onPress();
        require(ClientBattleSettings.stageBars() != shown && barOpacity.active == !shown
                        && (ClientBattleSettings.stageBarAlpha() == 0) == shown,
                context + ": the black bars switch does not switch the bars and their opacity slider together");
        bars.onPress();
        require(ClientBattleSettings.stageBars() == shown && barOpacity.active == shown, context + ": the switch does not switch back");
    }

    private static AbstractSliderButton slider(MusicBattleSettingsScreen screen, String key) throws Exception {
        for (Object child : screen.children()) {
            if (child instanceof AbstractSliderButton slider && key.equals(field(slider, "key"))) {
                return slider;
            }
        }
        throw new AssertionError("no slider " + key);
    }

    /** The number a percent slider's label shows, read back from the label the player sees. */
    private static int percentShown(AbstractSliderButton slider) {
        String text = slider.getMessage().getString();
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("(\\d+)%").matcher(text);
        require(number.find(), "no percent in \"" + text + "\"");
        return Integer.parseInt(number.group(1));
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Keep looking in the superclass.
            }
        }
        throw new AssertionError("no field " + name + " on " + target.getClass());
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Nunito through java.awt, the stand-in the layout checks use for Minecraft's TTF provider. */
    private static final class Measurer implements TextMeasurer {
        private final Map<String, Font> faces = new HashMap<>();
        private final FontRenderContext context = new FontRenderContext(null, true, true);

        Measurer(Path fontDirectory) throws Exception {
            Map<String, Font> files = new HashMap<>();
            for (TextStyle style : TextStyle.values()) {
                Font base = files.computeIfAbsent(style.fontFile(), file -> {
                    try {
                        return Font.createFont(Font.TRUETYPE_FONT, fontDirectory.resolve(file + ".ttf").toFile());
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                faces.put(style.name(), base.deriveFont(style.size()));
            }
        }

        @Override
        public float width(String text, TextStyle style) {
            return text == null || text.isEmpty() ? 0F : (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
        }
    }
}
