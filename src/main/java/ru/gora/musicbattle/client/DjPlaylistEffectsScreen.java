package ru.gora.musicbattle.client;

import java.util.*;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.data.*;
import ru.gora.musicbattle.network.EditDjPlaylistEffectPayload;

/**
 * Edits the current queue entry, preserving chart timing and every FX parameter.
 *
 * <p>Drawn as the toolkit's card over a light wash, like the playlist it is opened from: rounded rows with a rail, the
 * Nunito faces, and a beatmap effect's start on the right of its row in the playlist's clock. It was a teal slab with
 * the bitmap font and an "@ 1.25 s" whose unit no language file could translate.
 */
final class DjPlaylistEffectsScreen extends Screen implements DjPanelOwnedScreen {
    /**
     * The light wash DjVisualFxScreen lays over the broadcast camera and, without one, over the world around the DJ:
     * the controls stay readable and the picture the effects are judged against stays in sight. Without a camera this
     * screen used to fill the window opaque, which hid the world for nothing: the running effect stack is silent while
     * it is open (ClientBattleController.authoringDjFx), so there is no second set of effects to hide.
     */
    private static final int WASH = Palette.withAlpha(Palette.BG_0, 0.28F);
    /** The card keeps the translucency the old panel had, so what is behind it still reads faintly through. */
    private static final int CARD = Palette.withAlpha(Palette.BG_1, 0.82F);
    private static final int ROW_STEP = 24;
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 48;
    private static final int LIST_LEFT = 16;
    private final DjPanelScreen owner;
    private final UUID entryId;
    private final List<TrackEffect> effects=new ArrayList<>();
    private DjPlaylistEntry.FxMode mode;
    private int scroll;
    private long revision;
    DjPlaylistEffectsScreen(DjPanelScreen owner,DjPlaylistEntry entry) {
        super(Component.translatable("screen.music_battle.dj_playlist.entry_fx"));
        this.owner=owner;entryId=entry.entryId();mode=entry.fxMode();effects.addAll(entry.effects());revision=owner.playlistRevision();
    }
    protected void init() {
        addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_visual_fx.add"),
                b->edit(effects.size()),16,height-30,120,20));
        addRenderableWidget(FancyButton.create(Component.translatable("gui.done"),b->onClose(),width-136,height-30,120,20));
    }
    public void tick() {
        owner.tickCameraPreviewRequest();
        DjPlaylistEntry current=owner.activePlaylistEntry();
        if(current==null||!current.entryId().equals(entryId)){onClose();return;}
        if(revision!=owner.playlistRevision()) {
            revision=owner.playlistRevision();effects.clear();effects.addAll(current.effects());mode=current.fxMode();
        }
    }
    public void renderBackground(GuiGraphics g,int x,int y,float p){}

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        // The camera when there is one, the world when there is not; the same wash over either (see WASH).
        owner.renderActiveCameraPreview(g, 0, 0, width, height, partial);
        g.fill(0, 0, width, height, WASH);
        RoundedRect.fill(g, 8, 6, width - 16, height - 12, Palette.RADIUS_MD, CARD);
        RoundedRect.stroke(g, 8, 6, width - 16, height - 12, Palette.RADIUS_MD, 1.0F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        float textRoom = width - 48.0F;
        TextStyles.queueCentered(g, TextStyles.ellipsize(TextStyles.styled(title, TextStyle.TITLE), textRoom),
                width / 2.0F, 14, Palette.TEXT_1);
        Component source = Component.translatable("screen.music_battle.dj_playlist.fx_" + mode.name().toLowerCase(Locale.ROOT));
        TextStyles.queueCentered(g, TextStyles.ellipsize(TextStyles.styled(source, TextStyle.LABEL), textRoom),
                width / 2.0F, 30, Palette.ACCENT);
        drawRows(g, mx, my);
        super.render(g, mx, my, partial);
    }

    /** The entry's effects as the playlist draws its queue: rounded rows with a rail, the start on the right. */
    private void drawRows(GuiGraphics g, int mx, int my) {
        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, effects.size() - rows));
        int right = width - LIST_LEFT;
        boolean scrolls = effects.size() > rows;
        // A list that scrolls keeps its rows clear of the scrollbar on the right.
        int rowRight = scrolls ? right - 6 : right;
        for (int r = 0; r < rows && scroll + r < effects.size(); r++) {
            int i = scroll + r;
            int y = LIST_TOP + r * ROW_STEP;
            TrackEffect fx = effects.get(i);
            boolean hovered = mx >= LIST_LEFT && mx < right && my >= y && my < y + ROW_HEIGHT;
            RoundedRect.fill(g, LIST_LEFT, y, rowRight - LIST_LEFT, ROW_HEIGHT, Palette.RADIUS_SM,
                    hovered ? Palette.BG_3 : Palette.BG_2);
            RoundedRect.pill(g, LIST_LEFT + 1.5F, y + 4, 2.5F, ROW_HEIGHT - 8,
                    hovered ? Palette.mix(Palette.LINE, Palette.ACCENT, 0.6F) : Palette.LINE);
            // Only a beatmap's own effects sit at a moment of the song; a DJ's run for as long as the entry plays.
            String start = mode == DjPlaylistEntry.FxMode.TRACK ? formatTicks(fx.tick()) : "";
            float startWidth = TextFit.width(TextStyles.measurer(), start, TextStyle.CAPTION);
            if (!start.isEmpty()) {
                TextStyles.queueRight(g, TextStyles.text(start, TextStyle.CAPTION).getVisualOrderText(), rowRight - 8,
                        TextStyles.centeredCapY(TextStyle.CAPTION, y, ROW_HEIGHT), Palette.TEXT_MUTE);
            }
            String name = (i + 1) + ".  " + Component.translatable("effect.music_battle." + fx.type().id()).getString();
            float nameX = LIST_LEFT + 10;
            float nameRoom = rowRight - 8 - (start.isEmpty() ? 0 : startWidth + Metrics.GAP + 4) - nameX;
            String fitted = TextFit.ellipsize(TextStyles.measurer(), name, TextStyle.BODY, nameRoom);
            if (!fitted.isEmpty()) {
                TextStyles.queue(g, TextStyles.text(fitted, TextStyle.BODY).getVisualOrderText(), nameX,
                        TextStyles.centeredCapY(TextStyle.BODY, y, ROW_HEIGHT), Palette.TEXT_1);
            }
        }
        if (scrolls) {
            WorkshopDraw.scrollbar(g, new Rect(LIST_LEFT, LIST_TOP, right - LIST_LEFT, rows * ROW_STEP - 2),
                    scroll * ROW_STEP, effects.size() * ROW_STEP, false);
        }
        if (effects.isEmpty()) {
            float y = LIST_TOP + 16;
            for (String line : TextFit.wrap(TextStyles.measurer(),
                    Component.translatable("screen.music_battle.dj_playlist.no_entry_fx").getString(), TextStyle.BODY,
                    width - 48.0F, 2)) {
                TextStyles.queueCentered(g, TextStyles.text(line, TextStyle.BODY).getVisualOrderText(), width / 2.0F, y,
                        Palette.TEXT_2);
                y += Metrics.lineHeight(TextStyle.BODY);
            }
        }
    }

    private int visibleRows() {
        return Math.max(1, (height - 86) / ROW_STEP);
    }

    /** The playlist's clock (DjPlaylistScreen's cue), so a start reads the same on both screens and in any language. */
    private static String formatTicks(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%02d:%02d.%02d", seconds / 60, seconds % 60,
                Math.round((Math.max(0, ticks) % 20) * 5.0F));
    }
    public boolean mouseClicked(double x,double y,int button) {
        int rows=Math.max(1,(height-86)/24);
        if(button==0&&x>=16&&x<width-16&&y>=48&&y<48+rows*24){int i=scroll+(int)(y-48)/24;if(i<effects.size()){edit(i);return true;}}
        return super.mouseClicked(x,y,button);
    }
    public boolean mouseScrolled(double x,double y,double sx,double sy){scroll=Mth.clamp(scroll-(int)Math.signum(sy),0,Math.max(0,effects.size()-1));return true;}
    private void edit(int index) {
        if(minecraft==null||index>=TrackEffect.MAX_EFFECTS)return;
        TrackEffect original=index<effects.size()?effects.get(index):null;
        TrackEffect seed=original==null?DjVisualEffectConfig.of(TrackEffect.Type.FLASH).effect():original;
        TrackEffectEditHost host=new TrackEffectEditHost() {
            public Screen parentScreen(){return DjPlaylistEffectsScreen.this;}
            public Path effectPreviewAudioPath(){return null;}
            public boolean managesPreviewAudio(){return false;}
            public boolean supportsLivePreview(){return true;}
            public boolean showBrushActions(){return false;}
            public boolean showTimelineControls(){return mode==DjPlaylistEntry.FxMode.TRACK;}
            public boolean persistTemplateOnClose(){return false;}
            public int previewBpm(){return owner.playlistTracks().stream().filter(t->owner.activePlaylistEntry()!=null&&t.id().equals(owner.activePlaylistEntry().trackId())).mapToInt(TrackSummary::bpm).findFirst().orElse(120);}
            public float previewPlaybackSpeed(){return owner.playlistPlaybackSpeed();}
            public void previewEffect(TrackEffect a,TrackEffect edited,String placement){publish(index,edited);}
            public void saveEffect(TrackEffect a,TrackEffect edited,String placement){publish(index,edited);}
            public void cancelEffectPreview(TrackEffect a,TrackEffect initial,String placement){publish(index,original);}
            public void deleteEffect(TrackEffect a){publish(index,null);}
        };
        minecraft.setScreen(new TrackEffectEditScreen(host,original,seed,"range"));
    }
    private void publish(int index,TrackEffect effect) {
        if(!entryId.equals(owner.activePlaylistEntryId())||index>effects.size())return;
        int expectedCount=effects.size();
        TrackEffect expected=index<effects.size()?effects.get(index):null;
        DjPlaylistEntry.FxMode expectedMode=mode;
        if(effect==null){if(index==effects.size())return;effects.remove(index);}
        else if(index==effects.size())effects.add(effect);else effects.set(index,effect);
        if(mode==DjPlaylistEntry.FxMode.NONE)mode=DjPlaylistEntry.FxMode.DJ;
        PacketDistributor.sendToServer(new EditDjPlaylistEffectPayload(owner.playlistPanelPos(),entryId,index,effect,expectedMode,expectedCount,expected));
    }
    public boolean isPauseScreen(){return false;}
    public void onClose(){if(minecraft!=null)minecraft.setScreen(owner);}
    public DjPanelScreen owningDjPanel(){return owner;}
}
