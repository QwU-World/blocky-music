package ru.gora.musicbattle.mixin.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.gora.musicbattle.client.ClientSoundOptions;

@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin extends OptionsSubScreen {
    protected SoundOptionsScreenMixin(Screen parent, Options options, Component title) {
        super(parent, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void musicbattle$addMusicVolumeOption(CallbackInfo callback) {
        if (this.list != null) {
            this.list.addBig(ClientSoundOptions.musicVolumeOption());
        }
    }
}
