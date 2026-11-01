package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.data.NoteAppearance;

/**
 * In play a falling note and its button were out of line, with a gap where they met. This runs the production
 * BattlePanelRenderer.draw - the renderer of the battle HUD, the settings preview and the editor's gameplay preview -
 * with a recorder in place of the GPU, and measures every rectangle and triangle it draws, for every note skin, note
 * width and height, panel width and height, both screen sides and canvases of odd and even sizes:
 *
 * <ul>
 *   <li>the four lanes fill their panel exactly, so they are centred in it;</li>
 *   <li>every button is centred in its lane, and a note on its hit tick has the button's centre (an arrow or circle
 *       covers it exactly, a bar note is a taller bar on the button's own centre line);</li>
 *   <li>a held tail is centred on its button and runs into the middle of it;</li>
 *   <li>every edge of a button, a note head and a tail falls on a whole unit, which is a whole number of screen pixels
 *       in the battle HUD, so neither side of anything is drawn a pixel wider than the other;</li>
 *   <li>the four key labels are centred on their lanes at one size, and the name fits its strip.</li>
 * </ul>
 *
 * It also pins the header card's place and room (the old box's place, no taller, ending on the top stage bar), with the
 * words measured by Minecraft's own glyph provider at the HUD's tiers (GameTextMeasurer), and that the header and the
 * download bar draw only with toolkit tokens: no colour literal is left in them. The editor's gameplay preview draws the
 * same card between its two corner buttons, which share the width with it at every size (previewHeader). The panels over
 * the fighters in the world, which only spectators see, are drawn by BattlePanelRenderer.drawWorld and measured the same
 * way on every skin (worldPanels).
 */
public final class BattlePanelGeometryChecks {
    private static final float EPSILON = 1.0E-3F;
    /**
     * The least of its lane a world panel's coming note keeps above it, and the least gap between the lanes and the
     * progress strip: 0.01 of the panel's 1.24 height, a few screen pixels at the distance spectators watch from.
     */
    private static final float WORLD_LANE_HEADROOM = 0.01F;
    private static final Font FONT = new Font(ignored -> null, false) {
        @Override public int width(String text) { return text.length() * 6; }
    };
    private static final int[] COLORS = {0xFFFF4040, 0xFF40FF40, 0xFF4040FF, 0xFFFFFF40};
    private static final String[] LABELS = {"A", "Shift", "Up Arrow", "Left Button"};
    private static int checks;
    private static int cases;

    record Note(int owner, int lane, int tick, int durationTicks, boolean started, boolean resolved)
            implements BattlePanelRenderer.NoteView { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        int[][] canvases = {{320, 240}, {427, 240}, {427, 241}, {455, 256}, {456, 256}, {501, 281}, {640, 360},
                {641, 361}, {683, 384}, {711, 400}, {960, 540}, {1280, 720}};
        float[] noteScales = {0.35F, 0.5F, 0.7793827F, 1.0F, 1.3F, 1.8F, 2.2F};
        float[] widthScales = {0.45F, 0.6F, 0.64308643F, 0.8F, 1.0F, 1.2F, 1.6F};
        float[] heightScales = {0.55F, 1.0F, 1.5999999F};
        for (int[] canvas : canvases) {
            for (int skin = 0; skin < 3; skin++) {
                for (int side = 0; side < 2; side++) {
                    for (float widthScale : widthScales) {
                        for (float heightScale : heightScales) {
                            for (float noteWidth : noteScales) {
                                for (float noteHeight : noteScales) {
                                    panel(canvas[0], canvas[1], skin, side, widthScale, heightScale, noteWidth, noteHeight);
                                }
                            }
                        }
                    }
                }
            }
        }
        scaledPreview();
        header(root);
        previewHeader(root);
        worldPanels(root);
        sourcesUseTokens(root);
        System.out.println("Battle panel geometry PASS: " + checks + " checks on " + cases
                + " panels (3 skins, 49 note sizes, 21 panel sizes, 2 sides, 12 canvases), the scaled preview, the header,"
                + " the editor preview's corners (240-2560, en and ru, GUI scales 1-9), the world panels (3 skins, 2 sides)");
        System.out.println("NOT COVERED: the vanilla font's widths of the panel's own labels, the header's SDF shapes and text on a GPU,"
                + " the world panels' pose and their stats in the vanilla font, live play");
    }

    // ------------------------------------------------------------------------------------------------ the lanes

