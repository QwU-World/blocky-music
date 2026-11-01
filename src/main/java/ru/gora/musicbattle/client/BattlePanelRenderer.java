package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.NoteSpeed;

/**
 * The battle HUD's shared drawing: the note panel used by a live battle, the settings preview and the editor's gameplay
 * preview, and the header card over the battle (song, cover, time left, progress, count-in) with the beatmap download
 * bar that stands in its place before a battle.
 */
final class BattlePanelRenderer {
    static final int HIT_WINDOW_TICKS = 16;
    static final int MISS_AFTER_PANEL_EXIT_TICKS = 7;
    static final int EARLY_HIT_WINDOW_TICKS = 6;
    static final int BASE_LEAD_TICKS = 54;
    /** The narrowest and the widest lane: a panel is always exactly four lanes wide. */
    static final int MIN_LANE_WIDTH = 23;
    static final int MAX_LANE_WIDTH = 80;

    private BattlePanelRenderer() {
    }

    interface NoteView {
        int owner();

        int lane();

        int tick();

        int durationTicks();

        boolean started();

        boolean resolved();
    }

    record Panel(
            int owner,
            int screenSide,
            boolean own,
            String name,
            float speed,
            int color,
            float panelOpacity,
            float laneOpacity,
            float widthScale,
            float heightScale,
            float noteWidthScale,
            float noteHeightScale,
            int score,
            int combo,
            int[] laneFlashes,
            int[] hitBursts,
            String[] laneLabels,
            NoteAppearance appearance
    ) {
        Panel {
            appearance = appearance == null ? NoteAppearance.solid(color) : appearance;
        }

        Panel(int owner, int screenSide, boolean own, String name, float speed, int color,
              float panelOpacity, float laneOpacity, float widthScale, float heightScale,
              float noteWidthScale, float noteHeightScale, int score, int combo,
              int[] laneFlashes, int[] hitBursts, String[] laneLabels) {
            this(owner, screenSide, own, name, speed, color, panelOpacity, laneOpacity, widthScale, heightScale,
                    noteWidthScale, noteHeightScale, score, combo, laneFlashes, hitBursts, laneLabels,
                    NoteAppearance.solid(color));
        }
    }

    record Layout(int x, int y, int width, int height) { }

    /**
     * A panel's width on a canvas this wide at this width setting: four whole lanes. It used to be any width, with four
     * lanes cut out of it from the left, which left the remainder (up to three units) after the last lane, so the lanes,
     * their notes and their buttons sat off the middle of the panel, its name and its score. The lane limits are the old
     * 92 and 320 panel limits divided by four. Anything that reserves room for a panel asks here.
     */
    static int panelWidth(int canvasWidth, float widthScale) {
        return Mth.clamp(Math.round(canvasWidth / 12.0F * widthScale), MIN_LANE_WIDTH, MAX_LANE_WIDTH) * 4;
    }

    static Layout layout(int width, int height, float intro, Panel panel) {
        int screenSide = Mth.clamp(panel.screenSide(), 0, 1);
        int panelWidth = panelWidth(width, panel.widthScale());
        int topMargin = 42;
        int bottomMargin = 42;
        int maxPanelHeight = Math.max(150, height - topMargin - bottomMargin);
        int panelHeight = Mth.clamp((int) (height * 0.74F * panel.heightScale()), 170, maxPanelHeight);
        int baseX = screenSide == 0 ? 20 : width - panelWidth - 20;
        int slide = Math.round((1.0F - intro) * 44.0F);
        int x = baseX + (screenSide == 0 ? -slide : slide);
        int y = topMargin + (maxPanelHeight - panelHeight) / 2;
        return new Layout(x, y, panelWidth, panelHeight);
    }

    /** A rectangle in whole units: the left and top edges are inside it, the right and bottom ones are not. */
    record Box(int left, int top, int right, int bottom) {
        float centerX() {
            return (left + right) * 0.5F;
        }

        float centerY() {
            return (top + bottom) * 0.5F;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        Box grow(int amount) {
            return new Box(left - amount, top - amount, right + amount, bottom + amount);
        }
    }

    /**
     * Where everything in a lane is drawn, worked out in one place.
     *
     * <p>A note that reaches its button has to cover it exactly, and in play it did not: the falling note or the button
     * looked off the middle of the lane, with a gap where they met. Measured on every skin, note size, panel size
     * and side, there were four causes. The lanes started at the panel's left edge and left the rest of its width after
     * the last lane. A lane of odd width centred its arrow or circle on {@code laneX + laneWidth / 2} in whole units,
     * half a unit left of the lane's middle. A held tail was seven units wide from three left of that point to four right
     * of it, so it always came down half a unit right of the button it ran into. And a bar-skin note was centred a unit
     * above its bar of a button.
     *
     * <p>Now every part of a lane - its strip, its button, the note that reaches the button, a held tail, the hit burst
     * and the key label - is centred on the lane's own middle, a button and the note on it share one centre on the hit
     * line, and every edge falls on a whole unit: a button, a note head and a tail are as wide as the lane is odd or even.
     * A unit is a whole number of screen pixels in the battle HUD (see BattleHudScale), so the two sides of anything in a
     * lane are drawn the same number of pixels wide.
     */
    record Lanes(int left, int laneWidth, NoteAppearance appearance, int glyph, int noteHalfHeight, int noteInset) {
        /** A bar-skin button is a bar this tall whose top edge is the hit line, as it has always been. */
        static final int BAR_RECEPTOR_HEIGHT = 4;
        static final int MIN_BAR_HEAD_WIDTH = 4;

        static Lanes of(Layout layout, Panel panel) {
            int laneWidth = layout.width() / 4;
            int noteHalfHeight = Math.max(3, Math.round(7 * panel.noteHeightScale()));
            int baseInset = Math.max(3, Math.round(5 / Math.max(0.35F, panel.noteWidthScale())));
            int noteInset = Mth.clamp(
                    Math.round((laneWidth * (1.0F - Mth.clamp(panel.noteWidthScale(), 0.35F, 2.2F) * 0.84F)) * 0.5F),
                    baseInset,
                    Math.max(baseInset, laneWidth / 2 - 4));
            return new Lanes(layout.x(), laneWidth, panel.appearance(), glyphSize(laneWidth, panel), noteHalfHeight, noteInset);
        }

