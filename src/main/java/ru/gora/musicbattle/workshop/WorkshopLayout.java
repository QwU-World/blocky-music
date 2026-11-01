package ru.gora.musicbattle.workshop;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Names and folders of installed workshop beatmaps. Pure path arithmetic: the caller passes the
 * {@code music_battle_tracks} root (today {@code MusicBattleTracks.tracksRootDirectory()}), so nothing here
 * touches FML or the game directory. See docs/WORKSHOP.md, "Формат пакета".
 *
 * <p>An installed set is a normal track folder, readable by the existing loader without rewriting:
 * <pre>
 * music_battle_tracks/workshop/ws_42/
 *     beatmap.meta              manifest (JSON, deliberately not *.json)
 *     ws_42_hard.json           one chart per difficulty, id = file name, audio "ws_42/ws_42.ogg"
 *     ws_42.ogg                 only when the audio policy is included
 *     ws_42_preview.png         optional cover
 * </pre>
 */
public final class WorkshopLayout {
    /** Folder under {@code music_battle_tracks} with one sub-folder per installed set. Client-global, never per world. */
    public static final String INSTALLED_DIRECTORY_NAME = "workshop";
    /**
     * Sibling of the installed folder where a package is unpacked before the atomic move. It is outside
     * {@link #INSTALLED_DIRECTORY_NAME} on purpose: a catalog scan of the installed folder must never see a
     * half-written set, and a move between siblings stays on one file system.
     */
    public static final String STAGING_DIRECTORY_NAME = ".workshop_staging";
    /** A workshop package is a zip archive with this extension. */
    public static final String PACKAGE_EXTENSION = ".blockymap";
    /**
     * A one-line JSON file the QwU Workshop Helper drops in the inbox instead of a package:
     * {@code {"v":1,"setId":42,"source":"helper","at":"<ISO-8601>"}} named {@code <setId>.blockymap-request}.
     * The mod downloads the set itself, under its own network consent; the helper
     * never touches the network. See {@link ru.gora.musicbattle.workshop.api.WorkshopRequest}.
     */
    public static final String REQUEST_EXTENSION = ".blockymap-request";
    /**
     * The manifest is JSON, but its name must not end in {@code .json}: MusicBattleTracks.loadDirectory reads
     * every {@code *.json} up to depth 2 as a chart and would turn the manifest into a bogus track.
     * Same idea as Minecraft's {@code pack.mcmeta}.
     */
    public static final String MANIFEST_FILE_NAME = "beatmap.meta";
    /**
     * The site's Ed25519 signature over {@code bmw-sig-v1\n<setId>\n<revision>\n<contentSha256>\n} (raw 64 bytes).
     * The one archive entry that is allowed without a manifest declaration; not installed, and verification is a
     * later phase (docs/WORKSHOP.md).
     */
    public static final String SIGNATURE_FILE_NAME = "beatmap.sig";
    /** Every installed id starts with this, so a workshop id never collides with my_tracks or user_ ids. */
    public static final String SET_FOLDER_PREFIX = "ws_";
    /** Sibling of the installed folder holding {@code <setId>-<revision>.part} files while they download. */
    public static final String DOWNLOADS_DIRECTORY_NAME = ".workshop_downloads";
    /** Sibling of the installed folder keeping the last downloaded package of each set for a few days. */
    public static final String PACKAGES_DIRECTORY_NAME = ".workshop_packages";
    /** Folder under {@code music_battle_tracks} where a player (or the QwU installer) drops packages to install. */
    public static final String INBOX_DIRECTORY_NAME = "workshop_inbox";
    /** Sub-folder of the inbox for packages that failed validation, each with a {@code .txt} reason next to it. */
    public static final String INBOX_REJECTED_DIRECTORY_NAME = "rejected";
    /** Last installed-check answer per set, under {@code music_battle_tracks}. */
    public static final String STATE_FILE_NAME = ".workshop_state.json";
    /**
     * Offline card data written by the downloader next to an installed set: the set detail as the
     * site returned it plus {@code fetchedAt}. Undeclared by the manifest, so installs and checks ignore it; a
     * replaced revision loses it and the downloader writes it again. The installed folder is never passed to
     * MusicBattleTracks.loadDirectory, so the {@code .json} name cannot turn into a bogus track.
     */
    public static final String CARD_SIDECAR_FILE_NAME = "workshop-card.json";
    /** The site's 30-second preview clip saved next to an installed set. */
    public static final String PREVIEW_SIDECAR_FILE_NAME = "preview.ogg";
    public static final int MAX_CHART_KEY_LENGTH = 32;

    static final Pattern CHART_KEY = Pattern.compile("[a-z0-9_-]{1," + MAX_CHART_KEY_LENGTH + "}");

    private WorkshopLayout() {
    }

    public static Path installedDirectory(Path tracksRoot) {
        return tracksRoot.toAbsolutePath().normalize().resolve(INSTALLED_DIRECTORY_NAME);
    }

    public static Path stagingDirectory(Path tracksRoot) {
        return tracksRoot.toAbsolutePath().normalize().resolve(STAGING_DIRECTORY_NAME);
    }

    /** Whether a chart key is one this format allows: the check a server runs before touching a path. */
    public static boolean chartKeyValid(String key) {
        return key != null && CHART_KEY.matcher(key).matches();
    }

    /** {@code ws_<setId>}: the folder name of a set, and the prefix of its chart ids and file names. */
    public static String setFolderName(long setId) {
        if (setId <= 0) {
            throw new IllegalArgumentException("setId must be positive");
        }
        return SET_FOLDER_PREFIX + setId;
    }

    /**
     * {@code ws_<setId>_<key>}. At most 3 + 19 + 1 + 32 = 55 characters, inside the 96 the editor and
     * publication allow and the 128 the loader, the client cache and the DJ payloads read. Unambiguous:
     * set ids are digits only, so the first underscore after {@code ws_} ends the set id.
     */
    public static String chartId(long setId, String key) {
        if (key == null || !CHART_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid chart key");
        }
        return setFolderName(setId) + "_" + key;
    }

    public static String chartFileName(long setId, String key) {
        return chartId(setId, key) + ".json";
    }

    public static String audioFileName(long setId) {
        return setFolderName(setId) + ".ogg";
    }

    public static String coverFileName(long setId) {
        return setFolderName(setId) + "_preview.png";
    }

    /** The value a chart's {@code audio} or {@code preview} field must hold: relative to the installed folder. */
    public static String catalogReference(long setId, String fileName) {
        return setFolderName(setId) + "/" + fileName;
    }
}
