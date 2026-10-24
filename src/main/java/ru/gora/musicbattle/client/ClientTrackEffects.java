package ru.gora.musicbattle.client;

import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ru.gora.musicbattle.data.TrackEffect;

public final class ClientTrackEffects {
    /**
     * The canvas the effects lay themselves out in, in the units of whoever is drawing.
     *
     * The battle HUD draws in a space of its own so that it keeps the size it was designed at whatever the player's
     * GUI scale is (see {@link BattleHudScale}). Effects are part of that picture - a flash covers the screen, a
     * caption sits at a fraction of it - so they have to be told the same canvas rather than reading the GUI's.
     * Everything that draws effects outside a battle leaves this alone and gets the GUI canvas as before.
     */
    private static int canvasWidth;
    private static int canvasHeight;

    /** Lays the effects out in this canvas until {@link #clearCanvas()}. */
    public static void canvas(int width, int height) {
        canvasWidth = Math.max(0, width);
        canvasHeight = Math.max(0, height);
    }

    /** Back to whatever canvas the caller is drawing into. */
    public static void clearCanvas() {
        canvasWidth = 0;
        canvasHeight = 0;
    }

    private static int canvasWidth(GuiGraphics graphics) {
        return canvasWidth > 0 ? canvasWidth : graphics.guiWidth();
    }

    private static int canvasHeight(GuiGraphics graphics) {
        return canvasHeight > 0 ? canvasHeight : graphics.guiHeight();
    }

    /** Foreground depth shared by every FX that is configured to cover the battle HUD. */
    private static final float INTERFACE_OVERLAY_Z = 400.0F;
    private ClientTrackEffects() {
    }

    public static EffectState sample(List<TrackEffect> effects, float trackTick) {
        return sample(effects, trackTick, 120, 1.0F);
    }

    public static EffectState sample(List<TrackEffect> effects, float trackTick, int bpm) {
        return sample(effects, trackTick, bpm, 1.0F);
    }

    public static EffectState sample(List<TrackEffect> effects, float trackTick, int bpm, float playbackSpeed) {
        return sample(effects, trackTick, bpm, playbackSpeed, 1.0F);
    }

    public static EffectState sample(List<TrackEffect> effects, float trackTick, int bpm, float playbackSpeed,
                                     float visibilityMultiplier) {
        float effectSpeed = Mth.clamp(playbackSpeed, 1.0F, 2.0F);
        float globalVisibility = Mth.clamp(visibilityMultiplier, 0.0F, 1.0F);
        float flash = 0.0F;
        float oldTv = 0.0F;
        float shake = 0.0F;
        float pulse = 0.0F;
        float pulseBeat = 0.0F;
        float vignette = 0.0F;
        TrackEffect pulseEffect = null;
        TrackEffect flashEffect = null;
        TrackEffect oldTvEffect = null;
        TrackEffect vignetteEffect = null;
        TrackEffect visualizerEffect = null;
        TrackEffect textEffect = null;
        TrackEffect fireEffect = null;
        TrackEffect highlightEffect = null;
        TrackEffect objectOutlineEffect = null;
        boolean hideBattlePanels = false;
        List<TrackEffect> overlayEffects = new ArrayList<>();
        if (effects != null && trackTick >= 0.0F) {
            for (TrackEffect effect : effects) {
                if (effect.type() != TrackEffect.Type.PULSE) continue;
                float amount = amount(effect, trackTick, effectSpeed) * globalVisibility;
                if (amount <= 0.0F) continue;
                float value = amount * effect.intensity() * effect.opacity();
                if (value >= pulse) pulseEffect = effect;
                pulse = Math.min(5.0F, Math.max(pulse, value));
                pulseBeat = Math.max(pulseBeat, value * beatEnvelope(effect, trackTick, bpm, effectSpeed));
            }
            for (TrackEffect effect : effects) {
                if (effect.type() == TrackEffect.Type.HIDE_BATTLE_PANELS) {
                    if (globalVisibility > 0.001F && withinTimeline(effect, trackTick, effectSpeed)) {
                        hideBattlePanels = true;
                    }
                    continue;
                }
                if (effect.type() == TrackEffect.Type.HIDE_PARTICLES_HAND) {
                    // Visibility switches have a precise interval, not an opacity envelope.
                    if (globalVisibility > 0.001F && trackTick >= effect.tick()
                            && trackTick < effect.tick() + playbackDuration(effect, effectSpeed)) {
                        overlayEffects.add(effect);
                    }
                    continue;
                }
                float amount = amount(effect, trackTick, effectSpeed) * globalVisibility;
                if (amount <= 0.0F) {
                    continue;
                }
                float reactive = effect.beatReactive() ? 1.0F + Mth.clamp(pulseBeat, 0.0F, 5.0F) * 0.38F : 1.0F;
                float value = amount * effect.intensity() * reactive;
                if (effect.type() != TrackEffect.Type.SHAKE
                        && effect.type() != TrackEffect.Type.PLAYER_HIGHLIGHT) {
                    overlayEffects.add(effect);
                }
                switch (effect.type()) {
                    case FLASH -> {
                        float candidate = value * effect.opacity() * flashModulation(effect, trackTick, bpm, effectSpeed);
                        if (candidate >= flash) flashEffect = effect;
                        flash = Math.max(flash, candidate);
                    }
                    case OLD_TV -> {
                        float candidate = value * effect.opacity();
                        if (candidate >= oldTv) oldTvEffect = effect;
                        oldTv = Math.max(oldTv, candidate);
                    }
                    case FISH_EYE -> { }
                    case CINEMATIC_BARS -> { }
                    case SHAKE -> shake = Math.min(5.0F, shake + value);
                    case PULSE -> { }
                    case VISUALIZER -> visualizerEffect = stronger(visualizerEffect, effect, trackTick, effectSpeed);
                    case TEXT -> textEffect = stronger(textEffect, effect, trackTick, effectSpeed);
                    case PIXEL_FIRE -> fireEffect = stronger(fireEffect, effect, trackTick, effectSpeed);
                    case STARS -> { }
                    case PLAYER_HIGHLIGHT -> highlightEffect = higherPriority(highlightEffect, effect, trackTick, effectSpeed);
                    case OBJECT_OUTLINE -> objectOutlineEffect = higherPriority(objectOutlineEffect, effect, trackTick, effectSpeed);
                    case HIDE_BATTLE_PANELS, HIDE_PARTICLES_HAND -> { }
                    case COLOR_CORRECTION -> { }
                    case VIGNETTE -> {
                        float candidate = value * effect.opacity();
                        if (candidate >= vignette) vignetteEffect = effect;
                        vignette = Math.max(vignette, candidate);
                    }
                }
            }
        }
        // The legacy world pass selected exactly one active block outline. Preserve that
        // selection while keeping its original chart position in the ordered FX list.
        TrackEffect selectedObjectOutline = objectOutlineEffect;
        overlayEffects.removeIf(effect -> effect.type() == TrackEffect.Type.OBJECT_OUTLINE
                && effect != selectedObjectOutline);
        // List.sort is stable: rows are composed back-to-front, while effects sharing a
        // row keep the chart's source order. This makes exact-row overlaps deterministic
        // and avoids the old PULSE pre-pass silently moving pulses ahead of their peers.
        overlayEffects.sort(Comparator.comparingInt(ClientTrackEffects::globalRow).reversed());
        return new EffectState(flash, oldTv, shake, pulse, pulseBeat, vignette, trackTick,
                pulseEffect, visualizerEffect, textEffect, fireEffect, highlightEffect, objectOutlineEffect,
                hideBattlePanels, flashEffect, oldTvEffect, vignetteEffect, List.copyOf(overlayEffects), bpm, effectSpeed,
                globalVisibility, List.of());
    }

