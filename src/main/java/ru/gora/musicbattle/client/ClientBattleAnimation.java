package ru.gora.musicbattle.client;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.IActualAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.KeyFrame;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.StateCollection;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.StateCollection.State;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.gora.musicbattle.MusicBattleMod;

final class ClientBattleAnimation {
    private static final int TRANSITION_TICKS = 2;
    private static final Set<String> MISSING_ANIMATIONS_LOGGED = new HashSet<>();

    private ClientBattleAnimation() {
    }

    static Holder createLayer() {
        ModifierLayer<IAnimation> layer = new ModifierLayer<>();
        layer.addModifierLast(new BlockbenchCoordinateModifier());
        return new Holder(layer);
    }

    static boolean update(Holder holder, int triggerSerial, int triggerLane, int heldLaneMask, int bpm, float battleTick) {
        return update(holder, triggerSerial, triggerLane, heldLaneMask, bpm, battleTick, Float.NaN);
    }

    static boolean update(Holder holder, int triggerSerial, int triggerLane, int heldLaneMask, int bpm,
                          float battleTick, float triggerTick) {
        // A newly attached/recreated render layer first observes the latest
        // server serial. That value is a baseline, not a fresh button press.
        // Treating it as new replayed the last lane animation during rests.
        boolean firstObservationIsFresh = !holder.triggerSerialInitialized
                && freshTrigger(bpm, battleTick, triggerTick);
        boolean laneTriggered = (holder.triggerSerialInitialized || firstObservationIsFresh)
                && triggerLane >= 0 && triggerLane < 4
                && triggerSerial != holder.lastTriggerSerial;
        holder.triggerSerialInitialized = true;
        holder.lastTriggerSerial = triggerSerial;
        boolean heldLanesChanged = heldLaneMask != holder.lastHeldLaneMask;
        holder.lastHeldLaneMask = heldLaneMask;
        int heldLane = preferredHeldLane(heldLaneMask, triggerLane);
        boolean tapFinished = holder.tapActionUntilTick >= 0.0F && battleTick >= holder.tapActionUntilTick;
        holder.lastBattleTick = battleTick;
        if (laneTriggered) {
            boolean held = (heldLaneMask & (1 << triggerLane)) != 0;
            // Button presses are meant to feel punchy: switch to the requested
            // action pose immediately, as in the original implementation.
            play(holder, (held ? "hold_" : "lane_") + triggerLane, bpm, battleTick, true);
            holder.tapActionUntilTick = held ? -1.0F : nextBeatBoundary(bpm, battleTick);
        } else if (heldLanesChanged
                || tapFinished
                || holder.layer.getAnimation() == null
                || !holder.layer.getAnimation().isActive()) {
            play(holder, heldLane >= 0 ? "hold_" + heldLane : "idle", bpm, battleTick, heldLane >= 0);
            holder.tapActionUntilTick = -1.0F;
        }
        if (holder.synchronizedAnimation != null) {
            holder.synchronizedAnimation.setClock(bpm, battleTick);
        }
        return holder.layer.getAnimation() != null;
    }

    private static boolean freshTrigger(int bpm, float battleTick, float triggerTick) {
        if (!Float.isFinite(triggerTick)) {
            return false;
        }
        return battleTick + 1.0F >= triggerTick
                && battleTick <= nextBeatBoundary(bpm, triggerTick) + 0.001F;
    }

    static void stop(Holder holder) {
        clearTransitions(holder);
        holder.layer.setAnimation(null);
        holder.lastBattleTick = -Float.MAX_VALUE;
        holder.lastTriggerSerial = Integer.MIN_VALUE;
        holder.triggerSerialInitialized = false;
        holder.lastHeldLaneMask = 0;
        holder.tapActionUntilTick = -1.0F;
        holder.synchronizedAnimation = null;
    }

