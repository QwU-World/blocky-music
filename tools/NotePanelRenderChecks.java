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
            SettingsScreenLayout.Preview geometry = previewAt(426,240,427,240);
            SettingsPanelPreview.drawBattle(clipped,FONT,geometry,123F,panel,new SettingsPanelPreview.Stage(70,185));
            for(int yy=0;yy<240;yy++)for(int xx=0;xx<426;xx++)
                require(geometry.card().contains(xx,yy)||clipped.image.getRGB(xx,yy)==0,"Preview draws outside its picture, over controls/header/footer");
            require(clipped.poses.clear() && clipped.clip==null,"Preview leaks pose/scissor state");
        }
        stagePreview(panelConstructor, appearanceConstructor);
        // The preview keeps room for the panel the battle draws at the width slider's maximum, which the screen measures
        // with the battle's own layout: no setting of the slider gives a wider one, and that one fits in the picture.
        for (int canvas : new int[]{320, 427, 480, 569, 640, 854, 1280, 1920}) {
            BattlePanelRenderer.Layout widest = BattlePanelRenderer.layout(canvas, 360, 1F, SettingsPanelPreview.widestPanel());
            require(widest.x() == SettingsScreenLayout.PANEL_MARGIN, "The battle no longer puts its panel 20 units in at " + canvas);
            for (float scale = .45F; scale <= SettingsScreenLayout.MAX_PANEL_WIDTH_SCALE + 1E-4F; scale += .01F) {
                BattlePanelRenderer.Panel at = (BattlePanelRenderer.Panel) panelConstructor.newInstance(
                        0,0,true,"Preview",1F,0xFF111111,1F,1F,scale,1F,1F,1F,0,0,new int[4],new int[4],new String[]{"A","S","W","D"},null);
                require(BattlePanelRenderer.layout(canvas, 360, 1F, at).width() <= widest.width(),
                        "A panel narrower on the slider is wider than its maximum at " + canvas + " scale " + scale);
            }
            for (int[] gui : new int[][]{{320,240},{427,240},{480,270},{640,360},{960,540}}) {
                SettingsScreenLayout.Preview geometry = previewAt(gui[0], gui[1], canvas, 360);
                require(canvas < geometry.needed() || geometry.visibleWidth() >= SettingsScreenLayout.PANEL_MARGIN + widest.width(),
                        "The widest panel is cut off in the preview of a " + canvas + " canvas at " + gui[0] + "x" + gui[1]);
            }
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
        System.out.println("PASS: the settings preview over a world shows the panel background, the dimming and the bars, in the battle's order");
        System.out.println("NOT COVERED: Minecraft font batching, GPU integration, and live gameplay");
    }

    /** The settings screen's picture of a battle at a GUI size, for a battle canvas of the given size. */
    private static SettingsScreenLayout.Preview previewAt(int width, int height, int canvasWidth, int canvasHeight) {
        SettingsScreenLayout.Nav nav = SettingsScreenLayout.nav(width, height, (text, style) -> text.length() * 6F,
                List.of("General settings", "Calibration", "Beatmap Editor", "Guide", "Workshop"), 0);
        int widest = BattlePanelRenderer.layout(canvasWidth, canvasHeight, 1F, SettingsPanelPreview.widestPanel()).width();
        return SettingsScreenLayout.preview(SettingsScreenLayout.create(width, height, nav).preview(), canvasWidth, canvasHeight,
                widest, 40F);
    }

    /**
     * The settings preview drawn over a world, as the battle draws its panel over one. The panel background slider used
     * to change nothing there, because the panel's default colour was the very background it was drawn on; over a world
     * it has to show. The dimming and the bars have to be the battle's own: nothing at all at 0, the old alphas by
     * default, darker as the setting rises, the bars over the dimming and the panel over both.
     */
    private static void stagePreview(Constructor<?> panelConstructor, Constructor<?> appearanceConstructor) throws Exception {
        int width = 854, height = 480, canvasWidth = 640, canvasHeight = 360;
        SettingsScreenLayout.Preview geometry = previewAt(width, height, canvasWidth, canvasHeight);
        SettingsScreenLayout.Rect card = geometry.card();
        int world = 0xFF6A8FB0;
        Object style = appearanceConstructor.newInstance(1, List.of(0xFFBE8FFF, 0xFFFFD152, 0xFFBE8FFF, 0xFFFFD152));
        java.util.function.BiFunction<Float, Integer, BattlePanelRenderer.Panel> panelOf = (opacity, color) -> {
            try {
                return (BattlePanelRenderer.Panel) panelConstructor.newInstance(0,0,true,"You",5F,color,opacity,.35555556F,
                        .64308643F,1.5999999F,2.2F,.7793827F,0,0,new int[4],new int[4],new String[]{"A","S","W","D"},style);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        };
        BattlePanelRenderer.Layout at = BattlePanelRenderer.layout(canvasWidth, canvasHeight, 1F, panelOf.apply(0F, 0xFF110F14));
        int[] empty = {card.right() - 3, card.y() + card.height() / 2};
        int[] top = {card.right() - 3, card.y() + 2};
        int[] bottom = {card.right() - 3, card.bottom() - 2};
        int[] back = {Math.round(card.x() + (at.x() + at.width() / 2F) * geometry.scale()),
                Math.round(card.y() + (at.y() + at.height() - 4) * geometry.scale())};
        int[] header = {Math.round(card.x() + (at.x() + 5) * geometry.scale()), Math.round(card.y() + (at.y() + 7) * geometry.scale())};
        require(geometry.visibleWidth() * geometry.scale() > (at.x() + at.width() + 10) * geometry.scale()
                && empty[0] > Math.round(card.x() + (at.x() + at.width()) * geometry.scale()), "Sample point is not beside the panel");

        Raster clear = stage(geometry, world, panelOf.apply(0F, 0xFF110F14), 0, 0, width, height);
        Raster solid = stage(geometry, world, panelOf.apply(1F, 0xFF110F14), 0, 0, width, height);
        Raster standard = stage(geometry, world, panelOf.apply(0F, 0xFF110F14), 70, 185, width, height);
        Raster noBars = stage(geometry, world, panelOf.apply(0F, 0xFF110F14), 70, 0, width, height);
        Raster darkest = stage(geometry, world, panelOf.apply(0F, 0xFF110F14), 230, 0, width, height);
        Raster redPanel = stage(geometry, world, panelOf.apply(1F, 0xFFFF0000), 230, 185, width, height);

        require(clear.argb(empty) == world && clear.argb(top) == world, "Dimming or bars at 0 still draw over the world");
        require(clear.argb(back) == world, "A panel background at 0% hides the world");
        require(luma(solid.argb(back)) + 20 < luma(world), "The panel background slider changes nothing in the preview");
        int veiled = over(0, 70, world);
        require(close(noBars.argb(empty), veiled), "The default dimming is not argb(70, 0, 0, 0) over the world: "
                + Integer.toHexString(noBars.argb(empty)) + " vs " + Integer.toHexString(veiled));
        require(noBars.argb(top) == noBars.argb(empty), "Bars switched off still draw");
        require(close(standard.argb(top), over(0, 185, veiled)) && standard.argb(bottom) == standard.argb(top)
                && luma(standard.argb(top)) < luma(standard.argb(empty)), "The default bars are not alpha 185 over the dimming");
        require(close(darkest.argb(empty), over(0, 230, world)) && luma(darkest.argb(empty)) < luma(noBars.argb(empty)),
                "The darkest dimming is not darker than the default");
        require(((redPanel.argb(header) >> 16) & 0xFF) > 150, "The panel is drawn under the dimming instead of over it");
    }

    private static Raster stage(SettingsScreenLayout.Preview geometry, int world, BattlePanelRenderer.Panel panel,
                                int dimming, int bars, int width, int height) throws Exception {
        Raster raster = Raster.create(width, height);
        SettingsScreenLayout.Rect card = geometry.card();
        raster.fill(card.x(), card.y(), card.right(), card.bottom(), world);
        // At time 300 every demo note still drawn is on its way down, above the hit line, clear of the points sampled below it.
        SettingsPanelPreview.drawBattle(raster, FONT, geometry, 300F, panel, new SettingsPanelPreview.Stage(dimming, bars));
        require(raster.poses.clear() && raster.clip == null, "Preview leaks pose/scissor state");
        return raster;
    }

    /** {@code colour} at {@code alpha} over an opaque {@code below}, as eight-bit blending does it. */
    private static int over(int colour, int alpha, int below) {
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int top = (colour >> shift) & 0xFF;
            int bottom = (below >> shift) & 0xFF;
            result |= Math.round((top * alpha + bottom * (255 - alpha)) / 255F) << shift;
        }
        return result;
    }

    private static boolean close(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 2) return false;
        }
        return true;
    }

    private static int luma(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
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
        int argb(int[] point) { return image.getRGB(point[0],point[1]); }
        // Everything this raster draws lands at once; the real flush would reach for the GPU.
        @Override public void flush() { }
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
        // The renderer may draw a label left-aligned under a pose that centres and scales it; the key labels are
        // recorded the same way whichever call it uses, and nothing else is drawn as text here.
        @Override public int drawString(Font font,String text,int x,int y,int color) {
            drawCenteredString(font,text,x,y,color);
            return x;
        }
        @Override public int drawString(Font font,String text,int x,int y,int color,boolean shadow) {
            return drawString(font,text,x,y,color);
        }
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
