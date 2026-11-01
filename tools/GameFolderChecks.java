import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import ru.gora.musicbattle.client.ClientPreviewProcessor;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * The `game-folder` regression (claims-vs-code F10): nothing of the mod's is ever written outside the Minecraft folder.
 *
 * <ul>
 *     <li>"Publish to server" makes its PNG of the beatmap's picture in the mod's own temporary folder,
 *     {@code music_battle_tracks/.temp} inside the (throwaway) game folder, not in the system's temporary folder;</li>
 *     <li>the leftovers a killed game leaves there are deleted at the next start, and nothing outside that folder is;</li>
 *     <li>the sources: no temporary file or folder without a folder of the mod's own, no {@code java.io.tmpdir}, no
 *     ImageIO stream over a plain Input/OutputStream (ImageIO caches those in the system's temporary folder), and
 *     {@code user.home} read in two places only, neither of which writes there.</li>
 * </ul>
 */
public final class GameFolderChecks {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path work = Files.createTempDirectory("blocky-game-folder-");
        try {
            Path game = Files.createDirectories(work.resolve("game"));
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
            publishPreview(work, game);
            leftovers(work, game);
        } finally {
            deleteTree(work);
        }
        sources(root);
        if (!FAILURES.isEmpty()) {
            System.out.println(checks + " game folder checks passed, " + FAILURES.size() + " failed:");
            FAILURES.forEach(failure -> System.out.println("  FAIL " + failure));
            System.exit(1);
        }
        System.out.println(checks + " game folder checks passed");
        System.out.println("NOT COVERED: the publish upload itself (it needs a server connection); writes made by Minecraft or other mods");
    }

    private static void publishPreview(Path work, Path game) throws IOException {
        Path picture = work.resolve("outside/cover.png");
        Files.createDirectories(picture.getParent());
        BufferedImage image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y += 7) {
            for (int x = 0; x < image.getWidth(); x += 5) {
                image.setRGB(x, y, 0xFF000000 | (x * 31 + y * 17) & 0xFFFFFF);
            }
        }
        check(ImageIO.write(image, "png", picture.toFile()), "the test picture is written");
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Set<Path> before = listing(systemTemp);
        ClientPreviewProcessor.ProcessedPreview first = ClientPreviewProcessor.processForPublication(picture);
        ClientPreviewProcessor.ProcessedPreview second = ClientPreviewProcessor.processForPublication(picture);
        Path temporary = MusicBattleTracks.temporaryDirectory();
        check(temporary.startsWith(game.toAbsolutePath().normalize()), "the mod's temporary folder is inside the Minecraft folder: " + temporary);
        check(temporary.equals(game.resolve("music_battle_tracks/.temp").toAbsolutePath().normalize()), "and it is music_battle_tracks/.temp");
        for (ClientPreviewProcessor.ProcessedPreview preview : List.of(first, second)) {
            Path file = preview.path().toAbsolutePath().normalize();
            check(file.getParent().equals(temporary), "the publish picture is made in the mod's temporary folder: " + file);
            check(Files.isRegularFile(file) && Files.size(file) == preview.outputBytes() && preview.outputBytes() > 0,
                    "and it is the finished PNG");
            BufferedImage read = ImageIO.read(file.toFile());
            check(read != null && Math.max(read.getWidth(), read.getHeight()) <= ClientPreviewProcessor.MAX_PUBLISHED_DIMENSION,
                    "bounded to the published size");
        }
        check(!first.path().equals(second.path()), "two publishes never share a file");
        // What the system's temporary folder gained meanwhile: nothing of the mod's.
        Set<Path> after = listing(systemTemp);
        after.removeAll(before);
        List<Path> ours = after.stream().filter(path -> {
            String name = String.valueOf(path.getFileName()).toLowerCase(java.util.Locale.ROOT);
            return name.contains("blocky") || name.contains("preview") || name.startsWith("imageio");
        }).toList();
        check(ours.isEmpty(), "nothing of the mod's appeared in the system's temporary folder: " + ours);
        // The publisher deletes it when the upload ends or fails; this is the same call.
        Files.deleteIfExists(first.path());
        Files.deleteIfExists(second.path());
    }

    private static void leftovers(Path work, Path game) throws IOException {
        Path temporary = Files.createDirectories(MusicBattleTracks.temporaryDirectory());
        Path picture = Files.write(temporary.resolve("publish-preview-123.png"), new byte[] {1, 2, 3});
        Path song = Files.write(temporary.resolve("chosen-song-456.ogg"), new byte[] {4});
        Path folder = Files.createDirectories(temporary.resolve("kept-folder"));
        Path outside = Files.write(MusicBattleTracks.tracksRootDirectory().resolve("note.txt"), new byte[] {5});
        int deleted = MusicBattleTracks.clearTemporaryDirectory();
        check(deleted == 2 && !Files.exists(picture) && !Files.exists(song), "a killed game's leftovers are deleted at the next start");
        check(Files.isDirectory(folder) && Files.exists(outside), "nothing but the files directly in the temporary folder is touched");
        Files.delete(folder);
        check(MusicBattleTracks.clearTemporaryDirectory() == 0, "an empty temporary folder is fine");
        Files.delete(temporary);
        check(MusicBattleTracks.clearTemporaryDirectory() == 0, "a missing temporary folder is fine");
        String setup = Files.readString(Path.of(System.getProperty("user.dir")).resolve("src/main/java/ru/gora/musicbattle/client/ClientModEvents.java"));
        check(setup.contains("MusicBattleTracks.clearTemporaryDirectory()"), "the client clears the temporary folder at start");
    }

    private static void sources(Path root) throws IOException {
        Path src = root.resolve("src/main/java");
        // A temporary file or folder always names a folder of the mod's own: a string literal first is the system one.
        Pattern noFolder = Pattern.compile("\\b(?:Files\\.)?createTemp(?:File|Directory)\\s*\\(\\s*\"");
        Pattern legacyTemp = Pattern.compile("\\bFile\\.createTempFile\\s*\\(");
        Pattern tmpdir = Pattern.compile("java\\.io\\.tmpdir|getTempDirectory|deleteOnExit");
        // ImageIO keeps a cache file in the system's temporary folder for a stream that is not a file.
        Pattern imageIoStream = Pattern.compile("ImageIO\\.(?:read|write|createImageInputStream|createImageOutputStream)\\s*\\(([^;]*)");
        Pattern home = Pattern.compile("user\\.home");
        Set<String> homeReaders = Set.of("ru/gora/musicbattle/server/MusicBattleTracks.java", "ru/gora/musicbattle/client/RapGodConfigScreen.java");
        int files = 0;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                files++;
                String text = stripComments(Files.readString(file));
                String name = src.relativize(file).toString().replace('\\', '/');
                check(!noFolder.matcher(text).find(), name + ": no temporary file or folder in the system's temporary folder");
                check(!legacyTemp.matcher(text).find(), name + ": no File.createTempFile");
                check(!tmpdir.matcher(text).find(), name + ": no java.io.tmpdir, no deleteOnExit");
                Matcher stream = imageIoStream.matcher(text);
                while (stream.find()) {
                    String call = stream.group(1);
                    boolean fileBacked = call.contains(".toFile()") || call.contains("MemoryCacheImage");
                    if (!fileBacked) {
                        // A stream variable: it has to be one opened on a file or a memory cache in this file.
                        Matcher last = Pattern.compile("(\\w+)\\s*\\)[^,]*$").matcher(call.split("\\)\\s*[{;]|\\)\\s*\\)\\s*\\{")[0] + ")");
                        String variable = last.find() ? last.group(1) : "";
                        fileBacked = !variable.isEmpty() && Pattern.compile("\\b" + variable
                                + "\\s*=\\s*(?:new\\s+javax\\.imageio\\.stream\\.MemoryCacheImage\\w+Stream\\(|ImageIO\\.createImage\\w+Stream\\([^;]*\\.toFile\\(\\))")
                                .matcher(text).find();
                    }
                    check(fileBacked, name + ": ImageIO over a file or a memory cache, never a plain stream: ImageIO." + call.strip());
                }
                if (home.matcher(text).find()) {
                    check(homeReaders.contains(name), name + ": reads user.home, which only the path folding and the skin picker's start folder may");
                }
            }
        }
        check(files > 300, "the sources were found: " + files);
        // The two readers of user.home only read it: one folds paths in messages, one starts a file dialog there.
        String tracks = stripComments(Files.readString(src.resolve("ru/gora/musicbattle/server/MusicBattleTracks.java")));
        Matcher homeUse = Pattern.compile("String home = System\\.getProperty\\(\"user\\.home\", \"\"\\);").matcher(tracks);
        check(homeUse.find(), "MusicBattleTracks reads user.home only to fold paths in messages");
        String npc = stripComments(Files.readString(src.resolve("ru/gora/musicbattle/client/RapGodConfigScreen.java")));
        check(npc.contains("Path start = lastSkinFolder != null ? lastSkinFolder : Path.of(System.getProperty(\"user.home\", \".\"));"),
                "the skin picker only starts its dialog in the home folder");
        String publisher = stripComments(Files.readString(src.resolve("ru/gora/musicbattle/client/ClientTrackPublisher.java")));
        check(publisher.contains("Files.deleteIfExists(temporary)") && publisher.contains("Files.deleteIfExists(asset.path)"),
                "the publisher deletes the picture when the upload ends or fails");
    }

    private static Set<Path> listing(Path directory) throws IOException {
        try (Stream<Path> list = Files.list(directory)) {
            return new java.util.HashSet<>(list.toList());
        } catch (IOException unreadable) {
            return new java.util.HashSet<>();
        }
    }

    static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//[^\n]*", "").replaceAll("\\s//[^\n]*", "");
    }

    private static void check(boolean condition, String label) {
        if (condition) {
            checks++;
        } else {
            FAILURES.add(label);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
