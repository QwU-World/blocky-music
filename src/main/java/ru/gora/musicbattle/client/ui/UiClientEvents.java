package ru.gora.musicbattle.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import ru.gora.musicbattle.MusicBattleMod;

/** Game-bus hooks of the UI toolkit: per-frame cover uploads, preview bookkeeping, cursor safety, debug command. */
@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class UiClientEvents {
    private UiClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        CoverCache.pump();
        PreviewPlayer.update();
        if (HandCursor.current() != HandCursor.Kind.ARROW && !(Minecraft.getInstance().screen instanceof UiLayer)) {
            HandCursor.reset();
        }
    }

    @SubscribeEvent
    public static void onStreamingStarted(PlayStreamingSourceEvent event) {
        PreviewPlayer.onStreamingStarted(event);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PreviewPlayer.stop();
    }

    /** {@code /musicbattle debugui}: runs on the client; every other {@code /musicbattle} command still goes to the server. */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("musicbattle")
                .then(Commands.literal("debugui").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    // Deferred: the chat screen closes itself after running a command.
                    minecraft.tell(() -> minecraft.setScreen(new WorkshopUiDebugScreen(null)));
                    return 1;
                })));
    }
}
