import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.WorkshopInbox;
import ru.gora.musicbattle.workshop.api.WorkshopRequest;

/**
 * The install-request half of {@code music_battle_tracks/workshop_inbox/}: the tiny
 * {@code <setId>.blockymap-request} files the QwU Workshop Helper writes when the player clicks "Install in game" on
 * the site or opens a {@code blockymusic://install/<id>} link.
 *
 * <p>The request file crosses a trust boundary: it is written by another program, from a link a web page handed to the
 * operating system. So the checks below are mostly about refusing things — a wrong version, a set id that is not one,
 * a file that is really a 4 KiB blob, a request renamed to point at another set, a request from last month.
 *
 * <p>Pure {@code java.nio} plus Gson; no Minecraft classes on the classpath on purpose. What it does not prove: the
 * download that follows (that is {@code workshop-api}), the toasts, and the helper itself (its own tests live with
 * the Workshop service's source, which is not public).
 */
public final class WorkshopInboxChecks {
    private static int checks;

    /** Byte for byte what the Workshop helper (its source is not public) writes. If the helper changes, this string changes with it. */
    private static final String HELPER_OUTPUT = "{\"v\":1,\"setId\":42,\"source\":\"helper\",\"at\":\"2026-09-18T12:00:00Z\"}\n";

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("blocky-workshop-inbox-");
        try {
            parsing();
            fileNames();
            reading(temp.resolve("reading"));
            scanning(temp.resolve("scanning"));
            rejecting(temp.resolve("rejecting"));
        } finally {
            deleteTree(temp);
        }
        System.out.println("Workshop inbox checks passed: " + checks);
    }

    // ---------------------------------------------------------------- the JSON the helper writes

    private static void parsing() throws Exception {
        WorkshopRequest request = WorkshopRequest.parse(HELPER_OUTPUT);
        check(request.setId() == 42, "the helper's own output parses");
        check("helper".equals(request.source()), "source is read");
        check("2026-09-18T12:00:00Z".equals(request.at()), "at is read");

        check(WorkshopRequest.parse("{\"v\":1,\"setId\":\"42\"}").setId() == 42, "a quoted set id is accepted too");
        check(WorkshopRequest.parse("{\"v\":1,\"setId\":42,\"extra\":{\"a\":[1,2]}}").setId() == 42, "unknown fields are ignored");
        // 19 digits: past double precision. Gson keeps the digits, so the id must survive exactly.
        check(WorkshopRequest.parse("{\"v\":1,\"setId\":9007199254740993}").setId() == 9007199254740993L,
                "a large set id keeps every digit");

        malformed("", WorkshopRequest.Reason.MALFORMED);
        malformed("   ", WorkshopRequest.Reason.MALFORMED);
        malformed("not json", WorkshopRequest.Reason.MALFORMED);
        malformed("[{\"v\":1,\"setId\":42}]", WorkshopRequest.Reason.MALFORMED);
        malformed("\"just a string\"", WorkshopRequest.Reason.MALFORMED);
        malformed("{\"v\":2,\"setId\":42}", WorkshopRequest.Reason.UNSUPPORTED_VERSION);
        malformed("{\"setId\":42}", WorkshopRequest.Reason.UNSUPPORTED_VERSION);
        malformed("{\"v\":\"1\",\"setId\":42}", WorkshopRequest.Reason.UNSUPPORTED_VERSION);
        for (String id : new String[]{"0", "-1", "\"01\"", "\"\"", "\" 42\"", "\"42 \"", "\"4 2\"", "\"42; rm -rf /\"",
                "\"../../etc/passwd\"", "1.5", "true", "null", "[42]", "{\"a\":1}", "99999999999999999999",
                "\"9999999999999999999999\""}) {
            malformed("{\"v\":1,\"setId\":" + id + "}", WorkshopRequest.Reason.BAD_SET_ID);
        }
        malformed("{\"v\":1}", WorkshopRequest.Reason.BAD_SET_ID);
    }

    private static void malformed(String json, WorkshopRequest.Reason expected) {
        checks++;
        try {
            WorkshopRequest request = WorkshopRequest.parse(json);
            throw new AssertionError("accepted " + json + " as set " + request.setId());
        } catch (WorkshopRequest.InvalidException exception) {
            if (exception.reason() != expected) {
                throw new AssertionError(json + " was refused as " + exception.reason() + ", expected " + expected);
            }
        }
    }

    // ---------------------------------------------------------------- file names

    private static void fileNames() {
        check(WorkshopRequest.isRequestFile(Path.of("42" + WorkshopLayout.REQUEST_EXTENSION)), "a request file is recognised");
        check(WorkshopRequest.isRequestFile(Path.of("42.BLOCKYMAP-REQUEST")), "the extension is case-insensitive");
        check(!WorkshopRequest.isRequestFile(Path.of("42" + WorkshopLayout.PACKAGE_EXTENSION)), "a package is not a request");
        check(!WorkshopRequest.isRequestFile(Path.of(".42" + WorkshopLayout.REQUEST_EXTENSION)), "a hidden file is skipped");
        check(!WorkshopRequest.isRequestFile(Path.of("notes.txt")), "an unrelated file is not a request");
        check(!WorkshopRequest.isRequestFile(null), "null is not a request");

        check(WorkshopRequest.setIdFromFileName(Path.of("42" + WorkshopLayout.REQUEST_EXTENSION)) == 42, "the id comes out of the name");
        check(WorkshopRequest.setIdFromFileName(Path.of("007" + WorkshopLayout.REQUEST_EXTENSION)) == 0, "a padded name gives no id");
        check(WorkshopRequest.setIdFromFileName(Path.of("map" + WorkshopLayout.REQUEST_EXTENSION)) == 0, "a word gives no id");
        check(WorkshopRequest.setIdFromFileName(Path.of("42" + WorkshopLayout.PACKAGE_EXTENSION)) == 0, "a package gives no id");
        // The mod's package scan must never see a request: the extensions do not overlap.
        check(!("42" + WorkshopLayout.REQUEST_EXTENSION).endsWith(WorkshopLayout.PACKAGE_EXTENSION),
                "a request never looks like a package to the package scan");
    }

    // ---------------------------------------------------------------- reading a file from disk

    private static void reading(Path root) throws Exception {
        Files.createDirectories(root);
        long now = System.currentTimeMillis();

        Path good = write(root.resolve("42" + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        check(WorkshopRequest.read(good, now).setId() == 42, "a request on disk is read");

        // A request renamed to another set must not install that other set.
        Path renamed = write(root.resolve("7" + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        refused(renamed, now, WorkshopRequest.Reason.BAD_SET_ID, "a renamed request is refused");

        // A name that is not an id at all leaves the body in charge.
        Path oddName = write(root.resolve("map" + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        check(WorkshopRequest.read(oddName, now).setId() == 42, "a request with a free-form name still reads its body");

        Path huge = root.resolve("99" + WorkshopLayout.REQUEST_EXTENSION);
        Files.write(huge, new byte[(int) WorkshopRequest.MAX_BYTES + 1]);
        refused(huge, now, WorkshopRequest.Reason.TOO_LARGE, "an oversized request is refused before it is parsed");

        Path stale = write(root.resolve("43" + WorkshopLayout.REQUEST_EXTENSION),
                HELPER_OUTPUT.replace("\"setId\":42", "\"setId\":43"));
        Files.setLastModifiedTime(stale, FileTime.from(Instant.ofEpochMilli(now - WorkshopRequest.MAX_AGE_MS - 1000)));
        refused(stale, now, WorkshopRequest.Reason.STALE, "a request from last month is refused");

        Path folder = root.resolve("44" + WorkshopLayout.REQUEST_EXTENSION);
        Files.createDirectories(folder);
        refused(folder, now, WorkshopRequest.Reason.NOT_A_FILE, "a directory named like a request is refused");

        refused(root.resolve("missing" + WorkshopLayout.REQUEST_EXTENSION), now, WorkshopRequest.Reason.NOT_A_FILE,
                "a missing file is refused");

        Path link = root.resolve("45" + WorkshopLayout.REQUEST_EXTENSION);
        try {
            Files.createSymbolicLink(link, good);
            refused(link, now, WorkshopRequest.Reason.NOT_A_FILE, "a symbolic link is refused");
        } catch (IOException | UnsupportedOperationException unsupported) {
            System.out.println("SKIP symbolic link check: this file system or account cannot create links");
        }
    }

    private static void refused(Path file, long now, WorkshopRequest.Reason expected, String label) {
        checks++;
        try {
            WorkshopRequest request = WorkshopRequest.read(file, now);
            throw new AssertionError(label + ": accepted as set " + request.setId());
        } catch (WorkshopRequest.InvalidException exception) {
            if (exception.reason() != expected) {
                throw new AssertionError(label + ": refused as " + exception.reason() + ", expected " + expected);
            }
        } catch (IOException exception) {
            throw new AssertionError(label + ": " + exception);
        }
    }

    // ---------------------------------------------------------------- the inbox scan

    private static void scanning(Path tracks) throws Exception {
        WorkshopInbox inbox = new WorkshopInbox(tracks, System::currentTimeMillis);
        inbox.ensureDirectory();
        check(inbox.scanRequests().isEmpty(), "an empty inbox offers no requests");

        Path first = write(inbox.directory().resolve("42" + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        Files.setLastModifiedTime(first, FileTime.from(Instant.ofEpochMilli(1_000_000)));
        Path second = write(inbox.directory().resolve("43" + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        Files.setLastModifiedTime(second, FileTime.from(Instant.ofEpochMilli(2_000_000)));

        // No settle wait: the helper writes with a temporary file and a rename, so a request is whole the moment it
        // appears, and a click on the site should not have to wait for two scans.
        List<Path> ready = inbox.scanRequests();
        check(ready.equals(List.of(first, second)), "requests are offered at once, oldest first");

        // Things that are in the folder but are not requests.
        Files.writeString(inbox.directory().resolve("map" + WorkshopLayout.PACKAGE_EXTENSION), "PK not really");
        Files.writeString(inbox.directory().resolve(".hidden" + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        Files.writeString(inbox.directory().resolve("notes.txt"), "hello");
        Files.createDirectories(inbox.directory().resolve("50" + WorkshopLayout.REQUEST_EXTENSION));
        check(inbox.scanRequests().equals(List.of(first, second)), "packages, hidden files, text and folders are skipped");
        check(inbox.scan().isEmpty() || !inbox.scan().stream().anyMatch(WorkshopRequest::isRequestFile),
                "the package scan never returns a request");

        for (int i = 0; i < WorkshopInbox.MAX_REQUESTS_PER_SCAN + 5; i++) {
            write(inbox.directory().resolve((100 + i) + WorkshopLayout.REQUEST_EXTENSION), HELPER_OUTPUT);
        }
        check(inbox.scanRequests().size() == WorkshopInbox.MAX_REQUESTS_PER_SCAN,
                "a flooded inbox is capped at " + WorkshopInbox.MAX_REQUESTS_PER_SCAN + " requests per scan");

        inbox.consume(first);
        check(Files.notExists(first), "a handled request is deleted");
        try {
            inbox.consume(tracks.resolve("elsewhere" + WorkshopLayout.REQUEST_EXTENSION));
            throw new AssertionError("consumed a request outside the inbox");
        } catch (IOException expected) {
            checks++;
        }
    }

    // ---------------------------------------------------------------- rejected/

    private static void rejecting(Path tracks) throws Exception {
        WorkshopInbox inbox = new WorkshopInbox(tracks, () -> 1_700_000_000_000L);
        inbox.ensureDirectory();
        Path request = write(inbox.directory().resolve("42" + WorkshopLayout.REQUEST_EXTENSION), "{\"v\":9}");
        Path rejected = inbox.reject(request, WorkshopRequest.Reason.UNSUPPORTED_VERSION.code(), "v=9, this mod reads v=1");
        check(Files.notExists(request, LinkOption.NOFOLLOW_LINKS), "the request left the inbox");
        check(rejected.getParent().equals(inbox.rejectedDirectory()), "it landed in rejected/");
        check(String.valueOf(rejected.getFileName()).equals("42" + WorkshopLayout.REQUEST_EXTENSION), "under its own name");
        String reason = Files.readString(rejected.resolveSibling(rejected.getFileName() + ".txt"));
        check(reason.contains("request_unsupported_version") && reason.contains("v=9"), "with a reason a player can read");

        Path again = write(inbox.directory().resolve("42" + WorkshopLayout.REQUEST_EXTENSION), "{\"v\":9}");
        Path second = inbox.reject(again, WorkshopRequest.Reason.UNSUPPORTED_VERSION.code(), null);
        check(!second.equals(rejected) && Files.exists(rejected), "a second rejection never overwrites the first");
        check(String.valueOf(second.getFileName()).equals("42 (2)" + WorkshopLayout.REQUEST_EXTENSION),
                "the copy number goes before the extension, not after it");

        // Every reason has a code, and codes are the shape the lang files use.
        for (WorkshopRequest.Reason value : WorkshopRequest.Reason.values()) {
            check(value.code() != null && value.code().matches("[a-z0-9_]+") && value.code().startsWith("request_"),
                    value + " has a usable error code");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
