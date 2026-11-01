package ru.gora.musicbattle.client.workshop;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * The system file picker for the Workshop: importing a {@code .blockymap}, choosing the
 * audio file of a bring-your-own-audio set, and picking a cover for an upload. Uses LWJGL's tinyfd, which Minecraft
 * already ships.
 *
 * <p>A dialog can end four ways, and only one of them is the player saying no: Cancel. A folder or a shortcut, a file
 * of the wrong kind, and a dialog that could not open at all are refusals of what the player did, and each one says
 * why through {@link WorkshopDrops#pickRefused}; a picker that answered all four with silence read as a dead button.
 *
 * <p>Must be called on the render thread: on macOS a native dialog may only be opened from the main thread. The game
 * is frozen while the dialog is open, as with any modal system dialog.
 */
public final class WorkshopFiles {
    /**
     * What a dialog gave back: the file, or the reason the choice cannot be used, or neither when the player cancelled.
     */
    public record Choice(Path file, WorkshopDrops.Refusal refusal) {
        public static final Choice CANCELLED = new Choice(null, null);

        public static Choice refused(WorkshopDrops.Refusal reason) {
            return new Choice(null, reason);
        }

        public boolean cancelled() {
            return file == null && refusal == null;
        }
    }

    private static final String[] PACKAGE = {"*.blockymap"};
    /**
     * The song for a beatmap published without one: Ogg as it is, and everything the mod's own converter reads, the same
     * list as the chart editor's Audio button (InstalledActions converts anything that is not an Ogg the game plays).
     */
    private static final String[] AUDIO = {"*.ogg", "*.mp3", "*.mp2", "*.wav", "*.flac", "*.m4a", "*.aac", "*.mp4", "*.mov",
            "*.3gp", "*.m4r", "*.m4b", "*.opus", "*.oga", "*.webm", "*.weba", "*.mka", "*.mkv", "*.ts", "*.m2ts", "*.mts"};
    private static final String[] IMAGE = {"*.png", "*.jpg", "*.jpeg", "*.webp"};

    private WorkshopFiles() {
    }

    public static Choice pickPackage() {
        return pick(WorkshopStrings.KEY + "file.package", PACKAGE, WorkshopStrings.KEY + "file.package.filter");
    }

    public static Choice pickAudio() {
        return pick(WorkshopStrings.KEY + "file.audio", AUDIO, WorkshopStrings.KEY + "file.audio.filter");
    }

    public static Choice pickImage() {
        return pick(WorkshopStrings.KEY + "file.cover", IMAGE, WorkshopStrings.KEY + "file.cover.filter");
    }

    /** The package dialog for a caller that only wants a file: a refusal is told to the player, a cancel is not. */
    public static Path choosePackage() {
        return told(pickPackage());
    }

    /** The song dialog for a caller that only wants a file: a refusal is told to the player, a cancel is not. */
    public static Path chooseAudio() {
        return told(pickAudio());
    }

    /** The cover dialog for a caller that only wants a file: a refusal is told to the player, a cancel is not. */
    public static Path chooseImage() {
        return told(pickImage());
    }

    private static Path told(Choice choice) {
        if (choice.refusal() != null) {
            WorkshopDrops.pickRefused(choice.refusal());
        }
        return choice.file();
    }

    private static Choice pick(String titleKey, String[] patterns, String filterKey) {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("file dialogs open on the render thread only");
        }
        String picked;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(patterns.length);
            for (String pattern : patterns) {
                filters.put(stack.UTF8(pattern));
            }
            filters.flip();
            String start = MusicBattleTracks.tracksRootDirectory().toString() + java.io.File.separator;
            picked = TinyFileDialogs.tinyfd_openFileDialog(Component.translatable(titleKey).getString(), start, filters,
                    Component.translatable(filterKey).getString(), false);
        } catch (RuntimeException | UnsatisfiedLinkError exception) {
            MusicBattleMod.LOGGER.warn("Workshop file dialog unavailable: {}", exception.toString());
            return Choice.refused(WorkshopDrops.Refusal.PICKER_UNAVAILABLE);
        }
        return classify(picked, patterns);
    }

    /**
     * What the dialog's answer means. tinyfd answers a Cancel with nothing at all; anything else is a choice, which is
     * used only when it is a regular file - not a folder, not a link - with one of the extensions asked for, since on
     * some systems the dialog's filter is a hint the player can step around.
     */
    static Choice classify(String picked, String[] patterns) {
        if (picked == null || picked.isBlank()) {
            return Choice.CANCELLED;
        }
        Path path;
        try {
            path = Path.of(picked.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException notAPath) {
            return Choice.refused(WorkshopDrops.Refusal.UNREADABLE);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Choice.refused(WorkshopDrops.Refusal.UNREADABLE);
        }
        String name = String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (name.endsWith(pattern.substring(1))) {
                return new Choice(path, null);
            }
        }
        return Choice.refused(WorkshopDrops.Refusal.WRONG_TYPE);
    }
}
