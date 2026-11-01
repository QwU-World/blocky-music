package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.Ring;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.workshop.ServerWorkshopClient;
import ru.gora.musicbattle.client.workshop.WorkshopClient;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.WorkshopDropTarget;
import ru.gora.musicbattle.client.workshop.WorkshopDrops;
import ru.gora.musicbattle.client.workshop.WorkshopScreen;
import ru.gora.musicbattle.client.workshop.WorkshopTr;
import ru.gora.musicbattle.client.workshop.layout.BattleSetup;
import ru.gora.musicbattle.client.workshop.layout.BattleSetupLayout;
import ru.gora.musicbattle.client.workshop.layout.BattleTracks;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.data.VoteTiming;
import ru.gora.musicbattle.network.OpenVotePayload;
import ru.gora.musicbattle.network.SurrenderPayload;
import ru.gora.musicbattle.network.TrackPreviewRequestPayload;
import ru.gora.musicbattle.network.VoteInstalledPayload;
import ru.gora.musicbattle.network.VoteStatusPayload;
import ru.gora.musicbattle.network.VoteTrackPayload;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;

/**
 * The battle settings screen, which opens when a battle starts: who is playing whom, the settings this battle offers
 * on the left, the chosen map in a framed slot on the right, and the vote in the footer.
 *
 * <p>It used to be two screens in one - a menu of two track libraries, then a grid of every track the server has -
 * and both of those lists now live inside the Workshop. So the slot is the one door: pressing it opens the Workshop as
 * a picker, whose Server tab lists every track this battle can vote for (the server's Workshop sets, its own tracks and
 * its players' tracks), the chosen map comes back here, and the footer sends the vote. In a battle with another player
 * this is still a vote and nothing more: both sides choose, the countdown keeps running, and the server decides which
 * map wins.
 *
 * <p>Every choice and every rectangle lives in {@link BattleSetup} and {@link BattleSetupLayout}, which know nothing
 * about Minecraft; this class gathers what the game knows about the chosen map, loads covers, sends payloads and
 * draws. {@code check-vote-layout} drives the same two classes without a window, and {@code check-vote-warning}
 * presses this screen's own handlers on the rectangles they compute.
 */
public final class MusicBattleVoteScreen extends UiLayer implements WorkshopDropTarget {
    /** How often the chosen map's download and transfer state is re-read: often enough to feel live, rarely enough. */
    private static final int FETCH_POLL_TICKS = 10;
    /** A track picture that is not here yet is looked for again after this long, not on every frame. */
    private static final long PREVIEW_RECHECK_MS = 500L;
    /** The server answers at most this many picture requests in one payload. */
    private static final int PREVIEWS_PER_REQUEST = 8;
    private static final long NANOS_PER_TICK = 1_000_000_000L / VoteTiming.TICKS_PER_SECOND;

    private final OpenVotePayload payload;
    private final BattleTracks tracks;
    private final BattleSetup setup;
    /** The rectangles of the current frame, and the state they were computed from; both change together. */
    private Layout frame;
    private BattleSetupLayout.State frameState;
    private int framedWidth;
    private int framedHeight;
    private final Motion.Tween slotLift = new Motion.Tween(0.0);

    private boolean closedByServer;
    private String opponentVoteName = "";
    private String opponentVoteTrack = "";
    private boolean opponentUnifiedNotes;
    private boolean opponentDeadlyParty;
    private float opponentSpeedMultiplier = 1.0F;
    private int openTicks;
    /**
     * Server ticks left at the last sync, and when that was. The countdown runs on the clock rather than on this
     * screen's ticks, because Minecraft does not tick a screen while the Workshop picker covers it: counted in ticks,
     * a minute spent choosing came back as a minute the vote still seemed to have.
     */
    private int syncedRemainingTicks;
    private long syncedAtNanos;
    private boolean everyoneVoted;
    /** A Workshop check of the installed copies is on its way for the chosen map; see {@link #runCheck}. */
    private volatile boolean checkRunning;
    /**
     * Listens for the end of the download a held vote waits for, or null when no vote is held for one. Minecraft does
     * not tick a screen that the Workshop picker covers, so a vote held on this screen's tick alone stayed unsent while
     * the player browsed on, and a countdown that ran out meanwhile was decided without the vote they had pressed.
     */
    private HeldDownload heldDownload;

    private final Map<String, Path> previewFiles = new HashMap<>();
    private final Map<String, Long> previewLookups = new HashMap<>();
    private final Set<String> previewsAsked = new HashSet<>();

    /** The warning's rectangles for the window size they were laid out for; see {@link #warningFrame}. */
    private Layout warningFrame;
    private int warningWidth;
    private int warningHeight;
    private boolean userTrackWarning;
    private boolean userTrackWarningAccepted;
    private boolean hideUserTrackWarningChecked;
    private int userTrackWarningFocus;
    /**
     * How the Workshop picker is put in front of the player. The vote-warning check has no window to put it in, so it
     * swaps this for a counter: what it proves is that the slot, and the warning's accept button, hand the player on.
     */
    private Runnable showPicker = this::showWorkshopPicker;

    public MusicBattleVoteScreen(OpenVotePayload payload) {
        super(Component.literal("Blocky Music Vote"));
        this.payload = payload;
        this.tracks = battleTracks(payload);
        this.setup = new BattleSetup(payload.solo(), payload.mobBattle(), payload.opponentName());
        this.syncedRemainingTicks = VoteTiming.clampRemainingTicks(payload.remainingTicks());
        this.syncedAtNanos = System.nanoTime();
    }

