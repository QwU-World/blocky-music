import java.lang.reflect.Method;
import java.util.List;
import com.google.gson.JsonObject;
import ru.gora.musicbattle.client.ClientDjFxTransition;
import ru.gora.musicbattle.client.ClientTrackEffects;
import ru.gora.musicbattle.data.*;

/** Exercises the exact mask predicate used by renderOverlay, with real transition states. */
public final class DjTransitionOrderChecks {
    private static Method preserve;
    private static int checks;
    public static void main(String[] args)throws Exception {
        preserve=ClientTrackEffects.class.getDeclaredMethod("shouldPreserveHighlight",int.class,TrackEffect.class,int.class,TrackEffect.class);
        preserve.setAccessible(true);
        TrackEffect outgoing=effect(TrackEffect.Type.PLAYER_HIGHLIGHT,2);
        TrackEffect incoming=effect(TrackEffect.Type.FLASH,1);
        ClientDjFxTransition transition=new ClientDjFxTransition();
        transition.capture(List.of(outgoing),50,120,1,1_000_000L);
        var current=ClientTrackEffects.sample(List.of(incoming),20,120,1);
        var states=transition.attach(current,1_000_000L).states();
        check(states.size()==2 && states.getFirst().highlightEffect()!=null,"Fixture has no outgoing highlight tail");
        check(!rule(1,states.getLast().overlayEffects().getFirst(),0,states.getFirst().highlightEffect()),
                "New track FLASH rank -1 incorrectly preserves outgoing highlight rank -2");
        check(rule(0,effect(TrackEffect.Type.FLASH,8),1,effect(TrackEffect.Type.PLAYER_HIGHLIGHT,1)),
                "Outgoing FX must not overwrite newer highlight, even if its local rank was higher");
        // Ranks are handed out from the bottom of the mixer list, so a smaller rank is listed
        // lower, is drawn earlier, and therefore has to stay out of the highlighted pixels.
        check(rule(0,effect(TrackEffect.Type.FLASH,1),0,outgoing),"FX listed below the highlight lost its protection");
        check(!rule(0,effect(TrackEffect.Type.FLASH,3),0,outgoing),"FX listed above the highlight must paint over it");
        check(!rule(0,effect(TrackEffect.Type.FLASH,2),0,outgoing),"Same-row semantics changed");
        listOrderChecks();
        for(int row=0;row<48;row++) {
            TrackEffect chart=new TrackEffect(TrackEffect.Type.FLASH,row%6,0,100,1).withLayer(row/6);
            check(rule(0,chart,0,outgoing),"Same-stack chart row not below DJ highlight");
            check(!rule(1,chart,0,outgoing),"Newer chart stack incorrectly blocked by outgoing DJ highlight");
        }
        JsonObject settings=new JsonObject();settings.addProperty("overlayText",false);
        TrackEffect text=effect(TrackEffect.Type.TEXT,1);
        TrackEffect behindText=outgoing.withSettings(TrackEffectSettings.readJson(settings));
        check(!rule(0,text,1,behindText),"Highlight overlayText opt-out changed");
        check(rule(0,text,1,outgoing),"Default highlight/text protection lost");
        check(!rule(0,incoming,-1,null),"Absent highlight cannot create a mask");
        System.out.println("DJ transition order checks PASS: "+checks);
    }
    /** Pins the visible mixer order itself: the top row is applied last and covers the rows below. */
    private static void listOrderChecks()throws Exception {
        var correction=DjVisualEffectConfig.of(TrackEffect.Type.COLOR_CORRECTION);
        var highlight=DjVisualEffectConfig.of(TrackEffect.Type.PLAYER_HIGHLIGHT);
        var correctionOnTop=DjVisualEffects.materializeDj(List.of(correction,highlight),100);
        check(correctionOnTop.getFirst().type()==TrackEffect.Type.COLOR_CORRECTION
                && correctionOnTop.getLast().type()==TrackEffect.Type.PLAYER_HIGHLIGHT,"Materialization reordered the list");
        check(!rule(0,correctionOnTop.getFirst(),0,correctionOnTop.getLast()),
                "Colour correction listed above the highlight must colour-correct it");
        var correctionBelow=DjVisualEffects.materializeDj(List.of(highlight,correction),100);
        check(rule(0,correctionBelow.getLast(),0,correctionBelow.getFirst()),
                "Colour correction listed below the highlight must stay masked out of it");
        check(correctionOnTop.getFirst().renderOrder()<correctionOnTop.getLast().renderOrder(),
                "The top mixer row must own the rank that draws last");
    }
    private static TrackEffect effect(TrackEffect.Type type,int rank){return new TrackEffect(type,0,100,1).withDjOrder(rank);}
    private static boolean rule(int effectStack,TrackEffect effect,int highlightStack,TrackEffect highlight)throws Exception{return (boolean)preserve.invoke(null,effectStack,effect,highlightStack,highlight);}
    private static void check(boolean good,String label){checks++;if(!good)throw new AssertionError(label);}
}
