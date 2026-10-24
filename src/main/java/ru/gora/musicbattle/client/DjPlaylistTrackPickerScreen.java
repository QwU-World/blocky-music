package ru.gora.musicbattle.client;

import java.util.*;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.data.TrackSummary;

/** The same artwork and track metadata as the DJ library, with a focused selection action. */
final class DjPlaylistTrackPickerScreen extends Screen implements DjPanelOwnedScreen {
    private final Screen parent;
    private final DjPanelScreen owner;
    private final Consumer<TrackSummary> choose;
    private List<TrackSummary> filtered=List.of();
    private String query="";
    private int scroll;
    DjPlaylistTrackPickerScreen(Screen parent, DjPanelScreen owner, Consumer<TrackSummary> choose) {
        super(Component.translatable("screen.music_battle.dj_playlist.choose_track"));
        this.parent=parent; this.owner=owner; this.choose=choose;
    }
    protected void init() {
        EditBox search=addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font,16,32,width-32,20,title));
        search.setMaxLength(120); search.setValue(query);
        search.setHint(Component.translatable("screen.music_battle.dj_panel.search"));
        search.setResponder(value->{query=value;scroll=0;filter();});
        addRenderableWidget(FancyButton.create(Component.translatable("gui.back"),b->onClose(),width/2-60,height-28,120,20));
        filter();
    }
    private void filter() {
        String needle=query.strip().toLowerCase(Locale.ROOT);
        filtered=owner.playlistTracks().stream().filter(t -> (t.title()+" "+t.publishedBy()+" "+t.description()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    private int columns(){return width>=580?2:1;}
    private int cardWidth(){return (width-32-6*(columns()-1))/columns();}
    private int bottom(){return height-36;}
    private int maxScroll(){return Math.max(0,((filtered.size()+columns()-1)/columns())*84-(bottom()-60));}
    public void renderBackground(GuiGraphics g,int x,int y,float p){}
    public void render(GuiGraphics g,int mouseX,int mouseY,float partial) {
        g.fill(0,0,width,height,0xF2080C12);
        g.drawCenteredString(font,title,width/2,12,0xFFFFFFFF);
        scroll=Mth.clamp(scroll,0,maxScroll());
        List<String> visible=new ArrayList<>();
        g.enableScissor(16,60,width-16,bottom());
        for(int i=0;i<filtered.size();i++) {
            int x=16+(i%columns())*(cardWidth()+6), y=60+(i/columns())*84-scroll;
            if(y+78<60 || y>=bottom())continue;
            TrackSummary track=filtered.get(i); visible.add(track.id());
            boolean over=mouseX>=x&&mouseX<x+cardWidth()&&mouseY>=Math.max(60,y)&&mouseY<Math.min(bottom(),y+78);
            g.fill(x,y,x+cardWidth(),y+78,over?0xFF244D60:0xFF162832);
            g.fill(x,y,x+cardWidth(),y+2,over?0xFFFF72D2:0xFF315869);
            var image=owner.previewTexture(track);
            if(image!=null)g.blit(image,x+5,y+5,0,0,68,68,68,68);
            else g.fill(x+5,y+5,x+73,y+73,Palette.BG_3);
            int tx=x+80, tw=Math.max(10,cardWidth()-86);
            g.drawString(font,font.plainSubstrByWidth(track.title(),tw),tx,y+8,0xFFFFFFFF,false);
            int seconds=track.durationTicks()/20;
            g.drawString(font,"BPM "+track.bpm()+" · "+String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60),tx,y+23,Palette.TEXT_2,false);
            g.drawString(font,font.plainSubstrByWidth(track.publishedBy(),tw),tx,y+38,0xFFFFD38D,false);
            g.drawString(font,font.plainSubstrByWidth(track.description(),tw),tx,y+53,0xFFCAD7DD,false);
        }
        g.disableScissor(); owner.requestVisiblePreviews(visible);
        super.render(g,mouseX,mouseY,partial);
    }
    public boolean mouseClicked(double x,double y,int button) {
        if(button==0&&x>=16&&x<width-16&&y>=60&&y<bottom()) {
            int col=(int)(x-16)/(cardWidth()+6), row=(int)(y-60+scroll)/84;
            int i=row*columns()+col;
            if(col<columns()&&i<filtered.size()&&(x-16)%(cardWidth()+6)<cardWidth()&&(y-60+scroll)%84<78) {
                choose.accept(filtered.get(i));onClose();return true;
            }
        }
        return super.mouseClicked(x,y,button);
    }
    public boolean mouseScrolled(double x,double y,double sx,double sy){scroll=Mth.clamp(scroll-(int)(sy*28),0,maxScroll());return true;}
    public boolean isPauseScreen(){return false;}
    public void onClose(){if(minecraft!=null)minecraft.setScreen(parent);}
    public DjPanelScreen owningDjPanel(){return owner;}
}