        boolean bars() {
            return appearance.skin() == NoteAppearance.BARS;
        }

        int laneLeft(int lane) {
            return left + lane * laneWidth;
        }

        float centerX(int lane) {
            return laneLeft(lane) + laneWidth * 0.5F;
        }

        /** A box {@code width} wide, centred in the lane; the width keeps the lane's parity so both edges are whole. */
        private Box centred(int lane, int width, int top, int bottom) {
            int sized = (width - laneWidth) % 2 == 0 ? width : width - 1;
            int boxLeft = laneLeft(lane) + (laneWidth - sized) / 2;
            return new Box(boxLeft, top, boxLeft + sized, bottom);
        }

        /** The lane's strip, from under the panel's name strip to just above its bottom. */
        Box strip(int lane, int top, int bottom) {
            return new Box(laneLeft(lane) + 1, top, laneLeft(lane) + laneWidth - 1, bottom);
        }

        /** The button. */
        Box receptor(int lane, int hitY) {
            if (bars()) {
                return centred(lane, laneWidth - 6, hitY, hitY + BAR_RECEPTOR_HEIGHT);
            }
            return head(lane, hitY);
        }

        /**
         * A note head whose travel position is {@code y}, which is {@code hitY} on its tick: there its box is the
         * button's, or for bars a taller bar on the button's own centre line.
         */
        Box head(int lane, int y) {
            if (bars()) {
                int center = y + BAR_RECEPTOR_HEIGHT / 2;
                // The narrowest note on the narrowest lane asks for more inset than the lane has; such a bar used to be
                // drawn inside out, four units wide, and keeps that width.
                int width = Math.max(MIN_BAR_HEAD_WIDTH, laneWidth - noteInset * 2);
                return centred(lane, width, center - (noteHalfHeight + 1), center + noteHalfHeight + 1);
            }
            return centred(lane, glyph, y - glyph / 2, y - glyph / 2 + glyph);
        }

        /** The lighter middle of a bar-skin head, two units in from its edges. */
        Box headCore(Box head) {
            return new Box(head.left() + 2, head.top() + 2, head.right() - 2, head.bottom() - 2);
        }

        /** The row a head at travel position {@code y} is centred on, which is where its tail is joined to it. */
        int joinY(int y) {
            return (int) Math.floor(head(0, y).centerY());
        }

        /** A held or coming tail, between the rows its two ends are centred on. */
        Box tail(int lane, int fromY, int toY) {
            return centred(lane, 7, Math.min(fromY, toY), Math.max(fromY, toY));
        }

        /** The tail's light core, as centred as the tail. */
        Box tailCore(int lane, int fromY, int toY) {
            return centred(lane, 3, Math.min(fromY, toY), Math.max(fromY, toY));
        }

        /** Where the key label sits under its button. */
        int labelY(int hitY) {
            Box receptor = receptor(0, hitY);
            return receptor.bottom() + (bars() ? 4 : 3);
        }
    }

    static void draw(GuiGraphics graphics, Font font, int width, int height, float time, int bpm, float intro,
                     List<? extends NoteView> notes, Panel panel) {
        draw(graphics, font, width, height, time, bpm, 0.0F, intro, notes, panel);
    }

