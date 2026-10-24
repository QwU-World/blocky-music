import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.*;

/** Executes the real compiled server selector; substitutes only world/player reads. */
public final class BattleSpeakerRoutingChecks {
    static final String MANAGER = "ru/gora/musicbattle/server/BattleManager";
    static final Map<BlockPos, float[]> SPEAKERS = new HashMap<>();
    static Vec3 listener = new Vec3(0, 1, 0);
    static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 2) { isolate(Path.of(args[0]), Path.of(args[1])); return; }
        Class<?> manager = Class.forName(MANAGER.replace('/', '.'));
        Class<?> keyType = Class.forName(MANAGER.replace('/', '.') + "$BattleAudioSourceKey");
        Method selector;
        boolean acceptsSilent;
        try {
            selector = manager.getDeclaredMethod("nearestAudioSource", ServerPlayer.class, Vec3.class, Set.class, keyType, boolean.class);
            acceptsSilent = true;
        } catch (NoSuchMethodException oldSelector) {
            selector = manager.getDeclaredMethod("nearestAudioSource", ServerPlayer.class, Vec3.class, Set.class, keyType);
            acceptsSilent = false;
        }
        selector.setAccessible(true);
        BlockPos speaker = new BlockPos(10, 0, 0);
        SPEAKERS.put(speaker, new float[]{64, .1f});
        Object selected = select(selector, acceptsSilent, Set.of(speaker), null, false);
        check(id(selected) == speaker.asLong(), "a nearby battle participant cannot replace a quieter connected speaker");
        Object speakerKey = property(selected, "key");
        SPEAKERS.clear();
        selected = select(selector, acceptsSilent, Set.of(), null, false);
        check(id(selected) == Long.MIN_VALUE, "without speakers the battle still emits music");
        Object coreKey = property(selected, "key");
        SPEAKERS.put(speaker, new float[]{64, .1f});
        check(id(select(selector, acceptsSilent, Set.of(speaker), coreKey, true)) == speaker.asLong(),
                "adding a speaker replaces the previously retained core source");
        SPEAKERS.put(speaker, new float[]{2, 1});
        check(select(selector, acceptsSilent, Set.of(speaker), null, false) == null,
                "new listeners outside speaker coverage cannot hear the battle core");
        check(id(select(selector, acceptsSilent, Set.of(speaker), coreKey, true)) == speaker.asLong(),
                "existing listeners switch to a silent speaker instead of keeping an audible stale core");
        check(id(select(selector, acceptsSilent, Set.of(speaker), null, true)) == speaker.asLong(),
                "existing listeners with a cleared source key also receive the silent speaker");
        SPEAKERS.put(speaker, new float[]{64, 0});
        check(id(select(selector, acceptsSilent, Set.of(speaker), null, false)) == speaker.asLong(),
                "a muted speaker does not reactivate participant audio");
        SPEAKERS.put(speaker, new float[]{64, .1f});
        BlockPos louder = new BlockPos(5, 0, 0);
        SPEAKERS.put(louder, new float[]{64, 1});
        check(id(select(selector, acceptsSilent, Set.of(speaker, louder), speakerKey, true)) == louder.asLong(),
                "speaker-to-speaker loudness arbitration still works");
        SPEAKERS.clear();
        check(id(select(selector, acceptsSilent, Set.of(), speakerKey, true)) == Long.MIN_VALUE,
                "removing the last speaker restores ordinary battle audio");
        listener = new Vec3(100, 1, 0);
        check(select(selector, acceptsSilent, Set.of(), null, false) == null,
                "no-speaker audio keeps its original range");
        Object silentCore = select(selector, acceptsSilent, Set.of(), speakerKey, true);
        check(id(silentCore) == Long.MIN_VALUE && (float) property(silentCore, "effectiveVolume") == 0f,
                "removing the last speaker outside core coverage silences the old speaker channel");
        System.out.println("Battle speaker routing PASS: " + checks + " (real selector, isolated world reads; not an in-game audio test)");
    }

    static Object select(Method method, boolean hasSilent, Set<BlockPos> speakers, Object current, boolean listening) throws Exception {
        return hasSilent ? method.invoke(null, null, Vec3.ZERO, speakers, current, listening)
                : method.invoke(null, null, Vec3.ZERO, speakers, current);
    }
    static Object property(Object value, String name) throws Exception {
        if (value == null) return null;
        Method method = value.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return method.invoke(value);
    }
    static long id(Object value) throws Exception {
        return value == null ? Long.MAX_VALUE : (long) property(property(value, "key"), "id");
    }
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    public static double distance(ServerPlayer ignored, Vec3 source) { return listener.distanceToSqr(source); }
    public static ServerLevel level(ServerPlayer ignored) { return null; }
    public static float radius(ServerLevel ignored, BlockPos pos) { return SPEAKERS.get(pos)[0]; }
    public static float volume(ServerLevel ignored, BlockPos pos) { return SPEAKERS.get(pos)[1]; }
    public static BlockEntity blockEntity(ServerLevel ignored, BlockPos pos) { return null; }

    static void isolate(Path compiled, Path output) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(compiled.resolve(MANAGER + ".class")));
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) { return null; }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("nearestAudioSource") && !name.equals("effectiveSourceVolume")) return null;
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean iface) {
                        String bridge = null;
                        if (owner.equals("net/minecraft/server/level/ServerPlayer")) {
                            if (method.equals("distanceToSqr")) bridge = "distance";
                            if (method.equals("serverLevel")) bridge = "level";
                        } else if (owner.equals("ru/gora/musicbattle/server/SpeakerNetwork")) {
                            if (method.equals("soundRadius")) bridge = "radius";
                            if (method.equals("volume")) bridge = "volume";
                        } else if (owner.equals("net/minecraft/server/level/ServerLevel") && method.equals("getBlockEntity")) bridge = "blockEntity";
                        if (bridge == null) super.visitMethodInsn(opcode, owner, method, desc, iface);
                        else super.visitMethodInsn(Opcodes.INVOKESTATIC, "BattleSpeakerRoutingChecks", bridge,
                                opcode == Opcodes.INVOKESTATIC ? desc : "(L" + owner + ";" + desc.substring(1), false);
                    }
                };
            }
        }, 0);
        Path target = output.resolve(MANAGER + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }
}
