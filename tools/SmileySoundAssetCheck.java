import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.VorbisDecoder;

/**
 * The shipped smiley sound is a complete mono Ogg Vorbis file. It used to be checked with the system's ffprobe and
 * ffmpeg, which a developer had to install; the mod's own decoder reads it now, as it reads every song, and its Ogg
 * checksums prove the file whole. Minecraft plays the sound with its own decoder; the native-vorbis-decoder check
 * compares the two on this very file.
 */
public final class SmileySoundAssetCheck {
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path asset = project.resolve("src/main/resources/assets/music_battle/sounds/entity/smiley_damage.ogg");
        if (!Files.isRegularFile(asset)) {
            fail("Missing smiley sound asset: " + asset);
        }
        if (!ru.gora.musicbattle.audio.NativeAudio.isOggVorbis(asset)) {
            fail("The smiley sound is not Ogg Vorbis.");
        }
        long frames = 0;
        int rate;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(asset));
             PcmStream stream = VorbisDecoder.open(input)) {
            if (stream.channels() != 1) {
                fail("Expected a mono sound, got " + stream.channels() + " channels.");
            }
            rate = stream.sampleRate();
            float[][] buffer = new float[1][4096];
            int read;
            while ((read = stream.read(buffer, 0, 4096)) > 0) {
                for (int i = 0; i < read; i++) {
                    if (!Float.isFinite(buffer[0][i])) {
                        fail("The smiley sound decodes to a sample that is not a number.");
                    }
                }
                frames += read;
            }
        }
        if (frames == 0) {
            fail("The smiley sound has no audio.");
        }
        System.out.println("Smiley OGG decode PASS: Vorbis, mono, " + rate + " Hz, " + frames + " samples");
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(1);
    }
}
