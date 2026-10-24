package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ru.gora.musicbattle.data.VoteTiming;

/**
 * The battle setup screen with no Minecraft in it: what this battle offers, what the player chose, the map they
 * picked, how far that map is from being playable here, and what a press on one of {@link BattleSetupLayout}'s boxes
 * does.
 *
 * <p>The screen keeps one of these and holds no settings of its own. It asks for a {@link BattleSetupLayout.State},
 * draws the rectangles that come back, turns a click into the id of the box under the pointer and hands that id to
 * {@link #press}. It also gathers what the game knows about the chosen map into {@link Facts} and lets
 * {@link #decide} say what that means. Every choice is therefore made by code that needs neither a window nor a GPU,
 * which is how {@code check-vote-layout} presses the very buttons the screen draws and walks every reason a map can
 * be refused.
 */
public final class BattleSetup {
    private static final String KEY = "screen.music_battle.vote.setup.";
    private static final String ERROR = "music_battle.workshop.error.";

    /** Where the chosen map came from, which is what the slot's quiet badge says. */
    public enum Origin {
        /** A Workshop set: the server's own copy of it, or one this client fetches for the vote. */
        WORKSHOP,
        /** The player's own installed copy, which the server takes from them if it wins. */
        INSTALLED,
        /** A track the server's operator put there, outside the Workshop. */
        SERVER,
        /** A track a player published on this server with the mod's own editor. */
        PLAYERS
    }

    /**
     * How far the chosen map is from being playable here. Each state that blocks the vote names its own reason, so the
     * slot can say why instead of the footer refusing a press with no explanation; none of those reasons is one a
     * download could change, which is why none of them starts one.
     */
    public enum Fetch {
        /** The server has it, or this client does and may hand it over. */
        READY(false, ""),
        /** Its download is running; the slot carries the progress and a vote pressed now waits for it. */
        WORKING(false, KEY + "fetching"),
        /** The Workshop is being asked whether this copy may still be handed over; a vote pressed now waits too. */
        CHECKING(false, KEY + "checking"),
        /** It could not be fetched; {@link #setFetch(Fetch, String)} can carry the downloader's own reason. */
        FAILED(true, KEY + "fetch_failed"),
        /** This server does not accept maps from players, so a map only this client has cannot be voted for. */
        REFUSED(true, KEY + "not_allowed"),
        /** The song is a file the player adds themselves, which may not be sent to a server. */
        OWN_AUDIO(true, KEY + "own_audio"),
        /** The Workshop withdrew the set, and a withdrawn set is never handed to a server. */
        WITHDRAWN(true, ERROR + "withdrawn"),
        /** The Workshop removed the song at a rights holder's request; the copy plays alone, never on a server. */
        AUDIO_REMOVED(true, ERROR + "audio_removed"),
        /** Nobody has checked this copy with the Workshop lately, and the check cannot run now. */
        UNCHECKED(true, ERROR + "check_online_first"),
        /**
         * The copy carries no Workshop signature, so the server refuses it. A download of the same revision installs
         * as the copy already there and leaves it unsigned, so none is started.
         */
        UNSIGNED(true, ERROR + "unsigned_package");

        private final boolean problem;
        private final String noteKey;

        Fetch(boolean problem, String noteKey) {
            this.problem = problem;
            this.noteKey = noteKey;
        }

        /** Whether this is a reason the map cannot be voted for, rather than a step on its way here. */
        public boolean problem() {
            return problem;
        }

        /** Whether the map is still on its way: a vote pressed now is held until it arrives. */
        public boolean pending() {
            return this == WORKING || this == CHECKING;
        }

        public String noteKey() {
            return noteKey;
        }
    }

    /** What the screen has to do about a press; everything else {@link #press} has already done itself. */
    public enum Action {
        NONE, SETTING, OPEN_PICKER, CONFIRM, CANCEL
    }

    /** What the Workshop last said about handing the player's own copy to a server (spec 08 A5.1). */
    public enum Verdict {
        ALLOWED, CHECK_FIRST, WITHDRAWN, AUDIO_REMOVED
    }

    /** What the screen has to start for a map to become playable, besides showing its state. */
    public enum Step {
        NONE, DOWNLOAD, CHECK
    }

