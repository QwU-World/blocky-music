package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * The Workshop shell: a panel of 0.85 screen width on large screens and the full width below
 * 640 units, a title bar with the block-note icon and the lowercase title, the account chip and the close button, a
 * tab strip (Browse · Installed · Server beatmaps) with the Upload action, an optional notice line under the tabs, and
 * the content area the views fill.
 */
public final class ShellLayout {
    public static final String PANEL = "shell.panel";
    public static final String TITLE_BAR = "shell.titlebar";
    public static final String TABS = "shell.tabs";
    public static final String CONTENT = "shell.content";
    /** The line under the tabs that the open list needs read before its cards; laid out only when there is one. */
    public static final String NOTICE = "shell.notice";
    /** A notice wraps to this many lines at most; the narrowest window still reads a sentence in two. */
    private static final int NOTICE_LINES = 2;
    public static final float WIDE_FRACTION = 0.85F;
    public static final float FULL_WIDTH_BELOW = 640F;
    /** Room either side of a tab's label. */
    private static final float TAB_PAD = 4F;

    /** The tabs of the listing. SERVER shows only when the server the player is on offers its beatmaps, or a picker has some. */
    public enum Tab {
        BROWSE("browse"), INSTALLED("installed"), SERVER("server");

        private final String id;

        Tab(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /**
     * What the shell shows. {@code account} is the signed-in name or null for "Sign in"; {@code notice} is null, or a
     * sentence about the list under it that the cards themselves cannot say - a battle whose track list did not fit.
     */
    public record State(Tab tab, boolean serverTab, String account, boolean back, String title, String notice) {
        public State(Tab tab, boolean serverTab, String account, boolean back, String title) {
            this(tab, serverTab, account, back, title, null);
        }
    }

    private ShellLayout() {
    }

    public static Layout compute(float screenWidth, float screenHeight, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        float panelWidth = screenWidth < FULL_WIDTH_BELOW ? screenWidth : Math.round(screenWidth * WIDE_FRACTION);
        float panelX = (screenWidth - panelWidth) * 0.5F;
        Rect panel = new Rect(panelX, 0, panelWidth, screenHeight);
        layout.add(PANEL, "", Layout.Kind.PANEL, panel);

        boolean compact = Metrics.compact(screenWidth, screenHeight);
        float pad = Metrics.pad(screenWidth, screenHeight);
        float titleH = Metrics.titleBar(screenWidth, screenHeight);
        Rect titleBar = new Rect(panel.x(), 0, panelWidth, titleH);
        layout.add(TITLE_BAR, PANEL, Layout.Kind.PANEL, titleBar);

        float buttonH = Metrics.buttonHeight(screenWidth, screenHeight);
        float closeSize = titleH - 8 < 14 ? 14 : titleH - 8;
        Rect close = new Rect(titleBar.right() - pad - closeSize, (titleH - closeSize) * 0.5F, closeSize, closeSize);
        layout.add("shell.close", TITLE_BAR, Layout.Kind.BUTTON, close);

        float x = titleBar.x() + pad;
        if (state.back()) {
            Rect back = new Rect(x, (titleH - closeSize) * 0.5F, closeSize, closeSize);
            layout.add("shell.back", TITLE_BAR, Layout.Kind.BUTTON, back);
            x = back.right() + Metrics.GAP_SM;
        }
        Rect icon = new Rect(x, (titleH - Metrics.ICON) * 0.5F, Metrics.ICON, Metrics.ICON);
        layout.add("shell.icon", TITLE_BAR, Layout.Kind.ICON, icon);
        x = icon.right() + Metrics.GAP_SM + 1;

        // The account chip keeps its own room on the right; the title takes the rest.
        String accountText = state.account() == null ? tr.get(WorkshopStrings.KEY + "account.sign_in") : "@" + state.account();
        float chipTextMax = Math.min(96F, Math.max(30F, (panelWidth - pad * 2) * 0.32F));
        float chipText = Math.min(chipTextMax, TextFit.width(measurer, accountText, TextStyle.CAPTION));
        float chipWidth = chipText + Metrics.AVATAR_SM + Metrics.GAP_SM * 2 + 4;
        float chipHeight = Math.min(titleH - 6, Metrics.AVATAR_SM + 4);
        Rect chip = new Rect(close.x() - Metrics.GAP - chipWidth, (titleH - chipHeight) * 0.5F, chipWidth, chipHeight);
        layout.add("shell.account", TITLE_BAR, Layout.Kind.BUTTON, chip);
        Rect head = new Rect(chip.x() + Metrics.GAP_SM * 0.5F, chip.y() + (chipHeight - Metrics.AVATAR_SM) * 0.5F,
                Metrics.AVATAR_SM, Metrics.AVATAR_SM);
        layout.add("shell.account.head", "shell.account", Layout.Kind.IMAGE, head);
        layout.label("shell.account.name", "shell.account", head.right() + Metrics.GAP_SM,
                capY(TextStyle.CAPTION, chip.y(), chipHeight), chipText, accountText, TextStyle.CAPTION);

        String title = state.title() == null ? tr.get(WorkshopStrings.KEY + "title.listing") : state.title();
        float titleRoom = Math.max(20F, chip.x() - Metrics.GAP - x);
        layout.label("shell.title", TITLE_BAR, x, capY(TextStyle.TITLE, 0, titleH), titleRoom, title, TextStyle.TITLE);

        float tabsH = Metrics.tabBar(screenWidth, screenHeight);
        Rect tabs = new Rect(panel.x(), titleH, panelWidth, tabsH);
        layout.add(TABS, PANEL, Layout.Kind.PANEL, tabs);

        // Upload sits at the right end of the tab strip.
        String uploadLabel = tr.get(WorkshopStrings.KEY + "upload.open");
        float uploadWidth = Math.min(panelWidth * 0.34F,
                TextFit.width(measurer, uploadLabel, TextStyle.CAPTION) + Metrics.ICON_SM + Metrics.GAP + 10);
        float uploadH = Math.min(tabsH - 4, buttonH);
        Rect upload = new Rect(tabs.right() - pad - uploadWidth, tabs.y() + (tabsH - uploadH) * 0.5F, uploadWidth, uploadH);
        layout.add("shell.upload", TABS, Layout.Kind.BUTTON, upload);
        layout.labelCentered("shell.upload.text", "shell.upload", upload.centerX() + Metrics.ICON_SM * 0.5F,
                capY(TextStyle.CAPTION, upload.y(), uploadH), uploadWidth - Metrics.ICON_SM - 8, uploadLabel, TextStyle.CAPTION);

        List<Tab> visible = new ArrayList<>(List.of(Tab.BROWSE, Tab.INSTALLED));
        if (state.serverTab()) {
            visible.add(Tab.SERVER);
        }
        float tabX = tabs.x() + pad;
        float room = upload.x() - Metrics.GAP - tabX;
        // Each tab is as wide as its label, as the settings' tabs and the library's are, in body text while every tab
        // fits that way and in captions - the size the compact window's own buttons use - when they do not. Equal
        // shares of the strip cut "Установленные" to "Установлен…" and "Серверные карты" to "Серверные…" at 320 units.
        // Whether they fit is measured with every label in its bold face, so switching tabs never switches the size.
        boolean small = tabsWidth(measurer, tr, visible, TextStyle.STRONG) > room;
        boolean cut = small && tabsWidth(measurer, tr, visible, TextStyle.LABEL) > room;
        float perTab = room / visible.size();
        for (Tab tab : visible) {
            String label = tr.get(WorkshopStrings.KEY + "tab." + tab.id());
            boolean active = tab == state.tab();
            TextStyle style = small ? (active ? TextStyle.LABEL : TextStyle.CAPTION) : (active ? TextStyle.STRONG : TextStyle.BODY);
            float width = TextFit.width(measurer, label, style) + TAB_PAD * 2;
            if (cut) {
                // No shipped language gets here from 320 units up (the workshop-text check proves it); a longer one
                // would get equal shares with its labels cut rather than tabs running under the Upload button.
                width = Math.min(perTab - Metrics.GAP, width);
            }
            width = Math.max(18F, width);
            Rect box = new Rect(tabX, tabs.y() + 1, width, tabsH - 2);
            layout.add("shell.tab." + tab.id(), TABS, Layout.Kind.BUTTON, box);
            layout.labelCentered("shell.tab." + tab.id() + ".text", "shell.tab." + tab.id(), box.centerX(),
                    capY(style, box.y(), box.h() - 2), width - TAB_PAD * 2, label, style);
            tabX = box.right() + Metrics.GAP;
        }

        float contentY = tabs.bottom();
        if (state.notice() != null && !state.notice().isBlank()) {
            contentY = notice(layout, measurer, state.notice(), panel, contentY, pad, compact);
        }
        Rect content = new Rect(panel.x() + (compact ? 2 : pad), contentY + (compact ? 2 : 4),
                panelWidth - (compact ? 4 : pad * 2), Math.max(20F, screenHeight - contentY - (compact ? 4 : 8)));
        layout.add(CONTENT, PANEL, Layout.Kind.SLOT, content);
        return layout;
    }

    /**
     * The notice strip right under the tabs, where it is read before the first card and stays put while the list
     * scrolls. Returns the y the content area starts at.
     */
    private static float notice(Layout layout, TextMeasurer measurer, String text, Rect panel, float top, float pad,
                                boolean compact) {
        float textX = panel.x() + pad + Metrics.ICON_SM + Metrics.GAP_SM;
        float textWidth = Math.max(20F, panel.right() - pad - textX);
        int lines = Math.max(1, Math.min(NOTICE_LINES, TextFit.wrap(measurer, text, TextStyle.CAPTION, textWidth).size()));
        float lineHeight = Metrics.lineHeight(TextStyle.CAPTION);
        float block = (lines - 1) * lineHeight + TextFit.boxHeight(TextStyle.CAPTION);
        float inset = compact ? 2F : 3F;
        Rect strip = new Rect(panel.x(), top, panel.w(), block + inset * 2);
        layout.add(NOTICE, PANEL, Layout.Kind.PANEL, strip);
        float textY = strip.y() + inset;
        layout.add(NOTICE + ".icon", NOTICE, Layout.Kind.ICON, new Rect(panel.x() + pad,
                textY + (TextFit.boxHeight(TextStyle.CAPTION) - Metrics.ICON_SM) * 0.5F, Metrics.ICON_SM, Metrics.ICON_SM));
        layout.paragraph(NOTICE + ".text", NOTICE, textX, textY, textWidth, text, TextStyle.CAPTION, lineHeight,
                NOTICE_LINES);
        return strip.bottom();
    }

    /** The strip the visible tabs take with every label in {@code face}, their padding and the gaps between them. */
    private static float tabsWidth(TextMeasurer measurer, Tr tr, List<Tab> visible, TextStyle face) {
        float total = Metrics.GAP * (visible.size() - 1);
        for (Tab tab : visible) {
            total += Math.max(18F, TextFit.width(measurer, tr.get(WorkshopStrings.KEY + "tab." + tab.id()), face) + TAB_PAD * 2);
        }
        return total;
    }

    /** y of the cap line that centres a style's capitals in a box. */
    public static float capY(TextStyle style, float boxY, float boxHeight) {
        return boxY + (boxHeight - style.capHeight()) * 0.5F;
    }
}
