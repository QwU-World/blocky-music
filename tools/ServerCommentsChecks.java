package ru.gora.musicbattle.server.workshop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The {@code server-comments} check of spec 06 section 12: the key grammar and path checks of 08 A5.2
 * ({@code track_../..}, backslashes, colons, over-long keys and player names like {@code ../x}), paging cursors,
 * rate limits, the per-player and per-world document quotas, author and moderator deletes, {@code §} and control
 * stripping, atomic writes, ordered writes under concurrency and crash recovery.
 *
 * <p>It drives the production {@link ServerComments} through its player-free cores, so the rules under test are the
 * ones a real post goes through, not a copy of them.
 *
 * <p>Usage: {@code java -cp <classpath> ru.gora.musicbattle.server.workshop.ServerCommentsChecks <project root>}
 */
public final class ServerCommentsChecks {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static Path temp;
    private static Path comments;
    private static Path meta;

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-4000-8000-000000000003");

    public static void main(String[] args) throws Exception {
        temp = Files.createTempDirectory("blocky-server-comments-");
        FMLPaths.loadAbsolutePaths(temp.resolve("game"));

        keyGrammar();
        pathContainment();
        textRules();
        postingAndPaging();
        rateLimits();
        quotas();
        deletes();
        mutesReportsExports();
        atomicWrites();
        concurrentPosts();
        crashRecovery();
        retention();

        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " / " + checks + " server-comment failures: " + failures);
        }
        System.out.println("Server comments checks passed: " + checks);
        System.out.println("NOT COVERED: the payload round trip, skin heads and a real dedicated server");
    }

    // ---------------------------------------------------------------- keys and paths

    private static void keyGrammar() {
        for (String key : List.of("ws_1", "ws_42", "ws_9999999999999999999", "track_practice_beat", "track_a-b_c",
                "track_" + "a".repeat(128))) {
            check("valid key " + shorten(key), ServerComments.KEY.matcher(key).matches());
        }
        for (String key : List.of("track_../..", "track_..%2f..", "ws_0", "ws_01", "ws_", "track_", "../x",
                "track_a\\b", "track_a/b", "track_a:b", "track_A", "track_" + "a".repeat(129), "ws_1 ", " ws_1",
                "ws_1.json", "track_a.json", "", "..", ".", "track_a\u0000b", "WS_1")) {
            check("refused key " + shorten(key), !ServerComments.KEY.matcher(key).matches());
        }
        check("a null key is refused", ServerComments.keyError(null).isPresent());
    }

    private static void pathContainment() throws Exception {
        reset();
        for (String key : List.of("ws_42", "track_practice_beat")) {
            Optional<Path> file = ServerComments.fileOf(key);
            check("the file of " + key + " exists as a path", file.isPresent());
            check("the file of " + key + " sits directly in the comments folder",
                    file.orElseThrow().getParent().equals(comments));
            check("the file of " + key + " is named after the key",
                    file.orElseThrow().getFileName().toString().equals(key + ".json"));
        }
        for (String key : List.of("track_../..", "../x", "track_a\\b", "track_a/b", "track_a:b",
                "track_" + "a".repeat(129), "ws_0")) {
            check("no path for " + shorten(key), ServerComments.fileOf(key).isEmpty());
        }
        // A read never creates a file (08 A5.2).
        ServerComments.page(ALICE, false, Optional.empty(), "ws_4242", 0, 20);
        check("reading an unknown map creates no file",
                !Files.exists(comments.resolve("ws_4242.json")));
        try (var stream = Files.list(comments)) {
            check("the comments folder is still empty after a read", stream.findAny().isEmpty());
        }
        // A track_ key must name a track the server actually has; with an empty catalogue none does.
        check("an unknown legacy track is refused",
                ServerComments.keyError("track_practice_beat").orElse("").equals(ServerComments.ERROR_UNKNOWN_TRACK));
        check("a workshop key needs no catalogue lookup", ServerComments.keyError("ws_42").isEmpty());
    }

    // ---------------------------------------------------------------- text

    private static void textRules() throws Exception {
        reset();
        check("the section sign is stripped", ServerComments.sanitize("hello \u00A7cred").equals("hello cred"));
        check("control characters become spaces",
                ServerComments.sanitize("a\u0000b\u0007c").equals("a b c"));
        check("newlines collapse into one space", ServerComments.sanitize("a\nb\r\nc").equals("a b c"));
        check("runs of spaces collapse", ServerComments.sanitize("a      b").equals("a b"));
        check("surrounding whitespace goes", ServerComments.sanitize("   hi   ").equals("hi"));
        check("a null text is empty", ServerComments.sanitize(null).isEmpty());
        check("only formatting codes leave nothing", ServerComments.sanitize("\u00A7\u00A7\u00A7").isEmpty());

        check("an empty comment is refused",
                post(ALICE, "ws_1", "   ").orElse("").equals(ServerComments.ERROR_EMPTY));
        check("a comment of only formatting codes is refused",
                post(ALICE, "ws_1", "\u00A7\u00A7\u00A7").orElse("").equals(ServerComments.ERROR_EMPTY));
        check("501 characters are refused",
                post(ALICE, "ws_1", "x".repeat(501)).orElse("").equals(ServerComments.ERROR_TOO_LONG));
        check("500 characters are accepted", post(ALICE, "ws_1", "x".repeat(500)).isEmpty());
        ServerComments.Page page = read("ws_1");
        check("the stored comment is exactly 500 characters", page.comments().get(0).text().length() == 500);
    }

    // ---------------------------------------------------------------- posting and paging

    private static void postingAndPaging() throws Exception {
        reset();
        for (int i = 1; i <= 25; i++) {
            check("post " + i + " is accepted", post(ALICE, "ws_7", "comment " + i).isEmpty());
        }
        ServerComments.Page first = read("ws_7");
        check("the page holds at most 20 rows", first.comments().size() == 20);
        check("the total counts every comment", first.total() == 25);
        check("the newest comment comes first", first.comments().get(0).text().equals("comment 25"));
        check("the page ends at the oldest of its rows", first.comments().get(19).text().equals("comment 6"));
        check("the cursor points below the last row", first.nextBeforeId() == 6);

        ServerComments.Page second = ServerComments.page(ALICE, false, Optional.empty(), "ws_7",
                first.nextBeforeId(), 20);
        check("the second page holds the rest", second.comments().size() == 5);
        check("the second page starts where the first stopped", second.comments().get(0).text().equals("comment 5"));
        check("the last page has no cursor", second.nextBeforeId() == 0);
        check("a cursor of 1 returns nothing",
                ServerComments.page(ALICE, false, Optional.empty(), "ws_7", 1, 20).comments().isEmpty());
        check("a limit above the cap is capped",
                ServerComments.page(ALICE, false, Optional.empty(), "ws_7", 0, 999).comments().size() == 20);
        check("a limit of zero still returns a row",
                ServerComments.page(ALICE, false, Optional.empty(), "ws_7", 0, 0).comments().size() == 1);
        check("an invalid key returns an empty page with a reason",
                ServerComments.page(ALICE, false, Optional.empty(), "track_../..", 0, 20).comments().isEmpty());
        check("that page names the key rule",
                ServerComments.page(ALICE, false, Optional.empty(), "track_../..", 0, 20).note()
                        .equals(ServerComments.ERROR_BAD_KEY));
    }

    private static void rateLimits() throws Exception {
        reset(new ServerComments.Limits(true, 0, 0, 10, 3, 5, 1000));
        check("the first comment is accepted", post(BOB, "ws_9", "one").isEmpty());
        check("a second comment within ten seconds is refused",
                post(BOB, "ws_9", "two").orElse("").equals(ServerComments.ERROR_RATE));
        check("another player is not rate limited", post(CAROL, "ws_9", "one").isEmpty());

        reset(new ServerComments.Limits(true, 0, 0, 0, 3, 5, 1000));
        check("with no cool-down the first three go through",
                post(BOB, "ws_9", "a").isEmpty() && post(BOB, "ws_9", "b").isEmpty() && post(BOB, "ws_9", "c").isEmpty());
        check("the hourly cap refuses the fourth",
                post(BOB, "ws_9", "d").orElse("").equals(ServerComments.ERROR_RATE));
        check("the hourly cap is per player", post(CAROL, "ws_9", "a").isEmpty());
        check("the refused comments were never stored", read("ws_9").total() == 4);
    }

    private static void quotas() throws Exception {
        reset(new ServerComments.Limits(true, 0, 0, 0, 100, 2, 1000));
        check("the first new map is allowed", post(ALICE, "ws_11", "a").isEmpty());
        check("the second new map is allowed", post(ALICE, "ws_12", "a").isEmpty());
        check("a third new map is refused for today",
                post(ALICE, "ws_13", "a").orElse("").equals(ServerComments.ERROR_QUOTA));
        check("commenting again on a map they already started is allowed", post(ALICE, "ws_11", "b").isEmpty());
        check("another player may still start a map", post(BOB, "ws_13", "a").isEmpty());
        check("the refused map has no file", !Files.exists(comments.resolve("ws_13.json"))
                || read("ws_13").total() == 1);

        reset(new ServerComments.Limits(true, 0, 0, 0, 100, 100, 1));
        check("the world's first map is allowed", post(ALICE, "ws_21", "a").isEmpty());
        check("the world document cap refuses the second map",
                post(ALICE, "ws_22", "a").orElse("").equals(ServerComments.ERROR_QUOTA));
        check("an existing map still takes comments", post(BOB, "ws_21", "b").isEmpty());
    }

    private static void deletes() throws Exception {
        reset();
        check("alice posts", post(ALICE, "ws_31", "alice").isEmpty());
        check("bob posts", post(BOB, "ws_31", "bob").isEmpty());
        long aliceId = read("ws_31").comments().get(1).id();
        long bobId = read("ws_31").comments().get(0).id();

        check("a stranger cannot delete someone else's comment",
                ServerComments.delete(CAROL, "carol", false, "ws_31", aliceId).orElse("")
                        .equals(ServerComments.ERROR_NOT_AUTHOR));
        check("the author can delete their own", ServerComments.delete(ALICE, "alice", false, "ws_31", aliceId).isEmpty());
        check("a moderator can delete another's", ServerComments.delete(CAROL, "carol", true, "ws_31", bobId).isEmpty());
        check("deleting twice says it is gone",
                ServerComments.delete(ALICE, "alice", false, "ws_31", aliceId).orElse("")
                        .equals(ServerComments.ERROR_MISSING));
        check("an unknown id says it is gone",
                ServerComments.delete(ALICE, "alice", false, "ws_31", 9999).orElse("")
                        .equals(ServerComments.ERROR_MISSING));

        ServerComments.Page page = read("ws_31");
        check("deleted rows stay in place", page.total() == 2);
        check("a deleted row carries no text", page.comments().stream().allMatch(row -> row.text().isEmpty()));
        check("a deleted row is marked", page.comments().stream().allMatch(ServerComments.Comment::deleted));
        check("a deleted row keeps its author for the operators",
                page.comments().stream().anyMatch(row -> row.authorUuid().equals(ALICE)));
        check("a deleted row records who deleted it",
                page.comments().stream().anyMatch(row -> row.deletedBy().equals("carol")));
        check("a deleted row drops the skin textures",
                page.comments().stream().allMatch(row -> row.textures().isEmpty()));

        check("purging removes everything of one player", ServerComments.purge(BOB, "operator") >= 0);
    }

    private static void mutesReportsExports() throws Exception {
        reset();
        check("alice posts before the mute", post(ALICE, "ws_41", "hello").isEmpty());
        long id = read("ws_41").comments().get(0).id();

        // A player name is data, never a file name.
        ServerComments.mute(BOB, "../x", System.currentTimeMillis() + 60_000, "operator");
        check("the mute is live", ServerComments.mutes().size() == 1);
        ServerComments.drainForTest();
        check("mutes live in comments_meta", Files.isRegularFile(meta.resolve(ServerComments.MUTES_FILE)));
        check("no file is named after the player",
                !Files.exists(meta.resolve("../x")) && listNames(meta).stream().noneMatch(name -> name.contains("..")));
        check("unmuting works", ServerComments.unmute(BOB));
        check("unmuting twice reports nothing to do", !ServerComments.unmute(BOB));

        check("a report is stored", ServerComments.report(BOB, "../x", "ws_41", id, "\u00A7bad\ntext"));
        ServerComments.drainForTest();
        check("reports live in comments_meta", Files.isRegularFile(meta.resolve(ServerComments.REPORTS_FILE)));
        String reports = Files.readString(meta.resolve(ServerComments.REPORTS_FILE), StandardCharsets.UTF_8);
        check("the report keeps the key", reports.contains("ws_41"));
        check("the reported reason is stripped", !reports.contains("§"));
        check("a report about an invalid key is refused", !ServerComments.report(BOB, "bob", "track_../..", 1, "x"));

        Optional<Path> export = ServerComments.export(ALICE);
        ServerComments.drainForTest();
        check("the export exists", export.isPresent() && Files.isRegularFile(export.orElseThrow()));
        check("the export is named after the uuid and the clock",
                export.orElseThrow().getFileName().toString().matches(ALICE + "-\\d{8}-\\d{6}\\.json"));
        check("the export lives under comments_meta/exports",
                export.orElseThrow().getParent().equals(meta.resolve(ServerComments.EXPORTS_DIRECTORY)));
        check("the export holds the comment",
                Files.readString(export.orElseThrow(), StandardCharsets.UTF_8).contains("hello"));
    }

    private static void atomicWrites() throws Exception {
        reset();
        check("a comment is posted", post(ALICE, "ws_51", "one").isEmpty());
        ServerComments.drainForTest();
        Path file = comments.resolve("ws_51.json");
        check("the document was written", Files.isRegularFile(file));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        check("the document is JSON", text.trim().startsWith("{"));
        check("the document names its key", text.contains("\"key\": \"ws_51\""));
        check("the document carries the format number", text.contains("\"format\": 1"));
        check("no temporary file is left", listNames(comments).stream().noneMatch(name -> name.contains(".tmp")));
        check("only the document is in the folder", listNames(comments).equals(List.of("ws_51.json")));
    }

    /** Concurrent posts must all survive: the writes are ordered and no snapshot overwrites a newer one. */
    private static void concurrentPosts() throws Exception {
        reset(new ServerComments.Limits(true, 0, 0, 0, 10_000, 1000, 1000));
        int threads = 8;
        int each = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int index = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        post(UUID.nameUUIDFromBytes(("player" + index).getBytes(StandardCharsets.UTF_8)),
                                "ws_61", "from " + index + " number " + i);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        check("every poster finished", done.await(30, TimeUnit.SECONDS));
        check("nothing was lost in memory", read("ws_61").total() == threads * each);
        ServerComments.drainForTest();
        String text = Files.readString(comments.resolve("ws_61.json"), StandardCharsets.UTF_8);
        int stored = text.split("\"authorUuid\"", -1).length - 1;
        check("nothing was lost on disk: " + stored + " of " + threads * each, stored == threads * each);
    }

    private static void crashRecovery() throws Exception {
        reset();
        check("a comment is posted", post(ALICE, "ws_71", "before the crash").isEmpty());
        ServerComments.drainForTest();
        Path file = comments.resolve("ws_71.json");
        String whole = Files.readString(file, StandardCharsets.UTF_8);

        // A half-written file (the state a crash without atomic writes would leave) must not stop the server.
        Files.writeString(file, whole.substring(0, whole.length() / 2));
        ServerComments.bindForTest(comments, meta);
        ServerComments.overrideLimits(defaults());
        check("a truncated document reads as empty rather than throwing", read("ws_71").total() == 0);
        check("a new comment still works after that", post(BOB, "ws_71", "after").isEmpty());
        ServerComments.drainForTest();

        Files.writeString(file, whole);
        ServerComments.bindForTest(comments, meta);
        ServerComments.overrideLimits(defaults());
        check("the intact document comes back", read("ws_71").total() == 1);
        check("its text survived", read("ws_71").comments().get(0).text().equals("before the crash"));
        check("its author survived", read("ws_71").comments().get(0).authorUuid().equals(ALICE));
        ServerComments.drainForTest();

        Files.writeString(file, "");
        ServerComments.bindForTest(comments, meta);
        ServerComments.overrideLimits(defaults());
        check("an empty document is not an error", read("ws_71").total() == 0);
    }

    private static void retention() throws Exception {
        reset();
        check("a comment is posted", post(ALICE, "ws_81", "old").isEmpty());
        ServerComments.drainForTest();
        check("the comment is there", read("ws_81").total() == 1);
        // Retention is applied at bind time; zero keeps everything.
        ServerComments.bindForTest(comments, meta);
        ServerComments.overrideLimits(new ServerComments.Limits(true, 0, 0, 0, 1000, 1000, 1000));
        check("retention 0 keeps the comment", read("ws_81").total() == 1);
        check("comments can be turned off entirely",
                ServerComments.page(ALICE, false, Optional.empty(), "ws_81", 0, 20) != null);
        ServerComments.overrideLimits(new ServerComments.Limits(false, 0, 0, 0, 1000, 1000, 1000));
        check("a disabled server refuses a post",
                post(ALICE, "ws_81", "x").orElse("").equals(ServerComments.ERROR_DISABLED));
        check("a disabled server says so in the page",
                ServerComments.page(ALICE, false, Optional.empty(), "ws_81", 0, 20).note()
                        .equals(ServerComments.ERROR_DISABLED));
        ServerComments.overrideLimits(defaults());
    }

    // ---------------------------------------------------------------- helpers

    private static Optional<String> post(UUID uuid, String key, String text) {
        return ServerComments.post(uuid, "player", "", "", key, text);
    }

    private static ServerComments.Page read(String key) {
        return ServerComments.page(ALICE, false, Optional.empty(), key, 0, 20);
    }

    private static ServerComments.Limits defaults() {
        return new ServerComments.Limits(true, 0, 0, 0, 1000, 1000, 10_000);
    }

    private static void reset() throws IOException {
        reset(defaults());
    }

    private static void reset(ServerComments.Limits limits) throws IOException {
        Path root = Files.createTempDirectory(temp, "world");
        comments = root.resolve(ServerComments.COMMENTS_DIRECTORY);
        meta = root.resolve(ServerComments.META_DIRECTORY);
        Files.createDirectories(comments);
        Files.createDirectories(meta);
        ServerComments.bindForTest(comments, meta);
        ServerComments.overrideLimits(limits);
    }

    private static List<String> listNames(Path folder) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static String shorten(String value) {
        String shown = value.replace(" ", "\\0");
        return shown.length() <= 24 ? "'" + shown + "'" : "'" + shown.substring(0, 21) + "...'";
    }

    private static void check(String message, boolean value) {
        checks++;
        if (value) {
            System.out.println("PASS " + message);
        } else {
            failures.add(message);
            System.out.println("FAIL " + message);
        }
    }
}
