package ru.gora.musicbattle.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The space where the music behind the world-space FX can be heard, as a union of spheres in world coordinates.
 *
 * <p>The block outline and the entity glow used to reach as far as the camera could see. They now stay inside the
 * area the music fills and fade to nothing over the last stretch of it, so an effect never ends in a hard cut.
 * This class is plain math without Minecraft types: {@code FxAudibleAreaResolver} feeds it what the client world
 * shows, the outline shader receives the same spheres, and {@code FxAudibleAreaChecks} runs it without a game.</p>
 */
public final class FxAudibleArea {
    /** No known area: every world-space FX keeps the reach it had before the area existed. */
    public static final FxAudibleArea UNBOUNDED = new FxAudibleArea(new double[0], true);
    /** How many spheres the outline shader tests per pixel; it must match {@code object_outline.fsh}. */
    public static final int SHADER_SPHERES = 16;
    /** The fade band is this share of a sphere's radius, kept between the two limits below. */
    public static final float FADE_FRACTION = 0.3F;
    public static final float MIN_FADE_BLOCKS = 1.5F;
    public static final float MAX_FADE_BLOCKS = 8.0F;
    /**
     * A speaker sounds from 0.65 above its block centre, the point the server measures listeners from in
     * {@code BattleManager.nearestAudioSource} and {@code DjPlaybackManager.loudestSource}.
     */
    public static final double SPEAKER_EMITTER_HEIGHT = 0.65D;
    /** The battle's own emitter is one block above the arena centre, as in {@code BattleManager.nearestAudioSource}. */
    public static final double BATTLE_EMITTER_HEIGHT = 1.0D;
    /** The radius of a battle without speakers, {@code BattleManager.SPECTATOR_MUSIC_DISTANCE}. */
    public static final double BATTLE_RADIUS = 14.0D;
    /** The server never chains more speakers than this, {@code SpeakerNetwork.MAX_CHAIN_SPEAKERS}. */
    static final int MAX_CHAIN_SPEAKERS = 512;
    /**
     * How long, in client ticks, the player's view keeps the last DJ installation it found once it finds none. A
     * listener who walks out of the music sees the show's effects fade for about 0.65 s, 14 ticks of
     * {@code ClientBattleController#tickSpectatorFxVisibility}, from the tick they leave the speaker's sphere, and they
     * have left every sphere before they can be any distance past its edge. A few ticks more, so the fade is never cut.
     */
    public static final int DJ_FADE_HOLD_TICKS = 20;

    private final double[] spheres;
    private final boolean unbounded;

    private FxAudibleArea(double[] spheres, boolean unbounded) {
        this.spheres = spheres;
        this.unbounded = unbounded;
    }

    /** A bounded area from x, y, z, radius quadruples; spheres without a usable radius are left out. */
    public static FxAudibleArea of(double... xyzRadius) {
        if (xyzRadius.length % 4 != 0) {
            throw new IllegalArgumentException("spheres are x, y, z, radius quadruples");
        }
        double[] kept = new double[xyzRadius.length];
        int size = 0;
        for (int index = 0; index < xyzRadius.length; index += 4) {
            double radius = xyzRadius[index + 3];
            if (!(radius > 0.0D) || !Double.isFinite(radius) || !Double.isFinite(xyzRadius[index])
                    || !Double.isFinite(xyzRadius[index + 1]) || !Double.isFinite(xyzRadius[index + 2])) {
                continue;
            }
            System.arraycopy(xyzRadius, index, kept, size, 4);
            size += 4;
        }
        return new FxAudibleArea(Arrays.copyOf(kept, size), false);
    }

    public boolean unbounded() {
        return unbounded;
    }

    public int sphereCount() {
        return spheres.length / 4;
    }

    /** A copy of sphere {@code index} as x, y, z, radius. */
    public double[] sphere(int index) {
        return Arrays.copyOfRange(spheres, index * 4, index * 4 + 4);
    }

