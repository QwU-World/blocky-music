package ru.gora.musicbattle.server;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.gora.musicbattle.data.BattlePlacementMath.Bounds;
import ru.gora.musicbattle.data.CrowdSafetyMath;

/** Server-thread crowd safety; independent of battle lifetime so a final fall can finish safely. */
final class BattleCrowdSafety {
    private static final double EPSILON = 1.0E-5D;
    // Weak keys and per-tick removal avoid retaining unloaded worlds or dead mobs.
    private static final Map<Mob, TrackedFall> FALLS = new WeakHashMap<>();

    private BattleCrowdSafety() { }

    static void push(Mob mob, Vec3 away) {
        double danceFloor = mob instanceof ru.gora.musicbattle.entity.SmileyBallEntity ball
                ? ball.crowdPushFloorY() : Double.NaN;
        boolean supportedDance = Double.isFinite(danceFloor);
        if (!(mob.level() instanceof ServerLevel level)
                || !CrowdSafetyMath.canDisplace(mob.onGround(), mob.fallDistance, supportedDance)
                || mob.isPassenger() || mob.isVehicle()) return;
        mob.getNavigation().stop();
        Vec3 current = mob.getDeltaMovement();
        AABB body = mob.getBoundingBox();
        double lift = supportedDance ? Math.max(0, body.minY - danceFloor) : 0;
        if (supportedDance) body = body.move(0, danceFloor - body.minY, 0);
        CrowdSafetyMath.Velocity velocity = CrowdSafetyMath.safePush(
                new Bounds(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ),
                away.x, away.z, current.y,
                bounds -> clear(level, mob, box(bounds).expandTowards(0, lift, 0)), bounds -> supported(level, box(bounds)));
        if (velocity == null) {
            // Do not continue an earlier outward impulse into a corner or over a ledge.
            mob.setDeltaMovement(0.0D, current.y, 0.0D);
            mob.hurtMarked = true;
            return;
        }
        mob.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
        // Music already owns this hop; do not turn its mid-air repulsion into fall immunity.
        if (!supportedDance) mark(mob, level);
        mob.hurtMarked = true;
    }

    static void jump(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)
                || !CrowdSafetyMath.canDisplace(mob.onGround(), mob.fallDistance)
                || mob.isPassenger() || mob.isVehicle()) return;
        Vec3 current = mob.getDeltaMovement();
        AABB headroom = mob.getBoundingBox().expandTowards(0.0D, 0.7D, 0.0D);
        if (!clear(level, mob, headroom) || !safePath(level, mob, current.x, current.z)) return;
        mob.setDeltaMovement(current.x, 0.28D, current.z);
        mark(mob, level);
        mob.hurtMarked = true;
    }

    static void tick(long serverTick) {
        FALLS.entrySet().removeIf(entry -> {
            Mob mob = entry.getKey();
            return !entry.getValue().guard().update(serverTick, mob.onGround(), mob.isInWaterOrBubble() || mob.isInLava(),
                    valid(mob, entry.getValue()));
        });
    }

    static boolean absorbFall(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof Mob mob) || !source.is(DamageTypes.FALL)) return false;
        TrackedFall fall = FALLS.get(mob);
        if (fall == null) return false;
        if (!valid(mob, fall)) {
            FALLS.remove(mob);
            return false;
        }
        boolean absorbed = fall.guard().absorb(true);
        FALLS.remove(mob);
        return absorbed;
    }

    static void clear() { FALLS.clear(); }

    private static boolean valid(Mob mob, TrackedFall fall) {
        return mob.isAlive() && !mob.isRemoved() && !mob.isPassenger()
                && mob.level().dimension().equals(fall.dimension())
                // Also runs inside the damage hook: landing damage precedes ServerTick.Post.
                && fall.guard().observeMotion(mob.getX(), mob.getY(), mob.getZ(), mob.getDeltaMovement().y,
                        mob.hasEffect(MobEffects.LEVITATION));
    }

    private static void mark(Mob mob, ServerLevel level) {
        CrowdSafetyMath.FallGuard guard = new CrowdSafetyMath.FallGuard(level.getServer().getTickCount());
        if (guard.observeMotion(mob.getX(), mob.getY(), mob.getZ(), mob.getDeltaMovement().y,
                mob.hasEffect(MobEffects.LEVITATION))) {
            // Only grounded, non-falling mobs reach mark. A push followed by a crowd jump in
            // this same tick must start from the final authorized impulse, not the earlier Y.
            FALLS.put(mob, new TrackedFall(level.dimension(), guard));
        } else {
            FALLS.remove(mob);
        }
    }

    private static boolean safePath(ServerLevel level, Mob mob, double dx, double dz) {
        AABB body = mob.getBoundingBox();
        return CrowdSafetyMath.safePath(new Bounds(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ), dx, dz,
                box -> clear(level, mob, box(box)), box -> supported(level, box(box)));
    }

    private static boolean clear(ServerLevel level, Mob mob, AABB body) {
        var border = level.getWorldBorder();
        if (body.minY < level.getMinBuildHeight() || body.maxY >= level.getMaxBuildHeight()
                || body.minX < border.getMinX() || body.maxX > border.getMaxX()
                || body.minZ < border.getMinZ() || body.maxZ > border.getMaxZ()) return false;
        for (int x = Mth.floor(body.minX) >> 4; x <= Mth.floor(body.maxX) >> 4; x++) {
            for (int z = Mth.floor(body.minZ) >> 4; z <= Mth.floor(body.maxZ) >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, 0, z << 4))) return false;
            }
        }
        AABB inset = body.deflate(EPSILON);
        return level.noBlockCollision(mob, inset) && level.noBlockCollision(null, inset)
                && !level.containsAnyLiquid(inset) && !hazard(level, body.inflate(0, 0.05D, 0));
    }

    private static boolean supported(ServerLevel level, AABB body) {
        // Keep the center of mass over collision support. Requiring all four corners rejected
        // ordinary slab/fence edges and wide mobs despite a supported stopping trajectory.
        double x = (body.minX + body.maxX) * 0.5D;
        double z = (body.minZ + body.maxZ) * 0.5D;
        AABB foot = new AABB(x - EPSILON, body.minY - 0.04D, z - EPSILON,
                x + EPSILON, body.minY, z + EPSILON);
        return level.getBlockCollisions(null, foot).iterator().hasNext();
    }

    private static boolean hazard(ServerLevel level, AABB body) {
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(body.minY), Mth.floor(body.minZ),
                Mth.floor(body.maxX), Mth.floor(body.maxY), Mth.floor(body.maxZ))) {
            var state = level.getBlockState(pos);
            if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)) return true;
        }
        return false;
    }

    private static AABB box(Bounds box) {
        return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    private record TrackedFall(ResourceKey<Level> dimension, CrowdSafetyMath.FallGuard guard) { }
}
