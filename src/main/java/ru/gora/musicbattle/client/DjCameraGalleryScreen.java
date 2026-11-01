package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.network.EditDjCameraRequestPayload;
import ru.gora.musicbattle.network.OpenDjPanelPayload;

/**
 * Camera switcher for a DJ installation. Gallery thumbnails are filled by a
 * bounded round-robin queue: one low-resolution nested world pass at most per
 * render budget, never one pass per visible card.
 *
 * <p>It is drawn in the toolkit's look, as the DJ panel it opens from: a header bar with the title in Nunito, the cameras
 * as the library's rounded cards (lifted under the pointer, the selected one in the accent), the preview's messages
 * wrapped whole inside the picture, and the actions as the toolkit's buttons in a footer bar, Select the primary one
 * and Disconnect the danger. It was a vanilla screen with stone buttons and the bitmap font, and at 320 wide its
 * buttons and the preview's messages were cut. {@link GalleryLayout} sets it out by the text's own widths; the
 * dj-screens-kit check measures every word of it in English and Russian, from 320x240 up and at GUI scales 1-9.
 */
public final class DjCameraGalleryScreen extends Screen {
    private final DjPanelScreen parent;
    private final BlockPos panelPos;
    private final List<OpenDjPanelPayload.CameraEntry> cameras;
    private final Map<Long, DjCameraPreviewRenderer.Status> statuses = new HashMap<>();
    private int selectedIndex;
    private int scroll;
    private int previewCursor;
    private boolean initialLayout = true;
    private GalleryLayout layout;
    private Button selectButton;
    private Button editButton;
    private Button unlinkButton;
    /**
     * The hints of Select and Control, drawn as the toolkit's card (UiTooltip) like the DJ panel's, rather than in
     * vanilla's purple frame and bitmap font. A new holder comes with every build of the widgets.
     */
    private UiTooltip.Deferred tooltips = new UiTooltip.Deferred();

    public DjCameraGalleryScreen(DjPanelScreen parent, BlockPos panelPos,
                                 List<OpenDjPanelPayload.CameraEntry> cameras, int selectedIndex) {
        super(Component.translatable("screen.music_battle.dj_camera_gallery.title"));
        this.parent = parent;
        this.panelPos = panelPos.immutable();
        this.cameras = new ArrayList<>(cameras);
        this.selectedIndex = cameras.isEmpty() ? -1 : Mth.clamp(selectedIndex, 0, cameras.size() - 1);
    }

    @Override
    protected void init() {
        DjCameraPreviewRenderer.releaseThumbnails();
        statuses.clear();
        previewCursor = 0;
        tooltips = new UiTooltip.Deferred();
        layout = GalleryLayout.compute(width, height, TextStyles.measurer(), DjPanelScreen.Words.game());
        addRenderableWidget(KitButton.text(Component.translatable("screen.music_battle.dj_camera_gallery.back"),
                button -> onClose(), layout.back(), WorkshopDraw.ButtonStyle.SECONDARY));
        selectButton = addRenderableWidget(KitButton.text(
                Component.translatable("screen.music_battle.dj_camera_gallery.select"), button -> selectCurrent(),
                layout.select(), WorkshopDraw.ButtonStyle.PRIMARY));
        selectButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_camera_gallery.select_tooltip")));
        editButton = addRenderableWidget(KitButton.text(
                Component.translatable("screen.music_battle.dj_camera_gallery.control"), button -> editCurrent(),
                layout.control(), WorkshopDraw.ButtonStyle.SECONDARY));
        editButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_camera_gallery.control_tooltip")));
        unlinkButton = addRenderableWidget(KitButton.text(
                Component.translatable("screen.music_battle.dj_camera_gallery.unlink"), button -> unlinkCurrent(),
                layout.unlink(), WorkshopDraw.ButtonStyle.DANGER));
        if (initialLayout && selectedIndex >= 0) {
            GalleryLayout.Card selected = layout.card(selectedIndex, 0);
            if (selected.y() + selected.height() > layout.contentBottom()) {
                scroll += selected.y() + selected.height() - layout.contentBottom();
            }
            initialLayout = false;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
        refreshButtons();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GalleryLayout layout = layout();
        fillNextThumbnail(partialTick);
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.96F));
        // The header and the footer are bars of the panel's own surface, as the DJ panel's pages have their header.
        RoundedRect.fill(graphics, 0, 0, width, layout.header(), 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, layout.header() - 1, width, 1, 0, Palette.LINE);
        RoundedRect.fill(graphics, 0, layout.footerTop(), width, height - layout.footerTop(), 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, layout.footerTop(), width, 1, 0, Palette.LINE);
        TextStyles.queueCentered(graphics, TextStyles.styled(title, layout.titleFace()).getVisualOrderText(), width / 2.0F,
                layout.titleY(), Palette.TEXT_1);
        if (cameras.isEmpty()) {
            drawEmpty(graphics, layout);
        } else {
            drawCards(graphics, layout, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        // After the buttons, so the card lies over them, and in the foreground layer the DJ panel's cards use.
        if (!tooltips.placement(TextStyles.measurer(), mouseX, mouseY, width, height, getFocused()).empty()) {
            ClientUiForeground.render(graphics.pose(), graphics::flush,
                    () -> tooltips.draw(graphics, mouseX, mouseY, width, height, getFocused()));
        }
    }

    /** A tooltip made by {@link #tooltips} is drawn by this screen in the toolkit's look; any other stays vanilla's. */
    @Override
    public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
        if (!tooltips.offer(tooltip, positioner)) {
            super.setTooltipForNextRenderPass(tooltip, positioner, focused);
        }
    }

