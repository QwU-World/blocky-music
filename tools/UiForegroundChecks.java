import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;

/** Executes the production UI scope with a real PoseStack and hidden OpenGL depth buffer.
 * GuiGraphics/font batching and the full Minecraft screens still require in-game validation. */
public final class UiForegroundChecks {
    private static Method foreground;
    private static Method modal;
    private static Method tip;
    private static final int SIZE = 32;
    private static final int GREEN = 0x22CC55;
    private static final int MAGENTA = 0xDD22CC;

    public static void main(String[] args) throws Exception {
        require(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, 24);
        long window = GLFW.glfwCreateWindow(SIZE, SIZE, "Blocky Music UI check (hidden)", 0, 0);
        require(window != 0, "Hidden OpenGL context creation failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glViewport(0, 0, SIZE, SIZE);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            glOrtho(0, SIZE, 0, SIZE, -2000, 2000);
            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(true);
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            checkLegacyFailure();
            try {
                foreground = Class.forName("ru.gora.musicbattle.client.ClientUiForeground")
                        .getMethod("render", PoseStack.class, Runnable.class, Runnable.class);
                foreground.setAccessible(true);
            } catch (ClassNotFoundException missing) {
                throw new AssertionError("UI foreground scope is missing: preview FX and parent glyphs cover controls", missing);
            }
            checkForeground(400.0F, "editor controls over above-interface TEXT");
            checkForeground(0.03F, "DJ chooser over parent glyph depth");
            checkForeground(400.03F, "DJ chooser over above-interface FX glyphs");
            checkFailureRestoration(false);
            checkFailureRestoration(true);
            try {
                modal = foreground.getDeclaringClass()
                        .getMethod("renderModal", PoseStack.class, Runnable.class, Runnable.class);
                modal.setAccessible(true);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("Nested color picker needs a modal layer above the editor's +800 foreground", missing);
            }
            checkNestedModal();
            try {
                tip = foreground.getDeclaringClass().getMethod("renderTip", PoseStack.class, Runnable.class, Runnable.class);
                tip.setAccessible(true);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("Editor tips need a layer above the controls' shadowed glyphs (+800.03)", missing);
            }
            checkTipLayer();
            require(glGetError() == GL_NO_ERROR, "Unexpected OpenGL error");
            System.out.println("PASS: 10 checks; foreground/modal/tip pixels, parent batch boundary, pose restoration, renderer/flush exceptions");
            System.out.println("NOT COVERED: real GuiGraphics/font batches, screen integration, and in-game visual QA");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void checkLegacyFailure() {
        clear();
        quad(400, MAGENTA);
        quad(0, GREEN);
        require(pixel() == MAGENTA, "Legacy elevated TEXT fixture did not reproduce UI occlusion");
        clear();
        quad(0.03F, MAGENTA);
        quad(0, GREEN);
        require(pixel() == MAGENTA, "Legacy modal fixture did not reproduce parent glyph occlusion");
        System.out.println("REPRODUCED: preview TEXT and parent glyph depth defeat later unraised UI fills");
    }

    private static void checkForeground(float parentDepth, String label) throws Exception {
        clear();
        PoseStack poses = new PoseStack();
        Matrix4f initial = new Matrix4f(poses.last().pose());
        List<Runnable> batches = new ArrayList<>();
        batches.add(() -> quad(parentDepth, MAGENTA));
        Runnable flush = () -> {
            for (Runnable batch : List.copyOf(batches)) batch.run();
            batches.clear();
        };
        invoke(poses, flush, () -> {
            require(batches.isEmpty(), "Parent batches must finish before foreground begins");
            float foregroundDepth = poses.last().pose().m32();
            batches.add(() -> quad(foregroundDepth, GREEN));
        });
        require(batches.isEmpty(), "Foreground batches must finish before returning to parent pose");
        require(poses.last().pose().equals(initial), "Foreground leaked the pose transform");
        require(poses.clear(), "Foreground leaked a pose stack frame");
        require(pixel() == GREEN, label + " is still occluded");
        require(glIsEnabled(GL_DEPTH_TEST), "Foreground disabled caller depth testing");
    }

    private static void checkFailureRestoration(boolean failFlush) throws Exception {
        PoseStack poses = new PoseStack();
        poses.translate(11, 13, 17);
        Matrix4f initial = new Matrix4f(poses.last().pose());
        RuntimeException expected = new RuntimeException("fixture renderer failure");
        int[] flushes = {0};
        Runnable flush = () -> {
            if (++flushes[0] == 2 && failFlush) throw expected;
        };
        boolean caught = false;
        try {
            invoke(poses, flush, () -> { if (!failFlush) throw expected; });
        } catch (RuntimeException actual) {
            require(actual == expected, "Foreground replaced the original error");
            caught = true;
        }
        require(caught, "Foreground swallowed an error");
        require(flushes[0] == 2, "Foreground did not flush after renderer failure");
        require(poses.last().pose().equals(initial) && poses.clear(), "Failed foreground leaked its pose");
    }

    private static void checkNestedModal() throws Exception {
        clear();
        PoseStack poses = new PoseStack();
        Matrix4f initial = new Matrix4f(poses.last().pose());
        List<Runnable> batches = new ArrayList<>();
        Runnable flush = () -> {
            for (Runnable batch : List.copyOf(batches)) batch.run();
            batches.clear();
        };
        // ExactColorPickerScreen first renders its parent inspector, then its modal.
        invoke(poses, flush, () -> {
            float parentTextDepth = poses.last().pose().m32() + 0.03F;
            batches.add(() -> quad(parentTextDepth, MAGENTA));
        });
        quad(400, GREEN);
        require(pixel() == MAGENTA, "Legacy +400 picker must be occluded by +800 parent text");
        modal.invoke(null, poses, flush, (Runnable) () -> {
            require(batches.isEmpty(), "Parent batches leaked into modal");
            float modalDepth = poses.last().pose().m32();
            batches.add(() -> quad(modalDepth, GREEN));
        });
        require(pixel() == GREEN, "Nested color picker remains behind the parent inspector");
        require(batches.isEmpty(), "Modal batches leaked beyond its pose scope");
        require(poses.last().pose().equals(initial) && poses.clear(), "Modal leaked its pose");
        require(glIsEnabled(GL_DEPTH_TEST), "Modal disabled caller depth testing");
    }

    /**
     * The FX editor's tip card went up in the controls' own layer (+800), and vanilla draws an
     * EditBox's text with a drop shadow, the text itself 0.03 forward (Font.SHADOW_OFFSET), so a number box's value
     * printed through the card. The tip layer lies over that glyph, and a modal drawn over the editor still lies over the
     * tip's own glyphs.
     */
    private static void checkTipLayer() throws Exception {
        clear();
        PoseStack legacy = new PoseStack();
        List<Runnable> batches = new ArrayList<>();
        Runnable flush = () -> {
            for (Runnable batch : List.copyOf(batches)) batch.run();
            batches.clear();
        };
        // A number box's shadowed value in the controls' layer, then the card in that same layer: the old FX editor.
        invoke(legacy, flush, () -> {
            float textDepth = legacy.last().pose().m32() + 0.03F;
            batches.add(() -> quad(textDepth, MAGENTA));
        });
        invoke(legacy, flush, () -> {
            float cardDepth = legacy.last().pose().m32();
            batches.add(() -> quad(cardDepth, GREEN));
        });
        require(pixel() == MAGENTA, "Legacy fixture did not reproduce a shadowed control value printing through its tip");
        System.out.println("REPRODUCED: a tip in the controls' layer lies under their shadowed glyphs");

        clear();
        PoseStack poses = new PoseStack();
        Matrix4f initial = new Matrix4f(poses.last().pose());
        invoke(poses, flush, () -> {
            float textDepth = poses.last().pose().m32() + 0.03F;
            batches.add(() -> quad(textDepth, MAGENTA));
        });
        float[] tipText = {0F};
        tip.invoke(null, poses, flush, (Runnable) () -> {
            require(batches.isEmpty(), "Control batches leaked into the tip layer");
            float cardDepth = poses.last().pose().m32();
            tipText[0] = cardDepth + 0.03F;
            batches.add(() -> quad(cardDepth, GREEN));
        });
        require(pixel() == GREEN, "A tip still lies under the controls' shadowed glyphs");
        require(batches.isEmpty() && poses.last().pose().equals(initial) && poses.clear(), "The tip layer leaked its pose or batches");

        clear();
        quad(tipText[0], MAGENTA);
        modal.invoke(null, poses, flush, (Runnable) () -> {
            float modalDepth = poses.last().pose().m32();
            batches.add(() -> quad(modalDepth, GREEN));
        });
        require(pixel() == GREEN, "A dialog over the editor no longer covers the editor's tip");
        require(glIsEnabled(GL_DEPTH_TEST), "The tip layer disabled caller depth testing");
    }

    private static void invoke(PoseStack poses, Runnable flush, Runnable draw) throws Exception {
        try {
            foreground.invoke(null, poses, flush, draw);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException error) throw error;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw wrapped;
        }
    }

    private static void clear() {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private static void quad(float z, int color) {
        glColor3f(((color >>> 16) & 255) / 255F, ((color >>> 8) & 255) / 255F, (color & 255) / 255F);
        glBegin(GL_QUADS);
        glVertex3f(0, 0, z);
        glVertex3f(SIZE, 0, z);
        glVertex3f(SIZE, SIZE, z);
        glVertex3f(0, SIZE, z);
        glEnd();
    }

    private static int pixel() {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return (pixel.get(0) & 255) << 16 | (pixel.get(1) & 255) << 8 | pixel.get(2) & 255;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
