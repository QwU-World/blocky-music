package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.entity.SmileyBallEntity;
import ru.gora.musicbattle.data.SmileyWalkAnimation;

/** A round, lit mesh with painted-on facial geometry and two detached hand orbs. */
public final class SmileyBallModel extends EntityModel<SmileyBallEntity> {
    private static final float BODY_RADIUS = 0.4F;
    private static final float HAND_RADIUS = 0.088F;
    private static final int INK = 0x100F19;
    private static final float[] BODY = sphere(24, 12);
    private static final float[] HAND = sphere(12, 8);
    private static final float[] LEFT_EYE = ellipse(-0.25F, 0.13F, 0.072F, 0.15F, 20);
    private static final float[] RIGHT_EYE = ellipse(0.25F, 0.13F, 0.072F, 0.15F, 20);
    private static final float[] SMILE = smile();
    private static final float[] FOREHEAD_SHINE = ellipse(-0.30F, 0.55F, 0.12F, 0.135F, 20);
    private static final float[] HAND_SHINE = ellipse(-0.28F, 0.48F, 0.14F, 0.16F, 12);

    private int ballColor = 0xF32945;
    private SmileyWalkAnimation.Pose animationPose = SmileyWalkAnimation.Pose.REST;

    @Override
    public void prepareMobModel(SmileyBallEntity entity, float walkPosition, float walkSpeed, float partialTick) {
        ballColor = entity.getBallColor() & 0xFFFFFF;
        animationPose = SmileyWalkAnimation.Pose.REST;
        if (!entity.isAlive()) return;
        // Battle note-only poses win even during rests; walking must never fill those gaps.
        if (ClientMobFallbackAnimator.hasBattlePose(entity)) {
            float notePhase = ClientMobFallbackAnimator.smileyNotePhase(entity);
            if (notePhase >= 0) animationPose = new SmileyWalkAnimation.Pose(notePhase, 1, 0);
            return;
        }
        if (entity.isBattleDanceControlled()) return;
        animationPose = entity.isDancing()
                ? new SmileyWalkAnimation.Pose(entity.getDancePhase(partialTick), 1, 0)
                : entity.getWalkingPose(partialTick);
    }

    @Override
    public void setupAnim(SmileyBallEntity entity, float walkPosition, float walkSpeed, float ageInTicks,
                          float headYaw, float headPitch) {
        // The face belongs to the ball, so it follows body yaw instead of turning independently.
    }

    @Override
    public void renderToBuffer(PoseStack poses, VertexConsumer vertices, int packedLight, int packedOverlay,
                               int color) {
        float cycle = animationPose.phase() * Mth.TWO_PI;
        float verticalScale = animationPose.verticalScale();
        float horizontalScale = Mth.invSqrt(verticalScale);
        float tilt = animationPose.weight() * 0.09F * Mth.sin(cycle);
        float cosTilt = Mth.cos(tilt);
        float sinTilt = Mth.sin(tilt);
        float centerHeight = BODY_RADIUS * Mth.sqrt(verticalScale * verticalScale * cosTilt * cosTilt
                + horizontalScale * horizontalScale * sinTilt * sinTilt);

        // Vanilla uses white (or translucent white). The highlight engine supplies its literal
        // shell color here; preserve that color instead of multiplying two unrelated RGB colors.
        boolean uniformTint = ClientModelGlowEngine.isRenderingGlowShell() || (color & 0xFFFFFF) != 0xFFFFFF;
        int bodyColor = color & 0xFF000000 | (uniformTint ? color & 0xFFFFFF : ballColor);
        int inkColor = color & 0xFF000000 | (uniformTint ? color & 0xFFFFFF : INK);
        int shineColor = color & 0xFF000000 | (uniformTint ? color & 0xFFFFFF : 0xFFFDF0);

        poses.pushPose();
        // Convert vanilla's model ground plane / downward Y to feet-origin mesh coordinates.
        // The Z flip makes our positive-Z face point along the mob's normal forward direction.
        poses.translate(0.0F, 1.501F, 0.0F);
        poses.scale(1.0F, -1.0F, -1.0F);
        poses.translate(0.0F, centerHeight + animationPose.height(), 0.0F);
        poses.mulPose(Axis.ZP.rotation(tilt));

        poses.pushPose();
        poses.scale(BODY_RADIUS * horizontalScale, BODY_RADIUS * verticalScale,
                BODY_RADIUS * horizontalScale);
        draw(BODY, poses, vertices, packedLight, packedOverlay, bodyColor, !uniformTint);
        draw(LEFT_EYE, poses, vertices, packedLight, packedOverlay, inkColor, false);
        draw(RIGHT_EYE, poses, vertices, packedLight, packedOverlay, inkColor, false);
        draw(SMILE, poses, vertices, packedLight, packedOverlay, inkColor, false);
        draw(FOREHEAD_SHINE, poses, vertices, packedLight, packedOverlay, shineColor, false);
        poses.popPose();

        for (int side = -1; side <= 1; side += 2) {
            float reach = BODY_RADIUS * horizontalScale + 0.115F
                    + animationPose.weight() * 0.018F * Mth.cos(cycle);
            float swing = animationPose.weight() * side * 0.095F * Mth.sin(cycle);
            poses.pushPose();
            poses.translate(side * reach, 0.015F + swing,
                    animationPose.weight() * side * 0.035F * Mth.cos(cycle));
            poses.scale(HAND_RADIUS, HAND_RADIUS, HAND_RADIUS);
            draw(HAND, poses, vertices, packedLight, packedOverlay, bodyColor, !uniformTint);
            draw(HAND_SHINE, poses, vertices, packedLight, packedOverlay, shineColor, false);
            poses.popPose();
        }
        poses.popPose();
        // Ambient music hops are physical; walking adds a small visual lift only. Battle note
        // lift still belongs exclusively to ClientMobFallbackAnimator, never to this model.
    }