    /** No camera yet: why, with an icon, on the Workshop's empty panel, as the DJ panel's empty library says it. */
    private void drawEmpty(GuiGraphics graphics, GalleryLayout layout) {
        Rect panel = layout.emptyPanel();
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, Palette.BG_1);
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, 1.0F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        String message = Component.translatable("screen.music_battle.dj_camera_gallery.empty").getString();
        DjPanelScreen.PanelLayout.Block block = GalleryLayout.emptyBlock(TextStyles.measurer(), message, panel);
        if (block == null) {
            block = new DjPanelScreen.PanelLayout.Block(TextStyle.CAPTION, TextFit.wrap(TextStyles.measurer(), message,
                    TextStyle.CAPTION, panel.w() - 24.0F, 3));
        }
        float iconY = panel.y() + Math.max(4.0F, (panel.h() - 20 - block.height()) / 2.0F);
        WorkshopDraw.icon(graphics, new Rect(panel.centerX() - 7, iconY, 14, 14), UiIcon.INFO,
                Palette.withAlpha(Palette.TEXT_MUTE, 0.8F));
        float y = iconY + 20;
        for (String line : block.lines()) {
            TextStyles.queueCentered(graphics, TextStyles.text(line, block.face()).getVisualOrderText(), panel.centerX(), y,
                    Palette.TEXT_2);
            y += Metrics.lineHeight(block.face());
        }
    }

    /**
     * The cameras as cards, in three passes like the DJ panel's library, because a thumbnail draws immediately and flushes
     * what is queued: every shape, then every picture, then all the words.
     */
    private void drawCards(GuiGraphics graphics, GalleryLayout layout, int mouseX, int mouseY) {
        int top = layout.contentTop();
        int bottom = layout.contentBottom();
        List<Integer> shown = new ArrayList<>();
        for (int index = 0; index < cameras.size(); index++) {
            GalleryLayout.Card card = layout.card(index, scroll);
            if (card.y() + card.height() >= top && card.y() <= bottom) {
                shown.add(index);
            }
        }
        UiClip.push(graphics, 0, top, width, bottom);
        for (int index : shown) {
            GalleryLayout.Card card = layout.card(index, scroll);
            boolean selected = index == selectedIndex;
            boolean hovered = hovered(card, mouseX, mouseY);
            if (hovered) {
                RoundedRect.shadow(graphics, card.x(), card.y() + 2, card.width(), card.height(), Palette.RADIUS_MD, 7.0F,
                        Palette.SHADOW_LIFTED);
            }
            int fill = selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F) : hovered ? Palette.BG_3 : Palette.BG_2;
            RoundedRect.fill(graphics, card.x(), card.y(), card.width(), card.height(), Palette.RADIUS_MD, fill);
            if (selected) {
                RoundedRect.stroke(graphics, card.x(), card.y(), card.width(), card.height(), Palette.RADIUS_MD, 1.0F,
                        Palette.ACCENT);
            } else if (hovered) {
                RoundedRect.stroke(graphics, card.x(), card.y(), card.width(), card.height(), Palette.RADIUS_MD, 1.0F,
                        Palette.mix(Palette.LINE, Palette.ACCENT, 0.6F));
            }
            Rect preview = card.preview();
            RoundedRect.fill(graphics, preview.x(), preview.y(), preview.w(), preview.h(), Palette.RADIUS_SM, Palette.BG_0);
        }
        graphics.flush();
        for (int index : shown) {
            GalleryLayout.Card card = layout.card(index, scroll);
            Rect preview = card.preview();
            long key = cameras.get(index).pos().asLong();
            if (DjCameraPreviewRenderer.hasThumbnail(key)) {
                DjCameraPreviewRenderer.drawThumbnail(graphics, key, Math.round(preview.x()), Math.round(preview.y()),
                        Math.round(preview.w()), Math.round(preview.h()));
            }
        }
        for (int index : shown) {
            GalleryLayout.Card card = layout.card(index, scroll);
            OpenDjPanelPayload.CameraEntry camera = cameras.get(index);
            Rect preview = card.preview();
            long key = camera.pos().asLong();
            if (!DjCameraPreviewRenderer.hasThumbnail(key)) {
                DjCameraPreviewRenderer.Status status = statuses.getOrDefault(key,
                        camera.available() ? DjCameraPreviewRenderer.Status.READY
                                : DjCameraPreviewRenderer.Status.CAMERA_UNAVAILABLE);
                drawStatus(graphics, statusText(status).getString(), preview);
            }
            String name = Component.translatable("screen.music_battle.dj_camera_gallery.camera", index + 1).getString();
            float nameWidth = TextFit.width(TextStyles.measurer(), name, GalleryLayout.NAME_FACE);
            float lineY = card.nameY();
            TextStyles.queue(graphics, TextStyles.text(name, GalleryLayout.NAME_FACE).getVisualOrderText(),
                    card.x() + GalleryLayout.CARD_PAD, lineY, Palette.TEXT_1);
            // The coordinates are data and give way: the card's name is never cut, they take what is left.
            float room = card.width() - GalleryLayout.CARD_PAD * 2 - nameWidth - GalleryLayout.NAME_GAP;
            String coords = room > 8 ? TextFit.ellipsize(TextStyles.measurer(), camera.pos().toShortString(),
                    TextStyle.CAPTION, room) : "";
            if (!coords.isEmpty()) {
                TextStyles.queueRight(graphics, TextStyles.text(coords, TextStyle.CAPTION).getVisualOrderText(),
                        card.x() + card.width() - GalleryLayout.CARD_PAD,
                        lineY + GalleryLayout.NAME_FACE.capHeight() - TextStyle.CAPTION.capHeight(), Palette.TEXT_3);
            }
        }
        UiClip.pop(graphics);
        WorkshopDraw.scrollbar(graphics, new Rect(layout.gridLeft(), top, layout.gridWidth() + 7, bottom - top), scroll,
                (bottom - top) + maxScroll(), false);
    }

    /** A preview's message in place of its picture, wrapped whole and centred, as the DJ panel's monitor says it. */
    private static void drawStatus(GuiGraphics graphics, String message, Rect preview) {
        DjPanelScreen.PanelLayout.Block block = GalleryLayout.statusBlock(TextStyles.measurer(), message, preview);
        if (block == null) {
            // Only a language the layout check never measured lands here; its words are cut rather than spilled.
            block = new DjPanelScreen.PanelLayout.Block(TextStyle.CAPTION, TextFit.wrap(TextStyles.measurer(), message,
                    TextStyle.CAPTION, preview.w() - 8, Math.max(1, (int) ((preview.h() - 6)
                            / Metrics.lineHeight(TextStyle.CAPTION)))));
        }
        float lineHeight = Metrics.lineHeight(block.face());
        float y = preview.y() + Math.max(3.0F, (preview.h() - block.height()) / 2.0F + 1);
        for (String line : block.lines()) {
            TextStyles.queueCentered(graphics, TextStyles.text(line, block.face()).getVisualOrderText(), preview.centerX(), y,
                    Palette.TEXT_MUTE);
            y += lineHeight;
        }
    }

    private void fillNextThumbnail(float partialTick) {
        if (cameras.isEmpty()) {
            return;
        }
        GalleryLayout layout = layout();
        int attempts = cameras.size();
        while (attempts-- > 0) {
            int index = Math.floorMod(previewCursor++, cameras.size());
            GalleryLayout.Card card = layout.card(index, scroll);
            if (card.y() + card.height() < layout.contentTop() || card.y() > layout.contentBottom()) {
                continue;
            }
            OpenDjPanelPayload.CameraEntry camera = cameras.get(index);
            long key = camera.pos().asLong();
            if (DjCameraPreviewRenderer.hasThumbnail(key)) {
                continue;
            }
            Rect preview = card.preview();
            DjCameraPreviewRenderer.Status status = DjCameraPreviewRenderer.updateThumbnail(
                    key, snapshot(camera), Math.max(96, Math.round(preview.w())),
                    Math.max(54, Math.round(preview.h())), partialTick);
            statuses.put(key, status);
            break;
        }
    }

    private Component statusText(DjCameraPreviewRenderer.Status status) {
        return Component.translatable(statusKey(status));
    }

    static String statusKey(DjCameraPreviewRenderer.Status status) {
        return switch (status) {
            case CAMERA_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_offline";
            case WORLD_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_world_unavailable";
            case CHUNK_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_chunk_unavailable";
            case SHADERS_ACTIVE -> "screen.music_battle.dj_panel.camera_shaders_active";
            case RENDER_FAILED -> "screen.music_battle.dj_panel.camera_render_failed";
            case READY -> "screen.music_battle.dj_camera_gallery.loading";
        };
    }

    /** Under the pointer, and inside the cards' view: a card scrolled under the header or the footer is not there. */
    private boolean hovered(GalleryLayout.Card card, double mouseX, double mouseY) {
        GalleryLayout layout = layout();
        return mouseX >= card.x() && mouseX < card.x() + card.width() && mouseY >= card.y()
                && mouseY < card.y() + card.height() && mouseY >= layout.contentTop() && mouseY < layout.contentBottom();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int index = 0; index < cameras.size(); index++) {
                if (hovered(layout().card(index, scroll), mouseX, mouseY)) {
                    selectedIndex = index;
                    refreshButtons();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int old = scroll;
        scroll = Mth.clamp(scroll - (int) Math.round(scrollY * 38.0D), 0, maxScroll());
        return old != scroll || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void removed() {
        super.removed();
        // The gallery owns the preview renderer while it is open. Reset both
        // thumbnails and failure/throttle state so the parent live monitor can
        // start cleanly after returning from the gallery or camera editor.
        DjCameraPreviewRenderer.release();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    DjPanelScreen returnPanel() {
        return parent;
    }

    boolean isForPanel(BlockPos pos) {
        return panelPos.equals(pos);
    }

    void applyServerState(OpenDjPanelPayload payload) {
        if (!isForPanel(payload.panelPos())) {
            return;
        }
        parent.applyServerState(payload);
        cameras.clear();
        cameras.addAll(payload.cameras());
        selectedIndex = payload.activeCameraIndex() >= 0
                && payload.activeCameraIndex() < cameras.size()
                ? payload.activeCameraIndex() : cameras.isEmpty() ? -1 : 0;
        statuses.clear();
        previewCursor = 0;
        DjCameraPreviewRenderer.releaseThumbnails();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        refreshButtons();
    }

    void refreshAfterCameraEditor() {
        parent.refreshAfterCameraEditor();
    }

    private void selectCurrent() {
        if (selectedIndex >= 0) {
            parent.selectCameraFromGallery(selectedIndex);
        }
    }

    private void editCurrent() {
        if (selectedIndex < 0 || minecraft == null) {
            return;
        }
        OpenDjPanelPayload.CameraEntry camera = cameras.get(selectedIndex);
        if (camera.available()) {
            PacketDistributor.sendToServer(new EditDjCameraRequestPayload(panelPos, camera.pos()));
        }
    }

    private void unlinkCurrent() {
        if (selectedIndex < 0) {
            return;
        }
        parent.unlinkCameraFromGallery(selectedIndex);
    }

    private void refreshButtons() {
        boolean valid = selectedIndex >= 0 && selectedIndex < cameras.size();
        if (selectButton != null) selectButton.active = valid;
        if (editButton != null) editButton.active = valid && cameras.get(selectedIndex).available();
        if (unlinkButton != null) unlinkButton.active = valid;
    }

    /** The layout of the window as it is now; init sets it, and a gallery not yet shown lays itself out on demand. */
    private GalleryLayout layout() {
        if (layout == null || layout.width() != width || layout.height() != height) {
            layout = GalleryLayout.compute(width, height, TextStyles.measurer(), DjPanelScreen.Words.game());
        }
        return layout;
    }

    private int maxScroll() {
        return layout().maxScroll(cameras.size());
    }

    private static DjCameraPreviewRenderer.Snapshot snapshot(OpenDjPanelPayload.CameraEntry camera) {
        return new DjCameraPreviewRenderer.Snapshot(camera.available(), -1L,
                new Vec3(camera.x(), camera.y(), camera.z()),
                Mth.wrapDegrees(camera.yaw()), Mth.clamp(camera.pitch(), -89.0F, 89.0F),
                Mth.clamp(camera.fov(), 30.0F, 110.0F));
    }

    /**
     * Where the gallery's parts sit, in GUI units, and in which face each of its words is set: the header with the title,
     * the cameras' cards in up to four columns, and the footer with Back on the left and Disconnect, Control and Select on
     * the right, or, where one row cannot hold the four whole, two rows of two that share the width. Pure, like the DJ
     * panel's layout: no Minecraft calls, so the dj-screens-kit check sets it out without a window and reads
     * {@link #problems()} for every word that would not fit.
     */
    static final class GalleryLayout {
        static final int MARGIN = 12;
        static final int GAP = 8;
        static final int BUTTON_GAP = 6;
        /** The least a button's words get either side, so a short label is not a cramped slab. */
        static final int BUTTON_ROOM = 24;
        static final int MIN_BUTTON = 64;
        static final int CARD_MIN = 140;
        static final int CARD_MAX = 220;
        static final int CARD_NARROWEST = 112;
        static final float CARD_PAD = 5.0F;
        static final float NAME_GAP = 6.0F;
        /** The most cameras a booth links: the widest name is "Camera 16". */
        static final int MOST_CAMERAS = ru.gora.musicbattle.block.entity.DjPanelBlockEntity.MAX_LINKED_CAMERAS;
        static final TextStyle NAME_FACE = TextStyle.STRONG;
        static final String KEY = "screen.music_battle.dj_camera_gallery.";
        static final List<String> STATUS_KEYS = List.of("screen.music_battle.dj_panel.camera_offline",
                "screen.music_battle.dj_panel.camera_world_unavailable", "screen.music_battle.dj_panel.camera_chunk_unavailable",
                "screen.music_battle.dj_panel.camera_shaders_active", "screen.music_battle.dj_panel.camera_render_failed",
                "screen.music_battle.dj_camera_gallery.loading");

        /** A card, scrolled: its box, its picture inside a five-unit margin, and the y of its name line. */
        record Card(float x, float y, float width, float height, Rect preview, float nameY) { }

        private final int width;
        private final int height;
        private final int header;
        private final TextStyle titleFace;
        private final float titleY;
        private final int footerTop;
        private final int buttonRows;
        private final Rect back;
        private final Rect select;
        private final Rect control;
        private final Rect unlink;
        private final int contentTop;
        private final int contentBottom;
        private final int columns;
        private final float cardWidth;
        private final float previewHeight;
        private final float cardHeight;
        private final float gridLeft;
        private final List<String> problems;

        private GalleryLayout(int width, int height, int header, TextStyle titleFace, float titleY, int footerTop,
                              int buttonRows, Rect back, Rect select, Rect control, Rect unlink, int contentTop,
                              int contentBottom, int columns, float cardWidth, float previewHeight, float cardHeight,
                              float gridLeft, List<String> problems) {
            this.width = width;
            this.height = height;
            this.header = header;
            this.titleFace = titleFace;
            this.titleY = titleY;
            this.footerTop = footerTop;
            this.buttonRows = buttonRows;
            this.back = back;
            this.select = select;
            this.control = control;
            this.unlink = unlink;
            this.contentTop = contentTop;
            this.contentBottom = contentBottom;
            this.columns = columns;
            this.cardWidth = cardWidth;
            this.previewHeight = previewHeight;
            this.cardHeight = cardHeight;
            this.gridLeft = gridLeft;
            this.problems = problems;
        }

        static GalleryLayout compute(int width, int height, TextMeasurer measurer, DjPanelScreen.Words words) {
            List<String> problems = new ArrayList<>();
            int header = height < 300 ? 24 : 30;
            String title = words.get(KEY + "title");
            TextStyle titleFace = TextFit.width(measurer, title, TextStyle.TITLE) <= width - MARGIN * 2 ? TextStyle.TITLE
                    : TextStyle.STRONG;
            if (TextFit.width(measurer, title, titleFace) > width - MARGIN * 2) {
                problems.add("the title is wider than the window");
            }
            float titleY = header / 2.0F - titleFace.capHeight() / 2.0F;

            int buttonHeight = height < 300 ? 18 : 20;
            int backNeed = button(measurer, words.get(KEY + "back"), buttonHeight);
            int selectNeed = button(measurer, words.get(KEY + "select"), buttonHeight);
            int controlNeed = button(measurer, words.get(KEY + "control"), buttonHeight);
            int unlinkNeed = button(measurer, words.get(KEY + "unlink"), buttonHeight);
            int room = width - MARGIN * 2;
            int rows;
            Rect back;
            Rect select;
            Rect control;
            Rect unlink;
            if (backNeed + unlinkNeed + controlNeed + selectNeed + BUTTON_GAP * 2 + MARGIN <= room) {
                rows = 1;
                int y = height - 6 - buttonHeight;
                back = new Rect(MARGIN, y, backNeed, buttonHeight);
                float right = width - MARGIN;
                select = new Rect(right - selectNeed, y, selectNeed, buttonHeight);
                control = new Rect(select.x() - BUTTON_GAP - controlNeed, y, controlNeed, buttonHeight);
                unlink = new Rect(control.x() - BUTTON_GAP - unlinkNeed, y, unlinkNeed, buttonHeight);
            } else {
                rows = 2;
                int half = (room - BUTTON_GAP) / 2;
                if (Math.max(Math.max(backNeed, selectNeed), Math.max(controlNeed, unlinkNeed)) > half) {
                    problems.add("a button's words do not fit half the footer");
                }
                int second = height - 6 - buttonHeight;
                int first = second - 4 - buttonHeight;
                control = new Rect(MARGIN, first, half, buttonHeight);
                select = new Rect(width - MARGIN - half, first, half, buttonHeight);
                back = new Rect(MARGIN, second, half, buttonHeight);
                unlink = new Rect(width - MARGIN - half, second, half, buttonHeight);
            }
            int footerTop = Math.round(Math.min(back.y(), control.y())) - 6;

            int contentTop = header + GAP;
            int contentBottom = footerTop - 6;
            int available = width - MARGIN * 2;
            int columns = Math.max(1, Math.min(4, (available + GAP) / (CARD_MIN + GAP)));
            float cardWidth = Math.max(CARD_NARROWEST, Math.min(CARD_MAX, (available - GAP * (columns - 1)) / (float) columns));
            float gridWidth = cardWidth * columns + GAP * (columns - 1);
            float gridLeft = Math.max(MARGIN, (width - gridWidth) / 2.0F);
            float previewHeight = Math.round((cardWidth - CARD_PAD * 2) * 9.0F / 16.0F);
            float cardHeight = CARD_PAD + previewHeight + 5 + NAME_FACE.capHeight() + 6;
            if (contentBottom - contentTop < cardHeight) {
                problems.add("a whole card does not fit between the header and the footer");
            }
            GalleryLayout layout = new GalleryLayout(width, height, header, titleFace, titleY, footerTop, rows, back, select,
                    control, unlink, contentTop, contentBottom, columns, cardWidth, previewHeight, cardHeight, gridLeft,
                    problems);
            String name = words.format(KEY + "camera", Integer.toString(MOST_CAMERAS));
            if (TextFit.width(measurer, name, NAME_FACE) > cardWidth - CARD_PAD * 2) {
                layout.problems.add("a card's name does not fit the card");
            }
            Rect preview = layout.card(0, 0).preview();
            for (String key : STATUS_KEYS) {
                if (statusBlock(measurer, words.get(key), preview) == null) {
                    layout.problems.add("the preview's message " + key + " does not fit the preview");
                }
            }
            if (emptyBlock(measurer, words.get(KEY + "empty"), layout.emptyPanel()) == null) {
                layout.problems.add("the empty gallery's message does not fit its panel");
            }
            return layout;
        }

        /** A button's width for its label whole, and never less than a Workshop dialog's actions. */
        static int button(TextMeasurer measurer, String label, int height) {
            return Math.max(MIN_BUTTON, KitButton.need(measurer, label, height, false) + BUTTON_ROOM - (int) KitButton.PAD);
        }

        /** The preview's message: the body face when its lines fit the picture, else the caption, never a broken word. */
        static DjPanelScreen.PanelLayout.Block statusBlock(TextMeasurer measurer, String text, Rect preview) {
            return DjPanelScreen.PanelLayout.block(measurer, text, preview.w() - 8, preview.h() - 6, TextStyle.BODY,
                    TextStyle.CAPTION);
        }

        /** The empty gallery's message under its icon. */
        static DjPanelScreen.PanelLayout.Block emptyBlock(TextMeasurer measurer, String text, Rect panel) {
            return DjPanelScreen.PanelLayout.block(measurer, text, panel.w() - 24.0F, panel.h() - 28.0F, TextStyle.BODY,
                    TextStyle.CAPTION);
        }

        Card card(int index, int scroll) {
            int column = index % columns;
            int row = index / columns;
            float x = gridLeft + column * (cardWidth + GAP);
            float y = contentTop + row * (cardHeight + GAP) - scroll;
            Rect preview = new Rect(x + CARD_PAD, y + CARD_PAD, cardWidth - CARD_PAD * 2, previewHeight);
            return new Card(x, y, cardWidth, cardHeight, preview, preview.bottom() + 5);
        }

        int maxScroll(int cameras) {
            if (cameras <= 0) {
                return 0;
            }
            int rows = (cameras + columns - 1) / columns;
            float content = rows * cardHeight + (rows - 1) * GAP;
            return Math.max(0, (int) Math.ceil(content - (contentBottom - contentTop)));
        }

        /** The empty gallery's panel: the cards' column, up to 84 high, in the middle of the view. */
        Rect emptyPanel() {
            float panelWidth = Math.min(width - MARGIN * 2, 360);
            float panelHeight = Math.min(contentBottom - contentTop, 84);
            return new Rect((width - panelWidth) / 2.0F, contentTop + (contentBottom - contentTop - panelHeight) / 2.0F,
                    panelWidth, panelHeight);
        }

        int width() { return width; }
        int height() { return height; }
        int header() { return header; }
        TextStyle titleFace() { return titleFace; }
        float titleY() { return titleY; }
        int footerTop() { return footerTop; }
        int buttonRows() { return buttonRows; }
        Rect back() { return back; }
        Rect select() { return select; }
        Rect control() { return control; }
        Rect unlink() { return unlink; }
        int contentTop() { return contentTop; }
        int contentBottom() { return contentBottom; }
        int columns() { return columns; }
        float gridLeft() { return gridLeft; }
        float gridWidth() { return cardWidth * columns + GAP * (columns - 1); }
        List<String> problems() { return java.util.Collections.unmodifiableList(problems); }
    }
}
