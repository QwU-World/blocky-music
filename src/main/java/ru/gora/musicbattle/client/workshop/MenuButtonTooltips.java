package ru.gora.musicbattle.client.workshop;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * The toolkit's tooltip for the mod's buttons on vanilla's menus: "Workshop" on the title screen and in the pause menu,
 * "BM" there and on the options screen. The mod's own screens hand a widget's tooltip to a {@link UiTooltip.Deferred}
 * from their {@code setTooltipForNextRenderPass}; vanilla's menus cannot, so these buttons keep their hint out of
 * vanilla's tooltip holder, offer it here from their own render pass - when vanilla's holder would have shown it, under
 * the pointer or on keyboard focus - and the card is drawn once the whole screen has been, over every widget. The hint
 * is still read out by the narrator. These were the last menus with the purple vanilla frame in the pixel font.
 */
@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class MenuButtonTooltips {
    private static final UiTooltip.Deferred TOOLTIPS = new UiTooltip.Deferred();
    /**
     * One tooltip per hint, made once: the menus are set up again on every resize and every return to them, and the
     * holder keeps each tooltip it ever made.
     */
    private static final Map<String, Tooltip> HINTS = new HashMap<>();

    private MenuButtonTooltips() {
    }

    /** A vanilla button at {@code bounds} whose hint, {@code hintKey}'s text, is drawn as the toolkit's card. */
    public static Button button(Rect bounds, Component label, String hintKey, Button.OnPress onPress) {
        return new HintedButton((int) bounds.x(), (int) bounds.y(), (int) bounds.w(), (int) bounds.h(), label, hintKey, onPress);
    }

    /** The hint for a translation key, as a tooltip {@link #TOOLTIPS} recognises. */
    static Tooltip hint(String hintKey) {
        return HINTS.computeIfAbsent(hintKey, key -> TOOLTIPS.create(Component.translatable(key)));
    }

    /** After the menu has drawn everything, its own tooltip too: the card of the hint a button offered this frame. */
    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        TOOLTIPS.draw(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), screen.width, screen.height,
                screen.getFocused());
    }

    /** A vanilla button that offers its hint to {@link #TOOLTIPS} instead of vanilla's tooltip holder. */
    public static class HintedButton extends Button {
        private final Tooltip hint;

        protected HintedButton(int x, int y, int width, int height, Component label, String hintKey, OnPress onPress) {
            super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
            this.hint = hint(hintKey);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            // When vanilla's WidgetTooltipHolder would show it, with the positioner it would pick: a button the
            // keyboard reached, and the pointer is not on, gets its card under it rather than at the pointer.
            boolean keyboard = Minecraft.getInstance().getLastInputType().isKeyboard();
            if (isHovered() || isFocused() && keyboard) {
                TOOLTIPS.offer(hint, !isHovered() && isFocused() && keyboard
                        ? new BelowOrAboveWidgetTooltipPositioner(getRectangle()) : new MenuTooltipPositioner(getRectangle()));
            }
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            super.updateWidgetNarration(output);
            hint.updateNarration(output);
        }
    }
}