    private static void panel(int width, int height, int skin, int side, float widthScale, float heightScale,
                              float noteWidth, float noteHeight) throws Exception {
        cases++;
        BattlePanelRenderer.Panel panel = panel(skin, side, widthScale, heightScale, noteWidth, noteHeight);
        BattlePanelRenderer.Layout layout = BattlePanelRenderer.layout(width, height, 1F, panel);
        String where = width + "x" + height + " skin=" + skin + " side=" + side + " panel=" + widthScale + "x" + heightScale
                + " note=" + noteWidth + "x" + noteHeight;
        int laneWidth = layout.width() / 4;
        require(layout.width() == laneWidth * 4, "the panel is not four whole lanes: " + where);
        require(layout.width() == BattlePanelRenderer.panelWidth(width, widthScale), "panelWidth disagrees with layout: " + where);
        Recorder base = draw(width, height, panel, 1000F, List.of());
        List<Prim> strips = new ArrayList<>();
        for (int lane = 0; lane < 4; lane++) {
            strips.add(strip(base, lane, where));
        }
        require(Math.abs(strips.get(0).x0 - (layout.x() + 1)) < EPSILON
                        && Math.abs(strips.get(3).x1 - (layout.x() + layout.width() - 1)) < EPSILON,
                "the lanes do not fill the panel from edge to edge: " + where);
        float labelScale = Float.NaN;
        for (int lane = 0; lane < 4; lane++) {
            float laneCenter = strips.get(lane).cx();
            float[] receptor = bbox(receptor(base, lane));
            require(Math.abs(cx(receptor) - laneCenter) < EPSILON, "button off its lane's middle by "
                    + (cx(receptor) - laneCenter) + ": " + where + " lane=" + lane);
            whole(receptor, "button", where, lane);

            Recorder tap = draw(width, height, panel, 1000F, List.of(new Note(0, lane, 1000, 0, false, false)));
            float[] note = bbox(added(base, tap));
            require(Math.abs(cx(note) - cx(receptor)) < EPSILON && Math.abs(cy(note) - cy(receptor)) < EPSILON,
                    "a note on its tick is not centred on its button (" + (cx(note) - cx(receptor)) + ", "
                            + (cy(note) - cy(receptor)) + "): " + where + " lane=" + lane);
            whole(note, "note head", where, lane);
            if (skin == NoteAppearance.BARS) {
                require(note[1] < receptor[1] && note[3] > receptor[3], "a bar note does not cover its button's height: "
                        + where + " lane=" + lane);
                require(note[2] - note[0] <= laneWidth, "a bar note is wider than its lane: " + where);
            } else {
                for (int edge = 0; edge < 4; edge++) {
                    require(Math.abs(note[edge] - receptor[edge]) < EPSILON,
                            "an arrow or circle on its tick does not cover its button exactly: " + where + " lane=" + lane);
                }
            }

            Recorder hold = draw(width, height, panel, 1000F, List.of(new Note(0, lane, 990, 40, true, false)));
            float[] tail = bbox(added(base, hold));
            require(Math.abs(cx(tail) - cx(receptor)) < EPSILON, "a held tail is off its button's middle by "
                    + (cx(tail) - cx(receptor)) + ": " + where + " lane=" + lane);
            require(Math.abs(tail[3] - (float) Math.floor(cy(receptor))) < EPSILON,
                    "a held tail does not run into the middle of its button: " + where + " lane=" + lane);
            whole(tail, "tail", where, lane);

            Text label = base.label(LABELS[lane]);
            require(label != null, "no key label drawn for lane " + lane + ": " + where);
            require(Math.abs(label.centerX - laneCenter) < EPSILON, "key label off its lane's middle by "
                    + (label.centerX - laneCenter) + ": " + where);
            require(Float.isNaN(labelScale) || Math.abs(label.scale - labelScale) < EPSILON,
                    "the key labels are drawn at different sizes: " + where);
            require(label.width <= laneWidth - 6 + EPSILON, "a key label is wider than its lane: " + where);
            labelScale = label.scale;
        }
        Text name = base.label("A rather long player name");
        require(name != null && name.width <= layout.width() - 8 + EPSILON
                        && Math.abs(name.centerX - (layout.x() + layout.width() * 0.5F)) < EPSILON,
                "the name runs past its strip or off its middle: " + where);
    }

