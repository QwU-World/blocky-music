package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.workshop.layout.AccountForm;
import ru.gora.musicbattle.client.workshop.layout.Form;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.ClientLabels;
import ru.gora.musicbattle.workshop.api.DeviceFlow;
import ru.gora.musicbattle.workshop.api.MinecraftSignIn;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;
import ru.gora.musicbattle.workshop.api.WorkshopSession;

/**
 * The account view: signing in with the Minecraft account or with a code, the device
 * code with "Open the link" and "Copy code", "Signed in as @username. Continue · This isn't me", and, once signed in,
 * the linked Minecraft accounts, the automatic sign-in state, the one-time acceptance of the Workshop Terms and the
 * Community Rules, notifications, the standing banner and sign-out; signed in or out, the Network access switch.
 */
public final class AccountView implements WorkshopScreen.Overlay {
    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final FormView form;

    private AccountForm.Mode mode = AccountForm.Mode.SIGNED_OUT;
    private boolean remember = true;
    private boolean busy;
    private String status;
    private String error;
    private List<String> notifications = List.of();
    private int unread;
    private String standing;
    private List<Form.Row> minecraft = List.of();
    private AccountForm.Card card;
    /** Whether the link confirmation is already on screen, so a poll does not open a second one. */
    private boolean linkConfirmShown;
    private WorkshopDtos.Profile profile;
    private boolean autoSignIn;
    private boolean canLink;
    private long lastPollMs;
    private String rules;
    private String rulesChanged;
    private boolean rulesPending;
    /** Answers that came back from the network and have still to reach the render thread; see {@link #deliver}. */
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
    /** The press that asked for the network, kept until the consent dialog answers; see {@link #askNetwork}. */
    private Runnable afterConsent;
    /**
     * Set once the view is closed. A code sign-in whose codes are still on their way when the player leaves would
     * otherwise arrive to a view nobody sees and wait out its ten minutes holding one of the network's slots.
     */
    private volatile boolean closed;

    public AccountView(WorkshopScreen screen, WorkshopClient client) {
        this.screen = screen;
        this.client = client;
        this.form = new FormView(this::onAction);
        form.setImages(id -> {
            if (!client.networkAllowed()) {
                return null;
            }
            if (id.endsWith(".banner")) {
                String banner = profile == null || profile.headerCovers() == null ? null : profile.headerCovers().card();
                return CoverCache.get(banner);
            }
            String avatar = profile != null && profile.user() != null ? profile.user().avatarUrl() : null;
            if (avatar == null) {
                WorkshopSession.State state = client.session().state();
                avatar = state.account() == null ? null : state.account().avatarUrl();
            }
            return CoverCache.get(avatar);
        });
        refreshMode();
        // A code sign-in left waiting when the whole Workshop was closed is still a live code: show it again rather
        // than a signed-out page with a wait running unseen behind it.
        if (mode == AccountForm.Mode.SIGNED_OUT && client.deviceFlow().phase() == DeviceFlow.Phase.WAITING) {
            mode = AccountForm.Mode.DEVICE_WAITING;
            status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.waiting");
        }
        rebuild();
        loadAccount();
    }

    @Override
    public Component title() {
        return Component.translatable(WorkshopStrings.KEY + "title.account");
    }

    @Override
    public void setArea(Rect area) {
        form.setArea(area);
        pump();
        // Coming back from the consent dialog: run the sign-in that asked for it. A refusal drops it, because the
        // player turned down this very action and it must not happen behind the dialog that asked.
        Runnable asked = afterConsent;
        afterConsent = null;
        if (asked != null && client.networkAllowed()) {
            asked.run();
        }
    }

    /** The only words here are a sign-in code the service wrote, so leaving never needs "discard what you typed?". */
    @Override
    public boolean hasUnsavedText(boolean typed) {
        return false;
    }

    /**
     * Leaving while a code is still waiting is giving up on it, and the service is told; see
     * {@link #abandonDeviceSignIn}.
     */
    @Override
    public void onClose() {
        closed = true;
        afterConsent = null;
        if (mode == AccountForm.Mode.DEVICE_WAITING) {
            abandonDeviceSignIn();
        }
    }

    /**
     * Hands an answer back to this view.
     *
     * <p>A link confirmation is a vanilla screen over the Workshop, not one of its dialogs, and this view raises one
     * every time "Open the link", "Manage account" or the rules are pressed - which is to say during the very wait
     * the sign-in flows spend polling. An answer that arrived under one used to be dropped on the spot, leaving the
     * view empty, or busy, with nothing on the way to undo it. Held here, it is applied from this view's own frames.
     */
    private void deliver(Runnable work) {
        pending.add(work);
        screen.later(this::pump);
    }

