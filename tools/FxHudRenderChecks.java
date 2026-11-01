import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import com.google.gson.JsonObject;
import ru.gora.musicbattle.data.*;

/** Executes the compiled preview render branch; replaces only GPU drawing sinks with counters. */
public final class FxHudRenderChecks {
    private static final List<String> draws = new ArrayList<>();
    /** Stands for the client world in the probe: a ClientLevel cannot be made without bootstrapping the registries. */
    private static final Object WORLD = new Object();
    private static boolean inWorld;
    public static void drawn(String name) { draws.add(name); }
    /** What the probe reads in place of minecraft.level: only whether there is a world matters to render. */
    public static Object worldLevel(Minecraft minecraft) { return inWorld ? WORLD : null; }
    public static void run() throws Exception {
        String source = "ru/gora/musicbattle/client/TrackGameplayPreviewScreen";
        String probe = "GameplayHudBranchProbe";
        // The copied render method reads the screen's own fields, Screen's minecraft, width and height among them.
        // The probe declares every one it reads, so a new read in render cannot break the copy.
        Map<String, String> fields = new LinkedHashMap<>(Map.of("effects", "Ljava/util/List;", "bpm", "I",
                "disableStageBars", "Z"));
        List<String> staticFields = new ArrayList<>();
        try (var input = FxHudRenderChecks.class.getClassLoader().getResourceAsStream(source + ".class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if (!name.equals("render")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode,String owner,String field,String desc) {
                            if (!owner.equals(source)) return;
                            fields.putIfAbsent(field, desc);
                            if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) staticFields.add(field);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, probe, null, "java/lang/Object", null);
        for (Map.Entry<String, String> field : fields.entrySet())
            writer.visitField(Opcodes.ACC_PUBLIC | (staticFields.contains(field.getKey()) ? Opcodes.ACC_STATIC : 0),
                    field.getKey(), field.getValue(), null, null).visitEnd();
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
                            if (opcode == Opcodes.GETFIELD && owner.equals("net/minecraft/client/Minecraft")
                                    && field.equals("level")) {
                                // Only ever compared with null in render; a use as a real world fails verification.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,"FxHudRenderChecks","worldLevel",
                                        "(Lnet/minecraft/client/Minecraft;)Ljava/lang/Object;",false);
                                return;
                            }
                            super.visitFieldInsn(opcode,owner.equals(source)?probe:owner,field,desc);
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean iface) {
                            boolean localDraw = owner.equals(source) && method.startsWith("draw");
                            boolean fxDraw = owner.equals("ru/gora/musicbattle/client/ClientTrackEffects") && method.startsWith("render");
                            boolean controls = owner.equals("net/minecraft/client/gui/screens/Screen") && method.equals("render");
                            boolean clock = owner.equals(source) && method.equals("currentTick");
                            // The fill behind a preview without a world comes from a package-private setting the
                            // probe, outside that package, may not call; any colour does for the branch.
                            boolean backdrop = owner.equals(source) && method.equals("background");
                            if (!localDraw && !fxDraw && !controls && !clock && !backdrop) {
                                super.visitMethodInsn(opcode,owner,method,desc,iface); return;
                            }
                            Type[] args = Type.getArgumentTypes(desc);
                            for (int i=args.length-1;i>=0;i--) super.visitInsn(args[i].getSize()==2?Opcodes.POP2:Opcodes.POP);
                            if (opcode!=Opcodes.INVOKESTATIC) super.visitInsn(Opcodes.POP);
                            if (clock) { super.visitLdcInsn(15F); return; }
                            if (backdrop) { super.visitLdcInsn(0xFF101018); return; }
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
        if (fields.containsKey("minecraft")) type.getField("minecraft").set(screen, Probe.allocate(Minecraft.class));
        // In a world the preview draws over it; opened from the title screen it first fills the frame itself.
        // Either way the HUD switches must act alike.
        for (boolean world : new boolean[]{true,false}) {
            inWorld = world;
            for (boolean hud : new boolean[]{false,true}) for (boolean modHud : new boolean[]{false,true}) {
                JsonObject json = new JsonObject(); json.addProperty("hideHud",hud);
                json.addProperty("hideBlockyMusicHud",modHud);
                type.getField("effects").set(screen,List.of(new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND,10,20,1)
                        .withSettings(TrackEffectSettings.readJson(json))));
                draws.clear();
                type.getMethod("render",GuiGraphics.class,int.class,int.class,float.class).invoke(screen,Probe.create(),0,0,0F);
                if (draws.contains("drawGameplayHud") == modHud || draws.contains("drawStageBars") == modHud)
                    throw new AssertionError("Gameplay preview must follow only Blocky Music HUD switch while retaining editor controls"
                            + (inWorld ? "" : " (no world)"));
                if (!draws.containsAll(List.of("drawGameplayScene","editorControls","renderBelowInterface","renderAboveInterface")))
                    throw new AssertionError("HUD hiding must preserve preview scene, controls and both FX passes"
                            + (inWorld ? "" : " (no world)"));
            }
        }
        System.out.println("Gameplay HUD render branches passed");
    }
    private static final class Probe extends GuiGraphics {
        private PoseStack stack;
        Probe() { super(null,(MultiBufferSource.BufferSource)null); }
        static Probe create() throws Exception {
            Probe probe = allocate(Probe.class);
            probe.stack = new PoseStack();
            return probe;
        }
        /** An instance without running its constructor, which would need a window, a world or a GPU. */
        static <T> T allocate(Class<T> type) throws Exception {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return type.cast(unsafeType.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
        }
        @Override public void flush() { }
        @Override public PoseStack pose() { return stack; }
        @Override public void fill(int minX, int minY, int maxX, int maxY, int color) { drawn("fill"); }
    }
}
