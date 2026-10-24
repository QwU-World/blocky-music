import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import ru.gora.musicbattle.data.BattlePlacementMath;
import ru.gora.musicbattle.data.BattlePlacementMath.Bounds;
import ru.gora.musicbattle.data.BattlePlacementMath.Candidate;

/** Exercises the same pair reservation/limits code used by the server adapter. */
public final class BattlePlacementChecks {
    private static int checks;

    public static void main(String[] args) {
        Bounds player = box(0, 1, 0, .6, 1.8);
        check(player.fits(-64, 320, -10, -10, 10, 10), "normal player fits");
        check(!box(9.9, 1, 0, .6, 1.8).fits(-64, 320, -10, -10, 10, 10), "whole width inside border");
        check(!box(0, 319, 0, .6, 1.8).fits(-64, 320, -10, -10, 10, 10), "head below ceiling");
        check(!box(0, -64.1, 0, .6, 1.8).fits(-64, 320, -10, -10, 10, 10), "feet above bottom");
        check(!box(Double.NaN, 1, 0, .6, 1.8).usable(), "NaN rejected before world query");
        check(!box(0, 1, 0, 1000, 1.8).usable(), "unbounded query rejected");

        Candidate left = at(0, 0, 1, 0, .6, 1.8);
        Candidate overlapping = at(0, .4, 1, 0, .6, 1.8);
        Candidate clear = at(2, 2, 1, 0, .6, 1.8);
        check(BattlePlacementMath.reserve(List.of(left), List.of(overlapping)).isEmpty(), "reserve both, not separately");
        check(BattlePlacementMath.reserve(List.of(left), List.of(overlapping, clear)).orElseThrow().right() == clear, "find alternative after overlap");
        check(BattlePlacementMath.reserve(List.of(), List.of(clear)).isEmpty(), "cancel when no left spot");
        check(BattlePlacementMath.reserve(List.of(left), List.of()).isEmpty(), "cancel when no right spot");
        Candidate edge = at(0, .6, 1, 0, .6, 1.8);
        check(BattlePlacementMath.reserve(List.of(left), List.of(edge)).isPresent(), "touching edges are not overlapping");
        Candidate dragon = at(0, 7.5, 1, 0, 16, 8);
        check(BattlePlacementMath.reserve(List.of(left), List.of(dragon)).isEmpty(), "large mob center clear is insufficient");
        Candidate fartherDragon = at(1, 8.5, 1, 0, 16, 8);
        check(BattlePlacementMath.reserve(List.of(left), List.of(dragon, fartherDragon)).orElseThrow().right() == fartherDragon, "reserve full large mob body");
        Candidate baby = at(0, .51, 1, 0, .4, .4);
        check(BattlePlacementMath.reserve(List.of(left), List.of(baby)).isPresent(), "actual baby size respected");
        Candidate tall = at(0, 0, 2.4, 0, .8, 1);
        check(player.intersects(tall.bounds()), "standing player head reserved even if initially crouching");
        Candidate cheapBlocked = at(0, 0, 1, 0, .6, 1.8);
        Candidate cheapAlternative = at(1, -2, 1, 0, .6, 1.8);
        Candidate costlyRight = at(100, 5, 1, 0, .6, 1.8);
        var joint = BattlePlacementMath.reserve(List.of(cheapBlocked, cheapAlternative), List.of(cheapBlocked, costlyRight)).orElseThrow();
        check(joint.left() == cheapAlternative && joint.right() == cheapBlocked, "minimum combined move, not greedy first participant");

        Random random = new Random(5162026L);
        for (int sample = 0; sample < 2000; sample++) {
            List<Candidate> first = candidates(random), second = candidates(random);
            double best = Double.POSITIVE_INFINITY;
            for (Candidate a : first) for (Candidate b : second) {
                Bounds x = a.bounds(), y = b.bounds();
                boolean separated = x.maxX() <= y.minX() || y.maxX() <= x.minX()
                        || x.maxY() <= y.minY() || y.maxY() <= x.minY()
                        || x.maxZ() <= y.minZ() || y.maxZ() <= x.minZ();
                if (separated) best = Math.min(best, a.cost() + b.cost());
            }
            var result = BattlePlacementMath.reserve(first, second);
            check(result.isPresent() == Double.isFinite(best), "pair existence matches independent exhaustive oracle");
            if (result.isPresent()) {
                check(Math.abs(result.get().left().cost() + result.get().right().cost() - best) < 1e-10, "minimum safe cost");
                check(!result.get().left().bounds().intersects(result.get().right().bounds()), "no overlap in selected pair");
            }
        }
        automaticCameraStaysCentredBetweenFighters();
        cameraBlockOpensFromTheCentre();
        System.out.println("Battle placement checks passed: " + checks);
    }

