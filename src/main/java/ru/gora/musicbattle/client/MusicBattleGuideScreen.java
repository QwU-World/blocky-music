package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * A single, scrollable source of truth for Blocky Music controls and workflows: a list of sections on the left,
 * one article on the right.
 *
 * <p>It is drawn with the Workshop's toolkit - the same palette, the same Nunito text, the same rounded panels
 * and the same smooth scrolling - because the guide opens one click away from the Workshop and used to be the
 * one screen that gave away that the mod and the site were built by different hands. Where the parts go is
 * {@link GuideLayout}, which is pure and so can be measured by the guide-navigation check without a window;
 * this class only draws them and handles input.
 */
public final class MusicBattleGuideScreen extends UiLayer {
    /** How much of the rule under the article title is accent rather than a plain separator. */
    private static final float RULE_ACCENT = 24F;
    /** Wheel notches and arrow keys move the article by whole lines of body text. */
    private static final float LINE_STEP = Metrics.lineHeight(TextStyle.BODY) * 3F;

    private final List<GuideSection> sections = createSections();

    private final Screen parent;
    private final List<Button> sectionButtons = new ArrayList<>();
    private final Motion.SmoothScroll bodyScroll = new Motion.SmoothScroll();
    private final Motion.Tween articleFade = new Motion.Tween(1.0);
    private GuideLayout.Frame frame;
    private Layout article;
    private int selectedSection;
    private int firstSection;
    private boolean draggingScrollbar;

    public MusicBattleGuideScreen(Screen parent) {
        super(Component.translatable("music_battle.l10n.guide.text_000"));
        this.parent = parent;
    }

    /**
     * The frame for the current window. It is keyed on the window size rather than rebuilt in {@code layout()},
     * so a wheel or a click that arrives before the first layout still lands in the right place.
     */
    private GuideLayout.Frame frame() {
        GuideLayout.Frame current = frame;
        if (current == null || current.width() != this.width || current.height() != this.height) {
            current = GuideLayout.frame(this.width, this.height);
            frame = current;
        }
        return current;
    }