    /**
     * The settings preview draws the same renderer in a scaled, translated pose. Centring has to survive that: the
     * button and the note on its tick keep one centre in the preview's own pixels too.
     */
    private static void scaledPreview() throws Exception {
        for (int skin = 0; skin < 3; skin++) {
            BattlePanelRenderer.Panel panel = panel(skin, 0, 0.64308643F, 1.5999999F, 2.2F, 0.7793827F);
            for (float scale : new float[]{0.37F, 0.5F, 0.83F}) {
                Recorder base = Recorder.create();
                base.poses.translate(17.5F, 9.25F, 0F);
                base.poses.scale(scale, scale, 1F);
                BattlePanelRenderer.draw(base, FONT, 640, 360, 1000F, 100, 1F, List.of(), panel);
                for (int lane = 0; lane < 4; lane++) {
                    Recorder tap = Recorder.create();
                    tap.poses.translate(17.5F, 9.25F, 0F);
                    tap.poses.scale(scale, scale, 1F);
                    BattlePanelRenderer.draw(tap, FONT, 640, 360, 1000F, 100, 1F,
                            List.of(new Note(0, lane, 1000, 0, false, false)), panel);
                    float[] receptor = bbox(receptor(base, lane));
                    float[] note = bbox(added(base, tap));
                    require(Math.abs(cx(note) - cx(receptor)) < EPSILON && Math.abs(cy(note) - cy(receptor)) < EPSILON,
                            "scaled preview: a note on its tick is off its button, skin=" + skin + " scale=" + scale);
                    require(Math.abs(cx(receptor) - strip(base, lane, "scaled preview").cx()) < EPSILON,
                            "scaled preview: button off its lane, skin=" + skin + " scale=" + scale);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the header

    private static void header(Path root) throws Exception {
        // The HUD is drawn at one, two or three screen pixels per unit (BattleHudScale), so its text is at tier 1 to 3.
        for (int tier = 1; tier <= 3; tier++) {
            header(new GameTextMeasurer(root.resolve("src/main/resources/assets/music_battle/font"), tier));
        }
    }

    private static void header(GameTextMeasurer measurer) {
        float timer = measurer.width("00:00", TextStyle.LABEL);
        float loading = Math.max(measurer.width("Loading 100%", TextStyle.LABEL), measurer.width("Загрузка 100%", TextStyle.LABEL));
        for (int canvas = 320; canvas <= 1920; canvas++) {
            BattlePanelRenderer.HeaderLayout header = BattlePanelRenderer.headerLayout(canvas);
            int expectedWidth = Math.min(canvas - 80, 420);
            require(header.width() == expectedWidth && header.x() == canvas / 2 - expectedWidth / 2,
                    "the header left the old box's place at " + canvas);
            require(header.y() == 12 && header.height() == 24 && header.y() + header.height() == 36,
                    "the header is not 24 units tall from 12, ending on the 36-unit stage bar, at " + canvas);
            require(header.coverX() >= header.x() && header.coverY() >= header.y()
                    && header.coverY() + header.cover() <= header.y() + header.height()
                    && header.coverX() + header.cover() < header.textX(), "the cover leaves its card at " + canvas);
            Rect bar = header.bar();
            require(bar.x() >= header.textX() - EPSILON && bar.right() <= header.textRight() + EPSILON
                    && bar.y() > header.titleY() + TextStyle.STRONG.capHeight()
                    && bar.bottom() <= header.y() + header.height() - 4, "the progress bar leaves its row at " + canvas);
            float baseline = header.titleY() + TextStyle.STRONG.capHeight();
            require(Math.abs(header.statusY(TextStyle.STRONG, TextStyle.LABEL) + TextStyle.LABEL.capHeight() - baseline) < EPSILON,
                    "the time does not stand on the title's baseline at " + canvas);
            // The title keeps a real share of the row beside the widest time and the widest download status.
            require(header.titleRoom(timer) >= 60F && header.titleRoom(loading) >= 60F,
                    "a title has less than 60 units beside the time at " + canvas + ": " + header.titleRoom(loading));
            require(Math.abs(header.titleRoom(timer) + timer + BattlePanelRenderer.HeaderLayout.STATUS_GAP
                    - (header.textRight() - header.textX())) < EPSILON, "the title room does not leave the time its own room");
            Rect pill = header.pill(Math.max(measurer.width("READY · 3", TextStyle.PILL), measurer.width("ГОТОВЬСЯ · 3", TextStyle.PILL)));
            require(pill.y() >= header.y() + header.height() && pill.x() > 0 && pill.right() < canvas
                    && Math.abs(pill.x() + pill.w() * 0.5F - (header.x() + header.width() * 0.5F)) < EPSILON,
                    "the count-in pill is not centred under the card at " + canvas);
            // With the panels at their default width, the pill sits between them.
            BattlePanelRenderer.Panel standard = panel(1, 0, 0.64308643F, 1.5999999F, 2.2F, 0.7793827F);
            BattlePanelRenderer.Layout left = BattlePanelRenderer.layout(canvas, 360, 1F, standard);
            require(pill.x() > left.x() + left.width() && pill.right() < canvas - left.x() - left.width(),
                    "the count-in pill runs into a default-width panel at " + canvas);
        }
    }

    /**
     * The editor's gameplay preview has Back and Background in its top corners and the battle's card between them. Below
     * 356 units the card kept its 120-unit minimum and ran under both buttons, which drew over it; now the buttons and
     * the card share the width (TrackGameplayPreviewScreen.corners). At every width from 240 to 2560, in English and
     * Russian, with the labels measured by Minecraft's own glyph provider at GUI scales 1 to 9: the card is the battle's
     * (its top, its height, at most its width), centred, and keeps its gap to both buttons; the buttons are one width, 104
     * wherever that leaves the card its 120, and never narrower than their labels - the whole label, or on a narrow screen
     * Back's arrow and short word and Background's swatch, with its label only where the label fits. The card has its
     * 120 from 320 units up, the smallest window the mod is laid out for, and on a narrower one wherever the buttons
     * could still make room for it. Widening the window never narrows a button, nor the card by more than the unit an
     * odd width leaves over: the narrow form used to size the buttons to the arrow and the short word, so just before
     * the whole labels came in the card was up to 87 units wider than just after (Russian at 347 and 348 units). It also
     * confirms the old rule ran under the buttons at the widths it was reported at.
     */
    private static void previewHeader(Path root) throws Exception {
        Class<TrackGameplayPreviewScreen> screen = TrackGameplayPreviewScreen.class;
        int margin = staticInt(screen, "CORNER_MARGIN");
        int full = staticInt(screen, "CORNER_BUTTON_WIDTH");
        int gap = staticInt(screen, "CORNER_GAP");
        int labelPad = staticInt(screen, "LABEL_PAD");
        int swatchPad = staticInt(screen, "SWATCH_PAD");
        float arrow = staticFloat(screen, "ARROW") + staticFloat(screen, "ARROW_GAP");
        // Vanilla's "gui.back", which the screen shows on a narrow window, as Minecraft's own en_us.json and ru_ru.json
        // write it. The Russian file comes with the game's downloaded assets rather than in a jar, so the words are
        // written out here.
        String[][] labels = {
                {lang(root, "en_us", "music_battle.l10n.editor.preview_back"), "Back",
                        lang(root, "en_us", "music_battle.l10n.editor.preview_background")},
                {lang(root, "ru_ru", "music_battle.l10n.editor.preview_back"), "Назад",
                        lang(root, "ru_ru", "music_battle.l10n.editor.preview_background")}};
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (String[] words : labels) {
                int narrowest = (int) Math.ceil(TrackGameplayPreviewScreen.backWidth(measurer, words[1], true));
                int lastButton = 0;
                int lastCard = 0;
                for (int width = 240; width <= 2560; width++) {
                    TrackGameplayPreviewScreen.Corners corners = TrackGameplayPreviewScreen.corners(width, measurer, words[0],
                            words[1], words[2]);
                    BattlePanelRenderer.HeaderLayout card = corners.card();
                    int button = corners.buttonWidth();
                    String where = "'" + words[0] + "' at " + width + ", GUI scale " + tier;
                    require(card.y() == BattlePanelRenderer.HeaderLayout.TOP
                                    && card.height() == BattlePanelRenderer.HeaderLayout.HEIGHT,
                            "the preview's card is not at the battle's height " + where);
                    require(card.width() <= BattlePanelRenderer.HeaderLayout.MAX_WIDTH,
                            "the preview's card is wider than the battle's " + where + ": " + card.width());
                    require(card.width() >= TrackGameplayPreviewScreen.CARD_MIN
                                    || width < 320 && corners.compact() && button == narrowest,
                            "the preview's card is narrower than " + TrackGameplayPreviewScreen.CARD_MIN
                                    + " though the buttons could make room for it " + where + ": " + card.width());
                    require(Math.abs(card.x() + card.width() * 0.5F - width * 0.5F) <= 0.5F, "the preview's card is off centre " + where);
                    require(card.x() >= margin + button + gap && card.x() + card.width() <= width - margin - button - gap,
                            "the preview's card runs under a corner button " + where);
                    require(button <= full && (width < 356 || button == full && !corners.compact()),
                            "the corner buttons are not 104 wide where the card has its room " + where + ": " + button);
                    require(button >= lastButton && card.width() >= lastCard - 1,
                            "widening the window to " + width + " takes the buttons from " + lastButton + " to " + button
                                    + " and the card from " + lastCard + " to " + card.width() + ", '" + words[0]
                                    + "' at GUI scale " + tier);
                    lastButton = button;
                    lastCard = card.width();
                    float back = corners.compact() ? arrow + measurer.width(words[1], TextStyle.BODY)
                            : measurer.width(words[0], TextStyle.BODY);
                    require(back <= button - labelPad * 2 + EPSILON, "Back's label does not fit its button " + where);
                    float swatch = 20 - 8;
                    require(corners.backgroundLabel()
                                    ? swatchPad * 2 + swatch + measurer.width(words[2], TextStyle.BODY) <= button - labelPad + EPSILON
                                    : swatch + swatchPad * 2 <= button,
                            "Background's swatch or label does not fit its button " + where);
                }
            }
        }
        // What was reported: the old card, clamped to 120 between two 104-unit buttons, under both below 356.
        for (int width = 320; width < 356; width++) {
            int old = Math.max(120, Math.min(420, width - (margin + full + 6) * 2));
            BattlePanelRenderer.HeaderLayout card = BattlePanelRenderer.HeaderLayout.centred(width, old);
            require(card.x() < margin + full + 6, "the old rule no longer runs under Back at " + width
                    + ", so this check no longer measures what it was written for");
        }
    }

    private static float staticFloat(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(null);
    }

    private static String lang(Path root, String code, String key) throws Exception {
        String text = Files.readString(root.resolve("src/main/resources/assets/music_battle/lang/" + code + ".json"),
                StandardCharsets.UTF_8);
        com.google.gson.JsonElement value = com.google.gson.JsonParser.parseString(text).getAsJsonObject().get(key);
        require(value != null, "missing language key " + key + " in " + code);
        return value.getAsString();
    }

    // ------------------------------------------------------------------------------------------------ in the world

    /**
     * The panels over the fighters in the world, which spectators see: the production BattlePanelRenderer.drawWorld into
     * a recorder, measured on every skin the way the HUD panel is. The lanes are centred in the panel; every button is
     * centred in its lane and inside it; a note on its tick has its button's centre, an arrow or circle covering the
     * button exactly and a bar note covering its height; a held tail is centred on its button, runs into the middle of it
     * and, held past the end of the hold, stops there; cores and hollows are one rim in from every edge; a note starts
     * clear of the progress strip and is inside its lane, with some of the lane above it, from its first frame to its
     * tick; the lanes start under the strip; a missed note fades as the HUD's does; and with the default near-black
     * panel colour the progress strip is drawn in a colour that shows on its track.
     */
    private static void worldPanels(Path root) throws Exception {
        float rim = BattlePanelRenderer.World.RIM;
        for (int skin = 0; skin < 3; skin++) {
            for (int owner = 0; owner < 2; owner++) {
                String where = "world panel skin=" + skin + " owner=" + owner;
                BattlePanelRenderer.World.Panel panel = worldPanel(owner, skin, ru.gora.musicbattle.data.PlayerPanelDefaults.PANEL_COLOR);
                WorldRecorder base = drawWorld(panel, 1000F, List.of());
                BattlePanelRenderer.World.Area inner = BattlePanelRenderer.World.inner();
                List<WorldRecorder.Shape> strips = new ArrayList<>();
                for (int lane = 0; lane < 4; lane++) {
                    WorldRecorder.Shape strip = base.first("quad", COLORS[lane], 72);
                    require(strip != null, "no lane strip " + lane + ": " + where);
                    strips.add(strip);
                    require(strip.left() >= inner.left() && strip.right() <= inner.right() && strip.top() >= inner.top()
                            && strip.bottom() <= inner.bottom(), "a lane leaves the panel: " + where);
                    require(Math.abs(strip.width() - strips.get(0).width()) < EPSILON, "the lanes differ in width: " + where);
                }
                require(Math.abs((strips.get(0).left() + strips.get(3).right()) * 0.5F - inner.centerX()) < EPSILON,
                        "the lanes are off the panel's middle by " + ((strips.get(0).left() + strips.get(3).right()) * 0.5F)
                                + ": " + where);
                for (int lane = 0; lane < 4; lane++) {
                    WorldRecorder.Shape strip = strips.get(lane);
                    WorldRecorder.Shape button = base.first("glyph", COLORS[lane], 225);
                    require(button != null && Math.abs(button.centerX() - strip.centerX()) < EPSILON,
                            "a button is off its lane's middle: " + where + " lane=" + lane);
                    require(button.left() >= strip.left() - EPSILON && button.right() <= strip.right() + EPSILON
                                    && button.top() >= strip.top() && button.bottom() <= strip.bottom(),
                            "a button hangs out of its lane: " + where + " lane=" + lane + " " + button + " in " + strip);
                    if (skin != NoteAppearance.BARS) {
                        require(Math.abs(button.width() - button.height()) < EPSILON, "an arrow or circle button is not square: " + where);
                        core(base, button, Palette.BG_1, 210, skin, rim, "the button's hollow", where);
                    }

                    WorldRecorder tap = drawWorld(panel, 1000F, List.of(new Note(owner, lane, 1000, 0, false, false)));
                    WorldRecorder.Shape head = tap.added(base).stream().filter(shape -> shape.kind().equals("glyph")
                            && shape.alpha() == 245).findFirst().orElse(null);
                    require(head != null, "no note head drawn: " + where);
                    require(Math.abs(head.centerX() - button.centerX()) < EPSILON && Math.abs(head.centerY() - button.centerY()) < EPSILON,
                            "a note on its tick is not centred on its button: " + where + " lane=" + lane);
                    if (skin == NoteAppearance.BARS) {
                        require(head.top() < button.top() && head.bottom() > button.bottom(),
                                "a bar note does not cover its button's height: " + where + " " + head + " over " + button);
                        require(head.width() <= strip.width() + EPSILON, "a bar note is wider than its lane: " + where);
                    } else {
                        require(Math.abs(head.left() - button.left()) < EPSILON && Math.abs(head.right() - button.right()) < EPSILON
                                        && Math.abs(head.top() - button.top()) < EPSILON && Math.abs(head.bottom() - button.bottom()) < EPSILON,
                                "an arrow or circle on its tick does not cover its button exactly: " + where);
                    }
                    core(tap.added(base), head, -1, 245, skin, rim, "the note's core", where);

                    WorldRecorder hold = drawWorld(panel, 1000F, List.of(new Note(owner, lane, 990, 40, true, false)));
                    WorldRecorder.Shape tail = hold.addedQuad(base, COLORS[lane], 230);
                    require(tail != null && Math.abs(tail.centerX() - button.centerX()) < EPSILON,
                            "a held tail is off its button's middle: " + where + " lane=" + lane);
                    require(Math.abs(tail.bottom() - button.centerY()) < EPSILON,
                            "a held tail does not run into the middle of its button: " + where + " lane=" + lane);
                    WorldRecorder late = drawWorld(panel, 1000F, List.of(new Note(owner, lane, 960, 30, true, false)));
                    WorldRecorder.Shape ended = late.addedQuad(base, COLORS[lane], 230);
                    require(ended == null || ended.bottom() <= button.centerY() + EPSILON,
                            "a tail held past the end of its hold runs on past its button: " + where);
                    WorldRecorder coming = drawWorld(panel, 1000F, List.of(new Note(owner, lane, 1010, 20, false, false)));
                    WorldRecorder.Shape comingTail = coming.addedQuad(base, COLORS[lane], 230);
                    require(comingTail != null && Math.abs(comingTail.centerX() - button.centerX()) < EPSILON,
                            "a coming tail is off its lane's middle: " + where);

                    int lead = BattlePanelRenderer.leadTicks(panel.speed());
                    WorldRecorder spawned = drawWorld(panel, 1000F, List.of(new Note(owner, lane, 1000 + lead, 0, false, false)));
                    WorldRecorder.Shape fresh = spawned.added(base).stream().filter(shape -> shape.kind().equals("glyph")
                            && shape.alpha() == 245).findFirst().orElse(null);
                    require(fresh != null && fresh.top() >= BattlePanelRenderer.World.PROGRESS_BOTTOM + 0.03F - EPSILON,
                            "a new note starts on the progress strip: " + where);
                    // A note is drawn from six ticks before its lead starts (noteVisibleAt) and stands on its spawn row
                    // until then, so the first 300 ms of every note are spent there. The lanes used to start at -0.40,
                    // and every arrow or circle stuck 0.038 out of the top of its lane, a bar 0.02, over the dark gap
                    // under the progress strip. Now the whole note is inside its lane from its first frame to its tick,
                    // with some of the lane above it, as the HUD's fresh note has three units of its lane above it.
                    for (float time = 1000F - lead - 6F; time <= 1000F + EPSILON; time += 0.5F) {
                        WorldRecorder frame = drawWorld(panel, time, List.of(new Note(owner, lane, 1000, 0, false, false)));
                        WorldRecorder.Shape travelling = frame.added(base).stream().filter(shape -> shape.kind().equals("glyph")
                                && shape.alpha() == 245).findFirst().orElse(null);
                        require(travelling != null && travelling.top() >= strip.top() + WORLD_LANE_HEADROOM - EPSILON
                                        && travelling.left() >= strip.left() - EPSILON && travelling.right() <= strip.right() + EPSILON,
                                "a coming note sticks out of its lane " + (time - 1000F) + " ticks before its tick: " + where
                                        + " lane=" + lane + " " + travelling + " in " + strip);
                    }
                    require(strip.top() >= BattlePanelRenderer.World.PROGRESS_BOTTOM + WORLD_LANE_HEADROOM - EPSILON,
                            "a lane runs into the progress strip: " + where + " " + strip);
                }
                missedNotesFade(panel, base, where);
                // The default panel colour is as dark as the strip's track: the strip must not be drawn in it.
                WorldRecorder half = drawWorld(panel, 500F, List.of());
                WorldRecorder.Shape track = half.first("quad", Palette.BG_0, 255);
                WorldRecorder.Shape done = half.shapes.stream().filter(shape -> shape.kind().equals("quad")
                        && shape.top() == BattlePanelRenderer.World.PROGRESS_TOP && shape != track).findFirst().orElse(null);
                require(track != null && done != null && (done.color() & 0xFFFFFF) != (Palette.BG_0 & 0xFFFFFF)
                                && (done.color() & 0xFFFFFF) == (Palette.ACCENT & 0xFFFFFF),
                        "the progress strip of a dark panel is not drawn in the HUD's accent: " + where);
            }
        }
        String controller = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/ClientBattleController.java"),
                StandardCharsets.UTF_8);
        String render = body(controller, "private static void renderWorldPanel(");
        require(render.contains("BattlePanelRenderer.drawWorld(") && !Pattern.compile("drawQuad\\(|argb\\(|0x[0-9A-Fa-f]{6,8}")
                        .matcher(render).find(),
                "renderWorldPanel draws its own quads or colours again instead of BattlePanelRenderer.drawWorld");
        // A spectator's world panels are gameplay panels: an effect that leaves the interface uncovered must leave them
        // too. Drawn unmarked they sat under every full-screen effect, and a visualizer hid the scores for its section.
        int mark = render.indexOf("ClientModelGlowEngine.beginPanelFxMaskWrite()");
        int drawn = render.indexOf("BufferUploader.drawWithShader(", mark);
        int scored = render.indexOf("drawWorldPanelStats(", mark);
        int unmark = render.indexOf("ClientModelGlowEngine.endPanelFxMaskWrite()", Math.max(drawn, scored));
        require(mark >= 0 && drawn > mark && scored > mark && unmark > Math.max(drawn, scored),
                "the world panel and its score are not marked for the effects that leave the interface uncovered");
        String effects = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/ClientTrackEffects.java"),
                StandardCharsets.UTF_8);
        String overlay = body(effects, "private static void renderOverlay(GuiGraphics graphics, EffectState state, int interfacePass)");
        require(overlay.contains("boolean shouldPreservePanels = !effect.settings().overlayInterface()\n"
                        + "                        && ClientModelGlowEngine.hasPanelFxMask();")
                        && overlay.contains("shouldPreservePlayers, shouldPreserveEntities, shouldPreservePanels);"),
                "an effect that does not cover the interface covers a spectator's world panels again");
        String stats = body(controller, "private static void drawWorldPanelStats(");
        require(stats.contains("\"music_battle.settings.score\"") && stats.contains("BattlePanelRenderer.comboColor(")
                        && !stats.contains("\"SCORE") && !stats.contains("\"COMBO"),
                "the world panel's stats are English words again rather than the HUD's score line and combo colour");
        String renderer = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/BattlePanelRenderer.java"),
                StandardCharsets.UTF_8);
        String world = renderer.substring(renderer.indexOf("static final class World {"), renderer.indexOf("// ---------------"
                + "--------------------------------------------------------------------------------- the header"));
        require(!Pattern.compile("0x[0-9A-Fa-f]{6,8}|argb\\(").matcher(world).find(),
                "the world panel draws a colour literal instead of a Palette token");
    }

