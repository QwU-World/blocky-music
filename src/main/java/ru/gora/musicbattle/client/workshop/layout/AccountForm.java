package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import ru.gora.musicbattle.client.workshop.layout.Form.Item;

/**
 * The account view as a {@link Form} (spec 06 section 6.4, 08 A1.2): signing in with the Minecraft account or with a
 * code, the device code with "Open the link" and "Copy code", the "Signed in as @username. Continue · This isn't me"
 * step, the signed-in state with the linked Minecraft accounts, the standing banner, the one-time acceptance of the
 * rules, notifications and sign-out.
 */
public final class AccountForm {
    public enum Mode {
        SIGNED_OUT, DEVICE_WAITING, DEVICE_CONFIRM, SIGNED_IN, LINK_WAITING
    }

    /**
     * The profile card: chips, the joined line and the counters are already translated. Null while the profile has not
     * been fetched yet (or could not be), and the view falls back to the plain name row.
     */
    public record Card(List<Form.Chip> chips, String since, List<Form.Stat> stats, boolean banner) {
    }

    /**
     * {@code status}, {@code error}, {@code rules} and {@code rulesChanged} are already translated strings, or null.
     * {@code rules} says whether the one-time acceptance is given and when; {@code rulesChanged} carries the line about
     * a document that moved, and {@code rulesPending} offers the acceptance right here.
     */
    public record State(Mode mode, boolean minecraftAccount, boolean remember, String userCode, String status, String error,
                        String username, String displayName, List<Form.Row> minecraft, boolean canLink, boolean autoSignIn,
                        List<String> notifications, String standing, int unread, boolean busy, String rules,
                        String rulesChanged, boolean rulesPending, Card card) {
    }

    private AccountForm() {
    }

