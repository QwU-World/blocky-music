package ru.gora.musicbattle.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.data.BattleNote;

public final class ClientOsuManiaImporter {
    /**
     * An imported map. {@code audioLeftBehind} is set when the map had a song that could not be made into OGG: the
     * notes are still here, {@code audioFile} is empty, and the editor tells the player what to do about the song.
     * {@code pendingSong} is set instead when the song still has to be converted, which only
     * {@link #importChartDeferringSong} leaves to its caller.
     */
    public record ImportedOsuChart(ClientFnfChartImporter.ImportedChart chart, String audioFile, String previewFile,
                                   AudioLeftBehind audioLeftBehind, PendingSong pendingSong) {
    }

    /** How long one song may take to convert. Generous, because it no longer holds up anything the player sees. */
    private static final int SONG_CONVERSION_TIMEOUT_SECONDS = 120;

    /**
     * A map's song that is not OGG, already copied into music_battle_raw as it was, and still to be converted.
     *
     * <p>The conversion used to run inside the Import click, on the render thread, so the game froze for as long as
     * ffmpeg took - up to its 30-second timeout. The notes do not need the song, so the import now hands this back
     * and the editor converts it on a worker while the player already edits the notes.
     *
     * @param original the song as the map shipped it, in music_battle_raw
     * @param target where the OGG goes, next to it
     * @param sourceExtension what the song was, such as {@code .mp3}
     */
    public record PendingSong(Path original, Path target, String sourceExtension) {
        /**
         * Runs ffmpeg and waits for it, so never on the render thread. On success the original is removed, as it
         * was when the conversion happened during the import; on failure it stays where the Audio button finds it.
         */
        public SongResult convert() {
            try {
                ClientFfmpeg.convertToVorbis(original, target, null, SONG_CONVERSION_TIMEOUT_SECONDS);
            } catch (IOException exception) {
                // Almost every osu! map ships MP3, and most players have no ffmpeg. The notes are what the import is
                // for, so they stay; the song is kept as it was, where the Audio button will find it.
                boolean missing = exception instanceof ClientFfmpeg.MissingException;
                if (!missing) {
                    ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Blocky Music could not convert the song of an osu! map.", exception);
                }
                String kept = Files.isRegularFile(original) ? original.getFileName().toString() : "";
                return new SongResult("", new AudioLeftBehind(kept, sourceExtension, missing,
                        missing ? "" : firstLine(MusicBattleTracks.safeMessage(exception))));
            }
            try {
                Files.deleteIfExists(original);
            } catch (IOException exception) {
                ru.gora.musicbattle.MusicBattleMod.LOGGER.debug("Blocky Music kept the converted osu! song's original.", exception);
            }
            return new SongResult(target.getFileName().toString(), null);
        }
    }

    /** What became of a {@link PendingSong}: the OGG's name in music_battle_raw, or why there is none. */
    public record SongResult(String audioFile, AudioLeftBehind leftBehind) {
    }

    /**
     * A song that came with the map but is not OGG, the only format Minecraft plays, and could not be converted.
     *
     * @param keptFile the original, written into music_battle_raw under this name so the Audio button can offer it
     *     again once ffmpeg is installed; empty if even that failed
     * @param sourceExtension what the song was, such as {@code .mp3}
     * @param ffmpegMissing whether the reason was that this computer has no ffmpeg, rather than ffmpeg failing
     * @param reason what ffmpeg reported when it did run, for the status line
     */
    public record AudioLeftBehind(String keptFile, String sourceExtension, boolean ffmpegMissing, String reason) {
    }

    private record AudioCopy(String file, AudioLeftBehind leftBehind, PendingSong pending) {
        private static final AudioCopy NONE = new AudioCopy("", null, null);
    }

    public record OsuDifficulty(int index, String title, int bpm, int noteCount, String audioFile, float order) {
    }

    private record ParsedOsuChart(ClientFnfChartImporter.ImportedChart chart, String audioFile, String previewFile) {
    }

    private record TimingPoint(int timeMs, double beatLength, double sliderVelocity) {
    }

    private static final int HOLD_RELEASE_BUFFER_TICKS = 3;
    private static final int STANDARD_PATTERN_LENGTH = 8;
    private static final int[][] STANDARD_LANE_PATTERNS = {
            {0, 1, 2, 3, 2, 1, 0, 2},
            {3, 2, 1, 0, 1, 2, 3, 1},
            {0, 2, 1, 3, 2, 0, 3, 1},
            {1, 0, 2, 3, 1, 3, 2, 0},
            {0, 3, 1, 2, 3, 0, 2, 1},
            {2, 1, 3, 0, 2, 0, 1, 3}
    };

