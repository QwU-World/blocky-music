package ru.gora.musicbattle.client;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Minecraft's width of a line: its FreeType glyph provider opened on the bundled face exactly as the game opens a
 * {@code ttf} provider (TrueTypeGlyphProviderDefinition), at the size and oversample tier of the style's font JSON,
 * and the line's glyph advances summed as Font's StringSplitter sums them. A code point the face lacks fails the
 * check rather than fall back to a guess.
 */
public final class GameTextMeasurer implements TextMeasurer {
    public final int tier;
    private final TrueTypeGlyphProvider[] providers = new TrueTypeGlyphProvider[TextStyle.values().length];
    private final Map<String, Float> cache = new HashMap<>();

    public GameTextMeasurer(Path fonts, int tier) throws Exception {
        this.tier = tier;
        for (TextStyle style : TextStyle.values()) {
            providers[style.ordinal()] = open(fonts.resolve(style.fontFile() + ".ttf"), style.size(), tier);
        }
    }

    private static TrueTypeGlyphProvider open(Path file, float size, int tier) throws Exception {
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
                return new TrueTypeGlyphProvider(buffer, face, size, tier, 0.0F, 0.0F, "");
            }
        }
    }

    @Override
    public float width(String text, TextStyle style) {
        if (text == null || text.isEmpty()) {
            return 0F;
        }
        return cache.computeIfAbsent(style.name() + "\u0000" + text, key -> {
            float width = 0F;
            for (int codePoint : text.codePoints().toArray()) {
                GlyphInfo glyph = providers[style.ordinal()].getGlyph(codePoint);
                if (glyph == null) {
                    throw new AssertionError("the " + style + " face has no glyph U+" + Integer.toHexString(codePoint)
                            + " for '" + text + "'");
                }
                width += glyph.getAdvance();
            }
            return width;
        });
    }
}
