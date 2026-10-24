package ru.gora.musicbattle.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import ru.gora.musicbattle.registry.ModEntities;

public final class SmileyBallSpawnEggItem extends DeferredSpawnEggItem {
    public SmileyBallSpawnEggItem(Item.Properties properties) {
        super(ModEntities.SMILEY_BALL, 0xF5263E, 0xFFE34F, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.music_battle.smiley_ball_spawn_egg.flavor")
                .withStyle(ChatFormatting.GRAY));
    }
}
