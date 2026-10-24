package ru.gora.musicbattle.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.awt.Rectangle;
import javax.imageio.ImageIO;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;

/** Runs the production renderer. Java2D only replaces the low-level rectangle/text output. */
public final class NotePanelRenderChecks {
    private static final Font FONT = new Font(ignored -> null, false) {
        @Override public int width(String text) { return text.length() * 6; }
    };
    record Note(int owner, int lane, int tick, int durationTicks, boolean started, boolean resolved)
            implements BattlePanelRenderer.NoteView { }

    public static void main(String[] args) throws Exception {
        Class<?> appearance;
        try { appearance = Class.forName("ru.gora.musicbattle.data.NoteAppearance"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Panel cannot receive per-lane note appearance", missing); }
        Constructor<?> appearanceConstructor = appearance.getConstructor(int.class, List.class);
        Constructor<?> panelConstructor = null;
        for (Constructor<?> candidate : BattlePanelRenderer.Panel.class.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 18) panelConstructor = candidate;
        }
        require(panelConstructor != null, "Shared renderer still has only the panel color: note appearance is missing");
        panelConstructor.setAccessible(true);
        int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00};
        List<Note> notes = List.of(new Note(0,0,27,0,false,false), new Note(0,1,27,0,false,false),
                new Note(0,2,27,0,false,false), new Note(0,3,27,0,false,false));
        for (int skin = 0; skin < 3; skin++) {
            Object style = appearanceConstructor.newInstance(skin, List.of(colors[0], colors[1], colors[2], colors[3]));
            BattlePanelRenderer.Panel panel = (BattlePanelRenderer.Panel) panelConstructor.newInstance(
                    0,0,true,"Preview",1F,0xFF111111,1F,1F,1F,1F,1F,1F,2400,12,
                    new int[4],new int[4],new String[]{"A","S","W","D"},style);
            if (skin > 0) {
                var size = BattlePanelRenderer.class.getDeclaredMethod("glyphSize",int.class,BattlePanelRenderer.Panel.class);
                size.setAccessible(true);
                require((int)size.invoke(null,80,panel) == 74,"Arrow/circle must fill 74 of an 80-pixel lane");
            }
            Raster graphics = Raster.create(960,540);
            BattlePanelRenderer.draw(graphics, FONT, 960,540,0F,120,1F,notes,panel);
            for (int lane = 0; lane < 4; lane++) {
                int centerX = 60 + lane * 80;
                int centerY = skin == 0 ? 263 : 268;
                require(graphics.rgb(centerX,centerY) == lighten(colors[lane]), "Lane " + lane + " ignores independent note color");
                if (skin == 0) require(graphics.rgb(centerX - 10,258) != 0x111111, "Legacy bar lost its width");
                else {
                    require(graphics.rgb(centerX - 35,centerY - 35) != lighten(colors[lane]), "Shaped note has filled rectangular corners");
                    int minX=999,maxX=-1,minY=999,maxY=-1;
                    for(int yy=225;yy<310;yy++)for(int xx=centerX-40;xx<centerX+40;xx++) {
                        int rgb=graphics.rgb(xx,yy);
                        if(rgb==(colors[lane]&0xFFFFFF)||rgb==lighten(colors[lane])) {
                            minX=Math.min(minX,xx);maxX=Math.max(maxX,xx);minY=Math.min(minY,yy);maxY=Math.max(maxY,yy);
                        }
                    }
                    require(maxX-minX>=70 && maxY-minY>=70,"Actual note pixels still too small for full lane");
                }
            }
            for (int labelY : graphics.labelY) require(labelY + 9 <= 469,"Receptor key label overlaps panel score");
            if(skin>0) {
                Raster entering = Raster.create(960,540);
                BattlePanelRenderer.draw(entering,FONT,960,540,-27F,120,1F,notes,panel);
                for(int yy=70;yy<86;yy++)for(int xx=20;xx<340;xx++)for(int color:colors)
                    require(entering.rgb(xx,yy)!=(color&0xFFFFFF) && entering.rgb(xx,yy)!=lighten(color),
                            "Large entering note covers player-name header");
            }
            Raster hold = Raster.create(960,540);
            BattlePanelRenderer.draw(hold,FONT,960,540,35F,120,1F,
                    List.of(new Note(0,0,27,20,true,false)),panel);
            require(hold.rgb(60,380)==lighten(colors[0]),"Started sustain lost its colored tail");
            Raster expiredHold = Raster.create(960,540);
            BattlePanelRenderer.draw(expiredHold,FONT,960,540,50F,120,1F,
                    List.of(new Note(0,0,27,20,true,true)),panel);
            require(expiredHold.rgb(60,380)!=lighten(colors[0]),"Resolved sustain keeps drawing a tail");
            int[] bursts={8,0,0,0};
            BattlePanelRenderer.Panel hit=(BattlePanelRenderer.Panel)panelConstructor.newInstance(
                    0,0,true,"Preview",1F,0xFF111111,1F,1F,1F,1F,1F,1F,2400,12,
                    new int[4],bursts,new String[]{"A","S","W","D"},style);
            Raster hitFrame=Raster.create(960,540);
            BattlePanelRenderer.draw(hitFrame,FONT,960,540,0F,120,1F,List.of(),hit);
            require(hitFrame.rgb(60,skin==0?431:410)!=graphics.rgb(60,skin==0?431:410),"Receptor does not show hit feedback");
            ImageIO.write(graphics.image,"png",new File(args[0],"notes-skin-" + skin + ".png"));
            if(skin==1) {
                Raster enlarged=Raster.create(1280,1680);
                enlarged.pose().scale(4,4,1);
                enlarged.pose().translate(-20,-65,0);
                BattlePanelRenderer.draw(enlarged,FONT,960,540,0F,120,1F,notes,panel);
                ImageIO.write(enlarged.image,"png",new File(args[0],"notes-arrows-vector-4x.png"));
            }
            Raster clipped = Raster.create(426,240);
            SettingsScreenLayout.Rect viewport = SettingsScreenLayout.create(426,240).preview();
            SettingsPanelPreview.draw(clipped,FONT,viewport,123F,panel);
            for(int yy=0;yy<240;yy++)for(int xx=0;xx<426;xx++)
                require(viewport.contains(xx,yy)||clipped.image.getRGB(xx,yy)==0,"Preview draws over controls/header/footer");
            require(clipped.poses.clear() && clipped.clip==null,"Preview leaks pose/scissor state");
        }
        require(BattlePanelRenderer.leadTicks(20F) == 3, "20x still stops at the old eight-tick lead floor");
        Class<?> preview;
        try { preview = Class.forName("ru.gora.musicbattle.client.SettingsPanelPreview"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Settings still uses copied static note rendering",missing); }
        var frameMethod = preview.getDeclaredMethod("frame",float.class);
        frameMethod.setAccessible(true);
        Object frame = frameMethod.invoke(null,123F);
        var notesMethod = frame.getClass().getDeclaredMethod("notes");
        notesMethod.setAccessible(true);
        List<?> previewNotes = (List<?>) notesMethod.invoke(frame);
        require(previewNotes.stream().map(BattlePanelRenderer.NoteView.class::cast)
                .anyMatch(note -> note.lane()==2 && note.tick()==120 && note.started() && !note.resolved()
                        && note.durationTicks()==18),"Preview cannot demonstrate sustained notes");
        var burstsMethod = frame.getClass().getDeclaredMethod("bursts");
        burstsMethod.setAccessible(true);
        require(((int[])burstsMethod.invoke(frame))[2] == 5,"Animated preview does not trigger hit feedback");
        Object later = frameMethod.invoke(null,130F);
        require(((int[])burstsMethod.invoke(later))[2] == 0,"Preview hit feedback never expires");
        System.out.println("PASS: actual BattlePanelRenderer draws independent lane colors for all three skins; 20x reaches 3 ticks");
        System.out.println("NOT COVERED: Minecraft font batching, GPU integration, and live gameplay");
    }

    static final class Raster extends GuiGraphics {
        BufferedImage image;
        PoseStack poses;
        Rectangle clip;
        List<Integer> labelY;
        MultiBufferSource.BufferSource buffers;
        private Raster() { super(null, null); }
        static Raster create(int width, int height) throws Exception {
            // GuiGraphics constructor touches ItemStack registries; rendering geometry does not.
            var unsafeClass = Class.forName("sun.misc.Unsafe");
            var field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Raster result = (Raster) unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),Raster.class);
            result.image = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            result.poses = new PoseStack();
            result.labelY = new ArrayList<>();
            result.buffers = new RasterBuffers(result);
            return result;
        }
        @Override public PoseStack pose() { return poses; }
        @Override public MultiBufferSource.BufferSource bufferSource() { return buffers; }
        @Override public void enableScissor(int x,int y,int right,int bottom) { clip=new Rectangle(x,y,right-x,bottom-y); }
        @Override public void disableScissor() { clip=null; }
        int rgb(int x,int y) { return image.getRGB(x,y) & 0xFFFFFF; }
        @Override public void fill(int left,int top,int right,int bottom,int color) {
            Vector3f a = pose().last().pose().transformPosition(left,top,0,new Vector3f());
            Vector3f b = pose().last().pose().transformPosition(right,bottom,0,new Vector3f());
            var draw = image.createGraphics();
            draw.setClip(clip);
            draw.setColor(new java.awt.Color(color,true));
            draw.fillRect(Math.round(a.x),Math.round(a.y),Math.round(b.x-a.x),Math.round(b.y-a.y));
            draw.dispose();
        }
        @Override public void drawCenteredString(Font font,String text,int x,int y,int color) {
            if(text.equals("A")||text.equals("S")||text.equals("W")||text.equals("D"))
                labelY.add(Math.round(pose().last().pose().transformPosition(x,y,0,new Vector3f()).y));
        }
        @Override public void drawCenteredString(Font font,Component text,int x,int y,int color) { }
    }
    static final class RasterBuffers extends MultiBufferSource.BufferSource {
        final VertexConsumer sink;
        RasterBuffers(Raster raster) { super(null,new LinkedHashMap<>()); sink=new TriangleRaster(raster); }
        @Override public VertexConsumer getBuffer(RenderType type) { return sink; }
    }
    static final class TriangleRaster implements VertexConsumer {
        final Raster raster;
        final float[] x=new float[4],y=new float[4];
        int count;
        TriangleRaster(Raster raster) {this.raster=raster;}
        @Override public VertexConsumer addVertex(float px,float py,float pz){x[count]=px;y[count]=py;return this;}
        @Override public VertexConsumer setColor(int red,int green,int blue,int alpha){
            if(++count==4){
                var path=new java.awt.geom.Path2D.Float();path.moveTo(x[0],y[0]);path.lineTo(x[1],y[1]);path.lineTo(x[2],y[2]);path.closePath();
                var draw=raster.image.createGraphics();draw.setClip(raster.clip);draw.setColor(new java.awt.Color(red,green,blue,alpha));draw.fill(path);draw.dispose();count=0;
            }
            return this;
        }
        @Override public VertexConsumer setUv(float u,float v){return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
    private static int lighten(int color) { return Math.min(255, ((color>>>16)&255)+95)<<16
            | Math.min(255,((color>>>8)&255)+95)<<8 | Math.min(255,(color&255)+95); }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
