import io.netty.buffer.Unpooled;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.client.ClientTrackEffects;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.network.SpectatorBattlePayload;

/**
 * A19: every beat-driven visual counts its beats from the chart's beat 1 (offsetMs), not only the crowd, the mobs and
 * the editor's grid.
 *
 * <ul>
 *   <li>Old maps are untouched: with offset 0 the effect sampler gives exactly what a build from before offsets (the
 *       team keeps one; it is not in the public history) gives, for every effect
 *       type, beat-reactive or not, on every tick of a long stretch, at several tempos and at x1, x1.5 and x2. That
 *       jar is argument 2 or BLOCKY_PREVIOUS_JAR; without one this comparison is skipped and says so.</li>
 *   <li>With an offset the beat moves with it: the pulse, a beat-following flash and every beat-reactive swell at tick
 *       t with beat 1 at o are the offset-0 values at t - o (whole beats on), and the pulse peaks on beat 1 and each
 *       beat after it. Before beat 1 the beats run on backwards, so the first beat of the song still lands on beat 1.
 *       The combo's pulse and the gameplay preview's pulse obey the same rule.</li>
 *   <li>The watcher and the DJ booth get the offset: SpectatorBattlePayload and OpenDjPanelPayload carry it on the
 *       wire, the server fills it in, and every sampler of the battle controller, the previews and the DJ monitor
 *       passes its beat 1 on (source rules, since those need a running game).</li>
 *   <li>The speakers' cones pump on a beat the server names; the poses and the mobs' hops take beat 1.</li>
 * </ul>
 * How it looks in the game this does not show; the hidden battle run does.
 */
public final class BeatOneChecks {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static final int[] BPMS = {60, 97, 120, 128, 174};
    private static final float[] SPEEDS = {1.0F, 1.5F, 2.0F};
    private static final String[] VARIANTS = {"", "steady", "beat", "pulse", "alternate"};

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String oldJar = args.length > 1 ? args[1] : System.getenv().getOrDefault("BLOCKY_PREVIOUS_JAR", "");
        oldMapsUnchanged(oldJar);
        beatMovesWithOffset();
        panelAndPreviewPulse();
        payloads();
        speakerBeat();
        speakerNetworkBeat(root);
        mobHop();
        fxEditorPreview();
        sourceRules(root);
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(failure -> System.out.println("FAIL " + failure));
            System.out.println("beat-one: " + failures.size() + " of " + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("beat-one: PASS (" + checks + ")");
        System.out.println("NOT COVERED: the look in the game; the poses' arithmetic runs only in a game (source rules"
                + " here)");
    }

    private static List<TrackEffect> effects() {
        List<TrackEffect> list = new ArrayList<>();
        for (TrackEffect.Type type : TrackEffect.Type.values()) {
            for (boolean reactive : new boolean[] {false, true}) {
                for (String variant : VARIANTS) {
                    list.add(new TrackEffect(type, 0, 0, 37, 400, 0.8F, 0.3F, 0xFFFF0000, 0xFF00FF00, "x", "default",
                            0.5F, 0.5F, "fade", "fade", variant, reactive, 0.9F, 1.0F, 1.0F, 1.0F));
                }
            }
        }
        return list;
    }

    // ---- old maps --------------------------------------------------------------------------------------------

