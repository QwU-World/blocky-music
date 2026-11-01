import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.gora.musicbattle.data.DjQueueContinuity;
import ru.gora.musicbattle.data.DjQueueContinuity.AfterSong;
import ru.gora.musicbattle.data.DjQueueContinuity.PreparedStart;

/**
 * Replays the evening the DJ booth went quiet, with the server's own decisions.
 *
 * <p>The report: "after a while the booth stops playing - I flew off to other chunks, came back later and it was
 * silent; it happened several times, I could not make it happen on purpose." The model below is the booth as the
 * server sees it on its five-tick audience pass: the list of registered booths, whether the booth's chunk is full,
 * demoted or unloaded, whether its speakers' chunk is loaded, what is playing, and what {@link DjQueueContinuity}
 * makes of that - the same decisions {@code DjPlaybackManager} asks for. Each case also runs the rule that was there
 * before, to show that the case is the reported silence and not a different one. The first case is the one a scratch
 * dedicated-server probe replayed with real chunk tickets: in that build a playing booth kept its chunk full, so the
 * chunk was demoted only after the booth had stopped by itself, and it came back with the same block entity. Since the
 * live camera pass asks hasChunkAt first, the chunk can be demoted mid-song too; the rule checked here - a demoted
 * booth stays registered and is visited again once its chunk is full - is the same either way.
 */
public final class DjContinuityChecks {
    private static final String BOOTH = "booth";
    private static int checks;

    public static void main(String[] args) throws Exception {
        decisionTable();
        boothThatStoppedWhileAway();
        unloadedChunkComesBack();
        speakersLoadAfterTheBooth();
        startWaitsForTheBooth();
        sessionPassesLeaveChunksAlone(Path.of(args.length > 0 ? args[0] : "."));
        playAfterTheEnd(Path.of(args.length > 0 ? args[0] : "."));
        System.out.println("DJ continuity checks passed: " + checks);
    }

    private static void decisionTable() {
        List<String> registered = new ArrayList<>(List.of("full", "demoted", "gone"));
        List<String> asked = new ArrayList<>();
        List<String> visit = DjQueueContinuity.boothsToVisit(registered, booth -> !booth.equals("demoted"), booth -> {
            asked.add(booth);
            return !booth.equals("gone");
        });
        check(visit.equals(List.of("full")), "only a booth in a full chunk is visited");
        check(registered.equals(List.of("full", "demoted")), "a demoted booth stays registered for when it is back");
        check(!asked.contains("demoted"), "a demoted chunk is never asked for its block entity, which would load it");
        check(DjQueueContinuity.afterSong(true, 2) == AfterSong.PLAY_NEXT,
                "the next song starts while the wired speakers are still loading");
        check(DjQueueContinuity.afterSong(true, 0) == AfterSong.REST,
                "a booth with no speakers wired at all rests at the end of the song");
        check(DjQueueContinuity.afterSong(false, 2) == AfterSong.REST, "the end of the queue rests");
        check(DjQueueContinuity.startHasSpeakers(true, 2, 0), "the booth's own start is content with wired speakers");
        check(!DjQueueContinuity.startHasSpeakers(true, 0, 0), "the booth's own start still needs a speaker somewhere");
        check(!DjQueueContinuity.startHasSpeakers(false, 2, 0),
                "a DJ's own Play still asks for a loaded speaker and hears why when there is none");
        check(DjQueueContinuity.startHasSpeakers(false, 2, 1), "a DJ's Play with a loaded speaker goes ahead");
        for (boolean wanted : new boolean[] {true, false}) {
            for (boolean ready : new boolean[] {true, false}) {
                for (boolean speakers : new boolean[] {true, false}) {
                    check(DjQueueContinuity.preparedStart(false, wanted, ready, speakers) == PreparedStart.PARK,
                            "nothing is settled about a start while its booth is unloaded");
                }
            }
        }
        check(DjQueueContinuity.preparedStart(true, false, true, true) == PreparedStart.DROP,
                "a start the booth has moved on from is dropped without a word");
        check(DjQueueContinuity.preparedStart(true, true, false, true) == PreparedStart.FAIL,
                "audio that could not be prepared is reported");
        check(DjQueueContinuity.preparedStart(true, true, true, false) == PreparedStart.FAIL,
                "a start without the speakers it needs is reported");
        check(DjQueueContinuity.preparedStart(true, true, true, true) == PreparedStart.APPLY,
                "a wanted start with its audio and speakers goes ahead");
    }