    @Override
    protected void layout() {
        GuideLayout.Frame f = frame();
        sectionButtons.clear();
        for (int index = 0; index < sections.size(); index++) {
            // Every row is born in the first slot; refreshSectionButtons below moves it to the slot it belongs in,
            // and does so again after every resize and every section change.
            sectionButtons.add(this.addRenderableWidget(sectionButton(index, f.row(0))));
        }
        this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.guide.text_001"),
                ignored -> onClose(), Math.round(f.back().x()), Math.round(f.back().y()),
                Math.round(f.back().w()), Math.round(f.back().h())));
        rebuildArticle();
        refreshSectionButtons();
    }

    private SectionButton sectionButton(int index, Rect row) {
        return (SectionButton) Button.builder(Component.literal(sections.get(index).title()),
                        ignored -> selectSection(index))
                .bounds(Math.round(row.x()), Math.round(row.y()), Math.round(row.w()), Math.round(row.h()))
                .build(builder -> new SectionButton(builder, index));
    }

    // ---------------------------------------------------------------- rendering

    @Override
    protected void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        GuideLayout.Frame f = frame();
        drawShell(g, f, mouseX, mouseY);
        drawArticle(g, f);
        drawFooter(g, f, mouseX, mouseY);
    }

    private void drawShell(GuiGraphics g, GuideLayout.Frame f, double mouseX, double mouseY) {
        Rect panel = f.panel();
        if (panel.w() < this.width) {
            RoundedRect.fill(g, panel.x(), panel.y(), panel.w(), panel.h(), 0, Palette.BG_1);
        }
        Rect titleBar = f.titleBar();
        RoundedRect.fill(g, titleBar.x(), titleBar.y(), titleBar.w(), titleBar.h(), 0, Palette.BG_1);
        RoundedRect.fill(g, titleBar.x(), titleBar.bottom() - 1, titleBar.w(), 1, 0, Palette.LINE);
        WorkshopDraw.icon(g, f.titleIcon(), UiIcon.INFO, Palette.ACCENT);
        Rect titleText = f.titleText();
        drawLabel(g, this.title.getString(), TextStyle.TITLE, titleText.x(), titleText.y(), titleText.w(), Palette.TEXT_1);

        Rect close = f.close();
        boolean closeHovered = WorkshopDraw.hot(close, mouseX, mouseY);
        WorkshopDraw.button(g, close, WorkshopDraw.ButtonStyle.QUIET, closeHovered, true);
        WorkshopDraw.icon(g, close.inset(close.w() * 0.28F, close.h() * 0.28F), UiIcon.X,
                closeHovered ? Palette.TEXT_1 : Palette.TEXT_3);

        WorkshopDraw.panel(g, f.sidebar(), Palette.RADIUS_MD, Palette.BG_2);
        drawSidebarIndicator(g, f);
        WorkshopDraw.panel(g, f.article(), Palette.RADIUS_MD, Palette.BG_2);
    }

    /** The section list is paged, not scrolled, so its indicator counts pages rather than pixels. */
    private void drawSidebarIndicator(GuiGraphics g, GuideLayout.Frame f) {
        int rows = f.visibleRows();
        int total = sectionButtons.size();
        if (total <= rows) {
            return;
        }
        Rect list = f.list();
        float x = list.right() - Metrics.SCROLLBAR;
        float thumb = Math.max(10F, list.h() * rows / total);
        float y = list.y() + (list.h() - thumb) * firstSection / (total - rows);
        RoundedRect.pill(g, x, list.y(), Metrics.SCROLLBAR, list.h(), Palette.withAlpha(Palette.BG_1, 0.85F));
        RoundedRect.pill(g, x, y, Metrics.SCROLLBAR, thumb, Palette.LINE);
    }

    private void drawArticle(GuiGraphics g, GuideLayout.Frame f) {
        GuideSection section = sections.get(selectedSection);
        Rect header = f.header();
        String counter = (selectedSection + 1) + " / " + sections.size();
        float counterWidth = TextFit.width(TextStyles.measurer(), counter, TextStyle.CAPTION);
        drawLabel(g, section.title(), TextStyle.TITLE, header.x(),
                TextStyles.centeredCapY(TextStyle.TITLE, header.y(), header.h()),
                header.w() - counterWidth - Metrics.GAP, tint(Palette.TEXT_1));
        TextStyles.queueRight(g, TextStyles.text(counter, TextStyle.CAPTION).getVisualOrderText(), header.right(),
                TextStyles.centeredCapY(TextStyle.CAPTION, header.y(), header.h()), tint(Palette.TEXT_MUTE));

        Rect rule = f.rule();
        RoundedRect.fill(g, rule.x(), rule.y(), rule.w(), rule.h(), 0, Palette.LINE);
        RoundedRect.fill(g, rule.x(), rule.y(), Math.min(rule.w(), RULE_ACCENT), rule.h(), 0, Palette.ACCENT);

        if (article == null) {
            return;
        }
        bodyScroll.update();
        float offset = bodyScroll.snapped(TextStyles.guiScale());
        Rect body = f.body();
        UiClip.push(g, body.x(), body.y(), body.right(), body.bottom());
        List<GuideLayout.Block> blocks = section.blocks();
        for (int index = 0; index < blocks.size(); index++) {
            drawBlock(g, f, blocks.get(index), index, offset);
        }
        UiClip.pop(g);
        WorkshopDraw.scrollbar(g, body, offset, article.contentHeight(), draggingScrollbar);
    }

    private void drawBlock(GuiGraphics g, GuideLayout.Frame f, GuideLayout.Block block, int index, float offset) {
        String id = GuideLayout.blockId(index);
        switch (block.kind()) {
            case HEADING -> {
                Rect mark = shifted(f, article.rect(id + ".mark"), offset);
                RoundedRect.pill(g, mark.x(), mark.y(), mark.w(), mark.h(), tint(Palette.ACCENT));
                drawParagraph(g, f, id + ".text", offset, tint(Palette.TEXT_1));
            }
            case BULLET -> {
                Rect dot = shifted(f, article.rect(id + ".dot"), offset);
                RoundedRect.circle(g, dot.centerX(), dot.centerY(), dot.w() * 0.5F,
                        tint(Palette.fade(Palette.ACCENT, 0.85F)));
                drawParagraph(g, f, id + ".text", offset, tint(Palette.TEXT_2));
            }
            case PATH -> {
                Rect chip = shifted(f, article.rect(id + ".chip"), offset);
                if (visible(f, chip)) {
                    RoundedRect.fill(g, chip.x(), chip.y(), chip.w(), chip.h(), Palette.RADIUS_SM, tint(Palette.BG_3));
                }
                drawParagraph(g, f, id + ".text", offset, tint(Palette.LINK));
            }
            case TIP, WARN -> {
                boolean warning = block.kind() == GuideLayout.Kind.WARN;
                int accent = warning ? Palette.WARN : Palette.ACCENT;
                Rect note = shifted(f, article.rect(id + ".note"), offset);
                if (visible(f, note)) {
                    RoundedRect.fill(g, note.x(), note.y(), note.w(), note.h(), Palette.RADIUS_SM,
                            tint(Palette.mix(Palette.BG_3, accent, 0.14F)));
                    RoundedRect.pill(g, note.x() + 1F, note.y() + 2F, GuideLayout.MARK, Math.max(2F, note.h() - 4F),
                            tint(accent));
                }
                WorkshopDraw.icon(g, shifted(f, article.rect(id + ".icon"), offset),
                        warning ? UiIcon.ALERT : UiIcon.INFO, tint(accent));
                drawParagraph(g, f, id + ".text", offset, tint(Palette.TEXT_1));
            }
            default -> drawParagraph(g, f, id + ".text", offset, tint(Palette.TEXT_2));
        }
    }

    /** Article colours, faded by the cross-fade that runs while a newly picked section comes in. */
    private int tint(int colour) {
        return Palette.fade(colour, articleFade.value());
    }

    private void drawFooter(GuiGraphics g, GuideLayout.Frame f, double mouseX, double mouseY) {
        Rect footer = f.footer();
        RoundedRect.fill(g, footer.x(), footer.y(), footer.w(), footer.h(), 0, Palette.BG_1);
        RoundedRect.fill(g, footer.x(), footer.y(), footer.w(), 1, 0, Palette.LINE);
        drawNavButton(g, f.prev(), UiIcon.CHEVRON_LEFT, true, I18n.get("guide.music_battle.nav.prev"), mouseX, mouseY);
        drawNavButton(g, f.next(), UiIcon.CHEVRON_RIGHT, false, I18n.get("guide.music_battle.nav.next"), mouseX, mouseY);
    }

    /** Previous and next are drawn rather than widgets, because a chevron beside the label is what says which is which. */
    private void drawNavButton(GuiGraphics g, Rect rect, UiIcon icon, boolean iconFirst, String label,
                               double mouseX, double mouseY) {
        boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
        WorkshopDraw.button(g, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, true);
        int colour = hovered ? Palette.TEXT_1 : Palette.TEXT_2;
        float iconSize = Metrics.ICON_SM;
        float inset = Metrics.PAD_SM;
        float iconX = iconFirst ? rect.x() + inset : rect.right() - inset - iconSize;
        WorkshopDraw.icon(g, new Rect(iconX, rect.centerY() - iconSize * 0.5F, iconSize, iconSize), icon, colour);

        TextStyle style = rect.h() >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
        float textX = rect.x() + inset + (iconFirst ? iconSize + Metrics.GAP_SM : 0);
        float room = rect.w() - inset * 2F - iconSize - Metrics.GAP_SM;
        if (room <= 0) {
            return;
        }
        String fitted = TextFit.ellipsize(TextStyles.measurer(), label, style, room);
        TextStyles.queueCentered(g, TextStyles.text(fitted, style).getVisualOrderText(), textX + room * 0.5F,
                TextStyles.centeredCapY(style, rect.y(), rect.h()), colour);
    }

    /** One line, cut to the room it has, in the toolkit's font. */
    private void drawLabel(GuiGraphics g, String text, TextStyle style, float x, float y, float maxWidth, int colour) {
        if (!(maxWidth > 0)) {
            return;
        }
        String fitted = TextFit.ellipsize(TextStyles.measurer(), text, style, maxWidth);
        TextStyles.queue(g, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
    }

    /** Every line of a wrapped block, skipping the ones the viewport has scrolled past. */
    private void drawParagraph(GuiGraphics g, GuideLayout.Frame f, String id, float offset, int colour) {
        for (Layout.Box box : article.group(id + ".")) {
            if (box.text() == null || box.text().isEmpty()) {
                continue;
            }
            Rect rect = shifted(f, box.rect(), offset);
            if (!visible(f, rect)) {
                continue;
            }
            TextStyles.queue(g, TextStyles.text(box.text(), box.style() == null ? TextStyle.BODY : box.style())
                    .getVisualOrderText(), rect.x(), rect.y(), colour);
        }
    }

    /** Article coordinates are relative to the body viewport; this puts them on the screen at the current scroll. */
    private Rect shifted(GuideLayout.Frame f, Rect rect, float offset) {
        return rect.empty() ? rect : new Rect(f.body().x() + rect.x(), f.body().y() + rect.y() - offset, rect.w(), rect.h());
    }

    private boolean visible(GuideLayout.Frame f, Rect rect) {
        return !rect.empty() && rect.bottom() >= f.body().y() && rect.y() <= f.body().bottom();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        GuideLayout.Frame f = frame();
        if (f.sidebar().contains(mouseX, mouseY)) {
            firstSection = Mth.clamp(firstSection - (int) Math.round(scrollY * 3.0D), 0,
                    Math.max(0, sectionButtons.size() - f.visibleRows()));
            layoutSectionButtons();
            return true;
        }
        if (f.article().contains(mouseX, mouseY)) {
            bodyScroll.onWheel(scrollY, LINE_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        GuideLayout.Frame f = frame();
        if (button == 0) {
            if (f.close().contains(mouseX, mouseY)) {
                click();
                onClose();
                return true;
            }
            if (f.prev().contains(mouseX, mouseY)) {
                click();
                selectSection(selectedSection - 1);
                return true;
            }
            if (f.next().contains(mouseX, mouseY)) {
                click();
                selectSection(selectedSection + 1);
                return true;
            }
            if (overScrollbar(f, mouseX, mouseY)) {
                draggingScrollbar = true;
                dragScrollTo(f, mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollbar) {
            dragScrollTo(frame(), mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            bodyScroll.endDrag();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            selectSection(selectedSection - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            selectSection(selectedSection + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            scrollBy(-LINE_STEP);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            scrollBy(LINE_STEP);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scrollBy(-frame().body().h() * 0.9F);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollBy(frame().body().h() * 0.9F);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            bodyScroll.scrollTo(0, true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            bodyScroll.scrollTo(bodyScroll.extent(), true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        HandCursor.reset();
        this.minecraft.setScreen(parent);
    }

    private void click() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private boolean overScrollbar(GuideLayout.Frame f, double mouseX, double mouseY) {
        Rect body = f.body();
        // The thumb is three units wide; the grab area is the whole right margin of the viewport, as in lazer.
        return bodyScroll.extent() > 0.5D && mouseX >= body.right() - Metrics.SCROLLBAR - Metrics.GAP
                && mouseX <= body.right() && mouseY >= body.y() && mouseY <= body.bottom();
    }

    private void dragScrollTo(GuideLayout.Frame f, double mouseY) {
        Rect body = f.body();
        float content = article == null ? body.h() : article.contentHeight();
        // The same thumb height WorkshopDraw.scrollbar draws, so the thumb stays under the pointer.
        float thumb = Math.max(14F, body.h() * body.h() / Math.max(1F, content));
        double travel = Math.max(1.0D, body.h() - thumb);
        double progress = Mth.clamp((mouseY - body.y() - thumb * 0.5D) / travel, 0.0D, 1.0D);
        bodyScroll.dragTo(progress * bodyScroll.extent());
    }

    private void scrollBy(float amount) {
        bodyScroll.scrollTo(bodyScroll.target() + amount, true);
    }

    // ---------------------------------------------------------------- sections

    private void selectSection(int requested) {
        int next = Math.floorMod(requested, sections.size());
        if (next == selectedSection && article != null) {
            return;
        }
        selectedSection = next;
        draggingScrollbar = false;
        bodyScroll.scrollTo(0, false);
        // The article jumps back to the top, so it comes in rather than appearing already scrolled elsewhere.
        articleFade.set(0.0);
        articleFade.animateTo(1.0, Palette.T_FADE, Motion.Easing.OUT_QUINT);
        rebuildArticle();
        refreshSectionButtons();
    }

    private void rebuildArticle() {
        GuideLayout.Frame f = frame();
        article = GuideLayout.article(f, TextStyles.measurer(), sections.get(selectedSection).blocks());
        bodyScroll.setExtent(article.contentHeight() - f.body().h());
    }

    /** Pages the list so the selected section is on screen, then places the rows. */
    private void refreshSectionButtons() {
        int rows = frame().visibleRows();
        if (selectedSection < firstSection) {
            firstSection = selectedSection;
        }
        if (selectedSection >= firstSection + rows) {
            firstSection = selectedSection - rows + 1;
        }
        firstSection = Mth.clamp(firstSection, 0, Math.max(0, sectionButtons.size() - rows));
        layoutSectionButtons();
    }

    private void layoutSectionButtons() {
        GuideLayout.Frame f = frame();
        int rows = f.visibleRows();
        for (int index = 0; index < sectionButtons.size(); index++) {
            Button button = sectionButtons.get(index);
            button.visible = index >= firstSection && index < firstSection + rows;
            button.active = button.visible && index != selectedSection;
            Rect row = f.row(index - firstSection);
            button.setX(Math.round(row.x()));
            button.setY(Math.round(row.y()));
            button.setWidth(Math.round(row.w()));
            button.setHeight(Math.round(row.h()));
        }
    }

    /**
     * A row of the section list. It is a real button - keyboard focus and the navigation check both rely on that -
     * drawn as one of the Workshop's list rows: the current section keeps the accent bar and the brightest text
     * instead of looking disabled, which is what {@code active = false} would otherwise say about it.
     */
    private final class SectionButton extends Button {
        private final int index;
        private final Motion.Tween selection = new Motion.Tween(0.0);

        private SectionButton(Button.Builder builder, int index) {
            super(builder);
            this.index = index;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean selected = index == selectedSection;
            boolean hovered = isHoveredOrFocused();
            if (hovered && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            // The bar grows into the row rather than appearing on it, which is what makes a list of sections feel
            // like one thing being moved through instead of a stack of buttons lighting up.
            selection.animateTo(selected ? 1.0 : 0.0, Palette.T_HOVER, Motion.Easing.OUT_QUINT);
            float amount = selection.value();
            if (hovered && !selected) {
                RoundedRect.fill(graphics, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, Palette.BG_3);
            }
            if (amount > 0.01F) {
                RoundedRect.fill(graphics, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM,
                        Palette.fade(Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.5F), amount));
                float barHeight = Math.max(2F, (rect.h() - 4F) * amount);
                RoundedRect.pill(graphics, rect.x() + 1F, rect.centerY() - barHeight * 0.5F, GuideLayout.MARK,
                        barHeight, Palette.fade(Palette.ACCENT, amount));
            }
            TextStyle style = selected ? TextStyle.STRONG : TextStyle.BODY;
            float textX = rect.x() + GuideLayout.MARK + Metrics.GAP;
            drawLabel(graphics, getMessage().getString(), style, textX,
                    TextStyles.centeredCapY(style, rect.y(), rect.h()), rect.right() - Metrics.PAD_SM - textX,
                    Palette.mix(Palette.TEXT_3, Palette.TEXT_1, hovered ? 1F : amount));
        }
    }

    // ---------------------------------------------------------------- text

    private static List<GuideSection> createSections() {
        return List.of(
                section(I18n.get("music_battle.l10n.guide.text_004"),
                        b(I18n.get("music_battle.l10n.guide.text_008")),
                        b(I18n.get("music_battle.l10n.guide.text_012")),
                        b(I18n.get("music_battle.l10n.guide.text_009")),
                        b(I18n.get("music_battle.l10n.guide.text_010"))),

                section(I18n.get("music_battle.l10n.guide.text_014"),
                        h(I18n.get("music_battle.l10n.guide.text_015")),
                        b(I18n.get("music_battle.l10n.guide.text_016")),
                        b(I18n.get("music_battle.l10n.guide.text_017")),
                        b(I18n.get("music_battle.l10n.guide.text_018")),
                        b(I18n.get("music_battle.l10n.guide.text_019")),
                        b(I18n.get("music_battle.l10n.guide.text_020")),
                        h(I18n.get("music_battle.l10n.guide.text_021")),
                        b(I18n.get("music_battle.l10n.guide.text_022")),
                        b(I18n.get("music_battle.l10n.guide.text_023")),
                        b(I18n.get("music_battle.l10n.guide.text_024")),
                        h(I18n.get("music_battle.l10n.guide.text_025")),
                        b(I18n.get("music_battle.l10n.guide.text_027")),
                        b(I18n.get("music_battle.l10n.guide.text_028"))),

                section(I18n.get("guide.music_battle.panel.title"),
                        b(I18n.get("guide.music_battle.panel.keys")),
                        b(I18n.get("guide.music_battle.panel.speed")),
                        b(I18n.get("guide.music_battle.panel.skin")),
                        b(I18n.get("guide.music_battle.panel.colors")),
                        b(I18n.get("guide.music_battle.panel.layout")),
                        b(I18n.get("music_battle.l10n.guide.text_013")),
                        h(I18n.get("guide.music_battle.calibration.title")),
                        b(I18n.get("guide.music_battle.calibration.audio")),
                        b(I18n.get("guide.music_battle.calibration.input")),
                        p(I18n.get("guide.music_battle.calibration.scope"))),

                section(I18n.get("music_battle.l10n.guide.text_102"),
                        h(I18n.get("music_battle.l10n.guide.text_103")),
                        b(I18n.get("music_battle.l10n.guide.text_104")),
                        b(I18n.get("music_battle.l10n.guide.text_105")),
                        h(I18n.get("guide.music_battle.npc.title")),
                        b(I18n.get("guide.music_battle.npc.spawn")),
                        b(I18n.get("guide.music_battle.npc.settings")),
                        h(I18n.get("guide.music_battle.smiley.title")),
                        b(I18n.get("guide.music_battle.smiley.spawn")),
                        b(I18n.get("guide.music_battle.smiley.dyes"))),

                section(I18n.get("music_battle.l10n.guide.text_111"),
                        b(I18n.get("music_battle.l10n.guide.text_115")),
                        b(I18n.get("music_battle.l10n.guide.text_112")),
                        b(I18n.get("music_battle.l10n.guide.text_113")),
                        b(I18n.get("music_battle.l10n.guide.text_114")),
                        b(I18n.get("music_battle.l10n.guide.text_121")),
                        b(I18n.get("guide.music_battle.dj.presets.reset"))),

                section(I18n.get("music_battle.l10n.guide.text_106"),
                        b(I18n.get("guide.music_battle.camera.recipe")),
                        b(I18n.get("music_battle.l10n.guide.text_109")),
                        b(I18n.get("music_battle.l10n.guide.text_107")),
                        b(I18n.get("guide.music_battle.camera.mouse")),
                        b(I18n.get("music_battle.l10n.guide.text_108")),
                        b(I18n.get("music_battle.l10n.guide.text_118")),
                        b(I18n.get("music_battle.l10n.guide.text_120")),
                        b(I18n.get("music_battle.l10n.guide.text_119"))),

                section(I18n.get("music_battle.l10n.guide.text_116"),
                        b(I18n.get("music_battle.l10n.guide.text_128")),
                        b(I18n.get("music_battle.l10n.guide.text_117")),
                        b(I18n.get("music_battle.l10n.guide.text_123")),
                        b(I18n.get("music_battle.l10n.guide.text_124")),
                        b(I18n.get("music_battle.l10n.guide.text_125")),
                        b(I18n.get("music_battle.l10n.guide.text_126")),
                        h(I18n.get("guide.music_battle.dj.playlist.title")),
                        b(I18n.get("guide.music_battle.dj.playlist.queue")),
                        b(I18n.get("guide.music_battle.dj.playlist.cue")),
                        b(I18n.get("guide.music_battle.dj.playlist.fx")),
                        h(I18n.get("guide.music_battle.dj.fx.title")),
                        b(I18n.get("music_battle.l10n.guide.text_127")),
                        b(I18n.get("guide.music_battle.dj.fx.edit")),
                        b(I18n.get("guide.music_battle.dj.fx.preview")),
                        h(I18n.get("guide.music_battle.dj.presets.title")),
                        b(I18n.get("guide.music_battle.dj.presets.folder")),
                        b(I18n.get("guide.music_battle.dj.presets.types"))),

                section(I18n.get("music_battle.l10n.guide.text_029"),
                        h(I18n.get("music_battle.l10n.guide.text_030")),
                        b(I18n.get("music_battle.l10n.guide.text_031")),
                        b(I18n.get("music_battle.l10n.guide.text_032")),
                        h(I18n.get("music_battle.l10n.guide.text_033")),
                        b(I18n.get("music_battle.l10n.guide.text_034")),
                        b(I18n.get("music_battle.l10n.guide.text_035")),
                        b(I18n.get("music_battle.l10n.guide.text_036")),
                        b(I18n.get("music_battle.l10n.guide.text_037")),
                        warn(I18n.get("music_battle.l10n.guide.text_038"))),

                section(I18n.get("music_battle.l10n.guide.text_039"),
                        h(I18n.get("music_battle.l10n.guide.text_050")),
                        b(I18n.get("music_battle.l10n.guide.text_051")),
                        h(I18n.get("music_battle.l10n.guide.text_046")),
                        b(I18n.get("music_battle.l10n.guide.text_047")),
                        b(I18n.get("music_battle.l10n.guide.text_048")),
                        b(I18n.get("music_battle.l10n.guide.text_049")),
                        h(I18n.get("music_battle.l10n.guide.text_040")),
                        path(I18n.get("music_battle.l10n.guide.text_041")),
                        path(I18n.get("music_battle.l10n.guide.text_042")),
                        path(I18n.get("music_battle.l10n.guide.text_044")),
                        path(I18n.get("music_battle.l10n.guide.text_045")),
                        path(I18n.get("guide.music_battle.preset_path")),
                        tip(I18n.get("music_battle.l10n.guide.text_052"))),

                section(I18n.get("music_battle.l10n.guide.text_053"),
                        h(I18n.get("music_battle.l10n.guide.text_054")),
                        b(I18n.get("music_battle.l10n.guide.text_055")),
                        b(I18n.get("music_battle.l10n.guide.text_056")),
                        h(I18n.get("music_battle.l10n.guide.text_057")),
                        b(I18n.get("music_battle.l10n.guide.text_058")),
                        b(I18n.get("music_battle.l10n.guide.text_059")),
                        b(I18n.get("music_battle.l10n.guide.text_060")),
                        h(I18n.get("music_battle.l10n.guide.text_062")),
                        b(I18n.get("music_battle.l10n.guide.text_063")),
                        b(I18n.get("music_battle.l10n.guide.text_064")),
                        b(I18n.get("music_battle.l10n.guide.text_065"))),

                section(I18n.get("music_battle.l10n.guide.text_066"),
                        h(I18n.get("music_battle.l10n.guide.text_067")),
                        b(I18n.get("music_battle.l10n.guide.text_068")),
                        b(I18n.get("music_battle.l10n.guide.text_069")),
                        h(I18n.get("music_battle.l10n.guide.text_071")),
                        b(I18n.get("music_battle.l10n.guide.text_072")),
                        b(I18n.get("music_battle.l10n.guide.text_073")),
                        b(I18n.get("music_battle.l10n.guide.text_074")),
                        b(I18n.get("music_battle.l10n.guide.text_075")),
                        b(I18n.get("music_battle.l10n.guide.text_076")),
                        h(I18n.get("music_battle.l10n.guide.text_077")),
                        b(I18n.get("music_battle.l10n.guide.text_078")),
                        b(I18n.get("music_battle.l10n.guide.text_079")),
                        b(I18n.get("music_battle.l10n.guide.text_080")),
                        b(I18n.get("music_battle.l10n.guide.text_081")),
                        b(I18n.get("guide.music_battle.colors"))),

                section(I18n.get("music_battle.l10n.guide.text_082"),
                        h(I18n.get("music_battle.l10n.guide.text_083")),
                        b(I18n.get("music_battle.l10n.guide.text_084")),
                        b(I18n.get("music_battle.l10n.guide.text_085")),
                        b(I18n.get("music_battle.l10n.guide.text_086")),
                        b(I18n.get("music_battle.l10n.guide.text_087")),
                        b(I18n.get("music_battle.l10n.guide.text_088")),
                        b(I18n.get("music_battle.l10n.guide.text_089")),
                        b(I18n.get("music_battle.l10n.guide.text_090")),
                        b(I18n.get("music_battle.l10n.guide.text_092")),
                        h(I18n.get("music_battle.l10n.guide.text_093")),
                        b(I18n.get("music_battle.l10n.guide.text_094")),
                        b(I18n.get("music_battle.l10n.guide.text_095")),
                        b(I18n.get("music_battle.l10n.guide.text_096")),
                        b(I18n.get("music_battle.l10n.guide.text_097")),
                        b(I18n.get("music_battle.l10n.guide.text_098")),
                        b(I18n.get("guide.music_battle.object_outline")),
                        b(I18n.get("guide.music_battle.hide_panels")),
                        b(I18n.get("guide.music_battle.hide_particles_hand")),
                        h(I18n.get("music_battle.l10n.guide.text_099")),
                        b(I18n.get("music_battle.l10n.guide.text_100")),
                        b(I18n.get("music_battle.l10n.guide.text_101"))),

                section(I18n.get("music_battle.l10n.guide.text_129"),
                        path(I18n.get("music_battle.l10n.guide.text_130")),
                        path(I18n.get("music_battle.l10n.guide.text_131")),
                        path(I18n.get("music_battle.l10n.guide.text_132")),
                        path(I18n.get("music_battle.l10n.guide.text_133")),
                        path(I18n.get("music_battle.l10n.guide.text_134")),
                        b(I18n.get("music_battle.l10n.guide.text_136")))
        );
    }

    private static GuideSection section(String title, GuideLayout.Block... blocks) {
        return new GuideSection(title, List.of(blocks));
    }

    private static GuideLayout.Block h(String text) {
        return new GuideLayout.Block(text, GuideLayout.Kind.HEADING);
    }

    private static GuideLayout.Block p(String text) {
        return new GuideLayout.Block(text, GuideLayout.Kind.TEXT);
    }

    private static GuideLayout.Block b(String text) {
        return new GuideLayout.Block(text, GuideLayout.Kind.BULLET);
    }

    private static GuideLayout.Block path(String text) {
        return new GuideLayout.Block(text, GuideLayout.Kind.PATH);
    }

    private static GuideLayout.Block tip(String text) {
        return new GuideLayout.Block(I18n.get("music_battle.l10n.guide.text_137", text), GuideLayout.Kind.TIP);
    }

    private static GuideLayout.Block warn(String text) {
        return new GuideLayout.Block(I18n.get("music_battle.l10n.guide.text_138", text), GuideLayout.Kind.WARN);
    }

    private record GuideSection(String title, List<GuideLayout.Block> blocks) {
    }
}
