package ru.gora.musicbattle.client;

import java.util.UUID;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopButton;
import ru.gora.musicbattle.client.workshop.WorkshopDialogScreen;
import ru.gora.musicbattle.network.ChallengeSidePayload;

/**
 * Which side the challenger takes, asked once when they challenge another player or a mob with the microphone.
 *
 * <p>A dialog rather than chat buttons: the answer goes to the server as a payload, so it needs no command, and it
 * comes before the challenge is sent, so an invited player is told the sides in the same message they accept, and a
 * mob battle starts with the side already settled. Player
 * 1 is the primary action, so Enter keeps the side every challenger had before; Esc sends nothing.
 */
public final class ChallengeSideScreen extends WorkshopDialogScreen {
    private final UUID targetId;
    private boolean answered;

    ChallengeSideScreen(Screen parent, UUID targetId, String targetName) {
        super(parent, Component.translatable("screen.music_battle.challenge_side.title", targetName), UiIcon.MUSIC,
                Palette.ACCENT);
        this.targetId = targetId;
        addParagraph(Component.translatable("screen.music_battle.challenge_side.intro"));
        addAction(Component.translatable("screen.music_battle.challenge_side.first"), WorkshopButton.Kind.PRIMARY,
                UiIcon.USER_ROUND, () -> answer(true));
        addAction(Component.translatable("screen.music_battle.challenge_side.second"), WorkshopButton.Kind.SECONDARY,
                UiIcon.USER_ROUND, () -> answer(false));
        addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, this::onClose);
        setCancelAction(this::onClose);
    }

    private void answer(boolean challengerFirst) {
        // A double click must not send the challenge twice; the server would only ignore the second, but say it once.
        if (answered) {
            return;
        }
        answered = true;
        PacketDistributor.sendToServer(new ChallengeSidePayload(targetId, challengerFirst));
        onClose();
    }
}
