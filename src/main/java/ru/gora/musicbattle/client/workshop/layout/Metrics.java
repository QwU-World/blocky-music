package ru.gora.musicbattle.client.workshop.layout;

import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Sizes of the Workshop screens in GUI units. osu! lazer's numbers times {@link Palette#PX} (1 osu! px = 0.7 GUI units
 * at GUI scale 2, research {@code mc-gui-feasibility.md} section 1), rounded to something that still reads at GUI
 * scale 4 and fits a 320×240 window. Pure constants: the screens and the layout check share them.
 */
public final class Metrics {
    /** Below this width or height the shell uses its short bars and one column (GUI scale 4 on 1280×720). */
    public static final float COMPACT_WIDTH = 430F;
    public static final float COMPACT_HEIGHT = 300F;
    /** The filter panel starts collapsed under this height (review of 03 section 4.1). */
    public static final float COLLAPSE_FILTERS_HEIGHT = 360F;
    /** The set page and the info section keep two columns from this content width. */
    public static final float TWO_COLUMN_WIDTH = 520F;

    public static final float PAD = 8F;
    public static final float PAD_SM = 4F;
    public static final float GAP = 6F;
    public static final float GAP_SM = 3F;

    public static final float TITLE_BAR = 30F;
    public static final float TITLE_BAR_COMPACT = 22F;
    public static final float TAB_BAR = 20F;
    public static final float TAB_BAR_COMPACT = 16F;
    public static final float BUTTON_H = 20F;
    public static final float BUTTON_H_SM = 16F;
    public static final float FIELD_H = 20F;
    public static final float ICON = 9F;
    public static final float ICON_SM = 7F;
    public static final float SCROLLBAR = 3F;

    /** Card grid: lazer 345×80 / 345×112 at 0.7 units per osu! px, with a smaller minimum so 320-wide windows work. */
    public static final float CARD_NORMAL_H = 56F;
    public static final float CARD_EXTRA_H = 78F;
    public static final float CARD_MIN_W = 214F;
    public static final float CARD_GAP = 7F;
    public static final int CARD_MAX_COLUMNS = 3;
    /** The strip on the card's right edge: 8 u collapsed, 24 u on hover (lazer). */
    public static final float CARD_STRIP = 6F;
    public static final float CARD_STRIP_HOVER = 17F;
    /** Room under the last row so an expanded card's chart dropdown is not clipped (lazer keeps 220 u). */
    public static final float CARD_DROPDOWN_MAX = 140F;
    public static final float CARD_DROPDOWN_ROW = 13F;

    public static final float PILL_H = TextStyle.PILL.capHeight() + 5F;
    public static final float BADGE_H = TextStyle.PILL.capHeight() + 4F;
    public static final float DOT_STEP = 4.5F;

    /** Filter rows: label column and the height of one option line. */
    public static final float FILTER_LABEL_W = 62F;
    public static final float FILTER_LABEL_W_COMPACT = 46F;
    public static final float FILTER_ROW_GAP = 3F;
    public static final float OPTION_H = TextStyle.LABEL.capHeight() + 5F;
    public static final float OPTION_GAP = 5F;

    public static final float AVATAR = 22F;
    public static final float AVATAR_SM = 16F;
    public static final float COMMENT_GUTTER = 22F;
    public static final float COMMENT_INDENT = 14F;

    private Metrics() {
    }

    public static boolean compact(float width, float height) {
        return width < COMPACT_WIDTH || height < COMPACT_HEIGHT;
    }

    public static float titleBar(float width, float height) {
        return compact(width, height) ? TITLE_BAR_COMPACT : TITLE_BAR;
    }

    public static float tabBar(float width, float height) {
        return compact(width, height) ? TAB_BAR_COMPACT : TAB_BAR;
    }

    public static float buttonHeight(float width, float height) {
        return compact(width, height) ? BUTTON_H_SM : BUTTON_H;
    }

    public static float pad(float width, float height) {
        return compact(width, height) ? PAD_SM : PAD;
    }

    /** Line advance for wrapped text of a style. */
    public static float lineHeight(TextStyle style) {
        return style.capHeight() + 0.42F * style.size();
    }
}
