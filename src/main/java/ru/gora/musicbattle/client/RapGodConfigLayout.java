package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * Where the test NPC's settings screen puts its parts. Pure geometry: no {@code GuiGraphics} and no Minecraft, so the
 * npc-config-layout check measures these very rectangles at any window size, in both languages, without a window.
 *
 * <p>The screen is a Workshop dialog: a rounded card on the toolkit's backdrop, a title bar, and a footer with the one
 * primary action on the right. Inside, the NPC itself stands on the left, drawn live in the skin and arms being chosen,
 * and the settings that change how it looks sit beside it in the order they are read: its name, its skin, its arms.
 * What it does in the world comes last, as a grid of checkboxes across the whole card. A window too narrow for the
 * figure drops it rather than squeezing the controls.
 */
public final class RapGodConfigLayout {
    public static final int SKIN_SOURCES = 3;
    public static final int ARM_TYPES = 2;
    /** Stands still, pushable, invulnerable, glowing, random votes: two columns, read row by row. */
    public static final int CHECKS = 5;
    private static final int CHECK_COLUMNS = 2;

    /** Widest the card gets, whatever the window: a settings dialog, not a page. */
    private static final float MAX_PANEL_WIDTH = 480F;
    /** The figure needs this much room beside the controls, or it is left out. */
    private static final float PREVIEW_MIN_INNER_WIDTH = 330F;
    private static final float PREVIEW_FRACTION = 0.27F;
    private static final float PREVIEW_MIN = 90F;
    private static final float PREVIEW_MAX = 130F;
    private static final float SEGMENT_GAP = 3F;
    private static final float BUTTON_MIN_WIDTH = 64F;
    private static final float BUTTON_TEXT_PAD = 20F;

    /** Every vertical measure of the card, at the regular size and at the compact size a short window gets. */
    private record Spacing(float margin, float pad, float header, float labelGap, float field, float segment, float rowGap,
                           float sectionGap, float blockGap, float check, float checkGap, float button, float footerPad) {
        static final Spacing REGULAR = new Spacing(12F, 10F, 26F, 3F, 18F, 16F, 4F, 8F, 10F, 14F, 3F, 18F, 7F);
        static final Spacing COMPACT = new Spacing(4F, 6F, 20F, 2F, 16F, 14F, 3F, 5F, 6F, 12F, 2F, Metrics.BUTTON_H_SM, 5F);

        float label() {
            return TextFit.boxHeight(TextStyle.LABEL);
        }

        /** Name, skin and arms: the block the figure stands beside. */
        float topBlock() {
            return label() + labelGap + field + sectionGap
                    + label() + labelGap + segment + rowGap + field + sectionGap
                    + label() + labelGap + segment;
        }

        float behaviourBlock() {
            int rows = (CHECKS + CHECK_COLUMNS - 1) / CHECK_COLUMNS;
            return label() + labelGap + rows * check + (rows - 1) * checkGap;
        }

        float footerHeight() {
            return 1F + footerPad * 2F + button;
        }

        float panelHeight() {
            return header + pad + topBlock() + blockGap + behaviourBlock() + pad + footerHeight();
        }
    }

    private RapGodConfigLayout() {
    }

    /**
     * Every rectangle of the screen. {@code preview} is {@link Rect#EMPTY} when the window is too narrow for the figure;
     * {@code skinRow} is shared by the nickname field, the file picker and the note of the default outfit, one at a time.
     */
    public record Frame(float width, float height, boolean compact, float pad, Rect panel, Rect header, Rect icon,
                        Rect title, Rect headerNote, Rect preview, Rect nameLabel, Rect nameField, Rect skinLabel,
                        List<Rect> skinSources, Rect skinRow, Rect armsLabel, List<Rect> armTypes,
                        Rect behaviourLabel, List<Rect> checks, Rect footer, Rect status, Rect cancel, Rect save) {
        /** Text of the segments, checkboxes and notes: body size, or caption size on a compact screen. */
        public TextStyle controlStyle() {
            return compact ? TextStyle.CAPTION : TextStyle.BODY;
        }

    }

    /** The checkbox square of a check row, centred on the row. */
    public static Rect checkBox(Rect row, boolean compact) {
        float size = compact ? 8F : 9F;
        return new Rect(row.x() + 2F, row.y() + (row.h() - size) * 0.5F, size, size);
    }

    /** Where a check row's label starts and how wide it may be; the label is centred on the row's height. */
    public static Rect checkText(Rect row, boolean compact) {
        Rect box = checkBox(row, compact);
        float x = box.right() + 5F;
        return new Rect(x, row.y(), Math.max(0F, row.right() - x - 2F), row.h());
    }

