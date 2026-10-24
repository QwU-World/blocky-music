import java.lang.reflect.*;
import java.util.function.Predicate;
import ru.gora.musicbattle.data.BattlePlacementMath.Bounds;

/** Exercises the production policy; world collision/support are the only boundary doubles. */
public final class CrowdSafetyChecks {
    static int checks;
    static Class<?> policy;
    public static void main(String[] args) throws Exception {
        try { policy = Class.forName("ru.gora.musicbattle.data.CrowdSafetyMath"); }
        catch (ClassNotFoundException absent) { throw new AssertionError("Crowd displacement has no bounded motion/fall lifecycle policy", absent); }
        Method velocity = policy.getMethod("velocity", double.class,double.class,double.class);
        Object v = velocity.invoke(null, 3d, 4d, -0.6d);
        close(number(v,"x"), .108, "horizontal X is capped rather than accumulated");
        close(number(v,"z"), .144, "horizontal Z shares the same cap");
        close(number(v,"y"), -.6, "falling vertical velocity is never replaced by upward lift");
        close(number(velocity.invoke(null, 1000d,0d,.42d),"y"),.42,"existing upward velocity is preserved");
        check(velocity.invoke(null,Double.NaN,0d,0d)==null,"invalid direction cannot enter entity physics");
        check(velocity.invoke(null,0d,0d,0d)==null,"zero direction cannot normalize to NaN");
        Method canStart=policy.getMethod("canDisplace",boolean.class,float.class);
        check((boolean)canStart.invoke(null,true,0f),"grounded mob may be safely moved");
        check(!(boolean)canStart.invoke(null,false,0f),"airborne mob is not displaced");
        check(!(boolean)canStart.invoke(null,true,12f),"pre-existing fall distance is never covered");
        boolean dancingAllowed;
        try { dancingAllowed=(boolean)policy.getMethod("canDisplace",boolean.class,float.class,boolean.class).invoke(null,false,0f,true); }
        catch(NoSuchMethodException groundedOnly) { dancingAllowed=(boolean)canStart.invoke(null,false,0f); }
        check(dancingAllowed,"floor-supported music hop remains horizontally repulsible");
        Method danceStart = policy.getMethod("canDisplace",boolean.class,float.class,boolean.class);
        check(!(boolean)danceStart.invoke(null,false,0f,false),"ordinary airborne mobs remain untouched by repulsion");
        check(!(boolean)danceStart.invoke(null,false,4f,true),"dance exception cannot cover an already accumulated fall");
        Method path=policy.getMethod("safePath",Bounds.class,double.class,double.class,Predicate.class,Predicate.class);
        Bounds body=new Bounds(-.3,0,-.3,.3,1.8,.3);
        Predicate<Bounds> clear=b->true;
        Predicate<Bounds> support=b->true;
        check((boolean)path.invoke(null,body,.18,0d,clear,support),"clear supported path permits repulsion");
        check(!(boolean)path.invoke(null,body,.18,0d,(Predicate<Bounds>)(b->b.maxX()<.7),support),"wall in stopping corridor blocks repulsion");
        check(!(boolean)path.invoke(null,body,.18,0d,clear,(Predicate<Bounds>)(b->b.maxX()<.7)),"ledge in stopping corridor blocks repulsion");
        check(!(boolean)path.invoke(null,body,.18,0d,clear,(Predicate<Bounds>)(b->b.minX()<-.1||b.minX()>.1)),"unsupported gap before landing point blocks repulsion");
        check(!(boolean)path.invoke(null,body,100000d,0d,clear,support),"natural high-speed motion cannot trigger an unbounded world scan or dance jump");
        Class<?> guard=Class.forName("ru.gora.musicbattle.data.CrowdSafetyMath$FallGuard");
        Constructor<?> create=guard.getConstructor(long.class);
        Method update=guard.getMethod("update",long.class,boolean.class,boolean.class,boolean.class);
        Method absorb=guard.getMethod("absorb",boolean.class);
        Object g=create.newInstance(100L);
        check((boolean)update.invoke(g,100L,true,false,true),"same-tick grounded state does not erase freshly applied movement");
        check((boolean)update.invoke(g,101L,false,false,true),"guard remains during airborne displacement");
        check(!(boolean)absorb.invoke(g,false),"attacks/fire are never absorbed");
        check((boolean)update.invoke(g,10000L,false,false,true),"battle finish or long fall does not expire guard before landing");
        check((boolean)absorb.invoke(g,true),"first displacement fall damage is absorbed");
        check(!(boolean)absorb.invoke(g,true),"guard cannot protect a later natural fall");
        Object landed=create.newInstance(100L);
        check(!(boolean)update.invoke(landed,101L,true,false,true),"safe landing retires guard");
        check(!(boolean)absorb.invoke(landed,true),"landed mob regains ordinary fall damage");
        Object water=create.newInstance(100L);
        check(!(boolean)update.invoke(water,101L,false,true,true),"water ends falling episode");
        Object gone=create.newInstance(100L);
        check(!(boolean)update.invoke(gone,101L,false,false,false),"removed/dead/dimension-changed entity releases guard");
        continuousMotionDoesNotProtectAnUnrelatedFall(create, update, absorb);
        partialPushUsesAvailableSpace(body, clear, support);
        System.out.println("Crowd safety checks PASS: "+checks);
    }

