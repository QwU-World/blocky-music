package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.network.ConfigureSpeakerPayload;
import ru.gora.musicbattle.network.OpenSpeakerConfigPayload;

public final class SpeakerConfigScreen extends Screen {
    private final OpenSpeakerConfigPayload payload;
    private float radius;
    private float volume;
    private float lowGainDb;
    private float midGainDb;
    private float highGainDb;

    public SpeakerConfigScreen(OpenSpeakerConfigPayload payload) {
        super(Component.literal(text("music_battle.l10n.client.109")));
        this.payload = payload;
        this.radius = Mth.clamp(payload.radius(), 2.0F, 64.0F);
        this.volume = Mth.clamp(payload.volume(), 0.0F, 2.0F);
        this.lowGainDb = Mth.clamp(payload.lowGainDb(), -12.0F, 12.0F);
        this.midGainDb = Mth.clamp(payload.midGainDb(), -12.0F, 12.0F);
        this.highGainDb = Mth.clamp(payload.highGainDb(), -12.0F, 12.0F);
    }

    @Override protected void init() {
        int panelWidth = Math.min(420, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(8, height / 2 - 104);
        addRenderableWidget(new SettingSlider(left + 18, top + 45, panelWidth - 36, 22,
                text("music_battle.l10n.client.110"), radius, 2.0F, 64.0F, value -> radius = value, Unit.BLOCKS));
        addRenderableWidget(new SettingSlider(left + 18, top + 78, panelWidth - 36, 22,
                text("music_battle.l10n.client.111"), volume, 0.0F, 2.0F, value -> volume = value, Unit.PERCENT));
        int eqWidth = (panelWidth - 48) / 3;
        addRenderableWidget(new SettingSlider(left + 18, top + 111, eqWidth, 22,
                text("music_battle.l10n.client.112"), lowGainDb, -12.0F, 12.0F, value -> lowGainDb = value, Unit.DECIBELS));
        addRenderableWidget(new SettingSlider(left + 24 + eqWidth, top + 111, eqWidth, 22,
                text("music_battle.l10n.client.113"), midGainDb, -12.0F, 12.0F, value -> midGainDb = value, Unit.DECIBELS));
        addRenderableWidget(new SettingSlider(left + 30 + eqWidth * 2, top + 111, eqWidth, 22,
                text("music_battle.l10n.client.114"), highGainDb, -12.0F, 12.0F, value -> highGainDb = value, Unit.DECIBELS));
        int half = (panelWidth - 44) / 2;
        addRenderableWidget(FancyButton.create(Component.literal(text("music_battle.l10n.client.100")), button -> save(),
                left + 18, top + 150, half, 24));
        addRenderableWidget(FancyButton.create(Component.literal(text("music_battle.l10n.client.044")), button -> onClose(),
                left + 26 + half, top + 150, half, 24));
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(420, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(8, height / 2 - 104);
        graphics.fill(0, 0, width, height, 0xD9080B10);
        graphics.fill(left, top, left + panelWidth, top + 190, 0xFF13232D);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFF54D7F3);
        graphics.drawCenteredString(font, title, width / 2, top + 14, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }

    private void save() {
        PacketDistributor.sendToServer(new ConfigureSpeakerPayload(payload.pos(), radius, volume,
                lowGainDb, midGainDb, highGainDb));
        onClose();
    }

    private interface Setter { void set(float value); }

    private static final class SettingSlider extends AbstractSliderButton {
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Drawn by the toolkit, like every other slider in the mod; what the value means stays this screen's.
            ru.gora.musicbattle.client.ui.Slider.paint(graphics,
                    ru.gora.musicbattle.client.workshop.layout.Rect.of(getX(), getY(), getWidth(), getHeight()),
                    value, getMessage(), isHoveredOrFocused(), active);
        }

        private final String label;
        private final float minimum;
        private final float maximum;
        private final Setter setter;
        private final Unit unit;

        private SettingSlider(int x, int y, int width, int height, String label, float current,
                              float minimum, float maximum, Setter setter, boolean percent) {
            this(x, y, width, height, label, current, minimum, maximum, setter,
                    percent ? Unit.PERCENT : Unit.BLOCKS);
        }

        private SettingSlider(int x, int y, int width, int height, String label, float current,
                              float minimum, float maximum, Setter setter, Unit unit) {
            super(x, y, width, height, Component.empty(), (current - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.setter = setter;
            this.unit = unit;
            updateMessage();
        }

        private float setting() { return Mth.clamp((float) (minimum + value * (maximum - minimum)), minimum, maximum); }
        @Override protected void updateMessage() {
            String message = switch (unit) {
                case PERCENT -> String.format(java.util.Locale.ROOT, "%s: %.0f%%", label, setting() * 100.0F);
                case DECIBELS -> String.format(java.util.Locale.ROOT, "%s: %+.1f dB", label, setting());
                case BLOCKS -> String.format(java.util.Locale.ROOT, text("music_battle.l10n.client.115"), label, String.format(java.util.Locale.ROOT, "%.1f", setting()));
            };
            setMessage(Component.literal(message));
        }
        @Override protected void applyValue() { setter.set(setting()); }
    }

    private enum Unit { BLOCKS, PERCENT, DECIBELS }
}
