package ru.gora.musicbattle.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Font presets shared by the FX inspector, its preview and live gameplay. */
public final class BattleTextFonts {
    private static final ResourceLocation DEFAULT = ResourceLocation.withDefaultNamespace("default");
    private static final ResourceLocation UNIFORM = ResourceLocation.withDefaultNamespace("uniform");
    private static final ResourceLocation ALT = ResourceLocation.withDefaultNamespace("alt");
    private static final ResourceLocation ILLAGER = ResourceLocation.withDefaultNamespace("illageralt");

    private static final List<FontPreset> PRESETS = List.of(
            new FontPreset("minecraft:default", "music_battle.l10n.fx.font.default", DEFAULT, false, false, 1.0F),
            new FontPreset("minecraft:uniform", "music_battle.l10n.fx.font.uniform", UNIFORM, false, false, 1.0F),
            new FontPreset("minecraft:alt", "music_battle.l10n.fx.font.enchanting", ALT, false, false, 1.0F),
            new FontPreset("minecraft:illageralt", "music_battle.l10n.fx.font.illager", ILLAGER, false, false, 1.0F),
            new FontPreset("music_battle:bold", "music_battle.l10n.fx.font.bold", DEFAULT, true, false, 1.0F),
            new FontPreset("music_battle:italic", "music_battle.l10n.fx.font.italic", DEFAULT, false, true, 1.0F),
            new FontPreset("music_battle:bold_italic", "music_battle.l10n.fx.font.bold_italic", DEFAULT, true, true, 1.0F),
            new FontPreset("music_battle:narrow", "music_battle.l10n.fx.font.narrow", UNIFORM, false, false, 0.72F),
            new FontPreset("music_battle:wide", "music_battle.l10n.fx.font.wide", DEFAULT, true, false, 1.28F),
            new FontPreset("music_battle:retro", "music_battle.l10n.fx.font.retro", UNIFORM, true, false, 0.88F),
            new FontPreset("music_battle:arcade", "music_battle.l10n.fx.font.arcade", DEFAULT, true, false, 0.82F),
            new FontPreset("music_battle:poster", "music_battle.l10n.fx.font.poster", DEFAULT, true, false, 1.42F)
    );

    private BattleTextFonts() {
    }

    public static List<FontPreset> presets() {
        return PRESETS;
    }

    public static FontPreset resolve(String id) {
        for (FontPreset preset : PRESETS) {
            if (preset.id().equals(id)) {
                return preset;
            }
        }
        try {
            ResourceLocation resource = ResourceLocation.parse(id == null || id.isBlank() ? "minecraft:default" : id);
            return new FontPreset(resource.toString(), resource.getPath(), resource, false, false, 1.0F);
        } catch (RuntimeException ignored) {
            return PRESETS.getFirst();
        }
    }

    public static Component styledText(String text, String presetId) {
        FontPreset preset = resolve(presetId);
        return Component.literal(text).withStyle(style -> style.withFont(preset.baseFont())
                .withBold(preset.bold()).withItalic(preset.italic()));
    }

    public static void drawCentered(GuiGraphics graphics, Font font, String text, String presetId,
                                    float centerX, float y, int color, int outlineColor, float outlineWidth) {
        FontPreset preset = resolve(presetId);
        Component component = styledText(text, preset.id());
        int textLeft = -font.width(component) / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0.0F);
        graphics.pose().scale(preset.widthScale(), 1.0F, 1.0F);
        float width = Mth.clamp(outlineWidth, 0.0F, 5.0F);
        if (width > 0.01F) {
            int radius = Mth.ceil(width);
            float limit = (width + 0.35F) * (width + 0.35F);
            for (int offsetY = -radius; offsetY <= radius; offsetY++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    if ((offsetX != 0 || offsetY != 0) && offsetX * offsetX + offsetY * offsetY <= limit) {
                        graphics.drawString(font, component, textLeft + offsetX, offsetY, outlineColor, false);
                    }
                }
            }
        }
        graphics.drawString(font, component, textLeft, 0, color, false);
        graphics.pose().popPose();
    }

    public record FontPreset(String id, String displayName, ResourceLocation baseFont,
                             boolean bold, boolean italic, float widthScale) {
        public String displayName() {
            return I18n.exists(displayName) ? I18n.get(displayName) : displayName;
        }
    }
}