    /**
     * @param cancelLabel and {@code saveLabel} are measured so neither footer button ever cuts its own word
     */
    public static Frame frame(float width, float height, TextMeasurer measurer, String cancelLabel, String saveLabel) {
        boolean compact = Metrics.compact(width, height)
                || height < Spacing.REGULAR.panelHeight() + Spacing.REGULAR.margin() * 2F;
        Spacing s = compact ? Spacing.COMPACT : Spacing.REGULAR;
        float pad = s.pad();
        float label = s.label();

        float panelWidth = Math.max(160F, Math.min(MAX_PANEL_WIDTH, width - s.margin() * 2F));
        float panelHeight = s.panelHeight();
        Rect panel = new Rect((width - panelWidth) * 0.5F, Math.max(0F, (height - panelHeight) * 0.5F), panelWidth, panelHeight);

        Rect header = new Rect(panel.x(), panel.y(), panel.w(), s.header());
        Rect icon = new Rect(panel.x() + pad, header.centerY() - Metrics.ICON * 0.5F, Metrics.ICON, Metrics.ICON);
        float titleX = icon.right() + Metrics.GAP_SM + 2F;
        float titleRoom = panel.right() - pad - titleX;
        Rect title = new Rect(titleX, capY(TextStyle.TITLE, header), titleRoom * 0.55F, TextFit.boxHeight(TextStyle.TITLE));
        float noteX = title.right() + Metrics.GAP;
        Rect headerNote = new Rect(noteX, capY(TextStyle.CAPTION, header), Math.max(0F, panel.right() - pad - noteX),
                TextFit.boxHeight(TextStyle.CAPTION));

        float innerX = panel.x() + pad;
        float innerWidth = panel.w() - pad * 2F;
        float top = header.bottom() + pad;
        Rect preview = Rect.EMPTY;
        float columnX = innerX;
        float columnWidth = innerWidth;
        if (innerWidth >= PREVIEW_MIN_INNER_WIDTH) {
            float previewWidth = Math.round(Math.max(PREVIEW_MIN, Math.min(PREVIEW_MAX, innerWidth * PREVIEW_FRACTION)));
            preview = new Rect(innerX, top, previewWidth, s.topBlock());
            columnX = preview.right() + pad;
            columnWidth = innerWidth - previewWidth - pad;
        }

        float y = top;
        Rect nameLabel = new Rect(columnX, y, columnWidth, label);
        y += label + s.labelGap();
        Rect nameField = new Rect(columnX, y, columnWidth, s.field());
        y += s.field() + s.sectionGap();

        Rect skinLabel = new Rect(columnX, y, columnWidth, label);
        y += label + s.labelGap();
        float segmentWidth = (columnWidth - SEGMENT_GAP * (SKIN_SOURCES - 1)) / SKIN_SOURCES;
        List<Rect> skinSources = new ArrayList<>(SKIN_SOURCES);
        for (int i = 0; i < SKIN_SOURCES; i++) {
            skinSources.add(new Rect(columnX + i * (segmentWidth + SEGMENT_GAP), y, segmentWidth, s.segment()));
        }
        y += s.segment() + s.rowGap();
        Rect skinRow = new Rect(columnX, y, columnWidth, s.field());
        y += s.field() + s.sectionGap();

        Rect armsLabel = new Rect(columnX, y, columnWidth, label);
        y += label + s.labelGap();
        // The arm types line up under the first two skin sources, so the two rows of choices read as one grid.
        List<Rect> armTypes = new ArrayList<>(ARM_TYPES);
        for (int i = 0; i < ARM_TYPES; i++) {
            armTypes.add(new Rect(columnX + i * (segmentWidth + SEGMENT_GAP), y, segmentWidth, s.segment()));
        }

        float behaviourTop = top + s.topBlock() + s.blockGap();
        Rect behaviourLabel = new Rect(innerX, behaviourTop, innerWidth, label);
        float checksTop = behaviourTop + label + s.labelGap();
        float checkWidth = (innerWidth - pad * (CHECK_COLUMNS - 1)) / CHECK_COLUMNS;
        List<Rect> checks = new ArrayList<>(CHECKS);
        for (int i = 0; i < CHECKS; i++) {
            int row = i / CHECK_COLUMNS;
            int column = i % CHECK_COLUMNS;
            checks.add(new Rect(innerX + column * (checkWidth + pad), checksTop + row * (s.check() + s.checkGap()),
                    checkWidth, s.check()));
        }

        Rect footer = new Rect(panel.x(), panel.bottom() - s.footerHeight(), panel.w(), s.footerHeight());
        float buttonY = footer.y() + 1F + s.footerPad();
        float saveWidth = buttonWidth(measurer, saveLabel, s.button());
        float cancelWidth = buttonWidth(measurer, cancelLabel, s.button());
        Rect save = new Rect(panel.right() - pad - saveWidth, buttonY, saveWidth, s.button());
        Rect cancel = new Rect(save.x() - Metrics.GAP - cancelWidth, buttonY, cancelWidth, s.button());
        Rect status = new Rect(innerX, buttonY, Math.max(0F, cancel.x() - Metrics.GAP - innerX), s.button());

        return new Frame(width, height, compact, pad, panel, header, icon, title, headerNote, preview, nameLabel, nameField,
                skinLabel, List.copyOf(skinSources), skinRow, armsLabel, List.copyOf(armTypes), behaviourLabel,
                List.copyOf(checks), footer, status, cancel, save);
    }

    /** The footer message may take two lines of caption text beside the buttons: an error has to be read whole. */
    public static final int STATUS_LINES = 2;

    /** Line advance of the footer message. */
    public static float statusLineAdvance() {
        return Metrics.lineHeight(TextStyle.CAPTION);
    }

    /** Where the first line of a footer message of {@code lines} lines sits, so the block is centred on the buttons. */
    public static float statusTop(Rect status, int lines) {
        float block = TextStyle.CAPTION.capHeight() + Math.max(0, lines - 1) * statusLineAdvance();
        return status.y() + (status.h() - block) * 0.5F;
    }

    /** FancyButton draws its label in body text from a height of 18 and in caption text below that. */
    private static float buttonWidth(TextMeasurer measurer, String label, float height) {
        TextStyle style = height >= 18F ? TextStyle.BODY : TextStyle.CAPTION;
        return Math.max(BUTTON_MIN_WIDTH, (float) Math.ceil(TextFit.width(measurer, label, style) + BUTTON_TEXT_PAD));
    }

    private static float capY(TextStyle style, Rect box) {
        return box.y() + (box.h() - style.capHeight()) * 0.5F;
    }
}
