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

/**
 * How long a battle press shows its pose, how it lands and how it hands over to the next one, watched frame by frame.
 *
 * <p>The real clips go through the real Player Animator layer. Frames come at 60 fps and the layer is ticked at
 * 20 Hz, as Player Animator ticks it in game; every frame is compared with fresh layers that show only idle or only
 * one lane's plain pose at the same battle tick. The beta report was that a pose "plays for literally a fraction of a
 * second" and that switching should be sharper: a tap ended its pose with the key going up, and a hit just before a
 * beat line ended at that line. The code before the fix fails every group but four: the tempo bounds, the held key,
 * and two that measure the shape of the press punch, which that code does not have. With the punch switched off, the
 * repeated-press group fails: a note repeated on the lane already posed changes nothing on screen. Counting every
 * trigger as a new press fails the five groups about judgements of key presses: an early press's judgement, held back
 * until its note reaches the line, punches the body a second time, takes it back from a key pressed since, hides a key
 * that went down in its frame, or punches again however late it comes, and an opponent's judgement that arrives
 * before its key punches twice. Telling a judgement from a press by time, as the code did before the controller marked
 * its judgements, fails four groups: a mob's note soon after its hold, a preview tap soon after a hold, a judgement
 * held back past 8 ticks by a negative input offset, and an opponent's judgement that arrives before its key. No
 * Minecraft window.
 */
