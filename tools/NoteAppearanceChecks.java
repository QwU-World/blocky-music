import java.lang.reflect.*;
import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Real data and binary packet regressions; no Minecraft launch required. */
public final class NoteAppearanceChecks {
    static int checks;
    static Class<?> appearance;
    public static void main(String[] args) throws Exception {
        try { appearance = Class.forName("ru.gora.musicbattle.data.NoteAppearance"); }
        catch (ClassNotFoundException e) { throw new AssertionError("Four lane colors and shape are not represented in shared battle data", e); }
        Constructor<?> ctor = appearance.getConstructor(int.class, List.class);
        var mutable = new ArrayList<>(List.of(0x123456, 0xABCDEF, 0x010203, 0x987654));
        Object a = ctor.newInstance(1, mutable);
        mutable.set(0, 0);
        check(color(a, 0) == 0xFF123456, "saved palette copies input and makes colors opaque");
        check(color(a, 3) == 0xFF987654, "fourth lane keeps its independent color");
        List<?> colors = (List<?>) appearance.getMethod("colors").invoke(a);
        try { colors.clear(); throw new AssertionError("palette can be mutated after battle start"); }
        catch (UnsupportedOperationException expected) { checks++; }
        Object invalid = ctor.newInstance(999, Arrays.asList(0x123456, null));
        check((int) appearance.getMethod("skin").invoke(invalid) == 0, "unknown shapes safely fall back to bars");
        check(((List<?>)appearance.getMethod("colors").invoke(invalid)).size()==4, "malformed palette still supplies every lane");
        for (int seed=0; seed<256; seed++) {
            Object palette = appearance.getMethod("pastel", Random.class).invoke(null,new Random(seed));
            check(palette.equals(appearance.getMethod("pastel", Random.class).invoke(null,new Random(seed))),"server random seed produces stable appearance");
            var unique = new HashSet<Integer>();
            for (int lane=0;lane<4;lane++) {
                int c=color(palette,lane); unique.add(c);
                int r=c>>>16&255,g=c>>>8&255,b=c&255;
                check(Math.min(r,Math.min(g,b))>=100 && Math.max(r,Math.max(g,b))>=210,"mob colors are readable pastels");
            }
            check(unique.size()==4,"mob lanes are visually distinct");
        }
        Class<?> speed;
        try {speed=Class.forName("ru.gora.musicbattle.data.NoteSpeed");}
        catch(ClassNotFoundException e){throw new AssertionError("Server and client have no shared high speed timing policy",e);}
        for(float[] row:new float[][]{{.1f,.5f,108,6},{1,1,54,6},{10,10,5,1},{12,12,5,1},{18,18,3,1},{20,20,3,1},{90,20,3,1},{Float.NaN,1,54,6},{Float.POSITIVE_INFINITY,1,54,6}}) {
            check((float)speed.getMethod("clamp",float.class).invoke(null,row[0])==row[1],"speed range rejects non-finite values and preserves 20x");
            check((int)speed.getMethod("leadTicks",float.class).invoke(null,row[0])==(int)row[2],"high speed actually shortens approach to three ticks");
            check((int)speed.getMethod("earlyHitWindowTicks",float.class).invoke(null,row[0])==(int)row[3],"hit window follows the shared approach time");
        }
        if(args.length==0 || !args[0].equals("data")) {
            for(String packet:List.of("SettingsPayload","BattleStartPayload","SpectatorBattlePayload","BattleAppearancePayload")) packetRoundtrip(packet,ctor);
        }
        if(args.length>0 && args[0].equals("sync")) clientSync(ctor);
        System.out.println("NOTE_APPEARANCE_CHECKS=PASS checks="+checks);
    }
    static int color(Object a,int lane)throws Exception{return (int)appearance.getMethod("color",int.class).invoke(a,lane);}
    static void clientSync(Constructor<?> appearanceCtor)throws Exception{
        Class<?> controller=Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        for(String className:List.of("ru.gora.musicbattle.client.ClientBattleController","ru.gora.musicbattle.server.BattleManager")){
            Method timing=Class.forName(className).getDeclaredMethod("earlyHitWindowTicks",float.class);timing.setAccessible(true);
            for(float[] row:new float[][]{{.5f,6},{1,6},{5,2},{10,1},{20,1},{Float.NaN,6}})
                check((int)timing.invoke(null,row[0])==(int)row[1],className+" uses matching high-speed hit timing");
        }
        Class<?> packet=Class.forName("ru.gora.musicbattle.network.BattleAppearancePayload");
        Method update;
        try{update=controller.getMethod("acceptBattleAppearance",packet);}catch(NoSuchMethodException e){throw new AssertionError("Client cannot apply note appearance without restarting its battle",e);}
        UUID id=new UUID(17,29);
        Object look=appearanceCtor.newInstance(2,List.of(0x123456,0xABCDEF,0x112233,0x445566));
        field(controller,"active").set(null,true);field(controller,"battleId").set(null,id);
        field(controller,"ownOwner").set(null,1);field(controller,"localTicks").set(null,177);field(controller,"rightScore").set(null,813);
        var constructor=packet.getConstructor(UUID.class,int.class,float.class,appearance);
        update.invoke(null,constructor.newInstance(id,1,20f,look));
        check((float)field(controller,"ownSpeed").get(null)==20f,"right-side participant updates own speed");
        check(((Object[])field(controller,"noteAppearances").get(null))[1].equals(look),"right-side own palette remains bound to note owner");
        check((int)field(controller,"localTicks").get(null)==177 && (int)field(controller,"rightScore").get(null)==813,"appearance edit preserves battle timeline and score");
        update.invoke(null,constructor.newInstance(id,0,12f,look));
        check((float)field(controller,"opponentSpeed").get(null)==12f,"remote participant edit changes opponent timing");
        update.invoke(null,constructor.newInstance(UUID.randomUUID(),1,.5f,look));
        check((float)field(controller,"ownSpeed").get(null)==20f,"stale battle update cannot change current session");
        field(controller,"active").set(null,false);field(controller,"spectatorBattleId").set(null,id);
        field(controller,"spectatorLocalTicks").set(null,288);field(controller,"spectatorLeftScore").set(null,991);
        update.invoke(null,constructor.newInstance(id,0,18f,look));
        check((float)field(controller,"spectatorLeftSpeed").get(null)==18f,"spectator sees live speed changes");
        check(((Object[])field(controller,"spectatorNoteAppearances").get(null))[0].equals(look),"spectator receives the participant lane palette");
        check((int)field(controller,"spectatorLocalTicks").get(null)==288 && (int)field(controller,"spectatorLeftScore").get(null)==991,"spectator update preserves clock and score");
    }
    static Field field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    @SuppressWarnings({"rawtypes","unchecked"})
    static void packetRoundtrip(String name,Constructor<?> appearanceCtor)throws Exception{
        Class<?> type;
        try{type=Class.forName("ru.gora.musicbattle.network."+name);}catch(ClassNotFoundException e){throw new AssertionError("Active battle appearance update packet is missing",e);}
        var fields=type.getRecordComponents();
        Class<?>[] types=new Class<?>[fields.length]; Object[] values=new Object[fields.length];
        int palettes=0;
        for(int i=0;i<fields.length;i++){
            var f=fields[i];types[i]=f.getType();
            if(types[i]==appearance){values[i]=appearanceCtor.newInstance(++palettes%3,List.of(0x123456+palettes,0xABCDEF,0x112233,0x445566));}
            else if(types[i]==UUID.class)values[i]=new UUID(7,19);
            else if(types[i]==String.class)values[i]="fixture";
            else if(types[i]==int.class)values[i]=1;
            else if(types[i]==float.class)values[i]=12f;
            else if(types[i]==boolean.class)values[i]=true;
            else if(types[i]==List.class)values[i]=f.getName().toLowerCase(Locale.ROOT).contains("label")?List.of("A","S","W","D"):List.of();
            else throw new AssertionError("Unhandled fixture field "+f);
        }
        check(palettes==(name.equals("SettingsPayload")||name.equals("BattleAppearancePayload")?1:2),name+" must carry independent participant palettes");
        Object payload=type.getDeclaredConstructor(types).newInstance(values);
        var codec=(StreamCodec)type.getField("STREAM_CODEC").get(null);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),null,ConnectionType.NEOFORGE);
        try{codec.encode(buffer,payload); Object decoded=codec.decode(buffer);check(payload.equals(decoded),name+" binary codec preserves shapes, four colors, and speed");check(buffer.readableBytes()==0,name+" consumes its entire packet");}finally{buffer.release();}
    }
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
}
