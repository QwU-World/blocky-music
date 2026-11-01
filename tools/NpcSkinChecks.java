import com.mojang.blaze3d.platform.NativeImage;
import io.netty.buffer.Unpooled;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.data.NpcSkinPng;
import ru.gora.musicbattle.network.RapGodSkinUploadPayload;

/**
 * The test NPC's skin from a file, without starting the game: the rules a PNG has to pass before the server keeps it
 * (data/NpcSkinPng), the game's own PNG writer that the screen re-encodes through, the legacy 64x32 conversion
 * compared pixel for pixel with vanilla's HttpTexture.processLegacySkin, the upload payload's size limit, and the
 * message the server answers each refusal with.
 *
 * What it does not prove: that the NPC wears the skin in a running game, or that a second player receives it. Those
 * need two clients and a server.
 *
 * Usage: java -cp <classpath> NpcSkinChecks <project root>
 */
public final class NpcSkinChecks {
    static int checks;
    private static final Random RANDOM = new Random(0x5EEDL);

    public static void main(String[] args) throws Exception {
        writersTheGameMeets();
        wrongSizesAndOtherFiles();
        brokenFiles();
        sizeLimits();
        gameWriter();
        legacyMatchesVanilla();
        hashes();
        payloadCodec();
        refusals();
        System.out.println("NPC skin checks passed: " + checks);
    }

    // ---------------------------------------------------------------- files an editor writes

    private static void writersTheGameMeets() throws Exception {
        expectOk(imageIo(noise(64, 64, BufferedImage.TYPE_INT_ARGB)), 64, 64, "RGBA 64x64 from ImageIO");
        expectOk(imageIo(noise(64, 32, BufferedImage.TYPE_INT_ARGB)), 64, 32, "RGBA 64x32 (legacy) from ImageIO");
        check(NpcSkinPng.check(imageIo(noise(64, 32, BufferedImage.TYPE_INT_ARGB))).legacy(), "64x32 reads as legacy");
        check(!NpcSkinPng.check(imageIo(noise(64, 64, BufferedImage.TYPE_INT_ARGB))).legacy(), "64x64 is not legacy");
        expectOk(imageIo(noise(64, 64, BufferedImage.TYPE_INT_RGB)), 64, 64, "RGB without alpha");
        expectOk(imageIo(noise(64, 64, BufferedImage.TYPE_BYTE_GRAY)), 64, 64, "8-bit grey");
        expectOk(imageIo(noise(64, 64, BufferedImage.TYPE_USHORT_GRAY)), 64, 64, "16-bit grey");
        expectOk(imageIo(noise(64, 64, BufferedImage.TYPE_BYTE_INDEXED)), 64, 64, "palette");
        expectOk(imageIo(noise(64, 64, BufferedImage.TYPE_BYTE_BINARY)), 64, 64, "1-bit palette");
        expectOk(interlaced(noise(64, 64, BufferedImage.TYPE_INT_ARGB)), 64, 64, "Adam7-interlaced RGBA");
        expectOk(interlaced(noise(64, 32, BufferedImage.TYPE_BYTE_GRAY)), 64, 32, "Adam7-interlaced grey 64x32");
        expectOk(build(64, 64, 8, 6, rawRows(64, 64, 4, 0), 3), 64, 64, "image data split over three IDAT chunks");
        expectOk(build(64, 64, 8, 6, rawRows(64, 64, 4, 0), 1,
                chunk("qwUc", "ancillary".getBytes(StandardCharsets.US_ASCII))), 64, 64, "an unknown ancillary chunk");
        byte[] palette = new byte[16 * 3];
        expectOk(build(64, 64, 4, 3, rawRows(64, 64, 0.5, 0), 1, chunk("PLTE", palette)), 64, 64, "4-bit palette with PLTE");
    }

