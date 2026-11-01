package ru.gora.musicbattle.client;

import java.util.*;
import ru.gora.musicbattle.data.*;

/** Outgoing visual stacks have their own clocks; starting the next song never rewinds them. */
public final class ClientDjFxTransition {
    private final List<Tail> tails=new ArrayList<>();
    public void capture(List<TrackEffect> effects,float tick,int bpm,float speed,long now) {
        capture(effects,tick,bpm,speed,0.0F,now);
    }
    /** {@code beatOrigin}: beat 1 of the outgoing song on its clock, so its release keeps that song's beat. */
    public void capture(List<TrackEffect> effects,float tick,int bpm,float speed,float beatOrigin,long now) {
        List<TrackEffect> release=DjFxRelease.at(effects,tick,speed);
        if(release.isEmpty())return;
        if(tails.size()>=8)tails.removeFirst();
        tails.add(new Tail(release,tick,bpm,speed,beatOrigin,now));
    }
    public ClientTrackEffects.EffectState attach(ClientTrackEffects.EffectState current,long now) {
        tails.removeIf(t->t.tickAt(now)>DjFxRelease.endTick(t.effects));
        if(tails.isEmpty())return current;
        return current.withPreceding(tails.stream().map(t->ClientTrackEffects.sample(t.effects,t.tickAt(now),t.bpm,t.speed,current.visibilityMultiplier(),t.beatOrigin)).toList());
    }
    public void clear(){tails.clear();}
    private record Tail(List<TrackEffect> effects,float tick,int bpm,float speed,float beatOrigin,long started) {
        float tickAt(long now){return tick+Math.max(0,now-started)/50_000_000f;}
    }
}
