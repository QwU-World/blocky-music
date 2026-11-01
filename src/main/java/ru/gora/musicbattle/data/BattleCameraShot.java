package ru.gora.musicbattle.data;

/**
 * Where a moving battle shot stands and where it looks on one tick, as plain numbers.
 *
 * <p>Two shots move while a song plays. The automatic one, used when no camera block covers the arena, swings slowly
 * around the middle of the arena and leans towards whichever fighter is playing. A camera block in follow mode keeps
 * the spot and the angle the player gave it and only turns: at rest it shows exactly the framed shot, and from there
 * it turns towards the playing fighter by as much as a shot standing on that spot would.
 *
 * <p>Every position here is the lens, the point the picture is taken from. The automatic shot used to store the feet
 * of its helper armor stand and aim from them, while the picture was taken an eye height higher: the lens sat almost
 * three blocks above the floor and looked straight ahead over the fighters' heads. It now stands at a standing
 * player's eye level and aims from where it really is.
 *
 * <p>Pure arithmetic, so tools/BattleCameraChecks.java runs it without a world.
 */
public final class BattleCameraShot {
    /** Lens heights above the fighters' floor tried for the automatic shot, lowest first: about a player's eyes. */
    public static final double[] LENS_HEIGHTS = {1.8D, 2.05D, 2.3D};
    /** Distances from the middle of the arena tried for the automatic shot, nearest first. */
    public static final double[] DISTANCES = {3.6D, 4.2D, 4.8D};
    /**
     * Closer distances tried on the bisector, nearest {@link #DISTANCES} first, when neither side of it has room at
     * those: a cramped room. See BattlePlacementMath#automaticCameraSpots for why before any diagonal.
     */
    public static final double[] CLOSE_DISTANCES = {3.3D, 2.85D, 2.4D};
    /**
     * Where the shot looks, above the point it swings around. That point sits 1.22 below the middle of the two
     * fighters' bodies (see BattleManager#setupArena), so for two players this is a little below their waists.
     */
    public static final double LOOK_HEIGHT = 1.05D;
    /** How far the look point leans towards the playing fighter, as a share of half the way between the two. */
    public static final double FOCUS_PAN = 0.5D;
    /** How far the lens itself slides after them the same way; small, so the turn does most of the work. */
    public static final double FOCUS_TRUCK = 0.2D;
    /**
     * Below this horizontal distance between the lens and the point it looks at, a turn is not followed: a camera
     * placed straight above the arena would otherwise spin around its own axis as the look point moved under it.
     */
    private static final double MIN_YAW_DISTANCE = 0.25D;
    /** Over this much more distance the turn is followed fully. */
    private static final double YAW_DISTANCE_RAMP = 0.75D;
    /**
     * How much of its swing a cramped shot gives up per tick once a wall shows up ahead of it. The server looks ahead
     * along the swing, so the shot is usually already narrower by the time it gets there; dropping from a full swing
     * to none in one tick, as the shot used to, read as a jump.
     */
    public static final double SWING_DROP_PER_TICK = 0.1D;
    /** And how slowly it takes the room back once there is space again. */
    public static final double SWING_GROW_PER_TICK = 0.02D;
    /**
     * Blocks between two frames of a battle shot beyond which a viewer cuts to the second instead of blending into
     * it. The fastest a shot moves on its own is the count-in's dolly, under a block a tick for any camera near the
     * arena.
     */
    public static final double CUT_DISTANCE = 4.0D;

    private BattleCameraShot() {
    }

