package ru.gora.musicbattle.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.registry.ModBlocks;

/** Loaded speaker discovery and deterministic chained coverage calculation. */
public final class SpeakerNetwork {
    private static final int MAX_CHAIN_SPEAKERS = 512;
    private static final Map<ServerLevel, Set<BlockPos>> LOADED = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, Integer>> BATTLE_ACTIVE = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, Integer>> DJ_ACTIVE = new WeakHashMap<>();

    private SpeakerNetwork() {
    }

    public static void register(ServerLevel level, BlockPos pos) {
        LOADED.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        Set<BlockPos> positions = LOADED.get(level);
        if (positions != null) {
            positions.remove(pos);
            if (positions.isEmpty()) {
                LOADED.remove(level);
            }
        }
    }

    public static Set<BlockPos> connectedSpeakers(ServerLevel level, Vec3 battleCenter, double radius) {
        ArrayList<BlockPos> remaining = loadedPositions(level);
        if (remaining.isEmpty()) {
            return Set.of();
        }
        ArrayDeque<CoverageSource> sources = new ArrayDeque<>();
        Set<BlockPos> connected = new HashSet<>();
        sources.add(new CoverageSource(battleCenter, radius));
        while (!sources.isEmpty() && connected.size() < MAX_CHAIN_SPEAKERS) {
            CoverageSource source = sources.removeFirst();
            for (int index = remaining.size() - 1; index >= 0; index--) {
                BlockPos candidate = remaining.get(index);
                Vec3 speakerCenter = Vec3.atCenterOf(candidate);
                if (source.position.distanceToSqr(speakerCenter) > source.radius * source.radius) {
                    continue;
                }
                remaining.remove(index);
                connected.add(candidate);
                sources.addLast(new CoverageSource(speakerCenter, soundRadius(level, candidate)));
            }
        }
        return Set.copyOf(connected);
    }

    public static void applyActiveStates(ServerLevel level, Map<BlockPos, Integer> activePositions) {
        storeActiveMap(BATTLE_ACTIVE, level, activePositions);
        applyCombinedActiveStates(level);
    }

    /** Adds standalone DJ playback without allowing the battle refresh to turn those speakers off. */
    public static void setDjActiveStates(ServerLevel level, Map<BlockPos, Integer> activePositions) {
        storeActiveMap(DJ_ACTIVE, level, activePositions);
        applyCombinedActiveStates(level);
    }

    private static void storeActiveMap(Map<ServerLevel, Map<BlockPos, Integer>> target, ServerLevel level,
                                       Map<BlockPos, Integer> activePositions) {
        if (activePositions == null || activePositions.isEmpty()) {
            target.remove(level);
        } else {
            target.put(level, Map.copyOf(activePositions));
        }
    }

    private static void applyCombinedActiveStates(ServerLevel level) {
        Map<BlockPos, Integer> activePositions = new java.util.HashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : DJ_ACTIVE.getOrDefault(level, Map.of()).entrySet()) {
            activePositions.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        // A real battle also owns listener playback priority, so its BPM must
        // own the physical speaker animation when the same block is used by DJ.
        for (Map.Entry<BlockPos, Integer> entry : BATTLE_ACTIVE.getOrDefault(level, Map.of()).entrySet()) {
            activePositions.put(entry.getKey(), entry.getValue());
        }
        for (BlockPos pos : loadedPositions(level)) {
            BlockState state = level.getBlockState(pos);
            if (!isSpeaker(state) || !state.hasProperty(SpeakerBlock.ACTIVE)) {
                continue;
            }
            boolean active = activePositions.containsKey(pos);
            if (state.getValue(SpeakerBlock.ACTIVE) != active) {
                level.setBlock(pos, state.setValue(SpeakerBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
            }
            if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
                speaker.setActiveBpm(active ? activePositions.get(pos) : 120);
            }
        }
    }

    public static void deactivateAll() {
        for (Map.Entry<ServerLevel, Set<BlockPos>> entry : new ArrayList<>(LOADED.entrySet())) {
            BATTLE_ACTIVE.remove(entry.getKey());
            DJ_ACTIVE.remove(entry.getKey());
            applyCombinedActiveStates(entry.getKey());
        }
        BATTLE_ACTIVE.clear();
        DJ_ACTIVE.clear();
        LOADED.clear();
    }

    private static ArrayList<BlockPos> loadedPositions(ServerLevel level) {
        Set<BlockPos> positions = LOADED.get(level);
        if (positions == null || positions.isEmpty()) {
            return new ArrayList<>();
        }
        positions.removeIf(pos -> !level.hasChunkAt(pos) || !isSpeaker(level.getBlockState(pos)));
        return new ArrayList<>(positions);
    }

    private static boolean isSpeaker(BlockState state) {
        return state.is(ModBlocks.SPEAKER.get()) || state.is(ModBlocks.HORIZONTAL_SPEAKER.get());
    }

    public static float soundRadius(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker
                ? speaker.getSoundRadius() : SpeakerBlockEntity.DEFAULT_RADIUS;
    }

    public static float volume(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker
                ? speaker.getVolume() : SpeakerBlockEntity.DEFAULT_VOLUME;
    }

    private record CoverageSource(Vec3 position, double radius) { }
}
