package ru.gora.musicbattle.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.network.OpenRapGodConfigPayload;

public final class RapGodConfigWandItem extends Item {
    public RapGodConfigWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof TestRapGodMasterEntity npc)) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new OpenRapGodConfigPayload(
                    npc.getId(),
                    npc.getCustomName() == null ? TestRapGodMasterEntity.NPC_NAME : npc.getCustomName().getString(),
                    npc.getSkinName(),
                    npc.isSlimSkin(),
                    npc.isPushableWhenStationary(),
                    npc.isNoAi(),
                    npc.isInvulnerable(),
                    npc.isCurrentlyGlowing(),
                    npc.hasRandomVotes()
            ));
        }
        return InteractionResult.SUCCESS;
    }
}