    public static List<Item> build(Tr tr, State state) {
        String k = WorkshopStrings.KEY;
        Form.Builder form = Form.builder();
        switch (state.mode()) {
            case SIGNED_OUT -> {
                form.heading("account.heading", tr.get(k + "account.heading"));
                form.paragraph("account.intro", tr.get(k + "account.intro"));
                if (state.minecraftAccount()) {
                    form.buttons("account.mc.row", Form.primary("account.mc", tr.get(k + "account.sign_in_minecraft"), !state.busy()));
                    form.note("account.mc.note", tr.get(k + "account.sign_in_minecraft.note"), "normal");
                    if (state.canLink()) {
                        // "This Minecraft account is not linked yet" is the one refusal a player can answer from here,
                        // and this flow is the answer: the proof that refusal came with still starts a link request
                        // for ten minutes. The button lived in the signed-in branch alone, so the refused sign-in left
                        // the screen saying what was wrong and offering nothing to do about it.
                        form.buttons("account.link.row", Form.button("account.link", tr.get(k + "account.link_minecraft")));
                    }
                }
                form.buttons("account.device.row", state.minecraftAccount()
                        ? Form.button("account.device", tr.get(k + "account.sign_in_code"))
                        : Form.primary("account.device", tr.get(k + "account.sign_in_code"), !state.busy()));
                form.note("account.device.note", tr.get(k + "account.sign_in_code.note"), "normal");
                form.toggle("account.remember", tr.get(k + "account.remember"), state.remember(), true);
                form.buttons("account.about.row", Form.button("account.about", tr.get(k + "account.what_is_qwu")));
            }
            case DEVICE_WAITING, LINK_WAITING -> {
                boolean link = state.mode() == Mode.LINK_WAITING;
                form.heading("account.heading", tr.get(k + (link ? "account.link_heading" : "account.code_heading")));
                form.paragraph("account.code.intro", tr.get(k + (link ? "account.link_intro" : "account.code_intro")));
                form.code("account.code", state.userCode() == null ? "····-····" : state.userCode());
                form.buttons("account.code.row", Form.primary("account.open_link", tr.get(k + "account.open_link"), true),
                        Form.button("account.copy_code", tr.get(k + "account.copy_code")));
                if (state.status() != null && !state.status().isEmpty()) {
                    form.note("account.status", state.status(), "normal");
                }
                form.buttons("account.cancel.row", Form.button("account.cancel", tr.get(k + "cancel")));
            }
            case DEVICE_CONFIRM -> {
                form.heading("account.heading", tr.get(k + "account.signed_in_as", "@" + safe(state.username())));
                form.paragraph("account.confirm.intro", tr.get(k + "account.confirm_intro"));
                form.buttons("account.confirm.row", Form.primary("account.continue", tr.get(k + "account.continue"), true),
                        Form.button("account.not_me", tr.get(k + "account.not_me")));
            }
            case SIGNED_IN -> {
                if (state.card() != null) {
                    form.profile("account.card", safe(state.displayName()), "@" + safe(state.username()),
                            state.card().chips(), state.card().since(), state.card().stats(), state.card().banner());
                } else {
                    form.avatar("account.avatar", safe(state.displayName()), "@" + safe(state.username()));
                }
                if (state.standing() != null && !state.standing().isEmpty()) {
                    form.note("account.standing", state.standing(), "danger");
                    form.buttons("account.standing.row", Form.button("account.open_standing", tr.get(k + "account.open_standing")));
                }
                form.separator("account.sep1");
                form.heading("account.minecraft.heading", tr.get(k + "account.minecraft"));
                if (state.minecraft().isEmpty()) {
                    form.note("account.minecraft.none", tr.get(k + "account.minecraft.none"), "normal");
                } else {
                    for (int i = 0; i < state.minecraft().size(); i++) {
                        Form.Row row = state.minecraft().get(i);
                        form.row("account.minecraft." + i, row.label(), row.value());
                    }
                }
                form.row("account.auto", tr.get(k + "account.auto_sign_in"),
                        tr.get(k + (state.autoSignIn() ? "account.auto_sign_in.on" : "account.auto_sign_in.off")));
                if (state.canLink()) {
                    form.buttons("account.link.row", Form.button("account.link", tr.get(k + "account.link_minecraft")));
                }
                if (state.rules() != null && !state.rules().isEmpty()) {
                    // The one-time acceptance (Terms section 3.1), where a player can see it without hitting a refusal.
                    form.separator("account.sep.rules");
                    form.heading("account.rules.heading", tr.get(k + "account.rules"));
                    form.row("account.rules.state", tr.get(k + "account.rules.label"), state.rules());
                    if (state.rulesChanged() != null && !state.rulesChanged().isEmpty()) {
                        form.note("account.rules.changed", state.rulesChanged(), "warn");
                    }
                    if (state.rulesPending()) {
                        form.buttons("account.rules.row", Form.primary("account.rules.accept", tr.get(k + "account.accept_rules"),
                                !state.busy()), Form.button("account.rules.open", tr.get(k + "account.open_rules")));
                    } else {
                        form.buttons("account.rules.row", Form.button("account.rules.open", tr.get(k + "account.open_rules")));
                    }
                }
                form.separator("account.sep2");
                form.heading("account.notifications.heading", tr.get(k + "account.notifications", String.valueOf(state.unread())));
                if (state.notifications().isEmpty()) {
                    form.note("account.notifications.none", tr.get(k + "account.notifications.none"), "normal");
                } else {
                    for (int i = 0; i < state.notifications().size(); i++) {
                        form.note("account.notification." + i, state.notifications().get(i), "normal");
                    }
                    form.buttons("account.notifications.row", Form.button("account.mark_read", tr.get(k + "account.mark_read")));
                }
                form.separator("account.sep3");
                form.buttons("account.profile.row", Form.button("account.open_profile", tr.get(k + "account.open_profile")));
                form.buttons("account.actions", Form.button("account.manage", tr.get(k + "account.manage")),
                        Form.danger("account.sign_out", tr.get(k + "account.sign_out")));
            }
            default -> {
                // Every mode is handled above; a future one shows nothing rather than crashing.
            }
        }
        if (state.error() != null && !state.error().isEmpty()) {
            form.note("account.error", state.error(), "danger");
        }
        return form.build();
    }

    private static String safe(String value) {
        return value == null ? "?" : value;
    }
}
