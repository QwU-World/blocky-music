package ru.gora.musicbattle.entity;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smiley balls within reach of a playing DJ booth face it (A05). A ball outside the speakers' range is not dancing, so it
 * used to go on strolling, and a walking mob's body turns to where it walks every tick: in a hidden dedicated-server run
 * a ball 26 blocks from a playing booth showed it its back. The booth goal now holds the ball still while it watches.
 */
public final class SmileyBoothLookChecks {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        String code = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/entity/SmileyBallEntity.java"));
        String goals = body(code, "protected void registerGoals()");
        int audience = goals.indexOf("goalSelector.addGoal(2, new Goal()");
        int booth = goals.indexOf("goalSelector.addGoal(3, new Goal()");
        check(audience >= 0 && audience < booth && goals.substring(audience, booth).contains("audienceLookTarget"),
                "an audible battle's audience goal comes first and still wins the ball's gaze");
        String boothGoal = booth < 0 ? "" : goals.substring(booth, nextGoal(goals, booth));
        check(boothGoal.contains("findPlayingDjPanel("), "the goal at priority 3 is the one that watches the booth");
        check(boothGoal.contains("Flag.MOVE") && boothGoal.contains("Flag.LOOK"),
                "watching the booth claims MOVE as well as LOOK, so strolling cannot turn the ball away from it");
        check(boothGoal.matches("(?s).*public void start\\(\\)\\s*\\{\\s*getNavigation\\(\\)\\.stop\\(\\);.*"),
                "a ball that starts watching stops the walk it was on");
        check(boothGoal.contains("isPlaybackPaused()") || code.contains("!panel.isPlaybackPaused()"),
                "a paused booth lets the ball go");
        String stroll = goals.substring(goals.indexOf("WaterAvoidingRandomStrollGoal"));
        check(goals.indexOf("WaterAvoidingRandomStrollGoal") > booth && stroll.startsWith("WaterAvoidingRandomStrollGoal(this")
                        && goals.contains("goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal"),
                "strolling stays below the booth goal, so the booth goal wins the MOVE flag");
        System.out.println("Smiley booth look checks: " + checks + " run, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static int nextGoal(String goals, int from) {
        int next = goals.indexOf("goalSelector.addGoal(", from + 1);
        return next < 0 ? goals.length() : next;
    }

    private static String body(String code, String signature) {
        int start = code.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int index = code.indexOf('{', start); index < code.length(); index++) {
            char c = code.charAt(index);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(start, index + 1);
            }
        }
        return "";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "PASS " : "FAIL ") + message);
    }
}
