package ru.gora.musicbattle.client.workshop;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextField;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;

/**
 * The report dialog: the reasons of the target type, a details field that some reasons
 * require, and "I own the rights / legal complaint", which files no report but opens
 * {@code /legal/notice?url=…} through the trusted confirm-link dialog.
 */
public final class WorkshopReportScreen extends WorkshopDialogScreen {
    /** Beatmap-set reasons, in the website's order. */
    public static final List<String> SET_REASONS = List.of("rights_concern", "plagiarism", "inappropriate_cover",
            "inappropriate_audio", "explicit_unmarked", "photosensitivity_unmarked", "metadata_misleading", "impersonation",
            "malicious_or_broken", "spam_or_duplicate", "ai_undisclosed", "personal_data", "illegal_other", "other");
    public static final List<String> COMMENT_REASONS = List.of("harassment", "hate", "sexual_or_nsfw", "threat_or_self_harm",
            "personal_data", "spam", "piracy_link", "spoiler", "off_topic", "impersonation", "illegal_other", "other");
    /** Reasons of a beatmap-set report that need no details (every other one does, like "other"). */
    private static final List<String> SET_DETAILS_OPTIONAL = List.of("explicit_unmarked", "photosensitivity_unmarked",
            "metadata_misleading");

    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final String targetType;
    private final long targetId;
    private final List<String> reasons;
    private final TextField details;
    private String reason;
    private boolean sending;

    private WorkshopReportScreen(WorkshopScreen screen, WorkshopClient client, String targetType, long targetId, String subject,
                                 List<String> reasons) {
        super(screen, Component.translatable(WorkshopStrings.KEY + "report.title", subject), UiIcon.FLAG, Palette.DANGER);
        this.screen = screen;
        this.client = client;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reasons = reasons;
        this.reason = reasons.get(0);
        addParagraph(Component.translatable(WorkshopStrings.KEY + "report.intro"));
        for (String code : reasons) {
            addChoice(Component.translatable(WorkshopStrings.DATA + "report.reason." + code), () -> code.equals(reason),
                    () -> reason = code);
        }
        details = addField(Component.translatable(WorkshopStrings.KEY + "report.details"), true, 2000);
        addLink(Component.translatable(WorkshopStrings.KEY + "report.legal"), UiIcon.EXTERNAL_LINK, this::openLegalNotice);
        addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, this::onClose);
        addAction(Component.translatable(WorkshopStrings.KEY + "report.send"), WorkshopButton.Kind.DANGER, UiIcon.FLAG, this::send);
        setCancelAction(this::onClose);
    }

    public static WorkshopReportScreen forSet(WorkshopScreen screen, WorkshopClient client, long setId, String title) {
        return new WorkshopReportScreen(screen, client, "beatmapset", setId, title, SET_REASONS);
    }

    public static WorkshopReportScreen forComment(WorkshopScreen screen, WorkshopClient client, long commentId, String author) {
        return new WorkshopReportScreen(screen, client, "comment", commentId, author, COMMENT_REASONS);
    }

    /** "I own the rights / legal complaint" never files a report. */
    private void openLegalNotice() {
        String target = client.api().pageUrl(targetType.equals("comment") ? "/comments/" + targetId : "/beatmapsets/" + targetId);
        String url = client.api().pageUrl("/legal/notice?url=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
        LinkActions.openTrusted(this, url);
    }

    private boolean detailsRequired() {
        if ("other".equals(reason)) {
            return true;
        }
        return targetType.equals("beatmapset") && !SET_DETAILS_OPTIONAL.contains(reason);
    }

    private void send() {
        if (sending) {
            return;
        }
        if (!client.session().signedIn()) {
            setStatus(Component.translatable(WorkshopStrings.KEY + "report.sign_in"), Palette.WARN);
            relayout();
            return;
        }
        String text = details.value().trim();
        if (detailsRequired() && text.isEmpty()) {
            setStatus(Component.translatable(WorkshopStrings.KEY + "report.details_required"), Palette.WARN);
            relayout();
            return;
        }
        sending = true;
        setStatus(Component.translatable(WorkshopStrings.KEY + "report.sending"), Palette.TEXT_MUTE);
        relayout();
        String chosen = reason;
        screen.run(() -> {
            try {
                client.session().authorized(api -> api.report(targetType, targetId, chosen, text, CancelToken.none()));
                screen.later(() -> {
                    sending = false;
                    onClose();
                    screen.showMessage(Component.translatable(WorkshopStrings.KEY + "report.sent_title"),
                            Component.translatable(WorkshopStrings.KEY + "report.sent"));
                });
            } catch (WorkshopApiException exception) {
                screen.later(() -> {
                    sending = false;
                    // Terms section 3.1: a report is refused until the rules are accepted, like a comment or a rating.
                    // The report comes back either way: accepted and sent, or cancelled and still on screen with
                    // everything the player wrote (mod review, 18 September 2026).
                    if (screen.handleRulesRefusal(exception.code(), this::resend, this::restore)) {
                        return;
                    }
                    setStatus(WorkshopText.error(exception.code()), Palette.DANGER);
                    relayout();
                });
            }
        });
    }

    /**
     * Runs after the acceptance dialog: the dialog replaced this one, so the report comes back with the reason and the
     * details the player already wrote, and sends itself.
     */
    private void resend() {
        restore();
        send();
    }

    /** Puts the dialog back with the reason and the details intact, without sending anything. */
    private void restore() {
        setStatus(Component.empty(), Palette.TEXT_MUTE);
        screen.showDialog(this);
        relayout();
    }
}
