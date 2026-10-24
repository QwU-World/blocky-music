package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextField;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.Form;
import ru.gora.musicbattle.client.workshop.layout.FormLayout;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * Draws a {@link Form} (the account view and the upload wizard) from {@link FormLayout}: one renderer for headings,
 * paragraphs, notes, fields, toggles, choices, rows, buttons, stage rows, the device code, sliders and avatars, with
 * its own scrolling, text fields and click routing. Render thread only.
 */
public final class FormView {
    /** Called with the id of the button, toggle, choice or slider the player used. */
    @FunctionalInterface
    public interface Action {
        void onAction(String id, double relativeX);
    }

    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Motion.SmoothScroll scroll = new Motion.SmoothScroll();
    private final Action action;
    private List<Form.Item> items = List.of();
    private Layout layout;
    private Rect area = Rect.EMPTY;
    private ImageSource images = id -> null;

    /** Supplies a texture for {@link Form.Avatar} items (the qwu avatar, a Minecraft head). */
    @FunctionalInterface
    public interface ImageSource {
        net.minecraft.resources.ResourceLocation texture(String id);
    }

    public FormView(Action action) {
        this.action = action;
    }

    public void setImages(ImageSource source) {
        images = source == null ? id -> null : source;
    }

    /** New items; text fields keep the text the player has typed. */
    public void setItems(List<Form.Item> value) {
        items = value == null ? List.of() : value;
        List<String> alive = new ArrayList<>();
        for (Form.Item item : items) {
            if (item instanceof Form.Field field) {
                alive.add(field.id());
                TextField existing = fields.get(field.id());
                if (existing == null) {
                    TextField created = field.lines() > 1 ? TextField.multiLine(5000) : TextField.singleLine();
                    created.setMaxLength(field.lines() > 1 ? 5000 : 200);
                    created.setValue(field.value());
                    created.setPlaceholder(field.placeholder());
                    fields.put(field.id(), created);
                } else {
                    existing.setPlaceholder(field.placeholder());
                }
            }
        }
        fields.keySet().removeIf(id -> !alive.contains(id));
        relayout();
    }

    public List<Form.Item> items() {
        return items;
    }

    public TextField field(String id) {
        return fields.get(id);
    }

    public String value(String id) {
        TextField field = fields.get(id);
        return field == null ? "" : field.value();
    }

    public void setArea(Rect value) {
        area = value;
        relayout();
    }

    public Rect area() {
        return area;
    }

    public Layout layout() {
        return layout;
    }

    public void relayout() {
        if (area.empty()) {
            return;
        }
        layout = FormLayout.compute(area, ru.gora.musicbattle.client.ui.TextStyles.measurer(), WorkshopTr.INSTANCE, items);
        scroll.setExtent(Math.max(0, layout.contentHeight() - area.h()));
        for (Form.Item item : items) {
            if (item instanceof Form.Field field) {
                TextField box = fields.get(field.id());
                Rect rect = layout.rect(field.id());
                if (box != null && !rect.empty()) {
                    box.setBounds(area.x() + rect.x(), area.y() + rect.y(), rect.w(), rect.h());
                }
            }
        }
    }

    public float contentHeight() {
        return layout == null ? 0F : layout.contentHeight();
    }

    public void render(GuiGraphics g, double mouseX, double mouseY) {
        if (layout == null) {
            return;
        }
        scroll.update();
        float offset = scroll.snapped(ru.gora.musicbattle.client.ui.TextStyles.guiScale());
        UiClip.push(g, area.x(), area.y(), area.right(), area.bottom());
        for (Form.Item item : items) {
            renderItem(g, item, offset, mouseX, mouseY);
        }
        UiClip.pop(g);
        WorkshopDraw.scrollbar(g, area, offset, layout.contentHeight(), false);
        // Fields are drawn last so their caret is above the panel; they carry their own absolute bounds.
        for (Form.Item item : items) {
            if (item instanceof Form.Field field) {
                TextField box = fields.get(field.id());
                Rect rect = layout.rect(field.id());
                if (box != null && !rect.empty()) {
                    box.setBounds(area.x() + rect.x(), area.y() + rect.y() - offset, rect.w(), rect.h());
                    if (rect.y() - offset + rect.h() > 0 && rect.y() - offset < area.h()) {
                        UiClip.push(g, area.x(), area.y(), area.right(), area.bottom());
                        box.render(g, mouseX, mouseY);
                        UiClip.pop(g);
                    }
                }
            }
        }
    }

