import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import ru.gora.musicbattle.client.ui.CardGrid;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.SdfMath;
import ru.gora.musicbattle.client.ui.Spectrum;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiShaderGuard;

/**
 * Regression for the Workshop UI toolkit (ru.gora.musicbattle.client.ui) without a game window or GPU.
 *
 * Proves: the SDF shader's parameter packing and the Java copy of its coverage maths (rounded rects,
 * strokes, shadows and the ring wedge on a CPU grid against plain geometry), the shader files' names and
 * constants agree with the Java side (plus glslangValidator when installed); spectrum and palette colours
 * equal the website table and tokens (and the website repository's files when it sits next to this one);
 * TTF tier selection and one font JSON per style and tier; the fonts are TrueType with glyf outlines,
 * Cyrillic, the added symbols and an OFL text; icon atlases match the enum and have no dark or bleeding
 * pixels; Tween and SmoothScroll are deterministic and frame-rate independent; CardGrid's visible range and
 * hit-testing agree with brute force; a failing shader factory never throws.
 *
 * It does not prove how anything looks or performs in Minecraft: the debug screen (/musicbattle debugui) is
 * for that.
 *
 * Usage: java -cp <classpath> UiToolkitChecks <project root>
 */
public final class UiToolkitChecks {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static Path root;
    private static final String ASSETS = "src/main/resources/assets/music_battle/";

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        sdfEncoding();
        sdfShaderContract();
        ringWedgeGrid();
        roundedRectGrid();
        spectrum();
        palette();
        textTiers();
        fonts();
        icons();
        motion();
        smoothScroll();
        cardGrid();
        shaderGuard();
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(message -> System.out.println("FAIL " + message));
            System.out.println("UI toolkit checks FAILED: " + FAILURES.size() + " of " + checks);
            System.exit(1);
        }
        System.out.println("UI toolkit checks passed: " + checks);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            FAILURES.add(message);
        }
    }

    static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": expected " + expected + ", got " + actual);
    }

    static String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    static String hex(int argb) {
        return String.format(Locale.ROOT, "%08X", argb);
    }

    // ---- SDF --------------------------------------------------------------------------------------------------

    static void sdfEncoding() {
        check(SdfMath.q8(0) == 0 && SdfMath.q8(-3) == 0 && SdfMath.q8(Float.NaN) == 0, "q8 floors non-positive and NaN to 0");
        check(SdfMath.q8(1.0F) == 8 && SdfMath.q8(0.0625F) == 1 && SdfMath.q8(12.34F) == 99, "q8 is round(value * 8)");
        check(SdfMath.q8(4095.875F) == 32767 && SdfMath.q8(100_000F) == 32767, "q8 saturates at the positive short range");

        int[] fill = SdfMath.fill(20.0F, 10.0F, 4.0F);
        check(fill[0] == 160 && fill[1] == 80 && fill[2] == 32 && fill[3] == SdfMath.KIND_FILL, "fill packs half extents, radius, kind 0");
        int[] clamped = SdfMath.fill(5.0F, 3.0F, 10.0F);
        check(clamped[2] == 24, "a radius larger than the short half extent is clamped to it");

        int[] stroke = SdfMath.stroke(10.0F, 10.0F, 2.0F, 1.5F);
        check(stroke[3] == ((12 << 2) | 1), "stroke packs width*8 << 2 | kind 1, got " + stroke[3]);
        check(SdfMath.kind(stroke[3]) == 1 && SdfMath.param(stroke[3]) == 1.5F, "stroke width decodes back");
        int[] wide = SdfMath.stroke(10.0F, 10.0F, 2.0F, 5000.0F);
        check(wide[3] > 0 && wide[3] <= 32767 && SdfMath.kind(wide[3]) == 1, "a huge stroke width stays a positive short with its kind");

        int[] shadow = SdfMath.shadow(30.0F, 20.0F, 6.0F, 7.0F);
        check(SdfMath.kind(shadow[3]) == SdfMath.KIND_SHADOW && SdfMath.param(shadow[3]) == 7.0F, "shadow packs softness and kind 3");
        near(SdfMath.quadPadding(shadow), SdfMath.PAD + 7.0F, 1e-6, "shadow quads grow by their softness");
        near(SdfMath.quadPadding(fill), SdfMath.PAD, 1e-6, "other quads keep the AA pad");

        int[] ring = SdfMath.ring(12.0F, 0.2F, 0.25F);
        check(ring[0] == 96 && ring[1] == 77 && ring[2] == 8192 && ring[3] == SdfMath.KIND_RING,
                "ring packs outer*8, inner*8, sweep*32767, kind 2: " + java.util.Arrays.toString(ring));
        int[] pie = SdfMath.ring(12.0F, 1.0F, 1.7F);
        check(pie[1] == 0 && pie[2] == 32767, "a pie has inner radius 0 and progress clamps to a full turn");
        int[] none = SdfMath.ring(12.0F, 0.2F, Float.NaN);
        check(none[2] == 0, "NaN progress is empty");

        int[] scratch = new int[4];
        check(SdfMath.fill(scratch, 20.0F, 10.0F, 4.0F) == scratch && java.util.Arrays.equals(scratch, fill),
                "the allocation-free form writes the same values into the caller's array");

        Random random = new Random(7);
        boolean inRange = true;
        for (int i = 0; i < 20_000; i++) {
            float a = (random.nextFloat() - 0.1F) * 9000.0F;
            float b = (random.nextFloat() - 0.1F) * 9000.0F;
            float c = (random.nextFloat() - 0.2F) * 3000.0F;
            float d = (random.nextFloat() - 0.2F) * 3000.0F;
            for (int[] params : new int[][] {SdfMath.fill(a, b, c), SdfMath.stroke(a, b, c, d), SdfMath.shadow(a, b, c, d),
                    SdfMath.ring(a, random.nextFloat() * 1.4F - 0.2F, random.nextFloat() * 1.4F - 0.2F)}) {
                for (int value : params) {
                    inRange &= value >= 0 && value <= 32767;
                }
            }
        }
        check(inRange, "every packed value fits a positive signed short for 80 000 random shapes");
    }

    static void sdfShaderContract() throws IOException {
        String fsh = read(ASSETS + "shaders/core/ui_sdf.fsh");
        String vsh = read(ASSETS + "shaders/core/ui_sdf.vsh");
        for (String token : new String[] {"/ 8.0", "/ 32767.0", "shapeB.y & 3", "shapeB.y >> 2", "kind == 1", "kind == 2", "kind == 3",
                "sweep < 0.9999", "startSide = -localPos.x", "dot(localPos, vec2(cos(a), sin(a)))", "smoothstep(-soft, soft, d)",
                "clamp(0.5 - d / px, 0.0, 1.0)"}) {
            check(fsh.contains(token), "ui_sdf.fsh still contains the maths SdfMath mirrors: " + token);
        }
        check(SdfMath.FIXED == 8.0F && SdfMath.SWEEP_ONE == 32767, "Java fixed-point constants match the shader's");

        String uiSdf = read("src/main/java/ru/gora/musicbattle/client/ui/UiSdf.java");
        Matcher element = Pattern.compile("\\.add\\(\"(\\w+)\", VertexFormatElement\\.(\\w+)\\)").matcher(uiSdf);
        List<String> names = new ArrayList<>();
        while (element.find()) {
            names.add(element.group(1));
        }
        check(names.equals(List.of("Position", "Color", "UV0", "UV1", "UV2")), "UiSdf.FORMAT elements in order: " + names);
        for (String declaration : new String[] {"in vec3 Position;", "in vec4 Color;", "in vec2 UV0;", "in ivec2 UV1;", "in ivec2 UV2;"}) {
            check(vsh.contains(declaration), "ui_sdf.vsh declares " + declaration + " (attribute names bind by format element name)");
        }

        JsonObject sdfJson = JsonParser.parseString(read(ASSETS + "shaders/core/ui_sdf.json")).getAsJsonObject();
        check("music_battle:ui_sdf".equals(sdfJson.get("vertex").getAsString())
                && "music_battle:ui_sdf".equals(sdfJson.get("fragment").getAsString()), "ui_sdf.json names its own vertex and fragment");
        check(uniformNames(sdfJson).containsAll(List.of("ModelViewMat", "ProjMat", "ColorModulator")), "ui_sdf.json declares the default uniforms");

        JsonObject imageJson = JsonParser.parseString(read(ASSETS + "shaders/core/ui_rounded_image.json")).getAsJsonObject();
        String imageFsh = read(ASSETS + "shaders/core/ui_rounded_image.fsh");
        check("minecraft:position_tex_color".equals(imageJson.get("vertex").getAsString())
                && "music_battle:ui_rounded_image".equals(imageJson.get("fragment").getAsString()), "ui_rounded_image.json uses vanilla position_tex_color");
        List<String> imageUniforms = uniformNames(imageJson);
        String roundedImage = read("src/main/java/ru/gora/musicbattle/client/ui/RoundedImage.java");
        Matcher uniform = Pattern.compile("safeGetUniform\\(\"(\\w+)\"\\)").matcher(roundedImage);
        int used = 0;
        while (uniform.find()) {
            used++;
            check(imageUniforms.contains(uniform.group(1)) && imageFsh.contains("uniform") && imageFsh.contains(uniform.group(1)),
                    "RoundedImage sets uniform " + uniform.group(1) + ", declared in the JSON and the fragment shader");
        }
        check(used == 4, "RoundedImage sets RectSize, Radii, UvRect and Dim, found " + used);
        check(imageJson.getAsJsonArray("samplers").toString().contains("Sampler0") && imageFsh.contains("uniform sampler2D Sampler0"),
                "ui_rounded_image samples Sampler0");

        String events = read("src/main/java/ru/gora/musicbattle/client/ClientModEvents.java");
        check(events.contains("UiSdf.register(event)") && events.contains("RoundedImage.register(event)"),
                "ClientModEvents.registerShaders registers both UI shaders");

        Path validator = findOnPath("glslangValidator");
        if (validator == null) {
            System.out.println("NOTE glslangValidator not on PATH: GLSL compile check skipped");
            return;
        }
        for (String[] shader : new String[][] {{"vert", "ui_sdf.vsh"}, {"frag", "ui_sdf.fsh"}, {"frag", "ui_rounded_image.fsh"}}) {
            Path file = root.resolve(ASSETS + "shaders/core/" + shader[1]);
            try {
                Process process = new ProcessBuilder(validator.toString(), "-S", shader[0], file.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean done = process.waitFor(30, TimeUnit.SECONDS);
                check(done && process.exitValue() == 0, "glslangValidator accepts " + shader[1] + (output.isBlank() ? "" : ": " + output.trim()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                check(false, "glslangValidator interrupted");
            }
        }
    }

    static List<String> uniformNames(JsonObject shader) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : shader.getAsJsonArray("uniforms")) {
            names.add(element.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    static Path findOnPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : new String[] {program, program + ".exe"}) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Ring coverage from the packed parameters against plain polar geometry on a grid, away from edges. */
    static void ringWedgeGrid() {
        float outer = 20.0F;
        float thickness = 0.35F;
        double px = 0.25; // GUI scale 4
        for (float sweep : new float[] {0.05F, 0.25F, 0.5F, 0.6F, 0.75F, 0.9F, 0.99995F, 1.0F}) {
            int[] params = SdfMath.ring(outer, thickness, sweep);
            double inner = params[1] / 8.0;
            double sweepDecoded = params[2] / 32767.0;
            boolean full = sweepDecoded >= 0.9999;
            double end = sweepDecoded * Math.PI * 2.0;
            int checked = 0;
            int mismatches = 0;
            int insideCount = 0;
            for (double y = -22.0; y <= 22.0; y += 0.25) {
                for (double x = -22.0; x <= 22.0; x += 0.25) {
                    double r = Math.hypot(x, y);
                    // Clockwise angle from 12 o'clock with GUI y pointing down.
                    double theta = Math.atan2(x, -y);
                    if (theta < 0) {
                        theta += Math.PI * 2.0;
                    }
                    boolean inside = r >= inner && r <= outer && (full || theta <= end);
                    double edge = Math.min(Math.abs(r - outer), Math.abs(r - inner));
                    if (!full) {
                        edge = Math.min(edge, rayDistance(x, y, 0.0, -1.0));
                        edge = Math.min(edge, rayDistance(x, y, Math.sin(end), -Math.cos(end)));
                    }
                    if (inside) {
                        insideCount++;
                    }
                    if (edge < 0.5) {
                        continue;
                    }
                    checked++;
                    double coverage = SdfMath.coverage(params, x, y, px);
                    if (inside ? coverage < 0.999 : coverage > 0.001) {
                        mismatches++;
                    }
                }
            }
            check(mismatches == 0, "ring sweep " + sweep + ": " + mismatches + " of " + checked + " grid points disagree with polar geometry");
            double expected = (full ? 1.0 : sweepDecoded) * Math.PI * (outer * outer - inner * inner) / (0.25 * 0.25);
            check(Math.abs(insideCount - expected) / expected < 0.08,
                    "ring sweep " + sweep + ": filled area " + insideCount + " grid cells, expected about " + Math.round(expected));
        }
        // Anti-aliasing: coverage is 0.5 on the outer edge and fades over one physical pixel at any scale.
        int[] ring = SdfMath.ring(outer, thickness, 1.0F);
        for (double pixel : new double[] {1.0, 0.5, 1.0 / 3.0, 0.125}) {
            near(SdfMath.coverage(ring, 0.0, -outer, pixel), 0.5, 1e-9, "ring edge coverage is 0.5 at px " + pixel);
            near(SdfMath.coverage(ring, 0.0, -(outer + pixel * 0.5), pixel), 0.0, 1e-9, "half a pixel outside is empty at px " + pixel);
            near(SdfMath.coverage(ring, 0.0, -(outer - pixel * 0.5), pixel), 1.0, 1e-9, "half a pixel inside is full at px " + pixel);
        }
        // Start edge at 12 o'clock: a quarter ring covers the right of the top, not the left.
        int[] quarter = SdfMath.ring(outer, 0.5F, 0.25F);
        check(SdfMath.coverage(quarter, 5.0, -15.0, 0.25) == 1.0 && SdfMath.coverage(quarter, -5.0, -15.0, 0.25) == 0.0,
                "a quarter ring starts at 12 o'clock and runs clockwise (to the right first)");
        check(SdfMath.coverage(quarter, 15.0, 5.0, 0.25) == 0.0 && SdfMath.coverage(quarter, 15.0, -5.0, 0.25) == 1.0,
                "a quarter ring ends at 3 o'clock");
    }

    static double rayDistance(double x, double y, double dx, double dy) {
        double along = x * dx + y * dy;
        if (along <= 0) {
            return Math.hypot(x, y);
        }
        return Math.abs(x * dy - y * dx);
    }

    static void roundedRectGrid() {
        float hw = 30.0F;
        float hh = 18.0F;
        float radius = 8.0F;
        float width = 2.5F;
        int[] fill = SdfMath.fill(hw, hh, radius);
        int[] stroke = SdfMath.stroke(hw, hh, radius, width);
        int[] shadow = SdfMath.shadow(hw, hh, radius, 6.0F);
        int fillMismatch = 0;
        int strokeMismatch = 0;
        int checked = 0;
        for (double y = -22.0; y <= 22.0; y += 0.25) {
            for (double x = -34.0; x <= 34.0; x += 0.25) {
                double outerDistance = boxDistance(x, y, hw, hh, radius);
                double innerDistance = boxDistance(x, y, hw - width, hh - width, Math.max(0.0, radius - width));
                if (Math.abs(outerDistance) < 0.5 || Math.abs(innerDistance) < 0.5) {
                    continue;
                }
                checked++;
                boolean insideFill = outerDistance < 0;
                boolean insideStroke = outerDistance < 0 && innerDistance > 0;
                double f = SdfMath.coverage(fill, x, y, 0.25);
                double s = SdfMath.coverage(stroke, x, y, 0.25);
                if (insideFill ? f < 0.999 : f > 0.001) {
                    fillMismatch++;
                }
                if (insideStroke ? s < 0.999 : s > 0.001) {
                    strokeMismatch++;
                }
            }
        }
        check(fillMismatch == 0, "rounded fill: " + fillMismatch + " of " + checked + " grid points disagree with corner-circle geometry");
        check(strokeMismatch == 0, "inner stroke: " + strokeMismatch + " of " + checked + " grid points disagree with geometry");
        near(SdfMath.coverage(shadow, 0, 0, 0.25), 1.0, 1e-9, "a shadow is solid inside");
        near(SdfMath.coverage(shadow, hw, 0, 0.25), 0.5, 1e-9, "a shadow is half strength on the edge");
        near(SdfMath.coverage(shadow, hw + 6.0, 0, 0.25), 0.0, 1e-9, "a shadow is gone one softness outside");
    }

    /** Independent rounded-box distance: straight edges, or the distance to the corner circle. */
    static double boxDistance(double x, double y, double hw, double hh, double r) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double cx = hw - r;
        double cy = hh - r;
        if (ax > cx && ay > cy) {
            return Math.hypot(ax - cx, ay - cy) - r;
        }
        if (ax <= hw && ay <= hh) {
            return -Math.min(hw - ax, hh - ay);
        }
        return Math.max(ax - hw, ay - hh);
    }

    // ---- colours ------------------------------------------------------------------------------------------

    /** Spec 03 section 3.3, "Difficulty spectrum (our stops, linear-light interpolation)". */
    static final double[] WEBSITE_STOPS = {0.0, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 10.0};
    static final int[] WEBSITE_COLOURS = {0x7fb2ff, 0x56d6ff, 0x52efc0, 0xa8ee5c, 0xffe25e, 0xffa25a, 0xff6470, 0xd65ad8, 0x8363ff, 0x2a1a66};

    static void spectrum() throws IOException {
        check(Spectrum.stopCount() == WEBSITE_STOPS.length, "spectrum has the website's " + WEBSITE_STOPS.length + " stops");
        for (int i = 0; i < Math.min(Spectrum.stopCount(), WEBSITE_STOPS.length); i++) {
            check(Spectrum.stopStars(i) == WEBSITE_STOPS[i] && Spectrum.stopRgb(i) == WEBSITE_COLOURS[i],
                    "spectrum stop " + i + " equals the website table");
            check(Spectrum.argb(WEBSITE_STOPS[i]) == (0xFF000000 | WEBSITE_COLOURS[i]), "a rating exactly on stop " + i + " is the stop colour");
        }
        check(Spectrum.argb(-2) == (0xFF000000 | WEBSITE_COLOURS[0]) && Spectrum.argb(Double.NaN) == (0xFF000000 | WEBSITE_COLOURS[0]),
                "negative and NaN ratings use the first stop");
        check(Spectrum.argb(12.5) == (0xFF000000 | WEBSITE_COLOURS[9]), "ratings above 10 use the last stop");
        // Golden values computed independently in Python with the same definition.
        Object[][] golden = {{0.75, 0x6dc5ff}, {1.0, 0x66cbff}, {2.0, 0x54e3e2}, {3.0, 0x86ef99}, {4.0, 0xd9e85d}, {5.0, 0xffc55c},
                {6.0, 0xff8866}, {6.99, 0xec5fad}, {7.0, 0xec5fae}, {8.0, 0xb35fed}, {9.25, 0x634ac6}, {9.99, 0x2b1b68},
                // and from the website's generated spectrum.css (buckets 3.7, 6.7, 7.2, 9.5)
                {3.7, 0xbeec5c}, {6.7, 0xf8628d}, {7.2, 0xe35dc0}, {9.5, 0x553eae}};
        for (Object[] row : golden) {
            int expected = 0xFF000000 | (int) row[1];
            int actual = Spectrum.argb((double) row[0]);
            check(actual == expected, "spectrum at " + row[0] + ": expected " + hex(expected) + ", got " + hex(actual));
        }
        int mismatches = 0;
        for (int i = 0; i <= 1100; i++) {
            double stars = i / 100.0;
            if (Spectrum.argb(stars) != independentSpectrum(stars)) {
                mismatches++;
            }
        }
        check(mismatches == 0, "spectrum equals an independent linear-light implementation at 0.00..11.00, " + mismatches + " mismatches");
        check(Spectrum.bucket(6.72) == 67 && Spectrum.bucket(6.9999) == 69 && Spectrum.bucket(0.7) == 7 && Spectrum.bucket(-1) == 0
                && Spectrum.bucket(15) == 120 && Spectrum.bucket(Double.NaN) == 0, "website colour buckets are floor(stars * 10), 0..120");
        check(Spectrum.displayArgb(6.72) == Spectrum.argb(6.7), "the displayed colour is the bucket's colour");
        check(Spectrum.glyphIsDark(8.6) && !Spectrum.glyphIsDark(8.7) && Spectrum.glyphArgb(9.0) == Palette.TEXT_1,
                "the star glyph turns light from 8.7 stars (website contrast rule)");
        check(!Spectrum.needsRing(8.6) && Spectrum.needsRing(8.7) && Spectrum.needsRing(9.5), "dots get a ring from 8.7 stars on cards");
        check(Spectrum.PILL_INNER == Palette.hsla(265, 20, 12, 0.85) && Spectrum.GLYPH_DARK == Palette.hsl(265, 20, 12),
                "pill capsule and dark glyph colours");
        check(Spectrum.formatStars(6.9999).equals("6.99") && Spectrum.formatStars(4.5).equals("4.50") && Spectrum.formatStars(0.07).equals("0.07"),
                "star values floor to two decimals");
        near(Spectrum.contrast(0xFFFFFFFF, 0xFF000000), 21.0, 1e-9, "contrast of white on black");

        Path website = root.resolveSibling("blocky-music-workshop");
        Path spec = website.resolve("docs/spec/03-website.md");
        if (!Files.isRegularFile(spec)) {
            System.out.println("NOTE website repository not found next to this one: spectrum compared with the embedded table");
            return;
        }
        String text = Files.readString(spec, StandardCharsets.UTF_8);
        Matcher starsRow = Pattern.compile("\\| Stars \\|([^\\n]+)").matcher(text);
        Matcher colourRow = Pattern.compile("\\| Colour \\|([^\\n]+)").matcher(text);
        boolean found = starsRow.find() && colourRow.find();
        check(found, "03-website.md still has the spectrum table");
        if (found) {
            List<String> stars = cells(starsRow.group(1));
            List<String> colours = cells(colourRow.group(1));
            check(stars.size() == WEBSITE_STOPS.length && colours.size() == WEBSITE_COLOURS.length, "website table has 10 stops");
            for (int i = 0; i < Math.min(stars.size(), WEBSITE_STOPS.length); i++) {
                double value = Double.parseDouble(stars.get(i).replace("+", ""));
                int colour = Integer.parseInt(colours.get(i).replace("`", "").replace("#", ""), 16);
                check(value == WEBSITE_STOPS[i] && colour == WEBSITE_COLOURS[i],
                        "website spec stop " + i + " (" + stars.get(i) + ", " + colours.get(i) + ") equals the mod");
            }
        }
        Path module = website.resolve("client/spectrum.ts");
        if (Files.isRegularFile(module)) {
            String ts = Files.readString(module, StandardCharsets.UTF_8);
            Matcher stop = Pattern.compile("\\[([0-9.]+), '#([0-9a-fA-F]{6})'\\]").matcher(ts);
            int index = 0;
            while (stop.find()) {
                boolean same = index < WEBSITE_STOPS.length && Double.parseDouble(stop.group(1)) == WEBSITE_STOPS[index]
                        && Integer.parseInt(stop.group(2), 16) == WEBSITE_COLOURS[index];
                check(same, "client/spectrum.ts stop " + index + " [" + stop.group(1) + ", #" + stop.group(2) + "] equals the mod");
                index++;
            }
            check(index == WEBSITE_STOPS.length, "client/spectrum.ts has " + WEBSITE_STOPS.length + " stops, found " + index);
            check(ts.contains("hslToRgb(265, 20, 12), alpha: 0.85") && ts.contains("s >= 9.5 || contrast(") && ts.contains("< 3"),
                    "client/spectrum.ts still uses the pill capsule, ring threshold and contrast the mod mirrors");
        } else {
            System.out.println("NOTE website client/spectrum.ts not found: compared with the spec table only");
        }
        Path css = website.resolve("public/css/modules/spectrum.css");
        if (Files.isRegularFile(css)) {
            Matcher row = Pattern.compile("\\.sc-(\\d+) \\{ --star: #([0-9a-f]{6}); --star-glyph: ([^;]+); --star-ring: ([^;]+); \\}")
                    .matcher(Files.readString(css, StandardCharsets.UTF_8));
            int rows = 0;
            int wrong = 0;
            while (row.find()) {
                rows++;
                double stars = Integer.parseInt(row.group(1)) / 10.0;
                boolean colour = Spectrum.displayArgb(stars) == (0xFF000000 | Integer.parseInt(row.group(2), 16));
                boolean glyph = Spectrum.glyphIsDark(stars) == row.group(3).startsWith("hsl(265 20% 12%)");
                boolean ring = Spectrum.needsRing(stars) == row.group(4).contains("text-3");
                if (!(colour && glyph && ring)) {
                    wrong++;
                    if (wrong <= 3) {
                        System.out.println("  spectrum.css .sc-" + row.group(1) + ": colour " + colour + ", glyph " + glyph + ", ring " + ring);
                    }
                }
            }
            check(rows == Spectrum.MAX_BUCKET + 1 && wrong == 0,
                    "every bucket of the website's spectrum.css (" + rows + ") has the mod's colour, glyph and ring, " + wrong + " differ");
        } else {
            System.out.println("NOTE website spectrum.css not generated yet: bucket colours not cross-checked");
        }
    }

    static List<String> cells(String row) {
        List<String> result = new ArrayList<>();
        for (String cell : row.split("\\|")) {
            if (!cell.isBlank()) {
                result.add(cell.trim());
            }
        }
        return result;
    }

    static int independentSpectrum(double stars) {
        double s = Double.isFinite(stars) ? Math.max(0.0, Math.min(10.0, stars)) : 0;
        int segment = WEBSITE_STOPS.length - 2;
        for (int i = 0; i < WEBSITE_STOPS.length - 1; i++) {
            if (s < WEBSITE_STOPS[i + 1]) {
                segment = i;
                break;
            }
        }
        double t = (s - WEBSITE_STOPS[segment]) / (WEBSITE_STOPS[segment + 1] - WEBSITE_STOPS[segment]);
        t = Math.max(0.0, Math.min(1.0, t));
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double a = decode((WEBSITE_COLOURS[segment] >> shift & 255) / 255.0);
            double b = decode((WEBSITE_COLOURS[segment + 1] >> shift & 255) / 255.0);
            double mixed = encode(a * (1 - t) + b * t);
            out |= (int) Math.floor(Math.max(0, Math.min(1, mixed)) * 255.0 + 0.5) << shift;
        }
        return out;
    }

    static double decode(double c) {
        return c > 0.04045 ? Math.pow((c + 0.055) / 1.055, 2.4) : c / 12.92;
    }

    static double encode(double c) {
        return c > 0.0031308 ? 1.055 * Math.pow(c, 1 / 2.4) - 0.055 : c * 12.92;
    }

    /** tokens.css at hue 265 (with the 17 September 2026 contrast adjustments): name, h, s, l, alpha. */
    static final Object[][] TOKENS = {
            {"BG_0", 265, 14, 7, 1.0}, {"BG_1", 265, 13, 11, 1.0}, {"BG_2", 265, 12, 15, 1.0}, {"BG_3", 265, 11, 20, 1.0},
            {"BG_4", 265, 10, 26, 1.0}, {"LINE", 265, 10, 32, 1.0}, {"TEXT_1", 265, 40, 98, 1.0}, {"TEXT_2", 265, 28, 86, 1.0},
            {"TEXT_3", 265, 18, 70, 1.0}, {"TEXT_MUTE", 265, 12, 62, 1.0}, {"ACCENT", 265, 100, 78, 1.0},
            {"ACCENT_STRONG", 265, 62, 56, 1.0}, {"ACCENT_DEEP", 265, 55, 40, 1.0}, {"LINK", 265, 55, 80, 1.0},
            {"ON_PILL", 265, 20, 14, 1.0}, {"ST_FEATURED", 44, 100, 66, 1.0}, {"ST_COMMUNITY", 158, 62, 60, 1.0},
            {"ST_WIP", 22, 100, 68, 1.0}, {"ST_PENDING", 205, 88, 70, 1.0}, {"ST_HIDDEN", 265, 8, 58, 1.0},
            {"BADGE_LIBRARY", 188, 90, 64, 1.0}, {"BADGE_EXPLICIT", 35, 95, 62, 1.0}, {"BADGE_AI", 280, 60, 76, 1.0},
            {"WARN", 40, 100, 58, 1.0}, {"DANGER", 355, 85, 60, 1.0}, {"OK", 88, 70, 52, 1.0}, {"RATING_NEG", 40, 100, 58, 1.0},
            {"RATING_BAR", 205, 70, 56, 1.0}, {"SHADOW", 265, 30, 2, 0.35}, {"SHADOW_LIFTED", 265, 30, 2, 0.45},
    };

    static void palette() throws Exception {
        Map<String, Integer> constants = new LinkedHashMap<>();
        for (Field field : Palette.class.getFields()) {
            if (field.getType() == int.class && Modifier.isStatic(field.getModifiers()) && !field.getName().startsWith("T_")
                    && !field.getName().equals("HUE")) {
                constants.put(field.getName(), field.getInt(null));
            }
        }
        for (Object[] token : TOKENS) {
            String name = (String) token[0];
            int expected = independentHsl((int) token[1], (int) token[2], (int) token[3], (double) token[4]);
            Integer actual = constants.remove(name);
            check(actual != null && actual == expected, "Palette." + name + " = hsl" + java.util.Arrays.toString(token)
                    + ": expected " + hex(expected) + ", got " + (actual == null ? "missing" : hex(actual)));
            check(Palette.hsla((int) token[1], (int) token[2], (int) token[3], (double) token[4]) == expected, "Palette.hsla agrees for " + name);
        }
        check(constants.isEmpty(), "every Palette colour constant is checked against a token: unchecked " + constants.keySet());
        check(Palette.fade(0xFF123456, 0.5F) == 0x80123456 && Palette.withAlpha(0x00ABCDEF, 1.0F) == 0xFFABCDEF, "fade and withAlpha");
        check(Palette.mix(0xFF000000, 0xFFFFFFFF, 0.5F) == 0xFF808080, "mix halves each channel");

        Path tokens = root.resolveSibling("blocky-music-workshop").resolve("public/css/tokens.css");
        if (!Files.isRegularFile(tokens)) {
            System.out.println("NOTE website tokens.css not found next to this repository: palette compared with the embedded table");
            return;
        }
        String css = Files.readString(tokens, StandardCharsets.UTF_8);
        Matcher hue = Pattern.compile("--hue:\\s*(\\d+)").matcher(css);
        check(hue.find() && Integer.parseInt(hue.group(1)) == Palette.HUE, "tokens.css --hue equals Palette.HUE");
        Matcher line = Pattern.compile("--([a-z0-9-]+):\\s*hsl\\((var\\(--hue\\)|\\d+)\\s+(\\d+)%\\s+(\\d+)%\\)").matcher(css);
        int compared = 0;
        Map<String, Integer> all = new HashMap<>();
        for (Field field : Palette.class.getFields()) {
            if (field.getType() == int.class) {
                all.put(field.getName(), field.getInt(null));
            }
        }
        while (line.find()) {
            String name = line.group(1).toUpperCase(Locale.ROOT).replace('-', '_');
            int h = line.group(2).startsWith("var") ? Palette.HUE : Integer.parseInt(line.group(2));
            int expected = independentHsl(h, Integer.parseInt(line.group(3)), Integer.parseInt(line.group(4)), 1.0);
            Integer actual = all.get(name);
            check(actual != null && actual == expected, "tokens.css --" + line.group(1) + " is mirrored as Palette." + name
                    + " (" + hex(expected) + ", got " + (actual == null ? "missing" : hex(actual)) + ")");
            compared++;
        }
        check(compared >= 28, "tokens.css colour tokens compared: " + compared);
        Matcher shadow = Pattern.compile("--shadow:[^;]*hsl\\(var\\(--hue\\) (\\d+)% (\\d+)% / ([0-9.]+)\\)").matcher(css);
        Matcher lifted = Pattern.compile("--shadow-lifted:[^;]*hsl\\(var\\(--hue\\) (\\d+)% (\\d+)% / ([0-9.]+)\\)").matcher(css);
        check(shadow.find() && Palette.SHADOW == independentHsl(Palette.HUE, Integer.parseInt(shadow.group(1)),
                Integer.parseInt(shadow.group(2)), Double.parseDouble(shadow.group(3))), "tokens.css --shadow equals Palette.SHADOW");
        check(lifted.find() && Palette.SHADOW_LIFTED == independentHsl(Palette.HUE, Integer.parseInt(lifted.group(1)),
                Integer.parseInt(lifted.group(2)), Double.parseDouble(lifted.group(3))), "tokens.css --shadow-lifted equals Palette.SHADOW_LIFTED");
    }

    /** The classic hue-to-RGB formulation (not CSS Color 4's), rounded half up to 8 bits. */
    static int independentHsl(int hueDegrees, int saturation, int lightness, double alpha) {
        double h = hueDegrees / 360.0;
        double s = saturation / 100.0;
        double l = lightness / 100.0;
        double r;
        double g;
        double b;
        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hueToRgb(p, q, h + 1.0 / 3);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1.0 / 3);
        }
        return byteOf(alpha) << 24 | byteOf(r) << 16 | byteOf(g) << 8 | byteOf(b);
    }

    static double hueToRgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 1.0 / 2) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    static int byteOf(double unit) {
        return (int) Math.floor(Math.max(0, Math.min(1, unit)) * 255 + 0.5);
    }

    // ---- text ---------------------------------------------------------------------------------------------

    static void textTiers() throws IOException {
        for (int scale = 1; scale <= 9; scale++) {
            check(TextStyle.tier(scale, 1.0) == scale, "GUI scale " + scale + " selects font tier x" + scale);
        }
        check(TextStyle.tier(10, 1.0) == 9 && TextStyle.tier(12, 1.0) == 9, "scales above 9 use the x9 fonts");
        check(TextStyle.tier(0.4, 1.0) == 1 && TextStyle.tier(Double.NaN, 1.0) == 1 && TextStyle.tier(3, 0) == 1, "degenerate scales use x1");
        check(TextStyle.tier(2, 1.5) == 3 && TextStyle.tier(4, 0.5) == 2, "pose scale multiplies into the tier");
        check(TextStyle.tier(1.49, 1.0) == 1 && TextStyle.tier(1.5, 1.0) == 2, "fractional pixel ratios round to the nearest tier");

        String script = read("tools/fonts/build-fonts.py");
        Matcher entry = Pattern.compile("\"(\\w+)\": \\((\\d+), ([0-9.]+)\\)").matcher(script);
        Map<String, double[]> scriptStyles = new LinkedHashMap<>();
        while (entry.find()) {
            scriptStyles.put(entry.group(1), new double[] {Double.parseDouble(entry.group(2)), Double.parseDouble(entry.group(3))});
        }
        check(scriptStyles.size() == TextStyle.values().length, "build-fonts.py STYLES has one entry per TextStyle: " + scriptStyles.keySet());
        check(script.contains("TIERS = range(1, 10)"), "build-fonts.py builds tiers 1..9");

        Path fontDir = root.resolve(ASSETS + "font");
        int jsons = 0;
        try (var stream = Files.list(fontDir)) {
            jsons = (int) stream.filter(p -> p.getFileName().toString().matches("ui_\\w+_x\\d+\\.json")).count();
        }
        check(jsons == TextStyle.values().length * TextStyle.MAX_TIER, "exactly one font JSON per style and tier, found " + jsons);
        for (TextStyle style : TextStyle.values()) {
            double[] fromScript = scriptStyles.get(style.id());
            check(fromScript != null && fromScript[0] == style.weight() && fromScript[1] == style.size(),
                    "TextStyle." + style + " matches build-fonts.py (weight " + style.weight() + ", size " + style.size() + ")");
            check(style.fontFile().equals(switch (style.weight()) {
                case 600 -> "nunito-semibold";
                case 700 -> "nunito-bold";
                default -> "nunito-extrabold";
            }), style + " uses the TTF of its weight");
            check(style.size() * TextStyle.MAX_TIER <= 160.0F, style + " at x9 stays under 160 px so glyphs fit a 256 px font page");
            for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
                String path = style.fontPath(tier);
                check(path.equals("ui_" + style.id() + "_x" + tier), "font path " + path);
                Path json = fontDir.resolve(path + ".json");
                if (!Files.isRegularFile(json)) {
                    check(false, "missing font JSON " + json.getFileName());
                    continue;
                }
                JsonArray providers = JsonParser.parseString(Files.readString(json)).getAsJsonObject().getAsJsonArray("providers");
                JsonObject ttf = providers.get(0).getAsJsonObject();
                JsonArray shift = ttf.getAsJsonArray("shift");
                boolean ok = providers.size() == 2
                        && "ttf".equals(ttf.get("type").getAsString())
                        && ("music_battle:" + style.fontFile() + ".ttf").equals(ttf.get("file").getAsString())
                        && ttf.get("size").getAsFloat() == style.size()
                        && ttf.get("oversample").getAsFloat() == tier
                        && shift.size() == 2 && shift.get(0).getAsFloat() == 0.0F
                        && Math.abs(shift.get(1).getAsFloat() - style.shiftY()) < 1e-4
                        && "reference".equals(providers.get(1).getAsJsonObject().get("type").getAsString())
                        && "minecraft:default".equals(providers.get(1).getAsJsonObject().get("id").getAsString());
                check(ok, path + ".json: ttf " + style.fontFile() + " size " + style.size() + " oversample " + tier
                        + " shift [0, " + style.shiftY() + "], then a reference to minecraft:default");
            }
        }
        near(TextStyle.BODY.shiftY(), 0.05, 1e-6, "body text shift puts cap height at the draw y (0.705 * 10 - 7)");
        near(TextStyle.TITLE.capHeight(), 8.8125, 1e-4, "title cap height");
    }

    static void fonts() throws Exception {
        Path fontDir = root.resolve(ASSETS + "font");
        Map<String, Integer> weights = Map.of("nunito-semibold.ttf", 600, "nunito-bold.ttf", 700, "nunito-extrabold.ttf", 800);
        List<Integer> codepoints = new ArrayList<>();
        for (int cp = 0x20; cp <= 0x7E; cp++) codepoints.add(cp);
        for (int cp = 0xA0; cp <= 0xFF; cp++) codepoints.add(cp);
        for (int cp = 0x0410; cp <= 0x044F; cp++) codepoints.add(cp);
        for (int cp : new int[] {0x0401, 0x0451, 0x2026, 0x2014, 0x00AB, 0x00BB, 0x2116, 0x20BD, 0x2190, 0x2191, 0x2192, 0x2193, 0x2605, 0x2665}) {
            codepoints.add(cp);
        }
        for (Map.Entry<String, Integer> font : weights.entrySet()) {
            Path file = fontDir.resolve(font.getKey());
            if (!Files.isRegularFile(file)) {
                check(false, "missing font " + font.getKey());
                continue;
            }
            Ttf ttf = new Ttf(Files.readAllBytes(file));
            check(ttf.u32(0) == 0x00010000L, font.getKey() + " is sfnt 0x00010000 (FreeType reports \"TrueType\")");
            for (String table : new String[] {"glyf", "loca", "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post"}) {
                check(ttf.tables.containsKey(table), font.getKey() + " has table " + table);
            }
            check(!ttf.tables.containsKey("CFF ") && !ttf.tables.containsKey("CFF2") && !ttf.tables.containsKey("fvar"),
                    font.getKey() + " is a static font with quadratic outlines (no CFF, no fvar)");
            int os2 = ttf.tables.get("OS/2")[0];
            int head = ttf.tables.get("head")[0];
            int hhea = ttf.tables.get("hhea")[0];
            check(ttf.u16(os2 + 4) == font.getValue(), font.getKey() + " usWeightClass " + font.getValue() + ", got " + ttf.u16(os2 + 4));
            int unitsPerEm = ttf.u16(head + 18);
            near(ttf.s16(os2 + 88) / (double) unitsPerEm, TextStyle.CAP_HEIGHT_EM, 1e-6, font.getKey() + " cap height matches TextStyle");
            near(ttf.s16(hhea + 4) / (double) unitsPerEm, TextStyle.ASCENT_EM, 1e-6, font.getKey() + " ascender matches TextStyle");
            near(-ttf.s16(hhea + 6) / (double) unitsPerEm, TextStyle.DESCENT_EM, 1e-6, font.getKey() + " descender matches TextStyle");
            Map<Integer, Integer> cmap = ttf.cmap();
            List<String> missing = new ArrayList<>();
            List<String> empty = new ArrayList<>();
            for (int cp : codepoints) {
                Integer glyph = cmap.get(cp);
                if (glyph == null || glyph == 0) {
                    missing.add(Integer.toHexString(cp));
                } else if (cp != 0x20 && cp != 0xA0 && ttf.glyphLength(glyph) == 0) {
                    empty.add(Integer.toHexString(cp));
                }
            }
            check(missing.isEmpty(), font.getKey() + " maps Latin, Latin-1, Cyrillic, punctuation, arrows, star and heart; missing " + missing);
            check(empty.isEmpty(), font.getKey() + " has glyf outlines for them; empty " + empty);
            check(new String(ttf.data, StandardCharsets.ISO_8859_1).contains("N\0u\0n\0i\0t\0o"), font.getKey() + " name table says Nunito");
            Font awt = Font.createFont(Font.TRUETYPE_FONT, file.toFile());
            String cyrillic = "АБВГДЕЁЖЯаяё";
            check(awt.canDisplayUpTo(cyrillic) == -1, font.getKey() + " loads in java.awt and displays Cyrillic");
        }
        // The licence travels with the fonts, as the OFL requires, under a name Minecraft's resource loader accepts:
        // an uppercase path inside a namespace is rejected, and every player's log carried an error for it.
        Path ofl = fontDir.resolve("license-ofl.txt");
        String license = Files.isRegularFile(ofl) ? Files.readString(ofl, StandardCharsets.UTF_8) : "";
        check(license.startsWith("Copyright 2014 The Nunito Project Authors") && license.contains("SIL OPEN FONT LICENSE Version 1.1"),
                "the Nunito copyright sits next to the TTFs as license-ofl.txt");
        try (var names = Files.list(fontDir)) {
            check(names.map(path -> path.getFileName().toString())
                            .noneMatch(name -> !name.equals(name.toLowerCase(java.util.Locale.ROOT))),
                    "every file in the font folder has a name the resource loader accepts");
        }
        // The same rule for the whole namespace. A folder no loader lists today - textures/gui/workshop held
        // LUCIDE-LICENSE.txt - logs nothing, and starts logging that error for every player the day an atlas or a
        // listing walks it. So no path in the jar's namespace may carry a capital letter at all.
        Path namespace = fontDir.getParent();
        try (var files = Files.walk(namespace)) {
            java.util.List<String> capitals = files.filter(Files::isRegularFile)
                    .map(path -> namespace.relativize(path).toString().replace('\\', '/'))
                    .filter(name -> !name.equals(name.toLowerCase(java.util.Locale.ROOT)))
                    .toList();
            check(capitals.isEmpty(), "every path in the music_battle namespace is lowercase: " + capitals);
        }
    }

    /** Minimal sfnt reader: table directory, cmap formats 4 and 12, loca lengths. */
    static final class Ttf {
        final byte[] data;
        final ByteBuffer buffer;
        final Map<String, int[]> tables = new HashMap<>();

        Ttf(byte[] data) {
            this.data = data;
            this.buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            int count = u16(4);
            for (int i = 0; i < count; i++) {
                int record = 12 + i * 16;
                String tag = new String(data, record, 4, StandardCharsets.ISO_8859_1);
                tables.put(tag, new int[] {(int) u32(record + 8), (int) u32(record + 12)});
            }
        }

        int u16(int offset) {
            return buffer.getShort(offset) & 0xFFFF;
        }

        int s16(int offset) {
            return buffer.getShort(offset);
        }

        long u32(int offset) {
            return buffer.getInt(offset) & 0xFFFFFFFFL;
        }

        Map<Integer, Integer> cmap() {
            Map<Integer, Integer> map = new HashMap<>();
            int base = tables.get("cmap")[0];
            int count = u16(base + 2);
            for (int i = 0; i < count; i++) {
                int platform = u16(base + 4 + i * 8);
                int encoding = u16(base + 6 + i * 8);
                int offset = base + (int) u32(base + 8 + i * 8);
                boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
                if (!unicode) {
                    continue;
                }
                int format = u16(offset);
                if (format == 4) {
                    int segments = u16(offset + 6) / 2;
                    int ends = offset + 14;
                    int starts = ends + segments * 2 + 2;
                    int deltas = starts + segments * 2;
                    int ranges = deltas + segments * 2;
                    for (int s = 0; s < segments; s++) {
                        int end = u16(ends + s * 2);
                        int start = u16(starts + s * 2);
                        int delta = s16(deltas + s * 2);
                        int range = u16(ranges + s * 2);
                        for (int c = start; c <= end && c != 0xFFFF; c++) {
                            int glyph;
                            if (range == 0) {
                                glyph = (c + delta) & 0xFFFF;
                            } else {
                                glyph = u16(ranges + s * 2 + range + (c - start) * 2);
                                if (glyph != 0) {
                                    glyph = (glyph + delta) & 0xFFFF;
                                }
                            }
                            map.putIfAbsent(c, glyph);
                        }
                    }
                } else if (format == 12) {
                    long groups = u32(offset + 12);
                    for (int g = 0; g < groups; g++) {
                        int group = offset + 16 + g * 12;
                        long start = u32(group);
                        long end = u32(group + 4);
                        long glyph = u32(group + 8);
                        for (long c = start; c <= end; c++) {
                            map.putIfAbsent((int) c, (int) (glyph + c - start));
                        }
                    }
                }
            }
            return map;
        }

        int glyphLength(int glyph) {
            int head = tables.get("head")[0];
            int loca = tables.get("loca")[0];
            boolean longOffsets = s16(head + 50) == 1;
            if (longOffsets) {
                return (int) (u32(loca + (glyph + 1) * 4) - u32(loca + glyph * 4));
            }
            return (u16(loca + (glyph + 1) * 2) - u16(loca + glyph * 2)) * 2;
        }
    }

    static void icons() throws IOException {
        Path dir = root.resolve(ASSETS + "textures/gui/workshop");
        JsonObject manifest = JsonParser.parseString(Files.readString(dir.resolve("icons.json"))).getAsJsonObject();
        JsonArray list = manifest.getAsJsonArray("icons");
        UiIcon[] icons = UiIcon.values();
        check(list.size() == icons.length, "icons.json lists " + icons.length + " icons, found " + list.size());
        for (int i = 0; i < Math.min(list.size(), icons.length); i++) {
            check(list.get(i).getAsJsonObject().get("name").getAsString().equals(icons[i].atlasName()),
                    "atlas icon " + i + " is " + icons[i].atlasName());
        }
        check(manifest.get("columns").getAsInt() == UiIcon.COLUMNS && manifest.get("rows").getAsInt() == UiIcon.rows(), "atlas grid matches UiIcon");
        check(Files.readString(dir.resolve("lucide-license.txt")).startsWith("ISC License"), "Lucide's ISC licence sits next to the atlases");
        for (int tier : UiIcon.TIERS) {
            Path png = dir.resolve("icons_" + tier + ".png");
            BufferedImage image = ImageIO.read(png.toFile());
            check(image != null && image.getWidth() == UiIcon.atlasWidth(tier) && image.getHeight() == UiIcon.atlasHeight(tier),
                    "icons_" + tier + ".png is " + UiIcon.atlasWidth(tier) + "x" + UiIcon.atlasHeight(tier));
            if (image == null) {
                continue;
            }
            JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("icons_" + tier + ".png.mcmeta"))).getAsJsonObject();
            check(meta.getAsJsonObject("texture").get("blur").getAsBoolean(), "icons_" + tier + " is sampled LINEAR (blur)");
            int dark = 0;
            int bleeding = 0;
            int opaqueCells = 0;
            int cell = UiIcon.cell(tier);
            int gutter = UiIcon.gutter(tier);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int alpha = argb >>> 24;
                    if (alpha > 0 && (argb & 0xFFFFFF) != 0xFFFFFF) {
                        dark++;
                    }
                    int cx = x % cell;
                    int cy = y % cell;
                    boolean inGutter = cx < gutter || cx >= cell - gutter || cy < gutter || cy >= cell - gutter;
                    if (inGutter && alpha != 0) {
                        bleeding++;
                    }
                }
            }
            for (int i = 0; i < icons.length; i++) {
                int ox = (i % UiIcon.COLUMNS) * cell + gutter;
                int oy = (i / UiIcon.COLUMNS) * cell + gutter;
                boolean any = false;
                for (int y = 0; y < tier && !any; y++) {
                    for (int x = 0; x < tier && !any; x++) {
                        any = (image.getRGB(ox + x, oy + y) >>> 24) > 128;
                    }
                }
                if (any) {
                    opaqueCells++;
                }
            }
            check(dark == 0, "icons_" + tier + ": every visible pixel is white, so linear filtering never darkens edges (" + dark + " are not)");
            check(bleeding == 0, "icons_" + tier + ": gutters are transparent, so neighbours never bleed (" + bleeding + " pixels)");
            check(opaqueCells == icons.length, "icons_" + tier + ": every cell holds an icon (" + opaqueCells + ")");
        }
        check(UiIcon.tierFor(8) == 16 && UiIcon.tierFor(16) == 16 && UiIcon.tierFor(16.5) == 32 && UiIcon.tierFor(64) == 64
                && UiIcon.tierFor(300) == 64, "icon tier is the smallest atlas at least as large as the drawn pixels");
        float[] uv = UiIcon.PLAY.uv(16);
        check(uv[0] == 2.0F / 160 && uv[1] == 2.0F / 80 && uv[2] == 18.0F / 160 && uv[3] == 18.0F / 80, "PLAY uv in the 16 px atlas");
        float[] last = UiIcon.FILTER.uv(64);
        check(last[2] == (7 * 80 + 8 + 64) / 640.0F && last[3] == (3 * 80 + 8 + 64) / 320.0F, "FILTER uv in the 64 px atlas");
    }

    // ---- motion -------------------------------------------------------------------------------------------

    static void motion() {
        for (Motion.Easing easing : Motion.Easing.values()) {
            near(easing.apply(0.0), 0.0, 1e-9, easing + " starts at 0");
            near(easing.apply(1.0), 1.0, 1e-9, easing + " ends at 1");
            check(easing.apply(-3) == easing.apply(0) && easing.apply(7) == easing.apply(1), easing + " clamps its input");
        }
        for (double t = 0; t <= 1.0; t += 0.05) {
            near(Motion.Easing.OUT_QUINT.apply(t), 1 - Math.pow(1 - t, 5), 1e-12, "OutQuint at " + t);
            near(Motion.Easing.OUT_QUAD.apply(t), 1 - (1 - t) * (1 - t), 1e-12, "OutQuad at " + t);
            near(Motion.Easing.IN_OUT_CUBIC.apply(t), t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2, 1e-12, "InOutCubic at " + t);
        }

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        Motion.Tween tween = new Motion.Tween(10.0, clock::get);
        check(tween.value() == 10.0F && !tween.isAnimating(), "a new tween rests at its initial value");
        tween.animateTo(110.0, 360.0, Motion.Easing.OUT_QUINT);
        check(tween.value() == 10.0F, "a tween starts at its old value");
        clock.addAndGet(180_000_000L);
        near(tween.value(), 10.0 + 100.0 * (1 - Math.pow(0.5, 5)), 1e-4, "halfway through 360 ms OutQuint");
        float before = tween.value();
        tween.animateTo(0.0, 360.0, Motion.Easing.OUT_QUINT);
        check(tween.value() == before, "retargeting mid-animation continues from the current value");
        clock.addAndGet(360_000_000L);
        check(tween.value() == 0.0F && !tween.isAnimating(), "a tween lands exactly on its target");
        tween.animateTo(1.0, 360.0, Motion.Easing.OUT_QUINT, 100.0);
        clock.addAndGet(99_000_000L);
        check(tween.value() == 0.0F && tween.isAnimating(), "a delayed tween holds during the delay");
        clock.addAndGet(361_000_000L);
        check(tween.value() == 1.0F, "a delayed tween finishes after delay plus duration");
        tween.set(5.0);
        check(tween.value() == 5.0F && !tween.isAnimating(), "set jumps without animating");

        check(tweenRun().equals(tweenRun()), "two tween runs on the same clock produce identical values");
    }

    static List<Float> tweenRun() {
        AtomicLong clock = new AtomicLong();
        Motion.Tween tween = new Motion.Tween(0.0, clock::get);
        List<Float> values = new ArrayList<>();
        for (int frame = 0; frame < 120; frame++) {
            if (frame == 10) {
                tween.animateTo(1.0, 360, Motion.Easing.OUT_QUINT);
            }
            if (frame == 25) {
                tween.animateTo(-0.5, 500, Motion.Easing.IN_OUT_SINE, 100);
            }
            clock.addAndGet(6_944_444L);
            values.add(tween.value());
        }
        return values;
    }

    static void smoothScroll() {
        AtomicLong clock = new AtomicLong();
        Motion.SmoothScroll scroll = new Motion.SmoothScroll(clock::get);
        scroll.setExtent(1000);
        scroll.onWheel(-1.0);
        near(scroll.target(), Motion.SmoothScroll.WHEEL_STEP, 1e-9, "one notch down moves the target 80 osu px (56 GUI units)");
        near(Motion.SmoothScroll.WHEEL_STEP, 56.0, 1e-4, "wheel step in GUI units");
        for (int i = 1; i <= 30; i++) {
            clock.addAndGet(16_000_000L);
            scroll.update();
            near(scroll.current(), 56.0 * (1 - Math.exp(-0.01 * 16.0 * i)), 1e-6, "wheel scroll follows the exponential after " + (16 * i) + " ms");
        }

        double at60 = frameRun(60);
        double at144 = frameRun(144);
        double at30 = frameRun(30);
        near(at60, at144, 1e-6, "the same wall time gives the same position at 60 and 144 fps");
        near(at60, at30, 1e-6, "and at 30 fps");
        check(frameRun(60) == at60, "scrolling is deterministic for the same clock");

        AtomicLong preciseClock = new AtomicLong();
        Motion.SmoothScroll precise = new Motion.SmoothScroll(preciseClock::get);
        precise.setExtent(1000);
        precise.onWheel(-0.5);
        preciseClock.addAndGet(20_000_000L);
        precise.update();
        near(precise.current(), 28.0 * (1 - Math.exp(-0.05 * 20.0)), 1e-6, "fractional (trackpad) deltas use the faster 0.05 decay");

        AtomicLong hitchClock = new AtomicLong();
        Motion.SmoothScroll hitch = new Motion.SmoothScroll(hitchClock::get);
        hitch.setExtent(1000);
        hitch.onWheel(-1.0);
        hitchClock.addAndGet(5_000_000_000L);
        hitch.update();
        near(hitch.current(), 56.0 * (1 - Math.exp(-0.01 * 100.0)), 1e-6, "a 5 s hitch advances the model by 100 ms only");

        AtomicLong bounceClock = new AtomicLong();
        Motion.SmoothScroll bounce = new Motion.SmoothScroll(bounceClock::get);
        bounce.setExtent(100);
        bounce.scrollTo(-200, true);
        check(bounce.target() == -200, "the target may overshoot the start by up to the clamp extension");
        bounce.scrollTo(-10_000, true);
        near(bounce.target(), -Motion.SmoothScroll.CLAMP_EXTENSION, 1e-9, "but no further than 500 osu px");
        double minimum = 0;
        for (int i = 0; i < 400; i++) {
            bounceClock.addAndGet(16_666_667L);
            minimum = Math.min(minimum, bounce.update());
        }
        check(minimum < -1.0, "overscroll actually leaves the bounds before the clamping force wins (" + minimum + ")");
        near(bounce.current(), 0.0, 1e-3, "overscroll is pulled back to the start");
        near(bounce.target(), 0.0, 1e-3, "and the target settles on the bound");

        bounce.dragTo(250);
        check(bounce.current() == 100 && bounce.target() == 100, "dragging follows the pointer within the bounds");
        bounce.endDrag();

        AtomicLong snapClock = new AtomicLong();
        Motion.SmoothScroll snap = new Motion.SmoothScroll(snapClock::get);
        snap.setExtent(1000);
        snap.scrollTo(10.1, false);
        check(snap.current() == 10.1, "an unanimated jump lands immediately");
        near(snap.snapped(3.0), 10.0, 1e-6, "snapped rounds to whole physical pixels at GUI scale 3");
        near(snap.snapped(2.0), 10.0, 1e-6, "snapped rounds to whole physical pixels at GUI scale 2");
    }

    static double frameRun(int fps) {
        AtomicLong clock = new AtomicLong();
        Motion.SmoothScroll scroll = new Motion.SmoothScroll(clock::get);
        scroll.setExtent(5000);
        scroll.onWheel(-3.0);
        for (int frame = 1; frame <= fps; frame++) {
            clock.set(Math.round(frame * 1_000_000_000.0 / fps));
            scroll.update();
        }
        return scroll.current();
    }

    // ---- card grid ----------------------------------------------------------------------------------------

    static void cardGrid() {
        CardGrid grid = new CardGrid(243.0F, 56.0F, 7.0F, 3);
        grid.layout(14.0F, 40.0F, 760.0F, 300.0F, 60);
        check(grid.columns() == 3, "760 units fit 3 columns of 243 with gap 7");
        near(grid.cardWidth(), (760.0 - 14.0) / 3.0, 1e-4, "cards share the width minus gaps");
        check(grid.rows() == 20, "60 cards in 3 columns make 20 rows");
        near(grid.contentHeight(), 20 * 56 + 19 * 7, 1e-4, "content height has no trailing gap");
        near(grid.scrollExtent(), 20 * 56 + 19 * 7 - 300, 1e-4, "scroll extent is content minus viewport");
        grid.layout(0, 0, 500, 300, 60);
        check(grid.columns() == 2, "500 units fit 2 columns");
        grid.layout(0, 0, 200, 300, 60);
        check(grid.columns() == 1 && grid.cardWidth() == 200.0F, "a narrow viewport still shows one full-width column");
        grid.layout(0, 0, 3000, 300, 60);
        check(grid.columns() == 3, "the column count is capped at 3");

        grid.layout(14.0F, 40.0F, 760.0F, 300.0F, 60);
        check(grid.firstVisible(0) == 0 && grid.endVisible(0) == 15, "at the top rows 0..4 intersect a 300-unit viewport");
        check(grid.firstVisible(56) == 3 && grid.firstVisible(55.9) == 0, "a row whose bottom touches the viewport top is not visible");
        check(grid.endVisible(15) == 15 && grid.endVisible(16) == 18, "a row starting exactly at the bottom edge is not visible");

        int rangeMismatches = 0;
        for (int count : new int[] {0, 1, 2, 59, 60, 61}) {
            grid.setCount(count);
            for (double scroll = -80; scroll <= 1100; scroll += 0.5) {
                int first = -1;
                int end = 0;
                for (int i = 0; i < count; i++) {
                    double top = grid.row(i) * 63.0 - scroll;
                    boolean visible = top + 56.0 > 0 && top < 300.0;
                    if (visible) {
                        if (first < 0) {
                            first = i;
                        }
                        end = i + 1;
                    }
                }
                boolean ok = first < 0
                        ? grid.firstVisible(scroll) >= grid.endVisible(scroll)
                        : grid.firstVisible(scroll) == first && grid.endVisible(scroll) == end;
                if (!ok) {
                    rangeMismatches++;
                }
            }
        }
        check(rangeMismatches == 0, "visible range equals brute force for 6 item counts and 2 362 scroll offsets, " + rangeMismatches + " mismatches");
        grid.setCount(60);
        check(grid.endPrepared(0, 2) == 21, "preparing two rows ahead extends the range by two rows");

        double scroll = 37.5;
        float cardWidth = grid.cardWidth();
        check(grid.hitTest(14 + cardWidth + 7 + 10, 40 + 63 - scroll + 10, scroll) == 4, "the pointer over row 1, column 1 hits card 4");
        check(grid.hitTest(14 + cardWidth + 3, 40 + 63 - scroll + 10, scroll) == -1, "the horizontal gap hits nothing");
        check(grid.hitTest(14 + 10, 40 + 56 + 3 - scroll, scroll) == -1, "the vertical gap hits nothing");
        check(grid.hitTest(14 + 10, 39.0, scroll) == -1, "above the viewport hits nothing, even over a scrolled card");
        check(grid.hitTest(14 + 760 + 1, 60, scroll) == -1 && grid.hitTest(5, 60, scroll) == -1, "outside the viewport sideways hits nothing");
        grid.setCount(59);
        check(grid.hitTest(14 + 2 * (cardWidth + 7) + 5, 40 + 19 * 63 - 953 + 5, 953) == -1, "an empty slot after the last card hits nothing");
        check(grid.hitTest(14 + (cardWidth + 7) + 5, 40 + 19 * 63 - 953 + 5, 953) == 58, "the last card is hit");
        grid.setCount(0);
        check(grid.hitTest(50, 60, 0) == -1 && grid.endVisible(0) == 0, "an empty grid hits and shows nothing");

        grid.setCount(60);
        Random random = new Random(11);
        int hitMismatches = 0;
        for (int i = 0; i < 50_000; i++) {
            double mx = random.nextDouble() * 800;
            double my = random.nextDouble() * 400;
            double s = random.nextDouble() * 953;
            int expected = -1;
            if (mx >= 14 && mx < 774 && my >= 40 && my < 340) {
                for (int index = 0; index < 60; index++) {
                    double x = 14 + grid.column(index) * (cardWidth + 7.0);
                    double y = 40 + grid.row(index) * 63.0 - s;
                    if (mx >= x && mx < x + cardWidth && my >= y && my < y + 56) {
                        expected = index;
                    }
                }
            }
            if (grid.hitTest(mx, my, s) != expected) {
                hitMismatches++;
            }
        }
        check(hitMismatches == 0, "hit-testing equals brute force for 50 000 random pointers, " + hitMismatches + " mismatches");
    }

    // ---- shader guard ---------------------------------------------------------------------------------------

    static void shaderGuard() {
        for (Exception failure : new Exception[] {new IOException("broken ui_sdf.fsh"), new IllegalStateException("driver rejected fwidth")}) {
            AtomicBoolean registered = new AtomicBoolean();
            AtomicReference<Throwable> reported = new AtomicReference<>();
            boolean result;
            try {
                result = UiShaderGuard.register("ui_sdf", () -> {
                    throw failure;
                }, shader -> registered.set(true), reported::set);
            } catch (Throwable escaped) {
                check(false, "a " + failure.getClass().getSimpleName() + " escaped the shader guard: " + escaped);
                continue;
            }
            check(!result && !registered.get() && reported.get() == failure,
                    "a " + failure.getClass().getSimpleName() + " from the factory leaves the shader unregistered and is reported");
        }
        AtomicBoolean linkageReported = new AtomicBoolean();
        boolean linkage = UiShaderGuard.register("ui_sdf", () -> {
            throw new NoClassDefFoundError("missing GL binding");
        }, shader -> { }, failure -> linkageReported.set(true));
        check(!linkage && linkageReported.get(), "a linkage error is contained too");

        AtomicBoolean closed = new AtomicBoolean();
        AutoCloseable created = () -> closed.set(true);
        boolean registerFailed = UiShaderGuard.register("ui_rounded_image", () -> created, shader -> {
            throw new IllegalArgumentException("register failed");
        }, failure -> { });
        check(!registerFailed && closed.get(), "a shader that was built but could not be registered is closed");

        AtomicInteger calls = new AtomicInteger();
        boolean ok = UiShaderGuard.register("ui_sdf", () -> "shader", shader -> calls.incrementAndGet(), failure -> { });
        check(ok && calls.get() == 1, "a working factory registers exactly once");

        boolean noisy;
        try {
            noisy = UiShaderGuard.register("ui_sdf", () -> null, shader -> { }, failure -> {
                throw new RuntimeException("logger broke");
            });
            check(!noisy, "a null shader counts as a failure");
        } catch (Throwable escaped) {
            check(false, "an exception from the failure callback escaped: " + escaped);
        }
    }
}