    /**
     * Everything the game knows about a chosen map that bears on whether this battle can play it.
     *
     * @param serverHas         this battle's own list holds the chosen chart, so the vote names it by id
     * @param playerMapsAllowed the server accepts maps its players bring
     * @param installed         this client has the set installed
     * @param ownAudio          the installed set's song is a file the player adds (its audio policy is external)
     * @param audioPresent      the installed set's song file is there
     * @param signed            the installed set carries the Workshop's signature
     * @param verdict           the Workshop's last word on handing this copy over
     * @param downloading       a download of the set is queued or running
     * @param failureCode       the downloader's reason for its last failure, or empty when it has none
     * @param networkAllowed    the player allowed the Workshop to reach the network
     * @param checkRunning      a Workshop check of the installed copies is on its way
     */
    public record Facts(boolean serverHas, boolean playerMapsAllowed, boolean installed, boolean ownAudio,
                        boolean audioPresent, boolean signed, Verdict verdict, boolean downloading, String failureCode,
                        boolean networkAllowed, boolean checkRunning) {
        public Facts {
            verdict = verdict == null ? Verdict.CHECK_FIRST : verdict;
            failureCode = failureCode == null ? "" : failureCode;
        }
    }

    /** A state for the slot, the downloader's reason when it has one, and what the screen has to start. */
    public record Decision(Fetch fetch, String failureKey, Step step) {
    }

    /** The mob skill ids the vote payloads carry, hardest last; the deadly party needs one of the last three. */
    public static final List<String> SKILL_IDS = List.of("easy", "medium", "hard", "god", "perfect");
    /** The first skill the deadly party allows: below it a mob cannot keep a player alive. */
    public static final int DEADLY_MIN_SKILL = 3;
    private static final List<String> SKILL_KEYS = List.of("music_battle.l10n.client.064", "music_battle.l10n.client.065",
            "music_battle.l10n.client.066", "music_battle.l10n.client.067", "music_battle.l10n.client.068");

    public static final String ROW_NOTES = "notes";
    public static final String ROW_DEADLY = "deadly";
    public static final String ROW_SKILL = "skill";
    public static final String ROW_SPEED = "speed";
    public static final String ROW_DIFFICULTY = "difficulty";

    private static final String KEY_SOLO = "music_battle.l10n.client.069";
    private static final String KEY_ON = "music_battle.l10n.client.058";
    private static final String KEY_OFF = "music_battle.l10n.client.059";
    private static final String KEY_SKILL_ROW = "music_battle.l10n.client.082";
    private static final String KEY_CANCEL = "music_battle.l10n.client.044";
    private static final String KEY_VOTER = "music_battle.l10n.client.088";
    private static final String KEY_VOTER_PENDING = "music_battle.l10n.client.089";
    private static final String KEY_TIME = "screen.music_battle.vote_time";
    private static final String KEY_EVERYONE = "screen.music_battle.vote_everyone_chosen";
    private static final String KEY_CHIP_WORKSHOP = "screen.music_battle.vote.chip.workshop";
    private static final String KEY_CHIP_SERVER = "screen.music_battle.vote.chip.server";
    private static final String KEY_CHIP_PLAYERS = "screen.music_battle.vote.chip.players";
    private static final String KEY_CHIP_INSTALLED = "screen.music_battle.vote.chip.installed";
    private static final String KEY_WARNING_HEADING = "music_battle.l10n.client.076";
    private static final String KEY_LENGTH = "screen.music_battle.workshop.set.length";
    private static final String KEY_BPM = "screen.music_battle.workshop.set.bpm";
    /**
     * How the server joins the two sides of a battle between two mobs (BattleManager's MOB_PAIR_SEPARATOR); see
     * {@link #title}. A name cannot hold a line break, so a mob named "A vs B" is never split into two.
     */
    private static final String MOBS_SEPARATOR = "\n";

    /** The speeds the battle offers, as the id in a box name and the multiplier the vote carries. */
    private static final List<String> SPEED_IDS = List.of("x1", "x15", "x2");
    private static final float[] SPEED_VALUES = {1.0F, 1.5F, 2.0F};

