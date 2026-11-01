package ru.gora.musicbattle.client;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.core.data.gson.AnimationSerializing;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Every lane press reaches the battle once, and shows on the body once.
 *
 * <p>Part one runs the real BattleLaneLatch the way ClientInputIsolation feeds it and ClientBattleController drains
 * it: key events at any moment, client ticks every 50 ms. The battle used to read the keys only at the ticks, so a tap
 * that went down and came back up between two ticks sent nothing at all: no hit, a miss, and no pose. The code before
 * the fix fails the short-tap groups; the measurement printed at the end is how many taps of each length it lost.
 * The lanes after a single-player pause are the real ClientBattleController#resumeLanesAfterGamePause and the real
 * ClientInputIsolation#laneKeyEvent: a lane held through the menu carries on, and a key that went down in the menu -
 * the mouse button that clicked "Back to Game", say - is no press, at the resume or at its first key repeat.
 *
 * <p>Part two plays a battle's triggers through the real ClientBattleAnimation in the orders the controller makes
 * them, and counts the presses that land (a press is the moment the holder's punch starts). A press of a key shows
 * once however its judgement is ordered against it: an early hit judged when its note reaches the line, later still
 * with a negative input offset, and another player's hit, judged by the server before it passes on the key. A mob's
 * every note is a press, its hold notes included. No Minecraft window.
 */
public final class BattleInputLatchChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static final float FRAME = 1.0F / 3.0F;
    private static int checks;

    public static void main(String[] args) throws Exception {
        loadRealClips(Path.of(args[0]));
        aShortTapIsSentOnce();
        aHeldKeyIsSentOnceWithItsRepeats();
        twoTapsInOneTickAreTwoPresses();
        aReleaseAndPressInOneTickAreBoth();
        aKeyDownBeforeTheBattleStillCounts();
        clearedPressesAreNotSent();
        randomPlayingKeepsTheLaneInStep();
        aPauseKeepsItsHoldsAndMakesNoPresses();
        ownEarlyPressesShowOncePerKey();
        anOpponentsPressesShowOncePerKey();
        everyMobNoteShows();
        measureTapsTheOldPollingLost();
        System.out.println("Battle input latch checks: " + checks);
        FAILURES.stream().limit(25).forEach(System.err::println);
        if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " failed checks");
        System.out.println("Battle input latch regression PASS");
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

    // ------------------------------------------------------------------------------------------------ part one

    /** A tap shorter than a tick, at every phase against the ticks: one key-down then one key-up, at the next tick. */
    private static void aShortTapIsSentOnce() {
        for (int lengthMs = 5; lengthMs < 50; lengthMs += 5) {
            for (int phaseMs = 1; phaseMs + lengthMs < 50; phaseMs += 4) {
                Keys keys = new Keys();
                keys.tick();
                keys.event(0, true);
                keys.event(0, false);
                List<String> sent = keys.tick();
                String context = lengthMs + " ms tap " + phaseMs + " ms after a tick";
                check(sent.equals(List.of("0 down", "0 up")), context + " is sent as one press, not " + sent);
                check(keys.tick().isEmpty(), context + " is not sent again on the next tick");
            }
        }
    }

    /** A key held over many ticks, with the system's key-repeat events, goes down once and up once. */
    private static void aHeldKeyIsSentOnceWithItsRepeats() {
        Keys keys = new Keys();
        keys.event(2, true);
        check(keys.tick().equals(List.of("2 down")), "a held key goes down at the first tick");
        for (int tick = 0; tick < 10; tick++) {
            keys.repeat(2);
            check(keys.tick().isEmpty(), "a held key's repeats are not presses, tick " + tick);
        }
        keys.event(2, false);
        check(keys.tick().equals(List.of("2 up")), "a held key goes up once, at the tick after its release");
    }

    private static void twoTapsInOneTickAreTwoPresses() {
        Keys keys = new Keys();
        keys.event(1, true);
        keys.event(1, false);
        keys.event(1, true);
        keys.event(1, false);
        check(keys.tick().equals(List.of("1 down", "1 up", "1 down", "1 up")), "two taps between ticks are two presses");
        keys.event(3, true);
        keys.event(3, false);
        keys.event(3, true);
        check(keys.tick().equals(List.of("3 down", "3 up", "3 down")), "a tap then a press between ticks: the press stays down");
    }

    private static void aReleaseAndPressInOneTickAreBoth() {
        Keys keys = new Keys();
        keys.event(0, true);
        keys.tick();
        keys.event(0, false);
        keys.event(0, true);
        check(keys.tick().equals(List.of("0 up", "0 down")), "letting go and pressing again between ticks is a new press");
    }

    /** A key the battle finds down with no event of its own (vanilla saw it go down before the battle took the keys). */
    private static void aKeyDownBeforeTheBattleStillCounts() {
        Keys keys = new Keys();
        keys.down[1] = true;
        check(keys.tick().equals(List.of("1 down")), "a key already down when the battle took the keys is one press");
        check(keys.tick().isEmpty(), "and only one");
    }

    private static void clearedPressesAreNotSent() {
        Keys keys = new Keys();
        keys.event(2, true);
        keys.event(2, false);
        keys.latch.clear();
        check(keys.tick().isEmpty(), "presses made while the lanes were not played are dropped");
        for (int press = 0; press < 10; press++) {
            keys.event(3, true);
            keys.event(3, false);
        }
        List<String> sent = keys.tick();
        check(sent.size() == 2 * BattleLaneLatch.MAX_PRESSES_PER_TICK,
                "a stuck key's flood is cut to " + BattleLaneLatch.MAX_PRESSES_PER_TICK + " presses a tick, sent " + sent.size() / 2);
    }

    /**
     * Random playing on all four lanes: every press reaches the server exactly once, the lane changes alternate, and
     * after every tick what was sent matches the key.
     */
    private static void randomPlayingKeepsTheLaneInStep() {
        Random random = new Random(20261027L);
        for (int run = 0; run < 200; run++) {
            Keys keys = new Keys();
            int[] pressed = new int[4];
            int[] sentDowns = new int[4];
            boolean[] lastSent = new boolean[4];
            for (int tick = 0; tick < 400; tick++) {
                int events = random.nextInt(4);
                for (int event = 0; event < events; event++) {
                    int lane = random.nextInt(4);
                    int kind = random.nextInt(3);
                    if (kind == 2) {
                        keys.repeat(lane);
                    } else {
                        boolean down = kind == 0;
                        if (down && !keys.down[lane]) pressed[lane]++;
                        keys.event(lane, down);
                    }
                }
                for (String change : keys.tick()) {
                    int lane = change.charAt(0) - '0';
                    boolean down = change.endsWith("down");
                    check(down != lastSent[lane], "run " + run + " tick " + tick + ": lane " + lane
                            + " changes alternate, but sent " + change + " twice");
                    lastSent[lane] = down;
                    if (down) sentDowns[lane]++;
                }
                for (int lane = 0; lane < 4; lane++) {
                    check(lastSent[lane] == keys.down[lane], "run " + run + " tick " + tick + ": lane " + lane
                            + " sent as " + (lastSent[lane] ? "down" : "up") + " while the key is " + (keys.down[lane] ? "down" : "up"));
                }
            }
            for (int lane = 0; lane < 4; lane++) {
                check(sentDowns[lane] == pressed[lane], "run " + run + ": lane " + lane + " pressed " + pressed[lane]
                        + " times, sent " + sentDowns[lane]);
            }
        }
    }

    /**
     * The lanes when a single-player pause ends, through the real ClientBattleController#resumeLanesAfterGamePause,
     * for a lane on a keyboard key and on the left mouse button: before the pause up or down, during it the key held
     * through, let go, pressed and still down at the resume, pressed and let go, or let go and pressed again. The key
     * mapping is checked both as the pause screen leaves it (KeyMapping.releaseAll, and on macOS nothing after) and as
     * Minecraft sets it from the keyboard when the menu closes off macOS (KeyMapping.setAll). Then the next client
     * tick's lane loop drains the real latch, and a keyboard key still down sends its repeats through the real
     * ClientInputIsolation#laneKeyEvent. Without a game: sending is skipped (no battle). A repeat that reached
     * ClientInputIsolation's setLanes would press the lane, and the next one would fail on the key mapping's isDown(),
     * which asks for a game once the mapping is down; either is the check's failure.
     */
    @SuppressWarnings("unchecked")
    private static void aPauseKeepsItsHoldsAndMakesNoPresses() throws Exception {
        KeyMapping[] keys = MusicBattleKeys.lanes();
        Field heldField = ClientInputIsolation.class.getDeclaredField("HELD");
        heldField.setAccessible(true);
        Set<InputConstants.Key> held = (Set<InputConstants.Key>) heldField.get(null);
        Field sentField = ClientBattleController.class.getDeclaredField("previousLaneDown");
        sentField.setAccessible(true);
        boolean[] sent = (boolean[]) sentField.get(null);
        Method resume = ClientBattleController.class.getDeclaredMethod("resumeLanesAfterGamePause");
        resume.setAccessible(true);
        // KeyMapping#isDown asks NeoForge for the key's context, which needs a game; with no screen open, in a battle,
        // the flag itself is what the client tick reads.
        Field rawDown = KeyMapping.class.getDeclaredField("isDown");
        rawDown.setAccessible(true);
        InputConstants.Key saved = keys[0].getKey();
        InputConstants.Key keyboard = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_D);
        InputConstants.Key mouse = InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        // The old rule, the key's state alone: a key that went down in the menu was down at the next tick with no
        // press counted, and the latch sends such a key as one press.
        check(BattleLaneLatch.edges(false, 0, true) == 1,
                "reproduced: a key down at the tick and never sent is a press");
        String[] pauses = {"held through", "let go", "pressed, still down", "pressed and let go",
                "let go, pressed again"};
        int cases = 0;
        try {
            for (InputConstants.Key binding : List.of(keyboard, mouse)) {
                keys[0].setKey(binding);
                for (boolean downBefore : new boolean[]{false, true}) {
                    for (int pause = 0; pause < pauses.length; pause++) {
                        boolean downAfter = switch (pause) {
                            case 0 -> downBefore;
                            case 1, 3 -> false;
                            default -> true;
                        };
                        for (boolean setAll : new boolean[]{false, true}) {
                            String context = (binding == mouse ? "mouse" : "key") + ", lane "
                                    + (downBefore ? "down" : "up") + " before the pause, " + pauses[pause]
                                    + (setAll ? ", key mappings set from the keyboard"
                                    : ", key mappings as the menu left them");
                            sent[0] = downBefore;
                            BattleLaneLatch.LANES.clear();
                            held.clear();
                            if (downAfter) {
                                held.add(binding);
                            }
                            keys[0].setDown(setAll && downAfter && binding == keyboard);
                            resume.invoke(null);
                            boolean carries = downBefore && downAfter;
                            boolean mapped = rawDown.getBoolean(keys[0]);
                            check(sent[0] == carries, context + ": the lane is " + (carries ? "still down" : "up")
                                    + " after the resume, sent " + sent[0]);
                            check(mapped == carries, context + ": its key mapping is " + (carries ? "down" : "up")
                                    + ", is " + mapped);
                            int changes = BattleLaneLatch.LANES.drain(0, sent[0], mapped);
                            check(changes == 0, context + ": the next tick sends nothing, sent " + changes
                                    + " changes");
                            if (binding == keyboard && downAfter) {
                                // The key stays down: the system starts repeating it.
                                for (int repeat = 0; repeat < 6; repeat++) {
                                    try {
                                        ClientInputIsolation.laneKeyEvent(binding, GLFW.GLFW_REPEAT);
                                    } catch (RuntimeException reached) {
                                        check(false, context + ": a key repeat reached the lane (" + reached + ")");
                                        break;
                                    }
                                }
                                check(rawDown.getBoolean(keys[0]) == carries, context
                                        + ": its repeats leave the mapping " + (carries ? "down" : "up"));
                                int repeated = BattleLaneLatch.LANES.drain(0, sent[0], rawDown.getBoolean(keys[0]));
                                check(repeated == 0, context + ": its repeats are no press, sent " + repeated
                                        + " changes");
                            }
                            cases++;
                        }
                    }
                }
            }
        } finally {
            keys[0].setKey(saved);
            keys[0].setDown(false);
            sent[0] = false;
            held.clear();
            BattleLaneLatch.LANES.clear();
        }
        check(cases == 2 * 2 * pauses.length * 2, "every pause case ran, " + cases);
    }

    /** The keys as ClientInputIsolation sets them, and the lane states ClientBattleController sends at each tick. */
    private static final class Keys {
        private final BattleLaneLatch latch = new BattleLaneLatch();
        private final boolean[] down = new boolean[4];
        private final boolean[] sent = new boolean[4];

        /** A key event, as ClientInputIsolation.setLanes handles it. */
        private void event(int lane, boolean pressed) {
            latch.keyChanged(lane, down[lane], pressed);
            down[lane] = pressed;
        }

        /**
         * The system's key repeat. It never reaches setLanes (ClientInputIsolation#laneKeyEvent); before that it came
         * as the key being down, which for a key that is down changes nothing, so the model keeps that older form.
         */
        private void repeat(int lane) {
            if (down[lane]) event(lane, true);
        }

        /** One client tick of ClientBattleController's lane loop; returns the lane changes it sends, in order. */
        private List<String> tick() {
            List<String> changes = new ArrayList<>();
            for (int lane = 0; lane < 4; lane++) {
                int count = latch.drain(lane, sent[lane], down[lane]);
                for (int change = 0; change < count; change++) {
                    sent[lane] = !sent[lane];
                    changes.add(lane + (sent[lane] ? " down" : " up"));
                }
            }
            return changes;
        }
    }

    /**
     * How many taps the battle used to lose: taps of each length at random moments against the 50 ms ticks, read the
     * old way (the key's state at each tick) and through the latch.
     */
    private static void measureTapsTheOldPollingLost() {
        Random random = new Random(7L);
        StringBuilder report = new StringBuilder("Taps lost reading the keys only at ticks:");
        for (int lengthMs : new int[]{20, 35, 50, 65, 80, 100, 130}) {
            int lostOld = 0;
            int lostNew = 0;
            int taps = 2000;
            for (int tap = 0; tap < taps; tap++) {
                double start = random.nextDouble() * 50.0;
                double end = start + lengthMs;
                // The old way: the key is seen only if a tick (at every multiple of 50 ms) falls while it is down.
                boolean seen = Math.ceil(start / 50.0) * 50.0 < end;
                if (!seen) lostOld++;
                Keys keys = new Keys();
                keys.event(0, true);
                List<String> sent = new ArrayList<>();
                if (Math.ceil(start / 50.0) * 50.0 < end) sent.addAll(keys.tick());
                keys.event(0, false);
                sent.addAll(keys.tick());
                if (!sent.contains("0 down")) lostNew++;
            }
            check(lostNew == 0, lengthMs + " ms taps: the latch loses none, lost " + lostNew);
            if (lengthMs < 50) {
                check(lostOld > 0, lengthMs + " ms taps: the old reading loses some, which is what the latch is for");
            }
            report.append(String.format(java.util.Locale.ROOT, " %d ms %.0f%%", lengthMs, 100.0 * lostOld / taps));
        }
        System.out.println(report);
    }

    // ------------------------------------------------------------------------------------------------ part two

    /**
     * The player's own side: the key-down is the press, latched to the next tick; an early hit's judgement is let
     * through when its note reaches the line, later with a negative input offset (up to 500 ms, 10 ticks), and it is
     * marked as a judgement because our own side plays with keys.
     */
    private static void ownEarlyPressesShowOncePerKey() {
        Random random = new Random(11L);
        for (int bpm : new int[]{96, 120, 173, 240}) {
            for (int offsetTicks : new int[]{0, 2, 5, 10}) {
                Participant side = new Participant(bpm);
                float now = 4;
                int keyPresses = 0;
                for (int note = 0; note < 120; note++) {
                    now += 1 + random.nextInt(8) + random.nextFloat();
                    int lane = random.nextInt(4);
                    float early = random.nextFloat() * 6;
                    float keyDown = (float) Math.ceil(now);
                    side.at(keyDown, () -> side.keyDown(lane));
                    // Up before the next key-down, which is at least a tick later.
                    side.at(keyDown + 0.2F + random.nextFloat() * 0.7F, () -> side.keyUp(lane));
                    // An early hit's judgement goes out when its note reaches the line; the offset holds it back further.
                    side.at(keyDown + early + offsetTicks, () -> side.judgement(lane));
                    keyPresses++;
                }
                side.playUntil(now + 40);
                check(side.presses == keyPresses, "own side bpm " + bpm + " offset -" + offsetTicks * 50 + " ms: "
                        + keyPresses + " key presses showed as " + side.presses);
                check(side.wrongLane == 0, "own side bpm " + bpm + " offset -" + offsetTicks * 50 + " ms: "
                        + side.wrongLane + " presses did not show their own lane");
            }
        }
    }

    /**
     * Another player: the server judges the hit before it passes on the key, because that client sends the judgement
     * first, so the judgement can arrive frames before the key-down; or the key first, when the hit was early and
     * waits for its note. One press per key either way.
     */
    private static void anOpponentsPressesShowOncePerKey() {
        Random random = new Random(12L);
        for (boolean judgementFirst : new boolean[]{true, false}) {
            // 0.1 puts both in one frame with the second one last; a frame or more apart, they are drawn apart.
            for (float gap : new float[]{0.0F, 0.1F, FRAME, 1.0F, 2.0F, 6.0F}) {
                Participant side = new Participant(150);
                float now = 4;
                int keyPresses = 0;
                for (int note = 0; note < 120; note++) {
                    now += 2 + random.nextInt(7) + random.nextFloat();
                    int lane = random.nextInt(4);
                    float keyDown = now + (judgementFirst ? gap : 0);
                    float judged = now + (judgementFirst ? 0 : gap);
                    side.at(judged, () -> side.judgement(lane));
                    side.at(keyDown, () -> side.keyDown(lane));
                    side.at(keyDown + 0.5F + random.nextFloat(), () -> side.keyUp(lane));
                    keyPresses++;
                }
                side.playUntil(now + 40);
                String context = "opponent, " + (judgementFirst ? "judgement" : "key") + " first by " + gap + " ticks";
                check(side.presses == keyPresses, context + ": " + keyPresses + " key presses showed as " + side.presses);
                check(side.wrongLane == 0, context + ": " + side.wrongLane + " presses did not show their own lane");
            }
        }
    }

    /** A mob: no keys; each judged hit is a press, and a hold note holds its lane without being a key. */
    private static void everyMobNoteShows() {
        Random random = new Random(13L);
        for (int bpm : new int[]{96, 173}) {
            Participant side = new Participant(bpm);
            float now = 4;
            int notes = 0;
            for (int note = 0; note < 200; note++) {
                // As close as a 2x battle puts them: one tick apart at the tightest.
                now += 1 + random.nextInt(5);
                int lane = random.nextInt(4);
                boolean hold = random.nextInt(3) == 0;
                float at = now;
                side.at(at, () -> side.mobHit(lane, hold));
                if (hold) {
                    float length = 1 + random.nextInt(4);
                    side.at(at + length, () -> side.noteReleased(lane));
                    now += length;
                }
                notes++;
            }
            side.playUntil(now + 40);
            check(side.presses == notes, "mob bpm " + bpm + ": " + notes + " notes showed as " + side.presses);
            check(side.wrongLane == 0, "mob bpm " + bpm + ": " + side.wrongLane + " notes did not show their own lane");
        }
    }

    /**
     * One participant's triggers as ClientBattleController keeps them, drawn at 60 fps: presses and, apart from them,
     * the judgements of key presses, each a serial with the latest one's lane and tick, and every held lane.
     */
    private static final class Participant {
        private static final Field PUNCHED_AT;
        private static final Field PRESS_LANE;

        static {
            try {
                PUNCHED_AT = ClientBattleAnimation.Holder.class.getDeclaredField("punchedAtTick");
                PRESS_LANE = ClientBattleAnimation.Holder.class.getDeclaredField("pressLane");
                PUNCHED_AT.setAccessible(true);
                PRESS_LANE.setAccessible(true);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private final ClientBattleAnimation.Holder holder = ClientBattleAnimation.createLayer();
        private final int bpm;
        private final List<Float> times = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();
        private int serial;
        private int lane = -1;
        private float triggerTick = -Float.MAX_VALUE;
        private int judgedSerial;
        private int judgedLane = -1;
        private float judgedTick = -Float.MAX_VALUE;
        private int heldMask;
        private float now;
        /** The lane each key-down or mob hit of this frame asked for: a press must show one of them. */
        private int pressedThisFrame;
        private int presses;
        private int wrongLane;

        private Participant(int bpm) {
            this.bpm = bpm;
        }

        private void at(float time, Runnable action) {
            int index = 0;
            while (index < times.size() && times.get(index) <= time) index++;
            times.add(index, time);
            actions.add(index, action);
        }

        private void keyDown(int lane) {
            press(lane);
            heldMask |= 1 << lane;
        }

        private void keyUp(int lane) {
            heldMask &= ~(1 << lane);
        }

        private void judgement(int lane) {
            judgedSerial++;
            judgedLane = lane;
            judgedTick = now;
        }

        private void mobHit(int lane, boolean hold) {
            press(lane);
            if (hold) heldMask |= 1 << lane;
        }

        private void noteReleased(int lane) {
            heldMask &= ~(1 << lane);
        }

        private void press(int lane) {
            serial++;
            this.lane = lane;
            triggerTick = now;
            pressedThisFrame |= 1 << lane;
        }

        private void playUntil(float end) {
            int next = 0;
            for (now = 0; now < end; now += FRAME) {
                pressedThisFrame = 0;
                while (next < times.size() && times.get(next) <= now) {
                    actions.get(next++).run();
                }
                float punchedBefore = punchedAt();
                ClientBattleAnimation.update(holder, serial, lane, triggerTick, judgedSerial, judgedLane, judgedTick,
                        heldMask, bpm, now);
                if (punchedAt() != punchedBefore) {
                    presses++;
                    int shown = pressLane();
                    if (shown < 0 || (pressedThisFrame & (1 << shown)) == 0) wrongLane++;
                }
            }
        }

        private float punchedAt() {
            try {
                return PUNCHED_AT.getFloat(holder);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private int pressLane() {
            try {
                return PRESS_LANE.getInt(holder);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }
}
