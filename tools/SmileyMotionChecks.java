package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.data.gson.AnimationSerializing;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.SmileyWalkAnimation;

/** Real Player Animator clips/layers plus movement-driven gait; no Minecraft window. */
public final class SmileyMotionChecks {
    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> PLAYER_PARTS = List.of("head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg");
    private static int checks;

    public static void main(String[] args) throws Exception {
        loadRealClips(Path.of(args[0]));
        instantNotesInterruptIdleFade();
        freshTriggerSurvivesLateLayerAttachment();
        holdDirectionSwitchesImmediately();
        stopRemovesTheOutgoingPose();
        clipsKeepTheirInternalMotion();
        approvedPlayerClipTrajectories();
        firstButtonUsesUpdatedHeadPosition();
        walkingFollowsDistanceInsteadOfElapsedTime();
        walkingSafetyAndInterpolation();
        modelActuallyLiftsAndDeforms();
        System.out.println("Smiley motion checks: " + checks);
        FAILURES.stream().limit(20).forEach(System.err::println);
        if (!FAILURES.isEmpty()) throw new AssertionError(FAILURES.size() + " failed checks");
        System.out.println("Smiley motion regression PASS");
    }

    @SuppressWarnings({"unchecked", "removal"})
    private static void loadRealClips(Path root) throws Exception {
        Field field = PlayerAnimationRegistry.class.getDeclaredField("animations");
        field.setAccessible(true);
        Map<ResourceLocation, IPlayable> registry = (Map<ResourceLocation, IPlayable>) field.get(null);
        for (String name : List.of("idle", "lane_0", "lane_1", "lane_2", "lane_3", "hold_0", "hold_1", "hold_2", "hold_3")) {
            try (var reader = Files.newBufferedReader(root.resolve("src/main/resources/assets/music_battle/player_animations/" + name + ".json"))) {
                registry.put(MusicBattleMod.id(name), AnimationSerializing.deserializeAnimation(reader).getFirst());
            }
        }
    }

    private static void instantNotesInterruptIdleFade() {
        for (int from = 0; from < 4; from++) for (int to = 0; to < 4; to++) {
            var holder = ClientBattleAnimation.createLayer();
            ClientBattleAnimation.update(holder, 10, from, 0, 120, 0);
            ClientBattleAnimation.update(holder, 11, from, 0, 120, 1);
            ClientBattleAnimation.update(holder, 11, from, 0, 120, 10);
            // The preceding tap has just returned to idle; its fade has not ticked out.
            ClientBattleAnimation.update(holder, 12, to, 0, 120, 10.25F);
            var direct = ClientBattleAnimation.createLayer();
            ClientBattleAnimation.update(direct, 0, to, 0, 120, 10.25F);
            ClientBattleAnimation.update(direct, 1, to, 0, 120, 10.25F);
            samePose(holder, direct, "lane " + from + " -> idle fade -> " + to);
        }
    }

    private static void freshTriggerSurvivesLateLayerAttachment() {
        var late = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(late, 7, 2, 0, 120, 6.0F, 5.5F);

        var direct = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(direct, 0, 2, 0, 120, 5.0F);
        ClientBattleAnimation.update(direct, 1, 2, 0, 120, 6.0F);
        samePose(late, direct, "fresh trigger survives a lazily attached player layer");

        var stale = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(stale, 7, 2, 0, 120, 80.0F, 5.5F);
        var idle = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(idle, 0, 2, 0, 120, 80.0F);
        samePose(stale, idle, "stale trigger is not replayed when a layer attaches much later");
    }

    private static void holdDirectionSwitchesImmediately() {
        for (int from = 0; from < 4; from++) for (int to = 0; to < 4; to++) {
            if (from == to) continue;
            var holder = ClientBattleAnimation.createLayer();
            int both = (1 << from) | (1 << to);
            ClientBattleAnimation.update(holder, 0, from, 0, 120, 0);
            ClientBattleAnimation.update(holder, 1, from, both, 120, 1);
            // Releasing the preferred key selects the other held direction without a new press.
            ClientBattleAnimation.update(holder, 1, from, 1 << to, 120, 1.5F);
            var direct = ClientBattleAnimation.createLayer();
            ClientBattleAnimation.update(direct, 0, to, 1 << to, 120, 1.5F);
            samePose(holder, direct, "hold " + from + " -> " + to);
        }
    }

    private static void stopRemovesTheOutgoingPose() {
        var holder = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(holder, 0, 0, 0, 120, 0);
        ClientBattleAnimation.update(holder, 1, 0, 0, 120, 1);
        ClientBattleAnimation.update(holder, 1, 0, 0, 120, 10);
        ClientBattleAnimation.stop(holder);
        check(!holder.layer.isActive(), "stop must not retain the fade's outgoing animation");
    }

