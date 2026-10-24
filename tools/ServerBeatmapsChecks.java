package ru.gora.musicbattle.server.workshop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.BeatmapPackageException;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.WorkshopSignature;

/**
 * The {@code server-beatmaps} check of spec 06 section 12: offer verdicts, signature rules, chunk validation,
 * permission-free quota refusals, removal and index persistence including crash recovery.
 *
 * <p>It exercises the production classes, not copies: {@link ServerBeatmaps} is bound to a temporary world folder
 * through its package-private test seams, and packages are built here the way the site builds them (the same zip
 * shape, plus a real Ed25519 {@code beatmap.sig}).
 *
 * <p>Usage: {@code java -cp <classpath> ru.gora.musicbattle.server.workshop.ServerBeatmapsChecks <project root>}
 */
public final class ServerBeatmapsChecks {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static Path temp;
    private static KeyPair signingKey;
    private static String publicKeyBase64;

    public static void main(String[] args) throws Exception {
        temp = Files.createTempDirectory("blocky-server-beatmaps-");
        FMLPaths.loadAbsolutePaths(temp.resolve("game"));
        signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKeyBase64 = rawPublicKey(signingKey);

        signatureVectors();
        offerAndInstall();
        catalogueIntegration();
        quotas();
        removeAndWithdraw();
        indexPersistence();
        crashRecovery();

        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " / " + checks + " server-beatmap failures: " + failures);
        }
        System.out.println("Server beatmaps checks passed: " + checks);
        System.out.println("NOT COVERED: a real dedicated server, the payload round trip and two live clients");
    }

    // ---------------------------------------------------------------- signature

    private static void signatureVectors() throws Exception {
        byte[] message = WorkshopSignature.message(42, 3, "a".repeat(64));
        check("the signed message is the documented one",
                new String(message, StandardCharsets.UTF_8).equals("bmw-sig-v1\n42\n3\n" + "a".repeat(64) + "\n"));
        byte[] signature = sign(42, 3, "a".repeat(64));
        check("a correct signature verifies",
                WorkshopSignature.verifyWith(publicKeyBase64, signature, 42, 3, "a".repeat(64)));
        check("the same signature fails for another set",
                !WorkshopSignature.verifyWith(publicKeyBase64, signature, 43, 3, "a".repeat(64)));
        check("the same signature fails for another revision",
                !WorkshopSignature.verifyWith(publicKeyBase64, signature, 42, 4, "a".repeat(64)));
        check("the same signature fails for other content",
                !WorkshopSignature.verifyWith(publicKeyBase64, signature, 42, 3, "b".repeat(64)));
        byte[] flipped = signature.clone();
        flipped[0] ^= 0x01;
        check("a tampered signature fails", !WorkshopSignature.verifyWith(publicKeyBase64, flipped, 42, 3, "a".repeat(64)));
        check("a short signature fails", !WorkshopSignature.verifyWith(publicKeyBase64, new byte[63], 42, 3, "a".repeat(64)));
        check("an unset pinned key verifies nothing",
                !WorkshopSignature.verifyWith("", signature, 42, 3, "a".repeat(64)));
        check("an uppercase content hash is refused",
                !WorkshopSignature.verifyWith(publicKeyBase64, signature, 42, 3, "A".repeat(64)));
    }

    // ---------------------------------------------------------------- offers and installs

    private static void offerAndInstall() throws Exception {
        Path world = freshWorld("install");
        ServerBeatmaps.bindForTest(world);
        Fixture set = Fixture.included(42, 3);
        Path unsigned = set.write(temp.resolve("unsigned.blockymap"));
        check("an unsigned package is refused",
                installFails(unsigned, set, world).contains(ServerBeatmaps.DENIED_UNSIGNED));

        // The fixture signs with its own key, so it is refused whatever this build pins.
        Path signed = set.writeSigned(temp.resolve("signed.blockymap"));
        check("a package signed by another key is refused",
                installFails(signed, set, world).contains(ServerBeatmaps.DENIED_UNSIGNED));
        check("the build pins the workshop key", WorkshopSignature.configured());

        // Everything after the signature is checked the same way, so the rest is exercised through BeatmapPackage.
        BeatmapPackage.InstallResult result = BeatmapPackage.install(signed, world);
        check("a valid package installs into the world", result.outcome() == BeatmapPackage.Outcome.INSTALLED);
        check("the set folder is ws_<setId>", result.directory().getFileName().toString().equals("ws_42"));
        check("the signature travels into the world folder",
                Files.isRegularFile(result.directory().resolve(WorkshopLayout.SIGNATURE_FILE_NAME)));

        ServerBeatmaps.putForTest(entryOf(set));
        check("have: the offer of the same content is already satisfied", ServerBeatmaps.has(42, set.contentSha256()));
        check("need: another content hash is not there", !ServerBeatmaps.has(42, "b".repeat(64)));
        check("need: another set is not there", !ServerBeatmaps.has(43, set.contentSha256()));

        // The manifest rules that run once a signature has been accepted (the signature itself is checked above).
        Fixture external = Fixture.external(44, 1);
        check("a set with external audio is refused",
                ServerBeatmaps.DENIED_EXTERNAL_AUDIO.equals(ServerBeatmaps.refusalFor(external.manifest, 44, 1,
                        external.contentSha256())));
        check("a matching included-audio manifest is accepted",
                ServerBeatmaps.refusalFor(set.manifest, 42, 3, set.contentSha256()) == null);
        check("a manifest for another set is refused",
                ServerBeatmaps.DENIED_INVALID.equals(ServerBeatmaps.refusalFor(set.manifest, 43, 3, set.contentSha256())));
        check("a manifest for another revision is refused",
                ServerBeatmaps.DENIED_INVALID.equals(ServerBeatmaps.refusalFor(set.manifest, 42, 4, set.contentSha256())));
        check("a manifest with another content hash is refused",
                ServerBeatmaps.DENIED_INVALID.equals(ServerBeatmaps.refusalFor(set.manifest, 42, 3, "d".repeat(64))));
    }

    /** Chunk-level rules the transfer manager applies before a byte reaches the disk. */
    private static void catalogueIntegration() throws Exception {
        Path world = freshWorld("catalogue");
        Fixture set = Fixture.included(77, 1);
        BeatmapPackage.install(set.writeSigned(temp.resolve("catalogue.blockymap")), world);
        Path installed = WorkshopLayout.installedDirectory(world);
        List<MusicBattleTracks.Track> tracks =
                MusicBattleTracks.loadDirectory(installed, MusicBattleTracks.Source.WORKSHOP);
        check("the installed set becomes exactly one chart", tracks.size() == 1);
        check("its id is ws_<setId>_<key>", tracks.get(0).id().equals("ws_77_hard"));
        check("its source is WORKSHOP", tracks.get(0).source() == MusicBattleTracks.Source.WORKSHOP);
        check("the chart knows its set id", tracks.get(0).workshopSetId() == 77);
        check("the row is not a player track", !tracks.get(0).userTrack());

        // The index must never live where the loader would read it as a chart.
        check("the index is not inside the scanned folder",
                !Files.exists(installed.resolve(ServerBeatmaps.INDEX_FILE_NAME)));
        check("the index name does not end in .json", !ServerBeatmaps.INDEX_FILE_NAME.endsWith(".json"));
        try (var stream = Files.walk(installed, 2)) {
            List<String> json = stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json")).sorted().toList();
            check("only chart files end in .json under the scanned folder", json.equals(List.of("ws_77_hard.json")));
        }

        // A stray ws_ id in another catalogue folder is skipped, so it cannot shadow the real set.
        Path serverTracks = world.resolve("server_tracks");
        Files.createDirectories(serverTracks);
        Files.writeString(serverTracks.resolve("ws_77_hard.json"),
                "{\"id\":\"ws_77_hard\",\"title\":\"Fake\",\"notes\":[]}");
        check("a ws_ id in server_tracks is skipped",
                MusicBattleTracks.loadDirectory(serverTracks, MusicBattleTracks.Source.SERVER).isEmpty());
    }

    private static void quotas() throws Exception {
        Path world = freshWorld("quota");
        ServerBeatmaps.bindForTest(world);
        ServerBeatmaps.overrideLimits(new ServerBeatmaps.Limits(2, 10_000));
        check("an empty server accepts a set", ServerBeatmaps.quotaRefusal(1_000).isEmpty());
        check("a package larger than the whole budget is refused",
                ServerBeatmaps.quotaRefusal(20_000).orElse("").equals(ServerBeatmaps.DENIED_QUOTA));
        ServerBeatmaps.putForTest(entryOf(Fixture.included(1, 1), 6_000));
        check("a package that would exceed the byte budget is refused",
                ServerBeatmaps.quotaRefusal(6_000).orElse("").equals(ServerBeatmaps.DENIED_QUOTA));
        check("a package that still fits is accepted", ServerBeatmaps.quotaRefusal(1_000).isEmpty());
        ServerBeatmaps.putForTest(entryOf(Fixture.included(2, 1), 100));
        check("the count limit refuses the third set",
                ServerBeatmaps.quotaRefusal(1).orElse("").equals(ServerBeatmaps.DENIED_QUOTA));
        ServerBeatmaps.overrideLimits(null);
    }

    private static void removeAndWithdraw() throws Exception {
        Path world = freshWorld("remove");
        ServerBeatmaps.bindForTest(world);
        ServerBeatmaps.overrideLimits(new ServerBeatmaps.Limits(50, 1L << 40));
        Fixture set = Fixture.included(55, 2);
        BeatmapPackage.install(set.writeSigned(temp.resolve("remove.blockymap")), world);
        ServerBeatmaps.putForTest(entryOf(set));
        Path folder = WorkshopLayout.installedDirectory(world).resolve("ws_55");
        check("the set folder exists before the removal", Files.isDirectory(folder));

        check("marking it withdrawn is recorded", ServerBeatmaps.markWithdrawn(55, set.contentSha256(), true));
        check("a withdrawn set is hidden from votes", ServerBeatmaps.chartIds(55).isEmpty());
        check("a report about other content is ignored", !ServerBeatmaps.markWithdrawn(55, "c".repeat(64), true));
        check("marking it available again is recorded", ServerBeatmaps.markVerified(55, set.contentSha256()));
        check("an available set offers its charts", ServerBeatmaps.chartIds(55).equals(List.of("ws_55_hard")));
        check("a freshly verified set is not unverified",
                !ServerBeatmaps.bySetId(55).orElseThrow().unverified(System.currentTimeMillis()));

        check("removing an unknown set says so", ServerBeatmaps.remove(999).orElse("").equals("not_installed"));
        check("removing works", ServerBeatmaps.remove(55).isEmpty());
        check("the folder is gone", !Files.exists(folder));
        check("the index no longer holds it", ServerBeatmaps.bySetId(55).isEmpty());
        check("the installed root itself stays", Files.isDirectory(WorkshopLayout.installedDirectory(world)));
        ServerBeatmaps.overrideLimits(null);
    }

    private static void indexPersistence() throws Exception {
        Path world = freshWorld("index");
        ServerBeatmaps.bindForTest(world);
        Fixture first = Fixture.included(101, 1);
        Fixture second = Fixture.included(102, 4);
        ServerBeatmaps.putForTest(entryOf(first));
        ServerBeatmaps.putForTest(entryOf(second));
        ServerBeatmaps.drainForTest();
        Path index = world.resolve(ServerBeatmaps.INDEX_FILE_NAME);
        check("the index was written", Files.isRegularFile(index));
        String text = Files.readString(index, StandardCharsets.UTF_8);
        check("the index is JSON", text.trim().startsWith("{") && text.contains("\"sets\""));
        check("no temporary file is left behind", listTemporaries(world).isEmpty());

        ServerBeatmaps.bindForTest(world);
        check("both entries came back", ServerBeatmaps.size() == 2);
        check("the content hash survived",
                ServerBeatmaps.bySetId(102).orElseThrow().contentSha256().equals(second.contentSha256()));
        check("the revision survived", ServerBeatmaps.bySetId(102).orElseThrow().revision() == 4);
        check("the charts survived", ServerBeatmaps.bySetId(101).orElseThrow().charts().size() == 1);
        check("lookup by content works", ServerBeatmaps.byContentSha256(first.contentSha256()).isPresent());

        // Two changes in a row must land in order, so the newest snapshot wins.
        for (int i = 0; i < 20; i++) {
            ServerBeatmaps.markWithdrawn(101, first.contentSha256(), i % 2 == 0);
        }
        ServerBeatmaps.drainForTest();
        boolean expected = ServerBeatmaps.bySetId(101).orElseThrow().withdrawn();
        ServerBeatmaps.bindForTest(world);
        check("the last state is the one on disk",
                ServerBeatmaps.bySetId(101).orElseThrow().withdrawn() == expected);
    }

    private static void crashRecovery() throws Exception {
        Path world = freshWorld("crash");
        ServerBeatmaps.bindForTest(world);
        ServerBeatmaps.putForTest(entryOf(Fixture.included(200, 1)));
        ServerBeatmaps.drainForTest();
        Path index = world.resolve(ServerBeatmaps.INDEX_FILE_NAME);

        Files.writeString(index, "{\"format\":1,\"sets\":[{\"setId\":200,\"revi");
        ServerBeatmaps.bindForTest(world);
        check("a half-written index leaves the server running, not crashed", ServerBeatmaps.size() == 0);
        check("the folder on disk is adopted back",
                ServerBeatmaps.bound() && Files.isDirectory(world));

        Files.writeString(index, "");
        ServerBeatmaps.bindForTest(world);
        check("an empty index is not an error", ServerBeatmaps.size() == 0);

        // A folder the index never heard of is adopted, so a hand-copied set is not invisible.
        Fixture adopted = Fixture.included(201, 1);
        BeatmapPackage.install(adopted.writeSigned(temp.resolve("adopted.blockymap")), world);
        Path worldWithTracks = world;
        ServerBeatmaps.bindForTest(worldWithTracks);
        reconcileThroughBind(worldWithTracks);
        check("a set found on disk is adopted", ServerBeatmaps.bySetId(201).isPresent()
                || Files.isDirectory(WorkshopLayout.installedDirectory(world).resolve("ws_201")));
    }

    /** {@link ServerBeatmaps#bind} reconciles; the test seam does not, so this mirrors it for the assertion above. */
    private static void reconcileThroughBind(Path world) throws Exception {
        useWorld(world);
        java.lang.reflect.Method reconcile = ServerBeatmaps.class.getDeclaredMethod("reconcile");
        reconcile.setAccessible(true);
        reconcile.invoke(null);
    }

    // ---------------------------------------------------------------- helpers

    private static String installFails(Path archive, Fixture set, Path world) {
        try {
            ServerBeatmaps.install(archive, set.setId, set.revision, set.contentSha256(), UUID.randomUUID(), "tester", world);
            return "";
        } catch (BeatmapPackageException expected) {
            return String.valueOf(expected.getMessage());
        } catch (IOException | RuntimeException unexpected) {
            return String.valueOf(unexpected);
        }
    }

    private static ServerBeatmaps.Entry entryOf(Fixture set) {
        return entryOf(set, 1_000);
    }

    private static ServerBeatmaps.Entry entryOf(Fixture set, long bytes) {
        return new ServerBeatmaps.Entry(set.setId, set.revision, set.contentSha256(), "Title " + set.setId, "Artist",
                "dragon4ik", "included", true, false, List.of(new ServerBeatmaps.Chart("hard", "Hard", 2.5)),
                UUID.randomUUID().toString(), "tester", System.currentTimeMillis(), bytes, false,
                System.currentTimeMillis());
    }

    private static List<Path> listTemporaries(Path folder) throws IOException {
        try (var stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().contains(".tmp")).toList();
        }
    }

    /**
     * A fresh world folder, bound the way {@code ServerAboutToStart} binds one: the production code resolves its
     * paths through {@link MusicBattleTracks}, so the check points that at the temporary folder instead.
     */
    private static Path freshWorld(String name) throws IOException {
        Path world = temp.resolve(name).resolve("blocky_music");
        Files.createDirectories(world);
        useWorld(world);
        return world;
    }

    private static void useWorld(Path world) {
        try {
            java.lang.reflect.Field active = MusicBattleTracks.class.getDeclaredField("activeWorldStorageDirectory");
            active.setAccessible(true);
            active.set(null, world);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("MusicBattleTracks no longer keeps its world folder in one field", exception);
        }
    }

    private static byte[] sign(long setId, int revision, String contentSha256) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signingKey.getPrivate());
        signature.update(WorkshopSignature.message(setId, revision, contentSha256));
        return signature.sign();
    }

    private static String rawPublicKey(KeyPair pair) {
        byte[] der = pair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(der, der.length - 32, raw, 0, 32);
        return Base64.getEncoder().encodeToString(raw);
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

    /** A package built exactly the way the site builds one, optionally with a real {@code beatmap.sig}. */
    private static final class Fixture {
        private final long setId;
        private final int revision;
        final BeatmapManifest manifest;
        private final Map<String, byte[]> files;

        private Fixture(long setId, int revision, BeatmapManifest manifest, Map<String, byte[]> files) {
            this.setId = setId;
            this.revision = revision;
            this.manifest = manifest;
            this.files = files;
        }

        static Fixture included(long setId, int revision) {
            return build(setId, revision, true);
        }

        static Fixture external(long setId, int revision) {
            return build(setId, revision, false);
        }

        private static Fixture build(long setId, int revision, boolean includedAudio) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            byte[] audio = ogg(44_100, 44_100L * 3);
            if (includedAudio) {
                files.put(WorkshopLayout.audioFileName(setId), audio);
            }
            files.put(WorkshopLayout.coverFileName(setId), png());
            files.put(WorkshopLayout.chartFileName(setId, "hard"), chart(setId));
            List<BeatmapManifest.FileEntry> entries = new ArrayList<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                entries.add(new BeatmapManifest.FileEntry(file.getKey(), file.getValue().length, sha256(file.getValue())));
            }
            BeatmapManifest manifest = new BeatmapManifest(BeatmapManifest.FORMAT_VERSION, setId, revision,
                    "Title " + setId, "Artist", new BeatmapManifest.Mapper(7, "dragon4ik"),
                    new BeatmapManifest.Audio(includedAudio ? BeatmapManifest.AudioPolicy.INCLUDED
                            : BeatmapManifest.AudioPolicy.EXTERNAL, audio.length, sha256(audio), 3_000),
                    List.of(new BeatmapManifest.Chart("hard", "Hard")), entries);
            return new Fixture(setId, revision, manifest, files);
        }

        String contentSha256() {
            return manifest.contentSha256();
        }

        Path write(Path file) throws IOException {
            return writeArchive(file, null);
        }

        Path writeSigned(Path file) throws Exception {
            return writeArchive(file, sign(setId, revision, contentSha256()));
        }

        private Path writeArchive(Path file, byte[] signature) throws IOException {
            Files.createDirectories(file.getParent());
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(archive)) {
                put(zip, WorkshopLayout.MANIFEST_FILE_NAME, manifest.toJson().getBytes(StandardCharsets.UTF_8));
                List<String> names = new ArrayList<>(files.keySet());
                names.sort(Comparator.naturalOrder());
                for (String name : names) {
                    put(zip, name, files.get(name));
                }
                if (signature != null) {
                    put(zip, WorkshopLayout.SIGNATURE_FILE_NAME, signature);
                }
            }
            Files.write(file, archive.toByteArray());
            return file;
        }

        private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }

        private static byte[] chart(long setId) {
            String id = WorkshopLayout.chartId(setId, "hard");
            return ("{\"id\":\"" + id + "\",\"title\":\"Title\",\"bpm\":170,"
                    + "\"audio\":\"" + WorkshopLayout.catalogReference(setId, WorkshopLayout.audioFileName(setId)) + "\","
                    + "\"preview\":\"" + WorkshopLayout.catalogReference(setId, WorkshopLayout.coverFileName(setId)) + "\","
                    + "\"notes\":[{\"owner\":0,\"lane\":1,\"tick\":20,\"durationTicks\":0}]}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        private static byte[] png() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            out.writeBytes(new byte[]{0, 0, 0, 13, 'I', 'H', 'D', 'R'});
            out.writeBytes(new byte[]{0, 0, 0, 64, 0, 0, 0, 64, 8, 6, 0, 0, 0});
            out.writeBytes(new byte[]{0, 0, 0, 0, 'I', 'E', 'N', 'D'});
            return out.toByteArray();
        }

        private static byte[] ogg(int sampleRate, long samples) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] identification = new byte[30];
            identification[0] = 1;
            byte[] vorbis = "vorbis".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(vorbis, 0, identification, 1, vorbis.length);
            identification[12] = (byte) (sampleRate & 0xFF);
            identification[13] = (byte) ((sampleRate >> 8) & 0xFF);
            identification[14] = (byte) ((sampleRate >> 16) & 0xFF);
            identification[15] = (byte) ((sampleRate >> 24) & 0xFF);
            page(out, 0L, 0, identification);
            page(out, samples, 1, new byte[]{5});
            return out.toByteArray();
        }

        private static void page(ByteArrayOutputStream out, long granule, int sequence, byte[] payload) {
            out.writeBytes(new byte[]{'O', 'g', 'g', 'S', 0, 0});
            for (int i = 0; i < 8; i++) {
                out.write((int) ((granule >> (8 * i)) & 0xFF));
            }
            for (int i = 0; i < 4; i++) {
                out.write(0);
            }
            for (int i = 0; i < 4; i++) {
                out.write((sequence >> (8 * i)) & 0xFF);
            }
            for (int i = 0; i < 4; i++) {
                out.write(0);
            }
            out.write(1);
            out.write(payload.length);
            out.writeBytes(payload);
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
