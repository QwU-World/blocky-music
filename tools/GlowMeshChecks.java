import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Real interleaved buffers: detects pivot scaling, split hard edges and attribute corruption. */
public final class GlowMeshChecks {
    private static final int STRIDE = 36, POSITION = 4, NORMAL = 28;
    private static Method expand;
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            expand = Class.forName("ru.gora.musicbattle.client.GlowMeshExpansion")
                    .getMethod("expand", ByteBuffer.class, int.class, int.class, int.class, int.class, float.class);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing symmetric mesh expansion; pivot scaling cannot preserve equal top/bottom rims", e);
        }
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            sphere(order, 0, 0, 0);
            sphere(order, 13, -27, 4);
            cube(order, false);
            cube(order, true);
            thinCape(order);
            zeroNormals(order);
            attributes(order);
            preparedRadii(order);
        }
        System.out.println("Glow mesh checks PASS: " + checks);
    }

    private static void sphere(ByteOrder order, float x, float y, float z) throws Exception {
        ByteBuffer mesh = buffer(6, order);
        float[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int i=0;i<6;i++) {
            float[] n=directions[i];
            vertex(mesh,i,x+n[0]*.5F,y+n[1]*.5F,z+n[2]*.5F,n[0],n[1],n[2]);
        }
        run(mesh,6,.125F);
        for(int i=0;i<6;i++) {
            float[] n=directions[i];
            near(pos(mesh,i,0),x+n[0]*.625F,"sphere X translation/pivot invariance");
            near(pos(mesh,i,1),y+n[1]*.625F,"sphere Y translation/pivot invariance");
            near(pos(mesh,i,2),z+n[2]*.625F,"sphere Z translation/pivot invariance");
        }
        checks++;
    }

    private static void cube(ByteOrder order, boolean rotated) throws Exception {
        // Three faces share a corner. One face is triangulated more densely: no weighting bias.
        ByteBuffer mesh=buffer(4,order);
        float[][] normals={{1,0,0},{0,1,0},{0,0,1},{1,0,0}};
        float px=rotated?-2:1, py=rotated?1:2, pz=3;
        for(int i=0;i<4;i++) {
            float[] n=normals[i];
            vertex(mesh,i,px,py,pz,rotated?-n[1]:n[0],rotated?n[0]:n[1],n[2]);
        }
        run(mesh,4,.17320508F); // length of (.1,.1,.1)
        for(int i=0;i<4;i++) {
            near(pos(mesh,i,0),px+(rotated?-.1F:.1F),"welded corner X");
            near(pos(mesh,i,1),py+.1F,"welded corner Y");
            near(pos(mesh,i,2),pz+.1F,"welded corner Z");
        }
        checks++;
    }

    private static void thinCape(ByteOrder order) throws Exception {
        ByteBuffer mesh=buffer(2,order);
        vertex(mesh,0,3,7,0,0,0,1);
        vertex(mesh,1,3,7,0,0,0,-1);
        run(mesh,2,.125F);
        near(pos(mesh,0,2),.125F,"cape front expands outward");
        near(pos(mesh,1,2),-.125F,"cape back expands outward");
        near(pos(mesh,0,1),7,"cape cannot shift downward from pivot");
        checks++;
    }

    private static void zeroNormals(ByteOrder order) throws Exception {
        ByteBuffer mesh=buffer(2,order);
        vertex(mesh,0,1,2,3,0,0,0);
        vertex(mesh,1,1,2,3,1,0,0);
        run(mesh,2,.25F);
        near(pos(mesh,0,0),1,"zero normal is not an outward direction");
        near(pos(mesh,0,1),2,"zero normal stays finite");
        near(pos(mesh,1,0),1.25F,"zero normal does not dilute valid normal");
        checks++;
    }

    private static void attributes(ByteOrder order) throws Exception {
        ByteBuffer backing=buffer(3,order);
        backing.position(STRIDE);
        ByteBuffer mesh=backing.slice().order(order);
        vertex(mesh,0,1,2,3,0,1,0);
        byte[] original=bytes(backing);
        int beforePosition=mesh.position(),beforeLimit=mesh.limit();
        run(mesh,1,.2F);
        byte[] after=bytes(backing);
        for(int i=0;i<after.length;i++) {
            if(i>=STRIDE+POSITION && i<STRIDE+POSITION+12) continue;
            if(original[i]!=after[i]) throw new AssertionError("color/UV/light/normal or unrelated vertex mutated at "+i);
        }
        near(pos(mesh,0,1),2.2F,"sliced buffer position offset");
        if(mesh.position()!=beforePosition||mesh.limit()!=beforeLimit) throw new AssertionError("caller buffer cursor changed");
        byte[] once=bytes(backing);
        run(mesh,1,0);
        run(mesh,1,Float.NaN);
        expand.invoke(null,mesh,1,STRIDE,POSITION,-1,.2F);
        if(!Arrays.equals(once,bytes(backing)))throw new AssertionError("invalid displacement/format must be a no-op");
        checks+=3;
    }

    private static ByteBuffer buffer(int count,ByteOrder order) {
        ByteBuffer b=ByteBuffer.allocateDirect(count*STRIDE).order(order);
        for(int i=0;i<b.capacity();i++)b.put(i,(byte)0x5A);
        return b;
    }
    private static void preparedRadii(ByteOrder order) throws Exception {
        Method prepare=expand.getDeclaringClass().getMethod("prepare",ByteBuffer.class,int.class,int.class,int.class,int.class);
        ByteBuffer mesh=buffer(2,order);
        vertex(mesh,0,3,7,0,0,0,1);
        vertex(mesh,1,3,7,0,0,0,-1);
        Object prepared=prepare.invoke(null,mesh,2,STRIDE,POSITION,NORMAL);
        Method apply=prepared.getClass().getMethod("apply",ByteBuffer.class,float.class);
        apply.invoke(prepared,mesh,.5F);
        apply.invoke(prepared,mesh,.125F);
        near(pos(mesh,0,2),.125F,"inner cape radius must not accumulate outer radius");
        near(pos(mesh,1,2),-.125F,"opposite cape normal survives repeated passes");
        apply.invoke(prepared,mesh,0F);
        near(pos(mesh,0,2),0,"zero prepared radius restores captured positions");
        vertex(mesh,0,10,20,30,0,0,1);
        Object nextFrame=prepare.invoke(null,mesh,2,STRIDE,POSITION,NORMAL);
        apply.invoke(nextFrame,mesh,.25F);
        near(pos(mesh,0,0),10,"new animated capture cannot reuse previous positions");
        near(pos(mesh,0,2),30.25F,"new animated capture uses its own normals/position");
        checks+=3;
    }
    private static void vertex(ByteBuffer b,int i,float x,float y,float z,float nx,float ny,float nz) {
        int p=i*STRIDE+POSITION,n=i*STRIDE+NORMAL;
        b.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z);
        b.put(n,(byte)Math.round(nx*127)).put(n+1,(byte)Math.round(ny*127)).put(n+2,(byte)Math.round(nz*127));
    }
    private static float pos(ByteBuffer b,int i,int axis){return b.getFloat(i*STRIDE+POSITION+axis*4);}
    private static void run(ByteBuffer b,int count,float distance)throws Exception{expand.invoke(null,b,count,STRIDE,POSITION,NORMAL,distance);}
    private static byte[] bytes(ByteBuffer b){byte[] out=new byte[b.capacity()];for(int i=0;i<out.length;i++)out[i]=b.get(i);return out;}
    private static void near(float actual,float expected,String label){if(Math.abs(actual-expected)>0.00001F||!Float.isFinite(actual))throw new AssertionError(label+": expected "+expected+", actual "+actual);}
}