    private static void clipsKeepTheirInternalMotion() {
        var holder = ClientBattleAnimation.createLayer();
        ClientBattleAnimation.update(holder, 0, 0, 0, 120, 0);
        ClientBattleAnimation.update(holder, 1, 0, 0, 120, 1);
        Vec3f before = transform(holder, "head", TransformType.ROTATION, 0);
        ClientBattleAnimation.update(holder, 1, 0, 0, 120, 2.25F);
        Vec3f after = transform(holder, "head", TransformType.ROTATION, 0);
        check(distance(before, after) > 0.001, "instant clip changes must preserve motion inside the selected clip");
    }

    private static void approvedPlayerClipTrajectories() {
        assertClipPose("idle", 0, new float[][]{
                {0, 0.102662F, 3.566314F, -0.130900F, 0, 0},
                {0, 0.102662F, 3.566314F, -0.130900F, 0, 0},
                {0, 0.346604F, 5.288152F, 0.174533F, 0, 0},
                {0, 1.076996F, 3.174736F, -1.172658F, 0.161127F, 0.067376F},
                {0.973498F, 0.949965F, 3.631006F, 0.236922F, -0.262697F, -0.055310F},
                {-0.977697F, -0.113783F, 1.004588F, -0.161559F, 0.232928F, 0.029173F}
        });
        assertClipPose("idle", 6, new float[][]{
                {0, 0.914272F, 2.135867F, 0.156387F, 0, 0},
                {0, 0.914272F, 2.135867F, -0.011322F, 0, 0},
                {0, 1.813626F, 4.103166F, 0.292020F, 0.033185F, -0.109909F},
                {0, 1.914080F, 2.101900F, -0.717567F, 0.239643F, 0.277615F},
                {0.973805F, 0.757239F, 4.203322F, 0.190585F, -0.261133F, -0.046032F},
                {-0.977977F, -0.012817F, 0.094111F, -0.075174F, 0.227511F, 0.057460F}
        });
        assertClipPose("lane_1", 0, new float[][]{
                {-0.250000F, 0.102662F, -0.566314F, 0.130900F, 0, 0},
                {0, 0.102662F, -0.566314F, 0.130900F, 0, 0},
                {0, -0.175501F, 1.677628F, 0.436332F, 0, 0},
                {0, 1.076996F, -0.174736F, -1.664551F, -0.168247F, 0.805435F},
                {0.973777F, 0.831344F, 2.113828F, 0.331634F, -0.239298F, -0.122568F},
                {-0.978869F, 0.282697F, -0.987574F, -0.218169F, 0.218167F, 0}
        });
        assertClipPose("lane_1", 6, new float[][]{
                {-0.250000F, 1.921852F, -0.447078F, 0.330195F, 0, 0},
                {0, 1.016164F, -0.566314F, 0.130900F, 0, 0},
                {0, 1.607394F, 1.792085F, 0.433545F, 0.048402F, -0.104127F},
                {0, 1.990499F, -0.174736F, -1.496164F, -0.330310F, 0.761845F},
                {0.974192F, 0.697739F, 3.128930F, 0.251990F, -0.239141F, -0.122252F},
                {-0.978559F, 0.401908F, -2.011120F, -0.138454F, 0.218234F, 0.000170F}
        });
        // The former second-button motion now belongs to button 3, by clip name.
        assertClipPose("lane_2", 0, new float[][]{
                {-0.382663F, -1.009372F, 0.780901F, -0.403022F, 0.300803F, -0.104344F},
                {0.000019F, 0.102666F, 1.566348F, -0.141550F, 0.389158F, -0.054013F},
                {-0.380586F, -0.164194F, -0.591757F, 0.338416F, 0.389158F, -0.054013F},
                {-0.002066F, 0.214711F, 2.286361F, -1.955140F, -0.027804F, 0.469323F},
                {0.027300F, 0.613984F, -3.064043F, -0.099572F, 0.125379F, -0.105052F},
                {-0.340408F, 0.245639F, 1.996597F, 0.276776F, 0.582423F, 0.137601F}
        });
        assertClipPose("lane_2", 6, new float[][]{
                {-0.382663F, -0.095869F, 0.780901F, -0.283194F, 0.301090F, -0.104279F},
                {0.000019F, 1.016169F, 1.566348F, -0.141550F, 0.389158F, -0.054013F},
                {-0.380588F, 1.708831F, -0.718083F, 0.304229F, 0.415849F, -0.141110F},
                {-0.002066F, 1.128213F, 2.286361F, -1.828335F, -0.027890F, 0.469150F},
                {-0.322637F, 0.507664F, -3.781238F, -0.059532F, 0.125620F, -0.104803F},
                {0.009121F, 0.371449F, 2.954154F, 0.196829F, 0.582311F, 0.137535F}
        });
    }

