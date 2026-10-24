package ru.gora.musicbattle.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.registry.ModItems;
import net.minecraft.world.entity.Entity;

/** Hidden registry alias retained solely to migrate old saves without losing components. */
public final class SoloMicrophoneItem extends Item {
    public SoloMicrophoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    public static ItemStack migrate(ItemStack stack) {
        return stack.is(ModItems.SOLO_MICROPHONE.get()) ? stack.transmuteCopy(ModItems.MICROPHONE.get()) : stack;
    }

    public static void migrateInventory(Player player) {
        for (int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            ItemStack old=player.getInventory().getItem(slot), replacement=migrate(old);
            if(old!=replacement)player.getInventory().setItem(slot,replacement);
        }
    }

    @Override public void inventoryTick(ItemStack stack,Level level,Entity entity,int slot,boolean selected) {
        if(!level.isClientSide && entity instanceof Player player) migrateInventory(player);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer) player.setItemInHand(hand,migrate(player.getItemInHand(hand)));
        return ModItems.MICROPHONE.get().use(level,player,hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.music_battle.microphone.solo_hint").withStyle(ChatFormatting.AQUA));
    }
}
