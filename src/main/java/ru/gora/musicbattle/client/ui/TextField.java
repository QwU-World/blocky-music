package ru.gora.musicbattle.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

/**
 * The Workshop's own text field: TrueType text in a {@link TextStyle}, a caret, a selection, placeholder text,
 * clipboard keys and multi-line editing. Vanilla's {@code EditBox} measures everything with the bitmap font and
 * cannot draw our fonts, so the Workshop brings its own.
 *
 * <p>The owning screen positions it, renders it and forwards {@code charTyped}, {@code keyPressed} and mouse events.
 * Render thread only.
 */
public final class TextField {
    public static final int DEFAULT_MAX_LENGTH = 200;
    private static final long CARET_BLINK_NANOS = 500_000_000L;

    private final TextStyle style;
    private String value = "";
    private String placeholder = "";
    private int caret;
    private int anchor;
    private int maxLength = DEFAULT_MAX_LENGTH;
    private boolean multiline;
    private boolean focused;
    private boolean editable = true;
    private Runnable onChange;
    private Runnable onSubmit;
    private float x;
    private float y;
    private float width;
    private float height;
    private float scrollX;
    private float scrollY;
    private long focusNanos;
    private List<String> lines = List.of("");
    private boolean linesValid;
    private float linesWidth;

    public TextField(TextStyle style) {
        this.style = style;
    }

    public static TextField singleLine() {
        return new TextField(TextStyle.BODY);
    }

    public static TextField multiLine(int maxLength) {
        TextField field = new TextField(TextStyle.BODY);
        field.multiline = true;
        field.maxLength = maxLength;
        return field;
    }