    private static void oldMapsUnchanged(String oldJar) throws Exception {
        if (oldJar.isBlank()) {
            System.out.println("beat-one: SKIP old-build comparison (no previous jar given; set BLOCKY_PREVIOUS_JAR to a"
                    + " build from before offsets)");
            return;
        }
        Path jar = Path.of(oldJar);
        if (!Files.isRegularFile(jar)) {
            check(false, "the previous jar " + jar + " exists (it was given, so the old-build comparison needs it)");
            return;
        }
        ClassLoader old = oldLoader(jar);
        Class<?> oldEffects = Class.forName("ru.gora.musicbattle.client.ClientTrackEffects", true, old);
        Class<?> oldEffect = Class.forName("ru.gora.musicbattle.data.TrackEffect", true, old);
        Class<?> oldType = Class.forName("ru.gora.musicbattle.data.TrackEffect$Type", true, old);
        Constructor<?> make = oldEffect.getConstructor(oldType, int.class, int.class, int.class, int.class, float.class,
                float.class, int.class, int.class, String.class, String.class, float.class, float.class, String.class,
                String.class, String.class, boolean.class, float.class, float.class, float.class, float.class);
        Method oldSample = oldEffects.getMethod("sample", List.class, float.class, int.class, float.class, float.class);
        String[] scalars = {"flash", "oldTv", "shake", "pulse", "pulseBeat", "vignette", "trackTick", "bpm", "playbackSpeed"};
        int compared = 0;
        boolean same = true;
        String firstDiff = null;
        for (TrackEffect effect : effects()) {
            Object oldOne;
            try {
                oldOne = make.newInstance(Enum.valueOf(oldType.asSubclass(Enum.class), effect.type().name()), effect.lane(),
                        effect.layer(), effect.tick(), effect.durationTicks(), effect.intensity(), effect.softness(),
                        effect.colorA(), effect.colorB(), effect.text(), effect.font(), effect.positionX(), effect.positionY(),
                        effect.animation(), effect.exitAnimation(), effect.variant(), effect.beatReactive(), effect.opacity(),
                        effect.textSpeed(), effect.outlineThickness(), effect.glowStrength());
            } catch (IllegalArgumentException typeTheOldBuildLacks) {
                continue;
            }
            for (int bpm : BPMS) {
                for (float speed : SPEEDS) {
                    for (float tick = 0.0F; tick < 460.0F; tick += 1.731F) {
                        Object before = oldSample.invoke(null, List.of(oldOne), tick, bpm, speed, 1.0F);
                        ClientTrackEffects.EffectState now = ClientTrackEffects.sample(List.of(effect), tick, bpm, speed, 1.0F, 0.0F);
                        for (String scalar : scalars) {
                            Object a = component(before, scalar);
                            Object b = component(now, scalar);
                            if (!a.equals(b)) {
                                same = false;
                                if (firstDiff == null) {
                                    firstDiff = effect.type() + " " + effect.variant() + " reactive " + effect.beatReactive()
                                            + " bpm " + bpm + " x" + speed + " tick " + tick + ": " + scalar + " " + a + " -> " + b;
                                }
                            }
                        }
                        compared++;
                    }
                }
            }
        }
        check(compared > 50_000, "the old-build comparison ran (" + compared + " samples)");
        check(same, "with offset 0 every sampled value is the previous build's: " + firstDiff);
    }

    // ---- offset ----------------------------------------------------------------------------------------------