    /**
     * A placed camera block opens the way the automatic shot does: a short step from the arena centre
     * toward its own position. Never further out than that step, never beyond the camera itself, and
     * always on the line from the centre to the camera, whichever way and however far it was placed.
     */
    private static void cameraBlockOpensFromTheCentre() {
        double step = BattlePlacementMath.CAMERA_INTRO_DISTANCE;
        for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 9) {
            for (double distance : new double[]{0.0, 0.4, step, 1.5, 3.0, 9.0, 16.0}) {
                double cameraX = Math.cos(angle) * distance, cameraZ = Math.sin(angle) * distance;
                BattlePlacementMath.Offset start = BattlePlacementMath.introOffset(cameraX, cameraZ);
                double fromCentre = Math.hypot(start.x(), start.z());
                check(fromCentre <= Math.min(step, distance) + 1e-9,
                        "a camera block opens no further out than the step, and never beyond itself");
                if (distance > 1e-9) {
                    double cross = start.x() * cameraZ - start.z() * cameraX;
                    double dot = start.x() * cameraX + start.z() * cameraZ;
                    check(Math.abs(cross) < 1e-9 && dot >= -1e-9,
                            "the opening frame lies on the line from the centre to the camera block");
                }
                if (distance > step) {
                    check(Math.abs(fromCentre - step) < 1e-9, "a distant camera block opens one step from the centre");
                } else {
                    check(Math.abs(start.x() - cameraX) < 1e-9 && Math.abs(start.z() - cameraZ) < 1e-9,
                            "a camera block inside the step, or straight above the centre, opens at its own spot");
                }
            }
        }
    }

    /**
     * The automatic camera opens and settles on the perpendicular bisector, so both fighters are
     * the same distance away; only a blocked bisector may fall back to a diagonal, and the
     * directions behind a fighter are offered last.
     */
    private static void automaticCameraStaysCentredBetweenFighters() {
        double[] distances = {BattlePlacementMath.CAMERA_INTRO_DISTANCE, 3.6, 4.2, 4.8};
        for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 7) {
            double axisX = Math.cos(angle), axisZ = Math.sin(angle);
            double leftX = -2 * axisX, leftZ = -2 * axisZ, rightX = 2 * axisX, rightZ = 2 * axisZ;
            var directions = BattlePlacementMath.cameraDirections(-axisZ, axisX);
            check(directions.size() == 8, "every camera direction is offered");
            check(Math.abs(directions.get(1).x() + directions.get(0).x()) < 1e-12
                    && Math.abs(directions.get(1).z() + directions.get(0).z()) < 1e-12,
                    "both sides of the bisector are tried before anything else");
            for (int i = 0; i < directions.size(); i++) {
                var direction = directions.get(i);
                check(Math.abs(Math.hypot(direction.x(), direction.z()) - 1) < 1e-12, "camera directions are unit length");
                double alongFighterLine = Math.abs(direction.x() * axisX + direction.z() * axisZ);
                if (i < 2) {
                    check(alongFighterLine < 1e-12, "the opening shot is on the perpendicular bisector");
                    BattlePlacementMath.Offset opening = BattlePlacementMath.introOffset(direction.x() * 4.2, direction.z() * 4.2);
                    check(Math.abs(Math.hypot(opening.x() - leftX, opening.z() - leftZ)
                                    - Math.hypot(opening.x() - rightX, opening.z() - rightZ)) < 1e-6,
                            "the real opening frame of an automatic shot is the same distance from both fighters");
                    for (double distance : distances) {
                        double x = direction.x() * distance, z = direction.z() * distance;
                        check(Math.abs(Math.hypot(x - leftX, z - leftZ) - Math.hypot(x - rightX, z - rightZ)) < 1e-6,
                                "camera start and target are the same distance from both fighters");
                    }
                } else if (i < 6) {
                    check(Math.abs(alongFighterLine - Math.sqrt(0.5)) < 1e-12, "a blocked bisector falls back to a 45 degree diagonal");
                } else {
                    check(alongFighterLine > 1 - 1e-12, "standing behind a fighter is the last resort");
                }
            }
        }
    }

    private static List<Candidate> candidates(Random random) {
        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < 6; i++) result.add(at(random.nextDouble() * 10, random.nextInt(6), random.nextInt(3), random.nextInt(6), .4 + random.nextDouble() * 4, .4 + random.nextDouble() * 3));
        return result;
    }

    private static Candidate at(double cost, double x, double y, double z, double width, double height) {
        return new Candidate(x, y, z, box(x, y, z, width, height), cost);
    }

    private static Bounds box(double x, double y, double z, double width, double height) {
        return new Bounds(x - width / 2, y, z - width / 2, x + width / 2, y + height, z + width / 2);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
