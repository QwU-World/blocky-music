import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ru.gora.musicbattle.client.ClientBattleSettings;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.data.NoteAppearance;

/**
 * Executes production drawNote while isolating only its clock, localization, and GPU sinks.
 *
 * <p>What mirrors a battle must look like the battle: the note and the hit flash take the player's first lane colour.
 * The rest is the toolkit's: the hit line was the first interface's cyan (0xFF52D6EC) until the UI toolkit moved it to
 * {@code Palette.TEXT_2}, and the words went from their own mint and amber to {@code Palette.OK} and {@code Palette.WARN}
 * at the same time, on purpose.
 */
public final class CalibrationColorRenderChecks {
    /** The hit line at rest: the toolkit's secondary text tone, no longer the first interface's cyan. */
    private static final int LINE = Palette.TEXT_2;

    private static double elapsedMs;

    public static double elapsed(Object ignoredMetronome) {
        return elapsedMs;
    }

    public static Component tr(String key, Object... ignoredArgs) {
        return Component.literal(key);
    }

    /**
     * Stands in for the screen's drawFeedback: the judgement word is drawn in the toolkit's TrueType face, which needs a
     * running client, so the probe hands the word and its colour here instead, exactly as drawNote chose them.
     */
    public static void drawFeedback(GuiGraphics graphics, Component word, float centerX, float y, int colour) {
        ((Capture) graphics).labels.add(new Label(word.getString(), colour));
    }

    public static void main(String[] args) throws Exception {
        Class<?> probeType = isolateDrawNote();
        Object probe = probeType.getConstructor().newInstance();
        probeType.getField("controlWidth").setInt(probe, 220);
        probeType.getField("width").setInt(probe, 600);
        probeType.getField("height").setInt(probe, 400);
        probeType.getField("stageFoot").setInt(probe, 90);
        probeType.getField("metronome").set(probe, new Object());
        probeType.getField("lastTapElapsedMs").setDouble(probe, -1000.0);
        probeType.getField("lastError").setDouble(probe, Double.NaN);

        setSetting("loaded", true);
        setSetting("musicVolume", 0.0F); // Isolate Minecraft Options; rendering is unaffected.
        setSetting("inputOffsetMs", 0);

        int blue = 0xFF1267A4;
        Capture blueFrame = draw(probeType, probe, 700.0,
                appearance(blue, 0xFFAA3311, 0xFF22BB55, 0xFF6633CC), 0xFF09121B);
        assertApproachingNote(blueFrame, blue, 0xFF71C6FF, "first palette");

        int orange = 0xFFE05A20;
        Capture orangeFrame = draw(probeType, probe, 700.0,
                appearance(orange, 0xFF203040, 0xFF506070, 0xFF8090A0), 0xFFDEADBE);
        assertApproachingNote(orangeFrame, orange, 0xFFFFB97F, "second palette");

        Capture unrelatedChanges = draw(probeType, probe, 700.0,
                appearance(orange, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00), 0xFF35C17E);
        assertApproachingNote(unrelatedChanges, orange, 0xFFFFB97F, "unchanged first lane");
        require(noteColor(unrelatedChanges) == noteColor(orangeFrame),
                "Changing other lanes or the panel color changed the calibration note");

        int flashLane = 0xFF284C70;
        Capture flash = draw(probeType, probe, 1300.0,
                appearance(flashLane, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF), 0xFFFF00FF);
        requireFill(flash, 254, 289, 582, 293, 0xFFFFFFFF,
                "Hit reference line did not retain its opaque neutral white");
        requireFill(flash, 394, 42, 442, 310, 0x40284C70,
                "Lane flash did not retain its 85*burst alpha with lane-zero RGB");
        requireFill(flash, 392, 280, 444, 301, 0x9687ABCF,
                "Expanding flash did not retain its 200*burst alpha and gameplay highlight");
        requireFill(flash, 400, 287, 436, 295, 0xBFFFFFFF,
                "Hit core did not retain its 255*burst neutral white");
        require(countColor(flash, 0x96FFFFFF) == 8,
                "Hit particles did not retain eight neutral-white 200*burst fills");

        assertFeedback(probeType, probe, 0.0, "hit", Palette.OK);
        assertFeedback(probeType, probe, -80.0, "early", Palette.WARN);
        assertFeedback(probeType, probe, 80.0, "late", Palette.WARN);
        require(LINE != 0xFF52D6EC && Palette.OK != 0xFF81F7BC && Palette.WARN != 0xFFFFCE79,
                "the calibration test is back on the first interface's cyan, mint or amber");

        // The stage gives up height to a long note under it: with a foot of 102 the frame ends 12 units higher.
        probeType.getField("stageFoot").setInt(probe, 102);
        Capture raised = draw(probeType, probe, 700.0,
                appearance(blue, 0xFFAA3311, 0xFF22BB55, 0xFF6633CC), 0xFF09121B);
        requireFill(raised, 254, 277, 582, 281, LINE, "the hit line does not follow the stage's foot");
        probeType.getField("stageFoot").setInt(probe, 90);

        System.out.println("PASS: production calibration drawNote follows current lane-zero color across two palettes");
        System.out.println("PASS: other lanes/panel are isolated; geometry, reference, flash alpha, whites, and feedback meanings are preserved");
        System.out.println("PASS: the hit line and the words are toolkit tokens (TEXT_2, OK, WARN); the line follows the stage's foot");
        System.out.println("NOT COVERED: real Minecraft font batching, sound clock, GPU integration, or live screen rendering");
    }