    /**
     * How strongly a world-space FX shows at a point: 1 well inside, falling smoothly to 0 at the outer edge of the
     * area, 0 outside every sphere. An unbounded area answers 1 everywhere.
     *
     * <p>The spheres' own fades add up, capped at 1. Taking the strongest one alone dimmed a lens-shaped band between
     * two overlapping speakers, where each sphere is near its own edge although the music carries on past it. The
     * sum keeps that seam lit, is exactly one sphere's fade wherever only one sphere reaches, and is still 0 on
     * every point of the outer edge, where no sphere has anything left to add.</p>
     */
    public float fadeAt(double x, double y, double z) {
        if (unbounded) {
            return 1.0F;
        }
        float fade = 0.0F;
        for (int index = 0; index < spheres.length && fade < 1.0F; index += 4) {
            double dx = x - spheres[index];
            double dy = y - spheres[index + 1];
            double dz = z - spheres[index + 2];
            fade = Math.min(1.0F, fade + edgeFade(Math.sqrt(dx * dx + dy * dy + dz * dz), spheres[index + 3]));
        }
        return fade;
    }

    /** The width of the band over which one sphere fades out, in blocks. The shader computes the same. */
    public static float fadeWidth(double radius) {
        float width = clamp((float) radius * FADE_FRACTION, MIN_FADE_BLOCKS, MAX_FADE_BLOCKS);
        return Math.max(0.001F, Math.min(width, (float) radius));
    }

    /** GLSL {@code 1 - smoothstep(radius - width, radius, distance)}, so Java and the shader fade alike. */
    public static float edgeFade(double distance, double radius) {
        if (!(radius > 0.0D)) {
            return 0.0F;
        }
        double width = fadeWidth(radius);
        double t = Math.min(1.0D, Math.max(0.0D, (distance - (radius - width)) / width));
        return (float) (1.0D - t * t * (3.0D - 2.0D * t));
    }

    /**
     * Writes up to {@link #SHADER_SPHERES} spheres relative to the camera as vec4 values (x, y, z, radius) into
     * {@code out} and returns how many were written. Camera-relative values keep float precision far from the
     * world origin. When there are more spheres than the shader holds, the ones whose surface is nearest the
     * camera win: they cover the geometry in front of it.
     */
    public int packCameraRelative(double cameraX, double cameraY, double cameraZ, float[] out) {
        int count = Math.min(Math.min(sphereCount(), SHADER_SPHERES), out.length / 4);
        Integer[] order = new Integer[sphereCount()];
        double[] surface = new double[sphereCount()];
        for (int sphere = 0; sphere < order.length; sphere++) {
            order[sphere] = sphere;
            double dx = spheres[sphere * 4] - cameraX;
            double dy = spheres[sphere * 4 + 1] - cameraY;
            double dz = spheres[sphere * 4 + 2] - cameraZ;
            surface[sphere] = Math.sqrt(dx * dx + dy * dy + dz * dz) - spheres[sphere * 4 + 3];
        }
        if (order.length > count) {
            Arrays.sort(order, (left, right) -> Double.compare(surface[left], surface[right]));
        }
        for (int slot = 0; slot < count; slot++) {
            int sphere = order[slot];
            out[slot * 4] = (float) (spheres[sphere * 4] - cameraX);
            out[slot * 4 + 1] = (float) (spheres[sphere * 4 + 1] - cameraY);
            out[slot * 4 + 2] = (float) (spheres[sphere * 4 + 2] - cameraZ);
            out[slot * 4 + 3] = (float) spheres[sphere * 4 + 3];
        }
        return count;
    }

    /**
     * Keeps the last bounded area for a while once nothing bounded is found any more. A listener's effects fade out for
     * a moment after they leave the music, and for that moment they still belong where the music was: falling back to
     * {@link #UNBOUNDED} there gave the mobs at the edge of a show their full glow and full light back just as the rest
     * faded. {@code FxAudibleAreaResolver} keeps one for the player's view of a DJ installation.
     */
    public static final class Hold {
        private final int holdTicks;
        private FxAudibleArea last;
        private long lastTick;

