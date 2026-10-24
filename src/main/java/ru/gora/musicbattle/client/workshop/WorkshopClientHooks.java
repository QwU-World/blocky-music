package ru.gora.musicbattle.client.workshop;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import ru.gora.musicbattle.client.ClientBattleController;
import ru.gora.musicbattle.client.MusicBattleKeys;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiIcons;
import ru.gora.musicbattle.client.workshop.layout.MenuCornerButtons;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * Game hooks of the Workshop (spec 06 section 5): the Alt+B key (rebindable), the button on the title screen and the
 * pause menu, the global {@code .blockymap} drop and the client tick. Called from {@code ClientGameEvents} and the mouse
 * input mixin. Render thread only.
 */
public final class WorkshopClientHooks {
    private WorkshopClientHooks() {
    }

    // ---------------------------------------------------------------- payloads (spec 06 section 10)
    // The second owner of client-only handlers behind ClientPayloadBridge: everything the server's Workshop
    // features send lands here, so the battle controller keeps its own surface. Render thread.

    public static void acceptServerInfo(ru.gora.musicbattle.network.WorkshopServerInfoPayload payload) {
        ServerWorkshopClient.acceptServerInfo(payload);
    }

    public static void acceptServerBeatmapsPage(ru.gora.musicbattle.network.ServerBeatmapsPagePayload payload) {
        ServerWorkshopClient.acceptServerBeatmapsPage(payload);
    }

    public static void acceptServerTracks(ru.gora.musicbattle.network.ServerTracksPayload payload) {
        ServerWorkshopClient.acceptServerTracks(payload);
    }

    public static void acceptServerBeatmapOfferResult(ru.gora.musicbattle.network.ServerBeatmapOfferResultPayload payload) {
        ServerWorkshopClient.acceptServerBeatmapOfferResult(payload);
    }

    public static void acceptServerBeatmapAbort(ru.gora.musicbattle.network.ServerBeatmapTransferAbortPayload payload) {
        ServerWorkshopClient.acceptServerBeatmapAbort(payload);
    }

    public static void acceptServerBeatmapResult(ru.gora.musicbattle.network.ServerBeatmapResultPayload payload) {
        ServerWorkshopClient.acceptServerBeatmapResult(payload);
    }

    public static void acceptServerCommentsPage(ru.gora.musicbattle.network.ServerCommentsPagePayload payload) {
        ServerWorkshopClient.acceptServerCommentsPage(payload);
    }

    public static void acceptServerCommentResult(ru.gora.musicbattle.network.ServerCommentResultPayload payload) {
        ServerWorkshopClient.acceptServerCommentResult(payload);
    }

    /**
     * Answers a battle asset offer from the in-memory content index only (review of 06 section 9.4): no file is
     * hashed and nothing blocking happens on this thread.
     */
    public static void answerTrackAssetOffer(ru.gora.musicbattle.network.TrackAssetOfferPayload payload) {
        boolean have = ru.gora.musicbattle.client.ClientTrackAssets.hasContent(payload.sha256());
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ru.gora.musicbattle.network.TrackAssetAnswerPayload(payload.battleId(), payload.trackId(),
                        payload.assetKind(), payload.fileName(), payload.sha256(), have));
    }

    /** Leaving a server: the Workshop forgets everything that world told it. */
    public static void onDisconnect() {
        ServerWorkshopClient.clear();
    }

    /** Client tick: the open key (no screen, not in battle input isolation), then the Workshop's own ticking. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (MusicBattleKeys.OPEN_WORKSHOP.consumeClick()) {
            if (minecraft.screen == null && !ClientBattleController.blocksParticipantInput()) {
                WorkshopClient.open(null);
            }
        }
        WorkshopClient.get().tick();
    }

    /**
     * A small "Workshop" button in the top-right corner of the title screen, clear of vanilla buttons and Realms
     * notices, and in the top-left corner of the pause menu. The pause menu needs it as much as the title screen: on a
     * server the title screen is behind the player for the whole session, and Alt+B alone is a shortcut nobody has
     * been told about, so the Workshop looked gone the moment the owner played with a friend.
     */
    public static void addTitleButton(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        boolean pause = screen instanceof PauseScreen pauseScreen && pauseScreen.showsPauseMenu();
        // A fighter cannot open the Workshop with its key either: the battle owns their input until it ends.
        if (!(screen instanceof TitleScreen) && !(pause && !ClientBattleController.blocksParticipantInput())) {
            return;
        }
        // In the pause menu the top-right corner belongs to toasts - advancements, recipes, the offline server's chat
        // notice - which are drawn over every screen and hid the button, so there it sits beside the BM button.
        Rect bounds = pause ? MenuCornerButtons.pauseWorkshop() : MenuCornerButtons.workshop(screen.width);
        Button button = new TitleButton((int) bounds.x(), (int) bounds.y(), (int) bounds.w(), (int) bounds.h(),
                Component.translatable("screen.music_battle.workshop.title_button"), ignored -> WorkshopClient.open(screen));
        button.setTooltip(Tooltip.create(Component.translatable("screen.music_battle.workshop.title_button.tooltip")));
        event.addListener(button);
    }

    /**
     * Dropped files for any screen that does not handle them itself, and for the world with no screen open. Returns
     * the paths vanilla should still receive, or null when nothing was a Workshop package.
     */
    public static List<Path> interceptDrop(List<Path> paths) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof WorkshopDropTarget || paths.stream().noneMatch(WorkshopDrops::isPackage)) {
            return null;
        }
        return WorkshopDrops.onFilesDropped(screen, paths);
    }

    /** Vanilla button with the Workshop music icon before its label. */
    private static final class TitleButton extends Button {
        TitleButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
            int icon = 9;
            int textWidth = font.width(getMessage());
            int left = getX() + (getWidth() - textWidth - icon - 4) / 2;
            UiIcons.draw(graphics, UiIcon.MUSIC, left, getY() + (getHeight() - icon) / 2.0F, icon, color);
            graphics.flush();
            graphics.drawString(font, getMessage(), left + icon + 4, getY() + (getHeight() - 8) / 2, color);
        }
    }
}
