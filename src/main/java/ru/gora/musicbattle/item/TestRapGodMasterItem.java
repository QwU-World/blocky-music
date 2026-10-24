package ru.gora.musicbattle.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.registry.ModEntities;

public final class TestRapGodMasterItem extends Item {
    public TestRapGodMasterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        TestRapGodMasterEntity npc = ModEntities.TEST_RAP_GOD_MASTER.get().create(level, null, pos, MobSpawnType.SPAWN_EGG, true, true);
        if (npc == null) {
            return InteractionResult.FAIL;
        }
        Vec3 center = Vec3.atBottomCenterOf(pos);
        npc.moveTo(center.x, center.y, center.z, context.getPlayer() == null ? 0.0F : context.getPlayer().getYRot() + 180.0F, 0.0F);
        npc.setNoAi(true);
        npc.setCustomName(Component.literal(TestRapGodMasterEntity.NPC_NAME));
        npc.setCustomNameVisible(true);
        level.addFreshEntity(npc);
        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