    /** A glyph's inside: one rim in from every edge, an arrow's as a parallel inset. {@code rgb} -1 takes any colour. */
    private static void core(List<WorldRecorder.Shape> shapes, WorldRecorder.Shape outer, int rgb, int alpha, int skin, float rim,
                             String what, String where) {
        WorldRecorder.Shape inner = shapes.stream().filter(shape -> shape != outer && shape.alpha() == alpha
                && (rgb == -1 || (shape.color() & 0xFFFFFF) == (rgb & 0xFFFFFF))
                && Math.abs(shape.centerX() - outer.centerX()) < EPSILON && Math.abs(shape.centerY() - outer.centerY()) < EPSILON
                && shape.width() < outer.width()).findFirst().orElse(null);
        require(inner != null, what + " is missing: " + where);
        if (skin == NoteAppearance.ARROWS) {
            require(inner.kind().equals("arrow-core") && Math.abs(inner.thickness() - rim) < EPSILON
                            && Math.abs(inner.halfSize() - outer.width() * 0.5F) < EPSILON,
                    what + " is not the arrow inset by one rim: " + where);
        } else {
            require(Math.abs(inner.left() - (outer.left() + rim)) < EPSILON && Math.abs(inner.right() - (outer.right() - rim)) < EPSILON
                            && Math.abs(inner.top() - (outer.top() + rim)) < EPSILON && Math.abs(inner.bottom() - (outer.bottom() - rim)) < EPSILON,
                    what + " is not one rim in from every edge: " + where + " " + inner + " in " + outer);
        }
    }

