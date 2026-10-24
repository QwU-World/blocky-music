package ru.gora.musicbattle.entity;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.data.MusicBeatMath;
import ru.gora.musicbattle.data.SmileyDancePattern;
import ru.gora.musicbattle.data.SmileyWalkAnimation;
import ru.gora.musicbattle.server.MusicMobRhythm;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.registry.ModEntities;
import ru.gora.musicbattle.registry.ModSounds;

/** Friendly, dyeable beat dancers. Music changes the actual entity position and hitbox. */
public final class SmileyBallEntity extends Animal {
    private static final int[] INITIAL_COLORS = {
            0xFFE34F, 0xF5263E, 0x335CC9, 0x7147B8, 0xDC3096,
            0xFF9825, 0xB7DF35, 0x35BD55, 0x23BBD8
    };
    private static final EntityDataAccessor<Integer> BALL_COLOR = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DANCING = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> BATTLE_CONTROLLED = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> BEAT_PHASE = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BEAT_BPM = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Long> BEAT_GAME_TICK = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> DANCE_FLOOR_Y = SynchedEntityData.defineId(SmileyBallEntity.class, EntityDataSerializers.FLOAT);
    private static final double FLOOR_EPSILON = 0.001D;
    /** About two default speaker radii: the crowd on a dance floor turns to the booth, a ball across the map does not. */
    private static final double DJ_LOOK_RADIUS = 28.0D;
    private UUID rhythmSource;
    private double lastTrackTicks;
    private long lastSampleGameTick;
    private double previousTravelPhase = -1.0D;
    private UUID dancePatternId;
    private SmileyDancePattern dancePattern;
    private final SmileyWalkAnimation walkingHop = new SmileyWalkAnimation();

