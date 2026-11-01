package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import ru.gora.musicbattle.entity.SmileyBallEntity;
import ru.gora.musicbattle.data.SmileyDancePattern;

public final class ClientMobFallbackAnimator {
    private static final Map<LivingEntity, BounceState> STATES = new WeakHashMap<>();

    private ClientMobFallbackAnimator() {
    }

    static void prepare(LivingEntity entity, int triggerSerial, int heldLaneMask, int bpm, float battleTick) {
        prepare(entity, triggerSerial, heldLaneMask, bpm, battleTick, 0.0F);
    }

    /** {@code beatOrigin}: beat 1 on the battle clock, in ticks; the hop's arc is timed on beats counted from it. */
    static void prepare(LivingEntity entity, int triggerSerial, int heldLaneMask, int bpm, float battleTick,
                        float beatOrigin) {
        update(STATES.computeIfAbsent(entity, ignored -> new BounceState()), triggerSerial, heldLaneMask, bpm, battleTick,
                beatOrigin);
    }

    /** What {@link #prepare} keeps for one mob this frame. Pure, for beat-one. */
    static void update(BounceState state, int triggerSerial, int heldLaneMask, int bpm, float battleTick,
                       float beatOrigin) {
        if (!state.triggerSerialInitialized) {
            // Joining/recreating the renderer observes an existing serial. It
            // must not manufacture a bounce when no note was just played.
            state.triggerSerialInitialized = true;
            state.lastTriggerSerial = triggerSerial;
        } else if (state.lastTriggerSerial != triggerSerial) {
            state.lastTriggerSerial = triggerSerial;
            state.triggerBattleTick = battleTick;
        }
        state.heldLaneMask = heldLaneMask;
        state.bpm = Math.max(20, bpm);
        state.battleTick = battleTick;
        state.beatOrigin = Float.isFinite(beatOrigin) ? beatOrigin : 0.0F;
    }

    public static void applyPreparedBounce(LivingEntity entity, PoseStack poseStack, float partialTick) {
        BounceState state = STATES.get(entity);
        if (state == null) {
            return;
        }

        if (entity instanceof SmileyBallEntity) {
            float phase = smileyNotePhase(entity);
            if (phase >= 0) poseStack.translate(0, SmileyDancePattern.forEntity(entity.getUUID()).hopHeight(phase), 0);
            return;
        }

        float height = hopHeight(state);
        if (height > 0.0F) {
            poseStack.translate(0.0F, height, 0.0F);
        }
    }

    /** How high the mob hops now: an arc over each beat counted from beat 1, after a note or while one is held. */
    static float hopHeight(BounceState state) {
        float height = 0.0F;
        if (state.battleTick >= 0) {
            float beatTicks = 1200.0F / state.bpm;
            float sinceBeatOne = state.battleTick - state.beatOrigin;
            float phase = (sinceBeatOne % beatTicks + beatTicks) % beatTicks / beatTicks;
            float sinceTrigger = state.battleTick - state.triggerBattleTick;
            if (sinceTrigger >= 0.0F && sinceTrigger < beatTicks) {
                height = Math.max(height, (float)Math.sin(Math.PI * phase) * 0.34F);
            }
            if (state.heldLaneMask != 0) {
                height = Math.max(height, (float)Math.sin(Math.PI * phase) * 0.24F);
            }
        }
        return height;
    }

    static void stop(LivingEntity entity) {
        STATES.remove(entity);
    }

    static boolean hasBattlePose(LivingEntity entity) { return STATES.containsKey(entity); }

    static float smileyNotePhase(LivingEntity entity) {
        BounceState state = STATES.get(entity);
        if (state == null || state.battleTick < 0) return -1;
        double elapsed = state.battleTick - state.triggerBattleTick;
        // Sustained notes keep the animated singing motion, just like the fallback mobs.
        if (state.heldLaneMask != 0 && elapsed >= 0) elapsed %= 1200.0 / state.bpm;
        return (float) SmileyDancePattern.notePhase(elapsed, state.bpm);
    }

    public static void stopAll() {
        STATES.clear();
    }

    static final class BounceState {
        private int lastTriggerSerial = Integer.MIN_VALUE;
        private boolean triggerSerialInitialized;
        private float triggerBattleTick = -Float.MAX_VALUE;
        private int heldLaneMask;
        private int bpm = 120;
        private float battleTick = -Float.MAX_VALUE;
        private float beatOrigin;
    }
}
