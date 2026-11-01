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
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.KeyFrame;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.StateCollection;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation.StateCollection.State;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Turns battle presses into poses on a participant's body.
 *
 * <p>Every press owns the body for a short window, whatever the key does in the meantime. The pose used to end at
 * the next beat line, which could be a frame away for a note hit just before the beat, and for the player's own
 * presses it ended when the key went up, so a quick tap showed for 50-100 ms and spent most of its life fading. Now a
 * press holds its pose for a beat (see {@link #pressPoseTicks}), a held key keeps it for as long as it is held, a new
 * press cuts straight to its own pose, and only letting go of a pose eases back to the idle bob, briefly. Every press
 * also lands with a short punch (see {@link #PRESS_PUNCH}), so that a note repeated on the lane already posed shows
 * on the body too. A side that plays with keys presses when a key goes down; the judgement of that press, which can
 * come before the key-down (an opponent's) or long after it (an early hit, held back until its note reaches the
 * line), only confirms it. The controller hands such judgements over apart from the presses, so that one is never a
 * second press (see {@link #update(Holder, int, int, float, int, int, float, int, int, float)}). A mob has no keys,
 * and its judgement is its press.
 */
final class ClientBattleAnimation {
    /**
     * Where beat 1 falls on the battle clock the next update is given, in ticks (ChartBeat.originTicks of the chart's
     * offset at the battle's speed). The controller sets it just before it updates a fighter's poses, from the battle that
     * fighter is in or the one being watched; 0, a chart without an offset, keeps the loops exactly where they were.
     */
    private static float beatOriginTicks;

    static void setBeatOrigin(float ticks) {
        beatOriginTicks = Float.isFinite(ticks) ? ticks : 0.0F;
    }

    /**
     * A press holds its pose for one beat plus this slack. The next note's press, even if it comes a little late,
     * then takes over from the pose itself instead of from a return to idle it has just interrupted.
     */
    private static final float PRESS_POSE_SLACK_TICKS = 2.0F;
    /** The shortest press pose, 350 ms, so that every pose still reads on a very fast song. */
    static final float MIN_PRESS_POSE_TICKS = 7.0F;
    /** The longest press pose, 700 ms, so that a lone tap on a slow song does not leave the dancer frozen in it. */
    static final float MAX_PRESS_POSE_TICKS = 14.0F;
    /**
     * Letting go of a pose takes 100 ms and moves most of the way back at once. It is timed on the battle clock, as
     * the clips are, rather than on Player Animator's 20 Hz ticks, so it starts from the pose actually on screen
     * instead of jumping by however much of a tick had passed when the release came.
     */
    static final float RETURN_TO_IDLE_TICKS = 2.0F;
    /**
     * A press lands this far past its pose, as a share of the pose's distance from idle, and settles into the pose
     * over {@link #PRESS_PUNCH_TICKS}. Once a press held its pose for a beat, a note repeated on the same lane changed
     * nothing on screen and looked as if it had not registered; the punch makes every press visible, on any lane.
     */
    static final float PRESS_PUNCH = 0.25F;
    /** How long a press's punch takes to settle, 150 ms, most of it in the first half. */
    static final float PRESS_PUNCH_TICKS = 3.0F;
    private static final String IDLE = "idle";
    // Constant names: the choice is made on every frame for every participant, so it builds no strings.
    private static final String[] LANE_CLIPS = {"lane_0", "lane_1", "lane_2", "lane_3"};
    private static final String[] HOLD_CLIPS = {"hold_0", "hold_1", "hold_2", "hold_3"};
    private static final Set<String> MISSING_ANIMATIONS_LOGGED = new HashSet<>();

    private ClientBattleAnimation() {
    }

    static Holder createLayer() {
        return new Holder();
    }

    /**
     * The holder whose layer this is, or null for any other animation. Player Animator keeps a player's layers on the
     * player object itself, which is how a layer is found again for exactly that player (see
     * ClientPlayerAnimatorBridge).
     */
    static Holder holderOf(IAnimation animation) {
        return animation instanceof BattleLayer layer ? layer.holder : null;
    }

    /** A participant without keys, or the editor preview: every trigger is a press, and nothing is judged. */
    static boolean update(Holder holder, int triggerSerial, int triggerLane, int heldLaneMask, int bpm, float battleTick) {
        return update(holder, triggerSerial, triggerLane, Float.NaN, 0, -1, Float.NaN, heldLaneMask, bpm, battleTick);
    }

    static boolean update(Holder holder, int triggerSerial, int triggerLane, int heldLaneMask, int bpm,
                          float battleTick, float triggerTick) {
        return update(holder, triggerSerial, triggerLane, triggerTick, 0, -1, Float.NaN, heldLaneMask, bpm, battleTick);
    }

    /**
     * Shows this frame's pose from the participant's two kinds of trigger, each a serial that counts up, the lane of
     * the latest one and the battle tick it happened at.
     *
     * <p>A press is a key going down, or a mob's hit, which has no key. A judgement is the hit of a key already
     * pressed, which only confirms that press (see {@link #confirm}). A side that plays with keys sends both for one
     * press, in either order and up to 16 ticks apart: an early hit is judged when its note reaches the line, later
     * still with a negative input offset, and the server judges another player's hit before it passes on the key.
     * Counted as two presses, it punched the body a second time and could take it back to a lane already left. With
     * one stream of triggers, judgements were told from presses by how soon they followed a key on their lane: a mob's
     * note soon after its hold was swallowed (a hold note holds its lane, as a key does), a judgement held back longer
     * punched again, and since a frame sees only the latest trigger, a quick key-down followed by a judgement before
     * the next frame was lost. Each kind now has a stream of its own.
     *
     * @param heldLaneMask every lane held, by a key or by a hold note being sung, which picks the hold clips
     */
    static boolean update(Holder holder, int pressSerial, int pressLane, float pressTick,
                          int judgedSerial, int judgedLane, float judgedTick,
                          int heldLaneMask, int bpm, float battleTick) {
        float poseTicks = pressPoseTicks(bpm);
        if (pressSerial != holder.lastTriggerSerial) {
            // A newly attached/recreated render layer first observes the latest server serial. That value is a
            // baseline, not a fresh button press, unless the press it counts is still within its window.
            // Treating every baseline as new replayed the last lane animation during rests.
            boolean fresh = holder.triggerSerialInitialized || pressStillShowing(battleTick, pressTick, poseTicks);
            if (fresh && pressLane >= 0 && pressLane < 4) {
                press(holder, pressLane, pressStart(battleTick, pressTick));
            }
            holder.lastTriggerSerial = pressSerial;
        }
        if (judgedSerial != holder.lastJudgedSerial) {
            // After the press of the same frame, if any: a judgement never replaces a press, it can only extend one.
            if (holder.triggerSerialInitialized && judgedLane >= 0 && judgedLane < 4) {
                confirm(holder, judgedLane, pressStart(battleTick, judgedTick), poseTicks);
            }
            holder.lastJudgedSerial = judgedSerial;
        }
        holder.triggerSerialInitialized = true;

        float sincePress = battleTick - holder.pressedAtTick;
        // The latest press keeps the body for its whole window, even after a quick tap has let the key go and even
        // while another lane is held; the held lane takes over when the window ends. A clock that jumped back past
        // the press (a seek) ends the window instead of freezing the pose until the clock catches up.
        boolean pressShowing = holder.pressLane >= 0 && sincePress >= -1.0F && sincePress < poseTicks;
        int lane = pressShowing ? holder.pressLane : preferredHeldLane(heldLaneMask, pressLane);
        String clip = lane < 0 ? IDLE : (heldLaneMask & (1 << lane)) != 0 ? HOLD_CLIPS[lane] : LANE_CLIPS[lane];
        if (!clip.equals(holder.clip) || holder.layer.getAnimation() == null) {
            show(holder, clip, battleTick);
        }
        // Before the clock, so that an idle clip loaded for the first punch is sampled at this frame's time.
        holder.pose.punch(pressShowing ? punchWeight(battleTick - holder.punchedAtTick) : 0.0F);
        holder.pose.setClock(bpm, battleTick);
        return holder.layer.getAnimation() != null;
    }

    /** A new press: its pose on screen at once, landing with its punch. */
    private static void press(Holder holder, int lane, float start) {
        holder.pressLane = lane;
        holder.pressedAtTick = start;
        holder.punchedAtTick = start;
    }

    /**
     * A key press's judgement. If its lane is still the pose on screen, the pose's window starts again from the
     * judgement, so that an early hit holds its pose for a beat after it lands; otherwise, the body having moved on to
     * another press or back to rest, it changes nothing. It never punches.
     */
    private static void confirm(Holder holder, int lane, float judgedAt, float poseTicks) {
        if (lane == holder.pressLane && judgedAt - holder.pressedAtTick < poseTicks) {
            holder.pressedAtTick = Math.max(holder.pressedAtTick, judgedAt);
        }
    }

    /**
     * How far past its pose a press stands this long after it landed: the whole punch at once, settling with most of
     * the move in the first frames. It is measured from the press itself, so a press first drawn late (an entity
     * that was out of view) gets only what is left of it.
     */
    private static float punchWeight(float sincePress) {
        if (!(sincePress < PRESS_PUNCH_TICKS)) {
            return 0.0F;
        }
        float remaining = 1.0F - Math.max(0.0F, sincePress) / PRESS_PUNCH_TICKS;
        return PRESS_PUNCH * remaining * remaining;
    }

    /**
     * How long one press holds its pose at this tempo, in battle ticks: a beat and a little slack, kept within
     * 350-700 ms. A press is new information the instant it lands, so it never waits for this to run out; the window
     * only decides how long the pose lingers when nothing replaces it.
     */
    static float pressPoseTicks(int bpm) {
        float beatTicks = 1200.0F / Math.max(20, bpm);
        return Math.max(MIN_PRESS_POSE_TICKS, Math.min(MAX_PRESS_POSE_TICKS, beatTicks + PRESS_POSE_SLACK_TICKS));
    }

    private static boolean pressStillShowing(float battleTick, float triggerTick, float poseTicks) {
        return Float.isFinite(triggerTick)
                && triggerTick <= battleTick + 1.0F
                && battleTick - triggerTick < poseTicks;
    }

    private static float pressStart(float battleTick, float triggerTick) {
        // The controller stamps every press with the battle clock it read at the time. Counting the window from that
        // stamp keeps a press the renderer only notices late (an entity that was out of view) from posing past its
        // moment. A stamp ahead of the clock (the clock has since jumped back) counts from now instead.
        return Float.isFinite(triggerTick) && triggerTick <= battleTick ? triggerTick : battleTick;
    }

    static void stop(Holder holder) {
        holder.layer.setAnimation(null);
        holder.pose.clear();
        holder.clip = null;
        holder.lastTriggerSerial = Integer.MIN_VALUE;
        holder.triggerSerialInitialized = false;
        holder.pressLane = -1;
        holder.pressedAtTick = -Float.MAX_VALUE;
        holder.punchedAtTick = -Float.MAX_VALUE;
        holder.lastJudgedSerial = Integer.MIN_VALUE;
    }

    private static void show(Holder holder, String clip, float battleTick) {
        IAnimation next = load(clip);
        if (next == null) {
            return;
        }
        // A press or a newly held direction cuts straight to its pose, as an instant switch reads sharpest. Only
        // letting go of a pose eases back into the idle bob.
        boolean returningToIdle = IDLE.equals(clip) && holder.clip != null && !IDLE.equals(holder.clip);
        holder.pose.show(next, returningToIdle, battleTick);
        holder.clip = clip;
        if (holder.layer.getAnimation() != holder.pose) {
            holder.layer.setAnimation(holder.pose);
        }
    }

    private static IAnimation load(String clip) {
        IPlayable playable = PlayerAnimationRegistry.getAnimation(MusicBattleMod.id(clip));
        if (playable == null) {
            if (MISSING_ANIMATIONS_LOGGED.add(clip)) {
                MusicBattleMod.LOGGER.error("Blocky Music animation '{}' was not loaded. Available mod animations: {}", clip, PlayerAnimationRegistry.getModAnimations(MusicBattleMod.MOD_ID).keySet());
            }
            return null;
        }
        IActualAnimation<?> actual = playable.playAnimation();
        return actual instanceof KeyframeAnimationPlayer player
                ? new BeatSynchronizedAnimation(player.getData())
                : actual;
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

    static final class Holder {
        final ModifierLayer<IAnimation> layer;
        private final PoseMix pose = new PoseMix();
        /** The clip on screen, or null before the first update and after stop. */
        private String clip;
        private int lastTriggerSerial = Integer.MIN_VALUE;
        private boolean triggerSerialInitialized;
        private int pressLane = -1;
        /** Where the pose's window counts from: the press, or the judgement that renewed it. */
        private float pressedAtTick = -Float.MAX_VALUE;
        /** Where the press's punch counts from: the moment it first landed. */
        private float punchedAtTick = -Float.MAX_VALUE;
        private int lastJudgedSerial = Integer.MIN_VALUE;

        private Holder() {
            BattleLayer battleLayer = new BattleLayer(this);
            battleLayer.addModifierLast(new BlockbenchCoordinateModifier());
            this.layer = battleLayer;
        }
    }

    /** The layer itself, which knows its holder, so that the player wearing it can be asked for the holder. */
    private static final class BattleLayer extends ModifierLayer<IAnimation> {
        private final Holder holder;

        private BattleLayer(Holder holder) {
            this.holder = holder;
        }
    }

    /**
     * The one animation the layer plays: the pose on screen and, for {@link #RETURN_TO_IDLE_TICKS} after a pose lets
     * go, the pose it is leaving. Player Animator's own fade modifier counted its progress in client ticks while the
     * clips run on the battle clock, and it froze the outgoing clip's clock at the switch. A press's punch pushes the
     * pose on screen away from the idle pose sampled at the same moment.
     */
    private static final class PoseMix implements IAnimation {
        private IAnimation current;
        private IAnimation leaving;
        /** The idle clip, loaded with the first punch: the rest that a punch pushes the pose away from. */
        private IAnimation rest;
        private float leftAtTick;
        private float battleTick;
        private float punch;

        private void show(IAnimation next, boolean easeOutOfCurrent, float battleTick) {
            leaving = easeOutOfCurrent ? current : null;
            leftAtTick = battleTick;
            current = next;
        }

        private void punch(float weight) {
            if (weight > 0.0F && rest == null) {
                rest = load(IDLE);
            }
            punch = rest == null ? 0.0F : weight;
        }

        private void setClock(int bpm, float battleTick) {
            this.battleTick = battleTick;
            if (current instanceof BeatSynchronizedAnimation clip) {
                clip.setClock(bpm, battleTick);
            }
            if (leaving instanceof BeatSynchronizedAnimation clip) {
                clip.setClock(bpm, battleTick);
            }
            if (rest instanceof BeatSynchronizedAnimation clip) {
                clip.setClock(bpm, battleTick);
            }
        }

        private void clear() {
            current = null;
            leaving = null;
            // Loaded again on the next battle's first punch, so that a resource reload in between is picked up.
            rest = null;
            punch = 0.0F;
        }

        /** How much of the pose being left is still on screen: most of it goes in the first frames. */
        private float leavingWeight() {
            if (leaving == null) {
                return 0.0F;
            }
            float progress = (battleTick - leftAtTick) / RETURN_TO_IDLE_TICKS;
            if (!(progress >= 0.0F) || progress >= 1.0F) {
                // Done, or the clock jumped back past the release (a seek): idle alone from here.
                leaving = null;
                return 0.0F;
            }
            float remaining = 1.0F - progress;
            return remaining * remaining;
        }

        @Override
        public boolean isActive() {
            return current != null;
        }

        @Override
        public void tick() {
            if (current != null) {
                current.tick();
            }
            if (leaving != null) {
                leaving.tick();
            }
            if (rest != null) {
                rest.tick();
            }
        }

        @Override
        public void setupAnim(float tickDelta) {
            if (current != null) {
                current.setupAnim(tickDelta);
            }
            if (leaving != null) {
                leaving.setupAnim(tickDelta);
            }
            if (punch > 0.0F) {
                rest.setupAnim(tickDelta);
            }
        }

        @Override
        public Vec3f get3DTransform(String modelName, TransformType type, float tickDelta, Vec3f value0) {
            if (current == null) {
                return value0;
            }
            Vec3f shown = current.get3DTransform(modelName, type, tickDelta, value0);
            if (punch > 0.0F && (type == TransformType.POSITION || type == TransformType.ROTATION)) {
                // Only where the parts are and how they turn: a punched scale would swell a limb, not move it.
                Vec3f still = rest.get3DTransform(modelName, type, tickDelta, value0);
                shown = shown.add(shown.subtract(still).scale(punch));
            }
            float weight = leavingWeight();
            if (weight <= 0.0F) {
                return shown;
            }
            Vec3f left = leaving.get3DTransform(modelName, type, tickDelta, value0);
            return shown.scale(1.0F - weight).add(left.scale(weight));
        }

        @Override
        public FirstPersonMode getFirstPersonMode(float tickDelta) {
            return current == null ? FirstPersonMode.NONE : current.getFirstPersonMode(tickDelta);
        }

        @Override
        public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
            return current == null ? new FirstPersonConfiguration() : current.getFirstPersonConfiguration(tickDelta);
        }
    }

    /** Samples every clip from the battle clock instead of from the moment the key changed state. */
    private static final class BeatSynchronizedAnimation implements IAnimation {
        private final KeyframeAnimation data;
        private final KeyframeAnimationPlayer metadataPlayer;
        private int bpm = 120;
        private float battleTick;
        /** Beat 1 on battleTick's clock when the clock was set ({@link #setBeatOrigin}). */
        private float beatOrigin;
        private float sampledPosition;

        private BeatSynchronizedAnimation(KeyframeAnimation data) {
            this.data = data;
            this.metadataPlayer = new KeyframeAnimationPlayer(data);
        }

        private void setClock(int bpm, float battleTick) {
            this.bpm = Math.max(20, bpm);
            this.battleTick = battleTick;
            this.beatOrigin = beatOriginTicks;
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
            // Counted from the chart's beat 1, so the loop's downbeat lands on the song's beats when it has a lead-in.
            float sourcePosition = (battleTick - beatOrigin) * sourceCycleTicks / beatTicks;
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