    /**
     * The reported evening. The booth plays its only song (two minutes); its DJ flies off a few chunks past the view
     * distance. In the reported build the song kept the chunk full until it ended (now it may be demoted earlier, which
     * changes nothing below); then the booth rests and the chunk is demoted, not unloaded. Five minutes later the DJ
     * comes back - the same chunk is promoted with the same block entity and no onLoad - and presses Play.
     */
    private static void boothThatStoppedWhileAway() {
        Chunk chunk = new Chunk(2405, Long.MAX_VALUE, 6000);
        Evening now = new Evening(false, chunk, chunk, false, 6020);
        Evening before = new Evening(true, chunk, chunk, false, 6020);
        now.play(9_000);
        before.play(9_000);
        check(now.restingWhenPressed && before.restingWhenPressed,
                "the song ended and the booth rested while the DJ was away, under either rule");
        check(!before.visitedAfterPlay,
                "the old rule reproduces the report: Play on the panel reaches no audience pass, the booth is silent");
        check(now.visitedAfterPlay, "the booth is visited again after Play, so its listeners are sent the song");
        check(!now.server.askedDemoted, "the pass never loads the demoted chunk to look at the booth");
    }

    /**
     * The same evening, but the DJ flies on until the chunk unloads: the block entity is removed and a new one loads
     * on the way back. Here the old rule worked too, and this is why the report could not be repeated on purpose -
     * how far the DJ flew decided it.
     */
    private static void unloadedChunkComesBack() {
        Chunk chunk = new Chunk(2405, 3000, 6000);
        Evening now = new Evening(false, chunk, chunk, false, 6020);
        Evening before = new Evening(true, chunk, chunk, false, 6020);
        now.play(9_000);
        before.play(9_000);
        check(before.visitedAfterPlay && now.visitedAfterPlay,
                "a chunk that unloads and loads again brings its booth back under either rule");
    }

    /**
     * A booth whose chunk loads afresh, as after a restart, with a song that is over by its first visit and a second
     * row queued. Its chunk is full forty ticks before the chunk its speakers stand in, which is what chunks nearest
     * the player first does when the speakers stand one chunk further out.
     */
    private static void speakersLoadAfterTheBooth() {
        Chunk booth = new Chunk(0, 0, 6000);
        Chunk speakers = new Chunk(0, 0, 6040);
        Evening now = new Evening(false, booth, speakers, true, -1);
        Evening before = new Evening(false, booth, speakers, true, -1);
        before.speakersMustBeLoaded = true;
        now.play(20_000);
        before.play(20_000);
        check(before.booth.resting, "the old rule reproduces it: the booth rests for good at the end of the song");
        check(!now.booth.resting && now.booth.playing && now.booth.songsStarted > 1,
                "the booth plays on while its speakers are still loading");
    }

    /**
     * A ends while B's audio is still being prepared, so the booth waits at the end of A for it. The player leaves
     * right then: the booth's chunk goes away before the preparation is done, and comes back much later.
     */
    private static void startWaitsForTheBooth() {
        Chunk boothChunk = new Chunk(2420, 2500, 9000);
        long preparedAt = 2460;
        boolean waiting = true;
        boolean parked = false;
        boolean playing = false;
        boolean oldDropped = false;
        for (long tick = 2400; tick <= 12_000; tick += 5) {
            boolean loaded = boothChunk.full(tick);
            if (tick == preparedAt) {
                PreparedStart fate = DjQueueContinuity.preparedStart(loaded, true, true,
                        DjQueueContinuity.startHasSpeakers(true, 2, 0));
                // Before, a completion that found the booth away dropped the waiting start with the chunk.
                oldDropped = !loaded;
                if (fate == PreparedStart.APPLY) {
                    waiting = false;
                    playing = true;
                } else if (fate == PreparedStart.PARK) {
                    parked = true;
                }
            } else if (parked && loaded) {
                // The audience pass looks at a parked start whenever the booth is loaded again.
                PreparedStart fate = DjQueueContinuity.preparedStart(true, true, true,
                        DjQueueContinuity.startHasSpeakers(true, 2, 0));
                if (fate == PreparedStart.APPLY) {
                    parked = false;
                    waiting = false;
                    playing = true;
                }
            }
        }
        check(oldDropped, "the old rule reproduces the report: the waiting start was thrown away with the chunk");
        check(playing && !waiting && !parked, "the waiting start goes ahead once the booth is loaded again");
    }

