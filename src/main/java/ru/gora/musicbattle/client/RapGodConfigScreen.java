package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.FancyEditBox;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.SkinHead;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.NpcSkinPng;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.network.ConfigureRapGodPayload;
import ru.gora.musicbattle.network.OpenRapGodConfigPayload;
import ru.gora.musicbattle.network.RapGodSkinUploadPayload;

/**
 * The test NPC's settings: its name, its skin - its own outfit, a player's by nickname, or a PNG from a file - its
 * arms, and how it behaves in the world.
 *
 * <p>It used to be the last screen left from before the Workshop had a look: a square teal slab, the bitmap font and
 * six "Setting: ON" buttons. It is now a Workshop dialog built from the toolkit, and the NPC stands in it, drawn live
 * in whatever skin and arms are chosen and turning towards the pointer, so a skin can be judged before anyone else
 * sees it. Where the parts go is {@link RapGodConfigLayout}, which the npc-config-layout check measures.
 *
 * <p>A skin from a file is read here, checked against {@link NpcSkinPng}, and re-encoded through the game's own PNG
 * writer, which drops whatever an editor stored beside the picture. Nothing leaves this screen until Save; the server
 * then checks the file again, keeps it with the NPC and hands it to everyone who sees the NPC.
 */
public final class RapGodConfigScreen extends UiLayer {
    private enum SkinSource {
        DEFAULT, NICKNAME, FILE
    }

    /** A PNG chosen in this screen, already in the form the server will keep. */
    private record PendingFile(String fileName, byte[] png, String sha256, int width, int height) {
    }

    /** How long the nickname has to stay unchanged before the preview looks the player up. */
    private static final long NICKNAME_SETTLE_MS = 600L;
    /** Where the file picker opens: the folder of the last skin chosen in this session, else the home folder. */
    private static Path lastSkinFolder;

    private final OpenRapGodConfigPayload payload;
    /** Whether the NPC wore a skin from a file when the screen opened: leaving that mode takes it off. */
    private final boolean hadFileSkin;
    /**
     * That file's size as the file zone prints it, worked out once when the screen opens; empty if it does not check
     * out. The zone used to run the whole PNG check (checksums and a full unpack) every frame just to print this.
     */
    private final String currentFileSize;
    private RapGodConfigLayout.Frame frame;
    private FancyEditBox displayName;
    private FancyEditBox skinName;
    private FileZone fileZone;
    private Check pushableCheck;
    private String typedDisplayName;
    private String typedNickname;
    private SkinSource source;
    private boolean slim;
    private boolean stationary;
    private boolean invulnerable;
    private boolean glowing;
    private boolean randomVotes;
    private boolean pushableWhenStationary;
    private PendingFile pendingFile;
    /** The name of a file just turned down; the zone shows it in place of anything chosen before, until a new choice. */
    private String rejectedFile;
    private Component status = Component.empty();
    private int statusColour = Palette.TEXT_MUTE;
    private boolean fileProblem;
    private String previewNickname;
    private long nicknameEditedAt;
    /**
     * The hints of the file zone and the behaviour switches, drawn as the toolkit's card (UiTooltip) in place of vanilla's
     * purple frame and bitmap font, as the rest of this dialog is the toolkit's. A new holder comes with every build of
     * the widgets, so a resize does not leave the old widgets' tips in it.
     */
    private UiTooltip.Deferred tooltips = new UiTooltip.Deferred();

    public RapGodConfigScreen(OpenRapGodConfigPayload payload) {
        super(Component.translatable("screen.music_battle.npc.title"));
        this.payload = payload;
        TestRapGodMasterEntity npc = npc();
        hadFileSkin = npc != null && npc.hasSkinFile();
        byte[] currentPng = hadFileSkin ? npc.getSkinFilePng() : null;
        NpcSkinPng.Result current = currentPng == null ? null : NpcSkinPng.check(currentPng);
        currentFileSize = current != null && current.ok() ? current.width() + "×" + current.height() : "";
        typedDisplayName = payload.displayName();
        typedNickname = payload.skinName();
        previewNickname = typedNickname.trim();
        source = hadFileSkin ? SkinSource.FILE : typedNickname.isBlank() ? SkinSource.DEFAULT : SkinSource.NICKNAME;
        stationary = payload.stationary();
        invulnerable = payload.invulnerable();
        glowing = payload.glowing();
        randomVotes = payload.randomVotes();
        slim = payload.slimSkin();
        pushableWhenStationary = payload.pushableWhenStationary();
    }