    private static int globalRow(TrackEffect effect) {
        return effect.renderOrder();
    }

    private static TrackEffect higherPriority(TrackEffect current, TrackEffect candidate, float trackTick, float playbackSpeed) {
        if (current == null || globalRow(candidate) < globalRow(current)) {
            return candidate;
        }
        return globalRow(candidate) == globalRow(current) ? stronger(current, candidate, trackTick, playbackSpeed) : current;
    }

    private static TrackEffect stronger(TrackEffect current, TrackEffect candidate, float trackTick, float playbackSpeed) {
        return current == null || amount(candidate, trackTick, playbackSpeed) * candidate.intensity() >= amount(current, trackTick, playbackSpeed) * current.intensity()
                ? candidate : current;
    }

    private static float beatEnvelope(TrackEffect effect, float trackTick, int bpm, float playbackSpeed) {
        float phase = beatPhase(trackTick, bpm, playbackSpeed);
        return Mth.clamp(1.0F - phase * 4.0F, 0.0F, 1.0F);
    }

    private static float objectOutlineBeat(TrackEffect effect, float trackTick, int bpm, float playbackSpeed) {
        float phase = beatPhase(trackTick, bpm, playbackSpeed);
        float envelope = Mth.clamp(1.0F - phase * 3.2F, 0.0F, 1.0F);
        return smooth(envelope);
    }

    private static float objectOutlineAmount(TrackEffect effect, EffectState state, float baseAmount) {
        float rhythm = effect.beatReactive()
                ? 0.18F + 0.82F * objectOutlineBeat(effect, state.trackTick(), state.bpm(), state.playbackSpeed())
                : 1.0F;
        return Mth.clamp(baseAmount * effect.opacity() * rhythm
                * Mth.clamp(effect.intensity(), 0.1F, 1.5F), 0.0F, 1.0F);
    }

    /**
     * Resolves the animated outline palette from musical time. The phase is derived only from
     * the seekable track clock and playback speed, so rewinding or switching x1/x1.5/x2 always
     * returns to the same color instead of inheriting a wall-clock phase.
     */
    public static int objectOutlineColor(TrackEffect effect, float trackTick, float playbackSpeed) {
        if (effect == null) {
            return 0xFFFFFFFF;
        }
        List<Integer> colors = effect.objectOutlinePalette();
        if (colors.size() < 2 || effect.settings().objectOutlineColorSpeed() <= 0.0F) {
            return colors.getFirst();
        }
        float elapsedTicks = Math.max(0.0F, trackTick - effect.tick())
                * Mth.clamp(playbackSpeed, 1.0F, 2.0F);
        float cycle = elapsedTicks * effect.settings().objectOutlineColorSpeed() / 20.0F;
        float palettePosition = fractional(cycle) * colors.size();
        int firstIndex = Math.floorMod(Mth.floor(palettePosition), colors.size());
        int secondIndex = (firstIndex + 1) % colors.size();
        return lerpColor(colors.get(firstIndex), colors.get(secondIndex), smooth(fractional(palettePosition)));
    }

    private static float flashPulse(TrackEffect effect, float trackTick, int bpm, float playbackSpeed) {
        float softness = Mth.clamp(effect.softness(), 0.0F, 1.0F);
        float elapsed = Math.max(0.0F, trackTick - effect.tick());
        if (effect.durationTicks() <= 0) {
            float duration = playbackDuration(effect, playbackSpeed);
            float progress = Mth.clamp(elapsed / duration, 0.0F, 1.0F);
            float attack = 0.02F + softness * 0.16F;
            float rise = Mth.clamp(progress / attack, 0.0F, 1.0F);
            float fall = Mth.clamp((1.0F - progress) / Math.max(0.05F, 1.0F - attack), 0.0F, 1.0F);
            return smooth(rise) * smooth(fall) * (1.0F - softness * 0.22F);
        }
        float phase = beatPhase(trackTick, bpm, playbackSpeed);
        float attack = 0.01F + softness * 0.10F;
        float releaseEnd = 0.20F + softness * 0.34F;
        float rise = Mth.clamp(phase / attack, 0.0F, 1.0F);
        float fall = Mth.clamp((releaseEnd - phase) / Math.max(0.02F, releaseEnd - attack), 0.0F, 1.0F);
        return smooth(rise) * smooth(fall) * (1.0F - softness * 0.22F);
    }

    private static float flashModulation(TrackEffect effect, float trackTick, int bpm, float playbackSpeed) {
        return effect.flashFollowsBeat() ? flashPulse(effect, trackTick, bpm, playbackSpeed) : 1.0F;
    }

    /**
     * Reconstructs the exact musical beat length from the rounded effective BPM sent by the
     * server. This removes the accumulating x1.5 drift for odd source BPM values.
     */
    public static float beatDurationTicks(int roundedEffectiveBpm, float playbackSpeed) {
        float speed = Mth.clamp(playbackSpeed, 1.0F, 2.0F);
        int sourceBpm = Math.max(20, Math.round(Math.max(20, roundedEffectiveBpm) / speed));
        float exactEffectiveBpm = Math.max(20.0F, sourceBpm * speed);
        return 20.0F * 60.0F / exactEffectiveBpm;
    }

    /** Beat phase anchored to the song clock, independent of when a DJ effect was enabled. */
    public static float beatPhase(float trackTick, int roundedEffectiveBpm, float playbackSpeed) {
        float beatTicks = beatDurationTicks(roundedEffectiveBpm, playbackSpeed);
        return (Math.max(0.0F, trackTick) % beatTicks) / beatTicks;
    }

    /**
     * Extra travel, in ticks, that the beat has added by this moment.
     *
     * Each beat contributes a fixed amount of additional drift, nearly all of it immediately after the beat lands
     * and easing to nothing before the next. The total only ever grows, so nothing jumps backwards, and because it
     * is measured in beats rather than seconds a slow track surges as clearly as a fast one.
     */
    private static float beatSurgeTicks(float trackTick, int bpm, float playbackSpeed) {
        float beatTicks = beatDurationTicks(bpm, playbackSpeed);
        if (beatTicks <= 0.001F) {
            return 0.0F;
        }
        float beats = Math.max(0.0F, trackTick) / beatTicks;
        float whole = Mth.floor(beats);
        float within = beats - whole;
        float eased = 1.0F - (float) Math.exp(-6.0F * within);
        return (whole + eased) * beatTicks * 0.45F;
    }

    /** Explicit ranges are already scaled by the server; point FX keep an implicit type duration. */
    private static float playbackDuration(TrackEffect effect, float playbackSpeed) {
        float duration = Math.max(1.0F, effect.visibleDurationTicks());
        return effect.durationTicks() > 0 ? duration : Math.max(1.0F, duration / playbackSpeed);
    }

    private static float animationTick(TrackEffect effect, float trackTick, float playbackSpeed) {
        return effect.tick() + Math.max(0.0F, trackTick - effect.tick()) * playbackSpeed;
    }

