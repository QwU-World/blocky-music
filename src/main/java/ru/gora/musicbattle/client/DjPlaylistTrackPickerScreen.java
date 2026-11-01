package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.client.ui.FancyEditBox;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.TrackSummary;

/**
 * The playlist's chooser: the maps on the booth's own list, as cards, one of which the playlist's Add will queue.
 *
 * <p>It used to list every map the server had, in square teal cards with the bitmap font. It now shows only the
 * DJ's list - the maps they put on the booth with "+" - and is drawn with the Workshop's toolkit like the panel's
 * library above it: an opaque backdrop, rounded cards that lift under the pointer, Nunito text and the Workshop's
 * scrollbar. An empty list says where maps come from rather than showing nothing.
 */
final class DjPlaylistTrackPickerScreen extends UiLayer implements DjPanelOwnedScreen {
    private static final float HEADER = 30.0F;
    private static final float CARD_HEIGHT = 74.0F;
    /** The panel's compact card, for a list too short to show two full ones (a small window, GUI scale 3). */
    private static final float COMPACT_CARD_HEIGHT = 43.0F;
    private static final float CARD_GAP = 6.0F;
    private static final float SIDE = 16.0F;

    private final Screen parent;
    private final DjPanelScreen owner;
    /** The map the playlist's Add would queue right now, marked like the panel marks its selected card. */
    private final String currentId;
    private final Consumer<TrackSummary> choose;
    private List<TrackSummary> filtered = List.of();
    private String query = "";
    private int scroll;
    private EditBox search;

    DjPlaylistTrackPickerScreen(Screen parent, DjPanelScreen owner, String currentId, Consumer<TrackSummary> choose) {
        super(Component.translatable("screen.music_battle.dj_playlist.choose_track"));
        this.parent = parent;
        this.owner = owner;
        this.currentId = currentId == null ? "" : currentId;
        this.choose = choose;
    }

    @Override
    protected void layout() {
        Rect field = searchField();
        // The edit box keeps the vanilla font, which measures its caret; the rounded field around it is drawn here.
        int boxY = Math.round(field.y() + (field.h() - 8) * 0.5F);
        search = TextStyles.editBox(Math.round(field.x()) + 7, boxY, Math.round(field.w() - Metrics.ICON - 16),
                Math.round(field.bottom()) - boxY, Component.translatable("screen.music_battle.dj_panel.search"));
        search.setMaxLength(120);
        // A plain EditBox draws a hint without a colour of its own in the typed-text colour, near white here, so the
        // hint gets the muted colour FancyEditBox gives every hint.
        search.setHint(FancyEditBox.mutedHint(Component.translatable("screen.music_battle.dj_panel.search_hint")));
        search.setValue(query);
        search.setResponder(value -> {
            query = value == null ? "" : value;
            scroll = 0;
            filter();
        });
        addRenderableWidget(search);
        addRenderableWidget(FancyButton.create(Component.translatable("gui.back"), button -> onClose(),
                width / 2 - 60, height - 28, 120, 20));
        filter();
    }