    private static void core(WorldRecorder recorder, WorldRecorder.Shape outer, int rgb, int alpha, int skin, float rim,
                             String what, String where) {
        core(recorder.shapes, outer, rgb, alpha, skin, rim, what, where);
    }

    /** A missed note is at full strength until the late window closes and fades out over the next six ticks. */
    private static void missedNotesFade(BattlePanelRenderer.World.Panel panel, WorldRecorder base, String where) {
        int closes = 1000 + BattlePanelRenderer.HIT_WINDOW_TICKS;
        int[] expected = {245, Math.round(245 * 0.5F), 0};
        float[] times = {closes, closes + 3F, closes + 6F};
        for (int index = 0; index < times.length; index++) {
            WorldRecorder late = drawWorld(panel, times[index], List.of(new Note(panel.owner(), 1, 1000, 0, false, false)));
            WorldRecorder.Shape head = late.added(base).stream().filter(shape -> shape.kind().equals("glyph")
                    && (shape.color() & 0xFFFFFF) == (COLORS[1] & 0xFFFFFF)).findFirst().orElse(null);
            require(head != null && head.alpha() == expected[index], "a missed note does not fade as the HUD's does at "
                    + times[index] + ": " + where + " " + head);
        }
    }

    private static BattlePanelRenderer.World.Panel worldPanel(int owner, int skin, int color) {
        return new BattlePanelRenderer.World.Panel(owner, color,
                new NoteAppearance(skin, List.of(COLORS[0], COLORS[1], COLORS[2], COLORS[3])), 1F, 2000, new int[4], new int[4]);
    }