    /** Applies whatever came back while something else was on top. Render thread only. */
    private void pump() {
        Runnable work;
        while ((work = pending.poll()) != null) {
            work.run();
        }
    }

    private void refreshMode() {
        if (client.session().signedIn()) {
            mode = AccountForm.Mode.SIGNED_IN;
        } else if (mode == AccountForm.Mode.SIGNED_IN) {
            mode = AccountForm.Mode.SIGNED_OUT;
        }
    }

    private void rebuild() {
        WorkshopSession.State state = client.session().state();
        DeviceFlow.Snapshot device = client.deviceFlow().snapshot();
        MinecraftSignIn.LinkSnapshot link = client.signIn().linkSnapshot();
        String code = mode == AccountForm.Mode.LINK_WAITING ? link.userCode() : device.userCode();
        boolean minecraftAvailable = MinecraftProof.microsoftAccount() && MinecraftProof.playerOrNull() != null;
        AccountForm.State value = new AccountForm.State(mode, minecraftAvailable, remember, code, status, error,
                state.account() == null ? null : state.account().username(),
                state.account() == null ? null : state.account().displayName(), minecraft, canLink, autoSignIn, notifications,
                standing, unread, busy, rules, rulesChanged, rulesPending, card, client.networkAllowed());
        form.setItems(AccountForm.build(WorkshopTr.INSTANCE, value));
    }

    // ---------------------------------------------------------------- data

    private void loadAccount() {
        if (!client.session().signedIn() || !client.networkAllowed()) {
            return;
        }
        screen.run(() -> {
            try {
                WorkshopDtos.Me me = client.session().authorized(api -> api.me(CancelToken.none()));
                WorkshopDtos.NotificationsPage page = null;
                try {
                    page = client.session().authorized(api -> api.notifications(null, 5, CancelToken.none()));
                } catch (WorkshopApiException ignored) {
                    // Notifications are optional; the rest of the view still works.
                }
                WorkshopDtos.Profile card = null;
                if (me.account() != null) {
                    try {
                        card = client.session().authorized(api -> api.profile(String.valueOf(me.account().id()), CancelToken.none()));
                    } catch (WorkshopApiException ignored) {
                        // The card is decoration; the account still works without it.
                    }
                }
                WorkshopDtos.NotificationsPage loaded = page;
                WorkshopDtos.Profile loadedProfile = card;
                deliver(() -> applyMe(me, loaded, loadedProfile));
            } catch (WorkshopApiException exception) {
                deliver(() -> {
                    error = WorkshopText.error(exception.code()).getString();
                    rebuild();
                });
            }
        });
    }