    private static final class StandardOsuState {
        private int index;
        private int lastX = -1;
        private int lastY = -1;
        private int lastTimeMs = -1;
        private int lastTick = -1;
        private int lastPatternTimeMs = -1;
        private int lastLane = -1;
        private int previousLane = -1;
        private int patternIndex;
        private int patternStep = STANDARD_PATTERN_LENGTH;
        private int patternRotation;
        private boolean patternMirrored;
        private final int[] laneUsage = new int[4];
    }

    private ClientOsuManiaImporter() {
    }

    public static ClientFnfChartImporter.ImportedChart importChart(Path path) throws IOException {
        return importChartWithAudio(path).chart();
    }

    public static ImportedOsuChart importChartWithAudio(Path path) throws IOException {
        return importChartWithAudio(path, -1);
    }

    /**
     * Imports a map and converts its song before returning, waiting for ffmpeg. For callers that are already off the
     * render thread; the editor uses {@link #importChartDeferringSong} and converts in the background.
     */
    public static ImportedOsuChart importChartWithAudio(Path path, int difficultyIndex) throws IOException {
        ImportedOsuChart imported = importChartDeferringSong(path, difficultyIndex);
        if (imported.pendingSong() == null) {
            return imported;
        }
        SongResult song = imported.pendingSong().convert();
        return new ImportedOsuChart(imported.chart(), song.audioFile(), imported.previewFile(), song.leftBehind(), null);
    }

