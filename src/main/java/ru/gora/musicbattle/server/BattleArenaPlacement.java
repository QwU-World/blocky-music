package ru.gora.musicbattle.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ru.gora.musicbattle.data.BattlePlacementMath;
import ru.gora.musicbattle.data.BattlePlacementMath.Bounds;
import ru.gora.musicbattle.data.BattlePlacementMath.Candidate;
import ru.gora.musicbattle.data.SmileyDancePattern;
import ru.gora.musicbattle.entity.SmileyBallEntity;

/** Read-only, bounded placement: no block edits, chunk loads or partial teleports. */
final class BattleArenaPlacement {
    private static final double EPSILON = 1.0E-5D;
    private static final int SEARCH_RADIUS = 2;

    private BattleArenaPlacement() {
    }

    static Vec3[] find(ServerLevel level, LivingEntity left, LivingEntity right, Vec3[] preferred, boolean solo) {
        List<Candidate> leftCandidates = candidates(level, left, preferred[0]);
        if (solo) {
            // The invisible no-physics solo marker deliberately overlaps its performer.
            if (leftCandidates.isEmpty()) return null;
            Vec3 spot = position(leftCandidates.getFirst());
            return new Vec3[]{spot, spot.add(preferred[1].subtract(preferred[0]))};
        }
        return BattlePlacementMath.reserve(leftCandidates, candidates(level, right, preferred[1]))
                .map(pair -> new Vec3[]{position(pair.left()), position(pair.right())}).orElse(null);
    }

    static boolean safePair(ServerLevel level, LivingEntity left, LivingEntity right, Vec3 leftPos, Vec3 rightPos, boolean solo) {
        if (leftPos == null || rightPos == null || !safe(level, left, leftPos)) return false;
        return solo || safe(level, right, rightPos)
                && !bounds(left, leftPos).intersects(bounds(right, rightPos));
    }

    static boolean clearVotingAnchor(ServerLevel level, LivingEntity entity, Vec3 spot) {
        AABB body = entity.getBoundingBox().move(spot.subtract(entity.position()))
                .minmax(entity.getDimensions(Pose.STANDING).makeBoundingBox(spot));
        var border = level.getWorldBorder();
        return geometry(body).fits(level.getMinBuildHeight(), level.getMaxBuildHeight(),
                border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ())
                && loaded(level, body.inflate(EPSILON)) && level.noBlockCollision(entity, body.deflate(EPSILON))
                && level.noBlockCollision(null, body.deflate(EPSILON));
    }

    private static List<Candidate> candidates(ServerLevel level, LivingEntity entity, Vec3 preferred) {
        List<Candidate> result = new ArrayList<>();
        if (!geometry(bounds(entity, preferred)).usable() || entity.isPassenger() || entity.isVehicle()) return result;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                Vec3 column = preferred.add(dx, 0, dz);
                AABB body = bounds(entity, column);
                AABB floorSearch = new AABB(body.minX + EPSILON, body.minY - SEARCH_RADIUS - EPSILON, body.minZ + EPSILON,
                        body.maxX - EPSILON, body.minY + SEARCH_RADIUS + EPSILON, body.maxZ - EPSILON);
                if (!loaded(level, floorSearch)) continue;
                Set<Double> heights = new LinkedHashSet<>();
                heights.add(preferred.y);
                double feetOffset = body.minY - column.y;
                for (VoxelShape shape : level.getBlockCollisions(entity, floorSearch)) {
                    for (AABB part : shape.toAabbs()) {
                        double y = part.maxY - feetOffset;
                        if (Math.abs(y - preferred.y) <= SEARCH_RADIUS + EPSILON) heights.add(y);
                    }
                }
                for (double y : heights) {
                    Vec3 spot = new Vec3(column.x, y, column.z);
                    if (safe(level, entity, spot)) {
                        double vertical = y - preferred.y;
                        result.add(new Candidate(spot.x, spot.y, spot.z, geometry(bounds(entity, spot)),
                                dx * dx + dz * dz + vertical * vertical * 2.0D));
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(Candidate::cost));
        return result;
    }

    /** Called again before every held-anchor move, including pose/growth changes. */
    private static boolean safe(ServerLevel level, LivingEntity entity, Vec3 spot) {
        if (entity.isPassenger() || entity.isVehicle()) return false;
        AABB body = bounds(entity, spot);
        var border = level.getWorldBorder();
        if (!geometry(body).fits(level.getMinBuildHeight(), level.getMaxBuildHeight(),
                border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ())
                || !loaded(level, body.inflate(EPSILON))
                || !level.noBlockCollision(entity, body.deflate(EPSILON))
                // Conditional shapes (e.g. scaffolding) must also be clear after
                // the old position/sneak-dependent entity context changes.
                || !level.noBlockCollision(null, body.deflate(EPSILON))
                || level.containsAnyLiquid(body.deflate(EPSILON))) return false;
        // Test actual support, not just air in the participant's center block.
        AABB feet = new AABB(body.minX + EPSILON, body.minY - 0.025D, body.minZ + EPSILON,
                body.maxX - EPSILON, body.minY, body.maxZ - EPSILON);
        return level.getBlockCollisions(entity, feet).iterator().hasNext();
    }

    private static AABB bounds(LivingEntity entity, Vec3 spot) {
        AABB actual = entity.getBoundingBox().move(spot.subtract(entity.position()));
        // Battle input releases sneak/crawl: reserve standing dimensions too.
        AABB standing = entity.getDimensions(Pose.STANDING).makeBoundingBox(spot);
        AABB combined = actual.minmax(standing);
        return entity instanceof SmileyBallEntity ? combined.expandTowards(0, SmileyDancePattern.MAX_HOP_HEIGHT, 0) : combined;
    }

    private static boolean loaded(ServerLevel level, AABB box) {
        if (!geometry(box).usable()) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++) {
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, 0, z << 4))) return false;
            }
        }
        return true;
    }

    private static Bounds geometry(AABB box) {
        return new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static Vec3 position(Candidate candidate) {
        return new Vec3(candidate.x(), candidate.y(), candidate.z());
    }
}
