import ru.gora.musicbattle.client.BattleCameraInterpolation;
import ru.gora.musicbattle.data.BattleCameraFocus;
import ru.gora.musicbattle.data.BattleCameraShot;

/**
 * The battle camera as the server moves it and a client draws it, run without a game.
 *
 * <p>Each part reproduces what testers saw first and then checks the shot that replaces it:
 * <ul>
 *   <li>Height: the automatic shot's lens stood almost three blocks up and aimed as if it were at the helper stand's
 *       feet, so the fighters sat in the bottom of the frame. It now stands at eye level and the fighters are framed
 *       around the middle.</li>
 *   <li>Turns: the client showed each tick's direction as it came, while the position slid along three ticks behind,
 *       so a turn advanced in twenty steps a second. Rendered frames now blend between ticks with
 *       {@link BattleCameraInterpolation}, the class the client uses, at 60 and 144 frames a second.</li>
 *   <li>The lean itself starts and stops more gently, and a cramped swing narrows over several ticks.</li>
 *   <li>Follow mode: a camera block keeps its own frame at rest, stays on its spot and only turns as the automatic shot
 *       would from there, without spinning when it hangs above the arena. It used to swing around the arena too.</li>
 *   <li>Cuts: a change of camera is shown at once and every other frame blended, and a skipped rest no longer whips
 *       the shot round to where its swing would have been after the rest.</li>
 * </ul>
 *
 * <p>A hidden single-player client measured the same in the game (a test NPC battle with a skipped rest, every rendered
 * frame logged): the automatic lens stood 2.77 blocks above the player's feet and looked level, and now stands 1.81
 * above them and looks 13 degrees down; the frame-to-frame change of the turning speed fell from a median of 1.9 to
 * 0.2 degrees a second.
 */