    private void renderItem(GuiGraphics g, Form.Item item, float offset, double mouseX, double mouseY) {
        String id = item.id();
        if (item instanceof Form.Heading) {
            drawParagraph(g, id, offset, Palette.TEXT_1);
        } else if (item instanceof Form.Paragraph) {
            drawParagraph(g, id, offset, Palette.TEXT_2);
        } else if (item instanceof Form.Note note) {
            drawParagraph(g, id, offset, WorkshopDraw.toneColour(note.tone()));
        } else if (item instanceof Form.Field) {
            // Drawn after the pass, with the caret.
        } else if (item instanceof Form.Toggle toggle) {
            Rect row = shifted(layout.rect(id), offset);
            boolean hovered = toggle.enabled() && WorkshopDraw.hot(row, mouseX, mouseY);
            WorkshopDraw.check(g, shifted(layout.rect(id + ".box"), offset), toggle.on(), false, toggle.enabled());
            drawParagraph(g, id + ".label", offset, toggle.enabled()
                    ? (hovered ? Palette.TEXT_1 : Palette.TEXT_2) : Palette.TEXT_MUTE);
        } else if (item instanceof Form.Choice choice) {
            Rect row = shifted(layout.rect(id), offset);
            boolean hovered = choice.enabled() && WorkshopDraw.hot(row, mouseX, mouseY);
            WorkshopDraw.check(g, shifted(layout.rect(id + ".box"), offset), choice.selected(), true, choice.enabled());
            drawParagraph(g, id + ".label", offset, choice.enabled()
                    ? (hovered || choice.selected() ? Palette.TEXT_1 : Palette.TEXT_2) : Palette.TEXT_MUTE);
            drawParagraph(g, id + ".detail", offset, Palette.TEXT_MUTE);
        } else if (item instanceof Form.Row) {
            drawText(g, id + ".label", offset, Palette.TEXT_3);
            drawText(g, id + ".value", offset, Palette.TEXT_1);
        } else if (item instanceof Form.Buttons buttons) {
            for (Form.Button button : buttons.buttons()) {
                Rect rect = shifted(layout.rect(button.id()), offset);
                boolean hovered = button.enabled() && WorkshopDraw.hot(rect, mouseX, mouseY);
                WorkshopDraw.button(g, rect, button.danger() ? WorkshopDraw.ButtonStyle.DANGER
                        : button.primary() ? WorkshopDraw.ButtonStyle.PRIMARY : WorkshopDraw.ButtonStyle.SECONDARY, hovered,
                        button.enabled());
                drawText(g, button.id() + ".text", offset, button.enabled() ? Palette.TEXT_1 : Palette.TEXT_MUTE);
            }
        } else if (item instanceof Form.Stage stage) {
            Rect icon = shifted(layout.rect(id + ".icon"), offset);
            int colour = switch (stage.state()) {
                case DONE -> Palette.OK;
                case FAILED -> Palette.DANGER;
                case CANCELLED -> Palette.TEXT_MUTE;
                case RUNNING -> Palette.ACCENT;
                default -> Palette.TEXT_MUTE;
            };
            switch (stage.state()) {
                case RUNNING -> WorkshopDraw.spinner(g, icon.centerX(), icon.centerY(), icon.w() * 0.5F, colour);
                case DONE -> WorkshopDraw.icon(g, icon, UiIcon.CHECK, colour);
                case FAILED -> WorkshopDraw.icon(g, icon, UiIcon.ALERT, colour);
                case CANCELLED -> WorkshopDraw.icon(g, icon, UiIcon.X, colour);
                default -> WorkshopDraw.icon(g, icon, UiIcon.LOADER, Palette.fade(Palette.TEXT_MUTE, 0.6F));
            }
            drawText(g, id + ".label", offset, stage.state() == Form.StageState.PENDING ? Palette.TEXT_MUTE : Palette.TEXT_1);
            if (stage.state() == Form.StageState.RUNNING && stage.fraction() >= 0) {
                WorkshopDraw.bar(g, shifted(layout.rect(id + ".bar"), offset), stage.fraction(), Palette.BG_3, Palette.ACCENT);
            }
            drawParagraph(g, id + ".detail", offset, Palette.TEXT_MUTE);
        } else if (item instanceof Form.Code) {
            Rect rect = shifted(layout.rect(id), offset);
            WorkshopDraw.panel(g, rect, Palette.RADIUS_MD, Palette.BG_2);
            drawText(g, id + ".text", offset, Palette.ACCENT);
        } else if (item instanceof Form.Slider slider) {
            drawText(g, id + ".label", offset, Palette.TEXT_3);
            drawText(g, id + ".value", offset, Palette.TEXT_1);
            Rect rect = shifted(layout.rect(id), offset);
            WorkshopDraw.hot(rect, mouseX, mouseY);
            WorkshopDraw.bar(g, rect, slider.fraction(), Palette.BG_3, Palette.ACCENT_STRONG);
        } else if (item instanceof Form.Avatar) {
            Rect rect = shifted(layout.rect(id), offset);
            net.minecraft.resources.ResourceLocation texture = images.texture(id);
            if (texture != null) {
                ru.gora.musicbattle.client.ui.RoundedImage.draw(g, texture, rect.x(), rect.y(), rect.w(), rect.h(),
                        rect.w() * 0.5F, 0F, 1F);
            } else {
                WorkshopDraw.panel(g, rect, rect.w() * 0.5F, Palette.BG_3);
                WorkshopDraw.icon(g, rect.inset(3, 3), UiIcon.USER_ROUND, Palette.TEXT_3);
            }
            drawText(g, id + ".name", offset, Palette.TEXT_1);
            drawText(g, id + ".detail", offset, Palette.TEXT_MUTE);
        } else if (item instanceof Form.Profile profile) {
            Rect card = shifted(layout.rect(id), offset);
            WorkshopDraw.panel(g, card, Palette.RADIUS_MD, Palette.BG_2);
            if (profile.banner()) {
                // The person's most downloaded cover, held well back: it gives the card a place of its own without
                // competing with the name in front of it.
                net.minecraft.resources.ResourceLocation banner = images.texture(id + ".banner");
                if (banner != null) {
                    Rect strip = new Rect(card.x(), card.y(), card.w(), Math.min(card.h(), card.h() * 0.62F));
                    ru.gora.musicbattle.client.ui.RoundedImage.draw(g, banner, strip.x(), strip.y(), strip.w(), strip.h(),
                            0, 0, 1, 1, Palette.RADIUS_MD, Palette.RADIUS_MD, 0, 0, 0.55F,
                            Palette.withAlpha(0xFFFFFF, 0.5F), Palette.withAlpha(0xFFFFFF, 0.14F));
                }
            }
            Rect face = shifted(layout.rect(id + ".avatar"), offset);
            net.minecraft.resources.ResourceLocation texture = images.texture(id + ".avatar");
            if (texture != null) {
                ru.gora.musicbattle.client.ui.RoundedImage.draw(g, texture, face.x(), face.y(), face.w(), face.h(),
                        face.w() * 0.5F, 0F, 1F);
            } else {
                WorkshopDraw.panel(g, face, face.w() * 0.5F, Palette.BG_3);
                WorkshopDraw.icon(g, face.inset(face.w() * 0.28F, face.h() * 0.28F), UiIcon.USER_ROUND, Palette.TEXT_3);
            }
            WorkshopDraw.outline(g, face, face.w() * 0.5F, 1F, Palette.fade(Palette.ACCENT, 0.7F));
            drawText(g, id + ".name", offset, Palette.TEXT_1);
            drawText(g, id + ".handle", offset, Palette.TEXT_MUTE);
            for (int i = 0; i < profile.chips().size(); i++) {
                Rect chip = shifted(layout.rect(id + ".chip." + i), offset);
                WorkshopDraw.pill(g, chip, chipColour(profile.chips().get(i).key()));
                drawText(g, id + ".chip." + i + ".text", offset, Palette.ON_PILL);
            }
            drawText(g, id + ".since", offset, Palette.TEXT_MUTE);
            if (!profile.stats().isEmpty()) {
                Rect stats = shifted(layout.rect(id + ".stats"), offset);
                WorkshopDraw.panel(g, new Rect(stats.x(), stats.y(), stats.w(), 1), 0, Palette.LINE);
                for (int i = 1; i < profile.stats().size(); i++) {
                    float x = stats.x() + stats.w() / profile.stats().size() * i;
                    WorkshopDraw.panel(g, new Rect(x, stats.y() + 3, 1, stats.h() - 6), 0, Palette.fade(Palette.LINE, 0.7F));
                }
                for (int i = 0; i < profile.stats().size(); i++) {
                    drawText(g, id + ".stat." + i + ".value", offset, Palette.TEXT_1);
                    drawText(g, id + ".stat." + i + ".label", offset, Palette.TEXT_MUTE);
                }
            }
        } else if (item instanceof Form.Separator) {
            WorkshopDraw.panel(g, shifted(layout.rect(id), offset), 0, Palette.LINE);
        }
    }

