package ru.gora.musicbattle.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import ru.gora.musicbattle.MusicBattleMod;

final class BattleCameraModel {
    private static final ResourceLocation MODEL = MusicBattleMod.id("blockbench/camera.bbmodel");
    private static final ResourceLocation TEXTURE = MusicBattleMod.id("textures/block/battle_camera.png");
    private static final ResourceLocation SPEAKER_MODEL = MusicBattleMod.id("blockbench/speaker.bbmodel");
    private static final ResourceLocation SPEAKER_TEXTURE = MusicBattleMod.id("textures/block/speaker.png");
    private static final ResourceLocation DJ_MODEL = MusicBattleMod.id("blockbench/dj_panel.bbmodel");
    private static final ResourceLocation DJ_TEXTURE = MusicBattleMod.id("textures/block/dj_panel.png");
    private static final ResourceLocation CABLE_MODEL = MusicBattleMod.id("blockbench/speaker_wire.bbmodel");
    private static final ResourceLocation CABLE_TEXTURE = MusicBattleMod.id("textures/item/speaker_wire.png");
    private static volatile Definition cachedDefinition;
    private static volatile Definition cachedSpeakerDefinition;
    private static volatile Definition cachedHorizontalSpeakerDefinition;
    private static volatile Definition cachedDjDefinition;
    private static volatile Definition cachedCableDefinition;
    private static boolean loadFailed;
    private static boolean speakerLoadFailed;
    private static boolean horizontalSpeakerLoadFailed;
    private static boolean djLoadFailed;
    private static boolean cableLoadFailed;

    private BattleCameraModel() {
    }