    /**
     * The passes over playing booths never load a chunk or renew its ticket. Asking hasChunkAt first was not enough:
     * getBlockEntity goes through getChunk(load = true), which adds a ticket on every call, and the crowd pass runs
     * every tick, so a playing booth's chunk could never unload (a hidden dedicated-server run kept chunk 0,0 loaded
     * for minutes with every player 4000 blocks away). They read through loadedBlockEntity, which takes only a chunk
     * that is already there (getChunkNow).
     */
    private static void sessionPassesLeaveChunksAlone(Path root) throws Exception {
        Path source = root.resolve("src/main/java/ru/gora/musicbattle/server/DjPlaybackManager.java");
        String code = Files.readString(source);
        check(!code.contains("getBlockEntity(session.panelPos)"),
                "no pass reads a session's booth with getBlockEntity, which would keep its chunk loaded");
        int lookups = code.split("loadedBlockEntity\\(level, session\\.panelPos\\)", -1).length - 1;
        check(lookups >= 4, "the session passes read their booth through loadedBlockEntity (" + lookups + ")");
        String helper = method(code, "static net.minecraft.world.level.block.entity.BlockEntity loadedBlockEntity(");
        String chunk = method(code, "private static net.minecraft.world.level.chunk.LevelChunk loadedChunk(");
        check(helper.contains("loadedChunk(") && !helper.contains("level.getBlockEntity")
                        && chunk.contains("getChunkNow(") && !chunk.contains("getChunk("),
                "loadedBlockEntity only reads a chunk that is already loaded");
        for (String pass : List.of("private static void tickDjCrowd(", "private static void updateLiveAudienceCameras(",
                "static void appendMusicMobSources(", "private static boolean validLinkedSpeaker(",
                "private static void pruneConnections(", "private static List<BlockPos> loadedPanelPositions(")) {
            String body = method(code, pass);
            check(!body.isEmpty() && !body.contains("level.getBlockEntity("), pass + " never loads a chunk");
        }
        // The main audience pass runs every five ticks over the loaded booths, and the preload queue it feeds reads
        // the booth again for every request and every pending transfer: each of those must leave the chunk alone too.
        String audience = method(code, "public static void onServerTick(");
        check(audience.contains("loadedBlockEntity(level, panelPos) instanceof DjPanelBlockEntity panel")
                        && !audience.contains("level.getBlockEntity(panelPos)"),
                "the audience pass reads each booth through loadedBlockEntity");
        String queue = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/server/DjAudiencePreloadQueue.java"));
        String request = method(queue, "public static RequestResult request(");
        String tick = method(queue, "public static void tick(");
        check(request.contains("DjPlaybackManager.loadedBlockEntity(") && !request.contains("getBlockEntity(")
                        && tick.contains("DjPlaybackManager.loadedBlockEntity(") && !tick.contains("getBlockEntity("),
                "the preload queue reads booths through loadedBlockEntity, never loading their chunk");
    }

