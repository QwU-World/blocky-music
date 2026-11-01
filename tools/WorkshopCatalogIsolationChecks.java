import java.lang.reflect.Field;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.InstalledCheck;
import ru.gora.musicbattle.workshop.api.WorkshopApi;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopInbox;

/**
 * Installing Workshop sets never changes {@code MusicBattleTracks.availableTracks()}. Simulates
 * singleplayer in one JVM: the real {@code MusicBattleTracks} bound to a world folder (the integrated server's catalogue)
 * and the client-global {@code music_battle_tracks} under the same game directory, then installs sets the three ways the
 * client can (downloader against a fake workshop, inbox, dropped file) and reloads every catalogue.
 */
public final class WorkshopCatalogIsolationChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("blocky-workshop-isolation-");
        try {
            run(game);
        } finally {
            deleteTree(game);
        }
        System.out.println("Workshop catalog isolation checks passed: " + checks);
    }

    static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    static void run(Path game) throws Exception {
        FMLPaths.loadAbsolutePaths(game);
        Path tracksRoot = MusicBattleTracks.tracksRootDirectory();
        check(tracksRoot.startsWith(game.toAbsolutePath().normalize()), "tracks root lives in the fake game directory");
        // A legacy layout without the v2 marker: the one-time migration must not copy Workshop folders into my_tracks.
        Files.createDirectories(tracksRoot);
        writeTrack(tracksRoot.resolve(MusicBattleTracks.PERSONAL_DIRECTORY_NAME), "mine_local", "Local Track");

        Path worldStorage = game.resolve("saves").resolve("World").resolve(MusicBattleTracks.WORLD_STORAGE_DIRECTORY_NAME);
        writeTrack(worldStorage.resolve(MusicBattleTracks.SERVER_DIRECTORY_NAME), "server_song", "Server Song");
        writeTrack(worldStorage.resolve(MusicBattleTracks.USER_SERVER_DIRECTORY_NAME), "player_song", "Player Song");
        Field active = MusicBattleTracks.class.getDeclaredField("activeWorldStorageDirectory");
        active.setAccessible(true);
        active.set(null, worldStorage.toAbsolutePath().normalize());

        List<String> serverBefore = ids(MusicBattleTracks.reloadServerCatalog());
        List<String> localBefore = ids(MusicBattleTracks.loadTracks());
        check(serverBefore.equals(List.of("player_song", "server_song")), "singleplayer server catalogue before: " + serverBefore);
        check(localBefore.equals(List.of("mine_local")), "my_tracks before: " + localBefore);

        // 1. The downloader against a fake workshop.
        WorkshopCatalog catalog = new WorkshopCatalog(tracksRoot);
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            WorkshopFixtures.Built set = WorkshopFixtures.build(42, 1, "Downloaded");
            server.on("POST", "/api/v1/beatmapsets/42/download", r -> FakeWorkshopServer.Response.json(200,
                    "{\"schema\":1,\"url\":\"/files/packages/42/1.blockymap\",\"size\":" + set.bytes().length + ",\"packageSha256\":\"" + set.packageSha256()
                            + "\",\"contentSha256\":\"" + set.manifest().contentSha256() + "\",\"revision\":1,\"fileName\":\"x\",\"expiresAt\":\"x\"}"));
            server.on("GET", "/files/packages/42/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, set.bytes()));
            server.on("GET", "/api/v1/beatmapsets/42", r -> FakeWorkshopServer.Response.json(200,
                    WorkshopFixtures.detailJson(42, 1, set.manifest().contentSha256(), null)));
            WorkshopApi api = new WorkshopApi(WorkshopApi.Options.of(URI.create(server.base()), () -> Proxy.NO_PROXY, "BlockyMusic/test", () -> true));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                WorkshopDownloader downloader = new WorkshopDownloader(api, catalog, new InstalledCheck(api, catalog, System::currentTimeMillis),
                        pool, pool, System::currentTimeMillis);
                downloader.enqueue(42);
                long end = System.currentTimeMillis() + 15_000;
                while (!catalog.isInstalled(42)) {
                    if (System.currentTimeMillis() > end) {
                        throw new AssertionError("download did not install: " + downloader.status(42));
                    }
                    Thread.sleep(20);
                }
                // A set counts as installed before its card and preview arrive: those are two more round trips, and a
                // finished map should not wait on them to be playable. So the card is waited for here too, before the
                // pool is shut down - shutting it down at the INSTALLED instant interrupted the very worker writing
                // the card, and this check passed or failed on which thread got there first.
                Path card = WorkshopLayout.installedDirectory(tracksRoot).resolve("ws_42")
                        .resolve(WorkshopLayout.CARD_SIDECAR_FILE_NAME);
                while (!Files.isRegularFile(card) && System.currentTimeMillis() <= end) {
                    Thread.sleep(20);
                }
            } finally {
                pool.shutdownNow();
            }
        }
        check(Files.isRegularFile(WorkshopLayout.installedDirectory(tracksRoot).resolve("ws_42").resolve(WorkshopLayout.CARD_SIDECAR_FILE_NAME)),
                "downloaded set has its workshop-card.json sidecar");

        // 2. The inbox.
        WorkshopInbox inbox = new WorkshopInbox(tracksRoot, () -> System.currentTimeMillis() + 60_000);
        inbox.ensureDirectory();
        Path queued = WorkshopFixtures.write(WorkshopFixtures.build(43, 1, "From Inbox"), inbox.directory().resolve("inbox.blockymap"));
        inbox.scan();
        for (Path ready : inbox.scan()) {
            BeatmapPackage.install(ready, tracksRoot);
            inbox.consume(ready);
        }
        check(Files.notExists(queued), "inbox package consumed after install");

        // 3. A dropped file from anywhere on disk.
        Path dropped = WorkshopFixtures.write(WorkshopFixtures.build(44, 2, "Dropped"), game.resolve("Downloads").resolve("dropped.blockymap"));
        check(BeatmapPackage.install(dropped, tracksRoot).outcome() == BeatmapPackage.Outcome.INSTALLED, "dropped set installed");

        check(catalog.refresh() == 3 && catalog.isInstalled(42) && catalog.isInstalled(43) && catalog.isInstalled(44), "client catalog sees three sets");

        List<String> serverAfter = ids(MusicBattleTracks.reloadServerCatalog());
        List<String> available = ids(MusicBattleTracks.availableTracks());
        // Re-run the one-time legacy layout migration with Workshop folders present.
        Files.deleteIfExists(tracksRoot.resolve(".layout_v2_migrated"));
        List<String> localAfter = ids(MusicBattleTracks.loadTracks());
        check(Files.isRegularFile(tracksRoot.resolve(".layout_v2_migrated")), "legacy migration ran again");
        check(serverAfter.equals(serverBefore), "reloadServerCatalog unchanged after installs: " + serverAfter);
        check(available.equals(serverBefore), "availableTracks() unchanged after installs: " + available);
        check(localAfter.equals(localBefore), "my_tracks unchanged after installs (legacy migration skipped Workshop folders): " + localAfter);
        check(Stream.of(serverAfter, available, localAfter).flatMap(List::stream).noneMatch(id -> id.startsWith(WorkshopLayout.SET_FOLDER_PREFIX)),
                "no ws_ id in any server or local catalogue");
        check(MusicBattleTracks.byId("ws_42_hard").isEmpty() && MusicBattleTracks.byId("ws_44_hard").isEmpty(), "byId never finds installed sets");
        check(Files.notExists(tracksRoot.resolve(MusicBattleTracks.PERSONAL_DIRECTORY_NAME).resolve("workshop"))
                && Files.notExists(tracksRoot.resolve(MusicBattleTracks.PERSONAL_DIRECTORY_NAME).resolve("workshop_inbox")), "nothing copied into my_tracks");

        // The integrated server restarts (world reopened): still isolated.
        active.set(null, null);
        MusicBattleTracks.reloadServerCatalog();
        active.set(null, worldStorage.toAbsolutePath().normalize());
        check(ids(MusicBattleTracks.reloadServerCatalog()).equals(serverBefore), "catalogue after a world reload still excludes Workshop sets");
        check(catalog.delete(43) && !catalog.isInstalled(43) && ids(MusicBattleTracks.availableTracks()).equals(serverBefore), "deleting a set leaves the server catalogue alone");
    }

    static List<String> ids(List<MusicBattleTracks.Track> tracks) {
        return tracks.stream().map(MusicBattleTracks.Track::id).sorted().toList();
    }

    static void writeTrack(Path directory, String id, String title) throws Exception {
        Files.createDirectories(directory);
        String json = "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"bpm\":120,\"playerPartyOrderVersion\":2,"
                + "\"notes\":[{\"owner\":0,\"lane\":1,\"tick\":20,\"durationTicks\":0}],\"effects\":[]}";
        Files.writeString(directory.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    static void deleteTree(Path root) throws Exception {
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
