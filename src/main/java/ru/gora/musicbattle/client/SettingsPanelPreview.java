package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;

/**
 * The settings screen's live picture of a battle: a silent demonstration clock and the actual gameplay renderer, over a
 * world, with the battle's own dimming and stage bars between the two, in the battle's order.
 *
 * <p>It used to draw the panel straight onto the settings screen's background. That background is the very colour of
 * the default panel (both are {@code Palette.BG_0}), so the panel background slider changed nothing a player could see
 * here, while in a battle the same slider plainly lets the world through. Now the panel sits on a world, as it does in
 * a battle, and every slider that changes the battle changes this picture while it is being dragged.
 */
final class SettingsPanelPreview {
    /**
     * The world behind the picture: a face of the title screen's panorama, the one picture of a Minecraft world every
     * client has (resource packs included), and the same in a world and on the title screen.
     */
    static final ResourceLocation WORLD = ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama_1.png");

    private SettingsPanelPreview() { }
    record DemoNote(int owner, int lane, int tick, int durationTicks, boolean started, boolean resolved)
            implements BattlePanelRenderer.NoteView { }
    record Frame(List<DemoNote> notes, int[] flashes, int[] bursts, int score, int combo) { }

    /** What a battle draws between the world and its panels, as the player has set it; see {@link BattleBackdrop}. */
    record Stage(int dimmingAlpha, int barAlpha) {
        static Stage current() {
            return new Stage(ClientBattleSettings.worldDimmingAlpha(), ClientBattleSettings.stageBarAlpha());
        }
    }

    static Frame frame(float time) {
        int step = (int) Math.floor(time / 12F);
        List<DemoNote> notes = new ArrayList<>();
        int[] flashes = new int[4];
        int[] bursts = new int[4];
        for (int index = step - 2; index <= step + 10; index++) {
            int lane = Math.floorMod(index, 4);
            int tick = index * 12;
            int duration = Math.floorMod(index, 8) == 2 ? 18 : 0;
            float age = time - tick;
            boolean started = duration > 0 && age >= 0 && age < duration;
            notes.add(new DemoNote(0, lane, tick, duration, started, age >= duration));
            if (age >= 0 && age < 8) {
                bursts[lane] = Math.max(bursts[lane], 8 - (int) age);
                flashes[lane] = Math.max(flashes[lane], Math.max(0, 6 - (int) age));
            }
            if (started) flashes[lane] = Math.max(flashes[lane], 3);
        }
        return new Frame(List.copyOf(notes), flashes, bursts, Math.max(0, step) * 100, Math.max(0, step));
    }