    private static void wrongSizesAndOtherFiles() throws Exception {
        expect(imageIo(noise(65, 64, BufferedImage.TYPE_INT_ARGB)), NpcSkinPng.Problem.WRONG_SIZE, 65, 64, "65x64");
        expect(imageIo(noise(64, 48, BufferedImage.TYPE_INT_ARGB)), NpcSkinPng.Problem.WRONG_SIZE, 64, 48, "64x48");
        expect(imageIo(flat(128, 128)), NpcSkinPng.Problem.WRONG_SIZE, 128, 128, "a 128x128 HD skin");
        // A noisy HD skin is bigger than any skin the server keeps, which the server says first; the screen reads files
        // up to a megabyte and so tells the player the size that is actually wrong.
        byte[] noisyHd = imageIo(noise(128, 128, BufferedImage.TYPE_INT_ARGB));
        expect(noisyHd, NpcSkinPng.Problem.TOO_LARGE, 0, 0, "a noisy 128x128 skin, as the server sees it");
        NpcSkinPng.Result hd = NpcSkinPng.inspect(noisyHd, NpcSkinPng.MAX_SOURCE_BYTES);
        check(hd.problem() == NpcSkinPng.Problem.WRONG_SIZE && hd.width() == 128 && hd.height() == 128,
                "the screen names the size of a noisy 128x128 skin: " + hd);
        expect(imageIo(noise(32, 32, BufferedImage.TYPE_INT_ARGB)), NpcSkinPng.Problem.WRONG_SIZE, 32, 32, "32x32");
        // A header promising a gigantic image is refused from the header, before anything is unpacked.
        expect(build(100_000, 100_000, 8, 6, new byte[0], 1), NpcSkinPng.Problem.WRONG_SIZE, 100_000, 100_000,
                "100000x100000 header with no data");
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(noise(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        expect(jpeg.toByteArray(), NpcSkinPng.Problem.NOT_PNG, 0, 0, "a JPEG");
        expect("GIF89a....".getBytes(StandardCharsets.US_ASCII), NpcSkinPng.Problem.NOT_PNG, 0, 0, "a GIF header");
        byte[] random = new byte[2000];
        RANDOM.nextBytes(random);
        expect(random, NpcSkinPng.Problem.NOT_PNG, 0, 0, "random bytes");
        expect(new byte[0], NpcSkinPng.Problem.EMPTY, 0, 0, "an empty file");
        expect(null, NpcSkinPng.Problem.EMPTY, 0, 0, "no file");
        expect(Arrays.copyOf(imageIo(noise(64, 64, BufferedImage.TYPE_INT_ARGB)), 6), NpcSkinPng.Problem.NOT_PNG, 0, 0,
                "six bytes of a signature");
    }

    // ---------------------------------------------------------------- damaged files

    private static void brokenFiles() throws Exception {
        byte[] good = build(64, 64, 8, 6, rawRows(64, 64, 4, 0), 1);
        expectOk(good, 64, 64, "the hand-built reference PNG");
        expect(Arrays.copyOf(good, good.length - 20), NpcSkinPng.Problem.CORRUPT, 64, 64, "cut off inside IDAT");
        expect(Arrays.copyOf(good, good.length - 12), NpcSkinPng.Problem.CORRUPT, 64, 64, "no IEND");
        byte[] trailing = Arrays.copyOf(good, good.length + 5);
        expect(trailing, NpcSkinPng.Problem.CORRUPT, 64, 64, "bytes after IEND");

        byte[] flipped = good.clone();
        flipped[8 + 25 + 8 + 10] ^= 0x40;
        expect(flipped, NpcSkinPng.Problem.CORRUPT, 64, 64, "an IDAT byte changed under its checksum");

        byte[] shortStream = deflate(rawRows(64, 64, 4, 0), false);
        expect(assemble(ihdr(64, 64, 8, 6, 0), List.of(chunk("IDAT", Arrays.copyOf(shortStream, shortStream.length - 8)))),
                NpcSkinPng.Problem.CORRUPT, 64, 64, "a deflate stream that stops early, checksum fixed");
        expect(build(64, 64, 8, 6, rawRows(64, 65, 4, 0), 1), NpcSkinPng.Problem.CORRUPT, 64, 64, "one scanline too many");
        expect(build(64, 64, 8, 6, rawRows(64, 63, 4, 0), 1), NpcSkinPng.Problem.CORRUPT, 64, 64, "one scanline short");
        byte[] badFilter = rawRows(64, 64, 4, 0);
        badFilter[10 * (1 + 64 * 4)] = 9;
        expect(build(64, 64, 8, 6, badFilter, 1), NpcSkinPng.Problem.CORRUPT, 64, 64, "a scanline filter of 9");
        expect(build(64, 64, 8, 6, rawRows(64, 64, 4, 0), 1, chunk("QWUC", new byte[4])), NpcSkinPng.Problem.CORRUPT, 64, 64,
                "an unknown critical chunk");
        expect(build(64, 64, 8, 3, rawRows(64, 64, 1, 0), 1), NpcSkinPng.Problem.CORRUPT, 64, 64, "palette image without PLTE");
        expect(build(64, 64, 4, 6, rawRows(64, 64, 2, 0), 1), NpcSkinPng.Problem.CORRUPT, 64, 64, "RGBA at 4 bits a channel");
        expect(assemble(chunk("tEXt", "a".getBytes(StandardCharsets.US_ASCII)), List.of(ihdr(64, 64, 8, 6, 0),
                        chunk("IDAT", deflate(rawRows(64, 64, 4, 0), false)))),
                NpcSkinPng.Problem.CORRUPT, 0, 0, "a chunk before IHDR");
        byte[] stream = deflate(rawRows(64, 64, 4, 0), false);
        int half = stream.length / 2;
        expect(assemble(ihdr(64, 64, 8, 6, 0), List.of(chunk("IDAT", Arrays.copyOf(stream, half)),
                        chunk("tEXt", "a".getBytes(StandardCharsets.US_ASCII)),
                        chunk("IDAT", Arrays.copyOfRange(stream, half, stream.length)))),
                NpcSkinPng.Problem.CORRUPT, 64, 64, "IDAT chunks that are not consecutive");
        expect(assemble(ihdr(64, 64, 8, 6, 0), List.of(chunk("IDAT", deflate(rawRows(64, 64, 4, 0), true)))),
                NpcSkinPng.Problem.CORRUPT, 64, 64, "a deflate stream that needs a preset dictionary");
        expect(assemble(ihdr(64, 64, 8, 6, 0), List.of(chunk("IEND", new byte[0]))), NpcSkinPng.Problem.CORRUPT, 64, 64,
                "no image data at all");
        expect(build(64, 64, 8, 6, rawRows(64, 64, 4, 0), 1, chunk("IHDR", Arrays.copyOfRange(ihdr(64, 64, 8, 6, 0), 8, 21))),
                NpcSkinPng.Problem.CORRUPT, 64, 64, "a second IHDR");
    }

    // ---------------------------------------------------------------- sizes

    private static void sizeLimits() throws Exception {
        byte[] skin = imageIo(noise(64, 64, BufferedImage.TYPE_INT_ARGB));
        byte[] padded = withAncillary(skin, 30 * 1024);
        expect(padded, NpcSkinPng.Problem.TOO_LARGE, 0, 0, "a skin carrying 30 KiB of metadata, as the server sees it");
        expectInspectOk(padded, "the same file as the screen reads it before re-encoding");
        byte[] exact = withAncillary(skin, NpcSkinPng.MAX_BYTES - skin.length - 12);
        check(exact.length == NpcSkinPng.MAX_BYTES, "padded to exactly the limit: " + exact.length);
        expectOk(exact, 64, 64, "a PNG of exactly " + NpcSkinPng.MAX_BYTES + " bytes");
        byte[] over = withAncillary(skin, NpcSkinPng.MAX_BYTES - skin.length - 11);
        expect(over, NpcSkinPng.Problem.TOO_LARGE, 0, 0, "one byte over the limit");
        byte[] huge = withAncillary(skin, NpcSkinPng.MAX_SOURCE_BYTES);
        check(NpcSkinPng.inspect(huge, NpcSkinPng.MAX_SOURCE_BYTES).problem() == NpcSkinPng.Problem.TOO_LARGE,
                "more than the screen reads is too large there as well");
    }

    // ---------------------------------------------------------------- the game's own PNG writer

    /**
     * The screen re-encodes every chosen file with NativeImage.asByteArray (STB's writer). Whatever it writes for a
     * skin has to pass the server's rules, including the worst case for size: pure noise, which no compressor shrinks.
     */
    private static void gameWriter() throws Exception {
        int largest = 0;
        for (int height : new int[]{64, 32}) {
            for (int trial = 0; trial < 6; trial++) {
                try (NativeImage image = new NativeImage(64, height, false)) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < 64; x++) {
                            image.setPixelRGBA(x, y, trial == 0 ? 0 : RANDOM.nextInt());
                        }
                    }
                    byte[] png = image.asByteArray();
                    largest = Math.max(largest, png.length);
                    expectOk(png, 64, height, "NativeImage.asByteArray of a " + (trial == 0 ? "blank" : "noisy") + " 64x" + height);
                    try (NativeImage back = NativeImage.read(new java.io.ByteArrayInputStream(png))) {
                        check(back.getWidth() == 64 && back.getHeight() == height, "the game reads its own skin PNG back");
                        int x = RANDOM.nextInt(64);
                        int y = RANDOM.nextInt(height);
                        check(back.getPixelRGBA(x, y) == image.getPixelRGBA(x, y), "a pixel survives the round trip");
                    }
                }
            }
        }
        check(largest <= NpcSkinPng.MAX_BYTES, "the noisiest 64x64 skin the game writes fits the limit: " + largest
                + " of " + NpcSkinPng.MAX_BYTES + " bytes");
        // Every file an editor writes goes through the same writer, so the limit never refuses a real skin.
        for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_USHORT_GRAY}) {
            byte[] source = interlaced(noise(64, 64, type));
            try (NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(source))) {
                expectOk(image.asByteArray(), 64, 64, "re-encoded image of type " + type);
            }
        }
    }

    // ---------------------------------------------------------------- legacy skins

    /**
     * normalize() is a port of vanilla's HttpTexture.processLegacySkin; run both on the same pixels and compare all
     * 4096 results, for 64x64 and 64x32 skins, with the old hat layer both fully opaque (vanilla clears it) and not.
     */
    private static void legacyMatchesVanilla() throws Exception {
        Method process = HttpTexture.class.getDeclaredMethod("processLegacySkin", NativeImage.class);
        process.setAccessible(true);
        HttpTexture vanilla = new HttpTexture(null, "npc-skin-check",
                ResourceLocation.fromNamespaceAndPath("music_battle", "npc_skin_check"), true, null);
        int compared = 0;
        for (int height : new int[]{64, 32}) {
            for (int trial = 0; trial < 40; trial++) {
                int[] pixels = new int[64 * height];
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = RANDOM.nextInt();
                }
                if (trial % 2 == 0) {
                    // The hat layer of a 64x32 skin all opaque: the case vanilla's "Notch transparency hack" clears.
                    for (int y = 0; y < 32; y++) {
                        for (int x = 32; x < 64; x++) {
                            pixels[y * 64 + x] |= 0xFF000000;
                        }
                    }
                }
                int[] ours = NpcSkinPng.normalize(pixels, 64, height);
                NativeImage source = new NativeImage(64, height, true);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < 64; x++) {
                        source.setPixelRGBA(x, y, pixels[y * 64 + x]);
                    }
                }
                try (NativeImage result = (NativeImage) process.invoke(vanilla, source)) {
                    check(result != null && result.getWidth() == 64 && result.getHeight() == 64,
                            "vanilla turns a 64x" + height + " skin into 64x64");
                    for (int y = 0; y < 64; y++) {
                        for (int x = 0; x < 64; x++) {
                            if (result.getPixelRGBA(x, y) != ours[y * 64 + x]) {
                                throw new AssertionError("64x" + height + " trial " + trial + ": pixel " + x + "," + y
                                        + " is " + Integer.toHexString(ours[y * 64 + x]) + ", vanilla makes "
                                        + Integer.toHexString(result.getPixelRGBA(x, y)));
                            }
                        }
                    }
                    compared++;
                }
            }
        }
        check(compared == 80, "80 skins compared with vanilla pixel for pixel");
        check(NpcSkinPng.normalize(new int[64 * 64], 64, 64) != null, "normalize accepts 64x64");
        rejects(() -> NpcSkinPng.normalize(new int[64 * 48], 64, 48), "normalize refuses 64x48");
        rejects(() -> NpcSkinPng.normalize(new int[10], 64, 64), "normalize refuses a short array");
    }

    // ---------------------------------------------------------------- names

    private static void hashes() {
        String abc = NpcSkinPng.sha256("abc".getBytes(StandardCharsets.US_ASCII));
        check(abc.equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 of abc: " + abc);
        check(NpcSkinPng.isSha256(abc), "a SHA-256 is recognised");
        check(!NpcSkinPng.isSha256(abc.toUpperCase(java.util.Locale.ROOT)), "upper case is not the form textures are filed under");
        check(!NpcSkinPng.isSha256(abc.substring(1)), "63 characters is not a SHA-256");
        check(!NpcSkinPng.isSha256("../" + abc.substring(3)), "a path is not a SHA-256");
        check(!NpcSkinPng.isSha256(null), "null is not a SHA-256");
    }

    // ---------------------------------------------------------------- the upload

    private static void payloadCodec() throws Exception {
        byte[] skin = imageIo(noise(64, 64, BufferedImage.TYPE_INT_ARGB));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        RapGodSkinUploadPayload.STREAM_CODEC.encode(buffer, new RapGodSkinUploadPayload(4242, skin));
        RapGodSkinUploadPayload back = RapGodSkinUploadPayload.STREAM_CODEC.decode(buffer);
        check(back.entityId() == 4242 && Arrays.equals(back.png(), skin) && buffer.readableBytes() == 0, "a skin upload round-trips");

        RegistryFriendlyByteBuf removal = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        RapGodSkinUploadPayload.STREAM_CODEC.encode(removal, RapGodSkinUploadPayload.remove(7));
        RapGodSkinUploadPayload removed = RapGodSkinUploadPayload.STREAM_CODEC.decode(removal);
        check(removed.entityId() == 7 && removed.png().length == 0, "taking the skin off is an empty upload");

        RegistryFriendlyByteBuf hostile = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        hostile.writeVarInt(1);
        hostile.writeVarInt(NpcSkinPng.MAX_BYTES + 1);
        hostile.writeBytes(new byte[NpcSkinPng.MAX_BYTES + 1]);
        rejects(() -> RapGodSkinUploadPayload.STREAM_CODEC.decode(hostile), "the server refuses to decode an oversized skin");
        RegistryFriendlyByteBuf negative = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        negative.writeVarInt(1);
        negative.writeVarInt(-5);
        rejects(() -> RapGodSkinUploadPayload.STREAM_CODEC.decode(negative), "a negative length is refused");
        rejects(() -> RapGodSkinUploadPayload.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY),
                new RapGodSkinUploadPayload(1, new byte[NpcSkinPng.MAX_BYTES + 1])), "the client never sends an oversized skin");
    }

    /** Every reason the server can refuse a file has its own message, with the numbers it needs. */
    private static void refusals() throws Exception {
        Class<?> handler = Class.forName("ru.gora.musicbattle.server.RapGodConfigHandler");
        Method refusal = handler.getDeclaredMethod("refusal", NpcSkinPng.Result.class);
        refusal.setAccessible(true);
        String tooLarge = key(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.TOO_LARGE, 0, 0));
        String wrongSize = key(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.WRONG_SIZE, 65, 64));
        String damaged = key(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.CORRUPT, 64, 64));
        String notPng = key(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.NOT_PNG, 0, 0));
        String empty = key(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.EMPTY, 0, 0));
        check(tooLarge.equals("message.music_battle.npc.skin_file.too_large"), "too large: " + tooLarge);
        check(wrongSize.equals("message.music_battle.npc.skin_file.wrong_size"), "wrong size: " + wrongSize);
        check(damaged.equals("message.music_battle.npc.skin_file.damaged"), "damaged: " + damaged);
        check(notPng.equals("message.music_battle.npc.skin_file.not_png") && empty.equals(notPng), "not a PNG: " + notPng);
        Object[] sizeArgs = contents(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.WRONG_SIZE, 65, 64)).getArgs();
        check(Arrays.equals(sizeArgs, new Object[]{"65", "64"}), "the wrong size names the size: " + Arrays.toString(sizeArgs));
        Object[] limitArgs = contents(refusal, new NpcSkinPng.Result(NpcSkinPng.Problem.TOO_LARGE, 0, 0)).getArgs();
        check(Arrays.equals(limitArgs, new Object[]{String.valueOf(NpcSkinPng.MAX_BYTES / 1024)}),
                "the size limit is given in KiB: " + Arrays.toString(limitArgs));
    }

    private static String key(Method refusal, NpcSkinPng.Result result) throws Exception {
        return contents(refusal, result).getKey();
    }

    private static TranslatableContents contents(Method refusal, NpcSkinPng.Result result) throws Exception {
        MutableComponent component = (MutableComponent) refusal.invoke(null, result);
        return (TranslatableContents) component.getContents();
    }

    // ---------------------------------------------------------------- PNG making

    private static BufferedImage noise(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, RANDOM.nextInt());
            }
        }
        return image;
    }

    private static BufferedImage flat(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | (x / 8) * 0x100810 + (y / 8) * 0x081000);
            }
        }
        return image;
    }

    private static byte[] imageIo(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        check(ImageIO.write(image, "png", out), "ImageIO wrote a PNG");
        return out.toByteArray();
    }

    private static byte[] interlaced(BufferedImage image) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        byte[] png = out.toByteArray();
        check((png[8 + 8 + 12] & 0xFF) == 1, "ImageIO wrote the interlaced flag");
        return png;
    }

    /** Unfiltered scanlines (filter byte 0) of noise, {@code bytesPerPixel} may be fractional for sub-byte depths. */
    private static byte[] rawRows(int width, int rows, double bytesPerPixel, int filter) {
        int line = 1 + (int) Math.ceil(width * bytesPerPixel);
        byte[] raw = new byte[line * rows];
        RANDOM.nextBytes(raw);
        for (int row = 0; row < rows; row++) {
            raw[row * line] = (byte) filter;
        }
        return raw;
    }

    private static byte[] deflate(byte[] raw, boolean dictionary) {
        Deflater deflater = new Deflater();
        if (dictionary) {
            deflater.setDictionary("blocky".getBytes(StandardCharsets.US_ASCII));
        }
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] ihdr(int width, int height, int bitDepth, int colourType, int interlace) {
        byte[] data = new byte[13];
        putInt(data, 0, width);
        putInt(data, 4, height);
        data[8] = (byte) bitDepth;
        data[9] = (byte) colourType;
        data[12] = (byte) interlace;
        return chunk("IHDR", data);
    }

    /** IHDR, the extra chunks, the deflated rows split over {@code idatChunks} IDATs, IEND. */
    private static byte[] build(int width, int height, int bitDepth, int colourType, byte[] raw, int idatChunks, byte[]... extra) {
        List<byte[]> chunks = new ArrayList<>(Arrays.asList(extra));
        byte[] stream = deflate(raw, false);
        int step = (stream.length + idatChunks - 1) / Math.max(1, idatChunks);
        for (int at = 0; at < stream.length || at == 0 && stream.length == 0; at += Math.max(1, step)) {
            chunks.add(chunk("IDAT", Arrays.copyOfRange(stream, at, Math.min(stream.length, at + step))));
            if (stream.length == 0) {
                break;
            }
        }
        return assemble(ihdr(width, height, bitDepth, colourType, 0), chunks);
    }

    private static byte[] assemble(byte[] first, List<byte[]> rest) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        out.writeBytes(first);
        boolean ended = false;
        for (byte[] chunk : rest) {
            out.writeBytes(chunk);
            ended = new String(chunk, 4, 4, StandardCharsets.US_ASCII).equals("IEND");
        }
        if (!ended) {
            out.writeBytes(chunk("IEND", new byte[0]));
        }
        return out.toByteArray();
    }

    /** The PNG with one ancillary chunk of {@code dataBytes} inserted before IEND. */
    private static byte[] withAncillary(byte[] png, int dataBytes) {
        byte[] extra = chunk("zzTx", new byte[Math.max(0, dataBytes)]);
        byte[] out = new byte[png.length + extra.length];
        System.arraycopy(png, 0, out, 0, png.length - 12);
        System.arraycopy(extra, 0, out, png.length - 12, extra.length);
        System.arraycopy(png, png.length - 12, out, png.length - 12 + extra.length, 12);
        return out;
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] out = new byte[12 + data.length];
        putInt(out, 0, data.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, out, 4, 4);
        System.arraycopy(data, 0, out, 8, data.length);
        CRC32 crc = new CRC32();
        crc.update(out, 4, 4 + data.length);
        putInt(out, 8 + data.length, (int) crc.getValue());
        return out;
    }

    private static void putInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }

    // ---------------------------------------------------------------- assertions

    private static void expectOk(byte[] png, int width, int height, String what) {
        expect(png, NpcSkinPng.Problem.NONE, width, height, what);
    }

    private static void expectInspectOk(byte[] png, String what) {
        NpcSkinPng.Result result = NpcSkinPng.inspect(png, NpcSkinPng.MAX_SOURCE_BYTES);
        check(result.ok(), what + ": " + result);
    }

    private static void expect(byte[] png, NpcSkinPng.Problem problem, int width, int height, String what) {
        NpcSkinPng.Result result = NpcSkinPng.check(png);
        check(result.problem() == problem, what + ": expected " + problem + ", got " + result);
        if (problem == NpcSkinPng.Problem.NONE || problem == NpcSkinPng.Problem.WRONG_SIZE) {
            check(result.width() == width && result.height() == height, what + ": size " + result);
        }
    }

    private static void rejects(ThrowingRunnable action, String what) {
        try {
            action.run();
        } catch (Exception | AssertionError expected) {
            checks++;
            return;
        }
        throw new AssertionError(what + ": nothing was thrown");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
