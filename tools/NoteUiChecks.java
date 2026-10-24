package ru.gora.musicbattle.client;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Pure geometry checks. These exercise the same layout and glyph membership used by the UI. */
public final class NoteUiChecks {
    public static void main(String[] args) throws Exception {
        Class<?> layout;
        try { layout = Class.forName("ru.gora.musicbattle.client.SettingsScreenLayout"); }
        catch (ClassNotFoundException missing) {
            throw new AssertionError("Settings preview has no reserved viewport: compact controls overlap it", missing);
        }
        Method create = layout.getDeclaredMethod("create", int.class, int.class);
        create.setAccessible(true);
        int[][] sizes = {{320, 240}, {426, 240}, {640, 360}, {854, 480}, {1280, 720}, {1920, 1080}};
        for (int[] size : sizes) {
            Object value = create.invoke(null, size[0], size[1]);
            Object controls = get(value, "controls");
            Object preview = get(value, "preview");
            if (size[0] < 620 && size[1] == 240) {
                require(number(preview, "height") >= 65,
                        "Wrapped navigation shrinks the compact note preview instead of the scrollable controls");
            }
            for (Object rect : new Object[]{controls, preview}) {
                require(number(rect, "x") >= 0 && number(rect, "right") <= size[0], "Viewport leaves horizontal bounds");
                require(number(rect, "y") >= 32 && number(rect, "bottom") <= size[1] - 38,
                        "Viewport covers tabs or footer at " + size[0] + "x" + size[1]);
                require(number(rect, "width") > 0 && number(rect, "height") > 0, "Empty viewport");
            }
            boolean separate = number(controls, "right") <= number(preview, "x")
                    || number(preview, "right") <= number(controls, "x")
                    || number(controls, "bottom") <= number(preview, "y")
                    || number(preview, "bottom") <= number(controls, "y");
            require(separate, "Preview overlaps controls at " + size[0] + "x" + size[1]);
        }
        Class<?> glyph;
        try { glyph = Class.forName("ru.gora.musicbattle.client.BattleNoteShape"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Note skins have no shared shape geometry", missing); }
        Class<?> triangles;
        try { triangles = Class.forName("ru.gora.musicbattle.client.BattleNoteShape$TriangleFill"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Arrow edges still use enlarged stair-step scanline rectangles", missing); }
        Method draw = glyph.getDeclaredMethod("drawTriangles", triangles, int.class, int.class,
                float.class,float.class,float.class,float.class,int.class);
        draw.setAccessible(true);
        for (int skin : new int[]{0,1,2}) for (int lane=0;lane<4;lane++) {
            double[] area = {0};
            List<float[]> emitted = new ArrayList<>();
            Object sink = Proxy.newProxyInstance(triangles.getClassLoader(),new Class[]{triangles},(proxy,method,values)->{
                float x1=(float)values[0],y1=(float)values[1],x2=(float)values[2],y2=(float)values[3],x3=(float)values[4],y3=(float)values[5];
                require(x1>=-1 && x1<=1 && x2>=-1 && x2<=1 && x3>=-1 && x3<=1,"Triangle escapes glyph bounds");
                area[0] += Math.abs((x2-x1)*(y3-y1)-(x3-x1)*(y2-y1))*.5;
                emitted.add(new float[]{x1,y1,x2,y2,x3,y3});
                return null;
            });
            draw.invoke(null,sink,skin,lane,-1F,-1F,1F,1F,0xFFFFFFFF);
            require(Math.abs(area[0]-(skin==0?4:skin==1?1.72:Math.PI)) < .02,"Vector outline has gaps/overlaps or malformed circle");
            if(skin==1) {
                float[][] tip = {{-.9F, 0}, {0, .9F}, {0, -.9F}, {.9F, 0}};
                float[][] notch = {{.8F, .7F}, {.7F, -.8F}, {.7F, .8F}, {-.8F, .7F}};
                require(covered(emitted,tip[lane][0],tip[lane][1]),"Missing arrow tip " + lane);
                require(!covered(emitted,notch[lane][0],notch[lane][1]),"Arrow notch filled " + lane);
            } else if(skin==2) {
                require(covered(emitted,0,.9F) && !covered(emitted,.9F,.9F),"Circle is malformed or square");
            } else require(covered(emitted,.9F,.9F),"Legacy bar corner missing");
        }
        System.out.println("PASS: disjoint settings viewports at six GUI sizes; four arrow directions, circle and bar geometry");
    }

    private static Object get(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static int number(Object value, String name) throws Exception { return (int) get(value, name); }
    private static boolean covered(List<float[]> triangles,float x,float y) {
        for(float[] t:triangles) {
            float denominator=(t[3]-t[5])*(t[0]-t[4])+(t[4]-t[2])*(t[1]-t[5]);
            if(Math.abs(denominator)<.00001F)continue;
            float a=((t[3]-t[5])*(x-t[4])+(t[4]-t[2])*(y-t[5]))/denominator;
            float b=((t[5]-t[1])*(x-t[4])+(t[0]-t[4])*(y-t[5]))/denominator;
            if(a>=-.00001F && b>=-.00001F && a+b<=1.00001F)return true;
        }
        return false;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