    /** {@code beatOrigin}: beat 1 on {@code time}'s clock, in ticks; the combo's beat is counted from it. */
    static void draw(GuiGraphics graphics, Font font, int width, int height, float time, int bpm, float beatOrigin,
                     float intro, List<? extends NoteView> notes, Panel panel) {
        int owner = Mth.clamp(panel.owner(), 0, 1);
        Layout layout = layout(width, height, intro, panel);
        Lanes lanes = Lanes.of(layout, panel);
        int x = layout.x();
        int y = layout.y();
        int panelWidth = layout.width();
        int panelHeight = layout.height();
        int laneWidth = lanes.laneWidth();
        int glyphSize = lanes.glyph();
        int hitY = y + panelHeight - (lanes.bars() ? 38 : Math.max(38, glyphSize / 2 + 22));
        float spawnY = y + (lanes.bars() ? 24 : glyphSize / 2 + 19);
        int leadTicks = leadTicks(panel.speed());
        int color = panel.color();
        int panelColor = withAlpha(color, Math.round((panel.own() ? 110 : 96) * panel.panelOpacity() * intro));

        graphics.fill(x, y, x + panelWidth, y + panelHeight, panelColor);
        graphics.fill(x, y, x + panelWidth, y + 14, withAlpha(color, Math.round(190 * intro)));
        // A long name is narrowed to the strip rather than run out over the lanes of the panel beside it.
        drawCenteredLabel(graphics, font, panel.name(), x + panelWidth * 0.5F, y + 4,
                fitScale(font, panel.name(), panelWidth - 8), 0xFFFFFFFF);

        // One size for the four key labels, the size the longest one fits at, so the labels do not differ from lane to
        // lane by how long each key's name happens to be.
        float labelScale = 1.0F;
        for (int lane = 0; lane < 4; lane++) {
            labelScale = Math.min(labelScale, fitScale(font, labelAt(panel.laneLabels(), lane), laneWidth - 6));
        }
        for (int lane = 0; lane < 4; lane++) {
            int laneColor = panel.appearance().color(lane);
            int flash = valueAt(panel.laneFlashes(), lane);
            int laneAlpha = Math.round((flash > 0 ? 92 + flash * 14 : 42) * panel.laneOpacity());
            fill(graphics, lanes.strip(lane, y + 16, y + panelHeight - 9), withAlpha(laneColor, Math.round(laneAlpha * intro)));
            Box receptor = lanes.receptor(lane, hitY);
            if (lanes.bars()) {
                fill(graphics, receptor, withAlpha(laneColor, 225));
            } else {
                // The hollow of the hit target is the Workshop's page-panel shade, so it sits in the same family as
                // the settings backdrop and every palette lane colour keeps its contrast against it.
                drawShapedGlyph(graphics, panel, lane, receptor,
                        withAlpha(laneColor, Math.round(220 * intro)), withAlpha(Palette.BG_1, Math.round(210 * intro)));
            }
            int burst = valueAt(panel.hitBursts(), lane);
            if (burst > 0) {
                float pulse = burst / 8.0F;
                int spread = Math.round((1.0F - pulse) * 9.0F);
                int burstAlpha = Math.round(210.0F * pulse * intro);
                if (lanes.bars()) {
                    fill(graphics, new Box(lanes.laneLeft(lane) + 2, receptor.top() - 4, lanes.laneLeft(lane) + laneWidth - 2,
                            receptor.bottom() + 4).grow(spread), withAlpha(lighten(laneColor, 255), burstAlpha));
                    fill(graphics, new Box(lanes.laneLeft(lane) + 7, receptor.top() - 1, lanes.laneLeft(lane) + laneWidth - 7,
                            receptor.bottom() + 1), argb(Math.round(240.0F * pulse * intro), 255, 255, 255));
                } else {
                    drawShapedGlyph(graphics, panel, lane, receptor.grow(spread),
                            withAlpha(laneColor, burstAlpha), withAlpha(0xFFFFFFFF, burstAlpha));
                }
            }
            drawCenteredLabel(graphics, font, labelAt(panel.laneLabels(), lane), lanes.centerX(lane), lanes.labelY(hitY),
                    labelScale, 0xFFFFFFFF);
        }

        for (NoteView note : notes) {
            if (note.resolved() || note.owner() != owner || !noteVisibleAt(time, note.tick(), note.durationTicks(), leadTicks)) {
                continue;
            }
            int lane = Mth.clamp(note.lane(), 0, 3);
            int lateY = hitY + 86;
            int noteY = note.started() ? hitY : Math.round(noteTravelY(time, note.tick(), leadTicks, spawnY, hitY, lateY));
            int fadeTick = note.tick() + Math.max(0, note.durationTicks());
            int alpha = (int) (Mth.clamp(1.0F - Math.max(0.0F, time - fadeTick - HIT_WINDOW_TICKS) / 6.0F, 0.0F, 1.0F) * 255.0F);
            int laneColor = panel.appearance().color(lane);
            int outer = withAlpha(laneColor, Math.round(alpha * intro));
            int inner = lighten(laneColor, Math.round(alpha * intro));
            if (note.durationTicks() > 0) {
                int tailY = Math.round(noteTravelY(time, note.tick() + note.durationTicks(), leadTicks, spawnY, hitY, lateY));
                if (note.started()) {
                    // A successfully held sustain is anchored to the judgement line. The
                    // authoritative completion packet can arrive a fraction before/after the
                    // spectator's audio clock, but its tail must never look like a miss.
                    tailY = Math.min(tailY, hitY);
                }
                // Both ends of the tail are joined on the rows its head and its end are centred on, so a held tail runs
                // into the very middle of its button.
                fill(graphics, lanes.tail(lane, lanes.joinY(noteY), lanes.joinY(tailY)), outer);
                fill(graphics, lanes.tailCore(lane, lanes.joinY(noteY), lanes.joinY(tailY)), inner);
            }
            if (!note.started()) {
                Box head = lanes.head(lane, noteY);
                if (lanes.bars()) {
                    fill(graphics, head, outer);
                    fill(graphics, lanes.headCore(head), inner);
                } else {
                    drawShapedGlyph(graphics, panel, lane, head, outer, inner);
                }
            }
        }

        float comboPulse = 1.0F + beatPulse(time, bpm, beatOrigin) * 0.18F;
        int comboColor = comboColor(color, panel.combo());
        drawCenteredScaled(graphics, font, Component.literal("x" + panel.combo()), x + panelWidth / 2.0F,
                y + panelHeight / 2.0F - 5.0F, comboPulse * 1.35F, comboColor);
        graphics.drawCenteredString(font, Component.translatable("music_battle.settings.score", panel.score()), x + panelWidth / 2,
                y + panelHeight + 4, 0xFFFFFFFF);
    }

    static int leadTicks(float speed) {
        return NoteSpeed.leadTicks(speed);
    }

    static int earlyHitWindowTicks(float speed) {
        return NoteSpeed.earlyHitWindowTicks(speed);
    }

    /**
     * The arrow or circle size for a lane: nearly the lane's width, scaled by the note settings, and as odd or even as
     * the lane so that, centred in it, both of its edges are whole units.
     */
    static int glyphSize(int laneWidth, Panel panel) {
        float scale = (float) Math.sqrt(Mth.clamp(panel.noteWidthScale(), .35F, 2.2F)
                * Mth.clamp(panel.noteHeightScale(), .35F, 2.2F));
        int size = Math.max(6, Math.round((laneWidth - 6) * Math.min(1F, scale)));
        return (size - laneWidth) % 2 == 0 ? size : size - 1;
    }

    private static void drawShapedGlyph(GuiGraphics graphics, Panel panel, int lane, Box box, int outer, int inner) {
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        var pose = graphics.pose().last().pose();
        BattleNoteShape.TriangleFill fill = (x1,y1,x2,y2,x3,y3,color) -> {
            // GUI projection flips Y. Match GuiGraphics.fill's front-facing winding;
            // RenderType.gui keeps back-face culling enabled.
            vertices.addVertex(pose, x1,y1,0).setColor(color);
            vertices.addVertex(pose, x3,y3,0).setColor(color);
            vertices.addVertex(pose, x2,y2,0).setColor(color);
            vertices.addVertex(pose, x2,y2,0).setColor(color);
        };
        BattleNoteShape.drawTriangles(fill, panel.appearance().skin(), lane,
                box.left(), box.top(), box.right(), box.bottom(), outer);
        if (panel.appearance().skin() == NoteAppearance.ARROWS) {
            BattleNoteShape.drawInsetArrowTriangles(fill, lane, box.centerX(), box.centerY(), box.width() * 0.5F, 2F, inner);
        } else {
            BattleNoteShape.drawTriangles(fill, panel.appearance().skin(), lane,
                    box.left() + 2, box.top() + 2, box.right() - 2, box.bottom() - 2, inner);
        }
    }

    private static void fill(GuiGraphics graphics, Box box, int color) {
        if (box.right() > box.left() && box.bottom() > box.top()) {
            graphics.fill(box.left(), box.top(), box.right(), box.bottom(), color);
        }
    }