    /**
     * One chosen map. A Workshop chart carries every chart of its set rather than one, because the difficulty is a
     * choice about this battle and belongs with the other choices on the setup screen; {@link #chartKey()} and
     * {@link #difficulty()} are what the vote and the slot read. One of the server's own tracks has no set and a
     * single chart, and the vote names it by {@link #trackId}.
     */
    public record Pick(long setId, int revision, String contentSha256, String trackId, String title, String artist,
                       String coverUrl, List<String> chartKeys, List<String> chartNames, int chart, Origin origin) {
        public Pick {
            contentSha256 = contentSha256 == null ? "" : contentSha256;
            trackId = trackId == null ? "" : trackId;
            title = title == null ? "" : title;
            artist = artist == null ? "" : artist;
            chartKeys = List.copyOf(chartKeys);
            chartNames = List.copyOf(chartNames);
            chart = Math.max(0, Math.min(Math.max(0, chartKeys.size() - 1), chart));
            origin = origin == null ? Origin.WORKSHOP : origin;
        }

        /** A chart of a Workshop set, starting on {@code chart}; the setup screen can still swap it for another. */
        public static Pick workshop(long setId, int revision, String contentSha256, String title, String artist,
                                    String coverUrl, List<String> chartKeys, List<String> chartNames, int chart,
                                    Origin origin) {
            return new Pick(setId, revision, contentSha256, "", title, artist, coverUrl, chartKeys, chartNames, chart,
                    origin);
        }

        /** One of the server's own tracks, voted for by its id; {@code byline} says who published it, if anyone. */
        public static Pick track(String trackId, String title, String byline, Origin origin) {
            return new Pick(0L, 0, "", trackId, title, byline, null, List.of(), List.of(), 0, origin);
        }

        /** Whether this is one of the server's own tracks rather than a chart of a Workshop set. */
        public boolean serverTrack() {
            return !trackId.isEmpty();
        }

        /** Whether there is anything to vote for: a track id, or a chart of the set. */
        public boolean votable() {
            return serverTrack() || !chartKey().isEmpty();
        }

        /** The chart this vote is for, empty when the set reported none; then there is nothing to vote for. */
        public String chartKey() {
            return chartKeys.isEmpty() ? "" : chartKeys.get(chart);
        }

        public String difficulty() {
            return chartName(chart);
        }

        /** The name a chart is shown by; a set that sent keys without names shows the key itself. */
        public String chartName(int index) {
            if (index < 0 || index >= chartKeys.size()) {
                return "";
            }
            String name = index < chartNames.size() ? chartNames.get(index) : "";
            return name == null || name.isBlank() ? keyAsName(chartKeys.get(index)) : name;
        }

        public Pick withChart(int index) {
            return new Pick(setId, revision, contentSha256, trackId, title, artist, coverUrl, chartKeys, chartNames,
                    index, origin);
        }
    }

    /** The other voter's choice as the status payload reported it; null until somebody else has chosen. */
    public record Voter(String name, String track, String modes) {
    }

    /**
     * What the game knows about the chosen map beyond its name: its tempo, its length and who made it. Any of them may
     * be missing (0 or empty); the slot leaves out what it does not know.
     */
    public record Details(int bpm, long lengthMs, String mapper) {
        public static final Details NONE = new Details(0, 0L, "");

        public Details {
            mapper = mapper == null ? "" : mapper;
        }
    }

    /**
     * What only the screen knows when it builds a frame: the countdown, the other voter, the cover it loaded and what
     * it found out about the chosen map.
     */
    public record Frame(int remainingTicks, boolean everyoneVoted, Voter voter, boolean cover, Details details) {
        public Frame {
            details = details == null ? Details.NONE : details;
        }

        public Frame(int remainingTicks, boolean everyoneVoted, Voter voter, boolean cover) {
            this(remainingTicks, everyoneVoted, voter, cover, Details.NONE);
        }
    }

    private final boolean solo;
    private final boolean mobBattle;
    private final String opponentName;
    private boolean unifiedNotes;
    private boolean deadlyParty;
    private int mobSkill = 1;
    private int speed;
    private Pick pick;
    private Fetch fetch = Fetch.READY;
    /** The downloader's own reason for a failure, shown in place of the general line when there is one. */
    private String failureKey = "";
    private boolean waitingForMap;
    /** The one vote this screen sends has gone out; see {@link #claimVote}. */
    private boolean voted;
    /** The player left, or the battle closed; nothing may send a vote from this screen after that. */
    private boolean ended;

