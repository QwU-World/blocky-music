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
    /**
     * The game tick a beat of what a speaker plays falls on (beat 1 of the chart, or any beat after it), so its cone
     * pumps on the song's beats and not on beats counted from the world's tick 0. Missing: the old free-running pump.
     */
    private static final Map<ServerLevel, Map<BlockPos, Long>> BATTLE_BEAT = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, Long>> DJ_BEAT = new WeakHashMap<>();

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
        applyActiveStates(level, activePositions, Map.of());
    }

    /** {@code beatTicks}: the game tick a beat falls on for each speaker in {@code activePositions} (BATTLE_BEAT). */
    public static void applyActiveStates(ServerLevel level, Map<BlockPos, Integer> activePositions,
                                         Map<BlockPos, Long> beatTicks) {
        storeActiveMap(BATTLE_ACTIVE, level, activePositions);
        storeActiveMap(BATTLE_BEAT, level, beatTicks);
        applyCombinedActiveStates(level);
    }

    /** Adds standalone DJ playback without allowing the battle refresh to turn those speakers off. */
    public static void setDjActiveStates(ServerLevel level, Map<BlockPos, Integer> activePositions) {
        setDjActiveStates(level, activePositions, Map.of());
    }

    public static void setDjActiveStates(ServerLevel level, Map<BlockPos, Integer> activePositions,
                                         Map<BlockPos, Long> beatTicks) {
        storeActiveMap(DJ_ACTIVE, level, activePositions);
        storeActiveMap(DJ_BEAT, level, beatTicks);
        applyCombinedActiveStates(level);
    }

    private static <V> void storeActiveMap(Map<ServerLevel, Map<BlockPos, V>> target, ServerLevel level,
                                           Map<BlockPos, V> activePositions) {
        if (activePositions == null || activePositions.isEmpty()) {
            target.remove(level);
        } else {
            target.put(level, Map.copyOf(activePositions));
        }
    }

    /** What each speaker plays to: its tempo and the game tick of a beat of it (absent: the old free-running pump). */
    record Combined(Map<BlockPos, Integer> bpm, Map<BlockPos, Long> beats) {
        /** What the speaker at {@code pos} is handed: whether it plays, its tempo and its beat. Pure, for beat-one. */
        SpeakerBeat at(BlockPos pos) {
            Integer tempo = bpm.get(pos);
            if (tempo == null) {
                return SpeakerBeat.IDLE;
            }
            return new SpeakerBeat(true, tempo, beats.getOrDefault(pos, SpeakerBlockEntity.NO_BEAT));
        }
    }

    /** One speaker's state: idle speakers keep the old 120 BPM and no beat. */
    record SpeakerBeat(boolean active, int bpm, long beat) {
        static final SpeakerBeat IDLE = new SpeakerBeat(false, 120, SpeakerBlockEntity.NO_BEAT);
    }

    /**
     * The DJ booths' and the battles' speakers together. A real battle also owns listener playback priority, so its BPM
     * and its beat own the physical speaker animation when the same block is used by a DJ, even where the battle names
     * no beat (then the DJ song's beat must not stay on it). Pure, for beat-one.
     */
    static Combined combine(Map<BlockPos, Integer> djActive, Map<BlockPos, Long> djBeats,
                            Map<BlockPos, Integer> battleActive, Map<BlockPos, Long> battleBeats) {
        Map<BlockPos, Integer> activePositions = new java.util.HashMap<>();
        Map<BlockPos, Long> beats = new java.util.HashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : djActive.entrySet()) {
            activePositions.merge(entry.getKey(), entry.getValue(), Math::max);
            Long beat = djBeats.get(entry.getKey());
            if (beat != null) {
                beats.put(entry.getKey(), beat);
            }
        }
        for (Map.Entry<BlockPos, Integer> entry : battleActive.entrySet()) {
            activePositions.put(entry.getKey(), entry.getValue());
            Long beat = battleBeats.get(entry.getKey());
            if (beat != null) {
                beats.put(entry.getKey(), beat);
            } else {
                beats.remove(entry.getKey());
            }
        }
        return new Combined(activePositions, beats);
    }

    /**
     * The game tick of beat 1 of a song whose clock (in game ticks at its speed) reads {@code clockTicks} now, with beat 1
     * {@code effectiveOffsetMs} into it (ChartBeat.effectiveOffsetMs, the speed included). Pure, for beat-one.
     */
    public static long beatOneGameTick(long gameTime, double clockTicks, int effectiveOffsetMs) {
        return Math.round(gameTime - clockTicks + ru.gora.musicbattle.data.ChartBeat.originTicks(effectiveOffsetMs));
    }

    /**
     * Gives {@code pos} to a song at {@code bpm} with beat 1 at {@code beatOne} unless a faster one already holds it; the
     * beat goes with the tempo that won, so a cone never pumps one song's tempo on another's beat.
     */
    public static void claim(Map<BlockPos, Integer> active, Map<BlockPos, Long> beats, BlockPos pos, int bpm, long beatOne) {
        Integer held = active.get(pos);
        if (held == null || bpm > held) {
            active.put(pos, bpm);
            beats.put(pos, beatOne);
        }
    }

    private static void applyCombinedActiveStates(ServerLevel level) {
        Combined combined = combine(DJ_ACTIVE.getOrDefault(level, Map.of()), DJ_BEAT.getOrDefault(level, Map.of()),
                BATTLE_ACTIVE.getOrDefault(level, Map.of()), BATTLE_BEAT.getOrDefault(level, Map.of()));
        for (BlockPos pos : loadedPositions(level)) {
            BlockState state = level.getBlockState(pos);
            if (!isSpeaker(state) || !state.hasProperty(SpeakerBlock.ACTIVE)) {
                continue;
            }
            SpeakerBeat handed = combined.at(pos);
            if (state.getValue(SpeakerBlock.ACTIVE) != handed.active()) {
                level.setBlock(pos, state.setValue(SpeakerBlock.ACTIVE, handed.active()), Block.UPDATE_CLIENTS);
            }
            if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
                speaker.setActiveBeat(handed.bpm(), handed.beat());
            }
        }
    }

    public static void deactivateAll() {
        for (Map.Entry<ServerLevel, Set<BlockPos>> entry : new ArrayList<>(LOADED.entrySet())) {
            BATTLE_ACTIVE.remove(entry.getKey());
            DJ_ACTIVE.remove(entry.getKey());
            BATTLE_BEAT.remove(entry.getKey());
            DJ_BEAT.remove(entry.getKey());
            applyCombinedActiveStates(entry.getKey());
        }
        BATTLE_ACTIVE.clear();
        DJ_ACTIVE.clear();
        BATTLE_BEAT.clear();
        DJ_BEAT.clear();
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
