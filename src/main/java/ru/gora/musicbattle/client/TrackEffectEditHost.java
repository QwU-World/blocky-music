package ru.gora.musicbattle.client;

import java.nio.file.Path;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.data.TrackEffect;

/** Callback boundary that lets the shared FX inspector edit charts or DJ presets. */
public interface TrackEffectEditHost {
    Screen parentScreen();

    Path effectPreviewAudioPath();

    default boolean managesPreviewAudio() {
        return true;
    }

    void saveEffect(TrackEffect original, TrackEffect edited, String placementMode);

    /**
     * Whether this host wants valid, unsaved inspector drafts while the user is editing.
     * Chart editing keeps the historical save-only behavior; the DJ mixer opts in so its
     * existing apply callback can drive a live preview.
     */
    default boolean supportsLivePreview() {
        return false;
    }

    /** Publishes one coalesced, valid inspector draft without committing the edit. */
    default void previewEffect(TrackEffect original, TrackEffect edited, String placementMode) {
    }

    /** Restores the effect snapshot that was present when the inspector opened. */
    default void cancelEffectPreview(TrackEffect original, TrackEffect initial, String placementMode) {
    }

    default void deleteEffect(TrackEffect original) {
    }

    default void refreshEffectBrush(TrackEffect.Type type) {
    }

    default boolean showBrushActions() {
        return true;
    }

    default boolean showTimelineControls() {
        return true;
    }

    default boolean allowTypeChange() {
        return true;
    }

    default boolean persistTemplateOnClose() {
        return true;
    }

    default int previewBpm() {
        return 120;
    }

    default float previewPlaybackSpeed() {
        return 1.0F;
    }

    /**
     * Where beat 1 falls in the song whose effect this is, in ms ({@code ChartBeat}), so the preview's beat visuals land
     * on the chart's beats. 0 for a host with no chart behind it (a DJ set loops on its own clock).
     */
    default int previewBeatOffsetMs() {
        return 0;
    }

    default Component inspectorTitle(TrackEffect original) {
        return Component.translatable(original == null ? "music_battle.l10n.fx.brush_title" : "music_battle.l10n.fx.settings_title");
    }
}
