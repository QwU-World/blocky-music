import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.gora.musicbattle.client.RapGodConfigLayout;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * The test NPC's settings screen, measured without a window: RapGodConfigLayout at every GUI size Minecraft gives a
 * common screen, in English and Russian, with the bundled Nunito measured through java.awt. Everything stays inside the
 * card and the card inside the window, no two controls overlap, the controls keep their reading order, the figure is
 * there whenever the card is wide enough for it, and no label, choice or footer message is ever cut.
 *
 * The strings come from the lang files; a key the lang files do not have yet is measured in the wording this change
 * proposes for it, so the check means the same before and after the strings are merged.
 *
 * What it does not prove: how the screen looks in the game, or how the figure is drawn.
 *
 * Usage: java -cp <classpath> RapGodConfigLayoutChecks <project root>
 */
public final class RapGodConfigLayoutChecks {
    static int checks;
    private static final float SLACK = 0.5F;
    private static final int[][] SIZES = {
            {320, 240}, {400, 300}, {427, 240}, {480, 270}, {504, 327}, {569, 320}, {640, 360}, {683, 384},
            {756, 491}, {854, 480}, {960, 540}, {1280, 720}, {1920, 1080}, {430, 300}, {430, 293}, {700, 294}
    };

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Measurer measurer = new Measurer(root.resolve("src/main/resources/assets/music_battle/font"));
        for (String language : new String[]{"en_us", "ru_ru"}) {
            Strings strings = new Strings(root, language);
            for (int[] size : SIZES) {
                verify(RapGodConfigLayout.frame(size[0], size[1], measurer, strings.get("music_battle.l10n.client.044"),
                        strings.get("music_battle.l10n.client.100")), measurer, strings, language + " " + size[0] + "x" + size[1]);
            }
        }
        System.out.println("NPC settings layout checks passed: " + checks + " (" + SIZES.length + " window sizes, 2 languages)");
    }

    private static void verify(RapGodConfigLayout.Frame f, TextMeasurer measurer, Strings strings, String context) {
        Rect window = new Rect(0, 0, f.width(), f.height());
        Rect panel = f.panel();
        check(panel.inside(window, SLACK), context + ": the card fits the window " + panel);
        // The regular card is whatever a tall window of the same width gets; a window it does not fit with its margins
        // gets the compact spacing, and so does any window the Workshop itself calls compact.
        float regular = RapGodConfigLayout.frame(Math.max(f.width(), 640F), 1000F, measurer, "Cancel", "Save").panel().h();
        check(f.compact() == (Metrics.compact(f.width(), f.height()) || f.height() < regular + 24F),
                context + ": compact exactly when the window is short or narrow");
        if (!f.compact()) {
            check(panel.h() + 24F <= f.height() + SLACK, context + ": the regular card keeps its margins");
        }

        Map<String, Rect> parts = new LinkedHashMap<>();
        parts.put("header", f.header());
        parts.put("icon", f.icon());
        parts.put("title", f.title());
        parts.put("headerNote", f.headerNote());
        parts.put("nameLabel", f.nameLabel());
        parts.put("nameField", f.nameField());
        parts.put("skinLabel", f.skinLabel());
        parts.put("skinRow", f.skinRow());
        parts.put("armsLabel", f.armsLabel());
        parts.put("behaviourLabel", f.behaviourLabel());
        parts.put("footer", f.footer());
        parts.put("status", f.status());
        parts.put("cancel", f.cancel());
        parts.put("save", f.save());
        for (int i = 0; i < f.skinSources().size(); i++) {
            parts.put("skinSource" + i, f.skinSources().get(i));
        }
        for (int i = 0; i < f.armTypes().size(); i++) {
            parts.put("armType" + i, f.armTypes().get(i));
        }
        for (int i = 0; i < f.checks().size(); i++) {
            parts.put("check" + i, f.checks().get(i));
        }
        if (!f.preview().empty()) {
            parts.put("preview", f.preview());
        }
        for (Map.Entry<String, Rect> part : parts.entrySet()) {
            check(part.getValue().inside(panel, SLACK), context + ": " + part.getKey() + " inside the card " + part.getValue());
        }
        check(f.skinSources().size() == RapGodConfigLayout.SKIN_SOURCES && f.armTypes().size() == RapGodConfigLayout.ARM_TYPES
                && f.checks().size() == RapGodConfigLayout.CHECKS, context + ": every control has a place");

        // Nothing a player can click lies on top of anything else they can click.
        List<String> controls = new ArrayList<>(List.of("nameField", "skinRow", "status", "cancel", "save"));
        for (String key : parts.keySet()) {
            if (key.startsWith("skinSource") || key.startsWith("armType") || key.startsWith("check") || key.equals("preview")) {
                controls.add(key);
            }
        }
        for (int a = 0; a < controls.size(); a++) {
            for (int b = a + 1; b < controls.size(); b++) {
                check(!parts.get(controls.get(a)).overlaps(parts.get(controls.get(b)), SLACK),
                        context + ": " + controls.get(a) + " and " + controls.get(b) + " overlap");
            }
        }

        // Reading order, top to bottom.
        check(f.header().bottom() <= f.nameLabel().y() + SLACK, context + ": the name comes after the title bar");
        check(f.nameLabel().bottom() <= f.nameField().y() + SLACK, context + ": the name label is above its field");
        check(f.nameField().bottom() <= f.skinLabel().y() + SLACK, context + ": the skin comes after the name");
        check(f.skinLabel().bottom() <= f.skinSources().get(0).y() + SLACK, context + ": the skin label is above its choices");
        check(f.skinSources().get(0).bottom() <= f.skinRow().y() + SLACK, context + ": the skin choices are above their row");
        check(f.skinRow().bottom() <= f.armsLabel().y() + SLACK, context + ": the arms come after the skin");
        check(f.armsLabel().bottom() <= f.armTypes().get(0).y() + SLACK, context + ": the arms label is above its choices");
        check(f.armTypes().get(0).bottom() <= f.behaviourLabel().y() + SLACK, context + ": behaviour comes last");
        check(f.behaviourLabel().bottom() <= f.checks().get(0).y() + SLACK, context + ": the behaviour label is above its boxes");
        Rect lastCheck = f.checks().get(f.checks().size() - 1);
        check(lastCheck.bottom() <= f.footer().y() + SLACK, context + ": the checkboxes end above the footer");
        check(f.cancel().right() <= f.save().x() + SLACK, context + ": Save is the right-hand button");

        // The figure is there whenever the card can spare the room for it, and it is big enough to see a skin on.
        float inner = panel.w() - f.pad() * 2F;
        check(f.preview().empty() == inner < 330F, context + ": the figure is shown exactly when there is room for it");
        if (!f.preview().empty()) {
            check(f.preview().w() >= 90F && f.preview().h() >= 80F, context + ": the figure's card is big enough " + f.preview());
            check(f.preview().right() + f.pad() <= f.nameField().x() + SLACK, context + ": the figure stands beside the controls");
        }

        // Controls big enough to hit and to read.
        check(f.nameField().w() >= 120F && f.nameField().h() >= 14F, context + ": the name field is usable " + f.nameField());
        for (Rect segment : f.skinSources()) {
            check(segment.w() >= 40F && segment.h() >= 12F, context + ": a skin choice is usable " + segment);
        }
        for (Rect row : f.checks()) {
            check(row.w() >= 100F && row.h() >= 10F, context + ": a checkbox row is usable " + row);
            check(RapGodConfigLayout.checkBox(row, f.compact()).inside(row, SLACK), context + ": the box sits in its row");
            check(RapGodConfigLayout.checkText(row, f.compact()).inside(row, SLACK), context + ": the label sits in its row");
        }

        // No word is ever cut.
        TextStyle control = f.controlStyle();
        fits(measurer, strings.get("screen.music_battle.npc.title"), TextStyle.TITLE, f.title().w(), context + ": title");
        for (String key : new String[]{"screen.music_battle.npc.name", "screen.music_battle.npc.skin",
                "screen.music_battle.npc.arms", "screen.music_battle.npc.behaviour"}) {
            fits(measurer, strings.get(key), TextStyle.LABEL, f.nameLabel().w(), context + ": " + key);
        }
        String[] sources = {"screen.music_battle.npc.skin.default", "screen.music_battle.npc.skin.nickname",
                "screen.music_battle.npc.skin.file"};
        for (int i = 0; i < sources.length; i++) {
            fits(measurer, strings.get(sources[i]), control, f.skinSources().get(i).w() - 8F, context + ": " + sources[i]);
        }
        String[] arms = {"screen.music_battle.npc.arms.classic", "screen.music_battle.npc.arms.slim"};
        for (int i = 0; i < arms.length; i++) {
            fits(measurer, strings.get(arms[i]), control, f.armTypes().get(i).w() - 8F, context + ": " + arms[i]);
        }
        String[] checkKeys = {"screen.music_battle.npc.stationary", "screen.music_battle.npc.pushable",
                "screen.music_battle.npc.invulnerable", "screen.music_battle.npc.glowing", "screen.music_battle.npc.random_votes"};
        for (int i = 0; i < checkKeys.length; i++) {
            fits(measurer, strings.get(checkKeys[i]), control,
                    RapGodConfigLayout.checkText(f.checks().get(i), f.compact()).w(), context + ": " + checkKeys[i]);
        }
        Rect row = f.skinRow();
        fits(measurer, strings.get("screen.music_battle.npc.skin.default.note"), control, row.w() - 4F,
                context + ": the note of the NPC's own outfit");
        float zoneText = row.w() - 5F - Math.min(9F, row.h() - 6F) - 5F - 5F;
        fits(measurer, strings.get("screen.music_battle.npc.skin.file.choose"), control, zoneText, context + ": the file invitation");
        fits(measurer, strings.get("screen.music_battle.npc.skin.file.current"), control, zoneText - 40F,
                context + ": the current file skin beside its size");
        TextStyle buttonStyle = f.save().h() >= 18F ? TextStyle.BODY : TextStyle.CAPTION;
        fits(measurer, strings.get("music_battle.l10n.client.100"), buttonStyle, f.save().w() - 10F, context + ": Save");
        fits(measurer, strings.get("music_battle.l10n.client.044"), buttonStyle, f.cancel().w() - 10F, context + ": Cancel");

        // Every footer message is read whole, in at most two lines beside the buttons.
        Map<String, String[]> messages = new LinkedHashMap<>();
        messages.put("screen.music_battle.npc.skin.file.hint", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.shared", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.needed", new String[0]);
        messages.put("screen.music_battle.npc.skin.nickname.needed", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.unreadable", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.too_large", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.not_png", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.wrong_size", new String[]{"1024", "1024"});
        messages.put("screen.music_battle.npc.skin.file.damaged", new String[0]);
        messages.put("screen.music_battle.npc.skin.file.picker_unavailable", new String[0]);
        for (Map.Entry<String, String[]> message : messages.entrySet()) {
            String text = String.format(strings.get(message.getKey()), (Object[]) message.getValue());
            List<String> lines = TextFit.wrap(measurer, text, TextStyle.CAPTION, f.status().w());
            check(lines.size() <= RapGodConfigLayout.STATUS_LINES,
                    context + ": " + message.getKey() + " needs " + lines.size() + " lines in " + f.status().w() + ": " + text);
            float top = RapGodConfigLayout.statusTop(f.status(), lines.size());
            float bottom = top + TextStyle.CAPTION.capHeight() + (lines.size() - 1) * RapGodConfigLayout.statusLineAdvance()
                    + TextFit.DESCENDER_EM * TextStyle.CAPTION.size();
            check(top >= f.footer().y() + 1F - SLACK && bottom <= f.footer().bottom() + SLACK,
                    context + ": " + message.getKey() + " stays in the footer");
        }
    }

    private static void fits(TextMeasurer measurer, String text, TextStyle style, float room, String what) {
        float width = TextFit.width(measurer, text, style);
        check(width <= room + 0.01F, what + " \"" + text + "\" is " + width + " wide in " + room);
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) {
            throw new AssertionError(message);
        }
    }

    /** Lang file strings, with the wording this change proposes for keys the files do not have yet. */
    private static final class Strings {
        private static final Map<String, String[]> PROPOSED = new HashMap<>();

        static {
            proposed("screen.music_battle.npc.title", "Test NPC",
                    "\u0422\u0435\u0441\u0442\u043E\u0432\u044B\u0439 NPC");
            proposed("screen.music_battle.npc.name", "Name above its head",
                    "\u0418\u043C\u044F \u043D\u0430\u0434 \u0433\u043E\u043B\u043E\u0432\u043E\u0439");
            proposed("screen.music_battle.npc.skin", "Skin",
                    "\u0421\u043A\u0438\u043D");
            proposed("screen.music_battle.npc.skin.default", "Default",
                    "\u0412\u0441\u0442\u0440\u043E\u0435\u043D\u043D\u044B\u0439");
            proposed("screen.music_battle.npc.skin.nickname", "By nickname",
                    "\u041F\u043E \u043D\u0438\u043A\u0443");
            proposed("screen.music_battle.npc.skin.file", "From a file",
                    "\u0418\u0437 \u0444\u0430\u0439\u043B\u0430");
            proposed("screen.music_battle.npc.skin.default.note", "The skin the test NPC comes with.",
                    "\u0421\u043A\u0438\u043D, \u0441 \u043A\u043E\u0442\u043E\u0440\u044B\u043C \u043F\u043E\u044F\u0432\u043B\u044F\u0435\u0442\u0441\u044F \u0442\u0435\u0441\u0442\u043E\u0432\u044B\u0439 NPC.");
            proposed("screen.music_battle.npc.skin.nickname.hint", "Player's nickname",
                    "\u041D\u0438\u043A \u0438\u0433\u0440\u043E\u043A\u0430");
            proposed("screen.music_battle.npc.skin.nickname.needed", "Type a player's nickname, or pick another skin.",
                    "\u0412\u0432\u0435\u0434\u0438\u0442\u0435 \u043D\u0438\u043A \u0438\u0433\u0440\u043E\u043A\u0430 \u0438\u043B\u0438 \u0432\u044B\u0431\u0435\u0440\u0438\u0442\u0435 \u0434\u0440\u0443\u0433\u043E\u0439 \u0441\u043A\u0438\u043D.");
            proposed("screen.music_battle.npc.skin.file.choose", "Choose a PNG file\u2026",
                    "\u0412\u044B\u0431\u0440\u0430\u0442\u044C PNG-\u0444\u0430\u0439\u043B\u2026");
            proposed("screen.music_battle.npc.skin.file.current", "Current skin file",
                    "\u0422\u0435\u043A\u0443\u0449\u0438\u0439 \u0444\u0430\u0439\u043B \u0441\u043A\u0438\u043D\u0430");
            proposed("screen.music_battle.npc.skin.file.drop", "or drop it here",
                    "\u0438\u043B\u0438 \u043F\u0435\u0440\u0435\u0442\u0430\u0449\u0438\u0442\u0435 \u0441\u044E\u0434\u0430");
            proposed("screen.music_battle.npc.skin.file.tooltip", "Pick a PNG skin on your computer. You can also drag the file onto the game window.",
                    "\u0412\u044B\u0431\u0435\u0440\u0438\u0442\u0435 PNG-\u0441\u043A\u0438\u043D \u043D\u0430 \u043A\u043E\u043C\u043F\u044C\u044E\u0442\u0435\u0440\u0435. \u0424\u0430\u0439\u043B \u043C\u043E\u0436\u043D\u043E \u0438 \u043F\u0440\u043E\u0441\u0442\u043E \u043F\u0435\u0440\u0435\u0442\u0430\u0449\u0438\u0442\u044C \u0432 \u043E\u043A\u043D\u043E \u0438\u0433\u0440\u044B.");
            proposed("screen.music_battle.npc.skin.file.pick", "Skin for the test NPC",
                    "\u0421\u043A\u0438\u043D \u0434\u043B\u044F \u0442\u0435\u0441\u0442\u043E\u0432\u043E\u0433\u043E NPC");
            proposed("screen.music_battle.npc.skin.file.filter", "PNG images",
                    "\u0418\u0437\u043E\u0431\u0440\u0430\u0436\u0435\u043D\u0438\u044F PNG");
            proposed("screen.music_battle.npc.skin.file.hint", "A 64\u00D764 or 64\u00D732 PNG skin.",
                    "PNG-\u0441\u043A\u0438\u043D 64\u00D764 \u0438\u043B\u0438 64\u00D732.");
            proposed("screen.music_battle.npc.skin.file.shared", "Everyone who sees the NPC will see this skin.",
                    "\u042D\u0442\u043E\u0442 \u0441\u043A\u0438\u043D \u0443\u0432\u0438\u0434\u044F\u0442 \u0432\u0441\u0435, \u043A\u0442\u043E \u0432\u0438\u0434\u0438\u0442 NPC.");
            proposed("screen.music_battle.npc.skin.file.needed", "Choose a PNG file, or pick another skin.",
                    "\u0412\u044B\u0431\u0435\u0440\u0438\u0442\u0435 PNG-\u0444\u0430\u0439\u043B \u0438\u043B\u0438 \u0434\u0440\u0443\u0433\u043E\u0439 \u0441\u043A\u0438\u043D.");
            proposed("screen.music_battle.npc.skin.file.unreadable", "Could not read that file.",
                    "\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u043F\u0440\u043E\u0447\u0438\u0442\u0430\u0442\u044C \u044D\u0442\u043E\u0442 \u0444\u0430\u0439\u043B.");
            proposed("screen.music_battle.npc.skin.file.too_large", "That file is too large to be a skin.",
                    "\u0424\u0430\u0439\u043B \u0441\u043B\u0438\u0448\u043A\u043E\u043C \u0431\u043E\u043B\u044C\u0448\u043E\u0439 \u0434\u043B\u044F \u0441\u043A\u0438\u043D\u0430.");
            proposed("screen.music_battle.npc.skin.file.not_png", "A skin has to be a PNG image.",
                    "\u0421\u043A\u0438\u043D \u0434\u043E\u043B\u0436\u0435\u043D \u0431\u044B\u0442\u044C \u0438\u0437\u043E\u0431\u0440\u0430\u0436\u0435\u043D\u0438\u0435\u043C PNG.");
            proposed("screen.music_battle.npc.skin.file.wrong_size", "A skin is 64\u00D764 or 64\u00D732 pixels; this image is %s\u00D7%s.",
                    "\u0421\u043A\u0438\u043D \u0431\u044B\u0432\u0430\u0435\u0442 64\u00D764 \u0438\u043B\u0438 64\u00D732 \u043F\u0438\u043A\u0441\u0435\u043B\u044F, \u0430 \u044D\u0442\u043E \u0438\u0437\u043E\u0431\u0440\u0430\u0436\u0435\u043D\u0438\u0435 \u2014 %s\u00D7%s.");
            proposed("screen.music_battle.npc.skin.file.damaged", "This PNG is damaged and cannot be used.",
                    "\u042D\u0442\u043E\u0442 PNG \u043F\u043E\u0432\u0440\u0435\u0436\u0434\u0451\u043D, \u0435\u0433\u043E \u043D\u0435\u043B\u044C\u0437\u044F \u0438\u0441\u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u044C.");
            proposed("screen.music_battle.npc.skin.file.picker_unavailable", "The file picker did not open. Drop the PNG onto the game window instead.",
                    "\u041E\u043A\u043D\u043E \u0432\u044B\u0431\u043E\u0440\u0430 \u0444\u0430\u0439\u043B\u0430 \u043D\u0435 \u043E\u0442\u043A\u0440\u044B\u043B\u043E\u0441\u044C. \u041F\u0435\u0440\u0435\u0442\u0430\u0449\u0438\u0442\u0435 PNG \u0432 \u043E\u043A\u043D\u043E \u0438\u0433\u0440\u044B.");
            proposed("screen.music_battle.npc.arms", "Arms",
                    "\u0420\u0443\u043A\u0438");
            proposed("screen.music_battle.npc.arms.classic", "Classic",
                    "\u041E\u0431\u044B\u0447\u043D\u044B\u0435");
            proposed("screen.music_battle.npc.arms.slim", "Slim",
                    "\u0422\u043E\u043D\u043A\u0438\u0435");
            proposed("screen.music_battle.npc.behaviour", "Behaviour",
                    "\u041F\u043E\u0432\u0435\u0434\u0435\u043D\u0438\u0435");
            proposed("screen.music_battle.npc.stationary", "Stands still (AI off)",
                    "\u0421\u0442\u043E\u0438\u0442 \u043D\u0430 \u043C\u0435\u0441\u0442\u0435 (\u0431\u0435\u0437 \u0418\u0418)");
            proposed("screen.music_battle.npc.pushable", "Can be pushed while still",
                    "\u041C\u043E\u0436\u043D\u043E \u0442\u043E\u043B\u043A\u0430\u0442\u044C, \u043F\u043E\u043A\u0430 \u0441\u0442\u043E\u0438\u0442");
            proposed("screen.music_battle.npc.pushable.tooltip", "With its AI off, players and mobs can push the NPC out of their way. Switched off, it stays put and they pass through it. With its AI on it is always pushed like any mob.",
                    "\u0411\u0435\u0437 \u0418\u0418 \u0438\u0433\u0440\u043E\u043A\u0438 \u0438 \u043C\u043E\u0431\u044B \u043C\u043E\u0433\u0443\u0442 \u043E\u0442\u043E\u0434\u0432\u0438\u043D\u0443\u0442\u044C NPC, \u0442\u043E\u043B\u043A\u0430\u044F \u0435\u0433\u043E. \u0415\u0441\u043B\u0438 \u0432\u044B\u043A\u043B\u044E\u0447\u0438\u0442\u044C, \u043E\u043D \u043D\u0435 \u0441\u0434\u0432\u0438\u043D\u0435\u0442\u0441\u044F, \u0430 \u0441\u043A\u0432\u043E\u0437\u044C \u043D\u0435\u0433\u043E \u043C\u043E\u0436\u043D\u043E \u043F\u0440\u043E\u0439\u0442\u0438. \u0421 \u0432\u043A\u043B\u044E\u0447\u0451\u043D\u043D\u044B\u043C \u0418\u0418 \u0435\u0433\u043E \u0442\u043E\u043B\u043A\u0430\u044E\u0442, \u043A\u0430\u043A \u043B\u044E\u0431\u043E\u0433\u043E \u043C\u043E\u0431\u0430.");
            proposed("screen.music_battle.npc.invulnerable", "Invulnerable",
                    "\u041D\u0435\u0443\u044F\u0437\u0432\u0438\u043C\u044B\u0439");
            proposed("screen.music_battle.npc.invulnerable.tooltip", "Nothing hurts the NPC, not even a player in Creative mode. Only /kill and a lost deadly party remove it.",
                    "NPC \u043D\u0438\u0447\u0442\u043E \u043D\u0435 \u0440\u0430\u043D\u0438\u0442, \u0434\u0430\u0436\u0435 \u0438\u0433\u0440\u043E\u043A \u0432 \u0442\u0432\u043E\u0440\u0447\u0435\u0441\u043A\u043E\u043C \u0440\u0435\u0436\u0438\u043C\u0435. \u0423\u0431\u0440\u0430\u0442\u044C \u0435\u0433\u043E \u043C\u043E\u0433\u0443\u0442 \u0442\u043E\u043B\u044C\u043A\u043E /kill \u0438 \u043F\u0440\u043E\u0438\u0433\u0440\u044B\u0448 \u0432 \u0441\u043C\u0435\u0440\u0442\u0435\u043B\u044C\u043D\u043E\u0439 \u0432\u0435\u0447\u0435\u0440\u0438\u043D\u043A\u0435.");
            proposed("screen.music_battle.npc.glowing", "Glowing outline",
                    "\u0421\u0432\u0435\u0442\u044F\u0449\u0438\u0439\u0441\u044F \u043A\u043E\u043D\u0442\u0443\u0440");
            proposed("screen.music_battle.npc.random_votes", "Picks random modes",
                    "\u0412\u044B\u0431\u0438\u0440\u0430\u0435\u0442 \u0441\u043B\u0443\u0447\u0430\u0439\u043D\u044B\u0435 \u0440\u0435\u0436\u0438\u043C\u044B");
            proposed("screen.music_battle.npc.random_votes.tooltip", "In a battle the NPC rolls modes of its own, shared notes and a speed, and adds them to yours. Your choices always stay, and only you can ask for a deadly party.",
                    "\u0412 \u0431\u0438\u0442\u0432\u0435 NPC \u0441\u0430\u043C \u0432\u044B\u0431\u0438\u0440\u0430\u0435\u0442 \u0440\u0435\u0436\u0438\u043C\u044B \u2014 \u043E\u0431\u0449\u0438\u0435 \u043D\u043E\u0442\u044B \u0438 \u0441\u043A\u043E\u0440\u043E\u0441\u0442\u044C \u2014 \u0438 \u0434\u043E\u0431\u0430\u0432\u043B\u044F\u0435\u0442 \u0438\u0445 \u043A \u0432\u0430\u0448\u0438\u043C. \u0412\u0430\u0448 \u0432\u044B\u0431\u043E\u0440 \u0432\u0441\u0435\u0433\u0434\u0430 \u043E\u0441\u0442\u0430\u0451\u0442\u0441\u044F, \u0430 \u0441\u043C\u0435\u0440\u0442\u0435\u043B\u044C\u043D\u0443\u044E \u0432\u0435\u0447\u0435\u0440\u0438\u043D\u043A\u0443 \u043C\u043E\u0436\u0435\u0442\u0435 \u0432\u043A\u043B\u044E\u0447\u0438\u0442\u044C \u0442\u043E\u043B\u044C\u043A\u043E \u0432\u044B.");
        }

        private static void proposed(String key, String english, String russian) {
            PROPOSED.put(key, new String[]{english, russian});
        }

        private final JsonObject lang;
        private final boolean russian;

        Strings(Path root, String language) throws Exception {
            Path file = root.resolve("src/main/resources/assets/music_battle/lang/" + language + ".json");
            lang = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            russian = language.startsWith("ru");
        }

        String get(String key) {
            if (lang.has(key)) {
                return lang.get(key).getAsString();
            }
            String[] proposed = PROPOSED.get(key);
            if (proposed == null) {
                throw new AssertionError("no text for " + key);
            }
            return proposed[russian ? 1 : 0];
        }
    }

    /** Nunito through java.awt, the stand-in the other layout checks use for Minecraft's TTF provider. */
    private static final class Measurer implements TextMeasurer {
        private final Map<String, Font> faces = new HashMap<>();
        private final Map<String, Float> cache = new HashMap<>();
        private final FontRenderContext context = new FontRenderContext(null, true, true);

        Measurer(Path fontDirectory) throws Exception {
            for (TextStyle style : TextStyle.values()) {
                Font base = faces.get(style.fontFile());
                if (base == null) {
                    base = Font.createFont(Font.TRUETYPE_FONT, fontDirectory.resolve(style.fontFile() + ".ttf").toFile());
                    faces.put(style.fontFile(), base);
                }
                faces.put(style.name(), base.deriveFont(style.size()));
            }
        }

        @Override
        public float width(String text, TextStyle style) {
            if (text == null || text.isEmpty()) {
                return 0F;
            }
            String key = style.name() + "\u0000" + text;
            Float cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            float width = (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
            cache.put(key, width);
            return width;
        }
    }
}
