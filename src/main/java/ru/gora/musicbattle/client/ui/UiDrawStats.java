package ru.gora.musicbattle.client.ui;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Per-frame draw counters for measuring a screen (research section 13.3): SDF shapes queued, immediate image
 * draws, vanilla fallback fills, and, when the screen renders through {@link #countingGraphics}, the number
 * of batches actually flushed per render type. Render thread only.
 */
public final class UiDrawStats {
    private static int shapes;
    private static int images;
    private static int fallbacks;

    private UiDrawStats() {
    }

    static void shape() {
        shapes++;
    }

    static void image() {
        images++;
    }

    static void fallback() {
        fallbacks++;
    }

    /** Clears the per-frame counters. */
    public static void beginFrame() {
        shapes = 0;
        images = 0;
        fallbacks = 0;
    }

    public static int shapes() {
        return shapes;
    }

    public static int images() {
        return images;
    }

    public static int fallbacks() {
        return fallbacks;
    }

    /**
     * A buffer source that counts batch starts per render type. Every started batch becomes one draw call
     * when it is flushed (empty batches are skipped by Minecraft and are rare here).
     */
    public static final class CountingBufferSource extends MultiBufferSource.BufferSource implements AutoCloseable {
        private int shapeBatches;
        private int textBatches;
        private int iconBatches;
        private int otherBatches;

        public CountingBufferSource(int initialBytes) {
            super(new ByteBufferBuilder(initialBytes), new LinkedHashMap<>());
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            BufferBuilder started = this.startedBuilders.get(type);
            if (started == null || !type.canConsolidateConsecutiveGeometry()) {
                if (type == UiSdf.SHAPES) {
                    shapeBatches++;
                } else if (UiIcons.isIconType(type)) {
                    iconBatches++;
                } else if (type.toString().startsWith("RenderType[text")) {
                    textBatches++;
                } else {
                    otherBatches++;
                }
            }
            return super.getBuffer(type);
        }

        public void resetCounts() {
            shapeBatches = 0;
            textBatches = 0;
            iconBatches = 0;
            otherBatches = 0;
        }

        public int shapeBatches() {
            return shapeBatches;
        }

        public int textBatches() {
            return textBatches;
        }

        public int iconBatches() {
            return iconBatches;
        }

        public int otherBatches() {
            return otherBatches;
        }

        /** Batches flushed so far this frame, all render types. */
        public int batches() {
            return shapeBatches + textBatches + iconBatches + otherBatches;
        }

        @Override
        public void close() {
            endBatch();
            this.sharedBuffer.close();
        }
    }

    /**
     * A GuiGraphics that draws through {@code source} with the pose of {@code vanilla}. Draw into it, then
     * flush it before anything else is drawn through the vanilla one.
     */
    public static GuiGraphics countingGraphics(GuiGraphics vanilla, CountingBufferSource source) {
        GuiGraphics counting = new GuiGraphics(Minecraft.getInstance(), source);
        counting.pose().last().pose().set(vanilla.pose().last().pose());
        counting.pose().last().normal().set(vanilla.pose().last().normal());
        return counting;
    }
}