    /**
     * Staff and curator chips take the colours those roles already have elsewhere in the Workshop. The key comes from
     * the server, never the translated label, so it means the same in every language.
     */
    private static int chipColour(String key) {
        return switch (key == null ? "" : key) {
            case "admin" -> Palette.DANGER;
            case "mod" -> Palette.ST_PENDING;
            case "curator" -> Palette.ST_FEATURED;
            default -> Palette.ACCENT;
        };
    }

    private void drawText(GuiGraphics g, String id, float offset, int colour) {
        Layout.Box box = layout.box(id);
        if (box == null || box.text() == null || box.text().isEmpty()) {
            return;
        }
        Rect rect = shifted(box.rect(), offset);
        if (rect.bottom() < area.y() || rect.y() > area.bottom()) {
            return;
        }
        ru.gora.musicbattle.client.ui.TextStyles.queue(g,
                ru.gora.musicbattle.client.ui.TextStyles.text(box.text(), box.style() == null ? TextStyle.BODY : box.style())
                        .getVisualOrderText(), rect.x(), rect.y(), colour);
    }

    private void drawParagraph(GuiGraphics g, String id, float offset, int colour) {
        for (Layout.Box box : layout.group(id + ".")) {
            if (box.text() == null) {
                continue;
            }
            Rect rect = shifted(box.rect(), offset);
            if (rect.bottom() < area.y() || rect.y() > area.bottom()) {
                continue;
            }
            ru.gora.musicbattle.client.ui.TextStyles.queue(g,
                    ru.gora.musicbattle.client.ui.TextStyles.text(box.text(), box.style() == null ? TextStyle.BODY : box.style())
                            .getVisualOrderText(), rect.x(), rect.y(), colour);
        }
    }

