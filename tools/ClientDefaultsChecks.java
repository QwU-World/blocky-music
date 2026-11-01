import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.client.ClientBattleSettings;
import ru.gora.musicbattle.client.MusicBattleKeys;

/** Tests real first-run persistence and loading saved settings in an isolated game directory. */
public final class ClientDefaultsChecks {
    private static final List<String> failures=new ArrayList<>();
    private static int checks;
    // Approved settings copied from the test profile's General Settings menu, excluding calibration; the owner later
    // raised the default note speed to 5, moved the lane colours onto the Workshop palette and made the panel the
    // settings screen's dark background. The backdrop settings came after, defaulting to the battle's look before them.
    private static final String DEFAULTS="""
        {"noteSpeed":5.0,"musicVolume":0.35,"audioOffsetMs":0,"inputOffsetMs":0,
         "spectatorEffects":true,"panelColor":-15659244,"noteSkin":1,
         "laneColors":[-4288513,-11950,-4288513,-11950],"panelOpacity":0.0,
         "laneOpacity":0.35555556,"panelWidthScale":0.64308643,"panelHeightScale":1.5999999,
         "noteWidthScale":2.2,"noteHeightScale":0.7793827,
         "worldDimming":0.27450982,"stageBars":true,"stageBarOpacity":0.7254902}
        """;
    private static final String CUSTOM="""
        {"noteSpeed":7.5,"musicVolume":0.72,"audioOffsetMs":93,"inputOffsetMs":-47,
         "spectatorEffects":false,"hideUserTrackWarning":true,"panelColor":-15654349,"noteSkin":2,
         "laneColors":[-65536,-16711936,-16776961,-256],"panelOpacity":0.42,
         "laneOpacity":0.83,"panelWidthScale":1.21,"panelHeightScale":0.91,
         "noteWidthScale":1.34,"noteHeightScale":1.17,
         "worldDimming":0.6,"stageBars":false,"stageBarOpacity":0.4}
        """;
    public static void main(String[] args)throws Exception {
        boolean existing=args.length>0&&args[0].equals("existing");
        Path game=Files.createTempDirectory("blocky-client-defaults-");
        FMLPaths.loadAbsolutePaths(game);
        Path config=FMLPaths.CONFIGDIR.get().resolve("music_battle-client.json");
        if(existing){Files.createDirectories(config.getParent());Files.writeString(config,CUSTOM);}
        JsonObject want=JsonParser.parseString(existing?CUSTOM:DEFAULTS).getAsJsonObject();
        verifyLive(want);
        require(Files.isRegularFile(config),"first access persists a new config");
        JsonObject saved=JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        verifySaved(saved,want);
        if(existing)require(Files.readString(config).equals(CUSTOM),"loading existing settings rewrites their file");
        else require(!saved.get("hideUserTrackWarning").getAsBoolean(),"personal warning consent is not a General Settings default");
        verifyServerFallback();
        int[] arrows={263,264,265,262};
        for(int lane=0;lane<4;lane++)require(MusicBattleKeys.lanes()[lane].getDefaultKey().getValue()==arrows[lane],
                "new-player lane "+lane+" does not use its arrow key");
        // An already customized binding must not be reset as a side effect of defaults/settings loads.
        MusicBattleKeys.LANE_LEFT.setKey(InputConstants.Type.KEYSYM.getOrCreate(65));
        Field loaded=ClientBattleSettings.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(null,false);
        verifyLive(want);
        require(MusicBattleKeys.LANE_LEFT.getKey().getValue()==65,"loading mod settings overwrote an existing key binding");
        ClientBattleSettings.setPanelOpacity(existing?.65F:.25F);
        JsonObject edited=JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        JsonObject editedWant=want.deepCopy();editedWant.addProperty("panelOpacity",existing?.65F:.25F);
        verifySaved(edited,editedWant);
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("Client settings "+(existing?"preservation":"first-run defaults")+" PASS: "+checks);
    }
    private static void verifyLive(JsonObject want) {
        number(ClientBattleSettings.noteSpeed(),want,"noteSpeed");
        number(ClientBattleSettings.musicVolume(),want,"musicVolume");
        require(ClientBattleSettings.audioOffsetMs()==want.get("audioOffsetMs").getAsInt(),"audio calibration was copied/reset");
        require(ClientBattleSettings.inputOffsetMs()==want.get("inputOffsetMs").getAsInt(),"input calibration was copied/reset");
        require(ClientBattleSettings.spectatorEffects()==want.get("spectatorEffects").getAsBoolean(),"spectator effects default/override lost");
        require(ClientBattleSettings.panelColor()==want.get("panelColor").getAsInt(),"panel background color default/override lost");
        require(ClientBattleSettings.appearance().skin()==want.get("noteSkin").getAsInt(),"note skin default/override lost");
        for(int i=0;i<4;i++)require(ClientBattleSettings.appearance().color(i)==want.getAsJsonArray("laneColors").get(i).getAsInt(),"lane "+i+" color default/override lost");
        number(ClientBattleSettings.panelOpacity(),want,"panelOpacity");
        number(ClientBattleSettings.laneOpacity(),want,"laneOpacity");
        number(ClientBattleSettings.panelWidthScale(),want,"panelWidthScale");
        number(ClientBattleSettings.panelHeightScale(),want,"panelHeightScale");
        number(ClientBattleSettings.noteWidthScale(),want,"noteWidthScale");
        number(ClientBattleSettings.noteHeightScale(),want,"noteHeightScale");
        // The battle's backdrop: a new player gets the old argb(70, 0, 0, 0) and bars of alpha 185, exactly.
        number(ClientBattleSettings.worldDimming(),want,"worldDimming");
        require(ClientBattleSettings.stageBars()==want.get("stageBars").getAsBoolean(),"black bars default/override lost");
        number(ClientBattleSettings.stageBarOpacity(),want,"stageBarOpacity");
        require(ClientBattleSettings.worldDimmingAlpha()==Math.round(want.get("worldDimming").getAsFloat()*255F),"dimming alpha differs from the setting");
        require(ClientBattleSettings.stageBarAlpha()==(want.get("stageBars").getAsBoolean()?Math.round(want.get("stageBarOpacity").getAsFloat()*255F):0),
                "bar alpha differs from the setting");
    }
    private static void number(float actual,JsonObject want,String key){require(Float.compare(actual,want.get(key).getAsFloat())==0,key+" default/override lost: "+actual);}
    private static void verifyServerFallback()throws Exception {
        Class<?> manager=Class.forName("ru.gora.musicbattle.server.BattleManager");
        UUID newPlayer=UUID.randomUUID();
        require((int)serverValue(manager,"playerColor",newPlayer)==0xFF110F14,"server does not use the dark settings-background panel color before first settings packet");
        require(Float.compare((float)serverValue(manager,"playerSpeed",newPlayer),5.0F)==0,"server does not use the default speed 5 before the first settings packet");
        var appearance=(ru.gora.musicbattle.data.NoteAppearance)serverValue(manager,"playerAppearance",newPlayer);
        require(appearance.skin()==1&&appearance.colors().equals(List.of(0xFFBE8FFF,0xFFFFD152,0xFFBE8FFF,0xFFFFD152)),
                "server does not use the palette note appearance before first settings packet");
        Object style=serverValue(manager,"playerPanelStyle",newPlayer);
        String[] accessors={"panelOpacity","laneOpacity","widthScale","heightScale","noteWidthScale","noteHeightScale"};
        float[] values={0F,.35555556F,.64308643F,1.5999999F,2.2F,.7793827F};
        for(int i=0;i<values.length;i++) {
            var method=style.getClass().getDeclaredMethod(accessors[i]);method.setAccessible(true);
            require(Float.compare((float)method.invoke(style),values[i])==0,"server panel fallback differs: "+accessors[i]);
        }
        Field colors=manager.getDeclaredField("PANEL_COLORS");colors.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID,Integer> map=(Map<UUID,Integer>)colors.get(null);
        map.put(newPlayer,0xFF123456);
        require((int)serverValue(manager,"playerColor",newPlayer)==0xFF123456,"server ignores received panel color");
        map.remove(newPlayer);
    }
    private static Object serverValue(Class<?> manager,String name,UUID player)throws Exception {
        var method=manager.getDeclaredMethod(name,UUID.class);method.setAccessible(true);return method.invoke(null,player);
    }
    private static void verifySaved(JsonObject actual,JsonObject want){
        for(var entry:want.entrySet()) {
            String key=entry.getKey();JsonElement value=actual.get(key);
            boolean floating=key.endsWith("Scale")||key.endsWith("Opacity")||key.equals("noteSpeed")||key.equals("musicVolume")
                    ||key.equals("worldDimming");
            require(value!=null&&(floating?Float.compare(entry.getValue().getAsFloat(),value.getAsFloat())==0:entry.getValue().equals(value)),
                    "saved "+key+" differs from chosen settings");
        }
    }
    private static void require(boolean ok,String message){checks++;if(!ok)failures.add(message);}
}
