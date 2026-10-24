package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * A small form model: the account view (spec 06 section 6.4) and the upload wizard (section 6.5) are vertical stacks
 * of headings, paragraphs, fields, toggles, choices, rows, buttons and stage rows. Pure data, so the layout check
 * walks exactly what the screen draws, and one renderer handles every item kind.
 */
public final class Form {
    /** The state of one stage row of the upload pipeline. */
    public enum StageState {
        PENDING, RUNNING, DONE, FAILED, CANCELLED
    }

    public sealed interface Item permits Heading, Paragraph, Note, Field, Toggle, Choice, Row, Buttons, Stage, Code, Slider,
            Avatar, Profile, Separator {
        String id();
    }

    public record Heading(String id, String text) implements Item {
    }

    public record Paragraph(String id, String text) implements Item {
    }

    /** Small print: hints, errors and states. {@code tone} is "normal", "warn", "danger" or "ok". */
    public record Note(String id, String text, String tone) implements Item {
    }

    /** A text field; {@code lines} greater than one makes it multi-line. */
    public record Field(String id, String label, String value, String placeholder, int lines) implements Item {
    }

    public record Toggle(String id, String label, boolean on, boolean enabled) implements Item {
    }

    /** A radio option with an optional second line. */
    public record Choice(String id, String label, String detail, boolean selected, boolean enabled) implements Item {
    }

    /** A label on the left and a value on the right (linked accounts, counters). */
    public record Row(String id, String label, String value) implements Item {
    }

    public record Button(String id, String label, boolean primary, boolean enabled, boolean danger) {
    }

    /** One line holding one or more buttons. */
    public record Buttons(String id, List<Button> buttons) implements Item {
    }

    public record Stage(String id, String label, StageState state, String detail, float fraction) implements Item {
    }

    /** The device code, shown large. */
    public record Code(String id, String text) implements Item {
    }

    public record Slider(String id, String label, String value, float fraction) implements Item {
    }

    /** An avatar or head with a name and a second line. */
    public record Avatar(String id, String name, String detail) implements Item {
    }

    /** One chip of the profile card: {@code key} decides the colour, {@code text} is what the player reads. */
    public record Chip(String key, String text) {
    }

    /** One number of the profile card: how many, and what of. */
    public record Stat(String value, String label) {
    }

    /**
     * The profile card at the top of the account view: the picture, the name, what the person is here (chips such as
     * moderator or curator), when they joined, and what they have to show for it. The picture and the strip behind it
     * are supplied as textures by id ({@code <id>.avatar} and {@code <id>.banner}), so the card draws without knowing
     * where they come from.
     */
    public record Profile(String id, String name, String handle, List<Chip> chips, String since, List<Stat> stats,
                          boolean banner) implements Item {
    }

    public record Separator(String id) implements Item {
    }

    private Form() {
    }

    /** Builder that keeps the item list readable at the call site. */
    public static final class Builder {
        private final List<Item> items = new ArrayList<>();

        public Builder heading(String id, String text) {
            items.add(new Heading(id, text));
            return this;
        }

        public Builder paragraph(String id, String text) {
            items.add(new Paragraph(id, text));
            return this;
        }

        public Builder note(String id, String text, String tone) {
            items.add(new Note(id, text, tone));
            return this;
        }

        public Builder field(String id, String label, String value, String placeholder, int lines) {
            items.add(new Field(id, label, value, placeholder, lines));
            return this;
        }

        public Builder toggle(String id, String label, boolean on, boolean enabled) {
            items.add(new Toggle(id, label, on, enabled));
            return this;
        }

        public Builder choice(String id, String label, String detail, boolean selected, boolean enabled) {
            items.add(new Choice(id, label, detail, selected, enabled));
            return this;
        }

        public Builder profile(String id, String name, String handle, List<Chip> chips, String since, List<Stat> stats,
                               boolean banner) {
            items.add(new Profile(id, name, handle, chips == null ? List.of() : chips, since,
                    stats == null ? List.of() : stats, banner));
            return this;
        }

        public Builder row(String id, String label, String value) {
            items.add(new Row(id, label, value));
            return this;
        }

        public Builder buttons(String id, Button... buttons) {
            items.add(new Buttons(id, List.of(buttons)));
            return this;
        }

        public Builder stage(String id, String label, StageState state, String detail, float fraction) {
            items.add(new Stage(id, label, state, detail, fraction));
            return this;
        }

        public Builder code(String id, String text) {
            items.add(new Code(id, text));
            return this;
        }

        public Builder slider(String id, String label, String value, float fraction) {
            items.add(new Slider(id, label, value, fraction));
            return this;
        }

        public Builder avatar(String id, String name, String detail) {
            items.add(new Avatar(id, name, detail));
            return this;
        }

        public Builder separator(String id) {
            items.add(new Separator(id));
            return this;
        }

        public List<Item> build() {
            return List.copyOf(items);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Button button(String id, String label) {
        return new Button(id, label, false, true, false);
    }

    public static Button primary(String id, String label, boolean enabled) {
        return new Button(id, label, true, enabled, false);
    }

    public static Button danger(String id, String label) {
        return new Button(id, label, false, true, true);
    }
}
