import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.BeatmapPackageException;
import ru.gora.musicbattle.workshop.BeatmapPackageException.Reason;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/**
 * Standalone regression for the unwired workshop package groundwork (docs/WORKSHOP.md). Builds real zip
 * archives in a temp folder and runs the real installer on them: no Minecraft classes, no network.
 *
 * Proves: a valid set installs byte-for-byte and re-installs as a no-op; a new revision replaces the old
 * one; zip-slip names, hash mismatches, oversize files, newer manifest formats and other malformed input
 * are refused; a failed install or update leaves no partial folder and the previous version intact.
 * It does not prove that Minecraft's decoder plays the synthetic Ogg or that anything is shown in game.
 */
public final class WorkshopPackageChecks {
    private static final long SET = 42;
    private static final int SAMPLE_RATE = 44_100;
    private static final long DURATION_MS = 3_000;

    private static int failures;
    private static int checks;
    private static Path temp;

    public static void main(String[] args) throws Exception {
        temp = Files.createTempDirectory("workshop-package-checks");
        try {
            manifestRoundTrip();
            validPackageInstalls();
            reinstallIsNoOpAndRevisionReplaces();
            externalAudioPackage();
            signatureEntry();
            repackRoundTrip();
            zipSlipIsRejected();
            hashMismatchIsRejected();
            oversizeIsRejected();
            newerFormatIsRejected();
            failedInstallIsAtomic();
            malformedContentIsRejected();
            archiveShapeIsRejected();
            symbolicLinksAreRejected();
        } finally {
            deleteRecursively(temp);
        }
        System.out.println((failures == 0 ? "OK " : "FAILED ") + (checks - failures) + "/" + checks + " workshop package checks");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- checks

    private static void manifestRoundTrip() throws Exception {
        Fixture fixture = Fixture.included();
        BeatmapManifest manifest = fixture.manifest();
        BeatmapManifest parsed = BeatmapManifest.parse(manifest.toJson());
        check("manifest survives toJson/parse unchanged", parsed.equals(manifest));
        check("content hash is 64 lowercase hex and stable", manifest.contentSha256().matches("[0-9a-f]{64}")
                && manifest.contentSha256().equals(parsed.contentSha256()));
        fixture.files.put(WorkshopLayout.chartFileName(SET, "hard"), chart(SET, "hard", true, "[{\"owner\":1,\"lane\":2,\"tick\":40,\"durationTicks\":0}]"));
        check("content hash changes with a chart", !fixture.manifest().contentSha256().equals(manifest.contentSha256()));
        check("manifest name is not *.json, so the chart loader never reads it",
                !WorkshopLayout.MANIFEST_FILE_NAME.endsWith(".json"));
        check("chart ids stay within the 96-character id rule",
                WorkshopLayout.chartId(Long.MAX_VALUE, "k".repeat(WorkshopLayout.MAX_CHART_KEY_LENGTH)).length() <= 96);
        expectParse("deeply nested manifest", "[".repeat(5000), Reason.INVALID_MANIFEST);
        expectParse("manifest with a fractional setId", manifest.toJson().replace("\"setId\": 42", "\"setId\": 42.5"), Reason.INVALID_MANIFEST);
    }

    private static void validPackageInstalls() throws Exception {
        Path root = freshRoot("valid");
        Fixture fixture = Fixture.included();
        Path archive = fixture.write(temp.resolve("valid.blockymap"));
        BeatmapManifest preview = BeatmapPackage.readManifest(archive);
        check("readManifest returns the manifest without unpacking", preview.equals(fixture.manifest())
                && !Files.exists(WorkshopLayout.installedDirectory(root)));

        BeatmapPackage.InstallResult result = BeatmapPackage.install(archive, root);
        Path set = WorkshopLayout.installedDirectory(root).resolve("ws_42");
        check("valid package installs", result.outcome() == BeatmapPackage.Outcome.INSTALLED && result.directory().equals(set));
        boolean identical = true;
        for (Map.Entry<String, byte[]> file : fixture.files.entrySet()) {
            Path installed = set.resolve(file.getKey());
            identical &= Files.isRegularFile(installed) && Arrays.equals(Files.readAllBytes(installed), file.getValue());
        }
        check("every file is installed byte for byte", identical);
        check("manifest is installed next to the charts", Files.isRegularFile(set.resolve(WorkshopLayout.MANIFEST_FILE_NAME)));
        check("installed folder holds exactly the declared files", listNames(set).size() == fixture.files.size() + 1);
        check("installed root holds only the set", listNames(WorkshopLayout.installedDirectory(root)).equals(List.of("ws_42")));
        check("staging is empty after success", listNames(WorkshopLayout.stagingDirectory(root)).isEmpty());
    }

    private static void reinstallIsNoOpAndRevisionReplaces() throws Exception {
        Path root = freshRoot("update");
        Fixture first = Fixture.included();
        Path archive = first.write(temp.resolve("update-1.blockymap"));
        BeatmapPackage.install(archive, root);
        Path set = WorkshopLayout.installedDirectory(root).resolve("ws_42");
        long before = Files.getLastModifiedTime(set.resolve("ws_42_hard.json")).toMillis();

        BeatmapPackage.InstallResult again = BeatmapPackage.install(archive, root);
        check("identical package reports ALREADY_INSTALLED", again.outcome() == BeatmapPackage.Outcome.ALREADY_INSTALLED
                && Files.getLastModifiedTime(set.resolve("ws_42_hard.json")).toMillis() == before);

        Fixture second = Fixture.included();
        second.revision = 2;
        byte[] newChart = chart(SET, "hard", true, "[{\"owner\":0,\"lane\":3,\"tick\":80,\"durationTicks\":10}]");
        second.files.put("ws_42_hard.json", newChart);
        BeatmapPackage.InstallResult updated = BeatmapPackage.install(second.write(temp.resolve("update-2.blockymap")), root);
        check("new revision replaces the installed set", updated.outcome() == BeatmapPackage.Outcome.REPLACED
                && Arrays.equals(Files.readAllBytes(set.resolve("ws_42_hard.json")), newChart)
                && BeatmapManifest.parse(Files.readString(set.resolve(WorkshopLayout.MANIFEST_FILE_NAME))).revision() == 2);
        check("replacement leaves no retired copy behind", listNames(WorkshopLayout.stagingDirectory(root)).isEmpty()
                && listNames(WorkshopLayout.installedDirectory(root)).equals(List.of("ws_42")));

        Files.delete(set.resolve("ws_42_preview.png"));
        check("a set with a missing file is reinstalled, not reported as installed",
                BeatmapPackage.install(temp.resolve("update-2.blockymap"), root).outcome() == BeatmapPackage.Outcome.REPLACED
                        && Files.isRegularFile(set.resolve("ws_42_preview.png")));
    }

    private static void externalAudioPackage() throws Exception {
        Path root = freshRoot("external");
        Fixture external = Fixture.external();
        BeatmapPackage.InstallResult result = BeatmapPackage.install(external.write(temp.resolve("external.blockymap")), root);
        check("external-audio package installs without audio", result.outcome() == BeatmapPackage.Outcome.INSTALLED
                && !Files.exists(result.directory().resolve("ws_42.ogg"))
                && result.manifest().audio().policy() == BeatmapManifest.AudioPolicy.EXTERNAL);

        Fixture smuggled = Fixture.external();
        smuggled.extraEntries.put("ws_42.ogg", ogg(SAMPLE_RATE, SAMPLE_RATE * 3L));
        expectInstall("external-audio package carrying audio", smuggled, Reason.UNEXPECTED_ENTRY);

        Fixture includedWithoutAudio = Fixture.included();
        includedWithoutAudio.omitFromArchive.add("ws_42.ogg");
        expectInstall("included-audio package without its audio", includedWithoutAudio, Reason.MISSING_ENTRY);
    }

    /**
     * The repack of the review of 06 section 9.1: an installed set goes back into a package with its manifest, its
     * declared files re-hashed, and the signature when it has one. Installing the result must give the identical
     * folder, and a file whose bytes changed must stop the repack instead of shipping altered content.
     */
    private static void repackRoundTrip() throws Exception {
        Path root = freshRoot("repack");
        Fixture signed = Fixture.included();
        signed.extraEntries.put(WorkshopLayout.SIGNATURE_FILE_NAME, new byte[64]);
        BeatmapPackage.InstallResult installed =
                BeatmapPackage.install(signed.write(temp.resolve("repack-source.blockymap")), root);
        WorkshopCatalog catalog = new WorkshopCatalog(root);
        catalog.refresh();
        check("the catalog sees the installed set", catalog.isInstalled(42));
        check("the installed set counts as signed", catalog.signed(42));

        Path rebuilt = catalog.repack(42, temp.resolve("repacked"));
        check("repack produces a package", Files.isRegularFile(rebuilt) && Files.size(rebuilt) > 0);
        Path second = freshRoot("repack-target");
        BeatmapPackage.InstallResult again = BeatmapPackage.install(rebuilt, second);
        check("the repacked package installs", again.outcome() == BeatmapPackage.Outcome.INSTALLED);
        check("the repacked manifest is identical", again.manifest().equals(installed.manifest()));
        check("the repacked content hash is identical",
                again.manifest().contentSha256().equals(installed.manifest().contentSha256()));
        check("the signature survives the round trip",
                Files.exists(again.directory().resolve(WorkshopLayout.SIGNATURE_FILE_NAME)));
        for (BeatmapManifest.FileEntry entry : installed.manifest().files()) {
            check("repacked " + entry.path() + " is byte identical",
                    Arrays.equals(Files.readAllBytes(installed.directory().resolve(entry.path())),
                            Files.readAllBytes(again.directory().resolve(entry.path()))));
        }
        check("repacking twice gives the same bytes",
                Arrays.equals(Files.readAllBytes(rebuilt),
                        Files.readAllBytes(catalog.repack(42, temp.resolve("repacked2")))));

        // A file the player edited no longer matches the manifest: the repack refuses rather than ship it.
        Path chart = installed.directory().resolve("ws_42_hard.json");
        byte[] original = Files.readAllBytes(chart);
        Files.write(chart, "{\"id\":\"ws_42_hard\",\"notes\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean refused = false;
        try {
            catalog.repack(42, temp.resolve("repacked3"));
        } catch (IOException expected) {
            refused = true;
        }
        check("an edited file stops the repack", refused);
        Files.write(chart, original);

        // An unsigned install can be spotted before anything is offered to a server.
        Path unsignedRoot = freshRoot("repack-unsigned");
        BeatmapPackage.install(Fixture.included().write(temp.resolve("repack-unsigned.blockymap")), unsignedRoot);
        WorkshopCatalog unsigned = new WorkshopCatalog(unsignedRoot);
        unsigned.refresh();
        check("an install without a signature is not signed", !unsigned.signed(42));
    }

    private static void signatureEntry() throws Exception {
        Path root = freshRoot("signed");
        Fixture signed = Fixture.included();
        signed.extraEntries.put(WorkshopLayout.SIGNATURE_FILE_NAME, new byte[64]);
        BeatmapPackage.InstallResult result = BeatmapPackage.install(signed.write(temp.resolve("signed.blockymap")), root);
        check("package with an undeclared beatmap.sig installs", result.outcome() == BeatmapPackage.Outcome.INSTALLED);
        // Wave M3 (spec 08 A5.3): the signature travels into the installed folder, so a repack can still prove
        // where the copy came from. It is not a chart, audio or cover name, so nothing else ever reads it.
        check("beatmap.sig is installed next to the set",
                Files.exists(result.directory().resolve(WorkshopLayout.SIGNATURE_FILE_NAME))
                        && Files.size(result.directory().resolve(WorkshopLayout.SIGNATURE_FILE_NAME)) == 64);
        check("beatmap.sig does not change contentSha256",
                result.manifest().contentSha256().equals(Fixture.included().manifest().contentSha256()));
        check("the entry cap leaves room for 16 charts, audio, cover, manifest and signature",
                BeatmapPackage.MAX_ENTRIES == BeatmapManifest.MAX_CHARTS + 4);

        Fixture otherSignature = Fixture.included();
        otherSignature.extraEntries.put(WorkshopLayout.SIGNATURE_FILE_NAME, new byte[64]);
        otherSignature.extraEntries.put("ws_42.sig", new byte[64]);
        expectInstall("a second undeclared signature-like entry", otherSignature, Reason.UNSAFE_ENTRY_NAME);

        Fixture declaredSignature = Fixture.included();
        declaredSignature.files.put(WorkshopLayout.SIGNATURE_FILE_NAME, new byte[64]);
        expectInstall("beatmap.sig declared as a set file", declaredSignature, Reason.INVALID_MANIFEST);

        expectParse("manifest with the retired policy name licensed",
                Fixture.included().manifest().toJson().replace("\"policy\": \"included\"", "\"policy\": \"licensed\""), Reason.INVALID_MANIFEST);
        expectParse("manifest with the retired policy name chart_only",
                Fixture.external().manifest().toJson().replace("\"policy\": \"external\"", "\"policy\": \"chart_only\""), Reason.INVALID_MANIFEST);
    }

    private static void zipSlipIsRejected() throws Exception {
        String[] names = {
                "../ws_42_evil.json",
                "/tmp/ws_42_evil.json",
                "sub/../ws_42_evil.json",
                "..\\ws_42_evil.json",
                "C:\\ws_42_evil.json",
                "C:/ws_42_evil.json",
                "sub/ws_42_hard.json",
                "ws_42/",
                "WS_42_EVIL.JSON",
                "ws_42_evil.exe",
                "ws_42_evil.json\u0000.png",
        };
        for (String name : names) {
            Fixture fixture = Fixture.included();
            fixture.extraEntries.put(name, "{}".getBytes(StandardCharsets.UTF_8));
            Path root = freshRoot("slip");
            expectInstall("entry " + printable(name), fixture, Reason.UNSAFE_ENTRY_NAME, root);
            check("nothing written for " + printable(name), !Files.exists(root.getParent().resolve("ws_42_evil.json"))
                    && !Files.exists(WorkshopLayout.installedDirectory(root).resolve("ws_42")));
        }
    }

    private static void hashMismatchIsRejected() throws Exception {
        Fixture fixture = Fixture.included();
        fixture.tamper("ws_42_hard.json", bytes -> bytes[bytes.length - 2] ^= 1);
        expectInstall("chart with a flipped byte", fixture, Reason.HASH_MISMATCH);

        Fixture wrongDeclaredHash = Fixture.included();
        wrongDeclaredHash.hashOverride.put("ws_42_preview.png", "0".repeat(64));
        expectInstall("cover with a wrong declared hash", wrongDeclaredHash, Reason.HASH_MISMATCH);
    }

    private static void oversizeIsRejected() throws Exception {
        Fixture declaredTooLarge = Fixture.included();
        byte[] padded = chart(SET, "hard", true, "[]");
        byte[] big = Arrays.copyOf(padded, (int) BeatmapManifest.MAX_CHART_BYTES + 1);
        Arrays.fill(big, padded.length, big.length, (byte) ' ');
        declaredTooLarge.files.put("ws_42_hard.json", big);
        expectInstall("chart above 2 MiB", declaredTooLarge, Reason.FILE_TOO_LARGE);

        Fixture bomb = Fixture.included();
        bomb.sizeOverride.put("ws_42_hard.json", 16L);
        expectInstall("entry larger than its declared size", bomb, Reason.SIZE_MISMATCH);

        Fixture shortEntry = Fixture.included();
        shortEntry.sizeOverride.put("ws_42_hard.json", (long) shortEntry.files.get("ws_42_hard.json").length + 1);
        expectInstall("entry smaller than its declared size", shortEntry, Reason.SIZE_MISMATCH);

        Fixture audioTooLarge = Fixture.included();
        audioTooLarge.audioSizeOverride = BeatmapManifest.MAX_AUDIO_BYTES + 1;
        expectInstall("audio declared above 128 MiB", audioTooLarge, Reason.FILE_TOO_LARGE);
    }

    private static void newerFormatIsRejected() throws Exception {
        Fixture fixture = Fixture.included();
        fixture.format = BeatmapManifest.FORMAT_VERSION + 1;
        expectInstall("manifest from a newer format", fixture, Reason.UNSUPPORTED_FORMAT);
        expectParse("newer format with a different shape",
                "{\"format\": " + (BeatmapManifest.FORMAT_VERSION + 1) + ", \"sets\": []}", Reason.UNSUPPORTED_FORMAT);
    }

    private static void failedInstallIsAtomic() throws Exception {
        Path root = freshRoot("atomic");
        Fixture broken = Fixture.included();
        // The chart is extracted last (declared last), so audio and cover are already staged when it fails.
        broken.tamper("ws_42_hard.json", bytes -> bytes[3] ^= 1);
        expectInstall("fresh install failing on the last file", broken, Reason.HASH_MISMATCH, root);
        check("failed fresh install leaves no set folder", !Files.exists(WorkshopLayout.installedDirectory(root).resolve("ws_42")));
        check("failed fresh install leaves staging empty", listNames(WorkshopLayout.stagingDirectory(root)).isEmpty());

        Fixture good = Fixture.included();
        BeatmapPackage.install(good.write(temp.resolve("atomic-good.blockymap")), root);
        Path set = WorkshopLayout.installedDirectory(root).resolve("ws_42");
        Map<String, byte[]> before = snapshotTree(set);

        Fixture badUpdate = Fixture.included();
        badUpdate.revision = 2;
        badUpdate.files.put("ws_42_hard.json", chart(SET, "hard", true, "[]"));
        badUpdate.tamper("ws_42_hard.json", bytes -> bytes[3] ^= 1);
        expectInstall("update failing on the last file", badUpdate, Reason.HASH_MISMATCH, root);
        check("failed update keeps the previous version byte for byte", sameSnapshot(before, snapshotTree(set)));
        check("failed update leaves staging empty", listNames(WorkshopLayout.stagingDirectory(root)).isEmpty());

        Fixture badContent = Fixture.included();
        badContent.revision = 3;
        badContent.files.put("ws_42_preview.png", png(4096, 4096));
        expectInstall("update failing content validation", badContent, Reason.INVALID_COVER, root);
        check("content failure keeps the previous version", sameSnapshot(before, snapshotTree(set))
                && listNames(WorkshopLayout.stagingDirectory(root)).isEmpty());
    }

    private static void malformedContentIsRejected() throws Exception {
        Fixture opus = Fixture.included();
        byte[] notVorbis = ogg(SAMPLE_RATE, SAMPLE_RATE * 3L);
        notVorbis[28] = 'O';
        notVorbis[29] = 'p';
        opus.files.put("ws_42.ogg", notVorbis);
        expectInstall("Ogg that is not Vorbis", opus, Reason.INVALID_AUDIO);

        Fixture wrongDuration = Fixture.included();
        wrongDuration.audioDurationOverride = DURATION_MS + 5_000;
        expectInstall("audio shorter than declared", wrongDuration, Reason.INVALID_AUDIO);

        Fixture notPng = Fixture.included();
        notPng.files.put("ws_42_preview.png", "GIF89a-not-a-png-at-all".getBytes(StandardCharsets.UTF_8));
        expectInstall("cover that is not a PNG", notPng, Reason.INVALID_COVER);

        Fixture wrongId = Fixture.included();
        wrongId.files.put("ws_42_hard.json", new String(chart(SET, "hard", true, "[]"), StandardCharsets.UTF_8)
                .replace("\"ws_42_hard\"", "\"my_track\"").getBytes(StandardCharsets.UTF_8));
        expectInstall("chart whose id is not its file name", wrongId, Reason.INVALID_CHART);

        Fixture escapingAudio = Fixture.included();
        escapingAudio.files.put("ws_42_hard.json", new String(chart(SET, "hard", true, "[]"), StandardCharsets.UTF_8)
                .replace("\"ws_42/ws_42.ogg\"", "\"../../evil.ogg\"").getBytes(StandardCharsets.UTF_8));
        expectInstall("chart pointing its audio outside the set", escapingAudio, Reason.INVALID_CHART);

        Fixture tooManyNotes = Fixture.included();
        StringBuilder notes = new StringBuilder("[");
        for (int i = 0; i <= 4096; i++) {
            notes.append(i == 0 ? "" : ",").append("{\"owner\":0,\"lane\":0,\"tick\":").append(i).append(",\"durationTicks\":0}");
        }
        tooManyNotes.files.put("ws_42_hard.json", chart(SET, "hard", true, notes.append(']').toString()));
        expectInstall("chart with 4097 notes", tooManyNotes, Reason.INVALID_CHART);

        Fixture undeclared = Fixture.included();
        undeclared.extraEntries.put("ws_42_extra.json", chart(SET, "extra", true, "[]"));
        expectInstall("safe but undeclared entry", undeclared, Reason.UNEXPECTED_ENTRY);

        Fixture noManifest = Fixture.included();
        noManifest.omitManifest = true;
        expectInstall("archive without a manifest", noManifest, Reason.MISSING_MANIFEST);
    }

    private static void archiveShapeIsRejected() throws Exception {
        Fixture crowded = Fixture.included();
        for (int i = 0; crowded.files.size() + crowded.extraEntries.size() + 1 <= BeatmapPackage.MAX_ENTRIES; i++) {
            crowded.extraEntries.put("ws_42_filler" + i + ".json", "{}".getBytes(StandardCharsets.UTF_8));
        }
        expectInstall("archive above the entry cap", crowded, Reason.TOO_MANY_ENTRIES);

        Path notZip = temp.resolve("not-a-zip.blockymap");
        Files.write(notZip, "this is not a zip archive".getBytes(StandardCharsets.UTF_8));
        expectInstall("file that is not a zip", notZip, Reason.CORRUPT_ARCHIVE, freshRoot("corrupt"));
        expectInstall("missing archive", temp.resolve("absent.blockymap"), Reason.NOT_A_FILE, freshRoot("absent"));
    }

    private static void symbolicLinksAreRejected() throws Exception {
        Path archive = Fixture.included().write(temp.resolve("linked-source.blockymap"));
        Path link = temp.resolve("linked.blockymap");
        Path root = freshRoot("linked-root");
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(link, archive);
            Files.createDirectories(root);
            Files.createSymbolicLink(WorkshopLayout.installedDirectory(root), elsewhere);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            System.out.println("SKIP symbolic link checks: this file system or account cannot create links");
            return;
        }
        expectInstall("archive that is a symbolic link", link, Reason.NOT_A_FILE, freshRoot("via-link"));
        expectInstall("installed folder that is a symbolic link", archive, Reason.UNSAFE_DESTINATION, root);
        check("nothing written through the linked folder", listNames(elsewhere).isEmpty());
    }

    // ---------------------------------------------------------------- fixture

    /** One set with an included or external audio policy, built so that the default is valid. */
    private static final class Fixture {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Map<String, byte[]> extraEntries = new LinkedHashMap<>();
        final Map<String, String> hashOverride = new LinkedHashMap<>();
        final Map<String, Long> sizeOverride = new LinkedHashMap<>();
        final List<String> omitFromArchive = new ArrayList<>();
        final Map<String, Consumer<byte[]>> tamper = new LinkedHashMap<>();
        BeatmapManifest.AudioPolicy policy;
        int format = BeatmapManifest.FORMAT_VERSION;
        int revision = 1;
        long audioSizeOverride = -1;
        long audioDurationOverride = -1;
        boolean omitManifest;
        private final byte[] audio = ogg(SAMPLE_RATE, SAMPLE_RATE * DURATION_MS / 1000);

        static Fixture included() {
            Fixture fixture = new Fixture(BeatmapManifest.AudioPolicy.INCLUDED);
            fixture.files.put("ws_42.ogg", fixture.audio);
            fixture.files.put("ws_42_preview.png", png(512, 512));
            fixture.files.put("ws_42_hard.json", chart(SET, "hard", true, "[{\"owner\":0,\"lane\":1,\"tick\":20,\"durationTicks\":0}]"));
            return fixture;
        }

        static Fixture external() {
            Fixture fixture = new Fixture(BeatmapManifest.AudioPolicy.EXTERNAL);
            fixture.files.put("ws_42_hard.json", chart(SET, "hard", false, "[{\"owner\":0,\"lane\":1,\"tick\":20,\"durationTicks\":0}]"));
            return fixture;
        }

        private Fixture(BeatmapManifest.AudioPolicy policy) {
            this.policy = policy;
        }

        /** Changes the bytes written to the archive after the manifest has been computed from the originals. */
        void tamper(String path, Consumer<byte[]> change) {
            tamper.put(path, change);
        }

        BeatmapManifest manifest() {
            List<BeatmapManifest.FileEntry> entries = new ArrayList<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                entries.add(new BeatmapManifest.FileEntry(file.getKey(),
                        sizeOverride.getOrDefault(file.getKey(), (long) file.getValue().length),
                        hashOverride.getOrDefault(file.getKey(), sha256(file.getValue()))));
            }
            // A set with included audio declares the audio it carries, so a replaced audio file changes the declaration too.
            byte[] declaredAudio = files.getOrDefault(WorkshopLayout.audioFileName(SET), audio);
            return new BeatmapManifest(format, SET, revision, "Test Song & Friends", "QwU World",
                    new BeatmapManifest.Mapper(7, "dragon4ik"),
                    new BeatmapManifest.Audio(policy, audioSizeOverride > 0 ? audioSizeOverride : declaredAudio.length, sha256(declaredAudio),
                            audioDurationOverride > 0 ? audioDurationOverride : DURATION_MS),
                    List.of(new BeatmapManifest.Chart("hard", "Hard")),
                    entries);
        }

        Path write(Path archive) throws IOException {
            // Built by hand rather than through toJson(): a fixture must be able to express invalid manifests.
            String manifestJson = manifest().toJson().replace("\"format\": " + BeatmapManifest.FORMAT_VERSION, "\"format\": " + format);
            try (OutputStream file = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(file)) {
                if (!omitManifest) {
                    put(zip, WorkshopLayout.MANIFEST_FILE_NAME, manifestJson.getBytes(StandardCharsets.UTF_8));
                }
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    if (omitFromArchive.contains(entry.getKey())) {
                        continue;
                    }
                    byte[] bytes = entry.getValue().clone();
                    Consumer<byte[]> change = tamper.get(entry.getKey());
                    if (change != null) {
                        change.accept(bytes);
                    }
                    put(zip, entry.getKey(), bytes);
                }
                for (Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                    put(zip, entry.getKey(), entry.getValue());
                }
            }
            return archive;
        }

