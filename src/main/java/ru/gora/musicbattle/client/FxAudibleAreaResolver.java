package ru.gora.musicbattle.client;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;

/**
 * Works out, once per world pass, where the music behind the current FX can be heard, from what the client
 * world already shows: the battle participants and the speakers the server has switched on.
 *
 * <p>A battle is found through its participants, who stand locked at their arena anchors, so their midpoint is
 * the arena centre the server measures from. Without participants the effects belong to a DJ, and the area is
 * the installation whose speakers cover the listener; the DJ monitor shows the installation of the panel it
 * belongs to. Nothing found means nothing is known, and {@link FxAudibleArea#UNBOUNDED} keeps the old reach rather
 * than hiding an effect.</p>
 */
@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, value = Dist.CLIENT)
public final class FxAudibleAreaResolver {
    /** Speakers are looked for this many chunks around the anchor: 128 blocks, twice the largest radius. */
    private static final int SCAN_CHUNK_RADIUS = 8;
    /**
     * A listener walking out of a DJ's speakers keeps the effects for about 0.65 s while they fade, and can be a
     * few blocks outside every sphere by then. The installation must still be found for that stretch.
     */
    private static final double LISTENER_SLACK = 8.0D;

    private static long mainFrame;
    private static long nestedFrame;
    private static long resolvedMainFrame = -1L;
    private static long resolvedNestedFrame = -1L;
    private static FxAudibleArea mainArea = FxAudibleArea.UNBOUNDED;
    private static FxAudibleArea nestedArea = FxAudibleArea.UNBOUNDED;
    private static boolean mainWorldPass;
    /**
     * The player's view and the DJ monitor's camera usually stand in different chunks, and both resolve while the
     * monitor is open. One shared scan would be thrown away and redone for every pass of either.
     */
    private static final SpeakerScan MAIN_SCAN = new SpeakerScan();
    private static final SpeakerScan NESTED_SCAN = new SpeakerScan();
    /**
     * The player's view holds on to the DJ installation it was last in while the show's effects fade out. Past
     * LISTENER_SLACK beyond the speakers - a teleport, an ender pearl, flying fast - nothing is found any more, and the
     * area used to fall back to unbounded for the rest of the fade: mobs at the edge of the show flashed back to their
     * full glow and full light while everything else was fading.
     */
    private static final FxAudibleArea.Hold MAIN_INSTALLATION = new FxAudibleArea.Hold(FxAudibleArea.DJ_FADE_HOLD_TICKS);
    /** The world the held installation belongs to; weak for the same reason as SpeakerScan's. */
    private static WeakReference<ClientLevel> mainInstallationLevel = new WeakReference<>(null);
    /** Client ticks, counted like the ticks the effects fade by (they keep counting in the pause menu, as the fade does). */
    private static long clientTicks;