    public BattleSetup(boolean solo, boolean mobBattle, String opponentName) {
        this.solo = solo;
        this.mobBattle = mobBattle;
        this.opponentName = opponentName == null ? "" : opponentName;
    }

    public boolean solo() {
        return solo;
    }

    public boolean mobBattle() {
        return mobBattle;
    }

    /** Both sides play the same notes. A solo battle has no second side, so it never offers this. */
    public boolean unifiedNotes() {
        return !solo && unifiedNotes;
    }

    public boolean deadlyParty() {
        return !solo && deadlyParty;
    }

    public int mobSkillIndex() {
        return mobSkill;
    }

    public String mobSkill() {
        return SKILL_IDS.get(mobSkill);
    }

    public float speedMultiplier() {
        return SPEED_VALUES[speed];
    }

    public Pick pick() {
        return pick;
    }

    public Fetch fetch() {
        return fetch;
    }

    /** True once the player confirmed a map that was still on its way: the vote goes out when it arrives. */
    public boolean waitingForMap() {
        return waitingForMap;
    }

    /** Whether the vote has gone out. */
    public boolean voted() {
        return voted;
    }

    /** Whether the player left or the battle closed. */
    public boolean ended() {
        return ended;
    }

    public void setPick(Pick value) {
        pick = value;
        fetch = Fetch.READY;
        failureKey = "";
        waitingForMap = false;
    }

    public void setFetch(Fetch value) {
        setFetch(value, "");
    }

    /** A new state; {@code reasonKey} is a failure's own words and is kept for {@link Fetch#FAILED} only. */
    public void setFetch(Fetch value, String reasonKey) {
        fetch = value == null ? Fetch.READY : value;
        failureKey = fetch == Fetch.FAILED && reasonKey != null ? reasonKey : "";
        if (fetch.problem()) {
            // A map that cannot come cannot carry a vote either, so a held one is let go and the button comes back.
            waitingForMap = false;
        }
    }

    /** The player pressed the vote while the map is still coming: the press is kept and the slot says so. */
    public void setWaitingForMap(boolean value) {
        waitingForMap = value && pick != null && !fetch.problem() && !voted && !ended;
    }

    /**
     * A map is chosen, it names something to vote for, nothing has said it cannot be played here - or it only failed
     * to download, which the press tries again - and the vote has not already been pressed and held. A held vote
     * turns the button off so the press reads as taken.
     */
    public boolean confirmEnabled() {
        return pick != null && pick.votable() && (!fetch.problem() || retryable()) && !waitingForMap && !voted
                && !ended;
    }

    /**
     * Whether the chosen map only failed to download, for a reason another try could change. The vote then stays on
     * and its press downloads the map again and holds the vote for it, where the only way back used to be choosing
     * the same map again in the picker. A set the Workshop no longer offers, and a Workshop the player has not let
     * onto the network, fail the same way every time, so neither is retried; one of the server's own tracks was
     * never downloaded at all.
     */
    public boolean retryable() {
        return fetch == Fetch.FAILED && pick != null && !pick.serverTrack() && pick.setId() > 0
                && !failureKey.equals(ERROR + "unavailable") && !failureKey.equals(ERROR + "consent_required");
    }

    /**
     * A held vote whose map has arrived. The screen asks this whenever it hears about the map - from its own tick, from
     * the download's end, from the Workshop check - because the tick stops while the picker covers the screen.
     */
    public boolean heldVoteDue() {
        return waitingForMap && fetch == Fetch.READY && !voted && !ended;
    }

    /**
     * Claims the one vote this screen sends, right before the payload goes out; false means send nothing. A held vote
     * can be released from more than one place, and the answers that release it arrive in any order, so this is the
     * single gate that keeps it to one vote and keeps every vote from following a Cancel or a closed battle.
     */
    public boolean claimVote() {
        if (voted || ended) {
            return false;
        }
        voted = true;
        waitingForMap = false;
        return true;
    }

    /** The player left, or the battle closed: a held vote is dropped, and no vote can be claimed after this. */
    public void end() {
        ended = true;
        waitingForMap = false;
    }

    // ---------------------------------------------------------------- where the chosen map stands

