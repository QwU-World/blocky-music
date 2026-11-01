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
    /** One broadcast action, with the battle's tick and the mob's own tick when it was sent, and its side's combo then. */
    record Action(int owner, int lane, int note, boolean hit, boolean complete, int playTick, int aiTick, int combo) {
        /** The action without when it was sent, for comparing two runs whose mob plays at different leads. */
        String what() { return owner + "/" + lane + "/" + note + "/" + hit + "/" + complete + "@" + aiTick; }
    }

    public static void main(String[] args) throws Exception {
        // With the compiled classes and an output folder: isolate first, then run the checks in this same JVM. No
        // battle class has been loaded yet, and the output folder comes first on the class path, so the checks load
        // the isolated copies. tools/run-check.py runs only a script's first java line, and with the isolation as a
        // run of its own the checks themselves never ran there.
        if (args.length == 2) isolate(Path.of(args[0]), Path.of(args[1]));
        battleType = type("Battle"); stateType = type("ParticipantState");
        noteType = type("RuntimeNote"); skillType = type("MobSkill");
        chords(); denseAndHolds(); conflicts(); probabilities(); missesLeaveThePanel(); aheadOfTheBattle();
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
        check(mixed.number("score") == 200 && mixed.number("mistakes") == 0 && actions.size() == 2
                        && actions.stream().allMatch(Action::hit),
                "each lane gets its own difficulty outcome on the same tick: the two hits land, the two misses fall on");
        for (int tick = 11; tick <= 17; tick++) mixed.tick(tick);
        check(mixed.number("score") == 200 && mixed.number("mistakes") == 2 && actions.size() == 4
                        && actions.subList(2, 4).stream().allMatch(a -> !a.hit() && a.complete() && a.aiTick() == 17),
                "and the two misses are settled together once they have left the panel, one action each");
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

    /**
     * A mob's miss is played out like a player's. It is decided on the note's tick, as every choice of the mob is, and
     * settled where a player's own lands (ClientBattleController#resolveLocalMissedNotes): the note stays unresolved -
     * on every screen it falls on past its button - and the combo stays whole until the note has left the panel,
     * MISS_AFTER_PANEL_EXIT_TICKS later on the mob's clock; then the combo breaks, the mistake counts and one action
     * goes out, stamped with that tick. On the tick a note leaves, its miss comes before the mob's choices, as a client
     * settles its misses before the keys pressed in the same tick. The code before settled the miss on the note's own
     * tick, and the note vanished on the judgement line like a hit without its flash.
     */
    static void missesLeaveThePanel() throws Exception {
        Field exitField = Class.forName(MANAGER.replace('/', '.')).getDeclaredField("MISS_AFTER_PANEL_EXIT_TICKS");
        exitField.setAccessible(true);
        int exit = exitField.getInt(null);
        check(exit > 0, "a note leaves the panel some ticks after its own, " + exit);

        // A missed tap: whole until it leaves, then one miss, stamped with the tick it left on.
        Fixture f = new Fixture("EASY", new ConstantRandom(.9f));
        Object tap = f.note(0, 0, 10, 0);
        set(f.state, "combo", 5); set(f.state, "score", 300);
        boolean untouched = true;
        for (int tick = 10; tick < 10 + exit; tick++) {
            f.tick(tick);
            untouched &= !flag(tap, "resolved") && !flag(tap, "started") && f.number("combo") == 5
                    && f.number("mistakes") == 0 && actions.isEmpty();
        }
        check(untouched, "a missed tap stays unresolved, and the combo whole, until it has left the panel");
        f.tick(10 + exit);
        check(flag(tap, "resolved") && flag(tap, "missedResolved") && !flag(tap, "hitResolved")
                        && f.number("combo") == 0 && f.number("mistakes") == 1 && f.number("score") == 300,
                "then it is a miss: the combo broken, one mistake, no points taken");
        check(actions.size() == 1 && !actions.get(0).hit() && actions.get(0).complete()
                        && actions.get(0).aiTick() == 10 + exit && actions.get(0).combo() == 0
                        && field(tap, "resolvedAtTick") == 10 + exit,
                "one action tells everyone, stamped with the tick the note left on: " + actions);

        // The combo goes as a player's does: a hit while the missed note falls counts on the old combo, the miss
        // breaks it when the note leaves, and a hit on that very tick starts the new one.
        f = new Fixture("EASY", new SequenceRandom(.9f, .1f, .1f, .1f));
        f.note(0, 0, 10, 0); f.note(0, 1, 13, 0); f.note(0, 2, 10 + exit, 0); f.note(0, 3, 12 + exit, 0);
        set(f.state, "combo", 5);
        for (int tick = 10; tick <= 12 + exit; tick++) f.tick(tick);
        List<String> expected = List.of("1 hit@13 x6", "0 miss@" + (10 + exit) + " x0", "2 hit@" + (10 + exit) + " x1",
                "3 hit@" + (12 + exit) + " x2");
        List<String> order = actions.stream()
                .map(a -> a.note() + (a.hit() ? " hit@" : " miss@") + a.aiTick() + " x" + a.combo()).toList();
        check(order.equals(expected), "the combo runs 6, 0, 1, 2, the miss before the hit on the tick it leaves: " + order);
        check(f.number("score") == 116 + 100 + 100 && f.number("mistakes") == 1,
                "and the hit while it fell scores on the old combo, " + f.number("score"));
        // The same through Battle#tickParticipants, the mob ahead of the battle: the order is its own, not this fixture's.
        f = new Fixture("EASY", new SequenceRandom(.9f, .1f, .1f, .1f));
        f.note(0, 0, 10, 0); f.note(0, 1, 13, 0); f.note(0, 2, 10 + exit, 0); f.note(0, 3, 12 + exit, 0);
        set(f.state, "combo", 5);
        for (int playTick = 0; playTick <= 12 + exit; playTick++) f.serverTick(playTick);
        List<String> played = actions.stream()
                .map(a -> a.note() + (a.hit() ? " hit@" : " miss@") + a.aiTick() + " x" + a.combo()).toList();
        check(played.equals(expected), "Battle#tickMobSide settles the misses before its choices too: " + played);

        // A note on the lane the missed one is falling down is still hit on its own tick.
        f = new Fixture("EASY", new SequenceRandom(.9f, .1f));
        Object missed = f.note(0, 0, 10, 0);
        Object next = f.note(0, 0, 12, 0);
        for (int tick = 10; tick <= 12; tick++) f.tick(tick);
        check(flag(next, "hitResolved") && !flag(missed, "resolved") && actions.size() == 1 && actions.get(0).aiTick() == 12,
                "a same-lane note right behind a miss is hit on its tick while the miss falls on");
        for (int tick = 13; tick <= 10 + exit; tick++) f.tick(tick);
        check(flag(missed, "missedResolved") && f.number("mistakes") == 1 && actions.size() == 2,
                "and the miss is settled when it leaves");

        // A hold missed at its head falls on whole and is one miss; it never takes its lane.
        f = new Fixture("EASY", new SequenceRandom(.9f, .1f));
        Object hold = f.note(0, 0, 10, 20);
        Object inside = f.note(0, 0, 14, 0);
        for (int tick = 10; tick <= 10 + exit; tick++) f.tick(tick);
        check(flag(inside, "hitResolved") && flag(hold, "missedResolved") && !flag(hold, "started")
                        && getArray(f.state, "activeHolds", 0) == null && f.number("mistakes") == 1,
                "a hold missed at its head is one miss when it leaves, and a tap inside it is still hit");

        // While the missed note falls it is not over for the battle, so no rest is offered over it.
        f = new Fixture("EASY", new ConstantRandom(.9f));
        f.note(0, 0, 10, 0);
        boolean blocked = true;
        for (int tick = 10; tick < 10 + exit; tick++) {
            f.tick(tick);
            blocked &= f.restBlocked();
        }
        f.tick(10 + exit);
        check(blocked && !f.restBlocked(), "a falling miss blocks a rest skip until it has left the panel");

        // A broken hold still breaks on the tick the mob lets go: it was being held, not falling.
        f = new Fixture("GOD", new SequenceRandom(0, .005f));
        Object broken = f.note(0, 0, 10, 10);
        for (int tick = 10; tick <= 13; tick++) f.tick(tick);
        check(flag(broken, "missedResolved") && actions.size() == 2 && !actions.get(1).hit() && actions.get(1).aiTick() == 13,
                "a hold the mob lets go of breaks on the tick it lets go");
    }

    /**
     * A mob plays BattleManager.AI_LEAD_TICKS ahead of the battle so that its hits reach the clients before their tick.
     * That may change when it plays and nothing else. On random charts - chords, notes a tick or two apart on one
     * lane, holds, rests, notes from tick 0 - at every skill and with the same rolls, the mob sends exactly the actions
     * it sends with no lead, each stamped with the same tick of its own clock, only that many server ticks earlier;
     * it ends with the same score, combo, best combo and mistakes; every note is settled the same way; and a rest is
     * blocked on exactly the same server ticks, because a note the mob has played ahead is still to come for the
     * battle (Battle#settled).
     */
    static void aheadOfTheBattle() throws Exception {
        Field leadField = Class.forName(MANAGER.replace('/', '.')).getDeclaredField("AI_LEAD_TICKS");
        leadField.setAccessible(true);
        int lead = leadField.getInt(null);
        check(lead > 0, "the mob plays ahead of the battle, lead=" + lead);
        int runs = 0, sent = 0, oldLateRuns = 0;
        for (String skill : List.of("EASY", "MEDIUM", "HARD", "GOD", "PERFECT")) {
            for (long seed = 1; seed <= 40; seed++) {
                List<int[]> chart = randomChart(new Random(seed * 7919L + skill.hashCode()));
                int end = chart.stream().mapToInt(note -> note[1] + note[2]).max().orElse(0) + 40;
                Fixture onTime = chartFixture(skill, seed, chart);
                Fixture ahead = chartFixture(skill, seed, chart);
                List<Action> onTimeActions = new ArrayList<>(), aheadActions = new ArrayList<>();
                List<Boolean> onTimeBlocked = new ArrayList<>(), aheadBlocked = new ArrayList<>();
                for (int playTick = -40; playTick <= end; playTick++) {
                    actions.clear(); onTime.oldServerTick(playTick); onTimeActions.addAll(actions);
                    actions.clear(); ahead.serverTick(playTick); aheadActions.addAll(actions);
                    onTimeBlocked.add(onTime.restBlocked());
                    aheadBlocked.add(ahead.restBlocked());
                }
                String label = skill + " seed " + seed;
                compareRuns(label, onTime, onTimeActions, ahead, aheadActions, chart);
                check(aheadActions.stream().allMatch(a -> a.playTick() == a.aiTick() - lead)
                                && onTimeActions.stream().allMatch(a -> a.playTick() == a.aiTick()),
                        label + ": each action leaves " + lead + " battle ticks before its own tick");
                check(onTimeBlocked.equals(aheadBlocked), label + ": a rest is blocked on the same server ticks");

                // A server whose ticks come late and in bursts, or straddle a pause: playTicks jumps two or three
                // ticks and repeats. The mob still plays every one of its ticks, so nothing differs from on time.
                Random jumps = new Random(seed * 31L + skill.hashCode());
                Fixture jumpy = chartFixture(skill, seed, chart);
                Fixture oldJumpy = chartFixture(skill, seed, chart);
                List<Action> jumpyActions = new ArrayList<>(), oldJumpyActions = new ArrayList<>();
                for (int playTick = -40; playTick <= end; ) {
                    actions.clear(); jumpy.serverTick(playTick); jumpyActions.addAll(actions);
                    actions.clear(); oldJumpy.oldServerTick(playTick); oldJumpyActions.addAll(actions);
                    int roll = jumps.nextInt(10);
                    playTick += roll == 0 ? 0 : roll == 1 ? 3 : roll <= 3 ? 2 : 1;
                }
                compareRuns(label + " (server ticks that jump)", onTime, onTimeActions, jumpy, jumpyActions, chart);
                boolean oldLate = !onTimeActions.stream().map(Action::what).toList()
                        .equals(oldJumpyActions.stream().map(Action::what).toList());
                if (oldLate) oldLateRuns++;
                runs++;
                sent += aheadActions.size();
            }
        }
        // That the jumping server is a real test: reading the clock once per server tick did press late on it.
        check(oldLateRuns > runs / 2, "a mob reading its clock once per server tick pressed late on jumping ticks in "
                + oldLateRuns + " of " + runs + " charts");
        // A clock that jumps further than the catch-up allows is not replayed: the mob carries on from the battle.
        Fixture stalled = chartFixture("PERFECT", 1, List.of(new int[]{0, 10, 0}, new int[]{1, 2000, 0}));
        actions.clear();
        stalled.serverTick(0);
        stalled.serverTick(1800);
        check(field(stalled.battle, "aiTicks") == 1800 + lead, "a long stall is not replayed tick by tick");
        check(actions.size() == 1 && !actions.get(0).hit(), "a note left behind by a long stall is missed once, as before");
        System.out.println("Mob ahead of the battle by " + lead + " ticks: " + runs + " random charts, " + sent
                + " actions compared with on time and with server ticks that jump (reading the clock once per tick"
                + " differed in " + oldLateRuns + ")");
    }

    static Fixture chartFixture(String skill, long seed, List<int[]> chart) throws Exception {
        Fixture fixture = new Fixture(skill, new Random(seed));
        for (int[] note : chart) {
            fixture.note(0, note[0], note[1], note[2]);
        }
        return fixture;
    }

    static void compareRuns(String label, Fixture expected, List<Action> expectedActions, Fixture actual,
                            List<Action> actualActions, List<int[]> chart) throws Exception {
        check(expectedActions.stream().map(Action::what).toList().equals(actualActions.stream().map(Action::what).toList()),
                label + ": the same actions in the same order, on the same ticks of the mob's clock");
        for (String field : List.of("score", "combo", "maxCombo", "mistakes")) {
            check(expected.number(field) == actual.number(field), label + ": the same " + field);
        }
        boolean sameNotes = true;
        for (int i = 0; i < chart.size(); i++) {
            for (String flag : List.of("started", "resolved", "hitResolved", "missedResolved")) {
                sameNotes &= flag(expected.notes.get(i), flag) == flag(actual.notes.get(i), flag);
            }
        }
        check(sameNotes, label + ": every note settled the same way");
    }

    /** About 120 notes from tick 0..2: 0-3 ticks apart, a chord now and then, a quarter of them holds, a few rests. */
    static List<int[]> randomChart(Random random) {
        List<int[]> notes = new ArrayList<>();
        int tick = random.nextInt(3);
        while (notes.size() < 120) {
            int chord = random.nextInt(5) == 0 ? 2 + random.nextInt(2) : 1;
            for (int i = 0; i < chord; i++) {
                int duration = random.nextInt(4) == 0 ? 1 + random.nextInt(16) : 0;
                notes.add(new int[]{random.nextInt(4), tick, duration});
            }
            tick += random.nextInt(4);
            if (random.nextInt(25) == 0) {
                tick += 30 + random.nextInt(80);
            }
        }
        return notes;
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
            // The isolated battle has no field initialisers: its mob's clock starts unset, as a real battle's does.
            set(battle, "aiTicks", Integer.MIN_VALUE);
            Field skillField = skillType.getDeclaredField(skill); skillField.setAccessible(true); set(battle, "mobSkill", skillField.get(null));
        }
        Object note(int owner, int lane, int tick, int duration) throws Exception {
            Constructor<?> ctor = noteType.getDeclaredConstructor(int.class, BattleNote.class); ctor.setAccessible(true);
            Object note = ctor.newInstance(notes.size(), new BattleNote(owner, lane, tick, duration)); notes.add(note); return note;
        }
        /**
         * One tick of the mob's own clock (Battle#aiTicks), which everything the mob does reads, in the order
         * Battle#tickMobSide plays it: holds, the notes it let go, then its choices.
         */
        void tick(int tick) throws Exception {
            set(battle, "playTicks", tick); set(battle, "aiTicks", tick);
            invoke(battle, "tickHold", stateType, state); invoke(battle, "resolveMissedNotes", boolean.class, true);
            invoke(battle, "tickMobAi", stateType, state);
        }
        /**
         * One server tick with the mob on the battle's own tick and reading it once, as a battle tick used to go, in
         * the order Battle#tickMobSide plays a tick: holds, the notes it let go, then its choices from tick zero.
         */
        void oldServerTick(int playTick) throws Exception {
            set(battle, "playTicks", playTick); set(battle, "aiTicks", playTick);
            invoke(battle, "tickHold", stateType, state);
            invoke(battle, "resolveMissedNotes", boolean.class, true);
            if (playTick >= 0) invoke(battle, "tickMobAi", stateType, state);
        }
        /** One server tick of the real thing at {@code playTick}: Battle#tickParticipants, the mob ahead of it. */
        void serverTick(int playTick) throws Exception {
            set(battle, "rightState", null);
            set(battle, "playTicks", playTick);
            Method participants = battleType.getDeclaredMethod("tickParticipants"); participants.setAccessible(true);
            participants.invoke(battle);
        }
        boolean restBlocked() throws Exception {
            Method blocker = battleType.getDeclaredMethod("hasActivePauseSkipBlocker"); blocker.setAccessible(true);
            return (boolean) blocker.invoke(battle);
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
    static int field(Object object, String name) throws Exception { return (int) get(object, name); }
    static void check(boolean condition, String message) { checks++; if (!condition) failures.add(message); }
    public static void action(Object battle, int owner, int lane, int note, boolean hit, boolean complete) {
        try {
            Object side = get(battle, owner == 0 ? "leftState" : "rightState");
            actions.add(new Action(owner, lane, note, hit, complete, (int) get(battle, "playTicks"), (int) get(battle, "aiTicks"),
                    side == null ? -1 : (int) get(side, "combo")));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void isolate(Path compiled, Path output) throws Exception {
        transform(compiled, output, MANAGER, Set.of("resolveInput", "canHitClaimedNote", "shouldBreakComboForBadPress", "earlyHitWindowTicks", "timingScore", "animateParticipant"), Set.of("AI_LEAD_TICKS", "MISS_AFTER_PANEL_EXIT_TICKS"), false);
        transform(compiled, output, MANAGER + "$Battle", Set.of("tickMobAi", "tickHold", "resolveMissedNotes", "state", "noteByIndex",
                        "clockOf", "settled", "hasActivePauseSkipBlocker", "tickParticipants", "tickMobSide", "tickPlayerSide"),
                Set.of("runtimeNotes", "random", "mobSkill", "leftState", "rightState", "playTicks", "aiTicks"), true);
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
