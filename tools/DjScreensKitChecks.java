package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ru.gora.musicbattle.client.DjCameraGalleryScreen.GalleryLayout;
import ru.gora.musicbattle.client.DjVisualFxScreen.FxLayout;
import ru.gora.musicbattle.client.DjVisualFxScreen.PickerLayout;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * The DJ's camera gallery and visual FX list in the toolkit's look, every word of them whole, in English and in Russian,
 * on every window from 320x240 up and at every GUI scale: measured as the game measures it, with Minecraft's own
 * FreeType glyph provider at each oversample tier 1 to 9 (GameTextMeasurer), and set by the screens' own pure layouts
 * ({@code DjCameraGalleryScreen.GalleryLayout}, {@code DjVisualFxScreen.FxLayout} and {@code PickerLayout}).
 *
 * <p>Both screens were the first look: vanilla stone buttons and the bitmap font in the gallery, "[ON]"/"[OFF]" after
 * the FX list's switch words, ↑ and ↓ that Nunito does not have, an order hint drawn past the panel's right edge, and at
 * 320x240 labels cut to "Beatmap FX [O…". This check re-measures what the layouts placed instead of trusting them:
 * every button's words against its button in the face its height gives it, the gallery's title, card names, the
 * preview's six messages and the empty state, the FX list's title, its hint's lines inside the panel, every effect's
 * name on a row with the number of its eighth copy, the empty list, and the type picker's heading, names and
 * descriptions. It pins that the controls stay inside the window (and the FX panel) and clear of each other, that a
 * whole card and two rows fit, and that the sources draw no word in the bitmap font. A source rule over the whole client
 * keeps vanilla's own Button out of every screen: Button.builder only builds the mod's own subclasses.
 *
 * <p>NOT COVERED: how they look in the game (the visual session does that), a camera's coordinates (data the card may
 * shorten), windows lower than 240.
 */