    /** The vote payload's rows as the picker lists them; the voter's own installed sets are never among them. */
    static BattleTracks battleTracks(OpenVotePayload payload) {
        List<BattleTracks.Track> rows = new ArrayList<>(payload.tracks().size());
        for (TrackSummary track : payload.tracks()) {
            BattleTracks.Kind kind = switch (track.source()) {
                case WORKSHOP -> BattleTracks.Kind.WORKSHOP;
                case USER -> BattleTracks.Kind.PLAYERS;
                case SERVER -> BattleTracks.Kind.SERVER;
                case INSTALLED -> null;
            };
            if (kind == null) {
                continue;
            }
            String chartKey = kind == BattleTracks.Kind.WORKSHOP ? BattleTracks.chartKeyOf(track.id(), track.setId()) : "";
            rows.add(new BattleTracks.Track(track.id(), kind, track.setId(), chartKey, track.title(), track.description(),
                    track.publishedBy(), track.mapper(), track.contentSha256(), track.bpm(), track.durationTicks(),
                    track.previewFile()));
        }
        return new BattleTracks(rows, payload.truncated());
    }

    public boolean isForBattle(UUID battleId) {
        return payload.battleId().equals(battleId);
    }

    public void closeFromServer() {
        closedByServer = true;
        setup.end();
        stopFollowingDownload();
    }

    /** Takes another voter's choice when the status carries one, and re-syncs the countdown in every case. */
    public void acceptVoteStatus(VoteStatusPayload status) {
        if (status.carriesVote()) {
            opponentVoteName = status.voterName();
            opponentVoteTrack = status.trackTitle();
            opponentUnifiedNotes = status.unifiedNotes();
            opponentDeadlyParty = status.deadlyParty();
            opponentSpeedMultiplier = BattleSetup.nearestSpeed(status.speedMultiplier());
        }
        syncedRemainingTicks = VoteTiming.resync(remainingVoteTicks(), status.remainingTicks());
        syncedAtNanos = System.nanoTime();
        everyoneVoted |= status.everyoneVoted();
    }

    /** Counted down on the clock from the last server figure; zero once everyone chose. */
    private int remainingVoteTicks() {
        if (everyoneVoted) {
            return 0;
        }
        long elapsed = Math.max(0L, (System.nanoTime() - syncedAtNanos) / NANOS_PER_TICK);
        return VoteTiming.countdown(syncedRemainingTicks, (int) Math.min(Integer.MAX_VALUE, elapsed));
    }

    @Override
    protected void layout() {
        relayout();
    }

