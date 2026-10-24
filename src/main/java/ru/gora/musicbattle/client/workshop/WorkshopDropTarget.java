package ru.gora.musicbattle.client.workshop;

/**
 * A screen that handles dropped files itself by overriding {@code onFilesDrop} and passing {@code .blockymap} files to
 * {@link WorkshopDrops#onFilesDropped}. For every other screen, and in the world with no screen open, the mouse
 * handler mixin routes {@code .blockymap} drops to the same place (spec 06 section 5).
 */
public interface WorkshopDropTarget {
}
