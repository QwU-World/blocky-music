package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.NoteAppearance;

/**
 * The mod's general settings: lane keys and colours, the look of the notes and the panel, and how dark a battle is
 * behind the panel, beside a live picture of a battle drawn by the battle's own renderer.
 *
 * <p>Drawn with the Workshop's toolkit like the calibration screen it shares tabs with: the tabs sit in a header bar,
 * the current one raised and underlined in the accent instead of shown as a disabled button, and the controls, colour
 * buttons and scrollbar are the toolkit's rounded ones in Nunito rather than flat boxes in the bitmap font.
 */
public final class MusicBattleSettingsScreen extends UiLayer {
    private static final int ROW = 26;
    private static final int ROWS = 14;
    private static final int CONTENT_HEIGHT = ROW * ROWS;
    private static final String[] LANE_KEYS = {"left", "down", "up", "right"};
    private final Screen parent;
    private final Button[] keyButtons = new Button[4];
    private final List<Control> controls = new ArrayList<>();
    private final long animationStart = Util.getNanos();
    /** The note-shape button's hint, drawn in the toolkit's look; see {@link #setTooltipForNextRenderPass}. */
    private final UiTooltip.Deferred tooltips = new UiTooltip.Deferred();
    private SettingsScreenLayout layout;
    private SettingsScreenLayout.Preview preview;
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
    protected void layout() {
        build(TextStyles.measurer());
    }