    @Override
    public void tick() {
        openTicks++;
        super.tick();
        if (openTicks % FETCH_POLL_TICKS == 0) {
            refreshFetch(false);
        }
        if (setup.waitingForMap()) {
            // A listener dropped while this screen was out of the open stack comes back once it is on top again.
            followHeldDownload();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Workshop packages dropped on this screen install through the Workshop flow. */
    @Override
    public void onFilesDrop(List<Path> paths) {
        WorkshopDrops.onFilesDropped(this, paths);
    }

    // ---------------------------------------------------------------- the frame

    private void relayout() {
        frameState = setup.state(WorkshopTr.INSTANCE, new BattleSetup.Frame(remainingVoteTicks(), everyoneVoted,
                voterCard(), coverTexture() != null, details()));
        frame = BattleSetupLayout.compute(this.width, this.height, TextStyles.measurer(), WorkshopTr.INSTANCE, frameState);
        framedWidth = this.width;
        framedHeight = this.height;
    }

    /**
     * The clock is the only part of the screen that changes by itself, so the frame is rebuilt when the words it
     * holds change rather than every frame: a second of countdown, a press, an answer from the Workshop.
     */
    private void refreshFrame() {
        if (frame == null || framedWidth != this.width || framedHeight != this.height) {
            relayout();
            return;
        }
        BattleSetup.Frame live = new BattleSetup.Frame(remainingVoteTicks(), everyoneVoted, voterCard(),
                coverTexture() != null, details());
        BattleSetupLayout.State next = setup.state(WorkshopTr.INSTANCE, live);
        if (!next.equals(frameState)) {
            frameState = next;
            frame = BattleSetupLayout.compute(this.width, this.height, TextStyles.measurer(), WorkshopTr.INSTANCE, next);
        }
    }

    /**
     * The other voter, or null in a solo battle and against mobs, which never vote. Their card is there from the
     * start, saying that they are still choosing: this is a vote between two players, and a screen that only showed
     * them once they had answered read like a single-player menu until the last second.
     */
    private BattleSetup.Voter voterCard() {
        if (payload.solo() || payload.mobBattle()) {
            return null;
        }
        boolean chose = !opponentVoteName.isBlank() || !opponentVoteTrack.isBlank() || opponentUnifiedNotes
                || opponentDeadlyParty || opponentSpeedMultiplier > 1.01F;
        String name = opponentVoteName.isBlank() ? payload.opponentName() : opponentVoteName;
        return new BattleSetup.Voter(name, opponentVoteTrack,
                chose ? voteModeSummary(opponentUnifiedNotes, opponentDeadlyParty, opponentSpeedMultiplier) : "");
    }

    /**
     * What this battle's list and the installed copy say about the chosen map: its tempo, its length and who made it.
     * The server's rows carry all three; a set only this client has knows its length and its mapper from its manifest.
     */
    private BattleSetup.Details details() {
        BattleSetup.Pick pick = setup.pick();
        if (pick == null) {
            return BattleSetup.Details.NONE;
        }
        BattleTracks.Track row = null;
        if (pick.serverTrack()) {
            row = firstRow(pick.trackId());
        } else {
            BattleTracks.Entry listed = tracks.workshopSet(pick.setId());
            if (listed != null) {
                row = listed.first();
                for (BattleTracks.Track candidate : listed.rows()) {
                    if (candidate.chartKey().equals(pick.chartKey())) {
                        row = candidate;
                    }
                }
            }
        }
        if (row != null) {
            String mapper = row.mapper().isBlank() ? row.publishedBy() : row.mapper();
            return new BattleSetup.Details(row.bpm(), row.durationTicks() * 50L, mapper);
        }
        WorkshopCatalog.InstalledSet set = pick.setId() > 0
                ? WorkshopClient.get().catalog().bySetId(pick.setId()).orElse(null) : null;
        if (set == null || set.manifest() == null) {
            return BattleSetup.Details.NONE;
        }
        return new BattleSetup.Details(0, set.manifest().audio().durationMs(), set.manifest().mapper().name());
    }

    private static String voteModeSummary(boolean unified, boolean deadly, float speedMultiplier) {
        List<String> parts = new ArrayList<>();
        if (unified) {
            parts.add(text("music_battle.l10n.client.094"));
        }
        if (deadly) {
            parts.add(text("music_battle.l10n.client.095"));
        }
        if (speedMultiplier > 1.01F) {
            parts.add("x" + BattleSetup.format(speedMultiplier));
        }
        return parts.isEmpty() ? text("music_battle.l10n.client.096") : String.join(" | ", parts);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    protected void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        if (userTrackWarning) {
            drawUserTrackWarning(g, mouseX, mouseY);
            return;
        }
        refreshFrame();
        Rect panel = frame.rect(BattleSetupLayout.PANEL);
        if (panel.w() < this.width) {
            RoundedRect.fill(g, panel.x(), panel.y(), panel.w(), panel.h(), 0, Palette.BG_1);
        }
        drawTitleBar(g);
        drawTimer(g);
        drawSettings(g, mouseX, mouseY);
        drawMapSlot(g, mouseX, mouseY);
        drawFooter(g, mouseX, mouseY);
    }

    private void drawTitleBar(GuiGraphics g) {
        Rect bar = frame.rect(BattleSetupLayout.TITLE_BAR);
        RoundedRect.fill(g, bar.x(), bar.y(), bar.w(), bar.h(), 0, Palette.BG_1);
        WorkshopDraw.icon(g, frame.rect("setup.icon"), UiIcon.MUSIC, Palette.ACCENT);
        WorkshopDraw.text(g, frame, "setup.title", Palette.TEXT_1);
        WorkshopDraw.text(g, frame, "setup.subtitle", Palette.TEXT_3);
    }

    private void drawTimer(GuiGraphics g) {
        int remaining = remainingVoteTicks();
        boolean warm = !everyoneVoted && VoteTiming.finalStretch(remaining / (double) VoteTiming.TICKS_PER_SECOND);
        int accent = everyoneVoted ? Palette.OK : warm ? Palette.WARN : Palette.ACCENT;
        WorkshopDraw.text(g, frame, "setup.timer.label", everyoneVoted ? accent : Palette.TEXT_3);
        WorkshopDraw.text(g, frame, "setup.timer.clock", everyoneVoted || warm ? accent : Palette.TEXT_1);
        WorkshopDraw.bar(g, frame.rect(BattleSetupLayout.TIMER_BAR), VoteTiming.fraction(remaining),
                Palette.BG_3, accent);
    }

    private void drawSettings(GuiGraphics g, double mouseX, double mouseY) {
        WorkshopDraw.text(g, frame, "setup.settings", Palette.TEXT_2);
        for (BattleSetupLayout.Row row : frameState.rows()) {
            String rowId = BattleSetupLayout.rowId(row.id());
            if (frame.rect(rowId).empty()) {
                continue;
            }
            Rect card = frame.rect(rowId + BattleSetupLayout.CARD);
            if (!card.empty()) {
                WorkshopDraw.panel(g, card, Palette.RADIUS_SM, Palette.BG_2);
            }
            WorkshopDraw.text(g, frame, rowId + ".label", card.empty() ? Palette.TEXT_3 : Palette.TEXT_2);
            for (int i = 0; i < row.options().size(); i++) {
                BattleSetupLayout.Option option = row.options().get(i);
                String id = BattleSetupLayout.optionId(row.id(), option.id());
                Rect box = frame.rect(id);
                boolean selected = i == row.selected();
                boolean hovered = option.enabled() && WorkshopDraw.hot(box, mouseX, mouseY);
                WorkshopDraw.button(g, box, selected ? WorkshopDraw.ButtonStyle.PRIMARY : WorkshopDraw.ButtonStyle.SECONDARY,
                        hovered, option.enabled());
                int colour = !option.enabled() ? Palette.TEXT_MUTE
                        : selected ? Palette.TEXT_1 : hovered ? Palette.TEXT_1 : Palette.TEXT_3;
                WorkshopDraw.text(g, frame, id + ".text", colour);
            }
        }
        Rect voter = frame.rect(BattleSetupLayout.VOTER);
        if (voter.empty()) {
            return;
        }
        WorkshopDraw.panel(g, voter, Palette.RADIUS_SM, Palette.BG_2);
        RoundedRect.fill(g, voter.x(), voter.y(), voter.w(), 1, 0, Palette.ACCENT_DEEP);
        WorkshopDraw.text(g, frame, "setup.voter.title", Palette.TEXT_1);
        WorkshopDraw.text(g, frame, "setup.voter.track", Palette.TEXT_2);
        WorkshopDraw.text(g, frame, "setup.voter.modes", Palette.WARN);
    }

    /**
     * The map slot: the invitation while nothing is chosen, otherwise the map with its cover, its difficulty and
     * where it came from. Its own download is the only progress bar on the screen, right where the map is.
     */
    private void drawMapSlot(GuiGraphics g, double mouseX, double mouseY) {
        WorkshopDraw.text(g, frame, "setup.map.heading", Palette.TEXT_2);
        Rect slot = frame.rect(BattleSetupLayout.SLOT);
        BattleSetupLayout.Slot content = frameState.slot();
        boolean hovered = WorkshopDraw.hot(slot, mouseX, mouseY);
        if ((slotLift.target() > 0.5) != hovered) {
            slotLift.animateTo(hovered ? 1.0 : 0.0, Palette.T_HOVER, Motion.Easing.OUT_QUAD);
        }
        float lift = slotLift.value();
        int fill = Palette.mix(Palette.BG_2, Palette.BG_3, lift);
        int border = content.problem() ? Palette.DANGER
                : content.empty() ? Palette.mix(Palette.ACCENT_DEEP, Palette.ACCENT, lift)
                : Palette.mix(Palette.LINE, Palette.ACCENT, lift);
        WorkshopDraw.panel(g, slot, Palette.RADIUS_MD, fill);
        WorkshopDraw.outline(g, slot, Palette.RADIUS_MD, content.empty() ? 1.4F : 1.0F, border);

        if (content.empty()) {
            WorkshopDraw.icon(g, frame.rect("setup.map.icon"), UiIcon.MUSIC, border);
            WorkshopDraw.paragraph(g, frame, "setup.map.empty", Palette.TEXT_1);
            WorkshopDraw.paragraph(g, frame, "setup.map.hint", Palette.TEXT_3);
            return;
        }
        Rect cover = frame.rect(BattleSetupLayout.COVER);
        ResourceLocation texture = coverTexture();
        if (!cover.empty()) {
            RoundedRect.shadow(g, cover.x(), cover.y() + 3F, cover.w(), cover.h(), Palette.RADIUS_MD, 9F,
                    Palette.withAlpha(0xFF000000, 0.45F));
            if (texture != null) {
                RoundedImage.draw(g, texture, cover.x(), cover.y(), cover.w(), cover.h(), Palette.RADIUS_MD, 0F, 1F);
            } else {
                drawPlaceholderArt(g, cover);
            }
        }
        WorkshopDraw.paragraph(g, frame, "setup.map.title", Palette.TEXT_1);
        WorkshopDraw.text(g, frame, "setup.map.artist", Palette.TEXT_3);
        Rect difficulty = frame.rect("setup.map.difficulty");
        if (!difficulty.empty()) {
            WorkshopDraw.pill(g, difficulty, Palette.ACCENT_STRONG);
            WorkshopDraw.text(g, frame, "setup.map.difficulty.text", Palette.TEXT_1);
        }
        Rect badge = frame.rect("setup.map.badge");
        if (!badge.empty()) {
            WorkshopDraw.pill(g, badge, Palette.withAlpha(Palette.BG_4, 0.9F));
            WorkshopDraw.text(g, frame, "setup.map.badge.text", Palette.TEXT_3);
        }
        Rect stats = frame.rect(BattleSetupLayout.STATS);
        if (!stats.empty()) {
            RoundedRect.fill(g, stats.x() + stats.w() * 0.1F, stats.y(), stats.w() * 0.8F, 1F, 0F, Palette.LINE);
            for (int i = 0; i < content.stats().size(); i++) {
                WorkshopDraw.text(g, frame, BattleSetupLayout.STATS + "." + i + ".label", Palette.TEXT_MUTE);
                WorkshopDraw.text(g, frame, BattleSetupLayout.STATS + "." + i + ".value", Palette.TEXT_1);
            }
        }
        WorkshopDraw.paragraph(g, frame, "setup.map.note", content.problem() ? Palette.DANGER : Palette.TEXT_2);
        Rect bar = frame.rect(BattleSetupLayout.SLOT_BAR);
        if (!bar.empty()) {
            BattleSetup.Pick pick = setup.pick();
            float progress = pick == null || pick.setId() <= 0 ? 0F
                    : WorkshopClient.get().downloader().status(pick.setId()).progress();
            WorkshopDraw.bar(g, bar, progress, Palette.BG_0, Palette.ACCENT);
        }
    }

    /**
     * What the art box shows for a map with no picture: a record in the screen's own violet, its grooves and label
     * drawn in the Workshop's shapes. A map the server keeps without a cover used to leave the slot an empty frame.
     */
    private static void drawPlaceholderArt(GuiGraphics g, Rect art) {
        RoundedRect.fill(g, art.x(), art.y(), art.w(), art.h(), Palette.RADIUS_MD,
                Palette.mix(Palette.BG_1, Palette.ACCENT_DEEP, 0.28F));
        float cx = art.centerX();
        float cy = art.centerY();
        float radius = art.w() * 0.4F;
        RoundedRect.circle(g, cx, cy, radius, Palette.mix(Palette.BG_0, Palette.ACCENT_DEEP, 0.18F));
        for (int groove = 1; groove <= 3; groove++) {
            Ring.track(g, cx, cy, radius * (1F - groove * 0.16F), Math.max(0.6F, radius * 0.012F),
                    Palette.withAlpha(Palette.ACCENT, 0.10F));
        }
        RoundedRect.circle(g, cx, cy, radius * 0.3F, Palette.ACCENT_STRONG);
        float icon = radius * 0.3F;
        WorkshopDraw.icon(g, new Rect(cx - icon * 0.5F, cy - icon * 0.5F, icon, icon), UiIcon.MUSIC, Palette.TEXT_1);
    }

    private void drawFooter(GuiGraphics g, double mouseX, double mouseY) {
        Rect footer = frame.rect(BattleSetupLayout.FOOTER);
        RoundedRect.fill(g, footer.x(), footer.y(), footer.w(), footer.h(), 0, Palette.BG_1);
        RoundedRect.fill(g, footer.x(), footer.y(), footer.w(), 1, 0, Palette.LINE);
        Rect cancel = frame.rect(BattleSetupLayout.CANCEL);
        boolean cancelHovered = WorkshopDraw.hot(cancel, mouseX, mouseY);
        WorkshopDraw.button(g, cancel, WorkshopDraw.ButtonStyle.QUIET, cancelHovered, true);
        WorkshopDraw.text(g, frame, BattleSetupLayout.CANCEL + ".text", cancelHovered ? Palette.TEXT_1 : Palette.TEXT_3);
        Rect confirm = frame.rect(BattleSetupLayout.CONFIRM);
        boolean enabled = frameState.confirmEnabled();
        boolean confirmHovered = enabled && WorkshopDraw.hot(confirm, mouseX, mouseY);
        WorkshopDraw.button(g, confirm, WorkshopDraw.ButtonStyle.PRIMARY, confirmHovered, enabled);
        WorkshopDraw.text(g, frame, BattleSetupLayout.CONFIRM + ".text",
                enabled ? Palette.TEXT_1 : Palette.TEXT_MUTE);
    }

    /**
     * The chosen map's cover: the installed copy's own file first, then the one the Workshop card named, then the
     * picture the server keeps for the track, which covers the server's own tracks and the sets its list sent bare.
     */
    private ResourceLocation coverTexture() {
        BattleSetup.Pick pick = setup.pick();
        if (pick == null) {
            return null;
        }
        if (pick.serverTrack()) {
            return battlePreview(firstRow(pick.trackId()));
        }
        WorkshopCatalog.InstalledSet set = WorkshopClient.get().catalog().bySetId(pick.setId()).orElse(null);
        if (set != null && set.coverPresent()) {
            return CoverCache.getFile(set.coverFile());
        }
        String url = pick.coverUrl();
        if (url != null && !url.isEmpty() && WorkshopClient.get().networkAllowed()) {
            return CoverCache.get(url);
        }
        BattleTracks.Entry listed = tracks.workshopSet(pick.setId());
        return listed == null ? null : battlePreview(listed.first());
    }

    private BattleTracks.Track firstRow(String trackId) {
        for (BattleTracks.Entry entry : tracks.entries()) {
            if (entry.trackId().equals(trackId)) {
                return entry.first();
            }
        }
        return null;
    }

    /**
     * The picture the server keeps for one track of this battle, once it has sent it. It is asked for the first time
     * a card or the slot wants it, once per track, because the server sends each picture to a voter only once; the
     * cache on disk is looked at again every half second until the file is there, never on every frame.
     */
    private ResourceLocation battlePreview(BattleTracks.Track row) {
        if (row == null || row.previewFile().isEmpty()) {
            return null;
        }
        Path file = previewFiles.get(row.id());
        if (file == null) {
            long now = System.currentTimeMillis();
            Long next = previewLookups.get(row.id());
            if (next != null && now < next) {
                return null;
            }
            previewLookups.put(row.id(), now + PREVIEW_RECHECK_MS);
            file = ClientTrackAssets.cachedAsset(row.id(), row.previewFile()).orElse(null);
            if (file == null) {
                if (previewsAsked.add(row.id())
                        && !ClientTrackAssets.isTransferActive(row.id(), "preview", row.previewFile())) {
                    requestPreviews(List.of(row.id()));
                }
                return null;
            }
            previewFiles.put(row.id(), file);
        }
        return CoverCache.getFile(file);
    }

    private void requestPreviews(List<String> trackIds) {
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            return;
        }
        for (int start = 0; start < trackIds.size(); start += PREVIEWS_PER_REQUEST) {
            List<String> batch = trackIds.subList(start, Math.min(trackIds.size(), start + PREVIEWS_PER_REQUEST));
            PacketDistributor.sendToServer(new TrackPreviewRequestPayload(payload.battleId(), List.copyOf(batch)));
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (userTrackWarning) {
            return clickUserTrackWarning(mouseX, mouseY);
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || frame == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        BattleSetup.Action action = setup.press(BattleSetupLayout.hit(frame, mouseX, mouseY));
        switch (action) {
            case SETTING -> {
                click();
                refreshFrame();
            }
            case OPEN_PICKER -> {
                click();
                openPicker();
            }
            case CONFIRM -> {
                click();
                confirm();
            }
            case CANCEL -> {
                click();
                onClose();
            }
            case NONE -> {
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!userTrackWarning) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancelUserTrackWarning();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            int direction = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            userTrackWarningFocus = Math.floorMod(userTrackWarningFocus + direction, 3);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER
                || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (userTrackWarningFocus == 0) {
                hideUserTrackWarningChecked = !hideUserTrackWarningChecked;
            } else if (userTrackWarningFocus == 1) {
                acceptUserTrackWarning();
            } else {
                cancelUserTrackWarning();
            }
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        if (userTrackWarning) {
            cancelUserTrackWarning();
            return;
        }
        if (!setup.voted() && !closedByServer) {
            PacketDistributor.sendToServer(new SurrenderPayload(payload.battleId()));
        }
        setup.end();
        stopFollowingDownload();
        Minecraft.getInstance().setScreen(null);
    }

    private void click() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    // ---------------------------------------------------------------- choosing a map

    /**
     * The Workshop as the picker. The warning about what other players publish comes first, but only on a server
     * that carries such a track: that is the rule the old track list kept, and the slot is the press that now puts
     * those tracks in front of the player. A battle without them goes straight to the Workshop.
     */
    private void openPicker() {
        if (tracks.hasPlayerTracks() && !warningSettled()) {
            openUserTrackWarning();
            return;
        }
        showPicker.run();
    }

    private void showWorkshopPicker() {
        if (this.minecraft == null) {
            // A screen Minecraft never showed has no business putting another one in front of the player.
            return;
        }
        WorkshopScreen.openPicker(this, new WorkshopScreen.Picker(tracks, this::chooseMap, this::remainingVoteTicks,
                this::battlePreview));
    }

    /** Whether the Workshop may open without the warning about other players' work: for this battle, or for good. */
    private boolean warningSettled() {
        return userTrackWarningAccepted || ClientBattleSettings.hideUserTrackWarning();
    }

    /** Called by the picker. The map may not be here yet, so whatever it needs starts at once. */
    private void chooseMap(BattleSetup.Pick pick) {
        // A new map drops a vote held for the old one, and with it the listener for the old map's download.
        stopFollowingDownload();
        setup.setPick(pick);
        refreshFetch(true);
        if (this.minecraft != null) {
            relayout();
        }
    }

    /**
     * Where the chosen map stands. {@link BattleSetup#decide} reads the facts gathered here and says what they mean
     * and what has to start; {@code start} lets it begin a download or a Workshop check, which choosing a map and
     * pressing the vote do and the polling does not. A map that becomes ready lets a held vote go.
     */
    private void refreshFetch(boolean start) {
        BattleSetup.Pick pick = setup.pick();
        if (pick == null || setup.voted() || setup.ended()) {
            stopFollowingDownload();
            return;
        }
        if (pick.serverTrack()) {
            // The server's own tracks come from this battle's list, so there is nothing to fetch: either it is in the
            // list or this screen was handed a track it never offered.
            setup.setFetch(tracks.hasTrack(pick.trackId()) ? BattleSetup.Fetch.READY : BattleSetup.Fetch.FAILED);
        } else if (pick.setId() <= 0) {
            setup.setFetch(BattleSetup.Fetch.FAILED);
        } else {
            BattleSetup.Decision decision = BattleSetup.decide(facts(pick), start);
            switch (decision.step()) {
                case DOWNLOAD -> WorkshopClient.get().downloader().enqueue(pick.setId());
                case CHECK -> runCheck();
                case NONE -> {
                }
            }
            setup.setFetch(decision.fetch(), decision.failureKey());
        }
        if (setup.heldVoteDue()) {
            sendVote();
        }
        if (!setup.waitingForMap()) {
            stopFollowingDownload();
        }
    }

    private BattleSetup.Facts facts(BattleSetup.Pick pick) {
        WorkshopClient client = WorkshopClient.get();
        // The download's state is read before the catalogue and the verdict. The downloader puts the set in the
        // catalogue, then remembers it as checked, then reports it installed; read the other way round, a poll that
        // fell between those steps saw an installed copy nobody had checked, called it a problem and dropped the
        // vote the player was holding for it.
        WorkshopDownloader.Status status = client.downloader().status(pick.setId());
        WorkshopCatalog.InstalledSet set = client.catalog().bySetId(pick.setId()).orElse(null);
        BattleSetup.Verdict verdict = BattleSetup.Verdict.CHECK_FIRST;
        if (set != null) {
            verdict = switch (client.installedCheck().transferVerdict(set.setId(), set.contentSha256())) {
                case ALLOWED -> BattleSetup.Verdict.ALLOWED;
                case WITHDRAWN -> BattleSetup.Verdict.WITHDRAWN;
                case AUDIO_REMOVED -> BattleSetup.Verdict.AUDIO_REMOVED;
                case CHECK_FIRST -> BattleSetup.Verdict.CHECK_FIRST;
            };
        }
        boolean downloading = status.state() == WorkshopDownloader.State.QUEUED
                || status.state() == WorkshopDownloader.State.DOWNLOADING
                || status.state() == WorkshopDownloader.State.INSTALLING;
        String failure = "";
        if (status.state() == WorkshopDownloader.State.FAILED || status.state() == WorkshopDownloader.State.UNAVAILABLE) {
            failure = status.failureCode() != null && !status.failureCode().isEmpty() ? status.failureCode()
                    : status.state() == WorkshopDownloader.State.UNAVAILABLE ? WorkshopApiException.UNAVAILABLE : "";
            String key = WorkshopApiException.translationKey(failure);
            if (failure.isEmpty() || !key.equals("music_battle.workshop.error." + failure)
                    || !Language.getInstance().has(key)) {
                // A reason this build has no words for reads better as the general line than as a raw key.
                failure = "";
            }
        }
        return new BattleSetup.Facts(tracks.voteId(pick.setId(), pick.chartKey()) != null,
                ServerWorkshopClient.allowPlayerMaps(), set != null,
                set != null && set.manifest().audio().policy() != BeatmapManifest.AudioPolicy.INCLUDED,
                set != null && set.audioPresent(), set != null && client.catalog().signed(set.setId()), verdict,
                downloading, failure, client.networkAllowed(), checkRunning);
    }

    /**
     * Asks the Workshop about the installed copies now, the way "Add to server" does, because a copy nobody has
     * checked lately may not be handed to a server and the player has no other way to have it checked from here.
     */
    private void runCheck() {
        if (checkRunning) {
            return;
        }
        checkRunning = true;
        WorkshopClient client = WorkshopClient.get();
        client.io().execute(() -> {
            try {
                client.installedCheck().runNow(CancelToken.none());
            } catch (WorkshopApiException failed) {
                // The next look at the map finds it still unchecked and says so; there is nothing to retry here.
            } finally {
                Minecraft.getInstance().execute(() -> {
                    checkRunning = false;
                    refreshFetch(false);
                });
            }
        });
    }

    /**
     * The vote. A map that is still on its way holds the press until it lands, and the slot says so; one that cannot
     * be played here leaves the button off and its reason in the slot.
     */
    private void confirm() {
        if (setup.voted() || setup.ended() || setup.pick() == null) {
            return;
        }
        // A press on a map whose download failed starts it again here: start lets the decision begin a download.
        refreshFetch(true);
        if (setup.voted()) {
            return;
        }
        if (setup.fetch() == BattleSetup.Fetch.READY && setup.confirmEnabled()) {
            sendVote();
            return;
        }
        if (setup.fetch().pending()) {
            setup.setWaitingForMap(true);
            followHeldDownload();
        }
        relayout();
    }

    /**
     * Sends the vote. A track of this battle's list - the server's own, or a Workshop chart it has - is voted for by
     * its id; a set only this client has goes through the installed vote, and the server then asks for the package.
     */
    private void sendVote() {
        BattleSetup.Pick pick = setup.pick();
        if (setup.voted() || setup.ended() || pick == null || !stillOpen()) {
            return;
        }
        String trackId = pick.serverTrack() ? pick.trackId() : tracks.voteId(pick.setId(), pick.chartKey());
        if (trackId != null) {
            if (!setup.claimVote()) {
                return;
            }
            stopFollowingDownload();
            PacketDistributor.sendToServer(new VoteTrackPayload(payload.battleId(), trackId, setup.mobSkill(),
                    setup.unifiedNotes(), setup.deadlyParty(), setup.speedMultiplier()));
        } else {
            WorkshopCatalog.InstalledSet set = WorkshopClient.get().catalog().bySetId(pick.setId()).orElse(null);
            if (set == null) {
                setup.setFetch(BattleSetup.Fetch.FAILED);
                relayout();
                return;
            }
            if (!setup.claimVote()) {
                return;
            }
            stopFollowingDownload();
            // The server has to fetch this set from us first; it answers with a chunk request.
            // The revision and the hash are the installed copy's, which is the one we can actually hand over.
            ServerWorkshopClient.rememberVoteUpload(set.setId(), set.revision(), set.contentSha256());
            PacketDistributor.sendToServer(new VoteInstalledPayload(payload.battleId(), set.setId(), set.revision(),
                    set.contentSha256(), pick.chartKey(), setup.mobSkill(), setup.unifiedNotes(), setup.deadlyParty(),
                    setup.speedMultiplier()));
        }
        // Back to the game, taking the picker with it when a held vote went out while the player was choosing again;
        // a screen the battle no longer owns is left alone.
        if (stillOpen()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * Whether this screen still serves its battle: on top, or under the picker or one of the Workshop's dialogs. The
     * battle can end without telling it - the battle starting clears every screen, a new vote replaces this one, the
     * connection drops - and a download that finishes after that must not vote in a battle that is gone.
     */
    private boolean stillOpen() {
        return ScreenStack.find(MusicBattleVoteScreen.class) == this;
    }

    /**
     * Starts listening for the end of the download the held vote waits for; a second call changes nothing. A vote held
     * for the Workshop check needs no listener, because {@link #runCheck} already answers on the game thread.
     */
    private void followHeldDownload() {
        BattleSetup.Pick pick = setup.pick();
        if (heldDownload != null || pick == null || pick.serverTrack() || pick.setId() <= 0
                || setup.fetch() != BattleSetup.Fetch.WORKING) {
            return;
        }
        HeldDownload listener = new HeldDownload(pick.setId());
        heldDownload = listener;
        WorkshopDownloader downloader = WorkshopClient.get().downloader();
        downloader.addListener(listener);
        if (!downloader.active(pick.setId())) {
            // The job ended before the listener was there to hear it; queued, so this is read after the press is done.
            Minecraft.getInstance().tell(() -> heldDownloadEnded(listener));
        }
    }

    private void stopFollowingDownload() {
        HeldDownload listener = heldDownload;
        if (listener != null) {
            heldDownload = null;
            WorkshopClient.get().downloader().removeListener(listener);
        }
    }

    /**
     * The download a held vote waits for has ended, one way or another, and this runs on the game thread whatever
     * screen is on top. A screen that left the open stack lets go of the downloader rather than keep itself alive in
     * it; one that is still open reads where its map stands, which sends the held vote once the map is ready.
     */
    private void heldDownloadEnded(HeldDownload listener) {
        if (heldDownload != listener) {
            // An answer for a map the player has since swapped, or for a vote that has already gone out.
            return;
        }
        if (!stillOpen()) {
            stopFollowingDownload();
            return;
        }
        refreshFetch(false);
    }

    /**
     * Hears the downloader for one set. Its callbacks run on worker threads and progress arrives many times a second,
     * so only the end of the job - installed, failed, unavailable or cancelled - is handed to the game thread.
     */
    private final class HeldDownload implements WorkshopDownloader.Listener {
        private final long setId;

        HeldDownload(long setId) {
            this.setId = setId;
        }

        @Override
        public void statusChanged(long changed, WorkshopDownloader.Status status) {
            if (changed != setId || status == null) {
                return;
            }
            WorkshopDownloader.State state = status.state();
            if (state == WorkshopDownloader.State.QUEUED || state == WorkshopDownloader.State.DOWNLOADING
                    || state == WorkshopDownloader.State.INSTALLING) {
                return;
            }
            Minecraft.getInstance().execute(() -> heldDownloadEnded(this));
        }
    }

    // ---------------------------------------------------------------- the warning about other players' work

    private void openUserTrackWarning() {
        userTrackWarning = true;
        hideUserTrackWarningChecked = false;
        userTrackWarningFocus = 0;
    }

    /**
     * The warning's rectangles, laid out by {@link BattleSetupLayout#warning} once per window size. Drawing and the
     * clicks read the same ones, so a line that wraps on a narrow window moves the checkbox it pushes down for both.
     */
    private Layout warningFrame() {
        if (warningFrame == null || warningWidth != this.width || warningHeight != this.height) {
            warningFrame = BattleSetupLayout.warning(this.width, this.height, TextStyles.measurer(), WorkshopTr.INSTANCE,
                    BattleSetup.warning(WorkshopTr.INSTANCE));
            warningWidth = this.width;
            warningHeight = this.height;
        }
        return warningFrame;
    }

    /**
     * The warning that the server carries other players' work, in the Workshop's own shapes and type. Its geometry is
     * the one it has always had, because its answer is remembered on disk and its handlers are a regression of their
     * own.
     */
    private void drawUserTrackWarning(GuiGraphics g, double mouseX, double mouseY) {
        Layout warning = warningFrame();
        Rect panel = warning.rect(BattleSetupLayout.WARNING);
        WorkshopDraw.panel(g, panel, Palette.RADIUS_MD, Palette.BG_1);
        WorkshopDraw.outline(g, panel, Palette.RADIUS_MD, 1.4F, Palette.DANGER);
        int[] colours = {Palette.DANGER, Palette.TEXT_1, Palette.TEXT_2, Palette.TEXT_2, Palette.WARN};
        for (int i = 0; i < colours.length; i++) {
            WorkshopDraw.paragraph(g, warning, "warning.line." + i, colours[i]);
        }

        Rect checkbox = warning.rect(BattleSetupLayout.WARNING_CHECKBOX);
        boolean checkboxHovered = WorkshopDraw.hover(warning.rect(BattleSetupLayout.WARNING_TOGGLE), mouseX, mouseY);
        WorkshopDraw.check(g, checkbox, hideUserTrackWarningChecked, false, true);
        if (userTrackWarningFocus == 0 || checkboxHovered) {
            WorkshopDraw.outline(g, checkbox.inset(-1.5F, -1.5F), Palette.RADIUS_SM, 1F,
                    userTrackWarningFocus == 0 ? Palette.ACCENT : Palette.DANGER);
        }
        WorkshopDraw.text(g, warning, BattleSetupLayout.WARNING_TOGGLE + ".text", Palette.TEXT_2);

        Rect accept = warning.rect(BattleSetupLayout.WARNING_ACCEPT);
        Rect cancel = warning.rect(BattleSetupLayout.WARNING_CANCEL);
        boolean acceptHovered = userTrackWarningFocus == 1 || WorkshopDraw.hot(accept, mouseX, mouseY);
        boolean cancelHovered = userTrackWarningFocus == 2 || WorkshopDraw.hot(cancel, mouseX, mouseY);
        WorkshopDraw.button(g, accept, WorkshopDraw.ButtonStyle.DANGER, acceptHovered, true);
        WorkshopDraw.button(g, cancel, WorkshopDraw.ButtonStyle.SECONDARY, cancelHovered, true);
        WorkshopDraw.text(g, warning, BattleSetupLayout.WARNING_ACCEPT + ".text", Palette.TEXT_1);
        WorkshopDraw.text(g, warning, BattleSetupLayout.WARNING_CANCEL + ".text", Palette.TEXT_1);
    }

    private boolean clickUserTrackWarning(double mouseX, double mouseY) {
        Layout warning = warningFrame();
        if (warning.rect(BattleSetupLayout.WARNING_TOGGLE).contains(mouseX, mouseY)) {
            hideUserTrackWarningChecked = !hideUserTrackWarningChecked;
            userTrackWarningFocus = 0;
            return true;
        }
        if (warning.rect(BattleSetupLayout.WARNING_ACCEPT).contains(mouseX, mouseY)) {
            userTrackWarningFocus = 1;
            acceptUserTrackWarning();
            return true;
        }
        if (warning.rect(BattleSetupLayout.WARNING_CANCEL).contains(mouseX, mouseY)) {
            userTrackWarningFocus = 2;
            cancelUserTrackWarning();
            return true;
        }
        return true;
    }

    private void acceptUserTrackWarning() {
        userTrackWarningAccepted = true;
        if (hideUserTrackWarningChecked) {
            ClientBattleSettings.setHideUserTrackWarning(true);
        }
        userTrackWarning = false;
        hideUserTrackWarningChecked = false;
        openPicker();
    }

    private void cancelUserTrackWarning() {
        userTrackWarning = false;
        hideUserTrackWarningChecked = false;
        userTrackWarningFocus = 0;
    }
}
