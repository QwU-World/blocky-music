package ru.gora.musicbattle.client;

import java.util.function.Supplier;

/** One main-scene sample per frame; nested cameras never overwrite that sample. */
final class SceneVisibilityCache {
    private Flags main;
    private ClientTrackEffects.EffectState scopedSource;
    private Flags scoped = Flags.VISIBLE;

    Flags read(boolean isolatedCamera, ClientTrackEffects.EffectState cameraState,
               Supplier<ClientTrackEffects.EffectState> sampleMain) {
        if (isolatedCamera) {
            if (scopedSource != cameraState) {
                scopedSource = cameraState;
                scoped = Flags.from(cameraState);
            }
            return scoped;
        }
        if (main == null) main = Flags.from(sampleMain.get());
        return main;
    }

    void invalidate() {
        main = null;
        scopedSource = null;
        scoped = Flags.VISIBLE;
    }

    record Flags(boolean particles, boolean hand, boolean hud, boolean blockyMusicHud) {
        private static final Flags VISIBLE = new Flags(false, false, false, false);

        private static Flags from(ClientTrackEffects.EffectState state) {
            return state == null ? VISIBLE
                    : new Flags(state.hidesParticles(), state.hidesFirstPersonHand(), state.hidesHud(), state.hidesBlockyMusicHud());
        }
    }
}