    private static WorldRecorder drawWorld(BattlePanelRenderer.World.Panel panel, float time, List<Note> notes) {
        WorldRecorder recorder = new WorldRecorder();
        BattlePanelRenderer.drawWorld(recorder, panel, time, notes, 1F);
        return recorder;
    }

    /** The world panel's sink, recording every quad, glyph box and arrow core in panel units. */
    static final class WorldRecorder implements BattlePanelRenderer.WorldSink {
        record Shape(String kind, float left, float top, float right, float bottom, int color, float halfSize, float thickness) {
            float centerX() { return (left + right) * 0.5F; }
            float centerY() { return (top + bottom) * 0.5F; }
            float width() { return right - left; }
            float height() { return bottom - top; }
            int alpha() { return color >>> 24; }
        }

        final List<Shape> shapes = new ArrayList<>();

        @Override public void quad(float left, float top, float right, float bottom, int color) {
            shapes.add(new Shape("quad", left, top, right, bottom, color, 0F, 0F));
        }

        @Override public void glyph(float centerX, float centerY, float room, float barHalfWidth, float barHalfHeight, int skin,
                                    int lane, int color) {
            BattlePanelRenderer.World.Area box = BattlePanelRenderer.World.glyph(centerX, centerY, room, barHalfWidth,
                    barHalfHeight, skin, 1F);
            shapes.add(new Shape("glyph", box.left(), box.top(), box.right(), box.bottom(), color, 0F, 0F));
        }