    private static float smooth(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private static float amount(TrackEffect effect, float trackTick, float playbackSpeed) {
        float duration = playbackDuration(effect, playbackSpeed);
        float elapsed = trackTick - effect.tick();
        if (elapsed < 0.0F || elapsed > duration) {
            return 0.0F;
        }
        float legacyFadeIn;
        float legacyFadeOut;
        if (effect.durationTicks() <= 0) {
            float progress = Mth.clamp(elapsed / duration, 0.0F, 1.0F);
            legacyFadeIn = 1.0F;
            legacyFadeOut = 1.0F - progress * progress;
        } else {
            float fadeTicks = Math.min(3.0F, Math.max(1.0F, duration * 0.12F));
            legacyFadeIn = Mth.clamp((elapsed + 1.0F) / fadeTicks, 0.0F, 1.0F);
            legacyFadeOut = Mth.clamp((duration - elapsed + 1.0F) / fadeTicks, 0.0F, 1.0F);
        }
        float configuredIn = effect.settings().fadeInTicks() / playbackSpeed;
        float configuredOut = effect.settings().fadeOutTicks() / playbackSpeed;
        float fadeIn = !effect.settings().fadeInEnabled() ? 1.0F : configuredIn > 0.0F
                ? smooth(Mth.clamp(elapsed / configuredIn, 0.0F, 1.0F)) : legacyFadeIn;
        float fadeOut = !effect.settings().fadeOutEnabled() ? 1.0F : configuredOut > 0.0F
                ? smooth(Mth.clamp((duration - elapsed) / configuredOut, 0.0F, 1.0F)) : legacyFadeOut;
        return Math.min(fadeIn, fadeOut);
    }

    private static boolean withinTimeline(TrackEffect effect, float trackTick, float playbackSpeed) {
        float elapsed = trackTick - effect.tick();
        return elapsed >= 0.0F && elapsed <= playbackDuration(effect, playbackSpeed);
    }

    /** Shared visibility envelope for renderers that draw outside the regular GUI FX pass. */
    public static float visibility(TrackEffect effect, float trackTick) {
        return visibility(effect, trackTick, 1.0F);
    }

    public static float visibility(TrackEffect effect, float trackTick, float playbackSpeed) {
        return effect == null ? 0.0F : amount(effect, trackTick, Mth.clamp(playbackSpeed, 1.0F, 2.0F));
    }

    public static float shakeYaw(EffectState state) {
        return noise(state.trackTick() * state.playbackSpeed() * 1.91F + 7.0F) * state.shake() * 2.8F
                + (float)state.preceding().stream().mapToDouble(ClientTrackEffects::shakeYaw).sum();
    }

    public static float shakePitch(EffectState state) {
        return noise(state.trackTick() * state.playbackSpeed() * 2.37F + 19.0F) * state.shake() * 2.0F
                + (float)state.preceding().stream().mapToDouble(ClientTrackEffects::shakePitch).sum();
    }

    public static float shakeRoll(EffectState state) {
        return noise(state.trackTick() * state.playbackSpeed() * 1.53F + 31.0F) * state.shake() * 1.6F
                + (float)state.preceding().stream().mapToDouble(ClientTrackEffects::shakeRoll).sum();
    }

    public static float shakePixelsX(EffectState state) {
        return noise(state.trackTick() * state.playbackSpeed() * 2.11F + 43.0F) * state.shake() * 4.0F;
    }

    public static float shakePixelsY(EffectState state) {
        return noise(state.trackTick() * state.playbackSpeed() * 1.73F + 59.0F) * state.shake() * 3.0F;
    }

    public static void renderOverlay(GuiGraphics graphics, EffectState state) {
        renderOverlay(graphics, state, -1);
    }

    public static void renderBelowInterface(GuiGraphics graphics, EffectState state) {
        renderOverlay(graphics, state, 0);
    }

    public static void renderAboveInterface(GuiGraphics graphics, EffectState state) {
        renderOverlay(graphics, state, 1);
    }

    private static void renderOverlay(GuiGraphics graphics, EffectState state, int interfacePass) {
        // Flush everything queued by the current GUI/HUD layer while its original scissor and
        // viewport are still active. ImmediatelyFast can otherwise submit a batched HUD quad
        // only after FullscreenState disables the scissor, turning it into a large rectangle.
        graphics.flush();
        FullscreenState fullscreenState = FullscreenState.begin();
        // A stale GUI scissor/viewport must not clip queued fills or fullscreen shader passes.
        // This is especially visible with an opaque dark flash, where the stale rectangle
        // otherwise becomes a solid black block in the captured fish-eye frame.
        List<EffectState> sourceStates = state.states();
        TrackEffect highlight = null;
        int highlightStack = -1;
        for (int index = 0; index < sourceStates.size(); index++) {
            if (sourceStates.get(index).highlightEffect() != null) {
                highlight = sourceStates.get(index).highlightEffect();
                highlightStack = index;
            }
        }
        boolean orderedHighlight = highlight != null && ClientModelGlowEngine.hasModelHighlightMask();
        boolean preservingOutline = false;
        boolean preservingText = false;
        boolean preservingPlayers = false;
        boolean preservingEntities = false;
        boolean protectionConfigured = false;
        List<ClientFxShaderEngine.ScreenWarp> precedingWarps = new ArrayList<>();
        try {
            for (int sourceStack = 0; sourceStack < sourceStates.size(); sourceStack++) {
            EffectState sourceState = sourceStates.get(sourceStack);
            for (TrackEffect effect : sourceState.overlayEffects()) {
                if (interfacePass >= 0 && effect.settings().overlayInterface() != (interfacePass == 1)) {
                    continue;
                }
                boolean blockOnlyOutline = effect.type() == TrackEffect.Type.OBJECT_OUTLINE;
                boolean shouldPreserve = orderedHighlight
                        && shouldPreserveHighlight(sourceStack, effect, highlightStack, highlight);
                boolean shouldPreserveText = effect.type() != TrackEffect.Type.TEXT
                        && !effect.settings().overlayText() && ClientModelGlowEngine.hasTextFxMask();
                boolean shouldPreservePlayers = (blockOnlyOutline || !effect.settings().overlayPlayers())
                        && ClientModelGlowEngine.hasPlayerFxMask();
                boolean shouldPreserveEntities = (blockOnlyOutline || !effect.settings().overlayEntities())
                        && ClientModelGlowEngine.hasEntityFxMask();
                if (!protectionConfigured || shouldPreserve != preservingOutline || shouldPreserveText != preservingText
                        || shouldPreservePlayers != preservingPlayers || shouldPreserveEntities != preservingEntities) {
                    graphics.flush();
                    ClientModelGlowEngine.setFxProtection(shouldPreserve, shouldPreserveText,
                            shouldPreservePlayers, shouldPreserveEntities);
                    preservingOutline = shouldPreserve;
                    preservingText = shouldPreserveText;
                    preservingPlayers = shouldPreservePlayers;
                    preservingEntities = shouldPreserveEntities;
                    protectionConfigured = true;
                }
                renderOverlayEffect(graphics, sourceState, effect, precedingWarps);
                // Keep every FX row as its own submitted batch. A deferred modded HUD/model
                // layer must never be flushed under the next effect's stencil or shader state.
                graphics.flush();
            }
            }
        } finally {
            if (preservingOutline || preservingText || preservingPlayers || preservingEntities) {
                graphics.flush();
                ClientModelGlowEngine.setFxProtection(false, false, false, false);
            }
            graphics.flush();
            fullscreenState.restore();
        }
    }

    static boolean shouldPreserveHighlight(int effectStack, TrackEffect effect,
                                           int highlightStack, TrackEffect highlight) {
        // A larger globalRow is drawn earlier by the descending FX sort, so it sits visually below
        // the highlight and must be masked out of the highlighted pixels. DJ ranks are handed out
        // from the bottom of the mixer list, which makes this read exactly as the list does: an
        // effect listed below the highlight is protected against, one listed above paints over it.
        // Outgoing tails precede the current stack. Their local chart/DJ ranks cannot
        // raise an old mask above a new song, or let old FX damage its new highlight.
        boolean belowHighlight = effectStack < highlightStack
                || effectStack == highlightStack && highlight != null && globalRow(effect) > globalRow(highlight);
        return highlight != null && belowHighlight
                && (effect.type() != TrackEffect.Type.TEXT || highlight.settings().overlayText());
    }

    private record FullscreenState(boolean scissorEnabled, int[] scissorBox, int[] viewport,
                                   boolean mainFramebuffer) {
        private static FullscreenState begin() {
            int[] viewport = new int[4];
            int[] scissorBox = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            Minecraft minecraft = Minecraft.getInstance();
            var main = minecraft.getMainRenderTarget();
            boolean mainFramebuffer = main != null
                    && GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == main.frameBufferId;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (mainFramebuffer) {
                GL11.glViewport(0, 0, main.viewWidth, main.viewHeight);
            }
            return new FullscreenState(scissorEnabled, scissorBox, viewport, mainFramebuffer);
        }

        private void restore() {
            if (mainFramebuffer) {
                GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            }
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    private static void renderOverlayEffect(GuiGraphics graphics, EffectState state, TrackEffect effect,
                                            List<ClientFxShaderEngine.ScreenWarp> precedingWarps) {
        float baseAmount = amount(effect, state.trackTick(), state.playbackSpeed()) * state.visibilityMultiplier();
        if (baseAmount <= 0.0F) {
            return;
        }
        float pulseBeat = state.pulseBeat();
        float reactive = effect.beatReactive() ? 1.0F + Mth.clamp(pulseBeat, 0.0F, 5.0F) * 0.38F : 1.0F;
        float value = baseAmount * effect.intensity() * reactive;
        boolean aboveInterface = effect.settings().overlayInterface();
        if (aboveInterface) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, INTERFACE_OVERLAY_Z);
        }
        try {
            switch (effect.type()) {
                case PULSE -> addWarp(precedingWarps,
                        renderPulse(graphics, effect, baseAmount, state.trackTick(), state.bpm(), state.playbackSpeed()));
                case OLD_TV -> renderOldTv(graphics, effect, value * effect.opacity(),
                        animationTick(effect, state.trackTick(), state.playbackSpeed()), state.pulseBeat());
                case FISH_EYE -> addWarp(precedingWarps,
                        renderFishEye(graphics, effect, value, baseAmount, pulseBeat));
                case CINEMATIC_BARS -> renderCinematicBars(graphics, effect, baseAmount, reactive,
                        state.trackTick(), state.playbackSpeed());
                case VIGNETTE -> renderVignette(graphics, effect, value, state.pulseBeat());
                case FLASH -> renderFlash(graphics, effect,
                        value * effect.opacity() * flashModulation(effect, state.trackTick(), state.bpm(), state.playbackSpeed()), state.pulseBeat());
                case VISUALIZER -> renderVisualizer(graphics, effect, baseAmount, state.trackTick(), state.pulseBeat());
                case PIXEL_FIRE -> renderPixelFire(graphics, effect, baseAmount, state.trackTick(), state.pulseBeat(), state.playbackSpeed());
                case STARS -> renderStars(graphics, effect, baseAmount, state.trackTick(), state.pulseBeat(),
                        state.playbackSpeed(), state.bpm());
                case TEXT -> renderText(graphics, effect, baseAmount, state.trackTick(), state.pulseBeat(), state.playbackSpeed());
                case COLOR_CORRECTION -> renderColorCorrection(effect, value * effect.opacity());
                case OBJECT_OUTLINE -> {
                    float outlineAmount = objectOutlineAmount(effect, state, baseAmount);
                    if (outlineAmount > 0.001F) {
                        // Submit any queued GUI work first; this shader composites directly into
                        // the main target at this exact FX row.
                        graphics.flush();
                        int outlineColor = objectOutlineColor(effect, state.trackTick(), state.playbackSpeed());
                        ClientFxShaderEngine.renderObjectOutline(effect, outlineAmount, outlineColor,
                                precedingWarps);
                    }
                }
                case SHAKE, PLAYER_HIGHLIGHT, HIDE_BATTLE_PANELS, HIDE_PARTICLES_HAND -> { }
            }
        } finally {
            if (aboveInterface) {
                graphics.pose().popPose();
            }
        }
    }

    private static void addWarp(List<ClientFxShaderEngine.ScreenWarp> warps,
                                ClientFxShaderEngine.ScreenWarp warp) {
        if (warp == null) return;
        // DJ stacks are bounded, but retain the transforms nearest to the outline if an old
        // imported preset exceeds the shader's defensive capacity.
        if (warps.size() >= 8) warps.removeFirst();
        warps.add(warp);
    }

    private static ClientFxShaderEngine.ScreenWarp renderPulse(GuiGraphics graphics, TrackEffect effect,
                                                                float baseAmount, float trackTick,
                                                                int bpm, float playbackSpeed) {
        float pulse = baseAmount * effect.intensity() * effect.opacity()
                * beatEnvelope(effect, trackTick, bpm, playbackSpeed);
        if (pulse <= 0.001F) return null;
        // Pulse captures the current framebuffer directly. Do not let batched GUI geometry
        // arrive only after that capture (the preview screen already happened to flush here,
        // while the live HUD and spectator path did not).
        graphics.flush();
        return ClientFxShaderEngine.renderPulse(pulse)
                ? ClientFxShaderEngine.ScreenWarp.pulse(pulse) : null;
    }

    private static void renderOldTv(GuiGraphics graphics, TrackEffect effect, float strength, float trackTick, float pulseBeat) {
        var settings = effect.settings();
        float flicker = 1.0F + noise(trackTick * 0.91F + 17.0F) * settings.tvFlickerAmount() * 0.32F;
        float tv = Mth.clamp(strength * flicker, 0.0F, 5.0F);
        if (tv <= 0.01F) return;
        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        pushReactiveTransform(graphics, effect, pulseBeat);
        graphics.fill(0, 0, width, height, argb(Math.round((36.0F + 24.0F * settings.tvFlickerAmount()) * tv), 14, 16, 15));
        int spacing = Mth.clamp(Math.round(settings.tvScanlineSpacing()), 1, 16);
        int scanAlpha = Math.round(68.0F * tv * settings.tvScanlineOpacity());
        float legacyWhiteLineAmount = Mth.clamp(
                (settings.tvScanlineOpacity() * 68.0F - 30.0F) / 38.0F, 0.0F, 1.0F);
        for (int y = 0; y < height; y += spacing) {
            graphics.fill(0, y, width, y + 1, argb(scanAlpha, 0, 0, 0));
            if (spacing > 1 && (y / spacing & 3) == 0) {
                graphics.fill(0, y + 1, width, y + 2,
                        argb(Math.round(13.0F * tv * legacyWhiteLineAmount), 224, 232, 226));
            }
        }
        int frame = Mth.floor(trackTick * 2.0F);
        int noiseLines = Math.round(44.0F * settings.tvNoiseAmount());
        for (int i = 0; i < noiseLines; i++) {
            int hash = mix(frame * 7349 + i * 9151);
            int y = Math.floorMod(hash, Math.max(1, height));
            int x = Math.floorMod(hash >>> 8, Math.max(1, width));
            int length = 5 + Math.floorMod(hash >>> 16, Math.max(6, width / 7));
            int brightness = 115 + Math.floorMod(hash >>> 20, 120);
            graphics.fill(x, y, Math.min(width, x + length), y + 1,
                    argb(Math.round(62.0F * tv * settings.tvNoiseOpacity()), brightness, brightness, brightness));
        }
        if (settings.tvSweepEnabled() && settings.tvSweepOpacity() > 0.001F) {
            int sweepWidth = Mth.clamp(Math.round(settings.tvSweepWidth()), 1, 160);
            int travel = Math.max(1, height + sweepWidth * 2);
            int sweep = height + sweepWidth - Math.floorMod(Mth.floor(trackTick * settings.tvSweepSpeed()), travel);
            for (int band = 8; band >= 0; band--) {
                float ratio = band / 8.0F;
                int halfWidth = Math.max(1, Math.round(sweepWidth * ratio));
                float falloff = 1.0F - ratio;
                int alpha = Math.round(115.0F * tv * settings.tvSweepOpacity() * falloff * falloff);
                graphics.fill(0, sweep - halfWidth, width, sweep + halfWidth + 1, argb(alpha, 238, 250, 242));
            }
            graphics.fill(0, sweep, width, sweep + 1,
                    argb(Math.round(155.0F * tv * settings.tvSweepOpacity()), 255, 255, 255));
        }
        graphics.pose().popPose();
    }

    private static ClientFxShaderEngine.ScreenWarp renderFishEye(GuiGraphics graphics, TrackEffect effect,
                                                                  float strength, float visibility,
                                                                  float pulseBeat) {
        graphics.flush();
        if (ClientFxShaderEngine.renderFishEye(effect, strength, visibility)) {
            return ClientFxShaderEngine.ScreenWarp.fishEye(effect, strength, visibility);
        }
        float amount = Mth.clamp(strength * effect.settings().fishEyeStrength() * effect.opacity(), 0.0F, 1.0F);
        if (amount <= 0.001F) return null;
        pushReactiveTransform(graphics, effect, pulseBeat);
        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        int edge = Math.max(3, Math.round(Math.min(width, height) * 0.035F * amount));
        int alpha = Math.round(125.0F * amount * effect.settings().fishEyeEdgeDarkening());
        graphics.fill(0, 0, width, edge, argb(alpha, 0, 0, 0));
        graphics.fill(0, height - edge, width, height, argb(alpha, 0, 0, 0));
        graphics.fill(0, edge, edge, height - edge, argb(alpha, 0, 0, 0));
        graphics.fill(width - edge, edge, width, height - edge, argb(alpha, 0, 0, 0));
        graphics.pose().popPose();
        return null;
    }

    private static void renderCinematicBars(GuiGraphics graphics, TrackEffect effect, float visibility,
                                             float reactive, float trackTick, float playbackSpeed) {
        var settings = effect.settings();
        float movement = 1.0F;
        if (settings.cinematicAnimated()) {
            float elapsed = Math.max(0.0F, trackTick - effect.tick());
            float animationTicks = Mth.clamp(8.0F / settings.cinematicAnimationSpeed() / playbackSpeed,
                    1.0F / playbackSpeed, 30.0F / playbackSpeed);
            float enter = smooth(Mth.clamp(elapsed / animationTicks, 0.0F, 1.0F));
            float exit = 1.0F;
            if (effect.durationTicks() > 0) {
                float remaining = Math.max(0.0F, playbackDuration(effect, playbackSpeed) - elapsed);
                exit = smooth(Mth.clamp(remaining / animationTicks, 0.0F, 1.0F));
            }
            movement = Math.min(enter, exit);
        }
        float scale = Mth.clamp(effect.intensity() * reactive * movement, 0.0F, 5.0F);
        float offset = settings.cinematicCenterOffset();
        float topFraction = Mth.clamp((settings.cinematicTopHeight() + offset) * scale, 0.0F, 0.48F);
        float bottomFraction = Mth.clamp((settings.cinematicBottomHeight() - offset) * scale, 0.0F, 0.48F);
        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        int top = Math.round(height * topFraction);
        int bottom = Math.round(height * bottomFraction);
        int alpha = Math.round(255.0F * effect.opacity() * Mth.clamp(visibility, 0.0F, 1.0F));
        int color = withAlpha(effect.colorA(), alpha);
        if (top > 0) graphics.fill(0, 0, width, top, color);
        if (bottom > 0) graphics.fill(0, height - bottom, width, height, color);

        int feather = Math.round(height * settings.cinematicFeather() * Math.max(0.2F, scale));
        if (feather <= 0 || settings.cinematicShadow() <= 0.001F || alpha <= 0) return;
        int steps = Mth.clamp(feather, 2, 12);
        for (int step = 0; step < steps; step++) {
            int from = Math.round(feather * step / (float) steps);
            int to = Math.max(from + 1, Math.round(feather * (step + 1) / (float) steps));
            float fade = 1.0F - (step + 0.5F) / steps;
            int shadowAlpha = Math.round(alpha * settings.cinematicShadow() * fade * fade);
            int shadow = withAlpha(effect.colorA(), shadowAlpha);
            if (top > 0) graphics.fill(0, top + from, width, Math.min(height, top + to), shadow);
            if (bottom > 0) graphics.fill(0, Math.max(0, height - bottom - to), width, height - bottom - from, shadow);
        }
    }

    private static void renderVignette(GuiGraphics graphics, TrackEffect effect, float strength, float pulseBeat) {
        float vignette = Mth.clamp(strength, 0.0F, 5.0F);
        if (vignette <= 0.01F) return;
        graphics.flush();
        if (ClientFxShaderEngine.renderVignette(effect, vignette)) return;
        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        pushReactiveTransform(graphics, effect, pulseBeat);
        int layers = 12;
        int maxInset = Math.max(8, Math.round(Math.min(width, height) * (1.4F - effect.settings().vignetteRadius()) * 0.16F));
        for (int i = 0; i < layers; i++) {
            int inset = i * maxInset / layers;
            float falloff = 1.0F - i / (float) layers;
            int alpha = Math.round(70.0F * falloff * vignette * effect.opacity());
            int color = withAlpha(effect.colorA(), alpha);
            int thickness = Math.max(2, maxInset / layers + 1);
            graphics.fill(inset, inset, width - inset, inset + thickness, color);
            graphics.fill(inset, height - inset - thickness, width - inset, height - inset, color);
            graphics.fill(inset, inset, inset + thickness, height - inset, color);
            graphics.fill(width - inset - thickness, inset, width - inset, height - inset, color);
        }
        graphics.pose().popPose();
    }

    private static void renderFlash(GuiGraphics graphics, TrackEffect effect, float strength, float pulseBeat) {
        if (strength <= 0.01F) return;
        pushReactiveTransform(graphics, effect, pulseBeat);
        int alpha = Math.round(190.0F * Mth.clamp(strength, 0.0F, 1.4F));
        graphics.fill(0, 0, canvasWidth(graphics), canvasHeight(graphics), withAlpha(effect.colorA(), alpha));
        graphics.pose().popPose();
    }

    private static void renderColorCorrection(TrackEffect effect, float strength) {
        if (strength <= 0.001F) return;
        ClientFxShaderEngine.renderColorCorrection(effect, Mth.clamp(strength, 0.0F, 1.0F));
    }

    private static void renderVisualizer(GuiGraphics graphics, TrackEffect effect, float baseAmount, float trackTick,
                                         float pulseBeat) {
        if (effect == null) {
            return;
        }
        ClientAudioAnalyzer.Spectrum spectrum = ClientAudioAnalyzer.sample(trackTick);
        if (!spectrum.ready()) return;
        float strength = baseAmount * effect.intensity();
        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        float[] bands = spectrum.bands();
        boolean fullWidth = visualizerFullWidth(effect.variant());
        boolean gradientAlongBar = effect.variant() != null && effect.variant().contains("_v_");
        String position = effect.settings().visualizerPosition();
        boolean side = "left".equals(position) || "right".equals(position);
        int screenSpan = side ? height : width;
        float spanFraction = fullWidth ? 1.0F : Mth.clamp(effect.positionX(), 0.1F, 1.0F);
        int count = visualizerSections(effect.variant());
        int availableSpan = Math.max(8, Math.round(screenSpan * spanFraction) - (fullWidth ? 0 : 12));
        int gap = Math.max(1, availableSpan / 360);
        if (side && availableSpan < count * 2) gap = 0;
        int barThickness = Math.max(side ? 1 : 2, (availableSpan - gap * (count - 1)) / count);
        int usedSpan = count * (barThickness + gap) - gap;
        int startAlongEdge = (screenSpan - usedSpan) / 2;
        float lengthScale = Mth.clamp(effect.positionY() / 0.32F, 0.25F, 3.125F);
        int crossSize = side ? width : height;
        int baseMargin = Math.min(29, crossSize / 2);
        float offset = effect.settings().visualizerOffset();
        int offsetPixels = offset >= 0.0F
                ? Math.round(offset * Math.max(0, crossSize / 2 - baseMargin))
                : Math.round(offset * baseMargin);
        int anchor = switch (position) {
            case "top", "left" -> Mth.clamp(baseMargin + offsetPixels, 0, crossSize);
            default -> Mth.clamp(crossSize - baseMargin - offsetPixels, 0, crossSize);
        };
        pushReactiveTransform(graphics, effect, pulseBeat);
        for (int i = 0; i < count; i++) {
            int bandIndex = side ? count - 1 - i : i;
            int sourceBand = Math.min(bands.length - 1,
                    Math.round(bandIndex / (float) Math.max(1, count - 1) * (bands.length - 1)));
            int barLength = Math.max(0, Math.round((4.0F + 54.0F * bands[sourceBand]) * strength * lengthScale));
            int along = startAlongEdge + i * (barThickness + gap);
            for (int distance = 0; distance < barLength; distance += 4) {
                float gradient = gradientAlongBar
                        ? distance / (float)Math.max(1, barLength - 1)
                        : bandIndex / (float)Math.max(1, count - 1);
                int color = lerpColor(effect.colorA(), effect.colorB(), gradient);
                int argb = withAlpha(color, Math.round(255.0F * effect.opacity()));
                switch (position) {
                    case "top" -> graphics.fill(along, anchor + distance,
                            along + barThickness, anchor + distance + 2, argb);
                    case "left" -> graphics.fill(anchor + distance, along,
                            anchor + distance + 2, along + barThickness, argb);
                    case "right" -> graphics.fill(anchor - distance - 2, along,
                            anchor - distance, along + barThickness, argb);
                    default -> graphics.fill(along, anchor - distance - 2,
                            along + barThickness, anchor - distance, argb);
                }
            }
        }
        graphics.pose().popPose();
    }

    private static boolean visualizerFullWidth(String variant) {
        return variant != null && (variant.equals("full_width") || variant.startsWith("full_"));
    }

    private static int visualizerSections(String variant) {
        if (variant != null) {
            int split = variant.lastIndexOf('_');
            if (split >= 0 && split + 1 < variant.length()) {
                try {
                    return Mth.clamp(Integer.parseInt(variant.substring(split + 1)), 8, 64);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 24;
    }

    private static void renderPixelFire(GuiGraphics graphics, TrackEffect effect, float baseAmount, float trackTick,
                                        float pulseBeat, float playbackSpeed) {
        if (effect == null) {
            return;
        }
        float elapsed = Math.max(0.0F, trackTick - effect.tick());
        float growthTicks = Math.max(5.0F / playbackSpeed,
                Math.min(30.0F / playbackSpeed, playbackDuration(effect, playbackSpeed) * 0.35F));
        float strength = baseAmount * effect.intensity()
                * smooth(Mth.clamp(elapsed / growthTicks, 0.0F, 1.0F));
        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        int cell = Math.max(3, width / 180);
        int columns = width / cell + 1;
        int frame = Mth.floor(animationTick(effect, trackTick, playbackSpeed) * 2.0F);
        pushReactiveTransform(graphics, effect, pulseBeat);
        for (int column = 0; column < columns; column++) {
            int hash = mix(frame / 2 * 9283 + column * 1753);
            int flame = Math.round((8 + Math.floorMod(hash, 32)) * strength);
            for (int y = 0; y < flame; y += cell) {
                int flicker = mix(hash + y * 331);
                if ((flicker & 3) == 0 && y > flame / 2) {
                    continue;
                }
                float ratio = y / (float) Math.max(1, flame);
                int color = lerpColor(effect.colorA(), effect.colorB(), ratio);
                graphics.fill(column * cell, height - 1 - y, column * cell + cell, height - 1 - y + cell,
                        withAlpha(color, Math.round(225 * effect.opacity() * (1.0F - ratio * 0.55F))));
            }
        }
        graphics.pose().popPose();
    }

    private static void renderStars(GuiGraphics graphics, TrackEffect effect, float baseAmount, float trackTick,
                                    float pulseBeat, float playbackSpeed, int bpm) {
        var settings = effect.settings();
        float strength = Mth.clamp(baseAmount * effect.intensity() * settings.starBrightness(), 0.0F, 3.0F);
        if (strength <= 0.001F) return;

        int width = canvasWidth(graphics);
        int height = canvasHeight(graphics);
        float areaScale = Mth.clamp((float)Math.sqrt(width * (double)height / (320.0D * 180.0D)), 0.65F, 1.8F);
        int count = Mth.clamp(Math.round(56.0F * settings.starDensity() * areaScale), 3, 180);
        float time = animationTick(effect, trackTick, playbackSpeed);
        // Marked beat-reactive, the field keeps its steady drift and surges on every beat: a kick that is spent
        // within the first third of the beat and has settled by the next one, so the field reads as moving to the
        // music at any tempo rather than simply faster.
        float travel = effect.beatReactive() ? time + beatSurgeTicks(time, bpm, playbackSpeed) : time;
        float drift = travel * settings.starDriftSpeed() * 0.0025F;
        float spread = settings.starSpread();
        float driftXScale;
        float driftYScale;
        float swayXScale;
        float swayYScale;
        switch (settings.starDirection()) {
            case "right" -> {
                driftXScale = 1.0F; driftYScale = 0.0F; swayXScale = 0.0F; swayYScale = -0.7F;
            }
            case "left" -> {
                driftXScale = -1.0F; driftYScale = 0.0F; swayXScale = 0.0F; swayYScale = 0.7F;
            }
            case "down" -> {
                driftXScale = 0.0F; driftYScale = 1.0F; swayXScale = 1.0F; swayYScale = 0.0F;
            }
            case "up" -> {
                driftXScale = 0.0F; driftYScale = -1.0F; swayXScale = -1.0F; swayYScale = 0.0F;
            }
            case "down_left" -> {
                driftXScale = -1.0F; driftYScale = 0.55F; swayXScale = -1.0F; swayYScale = -0.7F;
            }
            case "up_right" -> {
                driftXScale = 1.0F; driftYScale = -0.55F; swayXScale = 1.0F; swayYScale = 0.7F;
            }
            case "up_left" -> {
                driftXScale = -1.0F; driftYScale = -0.55F; swayXScale = -1.0F; swayYScale = 0.7F;
            }
            default -> {
                // Legacy movement: old maps without starDirection keep exactly the same trajectory.
                driftXScale = 1.0F; driftYScale = 0.55F; swayXScale = 1.0F; swayYScale = -0.7F;
            }
        }
        int seed = mix(effect.tick() * 92821 + effect.visibleDurationTicks() * 31337 + globalRow(effect) * 68917);

        pushReactiveTransform(graphics, effect, pulseBeat);
        for (int index = 0; index < count; index++) {
            int positionHash = mix(seed + index * 0x45D9F3B);
            int detailHash = mix(positionHash ^ 0x9E3779B9);
            float baseX = unitFloat(positionHash);
            float baseY = unitFloat(detailHash);
            float swayPhase = unitFloat(mix(detailHash + 0x632BE5AB)) * Mth.TWO_PI;
            float sway = Mth.sin(time * 0.035F * settings.starDriftSpeed() + swayPhase)
                    * 0.012F * Math.abs(settings.starDriftSpeed());
            float localX = fractional(baseX + drift * driftXScale + sway * swayXScale);
            float localY = fractional(baseY + drift * driftYScale + sway * swayYScale);
            float normalizedX = effect.positionX() + (localX - 0.5F) * spread;
            float normalizedY = effect.positionY() + (localY - 0.5F) * spread;
            int x = Math.round(normalizedX * width);
            int y = Math.round(normalizedY * height);
            if (x < -16 || y < -16 || x > width + 16 || y > height + 16) continue;

            float phase = unitFloat(mix(positionHash + 0x165667B1)) * Mth.TWO_PI;
            float twinkle = 0.5F + 0.5F * Mth.sin(time * settings.starTwinkleSpeed() * 0.14F + phase);
            twinkle = smooth(twinkle);
            float sizeNoise = 0.58F + unitFloat(mix(detailHash - 0x27D4EB2D)) * 0.92F;
            int alpha = Mth.clamp(Math.round(255.0F * effect.opacity() * strength * (0.18F + 0.82F * twinkle)), 0, 255);
            if (alpha <= 1) continue;

            float shimmer = settings.starIridescence()
                    * (0.5F + 0.5F * Mth.sin(time * settings.starTwinkleSpeed() * 0.075F + phase * 1.73F));
            int color = lerpColor(effect.colorA(), effect.colorB(), shimmer);
            int brightColor = lerpColor(color, 0xFFFFFFFF, 0.28F + twinkle * 0.42F);
            if (settings.starSize() < 0.25F) {
                float tinyScale = Mth.clamp(settings.starSize() * 4.0F * sizeNoise, 0.08F, 1.0F);
                graphics.fill(x, y, x + 1, y + 1, withAlpha(brightColor, Math.round(alpha * tinyScale)));
                continue;
            }
            int radius = Mth.clamp(Math.round(settings.starSize() * sizeNoise), 1, 9);
            int ray = radius * 2 + (twinkle > 0.78F ? radius + 1 : 0);
            int glowRadius = Math.max(2, radius * 2);

            int glowAlpha = Math.round(alpha * (0.10F + 0.10F * settings.starIridescence()));
            graphics.fill(x - glowRadius, y - 1, x + glowRadius + 1, y + 2, withAlpha(color, glowAlpha));
            graphics.fill(x - 1, y - glowRadius, x + 2, y + glowRadius + 1, withAlpha(color, glowAlpha));
            graphics.fill(x - ray, y, x + ray + 1, y + 1, withAlpha(color, Math.round(alpha * 0.42F)));
            graphics.fill(x, y - ray, x + 1, y + ray + 1, withAlpha(color, Math.round(alpha * 0.42F)));
            for (int row = -radius; row <= radius; row++) {
                int halfWidth = radius - Math.abs(row);
                graphics.fill(x - halfWidth, y + row, x + halfWidth + 1, y + row + 1,
                        withAlpha(color, alpha));
            }
            int core = Math.max(1, radius / 2);
            graphics.fill(x - core, y - core, x + core + 1, y + core + 1,
                    withAlpha(brightColor, alpha));
        }
        graphics.pose().popPose();
    }

    private static void renderText(GuiGraphics graphics, TrackEffect effect, float baseAmount, float trackTick,
                                   float pulseBeat, float playbackSpeed) {
        if (effect == null || effect.text().isBlank()) {
            return;
        }
        float elapsed = Math.max(0.0F, trackTick - effect.tick());
        float duration = playbackDuration(effect, playbackSpeed);
        float animationTicks = Math.max(2.0F / playbackSpeed,
                Math.min(24.0F / playbackSpeed, duration * 0.28F) / effect.textSpeed());
        float reveal = smooth(Mth.clamp(elapsed / animationTicks, 0.0F, 1.0F));
        float exit = smooth(Mth.clamp((elapsed - Math.max(0.0F, duration - animationTicks)) / animationTicks, 0.0F, 1.0F));
        String shown = effect.text();
        if ("letters".equals(effect.animation())) {
            shown = shown.substring(0, Mth.clamp(Math.round(shown.length() * reveal), 0, shown.length()));
        } else if ("words".equals(effect.animation())) {
            String[] words = shown.split(" ");
            int count = Mth.clamp(Math.round(words.length * reveal), 0, words.length);
            shown = String.join(" ", java.util.Arrays.copyOf(words, count));
        }
        if (shown.isBlank()) {
            return;
        }
        int x = Math.round(canvasWidth(graphics) * effect.positionX());
        int y = Math.round(canvasHeight(graphics) * effect.positionY());
        float alphaFactor = "fade".equals(effect.animation()) ? reveal : 1.0F;
        if ("fade".equals(effect.exitAnimation())) alphaFactor *= 1.0F - exit;
        int alpha = Math.round(255.0F * effect.opacity() * alphaFactor * baseAmount);
        int color = withAlpha(effect.colorA(), alpha);
        int outlineColor = withAlpha(effect.colorB(), alpha);
        float scale = effect.intensity() * textAnimationScale(effect.animation(), reveal, false)
                * textAnimationScale(effect.exitAnimation(), exit, true);
        float offsetX = textAnimationOffsetX(effect.animation(), reveal, false, canvasWidth(graphics))
                + textAnimationOffsetX(effect.exitAnimation(), exit, true, canvasWidth(graphics));
        float offsetY = textAnimationOffsetY(effect.animation(), reveal, false, canvasHeight(graphics))
                + textAnimationOffsetY(effect.exitAnimation(), exit, true, canvasHeight(graphics));
        float rotation = textAnimationRotation(effect.animation(), reveal, false)
                + textAnimationRotation(effect.exitAnimation(), exit, true);
        pushReactiveTransform(graphics, effect, pulseBeat);
        graphics.pose().pushPose();
        graphics.pose().translate(x + offsetX, y + offsetY, 0.0F);
        if (Math.abs(rotation) > 0.01F) graphics.pose().mulPose(Axis.ZP.rotationDegrees(rotation));
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.flush();
        boolean writingTextMask = ClientModelGlowEngine.beginTextFxMaskWrite();
        try {
            BattleTextFonts.drawCentered(graphics, net.minecraft.client.Minecraft.getInstance().font, shown, effect.font(),
                    0.0F, 0.0F, color, outlineColor, effect.outlineThickness());
        } finally {
            // Submit the FX glyphs while their intended stencil/depth state is still active.
            // This is required with ImmediatelyFast font/HUD batching.
            graphics.flush();
            if (writingTextMask) {
                ClientModelGlowEngine.endTextFxMaskWrite();
            }
        }
        graphics.pose().popPose();
        graphics.pose().popPose();
    }

    private static float textAnimationScale(String animation, float progress, boolean exiting) {
        String value = animation == null ? "none" : animation;
        if (exiting) {
            return switch (value) {
                case "shrink" -> Mth.lerp(progress, 1.0F, 0.05F);
                case "zoom" -> Mth.lerp(progress, 1.0F, 1.8F);
                default -> 1.0F;
            };
        }
        return switch (value) {
            case "zoom" -> Mth.lerp(progress, 0.15F, 1.0F);
            case "pop" -> 1.0F + Mth.sin(progress * (float)Math.PI) * 0.32F * (1.0F - progress * 0.35F);
            default -> 1.0F;
        };
    }

    private static float textAnimationOffsetX(String animation, float progress, boolean exiting, int width) {
        float travel = Math.max(24.0F, width * 0.28F);
        float factor = exiting ? progress : 1.0F - progress;
        return switch (animation == null ? "none" : animation) {
            case "slide_left" -> -travel * factor;
            case "slide_right" -> travel * factor;
            default -> 0.0F;
        };
    }

    private static float textAnimationOffsetY(String animation, float progress, boolean exiting, int height) {
        float travel = Math.max(18.0F, height * 0.22F);
        float factor = exiting ? progress : 1.0F - progress;
        return switch (animation == null ? "none" : animation) {
            case "slide_up" -> -travel * factor;
            case "slide_down" -> travel * factor;
            default -> 0.0F;
        };
    }

    private static float textAnimationRotation(String animation, float progress, boolean exiting) {
        if (!"spin".equals(animation)) return 0.0F;
        return exiting ? progress * 180.0F : (1.0F - progress) * -180.0F;
    }

    private static void pushReactiveTransform(GuiGraphics graphics, TrackEffect effect, float pulseBeat) {
        graphics.pose().pushPose();
        if (effect == null || !effect.beatReactive() || pulseBeat <= 0.001F) return;
        float pulse = Mth.clamp(pulseBeat, 0.0F, 5.0F);
        float scale = 1.0F + pulse * 0.055F;
        float centerX = canvasWidth(graphics) / 2.0F;
        float centerY = canvasHeight(graphics) / 2.0F;
        graphics.pose().translate(centerX, centerY - pulse * 1.35F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate(-centerX, -centerY, 0.0F);
    }

    private static float noise(float value) {
        return Mth.sin(value * 12.9898F + Mth.sin(value * 0.73F) * 19.19F);
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private static float unitFloat(int value) {
        return ((value >>> 8) & 0x00FFFFFF) / 16777215.0F;
    }

    private static float fractional(float value) {
        return value - Mth.floor(value);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int lerpColor(int first, int second, float amount) {
        float t = Mth.clamp(amount, 0.0F, 1.0F);
        int red = Math.round(Mth.lerp(t, (first >> 16) & 255, (second >> 16) & 255));
        int green = Math.round(Mth.lerp(t, (first >> 8) & 255, (second >> 8) & 255));
        int blue = Math.round(Mth.lerp(t, first & 255, second & 255));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    public record EffectState(float flash, float oldTv, float shake, float pulse, float pulseBeat,
                              float vignette, float trackTick, TrackEffect pulseEffect,
                              TrackEffect visualizerEffect, TrackEffect textEffect, TrackEffect fireEffect,
                              TrackEffect highlightEffect, TrackEffect objectOutlineEffect,
                              boolean hideBattlePanels,
                              TrackEffect flashEffect, TrackEffect oldTvEffect,
                              TrackEffect vignetteEffect,
                              List<TrackEffect> overlayEffects, int bpm, float playbackSpeed,
                              float visibilityMultiplier, List<EffectState> preceding) {
        public boolean hidesParticles() {
            return overlayEffects.stream().anyMatch(e -> e.type() == TrackEffect.Type.HIDE_PARTICLES_HAND
                    && e.settings().hideParticles()) || preceding.stream().anyMatch(EffectState::hidesParticles);
        }
        public boolean hidesFirstPersonHand() {
            return overlayEffects.stream().anyMatch(e -> e.type() == TrackEffect.Type.HIDE_PARTICLES_HAND
                    && e.settings().hideFirstPersonHand()) || preceding.stream().anyMatch(EffectState::hidesFirstPersonHand);
        }
        public boolean hidesHud() {
            return overlayEffects.stream().anyMatch(e -> e.type() == TrackEffect.Type.HIDE_PARTICLES_HAND
                    && e.settings().hideHud()) || preceding.stream().anyMatch(EffectState::hidesHud);
        }
        public boolean hidesBlockyMusicHud() {
            return overlayEffects.stream().anyMatch(e -> e.type() == TrackEffect.Type.HIDE_PARTICLES_HAND
                    && e.settings().hideBlockyMusicHud()) || preceding.stream().anyMatch(EffectState::hidesBlockyMusicHud);
        }
        public EffectState withPreceding(List<EffectState> states) {
            return new EffectState(flash,oldTv,shake,pulse,pulseBeat,vignette,trackTick,pulseEffect,
                    visualizerEffect,textEffect,fireEffect,highlightEffect,objectOutlineEffect,hideBattlePanels,
                    flashEffect,oldTvEffect,vignetteEffect,overlayEffects,bpm,playbackSpeed,visibilityMultiplier,List.copyOf(states));
        }
        public List<EffectState> states() {
            if(preceding.isEmpty())return List.of(this);
            List<EffectState> result=new ArrayList<>(preceding);result.add(this);return result;
        }
        public EffectState outlineState() {
            if(objectOutlineEffect!=null)return this;
            for(int i=preceding.size()-1;i>=0;i--)if(preceding.get(i).objectOutlineEffect()!=null)return preceding.get(i);
            return this;
        }
        public float cameraPulseBeat(){return pulseBeat+(float)preceding.stream().mapToDouble(EffectState::cameraPulseBeat).sum();}
    }
}
