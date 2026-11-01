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
import java.util.LinkedHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

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
        checkWrappedFlush();
        checkIrisMasks();
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
    /**
     * Fish-eye and pulse take the highlighted-model mask under a shader pack as the block outline does. They used
     * to skip it whenever Iris had a pack on, so the warp bent highlighted players and mobs there and nowhere else. Every
     * full-screen pass that reads ModelMaskSampler builds its mask the same way, with no Iris condition; the in-game proof
     * under Iris is the hidden run, not this.
     */
    private static void checkIrisMasks() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/ru/gora/musicbattle/client/ClientFxShaderEngine.java")).replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        int uses = source.split("setSampler\\(\"ModelMaskSampler\"", -1).length - 1;
        int built = source.split("int modelMaskTexture = buildModelMask\\(main\\);", -1).length - 1;
        if (uses != 2 || built != 2) {
            throw new AssertionError("fish-eye and pulse must build the model mask unconditionally, as the outline does: "
                    + uses + " passes sample it, " + built + " build it without a condition");
        }
        if (source.contains("isIrisShaderPackInUse() ? 0 : buildModelMask")) {
            throw new AssertionError("a full-screen pass still drops the highlight mask under a shader pack");
        }
        System.out.println("Iris highlight mask PASS: fish-eye and pulse build the mask under a shader pack too");
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

    /**
     * While a shader pack is on, Iris hands each entity renderer a BufferSourceWrapper: a plain MultiBufferSource
     * that cannot be flushed. The glow engine flushed nothing then, so a player's or mob's body was drawn after its
     * stencil bit was switched off again, and the block outline traced every entity. The engine must flush the
     * source the wrapper hands back from getOriginal(), and must survive a wrapper that loops, fails or returns
     * nothing. Iris itself is not on this classpath: the wrappers below have the shape of its class.
     */
    private static void checkWrappedFlush() throws Exception {
        Method flush = Class.forName("ru.gora.musicbattle.client.ClientModelGlowEngine")
                .getDeclaredMethod("flushBuffers", MultiBufferSource.class);
        flush.setAccessible(true);
        try (ByteBufferBuilder shared = new ByteBufferBuilder(256)) {
            CountingSource source = new CountingSource(shared);
            flush.invoke(null, source);
            if (source.flushes != 1) throw new AssertionError("A plain buffer source must be flushed once: " + source.flushes);
            flush.invoke(null, new Wrapper(source));
            if (source.flushes != 2) throw new AssertionError("An Iris-style wrapper must flush the source it wraps: " + source.flushes);
            flush.invoke(null, new Wrapper(new Wrapper(source)));
            if (source.flushes != 3) throw new AssertionError("A wrapper of a wrapper must still reach the source: " + source.flushes);
            for (MultiBufferSource odd : new MultiBufferSource[]{new SelfWrapper(), new Wrapper(null), new ThrowingWrapper(),
                    new OddWrapper(source), type -> null}) {
                flush.invoke(null, odd);
            }
            if (source.flushes != 3) throw new AssertionError("Only real wrappers may flush a source: " + source.flushes);
        }
        System.out.println("Glow buffer flush PASS: plain sources, Iris-style wrappers and broken wrappers");
    }

    /** Counts flushes instead of drawing. */
    private static final class CountingSource extends MultiBufferSource.BufferSource {
        private int flushes;

        private CountingSource(ByteBufferBuilder shared) {
            super(shared, new LinkedHashMap<>());
        }

        @Override
        public void endBatch() {
            flushes++;
        }
    }

    /** The shape of Iris's BufferSourceWrapper. Public, as the engine looks the method up by name. */
    public static final class Wrapper implements MultiBufferSource {
        private final MultiBufferSource original;

        Wrapper(MultiBufferSource original) {
            this.original = original;
        }

        public MultiBufferSource getOriginal() {
            return original;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            throw new AssertionError("nothing is drawn here");
        }
    }

    /** A wrapper that hands back itself: the engine must not loop forever. */
    public static final class SelfWrapper implements MultiBufferSource {
        public MultiBufferSource getOriginal() {
            return this;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            throw new AssertionError("nothing is drawn here");
        }
    }

    /** A wrapper whose getOriginal() fails: a broken wrapper must not take the entity render down. */
    public static final class ThrowingWrapper implements MultiBufferSource {
        public MultiBufferSource getOriginal() {
            throw new IllegalStateException("broken wrapper");
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            throw new AssertionError("nothing is drawn here");
        }
    }

    /** A getOriginal() that is not a buffer source at all is not a wrapper. */
    public static final class OddWrapper implements MultiBufferSource {
        private final Object original;

        OddWrapper(Object original) {
            this.original = original;
        }

        public Object getOriginal() {
            return original;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            throw new AssertionError("nothing is drawn here");
        }
    }
}