    static void partialPushUsesAvailableSpace(Bounds body, Predicate<Bounds> clear, Predicate<Bounds> support) throws Exception {
        Object partial = push(body, 1, 0, -.08, b -> b.maxX() < .8, support);
        check(partial != null && number(partial,"x") > 0, "nearby wall leaves a smaller safe push instead of disabling repulsion");
        check(number(partial,"x") * 12 + body.maxX() < .8, "reduced impulse still has its full stopping distance checked");
        close(number(partial,"y"), -.08, "partial push never launches a mob upward");
        Object slide = push(body, 1, 1, 0, b -> b.maxX() <= .301, support);
        check(slide != null && number(slide,"z") > 0 && number(slide,"x") == 0, "blocked diagonal slides along a wall in an outward direction");
        check(push(body, 1, 0, 0, b -> b.maxX() <= .30001, support) == null, "fully blocked outward motion stays stopped");
        Object ledge = push(body, 1, 0, 0, clear, b -> b.maxX() < .6);
        check(ledge != null && number(ledge,"x") > 0 && number(ledge,"x") * 12 + body.maxX() < .6,
                "ledge permits only the stopping-safe part of a push");
    }

    static Object push(Bounds body, double x, double z, double y, Predicate<Bounds> clear, Predicate<Bounds> support) throws Exception {
        try {
            return policy.getMethod("safePush",Bounds.class,double.class,double.class,double.class,Predicate.class,Predicate.class)
                    .invoke(null,body,x,z,y,clear,support);
        } catch (NoSuchMethodException oldAllOrNothing) {
            Object velocity = policy.getMethod("velocity",double.class,double.class,double.class).invoke(null,x,z,y);
            return (boolean)policy.getMethod("safePath",Bounds.class,double.class,double.class,Predicate.class,Predicate.class)
                    .invoke(null,body,number(velocity,"x"),number(velocity,"z"),clear,support) ? velocity : null;
        }
    }

    static void continuousMotionDoesNotProtectAnUnrelatedFall(Constructor<?> create, Method update, Method absorb) throws Exception {
        Object relocated = create.newInstance(100L);
        observe(relocated, 0, 64, 0, .28, false);
        check(!observe(relocated, 100, 100, 0, .28, false), "same-dimension relocation immediately invalidates protection before Post tick");
        check(!(boolean)absorb.invoke(relocated,true), "relocated mob's unrelated landing is not absorbed");

        Object downwardTeleport = create.newInstance(100L);
        observe(downwardTeleport, 0, 64, 0, -.08, false);
        check(!observe(downwardTeleport, 0, 4, 0, -.08, false), "a downward teleport is not mistaken for a continuous fall");
        check(!(boolean)absorb.invoke(downwardTeleport,true), "damage hook rejects downward relocation without waiting for Post");

        Object forcedUp = create.newInstance(100L);
        observe(forcedUp, 0, 64, 0, -.3, false);
        check(!observe(forcedUp, 0, 63.7, 0, .6, false), "foreign upward force invalidates guard before the next movement tick");
        check(!(boolean)absorb.invoke(forcedUp,true), "foreign upward force cannot transfer protection to a later fall");

        Object lifted = create.newInstance(100L);
        observe(lifted, 0, 64, 0, -.3, false);
        check(!observe(lifted, 0, 65, 0, -.3, false), "unexpected upward relocation invalidates guard even if velocity is unchanged");

        Object levitating = create.newInstance(100L);
        observe(levitating, 0, 64, 0, .28, false);
        check(!observe(levitating, 0, 64.28, 0, .196, true), "levitation ends the displacement episode");
        check(!(boolean)absorb.invoke(levitating,true), "fall after levitation receives ordinary damage");

        Object normal = create.newInstance(100L);
        check(observe(normal, 0, 10000, 0, .28, false), "applied jump establishes the movement baseline");
        double y = 10000;
        double vertical = .28;
        for (int step = 1; step <= 1000; step++) {
            y += vertical;
            vertical = (vertical - .08) * .98;
            check(observe(normal, .1 * step, y, 0, vertical, false), "continuous gravity-driven fall remains protected at step " + step);
            check((boolean)update.invoke(normal,100L+step,false,false,true), "long continuing fall has no arbitrary timeout");
        }
        check(observe(normal, 100.1, y - .05, 0, 0, false), "collision-shortened final landing step stays protected");
        check((boolean)absorb.invoke(normal,true), "first impact of a genuinely continuous long fall is absorbed");

        Object invalid = create.newInstance(100L);
        observe(invalid, 0, 64, 0, 0, false);
        check(!observe(invalid, Double.NaN, 64, 0, 0, false), "non-finite position cannot retain protection");
        check(!observe(invalid, 0, 64, 0, 0, false), "invalidated guard cannot become active again");
    }

    // Before the regression fix, FallGuard has only lifecycle checks and ignores movement.
    // Keep that old behavior executable so RED demonstrates a real incorrectly absorbed fall.
    static boolean observe(Object guard, double x, double y, double z, double vertical, boolean levitating) throws Exception {
        try {
            return (boolean)guard.getClass().getMethod("observeMotion", double.class, double.class, double.class, double.class, boolean.class)
                    .invoke(guard, x, y, z, vertical, levitating);
        } catch (NoSuchMethodException oldLifecycleOnly) {
            return (boolean)guard.getClass().getMethod("update",long.class,boolean.class,boolean.class,boolean.class)
                    .invoke(guard,100L,false,false,true);
        }
    }
    static double number(Object obj,String field)throws Exception{return (double)obj.getClass().getMethod(field).invoke(obj);}
    static void close(double got,double expected,String name){check(Math.abs(got-expected)<1e-9,name+": "+got);}
    static void check(boolean good,String name){checks++;if(!good)throw new AssertionError(name);}
}