        public Hold(int holdTicks) {
            this.holdTicks = holdTicks;
        }

        /**
         * {@code found} when it is bounded, remembered as the last area; otherwise the last bounded area while it is at
         * most {@code holdTicks} old at {@code tick}, and {@code found} after that. A bounded area always wins at once,
         * so walking back into the music or into another installation is never held up.
         */
        public FxAudibleArea resolve(FxAudibleArea found, long tick) {
            if (!found.unbounded()) {
                last = found;
                lastTick = tick;
                return found;
            }
            if (last != null && tick >= lastTick && tick - lastTick <= holdTicks) {
                return last;
            }
            last = null;
            return found;
        }

        public void forget() {
            last = null;
        }
    }

    /**
     * A loaded speaker as the client world shows it. {@code x, y, z} is the block centre, {@code active} the
     * synced ACTIVE block state (the server sets it on every speaker it is playing through), and
     * {@code controller} the packed position of the DJ panel the speaker is wired to, or {@link #NO_CONTROLLER}.
     */
    public record Speaker(double x, double y, double z, double radius, boolean active, long controller) {
        public static final long NO_CONTROLLER = Long.MIN_VALUE;
    }

    /**
     * The area of a battle whose arena centre is known: the battle's own emitter, plus every playing speaker
     * that {@code SpeakerNetwork.connectedSpeakers} would chain to it. The chain starts at the centre with the
     * battle radius and grows through any speaker whose centre lies inside a source already reached, exactly
     * like the server's. Only active speakers are followed: the server marks every speaker of the chain active
     * while the song plays, so the active ones reachable from the centre are that chain. The emitter itself
     * stays in the area even when speakers replace it for listeners, because the arena is where the
     * participants play and where the battle's effects belong.
     */
    public static FxAudibleArea battle(double centerX, double centerY, double centerZ, List<Speaker> speakers) {
        List<Double> spheres = new ArrayList<>();
        addSphere(spheres, centerX, centerY + BATTLE_EMITTER_HEIGHT, centerZ, BATTLE_RADIUS);
        List<Speaker> remaining = new ArrayList<>();
        for (Speaker speaker : speakers) {
            if (speaker.active()) remaining.add(speaker);
        }
        ArrayDeque<double[]> sources = new ArrayDeque<>();
        sources.add(new double[]{centerX, centerY, centerZ, BATTLE_RADIUS});
        int connected = 0;
        while (!sources.isEmpty() && connected < MAX_CHAIN_SPEAKERS) {
            double[] source = sources.removeFirst();
            for (int index = remaining.size() - 1; index >= 0; index--) {
                Speaker candidate = remaining.get(index);
                double dx = candidate.x() - source[0];
                double dy = candidate.y() - source[1];
                double dz = candidate.z() - source[2];
                if (dx * dx + dy * dy + dz * dz > source[3] * source[3]) {
                    continue;
                }
                remaining.remove(index);
                connected++;
                sources.addLast(new double[]{candidate.x(), candidate.y(), candidate.z(), candidate.radius()});
                addSphere(spheres, candidate.x(), candidate.y() + SPEAKER_EMITTER_HEIGHT, candidate.z(),
                        candidate.radius());
            }
        }
        return of(toArray(spheres));
    }

