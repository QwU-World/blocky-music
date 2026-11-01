package ru.gora.musicbattle.block;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;

/**
 * A closed DJ panel stays closed: only the answer to a right-click opens it. A refresh answer still on its way when the
 * player pressed Esc used to put the panel back on screen (seen in a hidden dedicated-server run on the first try).
 */
public final class DjPanelReopenChecks {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        BlockPos booth = new BlockPos(0, -60, 0);
        BlockPos other = new BlockPos(9, -60, 0);
        long now = 1_000_000_000_000L;
        check(!DjPanelOpenRequests.consume(booth, now), "an answer nobody asked for does not open the panel");
        DjPanelOpenRequests.requested(booth);
        check(!DjPanelOpenRequests.consume(other, System.nanoTime()), "a click on one booth does not open another");
        check(DjPanelOpenRequests.consume(booth, System.nanoTime()), "the answer to the click opens the panel");
        check(!DjPanelOpenRequests.consume(booth, System.nanoTime()),
                "the click is used up: the next refresh answer after Esc does not reopen it");
        DjPanelOpenRequests.requested(booth);
        check(!DjPanelOpenRequests.consume(booth, System.nanoTime() + DjPanelOpenRequests.WINDOW_NANOS + 1),
                "a click long forgotten does not open the panel");
        DjPanelOpenRequests.requested(booth);
        check(DjPanelOpenRequests.consume(booth, System.nanoTime() + DjPanelOpenRequests.WINDOW_NANOS / 2),
                "a slow server's answer still opens it");

        String main = "src/main/java/ru/gora/musicbattle/";
        String block = Files.readString(root.resolve(main + "block/DjPanelBlock.java"));
        int open = block.indexOf("private static void openPanel(Level level, BlockPos pos, Player player)");
        String openBody = open < 0 ? "" : block.substring(open, block.indexOf("\n    }\n", open));
        check(openBody.contains("level.isClientSide") && openBody.contains("DjPanelOpenRequests.requested(pos)"),
                "the client notes its own right-click on the booth");
        String controller = Files.readString(root.resolve(main + "client/ClientBattleController.java"));
        int start = controller.indexOf("public static void openDjPanel(OpenDjPanelPayload payload)");
        String body = start < 0 ? "" : controller.substring(start, controller.indexOf("\n    }\n", start));
        int opens = body.split("setScreen\\(new DjPanelScreen", -1).length - 1;
        int guarded = body.split("DjPanelOpenRequests\\.consume\\(payload\\.panelPos\\(\\)\\)\\) \\{\\s*(//[^\\n]*\\s*)*minecraft\\.setScreen\\(new DjPanelScreen", -1).length - 1;
        check(opens > 0 && opens == guarded, "every place that opens a new panel asks for the click first (" + guarded
                + "/" + opens + ")");
        System.out.println("DJ panel reopen checks: " + checks + " run, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures++;
        }
        System.out.println((condition ? "PASS " : "FAIL ") + message);
    }
}