    static float noteTravelY(float time, int noteTick, int leadTicks, float startY, float hitY, float lateY) {
        float approach = (time - noteTick + leadTicks) / Math.max(1.0F, leadTicks);
        if (approach <= 1.0F) {
            return Mth.lerp(Mth.clamp(approach, 0.0F, 1.0F), startY, hitY);
        }
        float late = (time - noteTick) / Math.max(1.0F, HIT_WINDOW_TICKS);
        return Mth.lerp(Mth.clamp(late, 0.0F, 1.0F), hitY, lateY);
    }

    static boolean noteVisibleAt(float time, int noteTick, int durationTicks, int leadTicks) {
        int endTick = noteTick + Math.max(0, durationTicks);
        return time >= noteTick - leadTicks - 6.0F && time <= endTick + HIT_WINDOW_TICKS + 6.0F;
    }

    private static float beatPulse(float time, int bpm, float beatOrigin) {
        if (bpm <= 0 || time < 0.0F) {
            return 0.0F;
        }
        float beatTicks = 20.0F * 60.0F / bpm;
        float sinceBeatOne = time - beatOrigin;
        // Beat 1 at 0 is the old pulse exactly; a later beat 1 runs its beats on backwards through the lead-in.
        float within = beatOrigin == 0.0F ? time % beatTicks
                : sinceBeatOne - (float) Math.floor(sinceBeatOne / beatTicks) * beatTicks;
        float phase = within / beatTicks;
        return Mth.clamp(1.0F - phase * 4.0F, 0.0F, 1.0F);
    }

