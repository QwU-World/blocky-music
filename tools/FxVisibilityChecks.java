import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import ru.gora.musicbattle.client.ClientTrackEffects;
import ru.gora.musicbattle.data.*;

public final class FxVisibilityChecks {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static boolean flag(Object state, String name) throws Exception {
        try { return (boolean) state.getClass().getMethod(name).invoke(state); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Missing visibility behavior: " + name, missing); }
    }
    public static void main(String[] args) throws Exception {
        var isolatedPresets = java.nio.file.Files.createTempDirectory("blocky-hud-presets-");
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(isolatedPresets);
        nestedCameraHud();
        var presetReader = Class.forName("ru.gora.musicbattle.client.ClientDjPresetStorage")
                .getDeclaredMethod("readStrictEffect", JsonObject.class);
        presetReader.setAccessible(true);
        JsonObject oldPreset = DjVisualEffectConfig.of(TrackEffect.Type.FLASH).toJson();
        oldPreset.remove("hideParticles"); oldPreset.remove("hideFirstPersonHand"); oldPreset.remove("hideHud");
        oldPreset.remove("hideBlockyMusicHud");
        try { presetReader.invoke(null, oldPreset); check(true, "Legacy presets remain readable"); }
        catch (java.lang.reflect.InvocationTargetException ex) {
            throw new AssertionError("New hiding fields must not invalidate existing DJ presets", ex.getCause());
        }
        var type = TrackEffect.Type.byId("hide_particles_hand");
        check(type.id().equals("hide_particles_hand"), "Hide particles/hand FX must survive type lookup");
        check(!flag(TrackEffectSettings.readJson(new JsonObject()), "hideHud"), "Legacy charts must keep HUD visible");
        check(!flag(TrackEffectSettings.readJson(new JsonObject()), "hideBlockyMusicHud"), "Missing mod HUD switch must default to visible");
        for (boolean modHud : new boolean[]{false,true}) for (boolean hud : new boolean[]{false,true}) for (boolean particles : new boolean[]{false,true}) for (boolean hand : new boolean[]{false,true}) {
            JsonObject json = new JsonObject();
            json.addProperty("hideParticles", particles); json.addProperty("hideFirstPersonHand", hand);
            json.addProperty("hideHud", hud);
            json.addProperty("hideBlockyMusicHud", modHud);
            var settings = TrackEffectSettings.readJson(json);
            JsonObject saved = new JsonObject(); settings.writeJson(saved);
            check(saved.get("hideParticles").getAsBoolean() == particles, "Particle choice lost in JSON");
            check(saved.get("hideFirstPersonHand").getAsBoolean() == hand, "Hand choice lost in JSON");
            check(saved.get("hideHud").getAsBoolean() == hud, "HUD choice lost in JSON");
            check(saved.get("hideBlockyMusicHud").getAsBoolean() == modHud, "Mod HUD choice lost in JSON");
            var effect = new TrackEffect(type,10,20,1).withSettings(settings);
            var config = DjVisualEffectConfig.of(type).withEffect(effect);
            var reloaded = (DjVisualEffectConfig) presetReader.invoke(null, config.toJson());
            check(reloaded.effect().settings().equals(settings), "Preset reload lost independent switches");
            String presetName = "HUD " + hud + " " + modHud + " " + particles + " " + hand;
            check(ru.gora.musicbattle.client.ClientDjPresetStorage.saveEffectSet(presetName, true, true, List.of(config)),
                    "HUD preset must save to disk");
            var diskPreset = ru.gora.musicbattle.client.ClientDjPresetStorage.loadEffectSet(presetName).orElseThrow();
            check(diskPreset.configs().getFirst().effect().settings().equals(settings), "Disk preset reload lost HUD choices");
            var materialized = reloaded.materialize(10, 30);
            check(materialized.settings().equals(settings), "DJ materialization lost hiding switches");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                effect.write(buffer); buffer.writeInt(0x12345678);
                check(TrackEffect.read(buffer).equals(effect), "Network roundtrip lost hiding choices");
                check(buffer.readInt() == 0x12345678 && buffer.readableBytes() == 0, "Visibility codec consumed adjacent payload bytes");
            }
            finally { buffer.release(); }
            for (float tick : new float[]{0,9.99f,10,15,29.99f,30,80}) {
                var state = ClientTrackEffects.sample(List.of(effect),tick,120,1);
                boolean active = tick >= 10 && tick < 30;
                check(flag(state,"hidesParticles") == (active && particles), "Particle timing/choice mismatch at " + tick);
                check(flag(state,"hidesFirstPersonHand") == (active && hand), "Hand timing/choice mismatch at " + tick);
                check(flag(state,"hidesHud") == (active && hud), "HUD timing/choice mismatch at " + tick);
                check(flag(state,"hidesBlockyMusicHud") == (active && modHud), "Mod HUD timing/choice mismatch at " + tick);
            }
            var invisible = ClientTrackEffects.sample(List.of(effect),15,120,1,0);
            check(!flag(invisible,"hidesParticles") && !flag(invisible,"hidesFirstPersonHand"), "Invisible session must restore scene");
            check(!flag(invisible,"hidesHud"), "Invisible session must restore HUD");
            check(!flag(invisible,"hidesBlockyMusicHud"), "Invisible session must restore mod HUD");
            var prior = ClientTrackEffects.sample(List.of(effect),15,120,1);
            var empty = ClientTrackEffects.sample(List.of(),15,120,1).withPreceding(List.of(prior));
            check(flag(empty,"hidesParticles") == particles && flag(empty,"hidesFirstPersonHand") == hand, "Outgoing DJ stack lost hiding state");
            check(flag(empty,"hidesHud") == hud, "Outgoing DJ stack lost HUD choice");
            check(flag(empty,"hidesBlockyMusicHud") == modHud, "Outgoing DJ stack lost mod HUD choice");
            for (var copied : List.of(settings.withOverlayInterface(true), settings.withStars(1,1,2,1,1,1,1,"left"), settings.withObjectOutlinePalette(List.of(0xFF123456,0xFFABCDEF),1), settings.withColorCorrection(0,1,1,0,1,0))) {
                check(flag(copied,"hideParticles") == particles && flag(copied,"hideFirstPersonHand") == hand, "Settings copy reset hiding choice");
                check(flag(copied,"hideHud") == hud, "Settings copy reset HUD choice");
                check(flag(copied,"hideBlockyMusicHud") == modHud, "Settings copy reset mod HUD choice");
            }
        }
        check(flag(TrackEffectSettings.readJson(new JsonObject()),"hideParticles"), "Default must hide particles");
        check(flag(TrackEffectSettings.DEFAULT,"hideFirstPersonHand"), "Default must hide first-person hand");
        hudRenderHooks();
        frameVisibilityCache();
        FxHudRenderChecks.run();
        ru.gora.musicbattle.client.FxHudEditorChecks.run();
        System.out.println("FX visibility checks passed: " + checks);
        System.out.println("Isolated HUD presets: " + isolatedPresets);
    }
    private static void hudRenderHooks() throws Exception {
        Class<?> controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        Class<?> camera = Class.forName("ru.gora.musicbattle.client.DjCameraPreviewRenderer");
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeClass, "theUnsafe").get(null);
        var allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        Minecraft client = (Minecraft) allocate.invoke(unsafe, Minecraft.class);
        Options options = (Options) allocate.invoke(unsafe, Options.class);
        field(Minecraft.class, "options").set(client, options);
        Object previousClient = field(Minecraft.class, "instance").get(null);
        Object previousState = field(camera, "activeEffectState").get(null);
        Object previousEffects = field(controller, "activeEffects").get(null);
        boolean previousRendering = field(camera, "rendering").getBoolean(null);
        boolean previousActive = field(controller, "active").getBoolean(null);
        try {
            field(Minecraft.class, "instance").set(null, client);
            field(camera, "rendering").setBoolean(null, true);
            field(controller, "active").setBoolean(null, false);
            for (boolean modHud : new boolean[]{false, true}) for (boolean hud : new boolean[]{false, true}) for (boolean manualF1 : new boolean[]{false, true}) {
                JsonObject json = new JsonObject(); json.addProperty("hideHud", hud);
                json.addProperty("hideBlockyMusicHud", modHud);
                var effect = new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND, 10, 20, 1)
                        .withSettings(TrackEffectSettings.readJson(json));
                field(camera, "activeEffectState").set(null, ClientTrackEffects.sample(List.of(effect), 15));
                options.hideGui = manualF1;
                var layer = new RenderGuiLayerEvent.Pre(null, null,
                        ResourceLocation.withDefaultNamespace("hotbar"), (graphics, delta) -> {});
                controller.getMethod("hideGuiLayer", RenderGuiLayerEvent.Pre.class).invoke(null, layer);
                check(layer.isCanceled() == (hud || manualF1), "HUD render hook must suppress vanilla layers independently");
                var fxLayer = new RenderGuiLayerEvent.Pre(null, null,
                        ResourceLocation.fromNamespaceAndPath("music_battle", "battle_overlay"), (graphics, delta) -> {});
                controller.getMethod("hideGuiLayer", RenderGuiLayerEvent.Pre.class).invoke(null, fxLayer);
                check(!fxLayer.isCanceled(), "FX pass must survive hidden HUD");
                var foreignLayer = new RenderGuiLayerEvent.Pre(null, null,
                        ResourceLocation.fromNamespaceAndPath("other_mod", "overlay"), (graphics, delta) -> {});
                controller.getMethod("hideGuiLayer", RenderGuiLayerEvent.Pre.class).invoke(null, foreignLayer);
                check(foreignLayer.isCanceled() == manualF1, "Vanilla HUD effect must not cancel another mod's layer");
                check((boolean) controller.getMethod("hidesBlockyMusicHud").invoke(null) == (modHud || manualF1),
                        "Mod HUD render hook must be independent of vanilla HUD");
                check(options.hideGui == manualF1, "HUD effect must never mutate the manual F1 preference");
                // The Screen is outside GuiLayer rendering, including editor/escape menus.
                var editor = (net.minecraft.client.gui.screens.Screen) allocate.invoke(unsafe,
                        Class.forName("ru.gora.musicbattle.client.TrackEffectEditScreen"));
                client.screen = editor;
                controller.getMethod("hideGuiLayer", RenderGuiLayerEvent.Pre.class).invoke(null, layer);
                check(client.screen == editor, "HUD cancellation must preserve the open editor");
                client.screen = null;
            }
            options.hideGui = false;
            JsonObject hiddenJson = new JsonObject(); hiddenJson.addProperty("hideHud", true);
            var hidden = new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND, 0, 200, 1)
                    .withSettings(TrackEffectSettings.readJson(hiddenJson));
            field(controller, "active").setBoolean(null, true);
            field(controller, "activeEffects").set(null, List.of(hidden));
            field(camera, "activeEffectState").set(null, ClientTrackEffects.sample(List.of(), 15));
            check(!(boolean) controller.getMethod("hidesGameHud").invoke(null),
                    "A visible nested camera scope must not inherit player HUD effects");
            check(!(boolean) controller.getMethod("hidesWorldParticles").invoke(null),
                    "A visible nested camera scope must not inherit player particle effects");
            field(camera, "activeEffectState").set(null, ClientTrackEffects.sample(List.of(hidden), 201));
            check(!(boolean) controller.getMethod("hidesGameHud").invoke(null),
                    "Seek past effect end immediately restores HUD");
            field(camera, "rendering").setBoolean(null, false);
            field(controller, "active").setBoolean(null, false);
            client.level = null;
            check(!(boolean) controller.getMethod("hidesGameHud").invoke(null),
                    "Leaving the world does not retain a stale HUD effect");
            Object cache = field(controller, "sceneVisibility").get(null);
            var invalidate = cache.getClass().getDeclaredMethod("invalidate"); invalidate.setAccessible(true);
            var read = cache.getClass().getDeclaredMethod("read", boolean.class, ClientTrackEffects.EffectState.class,
                    java.util.function.Supplier.class); read.setAccessible(true);
            invalidate.invoke(cache);
            read.invoke(cache, false, null, (java.util.function.Supplier<ClientTrackEffects.EffectState>)
                    () -> ClientTrackEffects.sample(List.of(hidden), 10));
            check((boolean) controller.getMethod("hidesGameHud").invoke(null), "Controller consumes cached HUD flags");
            Class.forName("ru.gora.musicbattle.client.ClientGameEvents").getMethod("onRenderFrame",
                    net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class).invoke(null,
                    new net.neoforged.neoforge.client.event.RenderFrameEvent.Pre(null));
            check(!(boolean) controller.getMethod("hidesGameHud").invoke(null), "Real frame event invalidates prior HUD sample");
        } finally {
            field(Minecraft.class, "instance").set(null, previousClient);
            field(camera, "activeEffectState").set(null, previousState);
            field(camera, "rendering").setBoolean(null, previousRendering);
            field(controller, "active").setBoolean(null, previousActive);
            field(controller, "activeEffects").set(null, previousEffects);
        }
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void frameVisibilityCache() throws Exception {
        Class<?> cacheType;
        try { cacheType = Class.forName("ru.gora.musicbattle.client.SceneVisibilityCache"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Visibility must cache sampled flags per frame", missing); }
        var constructor = cacheType.getDeclaredConstructor(); constructor.setAccessible(true);
        Object cache = constructor.newInstance();
        var read = cacheType.getDeclaredMethod("read", boolean.class, ClientTrackEffects.EffectState.class,
                java.util.function.Supplier.class); read.setAccessible(true);
        var invalidate = cacheType.getDeclaredMethod("invalidate"); invalidate.setAccessible(true);
        JsonObject settings = new JsonObject(); settings.addProperty("hideHud", true);
        settings.addProperty("hideBlockyMusicHud", true);
        var hidden = ClientTrackEffects.sample(List.of(new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND,0,20,1)
                .withSettings(TrackEffectSettings.readJson(settings))), 10);
        var shown = ClientTrackEffects.sample(List.of(), 10);
        int[] calls = {0};
        var source = (java.util.function.Supplier<ClientTrackEffects.EffectState>) () -> { calls[0]++; return hidden; };
        Object first = read.invoke(cache, false, null, source);
        for (int i = 0; i < 200; i++) check(read.invoke(cache, false, null, source) == first,
                "Repeated entity/HUD reads reuse precomputed flags");
        check(calls[0] == 1, "A frame samples the main effect stack only once");
        var hud = first.getClass().getDeclaredMethod("hud"); hud.setAccessible(true);
        var modHud = first.getClass().getDeclaredMethod("blockyMusicHud"); modHud.setAccessible(true);
        check((boolean) hud.invoke(first), "Main frame retains HUD suppression");
        check((boolean) modHud.invoke(first), "Main frame retains mod HUD suppression");
        Object scoped = read.invoke(cache, true, shown, source);
        check(!(boolean) hud.invoke(scoped), "Visible camera scope overrides hidden main scene");
        check(!(boolean) modHud.invoke(scoped), "Visible camera scope restores mod HUD independently");
        check(!(boolean) hud.invoke(read.invoke(cache, true, null, source)), "Null camera scope cannot inherit main state");
        check(read.invoke(cache, false, null, source) == first && calls[0] == 1, "Camera preview does not replace main cache");
        invalidate.invoke(cache);
        Object next = read.invoke(cache, false, null,
                (java.util.function.Supplier<ClientTrackEffects.EffectState>) () -> { calls[0]++; return shown; });
        check(calls[0] == 2 && !(boolean) hud.invoke(next), "Next frame immediately reflects end/seek/stop");
        check(!(boolean) modHud.invoke(next), "Next frame restores mod HUD after end/seek/stop");
    }
    private static void nestedCameraHud() throws Exception {
        Class<?> controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        java.lang.reflect.Method hidden;
        try { hidden = controller.getDeclaredMethod("hidesWorldPanelHud",boolean.class,ClientTrackEffects.EffectState.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("World HUD must use camera-scoped visibility",missing); }
        hidden.setAccessible(true);
        for (boolean hud : new boolean[]{false,true}) for (boolean modHud : new boolean[]{false,true}) {
            JsonObject settings = new JsonObject(); settings.addProperty("hideHud",hud);
            settings.addProperty("hideBlockyMusicHud",modHud);
            var state = ClientTrackEffects.sample(List.of(new TrackEffect(TrackEffect.Type.HIDE_PARTICLES_HAND,0,20,1)
                    .withSettings(TrackEffectSettings.readJson(settings))),10);
            check((boolean) hidden.invoke(null,true,state)==modHud,
                    "Camera battle panels must follow only Blocky Music HUD switch: vanilla=" + hud + ", mod=" + modHud);
        }
    }
}