    /**
     * Imports a map without waiting for ffmpeg: a song that is not OGG comes back as {@code pendingSong}, already
     * copied into music_battle_raw, for the caller to {@link PendingSong#convert} off the render thread.
     */
    public static ImportedOsuChart importChartDeferringSong(Path path, int difficultyIndex) throws IOException {
        String lower = path.getFileName().toString().toLowerCase();
        if (lower.endsWith(".osz")) {
            return importOsz(path, difficultyIndex);
        }
        if (lower.endsWith(".osu")) {
            ParsedOsuChart parsed = parseOsu(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
            AudioCopy audio = copySiblingAudio(path, parsed.audioFile(), parsed.chart().id());
            String previewFile = copySiblingPreview(path, parsed.previewFile(), parsed.chart().id());
            return new ImportedOsuChart(parsed.chart(), audio.file(), previewFile, audio.leftBehind(), audio.pending());
        }
        throw new IOException("Import supports .json, .osu and .osz");
    }

    public static List<OsuDifficulty> listDifficulties(Path path) throws IOException {
        String lower = path.getFileName().toString().toLowerCase();
        if (lower.endsWith(".osu")) {
            ParsedOsuChart parsed = parseOsu(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
            return List.of(new OsuDifficulty(0, parsed.chart().title(), parsed.chart().bpm(), parsed.chart().notes().size(), parsed.audioFile(), difficultyOrder(parsed.chart())));
        }
        if (!lower.endsWith(".osz")) {
            return List.of();
        }

        List<OsuDifficulty> difficulties = new ArrayList<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().toList();
            int index = 0;
            for (ZipEntry entry : entries) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".osu")) {
                    continue;
                }
                String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                try {
                    ParsedOsuChart chart = parseOsu(baseName(entry.getName()), text);
                    difficulties.add(new OsuDifficulty(index, chart.chart().title(), chart.chart().bpm(), chart.chart().notes().size(), chart.audioFile(), difficultyOrder(chart.chart())));
                    index++;
                } catch (IOException ignored) {
                }
            }
        }
        difficulties.sort(Comparator.comparingDouble(OsuDifficulty::order)
                .thenComparing(OsuDifficulty::title, String.CASE_INSENSITIVE_ORDER));
        return difficulties;
    }

    private static ImportedOsuChart importOsz(Path path, int difficultyIndex) throws IOException {
        ParsedOsuChart first = null;
        String firstEntryName = "";
        ParsedOsuChart selected = null;
        String selectedEntryName = "";
        int maniaIndex = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().toList();
            for (ZipEntry entry : entries) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".osu")) {
                    continue;
                }
                String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                try {
                    ParsedOsuChart chart = parseOsu(baseName(entry.getName()), text);
                    if (first == null) {
                        first = chart;
                        firstEntryName = entry.getName();
                    }
                    if (maniaIndex == difficultyIndex) {
                        selected = chart;
                        selectedEntryName = entry.getName();
                        break;
                    }
                    maniaIndex++;
                    if (difficultyIndex < 0 && !chart.chart().notes().isEmpty()) {
                        AudioCopy audio = copyZipAudio(zip, entries, entry.getName(), chart.audioFile(), chart.chart().id());
                        String previewFile = copyZipPreview(zip, entries, entry.getName(), chart.previewFile(), chart.chart().id());
                        return new ImportedOsuChart(chart.chart(), audio.file(), previewFile, audio.leftBehind(), audio.pending());
                    }
                } catch (IOException ignored) {
                }
            }
            if (selected != null) {
                AudioCopy audio = copyZipAudio(zip, entries, selectedEntryName, selected.audioFile(), selected.chart().id());
                String previewFile = copyZipPreview(zip, entries, selectedEntryName, selected.previewFile(), selected.chart().id());
                return new ImportedOsuChart(selected.chart(), audio.file(), previewFile, audio.leftBehind(), audio.pending());
            }
        }
        if (first != null) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                AudioCopy audio = copyZipAudio(zip, zip.stream().toList(), firstEntryName, first.audioFile(), first.chart().id());
                String previewFile = copyZipPreview(zip, zip.stream().toList(), firstEntryName, first.previewFile(), first.chart().id());
                return new ImportedOsuChart(first.chart(), audio.file(), previewFile, audio.leftBehind(), audio.pending());
            }
        }
        throw new IOException("No .osu chart found in " + path.getFileName());
    }

    private static ParsedOsuChart parseOsu(String fileName, String text) throws IOException {
        int mode = 0;
        int keys = 4;
        int bpm = 120;
        float sliderMultiplier = 1.4F;
        String title = stripExtension(fileName);
        String version = "";
        String audioFile = "";
        String previewFile = "";
        String section = "";
        List<BattleNote> notes = new ArrayList<>();
        List<TimingPoint> timingPoints = new ArrayList<>();
        StandardOsuState standardState = new StandardOsuState();

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            if ("[General]".equals(section) && line.startsWith("Mode:")) {
                mode = parseInt(line.substring(5).trim(), 0);
            } else if ("[General]".equals(section) && line.startsWith("AudioFilename:")) {
                audioFile = line.substring(14).trim();
            } else if ("[Metadata]".equals(section)) {
                if (line.startsWith("Title:")) {
                    title = line.substring(6).trim();
                } else if (line.startsWith("Version:")) {
                    version = line.substring(8).trim();
                }
            } else if ("[Difficulty]".equals(section) && line.startsWith("CircleSize:")) {
                keys = Math.max(1, Math.round(parseFloat(line.substring(11).trim(), 4.0F)));
            } else if ("[Difficulty]".equals(section) && line.startsWith("SliderMultiplier:")) {
                sliderMultiplier = Math.max(0.1F, parseFloat(line.substring(17).trim(), 1.4F));
            } else if ("[Events]".equals(section) && previewFile.isBlank() && line.contains("\"")) {
                int firstQuote = line.indexOf('"');
                int secondQuote = line.indexOf('"', firstQuote + 1);
                if (firstQuote >= 0 && secondQuote > firstQuote) {
                    String candidate = line.substring(firstQuote + 1, secondQuote).trim();
                    String lower = candidate.toLowerCase();
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        previewFile = candidate;
                    }
                }
            } else if ("[TimingPoints]".equals(section)) {
                String[] parts = line.split(",");
                if (parts.length > 1) {
                    double beatLength = parseDouble(parts[1], 0.0D);
                    if (beatLength > 0.0D && bpm == 120) {
                        bpm = (int) Math.round(60000.0D / beatLength);
                    }
                    int timeMs = parseInt(parts[0], 0);
                    double sliderVelocity = beatLength < 0.0D ? Math.max(0.1D, -100.0D / beatLength) : 1.0D;
                    timingPoints.add(new TimingPoint(timeMs, beatLength, sliderVelocity));
                }
            } else if ("[HitObjects]".equals(section)) {
                String[] parts = line.split(",");
                if (parts.length < 5) {
                    continue;
                }
                int x = parseInt(parts[0], 0);
                int y = parseInt(parts[1], 0);
                int timeMs = parseInt(parts[2], 0);
                int type = parseInt(parts[3], 0);
                int owner = 0;
                int lane = 0;
                if (mode == 3) {
                    int rawLane = Math.max(0, Math.min(keys - 1, (int) Math.floor(x * keys / 512.0D)));
                    owner = rawLane / 4;
                    lane = rawLane % 4;
                }
                int tick = Math.max(0, Math.round(timeMs / 50.0F));
                int duration = 0;
                if (mode == 3 && (type & 128) != 0 && parts.length > 5) {
                    String end = parts[5].split(":")[0];
                    duration = Math.max(0, Math.round((parseInt(end, timeMs) - timeMs) / 50.0F));
                } else if (mode != 3 && (type & 8) != 0 && parts.length > 5) {
                    String end = parts[5].split(":")[0];
                    duration = Math.max(0, Math.round((parseInt(end, timeMs) - timeMs) / 50.0F));
                }
                if (mode == 3) {
                    notes.add(new BattleNote(Math.min(owner, 1), lane, tick, duration));
                } else {
                    addStandardOsuObject(notes, parts, type, x, y, timeMs, tick, sliderMultiplier, timingPoints, standardState);
                }
            }
        }

        timingPoints.sort(Comparator.comparingInt(TimingPoint::timeMs));
        notes.sort(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::owner).thenComparingInt(BattleNote::lane));
        String fullTitle = version.isBlank() ? title : title + " [" + version + "]";
        String sourceMode = mode == 3 ? "osu!mania" : "osu!";
        ClientFnfChartImporter.ImportedChart chart = new ClientFnfChartImporter.ImportedChart(safeId(fullTitle), fullTitle, "Imported from " + sourceMode + ": " + fileName, Math.max(20, Math.min(400, bpm)), List.copyOf(notes));
        return new ParsedOsuChart(chart, audioFile, previewFile);
    }

    private static void addStandardOsuObject(List<BattleNote> notes, String[] parts, int type, int x, int y, int timeMs, int tick, float sliderMultiplier, List<TimingPoint> timingPoints, StandardOsuState state) {
        int previousX = state.lastX;
        int previousY = state.lastY;
        int previousTimeMs = state.lastTimeMs;
        int previousTick = state.lastTick;
        int objectIndex = state.index++;
        state.lastX = x;
        state.lastY = y;
        state.lastTimeMs = timeMs;
        state.lastTick = tick;

        if ((type & 8) != 0 && parts.length > 5) {
            int endMs = parseInt(parts[5].split(":")[0], timeMs);
            int duration = Math.max(0, Math.round((endMs - timeMs) / 50.0F));
            int lane = chooseStandardLane(notes, state, x, y, timeMs, tick, duration, objectIndex);
            if (lane < 0) {
                return;
            }
            addUniqueNote(notes, 0, lane, tick, duration >= 6 ? duration : 0);
            int beatTicks = Math.max(3, Math.round((float) currentBeatLength(timingPoints, timeMs) / 50.0F / 2.0F));
            int generated = 0;
            for (int extraTick = tick + beatTicks; extraTick < tick + duration && generated < 64; extraTick += beatTicks, generated++) {
                int extraLane = chooseGeneratedLane(notes, state, extraTick, 0, objectIndex * 67 + generated);
                if (extraLane >= 0) {
                    addUniqueNote(notes, 0, extraLane, extraTick, 0);
                }
            }
            return;
        }

        if ((type & 2) != 0 && parts.length > 7) {
            int durationMs = estimateSliderDurationMs(parts, timeMs, sliderMultiplier, timingPoints);
            int duration = Math.max(0, Math.round(durationMs / 50.0F));
            int lane = chooseStandardLane(notes, state, x, y, timeMs, tick, duration, objectIndex);
            if (lane < 0) {
                return;
            }
            addUniqueNote(notes, 0, lane, tick, duration);
            int repeats = Math.max(1, parseInt(parts[6], 1));
            if (duration >= 4 && shouldChordStandardObject(previousX, previousY, previousTimeMs, x, y, timeMs, type, objectIndex)) {
                int secondaryLane = chooseChordLane(notes, state, lane, x, y, tick, Math.max(2, Math.min(duration, 8)), objectIndex);
                if (secondaryLane >= 0) {
                    addUniqueNote(notes, 0, secondaryLane, tick, Math.max(2, Math.min(duration, 8)));
                }
            }
            if (duration > 0) {
                int segmentTicks = Math.max(2, duration / Math.max(1, repeats));
                for (int repeat = 1; repeat <= repeats; repeat++) {
                    int repeatTick = tick + Math.min(duration, segmentTicks * repeat);
                    int repeatLane = chooseGeneratedLane(notes, state, repeatTick, 0, objectIndex * 31 + repeat);
                    if (repeatLane >= 0) {
                        addUniqueNote(notes, 0, repeatLane, repeatTick, 0);
                    }
                }
                int beatTicks = Math.max(3, Math.round((float) currentBeatLength(timingPoints, timeMs) / 50.0F / 2.0F));
                int generated = 0;
                for (int extraTick = tick + beatTicks; extraTick < tick + duration && generated < 48; extraTick += beatTicks, generated++) {
                    int extraLane = chooseGeneratedLane(notes, state, extraTick, 0, objectIndex * 97 + generated);
                    if (extraLane >= 0) {
                        addUniqueNote(notes, 0, extraLane, extraTick, 0);
                    }
                }
            }
            return;
        }

        if (previousTimeMs >= 0 && timeMs - previousTimeMs <= 170 && previousTick >= 0 && tick - previousTick >= 2 && objectIndex % 2 == 0) {
            int bridgeTick = previousTick + Math.max(1, (tick - previousTick) / 2);
            int bridgeX = (previousX + x) / 2;
            int bridgeY = (previousY + y) / 2;
            int bridgeLane = chooseStandardLane(notes, state, bridgeX, bridgeY, bridgeTick * 50, bridgeTick, 0, objectIndex * 17);
            if (bridgeLane >= 0) {
                addUniqueNote(notes, 0, bridgeLane, bridgeTick, 0);
            }
        }

        int lane = chooseStandardLane(notes, state, x, y, timeMs, tick, 0, objectIndex);
        if (lane < 0) {
            return;
        }
        addUniqueNote(notes, 0, lane, tick, 0);
        if (shouldChordStandardObject(previousX, previousY, previousTimeMs, x, y, timeMs, type, objectIndex)) {
            int secondaryLane = chooseChordLane(notes, state, lane, x, y, tick, 0, objectIndex);
            if (secondaryLane >= 0) {
                addUniqueNote(notes, 0, secondaryLane, tick, 0);
            }
        }
    }

    private static float difficultyOrder(ClientFnfChartImporter.ImportedChart chart) {
        String lower = chart.title().toLowerCase(java.util.Locale.ROOT);
        float namedRank = 50.0F;
        if (lower.contains("beginner") || lower.contains("easy")) {
            namedRank = 10.0F;
        } else if (lower.contains("normal")) {
            namedRank = 20.0F;
        } else if (lower.contains("hard")) {
            namedRank = 30.0F;
        } else if (lower.contains("insane") || lower.contains("expert")) {
            namedRank = 40.0F;
        } else if (lower.contains("lunatic") || lower.contains("extra") || lower.contains("master")) {
            namedRank = 50.0F;
        }
        int lastTick = chart.notes().stream().mapToInt(note -> note.tick() + note.durationTicks()).max().orElse(20);
        float density = chart.notes().size() * 1200.0F / Math.max(20, lastTick);
        float holdWeight = (float) chart.notes().stream().filter(note -> note.durationTicks() > 0).count() / Math.max(1, chart.notes().size());
        return namedRank * 1000.0F + density * 10.0F + holdWeight * 100.0F + chart.bpm() * 0.05F;
    }

    private static boolean shouldChordStandardObject(int previousX, int previousY, int previousTimeMs, int x, int y, int timeMs, int type, int objectIndex) {
        if ((type & 4) != 0) {
            return true;
        }
        if (previousTimeMs < 0) {
            return objectIndex % 9 == 0;
        }
        int gap = timeMs - previousTimeMs;
        double distance = Math.hypot(x - previousX, y - previousY);
        if (gap <= 160 && distance >= 100.0D) {
            return true;
        }
        if (gap <= 320 && distance >= 220.0D) {
            return true;
        }
        return objectIndex % 12 == 0 && distance >= 70.0D;
    }

    private static int chooseStandardLane(List<BattleNote> notes, StandardOsuState state, int x, int y, int timeMs, int tick, int duration, int seedSalt) {
        int gap = state.lastPatternTimeMs < 0 ? Integer.MAX_VALUE : Math.max(0, timeMs - state.lastPatternTimeMs);
        if (state.patternStep >= STANDARD_PATTERN_LENGTH || gap >= 900) {
            int seed = mixStandardSeed(x, y, timeMs, state.index + seedSalt);
            state.patternIndex = Math.floorMod(seed, STANDARD_LANE_PATTERNS.length);
            state.patternRotation = Math.floorMod(seed >>> 5, 4);
            state.patternMirrored = (seed & 0x400) != 0;
            state.patternStep = 0;
        }

        int[] pattern = STANDARD_LANE_PATTERNS[state.patternIndex];
        int rawPatternLane = pattern[state.patternStep++ % pattern.length];
        int patternLane = transformPatternLane(rawPatternLane, state.patternRotation, state.patternMirrored);
        int spatialLane = MthClamp((int) Math.floor(x * 4.0D / 512.0D), 0, 3);
        int verticalLane = MthClamp((int) Math.floor(y * 4.0D / 384.0D), 0, 3);
        int minUsage = Math.min(Math.min(state.laneUsage[0], state.laneUsage[1]), Math.min(state.laneUsage[2], state.laneUsage[3]));
        int bestLane = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int lane = 0; lane < 4; lane++) {
            if (!laneAvailable(notes, lane, tick, duration)) {
                continue;
            }
            int score = circularLaneDistance(lane, patternLane) * 8;
            score += (state.laneUsage[lane] - minUsage) * 7;
            if (lane == state.lastLane) {
                score += gap < 700 ? 38 : 14;
            }
            if (gap <= 210 && lane == state.previousLane && lane != state.lastLane) {
                score -= 3;
            }
            if (lane == spatialLane) {
                score -= 3;
            }
            if (lane == verticalLane) {
                score -= 1;
            }
            score += Math.floorMod(mixStandardSeed(x + lane * 29, y, timeMs, seedSalt), 3);
            if (score < bestScore) {
                bestScore = score;
                bestLane = lane;
            }
        }
        if (bestLane >= 0) {
            state.previousLane = state.lastLane;
            state.lastLane = bestLane;
            state.lastPatternTimeMs = timeMs;
            state.laneUsage[bestLane]++;
        }
        return bestLane;
    }

    private static int chooseGeneratedLane(List<BattleNote> notes, StandardOsuState state, int tick, int duration, int seedSalt) {
        int seed = mixStandardSeed(tick * 7, seedSalt * 3, tick * 50, state.index + seedSalt);
        int[] pattern = STANDARD_LANE_PATTERNS[Math.floorMod(seed >>> 3, STANDARD_LANE_PATTERNS.length)];
        int preferred = transformPatternLane(pattern[Math.floorMod(seedSalt, pattern.length)], Math.floorMod(seed >>> 8, 4), (seed & 1) != 0);
        int minUsage = Math.min(Math.min(state.laneUsage[0], state.laneUsage[1]), Math.min(state.laneUsage[2], state.laneUsage[3]));
        int bestLane = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int lane = 0; lane < 4; lane++) {
            if (!laneAvailable(notes, lane, tick, duration)) {
                continue;
            }
            int score = circularLaneDistance(lane, preferred) * 6 + (state.laneUsage[lane] - minUsage) * 8;
            score += Math.floorMod(seed + lane * 13, 4);
            if (score < bestScore) {
                bestScore = score;
                bestLane = lane;
            }
        }
        if (bestLane >= 0) {
            state.laneUsage[bestLane]++;
        }
        return bestLane;
    }

    private static int chooseChordLane(List<BattleNote> notes, StandardOsuState state, int primaryLane, int x, int y, int tick, int duration, int seedSalt) {
        int spatialLane = MthClamp((int) Math.floor(y * 4.0D / 384.0D), 0, 3);
        int[] candidates = {
                3 - primaryLane,
                spatialLane,
                Math.floorMod(primaryLane + 2, 4),
                Math.floorMod(primaryLane + 1 + Math.floorMod(mixStandardSeed(x, y, tick * 50, seedSalt), 2), 4)
        };
        int bestLane = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int order = 0; order < candidates.length; order++) {
            int lane = candidates[order];
            if (lane == primaryLane || !laneAvailable(notes, lane, tick, duration)) {
                continue;
            }
            int score = order * 4 + state.laneUsage[lane] * 3;
            if (score < bestScore) {
                bestScore = score;
                bestLane = lane;
            }
        }
        if (bestLane >= 0) {
            state.laneUsage[bestLane]++;
        }
        return bestLane;
    }

    private static boolean laneAvailable(List<BattleNote> notes, int lane, int tick, int duration) {
        int endTick = tick + Math.max(0, duration);
        for (BattleNote note : notes) {
            if (note.owner() != 0 || note.lane() != lane) {
                continue;
            }
            if (note.tick() == tick) {
                return false;
            }
            if (duration <= 0) {
                if (note.durationTicks() > 0 && tick > note.tick()
                        && tick <= note.tick() + note.durationTicks() + HOLD_RELEASE_BUFFER_TICKS) {
                    return false;
                }
                continue;
            }
            if (note.durationTicks() > 0) {
                int noteEnd = note.tick() + note.durationTicks() + HOLD_RELEASE_BUFFER_TICKS;
                if (tick <= noteEnd && endTick + HOLD_RELEASE_BUFFER_TICKS >= note.tick()) {
                    return false;
                }
            } else if (note.tick() > tick && note.tick() <= endTick + HOLD_RELEASE_BUFFER_TICKS) {
                return false;
            }
        }
        return true;
    }

    private static int transformPatternLane(int lane, int rotation, boolean mirrored) {
        int transformed = mirrored ? 3 - lane : lane;
        return Math.floorMod(transformed + rotation, 4);
    }

    private static int circularLaneDistance(int first, int second) {
        int distance = Math.abs(first - second);
        return Math.min(distance, 4 - distance);
    }

    private static int mixStandardSeed(int x, int y, int timeMs, int salt) {
        int value = x * 0x45D9F3B ^ y * 0x119DE1F3 ^ timeMs * 0x3449 ^ salt * 0x27D4EB2D;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        return value ^ value >>> 16;
    }

    private static void addUniqueNote(List<BattleNote> notes, int owner, int lane, int tick, int duration) {
        int safeLane = MthClamp(lane, 0, 3);
        int safeTick = Math.max(0, tick);
        int safeDuration = Math.max(0, duration);
        if (safeDuration <= 0 && overlapsExistingHold(notes, owner, safeLane, safeTick)) {
            return;
        }
        if (safeDuration > 0 && overlapsExistingHoldRange(notes, owner, safeLane, safeTick, safeTick + safeDuration)) {
            return;
        }
        if (safeDuration > 0) {
            notes.removeIf(note -> note.owner() == owner
                    && note.lane() == safeLane
                    && note.durationTicks() <= 0
                    && note.tick() > safeTick
                    && note.tick() <= safeTick + safeDuration + HOLD_RELEASE_BUFFER_TICKS);
        }
        for (BattleNote note : notes) {
            if (note.owner() == owner && note.lane() == safeLane && note.tick() == safeTick) {
                return;
            }
        }
        notes.add(new BattleNote(owner, safeLane, safeTick, safeDuration));
    }

    private static boolean overlapsExistingHold(List<BattleNote> notes, int owner, int lane, int tick) {
        for (BattleNote note : notes) {
            if (note.owner() == owner && note.lane() == lane && note.durationTicks() > 0
                    && tick > note.tick()
                    && tick <= note.tick() + note.durationTicks() + HOLD_RELEASE_BUFFER_TICKS) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsExistingHoldRange(List<BattleNote> notes, int owner, int lane, int startTick, int endTick) {
        for (BattleNote note : notes) {
            if (note.owner() != owner || note.lane() != lane || note.durationTicks() <= 0) {
                continue;
            }
            int existingStart = note.tick();
            int existingEnd = existingStart + note.durationTicks() + HOLD_RELEASE_BUFFER_TICKS;
            if (startTick <= existingEnd && endTick + HOLD_RELEASE_BUFFER_TICKS >= existingStart) {
                return true;
            }
        }
        return false;
    }

    private static int MthClamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int estimateSliderDurationMs(String[] parts, int timeMs, float sliderMultiplier, List<TimingPoint> timingPoints) {
        int repeats = Math.max(1, parseInt(parts[6], 1));
        double pixelLength = parseDouble(parts[7], 0.0D);
        if (pixelLength <= 0.0D) {
            return 0;
        }
        double beatLength = currentBeatLength(timingPoints, timeMs);
        double sliderVelocity = currentSliderVelocity(timingPoints, timeMs);
        double durationMs = pixelLength * repeats * beatLength / Math.max(1.0D, sliderMultiplier * 100.0D * sliderVelocity);
        return Math.max(0, (int) Math.round(durationMs));
    }

    private static double currentBeatLength(List<TimingPoint> timingPoints, int timeMs) {
        double beatLength = 500.0D;
        for (TimingPoint point : timingPoints) {
            if (point.timeMs() > timeMs) {
                break;
            }
            if (point.beatLength() > 0.0D) {
                beatLength = point.beatLength();
            }
        }
        return beatLength;
    }

    private static double currentSliderVelocity(List<TimingPoint> timingPoints, int timeMs) {
        double sliderVelocity = 1.0D;
        for (TimingPoint point : timingPoints) {
            if (point.timeMs() > timeMs) {
                break;
            }
            if (point.beatLength() > 0.0D) {
                sliderVelocity = 1.0D;
            } else {
                sliderVelocity = point.sliderVelocity();
            }
        }
        return sliderVelocity;
    }

    private static AudioCopy copySiblingAudio(Path osuPath, String audioFile, String chartId) throws IOException {
        Path audioPath = siblingFile(osuPath, audioFile);
        if (audioPath == null) {
            return AudioCopy.NONE;
        }
        return copyAudioBytes(Files.readAllBytes(audioPath), audioPath.getFileName().toString(), chartId);
    }

    private static AudioCopy copyZipAudio(ZipFile zip, List<? extends ZipEntry> entries, String osuEntryName, String audioFile, String chartId) throws IOException {
        if (audioFile == null || audioFile.isBlank()) {
            return AudioCopy.NONE;
        }
        ZipEntry audioEntry = findAudioEntry(entries, osuEntryName, audioFile);
        if (audioEntry == null) {
            return AudioCopy.NONE;
        }
        return copyAudioBytes(zip.getInputStream(audioEntry).readAllBytes(), baseName(audioEntry.getName()), chartId);
    }

    private static String copySiblingPreview(Path osuPath, String previewFile, String chartId) throws IOException {
        Path previewPath = siblingFile(osuPath, previewFile);
        if (previewPath == null) {
            return "";
        }
        return copyPreviewBytes(Files.readAllBytes(previewPath), previewPath.getFileName().toString(), chartId);
    }

    /**
     * A file the .osu names, looked for next to it: first where the name says, then by its bare file name. The name
     * comes from inside the map, so one the file system rejects (Windows refuses {@code ? * :} and more) is simply
     * not found rather than an exception that would abort the import.
     */
    private static Path siblingFile(Path osuPath, String name) {
        if (name == null || name.isBlank() || osuPath.getParent() == null) {
            return null;
        }
        try {
            Path candidate = osuPath.getParent().resolve(name).normalize();
            if (!Files.isRegularFile(candidate)) {
                candidate = osuPath.getParent().resolve(baseName(name)).normalize();
            }
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (java.nio.file.InvalidPathException exception) {
            return null;
        }
    }

    private static String copyZipPreview(ZipFile zip, List<? extends ZipEntry> entries, String osuEntryName, String previewFile, String chartId) throws IOException {
        if (previewFile == null || previewFile.isBlank()) {
            return "";
        }
        ZipEntry previewEntry = findAudioEntry(entries, osuEntryName, previewFile);
        if (previewEntry == null) {
            return "";
        }
        return copyPreviewBytes(zip.getInputStream(previewEntry).readAllBytes(), baseName(previewEntry.getName()), chartId);
    }

    /** The map's background as the chart's preview. A picture that will not decode is left out, never the map. */
    private static String copyPreviewBytes(byte[] bytes, String sourceName, String chartId) {
        String safeName = safeId(chartId + "_" + stripExtension(sourceName)) + ".png";
        Path target = MusicBattleTracks.rawDirectory().resolve(safeName).toAbsolutePath().normalize();
        try {
            Files.createDirectories(target.getParent());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return "";
            }
            ImageIO.write(image, "png", target.toFile());
            return target.getFileName().toString();
        } catch (IOException | RuntimeException exception) {
            // ImageIO's readers throw unchecked exceptions on damaged files as readily as checked ones.
            ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Blocky Music could not use the background of an osu! map.", exception);
            return "";
        }
    }

    private static ZipEntry findAudioEntry(List<? extends ZipEntry> entries, String osuEntryName, String audioFile) {
        String normalizedAudio = audioFile.replace('\\', '/');
        String parent = "";
        int slash = osuEntryName.replace('\\', '/').lastIndexOf('/');
        if (slash >= 0) {
            parent = osuEntryName.substring(0, slash + 1).replace('\\', '/');
        }
        String relative = parent + normalizedAudio;
        String audioName = baseName(normalizedAudio);
        for (ZipEntry entry : entries) {
            if (!entry.isDirectory() && entry.getName().replace('\\', '/').equalsIgnoreCase(relative)) {
                return entry;
            }
        }
        for (ZipEntry entry : entries) {
            if (!entry.isDirectory() && baseName(entry.getName()).equalsIgnoreCase(audioName)) {
                return entry;
            }
        }
        return null;
    }

    private static AudioCopy copyAudioBytes(byte[] bytes, String sourceName, String chartId) throws IOException {
        String sourceExtension = extension(sourceName);
        String baseName = safeId(chartId + "_" + stripExtension(sourceName));
        Path rawDirectory = MusicBattleTracks.rawDirectory().toAbsolutePath().normalize();
        Files.createDirectories(rawDirectory);
        if (!".ogg".equals(sourceExtension)) {
            // Only copied here; the conversion is the caller's, off the render thread (see PendingSong).
            Path converted = rawDirectory.resolve(baseName + ".ogg").toAbsolutePath().normalize();
            Path original = rawDirectory.resolve(baseName + sourceExtension).toAbsolutePath().normalize();
            try {
                Files.write(original, bytes);
            } catch (IOException writeFailure) {
                ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Blocky Music could not keep the song of an osu! map.", writeFailure);
                return new AudioCopy("", new AudioLeftBehind("", sourceExtension, false,
                        firstLine(MusicBattleTracks.safeMessage(writeFailure))), null);
            }
            return new AudioCopy("", null, new PendingSong(original, converted, sourceExtension));
        }
        String safeName = baseName + sourceExtension;
        Path target = rawDirectory.resolve(safeName).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return new AudioCopy(target.getFileName().toString(), null, null);
    }

    /** ffmpeg's report cut to what fits a status line; the whole of it is in the log. */
    private static String firstLine(String message) {
        String line = message == null ? "" : message.strip().lines().findFirst().orElse("");
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }

    /** The last segment of a name from inside a map or archive, by either kind of slash and without the file system. */
    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : ".ogg";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeId(String input) {
        return input == null ? "osu_mania" : input.toLowerCase().trim().replaceAll("[^a-z0-9_\\-]", "_");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
