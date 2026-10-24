package ru.gora.musicbattle.client;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import ru.gora.musicbattle.data.FxRange;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/** Real inspector widgets, draft history and DJ callbacks; no game window or audio. */
public final class FxHudEditorChecks {
    private static final Font FONT = new Font(ignored -> null, false) {
        @Override public int width(String text) { return text.length() * 6; }
        @Override public String plainSubstrByWidth(String text, int width) {
            return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
        }
    };
    private static int checks;

    public static void run() throws Exception {
        for (boolean dj : new boolean[]{false, true}) for (int[] size : new int[][]{{426,240},{854,480}})
            for (boolean hud : new boolean[]{false,true}) for (boolean modHud : new boolean[]{false,true}) {
                JsonObject json = new JsonObject();
                json.addProperty("hideHud", hud); json.addProperty("hideBlockyMusicHud", modHud);
                TrackEffect seed = new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND, 10, 80, 1)
                        .withSettings(TrackEffectSettings.readJson(json));
                Host host = new Host(dj);
                var screen = new TrackEffectEditScreen(host, seed, seed, "point");
                screen.width = size[0]; screen.height = size[1];
                Field font = Screen.class.getDeclaredField("font"); font.setAccessible(true); font.set(screen, FONT);
                screen.init();
                choices(screen, hud, modHud, "opened inspector");
                Button game = (Button) field(screen, "hideHudButton").get(screen);
                Button mod = (Button) field(screen, "hideBlockyMusicHudButton").get(screen);
                check(mod.getY() >= game.getY() + game.getHeight(), "HUD buttons occupy separate rows");
                mod.onPress();
                choices(screen, hud, !modHud, "mod HUD toggle");
                call(screen, "undoSettings"); choices(screen, hud, modHud, "undo");
                call(screen, "redoSettings"); choices(screen, hud, !modHud, "redo");
                // Reinitialization is the production path used after resize/picker return.
                screen.init(); choices(screen, hud, !modHud, "reinitialized inspector");
                game = (Button) field(screen, "hideHudButton").get(screen);
                game.onPress(); choices(screen, !hud, !modHud, "vanilla HUD toggle");
                TrackEffect edited = effect(screen);
                Method apply = TrackEffectEditScreen.class.getDeclaredMethod("applyEffect", TrackEffect.class);
                apply.setAccessible(true); apply.invoke(screen, seed);
                choices(screen, hud, modHud, "reloaded seed");
                apply.invoke(screen, edited); choices(screen, !hud, !modHud, "reloaded edited effect");
                for (int i = 0; i < 5; i++) screen.tick();
                if (dj) {
                    check(host.preview != null, "DJ receives unsaved preview");
                    check(host.preview.settings().hideHud() == !hud
                            && host.preview.settings().hideBlockyMusicHud() == !modHud,
                            "DJ preview retains both independent choices");
                }
            }
        sliderPairing();
        System.out.println("FX inspector checks passed: " + checks);
    }

    /**
     * Every numeric field must offer a slider next to its box, and the two must stay in step in
     * both directions, including at the exact ends of the range the data model accepts.
     */
    private static void sliderPairing() throws Exception {
        TrackEffect seed = new TrackEffect(TrackEffect.Type.OLD_TV, 10, 80, 1);
        var screen = new TrackEffectEditScreen(new Host(false), seed, seed, "point");
        screen.width = 854; screen.height = 480;
        Field font = Screen.class.getDeclaredField("font"); font.setAccessible(true); font.set(screen, FONT);
        screen.init();
        Field slidersField = TrackEffectEditScreen.class.getDeclaredField("sliders");
        slidersField.setAccessible(true);
        Map<?, ?> sliders = (Map<?, ?>) slidersField.get(screen);
        check(sliders.size() == 49, "every numeric FX box owns a slider, got " + sliders.size());
        check(!sliders.containsKey(field(screen, "textBox").get(screen)), "the text field stays a plain box");
        Field contentField = TrackEffectEditScreen.class.getDeclaredField("contentWidgets");
        contentField.setAccessible(true);
        List<?> content = (List<?>) contentField.get(screen);
        Field valueField = AbstractSliderButton.class.getDeclaredField("value");
        valueField.setAccessible(true);
        Method applyValue = AbstractSliderButton.class.getDeclaredMethod("applyValue");
        applyValue.setAccessible(true);
        for (Map.Entry<?, ?> entry : sliders.entrySet()) {
            EditBox box = (EditBox) entry.getKey();
            Object slider = entry.getValue();
            check(content.contains(slider), "slider joins the scroll/focus content list");
            Field rangeField = slider.getClass().getDeclaredField("range");
            rangeField.setAccessible(true);
            FxRange range = (FxRange) rangeField.get(slider);
            // Dragging the handle writes the value the box readers already understand.
            for (double fraction : new double[]{0.0D, 0.5D, 1.0D}) {
                valueField.set(slider, fraction);
                applyValue.invoke(slider);
                float expected = range.fromFraction(fraction);
                float shown = Float.parseFloat(box.getValue().trim().replace(',', '.'));
                check(Math.abs(shown - expected) <= 0.011F,
                        "slider drag writes its own value into the box: " + range + " at " + fraction);
                // Re-reading that same text must leave the handle exactly where it is.
                box.setValue(box.getValue());
                float back = range.fromFraction((Double) valueField.get(slider));
                check(Math.abs(back - expected) <= 0.011F,
                        "box text returns the handle to the same value: " + range + " at " + fraction);
            }
            // Typing either end of the accepted range must park the handle at that end.
            for (float end : new float[]{range.minimum(), range.maximum()}) {
                box.setValue(String.format(Locale.ROOT, "%.4f", end));
                float back = range.fromFraction((Double) valueField.get(slider));
                check(Math.abs(back - end) <= 0.011F, "typed range end moves the handle there: " + range);
            }
        }
    }

    private static void choices(TrackEffectEditScreen screen, boolean hud, boolean modHud, String context) throws Exception {
        TrackEffect effect = effect(screen);
        check(effect != null, context + " builds a valid effect");
        check(effect.settings().hideHud() == hud, context + " vanilla HUD");
        check(effect.settings().hideBlockyMusicHud() == modHud, context + " Blocky Music HUD");
    }
    private static TrackEffect effect(TrackEffectEditScreen screen) throws Exception {
        Method method = TrackEffectEditScreen.class.getDeclaredMethod("buildEffect", boolean.class);
        method.setAccessible(true); return (TrackEffect) method.invoke(screen, true);
    }
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void call(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object);
    }
    private static void check(boolean success, String reason) {
        checks++; if (!success) throw new AssertionError(reason);
    }
    private static final class Host implements TrackEffectEditHost {
        private final boolean dj;
        private TrackEffect preview;
        private Host(boolean dj) { this.dj = dj; }
        @Override public Screen parentScreen() { return null; }
        @Override public Path effectPreviewAudioPath() { return null; }
        @Override public boolean managesPreviewAudio() { return false; }
        @Override public boolean supportsLivePreview() { return dj; }
        @Override public boolean showBrushActions() { return !dj; }
        @Override public boolean showTimelineControls() { return !dj; }
        @Override public boolean persistTemplateOnClose() { return false; }
        @Override public void saveEffect(TrackEffect original, TrackEffect edited, String mode) { }
        @Override public void previewEffect(TrackEffect original, TrackEffect edited, String mode) { preview = edited; }
    }
}