    /**
     * Everything {@link #layout()} does, with the text measured by {@code measurer}: the Minecraft font in the game, the
     * bundled Nunito in the checks that build this screen without a window.
     */
    void build(TextMeasurer measurer) {
        controls.clear();
        previewAppearance = null;
        previewPanelColor = null;
        List<String> labels = SettingsScreenLayout.NAVIGATION.stream().map(key -> Component.translatable(key).getString()).toList();
        layout = SettingsScreenLayout.create(width, height, SettingsScreenLayout.nav(width, height, measurer, labels, 0));
        BattleHudScale.Frame hud = BattleHudScale.frame(width, height);
        int widest = BattlePanelRenderer.layout(hud.width(), hud.height(), 1F, SettingsPanelPreview.widestPanel()).width();
        preview = SettingsScreenLayout.preview(layout.preview(), hud.width(), hud.height(), widest,
                measurer.width(Component.translatable("music_battle.settings.preview").getString(), TextStyle.LABEL));
        addNavigationTab(0, button -> { });
        addNavigationTab(1, button -> minecraft.setScreen(new MusicBattleCalibrationScreen(this)));
        addNavigationTab(2, button -> minecraft.setScreen(new TrackLibraryScreen(this)));
        addNavigationTab(3, button -> minecraft.setScreen(new MusicBattleGuideScreen(this)));
        addNavigationTab(4, button -> ru.gora.musicbattle.client.workshop.WorkshopClient.open(this));
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
            addControl(new ColorButton(x + half + 6, half, Component.translatable("music_battle.settings.lane_color", laneName(lane)),
                    () -> (previewAppearance == null ? ClientBattleSettings.appearance() : previewAppearance).color(index),
                    color -> Component.literal(hex(color)), () -> openLaneColor(index)), ROW * (lane + 3));
        }
        Button skin = addControl(FancyButton.create(skinLabel(), button -> {
            ClientBattleSettings.setNoteSkin((ClientBattleSettings.appearance().skin() + 1) % 3);
            button.setMessage(skinLabel());
        }, x, 0, panelWidth, 20), ROW * 7);
        // Still a vanilla Tooltip, so the button decides when it shows and the narrator reads it, but drawn as the
        // toolkit's card (see render): it was the last tooltip on these screens in vanilla's purple frame.
        skin.setTooltip(tooltips.create(Component.translatable("music_battle.settings.skin.size_hint")));
        addControl(new ColorButton(x, panelWidth, panelColorLabel(ClientBattleSettings.panelColor()),
                () -> previewPanelColor == null ? ClientBattleSettings.panelColor() : previewPanelColor,
                MusicBattleSettingsScreen::panelColorLabel, this::openPanelColor), ROW * 8);
        addControl(new RangeSlider(x, half, "panel_opacity", ClientBattleSettings.panelOpacity(), 0F, 1F, true,
                ClientBattleSettings::setPanelOpacity), ROW * 9);
        addControl(new RangeSlider(x + half + 6, half, "lane_opacity", ClientBattleSettings.laneOpacity(), 0F, 1.5F, true,
                ClientBattleSettings::setLaneOpacity), ROW * 9);
        // How dark the battle is behind the panel sits right under the panel's own background, so the two read as the
        // different things they are.
        addControl(new RangeSlider(x, panelWidth, "world_dimming", ClientBattleSettings.worldDimming(), 0F,
                BattleBackdrop.MAX_DIMMING, true, ClientBattleSettings::setWorldDimming), ROW * 10);
        RangeSlider barOpacity = new RangeSlider(x + half + 6, half, "stage_bar_opacity", ClientBattleSettings.stageBarOpacity(),
                0F, 1F, true, ClientBattleSettings::setStageBarOpacity);
        barOpacity.active = ClientBattleSettings.stageBars();
        addControl(FancyButton.create(stageBarsLabel(), button -> {
            ClientBattleSettings.setStageBars(!ClientBattleSettings.stageBars());
            button.setMessage(stageBarsLabel());
            // The opacity is kept while the bars are off, so switching them back on brings back the same bars.
            barOpacity.active = ClientBattleSettings.stageBars();
        }, x, 0, half, 20), ROW * 11);
        addControl(barOpacity, ROW * 11);
        addControl(new RangeSlider(x, half, "panel_width", ClientBattleSettings.panelWidthScale(), .45F,
                SettingsScreenLayout.MAX_PANEL_WIDTH_SCALE, false, ClientBattleSettings::setPanelWidthScale), ROW * 12);
        addControl(new RangeSlider(x + half + 6, half, "panel_height", ClientBattleSettings.panelHeightScale(), .55F, 1.6F, false,
                ClientBattleSettings::setPanelHeightScale), ROW * 12);
        addControl(new RangeSlider(x, half, "note_width", ClientBattleSettings.noteWidthScale(), .35F, 2.2F, false,
                ClientBattleSettings::setNoteWidthScale), ROW * 13);
        addControl(new RangeSlider(x + half + 6, half, "note_height", ClientBattleSettings.noteHeightScale(), .35F, 2.2F, false,
                ClientBattleSettings::setNoteHeightScale), ROW * 13);
        positionControls();
    }

    private void addNavigationTab(int index, Button.OnPress action) {
        SettingsScreenLayout.Nav nav = layout.nav();
        addRenderableWidget(new NavTab(Component.translatable(SettingsScreenLayout.NAVIGATION.get(index)), action, nav.tabs().get(index),
                index == nav.selected(), nav.labels().get(index), nav.style(index), nav.ruleY()));
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
        super.render(graphics, mouseX, mouseY, partialTick);
        // After the tabs and Done, so the card lies over every widget; a button asked for it while it rendered.
        tooltips.draw(graphics, mouseX, mouseY, width, height, getFocused());
    }

    /** A tooltip made by {@link #tooltips} is drawn by this screen in the toolkit's look; any other stays vanilla's. */
    @Override
    public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
        if (!tooltips.offer(tooltip, positioner)) {
            super.setTooltipForNextRenderPass(tooltip, positioner, focused);
        }
    }

    @Override
    protected void renderUi(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        drawBars(graphics);
        float time = 192F + (Util.getNanos() - animationStart) / 50_000_000F % 960F;
        SettingsPanelPreview.draw(graphics, font, layout.preview(), preview, time,
                SettingsPanelPreview.panel(previewPanelColor, previewAppearance), SettingsPanelPreview.Stage.current());
        SettingsScreenLayout.Rect area = layout.controls();
        UiClip.push(graphics, area.x(), area.y(), area.right(), area.bottom());
        for (Control control : controls) control.widget().render(graphics, (int) mouseX, (int) mouseY, partialTick);
        // The Workshop's thumb, in the six units the controls leave free on their right.
        WorkshopDraw.scrollbar(graphics, Rect.of(area.x(), area.y(), area.width(), area.height()), scroll, CONTENT_HEIGHT, false);
        UiClip.pop(graphics);
    }

    /** The header and footer bars; the tabs and Done are widgets and land on top of them. */
    private void drawBars(GuiGraphics graphics) {
        int header = layout.nav().header();
        RoundedRect.fill(graphics, 0, 0, width, header, 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, header - 1, width, 1, 0, Palette.LINE);
        RoundedRect.fill(graphics, 0, height - SettingsScreenLayout.FOOTER, width, SettingsScreenLayout.FOOTER, 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, height - SettingsScreenLayout.FOOTER, width, 1, 0, Palette.LINE);
    }

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

    @Override public void onClose() {
        ClientBattleSettings.sendToServer();
        HandCursor.reset();
        minecraft.setScreen(parent);
    }

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
        // The picker's title has a key of its own: the button's label template with an empty value left a dangling
        // colon in the title ("Background color: ").
        minecraft.setScreen(new ExactColorPickerScreen(this, Component.translatable("music_battle.settings.panel_color_title"),
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
    private static Component stageBarsLabel() {
        return Component.translatable("music_battle.settings.stage_bars", ClientBattleSettings.stageBars() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }
    private static Component skinLabel() {
        String skin = switch (ClientBattleSettings.appearance().skin()) { case 1 -> "arrows"; case 2 -> "circles"; default -> "bars"; };
        return Component.translatable("music_battle.settings.skin", Component.translatable("music_battle.settings.skin." + skin));
    }
    private static Component panelColorLabel(int color) {
        return Component.translatable("music_battle.settings.panel_color", hex(color));
    }
    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /**
     * A tab in the header, drawn like the calibration screen's and the track library's: the current one raised with an
     * accent rule under it rather than shown as a disabled button, which is what a vanilla button that cannot be pressed
     * would say about it, and the others quiet until pointed at. The label is the layout's, in the style it was measured
     * in, so it is never wider than the tab.
     */
    private static final class NavTab extends Button {
        private final boolean selected;
        private final String label;
        private final TextStyle style;
        private final float ruleY;

        private NavTab(Component message, OnPress onPress, SettingsScreenLayout.Rect bounds, boolean selected, String label,
                       TextStyle style, float ruleY) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message, onPress, DEFAULT_NARRATION);
            this.selected = selected;
            this.label = label;
            this.style = style;
            this.ruleY = ruleY;
            this.active = !selected;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hovered = this.active && isHoveredOrFocused();
            if (hovered) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            if (selected) {
                RoundedRect.fill(graphics, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, Palette.BG_3);
                RoundedRect.fill(graphics, rect.x() + 3, ruleY, rect.w() - 6, 1.5F, 0.75F, Palette.ACCENT);
            } else {
                WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.QUIET, hovered, true);
                if (hovered) {
                    WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.5F));
                }
            }
            TextStyles.queueCentered(graphics, TextStyles.text(label, style).getVisualOrderText(), rect.centerX(),
                    TextStyles.centeredCapY(style, rect.y(), rect.h()), selected || hovered ? Palette.TEXT_1 : Palette.TEXT_2);
        }
    }

    /**
     * A button that shows a colour: a swatch at its left and its label beside it, on the toolkit's button surface. The
     * swatch keeps a faint outline so that a colour as dark as the button, such as the default panel's, still reads.
     */
    private static final class ColorButton extends Button {
        private final IntSupplier color;
        private final IntFunction<Component> label;

        private ColorButton(int x, int width, Component narration, IntSupplier color, IntFunction<Component> label,
                            Runnable onPress) {
            super(x, 0, width, 20, narration, button -> onPress.run(), DEFAULT_NARRATION);
            this.color = color;
            this.label = label;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hovered = isHoveredOrFocused();
            if (hovered && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, this.active);
            int value = 0xFF000000 | color.getAsInt();
            float swatch = rect.h() - 8;
            RoundedRect.fill(graphics, rect.x() + 4, rect.y() + 4, swatch, swatch, Palette.RADIUS_SM * 0.75F, value);
            RoundedRect.stroke(graphics, rect.x() + 4, rect.y() + 4, swatch, swatch, Palette.RADIUS_SM * 0.75F, 1F,
                    Palette.withAlpha(Palette.TEXT_1, 0.22F));
            float textLeft = rect.x() + 4 + swatch + 4;
            float room = Math.max(4, rect.right() - 5 - textLeft);
            String text = TextFit.ellipsize(TextStyles.measurer(), label.apply(value).getString(), TextStyle.BODY, room);
            TextStyles.queueCentered(graphics, TextStyles.text(text, TextStyle.BODY).getVisualOrderText(),
                    textLeft + room * 0.5F, TextStyles.centeredCapY(TextStyle.BODY, rect.y(), rect.h()),
                    hovered ? Palette.TEXT_1 : Palette.TEXT_2);
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
            // A percent is rounded half up from the very value the setting stores, as BattleBackdrop rounds it, so a
            // dimming or a bar shown as 0% is one that draws nothing.
            String value = percent ? Integer.toString(Math.round(valueToSetting() * 100F))
                    : String.format(Locale.ROOT, "%.2f", valueToSetting());
            setMessage(percent ? Component.literal(I18n.get(key, value)) : Component.translatable(key, value));
        }
        @Override protected void applyValue() { setter.accept(valueToSetting()); }
        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return visible && active && super.keyPressed(keyCode, scanCode, modifiers);
        }
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            ru.gora.musicbattle.client.ui.Slider.paint(graphics, Rect.of(getX(), getY(), getWidth(), getHeight()),
                    value, getMessage(), isHoveredOrFocused(), active);
        }
        private float valueToSetting() { return Mth.clamp((float) (min + value * (max - min)), min, max); }
    }
}
