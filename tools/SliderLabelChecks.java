package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Slider;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * Every slider of the mod draws through the toolkit's Slider, and the slider's white handle used to stop on its own
 * label: at the default 73% on a 320x240 window "Bar opacity: 73%" lost its "%" and the Russian bar opacity a letter,
 * and on the DJ panel the labels sat under their handles. This measures the slider the game draws now (the production
 * Slider.layout and Slider.label, compiled from the tree) against the ink of every slider label of the mod, in English
 * and Russian, glyph by glyph as Minecraft's FreeType provider places it at GUI scales 1 to 9 (GlyphInk):
 *
 * <ul>
 *   <li>at every value from 0 to 100% in steps of 0.5%, in every field from 24 to 420 units wide and 14 to 20 tall (the
 *       DJ panel's rows on windows from 240 units tall up to the settings' and the playlist's 20), the knob's box
 *       touches no glyph's ink box;</li>
 *   <li>every glyph lies inside its field, the name and the value never meet, and the value is cut only when it is
 *       wider than the whole line by itself;</li>
 *   <li>the knob is still a handle: inside its field at every value, at least 4 units across with a label and the
 *       largest knob without one, clearly thicker than the track it runs on, and it travels the whole track;</li>
 *   <li>the knob is under the pointer: vanilla's AbstractSliderButton, which every slider of the mod extends, reads a
 *       click or a drag at x as the value (x - (left + 4)) / (width - 8), and the knob's centre stands exactly there at
 *       every value, while it keeps a unit clear of the field's edges at both ends. It used to travel 6 units in from the
 *       edges, up to 2 units off the pointer, so a click on the middle of the knob moved it;</li>
 *   <li>a slider without a label (calibration, speaker dialog, FX editor) keeps its knob and track in the middle of the
 *       field;</li>
 *   <li>what Slider.paint draws is what layout and label say: its own bytecode runs with its toolkit calls (WorkshopDraw,
 *       RoundedRect, TextStyles, HandCursor) recorded instead of drawn, for every label, field and GUI scale, and the
 *       knob it draws, its track, the end of the fill and the two halves of the label it queues are measured against
 *       them and against the ink of the words it queued. Checking only layout and label let a knob or a word moved in
 *       paint itself, back over the text, pass;</li>
 *   <li>the five screens that have a slider still draw it through Slider.paint.</li>
 * </ul>
 * It also measures the old drawing - the label centred on the track, a 5-unit handle the field's height less 4 - and
 * fails if that no longer hides a glyph of "Bar opacity: 73%" on the 142-unit settings slider of a 320x240 window, so
 * that it keeps measuring what it was written for.
 */
public final class SliderLabelChecks {
    private static final int[] LABELLED_HEIGHTS = {14, 16, 18, 20};
    /** The settings screen's two-to-a-row slider on a 320-unit window: (296 - 6 - 6) / 2. */
    private static final int SETTINGS_HALF_AT_320 = 142;
    /** Where AbstractSliderButton.setValueFromMouse puts the pointer for a value: in 4 from each edge of the field. */
    private static final float VANILLA_POINTER_INSET = 4F;
    /** The values paint is recorded at, one per field, in turn; every one of them with every label at every scale. */
    private static final double[] PAINTED = {0D, 0.25D, 0.5D, 0.73D, 1D};
    private static final float EPSILON = 1E-3F;
    private static int checks;
    private static long knobChecks;
    private static long paints;
    private static float worstDrift;

    public static void main(String[] args) throws Throwable {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Map<String, List<String>> labels = new LinkedHashMap<>();
        labels.put("en", labels(root.resolve("src/main/resources/assets/music_battle/lang/en_us.json"), false));
        labels.put("ru", labels(root.resolve("src/main/resources/assets/music_battle/lang/ru_ru.json"), true));
        callers(root);
        MethodHandle paint = paintProbe();
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GlyphInk ink = new GlyphInk(fonts, tier);
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            measuring = measurer;
            for (Map.Entry<String, List<String>> language : labels.entrySet()) {
                for (String label : language.getValue()) {
                    for (int height : LABELLED_HEIGHTS) {
                        for (int width = 24; width <= 420; width++) {
                            labelled(ink, measurer, paint, label, width, height, language.getKey());
                        }
                    }
                }
            }
            oldDrawingHidGlyphs(ink, measurer, labels);
        }
        unlabelled(paint);
        underThePointer();
        System.out.println("Slider labels PASS: " + checks + " checks and " + knobChecks + " knob positions against glyph ink, "
                + labels.get("en").size() + " English and " + labels.get("ru").size() + " Russian labels, GUI scales 1-9; "
                + paints + " runs of Slider.paint recorded; the knob is at most " + worstDrift
                + " units off vanilla's pointer");
        System.out.println("NOT COVERED: fields under 14 units (the DJ panel's rows on windows under 220 tall), the live GPU");
    }

    private static void labelled(GlyphInk ink, GameTextMeasurer measurer, MethodHandle paint, String text, int width,
                                 int height, String language) throws Throwable {
        Rect field = new Rect(100, 50, width, height);
        Slider.Layout layout = Slider.layout(field, true);
        Slider.Label label = Slider.label(measurer, text, layout);
        String where = language + " '" + text + "' in " + width + "x" + height + " at GUI scale " + ink.tier;
        List<GlyphInk.Box> name = ink.line(label.name(), TextStyle.CAPTION, label.nameX(), label.y());
        List<GlyphInk.Box> value = ink.line(label.value(), TextStyle.CAPTION, label.valueX(), label.y());
        List<GlyphInk.Box> glyphs = new ArrayList<>(name);
        glyphs.addAll(value);
        for (GlyphInk.Box glyph : glyphs) {
            require(glyph.left() >= field.x() - 1E-3F && glyph.right() <= field.right() + 1E-3F
                    && glyph.top() >= field.y() - 1E-3F && glyph.bottom() <= field.bottom() + 1E-3F,
                    "a glyph of the label leaves its field: " + where + " " + glyph);
        }
        float[] nameInk = GlyphInk.bounds(name);
        float[] valueInk = GlyphInk.bounds(value);
        if (nameInk != null && valueInk != null) {
            require(nameInk[2] < valueInk[0], "the name runs into the value: " + where);
        }
        // The value is whole while it fits the line by itself, and the name while both fit with the gap between them.
        String[] parts = split(text);
        float room = layout.labelRight() - layout.labelLeft();
        float valueWidth = TextFit.width(measurer, parts[1], TextStyle.CAPTION);
        if (valueWidth <= room) {
            require(label.value().equals(parts[1]), "the value is cut though it fits: " + where + " -> " + label.value());
        }
        if (valueWidth <= room && TextFit.width(measurer, parts[0], TextStyle.CAPTION) <= room - valueWidth - Slider.NAME_GAP) {
            require(label.name().equals(parts[0]), "the name is cut though it fits: " + where + " -> " + label.name());
        }
        handle(layout, where, 4F);
        // The knob against every glyph at every value. Only glyphs that reach the knob's rows can meet it at all, but the
        // knob is still placed at each value and counted, so the count says what was measured.
        float knobTop = layout.knobY() - layout.knobRadius();
        float knobBottom = layout.knobY() + layout.knobRadius();
        List<GlyphInk.Box> reach = new ArrayList<>();
        for (GlyphInk.Box glyph : glyphs) {
            if (glyph.top() < knobBottom && knobTop < glyph.bottom()) {
                reach.add(glyph);
            }
        }
        for (int step = 0; step <= 200; step++) {
            double at = step / 200.0D;
            Rect knob = layout.knob(at);
            knobChecks++;
            for (GlyphInk.Box glyph : reach) {
                if (glyph.overlaps(knob.x(), knob.y(), knob.right(), knob.bottom())) {
                    throw new AssertionError("the knob at " + step / 2.0D + "% covers '" + new String(Character.toChars(glyph.codePoint()))
                            + "': " + where + " knob " + knob + " glyph " + glyph);
                }
            }
        }
        // What paint draws for this field: one value and one state in turn, so that every value and state meets every
        // label, field and scale.
        double at = PAINTED[(width + height) % PAINTED.length];
        int state = width % 3;
        painted(paint, ink, field, layout, label, text, at, state == 1, state != 2, where);
        checks++;
    }

    /** The knob reads as a handle, travels the whole track and stays under vanilla's pointer, inside its field. */
    private static void handle(Slider.Layout layout, String where, float smallest) {
        Rect field = layout.field();
        require(layout.knobRadius() * 2F >= smallest - 1E-4F, "the knob is smaller than " + smallest + " units: " + where);
        require(layout.knobRadius() * 2F >= layout.trackThickness() + 1F, "the knob does not stand out of its track: " + where);
        require(Math.abs(layout.knobX(0) - layout.trackLeft()) < 1E-4F && Math.abs(layout.knobX(1) - layout.trackRight()) < 1E-4F,
                "the knob does not travel the whole track: " + where);
        require(layout.track().x() >= field.x() && layout.track().right() <= field.right() + 1E-4F,
                "the track leaves its field: " + where);
        for (double at : new double[]{0D, 0.5D, 0.73D, 1D}) {
            Rect knob = layout.knob(at);
            require(knob.x() >= field.x() - 1E-4F && knob.right() <= field.right() + 1E-4F && knob.y() >= field.y() - 1E-4F
                    && knob.bottom() <= field.bottom() + 1E-4F, "the knob leaves its field at " + at + ": " + where);
        }
        require(layout.knob(0).x() >= field.x() + Slider.KNOB_EDGE - 1E-4F
                        && layout.knob(1).right() <= field.right() - Slider.KNOB_EDGE + 1E-4F,
                "the knob at an end of its travel comes nearer the field's edge than " + Slider.KNOB_EDGE + ": " + where);
    }

    /**
     * A click or a drag at the knob's centre gives back the value the knob is drawn at, in every field from 24 to 420
     * units wide and 14 to 20 tall, with a label and without, at every value in steps of 0.5%. Where the knob stands
     * depends on the field alone, not on the words in it.
     */
    private static void underThePointer() {
        for (boolean labelled : new boolean[]{true, false}) {
            for (int height = 14; height <= 20; height++) {
                for (int width = 24; width <= 420; width++) {
                    Rect field = new Rect(100, 50, width, height);
                    Slider.Layout layout = Slider.layout(field, labelled);
                    for (int step = 0; step <= 200; step++) {
                        double at = step / 200.0D;
                        float pointer = field.x() + VANILLA_POINTER_INSET + (float) at * (field.w() - VANILLA_POINTER_INSET * 2F);
                        float drift = Math.abs(layout.knobX(at) - pointer);
                        worstDrift = Math.max(worstDrift, drift);
                        require(drift <= EPSILON, "the knob at " + step / 2.0D + "% is " + drift + " units off the pointer"
                                + " vanilla reads that value from: " + width + "x" + height + (labelled ? "" : " without a label"));
                    }
                }
            }
        }
    }

    /** The calibration screen's and the speaker dialog's sliders have no label: knob and track in the field's middle. */
    private static void unlabelled(MethodHandle paint) throws Throwable {
        for (int height = 14; height <= 20; height++) {
            for (int width = 24; width <= 420; width++) {
                Rect field = new Rect(10, 20, width, height);
                Slider.Layout layout = Slider.layout(field, false);
                String where = width + "x" + height + " without a label";
                require(Math.abs(layout.knobY() - field.centerY()) < 1E-4F, "the knob is off the middle: " + where);
                require(Float.isNaN(layout.labelY()), "a slider without a label keeps a label line: " + where);
                handle(layout, where, Slider.KNOB_MAX);
                for (double at : PAINTED) {
                    painted(paint, null, field, layout, null, "", at, width % 2 == 0, true, where);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ what paint draws

    /** One toolkit call paint made, with the numbers it gave: a shape's box, a circle's centre and radius, a text's place. */
    record Drawn(String kind, float x, float y, float w, float h, float radius, int colour, String text, TextStyle style) {
        float right() {
            return x + w;
        }

        float bottom() {
            return y + h;
        }
    }

    private static final List<Drawn> DRAWN = new ArrayList<>();
    private static final List<TextStyle> STYLED = new ArrayList<>();
    private static TextMeasurer measuring;

    /**
     * Runs Slider.paint for one field and measures what it drew: the field's panel, the track as layout gives it, the
     * fill ending under the knob's centre, one knob at layout's centre and radius (with the hover ring round it, never
     * past it), and with a label exactly the two halves label gives, in captions, on their places, with no glyph of them
     * under the knob it drew; without a label, no text at all. Every shape stays inside the field.
     */
    private static void painted(MethodHandle paint, GlyphInk ink, Rect field, Slider.Layout layout, Slider.Label label,
                                String text, double at, boolean hovered, boolean active, String where) throws Throwable {
        DRAWN.clear();
        STYLED.clear();
        paint.invoke((GuiGraphics) null, field, at, (Component) Component.literal(text), hovered, active);
        paints++;
        String state = " at " + at + (hovered ? ", hovered" : "") + (active ? "" : ", inactive") + ": " + where;
        List<Drawn> circles = DRAWN.stream().filter(drawn -> drawn.kind().equals("circle")).toList();
        require(circles.size() == 1, "paint draws " + circles.size() + " knobs" + state);
        Drawn knob = circles.getFirst();
        require(near(knob.x(), layout.knobX(at)) && near(knob.y(), layout.knobY()) && near(knob.radius(), layout.knobRadius()),
                "paint draws the knob at (" + knob.x() + ", " + knob.y() + ") r " + knob.radius() + " where layout has ("
                        + layout.knobX(at) + ", " + layout.knobY() + ") r " + layout.knobRadius() + state);
        // In this order: the field, the track over it, and the fill over the track, which at 100% covers all of it.
        Rect track = layout.track();
        List<Drawn> panels = DRAWN.stream().filter(drawn -> drawn.kind().equals("panel")).toList();
        float filled = layout.knobX(at) - track.x();
        require(panels.size() == (filled > 0.5F ? 3 : 2), "paint draws " + panels.size() + " panels for a knob " + filled
                + " along its track, not the field, the track and the fill" + state);
        require(same(panels.get(0), field), "paint's first panel is not the whole field: " + panels.get(0) + state);
        require(same(panels.get(1), track), "paint draws the track at " + panels.get(1) + " where layout has " + track + state);
        if (panels.size() == 3) {
            Drawn fill = panels.get(2);
            require(near(fill.x(), track.x()) && near(fill.y(), track.y()) && near(fill.h(), track.h())
                            && near(fill.right(), Math.max(track.x() + track.h(), layout.knobX(at))),
                    "the fill does not run along the track to the knob's centre: " + fill + state);
        }
        for (Drawn ring : DRAWN.stream().filter(drawn -> drawn.kind().equals("stroke")).toList()) {
            require(near(ring.x() + ring.w() * 0.5F, knob.x()) && near(ring.y() + ring.h() * 0.5F, knob.y())
                            && ring.w() <= knob.radius() * 2F + EPSILON, "the hover ring is not round the knob: " + ring + state);
        }
        for (Drawn drawn : DRAWN) {
            if (drawn.kind().equals("text")) {
                continue;
            }
            float left = drawn.kind().equals("circle") ? drawn.x() - drawn.radius() : drawn.x();
            float top = drawn.kind().equals("circle") ? drawn.y() - drawn.radius() : drawn.y();
            float right = drawn.kind().equals("circle") ? drawn.x() + drawn.radius() : drawn.right();
            float bottom = drawn.kind().equals("circle") ? drawn.y() + drawn.radius() : drawn.bottom();
            require(left >= field.x() - EPSILON && right <= field.right() + EPSILON && top >= field.y() - EPSILON
                    && bottom <= field.bottom() + EPSILON, "paint draws a " + drawn.kind() + " out of the field" + state);
        }
        List<Drawn> texts = DRAWN.stream().filter(drawn -> drawn.kind().equals("text")).toList();
        if (label == null) {
            require(texts.isEmpty(), "paint writes " + texts + " on a slider without a label" + state);
            return;
        }
        require(texts.size() == 2, "paint writes " + texts.size() + " texts, not the name and the value" + state);
        Drawn name = texts.get(0);
        Drawn value = texts.get(1);
        require(name.text().equals(label.name()) && near(name.x(), label.nameX()) && near(name.y(), label.y()),
                "paint writes the name '" + name.text() + "' at (" + name.x() + ", " + name.y() + ") where label has '"
                        + label.name() + "' at (" + label.nameX() + ", " + label.y() + ")" + state);
        require(value.text().equals(label.value()) && near(value.x(), label.valueX()) && near(value.y(), label.y()),
                "paint writes the value '" + value.text() + "' at (" + value.x() + ", " + value.y() + ") where label has '"
                        + label.value() + "' at (" + label.valueX() + ", " + label.y() + ")" + state);
        require(name.style() == TextStyle.CAPTION && value.style() == TextStyle.CAPTION,
                "paint writes the label in another face than the captions label measures it in" + state);
        // And what paint drew, measured as drawn: the ink of the words it queued against the knob it drew.
        for (Drawn word : texts) {
            for (GlyphInk.Box glyph : ink.line(word.text(), word.style(), word.x(), word.y())) {
                require(!glyph.overlaps(knob.x() - knob.radius(), knob.y() - knob.radius(), knob.x() + knob.radius(),
                        knob.y() + knob.radius()), "the knob paint drew covers '"
                        + new String(Character.toChars(glyph.codePoint())) + "' of the words it wrote" + state);
            }
        }
    }

    private static boolean same(Drawn drawn, Rect rect) {
        return near(drawn.x(), rect.x()) && near(drawn.y(), rect.y()) && near(drawn.w(), rect.w()) && near(drawn.h(), rect.h());
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPSILON;
    }

    // Slider.paint's toolkit calls land here in the probe: they record what they were given instead of drawing it.

    static void panel(GuiGraphics graphics, Rect rect, float radius, int colour) {
        DRAWN.add(new Drawn("panel", rect.x(), rect.y(), rect.w(), rect.h(), radius, colour, null, null));
    }

    static void outline(GuiGraphics graphics, Rect rect, float radius, float width, int colour) {
        DRAWN.add(new Drawn("outline", rect.x(), rect.y(), rect.w(), rect.h(), radius, colour, null, null));
    }

    static void circle(GuiGraphics graphics, float centerX, float centerY, float radius, int colour) {
        DRAWN.add(new Drawn("circle", centerX, centerY, 0F, 0F, radius, colour, null, null));
    }

    static void stroke(GuiGraphics graphics, float x, float y, float w, float h, float radius, float width, int colour) {
        DRAWN.add(new Drawn("stroke", x, y, w, h, radius, colour, null, null));
    }

    static void fill(GuiGraphics graphics, float x, float y, float w, float h, float radius, int colour) {
        DRAWN.add(new Drawn("panel", x, y, w, h, radius, colour, null, null));
    }

    static void queue(GuiGraphics graphics, FormattedCharSequence text, float x, float y, int colour) {
        StringBuilder written = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            written.appendCodePoint(codePoint);
            return true;
        });
        TextStyle style = STYLED.isEmpty() ? null : STYLED.removeFirst();
        DRAWN.add(new Drawn("text", x, y, 0F, 0F, 0F, colour, written.toString(), style));
    }

    static void queue(GuiGraphics graphics, Component text, float x, float y, int colour) {
        queue(graphics, FormattedCharSequence.forward(text.getString(), Style.EMPTY), x, y, colour);
    }

    static MutableComponent text(String text, TextStyle style) {
        STYLED.add(style);
        return Component.literal(text == null ? "" : text);
    }

    /** In place of MutableComponent.getVisualOrderText, which asks the game's Language for the bidi order. */
    static FormattedCharSequence visualOrder(MutableComponent text) {
        return FormattedCharSequence.forward(text.getString(), Style.EMPTY);
    }

    static TextMeasurer measurer() {
        return measuring;
    }

    static void request(HandCursor.Kind kind) {
    }

    /**
     * Slider.paint as a class of this package, its bytecode as compiled from the tree, with every call it makes to the
     * toolkit's drawing (WorkshopDraw, RoundedRect, TextStyles, HandCursor) sent to the recorders above. A call to any
     * other method of those classes fails the check, so nothing paint draws goes unrecorded.
     */
    private static MethodHandle paintProbe() throws Exception {
        String slider = "ru/gora/musicbattle/client/ui/Slider";
        String probe = "ru/gora/musicbattle/client/SliderPaintProbe";
        String recorder = "ru/gora/musicbattle/client/SliderLabelChecks";
        List<String> recorded = List.of("ru/gora/musicbattle/client/workshop/WorkshopDraw.panel",
                "ru/gora/musicbattle/client/workshop/WorkshopDraw.outline", "ru/gora/musicbattle/client/ui/RoundedRect.circle",
                "ru/gora/musicbattle/client/ui/RoundedRect.stroke", "ru/gora/musicbattle/client/ui/RoundedRect.fill",
                "ru/gora/musicbattle/client/ui/TextStyles.queue", "ru/gora/musicbattle/client/ui/TextStyles.text",
                "ru/gora/musicbattle/client/ui/TextStyles.measurer", "ru/gora/musicbattle/client/ui/HandCursor.request");
        List<String> drawing = List.of("ru/gora/musicbattle/client/workshop/WorkshopDraw", "ru/gora/musicbattle/client/ui/RoundedRect",
                "ru/gora/musicbattle/client/ui/TextStyles", "ru/gora/musicbattle/client/ui/HandCursor",
                "ru/gora/musicbattle/client/ui/UiIcons", "ru/gora/musicbattle/client/ui/Ring", "ru/gora/musicbattle/client/ui/UiSdf");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, probe, null, "java/lang/Object", null);
        boolean[] found = new boolean[1];
        try (InputStream input = SliderLabelChecks.class.getClassLoader().getResourceAsStream(slider + ".class")) {
            require(input != null, "the compiled Slider is missing");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    if (!name.equals("paint") || !descriptor.equals("(Lnet/minecraft/client/gui/GuiGraphics;"
                            + "Lru/gora/musicbattle/client/workshop/layout/Rect;DLnet/minecraft/network/chat/Component;ZZ)V")) {
                        return null;
                    }
                    found[0] = true;
                    MethodVisitor output = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null,
                            exceptions);
                    return new MethodVisitor(Opcodes.ASM9, output) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String type, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/network/chat/MutableComponent")
                                    && method.equals("getVisualOrderText")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, recorder, "visualOrder",
                                        "(Lnet/minecraft/network/chat/MutableComponent;)Lnet/minecraft/util/FormattedCharSequence;", false);
                            } else if (recorded.contains(owner + "." + method)) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, recorder, method, type, false);
                            } else if (drawing.contains(owner)) {
                                throw new AssertionError("Slider.paint calls " + owner + "." + method
                                        + ", which this check does not record: add it to the recorders");
                            } else if (owner.equals(slider) && opcode == Opcodes.INVOKESTATIC && !method.equals("layout")
                                    && !method.equals("label")) {
                                throw new AssertionError("Slider.paint calls Slider." + method + ", which the probe cannot reach");
                            } else {
                                super.visitMethodInsn(opcode, owner, method, type, isInterface);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        require(found[0], "Slider.paint(GuiGraphics, Rect, double, Component, boolean, boolean) is missing");
        writer.visitEnd();
        Class<?> type = MethodHandles.lookup().defineClass(writer.toByteArray());
        return MethodHandles.lookup().findStatic(type, "paint", MethodType.methodType(void.class, GuiGraphics.class, Rect.class,
                double.class, Component.class, boolean.class, boolean.class));
    }

    // ------------------------------------------------------------------------------------------------ the old drawing

    /**
     * The old drawing, as it was until this change: the label cut to the field less 12 and centred on it, its cap line in
     * the field's middle, and a handle 5 units wide and the field's height less 4, at 5 + value x (width - 10).
     */
    private static void oldDrawingHidGlyphs(GlyphInk ink, GameTextMeasurer measurer, Map<String, List<String>> labels) {
        Rect field = new Rect(0, 0, SETTINGS_HALF_AT_320, 20);
        for (String text : new String[]{"Bar opacity: 73%", ru(labels, "Непрозрачность полос: 73%")}) {
            String shown = TextFit.ellipsize(measurer, text, TextStyle.CAPTION, field.w() - 12);
            float x = field.centerX() - measurer.width(shown, TextStyle.CAPTION) * 0.5F;
            float y = field.y() + (field.h() - TextStyle.CAPTION.capHeight()) / 2.0F;
            float handle = field.x() + 5 + 0.73F * (field.w() - 10);
            boolean hidden = false;
            for (GlyphInk.Box glyph : ink.line(shown, TextStyle.CAPTION, x, y)) {
                hidden |= glyph.overlaps(handle - 2.5F, field.y() + 2, handle + 2.5F, field.bottom() - 2);
            }
            require(hidden, "the old handle at 73% no longer hides a glyph of '" + text + "' at GUI scale " + ink.tier
                    + ", so this check no longer measures what it was written for");
        }
    }

    private static String ru(Map<String, List<String>> labels, String expected) {
        require(labels.get("ru").contains(expected), "the Russian bar opacity at 73% is not '" + expected + "' any more");
        return expected;
    }

    /**
     * The seven screens with a slider draw it through Slider.paint, the FX editor's and the beatmap editor's volume
     * included: a slider that draws itself puts its knob where vanilla does not read the pointer (the editor's volume
     * ran 3 units further in at both ends).
     */
    private static void callers(Path root) throws Exception {
        for (String screen : List.of("MusicBattleSettingsScreen", "MusicBattleCalibrationScreen", "SpeakerConfigScreen",
                "DjPlaylistScreen", "DjPanelScreen", "TrackEffectEditScreen", "TrackEditorScreen")) {
            String code = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/" + screen + ".java"),
                    StandardCharsets.UTF_8);
            require(code.contains("Slider.paint("), screen + " no longer draws its sliders through Slider.paint");
        }
    }

    /**
     * Every slider label of the mod with the narrowest and the widest values it shows: the settings' ten, the DJ panel's
     * thirteen and the playlist's cue, formatted as the game formats them (a decimal comma in Russian where the DJ panel
     * writes one).
     */
    private static List<String> labels(Path file, boolean russian) throws Exception {
        Map<String, String> strings = new HashMap<>();
        JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            strings.put(entry.getKey(), entry.getValue().getAsString());
        }
        String comma = russian ? "," : ".";
        Map<String, String[]> values = new LinkedHashMap<>();
        values.put("music_battle.settings.note_speed", new String[]{"0.50", "5.00", "20.00"});
        values.put("music_battle.settings.music_volume", new String[]{"0", "35", "100", "200"});
        values.put("music_battle.settings.panel_opacity", new String[]{"0", "100"});
        values.put("music_battle.settings.lane_opacity", new String[]{"0", "150"});
        values.put("music_battle.settings.world_dimming", new String[]{"0", "90"});
        values.put("music_battle.settings.stage_bar_opacity", new String[]{"0", "73", "100"});
        values.put("music_battle.settings.panel_width", new String[]{"0.45", "1.60"});
        values.put("music_battle.settings.panel_height", new String[]{"0.55", "1.60"});
        values.put("music_battle.settings.note_width", new String[]{"0.35", "2.20"});
        values.put("music_battle.settings.note_height", new String[]{"0.35", "2.20"});
        values.put("screen.music_battle.dj_panel.radius", new String[]{"2" + comma + "0", "64" + comma + "0"});
        values.put("screen.music_battle.dj_panel.volume", new String[]{"0", "200"});
        for (String band : new String[]{"eq_low", "eq_mid", "eq_high"}) {
            values.put("screen.music_battle.dj_panel." + band, new String[]{"+0" + comma + "0", "-12" + comma + "0", "+12" + comma + "0"});
        }
        for (String percent : new String[]{"reverb_wet", "reverb_room", "reverb_damping", "echo_wet", "echo_feedback"}) {
            values.put("screen.music_battle.dj_panel." + percent, new String[]{"0", "100"});
        }
        values.put("screen.music_battle.dj_panel.echo_time", new String[]{"40", "1000"});
        values.put("screen.music_battle.dj_panel.low_pass_cutoff", new String[]{"200", "20000"});
        values.put("screen.music_battle.dj_panel.timeline", new String[]{"00:00", "59:59"});
        values.put("screen.music_battle.dj_playlist.cue", new String[]{"00:00.00", "59:59.95"});
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : values.entrySet()) {
            String template = strings.get(entry.getKey());
            require(template != null, "missing language key " + entry.getKey());
            require(template.contains(": "), entry.getKey() + " is no longer written 'name: value', which Slider.label splits at");
            for (String value : entry.getValue()) {
                labels.add(template.replace("%%", "\u0000").replace("%s", value).replace("\u0000", "%"));
            }
        }
        return labels;
    }

    /** The name and the value as Slider.label splits them. */
    private static String[] split(String text) {
        int split = text.lastIndexOf(": ");
        return split > 0 ? new String[]{text.substring(0, split).strip(), text.substring(split + 2).strip()} : new String[]{text, ""};
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
