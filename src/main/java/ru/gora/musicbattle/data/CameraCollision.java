package ru.gora.musicbattle.data;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared client/server collision rule for the optical point of an edited camera. */
public final class CameraCollision {
    private static final double WALL_MARGIN = 0.08D;

    private CameraCollision() {
    }

    public static Vec3 resolve(Level level, Entity context, Vec3 from, Vec3 desired) {
        if (level == null || context == null || from == null || desired == null
                || from.distanceToSqr(desired) <= 1.0E-12D) {
            return from == null ? Vec3.ZERO : from;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                from, desired, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
        return hit.getType() == HitResult.Type.MISS ? desired : stopBeforeHit(from, desired, hit.getLocation());
    }

    public static Vec3 stopBeforeHit(Vec3 from, Vec3 desired, Vec3 hit) {
        Vec3 movement = desired.subtract(from);
        double length = movement.length();
        if (!Double.isFinite(length) || length <= 1.0E-9D) return from;
        double hitDistance = hit.subtract(from).dot(movement.scale(1.0D / length));
        double allowedDistance = Math.max(0.0D, Math.min(length, hitDistance - WALL_MARGIN));
        return from.add(movement.scale(allowedDistance / length));
    }
}