    private static void beatMovesWithOffset() {
        boolean shifted = true;
        boolean peaks = true;
        boolean leadIn = true;
        String first = null;
        for (int offsetMs : new int[] {437, 730, 1500, 11_000}) {
            for (int bpm : BPMS) {
                for (float speed : SPEEDS) {
                    float origin = (float) ChartBeat.originTicks(ChartBeat.effectiveOffsetMs(offsetMs, speed));
                    float beat = ClientTrackEffects.beatDurationTicks(Math.round(bpm * speed), speed);
                    // Off the beat boundaries (t starts 0.37 past one) and whole beats past the origin, so t - shift stays on the same phase of the offset-0 clock.
                    float shift = (float) Math.ceil(origin / beat) * beat - origin;
                    for (TrackEffect effect : effects()) {
                        if (effect.type() != TrackEffect.Type.PULSE && effect.type() != TrackEffect.Type.FLASH
                                && !effect.beatReactive()) {
                            continue;
                        }
                        TrackEffect long1 = effect.withTimeline(0, 0, 4000);
                        for (float t = origin + 600.37F; t < origin + 900.0F; t += 3.37F) {
                            ClientTrackEffects.EffectState with = ClientTrackEffects.sample(List.of(long1), t,
                                    Math.round(bpm * speed), speed, 1.0F, origin);
                            ClientTrackEffects.EffectState without = ClientTrackEffects.sample(List.of(long1), t + shift,
                                    Math.round(bpm * speed), speed, 1.0F, 0.0F);
                            boolean ok = close(with.pulseBeat(), without.pulseBeat()) && close(with.flash(), without.flash());
                            if (!ok && first == null) {
                                first = effect.type() + " " + effect.variant() + " offset " + offsetMs + " bpm " + bpm + " x"
                                        + speed + " t " + t + ": " + with.pulseBeat() + "/" + with.flash() + " vs "
                                        + without.pulseBeat() + "/" + without.flash();
                            }
                            shifted &= ok;
                        }
                    }
                    for (int k = -2; k <= 6; k++) {
                        // A hair past the beat, so float rounding cannot put it at the end of the beat before.
                        float onBeat = origin + k * beat + 0.002F;
                        if (onBeat < 0.0F) {
                            continue;
                        }
                        float phase = ClientTrackEffects.beatPhase(onBeat, Math.round(bpm * speed), speed, origin);
                        boolean onIt = phase < 1e-3F;
                        peaks &= onIt;
                        if (k < 0) {
                            leadIn &= onIt;
                        }
                    }
                }
            }
        }
        check(shifted, "with beat 1 at o, the pulse, a beat flash and beat-reactive swells at t are offset 0's at t - o: " + first);
        check(peaks, "the beat phase is 0 on beat 1 and on every beat after it");
        check(leadIn, "before beat 1 the beats run on backwards from it");
        check(ClientTrackEffects.beatPhase(55.0F, 120, 1.0F, 0.0F) == ClientTrackEffects.beatPhase(55.0F, 120, 1.0F),
                "origin 0 is the old phase");
        // The old rule, once: an offset of 437 ms at 120 BPM put the pulse peak 8.74 ticks off beat 1.
        float oldPhase = ClientTrackEffects.beatPhase((float) ChartBeat.originTicks(437), 120, 1.0F);
        check(oldPhase > 0.8F, "the old phase missed beat 1 by most of a beat (reproduced): " + oldPhase);
    }

    private static void panelAndPreviewPulse() throws Exception {
        Class<?> panel = Class.forName("ru.gora.musicbattle.client.BattlePanelRenderer");
        Method pulse = panel.getDeclaredMethod("beatPulse", float.class, int.class, float.class);
        pulse.setAccessible(true);
        Class<?> preview = Class.forName("ru.gora.musicbattle.client.TrackGameplayPreviewScreen");
        Object screen = allocate(preview);
        Method previewPulse = preview.getDeclaredMethod("beatPulse", float.class);
        previewPulse.setAccessible(true);
        Field bpmField = preview.getDeclaredField("bpm");
        bpmField.setAccessible(true);
        Field originField = preview.getDeclaredField("beatOrigin");
        originField.setAccessible(true);
        boolean panelOk = true;
        boolean previewOk = true;
        boolean zeroSame = true;
        for (int bpm : BPMS) {
            float beat = 1200.0F / bpm;
            for (float origin : new float[] {0.0F, 8.74F, 14.6F, 30.0F}) {
                bpmField.set(screen, bpm);
                originField.set(screen, origin);
                for (int k = 0; k < 8; k++) {
                    float onBeat = origin + k * beat;
                    panelOk &= (float) pulse.invoke(null, onBeat + 0.01F, bpm, origin) > 0.99F;
                    panelOk &= (float) pulse.invoke(null, onBeat + beat * 0.5F, bpm, origin) < 0.001F;
                    previewOk &= (float) previewPulse.invoke(screen, onBeat + 0.01F) > 0.99F;
                }
                if (origin == 0.0F) {
                    for (float t = 0.0F; t < 200.0F; t += 0.37F) {
                        float phase = (t % beat) / beat;
                        float old = Math.max(0.0F, Math.min(1.0F, 1.0F - phase * 4.0F));
                        zeroSame &= (float) pulse.invoke(null, t, bpm, 0.0F) == old;
                        zeroSame &= (float) previewPulse.invoke(screen, t) == old;
                    }
                }
            }
        }
        check(panelOk, "the combo's pulse peaks on beat 1 and each beat after it, and rests between");
        check(previewOk, "the gameplay preview's pulse (FOV and cover) peaks on beat 1 and each beat after it");
        check(zeroSame, "at offset 0 both pulses are the old ones exactly");
    }

    // ---- the wire --------------------------------------------------------------------------------------------