    private void filter() {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        filtered = owner.libraryTracks().stream()
                .filter(track -> (track.title() + " " + track.publishedBy() + " " + track.description())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private Rect searchField() {
        return new Rect(SIDE, HEADER + 8, width - SIDE * 2, Metrics.FIELD_H);
    }

    private float listTop() {
        return searchField().bottom() + 8;
    }

    private float listBottom() {
        return height - 36;
    }

    /** Full cards while two of them fit the list's height, the panel's compact ones below that. */
    private float cardHeight() {
        return listBottom() - listTop() >= CARD_HEIGHT * 2 + CARD_GAP ? CARD_HEIGHT : COMPACT_CARD_HEIGHT;
    }

    private float cover() {
        return cardHeight() - 10.0F;
    }

    private int columns() {
        return width >= 580 ? 2 : 1;
    }

    private float cardWidth() {
        return (width - SIDE * 2 - CARD_GAP * (columns() - 1)) / columns();
    }

    private Rect card(int index) {
        float x = SIDE + (index % columns()) * (cardWidth() + CARD_GAP);
        float y = listTop() + (index / columns()) * (cardHeight() + CARD_GAP) - scroll;
        return new Rect(x, y, cardWidth(), cardHeight());
    }

    private int maxScroll() {
        int rows = (filtered.size() + columns() - 1) / columns();
        return Math.max(0, Math.round(rows * (cardHeight() + CARD_GAP) - CARD_GAP - (listBottom() - listTop())));
    }

    @Override
    protected void renderUi(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        drawHeader(graphics);
        drawSearchField(graphics, mouseX, mouseY);
        if (filtered.isEmpty()) {
            drawEmptyState(graphics);
            return;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
        float top = listTop();
        float bottom = listBottom();
        boolean pointerInside = mouseY >= top && mouseY < bottom;
        List<Integer> visible = new ArrayList<>();
        for (int index = 0; index < filtered.size(); index++) {
            Rect card = card(index);
            if (card.bottom() >= top && card.y() <= bottom) {
                visible.add(index);
            }
        }
        UiClip.push(graphics, SIDE, top, width - SIDE, bottom);
        // Three passes, like the Workshop's grid: a cover draws immediately and flushes whatever is queued, so every
        // shape goes first, then the covers, then all text and icons.
        for (int index : visible) {
            Rect card = card(index);
            boolean over = pointerInside && card.contains(mouseX, mouseY);
            boolean current = filtered.get(index).id().equals(currentId);
            if (over) {
                HandCursor.request(HandCursor.Kind.HAND);
                RoundedRect.shadow(graphics, card.x(), card.y() + 2, card.w(), card.h(), Palette.RADIUS_MD, 7.0F,
                        Palette.SHADOW_LIFTED);
            }
            RoundedRect.fill(graphics, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_MD,
                    current ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F) : over ? Palette.BG_3 : Palette.BG_2);
            if (current) {
                RoundedRect.stroke(graphics, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_MD, 1.0F,
                        Palette.ACCENT);
            }
            RoundedRect.pill(graphics, card.x() + 1.5F, card.y() + 8, 2.5F, card.h() - 16,
                    current || over ? Palette.ACCENT : Palette.LINE);
        }
        List<String> visibleIds = new ArrayList<>();
        for (int index : visible) {
            Rect card = card(index);
            TrackSummary track = filtered.get(index);
            visibleIds.add(track.id());
            ResourceLocation cover = owner.previewTexture(track);
            if (cover != null) {
                RoundedImage.draw(graphics, cover, card.x() + 5, card.y() + 5, cover(), cover(), Palette.RADIUS_SM,
                        0.0F, 1.0F);
            } else {
                RoundedRect.fill(graphics, card.x() + 5, card.y() + 5, cover(), cover(), Palette.RADIUS_SM, Palette.BG_3);
            }
        }
        for (int index : visible) {
            Rect card = card(index);
            TrackSummary track = filtered.get(index);
            if (owner.previewTexture(track) == null) {
                Rect cover = new Rect(card.x() + 5, card.y() + 5, cover(), cover());
                WorkshopDraw.icon(graphics, cover.inset(cover() * 0.3F, cover() * 0.3F), UiIcon.MUSIC,
                        Palette.withAlpha(Palette.TEXT_MUTE, 0.7F));
            }
            float textX = card.x() + 5 + cover() + 8;
            float textWidth = Math.max(20.0F, card.right() - 8 - textX);
            boolean compact = cardHeight() < CARD_HEIGHT;
            float gap = compact ? 4 : 6;
            float y = card.y() + (compact ? 6 : 9);
            line(graphics, track.title(), TextStyle.TITLE, textX, y, textWidth, Palette.TEXT_1);
            y += TextStyle.TITLE.capHeight() + gap + 1;
            int seconds = Math.max(0, track.durationTicks()) / 20;
            line(graphics, "BPM " + track.bpm() + "  ·  " + String.format(Locale.ROOT, "%d:%02d", seconds / 60,
                    seconds % 60), TextStyle.CAPTION, textX, y, textWidth, Palette.TEXT_MUTE);
            y += TextStyle.CAPTION.capHeight() + gap;
            String author = track.publishedBy().isBlank()
                    ? Component.translatable("screen.music_battle.dj_panel.builtin").getString()
                    : Component.translatable("screen.music_battle.dj_panel.published_by", track.publishedBy()).getString();
            line(graphics, author, TextStyle.CAPTION, textX, y, textWidth, Palette.TEXT_3);
            if (!compact) {
                y += TextStyle.CAPTION.capHeight() + gap;
                line(graphics, track.description(), TextStyle.CAPTION, textX, y, textWidth, Palette.TEXT_3);
            }
        }
        UiClip.pop(graphics);
        WorkshopDraw.scrollbar(graphics, new Rect(SIDE, top, width - SIDE * 2 + 6, bottom - top), scroll,
                (bottom - top) + maxScroll(), false);
        owner.requestVisiblePreviews(visibleIds);
    }

    private void drawHeader(GuiGraphics graphics) {
        RoundedRect.fill(graphics, 0, 0, width, HEADER, 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, HEADER - 1, width, 1, 0, Palette.LINE);
        Rect icon = new Rect(SIDE, HEADER * 0.5F - Metrics.ICON * 0.5F, Metrics.ICON, Metrics.ICON);
        WorkshopDraw.icon(graphics, icon, UiIcon.MUSIC, Palette.ACCENT);
        line(graphics, title.getString(), TextStyle.TITLE, icon.right() + Metrics.GAP_SM + 1,
                TextStyles.centeredCapY(TextStyle.TITLE, 0, HEADER), width - SIDE * 2 - Metrics.ICON - 6,
                Palette.TEXT_1);
    }

    private void drawSearchField(GuiGraphics graphics, double mouseX, double mouseY) {
        Rect field = searchField();
        boolean focused = search != null && search.isFocused();
        boolean over = field.contains(mouseX, mouseY);
        if (over) {
            HandCursor.request(HandCursor.Kind.IBEAM);
        }
        RoundedRect.fill(graphics, field.x(), field.y(), field.w(), field.h(), Palette.RADIUS_SM, Palette.BG_3);
        RoundedRect.stroke(graphics, field.x(), field.y(), field.w(), field.h(), Palette.RADIUS_SM, 1.0F,
                focused ? Palette.ACCENT : over ? Palette.LINE : Palette.withAlpha(Palette.LINE, 0.5F));
        WorkshopDraw.icon(graphics, new Rect(field.right() - Metrics.ICON - 6, field.centerY() - Metrics.ICON * 0.5F,
                Metrics.ICON, Metrics.ICON), UiIcon.SEARCH, focused ? Palette.ACCENT : Palette.TEXT_MUTE);
    }

    /** Nothing to choose: why, on the Workshop's empty panel. An empty list points at "+" on the panel. */
    private void drawEmptyState(GuiGraphics graphics) {
        float top = listTop();
        Rect panel = new Rect(SIDE, top, width - SIDE * 2, Math.min(listBottom() - top, 96.0F));
        if (panel.h() < 24) {
            return;
        }
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, Palette.BG_1);
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, 1.0F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        boolean searching = !query.isBlank() && !owner.libraryTracks().isEmpty();
        float iconY = panel.centerY() - 19;
        WorkshopDraw.icon(graphics, new Rect(panel.centerX() - 7, iconY, 14, 14),
                searching ? UiIcon.SEARCH : UiIcon.MUSIC, Palette.withAlpha(Palette.TEXT_MUTE, 0.8F));
        String message = Component.translatable(searching ? "screen.music_battle.dj_panel.no_search_results"
                : "screen.music_battle.dj_playlist.picker_empty").getString();
        float y = iconY + 21;
        for (String messageLine : TextFit.wrap(TextStyles.measurer(), message, TextStyle.BODY, panel.w() - 32, 2)) {
            TextStyles.queueCentered(graphics, TextStyles.text(messageLine, TextStyle.BODY).getVisualOrderText(),
                    panel.centerX(), y, Palette.TEXT_2);
            y += Metrics.lineHeight(TextStyle.BODY);
        }
    }

    private static void line(GuiGraphics graphics, String value, TextStyle style, float x, float y, float maxWidth,
                             int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), value == null ? "" : value, style, maxWidth);
        if (!fitted.isEmpty()) {
            TextStyles.queue(graphics, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= listTop() && mouseY < listBottom()) {
            for (int index = 0; index < filtered.size(); index++) {
                if (card(index).contains(mouseX, mouseY)) {
                    choose.accept(filtered.get(index));
                    onClose();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.round(scrollY * 28.0D), 0, maxScroll());
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public DjPanelScreen owningDjPanel() {
        return owner;
    }
}
