import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import ru.gora.musicbattle.network.BattleCameraSyncPayload;
import net.minecraft.core.BlockPos;
import ru.gora.musicbattle.network.OpenDjPanelPayload;
import ru.gora.musicbattle.network.ConfigureDjPanelPayload;

public final class ServerAudienceChecks {
    static int checks;
    public static void main(String[] args) throws Exception {
        cameraFallbackClearsBothClientModes();
        galleryPreviewIsNotBroadcastSelection();
        selectionChangesOnlyExplicitly();
        boothCoverageIsSeparateFromCameraAvailability();
        spectatorsPreferOnlyTheirAudibleBattle();
        System.out.println("Server audience checks PASS: " + checks);
    }
    @SuppressWarnings({"unchecked", "removal"})
    static void galleryPreviewIsNotBroadcastSelection() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance",Class.class);
        Class<?> panelClass = Class.forName("ru.gora.musicbattle.client.DjPanelScreen");
        Object panel = allocate.invoke(unsafe,panelClass);
        var cameras = new ArrayList<>(List.of(
                new OpenDjPanelPayload.CameraEntry(new BlockPos(1,2,3),true,1,2,3,0,0,70),
                new OpenDjPanelPayload.CameraEntry(new BlockPos(4,5,6),true,4,5,6,0,0,70)));
        field(panelClass,"cameras").set(panel,cameras);
        for(String name:List.of("tracks","speakers","filteredTrackIndices","djVisualEffects")) field(panelClass,name).set(panel,new ArrayList<>());
        for(String name:List.of("searchQuery","loadedTrackId")) field(panelClass,name).set(panel,"");
        field(panelClass,"panelPos").set(panel,BlockPos.ZERO);
        field(panelClass,"cameraIndex").set(panel,0);
        Class<?> galleryClass = Class.forName("ru.gora.musicbattle.client.DjCameraGalleryScreen");
        Object gallery = allocate.invoke(unsafe,galleryClass);
        field(galleryClass,"parent").set(gallery,panel);
        field(galleryClass,"cameras").set(gallery,cameras);
        Class<?> screen = Class.forName("net.minecraft.client.gui.screens.Screen");
        screen.getField("width").set(gallery,640); screen.getField("height").set(gallery,480);
        Method cardMethod = galleryClass.getDeclaredMethod("card",int.class); cardMethod.setAccessible(true);
        Object card = cardMethod.invoke(gallery,1);
        Method x = card.getClass().getDeclaredMethod("x"); x.setAccessible(true);
        Method y = card.getClass().getDeclaredMethod("y"); y.setAccessible(true);
        List<Object> sent = (List<Object>)Class.forName("net.neoforged.neoforge.network.PacketDistributor").getField("SENT").get(null);
        sent.clear();
        galleryClass.getMethod("mouseClicked",double.class,double.class,int.class)
                .invoke(gallery,((int)x.invoke(card))+5d,((int)y.invoke(card))+5d,0);
        check(field(galleryClass,"selectedIndex").getInt(gallery)==1,"card click changes only the inspected camera");
        check(sent.isEmpty() && field(panelClass,"cameraIndex").getInt(panel)==0,"standby card inspection sends no broadcast selection");
        Method select = galleryClass.getDeclaredMethod("selectCurrent"); select.setAccessible(true); select.invoke(gallery);
        check(sent.size()==1 && sent.getFirst() instanceof ConfigureDjPanelPayload p
                && p.action()==ConfigureDjPanelPayload.Action.SELECT_CAMERA && p.targetPos().equals(new BlockPos(4,5,6)),
                "explicit Select button sends exactly the inspected camera to the server");
    }
    static void cameraFallbackClearsBothClientModes() throws Exception {
        Class<?> c = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        Method accept = c.getMethod("acceptBattleCameraSync", BattleCameraSyncPayload.class);
        for (boolean active : new boolean[]{true,false}) {
            UUID id = UUID.randomUUID();
            field(c,"active").set(null,active);
            field(c,"battleId").set(null,id);
            field(c,"spectatorBattleId").set(null,id);
            field(c,"cameraEntityId").set(null,12);
            field(c,"spectatorCameraEntityId").set(null,12);
            field(c,"syncedCameraBattleId").set(null,null);
            for(String name:List.of("cameraYaw","spectatorCameraYaw","spectatorCameraPreviousYaw")) field(c,name).set(null,200f);
            for(String name:List.of("cameraPitch","spectatorCameraPitch","spectatorCameraPreviousPitch")) field(c,name).set(null,40f);
            accept.invoke(null,new BattleCameraSyncPayload(id,12,1,2,3,30,10,70,10));
            check(field(c,"syncedCameraAvailable").getBoolean(null),"selected camera reaches mode " + active);
            accept.invoke(null,new BattleCameraSyncPayload(id,12,1,2,3,91,-17,0,11));
            check(!field(c,"syncedCameraAvailable").getBoolean(null),"dynamic fallback clears old override in mode " + active);
            checkRenderedFallbackAngles(c,active);
            accept.invoke(null,new BattleCameraSyncPayload(id,12,1,2,3,30,10,70,10));
            check(!field(c,"syncedCameraAvailable").getBoolean(null),"stale camera cannot resurrect after fallback");
            accept.invoke(null,new BattleCameraSyncPayload(UUID.randomUUID(),12,1,2,3,30,10,70,12));
            check(!field(c,"syncedCameraAvailable").getBoolean(null),"unrelated battle cannot replace fallback");
            accept.invoke(null,new BattleCameraSyncPayload(id,12,1,2,3,30,10,80,12));
            check(field(c,"syncedCameraAvailable").getBoolean(null),"new explicit selection restores override");
        }
    }
    static void checkRenderedFallbackAngles(Class<?> controller,boolean active) throws Exception {
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
        Field instance = field(minecraft,"instance"); Object previous = instance.get(null);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeClass,"theUnsafe").get(null);
        Object client = unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,minecraft);
        try {
            instance.set(null,client);
            field(controller,"localTicks").set(null,0);
            field(controller,"spectatorLocalTicks").set(null,0);
            field(controller,"musicStartedAtNanos").set(null,0L);
            field(controller,"spectatorMusicStartedAtNanos").set(null,0L);
            field(controller,"spectatorCameraEnabled").set(null,true);
            field(controller,"standaloneDjPlayback").set(null,false);
            Class<?> angles = Class.forName("net.neoforged.neoforge.client.event.ViewportEvent$ComputeCameraAngles");
            Object event = angles.getConstructor(Class.forName("net.minecraft.client.Camera"),double.class,float.class,float.class,float.class)
                    .newInstance(null,0d,0f,0f,0f);
            controller.getMethod("applyCameraAngles",angles).invoke(null,event);
            check(Math.abs((float)angles.getMethod("getYaw").invoke(event)-91f)<.001f,
                    "actual rendered fallback yaw switches to arena direction in mode " + active);
            check(Math.abs((float)angles.getMethod("getPitch").invoke(event)+17f)<.001f,
                    "actual rendered fallback pitch switches to arena direction in mode " + active);
        } finally { instance.set(null,previous); }
    }
    static void selectionChangesOnlyExplicitly() throws Exception {
        Class<?> c = Class.forName("ru.gora.musicbattle.data.ExplicitCameraSelection");
        Object selection = c.getConstructor().newInstance();
        Method select = c.getMethod("select",Object.class,Object.class);
        Method latest = c.getMethod("latest",BiPredicate.class);
        Set<String> valid = new HashSet<>(List.of("A","B","C"));
        BiPredicate<String,String> usable = (owner,camera) -> valid.contains(camera);
        check(latest.invoke(selection,usable)==null,"no edit/link implicitly becomes a broadcast selection");
        select.invoke(selection,"booth1","A");
        select.invoke(selection,"booth2","B");
        check(latest.invoke(selection,usable).equals("B"),"last explicit booth selection wins");
        check(latest.invoke(selection,usable).equals("B"),"reading updated camera transforms never changes selection order");
        select.invoke(selection,"booth1","A");
        check(latest.invoke(selection,usable).equals("A"),"reselecting same camera explicitly takes priority");
        valid.remove("A");
        check(latest.invoke(selection,usable).equals("B"),"unloaded/deleted/out-of-area camera falls back to eligible selection");
        valid.clear();
        check(latest.invoke(selection,usable)==null,"no eligible selected cameras means arena fallback");
    }
    static void spectatorsPreferOnlyTheirAudibleBattle() throws Exception {
        Class<?> c = Class.forName("ru.gora.musicbattle.data.BattleAudienceFocus");
        Method choose = c.getMethod("choose",boolean.class,boolean.class,double.class,double.class);
        check((int)choose.invoke(null,false,true,9d,16d)==0,"spectator follows nearer battle participant");
        check((int)choose.invoke(null,false,true,36d,4d)==1,"spectator follows other participant when nearer");
        check((int)choose.invoke(null,false,false,1d,2d)==-1,"DJ-only music leaves ordinary free look");
        check((int)choose.invoke(null,true,true,1d,2d)==-1,"participating smiley retains battle-controlled orientation");
        check((int)choose.invoke(null,false,true,Double.POSITIVE_INFINITY,4d)==1,"invalid participant is never targeted");
        check((int)choose.invoke(null,false,true,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY)==-1,"missing actors release look goal");
    }
    static void boothCoverageIsSeparateFromCameraAvailability() throws Exception {
        Class<?> c = Class.forName("ru.gora.musicbattle.data.ExplicitCameraSelection");
        Method select = c.getMethod("select",Object.class,Object.class);
        Map<String,Integer> x = Map.of("nearBooth",10,"farBooth",1000,"speakerBooth",150,"localCamera",12,"remoteCamera",2000);
        Predicate<String> covered = pos -> Math.abs(x.get(pos)) <= 32 || Math.abs(x.get(pos)-150) <= 20;
        BiPredicate<String,String> available = (owner,camera) -> true;
        Object near = c.getConstructor().newInstance();
        select.invoke(near,"nearBooth","remoteCamera");
        check("remoteCamera".equals(coveredSelection(c,near,covered,available)),"covered booth may broadcast a valid linked camera outside the arena");
        Object far = c.getConstructor().newInstance();
        select.invoke(far,"farBooth","localCamera");
        check(coveredSelection(c,far,covered,available)==null,"remote booth cannot control battle through a camera inside the arena");
        Object speaker = c.getConstructor().newInstance();
        select.invoke(speaker,"speakerBooth","remoteCamera");
        check("remoteCamera".equals(coveredSelection(c,speaker,covered,available)),"speaker coverage qualifies the booth rather than the camera");
        check(coveredSelection(c,speaker,covered,(owner,camera)->false)==null,"covered booth still needs an available linked camera");
        select.invoke(near,"farBooth","localCamera");
        check("remoteCamera".equals(coveredSelection(c,near,covered,available)),"later far-booth selection cannot steal a covered booth's broadcast");
    }
    static Object coveredSelection(Class<?> c,Object selection,Predicate<String> coverage,BiPredicate<String,String> available) throws Exception {
        try {
            return c.getMethod("latest",Predicate.class,BiPredicate.class).invoke(selection,coverage,available);
        } catch (NoSuchMethodException oldCameraScopedRouting) {
            // Previous server adapter combined coverage with camera availability on the camera.
            return c.getMethod("latest",BiPredicate.class).invoke(selection,
                    (BiPredicate<String,String>)(owner,camera)->coverage.test(camera)&&available.test(owner,camera));
        }
    }
    static Field field(Class<?> c,String name)throws Exception { Field f=c.getDeclaredField(name);f.setAccessible(true);return f; }
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
