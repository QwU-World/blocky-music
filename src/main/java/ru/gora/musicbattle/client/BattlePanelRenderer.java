package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.NoteSpeed;

/** Shared battle-panel renderer used by both a live battle and the editor gameplay preview. */
final class BattlePanelRenderer {
    static final int HIT_WINDOW_TICKS = 16;
    static final int MISS_AFTER_PANEL_EXIT_TICKS = 7;
    static final int EARLY_HIT_WINDOW_TICKS = 6;
    static final int BASE_LEAD_TICKS = 54;

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

    static Layout layout(int width, int height, float intro, Panel panel) {
        int screenSide = Mth.clamp(panel.screenSide(), 0, 1);
        int panelWidth = Mth.clamp(Math.round(width / 3.0F * panel.widthScale()), 92, 320);
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

    static void draw(GuiGraphics graphics, Font font, int width, int height, float time, int bpm, float intro,
                     List<? extends NoteView> notes, Panel panel) {
        int owner = Mth.clamp(panel.owner(), 0, 1);
        Layout layout = layout(width, height, intro, panel);
        int x = layout.x();
        int y = layout.y();
        int panelWidth = layout.width();
        int panelHeight = layout.height();
        int laneWidth = panelWidth / 4;
        int hitY = y + panelHeight - (panel.appearance().skin() == NoteAppearance.BARS ? 38
                : Math.max(38, glyphSize(laneWidth, panel) / 2 + 22));
        float spawnY = y + (panel.appearance().skin() == NoteAppearance.BARS ? 24
                : glyphSize(laneWidth, panel) / 2 + 19);
        int leadTicks = leadTicks(panel.speed());
        int color = panel.color();
        int panelColor = withAlpha(color, Math.round((panel.own() ? 110 : 96) * panel.panelOpacity() * intro));

        graphics.fill(x, y, x + panelWidth, y + panelHeight, panelColor);
        graphics.fill(x, y, x + panelWidth, y + 14, withAlpha(color, Math.round(190 * intro)));
        graphics.drawCenteredString(font, panel.name(), x + panelWidth / 2, y + 4, 0xFFFFFFFF);

        for (int lane = 0; lane < 4; lane++) {
            int laneX = x + lane * laneWidth;
            int laneColor = panel.appearance().color(lane);
            int flash = valueAt(panel.laneFlashes(), lane);
            int laneAlpha = Math.round((flash > 0 ? 92 + flash * 14 : 42) * panel.laneOpacity());
            graphics.fill(laneX + 1, y + 16, laneX + laneWidth - 1, y + panelHeight - 9,
                    withAlpha(laneColor, Math.round(laneAlpha * intro)));
            int glyphSize = glyphSize(laneWidth, panel);
            if (panel.appearance().skin() == NoteAppearance.BARS) {
                graphics.fill(laneX + 3, hitY, laneX + laneWidth - 3, hitY + 4, withAlpha(laneColor, 225));
            } else {
                // The hollow of the hit target is the Workshop's page-panel shade, so it sits in the same family as
                // the settings backdrop and every palette lane colour keeps its contrast against it.
                drawShapedGlyph(graphics, panel, lane, laneX + laneWidth / 2, hitY, glyphSize,
                        withAlpha(laneColor, Math.round(220 * intro)), withAlpha(Palette.BG_1, Math.round(210 * intro)));
            }
            int burst = valueAt(panel.hitBursts(), lane);
            if (burst > 0) {
                float pulse = burst / 8.0F;
                int spread = Math.round((1.0F - pulse) * 9.0F);
                int burstAlpha = Math.round(210.0F * pulse * intro);
                if (panel.appearance().skin() == NoteAppearance.BARS) {
                    graphics.fill(laneX + 2 - spread, hitY - 4 - spread, laneX + laneWidth - 2 + spread, hitY + 8 + spread,
                            withAlpha(lighten(laneColor, 255), burstAlpha));
                    graphics.fill(laneX + 7, hitY - 1, laneX + laneWidth - 7, hitY + 5,
                            argb(Math.round(240.0F * pulse * intro), 255, 255, 255));
                } else {
                    drawShapedGlyph(graphics, panel, lane, laneX + laneWidth / 2, hitY, glyphSize + spread * 2,
                            withAlpha(laneColor, burstAlpha), withAlpha(0xFFFFFFFF, burstAlpha));
                }
            }
            drawCenteredFittedString(graphics, font, labelAt(panel.laneLabels(), lane), laneX + laneWidth / 2,
                    hitY + (panel.appearance().skin() == NoteAppearance.BARS ? 8 : glyphSize / 2 + 3), laneWidth - 6, 0xFFFFFFFF);
        }

        for (NoteView note : notes) {
            if (note.resolved() || note.owner() != owner || !noteVisibleAt(time, note.tick(), note.durationTicks(), leadTicks)) {
                continue;
            }
            int laneX = x + note.lane() * laneWidth;
            int lateY = hitY + 86;
            int noteY = note.started() ? hitY : Math.round(noteTravelY(time, note.tick(), leadTicks, spawnY, hitY, lateY));
            int fadeTick = note.tick() + Math.max(0, note.durationTicks());
            int alpha = (int) (Mth.clamp(1.0F - Math.max(0.0F, time - fadeTick - HIT_WINDOW_TICKS) / 6.0F, 0.0F, 1.0F) * 255.0F);
            int laneColor = panel.appearance().color(note.lane());
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
                int top = Math.min(noteY, tailY);
                int bottom = Math.max(noteY, tailY);
                graphics.fill(laneX + laneWidth / 2 - 3, top, laneX + laneWidth / 2 + 4, bottom, outer);
                graphics.fill(laneX + laneWidth / 2 - 1, top, laneX + laneWidth / 2 + 2, bottom, inner);
            }
            if (!note.started()) {
                if (panel.appearance().skin() != NoteAppearance.BARS) {
                    drawShapedGlyph(graphics, panel, note.lane(), laneX + laneWidth / 2, noteY,
                            glyphSize(laneWidth, panel), outer, inner);
                    continue;
                }
                int noteHalfHeight = Math.max(3, Math.round(7 * panel.noteHeightScale()));
                int baseInset = Math.max(3, Math.round(5 / Math.max(0.35F, panel.noteWidthScale())));
                int noteInset = Mth.clamp(
                        Math.round((laneWidth * (1.0F - Mth.clamp(panel.noteWidthScale(), 0.35F, 2.2F) * 0.84F)) * 0.5F),
                        baseInset,
                        Math.max(baseInset, laneWidth / 2 - 4)
                );
                graphics.fill(laneX + noteInset, noteY - noteHalfHeight, laneX + laneWidth - noteInset, noteY + noteHalfHeight + 2, outer);
                graphics.fill(laneX + noteInset + 2, noteY - Math.max(3, noteHalfHeight - 2),
                        laneX + laneWidth - noteInset - 2, noteY + Math.max(4, noteHalfHeight - 1), inner);
            }
        }

        float comboPulse = 1.0F + beatPulse(time, bpm) * 0.18F;
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

    private static int glyphSize(int laneWidth, Panel panel) {
        float scale = (float) Math.sqrt(Mth.clamp(panel.noteWidthScale(), .35F, 2.2F)
                * Mth.clamp(panel.noteHeightScale(), .35F, 2.2F));
        return Math.max(6, Math.round((laneWidth - 6) * Math.min(1F, scale)));
    }

    private static void drawShapedGlyph(GuiGraphics graphics, Panel panel, int lane, int centerX, int centerY,
                                        int size, int outer, int inner) {
        int half = size / 2;
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
                centerX - half, centerY - half, centerX + half, centerY + half, outer);
        if (panel.appearance().skin() == NoteAppearance.ARROWS) {
            BattleNoteShape.drawInsetArrowTriangles(fill, lane, centerX, centerY, half, 2F, inner);
        } else {
            BattleNoteShape.drawTriangles(fill, panel.appearance().skin(), lane,
                    centerX - half + 2, centerY - half + 2, centerX + half - 2, centerY + half - 2, inner);
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

    private static float beatPulse(float time, int bpm) {
        if (bpm <= 0 || time < 0.0F) {
            return 0.0F;
        }
        float beatTicks = 20.0F * 60.0F / bpm;
        float phase = (time % beatTicks) / beatTicks;
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

    private static void drawCenteredFittedString(GuiGraphics graphics, Font font, String text, int centerX, int y,
                                                 int maxWidth, int color) {
        int textWidth = Math.max(1, font.width(text));
        float scale = Math.min(1.0F, maxWidth / (float) textWidth);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, y, 0.0F);
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
}
