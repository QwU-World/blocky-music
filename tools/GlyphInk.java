package ru.gora.musicbattle.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Where the ink of a line of toolkit text lands, glyph by glyph, as the game draws it: Minecraft's FreeType provider
 * opened on the bundled face exactly as the style's font JSON opens it (its size, the oversample tier and its vertical
 * {@code shift}, which puts the cap line at the y the text is drawn at), each glyph's bitmap box placed the way
 * BakedGlyph places it ({@code left = bearingX}, {@code top = 7 - bearingY}) and the pen moved by the glyph's advance,
 * as Font's string renderer moves it. The start is snapped to the GUI scale's pixels as TextStyles.queue snaps it.
 * GameTextMeasurer gives the widths; this gives the boxes that a knob, a chip or a note must keep clear of.
 */
public final class GlyphInk {
    /** One glyph's ink, in GUI units. */
    public record Box(int codePoint, float left, float top, float right, float bottom) {
        public boolean overlaps(float x0, float y0, float x1, float y1) {
            return left < x1 && x0 < right && top < y1 && y0 < bottom;
        }
    }

    public final int tier;
    private final TrueTypeGlyphProvider[] providers = new TrueTypeGlyphProvider[TextStyle.values().length];
    private final Map<String, List<Box>> cache = new HashMap<>();

    public GlyphInk(Path fonts, int tier) throws Exception {
        this.tier = tier;
        for (TextStyle style : TextStyle.values()) {
            providers[style.ordinal()] = open(fonts.resolve(style.fontFile() + ".ttf"), style, tier);
        }
    }

    private static TrueTypeGlyphProvider open(Path file, TextStyle style, int tier) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        synchronized (FreeTypeUtil.LIBRARY_LOCK) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0L, pointer),
                        "Initializing font face");
                FT_Face face = FT_Face.create(pointer.get());
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE), "Find unicode charmap");
                return new TrueTypeGlyphProvider(buffer, face, style.size(), tier, 0.0F, style.shiftY(), "");
            }
        }
    }

    /** The GUI coordinate TextStyles.snap makes of {@code value} at this tier's GUI scale. */
    public float snap(float value) {
        return (float) (Math.round(value * (double) tier) / (double) tier);
    }

    /**
     * The ink of {@code text} drawn with its cap line at {@code y} from {@code x}, both snapped first; spaces have none.
     * A code point the face lacks fails the check rather than fall back to a guess.
     */
    public List<Box> line(String text, TextStyle style, float x, float y) {
        List<Box> local = cache.computeIfAbsent(style.name() + '\u0000' + text, key -> measure(text, style));
        float sx = snap(x);
        float sy = snap(y);
        List<Box> placed = new ArrayList<>(local.size());
        for (Box box : local) {
            placed.add(new Box(box.codePoint(), box.left() + sx, box.top() + sy, box.right() + sx, box.bottom() + sy));
        }
        return placed;
    }

    private List<Box> measure(String text, TextStyle style) {
        List<Box> boxes = new ArrayList<>();
        float pen = 0F;
        for (int codePoint : text.codePoints().toArray()) {
            GlyphInfo glyph = providers[style.ordinal()].getGlyph(codePoint);
            if (glyph == null) {
                throw new AssertionError("the " + style + " face has no glyph U+" + Integer.toHexString(codePoint)
                        + " for '" + text + "'");
            }
            if (!(glyph instanceof GlyphInfo.SpaceGlyphInfo)) {
                float origin = pen;
                float[] box = new float[4];
                glyph.bake(sheet -> {
                    box[0] = origin + sheet.getLeft();
                    box[1] = sheet.getTop();
                    box[2] = origin + sheet.getRight();
                    box[3] = sheet.getBottom();
                    return null;
                });
                boxes.add(new Box(codePoint, box[0], box[1], box[2], box[3]));
            }
            pen += glyph.getAdvance();
        }
        return boxes;
    }

    /** The union of a line's ink, or null for a line with none. */
    public static float[] bounds(List<Box> boxes) {
        if (boxes.isEmpty()) {
            return null;
        }
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Box box : boxes) {
            bounds[0] = Math.min(bounds[0], box.left());
            bounds[1] = Math.min(bounds[1], box.top());
            bounds[2] = Math.max(bounds[2], box.right());
            bounds[3] = Math.max(bounds[3], box.bottom());
        }
        return bounds;
    }
}