    /**
     * What a chosen map's facts mean. A map this battle already lists is ready at once. Otherwise it is one the player
     * brings: the server has to accept such maps, and an installed copy has to be one the Workshop lets a server
     * have. The reasons that are facts about the copy - its song is the player's own file, it was withdrawn, its song
     * was taken down, it carries no signature - stop it with no download, because a fresh download would leave every
     * one of them as it is. A copy missing its own song file is downloaded again, which writes the file back; a copy
     * nobody has checked lately is checked. {@code start} is whether this moment may begin that work: choosing the map
     * and pressing the vote may, the screen's polling may not.
     */
    public static Decision decide(Facts facts, boolean start) {
        if (facts.serverHas()) {
            return new Decision(Fetch.READY, "", Step.NONE);
        }
        if (!facts.playerMapsAllowed()) {
            return new Decision(Fetch.REFUSED, "", Step.NONE);
        }
        if (facts.installed()) {
            if (facts.ownAudio()) {
                return new Decision(Fetch.OWN_AUDIO, "", Step.NONE);
            }
            if (facts.verdict() == Verdict.WITHDRAWN) {
                return new Decision(Fetch.WITHDRAWN, "", Step.NONE);
            }
            if (facts.verdict() == Verdict.AUDIO_REMOVED) {
                return new Decision(Fetch.AUDIO_REMOVED, "", Step.NONE);
            }
            if (!facts.signed()) {
                return new Decision(Fetch.UNSIGNED, "", Step.NONE);
            }
            if (facts.audioPresent() && !facts.downloading()) {
                if (facts.verdict() == Verdict.ALLOWED) {
                    return new Decision(Fetch.READY, "", Step.NONE);
                }
                if (facts.checkRunning()) {
                    return new Decision(Fetch.CHECKING, "", Step.NONE);
                }
                if (start && facts.networkAllowed()) {
                    return new Decision(Fetch.CHECKING, "", Step.CHECK);
                }
                return new Decision(Fetch.UNCHECKED, "", Step.NONE);
            }
        }
        if (facts.downloading()) {
            return new Decision(Fetch.WORKING, "", Step.NONE);
        }
        if (start && facts.networkAllowed()) {
            return new Decision(Fetch.WORKING, "", Step.DOWNLOAD);
        }
        if (!facts.failureCode().isEmpty()) {
            return new Decision(Fetch.FAILED, ERROR + facts.failureCode(), Step.NONE);
        }
        return new Decision(Fetch.FAILED, facts.networkAllowed() ? "" : ERROR + "consent_required", Step.NONE);
    }

    // ---------------------------------------------------------------- input

    /**
     * Carries out a press on the box named {@code boxId} and says what is left for the screen to do. An id that is
     * not a control of this screen, and an option this battle does not allow, both change nothing.
     */
    public Action press(String boxId) {
        if (boxId == null || boxId.isEmpty()) {
            return Action.NONE;
        }
        if (boxId.equals(BattleSetupLayout.SLOT)) {
            return Action.OPEN_PICKER;
        }
        if (boxId.equals(BattleSetupLayout.CONFIRM)) {
            return confirmEnabled() ? Action.CONFIRM : Action.NONE;
        }
        if (boxId.equals(BattleSetupLayout.CANCEL)) {
            return Action.CANCEL;
        }
        String row = BattleSetupLayout.rowOf(boxId);
        String option = BattleSetupLayout.optionOf(boxId);
        return row.isEmpty() || option.isEmpty() || !select(row, option) ? Action.NONE : Action.SETTING;
    }

