package ru.gora.musicbattle.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.joml.Quaternionf;
import ru.gora.musicbattle.item.SmileyBallSpawnEggItem;

/** Exercises the shipped Blockbench resources through the real runtime model loader. */
public final class DjCableModelChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ((net.minecraft.core.MappedRegistry<net.minecraft.world.item.Item>)
                net.minecraft.core.registries.BuiltInRegistries.ITEM).unfreeze();
        boolean packaged = args.length > 1;
        Path assets = packaged
                ? FileSystems.newFileSystem(Path.of(args[0], "build/libs/blocky_music-0.1.0.jar")).getPath("/assets")
                : Path.of(args[0], "src/main/resources/assets");
        installResources(assets);
        verifyResources(assets);

        Capture rest = djBlock(Direction.NORTH, false, 0.0F);
        check(rest.points.size() == 72, "DJ booth renders all three authored cubes");
        bounds(rest, new float[]{0.0F, 0.0F, 0.125F, 1.0F, 0.0635F, 0.8125F},
                "DJ booth occupies the authored block bounds");
        check(rest.bounds()[4] - 0.0625F >= 0.0009F,
                "flat platters render above the body instead of sharing its depth plane");
        same(rest, djBlock(Direction.NORTH, true, 0.0F), "DJ animation starts from its rest pose");
        Capture playing = djBlock(Direction.NORTH, true, 0.25F);
        different(rest, playing, "DJ platters move while music is playing");
        same(rest, djBlock(Direction.NORTH, false, 0.25F), "stopped DJ booth stays at rest");
        check(!djBlock(Direction.EAST, false, 0.0F).points.equals(rest.points), "DJ booth follows block facing");

        Capture cable = item(false);
        check(cable.points.size() == 240, "wire item renders all ten authored cubes");
        bounds(cable, new float[]{0.27902913F, 0.4453125F, 0.15402909F,
                        0.72097087F, 0.5546875F, 0.8125F},
                "wire item is centered before display transforms");
        guiBounds(assets, "speaker_wire.json", false);
        guiBounds(assets, "dj_panel.json", true);
        JsonObject wireDisplay = json(assets.resolve("music_battle/models/item/speaker_wire.json"))
                .getAsJsonObject("display");
        near(wireDisplay.getAsJsonObject("firstperson_righthand").getAsJsonArray("rotation").get(1).getAsFloat(),
                135.0F, "wire faces away from the holder in first person");
        near(wireDisplay.getAsJsonObject("thirdperson_righthand").getAsJsonArray("rotation").get(1).getAsFloat(),
                135.0F, "wire faces away from the holder in third person");
        near(wireDisplay.getAsJsonObject("gui").getAsJsonArray("rotation").get(1).getAsFloat(),
                225.0F, "wire inventory orientation is unchanged");

        SmileyBallSpawnEggItem egg = new SmileyBallSpawnEggItem(new net.minecraft.world.item.Item.Properties());
        List<Component> tooltip = new ArrayList<>();
        egg.appendHoverText(ItemStack.EMPTY, null, tooltip, TooltipFlag.NORMAL);
        check(tooltip.size() == 1, "smiley egg adds exactly one flavor line");
        check(tooltip.getFirst().getContents() instanceof TranslatableContents translated
                        && translated.getKey().equals("tooltip.music_battle.smiley_ball_spawn_egg.flavor"),
                "smiley egg tooltip is language-driven");

        System.out.println("DJ/cable model checks PASS: " + checks);
    }

    private static void installResources(Path assets) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeClass, "theUnsafe").get(null);
        Minecraft client = (Minecraft) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, Minecraft.class);
        var resources = new ReloadableResourceManager(PackType.CLIENT_RESOURCES) {
            @Override public Optional<Resource> getResource(ResourceLocation id) {
                Path path = assets.resolve(id.getNamespace()).resolve(id.getPath());
                return Files.exists(path) ? Optional.of(new Resource(null, () -> Files.newInputStream(path)))
                        : Optional.empty();
            }
        };
        field(Minecraft.class, "resourceManager").set(client, resources);
        field(Minecraft.class, "instance").set(null, client);
    }

    private static void verifyResources(Path assets) throws Exception {
        JsonObject dj = json(assets.resolve("music_battle/blockbench/dj_panel.bbmodel"));
        check(dj.getAsJsonArray("elements").size() == 3, "runtime DJ model keeps authored cubes");
        JsonObject animation = dj.getAsJsonArray("animations").get(0).getAsJsonObject();
        check("play".equals(animation.get("name").getAsString()), "runtime DJ model keeps play animation");
        near(animation.get("length").getAsFloat(), 0.5F, "DJ animation length");
        check("loop".equals(animation.get("loop").getAsString()), "DJ animation loops");
        checkImage(assets.resolve("music_battle/textures/block/dj_panel.png"), 64, 64, "DJ texture");

        JsonObject cable = json(assets.resolve("music_battle/blockbench/speaker_wire.bbmodel"));
        check(cable.getAsJsonArray("elements").size() == 10, "runtime wire model keeps authored cubes");
        checkImage(assets.resolve("music_battle/textures/item/speaker_wire.png"), 32, 32, "wire texture");

        JsonObject ru = json(assets.resolve("music_battle/lang/ru_ru.json"));
        JsonObject en = json(assets.resolve("music_battle/lang/en_us.json"));
        check("Провод".equals(ru.get("item.music_battle.speaker_wire").getAsString()), "Russian item name is concise");
        check("Wire".equals(en.get("item.music_battle.speaker_wire").getAsString()), "English item name is concise");
        check("Верите, не верите, но он здесь, и скоро произойдёт что-то ужасное...".equals(
                ru.get("tooltip.music_battle.smiley_ball_spawn_egg.flavor").getAsString()), "Russian egg flavor text");
        check("Wait, is that...?! Hmm, I think I mistook them for someone else.".equals(
                en.get("tooltip.music_battle.smiley_ball_spawn_egg.flavor").getAsString()), "English egg flavor text");
    }

    private static void checkImage(Path path, int width, int height, String reason) throws Exception {
        var image = javax.imageio.ImageIO.read(Files.newInputStream(path));
        check(image != null && image.getWidth() == width && image.getHeight() == height, reason);
    }

    private static JsonObject json(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Capture djBlock(Direction facing, boolean active, float time) {
        Capture capture = new Capture();
        PoseStack pose = new PoseStack();
        BattleCameraModel.renderDjBlock(pose, type -> capture, 15728880, 0, facing, active, time);
        check(pose.clear(), "DJ renderer balances pose stack");
        return capture;
    }

    private static Capture item(boolean dj) {
        Capture capture = new Capture();
        PoseStack pose = new PoseStack();
        if (dj) BattleCameraModel.renderDjItem(pose, type -> capture, 15728880, 0);
        else BattleCameraModel.renderCableItem(pose, type -> capture, 15728880, 0);
        check(pose.clear(), "item renderer balances pose stack");
        return capture;
    }

    private static void guiBounds(Path assets, String modelName, boolean dj) throws Exception {
        JsonObject gui = json(assets.resolve("music_battle/models/item").resolve(modelName))
                .getAsJsonObject("display").getAsJsonObject("gui");
        float[] rotation = vector(gui.getAsJsonArray("rotation"));
        float[] translation = vector(gui.getAsJsonArray("translation"));
        float[] scale = vector(gui.getAsJsonArray("scale"));
        PoseStack pose = new PoseStack();
        pose.translate(translation[0] / 16.0F, translation[1] / 16.0F, translation[2] / 16.0F);
        pose.mulPose(new Quaternionf().rotationXYZ((float)Math.toRadians(rotation[0]),
                (float)Math.toRadians(rotation[1]), (float)Math.toRadians(rotation[2])));
        pose.scale(scale[0], scale[1], scale[2]);
        pose.translate(-0.5F, -0.5F, -0.5F);
        Capture capture = new Capture();
        if (dj) BattleCameraModel.renderDjItem(pose, type -> capture, 15728880, 0);
        else BattleCameraModel.renderCableItem(pose, type -> capture, 15728880, 0);
        float[] b = capture.bounds();
        check(b[0] >= -0.46F && b[3] <= 0.46F && b[1] >= -0.46F && b[4] <= 0.46F,
                modelName + " fits the GUI slot: x=[" + b[0] + "," + b[3] + "] y=[" + b[1] + "," + b[4] + "]");
        check(b[3] - b[0] >= 0.18F && b[4] - b[1] >= 0.08F,
                modelName + " remains clearly visible in the GUI slot");
    }

    private static float[] vector(com.google.gson.JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static void same(Capture a, Capture b, String reason) {
        check(a.points.equals(b.points), reason);
    }

    private static void different(Capture a, Capture b, String reason) {
        check(!a.points.equals(b.points), reason);
    }

    private static void bounds(Capture capture, float[] expected, String reason) {
        float[] actual = capture.bounds();
        for (int index = 0; index < expected.length; index++) {
            check(Math.abs(actual[index] - expected[index]) < 0.0001F,
                    reason + ": actual=" + java.util.Arrays.toString(actual)
                            + " expected=" + java.util.Arrays.toString(expected));
        }
    }

    private static void near(float actual, float expected, String reason) {
        check(Math.abs(actual - expected) < 0.0001F, reason + ": " + actual + " != " + expected);
    }

    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record Point(float x, float y, float z) {}

    private static final class Capture implements VertexConsumer {
        private final List<Point> points = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { points.add(new Point(x, y, z)); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { check(Float.isFinite(u) && Float.isFinite(v), "finite UV"); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        private float[] bounds() {
            float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (Point point : points) {
                bounds[0] = Math.min(bounds[0], point.x); bounds[1] = Math.min(bounds[1], point.y); bounds[2] = Math.min(bounds[2], point.z);
                bounds[3] = Math.max(bounds[3], point.x); bounds[4] = Math.max(bounds[4], point.y); bounds[5] = Math.max(bounds[5], point.z);
            }
            return bounds;
        }
    }
}