    private static void firstButtonUsesUpdatedHeadPosition() {
        // New source head offset (0, 0, -1), rotated by the torso at tick zero.
        // These coordinates are independently derived from the authored pivot/rotation.
        for (int heldMask : new int[]{0, 1}) {
            var holder = ClientBattleAnimation.createLayer();
            ClientBattleAnimation.update(holder, 0, 0, 0, 120, 0);
            ClientBattleAnimation.update(holder, 1, 0, heldMask, 120, 0);
            Vec3f head = transform(holder, "head", TransformType.POSITION, 0);
            near(head.getX(), -0.382664F, "updated first button head X, held=" + heldMask);
            near(head.getY(), -0.017928F, "updated first button head Y, held=" + heldMask);
            near(head.getZ(), 2.650373F, "updated first button head Z, held=" + heldMask);
        }
    }

    private static void assertClipPose(String clip, int sourceTick, float[][] expected) {
        assertClipPose(clip, sourceTick, expected, false);
        if (!clip.equals("idle")) assertClipPose(clip, sourceTick, expected, true);
    }

    private static void assertClipPose(String clip, int sourceTick, float[][] expected, boolean held) {
        var holder = ClientBattleAnimation.createLayer();
        float battleTick = sourceTick * 10.0F / 13.0F;
        if (clip.equals("idle")) {
            ClientBattleAnimation.update(holder, 0, 0, 0, 120, battleTick);
        } else {
            int lane = Integer.parseInt(clip.substring("lane_".length()));
            ClientBattleAnimation.update(holder, 0, lane, 0, 120, battleTick);
            ClientBattleAnimation.update(holder, 1, lane, held ? 1 << lane : 0, 120, battleTick);
        }
        for (int partIndex = 0; partIndex < PLAYER_PARTS.size(); partIndex++) {
            String part = PLAYER_PARTS.get(partIndex);
            Vec3f position = transform(holder, part, TransformType.POSITION, 0);
            Vec3f rotation = transform(holder, part, TransformType.ROTATION, 0);
            float[] values = expected[partIndex];
            String context = clip + " held=" + held + " tick " + sourceTick + " " + part;
            near(position.getX(), values[0], context + " position X");
            near(position.getY(), values[1], context + " position Y");
            near(position.getZ(), values[2], context + " position Z");
            near(rotation.getX(), values[3], context + " rotation X");
            near(rotation.getY(), values[4], context + " rotation Y");
            near(rotation.getZ(), values[5], context + " rotation Z");
        }
    }

    private static void walkingFollowsDistanceInsteadOfElapsedTime() {
        var slow = new SmileyWalkAnimation();
        var fast = new SmileyWalkAnimation();
        slow.tick(0, 0, true, true);
        fast.tick(0, 0, true, true);
        slow.tick(0.04, 0, true, true);
        fast.tick(0.08, 0, true, true);
        float slowPhase = slow.sample(1).phase();
        float fastPhase = fast.sample(1).phase();
        check(slowPhase > 0 && slow.sample(1).height() > 0, "walking really produces a hop");
        near(fastPhase, slowPhase * 2, "twice the actual movement advances twice the cadence");
        slow.tick(0.08, 0, true, true);
        near(slow.sample(1).phase(), fastPhase, "same distance in two ticks reaches the same phase as one tick");
        var diagonal = new SmileyWalkAnimation();
        diagonal.tick(0, 0, true, true);
        diagonal.tick(0.048, 0.064, true, true);
        near(diagonal.sample(1).phase(), fastPhase, "diagonal cadence follows distance, not a sum of axes");
        SmileyWalkAnimation.Pose pose = fast.sample(0.5F);
        for (int i = 0; i < 100; i++) {
            check(pose.equals(fast.sample(0.5F)), "extra render passes never advance gait");
        }
        check(pose.verticalScale() != 1, "walking deforms the ball as well as lifting it");
    }