    private static String method(String code, String signature) {
        int start = code.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int index = code.indexOf('{', start); index < code.length(); index++) {
            char c = code.charAt(index);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(start, index + 1);
            }
        }
        return "";
    }

    /** One booth, its chunk, its speakers' chunk and the server's list, pass by pass. */
    private static final class Evening {
        private final Server server;
        private final Chunk boothChunk;
        private final Chunk speakerChunk;
        private final Booth booth;
        /** The tick the DJ presses Play at the panel, or -1. */
        private final long playAt;
        private boolean speakersMustBeLoaded;
        private boolean playPressed;
        private boolean restingWhenPressed;
        private boolean visitedAfterPlay;

        private Evening(boolean oldRule, Chunk boothChunk, Chunk speakerChunk, boolean queued, long playAt) {
            this.server = new Server(oldRule);
            this.boothChunk = boothChunk;
            this.speakerChunk = speakerChunk;
            this.booth = new Booth(2, 2400, queued);
            this.playAt = playAt;
            if (boothChunk.full(0)) {
                server.registered.add(BOOTH);
            }
        }

        private void play(long until) {
            for (long tick = 0; tick <= until; tick += 5) {
                if (boothChunk.unloadsAt(tick)) {
                    // setRemoved: the block entity goes with the chunk.
                    server.registered.remove(BOOTH);
                }
                if (boothChunk.reloadsAt(tick)) {
                    // A new block entity, and its onLoad.
                    server.registered.add(BOOTH);
                }
                if (tick == playAt) {
                    // Play reaches the block entity through the panel; whether anyone hears it is the pass's business.
                    restingWhenPressed = booth.resting;
                    booth.press(tick);
                    playPressed = true;
                }
                long now = tick;
                Predicate<String> full = ignored -> boothChunk.full(now);
                if (!server.visit(full).contains(BOOTH)) {
                    continue;
                }
                if (playPressed && booth.playing) {
                    visitedAfterPlay = true;
                }
                int loadedSpeakers = speakerChunk.full(tick) ? booth.wiredSpeakers : 0;
                booth.pass(tick, DjQueueContinuity.afterSong(booth.queued,
                        speakersMustBeLoaded ? loadedSpeakers : booth.wiredSpeakers));
            }
        }
    }

    /** The five-tick pass's list of booths, under the rule of today or the one before. */
    private static final class Server {
        private final boolean oldRule;
        private final Set<String> registered = new LinkedHashSet<>();
        private boolean askedDemoted;

        private Server(boolean oldRule) {
            this.oldRule = oldRule;
        }

        private List<String> visit(Predicate<String> chunkFull) {
            Predicate<String> boothThere = booth -> {
                askedDemoted |= !chunkFull.test(booth);
                return true;
            };
            if (oldRule) {
                // What loadedPanelPositions did before: strike off whatever is not in a full chunk right now.
                registered.removeIf(booth -> !chunkFull.test(booth) || !boothThere.test(booth));
                return List.copyOf(registered);
            }
            return DjQueueContinuity.boothsToVisit(registered, chunkFull, boothThere);
        }
    }

    /** A booth's clock and queue as the audience pass sees them; every queued song's audio is ready. */
    private static final class Booth {
        private final int wiredSpeakers;
        private final int duration;
        /** A second row waits behind the song on the deck. */
        private final boolean queued;
        private long startedAt;
        private boolean playing = true;
        private boolean resting;
        private int songsStarted = 1;

        private Booth(int wiredSpeakers, int duration, boolean queued) {
            this.wiredSpeakers = wiredSpeakers;
            this.duration = duration;
            this.queued = queued;
        }

        /** One audience pass at {@code tick}, given what the server decides for a finished song. */
        private void pass(long tick, AfterSong afterSong) {
            if (!playing || tick - startedAt < duration) {
                return;
            }
            if (afterSong == AfterSong.PLAY_NEXT) {
                startedAt = tick;
                songsStarted++;
            } else {
                playing = false;
                resting = true;
            }
        }

        private void press(long tick) {
            startedAt = tick;
            playing = true;
            resting = false;
            songsStarted++;
        }
    }

    /**
     * A chunk that is full until {@code demoted}, unloaded from {@code unloaded} when the player goes far enough, and
     * full again from {@code fullAgain}. A chunk that is never unloaded comes back with the same block entity.
     */
    private record Chunk(long demoted, long unloaded, long fullAgain) {
        private boolean full(long tick) {
            return tick < demoted || tick >= fullAgain;
        }

        private boolean unloadsAt(long tick) {
            return unloaded < fullAgain && tick == unloaded;
        }

        private boolean reloadsAt(long tick) {
            return unloaded < fullAgain && tick == fullAgain;
        }
    }

    /**
     * Play / resume on a song that has played to its end (the deck parks there, paused) starts it again from the top;
     * anywhere before the end it resumes where it stands. Seen in a hidden dedicated-server run: after a restart the
     * booth's song had ended, Play left it paused at the end, and the listener heard nothing.
     */
    private static void playAfterTheEnd(Path root) throws Exception {
        check(DjQueueContinuity.resumeTicks(1_200, 3_248) == 1_200, "Play mid-song resumes where the deck stands");
        check(DjQueueContinuity.resumeTicks(3_247, 3_248) == 3_247, "one tick before the end still resumes");
        check(DjQueueContinuity.resumeTicks(3_248, 3_248) == 0, "Play on a song parked at its end starts it again");
        check(DjQueueContinuity.resumeTicks(3_400, 3_248) == 0, "past the end (a longer copy, a speed change) too");
        check(DjQueueContinuity.resumeTicks(-5, 3_248) == 0, "a negative position is the start");
        check(DjQueueContinuity.resumeTicks(50, 0) == 50, "an unknown length never rewinds");
        String manager = java.nio.file.Files.readString(root.resolve(
                "src/main/java/ru/gora/musicbattle/server/DjPlaybackManager.java"));
        int start = manager.indexOf("private static void startPlayback(ServerPlayer player");
        String body = start < 0 ? "" : manager.substring(start, manager.indexOf("private static void queuePlaybackStart", start));
        check(body.contains("DjQueueContinuity.resumeTicks("), "the server's Play / resume goes through resumeTicks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