    private FxAudibleAreaResolver() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // Both the player's view and a DJ camera's nested pass start here; each gets its own answer.
            if (DjCameraPreviewRenderer.isRenderingPreview()) {
                nestedFrame++;
            } else {
                mainFrame++;
                mainWorldPass = true;
            }
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // Only GameRenderer dispatches this, after the player's world pass; GUI models come later.
            mainWorldPass = false;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTicks++;
    }

    /** The area for the world pass drawing right now; unbounded outside one. */
    public static FxAudibleArea current() {
        try {
            if (DjCameraPreviewRenderer.isRenderingPreview()) {
                if (resolvedNestedFrame != nestedFrame) {
                    resolvedNestedFrame = nestedFrame;
                    nestedArea = resolveCamera();
                }
                return nestedArea;
            }
            if (resolvedMainFrame != mainFrame) {
                resolvedMainFrame = mainFrame;
                mainArea = resolvePlayerView();
            }
            return mainArea;
        } catch (RuntimeException exception) {
            // A broken area must never take an effect down with it; the old reach is the safe answer.
            MusicBattleMod.LOGGER.warn("Unable to work out where the Blocky Music FX music is audible", exception);
            return FxAudibleArea.UNBOUNDED;
        }
    }

    /**
     * How strongly a highlighted entity glows for its place in the area: 1 inside, fading to 0 at the edge.
     * Models drawn into a screen, such as the chart editor's preview figure, are not in the world and keep 1.
     */
    public static float entityFade(int entityId) {
        if (!mainWorldPass && !DjCameraPreviewRenderer.isRenderingPreview()) {
            return 1.0F;
        }
        FxAudibleArea area = current();
        Minecraft minecraft = Minecraft.getInstance();
        if (area.unbounded() || minecraft.level == null) {
            return 1.0F;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity == null) {
            return 1.0F;
        }
        Vec3 position = entity.getPosition(minecraft.getTimer().getGameTimeDeltaPartialTick(false));
        return area.fadeAt(position.x, position.y + entity.getBbHeight() * 0.5D, position.z);
    }

    private static FxAudibleArea resolvePlayerView() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return FxAudibleArea.UNBOUNDED;
        }
        if (minecraft.screen instanceof TrackGameplayPreviewScreen) {
            // The chart editor previews a battle fought where the player stands.
            Vec3 player = minecraft.player.position();
            return FxAudibleArea.battle(player.x, player.y, player.z, MAIN_SCAN.speakers(level, player));
        }
        Vec3 arena = arenaCentre(level);
        if (arena != null) {
            return FxAudibleArea.battle(arena.x, arena.y, arena.z, MAIN_SCAN.speakers(level, arena));
        }
        // The server measures a listener from the feet, in BattleManager and DjPlaybackManager alike.
        Vec3 listener = minecraft.player.position();
        FxAudibleArea installation = FxAudibleArea.installation(listener.x, listener.y, listener.z,
                MAIN_SCAN.speakers(level, listener), LISTENER_SLACK);
        if (mainInstallationLevel.get() != level) {
            MAIN_INSTALLATION.forget();
            mainInstallationLevel = new WeakReference<>(level);
        }
        return MAIN_INSTALLATION.resolve(installation, clientTicks);
    }

    private static FxAudibleArea resolveCamera() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Camera camera = minecraft.getEntityRenderDispatcher().camera;
        if (level == null || camera == null) {
            return FxAudibleArea.UNBOUNDED;
        }
        Vec3 anchor = camera.getPosition();
        List<FxAudibleArea.Speaker> speakers = NESTED_SCAN.speakers(level, anchor);
        DjPanelScreen monitored = monitoredPanel(minecraft.screen);
        if (monitored != null) {
            // The monitor belongs to one panel, and its effects reach as far as that panel's speakers do, even when
            // the camera films them from outside, from above the dance floor say.
            for (FxAudibleArea.Speaker speaker : speakers) {
                if (speaker.controller() != FxAudibleArea.Speaker.NO_CONTROLLER
                        && monitored.isForPanel(BlockPos.of(speaker.controller()))) {
                    return FxAudibleArea.panel(speaker.controller(), speakers);
                }
            }
        }
        // Without its panel at hand (a thumbnail, or no speaker of it loaded) the installation is the one the camera
        // stands among.
        return FxAudibleArea.installation(anchor.x, anchor.y, anchor.z, speakers, LISTENER_SLACK);
    }

    /** The DJ panel whose monitor is on screen: the panel screen itself or one of the editors opened from it. */
    private static DjPanelScreen monitoredPanel(Screen screen) {
        if (screen instanceof DjPanelScreen panel) {
            return panel;
        }
        return screen instanceof DjPanelOwnedScreen owned ? owned.owningDjPanel() : null;
    }

    /**
     * The arena centre of the battle this client plays or watches, or null when there is none. The battle's
     * fighters are held at their anchors (that is why their walking is suppressed), so their average position
     * is the midpoint of the anchors that {@code BattleManager} measures the battle from. A solo battle has one
     * fighter, standing 0.05 blocks from the hidden partner, which is close enough to its own position.
     */
    private static Vec3 arenaCentre(ClientLevel level) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int found = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && ClientBattleController.suppressParticipantLocomotion(living)) {
                x += entity.getX();
                y += entity.getY();
                z += entity.getZ();
                if (++found == 2) break;
            }
        }
        return found == 0 ? null : new Vec3(x / found, y / found, z / found);
    }

    /** Loaded speakers around a point, read from the client's chunks at most once per tick and place. */
    private static final class SpeakerScan {
        /** Weak, so the scan of a world the player has left never keeps that whole world in memory. */
        private WeakReference<ClientLevel> level = new WeakReference<>(null);
        private long tick = Long.MIN_VALUE;
        private long chunk = Long.MIN_VALUE;
        private List<FxAudibleArea.Speaker> speakers = List.of();

        private List<FxAudibleArea.Speaker> speakers(ClientLevel current, Vec3 anchor) {
            int chunkX = SectionPos.blockToSectionCoord(anchor.x);
            int chunkZ = SectionPos.blockToSectionCoord(anchor.z);
            long anchorChunk = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
            long gameTime = current.getGameTime();
            if (level.get() == current && tick == gameTime && chunk == anchorChunk) {
                return speakers;
            }
            List<FxAudibleArea.Speaker> found = new ArrayList<>();
            for (int x = chunkX - SCAN_CHUNK_RADIUS; x <= chunkX + SCAN_CHUNK_RADIUS; x++) {
                for (int z = chunkZ - SCAN_CHUNK_RADIUS; z <= chunkZ + SCAN_CHUNK_RADIUS; z++) {
                    LevelChunk loaded = current.getChunkSource().getChunkNow(x, z);
                    if (loaded == null) continue;
                    for (BlockEntity blockEntity : loaded.getBlockEntities().values()) {
                        if (!(blockEntity instanceof SpeakerBlockEntity speaker)) continue;
                        BlockState state = speaker.getBlockState();
                        BlockPos pos = speaker.getBlockPos();
                        BlockPos controller = speaker.getDjControllerPos();
                        found.add(new FxAudibleArea.Speaker(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                                speaker.getSoundRadius(),
                                state.hasProperty(SpeakerBlock.ACTIVE) && state.getValue(SpeakerBlock.ACTIVE),
                                controller == null ? FxAudibleArea.Speaker.NO_CONTROLLER : controller.asLong()));
                    }
                }
            }
            level = new WeakReference<>(current);
            tick = gameTime;
            chunk = anchorChunk;
            speakers = List.copyOf(found);
            return speakers;
        }
    }
}