public final class DjScreensKitChecks {
    private static final int[] HEIGHTS = {240, 250, 270, 299, 300, 330, 360, 400, 480, 540, 720, 1080};
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;
    private static int layouts;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        sources(root);
        List<Integer> widths = new ArrayList<>();
        for (int width = 320; width <= 1000; width++) {
            widths.add(width);
        }
        for (int width = 1002; width <= 1920; width += 7) {
            widths.add(width);
        }
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            GameTextMeasurer measurer = new GameTextMeasurer(fonts, tier);
            for (int language = 0; language < 2; language++) {
                DjPanelScreen.Words words = words(lang, language);
                String name = (language == 0 ? "en" : "ru") + " at GUI scale " + tier;
                int oneRow = 0;
                int described = 0;
                for (int width : widths) {
                    for (int height : HEIGHTS) {
                        String where = name + " " + width + "x" + height;
                        GalleryLayout gallery = GalleryLayout.compute(width, height, measurer, words);
                        gallery(gallery, measurer, words, where);
                        oneRow += gallery.buttonRows() == 1 ? 1 : 0;
                        fx(FxLayout.compute(width, height, measurer, words), measurer, words, where);
                        PickerLayout picker = PickerLayout.compute(width, height, measurer, words,
                                DjVisualEffects.supportedTypes());
                        picker(picker, measurer, words, where);
                        described += picker.descriptions() ? 1 : 0;
                        layouts += 3;
                    }
                }
                System.out.println(name + ": the gallery's actions on one row in " + oneRow + " of "
                        + widths.size() * HEIGHTS.length + " windows, the picker describes its effects in " + described);
            }
        }
        System.out.println("DJ screens kit: " + layouts + " layouts, " + checks + " checks");
        FAILURES.stream().limit(40).forEach(System.err::println);
        if (!FAILURES.isEmpty()) {
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("DJ screens kit PASS: the camera gallery, the visual FX list and its type picker, every word whole, "
                + "en and ru, every width from 320 to 1920, heights 240 to 1080, GUI scales 1-9; no vanilla Button in "
                + "any client screen");
        System.out.println("NOT COVERED: the screens in the game, a camera's coordinates, windows lower than 240");
    }

    /** The gallery: the title, the four actions, a card's name, the preview's messages and the empty state. */
    private static void gallery(GalleryLayout layout, GameTextMeasurer measurer, DjPanelScreen.Words words, String where) {
        String at = where + " gallery";
        require(layout.problems().isEmpty(), at + ": the layout reports " + layout.problems());
        Rect window = new Rect(0, 0, layout.width(), layout.height());
        fitsText(measurer, words.get(GalleryLayout.KEY + "title"), layout.titleFace(), layout.width() - 2 * GalleryLayout.MARGIN,
                at + " title");
        require(layout.titleY() >= 0 && layout.titleY() + layout.titleFace().capHeight() <= layout.header(),
                at + ": the title sits in the header");
        Map<String, Rect> buttons = new java.util.LinkedHashMap<>();
        buttons.put("back", layout.back());
        buttons.put("select", layout.select());
        buttons.put("control", layout.control());
        buttons.put("unlink", layout.unlink());
        List<Rect> placed = new ArrayList<>(buttons.values());
        for (Map.Entry<String, Rect> button : buttons.entrySet()) {
            Rect rect = button.getValue();
            require(rect.inside(window, 0.01F) && rect.y() >= layout.footerTop(), at + ": " + button.getKey()
                    + " sits in the footer inside the window, " + rect);
            fitsText(measurer, words.get(GalleryLayout.KEY + button.getKey()), KitButton.face(rect.h()),
                    rect.w() - KitButton.PAD, at + " button " + button.getKey());
        }
        noOverlaps(placed, at + " buttons");
        require(layout.footerTop() >= layout.contentBottom() && layout.contentTop() >= layout.header(),
                at + ": the cards' view lies between the header and the footer");
        GalleryLayout.Card first = layout.card(0, 0);
        require(first.height() <= layout.contentBottom() - layout.contentTop(), at + ": a whole card fits the view");
        Rect last = new Rect(layout.card(layout.columns() - 1, 0).x(), first.y(), first.width(), first.height());
        require(first.x() >= 0 && last.right() <= layout.width() - GalleryLayout.MARGIN + 0.01F,
                at + ": a row of cards stays inside the window's margins");
        fitsText(measurer, words.format(GalleryLayout.KEY + "camera", Integer.toString(GalleryLayout.MOST_CAMERAS)),
                GalleryLayout.NAME_FACE, first.width() - GalleryLayout.CARD_PAD * 2, at + " card name");
        require(first.preview().inside(new Rect(first.x(), first.y(), first.width(), first.height()), 0.01F)
                        && first.nameY() + GalleryLayout.NAME_FACE.capHeight() <= first.y() + first.height() - 2,
                at + ": the card holds its picture and its name");
        require(Math.abs(first.preview().w() / first.preview().h() - 16.0F / 9.0F) < 0.05F,
                at + ": the picture is 16:9, " + first.preview().w() + "x" + first.preview().h());
        for (String key : GalleryLayout.STATUS_KEYS) {
            String text = words.get(key);
            DjPanelScreen.PanelLayout.Block block = GalleryLayout.statusBlock(measurer, text, first.preview());
            require(block != null, at + ": the picture holds the message " + key);
            if (block != null) {
                whole(measurer, text, block, first.preview().w() - 8, at + " " + key);
                require(block.height() <= first.preview().h() - 6 + 0.01F, at + ": " + key + " fits the picture's height");
            }
        }
        Rect empty = layout.emptyPanel();
        String message = words.get(GalleryLayout.KEY + "empty");
        DjPanelScreen.PanelLayout.Block block = GalleryLayout.emptyBlock(measurer, message, empty);
        require(block != null && empty.inside(window, 0.01F), at + ": the empty gallery holds its message");
        if (block != null) {
            whole(measurer, message, block, empty.w() - 24, at + " empty");
            require(20 + block.height() + 8 <= empty.h() + 0.01F, at + ": the empty message and its icon fit the panel");
        }
    }

    /** The FX list: the title, the switches and buttons, the hint inside the panel, the rows' names, the empty list. */
    private static void fx(FxLayout layout, GameTextMeasurer measurer, DjPanelScreen.Words words, String where) {
        String at = where + " FX list";
        require(layout.problems().isEmpty(), at + ": the layout reports " + layout.problems());
        Rect window = new Rect(0, 0, layout.width(), layout.height());
        Rect panel = layout.panel();
        require(panel.inside(window, 0.01F), at + ": the panel stays inside the window, " + panel);
        float inner = panel.w() - FxLayout.PAD * 2;
        fitsText(measurer, words.get(FxLayout.KEY + "title"), layout.titleFace(), inner, at + " title");
        require(layout.titleY() >= panel.y() && layout.titleY() + layout.titleFace().capHeight() <= layout.separatorY(),
                at + ": the title sits above its rule");
        require(layout.controls().size() == FxLayout.Control.values().length, at + ": every control is placed");
        Rect content = new Rect(panel.x() + FxLayout.PAD, panel.y(), inner, panel.h() - FxLayout.PAD);
        List<Rect> placed = new ArrayList<>();
        for (FxLayout.Control control : FxLayout.Control.values()) {
            Rect rect = layout.rect(control);
            if (rect == null) {
                continue;
            }
            placed.add(rect);
            require(rect.inside(content, 0.01F), at + ": " + control + " stays inside the panel, " + rect);
            switch (layout.kind(control)) {
                case TEXT -> fitsText(measurer, FxLayout.words(control, words), KitButton.face(rect.h()),
                        rect.w() - KitButton.PAD, at + " " + control);
                case SWITCH -> fitsText(measurer, FxLayout.words(control, words), KitButton.face(rect.h()),
                        rect.w() - KitButton.PAD - KitButton.CHECK - KitButton.CHECK_GAP, at + " switch " + control);
                case ICON -> require(rect.w() >= 12 && rect.h() >= 12, at + ": " + control + "'s icon has room");
            }
            if (control.ordinal() >= FxLayout.Control.ADD.ordinal()) {
                require(rect.y() >= layout.list().bottom(), at + ": " + control + " lies under the list");
            } else {
                require(rect.y() >= layout.separatorY() && rect.bottom() <= layout.hintY(),
                        at + ": " + control + " lies between the rule and the hint");
            }
        }
        noOverlaps(placed, at + " controls");
        // The order hint: its own words in lines inside the panel, none broken, above the list.
        String hint = words.get(FxLayout.KEY + "order_hint");
        require(String.join(" ", layout.hint()).equals(String.join(" ", hint.split(" +"))),
                at + ": the order hint is all there, wrapped at spaces: " + layout.hint());
        for (String line : layout.hint()) {
            fitsText(measurer, line, TextStyle.CAPTION, inner, at + " hint line");
        }
        require(layout.hintY() + layout.hint().size() * Metrics.lineHeight(TextStyle.CAPTION) <= layout.list().y() + 0.01F,
                at + ": the hint ends above the list");
        require(layout.visibleRows() >= 2, at + ": the list shows two rows at least, " + layout.visibleRows());
        // A row's name runs from past the pill to the order number ("8" at most, eight effects), which stands six in from
        // the row's right edge, eight units apart, with the scrollbar's six units taken off a full list's rows.
        float nameRoom = layout.list().w() - 6 - measurer.width("8", TextStyle.CAPTION) - 8 - FxLayout.ROW_TEXT - 6;
        require(Math.abs(layout.nameRoom(measurer) - nameRoom) < 0.01F, at + ": the layout gives a row's name the room the "
                + "row leaves it, " + layout.nameRoom(measurer) + " / " + nameRoom);
        for (TrackEffect.Type type : DjVisualEffects.supportedTypes()) {
            fitsText(measurer, words.get(type.translationKey()) + FxLayout.MOST_COPIES, layout.rowFace(), nameRoom,
                    at + " row " + type);
        }
        String empty = words.get(FxLayout.KEY + "empty");
        DjPanelScreen.PanelLayout.Block block = FxLayout.emptyBlock(measurer, empty, layout.list());
        require(block != null, at + ": the empty list holds its message");
        if (block != null) {
            whole(measurer, empty, block, layout.list().w() - 12, at + " empty");
        }
    }

    /** The type picker: inside the window, its heading, every name whole, and each description in two lines when shown. */
    private static void picker(PickerLayout layout, GameTextMeasurer measurer, DjPanelScreen.Words words, String where) {
        String at = where + " type picker";
        require(layout.problems().isEmpty(), at + ": the layout reports " + layout.problems());
        Rect window = new Rect(0, 0, layout.width(), layout.height());
        Rect panel = layout.panel();
        require(panel.inside(window, 0.01F), at + ": the picker stays inside the window, " + panel);
        float inner = panel.w() - PickerLayout.PAD * 2;
        for (String key : List.of(PickerLayout.ADD_HEADING, PickerLayout.TYPE_HEADING)) {
            fitsText(measurer, words.get(key), layout.headingFace(), inner, at + " heading");
        }
        fitsText(measurer, words.get(PickerLayout.LEAVE_HINT), TextStyle.CAPTION, inner, at + " foot line");
        Rect view = layout.view();
        require(view.inside(panel, 0.01F) && view.y() >= layout.separatorY() && view.bottom() <= layout.footY(),
                at + ": the cards' view lies inside the panel, under its rule and above its foot");
        List<TrackEffect.Type> types = DjVisualEffects.supportedTypes();
        float room = layout.cardWidth() - PickerLayout.TEXT_LEFT - PickerLayout.TEXT_RIGHT;
        Rect first = layout.card(0, 0);
        require(first.h() <= view.h(), at + ": a whole card fits the view");
        List<Rect> row = new ArrayList<>();
        for (int index = 0; index < Math.min(types.size(), layout.columns()); index++) {
            row.add(layout.card(index, 0));
            require(layout.card(index, 0).right() <= view.right() + 0.01F, at + ": a row of cards stays inside the view");
        }
        noOverlaps(row, at + " cards");
        for (TrackEffect.Type type : types) {
            fitsText(measurer, words.get(type.translationKey()), PickerLayout.NAME_FACE, room, at + " " + type);
            if (layout.descriptions()) {
                String description = words.get(type.translationKey() + ".description");
                DjPanelScreen.PanelLayout.Block block = DjPanelScreen.PanelLayout.block(measurer, description, room,
                        2 * Metrics.lineHeight(TextStyle.CAPTION), TextStyle.CAPTION);
                require(block != null && block.lines().size() <= 2, at + ": " + type + "'s description fits two lines");
            }
        }
        int last = types.size() - 1;
        Rect end = layout.card(last, layout.scrollMax());
        require(end.bottom() <= view.bottom() + 0.5F, at + ": scrolled to its end the picker shows its last card whole");
    }

    /** The screens' own sources, and every client screen's buttons. */
    private static void sources(Path root) throws Exception {
        Path client = root.resolve("src/main/java/ru/gora/musicbattle/client");
        for (String name : List.of("DjCameraGalleryScreen", "DjVisualFxScreen", "KitButton")) {
            String code = flat(client.resolve(name + ".java"));
            for (String bitmap : List.of("drawString(", "drawCenteredString(", "plainSubstrByWidth(", "font.width(",
                    "Button.builder(", "FancyButton.create(", "[ON]", "[OFF]", "↑", "↓")) {
                require(!code.contains(bitmap), name + " draws nothing of the first look: " + bitmap);
            }
            Matcher fill = Pattern.compile("graphics\\.fill\\(([^;]*)\\);").matcher(code);
            while (fill.find()) {
                require(fill.group(1).startsWith("0, 0, width, height,"), name + " fills only the whole window square (its "
                        + "backdrop or veil); every shape is the toolkit's: graphics.fill(" + fill.group(1) + ")");
            }
        }
        String gallery = flat(client.resolve("DjCameraGalleryScreen.java"));
        require(gallery.replace("( ", "(").contains("KitButton.text(Component.translatable(\"screen.music_battle."
                        + "dj_camera_gallery.select\"), button -> selectCurrent(), layout.select(), "
                        + "WorkshopDraw.ButtonStyle.PRIMARY)") && gallery.contains("layout.unlink(), WorkshopDraw.ButtonStyle.DANGER)"),
                "the gallery's Select is its primary action and Disconnect its danger");
        // Vanilla's Button draws the stone slab and the bitmap font. Button.builder may only build one of the mod's own
        // subclasses (FancyButton, the guide's SectionButton), never vanilla's button with .build().
        int builders = 0;
        try (Stream<Path> files = Files.walk(client)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = flat(file);
                Matcher builder = Pattern.compile("Button\\.builder\\(").matcher(code);
                while (builder.find()) {
                    builders++;
                    int end = code.indexOf(';', builder.start());
                    String statement = code.substring(builder.start(), end < 0 ? code.length() : end);
                    require(statement.matches("(?s).*\\.build\\([^)\\s].*"), client.relativize(file) + " builds a "
                            + "vanilla Button (stone slab, bitmap font): " + statement);
                }
                require(!code.matches("(?s).*\\bnew Button\\(.*") || file.endsWith("workshop/layout/Form.java"),
                        client.relativize(file) + " makes a vanilla Button");
            }
        }
        require(builders >= 2, "the client's Button.builder calls are there to check, " + builders);
    }

    private static void noOverlaps(List<Rect> rects, String at) {
        for (int index = 0; index < rects.size(); index++) {
            for (int other = index + 1; other < rects.size(); other++) {
                require(!rects.get(index).overlaps(rects.get(other), 0.01F), at + ": " + rects.get(index) + " and "
                        + rects.get(other) + " do not overlap");
            }
        }
    }

    /** Every line of a block within the width, and the lines together the text's own words, none broken. */
    private static void whole(GameTextMeasurer measurer, String text, DjPanelScreen.PanelLayout.Block block, float width,
                              String where) {
        for (String line : block.lines()) {
            fitsText(measurer, line, block.face(), width, where + " line");
        }
        require(String.join(" ", block.lines()).equals(String.join(" ", text.split(" +"))), where
                + ": wrapped at spaces only, no word broken: " + block.lines());
    }

    private static void fitsText(GameTextMeasurer measurer, String text, TextStyle face, float room, String where) {
        float width = measurer.width(text, face);
        require(width <= room + 0.01F, where + ": '" + text + "' is " + width + " wide in " + face + ", room " + room);
    }

    /** A source without its comments, its whitespace collapsed to single spaces. */
    private static String flat(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "").replaceAll("\\s+", " ");
    }

    private static DjPanelScreen.Words words(Path lang, int language) throws Exception {
        Map<String, String> values = new HashMap<>();
        Path file = lang.resolve(language == 0 ? "en_us.json" : "ru_ru.json");
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject().entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new DjPanelScreen.Words(key -> {
            String value = values.get(key);
            if (value == null) {
                throw new AssertionError("missing language key " + key + " (merge this wave's lang_keys)");
            }
            return value;
        }, Locale.forLanguageTag(language == 0 ? "en-US" : "ru-RU"));
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition && FAILURES.size() < 400) {
            FAILURES.add("FAIL: " + message);
        }
    }
}