    private static void draw(float[] mesh, PoseStack poses, VertexConsumer vertices, int light, int overlay,
                             int color, boolean shadeBottom) {
        int alpha = color >>> 24;
        int red = color >> 16 & 255;
        int green = color >> 8 & 255;
        int blue = color & 255;
        PoseStack.Pose pose = poses.last();
        for (int index = 0; index < mesh.length; index += 3) {
            float x = mesh[index];
            float y = mesh[index + 1];
            float z = mesh[index + 2];
            float inverseLength = Mth.invSqrt(x * x + y * y + z * z);
            float shade = shadeBottom ? 0.89F + 0.11F * y : 1.0F;
            vertices.addVertex(pose, x, y, z)
                    .setColor(Math.round(red * shade), Math.round(green * shade), Math.round(blue * shade), alpha)
                    .setUv(0.5F, 0.5F)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, x * inverseLength, y * inverseLength, z * inverseLength);
        }
    }

    private static float[] sphere(int segments, int rings) {
        float[] mesh = new float[segments * rings * 4 * 3];
        int index = 0;
        for (int ring = 0; ring < rings; ring++) {
            float lower = -Mth.HALF_PI + Mth.PI * ring / rings;
            float upper = -Mth.HALF_PI + Mth.PI * (ring + 1) / rings;
            for (int segment = 0; segment < segments; segment++) {
                float left = Mth.TWO_PI * segment / segments;
                float right = Mth.TWO_PI * (segment + 1) / segments;
                index = spherePoint(mesh, index, lower, left);
                index = spherePoint(mesh, index, lower, right);
                index = spherePoint(mesh, index, upper, right);
                index = spherePoint(mesh, index, upper, left);
            }
        }
        return mesh;
    }

    private static int spherePoint(float[] mesh, int index, float latitude, float longitude) {
        float radius = Mth.cos(latitude);
        mesh[index++] = radius * Mth.sin(longitude);
        mesh[index++] = Mth.sin(latitude);
        mesh[index++] = radius * Mth.cos(longitude);
        return index;
    }

    private static float[] ellipse(float centerX, float centerY, float radiusX, float radiusY, int segments) {
        float[] mesh = new float[segments * 4 * 3];
        int index = 0;
        for (int segment = 0; segment < segments; segment++) {
            float angle0 = Mth.TWO_PI * segment / segments;
            float angle1 = Mth.TWO_PI * (segment + 1) / segments;
            // A repeated final vertex encodes a triangle in the entity renderer's QUADS buffer.
            index = facePoint(mesh, index, centerX, centerY);
            index = facePoint(mesh, index, centerX + radiusX * Mth.cos(angle0), centerY + radiusY * Mth.sin(angle0));
            index = facePoint(mesh, index, centerX + radiusX * Mth.cos(angle1), centerY + radiusY * Mth.sin(angle1));
            index = facePoint(mesh, index, centerX, centerY);
        }
        return mesh;
    }

    private static float[] smile() {
        int segments = 28;
        float[] mesh = new float[segments * 4 * 3];
        int index = 0;
        for (int segment = 0; segment < segments; segment++) {
            float t0 = -1.0F + 2.0F * segment / segments;
            float t1 = -1.0F + 2.0F * (segment + 1) / segments;
            float y0 = -0.54F + 0.23F * t0 * t0;
            float y1 = -0.54F + 0.23F * t1 * t1;
            float halfWidth0 = 0.023F * (1.0F - 0.55F * Math.abs(t0));
            float halfWidth1 = 0.023F * (1.0F - 0.55F * Math.abs(t1));
            index = facePoint(mesh, index, 0.56F * t0, y0 + halfWidth0);
            index = facePoint(mesh, index, 0.56F * t0, y0 - halfWidth0);
            index = facePoint(mesh, index, 0.56F * t1, y1 - halfWidth1);
            index = facePoint(mesh, index, 0.56F * t1, y1 + halfWidth1);
        }
        return mesh;
    }

    private static int facePoint(float[] mesh, int index, float x, float y) {
        // Project the ink onto the sphere; a small radial lift prevents surface Z fighting.
        mesh[index++] = x * 1.014F;
        mesh[index++] = y * 1.014F;
        mesh[index++] = Mth.sqrt(Math.max(0.0F, 1.0F - x * x - y * y)) * 1.014F;
        return index;
    }
}