    public SmileyBallEntity(EntityType<? extends Animal> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (!level.isClientSide) {
            entityData.set(BALL_COLOR, INITIAL_COLORS[random.nextInt(INITIAL_COLORS.length)]);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.16D)
                .add(Attributes.FOLLOW_RANGE, 12.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(BALL_COLOR, INITIAL_COLORS[0]);
        builder.define(DANCING, false);
        builder.define(BATTLE_CONTROLLED, false);
        builder.define(BEAT_PHASE, 0.0F);
        builder.define(BEAT_BPM, 120.0F);
        builder.define(BEAT_GAME_TICK, 0L);
        builder.define(DANCE_FLOOR_Y, 0.0F);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new BreedGoal(this, 1.0D));
        goalSelector.addGoal(2, new Goal() {
            private net.minecraft.world.entity.LivingEntity target;
            { setFlags(java.util.EnumSet.of(Flag.LOOK)); }

            @Override public boolean canUse() {
                target = BattleManager.audienceLookTarget(SmileyBallEntity.this);
                return target != null;
            }
            @Override public boolean canContinueToUse() { return canUse(); }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void tick() {
                if (target == null) return;
                getLookControl().setLookAt(target, 30.0F, 30.0F);
                float yaw = (float) (Math.atan2(target.getZ() - getZ(), target.getX() - getX()) * 180.0D / Math.PI) - 90.0F;
                setYRot(net.minecraft.util.Mth.approachDegrees(getYRot(), yaw, 20.0F));
                setYBodyRot(getYRot());
            }
        });
        // Below the battle audience goal, so an audible battle still wins the ball's gaze over a DJ set.
        goalSelector.addGoal(3, new Goal() {
            private BlockPos panel;
            private int nextSearchTick;
            { setFlags(java.util.EnumSet.of(Flag.LOOK)); }

            @Override public boolean canUse() {
                if (!(level() instanceof ServerLevel serverLevel) || BattleManager.isEntityInBattle(SmileyBallEntity.this)) {
                    panel = null;
                    return false;
                }
                if (panel != null && !isPlayingDjPanel(serverLevel, panel)) {
                    panel = null;
                }
                // Scanning chunks is the costly part, so it runs about once a second per ball, staggered by id.
                if (panel == null && tickCount >= nextSearchTick) {
                    nextSearchTick = tickCount + 20 + Math.floorMod(getId(), 10);
                    panel = findPlayingDjPanel(serverLevel);
                }
                return panel != null;
            }
            @Override public boolean canContinueToUse() { return canUse(); }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void stop() { panel = null; }
            @Override public void tick() {
                if (panel == null) return;
                Vec3 target = Vec3.atCenterOf(panel);
                getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F);
                float yaw = (float) (Math.atan2(target.z - getZ(), target.x - getX()) * 180.0D / Math.PI) - 90.0F;
                setYRot(net.minecraft.util.Mth.approachDegrees(getYRot(), yaw, 20.0F));
                setYBodyRot(getYRot());
            }
        });
        goalSelector.addGoal(3, new FollowParentGoal(this, 1.0D));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.65D) {
            @Override
            public boolean canUse() {
                return !isDancing() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !isDancing() && super.canContinueToUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    private boolean isPlayingDjPanel(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && position().distanceToSqr(Vec3.atCenterOf(pos)) <= DJ_LOOK_RADIUS * DJ_LOOK_RADIUS
                && level.getBlockEntity(pos) instanceof DjPanelBlockEntity panel
                && panel.isPlaying() && !panel.isPlaybackPaused();
    }

    /** The nearest DJ panel that is playing within {@link #DJ_LOOK_RADIUS}, looking only at loaded chunks. */
    private BlockPos findPlayingDjPanel(ServerLevel level) {
        int radius = (int) Math.ceil(DJ_LOOK_RADIUS);
        int minChunkX = SectionPos.blockToSectionCoord(getBlockX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(getBlockX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(getBlockZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(getBlockZ() + radius);
        BlockPos nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof DjPanelBlockEntity panel) || !panel.isPlaying() || panel.isPlaybackPaused()) continue;
                    double distance = position().distanceToSqr(Vec3.atCenterOf(panel.getBlockPos()));
                    if (distance <= DJ_LOOK_RADIUS * DJ_LOOK_RADIUS && distance < nearestDistance) {
                        nearest = panel.getBlockPos();
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    @Override
    public void tick() {
        if (level() instanceof ServerLevel serverLevel) {
            updateRhythm(serverLevel);
        }
        super.tick();
        if (level().isClientSide) {
            // The visual cadence follows resolved/network-interpolated movement, not desired
            // navigation speed. No vertical displacement, AI or movement packets are changed.
            walkingHop.tick(getX(), getZ(), onGround(), canAnimateWalking());
        }
    }

    private void updateRhythm(ServerLevel serverLevel) {
        // Battle hits use the same synchronized note actions as other mob models.
        // Ambient physical dancing must not run concurrently with that animation.
        MusicMobRhythm.Beat beat = !canDanceHere() || BattleManager.isEntityInBattle(this)
                ? null : MusicMobRhythm.sample(serverLevel, position(), rhythmSource);
        if (beat == null || !Double.isFinite(beat.bpm()) || beat.bpm() <= 0.0D || !Double.isFinite(beat.trackTicks())) {
            stopDancing();
            return;
        }
        if (!isDancing()) {
            // A spawned/falling ball must land naturally before acquiring its dance floor.
            if (!onGround() && !entityData.get(BATTLE_CONTROLLED) || !hasDanceFloor(getY())) {
                return;
            }
            entityData.set(DANCE_FLOOR_Y, (float) getY());
            previousTravelPhase = -1.0D;
        } else if (!hasDanceFloor(entityData.get(DANCE_FLOOR_Y))
                || Math.abs(getY() - entityData.get(DANCE_FLOOR_Y)) > SmileyDancePattern.MAX_HOP_HEIGHT + 0.25D) {
            // Broken floor, knockback or teleport: relinquish vertical control to gravity.
            stopDancing();
            return;
        }

        long gameTick = serverLevel.getGameTime();
        double phase = MusicBeatMath.phase(beat.trackTicks(), beat.bpm());
        boolean seek = Math.abs(beat.trackTicks() - lastTrackTicks - (gameTick - lastSampleGameTick)) > 1.25D;
        if (!isDancing() || !Objects.equals(rhythmSource, beat.sourceId()) || seek
                || Math.abs(entityData.get(BEAT_BPM) - beat.bpm()) > 0.001D
                || MusicBeatMath.phaseDistance(phase, beatPhaseAt(gameTick)) > 0.025D
                || gameTick - entityData.get(BEAT_GAME_TICK) >= 100L) {
            entityData.set(BEAT_PHASE, (float) phase);
            entityData.set(BEAT_BPM, (float) beat.bpm());
            entityData.set(BEAT_GAME_TICK, gameTick);
        }
        entityData.set(DANCING, true);
        rhythmSource = beat.sourceId();
        lastTrackTicks = beat.trackTicks();
        lastSampleGameTick = gameTick;
        getNavigation().stop();
        setSpeed(0.0F);
        setJumping(false);
        xxa = 0.0F;
        zza = 0.0F;
    }

    private boolean canDanceHere() {
        boolean battleControlled = entityData.get(BATTLE_CONTROLLED);
        return isAlive() && (battleControlled || !isNoAi() && !isNoGravity() && !isInLove())
                && !isPassenger() && !isInWaterOrBubble() && !isInLava() && !isLeashed() && hurtTime == 0;
    }

    /** Keeps vanilla AI frozen while permitting our collision-resolved dance. */
    public void setBattleDanceControlled(boolean controlled) {
        entityData.set(BATTLE_CONTROLLED, controlled);
        stopDancing();
        if (controlled && hasDanceFloor(getY())) {
            setOnGround(true);
        }
    }

    private void stopDancing() {
        entityData.set(DANCING, false);
        rhythmSource = null;
        previousTravelPhase = -1.0D;
    }

    private boolean hasDanceFloor(double floorY) {
        AABB bounds = getBoundingBox();
        AABB feet = new AABB(bounds.minX + 0.02D, floorY - 0.025D, bounds.minZ + 0.02D,
                bounds.maxX - 0.02D, floorY + FLOOR_EPSILON, bounds.maxZ - 0.02D);
        return level().getBlockCollisions(this, feet).iterator().hasNext();
    }

    /** A controlled music hop is not a natural fall; crowd checks use its still-supported floor. */
    public double crowdPushFloorY() {
        double floor = entityData.get(DANCE_FLOOR_Y);
        return isDancing() && canDanceHere() && hasDanceFloor(floor)
                && getY() >= floor - 0.01D && getY() <= floor + SmileyDancePattern.MAX_HOP_HEIGHT + 0.01D
                ? floor : Double.NaN;
    }

    @Override
    public void travel(Vec3 input) {
        if (!isDancing() || !canDanceHere() || !hasDanceFloor(entityData.get(DANCE_FLOOR_Y))) {
            previousTravelPhase = -1.0D;
            super.travel(input);
            return;
        }

        double floorY = entityData.get(DANCE_FLOOR_Y);
        double phase = dancePhaseAt(level().getGameTime());
        // Sampled beats can fall between game ticks (e.g. 137 BPM). Resolve the
        // intervening floor contact before ascending, so every beat really lands.
        if (previousTravelPhase >= 0.0D && phase < previousTravelPhase) {
            move(MoverType.SELF, new Vec3(0.0D, floorY - getY() - FLOOR_EPSILON, 0.0D));
        }
        Vec3 velocity = getDeltaMovement();
        double targetY = floorY + dancePattern().hopHeight(phase);
        move(MoverType.SELF, new Vec3(velocity.x, targetY - getY() - FLOOR_EPSILON, velocity.z));
        // Vertical velocity is never integrated into the next hop. Collision
        // clipping (including a low ceiling) therefore cannot cause upward drift.
        Vec3 clippedVelocity = getDeltaMovement();
        setDeltaMovement(clippedVelocity.x * 0.5D, 0.0D, clippedVelocity.z * 0.5D);
        resetFallDistance();
        calculateEntityAnimation(false);
        previousTravelPhase = phase;
    }

    public int getBallColor() {
        return entityData.get(BALL_COLOR);
    }

    public boolean isDancing() {
        return entityData.get(DANCING);
    }

    public boolean isBattleDanceControlled() {
        return entityData.get(BATTLE_CONTROLLED);
    }

    public SmileyWalkAnimation.Pose getWalkingPose(float partialTick) {
        return onGround() && canAnimateWalking() ? walkingHop.sample(partialTick) : SmileyWalkAnimation.Pose.REST;
    }

    private boolean canAnimateWalking() {
        return isAlive() && !isDancing() && !isBattleDanceControlled() && !isNoAi() && !isNoGravity()
                && !isPassenger() && !isInWaterOrBubble() && !isInLava() && hurtTime == 0;
    }

    /** Matches the previous/current tick interpolation used for entity positions. */
    public float getDancePhase(float partialTick) {
        return isDancing() ? Math.min(Math.nextDown(1.0F), (float) dancePhaseAt(level().getGameTime() - 1.0D + partialTick)) : 0.0F;
    }

    private double dancePhaseAt(double gameTick) {
        return dancePattern().phase(beatPhaseAt(gameTick));
    }

    private double beatPhaseAt(double gameTick) {
        return MusicBeatMath.phaseAt(entityData.get(BEAT_PHASE), entityData.get(BEAT_GAME_TICK), gameTick, entityData.get(BEAT_BPM));
    }

    private SmileyDancePattern dancePattern() {
        UUID entityId = getUUID();
        // UUID is finalized by save loading / the spawn packet after construction.
        if (!entityId.equals(dancePatternId)) {
            dancePatternId = entityId;
            dancePattern = SmileyDancePattern.forEntity(entityId);
        }
        return dancePattern;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.getItem() instanceof DyeItem dye) {
            int color = dye.getDyeColor().getTextureDiffuseColor() & 0xFFFFFF;
            if (getBallColor() != color) {
                if (!level().isClientSide) {
                    entityData.set(BALL_COLOR, color);
                    if (!player.getAbilities().instabuild) {
                        stack.shrink(1);
                    }
                }
            }
            // Same-color sneak clicks must not accidentally feed the animal.
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (isFood(stack) && (entityData.get(BATTLE_CONTROLLED) || BattleManager.isEntityInBattle(this))) {
            return InteractionResult.FAIL;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return stack.getItem() instanceof DyeItem || stack.is(Tags.Items.DYES);
    }

    @Override
    public boolean canMate(Animal partner) {
        return !BattleManager.isEntityInBattle(this) && !BattleManager.isEntityInBattle(partner)
                && super.canMate(partner);
    }

    @Override
    public SmileyBallEntity getBreedOffspring(ServerLevel level, AgeableMob partner) {
        SmileyBallEntity child = ModEntities.SMILEY_BALL.get().create(level);
        if (child != null) {
            int color = partner instanceof SmileyBallEntity other && random.nextBoolean()
                    ? other.getBallColor() : getBallColor();
            child.entityData.set(BALL_COLOR, color);
        }
        return child;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return ModSounds.SMILEY_BALL_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.SMILEY_BALL_DEATH.get();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("BlockyMusicBallColor", getBallColor());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("BlockyMusicBallColor", 3)) {
            entityData.set(BALL_COLOR, tag.getInt("BlockyMusicBallColor") & 0xFFFFFF);
        }
        setPersistenceRequired();
        stopDancing();
    }
}
