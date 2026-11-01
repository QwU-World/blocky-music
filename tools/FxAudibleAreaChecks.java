import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.gora.musicbattle.client.FxAudibleArea;
import ru.gora.musicbattle.client.FxAudibleArea.Speaker;

/**
 * The audible area that bounds the block outline and the entity glow, without a game: the edge fade, the battle
 * chain and DJ wiring, the camera-relative packing for the shader, and the server constants and shader contract
 * the Java side mirrors. How the fade looks in Minecraft, under Iris or not, this does not show.
 */
public final class FxAudibleAreaChecks {
    private static final double EPSILON = 1.0e-6;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".");
        fadeProfile();
        fadeWidthRule();
        unionAndUnbounded();
        rejectsUnusableSpheres();
        battleWithoutSpeakers();
        battleChain();
        installationWiring();
        installationFallbacks();
        panelInstallation();
        installationHoldsThroughTheFade(project);
        packing();
        serverConstants(project);
        shaderContract(project);
        System.out.println("FX audible area checks PASS: " + checks);
    }

    /** Full inside, zero at and beyond the edge, monotonic and smooth across the band. */
    private static void fadeProfile() {
        FxAudibleArea area = FxAudibleArea.of(0, 0, 0, 14);
        double width = FxAudibleArea.fadeWidth(14);
        check(Math.abs(width - 4.2) < 1.0e-5, "a 14-block sphere fades over 30% of its radius, 4.2 blocks");
        check(area.fadeAt(0, 0, 0) == 1.0F, "the centre is fully lit");
        check(area.fadeAt(14 - width - 0.001, 0, 0) == 1.0F, "inside the band's inner edge is fully lit");
        check(area.fadeAt(14, 0, 0) == 0.0F, "the edge itself is dark");
        check(area.fadeAt(0, 30, 0) == 0.0F, "outside is dark in every direction");
        float previous = 1.0F;
        float largestStep = 0.0F;
        for (int step = 0; step <= 4200; step++) {
            double distance = 14 - width + step * width / 4200.0;
            float fade = area.fadeAt(distance, 0, 0);
            check(fade <= previous + EPSILON, "the fade never rises towards the edge (" + distance + ")");
            largestStep = Math.max(largestStep, previous - fade);
            previous = fade;
        }
        check(largestStep < 0.001F, "no cut: neighbouring millimetres differ by " + largestStep);
        // Smoothstep has no slope at either end, so the band starts and ends without a visible crease.
        double inner = 14 - width;
        check(1.0F - area.fadeAt(inner + 0.01, 0, 0) < 1.0e-4, "the fade starts flat at the inner edge");
        check(area.fadeAt(13.99, 0, 0) < 1.0e-4, "the fade ends flat at the outer edge");
        check(Math.abs(area.fadeAt(inner + width / 2, 0, 0) - 0.5F) < 1.0e-5, "half way through the band is half lit");
        // The shader computes 1 - smoothstep(r - w, r, d); Java must agree everywhere.
        for (double distance = 0; distance <= 16; distance += 0.37) {
            double t = Math.max(0, Math.min(1, (distance - inner) / width));
            double glsl = 1 - t * t * (3 - 2 * t);
            check(Math.abs(area.fadeAt(distance, 0, 0) - glsl) < 1.0e-6, "Java matches GLSL smoothstep at " + distance);
        }
    }

    private static void fadeWidthRule() {
        check(Math.abs(FxAudibleArea.fadeWidth(64) - 8.0F) < EPSILON, "a 64-block speaker caps its band at 8 blocks");
        check(Math.abs(FxAudibleArea.fadeWidth(2) - 1.5F) < EPSILON, "a 2-block speaker still fades over 1.5 blocks");
        check(Math.abs(FxAudibleArea.fadeWidth(1) - 1.0F) < EPSILON, "a band never reaches past the centre");
        FxAudibleArea tiny = FxAudibleArea.of(0, 0, 0, 1);
        check(tiny.fadeAt(0, 0, 0) == 1.0F && tiny.fadeAt(1, 0, 0) == 0.0F, "a tiny sphere fades from centre to edge");
    }

    private static void unionAndUnbounded() {
        FxAudibleArea pair = FxAudibleArea.of(0, 0, 0, 14, 20, 0, 0, 14);
        check(pair.fadeAt(10, 0, 0) == 1.0F, "a point covered by two overlapping spheres stays lit");
        check(pair.fadeAt(34, 0, 0) == 0.0F, "the union still ends at the outer edge");
        check(pair.fadeAt(30, 0, 0) == FxAudibleArea.of(20, 0, 0, 14).fadeAt(30, 0, 0),
                "near one sphere only that sphere decides");
        check(FxAudibleArea.UNBOUNDED.unbounded() && FxAudibleArea.UNBOUNDED.fadeAt(1e6, 0, -1e6) == 1.0F,
                "an unknown area keeps the old reach everywhere");
        check(!pair.unbounded(), "a known area is bounded");
        // Between the two speakers, five blocks off their axis, each sphere alone is three quarters faded although
        // the music carries on for another five blocks: the seam must stay lit, not show as a dim band.
        check(pair.fadeAt(10, 5, 0) == 1.0F, "the seam between two overlapping speakers stays lit: "
                + pair.fadeAt(10, 5, 0));
        double crease = Math.sqrt(14 * 14 - 10 * 10);
        check(pair.fadeAt(10, crease, 0) < 1.0e-4, "the circle where both spheres end is the edge of the area");
        check(pair.fadeAt(-14, 0, 0) == 0.0F && pair.fadeAt(34, 0, 0) == 0.0F, "the outer edge still reaches 0");
        float previous = pair.fadeAt(-16, 8, 0);
        float largestStep = 0.0F;
        for (int step = 1; step <= 52_000; step++) {
            float fade = pair.fadeAt(-16 + step * 0.001, 8, 0);
            largestStep = Math.max(largestStep, Math.abs(fade - previous));
            previous = fade;
        }
        check(largestStep < 0.002F, "walking past both speakers and their seam meets no cut: " + largestStep);
    }

    private static void rejectsUnusableSpheres() {
        FxAudibleArea area = FxAudibleArea.of(0, 0, 0, 0, 1, 2, 3, Double.NaN, Double.POSITIVE_INFINITY, 0, 0, 5,
                4, 5, 6, 7);
        check(area.sphereCount() == 1 && area.sphere(0)[3] == 7, "zero, NaN and infinite spheres are dropped");
        try {
            FxAudibleArea.of(1, 2, 3);
            check(false, "a sphere must have four numbers");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void battleWithoutSpeakers() {
        FxAudibleArea area = FxAudibleArea.battle(100.5, 64, -20.5, List.of());
        check(area.sphereCount() == 1, "a battle without speakers is its own emitter");
        double[] core = area.sphere(0);
        check(core[0] == 100.5 && core[1] == 65 && core[2] == -20.5 && core[3] == 14,
                "the emitter is one block above the arena centre with the 14-block radius");
    }

    private static void battleChain() {
        List<Speaker> speakers = new ArrayList<>();
        speakers.add(speaker(10.5, 64.5, 0.5, 14, true, Speaker.NO_CONTROLLER));   // inside the arena radius
        speakers.add(speaker(22.5, 64.5, 0.5, 14, true, Speaker.NO_CONTROLLER));   // reached through the first
        speakers.add(speaker(-13.5, 64.5, 0.5, 14, false, Speaker.NO_CONTROLLER)); // silent: not playing this song
        speakers.add(speaker(60.5, 64.5, 0.5, 14, true, Speaker.NO_CONTROLLER));   // playing, but out of reach
        speakers.add(speaker(0.5, 64.5, 13.5, 30, true, 99L));                     // wired DJ speakers chain too
        FxAudibleArea area = FxAudibleArea.battle(0.5, 64, 0.5, speakers);
        check(area.sphereCount() == 4, "core, two chained speakers and the in-range DJ speaker: " + area.sphereCount());
        check(contains(area, 10.5, 65.15, 0.5, 14), "a speaker within the arena radius joins");
        check(contains(area, 22.5, 65.15, 0.5, 14), "a speaker within a joined speaker's radius joins");
        check(!contains(area, -13.5, 65.15, 0.5, 14), "a speaker the server did not switch on stays out");
        check(!contains(area, 60.5, 65.15, 0.5, 14), "a playing speaker beyond every chained sphere stays out");
        // SpeakerNetwork measures the chain from block centres, the audio from 0.65 above them.
        List<Speaker> edge = List.of(speaker(14.5, 64.0, 0.5, 14, true, Speaker.NO_CONTROLLER));
        check(FxAudibleArea.battle(0.5, 64, 0.5, edge).sphereCount() == 2,
                "a block centre exactly on the arena radius joins, as on the server");
        // Coverage is directional: a big sphere reaches a small speaker that could never reach back.
        List<Speaker> oneWay = List.of(speaker(12.5, 64, 0.5, 30, true, Speaker.NO_CONTROLLER),
                speaker(40.5, 64, 0.5, 2, true, Speaker.NO_CONTROLLER));
        check(FxAudibleArea.battle(0.5, 64, 0.5, oneWay).sphereCount() == 3, "chains follow each source's radius");
        // The server caps the chain at 512 speakers.
        List<Speaker> line = new ArrayList<>();
        for (int index = 0; index < 600; index++) {
            line.add(speaker(0.5 + index * 2, 64, 0.5, 14, true, Speaker.NO_CONTROLLER));
        }
        check(FxAudibleArea.battle(0.5, 64, 0.5, line).sphereCount() == 513, "the chain stops at 512 speakers");
    }

    private static void installationWiring() {
        long panel = 0x1234L;
        long other = 0x5678L;
        List<Speaker> speakers = List.of(
                speaker(0.5, 64.5, 0.5, 14, true, panel),
                speaker(80.5, 64.5, 0.5, 10, true, panel),     // far, but wired to the same panel
                speaker(-30.5, 64.5, 0.5, 14, true, other),     // another installation
                speaker(5.5, 64.5, 0.5, 14, false, panel),      // linked but switched off
                speaker(2.5, 64.5, 2.5, 14, true, Speaker.NO_CONTROLLER)); // a battle speaker, not this DJ's
        FxAudibleArea area = FxAudibleArea.installation(3, 64, 0, speakers, 8);
        check(area.sphereCount() == 2, "the listener's installation has two playing speakers: " + area.sphereCount());
        check(contains(area, 0.5, 65.15, 0.5, 14) && contains(area, 80.5, 65.15, 0.5, 10),
                "every playing speaker on the listener's panel counts, however far apart");
        check(!contains(area, -30.5, 65.15, 0.5, 14), "another panel's speakers stay out");
        check(area.fadeAt(80.5, 65, 0.5) == 1.0F, "the far speaker of the same installation lights its own spot");
    }

    private static void installationFallbacks() {
        long panel = 7L;
        List<Speaker> playing = List.of(speaker(0.5, 64.5, 0.5, 10, true, panel));
        check(FxAudibleArea.installation(14, 64, 0, playing, 8).sphereCount() == 1,
                "a listener a few blocks outside still finds the installation while effects fade");
        check(FxAudibleArea.installation(30, 64, 0, playing, 8).unbounded(),
                "far beyond the slack nothing is known");
        List<Speaker> stopped = List.of(speaker(0.5, 64.5, 0.5, 10, false, panel),
                speaker(30.5, 64.5, 0.5, 6, false, panel));
        FxAudibleArea preview = FxAudibleArea.installation(2, 64, 0, stopped, 8);
        check(preview.sphereCount() == 2, "a stopped panel's camera preview uses all of its wired speakers");
        List<Speaker> unwired = List.of(speaker(0.5, 64.5, 0.5, 10, true, Speaker.NO_CONTROLLER));
        check(FxAudibleArea.installation(2, 64, 0, unwired, 8).unbounded(), "unwired speakers are no DJ's");
        check(FxAudibleArea.installation(2, 64, 0, List.of(), 8).unbounded(), "no speakers, no area");
    }

    /**
     * A DJ listener leaving the music fast. The show's effects fade for some ticks from the tick the listener leaves the
     * speaker's sphere, and over all of them the area must stay the installation's: a hidden game teleported a listener
     * past LISTENER_SLACK and the mobs at the edge of the show flashed back to full glow and light mid-fade. The hold
     * keeps the last installation for DJ_FADE_HOLD_TICKS and lets it go afterwards.
     */
    private static void installationHoldsThroughTheFade(Path project) throws Exception {
        String client = Files.readString(project.resolve(
                "src/main/java/ru/gora/musicbattle/client/ClientBattleController.java"));
        double fadeStep = number(client, "float step = target > spectatorFxVisibility \\? [0-9.]+F : ([0-9.]+)F;");
        int fadeTicks = (int) Math.ceil(1.0 / fadeStep - 1.0e-9);
        check(FxAudibleArea.DJ_FADE_HOLD_TICKS >= fadeTicks,
                "the hold outlasts the walk-out fade of " + fadeTicks + " ticks: " + FxAudibleArea.DJ_FADE_HOLD_TICKS);
        long panel = 9L;
        double slack = 8;
        List<Speaker> speakers = List.of(speaker(0.5, 64.5, 0.5, 16, true, panel));
        double emitterY = 64.5 + FxAudibleArea.SPEAKER_EMITTER_HEIGHT;
        // A villager in the fade band at the edge of the show: what its glow and its body light are scaled by.
        double mobX = 13.5;
        float inside = FxAudibleArea.installation(4, 64, 0.5, speakers, slack).fadeAt(mobX, 65, 0.5);
        check(inside > 0.05F && inside < 0.95F, "the villager stands in the fade band: " + inside);
        boolean reproduced = false;
        // Blocks a tick: sprinting, flying, flying at a sprint, and a teleport out of the show.
        for (double speed : new double[]{0.28, 0.55, 1.08, 40.0}) {
            FxAudibleArea.Hold hold = new FxAudibleArea.Hold(FxAudibleArea.DJ_FADE_HOLD_TICKS);
            long leftAt = -1;
            long lastFound = -1;
            FxAudibleArea held = FxAudibleArea.UNBOUNDED;
            FxAudibleArea found = FxAudibleArea.UNBOUNDED;
            for (long tick = 0; tick < 160; tick++) {
                double x = 4.0 + speed * tick;
                found = FxAudibleArea.installation(x, 64, 0.5, speakers, slack);
                held = hold.resolve(found, tick);
                if (!found.unbounded()) lastFound = tick;
                if (leftAt < 0 && Math.hypot(x - 0.5, 64 - emitterY) > 16) leftAt = tick;
                if (leftAt >= 0 && tick <= leftAt + fadeTicks) {
                    reproduced |= found.unbounded();
                    check(!held.unbounded() && held.fadeAt(mobX, 65, 0.5) == inside,
                            "while the effects fade the villager keeps its place in the band (" + speed + " a tick, tick "
                                    + tick + ")");
                }
            }
            check(found.unbounded() && 159 - lastFound > FxAudibleArea.DJ_FADE_HOLD_TICKS && held.unbounded(),
                    "once the fade is long over, the old reach comes back (" + speed + " a tick)");
        }
        check(reproduced, "reproduced: the plain search loses the installation mid-fade for a fast listener");
        FxAudibleArea first = FxAudibleArea.of(0, 65, 0, 16);
        FxAudibleArea second = FxAudibleArea.of(100, 65, 0, 10);
        FxAudibleArea.Hold hold = new FxAudibleArea.Hold(FxAudibleArea.DJ_FADE_HOLD_TICKS);
        check(hold.resolve(first, 10) == first && hold.resolve(FxAudibleArea.UNBOUNDED, 12) == first,
                "a gap is filled with the installation the listener just left");
        check(hold.resolve(second, 13) == second, "another installation found wins at once");
        check(hold.resolve(FxAudibleArea.UNBOUNDED, 5).unbounded(), "a clock that went back holds nothing");
        hold.resolve(first, 20);
        hold.forget();
        check(hold.resolve(FxAudibleArea.UNBOUNDED, 21).unbounded(), "a forgotten installation is not held");
    }

    /** The DJ monitor shows its own panel's installation, from wherever its camera stands. */
    private static void panelInstallation() {
        long panel = 0x42L;
        long other = 0x43L;
        List<Speaker> speakers = List.of(
                speaker(0.5, 64.5, 0.5, 10, true, panel),
                speaker(40.5, 64.5, 0.5, 10, true, panel),
                speaker(5.5, 64.5, 0.5, 10, false, panel),                 // wired, but not playing this song
                speaker(-20.5, 64.5, 0.5, 10, true, other),                // another installation
                speaker(2.5, 64.5, 2.5, 10, true, Speaker.NO_CONTROLLER)); // a battle speaker
        FxAudibleArea area = FxAudibleArea.panel(panel, speakers);
        check(area.sphereCount() == 2 && contains(area, 0.5, 65.15, 0.5, 10) && contains(area, 40.5, 65.15, 0.5, 10),
                "a playing panel's monitor covers exactly its playing speakers: " + area.sphereCount());
        check(!contains(area, -20.5, 65.15, 0.5, 10), "another panel's speakers stay out of the monitor");
        // A camera filming from high above the floor is no listener: the installation found from its position
        // is unknown, while the panel's own area stays the same.
        check(FxAudibleArea.installation(20.5, 90, 0.5, speakers, 8).unbounded(),
                "nothing covers a camera far above the speakers");
        check(area.fadeAt(0.5, 65, 0.5) == 1.0F && area.fadeAt(20.5, 90, 0.5) == 0.0F,
                "the panel's area lights its own floor and nothing high above it");
        List<Speaker> stopped = List.of(speaker(0.5, 64.5, 0.5, 10, false, panel),
                speaker(30.5, 64.5, 0.5, 6, false, panel), speaker(9.5, 64.5, 0.5, 6, false, other));
        check(FxAudibleArea.panel(panel, stopped).sphereCount() == 2,
                "a stopped panel previews over all of its wired speakers");
        check(FxAudibleArea.panel(0x99L, speakers).unbounded(), "a panel without a loaded speaker is unknown");
        check(FxAudibleArea.panel(Speaker.NO_CONTROLLER, speakers).unbounded(), "no panel, no area");
    }

    private static void packing() {
        double[] spheres = new double[20 * 4];
        for (int index = 0; index < 20; index++) {
            spheres[index * 4] = 1000.5 + (19 - index) * 10;   // listed farthest first
            spheres[index * 4 + 1] = 70;
            spheres[index * 4 + 2] = -3000.5;
            spheres[index * 4 + 3] = 4;
        }
        FxAudibleArea area = FxAudibleArea.of(spheres);
        float[] out = new float[FxAudibleArea.SHADER_SPHERES * 4];
        int count = area.packCameraRelative(1000.5, 70, -3000.5, out);
        check(count == FxAudibleArea.SHADER_SPHERES, "the shader receives at most " + FxAudibleArea.SHADER_SPHERES);
        double farthestKept = 0;
        for (int slot = 0; slot < count; slot++) {
            check(out[slot * 4 + 1] == 0 && out[slot * 4 + 2] == 0 && out[slot * 4 + 3] == 4,
                    "spheres are relative to the camera and keep their radius");
            farthestKept = Math.max(farthestKept, out[slot * 4]);
        }
        check(farthestKept == 150, "the spheres nearest the camera are the ones kept: " + farthestKept);
        float[] small = new float[8];
        check(area.packCameraRelative(0, 0, 0, small) == 2, "a short buffer is never overrun");
        float[] exact = new float[4];
        FxAudibleArea.of(12345.25, -10, 98765.75, 9).packCameraRelative(12340, -11, 98760, exact);
        check(exact[0] == 5.25F && exact[1] == 1 && exact[2] == 5.75F, "camera-relative values keep their precision");
    }

    /** The Java side mirrors server numbers; this fails when one of them moves without the other. */
    private static void serverConstants(Path project) throws Exception {
        String battle = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/BattleManager.java"));
        String dj = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/DjPlaybackManager.java"));
        String network = Files.readString(project.resolve("src/main/java/ru/gora/musicbattle/server/SpeakerNetwork.java"));
        double distance = number(battle, "SPECTATOR_MUSIC_DISTANCE\\s*=\\s*([0-9.]+)D?\\s*;");
        check(distance == FxAudibleArea.BATTLE_RADIUS,
                "the battle radius is BattleManager.SPECTATOR_MUSIC_DISTANCE (" + distance + ")");
        double core = number(battle, "coreSource\\s*=\\s*battleCenter\\.add\\(0\\.0D,\\s*([0-9.]+)D,\\s*0\\.0D\\)");
        check(core == FxAudibleArea.BATTLE_EMITTER_HEIGHT, "the battle emitter height matches (" + core + ")");
        double battleSpeaker = number(battle, "Vec3\\.atCenterOf\\(pos\\)\\.add\\(0\\.0D,\\s*([0-9.]+)D,\\s*0\\.0D\\)");
        double djSpeaker = number(dj, "Vec3\\.atCenterOf\\(speakerPos\\)\\.add\\(0\\.0D,\\s*([0-9.]+)D,\\s*0\\.0D\\)");
        check(battleSpeaker == FxAudibleArea.SPEAKER_EMITTER_HEIGHT && djSpeaker == FxAudibleArea.SPEAKER_EMITTER_HEIGHT,
                "speakers sound from " + battleSpeaker + " / " + djSpeaker + " above their block centre");
        double chain = number(network, "MAX_CHAIN_SPEAKERS\\s*=\\s*([0-9]+)\\s*;");
        check(chain == 512, "SpeakerNetwork still chains at most 512 speakers (" + chain + ")");
    }

    /** The shader holds exactly as many spheres as Java packs, and fades with the same formula. */
    private static void shaderContract(Path project) throws Exception {
        Path shaders = project.resolve("src/main/resources/assets/music_battle/shaders/core");
        String fragment = Files.readString(shaders.resolve("object_outline.fsh"));
        String json = Files.readString(shaders.resolve("object_outline.json"));
        for (int index = 0; index < FxAudibleArea.SHADER_SPHERES; index++) {
            check(fragment.contains("uniform vec4 AudibleSphere" + index + ";"), "the shader declares sphere " + index);
            check(json.contains("\"AudibleSphere" + index + "\""), "the shader JSON declares sphere " + index);
        }
        check(!fragment.contains("AudibleSphere" + FxAudibleArea.SHADER_SPHERES + ";")
                        && !json.contains("\"AudibleSphere" + FxAudibleArea.SHADER_SPHERES + "\""),
                "the shader holds no more spheres than Java packs");
        check(fragment.contains("for (int index = 0; index < " + FxAudibleArea.SHADER_SPHERES + "; index++)"),
                "the shader loops over exactly the packed spheres");
        String compact = fragment.replaceAll("\\s+", " ");
        check(compact.contains("clamp(sphere.w * AudibleFadeRule.x, AudibleFadeRule.y, AudibleFadeRule.z)")
                        && compact.contains("1.0 - smoothstep(sphere.w - width, sphere.w,"),
                "the shader fades with FxAudibleArea's band rule and smoothstep");
        check(compact.contains("fade = min(1.0, fade + 1.0 - smoothstep("),
                "the shader adds the spheres' fades up to 1, as FxAudibleArea.fadeAt does");
        check(json.contains("\"InverseViewProjection\", \"type\": \"matrix4x4\", \"count\": 16"),
                "the camera matrix is a 4x4 uniform");
        check(!fragment.contains("DepthSampler;\n") || fragment.contains("BlockDepthSampler"),
                "the outline samples the copied depth texture directly");
        check(!Files.exists(shaders.resolve("object_depth_copy.fsh")) && !Files.exists(shaders.resolve("object_depth_copy.json")),
                "no shader pass copies depth any more: Iris would switch its writes off");
    }

    private static double number(String source, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(source);
        if (!matcher.find()) {
            throw new AssertionError("pattern not found: " + pattern);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static boolean contains(FxAudibleArea area, double x, double y, double z, double radius) {
        for (int index = 0; index < area.sphereCount(); index++) {
            double[] sphere = area.sphere(index);
            if (Math.abs(sphere[0] - x) < EPSILON && Math.abs(sphere[1] - y) < EPSILON
                    && Math.abs(sphere[2] - z) < EPSILON && Math.abs(sphere[3] - radius) < EPSILON) {
                return true;
            }
        }
        return false;
    }

    private static Speaker speaker(double x, double y, double z, double radius, boolean active, long controller) {
        return new Speaker(x, y, z, radius, active, controller);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(String.format(Locale.ROOT, "FX audible area: %s", message));
        }
        checks++;
    }
}
