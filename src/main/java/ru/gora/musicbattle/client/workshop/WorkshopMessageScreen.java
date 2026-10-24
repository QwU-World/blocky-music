package ru.gora.musicbattle.client.workshop;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;

/** A one-button Workshop message (invalid package, install failure, hints). Returns to the screen underneath. */
public final class WorkshopMessageScreen extends WorkshopDialogScreen {
    public WorkshopMessageScreen(Screen parent, Component title, Component body) {
        super(parent, title, UiIcon.INFO, Palette.ACCENT);
        addParagraph(body);
        addAction(CommonComponents.GUI_OK, WorkshopButton.Kind.PRIMARY, null, this::onClose);
    }
}