    /** Layout coordinates are relative to the form; this puts them on the screen at the current scroll. */
    private Rect shifted(Rect rect, float offset) {
        return rect.empty() ? rect : new Rect(area.x() + rect.x(), area.y() + rect.y() - offset, rect.w(), rect.h());
    }

    // ---------------------------------------------------------------- input

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (layout == null || !area.contains(mouseX, mouseY)) {
            for (TextField field : fields.values()) {
                field.setFocused(false);
            }
            return false;
        }
        float offset = scroll.snapped(ru.gora.musicbattle.client.ui.TextStyles.guiScale());
        boolean handled = false;
        for (TextField field : fields.values()) {
            handled |= field.mouseClicked(mouseX, mouseY, button);
        }
        if (handled) {
            return true;
        }
        for (Form.Item item : items) {
            String id = item.id();
            if (item instanceof Form.Buttons buttons) {
                for (Form.Button entry : buttons.buttons()) {
                    if (entry.enabled() && shifted(layout.rect(entry.id()), offset).contains(mouseX, mouseY)) {
                        action.onAction(entry.id(), 0);
                        return true;
                    }
                }
            } else if (item instanceof Form.Toggle toggle) {
                if (toggle.enabled() && shifted(layout.rect(id), offset).contains(mouseX, mouseY)) {
                    action.onAction(id, 0);
                    return true;
                }
            } else if (item instanceof Form.Choice choice) {
                if (choice.enabled() && shifted(layout.rect(id), offset).contains(mouseX, mouseY)) {
                    action.onAction(id, 0);
                    return true;
                }
            } else if (item instanceof Form.Slider) {
                Rect rect = shifted(layout.rect(id), offset);
                if (rect.contains(mouseX, mouseY)) {
                    action.onAction(id, (mouseX - rect.x()) / Math.max(1, rect.w()));
                    return true;
                }
            }
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (layout == null) {
            return false;
        }
        float offset = scroll.snapped(ru.gora.musicbattle.client.ui.TextStyles.guiScale());
        for (Form.Item item : items) {
            if (item instanceof Form.Slider) {
                Rect rect = shifted(layout.rect(item.id()), offset);
                if (mouseY >= rect.y() - 4 && mouseY <= rect.bottom() + 4 && area.contains(mouseX, mouseY)) {
                    action.onAction(item.id(), (mouseX - rect.x()) / Math.max(1, rect.w()));
                    return true;
                }
            }
        }
        for (TextField field : fields.values()) {
            if (field.mouseDragged(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (layout == null || !area.contains(mouseX, mouseY) || scroll.extent() <= 0) {
            return false;
        }
        scroll.onWheel(amount);
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (TextField field : fields.values()) {
            if (field.focused() && field.keyPressed(keyCode, scanCode, modifiers)) {
                relayoutFields();
                return true;
            }
        }
        return false;
    }

    public boolean charTyped(char character, int modifiers) {
        for (TextField field : fields.values()) {
            if (field.focused() && field.charTyped(character, modifiers)) {
                relayoutFields();
                return true;
            }
        }
        return false;
    }

    public boolean anyFieldFocused() {
        for (TextField field : fields.values()) {
            if (field.focused()) {
                return true;
            }
        }
        return false;
    }

    private void relayoutFields() {
        // Multi-line fields can grow; the form is measured again so nothing below them is covered.
        relayout();
    }
}
