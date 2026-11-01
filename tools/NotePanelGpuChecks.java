package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import ru.gora.musicbattle.data.NoteAppearance;
import static org.lwjgl.opengl.GL11.*;

/** Production panel vertices on a real GPU with Minecraft's Y-down projection and back-face culling.
 * Replaces the shader submission and font only; does not launch Minecraft or exercise mod mixins. */
public final class NotePanelGpuChecks {
    private static final Font FONT = new Font(ignored -> null, false) {
        @Override public int width(String text) { return text.length() * 6; }
    };
    private static final int[] COLORS = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00};
    record Note(int owner, int lane, int tick, int durationTicks, boolean started, boolean resolved)
            implements BattlePanelRenderer.NoteView { }

    public static void main(String[] args) throws Exception {
        require(GLFW.glfwInit(), "GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(960, 540, "Blocky note culling test (hidden)", 0, 0);
        require(window != 0, "Hidden OpenGL context missing");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glViewport(0, 0, 960, 540);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            glOrtho(0, 960, 540, 0, -1000, 1000);
            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_TEXTURE_2D);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glFrontFace(GL_CCW);
            glCullFace(GL_BACK);
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            GpuGraphics graphics = GpuGraphics.create();
            checkUniformArrowBorder(graphics);
            List<Note> notes = List.of(new Note(0,0,27,0,false,false), new Note(0,1,27,0,false,false),
                    new Note(0,2,27,0,false,false), new Note(0,3,27,0,false,false));
            int checks = 0;
            for (int skin = 0; skin < 3; skin++) {
                BattlePanelRenderer.Panel panel = new BattlePanelRenderer.Panel(0,0,true,"Preview",1F,0xFF111111,
                        1F,1F,1F,1F,1F,1F,2400,12,new int[4],new int[4],new String[]{"A","S","W","D"},
                        new NoteAppearance(skin,List.of(COLORS[0],COLORS[1],COLORS[2],COLORS[3])));
                // The control proves geometry exists; enabling culling must not erase any part of it.
                glDisable(GL_CULL_FACE);
                clear();
                BattlePanelRenderer.draw(graphics,FONT,960,540,0F,120,1F,notes,panel);
                byte[] withoutCulling = pixels();
                glEnable(GL_CULL_FACE);
                clear();
                BattlePanelRenderer.draw(graphics,FONT,960,540,0F,120,1F,notes,panel);
                byte[] withCulling = pixels();
                for (int lane=0; lane<4; lane++) {
                    int expected = lighten(COLORS[lane]);
                    int x = 60+lane*80, y = skin==0?263:268;
                    require(rgb(withoutCulling,x,y)==expected,"Unculled reference head missing for skin="+skin+" lane="+lane);
                    require(rgb(withCulling,x,y)==expected,"Back-face culling erased head for skin="+skin+" lane="+lane);
                    checks += 2;
                }
                require(java.util.Arrays.equals(withoutCulling,withCulling),
                        "Back-face culling erased a head or receptor pixel for skin="+skin);
                checks++;
            }
            require(glGetError()==GL_NO_ERROR,"OpenGL error");
            System.out.println("PASS: "+checks+" GPU checks; heads/receptors remain visible with back-face culling for all skins/directions");
            System.out.println("NOT COVERED: Minecraft shaders/font batching, third-party rendering mixins, live gameplay");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }

    private static void checkUniformArrowBorder(GpuGraphics graphics) throws Exception {
        // The glyph is drawn into the box its lane gives it (BattlePanelRenderer.Lanes), centred here on (160, 260).
        var draw = BattlePanelRenderer.class.getDeclaredMethod("drawShapedGlyph",GuiGraphics.class,
                BattlePanelRenderer.Panel.class,int.class,BattlePanelRenderer.Box.class,int.class,int.class);
        draw.setAccessible(true);
        var panel = new BattlePanelRenderer.Panel(0,0,true,"Border",1F,0xFF111111,
                1F,1F,1F,1F,1F,1F,0,0,new int[4],new int[4],new String[]{"A","S","W","D"},
                new NoteAppearance(NoteAppearance.ARROWS,List.of(
                0xFFFF0000,0xFFFF0000,0xFFFF0000,0xFFFF0000)));
        GpuVertices sink = (GpuVertices)graphics.buffers.getBuffer(RenderType.gui());
        sink.capture = true;
        int checks = 0;
        // Literal fixtures: very small glyphs need a reduced border so the stem stays open.
        for (float[] fixture : new float[][]{{40,2F},{74,2F},{112,2F},{16,2F},{6,.75F}}) {
            for (int lane=0;lane<4;lane++) {
                sink.captured.clear();
                int size=(int)fixture[0];
                draw.invoke(null,graphics,panel,lane,new BattlePanelRenderer.Box(160-size/2,260-size/2,160-size/2+size,260-size/2+size),
                        0xFFFF0000,0xFF0000FF);
                require(sink.captured.size()==14,"Arrow boundary topology changed; review edge-distance fixture");
                for(int edge=0;edge<7;edge++) {
                    float[] outer=sink.captured.get(edge), inner=sink.captured.get(edge+7);
                    float dx=outer[6]-outer[3],dy=outer[7]-outer[4];
                    float length=(float)Math.hypot(dx,dy);
                    for(int point : new int[]{3,6}) {
                        float inward=-(dx*(inner[point+1]-outer[4])-dy*(inner[point]-outer[3]))/length;
                        require(Math.abs(inward-fixture[1])<.0001F,
                                "Uneven arrow border: size="+fixture[0]+" lane="+lane+" edge="+edge
                                +" expected="+fixture[1]+" actual="+inward);
                        checks++;
                    }
                    float winding=(inner[3]-inner[0])*(inner[7]-inner[1])-(inner[6]-inner[0])*(inner[4]-inner[1]);
                    require(winding<=.0001F,"Inset triangle leaves the concave arrow or becomes back-facing");
                }
            }
        }
        sink.capture=false;
        sink.captured.clear();
        System.out.println("PASS: "+checks+" uniform arrow edge distances at five sizes and four directions");
    }

    private static void clear() { glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT); }
    private static byte[] pixels() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(960*540*4);
        glReadPixels(0,0,960,540,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        byte[] result = new byte[pixels.remaining()]; pixels.get(result); return result;
    }
    private static int rgb(byte[] image,int x,int y) {
        int offset = ((539-y)*960+x)*4;
        return (image[offset]&255)<<16 | (image[offset+1]&255)<<8 | image[offset+2]&255;
    }
    private static int lighten(int color) { return Math.min(255,((color>>>16)&255)+95)<<16
            | Math.min(255,((color>>>8)&255)+95)<<8 | Math.min(255,(color&255)+95); }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }

    private static final class GpuGraphics extends GuiGraphics {
        PoseStack poses;
        MultiBufferSource.BufferSource buffers;
        private GpuGraphics() { super(null,null); }
        static GpuGraphics create() throws Exception {
            var type=Class.forName("sun.misc.Unsafe"); var field=type.getDeclaredField("theUnsafe"); field.setAccessible(true);
            var graphics=(GpuGraphics)type.getMethod("allocateInstance",Class.class).invoke(field.get(null),GpuGraphics.class);
            graphics.poses=new PoseStack();
            graphics.buffers=new MultiBufferSource.BufferSource(null,new LinkedHashMap<>()) {
                final VertexConsumer sink=new GpuVertices();
                @Override public VertexConsumer getBuffer(RenderType type) {
                    require(type==RenderType.gui(),"Note submission changed GUI render type; review GPU fixture state");
                    return sink;
                }
            };
            return graphics;
        }
        @Override public PoseStack pose() { return poses; }
        @Override public MultiBufferSource.BufferSource bufferSource() { return buffers; }
        @Override public void fill(int left,int top,int right,int bottom,int color) {
            // Same vertex order as the pinned GuiGraphics.fill (right/bottom first).
            VertexConsumer vertices=buffers.getBuffer(RenderType.gui());
            vertices.addVertex(poses.last().pose(),right,bottom,0).setColor(color);
            vertices.addVertex(poses.last().pose(),right,top,0).setColor(color);
            vertices.addVertex(poses.last().pose(),left,top,0).setColor(color);
            vertices.addVertex(poses.last().pose(),left,bottom,0).setColor(color);
        }
        @Override public void drawCenteredString(Font font,String text,int x,int y,int color) { }
        @Override public void drawCenteredString(Font font,Component text,int x,int y,int color) { }
    }
    private static final class GpuVertices implements VertexConsumer {
        final Vector3f[] points={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
        final java.util.List<float[]> captured=new java.util.ArrayList<>();
        boolean capture;
        int count;
        @Override public VertexConsumer addVertex(float x,float y,float z) { points[count].set(x,y,z); return this; }
        @Override public VertexConsumer setColor(int red,int green,int blue,int alpha) {
            if(++count==4) {
                if(capture) captured.add(new float[]{points[0].x,points[0].y,points[0].z,
                        points[1].x,points[1].y,points[1].z,points[2].x,points[2].y,points[2].z});
                glColor4ub((byte)red,(byte)green,(byte)blue,(byte)alpha);
                glBegin(GL_TRIANGLES);
                for(int index:new int[]{0,1,2,2,3,0}) { Vector3f p=points[index]; glVertex3f(p.x,p.y,p.z); }
                glEnd(); count=0;
            }
            return this;
        }
        @Override public VertexConsumer setUv(float u,float v) { return this; }
        @Override public VertexConsumer setUv1(int u,int v) { return this; }
        @Override public VertexConsumer setUv2(int u,int v) { return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { return this; }
    }
}
