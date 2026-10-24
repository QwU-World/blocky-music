package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.network.ConfigureRapGodPayload;
import ru.gora.musicbattle.network.OpenRapGodConfigPayload;

public final class RapGodConfigScreen extends Screen {
    private final OpenRapGodConfigPayload payload;
    private EditBox displayName;
    private EditBox skinName;
    private Button stationaryButton;
    private Button invulnerableButton;
    private Button glowingButton;
    private Button randomVotesButton;
    private Button slimSkinButton;
    private Button pushableButton;
    private boolean stationary;
    private boolean invulnerable;
    private boolean glowing;
    private boolean randomVotes;
    private boolean slimSkin;
    private boolean pushableWhenStationary;

    public RapGodConfigScreen(OpenRapGodConfigPayload payload) {
        super(Component.literal("TEST_RAP_GOD_MASTER"));
        this.payload = payload;
        stationary = payload.stationary();
        invulnerable = payload.invulnerable();
        glowing = payload.glowing();
        randomVotes = payload.randomVotes();
        slimSkin = payload.slimSkin();
        pushableWhenStationary = payload.pushableWhenStationary();
    }

    @Override
    protected void init() {
        String retainedDisplayName = displayName == null ? payload.displayName() : displayName.getValue();
        String retainedSkinName = skinName == null ? payload.skinName() : skinName.getValue();
        boolean compact = height < 260;
        int panelWidth = Math.min(440, width - 24);
        int left = width / 2 - panelWidth / 2;
        int panelHeight = compact ? 196 : 244;
        int top = Math.max(4, (height - panelHeight) / 2);
        int displayY = top + (compact ? 40 : 49);
        int skinY = top + (compact ? 69 : 85);
        int rowHeight = compact ? 18 : 22;
        int firstRowY = top + (compact ? 94 : 116);
        int rowStep = compact ? 22 : 28;

        displayName = addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font, left + 14, displayY, panelWidth - 28, 20, Component.literal(text("music_battle.l10n.client.097"))));
        displayName.setMaxLength(48);
        displayName.setHint(Component.literal(TestRapGodMasterEntity.NPC_NAME));
        displayName.setValue(retainedDisplayName);

        skinName = addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font, left + 14, skinY, panelWidth - 28, 20, Component.literal(text("music_battle.l10n.client.098"))));
        skinName.setMaxLength(16);
        skinName.setHint(Component.literal(text("music_battle.l10n.client.099")));
        skinName.setValue(retainedSkinName);

        int half = (panelWidth - 36) / 2;
        stationaryButton = addRenderableWidget(FancyButton.create(Component.empty(), button -> {
            stationary = !stationary;
            refreshLabels();
        }, left + 14, firstRowY, half, rowHeight));
        invulnerableButton = addRenderableWidget(FancyButton.create(Component.empty(), button -> {
            invulnerable = !invulnerable;
            refreshLabels();
        }, left + 22 + half, firstRowY, half, rowHeight));
        glowingButton = addRenderableWidget(FancyButton.create(Component.empty(), button -> {
            glowing = !glowing;
            refreshLabels();
        }, left + 14, firstRowY + rowStep, half, rowHeight));
        randomVotesButton = addRenderableWidget(FancyButton.create(Component.empty(), button -> {
            randomVotes = !randomVotes;
            refreshLabels();
        }, left + 22 + half, firstRowY + rowStep, half, rowHeight));
        slimSkinButton = addRenderableWidget(FancyButton.create(Component.empty(), button -> {
            slimSkin = !slimSkin;
            refreshLabels();
        }, left + 14, firstRowY + rowStep * 2, half, rowHeight));
        pushableButton = addRenderableWidget(FancyButton.create(Component.empty(), button -> {
            pushableWhenStationary = !pushableWhenStationary;
            refreshLabels();
        }, left + 22 + half, firstRowY + rowStep * 2, half, rowHeight));

        int actionY = firstRowY + rowStep * 3 + (compact ? 3 : 4);
        addRenderableWidget(FancyButton.create(Component.literal(text("music_battle.l10n.client.100")), button -> save(), left + 14, actionY, half, compact ? 20 : 24));
        addRenderableWidget(FancyButton.create(Component.literal(text("music_battle.l10n.client.044")), button -> onClose(), left + 22 + half, actionY, half, compact ? 20 : 24));
        refreshLabels();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen draws an opaque background itself; vanilla blur must not cover the panel.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean compact = height < 260;
        graphics.fill(0, 0, width, height, 0xEE080B10);
        int panelWidth = Math.min(440, width - 24);
        int left = width / 2 - panelWidth / 2;
        int panelHeight = compact ? 196 : 244;
        int top = Math.max(4, (height - panelHeight) / 2);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF13232D);
        graphics.fill(left, top, left + panelWidth, top + 2, 0xFF54D7F3);
        graphics.drawCenteredString(font, title, width / 2, top + (compact ? 7 : 10), 0xFFFFFFFF);
        graphics.drawString(font, text("music_battle.l10n.client.101"), left + 14, top + (compact ? 20 : 25), 0xFFFFD267, false);
        graphics.drawString(font, text("music_battle.l10n.client.102"), left + 14, top + (compact ? 30 : 38), 0xFFAED6E5, false);
        graphics.drawString(font, text("music_battle.l10n.client.099"), left + 14, top + (compact ? 59 : 74), 0xFFAED6E5, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    private void save() {
        PacketDistributor.sendToServer(new ConfigureRapGodPayload(
                payload.entityId(),
                displayName.getValue().trim(),
                skinName.getValue().trim(),
                slimSkin,
                pushableWhenStationary,
                stationary,
                invulnerable,
                glowing,
                randomVotes
        ));
        onClose();
    }

    private void refreshLabels() {
        stationaryButton.setMessage(Component.literal(text("music_battle.l10n.client.103") + (stationary ? text("music_battle.l10n.client.059") : text("music_battle.l10n.client.058"))));
        invulnerableButton.setMessage(Component.literal(text("music_battle.l10n.client.104") + (invulnerable ? text("music_battle.l10n.client.058") : text("music_battle.l10n.client.059"))));
        glowingButton.setMessage(Component.literal(text("music_battle.l10n.client.105") + (glowing ? text("music_battle.l10n.client.058") : text("music_battle.l10n.client.059"))));
        randomVotesButton.setMessage(Component.literal(text("music_battle.l10n.client.106") + (randomVotes ? text("music_battle.l10n.client.058") : text("music_battle.l10n.client.059"))));
        slimSkinButton.setMessage(Component.literal(text("music_battle.l10n.client.107") + (slimSkin ? "SLIM" : "CLASSIC")));
        pushableButton.setMessage(Component.literal(text("music_battle.l10n.client.108") + (pushableWhenStationary ? text("music_battle.l10n.client.058") : text("music_battle.l10n.client.059"))));
    }
}