    /** True when {@code option} of {@code row} is a choice this battle allows; it is then the chosen one. */
    private boolean select(String row, String option) {
        switch (row) {
            case ROW_NOTES -> {
                if (solo) {
                    return false;
                }
                unifiedNotes = "shared".equals(option);
                return true;
            }
            case ROW_DEADLY -> {
                if (solo) {
                    return false;
                }
                deadlyParty = "on".equals(option);
                // A mob that plays below godlike cannot keep a player alive in a deadly party, so switching the
                // party on also raises a skill that would be a death sentence.
                if (deadlyParty && mobBattle && mobSkill < DEADLY_MIN_SKILL) {
                    mobSkill = DEADLY_MIN_SKILL;
                }
                return true;
            }
            case ROW_SKILL -> {
                int index = SKILL_IDS.indexOf(option);
                if (!mobBattle || index < 0 || !skillAllowed(index)) {
                    return false;
                }
                mobSkill = index;
                return true;
            }
            case ROW_SPEED -> {
                int index = SPEED_IDS.indexOf(option);
                if (index < 0) {
                    return false;
                }
                speed = index;
                return true;
            }
            case ROW_DIFFICULTY -> {
                int index = index(option);
                if (pick == null || index < 0 || index >= pick.chartKeys().size()) {
                    return false;
                }
                pick = pick.withChart(index);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean skillAllowed(int index) {
        return !deadlyParty() || index >= DEADLY_MIN_SKILL;
    }

    private static int index(String option) {
        try {
            return Integer.parseInt(option);
        } catch (NumberFormatException notAnIndex) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- the frame the layout draws

    /** Everything {@link BattleSetupLayout} needs, in this battle's own words. */
    public BattleSetupLayout.State state(Tr tr, Frame frame) {
        String title = title(tr);
        String clock = VoteTiming.formatTicks(frame.remainingTicks());
        // A mob never votes, so a mob battle has no other voter to wait for, whatever the screen passed in.
        Voter voter = solo || mobBattle ? null : frame.voter();
        String voterTrack = voter == null ? "" : voter.name() + ": "
                + (voter.track() == null || voter.track().isBlank() ? tr.get(KEY_VOTER_PENDING) : voter.track());
        return new BattleSetupLayout.State(title, tr.get(KEY + "subtitle"),
                tr.get(frame.everyoneVoted() ? KEY_EVERYONE : KEY_TIME), clock, tr.get(KEY + "settings"), rows(tr),
                voter == null ? "" : tr.get(KEY_VOTER), voterTrack, voter == null ? "" : voter.modes(),
                tr.get(KEY + "map"), slot(tr, frame.cover(), frame.details()),
                tr.get(KEY + (solo || mobBattle ? "start" : "vote")), confirmEnabled(), tr.get(KEY_CANCEL));
    }

    /**
     * The battle's name. A player against a mob or another player reads "You vs Zombie". A battle the player sets up
     * between two mobs arrives with both names joined by the server ("Zombie vs Skeleton") as the opponent, and
     * "You vs Zombie vs Skeleton" named the player as a third side; that one is the two mobs against each other.
     */
    String title(Tr tr) {
        if (solo) {
            return tr.get(KEY_SOLO);
        }
        int split = mobBattle ? opponentName.indexOf(MOBS_SEPARATOR) : -1;
        if (split > 0 && split + MOBS_SEPARATOR.length() < opponentName.length()) {
            return tr.get(KEY + "mobs", opponentName.substring(0, split),
                    opponentName.substring(split + MOBS_SEPARATOR.length()));
        }
        return tr.get(KEY + "versus", opponentName);
    }

    /** The settings rows this battle offers, in the order the left column reads them. */
    private List<BattleSetupLayout.Row> rows(Tr tr) {
        List<BattleSetupLayout.Row> rows = new ArrayList<>(5);
        if (!solo) {
            rows.add(new BattleSetupLayout.Row(ROW_NOTES, tr.get(KEY + "notes"),
                    List.of(new BattleSetupLayout.Option("shared", tr.get(KEY + "notes.shared"), true),
                            new BattleSetupLayout.Option("separate", tr.get(KEY + "notes.separate"), true)),
                    unifiedNotes ? 0 : 1));
            rows.add(new BattleSetupLayout.Row(ROW_DEADLY, tr.get(KEY + "deadly"),
                    List.of(new BattleSetupLayout.Option("on", tr.get(KEY_ON), true),
                            new BattleSetupLayout.Option("off", tr.get(KEY_OFF), true)),
                    deadlyParty ? 0 : 1));
        }
        if (mobBattle) {
            List<BattleSetupLayout.Option> skills = new ArrayList<>(SKILL_IDS.size());
            for (int i = 0; i < SKILL_IDS.size(); i++) {
                skills.add(new BattleSetupLayout.Option(SKILL_IDS.get(i), tr.get(SKILL_KEYS.get(i)), skillAllowed(i)));
            }
            rows.add(new BattleSetupLayout.Row(ROW_SKILL, tr.get(KEY_SKILL_ROW), skills, mobSkill));
        }
        List<BattleSetupLayout.Option> speeds = new ArrayList<>(SPEED_IDS.size());
        for (int i = 0; i < SPEED_IDS.size(); i++) {
            speeds.add(new BattleSetupLayout.Option(SPEED_IDS.get(i), "x" + format(SPEED_VALUES[i]), true));
        }
        rows.add(new BattleSetupLayout.Row(ROW_SPEED, tr.get(KEY + "speed"), speeds, speed));
        // A set with one chart has nothing to choose, and the slot already names the difficulty it will play.
        if (pick != null && pick.chartKeys().size() > 1) {
            List<BattleSetupLayout.Option> charts = new ArrayList<>(pick.chartKeys().size());
            for (int i = 0; i < pick.chartKeys().size(); i++) {
                charts.add(new BattleSetupLayout.Option(String.valueOf(i), pick.chartName(i), true));
            }
            rows.add(new BattleSetupLayout.Row(ROW_DIFFICULTY, tr.get(KEY + "difficulty"), charts, pick.chart()));
        }
        return rows;
    }

    private BattleSetupLayout.Slot slot(Tr tr, boolean cover, Details details) {
        if (pick == null) {
            return new BattleSetupLayout.Slot(true, tr.get(KEY + "pick"), tr.get(KEY + "pick.hint"), "", "", "", "",
                    "", false, false, false, List.of());
        }
        // A held vote is the newest thing the player did, so its line wins over the download's: the press was taken.
        String note;
        if (waitingForMap) {
            note = tr.get(KEY + "waiting");
        } else if (fetch == Fetch.FAILED && !failureKey.isEmpty()) {
            note = tr.get(failureKey);
        } else {
            note = fetch.noteKey().isEmpty() ? "" : tr.get(fetch.noteKey());
        }
        String badge = switch (pick.origin()) {
            case SERVER -> tr.get(KEY_CHIP_SERVER);
            case PLAYERS -> tr.get(KEY_CHIP_PLAYERS);
            case INSTALLED -> tr.get(KEY_CHIP_INSTALLED);
            case WORKSHOP -> tr.get(KEY_CHIP_WORKSHOP);
        };
        // The line under the title is the artist; a server track has none, and its maker is named among the facts.
        String artist = pick.serverTrack() ? "" : pick.artist();
        return new BattleSetupLayout.Slot(false, "", "", pick.title(), artist, pick.difficulty(), badge, note,
                fetch == Fetch.WORKING, fetch.problem(), cover, stats(tr, details));
    }

    /** The facts under the chosen map's name, in the Workshop's own words; what is not known is left out. */
    private static List<BattleSetupLayout.Stat> stats(Tr tr, Details details) {
        List<BattleSetupLayout.Stat> stats = new ArrayList<>(3);
        if (details.bpm() > 0) {
            stats.add(new BattleSetupLayout.Stat(tr.get(KEY_BPM), String.valueOf(details.bpm())));
        }
        if (details.lengthMs() > 0L) {
            stats.add(new BattleSetupLayout.Stat(tr.get(KEY_LENGTH), WorkshopStrings.duration(details.lengthMs())));
        }
        if (!details.mapper().isBlank()) {
            stats.add(new BattleSetupLayout.Stat(tr.get(KEY + "mapper"), details.mapper()));
        }
        return stats;
    }

    /** The warning about other players' work, in this battle's language. */
    public static BattleSetupLayout.Warning warning(Tr tr) {
        return new BattleSetupLayout.Warning(tr.get(KEY_WARNING_HEADING),
                List.of(tr.get("music_battle.l10n.client.077"), tr.get("music_battle.l10n.client.078"),
                        tr.get("music_battle.l10n.client.079"), tr.get("music_battle.l10n.client.080")),
                tr.get("screen.music_battle.user_track_warning.dont_show_again"), tr.get("music_battle.l10n.client.081"),
                tr.get("music_battle.l10n.client.051"));
    }

    /** "1", "1.5", "2": the speeds a player reads, without a trailing zero. */
    public static String format(float multiplier) {
        return multiplier >= 1.9F ? "2" : multiplier >= 1.4F ? "1.5" : "1";
    }

    /** The nearest speed this battle offers to what another voter chose, so both sides read the same word. */
    public static float nearestSpeed(float multiplier) {
        return multiplier >= 1.9F ? 2.0F : multiplier >= 1.4F ? 1.5F : 1.0F;
    }

    /** A chart key shown where no name came with it: "hard" reads as "Hard". Keys are lower-case ASCII. */
    public static String keyAsName(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    }
}
