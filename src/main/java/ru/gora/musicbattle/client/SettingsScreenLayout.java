package ru.gora.musicbattle.client;

/** Non-overlapping viewports shared by rendering, scrolling, and pointer routing. */
record SettingsScreenLayout(SettingsScreenLayout.Rect controls, SettingsScreenLayout.Rect preview) {
    static int headerHeight(int width) { return width >= 620 ? 32 : 58; }

    /** General, Calibration, Track editor, Guide, Workshop. */
    static final int NAVIGATION_BUTTONS = 5;

    static Rect navigationButton(int width, int index) {
        // One row of five on wide screens, two rows of three below 620 units (the header is sized for two rows).
        int columns = width >= 620 ? NAVIGATION_BUTTONS : 3;
        int buttonWidth = Math.min(150, (width - 24 - (columns - 1) * 4) / columns);
        int left = (width - columns * buttonWidth - (columns - 1) * 4) / 2;
        return new Rect(left + index % columns * (buttonWidth + 4), 7 + index / columns * 26, buttonWidth, 20);
    }

    static SettingsScreenLayout create(int width, int height) {
        int top = headerHeight(width) + 4;
        int bottom = Math.max(top + 2, height - 40);
        int bodyHeight = bottom - top;
        if (width >= 620) {
            int controlsWidth = Math.min(360, (width - 44) / 2);
            int left = Math.max(12, (width - controlsWidth - 340 - 24) / 2);
            int previewX = left + controlsWidth + 20;
            return new SettingsScreenLayout(new Rect(left, top, controlsWidth, bodyHeight),
                    new Rect(previewX, top, Math.min(340, width - previewX - 12), bodyHeight));
        }
        int contentWidth = Math.max(1, Math.min(360, width - 24));
        int left = (width - contentWidth) / 2;
        // Let the settings list absorb the extra navigation row, not the note preview.
        int previewHeight = Math.min(148, Math.max(48, (bodyHeight + 26) * 2 / 5));
        int controlsTop = top + previewHeight + 8;
        return new SettingsScreenLayout(new Rect(left, controlsTop, contentWidth, Math.max(1, bottom - controlsTop)),
                new Rect(left, top, contentWidth, previewHeight));
    }

    record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }
}
