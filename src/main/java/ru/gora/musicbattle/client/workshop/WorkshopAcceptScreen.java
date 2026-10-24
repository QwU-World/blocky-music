package ru.gora.musicbattle.client.workshop;

import java.util.List;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;

/**
 * The lighter of the two one-time acceptances (Workshop Terms section 3.1): the Workshop Terms and the Community Rules,
 * ticked once before a player's first comment or rating. The upload wizard carries the bigger one and includes this,
 * so an uploader never sees this dialog.
 *
 * The gate itself is the server's: it refuses with {@code rules_not_accepted} or {@code rules_outdated} and this
 * screen is how a player answers without leaving the game, after which the refused action runs again. The Workshop
 * also offers the dialog before the refusal, from the comment composer and the account view, once {@code /me} says the
 * acceptance is still owed.
 *
 * <p>The age confirmation carries the key {@code age}; the age it states is the service's, which is 16 since
 * 18.09.2026.
 */
public final class WorkshopAcceptScreen extends WorkshopDialogScreen {
    /** Same keys and order as the website panel; the server records which ones were ticked. */
    private static final List<String> CONFIRMATIONS = List.of("terms", "age");

    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final Runnable afterAccept;
    /** Runs when the dialog closes without an acceptance, so a caller holding typed text can put it back on screen. */
    private final Runnable onCancel;
    private boolean accepted;
    private final boolean[] ticked = new boolean[CONFIRMATIONS.size()];
    private boolean sending;

    private WorkshopAcceptScreen(WorkshopScreen screen, WorkshopClient client, WorkshopDtos.Rules rules, Runnable afterAccept,
                                 Runnable onCancel) {
        super(screen, Component.translatable(WorkshopStrings.KEY + "accept.title"), UiIcon.CHECK, Palette.ACCENT);
        this.screen = screen;
        this.client = client;
        this.afterAccept = afterAccept;
        this.onCancel = onCancel;
        addParagraph(Component.translatable(WorkshopStrings.KEY + "accept.lead"));
        if (rules != null && rules.needsReaccept()) {
            String changes = rules.changes();
            addParagraph(changes == null || changes.isEmpty()
                    ? Component.translatable(WorkshopStrings.KEY + "accept.changed")
                    : Component.translatable(WorkshopStrings.KEY + "accept.changed_with", changes));
        }
        String terms = rules == null ? "1" : rules.termsVersion();
        String community = rules == null ? "1" : rules.rulesVersion();
        addChoice(Component.translatable(WorkshopStrings.KEY + "accept.terms", terms, community), () -> ticked[0], () -> ticked[0] = !ticked[0]);
        addChoice(Component.translatable(WorkshopStrings.KEY + "accept.age"), () -> ticked[1], () -> ticked[1] = !ticked[1]);
        addLink(Component.translatable(WorkshopStrings.KEY + "accept.read_terms"), UiIcon.EXTERNAL_LINK,
                () -> LinkActions.openTrusted(this, client.api().pageUrl("/legal/terms")));
        addLink(Component.translatable(WorkshopStrings.KEY + "accept.read_community"), UiIcon.EXTERNAL_LINK,
                () -> LinkActions.openTrusted(this, client.api().pageUrl("/rules/community")));
        addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, this::cancel);
        addAction(Component.translatable(WorkshopStrings.KEY + "accept.continue"), WorkshopButton.Kind.PRIMARY, UiIcon.CHECK, this::send);
        setCancelAction(this::cancel);
    }

    /**
     * Opens the dialog for a player who still owes the acceptance. The current versions come from {@code /meta} when
     * the account state has not been read yet, so the dialog works even on the first screen of a session.
     */
    public static WorkshopAcceptScreen open(WorkshopScreen screen, WorkshopClient client, WorkshopDtos.Rules rules, Runnable afterAccept) {
        return open(screen, client, rules, afterAccept, null);
    }

    /** The same, with something to run when the player closes the dialog instead of accepting. */
    public static WorkshopAcceptScreen open(WorkshopScreen screen, WorkshopClient client, WorkshopDtos.Rules rules,
                                            Runnable afterAccept, Runnable onCancel) {
        return new WorkshopAcceptScreen(screen, client, rules == null ? fromMeta(client) : rules, afterAccept, onCancel);
    }

    /** Closing without accepting: whatever the caller was holding comes back, so nothing a player typed is lost. */
    private void cancel() {
        onClose();
        if (!accepted && onCancel != null) {
            onCancel.run();
        }
    }

    /** The current versions come from {@code /meta}; whether a document moved comes from the cached {@code /me}. */
    private static WorkshopDtos.Rules fromMeta(WorkshopClient client) {
        WorkshopDtos.Meta meta = client.meta();
        WorkshopDtos.Rules known = client.participation();
        String terms = meta == null || meta.termsVersion() == null ? "1" : meta.termsVersion();
        String community = meta == null || meta.communityRulesVersion() == null ? "1" : meta.communityRulesVersion();
        return new WorkshopDtos.Rules(false, known == null ? null : known.acceptedAt(), terms, community,
                known != null && known.needsReaccept(), known == null ? null : known.changes());
    }

    private void send() {
        if (sending) {
            return;
        }
        for (boolean box : ticked) {
            if (!box) {
                setStatus(Component.translatable(WorkshopStrings.KEY + "accept.error"), Palette.WARN);
                relayout();
                return;
            }
        }
        if (!client.session().signedIn()) {
            setStatus(Component.translatable(WorkshopStrings.KEY + "accept.sign_in"), Palette.WARN);
            relayout();
            return;
        }
        sending = true;
        setStatus(Component.translatable(WorkshopStrings.KEY + "accept.sending"), Palette.TEXT_MUTE);
        relayout();
        WorkshopDtos.Meta meta = client.meta();
        String terms = meta == null || meta.termsVersion() == null ? "1" : meta.termsVersion();
        String community = meta == null || meta.communityRulesVersion() == null ? "1" : meta.communityRulesVersion();
        screen.run(() -> {
            try {
                WorkshopDtos.Rules state = client.session()
                        .authorized(api -> api.acceptRules("participation", terms, community, CONFIRMATIONS, CancelToken.none()));
                screen.later(() -> {
                    sending = false;
                    accepted = true;
                    // Everything that asks about the acceptance now reads the answer the server just gave.
                    client.rememberParticipation(state);
                    onClose();
                    if (afterAccept != null) {
                        afterAccept.run();
                    }
                });
            } catch (WorkshopApiException exception) {
                screen.later(() -> {
                    sending = false;
                    setStatus(WorkshopText.error(exception.code()), Palette.DANGER);
                    relayout();
                });
            }
        });
    }
}
