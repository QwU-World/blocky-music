package ru.gora.musicbattle.client;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.entity.LivingEntity;

public final class ClientHumanoidAnimatorBridge {
    private static final Map<LivingEntity, MobLayer> LAYERS = new WeakHashMap<>();
    private static final Map<HumanoidModel<?>, Boolean> SUPPORTED_MODELS = new WeakHashMap<>();

    private ClientHumanoidAnimatorBridge() {
    }

    static boolean supportsAnimation(HumanoidModel<?> model) {
        return SUPPORTED_MODELS.computeIfAbsent(model, ClientHumanoidAnimatorBridge::calculateAnimationSupport);
    }

    private static boolean calculateAnimationSupport(HumanoidModel<?> model) {
        if (isDescendant(model.body, model.head)
                || isDescendant(model.body, model.leftArm)
                || isDescendant(model.body, model.rightArm)
                || isDescendant(model.body, model.leftLeg)
                || isDescendant(model.body, model.rightLeg)) {
            return false;
        }
        if (!finite(model.head) || !finite(model.body)
                || !finite(model.leftArm) || !finite(model.rightArm)
                || !finite(model.leftLeg) || !finite(model.rightLeg)) {
            return false;
        }

        float armSeparation = Math.abs(model.leftArm.x - model.rightArm.x);
        float legSeparation = Math.abs(model.leftLeg.x - model.rightLeg.x);
        return armSeparation >= 4.0F && armSeparation <= 20.0F
                && legSeparation >= 1.0F && legSeparation <= 12.0F
                && Math.abs(model.leftArm.y - model.body.y) <= 12.0F
                && Math.abs(model.rightArm.y - model.body.y) <= 12.0F
                && model.leftLeg.y - model.body.y >= 5.0F
                && model.leftLeg.y - model.body.y <= 26.0F
                && model.rightLeg.y - model.body.y >= 5.0F
                && model.rightLeg.y - model.body.y <= 26.0F
                && Math.abs(model.head.z - model.body.z) <= 8.0F
                && Math.abs(model.leftArm.z - model.body.z) <= 8.0F
                && Math.abs(model.rightArm.z - model.body.z) <= 8.0F;
    }

    static void prepareBattleAnimation(LivingEntity entity, HumanoidModel<?> model, int triggerSerial,
                                       int triggerLane, int heldLaneMask, int bpm, float battleTick,
                                       float triggerTick, float partialTick) {
        MobLayer mobLayer = LAYERS.computeIfAbsent(entity, ignored -> new MobLayer(ClientBattleAnimation.createLayer()));
        tickToEntity(mobLayer, entity.tickCount);
        if (!ClientBattleAnimation.update(mobLayer.animation, triggerSerial, triggerLane, heldLaneMask,
                bpm, battleTick, triggerTick)) {
            return;
        }

        mobLayer.preparedModel = model;
        mobLayer.partialTick = partialTick;
    }

    public static void applyPreparedAnimation(LivingEntity entity, HumanoidModel<?> model) {
        MobLayer mobLayer = LAYERS.get(entity);
        if (mobLayer == null || mobLayer.preparedModel != model || mobLayer.animation.layer.getAnimation() == null) {
            return;
        }

        float partialTick = mobLayer.partialTick;
        restoreAppliedPose(mobLayer);
        mobLayer.originalPose = ModelPose.capture(model);
        mobLayer.appliedModel = model;
        resetToStableBasePose(model);
        applyAnimationPose(mobLayer, model, partialTick);
    }

    public static void finishPreparedAnimation(LivingEntity entity, HumanoidModel<?> model) {
        MobLayer mobLayer = LAYERS.get(entity);
        if (mobLayer == null || mobLayer.appliedModel != model) {
            return;
        }
        restoreAppliedPose(mobLayer);
        mobLayer.preparedModel = null;
    }

    static void stopBattleAnimation(LivingEntity entity) {
        MobLayer mobLayer = LAYERS.remove(entity);
        if (mobLayer != null) {
            restoreAppliedPose(mobLayer);
            ClientBattleAnimation.stop(mobLayer.animation);
        }
    }

    public static void stopAllBattleAnimations() {
        for (MobLayer mobLayer : LAYERS.values()) {
            restoreAppliedPose(mobLayer);
            ClientBattleAnimation.stop(mobLayer.animation);
        }
        LAYERS.clear();
    }

    private static void tickToEntity(MobLayer mobLayer, int entityTick) {
        if (mobLayer.lastEntityTick == Integer.MIN_VALUE) {
            mobLayer.lastEntityTick = entityTick;
            return;
        }
        int elapsed = Math.max(0, Math.min(20, entityTick - mobLayer.lastEntityTick));
        for (int tick = 0; tick < elapsed; tick++) {
            mobLayer.animation.layer.tick();
        }
        mobLayer.lastEntityTick = entityTick;
    }

    private static void applyPart(ClientBattleAnimation.Holder animation, String partName, ModelPart part, float partialTick) {
        Vec3f position = animation.layer.get3DTransform(
                partName,
                TransformType.POSITION,
                partialTick,
                new Vec3f(part.x, part.y, part.z)
        );
        part.setPos(position.getX(), position.getY(), position.getZ());
        Vec3f rotation = animation.layer.get3DTransform(
                partName,
                TransformType.ROTATION,
                partialTick,
                new Vec3f(part.xRot, part.yRot, part.zRot)
        );
        part.setRotation(rotation.getX(), rotation.getY(), rotation.getZ());
    }

