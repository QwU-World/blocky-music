package ru.gora.musicbattle.entity;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
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
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.NpcSkinPng;
import ru.gora.musicbattle.registry.ModItems;
import ru.gora.musicbattle.server.BattleManager;

public final class TestRapGodMasterEntity extends PathfinderMob {
    public static final String NPC_NAME = "TEST_RAP_GOD_MASTER";
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<UUID>> SKIN_UUID = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> RANDOM_VOTES = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SLIM_SKIN = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PUSHABLE_WHEN_STATIONARY = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * A skin uploaded from a file: its SHA-256 and the PNG itself, or an empty tag. It travels in the synched data
     * rather than in a payload of its own, so every client that starts tracking the NPC gets the picture in the same
     * packets as the rest of the NPC, and no client ever knows the hash without having the bytes. The PNG is checked by
     * {@link NpcSkinPng} before it gets here and is at most {@link NpcSkinPng#MAX_BYTES}.
     */
    private static final EntityDataAccessor<CompoundTag> SKIN_FILE = SynchedEntityData.defineId(TestRapGodMasterEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final String SKIN_FILE_HASH = "sha256";
    private static final String SKIN_FILE_PNG = "png";
    /** The server's own copy, saved with the NPC, so the skin is still there after a restart. */
    private static final String SAVED_SKIN_FILE = "BlockyMusicSkinPng";

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
        builder.define(SKIN_FILE, new CompoundTag());
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
        byte[] skinFile = getSkinFilePng();
        if (skinFile != null) {
            tag.putByteArray(SAVED_SKIN_FILE, skinFile);
        }
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
        restoreSkinFile(tag);
    }

    /**
     * Saved data is checked again rather than trusted: {@code /data merge} and hand-edited worlds reach this method
     * too, and whatever is set here is sent to every client that sees the NPC.
     */
    private void restoreSkinFile(CompoundTag tag) {
        if (!tag.contains(SAVED_SKIN_FILE, Tag.TAG_BYTE_ARRAY)) {
            clearSkinFile();
            return;
        }
        byte[] png = tag.getByteArray(SAVED_SKIN_FILE);
        NpcSkinPng.Result check = NpcSkinPng.check(png);
        if (check.ok()) {
            setSkinFile(png);
        } else {
            MusicBattleMod.LOGGER.warn("Dropping the saved skin of test NPC {}: {} ({}x{}, {} bytes)",
                    getUUID(), check.problem(), check.width(), check.height(), png.length);
            clearSkinFile();
        }
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

    /**
     * Wears a skin from a file. The caller has already passed the bytes through {@link NpcSkinPng#check}; the hash is
     * worked out here so it always belongs to these exact bytes.
     */
    public void setSkinFile(byte[] png) {
        CompoundTag skin = new CompoundTag();
        skin.putString(SKIN_FILE_HASH, NpcSkinPng.sha256(png));
        skin.putByteArray(SKIN_FILE_PNG, png.clone());
        entityData.set(SKIN_FILE, skin);
    }

    public void clearSkinFile() {
        if (hasSkinFile()) {
            entityData.set(SKIN_FILE, new CompoundTag());
        }
    }

    public boolean hasSkinFile() {
        return !getSkinFileHash().isEmpty();
    }

    /** SHA-256 of the file skin, or an empty string when the NPC has none. */
    public String getSkinFileHash() {
        return entityData.get(SKIN_FILE).getString(SKIN_FILE_HASH);
    }

    /** The file skin's PNG, or null. The array is the synched value itself: read it, never change it. */
    public byte[] getSkinFilePng() {
        CompoundTag skin = entityData.get(SKIN_FILE);
        return skin.contains(SKIN_FILE_PNG, Tag.TAG_BYTE_ARRAY) ? skin.getByteArray(SKIN_FILE_PNG) : null;
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

    /**
     * Lets a shove move the NPC while its AI is off. {@code LivingEntity#travel} only moves an entity "controlled by
     * this side", which for a mob means {@code Mob#isEffectiveAi}, and that is false for every mob with no AI. A push
     * therefore only ever set a velocity that nothing applied, and it decayed to nothing a few ticks later: the
     * "pushable while standing still" option could never work. Answering yes on the server, for exactly that case,
     * runs vanilla's own movement - gravity, friction, collisions, water - while the goals stay off, because those are
     * still gated on {@code isEffectiveAi}.
     *
     * <p>A battle holds its fighters on their spots itself, every tick; a push there would only make the NPC jitter
     * against that hold, so a fighting NPC keeps still.
     */
    @Override
    public boolean isControlledByLocalInstance() {
        return super.isControlledByLocalInstance() || canBeShovedWhileStationary();
    }

    private boolean canBeShovedWhileStationary() {
        return !level().isClientSide && isNoAi() && isPushableWhenStationary() && !BattleManager.isEntityInBattle(this);
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
        // The same rule when the other entity starts the collision. The push itself needs nothing more: the server
        // moves the NPC (see isControlledByLocalInstance) and sends its position like any other mob's. Forcing a
        // velocity packet on the other entity, as this used to, made the server overwrite a player's own movement on
        // every tick they stood against the NPC.
        if (isNoAi() && !isPushableWhenStationary()) {
            return;
        }
        super.push(other);
    }

    /**
     * Entity#isInvulnerableTo lets a hit from a player in creative mode through the invulnerable flag, and the NPC is
     * set up with a wand that only exists in creative, so "Invulnerable" did nothing for the very person who switched
     * it on. Here it holds against everything except damage that is defined to bypass invulnerability - /kill and the
     * void - so a stuck NPC can always be removed.
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (isInvulnerable() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    /** An invulnerable NPC does not catch fire either; flames it cannot be hurt by would only look like it was. */
    @Override
    public boolean fireImmune() {
        return isInvulnerable() || super.fireImmune();
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