    private static void play(Holder holder, String animation, int bpm, float battleTick, boolean instant) {
        IPlayable playable = PlayerAnimationRegistry.getAnimation(MusicBattleMod.id(animation));
        if (playable == null) {
            if (MISSING_ANIMATIONS_LOGGED.add(animation)) {
                MusicBattleMod.LOGGER.error("Blocky Music animation '{}' was not loaded. Available mod animations: {}", animation, PlayerAnimationRegistry.getModAnimations(MusicBattleMod.MOD_ID).keySet());
            }
            return;
        }
        // ModifierLayer.setAnimation only replaces its clip; an earlier fade remains in the
        // modifier chain and can keep blending the old pose into an otherwise instant note.
        clearTransitions(holder);
        IActualAnimation<?> actual = playable.playAnimation();
        if (actual instanceof KeyframeAnimationPlayer player) {
            BeatSynchronizedAnimation synchronizedAnimation = new BeatSynchronizedAnimation(player.getData());
            synchronizedAnimation.setClock(bpm, battleTick);
            holder.synchronizedAnimation = synchronizedAnimation;
            if (instant || holder.layer.getAnimation() == null) {
                holder.layer.setAnimation(synchronizedAnimation);
            } else {
                // Returning to idle remains softly blended so the fix for
                // unrelated spectator pose jitter stays intact.
                holder.layer.replaceAnimationWithFade(
                        AbstractFadeModifier.standardFadeIn(TRANSITION_TICKS, Ease.INOUTSINE),
                        synchronizedAnimation
                );
            }
        } else {
            holder.synchronizedAnimation = null;
            if (instant || holder.layer.getAnimation() == null) {
                holder.layer.setAnimation(actual);
            } else {
                holder.layer.replaceAnimationWithFade(
                        AbstractFadeModifier.standardFadeIn(TRANSITION_TICKS, Ease.INOUTSINE),
                        actual
                );
            }
        }
    }

    private static void clearTransitions(Holder holder) {
        // This private layer owns exactly one permanent modifier at index zero (coordinates).
        // Every later modifier is a transition added by play; preserve the coordinate mapping.
        while (holder.layer.size() > 1) {
            holder.layer.removeModifier(holder.layer.size() - 1);
        }
    }

    private static int preferredHeldLane(int heldLaneMask, int preferredLane) {
        if (preferredLane >= 0 && preferredLane < 4 && (heldLaneMask & (1 << preferredLane)) != 0) {
            return preferredLane;
        }
        for (int lane = 0; lane < 4; lane++) {
            if ((heldLaneMask & (1 << lane)) != 0) {
                return lane;
            }
        }
        return -1;
    }

    private static float nextBeatBoundary(int bpm, float battleTick) {
        float beatTicks = 1200.0F / Math.max(20, bpm);
        return ((float)Math.floor(battleTick / beatTicks) + 1.0F) * beatTicks;
    }

    static final class Holder {
        final ModifierLayer<IAnimation> layer;
        private BeatSynchronizedAnimation synchronizedAnimation;
        private float lastBattleTick = -Float.MAX_VALUE;
        private int lastTriggerSerial = Integer.MIN_VALUE;
        private boolean triggerSerialInitialized;
        private int lastHeldLaneMask;
        private float tapActionUntilTick = -1.0F;

        private Holder(ModifierLayer<IAnimation> layer) {
            this.layer = layer;
        }
    }

    /** Samples every clip from the battle clock instead of from the moment the key changed state. */
    private static final class BeatSynchronizedAnimation implements IAnimation {
        private final KeyframeAnimation data;
        private final KeyframeAnimationPlayer metadataPlayer;
        private int bpm = 120;
        private float battleTick;
        private float sampledPosition;

        private BeatSynchronizedAnimation(KeyframeAnimation data) {
            this.data = data;
            this.metadataPlayer = new KeyframeAnimationPlayer(data);
        }

        private void setClock(int bpm, float battleTick) {
            this.bpm = Math.max(20, bpm);
            this.battleTick = battleTick;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void setupAnim(float tickDelta) {
            prepareSample(tickDelta);
        }

        @Override
        public Vec3f get3DTransform(String modelName, TransformType type, float tickDelta, Vec3f value0) {
            prepareSample(tickDelta);
            StateCollection part = data.getPart(modelName);
            if (part == null || !part.isEnabled()) {
                return value0;
            }
            return switch (type) {
                case POSITION -> new Vec3f(
                        sample(part.x, value0.getX()),
                        sample(part.y, value0.getY()),
                        sample(part.z, value0.getZ())
                );
                case ROTATION -> new Vec3f(
                        sample(part.pitch, value0.getX()),
                        sample(part.yaw, value0.getY()),
                        sample(part.roll, value0.getZ())
                );
                case BEND -> new Vec3f(
                        sample(part.bendDirection, value0.getX()),
                        sample(part.bend, value0.getY()),
                        0.0F
                );
                case SCALE -> new Vec3f(
                        sample(part.scaleX, value0.getX()),
                        sample(part.scaleY, value0.getY()),
                        sample(part.scaleZ, value0.getZ())
                );
            };
        }

        @Override
        public FirstPersonMode getFirstPersonMode(float tickDelta) {
            return metadataPlayer.getFirstPersonMode(tickDelta);
        }

        @Override
        public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
            return metadataPlayer.getFirstPersonConfiguration(tickDelta);
        }