public final class BattleAnimationTimingChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> PARTS = List.of("head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static final float FRAME = 1.0F / 3.0F;
    private static final float PUNCH = ClientBattleAnimation.PRESS_PUNCH;
    private static final float PUNCH_TICKS = ClientBattleAnimation.PRESS_PUNCH_TICKS;
    private static final int IDLE = -1;
    private static final int BLEND = -2;
    private static int checks;

    public static void main(String[] args) throws Exception {
        loadRealClips(Path.of(args[0]));
        quickTapHoldsItsPose();
        beatLineDoesNotCutAPressShort();
        windowFollowsTheTempoWithinBounds();
        pressesSwitchPoseInstantly();
        everyPressLandsWithAPunch();
        punchSettlesFrontLoaded();
        repeatedPressOnTheSameLaneShows();
        returnToIdleIsShortAndFrontLoaded();
        heldKeyKeepsItsPoseUntilRelease();
        tapDuringAHoldReturnsToTheHeldDirection();
        repeatedPressExtendsThePose();
        opponentDownUpAndHitStayOnePose();
        earlyPressPunchesOnce();
        anEarlierKeysJudgementLeavesTheNewerPose();
        aKeyHiddenBehindAJudgementStillShows();
        aRealPressAfterAJudgementStillPunches();
        aMobsNoteAfterItsHoldStillLands();
        editorPreviewTapAfterAHoldStillLands();
        aLateJudgementNeverPressesAgain();
        anOpponentsJudgementBeforeItsKeyPunchesOnce();
        lateLayerShowsOnlyTheRestOfTheWindow();
        lateLayerShowsOnlyTheRestOfThePunch();
        pressSeenAfterItsWindowIsNotReplayed();
        clockJumpingBackEndsThePose();
        System.out.println("Battle animation timing checks: " + checks);
        FAILURES.stream().limit(25).forEach(System.err::println);
        if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " failed checks");
        System.out.println("Battle animation timing regression PASS");
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

    /** The reported bug: a quick tap, the player's own or a judged hit, keeps its pose for the whole window. */
    private static void quickTapHoldsItsPose() {
        for (int bpm : new int[]{60, 96, 120, 150, 173, 200, 240}) {
            float window = ClientBattleAnimation.pressPoseTicks(bpm);
            for (int lane = 0; lane < 4; lane++) {
                for (boolean ownKey : new boolean[]{true, false}) {
                    String context = "bpm " + bpm + " lane " + lane + (ownKey ? " own 75 ms tap" : " judged hit");
                    Driver driver = new Driver(bpm, 0);
                    driver.until(3 + FRAME);
                    driver.press(lane, ownKey);
                    float pressed = driver.now;
                    while (driver.now < pressed + window - FRAME) {
                        if (ownKey && driver.now >= pressed + 1.5F) driver.release(lane);
                        check(driver.shown() == lane, context + " still shows its pose at +"
                                + ticks(driver.now - pressed) + " of " + ticks(window));
                        driver.frame();
                    }
                    driver.release(lane);
                    driver.until(pressed + window + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + FRAME);
                    check(driver.shown() == IDLE, context + " is back at idle once its window and the return are over");
                }
            }
        }
    }

    /** A hit is judged around its note, and notes sit on beat lines: which side of the line it lands on cannot matter. */
    private static void beatLineDoesNotCutAPressShort() {
        int bpm = 120;
        float beatLine = 20.0F;
        int expected = -1;
        for (float offset : new float[]{-0.9F, -0.4F, -0.1F, -0.02F, 0.0F, 0.1F, 0.4F, 0.9F}) {
            Driver driver = new Driver(bpm, 0);
            driver.until(beatLine - 2);
            driver.at(beatLine + offset);
            driver.press(3, false);
            int frames = 0;
            while (driver.shown() == 3 && frames < 200) {
                frames++;
                driver.frame();
            }
            if (expected < 0) expected = frames;
            check(Math.abs(frames - expected) <= 1, "a hit " + offset + " ticks from the beat line poses for "
                    + frames + " frames, not the " + expected + " of the others");
            check(frames >= Math.round(ClientBattleAnimation.pressPoseTicks(bpm) * 3) - 1,
                    "a hit " + offset + " ticks from the beat line poses for its whole window, only " + frames + " frames");
        }
    }

    private static void windowFollowsTheTempoWithinBounds() {
        for (int bpm = 20; bpm <= 800; bpm++) {
            float window = ClientBattleAnimation.pressPoseTicks(bpm);
            float beat = 1200.0F / bpm;
            check(window * 50 >= 349.9F && window * 50 <= 700.1F,
                    "bpm " + bpm + " press window " + window * 50 + " ms stays within 350-700 ms");
            if (beat <= ClientBattleAnimation.MAX_PRESS_POSE_TICKS - 2.0F) {
                // A note one beat later, even two ticks late, finds the previous pose still up and takes over from it.
                check(window >= beat + 2.0F - 0.0001F, "bpm " + bpm + " window " + window
                        + " bridges a beat to the next note, " + beat + " ticks");
            }
        }
        check(ClientBattleAnimation.pressPoseTicks(-5) == ClientBattleAnimation.pressPoseTicks(20),
                "a broken tempo is treated as the slowest one");
        // The punch is over long before the shortest window ends, so a pose never lets go while still punched.
        check(ClientBattleAnimation.MIN_PRESS_POSE_TICKS >= 2 * PUNCH_TICKS,
                "the press punch settles within the first half of the shortest press window");
        check(PUNCH > 0.0F && PUNCH <= 0.35F, "the press punch is a visible accent, not a different pose: " + PUNCH);
    }

    /** Switching: every press shows its lane on the very frame it lands, and nothing in between is idle or a blend. */
    private static void pressesSwitchPoseInstantly() {
        for (int bpm : new int[]{96, 120, 173}) {
            for (float spacing : new float[]{1.0F, 2.0F, 3.0F, 5.0F}) {
                Driver driver = new Driver(bpm, 0);
                driver.until(2);
                int[] lanes = {0, 3, 1, 2, 0, 2, 3, 1};
                for (int index = 0; index < lanes.length; index++) {
                    int lane = lanes[index];
                    driver.press(lane, true);
                    float pressed = driver.now;
                    check(driver.shown() == lane, "bpm " + bpm + " spacing " + spacing + ": press " + index
                            + " shows lane " + lane + " on its own frame");
                    while (driver.now < pressed + spacing - FRAME / 2) {
                        if (driver.now >= pressed + 0.75F) driver.release(lane);
                        int shown = driver.shown();
                        check(shown == lane, "bpm " + bpm + " spacing " + spacing + ": between presses " + index
                                + " and " + (index + 1) + " the body shows lane " + lane + ", not " + name(shown));
                        driver.frame();
                    }
                    driver.release(lane);
                }
            }
        }
    }

    /** Every press, from idle or from another lane, lands past its pose and settles into it frame by frame. */
    private static void everyPressLandsWithAPunch() {
        for (int bpm : new int[]{96, 120, 173}) {
            for (int from = IDLE; from < 4; from++) for (int lane = 0; lane < 4; lane++) {
                if (from == lane) continue;
                String context = "bpm " + bpm + " " + name(from) + " -> lane " + lane;
                Driver driver = new Driver(bpm, 0);
                driver.until(2);
                if (from != IDLE) {
                    driver.press(from, false);
                    driver.until(driver.now + 4);
                }
                driver.press(lane, false);
                float pressed = driver.now;
                float landed = driver.laneWeight(lane);
                check(Math.abs(landed - (1 + PUNCH)) < 1.0e-3F, context + " lands " + PUNCH
                        + " past its pose on the press frame, at " + landed);
                float previous = landed;
                driver.frame();
                while (driver.now < pressed + PUNCH_TICKS) {
                    float weight = driver.laneWeight(lane);
                    check(weight < previous && weight > 1.0F - 1.0e-4F, context + " settles toward its pose every frame, "
                            + previous + " -> " + weight + " at +" + ticks(driver.now - pressed));
                    previous = weight;
                    driver.frame();
                }
                check(Math.abs(driver.laneWeight(lane) - 1) < 1.0e-3F, context + " stands in its plain pose once "
                        + PUNCH_TICKS * 50 + " ms have passed, at " + driver.laneWeight(lane));
            }
        }
    }

    private static void punchSettlesFrontLoaded() {
        for (int lane = 0; lane < 4; lane++) {
            Driver driver = new Driver(120, 0);
            driver.until(5);
            driver.press(lane, false);
            float pressed = driver.now;
            driver.at(pressed + PUNCH_TICKS / 2);
            float left = (driver.laneWeight(lane) - 1) / PUNCH;
            check(left <= 0.3F, "lane " + lane + " punch has done most of its settling halfway through, " + left
                    + " of it is left");
        }
    }

    /** What the punch is for: a note repeated on the lane already posed used to change nothing on screen. */
    private static void repeatedPressOnTheSameLaneShows() {
        for (int bpm : new int[]{120, 173}) {
            for (int lane = 0; lane < 4; lane++) {
                for (boolean ownKey : new boolean[]{true, false}) {
                    String context = "bpm " + bpm + " lane " + lane + (ownKey ? " own key" : " judged hit");
                    float spacing = 600.0F / bpm; // eighth notes
                    Driver driver = new Driver(bpm, 0);
                    driver.until(2);
                    for (int press = 0; press < 4; press++) {
                        if (press > 0) {
                            check(Math.abs(driver.laneWeight(lane) - 1) < 1.0e-3F, context
                                    + ": the body has settled into the pose before press " + press);
                        }
                        driver.press(lane, ownKey);
                        float pressed = driver.now;
                        check(driver.laneWeight(lane) > 1 + PUNCH * 0.9F, context + ": press " + press
                                + " of the same lane shows on the body, at " + driver.laneWeight(lane));
                        driver.until(pressed + 0.75F);
                        driver.release(lane);
                        driver.until(pressed + spacing - FRAME / 2);
                    }
                }
            }
        }
    }

    private static void returnToIdleIsShortAndFrontLoaded() {
        for (int lane = 0; lane < 4; lane++) {
            Driver driver = new Driver(120, 0);
            driver.until(4);
            driver.press(lane, false);
            float windowEnd = driver.now + ClientBattleAnimation.pressPoseTicks(120);
            driver.until(windowEnd - 1);
            check(Math.abs(driver.laneWeight(lane) - 1) < 1.0e-3F, "lane " + lane
                    + " stands in its plain pose just before its window ends");
            // The first frame past the window starts the return from the pose exactly as it stands.
            float started = windowEnd + 0.01F;
            driver.at(started);
            float previous = driver.laneWeight(lane);
            check(previous > 0.99F && previous < 1.001F, "lane " + lane
                    + " return starts from the plain pose, not with a jump, " + previous);
            for (float since = FRAME; since < ClientBattleAnimation.RETURN_TO_IDLE_TICKS; since += FRAME) {
                driver.at(started + since);
                float weight = driver.laneWeight(lane);
                check(weight < previous, "lane " + lane + " return to idle keeps moving toward idle, "
                        + previous + " -> " + weight + " at +" + ticks(since));
                previous = weight;
            }
            driver.at(started + ClientBattleAnimation.RETURN_TO_IDLE_TICKS / 2);
            float half = driver.laneWeight(lane);
            check(half <= 0.3F, "lane " + lane + " return to idle has done most of its move halfway through, "
                    + half + " of the pose is left");
            driver.at(started + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + 0.01F);
            check(driver.shown() == IDLE, "lane " + lane + " return to idle is over within "
                    + ClientBattleAnimation.RETURN_TO_IDLE_TICKS * 50 + " ms, showing " + name(driver.shown()));
        }
    }

    private static void heldKeyKeepsItsPoseUntilRelease() {
        for (int lane = 0; lane < 4; lane++) {
            Driver driver = new Driver(120, 0);
            driver.until(2);
            driver.press(lane, true);
            while (driver.now < 40) {
                check(driver.shown() == lane, "lane " + lane + " held key keeps its pose at " + ticks(driver.now));
                driver.frame();
            }
            driver.release(lane);
            driver.render();
            float released = driver.now;
            driver.frame();
            check(driver.shown() == BLEND, "lane " + lane + " lets go as soon as a long hold is released, no extra linger");
            driver.at(released + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + 0.01F);
            check(driver.shown() == IDLE, "lane " + lane + " is idle after the return that follows a long hold");
        }
    }

    private static void tapDuringAHoldReturnsToTheHeldDirection() {
        for (int held = 0; held < 4; held++) for (int tapped = 0; tapped < 4; tapped++) {
            if (held == tapped) continue;
            String context = "hold " + held + ", tap " + tapped;
            Driver driver = new Driver(120, 0);
            driver.until(1);
            driver.press(held, true);
            driver.until(10);
            driver.press(tapped, true);
            float pressed = driver.now;
            float window = ClientBattleAnimation.pressPoseTicks(120);
            while (driver.now < pressed + window - FRAME) {
                if (driver.now >= pressed + 1.5F) driver.release(tapped);
                check(driver.shown() == tapped, context + ": the tap keeps its pose during its window, at +"
                        + ticks(driver.now - pressed));
                driver.frame();
            }
            driver.at(pressed + window + 0.01F);
            check(driver.shown() == held, context + ": the held direction is back on the first frame after the window, "
                    + "with no idle in between, not " + name(driver.shown()));
            // Coming back to a held direction is not a press, so it does not punch.
            check(Math.abs(driver.laneWeight(held) - 1) < 1.0e-3F, context
                    + ": the held direction comes back in its plain pose, at " + driver.laneWeight(held));
        }
    }

    private static void repeatedPressExtendsThePose() {
        Driver driver = new Driver(120, 0);
        driver.until(5);
        float window = ClientBattleAnimation.pressPoseTicks(120);
        driver.press(2, false);
        driver.until(driver.now + window * 0.75F);
        driver.press(2, false);
        float second = driver.now;
        while (driver.now < second + window - FRAME) {
            check(driver.shown() == 2, "a second press of the same lane keeps the pose up to its own window, at +"
                    + ticks(driver.now - second));
            driver.frame();
        }
    }

    /** What an opponent's quick tap looks like here: key down, key up a tick later, then the server's hit. */
    private static void opponentDownUpAndHitStayOnePose() {
        for (int lane = 0; lane < 4; lane++) {
            Driver driver = new Driver(120, 0);
            driver.until(6);
            driver.press(lane, true);
            float down = driver.now;
            boolean hitArrived = false;
            while (driver.now < down + 2 + ClientBattleAnimation.pressPoseTicks(120) - FRAME) {
                if (driver.now >= down + 1) driver.release(lane);
                if (!hitArrived && driver.now >= down + 2 - FRAME / 2) {
                    driver.judge(lane);
                    hitArrived = true;
                }
                check(driver.shown() == lane, "opponent lane " + lane + " shows one pose from key down to the "
                        + "end of the hit's window, at +" + ticks(driver.now - down) + ": " + name(driver.shown()));
                driver.frame();
            }
        }
    }

    /**
     * An early press reaches the layer twice: as its key goes down, and again as its note reaches the line, where the
     * controller holds an early judgement back, up to 7 ticks later. The body punches once, at the key-down, and the
     * judgement renews the pose's window, so that the hit still holds its pose for a beat after it lands.
     */
    private static void earlyPressPunchesOnce() {
        for (int bpm : new int[]{120, 173}) {
            float window = ClientBattleAnimation.pressPoseTicks(bpm);
            for (int lane = 0; lane < 4; lane++) {
                for (boolean keptDown : new boolean[]{false, true}) {
                    for (float early : new float[]{2.0F, 3.5F, 5.0F, 7.0F}) {
                        String context = "bpm " + bpm + " lane " + lane + (keptDown ? " held" : " tapped") + " key "
                                + ticks(early) + " early";
                        Driver driver = new Driver(bpm, 0);
                        driver.until(3);
                        driver.press(lane, true);
                        float down = driver.now;
                        driver.until(down + 1.5F);
                        if (!keptDown) driver.release(lane);
                        driver.at(down + early);
                        driver.judge(lane);
                        float judged = driver.now;
                        float weight = driver.laneWeight(lane);
                        check(weight <= 1 + punchLeft(early) + 1.0e-3F, context + ": the judgement does not punch the"
                                + " pose again, " + weight + " where the key-down's punch leaves " + (1 + punchLeft(early)));
                        while (driver.now < judged + window - FRAME) {
                            check(driver.shown() == lane, context + ": the hit holds its pose for a window after it lands, at +"
                                    + ticks(driver.now - judged) + ": " + name(driver.shown()));
                            driver.frame();
                        }
                    }
                }
            }
        }
    }

    /**
     * Another key going down before an earlier key's judgement arrives, a chord included: the judgement is not a new
     * press, so it neither takes the body back to the earlier lane nor punches.
     */
    private static void anEarlierKeysJudgementLeavesTheNewerPose() {
        float window = ClientBattleAnimation.pressPoseTicks(120);
        for (int first = 0; first < 4; first++) for (int second = 0; second < 4; second++) {
            if (first == second) continue;
            for (float gap : new float[]{1.0F, 2.0F}) {
                String context = "key " + first + ", key " + second + " " + ticks(gap) + " later";
                Driver driver = new Driver(120, 0);
                driver.until(3);
                driver.press(first, true);
                float down = driver.now;
                driver.until(down + 0.75F);
                driver.release(first);
                driver.at(down + gap);
                driver.press(second, true);
                float secondDown = driver.now;
                driver.until(secondDown + 0.75F);
                driver.release(second);
                // The first key was 4 ticks early, so its judgement arrives after the second key went down.
                driver.at(down + 4);
                float before = driver.laneWeight(second);
                driver.judge(first);
                check(driver.shown() == second, context + ": the first key's judgement leaves the body on the second"
                        + " key, not " + name(driver.shown()));
                check(driver.laneWeight(second) <= before + 1.0e-4F, context + ": the first key's judgement does not"
                        + " punch, " + before + " -> " + driver.laneWeight(second));
                while (driver.now < secondDown + window - FRAME) {
                    check(driver.shown() == second, context + ": the second key keeps its pose for its window, at +"
                            + ticks(driver.now - secondDown) + ": " + name(driver.shown()));
                    driver.frame();
                }
            }
            // A chord: both keys go down in one frame, the controller passes the second on, and both judgements
            // arrive together in a later frame with the first one last.
            String context = "chord " + first + "+" + second;
            Driver driver = new Driver(120, 0);
            driver.until(3);
            driver.keyDown(first);
            driver.keyDown(second);
            driver.render();
            float down = driver.now;
            check(driver.shown() == second, context + " shows the key the controller passed on, not " + name(driver.shown()));
            driver.until(down + 0.75F);
            driver.release(first);
            driver.release(second);
            driver.at(down + 3);
            float before = driver.laneWeight(second);
            driver.judgement(second);
            driver.judgement(first);
            driver.render();
            check(driver.shown() == second, context + ": the chord's judgements leave the body on the key shown, not "
                    + name(driver.shown()));
            check(driver.laneWeight(second) <= before + 1.0e-4F, context + ": the chord's judgements do not punch, "
                    + before + " -> " + driver.laneWeight(second));
        }
    }

    /**
     * An opponent's key-down comes with the network, before the client tick that lets judgements through, so in one
     * frame a judgement can be the last trigger the controller passes on. The key that went down is still the press.
     */
    private static void aKeyHiddenBehindAJudgementStillShows() {
        for (int earlier = 0; earlier < 4; earlier++) for (int next = 0; next < 4; next++) {
            if (earlier == next) continue;
            String context = "key " + next + " going down in the frame of key " + earlier + "'s judgement";
            Driver driver = new Driver(120, 0);
            driver.until(3);
            driver.press(earlier, true);
            float down = driver.now;
            driver.until(down + 0.75F);
            driver.release(earlier);
            driver.at(down + 3);
            driver.keyDown(next);
            driver.judgement(earlier);
            driver.render();
            check(driver.shown() == next, context + " shows key " + next + ", not " + name(driver.shown()));
            check(driver.laneWeight(next) > 1 + PUNCH * 0.9F, context + " lands with its punch, at "
                    + driver.laneWeight(next));
        }
    }

    /** A judgement does not swallow the next real press on the same lane: that key goes down again, so it punches. */
    private static void aRealPressAfterAJudgementStillPunches() {
        for (int lane = 0; lane < 4; lane++) {
            Driver driver = new Driver(120, 0);
            driver.until(3);
            driver.press(lane, true);
            float down = driver.now;
            driver.until(down + 0.75F);
            driver.release(lane);
            driver.at(down + 3);
            driver.judge(lane);
            driver.at(down + 4);
            driver.press(lane, true);
            check(driver.laneWeight(lane) > 1 + PUNCH * 0.9F, "lane " + lane + ": a second key-down 1 tick after the"
                    + " first one's judgement punches, at " + driver.laneWeight(lane));
        }
    }

    /**
     * A mob holds no keys, but its hold note holds its lane, as a key does. Its next note on that lane is a press of
     * its own however soon it comes. The old rule took any trigger within 8 ticks of its lane going held for that
     * hold's judgement, so a mob's note 1-8 ticks into a short hold, or after it, was swallowed: no punch, and with
     * another lane posed in between, no pose at all. A 2x battle halves every gap of the chart.
     */
    private static void aMobsNoteAfterItsHoldStillLands() {
        for (int lane = 0; lane < 4; lane++) {
            for (boolean otherLaneBetween : new boolean[]{false, true}) {
                for (float hold : new float[]{2.0F, 3.0F, 6.0F}) {
                    for (float after : new float[]{1.0F, 2.0F, 3.0F}) {
                        String context = "mob lane " + lane + (otherLaneBetween ? " after another lane" : "") + ", "
                                + ticks(hold + after) + " into a " + ticks(hold) + " hold";
                        Driver driver = new Driver(96, 0);
                        driver.until(3);
                        driver.press(lane, false);
                        driver.noteHeld(lane);
                        driver.render();
                        float held = driver.now;
                        driver.at(held + hold);
                        driver.release(lane);
                        driver.render();
                        if (otherLaneBetween) {
                            driver.at(held + hold + after / 2);
                            driver.press((lane + 1) % 4, false);
                        }
                        driver.at(held + hold + after);
                        driver.press(lane, false);
                        check(driver.shown() == lane, context + " shows its lane, not " + name(driver.shown()));
                        check(driver.punchedNow(), context + " lands with its punch, at " + driver.laneWeight(lane));
                    }
                }
            }
        }
    }

    /**
     * The editor's gameplay preview passes no keys and no judgements: every trigger there is the player's key going
     * down, and only a hit hold note holds its lane. The old rule read that held lane as a key and swallowed the
     * player's next tap on it within 8 ticks.
     */
    private static void editorPreviewTapAfterAHoldStillLands() {
        for (int lane = 0; lane < 4; lane++) {
            for (float gap : new float[]{5.0F, 6.0F, 8.0F}) {
                var holder = ClientBattleAnimation.createLayer();
                float start = 4.0F;
                ClientBattleAnimation.update(holder, 0, -1, 0, 120, start);
                // A hold note hit: the press and its held lane in one frame, through the preview's own overload.
                ClientBattleAnimation.update(holder, 1, lane, 1 << lane, 120, start + 1);
                ClientBattleAnimation.update(holder, 1, lane, 0, 120, start + 4);
                float tapped = start + 1 + gap;
                ClientBattleAnimation.update(holder, 2, lane, 0, 120, tapped);
                String context = "preview lane " + lane + " tapped " + ticks(gap) + " after a hold began";
                check(shown(holder, 120, tapped) == lane, context + " shows its lane");
                float weight = weight(holder, reference(IDLE, 120, tapped), reference(lane, 120, tapped), tapped);
                check(weight > 1 + PUNCH * 0.99F, context + " lands with its punch, at " + weight);
            }
        }
    }

    /**
     * A judgement held back past every fixed window, as a negative input offset does: at -500 ms an early hit's
     * judgement comes up to 16 ticks after its key. It neither punches nor takes the body anywhere; a second key
     * pressed meanwhile keeps the body.
     */
    private static void aLateJudgementNeverPressesAgain() {
        for (int bpm : new int[]{96, 120, 173}) {
            for (int first = 0; first < 4; first++) {
                for (int second = -1; second < 4; second++) {
                    if (second == first) continue;
                    for (float delay : new float[]{8.5F, 10.0F, 12.0F, 16.0F}) {
                        String context = "bpm " + bpm + " key " + first + (second >= 0 ? " then key " + second : "")
                                + ", judged " + ticks(delay) + " after its key";
                        Driver driver = new Driver(bpm, 0);
                        driver.until(3);
                        driver.press(first, true);
                        float down = driver.now;
                        driver.until(down + 1);
                        driver.release(first);
                        if (second >= 0) {
                            driver.at(down + 3);
                            driver.press(second, true);
                            driver.until(down + 4);
                            driver.release(second);
                        }
                        driver.at(down + delay);
                        int before = driver.shown();
                        driver.judge(first);
                        check(!driver.punchedNow(), context + ": the judgement does not punch");
                        check(driver.shown() == before, context + ": the judgement leaves " + name(before)
                                + " on screen, not " + name(driver.shown()));
                        if (second >= 0) {
                            driver.frame();
                            check(driver.shown() != first, context + ": the body does not jump back to key " + first);
                        }
                    }
                }
            }
        }
    }

    /**
     * The server judges another player's hit before it passes on the key, because that player's client sends the
     * judgement first, so the judgement can arrive frames before the key-down. The body still punches once, at the
     * key-down.
     */
    private static void anOpponentsJudgementBeforeItsKeyPunchesOnce() {
        for (int from = IDLE; from < 4; from++) {
            for (int lane = 0; lane < 4; lane++) {
                for (float lead : new float[]{FRAME, 1.0F, 2.0F}) {
                    String context = name(from) + " -> opponent lane " + lane + ", judged " + ticks(lead) + " before its key";
                    Driver driver = new Driver(120, 0);
                    driver.until(3);
                    if (from != IDLE) {
                        driver.press(from, true);
                        driver.until(driver.now + 1);
                        driver.release(from);
                    }
                    driver.until(driver.now + 2);
                    int before = driver.shown();
                    driver.judge(lane);
                    check(!driver.punchedNow(), context + ": the judgement alone does not punch");
                    check(driver.shown() == before, context + ": the judgement alone leaves " + name(before)
                            + ", not " + name(driver.shown()));
                    driver.at(driver.now + lead);
                    driver.press(lane, true);
                    check(driver.shown() == lane, context + ": the key shows its lane, not " + name(driver.shown()));
                    check(driver.punchedNow(), context + ": the key punches, at " + driver.laneWeight(lane));
                }
            }
        }
    }

    /** How much of a press's punch is left this long after it landed. */
    private static float punchLeft(float since) {
        float remaining = Math.max(0.0F, 1.0F - since / PUNCH_TICKS);
        return PUNCH * remaining * remaining;
    }

    private static void lateLayerShowsOnlyTheRestOfTheWindow() {
        float window = ClientBattleAnimation.pressPoseTicks(120);
        float pressed = 30.0F;
        var late = ClientBattleAnimation.createLayer();
        float attached = pressed + window * 0.5F;
        ClientBattleAnimation.update(late, 9, 1, 0, 120, attached, pressed);
        check(shown(late, 120, attached) == 1, "a layer attached mid-window still shows the fresh press");
        float before = pressed + window - FRAME;
        ClientBattleAnimation.update(late, 9, 1, 0, 120, before, pressed);
        check(shown(late, 120, before) == 1, "the late layer keeps the pose to the press's own window");
        float after = pressed + window + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + FRAME;
        ClientBattleAnimation.update(late, 9, 1, 0, 120, pressed + window + FRAME, pressed);
        ClientBattleAnimation.update(late, 9, 1, 0, 120, after, pressed);
        check(shown(late, 120, after) == IDLE, "the late layer does not start a new window from the moment it attached");
    }

    /** A press first drawn late (an entity that was out of view) is as far through its punch as it would have been. */
    private static void lateLayerShowsOnlyTheRestOfThePunch() {
        float pressed = 30.0F;
        for (float late : new float[]{FRAME, 1.0F, 2.0F, PUNCH_TICKS + 0.5F}) {
            float now = pressed + late;
            var onTime = ClientBattleAnimation.createLayer();
            ClientBattleAnimation.update(onTime, 3, 0, 0, 120, 20.0F);
            ClientBattleAnimation.update(onTime, 4, 1, 0, 120, pressed, pressed);
            ClientBattleAnimation.update(onTime, 4, 1, 0, 120, now, pressed);
            var seenLate = ClientBattleAnimation.createLayer();
            ClientBattleAnimation.update(seenLate, 3, 0, 0, 120, 20.0F);
            ClientBattleAnimation.update(seenLate, 4, 1, 0, 120, now, pressed);
            check(distance(seenLate, onTime, now) < 1.0e-4, "a press first drawn " + ticks(late)
                    + " late shows what a layer that saw it land shows");
        }
    }

    /** An entity out of view while it was pressing: when it is drawn again, that press is over. */
    private static void pressSeenAfterItsWindowIsNotReplayed() {
        var holder = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(holder, 4, 0, 0, 120, 10);
        float window = ClientBattleAnimation.pressPoseTicks(120);
        float pressed = 12.0F;
        float seen = pressed + window + 1;
        ClientBattleAnimation.update(holder, 5, 3, 0, 120, seen, pressed);
        check(shown(holder, 120, seen) == IDLE, "a press first drawn after its window shows nothing, not a fresh pose");
    }

    private static void clockJumpingBackEndsThePose() {
        Driver driver = new Driver(120, 0);
        driver.until(50);
        driver.press(1, false);
        driver.frame();
        check(driver.shown() == 1, "the press shows before the seek");
        driver.now = 20;
        driver.render();
        // Counted from the old press time the pose would stand for another 40 ticks; it lets go at once instead.
        driver.at(20 + ClientBattleAnimation.RETURN_TO_IDLE_TICKS + 0.01F);
        check(driver.shown() == IDLE, "after a seek back the body settles at idle, showing " + name(driver.shown()));
    }

    /**
     * One participant as the controller feeds it: presses (a serial, the latest one's lane and tick), the judgements
     * of key presses the same way, and every held lane.
     */
    private static final class Driver {
        private final ClientBattleAnimation.Holder holder = ClientBattleAnimation.createLayer();
        private final int bpm;
        private int serial;
        private int lane = -1;
        private float triggerTick = -Float.MAX_VALUE;
        private int judgedSerial;
        private int judgedLane = -1;
        private float judgedTick = -Float.MAX_VALUE;
        private int heldMask;
        private int ticked;
        private float now;

        private Driver(int bpm, float start) {
            this.bpm = bpm;
            this.now = start;
            this.ticked = (int) Math.floor(start);
            render();
        }

        /** A key going down, or with {@code keyHeld} false a mob's judged hit, which is its press; drawn at once. */
        private void press(int lane, boolean keyHeld) {
            if (keyHeld) {
                keyDown(lane);
            } else {
                mobHit(lane);
            }
            render();
        }

        /** The judgement of a key press already shown, drawn at once. */
        private void judge(int lane) {
            judgement(lane);
            render();
        }

        /** The controller's press for a key going down, which also holds the lane; drawn by the next render. */
        private void keyDown(int lane) {
            serial++;
            this.lane = lane;
            triggerTick = now;
            heldMask |= 1 << lane;
        }

        /** The controller's judgement of a key press, apart from the presses; drawn by the next render. */
        private void judgement(int lane) {
            judgedSerial++;
            judgedLane = lane;
            judgedTick = now;
        }

        /** The controller's press for a mob's hit, which has no key; drawn by the next render. */
        private void mobHit(int lane) {
            serial++;
            this.lane = lane;
            triggerTick = now;
        }

        /** A hold note being sung holds its lane the way a key does, without being a key. */
        private void noteHeld(int lane) {
            heldMask |= 1 << lane;
        }

        private void release(int lane) {
            heldMask &= ~(1 << lane);
        }

        private void frame() {
            at(now + FRAME);
        }

        /** Draws a frame at exactly this battle tick, for moments that must not depend on the 60 fps grid. */
        private void at(float tick) {
            now = tick;
            render();
        }

        private void until(float tick) {
            while (now + FRAME / 2 < tick) frame();
        }

        private void render() {
            // Player Animator ticks every layer once per client tick; nothing in the battle layer may depend on it.
            while (ticked < (int) Math.floor(now)) {
                holder.layer.tick();
                ticked++;
            }
            ClientBattleAnimation.update(holder, serial, lane, triggerTick, judgedSerial, judgedLane, judgedTick,
                    heldMask, bpm, now);
        }

        /**
         * Whether a press landed on this very frame: the pose on screen stands as far past itself as a fresh press's
         * punch goes. A frame later the punch has already settled a fifth of the way back.
         */
        private boolean punchedNow() {
            int shown = shown();
            return shown >= 0 && laneWeight(shown) > 1 + PUNCH * 0.99F;
        }

        private int shown() {
            return BattleAnimationTimingChecks.shown(holder, bpm, now);
        }

        private float laneWeight(int lane) {
            return weight(holder, reference(IDLE, bpm, now), reference(lane, bpm, now), now);
        }
    }

    /**
     * Which pose is on screen: idle, a lane (its plain pose, or that pose pushed out from idle by a press's punch,
     * never further than the punch goes), or anything else, which is a blend.
     */
    private static int shown(ClientBattleAnimation.Holder holder, int bpm, float tick) {
        var idle = reference(IDLE, bpm, tick);
        if (distance(holder, idle, tick) < 1.0e-4) return IDLE;
        for (int lane = 0; lane < 4; lane++) {
            var pose = reference(lane, bpm, tick);
            float weight = weight(holder, idle, pose, tick);
            if (weight >= 1 - 1.0e-4F && weight <= 1 + PUNCH + 1.0e-4F && offLine(holder, idle, pose, weight, tick) < 1.0e-3) {
                return lane;
            }
        }
        return BLEND;
    }

    /**
     * A fresh layer showing only idle, or only one lane's plain pose. A held key seen by a new layer shows its lane at
     * once and without a punch, here and in the code before the fix alike, so the same references judge both. The
     * hold clips are the lane clips with the loop flag set: convert_blockbench_player_animations.mjs writes each pair
     * from one Blockbench animation.
     */
    private static ClientBattleAnimation.Holder reference(int lane, int bpm, float tick) {
        var holder = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(holder, 0, Math.max(lane, 0), lane >= 0 ? 1 << lane : 0, bpm, tick);
        return holder;
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

    /** How far from idle toward the lane pose the actual pose is: 1 is the pose, 0 is idle, above 1 is a punch. */
    private static float weight(ClientBattleAnimation.Holder actual, ClientBattleAnimation.Holder idle,
                                ClientBattleAnimation.Holder lane, float tick) {
        double dot = 0;
        double norm = 0;
        for (String part : PARTS) for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
            Vec3f p = transform(actual, part, type, tick);
            Vec3f i = transform(idle, part, type, tick);
            Vec3f a = transform(lane, part, type, tick);
            float[] towardActual = {p.getX() - i.getX(), p.getY() - i.getY(), p.getZ() - i.getZ()};
            float[] towardLane = {a.getX() - i.getX(), a.getY() - i.getY(), a.getZ() - i.getZ()};
            for (int axis = 0; axis < 3; axis++) {
                dot += towardActual[axis] * towardLane[axis];
                norm += towardLane[axis] * towardLane[axis];
            }
        }
        return norm < 1.0e-9 ? 0 : (float) (dot / norm);
    }

    /** How far the actual pose is from the point at this weight on the line from idle through the lane pose. */
    private static double offLine(ClientBattleAnimation.Holder actual, ClientBattleAnimation.Holder idle,
                                  ClientBattleAnimation.Holder lane, float weight, float tick) {
        double sum = 0;
        for (String part : PARTS) for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
            Vec3f p = transform(actual, part, type, tick);
            Vec3f i = transform(idle, part, type, tick);
            Vec3f a = transform(lane, part, type, tick);
            sum += Math.abs(p.getX() - (i.getX() + weight * (a.getX() - i.getX())))
                    + Math.abs(p.getY() - (i.getY() + weight * (a.getY() - i.getY())))
                    + Math.abs(p.getZ() - (i.getZ() + weight * (a.getZ() - i.getZ())));
        }
        return sum;
    }

    private static Vec3f transform(ClientBattleAnimation.Holder holder, String part, TransformType type, float tick) {
        float partial = tick - (float) Math.floor(tick);
        holder.layer.setupAnim(partial);
        return holder.layer.get3DTransform(part, type, partial, new Vec3f(0, 0, 0));
    }

    private static String name(int shown) {
        return shown == IDLE ? "idle" : shown == BLEND ? "a blend" : "lane " + shown;
    }

    private static String ticks(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f ticks", value);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }
}