    /** The caption, the world, the battle's layers over it, and the picture's rounded frame. */
    static void draw(GuiGraphics graphics, Font font, SettingsScreenLayout.Rect viewport, SettingsScreenLayout.Preview preview,
                     float time, BattlePanelRenderer.Panel settings, Stage stage) {
        if (preview.empty()) {
            return;
        }
        SettingsScreenLayout.Rect card = preview.card();
        float radius = Palette.RADIUS_MD;
        // The world fills the picture the way a cover fills a card: the panorama face is square, so the longer side of
        // the picture takes the whole face and the shorter one its middle.
        float aspect = card.width() / (float) card.height();
        float u = aspect < 1F ? (1F - aspect) * 0.5F : 0F;
        float v = aspect > 1F ? (1F - 1F / aspect) * 0.5F : 0F;
        RoundedImage.draw(graphics, WORLD, card.x(), card.y(), card.width(), card.height(), u, v, 1F - u, 1F - v,
                radius, radius, radius, radius, 0F, 0xFFFFFFFF, 0xFFFFFFFF);
        drawBattle(graphics, font, preview, time, settings, stage);
        // The battle's layers are square fills, as they are on a real screen; a band of the screen's own background
        // around the picture rounds its corners off, so the veil and the bars end where the world does. It is kept
        // inside the viewport, where nothing else is drawn.
        UiClip.push(graphics, viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        RoundedRect.stroke(graphics, card.x() - radius, card.y() - radius, card.width() + radius * 2,
                card.height() + radius * 2, radius * 2, radius, Palette.BG_0);
        RoundedRect.stroke(graphics, card.x(), card.y(), card.width(), card.height(), radius, 1F,
                Palette.withAlpha(Palette.LINE, 0.75F));
        UiClip.pop(graphics);
        TextStyles.queue(graphics, TextStyles.translated("music_battle.settings.preview", TextStyle.LABEL).getVisualOrderText(),
                preview.captionX(), preview.captionY(), Palette.TEXT_3);
        graphics.flush();
    }

    /**
     * The layers the battle HUD draws, scaled into the picture: the world dimming, the stage bars and the panel, through
     * the very calls the HUD makes ({@link BattleBackdrop}, {@link BattlePanelRenderer}) on a canvas the size of the
     * player's own battle screen. Nothing of the toolkit's here, only what the battle itself draws with, so the
     * note-panel-render check can rasterize it without a GPU.
     */
    static void drawBattle(GuiGraphics graphics, Font font, SettingsScreenLayout.Preview preview, float time,
                           BattlePanelRenderer.Panel settings, Stage stage) {
        if (preview.empty()) {
            return;
        }
        SettingsScreenLayout.Rect card = preview.card();
        graphics.enableScissor(card.x(), card.y(), card.right(), card.bottom());
        try {
            Frame frame = frame(time);
            BattlePanelRenderer.Panel panel = new BattlePanelRenderer.Panel(0, 0, true, settings.name(), settings.speed(), settings.color(),
                    settings.panelOpacity(), settings.laneOpacity(), settings.widthScale(), settings.heightScale(),
                    settings.noteWidthScale(), settings.noteHeightScale(), frame.score(), frame.combo(),
                    frame.flashes(), frame.bursts(), settings.laneLabels(), settings.appearance());
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(card.x(), card.y(), 0);
                graphics.pose().scale(preview.scale(), preview.scale(), 1);
                BattleBackdrop.dim(graphics::fill, preview.visibleWidth(), preview.canvasHeight(), stage.dimmingAlpha());
                BattleBackdrop.bars(graphics::fill, preview.visibleWidth(), preview.canvasHeight(), stage.barAlpha());
                BattlePanelRenderer.draw(graphics, font, preview.canvasWidth(), preview.canvasHeight(), time, 100, 1F,
                        frame.notes(), panel);
            } finally { graphics.pose().popPose(); }
            // Vanilla does not flush on a scissor change outside managed drawing; anything still queued would be drawn
            // after the scissor is gone, over the controls.
            graphics.flush();
        } finally { graphics.disableScissor(); }
    }

    /** A panel at the width slider's maximum: what the preview keeps room for, measured by the battle's own layout. */
    static BattlePanelRenderer.Panel widestPanel() {
        return new BattlePanelRenderer.Panel(0, 0, true, "", 1F, 0xFF000000, 0F, 0F, SettingsScreenLayout.MAX_PANEL_WIDTH_SCALE,
                1F, 1F, 1F, 0, 0, new int[4], new int[4], new String[4]);
    }

    /** The panel the preview draws, from the player's current settings and any colour still being picked. */
    static BattlePanelRenderer.Panel panel(Integer pickingPanelColor, ru.gora.musicbattle.data.NoteAppearance pickingAppearance) {
        return new BattlePanelRenderer.Panel(0, 0, true, Component.translatable("music_battle.settings.preview_player").getString(),
                ClientBattleSettings.noteSpeed(), pickingPanelColor == null ? ClientBattleSettings.panelColor() : pickingPanelColor,
                ClientBattleSettings.panelOpacity(), ClientBattleSettings.laneOpacity(),
                ClientBattleSettings.panelWidthScale(), ClientBattleSettings.panelHeightScale(),
                ClientBattleSettings.noteWidthScale(), ClientBattleSettings.noteHeightScale(),
                0, 0, new int[4], new int[4], ClientBattleSettings.keyLabels().toArray(String[]::new),
                pickingAppearance == null ? ClientBattleSettings.appearance() : pickingAppearance);
    }
}