    static void renderBlock(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay,
                            Direction facing, float animationSeconds) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(facingRotation(facing))));
        render(poseStack, bufferSource, packedLight, packedOverlay, definition(), TEXTURE,
                animationSeconds, false);
        poseStack.popPose();
    }

    static void renderItem(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        render(poseStack, bufferSource, packedLight, packedOverlay, definition(), TEXTURE,
                Float.POSITIVE_INFINITY, false);
        poseStack.popPose();
    }

    static void renderSpeakerBlock(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                   int packedOverlay, Direction facing, boolean active, float animationSeconds,
                                   boolean horizontal) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(facingRotation(facing))));
        placeSpeaker(poseStack, horizontal, true);
        render(poseStack, bufferSource, packedLight, packedOverlay, speakerDefinition(horizontal), SPEAKER_TEXTURE,
                active ? animationSeconds : 0.0F, active);
        poseStack.popPose();
    }

    static void renderSpeakerItem(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                  int packedOverlay, boolean horizontal) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        placeSpeaker(poseStack, horizontal, false);
        render(poseStack, bufferSource, packedLight, packedOverlay, speakerDefinition(horizontal), SPEAKER_TEXTURE,
                0.0F, false);
        poseStack.popPose();
    }

    static void renderDjBlock(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                              int packedOverlay, Direction facing, boolean active, float animationSeconds) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(facingRotation(facing))));
        render(poseStack, bufferSource, packedLight, packedOverlay, djDefinition(), DJ_TEXTURE,
                active ? animationSeconds : 0.0F, active);
        poseStack.popPose();
    }

    static void renderDjItem(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                             int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.46875D, 0.53125D);
        render(poseStack, bufferSource, packedLight, packedOverlay, djDefinition(), DJ_TEXTURE,
                0.0F, false);
        poseStack.popPose();
    }

    static void renderCableItem(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.4140625D, 0.375D);
        render(poseStack, bufferSource, packedLight, packedOverlay, cableDefinition(), CABLE_TEXTURE,
                0.0F, false);
        poseStack.popPose();
    }

    private static void placeSpeaker(PoseStack poseStack, boolean horizontal, boolean flushToWall) {
        // Both clips include the authored root pose, including the horizontal quarter-turn.
        // World blocks touch the rear edge; inventory models remain centered in their display frame.
        double horizontalOffset = horizontal ? (flushToWall ? 1.0D : 0.5D) : 0.0D;
        poseStack.translate(horizontalOffset, 0.0D, flushToWall ? 0.0D : -0.125D);
    }

    private static void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay,
                               Definition definition, ResourceLocation texture, float animationSeconds,
                               boolean looping) {
        if (definition == null) {
            return;
        }
        float time;
        if (!Float.isFinite(animationSeconds)) {
            time = definition.animationLength;
        } else if (looping && definition.animationLength > 0.0F) {
            time = Math.floorMod(Mth.floor(animationSeconds * 1000.0F),
                    Math.max(1, Mth.floor(definition.animationLength * 1000.0F))) / 1000.0F;
        } else {
            time = Mth.clamp(animationSeconds, 0.0F, definition.animationLength);
        }
        VertexConsumer vertices = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        for (Bone root : definition.roots) {
            renderBone(root, definition.animation, time, definition.textureWidth, definition.textureHeight,
                    definition.flatSurfaceLift, poseStack, vertices, packedLight, packedOverlay);
        }
    }

    private static void renderBone(Bone bone, Map<String, BoneAnimation> animation, float time,
                                   float textureWidth, float textureHeight, float flatSurfaceLift, PoseStack poseStack,
                                   VertexConsumer vertices, int packedLight, int packedOverlay) {
        BoneAnimation channels = animation.get(bone.uuid);
        Vec3 position = channels == null ? Vec3.ZERO : channels.position.sample(time, Vec3.ZERO);
        Vec3 rotation = bone.rotation.add(channels == null ? Vec3.ZERO : channels.rotation.sample(time, Vec3.ZERO));
        Vec3 scale = channels == null ? Vec3.ONE : channels.scale.sample(time, Vec3.ONE);

        poseStack.pushPose();
        poseStack.translate(position.x / 16.0F, position.y / 16.0F, position.z / 16.0F);
        poseStack.translate(bone.origin.x / 16.0F, bone.origin.y / 16.0F, bone.origin.z / 16.0F);
        applyRotation(poseStack, rotation);
        poseStack.scale(scale.x, scale.y, scale.z);
        poseStack.translate(-bone.origin.x / 16.0F, -bone.origin.y / 16.0F, -bone.origin.z / 16.0F);

        for (Cube cube : bone.cubes) {
            renderCube(cube, textureWidth, textureHeight, flatSurfaceLift,
                    poseStack, vertices, packedLight, packedOverlay);
        }
        for (Bone child : bone.children) {
            renderBone(child, animation, time, textureWidth, textureHeight, flatSurfaceLift,
                    poseStack, vertices, packedLight, packedOverlay);
        }
        poseStack.popPose();
    }

    private static void renderCube(Cube cube, float textureWidth, float textureHeight, float flatSurfaceLift,
                                   PoseStack poseStack,
                                   VertexConsumer vertices, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        if (flatSurfaceLift > 0.0F && Math.abs(cube.to.y - cube.from.y) < 0.0001F) {
            poseStack.translate(0.0F, flatSurfaceLift, 0.0F);
        }
        poseStack.translate(cube.origin.x / 16.0F, cube.origin.y / 16.0F, cube.origin.z / 16.0F);
        applyRotation(poseStack, cube.rotation);
        poseStack.translate(-cube.origin.x / 16.0F, -cube.origin.y / 16.0F, -cube.origin.z / 16.0F);

        float[] shape = new float[Direction.values().length];
        shape[FaceInfo.Constants.MIN_X] = cube.from.x / 16.0F;
        shape[FaceInfo.Constants.MIN_Y] = cube.from.y / 16.0F;
        shape[FaceInfo.Constants.MIN_Z] = cube.from.z / 16.0F;
        shape[FaceInfo.Constants.MAX_X] = cube.to.x / 16.0F;
        shape[FaceInfo.Constants.MAX_Y] = cube.to.y / 16.0F;
        shape[FaceInfo.Constants.MAX_Z] = cube.to.z / 16.0F;

        for (Map.Entry<Direction, Face> entry : cube.faces.entrySet()) {
            Direction direction = entry.getKey();
            Face face = entry.getValue();
            BlockFaceUV uv = new BlockFaceUV(new float[]{face.u1, face.v1, face.u2, face.v2}, face.rotation);
            for (int index = 0; index < 4; index++) {
                FaceInfo.VertexInfo info = FaceInfo.fromFacing(direction).getVertexInfo(index);
                vertices.addVertex(poseStack.last(), shape[info.xFace], shape[info.yFace], shape[info.zFace])
                        .setColor(0xFFFFFFFF)
                        .setUv(uv.getU(index) / textureWidth, uv.getV(index) / textureHeight)
                        .setOverlay(packedOverlay)
                        .setLight(packedLight)
                        .setNormal(poseStack.last(), direction.getStepX(), direction.getStepY(), direction.getStepZ());
            }
        }
        poseStack.popPose();
    }

    private static void applyRotation(PoseStack poseStack, Vec3 rotation) {
        if (!rotation.isZero()) {
            poseStack.mulPose(new Quaternionf().rotationZYX(
                    (float) Math.toRadians(rotation.z),
                    (float) Math.toRadians(rotation.y),
                    (float) Math.toRadians(rotation.x)
            ));
        }
    }

    private static float facingRotation(Direction facing) {
        return switch (facing) {
            case EAST -> -90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
    }

    private static Definition definition() {
        Definition current = cachedDefinition;
        if (current != null || loadFailed) {
            return current;
        }
        synchronized (BattleCameraModel.class) {
            if (cachedDefinition != null || loadFailed) {
                return cachedDefinition;
            }
            try {
                cachedDefinition = loadDefinition(MODEL, "place");
            } catch (Exception exception) {
                loadFailed = true;
                MusicBattleMod.LOGGER.error("Unable to load Blocky Music battle camera model '{}'.", MODEL, exception);
            }
            return cachedDefinition;
        }
    }

    private static Definition speakerDefinition(boolean horizontal) {
        Definition current = horizontal ? cachedHorizontalSpeakerDefinition : cachedSpeakerDefinition;
        if (current != null || (horizontal ? horizontalSpeakerLoadFailed : speakerLoadFailed)) {
            return current;
        }
        synchronized (BattleCameraModel.class) {
            current = horizontal ? cachedHorizontalSpeakerDefinition : cachedSpeakerDefinition;
            if (current != null || (horizontal ? horizontalSpeakerLoadFailed : speakerLoadFailed)) {
                return current;
            }
            try {
                current = loadDefinition(SPEAKER_MODEL, horizontal ? "Horizontal Playing" : "Playing");
                if (horizontal) {
                    cachedHorizontalSpeakerDefinition = current;
                } else {
                    cachedSpeakerDefinition = current;
                }
            } catch (Exception exception) {
                if (horizontal) {
                    horizontalSpeakerLoadFailed = true;
                } else {
                    speakerLoadFailed = true;
                }
                MusicBattleMod.LOGGER.error("Unable to load Blocky Music speaker model '{}'.", SPEAKER_MODEL, exception);
            }
            return current;
        }
    }

    private static Definition djDefinition() {
        Definition current = cachedDjDefinition;
        if (current != null || djLoadFailed) {
            return current;
        }
        synchronized (BattleCameraModel.class) {
            if (cachedDjDefinition != null || djLoadFailed) {
                return cachedDjDefinition;
            }
            try {
                // The authored platters are zero-thickness planes on top of the body.
                // Lift only those render planes very slightly to avoid depth fighting.
                cachedDjDefinition = loadDefinition(DJ_MODEL, "play", 0.001F);
            } catch (Exception exception) {
                djLoadFailed = true;
                MusicBattleMod.LOGGER.error("Unable to load Blocky Music DJ panel model '{}'.", DJ_MODEL, exception);
            }
            return cachedDjDefinition;
        }
    }

    private static Definition cableDefinition() {
        Definition current = cachedCableDefinition;
        if (current != null || cableLoadFailed) {
            return current;
        }
        synchronized (BattleCameraModel.class) {
            if (cachedCableDefinition != null || cableLoadFailed) {
                return cachedCableDefinition;
            }
            try {
                cachedCableDefinition = loadDefinition(CABLE_MODEL, "");
            } catch (Exception exception) {
                cableLoadFailed = true;
                MusicBattleMod.LOGGER.error("Unable to load Blocky Music wire model '{}'.", CABLE_MODEL, exception);
            }
            return cachedCableDefinition;
        }
    }

    private static Definition loadDefinition(ResourceLocation model, String animationName) throws IOException {
        return loadDefinition(model, animationName, 0.0F);
    }

    private static Definition loadDefinition(ResourceLocation model, String animationName,
                                             float flatSurfaceLift) throws IOException {
        Resource resource = Minecraft.getInstance().getResourceManager().getResource(model)
                .orElseThrow(() -> new IOException("Missing model resource " + model));
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject resolution = root.getAsJsonObject("resolution");
            float textureWidth = resolution.get("width").getAsFloat();
            float textureHeight = resolution.get("height").getAsFloat();

            Map<String, Cube> cubes = parseCubes(root.getAsJsonArray("elements"));
            Map<String, Group> groups = parseGroups(root.getAsJsonArray("groups"));
            List<Bone> roots = new ArrayList<>();
            for (JsonElement node : root.getAsJsonArray("outliner")) {
                if (node.isJsonObject()) {
                    roots.add(parseBone(node.getAsJsonObject(), groups, cubes));
                } else if (node.isJsonPrimitive()) {
                    Cube cube = cubes.get(node.getAsString());
                    if (cube != null) {
                        roots.add(new Bone("root:" + node.getAsString(), Vec3.ZERO, Vec3.ZERO,
                                List.of(cube), List.of()));
                    }
                }
            }

            JsonObject selectedAnimation = findAnimation(root.getAsJsonArray("animations"), animationName);
            float animationLength = selectedAnimation == null ? 0.0F : selectedAnimation.get("length").getAsFloat();
            Map<String, BoneAnimation> animation = selectedAnimation == null
                    ? Map.of()
                    : parseAnimation(selectedAnimation.getAsJsonObject("animators"));
            return new Definition(List.copyOf(roots), animation, animationLength, textureWidth, textureHeight,
                    Math.max(0.0F, flatSurfaceLift));
        }
    }

    private static Map<String, Cube> parseCubes(JsonArray array) {
        Map<String, Cube> cubes = new HashMap<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            EnumMap<Direction, Face> faces = new EnumMap<>(Direction.class);
            JsonObject faceObject = object.getAsJsonObject("faces");
            for (Direction direction : Direction.values()) {
                JsonElement faceElement = faceObject.get(direction.getSerializedName());
                if (faceElement == null || !faceElement.isJsonObject()) {
                    continue;
                }
                JsonObject jsonFace = faceElement.getAsJsonObject();
                JsonArray uv = jsonFace.getAsJsonArray("uv");
                int rotation = jsonFace.has("rotation") ? jsonFace.get("rotation").getAsInt() : 0;
                faces.put(direction, new Face(
                        uv.get(0).getAsFloat(), uv.get(1).getAsFloat(),
                        uv.get(2).getAsFloat(), uv.get(3).getAsFloat(), rotation
                ));
            }
            cubes.put(object.get("uuid").getAsString(), new Cube(
                    vector(object, "from", Vec3.ZERO),
                    vector(object, "to", Vec3.ZERO),
                    vector(object, "origin", Vec3.ZERO),
                    vector(object, "rotation", Vec3.ZERO),
                    faces
            ));
        }
        return cubes;
    }

    private static Map<String, Group> parseGroups(JsonArray array) {
        Map<String, Group> groups = new HashMap<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            groups.put(object.get("uuid").getAsString(), new Group(
                    vector(object, "origin", Vec3.ZERO),
                    vector(object, "rotation", Vec3.ZERO)
            ));
        }
        return groups;
    }

    private static Bone parseBone(JsonObject node, Map<String, Group> groups, Map<String, Cube> cubes) {
        String uuid = node.get("uuid").getAsString();
        Group group = groups.getOrDefault(uuid, new Group(Vec3.ZERO, Vec3.ZERO));
        List<Cube> boneCubes = new ArrayList<>();
        List<Bone> children = new ArrayList<>();
        for (JsonElement child : node.getAsJsonArray("children")) {
            if (child.isJsonPrimitive()) {
                Cube cube = cubes.get(child.getAsString());
                if (cube != null) {
                    boneCubes.add(cube);
                }
            } else if (child.isJsonObject()) {
                children.add(parseBone(child.getAsJsonObject(), groups, cubes));
            }
        }
        return new Bone(uuid, group.origin, group.rotation, List.copyOf(boneCubes), List.copyOf(children));
    }

    private static JsonObject findAnimation(JsonArray animations, String animationName) {
        if (animations == null) {
            return null;
        }
        for (JsonElement element : animations) {
            JsonObject animation = element.getAsJsonObject();
            if (animationName.equals(animation.get("name").getAsString())) {
                return animation;
            }
        }
        return null;
    }

    private static Map<String, BoneAnimation> parseAnimation(JsonObject animators) {
        Map<String, BoneAnimation> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            JsonObject animator = entry.getValue().getAsJsonObject();
            List<Keyframe> positions = new ArrayList<>();
            List<Keyframe> rotations = new ArrayList<>();
            List<Keyframe> scales = new ArrayList<>();
            JsonArray keyframes = animator.has("keyframes") ? animator.getAsJsonArray("keyframes") : new JsonArray();
            for (JsonElement element : keyframes) {
                JsonObject keyframe = element.getAsJsonObject();
                String channel = keyframe.get("channel").getAsString();
                JsonArray points = keyframe.getAsJsonArray("data_points");
                if (points == null || points.isEmpty()) {
                    continue;
                }
                Keyframe parsed = new Keyframe(
                        keyframe.get("time").getAsFloat(),
                        vector(points.get(0).getAsJsonObject()),
                        keyframe.has("interpolation") ? keyframe.get("interpolation").getAsString() : "linear"
                );
                switch (channel) {
                    case "position" -> positions.add(parsed);
                    case "rotation" -> rotations.add(parsed);
                    case "scale" -> scales.add(parsed);
                    default -> {
                    }
                }
            }
            result.put(entry.getKey(), new BoneAnimation(
                    new Curve(sorted(positions)),
                    new Curve(sorted(rotations)),
                    new Curve(sorted(scales))
            ));
        }
        return Map.copyOf(result);
    }

    private static List<Keyframe> sorted(List<Keyframe> keyframes) {
        keyframes.sort(Comparator.comparingDouble(Keyframe::time));
        return List.copyOf(keyframes);
    }

    private static Vec3 vector(JsonObject object, String member, Vec3 fallback) {
        return object.has(member) ? vector(object.getAsJsonArray(member)) : fallback;
    }

    private static Vec3 vector(JsonArray array) {
        return new Vec3(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private static Vec3 vector(JsonObject point) {
        return new Vec3(number(point, "x"), number(point, "y"), number(point, "z"));
    }

    private static float number(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null) {
            return 0.0F;
        }
        try {
            return value.getAsFloat();
        } catch (NumberFormatException ignored) {
            return 0.0F;
        }
    }

    private record Definition(List<Bone> roots, Map<String, BoneAnimation> animation, float animationLength,
                              float textureWidth, float textureHeight, float flatSurfaceLift) {
    }

    private record Bone(String uuid, Vec3 origin, Vec3 rotation, List<Cube> cubes, List<Bone> children) {
    }

    private record Group(Vec3 origin, Vec3 rotation) {
    }

    private record Cube(Vec3 from, Vec3 to, Vec3 origin, Vec3 rotation, EnumMap<Direction, Face> faces) {
    }

    private record Face(float u1, float v1, float u2, float v2, int rotation) {
    }

    private record BoneAnimation(Curve position, Curve rotation, Curve scale) {
    }

    private record Keyframe(float time, Vec3 value, String interpolation) {
    }

    private record Curve(List<Keyframe> keyframes) {
        private Vec3 sample(float time, Vec3 defaultValue) {
            if (keyframes.isEmpty()) {
                return defaultValue;
            }

            List<Keyframe> points = keyframes;
            Keyframe first = points.get(0);
            if (time <= first.time) {
                if (first.time <= 0.0F) {
                    return first.value;
                }
                return defaultValue.lerp(first.value, eased(time / first.time, first.interpolation));
            }

            for (int index = 0; index < points.size() - 1; index++) {
                Keyframe current = points.get(index);
                Keyframe next = points.get(index + 1);
                if (time > next.time) {
                    continue;
                }
                float range = Math.max(0.0001F, next.time - current.time);
                float progress = Mth.clamp((time - current.time) / range, 0.0F, 1.0F);
                if ("catmullrom".equals(current.interpolation)) {
                    Vec3 previous = index > 0 ? points.get(index - 1).value : current.value;
                    Vec3 following = index + 2 < points.size() ? points.get(index + 2).value : next.value;
                    return Vec3.catmull(previous, current.value, next.value, following, progress);
                }
                return current.value.lerp(next.value, eased(progress, current.interpolation));
            }
            return points.get(points.size() - 1).value;
        }

        private static float eased(float value, String interpolation) {
            float clamped = Mth.clamp(value, 0.0F, 1.0F);
            return "bezier".equals(interpolation) ? clamped * clamped * (3.0F - 2.0F * clamped) : clamped;
        }
    }

    private record Vec3(float x, float y, float z) {
        private static final Vec3 ZERO = new Vec3(0.0F, 0.0F, 0.0F);
        private static final Vec3 ONE = new Vec3(1.0F, 1.0F, 1.0F);

        private Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        private Vec3 lerp(Vec3 other, float progress) {
            return new Vec3(
                    Mth.lerp(progress, x, other.x),
                    Mth.lerp(progress, y, other.y),
                    Mth.lerp(progress, z, other.z)
            );
        }

        private boolean isZero() {
            return x == 0.0F && y == 0.0F && z == 0.0F;
        }

        private static Vec3 catmull(Vec3 first, Vec3 second, Vec3 third, Vec3 fourth, float progress) {
            return new Vec3(
                    catmull(first.x, second.x, third.x, fourth.x, progress),
                    catmull(first.y, second.y, third.y, fourth.y, progress),
                    catmull(first.z, second.z, third.z, fourth.z, progress)
            );
        }

        private static float catmull(float first, float second, float third, float fourth, float progress) {
            float squared = progress * progress;
            float cubed = squared * progress;
            return 0.5F * ((2.0F * second)
                    + (-first + third) * progress
                    + (2.0F * first - 5.0F * second + 4.0F * third - fourth) * squared
                    + (-first + 3.0F * second - 3.0F * third + fourth) * cubed);
        }
    }
}
