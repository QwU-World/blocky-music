package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Quaternionf;

/** Executes the model loader and block/item rendering into captured vertices, without a window. */
public final class SpeakerModelChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path assets = args.length > 1
                ? FileSystems.newFileSystem(Path.of(args[0], "build/libs/blocky_music-0.1.0.jar")).getPath("/assets")
                : Path.of(args[0], "src/main/resources/assets");
        verifyResources(assets);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeClass, "theUnsafe").get(null);
        Minecraft client = (Minecraft) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, Minecraft.class);
        var resources = new ReloadableResourceManager(PackType.CLIENT_RESOURCES) {
            @Override public Optional<Resource> getResource(ResourceLocation id) {
                Path path = assets.resolve(id.getNamespace()).resolve(id.getPath());
                return Files.exists(path) ? Optional.of(new Resource(null, () -> Files.newInputStream(path))) : Optional.empty();
            }
        };
        field(Minecraft.class, "resourceManager").set(client, resources);
        field(Minecraft.class, "instance").set(null, client);
        for (boolean horizontal : new boolean[]{false, true}) {
            Capture rest = block(horizontal, Direction.NORTH, false, 0);
            check(rest.points.size() == 216, "Both speakers must render all nine authored cubes; got " + rest.points.size());
            bounds(rest, horizontal ? new float[]{0,0,.25F,2,1,1}
                    : new float[]{0,0,.25F,1,2,1}, "rear face sits flush with the block edge");
            Capture item = new Capture();
            BattleCameraModel.renderSpeakerItem(new PoseStack(), type -> item, 15728880, 0, horizontal);
            bounds(item, horizontal ? new float[]{-.5F,0,.125F,1.5F,1,.875F}
                    : new float[]{0,0,.125F,1,2,.875F}, "inventory remains centered after wall alignment");
            Capture gui = guiItem(horizontal, assets);
            float[] guiBounds = gui.bounds();
            check(guiBounds[0] >= -.46F && guiBounds[3] <= .46F
                            && guiBounds[1] >= -.46F && guiBounds[4] <= .46F,
                    (horizontal ? "horizontal" : "upright") + " inventory bounds x=["
                            + guiBounds[0] + "," + guiBounds[3] + "] y=["
                            + guiBounds[1] + "," + guiBounds[4] + "]");
            same(rest, block(horizontal, Direction.NORTH, false, .25F), "inactive block stays in rest pose");
            same(rest, block(horizontal, Direction.NORTH, true, 0), "active starts in rest pose");
            Capture middle = block(horizontal, Direction.NORTH, true, .25F);
            check(!middle.points.equals(rest.points), "playing actually moves vertices");
            near(middle.bounds()[horizontal ? 4 : 3], 1.05F,
                    "updated clip pulses across its authored horizontal axis");
            same(middle, block(horizontal, Direction.NORTH, true, .75F), "animation repeats every half second");
            same(rest, block(horizontal, Direction.NORTH, true, .5F), "loop closes without residual transform");
            for (Direction facing : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                Capture rotated = block(horizontal, facing, false, 0);
                bounds(rotated, expectedBounds(horizontal, facing), "flush rear face when facing " + facing);
            }
        }
        System.out.println("Speaker model checks PASS: " + checks);
    }
    private static Capture guiItem(boolean horizontal, Path assets) throws Exception {
        String name = horizontal ? "horizontal_speaker.json" : "speaker.json";
        var root = com.google.gson.JsonParser.parseString(Files.readString(
                assets.resolve("music_battle/models/item").resolve(name))).getAsJsonObject();
        var gui = root.getAsJsonObject("display").getAsJsonObject("gui");
        float[] rotation = vector(gui.getAsJsonArray("rotation"));
        float[] translation = vector(gui.getAsJsonArray("translation"));
        float[] scale = vector(gui.getAsJsonArray("scale"));
        PoseStack pose = new PoseStack();
        pose.translate(translation[0] / 16.0F, translation[1] / 16.0F, translation[2] / 16.0F);
        pose.mulPose(new Quaternionf().rotationXYZ((float)Math.toRadians(rotation[0]),
                (float)Math.toRadians(rotation[1]), (float)Math.toRadians(rotation[2])));
        pose.scale(scale[0], scale[1], scale[2]);
        // ItemRenderer applies this after the baked model's display transform, including for BEWLR items.
        pose.translate(-0.5F, -0.5F, -0.5F);
        Capture capture = new Capture();
        BattleCameraModel.renderSpeakerItem(pose, type -> capture, 15728880, 0, horizontal);
        return capture;
    }
    private static float[] vector(com.google.gson.JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }
    private static float[] expectedBounds(boolean horizontal, Direction facing) {
        if (horizontal) {
            return switch (facing) {
                case NORTH -> new float[]{0,0,.25F,2,1,1};
                case SOUTH -> new float[]{-1,0,0,1,1,.75F};
                case EAST -> new float[]{0,0,0,.75F,1,2};
                case WEST -> new float[]{.25F,0,-1,1,1,1};
                default -> throw new AssertionError("Horizontal facing required: " + facing);
            };
        }
        return switch (facing) {
            case NORTH -> new float[]{0,0,.25F,1,2,1};
            case SOUTH -> new float[]{0,0,0,1,2,.75F};
            case EAST -> new float[]{0,0,0,.75F,2,1};
            case WEST -> new float[]{.25F,0,0,1,2,1};
            default -> throw new AssertionError("Horizontal facing required: " + facing);
        };
    }
    private static void verifyResources(Path assets) throws Exception {
        var model = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("music_battle/blockbench/speaker.bbmodel"))).getAsJsonObject();
        byte[] expected = java.util.Base64.getDecoder().decode(model.getAsJsonArray("textures").get(0).getAsJsonObject().get("source").getAsString().split(",", 2)[1]);
        byte[] actual = Files.readAllBytes(assets.resolve("music_battle/textures/block/speaker.png"));
        check(java.util.Arrays.equals(expected, actual), "runtime texture matches the embedded authored PNG");
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(actual));
        check(image != null && image.getWidth() == 128 && image.getHeight() == 128, "valid 128x128 texture");
        var clips = model.getAsJsonArray("animations");
        for (String name : new String[]{"Playing", "Horizontal Playing"}) {
            boolean found = false;
            for (var entry : clips) {
                var clip = entry.getAsJsonObject();
                if (name.equals(clip.get("name").getAsString())) {
                    found = true;
                    near(clip.get("length").getAsFloat(), .5F, "authored beat clip length");
                    check("loop".equals(clip.get("loop").getAsString()), "authored beat clip loops");
                }
            }
            check(found, "required animation: " + name);
        }
    }
    private static Capture block(boolean horizontal, Direction facing, boolean active, float time) {
        Capture capture = new Capture();
        PoseStack pose = new PoseStack();
        BattleCameraModel.renderSpeakerBlock(pose, type -> capture, 15728880, 0, facing, active, time, horizontal);
        check(pose.clear(), "renderer balances pose stack");
        return capture;
    }
    private static void same(Capture a, Capture b, String reason) {
        check(a.points.size() == b.points.size(), reason + " vertex count");
        for (int i=0;i<a.points.size();i++) {
            Point x=a.points.get(i),y=b.points.get(i);
            near(x.x,y.x,reason); near(x.y,y.y,reason); near(x.z,y.z,reason);
        }
    }
    private static void bounds(Capture c, float[] expected, String reason) {
        float[] actual = c.bounds();
        for (int i=0;i<6;i++) near(actual[i], expected[i], reason + " axis " + i);
    }
    private static void near(float a,float b,String reason) { check(Math.abs(a-b)<.0001F, reason + ": " + a + " != " + b); }
    private static void check(boolean value,String reason) { checks++; if(!value)throw new AssertionError(reason); }
    private static Field field(Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    private record Point(float x,float y,float z) {}
    private static final class Capture implements VertexConsumer {
        private final List<Point> points = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x,float y,float z){points.add(new Point(x,y,z));return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        @Override public VertexConsumer setUv(float u,float v){check(Float.isFinite(u)&&Float.isFinite(v),"finite UV");return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
        private float[] bounds(){
            float[] b={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(Point p:points){b[0]=Math.min(b[0],p.x);b[1]=Math.min(b[1],p.y);b[2]=Math.min(b[2],p.z);b[3]=Math.max(b[3],p.x);b[4]=Math.max(b[4],p.y);b[5]=Math.max(b[5],p.z);}
            return b;
        }
    }
}