    private static void walkingSafetyAndInterpolation() {
        var gait = new SmileyWalkAnimation();
        gait.tick(1000, -2000, true, true);
        rest(gait, "initial position is not movement");
        gait.tick(1000.08, -2000, true, true);
        check(gait.sample(1).height() > 0, "ordinary movement after initialization hops");
        gait.tick(1000.08, -2000, true, true);
        rest(gait, "stationary ball never freezes halfway up a hop");
        gait.tick(1000.16, -2000, false, true);
        rest(gait, "falling ball has no added walking hop");
        gait.tick(1000.24, -2000, true, false);
        rest(gait, "battle or ambient mode suppresses walking immediately");
        gait.tick(4000, 7000, true, true);
        rest(gait, "teleport spike is not walking");
        gait.tick(4000.08, 7000, true, true);
        check(gait.sample(1).height() > 0, "walking resumes from the new teleport baseline");
        gait.tick(Double.NaN, 7000, true, true);
        rest(gait, "invalid positions never poison render state");
        gait.tick(0, 0, true, true);
        rest(gait, "recovery from invalid position establishes a fresh baseline");
        gait.tick(0.48, 0, true, true);
        gait.tick(0.64, 0, true, true);
        check(gait.sample(0.5F).phase() > 0.75F, "render interpolation moves forward through phase wrap");
        near(gait.sample(1).height(), 0, "stride boundary reaches the floor");
        for (int i = 0; i <= 100; i++) {
            var pose = gait.sample(i / 100F);
            check(Float.isFinite(pose.height()) && pose.height() >= 0 && pose.height() <= 0.13F,
                    "walking hop height remains small and finite");
            check(Float.isFinite(pose.verticalScale()) && pose.verticalScale() > 0.8F && pose.verticalScale() < 1.2F,
                    "squash/stretch remains bounded");
        }
        gait.tick(0.64000001, 0, true, true);
        rest(gait, "subpixel position noise does not animate a stationary ball");
    }

    private static void rest(SmileyWalkAnimation gait, String message) {
        for (float partial : new float[]{0, 0.5F, 1}) {
            var pose = gait.sample(partial);
            check(pose.height() == 0 && pose.weight() == 0 && pose.verticalScale() == 1, message);
        }
    }

    private static void modelActuallyLiftsAndDeforms() throws Exception {
        var rest = renderBody(SmileyWalkAnimation.Pose.REST);
        var squash = renderBody(new SmileyWalkAnimation.Pose(0, 1, 0));
        var stretch = renderBody(new SmileyWalkAnimation.Pose(0.5F, 1, 0));
        var hop = renderBody(new SmileyWalkAnimation.Pose(0.5F, 1, 0.12F));
        near(rest.maxY - rest.minY, 0.8, "resting rendered body keeps its size");
        near(squash.maxY - squash.minY, 0.8 * 0.84, "rendered body visibly squashes");
        near(stretch.maxY - stretch.minY, 0.8 * 1.14, "rendered body visibly stretches");
        near(squash.maxY, rest.maxY, "squash does not sink through its ground plane");
        near(stretch.maxY, rest.maxY, "ambient/note deformation adds no duplicate hop lift");
        near(hop.maxY, rest.maxY - 0.12, "walking lifts the mesh above the vanilla model ground plane");
        near(hop.minY, stretch.minY - 0.12, "walking translates the whole stretched body");
        near(hop.maxX - hop.minX, stretch.maxX - stretch.minX, "vertical lift preserves horizontal shape");
    }

    private static CapturedBody renderBody(SmileyWalkAnimation.Pose pose) throws Exception {
        var model = new SmileyBallModel();
        Field field = SmileyBallModel.class.getDeclaredField("animationPose");
        field.setAccessible(true);
        field.set(model, pose);
        var captured = new CapturedBody();
        model.renderToBuffer(new PoseStack(), captured, 0, 0, -1);
        check(captured.vertices > 24 * 12 * 4, "real model emits body plus its face and hands");
        return captured;
    }

    private static final class CapturedBody implements VertexConsumer {
        private int vertices;
        private float minX = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (vertices++ < 24 * 12 * 4) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static void samePose(ClientBattleAnimation.Holder actual, ClientBattleAnimation.Holder expected, String message) {
        for (String part : PLAYER_PARTS) {
            for (TransformType type : List.of(TransformType.POSITION, TransformType.ROTATION)) {
                for (float partial : new float[]{0, 0.5F, 0.99F}) {
                    check(distance(transform(actual, part, type, partial), transform(expected, part, type, partial)) < 0.0001,
                            message + " immediately selects " + part + "/" + type + " at partial " + partial);
                }
            }
        }
    }

    private static Vec3f transform(ClientBattleAnimation.Holder holder, String part, TransformType type, float partial) {
        holder.layer.setupAnim(partial);
        return holder.layer.get3DTransform(part, type, partial, new Vec3f(0, 0, 0));
    }

    private static double distance(Vec3f first, Vec3f second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getY() - second.getY()) + Math.abs(first.getZ() - second.getZ());
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 0.00001, message + ": " + actual + " vs " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }
}