    private void applyMe(WorkshopDtos.Me me, WorkshopDtos.NotificationsPage page, WorkshopDtos.Profile loaded) {
        // The summary the session took when it started goes stale: a picture or a name changed on qwu.world only
        // reached the game after a sign-out. This is the fresh copy, so pass it on.
        if (me.account() != null) {
            client.session().rememberAccount(new WorkshopDtos.AccountSummary(me.account().id(), me.account().username(),
                    me.account().displayName(), me.account().avatarUrl()));
        }
        profile = loaded;
        card = buildCard(loaded);
        List<Form.Row> rows = new ArrayList<>();
        canLink = MinecraftProof.microsoftAccount();
        autoSignIn = false;
        if (me.minecraft() != null) {
            MinecraftSignIn.Player current = MinecraftProof.microsoftAccount() ? MinecraftProof.playerOrNull() : null;
            String currentName = current == null ? null : current.name();
            String currentUuid = current == null ? null : current.uuid().toString();
            for (WorkshopDtos.MinecraftLink link : me.minecraft()) {
                rows.add(new Form.Row("account.mc." + link.uuid(), link.name() == null ? link.uuid() : link.name(),
                        WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + (link.autoSignIn() ? "account.link.auto" : "account.link.manual"))));
                boolean isCurrent = currentUuid != null && currentUuid.equalsIgnoreCase(link.uuid())
                        || currentName != null && currentName.equalsIgnoreCase(link.name());
                // On this computer the game signs in by itself only for the account being played, when the site allows
                // it for that link and this Minecraft account signed in here before (see MinecraftSignIn.Memory).
                if (isCurrent && link.autoSignIn() && client.signIn().remembered(current.uuid())) {
                    autoSignIn = true;
                }
                if (isCurrent) {
                    canLink = false;
                }
            }
        }
        minecraft = rows;
        applyRules(me.participation());
        standing = standingText(me.standing());
        unread = me.notificationsUnread();
        if (page != null && page.items() != null) {
            List<String> lines = new ArrayList<>();
            for (WorkshopDtos.Notification item : page.items()) {
                lines.add(item.message() == null ? item.kind() : item.message());
            }
            notifications = lines;
            unread = page.unread();
        }
        mode = AccountForm.Mode.SIGNED_IN;
        rebuild();
    }

    /** Turns the profile answer into the card: the chips, the joined line and the four counters. */
    private AccountForm.Card buildCard(WorkshopDtos.Profile value) {
        if (value == null || value.user() == null) {
            return null;
        }
        String k = WorkshopStrings.KEY;
        List<Form.Chip> chips = new ArrayList<>();
        if (value.user().roles() != null) {
            for (String role : value.user().roles()) {
                String key = k + "account.role." + role;
                String label = WorkshopTr.INSTANCE.get(key);
                chips.add(new Form.Chip(role, label.equals(key) ? role : label));
            }
        }
        String joined = joinedMonth(value.user().joinedAt());
        String since = joined == null
                ? WorkshopTr.INSTANCE.get(k + "account.profile.since_unknown")
                : WorkshopTr.INSTANCE.get(k + "account.profile.since", joined);
        WorkshopDtos.ProfileCounts counts = value.counts();
        List<Form.Stat> stats = new ArrayList<>();
        if (counts != null) {
            stats.add(new Form.Stat(String.valueOf(counts.sets()), WorkshopTr.INSTANCE.get(k + "account.stat.maps")));
            stats.add(new Form.Stat(String.valueOf(counts.featured()), WorkshopTr.INSTANCE.get(k + "account.stat.featured")));
            stats.add(new Form.Stat(String.valueOf(counts.favouritesReceived()),
                    WorkshopTr.INSTANCE.get(k + "account.stat.favourites")));
            stats.add(new Form.Stat(String.valueOf(counts.comments()), WorkshopTr.INSTANCE.get(k + "account.stat.comments")));
        }
        boolean banner = value.headerCovers() != null && value.headerCovers().card() != null;
        return new AccountForm.Card(List.copyOf(chips), since, List.copyOf(stats), banner);
    }

    /** "September 2026" in the player's own language, from an ISO timestamp. Null when there is nothing to read. */
    private static String joinedMonth(String iso) {
        if (iso == null || iso.length() < 7) {
            return null;
        }
        try {
            java.time.YearMonth month = java.time.YearMonth.parse(iso.substring(0, 7));
            return month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy",
                    java.util.Locale.forLanguageTag(net.minecraft.client.Minecraft.getInstance().options.languageCode
                            .replace('_', '-'))));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * The one-time acceptance of the Workshop Terms and the Community Rules (Terms section 3.1). A server that says
     * nothing about it leaves the row out rather than guessing.
     */
    private void applyRules(WorkshopDtos.Rules value) {
        client.rememberParticipation(value);
        if (value == null) {
            rules = null;
            rulesChanged = null;
            rulesPending = false;
            return;
        }
        rulesPending = value.pending();
        if (!value.accepted()) {
            rules = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.rules.none");
        } else if (value.acceptedAt() == null) {
            rules = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.rules.given");
        } else {
            rules = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.rules.on",
                    WorkshopStrings.relative(WorkshopTr.INSTANCE, value.acceptedAt(), System.currentTimeMillis()));
        }
        if (!value.needsReaccept()) {
            rulesChanged = null;
        } else {
            String changes = value.changes();
            rulesChanged = changes == null || changes.isEmpty()
                    ? WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.rules.changed")
                    : WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.rules.changed_with", changes);
        }
    }

    private String standingText(WorkshopDtos.Standing value) {
        if (value == null) {
            return null;
        }
        if (value.restricted()) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.standing.restricted");
        }
        if (value.silencedUntil() != null) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.standing.silenced");
        }
        if (value.uploadTerminated() || value.uploadSuspendedUntil() != null) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.standing.upload");
        }
        if (value.activeStrikes() > 0) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.standing.strikes", String.valueOf(value.activeStrikes()));
        }
        return null;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        // Answers that came back while a link confirmation or a dialog was on top of the Workshop.
        pump();
        pollFlows();
        form.render(g, mouseX, mouseY);
    }

    /** Device and link polling respect the server's interval; both run off the render thread. */
    private void pollFlows() {
        long now = System.currentTimeMillis();
        if (now - lastPollMs < 400) {
            return;
        }
        lastPollMs = now;
        if (mode == AccountForm.Mode.DEVICE_WAITING && client.deviceFlow().pollDue()) {
            screen.run(() -> {
                DeviceFlow.Phase phase = client.deviceFlow().poll(CancelToken.none());
                deliver(() -> onDevicePhase(phase));
            });
        } else if (mode == AccountForm.Mode.LINK_WAITING
                && client.signIn().linkSnapshot().phase() == MinecraftSignIn.LinkPhase.WAITING) {
            // Only while the website has still to answer. Once it has, polling returns the same answer forever, and
            // the confirmation dialog was being opened again on every poll.
            screen.run(() -> {
                MinecraftSignIn.LinkPhase phase = client.signIn().pollLink(CancelToken.none());
                deliver(() -> onLinkPhase(phase));
            });
        }
    }

    private void onDevicePhase(DeviceFlow.Phase phase) {
        switch (phase) {
            case SIGNED_IN -> {
                mode = AccountForm.Mode.DEVICE_CONFIRM;
                status = null;
            }
            case DENIED, EXPIRED, CANCELLED, FAILED -> {
                mode = AccountForm.Mode.SIGNED_OUT;
                String code = client.deviceFlow().snapshot().errorCode();
                error = code == null ? null : WorkshopText.error(code).getString();
            }
            default -> status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.waiting");
        }
        rebuild();
    }

    /** Saying no to the link stops the wait as well, so the dialog does not come straight back. */
    private void cancelLinkConfirmation() {
        linkConfirmShown = false;
        client.signIn().cancelLink();
        mode = client.session().signedIn() ? AccountForm.Mode.SIGNED_IN : AccountForm.Mode.SIGNED_OUT;
        status = null;
        rebuild();
    }

    private void onLinkPhase(MinecraftSignIn.LinkPhase phase) {
        switch (phase) {
            case AWAITING_CONFIRMATION -> {
                MinecraftSignIn.LinkSnapshot snapshot = client.signIn().linkSnapshot();
                String account = snapshot.approvedBy() == null ? "?" : snapshot.approvedBy().username();
                MinecraftSignIn.Player current = MinecraftProof.playerOrNull();
                String name = current == null ? "?" : current.name();
                if (!linkConfirmShown) {
                    linkConfirmShown = true;
                    screen.showDialog(WorkshopConfirmScreen.confirm(screen,
                            Component.translatable(WorkshopStrings.KEY + "account.link_confirm_title"),
                            Component.translatable(WorkshopStrings.KEY + "account.link_confirm_body", name, "@" + account),
                            Component.translatable(WorkshopStrings.KEY + "account.link_confirm"), this::confirmLink,
                            this::cancelLinkConfirmation));
                }
                mode = AccountForm.Mode.LINK_WAITING;
                status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.link_confirm_hint");
            }
            case LINKED -> {
                linkConfirmShown = false;
                mode = AccountForm.Mode.SIGNED_IN;
                status = null;
                loadAccount();
            }
            case DENIED, EXPIRED, CANCELLED, FAILED -> {
                mode = client.session().signedIn() ? AccountForm.Mode.SIGNED_IN : AccountForm.Mode.SIGNED_OUT;
                String code = client.signIn().linkSnapshot().errorCode();
                error = code == null ? null : WorkshopText.error(code).getString();
            }
            default -> status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.waiting");
        }
        rebuild();
    }

    private void confirmLink() {
        linkConfirmShown = false;
        busy = true;
        rebuild();
        screen.run(() -> {
            MinecraftSignIn.LinkPhase phase = client.signIn().confirmLink(CancelToken.none());
            deliver(() -> {
                busy = false;
                onLinkPhase(phase);
            });
        });
    }

    // ---------------------------------------------------------------- actions

    private void onAction(String id, double relativeX) {
        screen.click();
        error = null;
        switch (id) {
            case "account.mc" -> signInWithMinecraft();
            case "account.device" -> startDeviceFlow();
            case "account.remember" -> {
                remember = !remember;
                rebuild();
            }
            case "account.about" -> LinkActions.openTrusted(screen, "https://qwu.world/");
            case "account.open_link" -> openVerification();
            case "account.copy_code" -> copyCode();
            case "account.cancel" -> cancelFlow();
            case "account.continue" -> {
                client.deviceFlow().confirm();
                mode = AccountForm.Mode.SIGNED_IN;
                loadAccount();
                rebuild();
            }
            case "account.not_me" -> notMe();
            case "account.link" -> startLink();
            case "account.sign_out" -> signOut();
            case "account.rules.open" -> LinkActions.openTrusted(screen, client.api().pageUrl("/rules/community"));
            case "account.rules.accept" -> screen.openRulesAcceptance(this::loadAccount);
            case "account.manage" -> LinkActions.openTrusted(screen, client.api().pageUrl("/account"));
            case "account.open_profile" -> {
                WorkshopSession.State signed = client.session().state();
                if (signed.account() != null) {
                    LinkActions.openTrusted(screen, client.api().pageUrl("/u/" + signed.account().username()));
                }
            }
            case "account.open_standing" -> LinkActions.openTrusted(screen, client.api().pageUrl("/account/standing"));
            case "account.mark_read" -> markRead();
            case AccountForm.NETWORK -> toggleNetwork();
            default -> {
                // Rows and notes are not clickable.
            }
        }
    }

    /**
     * Asks for the network, remembering what the press was for.
     *
     * <p>The consent dialog answers to the Workshop, not to this view, so a first-session player who pressed Sign in
     * before allowing the network got the question and then nothing: the press was spent on it. {@link #setArea} runs
     * the remembered press once the answer is in, as the set page does for Install.
     */
    private void askNetwork(Runnable retry) {
        afterConsent = retry;
        screen.openConsent();
    }

    /**
     * The Network access switch. Off → on goes through the first-run screen, as every other way to allow it does. On →
     * off stops the Workshop's network access at once: downloads are cancelled, the listing shows its offline state with
     * "Allow network", and the sign-in and the remembered Minecraft account are kept for when it is turned on again.
     */
    private void toggleNetwork() {
        if (!client.networkAllowed()) {
            askNetwork(() -> {
                rebuild();
                loadAccount();
            });
            return;
        }
        try {
            client.setNetworkConsent(false);
        } catch (java.io.IOException exception) {
            ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Unable to save the Workshop network choice: {}", exception.getMessage());
            error = WorkshopTr.INSTANCE.get("screen.music_battle.workshop.consent.save_failed");
            rebuild();
            return;
        }
        status = null;
        busy = false;
        screen.networkTurnedOff();
        rebuild();
    }

    private void signInWithMinecraft() {
        if (!client.networkAllowed()) {
            askNetwork(this::signInWithMinecraft);
            return;
        }
        busy = true;
        status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.checking");
        rebuild();
        MinecraftProof.proveNow(client, MinecraftSignIn.PURPOSE_SESSION).whenComplete((result, failure) -> deliver(() -> {
            busy = false;
            status = null;
            if (failure != null || result == null) {
                error = WorkshopText.error("mc_join_failed").getString();
                rebuild();
                return;
            }
            switch (result.outcome()) {
                case SIGNED_IN -> {
                    mode = AccountForm.Mode.SIGNED_IN;
                    loadAccount();
                }
                case NOT_LINKED -> {
                    canLink = true;
                    error = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.not_linked");
                }
                case AUTO_SIGN_IN_DISABLED -> error = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.auto_disabled");
                default -> error = WorkshopText.error(result.errorCode()).getString();
            }
            rebuild();
        }));
    }

    private void startDeviceFlow() {
        if (!client.networkAllowed()) {
            askNetwork(this::startDeviceFlow);
            return;
        }
        busy = true;
        rebuild();
        boolean rememberMe = remember;
        screen.run(() -> {
            try {
                client.deviceFlow().start(ClientLabels.forGame(System.getProperty("os.name")), rememberMe, CancelToken.none());
                if (closed) {
                    abandonDeviceSignIn();
                    return;
                }
                deliver(() -> {
                    busy = false;
                    if (closed) {
                        abandonDeviceSignIn();
                        return;
                    }
                    mode = AccountForm.Mode.DEVICE_WAITING;
                    status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.waiting");
                    rebuild();
                });
            } catch (WorkshopApiException exception) {
                deliver(() -> {
                    busy = false;
                    error = WorkshopText.error(exception.code()).getString();
                    rebuild();
                });
            }
        });
    }

    private void startLink() {
        MinecraftSignIn.Player player = MinecraftProof.playerOrNull();
        if (!MinecraftProof.microsoftAccount() || player == null) {
            error = WorkshopText.error("mc_invalid_session").getString();
            rebuild();
            return;
        }
        busy = true;
        rebuild();
        screen.run(() -> {
            MinecraftSignIn.LinkPhase phase = client.signIn().startLink(player, CancelToken.none());
            deliver(() -> {
                busy = false;
                mode = AccountForm.Mode.LINK_WAITING;
                onLinkPhase(phase);
            });
        });
    }

    private void openVerification() {
        String url = mode == AccountForm.Mode.LINK_WAITING
                ? client.signIn().linkSnapshot().verificationUriComplete()
                : client.deviceFlow().snapshot().verificationUriComplete();
        if (url == null) {
            url = client.api().pageUrl("/link");
        }
        LinkActions.openTrusted(screen, url);
    }

    private void copyCode() {
        String code = mode == AccountForm.Mode.LINK_WAITING
                ? client.signIn().linkSnapshot().userCode() : client.deviceFlow().snapshot().userCode();
        if (LinkActions.copy(code)) {
            status = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "account.code_copied");
            rebuild();
        }
    }

    private void cancelFlow() {
        if (mode == AccountForm.Mode.LINK_WAITING) {
            client.signIn().cancelLink();
        } else {
            abandonDeviceSignIn();
        }
        mode = client.session().signedIn() ? AccountForm.Mode.SIGNED_IN : AccountForm.Mode.SIGNED_OUT;
        status = null;
        rebuild();
    }

    /**
     * Stops the code sign-in here and tells the service, which otherwise keeps the code valid for its full ten minutes
     * and counts it against the few sign-ins one connection may have waiting: two friends behind one router who each
     * opened and left the sign-in could lock themselves out. A link request is not counted there and has no such call.
     */
    private void abandonDeviceSignIn() {
        client.deviceFlow().cancel();
        screen.run(() -> {
            try {
                client.api().cancelStartedDevice(CancelToken.none());
            } catch (WorkshopApiException ignored) {
                // Best effort: an unheard cancel only means the code expires on its own, as it always did.
            }
        });
    }

    private void notMe() {
        busy = true;
        rebuild();
        screen.run(() -> {
            client.deviceFlow().notMe(CancelToken.none());
            deliver(() -> {
                busy = false;
                mode = AccountForm.Mode.SIGNED_OUT;
                rebuild();
            });
        });
    }

    private void signOut() {
        screen.showDialog(WorkshopConfirmScreen.confirm(screen,
                Component.translatable(WorkshopStrings.KEY + "account.sign_out_title"),
                Component.translatable(WorkshopStrings.KEY + "account.sign_out_body"),
                Component.translatable(WorkshopStrings.KEY + "account.sign_out"), () -> screen.run(() -> {
                    // An explicit sign-out: the next launch no longer signs this Minecraft account in by itself.
                    MinecraftSignIn.Player current = MinecraftProof.playerOrNull();
                    if (current != null) {
                        client.signIn().forget(current.uuid());
                    }
                    WorkshopSession.State before = client.session().state();
                    if (before.minecraft() != null && before.minecraft().uuid() != null) {
                        try {
                            client.signIn().forget(java.util.UUID.fromString(before.minecraft().uuid()));
                        } catch (IllegalArgumentException notUuid) {
                            // Nothing remembered under a name that is not a UUID.
                        }
                    }
                    client.session().signOut(CancelToken.none());
                    client.forgetParticipation();
                    deliver(() -> {
                        mode = AccountForm.Mode.SIGNED_OUT;
                        notifications = List.of();
                        minecraft = List.of();
                        // Everything below belonged to the account that just left. The signed-out screen now offers
                        // the link button on this flag, so an account kept behind would offer to link to itself.
                        canLink = false;
                        profile = null;
                        card = null;
                        unread = 0;
                        standing = null;
                        rules = null;
                        rulesChanged = null;
                        rulesPending = false;
                        rebuild();
                    });
                })));
    }

    private void markRead() {
        screen.run(() -> {
            try {
                client.session().authorized(api -> {
                    api.markAllNotificationsRead(CancelToken.none());
                    return null;
                });
                deliver(() -> {
                    unread = 0;
                    rebuild();
                });
            } catch (WorkshopApiException exception) {
                screen.showError(exception.code());
            }
        });
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return form.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        return form.mouseDragged(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return form.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return form.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return form.charTyped(character, modifiers);
    }
}