    private static Capture draw(Class<?> probeType, Object probe, double elapsed,
                                NoteAppearance appearance, int panelColor) throws Exception {
        elapsedMs = elapsed;
        setSetting("appearance", appearance);
        setSetting("panelColor", panelColor);
        Capture capture = Capture.create();
        probeType.getMethod("drawNote", GuiGraphics.class).invoke(probe, capture);
        return capture;
    }

    private static void assertApproachingNote(Capture capture, int laneColor, int highlightColor, String context) {
        requireFill(capture, 254, 289, 582, 293, LINE,
                context + ": pre-hit reference moved or changed color");
        requireFill(capture, 400, 166, 436, 174, laneColor,
                context + ": moving note does not use current lane zero");
        requireFill(capture, 402, 167, 434, 169, highlightColor,
                context + ": moving-note highlight does not lighten current lane zero");
    }

    private static void assertFeedback(Class<?> probeType, Object probe, double error,
                                       String expectedText, int expectedColor) throws Exception {
        probeType.getField("lastTapElapsedMs").setDouble(probe, 600.0);
        probeType.getField("lastError").setDouble(probe, error);
        Capture capture = draw(probeType, probe, 700.0,
                appearance(0xFF284C70, 0xFF101010, 0xFF202020, 0xFF303030), 0xFF445566);
        probeType.getField("lastTapElapsedMs").setDouble(probe, -1000.0);
        probeType.getField("lastError").setDouble(probe, Double.NaN);
        require(capture.labels.stream().anyMatch(label -> label.text.equals(expectedText)
                        && label.color == expectedColor),
                expectedText + " feedback meaning/color changed");
    }

    private static int noteColor(Capture capture) {
        return capture.fills.stream()
                .filter(fill -> fill.left == 400 && fill.top == 166 && fill.right == 436 && fill.bottom == 174)
                .findFirst().orElseThrow(() -> new AssertionError("Approaching note geometry is missing")).color;
    }

    private static long countColor(Capture capture, int color) {
        return capture.fills.stream().filter(fill -> fill.color == color).count();
    }

    private static NoteAppearance appearance(int first, int second, int third, int fourth) {
        return new NoteAppearance(NoteAppearance.BARS, List.of(first, second, third, fourth));
    }

    private static void requireFill(Capture capture, int left, int top, int right, int bottom,
                                    int color, String message) {
        Fill matching = capture.fills.stream()
                .filter(fill -> fill.left == left && fill.top == top && fill.right == right && fill.bottom == bottom)
                .reduce((earlier, later) -> later)
                .orElseThrow(() -> new AssertionError(message + ": geometry missing"));
        require(matching.color == color, message + ": expected " + hex(color) + ", got " + hex(matching.color));
    }

