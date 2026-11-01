package ru.gora.musicbattle.client.workshop;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.MusicBattleVoteScreen;
import ru.gora.musicbattle.client.ScreenStack;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.PreviewPlayer;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.SkinHead;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.workshop.layout.BattleSetup;
import ru.gora.musicbattle.client.workshop.layout.BattleTracks;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.ShellLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.data.VoteTiming;
import ru.gora.musicbattle.network.DjWorkshopAddPayload;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;
import ru.gora.musicbattle.workshop.api.WorkshopSession;

/**
 * The Workshop: one screen with the shell (title bar, tabs, account chip) and its internal views
 * — the listing (Browse and Installed), the set page, the account view and the upload wizard as overlays that slide
 * over the listing and keep its state.
 *
 * <p>Opening it runs the Workshop's open hooks (inbox scan, lazy Minecraft sign-in, installed-check at most every ten
 * minutes). Previews stop when it closes; the cover cache is released with it.
 *
 * <p>Opened through {@link #openPicker} it is a battle's map picker: the same tabs and the same cards, with the vote's
 * countdown in the title bar. Its Server tab then lists every track the battle can vote for, the press that would open
 * or install a set chooses it, and a set page's primary button reads {@link #primaryAction}. The choice goes to the
 * battle and the Workshop closes back to it.
 */