    private static void payloads() {
        for (int offset : new int[] {0, 437, 12_000}) {
            SpectatorBattlePayload sent = new SpectatorBattlePayload(java.util.UUID.randomUUID(), 100, 0, 120, 1.5F,
                    0, 0, -1, 0F, 0F, 0F, 0xFF00FF00, 0xFFFF00FF, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F, 1F,
                    0, 0, 0, 0, 0, 0, List.of(), List.of(), offset);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null, ConnectionType.NEOFORGE);
            SpectatorBattlePayload.STREAM_CODEC.encode(buf, sent);
            SpectatorBattlePayload got = SpectatorBattlePayload.STREAM_CODEC.decode(buf);
            check(got.beatOffsetMs() == offset && buf.readableBytes() == 0,
                    "a watcher gets beat 1 at " + offset + " ms, and nothing is left over");
        }
    }

    private static void speakerBeat() throws Exception {
        SpeakerBlockEntity speaker = allocate(SpeakerBlockEntity.class);
        Field bpm = SpeakerBlockEntity.class.getDeclaredField("activeBpm");
        bpm.setAccessible(true);
        bpm.set(speaker, 120);
        Field beat = SpeakerBlockEntity.class.getDeclaredField("beatGameTick");
        beat.setAccessible(true);
        beat.set(speaker, SpeakerBlockEntity.NO_BEAT);
        speaker.setActiveBeat(128, 1000L);
        check(speaker.getActiveBpm() == 128 && speaker.getBeatGameTick() == 1000L, "a speaker takes the song's tempo and beat");
        speaker.setActiveBeat(128, 1001L);
        check(speaker.getBeatGameTick() == 1000L, "a beat a tick off is the same beat, rounded the other way: not resent");
        speaker.setActiveBeat(128, 1040L);
        check(speaker.getBeatGameTick() == 1040L, "a moved beat (a pause, a seek) is taken");
        speaker.setActiveBeat(120, SpeakerBlockEntity.NO_BEAT);
        check(speaker.getBeatGameTick() == SpeakerBlockEntity.NO_BEAT, "a silent speaker forgets its beat");
    }

    /**
     * The speakers' cones were pinned only on the block entity. The network that hands each speaker its
     * song now decides in pure code which tempo and which beat a speaker gets, and both managers name beat 1 through the
     * same rule: the battle's beat goes with the battle's tempo over a DJ's on a shared speaker, a battle naming no beat
     * leaves none of the DJ's behind, and beat 1 is the clock's start plus the offset.
     */
    private static void speakerNetworkBeat(Path root) throws Exception {
        Class<?> network = Class.forName("ru.gora.musicbattle.server.SpeakerNetwork");
        Method combine = network.getDeclaredMethod("combine", java.util.Map.class, java.util.Map.class, java.util.Map.class,
                java.util.Map.class);
        combine.setAccessible(true);
        net.minecraft.core.BlockPos dj = new net.minecraft.core.BlockPos(1, 64, 1);
        net.minecraft.core.BlockPos shared = new net.minecraft.core.BlockPos(2, 64, 2);
        net.minecraft.core.BlockPos battleOnly = new net.minecraft.core.BlockPos(3, 64, 3);
        net.minecraft.core.BlockPos battleNoBeat = new net.minecraft.core.BlockPos(4, 64, 4);
        Object combined = combine.invoke(null,
                java.util.Map.of(dj, 128, shared, 140, battleNoBeat, 100),
                java.util.Map.of(dj, 500L, shared, 600L, battleNoBeat, 650L),
                java.util.Map.of(shared, 96, battleOnly, 120, battleNoBeat, 110),
                java.util.Map.of(shared, 777L, battleOnly, 888L));
        @SuppressWarnings("unchecked")
        java.util.Map<net.minecraft.core.BlockPos, Integer> bpm = (java.util.Map<net.minecraft.core.BlockPos, Integer>) component(combined, "bpm");
        @SuppressWarnings("unchecked")
        java.util.Map<net.minecraft.core.BlockPos, Long> beats = (java.util.Map<net.minecraft.core.BlockPos, Long>) component(combined, "beats");
        check(bpm.get(dj) == 128 && Long.valueOf(500L).equals(beats.get(dj)), "a DJ booth's speaker pumps on its song's beat");
        check(bpm.get(shared) == 96 && Long.valueOf(777L).equals(beats.get(shared)),
                "a speaker a battle shares with a DJ takes the battle's tempo and the battle's beat");
        check(bpm.get(battleOnly) == 120 && Long.valueOf(888L).equals(beats.get(battleOnly)),
                "a battle's speaker pumps on the battle's beat");
        check(bpm.get(battleNoBeat) == 110 && !beats.containsKey(battleNoBeat),
                "a battle that names no beat leaves none of the DJ song's on its speaker");
        // Combine was tested alone, so a loop that read no beats from it (every cone NO_BEAT) went
        // unseen. What each speaker is handed comes from combine through Combined.at, measured here on the same maps.
        Method at = combined.getClass().getDeclaredMethod("at", net.minecraft.core.BlockPos.class);
        at.setAccessible(true);
        net.minecraft.core.BlockPos idle = new net.minecraft.core.BlockPos(5, 64, 5);
        Object[][] handed = {
                {dj, true, 128, 500L}, {shared, true, 96, 777L}, {battleOnly, true, 120, 888L},
                {battleNoBeat, true, 110, SpeakerBlockEntity.NO_BEAT}, {idle, false, 120, SpeakerBlockEntity.NO_BEAT}};
        for (Object[] row : handed) {
            Object got = at.invoke(combined, row[0]);
            boolean active = (Boolean) component(got, "active");
            int tempo = (Integer) component(got, "bpm");
            long beat = (Long) component(got, "beat");
            check(active == (Boolean) row[1] && tempo == (Integer) row[2] && beat == (Long) row[3],
                    "the speaker at " + row[0] + " is handed " + row[1] + ", " + row[2] + " BPM, beat " + row[3]
                            + ": got " + active + ", " + tempo + " BPM, beat " + beat);
        }
        String networkCode = code(root.resolve("src/main/java/ru/gora/musicbattle/server/SpeakerNetwork.java"));
        int apply = networkCode.indexOf("private static void applyCombinedActiveStates(ServerLevel level) {");
        String applyBody = apply < 0 ? "" : networkCode.substring(apply, networkCode.indexOf("\n    }\n", apply));
        check(applyBody.contains("Combined combined = combine(DJ_ACTIVE.getOrDefault(level, Map.of()), DJ_BEAT.getOrDefault(level, Map.of()),\n"
                        + "                BATTLE_ACTIVE.getOrDefault(level, Map.of()), BATTLE_BEAT.getOrDefault(level, Map.of()));")
                        && applyBody.contains("SpeakerBeat handed = combined.at(pos);")
                        && applyBody.contains("state.setValue(SpeakerBlock.ACTIVE, handed.active())")
                        && applyBody.contains("speaker.setActiveBeat(handed.bpm(), handed.beat());")
                        && applyBody.split("setActiveBeat\\(", -1).length - 1 == 1,
                "every loaded speaker is handed exactly what combine gives it (DJ and battle maps, tempo and beat)");
        // Beat 1 in game ticks: 100 ticks into the song now at game tick 1000, beat 1 730 ms (14.6 ticks) in.
        Method beatOne = network.getMethod("beatOneGameTick", long.class, double.class, int.class);
        check((Long) beatOne.invoke(null, 1000L, 100.0, 730) == 915L && (Long) beatOne.invoke(null, 1000L, 100.0, 0) == 900L,
                "beat 1's game tick is the song's start plus its offset (915 for 730 ms 100 ticks in at 1000; 900 at 0)");
        Method claim = network.getMethod("claim", java.util.Map.class, java.util.Map.class, net.minecraft.core.BlockPos.class,
                int.class, long.class);
        java.util.Map<net.minecraft.core.BlockPos, Integer> active = new java.util.HashMap<>();
        java.util.Map<net.minecraft.core.BlockPos, Long> claimed = new java.util.HashMap<>();
        claim.invoke(null, active, claimed, shared, 120, 10L);
        claim.invoke(null, active, claimed, shared, 100, 20L);
        boolean slowerLost = active.get(shared) == 120 && claimed.get(shared) == 10L;
        claim.invoke(null, active, claimed, shared, 150, 30L);
        check(slowerLost && active.get(shared) == 150 && claimed.get(shared) == 30L,
                "of two songs on one speaker the faster holds it, and its beat goes with its tempo");
        String battle = code(root.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"));
        String djCode = code(root.resolve("src/main/java/ru/gora/musicbattle/server/DjPlaybackManager.java"));
        check(battle.contains("SpeakerNetwork.beatOneGameTick(level.getGameTime(), battle.playTicks,\n                        battle.effectiveOffsetMs);")
                        && battle.contains("SpeakerNetwork.claim(active, beats, pos, battle.effectiveBpm, beatOne);")
                        && battle.contains("SpeakerNetwork.applyActiveStates(level, activeByLevel.getOrDefault(level, Map.of()),\n                    beatByLevel.getOrDefault(level, Map.of()));"),
                "a battle names its beat 1 to its speakers");
        check(djCode.contains("SpeakerNetwork.beatOneGameTick(level.getGameTime(), elapsed,\n                ChartBeat.effectiveOffsetMs(track.offsetMs(), speed));")
                        && djCode.contains("SpeakerNetwork.claim(activeSpeakers, speakerBeats, speakerPos, bpm, beatOne);")
                        && djCode.contains("SpeakerNetwork.setDjActiveStates(level, activeSpeakers, speakerBeats);"),
                "a DJ booth names its song's beat 1 to its speakers");
    }

    /**
     * The fallback mobs' hop was pinned by one source line, so storing beat 1 as 0 went unseen. Now the
     * real state a mob keeps is filled as prepare fills it and measured: with a note held the hop is flat on beat 1 and
     * on every beat after it, highest half-way, and at offset 0 it is the old hop.
     */
    private static void mobHop() throws Exception {
        Class<?> animator = Class.forName("ru.gora.musicbattle.client.ClientMobFallbackAnimator");
        Class<?> state = Class.forName("ru.gora.musicbattle.client.ClientMobFallbackAnimator$BounceState");
        Constructor<?> make = state.getDeclaredConstructor();
        make.setAccessible(true);
        Method update = animator.getDeclaredMethod("update", state, int.class, int.class, int.class, float.class, float.class);
        update.setAccessible(true);
        Method height = animator.getDeclaredMethod("hopHeight", state);
        height.setAccessible(true);
        boolean flatOnBeats = true;
        boolean peaks = true;
        boolean shifted = true;
        for (int bpm : new int[] {96, 128}) {
            float beat = 1200.0F / bpm;
            for (float origin : new float[] {14.6F, 7.3F, 3.0F}) {
                Object mob = make.newInstance();
                Object reference = make.newInstance();
                for (int k = 0; k < 6; k++) {
                    float onBeat = origin + k * beat;
                    update.invoke(null, mob, 1, 1, bpm, onBeat, origin);
                    flatOnBeats &= (Float) height.invoke(null, mob) < 1e-3F;
                    update.invoke(null, mob, 1, 1, bpm, onBeat + beat * 0.5F, origin);
                    peaks &= Math.abs((Float) height.invoke(null, mob) - 0.24F) < 1e-3F;
                    for (float into : new float[] {0.17F, 0.41F, 0.83F}) {
                        float t = onBeat + beat * into;
                        update.invoke(null, mob, 1, 1, bpm, t, origin);
                        update.invoke(null, reference, 1, 1, bpm, t - origin, 0.0F);
                        shifted &= Math.abs((Float) height.invoke(null, mob) - (Float) height.invoke(null, reference)) < 1e-3F;
                    }
                }
            }
        }
        // Update was measured alone, so prepare forwarding 0 instead of beat 1 went unseen. The same
        // hop through prepare, the entry point the battle controller calls, on a (never constructed) mob. A mob's class
        // needs the game's registries, so they are bootstrapped here (no level, no window).
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Method prepare = animator.getDeclaredMethod("prepare", net.minecraft.world.entity.LivingEntity.class, int.class,
                int.class, int.class, float.class, float.class);
        prepare.setAccessible(true);
        Field states = animator.getDeclaredField("STATES");
        states.setAccessible(true);
        boolean throughPrepare = true;
        for (int bpm : new int[] {96, 128}) {
            float beat = 1200.0F / bpm;
            for (float origin : new float[] {14.6F, 7.3F}) {
                net.minecraft.world.entity.LivingEntity entity = allocate(StubMob.class);
                Object reference = make.newInstance();
                for (float into : new float[] {0.0F, 0.17F, 0.5F, 0.83F, 1.3F}) {
                    float t = origin + beat * into;
                    prepare.invoke(null, entity, 1, 1, bpm, t, origin);
                    update.invoke(null, reference, 1, 1, bpm, t - origin, 0.0F);
                    Object kept = ((java.util.Map<?, ?>) states.get(null)).get(entity);
                    throughPrepare &= kept != null
                            && Math.abs((Float) height.invoke(null, kept) - (Float) height.invoke(null, reference)) < 1e-3F;
                }
                ((java.util.Map<?, ?>) states.get(null)).remove(entity);
            }
        }
        check(throughPrepare, "prepare times a mob's hop from the beat 1 it is given, as update does");
        check(flatOnBeats, "a mob holding a note is on the ground on beat 1 and every beat after it");
        check(peaks, "a mob holding a note is highest half-way between the chart's beats");
        check(shifted, "a mob's hop at t with beat 1 at o is the offset-0 hop at t - o");
    }

    /**
     * The FX editor's preview took its beat 1 from its host, and a host answering 0 went unseen. The real
     * chart-editor host, on a real (unopened) chart editor with a tempo and a beat 1 typed, hands both on.
     */
    private static void fxEditorPreview() throws Exception {
        Class<?> editorType = Class.forName("ru.gora.musicbattle.client.TrackEditorScreen");
        Class<?> fxType = Class.forName("ru.gora.musicbattle.client.TrackEffectEditScreen");
        Method host = fxType.getDeclaredMethod("editorHost", editorType);
        host.setAccessible(true);
        for (int[] typed : new int[][] {{96, 730}, {128, 220}, {120, 0}}) {
            Object editor = allocate(editorType);
            Field bpm = editorType.getDeclaredField("draftBpm");
            bpm.setAccessible(true);
            bpm.set(editor, Integer.toString(typed[0]));
            Field offset = editorType.getDeclaredField("draftOffset");
            offset.setAccessible(true);
            offset.set(editor, Integer.toString(typed[1]));
            Object made = host.invoke(null, editor);
            Class<?> hostType = Class.forName("ru.gora.musicbattle.client.TrackEffectEditHost");
            int expected = ChartBeat.storableOffsetMs(typed[1], typed[0]);
            int gotBpm = (Integer) hostType.getMethod("previewBpm").invoke(made);
            int gotOffset = (Integer) hostType.getMethod("previewBeatOffsetMs").invoke(made);
            check(gotBpm == typed[0] && gotOffset == expected && (typed[1] == 0 || gotOffset != 0),
                    "the FX editor's preview beats at the chart's " + typed[0] + " BPM from its beat 1 (" + expected
                            + " ms): got " + gotBpm + " BPM, " + gotOffset + " ms");
        }
    }

    // ---- what needs a game: source rules ----------------------------------------------------------------------

    private static void sourceRules(Path root) throws Exception {
        String client = "src/main/java/ru/gora/musicbattle/client/";
        for (String file : List.of("ClientBattleController", "TrackGameplayPreviewScreen", "TrackEffectEditScreen",
                "DjPanelScreen", "ClientDjFxTransition")) {
            String code = code(root.resolve(client + file + ".java"));
            int calls = 0;
            int withOrigin = 0;
            for (int at = code.indexOf("ClientTrackEffects.sample("); at >= 0; at = code.indexOf("ClientTrackEffects.sample(", at + 1)) {
                String call = call(code, at + "ClientTrackEffects.sample".length());
                calls++;
                if (call.toLowerCase(java.util.Locale.ROOT).contains("origin")) {
                    withOrigin++;
                }
            }
            check(calls > 0 && calls == withOrigin, file + " gives every effect sample its beat 1 (" + withOrigin + " of " + calls + ")");
        }
        String editor = code(root.resolve(client + "TrackEditorScreen.java"));
        check(editor.contains("ClientTrackEffects.sample(effects, renderTick, bpm(), 1.0F, 1.0F,\n                        (float) ChartBeat.originTicks(offsetMs()))"),
                "the chart editor's playing preview counts its beats from the typed beat 1");
        String controller = code(root.resolve(client + "ClientBattleController.java"));
        check(controller.contains("highlightApplies(highlight, state.trackTick() - state.beatOriginTicks(),"),
                "the highlight's pulse and alternation count from the sampled chart's beat 1");
        check(controller.contains(": (float) ChartBeat.beatTicks(time, spectatorBeatOffsetMs), bpm)"),
                "a watcher's FOV pulse counts from the watched chart's beat 1");
        check(controller.contains("BattlePanelRenderer.draw(graphics, font, width, height, time, trackBpm, activeBeatOrigin(), intro,"),
                "the fighter's combo pulse counts from beat 1");
        check(controller.split("ClientBattleAnimation\\.setBeatOrigin\\(", -1).length - 1 == 3
                        && controller.contains("animationBpm, animationTick,\n                    currentBeatOrigin(spectating));"),
                "every fighter's poses and a mob's hop are given beat 1 before they are updated");
        String animation = code(root.resolve(client + "ClientBattleAnimation.java"));
        check(animation.contains("float sourcePosition = (battleTick - beatOrigin) * sourceCycleTicks / beatTicks;"),
                "the beat-synced pose loops count from beat 1");
        String mob = code(root.resolve(client + "ClientMobFallbackAnimator.java"));
        check(mob.contains("float sinceBeatOne = state.battleTick - state.beatOrigin;"), "the mob's hop arc is timed from beat 1");
        String dj = code(root.resolve("src/main/java/ru/gora/musicbattle/server/DjPlaybackManager.java"));
        check(dj.contains("ChartBeat.beatTicks(elapsed, ChartBeat.effectiveOffsetMs(track.offsetMs(), speed))"),
                "a DJ song's mobs and smileys move on beats counted from its beat 1");
        check(dj.contains("ChartBeat.effectiveOffsetMs(track.offsetMs(), speed)\n        ));")
                        && dj.contains("loadedTrack.map(track -> ChartBeat.effectiveOffsetMs(track.offsetMs(), speed)).orElse(0)"),
                "the DJ booth sends beat 1 to its listeners and its monitor");
        String battle = code(root.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"));
        check(battle.contains("battle.rightState.appearance,\n                    join.settledNotes(),\n                    join.startedNotes(),\n                    join.leadActions(),\n                    battle.effectiveOffsetMs"),
                "a battle sends beat 1 to its watchers");
        String renderer = code(root.resolve(client + "SpeakerBlockEntityRenderer.java"));
        check(renderer.contains("((speaker.getLevel().getGameTime() - beatTick) + partialTick) / 20.0F"),
                "a speaker's cone pumps from the beat the server named");
    }

    /** A mob for prepare's map key only: allocated, never constructed, never ticked. */
    private static final class StubMob extends net.minecraft.world.entity.LivingEntity {
        private StubMob() {
            super(null, null);
        }

        @Override public Iterable<net.minecraft.world.item.ItemStack> getArmorSlots() { return List.of(); }
        @Override public net.minecraft.world.item.ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) { return null; }
        @Override public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot, net.minecraft.world.item.ItemStack stack) { }
        @Override public net.minecraft.world.entity.HumanoidArm getMainArm() { return null; }
    }

    private static String code(Path file) throws Exception {
        return Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//[^\\n]*\\n", "");
    }

    /** The argument list of a call starting at the opening parenthesis. */
    private static String call(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return code.substring(open, i + 1);
        }
        return code.substring(open);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 2e-3F;
    }

    private static Object component(Object record, String name) throws Exception {
        for (RecordComponent part : record.getClass().getRecordComponents()) {
            if (part.getName().equals(name)) {
                Method accessor = part.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(record);
            }
        }
        throw new AssertionError("no " + name + " in " + record.getClass());
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (T) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
    }

    private static ClassLoader oldLoader(Path jar) throws Exception {
        URL url = jar.toUri().toURL();
        ClassLoader parent = BeatOneChecks.class.getClassLoader();
        return new URLClassLoader(new URL[] {url}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("ru.gora.musicbattle.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures.add(message);
        }
    }
}
