package ru.gora.musicbattle.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;

public final class NoteAppearanceConfigChecks {
    public static void main(String[] args) throws Exception {
        Class<?> config;
        try { config = Class.forName("ru.gora.musicbattle.client.ClientNoteAppearanceConfig"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Saved panel color is not migrated to four note lanes", missing); }
        Method read = config.getDeclaredMethod("read", JsonObject.class, int.class);
        read.setAccessible(true);
        Object legacy = read.invoke(null, JsonParser.parseString("{\"panelColor\":-16711936}").getAsJsonObject(), 0xFF00FF00);
        require((int) call(legacy,"skin") == 0,"Legacy config changed skin");
        for (int lane = 0; lane < 4; lane++) require(color(legacy,lane) == 0xFF00FF00,"Legacy color lost in lane " + lane);
        Object customized = read.invoke(null, JsonParser.parseString("{\"noteSkin\":1,\"laneColors\":[16711680,65280,255,16776960]}").getAsJsonObject(), 0xFF777777);
        Method write = config.getDeclaredMethod("write", JsonObject.class, customized.getClass());
        write.setAccessible(true);
        JsonObject saved = new JsonObject();
        write.invoke(null,saved,customized);
        Object restored = read.invoke(null,JsonParser.parseString(saved.toString()).getAsJsonObject(),0xFF222222);
        require((int)call(restored,"skin") == 1,"Skin not restored");
        int[] want = {0xFFFF0000,0xFF00FF00,0xFF0000FF,0xFFFFFF00};
        for (int lane = 0; lane < 4; lane++) require(color(restored,lane) == want[lane],"Saved lane color lost");
        Object malformed = read.invoke(null,JsonParser.parseString("{\"noteSkin\":{},\"laneColors\":[255,{},null]}").getAsJsonObject(),0xFF123456);
        require((int)call(malformed,"skin") == 0 && color(malformed,0)==0xFF0000FF
                && color(malformed,1)==0xFF123456 && color(malformed,3)==0xFF123456,"Malformed field discarded valid values or lost fallback");
        System.out.println("PASS: old panel-color migration, opaque lane colors, JSON save/reload, malformed-field fallback");
    }
    private static Object call(Object value,String method) throws Exception { return value.getClass().getMethod(method).invoke(value); }
    private static int color(Object value,int lane) throws Exception { return (int)value.getClass().getMethod("color",int.class).invoke(value,lane); }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