    @Override
    protected void layout() {
        // Minecraft runs init() again on every resize; what was typed lives in fields, not in the old widgets.
        if (displayName != null) {
            typedDisplayName = displayName.getValue();
        }
        if (skinName != null) {
            typedNickname = skinName.getValue();
        }
        Component cancel = Component.translatable("music_battle.l10n.client.044");
        Component save = Component.translatable("music_battle.l10n.client.100");
        frame = RapGodConfigLayout.frame(this.width, this.height, TextStyles.measurer(), cancel.getString(), save.getString());
        tooltips = new UiTooltip.Deferred();

        displayName = addRenderableWidget(field(frame.nameField(), Component.translatable("screen.music_battle.npc.name")));
        displayName.setMaxLength(48);
        // EditBox draws a hint in the colour of typed text unless the hint brings its own, and a hint in full white
        // reads as a value already filled in.
        displayName.setHint(Component.literal(TestRapGodMasterEntity.NPC_NAME).withColor(Palette.TEXT_MUTE));
        displayName.setValue(typedDisplayName);

        List<Rect> sources = frame.skinSources();
        addRenderableWidget(new Segment(sources.get(0), Component.translatable("screen.music_battle.npc.skin.default"),
                () -> source == SkinSource.DEFAULT, () -> choose(SkinSource.DEFAULT)));
        addRenderableWidget(new Segment(sources.get(1), Component.translatable("screen.music_battle.npc.skin.nickname"),
                () -> source == SkinSource.NICKNAME, () -> choose(SkinSource.NICKNAME)));
        addRenderableWidget(new Segment(sources.get(2), Component.translatable("screen.music_battle.npc.skin.file"),
                () -> source == SkinSource.FILE, () -> choose(SkinSource.FILE)));

        skinName = addRenderableWidget(field(frame.skinRow(), Component.translatable("screen.music_battle.npc.skin.nickname")));
        skinName.setMaxLength(16);
        // Only what a Minecraft account name can hold; the server refuses anything else, so it is never typed.
        skinName.setFilter(value -> value.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || c == '_')));
        skinName.setHint(Component.translatable("screen.music_battle.npc.skin.nickname.hint").withColor(Palette.TEXT_MUTE));
        skinName.setValue(typedNickname);
        // Set after the value, so restoring the typed name on a resize is not taken for typing.
        skinName.setResponder(value -> {
            nicknameEditedAt = Util.getMillis();
            if (!fileProblem) {
                setStatus(Component.empty(), Palette.TEXT_MUTE);
            }
        });

        fileZone = addRenderableWidget(new FileZone(frame.skinRow()));
        fileZone.setTooltip(tooltips.create(Component.translatable("screen.music_battle.npc.skin.file.tooltip")));

        List<Rect> arms = frame.armTypes();
        addRenderableWidget(new Segment(arms.get(0), Component.translatable("screen.music_battle.npc.arms.classic"),
                () -> !slim, () -> slim = false));
        addRenderableWidget(new Segment(arms.get(1), Component.translatable("screen.music_battle.npc.arms.slim"),
                () -> slim, () -> slim = true));

        List<Rect> checks = frame.checks();
        addRenderableWidget(new Check(checks.get(0), Component.translatable("screen.music_battle.npc.stationary"),
                () -> stationary, value -> stationary = value));
        pushableCheck = addRenderableWidget(new Check(checks.get(1), Component.translatable("screen.music_battle.npc.pushable"),
                () -> pushableWhenStationary, value -> pushableWhenStationary = value));
        pushableCheck.setTooltip(tooltips.create(Component.translatable("screen.music_battle.npc.pushable.tooltip")));
        Check invulnerableCheck = addRenderableWidget(new Check(checks.get(2),
                Component.translatable("screen.music_battle.npc.invulnerable"), () -> invulnerable, value -> invulnerable = value));
        invulnerableCheck.setTooltip(tooltips.create(Component.translatable("screen.music_battle.npc.invulnerable.tooltip")));
        addRenderableWidget(new Check(checks.get(3), Component.translatable("screen.music_battle.npc.glowing"),
                () -> glowing, value -> glowing = value));
        Check randomVotesCheck = addRenderableWidget(new Check(checks.get(4),
                Component.translatable("screen.music_battle.npc.random_votes"), () -> randomVotes, value -> randomVotes = value));
        // The label alone cannot say that the NPC's modes add to the player's rather than replace them.
        randomVotesCheck.setTooltip(tooltips.create(Component.translatable("screen.music_battle.npc.random_votes.tooltip")));

        addRenderableWidget(button(frame.cancel(), cancel, ignored -> onClose()));
        addRenderableWidget(button(frame.save(), save, ignored -> save())).primary();
        refreshControls();
    }

    private FancyEditBox field(Rect rect, Component narration) {
        return new FancyEditBox(this.font, Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()),
                Math.round(rect.h()), narration);
    }

    private static FancyButton button(Rect rect, Component label, net.minecraft.client.gui.components.Button.OnPress action) {
        return FancyButton.create(label, action, Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()),
                Math.round(rect.h()));
    }

    /** Shows the control of the chosen skin source in the shared row, and lets "pushable" be set only when it applies. */
    private void refreshControls() {
        if (skinName == null) {
            return;
        }
        skinName.visible = source == SkinSource.NICKNAME;
        fileZone.visible = source == SkinSource.FILE;
        if (!skinName.visible && getFocused() == skinName) {
            setFocused(null);
        }
        // Pushing is a setting of the standing-still NPC; with its AI on it is pushed like any other mob.
        pushableCheck.active = stationary;
    }

    private void choose(SkinSource chosen) {
        source = chosen;
        fileProblem = false;
        rejectedFile = null;
        setStatus(Component.empty(), Palette.TEXT_MUTE);
        refreshControls();
    }

    // ---------------------------------------------------------------- saving

    private void save() {
        String nickname = skinName.getValue().trim();
        if (source == SkinSource.NICKNAME && nickname.isEmpty()) {
            setStatus(Component.translatable("screen.music_battle.npc.skin.nickname.needed"), Palette.DANGER);
            return;
        }
        if (source == SkinSource.FILE && !showsUsableFile()) {
            fileProblem = true;
            // A file that was just turned down already says why; with nothing chosen at all, a file is asked for.
            if (rejectedFile == null) {
                setStatus(Component.translatable("screen.music_battle.npc.skin.file.needed"), Palette.DANGER);
            }
            return;
        }
        PacketDistributor.sendToServer(new ConfigureRapGodPayload(
                payload.entityId(),
                displayName.getValue().trim(),
                source == SkinSource.NICKNAME ? nickname : "",
                slim,
                pushableWhenStationary,
                stationary,
                invulnerable,
                glowing,
                randomVotes
        ));
        // The settings go first: a blank nickname there leaves the file skin alone, and this decides what it becomes.
        if (source == SkinSource.FILE && pendingFile != null) {
            PacketDistributor.sendToServer(new RapGodSkinUploadPayload(payload.entityId(), pendingFile.png()));
        } else if (source != SkinSource.FILE && hadFileSkin) {
            PacketDistributor.sendToServer(RapGodSkinUploadPayload.remove(payload.entityId()));
        }
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }


    // ---------------------------------------------------------------- skin files

    /** A PNG dropped anywhere on the window while this screen is open becomes the NPC's skin to be. */
    @Override
    public void onFilesDrop(List<Path> paths) {
        Path chosen = null;
        for (Path path : paths) {
            if (String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT).endsWith(".png")) {
                chosen = path;
                break;
            }
        }
        loadFile(chosen != null ? chosen : paths.isEmpty() ? null : paths.get(0));
    }

    /**
     * The system file picker, filtered to PNG files. LWJGL's tinyfd ships with the game; it opens on the render thread
     * (macOS allows a native dialog nowhere else) and the game waits while it is open.
     */
    private void chooseFile() {
        String picked;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.png"));
            filters.flip();
            Path start = lastSkinFolder != null ? lastSkinFolder : Path.of(System.getProperty("user.home", "."));
            picked = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.music_battle.npc.skin.file.pick").getString(),
                    start.toAbsolutePath() + File.separator, filters,
                    Component.translatable("screen.music_battle.npc.skin.file.filter").getString(), false);
        } catch (RuntimeException | UnsatisfiedLinkError unavailable) {
            MusicBattleMod.LOGGER.warn("Skin file dialog unavailable: {}", unavailable.toString());
            fileFailed(Component.translatable("screen.music_battle.npc.skin.file.picker_unavailable"));
            return;
        }
        if (picked == null || picked.isBlank()) {
            return;
        }
        try {
            loadFile(Path.of(picked.trim()));
        } catch (InvalidPathException notAPath) {
            reject(picked.trim().replaceAll(".*[/\\\\]", ""),
                    Component.translatable("screen.music_battle.npc.skin.file.unreadable"));
        }
    }

    private void loadFile(Path path) {
        if (path == null) {
            return;
        }
        Path file = path.toAbsolutePath().normalize();
        String name = String.valueOf(file.getFileName());
        byte[] original;
        try {
            if (!Files.isRegularFile(file)) {
                reject(name, Component.translatable("screen.music_battle.npc.skin.file.unreadable"));
                return;
            }
            // Read nothing larger than any skin could be; a picture this size is not one.
            if (Files.size(file) > NpcSkinPng.MAX_SOURCE_BYTES) {
                reject(name, problem(new NpcSkinPng.Result(NpcSkinPng.Problem.TOO_LARGE, 0, 0)));
                return;
            }
            original = Files.readAllBytes(file);
        } catch (IOException | SecurityException unreadable) {
            reject(name, Component.translatable("screen.music_battle.npc.skin.file.unreadable"));
            return;
        }
        if (file.getParent() != null) {
            lastSkinFolder = file.getParent();
        }
        NpcSkinPng.Result shape = NpcSkinPng.inspect(original, NpcSkinPng.MAX_SOURCE_BYTES);
        if (!shape.ok()) {
            reject(name, problem(shape));
            return;
        }
        byte[] png;
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(original))) {
            if (image.getWidth() != shape.width() || image.getHeight() != shape.height()) {
                reject(name, Component.translatable("screen.music_battle.npc.skin.file.damaged"));
                return;
            }
            png = image.asByteArray();
        } catch (IOException | RuntimeException undecodable) {
            reject(name, Component.translatable("screen.music_battle.npc.skin.file.damaged"));
            return;
        }
        NpcSkinPng.Result check = NpcSkinPng.check(png);
        if (!check.ok()) {
            reject(name, problem(check));
            return;
        }
        String sha256 = NpcSkinPng.sha256(png);
        // The preview wears the very texture the NPC will wear once the server has the file: same bytes, same hash.
        if (TestRapGodMasterRenderer.fileSkinTexture(sha256, png) == null) {
            reject(name, Component.translatable("screen.music_battle.npc.skin.file.damaged"));
            return;
        }
        pendingFile = new PendingFile(name, png, sha256, check.width(), check.height());
        choose(SkinSource.FILE);
    }

    /**
     * Why a file cannot be the skin. "Too large" names no limit: the screen reads a file of up to a megabyte and
     * re-encodes it, so a number would only ever be the reading limit, which says nothing about what a skin weighs.
     */
    private static Component problem(NpcSkinPng.Result result) {
        return switch (result.problem()) {
            case TOO_LARGE -> Component.translatable("screen.music_battle.npc.skin.file.too_large");
            case WRONG_SIZE -> Component.translatable("screen.music_battle.npc.skin.file.wrong_size",
                    String.valueOf(result.width()), String.valueOf(result.height()));
            case CORRUPT -> Component.translatable("screen.music_battle.npc.skin.file.damaged");
            case EMPTY, NOT_PNG, NONE -> Component.translatable("screen.music_battle.npc.skin.file.not_png");
        };
    }

    /**
     * A file that cannot be a skin. It takes the place of whatever was chosen before it, in the zone and for Save, so
     * the zone and the reason under it always speak of the same file. The zone used to keep the earlier file in a red
     * frame above a reason that was about the new one, which read as if the good file were the broken one. A new
     * choice, or another skin source, clears it.
     */
    private void reject(String fileName, Component reason) {
        pendingFile = null;
        rejectedFile = fileName;
        source = SkinSource.FILE;
        refreshControls();
        fileFailed(reason);
    }

    private void fileFailed(Component reason) {
        fileProblem = true;
        setStatus(reason, Palette.DANGER);
    }

    /** Whether the file zone shows a file that Save would keep: one chosen here, or the one the NPC already wears. */
    private boolean showsUsableFile() {
        return rejectedFile == null && (pendingFile != null || hadFileSkin);
    }

    private void setStatus(Component text, int colour) {
        status = text;
        statusColour = colour;
    }

    // ---------------------------------------------------------------- drawing

    /**
     * A dim of the page colour over the world rather than the toolkit's opaque page, as on the speaker's settings: the
     * NPC this edits stands right behind the dialog, and seeing it is part of knowing which one is open.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, Palette.withAlpha(Palette.BG_0, 0.82F));
    }

    /**
     * After every widget, the card of the hint a widget asked for. It goes up in the foreground layer: the NPC in the card
     * is drawn with depth some fifty units up, as the inventory draws a player, and it would show through a card left at
     * the widgets' level; vanilla's tooltip was drawn four hundred up for the same reason.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
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

    @Override
    protected void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        RapGodConfigLayout.Frame f = frame;
        if (f == null) {
            return;
        }
        Rect panel = f.panel();
        RoundedRect.shadow(g, panel.x(), panel.y() + 2F, panel.w(), panel.h(), Palette.RADIUS_LG, 12F, Palette.SHADOW_LIFTED);
        RoundedRect.fill(g, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(g, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, 1F, Palette.LINE);

        WorkshopDraw.icon(g, f.icon(), UiIcon.USER_ROUND, Palette.ACCENT);
        line(g, this.title.getString(), TextStyle.TITLE, f.title(), Palette.TEXT_1);
        TestRapGodMasterEntity npc = npc();
        if (npc != null && npc.getCustomName() != null) {
            lineRight(g, npc.getCustomName().getString(), TextStyle.CAPTION, f.headerNote(), Palette.TEXT_MUTE);
        }
        RoundedRect.fill(g, panel.x() + f.pad(), f.header().bottom() - 1F, panel.w() - f.pad() * 2F, 1F, 0F, Palette.LINE);

        sectionLabel(g, "screen.music_battle.npc.name", f.nameLabel());
        sectionLabel(g, "screen.music_battle.npc.skin", f.skinLabel());
        sectionLabel(g, "screen.music_battle.npc.arms", f.armsLabel());
        sectionLabel(g, "screen.music_battle.npc.behaviour", f.behaviourLabel());
        if (source == SkinSource.DEFAULT) {
            Rect row = f.skinRow();
            TextStyle style = f.controlStyle();
            line(g, Component.translatable("screen.music_battle.npc.skin.default.note").getString(), style,
                    new Rect(row.x() + 2F, TextStyles.centeredCapY(style, row.y(), row.h()), row.w() - 4F, row.h()),
                    Palette.TEXT_MUTE);
        }

        Rect footer = f.footer();
        RoundedRect.fill(g, panel.x() + f.pad(), footer.y(), panel.w() - f.pad() * 2F, 1F, 0F, Palette.LINE);
        Component shown = footerStatus();
        if (!shown.getString().isEmpty()) {
            Rect room = f.status();
            List<String> lines = TextFit.wrap(TextStyles.measurer(), shown.getString(), TextStyle.CAPTION, room.w(),
                    RapGodConfigLayout.STATUS_LINES);
            float y = RapGodConfigLayout.statusTop(room, lines.size());
            int colour = shown == status ? statusColour : Palette.TEXT_MUTE;
            for (String text : lines) {
                line(g, text, TextStyle.CAPTION, new Rect(room.x(), y, room.w(), room.h()), colour);
                y += RapGodConfigLayout.statusLineAdvance();
            }
        }

        drawPreview(g, f, npc, mouseX, mouseY);
    }

    /** An error or a note this screen set, or else what the chosen skin source needs to be told. */
    private Component footerStatus() {
        if (!status.getString().isEmpty()) {
            return status;
        }
        if (source == SkinSource.FILE) {
            return showsUsableFile()
                    ? Component.translatable("screen.music_battle.npc.skin.file.shared")
                    : Component.translatable("screen.music_battle.npc.skin.file.hint");
        }
        return Component.empty();
    }

    /**
     * The NPC in the card, in the skin and arms chosen right now. The figure is drawn immediately rather than queued,
     * so the card under it is flushed first.
     */
    private void drawPreview(GuiGraphics g, RapGodConfigLayout.Frame f, TestRapGodMasterEntity npc, double mouseX, double mouseY) {
        Rect box = f.preview();
        if (box.empty()) {
            return;
        }
        WorkshopDraw.panel(g, box, Palette.RADIUS_MD, Palette.BG_2);
        float scale = Math.max(8F, Math.min(box.h() * 0.38F, box.w() * 0.62F));
        float feetY = box.centerY() + (0.9F + 0.0625F) * scale;
        // A soft violet light behind the figure and its shadow on the floor, so it stands in the card, not on it.
        float glow = Math.min(box.w(), box.h()) * 0.34F;
        RoundedRect.shadow(g, box.centerX() - glow, box.centerY() - glow * 1.15F, glow * 2F, glow * 2F, glow, glow,
                Palette.withAlpha(Palette.ACCENT, 0.10F));
        float floor = scale * 0.62F;
        RoundedRect.shadow(g, box.centerX() - floor * 0.5F, feetY - 2F, floor, 4F, 2F, 3F, Palette.withAlpha(0xFF000000, 0.55F));
        if (npc == null) {
            WorkshopDraw.icon(g, new Rect(box.centerX() - 10F, box.centerY() - 10F, 20F, 20F), UiIcon.USER_ROUND,
                    Palette.withAlpha(Palette.TEXT_MUTE, 0.7F));
            return;
        }
        g.flush();
        TestRapGodMasterRenderer.renderPreview(g, npc, previewTexture(npc), slim,
                Math.round(box.x()) + 1, Math.round(box.y()) + 1, Math.round(box.right()) - 1, Math.round(box.bottom()) - 1,
                Math.round(scale), (float) mouseX, (float) mouseY);
    }

    private ResourceLocation previewTexture(TestRapGodMasterEntity npc) {
        return switch (source) {
            case DEFAULT -> TestRapGodMasterRenderer.DEFAULT_TEXTURE;
            case FILE -> {
                ResourceLocation texture = pendingFile != null
                        ? TestRapGodMasterRenderer.fileSkinTexture(pendingFile.sha256(), pendingFile.png())
                        : npc.hasSkinFile() ? TestRapGodMasterRenderer.fileSkinTexture(npc.getSkinFileHash(), npc.getSkinFilePng())
                        : null;
                yield texture == null ? TestRapGodMasterRenderer.DEFAULT_TEXTURE : texture;
            }
            case NICKNAME -> nicknameTexture(npc);
        };
    }

    /**
     * The skin of the nickname in the field, looked up once the typing has paused; the NPC's current skin when the
     * name is the one it already wears.
     */
    private ResourceLocation nicknameTexture(TestRapGodMasterEntity npc) {
        String typed = skinName.getValue().trim();
        if (typed.isEmpty()) {
            return TestRapGodMasterRenderer.DEFAULT_TEXTURE;
        }
        if (!typed.equals(previewNickname) && Util.getMillis() - nicknameEditedAt >= NICKNAME_SETTLE_MS) {
            previewNickname = typed;
        }
        if (previewNickname.equalsIgnoreCase(npc.getSkinName()) && npc.getSkinUuid().isPresent()) {
            return TestRapGodMasterRenderer.profileSkinTexture(npc.getSkinUuid().get());
        }
        if (!SkinHead.isValidName(previewNickname)) {
            return TestRapGodMasterRenderer.DEFAULT_TEXTURE;
        }
        return SkinHead.skin(new SkinHead.Author(null, previewNickname, null, null), true).texture();
    }

    private TestRapGodMasterEntity npc() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.getEntity(payload.entityId()) instanceof TestRapGodMasterEntity npc
                ? npc : null;
    }

    private static void sectionLabel(GuiGraphics g, String key, Rect box) {
        line(g, Component.translatable(key).getString(), TextStyle.LABEL, box, Palette.TEXT_3);
    }

    private static void line(GuiGraphics g, String text, TextStyle style, Rect box, int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), text, style, box.w());
        if (!fitted.isEmpty()) {
            TextStyles.queue(g, TextStyles.text(fitted, style).getVisualOrderText(), box.x(), box.y(), colour);
        }
    }

    private static void lineRight(GuiGraphics g, String text, TextStyle style, Rect box, int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), text, style, box.w());
        if (!fitted.isEmpty()) {
            TextStyles.queueRight(g, TextStyles.text(fitted, style).getVisualOrderText(), box.right(), box.y(), colour);
        }
    }

    // ---------------------------------------------------------------- controls

    /**
     * One option of a row of choices, drawn like the battle setup's options: the primary fill when chosen, a quiet
     * slab otherwise. A vanilla widget underneath, so Tab and Enter reach it like any button.
     */
    private final class Segment extends AbstractButton {
        private final BooleanSupplier chosen;
        private final Runnable action;

        Segment(Rect rect, Component label, BooleanSupplier chosen, Runnable action) {
            super(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), label);
            this.chosen = chosen;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean selected = chosen.getAsBoolean();
            boolean hot = isHoveredOrFocused() && this.active;
            if (isHovered() && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            WorkshopDraw.button(g, rect, selected ? WorkshopDraw.ButtonStyle.PRIMARY : WorkshopDraw.ButtonStyle.SECONDARY,
                    hot, this.active);
            TextStyle style = frame.controlStyle();
            int colour = !this.active ? Palette.TEXT_MUTE : selected || hot ? Palette.TEXT_1 : Palette.TEXT_3;
            String label = TextFit.ellipsize(TextStyles.measurer(), getMessage().getString(), style, Math.max(4F, rect.w() - 8F));
            TextStyles.queueCentered(g, TextStyles.text(label, style).getVisualOrderText(), rect.centerX(),
                    TextStyles.centeredCapY(style, rect.y(), rect.h()), colour);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** A setting that is on or off: the toolkit's checkbox and its label, the whole row clickable. */
    private final class Check extends AbstractButton {
        private final BooleanSupplier value;
        private final Consumer<Boolean> setter;

        Check(Rect rect, Component label, BooleanSupplier value, Consumer<Boolean> setter) {
            super(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), label);
            this.value = value;
            this.setter = setter;
        }

        @Override
        public void onPress() {
            setter.accept(!value.getAsBoolean());
            refreshControls();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Rect row = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean on = value.getAsBoolean();
            boolean hot = isHoveredOrFocused() && this.active;
            if (isHovered() && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            if (hot) {
                WorkshopDraw.panel(g, row, Palette.RADIUS_SM, Palette.withAlpha(Palette.BG_3, 0.75F));
            }
            WorkshopDraw.check(g, RapGodConfigLayout.checkBox(row, frame.compact()), on, false, this.active);
            Rect text = RapGodConfigLayout.checkText(row, frame.compact());
            TextStyle style = frame.controlStyle();
            int colour = !this.active ? Palette.TEXT_MUTE : on || hot ? Palette.TEXT_1 : Palette.TEXT_2;
            line(g, getMessage().getString(), style,
                    new Rect(text.x(), TextStyles.centeredCapY(style, text.y(), text.h()), text.w(), text.h()), colour);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * Where a skin file goes in: the chosen file and its size, or the invitation to choose one. Pressing it opens the
     * system file picker; a PNG dropped on the window lands here too.
     */
    private final class FileZone extends AbstractButton {
        FileZone(Rect rect) {
            super(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()),
                    Component.translatable("screen.music_battle.npc.skin.file.choose"));
        }

        @Override
        public void onPress() {
            chooseFile();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Rect zone = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hot = isHoveredOrFocused() && this.active;
            if (isHovered() && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            WorkshopDraw.panel(g, zone, Palette.RADIUS_SM, hot ? Palette.BG_3 : Palette.BG_2);
            // Red while the zone holds nothing Save would keep: a file just turned down, or no file when one is needed.
            int border = fileProblem && !showsUsableFile() ? Palette.DANGER : hot ? Palette.ACCENT : Palette.ACCENT_DEEP;
            WorkshopDraw.outline(g, zone, Palette.RADIUS_SM, 1F, border);

            TextStyle style = frame.controlStyle();
            float iconSize = Math.min(9F, zone.h() - 6F);
            Rect icon = new Rect(zone.x() + 5F, zone.centerY() - iconSize * 0.5F, iconSize, iconSize);
            if (rejectedFile != null) {
                WorkshopDraw.icon(g, icon, UiIcon.ALERT, Palette.DANGER);
            } else {
                WorkshopDraw.icon(g, icon, UiIcon.FOLDER_OPEN, hot ? Palette.TEXT_1 : Palette.ACCENT);
            }

            String name;
            String size = "";
            int nameColour = Palette.TEXT_1;
            if (rejectedFile != null) {
                name = rejectedFile;
            } else if (pendingFile != null) {
                name = pendingFile.fileName();
                size = pendingFile.width() + "×" + pendingFile.height();
            } else if (hadFileSkin) {
                name = Component.translatable("screen.music_battle.npc.skin.file.current").getString();
                size = currentFileSize;
            } else {
                name = getMessage().getString();
                nameColour = hot ? Palette.TEXT_1 : Palette.TEXT_2;
            }

            float textX = icon.right() + 5F;
            float right = zone.right() - 5F;
            if (!size.isEmpty()) {
                float pillWidth = TextFit.width(TextStyles.measurer(), size, TextStyle.PILL) + 8F;
                float pillHeight = Math.min(zone.h() - 4F, TextStyle.PILL.capHeight() + 6F);
                Rect pill = new Rect(right - pillWidth, zone.centerY() - pillHeight * 0.5F, pillWidth, pillHeight);
                WorkshopDraw.pill(g, pill, Palette.withAlpha(Palette.BG_4, 0.9F));
                TextStyles.queueCentered(g, TextStyles.text(size, TextStyle.PILL).getVisualOrderText(), pill.centerX(),
                        TextStyles.centeredCapY(TextStyle.PILL, pill.y(), pill.h()), Palette.TEXT_2);
                right = pill.x() - 5F;
            } else if (!showsUsableFile()) {
                // The drop hint takes the right-hand end when there is room for it beside the invitation.
                String hint = Component.translatable("screen.music_battle.npc.skin.file.drop").getString();
                float hintWidth = TextFit.width(TextStyles.measurer(), hint, TextStyle.CAPTION);
                float nameWidth = TextFit.width(TextStyles.measurer(), name, style);
                if (textX + nameWidth + 8F + hintWidth <= right) {
                    TextStyles.queueRight(g, TextStyles.text(hint, TextStyle.CAPTION).getVisualOrderText(), right,
                            TextStyles.centeredCapY(TextStyle.CAPTION, zone.y(), zone.h()), Palette.TEXT_MUTE);
                    right -= hintWidth + 8F;
                }
            }
            line(g, name, style, new Rect(textX, TextStyles.centeredCapY(style, zone.y(), zone.h()),
                    Math.max(0F, right - textX), zone.h()), nameColour);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
