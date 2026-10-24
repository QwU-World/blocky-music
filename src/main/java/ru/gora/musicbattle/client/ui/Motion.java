package ru.gora.musicbattle.client.ui;

import java.util.function.LongSupplier;
import net.minecraft.Util;

/**
 * Wall-clock animation. The float passed to {@code Screen.render} is the frame delta in ticks, not a partial
 * tick, and partial ticks freeze while a pause screen pauses singleplayer, so UI motion runs on
 * {@link Util#getNanos()} instead. Every class takes an injectable clock for deterministic tests.
 *
 * <p>Curves and scroll constants follow osu-framework 2026.914.0 ({@code DefaultEasingFunction},
 * {@code ScrollContainer}); credited in NOTICE.md.
 */
public final class Motion {
    /** Minecraft's monotonic clock. Resolved lazily so pure tests never load {@code Util}. */
    public static final LongSupplier GAME_CLOCK = Motion::gameNanos;

    private Motion() {
    }

    private static long gameNanos() {
        return Util.getNanos();
    }

    public enum Easing {
        NONE, IN, OUT, IN_OUT, IN_QUAD, OUT_QUAD, IN_OUT_QUAD, IN_CUBIC, OUT_CUBIC, IN_OUT_CUBIC,
        IN_QUART, OUT_QUART, IN_OUT_QUART, IN_QUINT, OUT_QUINT, IN_OUT_QUINT, IN_SINE, OUT_SINE, IN_OUT_SINE,
        IN_EXPO, OUT_EXPO, IN_BACK, OUT_BACK;

        private static final double BACK = 1.70158;
        private static final double EXPO_OFFSET = Math.pow(2, -10);

        /** Maps 0..1 to the eased progress; input outside 0..1 is clamped. */
        public double apply(double time) {
            double t = time != time ? 0.0 : Math.max(0.0, Math.min(1.0, time));
            switch (this) {
                case IN, IN_QUAD:
                    return t * t;
                case OUT, OUT_QUAD:
                    return t * (2 - t);
                case IN_OUT, IN_OUT_QUAD:
                    if (t < .5) return t * t * 2;
                    t -= 1;
                    return t * t * -2 + 1;
                case IN_CUBIC:
                    return t * t * t;
                case OUT_CUBIC:
                    t -= 1;
                    return t * t * t + 1;
                case IN_OUT_CUBIC:
                    if (t < .5) return t * t * t * 4;
                    t -= 1;
                    return t * t * t * 4 + 1;
                case IN_QUART:
                    return t * t * t * t;
                case OUT_QUART:
                    t -= 1;
                    return 1 - t * t * t * t;
                case IN_OUT_QUART:
                    if (t < .5) return t * t * t * t * 8;
                    t -= 1;
                    return t * t * t * t * -8 + 1;
                case IN_QUINT:
                    return t * t * t * t * t;
                case OUT_QUINT:
                    t -= 1;
                    return t * t * t * t * t + 1;
                case IN_OUT_QUINT:
                    if (t < .5) return t * t * t * t * t * 16;
                    t -= 1;
                    return t * t * t * t * t * 16 + 1;
                case IN_SINE:
                    return 1 - Math.cos(t * Math.PI * .5);
                case OUT_SINE:
                    return Math.sin(t * Math.PI * .5);
                case IN_OUT_SINE:
                    return .5 - .5 * Math.cos(Math.PI * t);
                case IN_EXPO:
                    return Math.pow(2, 10 * (t - 1)) + EXPO_OFFSET * (t - 1);
                case OUT_EXPO:
                    return -Math.pow(2, -10 * t) + 1 + EXPO_OFFSET * t;
                case IN_BACK:
                    return t * t * ((BACK + 1) * t - BACK);
                case OUT_BACK:
                    t -= 1;
                    return t * t * ((BACK + 1) * t + BACK) + 1;
                case NONE:
                default:
                    return t;
            }
        }
    }

    /**
     * A value that eases to a target over a fixed duration, like osu!'s {@code FadeTo(value, 360, OutQuint)}.
     * Retargeting starts from the current value, so interrupted animations never jump.
     */
    public static final class Tween {
        private final LongSupplier clock;
        private double from;
        private double to;
        private long startNanos;
        private long durationNanos;
        private Easing easing = Easing.OUT_QUINT;

        public Tween(double initial) {
            this(initial, GAME_CLOCK);
        }

        public Tween(double initial, LongSupplier clock) {
            this.clock = clock;
            this.from = initial;
            this.to = initial;
        }

        /** Jumps to a value with no animation. */
        public void set(double value) {
            from = value;
            to = value;
            durationNanos = 0L;
        }

        public void animateTo(double target, double durationMs, Easing easing) {
            animateTo(target, durationMs, easing, 0.0);
        }

        /** Starts easing to {@code target} after {@code delayMs}; a no-op if already heading there. */
        public void animateTo(double target, double durationMs, Easing easing, double delayMs) {
            if (target == to) {
                return;
            }
            long now = clock.getAsLong();
            from = valueAt(now);
            to = target;
            this.easing = easing == null ? Easing.NONE : easing;
            startNanos = now + Math.max(0L, (long) (delayMs * 1_000_000.0));
            durationNanos = Math.max(0L, (long) (durationMs * 1_000_000.0));
        }

