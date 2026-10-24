package ru.gora.musicbattle.client;

/**
 * The battle HUD keeps the size it was designed at, at every GUI scale and window size.
 *
 * <p>Pure arithmetic: {@link BattleHudScale#frame(int, int, double, double)} takes the two things a window would
 * otherwise be asked for, so this runs without one.
 */
public final class HudScaleChecks {
    private static int checks;

    public static void main(String[] args) {
        sameApparentSizeAtEveryGuiScale();
        canvasAlwaysCoversTheScreen();
        smallWindowsAreNotPushedOffTheirOwnEdges();
        noWindowMeansNoScaling();
        System.out.println("HUD scale checks PASS: " + checks);
    }

    /**
     * One HUD unit has to cover the same number of real pixels whatever the player set, which is what "the size I
     * made the mod at" means. At 1920x1080 every scale from 1 to 4 can host scale 3, so the factor is exactly 3/scale.
     */
    private static void sameApparentSizeAtEveryGuiScale() {
        for (double guiScale : new double[] {1, 2, 3, 4}) {
            int guiWidth = (int) Math.ceil(1920 / guiScale);
            int guiHeight = (int) Math.ceil(1080 / guiScale);
            BattleHudScale.Frame frame = BattleHudScale.frame(guiWidth, guiHeight, guiScale, 4);
            double pixelsPerUnit = frame.factor() * guiScale;
            check(Math.abs(pixelsPerUnit - BattleHudScale.REFERENCE_SCALE) < 1.0E-4,
                    "one HUD unit is three screen pixels at gui scale " + guiScale);
            // And the canvas the HUD lays itself out in is the same one every time, so nothing moves.
            check(Math.abs(frame.width() - 640) <= 1 && Math.abs(frame.height() - 360) <= 1,
                    "the design canvas is the window at scale three, at gui scale " + guiScale);
        }
        BattleHudScale.Frame atThree = BattleHudScale.frame(640, 360, 3, 4);
        check(atThree.identity(), "at the design scale itself nothing is pushed");
    }

    /** A full-canvas fill must reach the edge of the screen: a seam of one pixel along it would be visible. */
    private static void canvasAlwaysCoversTheScreen() {
        int[][] windows = {{1920, 1080}, {3840, 2160}, {1280, 720}, {1024, 600}, {854, 480}, {1600, 900}};
        for (int[] window : windows) {
            for (double guiScale : new double[] {1, 2, 3, 4}) {
                int guiWidth = (int) Math.ceil(window[0] / guiScale);
                int guiHeight = (int) Math.ceil(window[1] / guiScale);
                BattleHudScale.Frame frame = BattleHudScale.frame(guiWidth, guiHeight, guiScale, 4);
                check(Math.ceil(frame.width() * frame.factor()) >= guiWidth,
                        "the canvas reaches the right edge at " + window[0] + "x" + window[1] + " scale " + guiScale);
                check(Math.ceil(frame.height() * frame.factor()) >= guiHeight,
                        "the canvas reaches the bottom edge at " + window[0] + "x" + window[1] + " scale " + guiScale);
            }
        }
    }

    /**
     * Three is not a scale every window can host. Forcing it on a small one would push the lanes off the screen, so
     * the largest scale that fits is used instead, which is what the game itself would have chosen.
     */
    private static void smallWindowsAreNotPushedOffTheirOwnEdges() {
        // A 640x480 window: Minecraft would allow scale 2 at most.
        BattleHudScale.Frame frame = BattleHudScale.frame(640, 480, 1, 2);
        check(Math.abs(frame.factor() - 2.0F) < 1.0E-4F, "a window that fits two is scaled by two, not three");
        check(frame.width() == 320 && frame.height() == 240, "and the canvas is what fits");

        BattleHudScale.Frame tiny = BattleHudScale.frame(320, 240, 1, 1);
        check(tiny.identity(), "a window that fits only one is left alone");
    }

    /** A harness with no window, and a nonsense canvas, must not scale anything. */
    private static void noWindowMeansNoScaling() {
        check(BattleHudScale.frame(0, 0, 3, 3).identity(), "no canvas, no scaling");
        check(BattleHudScale.frame(640, 360, 0, 3).identity(), "no gui scale, no scaling");
        BattleHudScale.Frame nothing = BattleHudScale.frame(0, 0, 0, 0);
        check(nothing.width() >= 1 && nothing.height() >= 1, "and the canvas is never zero");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
