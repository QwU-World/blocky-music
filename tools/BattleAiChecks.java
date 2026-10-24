import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import ru.gora.musicbattle.data.BattleNote;

/** Real compiled AI, note judgement and holds; only construction/world packet IO is isolated. */
public final class BattleAiChecks {
    static final String MANAGER = "ru/gora/musicbattle/server/BattleManager";
    static Class<?> battleType, stateType, noteType, skillType;
    static final List<Action> actions = new ArrayList<>();
    static final List<String> failures = new ArrayList<>();
    static int checks;
    record Action(int owner, int lane, int note, boolean hit, boolean complete) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2) { isolate(Path.of(args[0]), Path.of(args[1])); return; }
        battleType = type("Battle"); stateType = type("ParticipantState");
        noteType = type("RuntimeNote"); skillType = type("MobSkill");
        chords(); denseAndHolds(); conflicts(); probabilities();
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("Battle AI PASS: " + checks + " checks (actual tickMobAi/resolveInput/tickHold; no Minecraft launch)");
    }

    // A one-note-per-tick break loses three notes here, independently of difficulty randomness.
    static void chords() throws Exception {
        for (String skill : List.of("EASY", "MEDIUM", "HARD", "GOD", "PERFECT")) {
            Fixture f = new Fixture(skill, new ConstantRandom(0));
            for (int lane = 0; lane < 4; lane++) f.note(0, lane, 10, 0);
            f.note(1, 0, 10, 0); f.note(0, 0, 20, 0);
            f.tick(10);
            check(f.number("combo") == 4, skill + " hits all four lanes on the due tick");
            check(f.number("score") == 412, skill + " scores the complete chord with real combo math");
            check(actions.size() == 4 && actions.stream().allMatch(a -> a.hit && a.complete && a.owner == 0),
                    skill + " emits one successful completion per chord note");
            check(!flag(f.notes.get(4), "started") && !flag(f.notes.get(5), "started"),
                    skill + " leaves opponent/future notes untouched");
        }
        Fixture mixed = new Fixture("EASY", new SequenceRandom(.1f, .9f, .1f, .9f));
        for (int lane = 0; lane < 4; lane++) mixed.note(0, lane, 10, 0);
        mixed.tick(10);
        check(mixed.number("score") == 200 && mixed.number("mistakes") == 2 && actions.size() == 4,
                "each lane gets its own difficulty outcome and action on the same tick");
    }

    static void denseAndHolds() throws Exception {
        Fixture f = new Fixture("PERFECT", new ConstantRandom(0));
        for (int tick = 10; tick <= 18; tick += 2)
            for (int lane = 0; lane < 4; lane++) f.note(0, lane, tick, 0);
        for (int tick = 10; tick <= 18; tick++) f.tick(tick);
        check(f.number("combo") == 20 && f.number("mistakes") == 0 && actions.size() == 20,
                "dense four-lane chords preserve the two-tick same-lane lockout without losing notes");

        f = new Fixture("PERFECT", new ConstantRandom(0));
        for (int lane = 0; lane < 4; lane++) f.note(0, lane, 10, 10);
        f.tick(10);
        check(f.number("combo") == 4 && f.number("score") == 412 && actions.size() == 4
                        && actions.stream().allMatch(a -> a.hit && !a.complete), "four holds begin together");
        f.tick(15);
        check(f.number("score") == 428 && f.number("mistakes") == 0, "all simultaneous holds receive sustain score");
        f.tick(20);
        check(f.number("score") == 508 && actions.stream().filter(a -> a.hit && a.complete).count() == 4,
                "all four holds complete and receive completion score");

        f = new Fixture("GOD", new SequenceRandom(0, .005f, 0, .5f, 0, .5f, 0, .5f));
        for (int lane = 0; lane < 4; lane++) f.note(0, lane, 10, 10);
        f.tick(10); f.tick(13);
        check(f.number("mistakes") == 1 && flag(f.notes.get(0), "missedResolved")
                        && !flag(f.notes.get(1), "resolved") && !flag(f.notes.get(2), "resolved")
                        && !flag(f.notes.get(3), "resolved"), "an early release fails only its own hold lane");
    }

    static void conflicts() throws Exception {
        CountingRandom rng = new CountingRandom(0);
        Fixture f = new Fixture("PERFECT", rng);
        f.note(0, 0, 10, 0); f.note(0, 0, 10, 0); f.note(0, 1, 10, 0);
        f.tick(10); f.tick(11); f.tick(12); f.tick(17);
        check(f.number("score") == 200 && f.number("mistakes") == 1 && !flag(f.notes.get(1), "hitResolved"),
                "duplicate same-lane notes cannot turn into delayed bonus hits");
        check(rng.calls == 2, "duplicate blocked lane gets no extra difficulty rolls");

        f = new Fixture("EASY", new SequenceRandom(.9f, .1f));
        f.note(0, 0, 10, 0); f.note(0, 0, 10, 0); f.note(0, 1, 10, 0);
        f.tick(10); f.tick(12); f.tick(17);
        check(f.number("score") == 100 && f.number("mistakes") == 2
                        && !flag(f.notes.get(1), "hitResolved") && flag(f.notes.get(2), "hitResolved"),
                "a missed chord note cannot reroll through a duplicate on the same lane");

        rng = new CountingRandom(0); f = new Fixture("PERFECT", rng);
        ((int[]) get(f.state, "laneHitLockoutUntil"))[0] = 12;
        f.note(0, 0, 10, 0); f.note(0, 1, 10, 0);
        f.tick(10); f.tick(11); f.tick(12); f.tick(17);
        check(!flag(f.notes.get(0), "hitResolved") && flag(f.notes.get(1), "hitResolved") && rng.calls == 1,
                "lockout skips one note once and never delays another lane or rerolls after unlock");

        rng = new CountingRandom(0); f = new Fixture("PERFECT", rng);
        f.note(0, 0, 0, 0); f.note(0, 0, 11, 0); f.note(0, 1, 10, 0);
        f.tick(10);
        check(!flag(f.notes.get(1), "started") && flag(f.notes.get(2), "hitResolved") && rng.calls == 1,
                "an expired claimed note cannot fall back to stealing a future same-lane note");

        rng = new CountingRandom(0); f = new Fixture("PERFECT", rng);
        Object hold = f.note(0, 0, 10, 10);
        f.note(0, 0, 12, 0); f.note(0, 1, 12, 0); f.note(0, 0, 20, 0);
        f.tick(10); f.tick(12); f.tick(13);
        check(getArray(f.state, "activeHolds", 0) == hold && ((boolean[]) get(f.state, "laneDown"))[0],
                "a tap inside a held lane cannot release or replace that hold");
        f.tick(19); f.tick(20);
        check(flag(hold, "hitResolved") && flag(hold, "resolved") && flag(f.notes.get(3), "hitResolved"),
                "a note exactly at the hold end becomes playable after completion");
        check(f.number("mistakes") == 1 && rng.calls == 4, "overlapping tap misses once without reroll boosts");
    }

    static void probabilities() throws Exception {
        for (String skill : List.of("EASY", "MEDIUM", "HARD", "GOD", "PERFECT")) {
            float threshold = switch (skill) { case "EASY" -> .48f; case "MEDIUM" -> .68f; case "HARD" -> .82f; case "GOD" -> .998f; default -> 1f; };
            for (float sample : new float[]{threshold - .001f, threshold}) {
                Fixture f = new Fixture(skill, new ConstantRandom(sample)); f.note(0, 0, 10, 0); f.tick(10);
                check(flag(f.notes.get(0), "hitResolved") == (sample < threshold), skill + " hit boundary sample=" + sample);
            }
        }
        for (float release : new float[]{.0f, .009f, .01f, .011f, .029f}) {
            Fixture f = new Fixture("GOD", new SequenceRandom(0, release)); f.note(0, 0, 10, 10);
            f.tick(10); f.tick(13);
            check(flag(f.notes.get(0), "missedResolved") == (release < .01f), "GOD hold-release boundary sample=" + release);
        }
        for (String skill : List.of("EASY", "MEDIUM", "HARD", "PERFECT")) {
            float threshold = switch (skill) { case "EASY" -> .55f; case "MEDIUM" -> .28f; case "HARD" -> .14f; default -> 0f; };
            for (float release : new float[]{Math.max(0, threshold - .001f), threshold, .999f}) {
                Fixture f = new Fixture(skill, new SequenceRandom(0, release)); f.note(0, 0, 10, 10);
                f.tick(10); f.tick(13);
                check(flag(f.notes.get(0), "missedResolved") == (release < threshold), skill + " retains release outcome at " + release);
            }
        }
        Random fixed = new Random(918233L);
        int hitCount = 0, releaseCount = 0;
        for (int i = 0; i < 20000; i++) {
            Fixture f = new Fixture("GOD", fixed); f.note(0, 0, 10, 10); f.tick(10);
            if (flag(f.notes.get(0), "started")) { hitCount++; if (!((boolean[]) get(f.state, "laneDown"))[0]) releaseCount++; }
        }
        check(hitCount >= 19925 && hitCount <= 19988, "fixed-seed GOD hit rate remains approximately 99.8%, got " + hitCount + "/20000");
        check(releaseCount >= 150 && releaseCount <= 250, "fixed-seed GOD release rate remains approximately 1%, got " + releaseCount + "/" + hitCount);
        System.out.println("GOD fixed-seed outcomes: hits=" + hitCount + "/20000, early releases=" + releaseCount + "/" + hitCount);
    }

    static final class Fixture {
        final Object battle, state; final List<Object> notes = new ArrayList<>();
        Fixture(String skill, Random random) throws Exception {
            actions.clear(); battle = create(battleType); state = create(stateType);
            set(state, "owner", 0); set(state, "ai", true); set(state, "speed", 1f);
            set(state, "laneDown", new boolean[4]); set(state, "laneHitLockoutUntil", new int[4]);
            set(state, "activeHolds", Array.newInstance(noteType, 4));
            set(battle, "leftState", state); set(battle, "rightState", state);
            set(battle, "runtimeNotes", notes); set(battle, "random", random);
            Field skillField = skillType.getDeclaredField(skill); skillField.setAccessible(true); set(battle, "mobSkill", skillField.get(null));
        }
        Object note(int owner, int lane, int tick, int duration) throws Exception {
            Constructor<?> ctor = noteType.getDeclaredConstructor(int.class, BattleNote.class); ctor.setAccessible(true);
            Object note = ctor.newInstance(notes.size(), new BattleNote(owner, lane, tick, duration)); notes.add(note); return note;
        }
        void tick(int tick) throws Exception {
            set(battle, "playTicks", tick);
            invoke(battle, "tickHold", stateType, state); invoke(battle, "tickMobAi", stateType, state);
            Method miss = battleType.getDeclaredMethod("resolveMissedNotes"); miss.setAccessible(true); miss.invoke(battle);
        }
        int number(String field) throws Exception { return (int) get(state, field); }
    }
    static class ConstantRandom extends Random { final float value; ConstantRandom(float value) { this.value = value; } @Override public float nextFloat() { return value; } }
    static final class CountingRandom extends ConstantRandom { int calls; CountingRandom(float value) { super(value); } @Override public float nextFloat() { calls++; return super.nextFloat(); } }
    static final class SequenceRandom extends Random { final float[] values; int index; SequenceRandom(float... values) { this.values = values; } @Override public float nextFloat() { if (index >= values.length) throw new AssertionError("unexpected additional random roll"); return values[index++]; } }
    static Class<?> type(String name) throws Exception { return Class.forName(MANAGER.replace('/', '.') + "$" + name); }
    static Object create(Class<?> type) throws Exception { Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true); return ctor.newInstance(); }
    static Object get(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    static Object getArray(Object object, String name, int index) throws Exception { return Array.get(get(object, name), index); }
    static boolean flag(Object object, String name) throws Exception { return (boolean) get(object, name); }
    static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    static void invoke(Object object, String name, Class<?> argType, Object arg) throws Exception { Method method = object.getClass().getDeclaredMethod(name, argType); method.setAccessible(true); method.invoke(object, arg); }
    static void check(boolean condition, String message) { checks++; if (!condition) failures.add(message); }
    public static void action(Object ignored, int owner, int lane, int note, boolean hit, boolean complete) { actions.add(new Action(owner, lane, note, hit, complete)); }

    static void isolate(Path compiled, Path output) throws Exception {
        transform(compiled, output, MANAGER, Set.of("resolveInput", "canHitClaimedNote", "shouldBreakComboForBadPress", "earlyHitWindowTicks", "timingScore", "animateParticipant"), Set.of(), false);
        transform(compiled, output, MANAGER + "$Battle", Set.of("tickMobAi", "tickHold", "resolveMissedNotes", "state", "noteByIndex"),
                Set.of("runtimeNotes", "random", "mobSkill", "leftState", "rightState", "playTicks"), true);
        transform(compiled, output, MANAGER + "$ParticipantState", Set.of(),
                Set.of("owner", "ai", "speed", "laneDown", "laneHitLockoutUntil", "activeHolds", "score", "combo", "maxCombo", "mistakes", "bopTicks"), true);
    }
    static void transform(Path compiled, Path output, String name, Set<String> methods, Set<String> fields, boolean constructor) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(compiled.resolve(name + ".class")));
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                return fields.contains(field) ? super.visitField(access & ~Opcodes.ACC_FINAL, field, descriptor, signature, value) : null;
            }
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                if (!methods.contains(method)) return null;
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, descriptor, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String target, String desc, boolean iface) {
                        if (owner.equals(MANAGER) && target.equals("broadcastAction"))
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "BattleAiChecks", "action", "(Ljava/lang/Object;IIIZZ)V", false);
                        else super.visitMethodInsn(opcode, owner, target, desc, iface);
                    }
                };
            }
            @Override public void visitEnd() {
                if (constructor) {
                    MethodVisitor ctor = super.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
                    ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        Path target = output.resolve(name + ".class"); Files.createDirectories(target.getParent()); Files.write(target, writer.toByteArray());
    }
}
