package ru.gora.musicbattle.client.ui;

import com.mojang.math.Axis;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * A look-and-performance check of the Workshop UI toolkit for the owner ({@code /musicbattle debugui}): shapes,
 * pills, animated rings, the difficulty spectrum, every text style at the current font tier, images, heads,
 * icons, and a virtualised grid of 60 placeholder cards with smooth scrolling and a real audio preview. The
 * header shows the UI's CPU time and draw calls. Nothing here touches the network.
 */
public final class WorkshopUiDebugScreen extends UiLayer {
    private static final String KEY = "screen.music_battle.workshop.debug.";
    private static final int CARD_COUNT = 60;
    private static final float HEADER_HEIGHT = 32.0F;
    private static final float FOOTER_HEIGHT = 16.0F;
    private static final float PAD = 8.0F;
    private static final float CARD_HEIGHT = 56.0F;
    private static final ResourceLocation PREVIEW_SOUND = ResourceLocation.withDefaultNamespace("sounds/records/cat.ogg");
    private static final long PREVIEW_MS = 30_000L;
    private static final String[] STATUS_KEYS = {"featured", "community", "wip", "pending", "hidden"};
    private static final int[] STATUS_COLOURS = {
            Palette.ST_FEATURED, Palette.ST_COMMUNITY, Palette.ST_WIP, Palette.ST_PENDING, Palette.ST_HIDDEN
    };
    private static final double[] STAR_PILLS = {0.75, 2.3, 3.9, 5.25, 6.72, 8.4, 8.95, 10.3};
    private static final int[] SWATCHES = {
            Palette.BG_0, Palette.BG_1, Palette.BG_2, Palette.BG_3, Palette.BG_4, Palette.LINE,
            Palette.TEXT_1, Palette.TEXT_2, Palette.TEXT_3, Palette.TEXT_MUTE,
            Palette.ACCENT, Palette.ACCENT_STRONG, Palette.ACCENT_DEEP, Palette.LINK,
            Palette.ST_FEATURED, Palette.ST_COMMUNITY, Palette.ST_WIP, Palette.ST_PENDING, Palette.ST_HIDDEN,
            Palette.BADGE_LIBRARY, Palette.BADGE_EXPLICIT, Palette.BADGE_AI, Palette.WARN, Palette.DANGER, Palette.OK,
            Palette.RATING_BAR
    };

    private final Screen parent;
    private final CardGrid grid = new CardGrid(170.0F, CARD_HEIGHT, 7.0F, 3);
    private final Motion.SmoothScroll gridScroll = new Motion.SmoothScroll();
    private final Motion.SmoothScroll panelScroll = new Motion.SmoothScroll();
    private final Card[] cards = new Card[CARD_COUNT];
    private final Motion.Tween hoverBox = new Motion.Tween(0.0);
    private final Motion.Tween imageDim = new Motion.Tween(0.0);
    private final long openedNanos = Util.getNanos();
    private final TextStyles.Label[] styleSamples = new TextStyles.Label[TextStyle.values().length];
    private final TextStyles.Label longTitle;
    private final TextStyles.Label heading;
    private final Component[] statusLabels = new Component[STATUS_KEYS.length];
    private final String glyphsLatin;
    private final String glyphsCyrillic;
    private final String glyphsSymbols;

    private float panelX;
    private float panelY;
    private float panelWidth;
    private float panelHeight;
    private float panelContent;
    private float gridX;
    private float gridY;
    private float gridWidth;
    private float gridHeight;
    private int hoveredCard = -1;
    private boolean hoverBoxHovered;
    private boolean imageHovered;
    private boolean draggingScrollbar;
    private long statsNanos;
    private Component statsLine = Component.empty();
    private Component scaleLine = Component.empty();
    private byte[] previewBytes;
    private boolean previewLoading;
    private String previewWanted;

