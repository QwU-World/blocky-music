package ru.gora.musicbattle.client;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.data.gson.AnimationSerializing;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.BattleAnimationInput;

/**
 * The editor's gameplay preview poses the player the way a battle poses its own side, frame by frame.
 *
 * <p>Since the battle animation split its triggers into presses and judgements, a battle hands the layer every key that
 * goes down as a press and, as held lanes, the hold notes being sung together with the lane keys that are down
 * (ClientBattleController.applyPlayerAnimation). The preview presses the same way, but it used to pass the hold notes
 * alone as held lanes: a key held on a tap note, or on no note, let the body fall back to idle when its press's window
 * ran out, and a held key did not take the body back after a tap on another lane. Here the preview's own mask
 * (TrackGameplayPreviewScreen.heldLanes, and the screen's previewAnimationHeldMask) and the battle's inputs go through
 * the real clips and the real Player Animator layer side by side, ticked at 20 Hz and drawn at 60 fps, and every frame
 * has to show the same pose. The mask the preview used before fails the held-key scenarios, which this check also
 * asserts, so it cannot pass by being blind to them. No Minecraft window.
 */
public final class PreviewAnimationChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> PARTS = List.of("head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static final float FRAME = 1.0F / 3.0F;
    private static int checks;

    /** A lane key going down or up at a battle tick on the 60 fps grid; on the way down, whether it hits a hold note. */
    private record Event(float tick, int lane, boolean down, boolean holdNote) {
        static Event down(float tick, int lane, boolean holdNote) {
            return new Event(tick, lane, true, holdNote);
        }

        static Event up(float tick, int lane) {
            return new Event(tick, lane, false, false);
        }
    }

    /** A hold note the preview finishes while its key is still down (completeHold), without the key going up. */
    private record HoldEnd(float tick, int lane) {
    }

    private record Scenario(String name, int bpm, float end, List<Event> events, List<HoldEnd> holdEnds) {
    }

    public static void main(String[] args) throws Exception {
        loadRealClips(Path.of(args[0]));
        heldLanesJoinHoldNotesAndKeys();
        theScreenReportsItsOwnKeys();
        for (Scenario scenario : scenarios()) {
            previewMatchesTheBattle(scenario);
        }
        aHeldKeyKeepsItsPoseInThePreview();
        theOldMaskFailsTheHeldKeyScenarios();
        System.out.println("Preview animation checks: " + checks);
        FAILURES.stream().limit(25).forEach(System.err::println);
        if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " failed checks");
        System.out.println("Preview animation regression PASS");
    }

    @SuppressWarnings({"unchecked", "removal"})
    private static void loadRealClips(Path root) throws Exception {
        Field field = PlayerAnimationRegistry.class.getDeclaredField("animations");
        field.setAccessible(true);
        Map<ResourceLocation, IPlayable> registry = (Map<ResourceLocation, IPlayable>) field.get(null);
        for (String name : List.of("idle", "lane_0", "lane_1", "lane_2", "lane_3", "hold_0", "hold_1", "hold_2", "hold_3")) {
            try (var reader = Files.newBufferedReader(root.resolve("src/main/resources/assets/music_battle/player_animations/" + name + ".json"))) {
                registry.put(MusicBattleMod.id(name), AnimationSerializing.deserializeAnimation(reader).getFirst());
            }
        }
    }

    /** Every combination of hold-note lanes and keys down: the union, never a fifth lane, whatever the array holds. */
    private static void heldLanesJoinHoldNotesAndKeys() {
        for (int holds = 0; holds < 16; holds++) {
            for (int keys = 0; keys < 16; keys++) {
                boolean[] down = new boolean[4];
                for (int lane = 0; lane < 4; lane++) down[lane] = (keys & (1 << lane)) != 0;
                int mask = TrackGameplayPreviewScreen.heldLanes(holds, down);
                check(mask == (holds | keys), "hold notes " + bits(holds) + " and keys " + bits(keys) + " give "
                        + bits(mask));
            }
        }
        check(TrackGameplayPreviewScreen.heldLanes(0xF0 | 0b0001, new boolean[]{false, false, false, false}) == 0b0001,
                "bits past the fourth lane are not lanes");
        check(TrackGameplayPreviewScreen.heldLanes(0, new boolean[]{true, false, false, false, true, true}) == 0b0001,
                "a longer key array adds no lane past the fourth");
        check(TrackGameplayPreviewScreen.heldLanes(0b0100, new boolean[0]) == 0b0100, "no keys leave the hold notes");
    }

    /** The screen's own getter, on a real instance: what it hands the layer is its keys and its hold notes together. */
    private static void theScreenReportsItsOwnKeys() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        var screen = (TrackGameplayPreviewScreen) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, TrackGameplayPreviewScreen.class);
        Field keys = TrackGameplayPreviewScreen.class.getDeclaredField("laneDown");
        keys.setAccessible(true);
        Field holds = TrackGameplayPreviewScreen.class.getDeclaredField("animationHeldMask");
        holds.setAccessible(true);
        boolean[] down = new boolean[4];
        keys.set(screen, down);
        holds.setInt(screen, 0b0100);
        check(screen.previewAnimationHeldMask() == 0b0100, "a hold note being sung holds its lane");
        down[1] = true;
        check(screen.previewAnimationHeldMask() == 0b0110, "a key down on a tap note holds its lane too, as in a battle");
        holds.setInt(screen, 0);
        down[1] = false;
        down[3] = true;
        check(screen.previewAnimationHeldMask() == 0b1000, "a key held on no note at all holds its lane");
        down[3] = false;
        check(screen.previewAnimationHeldMask() == 0, "nothing held when every key is up and no hold note sings");
    }

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        for (int bpm : new int[]{60, 120, 173, 240}) {
            for (int lane = 0; lane < 4; lane++) {
                // A tap note whose key stays down for a second and a half: far past the longest press window.
                list.add(new Scenario("bpm " + bpm + " lane " + lane + " held 30 ticks on a tap note", bpm, 50,
                        List.of(Event.down(3, lane, false), Event.up(33, lane)), List.of()));
                // A held key, a tap on another lane, and the held key taking the body back once the tap's window ends.
                int other = (lane + 2) % 4;
                list.add(new Scenario("bpm " + bpm + " lane " + lane + " held while lane " + other + " is tapped", bpm, 50,
                        List.of(Event.down(3, lane, false), Event.down(5, other, false), Event.up(6, other),
                                Event.up(35, lane)), List.of()));
                // A hold note the preview completes at its end while the key is still down, then the key goes up.
                list.add(new Scenario("bpm " + bpm + " lane " + lane + " hold note finished under a held key", bpm, 50,
                        List.of(Event.down(3, lane, true), Event.up(24, lane)), List.of(new HoldEnd(13, lane))));
                // The original report: a tap 5-8 ticks after a hold began on the same lane.
                for (float gap : new float[]{5.0F, 6.0F, 8.0F}) {
                    list.add(new Scenario("bpm " + bpm + " lane " + lane + " tapped " + gap + " ticks after a hold began",
                            bpm, 40, List.of(Event.down(5, lane, true), Event.up(8, lane),
                            Event.down(5 + gap, lane, false), Event.up(5 + gap + FRAME * 2, lane)), List.of()));
                }
            }
            // Quick alternating taps, each key up after a frame or two, some overlapping.
            list.add(new Scenario("bpm " + bpm + " quick taps", bpm, 40, List.of(
                    Event.down(2, 0, false), Event.up(2 + FRAME, 0),
                    Event.down(3, 3, false), Event.down(3 + FRAME, 1, false), Event.up(4, 3), Event.up(4 + FRAME, 1),
                    Event.down(6, 2, true), Event.down(7, 0, false), Event.up(7 + FRAME, 0), Event.up(12, 2),
                    Event.down(13, 1, false), Event.up(25, 1)), List.of()));
        }
        return list;
    }

    /** The preview and the battle given the same keys and notes show the same pose on every frame. */
    private static void previewMatchesTheBattle(Scenario scenario) {
        Preview preview = new Preview(false);
        Battle battle = new Battle();
        int diverged = run(scenario, preview, battle);
        check(diverged < 0, scenario.name() + ": the preview shows the battle's pose on every frame"
                + (diverged < 0 ? "" : ", not from frame " + diverged));
    }

    /**
     * The reported shape, stated plainly: a key held well past its press's window keeps its lane's pose in the preview,
     * and the body returns to idle only once the key is up.
     */
    private static void aHeldKeyKeepsItsPoseInThePreview() {
        for (int bpm : new int[]{60, 120, 240}) {
            float window = ClientBattleAnimation.pressPoseTicks(bpm);
            for (int lane = 0; lane < 4; lane++) {
                Preview preview = new Preview(false);
                float now = 0;
                preview.render(bpm, now);
                now = 3;
                preview.apply(Event.down(now, lane, false));
                preview.render(bpm, now);
                float pressed = now;
                while (now < pressed + window + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + 10) {
                    now += FRAME;
                    preview.render(bpm, now);
                }
                check(!isIdle(preview.holder, bpm, now), "bpm " + bpm + " lane " + lane + ": a key held "
                        + (now - pressed) + " ticks keeps a pose, not idle");
                check(distance(preview.holder, reference(lane, bpm, now), now) < 1.0e-4,
                        "bpm " + bpm + " lane " + lane + ": the held key shows its own lane's pose");
                preview.apply(Event.up(now, lane));
                float released = now;
                while (now < released + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + FRAME) {
                    now += FRAME;
                    preview.render(bpm, now);
                }
                check(isIdle(preview.holder, bpm, now), "bpm " + bpm + " lane " + lane
                        + ": the body is back at idle once the key is up");
            }
        }
    }

    /** The hold notes alone, as the preview passed them before, part from the battle wherever a key is held. */
    private static void theOldMaskFailsTheHeldKeyScenarios() {
        int caught = 0;
        int heldKeyScenarios = 0;
        for (Scenario scenario : scenarios()) {
            if (!scenario.name().contains("held 30 ticks") && !scenario.name().contains("is tapped")) continue;
            heldKeyScenarios++;
            if (run(scenario, new Preview(true), new Battle()) >= 0) caught++;
        }
        check(heldKeyScenarios > 0 && caught == heldKeyScenarios, "the old mask parts from the battle in every held-key"
                + " scenario: " + caught + " of " + heldKeyScenarios);
    }

    /** Plays a scenario at 60 fps into both; returns the first frame whose poses differ, or -1. */
    private static int run(Scenario scenario, Preview preview, Battle battle) {
        List<Event> events = new ArrayList<>(scenario.events());
        List<HoldEnd> holdEnds = new ArrayList<>(scenario.holdEnds());
        int ticked = 0;
        int frame = 0;
        for (float now = 0; now <= scenario.end() + 1.0e-4F; now = ++frame * FRAME) {
            // Player Animator ticks every layer once per client tick; nothing in the battle layer may depend on it.
            while (ticked < (int) Math.floor(now + 1.0e-4F)) {
                preview.holder.layer.tick();
                battle.holder.layer.tick();
                ticked++;
            }
            while (!events.isEmpty() && events.getFirst().tick() <= now + 1.0e-4F) {
                Event event = events.removeFirst();
                preview.apply(event);
                battle.apply(event, now);
            }
            while (!holdEnds.isEmpty() && holdEnds.getFirst().tick() <= now + 1.0e-4F) {
                preview.finishHold(holdEnds.removeFirst().lane());
            }
            preview.render(scenario.bpm(), now);
            battle.render(scenario.bpm(), now);
            if (distance(preview.holder, battle.holder, now) > 1.0e-4) {
                return frame;
            }
        }
        return -1;
    }

    /**
     * The preview screen's state as pressLane, registerNoteHit, releaseLane and completeHold leave it, and the layer
     * update ClientBattleController.applyPlayerAnimation makes for it: every trigger a press, nothing judged.
     */
    private static final class Preview {
        final ClientBattleAnimation.Holder holder = ClientBattleAnimation.createLayer();
        final boolean[] laneDown = new boolean[4];
        final boolean holdNotesOnly;
        int holdNotes;
        int serial;
        int lane = -1;

        Preview(boolean holdNotesOnly) {
            this.holdNotesOnly = holdNotesOnly;
        }

        void apply(Event event) {
            if (event.down()) {
                if (laneDown[event.lane()]) return;
                laneDown[event.lane()] = true;
                lane = event.lane();
                serial++;
                if (event.holdNote()) holdNotes |= 1 << event.lane();
            } else {
                laneDown[event.lane()] = false;
                holdNotes &= ~(1 << event.lane());
            }
        }

        void finishHold(int lane) {
            holdNotes &= ~(1 << lane);
        }

        void render(int bpm, float now) {
            int held = holdNotesOnly ? holdNotes : TrackGameplayPreviewScreen.heldLanes(holdNotes, laneDown);
            ClientBattleAnimation.update(holder, serial, lane, held, bpm, now);
        }
    }

    /**
     * The battle's own side, as ClientBattleController.sendLaneState and applyPlayerAnimation feed it: the press stamped
     * with the battle clock, the hold note and the key as held lanes, no judgements of its own presses on screen.
     */
    private static final class Battle {
        final ClientBattleAnimation.Holder holder = ClientBattleAnimation.createLayer();
        int serial;
        int lane = -1;
        float pressTick = -Float.MAX_VALUE;
        int heldMask;
        int inputHeldMask;

        void apply(Event event, float now) {
            if (event.down()) {
                serial++;
                lane = event.lane();
                pressTick = now;
                heldMask = BattleAnimationInput.updateHeldMask(heldMask, event.lane(), event.holdNote());
                inputHeldMask = BattleAnimationInput.updateHeldMask(inputHeldMask, event.lane(), true);
            } else {
                heldMask = BattleAnimationInput.updateHeldMask(heldMask, event.lane(), false);
                inputHeldMask = BattleAnimationInput.updateHeldMask(inputHeldMask, event.lane(), false);
            }
        }

        void render(int bpm, float now) {
            ClientBattleAnimation.update(holder, serial, lane, pressTick, 0, -1, Float.NaN, heldMask | inputHeldMask,
                    bpm, now);
        }
    }

    /** A fresh layer showing only idle, or only one lane's held pose, at this tick. */
    private static ClientBattleAnimation.Holder reference(int lane, int bpm, float tick) {
        var holder = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(holder, 0, Math.max(lane, 0), lane >= 0 ? 1 << lane : 0, bpm, tick);
        return holder;
    }

    private static boolean isIdle(ClientBattleAnimation.Holder holder, int bpm, float tick) {
        return distance(holder, reference(-1, bpm, tick), tick) < 1.0e-4;
    }

    private static double distance(ClientBattleAnimation.Holder first, ClientBattleAnimation.Holder second, float tick) {
        double sum = 0;
        for (String part : PARTS) for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
            Vec3f a = transform(first, part, type, tick);
            Vec3f b = transform(second, part, type, tick);
            sum += Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
        }
        return sum;
    }

    private static Vec3f transform(ClientBattleAnimation.Holder holder, String part, TransformType type, float tick) {
        float partial = tick - (float) Math.floor(tick);
        holder.layer.setupAnim(partial);
        return holder.layer.get3DTransform(part, type, partial, new Vec3f(0, 0, 0));
    }

    private static String bits(int mask) {
        return String.format("%4s", Integer.toBinaryString(mask & 0xFF)).replace(' ', '0');
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }
}
