package ru.gora.musicbattle.client.workshop;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;

/**
 * A yes/no dialog in the Workshop's own look (delete a local copy, delete a comment, leave the wizard). The action
 * runs after the dialog closes, so the Workshop screen below is the one that reacts.
 */
public final class WorkshopConfirmScreen extends WorkshopDialogScreen {
    private WorkshopConfirmScreen(Screen parent, Component title, Component body, Component confirmLabel, Runnable action,
                                  Runnable onCancel, boolean danger) {
        super(parent, title, danger ? UiIcon.ALERT : UiIcon.INFO, danger ? Palette.DANGER : Palette.ACCENT);
        addParagraph(body);
        Runnable cancel = () -> {
            onClose();
            if (onCancel != null) {
                onCancel.run();
            }
        };
        addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, cancel);
        addAction(confirmLabel, danger ? WorkshopButton.Kind.DANGER : WorkshopButton.Kind.PRIMARY,
                danger ? UiIcon.TRASH : UiIcon.CHECK, () -> {
                    onClose();
                    action.run();
                });
        setCancelAction(cancel);
    }

    public static WorkshopConfirmScreen danger(Screen parent, Component title, Component body, Component confirmLabel,
                                               Runnable action) {
        return new WorkshopConfirmScreen(parent, title, body, confirmLabel, action, null, true);
    }

    public static WorkshopConfirmScreen confirm(Screen parent, Component title, Component body, Component confirmLabel,
                                                Runnable action) {
        return new WorkshopConfirmScreen(parent, title, body, confirmLabel, action, null, false);
    }

    /**
     * A dialog that does something when it is dismissed as well.
     *
     * Saying no has to mean something wherever the thing behind the dialog keeps running: the Minecraft link waits
     * for an answer, and a dialog whose Cancel only closed the window left that wait in place, so the next poll
     * opened the dialog again - once every four hundred milliseconds, with no way out but leaving the screen.
     */
    public static WorkshopConfirmScreen confirm(Screen parent, Component title, Component body, Component confirmLabel,
                                                Runnable action, Runnable onCancel) {
        return new WorkshopConfirmScreen(parent, title, body, confirmLabel, action, onCancel, false);
    }
}