    /**
     * The area of a DJ installation heard at {@code anchor}: every speaker wired to the same panel as a speaker
     * whose sphere holds the anchor. A DJ plays through the speakers linked to its panel, not through a chain,
     * so wiring decides membership. Playing speakers win; when none covers the anchor, the nearest playing one
     * within {@code slack} blocks of its own edge is taken (a listener walking out while the effects fade), and
     * only after that a silent wired speaker (an editor preview while the panel is stopped). Returns
     * {@link #UNBOUNDED} when no wired speaker qualifies.
     */
    public static FxAudibleArea installation(double anchorX, double anchorY, double anchorZ, List<Speaker> speakers,
                                             double slack) {
        Set<Long> controllers = new HashSet<>();
        boolean active = collectCovering(anchorX, anchorY, anchorZ, speakers, true, controllers);
        if (!active) {
            active = collectNearest(anchorX, anchorY, anchorZ, speakers, slack, controllers);
        }
        if (!active && !collectCovering(anchorX, anchorY, anchorZ, speakers, false, controllers)) {
            return UNBOUNDED;
        }
        return wiredTo(controllers, speakers, active);
    }

    /**
     * The area of one DJ panel's installation, whatever point it is looked at from: the DJ monitor shows its panel's
     * effects through a camera that may stand outside every speaker. The playing speakers while the panel plays, all
     * of its wired speakers while it is stopped (an editor preview), {@link #UNBOUNDED} when none is wired to it.
     */
    public static FxAudibleArea panel(long controller, List<Speaker> speakers) {
        if (controller == Speaker.NO_CONTROLLER) {
            return UNBOUNDED;
        }
        boolean playing = false;
        for (Speaker speaker : speakers) {
            if (speaker.controller() == controller && speaker.active()) {
                playing = true;
                break;
            }
        }
        return wiredTo(Set.of(controller), speakers, playing);
    }

    /** Every speaker wired to one of the panels, only the playing ones when {@code playingOnly}. */
    private static FxAudibleArea wiredTo(Set<Long> controllers, List<Speaker> speakers, boolean playingOnly) {
        List<Double> spheres = new ArrayList<>();
        for (Speaker speaker : speakers) {
            if (speaker.controller() != Speaker.NO_CONTROLLER && controllers.contains(speaker.controller())
                    && (speaker.active() || !playingOnly)) {
                addSphere(spheres, speaker.x(), speaker.y() + SPEAKER_EMITTER_HEIGHT, speaker.z(), speaker.radius());
            }
        }
        return spheres.isEmpty() ? UNBOUNDED : of(toArray(spheres));
    }

    /** Adds the panel of every wired speaker whose sphere holds the point; true when there was one. */
    private static boolean collectCovering(double x, double y, double z, List<Speaker> speakers,
                                           boolean activeOnly, Set<Long> controllers) {
        boolean found = false;
        for (Speaker speaker : speakers) {
            if (speaker.controller() == Speaker.NO_CONTROLLER || activeOnly && !speaker.active()) {
                continue;
            }
            if (distanceToEmitter(x, y, z, speaker) <= speaker.radius()) {
                controllers.add(speaker.controller());
                found = true;
            }
        }
        return found;
    }

    /** Adds the panel of the playing speaker whose edge is nearest the point, if it is within {@code slack}. */
    private static boolean collectNearest(double x, double y, double z, List<Speaker> speakers, double slack,
                                          Set<Long> controllers) {
        Speaker nearest = null;
        double nearestGap = slack;
        for (Speaker speaker : speakers) {
            if (speaker.controller() == Speaker.NO_CONTROLLER || !speaker.active()) {
                continue;
            }
            double gap = distanceToEmitter(x, y, z, speaker) - speaker.radius();
            if (gap <= nearestGap) {
                nearestGap = gap;
                nearest = speaker;
            }
        }
        if (nearest == null) {
            return false;
        }
        controllers.add(nearest.controller());
        return true;
    }

    private static double distanceToEmitter(double x, double y, double z, Speaker speaker) {
        double dx = x - speaker.x();
        double dy = y - (speaker.y() + SPEAKER_EMITTER_HEIGHT);
        double dz = z - speaker.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void addSphere(List<Double> spheres, double x, double y, double z, double radius) {
        spheres.add(x);
        spheres.add(y);
        spheres.add(z);
        spheres.add(radius);
    }

    private static double[] toArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int index = 0; index < array.length; index++) {
            array[index] = values.get(index);
        }
        return array;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
