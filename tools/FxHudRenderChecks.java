import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import com.google.gson.JsonObject;
import ru.gora.musicbattle.data.*;

/** Executes the compiled preview render branch; replaces only GPU drawing sinks with counters. */
public final class FxHudRenderChecks {
    private static final List<String> draws = new ArrayList<>();
    public static void drawn(String name) { draws.add(name); }
    public static void run() throws Exception {
        String source = "ru/gora/musicbattle/client/TrackGameplayPreviewScreen";
        String probe = "GameplayHudBranchProbe";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, probe, null, "java/lang/Object", null);
        for (String[] field : new String[][]{{"effects","Ljava/util/List;"},{"bpm","I"},{"disableStageBars","Z"}})
            writer.visitField(Opcodes.ACC_PUBLIC, field[0], field[1], null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD,0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0,0); init.visitEnd();
        try (var input = FxHudRenderChecks.class.getClassLoader().getResourceAsStream(source + ".class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if (!name.equals("render")) return null;
                    MethodVisitor output = writer.visitMethod(Opcodes.ACC_PUBLIC,name,descriptor,null,null);
                    return new MethodVisitor(Opcodes.ASM9, output) {
                        @Override public void visitFieldInsn(int opcode,String owner,String field,String desc) {
                            super.visitFieldInsn(opcode,owner.equals(source)?probe:owner,field,desc);
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean iface) {
                            boolean localDraw = owner.equals(source) && method.startsWith("draw");
                            boolean fxDraw = owner.equals("ru/gora/musicbattle/client/ClientTrackEffects") && method.startsWith("render");
                            boolean controls = owner.equals("net/minecraft/client/gui/screens/Screen") && method.equals("render");
                            boolean clock = owner.equals(source) && method.equals("currentTick");
                            if (!localDraw && !fxDraw && !controls && !clock) {
                                super.visitMethodInsn(opcode,owner,method,desc,iface); return;
                            }
                            Type[] args = Type.getArgumentTypes(desc);
                            for (int i=args.length-1;i>=0;i--) super.visitInsn(args[i].getSize()==2?Opcodes.POP2:Opcodes.POP);
                            if (opcode!=Opcodes.INVOKESTATIC) super.visitInsn(Opcodes.POP);
                            if (clock) { super.visitLdcInsn(15F); return; }
                            super.visitLdcInsn(controls?"editorControls":method);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"FxHudRenderChecks","drawn","(Ljava/lang/String;)V",false);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        writer.visitEnd();
        Class<?> type = MethodHandles.lookup().defineClass(writer.toByteArray());
        Object screen = type.getConstructor().newInstance();
        type.getField("bpm").setInt(screen,120);
        for (boolean hud : new boolean[]{false,true}) for (boolean modHud : new boolean[]{false,true}) {
            JsonObject json = new JsonObject(); json.addProperty("hideHud",hud);
            json.addProperty("hideBlockyMusicHud",modHud);
            type.getField("effects").set(screen,List.of(new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND,10,20,1)
                    .withSettings(TrackEffectSettings.readJson(json))));
            draws.clear();
            type.getMethod("render",GuiGraphics.class,int.class,int.class,float.class).invoke(screen,Probe.create(),0,0,0F);
            if (draws.contains("drawGameplayHud") == modHud || draws.contains("drawStageBars") == modHud)
                throw new AssertionError("Gameplay preview must follow only Blocky Music HUD switch while retaining editor controls");
            if (!draws.containsAll(List.of("drawGameplayScene","editorControls","renderBelowInterface","renderAboveInterface")))
                throw new AssertionError("HUD hiding must preserve preview scene, controls and both FX passes");
        }
        System.out.println("Gameplay HUD render branches passed");
    }
    private static final class Probe extends GuiGraphics {
        private PoseStack stack;
        Probe() { super(null,(MultiBufferSource.BufferSource)null); }
        static Probe create() throws Exception {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            var field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
            Probe probe = (Probe) type.getMethod("allocateInstance",Class.class).invoke(field.get(null),Probe.class);
            probe.stack = new PoseStack();
            return probe;
        }
        @Override public void flush() { }
        @Override public PoseStack pose() { return stack; }
    }
}
