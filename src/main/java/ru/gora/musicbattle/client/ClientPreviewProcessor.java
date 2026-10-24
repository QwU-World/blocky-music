package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/** Decodes common image formats and normalizes track previews to bounded PNG files. */
public final class ClientPreviewProcessor {
    public static final int MAX_PUBLISHED_DIMENSION = 1024;
    private static final long MAX_SOURCE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "wbmp");

    private ClientPreviewProcessor() {
    }

    public static boolean isSupported(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static ProcessedPreview processForPublication(Path source) throws IOException {
        long sourceBytes = Files.size(source);
        Path output = Files.createTempFile("blocky-music-preview-", ".png");
        boolean complete = false;
        try {
            Decoded decoded = decode(source, MAX_PUBLISHED_DIMENSION);
            writePng(decoded.image(), output);
            long outputBytes = Files.size(output);
            complete = true;
            return new ProcessedPreview(output, sourceBytes, outputBytes, decoded.format(), decoded.originalWidth(), decoded.originalHeight());
        } finally {
            if (!complete) Files.deleteIfExists(output);
        }
    }

    public static void normalizeToPng(Path source, Path target) throws IOException {
        Decoded decoded = decode(source, MAX_PUBLISHED_DIMENSION);
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".blocky-music-preview-", ".png");
        try {
            writePng(decoded.image(), temporary);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static NativeImage loadNativeImage(Path source) throws IOException {
        Decoded decoded = decode(source, 2048);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(decoded.image(), "png", output)) {
                throw new IOException("PNG encoder is unavailable");
            }
            try (ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray())) {
                return NativeImage.read(input);
            }
        }
    }

    private static Decoded decode(Path source, int maxDimension) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException(text("music_battle.l10n.client.011") + source);
        long sourceBytes = Files.size(source);
        if (sourceBytes <= 0L || sourceBytes > MAX_SOURCE_BYTES) {
            throw new IOException(text("music_battle.l10n.client.012"));
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) throw new IOException(text("music_battle.l10n.client.013"));
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException(text("music_battle.l10n.client.014"));
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new IOException(text("music_battle.l10n.client.015"));
                }
                int subsampling = Math.max(1, Math.max(width, height) / Math.max(1, maxDimension));
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsampling > 1) param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                if (decoded == null) throw new IOException(text("music_battle.l10n.client.016"));
                BufferedImage bounded = resize(decoded, maxDimension);
                return new Decoded(bounded, reader.getFormatName().toUpperCase(Locale.ROOT), width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage resize(BufferedImage source, int maxDimension) {
        float scale = Math.min(1.0F, maxDimension / (float) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static void writePng(BufferedImage image, Path output) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IOException("PNG encoder is unavailable");
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
    }

    public record ProcessedPreview(Path path, long sourceBytes, long outputBytes, String sourceFormat,
                                   int originalWidth, int originalHeight) {
    }

    private record Decoded(BufferedImage image, String format, int originalWidth, int originalHeight) {
    }
}
