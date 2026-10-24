package ru.gora.musicbattle.data;

import java.util.List;

/** First-run panel preferences, also used before the server receives client settings. */
public final class PlayerPanelDefaults {
    public static final float NOTE_SPEED = 5.0F;
    /**
     * The first-run speed shipped before config version 2, a slider position copied from a test profile. A free drag
     * of the continuous slider practically never lands on it exactly, so an unversioned file holding it is read as
     * untouched and migrated to {@link #NOTE_SPEED}.
     */
    public static final float LEGACY_NOTE_SPEED = 1.0035211F;
    /**
     * The panel header and backdrop colour: the settings screen's own background ({@code Palette.BG_0}), so the
     * area behind the notes is the same calm dark as the rest of the mod and the palette lane colours stand out on
     * it. This class is shared with the server, so it holds the value rather than reaching into the client toolkit;
     * the note-color-migration check pins it to the palette.
     */
    public static final int PANEL_COLOR = 0xFF110F14;
    /**
     * Lanes alternate the palette's lavender accent ({@code Palette.ACCENT}) and gold ({@code Palette.ST_FEATURED}),
     * the same left/up and down/right pairing the old cyan pair used. The two differ on the blue-yellow axis and by a
     * wide step in lightness, which survives every common colour-vision deficiency, and both stay bright against the
     * dark settings backdrop and the dark hit-target fill.
     */
    public static final NoteAppearance APPEARANCE = new NoteAppearance(NoteAppearance.ARROWS,
            List.of(0xFFBE8FFF, 0xFFFFD152, 0xFFBE8FFF, 0xFFFFD152));
    /**
     * The cyan panel colour and lane pair shipped before config version 3. A colour picker practically never lands on
     * these exact values by hand, so a file still holding one of them is read as untouched and moves to the defaults.
     */
    public static final int LEGACY_PANEL_COLOR = 0xFF77D7FF;
    public static final List<Integer> LEGACY_LANE_COLORS = List.of(0xFF77D7FF, 0xFFC9F3F5, 0xFF77D7FF, 0xFFC9F3F5);
    /**
     * The violet panel default of config version 3 ({@code Palette.ACCENT_STRONG}). It shipped for about an hour
     * before the owner asked for the dark backdrop, so nobody chose it on purpose and a file holding it moves too.
     */
    public static final int VIOLET_PANEL_COLOR = 0xFF8349D4;
    public static final float PANEL_OPACITY = 0.0F;
    public static final float LANE_OPACITY = 0.35555556F;
    public static final float PANEL_WIDTH_SCALE = 0.64308643F;
    public static final float PANEL_HEIGHT_SCALE = 1.5999999F;
    public static final float NOTE_WIDTH_SCALE = 2.2F;
    public static final float NOTE_HEIGHT_SCALE = 0.7793827F;

    private PlayerPanelDefaults() { }
}