public final class BattleCameraChecks {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        automaticShotIsAtEyeLevel();
        restFrameIsTheIntroFrame();
        renderedTurnsAreSmooth();
        leanStartsAndStopsGently();
        crampedSwingNarrowsGradually();
        leanKeepsBothFightersInACrampedRoom();
        followCameraKeepsItsFrame();
        followCameraKeepsItsSpotAndOnlyTurns();
        followCameraAboveTheArenaDoesNotSpin();
        cutsAreShownAndMovesAreBlended();
        aSkipDoesNotWhipTheShotRound();
        if (failures > 0) {
            System.err.println(failures + " of " + checks + " battle camera check(s) failed.");
            System.exit(1);
        }
        System.out.println("Battle camera checks passed: " + checks);
        System.out.println("NOT COVERED: how the shot looks in the game, and the network between the server and a client");
    }

    // Two players 3.5 blocks apart along x, feet at y = 64; the bodies' middle is at 0.62 of 1.8 blocks.
    private static final double FLOOR = 64.0D;
    private static final double CENTER_Y = FLOOR + 1.8D * 0.62D - 1.22D;
    private static final double SIDE_X = 1.75D;

    private static void automaticShotIsAtEyeLevel() {
        // Old: the helper stand's feet 1.0 above the floor and 3.6 out, aimed from the feet at the old look point,
        // with the picture taken an eye height (1.7775) higher.
        double oldFeetY = FLOOR + 1.00D;
        double oldLensY = oldFeetY + 1.7775D;
        double oldLookY = FLOOR + 1.8D * 0.62D;
        float oldPitch = BattleCameraShot.pitchToFace(0, oldFeetY, 3.6D, 0, oldLookY, 0);
        double bodyMiddleY = FLOOR + 0.9D;
        float oldBodyAngle = BattleCameraShot.pitchToFace(0, oldLensY, 3.6D, 0, bodyMiddleY, 0);
        check(oldLensY - FLOOR > 2.7D, "reproduced: the old automatic lens stood " + (oldLensY - FLOOR) + " above the floor");
        check(oldBodyAngle - oldPitch > 12.0F,
                "reproduced: the fighters' middle sat " + (oldBodyAngle - oldPitch) + " degrees below the old frame's centre");

        double lensY = FLOOR + BattleCameraShot.LENS_HEIGHTS[0];
        BattleCameraShot.Lens rest = BattleCameraShot.automatic(0, lensY, 3.6D, 0, CENTER_Y, 0, SIDE_X, 0, 0, 0, 0, 1);
        float bodyAngle = BattleCameraShot.pitchToFace(rest.x(), rest.y(), rest.z(), 0, bodyMiddleY, 0);
        check(lensY - FLOOR <= 2.0D, "the automatic lens is at about eye level: " + (lensY - FLOOR));
        check(rest.pitch() > 5.0F && rest.pitch() < 25.0F, "and looks gently down at the fighters: " + rest.pitch());
        check(Math.abs(bodyAngle - rest.pitch()) < 4.0F,
                "the fighters' middle is near the middle of the frame: " + (bodyAngle - rest.pitch()) + " degrees off");
        for (double height : BattleCameraShot.LENS_HEIGHTS) {
            check(height < oldLensY - FLOOR, "every preferred lens height is below the old one: " + height);
        }
    }

    private static void restFrameIsTheIntroFrame() {
        // The intro ends on the lens found at set-up, aimed at the look point; the song must begin on that frame.
        double lensY = FLOOR + BattleCameraShot.LENS_HEIGHTS[0];
        float setupYaw = BattleCameraShot.yawToFace(0, 3.6D, 0, 0);
        float setupPitch = BattleCameraShot.pitchToFace(0, lensY, 3.6D, 0, CENTER_Y + BattleCameraShot.LOOK_HEIGHT, 0);
        BattleCameraShot.Lens first = BattleCameraShot.automatic(0, lensY, 3.6D, 0, CENTER_Y, 0, SIDE_X, 0,
                0, BattleCameraShot.swing(0, 70, Math.PI, 0.2D), BattleCameraShot.rise(0, 70, 0.1D), 1);
        check(Math.abs(first.x()) < 1.0E-9D && Math.abs(first.y() - lensY) < 1.0E-9D && Math.abs(first.z() - 3.6D) < 1.0E-9D,
                "the first frame of the song is where the intro ended");
        check(Math.abs(BattleCameraShot.wrapDegrees(first.yaw() - setupYaw)) < 1.0E-3F
                        && Math.abs(first.pitch() - setupPitch) < 1.0E-3F,
                "and looks where it looked");
    }

    /**
     * The direction drawn on each frame for one automatic shot swinging and leaning over five seconds: the old way
     * (each tick's direction as it came) and the new (blended between ticks). A smooth turn changes by about the same
     * amount every frame; the old one stood still for most frames and then jumped.
     */
    private static void renderedTurnsAreSmooth() {
        for (int fps : new int[]{60, 144}) {
            BattleCameraFocus focus = new BattleCameraFocus();
            BattleCameraInterpolation camera = new BattleCameraInterpolation();
            float[] serverYaw = new float[101];
            double lensY = FLOOR + BattleCameraShot.LENS_HEIGHTS[0];
            for (int tick = 0; tick <= 100; tick++) {
                focus.tick(true, false, tick >= 10);
                BattleCameraShot.Lens shot = BattleCameraShot.automatic(0, lensY, 3.6D, 0, CENTER_Y, 0, SIDE_X, 0,
                        focus.focus(), BattleCameraShot.swing(tick, 70, 0, 0.2D), BattleCameraShot.rise(tick, 70, 0.1D), 1);
                serverYaw[tick] = shot.yaw();
            }
            double framesPerTick = fps / 20.0D;
            int frames = (int) (100 * framesPerTick);
            float previousOld = Float.NaN;
            float previousNew = Float.NaN;
            float previousOldStep = 0.0F;
            float previousNewStep = 0.0F;
            float worstOldChange = 0.0F;
            float worstNewChange = 0.0F;
            int stillOld = 0;
            int lastTick = -1;
            for (int frame = 0; frame < frames; frame++) {
                double time = frame / framesPerTick;
                int tick = (int) Math.floor(time);
                float partial = (float) (time - tick);
                while (lastTick < tick) {
                    lastTick++;
                    if (lastTick == 0) {
                        camera.snap(0, 0, 0, serverYaw[0], 0, 0);
                    }
                    camera.captureTickStart();
                    camera.approach(0, 0, 0, serverYaw[lastTick], 0, 0, 0.5D, 0.5F, 1.0F);
                }
                float oldYaw = serverYaw[tick];
                float newYaw = camera.sample(partial).yaw();
                if (!Float.isNaN(previousOld) && tick > 15) {
                    float oldStep = BattleCameraShot.wrapDegrees(oldYaw - previousOld);
                    float newStep = BattleCameraShot.wrapDegrees(newYaw - previousNew);
                    worstOldChange = Math.max(worstOldChange, Math.abs(oldStep - previousOldStep));
                    worstNewChange = Math.max(worstNewChange, Math.abs(newStep - previousNewStep));
                    if (Math.abs(oldStep) < 1.0E-6F) {
                        stillOld++;
                    }
                    previousOldStep = oldStep;
                    previousNewStep = newStep;
                }
                previousOld = oldYaw;
                previousNew = newYaw;
            }
            check(stillOld > frames / 2, "reproduced at " + fps + " fps: the old turn stood still on " + stillOld + " of " + frames + " frames");
            check(worstNewChange * 4.0F < worstOldChange,
                    "at " + fps + " fps a blended turn changes speed far less from frame to frame: "
                            + worstNewChange + " against " + worstOldChange + " degrees");
        }
    }

    private static void leanStartsAndStopsGently() {
        // The law the lean had before: two seconds for a full swing, full speed within a few ticks.
        float oldWorst = worstLeanJolt(0.05F, 0.1F, 0.2F);
        BattleCameraFocus focus = new BattleCameraFocus();
        float previous = 0.0F;
        float previousStep = 0.0F;
        float worst = 0.0F;
        for (int tick = 0; tick < 200; tick++) {
            focus.tick(true, false, true);
            float step = focus.focus() - previous;
            worst = Math.max(worst, Math.abs(step - previousStep));
            previous = focus.focus();
            previousStep = step;
        }
        check(worst < oldWorst, "the lean changes speed more gently than it did: " + worst + " against " + oldWorst);
        int swing = 0;
        while (focus.focus() > -0.98F && swing < 400) {
            focus.tick(true, true, false);
            swing++;
        }
        check(swing >= 55 && swing <= 90, "a full swing from one fighter to the other takes about three seconds: " + swing + " ticks");
    }

    /** The old lean, re-run here with its own constants, so the comparison does not depend on remembered numbers. */
    private static float worstLeanJolt(float maxSpeed, float ease, float acceleration) {
        float focus = 0.0F;
        float velocity = 0.0F;
        float previousStep = 0.0F;
        float worst = 0.0F;
        for (int tick = 0; tick < 200; tick++) {
            float desired = Math.max(-maxSpeed, Math.min(maxSpeed, (1.0F - focus) * ease));
            velocity += (desired - velocity) * acceleration;
            float next = Math.max(-1.0F, Math.min(1.0F, focus + velocity));
            if (Math.abs(1.0F - next) < 1.0E-3F && Math.abs(velocity) < 1.0E-3F) {
                next = 1.0F;
                velocity = 0.0F;
            }
            float step = next - focus;
            worst = Math.max(worst, Math.abs(step - previousStep));
            previousStep = step;
            focus = next;
        }
        return worst;
    }

    private static void crampedSwingNarrowsGradually() {
        double scale = 1.0D;
        int ticks = 0;
        while (scale > 0.0D && ticks < 100) {
            double next = BattleCameraShot.easeSwingScale(scale, 0.0D);
            check(scale - next <= BattleCameraShot.SWING_DROP_PER_TICK + 1.0E-9D, "a cramped swing narrows a little each tick");
            scale = next;
            ticks++;
        }
        check(ticks >= 8, "from a full swing to none takes " + ticks + " ticks, not one");
        int grow = 0;
        while (scale < 1.0D && grow < 200) {
            scale = BattleCameraShot.easeSwingScale(scale, 1.0D);
            grow++;
        }
        check(grow >= 40, "and it takes the room back slowly: " + grow + " ticks");
        check(BattleCameraShot.easeSwingScale(0.3D, 0.5D) <= 0.5D && BattleCameraShot.easeSwingScale(0.7D, 0.5D) >= 0.5D,
                "it never overshoots the share that has room");
    }

    /**
     * A cramped room's shot stands 2.4 to 3.3 blocks out on the bisector. A full lean used to pan it as far as on an open
     * stage, which at that distance turned the fighter who was not playing out of the picture (a hidden game showed the
     * player cut at the left edge while the test NPC played). Measured here with the automatic shot itself, truck
     * included, against the picture Minecraft's default field of view (70 degrees) gives on a 16:9 screen.
     */
    private static void leanKeepsBothFightersInACrampedRoom() {
        double halfFrame = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(35.0D)) * 16.0D / 9.0D));
        double lensY = FLOOR + BattleCameraShot.LENS_HEIGHTS[0];
        for (double distance : new double[]{2.4D, 2.85D, 3.3D, 3.6D, 4.2D, 4.8D}) {
            for (double lean : new double[]{-1.0D, 1.0D}) {
                BattleCameraShot.Lens shot = BattleCameraShot.automatic(0, lensY, distance, 0, CENTER_Y, 0, SIDE_X, 0,
                        lean, 0, 0, 1);
                // The far side of the body of the fighter who is not playing: half a player's width beyond their middle.
                double idleEdge = -Math.signum(lean) * (SIDE_X + 0.3D);
                double idleMiddle = -Math.signum(lean) * SIDE_X;
                double edgeAngle = Math.abs(BattleCameraShot.wrapDegrees(
                        BattleCameraShot.yawToFace(shot.x(), shot.z(), idleEdge, 0) - shot.yaw()));
                double middleAngle = Math.abs(BattleCameraShot.wrapDegrees(
                        BattleCameraShot.yawToFace(shot.x(), shot.z(), idleMiddle, 0) - shot.yaw()));
                check(edgeAngle < halfFrame - 1.0D, "at " + distance + " out, a full lean keeps the other fighter whole in the "
                        + "picture: their far side " + edgeAngle + " degrees off the axis, the edge " + halfFrame);
                if (distance == 2.4D && lean > 0) {
                    // The same shot with the open stage's pan, as before.
                    double[] lens = BattleCameraShot.lensPosition(0, lensY, distance, 0, 0, SIDE_X, 0, lean, 0, 0, 1);
                    double[] oldLook = BattleCameraShot.lookPoint(0, CENTER_Y, 0, SIDE_X, 0, lean);
                    float oldYaw = BattleCameraShot.yawToFace(lens[0], lens[2], oldLook[0], oldLook[2]);
                    double oldMiddle = Math.abs(BattleCameraShot.wrapDegrees(
                            BattleCameraShot.yawToFace(lens[0], lens[2], idleMiddle, 0) - oldYaw));
                    check(oldMiddle > halfFrame, "reproduced: the open stage's pan put the middle of the other fighter "
                            + oldMiddle + " degrees off the axis, outside the picture; now " + middleAngle);
                }
            }
            BattleCameraShot.Lens rest = BattleCameraShot.automatic(0, lensY, distance, 0, CENTER_Y, 0, SIDE_X, 0, 0, 0, 0, 1);
            check(Math.abs(rest.x()) < 1.0E-9D && Math.abs(BattleCameraShot.wrapDegrees(rest.yaw()
                            - BattleCameraShot.yawToFace(0, distance, 0, 0))) < 1.0E-3F,
                    "without a lean the shot still looks straight at the middle from " + distance);
        }
        for (double distance : new double[]{3.6D, 4.2D, 6.0D}) {
            check(BattleCameraShot.panShare(distance) == 1.0D, "an open stage keeps its whole pan at " + distance);
        }
        double previous = 0.0D;
        for (double distance = 0.0D; distance <= 5.0D; distance += 0.05D) {
            double share = BattleCameraShot.panShare(distance);
            check(share >= previous && share >= 0.0D && share <= 1.0D, "the pan never grows as the shot comes closer");
            previous = share;
        }
    }

    private static void followCameraKeepsItsFrame() {
        double[][] bases = {{5.0D, FLOOR + 3.0D, 6.0D}, {-7.0D, FLOOR + 1.2D, -2.0D}, {0.5D, FLOOR + 6.0D, 9.0D}};
        float[][] angles = {{150.0F, 20.0F}, {-60.0F, -5.0F}, {175.0F, 35.0F}};
        for (int i = 0; i < bases.length; i++) {
            BattleCameraShot.Lens rest = BattleCameraShot.follow(bases[i][0], bases[i][1], bases[i][2], angles[i][0], angles[i][1],
                    0, CENTER_Y, 0, SIDE_X, 0, 0);
            check(Math.abs(rest.x() - bases[i][0]) < 1.0E-9D && Math.abs(rest.y() - bases[i][1]) < 1.0E-9D
                    && Math.abs(rest.z() - bases[i][2]) < 1.0E-9D, "follow mode at rest stands exactly where it was put (" + i + ")");
            check(Math.abs(BattleCameraShot.wrapDegrees(rest.yaw() - angles[i][0])) < 1.0E-3F
                    && Math.abs(rest.pitch() - angles[i][1]) < 1.0E-3F, "and looks exactly the way it was turned (" + i + ")");
        }
    }

    /**
     * The editor tells the player that a camera in follow mode keeps its spot and angle and only pans after whoever is
     * playing. It used to swing around the arena like the automatic shot as well; a hidden game measured a camera 7.2
     * blocks out moving 1.54 blocks off its spot, which carried a pillar framed beside it into the picture.
     */
    private static void followCameraKeepsItsSpotAndOnlyTurns() {
        // Reproduced: the old follow lens was the automatic lens from the placed spot, so a far camera moved far.
        double farX = 0.0D;
        double farZ = 7.2D;
        double farY = FLOOR + 3.0D;
        double oldWorst = 0.0D;
        for (int tick = 0; tick < 400; tick++) {
            // The widest swing and lift setupArena can roll (amplitude 0.24 rad, lift 0.13), at its fastest period.
            double swing = BattleCameraShot.swing(tick, 52.0D, 0.0D, 0.24D);
            double rise = BattleCameraShot.rise(tick, 52.0D, 0.13D);
            double[] oldLens = BattleCameraShot.lensPosition(farX, farY, farZ, 0, 0, SIDE_X, 0, 1.0D, swing, rise, 1.0D);
            oldWorst = Math.max(oldWorst, Math.sqrt(square(oldLens[0] - farX) + square(oldLens[1] - farY)
                    + square(oldLens[2] - farZ)));
        }
        check(oldWorst > 1.5D, "reproduced: the old follow lens moved " + oldWorst + " blocks off its spot 7.2 blocks out");

        double baseX = 1.0D;
        double baseY = FLOOR + 2.5D;
        double baseZ = 5.0D;
        // A camera framed a little off to one side of the arena, and tilted more than the automatic shot would be.
        float baseYaw = BattleCameraShot.yawToFace(baseX, baseZ, 0, 0) + 12.0F;
        float basePitch = 18.0F;
        BattleCameraShot.Lens automaticRest = BattleCameraShot.automatic(baseX, baseY, baseZ, 0, CENTER_Y, 0, SIDE_X, 0,
                0, 0, 0, 0);
        for (double lean : new double[]{-1.0D, -0.4D, 0.0D, 0.6D, 1.0D}) {
            BattleCameraShot.Lens follow = BattleCameraShot.follow(baseX, baseY, baseZ, baseYaw, basePitch, 0, CENTER_Y, 0,
                    SIDE_X, 0, lean);
            check(follow.x() == baseX && follow.y() == baseY && follow.z() == baseZ,
                    "follow mode stands exactly on its spot whatever the lean (" + lean + ")");
            // An automatic shot standing on the same spot without swing, lift or slide turns only with the lean.
            BattleCameraShot.Lens standing = BattleCameraShot.automatic(baseX, baseY, baseZ, 0, CENTER_Y, 0, SIDE_X, 0,
                    lean, 0, 0, 0);
            float automaticTurn = BattleCameraShot.wrapDegrees(standing.yaw() - automaticRest.yaw());
            float followTurn = BattleCameraShot.wrapDegrees(follow.yaw() - baseYaw);
            check(Math.abs(automaticTurn - followTurn) < 1.0E-3F,
                    "and turns as a shot standing there would: " + followTurn + " against " + automaticTurn);
            check(Math.abs((follow.pitch() - basePitch) - (standing.pitch() - automaticRest.pitch())) < 1.0E-3F,
                    "and tilts by the same angle");
        }
        BattleCameraShot.Lens left = BattleCameraShot.follow(baseX, baseY, baseZ, baseYaw, basePitch, 0, CENTER_Y, 0, SIDE_X, 0,
                -1);
        BattleCameraShot.Lens right = BattleCameraShot.follow(baseX, baseY, baseZ, baseYaw, basePitch, 0, CENTER_Y, 0, SIDE_X, 0,
                1);
        float toLeft = BattleCameraShot.yawToFace(left.x(), left.z(), -SIDE_X, 0);
        float toRight = BattleCameraShot.yawToFace(right.x(), right.z(), SIDE_X, 0);
        check(Math.abs(BattleCameraShot.wrapDegrees(left.yaw() - toLeft)) < Math.abs(BattleCameraShot.wrapDegrees(baseYaw - toLeft))
                        && Math.abs(BattleCameraShot.wrapDegrees(right.yaw() - toRight)) < Math.abs(BattleCameraShot.wrapDegrees(baseYaw - toRight)),
                "leaning left turns it towards the left fighter, leaning right towards the right one");
    }

    private static double square(double value) {
        return value * value;
    }

    private static void followCameraAboveTheArenaDoesNotSpin() {
        // Straight above the middle, looking down: a lean moves the look point under the lens.
        BattleCameraShot.Lens rest = BattleCameraShot.follow(0.05D, FLOOR + 8.0D, 0.0D, 30.0F, 89.0F, 0, CENTER_Y, 0, SIDE_X, 0,
                0);
        BattleCameraShot.Lens leaned = BattleCameraShot.follow(0.05D, FLOOR + 8.0D, 0.0D, 30.0F, 89.0F, 0, CENTER_Y, 0, SIDE_X, 0,
                1);
        check(Math.abs(BattleCameraShot.wrapDegrees(leaned.yaw() - rest.yaw())) < 1.0F,
                "a camera hanging over the arena does not spin round as the shot leans: " + leaned.yaw());
        check(leaned.pitch() <= 89.0F && leaned.pitch() >= -89.0F, "and never tilts past straight down");
    }

    /**
     * What a viewer cuts to and what it blends into. Blending a cut flies the view across the arena; cutting every
     * frame of a moving shot steps it twenty times a second, which is what the automatic shot did before.
     */
    private static void cutsAreShownAndMovesAreBlended() {
        check(BattleCameraShot.isCut(false, false, false, false, 0, 0, 0), "a new battle or camera entity is a cut");
        check(BattleCameraShot.isCut(true, false, false, false, 0, 0, 0), "the first frame of the automatic shot is a cut");
        check(!BattleCameraShot.isCut(true, false, false, true, 0.05D, 0.01D, 0.02D),
                "the automatic shot's next frame is blended");
        check(BattleCameraShot.isCut(true, false, true, false, 0.1D, 0, 0), "a camera block giving way to the automatic shot is a cut");
        check(BattleCameraShot.isCut(true, true, false, true, 0.1D, 0, 0), "and the other way round");
        check(!BattleCameraShot.isCut(true, true, true, false, 0.3D, 0.1D, 0.0D),
                "a camera block moved a little in its editor is blended");
        check(BattleCameraShot.isCut(true, true, true, false, 9.0D, 0.0D, 3.0D),
                "another camera block put on air is a cut, not a flight across the arena");
        // The count-in dollies from beside the arena centre to the camera over two seconds with a smoothstep, whose
        // fastest tick moves 1.5 times the average.
        for (double distance : new double[]{1.0D, 4.0D, 16.0D, 40.0D}) {
            double fastestTick = distance * 1.5D / 40.0D;
            check(!BattleCameraShot.isCut(true, true, true, false, fastestTick, 0, 0),
                    "the count-in's dolly of a camera " + distance + " blocks out stays blended: " + fastestTick + " a tick");
        }
    }

    /**
     * The swing is a slow sine of the battle tick, and a skip jumps the tick by a minute and a half. The server shifts
     * the swing's clock back by the ticks it skipped (BattleManager cameraClockShift), so the shot carries on where
     * it was.
     */
    private static void aSkipDoesNotWhipTheShotRound() {
        double period = 70.0D;
        double phase = 0.0D;
        double amplitude = 0.2D;
        int from = 810;
        int to = 2_740;
        double before = BattleCameraShot.swing(from, period, phase, amplitude);
        double jumped = BattleCameraShot.swing(to, period, phase, amplitude);
        check(Math.abs(Math.toDegrees(jumped - before)) > 5.0D,
                "reproduced: read from the battle tick, the skip turned the shot " + Math.toDegrees(jumped - before)
                        + " degrees in one tick");
        int shift = -(to - from);
        double after = BattleCameraShot.swing(to + shift, period, phase, amplitude);
        double riseBefore = BattleCameraShot.rise(from, period, 0.1D);
        double riseAfter = BattleCameraShot.rise(to + shift, period, 0.1D);
        check(Math.abs(after - before) < 1.0E-12D && Math.abs(riseAfter - riseBefore) < 1.0E-12D,
                "with the clock shifted by the skip the swing and the lift carry on where they were");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }
}