    public WorkshopUiDebugScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        for (int i = 0; i < CARD_COUNT; i++) {
            cards[i] = new Card(i);
        }
        for (TextStyle style : TextStyle.values()) {
            styleSamples[style.ordinal()] = new TextStyles.Label(Component.translatable(KEY + "sample"), style);
        }
        longTitle = new TextStyles.Label(Component.translatable(KEY + "long_title"), TextStyle.TITLE);
        heading = new TextStyles.Label(Component.translatable(KEY + "title"), TextStyle.DISPLAY);
        StringBuilder latin = new StringBuilder();
        for (char c = 'A'; c <= 'Z'; c++) {
            latin.append(c);
        }
        latin.append(' ');
        for (char c = 'a'; c <= 'z'; c++) {
            latin.append(c);
        }
        glyphsLatin = latin.append(" 0123456789").toString();
        StringBuilder cyrillic = new StringBuilder();
        for (int cp = 0x0410; cp <= 0x042F; cp++) {
            cyrillic.appendCodePoint(cp);
        }
        cyrillic.append(' ');
        for (int cp = 0x0430; cp <= 0x044F; cp++) {
            cyrillic.appendCodePoint(cp);
        }
        glyphsCyrillic = cyrillic.append(' ').appendCodePoint(0x0401).appendCodePoint(0x0451).toString();
        glyphsSymbols = "★ ♥ ← ↑ → ↓ № ₽ € … — «» „“ "
                + "± × · 日本語";
    }

    @Override
    protected boolean countDraws() {
        return true;
    }

    @Override
    protected void layout() {
        float top = HEADER_HEIGHT + PAD;
        float bottom = this.height - FOOTER_HEIGHT;
        float available = this.width - PAD * 3 - 6.0F;
        panelX = PAD;
        panelY = top;
        panelWidth = Math.max(140.0F, Math.min(300.0F, available * 0.45F));
        panelHeight = Math.max(20.0F, bottom - top - 4.0F);
        gridX = panelX + panelWidth + PAD;
        gridY = top;
        gridWidth = Math.max(40.0F, this.width - gridX - PAD - 6.0F);
        gridHeight = panelHeight;
        grid.layout(gridX, gridY, gridWidth, gridHeight, CARD_COUNT);
        gridScroll.setExtent(grid.scrollExtent());
        panelScroll.setExtent(Math.max(0.0F, panelContent - panelHeight));
    }

    @Override
    protected void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        double scale = guiScale();
        gridScroll.update();
        panelScroll.update();
        float gridOffset = gridScroll.snapped(scale);
        float panelOffset = panelScroll.snapped(scale);
        hoveredCard = draggingScrollbar ? -1 : grid.hitTest(mouseX, mouseY, gridOffset);
        if (hoveredCard >= 0) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        updateStats();

        drawHeader(g);
        drawPanel(g, mouseX, mouseY, panelOffset);
        drawGrid(g, gridOffset);
        drawScrollbar(g, gridOffset);
        TextStyles.queue(g, TextStyles.styled(Component.translatable(KEY + "hint"), TextStyle.CAPTION).getVisualOrderText(),
                PAD, this.height - FOOTER_HEIGHT + (FOOTER_HEIGHT - TextStyle.CAPTION.capHeight()) * 0.5F - 1.0F, Palette.TEXT_MUTE);
    }

    // ---- header ---------------------------------------------------------------------------------------------

    private void updateStats() {
        long now = Util.getNanos();
        if (now - statsNanos < 250_000_000L) {
            return;
        }
        statsNanos = now;
        int others = lastBatches() - lastShapeBatches() - lastTextBatches() - lastIconBatches();
        statsLine = Component.translatable(KEY + "stats",
                String.format(Locale.ROOT, "%.2f", frameMillis()),
                String.valueOf(Minecraft.getInstance().getFps()),
                String.valueOf(lastBatches()), String.valueOf(lastShapeBatches()), String.valueOf(lastTextBatches()),
                String.valueOf(lastIconBatches()), String.valueOf(others),
                String.valueOf(lastImages()), String.valueOf(lastShapes()),
                String.valueOf(CoverCache.textureCount()),
                String.format(Locale.ROOT, "%.1f", CoverCache.gpuBytes() / (1024.0 * 1024.0)));
        String sdfKey = UiSdf.forceFallback() ? "sdf_forced" : UiSdf.shader() == null ? "sdf_missing" : "sdf_on";
        scaleLine = Component.translatable(KEY + "scale", String.format(Locale.ROOT, "%.0f", guiScale()),
                        String.valueOf(TextStyles.tier()), String.valueOf(UiIcon.tierFor(9.0 * guiScale())))
                .append("  ·  ").append(Component.translatable(KEY + sdfKey));
    }

    private void drawHeader(GuiGraphics g) {
        RoundedRect.fill(g, 0, 0, this.width, HEADER_HEIGHT, 0, Palette.BG_1);
        RoundedRect.fill(g, 0, HEADER_HEIGHT - 1.0F, this.width, 1.0F, 0, Palette.LINE);
        float right = this.width - PAD;
        FormattedCharSequence stats = TextStyles.ellipsize(TextStyles.styled(statsLine, TextStyle.CAPTION), this.width * 0.62F);
        FormattedCharSequence scaleText = TextStyles.ellipsize(TextStyles.styled(scaleLine, TextStyle.CAPTION), this.width * 0.62F);
        TextStyles.queueRight(g, stats, right, 7.0F, Palette.TEXT_2);
        int sdfColour = UiSdf.available() ? Palette.OK : Palette.WARN;
        TextStyles.queueRight(g, scaleText, right, 19.0F, sdfColour);
        float headingRoom = this.width * 0.36F - PAD;
        heading.queue(g, PAD, TextStyles.centeredCapY(TextStyle.DISPLAY, 0, HEADER_HEIGHT), headingRoom, Palette.TEXT_1);
    }

    // ---- left panel: the showcase -------------------------------------------------------------------------

    private void drawPanel(GuiGraphics g, double mouseX, double mouseY, float offset) {
        RoundedRect.fill(g, panelX, panelY, panelWidth, panelHeight, Palette.RADIUS_LG, Palette.BG_1);
        UiClip.push(g, panelX, panelY, panelX + panelWidth, panelY + panelHeight);
        boolean mouseInPanel = mouseX >= panelX && mouseX < panelX + panelWidth && mouseY >= panelY && mouseY < panelY + panelHeight;
        float x = panelX + PAD;
        float inner = panelWidth - PAD * 2;
        float y = panelY + PAD - offset;
        y = drawShapes(g, x, y, inner, mouseInPanel, mouseX, mouseY);
        y = drawRings(g, x, y);
        y = drawSpectrum(g, x, y, inner);
        y = drawPalette(g, x, y, inner);
        y = drawText(g, x, y, inner);
        y = drawImages(g, x, y, mouseInPanel, mouseX, mouseY);
        y = drawIcons(g, x, y, inner);
        UiClip.pop(g);
        float content = y + offset - panelY + PAD;
        if (Math.abs(content - panelContent) > 0.5F) {
            panelContent = content;
            panelScroll.setExtent(Math.max(0.0F, panelContent - panelHeight));
        }
    }

    private float section(GuiGraphics g, String name, float x, float y) {
        TextStyles.queue(g, TextStyles.translated(KEY + "section." + name, TextStyle.TITLE), x, y, Palette.TEXT_1);
        return y + TextStyle.TITLE.capHeight() + 7.0F;
    }

    private float drawShapes(GuiGraphics g, float x, float y, float inner, boolean mouseInPanel, double mouseX, double mouseY) {
        y = section(g, "shapes", x, y);
        float[] radii = {0.0F, Palette.RADIUS_SM, Palette.RADIUS_MD, Palette.RADIUS_LG};
        for (int i = 0; i < radii.length; i++) {
            float bx = x + i * 44.0F;
            RoundedRect.fill(g, bx, y, 38.0F, 26.0F, radii[i], Palette.BG_3);
            RoundedRect.stroke(g, bx, y, 38.0F, 26.0F, radii[i], 1.0F, Palette.LINE);
        }
        y += 34.0F;
        RoundedRect.stroke(g, x, y, 60.0F, 26.0F, Palette.RADIUS_MD, 1.5F, Palette.ACCENT);
        RoundedRect.shadow(g, x + 70.0F, y + 3.0F, 50.0F, 26.0F, Palette.RADIUS_MD, 6.0F, Palette.SHADOW_LIFTED);
        RoundedRect.fill(g, x + 70.0F, y, 50.0F, 26.0F, Palette.RADIUS_MD, Palette.BG_4);

        float boxX = x + 130.0F;
        float grow = hoverBox.value();
        float boxWidth = 64.0F + 24.0F * grow;
        boolean over = mouseInPanel && mouseX >= boxX && mouseX < boxX + boxWidth && mouseY >= y && mouseY < y + 26.0F;
        if (over != hoverBoxHovered) {
            hoverBoxHovered = over;
            hoverBox.animateTo(over ? 1.0 : 0.0, Palette.T_CARD, Motion.Easing.OUT_QUINT, over ? 100.0 : 0.0);
        }
        if (over) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        if (boxX + boxWidth <= x + inner + PAD) {
            RoundedRect.shadow(g, boxX, y + 2.0F, boxWidth, 26.0F, Palette.RADIUS_MD, 8.0F * grow, Palette.fade(Palette.SHADOW_LIFTED, grow));
            RoundedRect.fill(g, boxX, y, boxWidth, 26.0F, Palette.RADIUS_MD, Palette.mix(Palette.BG_3, Palette.ACCENT_STRONG, grow));
            TextStyles.queueCentered(g, TextStyles.translated(KEY + "hover", TextStyle.BODY).getVisualOrderText(),
                    boxX + boxWidth * 0.5F, TextStyles.centeredCapY(TextStyle.BODY, y, 26.0F), Palette.TEXT_1);
        }
        y += 34.0F;

        float px = x;
        float pillHeight = TextStyle.PILL.capHeight() + 5.0F;
        for (int i = 0; i < STATUS_KEYS.length; i++) {
            if (statusLabels[i] == null) {
                statusLabels[i] = Component.translatable("music_battle.workshop.status." + STATUS_KEYS[i]);
            }
            String upper = statusLabels[i].getString().toUpperCase(Locale.ROOT);
            FormattedCharSequence text = TextStyles.text(upper, TextStyle.PILL).getVisualOrderText();
            float width = TextStyles.width(text) + 8.0F;
            if (px + width > x + inner && px > x) {
                px = x;
                y += pillHeight + 4.0F;
            }
            RoundedRect.pill(g, px, y, width, pillHeight, STATUS_COLOURS[i]);
            TextStyles.queue(g, text, px + 4.0F, TextStyles.centeredCapY(TextStyle.PILL, y, pillHeight), Palette.ON_PILL);
            px += width + 4.0F;
        }
        return y + pillHeight + 12.0F;
    }

    private float drawRings(GuiGraphics g, float x, float y) {
        y = section(g, "rings", x, y);
        double seconds = (Util.getNanos() - openedNanos) / 1_000_000_000.0;
        float radius = 13.0F;
        float cy = y + radius;
        float cx = x + radius;
        RoundedRect.circle(g, cx, cy, radius, Palette.withAlpha(Palette.BG_0, 0.6F));
        Ring.draw(g, cx, cy, radius, Ring.OSU_PREVIEW_THICKNESS, (float) (seconds % 4.0 / 4.0), Palette.ACCENT);
        UiIcons.drawCentered(g, UiIcon.PAUSE, cx, cy, 9.0F, Palette.TEXT_1);
        cx += 34.0F;
        Ring.track(g, cx, cy, radius, 0.5F, Palette.withAlpha(Palette.LINE, 0.6F));
        Ring.draw(g, cx, cy, radius, 0.5F, (float) (seconds % 3.0 / 3.0), Palette.ST_COMMUNITY);
        cx += 34.0F;
        Ring.draw(g, cx, cy, radius, 1.0F, (float) (seconds % 5.0 / 5.0), Palette.ST_FEATURED);
        cx += 34.0F;
        Ring.track(g, cx, cy, radius, 0.28F, Palette.BG_4);
        Ring.draw(g, cx, cy, radius, 0.28F, (float) (0.5 + 0.5 * Math.sin(seconds * 1.7)), Palette.DANGER);
        cx += 34.0F;
        drawSpinner(g, cx, cy, radius * 0.7F, seconds, Palette.ACCENT);
        return y + radius * 2 + 12.0F;
    }

    private static void drawSpinner(GuiGraphics g, float cx, float cy, float radius, double seconds, int argb) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees((float) (seconds * 360.0 % 360.0)));
        Ring.draw(g, 0.0F, 0.0F, radius, 0.3F, 0.3F, argb);
        g.pose().popPose();
    }

    private float drawSpectrum(GuiGraphics g, float x, float y, float inner) {
        y = section(g, "spectrum", x, y);
        for (int i = 0; i <= 24; i++) {
            StarPill.dot(g, x + i * (StarPill.DOT_STEP + 0.5F), y, i * 0.5, Palette.BG_1);
        }
        y += 12.0F;
        float barWidth = Math.min(inner, 220.0F);
        int segments = 110;
        float step = barWidth / segments;
        for (int i = 0; i < segments; i++) {
            // Each segment overlaps the next by a unit, so the anti-aliased seams between them never show the panel.
            float overlap = i < segments - 1 ? 1.0F : 0.0F;
            RoundedRect.fill(g, x + i * step, y, step + overlap, 6.0F, 0.0F, Spectrum.argb(10.0 * i / (segments - 1)));
        }
        y += 11.0F;
        float px = x;
        for (double stars : STAR_PILLS) {
            float width = StarPill.width(stars);
            if (px + width > x + inner && px > x) {
                px = x;
                y += StarPill.HEIGHT + 4.0F;
            }
            StarPill.shapes(g, px, y, stars);
            StarPill.text(g, px, y, stars);
            px += width + 4.0F;
        }
        return y + StarPill.HEIGHT + 12.0F;
    }

    private float drawPalette(GuiGraphics g, float x, float y, float inner) {
        y = section(g, "palette", x, y);
        float sx = x;
        for (int colour : SWATCHES) {
            if (sx + 14.0F > x + inner) {
                sx = x;
                y += 14.0F;
            }
            RoundedRect.fill(g, sx, y, 12.0F, 10.0F, Palette.RADIUS_SM * 0.7F, colour);
            RoundedRect.stroke(g, sx, y, 12.0F, 10.0F, Palette.RADIUS_SM * 0.7F, 0.5F, Palette.withAlpha(Palette.TEXT_1, 0.12F));
            sx += 14.0F;
        }
        return y + 22.0F;
    }

    private float drawText(GuiGraphics g, float x, float y, float inner) {
        y = section(g, "text", x, y);
        int tier = TextStyles.tier();
        for (TextStyle style : TextStyle.values()) {
            String caption = String.format(Locale.ROOT, "%s · %s · Nunito %d · x%d",
                    style.id(), trim(style.size()), style.weight(), tier);
            TextStyles.queue(g, TextStyles.text(caption, TextStyle.CAPTION), x, y, Palette.TEXT_MUTE);
            y += TextStyle.CAPTION.lineHeight();
            styleSamples[style.ordinal()].queue(g, x, y, inner, Palette.TEXT_1);
            y += style.lineHeight() + 3.0F;
        }
        y += 2.0F;
        for (String glyphs : new String[] {glyphsLatin, glyphsCyrillic, glyphsSymbols}) {
            List<FormattedCharSequence> lines = TextStyles.wrap(TextStyles.text(glyphs, TextStyle.BODY), inner);
            y += TextStyles.queueLines(g, lines, x, y, TextStyle.BODY.lineHeight(), Palette.TEXT_2);
        }
        y += 4.0F;
        longTitle.queue(g, x, y, inner * 0.7F, Palette.TEXT_1);
        y += TextStyle.TITLE.lineHeight() + 2.0F;
        List<FormattedCharSequence> paragraph = TextStyles.wrap(TextStyles.translated(KEY + "wrap", TextStyle.BODY), inner);
        y += TextStyles.queueLines(g, paragraph, x, y, TextStyle.BODY.lineHeight(), Palette.TEXT_3);
        return y + 10.0F;
    }

    private float drawImages(GuiGraphics g, float x, float y, boolean mouseInPanel, double mouseX, double mouseY) {
        y = section(g, "images", x, y);
        float size = 48.0F;
        boolean over = mouseInPanel && mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        if (over != imageHovered) {
            imageHovered = over;
            imageDim.animateTo(over ? 0.6 : 0.0, Palette.T_CARD, Motion.Easing.OUT_QUINT);
        }
        ResourceLocation first = cover(0);
        ResourceLocation second = cover(1);
        ResourceLocation third = cover(2);
        if (first != null) {
            RoundedImage.draw(g, first, x, y, size, size, Palette.RADIUS_MD, imageDim.value(), 1.0F);
        } else {
            RoundedRect.fill(g, x, y, size, size, Palette.RADIUS_MD, Palette.BG_3);
        }
        if (over) {
            RoundedRect.circle(g, x + size * 0.5F, y + size * 0.5F, 11.0F, Palette.withAlpha(Palette.BG_0, 0.5F));
            UiIcons.drawCentered(g, UiIcon.PLAY, x + size * 0.5F + 0.7F, y + size * 0.5F, 10.0F, Palette.TEXT_1);
        }
        float gx = x + size + 8.0F;
        RoundedRect.fill(g, gx, y, 96.0F, size, Palette.RADIUS_MD, Palette.BG_3);
        if (second != null) {
            RoundedImage.draw(g, second, gx, y, 96.0F, size, 0.0F, 0.25F, 1.0F, 0.75F,
                    Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, 0.0F, 0x00FFFFFF, 0x99FFFFFF);
        }
        float lx = gx + 104.0F;
        if (third != null) {
            RoundedImage.draw(g, third, lx, y, size, size, 0.0F, 0.0F, 1.0F, 1.0F,
                    Palette.RADIUS_MD, 0.0F, 0.0F, Palette.RADIUS_MD, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
        }
        y += size + 8.0F;
        SkinHead.drawSkin(g, SkinHead.steve(), x, y, 24.0F, 4.2F);
        SkinHead.drawSkin(g, SkinHead.steve(), x + 32.0F, y, 24.0F, 0.0F);
        SkinHead.drawSkin(g, SkinHead.steve(), x + 64.0F, y + 6.0F, 12.0F, 12.0F);
        return y + 24.0F + 12.0F;
    }

    private float drawIcons(GuiGraphics g, float x, float y, float inner) {
        y = section(g, "icons", x, y);
        float ix = x;
        for (UiIcon icon : UiIcon.values()) {
            if (ix + 12.0F > x + inner) {
                ix = x;
                y += 13.0F;
            }
            UiIcons.draw(g, icon, ix, y, 9.0F, Palette.TEXT_2);
            ix += 13.0F;
        }
        y += 16.0F;
        UiIcons.draw(g, UiIcon.PLAY, x, y, 18.0F, Palette.ACCENT);
        UiIcons.draw(g, UiIcon.HEART_FILLED, x + 24.0F, y, 18.0F, Palette.DANGER);
        UiIcons.draw(g, UiIcon.STAR_FILLED, x + 48.0F, y, 18.0F, Palette.ST_FEATURED);
        UiIcons.draw(g, UiIcon.DOWNLOAD, x + 72.0F, y, 18.0F, Palette.TEXT_1);
        return y + 18.0F + 10.0F;
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static ResourceLocation cover(int index) {
        return CoverCache.getResource(ResourceLocation.withDefaultNamespace(
                "textures/gui/title/background/panorama_" + Math.floorMod(index, 6) + ".png"));
    }

    // ---- right: card grid ---------------------------------------------------------------------------------

    private void drawGrid(GuiGraphics g, float offset) {
        int first = grid.firstVisible(offset);
        int end = grid.endVisible(offset);
        for (int i = 0; i < CARD_COUNT; i++) {
            boolean hovered = i == hoveredCard;
            Card card = cards[i];
            if (hovered != card.hovered) {
                card.hovered = hovered;
                card.hover.animateTo(hovered ? 1.0 : 0.0, hovered ? Palette.T_CARD : Palette.T_CARD / 3.0,
                        Motion.Easing.OUT_QUINT, hovered ? 100.0 : 0.0);
            }
            boolean playing = PreviewPlayer.isCurrent(card.key) || card.key.equals(previewWanted);
            if (playing != card.playing) {
                card.playing = playing;
                card.dim.animateTo(playing ? 0.6 : 0.0, Palette.T_CARD, Motion.Easing.OUT_QUINT);
            }
        }
        UiClip.push(g, gridX - 3.0F, gridY, gridX + gridWidth + 3.0F, gridY + gridHeight);
        // Pass 1: shadows and card backgrounds, one SDF batch.
        for (int i = first; i < end; i++) {
            Card card = cards[i];
            float grow = card.hover.value();
            float x = grid.cardX(i) - 1.5F * grow;
            float y = grid.cardY(i, offset) - 1.5F * grow;
            float w = grid.cardWidth() + 3.0F * grow;
            float h = grid.cardHeight() + 3.0F * grow;
            if (grow > 0.01F) {
                RoundedRect.shadow(g, x, y + 2.0F, w, h, Palette.RADIUS_MD, 7.0F, Palette.fade(Palette.SHADOW_LIFTED, grow));
            }
            // Shows through where the thumbnail has not loaded yet.
            RoundedRect.fill(g, x, y, w, h, Palette.RADIUS_MD, Palette.mix(Palette.BG_2, Spectrum.argb(card.stars[0]), 0.3F));
        }
        // Pass 2: thumbnails, one immediate draw each.
        for (int i = first; i < end; i++) {
            Card card = cards[i];
            float grow = card.hover.value();
            float x = grid.cardX(i) - 1.5F * grow;
            float y = grid.cardY(i, offset) - 1.5F * grow;
            float h = grid.cardHeight() + 3.0F * grow;
            ResourceLocation texture = cover(card.index);
            if (texture != null) {
                float u = (card.index % 5) * 0.1F;
                RoundedImage.draw(g, texture, x, y, h, h, u, 0.2F, u + 0.5F, 0.7F,
                        Palette.RADIUS_MD, 0.0F, 0.0F, Palette.RADIUS_MD, card.dim.value(), 0xFFFFFFFF, 0xFFFFFFFF);
            }
        }
        // Pass 3: content panels, pills, dots and rings, one SDF batch.
        for (int i = first; i < end; i++) {
            Card card = cards[i];
            float grow = card.hover.value();
            float x = grid.cardX(i) - 1.5F * grow;
            float y = grid.cardY(i, offset) - 1.5F * grow;
            float w = grid.cardWidth() + 3.0F * grow;
            float h = grid.cardHeight() + 3.0F * grow;
            float contentX = x + h - Palette.RADIUS_MD;
            RoundedRect.fill(g, contentX, y, w - h + Palette.RADIUS_MD, h, Palette.RADIUS_MD,
                    Palette.mix(Palette.BG_3, Palette.BG_4, grow));
            float textX = x + h + 6.0F;
            float pillHeight = TextStyle.PILL.capHeight() + 5.0F;
            float pillY = y + h - pillHeight - 6.0F;
            RoundedRect.pill(g, textX, pillY, card.pillWidth(), pillHeight, STATUS_COLOURS[card.status]);
            float dotX = textX + card.pillWidth() + 5.0F;
            int surface = Palette.mix(Palette.BG_3, Palette.BG_4, grow);
            for (double stars : card.stars) {
                StarPill.dot(g, dotX, pillY + (pillHeight - StarPill.DOT_HEIGHT) * 0.5F, stars, surface);
                dotX += StarPill.DOT_STEP;
            }
            float cx = x + h * 0.5F;
            float cy = y + h * 0.5F;
            if (card.hovered || card.playing) {
                RoundedRect.circle(g, cx, cy, 13.0F, Palette.withAlpha(Palette.BG_0, 0.55F));
            }
            if (PreviewPlayer.isCurrent(card.key) && !PreviewPlayer.isLoading(card.key)) {
                Ring.draw(g, cx, cy, 13.0F, Ring.OSU_PREVIEW_THICKNESS, PreviewPlayer.progress(card.key), Palette.ACCENT);
            } else if (card.playing) {
                drawSpinner(g, cx, cy, 13.0F, (Util.getNanos() - openedNanos) / 1_000_000_000.0, Palette.ACCENT);
            }
        }
        // Pass 4: text and icons, one batch per font page and icon tier.
        for (int i = first; i < end; i++) {
            Card card = cards[i];
            float grow = card.hover.value();
            float x = grid.cardX(i) - 1.5F * grow;
            float y = grid.cardY(i, offset) - 1.5F * grow;
            float w = grid.cardWidth() + 3.0F * grow;
            float h = grid.cardHeight() + 3.0F * grow;
            float textX = x + h + 6.0F;
            float room = w - h - 12.0F;
            card.title.queue(g, textX, y + 6.0F, room, Palette.TEXT_1);
            card.artist.queue(g, textX, y + 6.0F + TextStyle.TITLE.capHeight() + 5.0F, room, Palette.TEXT_2);
            card.mapper.queue(g, textX, y + 6.0F + TextStyle.TITLE.capHeight() + 5.0F + TextStyle.BODY.capHeight() + 4.0F,
                    room, Palette.TEXT_MUTE);
            float pillHeight = TextStyle.PILL.capHeight() + 5.0F;
            float pillY = y + h - pillHeight - 6.0F;
            TextStyles.queue(g, card.pillText(), textX + 4.0F, TextStyles.centeredCapY(TextStyle.PILL, pillY, pillHeight), Palette.ON_PILL);
            if (card.hovered || card.playing) {
                UiIcon icon = PreviewPlayer.isCurrent(card.key) ? UiIcon.PAUSE : UiIcon.PLAY;
                UiIcons.drawCentered(g, icon, x + h * 0.5F + (icon == UiIcon.PLAY ? 0.7F : 0.0F), y + h * 0.5F, 9.0F, Palette.TEXT_1);
            }
        }
        UiClip.pop(g);
    }

    private void drawScrollbar(GuiGraphics g, float offset) {
        float extent = grid.scrollExtent();
        float trackX = gridX + gridWidth + 3.0F;
        RoundedRect.pill(g, trackX, gridY, 3.0F, gridHeight, Palette.withAlpha(Palette.BG_2, 0.9F));
        if (extent <= 0) {
            return;
        }
        float thumbHeight = Math.max(16.0F, gridHeight * gridHeight / grid.contentHeight());
        float thumbY = gridY + (gridHeight - thumbHeight) * Math.max(0.0F, Math.min(1.0F, offset / extent));
        RoundedRect.pill(g, trackX, thumbY, 3.0F, thumbHeight, draggingScrollbar ? Palette.ACCENT : Palette.LINE);
    }

    // ---- input --------------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < gridX - PAD * 0.5) {
            panelScroll.onWheel(scrollY);
        } else {
            gridScroll.onWheel(scrollY);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            float trackX = gridX + gridWidth + 1.0F;
            if (mouseX >= trackX && mouseX < trackX + 8.0F && mouseY >= gridY && mouseY < gridY + gridHeight
                    && grid.scrollExtent() > 0) {
                draggingScrollbar = true;
                scrollbarDrag(mouseY);
                return true;
            }
            int index = grid.hitTest(mouseX, mouseY, gridScroll.current());
            if (index >= 0) {
                togglePreview(cards[index].key);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollbarDrag(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            gridScroll.endDrag();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrollbarDrag(double mouseY) {
        float thumbHeight = Math.max(16.0F, gridHeight * gridHeight / Math.max(1.0F, grid.contentHeight()));
        double travel = Math.max(1.0, gridHeight - thumbHeight);
        double fraction = (mouseY - gridY - thumbHeight * 0.5) / travel;
        gridScroll.dragTo(fraction * grid.scrollExtent());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_S) {
            UiSdf.setForceFallback(!UiSdf.forceFallback());
            statsNanos = 0L;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void togglePreview(String key) {
        if (PreviewPlayer.isCurrent(key) || key.equals(previewWanted)) {
            previewWanted = null;
            PreviewPlayer.stop();
            return;
        }
        PreviewPlayer.stop();
        if (previewBytes != null) {
            PreviewPlayer.play(key, previewBytes, PREVIEW_MS, PreviewPlayer.DEFAULT_VOLUME);
            return;
        }
        previewWanted = key;
        if (previewLoading) {
            return;
        }
        previewLoading = true;
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try (InputStream in = minecraft.getResourceManager().getResource(PREVIEW_SOUND).orElseThrow().open()) {
                return in.readNBytes((int) PreviewPlayer.MAX_PREVIEW_BYTES);
            } catch (Exception e) {
                MusicBattleMod.LOGGER.warn("Debug preview sound {} could not be read: {}", PREVIEW_SOUND, e.toString());
                return null;
            }
        }, Util.nonCriticalIoPool()).thenAcceptAsync(bytes -> {
            previewLoading = false;
            previewBytes = bytes;
            String wanted = previewWanted;
            previewWanted = null;
            if (bytes != null && wanted != null && minecraft.screen == this) {
                PreviewPlayer.play(wanted, bytes, PREVIEW_MS, PreviewPlayer.DEFAULT_VOLUME);
            }
        }, minecraft);
    }

    @Override
    public void onClose() {
        PreviewPlayer.stop();
        previewWanted = null;
        UiSdf.setForceFallback(false);
        CoverCache.clear();
        HandCursor.reset();
        Minecraft.getInstance().setScreen(parent);
    }

    // ---- placeholder data ---------------------------------------------------------------------------------

    private static final class Card {
        final int index;
        final String key;
        final double[] stars;
        final int status;
        final TextStyles.Label title;
        final TextStyles.Label artist;
        final TextStyles.Label mapper;
        final Motion.Tween hover = new Motion.Tween(0.0);
        final Motion.Tween dim = new Motion.Tween(0.0);
        final Component statusLabel;
        boolean hovered;
        boolean playing;
        private FormattedCharSequence pillText;
        private int pillTier = -1;
        private float pillWidth;

        Card(int index) {
            this.index = index;
            this.key = "debug_" + index;
            int seed = index * 2654435 + 12345;
            int count = 1 + Math.floorMod(seed >> 3, 6);
            stars = new double[count];
            double base = Math.floorMod(seed, 40) / 10.0;
            for (int d = 0; d < count; d++) {
                stars[d] = Math.min(10.5, base + d * (0.6 + Math.floorMod(seed >> 7, 5) * 0.25));
            }
            status = Math.floorMod(seed >> 5, STATUS_KEYS.length);
            title = new TextStyles.Label(Component.translatable(KEY + "card_title", String.valueOf(index + 1)), TextStyle.TITLE);
            artist = new TextStyles.Label(Component.translatable(KEY + "card_artist", String.valueOf((char) ('A' + index % 26))), TextStyle.BODY);
            mapper = new TextStyles.Label(Component.translatable(KEY + "card_mapper", "qwu_mapper_" + (index % 7)), TextStyle.CAPTION);
            statusLabel = Component.translatable("music_battle.workshop.status." + STATUS_KEYS[status]);
        }

        FormattedCharSequence pillText() {
            int tier = TextStyles.tier();
            if (tier != pillTier || pillText == null) {
                pillTier = tier;
                pillText = TextStyles.text(statusLabel.getString().toUpperCase(Locale.ROOT), TextStyle.PILL).getVisualOrderText();
                pillWidth = TextStyles.width(pillText) + 8.0F;
            }
            return pillText;
        }

        float pillWidth() {
            pillText();
            return pillWidth;
        }
    }
}
