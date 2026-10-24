import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Hidden OpenGL contract fixture for the real VIGNETTE/FISH_EYE/vanilla monitor shaders.
 * Blend/resolve calls below model the intended contract; this does not execute the Java
 * compositor or validate its in-game integration and state restoration.
 */
public final class DjCameraAlphaChecks {
    private static final int WIDTH = 384;
    private static final int HEIGHT = 128;
    private static final int[] BACKGROUND = {8, 13, 18};
    private static final String VERTEX = """
            #version 150
            out vec2 texCoord;
            out vec2 texCoord0;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = p;
                texCoord0 = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static int vignette;
    private static int fishEye;
    private static int monitor;
    private static int objectOutline;
    private static JsonObject vignetteConfig;
    private static JsonObject fishConfig;

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]);
        Path trackPath = Path.of(args[1]);
        JsonObject track = JsonParser.parseString(Files.readString(trackPath)).getAsJsonObject();
        for (var item : track.getAsJsonArray("effects")) {
            JsonObject effect = item.getAsJsonObject();
            if (effect.get("tick").getAsInt() != 0) continue;
            if (effect.get("type").getAsString().equals("vignette")) vignetteConfig = effect;
            if (effect.get("type").getAsString().equals("fish_eye")) fishConfig = effect;
        }
        require(vignetteConfig != null && fishConfig != null, "Track must reproduce both initial effects");
        require(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Blocky Music alpha check (hidden)", 0, 0);
        require(window != 0, "Hidden OpenGL context creation failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            Path shaders = project.resolve("src/main/resources/assets/music_battle/shaders/core");
            vignette = program(Files.readString(shaders.resolve("vignette.fsh")));
            fishEye = program(Files.readString(shaders.resolve("fish_eye.fsh")));
            objectOutline = program(Files.readString(shaders.resolve("object_outline.fsh")));
            try (ZipFile resources = new ZipFile(project.resolve(
                    "build/moddev/artifacts/neoforge-21.1.230-client-extra-aka-minecraft-resources.jar").toFile())) {
                monitor = program(new String(resources.getInputStream(resources.getEntry(
                        "assets/minecraft/shaders/core/position_tex.fsh")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            glBindVertexArray(glGenVertexArrays());
            Result replacementOnly = render(true, false, false);
            require(allOpaque(replacementOnly.scene),
                    "Replacement fullscreen FX retained transparent custom-block pixels");
            Result corrected = render(true, true, false);
            require(!Arrays.equals(center(corrected.display), BACKGROUND), "Corrected center still has a hole");
            require(allOpaque(corrected.scene), "Corrected camera image must be opaque at every pixel");
            require(equalRgb(replacementOnly.scene, corrected.scene),
                    "Opaque resolve changed the ordered effect RGB result");
            Result reversed = render(true, true, true);
            require(!equalRgb(corrected.scene, reversed.scene), "Vignette/fish-eye order must remain meaningful");
            Result resolveOnly = render(false, true, false);
            require(allOpaque(resolveOnly.scene) && equalRgb(replacementOnly.scene, resolveOnly.scene),
                    "Opaque camera resolve must preserve RGB even after a transparent overlay");
            checkResolvePreservesMasks();
            checkObjectOutlineExcludesEntities();

            require(glGetError() == GL_NO_ERROR, "OpenGL reported an error during regression");
            System.out.println("PASS: shader/GL contract fixture preserves center, opaque coverage, RGB, row order and depth/stencil");
            System.out.println("NOT COVERED: production Java compositor execution and in-game state restoration");
            System.out.println("Track: " + trackPath);
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static Result render(boolean preserveAlpha, boolean opaqueResolve, boolean reverse) {
        Target source = target(true);
        Target warped = target(false);
        Target displayed = target(false);
        if (!reverse) vignette(source, preserveAlpha);
        fishEye(source, warped);
        if (reverse) vignette(warped, preserveAlpha);
        if (opaqueResolve) resolveOpaque(warped);
        byte[] scene = pixels(warped);
        glBindFramebuffer(GL_FRAMEBUFFER, displayed.framebuffer);
        glClearColor(BACKGROUND[0] / 255f, BACKGROUND[1] / 255f, BACKGROUND[2] / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(monitor);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, warped.texture);
        glUniform1i(glGetUniformLocation(monitor, "Sampler0"), 0);
        glUniform4f(glGetUniformLocation(monitor, "ColorModulator"), 1, 1, 1, 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        byte[] display = pixels(displayed);
        source.release();
        warped.release();
        displayed.release();
        return new Result(scene, display);
    }

    private static void checkResolvePreservesMasks() {
        Target target = target(true);
        int depthStencil = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Incomplete stencil fixture");
        glClearBufferfi(GL_DEPTH_STENCIL, 0, 0.375f, 0x5A);
        glColorMask(false, false, false, true);
        glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 0});
        glColorMask(true, true, true, true);
        byte[] before = pixels(target);
        resolveOpaque(target);
        byte[] after = pixels(target);
        require(equalRgb(before, after) && allOpaque(after), "Resolve changed RGB or retained alpha holes");
        ByteBuffer stencil = BufferUtils.createByteBuffer(1);
        glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL_STENCIL_INDEX, GL_UNSIGNED_BYTE, stencil);
        require((stencil.get(0) & 255) == 0x5A, "Opaque resolve overwrote player/entity stencil masks");
        float[] depth = new float[1];
        glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        require(Math.abs(depth[0] - 0.375f) < 0.00001f, "Opaque resolve changed scene depth");
        target.release();
        glDeleteRenderbuffers(depthStencil);
    }

    private static void checkObjectOutlineExcludesEntities() {
        int colors = fixtureTexture(false, false);
        int depths = fixtureTexture(true, false);
        int excluded = fixtureTexture(false, true);
        Target output = target(false);
        glBindFramebuffer(GL_FRAMEBUFFER, output.framebuffer);
        glViewport(0, 0, WIDTH, HEIGHT);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_BLEND);
        glUseProgram(objectOutline);
        bind(objectOutline, "BlockColorSampler", colors, 0);
        bind(objectOutline, "BlockDepthSampler", depths, 1);
        bind(objectOutline, "ExcludedSampler", excluded, 2);
        bind(objectOutline, "SceneDepthSampler", depths, 3);
        glUniform2f(glGetUniformLocation(objectOutline, "InSize"), WIDTH, HEIGHT);
        glUniform3f(glGetUniformLocation(objectOutline, "OutlineColor"), 1, 0, 1);
        uniform(objectOutline, "Alpha", 1);
        uniform(objectOutline, "Thickness", 2);
        uniform(objectOutline, "GlowStrength", 0);
        uniform(objectOutline, "UseSceneDepth", 0);
        uniform(objectOutline, "UseExcludedMask", 1);
        uniform(objectOutline, "WarpCount", 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        byte[] pixels = pixels(output);
        require(alphaAt(pixels, WIDTH / 4 - 17, HEIGHT / 2) > 100,
                "Late block-entity geometry produced no object contour");
        require(alphaAt(pixels, WIDTH * 3 / 4 - 17, HEIGHT / 2) == 0,
                "Excluded living entity leaked into the object contour");
        glDeleteTextures(colors);
        glDeleteTextures(depths);
        glDeleteTextures(excluded);
        output.release();
    }

    private static int fixtureTexture(boolean depth, boolean exclusion) {
        ByteBuffer data = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            boolean blockEntity = Math.abs(x - WIDTH / 4) < 16 && Math.abs(y - HEIGHT / 2) < 24;
            boolean entity = Math.abs(x - WIDTH * 3 / 4) < 16 && Math.abs(y - HEIGHT / 2) < 24;
            boolean present = blockEntity || entity;
            int value = exclusion ? (entity ? 255 : 0) : depth ? (present ? 128 : 255) : (present ? 180 : 20);
            data.put((byte) value).put((byte) (depth ? 0 : value)).put((byte) (depth ? 0 : value));
            data.put((byte) (depth ? (present ? 255 : 0) : 255));
        }
        data.flip();
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        return texture;
    }

    private static void bind(int program, String uniform, int texture, int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(program, uniform), unit);
    }

    private static int alphaAt(byte[] pixels, int x, int y) {
        return pixels[(y * WIDTH + x) * 4 + 3] & 255;
    }

    private static void resolveOpaque(Target target) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(false, false, false, true);
        glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 1});
        glColorMask(true, true, true, true);
    }

    private static void vignette(Target target, boolean preserveAlpha) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, WIDTH, HEIGHT);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
        glColorMask(true, true, true, !preserveAlpha);
        glUseProgram(vignette);
        int tint = vignetteConfig.get("colorA").getAsInt();
        glUniform3f(glGetUniformLocation(vignette, "Tint"), (tint >> 16 & 255) / 255f,
                (tint >> 8 & 255) / 255f, (tint & 255) / 255f);
        uniform(vignette, "Radius", number(vignetteConfig, "vignetteRadius"));
        uniform(vignette, "Softness", number(vignetteConfig, "vignetteSoftness"));
        uniform(vignette, "Roundness", number(vignetteConfig, "vignetteRoundness"));
        uniform(vignette, "Alpha", number(vignetteConfig, "intensity") * number(vignetteConfig, "opacity"));
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glColorMask(true, true, true, true);
    }

    private static void fishEye(Target source, Target destination) {
        glBindFramebuffer(GL_FRAMEBUFFER, destination.framebuffer);
        glViewport(0, 0, WIDTH, HEIGHT);
        glDisable(GL_BLEND);
        glUseProgram(fishEye);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, source.texture);
        glUniform1i(glGetUniformLocation(fishEye, "DiffuseSampler"), 0);
        glUniform1i(glGetUniformLocation(fishEye, "ModelMaskSampler"), 0);
        uniform(fishEye, "Strength", number(fishConfig, "fishEyeStrength"));
        uniform(fishEye, "Radius", number(fishConfig, "fishEyeRadius"));
        uniform(fishEye, "Zoom", number(fishConfig, "fishEyeZoom"));
        uniform(fishEye, "Opacity", number(fishConfig, "opacity"));
        uniform(fishEye, "Chromatic", number(fishConfig, "fishEyeChromatic"));
        uniform(fishEye, "EdgeDarkening", number(fishConfig, "fishEyeEdgeDarkening"));
        uniform(fishEye, "RemoveBlackEdges", 0);
        uniform(fishEye, "UseModelMask", 0);
        glUniform2f(glGetUniformLocation(fishEye, "Center"), 0.5f, 0.5f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static Target target(boolean pattern) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        ByteBuffer data = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                data.put((byte) (40 + x * 150 / WIDTH));
                data.put((byte) (60 + y * 140 / HEIGHT));
                data.put((byte) (((x / 16 + y / 16) & 1) == 0 ? 90 : 180));
                // Custom block renderers can leave non-opaque coverage in the scene target.
                // Replacement fullscreen FX must resolve it instead of carrying a see-through
                // speaker/camera/DJ model into the live monitor texture.
                boolean customBlockPatch = x >= WIDTH / 3 && x < WIDTH * 2 / 3
                        && y >= HEIGHT / 3 && y < HEIGHT * 2 / 3;
                data.put((byte) (customBlockPatch ? 96 : 255));
            }
        }
        data.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                pattern ? data : null);
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "Incomplete test framebuffer");
        return new Target(texture, framebuffer);
    }

    private static int program(String fragment) {
        int vertex = compile(GL_VERTEX_SHADER, VERTEX);
        int pixel = compile(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, pixel);
        glLinkProgram(program);
        require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
        glDeleteShader(vertex);
        glDeleteShader(pixel);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        require(glGetShaderi(shader, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(shader));
        return shader;
    }

    private static byte[] pixels(Target target) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        byte[] values = new byte[pixels.remaining()];
        pixels.get(values);
        return values;
    }

    private static int[] center(byte[] pixels) {
        int offset = (HEIGHT / 2 * WIDTH + WIDTH / 2) * 4;
        return new int[]{pixels[offset] & 255, pixels[offset + 1] & 255, pixels[offset + 2] & 255};
    }

    private static int alphaAtCenter(byte[] pixels) {
        return pixels[(HEIGHT / 2 * WIDTH + WIDTH / 2) * 4 + 3] & 255;
    }

    private static boolean allOpaque(byte[] pixels) {
        for (int index = 3; index < pixels.length; index += 4) if ((pixels[index] & 255) != 255) return false;
        return true;
    }

    private static boolean equalRgb(byte[] left, byte[] right) {
        for (int index = 0; index < left.length; index++) if (index % 4 != 3 && left[index] != right[index]) return false;
        return true;
    }

    private static float number(JsonObject object, String field) {
        return object.get(field).getAsFloat();
    }

    private static void uniform(int shader, String field, float value) {
        glUniform1f(glGetUniformLocation(shader, field), value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Target(int texture, int framebuffer) {
        void release() {
            glDeleteTextures(texture);
            glDeleteFramebuffers(framebuffer);
        }
    }

    private record Result(byte[] scene, byte[] display) { }
}
