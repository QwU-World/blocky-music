package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.data.NoteAppearance;

public final class MusicBattleSettingsScreen extends Screen {
    private static final int ROW = 26;
    private static final int CONTENT_HEIGHT = ROW * 12;
    private static final String[] LANE_KEYS = {"left", "down", "up", "right"};
    private final Screen parent;
    private final Button[] keyButtons = new Button[4];
    private final List<Control> controls = new ArrayList<>();
    private final long animationStart = System.nanoTime();
    private SettingsScreenLayout layout;
    private int capturingKey = -1;
    private double scroll;
    private NoteAppearance previewAppearance;
    private Integer previewPanelColor;
    private record Control(AbstractWidget widget, int contentY) { }

    public MusicBattleSettingsScreen(Screen parent) {
        super(Component.translatable("music_battle.options"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        controls.clear();
        previewAppearance = null;
        previewPanelColor = null;
        layout = SettingsScreenLayout.create(width, height);
        Button general = addNavigationButton(0, "music_battle.calibration.general", button -> { });
        general.active = false;
        addNavigationButton(1, "music_battle.calibration.title", button -> minecraft.setScreen(new MusicBattleCalibrationScreen(this)));
        addNavigationButton(2, "music_battle.editor", button -> minecraft.setScreen(new TrackLibraryScreen(this)));
        addNavigationButton(3, "music_battle.settings.guide", button -> minecraft.setScreen(new MusicBattleGuideScreen(this)));
        addNavigationButton(4, "screen.music_battle.workshop.nav", button -> ru.gora.musicbattle.client.workshop.WorkshopClient.open(this));
        addRenderableWidget(FancyButton.create(CommonComponents.GUI_DONE, button -> onClose(), width / 2 - 70, height - 30, 140, 20));
        int x = layout.controls().x();
        int panelWidth = layout.controls().width() - 6;
        int half = (panelWidth - 6) / 2;
        addControl(new RangeSlider(x, panelWidth, "note_speed", ClientBattleSettings.noteSpeed(), .5F, 20F, false,
                ClientBattleSettings::setNoteSpeed), 0);
        addControl(new RangeSlider(x, panelWidth, "music_volume", ClientBattleSettings.musicVolume(), 0F, 2F, true,
                ClientBattleSettings::setMusicVolume), ROW);
        addControl(FancyButton.create(spectatorEffectsLabel(), button -> {
            ClientBattleSettings.setSpectatorEffects(!ClientBattleSettings.spectatorEffects());
            button.setMessage(spectatorEffectsLabel());
        }, x, 0, panelWidth, 20), ROW * 2);
        for (int lane = 0; lane < 4; lane++) {
            int index = lane;
            keyButtons[lane] = addControl(FancyButton.create(keyLabel(lane), button -> {
                capturingKey = index;
                button.setMessage(Component.translatable("music_battle.settings.press_key", laneName(index)));
            }, x, 0, half, 20), ROW * (lane + 3));
            addControl(new LaneColorButton(x + half + 6, half, index), ROW * (lane + 3));
        }
        Button skin = addControl(FancyButton.create(skinLabel(), button -> {
            ClientBattleSettings.setNoteSkin((ClientBattleSettings.appearance().skin() + 1) % 3);
            button.setMessage(skinLabel());
        }, x, 0, panelWidth, 20), ROW * 7);
        skin.setTooltip(Tooltip.create(Component.translatable("music_battle.settings.skin.size_hint")));
        addControl(FancyButton.create(panelColorLabel(), button -> openPanelColor(), x, 0, panelWidth, 20), ROW * 8);
        addControl(new RangeSlider(x, half, "panel_opacity", ClientBattleSettings.panelOpacity(), 0F, 1F, true,
                ClientBattleSettings::setPanelOpacity), ROW * 9);
        addControl(new RangeSlider(x + half + 6, half, "lane_opacity", ClientBattleSettings.laneOpacity(), 0F, 1.5F, true,
                ClientBattleSettings::setLaneOpacity), ROW * 9);
        addControl(new RangeSlider(x, half, "panel_width", ClientBattleSettings.panelWidthScale(), .45F, 1.6F, false,
                ClientBattleSettings::setPanelWidthScale), ROW * 10);
        addControl(new RangeSlider(x + half + 6, half, "panel_height", ClientBattleSettings.panelHeightScale(), .55F, 1.6F, false,
                ClientBattleSettings::setPanelHeightScale), ROW * 10);
        addControl(new RangeSlider(x, half, "note_width", ClientBattleSettings.noteWidthScale(), .35F, 2.2F, false,
                ClientBattleSettings::setNoteWidthScale), ROW * 11);
        addControl(new RangeSlider(x + half + 6, half, "note_height", ClientBattleSettings.noteHeightScale(), .35F, 2.2F, false,
                ClientBattleSettings::setNoteHeightScale), ROW * 11);
        positionControls();
    }

    private Button addNavigationButton(int index, String key, Button.OnPress action) {
        SettingsScreenLayout.Rect bounds = SettingsScreenLayout.navigationButton(width, index);
        return addRenderableWidget(FancyButton.create(Component.translatable(key), action,
                bounds.x(), bounds.y(), bounds.width(), bounds.height()));
    }

    private <T extends AbstractWidget> T addControl(T widget, int contentY) {
        controls.add(new Control(widget, contentY));
        return addWidget(widget);
    }

    private void positionControls() {
        scroll = Mth.clamp(scroll, 0, Math.max(0, CONTENT_HEIGHT - layout.controls().height()));
        for (Control control : controls) {
            int y = layout.controls().y() + control.contentY() - (int) Math.round(scroll);
            control.widget().setY(y);
            control.widget().visible = y >= layout.controls().y() && y + control.widget().getHeight() <= layout.controls().bottom();
            if (!control.widget().visible && getFocused() == control.widget()) {
                control.widget().setFocused(false);
                setFocused(null);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, Palette.BG_0);
        graphics.fill(0, 0, width, SettingsScreenLayout.headerHeight(width), Palette.BG_1);
        graphics.fill(0, height - 38, width, height, Palette.BG_1);
        float time = 192F + (System.nanoTime() - animationStart) / 50_000_000F % 960F;
        SettingsPanelPreview.draw(graphics, font, layout.preview(), time,
                new BattlePanelRenderer.Panel(0, 0, true, Component.translatable("music_battle.settings.preview_player").getString(),
                        ClientBattleSettings.noteSpeed(), previewPanelColor == null ? ClientBattleSettings.panelColor() : previewPanelColor,
                        ClientBattleSettings.panelOpacity(), ClientBattleSettings.laneOpacity(),
                        ClientBattleSettings.panelWidthScale(), ClientBattleSettings.panelHeightScale(),
                        ClientBattleSettings.noteWidthScale(), ClientBattleSettings.noteHeightScale(),
                        0, 0, new int[4], new int[4], ClientBattleSettings.keyLabels().toArray(String[]::new),
                        previewAppearance == null ? ClientBattleSettings.appearance() : previewAppearance));
        graphics.enableScissor(layout.controls().x(), layout.controls().y(), layout.controls().right(), layout.controls().bottom());
        try {
            for (Control control : controls) control.widget().render(graphics, mouseX, mouseY, partialTick);
            if (CONTENT_HEIGHT > layout.controls().height()) {
                int trackHeight = layout.controls().height();
                int thumbHeight = Math.max(12, trackHeight * trackHeight / CONTENT_HEIGHT);
                int thumbTop = layout.controls().y() + (int) Math.round(scroll / (CONTENT_HEIGHT - trackHeight) * (trackHeight - thumbHeight));
                graphics.fill(layout.controls().right() - 3, layout.controls().y(), layout.controls().right(), layout.controls().bottom(), Palette.BG_2);
                graphics.fill(layout.controls().right() - 3, thumbTop, layout.controls().right(), thumbTop + thumbHeight, Palette.ACCENT);
            }
        } finally { graphics.disableScissor(); }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (layout.controls().contains(mouseX, mouseY)) {
            scroll -= vertical * ROW;
            positionControls();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturingKey >= 0) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                minecraft.options.setKey(MusicBattleKeys.lanes()[capturingKey], InputConstants.getKey(keyCode, scanCode));
                KeyMapping.resetMapping();
                minecraft.options.save();
                ClientBattleSettings.sendToServer();
            }
            capturingKey = -1;
            refreshKeyButtons();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scroll += (keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * layout.controls().height() * .8;
            positionControls();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturingKey >= 0) {
            minecraft.options.setKey(MusicBattleKeys.lanes()[capturingKey], InputConstants.Type.MOUSE.getOrCreate(button));
            KeyMapping.resetMapping();
            minecraft.options.save();
            capturingKey = -1;
            refreshKeyButtons();
            ClientBattleSettings.sendToServer();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public void onClose() { ClientBattleSettings.sendToServer(); minecraft.setScreen(parent); }

    private void openLaneColor(int lane) {
        NoteAppearance initial = ClientBattleSettings.appearance();
        minecraft.setScreen(new ExactColorPickerScreen(this,
                Component.translatable("music_battle.settings.lane_color", laneName(lane)), initial.color(lane),
                color -> {
                    List<Integer> colors = new ArrayList<>(initial.colors());
                    colors.set(lane, color);
                    previewAppearance = new NoteAppearance(initial.skin(), colors);
                }, color -> ClientBattleSettings.setLaneColor(lane, color)));
    }

    private void openPanelColor() {
        minecraft.setScreen(new ExactColorPickerScreen(this, Component.translatable("music_battle.settings.panel_color", ""),
                ClientBattleSettings.panelColor(), color -> previewPanelColor = color, ClientBattleSettings::setPanelColor));
    }

    private void refreshKeyButtons() {
        for (int lane = 0; lane < keyButtons.length; lane++) if (keyButtons[lane] != null) keyButtons[lane].setMessage(keyLabel(lane));
    }
    private static Component laneName(int lane) { return Component.translatable("music_battle.settings.lane." + LANE_KEYS[lane]); }
    private static Component keyLabel(int lane) {
        return Component.translatable("music_battle.settings.key_binding", laneName(lane), MusicBattleKeys.lanes()[lane].getTranslatedKeyMessage());
    }
    private static Component spectatorEffectsLabel() {
        return Component.translatable("music_battle.settings.spectator_effects", ClientBattleSettings.spectatorEffects() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }
    private static Component skinLabel() {
        String skin = switch (ClientBattleSettings.appearance().skin()) { case 1 -> "arrows"; case 2 -> "circles"; default -> "bars"; };
        return Component.translatable("music_battle.settings.skin", Component.translatable("music_battle.settings.skin." + skin));
    }
    private static Component panelColorLabel() {
        return Component.translatable("music_battle.settings.panel_color", String.format(Locale.ROOT, "#%06X", ClientBattleSettings.panelColor() & 0xFFFFFF));
    }

    private final class LaneColorButton extends Button {
        private final int lane;
        LaneColorButton(int x, int width, int lane) {
            super(Button.builder(Component.translatable("music_battle.settings.lane_color", laneName(lane)), button -> openLaneColor(lane)).bounds(x, 0, width, 20));
            this.lane = lane;
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int color = (previewAppearance == null ? ClientBattleSettings.appearance() : previewAppearance).color(lane);
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + 20, isHoveredOrFocused() ? Palette.BG_4 : Palette.BG_3);
            graphics.fill(getX() + 4, getY() + 4, getX() + 16, getY() + 16, color);
            drawFitted(graphics, Component.literal(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF)),
                    getX() + 20 + (getWidth() - 20) / 2F, getY(), getWidth() - 24, 20);
        }
    }

    private static final class RangeSlider extends AbstractSliderButton {
        private final String key;
        private final float min;
        private final float max;
        private final boolean percent;
        private final Consumer<Float> setter;
        private RangeSlider(int x, int width, String key, float current, float min, float max, boolean percent, Consumer<Float> setter) {
            super(x, 0, width, 20, Component.empty(), (current - min) / (max - min));
            this.key = "music_battle.settings." + key;
            this.min = min;
            this.max = max;
            this.percent = percent;
            this.setter = setter;
            updateMessage();
        }
        @Override protected void updateMessage() {
            String value = String.format(Locale.ROOT, percent ? "%.0f" : "%.2f", valueToSetting() * (percent ? 100 : 1));
            setMessage(percent ? Component.literal(I18n.get(key, value)) : Component.translatable(key, value));
        }
        @Override protected void applyValue() { setter.accept(valueToSetting()); }
        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return visible && active && super.keyPressed(keyCode, scanCode, modifiers);
        }
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            ru.gora.musicbattle.client.ui.Slider.paint(graphics,
                    ru.gora.musicbattle.client.workshop.layout.Rect.of(getX(), getY(), getWidth(), getHeight()),
                    value, getMessage(), isHoveredOrFocused(), active);
        }
        private float valueToSetting() { return Mth.clamp((float) (min + value * (max - min)), min, max); }
    }

    private static void drawFitted(GuiGraphics graphics, Component text, float centerX, int y, int width, int height) {
        var font = Minecraft.getInstance().font;
        float scale = Math.min(1F, width / (float) Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y + (height - 8 * scale) / 2F, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawCenteredString(font, text, 0, 0, 0xFFFFFFFF);
        graphics.pose().popPose();
    }
}
