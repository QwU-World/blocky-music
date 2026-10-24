package ru.gora.musicbattle.client;

import java.util.*;
import ru.gora.musicbattle.client.ui.Palette;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.data.*;
import ru.gora.musicbattle.network.EditDjPlaylistEffectPayload;

/** Edits the current queue entry, preserving chart timing and every FX parameter. */
final class DjPlaylistEffectsScreen extends Screen implements DjPanelOwnedScreen {
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
    public void render(GuiGraphics g,int mx,int my,float partial) {
        if(!owner.renderActiveCameraPreview(g,0,0,width,height,partial))g.fill(0,0,width,height,0xFF080C12);
        g.fill(8,6,width-8,height-6,0xD012232D);
        g.drawCenteredString(font,title,width/2,14,0xFFFFFFFF);
        g.drawCenteredString(font,Component.translatable("screen.music_battle.dj_playlist.fx_"+mode.name().toLowerCase(Locale.ROOT)),width/2,29,0xFF72DFF5);
        int rows=Math.max(1,(height-86)/24);scroll=Mth.clamp(scroll,0,Math.max(0,effects.size()-rows));
        for(int r=0;r<rows&&scroll+r<effects.size();r++) {
            int i=scroll+r,y=48+r*24;TrackEffect fx=effects.get(i);
            g.fill(16,y,width-16,y+22,mx>=16&&mx<width-16&&my>=y&&my<y+22?0xFF24566A:0xCC162832);
            String line=(i+1)+". "+Component.translatable("effect.music_battle."+fx.type().id()).getString();
            if(mode==DjPlaylistEntry.FxMode.TRACK)line+="  @ "+String.format(Locale.ROOT,"%.2f s",fx.tick()/20f);
            g.drawString(font,font.plainSubstrByWidth(line,width-48),24,y+7,0xFFFFFFFF,false);
        }
        if(effects.isEmpty())g.drawCenteredString(font,Component.translatable("screen.music_battle.dj_playlist.no_entry_fx"),width/2,64,Palette.TEXT_2);
        super.render(g,mx,my,partial);
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
