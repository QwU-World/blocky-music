package ru.gora.musicbattle.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * Client-only draft preview. It never mutates the active spectator/DJ session and can therefore
 * be fed by unsaved UI controls. Its clock follows ClientBattleController semantics: real track
 * ticks are sampled once, while playbackSpeed is passed to ClientTrackEffects for speed-aware
 * animation, point duration and fade timing.
 */
public final class ClientDjVisualFxPreview {
    public static final int LOOP_TICKS = 160;
    private List<DjVisualEffectConfig> draft = List.of();
    private List<TrackEffect> materialized = List.of();
    private int bpm = 120;
    private float playbackSpeed = 1.0F;
    private boolean enabled;
    private long startedNanos;

    public void setDraft(List<DjVisualEffectConfig> configs, int bpm, float playbackSpeed) {
        List<DjVisualEffectConfig> safe = configs == null ? List.of() : configs.stream()
                .filter(config -> config != null)
                .limit(DjVisualEffectConfig.MAX_CONFIGS)
                .toList();
        int safeBpm = Math.max(20, bpm);
        float safeSpeed = Mth.clamp(playbackSpeed, 1.0F, 2.0F);
        boolean changed = !draft.equals(safe) || this.bpm != safeBpm
                || Float.compare(this.playbackSpeed, safeSpeed) != 0;
        draft = List.copyOf(safe);
        this.bpm = safeBpm;
        this.playbackSpeed = safeSpeed;
        materialized = DjVisualEffects.materializeDj(draft, LOOP_TICKS);
        if (changed && enabled) startedNanos = System.nanoTime();
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        startedNanos = value ? System.nanoTime() : 0L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEmpty() {
        return materialized.isEmpty();
    }

    public float currentTick() {
        if (!enabled || startedNanos <= 0L) return 0.0F;
        float elapsedTicks = Math.max(0L, System.nanoTime() - startedNanos) / 50_000_000.0F;
        return elapsedTicks % LOOP_TICKS;
    }

    public ClientTrackEffects.EffectState sample() {
        int effectiveBpm = Math.max(20, Math.round(bpm * playbackSpeed));
        return ClientTrackEffects.sample(enabled ? materialized : List.of(), currentTick(), effectiveBpm, playbackSpeed);
    }

    /** Render before DJ widgets; only FX with overlayInterface=false are submitted. */
    public void renderBelowInterface(GuiGraphics graphics) {
        if (!enabled || materialized.isEmpty()) return;
        ClientTrackEffects.renderBelowInterface(graphics, sample());
    }

    public void renderBelowInterface(GuiGraphics graphics, ClientTrackEffects.EffectState state) {
        if (!enabled || materialized.isEmpty()) return;
        ClientTrackEffects.renderBelowInterface(graphics, state);
    }

    /** Render after DJ widgets; only FX with overlayInterface=true are submitted. */
    public void renderAboveInterface(GuiGraphics graphics) {
        if (!enabled || materialized.isEmpty()) return;
        ClientTrackEffects.renderAboveInterface(graphics, sample());
    }

    public void renderAboveInterface(GuiGraphics graphics, ClientTrackEffects.EffectState state) {
        if (!enabled || materialized.isEmpty()) return;
        ClientTrackEffects.renderAboveInterface(graphics, state);
    }

    public void reset() {
        draft = List.of();
        materialized = List.of();
        bpm = 120;
        playbackSpeed = 1.0F;
        enabled = false;
        startedNanos = 0L;
    }
}