        private void prepareSample(float tickDelta) {
            float beatTicks = 1200.0F / bpm;
            // The exported clips use an inclusive duplicate end frame (frame 13
            // equals frame 0). The playable loop is therefore [returnToTick,
            // endTick), not stopTick frames long. Sampling the duplicate as an
            // extra frame caused a short freeze and visible jerk every beat.
            int loopStart = Math.max(0, data.returnToTick);
            float sourceCycleTicks = Math.max(1.0F, data.endTick - loopStart);
            // battleTick is already a smooth audio-clock value captured for this render.
            // Adding Minecraft's unrelated partial tick here can move time backwards at
            // client-tick boundaries and causes the visible snapping seen in battles.
            float sourcePosition = battleTick * sourceCycleTicks / beatTicks;
            float cyclePosition = sourcePosition - (float)Math.floor(sourcePosition / sourceCycleTicks) * sourceCycleTicks;
            sampledPosition = loopStart + cyclePosition;
        }

        private float sample(State state, float fallback) {
            if (state == null || !state.isEnabled()) {
                return fallback;
            }
            List<KeyFrame> frames = state.getKeyFrames();
            if (frames.isEmpty()) {
                return state.defaultValue;
            }

            KeyFrame before = null;
            KeyFrame after = null;
            for (KeyFrame frame : frames) {
                if (frame.tick <= sampledPosition) {
                    before = frame;
                }
                if (frame.tick >= sampledPosition) {
                    after = frame;
                    break;
                }
            }
            if (before == null) {
                before = frames.get(0);
            }
            if (after == null) {
                after = frames.get(frames.size() - 1);
            }
            if (before.tick == after.tick) {
                return before.value;
            }

            float progress = (sampledPosition - before.tick) / (after.tick - before.tick);
            progress = Math.max(0.0F, Math.min(1.0F, progress));
            KeyFrame easingFrame = data.isEasingBefore ? after : before;
            float eased = easingFrame.ease.invoke(progress, easingFrame.easingArg);
            return before.value + (after.value - before.value) * eased;
        }
    }

    private static final class BlockbenchCoordinateModifier extends AbstractModifier {
        @Override
        public Vec3f get3DTransform(String modelName, TransformType type, float tickDelta, Vec3f value0) {
            Vec3f transformed = super.get3DTransform(modelName, type, tickDelta, value0);
            if (type == TransformType.POSITION) {
                Vec3f sourcePivot = sourcePivot(modelName);
                if (sourcePivot == null) {
                    return value0;
                }

                // The bbmodel bones are siblings, not torso children. Preserve each
                // bone's independent world-space offset and apply it relative to the
                // current model's own pivot. The generated clips predate the GeckoLib
                // X-axis mirror, so mirror only their X delta here.
                float deltaX = transformed.getX() - sourcePivot.getX();
                float deltaY = transformed.getY() - sourcePivot.getY();
                float deltaZ = transformed.getZ() - sourcePivot.getZ();
                return new Vec3f(
                        value0.getX() - deltaX,
                        value0.getY() + deltaY,
                        value0.getZ() + deltaZ
                );
            }
            if (type == TransformType.ROTATION) {
                // GeckoLib's Blockbench exporter mirrors X and Y rotations before
                // Player Animator reads them. These clips come straight from a
                // generic bbmodel, so reproduce that coordinate conversion here.
                return new Vec3f(-transformed.getX(), -transformed.getY(), transformed.getZ());
            }
            return transformed;
        }

        private static Vec3f sourcePivot(String modelName) {
            return switch (modelName) {
                case "head", "torso" -> new Vec3f(0.0F, 0.0F, 0.0F);
                case "leftArm" -> new Vec3f(5.0F, 2.0F, 0.0F);
                case "rightArm" -> new Vec3f(-5.0F, 2.0F, 0.0F);
                case "leftLeg" -> new Vec3f(1.9F, 12.0F, 0.1F);
                case "rightLeg" -> new Vec3f(-1.9F, 12.0F, 0.1F);
                default -> null;
            };
        }
    }
}
