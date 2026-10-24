package ru.gora.musicbattle.client.ui;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base for Workshop screens.
 *
 * <ul>
 *   <li>Opaque backdrop ({@link Palette#BG_0}): no vanilla blur passes, no menu texture.</li>
 *   <li>State lives in fields, not in {@code init()}: Minecraft re-runs {@code init()} on every resize and
 *       every return from a child screen (a link dialog, a confirm screen). Override {@link #layout()} for
 *       sizes and positions.</li>
 *   <li>{@link #renderUi} gets sub-unit mouse coordinates (Minecraft passes truncated ints).</li>
 *   <li>The hand cursor is requested during rendering and applied once per frame, and always reset when the
 *       screen is removed or closed.</li>
 *   <li>CPU time of the UI render is measured every frame ({@link #frameMillis()}); with
 *       {@link #countDraws()} the UI renders through a counting buffer source for draw-call statistics.</li>
 * </ul>
 * Don't stop previews or clear caches in {@code removed()}: it also runs when a dialog replaces the screen.
 */
public abstract class UiLayer extends Screen {
    private double frameMillis;
    private long lastRenderNanos;
    private UiDrawStats.CountingBufferSource countingSource;
    private int lastBatches;
    private int lastShapeBatches;
    private int lastTextBatches;
    private int lastIconBatches;
    private int lastShapes;
    private int lastImages;
    private int lastFallbacks;

    protected UiLayer(Component title) {
        super(title);
    }

    @Override
    protected final void init() {
        layout();
    }

    /** Positions and sizes from {@link #width} and {@link #height}; add vanilla widgets here. Called on every init. */
    protected void layout() {
    }

    /** Draws the screen. Mouse coordinates are sub-unit precise; {@code partialTick} is Minecraft's frame delta. */
    protected abstract void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick);

    /** Return true to render through a counting buffer source (debug and measurement screens only). */
    protected boolean countDraws() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long start = Util.getNanos();
        if (Minecraft.getInstance().screen == this) {
            HandCursor.beginFrame();
        }
        UiDrawStats.beginFrame();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
        GuiGraphics g = graphics;
        if (countDraws()) {
            if (countingSource == null) {
                countingSource = new UiDrawStats.CountingBufferSource(786_432);
            }
            countingSource.resetCounts();
            g = UiDrawStats.countingGraphics(graphics, countingSource);
        }
        renderUi(g, preciseMouseX(mouseX), preciseMouseY(mouseY), partialTick);
        g.flush();
        if (countingSource != null && countDraws()) {
            lastBatches = countingSource.batches();
            lastShapeBatches = countingSource.shapeBatches();
            lastTextBatches = countingSource.textBatches();
            lastIconBatches = countingSource.iconBatches();
        }
        lastShapes = UiDrawStats.shapes();
        lastImages = UiDrawStats.images();
        lastFallbacks = UiDrawStats.fallbacks();
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        if (Minecraft.getInstance().screen == this) {
            // Only the top layer owns the cursor; a screen under a pushed GUI layer must not fight it.
            HandCursor.apply();
        }
        long end = Util.getNanos();
        double millis = (end - start) / 1_000_000.0;
        frameMillis = lastRenderNanos == 0L ? millis : frameMillis + (millis - frameMillis) * 0.1;
        lastRenderNanos = end;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, Palette.BG_0);
    }

    @Override
    public void removed() {
        HandCursor.reset();
        if (countingSource != null) {
            countingSource.close();
            countingSource = null;
        }
        super.removed();
    }

    @Override
    public void onClose() {
        HandCursor.reset();
        super.onClose();
    }

    /** Smoothed CPU time of this screen's render, milliseconds. */
    public double frameMillis() {
        return frameMillis;
    }

    /** Draw calls of the last UI pass when {@link #countDraws()} is on, else 0. */
    protected int lastBatches() {
        return lastBatches;
    }

    protected int lastShapeBatches() {
        return lastShapeBatches;
    }

    protected int lastTextBatches() {
        return lastTextBatches;
    }

    protected int lastIconBatches() {
        return lastIconBatches;
    }

    /** SDF shapes queued, immediate image draws and vanilla fallback fills in the last UI pass. */
    protected int lastShapes() {
        return lastShapes;
    }

    protected int lastImages() {
        return lastImages;
    }

    protected int lastFallbacks() {
        return lastFallbacks;
    }

    protected double guiScale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Rounds to whole physical pixels. */
    protected float snap(double value) {
        double scale = guiScale();
        return (float) (Math.round(value * scale) / scale);
    }

    private double preciseMouseX(int fallback) {
        Minecraft mc = Minecraft.getInstance();
        if (fallback == Integer.MAX_VALUE || mc.getWindow().getScreenWidth() <= 0) {
            return fallback;
        }
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }

    private double preciseMouseY(int fallback) {
        Minecraft mc = Minecraft.getInstance();
        if (fallback == Integer.MAX_VALUE || mc.getWindow().getScreenHeight() <= 0) {
            return fallback;
        }
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }
}
