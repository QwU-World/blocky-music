package ru.gora.musicbattle.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import ru.gora.musicbattle.registry.ModBlocks;

/** Tracks loaded battle-camera blocks without scanning large speaker coverage volumes. */
public final class BattleCameraNetwork {
    private static final Map<ServerLevel, Set<BlockPos>> LOADED = new WeakHashMap<>();

    private BattleCameraNetwork() {
    }

    public static void register(ServerLevel level, BlockPos pos) {
        LOADED.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        Set<BlockPos> positions = LOADED.get(level);
        if (positions == null) {
            return;
        }
        positions.remove(pos);
        if (positions.isEmpty()) {
            LOADED.remove(level);
        }
    }

    public static Set<BlockPos> loadedPositions(ServerLevel level) {
        Set<BlockPos> positions = LOADED.get(level);
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        positions.removeIf(pos -> !level.hasChunkAt(pos) || !level.getBlockState(pos).is(ModBlocks.BATTLE_CAMERA.get()));
        return Set.copyOf(new ArrayList<>(positions));
    }
}
