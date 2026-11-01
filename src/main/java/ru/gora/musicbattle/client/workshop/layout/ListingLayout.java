package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import java.util.Set;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * The listing (lazer's {@code BeatmapListingOverlay}): the search panel with its filter rows, the
 * sort bar with the card-size toggle, and the card grid area. Everything is laid out in the scroll container's own
 * coordinates (y = 0 is the top of the content), so the screen only adds its scroll offset.
 *
 * <p>The Installed tab uses the same layout with {@code installed = true}: text search, its own sort fields and the
 * two file actions instead of the website's filter rows.
 */
public final class ListingLayout {
    public static final String SEARCH = "listing.search";
    public static final String SORT_BAR = "listing.sortbar";
    public static final String GRID = "listing.grid";

    /** Everything the listing needs to lay itself out. Nothing here touches Minecraft. */
    public record State(boolean installed, String query, String category, Set<String> general, int genre, int language,
                        String audio, Set<String> extra, boolean explicit, String sort, boolean sortAscending,
                        boolean moreFilters, boolean extraCards, boolean serverFilter, String installedSort,
                        Long directSetId, String status, String statusDetail, int cardCount, boolean loading,
                        boolean hasMore) {
        public String effectiveSort() {
            return installed ? (Filters.INSTALLED_SORTS.contains(installedSort) ? installedSort : "title")
                    : Filters.effectiveSort(query, category, sort);
        }

        /**
         * How many of the advanced rows are narrowing the search. Those rows hide behind "More filters" but keep
         * filtering while they are hidden, so the collapsed button carries this number: a player who collapsed the
         * panel and then wondered where half the beatmaps went can see that four of them are still on.
         */
        public int advancedFilters() {
            int count = genre > 0 ? 1 : 0;
            count += language > 0 ? 1 : 0;
            count += audio != null && !Filters.AUDIO_ANY.equals(audio) ? 1 : 0;
            return count + (extra == null ? 0 : extra.size());
        }
    }

    private ListingLayout() {
    }

    public static Layout compute(Rect content, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        boolean compact = content.w() < Metrics.COMPACT_WIDTH;
        float pad = compact ? Metrics.PAD_SM : Metrics.PAD;
        float width = content.w();
        float y = 0;

        // ── search panel ────────────────────────────────────────────────────────────────────────────────────
        float searchTop = y;
        float inner = width - pad * 2;
        float fieldH = compact ? Metrics.BUTTON_H_SM : Metrics.FIELD_H;
        Rect field = new Rect(pad, y + pad, inner, fieldH);
        float panelHeight;
        layout.add(SEARCH, "", Layout.Kind.PANEL, new Rect(0, searchTop, width, fieldH + pad * 2));
        layout.add("listing.search.field", SEARCH, Layout.Kind.FIELD, field);
        layout.add("listing.search.icon", "listing.search.field", Layout.Kind.ICON,
                new Rect(field.right() - Metrics.ICON - 4, field.y() + (fieldH - Metrics.ICON) * 0.5F, Metrics.ICON, Metrics.ICON));
        y = field.bottom() + pad;

        if (state.installed()) {
            float buttonH = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
            float half = (inner - Metrics.GAP) * 0.5F;
            Rect importButton = new Rect(pad, y, Math.min(half, 130), buttonH);
            layout.add("listing.tool.import", SEARCH, Layout.Kind.BUTTON, importButton);
            layout.labelCentered("listing.tool.import.text", "listing.tool.import", importButton.centerX(),
                    ShellLayout.capY(TextStyle.CAPTION, importButton.y(), buttonH), importButton.w() - 6,
                    tr.get(WorkshopStrings.KEY + "installed.import"), TextStyle.CAPTION);
            Rect inbox = new Rect(importButton.right() + Metrics.GAP, y, Math.min(half, 130), buttonH);
            layout.add("listing.tool.inbox", SEARCH, Layout.Kind.BUTTON, inbox);
            layout.labelCentered("listing.tool.inbox.text", "listing.tool.inbox", inbox.centerX(),
                    ShellLayout.capY(TextStyle.CAPTION, inbox.y(), buttonH), inbox.w() - 6,
                    tr.get(WorkshopStrings.KEY + "installed.open_inbox"), TextStyle.CAPTION);
            y = inbox.bottom() + pad;
        } else {
            y = filterRows(layout, tr, measurer, state, pad, inner, y, compact);
        }
        panelHeight = y - searchTop;
        layout.resize(SEARCH, new Rect(0, searchTop, width, panelHeight));

        // ── sort bar ────────────────────────────────────────────────────────────────────────────────────────
        y += Metrics.GAP_SM;
        float sortTop = y;
        layout.add(SORT_BAR, "", Layout.Kind.PANEL, new Rect(0, sortTop, width, Metrics.OPTION_H + 4));
        float sortRowH = Metrics.OPTION_H + 2;
        float toggleSize = Math.min(sortRowH, 14F);
        Rect normalToggle = new Rect(width - pad - toggleSize * 2 - Metrics.GAP_SM, y + 2, toggleSize, toggleSize);
        Rect extraToggle = new Rect(normalToggle.right() + Metrics.GAP_SM, y + 2, toggleSize, toggleSize);
        Rect sortArea;
        float sortX = pad;
        float sortRight = state.installed() ? width - pad : normalToggle.x() - Metrics.GAP;
        String sortLabel = tr.get(WorkshopStrings.KEY + "sort.by");
        float labelWidth = TextFit.width(measurer, sortLabel, TextStyle.CAPTION);
        float rowY = y + 2;
        layout.label("listing.sort.label", SORT_BAR, sortX, ShellLayout.capY(TextStyle.CAPTION, rowY, Metrics.OPTION_H),
                Math.min(labelWidth, 70), sortLabel, TextStyle.CAPTION);
        sortX += Math.min(labelWidth, 70) + Metrics.OPTION_GAP;
        List<String> sorts = state.installed() ? Filters.INSTALLED_SORTS : Filters.visibleSorts(state.query(), state.category());
        String active = state.effectiveSort();
        for (String sortField : sorts) {
            String label = tr.get(WorkshopStrings.KEY + "sort." + sortField);
            boolean isActive = sortField.equals(active);
            TextStyle style = isActive ? TextStyle.LABEL : TextStyle.CAPTION;
            float caret = isActive ? Metrics.ICON_SM + 2 : 0;
            float optionWidth = Math.min(sortRight - pad, TextFit.width(measurer, label, style) + 8 + caret);
            if (sortX + optionWidth > sortRight && sortX > pad) {
                sortX = pad;
                rowY += sortRowH;
            }
            Rect box = new Rect(sortX, rowY, optionWidth, Metrics.OPTION_H);
            layout.add("listing.sort." + sortField, SORT_BAR, Layout.Kind.BUTTON, box);
            layout.label("listing.sort." + sortField + ".text", "listing.sort." + sortField, box.x() + 4,
                    ShellLayout.capY(style, box.y(), Metrics.OPTION_H), optionWidth - 8 - caret, label, style);
            if (isActive) {
                layout.add("listing.sort." + sortField + ".caret", "listing.sort." + sortField, Layout.Kind.ICON,
                        new Rect(box.right() - Metrics.ICON_SM - 2, box.y() + (Metrics.OPTION_H - Metrics.ICON_SM) * 0.5F,
                                Metrics.ICON_SM, Metrics.ICON_SM));
            }
            sortX = box.right() + Metrics.OPTION_GAP;
        }
        float sortHeight = rowY + Metrics.OPTION_H + 4 - sortTop;
        sortArea = new Rect(0, sortTop, width, sortHeight);
        layout.resize(SORT_BAR, sortArea);
        if (!state.installed()) {
            layout.add("listing.cards.normal", SORT_BAR, Layout.Kind.BUTTON, normalToggle);
            layout.add("listing.cards.extra", SORT_BAR, Layout.Kind.BUTTON, extraToggle);
        }
        y = sortArea.bottom() + Metrics.GAP_SM;

        // ── "Open set #42" row (a bare number stays a normal search) ───────────────
        if (state.directSetId() != null) {
            float buttonH = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
            Rect direct = new Rect(0, y, width, buttonH);
            layout.add("listing.direct", "", Layout.Kind.BUTTON, direct);
            layout.label("listing.direct.text", "listing.direct", pad, ShellLayout.capY(TextStyle.BODY, direct.y(), buttonH),
                    width - pad * 2, tr.get(WorkshopStrings.KEY + "search.open_set", String.valueOf(state.directSetId())),
                    TextStyle.BODY);
            y = direct.bottom() + Metrics.GAP_SM;
        }

        // ── results ─────────────────────────────────────────────────────────────────────────────────────────
        float gridTop = y;
        float cardHeight = state.extraCards() ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H;
        int columns = Math.max(1, Math.min(Metrics.CARD_MAX_COLUMNS,
                (int) Math.floor((width + Metrics.CARD_GAP) / (Metrics.CARD_MIN_W + Metrics.CARD_GAP))));
        int rows = (state.cardCount() + columns - 1) / columns;
        float gridHeight = rows == 0 ? 0 : rows * cardHeight + (rows - 1) * Metrics.CARD_GAP;
        layout.add(GRID, "", Layout.Kind.SLOT, new Rect(0, gridTop, width, gridHeight));

        // The foot of the list says how much of it the player is looking at, and whether the site still has more to
        // give: with infinite scrolling there is otherwise no difference between the end of the results and a page
        // that never arrived.
        float below = gridTop + gridHeight;
        if (state.cardCount() > 0) {
            Rect footer = new Rect(pad, below + Metrics.GAP_SM, width - pad * 2, Metrics.OPTION_H);
            layout.labelCentered("listing.results", "", footer.centerX(),
                    ShellLayout.capY(TextStyle.CAPTION, footer.y(), footer.h()), footer.w(), footerText(tr, state),
                    TextStyle.CAPTION);
            below = footer.bottom();
        }

        boolean hasState = state.status() != null && !state.status().isEmpty() && !"ok".equals(state.status());
        if (hasState) {
            float stateY = below + (state.cardCount() > 0 ? Metrics.GAP : Metrics.PAD);
            Rect panel = new Rect(0, stateY, width, 0);
            layout.add("listing.state", "", Layout.Kind.PANEL, panel);
            float textY = stateY + Metrics.PAD;
            float textWidth = width - pad * 2;
            float used = layout.paragraph("listing.state.title", "", pad, textY, textWidth,
                    tr.get(WorkshopStrings.KEY + "state." + state.status()), TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY), 3);
            textY += used + Metrics.GAP_SM;
            if (state.statusDetail() != null && !state.statusDetail().isEmpty()) {
                textY += layout.paragraph("listing.state.detail", "", pad, textY, textWidth, state.statusDetail(),
                        TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 3) + Metrics.GAP_SM;
            }
            String actionKey = switch (state.status()) {
                case "offline", "error" -> "state.retry";
                case "consent" -> "state.allow_network";
                case "empty", "no_matches" -> "state.clear_filters";
                case "sign_in" -> "account.sign_in";
                default -> null;
            };
            if (actionKey != null) {
                String label = tr.get(WorkshopStrings.KEY + actionKey);
                float buttonH = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
                float buttonWidth = Math.min(textWidth, TextFit.width(measurer, label, TextStyle.BODY) + 24);
                Rect action = new Rect(pad, textY, buttonWidth, buttonH);
                layout.add("listing.state.action", "", Layout.Kind.BUTTON, action);
                layout.labelCentered("listing.state.action.text", "listing.state.action", action.centerX(),
                        ShellLayout.capY(TextStyle.BODY, action.y(), buttonH), buttonWidth - 8, label, TextStyle.BODY);
                textY = action.bottom();
            }
            layout.resize("listing.state", panel.withHeight(textY - stateY + Metrics.PAD));
            y = textY + Metrics.PAD;
        } else {
            y = below;
        }
        layout.setContentHeight(y + (state.cardCount() > 0 ? Metrics.CARD_DROPDOWN_MAX * 0.5F : Metrics.PAD));
        return layout;
    }

    /** The line under the last card: how many results are loaded, and what the list is doing after them. */
    private static String footerText(Tr tr, State state) {
        String count = WorkshopStrings.plural(tr, WorkshopStrings.KEY + "listing.results", state.cardCount());
        if (state.loading()) {
            return count + " · " + tr.get(WorkshopStrings.KEY + "listing.loading_more");
        }
        // The Installed tab is the whole local catalogue every time, so "that's everything" would state the obvious.
        return state.hasMore() || state.installed() ? count : count + " · " + tr.get(WorkshopStrings.KEY + "listing.end");
    }

    /** The filter rows of the Browse tab; returns the y below them. */
    private static float filterRows(Layout layout, Tr tr, TextMeasurer measurer, State state, float pad, float inner, float y,
                                    boolean compact) {
        float labelColumn = compact ? Metrics.FILTER_LABEL_W_COMPACT : Metrics.FILTER_LABEL_W;
        List<Filters.Row> rows = Filters.rows(tr, state.serverFilter());
        // The column is at least as wide as the longest word of any row's label, hidden rows too so it does not move
        // when "More filters" opens: a label wraps between its words and never inside one. At 46 units Russian broke
        // "Откровенный контент" into "Откровенн" / "ый контент" and "Дополнительно" into "Дополните" / "льно". Never
        // more than two fifths of the panel, which only a window narrower than the game allows would reach.
        for (Filters.Row row : rows) {
            labelColumn = Math.max(labelColumn, (float) Math.ceil(TextFit.widestWord(measurer, row.label(), TextStyle.CAPTION)));
        }
        labelColumn = Math.min(labelColumn, Math.max(Metrics.FILTER_LABEL_W_COMPACT, inner * 0.4F));
        float optionsX = pad + labelColumn + Metrics.GAP_SM;
        float optionsWidth = Math.max(40F, inner + pad - optionsX);
        for (Filters.Row row : rows) {
            if (row.advanced() && !state.moreFilters()) {
                continue;
            }
            float rowTop = y;
            float labelHeight = layout.paragraph("listing.filters." + row.id() + ".label", SEARCH, pad, y + 1, labelColumn,
                    row.label(), TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 2);
            float x = optionsX;
            float optionY = y;
            for (Filters.Option option : row.options()) {
                boolean active = isActive(state, row, option.id());
                TextStyle style = active ? TextStyle.LABEL : TextStyle.CAPTION;
                float extra = active && row.kind() == Filters.RowKind.MULTI ? Metrics.ICON_SM + 3 : 0;
                float optionWidth = Math.min(optionsWidth, TextFit.width(measurer, option.label(), style) + 6 + extra);
                if (x + optionWidth > optionsX + optionsWidth && x > optionsX) {
                    x = optionsX;
                    optionY += Metrics.OPTION_H + Metrics.FILTER_ROW_GAP;
                }
                Rect box = new Rect(x, optionY, optionWidth, Metrics.OPTION_H);
                String id = "listing.filters." + row.id() + ".opt." + option.id();
                layout.add(id, SEARCH, Layout.Kind.BUTTON, box);
                if (extra > 0) {
                    layout.add(id + ".clear", id, Layout.Kind.ICON,
                            new Rect(box.x() + 3, box.y() + (Metrics.OPTION_H - Metrics.ICON_SM) * 0.5F, Metrics.ICON_SM, Metrics.ICON_SM));
                }
                layout.label(id + ".text", id, box.x() + 3 + extra, ShellLayout.capY(style, box.y(), Metrics.OPTION_H),
                        optionWidth - 6 - extra, option.label(), style);
                x = box.right() + Metrics.OPTION_GAP;
            }
            y = Math.max(rowTop + labelHeight, optionY + Metrics.OPTION_H) + Metrics.FILTER_ROW_GAP + 1;
        }
        int advanced = state.advancedFilters();
        String moreLabel = state.moreFilters() ? tr.get(WorkshopStrings.KEY + "filter.fewer")
                : advanced > 0 ? tr.get(WorkshopStrings.KEY + "filter.more_count", String.valueOf(advanced))
                : tr.get(WorkshopStrings.KEY + "filter.more");
        float moreWidth = Math.min(inner, TextFit.width(measurer, moreLabel, TextStyle.CAPTION) + 14);
        Rect more = new Rect(optionsX, y, moreWidth, Metrics.OPTION_H);
        layout.add("listing.filters.more", SEARCH, Layout.Kind.BUTTON, more);
        layout.label("listing.filters.more.text", "listing.filters.more", more.x() + 4,
                ShellLayout.capY(TextStyle.CAPTION, more.y(), Metrics.OPTION_H), moreWidth - 8, moreLabel, TextStyle.CAPTION);
        return more.bottom() + pad;
    }

    /** Whether an option of a row is the current choice. */
    public static boolean isActive(State state, Filters.Row row, String optionId) {
        return switch (row.id()) {
            case "general" -> state.general().contains(optionId);
            case "category" -> optionId.equals(state.category() == null ? Filters.CATEGORY_DEFAULT : state.category());
            case "genre" -> optionId.equals(String.valueOf(state.genre()));
            case "language" -> optionId.equals(String.valueOf(state.language()));
            case "audio" -> optionId.equals(state.audio() == null ? Filters.AUDIO_ANY : state.audio());
            case "extra" -> state.extra().contains(optionId);
            case "explicit" -> optionId.equals(state.explicit() ? "show" : "hide");
            default -> false;
        };
    }

    /** Columns of the card grid for a content width (the screen's {@code CardGrid} uses the same formula). */
    public static int columns(float width) {
        return Math.max(1, Math.min(Metrics.CARD_MAX_COLUMNS,
                (int) Math.floor((width + Metrics.CARD_GAP) / (Metrics.CARD_MIN_W + Metrics.CARD_GAP))));
    }
}
