import com.mojang.blaze3d.vertex.*;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Arrays;
import ru.gora.musicbattle.client.GlowMeshExpansion;
import ru.gora.musicbattle.client.SmileyBallModel;
import java.lang.reflect.Constructor;

/** CPU/allocation contract, actual animated-model vertices; no game or GPU workload. */
public final class GlowMeshPerformanceChecks {
    private static final int PASSES=4, WARMUP=300, SAMPLES=600;
    private static final float[] RADII={.080F,.060F,.040F,.020F};
    private static final ThreadMXBean ALLOC=(ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static volatile float consumed;
    private static Method prepare,apply;
    private static Constructor<?> batchConstructor;
    private static Method batchMesh;
    private static final SmileyBallModel MODEL=new SmileyBallModel();

    public static void main(String[] args)throws Exception {
        try {
            prepare=GlowMeshExpansion.class.getMethod("prepare",ByteBuffer.class,int.class,int.class,int.class,int.class);
            apply=prepare.getReturnType().getMethod("apply",ByteBuffer.class,float.class);
        }catch(NoSuchMethodException ignored){}
        try {
            Class<?> batch=Class.forName("ru.gora.musicbattle.client.ClientModelGlowEngine$ShellBatch");
            batchConstructor=batch.getDeclaredConstructor(MeshData.class);batchConstructor.setAccessible(true);
            batchMesh=batch.getDeclaredMethod("mesh",float.class,int.class);batchMesh.setAccessible(true);
        }catch(ClassNotFoundException ignored){}
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        try(ByteBufferBuilder storage=new ByteBufferBuilder(256*1024)) {
            if(batchConstructor!=null)checkBatch(storage);
            for(int i=0;i<WARMUP;i++) render(storage);
            long id=Thread.currentThread().threadId(),allocated=ALLOC.getThreadAllocatedBytes(id),time=System.nanoTime();
            for(int i=0;i<SAMPLES;i++)render(storage);
            double nanos=(System.nanoTime()-time)/(double)SAMPLES;
            long bytes=(ALLOC.getThreadAllocatedBytes(id)-allocated)/SAMPLES;
            System.out.printf(Locale.ROOT,"Glow %s: actual SmileyBallModel, %d passes: %.3f ms/model, %,d bytes/model; 32 highlighted models: %.3f ms + %.2f MiB/frame CPU-only%n",
                    prepare==null?"legacy":batchConstructor==null?"prepared-helper":"production-batch",PASSES,nanos/1e6,bytes,nanos*32/1e6,bytes*32/1048576.0);
            if(args.length==0 || !args[0].equals("--baseline")) {
                if(bytes>160_000)throw new AssertionError("4-pass glow allocates "+bytes+" bytes/model; budget 160000 (no object graph rebuilt per pass)");
                if(prepare==null)throw new AssertionError("Multi-pass glow must prepare animated geometry once per render, not weld each pass");
                if(batchConstructor==null)throw new AssertionError("Production renderer still captures/welds separately for each shell pass");
            }
        }
    }
    private static void render(ByteBufferBuilder storage)throws Exception {
        if(batchConstructor!=null)try(MeshData capture=capture(storage);
                                     AutoCloseable batch=(AutoCloseable)batchConstructor.newInstance(capture)) {
            for(float radius:RADII)try(MeshData mesh=(MeshData)batchMesh.invoke(batch,radius,127)) {
                consumed=mesh.vertexBuffer().getFloat(0);
            }
        }else if(prepare==null) {
            for(float radius:RADII)try(MeshData mesh=capture(storage)) {
                var f=mesh.drawState().format();
                GlowMeshExpansion.expand(mesh.vertexBuffer(),mesh.drawState().vertexCount(),f.getVertexSize(),
                        f.getOffset(VertexFormatElement.POSITION),f.getOffset(VertexFormatElement.NORMAL),radius);
                consumed=mesh.vertexBuffer().getFloat(0);
            }
        }else try(MeshData mesh=capture(storage)) {
            var f=mesh.drawState().format();ByteBuffer vertices=mesh.vertexBuffer();
            Object prepared=prepare.invoke(null,vertices,mesh.drawState().vertexCount(),f.getVertexSize(),
                    f.getOffset(VertexFormatElement.POSITION),f.getOffset(VertexFormatElement.NORMAL));
            for(float radius:RADII) {
                apply.invoke(prepared,vertices,radius);
                consumed=vertices.getFloat(0);
            }
        }
    }
    private static MeshData capture(ByteBufferBuilder storage) {
        BufferBuilder b=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
        PoseStack pose=new PoseStack();pose.translate(1.25,2.5,-8);
        MODEL.renderToBuffer(pose,b,0xF000F0,0,-1);
        return b.buildOrThrow();
    }
    private static void checkBatch(ByteBufferBuilder storage)throws Exception {
        try(MeshData source=capture(storage); AutoCloseable batch=(AutoCloseable)batchConstructor.newInstance(source)) {
            byte[] before=bytes(source.vertexBuffer());
            var f=source.drawState().format();int stride=f.getVertexSize(),color=f.getOffset(VertexFormatElement.COLOR);
            int p=f.getOffset(VertexFormatElement.POSITION);
            int[] alphas={0,128,255};float[] radii={.25F,.08F,.02F};
            for(int pass=0;pass<radii.length;pass++)try(MeshData shell=(MeshData)batchMesh.invoke(batch,radii[pass],alphas[pass])) {
                ByteBuffer actual=shell.vertexBuffer(),original=source.vertexBuffer();
                for(int vertex=0;vertex<source.drawState().vertexCount();vertex++) {
                    int base=vertex*stride;double distance=0;
                    for(int a=0;a<3;a++) {double d=actual.getFloat(base+p+a*4)-original.getFloat(base+p+a*4);distance+=d*d;}
                    if(Math.abs(Math.sqrt(distance)-radii[pass])>.00001)throw new AssertionError("Shell radius accumulated/drifted between passes");
                    for(int offset=0;offset<stride;offset++) {
                        if(offset>=p&&offset<p+12)continue;
                        int expected=offset==color+3?alphas[pass]:(original.get(base+offset)&255);
                        if((actual.get(base+offset)&255)!=expected)throw new AssertionError("Shell RGB/UV/normal/light/alpha changed at "+offset);
                    }
                }
            }
            if(!Arrays.equals(before,bytes(source.vertexBuffer())))throw new AssertionError("Drawing glow mutated/closed original model geometry");
        }
        System.out.println("Production ShellBatch PASS: independent radii, per-pass alpha, original mesh/attributes/lifetime preserved");
    }
    private static byte[] bytes(ByteBuffer b){byte[] out=new byte[b.remaining()];b.duplicate().get(out);return out;}
}