    private static void applyAnimationPose(MobLayer mobLayer, HumanoidModel<?> model, float partialTick) {
        mobLayer.animation.layer.setupAnim(partialTick);
        model.crouching = false;
        applyPart(mobLayer.animation, "head", model.head, partialTick);
        model.hat.copyFrom(model.head);
        applyPart(mobLayer.animation, "torso", model.body, partialTick);
        applyPart(mobLayer.animation, "leftArm", model.leftArm, partialTick);
        applyPart(mobLayer.animation, "rightArm", model.rightArm, partialTick);
        applyPart(mobLayer.animation, "leftLeg", model.leftLeg, partialTick);
        applyPart(mobLayer.animation, "rightLeg", model.rightLeg, partialTick);
        copyPlayerSkinOverlays(model);
    }

    private static void resetToStableBasePose(HumanoidModel<?> model) {
        // setupAnim leaves locomotion/attack state in the shared renderer model.
        // Battle clips are full-body absolute poses, so using that mutable pose
        // as their positional base can leak a single vanilla frame between two
        // clip samples. The captured pose is restored after rendering; applying
        // from each part's authored base keeps battle animation deterministic.
        model.head.resetPose();
        model.hat.resetPose();
        model.body.resetPose();
        model.leftArm.resetPose();
        model.rightArm.resetPose();
        model.leftLeg.resetPose();
        model.rightLeg.resetPose();
        if (model instanceof PlayerModel<?> playerModel) {
            playerModel.jacket.resetPose();
            playerModel.leftSleeve.resetPose();
            playerModel.rightSleeve.resetPose();
            playerModel.leftPants.resetPose();
            playerModel.rightPants.resetPose();
        }
    }

    private static boolean isDescendant(ModelPart parent, ModelPart candidate) {
        return parent.getAllParts().skip(1).anyMatch(part -> part == candidate);
    }

    private static boolean finite(ModelPart part) {
        return Float.isFinite(part.x) && Float.isFinite(part.y) && Float.isFinite(part.z);
    }

    private static void restoreAppliedPose(MobLayer mobLayer) {
        if (mobLayer.appliedModel != null && mobLayer.originalPose != null) {
            mobLayer.originalPose.restore(mobLayer.appliedModel);
        }
        mobLayer.appliedModel = null;
        mobLayer.originalPose = null;
    }

    private static void copyPlayerSkinOverlays(HumanoidModel<?> model) {
        if (!(model instanceof PlayerModel<?> playerModel)) {
            return;
        }
        playerModel.jacket.copyFrom(model.body);
        playerModel.leftSleeve.copyFrom(model.leftArm);
        playerModel.rightSleeve.copyFrom(model.rightArm);
        playerModel.leftPants.copyFrom(model.leftLeg);
        playerModel.rightPants.copyFrom(model.rightLeg);
    }

    private static final class MobLayer {
        private final ClientBattleAnimation.Holder animation;
        private int lastEntityTick = Integer.MIN_VALUE;
        private HumanoidModel<?> preparedModel;
        private HumanoidModel<?> appliedModel;
        private ModelPose originalPose;
        private float partialTick;

        private MobLayer(ClientBattleAnimation.Holder animation) {
            this.animation = animation;
        }
    }

    private record ModelPose(
            PartPose head,
            PartPose hat,
            PartPose body,
            PartPose leftArm,
            PartPose rightArm,
            PartPose leftLeg,
            PartPose rightLeg,
            PartPose jacket,
            PartPose leftSleeve,
            PartPose rightSleeve,
            PartPose leftPants,
            PartPose rightPants,
            boolean crouching
    ) {
        private static ModelPose capture(HumanoidModel<?> model) {
            if (model instanceof PlayerModel<?> playerModel) {
                return new ModelPose(
                        model.head.storePose(), model.hat.storePose(), model.body.storePose(),
                        model.leftArm.storePose(), model.rightArm.storePose(),
                        model.leftLeg.storePose(), model.rightLeg.storePose(),
                        playerModel.jacket.storePose(), playerModel.leftSleeve.storePose(),
                        playerModel.rightSleeve.storePose(), playerModel.leftPants.storePose(),
                        playerModel.rightPants.storePose(), model.crouching
                );
            }
            return new ModelPose(
                    model.head.storePose(), model.hat.storePose(), model.body.storePose(),
                    model.leftArm.storePose(), model.rightArm.storePose(),
                    model.leftLeg.storePose(), model.rightLeg.storePose(),
                    null, null, null, null, null, model.crouching
            );
        }

        private void restore(HumanoidModel<?> model) {
            model.head.loadPose(head);
            model.hat.loadPose(hat);
            model.body.loadPose(body);
            model.leftArm.loadPose(leftArm);
            model.rightArm.loadPose(rightArm);
            model.leftLeg.loadPose(leftLeg);
            model.rightLeg.loadPose(rightLeg);
            model.crouching = crouching;
            if (model instanceof PlayerModel<?> playerModel && jacket != null) {
                playerModel.jacket.loadPose(jacket);
                playerModel.leftSleeve.loadPose(leftSleeve);
                playerModel.rightSleeve.loadPose(rightSleeve);
                playerModel.leftPants.loadPose(leftPants);
                playerModel.rightPants.loadPose(rightPants);
            }
        }
    }
}
