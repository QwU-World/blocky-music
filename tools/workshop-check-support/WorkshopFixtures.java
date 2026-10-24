import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/** Valid .blockymap packages for the Workshop checks (same shapes as WorkshopPackageChecks). */
final class WorkshopFixtures {
    static final int SAMPLE_RATE = 44_100;
    static final long DURATION_MS = 3_000;

    record Built(byte[] bytes, BeatmapManifest manifest, String packageSha256) {
    }

    private WorkshopFixtures() {
    }

    static Built build(long setId, int revision, String title) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] audio = ogg(SAMPLE_RATE, SAMPLE_RATE * DURATION_MS / 1000 + revision);
        files.put(WorkshopLayout.audioFileName(setId), audio);
        files.put(WorkshopLayout.coverFileName(setId), png(64, 64));
        files.put(WorkshopLayout.chartFileName(setId, "hard"), chart(setId, "hard"));
        List<BeatmapManifest.FileEntry> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            entries.add(new BeatmapManifest.FileEntry(file.getKey(), file.getValue().length, sha256(file.getValue())));
        }
        BeatmapManifest manifest = new BeatmapManifest(BeatmapManifest.FORMAT_VERSION, setId, revision, title, "QwU World",
                new BeatmapManifest.Mapper(7, "dragon4ik"),
                new BeatmapManifest.Audio(BeatmapManifest.AudioPolicy.INCLUDED, audio.length, sha256(audio), DURATION_MS),
                List.of(new BeatmapManifest.Chart("hard", "Hard")), entries);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, WorkshopLayout.MANIFEST_FILE_NAME, manifest.toJson().getBytes(StandardCharsets.UTF_8));
            List<String> names = new ArrayList<>(files.keySet());
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                put(zip, name, files.get(name));
            }
        }
        byte[] bytes = archive.toByteArray();
        return new Built(bytes, manifest, sha256(bytes));
    }

    static Path write(Built built, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, built.bytes());
        return file;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    static byte[] chart(long setId, String key) {
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
                + "  \"preview\": \"" + folder + "/" + WorkshopLayout.coverFileName(setId) + "\",\n"
                + "  \"disableStageBars\": false,\n"
                + "  \"hideStandardDimming\": false,\n"
                + "  \"fxRowsPerLayer\": 6,\n"
                + "  \"notes\": [{\"owner\":0,\"lane\":1,\"tick\":20,\"durationTicks\":0}],\n"
                + "  \"effects\": []\n"
                + "}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] ogg(int sampleRate, long samples) {
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

    static byte[] png(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + 25 + 12).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        buffer.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0);
        buffer.putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt(0xAE426082);
        return buffer.array();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A detail JSON the fake server answers for a set, with a preview clip URL and viewer fields to strip. */
    static String detailJson(long setId, int revision, String contentSha256, String previewPath) {
        return "{\"schema\":1,\"id\":" + setId + ",\"revision\":" + revision + ",\"contentSha256\":\"" + contentSha256 + "\","
                + "\"title\":\"Test Song\",\"titleUnicode\":null,\"artist\":\"QwU World\",\"artistUnicode\":null,\"source\":\"\","
                + "\"status\":\"community\",\"availability\":\"ok\",\"audioMode\":\"included\",\"library\":null,"
                + "\"mapper\":{\"id\":7,\"username\":\"dragon4ik\",\"displayName\":\"Dragon4ik\",\"avatarUrl\":null},"
                + "\"covers\":null,\"preview\":" + (previewPath == null ? "null" : "{\"ogg\":\"" + previewPath + "\",\"mp3\":\"/x.mp3\",\"durationMs\":30000}") + ","
                + "\"bpm\":120,\"durationMs\":3000,"
                + "\"charts\":[{\"key\":\"hard\",\"name\":\"Hard\",\"stars\":2.5,\"starsLeft\":2.5,\"starsRight\":null,\"sides\":1,"
                + "\"noteCount\":1,\"holdCount\":0,\"effectCount\":0,\"durationMs\":3000,\"bpm\":120,\"peakNps\":null,\"fileSha256\":\"x\"}],"
                + "\"counts\":{\"downloads\":1,\"favourites\":0,\"comments\":0,\"ratings\":0},\"rating\":null,"
                + "\"flags\":{\"explicit\":false,\"flashing\":false,\"aiAudio\":false,\"aiCover\":false,\"aiChart\":false,\"spoiler\":false},"
                + "\"hasFx\":false,\"genre\":1,\"language\":1,\"tags\":[],\"submittedAt\":\"2026-09-17T10:00:00Z\","
                + "\"updatedAt\":\"2026-09-17T10:00:00Z\",\"featuredAt\":null,\"viewer\":{\"favourited\":true,\"rating\":null},"
                + "\"descriptionHtml\":\"<p>x</p>\",\"creditsHtml\":\"\",\"featured\":null,\"availabilityInfo\":null,"
                + "\"ratingHist\":[0,0,0,0,0,0,0,0,0,0],\"previewStartMs\":60000,\"libraryAttribution\":null,"
                + "\"download\":{\"size\":1,\"packageSha256\":\"x\",\"fileName\":\"x.blockymap\"},"
                + "\"audio\":{\"mode\":\"included\",\"sha256\":\"x\",\"size\":1,\"durationMs\":3000},\"commentsLocked\":false,"
                + "\"can\":{\"download\":true},\"cannot\":{\"rate\":\"own_set\"},\"mapperProfile\":{\"sets\":1,\"featured\":0}}";
    }
}