    /** One frame of a shot: the lens and the direction it looks, in Minecraft degrees. */
    public record Lens(double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * How far the shot has swung around the middle of the arena at {@code playTicks}, in radians: a slow sine of
     * {@code periodTicks} that starts at {@code phase} and reaches at most {@code amplitude}.
     */
    public static double swing(double playTicks, double periodTicks, double phase, double amplitude) {
        return Math.sin(playTicks / periodTicks + phase) * amplitude;
    }

    /** The slow lift that goes with the swing, in blocks, on a period a little shorter so the two never line up. */
    public static double rise(double playTicks, double periodTicks, double amplitude) {
        return Math.sin(playTicks / (periodTicks * 0.72D)) * amplitude;
    }

    /**
     * The lens of the automatic shot: {@code base} turned by {@code swing} around {@code center}, lifted by
     * {@code rise}, and slid after the playing fighter by {@code lean} (-1 the left one, 1 the right one) along
     * {@code side}, which is half the way from the left fighter to the right one, flat. {@code slide} scales that
     * slide: 1 normally, 0 for a cramped shot that has room only where it was set up.
     */
    public static double[] lensPosition(double baseX, double baseY, double baseZ,
                                        double centerX, double centerZ,
                                        double sideX, double sideZ, double lean, double swing, double rise,
                                        double slide) {
        double offsetX = baseX - centerX;
        double offsetZ = baseZ - centerZ;
        double cos = Math.cos(swing);
        double sin = Math.sin(swing);
        double truck = lean * FOCUS_TRUCK * slide;
        return new double[]{
                centerX + sideX * truck + offsetX * cos - offsetZ * sin,
                baseY + rise,
                centerZ + sideZ * truck + offsetX * sin + offsetZ * cos
        };
    }

    /** The point the shot looks at: above the middle, leaned towards the playing fighter. */
    public static double[] lookPoint(double centerX, double centerY, double centerZ,
                                     double sideX, double sideZ, double lean) {
        double pan = lean * FOCUS_PAN;
        return new double[]{centerX + sideX * pan, centerY + LOOK_HEIGHT, centerZ + sideZ * pan};
    }

    /**
     * The share of {@link #FOCUS_PAN} a shot set up {@code distance} blocks from the middle of the arena, flat, leans
     * by: all of it from the nearest usual distance out, and the square of the way there closer in.
     *
     * <p>Closer in, the two fighters already spread wider across the picture and the same pan turns the shot further,
     * so a cramped room's shot, 2.4 blocks out, turned the fighter who was not playing out of the frame: with Minecraft's
     * default field of view on a 16:9 screen the middle of their body ended up 53.5 degrees off the axis, against 51 at
     * the edge of the picture. With the square they stay about where an open stage keeps them: the far side of their
     * body 46 degrees off the axis at 2.4 blocks, and 42 at 3.6.</p>
     */
    public static double panShare(double distance) {
        double share = distance / DISTANCES[0];
        return share >= 1.0D ? 1.0D : Math.max(0.0D, share * share);
    }

    /** {@link #lookPoint} for a shot set up at {@code base}, with the pan its distance from the middle allows. */
    public static double[] lookPointFrom(double baseX, double baseZ, double centerX, double centerY, double centerZ,
                                         double sideX, double sideZ, double lean) {
        return lookPoint(centerX, centerY, centerZ, sideX, sideZ,
                lean * panShare(Math.hypot(baseX - centerX, baseZ - centerZ)));
    }

    /** The automatic shot: aimed straight at the leaning look point from wherever the lens ended up. */
    public static Lens automatic(double baseX, double baseY, double baseZ,
                                 double centerX, double centerY, double centerZ,
                                 double sideX, double sideZ, double lean, double swing, double rise, double slide) {
        double[] lens = lensPosition(baseX, baseY, baseZ, centerX, centerZ, sideX, sideZ, lean, swing, rise, slide);
        double[] look = lookPointFrom(baseX, baseZ, centerX, centerY, centerZ, sideX, sideZ, lean);
        return new Lens(lens[0], lens[1], lens[2],
                yawToFace(lens[0], lens[2], look[0], look[2]),
                pitchToFace(lens[0], lens[1], lens[2], look[0], look[1], look[2]));
    }

    /**
     * A camera block in follow mode. It stays exactly where it was placed and only turns: away from the angle it was
     * given by exactly as much as a lens standing on that spot turns between the middle of the arena and the leaning
     * look point. A camera framed off to one side of the arena stays off to that side.
     *
     * <p>It used to move like the automatic shot as well, swinging around the arena, rising and sliding after the
     * playing fighter. The swing is an angle around the arena, so a camera placed seven blocks out moved one and a half
     * blocks and carried a pillar it had been framed beside a third of the way into the picture, while the editor tells
     * the player that the camera keeps its spot and angle and only pans after whoever is playing.</p>
     */
    public static Lens follow(double baseX, double baseY, double baseZ, float baseYaw, float basePitch,
                              double centerX, double centerY, double centerZ,
                              double sideX, double sideZ, double lean) {
        double[] restLook = lookPoint(centerX, centerY, centerZ, sideX, sideZ, 0.0D);
        double[] look = lookPointFrom(baseX, baseZ, centerX, centerY, centerZ, sideX, sideZ, lean);
        float yawTurn = wrapDegrees(yawToFace(baseX, baseZ, look[0], look[2])
                - yawToFace(baseX, baseZ, restLook[0], restLook[2]));
        float pitchTurn = pitchToFace(baseX, baseY, baseZ, look[0], look[1], look[2])
                - pitchToFace(baseX, baseY, baseZ, restLook[0], restLook[1], restLook[2]);
        double horizontal = Math.min(Math.hypot(restLook[0] - baseX, restLook[2] - baseZ),
                Math.hypot(look[0] - baseX, look[2] - baseZ));
        float yawWeight = (float) clamp((horizontal - MIN_YAW_DISTANCE) / YAW_DISTANCE_RAMP, 0.0D, 1.0D);
        return new Lens(baseX, baseY, baseZ,
                wrapDegrees(baseYaw + yawTurn * yawWeight),
                (float) clamp(basePitch + pitchTurn, -89.0D, 89.0D));
    }

    /**
     * Whether a viewer shows a battle camera frame at once instead of blending into it from the last one. A cut is a
     * frame of another battle or camera entity ({@code sameCamera} false), a switch between a camera block's shot
     * ({@code blockShot}) and the automatic one, the first automatic frame, or a frame further from the last than
     * {@link #CUT_DISTANCE}: another camera block put on air, or a camera reset to where it was placed. The last frame
     * is described by {@code lastBlockShot} and {@code lastAutomatic}, and {@code dx}, {@code dy}, {@code dz} is the
     * step from it.
     */
    public static boolean isCut(boolean sameCamera, boolean blockShot, boolean lastBlockShot, boolean lastAutomatic,
                                double dx, double dy, double dz) {
        boolean jumped = dx * dx + dy * dy + dz * dz > CUT_DISTANCE * CUT_DISTANCE;
        return !sameCamera || jumped || blockShot != lastBlockShot || !blockShot && !lastAutomatic;
    }

    /**
     * The share of the swing used on this tick, moved from {@code current} towards {@code allowed}, the largest share
     * that has room now and a little later: down quickly, back up slowly.
     */
    public static double easeSwingScale(double current, double allowed) {
        double clampedAllowed = clamp(allowed, 0.0D, 1.0D);
        if (clampedAllowed < current) {
            return Math.max(clampedAllowed, current - SWING_DROP_PER_TICK);
        }
        return Math.min(clampedAllowed, current + SWING_GROW_PER_TICK);
    }

    /** Minecraft yaw that looks from one point to another, flat. */
    public static float yawToFace(double fromX, double fromZ, double toX, double toZ) {
        return wrapDegrees((float) (Math.atan2(toZ - fromZ, toX - fromX) * 180.0D / Math.PI) - 90.0F);
    }

    /** Minecraft pitch that looks from one point to another: negative is up. */
    public static float pitchToFace(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return wrapDegrees((float) (-(Math.atan2(toY - fromY, horizontal) * 180.0D / Math.PI)));
    }

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
