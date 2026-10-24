package ru.gora.musicbattle.client;

import java.util.ArrayList;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.network.EditDjCameraRequestPayload;
import ru.gora.musicbattle.network.OpenDjPanelPayload;

/**
 * Camera switcher for a DJ installation. Gallery thumbnails are filled by a
 * bounded round-robin queue: one low-resolution nested world pass at most per
 * render budget, never one pass per visible card.
 */
public final class DjCameraGalleryScreen extends Screen {
    private static final int MARGIN = 18;
    private static final int GAP = 8;
    private static final int TOP = 42;
    private static final int BOTTOM = 42;
    private static final int COMPACT_BOTTOM = 66;

    private final DjPanelScreen parent;
    private final BlockPos panelPos;
    private final List<OpenDjPanelPayload.CameraEntry> cameras;
    private final Map<Long, DjCameraPreviewRenderer.Status> statuses = new HashMap<>();
    private int selectedIndex;
    private int scroll;
    private int previewCursor;
    private boolean initialLayout = true;
    private Button selectButton;
    private Button editButton;
    private Button unlinkButton;

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
        int buttonColumns = width < 360 ? 2 : 4;
        int buttonWidth = Math.min(150,
                Math.max(56, (width - 12 - (buttonColumns - 1) * 6) / buttonColumns));
        int totalWidth = buttonWidth * buttonColumns + (buttonColumns - 1) * 6;
        int x = Math.max(6, (width - totalWidth) / 2);
        int y = height - (buttonColumns == 2 ? 54 : 30);
        addRenderableWidget(Button.builder(Component.translatable("screen.music_battle.dj_camera_gallery.back"),
                button -> onClose()).bounds(x, y, buttonWidth, 20).build());
        selectButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.music_battle.dj_camera_gallery.select"),
                button -> selectCurrent()).bounds(buttonX(x, buttonWidth, buttonColumns, 1),
                buttonY(y, buttonColumns, 1), buttonWidth, 20).build());
        selectButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_camera_gallery.select_tooltip")));
        editButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.music_battle.dj_camera_gallery.control"),
                button -> editCurrent()).bounds(buttonX(x, buttonWidth, buttonColumns, 2),
                buttonY(y, buttonColumns, 2), buttonWidth, 20).build());
        editButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_camera_gallery.control_tooltip")));
        unlinkButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.music_battle.dj_camera_gallery.unlink"),
                button -> unlinkCurrent()).bounds(buttonX(x, buttonWidth, buttonColumns, 3),
                buttonY(y, buttonColumns, 3), buttonWidth, 20).build());
        if (initialLayout && selectedIndex >= 0) {
            Card selected = card(selectedIndex);
            if (selected.y + selected.height > contentBottom()) {
                scroll += selected.y + selected.height - contentBottom();
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
        fillNextThumbnail(partialTick);
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.96F));
        graphics.fill(0, 0, width, 32, Palette.BG_1);
        graphics.drawCenteredString(font, title, width / 2, 12, Palette.TEXT_1);

        if (cameras.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.music_battle.dj_camera_gallery.empty"),
                    width / 2, height / 2 - 6, Palette.TEXT_3);
        } else {
            graphics.enableScissor(0, TOP, width, contentBottom());
            for (int index = 0; index < cameras.size(); index++) {
                Card card = card(index);
                if (card.y + card.height < TOP || card.y > contentBottom()) {
                    continue;
                }
                drawCard(graphics, index, card, mouseX, mouseY);
            }
            graphics.disableScissor();
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void fillNextThumbnail(float partialTick) {
        if (cameras.isEmpty()) {
            return;
        }
        int attempts = cameras.size();
        while (attempts-- > 0) {
            int index = Math.floorMod(previewCursor++, cameras.size());
            Card card = card(index);
            if (card.y + card.height < TOP || card.y > contentBottom()) {
                continue;
            }
            OpenDjPanelPayload.CameraEntry camera = cameras.get(index);
            long key = camera.pos().asLong();
            if (DjCameraPreviewRenderer.hasThumbnail(key)) {
                continue;
            }
            DjCameraPreviewRenderer.Status status = DjCameraPreviewRenderer.updateThumbnail(
                    key, snapshot(camera), Math.max(96, card.width - 6),
                    Math.max(54, card.previewHeight - 4), partialTick);
            statuses.put(key, status);
            break;
        }
    }

    private void drawCard(GuiGraphics graphics, int index, Card card, int mouseX, int mouseY) {
        OpenDjPanelPayload.CameraEntry camera = cameras.get(index);
        boolean selected = index == selectedIndex;
        boolean hovered = inside(mouseX, mouseY, card)
                && mouseY >= TOP && mouseY < contentBottom();
        int border = selected ? Palette.ACCENT : hovered ? Palette.LINK : Palette.LINE;
        graphics.fill(card.x, card.y, card.x + card.width, card.y + card.height, border);
        graphics.fill(card.x + 2, card.y + 2, card.x + card.width - 2,
                card.y + card.height - 2, Palette.BG_2);

        int px = card.x + 3;
        int py = card.y + 3;
        int pw = card.width - 6;
        int ph = card.previewHeight - 4;
        graphics.fill(px, py, px + pw, py + ph, Palette.BG_0);
        long key = camera.pos().asLong();
        if (DjCameraPreviewRenderer.hasThumbnail(key)) {
            DjCameraPreviewRenderer.drawThumbnail(graphics, key, px, py, pw, ph);
        } else {
            DjCameraPreviewRenderer.Status status = statuses.getOrDefault(key,
                    camera.available() ? DjCameraPreviewRenderer.Status.READY
                            : DjCameraPreviewRenderer.Status.CAMERA_UNAVAILABLE);
            Component text = statusText(status);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(text.getString(), pw - 8),
                    px + pw / 2, py + Math.max(3, (ph - font.lineHeight) / 2), Palette.TEXT_MUTE);
        }

        String name = Component.translatable("screen.music_battle.dj_camera_gallery.camera", index + 1).getString();
        graphics.drawString(font, font.plainSubstrByWidth(name, card.width / 2),
                card.x + 5, card.y + card.previewHeight + 2, Palette.TEXT_1, false);
        String coords = camera.pos().toShortString();
        String fitted = font.plainSubstrByWidth(coords, card.width / 2 - 7);
        graphics.drawString(font, fitted, card.x + card.width - font.width(fitted) - 5,
                card.y + card.previewHeight + 2, Palette.TEXT_2, false);
    }

    private Component statusText(DjCameraPreviewRenderer.Status status) {
        return Component.translatable(switch (status) {
            case CAMERA_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_offline";
            case WORLD_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_world_unavailable";
            case CHUNK_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_chunk_unavailable";
            case SHADERS_ACTIVE -> "screen.music_battle.dj_panel.camera_shaders_active";
            case RENDER_FAILED -> "screen.music_battle.dj_panel.camera_render_failed";
            case READY -> "screen.music_battle.dj_camera_gallery.loading";
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int index = 0; index < cameras.size(); index++) {
                Card card = card(index);
                if (inside(mouseX, mouseY, card)
                        && mouseY >= TOP && mouseY < contentBottom()) {
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

    private Card card(int index) {
        int columns = Math.max(1, Math.min(4, (width - MARGIN * 2 + GAP) / 140));
        int availableWidth = width - MARGIN * 2 - GAP * (columns - 1);
        int cardWidth = Math.max(112, Math.min(220, availableWidth / columns));
        int gridWidth = cardWidth * columns + GAP * (columns - 1);
        int gridLeft = Math.max(MARGIN, (width - gridWidth) / 2);
        // previewHeight includes the four vertical pixels consumed by the
        // card's preview padding; the actual framebuffer rectangle therefore
        // remains a true 16:9 image at every responsive card width.
        int contentPreviewHeight = Math.max(58, Math.round((cardWidth - 6) * 9.0F / 16.0F));
        int previewHeight = contentPreviewHeight + 4;
        int cardHeight = previewHeight + 17;
        int column = index % columns;
        int row = index / columns;
        return new Card(gridLeft + column * (cardWidth + GAP), TOP + row * (cardHeight + GAP) - scroll,
                cardWidth, cardHeight, previewHeight);
    }

    private int maxScroll() {
        if (cameras.isEmpty()) return 0;
        Card last = card(cameras.size() - 1);
        int contentBottomWithoutScroll = last.y + scroll + last.height;
        return Math.max(0, contentBottomWithoutScroll - contentBottom());
    }

    private int contentBottom() {
        return height - (width < 360 ? COMPACT_BOTTOM : BOTTOM);
    }

    private static int buttonX(int startX, int buttonWidth, int columns, int index) {
        return startX + (index % columns) * (buttonWidth + 6);
    }

    private static int buttonY(int startY, int columns, int index) {
        return startY + (index / columns) * 24;
    }

    private static boolean inside(double mouseX, double mouseY, Card card) {
        return mouseX >= card.x && mouseX < card.x + card.width
                && mouseY >= card.y && mouseY < card.y + card.height;
    }

    private static DjCameraPreviewRenderer.Snapshot snapshot(OpenDjPanelPayload.CameraEntry camera) {
        return new DjCameraPreviewRenderer.Snapshot(camera.available(), -1L,
                new Vec3(camera.x(), camera.y(), camera.z()),
                Mth.wrapDegrees(camera.yaw()), Mth.clamp(camera.pitch(), -89.0F, 89.0F),
                Mth.clamp(camera.fov(), 30.0F, 110.0F));
    }

    private record Card(int x, int y, int width, int height, int previewHeight) {
    }
}
