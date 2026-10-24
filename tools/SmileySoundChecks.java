import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Compiled entity/event behavior plus the real sound-definition resource; no Minecraft window. */
public final class SmileySoundChecks {
    private static final String ENTITY_CLASS = "ru.gora.musicbattle.entity.SmileyBallEntity";
    private static final String SOUNDS_CLASS = "ru.gora.musicbattle.registry.ModSounds";
    private static final String MOD_SOUNDS_OWNER = "ru/gora/musicbattle/registry/ModSounds";
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(args[0]);
        overridesUseRegisteredEvents();
        registeredEventIdsMatchDefinitions();
        definitionsMapBothEventsToOneUnmodifiedAsset(projectDir);

        System.out.println("Smiley sound checks: " + checks);
        FAILURES.forEach(System.err::println);
        if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " failed checks");
        System.out.println("Smiley sound behavior PASS");
    }

    private static void overridesUseRegisteredEvents() throws Exception {
        Class<?> entityClass = Class.forName(ENTITY_CLASS, false, SmileySoundChecks.class.getClassLoader());
        Method hurt = declaredMethod(entityClass, "getHurtSound", DamageSource.class);
        Method death = declaredMethod(entityClass, "getDeathSound");
        check(hurt != null && hurt.getReturnType() == SoundEvent.class && Modifier.isProtected(hurt.getModifiers()),
                "SmileyBallEntity declares the protected hurt-sound override");
        check(death != null && death.getReturnType() == SoundEvent.class && Modifier.isProtected(death.getModifiers()),
                "SmileyBallEntity declares the protected death-sound override");

        try (InputStream input = entityClass.getResourceAsStream("/ru/gora/musicbattle/entity/SmileyBallEntity.class")) {
            check(input != null, "compiled SmileyBallEntity bytecode is available");
            if (input == null) return;
            Class<?> fixtureClass = isolateOverrideBodies(input);
            Object fixture = fixtureClass.getConstructor().newInstance();
            Method isolatedHurt = fixtureClass.getDeclaredMethod("getHurtSound", DamageSource.class);
            Method isolatedDeath = fixtureClass.getDeclaredMethod("getDeathSound");
            isolatedHurt.setAccessible(true);
            isolatedDeath.setAccessible(true);
            SoundEvent hurtResult = (SoundEvent) isolatedHurt.invoke(fixture, new Object[] {null});
            SoundEvent deathResult = (SoundEvent) isolatedDeath.invoke(fixture);
            check(hurtResult == BoundSounds.SMILEY_BALL_HURT_EVENT,
                    "executed hurt override returns its bound registered event");
            check(deathResult == BoundSounds.SMILEY_BALL_DEATH_EVENT,
                    "executed death override returns its bound registered event");
            check(hurtResult.getLocation().toString().equals("music_battle:smiley_ball_hurt"),
                    "executed hurt override returns the hurt resource ID");
            check(deathResult.getLocation().toString().equals("music_battle:smiley_ball_death"),
                    "executed death override returns the death resource ID");
        }
    }

    private static Class<?> isolateOverrideBodies(InputStream input) throws Exception {
        String fixtureName = "SmileySoundOverrideFixture";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, fixtureName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        int[] copied = {0};
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                boolean hurt = name.equals("getHurtSound")
                        && descriptor.equals("(Lnet/minecraft/world/damagesource/DamageSource;)Lnet/minecraft/sounds/SoundEvent;");
                boolean death = name.equals("getDeathSound")
                        && descriptor.equals("()Lnet/minecraft/sounds/SoundEvent;");
                if (!hurt && !death) return null;
                copied[0]++;
                MethodVisitor target = writer.visitMethod(Opcodes.ACC_PROTECTED, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        String replacementOwner = opcode == Opcodes.GETSTATIC && owner.equals(MOD_SOUNDS_OWNER)
                                ? "SmileySoundChecks$BoundSounds" : owner;
                        super.visitFieldInsn(opcode, replacementOwner, fieldName, fieldDescriptor);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        writer.visitEnd();
        check(copied[0] == 2, "both compiled override bodies were isolated for execution");
        return MethodHandles.lookup().defineClass(writer.toByteArray());
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static void registeredEventIdsMatchDefinitions() throws Exception {
        String resource = "/" + SOUNDS_CLASS.replace('.', '/') + ".class";
        try (InputStream input = SmileySoundChecks.class.getResourceAsStream(resource)) {
            check(input != null, "compiled ModSounds bytecode is available");
            if (input == null) return;
            ModSoundsVisitor visitor = new ModSoundsVisitor();
            new ClassReader(input).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            check("smiley_ball_hurt".equals(visitor.names.get("SMILEY_BALL_HURT")),
                    "hurt holder registers the smiley_ball_hurt event ID");
            check("smiley_ball_death".equals(visitor.names.get("SMILEY_BALL_DEATH")),
                    "death holder registers the smiley_ball_death event ID");
            check(visitor.createsVariableRangeEvent && visitor.usesDeferredRegister,
                    "sound registration creates variable-range SoundEvents through DeferredRegister");
        }
    }

    private static void definitionsMapBothEventsToOneUnmodifiedAsset(Path projectDir) throws Exception {
        Path definitions = projectDir.resolve("src/main/resources/assets/music_battle/sounds.json");
        check(Files.isRegularFile(definitions), "sounds.json exists");
        if (!Files.isRegularFile(definitions)) return;
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(definitions)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        definition(root, "smiley_ball_hurt", "subtitles.music_battle.smiley_ball_hurt");
        definition(root, "smiley_ball_death", "subtitles.music_battle.smiley_ball_death");
    }

    private static void definition(JsonObject root, String eventName, String subtitleKey) {
        JsonObject definition = root.has(eventName) && root.get(eventName).isJsonObject()
                ? root.getAsJsonObject(eventName) : null;
        check(definition != null, eventName + " has a sound definition");
        if (definition == null) return;
        check(subtitleKey.equals(stringValue(definition.get("subtitle"))),
                eventName + " exposes the expected subtitle key");
        check(definition.has("sounds") && definition.getAsJsonArray("sounds").size() == 1,
                eventName + " maps to exactly one supplied sound");
        if (!definition.has("sounds") || definition.getAsJsonArray("sounds").size() != 1) return;
        JsonElement entry = definition.getAsJsonArray("sounds").get(0);
        String name = entry.isJsonPrimitive() ? entry.getAsString()
                : stringValue(entry.getAsJsonObject().get("name"));
        check("music_battle:entity/smiley_damage".equals(name),
                eventName + " maps to the shared smiley_damage asset");
        check(!entry.isJsonObject() || !entry.getAsJsonObject().has("pitch"),
                eventName + " does not modify the supplied pitch");
    }

    private static String stringValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }

    public static final class BoundSounds {
        public static final SoundEvent SMILEY_BALL_HURT_EVENT = SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("music_battle", "smiley_ball_hurt"));
        public static final SoundEvent SMILEY_BALL_DEATH_EVENT = SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("music_battle", "smiley_ball_death"));
        public static final Supplier<SoundEvent> SMILEY_BALL_HURT = () -> SMILEY_BALL_HURT_EVENT;
        public static final Supplier<SoundEvent> SMILEY_BALL_DEATH = () -> SMILEY_BALL_DEATH_EVENT;

        private BoundSounds() {
        }
    }

    private static final class ModSoundsVisitor extends ClassVisitor {
        private final Map<String, String> names = new HashMap<>();
        private boolean createsVariableRangeEvent;
        private boolean usesDeferredRegister;

        private ModSoundsVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.equals("<clinit>")) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastString;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String string) lastString = string;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.PUTSTATIC && owner.equals(MOD_SOUNDS_OWNER)
                                && (fieldName.equals("SMILEY_BALL_HURT") || fieldName.equals("SMILEY_BALL_DEATH"))) {
                            names.put(fieldName, lastString);
                        }
                    }
                };
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor,
                                            boolean isInterface) {
                    if (owner.equals("net/minecraft/sounds/SoundEvent")
                            && methodName.equals("createVariableRangeEvent")) createsVariableRangeEvent = true;
                    if (owner.equals("net/neoforged/neoforge/registries/DeferredRegister")
                            && methodName.equals("register")) usesDeferredRegister = true;
                }
            };
        }
    }
}