        @Override public void insetArrow(int lane, float centerX, float centerY, float halfSize, float thickness, int color) {
            float inner = halfSize - thickness;
            shapes.add(new Shape("arrow-core", centerX - inner, centerY - inner, centerX + inner, centerY + inner, color,
                    halfSize, thickness));
        }

        Shape first(String kind, int rgb, int alpha) {
            return shapes.stream().filter(shape -> shape.kind().equals(kind) && shape.alpha() == alpha
                    && (shape.color() & 0xFFFFFF) == (rgb & 0xFFFFFF)).findFirst().orElse(null);
        }

        /** What a frame with notes draws after the same frame without them: the notes come after every lane. */
        List<Shape> added(WorldRecorder base) {
            require(shapes.size() >= base.shapes.size(), "the notes took shapes away");
            return new ArrayList<>(shapes.subList(base.shapes.size(), shapes.size()));
        }

        Shape addedQuad(WorldRecorder base, int rgb, int alpha) {
            return added(base).stream().filter(shape -> shape.kind().equals("quad") && shape.alpha() == alpha
                    && (shape.color() & 0xFFFFFF) == (rgb & 0xFFFFFF)).findFirst().orElse(null);
        }
    }

    private static int staticInt(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    /** The header and the download bar draw in toolkit tokens: no colour literal is left in either. */
    private static void sourcesUseTokens(Path root) throws Exception {
        String renderer = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/BattlePanelRenderer.java"),
                StandardCharsets.UTF_8);
        int start = renderer.indexOf("static void drawHeader(");
        require(start > 0, "BattlePanelRenderer.drawHeader is missing");
        String header = renderer.substring(start);
        require(!Pattern.compile("0x[0-9A-Fa-f]{6,8}|argb\\(").matcher(header).find(),
                "the header or the download bar draws a colour literal instead of a Palette token");
        String controller = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/ClientBattleController.java"),
                StandardCharsets.UTF_8);
        for (String method : new String[]{"private static void drawHeader(", "private static void renderPreloadBar("}) {
            String body = body(controller, method);
            require(!Pattern.compile("0x[0-9A-Fa-f]{6,8}|argb\\(|graphics\\.fill\\(|drawCenteredString").matcher(body).find(),
                    method + " draws its own boxes or colours again instead of BattlePanelRenderer's card");
        }
        String preview = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackGameplayPreviewScreen.java"),
                StandardCharsets.UTF_8);
        String previewHeader = body(preview, "private void drawBattleHeader(");
        require(previewHeader.contains("BattlePanelRenderer.drawHeader(") && previewHeader.contains("corners.card()")
                        && !Pattern.compile("0x[0-9A-Fa-f]{6,8}|argb\\(|graphics\\.fill\\(|drawCenteredString|blit\\(")
                        .matcher(previewHeader).find(),
                "the editor's gameplay preview draws its own header box again instead of the battle's card");
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        require(start >= 0, "missing " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char c = source.charAt(index);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    // ------------------------------------------------------------------------------------------------ measuring

    private static BattlePanelRenderer.Panel panel(int skin, int side, float widthScale, float heightScale,
                                                   float noteWidth, float noteHeight) {
        return new BattlePanelRenderer.Panel(0, side, true, "A rather long player name", 1F, 0xFF111111, 1F, 1F,
                widthScale, heightScale, noteWidth, noteHeight, 0, 0, new int[4], new int[4], LABELS.clone(),
                new NoteAppearance(skin, List.of(COLORS[0], COLORS[1], COLORS[2], COLORS[3])));
    }

    private static Recorder draw(int width, int height, BattlePanelRenderer.Panel panel, float time, List<Note> notes)
            throws Exception {
        Recorder recorder = Recorder.create();
        BattlePanelRenderer.draw(recorder, FONT, width, height, time, 120, 1F, notes, panel);
        return recorder;
    }

    /** The lane's strip: its own colour, running most of the panel's height. */
    private static Prim strip(Recorder recorder, int lane, String where) {
        for (Prim prim : recorder.prims) {
            if ((prim.color & 0xFFFFFF) == (COLORS[lane] & 0xFFFFFF) && prim.rect && prim.y1 - prim.y0 > 60) {
                return prim;
            }
        }
        throw new AssertionError("no strip for lane " + lane + ": " + where);
    }

    /** The button: everything in the lane's colour that is not its strip. */
    private static List<Prim> receptor(Recorder recorder, int lane) {
        List<Prim> found = new ArrayList<>();
        for (Prim prim : recorder.prims) {
            if ((prim.color & 0xFFFFFF) == (COLORS[lane] & 0xFFFFFF) && !(prim.rect && prim.y1 - prim.y0 > 60)) {
                found.add(prim);
            }
        }
        require(!found.isEmpty(), "no button drawn for lane " + lane);
        return found;
    }

    /** What a frame with notes draws after the same frame without them: the notes come after every lane. */
    private static List<Prim> added(Recorder base, Recorder with) {
        require(with.prims.size() > base.prims.size(), "the note drew nothing");
        return new ArrayList<>(with.prims.subList(base.prims.size(), with.prims.size()));
    }

    private static void whole(float[] box, String what, String where, int lane) {
        for (float edge : box) {
            require(Math.abs(edge - Math.round(edge)) < EPSILON, what + " edge " + edge + " is not on a whole unit: "
                    + where + " lane=" + lane);
        }
    }

    private static float[] bbox(List<Prim> prims) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Prim prim : prims) {
            x0 = Math.min(x0, prim.x0);
            y0 = Math.min(y0, prim.y0);
            x1 = Math.max(x1, prim.x1);
            y1 = Math.max(y1, prim.y1);
        }
        return new float[]{x0, y0, x1, y1};
    }

    private static float cx(float[] box) {
        return (box[0] + box[2]) * 0.5F;
    }

    private static float cy(float[] box) {
        return (box[1] + box[3]) * 0.5F;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    record Prim(boolean rect, float x0, float y0, float x1, float y1, int color) {
        float cx() { return (x0 + x1) * 0.5F; }
    }

    record Text(String text, float centerX, float y, float width, float scale) { }

    /** GuiGraphics with its sinks replaced: fills, GUI triangles and strings are recorded in the pose's coordinates. */
    static final class Recorder extends GuiGraphics {
        PoseStack poses;
        List<Prim> prims;
        List<Text> texts;
        MultiBufferSource.BufferSource buffers;

        private Recorder() {
            super(null, (MultiBufferSource.BufferSource) null);
        }

        static Recorder create() throws Exception {
            // GuiGraphics' constructor touches registries; recording geometry does not need them.
            var unsafeClass = Class.forName("sun.misc.Unsafe");
            var field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Recorder recorder = (Recorder) unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(field.get(null), Recorder.class);
            recorder.poses = new PoseStack();
            recorder.prims = new ArrayList<>();
            recorder.texts = new ArrayList<>();
            recorder.buffers = new MultiBufferSource.BufferSource(null, new LinkedHashMap<>()) {
                final VertexConsumer sink = new Triangles(recorder);
                @Override public VertexConsumer getBuffer(RenderType type) { return sink; }
            };
            return recorder;
        }

        Text label(String text) {
            for (Text recorded : texts) {
                if (recorded.text.equals(text)) return recorded;
            }
            return null;
        }

        @Override public PoseStack pose() { return poses; }
        @Override public MultiBufferSource.BufferSource bufferSource() { return buffers; }
        @Override public void enableScissor(int x, int y, int right, int bottom) { }
        @Override public void disableScissor() { }
        @Override public void flush() { }

        @Override public void fill(int left, int top, int right, int bottom, int color) {
            Vector3f a = poses.last().pose().transformPosition(left, top, 0, new Vector3f());
            Vector3f b = poses.last().pose().transformPosition(right, bottom, 0, new Vector3f());
            prims.add(new Prim(true, Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y), color));
        }

        @Override public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
            Vector3f at = poses.last().pose().transformPosition(x, y, 0, new Vector3f());
            float scale = poses.last().pose().m00();
            float width = font.width(text) * scale;
            texts.add(new Text(text, at.x + width * 0.5F, at.y, width, scale));
            return 0;
        }

        @Override public void drawCenteredString(Font font, String text, int x, int y, int color) {
            drawString(font, text, x - font.width(text) / 2, y, color, true);
        }

        @Override public void drawCenteredString(Font font, Component text, int x, int y, int color) {
            // Translations need the game's language; the combo and score are not measured here.
        }
    }

    static final class Triangles implements VertexConsumer {
        final Recorder recorder;
        final float[] x = new float[4];
        final float[] y = new float[4];
        int count;

        Triangles(Recorder recorder) { this.recorder = recorder; }

        @Override public VertexConsumer addVertex(float px, float py, float pz) {
            x[count] = px;
            y[count] = py;
            return this;
        }

        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            if (++count == 4) {
                float minX = Math.min(x[0], Math.min(x[1], x[2])), maxX = Math.max(x[0], Math.max(x[1], x[2]));
                float minY = Math.min(y[0], Math.min(y[1], y[2])), maxY = Math.max(y[0], Math.max(y[1], y[2]));
                recorder.prims.add(new Prim(false, minX, minY, maxX, maxY, alpha << 24 | red << 16 | green << 8 | blue));
                count = 0;
            }
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { return this; }
    }
}
