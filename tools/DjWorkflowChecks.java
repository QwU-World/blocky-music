import java.lang.reflect.*;
import java.util.*;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import ru.gora.musicbattle.data.*;

public final class DjWorkflowChecks {
    static int checks;
    public static void main(String[] args) throws Exception {
        Class<?> editorType=Class.forName("ru.gora.musicbattle.client.TrackEditorScreen");
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Object editor=((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(editorType);
        field(editorType,"previewPlaying").set(editor,true);
        field(editorType,"previewTick").set(editor,80);
        field(editorType,"previewStartedAtTick").set(editor,80);
        field(editorType,"previewAudioStartedSignalNanos").set(editor,System.nanoTime()-100_000_000L);
        Method previewClock=editorType.getDeclaredMethod("currentPreviewTick"); previewClock.setAccessible(true);
        check((float)previewClock.invoke(editor)>=82f,"editor follows audio start before the next game tick");
        field(editorType,"previewPlaying").set(editor,false);
        check((float)previewClock.invoke(editor)==80f,"stopped editor playhead does not drift with old audio signal");
        field(editorType,"previewPlaying").set(editor,true);
        field(editorType,"previewAudioStartedSignalNanos").set(editor,0L);
        check((float)previewClock.invoke(editor)==80f,"editor holds seek point until audio actually starts");
        Class<?> controller=Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        Class<?> noteType=Class.forName("ru.gora.musicbattle.client.ClientBattleController$ClientNote");
        Constructor<?> nc=noteType.getDeclaredConstructor(int.class,int.class,int.class,int.class,int.class);nc.setAccessible(true);
        Object waiting=nc.newInstance(0,0,1,100,0);field(noteType,"pendingHit").set(waiting,true);
        List panelNotes=(List)field(controller,"spectatorNotes").get(null);panelNotes.add(waiting);
        field(controller,"spectatorLocalTicks").set(null,99);
        field(controller,"spectatorMusicClockStartTick").set(null,98);
        field(controller,"spectatorMusicStartedAtNanos").set(null,1_000_000_000L);
        field(controller,"spectatorMusicPausedAtNanos").set(null,1_100_000_000L);
        Method panelFrame;
        try {panelFrame=controller.getDeclaredMethod("prepareSpectatorPanelFrame",float.class);}
        catch(NoSuchMethodException absent){throw new AssertionError("panel hits have no render-frame audio synchronization",absent);}
        panelFrame.setAccessible(true);
        check((float)panelFrame.invoke(null,0f)==100f,"panel uses exact listener audio time, not last client tick");
        check((boolean)field(noteType,"resolved").get(waiting),"queued hit is displayed in the crossing frame before next game tick");
        int[] serial=(int[])field(controller,"spectatorAnimationTriggerSerial").get(null);
        int trigger=serial[0];panelFrame.invoke(null,0f);
        check(serial[0]==trigger,"extra render frames never repeat a received hit");
        panelNotes.clear();
        Class<?> djPanelType=Class.forName("ru.gora.musicbattle.client.DjPanelScreen");
        Method canPreload=djPanelType.getDeclaredMethod("canPreloadAudience",boolean.class,boolean.class,String.class,String.class);
        canPreload.setAccessible(true);
        check((boolean)canPreload.invoke(null,true,true,"next_track",""),
                "idle DJ panel can preload a selected track before playback starts");
        check(!(boolean)canPreload.invoke(null,true,true,"active_track","active_track"),
                "DJ panel does not preload the already loaded track");
        Class<?> calibrationType=Class.forName("ru.gora.musicbattle.client.MusicBattleCalibrationScreen");
        Object calibration=((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(calibrationType);
        field(net.minecraft.client.gui.screens.Screen.class,"height").set(calibration,270);
        field(calibrationType,"controlWidth").set(calibration,250);
        field(calibrationType,"statsY").set(calibration,270);
        Class<?> controlType=Class.forName("ru.gora.musicbattle.client.MusicBattleCalibrationScreen$Control");
        Constructor<?> cc=controlType.getDeclaredConstructors()[0];cc.setAccessible(true);
        var topButton=net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.empty(),b->{}).bounds(12,52,250,20).build();
        var lowerButton=net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.empty(),b->{}).bounds(12,150,250,20).build();
        field(calibrationType,"controls").set(calibration,new ArrayList<>(List.of(cc.newInstance(topButton,52),cc.newInstance(lowerButton,150))));
        ((net.minecraft.client.gui.screens.Screen)calibration).setFocused(topButton);
        calibrationType.getMethod("mouseScrolled",double.class,double.class,double.class,double.class).invoke(calibration,20d,100d,0d,-10d);
        check((int)field(calibrationType,"controlScroll").get(calibration)==66,"calibration help scroll clamps to content end");
        check(!topButton.visible && lowerButton.visible && lowerButton.getY()==84,"scrolled controls move hitboxes and hide controls outside viewport");
        check(((net.minecraft.client.gui.screens.Screen)calibration).getFocused()==null,"hidden calibration control cannot keep keyboard focus");
        try {
            Method phase = SmileyDancePattern.class.getMethod("notePhase", double.class, double.class);
            for (double[] row : new double[][]{{-1,120,-1},{0,120,0},{5,120,0.5},{10,120,-1},{25,120,-1},{2.5,240,0.5},{Double.NaN,120,-1}}) {
                check(Math.abs((double)phase.invoke(null,row[0],row[1])-row[2]) < 0.000001, "note hop has one cycle, then rests");
            }
        } catch (NoSuchMethodException missing) { throw new AssertionError("Smiley has no finite, note-triggered hop yet", missing); }
        JsonObject settings = new JsonObject(); settings.addProperty("fadeOutEnabled",true); settings.addProperty("fadeOutTicks",40);
        TrackEffect fx = new TrackEffect(TrackEffect.Type.STARS,17,200,1).withSettings(TrackEffectSettings.readJson(settings));
        Class<?> lifetime = Class.forName("ru.gora.musicbattle.data.DjFxRelease");
        @SuppressWarnings("unchecked") List<TrackEffect> released = (List<TrackEffect>)lifetime.getMethod("at",List.class,float.class,float.class).invoke(null,List.of(fx),100f,1f);
        check(released.size()==1 && released.getFirst().tick()==17 && released.getFirst().endTick()==140, "release preserves animation origin and configured fade");
        check(fx.endTick()==217,"release does not mutate source chart");
        List<?> expired=(List<?>)lifetime.getMethod("at",List.class,float.class,float.class).invoke(null,List.of(fx),300f,1f);
        check(expired.isEmpty(),"already ended effects cannot resurrect at release");
        DjPlaylistEntry entry=new DjPlaylistEntry(UUID.randomUUID(),"song",60);
        Class<?> mode=Class.forName("ru.gora.musicbattle.data.DjPlaylistEntry$FxMode");
        Object track=Enum.valueOf(mode.asSubclass(Enum.class),"TRACK");
        entry=(DjPlaylistEntry)DjPlaylistEntry.class.getMethod("withEffects",mode,List.class).invoke(entry,track,List.of(fx));
        DjPlaylistEntry cue=entry.withStartSourceTicks(90);
        check(DjPlaylistEntry.class.getMethod("effects").invoke(cue).equals(List.of(fx)),"cue edit retains per-entry effects");
        RegistryFriendlyByteBuf buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try { cue.write(buf); check(cue.equals(DjPlaylistEntry.read(buf)),"playlist network roundtrip preserves FX timeline/settings"); } finally {buf.release();}
        Object nbt=DjPlaylistEntry.class.getMethod("toTag").invoke(cue);
        check(cue.equals(DjPlaylistEntry.class.getMethod("fromTag",net.minecraft.nbt.CompoundTag.class).invoke(null,nbt)),"playlist save/load preserves FX");
        DjPlaylistEntry large=(DjPlaylistEntry)DjPlaylistEntry.class.getMethod("withEffects",mode,List.class).invoke(entry,track,Collections.nCopies(512,fx));
        var largeTag=(net.minecraft.nbt.CompoundTag)DjPlaylistEntry.class.getMethod("toTag").invoke(large);
        var saved=new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(largeTag,new java.io.DataOutputStream(saved));
        check(saved.size()>65535,"large FX chart is actually serialized by NBT beyond a single UTF string limit");
        var reloaded = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(saved.toByteArray())));
        check(large.equals(DjPlaylistEntry.fromTag(reloaded)),"large FX chart survives actual binary NBT save and reload");
        var editPacket = new ru.gora.musicbattle.network.EditDjPlaylistEffectPayload(net.minecraft.core.BlockPos.ZERO,
                entry.entryId(),0,fx,DjPlaylistEntry.FxMode.TRACK,1,fx);
        RegistryFriendlyByteBuf editBuf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try {
            var codec=ru.gora.musicbattle.network.EditDjPlaylistEffectPayload.STREAM_CODEC;
            codec.encode(editBuf,editPacket);
            check(editBuf.readableBytes()<32767,"single conditional FX edit stays within serverbound payload budget");
            check(editPacket.equals(codec.decode(editBuf)),"conditional FX edit network roundtrip preserves conflict guard");
        } finally {editBuf.release();}
        Method gesture=Class.forName("ru.gora.musicbattle.item.MicrophoneItem").getMethod("startsSolo",boolean.class,boolean.class);
        check((boolean)gesture.invoke(null,true,false),"sneak air/block starts solo");
        check(!(boolean)gesture.invoke(null,false,false),"ordinary use must not start solo");
        check(!(boolean)gesture.invoke(null,true,true),"sneak entity interaction remains a challenge");
        Class<?> transition=Class.forName("ru.gora.musicbattle.client.ClientDjFxTransition");
        Object tail=transition.getConstructor().newInstance();
        transition.getMethod("capture",List.class,float.class,int.class,float.class,long.class)
                .invoke(tail,List.of(fx),100f,120,1f,1000000000L);
        var fresh=ru.gora.musicbattle.client.ClientTrackEffects.sample(List.of(),20f,180,1f);
        var combined=(ru.gora.musicbattle.client.ClientTrackEffects.EffectState)transition.getMethod("attach",fresh.getClass(),long.class)
                .invoke(tail,fresh,1500000000L);
        List<?> prior=(List<?>)combined.getClass().getMethod("preceding").invoke(combined);
        check(prior.size()==1,"auto-next retains outgoing FX stack");
        var old=(ru.gora.musicbattle.client.ClientTrackEffects.EffectState)prior.getFirst();
        check(old.trackTick()==110f && old.bpm()==120 && combined.trackTick()==20f,"outgoing animation and beat clock advance independently of next track");
        Method edit=DjPlaylistEntry.class.getMethod("editEffect",int.class,mode,int.class,TrackEffect.class,TrackEffect.class);
        check(edit.invoke(entry,0,track,2,fx,null)==null,"stale row count cannot delete another DJ's effect");
        check(edit.invoke(entry,0,track,1,fx.withTimeline(0,0,10),null)==null,"changed row cannot be overwritten by stale inspector");
        check(edit.invoke(entry,0,track,1,fx,null)!=null,"matching edit removes intended row");
        var offer = new ru.gora.musicbattle.network.DjPreloadOfferPayload(net.minecraft.core.BlockPos.ZERO,
                "night_track","night.ogg","a".repeat(64));
        RegistryFriendlyByteBuf offerBuf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try {
            var codec=ru.gora.musicbattle.network.DjPreloadOfferPayload.STREAM_CODEC;
            codec.encode(offerBuf,offer);
            check(offer.equals(codec.decode(offerBuf)),"preload offer roundtrip keeps panel/track/file/hash identity");
        } finally {offerBuf.release();}
        for (boolean have : new boolean[]{true,false}) {
            var answer=new ru.gora.musicbattle.network.DjPreloadAnswerPayload(new net.minecraft.core.BlockPos(4,5,6),
                    "night_track","night.ogg","b".repeat(64),have);
            RegistryFriendlyByteBuf answerBuf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
            try {
                var codec=ru.gora.musicbattle.network.DjPreloadAnswerPayload.STREAM_CODEC;
                codec.encode(answerBuf,answer);
                check(answer.equals(codec.decode(answerBuf)),"preload answer roundtrip preserves the cached-asset verdict");
            } finally {answerBuf.release();}
        }
        var repeatCommand=new ru.gora.musicbattle.network.ConfigureDjPlaylistPayload(net.minecraft.core.BlockPos.ZERO,
                ru.gora.musicbattle.network.ConfigureDjPlaylistPayload.Action.SET_REPEAT,null,"",0,-1,true,2);
        RegistryFriendlyByteBuf repeatBuf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try {
            var codec=ru.gora.musicbattle.network.ConfigureDjPlaylistPayload.STREAM_CODEC;
            codec.encode(repeatBuf,repeatCommand);
            check(repeatCommand.equals(codec.decode(repeatBuf)),"repeat command roundtrip keeps its selected mode");
        } finally {repeatBuf.release();}
        RegistryFriendlyByteBuf hostile=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try {
            hostile.writeBlockPos(net.minecraft.core.BlockPos.ZERO);
            hostile.writeVarInt(ru.gora.musicbattle.network.ConfigureDjPlaylistPayload.Action.SET_REPEAT.ordinal());
            hostile.writeBoolean(false);
            hostile.writeUtf("",DjPlaylistEntry.MAX_TRACK_ID_LENGTH);
            hostile.writeVarInt(0);
            hostile.writeVarInt(-1);
            hostile.writeBoolean(true);
            hostile.writeVarInt(7);
            boolean rejected=false;
            try {ru.gora.musicbattle.network.ConfigureDjPlaylistPayload.STREAM_CODEC.decode(hostile);}
            catch (RuntimeException expected) {rejected=true;}
            check(rejected,"an out-of-range repeat mode is refused instead of reaching the panel");
        } finally {hostile.release();}
        Class<?> repeatType=Class.forName("ru.gora.musicbattle.block.entity.DjPanelBlockEntity$PlaylistRepeat");
        Method repeatByName=repeatType.getDeclaredMethod("byName",String.class); repeatByName.setAccessible(true);
        Method repeatByOrdinal=repeatType.getMethod("byOrdinal",int.class);
        Object repeatOff=Enum.valueOf(repeatType.asSubclass(Enum.class),"OFF");
        check(repeatByName.invoke(null,new net.minecraft.nbt.CompoundTag().getString("PlaylistRepeat"))==repeatOff,
                "a panel saved before repeat existed loads as OFF");
        check(repeatByName.invoke(null,"SHUFFLE")==repeatOff,"an unknown saved repeat mode falls back to OFF");
        for (Object value : repeatType.getEnumConstants()) {
            check(repeatByName.invoke(null,((Enum<?>)value).name())==value,"repeat mode survives its NBT name");
            check(repeatByOrdinal.invoke(null,((Enum<?>)value).ordinal())==value,"repeat mode survives its network ordinal");
        }
        check(repeatByOrdinal.invoke(null,-1)==repeatOff && repeatByOrdinal.invoke(null,99)==repeatOff,
                "an out-of-range repeat ordinal cannot select a mode");
        libraryWire();
        libraryFollowsThePanel();
        System.out.println("DJ workflow checks passed: "+checks);
    }
    /** An idle booth as the panel payload describes it: these maps on the server, this list on the booth. */
    static ru.gora.musicbattle.network.OpenDjPanelPayload boothState(List<TrackSummary> tracks,List<String> library) {
        return boothState(tracks,library,List.of());
    }
    /** The same idle booth with a queue; its revision stays put, so only the list can change between two of them. */
    static ru.gora.musicbattle.network.OpenDjPanelPayload boothState(List<TrackSummary> tracks,List<String> library,
                                                                     List<DjPlaylistEntry> queue) {
        return new ru.gora.musicbattle.network.OpenDjPanelPayload(net.minecraft.core.BlockPos.ZERO,
                tracks,library,List.of(),-1,false,List.of(),-1,
                false,"","",1f,0L,false,0,0,true,false,.3f,.65f,.45f,false,.25f,280f,.35f,false,12000f,
                true,false,List.of(),queue,null,true,0,7L,List.of(),120,null,1f,0,List.of(),List.of(),false,0);
    }
    /**
     * The booth's own list on the wire: the panel payload carries it in its order, the add and remove commands carry
     * their map, and a list longer than a booth can hold is refused rather than believed.
     */
    static void libraryWire() {
        var panel=boothState(List.of(),List.of("first_map","ws_12_easy","second_map"));
        RegistryFriendlyByteBuf panelBuf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try {
            var codec=ru.gora.musicbattle.network.OpenDjPanelPayload.STREAM_CODEC;
            codec.encode(panelBuf,panel);
            check(codec.decode(panelBuf).library().equals(panel.library()),
                    "the panel payload brings the booth's list in its order");
        } finally {panelBuf.release();}
        RegistryFriendlyByteBuf hostile=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try {
            hostile.writeBlockPos(net.minecraft.core.BlockPos.ZERO);
            hostile.writeVarInt(0);
            hostile.writeVarInt(ru.gora.musicbattle.block.entity.DjPanelBlockEntity.MAX_LIBRARY_TRACKS+1);
            boolean refused=false;
            try {ru.gora.musicbattle.network.OpenDjPanelPayload.STREAM_CODEC.decode(hostile);}
            catch (RuntimeException expected) {refused=true;}
            check(refused,"a list longer than a booth can hold is refused");
        } finally {hostile.release();}
        for (var action : List.of(ru.gora.musicbattle.network.ConfigureDjPanelPayload.Action.LIBRARY_ADD,
                ru.gora.musicbattle.network.ConfigureDjPanelPayload.Action.LIBRARY_REMOVE)) {
            var command=new ru.gora.musicbattle.network.ConfigureDjPanelPayload(net.minecraft.core.BlockPos.ZERO,action,
                    net.minecraft.core.BlockPos.ZERO,14f,1f,0f,0f,0f,"ws_12_easy",false,0,1f,0L,true,false,.3f,.65f,.45f,
                    false,.25f,280f,.35f,false,12000f,true,false,List.of());
            RegistryFriendlyByteBuf commandBuf=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
            try {
                var codec=ru.gora.musicbattle.network.ConfigureDjPanelPayload.STREAM_CODEC;
                codec.encode(commandBuf,command);
                check(command.equals(codec.decode(commandBuf)),"a list command keeps its action and its map: "+action);
            } finally {commandBuf.release();}
        }
    }
    /**
     * The playlist keeps its own copy of the booth's list and takes Add's map from it, while its chooser lists the
     * panel's current one. The list changes without the playlist's revision moving - a "+" pick that lands while the
     * playlist is open, a map the server gains or loses - and the copy used to stay as it was until the queue itself
     * changed, so the chooser could offer a map that Add then could not find.
     */
    static void libraryFollowsThePanel() throws Exception {
        TrackSummary a=TrackSummary.legacy("map_a","Alpha","",120,400,"","",false);
        TrackSummary b=TrackSummary.legacy("map_b","Beta","",128,500,"","",false);
        TrackSummary c=TrackSummary.legacy("map_c","Gamma","",140,600,"","",false);
        Class<?> panelType=Class.forName("ru.gora.musicbattle.client.DjPanelScreen");
        Object panel=panelType.getConstructor(ru.gora.musicbattle.network.OpenDjPanelPayload.class)
                .newInstance(boothState(List.of(a,b,c),List.of("map_b","map_a")));
        Method apply=panelType.getMethod("applyServerState",ru.gora.musicbattle.network.OpenDjPanelPayload.class);
        Method revision=panelType.getDeclaredMethod("libraryRevision"); revision.setAccessible(true);
        Method resolved=panelType.getDeclaredMethod("libraryTracks"); resolved.setAccessible(true);
        check(resolved.invoke(panel).equals(List.of(b,a)),"the panel resolves the booth's list in the list's order");
        int opened=(int)revision.invoke(panel);
        apply.invoke(panel,boothState(List.of(a,b,c),List.of("map_b","map_a")));
        check((int)revision.invoke(panel)==opened,"a refresh that brings the same list leaves its revision alone");
        Class<?> playlistType=Class.forName("ru.gora.musicbattle.client.DjPlaylistScreen");
        Constructor<?> open=playlistType.getDeclaredConstructor(panelType); open.setAccessible(true);
        Object playlist=open.newInstance(panel);
        // Far from its next once-a-second refresh, so tick() stays off the network in this harness.
        field(playlistType,"refreshCooldown").set(playlist,1_000);
        Method tick=playlistType.getMethod("tick"); tick.setAccessible(true);
        check(field(playlistType,"library").get(playlist).equals(List.of(b,a)),"the playlist opens on the booth's list");
        apply.invoke(panel,boothState(List.of(a,b,c),List.of("map_b","map_a","map_c")));
        check((int)revision.invoke(panel)!=opened,"a map joining the list moves its revision");
        tick.invoke(playlist);
        check(field(playlistType,"library").get(playlist).equals(List.of(b,a,c)),
                "an open playlist takes up a map that joined the list, with no change to the queue");
        apply.invoke(panel,boothState(List.of(a,c),List.of("map_b","map_a","map_c")));
        tick.invoke(playlist);
        check(field(playlistType,"library").get(playlist).equals(List.of(a,c)),
                "a map the server no longer has leaves what the playlist's Add can choose");

        // A DJ typing a new start for the selected row when a map lands: the queue did not change, so neither does
        // the cue on screen.
        DjPlaylistEntry row=new DjPlaylistEntry(UUID.randomUUID(),"map_a",40);
        Object queued=panelType.getConstructor(ru.gora.musicbattle.network.OpenDjPanelPayload.class)
                .newInstance(boothState(List.of(a,b,c),List.of("map_a"),List.of(row)));
        Object editing=open.newInstance(queued);
        field(playlistType,"refreshCooldown").set(editing,1_000);
        check((int)field(playlistType,"cueSourceTicks").get(editing)==40,"the playlist opens on its first row's cue");
        field(playlistType,"cueSourceTicks").set(editing,333);
        apply.invoke(queued,boothState(List.of(a,b,c),List.of("map_a","map_b"),List.of(row)));
        tick.invoke(editing);
        check(field(playlistType,"library").get(editing).equals(List.of(a,b))
                        && (int)field(playlistType,"cueSourceTicks").get(editing)==333,
                "a map joining the list leaves the cue being typed alone");
    }
    static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
}
