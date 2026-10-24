import java.util.*;
import ru.gora.musicbattle.data.*;
import ru.gora.musicbattle.client.ClientTrackEffects;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import ru.gora.musicbattle.network.EditDjPlaylistMixerPayload;

public final class DjMixerChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var configs = List.of(DjVisualEffectConfig.of(TrackEffect.Type.FLASH),
                DjVisualEffectConfig.of(TrackEffect.Type.PLAYER_HIGHLIGHT));
        var effects = DjVisualEffects.materializeDj(configs, 100);
        var row = ClientTrackEffects.class.getDeclaredMethod("globalRow", TrackEffect.class);
        row.setAccessible(true);
        check((int)row.invoke(null,effects.get(0)) < (int)row.invoke(null,effects.get(1)),
                "A DJ FLASH listed above the highlight must draw over its mask, not on the same row");
        for (int i=0;i<48;i++) {
            var chart = new TrackEffect(TrackEffect.Type.TEXT,i%6,0,100,1).withLayer(i/6);
            var composed = DjVisualEffects.compose(List.of(chart),configs,true,true,100);
            check(composed.getFirst().equals(chart), "Composition rewrote a chart row");
            check((int)row.invoke(null,composed.getLast()) < (int)row.invoke(null,chart), "DJ not above chart");
        }
        System.out.println("DJ ordering checks PASS");
        try { DjPlaylistEntry.class.getMethod("mixer"); }
        catch (NoSuchMethodException absent) {
            throw new AssertionError("Playlist entry cannot preserve normal mixer toggles, disabled presets and IDs", absent);
        }
        var chart = new TrackEffect(TrackEffect.Type.TEXT,5,19,80,1).withLayer(7);
        var initial = new DjPlaylistMixer(false,true,List.of(configs.getFirst().withEnabled(false),configs.getLast().withPresetId("own_copy")));
        var entry = new DjPlaylistEntry(UUID.randomUUID(),"song",12).withMixer(initial,List.of(chart));
        var sibling = new DjPlaylistEntry(UUID.randomUUID(),"song",12).withMixer(initial,List.of(chart));
        var changed = new DjPlaylistMixer(true,false,List.of(configs.getLast(),configs.getFirst()));
        var edited = entry.editMixer(initial,changed);
        check(edited != null && edited.mixer().equals(changed),"Mixer update not applied");
        check(sibling.mixer().equals(initial) && entry.mixer().equals(initial),"Entry snapshot aliased");
        check(edited.mixerChartEffects().equals(List.of(chart)),"Mixer edit altered chart copy");
        check(edited.editMixer(initial,initial)==null,"Stale editor overwrote newer mixer");
        check(edited.editMixer(changed,initial).equals(entry),"Cancel failed to restore original controls/configs");
        check(DjPlaylistEntry.fromTag(entry.toTag()).equals(entry),"NBT lost toggles/disabled IDs/chart snapshot");
        check(entry.withStartSourceTicks(44).mixer().equals(initial),"Cue change erased mixer");
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            entry.write(b); check(DjPlaylistEntry.read(b).equals(entry),"Entry wire lost snapshot");
            b.clear();
            var packet = new EditDjPlaylistMixerPayload(BlockPos.ZERO,entry.entryId(),initial,changed);
            EditDjPlaylistMixerPayload.STREAM_CODEC.encode(b,packet);
            check(EditDjPlaylistMixerPayload.STREAM_CODEC.decode(b).equals(packet),"CAS packet lost controls");
            b.clear(); effects.getLast().write(b);
            check(TrackEffect.read(b).renderOrder()==effects.getLast().renderOrder(),"Wire lost runtime rank");
            b.clear(); largeWireFixture(b,chart);
        } finally { b.release(); }
        check(DjFxRelease.at(effects,50,1).getLast().renderOrder()==effects.getLast().renderOrder(),"Release lost runtime rank");
        check(DjVisualEffectConfig.fromJson(new DjVisualEffectConfig("x",true,effects.getLast()).toJson()).effect().djOrder()==0,
                "Runtime rank leaked into saved template");
        var legacy = entry.toTag(); legacy.remove("Mixer"); legacy.remove("MixerChart");
        check(DjPlaylistEntry.fromTag(legacy).mixer().djFxEnabled(),"Legacy DJ entry failed migration");
        check(!DjPlaylistEntry.fromTag(legacy).mixerChartCaptured(),"Legacy empty chart must be captured once on server");
        var large = new DjPlaylistEntry(UUID.randomUUID(),"song",0,DjPlaylistEntry.FxMode.DJ,Collections.nCopies(9,chart));
        check(large.usesLegacyDjEditor() && !entry.usesLegacyDjEditor(),"Legacy/normal editor route wrong");
        check(DjPlaylistEntry.fromTag(large.toTag()).equals(large),"Large legacy DJ snapshot truncated in NBT");
        check(large.editMixer(large.mixer(),initial)==null,"Normal mixer may truncate oversized legacy entry");
        var legacyEffect = configs.getFirst().effect();
        var transition = new DjPlaylistEntry(UUID.randomUUID(),"song",0,DjPlaylistEntry.FxMode.DJ,Collections.nCopies(9,legacyEffect));
        var eight = transition.editEffect(8,DjPlaylistEntry.FxMode.DJ,9,legacyEffect,null);
        var otherEditor = eight.editMixer(eight.mixer(),new DjPlaylistMixer(true,false,eight.mixer().effects()));
        var rowEdited = otherEditor.editEffect(0,DjPlaylistEntry.FxMode.DJ,8,legacyEffect,legacyEffect.withSettings(legacyEffect.settings().withOverlayInterface(true)));
        check(rowEdited != null && rowEdited.mixer().trackFxEnabled() && !rowEdited.mixer().djFxEnabled(),
                "Stale legacy row editor erased newer normal mixer toggles after 9-to-8 transition");
        uiCancelChecks(entry);
        System.out.println("DJ mixer checks PASS: " + checks);
    }
    private static void largeWireFixture(RegistryFriendlyByteBuf b, TrackEffect chart) throws Exception {
        var text = DjVisualEffectConfig.of(TrackEffect.Type.TEXT).toJson();
        text.addProperty("text", "\u2605".repeat(160)); text.addProperty("font", "f".repeat(40));
        text.addProperty("animation", "a".repeat(24)); text.addProperty("exitAnimation", "e".repeat(24));
        text.addProperty("variant", "v".repeat(24)); text.addProperty("presetId", "p".repeat(32));
        var maximal = new DjPlaylistMixer(true,true,Collections.nCopies(8,DjVisualEffectConfig.fromJson(text)));
        var packet = new EditDjPlaylistMixerPayload(BlockPos.ZERO,UUID.randomUUID(),maximal,maximal);
        EditDjPlaylistMixerPayload.STREAM_CODEC.encode(b,packet);
        check(b.readableBytes()<32767,"Mixer CAS exceeds serverbound custom-payload limit");
        System.out.println("Maximal mixer CAS bytes: " + b.readableBytes());
        var allRows = new ArrayList<TrackEffect>();
        for(int i=0;i<512;i++)allRows.add(chart.withTimeline(i%6,i*10,8).withLayer(i%8));
        var entry = new DjPlaylistEntry(UUID.randomUUID(),"song",0).withMixer(maximal,allRows);
        var bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(entry.toTag(),new java.io.DataOutputStream(bytes));
        var roundtrip = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())));
        check(DjPlaylistEntry.fromTag(roundtrip).equals(entry),"Binary NBT lost 512 copied chart FX");
    }
    private static void uiCancelChecks(DjPlaylistEntry entry) throws Exception {
        var authoritative = new DjPlaylistEntry[]{entry};
        List<ru.gora.musicbattle.client.DjVisualFxScreen.State> calls = new ArrayList<>();
        var screen = new ru.gora.musicbattle.client.DjVisualFxScreen(null,entry.mixer().trackFxEnabled(),
                entry.mixer().djFxEnabled(),entry.mixer().effects(),120,1,state -> {
            var next = new DjPlaylistMixer(state.trackFxEnabled(),state.djFxEnabled(),state.effects());
            authoritative[0] = authoritative[0].editMixer(authoritative[0].mixer(),next);
            calls.add(state);
        });
        var enabled = screen.getClass().getDeclaredField("trackFxEnabled"); enabled.setAccessible(true); enabled.set(screen,true);
        var publish = screen.getClass().getDeclaredMethod("publishCurrentState",boolean.class); publish.setAccessible(true);
        publish.invoke(screen,false);
        check(authoritative[0].mixer().trackFxEnabled(),"Actual screen callback ignored chart toggle");
        screen.onClose();
        check(authoritative[0].equals(entry) && calls.size()==2,"Actual Cancel failed to restore snapshot after live preview");
    }
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