    private static void setSetting(String name, Object value) throws Exception {
        Field field = ClientBattleSettings.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Class<?> isolateDrawNote() throws Exception {
        String source = "ru/gora/musicbattle/client/MusicBattleCalibrationScreen";
        String screen = "net/minecraft/client/gui/screens/Screen";
        String probe = "CalibrationDrawNoteProbe";
        String test = "CalibrationColorRenderChecks";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, probe, null, "java/lang/Object", null);
        for (String[] field : new String[][]{
                {"controlWidth", "I"}, {"width", "I"}, {"height", "I"}, {"stageFoot", "I"},
                {"metronome", "Ljava/lang/Object;"}, {"lastTapElapsedMs", "D"}, {"lastError", "D"},
                {"font", "Lnet/minecraft/client/gui/Font;"},
                {"minecraft", "Lnet/minecraft/client/Minecraft;"}}) {
            writer.visitField(Opcodes.ACC_PUBLIC, field[0], field[1], null, null).visitEnd();
        }
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        try (var input = CalibrationColorRenderChecks.class.getClassLoader().getResourceAsStream(source + ".class")) {
            if (input == null) throw new AssertionError("Compiled MusicBattleCalibrationScreen is missing");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    boolean drawNote = name.equals("drawNote")
                            && descriptor.equals("(Lnet/minecraft/client/gui/GuiGraphics;)V");
                    boolean lighten = name.equals("lighten") && descriptor.equals("(I)I");
                    if (!drawNote && !lighten) {
                        return null;
                    }
                    int outputAccess = Opcodes.ACC_PUBLIC | (lighten ? Opcodes.ACC_STATIC : 0);
                    MethodVisitor output = writer.visitMethod(outputAccess, name, descriptor, null, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, output) {
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if ((owner.equals(source) || owner.equals(screen)) && isProbeField(name)) {
                                String mappedDescriptor = name.equals("metronome") ? "Ljava/lang/Object;" : descriptor;
                                super.visitFieldInsn(opcode, probe, name, mappedDescriptor);
                            } else {
                                super.visitFieldInsn(opcode, owner, name, descriptor);
                            }
                        }

                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String descriptor, boolean isInterface) {
                            if (owner.equals("ru/gora/musicbattle/client/CalibrationSoundInstance")
                                    && name.equals("elapsedMs") && descriptor.equals("()D")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, test, "elapsed", "(Ljava/lang/Object;)D", false);
                            } else if (owner.equals(source) && name.equals("tr")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, test, "tr", descriptor, false);
                            } else if (owner.equals(source) && name.equals("drawFeedback")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, test, "drawFeedback", descriptor, false);
                            } else if (owner.equals(source) && name.equals("lighten")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, probe, name, descriptor, false);
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        writer.visitEnd();
        return MethodHandles.lookup().defineClass(writer.toByteArray());
    }

    private static boolean isProbeField(String name) {
        return name.equals("controlWidth") || name.equals("width") || name.equals("height") || name.equals("stageFoot")
                || name.equals("metronome") || name.equals("lastTapElapsedMs") || name.equals("lastError")
                || name.equals("font") || name.equals("minecraft");
    }

    private static String hex(int value) {
        return String.format("0x%08X", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fill(int left, int top, int right, int bottom, int color) { }
    private record Label(String text, int color) { }

    private static final class Capture extends GuiGraphics {
        private List<Fill> fills;
        private List<Label> labels;

        private Capture() {
            super(null, (MultiBufferSource.BufferSource) null);
        }

        static Capture create() throws Exception {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Capture capture = (Capture) unsafeType.getMethod("allocateInstance", Class.class)
                    .invoke(field.get(null), Capture.class);
            capture.fills = new ArrayList<>();
            capture.labels = new ArrayList<>();
            return capture;
        }

        @Override public void fill(int left, int top, int right, int bottom, int color) {
            fills.add(new Fill(left, top, right, bottom, color));
        }

        @Override public void enableScissor(int left, int top, int right, int bottom) { }
        @Override public void disableScissor() { }

        // No text sinks: drawNote's only words go through drawFeedback, and its status line was moved out of it with
        // the rest of the chrome. A string drawn here again would reach the real GuiGraphics and fail this check.
    }
}