public final class WorkshopScreen extends UiLayer implements WorkshopUi, WorkshopDropTarget,
        ru.gora.musicbattle.client.ParentedScreen {
    /** A view that covers the content area (set page, account, wizard). */
    public interface Overlay {
        void setArea(Rect area);

        void render(GuiGraphics g, double mouseX, double mouseY, float partialTick);

        default boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        default boolean mouseDragged(double mouseX, double mouseY, int button) {
            return false;
        }

        default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            return false;
        }

        default boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return false;
        }

        default boolean charTyped(char character, int modifiers) {
            return false;
        }

        /** The title bar text while this overlay is open. */
        Component title();

        default void onClose() {
        }

        /**
         * Whether closing this overlay now would lose words the player wrote and has not sent; every way out of it asks
         * this first. {@code typed} is all the screen itself can see: that the overlay took typed or pasted characters
         * since it opened. That is also the default answer, which errs towards asking. A view that owns its fields
         * answers from them instead - an empty composer after a comment went out, an upload that has finished - and
         * then the question is never asked over nothing.
         */
        default boolean hasUnsavedText(boolean typed) {
            return typed;
        }
    }

    /**
     * A battle waiting for its map: what it can vote for, where the choice goes, how much of the vote is left, and the
     * picture the server keeps for one of its tracks. The Workshop opened with one is that battle's map picker.
     */
    public record Picker(BattleTracks tracks, Consumer<BattleSetup.Pick> onChoose, IntSupplier remainingTicks,
                         Function<BattleTracks.Track, ResourceLocation> preview) {
        public Picker {
            tracks = tracks == null ? BattleTracks.NONE : tracks;
            remainingTicks = remainingTicks == null ? () -> 0 : remainingTicks;
            preview = preview == null ? row -> null : preview;
        }
    }

    /**
     * What a set page's primary button does in place of Install, and the words it carries. {@code sub} is the quieter
     * second line, or null; {@code press} does nothing while the button is off.
     */
    public record PrimaryAction(String label, String sub, boolean enabled, Runnable press) {
    }

    private static final int PREVIEW_CACHE = 6;

    private final Screen parent;
    private final WorkshopClient client = WorkshopClient.get();
    private final ListingView browse;
    private final ListingView installedTab;
    /** The Server beatmaps tab, hidden until a server reports the feature. */
    private final ListingView serverTab;
    private final Deque<Overlay> overlays = new ArrayDeque<>();
    private final Motion.Tween overlaySlide = new Motion.Tween(0.0);
    private final Map<Long, byte[]> previews = new LinkedHashMap<>(8, 0.75F, true);
    /** Overlays that took characters the player typed or pasted: what the screen itself can see of their text. */
    private final Set<Overlay> typedInto = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Answers that came back while something was on top of this screen; drained when it draws again. */
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
    /** The battle waiting for a map, or null when the Workshop was opened for its own sake. */
    private Picker picker;
    /**
     * Whether {@link #picker} adds to a DJ booth's beatmap library (DjPanelBlockEntity.library) rather than a battle's
     * vote: then there is no clock to show.
     */
    private boolean djPicker;
    /**
     * A Workshop that is never shown, only lent to "Add to server" for a DJ's pick. That flow reports back through
     * {@link #later}, which otherwise holds work until its screen is drawn again, and this one never is.
     */
    private boolean detached;
    /** The DJ's Workshop pick on its way to the server, or null; render thread only. */
    private static DjCourier djCourier;
    private ShellLayout.Tab tab = ShellLayout.Tab.BROWSE;
    private Layout shell;
    private ShellLayout.State shellState;
    private boolean opened;
    private long previewWanted;
    /** The set whose clip is being downloaded right now (0: none), so its play button can show that it is busy. */
    private long previewFetching;
    /** The set whose clip download last failed (0: none); read once by the page that asked, see takePreviewFailure. */
    private long previewFailedSetId;

    @Override
    public Screen musicBattleParent() {
        return parent;
    }

    public WorkshopScreen(Screen parent) {
        super(Component.translatable(WorkshopStrings.KEY + "title.listing"));
        this.parent = parent;
        browse = new ListingView(this, client, false);
        installedTab = new ListingView(this, client, true);
        serverTab = new ListingView(this, client, false, true);
    }

    /** Opens the Workshop with the upload wizard and one of the player's tracks ticked (track library). */
    public static void openUploadFor(Screen parent, String trackId) {
        WorkshopScreen screen = new WorkshopScreen(parent);
        Minecraft.getInstance().setScreen(screen);
        screen.openUploadWithTrack(trackId);
    }

    /**
     * Opens the Workshop as a battle's map picker. Its tabs work as they always do; the Server tab adds every track the
     * battle can vote for, and it is the tab the picker opens on whenever the battle has any, because those are the
     * maps that play at once. The choice reaches {@code picker} before the Workshop closes back to {@code parent}.
     */
    public static void openPicker(Screen parent, Picker picker) {
        WorkshopScreen screen = new WorkshopScreen(parent);
        screen.picker = picker;
        if (!picker.tracks().isEmpty()) {
            screen.tab = ShellLayout.Tab.SERVER;
        }
        Minecraft.getInstance().setScreen(screen);
    }

    /**
     * Opens the Workshop as a DJ booth's beatmap picker: the battle picker's tabs and cards, with every beatmap the
     * server offers the booth on the Server tab and no vote clock. The choice reaches {@code picker} as the Workshop
     * closes back, and joins the booth's beatmap library (DjPanelBlockEntity.library), not its playlist: the playlist
     * queues from that library with its own Add button.
     */
    public static void openDjPicker(Screen parent, Picker picker) {
        WorkshopScreen screen = new WorkshopScreen(parent);
        screen.picker = picker;
        screen.djPicker = true;
        if (!picker.tracks().isEmpty()) {
            screen.tab = ShellLayout.Tab.SERVER;
        }
        Minecraft.getInstance().setScreen(screen);
    }

    /** Opens the Workshop straight on a set page (a comment link, "Open set #42"). */
    public static void openSet(Screen parent, long setId) {
        WorkshopScreen screen = new WorkshopScreen(parent);
        Minecraft.getInstance().setScreen(screen);
        screen.openSetPage(setId, null);
    }

    @Override
    protected void layout() {
        if (!opened) {
            opened = true;
            client.onWorkshopOpened();
            client.ensureMeta(null);
            activeListing().onShow();
        }
        applyShell(shellState(), true);
    }

    /**
     * Lays the shell out and hands the content area to every view. The area moves when the notice under the tabs comes
     * or goes, and a view left with the old one would draw over the strip or leave a gap above itself.
     */
    private void applyShell(ShellLayout.State state, boolean force) {
        Rect before = shell == null ? null : shell.rect(ShellLayout.CONTENT);
        shellState = state;
        shell = ShellLayout.compute(this.width, this.height, TextStyles.measurer(), WorkshopTr.INSTANCE, state);
        Rect content = shell.rect(ShellLayout.CONTENT);
        if (!force && content.equals(before)) {
            return;
        }
        browse.setArea(content);
        installedTab.setArea(content);
        serverTab.setArea(content);
        for (Overlay overlay : overlays) {
            overlay.setArea(content);
        }
    }

    private ShellLayout.State shellState() {
        WorkshopSession.State state = client.session().state();
        String account = state.signedIn() && state.account() != null ? state.account().username() : null;
        Overlay overlay = overlays.peek();
        String title = overlay != null ? overlay.title().getString()
                : picker != null ? Component.translatable(WorkshopStrings.KEY + "title.pick").getString() : null;
        if (picker != null && !djPicker) {
            // The vote does not wait while the player browses, so the picker carries its clock wherever they are.
            title = title + "  ·  " + VoteTiming.formatTicks(picker.remainingTicks().getAsInt());
        }
        boolean serverList = WorkshopServerHooks.serverBeatmapsAvailable() || picker != null && !picker.tracks().isEmpty();
        String notice = picker != null && tab == ShellLayout.Tab.SERVER && picker.tracks().truncated()
                ? WorkshopTr.INSTANCE.get("screen.music_battle.vote.truncated") : null;
        return new ShellLayout.State(tab, serverList, account, overlay != null, title, notice);
    }

    private ListingView activeListing() {
        return switch (tab) {
            case INSTALLED -> installedTab;
            case SERVER -> serverTab;
            default -> browse;
        };
    }

    // ---------------------------------------------------------------- rendering

    @Override
    protected void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        pump();
        if (shell == null) {
            layout();
        }
        // The shell follows sign-in, server state and the vote's clock, but it is only laid out again when one of
        // them changed.
        ShellLayout.State state = shellState();
        if (!state.equals(shellState) || shell == null) {
            applyShell(state, false);
        }
        drawShell(g, mouseX, mouseY);

        Overlay overlay = overlays.peek();
        boolean overlayOpen = overlay != null;
        if ((overlaySlide.target() > 0.5) != overlayOpen) {
            overlaySlide.animateTo(overlayOpen ? 1.0 : 0.0, Palette.T_CARD, Motion.Easing.OUT_QUINT);
        }
        float slide = overlaySlide.value();
        Rect content = shell.rect(ShellLayout.CONTENT);
        if (slide < 0.999F) {
            activeListing().render(g, overlayOpen ? -1 : mouseX, overlayOpen ? -1 : mouseY, partialTick);
        }
        if (slide > 0.001F && overlay != null) {
            float offset = (1F - slide) * content.w();
            g.pose().pushPose();
            g.pose().translate(offset, 0F, 0F);
            RoundedRect.fill(g, content.x() - 2, content.y() - 2, content.w() + 4, content.h() + 4, Palette.RADIUS_MD, Palette.BG_0);
            overlay.render(g, mouseX - offset, mouseY, partialTick);
            g.pose().popPose();
        }
    }

    private void drawShell(GuiGraphics g, double mouseX, double mouseY) {
        Rect panel = shell.rect(ShellLayout.PANEL);
        Rect titleBar = shell.rect(ShellLayout.TITLE_BAR);
        Rect tabs = shell.rect(ShellLayout.TABS);
        if (panel.w() < this.width) {
            RoundedRect.fill(g, panel.x(), panel.y(), panel.w(), panel.h(), 0, Palette.BG_1);
        }
        RoundedRect.fill(g, titleBar.x(), titleBar.y(), titleBar.w(), titleBar.h(), 0, Palette.BG_1);
        RoundedRect.fill(g, tabs.x(), tabs.y(), tabs.w(), tabs.h(), 0, Palette.BG_2);
        RoundedRect.fill(g, tabs.x(), tabs.bottom() - 1, tabs.w(), 1, 0, Palette.LINE);
        WorkshopDraw.icon(g, shell.rect("shell.icon"), UiIcon.MUSIC, Palette.ACCENT);
        WorkshopDraw.text(g, shell, "shell.title", Palette.TEXT_1);

        Rect close = shell.rect("shell.close");
        boolean closeHovered = WorkshopDraw.hot(close, mouseX, mouseY);
        WorkshopDraw.button(g, close, WorkshopDraw.ButtonStyle.QUIET, closeHovered, true);
        WorkshopDraw.icon(g, close.inset(close.w() * 0.28F, close.h() * 0.28F), UiIcon.X,
                closeHovered ? Palette.TEXT_1 : Palette.TEXT_3);

        Rect back = shell.rect("shell.back");
        if (!back.empty()) {
            boolean hovered = WorkshopDraw.hot(back, mouseX, mouseY);
            WorkshopDraw.button(g, back, WorkshopDraw.ButtonStyle.QUIET, hovered, true);
            WorkshopDraw.icon(g, back.inset(back.w() * 0.28F, back.h() * 0.28F), UiIcon.ARROW_LEFT,
                    hovered ? Palette.TEXT_1 : Palette.TEXT_3);
        }

        Rect chip = shell.rect("shell.account");
        boolean chipHovered = WorkshopDraw.hot(chip, mouseX, mouseY);
        WorkshopDraw.panel(g, chip, chip.h() * 0.5F, chipHovered ? Palette.BG_3 : Palette.withAlpha(Palette.BG_3, 0.6F));
        Rect head = shell.rect("shell.account.head");
        ResourceLocation avatar = avatarTexture();
        if (avatar != null) {
            RoundedImage.draw(g, avatar, head.x(), head.y(), head.w(), head.h(), head.w() * 0.5F, 0F, 1F);
        } else if (client.session().state().kind() == WorkshopSession.Kind.MINECRAFT) {
            SkinHead.drawSkin(g, SkinHead.steve(), head.x(), head.y(), head.w(), head.w() * 0.3F);
        } else {
            WorkshopDraw.icon(g, head.inset(2, 2), UiIcon.USER_ROUND, Palette.TEXT_3);
        }
        WorkshopDraw.text(g, shell, "shell.account.name", chipHovered ? Palette.TEXT_1 : Palette.TEXT_2);

        for (ShellLayout.Tab entry : ShellLayout.Tab.values()) {
            Rect rect = shell.rect("shell.tab." + entry.id());
            if (rect.empty()) {
                continue;
            }
            boolean active = entry == tab;
            boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
            if (active || hovered) {
                WorkshopDraw.panel(g, rect, Palette.RADIUS_SM, active ? Palette.BG_3 : Palette.withAlpha(Palette.BG_3, 0.6F));
            }
            WorkshopDraw.text(g, shell, "shell.tab." + entry.id() + ".text", active ? Palette.TEXT_1 : Palette.TEXT_3);
            if (active) {
                RoundedRect.fill(g, rect.x() + 2, rect.bottom() - 1.5F, rect.w() - 4, 1.5F, 0.75F, Palette.ACCENT);
            }
        }

        Rect upload = shell.rect("shell.upload");
        boolean uploadHovered = WorkshopDraw.hot(upload, mouseX, mouseY);
        WorkshopDraw.button(g, upload, WorkshopDraw.ButtonStyle.SECONDARY, uploadHovered, true);
        WorkshopDraw.icon(g, new Rect(upload.x() + 4, upload.y() + (upload.h() - 7) * 0.5F, 7, 7), UiIcon.UPLOAD,
                uploadHovered ? Palette.TEXT_1 : Palette.TEXT_2);
        WorkshopDraw.text(g, shell, "shell.upload.text", uploadHovered ? Palette.TEXT_1 : Palette.TEXT_2);

        Rect notice = shell.rect(ShellLayout.NOTICE);
        if (!notice.empty()) {
            RoundedRect.fill(g, notice.x(), notice.y(), notice.w(), notice.h(), 0, Palette.mix(Palette.BG_2, Palette.WARN, 0.08F));
            RoundedRect.fill(g, notice.x(), notice.bottom() - 1, notice.w(), 1, 0, Palette.LINE);
            WorkshopDraw.icon(g, shell.rect(ShellLayout.NOTICE + ".icon"), UiIcon.INFO, Palette.WARN);
            WorkshopDraw.paragraph(g, shell, ShellLayout.NOTICE + ".text", Palette.TEXT_2);
        }
    }

    private ResourceLocation avatarTexture() {
        WorkshopSession.State state = client.session().state();
        if (!client.networkAllowed() || !state.signedIn() || state.account() == null || state.account().avatarUrl() == null) {
            return null;
        }
        return CoverCache.get(state.account().avatarUrl());
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Overlay overlay = overlays.peek();
        if (shell != null) {
            if (shellClick(mouseX, mouseY, button)) {
                return true;
            }
        }
        if (overlay != null) {
            return overlay.mouseClicked(mouseX, mouseY, button);
        }
        if (activeListing().mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean shellClick(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (shell.rect("shell.close").contains(mouseX, mouseY)) {
            click();
            onClose();
            return true;
        }
        if (shell.rect("shell.back").contains(mouseX, mouseY)) {
            click();
            requestCloseOverlay();
            return true;
        }
        if (shell.rect("shell.account").contains(mouseX, mouseY)) {
            click();
            openAccount();
            return true;
        }
        if (shell.rect("shell.upload").contains(mouseX, mouseY)) {
            click();
            openUpload(null);
            return true;
        }
        for (ShellLayout.Tab entry : ShellLayout.Tab.values()) {
            if (shell.rect("shell.tab." + entry.id()).contains(mouseX, mouseY)) {
                click();
                switchTab(entry);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        Overlay overlay = overlays.peek();
        if (overlay != null && overlay.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        if (overlay == null && activeListing().mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Overlay overlay = overlays.peek();
        if (overlay != null) {
            return overlay.mouseScrolled(mouseX, mouseY, scrollY);
        }
        if (activeListing().mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Overlay escaped = overlays.peek();
            if (escaped == null) {
                onClose();
                return true;
            }
            // The overlay hears Escape first: a reply being written and a revealed field are its own to close, and
            // this branch used to return before any of them ever saw the key.
            if (escaped.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            requestCloseOverlay();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F && (Screen.hasControlDown() || Screen.hasAltDown())) {
            if (overlays.isEmpty()) {
                activeListing().focusSearch();
                return true;
            }
        }
        Overlay overlay = overlays.peek();
        if (overlay != null) {
            if (overlay.keyPressed(keyCode, scanCode, modifiers)) {
                if (Screen.isPaste(keyCode)) {
                    // A pasted comment is as much the player's text as a typed one.
                    typedInto.add(overlay);
                }
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (activeListing().keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        Overlay overlay = overlays.peek();
        if (overlay != null) {
            if (overlay.charTyped(character, modifiers)) {
                typedInto.add(overlay);
                return true;
            }
            return super.charTyped(character, modifiers);
        }
        return activeListing().charTyped(character, modifiers) || super.charTyped(character, modifiers);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        WorkshopDrops.onFilesDropped(this, paths);
    }

    /**
     * A battle's vote keeps running on the server while the player browses, so the Workshop must not pause a single
     * player world then: the integrated server would stop counting the vote down while the screen's clock, which runs
     * on real time, went on to 0:00 and stayed there. That covers the picker and the Workshop opened over the vote
     * screen. Anywhere else it pauses as every menu does.
     */
    @Override
    public boolean isPauseScreen() {
        return picker == null && ScreenStack.find(parent, MusicBattleVoteScreen.class) == null;
    }

    /** The close button and Escape over the listing: the whole Workshop goes, after the question if words would. */
    @Override
    public void onClose() {
        leave(false, this::closeNow);
    }

    /**
     * The Workshop can also be taken away from outside: a battle that starts while it is a picker closes every screen
     * at once, and closeNow never runs. A preview clip started on a card then played on over the battle's music. A
     * dialog over the Workshop removes it too and must keep both the clip and the covers, so this waits until the
     * change of screen has happened and keeps them only for the Workshop's own dialogs and the link confirmation, which
     * both come back to it. Any other screen means the Workshop is gone: the vote screen that opens when a challenge
     * is accepted used to arrive under a playing clip, with a code sign-in still holding its slot.
     */
    @Override
    public void removed() {
        super.removed();
        Minecraft game = Minecraft.getInstance();
        game.tell(() -> {
            if (!(game.screen instanceof WorkshopUi) && !(game.screen instanceof ConfirmLinkScreen)) {
                closeAllOverlays();
                PreviewPlayer.stop();
                CoverCache.clear();
            }
        });
    }

    private void closeNow() {
        // Closing the Workshop closes what is open in it, so each page has its say: a code sign-in still waiting tells
        // the service it was abandoned instead of holding its network's sign-in slot for ten minutes. The player has
        // already been asked about anything they wrote, by leave().
        closeAllOverlays();
        PreviewPlayer.stop();
        CoverCache.clear();
        HandCursor.reset();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    // ---------------------------------------------------------------- navigation and helpers

    /**
     * Shows a tab, closing whatever is open over the listing.
     *
     * <p>A press on the tab that is already active used to do nothing at all, which made the account view a dead
     * end: "Sign in" opens it over whichever tab the player was on, and from there Browse was unreachable because
     * Browse was still the active tab. A tab press is a way back to that tab, always - after the same question the
     * back arrow asks, because a tab closes the same pages.
     */
    public void switchTab(ShellLayout.Tab value) {
        leave(false, () -> showTab(value));
    }

    private void showTab(ShellLayout.Tab value) {
        closeAllOverlays();
        if (tab != value) {
            activeListing().onHide();
            tab = value;
            activeListing().onShow();
        }
        layout();
    }

    public void pushOverlay(Overlay overlay) {
        overlays.push(overlay);
        overlay.setArea(shell == null ? Rect.EMPTY : shell.rect(ShellLayout.CONTENT));
    }

    public void closeOverlay() {
        Overlay overlay = overlays.poll();
        if (overlay != null) {
            typedInto.remove(overlay);
            overlay.onClose();
        }
        layout();
    }

    /** Closes the top overlay, asking first when it holds words the player wrote and has not sent. */
    public void requestCloseOverlay() {
        leave(true, this::closeOverlay);
    }

    public void closeAllOverlays() {
        while (!overlays.isEmpty()) {
            closeOverlay();
        }
    }

    /**
     * Runs {@code then} once nothing the player wrote stands in the way: at once when no overlay it would close holds
     * unsent words, otherwise after "discard what you typed?". Every way out of an overlay comes through here - the
     * back arrow, Escape, a tab, the close button and a battle's choice - because one way that throws a comment or a
     * half-filled upload away without a word is one too many. {@code topOnly} is for the back arrow and Escape, which
     * close the top overlay and leave the ones under it.
     */
    private void leave(boolean topOnly, Runnable then) {
        boolean unsaved = false;
        for (Overlay overlay : overlays) {
            if (overlay.hasUnsavedText(typedInto.contains(overlay))) {
                unsaved = true;
                break;
            }
            if (topOnly) {
                break;
            }
        }
        if (!unsaved) {
            then.run();
            return;
        }
        showDialog(WorkshopConfirmScreen.danger(this,
                Component.translatable(WorkshopStrings.KEY + "discard.title"),
                Component.translatable(WorkshopStrings.KEY + "discard.body"),
                Component.translatable(WorkshopStrings.KEY + "discard.confirm"),
                then));
    }

    public void openSetPage(long setId, CardModel model) {
        // In a battle's picker the press that would open a set page chooses the map. A Workshop card with no charts in
        // it has nothing to vote for, so it still opens its page, where the Choose button explains itself by being off.
        if (picker != null && model != null && pickOf(model, 0) != null) {
            choose(model, 0);
            return;
        }
        pushOverlay(new SetPageView(this, client, setId, model));
    }

    /**
     * Opens the page of a map from the Server tab: a Workshop set on the server ({@code ws_<setId>}) or a track made in
     * the editor ({@code track_<trackId>}). It starts on the server's own comments, and the picture the server keeps
     * stands in for a cover the Workshop cannot give.
     */
    public void openServerPage(long setId, CardModel model, String serverKey,
                               Supplier<ResourceLocation> picture) {
        pushOverlay(new SetPageView(this, client, setId, model, serverKey, picture));
    }

    /**
     * The page of one of the server's own beatmaps, under its {@code track_<id>} comment thread. The row is what the
     * server's list says about it, and the page is built from it rather than from the Workshop, which never heard of it.
     */
    public void openServerTrackPage(long cardId, CardModel model, ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track row,
                                    Supplier<ResourceLocation> picture) {
        pushOverlay(new SetPageView(this, client, cardId, model, "track_" + row.id(), picture, row));
    }

    /**
     * Whether the Workshop is open as a battle's map picker. The listing then makes Choose every card's primary action,
     * and a set page reads its primary button from {@link #primaryAction}.
     */
    public boolean picking() {
        return picker != null;
    }

    /** The battle this Workshop is choosing a map for, or null outside a picker. */
    public Picker picker() {
        return picker;
    }

    /**
     * Hands a chosen card to the battle: a Workshop set with every difficulty the battle can play from it, or one of
     * the server's own tracks. A set that is not installed here yet is chosen all the same - the setup screen starts
     * what it needs and says where it stands.
     *
     * @param chart which of the set's charts to start on; the setup screen can still swap it for another
     */
    public void choose(CardModel model, int chart) {
        BattleSetup.Pick pick = pickOf(model, chart);
        if (pick != null) {
            deliver(pick);
        }
    }

    /**
     * What a set page's primary button does in place of Install: null outside a battle's picker, where the page
     * installs as it always has. In the picker it is Choose this map, starting on the chart the page has selected,
     * with that chart's name on its second line; it is off while the page knows no chart of the set.
     *
     * @param card   what the page was opened with, or null
     * @param detail the loaded page, or null while it loads or offline
     * @param chart  the page's selected chart, an index into the charts it shows - the detail's while it is loaded
     */
    public PrimaryAction primaryAction(long setId, CardModel card, WorkshopDtos.SetDetail detail, int chart) {
        if (picker == null) {
            return null;
        }
        String label = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.choose");
        BattleSetup.Pick pick = pagePick(setId, card, detail, chart);
        if (pick == null) {
            return new PrimaryAction(label, null, false, () -> {
            });
        }
        String sub = pick.difficulty().isEmpty() ? null : pick.difficulty();
        return new PrimaryAction(label, sub, true, () -> deliver(pick));
    }

    /** A set page's set as a pick: the loaded detail when there is one, the card it was opened with otherwise. */
    private BattleSetup.Pick pagePick(long setId, CardModel card, WorkshopDtos.SetDetail detail, int chart) {
        if (detail != null && detail.charts() != null && !detail.charts().isEmpty()) {
            List<String> keys = new ArrayList<>(detail.charts().size());
            List<String> names = new ArrayList<>(detail.charts().size());
            for (WorkshopDtos.ChartDetail entry : detail.charts()) {
                keys.add(entry.key() == null ? "" : entry.key());
                names.add(entry.name() == null ? "" : entry.name());
            }
            String cover = detail.covers() == null ? null : firstPresent(detail.covers().list(), detail.covers().list1x(),
                    detail.covers().card());
            return workshopPick(detail.id() > 0 ? detail.id() : setId, detail.revision(), detail.contentSha256(),
                    detail.title(), detail.artist(), cover, keys, names, chart);
        }
        return card == null ? null : pickOf(card, chart);
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** A card as the battle receives it, or null when it holds nothing the battle could vote for. */
    private BattleSetup.Pick pickOf(CardModel model, int chart) {
        if (picker == null || model == null) {
            return null;
        }
        BattleTracks.Entry entry = picker.tracks().entry(model.setId);
        if (entry != null) {
            return switch (entry.kind()) {
                case WORKSHOP -> workshopPick(entry.setId(), 0, entry.first().contentSha256(), entry.title(), "", null,
                        entry.chartKeys(), List.of(), 0);
                case SERVER -> BattleSetup.Pick.track(entry.trackId(), entry.title(), "", BattleSetup.Origin.SERVER);
                case PLAYERS -> BattleSetup.Pick.track(entry.trackId(), entry.title(), publishedBy(entry.first()),
                        BattleSetup.Origin.PLAYERS);
            };
        }
        return workshopPick(model.setId, model.revision, model.contentSha256, model.title, model.artist, model.coverUrl,
                model.chartKeys, model.chartNames, chart);
    }

    /** "Published by: name" for a player's track, or nothing when the server did not say who. */
    static String publishedBy(BattleTracks.Track row) {
        return row.publishedBy().isBlank() ? ""
                : WorkshopTr.INSTANCE.get("music_battle.l10n.client.093") + row.publishedBy();
    }

    /**
     * A Workshop set as a pick. When this battle lists the set, its charts are the ones the battle lists: the server
     * plays the copy it has, and offering a difficulty that copy lacks would turn the vote into a refusal. The badge
     * says the server's copy, the player's own installed one, or a Workshop download.
     */
    private BattleSetup.Pick workshopPick(long setId, int revision, String contentSha256, String title, String artist,
                                          String coverUrl, List<String> keys, List<String> names, int chart) {
        if (setId <= 0) {
            return null;
        }
        List<String> chartKeys = new ArrayList<>();
        List<String> chartNames = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != null && !keys.get(i).isEmpty()) {
                chartKeys.add(keys.get(i));
                chartNames.add(i < names.size() && names.get(i) != null ? names.get(i) : "");
            }
        }
        String wanted = chart >= 0 && chart < keys.size() ? keys.get(chart) : "";
        BattleTracks.Entry listed = picker.tracks().workshopSet(setId);
        if (listed != null) {
            List<String> playable = listed.chartKeys();
            List<String> listedKeys = new ArrayList<>(playable.size());
            List<String> listedNames = new ArrayList<>(playable.size());
            for (int i = 0; i < chartKeys.size(); i++) {
                if (playable.contains(chartKeys.get(i))) {
                    listedKeys.add(chartKeys.get(i));
                    listedNames.add(chartNames.get(i));
                }
            }
            for (String key : playable) {
                if (!listedKeys.contains(key)) {
                    listedKeys.add(key);
                    listedNames.add("");
                }
            }
            chartKeys = listedKeys;
            chartNames = listedNames;
        }
        if (chartKeys.isEmpty()) {
            return null;
        }
        int start = Math.max(0, chartKeys.indexOf(wanted));
        BattleSetup.Origin origin = listed == null && client.catalog().isInstalled(setId)
                ? BattleSetup.Origin.INSTALLED : BattleSetup.Origin.WORKSHOP;
        return BattleSetup.Pick.workshop(setId, revision, contentSha256, title, artist, coverUrl, chartKeys, chartNames,
                start, origin);
    }

    /** The choice goes to the battle, and the Workshop closes back to it - after the question if words would go. */
    private void deliver(BattleSetup.Pick pick) {
        Picker battle = picker;
        if (battle == null) {
            return;
        }
        leave(false, () -> {
            click();
            closeNow();
            battle.onChoose().accept(pick);
        });
    }

    public void openAccount() {
        if (overlays.peek() instanceof AccountView) {
            return;
        }
        pushOverlay(new AccountView(this, client));
    }

    /** The upload wizard with a local track preselected. */
    public void openUploadWithTrack(String trackId) {
        if (!client.session().signedIn()) {
            openAccount();
            return;
        }
        UploadWizardView wizard = new UploadWizardView(this, client, null);
        pushOverlay(wizard);
        wizard.preselect(trackId);
    }

    public void openUpload(Long setId) {
        if (!client.session().signedIn()) {
            openAccount();
            return;
        }
        pushOverlay(new UploadWizardView(this, client, setId));
    }

    /** The Network access switch turned access off: Browse shows its offline state with "Allow network". */
    public void networkTurnedOff() {
        browse.networkOff();
    }

    public void openConsent() {
        minecraft.setScreen(new WorkshopConsentScreen(this, () -> {
            minecraft.setScreen(this);
            client.onWorkshopOpened();
            browse.onShow();
        }, () -> minecraft.setScreen(this)));
    }

    /** Opens a dialog over the Workshop; the Workshop is the dialog's parent, so state survives. */
    public void showDialog(Screen dialog) {
        minecraft.setScreen(dialog);
    }

    public void showError(String code) {
        WorkshopToasts.problem(Component.translatable(WorkshopStrings.KEY + "error_title"), WorkshopText.error(code));
    }

    /**
     * Terms section 3.1: the server refuses a first comment, rating or report until the Workshop Terms and the
     * Community Rules are accepted. Opens the acceptance dialog and runs {@code retry} once it is given; returns false
     * when the refusal was about something else, so the caller shows its usual error.
     */
    public boolean handleRulesRefusal(String code, Runnable retry) {
        return handleRulesRefusal(code, retry, null);
    }

    /**
     * The same, for a caller that holds something the player typed: {@code onCancel} runs when the acceptance dialog is
     * closed without accepting, so a written report or a half-filled form comes back instead of disappearing.
     */
    public boolean handleRulesRefusal(String code, Runnable retry, Runnable onCancel) {
        if (!"rules_not_accepted".equals(code) && !"rules_outdated".equals(code)) {
            return false;
        }
        client.participationRefused();
        later(() -> Minecraft.getInstance().setScreen(WorkshopAcceptScreen.open(this, client, null, retry, onCancel)));
        return true;
    }

    /**
     * The same dialog without waiting for a refusal: the comment composer offers it as soon as {@code /me} says the
     * acceptance is still owed, and the account view offers it beside the rules row.
     */
    public void openRulesAcceptance(Runnable afterAccept) {
        later(() -> Minecraft.getInstance().setScreen(WorkshopAcceptScreen.open(this, client, null, afterAccept)));
    }

    public void showMessage(Component title, Component body) {
        WorkshopToasts.show(title, body);
    }

    public void click() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    /** Runs work off the render thread (search, set detail, comments). */
    public void run(Runnable work) {
        client.requests().execute(work);
    }

    /** Runs work on the render thread, or keeps it until this screen is the one being drawn again. */
    public void later(Runnable work) {
        Minecraft.getInstance().execute(() -> {
            if (detached || Minecraft.getInstance().screen == this || isDialogAbove()) {
                work.run();
                return;
            }
            // A vanilla screen can be on top as well - the link confirmation is one - and that is not a Workshop
            // dialog. An answer that arrived then used to be dropped on the spot, which left the set page and the
            // code sign-in frozen until the page was opened again.
            pending.add(work);
        });
    }

    /** Answers that came back while something else was on top of the Workshop. */
    private void pump() {
        for (Runnable work = pending.poll(); work != null; work = pending.poll()) {
            work.run();
        }
    }

    private boolean isDialogAbove() {
        Screen current = Minecraft.getInstance().screen;
        return current instanceof WorkshopUi && current != this;
    }

    public WorkshopClient client() {
        return client;
    }

    public ShellLayout.Tab tab() {
        return tab;
    }

    public ListingView listing() {
        return activeListing();
    }

    /** Called after an install, update or delete so the Installed tab and the cards follow. */
    public void onCatalogChanged() {
        browse.onCatalogChanged();
        installedTab.onCatalogChanged();
        serverTab.onCatalogChanged();
    }

    // ---------------------------------------------------------------- previews and files

    /** Fetches a preview clip once, caches a few, and plays it (previews stop with the screen). */
    public void fetchPreview(long setId, String url, long durationMs) {
        byte[] cached = previews.get(setId);
        if (cached != null) {
            PreviewPlayer.play(CardRenderer.previewKey(setId), cached, durationMs, client.previewVolume());
            return;
        }
        previewWanted = setId;
        previewFetching = setId;
        if (previewFailedSetId == setId) {
            previewFailedSetId = 0;
        }
        run(() -> {
            try {
                byte[] bytes = client.api().fetchBytes(url, PreviewPlayer.MAX_PREVIEW_BYTES, CancelToken.none());
                later(() -> {
                    if (previewFetching == setId) {
                        previewFetching = 0;
                    }
                    previews.put(setId, bytes);
                    while (previews.size() > PREVIEW_CACHE) {
                        previews.remove(previews.keySet().iterator().next());
                    }
                    if (previewWanted == setId) {
                        PreviewPlayer.play(CardRenderer.previewKey(setId), bytes, durationMs, client.previewVolume());
                    }
                });
            } catch (WorkshopApiException | RuntimeException exception) {
                // Said at once, not after the set page's 20-second wait: the page asks takePreviewFailure each frame.
                MusicBattleMod.LOGGER.debug("Workshop preview failed: {}", exception instanceof WorkshopApiException api
                        ? api.code() : exception.toString());
                later(() -> {
                    if (previewFetching == setId) {
                        previewFetching = 0;
                    }
                    if (previewWanted == setId) {
                        previewFailedSetId = setId;
                    }
                });
            }
        });
    }

    /** True while the clip for {@code setId} is being downloaded, before the player has it. */
    public boolean previewFetching(long setId) {
        return setId != 0 && previewFetching == setId;
    }

    /** Whether the clip download for {@code setId} failed since it was last asked for; consumed by the reader. */
    public boolean takePreviewFailure(long setId) {
        if (setId != 0 && previewFailedSetId == setId) {
            previewFailedSetId = 0;
            return true;
        }
        return false;
    }

    public void stopPreview() {
        previewWanted = 0;
        previewFetching = 0;
        PreviewPlayer.stop();
    }

    /**
     * "Import .blockymap…": the system file picker, then the usual confirm-and-install flow. A file the dialog hands
     * back that cannot be used says why, through the same message the drop path shows; a cancel says nothing.
     */
    public void importPackage() {
        WorkshopFiles.Choice choice = WorkshopFiles.pickPackage();
        if (choice.refusal() != null) {
            WorkshopDrops.pickRefused(choice.refusal());
        } else if (choice.file() != null) {
            WorkshopDrops.onFilesDropped(this, List.of(choice.file()));
        }
    }

    public void openInboxFolder() {
        Path inbox = client.inbox().directory();
        client.io().execute(() -> {
            try {
                client.inbox().ensureDirectory();
                Util.getPlatform().openPath(inbox);
            } catch (Exception exception) {
                MusicBattleMod.LOGGER.warn("Unable to open the Workshop inbox: {}", exception.getMessage());
            }
        });
    }

    /** Text style used by overlay headings; kept here so every view agrees. */
    public static TextStyle headingStyle() {
        return TextStyle.STRONG;
    }
    // ---------------------------------------------------------------- a DJ's pick on its way to the server

    /** What the DJ chose and where the news about it goes; {@code listener} is set while the set downloads here. */
    private record DjCourier(BlockPos panelPos, BattleSetup.Pick pick, BiConsumer<Component, Boolean> status,
                             WorkshopDownloader.Listener listener) {
    }

    /**
     * Brings a Workshop set the DJ panel does not list yet to the server, by the doors a battle's map already uses: the
     * Workshop download when this client lacks the set, then "Add to server" once the server has said this DJ may
     * add it. The server adds the beatmap to the booth's library itself when the set is installed, so nothing here
     * waits for that. {@code status} hears each step in words the panel's library line can show, and whether it is a
     * problem.
     */
    public static void bringToDj(BlockPos panelPos, BattleSetup.Pick pick, BiConsumer<Component, Boolean> status) {
        if (pick == null || pick.setId() <= 0 || pick.chartKey().isEmpty()) {
            return;
        }
        dropDjCourier();
        WorkshopClient client = WorkshopClient.get();
        WorkshopCatalog.InstalledSet set = client.catalog().bySetId(pick.setId()).orElse(null);
        if (set != null || !(ServerWorkshopClient.canManage() || ServerWorkshopClient.allowPlayerMaps())) {
            // Someone who may not add maps, on a server that takes no maps from players either, is answered by the
            // server in its own words, and downloading a set first only to hear that would waste the wait.
            DjCourier courier = new DjCourier(panelPos, pick, status, null);
            djCourier = courier;
            askServer(courier);
            return;
        }
        long setId = pick.setId();
        WorkshopDownloader downloader = client.downloader();
        WorkshopDownloader.Listener listener = new WorkshopDownloader.Listener() {
            @Override
            public void statusChanged(long changed, WorkshopDownloader.Status state) {
                if (changed != setId || state == null || state.state() == WorkshopDownloader.State.QUEUED
                        || state.state() == WorkshopDownloader.State.DOWNLOADING
                        || state.state() == WorkshopDownloader.State.INSTALLING) {
                    return;
                }
                Minecraft.getInstance().execute(() -> djDownloadEnded(this));
            }
        };
        DjCourier courier = new DjCourier(panelPos, pick, status, listener);
        djCourier = courier;
        status.accept(Component.translatable("screen.music_battle.dj_playlist.workshop.downloading", pick.title()), false);
        downloader.addListener(listener);
        downloader.enqueue(setId);
        if (!downloader.active(setId)) {
            // The job ended before the listener could hear it; read where it stands after this press is done.
            Minecraft.getInstance().tell(() -> djDownloadEnded(listener));
        }
    }

    /**
     * The server lets this DJ add the set: "Add to server" starts, and the beatmap joins the booth's library when it
     * lands.
     */
    public static void djUploadApproved(long setId) {
        DjCourier courier = djCourier;
        if (courier == null || courier.pick().setId() != setId) {
            return;
        }
        courier.status().accept(Component.translatable("screen.music_battle.dj_playlist.workshop.sending",
                courier.pick().title()), false);
        WorkshopScreen lent = new WorkshopScreen(null);
        lent.detached = true;
        ServerWorkshopClient.addToServer(lent, setId);
    }

    /** The server's last word on a DJ pick - in the booth's library, or refused - so nothing more is owed for it. */
    public static void djAnswered(long setId) {
        DjCourier courier = djCourier;
        if (courier != null && courier.pick().setId() == setId) {
            dropDjCourier();
        }
    }

    private static void djDownloadEnded(WorkshopDownloader.Listener listener) {
        DjCourier courier = djCourier;
        if (courier == null || courier.listener() != listener) {
            return;
        }
        WorkshopClient client = WorkshopClient.get();
        client.downloader().removeListener(listener);
        if (client.downloader().active(courier.pick().setId())) {
            client.downloader().addListener(listener);
            return;
        }
        DjCourier waiting = new DjCourier(courier.panelPos(), courier.pick(), courier.status(), null);
        djCourier = waiting;
        if (client.catalog().bySetId(courier.pick().setId()).isEmpty()) {
            WorkshopDownloader.Status state = client.downloader().status(courier.pick().setId());
            String code = state.failureCode() == null || state.failureCode().isEmpty() ? "install_failed"
                    : state.failureCode();
            Component reason = WorkshopText.error(code);
            WorkshopToasts.problem(Component.translatable(WorkshopStrings.KEY + "error_title"), reason);
            waiting.status().accept(reason, true);
            djCourier = null;
            return;
        }
        askServer(waiting);
    }

    /**
     * Names the copy this client can actually hand over when it has one, as a battle's vote does: the card may show a
     * newer revision than the one installed, and the server waits for exactly the content it was told about.
     */
    private static void askServer(DjCourier courier) {
        BattleSetup.Pick pick = courier.pick();
        WorkshopCatalog.InstalledSet set = WorkshopClient.get().catalog().bySetId(pick.setId()).orElse(null);
        PacketDistributor.sendToServer(new DjWorkshopAddPayload(courier.panelPos(), pick.setId(),
                set != null ? set.revision() : pick.revision(),
                set != null ? set.contentSha256() : pick.contentSha256(), pick.chartKey()));
    }

    private static void dropDjCourier() {
        DjCourier courier = djCourier;
        djCourier = null;
        if (courier != null && courier.listener() != null) {
            WorkshopClient.get().downloader().removeListener(courier.listener());
        }
    }
}