    private static void drawCenteredScaled(GuiGraphics graphics, Font font, Component text, float centerX, float y,
                                           float scale, int color) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, text, 0, 0, color);
        pose.popPose();
    }

    /** How far a label has to shrink to fit {@code maxWidth}; never grown. */
    private static float fitScale(Font font, String text, int maxWidth) {
        return Math.min(1.0F, Math.max(1, maxWidth) / (float) Math.max(1, font.width(text)));
    }

    /**
     * A label centred on {@code centerX} exactly. Vanilla's centring halves the width in whole units, which puts a label
     * of odd width half a unit right of the point it was asked to centre on; the translation takes that half back.
     */
    private static void drawCenteredLabel(GuiGraphics graphics, Font font, String text, float centerX, int y,
                                          float scale, int color) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX - (font.width(text) % 2) * 0.5F * scale, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, text, 0, 0, color);
        pose.popPose();
    }

    private static int valueAt(int[] values, int index) {
        return values != null && index >= 0 && index < values.length ? values[index] : 0;
    }

    private static String labelAt(String[] labels, int lane) {
        String fallback = new String[]{"<", "v", "^", ">"}[Mth.clamp(lane, 0, 3)];
        if (labels == null || lane < 0 || lane >= labels.length || labels[lane] == null || labels[lane].isBlank()) {
            return fallback;
        }
        return labels[lane];
    }

    /**
     * The combo is written in the panel colour so a recoloured panel carries through, but the default panel is the
     * settings screen's near-black, which would vanish on a dark backdrop. A panel that dark writes its combo in the
     * palette's lavender instead, and in gold from the same milestone at which a bright panel colour lightens.
     */
    static int comboColor(int color, int combo) {
        int luma = (((color >> 16) & 0xFF) * 299 + ((color >> 8) & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
        if (luma < 80) return combo >= 20 ? Palette.ST_FEATURED : Palette.ACCENT;
        return combo >= 20 ? lighten(color, 255) : withAlpha(color, 255);
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int lighten(int color, int alpha) {
        int red = Math.min(255, ((color >> 16) & 0xFF) + 95);
        int green = Math.min(255, ((color >> 8) & 0xFF) + 95);
        int blue = Math.min(255, (color & 0xFF) + 95);
        return argb(alpha, red, green, blue);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Mth.clamp(alpha, 0, 255) << 24)
                | (Mth.clamp(red, 0, 255) << 16)
                | (Mth.clamp(green, 0, 255) << 8)
                | Mth.clamp(blue, 0, 255);
    }

    // ------------------------------------------------------------------------------------------------ in the world

    /**
     * The panel over a fighter's head that spectators see, in the panel's own units: the controller turns it to the
     * camera and scales it by 0.62 to blocks, x to the right and y down, centred on 0.
     *
     * <p>Measured the way the HUD panel was measured, it had the HUD's old faults and a few of its own. The four lanes
     * ran from 0.48 left of the middle to 0.45 right of it, so every lane, button and note sat 0.015 left of the panel's
     * middle. A bar note was shorter than the bar of its button, so the button showed round the note that had reached it.
     * The arrow and circle buttons hung 0.04 out of the bottom of their lanes, and every new note stood out of the top of
     * its lane for its first 300 ms. A held tail ran on past its button when the end of the hold arrived late. The light
     * core of an arrow was the arrow scaled down, its rim nearly three times thicker at the point than along the shaft,
     * and the buttons were solid where the HUD's are hollow. A missed note slid out under the panel at full strength and
     * vanished. With the default near-black panel colour the progress strip was black on black, and the stats were English
     * words in every language. Now the lanes are centred, every part of a lane is centred on the lane's middle, a coming
     * note is inside its lane from its first frame, a note on its tick has its button's centre (an arrow or circle covers
     * it exactly, a bar note is a taller bar on the button's centre line), a held tail runs into the middle of its button
     * and stops there, the cores and hollows are parallel insets as in the HUD, a missed note fades as the HUD's does,
     * the strip takes the HUD's readable colour for a dark panel, and the score is the HUD's own line.
     * battle-panel-geometry measures all of it on every skin.
     */
    static final class World {
        static final float HALF_WIDTH = 0.62F;
        static final float HALF_HEIGHT = 0.58F;
        /** The frame's hairline, inside which the panel is filled. */
        static final float BORDER = 0.02F;
        /** A lane is 0.21 wide, one every 0.24: four of them take 0.93, centred. */
        static final float LANE_PITCH = 0.24F;
        static final float LANE_WIDTH = 0.21F;
        /**
         * 0.02 under the progress strip, so that a note on its spawn row (see {@link #spawnY}) is inside its lane: the
         * top of a fresh arrow or circle is at -0.4376 and of a bar at -0.42, which leaves 0.012 and 0.03 of the lane
         * above them. At -0.40 every fresh note stuck out of the top of its lane over the dark gap for its first 300 ms.
         */
        static final float LANE_TOP = -0.45F;
        static final float LANE_BOTTOM = 0.50F;
        /** The buttons' centre line, where a note is on its tick. */
        static final float HIT_Y = 0.34F;
        /** Where a missed note has slid to when the hit window closes; it fades there over six ticks. */
        static final float LATE_Y = 0.67F;
        static final float PROGRESS_HALF_WIDTH = 0.56F;
        static final float PROGRESS_TOP = -0.50F;
        static final float PROGRESS_BOTTOM = -0.47F;
        /** An arrow or a circle is 93% of its lane wide, so half of it is this share of the lane. */
        static final float GLYPH = 0.465F;
        /** A bar button, and a bar note, which is narrower and taller so that it covers the button's height. */
        static final float BAR_BUTTON_HALF_WIDTH = 0.085F;
        static final float BAR_BUTTON_HALF_HEIGHT = 0.03F;
        static final float BAR_HEAD_HALF_WIDTH = 0.075F;
        static final float BAR_HEAD_HALF_HEIGHT = 0.04F;
        static final float TAIL_HALF_WIDTH = 0.025F;
        static final float TAIL_CORE_HALF_WIDTH = 0.01F;
        /** The rim of a hollow button and of a note round its light core: one inset, the same on every edge. */
        static final float RIM = 0.02F;

        private World() {
        }

        /** A rectangle in panel units. */
        record Area(float left, float top, float right, float bottom) {
            float centerX() {
                return (left + right) * 0.5F;
            }

            float centerY() {
                return (top + bottom) * 0.5F;
            }

            float width() {
                return right - left;
            }

            float height() {
                return bottom - top;
            }

            Area inset(float amount) {
                return new Area(left + amount, top + amount, right - amount, bottom - amount);
            }
        }

        /** What the panel shows of one fighter. */
        record Panel(int owner, int color, NoteAppearance appearance, float speed, int durationTicks, int[] laneFlashes,
                     int[] hitBursts) {
            Panel {
                appearance = appearance == null ? NoteAppearance.solid(color) : appearance;
            }
        }

        static Area frame() {
            return new Area(-HALF_WIDTH, -HALF_HEIGHT, HALF_WIDTH, HALF_HEIGHT);
        }

        static Area inner() {
            return frame().inset(BORDER);
        }

        static float firstLane() {
            return -(LANE_PITCH * 3F + LANE_WIDTH) * 0.5F;
        }

        static float laneLeft(int lane) {
            return firstLane() + lane * LANE_PITCH;
        }

        static float laneCenter(int lane) {
            return laneLeft(lane) + LANE_WIDTH * 0.5F;
        }

        static Area strip(int lane) {
            return new Area(laneLeft(lane), LANE_TOP, laneLeft(lane) + LANE_WIDTH, LANE_BOTTOM);
        }

        static Area progressTrack() {
            return new Area(-PROGRESS_HALF_WIDTH, PROGRESS_TOP, PROGRESS_HALF_WIDTH, PROGRESS_BOTTOM);
        }

        static Area progress(float fraction) {
            float clamped = Mth.clamp(fraction, 0F, 1F);
            return new Area(-PROGRESS_HALF_WIDTH, PROGRESS_TOP, -PROGRESS_HALF_WIDTH + PROGRESS_HALF_WIDTH * 2F * clamped,
                    PROGRESS_BOTTOM);
        }

        /**
         * Where a note starts down its lane: an arrow or a circle lower than a bar, so that its full height clears the
         * progress strip (world-note-shape pins both).
         */
        static float spawnY(int skin) {
            return skin == NoteAppearance.BARS ? -0.38F : -0.34F;
        }

        /**
         * The box a glyph fills: a bar its own half sizes, an arrow or a circle {@link #GLYPH} of {@code room} each side
         * of its centre and as tall as it is wide in the world, {@code aspect} being the pose's x scale over its y
         * scale (1 for the panel's own pose).
         */
        static Area glyph(float centerX, float centerY, float room, float barHalfWidth, float barHalfHeight, int skin,
                          float aspect) {
            float halfWidth = skin == NoteAppearance.BARS ? barHalfWidth : room * GLYPH;
            float halfHeight = skin == NoteAppearance.BARS ? barHalfHeight : halfWidth * aspect;
            return new Area(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight);
        }

        static Area button(int lane, int skin, float aspect) {
            return glyph(laneCenter(lane), HIT_Y, LANE_WIDTH, BAR_BUTTON_HALF_WIDTH, BAR_BUTTON_HALF_HEIGHT, skin, aspect);
        }

        static Area head(int lane, float y, int skin, float aspect) {
            return glyph(laneCenter(lane), y, LANE_WIDTH, BAR_HEAD_HALF_WIDTH, BAR_HEAD_HALF_HEIGHT, skin, aspect);
        }

        /** A held or coming tail between the rows its two ends are centred on. */
        static Area tail(int lane, float fromY, float toY) {
            return new Area(laneCenter(lane) - TAIL_HALF_WIDTH, Math.min(fromY, toY), laneCenter(lane) + TAIL_HALF_WIDTH,
                    Math.max(fromY, toY));
        }

        static Area tailCore(int lane, float fromY, float toY) {
            return new Area(laneCenter(lane) - TAIL_CORE_HALF_WIDTH, Math.min(fromY, toY),
                    laneCenter(lane) + TAIL_CORE_HALF_WIDTH, Math.max(fromY, toY));
        }

        /** How far the lane's room shrinks for a glyph to lose {@code inset} on each side. */
        static float room(float room, float inset) {
            return Math.max(0F, room - inset / GLYPH);
        }
    }

    /** Where a world panel goes: plain quads and the skins' glyphs, in panel units. */
    interface WorldSink {
        void quad(float left, float top, float right, float bottom, int color);

        /** A glyph of the skin in the box {@link World#glyph} gives for these arguments. */
        void glyph(float centerX, float centerY, float room, float barHalfWidth, float barHalfHeight, int skin, int lane,
                   int color);

        /** An arrow's core: the arrow of this half size, every edge moved in by {@code thickness}. */
        void insetArrow(int lane, float centerX, float centerY, float halfSize, float thickness, int color);
    }

    /**
     * A world panel, all but its stats (the controller writes those in the game's font above it): the frame, the
     * progress strip, the lanes with their buttons and hit bursts, and the notes, in that order.
     */
    static void drawWorld(WorldSink sink, World.Panel panel, float time, List<? extends NoteView> notes, float aspect) {
        int color = panel.color();
        World.Area frame = World.frame();
        World.Area inner = World.inner();
        sink.quad(frame.left(), frame.top(), frame.right(), frame.bottom(), Palette.withAlpha(Palette.LINE, 0.9F));
        sink.quad(inner.left(), inner.top(), inner.right(), inner.bottom(), Palette.BG_1);
        sink.quad(inner.left(), inner.top(), inner.right(), inner.bottom(), withAlpha(color, 58));
        World.Area track = World.progressTrack();
        sink.quad(track.left(), track.top(), track.right(), track.bottom(), Palette.BG_0);
        float fraction = Mth.clamp(Math.max(0.0F, time) / Math.max(1.0F, panel.durationTicks()), 0.0F, 1.0F);
        if (fraction > 0F) {
            // The HUD's readable colour for the panel: its own when it is bright, the accent when it is as dark as the
            // default, which would leave the strip black on black.
            World.Area done = World.progress(fraction);
            sink.quad(done.left(), done.top(), done.right(), done.bottom(), withAlpha(comboColor(color, 0), 230));
        }

        NoteAppearance appearance = panel.appearance();
        int skin = appearance.skin();
        boolean bars = skin == NoteAppearance.BARS;
        for (int lane = 0; lane < 4; lane++) {
            int laneColor = appearance.color(lane);
            int flash = valueAt(panel.laneFlashes(), lane);
            World.Area strip = World.strip(lane);
            sink.quad(strip.left(), strip.top(), strip.right(), strip.bottom(),
                    withAlpha(laneColor, flash > 0 ? 165 + flash * 8 : 72));
            float center = World.laneCenter(lane);
            sink.glyph(center, World.HIT_Y, World.LANE_WIDTH, World.BAR_BUTTON_HALF_WIDTH, World.BAR_BUTTON_HALF_HEIGHT,
                    skin, lane, withAlpha(laneColor, 225));
            if (!bars) {
                // Hollow, as the HUD's buttons are, so a note that reaches one fills it.
                worldCore(sink, skin, lane, center, World.HIT_Y, World.button(lane, skin, aspect), aspect,
                        withAlpha(Palette.BG_1, 210));
            }
            int burst = valueAt(panel.hitBursts(), lane);
            if (burst > 0) {
                float pulse = burst / 8.0F;
                float spread = 0.015F + (1.0F - pulse) * 0.035F;
                sink.glyph(center, World.HIT_Y, World.LANE_WIDTH + spread * 2.0F, 0.095F + spread, 0.050F + spread,
                        skin, lane, withAlpha(lighten(laneColor, 255), Math.round(245 * pulse)));
            }
        }

        int owner = Mth.clamp(panel.owner(), 0, 1);
        int leadTicks = leadTicks(panel.speed());
        float spawnY = World.spawnY(skin);
        for (NoteView note : notes) {
            if (note.resolved() || note.owner() != owner || !noteVisibleAt(time, note.tick(), note.durationTicks(), leadTicks)) {
                continue;
            }
            int lane = Mth.clamp(note.lane(), 0, 3);
            int laneColor = appearance.color(lane);
            int fadeTick = note.tick() + Math.max(0, note.durationTicks());
            // The HUD's fade: full until the late window closes, gone six ticks later.
            float alpha = Mth.clamp(1.0F - Math.max(0.0F, time - fadeTick - HIT_WINDOW_TICKS) / 6.0F, 0.0F, 1.0F);
            float noteY = note.started() ? World.HIT_Y
                    : noteTravelY(time, note.tick(), leadTicks, spawnY, World.HIT_Y, World.LATE_Y);
            float center = World.laneCenter(lane);
            if (note.durationTicks() > 0) {
                float tailY = noteTravelY(time, note.tick() + note.durationTicks(), leadTicks, spawnY, World.HIT_Y,
                        World.LATE_Y);
                if (note.started()) {
                    // A hold being held ends on its button, as the HUD's does, never past it like a miss.
                    tailY = Math.min(tailY, World.HIT_Y);
                }
                World.Area tail = World.tail(lane, noteY, tailY);
                sink.quad(tail.left(), tail.top(), tail.right(), tail.bottom(), withAlpha(laneColor, Math.round(230 * alpha)));
                World.Area core = World.tailCore(lane, noteY, tailY);
                sink.quad(core.left(), core.top(), core.right(), core.bottom(), lighten(laneColor, Math.round(230 * alpha)));
            }
            if (!note.started()) {
                sink.glyph(center, noteY, World.LANE_WIDTH, World.BAR_HEAD_HALF_WIDTH, World.BAR_HEAD_HALF_HEIGHT, skin,
                        lane, withAlpha(laneColor, Math.round(245 * alpha)));
                worldCore(sink, skin, lane, center, noteY, World.head(lane, noteY, skin, aspect), aspect,
                        lighten(laneColor, Math.round(245 * alpha)));
            }
        }
    }

    /** The inside of a glyph, {@link World#RIM} in from every edge of {@code box}: a parallel inset, as the HUD's. */
    private static void worldCore(WorldSink sink, int skin, int lane, float centerX, float centerY, World.Area box,
                                  float aspect, int color) {
        if (skin == NoteAppearance.ARROWS) {
            sink.insetArrow(lane, centerX, centerY, box.width() * 0.5F, World.RIM, color);
        } else if (skin == NoteAppearance.BARS) {
            World.Area core = box.inset(World.RIM);
            sink.quad(core.left(), core.top(), core.right(), core.bottom(), color);
        } else {
            sink.glyph(centerX, centerY, World.room(World.LANE_WIDTH, World.RIM), 0F, 0F, skin, lane, color);
        }
    }

    // ------------------------------------------------------------------------------------------------ the header

    /**
     * What the header shows: the song's title and cover, how far the song has got, the seconds left, the count-in
     * before the first note (0 once the song plays), the beat (1 on it, falling to 0) and the panels' slide-in.
     */
    record Header(String title, ResourceLocation cover, float progress, int remainingSeconds, int countdown, float beat,
                  float intro) {
    }

    /**
     * The header card, in the battle HUD's own units. It is where the old dark box was, exactly as wide and no taller:
     * centred, 12 units down, 24 tall, so it ends where the top stage bar does and the panels below it, the bars and
     * the effects keep their places. The cover takes the left of the card; to its right the title and the time left
     * share the first line and the progress bar runs under them. The count-in pill hangs four units under the card.
     */
    record HeaderLayout(int x, int y, int width, int height) {
        static final int TOP = 12;
        static final int HEIGHT = 24;
        static final int MAX_WIDTH = 420;
        /** The canvas width the card leaves free on each side together, as the old box did. */
        static final int SIDE_ROOM = 80;
        static final float PAD = 3F;
        static final float TEXT_GAP = 7F;
        static final float RIGHT_PAD = 8F;
        /** Between the end of a fitted title and the time on its right. */
        static final float STATUS_GAP = 8F;
        static final float BAR_TOP = 16F;
        static final float BAR_HEIGHT = 3F;
        static final float PILL_GAP = 4F;
        static final float PILL_HEIGHT = 12F;
        static final float PILL_PAD = 7F;

        static HeaderLayout of(int canvasWidth) {
            return centred(canvasWidth, canvasWidth - SIDE_ROOM);
        }

        /**
         * The card centred on a canvas at most {@code width} wide, for a screen with less room across than the battle:
         * the editor's gameplay preview keeps it between its corner buttons.
         */
        static HeaderLayout centred(int canvasWidth, int width) {
            int clamped = Math.max(0, Math.min(width, MAX_WIDTH));
            return new HeaderLayout(canvasWidth / 2 - clamped / 2, TOP, clamped, HEIGHT);
        }

        float cover() {
            return height - PAD * 2;
        }

        float coverX() {
            return x + PAD;
        }

        float coverY() {
            return y + PAD;
        }

        float textX() {
            return coverX() + cover() + TEXT_GAP;
        }

        float textRight() {
            return x + width - RIGHT_PAD;
        }

        /** The title's cap line: with the bar under it, the two are centred on the card as one block. */
        float titleY() {
            return y + 5F;
        }

        /** The time on the title's baseline, so the two words of different sizes stand on one line. */
        float statusY(TextStyle title, TextStyle status) {
            return titleY() + title.capHeight() - status.capHeight();
        }

        Rect bar() {
            return new Rect(textX(), y + BAR_TOP, Math.max(0F, textRight() - textX()), BAR_HEIGHT);
        }

        /** What a title may take beside a status of this width; the status keeps its own room. */
        float titleRoom(float statusWidth) {
            return Math.max(0F, textRight() - textX() - statusWidth - STATUS_GAP);
        }

        Rect pill(float textWidth) {
            float width = textWidth + PILL_PAD * 2;
            return new Rect(x + this.width * 0.5F - width * 0.5F, y + height + PILL_GAP, width, PILL_HEIGHT);
        }
    }

    static HeaderLayout headerLayout(int canvasWidth) {
        return HeaderLayout.of(canvasWidth);
    }

    /** The header card over a battle; the caller has pushed the HUD's scale (BattleHudScale) and flushes afterwards. */
    static void drawHeader(GuiGraphics graphics, int canvasWidth, Header header) {
        drawHeader(graphics, HeaderLayout.of(canvasWidth), header);
    }

    /** The header card in a given place; see {@link HeaderLayout#centred}. */
    static void drawHeader(GuiGraphics graphics, HeaderLayout layout, Header header) {
        if (layout.width() <= 0) {
            return;
        }
        HudText text = HudText.of(graphics);
        drawCard(graphics, layout);
        drawCover(graphics, layout, header.cover());
        if (header.beat() > 0.01F) {
            // The cover used to swell on the beat; a frame that lights up on it keeps the pulse without resizing a
            // picture on the pixel grid.
            float ring = 1.5F;
            RoundedRect.stroke(graphics, layout.coverX() - ring, layout.coverY() - ring, layout.cover() + ring * 2,
                    layout.cover() + ring * 2, Palette.RADIUS_SM + ring, 1.2F,
                    Palette.withAlpha(Palette.ACCENT, Mth.clamp(header.beat(), 0F, 1F) * 0.9F));
        }
        int remaining = Math.max(0, header.remainingSeconds());
        String time = String.format(Locale.ROOT, "%02d:%02d", remaining / 60, remaining % 60);
        drawTitleRow(graphics, text, layout, header.title(), text.literal(time, TextStyle.LABEL),
                text.literal("00:00", TextStyle.LABEL), Palette.TEXT_2);
        WorkshopDraw.bar(graphics, layout.bar(), Mth.clamp(header.progress(), 0F, 1F), Palette.BG_0, Palette.ACCENT);
        if (header.countdown() > 0) {
            float alpha = Mth.clamp(header.intro(), 0F, 1F);
            Component ready = text.styled(Component.translatable("music_battle.hud.ready",
                    String.valueOf(header.countdown())), TextStyle.PILL);
            Rect pill = layout.pill(text.width(ready));
            // An accent pill of its own rather than words on the world: under the card there is no stage bar behind
            // it, and whatever the world is, the pill stays readable.
            RoundedRect.pill(graphics, pill.x(), pill.y(), pill.w(), pill.h(), Palette.withAlpha(Palette.ACCENT, alpha));
            text.draw(graphics, ready.getVisualOrderText(), pill.x() + HeaderLayout.PILL_PAD,
                    pill.y() + (pill.h() - TextStyle.PILL.capHeight()) * 0.5F, Palette.fade(Palette.ON_PILL, alpha));
        }
    }

    /**
     * The bar shown while a battle's beatmap downloads, in the header's place and at its size (the caller pushes the
     * battle HUD's scale): the card, the Workshop's turning ring where the cover will be, the beatmap's title, how much
     * has arrived, and the same progress bar.
     */
    static void drawLoading(GuiGraphics graphics, int canvasWidth, String title, float progress) {
        HeaderLayout layout = HeaderLayout.of(canvasWidth);
        if (layout.width() <= 0) {
            return;
        }
        HudText text = HudText.of(graphics);
        drawCard(graphics, layout);
        RoundedRect.fill(graphics, layout.coverX(), layout.coverY(), layout.cover(), layout.cover(), Palette.RADIUS_SM,
                Palette.BG_3);
        WorkshopDraw.spinner(graphics, layout.coverX() + layout.cover() * 0.5F, layout.coverY() + layout.cover() * 0.5F,
                layout.cover() * 0.3F, Palette.ACCENT);
        float clamped = Mth.clamp(progress, 0F, 1F);
        Component status = text.styled(Component.translatable("music_battle.hud.loading",
                String.valueOf(Math.round(clamped * 100.0F))), TextStyle.LABEL);
        Component widest = text.styled(Component.translatable("music_battle.hud.loading", "100"), TextStyle.LABEL);
        drawTitleRow(graphics, text, layout, title, status, widest, Palette.TEXT_2);
        WorkshopDraw.bar(graphics, layout.bar(), clamped, Palette.BG_0, Palette.ACCENT);
    }

    /** The near-opaque Workshop panel both cards sit on: readable over any world, a stage bar or none. */
    private static void drawCard(GuiGraphics graphics, HeaderLayout layout) {
        RoundedRect.shadow(graphics, layout.x(), layout.y() + 1, layout.width(), layout.height(), Palette.RADIUS_MD, 6F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, layout.x(), layout.y(), layout.width(), layout.height(), Palette.RADIUS_MD,
                Palette.withAlpha(Palette.BG_1, 0.94F));
        RoundedRect.stroke(graphics, layout.x(), layout.y(), layout.width(), layout.height(), Palette.RADIUS_MD, 1F,
                Palette.withAlpha(Palette.LINE, 0.9F));
    }

    /** The cover the way the Workshop's cards and the track library show one, or their placeholder without it. */
    private static void drawCover(GuiGraphics graphics, HeaderLayout layout, ResourceLocation cover) {
        if (cover != null) {
            RoundedImage.draw(graphics, cover, layout.coverX(), layout.coverY(), layout.cover(), layout.cover(),
                    Palette.RADIUS_SM, 0F, 1F);
            return;
        }
        RoundedRect.fill(graphics, layout.coverX(), layout.coverY(), layout.cover(), layout.cover(), Palette.RADIUS_SM,
                Palette.BG_3);
        Rect icon = new Rect(layout.coverX(), layout.coverY(), layout.cover(), layout.cover());
        WorkshopDraw.icon(graphics, icon.inset(layout.cover() * 0.3F, layout.cover() * 0.3F), UiIcon.MUSIC,
                Palette.withAlpha(Palette.TEXT_MUTE, 0.7F));
    }

    /**
     * The title, cut with an ellipsis in its own face where it meets the status on the right, and the status. The room
     * is measured against the widest status the row can show, so the title does not shift as the digits change.
     */
    private static void drawTitleRow(GuiGraphics graphics, HudText text, HeaderLayout layout, String title,
                                     Component status, Component widestStatus, int statusColour) {
        float statusWidth = text.width(status);
        float room = layout.titleRoom(Math.max(statusWidth, text.width(widestStatus)));
        Component titleText = text.literal(title == null ? "" : title, TextStyle.STRONG);
        text.draw(graphics, TextStyles.ellipsize(titleText, room), layout.textX(), layout.titleY(), Palette.TEXT_1);
        text.draw(graphics, status.getVisualOrderText(), layout.textRight() - statusWidth,
                layout.statusY(TextStyle.STRONG, TextStyle.LABEL), statusColour);
    }

    /**
     * Workshop text in the battle HUD. The toolkit picks a font's oversample from the GUI scale alone and snaps text to
     * the GUI's pixels, but the HUD is drawn in a pose of its own (BattleHudScale): there the glyphs would be sampled at
     * the wrong size and land between pixels. This takes the scale from the pose, so one unit's real pixels pick the
     * oversample and the snapping.
     */
    private record HudText(int tier, double pixelsX, double offsetX, double pixelsY, double offsetY, Matrix4f pose) {
        private static final Style[][] STYLES = new Style[TextStyle.values().length][TextStyle.MAX_TIER + 1];

        static HudText of(GuiGraphics graphics) {
            Matrix4f pose = graphics.pose().last().pose();
            double guiScale = TextStyles.guiScale();
            return new HudText(TextStyle.tier(guiScale, pose.m00()), pose.m00() * guiScale, pose.m30() * guiScale,
                    pose.m11() * guiScale, pose.m31() * guiScale, pose);
        }

        Style style(TextStyle style) {
            Style cached = STYLES[style.ordinal()][tier];
            if (cached == null) {
                cached = Style.EMPTY.withFont(TextStyles.fontId(style, tier));
                STYLES[style.ordinal()][tier] = cached;
            }
            return cached;
        }

        MutableComponent literal(String text, TextStyle style) {
            return Component.literal(text).withStyle(style(style));
        }

        MutableComponent styled(Component component, TextStyle style) {
            return component.copy().withStyle(style(style));
        }

        float width(Component component) {
            return TextStyles.width(component);
        }

        void draw(GuiGraphics graphics, FormattedCharSequence line, float x, float y, int argb) {
            if (line == null || (argb >>> 24) < 4) {
                return;
            }
            TextStyles.font().drawInBatch(line, snap(x, pixelsX, offsetX), snap(y, pixelsY, offsetY), argb, false, pose,
                    graphics.bufferSource(), Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }

        /** The nearest coordinate that falls on a whole screen pixel under this pose. */
        private static float snap(float value, double pixels, double offset) {
            if (!(pixels > 0.0)) {
                return value;
            }
            return (float) ((Math.round(offset + value * pixels) - offset) / pixels);
        }
    }
}
