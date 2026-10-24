package ru.gora.musicbattle.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.connection.ConnectionType;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/** Runs real serialization, storage and palette-refresh code without launching Minecraft. */
public final class DjPresetColorChecks {
    private static final int A = 0xFF1234AB;
    private static final int B = 0xFFBA4321;
    private static final int SPECTATOR = 0xFF19A267;
    private static final int ENTITY = 0xFFCE2589;
    private static final List<Integer> PALETTE = List.of(A, 0xFFFDA208, 0xFF080706);
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path controlledRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(controlledRoot);
        Path gameDir = Files.createTempDirectory(controlledRoot, "run-");
        FMLPaths.loadAbsolutePaths(gameDir);
        check(ClientDjPresetStorage.rootDirectory().startsWith(gameDir), "disk IO stays in isolated game directory");
        for (TrackEffect.Type type : TrackEffect.Type.values()) {
            DjVisualEffectConfig original = fixture(type);
            assertColors(original.effect(), type + " fixture");
            assertColors(DjVisualEffectConfig.fromJson(parse(original.toJson())).effect(), type + " JSON");
            assertColors(DjVisualEffectConfig.decodeList(DjVisualEffectConfig.encodeList(List.of(original)))
                    .getFirst().effect(), type + " NBT JSON list");
            assertColors(DjVisualEffects.sanitizeConfigs(List.of(original)).getFirst().effect(), type + " config validation");
            assertColors(original.materialize(37, 1200), type + " active FX materialization");
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), null, ConnectionType.NEOFORGE);
            try {
                original.write(buffer);
                assertColors(DjVisualEffectConfig.read(buffer).effect(), type + " network");
                check(!buffer.isReadable(), type + " network consumes the whole record");
            } finally {
                buffer.release();
            }
            boolean saved = ClientDjPresetStorage.saveEffectSet("Цвета " + type.id(), true, false, List.of(original));
            check(saved, type + " save succeeds");
            var loaded = ClientDjPresetStorage.loadEffectSet("Цвета " + type.id());
            check(loaded.isPresent(), type + " own saved JSON is readable");
            loaded.ifPresent(preset -> {
                check(preset.trackFxEnabled() && !preset.djFxEnabled(), type + " saved enable flags");
                var applied = preset.applyToCurrentModes(false, true);
                check(!applied.trackFxEnabled() && applied.djFxEnabled(),
                        type + " loading effects preserves the current FX mode buttons");
                assertColors(preset.configs().getFirst().effect(), type + " disk save/load");
            });
            verifyPaletteRefreshKeepsIndependentColors(type);
        }
        check(ClientDjPresetStorage.listEffectSets().size() == TrackEffect.Type.values().length,
                "preset browser can list every file just saved");
        verifyOverwriteUpdatesSelectedColors();
        verifyStrictValidationStillRejectsInvalidValues();
        verifyLegacySingleOutlineColor();
        for (int index = 1; index < args.length; index++) verifyExistingPresetReadOnly(Path.of(args[index]));
        System.out.println("DJ preset/color checks: " + checks + ", isolated files: " + gameDir);
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(System.err::println);
            throw new AssertionError(FAILURES.size() + " failed checks");
        }
        System.out.println("DJ preset/color regression PASS");
    }

    private static DjVisualEffectConfig fixture(TrackEffect.Type type) {
        JsonObject json = DjVisualEffectConfig.of(type).toJson();
        json.addProperty("colorA", A);
        json.addProperty("colorB", B);
        json.addProperty("intensity", 0.1F);
        json.addProperty("softness", 0.37F);
        json.addProperty("highlightSpectatorColor", SPECTATOR);
        json.addProperty("highlightEntityColor", ENTITY);
        JsonArray palette = new JsonArray();
        PALETTE.forEach(palette::add);
        json.add("objectOutlineColors", palette);
        json.addProperty("objectOutlineColorSpeed", 0.35F);
        return DjVisualEffectConfig.fromJson(json);
    }

    private static void assertColors(TrackEffect effect, String context) {
        check(effect.colorA() == A, context + " color A");
        check(effect.colorB() == B, context + " color B");
        check(effect.settings().highlightSpectatorColor() == SPECTATOR, context + " spectator color");
        check(effect.settings().highlightEntityColor() == ENTITY, context + " entity color");
        check(effect.settings().objectOutlineColors().equals(PALETTE), context + " outline palette");
        check(effect.intensity() == 0.1F && effect.softness() == 0.37F, context + " decimal float controls");
    }

    private static void verifyPaletteRefreshKeepsIndependentColors(TrackEffect.Type type) throws Exception {
        // Allocate only the UI state used by this method: no window, Minecraft singleton or GL.
        // The real button and real production refresh method reproduce returning from the picker.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object allocator = singleton.get(null);
        TrackEffectEditScreen screen = (TrackEffectEditScreen) unsafeClass
                .getMethod("allocateInstance", Class.class).invoke(allocator, TrackEffectEditScreen.class);
        set(screen, "type", type);
        set(screen, "seed", DjVisualEffectConfig.of(type).effect());
        set(screen, "objectOutlineColors", new ArrayList<>(List.of(0xFF556677, 0xFF667788)));
        set(screen, "objectOutlineColorButtons", new ArrayList<ExactColorButton>());
        ExactColorButton primary = new ExactColorButton(screen, 0, 0, 40, 20, Component.literal("A"), A, ignored -> {});
        ExactColorButton secondary = new ExactColorButton(screen, 0, 0, 40, 20, Component.literal("B"), B, ignored -> {});
        set(screen, "colorAButton", primary);
        set(screen, "colorBButton", secondary);
        Method refresh = TrackEffectEditScreen.class.getDeclaredMethod("refreshObjectOutlinePaletteButtons");
        refresh.setAccessible(true);
        refresh.invoke(screen);
        check(primary.color() == (type == TrackEffect.Type.OBJECT_OUTLINE ? 0xFF556677 : A),
                type + " picker/draft refresh must not replace color A from another effect's hidden palette");
        check(secondary.color() == B, type + " palette refresh preserves color B");
        refresh.invoke(screen);
        check(primary.color() == (type == TrackEffect.Type.OBJECT_OUTLINE ? 0xFF556677 : A),
                type + " repeated UI rebuild preserves color A");
    }

    private static void verifyStrictValidationStillRejectsInvalidValues() throws Exception {
        Method reader = ClientDjPresetStorage.class.getDeclaredMethod("readStrictEffect", JsonObject.class);
        reader.setAccessible(true);
        JsonObject valid = parse(fixture(TrackEffect.Type.VISUALIZER).toJson());
        try {
            reader.invoke(null, valid);
            check(true, "canonical decimal float JSON accepted");
        } catch (InvocationTargetException exception) {
            check(false, "canonical decimal float JSON accepted: " + exception.getCause());
        }
        for (String field : List.of("intensity", "softness", "tvScanlineOpacity", "objectOutlineColorSpeed")) {
            JsonObject invalid = valid.deepCopy();
            invalid.addProperty(field, 9000);
            expectInvalid(reader, invalid, field + " out-of-range number remains rejected");
            invalid = valid.deepCopy();
            invalid.addProperty(field, "0.1");
            expectInvalid(reader, invalid, field + " string masquerading as number remains rejected");
            invalid = valid.deepCopy();
            invalid.addProperty(field, Double.NaN);
            expectInvalid(reader, invalid, field + " NaN remains rejected");
        }
        for (String field : List.of("colorA", "colorB", "highlightSpectatorColor", "highlightEntityColor")) {
            JsonObject invalid = valid.deepCopy();
            invalid.addProperty(field, -123.5);
            expectInvalid(reader, invalid, field + " fractional color integer rejected");
            invalid = valid.deepCopy();
            invalid.addProperty(field, 4294967295L);
            expectInvalid(reader, invalid, field + " overflowing color integer rejected");
        }
    }

    private static void verifyOverwriteUpdatesSelectedColors() {
        JsonObject replacement = fixture(TrackEffect.Type.VISUALIZER).toJson();
        replacement.addProperty("colorA", 0xFF098765);
        replacement.addProperty("colorB", 0xFF654321);
        check(ClientDjPresetStorage.saveEffectSet("Цвета visualizer", false, true,
                List.of(DjVisualEffectConfig.fromJson(replacement))), "overwrite existing preset succeeds");
        var reloaded = ClientDjPresetStorage.loadEffectSet("Цвета visualizer");
        check(reloaded.isPresent(), "overwritten preset remains readable");
        reloaded.ifPresent(preset -> {
            check(preset.configs().getFirst().effect().colorA() == 0xFF098765,
                    "overwrite updates visualizer color A on disk");
            check(preset.configs().getFirst().effect().colorB() == 0xFF654321,
                    "overwrite updates visualizer color B on disk");
            check(!preset.trackFxEnabled() && preset.djFxEnabled(), "overwrite updates enable flags");
        });
        check(ClientDjPresetStorage.listEffectSets().size() == TrackEffect.Type.values().length,
                "overwrite does not create duplicate browser entry");
    }

    private static void verifyExistingPresetReadOnly(Path existingPreset) throws Exception {
        // Optional recovery proof: only parse the named source; never copy over or repair it.
        Method reader = ClientDjPresetStorage.class.getDeclaredMethod("readEffectSet", Path.class);
        reader.setAccessible(true);
        JsonObject json = JsonParser.parseString(Files.readString(existingPreset)).getAsJsonObject();
        try {
            var preset = (ClientDjPresetStorage.EffectSetPreset) reader.invoke(null, existingPreset);
            check(preset.configs().size() == json.getAsJsonArray("effects").size(),
                    "existing named preset is fully readable without changing its file");
        } catch (InvocationTargetException exception) {
            check(false, "existing named preset is readable: " + exception.getCause());
        }
    }

    private static void verifyLegacySingleOutlineColor() throws Exception {
        JsonObject legacy = parse(DjVisualEffectConfig.of(TrackEffect.Type.OBJECT_OUTLINE).toJson());
        legacy.addProperty("colorA", A);
        legacy.remove("objectOutlineColors");
        Method reader = ClientDjPresetStorage.class.getDeclaredMethod("readStrictEffect", JsonObject.class);
        reader.setAccessible(true);
        try {
            TrackEffect effect = ((DjVisualEffectConfig) reader.invoke(null, legacy)).effect();
            check(effect.colorA() == A && effect.objectOutlinePalette().equals(List.of(A)),
                    "legacy v1 outline retains original solid color without palette array");
        } catch (InvocationTargetException exception) {
            check(false, "legacy v1 outline remains readable: " + exception.getCause());
        }
    }

    private static void expectInvalid(Method reader, JsonObject json, String message) throws Exception {
        try {
            reader.invoke(null, json);
            check(false, message);
        } catch (InvocationTargetException exception) {
            check(exception.getCause() instanceof IOException, message);
        }
    }

    private static JsonObject parse(JsonObject json) {
        return JsonParser.parseString(json.toString()).getAsJsonObject();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) FAILURES.add("FAIL: " + message);
    }
}