        public double target() {
            return to;
        }

        public float value() {
            return (float) valueAt(clock.getAsLong());
        }

        public boolean isAnimating() {
            long now = clock.getAsLong();
            return durationNanos > 0L && now < startNanos + durationNanos && from != to;
        }

        double valueAt(long now) {
            if (durationNanos <= 0L) {
                return now < startNanos ? from : to;
            }
            if (now <= startNanos) {
                return from;
            }
            long elapsed = now - startNanos;
            if (elapsed >= durationNanos) {
                return to;
            }
            double progress = easing.apply(elapsed / (double) durationNanos);
            return from + (to - from) * progress;
        }
    }

    /**
     * osu-framework's ScrollContainer: the target moves by whole steps, the position follows it exponentially
     * ({@code current = lerp(target, current, exp(-decay * elapsedMs))}), and overscroll is pulled back with a
     * clamping force. Distances are GUI units; osu! values are scaled by {@link Palette#PX}.
     */
    public static final class SmoothScroll {
        /** ScrollDistance 80 osu px per wheel notch. */
        public static final double WHEEL_STEP = 80.0 * Palette.PX;
        /** ClampExtension 500 osu px: how far past the ends the target may go. */
        public static final double CLAMP_EXTENSION = 500.0 * Palette.PX;
        public static final double DECAY_SCROLL = 0.01;
        public static final double DECAY_PRECISE = 0.05;
        public static final double DECAY_DRAG = 0.0035;
        public static final double DECAY_JUMP = 0.01;
        public static final double DECAY_CLAMPING = 0.012;
        /** Frames longer than this (a hitch, a dragged window) advance the model by this much only. */
        public static final double MAX_FRAME_MS = 100.0;
        private static final double EPSILON = 1.0e-7;

        private final LongSupplier clock;
        private double current;
        private double target;
        private double extent;
        private double decay = DECAY_SCROLL;
        private boolean dragging;
        private long lastNanos;

        public SmoothScroll() {
            this(GAME_CLOCK);
        }

        public SmoothScroll(LongSupplier clock) {
            this.clock = clock;
            this.lastNanos = clock.getAsLong();
        }

        /** Scrollable distance: content height minus viewport height (never negative). */
        public void setExtent(double scrollableExtent) {
            extent = Math.max(0.0, scrollableExtent);
            target = clamp(target, CLAMP_EXTENSION);
        }

        public double extent() {
            return extent;
        }

        /**
         * A wheel or trackpad event. {@code scrollY} is Minecraft's value, already multiplied by the player's
         * wheel sensitivity; a fractional value means a precise device, which uses the faster decay.
         */
        public void onWheel(double scrollY) {
            onWheel(scrollY, WHEEL_STEP);
        }

        public void onWheel(double scrollY, double step) {
            if (scrollY != scrollY || scrollY == 0.0) {
                return;
            }
            boolean precise = scrollY != Math.rint(scrollY);
            scrollTo(target - scrollY * step, true, precise ? DECAY_PRECISE : DECAY_SCROLL);
        }

        public void scrollTo(double value, boolean animated) {
            scrollTo(value, animated, DECAY_JUMP);
        }

        private void scrollTo(double value, boolean animated, double distanceDecay) {
            target = clamp(value, CLAMP_EXTENSION);
            if (animated) {
                decay = distanceDecay;
            } else {
                current = target;
            }
        }

        /** Scrollbar or content drag: position follows the pointer directly, within bounds. */
        public void dragTo(double value) {
            dragging = true;
            target = clamp(value, 0.0);
            current = target;
        }

        public void endDrag() {
            dragging = false;
            decay = DECAY_DRAG;
        }

        public double target() {
            return target;
        }

        /** Advance to now. Call once per frame at the start of render; returns the position. */
        public double update() {
            long now = clock.getAsLong();
            double elapsed = Math.max(0.0, Math.min(MAX_FRAME_MS, (now - lastNanos) / 1_000_000.0));
            lastNanos = now;
            double localDecay = decay;
            if (!dragging && current != clamp(current, 0.0) && target != clamp(target, -0.01)) {
                target = clamp(target, CLAMP_EXTENSION);
                if ((current < target && target < 0) || (current > target && target > extent)) {
                    localDecay = DECAY_CLAMPING * 2;
                }
                target = lerp(clamp(target, 0.0), target, Math.exp(-DECAY_CLAMPING * elapsed));
                double clamped = clamp(target, 0.0);
                if (Math.abs(clamped - target) <= EPSILON) {
                    target = clamped;
                }
            }
            current = lerp(target, current, Math.exp(-localDecay * elapsed));
            if (Math.abs(current - target) <= EPSILON) {
                current = target;
            }
            return current;
        }

        public double current() {
            return current;
        }

        /** The position rounded to whole physical pixels, so text at rest never lands between pixels. */
        public float snapped(double guiScale) {
            double scale = guiScale > 0.0 ? guiScale : 1.0;
            return (float) (Math.round(current * scale) / scale);
        }

        private double clamp(double position, double extension) {
            return Math.max(Math.min(position, extent + extension), -extension);
        }

        private static double lerp(double start, double end, double amount) {
            return start + (end - start) * amount;
        }
    }
}