    public void setBounds(float x, float y, float width, float height) {
        if (this.width != width) {
            linesValid = false;
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public String value() {
        return value;
    }

    public void setValue(String text) {
        String clean = StringUtil.filterText(text == null ? "" : text, multiline);
        if (clean.length() > maxLength) {
            clean = clean.substring(0, maxLength);
        }
        if (!clean.equals(value)) {
            value = clean;
            caret = Math.min(caret, value.length());
            anchor = caret;
            linesValid = false;
            fireChange();
        }
    }

    public void setPlaceholder(String text) {
        placeholder = text == null ? "" : text;
    }

    public void setMaxLength(int value) {
        maxLength = Math.max(1, value);
    }

    public void setEditable(boolean value) {
        editable = value;
    }

    public boolean editable() {
        return editable;
    }

    public void setOnChange(Runnable action) {
        onChange = action;
    }

    /** Enter on a single-line field, Ctrl/Cmd+Enter on a multi-line one. */
    public void setOnSubmit(Runnable action) {
        onSubmit = action;
    }

    public boolean focused() {
        return focused;
    }

    public void setFocused(boolean value) {
        if (focused != value) {
            focused = value;
            focusNanos = Util.getNanos();
            if (value) {
                HandCursor.request(HandCursor.Kind.IBEAM);
            }
        }
    }

    public int caret() {
        return caret;
    }

    public void moveCaretToEnd() {
        caret = value.length();
        anchor = caret;
    }

    public void clear() {
        setValue("");
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Lines of a multi-line field, wrapped to the field width. */
    public List<String> lines() {
        ensureLines();
        return lines;
    }

    public float lineHeight() {
        return style.capHeight() + 0.42F * style.size();
    }

    /** The height this field needs for its current text (multi-line fields grow). */
    public float preferredHeight(float minHeight, float maxHeight) {
        ensureLines();
        float needed = lines.size() * lineHeight() + 6;
        return Math.max(minHeight, Math.min(maxHeight, needed));
    }

    // ---------------------------------------------------------------- rendering

    public void render(GuiGraphics g, double mouseX, double mouseY) {
        boolean hovered = contains(mouseX, mouseY);
        if (hovered && editable) {
            HandCursor.request(HandCursor.Kind.IBEAM);
        }
        int background = focused ? Palette.BG_2 : Palette.BG_3;
        RoundedRect.fill(g, x, y, width, height, Palette.RADIUS_SM, editable ? background : Palette.fade(background, 0.6F));
        if (focused) {
            RoundedRect.stroke(g, x, y, width, height, Palette.RADIUS_SM, 1.0F, Palette.ACCENT);
        }
        float padX = 4;
        float innerWidth = Math.max(4, width - padX * 2);
        UiClip.push(g, x + 1, y + 1, x + width - 1, y + height - 1);
        if (value.isEmpty() && !placeholder.isEmpty()) {
            TextStyles.queue(g, TextStyles.text(placeholder, style).getVisualOrderText(), x + padX,
                    multiline ? y + 3 : TextStyles.centeredCapY(style, y, height), Palette.TEXT_MUTE);
        } else if (multiline) {
            renderMultiline(g, padX, innerWidth);
        } else {
            renderSingleLine(g, padX, innerWidth);
        }
        UiClip.pop(g);
    }

    private void renderSingleLine(GuiGraphics g, float padX, float innerWidth) {
        float caretX = measure(value.substring(0, caret));
        if (caretX - scrollX > innerWidth) {
            scrollX = caretX - innerWidth;
        }
        if (caretX - scrollX < 0) {
            scrollX = caretX;
        }
        float total = measure(value);
        if (total - scrollX < innerWidth) {
            scrollX = Math.max(0, total - innerWidth);
        }
        float textY = TextStyles.centeredCapY(style, y, height);
        drawSelection(g, 0, x + padX - scrollX, textY);
        TextStyles.queue(g, TextStyles.text(value, style).getVisualOrderText(), x + padX - scrollX, textY, Palette.TEXT_1);
        drawCaret(g, x + padX - scrollX + caretX, textY);
    }

    private void renderMultiline(GuiGraphics g, float padX, float innerWidth) {
        ensureLines();
        float step = lineHeight();
        int caretLine = lineOf(caret);
        float visible = Math.max(step, height - 6);
        float caretTop = caretLine * step;
        if (caretTop - scrollY + step > visible) {
            scrollY = caretTop + step - visible;
        }
        if (caretTop - scrollY < 0) {
            scrollY = caretTop;
        }
        scrollY = Math.max(0, Math.min(scrollY, Math.max(0, lines.size() * step - visible)));
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            float lineY = y + 3 + i * step - scrollY;
            if (lineY + step >= y && lineY <= y + height) {
                TextStyles.queue(g, TextStyles.text(lines.get(i), style).getVisualOrderText(), x + padX, lineY, Palette.TEXT_1);
                if (focused && caretLine == i) {
                    float caretX = measure(lines.get(i).substring(0, Math.max(0, Math.min(lines.get(i).length(), caret - start))));
                    drawCaret(g, x + padX + caretX, lineY);
                }
            }
            start += lines.get(i).length();
        }
    }

    private void drawSelection(GuiGraphics g, int lineStart, float textX, float textY) {
        if (!focused || anchor == caret) {
            return;
        }
        int from = Math.min(anchor, caret) - lineStart;
        int to = Math.max(anchor, caret) - lineStart;
        from = Math.max(0, Math.min(value.length(), from));
        to = Math.max(0, Math.min(value.length(), to));
        if (from >= to) {
            return;
        }
        float left = textX + measure(value.substring(0, from));
        float right = textX + measure(value.substring(0, to));
        RoundedRect.fill(g, left, textY - 1, right - left, style.capHeight() + 3, 0, Palette.withAlpha(Palette.ACCENT_DEEP, 0.55F));
    }

    private void drawCaret(GuiGraphics g, float caretX, float textY) {
        if (!focused || !editable) {
            return;
        }
        long elapsed = Util.getNanos() - focusNanos;
        if ((elapsed / CARET_BLINK_NANOS) % 2 == 1) {
            return;
        }
        RoundedRect.fill(g, caretX, textY - 1.5F, 1.0F, style.capHeight() + 3, 0, Palette.TEXT_1);
    }

    // ---------------------------------------------------------------- input

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        boolean inside = contains(mouseX, mouseY);
        setFocused(inside && editable);
        if (!inside) {
            return false;
        }
        caret = positionAt(mouseX, mouseY);
        anchor = caret;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!focused || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        caret = positionAt(mouseX, mouseY);
        return true;
    }

    public boolean charTyped(char character, int modifiers) {
        if (!focused || !editable || !StringUtil.isAllowedChatCharacter(character)) {
            return false;
        }
        insert(String.valueOf(character));
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !editable) {
            return false;
        }
        if (Screen.isSelectAll(keyCode)) {
            anchor = 0;
            caret = value.length();
            return true;
        }
        if (Screen.isCopy(keyCode)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(selection());
            return true;
        }
        if (Screen.isPaste(keyCode)) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard());
            return true;
        }
        if (Screen.isCut(keyCode)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(selection());
            insert("");
            return true;
        }
        boolean shift = Screen.hasShiftDown();
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (anchor == caret && caret > 0) {
                    anchor = caret - 1;
                }
                insert("");
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (anchor == caret && caret < value.length()) {
                    anchor = caret + 1;
                }
                insert("");
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                move(Screen.hasControlDown() ? previousWord() : Math.max(0, caret - 1), shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                move(Screen.hasControlDown() ? nextWord() : Math.min(value.length(), caret + 1), shift);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                move(lineStart(caret), shift);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                move(lineEnd(caret), shift);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (multiline) {
                    move(verticalStep(-1), shift);
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (multiline) {
                    move(verticalStep(1), shift);
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                boolean commit = !multiline || Screen.hasControlDown() || Screen.hasShiftDown();
                if (commit) {
                    if (onSubmit != null) {
                        onSubmit.run();
                    }
                    return true;
                }
                insert("\n");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Replaces the selection (or inserts at the caret) with {@code text}. */
    public void insert(String text) {
        String clean = StringUtil.filterText(text == null ? "" : text, multiline);
        int from = Math.min(anchor, caret);
        int to = Math.max(anchor, caret);
        int room = maxLength - (value.length() - (to - from));
        if (room <= 0 && !clean.isEmpty()) {
            return;
        }
        if (clean.length() > room) {
            clean = clean.substring(0, Math.max(0, room));
        }
        value = value.substring(0, from) + clean + value.substring(to);
        caret = from + clean.length();
        anchor = caret;
        linesValid = false;
        focusNanos = Util.getNanos();
        fireChange();
    }

    public String selection() {
        int from = Math.min(anchor, caret);
        int to = Math.max(anchor, caret);
        return value.substring(from, to);
    }

    private void move(int position, boolean keepSelection) {
        caret = Math.max(0, Math.min(value.length(), position));
        if (!keepSelection) {
            anchor = caret;
        }
        focusNanos = Util.getNanos();
    }

    private int previousWord() {
        int index = Math.max(0, caret - 1);
        while (index > 0 && value.charAt(index - 1) != ' ') {
            index--;
        }
        return index;
    }

    private int nextWord() {
        int index = Math.min(value.length(), caret + 1);
        while (index < value.length() && value.charAt(index) != ' ') {
            index++;
        }
        return index;
    }

    private int lineStart(int position) {
        if (!multiline) {
            return 0;
        }
        int index = value.lastIndexOf('\n', Math.max(0, position - 1));
        return index < 0 ? 0 : index + 1;
    }

    private int lineEnd(int position) {
        if (!multiline) {
            return value.length();
        }
        int index = value.indexOf('\n', position);
        return index < 0 ? value.length() : index;
    }

    private int verticalStep(int direction) {
        ensureLines();
        int line = lineOf(caret);
        int target = Math.max(0, Math.min(lines.size() - 1, line + direction));
        if (target == line) {
            return caret;
        }
        int offset = caret - lineStartIndex(line);
        return Math.min(lineStartIndex(target) + offset, lineStartIndex(target) + lines.get(target).length());
    }

    private int lineStartIndex(int line) {
        ensureLines();
        int index = 0;
        for (int i = 0; i < line && i < lines.size(); i++) {
            index += lines.get(i).length();
        }
        return index;
    }

    private int lineOf(int position) {
        ensureLines();
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            index += lines.get(i).length();
            if (position < index) {
                return i;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    private int positionAt(double mouseX, double mouseY) {
        float padX = 4;
        if (!multiline) {
            return indexAt(value, (float) (mouseX - x - padX + scrollX));
        }
        ensureLines();
        int line = (int) Math.floor((mouseY - y - 3 + scrollY) / lineHeight());
        line = Math.max(0, Math.min(lines.size() - 1, line));
        return lineStartIndex(line) + indexAt(lines.get(line), (float) (mouseX - x - padX));
    }

    private int indexAt(String text, float offset) {
        if (offset <= 0) {
            return 0;
        }
        for (int i = 1; i <= text.length(); i++) {
            if (measure(text.substring(0, i)) > offset) {
                return i - 1;
            }
        }
        return text.length();
    }

    private float measure(String text) {
        return text.isEmpty() ? 0F : TextStyles.width(TextStyles.text(text, style));
    }

    private void fireChange() {
        if (onChange != null) {
            onChange.run();
        }
    }

    /** Wraps the value for a multi-line field; the lines keep their own newline characters. */
    private void ensureLines() {
        if (!multiline) {
            lines = List.of(value);
            return;
        }
        if (linesValid && linesWidth == width) {
            return;
        }
        float room = Math.max(8, width - 8);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            line.append(c);
            boolean newline = c == '\n';
            if (newline || measure(line.toString()) > room) {
                if (!newline && line.length() > 1) {
                    int space = line.lastIndexOf(" ");
                    if (space > 0 && space < line.length() - 1) {
                        out.add(line.substring(0, space + 1));
                        String rest = line.substring(space + 1);
                        line.setLength(0);
                        line.append(rest);
                        continue;
                    }
                    out.add(line.substring(0, line.length() - 1));
                    String rest = String.valueOf(c);
                    line.setLength(0);
                    line.append(rest);
                    continue;
                }
                out.add(line.toString());
                line.setLength(0);
            }
        }
        out.add(line.toString());
        lines = List.copyOf(out);
        linesValid = true;
        linesWidth = width;
    }
}