        private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }
    }

    /** A chart in the exporter's shape, with the id and asset references the installed folder requires. */
    private static byte[] chart(long setId, String key, boolean withAssets, String notesJson) {
        String folder = WorkshopLayout.setFolderName(setId);
        String json = "{\n"
                + "  \"id\": \"" + WorkshopLayout.chartId(setId, key) + "\",\n"
                + "  \"title\": \"Test Song [" + key + "]\",\n"
                + "  \"description\": \"\",\n"
                + "  \"playerPartyOrderVersion\": 2,\n"
                + "  \"bpm\": 120,\n"
                + "  \"volume\": 1.0,\n"
                + "  \"durationTicks\": 400,\n"
                + "  \"audio\": \"" + folder + "/" + WorkshopLayout.audioFileName(setId) + "\",\n"
                + "  \"preview\": \"" + (withAssets ? folder + "/" + WorkshopLayout.coverFileName(setId) : "") + "\",\n"
                + "  \"disableStageBars\": false,\n"
                + "  \"hideStandardDimming\": false,\n"
                + "  \"fxRowsPerLayer\": 6,\n"
                + "  \"notes\": " + notesJson + ",\n"
                + "  \"effects\": []\n"
                + "}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Two Ogg pages: a Vorbis identification header, then a page whose granule position sets the length. */
    private static byte[] ogg(int sampleRate, long samples) {
        ByteBuffer identification = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        identification.put((byte) 1).put("vorbis".getBytes(StandardCharsets.US_ASCII)).putInt(0).put((byte) 2)
                .putInt(sampleRate).putInt(0).putInt(128_000).putInt(0).put((byte) 0xB8).put((byte) 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        oggPage(out, 2, 0, 0, identification.array());
        byte[] audio = new byte[64];
        Arrays.fill(audio, (byte) 0x5A);
        oggPage(out, 4, samples, 1, audio);
        return out.toByteArray();
    }

    private static void oggPage(ByteArrayOutputStream out, int type, long granule, int sequence, byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        header.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) type).putLong(granule)
                .putInt(0x424D5553).putInt(sequence).putInt(0).put((byte) 1).put((byte) payload.length);
        out.writeBytes(header.array());
        out.writeBytes(payload);
    }

    private static byte[] png(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 25 + 12).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        buffer.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0);
        buffer.putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt(0xAE426082);
        return buffer.array();
    }

    // ---------------------------------------------------------------- helpers

    private static void expectInstall(String label, Fixture fixture, Reason reason) throws IOException {
        Path root = freshRoot("expect");
        expectInstall(label, fixture, reason, root);
    }

    private static void expectInstall(String label, Fixture fixture, Reason reason, Path root) throws IOException {
        Path archive = fixture.write(temp.resolve("case-" + (checks + 1) + WorkshopLayout.PACKAGE_EXTENSION));
        expectInstall(label, archive, reason, root);
    }

    private static void expectInstall(String label, Path archive, Reason reason, Path root) throws IOException {
        Map<String, byte[]> before = Files.isDirectory(WorkshopLayout.installedDirectory(root))
                ? snapshotTree(WorkshopLayout.installedDirectory(root)) : Map.of();
        try {
            BeatmapPackage.install(archive, root);
            check(label + " is refused with " + reason + " (it installed)", false);
        } catch (BeatmapPackageException exception) {
            boolean staged = !Files.isDirectory(WorkshopLayout.stagingDirectory(root))
                    || listNames(WorkshopLayout.stagingDirectory(root)).isEmpty();
            boolean untouched = sameSnapshot(before, Files.isDirectory(WorkshopLayout.installedDirectory(root))
                    ? snapshotTree(WorkshopLayout.installedDirectory(root)) : Map.of());
            check(label + " is refused with " + reason + " (got " + exception.reason() + ")",
                    exception.reason() == reason && staged && untouched);
            check(label + ": message has no absolute path", !exception.getMessage().contains(temp.toString()));
        }
    }

    private static void expectParse(String label, String json, Reason reason) {
        try {
            BeatmapManifest.parse(json);
            check(label + " is refused with " + reason + " (it parsed)", false);
        } catch (BeatmapPackageException exception) {
            check(label + " is refused with " + reason + " (got " + exception.reason() + ")", exception.reason() == reason);
        } catch (Throwable unexpected) {
            check(label + " is refused with " + reason + " (threw " + unexpected.getClass().getSimpleName() + ")", false);
        }
    }

    private static Path freshRoot(String name) throws IOException {
        return Files.createTempDirectory(temp, name + "-").resolve("music_battle_tracks");
    }

    private static List<String> listNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static Map<String, byte[]> snapshotTree(Path directory) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                files.put(directory.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return files;
    }

    private static boolean sameSnapshot(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : a.entrySet()) {
            if (!Arrays.equals(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String printable(String value) {
        return value.replace("\u0000", "\\0");
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + label);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
