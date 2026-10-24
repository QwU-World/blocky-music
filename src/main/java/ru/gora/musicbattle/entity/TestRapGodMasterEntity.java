package ru.gora.musicbattle.entity;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import ru.gora.musicbattle.registry.ModItems;

public final class TestRapGodMasterEntity extends PathfinderMob {
    public static final String NPC_NAME = "TEST_RAP_GOD_MASTER";
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<UUID>> SKIN_UUID = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> RANDOM_VOTES = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SLIM_SKIN = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PUSHABLE_WHEN_STATIONARY = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.BOOLEAN);

    public TestRapGodMasterEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        setCustomName(Component.literal(NPC_NAME));
        setCustomNameVisible(true);
        setPersistenceRequired();
        ensureMicrophone();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.FOLLOW_RANGE, 16.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new RandomStrollGoal(this, 0.9D));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN_NAME, "");
        builder.define(SKIN_UUID, Optional.empty());
        builder.define(RANDOM_VOTES, true);
        builder.define(SLIM_SKIN, false);
        builder.define(PUSHABLE_WHEN_STATIONARY, true);
    }

    @Override
    public void tick() {
        super.tick();
        if (getCustomName() == null) {
            setCustomName(Component.literal(NPC_NAME));
        }
        setCustomNameVisible(true);
        ensureMicrophone();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("BlockyMusicSkinName", getSkinName());
        getSkinUuid().ifPresent(uuid -> tag.putUUID("BlockyMusicSkinUuid", uuid));
        tag.putBoolean("BlockyMusicRandomVotes", hasRandomVotes());
        tag.putBoolean("BlockyMusicGlowing", isCurrentlyGlowing());
        tag.putBoolean("BlockyMusicSlimSkin", isSlimSkin());
        tag.putBoolean("BlockyMusicPushableWhenStationary", isPushableWhenStationary());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        String skinName = tag.getString("BlockyMusicSkinName");
        Optional<UUID> skinUuid = tag.hasUUID("BlockyMusicSkinUuid") ? Optional.of(tag.getUUID("BlockyMusicSkinUuid")) : Optional.empty();
        entityData.set(SKIN_NAME, skinName);
        entityData.set(SKIN_UUID, skinUuid);
        entityData.set(RANDOM_VOTES, !tag.contains("BlockyMusicRandomVotes") || tag.getBoolean("BlockyMusicRandomVotes"));
        entityData.set(SLIM_SKIN, tag.getBoolean("BlockyMusicSlimSkin"));
        entityData.set(PUSHABLE_WHEN_STATIONARY, !tag.contains("BlockyMusicPushableWhenStationary") || tag.getBoolean("BlockyMusicPushableWhenStationary"));
        setGlowingTag(tag.getBoolean("BlockyMusicGlowing"));
    }

    public String getSkinName() {
        return entityData.get(SKIN_NAME);
    }

    public Optional<UUID> getSkinUuid() {
        return entityData.get(SKIN_UUID);
    }

    public void setSkinProfile(String name, UUID uuid) {
        entityData.set(SKIN_NAME, name == null ? "" : name);
        entityData.set(SKIN_UUID, Optional.ofNullable(uuid));
    }

    /** Keeps the requested nickname visible and persistent while Mojang profile lookup is running. */
    public void setPendingSkinName(String name) {
        entityData.set(SKIN_NAME, name == null ? "" : name);
        entityData.set(SKIN_UUID, Optional.empty());
    }

    public void clearSkinProfile() {
        entityData.set(SKIN_NAME, "");
        entityData.set(SKIN_UUID, Optional.empty());
    }

    public boolean hasRandomVotes() {
        return entityData.get(RANDOM_VOTES);
    }

    public void setRandomVotes(boolean randomVotes) {
        entityData.set(RANDOM_VOTES, randomVotes);
    }

    public boolean isSlimSkin() {
        return entityData.get(SLIM_SKIN);
    }

    public void setSlimSkin(boolean slimSkin) {
        entityData.set(SLIM_SKIN, slimSkin);
    }

    public boolean isPushableWhenStationary() {
        return entityData.get(PUSHABLE_WHEN_STATIONARY);
    }

    public void setPushableWhenStationary(boolean pushable) {
        entityData.set(PUSHABLE_WHEN_STATIONARY, pushable);
    }

    @Override
    public boolean isPushable() {
        return super.isPushable() && (!isNoAi() || isPushableWhenStationary());
    }

    @Override
    protected void pushEntities() {
        // LivingEntity#pushEntities is initiated by both colliding entities.  Merely
        // returning false from isPushable() therefore is not enough: a stationary NPC
        // could still initiate the collision from its own tick.  Keep both directions
        // governed by the same menu option.
        if (isNoAi() && !isPushableWhenStationary()) {
            return;
        }
        super.pushEntities();
    }

    @Override
    public void push(net.minecraft.world.entity.Entity other) {
        if (isNoAi() && !isPushableWhenStationary()) {
            return;
        }
        super.push(other);
        if (!level().isClientSide) {
            // No-AI mobs still use vanilla collision physics, but their motion
            // is not always followed by an AI update that forces an immediate
            // velocity packet. Mark both participants so the local player and
            // remote clients see the same push instead of the server silently
            // correcting them back on the next movement packet.
            hurtMarked = true;
            other.hurtMarked = true;
        }
    }

    public void setNpcDisplayName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        setCustomName(Component.literal(normalized.isBlank() ? NPC_NAME : normalized));
        setCustomNameVisible(true);
    }

    private void ensureMicrophone() {
        ItemStack stack = getItemBySlot(EquipmentSlot.MAINHAND);
        if (!stack.is(ModItems.MICROPHONE.get())) {
            setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ModItems.MICROPHONE.get()));
        }
    }
}
